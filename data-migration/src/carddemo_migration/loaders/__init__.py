"""Write decoded CardDemo records to Aurora PostgreSQL and object storage, behind one boundary.

Purpose
-------
``carddemo_migration.loaders`` is the WRITE side of the extract-transform-load package. Where
:mod:`carddemo_migration.copybook` answers where a field sits inside a fixed-width record and
what the bytes at that position mean, and :mod:`carddemo_migration.readers` turns an extract
file into decoded records, this subpackage puts those records where the target system expects
them: rows in the Aurora PostgreSQL schema that owns them, and bytes in the generation prefix
that replaces a generation data group. It is the last layer before the data leaves this
process, which is why the three passes in :mod:`carddemo_migration.verify` exist to check that
what arrived is what was sent.

This module is the subpackage entry point. It states the contract the modules below are held
to and publishes a curated selection of their names, so that
``from carddemo_migration.loaders import load_records`` is a stable spelling. It declares no
class and no function of its own beyond the two attribute hooks at the foot of the file, it
converts nothing, and it imports none of those modules until one of their names is asked for.

The two loaders
---------------
``aurora``
    Bulk-loads decoded records into one owning schema at a time, streaming rows through
    PostgreSQL's own server-side ``COPY`` rather than issuing a statement per row. This is the
    migrated form of the baseline's ``IDCAMS REPRO`` load steps -- the work
    ``app/jcl/ACCTFILE.jcl`` and its nine siblings each perform for one dataset. Its
    ``TARGETS`` registry declares one target per base-master record, mapping each decoded
    field to the column the owning service named for it; that mapping is declared rather than
    derived, because the transformation is not mechanical, and it is where the baseline's three
    misspelled field names are corrected. It is the ONLY module in this distribution permitted
    to import ``psycopg``, and it does so inside the single function that opens a connection
    rather than at module scope.
``s3_stage``
    Stages a dataset generation into versioned object storage beneath
    ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/``, which is the migrated form of
    generation-data-group behaviour: a baseline ``(+1)`` reference becomes a NEW generation
    prefix, a ``(0)`` reference resolves to the newest one that exists, and the newest
    configured count of logical generations is kept while the ones that roll off are scratched.
    It owns the inventory of generation families, and there are **TEN** of them. It transfers a
    dataset's bytes verbatim -- opened in binary and never decoded, transcoded, padded,
    stripped or newline-translated on the way through -- because record-boundary and
    sign-overpunch interpretation belongs to ``copybook`` and the readers, not here.

The supporting module
---------------------
``protected_columns``
    Not a loader, and published alongside them for the same reason ``readers`` publishes its
    two support modules: a caller assembling a load cannot complete one without it. It
    produces the ciphertext the two protected ``BYTEA`` column families require -- a card
    verification value, and a customer's national and government-issued identifiers -- so that
    ``aurora`` can load those records without this package ever holding a key of its own, the
    data key being fetched from AWS KMS by ALIAS through :mod:`carddemo_migration.config`.
    Reaching it by the same spelling as the loader it serves is what keeps a caller from
    discovering the dependency only when a load fails on a column it could not fill.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its
    own definition, in the module that owns it.

Returns
-------
None
    Importing binds names only. It imports NONE of the three modules below, opens no
    connection, constructs no client, reads no environment variable, configures no logging and
    reaches no network; each module is resolved on first access by :func:`__getattr__`.

Raises
------
AttributeError
    Raised by attribute access for a name this subpackage does not publish. Nothing else is
    raised from this module: unlike the sibling ``readers`` entry point it performs no
    inventory check at import, because the two registries it would compare -- ``aurora``'s
    load targets and ``s3_stage``'s generation families -- are declared inside the very
    modules this one declines to import.

Layering guarantees
-------------------
These are properties other modules, and the tests that check them, are entitled to rely on.
They are stated here because this is the boundary at which they hold.

* **Nothing here declares a layout.** No record offset, byte length, key geometry, record
  length, dataset name or field width appears in this file or is re-declared by the modules
  below. Every one of them lives exactly once, in
  :mod:`carddemo_migration.copybook.layouts`, and is imported from there. That single copy is
  the Python analogue of compiling every COBOL program against one copybook include path, and
  it is why two loaders cannot drift apart on the same record: there is one place to change
  and one place to review.
* **No endpoint, credential, connection string, bucket name, ARN or account identifier appears
  in source.** Every one is obtained from :mod:`carddemo_migration.config`, which resolves them
  when a command runs rather than when a module is imported, reading them from AWS Systems
  Manager Parameter Store and Secrets Manager. That timing is what makes a checkout of this
  repository inert on its own, and it is what turns "nothing sensitive committed" from a review
  habit into a structural property of the tree.
* **The third-party clients are confined by module.** ``psycopg`` belongs solely to ``aurora``;
  the AWS SDK is reached only along the ``s3_stage`` path, and only through ``config``. Neither
  leaks into ``carddemo_migration.copybook``, which is contractually standard-library-only so
  that the codecs stay exercisable against known-answer vectors on a bare checkout with no
  driver installed, no SDK installed and no credential configured. A codec defect is the one
  class of defect in this package that is silent -- it produces plausible numbers that are
  wrong -- so being able to reproduce one in isolation is load-bearing rather than tidy.
* **Absolute imports only.** Every import in this file and in the modules below is written in
  full from ``carddemo_migration``, including the imports of this package's own children. The
  ban is mechanical rather than agreed: the sibling ``pyproject.toml`` selects ``TID252`` with
  ``ban-relative-imports = "all"``, so ``from carddemo_migration.loaders.aurora import ...``
  is the required spelling and ``from .aurora import ...`` would fail the build rather than
  wait for a reviewer to object.
* **This subpackage does not own the schema.** It issues no ``CREATE SCHEMA``, ``CREATE ROLE``,
  ``GRANT``, ``REVOKE``, ``DELETE``, ``TRUNCATE`` or ``CREATE INDEX``.
  ``data-migration/sql/V0__schemas_and_roles.sql`` owns the schema and privilege topology, and
  the per-service Flyway migrations own the tables and their indexes. Two sources of truth for
  a schema is a state in which both can appear to work while disagreeing, so the loaders read
  that topology and never assert it.
* **``app/**`` is reference-only input, and that includes ``app/data/**``.** The extracts are
  the specification these loaders are held to, so nothing below modifies, re-encodes,
  normalises or rewrites one of them in place -- a loader that adjusted its own input would be
  destroying the evidence it is checked against. ``tests/**``, ``scripts/**`` and ``samples/**``
  are reference-only on the same terms.

The published surface
---------------------
:data:`__all__` is the contract. The inclusion criterion is the smallest set with which a
caller can resolve WHERE a decoded record loads, perform that load and read what it did, stage
an extract as a generation and read where it went, supply the ciphers those loads need for the
protected columns, and catch anything any of it raises -- all without reaching into a
submodule. Everything else the three modules publish stays reachable at its owner, which is the
spelling every existing consumer already uses, so curating withdraws nothing that was available
before.

``LOADER_MODULES``
    The three module names in this subpackage, in dependency order rather than alphabetical
    order: ``aurora``, ``s3_stage``, ``protected_columns``.
``aurora``, ``s3_stage``, ``protected_columns``
    The modules themselves, each reachable by attribute under its own stable name.

From ``aurora`` -- the database load:

``connect``
    Open a connection from settings ``config`` resolved. Where ``psycopg`` is imported.
``TableTarget``
    Where one record loads, and which column each of its fields becomes.
``target_for``, ``target_names``
    Resolve one load target by record name, and list the record names that have one. These are
    the accessors for that registry; the registry itself stays ``aurora.TARGETS``.
``Projection``
    How one decoded field's value becomes the value its column stores.
``LoadContext``
    The collaborators a load needs when its target declares a non-mechanical projection -- the
    ciphers below are supplied through this.
``prepare_record``
    Apply every projection a target declares, giving a record ready to become a row.
``load_records``
    Bulk-load decoded records into one table and commit. The entry point of the load.
``LoadOutcome``
    What one load did, distinguishing rows READ from rows the table GAINED. The distinction
    matters: they differ whenever a row was already present, so collapsing them would report a
    no-op load as a successful one.
``AuroraLoadError``
    Raised when a load cannot be performed or does not complete.

From ``s3_stage`` -- the generation staging:

``family``, ``family_names``
    Resolve one generation family by its dataset path segment, and list every registered family
    name. As above, the registry itself stays ``s3_stage.GENERATION_FAMILIES``, and so do the
    generation-number bounds and the default retention count.
``GenerationFamily``
    One baseline generation-data-group base and where its generations are staged.
``reserve_generation``
    Reserve the generation number this execution will write, durably and exactly once.
``stage_dataset_file``, ``stage_family_file``
    Stage a local extract's bytes verbatim as one generation, then enforce retention; the
    second resolves the family for you and delegates to the first.
``StagedObject``
    A staged extract together with its audit anchors.
``s3_client``
    Build the client staging goes through, from settings ``config`` resolved.
``GenerationRetentionError``
    An unsafe staging or generation-cleanup operation. The base of the two below, so a caller
    that does not care which failed catches this one.
``DatasetSourceError``
    A local extract that cannot be staged.
``GenerationDiscoveryError``
    The next generation number cannot be determined.

From ``protected_columns`` -- the ciphers two of those loads require:

``CardVerificationValueCipher``
    Produces the card verification value's protected column.
``CustomerIdentifierCipher``
    Produces the customer's national and government-issued identifier columns.
``KmsDataKeySource``
    Supplies the data key both ciphers use, fetched from AWS KMS by alias.
``ProtectedColumnError``
    Raised when a protected column cannot be produced.

Design decisions (WHY)
----------------------
Trade-offs:
    **The three modules are NOT imported at package import; the surface is resolved on first
    access instead.** This matches the sibling ``readers`` and ``verify`` entry points and is
    the opposite of what ``copybook`` does, and the three differ because their costs differ
    rather than because one of them is inconsistent.

    The cost was MEASURED against this interpreter rather than assumed, and the measurement
    corrects the reason one would expect. Importing all three eagerly does **not** pull in
    ``psycopg`` or ``boto3``: ``aurora`` imports ``psycopg`` inside its ``connect`` function,
    ``s3_stage`` never imports the AWS SDK at all and obtains its client from ``config``, which
    likewise imports ``boto3`` inside a function, and ``protected_columns`` is
    standard-library-only at module scope. What an eager barrel would actually cost is 234
    kibibytes of source across the three modules, taking ``import carddemo_migration.loaders``
    from 2 to 12 ``carddemo_migration`` modules and from 150 to 207 entries in ``sys.modules``
    -- and, wherever the optional ``ebcdic`` distribution is installed, 37 further code-page
    modules pulled in transitively through ``copybook``. None of that is wanted by a caller who
    named one dataset, and all of it is wanted by a caller performing a load, which is exactly
    the shape first-access resolution serves.

    The accepted cost is real and is why the lazy form is not free: a defect inside one module
    -- a syntax error, a bad import -- now surfaces on first ACCESS rather than at package
    import, so a process that never touches that module no longer fails. Two things bound it.
    ``carddemo_migration.cli`` imports all three at ITS module scope, so any command-line
    invocation still fails loudly on a broken module; and this subpackage's tests import each
    module by name. Were a module here ever to acquire an unguarded heavy dependency at module
    scope, this would stop being a cost decision and become a correctness one -- so the
    condition is named rather than left implicit.
Alternatives Considered:
    **A flat curated surface was chosen over re-exporting the modules alone.** The sibling
    ``readers`` package publishes only its module objects, and it has to: twelve readers each
    declare ``record_key`` and ``DROPPED_FIELD_NAMES``, so a flat surface there would bind the
    same name twelve times and let the last import silently decide which record a caller got.
    That collision was checked here and does not exist -- the three ``__all__`` declarations
    below are pairwise disjoint -- so a flat surface is total and unambiguous, and it is what
    lets ``from carddemo_migration.loaders import load_records`` work without the caller
    knowing which of three modules owns the name. The modules are published as well, under
    their own names, so nothing is reachable by only one route.
Alternatives Considered:
    **Relying on implicit module attributes instead of declaring :data:`__all__` was
    rejected.** A surface that changes shape whenever a module gains a helper is not a
    contract. The explicit list is also the input :func:`__getattr__` resolves against, so the
    declaration and the resolver check each other rather than agreeing by coincidence, and a
    name added to one without the other fails rather than half-working.
Assumptions:
    **Importing this package has no side effect whatever.** No connection is opened, no client
    or session is constructed, no environment variable is read, no logging handler is installed,
    no file is opened and no network call is made -- and, unlike ``readers``, not even an
    in-memory inventory check runs. The root ``carddemo_migration`` entry point publishes that
    promise for the whole distribution, and a side effect here would break it transitively.
    The assumption it protects is concrete: consumers import this tree with no AWS credentials
    configured and no database reachable, so a client constructed at import time would raise,
    and a logging configuration installed at import time would silently take over the root
    logger of whatever process imported it.
Assumptions:
    **``protected_columns`` is documented here even though it is not a loader.** The
    alternative was to describe this directory as the two loaders the migration plan names and
    leave the third module unmentioned. That was rejected because a package entry point is the
    docstring a reader consults INSTEAD of listing the directory, so one that describes a
    subset of its own contents is uniquely misleading -- it invites a second implementation of
    something already present, and it hides a dependency ``aurora`` cannot load two of its
    records without. Naming it, and saying plainly that it is not a loader, costs one section
    and removes both hazards.
Refactoring Rationale:
    **This file previously held a docstring and nothing else -- no re-exports and no
    :data:`__all__`.** That was defensible while it existed only to make the directory a
    regular package rather than an implicit namespace package, which is a real requirement:
    package discovery finds a regular package by this file and a namespace child only by
    falling back, so a subsequently added ``packages`` or ``exclude`` entry would have dropped
    this directory from the built wheel with every test in the checkout still passing, and the
    failure would then have arrived as ``ModuleNotFoundError`` inside a container. What the
    choice cost became clear once all three modules had landed: the boundary named two of them
    in prose, published none of them, and gave a caller no supported spelling short of the
    full module path, so every consumer either hard-coded that path or reached for a name the
    boundary never promised to keep.
"""

from __future__ import annotations

# WHY : Trade-offs: this import block is the whole of it -- the standard library only, plus the
#   one annotation-only name behind the type-checking guard below. NONE of this package's three
#   children is imported here; they are resolved by `__getattr__` instead, for the measured
#   reasons argued in the module docstring. `importlib` is what performs that resolution, and
#   `MappingProxyType` is what makes the export registry read-only.
# WHY : Assumptions: `Mapping` is imported for annotation only and stays behind the
#   type-checking guard, because `from __future__ import annotations` defers every annotation to
#   a string. Taking it at run time would import `collections.abc` for a name nothing evaluates.
import importlib
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Final

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from collections.abc import Mapping

# WHY : Alternatives Considered: the surface is DECLARED rather than left to whatever names
#   happen to be bound, and the reasoning is in the module docstring above: a surface that
#   changes shape whenever a module gains a helper is not a contract. This list is also the set
#   `__getattr__` answers for, so the declaration and the resolver are checked against each
#   other rather than agreeing by coincidence.
# WHY : Assumptions: the constant comes first and the remaining names follow in sorted order,
#   matching how the three sibling entry points in this distribution declare their own surfaces,
#   so all four read the same way.
__all__ = [
    "LOADER_MODULES",
    "AuroraLoadError",
    "CardVerificationValueCipher",
    "CustomerIdentifierCipher",
    "DatasetSourceError",
    "GenerationDiscoveryError",
    "GenerationFamily",
    "GenerationRetentionError",
    "KmsDataKeySource",
    "LoadContext",
    "LoadOutcome",
    "Projection",
    "ProtectedColumnError",
    "StagedObject",
    "TableTarget",
    "aurora",
    "connect",
    "family",
    "family_names",
    "load_records",
    "prepare_record",
    "protected_columns",
    "reserve_generation",
    "s3_client",
    "s3_stage",
    "stage_dataset_file",
    "stage_family_file",
    "target_for",
    "target_names",
]

# WHY : Assumptions: the three are published in DEPENDENCY order rather than alphabetically,
#   because the order carries information a reader needs: `aurora` is the load, `s3_stage` is
#   the staging that precedes a load from object storage, and `protected_columns` is neither --
#   it is the cipher supplier `aurora` reaches for on two of its eleven records. Sorting the
#   tuple would put the one module that is not a loader in the middle of the two that are.
LOADER_MODULES: Final[tuple[str, ...]] = ("aurora", "s3_stage", "protected_columns")

# WHY : Trade-offs: the values are (module, attribute) PAIRS rather than the objects themselves,
#   so this constant is inert and building it imports nothing at all. That is the whole
#   mechanism by which the deferral works: a mapping of imported objects would have to import
#   all three modules to be built, which is precisely the eager cost the module docstring
#   measures and declines.
# WHY : Assumptions: the entries are grouped by owning module and, within a group, follow the
#   order a caller uses them in rather than alphabetical order -- connect, then resolve a
#   target, then prepare, then load, then read the outcome, then the error. A reader scanning
#   this mapping is trying to learn the shape of a load, and alphabetical order would scatter
#   those steps.
# WHY : Assumptions: no attribute name is spelled differently here from the name its owning
#   module publishes. A rename at this boundary would give one object two public spellings and
#   make every future correction in the owning module a breaking change here as well.
# WHY : Assumptions: MappingProxyType makes it read-only at run time, so a caller cannot
#   redirect a published name to a different implementation by mutating a shared dict -- which
#   would be a silent, process-wide change to which code a load actually runs.
_EXPORTS: Final[Mapping[str, tuple[str, str]]] = MappingProxyType(
    {
        "connect": ("aurora", "connect"),
        "TableTarget": ("aurora", "TableTarget"),
        "target_for": ("aurora", "target_for"),
        "target_names": ("aurora", "target_names"),
        "Projection": ("aurora", "Projection"),
        "LoadContext": ("aurora", "LoadContext"),
        "prepare_record": ("aurora", "prepare_record"),
        "load_records": ("aurora", "load_records"),
        "LoadOutcome": ("aurora", "LoadOutcome"),
        "AuroraLoadError": ("aurora", "AuroraLoadError"),
        "family": ("s3_stage", "family"),
        "family_names": ("s3_stage", "family_names"),
        "GenerationFamily": ("s3_stage", "GenerationFamily"),
        "reserve_generation": ("s3_stage", "reserve_generation"),
        "stage_dataset_file": ("s3_stage", "stage_dataset_file"),
        "stage_family_file": ("s3_stage", "stage_family_file"),
        "StagedObject": ("s3_stage", "StagedObject"),
        "s3_client": ("s3_stage", "s3_client"),
        "GenerationRetentionError": ("s3_stage", "GenerationRetentionError"),
        "DatasetSourceError": ("s3_stage", "DatasetSourceError"),
        "GenerationDiscoveryError": ("s3_stage", "GenerationDiscoveryError"),
        "CardVerificationValueCipher": ("protected_columns", "CardVerificationValueCipher"),
        "CustomerIdentifierCipher": ("protected_columns", "CustomerIdentifierCipher"),
        "KmsDataKeySource": ("protected_columns", "KmsDataKeySource"),
        "ProtectedColumnError": ("protected_columns", "ProtectedColumnError"),
    }
)


def __getattr__(name: str) -> Any:  # noqa: ANN401 -- the surface spans modules, types and functions
    """Resolve a published module or entry point by attribute, importing it on first access.

    Purpose
    -------
    Make everything this subpackage documents reachable after ``import
    carddemo_migration.loaders`` alone, without the package importing all three of its modules
    to make that true. This hook is the mechanism behind the deferral the module docstring
    argues for.

    Parameters
    ----------
    name : str
        The attribute being looked up. Only the three module names in :data:`LOADER_MODULES`
        and the curated entry points in :data:`__all__` resolve; anything else is reported as
        missing. The comparison is exact and case-sensitive.

    Returns
    -------
    Any
        The imported module when ``name`` is one of the three module names, otherwise the
        class, function or constant that the owning module publishes under that name. The
        annotation is deliberately broad because the published surface genuinely spans all
        three kinds; narrowing it would require a lie about one of them.

    Raises
    ------
    AttributeError
        If ``name`` is not part of the published surface. The message names the package rather
        than only the attribute, because a mistyped loader name is otherwise reported
        identically to a mistyped attribute on a loader.
    """
    # WHY : Assumptions: the module names are tested BEFORE the curated entries, so
    #   `loaders.aurora` continues to mean the MODULE and can never be shadowed by an entry that
    #   happens to share a name. The two sets are disjoint today; ordering the checks means a
    #   future entry cannot silently change what an existing spelling refers to.
    if name in LOADER_MODULES:
        return importlib.import_module(f"{__name__}.{name}")
    # WHY : Assumptions: only names in the published set are resolved, so this hook cannot be
    #   used to import an arbitrary sibling module by attribute. A permissive hook that
    #   forwarded every name to importlib would turn a typo into an ImportError from deep inside
    #   the import machinery, and would make any module that happened to sit in this directory
    #   reachable as part of the public surface.
    # WHY : Assumptions: Python calls this only after ordinary attribute lookup has already
    #   failed, so a name already resolved -- by this hook or by an explicit `from ... import`
    #   -- is found in the package namespace and never reaches here. That is what makes each
    #   import happen at most once without any cache being written in this file.
    if name in _EXPORTS:
        module_name, attribute = _EXPORTS[name]
        return getattr(importlib.import_module(f"{__name__}.{module_name}"), attribute)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def __dir__() -> list[str]:
    """Return the published surface, so interactive completion matches the documented one.

    Purpose
    -------
    Report this subpackage's contract to ``dir()``, ``help()`` and a shell's completion, which
    would otherwise see only the subset of names the current session happens to have touched.

    Parameters
    ----------
    None
        Python calls this hook with no argument.

    Returns
    -------
    list[str]
        The sorted contents of :data:`__all__`.

    Raises
    ------
    None
    """
    # WHY : Assumptions: dir() is overridden for the same reason the reader and verification
    #   entry points override it -- a lazily resolved name is absent from this package's
    #   namespace until it is touched, so the default implementation would report a surface that
    #   GROWS as a session proceeds and would differ between two processes that had run
    #   different commands.
    return sorted(__all__)
