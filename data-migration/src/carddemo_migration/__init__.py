"""CardDemo mainframe-extract decoder and Aurora PostgreSQL loader.

Purpose
-------
``carddemo_migration`` is the Python 3.13 extract-transform-load package that carries
CardDemo's data off the mainframe. It decodes the fixed-width VSAM, Db2 and IMS extract
files the COBOL baseline writes, bulk-loads each record set into the Aurora PostgreSQL
schema that owns it, stages the generation-dataset families into versioned object storage,
and then verifies the load three independent ways. It is the data half of the migration;
the Java services are the behaviour half, and the two meet at the record layouts described
under *Single-sourcing contract* below.

This module is the package entry point. It states the package's documentation, exposes
:data:`__version__`, and deliberately does nothing else -- every reason for that restraint
is recorded under *Design decisions (WHY)* below.

Package layout
--------------
``carddemo_migration.copybook``
    The record contracts and the three codecs that read them: ``layouts`` (offset, length
    and usage for every copybook field, declared exactly once), ``zoned`` (trailing-sign
    overpunch for ``USAGE DISPLAY`` numerics), ``packed`` (``COMP-3`` packed decimal) and
    ``ebcdic_codec`` (cp037 decode applied per fixed-width field, never per record, so
    sign bytes and packed nibbles are never routed through a text decoder).
``carddemo_migration.readers``
    Twelve fixed-width record readers, one per record layout: the eleven seed datasets
    (``usrsec``, ``account``, ``card``, ``customer``, ``xref``, ``dalytran``,
    ``transaction``, ``discgrp``, ``trancatg``, ``trantype``, ``tcatbal``) plus
    ``export_record``, the packed-decimal export layout that has no seed dataset of its
    own.
``carddemo_migration.loaders``
    ``aurora`` bulk-loads decoded rows into one owning schema at a time; ``s3_stage``
    stages dataset generations under the ``dt=``/``gen=`` prefix convention that replaces
    generation-data-group semantics.
``carddemo_migration.verify``
    The three mandatory post-load passes: ``row_counts`` per dataset, ``checksum`` per
    record, and ``money_parity`` against the source files. A load that reports success
    without the money-total pass is not evidence of anything, which is why verification is
    a first-class subpackage rather than an afterthought in a script.

Two modules sit at the package root beside this one: ``cli``, which exposes the
command-line entry points the batch staging step invokes, and ``config``, which resolves
runtime settings when a command runs rather than when a module is imported -- that timing
is what keeps this package importable with nothing configured.

Import and layering contract
----------------------------
* **Absolute imports only.** Every import inside this package is written in full from
  ``carddemo_migration`` -- never relative, and never reached by manipulating
  ``sys.path``. This is mechanically enforced rather than merely agreed: the sibling
  ``pyproject.toml`` selects ``TID252`` with ``ban-relative-imports = "all"``, so a
  relative import fails the build instead of waiting for a reviewer to notice it.
* **``carddemo_migration.copybook`` is standard library only.** The layouts and the three
  codecs import nothing outside the Python standard library, so they import and run on a
  bare checkout with no database driver and no AWS SDK present. Only ``loaders`` and
  ``verify`` may import third-party clients. This mirrors the discipline the reference
  codec under ``tests/helpers/`` states for itself, and here it is load-bearing rather
  than tidy: the codecs are the one place where a defect is silent -- it produces
  plausible numbers that are wrong -- so they must stay exercisable against known-answer
  vectors in isolation from anything that needs a credential or a reachable database.

Single-sourcing contract
------------------------
Record offsets, lengths and usages are declared once, in
``carddemo_migration.copybook.layouts``, and imported from there by every reader. Two
readers therefore cannot drift apart on the same layout: there is only one place to change
and one place to review. This is the Python analogue of compiling every COBOL program
against a single copybook include path, which is exactly how the baseline guaranteed the
same property.

The contract reaches across languages as well. The Java shared-kernel codecs
(``CopybookLayout``, ``FixedWidthCodec``, ``ZonedDecimalCodec``, ``PackedDecimalCodec``)
transcribe the same copybooks, so a decode performed here and a decode performed there
must agree field for field. A divergence between the two is not a style difference; it is
a silent data defect, because both sides would keep returning well-formed values.

Command-line entry point
------------------------
The published invocation is ``python -m carddemo_migration.cli``, which is also the form
the batch staging step issues as an argument list. The generated console script the
distribution installs is a convenience beside it, not a replacement for it.

Design decisions (WHY)
----------------------
Alternatives Considered:
    **This file is documented rather than empty.** The conventional alternative -- a
    zero-byte ``__init__.py`` used purely as a package marker -- is refused. It fails the
    project's explainability rule, which names a module entry point as an artifact that
    must carry a docstring, and it fails ruff's ``D104`` under the sibling
    ``pyproject.toml``, whose ``ignore`` list is empty and which explicitly declines to
    exempt ``__init__.py`` from that check. The substantive cost of an empty file is
    larger than the mechanical one: the import rule, the standard-library-only layering
    guarantee and the single-sourcing rule stated above have no other home in code, and a
    reader who opens the package root would find nothing telling them the rules exist.
Trade-offs:
    **No subpackage is imported here, and no name is re-exported.** A barrel that surfaced
    the codecs or the readers at the package root would shorten a handful of call sites.
    It is refused because ``import carddemo_migration`` is on the import path of every
    consumer, including the codec tests: importing ``loaders`` or ``verify`` from here
    would pull ``psycopg`` and ``boto3`` in behind them, so a copybook-only import would
    stop working on a bare checkout and the layering guarantee above would become false at
    the very point it is asserted. The accepted cost is that callers write the full module
    path they need, which is also what keeps the dependency direction visible in each
    importing file.
Assumptions:
    **Importing this package performs no configuration and touches nothing outside
    itself.** No logging is configured, no logging handler is installed, no client or
    session is constructed, no environment variable is read, no application file is opened
    and no network call is made. The assumption this protects is real: consumers import
    this package with no AWS credentials configured and no database reachable -- the codec
    tests do exactly that -- so a client constructed at import time would raise, and a
    logging configuration installed at import time would silently take over the root
    logger of whatever process imported it. The one exception is the read-only
    distribution-metadata lookup below, which is the mechanism the version decision
    requires and which cannot fail into an unhandled exception.
Assumptions:
    **This tree is a real distribution package; the reference test tree deliberately is
    not, and the two must not be made to match.** There is no ``__init__.py`` anywhere
    under ``tests/``: that suite resolves as a PEP 420 namespace package through
    ``PYTHONPATH`` pointing at the repository root. This package is the opposite by
    design -- an installable distribution under a src layout, discovered through
    ``where = ["src"]`` in the sibling ``pyproject.toml``, with an explicit documented
    ``__init__.py`` here and in each of the four subpackages. ``data-migration/src`` itself
    is a layout container and is correctly *not* a package. Anyone reconciling the two
    trees should leave both alone: deleting these files to match ``tests/`` would remove
    the packaging the container image installs, and adding files under ``tests/`` to match
    this tree would change how the parity oracle resolves its own imports.
"""

# WHY (Alternatives Considered): the plainer ``from importlib.metadata import version``
# was written first and then rejected, because it binds a name called ``version`` INTO
# this package's root namespace. That is a re-export this package does not intend and a
# specific trap: ``from carddemo_migration import version`` would then succeed and hand
# the caller the standard library's lookup FUNCTION, not the version string they were
# reaching for, and printing it yields a function repr rather than a number. Binding the
# module under a private alias instead leaves the package root with no importable public
# name at all, which is what the no-re-export contract in the docstring above claims.
import importlib.metadata as _metadata

# WHY (Assumptions): the metadata lookup below is keyed on the DISTRIBUTION name declared
# in the sibling pyproject.toml, which is hyphenated, whereas the import package this file
# belongs to is underscored. The two spellings can never be unified -- a hyphen is not a
# legal Python identifier -- so this constant is what ties them together, and mistaking one
# for the other is the way the lookup silently falls through to the sentinel. Metadata name
# normalisation would accept the underscored spelling equally well; the hyphenated form is
# used verbatim so that a reader holding this line against pyproject.toml compares the same
# characters instead of having to know that normalisation exists.
_DISTRIBUTION_NAME = "carddemo-migration"

# WHY (Trade-offs): a source tree that was never installed reports this sentinel instead
# of raising. That is the whole point -- a copybook-only import must not fail merely
# because nothing has been installed yet, which is the state a bare checkout and the
# codec tests both run in. The accepted cost is that a caller which logs the version can
# log an uninformative string, so the value is chosen to be unmistakable: "0+unknown" is a
# valid PEP 440 version, so a caller that parses or compares it will not raise, its local
# segment can never be read as a release identifier, and its "0" public segment sorts
# below every real release of this distribution.
_UNKNOWN_VERSION = "0+unknown"


def _resolve_version() -> str:
    """Resolve this package's version from the installed distribution metadata.

    Purpose
    -------
    Report the version of the ``carddemo-migration`` distribution that provides this
    import package, reading it from the metadata an installer wrote rather than from a
    literal held here.

    Alternatives Considered: the version could be stated as a literal in this module. It
    is not, because the sibling ``pyproject.toml`` already declares ``version`` statically,
    so a literal here would be a second place naming the same number and the two would
    eventually disagree -- the drift being invisible until something compared them.
    Reading the metadata keeps ``pyproject.toml`` the single source of truth and makes this
    module the read side of that one declaration. The inverse arrangement would be correct
    only if ``pyproject.toml`` declared ``dynamic = ["version"]`` and pointed at this
    module, in which case reading metadata from here would be circular; it does not.

    Alternatives Considered: the lookup could be deferred to first access through a
    module-level ``__getattr__``, which would remove even this much work from import. It is
    resolved eagerly instead, because a lazily produced ``__version__`` does not appear in
    the module's namespace for ``vars()``, ``dir()`` or static tooling to find, and the
    work being deferred is a read-only metadata lookup rather than anything that opens a
    connection.

    Parameters
    ----------
    None
        The distribution name is fixed by :data:`_DISTRIBUTION_NAME`; taking it as an
        argument would imply this package can report a version other than its own.

    Returns
    -------
    str
        The installed distribution's version string, or :data:`_UNKNOWN_VERSION` when this
        package is being imported from a source tree that was never installed.

    Raises
    ------
    None directly
        ``PackageNotFoundError`` is the only failure the metadata lookup raises for a
        distribution that is absent, and it is caught here and turned into the sentinel, so
        this function contributes no exception of its own. Anything else the lookup can
        fail with -- installed metadata that exists but cannot be read, for instance --
        propagates unchanged, and that is deliberate: a corrupt installation is a condition
        the caller has to see, not one a sentinel should hide behind a plausible-looking
        version string.
    """
    try:
        return _metadata.version(_DISTRIBUTION_NAME)
    except _metadata.PackageNotFoundError:
        # WHY (Assumptions): absence of the distribution is an ordinary, expected state
        # here rather than an error -- the package is imported directly from the src tree
        # during local iteration and in the codec tests, which the sibling pyproject.toml
        # supports by placing "src" on pytest's import path. Only this one exception is
        # caught, so a genuinely broken installation still surfaces.
        return _UNKNOWN_VERSION


__version__ = _resolve_version()

# The module's public surface is complete at the line above, so this is the point a reader
# looking for ``__all__`` reaches and finds none.
# WHY (Trade-offs): no ``__all__`` is declared, and the omission is deliberate rather than
# an oversight. A package-level ``__all__`` naming subpackages is not inert: measured
# against this interpreter, a plain ``import`` of a package leaves a subpackage named in
# ``__all__`` out of ``sys.modules``, but ``from <package> import *`` imports every name
# the list contains. Listing ``loaders`` and ``verify`` here would therefore give a star
# import the power to pull ``psycopg`` and ``boto3`` into a copybook-only process, which is
# precisely the layering guarantee stated in the docstring above. A hand-maintained list
# would additionally have to be kept in step with the four subdirectories by memory. The
# accepted cost is that the subpackages are discoverable only from the docstring's
# *Package layout* section, which names all four and describes what each one owns.
