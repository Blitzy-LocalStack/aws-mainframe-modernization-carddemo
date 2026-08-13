"""Decode fixed-width CardDemo extracts into records, behind one package boundary.

Purpose
-------
``carddemo_migration.readers`` is the layer between the byte geometry of
:mod:`carddemo_migration.copybook` and the target-datastore loaders of
:mod:`carddemo_migration.loaders`. One module per record layout turns an extract file into
decoded records, and each module additionally publishes the privacy-safe renderings a
diagnostic may emit for the record it owns.

This module is the subpackage entry point. It publishes the complete inventory of readers, the
mapping from a record layout to the module that owns it, one dispatch registry per corpus with the
resolver that selects between them, and lazy access to every one of those modules by attribute.

The contract every reader is held to
------------------------------------
**One reader per record layout, at the documented record length.** No reader declares a layout,
an offset, a length or a ``USAGE`` width of its own. Every one of the twelve imports its geometry
from :mod:`carddemo_migration.copybook.layouts` and its value decoders from
:mod:`carddemo_migration.copybook.zoned`, :mod:`carddemo_migration.copybook.packed` and
:mod:`carddemo_migration.copybook.ebcdic_codec`. A reader therefore differs from its siblings only
in which record descriptor it names, which is what keeps twelve modules from drifting apart on one
record.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only. It imports NO reader module, opens no dataset, reads no
    environment variable and reaches no network; the twelve readers are resolved one at a time,
    on first access, by :func:`__getattr__` below.

Raises
------
LayoutError
    Raised at import by :mod:`carddemo_migration.copybook.layouts`, which this module imports for
    the layout names in :data:`DATASET_READERS`, if a declared layout is internally inconsistent
    or if either disclosure allowlist disagrees with the descriptors. That failure belongs to that
    module; nothing here re-raises or re-words it.
ValueError
    Raised at import by this module's own closing inventory check if the registry in
    :data:`DATASET_READERS` and the layouts declared by ``layouts`` disagree about which records
    have a reader. The check and the reason it is blunt are documented at the site.
AttributeError
    Raised by attribute access for a name this package does not publish.
KeyError
    Raised by :func:`reader_module` for a layout no reader owns, with a message that enumerates
    the layouts that do have one.
LayoutError
    Raised by :func:`dataset_reader` for an unknown corpus, a layout no reader owns, or a layout
    that publishes no entry point for the requested corpus. That function documents at its own
    definition why its failure is typed differently from :func:`reader_module`'s.

Dispatching by record and by corpus
-----------------------------------
:data:`DATASET_READERS` holds the twelve whole-extract EBCDIC entry points and
:data:`ASCII_DATASET_READERS` the ten whole-extract character entry points, both keyed by layout
name; :data:`CHARACTER_PATH_ABSENT_RECORDS` is the derived difference between them, and
:func:`dataset_reader` selects one callable from a layout name and a corpus name.

Assumptions: a caller that needs decoded records reaches them through :func:`dataset_reader` rather
than by building a reader from a layout with :mod:`carddemo_migration.readers.factory`. The two are
NOT interchangeable and the difference is a security boundary, not a convenience: a factory-built
reader carries the geometry and the suppression contract, and it carries none of the per-record
policy the twelve modules add on top -- ``card``'s ``ProtectedValue`` wrapper on the card
verification value, ``usrsec``'s A/U user-type domain, the processing-timestamp validation in
``dalytran`` and ``transaction``, and ``transaction``'s absent-source semantics. ``factory`` exists
to build a reader, and the twelve modules are what a caller should dispatch to.

The twelve readers, their copybooks and their record lengths
-----------------------------------------------------------
Every figure below is quoted from ``data-migration/README.md`` section 6.1, which is the
authoritative catalogue: the eleven base-master rows from its table, and the export row from the
closing prose and command block of the same section, because the export envelope has no base
master and so no row in that table. The eleven are additionally asserted at import by ``layouts``
against the ``IDCAMS DEFINE CLUSTER`` operands that created the file; the export length is
corroborated instead by exact division of its dataset, 250000 bytes over 500 records of 500.
Assumptions: these lengths appear here as DOCUMENTATION and nowhere in this module as a value.
Nothing below branches on a record length; ``layouts`` owns every one of them, and a second copy in
code is exactly the drift the single-sourcing rule exists to prevent.

===================  ==========  ==================  ==============  ======
Module               Copybook    Dataset             Layout name     RECLEN
===================  ==========  ==================  ==============  ======
``account``          CVACT01Y    ``ACCTDATA.PS``     ``ACCOUNT``        300
``card``             CVACT02Y    ``CARDDATA.PS``     ``CARD``           150
``customer``         CVCUS01Y    ``CUSTDATA.PS``     ``CUSTOMER``       500
``xref``             CVACT03Y    ``CARDXREF.PS``     ``XREF``            50
``dalytran``         CVTRA06Y    ``DALYTRAN.PS``     ``DALYTRAN``       350
``transaction``      CVTRA05Y    ``TRANSACT``        ``TRAN``           350
``tcatbal``          CVTRA01Y    ``TCATBALF.PS``     ``TCATBAL``         50
``discgrp``          CVTRA02Y    ``DISCGRP.PS``      ``DISGROUP``        50
``trantype``         CVTRA03Y    ``TRANTYPE.PS``     ``TRANTYPE``        60
``trancatg``         CVTRA04Y    ``TRANCATG.PS``     ``TRANCAT``         60
``usrsec``           CSUSR01Y    ``USRSEC.PS``       ``SECUSER``         80
``export_record``    CVEXPORT    ``EXPORT.DATA.PS``  ``EXPORT``         500
===================  ==========  ==================  ==============  ======

Note the arithmetic: **twelve modules over ELEVEN base-master record layouts, plus the export
layout.** The export record is the twelfth because ``CVEXPORT`` describes a multi-record envelope
that has no base master of its own, so ``layouts`` registers it separately rather than as one of
the eleven the dataset table lists.

What each reader owns
---------------------
Each publishes the same six decode entry points -- ``decode_``, ``iter_`` and ``read_`` for both
the ASCII seed form and the EBCDIC dataset form -- plus a key accessor and two masked renderings,
except where a record's own corpus makes an entry point meaningless, which is noted.

``account``
    The 300-byte account master of ``app/cpy/CVACT01Y.cpy``. Five ``PIC S9(10)V99`` money fields,
    which are the five ``NUMERIC(12,2)`` columns the money-total verification pass aggregates.
``card``
    The 150-byte card master of ``app/cpy/CVACT02Y.cpy``, whose primary account number and card
    verification value are the most closely held values in the migration. Carries the ``CARDAIX``
    alternate key.
``customer``
    The 500-byte customer master of ``app/cpy/CVCUS01Y.cpy``: the widest record in the corpus and
    the one holding name, address, national identifier, government-issued identifier and date of
    birth. Its ``CUST-FICO-CREDIT-SCORE`` is the corpus's one bounded score and projects to an
    ``int``.
``xref``
    The 50-byte card cross-reference of ``app/cpy/CVACT03Y.cpy``, which is also the ``CXACAIX``
    alternate access path. Its shipped ASCII seed is the one that relies on the trailing-pad
    tolerance: 36 data characters against a declared 50.
``transaction``
    The 350-byte posted transaction of ``app/cpy/CVTRA05Y.cpy``. The ONE record whose input may
    legitimately be absent -- no extract ships in either encoding, because the content is produced
    by the posting and backup pipeline -- so its file-taking entry points yield nothing for a
    missing source and refuse a path that exists but cannot be read.
``dalytran``
    The 350-byte daily transaction of ``app/cpy/CVTRA06Y.cpy``, the pre-posting feed, whose
    processing timestamp is blank on every shipped record.
``tcatbal``
    The 50-byte transaction-category balance of ``app/cpy/CVTRA01Y.cpy``, keyed by account,
    transaction type and transaction category.
``discgrp``
    The 50-byte disclosure group of ``app/cpy/CVTRA02Y.cpy``. Fifty-ONE records, not fifty: the
    extra row is the mandatory ``DEFAULT`` group the interest calculation falls back to.
``trantype``
    The 60-byte transaction type of ``app/cpy/CVTRA03Y.cpy``; seven records.
``trancatg``
    The 60-byte transaction category of ``app/cpy/CVTRA04Y.cpy``; eighteen records, each keyed by
    a type code and a category code, with a foreign key to the type.
``usrsec``
    The 80-byte security user of ``app/cpy/CSUSR01Y.cpy``. EBCDIC ONLY -- it ships in no ASCII
    form and publishes no ASCII entry point -- and the one record whose baseline holds a plaintext
    password, which this reader never decodes and the target never carries.
``export_record``
    The 500-byte export record of ``app/cpy/CVEXPORT.cpy``: a fixed envelope carrying one of five
    payload shapes, selected at run time by the envelope's own discriminator. EBCDIC only, because
    three of its regimes are not characters in any code page.

The two support modules
-----------------------
``factory``
    Builds a reader from a layout descriptor, so nine of the twelve are one layout constant plus a
    name rather than a thousand hand-written lines.
``source``
    The one hardened flat-file open and the two record-width guards, shared by ``factory`` and by
    eleven of the twelve readers. ``export_record`` is the measured exception: it publishes no
    character path, so it has no seed file to open, and it takes its record boundaries and width
    refusals from ``copybook``'s own export entry points instead.

Every reader streams
--------------------
Each reader's ``iter_`` and ``read_`` entry points are GENERATORS that yield one decoded record at
a time; only the ``decode_`` form takes a single record already in hand. Nothing here accumulates
a dataset in memory, so the 800-byte security file and a production extract of arbitrary size
follow the same code path and differ only in how long the caller iterates. Assumptions: this is
what lets the loaders bind one decoded row at a time into a single committed unit of work, and it
is why a reader may be pointed at an extract far larger than the shipped seed without any caller
changing shape.

Four ways the twelve are NOT uniform
------------------------------------
Everything else about the twelve is deliberately interchangeable, which makes these four the
facts a caller has to know. Each is stated at its own reader as well; they are collected here
because a caller who reads only this boundary would otherwise write a test that asserts the wrong
thing.

1. **``xref`` right-pads a short row.** ``app/data/ASCII/cardxref.txt`` carries 36 data bytes per
   line, not 50: the 14-byte trailing ``FILLER`` that ``CVACT03Y`` declares is simply omitted from
   the shipped seed. The EBCDIC form and every ``tests/fixtures/**`` copy carry the full 50. A
   short row is right-padded with spaces before any field is extracted; an over-long row is still
   refused, because padding on the right cannot move a field that exists whereas truncating an
   over-long row would move every field after the cut.
2. **``transaction`` has no seed dataset.** No ``TRANSACT`` extract ships in either encoding, so an
   absent or zero-row input is a NORMAL state for this one reader rather than an error. Its
   ``HAS_COMMITTED_SEED_DATASET`` flag says so in code, and a caller that treats an empty result as
   a defect will report a working reader as broken.
3. **``usrsec`` and ``export_record`` are EBCDIC-only.** Neither publishes a ``decode_ascii_``,
   ``iter_ascii_`` or ``read_ascii_`` entry point at all -- but for two different reasons, which is
   worth knowing before writing a test that expects one. ``usrsec`` COULD have a character path and
   is denied one: it ships in no ASCII form, and every transcode of it would be another copy of a
   plaintext password, so ``factory`` lists ``SECUSER`` among the records whose character path is
   withheld and the path is closed at both doors rather than at one. ``export_record`` could not
   have one at all: three of its five payload regimes are not characters in any code page, so a
   character entry point would have nothing to return.
4. **``usrsec`` drops the password field.** ``SEC-USR-PWD`` is decoded by nothing and rendered by
   nothing: the reader declares it suppressed rather than sensitive, which is a stronger statement
   than masking. This is a deliberate, documented security correction rather than a parity gap --
   the target ``auth.users`` table carries no password column, because identity moved to a managed
   user pool.

Standard library only
---------------------
Importing this package pulls in the Python standard library and :mod:`carddemo_migration.copybook`
and nothing else. ``psycopg``, ``boto3``, ``botocore``, the ``ebcdic`` distribution and the sibling
:mod:`carddemo_migration.config` are all excluded here and in all fourteen modules below, so this
subpackage and every reader in it import and run on a bare checkout with no database driver, no
AWS SDK and no credential configured. ``config`` may reach an AWS client, which is why it is named
in that exclusion rather than left out of it by accident.

The guarantee is about THIS package and says nothing about the rest of the distribution, which is
a narrowing: an earlier wording added that only ``loaders`` and ``verify`` may import a third-party
client, and that was simply untrue -- ``config`` constructs the AWS clients and ``credentials``
imports the database driver, both inside functions, and both are outside those two packages. A
boundary
claim is only worth stating where it holds, and where it holds here is that nothing on the
copybook-and-readers path imports a service client at all, at module scope or inside a function.
That is what makes the codecs exercisable against known-answer vectors on a bare checkout, and a
codec defect is the one class of defect in this package that is silent -- it produces plausible
numbers that are wrong -- so reproducing one in isolation is load-bearing rather than tidy.

Two meanings of "EBCDIC"
------------------------
The word names two entirely distinct things, and conflating them produces a value of the right
width and the wrong content -- which every downstream length check then passes. In
:mod:`carddemo_migration.copybook.zoned` it names a SIGN CONVENTION, the trailing overpunch whose
characters are themselves ASCII-printable. In :mod:`carddemo_migration.copybook.ebcdic_codec` it
names a CHARACTER ENCODING, the cp037 code page. A field may be subject to one, the other, both or
neither: the ASCII seeds carry overpunched signs with no cp037 anywhere, and the ``.PS`` datasets
carry both at once. A reader's ``*_ascii_*`` and ``*_ebcdic_*`` entry points are named for the
CORPUS they read, so both trios go through the overpunch tables and only the second goes through
cp037. That distinction is owned by ``copybook`` and is restated here because the entry-point
names are where a caller meets it.

Import discipline
-----------------
Every import in this file, and in all fourteen modules below, is absolute and written in full from
``carddemo_migration`` -- including the imports of this package's OWN children, which are spelled
``carddemo_migration.readers.<module>`` rather than as relative imports. The ban is mechanical
rather than agreed: the sibling ``pyproject.toml`` selects ``TID252`` with
``ban-relative-imports = "all"``, so ``from .account import ...`` written here would fail the build
rather than wait for a reviewer.

Relationship to the baseline
----------------------------
``app/**`` is reference-only input, and that includes ``app/data/**``. Every module below opens
those extracts read-only and never modifies, re-encodes, normalises or rewrites one of them in
place: they are the specification these decoders are held to, so a reader that adjusted its own
input would be destroying the evidence it is checked against. ``tests/**``, ``scripts/**`` and
``samples/**`` are reference-only on the same terms.

Design decisions (WHY)
----------------------
Assumptions:
    **This file exists with content for two independent reasons, and neither is boilerplate.**
    First, packaging: this directory had no ``__init__.py`` at one point, which made
    ``carddemo_migration.readers`` an implicit NAMESPACE package while its siblings ``copybook``
    and ``loaders`` were regular packages -- measured rather than assumed, because
    ``importlib.import_module("carddemo_migration.readers").__file__`` was ``None`` where both
    siblings reported a real path. Setuptools discovery finds a regular package by this file and a
    namespace child only by falling back, so a later ``packages`` or ``exclude`` entry would have
    dropped this directory from the built wheel with every test in the checkout still passing; the
    failure would then arrive as ``ModuleNotFoundError`` inside a container. It is also what makes
    the absolute path ``carddemo_migration.readers.<module>`` resolve as a regular package member.
    Second, documentation: a package entry point is the first artifact kind the project's
    explainability rule names, so a documented one is required rather than preferred, and ruff's
    ``D104`` enforces it under an ``ignore`` list that is deliberately empty and a
    ``per-file-ignores`` table that deliberately does not exist. A zero-byte ``__init__.py`` --
    the reflex -- would fail that gate rather than merely be terse.
Trade-offs:
    **The TWELVE readers are imported eagerly at package import, and nothing else is.** The two
    support modules -- ``factory`` and ``source`` -- are resolved on first access, and in practice
    they are bound anyway because every reader imports ``source``; the hook exists so that the
    spelling ``readers.factory`` works whether or not something else has pulled it in.

    The cost was measured rather than assumed: the twelve readers are 712 kibibytes of source, and
    importing them takes ``import carddemo_migration.readers`` from the two-module copybook floor
    to the full reader layer. Nothing HEAVIER hides behind them -- each is
    standard-library-plus-``copybook``, so no database driver, no AWS SDK and no optional
    distribution is pulled in, which is the property the "standard library only" section above
    states and a child-interpreter probe in the suite checks.

    Refactoring Rationale: an earlier revision deferred all fourteen and made
    :data:`DATASET_READERS` map to module NAME strings so the registry could be built without
    importing anything. Two things were wrong with that, and the second is why it changed. It cost
    the dispatch mapping its usefulness: a caller holding a record name got a string back and
    needed a second call to turn it into something callable, so the "dispatch mapping" dispatched
    nothing. And it deferred every reader's import errors to first ACCESS, so a syntax error or a
    bad import in a reader no consumer touched did not fail at all -- while the argument for the
    deferral rested on a 781-kilobyte figure that included this file and the two support modules
    every reader imports regardless. Importing the twelve is what lets :data:`DATASET_READERS` hold
    the reader FUNCTIONS themselves, which is the contract this package's plan states, and it turns
    a broken reader into an immediate ``ImportError`` from the package rather than a deferred
    surprise. Were a reader ever to acquire a heavier dependency -- a service client, an optional
    distribution at module scope -- this would stop being a cost decision and become a correctness
    one, so the condition is named rather than left implicit. Three checks bound it from the other
    side: ``data-migration/tests/test_readers.py`` invokes every dispatch value,
    ``data-migration/tests/test_package_surfaces.py`` asserts :data:`READER_MODULES` against this
    directory's actual contents in BOTH directions, and the closing check in this file asserts the
    registry's keys against the layouts ``layouts`` declares.
Alternatives Considered:
    **Re-exporting each reader's own entry points at this level, flat, was evaluated and is
    impossible rather than merely undesirable.** It was measured over the twelve ``__all__``
    declarations, not estimated: ``DROPPED_FIELD_NAMES`` is published by all TWELVE,
    ``LOADED_FIELDS`` and ``record_key`` by ELEVEN each, ``is_normalized_timestamp_field`` by
    THREE, and ``DETERMINISTIC_FIELD_NAMES``, ``NORMALIZED_TIMESTAMP_FIELD_NAMES`` and
    ``SUPPRESSED_FIELD_NAMES`` by TWO each. A flat surface would bind each of those names as many
    times as it is declared, so ten of the eleven ``record_key`` functions would simply be
    unreachable and the last import to execute would silently decide which record a caller got.
    What is re-exported instead is the twelve reader MODULES under their stable names, which is a
    total surface with no collision: ``readers.account.record_key`` and ``readers.card.record_key``
    are both reachable and cannot be confused. The two support modules are published the same way,
    so every module in this directory is reached by one spelling. :data:`DATASET_READERS` then
    carries the one entry point per reader that a dispatching caller actually wants -- the
    whole-extract EBCDIC reader -- keyed by record name, where a flat re-export of those twelve
    functions would have collided on nothing but would also have told a caller nothing about which
    record each one reads.
Alternatives Considered:
    **Relying on implicit module attributes instead of declaring :data:`__all__` was evaluated and
    rejected.** A surface that changes shape whenever a module gains a helper is not a contract,
    and ``cli.py`` declares a dependency on this exact file, so what it may import has to be
    stated rather than discovered. The explicit list is also what makes the remaining lazy
    resolution checkable: ``__getattr__`` answers only the two support-module names, both of which
    are in the published set, so the declaration and the resolver are verified against each other
    by the suite rather than by reading.
Assumptions:
    **The registry is keyed on the caller's own dataset vocabulary, not on an internal one.**
    ``cli.py`` resolves its ``--dataset`` argument through ``layouts.layout()``, and
    ``data-migration/README.md`` records that ``--dataset`` IS the record-layout identifier
    ``list-datasets`` reports -- so the layout name is what every caller already holds, including
    the batch chain's per-dataset staging step, which enumerates one branch per dataset from that
    same list. Keying this mapping on anything else would put a second vocabulary in the tree for
    one set of records. The two sets OVERLAP rather than coincide, and the mapping site records each
    deliberate extra, because a near-match invites the assumption that they are the same set. A
    file-name key was rejected for the same reason from the other direction: the one record ships as
    both ``app/data/ASCII/cardxref.txt`` and ``app/data/EBCDIC/AWS.M2.CARDDEMO.CARDXREF.PS``, so a
    file-name key would need two entries per record and would answer differently depending on which
    corpus a caller happened to name.
Assumptions:
    **The four non-uniformities are restated here as well as at their own readers.** The cost is
    duplication with four module docstrings, and it is accepted because the alternative is worse
    than duplication: a caller who reads only this boundary would assert that ``transaction``
    returns rows, that ``usrsec`` has an ASCII path, or that ``xref`` refuses a 36-byte line, and
    each of those assertions describes a working reader as broken. One place to learn what is not
    uniform is worth four restatements.
Assumptions:
    **Importing this package has no side effect beyond binding names, importing the twelve readers
    and one in-memory check.**
    The root ``carddemo_migration`` entry point imports no subpackage at all and promises exactly
    that, and a side effect here would break the promise transitively -- importing a reader to
    decode one local file must not configure logging, construct a client, read an environment
    variable or touch the filesystem. The one thing that does run at import is the closing
    inventory comparison, which reads only names already in memory; its bluntness is argued at the
    site.
Refactoring Rationale:
    **This file used to document three readers, re-export nothing and declare no ``__all__``.**
    That was a deliberate choice at the time -- it existed to make the directory a regular package
    -- and what the choice cost became clear once all twelve readers had landed. A caller holding a
    dataset name had no way to reach the reader that owns it except by knowing the module name, and
    the mapping from a record to its module is not guessable: ``TRAN`` is read by ``transaction``,
    ``DISGROUP`` by ``discgrp``, ``TRANCAT`` by ``trancatg`` and ``SECUSER`` by ``usrsec``. So every
    consumer either hard-coded that mapping or guessed at it, which is one mapping in several
    places, and the package boundary documented a third of its own contents.
"""

from __future__ import annotations

# WHY : Trade-offs: the TWELVE readers are imported here, eagerly, and the two support modules are
#   not -- the module docstring argues the cost and the two mistakes the earlier deferral made. The
#   imports are written absolutely, from `carddemo_migration.readers`, including these imports of
#   this package's own children: `TID252` bans the relative spelling, and the absolute form of a
#   self-import resolves correctly because importing a submodule binds it on the partially
#   initialised parent before the `from` clause is satisfied.
# WHY : Assumptions: `importlib` is still imported, because `factory` and `source` remain resolved
#   on first access and `reader_module` answers for a record whose reader is already bound. It is
#   standard library, so it costs nothing an interpreter has not already paid.
# WHY : Assumptions: `Mapping` and `Callable` are imported for annotation only and stay behind the
#   type-checking guard, because `from __future__ import annotations` defers every annotation to a
#   string. Taking them at run time would import `collections.abc` for names nothing evaluates.
import importlib
from types import MappingProxyType, ModuleType
from typing import TYPE_CHECKING, Final

from carddemo_migration.copybook.layouts import (
    EXPORT_HEADER_LAYOUT,
    LayoutError,
    base_master_names,
)
from carddemo_migration.readers import (
    account,
    card,
    customer,
    dalytran,
    discgrp,
    export_record,
    tcatbal,
    trancatg,
    transaction,
    trantype,
    usrsec,
    xref,
)

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    import pathlib
    from collections.abc import Callable, Iterator, Mapping

# WHY : Alternatives Considered: the surface is DECLARED rather than left to whatever names happen
#   to be bound, and the reasoning is in the module docstring above: a surface that changes shape
#   whenever a module gains a helper is not a contract, and `cli.py` names this exact file as a
#   dependency. The list is also the input `__getattr__` resolves against, so the declaration and
#   the resolver check each other rather than agreeing by coincidence.
# WHY : Assumptions: the three constants come first and the fourteen module names follow in sorted
#   order, matching how the sibling `copybook` package declares its own surface, so both entry
#   points in this distribution read the same way. `reader_module` sorts among them because it is a
#   published callable rather than a constant.
# WHY : Refactoring Rationale: `reader_module` was absent from this list while being public, used
#   by `cli.py` and `verify/row_counts.py`, documented in the module docstring above, and added to
#   `__dir__` by hand. A surface declared in two places disagreed in exactly the way that matters:
#   `from carddemo_migration.readers import *` did not bring in the one callable a dispatching
#   caller needs, and `__dir__` had to compensate for the omission rather than derive from the
#   declaration.
__all__ = [
    "ASCII_DATASET_READERS",
    "CHARACTER_PATH_ABSENT_RECORDS",
    "DATASET_READERS",
    "READER_MODULES",
    "SUPPORT_MODULES",
    "account",
    "card",
    "customer",
    "dalytran",
    "dataset_reader",
    "discgrp",
    "export_record",
    "factory",
    "reader_module",
    "ships_committed_extract",
    "source",
    "tcatbal",
    "trancatg",
    "transaction",
    "trantype",
    "usrsec",
    "xref",
]

# WHY : Assumptions: the mapping is keyed by the LAYOUT name -- the identifier
#   `carddemo_migration.copybook.layouts` spells, that `list-datasets` prints and that `cli.py`
#   names `--dataset` -- so this registry speaks the vocabulary every caller already holds rather
#   than an internal one. The full argument, including why a dataset FILE name was rejected, is in
#   the module docstring above.
# WHY : Assumptions: this registry and the set `--dataset` accepts OVERLAP on the eleven base
#   masters and each carries one deliberate extra, which is worth stating because the near-match
#   invites the assumption that they are the same set. `EXPORT` is here and not there because
#   `layouts` registers the export envelope separately rather than in the resolvable registry, so a
#   caller reaches that reader through this mapping rather than through `--dataset`. The three
#   derived layouts are there and not here because each is an OUTPUT of the migrated pipeline, so
#   `--dataset` can name one while no reader owns it -- which is precisely the case `reader_module`
#   raises for rather than answering with nothing.
# WHY : Refactoring Rationale: this mapping exists because four of the twelve pairs are not
#   guessable by transformation. `TRAN` is read by `transaction`, `DISGROUP` by `discgrp`,
#   `TRANCAT` by `trancatg` and `SECUSER` by `usrsec` -- the module names follow the shipped
#   dataset spellings while the layout names follow the copybook record names, and neither is
#   derivable from the other. A caller deriving the module name from the layout name gets eight of
#   twelve right, which is the worst possible outcome: it works in development against the records
#   somebody tested and fails on the four nobody did.
# WHY : Refactoring Rationale: the values are the readers' whole-extract EBCDIC entry points --
#   CALLABLES -- where an earlier revision made them module NAME strings so the mapping could be
#   built without importing anything. That earlier form left the mapping unable to dispatch: a
#   caller holding a record name received a string and had to make a second call to obtain
#   something it could invoke, so "dispatch mapping" described a lookup table that dispatched
#   nothing. The plan for this file states that every value is callable, and this is that.
# WHY : Assumptions: the EBCDIC entry point is the one chosen, and it is chosen because it is the
#   only entry point ALL TWELVE readers publish. `usrsec` ships in no ASCII form -- transcoding it
#   would be another copy of a plaintext password -- and three of `export_record`'s five payload
#   regimes are not characters in any code page, so neither has an ASCII path to name here. A
#   mapping missing two of twelve entries would not be a dispatch table, and a mapping whose value
#   was a PAIR of entry points would push the corpus choice onto every caller when the binary `.PS`
#   extracts are what the cutover actually reads. Each reader's ASCII entry point stays reachable at
#   its own module, which is the spelling the scenario tests already use.
# WHY : Trade-offs: the values are bound FUNCTIONS rather than the modules that hold them, so a
#   caller can invoke a value directly without knowing the entry point's name -- which differs per
#   record: `read_ebcdic_accounts`, `read_ebcdic_card_xrefs`, `read_ebcdic_security_users`. The
#   modules stay published under their own names for everything else a caller needs from one, and
#   `reader_module` still answers "which module owns this record" for the two consumers that ask.
# WHY : Assumptions: no record length, offset or field width appears in this mapping. Those belong
#   to `carddemo_migration.copybook.layouts`, which every reader resolves them from, and a second
#   copy here is exactly the drift single-sourcing exists to prevent -- the copy would keep
#   answering plausibly after the original was corrected. The lengths are documented in the table
#   above, which is prose a reader consults rather than a value this module branches on.
# WHY : Assumptions: MappingProxyType makes it read-only at run time, so a caller cannot redirect a
#   record to a different reader by mutating a shared dict -- which would be a silent, global change
#   to which decoder a dataset is read with.
DATASET_READERS: Final[Mapping[str, Callable[[pathlib.Path], Iterator[Mapping[str, object]]]]] = (
    MappingProxyType(
        {
            "ACCOUNT": account.read_ebcdic_accounts,
            "CARD": card.read_ebcdic_cards,
            "CUSTOMER": customer.read_ebcdic_customers,
            "XREF": xref.read_ebcdic_card_xrefs,
            "DALYTRAN": dalytran.read_ebcdic_daily_transactions,
            "TRAN": transaction.read_ebcdic_transactions,
            "DISGROUP": discgrp.read_ebcdic_disclosure_groups,
            "TCATBAL": tcatbal.read_ebcdic_category_balances,
            "SECUSER": usrsec.read_ebcdic_security_users,
            "TRANCAT": trancatg.read_ebcdic_transaction_categories,
            "TRANTYPE": trantype.read_ebcdic_transaction_types,
            EXPORT_HEADER_LAYOUT.name: export_record.read_ebcdic_export_records,
        }
    )
)

# WHY : Refactoring Rationale: the CHARACTER counterpart of the mapping above is now declared too,
#   because a caller that could reach only the EBCDIC entry point through this boundary had no way
#   to honour a `--encoding ascii` request except by building a GENERIC reader from the layout --
#   which is exactly what `cli.py` did, and it silently bypassed every policy the owning readers
#   enforce: `card`'s card-verification value came back as bare text instead of a `ProtectedValue`,
#   `usrsec`'s A/U user-type domain went unchecked, `dalytran` and `transaction` skipped their
#   processing-timestamp validation, and `transaction`'s absent-source semantics were replaced by
#   an ordinary open failure. Publishing the character entry points here is what lets a dispatching
#   caller pick a CORPUS without also picking a weaker decoder.
# WHY : Alternatives Considered: deriving each character entry point from its EBCDIC sibling's
#   `__name__` -- replacing the `ebcdic` segment with `ascii` -- was evaluated and rejected. It
#   would work for all ten today and it is the same class of mistake this package already refuses
#   for module names: a derivation that is right until one reader is spelled differently, and whose
#   failure is an `AttributeError` from inside a dispatch rather than a missing registry entry.
#   Declaring the ten costs ten lines and cannot be right for nine records and wrong for one.
# WHY : Assumptions: this mapping holds TEN entries against the twelve above, and the two absences
#   are deliberate and unlike each other. `SECUSER` COULD have a character path and is denied one,
#   because every transcode of that record is another copy of a plaintext password --
#   `factory.CHARACTER_PATH_WITHHELD_RECORDS` states the same refusal for the generic reader, so
#   the path is closed at both doors. `EXPORT` could not have one at all: three of its five payload
#   regimes are not characters in any code page. The two are kept distinguishable rather than
#   merged into one "no ASCII" set because the first is a policy that could be revisited and the
#   second is a property of the bytes that cannot.
ASCII_DATASET_READERS: Final[
    Mapping[str, Callable[[pathlib.Path], Iterator[Mapping[str, object]]]]
] = MappingProxyType(
    {
        "ACCOUNT": account.read_ascii_accounts,
        "CARD": card.read_ascii_cards,
        "CUSTOMER": customer.read_ascii_customers,
        "XREF": xref.read_ascii_card_xrefs,
        "DALYTRAN": dalytran.read_ascii_daily_transactions,
        "TRAN": transaction.read_ascii_transactions,
        "DISGROUP": discgrp.read_ascii_disclosure_groups,
        "TCATBAL": tcatbal.read_ascii_category_balances,
        "TRANCAT": trancatg.read_ascii_transaction_categories,
        "TRANTYPE": trantype.read_ascii_transaction_types,
    }
)

# WHY : Assumptions: the set is DERIVED from the two registries rather than written out, so a
#   reader that gains or loses a character entry point changes this set by construction. Writing
#   `{"SECUSER", "EXPORT"}` here would be a fourth statement of one fact -- alongside the two
#   registries and `factory.CHARACTER_PATH_WITHHELD_RECORDS` -- and the copy would keep reading
#   plausibly after the original changed.
CHARACTER_PATH_ABSENT_RECORDS: Final[frozenset[str]] = frozenset(DATASET_READERS) - frozenset(
    ASCII_DATASET_READERS
)

# WHY : Assumptions: the inventory is derived from the mapping above rather than written a second
#   time, so a reader added to one is present in the other by construction. Sorting it makes the
#   published order independent of the mapping's declaration order, which is the corpus order and
#   is the wrong order for a name lookup.
# WHY : Assumptions: each module name is read from its entry point's own `__module__` and reduced to
#   the last dotted segment, which is what keeps this inventory derived now that the mapping holds
#   functions rather than names. Reading `__module__` also makes the derivation self-correcting: an
#   entry pointing at a function from the wrong module reports that module here, and
#   `test_package_surfaces.py` compares this tuple against the directory's contents in both
#   directions, so the mistake surfaces as a mismatch rather than as a reader nobody dispatches to.
READER_MODULES: Final[tuple[str, ...]] = tuple(
    sorted({reader.__module__.rsplit(".", 1)[-1] for reader in DATASET_READERS.values()})
)

# WHY : Assumptions: the two support modules are published in their own tuple rather than folded
#   into READER_MODULES, because they are not readers and a caller iterating readers must not be
#   handed them. `factory` builds a reader from a layout; `source` holds the hardened open and the
#   width guards. Both are importable through the same lazy attribute path, because a test that
#   exercises the shared guards needs to reach them by name.
SUPPORT_MODULES: Final[tuple[str, ...]] = ("factory", "source")

# WHY : Refactoring Rationale: only the two SUPPORT modules are resolved lazily now. The twelve
#   readers are imported at the top of this file, so they are already bound as attributes of this
#   package and ordinary attribute lookup finds them without `__getattr__` being called at all --
#   which is why they are removed from this set rather than left in it harmlessly: a set claiming to
#   list what is resolved lazily and naming twelve names that never reach the resolver would be a
#   third description of the import policy, disagreeing with the other two.
_LAZY_MODULES: Final[frozenset[str]] = frozenset(SUPPORT_MODULES)


def reader_module(layout_name: str) -> ModuleType:
    """Import and return the reader module that owns one record layout.

    Purpose
    -------
    Turn a layout name into the module that decodes it, so a caller holding a record identity does
    not have to hold the mapping to a module name as well. This is the one callable that resolves
    an entry in :data:`DATASET_READERS`, and it is the surface ``cli.py``'s per-dataset
    subcommands reach the twelve readers through.

    Parameters
    ----------
    layout_name : str
        A layout name as :mod:`carddemo_migration.copybook.layouts` spells it -- ``ACCOUNT``,
        ``SECUSER``, ``EXPORT`` and so on. The comparison is exact and case-sensitive, matching
        that module's own registry lookup.

    Returns
    -------
    ModuleType
        The reader module. It is already imported -- this package imports the twelve at import --
        so the call is a registry lookup plus a ``sys.modules`` lookup.

    Raises
    ------
    KeyError
        If no reader owns that layout. The message lists the layouts that do have one, because the
        three derived layouts -- the statement view, the reject stream and the interest
        transaction -- are deliberately readerless: each is an OUTPUT of the migrated pipeline
        rather than an extract to be read, so there is no source file to point a reader at.
    """
    # WHY : Trade-offs: an unknown layout raises rather than returning None, so a caller cannot
    #   accidentally treat "no reader" as "no records" -- which is the same conflation that made an
    #   unreadable path look like an absent dataset elsewhere in this package.
    # WHY : Alternatives Considered: a package-specific exception class deriving from KeyError was
    #   evaluated so that the failure would carry a name of its own. It was rejected because the
    #   failure IS a mapping miss and reads as one, because `cli.py` already catches KeyError from
    #   this call to answer "this layout has no reader" with an empty suppression set, and because
    #   what a maintainer needs at that moment is the closed set of names -- which the message
    #   carries. What is deliberately NOT done is letting the bare subscript error escape: that one
    #   reports only the missing key and would leave the caller to discover the valid set by
    #   reading this file.
    try:
        reader = DATASET_READERS[layout_name]
    except KeyError:
        raise KeyError(
            f"no reader owns layout {layout_name!r}; the layouts with a reader are"
            f" {sorted(DATASET_READERS)}. The derived layouts are readerless on purpose: each is"
            " produced by the migrated pipeline rather than shipped as an extract"
        ) from None
    # WHY : Assumptions: the module is resolved from the registered CALLABLE's own `__module__`
    #   rather than from a name string, so this function and the registry cannot disagree about
    #   which module owns a record -- they read the same object. `import_module` on an
    #   already-imported name is a `sys.modules` lookup, which is what makes reaching the module
    #   this way no more expensive than holding a second mapping to it.
    return importlib.import_module(reader.__module__)


def dataset_reader(
    layout_name: str, encoding: str
) -> Callable[[pathlib.Path], Iterator[Mapping[str, object]]]:
    """Return the owning reader's whole-extract entry point for one layout and one corpus.

    Purpose
    -------
    Give a dispatching caller the ONE callable that reads a named record from a named corpus with
    every validation and value type that record's own reader enforces -- so a caller choosing a
    corpus cannot end up choosing a weaker decoder as well.

    Parameters
    ----------
    layout_name : str
        A layout name as :mod:`carddemo_migration.copybook.layouts` spells it -- ``ACCOUNT``,
        ``SECUSER``, ``EXPORT`` and so on. The comparison is exact and case-sensitive.
    encoding : str
        ``"ebcdic"`` for the ``.PS`` dataset form or ``"ascii"`` for the seed text form. Any other
        spelling is refused rather than defaulted, because defaulting would silently read a binary
        extract as text or the reverse.

    Returns
    -------
    Callable[[pathlib.Path], Iterator[Mapping[str, object]]]
        The owning reader's ``read_ebcdic_*`` or ``read_ascii_*`` generator, taking the extract
        path and yielding one decoded record at a time.

    Raises
    ------
    LayoutError
        If the encoding is not one of the two corpora, if no reader owns the layout, or if the
        layout has no entry point for that corpus. The message names the closed set in each case.
    """
    # WHY : Alternatives Considered: this raises `LayoutError` where the sibling `reader_module`
    #   raises `KeyError` for the same missing layout, and the difference is deliberate rather than
    #   an inconsistency. `reader_module` answers an INTERNAL question -- which module owns this
    #   record -- and `cli.py` already catches its `KeyError` to mean "no reader, so no suppression
    #   set". This function answers a question that came straight from a command argument, and its
    #   failure has to reach the operator as a classified non-zero exit rather than a traceback:
    #   `LayoutError` is in `cli.py`'s `_STEP_ERRORS`, and it is the type the owning readers already
    #   raise when a corpus is refused, so a caller needs one `except` clause rather than two.
    registries = {"ebcdic": DATASET_READERS, "ascii": ASCII_DATASET_READERS}
    registry = registries.get(encoding)
    if registry is None:
        raise LayoutError(
            f"unknown extract encoding {encoding!r}; the corpora this package reads are"
            f" {sorted(registries)}"
        )
    reader = registry.get(layout_name)
    if reader is not None:
        return reader
    # WHY : Assumptions: the two refusals are reported SEPARATELY, because they send a maintainer to
    #   different places. "No reader owns this layout" means the name is one of the derived layouts
    #   the migrated pipeline produces, so there is no extract to read at all; "no character entry
    #   point" means the record is real and ships in one corpus only, which is a fact about that
    #   record's own contract. One shared message would send half of each audience to the wrong
    #   file.
    if layout_name not in DATASET_READERS:
        raise LayoutError(
            f"no reader owns layout {layout_name!r}; the layouts with a reader are"
            f" {sorted(DATASET_READERS)}. The derived layouts are readerless on purpose: each is"
            " produced by the migrated pipeline rather than shipped as an extract"
        )
    raise LayoutError(
        f"layout {layout_name!r} publishes no character entry point, so it cannot be read from the"
        f" ASCII seed form; the records without one are"
        f" {sorted(CHARACTER_PATH_ABSENT_RECORDS)}. Read it from its EBCDIC extract instead"
    )


def __getattr__(name: str) -> ModuleType:
    """Resolve a published submodule by attribute, importing it on first access.

    Purpose
    -------
    Let ``readers.factory`` and ``readers.source`` work after ``import carddemo_migration.readers``
    alone, without this package importing either to make that true. The twelve readers do not reach
    here: they are imported at the top of this file, so ordinary attribute lookup finds them.

    Parameters
    ----------
    name : str
        The attribute being looked up. Only the two support-module names resolve; anything else is
        reported as missing, including a reader name misspelled in a way ordinary lookup missed.

    Returns
    -------
    ModuleType
        The imported submodule.

    Raises
    ------
    AttributeError
        If ``name`` is not a module this package publishes. The message names the package rather
        than only the attribute, because a mistyped reader name is otherwise reported identically
        to a mistyped attribute on a reader.
    """
    # WHY : Assumptions: only names in the published set are resolved, so this hook cannot be used
    #   to import an arbitrary sibling module by attribute. A permissive hook that forwarded every
    #   name to importlib would turn a typo into an ImportError from deep inside the import
    #   machinery, and would make any module that happened to sit in this directory reachable as
    #   part of the public surface.
    # WHY : Assumptions: Python calls this only when ordinary attribute lookup has already failed,
    #   so a module already imported -- by this hook or by an explicit `from ... import` -- is found
    #   in the package's namespace and never reaches here. `import_module` binds the submodule as an
    #   attribute of this package as a side effect, so the resolution happens at most once per
    #   module with no cache written in this file: the binding IS the cache. That side effect is
    #   what a lazily returned CLASS or FUNCTION would not have, which is why the sibling `loaders`
    #   entry point -- whose surface is classes and functions -- writes its resolutions into its own
    #   namespace explicitly and this hook does not need to.
    if name in _LAZY_MODULES:
        return importlib.import_module(f"{__name__}.{name}")
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def __dir__() -> list[str]:
    """Return the published surface, so interactive completion matches the documented one.

    Purpose
    -------
    Report this package's contract to ``dir()``, ``help()`` and a shell's completion, which would
    otherwise see only the subset of modules the current session happens to have touched.

    Parameters
    ----------
    None
        Python calls this hook with no argument.

    Returns
    -------
    list[str]
        The sorted contents of :data:`__all__`, which now includes :func:`reader_module`.

    Raises
    ------
    None
    """
    # WHY : Refactoring Rationale: this derives from `__all__` ALONE, where it used to append
    #   `reader_module` by hand because that name was missing from the declaration. Two statements
    #   of one surface is what let them disagree; the name is in `__all__` now, so this hook has
    #   nothing of its own to add and cannot drift from the contract again.
    # WHY : Assumptions: dir() is overridden because the two support modules are absent from this
    #   package's namespace until something imports them, so the default implementation would report
    #   a surface that GROWS as a session proceeds -- importing a submodule binds it on the parent
    #   package, so the default answer only ever gains names, never loses them. An earlier comment
    #   here described that surface as SHRINKING, which is the opposite of what the import system
    #   does and would have sent a reader looking for a name being removed.
    return sorted(__all__)


# WHY : Assumptions: the inventory is proven against the layouts module at IMPORT rather than only
#   in the test suite, and the check is one comparison over names that are already in memory. Every
#   base master must have a reader, because each ships an extract that has to be loaded; the export
#   record is added separately because it is registered under its own layout rather than as a base
#   master. A base master left without a reader is a dataset the migration cannot read at all, and
#   discovering that at import is strictly better than discovering it when the load reaches that
#   dataset.
# WHY : Trade-offs: the failure is a ValueError raised from the package's own import, which is
#   blunt -- it stops every reader, not only the missing one. That is the correct bluntness here:
#   an incomplete reader inventory means the migration is incomplete, and a partial load is the
#   outcome this refuses to let happen quietly.
_EXPECTED_LAYOUTS: Final[frozenset[str]] = frozenset(base_master_names()) | {
    EXPORT_HEADER_LAYOUT.name
}
if frozenset(DATASET_READERS) != _EXPECTED_LAYOUTS:
    raise ValueError(
        "every base master and the export record must have a reader, but the reader registry and"
        f" the declared layouts disagree: registered without a layout"
        f" {sorted(frozenset(DATASET_READERS) - _EXPECTED_LAYOUTS)}, declared without a reader"
        f" {sorted(_EXPECTED_LAYOUTS - frozenset(DATASET_READERS))}"
    )


def ships_committed_extract(layout_name: str) -> bool:
    """Report whether a record layout has a committed seed extract that can be read.

    Purpose
    -------
    Publish the one authority for "does an extract for this record exist in the repository", so that
    every caller which has to decide whether to READ one branches on the owning reader's own
    declaration rather than on a list of layout names it maintains itself.

    Parameters
    ----------
    layout_name : str
        A record layout name owned by one of the readers.

    Returns
    -------
    bool
        True when the layout's reader does not declare itself unseeded.

    Raises
    ------
    KeyError
        Propagated from :func:`reader_module` if no reader owns the layout, with a message
        enumerating the layouts that do.
    """
    # WHY : Refactoring Rationale: this was a private helper inside
    #   `carddemo_migration.verify.money_parity`, and it is published here because a second caller
    #   appeared that must not disagree with the first. The combined verification gate must decide
    #   which registered datasets it may read an extract for, and the money pass must decide which
    #   columns it requires a source total for. Two copies of that rule is how the gate comes
    #   to offer an extract the pass refuses -- or, worse, to read a file that is not a full record.
    # WHY : Assumptions: the default is TRUE -- a reader that says nothing ships an extract -- and
    #   only a reader declaring the flag False is unseeded. `readers/transaction.py` is the one that
    #   declares it: no `app/data/ASCII/transact.txt` and no TRANSACT extract under
    #   `app/data/EBCDIC` exist, and `app/jcl/TRANFILE.jcl` primes that cluster from a single
    #   350-byte initializer record whose unpopulated fields do not decode as a whole transaction.
    #   Defaulting to False would make every other layout look unseeded, and an unseeded column is a
    #   PASSING not-comparable line -- so the wrong default would turn a forgotten extract into a
    #   clean bill of health.
    return bool(getattr(reader_module(layout_name), "HAS_COMMITTED_SEED_DATASET", True))
