"""Prove the Aurora bulk loader loads exactly what it declares, atomically, and privately.

Purpose
-------
Establish the four properties a bulk load into a live cluster depends on and which nothing
else in this suite covers: that every declared target names columns the owning service's
migration really creates, that the COPY statement names its columns explicitly rather than
relying on declared table order, that a mapping failure rolls the transaction back before it
propagates, and that no diagnostic on the failure path carries a field VALUE. A loader
missing any one of them fails in a way that is either silent or unsafe -- values in the
wrong columns, a half-loaded table, or a national identifier in a retained log.

Alternatives Considered:
    Two were evaluated. (1) Running against the container database the setup log describes
    was rejected: it makes the suite depend on a running service and a credential, where
    the recording double the shared ``conftest`` already publishes lets every statement the
    loader issues be asserted exactly -- including the statements it must NOT issue, which a
    real connection can only refuse rather than record. (2) Asserting the COPY statement as
    one literal string per target was rejected in favour of asserting its SHAPE plus the
    column list read from the target, because a literal would have to be rewritten whenever
    a column was added and the rewrite is where an implicit column order slips back in.

Assumptions:
    The column names each target declares are checked against the Flyway migrations the
    owning services ship, read from disk. That cross-tree assertion is the only thing that
    can catch the loader and the schema drifting apart, since both are text and neither
    imports the other. A load that named a column no table had would otherwise be
    discovered by the first real deployment.

Trade-offs:
    Atomicity is asserted through the double's rollback and commit counters rather than by
    observing a partially populated table. The counters cannot prove the ORDER of the
    rollback against the raise directly; what they do prove is that a rollback happened at
    all on a path that ended in an exception, which is the property that distinguishes a
    loader leaving a clean transaction from one leaving an open one for the connection pool.
"""

from __future__ import annotations

import re
from collections.abc import Iterator, Mapping
from decimal import Decimal
from pathlib import Path
from typing import TYPE_CHECKING

import pytest

from carddemo_migration.config import quote_identifier
from carddemo_migration.copybook import layouts
from carddemo_migration.loaders.aurora import (
    TRANSACTION_ID_SEQUENCE,
    AuroraLoadError,
    LoadContext,
    LoadStrategy,
    Projection,
    TableTarget,
    load_records,
    prepare_record,
    reconcile_transaction_id_sequence,
    target_for,
    target_names,
)
from carddemo_migration.loaders.protected_columns import (
    CardVerificationValueCipher,
    CustomerIdentifierCipher,
    DataKey,
    ProtectedColumnError,
)

# WHY : Assumptions: the dataset-to-reader dispatch is imported rather than restated, so the
#   assertion that a refused layout is readerless reads the same mapping `cli.py` resolves a reader
#   through. A local list of reader names would keep asserting a layout has no reader after one had
#   been added, which is precisely the drift that turned the transaction master unloadable.
from carddemo_migration.readers import DATASET_READERS

# WHY : Assumptions: the composite key's membership is imported from the reader that publishes
#   it rather than restated here, following the same single-sourcing rule the readers follow for
#   layouts. Restating the three component names would create a second statement of the key's
#   composition free to go stale against the descriptor, and the sibling category-reference
#   record's identically-named key group -- two components where this one has three -- is
#   exactly the drift that restatement would hide.
from carddemo_migration.readers.tcatbal import COMPOSITE_KEY_FIELD_NAMES

if TYPE_CHECKING:
    from conftest import FakeAuroraDatabase

# Assumptions: the eleven loadable records are stated literally, in declaration order, so a
#   target appearing or disappearing is a visible edit here rather than a silent change in
#   what a migration run covers. Reading the mapping back would make this assertion true of
#   any mapping at all, including an empty one.
# WHY : Refactoring Rationale: this table named FIVE records and now names eleven. The six that
#   were missing were the card master, the customer master, the daily-transaction feed, the
#   category balances, every user of the system and the transaction master -- so
#   `sql/verify/row_counts.sql` listed eleven dataset baselines against a loader that could satisfy
#   four of them, and the shortfall was asserted here as if it were a design. `TRAN` was the last
#   to be added and was withheld on a different ground from the other five: that the SEED CORPUS
#   ships no extract for it. That is true of the corpus and not of the record, which has a reader,
#   a 350-byte layout, a registered verification baseline and a REPRO job at
#   `app/jcl/TRANFILE.jcl`, so asserting it unloadable pinned "a cutover cannot move the
#   transaction master" as a contract.
_LOADABLE_RECORDS = (
    "XREF",
    "TRANTYPE",
    "TRANCAT",
    "DISGROUP",
    "ACCOUNT",
    "CARD",
    "CUSTOMER",
    "DALYTRAN",
    "TRAN",
    "TCATBAL",
    "SECUSER",
)

# WHY : Refactoring Rationale: this table used to name the FOUR records that merged, on the ground
#   that only their tables had a second writer, and it recorded that giving a single-writer master a
#   conflict key "would turn a re-run from a reported duplicate into a silent no-op". Both halves
#   have been withdrawn. Every one of the eleven records merges now, because a re-run is a normal
#   event for all of them -- AAP 0.4.1.7 drives this load from a Step Functions state that re-enters
#   a failed state from the beginning -- and the "reported duplicate" it valued was a hard failure
#   of that retry for seven datasets. What replaces the distinction is a strategy per target, which
#   is a property of the TABLE's indexes: ten conflict on a real unique constraint over the
#   record's own key window, and `ledger.daily_transactions` cannot, because its primary key is an
#   identity column no extract supplies and `transaction_id` is deliberately non-unique.
_KEYED_MERGE_RECORDS = tuple(record for record in _LOADABLE_RECORDS if record != "DALYTRAN")

_WHOLE_ROW_MERGE_RECORDS = ("DALYTRAN",)

# Assumptions: the subset of merged records whose second writer is the reference seed migration, so
#   the conflict target can be asserted against that file's own `ON CONFLICT` clause. The other
#   eight keyed records are excluded because they ship no such clause -- the posting job inserts
#   through application code and the masters have no second writer at all -- so their keys are
#   checked against the primary-key constraint in the owning migration instead.
_SEED_MERGED_RECORDS = ("TRANTYPE", "TRANCAT", "DISGROUP")

# Assumptions: these records are registered and deliberately have NO load target, and the reason is
#   the OPPOSITE of a refusal: they are written BY the batch chain and nothing reads them in, so
#   there is no load direction to declare a target for. `readers/__init__.py` publishes no reader
#   for any of the three, which is the same fact stated where a caller would meet it.
# WHY : Refactoring Rationale: `TRAN` was a fourth member here and is REMOVED. It was grouped with
#   these three on the ground that no extract ships for it, but it is not like them: it has a
#   reader, a layout, a verification baseline and a REPRO job, and what it lacks is a dataset in the
#   seed corpus. Keeping it here asserted the corpus's contents as a property of the record.
_BATCH_WRITTEN_RECORDS = ("TRNX", "REJECT", "INTTRAN")

# Assumptions: the keys a target may declare that no reader publishes, stated here so the
#   field-provenance assertion can exempt exactly these and nothing else. `cognito_sub` is the
#   only one: `auth.users` requires it NOT NULL and UNIQUE, and the 80-byte `USRSEC` record
#   cannot carry a value the identity provider mints.
_DERIVED_KEYS = frozenset({"cognito_sub"})

# Assumptions: the migrations are located relative to this file rather than through an
#   installed distribution, because they are resources of the SERVICE modules and are not
#   packaged with this one. The suite therefore reads the same text a reviewer edits.
_SERVICES_ROOT = Path(__file__).resolve().parents[2] / "services"

# WHY : Refactoring Rationale: this pattern used to match the direct COPY into the target table,
#   `COPY "schema"."table" (cols) FROM STDIN`. That statement no longer exists: every target now
#   stages into a session-temporary table and merges, so the only COPY a load issues names the
#   UNQUALIFIED staging table. The pattern is rewritten rather than deleted because the property it
#   asserts is unchanged and still worth asserting -- the column list is explicit, quoted, and in
#   the target's own order -- and it now additionally pins the staging name, which must stay
#   unqualified or the "temporary" table would be created permanently in the target's schema.
_STAGE_COPY_SHAPE = re.compile(r'\ACOPY "(?P<stage>[^"]+)" \((?P<columns>[^)]*)\) FROM STDIN\Z')

# Assumptions: the verification SQL is read from the repository rather than restated here,
#   because it is the artifact that DECLARES which datasets a migration run is checked against.
#   Restating its contents in this file would let the two drift apart in exactly the way the
#   category-balance gap did -- the SQL claiming a dataset is verified while nothing could load
#   it -- and the restatement would then be the thing asserted rather than the SQL.
_ROW_COUNTS_SQL = Path(__file__).resolve().parents[1] / "sql" / "verify" / "row_counts.sql"

# Assumptions: one row of the SQL's dataset registration is an ordinal, a quoted dataset name
#   and a quoted schema-qualified table, in that order, with the PostgreSQL cast suffixes the
#   first row carries to fix the CTE's column types. The pattern anchors on that shape rather
#   than on line numbers so it survives a comment being inserted above a row.
_REGISTRATION_ROW = re.compile(
    r"^\s*\(\s*\d+(?:::smallint)?\s*,\s*'(?P<dataset>[^']+)'(?:::text)?\s*,"
    r"\s*'(?P<table>[^']+)'(?:::text)?\s*,",
    re.MULTILINE,
)

# Assumptions: every table the verification SQL registers that this module deliberately cannot
#   load is listed here WITH its reason, one entry per table, so the general assertion below can
#   be exhaustive rather than approximate. A table missing from both this mapping and TARGETS is
#   the defect the assertion exists to catch: a dataset declared verified that nothing can fill.
# Trade-offs: the alternative was to assert only the category-balance table, which is the one
#   the review named. That was rejected because it would prove the single instance fixed and
#   leave the defect CLASS undetected -- the next dataset registered without a target would
#   reproduce it exactly, and the suite would stay green.
# Refactoring Rationale: four entries stood here and are WITHDRAWN, because each named an
#   obstacle the loader has since removed rather than a table it cannot fill. `account.customers`
#   and `card.cards` were excused as cipher-bound, on the ground that this package would otherwise
#   load plaintext into a column asserting ciphertext; both now project their protected fields
#   through the sealing projections, so nothing plaintext reaches either column and the objection
#   is answered rather than deferred. `auth.users` was excused over the baseline's cleartext
#   credential; the reader never decodes that span, the target declares no column for it, and what
#   the target does carry is the subject the identity provider mints -- so the record loads without
#   the credential existing anywhere on the path. `ledger.daily_transactions` was excused as having
#   no REPRO job of its own; it now has a target with a recorded decision about the conflict key it
#   deliberately does not declare. Withdrawing an excuse whose obstacle is gone is the point of this
#   mapping being asserted against the targets: an excuse that outlives its reason is exactly how a
#   delivered load would come to be reported as impossible.
# WHY : Refactoring Rationale: the FIFTH and last entry is now withdrawn too, and the mapping is
#   deliberately EMPTY rather than deleted. It excused `ledger.transactions` as "registered against
#   the dataset name '(none)' with a NULL baseline ... filled by the posting job rather than by any
#   load". Every clause of that was true and the conclusion still did not follow: a NULL baseline
#   says the seed corpus ships no extract to count, not that no extract can ever be loaded, and
#   `app/jcl/TRANFILE.jcl` is a REPRO job for exactly this cluster. The excuse therefore described
#   the corpus while reading as a property of the table, and while it stood the general assertion
#   above stayed green with the largest table in the system unloadable. The mapping is kept in place
#   because it is the mechanism by which a future exemption must be stated explicitly and checked
#   against the verification SQL, and an empty one asserts that no exemption is currently claimed.
_NO_LOAD_TARGET_BY_DESIGN: dict[str, str] = {}


def _datasets_registered_for_verification() -> dict[str, str]:
    """Read which schema-qualified tables the row-count verification SQL registers a dataset for.

    Purpose
    -------
    Take the set of datasets a migration run is checked against from the SQL that declares it,
    so the loader's coverage can be asserted against the verification layer's own claim instead
    of against a second list maintained here.

    Returns
    -------
    dict[str, str]
        Schema-qualified table name to the baseline dataset name the SQL pairs it with.

    Raises
    ------
    AssertionError
        If the SQL file is absent, which would mean the path had moved and every assertion built
        on it was checking an empty set.
    """
    assert _ROW_COUNTS_SQL.is_file(), f"verification SQL not found at {_ROW_COUNTS_SQL}"
    sql = _ROW_COUNTS_SQL.read_text(encoding="utf-8")
    return {
        match.group("table"): match.group("dataset") for match in _REGISTRATION_ROW.finditer(sql)
    }


def _migration_text() -> str:
    """Concatenate every service Flyway migration into one searchable body of DDL.

    Purpose
    -------
    Give the column-existence assertion one text to search, so a target's column is accepted
    when ANY owning service declares it and no assertion has to know which service owns which
    schema.

    Returns
    -------
    str
        Every ``V*.sql`` under ``services/*/src/main/resources/db/migration``, joined.

    Raises
    ------
    AssertionError
        If no migration file is found at all, which would mean the path had moved and the
        assertion below was searching an empty string -- passing while proving nothing.
    """
    files = sorted(_SERVICES_ROOT.glob("*/src/main/resources/db/migration/V*.sql"))
    assert files, f"no Flyway migration found beneath {_SERVICES_ROOT}"
    return "\n".join(path.read_text(encoding="utf-8") for path in files)


def _table_ddl(schema: str, table: str) -> str:
    """Extract one table's ``CREATE TABLE`` block from the shipped service migrations.

    Purpose
    -------
    Let a constraint assertion be scoped to the table it is about. The ledger migration declares
    four tables in one file, and three of them carry a ``transaction_id`` column, so searching the
    whole file for ``PRIMARY KEY (transaction_id)`` answers a question about
    ``ledger.transactions`` when the question was about ``ledger.daily_transactions``.

    Parameters
    ----------
    schema : str
        The owning schema, as the migration spells it in the qualified table name.
    table : str
        The table within that schema.

    Returns
    -------
    str
        The text from the ``CREATE TABLE`` line to the statement's closing parenthesis.

    Raises
    ------
    AssertionError
        If no migration declares that table, which would leave the assertion searching an empty
        string and passing while proving nothing.
    """
    opening = f"CREATE TABLE {schema}.{table} ("
    for path in sorted(_SERVICES_ROOT.glob("*/src/main/resources/db/migration/V*.sql")):
        text = path.read_text(encoding="utf-8")
        if opening not in text:
            continue
        body = text[text.index(opening) :]
        # Assumptions: the block ends at the first line that is exactly `);`, which is how every
        #   shipped migration closes a CREATE TABLE. A parenthesis counter was considered and
        #   rejected as more machinery than the fixed house formatting needs.
        closing = body.index("\n);")
        return body[:closing]
    raise AssertionError(f"no shipped migration declares {schema}.{table}")


def test_the_declared_targets_are_exactly_the_eleven_loadable_records() -> None:
    """Declare a load target for exactly the eleven records this module can load.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the declared target set differs from the stated one in either direction.
    """
    assert target_names() == _LOADABLE_RECORDS


def test_every_dataset_the_verification_sql_registers_has_a_load_target() -> None:
    """Declare a load target for every dataset the verification SQL claims to check.

    Purpose
    -------
    Close the gap that made the category-balance dataset unloadable: the whole-schema
    verification queries registered it, and the loader did not carry a target for it, so the
    dataset was reported as verified while no code in this package could put a row in its
    table. This assertion is deliberately driven from the SQL rather than from a second list,
    so it fails on either side of that pair going out of step.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the SQL registers a dataset whose layout has a reader but no load target, or if the
        registration block cannot be found at all -- which would make this assertion pass while
        proving nothing.
    """
    registered = _datasets_registered_for_verification()
    assert registered, (
        f"no dataset registration row found in {_ROW_COUNTS_SQL}; the assertion would pass"
        " while checking an empty set"
    )
    # WHY : Assumptions: the comparison is by TABLE rather than by dataset name, because the
    #   two vocabularies differ on purpose -- the SQL names the baseline dataset (`tcatbal`,
    #   `cardxref`) and this module names the record layout (`TCATBAL`, `XREF`). Matching on
    #   the schema-qualified table is the one identifier both sides state identically, so the
    #   assertion needs no translation table that could itself go stale.
    targeted = {f"{target_for(name).schema}.{target_for(name).table}" for name in target_names()}
    unloadable = sorted(
        table
        for table in registered
        if table not in targeted and table not in _NO_LOAD_TARGET_BY_DESIGN
    )
    assert not unloadable, (
        "the verification SQL registers a dataset for these tables but no load target can fill"
        f" them: {unloadable}"
    )


def test_no_table_is_both_targeted_and_excused_from_having_a_target() -> None:
    """Keep the excused-table mapping honest by refusing an entry that also has a load target.

    Purpose
    -------
    Stop the excuse list becoming the way a future gap is hidden. Left unchecked, adding a
    target while leaving its table excused would keep the general assertion above green for the
    wrong reason, and the excuse's stated justification would be false about delivered code.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a table appears both in :data:`_NO_LOAD_TARGET_BY_DESIGN` and among the declared
        targets, or if an excused table is not one the verification SQL registers at all.
    """
    targeted = {f"{target_for(name).schema}.{target_for(name).table}" for name in target_names()}
    contradictory = sorted(targeted & set(_NO_LOAD_TARGET_BY_DESIGN))
    assert not contradictory, (
        f"these tables have a load target and are also excused from having one: {contradictory}"
    )
    registered = _datasets_registered_for_verification()
    # WHY : Assumptions: an excuse for a table the SQL does not register is dead weight that
    #   would go on excusing a table nothing checks, so it is refused too. That keeps the
    #   mapping's size a measure of the real exemptions rather than an accumulation.
    unregistered = sorted(set(_NO_LOAD_TARGET_BY_DESIGN) - set(registered))
    assert not unregistered, (
        f"these tables are excused but the verification SQL registers no dataset for them:"
        f" {unregistered}"
    )


def test_the_category_balance_target_maps_the_composite_key_and_the_balance() -> None:
    """Map the transaction-category-balance record onto its four ledger columns, in key order.

    Purpose
    -------
    Assert the mapping the review prescribed, field by field, rather than only that the target
    exists. The three key components and the balance are the whole record once the trailing pad
    is dropped, so a target that mapped three of the four would load rows the primary key
    accepted and the money-parity pass then reported as short by the whole balance total.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the target names another schema or table, maps a different field set, maps them in
        another order, or names a column other than the ledger table's own.
    """
    target = target_for("TCATBAL")
    assert (target.schema, target.table) == ("ledger", "transaction_category_balances")
    # WHY : Assumptions: the pairs are asserted as an ORDERED tuple and not as a mapping
    #   equality, because iteration order is the COPY column order. A target that mapped the
    #   right four columns in the wrong order would emit a COPY whose column list disagreed
    #   with the row tuples written into it, and `type_cd` and `category_cd` are both CHAR, so
    #   a transposition of those two would load silently and put a two-character code in the
    #   four-character column.
    assert tuple(target.columns.items()) == (
        ("TRANCAT-ACCT-ID", "account_id"),
        ("TRANCAT-TYPE-CD", "type_cd"),
        ("TRANCAT-CD", "category_cd"),
        ("TRAN-CAT-BAL", "balance"),
    )
    # WHY : Assumptions: the composite key's arity comes from the reader's own published
    #   derivation rather than from a list restated here, so the two cannot agree by
    #   coincidence. That constant is derived from the descriptor's key span, so this assertion
    #   fails if the key ever gains or loses a component while the mapping above stands still --
    #   which is the failure mode the identically-named key group on the category REFERENCE
    #   record makes plausible: that one brackets two fields where this brackets three.
    assert COMPOSITE_KEY_FIELD_NAMES == ("TRANCAT-ACCT-ID", "TRANCAT-TYPE-CD", "TRANCAT-CD")
    assert set(COMPOSITE_KEY_FIELD_NAMES) < set(target.columns)
    layout = layouts.LAYOUTS["TCATBAL"]
    # WHY : the money field is asserted to be the SIGNED display regime, because that is what
    #   makes it decode to an exact Decimal rather than to characters. The balance carries a sign
    #   overpunch in the shipped seed -- `0000000000{` in bytes 18-28 -- and a field read as
    #   unsigned would hand the loader the overpunch character itself.
    assert layout.field("TRAN-CAT-BAL").kind is layouts.Kind.ZONED
    # WHY : the trailing pad is asserted ABSENT from the mapping rather than merely unmentioned.
    #   The layout declares it, every reader drops it, and a target naming it would raise the
    #   "missing mapped field" error on the first record -- a load that could never succeed.
    assert "FILLER" not in target.columns


@pytest.mark.parametrize("record", _LOADABLE_RECORDS)
def test_every_target_maps_only_fields_its_reader_publishes(record: str) -> None:
    """Map only fields the record's own layout declares and its reader loads.

    Parameters
    ----------
    record : str
        One loadable record name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a mapped field is not declared by the layout, or is one the reader drops as padding.
    """
    target = target_for(record)
    layout = layouts.LAYOUTS[record]
    declared = {field.name for field in layout.fields}
    # WHY : Assumptions: the target's OWN declared derived set is required to be a subset of the
    #   one this file states, rather than simply trusted. A target free to declare any key derived
    #   could exempt a misspelled copybook field from this assertion and load nothing into a
    #   column that looked mapped -- so the exemption is bounded here, at the test, and a new
    #   derived key is a visible edit rather than a silent one in the module under test.
    assert target.derived_fields <= _DERIVED_KEYS, (
        f"{record} declares a derived field this suite does not admit:"
        f" {sorted(target.derived_fields - _DERIVED_KEYS)}"
    )
    for field_name in target.columns:
        if field_name in target.derived_fields:
            continue
        assert field_name in declared, f"{record} target maps undeclared field {field_name}"
        # WHY : a mapped field is asserted NOT to be padding. Padding is dropped by every
        #   reader, so a target naming it would raise the "missing mapped field" error on the
        #   very first record -- a load that could never succeed, declared as if it could.
        assert not layout.field(field_name).name.endswith("FILLER")


@pytest.mark.parametrize("record", _LOADABLE_RECORDS)
def test_every_target_column_exists_in_a_shipped_migration(record: str) -> None:
    """Name only columns the owning service's Flyway migration really creates.

    Parameters
    ----------
    record : str
        One loadable record name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a mapped column name appears in no migration, or the target's table does not.
    """
    ddl = _migration_text()
    target = target_for(record)
    assert re.search(rf"\b{target.schema}\.{target.table}\b", ddl), (
        f"{target.schema}.{target.table} is created by no shipped migration"
    )
    for column in target.columns.values():
        # WHY : Trade-offs: the search is for the column NAME as a word anywhere in the
        #   concatenated DDL, not for its declaration within the right CREATE TABLE. Parsing
        #   the DDL properly would need a SQL parser this suite has no reason to carry, and the
        #   weaker assertion still catches the failure that matters -- a column this loader
        #   names that no migration creates anywhere, which is a load that aborts on its first
        #   COPY. The accepted cost is that a column belonging to a different table would
        #   satisfy it.
        assert re.search(rf"\b{column}\b", ddl), f"column {column} is declared by no migration"


@pytest.mark.parametrize("record", _BATCH_WRITTEN_RECORDS)
def test_a_layout_the_batch_chain_writes_is_refused_with_its_reason(record: str) -> None:
    """Refuse a load target for a layout the batch chain writes, and say that is why.

    Parameters
    ----------
    record : str
        One registered layout that the batch chain writes and nothing reads in.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the layout has a target, or is refused without naming the no-load-direction reason.
    """
    # WHY : Refactoring Rationale: this test used to run over CUSTOMER, CARD and TRAN as well.
    #   CUSTOMER and CARD required the refusal to say the word "ciphertext", pinning as a contract
    #   that the customer and card masters had no migration path; both now load through
    #   `loaders/protected_columns.py`. TRAN required it to say "no extract", pinning the seed
    #   corpus's contents as a property of the transaction master; it now loads, and an empty
    #   extract is a zero-row success. What is pinned here instead is the one refusal that is
    #   genuinely a design decision: a layout with no load direction at all.
    assert record not in target_names()
    # WHY : Assumptions: the dispatch mapping is asserted to publish no reader for the layout,
    #   which is the fact the refusal message states. Without this the message could keep claiming
    #   a layout is readerless after a reader had been added for it.
    assert record not in DATASET_READERS
    with pytest.raises(AuroraLoadError) as refused:
        target_for(record)
    message = str(refused.value)
    # WHY : the REASON is asserted, not merely the refusal. "No target" reads as an omission
    #   someone should fill in; "written BY the batch chain ... so there is no load direction for
    #   it" tells the next reader that adding a mapping would be loading a table from a layout
    #   nothing produces as input.
    assert "written BY the batch" in message
    assert "no load direction" in message
    assert record in message


def test_every_protected_column_is_declared_sealed_rather_than_verbatim() -> None:
    """Require every ``*_encrypted`` column this module loads to declare a sealing projection.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any target maps a field to a protected column without declaring a sealing projection.
    """
    # WHY : Assumptions: the check is driven by the COLUMN NAME rather than by a list of fields,
    #   so a protected column added to any target in future is covered without this test being
    #   revised. It is the single most consequential declaration in the module: a protected column
    #   left VERBATIM would load a national identifier, a government-issued identifier or a card
    #   verification value as PLAINTEXT into a column named `*_encrypted`, and the load would
    #   succeed. Nothing downstream reads those columns during a migration, so nothing else in
    #   this suite or in the verification passes could report it.
    sealing = {Projection.SEALED_IDENTIFIER, Projection.SEALED_VERIFICATION_VALUE}
    found = 0
    for record in _LOADABLE_RECORDS:
        target = target_for(record)
        for field_name, column in target.columns.items():
            if not column.endswith("_encrypted"):
                continue
            found += 1
            assert target.projections.get(field_name) in sealing, (
                f"{record} maps {field_name} to protected column {column} without a sealing"
                " projection, so it would load plaintext"
            )
    # WHY : the count is asserted so the loop cannot pass by finding nothing. Three protected
    #   columns exist across the ten targets -- the two customer identifiers and the card
    #   verification value -- and a rename that took one of them out of the `*_encrypted` naming
    #   would otherwise reduce this test to a tautology.
    assert found == 3


def test_an_unregistered_record_has_no_target() -> None:
    """Refuse a target for a name the layout registry does not hold.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an unknown record name yields a target instead of a refusal.
    """
    with pytest.raises(AuroraLoadError):
        target_for("NO-SUCH-RECORD")


@pytest.mark.parametrize("record", _LOADABLE_RECORDS)
def test_the_staging_copy_names_every_column_explicitly(record: str) -> None:
    """Emit a COPY that lists its columns, quoted, in the target's own declared order.

    Parameters
    ----------
    record : str
        One loadable record name.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the statement omits the column list, quotes nothing, names a qualified staging table,
        or lists the columns in an order other than the target's.
    """
    target = target_for(record)
    statement = target.stage_copy_statement()
    matched = _STAGE_COPY_SHAPE.match(statement)
    assert matched, f"{record} COPY does not match the expected shape: {statement}"
    # WHY : Assumptions: the staging table is asserted UNQUALIFIED, which is the one mistake this
    #   name must not make. `CREATE TEMPORARY TABLE "schema"."name"` is an error in PostgreSQL, and
    #   a qualified name in the COPY would mean the staging table had been created somewhere
    #   permanent -- in the target's own schema, which the loading role has no CREATE on anyway.
    assert matched.group("stage") == f"carddemo_stage_{target.table}"
    assert "." not in matched.group("stage")
    # WHY : the ORDER is asserted, not just the membership. A COPY whose column list is a
    #   permutation of the right names loads every value into the wrong column of the right
    #   table, and where the adjacent types agree -- two NUMERIC(11,2) money columns, say -- it
    #   commits successfully. That is the failure an explicit column list exists to prevent, so
    #   the order is the assertion.
    expected = ", ".join(quote_identifier(column) for column in target.columns.values())
    assert matched.group("columns") == expected
    # WHY : Assumptions: the merge's own column list is compared against the same expectation, so
    #   the two halves of one load cannot disagree. A staging COPY and a merge that named the same
    #   columns in different orders would move every value into the wrong column, and both
    #   statements would be individually well formed.
    assert f"({expected})" in target.merge_statement()


def test_row_of_orders_values_by_the_declared_column_order() -> None:
    """Project a decoded record into the tuple order the COPY column list declares.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the projected tuple is not in the target's declared field order.
    """
    target = TableTarget(
        schema="reference",
        table="transaction_types",
        columns={"TRAN-TYPE": "type_cd", "TRAN-TYPE-DESC": "description"},
    )
    # WHY : Assumptions: the record is given with its keys in the OPPOSITE order to the
    #   target's, so a projection that happened to iterate the record instead of the mapping
    #   would produce a reversed tuple and fail. Supplying them in matching order would let
    #   both implementations pass.
    record = {"TRAN-TYPE-DESC": "SYNTHETIC DESCRIPTION", "TRAN-TYPE": "99"}
    assert target.row_of(record) == ("99", "SYNTHETIC DESCRIPTION")


def test_row_of_refuses_a_missing_field_and_renders_no_value() -> None:
    """Refuse a record lacking a mapped field, naming the field and none of the record's values.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal omits the missing field name, or repeats any value the record carried.
    """
    target = TableTarget(
        schema="account",
        table="card_xref",
        columns={
            "XREF-CARD-NUM": "card_num",
            "XREF-CUST-ID": "customer_id",
            "XREF-ACCT-ID": "account_id",
        },
    )
    present = {"XREF-CARD-NUM": "4111111111111111", "XREF-CUST-ID": "000000001"}
    with pytest.raises(AuroraLoadError) as refused:
        target.row_of(present)
    message = str(refused.value)
    assert "XREF-ACCT-ID" in message
    # WHY : the values are asserted ABSENT from the diagnostic. A decoded cross-reference
    #   record carries a primary account number, and this message is written to a log that
    #   outlives the load and is readable by everyone holding log access -- so the field names
    #   are the diagnostic and the values are not part of it.
    for value in present.values():
        assert value not in message, f"{value!r} reached the failure diagnostic"


def test_load_records_stages_every_row_and_commits(fake_aurora: FakeAuroraDatabase) -> None:
    """Stage every supplied record through one COPY, merge it, and commit once.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the row count returned differs from the records supplied, if the rows are not the
        projected tuples, if the three statements are issued out of order, or if the transaction
        did not commit exactly once.
    """
    # WHY : Refactoring Rationale: this test asserted the DIRECT copy path, which no longer
    #   exists -- every target stages and merges, because a re-run is a normal event for all
    #   eleven. It keeps its target and its records and now pins the three-statement sequence a
    #   load issues, which is the property that replaced "one COPY straight into the table".
    target = target_for("TCATBAL")
    records = [
        {
            "TRANCAT-ACCT-ID": "00000000001",
            "TRANCAT-TYPE-CD": "01",
            "TRANCAT-CD": "0001",
            "TRAN-CAT-BAL": Decimal("10.00"),
        },
        {
            "TRANCAT-ACCT-ID": "00000000002",
            "TRANCAT-TYPE-CD": "02",
            "TRANCAT-CD": "0003",
            "TRAN-CAT-BAL": Decimal("-4.50"),
        },
    ]
    connection = fake_aurora.connect(**_connection_params())
    # Assumptions: the affected-row count is arranged to equal the staged count, which is what a
    #   server reports for a merge into a table holding none of the keys. Without an arrangement
    #   the double reports none and the loader falls back to the staged count, so the assertion
    #   below would hold for the wrong reason.
    fake_aurora.arrange_affected_rows("INSERT INTO", len(records))
    outcome = load_records(connection, target, records)
    assert outcome.staged == len(records)
    assert outcome.inserted == len(records)
    assert outcome.skipped == 0
    assert fake_aurora.copy_statements == [target.stage_copy_statement()]
    # WHY : the ORDER of the three statements is asserted, not merely their presence. A merge
    #   issued before the COPY would insert nothing and still commit, reporting a successful load
    #   of an empty table; a staging table created after the COPY could not have received it.
    executed = fake_aurora.executed_sql()
    assert executed.index(target.stage_statement()) < executed.index(target.stage_copy_statement())
    assert executed.index(target.stage_copy_statement()) < executed.index(target.merge_statement())
    # WHY : Assumptions: the double records each copied row PAIRED with the statement it was
    #   written under, so the statement is projected away here rather than the pair being
    #   compared against a bare tuple. Keeping the pair is what lets the assertion below prove
    #   the rows went through the one COPY this target declares and not some other.
    assert [statement for statement, _ in fake_aurora.copied_rows] == [
        target.stage_copy_statement()
    ] * len(records)
    assert [row for _, row in fake_aurora.copied_rows] == [
        ("00000000001", "01", "0001", Decimal("10.00")),
        ("00000000002", "02", "0003", Decimal("-4.50")),
    ]
    assert fake_aurora.commits == 1
    assert fake_aurora.rollbacks == 0


def test_load_records_preserves_an_exact_decimal_unrounded(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Hand the driver the exact decimal the reader decoded, at its own scale.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the copied value is not the identical decimal, or its scale was normalised away.
    """
    target = target_for("DISGROUP")
    rate = Decimal("-12.30")
    connection = fake_aurora.connect(**_connection_params())
    load_records(
        connection,
        target,
        [
            {
                "DIS-ACCT-GROUP-ID": "SYNTHGRP01",
                "DIS-TRAN-TYPE-CD": "01",
                "DIS-TRAN-CAT-CD": "0005",
                "DIS-INT-RATE": rate,
            }
        ],
    )
    copied = fake_aurora.copied_rows[0][1][-1]
    # WHY : the SCALE is asserted as well as the value, because `Decimal("-12.30")` and
    #   `Decimal("-12.3")` compare equal and are different stored values in a NUMERIC(6,2). A
    #   value-only assertion would pass against a loader that had normalised the scale away,
    #   and the money-parity check would then compare two totals that agreed while the column
    #   held something the extract did not.
    assert copied == rate
    assert copied.as_tuple().exponent == rate.as_tuple().exponent


def test_load_records_rolls_back_before_reraising(fake_aurora: FakeAuroraDatabase) -> None:
    """Roll the transaction back, and never commit, when a record fails partway through.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the failure commits, does not roll back, or copies the failing record.
    """
    target = target_for("TCATBAL")
    records = [
        {
            "TRANCAT-ACCT-ID": "00000000001",
            "TRANCAT-TYPE-CD": "01",
            "TRANCAT-CD": "0001",
            "TRAN-CAT-BAL": Decimal("10.00"),
        },
        {"TRANCAT-ACCT-ID": "00000000002", "TRANCAT-TYPE-CD": "02"},
    ]
    connection = fake_aurora.connect(**_connection_params())
    with pytest.raises(AuroraLoadError):
        load_records(connection, target, records)
    # WHY : Assumptions: the failure is placed on the SECOND record deliberately, so the
    #   loader has already written one row when it fails. A first-record failure would roll
    #   back an empty transaction, which every implementation gets right; only a partway
    #   failure distinguishes a loader that rolls back from one that leaves a row behind.
    assert [row for _, row in fake_aurora.copied_rows] == [
        ("00000000001", "01", "0001", Decimal("10.00"))
    ]
    assert fake_aurora.rollbacks == 1
    assert fake_aurora.commits == 0


def test_load_records_rolls_back_a_decode_failure_and_wraps_it(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Roll back and wrap a failure that is not already a load error, such as a decode failure.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the failure commits, does not roll back, or escapes unwrapped.
    """

    def failing_stream() -> Iterator[dict[str, object]]:
        """Yield one good record, then fail the way a truncated extract fails.

        Yields
        ------
        dict[str, object]
            One valid category-balance record.

        Raises
        ------
        RecordLengthError
            Always, after the first record, standing for the width failure a reader raises on
            a truncated extract.
        """
        yield {
            "TRANCAT-ACCT-ID": "00000000001",
            "TRANCAT-TYPE-CD": "01",
            "TRANCAT-CD": "0001",
            "TRAN-CAT-BAL": Decimal("10.00"),
        }
        raise layouts.RecordLengthError("record 2 of TCATBAL is 49 characters against 50")

    target = target_for("TCATBAL")
    connection = fake_aurora.connect(**_connection_params())
    # WHY : this exercises the SECOND of the loader's two failure arms, and it was added
    #   because a mutation that made that arm commit instead of roll back survived a suite
    #   covering only the first. The two arms differ in kind: a missing mapped field is already
    #   an AuroraLoadError and is re-raised, whereas anything a lazy reader raises mid-stream --
    #   a width failure, a decode failure, a driver error -- arrives as a foreign exception and
    #   must be both rolled back AND wrapped, so a caller branching on the load's own error type
    #   still sees it.
    with pytest.raises(AuroraLoadError) as refused:
        load_records(connection, target, failing_stream())
    message = str(refused.value)
    assert "rolled back" in message
    # WHY : the count is asserted as "staged" rows, which is the only count a partway failure can
    #   truthfully report. Nothing had reached the table yet -- the merge had not run -- so a
    #   message claiming rows were loaded would misdescribe a transaction that inserted none.
    assert "1 staged row(s)" in message
    assert fake_aurora.rollbacks == 1
    assert fake_aurora.commits == 0


def test_the_loader_issues_no_privileged_statement(fake_aurora: FakeAuroraDatabase) -> None:
    """Issue no schema, role, grant, delete, truncate or index statement while loading.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double, which classifies every statement it is given.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the loader issued any statement the double classifies as privileged.
    """
    target = target_for("XREF")
    connection = fake_aurora.connect(**_connection_params())
    load_records(
        connection,
        target,
        [
            {
                "XREF-CARD-NUM": "4111111111111111",
                "XREF-CUST-ID": "000000001",
                "XREF-ACCT-ID": "00000000011",
            }
        ],
    )
    # WHY : this is asserted rather than assumed from the connection role's grants, because the
    #   grants are applied by a bootstrap this suite does not run. The loader's own statement
    #   log is the only evidence available here that it stays inside the DML the role holds --
    #   in particular that it never clears the table before loading it, which would turn a
    #   re-run into silent data loss rather than the duplicate-key failure it should be.
    assert fake_aurora.forbidden_statements() == ()


@pytest.mark.parametrize("record", _KEYED_MERGE_RECORDS)
def test_every_keyed_merge_conflicts_on_a_key_its_own_migration_declares_unique(
    record: str,
) -> None:
    """Prove each derived conflict target is backed by a unique constraint in the shipped DDL.

    Parameters
    ----------
    record : str
        One record whose target merges on the record's own key window.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a target derives no key, or derives one no owning migration declares unique -- which
        PostgreSQL would refuse at run time for want of a matching index.
    """
    target = target_for(record)
    assert target.strategy is LoadStrategy.KEYED_MERGE
    assert target.key_columns, f"{record} derives no key from its descriptor"
    clause = ", ".join(target.key_columns)
    if record in _SEED_MERGED_RECORDS:
        seed = (
            _SERVICES_ROOT
            / "reference-service/src/main/resources/db/migration/V2__seed_reference.sql"
        ).read_text(encoding="utf-8")
        # WHY : Assumptions: the conflict target is checked against the SEED MIGRATION's own
        #   `ON CONFLICT` clause, read from disk, rather than against a key written into this file.
        #   The two writers must conflict on the same key or they do not compose: if the loader
        #   conflicted on a subset it would silently skip rows the migration had not written, and
        #   if it conflicted on a superset PostgreSQL would refuse the statement for want of a
        #   matching unique index. Comparing against the other writer's clause is the only
        #   assertion that catches either.
        assert f"ON CONFLICT ({clause}) DO NOTHING" in seed, (
            f"{record} merges on ({clause}), which is not the conflict target"
            " V2__seed_reference.sql uses for the same table"
        )
    # WHY : Assumptions: every keyed target -- the three seeded ones included -- is ALSO checked
    #   against the unique constraint in the migration that owns its table, because that is the
    #   index PostgreSQL matches an `ON CONFLICT` target against. The seed migration's clause
    #   proves the two writers agree; only the DDL proves the key is supported at all. A target
    #   deriving a key from a record whose window happened to cover a non-unique column would
    #   satisfy the first check and fail at run time on the second.
    # WHY : Assumptions: the DDL searched is the TARGET TABLE's own block rather than the whole
    #   migration set, because three ledger tables carry a `transaction_id` column and a
    #   file-wide search would let one table's primary key vouch for another's.
    ddl = _table_ddl(target.schema, target.table)
    if len(target.key_columns) == 1:
        # Assumptions: a single-column key may be declared INLINE on its column --
        #   `user_id CHAR(8) PRIMARY KEY` in `V1__auth.sql` -- as well as as a named table
        #   constraint, so the inline form is matched with the column type between the two, which
        #   a plain substring cannot express.
        inline = re.compile(
            rf"^\s*{re.escape(clause)}\s+[^\n,]*\b(PRIMARY KEY|UNIQUE)\b", re.MULTILINE
        )
        assert (
            f"PRIMARY KEY ({clause})" in ddl
            or f"UNIQUE ({clause})" in ddl
            or inline.search(ddl) is not None
        ), (
            f"{record} merges on ({clause}), which {target.schema}.{target.table} does not declare"
            " as a primary key or a unique constraint, so no index would support the conflict"
            " target"
        )
    else:
        assert f"PRIMARY KEY ({clause})" in ddl, (
            f"{record} merges on the composite ({clause}), which {target.schema}.{target.table}"
            " does not declare as its primary key, so no index would support the conflict target"
        )


@pytest.mark.parametrize("record", _WHOLE_ROW_MERGE_RECORDS)
def test_a_table_with_no_unique_key_merges_on_the_whole_row(record: str) -> None:
    """Prove the one target whose table has no natural unique index anti-joins instead.

    Parameters
    ----------
    record : str
        The record whose target table is keyed on an identity column.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the target names an `ON CONFLICT` target, or if the migration it loads into turns out
        to declare the unique constraint that would have made one possible.
    """
    target = target_for(record)
    assert target.strategy is LoadStrategy.WHOLE_ROW_MERGE
    statement = target.merge_statement()
    assert "ON CONFLICT" not in statement
    assert "WHERE NOT EXISTS" in statement
    # WHY : Assumptions: the ABSENCE of a unique constraint is asserted against the shipped DDL, so
    #   this strategy cannot outlive the reason for it. If a later migration gave
    #   `ledger.daily_transactions` a unique key over its business columns, the cheaper keyed merge
    #   would become available and this test would fail rather than leaving the anti-join in place
    #   as folklore.
    ddl = _table_ddl(target.schema, target.table)
    clause = ", ".join(target.key_columns)
    assert f"PRIMARY KEY ({clause})" not in ddl
    assert f"UNIQUE ({clause})" not in ddl
    assert "pk_daily_transactions PRIMARY KEY (ingest_seq)" in ddl
    # WHY : Assumptions: the sibling table's key is asserted too, because it is the reason the
    #   scoped extraction above matters: `ledger.transactions` DOES declare
    #   `PRIMARY KEY (transaction_id)`, in the same migration file, so a whole-file search would
    #   have found it and concluded that this table could conflict on that column.
    assert f"PRIMARY KEY ({clause})" in _table_ddl(target.schema, "transactions")
    # WHY : Assumptions: the key columns are still DERIVED for this target even though the merge
    #   does not use them, and that is asserted rather than left ambiguous. The descriptor's key
    #   window is a fact about the RECORD -- `DALYTRAN-ID` at offset zero -- and stays true whatever
    #   the table does with it; what the strategy records is that the TABLE cannot conflict on it.
    assert target.key_columns == ("transaction_id",)


def test_load_records_merges_staged_rows_on_the_declared_key(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Stage into a temporary table, then merge on the declared key, in that order, once.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the COPY does not target the staging table, if the two statements are absent or out
        of order, or if the transaction did not commit exactly once.
    """
    target = target_for("TRANTYPE")
    records = [
        {"TRAN-TYPE": "98", "TRAN-TYPE-DESC": "SYNTHETIC ONE" + " " * 37},
        {"TRAN-TYPE": "99", "TRAN-TYPE-DESC": "SYNTHETIC TWO" + " " * 37},
    ]
    fake_aurora.arrange_affected_rows("insert into", 2)
    connection = fake_aurora.connect(**_connection_params())
    outcome = load_records(connection, target, records)
    assert outcome.staged == 2
    assert outcome.inserted == 2
    # WHY : Assumptions: the COPY goes to the STAGING table and not to the target, which is the
    #   whole mechanism. A COPY straight into the target cannot express a conflict clause at all,
    #   so a loader that kept copying directly and merely appended an insert afterwards would
    #   still abort on the first key already present.
    assert fake_aurora.copy_statements == [target.stage_copy_statement()]
    assert target.stage_name in target.stage_copy_statement()
    # WHY : the ORDER is asserted, not just the presence. The staging table has to exist before
    #   the COPY and the merge has to follow it; a merge issued first would insert nothing and
    #   report success, which is exactly the silent outcome this path exists to avoid.
    executed = fake_aurora.executed_sql()
    stage_at = executed.index(target.stage_statement())
    merge_at = executed.index(target.merge_statement())
    assert stage_at < merge_at
    assert fake_aurora.commits == 1
    assert fake_aurora.rollbacks == 0
    # WHY : the merged description is asserted TRIMMED. `V2__seed_reference.sql` writes
    #   'SYNTHETIC ONE' with no padding, so a loader carrying the copybook's fifty-character
    #   blank-padded form would produce a row differing from the migration's in content while
    #   agreeing in count -- which the row-count verification pass cannot see.
    assert [row for _, row in fake_aurora.copied_rows] == [
        ("98", "SYNTHETIC ONE"),
        ("99", "SYNTHETIC TWO"),
    ]


def test_a_merge_into_an_already_seeded_table_reports_every_row_skipped(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Report rows staged and none inserted, and commit, when the target already holds them.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the load fails, rolls back, or reports the staged rows as inserted.
    """
    # WHY : Assumptions: this is the case the finding was about. `V2__seed_reference.sql` may
    #   already have written every row, and before the merge path a plain COPY aborted on the
    #   first primary-key collision -- so the documented claim that the counts "hold whether the
    #   seed migration ran, the ETL ran, or both did" was false. What must happen instead is a
    #   clean commit reporting that nothing was added, which is a success and has to read as one.
    target = target_for("TRANCAT")
    fake_aurora.arrange_affected_rows("insert into", 0)
    connection = fake_aurora.connect(**_connection_params())
    outcome = load_records(
        connection,
        target,
        [{"TRAN-TYPE-CD": "01", "TRAN-CAT-CD": "0001", "TRAN-CAT-TYPE-DESC": "Regular Sales"}],
    )
    assert outcome.staged == 1
    assert outcome.inserted == 0
    assert outcome.skipped == 1
    assert "already present" in outcome.describe()
    assert fake_aurora.commits == 1
    assert fake_aurora.rollbacks == 0


def _transaction_record(transaction_id: str) -> dict[str, object]:
    """Build one decoded transaction-master record in the shape its reader publishes.

    Purpose
    -------
    Supply a `TRAN` record for the load-path tests without restating a layout. Only the mapped
    field NAMES appear, taken from the target's own column mapping, and the widths are the
    copybook's so that the trimming and stamp projections have something real to act on.

    Parameters
    ----------
    transaction_id : str
        The sixteen-character key the row carries, which is what a re-run collides on.

    Returns
    -------
    dict[str, object]
        One record keyed by copybook field name, including the `FILLER` a reader drops, so that a
        preparation copying the record forward rather than projecting it would be caught.

    Raises
    ------
    None
    """
    return {
        "TRAN-ID": transaction_id,
        "TRAN-TYPE-CD": "01",
        "TRAN-CAT-CD": "0001",
        "TRAN-SOURCE": "POS TERM  ",
        "TRAN-DESC": "Purchase at Abshire-Lowe" + " " * 76,
        "TRAN-AMT": Decimal("504.77"),
        "TRAN-MERCHANT-ID": "800000000",
        "TRAN-MERCHANT-NAME": "Abshire-Lowe" + " " * 38,
        "TRAN-MERCHANT-CITY": "North Enoshaven" + " " * 35,
        "TRAN-MERCHANT-ZIP": "72112     ",
        "TRAN-CARD-NUM": "4859452612877065",
        "TRAN-ORIG-TS": "2022-07-18 10:30:00.123456",
        "TRAN-PROC-TS": "2022-07-18 10:30:00.123456",
        "FILLER": " " * 20,
    }


def test_reloading_the_transaction_master_adds_nothing_and_raises_nothing(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Leave the row count unchanged, and let no duplicate-key error escape, on a second load.

    Purpose
    -------
    Pin idempotency on a LEDGER target rather than only on a reference one. The reference tables
    compose with a migration; this one composes with the posting job, and it is the target where a
    redriven cutover load actually re-runs, so the second-run behaviour has to be asserted here
    too and not inferred from the reference case.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the second load reports rows added, fails, or rolls back.
    """
    target = target_for("TRAN")
    records = [_transaction_record("0000000000683580")]
    # WHY : Assumptions: the first run's merge is arranged to report one row added and the second
    #   run's to report none, which is what PostgreSQL answers for `ON CONFLICT DO NOTHING` against
    #   a row already present. Arranging both is what makes the two runs distinguishable; deriving
    #   the count would make an inserting load and a no-op load report the same number.
    fake_aurora.arrange_affected_rows("insert into", 1)
    first = load_records(fake_aurora.connect(**_connection_params()), target, records)
    assert first.staged == 1
    assert first.inserted == 1
    fake_aurora.arrange_affected_rows("insert into", 0)
    second = load_records(fake_aurora.connect(**_connection_params()), target, records)
    # WHY : the second run is asserted to COMMIT while adding nothing. A plain COPY would abort on
    #   `pk_transactions` and surface a duplicate-key error, which on this table would misreport a
    #   correctly-posted row as a load fault and would break Step Functions redrive outright.
    assert second.staged == 1
    assert second.inserted == 0
    assert second.skipped == 1
    assert fake_aurora.rollbacks == 0
    assert fake_aurora.commits == 2
    # WHY : the table is never cleared between runs. Idempotency achieved by emptying the table
    #   first would discard rows the posting job had written, and `V0__schemas_and_roles.sql`
    #   withholds DELETE and TRUNCATE from every role precisely so that cannot be the mechanism.
    assert fake_aurora.forbidden_statements() == ()


def test_an_absent_transaction_extract_loads_zero_rows_and_succeeds(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Commit a zero-row load when the transaction extract yields no records at all.

    Purpose
    -------
    Pin the case that made this target look unloadable. The seed corpus ships no `TRANSACT`
    extract, `readers/transaction.py` treats an absent source as a normal state, and
    `sql/verify/row_counts.sql` gives the table a NULL baseline -- so an empty stream is the
    NORMAL outcome of a corpus-only run and has to commit rather than raise.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an empty stream raises, rolls back, or reports rows it did not load.
    """
    target = target_for("TRAN")
    fake_aurora.arrange_affected_rows("insert into", 0)
    # WHY : Assumptions: the empty case is exercised through an exhausted GENERATOR rather than an
    #   empty list, because that is what a reader hands the loader -- `readers/transaction.py`
    #   yields nothing for an absent or empty source. A list would also pass while proving nothing
    #   about the streaming path, which is the path a real load takes.
    outcome = load_records(
        fake_aurora.connect(**_connection_params()),
        target,
        (record for record in ()),
    )
    assert outcome.staged == 0
    assert outcome.inserted == 0
    assert fake_aurora.commits == 1
    assert fake_aurora.rollbacks == 0
    # WHY : no row is written, but the staging table and the merge ARE issued. A load that skipped
    #   the statements on an empty stream would leave the empty case exercising a different code
    #   path from every other run, so a fault in the merge would first appear on the day an extract
    #   finally arrived.
    assert [row for _, row in fake_aurora.copied_rows] == []


def test_a_merge_that_fails_rolls_back_and_names_the_staged_count(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Roll back and never commit when a record fails partway through a staged merge.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the failure commits, does not roll back, or reaches the merge statement.
    """

    def failing_stream() -> Iterator[dict[str, str]]:
        """Yield one good record, then fail the way a truncated extract fails.

        Yields
        ------
        dict[str, str]
            One valid transaction-type record.

        Raises
        ------
        RecordLengthError
            Always, after the first record.
        """
        yield {"TRAN-TYPE": "98", "TRAN-TYPE-DESC": "SYNTHETIC ONE"}
        raise layouts.RecordLengthError("record 2 of TRANTYPE is 59 characters against 60")

    # WHY : Assumptions: the failure is a FOREIGN exception rather than a missing mapped field,
    #   which exercises the wrapping arm of the merge path. The two arms differ in kind: a missing
    #   field is already an `AuroraLoadError` and is re-raised with its own message, whereas
    #   anything a lazy reader raises mid-stream arrives foreign and must be both rolled back AND
    #   wrapped -- and only the wrapping arm can state how many rows had been staged when it failed.
    target = target_for("TRANTYPE")
    connection = fake_aurora.connect(**_connection_params())
    with pytest.raises(AuroraLoadError) as refused:
        load_records(connection, target, failing_stream())
    message = str(refused.value)
    assert "staged row(s)" in message
    assert "rolled back" in message
    assert "1 staged row(s)" in message
    # WHY : the MERGE must not have been reached. A merge issued after a failed staging COPY
    #   would insert whatever rows had already been staged -- a partial load that committed, which
    #   is precisely the outcome the single unit of work exists to make impossible.
    assert target.merge_statement() not in fake_aurora.executed_sql()
    assert fake_aurora.rollbacks == 1
    assert fake_aurora.commits == 0


def test_the_merge_path_issues_no_privileged_statement(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Create only a session-temporary table, and issue no privileged statement, while merging.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double, which classifies every statement it is given.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the merge path issues a privileged statement, or creates a table outside the
        session-temporary schema.
    """
    target = target_for("DISGROUP")
    fake_aurora.arrange_affected_rows("insert into", 1)
    connection = fake_aurora.connect(**_connection_params())
    load_records(
        connection,
        target,
        [
            {
                "DIS-ACCT-GROUP-ID": "SYNTHGRP01",
                "DIS-TRAN-TYPE-CD": "01",
                "DIS-TRAN-CAT-CD": "0005",
                "DIS-INT-RATE": Decimal("1.00"),
            }
        ],
    )
    # WHY : Assumptions: the merge path adds two statements to what the loader issues, so the
    #   privilege contract is re-asserted for it specifically rather than inherited from the direct
    #   path's test. A runtime role holds SELECT, INSERT and UPDATE and has CREATE revoked on its
    #   schema, so the staging table has to be TEMPORARY -- created in the session's own temporary
    #   schema under the database-level privilege PostgreSQL grants PUBLIC -- and it must be
    #   unqualified, because qualifying it with the target's schema would attempt a PERMANENT table
    #   there and be refused.
    assert fake_aurora.forbidden_statements() == ()
    creates = [sql for sql in fake_aurora.executed_sql() if "CREATE" in sql.upper()]
    assert len(creates) == 1
    assert creates[0].startswith("CREATE TEMPORARY TABLE ")
    assert f".{target.stage_name}" not in creates[0]
    # WHY : the staging table is asserted to carry only the columns the COPY supplies. `LIKE` was
    #   the obvious way to build it and would have copied every column of the target, including
    #   `ledger.daily_transactions`' identity primary key -- as a NOT NULL column with no default,
    #   which the COPY does not fill.
    assert " WITH NO DATA" in creates[0]


def test_prepare_record_projects_each_declared_transformation() -> None:
    """Trim padding, null a blank nullable column, and canonicalise both timestamp dialects.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any projection is not applied, or an unmapped field reaches the prepared record.
    """
    target = target_for("DALYTRAN")
    record = {
        "DALYTRAN-ID": "0000000000683580",
        "DALYTRAN-TYPE-CD": "01",
        "DALYTRAN-CAT-CD": "0001",
        "DALYTRAN-SOURCE": "POS TERM  ",
        "DALYTRAN-DESC": "Purchase at Abshire-Lowe" + " " * 76,
        "DALYTRAN-AMT": Decimal("504.77"),
        "DALYTRAN-MERCHANT-ID": "800000000",
        "DALYTRAN-MERCHANT-NAME": "Abshire-Lowe" + " " * 38,
        "DALYTRAN-MERCHANT-CITY": "North Enoshaven" + " " * 35,
        "DALYTRAN-MERCHANT-ZIP": "72112     ",
        "DALYTRAN-CARD-NUM": "4859452612877065",
        # WHY : the DOTTED dialect is used here deliberately. All 300 shipped records carry the
        #   space-separated form, so a verbatim loader passes against the corpus and fails on the
        #   first row the interest calculation writes -- which uses this form, and which PostgreSQL
        #   cannot cast at all. Using the form the corpus does NOT contain is what makes this test
        #   able to fail.
        "DALYTRAN-ORIG-TS": "2022-07-18-10.30.00.123456",
        "DALYTRAN-PROC-TS": " " * 26,
        "FILLER": " " * 20,
    }
    prepared = prepare_record(target, record)
    assert prepared["DALYTRAN-DESC"] == "Purchase at Abshire-Lowe"
    assert prepared["DALYTRAN-MERCHANT-NAME"] == "Abshire-Lowe"
    assert prepared["DALYTRAN-ORIG-TS"] == "2022-07-18 10:30:00.123456"
    assert prepared["DALYTRAN-PROC-TS"] is None
    # WHY : a fixed code keeps its declared width even though a description does not, because the
    #   distinction is the COLUMN's: `source` is CHAR(10) and `description` is VARCHAR(100). A
    #   loader that trimmed uniformly would produce a `source` PostgreSQL then pads back, and a
    #   loader that trimmed nothing would store padding in the description a screen renders.
    assert prepared["DALYTRAN-SOURCE"] == "POS TERM  "
    # WHY : the prepared record carries the mapped fields and NOTHING else. `FILLER` was supplied
    #   above precisely so that a preparation copying the record forward would be caught.
    assert set(prepared) == set(target.columns)


def test_a_stamp_the_column_cannot_hold_is_refused_at_the_load_boundary() -> None:
    """Refuse a malformed originating stamp when rendering it for a timestamp column.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a malformed stamp reaches a row, or the refusal quotes it.
    """
    # WHY : Assumptions: the ORIGINATING stamp is the one this test corrupts, because it is the one
    #   the reader does NOT validate -- its descriptor marks it deterministic business data, so
    #   only the run-generated processing stamp is checked on the way in. That leaves the load
    #   boundary as the only place a corrupt originating stamp can be caught, and it has to be
    #   caught somewhere: PostgreSQL would otherwise reject the whole COPY with a cast error naming
    #   a column, after the entire dataset had been streamed.
    target = target_for("DALYTRAN")
    record = {
        "DALYTRAN-ID": "0000000000683580",
        "DALYTRAN-TYPE-CD": "01",
        "DALYTRAN-CAT-CD": "0001",
        "DALYTRAN-SOURCE": "POS TERM  ",
        "DALYTRAN-DESC": "Synthetic purchase" + " " * 82,
        "DALYTRAN-AMT": Decimal("1.00"),
        "DALYTRAN-MERCHANT-ID": "800000000",
        "DALYTRAN-MERCHANT-NAME": "Synthetic" + " " * 41,
        "DALYTRAN-MERCHANT-CITY": "Synthetictown" + " " * 37,
        "DALYTRAN-MERCHANT-ZIP": "72112     ",
        "DALYTRAN-CARD-NUM": "4859452612877065",
        "DALYTRAN-ORIG-TS": "2022-13-45 10:30:00.123456",
        "DALYTRAN-PROC-TS": " " * 26,
    }
    with pytest.raises(AuroraLoadError) as refused:
        prepare_record(target, record)
    message = str(refused.value)
    assert "DALYTRAN-ORIG-TS" in message
    assert "orig_ts" in message
    # WHY : no part of the value reaches the diagnostic. The shared renderer names the width and
    #   the two admitted forms only, and this wrapper adds the record and the column -- which is
    #   the discipline the whole load path holds to, because these records carry account numbers.
    assert "2022-13-45" not in message


def test_prepare_record_nulls_a_blank_nullable_column_and_keeps_a_present_one() -> None:
    """Yield ``None`` for a blank nullable field and the trimmed text for a populated one.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a blank nullable field becomes an empty string, or a populated one is not trimmed.
    """
    target = TableTarget(
        schema="account",
        table="customers",
        columns={"CUST-MIDDLE-NAME": "middle_name", "CUST-PHONE-NUM-2": "phone_num_2"},
        projections={
            "CUST-MIDDLE-NAME": Projection.TRIMMED_OR_NULL,
            "CUST-PHONE-NUM-2": Projection.TRIMMED_OR_NULL,
        },
    )
    prepared = prepare_record(
        target,
        {"CUST-MIDDLE-NAME": " " * 25, "CUST-PHONE-NUM-2": "(373)693-8684  "},
    )
    # WHY : `None` rather than `''`, and the difference is a fact about the customer. An empty
    #   string would make "no middle name" and "a middle name of zero characters" the same stored
    #   value, and every screen and report reading the column would render the second.
    assert prepared["CUST-MIDDLE-NAME"] is None
    assert prepared["CUST-PHONE-NUM-2"] == "(373)693-8684"


def test_a_target_declaring_a_projection_for_an_unmapped_field_is_refused() -> None:
    """Refuse a target whose projection names a field it does not map to a column.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If such a target is accepted, or the refusal does not name the offending field.
    """
    # WHY : Assumptions: this is checked at CONSTRUCTION because every way it fails otherwise is
    #   silent. A projection keyed on a misspelled field name simply never applies -- so a money
    #   column would load padded text, or, far worse, a protected column declared
    #   `SEALED_IDENTIFIER` under a misspelling would fall through to VERBATIM and load a national
    #   identifier as plaintext into a column named `ssn_encrypted`, successfully.
    with pytest.raises(ValueError) as refused:
        TableTarget(
            schema="account",
            table="customers",
            columns={"CUST-SSN": "ssn_encrypted"},
            projections={"CUST-SSNN": Projection.SEALED_IDENTIFIER},
        )
    assert "CUST-SSNN" in str(refused.value)


def test_a_target_whose_mapping_omits_a_key_field_is_refused() -> None:
    """Refuse a target that does not map every field its record's key window covers.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If such a target is accepted, or the refusal does not name the unmapped key field.
    """
    # WHY : Refactoring Rationale: this replaces a test that declared a conflict key BY HAND and
    #   asserted the target refused a column it did not produce. Conflict keys are no longer
    #   declared -- they are derived from the record descriptor's `KEYS(len off)` window -- so the
    #   mistake that test guarded against cannot be made any more. The mistake that CAN still be
    #   made is mapping only part of the key, and it is caught here.
    # WHY : Assumptions: the derivation is checked at CONSTRUCTION rather than at merge time,
    #   because the failure is otherwise deferred until after a dataset has been staged. TRANCAT
    #   keys on a six-byte window covering TWO fields -- the two-character type followed by the
    #   four-character category -- so a mapping naming only the first leaves the window's tail
    #   unmapped, and a merge conflicting on the type alone would collapse every category of a
    #   type onto one row.
    with pytest.raises(ValueError) as refused:
        TableTarget(
            schema="reference",
            table="transaction_categories",
            columns={"TRAN-TYPE-CD": "type_cd"},
            record="TRANCAT",
        )
    message = str(refused.value)
    assert "TRAN-CAT-CD" in message
    assert "TRANCAT" in message


def test_a_target_bound_to_no_record_has_no_merge_statement() -> None:
    """Refuse to compose a merge statement for a construct that loads no dataset.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a merge statement is produced for an unbound target, or the refusal does not name it.
    """
    # WHY : Refactoring Rationale: this replaces a test asserting that a target with no declared
    #   conflict key had no merge statement. Every DECLARED target now merges -- all eleven, since
    #   a re-run is a normal event for all eleven -- so the property under test moved: the only
    #   construct without a merge is one bound to no record at all, which the tests and the
    #   projection helpers build to exercise column shaping in isolation.
    projection_only = TableTarget(
        schema="account",
        table="customers",
        columns={"CUST-ID": "customer_id"},
    )
    assert projection_only.record == ""
    assert projection_only.key_columns == ()
    with pytest.raises(AuroraLoadError) as refused:
        projection_only.merge_statement()
    assert "account.customers" in str(refused.value)


class _RecordingKeys:
    """Data-key source answering deterministically and recording every context it was asked for.

    Purpose
    -------
    Let the protected-column projections be exercised with no key-management service, while
    making the encryption context each envelope is bound to observable -- which is the property
    that decides whether the owning service can read the value back.
    """

    def __init__(self) -> None:
        """Start with no recorded requests.

        Returns
        -------
        None
            Initialises the request log.

        Raises
        ------
        None
        """
        self.requests: list[tuple[str, dict[str, str]]] = []

    def data_key(self, *, key_id: str, encryption_context: Mapping[str, str]) -> DataKey:
        """Answer with a fixed key pair, recording the key and context asked for.

        Parameters
        ----------
        key_id : str
            The key the caller named.
        encryption_context : Mapping[str, str]
            The context the caller bound the key to.

        Returns
        -------
        DataKey
            A fixed 32-byte key and a fixed wrapped form.

        Raises
        ------
        None
        """
        self.requests.append((key_id, dict(encryption_context)))
        # WHY : the key material is FIXED rather than random, so an envelope produced here is
        #   reproducible and its framing can be asserted byte by byte. The vector is still drawn
        #   from the operating system inside the cipher, so two envelopes over the same value
        #   differ -- which is asserted below and is the property a fixed key must not destroy.
        return DataKey(plaintext=bytes(range(32)), wrapped=b"SYNTHETIC-WRAPPED-KEY")


def test_a_protected_identifier_is_framed_as_the_account_service_reads_it() -> None:
    """Frame a customer identifier with the marker, version, length, wrapped key, vector and tag.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the framing, the encryption context or the column binding differs from what
        ``CustomerIdentifierCipher`` parses.
    """
    keys = _RecordingKeys()
    target = target_for("CUSTOMER")
    context = LoadContext(
        identifier_cipher=CustomerIdentifierCipher(key_id="alias/synthetic", keys=keys)
    )
    prepared = prepare_record(
        target,
        {
            **_customer_record(),
            "CUST-SSN": "020973888",
            "CUST-GOVT-ISSUED-ID": " " * 20,
        },
        context,
    )
    envelope = prepared["CUST-SSN"]
    assert isinstance(envelope, bytes)
    # WHY : the framing is asserted OFFSET BY OFFSET against what the Java class parses, because
    #   every part of it is load-bearing and a mistake in any of them produces bytes that store
    #   successfully and never decipher.
    # WHY : ⚠️ Refactoring Rationale: this assertion used to begin at the length prefix at offset
    #   ZERO, with a comment stating that the customer framing carries no marker and no version
    #   byte. It was transcribed from a Java writer that has since been deleted -- a private nested
    #   implementation inside `CustomerIdentifierProtectionConfig` that competed with the
    #   component-scanned `CustomerIdentifierCipher` for this one column -- and the surviving writer
    #   frames `CDCI` and a version byte first, exactly as the card writer frames `CDCV`. The
    #   five-byte shift the old comment warned about is the correct layout.
    wrapped = b"SYNTHETIC-WRAPPED-KEY"
    assert envelope[:4] == b"CDCI"
    assert envelope[4] == 1
    assert envelope[5:7] == len(wrapped).to_bytes(2, "big")
    assert envelope[7 : 7 + len(wrapped)] == wrapped
    assert len(envelope) == 4 + 1 + 2 + len(wrapped) + 12 + len("020973888") + 16
    # WHY : the encryption context is asserted because it is AUTHENTICATED data on the data key:
    #   KMS refuses to unwrap under a different context, so a context differing by one character
    #   makes every identifier permanently unreadable by the service that owns the column.
    assert keys.requests == [
        (
            "alias/synthetic",
            {"carddemo:purpose": "customer-identifier", "carddemo:column": "ssn_encrypted"},
        )
    ]
    # WHY : the blank government identifier becomes NULL rather than an envelope over nothing.
    #   That column is nullable; enciphering an empty value would store a present envelope that
    #   deciphers to zero characters, which no reader can tell apart from a corrupted one.
    assert prepared["CUST-GOVT-ISSUED-ID"] is None


def test_a_protected_verification_value_carries_the_marker_the_card_service_checks() -> None:
    """Frame a verification value with the marker, the version byte and the length prefix.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the marker, version, framing or encryption context differs from what ``EncryptedCvv``
        parses, or if two envelopes over one value are identical.
    """
    keys = _RecordingKeys()
    target = target_for("CARD")
    context = LoadContext(
        verification_value_cipher=CardVerificationValueCipher(key_id="alias/synthetic", keys=keys)
    )
    record = {
        "CARD-NUM": "0500024453765740",
        "CARD-ACCT-ID": "00000000050",
        "CARD-CVV-CD": "123",
        "CARD-EMBOSSED-NAME": "Aniya Von" + " " * 41,
        "CARD-EXPIRAION-DATE": "2023-03-09",
        "CARD-ACTIVE-STATUS": "Y",
    }
    first = prepare_record(target, record, context)["CARD-CVV-CD"]
    second = prepare_record(target, record, context)["CARD-CVV-CD"]
    assert isinstance(first, bytes)
    wrapped = b"SYNTHETIC-WRAPPED-KEY"
    assert first[:4] == b"CDCV"
    assert first[4] == 1
    assert first[5:7] == len(wrapped).to_bytes(2, "big")
    assert first[7 : 7 + len(wrapped)] == wrapped
    assert len(first) == 4 + 1 + 2 + len(wrapped) + 12 + 3 + 16
    # WHY : the context here is PURPOSE ONLY -- no column key -- which is where the two framings
    #   differ, now that both carry a marker and a version byte. Adding a column key would bind the
    #   data key to a context the card service never presents on decrypt, so KMS would refuse to
    #   unwrap it.
    assert keys.requests[0][1] == {"carddemo:purpose": "card-cvv"}
    # WHY : two envelopes over the SAME value must differ, which proves the initialisation vector
    #   is drawn per value rather than fixed. A repeated vector under one key is the single mistake
    #   AES-GCM does not survive, and a deterministic envelope would also make the column a
    #   searchable index of card verification values.
    assert first != second
    assert prepare_record(target, record, context)["CARD-EMBOSSED-NAME"] == "Aniya Von"


def test_a_sealing_projection_without_its_cipher_refuses_rather_than_loading_plaintext() -> None:
    """Refuse the load when a protected column's cipher was not supplied.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the projection falls back to plaintext, or the refusal does not name the column.
    """
    # WHY : Assumptions: absence of a cipher is a REFUSAL and never a fallback, which is the most
    #   important single behaviour in the projection layer. A fallback to the plain value would
    #   load a national identifier as text into `ssn_encrypted`, the load would report success,
    #   and nothing downstream reads that column during a migration -- so nothing would report it.
    target = target_for("CUSTOMER")
    with pytest.raises(AuroraLoadError) as refused:
        prepare_record(target, {**_customer_record(), "CUST-SSN": "020973888"})
    message = str(refused.value)
    assert "ssn_encrypted" in message
    # WHY : the VALUE must not reach the diagnostic. This message names the column and the table
    #   and nothing else, because the argument it is refusing is a national identifier.
    assert "020973888" not in message


def test_a_verification_value_of_the_wrong_width_is_refused_without_being_quoted() -> None:
    """Refuse a verification value that is not exactly three digits, quoting none of it.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a wrong-width value is enciphered, or the refusal quotes the value.
    """
    cipher = CardVerificationValueCipher(key_id="alias/synthetic", keys=_RecordingKeys())
    for candidate in ("12", "1234", "12a"):
        with pytest.raises(ProtectedColumnError) as refused:
            cipher.seal(candidate)
        assert candidate not in str(refused.value)


def test_an_identifier_bound_to_an_undeclared_column_is_refused() -> None:
    """Refuse to bind an identifier envelope to a column this framing does not protect.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an undeclared column name is accepted into the encryption context.
    """
    # WHY : the column name becomes AUTHENTICATED data, so a misspelling produces an envelope
    #   that frames correctly, stores successfully, and fails its integrity check the first time
    #   the account service reads it -- weeks later, against a row whose provenance is gone.
    cipher = CustomerIdentifierCipher(key_id="alias/synthetic", keys=_RecordingKeys())
    with pytest.raises(ProtectedColumnError) as refused:
        cipher.seal("020973888", "ssn")
    assert "ssn" in str(refused.value)
    assert "020973888" not in str(refused.value)


def test_the_security_user_subject_is_resolved_from_the_published_document() -> None:
    """Fill the subject column from the published document, keyed by the user id.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the subject is not resolved, or an unpublished user id is loaded rather than refused.
    """
    target = target_for("SECUSER")
    record = {
        "SEC-USR-ID": "ADMIN001",
        "SEC-USR-FNAME": "Admin" + " " * 15,
        "SEC-USR-LNAME": "User" + " " * 16,
        "SEC-USR-TYPE": "A",
    }
    subject = "11111111-2222-3333-4444-555555555555"
    prepared = prepare_record(target, record, LoadContext(subjects={"ADMIN001": subject}))
    assert prepared["cognito_sub"] == subject
    assert prepared["SEC-USR-FNAME"] == "Admin"
    # WHY : the password span must have no column to reach. `auth.users` declares none, and this
    #   assertion pins that the target maps none either -- so the baseline's cleartext credential
    #   has no path into the target database even if a reader were changed to publish it.
    assert "SEC-USR-PWD" not in target.columns
    assert not any(column.startswith("password") for column in target.columns.values())
    # WHY : an unpublished user id is REFUSED rather than loaded with a null or a synthesised
    #   subject. The column is NOT NULL and UNIQUE, so a synthesised value would either abort the
    #   load partway through or, worse, collide two users onto one identity.
    with pytest.raises(AuroraLoadError) as refused:
        prepare_record(target, record, LoadContext(subjects={"OTHER001": subject}))
    assert "ADMIN001" in str(refused.value)
    # WHY : the subject document itself must not be enumerated in the diagnostic. It pairs every
    #   user id with its subject, and an exception string is the least controlled place for that
    #   whole pairing to end up.
    assert subject not in str(refused.value)


def _customer_record() -> dict[str, object]:
    """Build one synthetic customer record carrying every field the target maps.

    Purpose
    -------
    Keep the eighteen-field customer record in one place, so a projection test states only the
    field it is about and a target gaining a column is one edit rather than several.

    Returns
    -------
    dict[str, object]
        A record at the declared field widths, with no value resembling a real identifier.

    Raises
    ------
    None
    """
    # WHY : Assumptions: every value here is padded to the copybook's declared width, because the
    #   projections under test are exactly the ones that remove that padding -- a record supplied
    #   already trimmed would let a loader that trimmed nothing pass.
    return {
        "CUST-ID": "000000001",
        "CUST-FIRST-NAME": "Synthetic" + " " * 16,
        "CUST-MIDDLE-NAME": " " * 25,
        "CUST-LAST-NAME": "Person" + " " * 19,
        "CUST-ADDR-LINE-1": "1 Synthetic Way" + " " * 35,
        "CUST-ADDR-LINE-2": " " * 50,
        "CUST-ADDR-LINE-3": "Synthetictown" + " " * 37,
        "CUST-ADDR-STATE-CD": "NC",
        "CUST-ADDR-COUNTRY-CD": "USA",
        "CUST-ADDR-ZIP": "12546     ",
        "CUST-PHONE-NUM-1": "(908)119-8310  ",
        "CUST-PHONE-NUM-2": " " * 15,
        "CUST-SSN": "000000000",
        "CUST-GOVT-ISSUED-ID": "0" * 20,
        "CUST-DOB-YYYY-MM-DD": "1961-06-08",
        "CUST-EFT-ACCOUNT-ID": "0053581756",
        "CUST-PRI-CARD-HOLDER-IND": "Y",
        "CUST-FICO-CREDIT-SCORE": "274",
    }


def _connection_params() -> dict[str, object]:
    """Build the connection parameters the recording double accepts.

    Purpose
    -------
    Keep the verified-TLS requirement in one place, since the double refuses any connection
    that does not request it -- which is the behaviour a real cluster has too.

    Returns
    -------
    dict[str, object]
        Parameters naming a synthetic, unreachable host and requesting full verification.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the host is in the reserved `.invalid` top-level domain so nothing
    #   here can reach a real cluster even if a future edit handed these parameters to the real
    #   driver. `sslmode` is the value `config.REQUIRED_SSL_MODE` fixes, which is why the double
    #   rejects anything weaker rather than accepting a downgrade silently.
    return {
        "host": "aurora.carddemo.invalid",
        "port": 5432,
        "dbname": "carddemo",
        "user": "carddemo_reference",
        "sslmode": "verify-full",
        "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
    }


def _tcatbal_records() -> list[dict[str, object]]:
    """Build two category-balance records for the direct-COPY path.

    Purpose
    -------
    Give the restart-safety cases below a target that still takes the direct COPY -- the path
    whose repeat behaviour those cases are about -- with records short enough to read.

    Returns
    -------
    list[dict[str, object]]
        Two decoded records at the composite key's declared widths.

    Raises
    ------
    None
    """
    return [
        {
            "TRANCAT-ACCT-ID": "00000000001",
            "TRANCAT-TYPE-CD": "01",
            "TRANCAT-CD": "0001",
            "TRAN-CAT-BAL": Decimal("10.00"),
        },
        {
            "TRANCAT-ACCT-ID": "00000000002",
            "TRANCAT-TYPE-CD": "02",
            "TRANCAT-CD": "0003",
            "TRAN-CAT-BAL": Decimal("-4.50"),
        },
    ]


def test_a_failing_commit_is_translated_and_rolled_back(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Report a failing commit as a load error naming the target, having rolled back.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the driver's own exception escapes untranslated, or no rollback was attempted.
    """
    # WHY : Refactoring Rationale: this is the failure the commit's position used to escape. A
    #   commit that fails -- serialisation failure, lost connection, exhausted disk -- used to
    #   propagate as a raw driver exception from OUTSIDE the guarded block: the caller learned
    #   nothing about which target or how many rows, and no rollback was attempted, so a pooled
    #   connection went back with a transaction still open.
    target = target_for("TCATBAL")
    connection = fake_aurora.connect(**_connection_params())
    records = _tcatbal_records()

    def _failing_commit() -> None:
        """Refuse the commit the way a lost connection does.

        Returns
        -------
        None
            Never returns; it raises.

        Raises
        ------
        RuntimeError
            Always, standing in for the driver's own error at commit time.
        """
        raise RuntimeError("the server closed the connection during COMMIT")

    connection.commit = _failing_commit  # type: ignore[method-assign]

    with pytest.raises(AuroraLoadError) as raised:
        load_records(connection, target, records)

    message = str(raised.value)
    assert f"{target.schema}.{target.table}" in message
    assert f"{len(records)} staged row(s)" in message
    assert "rolled back" in message
    # WHY : Refactoring Rationale: the cause chain is asserted ABSENT rather than present. The
    #   loader raises `from None` on every exit so that no driver diagnostic reaches a traceback --
    #   a driver quotes the statement it could not run together with its bound parameters, and the
    #   parameters of these loads are cardholder records. The condition is still reported: the
    #   translated message names the operation, the target and the staged row count.
    assert raised.value.__cause__ is None
    assert "the server closed the connection during COMMIT" not in message
    assert fake_aurora.rollbacks == 1


def test_a_failing_rollback_does_not_displace_the_load_error(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Keep the translated diagnosis when the rollback itself fails.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the rollback's own exception reaches the caller instead of the load error.
    """
    # WHY : Assumptions: a rollback fails for exactly the reasons a commit does, and on this path
    #   the connection is usually already gone -- so a rollback raising is the NORMAL companion
    #   of a lost-connection commit, not an exotic case. Letting it propagate would replace a
    #   message naming the target and the row count with one about a rollback, which is the
    #   wrong failure to hand an operator.
    target = target_for("TCATBAL")
    connection = fake_aurora.connect(**_connection_params())

    def _failing_commit() -> None:
        """Refuse the commit the way a lost connection does.

        Returns
        -------
        None
            Never returns; it raises.

        Raises
        ------
        RuntimeError
            Always, standing in for the driver's own error at commit time.
        """
        raise RuntimeError("the server closed the connection during COMMIT")

    def _failing_rollback() -> None:
        """Refuse the rollback as a connection that has already gone would.

        Returns
        -------
        None
            Never returns; it raises.

        Raises
        ------
        RuntimeError
            Always, which is what must not displace the translated load error.
        """
        raise RuntimeError("the connection is already gone")

    connection.commit = _failing_commit  # type: ignore[method-assign]
    connection.rollback = _failing_rollback  # type: ignore[method-assign]

    with pytest.raises(AuroraLoadError) as raised:
        load_records(connection, target, _tcatbal_records())

    message = str(raised.value)
    assert f"{target.schema}.{target.table}" in message
    # WHY : Refactoring Rationale: the failed ROLLBACK is reported inside the translated message
    #   rather than attached as a cause, and the original failure is not displaced by it. The
    #   loader raises `from None`, so neither driver text reaches a traceback; what an operator
    #   needs from the rollback failure is that the connection is unusable, and that is stated.
    assert raised.value.__cause__ is None
    assert "also failed" in message
    assert "must be discarded rather than reused" in message
    assert "the connection is already gone" not in message


def test_the_merge_path_issues_no_guarding_count(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Keep the emptiness precondition off the path whose statement is already idempotent.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a count is issued on the merge path, or the merge does not commit.
    """
    # WHY : Assumptions: the merge path must NOT decline a populated target -- being populated is
    #   its normal state, since `V2__seed_reference.sql` writes those three tables and the
    #   posting job writes the fourth. Its restart safety comes from `ON CONFLICT ... DO
    #   NOTHING`, which is idempotency in the statement rather than in a guard, and a guard here
    #   would refuse exactly the loads the merge exists to perform.
    target = target_for("TRANTYPE")
    connection = fake_aurora.connect(**_connection_params())

    load_records(
        connection,
        target,
        [{"TRAN-TYPE": "01", "TRAN-TYPE-DESC": "Purchase" + " " * 42}],
    )

    executed = " ".join(fake_aurora.executed_sql()).casefold()
    assert "count(*)" not in executed
    assert fake_aurora.commits == 1


def _arrange_allocator(
    fake_aurora: FakeAuroraDatabase,
    *,
    last_value: int,
    is_called: bool,
    stored_maximum: int,
) -> None:
    """Arrange the allocator's position and the table's largest stored identifier.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.
    last_value : int
        The value the sequence reports as its last.
    is_called : bool
        Whether the sequence has issued a value, which decides what it issues next.
    stored_maximum : int
        The largest sequence-format identifier the table holds.

    Returns
    -------
    None
        Arranges both result sets.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the two result sets are keyed on fragments that cannot match each other.
    #   Both statements are SELECTs against the ledger schema, so a fragment as loose as
    #   "select" would arrange the sequence's row for the maximum query too and the test would
    #   assert against numbers it did not intend.
    fake_aurora.arrange_rows("last_value, is_called", [(last_value, is_called)])
    fake_aurora.arrange_rows("max(transaction_id", [(stored_maximum,)])


def test_the_allocator_is_advanced_past_every_loaded_identifier(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Advance the sequence to one past the largest stored identifier, as the owner.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the owner role is not assumed, the advance is not issued, or the target is wrong.
    """
    # WHY : Refactoring Rationale: this is the cutover ordering hazard.
    #   `V2__ledger_transaction_id_allocator.sql` derives the sequence's start from
    #   `max(transaction_id)` at MIGRATION time, which on a cutover is an empty table -- so the
    #   allocator issues 1 while the loaded master occupies the range up to 683580, and the
    #   first interactive add fails on the primary key. It then fails again for every allocation
    #   until it climbs past the loaded range.
    _arrange_allocator(fake_aurora, last_value=1, is_called=False, stored_maximum=683_580)
    connection = fake_aurora.connect(**_connection_params())

    reconciliation = reconcile_transaction_id_sequence(connection)

    assert reconciliation.sequence == TRANSACTION_ID_SEQUENCE
    assert reconciliation.stored_maximum == 683_580
    assert reconciliation.next_value_before == 1
    assert reconciliation.next_value_after == 683_581
    assert reconciliation.advanced is True
    assert reconciliation.would_have_collided is True
    executed = fake_aurora.executed_sql()
    # WHY : Assumptions: the SET ROLE must precede the setval, and it is asserted rather than
    #   assumed because V0 grants the migration login its owner `WITH INHERIT FALSE`: without
    #   the SET ROLE the statement fails on a permission error at the one cutover step that
    #   cannot be skipped.
    assert any(statement.startswith("SET ROLE") for statement in executed)
    setvals = [statement for statement in executed if "setval" in statement]
    assert len(setvals) == 1
    assert executed.index(setvals[0]) > next(
        index for index, statement in enumerate(executed) if statement.startswith("SET ROLE")
    )
    # WHY : Trade-offs: the target is bound as a PARAMETER rather than interpolated, so the recorded
    #   parameters carry it. An interpolated value would be a statement built from a number read
    #   out of the database, which is the shape this package refuses everywhere else.
    assert (683_581,) in [params for _, params in fake_aurora.statements if params]
    assert fake_aurora.commits == 1


def test_an_allocator_already_past_the_data_is_left_alone(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Issue no advance when the sequence is already beyond every stored identifier.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a setval is issued, or the outcome claims an advance.
    """
    # WHY : Trade-offs: this is the safety property, not an optimisation. If writes were ever
    #   enabled -- even for a smoke test -- allocations have happened, and rewinding the
    #   allocator to `max + 1` would reissue every identifier allocated since. Only ever
    #   advancing is also what makes the step safe to leave in a cutover script that gets
    #   re-run.
    _arrange_allocator(fake_aurora, last_value=900_000, is_called=True, stored_maximum=683_580)
    connection = fake_aurora.connect(**_connection_params())

    reconciliation = reconcile_transaction_id_sequence(connection)

    assert reconciliation.next_value_before == 900_001
    assert reconciliation.next_value_after == 900_001
    assert reconciliation.advanced is False
    assert reconciliation.would_have_collided is False
    assert [statement for statement in fake_aurora.executed_sql() if "setval" in statement] == []
    assert fake_aurora.commits == 0
    assert "nothing to reconcile" in reconciliation.describe()


def test_a_called_allocator_is_read_as_issuing_the_next_value(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Read a called sequence as issuing ``last_value + 1``, not ``last_value``.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the position is read off by one, which would stop the advance one identifier short.
    """
    # WHY : Assumptions: `is_called` is the whole difference between a sequence that has issued a
    #   value and one that has only been positioned, and reading `last_value` alone understates
    #   the next value by one on every sequence that has issued anything. Here the sequence has
    #   issued 10 and the table holds 10, so the next value is 11 -- already clear -- and a
    #   reader that took 10 as the next value would decide it collided and advance needlessly.
    _arrange_allocator(fake_aurora, last_value=10, is_called=True, stored_maximum=10)
    connection = fake_aurora.connect(**_connection_params())

    reconciliation = reconcile_transaction_id_sequence(connection)

    assert reconciliation.next_value_before == 11
    assert reconciliation.advanced is False
    assert reconciliation.would_have_collided is False


def test_an_allocator_poised_on_the_stored_maximum_counts_as_a_collision(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Treat a sequence about to issue exactly the stored maximum as a collision.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the boundary is read as safe, which would leave one guaranteed duplicate key.
    """
    # WHY : Assumptions: the boundary is asserted because it is the one an off-by-one gets wrong in
    #   the unsafe direction. A sequence poised to issue 500 against a table whose largest
    #   identifier is 500 fails on its very FIRST allocation, so equality is a collision rather
    #   than a value safely at the edge of the loaded range.
    _arrange_allocator(fake_aurora, last_value=500, is_called=False, stored_maximum=500)
    connection = fake_aurora.connect(**_connection_params())

    reconciliation = reconcile_transaction_id_sequence(connection)

    assert reconciliation.next_value_before == 500
    assert reconciliation.would_have_collided is True
    assert reconciliation.next_value_after == 501
    assert reconciliation.advanced is True


def test_an_empty_ledger_leaves_the_allocator_at_its_first_value(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Leave a fresh allocator alone when the table holds no sequence-format identifier.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a setval is issued against an empty table, which the sequence's MINVALUE would refuse.
    """
    # WHY : Assumptions: an empty ledger is the NORMAL case on a corpus-only deployment -- no
    #   `TRANSACT` extract ships in either seed tree -- so this path runs far more often than
    #   the cutover one. The sequence declares MINVALUE 1, and a reconciliation that computed a
    #   target of 0 and passed it to setval would abort on every fresh environment.
    _arrange_allocator(fake_aurora, last_value=1, is_called=False, stored_maximum=0)
    connection = fake_aurora.connect(**_connection_params())

    reconciliation = reconcile_transaction_id_sequence(connection)

    assert reconciliation.stored_maximum == 0
    assert reconciliation.next_value_after == 1
    assert reconciliation.advanced is False
    assert [statement for statement in fake_aurora.executed_sql() if "setval" in statement] == []


def test_the_reconciliation_reads_only_sequence_format_identifiers(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Require the maximum to be taken over sixteen-digit identifiers only.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the statement does not filter to the sequence format.
    """
    # WHY : Assumptions: `ledger.transactions` holds TWO identifier formats -- the sixteen digits
    #   this allocator issues, and the business-date-prefixed form the interest job composes at
    #   app/cbl/CBACT04C.cbl:474-480. Only the first is this allocator's to advance past: taking
    #   the maximum across both would set it into the date-prefixed range and consume the
    #   identifiers of a decade of transactions in one statement. The filter is also what keeps
    #   the `::BIGINT` cast from aborting on a value that is not a number.
    _arrange_allocator(fake_aurora, last_value=1, is_called=False, stored_maximum=42)
    connection = fake_aurora.connect(**_connection_params())

    reconcile_transaction_id_sequence(connection)

    maximum_query = next(
        statement for statement in fake_aurora.executed_sql() if "max(transaction_id" in statement
    )
    assert "^[0-9]{16}$" in maximum_query
    assert "::BIGINT" in maximum_query


def test_an_unreadable_allocator_refuses_to_let_writes_be_enabled(
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Report a load error naming the allocator when its position cannot be read.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the driver's own exception escapes, or the message omits the consequence.
    """
    # WHY : Trade-offs: the message says writes must NOT be enabled, because that is the action the
    #   failure bears on. A message naming only the failed statement would leave an operator to
    #   decide for themselves whether an unreconciled allocator is safe, and it is not: the
    #   first interactive write after the load would fail on the primary key.
    target = target_for("TRAN")
    connection = fake_aurora.connect(**_connection_params())
    original_cursor = connection.cursor

    def _cursor_failing_on_the_sequence() -> object:
        """Answer with a cursor that refuses the sequence read and nothing else.

        Returns
        -------
        object
            A cursor whose ``execute`` raises for the sequence position query.

        Raises
        ------
        None
        """
        cursor = original_cursor()
        original_execute = cursor.execute

        def _execute(statement: str, params: object = None) -> object:
            """Refuse the sequence position query and delegate everything else.

            Parameters
            ----------
            statement : str
                The statement being executed.
            params : object
                Bound parameters, passed through unchanged.

            Returns
            -------
            object
                Whatever the recording cursor returns for any other statement.

            Raises
            ------
            RuntimeError
                If the statement reads the sequence's position.
            """
            if "last_value" in statement:
                raise RuntimeError("permission denied for sequence transaction_id_seq")
            return original_execute(statement, params)

        cursor.execute = _execute  # type: ignore[method-assign]
        return cursor

    connection.cursor = _cursor_failing_on_the_sequence  # type: ignore[method-assign]

    with pytest.raises(AuroraLoadError) as raised:
        reconcile_transaction_id_sequence(connection)

    message = str(raised.value)
    assert TRANSACTION_ID_SEQUENCE in message
    assert "writes must not be enabled" in message
    assert target.schema == "ledger"
    assert [statement for statement in fake_aurora.executed_sql() if "setval" in statement] == []
