"""Prove a load landed rather than assume it did: the three post-load verification passes.

Purpose
-------
``carddemo_migration.verify`` answers one question -- did what arrived in Aurora PostgreSQL
match the extract it came from -- and it answers it three independent ways, because no single
check can. Where :mod:`carddemo_migration.copybook` says what the bytes at a position mean,
:mod:`carddemo_migration.readers` turns an extract file into decoded records, and
:mod:`carddemo_migration.loaders` writes those records to the target, this subpackage reads both
sides back afterwards and compares them. It is the step that makes "the load succeeded" a
verified fact rather than an assertion.

This module is the subpackage entry point. It states the contract the three passes are held to
and publishes a curated selection of their names, so that
``from carddemo_migration.verify import compare_counts`` is a stable spelling. It declares no
pass of its own, compares nothing, and imports none of the three modules until one of their
names is asked for.

The three passes
----------------
``row_counts``
    Pass 1 -- did the right NUMBER of rows arrive. Compares the record count a source dataset
    holds against the row count its target table holds, per dataset, and for the whole migration
    in one shot by executing ``data-migration/sql/verify/row_counts.sql`` and judging the report
    it publishes. Catches the coarsest and most common failures: a load that stopped early, ran
    twice, or skipped records.
``checksum``
    Pass 2 -- is each row the row it should BE. Canonicalises every field of a record and
    digests it, then compares the digest of the decoded source record against the digest of the
    row read back, so a field corrupted in place is detectable even when every count agrees. It
    has no SQL counterpart deliberately: the canonical form IS the pass, and expressing it in
    SQL as well would put that one definition in two places that could then disagree.
``money_parity``
    Pass 3 -- is the MONEY the same money. Totals each money column exactly, at scale two, on
    both sides -- from the source bytes and from the database's own sum of the column they load
    into -- by executing ``data-migration/sql/verify/money_totals.sql``. It is the only pass
    that can catch a systematically mis-decoded sign.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition, in the module that owns it.

Returns
-------
None
    Importing binds names only. It imports NONE of the three pass modules, opens no connection,
    constructs no client, reads no environment variable, configures no logging and opens no
    file; each module is resolved on first access by :func:`__getattr__`.

Raises
------
AttributeError
    Raised by attribute access for a name this subpackage does not publish. Nothing else is
    raised from this module: every failure a verification can have belongs to a call, and each
    pass declares its own exception type at its own definition.

All three passes are mandatory
------------------------------
None of the three may be skipped, made optional by default, weakened, or switched off, and the
reason is mechanical rather than procedural.

A row count shows only that rows arrived, and in the right quantity. It is blind to every defect
that preserves the number of records -- and the defect this migration is most exposed to is
exactly of that shape. A zoned-decimal sign overpunch decoded with the wrong convention, or a
packed-decimal (``COMP-3``) nibble read wrongly, yields exactly the right number of rows with
the wrong money in them: the count agrees, every field keeps its declared width, and only the
sign or the scale of a value is wrong. Pass 1 would report a match on every line of such a load.
Only pass 3 compares the money itself, and only pass 2 can localise a single field corrupted in
place. A green pass 1 is therefore NECESSARY BUT NOT SUFFICIENT evidence of a good load.

This is why the migration plan makes verification a first-class deliverable rather than a
convenience: a load that "succeeded" without a money-total check is not evidence of anything.
Nothing in this subpackage accepts a flag that turns a check off, and no caller can ask for a
partial verdict. The combined invocation the sibling ``README.md`` contracts runs all three in
the fixed order 1, 2, 3 and stops at the first failure, so "verified" has one meaning.

Read-only by construction
-------------------------
Every pass only reads, and it is prevented from doing otherwise rather than merely intending it.
The two passes that reach the database connect as the least-privilege reporting role, which
holds ``USAGE`` and ``SELECT`` on the ``reporting`` schema and nothing else: that schema owns no
table of its own -- masked security-barrier views are the whole of its read surface -- and every
privilege the role might otherwise have inherited on the owning schemas is explicitly revoked by
``data-migration/sql/V0__schemas_and_roles.sql``. Each pass also checks the session's role
against the server before running its query, so a session that could write cannot certify the
load. No module here issues a ``CREATE``, ``ALTER``, ``DROP``, ``INSERT``, ``UPDATE``,
``DELETE``, ``TRUNCATE``, ``GRANT`` or ``REVOKE`` statement, and none writes to an extract file:
``app/**`` is reference-only input, including ``app/data/**``, so a pass that adjusted its own
input would be destroying the evidence it exists to check.

A binary outcome, not a graded return code
------------------------------------------
A verification either verifies or it does not. The outcome is binary at every level -- each
pass's own verdict, the exit status of each ``verify-*`` subcommand, and the result of the
combined invocation -- and any difference at all is a failure, including a money total that
differs by one cent.

The graded aggregate return-code rubric used by the COBOL parity oracle under ``tests/**`` (0
pass, 2 usage, 4 warn, 8 fail, 16 fatal, aggregating the worst code seen, in which a warn-level
4 is the documented green state because two baseline programs carry an unfixable defect in
immutable source) belongs to that oracle alone and is deliberately not imported here.

Deterministic, timestamp-free output
------------------------------------
What a pass renders is reproducible: two runs over unchanged data diff to nothing. Both shipped
queries guarantee it at the source by excluding every timestamp column from every predicate and
grouping, and by ordering on a sort key they do not project, so the row order cannot vary with
the plan the cluster chooses. Nothing in this subpackage stamps its own output with a clock
reading either. That is what lets an operator line-diff two reports instead of parsing them, and
it is why a timestamp anywhere in the rendering would defeat the property outright.

Import discipline
-----------------
Every import in this file and in the three modules below is absolute and written in full from
``carddemo_migration`` -- never relative, and never reached by manipulating ``sys.path``. The
ban is mechanical rather than agreed: the sibling ``pyproject.toml`` selects ``TID252`` with
``ban-relative-imports = "all"``, so a relative import fails the build instead of waiting for a
reviewer to object. This file itself imports from the Python standard library only. Nothing in
the subpackage reaches outside the standard library and the distribution's pinned dependency
closure, and neither third-party client is imported at module scope anywhere in it.

The published surface
---------------------
:data:`__all__` is the contract. The inclusion criterion is the smallest set with which a caller
can run each pass and read its result -- one entry point and one result type per pass, plus the
two money-totalling functions a caller comparing an already-loaded total needs. Everything else
the three modules publish stays reachable at its owning module, which is the spelling every
existing consumer already uses, so curating withdraws nothing that was available before.

``PASS_MODULES``
    The three module names in the order the passes run: count, then checksum, then money total.
``row_counts``, ``checksum``, ``money_parity``
    The modules themselves, each reachable by attribute under its own stable name.

From ``row_counts`` -- pass 1:

``compare_counts``
    Compare one dataset's source record count against its target table's row count.
``RowCountComparison``
    What that comparison found, for one dataset.

From ``checksum`` -- pass 2:

``digest_records``
    Digest a stream of records, giving the whole-dataset digest and the per-record digests.
``digest_of_record``
    Digest one record over a stated field list. The unit the pass is built from.
``RecordDigest``
    A digest together with what it was taken over.

From ``money_parity`` -- pass 3:

``compare_money_totals``
    Compare the exact money total of one source column against the loaded column's own sum.
``MoneyParity``
    What that comparison found, for one money column.
``total_source_money``, ``total_target_money``
    The two sides of that comparison, published separately so a caller holding a total already
    computed can compare against one side without recomputing both.

Design decisions (WHY)
----------------------
Assumptions:
    **All three passes are mandatory, and the constraint is a property of what each pass can
    see rather than a policy.** A row count cannot detect a sign-overpunch or packed-nibble
    decode defect: such a load produces exactly the right number of rows, so pass 1 reports a
    match while every balance carries the wrong sign. Only pass 3 compares the money and only
    pass 2 localises a field corrupted in place, which is why no switch exists here to run
    fewer than three and why the plan treats a load verified by counts alone as unverified.
Assumptions:
    **The two database passes connect as a role that could not write even if a pass tried.**
    The reporting role holds ``USAGE`` and ``SELECT`` on a schema that owns no table, and its
    privileges on the schemas that do own tables are explicitly revoked. The alternative -- a
    pass connecting as the loader's own role -- was refused because a verifier able to mutate
    what it verifies cannot certify anything: a defect in this subpackage would then be able to
    silently repair the very evidence a reader is relying on it to judge.
Assumptions:
    **Rendered output carries no timestamp and is stable between runs.** Both shipped queries
    keep every timestamp column out of their predicates and grouping and order on an unprojected
    sort key, precisely so that two runs over unchanged data are byte-identical. A clock reading
    added anywhere in the rendering would make every report differ from every other one, which
    would turn a one-line diff into a manual reading exercise and lose the property both queries
    were written to provide.
Alternatives Considered:
    **The outcome is binary, and the COBOL oracle's graded rubric was deliberately not
    reused.** Adopting 0/2/4/8/16 here was rejected because that scale carries a warn tier whose
    documented green state is a non-zero code -- a 4 meaning "an unfixable defect in immutable
    baseline source". Borrowing it would make a non-zero verification result ambiguous between
    "warned" and "failed", so the one signal an operator needs from a verification, whether the
    load can be trusted, would no longer be readable from the exit status alone.
Alternatives Considered:
    **A curated flat surface was chosen over publishing the three modules alone**, and the name
    collision that would have ruled it out was checked for rather than assumed absent. It does
    exist between the two passes that reach the database: ``row_counts`` and ``money_parity``
    each publish their own ``REPORTING_SCHEMA``, ``open_reporting_connection``,
    ``reporting_role``, ``reporting_settings`` and ``require_reporting_session``, so binding any
    of those five flat here would let one pass silently decide which session a caller opened.
    They are therefore deliberately excluded, and every name that IS re-exported is owned by
    exactly one of the three passes -- which is what makes
    ``from carddemo_migration.verify import compare_counts`` unambiguous without the caller
    knowing which module owns it. A caller who wants a reporting session reaches it at the pass
    that owns the query it is for, where the choice is explicit. This is the same hazard that
    forces the sibling ``readers`` package to publish module objects only, where all twelve
    readers declare ``DROPPED_FIELD_NAMES``; here it is confined to five names and is answered
    by omitting them. The modules are published as well, under their own names, so nothing is
    reachable by only one route.
Alternatives Considered:
    **The three modules are resolved on first access rather than imported here, and the cost of
    the eager alternative was measured rather than assumed.** The measurement corrects the
    reason one would expect: importing all three eagerly does NOT pull in ``psycopg`` or
    ``boto3``, because no module in this distribution imports either at module scope. What it
    would actually cost is roughly 247 kibibytes of source, taking
    ``import carddemo_migration.verify`` from 2 to 30 ``carddemo_migration`` modules and from
    150 to 225 entries in ``sys.modules`` -- pass 1 imports the loader and every reader at
    module scope -- plus 37 further code-page modules wherever the optional ``ebcdic``
    distribution is installed. None of that is wanted by a caller who asked for a checksum, and
    all of it is wanted by a caller running pass 1, which is the shape first-access resolution
    serves.
Trade-offs:
    **The accepted cost of that deferral is that a defect inside one pass -- a syntax error, a
    bad import -- surfaces on first ACCESS rather than at package import**, so a process that
    never touches that module no longer fails. Two things bound it: ``carddemo_migration.cli``
    imports all three passes at ITS module scope, so any command-line invocation still fails
    loudly on a broken module, and this subpackage's tests import each pass by name. Were a pass
    here ever to acquire an unguarded heavy dependency at module scope, this would stop being a
    cost decision and become a correctness one, so the condition is named rather than left
    implicit.
Trade-offs:
    **No routine in this subpackage renders a field value.** That makes a failure less
    immediately diagnosable -- an operator sees which record and which field differ, and the
    differing field's byte interval, but not what the two values were -- and it is the right
    trade: these datasets carry primary account numbers and national identifiers, and a
    verification log is retained and readable by everyone holding log access. The masked
    renderings the readers publish are the supported way to look at a record.
"""

from __future__ import annotations

# WHY : Trade-offs: this import block is the whole of it -- the standard library only, plus the
#   one annotation-only name behind the type-checking guard below. NONE of the three pass modules
#   is imported here; they are resolved by `__getattr__` instead, for the measured reasons argued
#   in the module docstring. `importlib` is what performs that resolution, and `MappingProxyType`
#   is what makes the export registry read-only.
# WHY : Assumptions: `Mapping` is imported for annotation only and stays behind the type-checking
#   guard, because `from __future__ import annotations` defers every annotation to a string.
#   Taking it at run time would import `collections.abc` for a name nothing evaluates.
import importlib
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Final

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from collections.abc import Mapping

# WHY : Alternatives Considered: the surface is DECLARED rather than left to whatever names
#   happen to be bound. A surface that changes shape whenever a pass gains a helper is not a
#   contract, and this list is also the set `__getattr__` answers for, so the declaration and the
#   resolver check each other rather than agreeing by coincidence -- a name added to one without
#   the other fails rather than half-working.
# WHY : Assumptions: the constant comes first and the remaining names follow in sorted order,
#   matching how the three sibling entry points in this distribution declare their own surfaces,
#   so all four read the same way.
__all__ = [
    "PASS_MODULES",
    "MoneyParity",
    "RecordDigest",
    "RowCountComparison",
    "checksum",
    "compare_counts",
    "compare_money_totals",
    "digest_of_record",
    "digest_records",
    "money_parity",
    "row_counts",
    "total_source_money",
    "total_target_money",
]

# WHY : Assumptions: the three passes are published in the order the plan RUNS them -- count,
#   then checksum, then money total -- rather than alphabetically, because that order is
#   load-bearing for a reader: a count mismatch explains a checksum mismatch, so running the
#   cheap pass first is what makes the expensive one's failure interpretable.
PASS_MODULES: Final[tuple[str, ...]] = ("row_counts", "checksum", "money_parity")

# WHY : Assumptions: the surface is CURATED rather than a star re-export of the three modules,
#   and the criterion is the smallest set with which a caller can run each pass and read its
#   result: one entry point and one result type per pass, plus the two money-totalling functions
#   a caller comparing an already-loaded total needs. Everything else the three modules publish
#   -- the digest name, the two separators, the money scale, the report types -- stays reachable
#   at its owning module, which is the spelling every existing consumer already uses, so curating
#   withdraws nothing.
# WHY : Trade-offs: the values are (module, attribute) PAIRS rather than the objects themselves,
#   so this constant is inert and building it imports nothing at all. That is the whole mechanism
#   by which the deferral works: a mapping of imported objects would have to import all three
#   passes to be built, which is precisely the eager cost the module docstring measures and
#   declines.
# WHY : Assumptions: the entries are grouped by owning pass and, within a group, follow the order
#   a caller uses them in rather than alphabetical order. A reader scanning this mapping is trying
#   to learn the shape of a verification, and alphabetical order would scatter the three passes
#   into each other.
# WHY : Assumptions: no attribute is spelled differently here from the name its owning module
#   publishes. A rename at this boundary would give one object two public spellings and make
#   every future correction in the owning module a breaking change here as well.
# WHY : Assumptions: MappingProxyType makes it read-only at run time, so a caller cannot redirect
#   a published name to a different implementation by mutating a shared dict -- which would be a
#   silent, process-wide change to which code actually judges a load.
_EXPORTS: Final[Mapping[str, tuple[str, str]]] = MappingProxyType(
    {
        "RowCountComparison": ("row_counts", "RowCountComparison"),
        "compare_counts": ("row_counts", "compare_counts"),
        "RecordDigest": ("checksum", "RecordDigest"),
        "digest_records": ("checksum", "digest_records"),
        "digest_of_record": ("checksum", "digest_of_record"),
        "MoneyParity": ("money_parity", "MoneyParity"),
        "compare_money_totals": ("money_parity", "compare_money_totals"),
        "total_source_money": ("money_parity", "total_source_money"),
        "total_target_money": ("money_parity", "total_target_money"),
    }
)


def __getattr__(name: str) -> Any:
    """Resolve a published pass entry point or pass module, importing it on first access.

    Purpose
    -------
    Make the three passes reachable from the package boundary that documents them, without this
    package importing all three to make that true.

    Parameters
    ----------
    name : str
        The attribute being looked up. Only the three pass module names in
        :data:`PASS_MODULES` and the nine curated entry points in the export registry resolve;
        anything else is reported as missing.

    Returns
    -------
    Any
        The imported module, class or function the name publishes. The annotation is deliberately
        the widest one available because this single hook resolves all three kinds, and narrowing
        it would require either a union that has to be edited whenever a pass publishes a new
        kind of object, or an overload per name.

    Raises
    ------
    AttributeError
        If ``name`` is not part of the published surface. The message names the package, because
        a mistyped pass name is otherwise reported identically to a mistyped attribute ON a pass.
    """
    # WHY : Assumptions: the module names are checked BEFORE the curated entries, so
    #   `verify.row_counts` continues to mean the MODULE and not some attribute of it. That
    #   spelling is what every consumer predating the curated surface uses, and rebinding it
    #   would break them without any import failing.
    if name in PASS_MODULES:
        return importlib.import_module(f"{__name__}.{name}")
    if name in _EXPORTS:
        module_name, attribute = _EXPORTS[name]
        return getattr(importlib.import_module(f"{__name__}.{module_name}"), attribute)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def __dir__() -> list[str]:
    """Return the published surface, so interactive completion matches the documented one.

    Purpose
    -------
    Report exactly what :data:`__all__` declares, rather than whatever a session has happened to
    touch, so that completing on this package agrees with the contract stated above.

    Returns
    -------
    list[str]
        The sorted contents of :data:`__all__`.

    Raises
    ------
    None
        Reading a declared list cannot fail, so this hook has no failure mode of its own.
    """
    # WHY : Assumptions: dir() is overridden for the same reason the three sibling entry points
    #   override it -- a lazily resolved name is absent from this package's namespace until it is
    #   touched, and importing a submodule binds it on the parent, so the default implementation
    #   reports a surface that GROWS as a session proceeds and never shrinks.
    return sorted(__all__)
