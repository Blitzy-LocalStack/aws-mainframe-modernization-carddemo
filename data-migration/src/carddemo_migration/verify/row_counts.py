"""Compare the record count of a source dataset against the row count of its target table.

Purpose
-------
Verification pass 1 of 3. Catch the coarsest and most common load failure -- a load that stopped
early, ran twice, or silently skipped records -- and do it two complementary ways: per dataset
against a reader's own record count, and for the whole migration in one shot by executing
``data-migration/sql/verify/row_counts.sql`` and judging the six-column report it publishes.
This is the check that makes "the load succeeded" mean something.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only. No connection is opened, no file is read, no environment variable
    is consulted and no query is executed at import time, so this module costs one compile even
    on a host with no database driver installed.


What this pass CANNOT prove
---------------------------
Stated here rather than buried, because a reader who treats a green row-count report as proof of
a correct load has been actively misled:

- A row count shows only that rows ARRIVED, and in the right quantity. It is blind to every
  defect that preserves the number of records.
- It cannot detect a zoned-decimal sign-overpunch decode defect, nor a packed-decimal
  (``COMP-3``) nibble decode defect. That is this migration's most dangerous silent failure,
  because the result looks almost right: the count agrees, every field keeps its declared width,
  and only the SIGN of a money value is wrong.
- It cannot detect a per-row value error, a field misalignment that preserves the record count,
  a mis-assigned key, or a text field truncated to its column width.

The two passes that close those gaps are :mod:`carddemo_migration.verify.checksum` (pass 2) and
:mod:`carddemo_migration.verify.money_parity` (pass 3). A green row count is therefore NECESSARY
BUT NOT SUFFICIENT evidence of a good load.

Notes
-----
Assumptions: all three passes are mandatory and none of them is optional, weakened or
switchable, and the reason is mechanical rather than procedural. A mis-decoded sign yields
exactly the right number of rows with the wrong money in them, so this pass would report MATCH on
every line of a load whose every balance had the wrong sign. Only pass 3 compares the money
itself. Nothing in this module accepts a flag that turns a check off, and no caller can ask it
for a partial verdict.

Assumptions: every count that crosses this module is an integer and no value in it is ever
converted to a binary floating-point type. ``COUNT(*)`` yields ``bigint`` and the baselines are
declared as ``int``, so a count larger than 2**53 stays exact; a ``float`` reaching either side
is refused rather than rounded, because a count that is nearly right is indistinguishable here
from one that is right.
"""

from __future__ import annotations

import enum
import hashlib
import pathlib
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from types import MappingProxyType
from typing import Any, Final

from carddemo_migration.config import (
    AuroraConnectionSettings,
    quote_identifier,
    resolve_aurora_settings,
    role_for_schema,
)
from carddemo_migration.copybook.layouts import (
    EXPORT_HEADER_LAYOUT,
    count_fixed_length_records,
    reclen_of,
)
from carddemo_migration.loaders.aurora import TARGETS, connect, target_for
from carddemo_migration.readers import DATASET_READERS, reader_module

__all__ = [
    "NO_DATASET_LABEL",
    "REPORTING_SCHEMA",
    "ROW_COUNT_COLUMNS",
    "ROW_COUNT_QUERY_DIGEST",
    "ROW_COUNT_QUERY_NAME",
    "ROW_COUNT_QUERY_RELATIONS",
    "SEED_DATASET_BASELINES",
    "UNSEEDED_LAYOUT_NAME",
    "DatasetBaseline",
    "ResultSetContractError",
    "RowCountComparison",
    "RowCountReport",
    "RowCountRow",
    "RowCountVerdict",
    "RowCountVerificationError",
    "VerificationQueryError",
    "baseline_for",
    "compare_counts",
    "count_dataset_records",
    "count_source_records",
    "count_target_rows",
    "fetch_row_count_rows",
    "open_reporting_connection",
    "parse_row_count_rows",
    "read_row_count_query",
    "reporting_role",
    "reporting_settings",
    "require_reporting_session",
    "row_count_query_path",
    "verify_row_count_rows",
    "verify_row_counts",
]

# Assumptions: the six names and their ORDER are the published contract of
#   `data-migration/sql/verify/row_counts.sql`, whose final SELECT projects dataset, target_table,
#   expected_rows, actual_rows, delta and status in exactly this sequence. They are named here so
#   that a result set of the wrong arity is reported as a contract breach against a named column
#   list rather than as an IndexError from a tuple unpack, which names nothing an operator can act
#   on. The query's ordering column, `sort_key`, is deliberately absent: the query orders by it
#   without projecting it, and a seventh name here would report a conforming result set as wrong.
ROW_COUNT_COLUMNS: Final[tuple[str, ...]] = (
    "dataset",
    "target_table",
    "expected_rows",
    "actual_rows",
    "delta",
    "status",
)

# Assumptions: the query spells the absent-dataset label exactly this way, as a quoted
#   literal in its baseline VALUES list, and this module matches on that spelling to recognise the
#   row. An empty string or a SQL NULL would have been the tempting encodings and the query uses
#   neither, because a visible token survives a copy into an issue tracker where an empty cell
#   does not.
NO_DATASET_LABEL: Final[str] = "(none)"

# Assumptions: `ledger.transactions` is the one migrated table with no seed dataset of its
#   own, and TRAN is the layout that describes it. The fact is corroborated three ways rather than
#   asserted here: no `app/data/ASCII/transact.txt` and no TRANSACT dataset under
#   `app/data/EBCDIC` exist; `app/jcl/TRANFILE.jcl` primes the cluster at STEP15 by REPROing
#   `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`, which is exactly 350 bytes -- one single initializer
#   record of the 350-byte layout, not a master; and the reader for TRAN publishes
#   HAS_COMMITTED_SEED_DATASET as False. `_require_unseeded_layout` re-reads that third source at
#   run time, so a day on which an extract does ship is a reported contradiction rather than a
#   baseline that has quietly become wrong.
UNSEEDED_LAYOUT_NAME: Final[str] = "TRAN"

# Assumptions: `reporting` is one of the eight schema names `config` publishes, and the role
#   this pass connects as is resolved FROM it rather than spelled out, so the role name lives in
#   exactly one place -- `config.SCHEMA_ROLES`. Writing "carddemo_reporting" here would be a second
#   copy that keeps answering plausibly once the two disagree.
REPORTING_SCHEMA: Final[str] = "reporting"

# Assumptions: the file NAME is a constant and the DIRECTORY is resolved at call time by
#   `row_count_query_path`, because the two have different lifetimes: the name is part of this
#   pass's contract with its SQL sibling, while the directory depends on where the distribution
#   was unpacked and is therefore an argument a caller may override.
ROW_COUNT_QUERY_NAME: Final[str] = "row_counts.sql"

# Assumptions: the two relations the committed query is entitled to read are named here, so
#   that "this text is the row-count query" becomes a checkable property of the TEXT rather than a
#   claim about a filename. `sql/V3__verification_surfaces.sql` creates both as owner-created
#   security-barrier views projecting counts only -- no key, no identifier, no row value -- and the
#   reporting role holds SELECT on them and on no base table. The pair is a pair rather than one
#   view because `carddemo_reporting_owner` holds no USAGE on the auth schema, so the users count
#   must come from a second view that schema owns; a text reading anything else is either not this
#   query or not entitled to run, and both are refusals rather than results.
ROW_COUNT_QUERY_RELATIONS: Final[frozenset[str]] = frozenset(
    {"reporting.v_verification_row_counts", "auth.v_verification_row_counts"}
)

# Assumptions: the committed query's IDENTITY is pinned, not just its shape. The shape checks
#   establish that a text is a harmless read of the allow-listed views; they cannot establish that
#   it is the report an operator believes is being run, and `--sql-root` exists precisely so a
#   caller can say where the file is. Pinning the digest closes the remaining substitution: a
#   well-formed, allow-listed text that is nevertheless not the shipped query is refused.
# Trade-offs: the accepted cost is that editing `sql/verify/row_counts.sql` requires
#   re-measuring this constant. That cost is bounded by a test which compares the pin against the
#   shipped file and reports the measured digest in its failure, so an edit that forgets the pin
#   fails immediately with the value to paste rather than at an operator's next verification run.
#   Re-measure with `sha256sum data-migration/sql/verify/row_counts.sql`.
ROW_COUNT_QUERY_DIGEST: Final[str] = (
    "3ed794296878932c9385059fa4441f3132aaee966216830e7de9324269ba881d"
)

# Assumptions: the query executes under a statement timeout, set for its transaction only, and
#   the value matches the money pass's own so an operator learns one number. Five minutes never
#   bounds a healthy run -- the views aggregate eleven tables whose largest is the
#   three-hundred-thousand-row transaction master, seconds of sequential scan on the smallest
#   provisioned capacity -- what it bounds is a pass stuck behind a lock taken by the batch chain it
#   is meant to gate, which then fails with a named timeout instead of holding the window open.
_STATEMENT_TIMEOUT_MILLISECONDS: Final[int] = 300_000

# Assumptions: read-only-ness is asserted by the SERVER for the transaction the query runs in,
#   in addition to the role holding no write privilege. The two fail differently and that is why
#   both are kept: the role is what makes writing impossible, and the transaction setting is what
#   makes an ATTEMPT to write fail loudly on a cluster where the role was mis-provisioned. The
#   spelling is `SET TRANSACTION` so the setting lasts exactly as long as the read it protects.
_READ_ONLY_TRANSACTION_STATEMENT: Final[str] = "SET TRANSACTION READ ONLY"

# Assumptions: only these two keywords may OPEN the query, and the pair is an allow-list
#   rather than a list of refused verbs. A single statement beginning `SELECT` or `WITH` cannot
#   modify data unless one of its own expressions does, which the relation check also catches,
#   whereas enumerating write verbs leaves the set open-ended -- a statement type nobody listed
#   would pass a deny-list and fail this allow-list.
_READING_KEYWORDS: Final[frozenset[str]] = frozenset({"SELECT", "WITH"})

# Assumptions: a relation is recognised after FROM or JOIN, optionally schema-qualified, over
#   the COMMENT-STRIPPED text so the query's own prose cannot contribute a match. It is a shape
#   check rather than a parse: the accepted cost is that a sub-select's FROM is treated like a
#   top-level one, which is the safe direction because every relation anywhere must be accounted
#   for.
_RELATION_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?i)\b(?:from|join)\s+([a-z_][a-z0-9_]*(?:\.[a-z_][a-z0-9_]*)?)"
)

# Assumptions: a common table expression is recognised by its `<name> [(columns)] AS (` form
#   after the opening WITH or a comma, which is how the shipped query declares `expected` and
#   `actual`. Recognising them is what lets the relation check insist on the allow-listed views
#   without rejecting the query's own inline vocabulary.
_CTE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?i)(?:\bwith\b|,)\s*([a-z_][a-z0-9_]*)\s*(?:\([^)]*\))?\s+as\s*\("
)


class RowCountVerificationError(RuntimeError):
    """Base class for every way verification pass 1 can fail to reach a verdict.

    Purpose
    -------
    Give a caller one name to catch when it wants to distinguish "this pass could not run" from
    "this pass ran and the counts disagree". The second is not an error and is never raised: a
    disagreement is a reported outcome, because a run that stopped at the first short table would
    hide every table after it.

    Parameters
    ----------
    None
        Constructed with a message like any :exc:`RuntimeError`.

    Returns
    -------
    None
        Exception classes are raised, not returned.
    """


class ResultSetContractError(RowCountVerificationError):
    """Raised when the row-count query returns something other than its published contract.

    Purpose
    -------
    Refuse a result set this module cannot judge, instead of judging it anyway. Every case is a
    breach of the six-column, eleven-row contract stated in ``row_counts.sql``: the wrong number
    of columns, a status token outside the closed set, a delta that contradicts the two counts it
    is derived from, a value that is not an exact integer, a declared (dataset, table) pair the
    report omits, or a pair the report carries that no baseline here recognises.

    Parameters
    ----------
    None
        Constructed with a message naming the breach.

    Returns
    -------
    None
        Exception classes are raised, not returned.
    """


class VerificationQueryError(RowCountVerificationError):
    """Raised when the row-count query itself cannot be located or read.

    Purpose
    -------
    Separate a missing or unreadable query FILE from a database that refused, because the two
    have different remedies -- one is an incomplete checkout or an unpackaged ``sql`` directory,
    the other is a privilege or connectivity problem -- and a single error class would send an
    operator to the wrong one.

    Parameters
    ----------
    None
        Constructed with a message naming the path that was tried.

    Returns
    -------
    None
        Exception classes are raised, not returned.
    """


class RowCountVerdict(enum.Enum):
    """The three outcomes a row-count line can have, one per status token the query emits.

    Purpose
    -------
    Model the outcome as THREE states rather than as a boolean, so that "no baseline exists to
    compare against" cannot collapse into either "agrees" or "differs". A boolean forces that
    collapse and both collapses are wrong: reported as agreement it claims a check that never
    ran, and reported as disagreement it fails a correct fresh load.

    Parameters
    ----------
    None
        Enumeration members are values, not constructed by a caller.

    Returns
    -------
    None
        Enumeration classes are referenced, not returned.
    """

    MATCH = "MATCH"
    MISMATCH = "MISMATCH"
    NO_BASELINE = "NO_BASELINE"

    @property
    def comparable(self) -> bool:
        """Report whether this verdict came from an actual comparison.

        Parameters
        ----------
        None
            Reads the member.

        Returns
        -------
        bool
            True for :attr:`MATCH` and :attr:`MISMATCH`, both of which weighed a baseline against
            a count. False for :attr:`NO_BASELINE`, where there was nothing to weigh.
        """
        return self is not RowCountVerdict.NO_BASELINE

    # Assumptions: this enumeration publishes no "is this acceptable" property, and deliberately
    #   cannot. A member knows which of the three outcomes it is and knows nothing about how many
    #   rows the table holds, so a member-level rule could only treat NO_BASELINE as acceptable
    #   unconditionally -- and it is conditional. The one line reaching that verdict is
    #   `ledger.transactions`, which the ETL leaves EMPTY because the posting job fills it, so a
    #   nonzero count there is stale data from an earlier cutover attempt, a load pointed at the
    #   wrong table, or a posting run that started before verification. The acceptability rule
    #   therefore lives on :class:`RowCountRow`, which holds the count it needs to apply it.


@dataclass(frozen=True)
class DatasetBaseline:
    """One seed dataset's expected record count, and the target table it loads into.

    Purpose
    -------
    Hold a baseline as a named, checkable value rather than as a number embedded in a comparison.
    The record LENGTH is not stored: it is resolved from
    :mod:`carddemo_migration.copybook.layouts` on demand, so a baseline can be re-derived from a
    dataset's byte size and cross-checked against the count declared here.

    Parameters
    ----------
    dataset : str
        The dataset label exactly as ``row_counts.sql`` spells it in its baseline list -- for
        example ``acctdata``.
    layout_name : str
        The record layout that describes it, as :mod:`carddemo_migration.copybook.layouts` spells
        it -- for example ``ACCOUNT``.
    expected_rows : int
        How many records the committed seed dataset holds.

    Returns
    -------
    None
        Construction binds the three declared values. The inapplicability is stated rather than
        left silent, so a reader can tell a value object with no return from a docstring that
        forgot to document one.
    """

    dataset: str
    layout_name: str
    expected_rows: int

    @property
    def reclen(self) -> int:
        """Return the record length of this dataset's layout, in bytes.

        Parameters
        ----------
        None
            Reads :attr:`layout_name`.

        Returns
        -------
        int
            The declared fixed record length.

        Raises
        ------
        LayoutError
            Propagated from :mod:`carddemo_migration.copybook.layouts` if the layout name is not
            registered, which is a typo in this module rather than a data fault.
        """
        # Assumptions: the length is resolved from `copybook.layouts` and is never declared
        #   in this module. Offsets, widths and record lengths are single-sourced there and every
        #   reader resolves them from it, so a second copy here is exactly the drift
        #   single-sourcing exists to prevent -- a copy keeps dividing dataset sizes plausibly
        #   once it disagrees with the layout, and this pass would then certify a load against a
        #   geometry the readers do not use.
        # Assumptions: the export record is reached through EXPORT_HEADER_LAYOUT rather than
        #   through `reclen_of`, because EXPORT is not in that registry -- `reclen_of("EXPORT")`
        #   raises. Its length and its record count are BOTH 500 and they are unrelated facts: 500
        #   bytes a record, 250000 bytes of dataset, 500 records. Conflating them is the specific
        #   mistake this branch exists to make impossible.
        if self.layout_name == EXPORT_HEADER_LAYOUT.name:
            return EXPORT_HEADER_LAYOUT.reclen
        return reclen_of(self.layout_name)

    @property
    def target_table(self) -> str | None:
        """Return the schema-qualified table this dataset loads into, if it loads into one.

        Parameters
        ----------
        None
            Reads :attr:`layout_name`.

        Returns
        -------
        str | None
            ``schema.table`` for a dataset with a declared load target; ``None`` for the export
            record, which is a round-trip artifact rather than a load target and therefore has no
            line in the row-count report.
        """
        # Assumptions: the table name comes from `loaders.aurora.TARGETS`, the same
        #   declaration the loader writes through, so this pass cannot disagree with the loader
        #   about which table a dataset lands in. Membership is tested rather than catching the
        #   loader's refusal, because "this dataset has no load target" is a normal property of the
        #   export record and not an error to be handled.
        if self.layout_name not in TARGETS:
            return None
        target = target_for(self.layout_name)
        return f"{target.schema}.{target.table}"

    def records_in(self, size_bytes: int) -> int:
        """Derive how many records a dataset of a given byte size holds, at this layout's length.

        Parameters
        ----------
        size_bytes : int
            The dataset's total size in bytes.

        Returns
        -------
        int
            The number of whole records, being ``size_bytes`` divided by :attr:`reclen`.

        Raises
        ------
        LayoutError
            If the record length or the size is not usable, propagated from
            :func:`carddemo_migration.copybook.layouts.count_fixed_length_records`.
        RecordLengthError
            If the size is not an exact multiple of the record length. The division is what proves
            a fixed-length dataset well formed, so an inexact one is refused rather than
            clipped -- a floor division would silently discard a partial trailing record and report
            a truncated delivery as complete.
        """
        return count_fixed_length_records(size_bytes, self.reclen)


# Assumptions: every count below was cross-checked TWO independent ways against the
#   immutable seed data -- the line count of `app/data/ASCII/<dataset>.txt`, and the byte size of
#   the matching `app/data/EBCDIC` dataset divided by the record length its copybook declares --
#   and both derivations agree on every row with every division exact and no remainder, which is
#   what makes the record lengths themselves corroborated rather than assumed. The counts are
#   literals rather than measurements taken at run time so that this module needs no access to the
#   seed files in order to know what a correct load looks like, and so that a drift in either the
#   data or the loader shows up as a delta instead of moving the yardstick with it.
# Assumptions: `discgrp` is 51 where every other master is 50, and the odd number is the
#   correct one. `app/data/ASCII/discgrp.txt` holds 51 lines and the EBCDIC dataset holds 2550
#   bytes of a 50-byte record. The extra rows are the mandatory 'DEFAULT' disclosure group the
#   interest calculation falls back to when an account's own group key is not found; carrying 50
#   here would report a correct load as holding one row too many and send an operator hunting a
#   duplicate that does not exist.
# Assumptions: `usrsec` is derived from the EBCDIC dataset alone, because it is the one
#   master with no `app/data/ASCII` counterpart: 800 bytes over the 80-byte record its copybook
#   declares. Nothing about its content is recorded here -- see the disclosure note on
#   `describe`.
# Trade-offs: the export record is listed even though it has no target table and therefore
#   no line in the SQL report. The cost is one entry a caller must know to skip when building the
#   report's expected pair set, which `_expected_report_pairs` does from `target_table` rather
#   than from a hand-kept exclusion list. The benefit is that the eleventh committed seed dataset
#   has a declared baseline like the other ten, so a round-trip check has a number to compare
#   against instead of re-deriving one.
SEED_DATASET_BASELINES: Final[Mapping[str, DatasetBaseline]] = MappingProxyType(
    {
        baseline.dataset: baseline
        for baseline in (
            DatasetBaseline("acctdata", "ACCOUNT", 50),
            DatasetBaseline("carddata", "CARD", 50),
            DatasetBaseline("cardxref", "XREF", 50),
            DatasetBaseline("custdata", "CUSTOMER", 50),
            DatasetBaseline("dailytran", "DALYTRAN", 300),
            DatasetBaseline("discgrp", "DISGROUP", 51),
            DatasetBaseline("tcatbal", "TCATBAL", 50),
            DatasetBaseline("trancatg", "TRANCAT", 18),
            DatasetBaseline("trantype", "TRANTYPE", 7),
            DatasetBaseline("usrsec", "SECUSER", 10),
            DatasetBaseline("export", EXPORT_HEADER_LAYOUT.name, 500),
        )
    }
)


def baseline_for(dataset: str) -> DatasetBaseline:
    """Return the declared baseline for one seed dataset label.

    Parameters
    ----------
    dataset : str
        A dataset label as ``row_counts.sql`` spells it, matched exactly and case-sensitively.

    Returns
    -------
    DatasetBaseline
        The declared baseline for that dataset.

    Raises
    ------
    ResultSetContractError
        If no baseline is declared for that label. The message lists the labels that are declared,
        because the caller's next question is always which spelling was expected.
    """
    try:
        return SEED_DATASET_BASELINES[dataset]
    except KeyError:
        raise ResultSetContractError(
            f"no baseline is declared for dataset {dataset!r}; the declared datasets are"
            f" {sorted(SEED_DATASET_BASELINES)}. {NO_DATASET_LABEL!r} is not among them on"
            " purpose: it labels the one target table that has no seed dataset at all"
        ) from None


def count_dataset_records(source: pathlib.Path, dataset: str) -> int:
    """Count the records a seed dataset holds, from its size and its declared record length.

    Purpose
    -------
    Re-derive a baseline from the bytes on disk so the declared constant can be checked rather
    than trusted. The division is the check: a fixed-length dataset that does not divide exactly
    into whole records is malformed, whatever its size.

    Parameters
    ----------
    source : pathlib.Path
        Path to the dataset image. Supplied by the caller rather than resolved here, so a test can
        point this at a fixture and an operator can point it at a staged extract.
    dataset : str
        The dataset label, used to resolve the record length through :func:`baseline_for`.

    Returns
    -------
    int
        The number of whole records the file holds.

    Raises
    ------
    ResultSetContractError
        If no baseline is declared for that dataset label.
    VerificationQueryError
        If the path does not exist or cannot be measured. The message names the path and nothing
        about the file's contents.
    LayoutError
        If the layout's record length is unusable.
    RecordLengthError
        If the file's size is not an exact multiple of the record length.
    """
    # Assumptions: only the SIZE of the file is read, never a byte of its content, so this
    #   function cannot decode anything and cannot therefore mis-decode anything. That also means
    #   it never has a value to disclose -- the count is derived from `stat`, and the EBCDIC
    #   decode stays where it belongs, behind the readers and their per-field codec.
    baseline = baseline_for(dataset)
    try:
        size_bytes = source.stat().st_size
    except OSError as exc:
        raise VerificationQueryError(
            f"the {dataset} dataset at {source} cannot be measured: {exc.strerror or exc}"
        ) from exc
    return baseline.records_in(size_bytes)


@dataclass(frozen=True)
class RowCountComparison:
    """The outcome of one row-count comparison.

    Purpose
    -------
    Carry one dataset's source record count beside its target table's row count, so a caller
    reads a named outcome rather than comparing two integers it has to keep straight itself.

    Parameters
    ----------
    record_name : str
        The layout name of the dataset compared.
    qualified_table : str
        The schema-qualified target table.
    source_records : int
        Records decoded from the source dataset.
    target_rows : int
        Rows present in the target table.

    Returns
    -------
    None
        Construction binds the four compared values; the verdict is derived by :attr:`matched`
        and :attr:`difference` rather than stored, so the two cannot disagree.
    """

    record_name: str
    qualified_table: str
    source_records: int
    target_rows: int

    @property
    def matched(self) -> bool:
        """Report whether the two counts agree.

        Parameters
        ----------
        None
            Reads the two counts.

        Returns
        -------
        bool
            True when the source and target counts are equal.
        """
        return self.source_records == self.target_rows

    @property
    def difference(self) -> int:
        """Report the target's excess over the source, which is negative when rows are missing.

        Parameters
        ----------
        None
            Reads the two counts.

        Returns
        -------
        int
            ``target_rows - source_records``.
        """
        return self.target_rows - self.source_records

    def describe(self) -> str:
        """Render the outcome as one privacy-safe line.

        Parameters
        ----------
        None
            Reads the comparison.

        Returns
        -------
        str
            A line naming the dataset, the table and both counts. No field value appears,
            because a count comparison never needs one.
        """
        verdict = "MATCH" if self.matched else "DIFFER"
        return (
            f"{verdict} {self.record_name} -> {self.qualified_table}"
            f" source={self.source_records} target={self.target_rows}"
            f" difference={self.difference:+d}"
        )


def count_source_records(records: Iterable[Mapping[str, str | Decimal | bytes]]) -> int:
    """Count the records a reader yields, without holding them.

    Parameters
    ----------
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records, as a reader yields them.

    Returns
    -------
    int
        The number of records.
    """
    # Trade-offs: the records are consumed and discarded one at a time rather than
    #   collected, so counting a three-hundred-thousand-record dataset costs one record of
    #   memory. The cost is that the caller cannot reuse the iterator afterwards, which is why
    #   the checksum verifier takes its own.
    return sum(1 for _ in records)


def count_target_rows(connection: Any, schema: str, table: str) -> int:
    """Count the rows in a target table.

    Parameters
    ----------
    connection : Any
        An open database connection.
    schema : str
        The owning schema.
    table : str
        The table within it.

    Returns
    -------
    int
        The row count.

    Raises
    ------
    ValueError
        If the count query returns no row, which a well-formed COUNT never does and which
        therefore indicates a driver or double that is not behaving as one.
    """
    # Assumptions: the identifiers are quoted through the shared helper rather than
    #   interpolated raw. A schema named `authorization` is a reserved word in PostgreSQL and an
    #   unquoted reference to it is a syntax error, which is the same reason the schema
    #   bootstrap quotes it.
    statement = f"SELECT COUNT(*) FROM {quote_identifier(schema)}.{quote_identifier(table)}"  # noqa: S608
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        cursor.execute(statement)
        row = cursor.fetchone()
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)
    if row is None:
        raise ValueError(
            f"counting {schema}.{table} returned no row at all; a COUNT always returns one, so"
            " the connection is not behaving as a database connection"
        )
    # Assumptions: the value is validated by the same exact-whole-number rule the whole-migration
    #   report parser applies, rather than coerced with `int(row[0])`. Coercion accepts every
    #   representation a count must not arrive as: `True` counts as one row, `2.9` truncates to
    #   two, `Decimal("2.5")` truncates to two, and the text `"2"` parses as two -- so a driver or
    #   a double substituting any of them yields a count this pass would compare against the
    #   extract and report as agreement or disagreement with equal confidence. Beyond 2**53 a float
    #   cannot represent a bigint exactly either, which is the failure that arrives silently on a
    #   large table. Sharing the validator also keeps the two count paths -- this one and the
    #   report's -- refusing the same set for the same stated reason.
    return _require_whole_number(row[0], "count", f"{schema}.{table}")


def compare_counts(
    connection: Any,
    record_name: str,
    schema: str,
    table: str,
    records: Iterable[Mapping[str, str | Decimal | bytes]],
) -> RowCountComparison:
    """Compare one dataset's record count against its target table's row count.

    Parameters
    ----------
    connection : Any
        An open database connection.
    record_name : str
        The layout name of the dataset.
    schema : str
        The target schema.
    table : str
        The target table.
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded source records.

    Returns
    -------
    RowCountComparison
        The outcome, whether or not the counts agree. A disagreement is reported rather than
        raised, so a verification run can report every dataset instead of stopping at the first.

    Raises
    ------
    ValueError
        If the target count cannot be read.
    """
    return RowCountComparison(
        record_name=record_name,
        qualified_table=f"{schema}.{table}",
        source_records=count_source_records(records),
        target_rows=count_target_rows(connection, schema, table),
    )


# Assumptions: the two renderings of an absent value are constants rather than inline
#   literals, because the report's whole value is that two runs over unchanged data diff to
#   nothing. Two spellings of "no baseline" drifting apart between the per-line and the summary
#   form would show up as a diff an operator has to read before dismissing.
_ABSENT: Final[str] = "n/a"
_NOT_COMPARABLE: Final[str] = "not comparable"

# Assumptions: the expectation a baseline-free line IS held to is named as a constant, and the
#   wording matches `money_parity._EXPECTED_EMPTY` deliberately so the two reports an operator reads
#   in the same run speak one vocabulary. A dataset that ships no seed extract must leave its target
#   empty, so the report states which expectation was applied rather than only that no comparison
#   was possible -- the earlier bare `(not comparable)` rendered a correctly-empty table and one
#   holding unaccounted rows identically, while only the first of the two passes.
_EXPECTED_EMPTY: Final[str] = "expected empty:"

# Assumptions: the summary counts the lines that FAILED rather than the lines that mismatched,
#   because the two stopped being the same set when the expected-empty rule arrived: a baseline-free
#   line can now fail without any baseline to have disagreed with. Labelling that count "mismatched"
#   would name a comparison that never happened, and an operator reconciling "1 mismatched" against
#   a report whose only failing line reads `NO_BASELINE` would be reading a contradiction.
_FAILING: Final[str] = "failing"


def _require_whole_number(value: object, column: str, dataset: str) -> int:
    """Convert one result-set value to an exact integer, refusing anything inexact.

    Parameters
    ----------
    value : object
        The value the driver returned for that column.
    column : str
        The column's name, used only in the failure message.
    dataset : str
        The dataset label of the row being parsed, used only in the failure message.

    Returns
    -------
    int
        The value as an exact integer.

    Raises
    ------
    ResultSetContractError
        If the value is a ``bool``, a ``float``, a non-integral :class:`~decimal.Decimal`, or any
        other type. The message names the column, the dataset and the offending type, and never
        the value itself.
    """
    # Assumptions: `bool` is refused before `int` even though it IS an `int` in Python.
    #   `True` would otherwise parse as a count of 1 and a driver or double returning a boolean
    #   where a count belongs is a contract breach that must be reported, not silently coerced
    #   into the smallest plausible count.
    # Alternatives Considered: `float` is refused outright rather than converted through
    #   `int()`. A count arrives as `bigint`, and beyond 2**53 a float cannot represent one
    #   exactly -- so accepting it would let a report certify a load on the strength of a number
    #   that had already lost its last digits. Decimal is accepted because a driver may hand back
    #   `numeric` as one and it is exact, but only when it is integral: a fractional row count is
    #   not a row count.
    if isinstance(value, bool):
        raise ResultSetContractError(
            f"column {column} of the {dataset} row is a bool, which is not a row count; the"
            " row-count query projects bigint in every count column"
        )
    if isinstance(value, int):
        return value
    if isinstance(value, Decimal) and value == value.to_integral_value():
        return int(value)
    raise ResultSetContractError(
        f"column {column} of the {dataset} row is {type(value).__name__} and is not an exact"
        " whole number; a row count must be exact, so it is refused rather than rounded"
    )


@dataclass(frozen=True)
class RowCountRow:
    """One line of the report ``data-migration/sql/verify/row_counts.sql`` publishes.

    Purpose
    -------
    Carry one (seed dataset, target table) pair's baseline, actual count, signed difference and
    verdict as a checked value, so that a caller reads named attributes instead of indexing a
    driver tuple by position.

    Parameters
    ----------
    dataset : str
        The seed dataset label, or :data:`NO_DATASET_LABEL` where no seed dataset exists.
    target_table : str
        The schema-qualified target table.
    expected_rows : int | None
        The baseline record count, or ``None`` where no seed dataset exists. ``None`` is a normal
        state and is never coerced to zero.
    actual_rows : int
        The rows the target table holds now.
    delta : int | None
        ``actual_rows - expected_rows``, or ``None`` when the baseline is ``None``, because no
        difference is defined against an absent baseline.
    status : str
        The verdict token: one of ``MATCH``, ``MISMATCH`` or ``NO_BASELINE``.

    Returns
    -------
    None
        Construction binds the six projected values, having validated them against one another.
        The verdict, the comparability and the pass are all derived from them rather than stored.

    Raises
    ------
    ResultSetContractError
        From :meth:`__post_init__`, if the six values contradict one another. Construction is
        therefore a refusal point, which is what makes an inconsistent line unrepresentable.
    """

    dataset: str
    target_table: str
    expected_rows: int | None
    actual_rows: int
    delta: int | None
    status: str

    def __post_init__(self) -> None:
        """Refuse a line whose six values contradict one another.

        Parameters
        ----------
        None
            Reads the fields just assigned.

        Returns
        -------
        None
            Validation succeeds silently; the value is usable exactly when this returns.

        Raises
        ------
        ResultSetContractError
            If the status token is outside the closed set, if the token disagrees with whether a
            baseline is present, if the delta is not the difference of the two counts, or if a
            delta is present without a baseline.
        """
        # Assumptions: cross-field consistency is checked HERE rather than only in the
        #   parser, so a `RowCountRow` cannot exist in an inconsistent state whichever way it was
        #   built -- including from a test that constructs one directly. A parser-only check would
        #   leave every other construction path unguarded, and the report's arithmetic is the one
        #   thing a reader trusts without re-deriving.
        # Assumptions: the checks RAISE rather than assert. `python -O` strips `assert`
        #   entirely, so an assertion is not a validation -- it is a validation that disappears in
        #   exactly the configuration an operator is most likely to run in production.
        if self.status not in {verdict.value for verdict in RowCountVerdict}:
            raise ResultSetContractError(
                f"the {self.dataset} row carries status {self.status!r}, which is outside the"
                f" closed set {sorted(verdict.value for verdict in RowCountVerdict)} the"
                " row-count query emits"
            )
        no_baseline = self.status == RowCountVerdict.NO_BASELINE.value
        if no_baseline != (self.expected_rows is None):
            raise ResultSetContractError(
                f"the {self.dataset} row reports status {self.status!r} with"
                f" expected_rows={'absent' if self.expected_rows is None else 'present'}; the"
                " query emits NO_BASELINE for exactly the rows whose baseline is NULL, so one of"
                " the two has been lost in transit"
            )
        if self.expected_rows is None:
            if self.delta is not None:
                raise ResultSetContractError(
                    f"the {self.dataset} row carries a delta with no baseline to subtract from;"
                    " NULL propagates into delta in the query, so a value here cannot have come"
                    " from it"
                )
            return
        if self.delta != self.actual_rows - self.expected_rows:
            raise ResultSetContractError(
                f"the {self.dataset} row's delta does not equal actual minus expected; the query"
                " derives it from those two columns, so a disagreement means the row was"
                " assembled from more than one result set"
            )
        matched = self.actual_rows == self.expected_rows
        if matched != (self.status == RowCountVerdict.MATCH.value):
            raise ResultSetContractError(
                f"the {self.dataset} row reports status {self.status!r} while its two counts"
                f" {'agree' if matched else 'differ'}; the token and the arithmetic must say the"
                " same thing or the report cannot be read without recomputing it"
            )

    @property
    def verdict(self) -> RowCountVerdict:
        """Return the three-state verdict this line carries.

        Parameters
        ----------
        None
            Reads :attr:`status`, which ``__post_init__`` has already confirmed is in the set.

        Returns
        -------
        RowCountVerdict
            The matching enumeration member.
        """
        return RowCountVerdict(self.status)

    @property
    def comparable(self) -> bool:
        """Report whether this line compared a baseline against a count at all.

        Parameters
        ----------
        None
            Reads :attr:`verdict`.

        Returns
        -------
        bool
            False for the one line whose baseline is absent; True for every other.
        """
        return self.verdict.comparable

    @property
    def verified(self) -> bool:
        """Report whether this line is acceptable in a passing run.

        Parameters
        ----------
        None
            Reads :attr:`verdict`.

        Returns
        -------
        bool
            True when the baseline and the count agreed, and -- for the one line with no baseline --
            when the target table is EXACTLY EMPTY. False for a disagreement, and false for a
            baseline-free line whose table nevertheless holds rows.
        """
        # Assumptions: a line with no baseline is held to an expected-EMPTY rule rather than
        #   waved through, because the state it describes is determinable: no seed extract ships
        #   for the transaction master, so the ETL leaves `ledger.transactions` empty. A nonzero
        #   count there is evidence of exactly the failures a verification run exists to catch --
        #   rows left behind by an earlier attempt into a table nothing in this package can empty
        #   (no role holds DELETE), a load pointed at the wrong table, or a posting run that ran
        #   ahead of its gate. The shipped `sql/verify/row_counts.sql` reports the NULL baseline
        #   "for completeness"; this rule is what makes that completeness mean something.
        if self.verdict is RowCountVerdict.NO_BASELINE:
            return self.actual_rows == 0
        return self.verdict is RowCountVerdict.MATCH

    def describe(self) -> str:
        """Render this line as one deterministic, privacy-safe string.

        Parameters
        ----------
        None
            Reads the line.

        Returns
        -------
        str
            The status token, the dataset label, the target table and the three numbers, with an
            absent baseline and delta rendered as :data:`_ABSENT` and annotated
            ``not comparable``. No field value, key or record content appears.
        """
        # Trade-offs: this pass renders NO field value at all -- not a masked one -- which is
        #   stricter than the masking the readers apply, and the strictness is deliberate. A
        #   verification report is the artifact most likely to be pasted whole into an issue
        #   tracker, and these datasets carry primary account numbers, national identifiers and
        #   names; the accepted cost is that a mismatch tells an operator WHICH table is short and
        #   not which row is missing, which is what the checksum pass is for. The reference helper
        #   `tests/helpers/record_codec.py::decode_zoned` echoes the offending bytes as `{raw!r}`
        #   in its own failure message; that is a fixture-level convenience and is deliberately
        #   not copied here, because nothing in this module ever holds a record to echo.
        expected = _ABSENT if self.expected_rows is None else str(self.expected_rows)
        delta = _ABSENT if self.delta is None else f"{self.delta:+d}"
        line = (
            f"{self.status} {self.dataset} -> {self.target_table}"
            f" expected={expected} actual={self.actual_rows} delta={delta}"
        )
        if self.comparable:
            return line
        # Assumptions: a baseline-free line's note states the VERDICT reached over it rather than
        #   only that no comparison was possible. A target with no seed extract must be empty, so
        #   the reader is told which expectation was applied and whether it held instead of having
        #   to infer a pass from the absence of a baseline.
        return (
            f"{line} ({_NOT_COMPARABLE}; {_EXPECTED_EMPTY}"
            f" {'held' if self.verified else 'VIOLATED'})"
        )


def parse_row_count_rows(rows: Iterable[Sequence[object]]) -> tuple[RowCountRow, ...]:
    """Parse the row-count query's result set into checked lines, in the order it returned them.

    Purpose
    -------
    Be the whole of the boundary between a driver's tuples and this module's own types. Nothing
    downstream indexes a result row by position, so a change in the query's projection is reported
    here, once, against a named column list.

    Parameters
    ----------
    rows : Iterable[Sequence[object]]
        The result set as a cursor's ``fetchall`` returns it -- one sequence of six values per
        line. Supplied by the caller, so a test can drive every branch with no database at all.

    Returns
    -------
    tuple[RowCountRow, ...]
        One checked line per result row, in the query's own order. That order is fixed by the
        query's non-projected ``sort_key``, so it is preserved rather than re-sorted here.

    Raises
    ------
    ResultSetContractError
        If any row has other than six values, if a label or token is not text, if a count is not
        an exact whole number, or if a line's six values contradict one another.
    """
    # Assumptions: the query's order is PRESERVED and never re-sorted. The query orders by an
    #   integer ordinal it deliberately does not project, precisely so the order cannot depend on
    #   the database collation -- under which '(none)' may sort either side of 'acctdata'. Sorting
    #   here would reintroduce exactly the collation dependence the query removed and break the
    #   byte-identical line diff the ordering exists to guarantee.
    parsed: list[RowCountRow] = []
    for ordinal, row in enumerate(rows, start=1):
        values = tuple(row)
        if len(values) != len(ROW_COUNT_COLUMNS):
            raise ResultSetContractError(
                f"row {ordinal} of the row-count report carries {len(values)} values, but the"
                f" query projects {len(ROW_COUNT_COLUMNS)}: {list(ROW_COUNT_COLUMNS)}"
            )
        dataset, target_table, expected_rows, actual_rows, delta, status = values
        if not isinstance(dataset, str) or not isinstance(target_table, str):
            raise ResultSetContractError(
                f"row {ordinal} of the row-count report names its dataset as"
                f" {type(dataset).__name__} and its table as {type(target_table).__name__};"
                " the query projects text for both"
            )
        if not isinstance(status, str):
            raise ResultSetContractError(
                f"the {dataset} row carries a status of {type(status).__name__}; the query"
                " projects one of three text tokens"
            )
        parsed.append(
            RowCountRow(
                dataset=dataset,
                target_table=target_table,
                # Assumptions: an absent baseline is carried through as None and is never
                #   coerced to zero. The query emits NULL for the one target table that has no seed
                #   dataset, and zero would be a claim -- "the source held nothing" -- rather than
                #   the absence of a claim, so a correct fresh load would read as a mismatch.
                expected_rows=(
                    None
                    if expected_rows is None
                    else _require_whole_number(expected_rows, "expected_rows", dataset)
                ),
                actual_rows=_require_whole_number(actual_rows, "actual_rows", dataset),
                delta=(None if delta is None else _require_whole_number(delta, "delta", dataset)),
                status=status,
            )
        )
    return tuple(parsed)


def _require_unseeded_layout() -> str:
    """Confirm the layout this module treats as unseeded still ships no extract, and name it.

    Parameters
    ----------
    None
        Reads :data:`UNSEEDED_LAYOUT_NAME` and the reader that owns it.

    Returns
    -------
    str
        The schema-qualified target table that legitimately has no baseline.

    Raises
    ------
    RowCountVerificationError
        If the layout is not in the readers' dispatch mapping, if it has no declared load target,
        or if its reader now reports that a seed dataset DOES ship -- in which case the NULL
        baseline has become wrong and the table needs a real count rather than an exemption.
    """
    # Assumptions: the reader is reached through the readers' name-keyed dispatch mapping
    #   rather than by importing `readers.transaction` directly, which is the surface `cli.py`
    #   uses for the same purpose. Importing one reader by name here would work today and would
    #   silently stop tracking the mapping the moment a layout was re-homed to another module.
    # Trade-offs: exactly ONE reader is resolved, not twelve. Driving the whole mapping to
    #   discover which layouts are unseeded would import every reader in the package -- roughly
    #   seven hundred kilobytes of source -- to learn one boolean, and the mapping is consulted
    #   for the lookup either way.
    if UNSEEDED_LAYOUT_NAME not in DATASET_READERS:
        raise RowCountVerificationError(
            f"layout {UNSEEDED_LAYOUT_NAME!r} is not in the readers' dispatch mapping, so this"
            " pass cannot confirm which target table legitimately has no baseline"
        )
    if UNSEEDED_LAYOUT_NAME not in TARGETS:
        raise RowCountVerificationError(
            f"layout {UNSEEDED_LAYOUT_NAME!r} has no declared load target, so no line of the"
            " row-count report belongs to it"
        )
    module = reader_module(UNSEEDED_LAYOUT_NAME)
    if getattr(module, "HAS_COMMITTED_SEED_DATASET", False):
        raise RowCountVerificationError(
            f"the reader for {UNSEEDED_LAYOUT_NAME} now reports a committed seed dataset, so the"
            " NULL baseline this pass expects for its target table is no longer correct; give the"
            " table a counted baseline instead of an exemption"
        )
    target = target_for(UNSEEDED_LAYOUT_NAME)
    return f"{target.schema}.{target.table}"


def _expected_report_pairs() -> Mapping[str, str]:
    """Build the (dataset label, target table) pairs the row-count report must carry.

    Parameters
    ----------
    None
        Derived from :data:`SEED_DATASET_BASELINES` and the one unseeded target.

    Returns
    -------
    Mapping[str, str]
        Dataset label to schema-qualified target table, one entry per line the report must have.

    Raises
    ------
    RowCountVerificationError
        Propagated from :func:`_require_unseeded_layout` if the unseeded target can no longer be
        established.
    """
    # Assumptions: the pair set is DERIVED from the baselines' own load targets rather than
    #   listed a second time, so a dataset added to the baselines appears here with no edit. The
    #   export record drops out by construction because it has no load target -- it is a
    #   round-trip artifact, so no table holds its 500 records and no line of the report belongs
    #   to it. A hand-kept exclusion list would have to be remembered instead.
    pairs = {
        baseline.dataset: baseline.target_table
        for baseline in SEED_DATASET_BASELINES.values()
        if baseline.target_table is not None
    }
    pairs[NO_DATASET_LABEL] = _require_unseeded_layout()
    return MappingProxyType(pairs)


@dataclass(frozen=True)
class RowCountReport:
    """Every line of one row-count verification, with a single binary verdict over them.

    Purpose
    -------
    Let one run report every dataset instead of stopping at the first short table, while still
    answering one yes-or-no question about the whole load.

    Parameters
    ----------
    rows : tuple[RowCountRow, ...]
        The report's lines, in the order the query returned them.

    Returns
    -------
    None
        Construction binds the already-validated lines. The single binary verdict is derived by
        :attr:`verified` rather than stored, so it cannot fall out of step with the lines.
    """

    rows: tuple[RowCountRow, ...]

    @property
    def mismatches(self) -> tuple[RowCountRow, ...]:
        """Return the lines that do not pass.

        Parameters
        ----------
        None
            Reads :attr:`rows`.

        Returns
        -------
        tuple[RowCountRow, ...]
            The failing lines, in report order: a line whose baseline and count disagreed, and a
            baseline-free line whose target holds rows it should not. Empty when the load verifies.
        """
        return tuple(row for row in self.rows if not row.verified)

    @property
    def not_comparable(self) -> tuple[RowCountRow, ...]:
        """Return the lines that had no baseline to compare against.

        Parameters
        ----------
        None
            Reads :attr:`rows`.

        Returns
        -------
        tuple[RowCountRow, ...]
            The lines whose verdict is :attr:`RowCountVerdict.NO_BASELINE`, in report order.
        """
        return tuple(row for row in self.rows if not row.comparable)

    @property
    def verified(self) -> bool:
        """Report the one binary verdict this pass produces.

        Parameters
        ----------
        None
            Reads :attr:`rows`.

        Returns
        -------
        bool
            True when no line mismatched, False otherwise. An empty report is NOT verified,
            because a run that judged nothing has proved nothing.
        """
        # Alternatives Considered: the verdict is BINARY -- verified or not -- and this
        #   module deliberately borrows nothing from the graded aggregate return-code rubric the
        #   COBOL parity suite under `tests/**` uses, in which 4 is a soft warn, 8 a failure and 16
        #   an abend, aggregated worst-wins. A graded tier was considered for the not-comparable
        #   line and rejected: it would make a non-zero verification result ambiguous between
        #   "warned" and "failed", and a caller branching on the number would then have to know
        #   which. That rubric belongs to the parity oracle; the migration CLI commits to a
        #   strictly binary exit status, and a not-comparable line is a PASS here rather than a
        #   third tier.
        # Assumptions: an empty report fails rather than vacuously passing. `all()` over no
        #   rows is True, which would turn "the query returned nothing" -- a lost connection, a
        #   projection that dropped every line -- into a clean bill of health.
        if not self.rows:
            return False
        return all(row.verified for row in self.rows)

    def render(self) -> str:
        """Render the whole report as deterministic, line-diffable text.

        Parameters
        ----------
        None
            Reads :attr:`rows`.

        Returns
        -------
        str
            One header line, one line per report line with the columns aligned, and one summary
            line. No trailing newline, so a caller decides how it is emitted.
        """
        # Assumptions: NOTHING run-varying appears anywhere in this text -- no timestamp, no
        #   duration, no run identifier, no hostname, no process id, no connection detail. The
        #   query already guarantees a fixed row order for exactly this reason, and a single
        #   varying value in the rendering would defeat it: two runs over unchanged data must diff
        #   to nothing, so that a real change stands out instead of arriving inside a diff an
        #   operator has learned to skim.
        # Assumptions: the column widths are computed from THESE rows, so they are a function
        #   of the data and not of the run. Padding to a hard-coded width would either clip a
        #   longer table name later or leave a ragged column now.
        status_width = max((len(row.status) for row in self.rows), default=0)
        dataset_width = max((len(row.dataset) for row in self.rows), default=0)
        table_width = max((len(row.target_table) for row in self.rows), default=0)
        lines = [f"row count verification: {len(self.rows)} line(s)"]
        for row in self.rows:
            expected = _ABSENT if row.expected_rows is None else str(row.expected_rows)
            delta = _ABSENT if row.delta is None else f"{row.delta:+d}"
            note = (
                ""
                if row.comparable
                else f"  ({_NOT_COMPARABLE}; {_EXPECTED_EMPTY} "
                f"{'held' if row.verified else 'VIOLATED'})"
            )
            lines.append(
                f"  {row.status:<{status_width}}"
                f"  {row.dataset:<{dataset_width}}"
                f" -> {row.target_table:<{table_width}}"
                f"  expected={expected} actual={row.actual_rows} delta={delta}{note}"
            )
        matched = sum(1 for row in self.rows if row.verdict is RowCountVerdict.MATCH)
        lines.append(
            f"row count verification {'PASSED' if self.verified else 'FAILED'}:"
            f" {matched} matched, {len(self.mismatches)} {_FAILING},"
            f" {len(self.not_comparable)} {_NOT_COMPARABLE}"
        )
        return "\n".join(lines)


def verify_row_count_rows(rows: Iterable[Sequence[object]]) -> RowCountReport:
    """Judge a row-count result set without touching a database or a file.

    Purpose
    -------
    Be the pure half of this pass: parsing, cross-field validation, coverage against the declared
    baselines, and the binary verdict, all over rows the caller supplies. That is what lets the
    NULL-baseline case and every count assertion be driven with no cluster available.

    Parameters
    ----------
    rows : Iterable[Sequence[object]]
        The result set as the query returned it, one sequence of six values per line.

    Returns
    -------
    RowCountReport
        The parsed lines and the single binary verdict over them. A mismatch is reported here, not
        raised, so one run names every short table.

    Raises
    ------
    ResultSetContractError
        If the result set breaches the published contract, including a declared (dataset, table)
        pair the report omits or a pair no baseline recognises.
    RowCountVerificationError
        If the one legitimately unbaselined target table can no longer be established.
    """
    parsed = parse_row_count_rows(rows)
    expected_pairs = _expected_report_pairs()
    reported = {row.dataset: row.target_table for row in parsed}
    # Assumptions: coverage is checked in BOTH directions, and the missing direction is the
    #   dangerous one. A report that silently drops a table reads as a passing verification of a
    #   load that was never checked, which is the failure this whole pass exists to prevent. The
    #   unknown direction is refused as well, because a line whose baseline this module cannot
    #   name is a line it cannot judge, and reporting it as verified would be a guess.
    missing = sorted(set(expected_pairs) - set(reported))
    if missing:
        raise ResultSetContractError(
            f"the row-count report omits {missing}; a report that drops a dataset reads as a"
            " passing verification of a table nothing checked"
        )
    unknown = sorted(set(reported) - set(expected_pairs))
    if unknown:
        raise ResultSetContractError(
            f"the row-count report carries {unknown}, for which no baseline is declared here;"
            f" the declared datasets are {sorted(expected_pairs)}"
        )
    mismapped = sorted(
        f"{dataset} -> {reported[dataset]} (expected {table})"
        for dataset, table in expected_pairs.items()
        if reported[dataset] != table
    )
    if mismapped:
        raise ResultSetContractError(
            f"the row-count report pairs a dataset with a table this module does not load it"
            f" into: {mismapped}"
        )
    return RowCountReport(rows=parsed)


def row_count_query_path(root: pathlib.Path | None = None) -> pathlib.Path:
    """Locate the row-count query on disk.

    Parameters
    ----------
    root : pathlib.Path | None
        The distribution root holding the ``sql`` directory. ``None`` resolves it from this
        module's own location, which is correct in a source tree and in an editable install.

    Returns
    -------
    pathlib.Path
        The resolved path to ``sql/verify/row_counts.sql``.

    Raises
    ------
    VerificationQueryError
        If nothing readable is at that path. The message names the path and says why it may be
        absent, because the commonest cause is not a missing file but a packaging boundary.
    """
    # Assumptions: the `sql` directory is NOT part of the installed distribution -- the
    #   package configuration finds packages under `src` only -- so this default is correct in a
    #   source checkout and in an editable install and is expected to fail in a plain wheel
    #   install. That is why the root is a parameter: an operator running from an unpacked
    #   distribution supplies it, and the failure below names the path rather than reporting an
    #   empty report.
    # Alternatives Considered: reading the query through `importlib.resources` was rejected
    #   because it would require the SQL to be packaged as module data, which would put a second
    #   copy of the operator-facing query inside the wheel and let the two drift -- the file an
    #   operator runs with `psql` would no longer be the file this pass executes.
    base = pathlib.Path(__file__).resolve().parents[3] if root is None else pathlib.Path(root)
    path = base / "sql" / "verify" / ROW_COUNT_QUERY_NAME
    if not path.is_file():
        raise VerificationQueryError(
            f"the row-count query is not at {path}; the sql directory ships beside the package"
            " rather than inside it, so pass the distribution root explicitly when running from"
            " an installed wheel"
        )
    return path


def _executable_text(text: str) -> str:
    """Remove the query's whole-line comments, leaving the text a server would act on.

    Parameters
    ----------
    text : str
        The query file's contents, comments included.

    Returns
    -------
    str
        The same lines with every line whose first non-blank characters are ``--`` removed,
        rejoined with newlines.
    """
    # Assumptions: the guards below MUST read the executable text rather than the file,
    #   because the shipped query documents its own decisions in comments -- and two of those
    #   comments would otherwise trip them. It shows an operator the exact psql invocation,
    #   whose line continuation is a backslash, and it uses semicolons in ordinary prose. A guard
    #   reading the raw file would therefore refuse the very file it exists to protect, and the
    #   remedy would have been to remove the documentation Rule 1 requires. This is the same
    #   accommodation `data-migration/tests/test_verification.py` makes for the same reason.
    # Trade-offs: whole LINE comments only, matched on the first non-blank characters, with
    #   no SQL lexer. A trailing comment on a code line survives, and a `--` inside a string
    #   literal would be misread as a comment. Both are accepted because this is a guard over one
    #   committed file whose shape is asserted by that file's own tests, and because the text that
    #   is EXECUTED is never this stripped form -- it is always the file's own bytes, so a
    #   mis-strip can only ever cause a refusal, never a wrong query.
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("--"))


def read_row_count_query(path: pathlib.Path | None = None) -> str:
    """Read the row-count query verbatim, and refuse one a driver cursor could not run whole.

    Purpose
    -------
    Hand back the query's exact text, unmodified, having confirmed the three mechanical properties
    a cursor depends on. The text is never rewritten, reformatted or interpolated: the file an
    operator runs with ``psql`` and the text this pass executes are the same bytes.

    Parameters
    ----------
    path : pathlib.Path | None
        The query file. ``None`` resolves it through :func:`row_count_query_path`.

    Returns
    -------
    str
        The file's contents exactly as they are on disk.

    Raises
    ------
    VerificationQueryError
        If the file cannot be located or read, if it holds no executable statement, if it carries
        a psql meta-command, if it carries a bound-parameter placeholder, or if it holds more than
        one statement. Every check reads the text with its whole-line comments removed, because
        the shipped query documents its own decisions in comments and two of those comments would
        otherwise trip a check.
    """
    resolved = row_count_query_path() if path is None else pathlib.Path(path)
    if not resolved.is_file():
        raise VerificationQueryError(f"the row-count query is not at {resolved}")
    try:
        text = resolved.read_text(encoding="utf-8")
    except OSError as exc:
        raise VerificationQueryError(
            f"the row-count query at {resolved} cannot be read: {exc.strerror or exc}"
        ) from exc
    executable = _executable_text(text)
    if not executable.strip():
        raise VerificationQueryError(
            f"the row-count query at {resolved} holds no executable statement; every line of it"
            " is a comment, so running it would produce no report at all"
        )
    # Alternatives Considered: the three properties checked here are the ones that silently
    #   corrupt the RESULT, and read-only-ness is deliberately NOT among them. Scanning the text for
    #   write verbs was considered and rejected as the weaker guarantee: it would have to strip
    #   comments to avoid matching the file's own prose, and it would still only prove something
    #   about the text this module happened to read. What actually makes the pass incapable of
    #   writing is the privilege boundary, which holds whatever text is supplied: the role it
    #   connects as holds no privilege on any base table, no INSERT, UPDATE, DELETE or TRUNCATE
    #   anywhere, and no CREATE even in the one schema it may read. Its whole grant is USAGE on
    #   `reporting`, SELECT on nine views in it and EXECUTE on one read-only lookup function --
    #   the topology is inventoried at the subpackage's own __init__. A backslash is refused
    #   because a psql meta-command is unusable through a driver
    #   cursor; a placeholder is refused because this pass binds no parameter and one would raise
    #   from inside the driver instead; and a second statement is refused because a cursor's
    #   execute() exposes only the LAST result set, so a two-statement file would report a
    #   verification of one table while claiming to have checked eleven.
    if "\\" in executable:
        raise VerificationQueryError(
            f"the row-count query at {resolved} holds a backslash, so it is not pure SQL; a psql"
            " meta-command cannot be run through a driver cursor"
        )
    if "%s" in executable or "%(" in executable:
        raise VerificationQueryError(
            f"the row-count query at {resolved} holds a bound-parameter placeholder; this pass"
            " supplies no parameter, and the query's baselines are literals by design"
        )
    statements = [fragment for fragment in executable.split(";") if fragment.strip()]
    if len(statements) != 1:
        raise VerificationQueryError(
            f"the row-count query at {resolved} holds {len(statements)} statements; a cursor"
            " exposes only the last result set, so all but one report would be lost"
        )
    _require_harmless_query(resolved, executable)
    _require_committed_query(resolved, text)
    return text


def _require_committed_query(resolved: pathlib.Path, text: str) -> None:
    """Establish that the text read is the committed query, by its digest.

    Purpose
    -------
    Close the one substitution the shape checks cannot: a well-formed read of the allow-listed views
    that is nevertheless not the report this pass publishes. The shape checks bound what a text may
    DO; this bounds which text it may BE.

    Parameters
    ----------
    resolved : pathlib.Path
        The file the text came from, named in a refusal so an operator knows which artifact differs.
    text : str
        The file's contents exactly as read, digested verbatim -- comments included, because the
        committed artifact is the whole file rather than its executable remainder.

    Returns
    -------
    None
        Returning is the pass; a mismatch raises.

    Raises
    ------
    VerificationQueryError
        If the digest of ``text`` is not :data:`ROW_COUNT_QUERY_DIGEST`.
    """
    # Assumptions: the WHOLE file is digested, comments and all, rather than the
    #   comment-stripped remainder the shape checks read. The comments carry the query's own
    #   rationale and its published column contract, so a revision that rewrote them while leaving
    #   the SQL alone has changed the artifact an operator reads -- and the digest is the identity
    #   of that artifact, not of its executable subset.
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if digest != ROW_COUNT_QUERY_DIGEST:
        # Trade-offs: the refusal reports both digests and no part of the text. Two hex
        #   strings are enough to tell "this is a different file" from "this file was moved", and
        #   quoting the text would put a query -- which may hold literals -- into a retained log.
        raise VerificationQueryError(
            f"the row-count query at {resolved} has digest {digest}, not the committed"
            f" {ROW_COUNT_QUERY_DIGEST}; this pass executes the committed report and nothing else,"
            " so a substituted file is refused even when it reads only the permitted views"
        )


def _require_harmless_query(resolved: pathlib.Path, executable: str) -> None:
    """Establish that a query text can only read, and can only read the verification views.

    Purpose
    -------
    Turn "this is the committed row-count query" from a statement about a filename into two
    checkable properties of the text itself: it begins as a read, and every relation it names is
    either one of the two allow-listed aggregate views or a common table expression it declares
    inline.

    Parameters
    ----------
    resolved : pathlib.Path
        The file the text came from, named in a refusal so an operator knows which artifact to look
        at. No part of the text is quoted.
    executable : str
        The text with its whole-line comments removed, as :func:`_executable_text` produces it.

    Returns
    -------
    None
        Returning is the pass; every refusal raises.

    Raises
    ------
    VerificationQueryError
        If the text does not begin with a reading keyword, or if it reads any relation other than
        those in :data:`ROW_COUNT_QUERY_RELATIONS` and its own declared expressions.
    """
    # Assumptions: these two checks establish what the text DOES, which the three above it -- no
    #   meta-command, no bound parameter, one statement -- do not: those establish only that the
    #   text is RUNNABLE through a cursor. The privilege boundary is the right defence for a role
    #   provisioned as documented, but it is no defence on a cluster where the role was
    #   mis-provisioned and it says nothing about whether the text is the report an operator
    #   believes is being run. The identical pair guards the money pass, so both verification
    #   queries are held to one rule.
    leading = executable.strip().split(None, 1)[0].upper() if executable.strip() else ""
    if leading not in _READING_KEYWORDS:
        raise VerificationQueryError(
            f"the row-count query at {resolved} begins with {leading or '(nothing)'!r} rather"
            f" than {' or '.join(sorted(_READING_KEYWORDS))}; a verification pass executes a read"
            " and nothing else, so a text beginning any other way is refused rather than run"
        )
    declared = {match.group(1).lower() for match in _CTE_PATTERN.finditer(executable)}
    read = {match.group(1).lower() for match in _RELATION_PATTERN.finditer(executable)}
    unexpected = sorted(read - declared - ROW_COUNT_QUERY_RELATIONS)
    if unexpected:
        # Trade-offs: the refusal names the RELATIONS it did not expect and quotes no other
        #   part of the text. That is enough for an operator to see which artifact is wrong, and it
        #   keeps a query holding a literal out of a log line that is retained.
        raise VerificationQueryError(
            f"the row-count query at {resolved} reads {unexpected}; this pass may read only"
            f" {sorted(ROW_COUNT_QUERY_RELATIONS)} and the expressions the query declares itself,"
            " so a text reading anything else is either not this query or not entitled to run"
        )


def reporting_role() -> str:
    """Return the name of the least-privilege role this pass connects as.

    Parameters
    ----------
    None
        Resolved from :data:`REPORTING_SCHEMA`.

    Returns
    -------
    str
        The role name, resolved from the configuration module's schema-to-role map.

    Raises
    ------
    ConfigurationError
        Propagated if the schema name is not one the configuration module knows.
    """
    return role_for_schema(REPORTING_SCHEMA)


def reporting_settings() -> AuroraConnectionSettings:
    """Resolve the connection parameters of the read-only reporting role.

    Parameters
    ----------
    None
        Resolved from :data:`REPORTING_SCHEMA`.

    Returns
    -------
    AuroraConnectionSettings
        The resolved parameters. Nothing is opened; the configuration module returns parameters
        and never a connection.

    Raises
    ------
    ConfigurationError
        If a parameter or credential cannot be resolved.
    RowCountVerificationError
        If the resolved settings name a role other than the reporting role.
    """
    return _require_reporting_role(resolve_aurora_settings(REPORTING_SCHEMA))


def _require_reporting_role(settings: AuroraConnectionSettings) -> AuroraConnectionSettings:
    """Confirm connection settings authenticate as the read-only reporting role.

    Parameters
    ----------
    settings : AuroraConnectionSettings
        The resolved connection parameters to check.

    Returns
    -------
    AuroraConnectionSettings
        The same settings, unchanged, when they name the reporting role.

    Raises
    ------
    RowCountVerificationError
        If they name any other role. The message names both roles and nothing else about the
        settings, so no host, database or credential reaches the message.
    """
    # Assumptions: a verification pass must be structurally incapable of mutating what it
    #   verifies, and the role is what makes that true rather than the query text. The reporting
    #   role holds SELECT on nine views of one schema and EXECUTE on one read-only lookup
    #   function, and it holds no privilege on a base table, no write privilege of any kind and no
    #   CREATE, so a session on it cannot write a row anywhere even if handed a statement that
    #   tried. The schema is NOT empty -- it owns nine views, one protected table and that function
    #   -- and grounding the guarantee in absent privileges rather than in absent objects is what
    #   keeps it true as relations are added. Running this pass as the bootstrap principal would
    #   work and
    #   is exactly what is refused here: that principal holds row-level read access to every
    #   account balance, card number and national identifier in the system, so the ACT of verifying
    #   would itself be a disclosure.
    expected = reporting_role()
    if settings.user != expected:
        raise RowCountVerificationError(
            f"verification pass 1 connects as {expected!r} and was handed settings for"
            f" {settings.user!r}; a pass that can write cannot certify what it verifies"
        )
    return settings


def open_reporting_connection(settings: AuroraConnectionSettings | None = None) -> Any:
    """Open a read-only connection for this pass, through the loaders' own connect machinery.

    Parameters
    ----------
    settings : AuroraConnectionSettings | None
        Connection parameters to use. ``None`` resolves the reporting role's own through
        :func:`reporting_settings`. Supplied parameters are still checked to name that role.

    Returns
    -------
    Any
        An open connection, as the loaders' connect function returns one. The caller closes it;
        this function deliberately does not, because one connection serves the whole report.

    Raises
    ------
    ConfigurationError
        If the driver is not installed, or a parameter cannot be resolved.
    AuroraLoadError
        If the driver is present and the connection attempt fails.
    RowCountVerificationError
        If the settings name a role other than the reporting role.
    """
    # Alternatives Considered: the connection is opened through
    #   `carddemo_migration.loaders.aurora.connect`, which the loaders package publishes, rather
    #   than by importing the driver here. That module declares itself the only place in this tree
    #   permitted to import psycopg -- and it keeps that import inside the function body -- so
    #   routing through it both honours the invariant and keeps THIS module importable on a host
    #   with no driver installed, which is what lets the pure half of this pass be tested without
    #   one. Importing psycopg here was the alternative and was rejected on that invariant; taking
    #   an already-open connection remains supported and is what every other entry point below
    #   does, because it is what lets the existing in-process database double stand in.
    return connect(_require_reporting_role(reporting_settings() if settings is None else settings))


def _one_scalar(connection: Any, statement: str) -> object:
    """Execute one statement and return the single value its one row projects.

    Parameters
    ----------
    connection : Any
        An open database connection, or the in-process double that stands in for one.
    statement : str
        The statement to execute, which must project exactly one column of one row.

    Returns
    -------
    object
        The projected value, exactly as the driver returned it.

    Raises
    ------
    ResultSetContractError
        If the statement yields no row, or a row of any arity other than one. Both mean the
        object supplied is not behaving as a connection, which is reported as such rather than
        surfaced later as an index error naming nothing.
    """
    # Assumptions: the cursor may or may not be a context manager, so both shapes are
    #   handled -- the same accommodation fetch_row_count_rows below makes, for the same reason.
    #   The driver's cursor is one and this package's in-process double returns a plain object.
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        cursor.execute(statement)
        row = cursor.fetchone()
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)
    if row is None:
        raise ResultSetContractError(
            f"the statement {statement!r} returned no row at all; it projects one by"
            " construction, so the connection is not behaving as a database connection"
        )
    values = tuple(row)
    if len(values) != 1:
        raise ResultSetContractError(
            f"the statement {statement!r} projected {len(values)} columns where it projects one;"
            " the connection is not behaving as a database connection"
        )
    return values[0]


def require_reporting_session(connection: Any) -> str:
    """Confirm the LIVE session on a connection authenticates as the read-only reporting role.

    Purpose
    -------
    Ask the server who it thinks the caller is, before any supplied query text is executed on
    that connection, so that a pass which cannot write is a property of the session rather than a
    property of the settings some earlier call happened to be handed.

    Assumptions: the caller executes ``SET TRANSACTION READ ONLY`` before reaching here, so this
    probe runs inside a transaction the server has already been told cannot write. That ordering
    belongs to the caller because only the caller knows which transaction the report runs in; what
    is owned here is the identity question alone.

    Parameters
    ----------
    connection : Any
        An open database connection, or the in-process double that stands in for one.

    Returns
    -------
    str
        The session's role name, so a caller may log which role certified the report.

    Raises
    ------
    RowCountVerificationError
        If the session authenticates as any role other than the reporting role. The message names
        both roles and nothing else about the session, so no host, database or credential reaches
        it.
    ResultSetContractError
        If the connection does not answer the probe as a connection would.
    """
    # Assumptions: this check interrogates the SESSION, where the settings check beside it,
    #   _require_reporting_role, inspects the parameters handed to open_reporting_connection. That
    #   one protects only callers who open a connection through that function, and every published
    #   entry point here also accepts an already-open connection so the in-process double can stand
    #   in -- so without this check the command-line verification path could open a schema-owner
    #   connection and nothing would refuse it, running the pass with write authority over the very
    #   tables it is certifying. Asking the server covers every caller, including one holding a
    #   connection this module never opened.
    #
    # Alternatives Considered: trusting the settings alone, and requiring every caller to
    #   route through open_reporting_connection. The first cannot see a connection this module did
    #   not open. The second was rejected because it would remove the injection seam the suite
    #   depends on, and because settings do not determine a session anyway: a connection may be
    #   pooled, handed on, or have executed SET ROLE between being opened and being used here.
    #
    # Assumptions: `select current_user` is the probe rather than `session_user`, because
    #   current_user is the identifier privilege decisions are actually made against -- it follows
    #   a SET ROLE where session_user does not. A session that authenticated as the reporting role
    #   and then assumed a writable one would pass a session_user check and could still write.
    expected = reporting_role()
    observed = _one_scalar(connection, "select current_user")
    text = observed.strip() if isinstance(observed, str) else str(observed)
    if text != expected:
        raise RowCountVerificationError(
            f"verification pass 1 must run on a session for {expected!r} and this connection's"
            f" session is {text!r}; a pass that can write cannot certify what it verifies"
        )
    return text


def fetch_row_count_rows(
    connection: Any, *, query_path: pathlib.Path | None = None
) -> tuple[tuple[object, ...], ...]:
    """Read the committed row-count query and execute it on a certified read-only session.

    Parameters
    ----------
    connection : Any
        An open database connection, supplied by the caller so a double can stand in. Its live
        session is confirmed to be the reporting role before anything is executed.
    query_path : pathlib.Path | None
        Where to read the committed query from. ``None`` resolves it through
        :func:`row_count_query_path`. A PATH is accepted and query TEXT is not, so the statement
        this pass executes is always the committed one.

    Returns
    -------
    tuple[tuple[object, ...], ...]
        The result set in the order the query returned it, each row as a tuple of column values.

    Raises
    ------
    VerificationQueryError
        If the query cannot be located or read, is not a single pure-SQL statement, or is not a
        read of the allow-listed verification views.
    RowCountVerificationError
        If the connection's live session is not the reporting role.
    ResultSetContractError
        If the cursor yields no result set at all, which a ``SELECT`` always does and which
        therefore means the object supplied is not behaving as a connection.
    """
    # Assumptions: the published entry point accepts a query PATH and never query TEXT. Accepting
    #   text would let the one function an orchestration step reaches for be handed a statement
    #   nobody committed, executed on the session this pass has just certified as the sole
    #   authority entitled to judge the load. A path answers the only question packaging raises --
    #   the sql directory ships BESIDE the package rather than inside it, so a caller running from
    #   a wheel has to say where the file is -- without also saying what to run. The injectable
    #   seam the tests need is private and unreachable from the published surface.
    return _row_count_rows(connection, read_row_count_query(query_path))


def _row_count_rows(connection: Any, query: str) -> tuple[tuple[object, ...], ...]:
    """Execute one already-validated query text on a certified read-only session.

    Purpose
    -------
    Hold the execution discipline -- session check, read-only transaction, statement timeout, one
    fetch -- in one place, and keep the text an argument so a test can drive a substituted result
    set without that seam being reachable from the published surface.

    Parameters
    ----------
    connection : Any
        An open database connection, or the in-process double that stands in for one.
    query : str
        Query text that has already passed :func:`read_row_count_query`, executed verbatim. Nothing
        is appended, wrapped or interpolated.

    Returns
    -------
    tuple[tuple[object, ...], ...]
        The result set in the order the query returned it, each row as a tuple of column values.

    Raises
    ------
    RowCountVerificationError
        If the connection's live session is not the reporting role.
    ResultSetContractError
        If the cursor yields no result set at all.
    """
    # WHY : Assumptions: the cursor may or may not be a context manager, so both shapes are
    #   handled. The driver's cursor is one and the in-process double used by this package's suite
    #   returns a plain object, and this is the same accommodation both sibling passes make.
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        # WHY : Assumptions: the ORDER of these four statements is load-bearing and it begins with
        #   the read-only setting. The driver opens a transaction implicitly on the first execute,
        #   so whichever statement runs first decides what the transaction is for every statement
        #   after it -- including the identity probe. Issuing `SET TRANSACTION READ ONLY` first is
        #   what makes the WHOLE transaction read-only rather than only the part after the probe.
        # WHY : Refactoring Rationale: the probe used to run first, and the read-only setting
        #   second. Measured against PostgreSQL 17 rather than assumed: that order is ACCEPTED --
        #   the engine allows a transaction's access mode to be tightened mid-transaction, and only
        #   `SET TRANSACTION ISOLATION LEVEL` is refused after a query, with SQLSTATE 25001. So the
        #   old order worked, and it worked by relying on engine leniency the SQL standard does not
        #   grant: the standard's `SET TRANSACTION` applies to the next transaction, and a
        #   connection pooler or proxy that opens the transaction itself may present the statement
        #   out of that position. Two things are gained by leading with it and nothing is lost: the
        #   probe itself now executes in a transaction the server has already been told cannot
        #   write, and the sequence no longer depends on a vendor-specific allowance for a rule this
        #   module states in its own comments.
        # WHY : Assumptions: the identity probe follows in the SAME transaction, through the
        #   published guard, and it is not moved out to the caller. A guard at the one execution
        #   site cannot be bypassed by another path into this helper, and the probe reads
        #   `current_user` from the server rather than trusting the settings a connection was opened
        #   with -- see `require_reporting_session`. It shares this transaction because every cursor
        #   on a connection does.
        # WHY : Trade-offs: the timeout is interpolated from a module constant rather than bound as
        #   a parameter, because `SET` accepts no bound parameter. The value is an `int` constant
        #   declared in this module and never caller supplied, so no text from outside this file
        #   reaches the statement. `SET LOCAL` is transaction-scoped, so it expires with the read it
        #   bounds instead of leaking into a later use of the connection.
        cursor.execute(_READ_ONLY_TRANSACTION_STATEMENT)
        require_reporting_session(connection)
        cursor.execute(f"SET LOCAL statement_timeout = {_STATEMENT_TIMEOUT_MILLISECONDS}")
        cursor.execute(query)
        fetched = cursor.fetchall()
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)
    if fetched is None:
        raise ResultSetContractError(
            "the row-count query returned no result set at all; a SELECT always returns one, so"
            " the connection is not behaving as a database connection"
        )
    return tuple(tuple(row) for row in fetched)


def verify_row_counts(
    connection: Any,
    *,
    query_path: pathlib.Path | None = None,
) -> RowCountReport:
    """Run verification pass 1 over the whole migration and report every line of it.

    Purpose
    -------
    Execute ``data-migration/sql/verify/row_counts.sql`` as-is against a read-only session and
    judge the six-column report it publishes, so that one call answers "did every dataset land, in
    the right quantity" for the whole load.

    Parameters
    ----------
    connection : Any
        An open database connection, which must be a session on the reporting role. That is
        CHECKED against the server rather than assumed -- see :func:`require_reporting_session`,
        which :func:`fetch_row_count_rows` calls before executing anything. Supplied rather than
        opened here so that one connection can serve all three passes and so that the in-process
        double can stand in; :func:`open_reporting_connection` opens one.
    query_path : pathlib.Path | None
        Where to read the committed query from. ``None`` resolves it through
        :func:`row_count_query_path`. A path is accepted and query TEXT is not, so the statement
        this pass executes is always the committed one -- see :func:`fetch_row_count_rows`.

    Returns
    -------
    RowCountReport
        Every line of the report and the single binary verdict over them. A mismatch is reported,
        never raised, so one run names every short table instead of stopping at the first.

    Raises
    ------
    VerificationQueryError
        If the query cannot be located or read, is not a single pure-SQL statement, or is not a
        read of the allow-listed verification views.
    ResultSetContractError
        If the result set breaches the query's published contract.
    RowCountVerificationError
        If the connection's live session is not the reporting role, or if the one legitimately
        unbaselined target table can no longer be established.
    """
    # Assumptions: the caller supplies a query PATH and never query TEXT. Packaging is the only
    #   reason text would be needed -- the sql directory ships beside the package rather than
    #   inside it -- and the ROOT parameter already answers that, so accepting text would add
    #   nothing but a way to run an uncommitted statement under the one authority this pass
    #   certifies. The money pass is held to the same rule.
    return verify_row_count_rows(fetch_row_count_rows(connection, query_path=query_path))
