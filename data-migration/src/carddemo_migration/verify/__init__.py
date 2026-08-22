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
pass of its own, compares nothing, and imports none of the four modules until one of their
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

The gate over them
------------------
``gate``
    Not a fourth pass: the orchestration that makes the three above indivisible.
    :func:`~carddemo_migration.verify.gate.run_verification_gate` accepts exactly the three
    declared passes in their declared order -- omitting one, repeating one or reordering them
    raises before any measurement is taken -- runs them 1, 2, 3, stops at the first that fails,
    and reduces the run to one binary verdict in a :class:`MigrationVerification`. Each pass is
    supplied as a callable, so the gate's own rules carry no connection, extract or dataset
    vocabulary and are drivable with no cluster at all.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition, in the module that owns it.

Returns
-------
None
    Importing binds names only. It imports NONE of the three pass modules nor the gate over them,
    opens no connection, constructs no client, reads no environment variable, configures no
    logging and opens no file; each module is resolved on first access by :func:`__getattr__`.

Raises
------
AttributeError
    Raised by attribute access for a name this subpackage does not publish. Nothing else is
    raised from this module: every failure a verification can have belongs to a call, and each
    pass declares its own exception type at its own definition, as does the gate over them.

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
partial verdict. The mandate is enforced by :func:`~carddemo_migration.verify.gate.
run_verification_gate`, published here as :func:`run_verification_gate`: it takes exactly the
three declared passes in their declared order and RAISES on anything else, runs them 1, 2, 3,
stops at the first failure, and reports verified only when all three ran and all three agreed --
so a run that stopped early, and a run that judged nothing, are both unverified. That is what
gives "verified" one meaning rather than one per caller.

Read-only by construction
-------------------------
Every pass only reads, and it is prevented from doing otherwise rather than merely intending it.
The two passes that reach the database connect as the least-privilege ``carddemo_reporting``
role, whose whole privilege set is: ``USAGE`` on the ``reporting`` schema, ``SELECT`` on nine
views in it, and ``EXECUTE`` on one lookup function. What makes the role incapable of writing is
not that the schema is empty -- it is not -- but that the role holds **no privilege of any kind
on a base table, and no ``INSERT``, ``UPDATE``, ``DELETE``, ``TRUNCATE``, ``CREATE``, ``ALTER``
or ``DROP`` anywhere**: ``CREATE`` on the ``reporting`` schema is revoked, and every privilege the
role might otherwise have inherited on ``ledger``, ``account``, ``card`` and ``reference`` --
including the default privileges of future tables -- is explicitly revoked by
``data-migration/sql/V0__schemas_and_roles.sql``.

The actual topology, stated because an understatement of it reads as a stronger guarantee than
the one that holds. In the ``reporting`` schema:

* **eight product views** created by ``data-migration/sql/V1__reporting_views.sql`` --
  ``v_report_transactions``, ``v_statement_transactions``, ``v_transaction_types``,
  ``v_transaction_categories``, ``v_accounts``, ``v_customers``, ``v_card_xref`` and
  ``v_transaction_category_balances`` -- which are ``reporting-service``'s entire readable data
  surface. Seven of the eight are mapped as entities; ``v_report_transactions`` is read by native
  query, which is why ``application-test.yml`` counts seven MAPPED views and this counts eight
  CREATED ones;
* **two verification views** created by ``data-migration/sql/V3__verification_surfaces.sql`` --
  ``v_verification_row_counts`` and ``v_verification_money_totals`` -- which are what the two
  whole-migration SQL reports in ``data-migration/sql/verify/`` select from;
* **one table**, ``card_grouping_key``, which the schema owns and on which this role holds
  nothing at all: ``V0`` and ``V1`` each revoke it explicitly, because it holds the secret the
  statement view's grouping key is derived from;
* **one function**, ``resolve_card(character varying)``, on which the role holds ``EXECUTE``. It
  performs a read-only lookup and returns no card number.

``V3`` additionally creates ``auth.v_verification_row_counts`` and grants it to
``carddemo_reporting_owner`` rather than to this role, so the auth schema's contribution to the
row-count report is reached through the owner's own view chain and never by this role directly.

Each pass also checks the session's role against the server before running its query, so a
session that could write cannot certify the load. No module here issues a ``CREATE``, ``ALTER``,
``DROP``, ``INSERT``, ``UPDATE``, ``DELETE``, ``TRUNCATE``, ``GRANT`` or ``REVOKE`` statement, and
none writes to an extract file: ``app/**`` is reference-only input, including ``app/data/**``, so
a pass that adjusted its own input would be destroying the evidence it exists to check.

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
can run each pass BOTH ways -- over the whole migration and over one dataset -- read its result,
and run the three as one gate. Everything else the four modules publish stays reachable at its
owning module, which is the spelling every existing consumer already uses, so curating withdraws
nothing that was available before.

``PASS_MODULES``
    The three module names in the order the passes run: count, then checksum, then money total.
``row_counts``, ``checksum``, ``money_parity``, ``gate``, ``session``
    The modules themselves, each reachable by attribute under its own stable name.

From ``row_counts`` -- pass 1:

``verify_row_counts``
    Run the whole-migration count report: execute the shipped query on a read-only session and
    judge every declared dataset in one shot. The pass's own entry point.
``RowCountReport``
    What that run produced -- every line, and the single binary verdict over them.
``compare_counts``
    Compare one dataset's source record count against its target table's row count.
``RowCountComparison``
    What that comparison found, for one dataset.

From ``checksum`` -- pass 2:

``compare_record_digests``
    Compare a source record stream against the rows read back, naming which record and which
    field differ. The pass's own entry point.
``ChecksumComparison``
    What that comparison found: the digests, the located differences and the verdict.
``digest_records``
    Digest a stream of records, giving the whole-dataset digest and the per-record digests.
``digest_of_record``
    Digest one record over a stated field list. The unit the pass is built from.
``RecordDigest``
    A digest together with what it was taken over.
``audit_sealed_columns``
    Certify the protected columns the digest comparison must exclude -- an envelope draws a fresh
    initialisation vector per value, so its stored bytes are not a function of its source value --
    by presence and framing rather than by plaintext, which this package cannot recover.
``SealedColumnAudit``
    What that audit found: per column, how many values had to be sealed, how many envelopes are
    stored and how many are malformed. Counts only, never a stored byte.
``SealableValueTally``
    Count how many source values had to be sealed while the records stream past the digest
    comparison, so the audit costs no second read of the extract.

From ``money_parity`` -- pass 3:

``verify_money_totals``
    Run the whole-migration money report: execute the shipped query on a read-only session and
    pair every declared money column against the source measurement. The pass's own entry point.
``MoneyTotalReport``
    What that run produced -- every paired line, the sign discrepancies, and the verdict.
``read_source_totals``
    Measure the source side the report is paired against, from the extracts. Without it
    ``verify_money_totals`` has nothing to compare to, which is why both are published together.
``SourceExtract``
    Which extract file and encoding a source measurement is to be taken from.
``money_columns``
    The declared money columns, derived from the layouts at call time rather than at import.
``compare_money_totals``
    Compare the exact money total of one source column against the loaded column's own sum.
``MoneyParity``
    What that comparison found, for one money column.
``total_source_money``, ``total_target_money``
    The two sides of that comparison, published separately so a caller holding a total already
    computed can compare against one side without recomputing both.

From ``gate`` -- the three as one:

``run_verification_gate``
    Run all three passes in declared order, stopping at the first failure. Refuses to run at all
    over fewer than three.
``MigrationVerification``
    The gate's outcomes and its single binary verdict.
``PassOutcome``
    What one pass of the gate produced, kept whole rather than reduced to a boolean.
``CombinedPassResult``
    Fold the per-dataset results of one pass into that pass's single result, for the checksum pass
    that runs a dataset at a time.
``VerificationGateError``
    Raised when the gate is handed other than the three declared passes in declared order.

From ``session`` -- the authority the per-dataset passes run under:

``open_verification_connection``
    Open a connection on the read-only verification role and certify the session it carries.
``require_verification_session``
    Certify a session already in hand: the server is asked who it is, told to be read-only, and
    then asked to confirm it.
``verifier_role``, ``verifier_settings``
    The role name and its resolved connection descriptor.
``VerificationSessionError``
    Raised when a session offered for verification is not a certified read-only verifier session.

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
    The reporting role holds ``USAGE`` on one schema, ``SELECT`` on nine views in it and
    ``EXECUTE`` on one read-only lookup function -- and no privilege on any base table, no
    ``INSERT``/``UPDATE``/``DELETE``/``TRUNCATE`` anywhere, and no ``CREATE`` even in the schema
    it reads. Its privileges on the four schemas that do own tables, present and future, are
    explicitly revoked. The alternative -- a pass connecting as the loader's own role -- was
    refused because a verifier able to mutate what it verifies cannot certify anything: a defect
    in this subpackage would then be able to silently repair the very evidence a reader is relying
    on it to judge.
Refactoring Rationale:
    **The immutability argument is grounded in ABSENT privileges rather than in an empty schema.**
    This section, and the same claim at the two passes that make it, used to say the reporting
    role "reads one aggregate verification view and nothing else" in a schema that "owns no table
    at all". Both halves were false: the schema holds nine views, one table and one executable
    function, and the role reads nine of those relations. The role is nonetheless read-only, so
    the conclusion survived while the reason for it did not -- which is the worse kind of error in
    a security note, because a reader who checks the topology and finds it wrong has no way to
    tell whether the conclusion was checked either. Naming the privileges the role does NOT hold
    is an argument that stays true as relations are added to the schema.
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
    "ChecksumComparison",
    "CombinedPassResult",
    "MigrationVerification",
    "MoneyParity",
    "MoneyTotalReport",
    "PassOutcome",
    "RecordDigest",
    "RowCountComparison",
    "RowCountReport",
    "SealableValueTally",
    "SealedColumnAudit",
    "SourceExtract",
    "VerificationGateError",
    "VerificationSessionError",
    "audit_sealed_columns",
    "checksum",
    "compare_counts",
    "compare_money_totals",
    "compare_record_digests",
    "digest_of_record",
    "digest_records",
    "gate",
    "money_columns",
    "money_parity",
    "open_verification_connection",
    "read_source_totals",
    "require_verification_session",
    "row_counts",
    "run_verification_gate",
    "session",
    "total_source_money",
    "total_target_money",
    "verifier_role",
    "verifier_settings",
    "verify_money_totals",
    "verify_row_counts",
]

# WHY : Assumptions: the three passes are published in the order the plan RUNS them -- count,
#   then checksum, then money total -- rather than alphabetically, because that order is
#   load-bearing for a reader: a count mismatch explains a checksum mismatch, so running the
#   cheap pass first is what makes the expensive one's failure interpretable.
PASS_MODULES: Final[tuple[str, ...]] = ("row_counts", "checksum", "money_parity")

# WHY : Assumptions: the gate is listed as a resolvable submodule but is deliberately NOT a member
#   of `PASS_MODULES`, because that constant is the ordered list of MEASUREMENTS and a caller
#   iterating it to run the three passes must not be handed the orchestration as a fourth. Keeping
#   the two lists separate is what lets `PASS_MODULES` stay usable as the pass order it documents.
_SUBMODULES: Final[tuple[str, ...]] = (*PASS_MODULES, "gate", "session")

# WHY : Assumptions: the surface is CURATED rather than a star re-export of the three modules,
#   and the criterion is the smallest set with which a caller can run each pass and read its
#   result: one entry point and one result type per pass, plus the two money-totalling functions
#   a caller comparing an already-loaded total needs, plus the whole-migration money verifier with
#   the report it returns and the extract description its one required argument takes. Everything
#   else the three modules publish -- the digest name, the two separators, the money scale, the
#   per-line report types -- stays reachable at its owning module, which is the spelling every
#   existing consumer already uses, so curating withdraws nothing.
# WHY : Refactoring Rationale: the criterion above previously excluded "the report types" as a
#   class, and that exclusion made the docstring's claim of a combined invocation unreachable from
#   here. It is narrowed to the PER-LINE types: a caller running the whole-migration pass needs the
#   report it returns, and nothing about a single judged line.
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
        "verify_row_counts": ("row_counts", "verify_row_counts"),
        "RowCountReport": ("row_counts", "RowCountReport"),
        "compare_counts": ("row_counts", "compare_counts"),
        "RowCountComparison": ("row_counts", "RowCountComparison"),
        "compare_record_digests": ("checksum", "compare_record_digests"),
        "ChecksumComparison": ("checksum", "ChecksumComparison"),
        "digest_records": ("checksum", "digest_records"),
        "digest_of_record": ("checksum", "digest_of_record"),
        "RecordDigest": ("checksum", "RecordDigest"),
        "audit_sealed_columns": ("checksum", "audit_sealed_columns"),
        "SealedColumnAudit": ("checksum", "SealedColumnAudit"),
        "SealableValueTally": ("checksum", "SealableValueTally"),
        # WHY : Refactoring Rationale: the three whole-migration money names below were once
        #   absent from this registry, and their absence made this package's own docstring
        #   untrue -- it states that the three passes have a combined invocation, while the
        #   only money name reachable here was the per-dataset total comparison.
        #   `verify_money_totals` is the sole path that compares negative-ROW COUNTS as well
        #   as totals, which is what catches a compensating sign swap: two records whose signs
        #   are exchanged leave the total unchanged, so a total-only comparison passes and only
        #   the count of strictly-negative rows moves. Publishing it here is what makes that
        #   check reachable from the boundary that documents it, and `cli.py`'s
        #   `verify-money-total-report` is what makes it reachable from a command line.
        # WHY : Assumptions: `SourceExtract` is published alongside them because it is the only
        #   way to CALL the verifier -- its source side is injected, by design, since this
        #   distribution holds no dataset-to-path mapping and the seed trees are an operator's
        #   input rather than a location the package knows. Publishing the entry point without
        #   the type its one required argument takes would leave the export unusable from here.
        "verify_money_totals": ("money_parity", "verify_money_totals"),
        "MoneyTotalReport": ("money_parity", "MoneyTotalReport"),
        "read_source_totals": ("money_parity", "read_source_totals"),
        "SourceExtract": ("money_parity", "SourceExtract"),
        "money_columns": ("money_parity", "money_columns"),
        "compare_money_totals": ("money_parity", "compare_money_totals"),
        "MoneyParity": ("money_parity", "MoneyParity"),
        "total_source_money": ("money_parity", "total_source_money"),
        "total_target_money": ("money_parity", "total_target_money"),
        "run_verification_gate": ("gate", "run_verification_gate"),
        "MigrationVerification": ("gate", "MigrationVerification"),
        "PassOutcome": ("gate", "PassOutcome"),
        "CombinedPassResult": ("gate", "CombinedPassResult"),
        "VerificationGateError": ("gate", "VerificationGateError"),
        "open_verification_connection": ("session", "open_verification_connection"),
        "require_verification_session": ("session", "require_verification_session"),
        "verifier_role": ("session", "verifier_role"),
        "verifier_settings": ("session", "verifier_settings"),
        "VerificationSessionError": ("session", "VerificationSessionError"),
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
        The attribute being looked up. Only the three pass module names in :data:`PASS_MODULES`,
        the ``gate`` and ``session`` modules beside them, and the curated entry points the export
        registry declares resolve; anything else is reported as missing. The registry and
        :data:`__all__` are held equal by a test, so a name in one and not the other fails rather
        than half-working.

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
    if name in _SUBMODULES:
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

    Parameters
    ----------
    None
        ``dir()`` calls this hook with no argument. It reads :data:`__all__` and nothing else --
        not the module namespace, and not the export registry -- because the declaration is the
        contract and the two are held equal by a test rather than by this function reconciling
        them at run time.

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
