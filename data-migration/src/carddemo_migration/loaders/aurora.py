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
:data:`TARGETS` declares ELEVEN records, which is every base master. The change made HERE was
10 -> 11: the transaction master, ``ledger.transactions``, was the one registered baseline in
``sql/verify/row_counts.sql`` that no target could fill. Its absence rested on a different
ground from the earlier gaps -- not that loading it was refused, but that the SEED CORPUS ships
no extract for it. That conflated the test corpus with the cutover.
``app/jcl/TRANFILE.jcl`` is a REPRO job like its nine siblings and is named as a source of this
module for that reason; the corpus merely primes the cluster with a single record instead of
shipping a full extract. Reading "no seed dataset" as "no load target" left the largest table in
the system with no migration path at all, which ``docs/runbooks/data-migration.md`` needs and
AAP 0.9.2's read-then-verify-then-switch cutover cannot do without. An absent or empty extract
is a normal state and loads zero rows successfully instead of failing.

Older history, stated as older so the figures above cannot be read as this change: the registry
began at FIVE records and reached TEN before the transaction master was added. Two of those five
additions -- ``card.cards`` and ``account.customers`` -- had been refused by name, on the stated
grounds that their tables declare protected ``BYTEA`` columns holding "ciphertext produced by the
owning service's own cipher, under a key this package has no access to and should not have". That
was a boundary drawn in the wrong place. The key is an AWS KMS customer-managed key reached by
ALIAS, so "no access" describes an IAM grant rather than a capability, and the batch task that
runs this ETL is exactly the principal such a grant is written for -- the same alias is already
published to the account and card workloads as a runtime parameter.
:mod:`carddemo_migration.loaders.protected_columns` reproduces both envelope framings exactly and
records why reproducing them is lower-risk than either of the two alternatives. The other three
of those five -- ``ledger.daily_transactions``, ``ledger.transaction_category_balances`` and
``auth.users`` -- needed no cipher at all. They were simply absent.

Assumptions:
------------
A field's value reaches its column through a declared PROJECTION rather than verbatim, and the
default is verbatim so that a target declares only its exceptions. Four exceptions exist and
each one is a property of the target column rather than of the reader: a descriptive
``VARCHAR`` column takes the value with its fixed-width blank padding removed, a nullable
column takes ``None`` where the source holds nothing, a ``TIMESTAMP(6)`` column takes the one
spelling PostgreSQL can cast, and a ``BYTEA`` column takes an envelope. Expressing these as
declarations beside the column mapping keeps the transformation reviewable in one place; doing
it in each reader instead would push target-column knowledge into eleven modules that have no
business holding it.

Trade-offs:
-----------
EVERY one of the eleven targets loads through a stage-and-merge, so re-running any dataset adds
the rows the table does not hold, leaves the rest alone, and raises nothing. There is no
second, plain-COPY path: one path is what makes "the load is idempotent" a property of the
module rather than of whichever target a reader happens to look at.

An earlier revision reserved the merge for the four tables with a SECOND WRITER --
``reference.transaction_types``, ``reference.transaction_categories`` and
``reference.disclosure_groups``, which ``V2__seed_reference.sql`` also seeds, and
``ledger.transactions``, which ``app/cbl/CBTRN02C.cbl`` posts into -- and argued that for a
single-writer master a COPY failing on the second run was the more useful behaviour, because it
reported that the table was not empty. That argument was wrong twice over, and both halves are
concrete.

First, it breaks redrive. AAP 0.4.1.7 drives this load from a Step Functions state that
re-enters a failed state FROM THE BEGINNING, so a retry is a normal event rather than an
operator error, and seven of the eleven datasets would have failed on it with a duplicate key.
The information the failure carried is not lost: :class:`LoadOutcome` reports rows staged
separately from rows the table gained, so a re-run against a full table reports every row
skipped -- which says "the table already held these" more precisely than a duplicate-key abort,
and says it without failing a state machine.

Second, one of those seven would not even have failed. ``ledger.daily_transactions`` is keyed on
``ingest_seq``, an identity column no extract supplies, and ``V1__ledger.sql`` deliberately
leaves ``transaction_id`` NON-unique because a sequential feed may carry a value twice. A plain
COPY re-run into it therefore SUCCEEDS and doubles the feed, which is the one outcome worse than
an abort: a silent duplication of financial records that only a money-total comparison would
notice. That table is the reason :class:`LoadStrategy` has two members rather than one -- it has
no unique index to conflict on, so it merges by whole-row anti-join instead.

The accepted cost of one merge path for all eleven is a second write per row: rows land in a
session-temporary table and the merge moves them across. On a cutover-sized extract that is
roughly twice the write volume of a direct COPY, and it buys exactly-once rerun semantics for
every dataset, which the alternative did not offer for any of the seven.

Alternatives Considered:
------------------------
Emptying each table and reloading it -- the obvious way to make a load repeatable, and the
closest analogue to what the baseline's ``DELETE CLUSTER`` / ``DEFINE CLUSTER`` pair achieves.
It is unavailable here for two INDEPENDENT reasons, either of which alone would rule it out.

First, the privilege does not exist. ``sql/V0__schemas_and_roles.sql`` grants each role only
what its service needs, and ``DELETE`` and ``TRUNCATE`` are withheld from every one of them --
including the batch role, whose grants are SELECT/INSERT/UPDATE on ``ledger`` and SELECT/UPDATE
on ``account``. So truncate-then-load does not fail review, it fails at RUNTIME with an
insufficient-privilege error partway through a cutover, which is the worst moment to discover it.

Second, and independently, three of the target tables are ALREADY POPULATED before this module
runs. ``reference.transaction_types``, ``reference.transaction_categories`` and
``reference.disclosure_groups`` are seeded by ``V2__seed_reference.sql`` with 7, 18 and 51 rows,
and ``ledger.transactions`` receives rows from the posting job. Emptying those would discard
another writer's committed work, and a bare ``INSERT`` into them raises a duplicate key on a
perfectly normal deployment. The stage-and-merge path composes with both instead.

The baseline is itself re-run tolerant and this preserves that property rather than inventing
it: ``app/jcl/ACCTFILE.jcl`` L27 follows its delete with ``IF MAXCC LE 08 THEN SET MAXCC = 0``,
and ``app/jcl/DEFGDGB.jcl`` carries ``IF LASTCC=12 THEN SET MAXCC=0`` after every one of its six
defines at L29, L35, L41, L47, L53 and L59. Re-running was a deliberate no-op in the original
design too.

Assumptions:
------------
A target's conflict columns are DERIVED from the record descriptor and cannot be declared. Each
target names the record it loads, :class:`TableTarget` resolves that name in
:mod:`carddemo_migration.copybook.layouts`, and the key columns are the ones the descriptor's
``key_offset``/``key_length`` window covers, translated through the target's own column mapping.
Those two numbers are the ``KEYS(len off)`` clause of the record's own ``DEFINE CLUSTER``:
``app/jcl/TRANFILE.jcl`` declares ``KEYS(16 0)`` and ``LAYOUTS["TRAN"]`` carries exactly that --
so deriving them keeps ONE copy of the VSAM key geometry in the tree. An earlier revision passed
the columns to the constructor instead, which read as explicit and was in fact a second copy that
nothing compared with the first: a key edited here and not in the descriptor, or the reverse,
would still produce a syntactically valid merge, against the wrong columns, and the load would
report success. The derivation validates the window against the field boundaries rather than
trusting it, so a key that begins or ends mid-field is refused at import rather than silently
narrowed to whichever fields happened to fall inside it.

Trade-offs:
-----------
A failure this module reports carries ALLOW-LISTED metadata only -- the operation, the
schema-qualified table, the number of rows staged, the driver exception's class name, its
SQLSTATE, and the schema, table, column and constraint names its diagnostic supplies. The
driver's own message text is deliberately NOT carried, and the chained cause is suppressed. The
reason is specific rather than precautionary: PostgreSQL's error response carries DETAIL and
CONTEXT fields alongside the primary message, a unique-violation DETAIL names the conflicting
key VALUES verbatim, and psycopg exposes all of them on the exception. Every record these
targets carry holds a primary account number, a national identifier or a cardholder name, and
``cli.py`` writes the message this module raises straight into a container log that outlives the
load. So the routine failures -- a duplicate key on a re-run, a NOT NULL violation on an
incomplete delivery -- are exactly the paths that would have disclosed row content. The accepted
cost is real: an operator diagnosing an unexpected failure gets the fault's class, its SQLSTATE
and the constraint it violated, and must reach the database's own log for the values. That is
the trade this package makes everywhere else too, and it is why the field-level refusals above
name fields rather than values.

Refactoring Rationale:
----------------------
``IDCAMS BLDINDEX`` is RETIRED, not ported, and this module deliberately builds no index at all.
The baseline ends three of its ten load jobs with one: ``app/jcl/CARDFILE.jcl`` L110,
``app/jcl/XREFFILE.jcl`` L100 and ``app/jcl/TRANFILE.jcl`` L109, each closing a four-step tail
that first defines an alternate index and a path over the cluster it has just loaded. The step
exists because VSAM builds an alternate index by reading the base cluster AFTER the data is
there, so the index is a separate artifact that goes stale unless something rebuilds it.

PostgreSQL has no equivalent step because it maintains indexes TRANSACTIONALLY -- an index is
updated by the same statement that inserts the row, inside the same transaction, so there is
never a window in which the table is loaded and its indexes are not. A port of ``BLDINDEX``
would therefore be a statement with nothing to do.

The three access paths are NOT lost, and this module is not where they come from either: they
are real secondary indexes created by the owning services' own migrations --
``idx_cards_account_id``, ``idx_card_xref_account_id`` and ``idx_transactions_proc_ts``. Schema
objects belong to the migration that owns the table, so creating them here would put two
sources of truth on the same index and leave the loader able to disagree with the schema it
loads into. That is why no ``CREATE INDEX`` appears anywhere in this file.
"""

from __future__ import annotations

import contextlib
import enum
import hashlib
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass, field
from decimal import Decimal
from types import MappingProxyType
from typing import Any, Final, Protocol

from carddemo_migration.config import (
    AuroraConnectionSettings,
    ConfigurationError,
    owner_role_for_schema,
    quote_identifier,
)
from carddemo_migration.copybook import layouts, timestamp
from carddemo_migration.loaders.protected_columns import (
    CardVerificationValueCipher,
    CustomerIdentifierCipher,
)

__all__ = [
    "CARD_IDENTITY_PROCEDURE",
    "CARD_IDENTITY_RELATION",
    "TARGETS",
    "TRANSACTION_ID_SEQUENCE",
    "AuroraLoadError",
    "CardIdentityRefresh",
    "LoadContext",
    "LoadOutcome",
    "LoadStrategy",
    "Projection",
    "SequenceReconciliation",
    "TableTarget",
    "connect",
    "driver_errors",
    "key_columns_of",
    "load_records",
    "prepare_record",
    "reconcile_transaction_id_sequence",
    "refresh_card_identity",
    "target_names",
    "target_for",
]


class AuroraLoadError(RuntimeError):
    """Raised when a bulk load cannot be performed or does not complete.

    Purpose
    -------
    Separate a load failure from a configuration fault, so an operator can tell a database
    that refused from an environment that was never able to try.

    Assumptions: every validation in this module RAISES this (or ``ValueError`` from a target's
    own ``__post_init__``) and none of them uses ``assert``. The reason is mechanical rather than
    stylistic: ``python -O`` and ``PYTHONOPTIMIZE`` strip every ``assert`` statement from the
    compiled bytecode, so an assertion is not a validation -- it is a validation that disappears
    under exactly the interpreter flag a production container is most likely to set. A dataset
    whose reader and target disagreed about its shape would then be loaded silently instead of
    refused. The container image does not currently set that flag, which is precisely why relying
    on it is unsafe: nothing in this package would fail if it were added.

    Trade-offs: this subclasses a STDLIB error rather than ``Exception`` directly, which is the
    same choice ``copybook/zoned.py`` makes for its decode errors -- though it picks
    ``ValueError``, because a bad sign nibble is a bad value, whereas a refused COPY is a failed
    operation and ``RuntimeError`` is the closer parent here. The trade either way is a broader
    parent than strictly necessary, accepted so that an existing ``except`` guard in a caller or a
    test harness keeps catching the failure and introducing this type could not turn a handled
    error into an unhandled one.
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
        """Run one statement.

        Parameters
        ----------
        statement : str
            The statement to run. This module issues only the statements the stage-and-merge path
            needs: a temporary-table creation, the content-conflict probe, and one insert.

        Returns
        -------
        Any
            Whatever the driver returns; this module reads :attr:`rowcount` or calls
            :meth:`fetchone` afterwards, never both for one statement.
        """
        ...  # pragma: no cover - Protocol declaration

    def fetchone(self) -> Any:
        """Return the single row the last execution projected, or ``None``.

        Purpose
        -------
        Let the content-conflict probe read its one aggregate row. Declared on the Protocol rather
        than reached through ``getattr`` so a double that omits it fails type checking here rather
        than at run time on a load.

        Returns
        -------
        Any
            A sequence of column values, or ``None`` when the statement projected no row.
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

    Declared only where the target column is NULLABLE. ``ledger.daily_transactions.proc_ts`` is
    the case that matters: the feed is written before posting, so an unwritten stamp there is
    the normal state of a row rather than a defect in the delivery.
    """

    TIMESTAMP_REQUIRED = "timestamp_required"
    """Render the stamp the same way, and REFUSE the record when nothing has been written into it.

    The only difference from :attr:`TIMESTAMP_OR_NULL` is what an unwritten span means, and that
    is a property of the column rather than of the stamp. ``ledger.transactions.proc_ts`` is
    declared ``NOT NULL`` -- ``V1__ledger.sql`` states the evidence at the column: every baseline
    writer of that master sets the processing stamp, ``app/cbl/CBTRN02C.cbl`` minting it at
    posting time at L437-L438 and ``app/cbl/COBIL00C.cbl`` at L230-L232 -- so a row without one is
    a row no baseline path can produce.

    Assumptions: the refusal happens HERE, in the projection, rather than at the server. Passing
    ``None`` into the COPY would reach the same conclusion by way of a NOT NULL violation raised
    after the whole dataset had been streamed, in a diagnostic this module then has to strip of
    the row it quotes. Refusing on the record instead names the record, the field and the column
    and quotes nothing.

    Alternatives Considered: deriving the missing stamp -- from the originating stamp, the
    business date, or the load's own clock. Rejected: the processing stamp is the record of WHEN
    A POSTING RUN HANDLED the transaction, so a derived value is a fabricated fact about a
    financial record, indistinguishable afterwards from one the posting job wrote. It would also
    break the parity comparison the migration is verified by, which normalises stamps rather than
    inventing them (AAP 0.7.7).
    """

    SEALED_IDENTIFIER = "sealed_identifier"
    """Encipher the value into the self-describing envelope ``account.customers`` stores.

    The envelope carries the ``CDCI`` marker and a version byte ahead of its wrapped data key,
    and is additionally bound to the target column through its KMS encryption context -- so the
    column name this target declares is authenticated data rather than a label, and a value
    recovered from one protected column cannot be deciphered as the other.

    ⚠️ Refactoring Rationale:
        This docstring recorded the framing as marker-less, and
        :mod:`carddemo_migration.loaders.protected_columns` wrote it that way. Both reproduced a
        Java writer that has since been deleted: ``account-service`` framed this one column from
        two places, a private nested implementation inside
        ``CustomerIdentifierProtectionConfig`` that omitted the header and the component-scanned
        ``CustomerIdentifierCipher`` that writes it. The surviving writer is the cipher, and the
        two framings now differ ONLY in their marker and their encryption context.
    """

    SEALED_VERIFICATION_VALUE = "sealed_verification_value"
    """Encipher the value into the self-describing envelope ``card.cards.cvv_encrypted`` stores.

    The same header shape as :attr:`SEALED_IDENTIFIER` under a DIFFERENT marker -- ``CDCV`` --
    and under a purpose-only encryption context with no column entry, because a different
    service parses it and presents a different context on decrypt. The markers are what keep a
    value recovered from either column from being read as an envelope of the other kind.
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


#: The projections whose stored bytes are not a function of their source value.
#:
#: WHY : Refactoring Rationale: the pair is named ONCE here where three members used to spell the
#:   same two-element set inline -- `comparable_fields`, `content_columns` and the new
#:   `sealed_fields`. The three are complements of one another by construction, so a set that
#:   drifted in one of them would silently produce a column that is digested as comparable AND
#:   audited as sealed, or one that is neither. Deriving all three from one declaration makes that
#:   class of disagreement unrepresentable rather than merely unlikely.
_SEALING_PROJECTIONS: Final[frozenset[Projection]] = frozenset(
    {Projection.SEALED_IDENTIFIER, Projection.SEALED_VERIFICATION_VALUE}
)


class LoadStrategy(enum.Enum):
    """How a re-run of one dataset avoids adding a row the target table already holds.

    Purpose
    -------
    Name the two shapes a rerun-safe merge can take, so a target declares which one its TABLE
    supports rather than the module inferring it. Both stage the dataset in a session-temporary
    table first; they differ only in the predicate that decides whether a staged row is new.

    Assumptions: the choice is a property of the target table's indexes, not of the record. Ten
    of the eleven tables declare a primary key over exactly the columns the record's own key
    window produces, so those ten can conflict on a real unique index. The eleventh cannot, and
    that is a deliberate decision recorded in its own migration rather than an oversight to work
    around.
    """

    KEYED_MERGE = "keyed_merge"
    """Insert every staged row, letting a unique-index conflict on the derived key do nothing.

    Correct wherever the target declares a unique constraint over exactly
    :attr:`TableTarget.key_columns`. All ten keyed masters do: ``pk_accounts(account_id)``,
    ``pk_customers(customer_id)``, ``pk_card_xref(card_num)``, ``pk_cards(card_num)``,
    ``auth.users(user_id)``, ``pk_transaction_types(type_cd)``,
    ``pk_transaction_categories(type_cd, cat_cd)``, the disclosure-group triple,
    ``pk_transactions(transaction_id)`` and the category-balance triple.
    """

    WHOLE_ROW_MERGE = "whole_row_merge"
    """Insert only staged rows whose complete column tuple the target does not already hold.

    Declared for exactly one target, ``ledger.daily_transactions``, and the reason is in that
    table's own DDL: its primary key is ``ingest_seq``, an identity column no extract supplies,
    and ``transaction_id`` is deliberately left NON-unique because the sequential feed
    ``app/cbl/CBTRN02C.cbl`` reads may carry a value twice. There is therefore no unique index to
    name in an ``ON CONFLICT`` clause -- naming one anyway raises "there is no unique or exclusion
    constraint matching the ON CONFLICT specification" at run time -- and a plain COPY re-run
    would succeed and double the feed.

    Assumptions: comparing the WHOLE tuple is what keeps a legitimate duplicate representable. Two
    identical rows in one delivery both load, because the anti-join is evaluated against the
    table as the statement found it and neither row is visible to the other; a re-run of the same
    delivery then matches both and inserts nothing. Comparing on ``transaction_id`` alone would
    instead discard the second occurrence on the FIRST load, which is the property
    ``V1__ledger.sql`` refuses to lose.

    Trade-offs: the predicate is an anti-join over every copied column rather than an index
    probe, so it costs a hash of the target table per load instead of one lookup per row. That is
    accepted because the alternative is not a cheaper correct answer, it is the absence of one:
    without a unique index there is nothing to probe.
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
    Report both numbers, because they differ on a re-run and the difference is the whole point: a
    load that staged 7 rows and inserted 0 means every one of them was already present -- an
    earlier attempt, or the owning service's own seed migration, had got there first -- which is a
    success, while a load reporting 7 inserted means the table gained 7. Collapsing them into one
    number would make those two outcomes indistinguishable, and an operator re-running a failed
    staging branch needs to tell them apart.

    Parameters
    ----------
    staged : int
        Rows the reader produced and this module accepted.
    inserted : int
        Rows the target table gained, read from the merge's own affected-row count. Equal to
        :attr:`staged` only when the table held none of the staged rows beforehand.

    Raises
    ------
    None
    """

    staged: int
    inserted: int

    @property
    def skipped(self) -> int:
        """Report how many staged rows the target already held IDENTICALLY.

        Returns
        -------
        int
            The difference between the two counts, which is zero for a load into a table that
            held none of the staged rows.

        Raises
        ------
        None
        """
        # WHY : Refactoring Rationale: this count used to mean "the table already held these rows"
        #   on the strength of the merge having skipped them, and the merge skips a key WITHOUT
        #   reading it -- so a staged row disagreeing with the stored one was counted here and the
        #   load returned success. `_conflicting_content` now runs before the merge and refuses that
        #   case, which is what makes the word "identically" above true rather than assumed. The
        #   one difference it cannot see is inside a sealed column, and `content_columns` records
        #   why.
        return self.staged - self.inserted

    def describe(self) -> str:
        """Render one operator-readable line stating what the load did.

        Returns
        -------
        str
            A sentence naming both counts, and the skipped count only when it is non-zero so
            that a first load's line does not carry a number that is always zero.

        Raises
        ------
        None
        """
        # WHY : Refactoring Rationale: there is no longer a DECLINED rendering, and there was one.
        #   Two independently authored resolutions of the same restart-safety defect met here. One
        #   kept a direct-COPY path and guarded it with a row count, reporting a populated target as
        #   a load that declined to run; the other withdrew the direct path so that every one of the
        #   eleven targets stages and then merges. The second subsumes the first: a re-run of a
        #   completed load now stages every row and inserts none, which is the skipped line below,
        #   and it is safe for the identity-keyed daily feed as well -- the case a count-based
        #   precondition could only decline, never de-duplicate.
        if self.skipped:
            return (
                f"{self.staged} row(s) read, {self.inserted} inserted,"
                f" {self.skipped} already present"
            )
        return f"{self.staged} row(s) read, {self.inserted} inserted"


def key_columns_of(record: str, columns: Mapping[str, str]) -> tuple[str, ...]:
    """Derive the target columns a record's primary key occupies, from its descriptor alone.

    Purpose
    -------
    Translate the ``key_offset``/``key_length`` window
    :mod:`carddemo_migration.copybook.layouts` carries for a record into the target column names
    that window covers, so a merge conflicts on the record's real key and no key geometry is
    written down a second time.

    Parameters
    ----------
    record : str
        A registered record name, as the layout registry spells it -- ``ACCOUNT``, ``SECUSER``
        and so on. The comparison is exact and case-sensitive.
    columns : Mapping[str, str]
        A target's copybook-field-to-column mapping, used to translate each key field into the
        column it becomes and to prove that every key field is loaded at all.

    Returns
    -------
    tuple[str, ...]
        The target column names of the fields the key window covers, in the descriptor's own
        field order. Never empty: a record whose key resolves to no column is refused instead.

    Raises
    ------
    ValueError
        If the record is not registered, if the key window does not begin and end exactly on a
        field boundary, or if a field inside the window is not mapped to a column.
    """
    # WHY : Assumptions: an UNREGISTERED record is refused rather than answered with an empty
    #   tuple, because an empty tuple is what a keyless target looks like and the two must not be
    #   confused. `layouts.layout` raises `LayoutError`, which is a `ValueError` subclass; it is
    #   re-raised as a plain `ValueError` naming this derivation so that a target declaration
    #   failing at import says which of its own fields was wrong rather than only which registry
    #   lookup missed.
    try:
        spec = layouts.layout(record)
    except layouts.LayoutError as exc:
        raise ValueError(
            f"record {record!r} is not a registered layout, so no key geometry can be derived"
            f" for it: {exc}"
        ) from exc

    window_end = spec.key_offset + spec.key_length
    covered = tuple(
        field
        for field in spec.fields
        if field.start >= spec.key_offset and field.start + field.length <= window_end
    )
    # WHY : Assumptions: the window is validated against the FIELD BOUNDARIES rather than
    #   trusted, and containment is tested rather than overlap. The two differ exactly where a key
    #   ends mid-field, and that difference is the whole point: overlap would quietly return the
    #   straddling field's whole column, so a key declared one byte short would produce a
    #   syntactically valid merge over a wider column set than the index it names. Containment
    #   plus the three checks below turn the same mistake into a refusal at import. All eleven
    #   registered masters key on whole leading fields -- `KEYS(11 0)`, `KEYS(16 0)`, `KEYS(17 0)`
    #   and their siblings in the ten REPRO jobs -- so the strict form costs nothing today and is
    #   the reason a future record with a partial-field key cannot be loaded by accident.
    if not covered or covered[0].start != spec.key_offset:
        raise ValueError(
            f"record {record} declares a key at offset {spec.key_offset}, which is not the start"
            f" of a declared field, so the columns it covers cannot be derived"
        )
    if covered[-1].start + covered[-1].length != window_end:
        raise ValueError(
            f"record {record} declares a {spec.key_length}-byte key at offset"
            f" {spec.key_offset}, which ends at {window_end} rather than on a field boundary, so"
            f" the columns it covers cannot be derived"
        )
    unmapped = [field.name for field in covered if field.name not in columns]
    if unmapped:
        raise ValueError(
            f"record {record} keys on {', '.join(unmapped)}, which the target does not map to a"
            " column, so a merge could not name the key it conflicts on"
        )
    return tuple(columns[field.name] for field in covered)


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
    record : str
        The registered record name this target loads, which binds it to the descriptor its key
        geometry is derived from. Empty ONLY for a target constructed to exercise a projection or
        an identifier rendering rather than to load a dataset; such a target has no key columns
        and refuses to compose a merge statement, so the absence cannot pass for a key.
    projections : Mapping[str, Projection]
        How each named field's value becomes its column's value. A field absent from this
        mapping projects :attr:`Projection.VERBATIM`, so only the exceptions are declared.
    strategy : LoadStrategy
        Which rerun-safe merge the target's own indexes support. Defaults to
        :attr:`LoadStrategy.KEYED_MERGE`, which every target whose table declares a unique
        constraint over :attr:`key_columns` uses.
    derived_fields : frozenset[str]
        Keys of ``columns`` that no reader publishes because the value is derived rather than
        decoded. Declared so that the missing-field check does not demand them of a record and
        so a test can assert the set rather than infer it.
    key_columns : tuple[str, ...]
        DERIVED, never passed: the target column names the record's primary-key window covers, as
        :func:`key_columns_of` computes them. Empty only for an unbound target.

    Raises
    ------
    ValueError
        If the schema, table or mapping is empty, if a projection or a derived field names a
        field the mapping does not carry, if the bound record is unregistered, or if the record's
        key window does not resolve to mapped columns on field boundaries.
    """

    schema: str
    table: str
    columns: Mapping[str, str]
    record: str = ""
    projections: Mapping[str, Projection] = field(default_factory=lambda: MappingProxyType({}))
    strategy: LoadStrategy = LoadStrategy.KEYED_MERGE
    derived_fields: frozenset[str] = frozenset()
    # WHY : Assumptions: the key columns are `init=False`, so no caller can pass them and every
    #   target's key is the descriptor's. A default of `()` is required for the field to be
    #   declarable at all, and it is overwritten in `__post_init__` for every bound target; the
    #   only target it survives on is an unbound one, which has no merge path to use it.
    key_columns: tuple[str, ...] = field(init=False, default=())

    def __post_init__(self) -> None:
        """Refuse a target whose declarations disagree with each other, and derive its key.

        Returns
        -------
        None
            Nothing. A dataclass initialiser hook is called for its validation and derivation
            effect, and returning normally is what signals that this target is usable; the only
            other outcome is the exception below.

        Raises
        ------
        ValueError
            If any component is empty, if a projection or derived field names something the
            column mapping does not, if the bound record is unregistered, or if its key window
            does not resolve to mapped columns on field boundaries.
        """
        if not self.schema.strip() or not self.table.strip():
            raise ValueError("a table target must name both a schema and a table")
        if not self.columns:
            raise ValueError(
                f"target {self.schema}.{self.table} maps no column, so a load would insert"
                " nothing; a target with no mapping is a declaration error rather than an"
                " empty load"
            )
        # Assumptions: the two supplementary declarations are checked against the column
        #   mapping HERE, at import time, rather than at the point each is used. Both fail
        #   silently otherwise: a projection keyed on a misspelled field name would simply never
        #   apply, so a money column would load padded text or a protected column would load
        #   PLAINTEXT; and a derived field not in the mapping would exempt nothing.
        # WHY : Assumptions: these two checks run BEFORE the key derivation below, and the order
        #   is load-bearing for the diagnostic rather than for correctness. A target whose
        #   projection names a misspelled field is usually a partial declaration, which the key
        #   derivation would also reject -- for its own, unrelated reason -- so deriving first
        #   would answer a misspelling with a message about a key.
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
        if self.record:
            # WHY : Assumptions: the derivation runs at CONSTRUCTION, so a target bound to a
            #   record whose key window it does not map fails at import rather than on the merge
            #   statement of a load that has already streamed the whole dataset into a staging
            #   table. `object.__setattr__` is the documented way to complete a frozen dataclass's
            #   derived state from its own initialiser hook; the field is `init=False`, so this is
            #   the only place it is ever written.
            object.__setattr__(self, "key_columns", key_columns_of(self.record, self.columns))

    @property
    def qualified_name(self) -> str:
        """Report the safely-quoted, schema-qualified table name.

        Returns
        -------
        str
            The identifier pair, each quoted independently.
        """
        # WHY : Alternatives Considered: setting the session `search_path` to the target's schema
        #   and naming the table bare. Rejected because the search path is session state that the
        #   connecting role's own default can already have set, so an unqualified name could
        #   resolve to a table in a different schema and the load would report success against
        #   something it never meant to write. `sql/verify/row_counts.sql` refuses unqualified
        #   names for exactly this reason, so qualifying here keeps the load and the pass that
        #   checks it addressing tables the same way. The cost is one prefix per statement.
        # WHY : Assumptions: BOTH halves go through `config.quote_identifier` rather than being
        #   interpolated bare, and the schema half is why it is not optional. `V0__schemas_and_roles
        #   .sql` creates a schema named `authorization`, which is a RESERVED WORD in PostgreSQL:
        #   written bare it is parsed as the keyword and the statement is a syntax error rather
        #   than a wrong-table load. No target below names that schema -- this module writes five,
        #   and `"authorization"` is not one of them -- but the accessor is applied uniformly so
        #   that no code path here is capable of emitting the bare word, and so that adding a
        #   target for it later cannot introduce the fault. Quoting the table half costs nothing
        #   and removes the same class of error for any future table name.
        return f"{quote_identifier(self.schema)}.{quote_identifier(self.table)}"

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
            # WHY : Trade-offs: the FIELD NAMES are reported and no value is. A decoded record of
            #   any of these datasets carries primary account numbers and national identifiers, and
            #   a diagnostic is retained and readable by every holder of log access. Naming the
            #   field, its target and its table is enough to locate the disagreement between a
            #   reader and a target; the value would add nothing to that diagnosis and would put a
            #   card number or a complete identity in a log line. Field name, offset, length and
            #   kind are all recoverable from `copybook.layouts` for anyone who needs them, which
            #   is why this message does not carry them either.
            raise AuroraLoadError(
                f"a record bound for {self.schema}.{self.table} is missing the mapped field(s)"
                f" {', '.join(missing)}; the reader and the target disagree about the record's"
                " shape, so nothing is loaded"
            )
        # WHY : Trade-offs: the projection is driven by `self.columns`, so a field the mapping does
        #   not name is DROPPED here rather than being carried into the COPY. `FILLER` is the field
        #   this matters for: every 350-, 300- and 80-byte record ends in padding that exists only
        #   to reach the declared record length, and no target maps it. The trade is that
        #   `copybook.layouts` deliberately RETAINS `FILLER` in its descriptors while this boundary
        #   discards it -- two statements about the same field that look contradictory and are not.
        #   Keeping it in the descriptor is what makes the sum-of-widths-equals-reclen invariant
        #   expressible and machine-checkable, which is how a mis-transcribed offset is caught at
        #   all; writing it to the database would store blanks in a column no service reads. Note
        #   that the drop is by ABSENCE FROM THE MAPPING, not by matching the token `FILLER`: the
        #   security record's padding is NAMED `SEC-USR-FILLER`, so a substring predicate would be
        #   the fragile way to express this and an exact-token predicate would miss that one.
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
        return tuple(
            name
            for name in self.columns
            if self.projections.get(name, Projection.VERBATIM) not in _SEALING_PROJECTIONS
        )

    def sealed_fields(self) -> Mapping[str, str]:
        """Map each field this target seals to the column its envelope is stored in.

        Purpose
        -------
        Name the exact complement of :meth:`comparable_fields`, so the verification pass that
        certifies a sealed column by presence and framing can find those columns without
        re-deriving which projections seal. Excluding them from the digest is correct and was
        argued at :meth:`comparable_fields`; leaving them certified by NOTHING was not, and a load
        that silently dropped every stored identifier would have been reported as verified.

        Returns
        -------
        Mapping[str, str]
            Copybook field name to stored column name, in the target's own mapping order. Empty
            for the nine loadable targets that seal nothing, which is the common case.

        Raises
        ------
        None
        """
        # WHY : Assumptions: the returned mapping is keyed by FIELD and valued by COLUMN, because
        #   the audit needs both halves and they are read from different sides. The expected count
        #   comes from the source record, which is keyed by field name; the stored count comes from
        #   a read of the target, which is keyed by column name. Returning only one of the two
        #   would push the other lookup onto every caller.
        return MappingProxyType(
            {
                field: column
                for field, column in self.columns.items()
                if self.projections.get(field, Projection.VERBATIM) in _SEALING_PROJECTIONS
            }
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

    def count_statement(self) -> str:
        """Compose the statement that counts the rows this target's table already holds.

        Purpose
        -------
        Give the direct-COPY path a precondition it can evaluate before it writes anything, so a
        load into an already-populated single-writer master reports what is there instead of
        appending to it or failing on a key it cannot explain.

        Returns
        -------
        str
            A ``SELECT count(*) FROM <table>`` statement against the schema-qualified name.

        Raises
        ------
        None
        """
        # WHY : Alternatives Considered: `SELECT 1 FROM <table> LIMIT 1`, which answers the
        #   emptiness question at a fraction of the cost because it stops at the first row.
        #   Rejected because the COUNT is the number an operator needs: the decision a declined
        #   load hands back is whether what is already there is the extract they meant to load,
        #   and that decision is made by comparing the count against the extract's -- which is
        #   exactly what `sql/verify/row_counts.sql` compares. A bare "not empty" would send them
        #   to run that pass by hand to learn the number this statement already has.
        # WHY : Trade-offs: on a large master this is a sequential scan, which is the cost paid
        #   once per load attempt and is negligible beside the COPY it guards -- and it is not paid
        #   at all on the path that matters for throughput, since a first load of an empty table
        #   scans nothing.
        # WHY : Assumptions: the name goes through `qualified_name`, so the schema is explicit and
        #   both halves are quoted, for the reasons recorded there. A count against an
        #   unqualified name could count a different schema's table and would then decline a load
        #   that should have run.
        return f"SELECT count(*) FROM {self.qualified_name}"

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
        # Assumptions: the column list comes from the same mapping the staging table and the merge
        #   read, so the three statements cannot disagree about either the set of columns or their
        #   order. Writing the list out three times would be three chances to reorder one of them,
        #   and a reordered COPY loads plausible values into wrong columns.
        names = ", ".join(quote_identifier(column) for column in self.columns.values())
        return f"COPY {self.stage_name} ({names}) FROM STDIN"

    def merge_statement(self) -> str:
        """Compose the statement that moves staged rows into the target without duplicating one.

        Purpose
        -------
        Insert every staged row the target does not already hold, so that a load composing with a
        second writer -- or with an earlier attempt at itself -- neither fails nor duplicates.

        Returns
        -------
        str
            An ``INSERT ... SELECT`` statement, closed by ``ON CONFLICT (<key>) DO NOTHING`` under
            :attr:`LoadStrategy.KEYED_MERGE` and by a ``WHERE NOT EXISTS`` anti-join under
            :attr:`LoadStrategy.WHOLE_ROW_MERGE`.

        Raises
        ------
        AuroraLoadError
            If the target is not bound to a record, so no key columns could be derived and this
            path was reached for a construct that was never meant to load a dataset.
        """
        names = ", ".join(quote_identifier(column) for column in self.columns.values())
        if self.strategy is LoadStrategy.WHOLE_ROW_MERGE:
            # WHY : Assumptions: `IS NOT DISTINCT FROM` rather than `=` on every column, because
            #   two of the columns this target copies are NULLABLE -- the daily feed's two stamps --
            #   and `NULL = NULL` is unknown rather than true. With `=` the anti-join would find no
            #   match for any row carrying an unwritten stamp, so a re-run of the feed would insert
            #   every one of those rows again: the exact duplication this path exists to prevent,
            #   reintroduced by the one operator that reads as obviously correct.
            predicate = " AND ".join(
                f"{_MERGE_TARGET_ALIAS}.{quote_identifier(column)} IS NOT DISTINCT FROM"
                f" {_MERGE_STAGE_ALIAS}.{quote_identifier(column)}"
                for column in self.columns.values()
            )
            staged = ", ".join(
                f"{_MERGE_STAGE_ALIAS}.{quote_identifier(column)}"
                for column in self.columns.values()
            )
            return (
                f"INSERT INTO {self.qualified_name} ({names})"
                f" SELECT {staged} FROM {self.stage_name} AS {_MERGE_STAGE_ALIAS}"
                f" WHERE NOT EXISTS (SELECT 1 FROM {self.qualified_name}"
                f" AS {_MERGE_TARGET_ALIAS} WHERE {predicate})"
            )
        if not self.key_columns:
            raise AuroraLoadError(
                f"target {self.schema}.{self.table} is bound to no record, so its key columns"
                " could not be derived and it has no merge statement; every declared load target"
                " names the record it loads"
            )
        # WHY : Trade-offs: DO NOTHING rather than DO UPDATE, and the choice is what makes two
        #   writers genuinely compose rather than merely coexist. `V2__seed_reference.sql`
        #   uses DO NOTHING on all six of its inserts, so with DO NOTHING here the outcome is the
        #   same set of rows whichever writer runs first, whichever runs second, and however many
        #   times either runs. DO UPDATE would instead make the stored description depend on
        #   execution order -- and since this loader now trims its descriptions to match the
        #   migration's exactly, an update would be writing identical values over identical values
        #   while producing a different row version and a different affected-row count.
        # WHY : Assumptions: the conflict target is the DERIVED key, so this clause names the
        #   columns the record's own `KEYS(len off)` window covers and nothing else. Each of the
        #   ten tables taking this path declares a unique constraint over exactly those columns,
        #   which is what PostgreSQL requires of an `ON CONFLICT` target; a key that named more or
        #   fewer columns would raise "there is no unique or exclusion constraint matching the ON
        #   CONFLICT specification" at run time, after the dataset had been staged.
        key = ", ".join(quote_identifier(column) for column in self.key_columns)
        return (
            f"INSERT INTO {self.qualified_name} ({names})"
            f" SELECT {names} FROM {self.stage_name}"
            f" ON CONFLICT ({key}) DO NOTHING"
        )

    def delivery_lock_statement(self) -> str:
        """Compose the statement that serialises this target's merge against a second delivery.

        Purpose
        -------
        Give :attr:`LoadStrategy.WHOLE_ROW_MERGE` the mutual exclusion its predicate cannot get from
        an index. Its merge decides what is new by an anti-join against the target as the statement
        finds it, so two deliveries that both reach that anti-join before either commits each find
        the table empty of their rows and each insert all of them: two concurrent loads of the daily
        feed reported ``300 inserted`` twice and left 600 rows, both exiting zero. A keyed target
        cannot reach that state -- its unique index makes the second writer block and then do
        nothing -- which is exactly why this one needs an explicit lock and the other ten do not.

        Returns
        -------
        str
            A ``SELECT pg_advisory_xact_lock(<namespace>, <table digest>)`` statement. Both keys are
            integer literals derived here, so the statement carries no bound parameter and no
            caller-supplied text.

        Raises
        ------
        None
            The keys are derived from this target's own schema and table names, which every declared
            target carries.
        """
        # WHY : Alternatives Considered: `pg_advisory_xact_lock` rather than `pg_advisory_lock`, so
        #   the lock is released by the COMMIT or the ROLLBACK that ends this load's transaction and
        #   there is no path on which a failed load leaves a session-held lock behind for the next
        #   one to wait on forever. A session lock would have to be released explicitly, and the one
        #   place that release could be missed is the failure path -- which is the path a load takes
        #   when something has already gone wrong.
        # WHY : Assumptions: the keys are composed as LITERALS rather than bound as parameters,
        #   because the `_Cursor` protocol this module declares takes a statement and nothing else,
        #   and every other statement here is composed the same way. There is no injection surface:
        #   both values are integers this module derives from its own declarations.
        # WHY : Assumptions: `to_regclass('<schema>.<table>')::oid` was the alternative for the
        #   second key and is rejected on one specific behaviour: `to_regclass` answers NULL for a
        #   relation it cannot resolve, and `pg_advisory_xact_lock(NULL)` returns NULL having taken
        #   NO lock. A serialisation that silently does not serialise is worse than none at all,
        #   because it looks correct in the statement log.
        digest = hashlib.blake2s(
            f"{self.schema}.{self.table}".encode(), digest_size=_DELIVERY_LOCK_DIGEST_BYTES
        ).digest()
        key = int.from_bytes(digest, "big", signed=True)
        return f"SELECT pg_advisory_xact_lock({_DELIVERY_LOCK_NAMESPACE}, {key})"

    def key_of(
        self, record: Mapping[str, str | int | Decimal | bytes]
    ) -> tuple[object, ...] | None:
        """Project one prepared record into the values its target key columns receive.

        Purpose
        -------
        Let a load recognise that ONE delivery carries the same business key twice, before the merge
        silently collapses the repeat. ``ON CONFLICT ... DO NOTHING`` cannot tell a repeat inside
        the delivery from a row the table already held, so a 50-record extract whose second record
        repeated the first record's key reported ``49 inserted, 1 already present`` and exited zero
        while the record it displaced was simply absent.

        Parameters
        ----------
        record : Mapping[str, str | int | Decimal | bytes]
            One record already projected by :func:`prepare_record`, keyed by copybook field name.
            The PREPARED form is used rather than the decoded one because it is what is staged, so
            the values compared here are the values the merge would conflict on.

        Returns
        -------
        tuple[object, ...] | None
            The key values in the mapping's own field order, or ``None`` for a target bound to no
            record, which therefore has no derived key to project.

        Raises
        ------
        AuroraLoadError
            If the record does not carry a field one of the key columns is mapped from, which means
            the reader and the target disagree about the record's shape.
        """
        # WHY : Assumptions: whether a repeated key is a DEFECT is decided by the caller from the
        #   target's STRATEGY, not here, and the distinction matters because a key window is
        #   derived for every record including the daily feed's. `ledger.daily_transactions`
        #   declares `DALYTRAN-ID` as its record key and its TABLE deliberately does not: the
        #   primary key is an identity column and `transaction_id` carries no unique index,
        #   because `app/cbl/CBTRN02C.cbl` reads the feed front to back and a repeated
        #   identifier in one delivery is a second physical occurrence the baseline posts
        #   twice. So this method answers the projection for any bound target and
        #   `load_records` asks it only where the table asserts uniqueness.
        # WHY : Assumptions: an unbound target answers None rather than an empty tuple, because an
        #   empty tuple compares equal to itself for every row and would make the second record of
        #   any such delivery a duplicate.
        if not self.key_columns:
            return None
        keys = set(self.key_columns)
        fields = tuple(field for field, column in self.columns.items() if column in keys)
        missing = [field for field in fields if field not in record]
        if missing:
            # WHY : Assumptions: the FIELD NAMES are reported and no value is, for the reason
            #   `row_of` records at length: a decoded record of these datasets carries primary
            #   account numbers and national identifiers, and this diagnostic is retained.
            raise AuroraLoadError(
                f"a record bound for {self.schema}.{self.table} is missing the key field(s)"
                f" {', '.join(missing)}, so its delivery cannot be checked for a repeated key;"
                " the reader and the target disagree about the record's shape"
            )
        return tuple(record[field] for field in fields)

    def content_columns(self) -> tuple[str, ...]:
        """List the columns whose disagreement between a staged and a stored row is real.

        Purpose
        -------
        Name the columns a content comparison may read, so that the conflict probe compares only
        values that are a DETERMINISTIC function of the source record.

        Returns
        -------
        tuple[str, ...]
            Every mapped target column that is neither a key column nor the product of a sealing
            projection, in the mapping's own order.

        Raises
        ------
        None
        """
        # WHY : Assumptions: the KEY columns are excluded because the probe joins on them -- they
        #   are equal by construction in every row it examines, so including them would add
        #   predicates that can never fire.
        # WHY : Assumptions: a SEALED column is excluded for the reason `comparable_fields`
        #   records, and here the consequence is sharper. An envelope draws a fresh initialisation
        #   vector per value, so the same card verification value enciphered twice is different
        #   bytes: a probe that read `cvv_encrypted` would report a conflict on EVERY row of every
        #   re-run of the card and customer loads, and the refusal it produced would be
        #   indistinguishable from a genuine content disagreement. The cost is real and is stated
        #   rather than hidden: a stored row whose only difference from the extract is inside a
        #   sealed column is accepted as already present. Nothing in this package can detect that
        #   difference, because detecting it would require decrypting, which this package
        #   deliberately cannot do.
        keys = set(self.key_columns)
        return tuple(
            column
            for field, column in self.columns.items()
            if column not in keys
            and self.projections.get(field, Projection.VERBATIM) not in _SEALING_PROJECTIONS
        )

    def conflict_statement(self) -> str:
        """Compose the statement that finds staged rows the table already holds DIFFERENTLY.

        Purpose
        -------
        Give the load a way to tell the two things ``ON CONFLICT ... DO NOTHING`` cannot tell
        apart: a staged row the table already holds exactly, which a re-run may skip, and a staged
        row whose key the table holds against different content, which no load may skip silently.

        Returns
        -------
        str
            A statement projecting exactly one row: the number of staged rows whose key is present
            with differing content, followed by one count per column of
            :meth:`content_columns` -- in that order -- giving how many of those rows differ in
            that column. The columns are read POSITIONALLY rather than by alias, so no identifier
            of this module's own invention reaches the statement.

        Raises
        ------
        AuroraLoadError
            If the target declares no key columns, or declares no comparable content column, so
            the question this statement asks is not expressible for it. Both are refused rather
            than answered with an empty statement, because a caller that received one would
            conclude there was no conflict.
        """
        if not self.key_columns:
            raise AuroraLoadError(
                f"target {self.schema}.{self.table} is bound to no record, so its key columns"
                " could not be derived and a same-key content comparison is not expressible"
                " for it"
            )
        content = self.content_columns()
        if not content:
            raise AuroraLoadError(
                f"target {self.schema}.{self.table} maps no column that is both outside its key"
                " and comparable, so a same-key content comparison is not expressible for it"
            )
        # WHY : Assumptions: the key join uses `=` while the whole-row anti-join in
        #   `merge_statement` uses `IS NOT DISTINCT FROM`, and the asymmetry is deliberate rather
        #   than an inconsistency. Every column joined here belongs to a unique constraint, so it
        #   is `NOT NULL` and the two operators agree -- and `=` is the form the index on that
        #   constraint can be used for, which matters because this probe runs over the whole
        #   staged dataset on every load.
        join = " AND ".join(
            f"{_MERGE_TARGET_ALIAS}.{quote_identifier(column)}"
            f" = {_MERGE_STAGE_ALIAS}.{quote_identifier(column)}"
            for column in self.key_columns
        )
        # WHY : Assumptions: the per-column predicate is `IS DISTINCT FROM`, which is the correct
        #   operator here for the mirror of the reason `=` is correct above. A content column MAY
        #   be null -- the daily feed's two stamps are, and so are several customer address lines
        #   -- and `<>` against a null yields unknown, so a row that gained or lost a value in a
        #   nullable column would not be counted as differing. That is precisely the drift this
        #   probe exists to catch.
        differs = [
            f"{_MERGE_TARGET_ALIAS}.{quote_identifier(column)}"
            f" IS DISTINCT FROM {_MERGE_STAGE_ALIAS}.{quote_identifier(column)}"
            for column in content
        ]
        # WHY : Trade-offs: one statement returning one row, rather than a query per column or a
        #   query returning the differing rows. A per-column query would multiply the join by the
        #   column count -- fifteen times over the customer master -- and a query returning rows
        #   would put cardholder values into this process, where the next mistake puts them in a
        #   log. Aggregate counts answer both questions an operator has (how many rows, which
        #   columns) and can carry no value at all.
        projections = ", ".join(f"count(*) FILTER (WHERE {predicate})" for predicate in differs)
        return (
            f"SELECT count(*), {projections}"
            f" FROM {self.stage_name} AS {_MERGE_STAGE_ALIAS}"
            f" JOIN {self.qualified_name} AS {_MERGE_TARGET_ALIAS} ON {join}"
            f" WHERE {' OR '.join(differs)}"
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

    if projection in (Projection.TIMESTAMP_OR_NULL, Projection.TIMESTAMP_REQUIRED):
        if not isinstance(value, str):
            raise AuroraLoadError(
                f"field {name} of a record bound for {target.schema}.{target.table} declares a"
                f" timestamp projection but decoded to {type(value).__name__}"
            )
        try:
            rendered = timestamp.canonical(value)
        except ValueError as exc:
            # Trade-offs: the shared renderer's message is carried through because it quotes
            #   no part of the value -- it names the width and the two admitted forms only. This
            #   wrapper adds the record and the column, which the renderer cannot know.
            raise AuroraLoadError(
                f"field {name} of a record bound for {target.schema}.{target.table} could not"
                f" be rendered for column {column}: {exc}"
            ) from exc
        if rendered is None and projection is Projection.TIMESTAMP_REQUIRED:
            # WHY : Trade-offs: the refusal names the field, the column and the fact that the span
            #   is UNWRITTEN, and quotes no part of it. There is nothing in the value worth
            #   quoting -- an unwritten stamp is 26 blanks or 26 low values, which
            #   `timestamp.is_unwritten` recognises -- and the record it sits in carries a card
            #   number, so a message echoing the record to show what was wrong would disclose one.
            raise AuroraLoadError(
                f"field {name} of a record bound for {target.schema}.{target.table} holds no"
                f" written timestamp, and column {column} is declared NOT NULL because every"
                " baseline writer of that table sets it; the delivery is incomplete rather than"
                " the row being unposted, so nothing is loaded"
            )
        return rendered

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
#   than an omission, so `target_for` can say which of the two it is. All three are written BY the
#   batch chain rather than read into it: `TRNX` is the combined transaction view, `REJECT` the
#   posting reject stream and `INTTRAN` the interest transactions the accrual run generates. None
#   is part of a load in either direction, which is why `readers/__init__.py` publishes no reader
#   for any of them, and a caller naming one gets that explanation rather than the message a
#   misspelled record name deserves.
# WHY : Refactoring Rationale: `TRAN` was a fourth member of this set and is REMOVED, because it
#   did not belong to the category the set names. The other three have no reader and no extract in
#   any encoding; the transaction master has a reader, a 350-byte layout, a registered row in
#   `sql/verify/row_counts.sql` and a REPRO job of its own at `app/jcl/TRANFILE.jcl`. What it
#   lacks is a dataset in the SEED CORPUS, which is a fact about the corpus rather than about the
#   record, and treating the two as the same fact is what left `ledger.transactions` unloadable.
_BATCH_WRITTEN_RECORDS: Final[frozenset[str]] = frozenset({"TRNX", "REJECT", "INTTRAN"})

# Assumptions: the staging table lives in the session's temporary schema and is named from the
#   target table, so two concurrent loads of DIFFERENT records cannot collide on it and a load of
#   the SAME record from two sessions still cannot, a temporary table being per-session. The
#   prefix is spelled out rather than generated, because a generated name would make the statement
#   this module issues unpredictable in a log an operator is reading to see what ran.
_STAGE_PREFIX: Final[str] = "carddemo_stage_"

# WHY : Assumptions: the anti-join's two table aliases are named here rather than inline, and they
#   are spelled in full rather than as `s` and `t`. An alias is what disambiguates the staged row
#   from the row already in the table, and the whole predicate is a column-by-column comparison
#   between the two, so a reader of the generated statement -- or of a log line carrying it -- has
#   to be able to tell at a glance which side is which. Neither spelling is a PostgreSQL reserved
#   word, and an alias cannot collide with a column name because the two occupy different
#   namespaces in a statement.
_MERGE_STAGE_ALIAS: Final[str] = "staged_row"
_MERGE_TARGET_ALIAS: Final[str] = "existing_row"

# WHY : Assumptions: the first key of every delivery lock this module takes is a FIXED literal, so
#   PostgreSQL's two-argument advisory-lock space is partitioned once: every lock this package holds
#   carries this value in `pg_locks.classid`, which is what lets an operator investigating a waiting
#   load tell a delivery lock apart from any other advisory lock the cluster is holding. The value
#   itself is arbitrary and is chosen to be recognisable rather than derived -- 0x43 0x44 are the
#   letters C and D -- because a derived namespace would have to be documented somewhere anyway and
#   a reader could not confirm it from the number in front of them.
_DELIVERY_LOCK_NAMESPACE: Final[int] = 0x43440001

# WHY : Assumptions: the second key is a DIGEST of the qualified table name, taken with blake2s at
#   four bytes and read as a signed integer, because `pg_advisory_xact_lock(int, int)` takes two
#   32-bit signed integers. Python's own `hash()` is emphatically NOT used: it is salted per process
#   for str inputs, so two concurrent loads of one table -- which run as two processes -- would
#   derive two different keys and serialise on nothing at all, which is the exact defect the lock
#   exists to close and would have been invisible in any single-process test.
# WHY : Trade-offs: a 32-bit digest can collide, and a collision means two DIFFERENT tables
#   serialise their deliveries against each other. That is accepted because the consequence is a
#   brief wait rather than a wrong answer, and because the alternative -- a registry mapping each
#   table to a hand-assigned number -- is a second declaration of the same set that a target added
#   later would silently be missing from, turning a correctness property into a maintenance one.
_DELIVERY_LOCK_DIGEST_BYTES: Final[int] = 4

# WHY : Assumptions: every mapping below was read from the owning service's own Flyway
#   migration rather than derived, and each records one anti-corruption decision. TWO of the
#   baseline's three documented misspellings are corrected here, each at its own mapping site: the
#   account and card expiration dates, both spelled `EXPIRAION` in the copybooks. The third,
#   `PA-MERCHANT-CATAGORY-CODE` becoming `merchant_category_code`, is NOT corrected here and cannot
#   be -- it belongs to the pending-authorization detail segment, whose schema this module does not
#   load at all, so the correction is made by the owning service's own mapper. All three are
#   registered in `docs/architecture/data-model-and-schema-mapping.md`, which is where the lineage
#   is recorded; naming only the two this file performs keeps that register and this comment from
#   disagreeing about which code makes which change. `FILLER` never appears in any mapping below,
#   because a reader never publishes it.
# WHY : Assumptions: ELEVEN records, which is every base master, and every one of the eleven
#   baselines `sql/verify/row_counts.sql` registers now has a target able to fill it. The twelfth
#   registered layout, the export record, is deliberately absent: `app/cpy/CVEXPORT.cpy` describes
#   an interchange file the batch chain writes and reads back, and no service declares a table for
#   it, so a target would have to invent one.
# WHY : Assumptions: FIVE of the eight schemas are written, and they own the eleven tables between
#   them -- `auth` one, `account` three, `card` one, `ledger` three, `reference` three. Counted
#   from the declarations below rather than restated from the plan, whose prose says six: the
#   eleven target tables name five distinct schemas and no sixth appears anywhere in this mapping.
#   Nothing here writes `batch`, `"authorization"` or `reporting`. The first two are filled by
#   their owning services at run time, and `V0__schemas_and_roles.sql` gives `reporting` no table
#   at all and a SELECT-only role, so a load into it could not be granted even if one were written.
TARGETS: Final[Mapping[str, TableTarget]] = MappingProxyType(
    {
        "XREF": TableTarget(
            record="XREF",
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
        # WHY : Assumptions: the three reference targets are the only ones trimming a descriptive
        #   column, and the reason is that they have a second writer whose rows theirs must MATCH
        #   rather than merely coexist with. `V2__seed_reference.sql` writes these three tables
        #   too, with `ON CONFLICT ... DO NOTHING` on each of its inserts, and it writes the
        #   descriptions TRIMMED -- `'Purchase'`, not `'Purchase'` followed by forty-two blanks.
        #   Without the trim the two writers produce rows that differ in content while agreeing in
        #   count, so the row-count pass would pass and the description a screen renders would
        #   depend on which writer ran first. The merge itself is no longer special to these three:
        #   every target merges, because a re-run is a normal event for all eleven.
        "TRANTYPE": TableTarget(
            record="TRANTYPE",
            schema="reference",
            table="transaction_types",
            columns=MappingProxyType(
                {
                    "TRAN-TYPE": "type_cd",
                    "TRAN-TYPE-DESC": "description",
                }
            ),
            projections=MappingProxyType({"TRAN-TYPE-DESC": Projection.TRIMMED}),
        ),
        "TRANCAT": TableTarget(
            record="TRANCAT",
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
        ),
        # WHY : Assumptions: the group id is NOT trimmed although the two descriptions above are,
        #   and the asymmetry follows the COLUMN rather than the value. `acct_group_id` is
        #   `CHAR(10)`, so PostgreSQL pads whatever it is given back to ten characters and the
        #   trimmed and untrimmed forms are the same stored value; a description column is
        #   `VARCHAR(50)`, where they are not. Trimming a key would also be the wrong habit to
        #   establish here: the interest calculation's `DEFAULT` fallback matches on this column.
        "DISGROUP": TableTarget(
            record="DISGROUP",
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
        ),
        "ACCOUNT": TableTarget(
            record="ACCOUNT",
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
                    # WHY : Refactoring Rationale: the source field is MISSPELLED -- `EXPIRAION`,
                    #   missing the second `T`, in app/cpy/CVACT01Y.cpy. The correction to
                    #   `expiration_date` happens here, once, at the boundary that already
                    #   translates every other name, because the copybook is reference-only and
                    #   keeps its own spelling forever. Carrying the misspelling forward into a
                    #   persisted column name was the alternative and is worse for a reason that
                    #   outlives the load: every future query, index and mapper would have to
                    #   reproduce a typo to work, and one that reads correctly would silently
                    #   return nothing. The lineage is registered in
                    #   docs/architecture/data-model-and-schema-mapping.md so the rename is
                    #   traceable rather than merely applied.
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
            record="CARD",
            schema="card",
            table="cards",
            columns=MappingProxyType(
                {
                    "CARD-NUM": "card_num",
                    "CARD-ACCT-ID": "account_id",
                    "CARD-CVV-CD": "cvv_encrypted",
                    "CARD-EMBOSSED-NAME": "embossed_name",
                    # WHY : Refactoring Rationale: the same misspelling again -- `EXPIRAION` in
                    #   app/cpy/CVACT02Y.cpy, the second of the baseline's three. Corrected to
                    #   `expiration_date` here for the same reason as the account master above, and
                    #   registered in the same place. Both are corrected at their own mapping site
                    #   rather than by one shared rule, because a rule keyed on the misspelled stem
                    #   would also rewrite any correctly-spelled field that happened to match it.
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
            record="CUSTOMER",
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
        # WHY : Assumptions: this is the ONE target that merges on the whole row rather than on the
        #   record's key, and the reason is in `V1__ledger.sql` rather than in the copybook. The
        #   descriptor keys this record on `DALYTRAN-ID`, but the TABLE does not: its primary key is
        #   `pk_daily_transactions(ingest_seq)`, an identity column no extract supplies, and
        #   `transaction_id` is left deliberately NON-unique because the sequential feed
        #   `app/cbl/CBTRN02C.cbl` reads may carry a value twice. So there is no unique index for an
        #   `ON CONFLICT` clause to name -- naming one raises at run time -- and a plain COPY re-run
        #   would succeed and DOUBLE the feed, which no count check would flag as an error.
        #   Comparing the whole copied tuple instead keeps a legitimate duplicate loadable on the
        #   first pass and inserts nothing on the second.
        "DALYTRAN": TableTarget(
            record="DALYTRAN",
            strategy=LoadStrategy.WHOLE_ROW_MERGE,
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
        # WHY : Refactoring Rationale: this target was ABSENT, and unlike the customer and card
        #   records its absence was not a refusal that a cipher answered -- it was the claim that
        #   the record has nothing to load. Three facts were cited for it and all three are true:
        #   no `TRANSACT` dataset ships under `app/data/ASCII` or `app/data/EBCDIC`,
        #   `readers/transaction.py` treats an absent source as a normal state, and
        #   `sql/verify/row_counts.sql` gives this table a NULL baseline rather than a count. None
        #   of them says the record is unloadable. They say the SEED CORPUS carries no extract for
        #   it, which is a fact about the corpus: `app/jcl/TRANFILE.jcl` is a REPRO job exactly like
        #   its nine siblings, it is named as a source of this module for that reason, and it primes
        #   the cluster at L67-L74 from a 350-byte initializer -- one record -- rather than from a
        #   master extract. Declaring no target turned "the corpus ships no rows" into "a cutover
        #   cannot move the transaction master", which is the largest table in the system and the
        #   one AAP 0.9.2's read-then-verify-then-switch sequence most needs to move. The
        #   zero-row case is preserved rather than traded away: an empty extract streams no rows,
        #   commits, and reports `staged=0 inserted=0`, so a corpus-only run behaves exactly as it
        #   did while a real extract now has somewhere to go.
        # WHY : Assumptions: this table has a SECOND WRITER, which is why a merge here is not only
        #   about re-runs. `app/cbl/CBTRN02C.cbl` posts from the daily feed into this master as part
        #   of its three-write unit of work, so by the time a load is re-run the posting job may
        #   already have inserted rows carrying these keys, and a load that aborted on them would
        #   misreport a correctly-posted row as a load fault.
        # WHY : Assumptions: the merge conflicts on `transaction_id` and this target says so
        #   NOWHERE -- the column is derived from `LAYOUTS["TRAN"]`, which carries `key_length=16`
        #   at `key_offset=0`, spanning `TRAN-ID` exactly, and is the same `KEYS(16 0)` that
        #   `app/jcl/TRANFILE.jcl` gives the cluster. `V1__ledger.sql` names the matching constraint
        #   `pk_transactions PRIMARY KEY (transaction_id)`, so the merge conflicts on a real unique
        #   index. The derivation is what keeps this from becoming a second place the VSAM key
        #   length is written down and can drift.
        # WHY : Assumptions: the money column is `amount`, taken from `V1__ledger.sql`, and NOT the
        #   `tran_amt` the migration plan's prose names. The shipped migration and
        #   `sql/verify/money_totals.sql` agree on `amount` for both ledger tables, and the
        #   verification pass aggregates the column it names -- so a target declaring the plan's
        #   spelling would fail on the COPY, and the money-parity check would have no column to
        #   total. `TRAN-AMT` decodes to an exact `Decimal` and lands in `NUMERIC(11,2)` with no
        #   float on the path.
        "TRAN": TableTarget(
            record="TRAN",
            schema="ledger",
            table="transactions",
            columns=MappingProxyType(
                {
                    "TRAN-ID": "transaction_id",
                    "TRAN-TYPE-CD": "type_cd",
                    "TRAN-CAT-CD": "category_cd",
                    "TRAN-SOURCE": "source",
                    "TRAN-DESC": "description",
                    "TRAN-AMT": "amount",
                    "TRAN-MERCHANT-ID": "merchant_id",
                    "TRAN-MERCHANT-NAME": "merchant_name",
                    "TRAN-MERCHANT-CITY": "merchant_city",
                    "TRAN-MERCHANT-ZIP": "merchant_zip",
                    "TRAN-CARD-NUM": "card_num",
                    "TRAN-ORIG-TS": "orig_ts",
                    "TRAN-PROC-TS": "proc_ts",
                }
            ),
            # WHY : Trade-offs: the three descriptive columns are trimmed and both stamps are
            #   rendered, matching the `DALYTRAN` target field for field because the two layouts
            #   are field for field identical -- same offsets, same kinds, same 350 bytes. Keeping
            #   the two declarations parallel is what lets the posting parity comparison put a
            #   daily row beside the transaction row it became and diff them, which a different
            #   trimming or a different stamp spelling on either side would defeat.
            # WHY : Refactoring Rationale: the PROCESSING stamp projects
            #   `TIMESTAMP_REQUIRED` where it previously projected `TIMESTAMP_OR_NULL`, and the two
            #   stamps of this one target therefore differ from each other. This is the one place
            #   the parallel with `DALYTRAN` is deliberately broken, and the asymmetry is the
            #   TABLES': `ledger.transactions.proc_ts` is declared NOT NULL while
            #   `ledger.daily_transactions.proc_ts` is nullable, each with the evidence recorded at
            #   the column in `V1__ledger.sql`. Under the previous declaration an unwritten stamp
            #   became `None` and the COPY hit that NOT NULL constraint at the server -- so the
            #   only committed transaction-shaped rows in the repository, the five in
            #   `tests/fixtures/export/happy_path/trandata.txt` whose processing stamps are 26
            #   blanks, would have failed the load mid-stream with a server diagnostic quoting the
            #   row. It now fails on the record instead, naming the field and the column, before
            #   anything is written.
            projections=MappingProxyType(
                {
                    "TRAN-DESC": Projection.TRIMMED,
                    "TRAN-MERCHANT-NAME": Projection.TRIMMED,
                    "TRAN-MERCHANT-CITY": Projection.TRIMMED,
                    "TRAN-ORIG-TS": Projection.TIMESTAMP_OR_NULL,
                    "TRAN-PROC-TS": Projection.TIMESTAMP_REQUIRED,
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
            record="TCATBAL",
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
            record="SECUSER",
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

# WHY : Assumptions: the binding is proven at IMPORT rather than only in the test suite, and it is
#   proven in both directions -- each target names the record it is filed under, and each has
#   derived a non-empty key from that record's descriptor. Both halves are needed. A target whose
#   `record` disagreed with its key would derive another record's key geometry and merge on the
#   wrong columns, which is the exact drift the derivation exists to remove; and a target left
#   unbound would derive no key at all, which is a state the class permits for a projection-only
#   construct and must never reach this registry, because its merge would be refused only once a
#   load had already streamed the dataset into a staging table.
# WHY : Trade-offs: the failure is a `ValueError` raised from this module's own import, which is
#   blunt -- it stops every load rather than the one that is wrong. That is the correct bluntness:
#   a mis-bound target means the module's own declarations disagree with the copybook registry, and
#   there is no dataset it would then be safe to load.
_MISBOUND: Final[tuple[str, ...]] = tuple(
    name
    for name, declared in TARGETS.items()
    if declared.record != name or not declared.key_columns
)
if _MISBOUND:
    raise ValueError(
        "every load target must name the record it is filed under and derive a key from that"
        f" record's descriptor, but {', '.join(_MISBOUND)} does not"
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
        If the record has no declared target. A layout the batch chain WRITES is named
        explicitly, because "no target" and "no target because nothing ever reads this layout
        in" send the next reader to entirely different places.
    """
    try:
        return TARGETS[record_name]
    except KeyError as exc:
        # WHY : Refactoring Rationale: this branch used to name CUSTOMER, CARD and TRAN as
        #   deliberately unloadable. CUSTOMER and CARD were refused "because its table declares
        #   protected columns holding ciphertext ... under a key this package does not hold"; both
        #   now load through `loaders/protected_columns.py`. TRAN was refused on the ground that no
        #   extract ships for it, which described the seed corpus rather than the record and left
        #   the transaction master with no migration path. All three refusals are withdrawn rather
        #   than reworded, because each named an obstacle that no longer exists. What remains are
        #   the three layouts the batch chain writes and nothing reads in, for which there is no
        #   load direction to declare a target for.
        if record_name in _BATCH_WRITTEN_RECORDS:
            raise AuroraLoadError(
                f"record {record_name} has no load target because it is written BY the batch"
                " chain rather than loaded into it: it is a derived layout with no reader and no"
                " extract in either encoding, so there is no load direction for it"
            ) from exc
        raise AuroraLoadError(
            f"record {record_name} has no declared load target; the loadable records are"
            f" {', '.join(target_names())}"
        ) from exc


def driver_errors() -> tuple[type[BaseException], ...]:
    """Return the database driver's own exception types, so a caller can classify one.

    Purpose
    -------
    Let a command translate a query failure into its own documented exit tier without importing
    the driver -- which no module outside this one and ``credentials`` is permitted to do -- and
    without catching every exception to find one.

    Parameters
    ----------
    None
        The driver is resolved by import, from the same environment every other call in this
        module resolves it from.

    Returns
    -------
    tuple[type[BaseException], ...]
        ``(psycopg.Error,)`` when the driver is installed, and an EMPTY tuple when it is not.

    Raises
    ------
    None
        An absent driver is answered with an empty tuple rather than raised for. A caller
        catching an empty tuple catches nothing, which is correct: with no driver installed there
        is no connection to have failed, and the absence itself surfaces as the
        ``ConfigurationError`` :func:`connect` raises.
    """
    # WHY : Alternatives Considered: the alternative was for each caller to catch `Exception`
    #   around every query it issues, which is what the verification handlers effectively had to do
    #   -- and it cannot distinguish a driver failure from a programming error, so a mis-spelled
    #   attribute in a comparison would have been reported to an operator as a failed database step.
    #   Publishing the driver's own base class keeps that distinction and keeps the driver import
    #   inside the two modules that already own it.
    # WHY : Assumptions: `psycopg.Error` is the ONE type named, because the driver documents it as
    #   the base of every exception it raises -- interface, database, data, operational, integrity,
    #   internal, programming and not-supported errors all derive from it. Naming the subclasses
    #   would be a list to keep in step with a dependency for no gain.
    try:
        import psycopg
    except ImportError:  # pragma: no cover - exercised only on an incomplete install
        return ()
    return (psycopg.Error,)


def connect(settings: AuroraConnectionSettings, *, expected_role: str | None = None) -> Any:
    """Open a database connection from resolved settings, optionally proving the login role.

    Purpose
    -------
    Keep the driver import inside the one function that needs it, so importing this module on
    a host without the driver still works and the failure names the missing dependency; and give
    a caller that knows which schema it is loading a way to have that expectation CHECKED rather
    than assumed.

    Parameters
    ----------
    settings : AuroraConnectionSettings
        The resolved connection parameters.
    expected_role : str | None
        The login role this connection must authenticate as, which a caller loading one schema
        obtains from :func:`carddemo_migration.config.role_for_schema`. ``None`` performs no check
        and is correct for a caller that is not loading a specific schema's table.

    Returns
    -------
    Any
        An open connection.

    Raises
    ------
    ConfigurationError
        If the settings authenticate as a role other than ``expected_role``, or if the driver is
        not installed -- both being an incomplete or misconfigured environment rather than a
        database that refused.
    AuroraLoadError
        If the driver is present and the connection attempt fails.
    """
    # WHY : Assumptions: role selection is the CALLER's responsibility and this function cannot
    #   take it over, because it is handed settings that are already resolved. `cli.py` is the
    #   caller that discharges it, calling `resolve_aurora_settings(target.schema)` per dataset so
    #   that each load authenticates as that schema's own `carddemo_*` role.
    #   `sql/V0__schemas_and_roles.sql` grants privileges PER OWNING ROLE and gives each role
    #   exactly its own schema, so a single shared superuser connection would load every table
    #   successfully while bypassing the least-privilege boundary the bootstrap exists to
    #   establish -- and a load that only works as a superuser proves nothing about whether the
    #   service that owns the table can write it.
    # WHY : Refactoring Rationale: `expected_role` exists because the previous rationale here
    #   claimed the signature made the mistake impossible -- that taking settings rather than a
    #   schema name meant this function "cannot be handed one connection to reuse for everything".
    #   That was not true of the API as written: the settings are opaque to this function, and
    #   `load_records` accepts any open connection for any target, so nothing prevented a
    #   superuser connection being resolved once and reused. Rather than restate the claim more
    #   carefully, the check the claim described is now performed, at the one point that holds both
    #   the expectation and the credential. It is compared BEFORE the driver is imported, so the
    #   refusal is reachable on a host with no driver installed and costs no connection attempt.
    if expected_role is not None and settings.user != expected_role:
        raise ConfigurationError(
            f"a load of a table owned by {expected_role!r} would authenticate as"
            f" {settings.user!r}; the least-privilege boundary the bootstrap establishes is per"
            " owning role, so the connection is refused rather than made as another role"
        )
    # WHY : Assumptions: no connection parameter is built from a literal in this module. Host,
    #   port, database, user, password and both TLS settings all come from
    #   `AuroraConnectionSettings.as_connection_params()`, which resolves them from Parameter Store
    #   and Secrets Manager. That is what keeps a credential out of this source file, and it is why
    #   the failure message below renders `settings` -- whose `__repr__` masks the password -- and
    #   never the parameter mapping itself.
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
        # WHY : Trade-offs: the settings object's own repr is used, which is defined to withhold
        #   the password, so the message names the host, port, database and user but not the
        #   credential. Formatting the connection parameters here would read better -- a driver
        #   error is usually diagnosed from exactly those parameters -- and would put the secret
        #   into every log that captured the failure. The masked repr is the half of that
        #   diagnostic which is safe to keep.
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


def _safe_diagnostic(
    operation: str,
    target: TableTarget,
    staged: int,
    exc: BaseException,
) -> str:
    """Describe a driver failure using allow-listed metadata and none of its message text.

    Purpose
    -------
    Produce the one sentence a failed statement is reported with, carrying enough to identify the
    fault -- what was being done, to which table, after how many rows, with which SQLSTATE and
    which named constraint or column -- and carrying no part of the row that provoked it.

    Parameters
    ----------
    operation : str
        What was being attempted, in words an operator reads: ``staging table creation``,
        ``bulk copy``, ``merge`` or ``commit``.
    target : TableTarget
        The target being loaded, whose schema and table name the message identifies.
    staged : int
        How many rows had been accepted into the staging stream when the failure arrived.
    exc : BaseException
        The failure. Only its class name and its allow-listed diagnostic attributes are read.

    Returns
    -------
    str
        The sanitised description, always naming the operation, the qualified table and the row
        count, and naming the SQLSTATE and any diagnostic identifiers the driver supplied.

    Raises
    ------
    None
        A driver that publishes none of the allow-listed attributes yields a shorter sentence
        rather than an error: this function is called on a failure path and must not fail.
    """
    # WHY : Assumptions: the driver's own message is NOT read, and this is the whole point of the
    #   function rather than an incidental omission. PostgreSQL's error response carries DETAIL and
    #   CONTEXT beside the primary message; for a unique violation the DETAIL is
    #   `Key (transaction_id)=(...) already exists`, and for a NOT NULL violation the CONTEXT names
    #   the COPY line. psycopg surfaces all of them -- `Diagnostic.message_primary`,
    #   `.message_detail`, `.message_hint` and `.context` -- so interpolating `str(exc)`, which is
    #   what this module did before, put the conflicting key's VALUE into a message `cli.py` writes
    #   straight to a container log. Every one of these targets carries a primary account number, a
    #   national identifier or a cardholder name.
    # WHY : Assumptions: the exception CLASS NAME is included and is safe to include. A class name
    #   is a fixed identifier chosen by the driver -- `UniqueViolation`, `NotNullViolation`,
    #   `InsufficientPrivilege` -- so it carries no row content, and it is the single most useful
    #   thing an operator can be told about a failure whose text is withheld.
    # WHY : Trade-offs: the four identifier fields are read through `getattr` rather than by
    #   importing the driver's diagnostic type. This module imports `psycopg` only inside
    #   `connect`, so a type-driven implementation would either move that import to module scope --
    #   breaking the codec-only import guarantee this package holds to -- or import it again on a
    #   failure path, which is the worst place to require a dependency to be present. The cost is
    #   that a driver publishing a differently-named diagnostic yields a shorter message.
    diagnostic = getattr(exc, "diag", None)
    identifiers = {
        "sqlstate": getattr(exc, "sqlstate", None),
        "schema": getattr(diagnostic, "schema_name", None),
        "table": getattr(diagnostic, "table_name", None),
        "column": getattr(diagnostic, "column_name", None),
        "constraint": getattr(diagnostic, "constraint_name", None),
    }
    named = ", ".join(f"{label}={value}" for label, value in identifiers.items() if value)
    detail = f" [{named}]" if named else ""
    return (
        f"the {operation} for {target.schema}.{target.table} failed after {staged} staged row(s)"
        f" and was rolled back: {type(exc).__name__}{detail}"
    )


def _discard(connection: _Connection, operation: str, target: TableTarget, staged: int) -> str:
    """Roll the transaction back, reporting whether the rollback itself succeeded.

    Purpose
    -------
    Leave the connection in a DEFINED state after any failure, and describe what that state is, so
    a caller returning it to a pool or reusing it is not doing so blind.

    Parameters
    ----------
    connection : _Connection
        The connection whose open transaction is to be discarded.
    operation : str
        What had been attempted when the failure arrived, for the message a failed rollback adds.
    target : TableTarget
        The target being loaded, named in that message.
    staged : int
        How many rows had been accepted, named in that message.

    Returns
    -------
    str
        Empty when the rollback succeeded, which is the ordinary case. Otherwise a sanitised
        clause naming the failed rollback, to be appended to the original diagnostic.

    Raises
    ------
    None
        A rollback failure is REPORTED rather than raised, deliberately: it arrives while another
        failure is already being handled, and raising it would replace the diagnosis with its
        consequence.
    """
    # WHY : Assumptions: a failed rollback does not become the raised error, and the reason is that
    #   it is never the interesting one. A rollback fails because the connection is already broken
    #   -- the server closed it, the socket died -- which is a CONSEQUENCE of, or concurrent with,
    #   the failure being handled. Letting it propagate would discard the original diagnosis in
    #   favour of "rollback failed", the least actionable sentence available. It is appended
    #   instead, because it changes what an operator must do next: a connection whose rollback
    #   failed must be discarded rather than reused, and the transaction is already gone in any
    #   case -- a broken connection has no committed work.
    try:
        connection.rollback()
    except Exception as rollback_failure:  # noqa: BLE001 -- a failure path must not raise its own
        return (
            f"; the rollback after this failure also failed"
            f" ({type(rollback_failure).__name__}), so the connection is unusable and must be"
            f" discarded rather than reused"
        )
    return ""


@dataclass(frozen=True, slots=True)
class _ContentConflict:
    """One dataset's same-key, different-content disagreement, counted and never quoted.

    Purpose
    -------
    Carry the two facts a refusal must state -- how many staged rows the table already holds under
    the same key with different content, and which columns those rows differ in -- in a form that
    structurally cannot carry a value.

    Parameters
    ----------
    rows : int
        How many staged rows conflict.
    columns : tuple[str, ...]
        The columns at least one conflicting row differs in, in the target's own column order.

    Returns
    -------
    None
        A dataclass is constructed, not returned. :meth:`describe` renders it.

    Raises
    ------
    None
        Construction validates nothing; the probe that builds it has already.
    """

    rows: int
    columns: tuple[str, ...]

    def describe(self, target: TableTarget) -> str:
        """Render the refusal an operator acts on.

        Parameters
        ----------
        target : TableTarget
            The target being loaded, whose schema and table the message names.

        Returns
        -------
        str
            One sentence naming the row count, the qualified table and the differing columns, and
            stating that nothing was loaded.

        Raises
        ------
        None
        """
        # WHY : Assumptions: the message names COLUMNS and COUNTS and no value, for the same reason
        #   `_safe_diagnostic` withholds the driver's DETAIL: every one of these tables carries a
        #   primary account number, a national identifier or a cardholder name, and `cli.py` writes
        #   this text straight to a container log.
        # WHY : Assumptions: the remedy is stated, because the correct action is not obvious from
        #   the fault. The load is re-runnable once the disagreement is resolved, and resolving it
        #   is a decision about WHICH side is authoritative -- a decision this package cannot take,
        #   since no role it holds may update or delete a loaded row.
        return (
            f"{self.rows} staged row(s) for {target.schema}.{target.table} carry a key the table"
            f" already holds against different content, in column(s)"
            f" {', '.join(self.columns)}; nothing was loaded and the transaction was rolled back."
            " Establish which side is authoritative before re-running: this loader inserts rows"
            " the table does not hold and never overwrites one it does, so it cannot resolve the"
            " disagreement itself"
        )


def _conflicting_content(cursor: _Cursor, target: TableTarget) -> _ContentConflict | None:
    """Ask the server whether any staged row disagrees with a stored row of the same key.

    Purpose
    -------
    Close the gap ``ON CONFLICT ... DO NOTHING`` leaves open. The merge skips a key the table
    already holds WITHOUT reading it, so a staged row that disagrees with the stored one is
    reported as an exact duplicate and the load returns success -- which is the one outcome that
    makes a wrong table look like a re-run.

    Parameters
    ----------
    cursor : _Cursor
        A cursor on the open load transaction, with the staging table already populated.
    target : TableTarget
        The target being loaded.

    Returns
    -------
    _ContentConflict | None
        The conflict, or ``None`` when every staged row the table already holds is held
        identically -- which includes the ordinary case of a table holding none of them.

    Raises
    ------
    AuroraLoadError
        If the probe projects no row, or a row of the wrong arity. Either means the object in hand
        is not answering as a database cursor, which is reported here rather than surfacing later
        as an index error naming nothing.
    """
    cursor.execute(target.conflict_statement())
    row = cursor.fetchone()
    if row is None:
        raise AuroraLoadError(
            f"the content-conflict probe for {target.schema}.{target.table} returned no row; it"
            " projects exactly one by construction, so the cursor is not behaving as a database"
            " cursor"
        )
    values = tuple(row)
    content = target.content_columns()
    expected = 1 + len(content)
    if len(values) != expected:
        raise AuroraLoadError(
            f"the content-conflict probe for {target.schema}.{target.table} projected"
            f" {len(values)} column(s) where it projects {expected}; the cursor is not behaving as"
            " a database cursor"
        )
    # WHY : Assumptions: a non-integer count is treated as ABSENT EVIDENCE and refused, not
    #   coerced. `int("0")` and `int(None)` fail differently and `int(0.4)` succeeds while losing
    #   the answer; a probe whose count cannot be read as a whole number has not established that
    #   there is no conflict, and the safe reading of "I do not know" here is to refuse.
    counts: list[int] = []
    for value in values:
        if isinstance(value, bool) or not isinstance(value, int):
            raise AuroraLoadError(
                f"the content-conflict probe for {target.schema}.{target.table} projected a"
                f" {type(value).__name__} where every column is a count, so whether the staged"
                " rows agree with the stored ones could not be established"
            )
        counts.append(value)
    if counts[0] == 0:
        return None
    return _ContentConflict(
        rows=counts[0],
        columns=tuple(column for column, count in zip(content, counts[1:], strict=True) if count),
    )


def load_records(
    connection: _Connection,
    target: TableTarget,
    records: Iterable[Mapping[str, str | int | Decimal | bytes]],
    context: LoadContext | None = None,
) -> LoadOutcome:
    """Stage decoded records, merge them into one table, and commit -- once, or not at all.

    Purpose
    -------
    Load one dataset as a single unit of work whose partial application is not observable, and
    whose repetition adds nothing. Every record streams through one server-side COPY into a
    session-temporary staging table, one insert moves the rows the target does not already hold,
    and one commit makes the whole of it durable.

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
        Rows staged and rows the table gained. The two differ by the number of staged rows the
        target already held IDENTICALLY, so a re-run of a completed load reports every row skipped.

    Raises
    ------
    AuroraLoadError
        If a record does not carry a mapped field, a projection cannot be applied, any staged row
        carries a key the table already holds against DIFFERENT content, or the staging, the copy,
        the conflict probe, the merge or the commit fails. The transaction is rolled back before
        the error is raised, and the message names no value the records carried.
    """
    # WHY : Trade-offs: ONE transaction spans the whole dataset, rather than a commit every N
    #   rows. The cost is a longer-held transaction and its accumulated locks and WAL, which on a
    #   cutover-sized extract is the larger of the two costs here. It is accepted because the
    #   alternative leaves a PARTIAL load behind on any failure, and a partially-loaded master is
    #   the one state this pipeline has no way to describe: `sql/verify/row_counts.sql` compares a
    #   table's count against the extract's, so a load that stopped at row 200 000 of 300 000
    #   reports as a mismatch indistinguishable from a decode fault or a wrong extract, and the
    #   operator's only recovery -- emptying the table and starting over -- needs the DELETE
    #   privilege that no role has. Committing once means the table is either fully loaded or
    #   untouched, and a failure is always re-runnable.
    # WHY : Refactoring Rationale: there is ONE path here where there were two. A target with no
    #   second writer used to copy straight into its table, and that path is gone rather than
    #   merely unused: seven of the eleven datasets took it, and every one of them failed on a
    #   Step Functions redrive with a duplicate key -- except the daily feed, which succeeded and
    #   doubled itself. The module docstring records the full argument.
    staged = 0
    inserted = 0
    # WHY : Refactoring Rationale: the delivery's own keys are tracked as it is staged, and they
    #   were not. `ON CONFLICT ... DO NOTHING` cannot distinguish a key the TABLE already holds
    #   from a key this same extract has already presented, so an extract whose second record
    #   repeated the first's identifier loaded 49 of 50 rows, reported "1 already present" and
    #   exited zero -- with the displaced record simply absent from a table that had been empty.
    #   The later row-count verifier catches the shortfall, but the load's own verdict said
    #   success, which is the verdict an operator acts on.
    # WHY : Trade-offs: the keys are held in a SET for the length of the delivery, so peak memory
    #   grows with the number of records rather than staying flat. That is accepted: the widest key
    #   in this corpus is a sixteen-character card number, the largest keyed extract ships fifty
    #   records, and the one genuinely large feed -- 300 records shipped and hundreds of thousands
    #   possible -- is the identity-keyed target that answers None here and allocates nothing.
    #   Asking the server instead, with a `GROUP BY ... HAVING count(*) > 1` probe over the staging
    #   table, was the alternative: it is constant-memory and was rejected because it adds a
    #   statement and a round trip to every one of the ten keyed loads to answer a question the rows
    #   already passing through this loop can answer for free.
    # WHY : Assumptions: the check applies to the KEYED strategy only, and the discriminator is the
    #   strategy rather than the presence of key columns. Every bound target derives a key window
    #   from its record -- the daily feed's is `DALYTRAN-ID` -- but only the ten keyed TABLES assert
    #   uniqueness over it. Reading the key columns instead of the strategy would refuse a feed
    #   carrying one identifier twice, which `V1__ledger.sql` deliberately permits and the baseline
    #   posts twice.
    enforce_unique_keys = target.strategy is LoadStrategy.KEYED_MERGE
    seen_keys: set[tuple[object, ...]] = set()
    try:
        with _cursor_of(connection) as cursor:
            operation = "staging table creation"
            cursor.execute(target.stage_statement())
            operation = "bulk copy"
            with cursor.copy(target.stage_copy_statement()) as stream:
                for record in records:
                    prepared = prepare_record(target, record, context)
                    key = target.key_of(prepared) if enforce_unique_keys else None
                    if key is not None:
                        if key in seen_keys:
                            # WHY : Assumptions: the whole delivery is refused rather than the one
                            #   record skipped, and the refusal names the record's ORDINAL and the
                            #   key COLUMNS while quoting no value. A duplicate business key means
                            #   the extract is not the extract it claims to be -- one record of the
                            #   population it was cut from is missing from it -- so loading the rest
                            #   would store a partial master that every count check reports as a
                            #   mismatch of unknown cause. Refusing here says which record to look
                            #   at, and the ordinal plus the column names locate it in the file
                            #   without putting an account identifier in a retained log.
                            raise AuroraLoadError(
                                f"the extract bound for {target.schema}.{target.table} presents the"
                                f" same {', '.join(target.key_columns)} twice, first at an earlier"
                                f" record and again at record {staged + 1}; a repeated business key"
                                " means one record of the population is absent from the delivery,"
                                " so the whole delivery is refused rather than silently collapsed"
                            )
                        seen_keys.add(key)
                    stream.write_row(target.row_of(prepared))
                    staged += 1
            # WHY : Assumptions: the conflict probe runs BEFORE the merge and inside the same
            #   transaction. Before, because after the merge the disagreeing rows have already been
            #   silently skipped and the outcome reports them as duplicates -- which is the defect.
            #   Inside, because the staging table is session-temporary and the comparison must see
            #   the stored rows under the same snapshot the merge would.
            # WHY : Assumptions: the probe is skipped for a WHOLE_ROW_MERGE target, and DALYTRAN is
            #   the only one. Its merge is an anti-join over every column, so a staged row that
            #   differs anywhere is a row the table does not hold and is inserted; "same key,
            #   different content" is not a conflict there but a second, distinct feed row, which
            #   the baseline's own daily file genuinely contains.
            if target.strategy is LoadStrategy.KEYED_MERGE:
                operation = "content-conflict check"
                conflict = _conflicting_content(cursor, target)
                if conflict is not None:
                    raise AuroraLoadError(conflict.describe(target))
            else:
                # WHY : Assumptions: the whole-row target takes an advisory lock and takes it HERE
                #   -- after the copy, immediately before the merge -- rather than at the top of the
                #   transaction. The staging table is session-temporary, so the copy needs no mutual
                #   exclusion at all; only the anti-join and the insert it feeds do. Locking earlier
                #   would hold the lock across the whole stream, which on a large feed serialises
                #   two deliveries for the duration of both transfers rather than of one merge.
                # WHY : Assumptions: the lock is correct only because this transaction runs at READ
                #   COMMITTED, the server default, which nothing in this package changes. The merge
                #   is issued AFTER the lock is granted, so it takes a fresh snapshot that includes
                #   whatever the previous holder committed -- and its anti-join therefore finds
                #   those rows and inserts none of its own. Under REPEATABLE READ the snapshot
                #   would predate the wait and the duplication would survive the lock, so raising
                #   the isolation level here would silently reintroduce the defect.
                # WHY : Assumptions: the lock is taken for the whole-row strategy rather than for
                #   every target, because the ten keyed targets already serialise on a real unique
                #   index: the second writer blocks on the index entry and its `DO NOTHING` then
                #   applies. Locking them too would add a wait to every load to duplicate a
                #   guarantee the schema already gives.
                operation = "delivery serialisation"
                cursor.execute(target.delivery_lock_statement())
            operation = "merge"
            cursor.execute(target.merge_statement())
            # Assumptions: the inserted count is read from the driver's affected-row count for
            #   the MERGE statement, which counts the rows actually added and not the rows
            #   offered. That is the number an operator needs and it is not derivable from
            #   anything else this function sees. A driver or a double that reports no count is
            #   treated as reporting none rather than as reporting zero, because zero is a
            #   meaningful answer here and must not be manufactured.
            reported = getattr(cursor, "rowcount", None)
            inserted = reported if isinstance(reported, int) and reported >= 0 else staged
        # WHY : Assumptions: the COMMIT is inside the guarded block, which is where an earlier
        #   revision did not put it. A commit can fail on its own -- a deferred constraint, a
        #   serialization failure, a disk or replication error -- and outside the guard that
        #   failure escaped as the driver's own exception type, with the driver's own text, past
        #   every sanitising and rolling-back this function does. The caller then held a
        #   connection with an aborted transaction it had been given no reason to expect.
        operation = "commit"
        connection.commit()
    except AuroraLoadError as refusal:
        # WHY : the rollback happens before the re-raise, so a mapping failure partway through
        #   a stream leaves no partially-loaded table. Letting it propagate first would leave
        #   the transaction open until the connection closed, and a connection returned to a
        #   pool with an open transaction is a defect that surfaces somewhere else entirely.
        # WHY : Assumptions: this branch re-raises the refusal UNCHANGED, because this module
        #   composed it: `row_of` and `prepare_record` name a field, a column and a table and
        #   quote no value, which is exactly the diagnostic to keep. Only the failure of a
        #   rollback is added, and only when there is one to report.
        broken = _discard(connection, operation, target, staged)
        if broken:
            raise AuroraLoadError(f"{refusal}{broken}") from None
        raise
    except Exception as exc:
        broken = _discard(connection, operation, target, staged)
        # WHY : Assumptions: `from None` rather than `from exc`, so the raised error carries no
        #   `__cause__`. The sanitised message is only half the protection: a chained cause puts
        #   the driver's own text -- including the DETAIL naming the conflicting key's value --
        #   into the traceback of anything that logs `exc_info`, and a traceback is exactly what
        #   an unexpected failure gets logged with. Suppressing the context is what makes the
        #   allow-list hold on every path out of this function rather than only on the message.
        sanitised = _safe_diagnostic(operation, target, staged, exc)
        raise AuroraLoadError(f"{sanitised}{broken}") from None
    return LoadOutcome(staged=staged, inserted=inserted)


#: The identifier allocator the interactive write paths draw from.
#:
#: Assumptions: the name is the one
#: ``services/transaction-service/src/main/resources/db/migration/
#: V2__ledger_transaction_id_allocator.sql`` creates, spelled schema-qualified for the same reason
#: every table name in this module is: an unqualified sequence resolves through the session's search
#: path, and advancing the wrong schema's sequence would leave the real one untouched while
#: reporting success.
TRANSACTION_ID_SEQUENCE: Final[str] = "ledger.transaction_id_seq"

#: The pattern the allocator's own migration uses to recognise a sequence-format identifier.
#:
#: Assumptions: copied in the sense that it must MATCH the migration's filter, not in the sense of
#: being a second decision. ``ledger.transactions`` holds two identifier formats -- the sixteen
#: digits this sequence issues, and the business-date-prefixed form the interest job composes at
#: ``app/cbl/CBACT04C.cbl:474-480`` -- and only the first is this allocator's to advance past. A
#: filter that admitted the other would advance the sequence into the date-prefixed range and
#: consume identifiers for a decade of transactions in one statement.
_SEQUENCE_FORMAT_PATTERN: Final[str] = "^[0-9]{16}$"


@dataclass(frozen=True)
class SequenceReconciliation:
    """What one reconciliation of the transaction-identifier allocator found and did.

    Purpose
    -------
    Report the three numbers an operator needs to decide whether writes may be enabled: the
    largest sequence-format identifier the table holds, what the allocator would have issued
    next before the reconciliation, and what it will issue now.

    Parameters
    ----------
    sequence : str
        The schema-qualified sequence reconciled.
    stored_maximum : int
        The largest sequence-format identifier in ``ledger.transactions``, or zero when the table
        holds none.
    next_value_before : int
        What the allocator would have issued next when the reconciliation began.
    next_value_after : int
        What it will issue next now. Never lower than :attr:`next_value_before`.

    Raises
    ------
    None
    """

    sequence: str
    stored_maximum: int
    next_value_before: int
    next_value_after: int

    @property
    def advanced(self) -> bool:
        """Report whether the allocator had to be moved.

        Returns
        -------
        bool
            ``True`` when the reconciliation advanced the sequence, ``False`` when it was already
            past every stored identifier and was left alone.

        Raises
        ------
        None
        """
        return self.next_value_after > self.next_value_before

    @property
    def would_have_collided(self) -> bool:
        """Report whether the allocator would have reissued a stored identifier.

        Returns
        -------
        bool
            ``True`` when the value the allocator was about to issue is one the table already
            holds, which is the defect this step exists to remove.

        Raises
        ------
        None
        """
        # Assumptions: the comparison is `<=` rather than `<`, because `next_value_before` is the
        #   value about to be ISSUED. A sequence poised to issue exactly the stored maximum
        #   collides on its very first allocation, so equality is a collision and not a boundary
        #   safely inside the loaded range.
        return self.next_value_before <= self.stored_maximum

    def describe(self) -> str:
        """Render one operator-readable line stating what the reconciliation did.

        Returns
        -------
        str
            A sentence naming the sequence, the stored maximum and both allocator positions.

        Raises
        ------
        None
        """
        if not self.advanced:
            return (
                f"{self.sequence} already issues {self.next_value_after} and the largest stored"
                f" identifier is {self.stored_maximum}; nothing to reconcile"
            )
        return (
            f"{self.sequence} advanced from {self.next_value_before} to {self.next_value_after}"
            f" past a largest stored identifier of {self.stored_maximum}"
        )


def _rollback_quietly(connection: _Connection) -> None:
    """Roll the transaction back, suppressing any failure the rollback itself reports.

    Purpose
    -------
    Keep a failed diagnosis intact on the SEQUENCE path. The load path discards through
    :func:`_discard`, which names the operation and the target it was working on; the allocator
    reconciliation below has neither a target nor a staged row count, so it discards through this
    narrower helper. Either way the translated error must survive: if the rollback raised, a driver
    exception about the rollback would replace it and the operator would be told about the wrong
    failure.

    Parameters
    ----------
    connection : _Connection
        The connection whose transaction is to be discarded.

    Returns
    -------
    None
        The transaction is discarded, or the attempt is abandoned.

    Raises
    ------
    None
        Deliberately nothing. See the rationale below.
    """
    # WHY : Trade-offs: swallowing an exception is normally the wrong thing to do, and it is the
    #   right thing here for one specific reason: this function is only ever called on a path that
    #   is ABOUT to raise. The rollback is best-effort cleanup, and the two ways it can fail are
    #   both already covered -- the transaction was never open, in which case there is nothing to
    #   discard, or the connection is gone, in which case the server has already discarded it.
    #   Neither is news, and either would mask a diagnosis that is.
    # WHY : Alternatives Considered: attaching the rollback failure to the raised error with
    #   `raise ... from`. Rejected because the chain slot is already used to carry the ORIGINAL
    #   cause, which is the one an operator needs; a rollback failure would displace it.
    try:
        connection.rollback()
    except Exception:  # noqa: BLE001 - deliberately broad; see the rationale above
        return


def reconcile_transaction_id_sequence(
    connection: _Connection,
    *,
    schema: str = "ledger",
) -> SequenceReconciliation:
    """Advance the transaction-identifier allocator past every identifier already loaded.

    Purpose
    -------
    Close the one ordering hazard a cutover has that a fresh deployment does not. The allocator's
    starting position is derived by its own migration, from
    ``max(transaction_id)`` over ``ledger.transactions`` -- and on a cutover that migration runs
    BEFORE the extract is loaded, against an empty table, so it sets the allocator to issue 1.
    The load then writes the real master with its own identifiers, and the allocator is left
    pointing into a range that is now occupied. The first interactive transaction add or bill
    payment after writes are enabled then allocates an identifier the table already holds and
    fails on the primary key -- as does the next, and the next, for as many allocations as the
    loaded range is wide.

    Run this after the last load into ``ledger.transactions`` and BEFORE writes are enabled.

    Parameters
    ----------
    connection : _Connection
        An open connection authenticated as the schema's ``_migrator`` role. The caller owns
        closing it.
    schema : str
        The bounded-context schema owning the allocator. Defaults to the only schema that has
        one, and is a parameter so the owner role is derived rather than named as a literal.

    Returns
    -------
    SequenceReconciliation
        The stored maximum, and the allocator's position before and after.

    Raises
    ------
    AuroraLoadError
        If the owner role cannot be assumed, the sequence or the table cannot be read, or the
        advance is refused. Nothing is left half applied: the reconciliation issues at most one
        ``setval``, and a failure before it leaves the allocator exactly where it was.
    """
    # WHY : Assumptions: this needs the OWNER's authority, not the service role's.
    #   `sql/V0__schemas_and_roles.sql` grants each service role `USAGE, SELECT` on its schema's
    #   sequences -- which is `nextval` and `currval`, and deliberately not `setval`, since
    #   `setval` requires UPDATE. The caller therefore authenticates as the `_migrator` login and
    #   this function issues the `SET ROLE`, exactly as the Flyway migration that created the
    #   sequence does.
    # WHY : Alternatives Considered: granting `UPDATE ON SEQUENCES` to the runtime service role so
    #   that the load's own connection could reconcile. Rejected because it hands a permanent
    #   privilege to a long-lived principal for a one-time cutover step, and the privilege is
    #   precisely the dangerous one: a role that can `setval` can REWIND the allocator and make
    #   the service reissue identifiers it has already stored. The migration role already has the
    #   authority and already exists for exactly this kind of step.
    owner = owner_role_for_schema(schema)
    try:
        with _cursor_of(connection) as cursor:
            cursor.execute(f"SET ROLE {quote_identifier(owner)}")
            cursor.execute(
                f"SELECT last_value, is_called FROM {quote_identifier(schema)}"
                f".{quote_identifier('transaction_id_seq')}"
            )
            position = cursor.fetchone()
            cursor.execute(
                "SELECT coalesce((SELECT max(transaction_id::BIGINT)"
                f" FROM {quote_identifier(schema)}.{quote_identifier('transactions')}"
                f" WHERE transaction_id ~ '{_SEQUENCE_FORMAT_PATTERN}'), 0)"
            )
            maximum = cursor.fetchone()
    except Exception as exc:
        _rollback_quietly(connection)
        raise AuroraLoadError(
            f"the allocator {TRANSACTION_ID_SEQUENCE} could not be read as {owner}, so it was"
            f" not reconciled and writes must not be enabled: {exc}"
        ) from exc

    stored_maximum = _first_int(maximum)
    last_value = _first_int(position)
    # Assumptions: `is_called` is what distinguishes a sequence that has issued a value from one
    #   that has only been positioned. A never-called sequence issues `last_value` itself; a
    #   called one issues `last_value + 1`. Reading `last_value` alone would understate the next
    #   value by one on every sequence that has issued anything, which is the difference between
    #   an advance that clears the loaded range and one that stops one identifier short of it.
    is_called = bool(_second_value(position))
    next_value_before = last_value + 1 if is_called else last_value
    target = stored_maximum + 1
    if target <= next_value_before:
        # WHY : Trade-offs: the reconciliation only ever ADVANCES. A sequence already past the
        #   stored maximum is left exactly where it is, and this is the load-bearing safety
        #   property of the whole step rather than an optimisation: if writes were ever enabled --
        #   even briefly, even by a smoke test -- allocations have happened, and setting the
        #   allocator back to `max + 1` would reissue every identifier allocated since. A
        #   redundant run is therefore a no-op, which is what makes the step safe to repeat.
        _rollback_quietly(connection)
        return SequenceReconciliation(
            sequence=TRANSACTION_ID_SEQUENCE,
            stored_maximum=stored_maximum,
            next_value_before=next_value_before,
            next_value_after=next_value_before,
        )
    try:
        with _cursor_of(connection) as cursor:
            cursor.execute(f"SET ROLE {quote_identifier(owner)}")
            # Assumptions: the third argument is false, so the value passed is the one the NEXT
            #   allocation returns rather than the last one used -- the same form the creating
            #   migration uses, for the same reason: it keeps the empty-table case above the
            #   sequence's declared MINVALUE of 1, which `setval` would otherwise refuse.
            cursor.execute(
                f"SELECT setval('{TRANSACTION_ID_SEQUENCE}', %s, false)",
                (target,),
            )
        connection.commit()
    except Exception as exc:
        _rollback_quietly(connection)
        raise AuroraLoadError(
            f"the allocator {TRANSACTION_ID_SEQUENCE} could not be advanced to {target}, so"
            f" writes must not be enabled: {exc}"
        ) from exc
    return SequenceReconciliation(
        sequence=TRANSACTION_ID_SEQUENCE,
        stored_maximum=stored_maximum,
        next_value_before=next_value_before,
        next_value_after=target,
    )


#: The derived per-card relation the reporting projections are served from.
#:
#: Assumptions: the name is the one ``sql/V1__reporting_views.sql`` creates, spelled
#: schema-qualified for the reason every relation name in this module is -- an unqualified name
#: resolves through the session's search path, and counting rows in some other schema's relation of
#: the same name would report a reconciliation that never happened.
CARD_IDENTITY_RELATION: Final[str] = "reporting.card_identity"

#: The maintenance procedure that reconciles that relation against the cross-reference.
#:
#: Assumptions: it is a PROCEDURE and is invoked with ``CALL``, not a function invoked with
#: ``SELECT``. ``sql/V1__reporting_views.sql`` records why: the reporting service reaches it through
#: a modifying repository method, and the driver that method runs on rejects a result set where none
#: is expected.
CARD_IDENTITY_PROCEDURE: Final[str] = "reporting.refresh_card_identity"


@dataclass(frozen=True)
class CardIdentityRefresh:
    """What one reconciliation of the derived per-card identity relation found and did.

    Purpose
    -------
    Report the numbers an operator needs to decide whether a statement run may proceed: how many
    cards the cross-reference publishes, how many identities were MISSING and how many were
    DEPARTED before the reconciliation, and how many identities exist after it. A run started
    against an under-populated relation omits a cardholder's statement and reports nothing, so
    these are the numbers that make the omission visible BEFORE the run rather than after it.

    Parameters
    ----------
    relation : str
        The schema-qualified relation reconciled.
    identities_before : int
        How many identity rows existed when the reconciliation began.
    identities_after : int
        How many exist now. Equals :attr:`cards_published` on a reconciled relation.
    cards_published : int
        How many cards ``account.card_xref`` holds, which is what the relation must match.
    missing_before : int
        How many published cards had no identity row -- the rows the reconciliation inserted, and
        the cardholders a run started beforehand would have omitted.
    departed_before : int
        How many identity rows named a card the cross-reference no longer publishes -- the rows the
        reconciliation deleted.

    Raises
    ------
    None
    """

    relation: str
    identities_before: int
    identities_after: int
    cards_published: int
    missing_before: int
    departed_before: int

    @property
    def gained(self) -> int:
        """Report how many identities the reconciliation inserted.

        Returns
        -------
        int
            The number of published cards that had no identity row beforehand.
        """
        # WHY : Refactoring Rationale: this was the NET count difference, and a net difference
        #   cannot see the case that matters most. A card withdrawn and another issued between two
        #   extracts leaves the cardinality unchanged while both rows are wrong, so the earlier
        #   form reported zero work for precisely the reconciliation that did the most -- and an
        #   operator reading a cutover log would have taken a ceremonial step for a real one. The
        #   anti-join counts the caller measures are each one index-only scan, which is a cost worth
        #   paying to make the number mean what it says.
        return self.missing_before

    @property
    def removed(self) -> int:
        """Report how many identities the reconciliation deleted.

        Returns
        -------
        int
            The number of identity rows naming a card the cross-reference no longer publishes.
        """
        return self.departed_before

    @property
    def reconciled(self) -> bool:
        """Report whether the relation now carries exactly one identity per published card.

        Returns
        -------
        bool
            ``True`` when the counts agree, which is the postcondition the step exists for.
        """
        return self.identities_after == self.cards_published

    @property
    def was_stale(self) -> bool:
        """Report whether the relation disagreed with the cross-reference before this run.

        Returns
        -------
        bool
            ``True`` when either side of the difference was non-empty, so an operator reading a
            cutover log can tell a step that did work from one that was ceremonial -- including the
            equal-cardinality case a count comparison alone reports as unchanged.
        """
        return bool(self.missing_before or self.departed_before)

    def describe(self) -> str:
        """Render the outcome as one line, naming no card number.

        Returns
        -------
        str
            A single line carrying the relation and the counts. No value it carries is sensitive:
            the counts are cardinalities, and the relation name is a schema object.
        """
        return (
            f"reconciled {self.relation}: published={self.cards_published}"
            f" before={self.identities_before} after={self.identities_after}"
            f" gained={self.gained} removed={self.removed}"
        )


def refresh_card_identity(
    connection: _Connection,
    *,
    schema: str = "reporting",
) -> CardIdentityRefresh:
    """Bring the derived per-card identity relation level with the card cross-reference.

    Purpose
    -------
    Close the one ordering hazard the reporting context has that the others do not.
    ``reporting.card_identity`` holds one row per card, carrying the keyed fingerprint every
    card-bearing reporting projection publishes and the whole card number the projections join
    on, and it is what makes a fingerprint lookup an indexed one. Its rows are DERIVED from
    ``account.card_xref``, and ``sql/V1__reporting_views.sql`` populates it by backfill at the
    moment it is created -- so on a cutover, where the migration runs before the extract is
    loaded, it is created against an empty cross-reference and holds nothing. Every card the load
    then writes is absent from ``reporting.v_card_xref`` and unresolvable by
    ``reporting.resolve_card`` until this step runs.

    Run this after the last load into ``account.card_xref`` and BEFORE any statement run.

    Parameters
    ----------
    connection : _Connection
        An open connection authenticated as an identity that is a member of the schema's owner
        role, which for ``reporting`` means the cluster's master user: it is the one context
        :data:`carddemo_migration.config.MIGRATION_SCHEMA_ROLES` has no ``_migrator`` login for,
        because reporting-service ships no Flyway migration and the reporting objects are applied
        by the bootstrap principal. ``carddemo_migration.cli`` resolves it accordingly. The caller
        owns closing it.
    schema : str
        The bounded-context schema owning the relation. Defaults to the only schema that has one,
        and is a parameter so the owner role is derived rather than named as a literal.

    Returns
    -------
    CardIdentityRefresh
        The published card count, the identity count before and after, and the measured number of
        missing and departed identities -- which is the whole of what an operator needs to decide
        whether a statement run may proceed, and to tell a step that did work from one that did
        none.

    Raises
    ------
    AuroraLoadError
        If the owner role cannot be assumed, either relation cannot be counted, the procedure is
        absent -- a database the reporting migration has not been applied to -- or the
        reconciliation is refused. Nothing is left half applied: the procedure is one statement in
        one transaction, and a failure rolls it back entirely.
    """
    # WHY : Assumptions: this needs the OWNER's authority, exactly as the allocator reconciliation
    #   above does, and for a comparable reason. `sql/V1__reporting_views.sql` grants the runtime
    #   reporting role SELECT on two of the relation's three columns and nothing else -- no insert,
    #   no delete -- and grants it EXECUTE on the procedure so the service can reconcile without
    #   being able to write. The counts below read the THIRD state the service cannot see (the
    #   cross-reference), so the caller authenticates as the `_migrator` login and this function
    #   issues the `SET ROLE`, which is also the role membership that file requires of whoever
    #   applies it.
    # WHY : Alternatives Considered: a trigger on `account.card_xref` maintaining the row as part
    #   of every write, which would make this step unnecessary and remove the ordering hazard
    #   outright. Rejected because `sql/V1__reporting_views.sql` runs under
    #   `SET LOCAL ROLE carddemo_reporting_owner` and that role holds no TRIGGER privilege on a
    #   relation the ACCOUNT context owns; granting it would widen a cross-context boundary in the
    #   direction this architecture forbids, and it would make an account-context write fail
    #   whenever reporting maintenance failed -- coupling a transaction that must succeed to a
    #   derived relation that may be repaired later.
    # WHY : Alternatives Considered: calling this from inside `load_records` when the target is the
    #   cross-reference, so no caller had to remember it. Rejected on two grounds: that function's
    #   contract is one dataset in exactly one transaction, and a second commit would break the
    #   property its own docstring publishes; and the connection it holds authenticates as the
    #   ACCOUNT schema's role, which holds no privilege in the reporting schema at all.
    owner = owner_role_for_schema(schema)
    relation = f"{quote_identifier(schema)}.{quote_identifier('card_identity')}"
    try:
        with _cursor_of(connection) as cursor:
            cursor.execute(f"SET ROLE {quote_identifier(owner)}")
            cursor.execute(f"SELECT count(*) FROM {relation}")
            before = cursor.fetchone()
            # Assumptions: the published count is read from the cross-reference rather than
            #   inferred from the load's own row count. A load reports the rows IT staged, and the
            #   relation may already have held cards from an earlier extract; the postcondition
            #   this step publishes is about the whole relation, so it is measured against the
            #   whole relation.
            cursor.execute(
                f"SELECT count(*) FROM {quote_identifier('account')}"
                f".{quote_identifier('card_xref')}"
            )
            published = cursor.fetchone()
            # WHY : Assumptions: the two differences are MEASURED rather than inferred from the
            #   count movement, and the distinction is the difference between a monitoring signal
            #   and a misleading one. A card withdrawn and another issued between two extracts
            #   leaves the cardinality identical while both rows are wrong, so a before/after
            #   comparison reports "unchanged" for the reconciliation that did the most work. Each
            #   of these is an anti-join over a unique key, so each is one index scan.
            # WHY : Alternatives Considered: having the procedure return its own inserted and
            #   deleted counts, which would be exact by construction and would need no extra
            #   query. Rejected because the reporting service reaches the same procedure through a
            #   modifying repository method, and a routine returning rows in that position is
            #   refused by the driver -- so making it a function would break the other caller to
            #   improve a log line for this one.
            cursor.execute(
                f"SELECT count(*) FROM {quote_identifier('account')}"
                f".{quote_identifier('card_xref')} AS x"
                f" WHERE NOT EXISTS (SELECT 1 FROM {relation} AS ci"
                f" WHERE ci.card_num = x.card_num)"
            )
            missing = cursor.fetchone()
            cursor.execute(
                f"SELECT count(*) FROM {relation} AS ci"
                f" WHERE NOT EXISTS (SELECT 1 FROM {quote_identifier('account')}"
                f".{quote_identifier('card_xref')} AS x WHERE x.card_num = ci.card_num)"
            )
            departed = cursor.fetchone()
    except Exception as exc:
        _rollback_quietly(connection)
        raise AuroraLoadError(
            f"{CARD_IDENTITY_RELATION} could not be counted as {owner}, so it was not reconciled"
            f" and a statement run would omit every card the cross-reference has gained: {exc}"
        ) from exc

    identities_before = _first_int(before)
    cards_published = _first_int(published)
    missing_before = _first_int(missing)
    departed_before = _first_int(departed)
    try:
        with _cursor_of(connection) as cursor:
            cursor.execute(f"SET ROLE {quote_identifier(owner)}")
            # WHY : Trade-offs: the procedure is called UNCONDITIONALLY, even when the two counts
            #   above already agree. An equal count is necessary for a reconciled relation and not
            #   sufficient for one -- a card removed and another issued between two extracts leaves
            #   the cardinality unchanged and both rows wrong -- so a guard on the counts would
            #   skip exactly the case that most needs the work. The procedure is a delta insert and
            #   a delta delete, so a call against an already-reconciled relation writes nothing.
            cursor.execute(f"CALL {CARD_IDENTITY_PROCEDURE}()")
            cursor.execute(f"SELECT count(*) FROM {relation}")
            after = cursor.fetchone()
        connection.commit()
    except Exception as exc:
        _rollback_quietly(connection)
        raise AuroraLoadError(
            f"{CARD_IDENTITY_RELATION} could not be reconciled by {CARD_IDENTITY_PROCEDURE}(),"
            f" so a statement run must not be started -- it would omit every card the"
            f" cross-reference has gained since the reporting migration was applied. Apply"
            f" sql/V1__reporting_views.sql if the procedure is absent: {exc}"
        ) from exc
    return CardIdentityRefresh(
        relation=CARD_IDENTITY_RELATION,
        identities_before=identities_before,
        identities_after=_first_int(after),
        cards_published=cards_published,
        missing_before=missing_before,
        departed_before=departed_before,
    )


def _first_int(row: object) -> int:
    """Read the first column of a result row as a non-negative integer.

    Parameters
    ----------
    row : object
        A row as the driver returned it, a bare value, or ``None``.

    Returns
    -------
    int
        The value, or zero when the row is absent or the value is not a non-negative integer.

    Raises
    ------
    None
    """
    # Assumptions: an absent row answers ZERO rather than raising, matching how the guarding row
    #   count treats the same case -- a table with no rows and a double that arranges none are
    #   indistinguishable here, and both mean "nothing stored".
    if row is None:
        return 0
    value = row[0] if isinstance(row, (list, tuple)) else row
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else 0


def _second_value(row: object) -> object:
    """Read the second column of a result row, or ``None`` when there is not one.

    Parameters
    ----------
    row : object
        A row as the driver returned it, a bare value, or ``None``.

    Returns
    -------
    object
        The second column, or ``None``.

    Raises
    ------
    None
    """
    # Assumptions: a row too short to have a second column answers `None`, which the caller reads
    #   as a sequence that has NOT been called -- the conservative reading, because it makes the
    #   next value `last_value` rather than `last_value + 1` and so can only understate how far
    #   the allocator has gone, never overstate it.
    if isinstance(row, (list, tuple)) and len(row) > 1:
        return row[1]
    return None
