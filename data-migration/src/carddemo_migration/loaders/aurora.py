"""Bulk-load decoded baseline records into Aurora PostgreSQL through server-side COPY.

Purpose
-------
Provide the load half of the migration: take the decoded records a reader yields and put them
into the target table the owning service declares, using PostgreSQL's own bulk path rather
than a statement per row. This is the migrated form of the baseline's ``IDCAMS REPRO`` load
steps, which is what ``app/jcl/ACCTFILE.jcl`` and its nine siblings each perform for one
dataset.

Alternatives Considered:
------------------------
An ``INSERT`` per row, or an executemany. Rejected because a dataset load is the one operation
in this package where volume is the whole problem: COPY streams rows into the server in a
single statement with one parse and no per-row round trip, and psycopg 3 exposes it as a
context manager that this module writes rows into. The requirements file already records this
as the reason psycopg 3 is pinned rather than psycopg2 -- "the Aurora bulk loaders drive
server-side COPY through psycopg 3 copy context manager", and psycopg2 offers neither that nor
its pipeline mode.

Assumptions:
------------
The column mapping is DECLARED per record and is not derived from the field name. It cannot be
derived: the target column names are the owning service's, and the transformation is not
mechanical -- ``CUST-ID`` becomes ``customer_id``, ``CUST-FIRST-NAME`` becomes ``first_name``,
``DIS-ACCT-GROUP-ID`` becomes ``acct_group_id`` and ``TRAN-TYPE`` becomes ``type_cd``. A
hyphen-to-underscore rule would produce a different name for every one of those and the load
would fail on the first column, or worse, load into the wrong one where a coincidence lined up.
Declaring the mapping is what makes this module the anti-corruption layer for the load
direction, and it is where the baseline's three misspellings are corrected.

Refactoring Rationale:
:data:`TARGETS` declared FIVE records and now declares TEN, and the five that were missing were
not edge cases: they were ``card.cards``, ``account.customers``, ``ledger.daily_transactions``,
``ledger.transaction_category_balances`` and ``auth.users`` -- the card master, the customer
master, the daily-transaction feed, the category balances the posting run updates, and every
user of the system. A migration missing those has migrated the account master and its
cross-reference and nothing else, so ``sql/verify/row_counts.sql`` listed eleven baselines
against a loader that could satisfy four of them.

Two of the five were refused by name, on the stated grounds that their tables declare protected
``BYTEA`` columns holding "ciphertext produced by the owning service's own cipher, under a key
this package has no access to and should not have". That was a boundary drawn in the wrong
place. The key is an AWS KMS customer-managed key reached by ALIAS, so "no access" describes an
IAM grant rather than a capability, and the batch task that runs this ETL is exactly the
principal such a grant is written for -- the same alias is already published to the account and
card workloads as a runtime parameter. :mod:`carddemo_migration.loaders.protected_columns`
reproduces both envelope framings exactly and records why reproducing them is lower-risk than
either of the two alternatives.

The remaining three needed no cipher at all. They were simply absent.

WHY (Assumptions)
-----------------
A field's value reaches its column through a declared PROJECTION rather than verbatim, and the
default is verbatim so that a target declares only its exceptions. Four exceptions exist and
each one is a property of the target column rather than of the reader: a descriptive
``VARCHAR`` column takes the value with its fixed-width blank padding removed, a nullable
column takes ``None`` where the source holds nothing, a ``TIMESTAMP(6)`` column takes the one
spelling PostgreSQL can cast, and a ``BYTEA`` column takes an envelope. Expressing these as
declarations beside the column mapping keeps the transformation reviewable in one place; doing
it in each reader instead would push target-column knowledge into eleven modules that have no
business holding it.

WHY (Trade-offs)
----------------
Only the three REFERENCE targets declare a conflict key and load through a stage-and-merge, and
the seven master targets deliberately do not. The distinction is whether the table has a second
writer: ``reference.transaction_types``, ``reference.transaction_categories`` and
``reference.disclosure_groups`` are also seeded by ``V2__seed_reference.sql``, so those three
must compose with a migration that may already have run, and their descriptive columns are
trimmed here precisely so that the two writers produce byte-identical rows rather than merely
equal counts. A master table has exactly one writer, and a plain COPY that fails on a second
run is the more useful behaviour there: it reports that the table was not empty, which is
information a silent no-op would destroy.
"""

from __future__ import annotations

import contextlib
import enum
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass, field
from decimal import Decimal
from types import MappingProxyType
from typing import Any, Final, Protocol

from carddemo_migration.config import (
    AuroraConnectionSettings,
    ConfigurationError,
    quote_identifier,
)
from carddemo_migration.copybook import timestamp
from carddemo_migration.loaders.protected_columns import (
    CardVerificationValueCipher,
    CustomerIdentifierCipher,
)

__all__ = [
    "TARGETS",
    "AuroraLoadError",
    "LoadContext",
    "LoadOutcome",
    "Projection",
    "TableTarget",
    "connect",
    "load_records",
    "prepare_record",
    "target_names",
    "target_for",
]


class AuroraLoadError(RuntimeError):
    """Raised when a bulk load cannot be performed or does not complete.

    Purpose
    -------
    Separate a load failure from a configuration fault, so an operator can tell a database
    that refused from an environment that was never able to try.
    """


class _Cursor(Protocol):
    """The cursor surface this module uses, narrowed to what it calls.

    Purpose
    -------
    Let a test supply a double without importing the driver, and make the module's demands on
    the driver explicit: one COPY context manager, one statement execution, and the row count
    that execution affected.
    """

    def copy(self, statement: str) -> Any:
        """Open a COPY stream for a statement.

        Parameters
        ----------
        statement : str
            The fully-formed ``COPY ... FROM STDIN`` statement.

        Returns
        -------
        Any
            A context manager whose ``write_row`` accepts one row tuple.
        """
        ...  # pragma: no cover - Protocol declaration

    def execute(self, statement: str) -> Any:
        """Run one statement that returns no rows.

        Parameters
        ----------
        statement : str
            The statement to run. This module issues only the two statements the
            stage-and-merge path needs: a temporary-table creation and one insert.

        Returns
        -------
        Any
            Whatever the driver returns; this module reads only :attr:`rowcount` afterwards.
        """
        ...  # pragma: no cover - Protocol declaration

    @property
    def rowcount(self) -> int:
        """Report how many rows the last execution affected.

        Returns
        -------
        int
            The affected-row count, which on the merge path is the number of rows the insert
            actually added rather than the number staged.
        """
        ...  # pragma: no cover - Protocol declaration


class _Connection(Protocol):
    """The connection surface this module uses, narrowed to what it calls."""

    def cursor(self) -> Any:
        """Open a cursor.

        Returns
        -------
        Any
            A context manager yielding a cursor.
        """
        ...  # pragma: no cover - Protocol declaration

    def commit(self) -> None:
        """Commit the open transaction.

        Returns
        -------
        None
            Nothing. The effect is on the connection, not in a return value: after this
            call the rows written by :func:`load_records` are durable and the connection
            holds no open transaction.
        """
        ...  # pragma: no cover - Protocol declaration

    def rollback(self) -> None:
        """Roll the open transaction back.

        Returns
        -------
        None
            Nothing. The effect is on the connection, not in a return value: after this
            call every row written since the transaction opened is discarded, which is
            what makes a failed dataset load leave the target table as it was rather
            than half filled.
        """
        ...  # pragma: no cover - Protocol declaration


class Projection(enum.Enum):
    """How one decoded field's value becomes the value its target column stores.

    Purpose
    -------
    Name the four transformations a target column can require, so each is declared beside the
    column mapping rather than implied by whichever reader produced the value. Every member
    names a property of the COLUMN, which is why the declaration lives here and not in a reader.

    Assumptions: :attr:`VERBATIM` is the default for every unlisted field, so a target declares
    only its exceptions and a reviewer reading a target sees exactly what is not a straight
    copy.
    """

    VERBATIM = "verbatim"
    """Pass the decoded value through unchanged.

    Correct for every fixed-width ``CHAR(n)`` column, for a ``BIGINT`` or ``SMALLINT`` column
    taking display digits PostgreSQL casts, for a ``DATE`` column taking an ISO-ordered
    ``PIC X(10)``, and for a ``NUMERIC`` column taking an exact ``Decimal``.
    """

    TRIMMED = "trimmed"
    """Remove trailing blanks, which for this field are fixed-width padding rather than data.

    Declared only for a descriptive ``VARCHAR`` column. It is NOT declared for a fixed code or
    key even where the column happens to be variable-width, because a trimmed key no longer
    matches the key it joins on.
    """

    TRIMMED_OR_NULL = "trimmed_or_null"
    """Remove trailing blanks, and yield ``None`` when nothing is left.

    Declared only where the target column is NULLABLE and the source expresses absence as a
    blank field. Storing an empty string instead would make "no middle name" and "a middle name
    of zero characters" the same value, and only one of those is a fact about the customer.
    """

    TIMESTAMP_OR_NULL = "timestamp_or_null"
    """Render the stamp in the one spelling ``TIMESTAMP(6)`` accepts, or ``None`` if unwritten.

    CardDemo writes 26-character stamps in two spellings and PostgreSQL can cast only one of
    them, so a verbatim column would load every record the posting program wrote and reject
    every record the interest calculation wrote.
    :mod:`carddemo_migration.copybook.timestamp` owns both the recognition and the rendering.
    """

    SEALED_IDENTIFIER = "sealed_identifier"
    """Encipher the value into the envelope ``account.customers`` stores.

    The envelope is bound to the target column through its KMS encryption context, so the
    column name this target declares is authenticated data rather than a label.
    """

    SEALED_VERIFICATION_VALUE = "sealed_verification_value"
    """Encipher the value into the self-describing envelope ``card.cards.cvv_encrypted`` stores.

    A different framing from :attr:`SEALED_IDENTIFIER` -- it carries a marker and a version byte
    -- because a different service parses it.
    """

    SUBJECT_FOR_USER_ID = "subject_for_user_id"
    """Resolve the identity provider's subject for the user id, for ``auth.users.cognito_sub``.

    Assumptions: this is the one projection whose input is a DIFFERENT field from the one it is
    declared against. ``auth.users`` declares ``cognito_sub UUID NOT NULL UNIQUE`` and the
    80-byte ``USRSEC`` record cannot supply it -- a subject is minted by the identity provider --
    so the value is looked up by ``SEC-USR-ID`` in the document
    ``infra/modules/cognito`` publishes. The declared field name is therefore the COLUMN's, not
    a copybook field's, and :attr:`TableTarget.derived_fields` records that.
    """


@dataclass(frozen=True)
class LoadContext:
    """The collaborators a load needs when its target declares a non-mechanical projection.

    Purpose
    -------
    Carry the two ciphers and the subject document as ONE argument, so the load signature does
    not grow a parameter for each projection and a target needing none can be loaded with no
    context at all.

    Parameters
    ----------
    identifier_cipher : CustomerIdentifierCipher | None
        Required only by a target declaring :attr:`Projection.SEALED_IDENTIFIER`.
    verification_value_cipher : CardVerificationValueCipher | None
        Required only by a target declaring :attr:`Projection.SEALED_VERIFICATION_VALUE`.
    subjects : Mapping[str, str]
        Identity-provider subject per eight-character user id, required only by a target
        declaring :attr:`Projection.SUBJECT_FOR_USER_ID`.

    Raises
    ------
    None
        Absence is not an error here. A projection that needs a collaborator it was not given
        reports that itself, naming the record and the column, which is a better diagnostic than
        a constructor refusing a context whose intended target it does not know.
    """

    identifier_cipher: CustomerIdentifierCipher | None = None
    verification_value_cipher: CardVerificationValueCipher | None = None
    subjects: Mapping[str, str] = field(default_factory=lambda: MappingProxyType({}))


@dataclass(frozen=True)
class LoadOutcome:
    """What one load did, distinguishing rows read from rows the table gained.

    Purpose
    -------
    Report both numbers, because on the stage-and-merge path they differ and the difference is
    the whole point: a merge that staged 7 rows and inserted 0 means the seed migration had
    already run, which is a success, while a plain COPY reporting 7 means the table gained 7.
    Collapsing them into one number would make those two outcomes indistinguishable.

    Parameters
    ----------
    staged : int
        Rows the reader produced and this module accepted.
    inserted : int
        Rows the target table gained. Equal to :attr:`staged` on the plain COPY path, because
        every staged row goes straight into the table there.

    Raises
    ------
    None
    """

    staged: int
    inserted: int

    @property
    def skipped(self) -> int:
        """Report how many staged rows the target already held.

        Returns
        -------
        int
            The difference between the two counts, which is zero on the plain COPY path.
        """
        return self.staged - self.inserted

    def describe(self) -> str:
        """Render one operator-readable line stating what the load did.

        Returns
        -------
        str
            A sentence naming both counts, and the skipped count only when it is non-zero so
            that the plain COPY path's line does not carry a number that is always zero.

        Raises
        ------
        None
        """
        if self.skipped:
            return (
                f"{self.staged} row(s) read, {self.inserted} inserted,"
                f" {self.skipped} already present"
            )
        return f"{self.staged} row(s) read, {self.inserted} inserted"


@dataclass(frozen=True)
class TableTarget:
    """Where one decoded record loads, and which column each of its fields becomes.

    Parameters
    ----------
    schema : str
        The owning service's schema.
    table : str
        The table within that schema.
    columns : Mapping[str, str]
        Ordered mapping of copybook field name to target column name. Iteration order is the
        COPY column order, so it is the order rows are written in. A field absent from this
        mapping is deliberately not loaded.
    projections : Mapping[str, Projection]
        How each named field's value becomes its column's value. A field absent from this
        mapping projects :attr:`Projection.VERBATIM`, so only the exceptions are declared.
    conflict_key : tuple[str, ...]
        The TARGET COLUMN names forming the conflict target of a stage-and-merge load. Empty for
        a target with exactly one writer, which loads through a plain COPY.
    derived_fields : frozenset[str]
        Keys of ``columns`` that no reader publishes because the value is derived rather than
        decoded. Declared so that the missing-field check does not demand them of a record and
        so a test can assert the set rather than infer it.

    Raises
    ------
    ValueError
        If the schema, table or mapping is empty, if a projection or a derived field names a
        field the mapping does not carry, or if a conflict key names a column the mapping does
        not produce.
    """

    schema: str
    table: str
    columns: Mapping[str, str]
    projections: Mapping[str, Projection] = field(default_factory=lambda: MappingProxyType({}))
    conflict_key: tuple[str, ...] = ()
    derived_fields: frozenset[str] = frozenset()

    def __post_init__(self) -> None:
        """Refuse a target whose declarations disagree with each other.

        Returns
        -------
        None
            Nothing. A dataclass initialiser hook is called for its validation effect,
            and returning normally is what signals that this target is usable; the only
            other outcome is the exception below.

        Raises
        ------
        ValueError
            If any component is empty, or a projection, derived field or conflict key names
            something the column mapping does not.
        """
        if not self.schema.strip() or not self.table.strip():
            raise ValueError("a table target must name both a schema and a table")
        if not self.columns:
            raise ValueError(
                f"target {self.schema}.{self.table} maps no column, so a load would insert"
                " nothing; a target with no mapping is a declaration error rather than an"
                " empty load"
            )
        # Assumptions: the three supplementary declarations are checked against the column
        #   mapping HERE, at import time, rather than at the point each is used. Every one of them
        #   fails silently otherwise: a projection keyed on a misspelled field name would simply
        #   never apply, so a money column would load padded text or a protected column would load
        #   PLAINTEXT; a derived field not in the mapping would exempt nothing; and a conflict key
        #   naming a column the COPY does not supply would produce an insert PostgreSQL rejects at
        #   run time, after the whole dataset had been staged.
        unmapped = sorted(set(self.projections) - set(self.columns))
        if unmapped:
            raise ValueError(
                f"target {self.schema}.{self.table} declares a projection for"
                f" {', '.join(unmapped)}, which it does not map to a column, so the projection"
                " would never be applied"
            )
        undeclared = sorted(self.derived_fields - set(self.columns))
        if undeclared:
            raise ValueError(
                f"target {self.schema}.{self.table} declares {', '.join(undeclared)} derived"
                " without mapping it to a column"
            )
        produced = set(self.columns.values())
        missing = sorted(set(self.conflict_key) - produced)
        if missing:
            raise ValueError(
                f"target {self.schema}.{self.table} declares a conflict key naming"
                f" {', '.join(missing)}, which its column mapping does not produce"
            )

    @property
    def qualified_name(self) -> str:
        """Report the safely-quoted, schema-qualified table name.

        Returns
        -------
        str
            The identifier pair, each quoted independently.
        """
        return f"{quote_identifier(self.schema)}.{quote_identifier(self.table)}"

    def copy_statement(self) -> str:
        """Compose the COPY statement this target loads through.

        Purpose
        -------
        Name every column explicitly rather than relying on the table's declaration order.

        Returns
        -------
        str
            A ``COPY <table> (<columns>) FROM STDIN`` statement in binary-safe text form.

        Raises
        ------
        None
        """
        # WHY : Assumptions: the columns are named explicitly and never left implicit. A COPY
        #   without a column list loads into the table's declared order, so adding a column to
        #   the table -- a version column, say -- silently shifts every value by one position
        #   and the load either fails on a type mismatch or, where the adjacent types agree,
        #   succeeds with the values in the wrong columns.
        names = ", ".join(quote_identifier(column) for column in self.columns.values())
        return f"COPY {self.qualified_name} ({names}) FROM STDIN"

    # WHY : Refactoring Rationale: the value union admits `int` as well as `str`, `Decimal` and
    #   `bytes`, and the addition is one field rather than a widening for its own sake. The customer
    #   reader projects `CUST-FICO-CREDIT-SCORE` to an `int`, because its target column is a bounded
    #   `SMALLINT` and the reader is where a copybook field's MEANING is decided; every other
    #   unsigned display field stays a fixed-width string, because its leading zeroes are
    #   significant. Nothing here inspects a value's type -- the projection is positional and the
    #   driver adapts each Python type to its column -- so the change is to the annotation's
    #   accuracy, and an annotation that omitted the type a reader actually yields would be a false
    #   statement about the contract this method enforces.
    def row_of(self, record: Mapping[str, str | int | Decimal | bytes]) -> tuple[object, ...]:
        """Project one decoded record into this target's column order.

        Parameters
        ----------
        record : Mapping[str, str | int | Decimal | bytes]
            One decoded record keyed by copybook field name.

        Returns
        -------
        tuple[object, ...]
            The values in COPY column order.

        Raises
        ------
        AuroraLoadError
            If the record does not carry a field this target maps.
        """
        missing = [field for field in self.columns if field not in record]
        if missing:
            # WHY : the FIELD NAMES are reported and no value is. A decoded record of any of
            #   these datasets carries primary account numbers and national identifiers, and a
            #   diagnostic is retained and readable by every holder of log access.
            raise AuroraLoadError(
                f"a record bound for {self.schema}.{self.table} is missing the mapped field(s)"
                f" {', '.join(missing)}; the reader and the target disagree about the record's"
                " shape, so nothing is loaded"
            )
        return tuple(record[name] for name in self.columns)

    def copy_columns(self) -> tuple[str, ...]:
        """List the target column names in COPY order.

        Purpose
        -------
        Give the stage-and-merge path the same column list the COPY statement names, from the
        same source, so the staging table, the COPY and the insert cannot disagree about either
        the set of columns or their order.

        Returns
        -------
        tuple[str, ...]
            The target column names, in the mapping's iteration order.

        Raises
        ------
        None
        """
        return tuple(self.columns.values())

    def comparable_fields(self) -> tuple[str, ...]:
        """List the fields whose stored value a digest can compare against its source value.

        Purpose
        -------
        Let the checksum verification pass digest both sides over the same field set, by naming
        the fields it must EXCLUDE because their stored form is not a function of the source
        value at all.

        Returns
        -------
        tuple[str, ...]
            Every mapped field except the ones declaring a sealing projection, in the mapping's
            own order.

        Raises
        ------
        None
        """
        # WHY : Assumptions: a SEALED field is excluded and every other projected field is kept.
        #   The distinction is determinism. Trimming and timestamp rendering are functions of the
        #   source value, so digesting the PREPARED record reproduces exactly what was stored; an
        #   envelope is not -- its initialisation vector is drawn per value, so the same identifier
        #   enciphered twice yields different bytes, and a digest comparison over it would report
        #   a difference on every single run. Excluding it is not a weakening of the check: the
        #   ciphertext cannot be verified by comparison at all, only by decryption, which this
        #   package deliberately cannot perform.
        sealing = {Projection.SEALED_IDENTIFIER, Projection.SEALED_VERIFICATION_VALUE}
        return tuple(
            name
            for name in self.columns
            if self.projections.get(name, Projection.VERBATIM) not in sealing
        )

    @property
    def stage_name(self) -> str:
        """Report the safely-quoted name of this target's session-temporary staging table.

        Returns
        -------
        str
            The quoted identifier, unqualified so that it resolves in the session's temporary
            schema. Qualifying it with the target's schema would create a PERMANENT table there
            instead, which is the one mistake this name must not make.

        Raises
        ------
        None
        """
        return quote_identifier(f"{_STAGE_PREFIX}{self.table}")

    def stage_statement(self) -> str:
        """Compose the statement that creates this target's staging table.

        Purpose
        -------
        Create a table holding EXACTLY the columns this target copies, with exactly the target's
        own types, and nothing else.

        Returns
        -------
        str
            A ``CREATE TEMPORARY TABLE ... AS SELECT ... WITH NO DATA`` statement.

        Raises
        ------
        None
        """
        # WHY : Alternatives Considered: `CREATE TEMPORARY TABLE ... (LIKE <target>)` was written
        #   first and rejected. `LIKE` copies EVERY column of the target, including ones this
        #   target does not supply, and it copies NOT NULL without copying defaults or identity --
        #   so staging `ledger.daily_transactions` would create a `bigint NOT NULL` column with no
        #   default where the real table has `GENERATED BY DEFAULT AS IDENTITY`, and the COPY would
        #   fail on the first row for a reason that has nothing to do with the data. Selecting the
        #   copied columns with no data produces precisely those columns at precisely the target's
        #   types, needs only SELECT on the target, and carries no constraint, default or identity
        #   to work around.
        # WHY : Assumptions: `ON COMMIT DROP` is deliberately NOT used, even though it reads as the
        #   tidier choice. The commit at the end of a merge is this module's own, and a table
        #   dropped by it would be gone before a caller could inspect what was staged after a
        #   partial failure -- and the rollback path already discards the table, because a
        #   temporary table created inside the failed transaction never existed outside it. What
        #   would remain after a SUCCESSFUL load is a session-temporary table that the session's
        #   end removes, which costs nothing and keeps the failure path inspectable.
        names = ", ".join(quote_identifier(column) for column in self.columns.values())
        return (
            f"CREATE TEMPORARY TABLE {self.stage_name} AS"
            f" SELECT {names} FROM {self.qualified_name} WITH NO DATA"
        )

    def stage_copy_statement(self) -> str:
        """Compose the COPY statement that fills this target's staging table.

        Returns
        -------
        str
            A ``COPY <stage> (<columns>) FROM STDIN`` statement naming the same columns, in the
            same order, as :meth:`copy_statement`.

        Raises
        ------
        None
        """
        # Assumptions: the column list comes from the same mapping the direct COPY uses, so the
        #   staging table, this COPY and the merge below cannot disagree about either the set of
        #   columns or their order. Writing the list out three times would be three chances to
        #   reorder one of them, and a reordered COPY loads plausible values into wrong columns.
        names = ", ".join(quote_identifier(column) for column in self.columns.values())
        return f"COPY {self.stage_name} ({names}) FROM STDIN"

    def merge_statement(self) -> str:
        """Compose the statement that moves staged rows into the target on the declared key.

        Purpose
        -------
        Insert every staged row the target does not already hold, with explicit conflict
        semantics, so that a load composing with a second writer neither fails nor overwrites.

        Returns
        -------
        str
            An ``INSERT ... SELECT ... ON CONFLICT (<key>) DO NOTHING`` statement.

        Raises
        ------
        AuroraLoadError
            If the target declares no conflict key, which means this path was reached for a
            target that was never meant to take it.
        """
        if not self.conflict_key:
            raise AuroraLoadError(
                f"target {self.schema}.{self.table} declares no conflict key, so it has no merge"
                " statement; a table with one writer loads through a direct COPY"
            )
        # WHY : Trade-offs: DO NOTHING rather than DO UPDATE, and the choice is what makes the
        #   two writers genuinely compose rather than merely coexist. `V2__seed_reference.sql`
        #   uses DO NOTHING on all six of its inserts, so with DO NOTHING here the outcome is the
        #   same set of rows whichever writer runs first, whichever runs second, and however many
        #   times either runs. DO UPDATE would instead make the stored description depend on
        #   execution order -- and since this loader now trims its descriptions to match the
        #   migration's exactly, an update would be writing identical values over identical values
        #   while producing a different row version and a different affected-row count.
        names = ", ".join(quote_identifier(column) for column in self.columns.values())
        key = ", ".join(quote_identifier(column) for column in self.conflict_key)
        return (
            f"INSERT INTO {self.qualified_name} ({names})"
            f" SELECT {names} FROM {self.stage_name}"
            f" ON CONFLICT ({key}) DO NOTHING"
        )


def _projected(
    target: TableTarget,
    record: Mapping[str, object],
    name: str,
    context: LoadContext,
) -> object:
    """Apply one field's declared projection and return the value its column stores.

    Purpose
    -------
    Hold every projection in one place, so a target's declaration is the whole statement of how
    its values are transformed and no reader has to know a target column's type.

    Parameters
    ----------
    target : TableTarget
        The target whose declaration is being applied, named so a refusal can identify the table.
    record : Mapping[str, object]
        The decoded record, keyed by copybook field name.
    name : str
        The key of ``target.columns`` being projected. For a derived field this is the column's
        own name rather than a copybook field's.
    context : LoadContext
        The collaborators the non-mechanical projections need.

    Returns
    -------
    object
        The value to write into the column: a ``str``, a ``Decimal``, ``bytes`` or ``None``.

    Raises
    ------
    AuroraLoadError
        If the record does not carry the field, if a projection needs a collaborator the context
        does not supply, if a subject is not published for a user id, or if a value's type cannot
        satisfy the declared projection.
    """
    projection = target.projections.get(name, Projection.VERBATIM)
    column = target.columns[name]

    if projection is Projection.SUBJECT_FOR_USER_ID:
        # Assumptions: the source field is `SEC-USR-ID` and is named here, at the one
        #   projection whose input differs from the key it is declared against. Reading the
        #   record's own `name` would look for a `cognito_sub` field no reader publishes.
        user_id = record.get(_USER_ID_FIELD)
        if not isinstance(user_id, str):
            raise AuroraLoadError(
                f"a record bound for {target.schema}.{target.table} carries no"
                f" {_USER_ID_FIELD}, so no identity-provider subject can be resolved for"
                f" column {column}"
            )
        subject = context.subjects.get(user_id)
        if subject is None:
            # Trade-offs: the USER ID is named and the subject document is not enumerated.
            #   A user id is an eight-character operator-facing key, so naming it is what makes
            #   the diagnostic actionable; the document pairs every id with its subject, and
            #   printing it would put that whole pairing in a log.
            raise AuroraLoadError(
                f"no identity-provider subject is published for user id {user_id!r}, and"
                f" {target.schema}.{target.table} declares its subject column NOT NULL; the"
                " seed-user subject document and the security-user extract disagree about who"
                " exists, so nothing is loaded"
            )
        return subject

    if name not in record:
        raise AuroraLoadError(
            f"a record bound for {target.schema}.{target.table} is missing the mapped field"
            f" {name}; the reader and the target disagree about the record's shape, so nothing"
            " is loaded"
        )
    value = record[name]

    if projection is Projection.VERBATIM:
        return value

    if projection in (Projection.TRIMMED, Projection.TRIMMED_OR_NULL):
        if not isinstance(value, str):
            raise AuroraLoadError(
                f"field {name} of a record bound for {target.schema}.{target.table} declares a"
                f" text projection but decoded to {type(value).__name__}; the target's"
                " declaration and the reader's regime disagree"
            )
        # Assumptions: only the BLANK is removed, and only from the right, matching
        #   `ebcdic_codec.trim_trailing_blanks`. A leading blank is a right-aligned value's
        #   alignment, and a trailing low value is content this module has no licence to
        #   interpret, so the argument-free strip is refused for both reasons.
        trimmed = value.rstrip(" ")
        if projection is Projection.TRIMMED_OR_NULL and not trimmed:
            return None
        return trimmed

    if projection is Projection.TIMESTAMP_OR_NULL:
        if not isinstance(value, str):
            raise AuroraLoadError(
                f"field {name} of a record bound for {target.schema}.{target.table} declares a"
                f" timestamp projection but decoded to {type(value).__name__}"
            )
        try:
            return timestamp.canonical(value)
        except ValueError as exc:
            # Trade-offs: the shared renderer's message is carried through because it quotes
            #   no part of the value -- it names the width and the two admitted forms only. This
            #   wrapper adds the record and the column, which the renderer cannot know.
            raise AuroraLoadError(
                f"field {name} of a record bound for {target.schema}.{target.table} could not"
                f" be rendered for column {column}: {exc}"
            ) from exc

    if projection is Projection.SEALED_IDENTIFIER:
        cipher = context.identifier_cipher
        if cipher is None:
            raise AuroraLoadError(
                f"column {column} of {target.schema}.{target.table} holds a protected"
                " identifier, and no identifier cipher was supplied; the load is refused rather"
                " than performed with the column left unprotected"
            )
        return _sealed_text(target, name, value, lambda text: cipher.seal(text, column))

    if projection is Projection.SEALED_VERIFICATION_VALUE:
        verification = context.verification_value_cipher
        if verification is None:
            raise AuroraLoadError(
                f"column {column} of {target.schema}.{target.table} holds a protected"
                " verification value, and no verification-value cipher was supplied; the load is"
                " refused rather than performed with the column left unprotected"
            )
        return _sealed_text(target, name, value, verification.seal)

    # Assumptions: this line is unreachable while `Projection` and the branches above stay in
    #   step, and it exists so that ADDING a member without a branch fails loudly on the first
    #   record instead of returning `None` into a column. A `match` with no default would raise a
    #   bare `UnboundLocalError` here, which names neither the member nor the column.
    raise AuroraLoadError(
        f"projection {projection.value} declared for column {column} of"
        f" {target.schema}.{target.table} has no implementation in this module"
    )


def _sealed_text(
    target: TableTarget,
    name: str,
    value: object,
    seal: Any,
) -> bytes | None:
    """Encipher one protected field's value, or report its absence as a null column.

    Purpose
    -------
    Share the three steps both sealing projections need -- reveal a protected value, treat a
    blank one as absent, and translate a cipher refusal into a load failure -- so the two cannot
    come to handle absence or failure differently.

    Parameters
    ----------
    target : TableTarget
        The target being loaded, named so a refusal identifies the table.
    name : str
        The field being sealed, named so a refusal identifies the column's source.
    value : object
        The decoded value. A reader may publish it as a ``str`` or as a protected wrapper
        exposing ``reveal()``; both are accepted, and nothing else is.
    seal : Any
        The cipher call to apply to the revealed text. Typed loosely because the two ciphers
        take different argument counts and each is bound by its caller before arriving here.

    Returns
    -------
    bytes | None
        The framed envelope, or ``None`` when the source field holds nothing -- which is how a
        nullable protected column expresses an absent value, and which the ciphers refuse to
        encipher for exactly that reason.

    Raises
    ------
    AuroraLoadError
        If the value is neither text nor a protected wrapper, or the cipher refuses it.
    """
    # Assumptions: a protected wrapper is unwrapped through `reveal()` HERE and nowhere
    #   else in the load path. `readers/card.py` publishes the verification value as a
    #   `ProtectedValue` whose repr is `[PROTECTED]` precisely so that it cannot reach a log or a
    #   diagnostic by accident; this is the boundary that wrapper exists to be handed to, so the
    #   unwrap happens once, at the point the value is about to become ciphertext.
    reveal = getattr(value, "reveal", None)
    text = reveal() if callable(reveal) else value
    if not isinstance(text, str):
        raise AuroraLoadError(
            f"field {name} of a record bound for {target.schema}.{target.table} declares a"
            f" protected projection but decoded to {type(value).__name__}, which is neither"
            " text nor a protected value"
        )
    # Trade-offs: a field of nothing but blanks becomes NULL rather than an envelope over an
    #   empty string. The nullable protected column's absent state is a null, and enciphering
    #   nothing would instead store a present envelope that deciphers to zero characters -- which
    #   no reader can tell apart from a corrupted one.
    stripped = text.rstrip(" ")
    if not stripped:
        return None
    try:
        return seal(stripped)
    except Exception as exc:
        # Trade-offs: the cipher's own message is carried through because that module is
        #   written to name no value; the record and the field are added here. The guard is broad
        #   because a cipher refusal, a key-service refusal and a missing distribution all arrive
        #   as different types and all mean the same thing to this loader: nothing is written.
        raise AuroraLoadError(
            f"field {name} of a record bound for {target.schema}.{target.table} could not be"
            f" protected: {exc}"
        ) from exc


def prepare_record(
    target: TableTarget,
    record: Mapping[str, object],
    context: LoadContext | None = None,
) -> Mapping[str, object]:
    """Apply every projection a target declares, yielding a record ready to project into a row.

    Purpose
    -------
    Separate transformation from projection, so that :meth:`TableTarget.row_of` stays a pure
    ordering of values and every transformation happens once, in one place, against the target's
    own declaration.

    Parameters
    ----------
    target : TableTarget
        The target being loaded.
    record : Mapping[str, object]
        One decoded record, as a reader yields it.
    context : LoadContext | None
        The collaborators the non-mechanical projections need. ``None`` is equivalent to an
        empty context and is correct for a target declaring only verbatim and text projections.

    Returns
    -------
    Mapping[str, object]
        A mapping carrying exactly the keys ``target.columns`` names, each holding the value its
        column stores. Fields the target does not map are absent, so nothing unmapped can reach
        a row by accident.

    Raises
    ------
    AuroraLoadError
        If the record is missing a mapped field, a projection needs a collaborator the context
        does not supply, a subject is unpublished, or a value cannot satisfy its projection.
    """
    resolved = LoadContext() if context is None else context
    # Assumptions: the result is built from `target.columns` rather than from the record, so
    #   it carries the mapped fields and NOTHING else. Copying the record and overwriting the
    #   projected keys was the shorter form and is rejected: it would carry every unmapped field
    #   forward, and a field added to a reader would then travel silently into the prepared record
    #   -- harmless today, and exactly how an unmapped protected value ends up somewhere it was
    #   never reviewed to be.
    return MappingProxyType(
        {name: _projected(target, record, name, resolved) for name in target.columns}
    )


# Assumptions: the field the subject projection reads, named once because it is the ONE
#   projection whose input field differs from the key it is declared against. `SEC-USR-ID` is
#   `PIC X(08)` at app/cpy/CSUSR01Y.cpy L18, it is `auth.users`' primary key, and
#   `config.resolve_seed_user_subjects` validates the same width on the document it reads -- so
#   the three agree on the join key rather than each choosing one.
_USER_ID_FIELD: Final[str] = "SEC-USR-ID"

# Assumptions: the registered layouts whose absence from `TARGETS` is a design decision rather
#   than an omission, so `target_for` can say which of the two it is. `TRAN` is the transaction
#   master, filled by the posting job; the three derived layouts `TRNX`, `REJECT` and `INTTRAN`
#   are written by the batch chain and are not part of a seed load at all, so a caller naming one
#   gets the same explanation rather than the misspelling message.
_POSTING_FILLED_RECORDS: Final[frozenset[str]] = frozenset({"TRAN", "TRNX", "REJECT", "INTTRAN"})

# Assumptions: the staging table lives in the session's temporary schema and is named from the
#   target table, so two concurrent loads of DIFFERENT records cannot collide on it and a load of
#   the SAME record from two sessions still cannot, a temporary table being per-session. The
#   prefix is spelled out rather than generated, because a generated name would make the statement
#   this module issues unpredictable in a log an operator is reading to see what ran.
_STAGE_PREFIX: Final[str] = "carddemo_stage_"

# WHY : Assumptions: every mapping below was read from the owning service's own Flyway
#   migration rather than derived, and each records one anti-corruption decision. The three
#   baseline misspellings are corrected here: the account and card expiration dates, spelled
#   `EXPIRAION` in the copybooks, and the authorization merchant category code. `FILLER` never
#   appears, because a reader never publishes it.
# WHY : Assumptions: TEN records, which is every base master except the transaction master. That
#   one has no target here for a reason recorded from three sides: no `TRANSACT` dataset ships in
#   either encoding, `readers/transaction.py` treats an absent source as a normal state, and
#   `sql/verify/row_counts.sql` gives `ledger.transactions` a NULL baseline rather than zero --
#   the table is filled by the posting job from `ledger.daily_transactions`, which IS loaded here.
TARGETS: Final[Mapping[str, TableTarget]] = MappingProxyType(
    {
        "XREF": TableTarget(
            schema="account",
            table="card_xref",
            columns=MappingProxyType(
                {
                    "XREF-CARD-NUM": "card_num",
                    "XREF-CUST-ID": "customer_id",
                    "XREF-ACCT-ID": "account_id",
                }
            ),
        ),
        # WHY : Assumptions: the three reference targets are the only ones declaring a conflict
        #   key, and the only ones trimming a descriptive column. Both declarations exist for the
        #   same reason: `V2__seed_reference.sql` writes these three tables too, with
        #   `ON CONFLICT ... DO NOTHING` on each of its inserts, and it writes the descriptions
        #   TRIMMED -- `'Purchase'`, not `'Purchase'` followed by forty-two blanks. Without the
        #   conflict key a second writer makes the load fail on a unique violation; without the
        #   trim the two writers produce rows that differ in content while agreeing in count, so
        #   the row-count pass would pass and the description a screen renders would depend on
        #   which writer ran first.
        "TRANTYPE": TableTarget(
            schema="reference",
            table="transaction_types",
            columns=MappingProxyType(
                {
                    "TRAN-TYPE": "type_cd",
                    "TRAN-TYPE-DESC": "description",
                }
            ),
            projections=MappingProxyType({"TRAN-TYPE-DESC": Projection.TRIMMED}),
            conflict_key=("type_cd",),
        ),
        "TRANCAT": TableTarget(
            schema="reference",
            table="transaction_categories",
            columns=MappingProxyType(
                {
                    "TRAN-TYPE-CD": "type_cd",
                    "TRAN-CAT-CD": "cat_cd",
                    "TRAN-CAT-TYPE-DESC": "description",
                }
            ),
            projections=MappingProxyType({"TRAN-CAT-TYPE-DESC": Projection.TRIMMED}),
            conflict_key=("type_cd", "cat_cd"),
        ),
        # WHY : Assumptions: the group id is NOT trimmed although the two descriptions above are,
        #   and the asymmetry follows the COLUMN rather than the value. `acct_group_id` is
        #   `CHAR(10)`, so PostgreSQL pads whatever it is given back to ten characters and the
        #   trimmed and untrimmed forms are the same stored value; a description column is
        #   `VARCHAR(50)`, where they are not. Trimming a key would also be the wrong habit to
        #   establish here: the interest calculation's `DEFAULT` fallback matches on this column.
        "DISGROUP": TableTarget(
            schema="reference",
            table="disclosure_groups",
            columns=MappingProxyType(
                {
                    "DIS-ACCT-GROUP-ID": "acct_group_id",
                    "DIS-TRAN-TYPE-CD": "tran_type_cd",
                    "DIS-TRAN-CAT-CD": "tran_cat_cd",
                    "DIS-INT-RATE": "interest_rate",
                }
            ),
            conflict_key=("acct_group_id", "tran_type_cd", "tran_cat_cd"),
        ),
        "ACCOUNT": TableTarget(
            schema="account",
            table="accounts",
            columns=MappingProxyType(
                {
                    "ACCT-ID": "account_id",
                    "ACCT-ACTIVE-STATUS": "active_status",
                    "ACCT-CURR-BAL": "curr_bal",
                    "ACCT-CREDIT-LIMIT": "credit_limit",
                    "ACCT-CASH-CREDIT-LIMIT": "cash_credit_limit",
                    "ACCT-OPEN-DATE": "open_date",
                    # WHY : the source field is spelled EXPIRAION in app/cpy/CVACT01Y.cpy. The
                    #   correction happens here, once, and the target column carries the correct
                    #   spelling; the copybook is reference-only and keeps its own.
                    "ACCT-EXPIRAION-DATE": "expiration_date",
                    "ACCT-REISSUE-DATE": "reissue_date",
                    "ACCT-CURR-CYC-CREDIT": "curr_cyc_credit",
                    "ACCT-CURR-CYC-DEBIT": "curr_cyc_debit",
                    "ACCT-ADDR-ZIP": "addr_zip",
                    "ACCT-GROUP-ID": "group_id",
                }
            ),
        ),
        # WHY : Assumptions: the verification value is the one column in this target whose value
        #   is not a copy of what the reader decoded. `readers/card.py` publishes `CARD-CVV-CD` as
        #   a `ProtectedValue` whose repr is `[PROTECTED]`, specifically so it cannot reach a log
        #   by accident, and this declaration is the boundary that wrapper was built to be handed
        #   to: the value is revealed, enciphered under the card framing and stored as an envelope.
        #   The column is NULLABLE, so a blank source field becomes a null rather than an envelope
        #   over nothing.
        "CARD": TableTarget(
            schema="card",
            table="cards",
            columns=MappingProxyType(
                {
                    "CARD-NUM": "card_num",
                    "CARD-ACCT-ID": "account_id",
                    "CARD-CVV-CD": "cvv_encrypted",
                    "CARD-EMBOSSED-NAME": "embossed_name",
                    # WHY : the source field is spelled EXPIRAION in app/cpy/CVACT02Y.cpy, the
                    #   second of the baseline's three misspellings. The correction happens here,
                    #   once, exactly as it does for the account master above.
                    "CARD-EXPIRAION-DATE": "expiration_date",
                    "CARD-ACTIVE-STATUS": "active_status",
                }
            ),
            projections=MappingProxyType(
                {
                    "CARD-CVV-CD": Projection.SEALED_VERIFICATION_VALUE,
                    "CARD-EMBOSSED-NAME": Projection.TRIMMED,
                }
            ),
        ),
        # WHY : Assumptions: the two identifier columns are sealed under the SAME framing but with
        #   DIFFERENT encryption contexts, because the context binds the column name -- so an
        #   envelope written for one of them cannot be read from the other. That is why the
        #   projection is declared per field rather than once for the target: the column name each
        #   one is bound to comes from this mapping.
        # WHY : Trade-offs: `middle_name`, `addr_line_2`, `phone_num_1` and `phone_num_2` project
        #   TRIMMED_OR_NULL while `first_name`, `last_name`, `addr_line_1` and `addr_line_3`
        #   project TRIMMED, and the split is exactly the columns' own nullability rather than a
        #   judgement about the data. Storing `''` in a nullable column would make "no middle name"
        #   and "a middle name of zero characters" the same value, and only the first is a fact
        #   about a customer; storing `None` in a NOT NULL column would fail the load, which is
        #   correct -- a customer with no first name is a corrupt record, not an absent value.
        "CUSTOMER": TableTarget(
            schema="account",
            table="customers",
            columns=MappingProxyType(
                {
                    "CUST-ID": "customer_id",
                    "CUST-FIRST-NAME": "first_name",
                    "CUST-MIDDLE-NAME": "middle_name",
                    "CUST-LAST-NAME": "last_name",
                    "CUST-ADDR-LINE-1": "addr_line_1",
                    "CUST-ADDR-LINE-2": "addr_line_2",
                    "CUST-ADDR-LINE-3": "addr_line_3",
                    "CUST-ADDR-STATE-CD": "addr_state_cd",
                    "CUST-ADDR-COUNTRY-CD": "addr_country_cd",
                    "CUST-ADDR-ZIP": "addr_zip",
                    "CUST-PHONE-NUM-1": "phone_num_1",
                    "CUST-PHONE-NUM-2": "phone_num_2",
                    "CUST-SSN": "ssn_encrypted",
                    "CUST-GOVT-ISSUED-ID": "govt_issued_id_encrypted",
                    "CUST-DOB-YYYY-MM-DD": "dob",
                    "CUST-EFT-ACCOUNT-ID": "eft_account_id",
                    "CUST-PRI-CARD-HOLDER-IND": "pri_card_holder_ind",
                    "CUST-FICO-CREDIT-SCORE": "fico_credit_score",
                }
            ),
            projections=MappingProxyType(
                {
                    "CUST-FIRST-NAME": Projection.TRIMMED,
                    "CUST-MIDDLE-NAME": Projection.TRIMMED_OR_NULL,
                    "CUST-LAST-NAME": Projection.TRIMMED,
                    "CUST-ADDR-LINE-1": Projection.TRIMMED,
                    "CUST-ADDR-LINE-2": Projection.TRIMMED_OR_NULL,
                    "CUST-ADDR-LINE-3": Projection.TRIMMED,
                    "CUST-PHONE-NUM-1": Projection.TRIMMED_OR_NULL,
                    "CUST-PHONE-NUM-2": Projection.TRIMMED_OR_NULL,
                    "CUST-SSN": Projection.SEALED_IDENTIFIER,
                    "CUST-GOVT-ISSUED-ID": Projection.SEALED_IDENTIFIER,
                }
            ),
        ),
        # WHY : Assumptions: both timestamps project through the shared authority rather than
        #   verbatim, and both rather than only the processing one. All 300 shipped records carry a
        #   populated `DALYTRAN-ORIG-TS` in the space-separated dialect and a BLANK
        #   `DALYTRAN-PROC-TS` -- so verbatim would have worked for this corpus and would have
        #   failed on the first record the interest calculation writes, which uses the dotted
        #   dialect PostgreSQL cannot cast, and on the first blank stamp, which it cannot cast
        #   either. Declaring both keeps the two stamps' handling identical, which is the property
        #   a parity comparison across them depends on.
        # WHY : Assumptions: NO conflict key, although `ingest_seq` makes every row unique. The
        #   primary key is `GENERATED BY DEFAULT AS IDENTITY` and this target does not supply it,
        #   so there is no natural key to conflict ON: the same feed record loaded twice is
        #   genuinely two rows, and the baseline's own daily feed is a fresh extract per run rather
        #   than a keyed master. A conflict key here would have to be invented, and an invented key
        #   silently discards a legitimate duplicate transaction.
        "DALYTRAN": TableTarget(
            schema="ledger",
            table="daily_transactions",
            columns=MappingProxyType(
                {
                    "DALYTRAN-ID": "transaction_id",
                    "DALYTRAN-TYPE-CD": "type_cd",
                    "DALYTRAN-CAT-CD": "category_cd",
                    "DALYTRAN-SOURCE": "source",
                    "DALYTRAN-DESC": "description",
                    "DALYTRAN-AMT": "amount",
                    "DALYTRAN-MERCHANT-ID": "merchant_id",
                    "DALYTRAN-MERCHANT-NAME": "merchant_name",
                    "DALYTRAN-MERCHANT-CITY": "merchant_city",
                    "DALYTRAN-MERCHANT-ZIP": "merchant_zip",
                    "DALYTRAN-CARD-NUM": "card_num",
                    "DALYTRAN-ORIG-TS": "orig_ts",
                    "DALYTRAN-PROC-TS": "proc_ts",
                }
            ),
            projections=MappingProxyType(
                {
                    "DALYTRAN-DESC": Projection.TRIMMED,
                    "DALYTRAN-MERCHANT-NAME": Projection.TRIMMED,
                    "DALYTRAN-MERCHANT-CITY": Projection.TRIMMED,
                    "DALYTRAN-ORIG-TS": Projection.TIMESTAMP_OR_NULL,
                    "DALYTRAN-PROC-TS": Projection.TIMESTAMP_OR_NULL,
                }
            ),
        ),
        # WHY : Assumptions: the field spelled `TRANCAT-CD` becomes `category_cd` while its
        #   siblings keep their stems, because the copybook's own group prefix is inconsistent --
        #   `TRANCAT-ACCT-ID` and `TRANCAT-TYPE-CD` carry it, the category code drops the second
        #   half of it, and the balance drops the prefix entirely as `TRAN-CAT-BAL`. This is
        #   exactly the sort of mapping a hyphen-to-underscore rule would get wrong in four
        #   different ways, which is why the mapping is declared rather than derived.
        # WHY : Refactoring Rationale: this target was absent, and its absence was not a
        #   deliberate exclusion of the kind recorded above for the customer and card records.
        #   The whole verification layer already declared this dataset as one it checks --
        #   data-migration/sql/verify/row_counts.sql line 162 registers `tcatbal` against
        #   `ledger.transaction_category_balances` at 50 bytes and line 219 counts that table,
        #   and money_totals.sql lines 228 and 372 aggregate its `balance` column against
        #   `TRAN-CAT-BAL`. `carddemo_migration.readers.tcatbal` states in its own opening
        #   paragraph that it decodes the extract for "the Aurora loaders and the verification
        #   passes". Every part of the pipeline except the one that writes rows therefore
        #   treated the dataset as migrated, which left those queries able only to compare a
        #   source count against zero. Declaring the target is what makes them able to pass, and
        #   what makes the reader's statement true rather than aspirational.
        # WHY : Assumptions: the owning schema is `ledger` and NOT `account`, even though the
        #   record leads with an eleven-digit account identifier and even though the sibling
        #   `ACCOUNT` target above writes into `account`. The table belongs to the context that
        #   owns the posting unit of work: app/cbl/CBTRN02C.cbl updates this row, the daily
        #   transaction and the account together in one commit, and the balance it accumulates
        #   is a ledger quantity rather than an attribute of the account. Sending it to
        #   `account` would put a write the transaction context owns inside another context's
        #   schema, and the load would then be performed by a role with no grant on it --
        #   config.py line 540 maps `ledger` to its own `carddemo_ledger` role precisely so
        #   this load authenticates as the owner.
        # WHY : Assumptions: declared last, after the reference and account targets, so the
        #   five existing names keep the positions `target_names()` already reported and this
        #   mapping's iteration order continues to state a safe load order. That order is
        #   reference, then account, then ledger. This table declares no foreign key of its own
        #   -- its only constraint is the three-part composite primary key -- so the position
        #   is a convention rather than a requirement, and it is recorded as one so a future
        #   reader does not infer a dependency that the DDL does not create.
        "TCATBAL": TableTarget(
            schema="ledger",
            table="transaction_category_balances",
            columns=MappingProxyType(
                {
                    "TRANCAT-ACCT-ID": "account_id",
                    "TRANCAT-TYPE-CD": "type_cd",
                    # WHY : Assumptions: `TRANCAT-CD` becomes `category_cd` here and NOT
                    #   `cat_cd`, which is what the `TRANCAT` target above maps its own
                    #   category code to. The two are not inconsistent and neither is a
                    #   typographical slip: the same four-character category code is spelled
                    #   `cat_cd` in `reference.transaction_categories`, `tran_cat_cd` in
                    #   `reference.disclosure_groups` and `category_cd` in this table, because
                    #   each owning service named its own column and this table's spelling
                    #   matches `ledger.transactions.category_cd` beside it so a join across
                    #   the two needs no aliasing. This is the concrete case the module's
                    #   Assumptions section describes when it says the mapping cannot be
                    #   derived from the field name -- a hyphen-to-underscore rule would emit
                    #   `trancat_cd` and the COPY would abort on an unknown column.
                    "TRANCAT-CD": "category_cd",
                    # WHY : Assumptions: the balance is mapped even though the column declares
                    #   `NOT NULL DEFAULT 0`, so a loaded row carries the extract's own value
                    #   rather than the default. The default exists for a row this load did not
                    #   produce; leaving the column unmapped here would silently zero every
                    #   balance in the dataset, and two of the three verification passes would
                    #   still report agreement -- row counts never read a non-key column, and the
                    #   checksum digests exactly the fields the target maps, so an unmapped
                    #   balance drops out of the digest on BOTH sides. Only money parity would
                    #   notice, as a total short by the whole sum. Mapping the column is what
                    #   makes the extract authoritative rather than the default.
                    "TRAN-CAT-BAL": "balance",
                }
            ),
        ),
        # WHY : Assumptions: `cognito_sub` is keyed by its COLUMN name rather than by a copybook
        #   field, and is declared derived for that reason. `auth.users` requires it NOT NULL and
        #   UNIQUE, and the 80-byte `USRSEC` record cannot supply it: a subject is minted by the
        #   identity provider, so `infra/modules/cognito` publishes the mapping and
        #   `config.resolve_seed_user_subjects` reads it. Using a lower-case underscored key is
        #   what makes the derivation unmistakable -- every copybook field name in this module is
        #   upper-case and hyphenated, so the two vocabularies cannot be confused.
        # WHY : Assumptions: `SEC-USR-PWD` is absent from this mapping and there is no column for
        #   it to be absent FROM. `auth.users` declares no password column of any kind -- not
        #   plaintext, not a hash, not a shadow column -- and `readers/usrsec.py` never decodes the
        #   span at all, so the baseline's cleartext credential has no path into the target
        #   database through this module or any other.
        "SECUSER": TableTarget(
            schema="auth",
            table="users",
            columns=MappingProxyType(
                {
                    "SEC-USR-ID": "user_id",
                    "SEC-USR-FNAME": "first_name",
                    "SEC-USR-LNAME": "last_name",
                    "SEC-USR-TYPE": "user_type",
                    "cognito_sub": "cognito_sub",
                }
            ),
            projections=MappingProxyType(
                {
                    "SEC-USR-FNAME": Projection.TRIMMED,
                    "SEC-USR-LNAME": Projection.TRIMMED,
                    "cognito_sub": Projection.SUBJECT_FOR_USER_ID,
                }
            ),
            derived_fields=frozenset({"cognito_sub"}),
        ),
    }
)


def target_names() -> tuple[str, ...]:
    """List the record names this module can load, in declaration order.

    Returns
    -------
    tuple[str, ...]
        Every key of :data:`TARGETS`.

    Raises
    ------
    None
    """
    return tuple(TARGETS)


def target_for(record_name: str) -> TableTarget:
    """Resolve the load target for a record name.

    Parameters
    ----------
    record_name : str
        The layout name, as :data:`carddemo_migration.copybook.layouts.LAYOUTS` keys it.

    Returns
    -------
    TableTarget
        The declared target.

    Raises
    ------
    AuroraLoadError
        If the record has no declared target. The transaction master is named explicitly,
        because it is the one base master with no target BY DESIGN and the reason is not
        guessable from the message a misspelling would deserve.
    """
    try:
        return TARGETS[record_name]
    except KeyError as exc:
        # WHY : Refactoring Rationale: this branch used to name CUSTOMER and CARD as
        #   deliberately unloadable "because its table declares protected columns holding
        #   ciphertext ... under a key this package does not hold". Both now load, through
        #   `loaders/protected_columns.py`, and that refusal is withdrawn rather than reworded --
        #   it was the mechanism by which two core masters had no migration path at all. What
        #   remains is the one record that genuinely has no target, and it is named for the
        #   opposite reason: not because loading it is refused, but because there is nothing to
        #   load. No `TRANSACT` dataset ships in either encoding, and `ledger.transactions` is
        #   filled by the posting job from `ledger.daily_transactions`, which this module does load.
        if record_name in _POSTING_FILLED_RECORDS:
            raise AuroraLoadError(
                f"record {record_name} has no load target because no extract for it ships in"
                " either encoding; ledger.transactions is filled by the posting job from"
                " ledger.daily_transactions, which is loadable here as DALYTRAN"
            ) from exc
        raise AuroraLoadError(
            f"record {record_name} has no declared load target; the loadable records are"
            f" {', '.join(target_names())}"
        ) from exc


def connect(settings: AuroraConnectionSettings) -> Any:
    """Open a database connection from resolved settings.

    Purpose
    -------
    Keep the driver import inside the one function that needs it, so importing this module on
    a host without the driver still works and the failure names the missing dependency.

    Parameters
    ----------
    settings : AuroraConnectionSettings
        The resolved connection parameters.

    Returns
    -------
    Any
        An open connection.

    Raises
    ------
    ConfigurationError
        If the driver is not installed, which is an incomplete environment rather than a
        database that refused.
    AuroraLoadError
        If the driver is present and the connection attempt fails.
    """
    try:
        import psycopg
    except ImportError as exc:  # pragma: no cover - exercised only on an incomplete install
        raise ConfigurationError(
            "the psycopg driver is required to bulk-load Aurora but is not installed; install"
            " data-migration/requirements.txt"
        ) from exc
    try:
        return psycopg.connect(**settings.as_connection_params())
    except psycopg.Error as exc:
        # WHY : the settings object's own repr is used, which is defined to withhold the
        #   password. Formatting the connection parameters here would put the secret in the
        #   message.
        raise AuroraLoadError(f"could not connect to the cluster as {settings!r}: {exc}") from exc


@contextlib.contextmanager
def _cursor_of(connection: _Connection) -> Iterator[_Cursor]:
    """Yield a cursor from a connection, tolerating a double that returns one directly.

    Purpose
    -------
    Let the same code path serve the driver, whose ``cursor()`` is a context manager, and a
    test double that returns a plain object.

    Parameters
    ----------
    connection : _Connection
        The open connection.

    Yields
    ------
    _Cursor
        The cursor to use.

    Raises
    ------
    None
    """
    candidate = connection.cursor()
    if hasattr(candidate, "__enter__"):
        with candidate as cursor:
            yield cursor
        return
    yield candidate


def load_records(
    connection: _Connection,
    target: TableTarget,
    records: Iterable[Mapping[str, str | int | Decimal | bytes]],
    context: LoadContext | None = None,
) -> LoadOutcome:
    """Bulk-load decoded records into one table and commit.

    Purpose
    -------
    Stream every record through one server-side COPY, then commit once, so the load is one
    unit of work whose partial application is not observable. When the target declares a
    conflict key the COPY lands in a session-temporary staging table and one insert merges it,
    which keeps the same single unit of work while letting the load compose with a second writer.

    Parameters
    ----------
    connection : _Connection
        An open connection. The caller owns closing it.
    target : TableTarget
        Where the records load, which column each field becomes, and how each value is projected.
    records : Iterable[Mapping[str, str | int | Decimal | bytes]]
        Decoded records, as a reader yields them.
    context : LoadContext | None
        The collaborators a non-mechanical projection needs. ``None`` is correct for a target
        declaring only verbatim, text and timestamp projections.

    Returns
    -------
    LoadOutcome
        Rows read and rows the table gained. The two differ only on the merge path, where the
        difference is the number of rows the target already held.

    Raises
    ------
    AuroraLoadError
        If a record does not carry a mapped field, a projection cannot be applied, or the COPY or
        the merge fails. The transaction is rolled back before the error is raised.
    """
    if target.conflict_key:
        return _merge_records(connection, target, records, context)
    return _copy_records(connection, target, records, context)


def _copy_records(
    connection: _Connection,
    target: TableTarget,
    records: Iterable[Mapping[str, str | Decimal | bytes]],
    context: LoadContext | None,
) -> LoadOutcome:
    """Stream every record straight into the target table through one COPY, then commit.

    Purpose
    -------
    Carry the load path for a table with exactly one writer, where a row already present is a
    fault to report rather than a state to merge into.

    Parameters
    ----------
    connection : _Connection
        An open connection. The caller owns closing it.
    target : TableTarget
        The target, which must declare no conflict key.
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records, as a reader yields them.
    context : LoadContext | None
        The collaborators a non-mechanical projection needs.

    Returns
    -------
    LoadOutcome
        Rows read, and the same number inserted -- on this path every accepted row goes into the
        table, so the two counts are equal by construction rather than by observation.

    Raises
    ------
    AuroraLoadError
        If a record cannot be prepared or the COPY fails. The transaction is rolled back first.
    """
    written = 0
    try:
        with _cursor_of(connection) as cursor, cursor.copy(target.copy_statement()) as stream:
            for record in records:
                stream.write_row(target.row_of(prepare_record(target, record, context)))
                written += 1
    except AuroraLoadError:
        # WHY : the rollback happens before the re-raise, so a mapping failure partway through
        #   a stream leaves no partially-loaded table. Letting it propagate first would leave
        #   the transaction open until the connection closed, and a connection returned to a
        #   pool with an open transaction is a defect that surfaces somewhere else entirely.
        connection.rollback()
        raise
    except Exception as exc:
        connection.rollback()
        raise AuroraLoadError(
            f"the bulk load into {target.schema}.{target.table} failed after {written} row(s)"
            f" and was rolled back: {exc}"
        ) from exc
    connection.commit()
    return LoadOutcome(staged=written, inserted=written)


def _merge_records(
    connection: _Connection,
    target: TableTarget,
    records: Iterable[Mapping[str, str | Decimal | bytes]],
    context: LoadContext | None,
) -> LoadOutcome:
    """Stage every record in a temporary table, merge it on the declared key, then commit.

    Purpose
    -------
    Carry the load path for a table that has a SECOND writer, so that a load into a table the
    seed migration has already filled adds the rows that are missing and leaves the rest alone,
    rather than aborting on the first key collision.

    Parameters
    ----------
    connection : _Connection
        An open connection. The caller owns closing it.
    target : TableTarget
        The target, which must declare a conflict key.
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records, as a reader yields them.
    context : LoadContext | None
        The collaborators a non-mechanical projection needs.

    Returns
    -------
    LoadOutcome
        Rows staged and rows the table actually gained. A run against an already-seeded table
        reports every row staged and none inserted, which is a success and reads as one.

    Raises
    ------
    AuroraLoadError
        If a record cannot be prepared, or the staging, the COPY or the merge fails. The
        transaction is rolled back first, which also discards the staging table.
    """
    staged = 0
    inserted = 0
    try:
        with _cursor_of(connection) as cursor:
            cursor.execute(target.stage_statement())
            with cursor.copy(target.stage_copy_statement()) as stream:
                for record in records:
                    stream.write_row(target.row_of(prepare_record(target, record, context)))
                    staged += 1
            cursor.execute(target.merge_statement())
            # Assumptions: the inserted count is read from the driver's affected-row count for
            #   the MERGE statement, which under `ON CONFLICT ... DO NOTHING` counts the rows
            #   actually added and not the rows offered. That is the number an operator needs and
            #   it is not derivable from anything else this function sees. A driver or a double
            #   that reports no count is treated as reporting none rather than as reporting zero,
            #   because zero is a meaningful answer here and must not be manufactured.
            reported = getattr(cursor, "rowcount", None)
            inserted = reported if isinstance(reported, int) and reported >= 0 else staged
    except AuroraLoadError:
        connection.rollback()
        raise
    except Exception as exc:
        connection.rollback()
        raise AuroraLoadError(
            f"the staged merge into {target.schema}.{target.table} failed after"
            f" {staged} staged row(s) and was rolled back: {exc}"
        ) from exc
    connection.commit()
    return LoadOutcome(staged=staged, inserted=inserted)
