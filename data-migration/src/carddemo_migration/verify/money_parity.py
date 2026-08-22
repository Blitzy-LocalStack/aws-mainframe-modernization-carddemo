"""Total the money fields of a dataset exactly, and compare against the target's own total.

Purpose
-------
Verification pass 3 of 3. Catch the failure this migration is most exposed to and which the
other two passes can miss: a decode that read a zoned-decimal sign overpunch with the wrong
convention, or shifted an implied decimal point. Both produce a plausible number rather than an
error, so the record count agrees and, where a digest is computed from the same wrong decode on
both sides, a digest can agree too. A total computed from the SOURCE bytes and compared against
the DATABASE's own ``SUM`` is the check that does not share that assumption.

The pass works two complementary ways, and both are published here:

* per dataset and per column, against records a caller has already decoded -- the surface
  ``cli.py``'s ``verify-money-parity`` subcommand drives, through :func:`compare_money_totals`;
* for the whole migration in one shot, by executing ``data-migration/sql/verify/money_totals.sql``
  as-is and judging the eight-column, nine-row report it publishes against totals recomputed
  independently from the source extracts -- :func:`verify_money_totals`.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only. No connection is opened, no file is read, no environment variable
    is consulted and no query is executed at import time, so this module costs one compile even on
    a host with no database driver installed.

Raises
------
None
    Importing raises nothing. Every failure this pass can have belongs to a call, and each of
    :exc:`MoneyParityVerificationError`'s two subclasses names one class of them.

Why the negative-row count is compared and not merely reported
-------------------------------------------------------------
A mis-applied overpunch table flips signs WHOLESALE. The canonical mapping the reference codec
declares at ``tests/helpers/record_codec.py`` L137-L138 is ``'{ABCDEFGHI'`` for +0 through +9 and
``'}JKLMNOPQR'`` for -0 through -9, applied to a field's low-order byte with the decimal point
implied, so a decoder that reads ``'J'`` through ``'R'`` as the plain digits 1 through 9 returns a
number of exactly the right magnitude with the wrong sign. Nothing else notices: the record keeps
its declared length, every field keeps its boundaries and the row count is unchanged.

A TOTAL alone is not enough either, because a wholesale flip can leave one unchanged when the
positives and negatives happen to balance. The count of strictly-negative rows is what makes the
defect unambiguous, so this pass compares that count on both sides rather than displaying it. The
hazard is not hypothetical in this repository: ``tests/helpers/statement_compat.py`` L33-L36
records that ``-fsign=EBCDIC`` is mandatory for the statement build because the committed goldens
were produced under that sign convention, so an ASCII-sign build "would render negative amounts
differently and mismatch the golden".

What this pass CANNOT prove
---------------------------
Stated here rather than buried, because a sum is the single most over-trusted number in a
migration report:

- It cannot catch a COMPENSATING PAIR of errors -- two mis-decodes of opposite direction and equal
  magnitude leave the total untouched. The negative-row count is a partial mitigation, not a
  refutation.
- It cannot catch a PER-ROW MIS-ASSIGNMENT: the right amounts attached to the wrong keys sum
  identically, because addition does not record which row it read.
- It cannot catch a defect in a NON-MONEY column. This pass observes nine columns and is blind to
  the rest.

The two passes that close those gaps are :mod:`carddemo_migration.verify.row_counts` (pass 1) and
:mod:`carddemo_migration.verify.checksum` (pass 2).

Notes
-----
Assumptions: all three passes are mandatory and none of them is optional, weakened or switchable.
The reason is mechanical rather than procedural: a mis-decoded sign yields exactly the right
number of rows with the wrong money in them, so pass 1 reports MATCH on every line of a load whose
every balance has the wrong sign, and pass 2 agrees with itself whenever the same wrong decode is
applied to both sides. Only this pass compares the money against the bytes. Nothing here accepts a
flag that turns a check off, and no caller can ask it for a partial verdict.

Assumptions: every total that crosses this module is a :class:`decimal.Decimal` and no value in it
is ever converted to a binary floating-point type. A binary float cannot represent ten cents
exactly, so a float aggregate would drift from the total the baseline computed by an amount that
grows with the row count -- masking the very defect this pass exists to find. The SQL side holds
the same invariant by construction: the aggregate view is ``NUMERIC`` from column through ``SUM``
to output.

Trade-offs: a ``'}'``-signed zero -- COBOL's negative zero, a distinct byte from ``'{'`` --
contributes to NEITHER the total NOR the negative-row count, on either side. On the Python side
:mod:`carddemo_migration.copybook.zoned` decodes it to an unsigned zero as a documented
cross-language parity policy, and on the SQL side PostgreSQL ``NUMERIC`` has no signed zero at all,
so the view's ``WHERE col < 0`` filter is false for it. The consequence is accepted rather than
worked around: a dataset whose only sign defect were a negative zero read as a positive zero would
verify clean here. The encode direction is not this pass's business -- nothing here re-encodes a
record -- so the round-trip law and its single exception stay documented where they are enforced,
in ``zoned.py``.

Trade-offs: no field value is rendered anywhere in this module, not even a masked one, which is
stricter than the masking the readers apply. A verification report is the artifact most likely to
be pasted whole into an issue tracker, and these datasets carry primary account numbers, national
identifiers and names; every money field this module totals is itself marked sensitive by its own
descriptor except the disclosure group's interest rate, which is a published rate rather than a
customer's money. The proportion is deliberately not written here as a fraction: it is a property
of ``declared_money_columns()`` and of the layouts behind it, so a hand-maintained count goes
stale on the
next column added and the staleness is invisible to a reader. ``test_money_parity_descriptors``
derives it from the descriptors instead. Only
aggregates over a whole dataset and field GEOMETRY -- name, offset, length, storage regime --
reach a message here. The reference helper ``tests/helpers/record_codec.py``'s ``decode_zoned``
echoes the offending bytes as ``{raw!r}`` in its own failure message; that is a fixture-level
convenience and is deliberately not copied.
"""

from __future__ import annotations

import enum
import hashlib
import pathlib
import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from functools import lru_cache
from types import MappingProxyType
from typing import Any, Final

from carddemo_migration.config import (
    AuroraConnectionSettings,
    quote_identifier,
    resolve_aurora_settings,
    role_for_schema,
)
from carddemo_migration.copybook import layouts
from carddemo_migration.loaders.aurora import TARGETS, connect
from carddemo_migration.readers import DATASET_READERS, dataset_reader, ships_committed_extract

__all__ = [
    "MONEY_SCALE",
    "MONEY_TOTAL_COLUMNS",
    "MONEY_TOTAL_COLUMN_COUNT",
    "MONEY_TOTAL_QUERY_DIGEST",
    "MONEY_TOTAL_QUERY_NAME",
    "MONEY_TOTAL_TABLE_COUNT",
    "REPORTING_SCHEMA",
    "SOURCE_ENCODINGS",
    "MoneyColumn",
    "MoneyParity",
    "MoneyParityLine",
    "MoneyParityVerdict",
    "MoneyParityVerificationError",
    "MoneyQueryError",
    "MoneyResultSetContractError",
    "MoneyTotalReport",
    "MoneyTotalRow",
    "SourceExtract",
    "SourceMoneyTotal",
    "compare_money_totals",
    "declared_money_columns",
    "money_columns",
    "fetch_money_total_rows",
    "money_total_query_path",
    "open_reporting_connection",
    "parse_money_total_rows",
    "read_money_total_query",
    "read_source_totals",
    "reporting_role",
    "reporting_settings",
    "require_reporting_session",
    "total_source_column",
    "total_source_fields",
    "total_source_money",
    "total_target_money",
    "verify_money_total_rows",
    "verify_money_totals",
]

# WHY : Assumptions: every money column in this system is NUMERIC(p,2) and every money field in
#   the baseline is a display field with two implied decimal places, so a total is compared at
#   scale 2 exactly. Comparing at the raw decimal's own scale would let 1.5 and 1.50 differ.
MONEY_SCALE: Final[int] = 2

# WHY : Assumptions: the precision a per-column total is cast to is 38 digits, which is what makes
#   the cast a SCALE assertion rather than a value constraint. The widest money column is
#   NUMERIC(12,2), so a total over even three hundred million rows of the largest representable
#   balance stays inside 38 digits by a wide margin -- while a narrower precision would raise a
#   numeric-overflow error on a legitimately large total and turn a passing verification into a
#   failed step. PostgreSQL's own NUMERIC maximum precision is far higher again, so 38 is a
#   deliberate, generous ceiling rather than a limit anything here can reach.
_TARGET_TOTAL_PRECISION: Final[int] = 38

# WHY : Assumptions: the eight names and their ORDER are the published contract of
#   `data-migration/sql/verify/money_totals.sql`, whose final SELECT projects target_table,
#   money_column, cobol_field, cobol_picture, sql_type, row_count, total and negative_rows in
#   exactly this sequence. They are named here so that a result set of the wrong arity is reported
#   as a contract breach against a named column list rather than as an IndexError from a tuple
#   unpack, which names nothing an operator can act on. The query's ordering column, `sort_key`, is
#   deliberately absent: the query orders by it without projecting it, and a ninth name here would
#   report a conforming result set as wrong.
MONEY_TOTAL_COLUMNS: Final[tuple[str, ...]] = (
    "target_table",
    "money_column",
    "cobol_field",
    "cobol_picture",
    "sql_type",
    "row_count",
    "total",
    "negative_rows",
)

# WHY : Assumptions: the report is exactly NINE rows over exactly FIVE tables, and the two figures
#   are declared separately because they fail differently. A result set of nine rows over four
#   tables would mean one table's rows had been duplicated and another's lost, which a row count
#   alone cannot see. `declared_money_columns` checks BOTH against the inventory it derives from
#   the loader's own declarations, so the figures are corroborated rather than merely asserted.
# WHY : Alternatives Considered: the two counts are literals here and the (table, column) pairs are
#   DERIVED from `loaders.aurora.TARGETS` and the layout registry. Listing the nine pairs literally
#   as well was the alternative and was rejected: the loader already declares which copybook field
#   becomes which column, and a second copy of that mapping here would keep answering plausibly
#   after the first was corrected. Deriving the pairs and declaring only their COUNT keeps one
#   authority for the mapping while still failing loudly if the derivation stops producing nine.
MONEY_TOTAL_TABLE_COUNT: Final[int] = 5
MONEY_TOTAL_COLUMN_COUNT: Final[int] = 9

# WHY : Assumptions: the schema name is the key into the configuration module's schema-to-role map,
#   and the role this pass connects as is resolved FROM it rather than spelled out, so the role
#   name lives in exactly one place -- `config.SCHEMA_ROLES`. Writing the role name here would be a
#   second copy that keeps answering plausibly after the first is corrected.
REPORTING_SCHEMA: Final[str] = "reporting"

# WHY : Assumptions: the file NAME is a constant and the DIRECTORY is resolved at call time by
#   `money_total_query_path`, because the two have different lifetimes: the name is part of this
#   pass's contract with its SQL sibling, while the directory depends on where the distribution was
#   unpacked and is therefore an argument a caller may override.
MONEY_TOTAL_QUERY_NAME: Final[str] = "money_totals.sql"

# WHY : Assumptions: the committed query's IDENTITY is pinned, not just its shape, for the reason
#   its row-count sibling states: the shape checks establish that a text is a harmless read of the
#   allow-listed view and cannot establish that it is the report an operator believes is running.
# WHY : Trade-offs: editing `sql/verify/money_totals.sql` requires re-measuring this constant. The
#   cost is bounded by a test comparing the pin against the shipped file and reporting the measured
#   digest in its failure, so a forgotten pin fails with the value to paste rather than at an
#   operator's next run. Re-measure with `sha256sum data-migration/sql/verify/money_totals.sql`.
# WHY : Refactoring Rationale: this pin was re-measured when the query's own header comment was
#   corrected. That comment described the grants the batch role holds -- its reason for NOT running
#   this pass as that role -- and it said "V0 grants it SELECT, INSERT and UPDATE across ledger and
#   account". V0 grants SELECT on account and nothing more; the single account write the batch role
#   holds is UPDATE on account.accounts, granted by that service's own
#   V3__batch_account_write_grant.sql. The old text therefore overstated the privilege it was
#   arguing about, and it also wrote a bare "V3" that a reader could resolve to either that
#   migration or this directory's V3__verification_surfaces.sql. Correcting a comment moves the
#   digest exactly as correcting a statement would, which is the point of pinning identity rather
#   than shape.
MONEY_TOTAL_QUERY_DIGEST: Final[str] = (
    "cc302170ab8a8cf4889dbc56c9914eed82a582af840f70a64a4e86e4866513bf"
)

# WHY : Assumptions: the ONE relation the committed query is permitted to read is the aggregate-only
#   verification view, and naming it here is what makes "this text is harmless" a checkable property
#   rather than a claim about a file. `sql/V3__verification_surfaces.sql` creates that view as a
#   security-barrier view projecting a count, an exact NUMERIC sum and a strictly-negative row count
#   per money column -- no key, no identifier, no row-level value -- and the reporting role holds
#   SELECT on it and on no base table. A text reading anything else is therefore either not this
#   query or not entitled to run, and both are refusals rather than results.
MONEY_TOTAL_QUERY_RELATION: Final[str] = "reporting.v_verification_money_totals"

# WHY : Assumptions: the query executes under a statement timeout, set for the transaction only.
#   Five minutes is chosen against the work: the view aggregates five tables whose largest is the
#   three-hundred-thousand-row transaction master, which is seconds of sequential scan on the
#   smallest Aurora capacity the environments provision. The ceiling therefore never bounds a
#   healthy run; what it bounds is a pass that has become stuck behind a lock taken by the batch
#   chain it is meant to gate, so a verification step fails with a named timeout instead of holding
#   the nightly window open until an operator notices.
_STATEMENT_TIMEOUT_MILLISECONDS: Final[int] = 300_000

# WHY : Assumptions: read-only-ness is asserted by the SERVER for the transaction the query runs in,
#   in addition to the role holding no write privilege. The two guards fail differently and that is
#   why both are kept: the role is what makes writing impossible, and the transaction setting is
#   what makes an ATTEMPT to write fail loudly on a cluster where the role was mis-provisioned. The
#   spelling is `SET TRANSACTION`, not `SET SESSION CHARACTERISTICS`, so the setting lasts exactly
#   as long as the read it protects and cannot leak into a later use of the same connection.
_READ_ONLY_TRANSACTION_STATEMENT: Final[str] = "SET TRANSACTION READ ONLY"

# WHY : Assumptions: the two seed forms are named as a closed set, matching the choices `cli.py`
#   offers, because an extract's form is DECLARED and never sniffed. The two are distinguishable
#   only by inspecting bytes for characters outside the ASCII range, and an EBCDIC extract whose
#   records happen to be all-ASCII would sniff as text -- then decode to plausible wrong values
#   rather than to an error, which is the failure this pass exists to catch.
SOURCE_ENCODINGS: Final[tuple[str, ...]] = ("ascii", "ebcdic")

# WHY : Alternatives Considered: these two tokens -- and the report geometry, the three-state
#   verdict, the query-reading guards and the role and session checks further down -- REPRODUCE the
#   shape verification pass 1 established rather than importing it from
#   `carddemo_migration.verify.row_counts`. Importing was the obvious alternative and would have
#   removed the repetition; it was rejected because it would make pass 3 unable to run without pass
#   1's module loading first, and `cli.py` invokes each pass alone, so a failure in one pass's
#   baseline table would take the other pass down with it for no reason a reader could see. The
#   in-repo precedent is the SQL side of the same pair: `sql/verify/money_totals.sql` reproduces
#   `row_counts.sql`'s header geometry rather than sharing an include, for the same reason. The
#   accepted cost is that a change to one report's vocabulary has to be made twice; the tokens are
#   spelled identically so an operator reading both reports still reads one vocabulary, and a
#   visible token survives a copy into an issue tracker where an empty cell does not.
_ABSENT: Final[str] = "n/a"
_NOT_COMPARABLE: Final[str] = "not comparable"

# WHY : Assumptions: the expectation an unmeasurable column IS held to is named as a constant, so
#   the rendering and the rule that decides it use one wording. A column whose layout ships no seed
#   extract must be empty after the migration; the phrase appears in the report beside the verdict
#   so a reader learns what was actually checked rather than only that a comparison was impossible.
_EXPECTED_EMPTY: Final[str] = "expected empty:"

# WHY : Assumptions: the summary counts the lines that FAILED rather than the lines that mismatched,
#   because the two stopped being the same set when the expected-empty rule arrived: an unmeasurable
#   column can now fail with no source total to have disagreed with. Labelling that count
#   "mismatched" would name a comparison that never happened. The spelling matches
#   `row_counts._FAILING` so both reports in one run read as one vocabulary.
_FAILING: Final[str] = "failing"

# WHY : Assumptions: a migrated money column is required to be NUMERIC at the money scale, and the
#   pattern is a whitelist rather than a list of refused spellings. That refuses a binary
#   floating-point column type by construction, which matters because such a type cannot represent
#   ten cents exactly: its SUM would depend on the order the executor read the rows in, so two runs
#   over byte-identical data could differ in the last place and this pass would report a difference
#   that does not exist -- or hide one that does. AAP rule T3 forbids that representation across the
#   whole money path; the Java services hold it with an ArchUnit rule and SQL has no such linter, so
#   the report's own declared type is checked here.
_SQL_TYPE_PATTERN: Final[re.Pattern[str]] = re.compile(r"^NUMERIC\(\d+,2\)$")

# WHY : Assumptions: a money column's originating PICTURE must declare BOTH a sign and two decimal
#   places, and each half is checked for a different reason. Without the leading S the field carries
#   no sign representation at all, so its negative-row count could never be non-zero and comparing
#   one would be theatre; without the V99 the value is not carried at the money scale, so a total
#   compared at scale 2 would be comparing a different quantity.
_SIGNED_PICTURE_MARKER: Final[str] = "S9("
_MONEY_PICTURE_MARKER: Final[str] = "V99"

# WHY : Assumptions: only these two keywords may OPEN the query, and the pair is exact rather than a
#   list of refused verbs. A single statement beginning `SELECT` or `WITH` cannot modify data unless
#   one of its own expressions does, which the relation check below also catches, whereas
#   enumerating every write verb leaves the set open-ended: a future PostgreSQL statement type
#   nobody listed would pass a deny-list and fail this allow-list.
_READING_KEYWORDS: Final[frozenset[str]] = frozenset({"SELECT", "WITH"})

# WHY : Assumptions: a relation is recognised after FROM or JOIN, optionally schema-qualified, and
#   the pattern deliberately reads the COMMENT-STRIPPED text so the query's own prose cannot
#   contribute a match. It is a shape check rather than a parse: the accepted cost is that a
#   sub-select's own FROM is treated the same as a top-level one -- which is the safe direction,
#   because it means every relation anywhere in the text has to be accounted for.
_RELATION_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?i)\b(?:from|join)\s+([a-z_][a-z0-9_]*(?:\.[a-z_][a-z0-9_]*)?)"
)

# WHY : Assumptions: a common table expression is recognised by its `<name> [(columns)] AS (` form,
#   after either the opening WITH or a comma, which is how both shipped verification queries declare
#   theirs. Recognising them is what lets the relation check above insist on the allow-listed view
#   without also rejecting the query's own inline vocabulary -- `money_columns` and `aggregated` in
#   the money query, `expected` and `actual` in its row-count sibling.
_CTE_PATTERN: Final[re.Pattern[str]] = re.compile(
    r"(?i)(?:\bwith\b|,)\s*([a-z_][a-z0-9_]*)\s*(?:\([^)]*\))?\s+as\s*\("
)


class MoneyParityVerificationError(RuntimeError):
    """Base class for every way verification pass 3 can fail to reach a verdict.

    Purpose
    -------
    Give a caller one name to catch when it wants to distinguish "this pass could not run" from
    "this pass ran and the money disagrees". The second is not an error and is never raised: a
    disagreement is a reported outcome, because a run that stopped at the first differing column
    would hide every column after it.

    Parameters
    ----------
    None
        Constructed with a message like any :exc:`RuntimeError`.

    Returns
    -------
    None
        Exception classes are raised, not returned.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


class MoneyResultSetContractError(MoneyParityVerificationError):
    """Raised when the money-total query returns something other than its published contract.

    Purpose
    -------
    Refuse a result set this module cannot judge, instead of judging it anyway. Every case is a
    breach of the eight-column, nine-row contract stated in ``money_totals.sql``: the wrong number
    of columns, a label that is not text, a count that is not an exact whole number, a total that
    is not an exact decimal, a declared money column the report omits, a column the report carries
    that no target declares, or a row whose own eight values contradict one another.

    Parameters
    ----------
    None
        Constructed with a message naming the breach.

    Returns
    -------
    None
        Exception classes are raised, not returned.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


class MoneyQueryError(MoneyParityVerificationError):
    """Raised when the money-total query itself cannot be located or read.

    Purpose
    -------
    Separate a missing or unreadable query FILE from a database that refused, because the two have
    different remedies -- one is an incomplete checkout or an unpackaged ``sql`` directory, the
    other is a privilege or connectivity problem -- and a single error class would send an operator
    to the wrong one.

    Parameters
    ----------
    None
        Constructed with a message naming the path that was tried.

    Returns
    -------
    None
        Exception classes are raised, not returned.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


class MoneyParityVerdict(enum.Enum):
    """The three outcomes a money-total line can have.

    Purpose
    -------
    Model the outcome as THREE states rather than as a boolean, so that "no source extract exists
    to compare against" cannot collapse into either "agrees" or "differs". A boolean forces that
    collapse and both collapses are wrong: reported as agreement it claims a check that never ran,
    and reported as disagreement it fails a correct fresh load of ``ledger.transactions``, which
    the posting job fills and no seed extract populates.

    Parameters
    ----------
    None
        Enumeration members are values, not constructed by a caller.

    Returns
    -------
    None
        Enumeration classes are referenced, not returned.

    Raises
    ------
    None
        Declaring an enumeration raises nothing.
    """

    MATCH = "MATCH"
    MISMATCH = "MISMATCH"
    NO_SOURCE = "NO_SOURCE"

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
            True for :attr:`MATCH` and :attr:`MISMATCH`, both of which weighed a source total
            against a target total. False for :attr:`NO_SOURCE`, where there was nothing to weigh.

        Raises
        ------
        None
            Reading a member cannot fail.
        """
        return self is not MoneyParityVerdict.NO_SOURCE

    # WHY : Refactoring Rationale: this enumeration published a `verified` property, returning True
    #   for MATCH and NO_SOURCE and False only for MISMATCH, and it is REMOVED rather than
    #   corrected. The defect was where the decision lived, not how it was spelled: a member of
    #   this enumeration knows which of the three outcomes it is and knows nothing about the target
    #   table, so "NO_SOURCE is acceptable" could only ever be expressed here as
    #   unconditional -- and it is not unconditional. A money column whose layout ships no seed
    #   extract is expected to be EMPTY after the migration, so a nonzero total on it is stale data
    #   from an earlier run, a load into the wrong table, or a posting job that ran before
    #   verification, and every one of those was certified as a pass. The rule now lives on
    #   :class:`MoneyParityLine`, which holds the row count, the total and the negative-row count
    #   the rule needs. Removing the property rather than leaving it in place is deliberate: a
    #   published name that answers a question it cannot answer correctly is how the same defect
    #   comes back through a different caller.


@dataclass(frozen=True)
class MoneyColumn:
    """One migrated money column, bound to the copybook field the load reads it from.

    Purpose
    -------
    Carry the whole binding for one money column -- the target table and column, the layout and
    field that feed it, and that field's own descriptor -- so that no caller has to pair a column
    name with a copybook field for itself and mispair them.

    Parameters
    ----------
    target_table : str
        Schema-qualified target table, spelled as ``money_totals.sql`` spells it.
    money_column : str
        The column name the owning migration declares, as the loader's target maps it.
    layout_name : str
        The record layout whose extract feeds this column.
    field : layouts.FieldSpec
        The descriptor of the feeding field. Digit counts, byte offset, width, storage regime and
        sensitivity are read FROM it and never restated here.

    Returns
    -------
    MoneyColumn
        A new immutable binding of one target column to the field that feeds it.

    Raises
    ------
    MoneyParityVerificationError
        If the table, column or layout name is blank, or if the field is not a signed field of the
        zoned display regime -- which is what "money" means in this corpus.
    """

    target_table: str
    money_column: str
    layout_name: str
    field: layouts.FieldSpec

    def __post_init__(self) -> None:
        """Refuse a binding that does not describe a money column.

        Parameters
        ----------
        None
            Reads the four components just assigned.

        Returns
        -------
        None
            Validation succeeds silently; the binding is usable exactly when this returns.

        Raises
        ------
        MoneyParityVerificationError
            If any name is blank, or if the field is not a signed zoned display field.
        """
        # WHY : Assumptions: a money field is exactly a SIGNED field of the ZONED display regime.
        #   An unsigned display field is an identifier or a count -- a card number, a credit score
        #   -- and totalling one would produce a number with no meaning that a source-versus-target
        #   comparison would then solemnly confirm.
        # WHY : Assumptions: the checks RAISE rather than assert. `python -O` strips `assert`
        #   entirely, so an assertion is not a validation -- it is a validation that disappears in
        #   exactly the configuration an operator is most likely to run in production.
        if not self.target_table.strip() or not self.money_column.strip():
            raise MoneyParityVerificationError(
                "a money column must name both its target table and its column, because the"
                " report's own rows are keyed on that pair"
            )
        if not self.layout_name.strip():
            raise MoneyParityVerificationError(
                f"money column {self.target_table}.{self.money_column} names no record layout, so"
                " no source extract could be totalled against it"
            )
        if self.field.kind is not layouts.Kind.ZONED or not self.field.signed:
            raise MoneyParityVerificationError(
                f"field {self.field.name} feeds {self.target_table}.{self.money_column} but"
                f" declares kind {self.field.kind.name} and"
                f" signed={self.field.signed}; a money column is fed by a signed zoned display"
                " field, and totalling an identifier or a count would compare two meaningless"
                " numbers"
            )

    @property
    def qualified_column(self) -> str:
        """Report the schema-qualified table and column as one label.

        Parameters
        ----------
        None
            Reads :attr:`target_table` and :attr:`money_column`.

        Returns
        -------
        str
            The dotted label, for example ``account.accounts.curr_bal``.

        Raises
        ------
        None
            Joining two validated names cannot fail.
        """
        return f"{self.target_table}.{self.money_column}"

    @property
    def scale(self) -> int:
        """Report the number of implied decimal places the feeding field declares.

        Parameters
        ----------
        None
            Reads the field descriptor.

        Returns
        -------
        int
            The descriptor's declared fractional digit count.

        Raises
        ------
        None
            Reading a validated descriptor cannot fail.
        """
        # WHY : Assumptions: the scale is taken from the DESCRIPTOR and never by analogy with a
        #   sibling field. `DIS-INT-RATE PIC S9(04)V99` is the only such picture in the whole ETL
        #   and occupies SIX bytes where every balance occupies eleven or twelve; deriving its
        #   geometry from a twelve-byte neighbour would misread every rate in the disclosure-group
        #   extract while every other column still totalled correctly.
        return self.field.dec_digits

    @property
    def quantum(self) -> Decimal:
        """Report the exact decimal unit a total of this column is carried at.

        Parameters
        ----------
        None
            Reads :attr:`scale`.

        Returns
        -------
        Decimal
            One at the negative power of ten the field's fractional width declares, suitable as the
            argument to :meth:`decimal.Decimal.quantize`.

        Raises
        ------
        None
            Building an exact decimal cannot fail.
        """
        return Decimal(1).scaleb(-self.scale)

    def describe_field(self) -> str:
        """Render the feeding field as geometry alone, with no value and no bytes.

        Parameters
        ----------
        None
            Reads the field descriptor.

        Returns
        -------
        str
            The field name, its zero-based offset, its byte width and its storage regime, plus an
            explicit note when the descriptor marks the field sensitive.

        Raises
        ------
        None
            Rendering geometry cannot fail.
        """
        # WHY : Trade-offs: the diagnostic form here is stricter than the readers' masking -- it
        #   reports only name, offset, length and regime, and never a value, masked or otherwise.
        #   Every money field this module totals except the disclosure group's published interest
        #   rate is marked sensitive by its own descriptor, and a verification report is the
        #   artifact most likely to be pasted whole into an issue tracker. The accepted cost is that
        #   a mismatch tells an operator WHICH column and which field differ and not which record,
        #   which is what the checksum pass localises. The `sensitive` flag drives the closing
        #   clause rather than being ignored, so a reader can see that the withholding is a decision
        #   the descriptor made and not an omission.
        detail = (
            f"{self.field.name} offset={self.field.start} length={self.field.length}"
            f" kind={self.field.kind.name}"
        )
        if self.field.sensitive:
            return f"{detail} (value withheld: the descriptor marks this field sensitive)"
        return detail


def _derive_money_columns() -> Mapping[tuple[str, str], MoneyColumn]:
    """Derive every migrated money column from the loader's own target declarations.

    Purpose
    -------
    Build the (target table, column) to money-column inventory from the two authorities that
    already hold it -- the loader's field-to-column mapping and the layout registry's field
    descriptors -- so this module states no offset, no width and no column name of its own.

    Parameters
    ----------
    None
        Reads :data:`carddemo_migration.loaders.aurora.TARGETS` and the layout registry.

    Returns
    -------
    Mapping[tuple[str, str], MoneyColumn]
        Read-only mapping keyed by (schema-qualified table, column name).

    Raises
    ------
    MoneyParityVerificationError
        If a derived binding does not describe a money column, propagated from
        :meth:`MoneyColumn.__post_init__`.
    """
    # WHY : Assumptions: the key is the (table, column) PAIR rather than either half, because
    #   neither is unique on its own: `amount` names a column of two different ledger tables, and
    #   `account.accounts` contributes five columns. Keying on one half would silently collapse
    #   rows the report keeps separate.
    # WHY : Assumptions: a field is included only when the target actually MAPS it to a column. A
    #   signed field the loader does not carry forward has no column to be totalled against, so
    #   including it would put a line in the report that no result row could ever match.
    derived: dict[tuple[str, str], MoneyColumn] = {}
    for layout_name, target in TARGETS.items():
        spec = layouts.LAYOUTS.get(layout_name)
        if spec is None:
            # WHY : Assumptions: a target bound to no registered layout is skipped rather than
            #   refused, because `TARGETS` admits an unbound target constructed to exercise a
            #   projection, which owns no dataset and therefore no money column. Refusing here
            #   would make importing this module depend on the loader carrying no such target.
            continue
        for field in spec.fields:
            column = target.columns.get(field.name)
            if column is None or field.kind is not layouts.Kind.ZONED or not field.signed:
                continue
            derived[f"{target.schema}.{target.table}", column] = MoneyColumn(
                target_table=f"{target.schema}.{target.table}",
                money_column=column,
                layout_name=layout_name,
                field=field,
            )
    return MappingProxyType(derived)


@lru_cache(maxsize=1)
def money_columns() -> Mapping[tuple[str, str], MoneyColumn]:
    """Return the read-only inventory of every migrated money column, deriving it once.

    Purpose
    -------
    Publish the (schema-qualified table, column) to money-column mapping this pass judges a report
    against, and derive it on FIRST USE rather than at import.

    Parameters
    ----------
    None
        Reads :data:`carddemo_migration.loaders.aurora.TARGETS` and the layout registry through
        :func:`_derive_money_columns`.

    Returns
    -------
    Mapping[tuple[str, str], MoneyColumn]
        Read-only mapping keyed by (schema-qualified table, column name), memoised so the
        derivation runs once per process.

    Raises
    ------
    MoneyParityVerificationError
        If a derived binding does not describe a money column, propagated from
        :meth:`MoneyColumn.__post_init__`. Raised at the first CALL rather than at import, which is
        the whole point of the deferral.
    """
    # WHY : Refactoring Rationale: this replaces a module-level `MONEY_COLUMNS` constant that was
    #   built at IMPORT time, and the deferral matters for a reason outside this module. `cli.py`
    #   imports the verification passes to register its subcommands, so a defect in this derivation
    #   -- a target whose declaration stopped describing a money column, a layout renamed under it
    #   -- raised while the module was being imported, which took down every unrelated verb with
    #   it: `list-datasets`, `--help` and `stage-dataset` could not run because a money inventory
    #   they never touch could not be built. Deriving on first call confines the failure to the pass
    #   that needs the inventory.
    # WHY : Trade-offs: memoised with `lru_cache` rather than recomputed per call. The derivation
    #   walks eleven targets and their fields, so recomputing it inside a per-column loop would be
    #   wasteful; and the inputs are module-level declarations that cannot change during a process,
    #   so a cache cannot go stale. The cost is one cached mapping per process, which the read-only
    #   proxy makes safe to share.
    return _derive_money_columns()


def declared_money_columns() -> Mapping[tuple[str, str], MoneyColumn]:
    """Return the money-column inventory, having checked it is the nine over five it must be.

    Purpose
    -------
    Corroborate the derived inventory against the two counts ``money_totals.sql`` publishes, before
    any result set is judged against it, so that a drift in the loader's declarations is reported
    as a drift rather than silently changing what a passing report means.

    Parameters
    ----------
    None
        Reads the memoised inventory :func:`money_columns` publishes.

    Returns
    -------
    Mapping[tuple[str, str], MoneyColumn]
        The same read-only inventory, unchanged, when it agrees with both published counts.

    Raises
    ------
    MoneyParityVerificationError
        If the inventory does not hold exactly :data:`MONEY_TOTAL_COLUMN_COUNT` columns over
        exactly :data:`MONEY_TOTAL_TABLE_COUNT` tables, or if any column's declared fractional
        width is not the money scale.
    """
    # WHY : Assumptions: both counts are checked, and the corroboration is genuinely independent.
    #   The nine is what the baseline declares -- grepping the eleven base copybooks for a signed
    #   fixed-point PICTURE returns exactly nine fields, five in CVACT01Y and one each in CVTRA05Y,
    #   CVTRA06Y, CVTRA01Y and CVTRA02Y -- and the five is the number of migrated tables those
    #   fields land in. This function reaches the same two figures from the LOADER's declarations
    #   instead, so agreement is two sources agreeing rather than one source repeated.
    inventory = money_columns()
    if len(inventory) != MONEY_TOTAL_COLUMN_COUNT:
        raise MoneyParityVerificationError(
            f"the loader declares {len(inventory)} money columns and"
            f" {MONEY_TOTAL_QUERY_NAME} reports {MONEY_TOTAL_COLUMN_COUNT};"
            f" the declared columns are {sorted(inventory)}"
        )
    tables = {column.target_table for column in inventory.values()}
    if len(tables) != MONEY_TOTAL_TABLE_COUNT:
        raise MoneyParityVerificationError(
            f"the loader's money columns span {len(tables)} tables and {MONEY_TOTAL_QUERY_NAME}"
            f" reports {MONEY_TOTAL_TABLE_COUNT}; the spanned tables are {sorted(tables)}"
        )
    # WHY : Assumptions: the money scale is checked per column rather than assumed from the report,
    #   because it is the scale the SOURCE side quantizes at. A field declaring three fractional
    #   digits would be totalled at a thousandth and compared against a column that stores
    #   hundredths, so the two sides would differ by a rounding this pass had introduced itself.
    off_scale = sorted(
        f"{column.qualified_column} at scale {column.scale}"
        for column in inventory.values()
        if column.scale != MONEY_SCALE
    )
    if off_scale:
        raise MoneyParityVerificationError(
            f"these money columns are not carried at scale {MONEY_SCALE}: {off_scale}; a total"
            " taken at one scale and compared against a column stored at another would differ by a"
            " rounding this pass had introduced"
        )
    return inventory


@dataclass(frozen=True)
class MoneyParity:
    """The outcome of one money-total comparison.

    Parameters
    ----------
    record_name : str
        The layout name of the dataset compared.
    qualified_column : str
        The schema-qualified table and column totalled in the target.
    source_total : Decimal
        The exact total computed from the source bytes, at scale 2.
    target_total : Decimal
        The total the database reported, at scale 2.
    records : int
        How many source records contributed.

    Returns
    -------
    MoneyParity
        A new immutable outcome of one money-total comparison.

    Raises
    ------
    None
        Construction validates nothing: every component is produced by the two totalling functions
        below, each of which refuses an inexact value at the point it is read.
    """

    record_name: str
    qualified_column: str
    source_total: Decimal
    target_total: Decimal
    records: int

    @property
    def matched(self) -> bool:
        """Report whether the two totals are exactly equal at scale 2.

        Parameters
        ----------
        None
            Reads the two totals.

        Returns
        -------
        bool
            True when the totals agree to the cent.

        Raises
        ------
        None
            Comparing two exact decimals cannot fail.
        """
        return self.source_total == self.target_total

    @property
    def difference(self) -> Decimal:
        """Report the target's excess over the source.

        Parameters
        ----------
        None
            Reads the two totals.

        Returns
        -------
        Decimal
            ``target_total - source_total`` at scale 2.

        Raises
        ------
        None
            Exact decimal subtraction at a fixed scale cannot fail.
        """
        return (self.target_total - self.source_total).quantize(self.source_total)

    def describe(self) -> str:
        """Render the outcome as one line.

        Parameters
        ----------
        None
            Reads the outcome.

        Returns
        -------
        str
            A line naming the dataset, the column and both totals.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        # WHY : Trade-offs: these AGGREGATES may be rendered where a field value may not. A total
        #   over a whole dataset is not attributable to any individual, which is exactly the
        #   property that makes a total publishable and a balance not; the accepted cost is that the
        #   line says which column disagrees and not which record, which pass 2 localises.
        verdict = "MATCH" if self.matched else "DIFFER"
        return (
            f"{verdict} {self.record_name} -> {self.qualified_column}"
            f" source={self.source_total} target={self.target_total}"
            f" difference={self.difference:+} records={self.records}"
        )


def total_source_money(
    records: Iterable[Mapping[str, str | Decimal | bytes]], fields: Sequence[str]
) -> tuple[Decimal, int]:
    """Total named money fields across decoded records, exactly.

    Parameters
    ----------
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records, as a reader yields them.
    fields : Sequence[str]
        The money field names to total.

    Returns
    -------
    tuple[Decimal, int]
        The total at scale 2, and the number of records contributing.

    Raises
    ------
    ValueError
        If no field is named, or a named field's value is not an exact decimal -- which means the
        field is not a money field and totalling it would be meaningless.
    KeyError
        If a record does not carry a named field.
    """
    ordered = tuple(fields)
    if not ordered:
        raise ValueError(
            "a money total over no field is zero for every dataset and would report parity"
            " between datasets that differ; name the money fields to total"
        )
    total = Decimal(0).scaleb(0).quantize(Decimal(1).scaleb(-MONEY_SCALE))
    counted = 0
    for record in records:
        for field in ordered:
            value = record[field]
            if not isinstance(value, Decimal):
                raise ValueError(
                    f"field {field} of {type(value).__name__} is not an exact decimal, so it is"
                    " not a money field; totalling a character field would compare a number"
                    " against text"
                )
            total += value
        counted += 1
    # WHY : Assumptions: the running total is exact throughout -- Decimal addition of scale-2
    #   values is exact and never rounds -- and the final quantize only fixes the SCALE for
    #   comparison. No rounding mode is supplied because none can be needed; if one were, the
    #   inputs would not have been scale-2 money.
    return total.quantize(Decimal(1).scaleb(-MONEY_SCALE)), counted


def total_source_fields(
    records: Iterable[Mapping[str, str | Decimal | bytes]],
    fields: Sequence[str],
) -> Mapping[str, tuple[Decimal, int]]:
    """Total several money fields of one dataset in a SINGLE pass over its records.

    Purpose
    -------
    Let a caller comparing every money field of a dataset read the extract once. Totalling one field
    at a time forces either a re-read per field or a materialised list of every record, and a seed
    extract is hundreds of kilobytes today and is the same code path a production extract runs
    through -- so both shapes make peak memory or read cost scale with the number of money columns.

    Parameters
    ----------
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        The decoded source records. Consumed exactly once, and never materialised.
    fields : Sequence[str]
        The money field names to total, each of which must be present on every record.

    Returns
    -------
    Mapping[str, tuple[Decimal, int]]
        Per field, its exact total at scale 2 and the number of records whose value was negative.
        Read-only. A field list of length zero yields an empty mapping.

    Raises
    ------
    ValueError
        If a named field is absent from a record, or holds a value that is not an exact
        :class:`~decimal.Decimal` -- which would mean a character field was being totalled.
    """
    # WHY : Assumptions: the negative-row count is returned per field alongside the total, matching
    #   what the committed money-total query reports per column. A total alone cannot distinguish a
    #   sign-overpunch fault that flipped one debit into a credit and one credit into a debit: that
    #   leaves the sum unchanged and the two counts different, so returning only the sum would miss
    #   the one corruption this pass exists to catch.
    ordered = tuple(fields)
    zero = Decimal(1).scaleb(-MONEY_SCALE)
    totals: dict[str, Decimal] = {field: Decimal(0).quantize(zero) for field in ordered}
    negatives: dict[str, int] = dict.fromkeys(ordered, 0)
    for record in records:
        for field in ordered:
            if field not in record:
                raise ValueError(
                    f"field {field} is absent from a source record, so it cannot be totalled;"
                    " the reader and the requested field list disagree about this record's shape"
                )
            value = record[field]
            if not isinstance(value, Decimal):
                raise ValueError(
                    f"field {field} of {type(value).__name__} is not an exact decimal, so it is"
                    " not a money field; totalling a character field would compare a number"
                    " against text"
                )
            totals[field] += value
            if value < 0:
                negatives[field] += 1
    # WHY : Assumptions: the running totals are exact throughout -- Decimal addition of scale-2
    #   values never rounds -- and the final quantize only fixes the SCALE for comparison. No
    #   rounding mode is supplied because none can be needed; if one were, the inputs would not have
    #   been scale-2 money.
    return MappingProxyType(
        {field: (totals[field].quantize(zero), negatives[field]) for field in ordered}
    )


def total_target_money(connection: Any, schema: str, table: str, column: str) -> Decimal:
    """Read the target's own total for one money column, refusing an inexact representation.

    Parameters
    ----------
    connection : Any
        An open database connection.
    schema : str
        The owning schema.
    table : str
        The table within it.
    column : str
        The money column to total.

    Returns
    -------
    Decimal
        The total, as an exact decimal already carrying the money scale. An empty table totals to
        zero at that same scale rather than to null.

    Raises
    ------
    ValueError
        If the query returns no row at all.
    MoneyResultSetContractError
        If the value the driver returned is not an exact decimal at the money scale -- a bool, an
        int, a binary float, a string, or a decimal of the wrong exponent. See
        :func:`_require_exact_money` for why each is refused rather than converted.
    """
    # WHY : Assumptions: COALESCE wraps the SUM because SUM over zero rows is NULL, not zero, and a
    #   null total compared against an exact zero would report a difference on an empty table that
    #   has nothing wrong with it.
    # WHY : Refactoring Rationale: the substituted zero is written `0.00` and the whole expression
    #   is CAST to NUMERIC at the money scale, where this read `COALESCE(SUM(col), 0)` and then
    #   coerced whatever came back through `Decimal(...)`. Two things were wrong with that pairing.
    #   An integer literal makes the coalesced expression's type numeric of scale ZERO, so an empty
    #   table answered `0` rather than `0.00` and the pass could not require a scale it had itself
    #   made impossible. And the coercion accepted a binary float: `Decimal(0.1)` is
    #   0.1000000000000000055511151231257827, which `quantize` then rounded to a plausible `0.10` --
    #   so a column whose SUM had been computed over a floating-point representation was certified
    #   as exact, which is precisely the substitution AAP rule T3 exists to forbid. The cast makes
    #   the server state the scale, and `_require_exact_money` refuses everything that is not it.
    #   The scale-2 spelling matches `sql/V3__verification_surfaces.sql`, whose money view writes
    #   `COALESCE(SUM(...), 0.00)` for the same reason.
    statement = (
        f"SELECT CAST(COALESCE(SUM({quote_identifier(column)}), 0.00)"  # noqa: S608
        f" AS NUMERIC({_TARGET_TOTAL_PRECISION},{MONEY_SCALE}))"
        f" FROM {quote_identifier(schema)}.{quote_identifier(table)}"
    )
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
            f"totalling {schema}.{table}.{column} returned no row at all; an aggregate always"
            " returns one, so the connection is not behaving as a database connection"
        )
    return _require_exact_money(row[0], f"{schema}.{table}.{column}")


def compare_money_totals(
    connection: Any,
    record_name: str,
    schema: str,
    table: str,
    column: str,
    records: Iterable[Mapping[str, str | Decimal | bytes]],
    fields: Sequence[str],
) -> MoneyParity:
    """Compare a dataset's exact money total against the target column's own total.

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
    column : str
        The target money column.
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded source records.
    fields : Sequence[str]
        The source money field names to total.

    Returns
    -------
    MoneyParity
        The outcome, whether or not the totals agree. A disagreement is reported rather than
        raised, so one run can report every dataset.

    Raises
    ------
    ValueError
        If a named field is not money, or the target total cannot be read.
    KeyError
        If a record does not carry a named field.
    """
    source_total, counted = total_source_money(records, fields)
    return MoneyParity(
        record_name=record_name,
        qualified_column=f"{schema}.{table}.{column}",
        source_total=source_total,
        target_total=total_target_money(connection, schema, table, column),
        records=counted,
    )


def _money_columns_of(layout_name: str) -> tuple[MoneyColumn, ...]:
    """List the money columns one record layout feeds, in declaration order.

    Parameters
    ----------
    layout_name : str
        A record layout name, as the layout registry and the loader's targets key them.

    Returns
    -------
    tuple[MoneyColumn, ...]
        Every money column fed by that layout, ordered by the feeding field's byte offset so the
        order is the record's own and not a mapping's iteration order.

    Raises
    ------
    None
        A layout feeding no money column yields an empty tuple, which the callers report in their
        own terms rather than treating as an error here.
    """
    # WHY : Assumptions: the order is the RECORD's -- by byte offset -- rather than the inventory's
    #   insertion order, so the five account columns are reported in the order a reader of
    #   CVACT01Y meets them. That is the order an operator comparing this report against a copybook
    #   reads in, and a mapping's order would change if the loader's declarations were reordered.
    return tuple(
        sorted(
            (column for column in money_columns().values() if column.layout_name == layout_name),
            key=lambda column: column.field.start,
        )
    )


def _ships_seed_extract(layout_name: str) -> bool:
    """Report whether a record layout has a committed seed extract to be totalled from.

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
        Propagated from :func:`carddemo_migration.readers.reader_module` if no reader owns the
        layout, with a message enumerating the layouts that do.
    """
    # WHY : Refactoring Rationale: the rule itself now lives in
    #   `carddemo_migration.readers.ships_committed_extract` and this delegates to it, where it used
    #   to read the reader's attribute here with its own default. It moved because a second caller
    #   appeared: the combined verification gate must decide which registered datasets it may read
    #   an extract for, and two copies of "does an extract exist" is how the gate comes to offer an
    #   extract this pass refuses. The function is kept as a name in this module because its callers
    #   below read as money-pass logic, and it is one delegation rather than a duplicated default.
    return ships_committed_extract(layout_name)


@dataclass(frozen=True)
class SourceExtract:
    """One local seed extract to recompute money totals from.

    Purpose
    -------
    Name a source dataset completely enough to read it -- which layout describes it, where it is,
    and which of the two seed forms it is in -- so that the source side of this pass is INJECTED by
    the caller rather than discovered from the environment.

    Parameters
    ----------
    layout_name : str
        The record layout describing the extract. Must be a layout a reader owns and must feed at
        least one money column, because an extract with no money in it has nothing to contribute.
    path : pathlib.Path
        The extract file to read. Read-only: nothing in this module writes, re-encodes or
        normalises a source dataset.
    encoding : str
        One of :data:`SOURCE_ENCODINGS`. Declared, never sniffed.

    Returns
    -------
    SourceExtract
        A new immutable, validated description of one source extract to total.

    Raises
    ------
    MoneyParityVerificationError
        If no reader owns the layout, if the layout feeds no money column, or if the form is not
        one of the two declared encodings.
    """

    layout_name: str
    path: pathlib.Path
    encoding: str

    def __post_init__(self) -> None:
        """Refuse an extract this pass could not total.

        Parameters
        ----------
        None
            Reads the three components just assigned.

        Returns
        -------
        None
            Validation succeeds silently; the extract is usable exactly when this returns.

        Raises
        ------
        MoneyParityVerificationError
            If the layout is unknown to the readers, feeds no money column, or the encoding is
            outside the closed set.
        """
        # WHY : Assumptions: membership is checked against the READERS' dispatch mapping rather
        #   than against the layout registry, because a layout the registry knows but no reader
        #   owns cannot be decoded at all -- the failure would otherwise surface as a KeyError from
        #   inside the read, naming a mapping the caller never mentioned.
        if self.layout_name not in DATASET_READERS:
            raise MoneyParityVerificationError(
                f"layout {self.layout_name!r} is not in the readers' dispatch mapping, so no"
                f" extract of it can be decoded; the layouts with a reader are"
                f" {sorted(DATASET_READERS)}"
            )
        if not _money_columns_of(self.layout_name):
            raise MoneyParityVerificationError(
                f"layout {self.layout_name!r} feeds no money column, so totalling its extract"
                " would contribute nothing to a money-parity report; the layouts that do are"
                f" {sorted({column.layout_name for column in money_columns().values()})}"
            )
        # WHY : Trade-offs: BOTH forms are accepted and neither is preferred here, even though the
        #   EBCDIC extracts are authoritative wherever both exist -- and one of the two documented
        #   twin divergences changes money, so the choice has a visible cost. The disclosure-group
        #   `DEFAULT` row carries a rate of 15.00 in the EBCDIC extract and 0.00 in the ASCII twin,
        #   which is the whole of the 15.00 by which a total over the two forms differs; the
        #   interest calculation falls back to exactly that row, so an operator who totals the ASCII
        #   twin against a database loaded from the EBCDIC original reads a real 15.00 difference
        #   that is an artefact of the source form and not a load defect. Refusing the ASCII form
        #   outright was the alternative and was rejected: the twin is committed, an operator
        #   holding only it can still verify eight of the nine columns exactly, and a pass that
        #   refused to run at all would leave them unverified. `data-migration/README.md` section 8
        #   records the divergence measurement, and neither extract may be edited to reconcile them
        #   because `app/**` is reference-only.
        if self.encoding not in SOURCE_ENCODINGS:
            raise MoneyParityVerificationError(
                f"extract of {self.layout_name} declares form {self.encoding!r}, which is outside"
                f" {list(SOURCE_ENCODINGS)}; the form is declared rather than sniffed because an"
                " all-ASCII EBCDIC extract would sniff as text and decode to plausible wrong"
                " values"
            )

    def records(self) -> Iterable[Mapping[str, str | Decimal | bytes]]:
        """Open the extract and yield its decoded records, one forward pass.

        Parameters
        ----------
        None
            Reads the extract's own three components.

        Returns
        -------
        Iterable[Mapping[str, str | Decimal | bytes]]
            A lazy iterator of decoded records, as the readers' own entry points yield them. Money
            fields arrive as :class:`decimal.Decimal` at their declared scale.

        Raises
        ------
        LayoutError
            Propagated if the layout cannot be decoded from text at all, for an ASCII extract.
        RecordLengthError
            Propagated if the file does not divide into whole records of the declared length.
        OSError
            Propagated if the file cannot be opened or read.
        """
        # WHY : Refactoring Rationale: both forms are now decoded through the module that OWNS the
        #   record, resolved by `readers.dataset_reader`, where both went through the generic
        #   `readers.factory.RecordReader` built from the layout. The rationale this replaces argued
        #   the generic reader was necessary because the readers' dispatch mapping held only the
        #   EBCDIC entry points, "so dispatching through it would leave an operator holding the
        #   committed `app/data/ASCII` twin unable to run this pass at all". That was true of the
        #   package it was written against and is false of this one: `ASCII_DATASET_READERS` now
        #   publishes the character entry point of every reader that has one, and all five layouts
        #   that feed a money column -- ACCOUNT, DALYTRAN, DISGROUP, TCATBAL and TRAN -- are among
        #   them, so no form and no operator loses a route. What the change buys is that this pass
        #   and `cli.py` reach the same decoder: a generic reader carries the geometry and the
        #   suppression contract and NONE of the per-record policy the owning modules add, and
        #   `transaction`'s absent-extract semantics in particular matter here, because
        #   `ledger.transactions` has no committed extract in either corpus.
        # WHY : Assumptions: the concern the old rationale was right about is PRESERVED -- both
        #   forms still go through ONE implementation, because that implementation is now the one
        #   reader module that owns the record and publishes both of its entry points. A divergence
        #   between two decoders is precisely the defect class this pass exists to detect, so the
        #   pass must not introduce one of its own; dispatching by corpus within a single owning
        #   module keeps that property rather than trading it away.
        # WHY : Assumptions: EBCDIC is decoded through the reader, which decodes per fixed-width
        #   FIELD through `copybook.ebcdic_codec`, and never per record. A record-wide character
        #   decode succeeds and yields a plausible string, so a sign overpunch byte and an embedded
        #   low value are silently replaced and every money value after them is wrong with nothing
        #   raised.
        return dataset_reader(self.layout_name, self.encoding)(self.path)


@dataclass(frozen=True)
class SourceMoneyTotal:
    """One money column's total and negative-row count, recomputed from the source bytes.

    Purpose
    -------
    Carry both halves of the source-side measurement together, because neither is evidence on its
    own: a total can balance across a wholesale sign flip, and a negative-row count says nothing
    about magnitude.

    Parameters
    ----------
    layout_name : str
        The layout the extract was decoded against.
    field_name : str
        The copybook field totalled, exactly as the copybook spells it.
    total : Decimal
        The exact total of that field across the extract, at the field's declared scale.
    negative_rows : int
        How many records carried a strictly negative value in that field.
    records : int
        How many records were read.

    Returns
    -------
    SourceMoneyTotal
        A new immutable, self-consistent source-side measurement of one money column.

    Raises
    ------
    MoneyParityVerificationError
        If the total is not an exact decimal, if either count is not a non-negative whole number,
        or if more records were negative than were read.
    """

    layout_name: str
    field_name: str
    total: Decimal
    negative_rows: int
    records: int

    def __post_init__(self) -> None:
        """Refuse a source measurement that contradicts itself.

        Parameters
        ----------
        None
            Reads the five components just assigned.

        Returns
        -------
        None
            Validation succeeds silently; the measurement is usable exactly when this returns.

        Raises
        ------
        MoneyParityVerificationError
            If the total is not a :class:`decimal.Decimal`, if either count is not a non-negative
            whole number, or if the negative count exceeds the record count.
        """
        # WHY : Assumptions: the total is required to BE a Decimal rather than merely to look
        #   numeric, and the check is here so that no construction path can bypass it. A binary
        #   float cannot represent ten cents exactly, so one reaching this side would drift from the
        #   NUMERIC total the database holds by an amount that grows with the row count -- and this
        #   pass would report that drift as a load defect, or absorb a real one into it.
        if not isinstance(self.total, Decimal):
            raise MoneyParityVerificationError(
                f"the source total of {self.layout_name}.{self.field_name} is"
                f" {type(self.total).__name__} and not an exact decimal; money is exact fixed"
                " point at every hop, so an inexact total is refused rather than rounded"
            )
        # WHY : Assumptions: `bool` is refused before `int` even though it IS an `int` in Python.
        #   `True` would otherwise pass as a count of one, so a double returning a boolean where a
        #   count belongs would be silently coerced into the smallest plausible count.
        for label, count in (("negative_rows", self.negative_rows), ("records", self.records)):
            if isinstance(count, bool) or not isinstance(count, int) or count < 0:
                # WHY : Trade-offs: the offending TYPE is named and the value is not, which is the
                #   same discipline `describe_field` applies. A count is not itself sensitive, but
                #   a caller that mis-wired a field value into this argument would have that value
                #   echoed into a retained log by a message written to help it -- so the message is
                #   built from geometry and type alone, at the cost of one round trip for whoever
                #   has to find which value was wrong.
                raise MoneyParityVerificationError(
                    f"the {label} of {self.layout_name}.{self.field_name} is"
                    f" {type(count).__name__} and not a non-negative whole number; a count of"
                    " records cannot be fractional, negative or a boolean"
                )
        if self.negative_rows > self.records:
            raise MoneyParityVerificationError(
                f"{self.layout_name}.{self.field_name} reports {self.negative_rows} negative rows"
                f" out of {self.records} read; the negative rows are a subset of the records, so"
                " the two counts were not taken over the same pass"
            )


def _total_source_columns(
    records: Iterable[Mapping[str, str | Decimal | bytes]], columns: Sequence[MoneyColumn]
) -> tuple[SourceMoneyTotal, ...]:
    """Total several money columns of one record layout in a single forward pass.

    Parameters
    ----------
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records of one layout, as a reader yields them.
    columns : Sequence[MoneyColumn]
        The money columns to total. Must be non-empty and must all belong to one layout.

    Returns
    -------
    tuple[SourceMoneyTotal, ...]
        One measurement per column, in the order the columns were given.

    Raises
    ------
    MoneyParityVerificationError
        If no column is named, if the columns span more than one layout, if a record does not carry
        a named field, or if a field's value is not an exact decimal.
    """
    ordered = tuple(columns)
    if not ordered:
        raise MoneyParityVerificationError(
            "a source total over no column is zero for every extract and would report parity"
            " between extracts that differ; name the money columns to total"
        )
    layout_names = {column.layout_name for column in ordered}
    if len(layout_names) != 1:
        raise MoneyParityVerificationError(
            f"these money columns span {sorted(layout_names)}; one pass reads one extract, so"
            " columns of two layouts cannot be totalled over it"
        )
    layout_name = ordered[0].layout_name
    # WHY : Alternatives Considered: every column is accumulated in ONE pass over the records,
    #   rather than one pass per column. The reader is a generator by design, so a second pass over
    #   the same iterator yields nothing and would total zero for every column after the first -- a
    #   difference of zero that reads as agreement. Materialising the records into a list was the
    #   other alternative and was rejected because these extracts are read as streams precisely so
    #   that the seed and a production-sized extract behave identically here.
    totals = {column.money_column: Decimal(0).quantize(column.quantum) for column in ordered}
    negatives = dict.fromkeys((column.money_column for column in ordered), 0)
    counted = 0
    for record in records:
        for column in ordered:
            value = _require_source_money(record, column)
            totals[column.money_column] += value
            # WHY : Assumptions: the predicate is strictly less than zero, mirroring the aggregate
            #   view's `COUNT(*) FILTER (WHERE col < 0)` exactly. A `'}'`-signed zero therefore
            #   counts on NEITHER side: `zoned.py` decodes it to an unsigned zero by documented
            #   cross-language parity policy, and PostgreSQL NUMERIC has no signed zero for the
            #   filter to catch. Using a sign-bit test here instead would count it on this side
            #   alone and report a defect that no load could ever avoid.
            if value < 0:
                negatives[column.money_column] += 1
        counted += 1
    return tuple(
        SourceMoneyTotal(
            layout_name=layout_name,
            field_name=column.field.name,
            total=totals[column.money_column].quantize(column.quantum),
            negative_rows=negatives[column.money_column],
            records=counted,
        )
        for column in ordered
    )


def _require_source_money(
    record: Mapping[str, str | Decimal | bytes], column: MoneyColumn
) -> Decimal:
    """Read one money field out of a decoded record, refusing anything inexact.

    Parameters
    ----------
    record : Mapping[str, str | Decimal | bytes]
        One decoded record.
    column : MoneyColumn
        The money column whose feeding field is read.

    Returns
    -------
    Decimal
        The field's exact value.

    Raises
    ------
    MoneyParityVerificationError
        If the record does not carry the field, or the value is not an exact decimal.
    """
    # WHY : Assumptions: a missing field is reported against the field's GEOMETRY rather than by
    #   letting a KeyError escape, because the commonest cause is that the extract was decoded
    #   against the wrong layout -- and the offset and width in the message are what distinguish
    #   that from a reader that dropped the field. No value and no byte is echoed either way.
    if column.field.name not in record:
        raise MoneyParityVerificationError(
            f"a record of {column.layout_name} does not carry {column.describe_field()}, so"
            f" {column.qualified_column} cannot be totalled from this extract"
        )
    value = record[column.field.name]
    if not isinstance(value, Decimal):
        raise MoneyParityVerificationError(
            f"{column.describe_field()} decoded to {type(value).__name__} and not to an exact"
            f" decimal, so it is not the money field {column.qualified_column} loads from;"
            " totalling a character field would compare a number against text"
        )
    return value


def total_source_column(
    records: Iterable[Mapping[str, str | Decimal | bytes]], column: MoneyColumn
) -> SourceMoneyTotal:
    """Total one money column from decoded records, with its negative-row count.

    Purpose
    -------
    Be the smallest pure unit of the source side, so a caller -- or a test with no database and no
    extract -- can measure exactly what this pass compares against the report.

    Parameters
    ----------
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records of the column's own layout.
    column : MoneyColumn
        The money column to total.

    Returns
    -------
    SourceMoneyTotal
        The exact total, the count of strictly negative records, and the record count.

    Raises
    ------
    MoneyParityVerificationError
        If a record does not carry the field, or a value is not an exact decimal.
    """
    return _total_source_columns(records, (column,))[0]


def read_source_totals(
    extracts: Iterable[SourceExtract],
) -> Mapping[tuple[str, str], SourceMoneyTotal]:
    """Recompute every money total the given extracts can supply, from their own bytes.

    Purpose
    -------
    Produce the source half of this pass by re-reading the extracts through the readers, so the two
    sides of the comparison share no code path beyond the copybook geometry itself.

    Parameters
    ----------
    extracts : Iterable[SourceExtract]
        The extracts to read, at most one per layout.

    Returns
    -------
    Mapping[tuple[str, str], SourceMoneyTotal]
        Read-only mapping from (schema-qualified table, column) to that column's source-side
        measurement, so it keys exactly as the report's own rows do.

    Raises
    ------
    MoneyParityVerificationError
        If two extracts name one layout, if a record does not carry a money field, or if a value is
        not an exact decimal.
    LayoutError
        Propagated from the reader if a layout cannot be decoded from the declared form.
    RecordLengthError
        Propagated from the reader if an extract does not divide into whole records.
    OSError
        Propagated if an extract cannot be read.
    """
    measured: dict[tuple[str, str], SourceMoneyTotal] = {}
    seen: dict[str, pathlib.Path] = {}
    for extract in extracts:
        # WHY : Assumptions: two extracts for one layout are refused rather than the last one
        #   winning. They would be two different files claiming to be the same dataset, and
        #   whichever the iteration order happened to end on would silently become the baseline the
        #   load was certified against.
        if extract.layout_name in seen:
            raise MoneyParityVerificationError(
                f"two extracts name layout {extract.layout_name}: {seen[extract.layout_name]} and"
                f" {extract.path}; one dataset has one source, and letting the later one win would"
                " make the verdict depend on iteration order"
            )
        seen[extract.layout_name] = extract.path
        columns = _money_columns_of(extract.layout_name)
        for column, measurement in zip(
            columns, _total_source_columns(extract.records(), columns), strict=True
        ):
            measured[column.target_table, column.money_column] = measurement
    return MappingProxyType(measured)


def _require_whole_number(value: object, column: str, label: str) -> int:
    """Convert one result-set value to an exact integer, refusing anything inexact.

    Parameters
    ----------
    value : object
        The value the driver returned for that column.
    column : str
        The result-set column's name, used only in the failure message.
    label : str
        The report line being parsed, used only in the failure message.

    Returns
    -------
    int
        The value as an exact integer.

    Raises
    ------
    MoneyResultSetContractError
        If the value is a ``bool``, a non-integral :class:`~decimal.Decimal`, or any other type
        than those. The message names the column, the line and the offending type, and never the
        value itself.
    """
    # WHY : Assumptions: `bool` is refused before `int` even though it IS an `int` in Python. `True`
    #   would otherwise parse as a count of one, and a driver or a double returning a boolean where
    #   a count belongs is a contract breach that must be reported rather than silently coerced into
    #   the smallest plausible count.
    # WHY : Alternatives Considered: a binary floating-point value is refused outright rather than
    #   converted through `int()`. A count arrives as `bigint`, and beyond 2**53 such a value
    #   cannot represent one exactly -- so accepting it would let a report certify a load on the
    #   strength of a number that had already lost its last digits. Decimal is accepted because a
    #   driver may hand back `numeric` as one and it is exact, but only when it is integral: a
    #   fractional row count is not a row count.
    if isinstance(value, bool):
        raise MoneyResultSetContractError(
            f"column {column} of the {label} line is a bool, which is not a count; the money-total"
            " query projects bigint in both of its count columns"
        )
    if isinstance(value, int):
        return value
    if isinstance(value, Decimal) and value == value.to_integral_value():
        return int(value)
    raise MoneyResultSetContractError(
        f"column {column} of the {label} line is {type(value).__name__} and is not an exact whole"
        " number; a count must be exact, so it is refused rather than rounded"
    )


def _require_exact_total(value: object, label: str) -> Decimal:
    """Convert one result-set total to an exact decimal, refusing anything inexact.

    Parameters
    ----------
    value : object
        The value the driver returned for the ``total`` column.
    label : str
        The report line being parsed, used only in the failure message.

    Returns
    -------
    Decimal
        The total as an exact decimal.

    Raises
    ------
    MoneyResultSetContractError
        If the value is a ``bool``, an ``int``, or anything other than a :class:`~decimal.Decimal`.
        The message names the line and the offending type, and never the value itself.
    """
    # WHY : Assumptions: only a Decimal is accepted, and an `int` is refused with it. The query's
    #   total is NUMERIC wrapped in a scale-carrying COALESCE, so an integer arriving here means the
    #   aggregate was computed over some other type -- the exact case AAP rule T3 forbids -- and
    #   coercing it would hide the substitution behind a number that still added up.
    if isinstance(value, Decimal) and not isinstance(value, bool):
        return value
    raise MoneyResultSetContractError(
        f"the total of the {label} line is {type(value).__name__} and not an exact decimal; the"
        " money-total query projects NUMERIC from the column through the SUM to the output, so any"
        " other type means the aggregate was taken over a representation that cannot hold cents"
    )


def _require_exact_money(value: object, label: str) -> Decimal:
    """Convert one aggregate money value to an exact decimal at the declared money scale.

    Purpose
    -------
    Be the single gate every money total this pass reads passes through, so that "the money agrees"
    is a statement about exact decimals at scale 2 and never about a value that was rounded into
    looking like one.

    Parameters
    ----------
    value : object
        The value the driver returned for a money aggregate.
    label : str
        What was being totalled -- a qualified column, or a report line -- used only in the failure
        message. No value is ever quoted.

    Returns
    -------
    Decimal
        The same value, unchanged, once it is proven to be an exact decimal whose exponent is the
        negated money scale.

    Raises
    ------
    MoneyResultSetContractError
        If the value is a ``bool``, an ``int``, a binary ``float``, a ``str``, a non-finite decimal
        or a decimal at any other scale.
    """
    # WHY : Assumptions: `bool` is refused before anything else even though it IS an `int` in
    #   Python, for the same reason the count validator above refuses it: `True` would otherwise
    #   total as one cent short of nothing and a substituted boolean would be certified as money.
    # WHY : Assumptions: a `float` is refused OUTRIGHT and never converted. `Decimal(0.1)` is
    #   0.1000000000000000055511151231257827, so passing a float through `Decimal(...)` and then
    #   quantizing produces a plausible `0.10` from a representation that never held ten cents --
    #   which certifies as exact a total that AAP rule T3 forbids the money path from ever
    #   computing. The refusal is what makes the rule enforceable at the one boundary where a
    #   floating-point substitution can enter: the driver's own mapping of the column type.
    # WHY : Assumptions: an `int` and a `str` are refused as well, so a driver returning a total as
    #   a whole number or as text cannot be silently reinterpreted. Both mean the aggregate was
    #   taken over -- or transported as -- something other than NUMERIC, and the remedy is to fix
    #   the projection rather than to coerce the value here.
    if isinstance(value, bool) or not isinstance(value, Decimal):
        raise MoneyResultSetContractError(
            f"the total of {label} is {type(value).__name__} and not an exact decimal; a money"
            " total is NUMERIC from the column through the SUM to the output, so any other type"
            " means it was computed or transported through a representation that cannot hold cents"
        )
    if not value.is_finite():
        raise MoneyResultSetContractError(
            f"the total of {label} is a non-finite decimal; a sum of exact money values is always"
            " finite, so this is a substituted value rather than a total"
        )
    # WHY : Assumptions: the SCALE is asserted rather than imposed by `quantize`. Quantizing would
    #   accept a total of any scale and round it into shape, which is indistinguishable afterwards
    #   from a total that arrived correct -- and a total arriving at scale 0 or 6 means the column,
    #   the aggregate or the cast is not what this pass believes it is. Asserting turns that into a
    #   named refusal; rounding would turn it into a passing verification of a different quantity.
    exponent = value.as_tuple().exponent
    if exponent != -MONEY_SCALE:
        raise MoneyResultSetContractError(
            f"the total of {label} carries decimal exponent {exponent} where the money scale"
            f" requires {-MONEY_SCALE}; the aggregate is cast to NUMERIC at that scale, so another"
            " scale means the value did not come from the expression this pass reads"
        )
    return value


@dataclass(frozen=True)
class MoneyTotalRow:
    """One line of the report ``data-migration/sql/verify/money_totals.sql`` publishes.

    Purpose
    -------
    Carry one money column's provenance, row count, exact total and negative-row count as a checked
    value, so that a caller reads named attributes instead of indexing a driver tuple by position.

    Parameters
    ----------
    target_table : str
        The schema-qualified target table.
    money_column : str
        The column name as the owning migration declares it.
    cobol_field : str
        The originating COBOL field name, for example ``ACCT-CURR-BAL``.
    cobol_picture : str
        That field's PICTURE, for example ``PIC S9(10)V99``.
    sql_type : str
        The migrated column type, for example ``NUMERIC(12,2)``.
    row_count : int
        ``COUNT(*)`` over the whole table.
    total : Decimal
        The exact sum of the column; zero at scale 2 when the table has no rows.
    negative_rows : int
        The rows in which the column is strictly negative.

    Returns
    -------
    MoneyTotalRow
        A new immutable, self-consistent line of the published money-total report.

    Raises
    ------
    MoneyResultSetContractError
        From :meth:`__post_init__`, if the eight values contradict one another or if the declared
        picture or column type is not one a money column can have. Construction is therefore a
        refusal point, which is what makes an inconsistent line unrepresentable.
    """

    target_table: str
    money_column: str
    cobol_field: str
    cobol_picture: str
    sql_type: str
    row_count: int
    total: Decimal
    negative_rows: int

    def __post_init__(self) -> None:
        """Refuse a line whose eight values contradict one another.

        Parameters
        ----------
        None
            Reads the fields just assigned.

        Returns
        -------
        None
            Validation succeeds silently; the line is usable exactly when this returns.

        Raises
        ------
        MoneyResultSetContractError
            If either count is negative, if more rows are negative than exist, if an empty table
            reports a non-zero total or negative rows, if the picture does not declare a signed
            money field, or if the column type is not NUMERIC at the money scale.
        """
        # WHY : Assumptions: cross-field consistency is checked HERE rather than only in the parser,
        #   so a `MoneyTotalRow` cannot exist in an inconsistent state whichever way it was built --
        #   including from a test that constructs one directly. A parser-only check would leave
        #   every other construction path unguarded, and the report's arithmetic is the one thing a
        #   reader trusts without re-deriving it.
        # WHY : Assumptions: the checks RAISE rather than assert, for the reason `python -O` makes
        #   unavoidable: it strips assertions, so an assertion is a validation that disappears in
        #   exactly the deployment where a mis-decoded amount costs money.
        if self.row_count < 0 or self.negative_rows < 0:
            raise MoneyResultSetContractError(
                f"the {self.qualified_column} line reports row_count={self.row_count} and"
                f" negative_rows={self.negative_rows}; both are COUNT expressions and cannot be"
                " negative, so the row was not assembled from this query"
            )
        if self.negative_rows > self.row_count:
            raise MoneyResultSetContractError(
                f"the {self.qualified_column} line reports {self.negative_rows} negative rows in a"
                f" table of {self.row_count}; the filtered count is a subset of the total count, so"
                " the two were not taken over the same relation"
            )
        if self.row_count == 0 and (self.total != 0 or self.negative_rows != 0):
            raise MoneyResultSetContractError(
                f"the {self.qualified_column} line totals a non-zero amount over zero rows; the"
                " query's COALESCE substitutes a scale-2 zero for an empty table, so a value here"
                " means the count and the total were read from different relations"
            )
        if _SIGNED_PICTURE_MARKER not in self.cobol_picture:
            raise MoneyResultSetContractError(
                f"the {self.qualified_column} line names picture {self.cobol_picture!r} for"
                f" {self.cobol_field}, which declares no sign; an unsigned field carries no sign"
                " representation at all, so its negative-row count could never be non-zero and"
                " comparing one would prove nothing"
            )
        if _MONEY_PICTURE_MARKER not in self.cobol_picture:
            raise MoneyResultSetContractError(
                f"the {self.qualified_column} line names picture {self.cobol_picture!r} for"
                f" {self.cobol_field}, which declares no two implied decimal places; a total"
                f" compared at scale {MONEY_SCALE} against a field of another scale would be"
                " comparing a different quantity"
            )
        if not _SQL_TYPE_PATTERN.match(self.sql_type):
            raise MoneyResultSetContractError(
                f"the {self.qualified_column} line declares column type {self.sql_type!r}; a money"
                f" column is NUMERIC at scale {MONEY_SCALE}, and a column of any other numeric"
                " representation could not hold ten cents exactly -- which is the defect this pass"
                " exists to find rather than to inherit"
            )

    @property
    def qualified_column(self) -> str:
        """Report the schema-qualified table and column as one label.

        Parameters
        ----------
        None
            Reads :attr:`target_table` and :attr:`money_column`.

        Returns
        -------
        str
            The dotted label, for example ``account.accounts.curr_bal``.

        Raises
        ------
        None
            Joining two names cannot fail.
        """
        return f"{self.target_table}.{self.money_column}"

    @property
    def key(self) -> tuple[str, str]:
        """Report the (table, column) pair this line is keyed on.

        Parameters
        ----------
        None
            Reads :attr:`target_table` and :attr:`money_column`.

        Returns
        -------
        tuple[str, str]
            The pair, which is what :func:`money_columns` and the source-side measurements key on.

        Raises
        ------
        None
            Building a pair cannot fail.
        """
        # WHY : Assumptions: the key is the PAIR because neither half is unique: `amount` names a
        #   column of two ledger tables and `account.accounts` contributes five columns. Keying a
        #   lookup on either half alone would silently match the wrong line.
        return self.target_table, self.money_column


def parse_money_total_rows(rows: Iterable[Sequence[object]]) -> tuple[MoneyTotalRow, ...]:
    """Parse the money-total query's result set into checked lines, in the order it returned them.

    Purpose
    -------
    Be the whole of the boundary between a driver's tuples and this module's own types. Nothing
    downstream indexes a result row by position, so a change in the query's projection is reported
    here, once, against a named column list.

    Parameters
    ----------
    rows : Iterable[Sequence[object]]
        The result set as a cursor's ``fetchall`` returns it -- one sequence of eight values per
        line. Supplied by the caller, so a test can drive every branch with no database at all.

    Returns
    -------
    tuple[MoneyTotalRow, ...]
        One checked line per result row, in the query's own order.

    Raises
    ------
    MoneyResultSetContractError
        If any row has other than eight values, if any of the five labels is not text, if a count is
        not an exact whole number, if the total is not an exact decimal, or if a line's eight values
        contradict one another.
    """
    # WHY : Assumptions: the query's order is PRESERVED and never re-sorted. The query orders by an
    #   integer ordinal it deliberately does not project, precisely so the order cannot depend on
    #   the database collation -- under which 'account.accounts' may sort either side of
    #   'ledger.transactions'. Sorting here would reintroduce exactly the collation dependence the
    #   query removed and break the byte-identical line diff the ordering exists to guarantee.
    parsed: list[MoneyTotalRow] = []
    for ordinal, row in enumerate(rows, start=1):
        values = tuple(row)
        if len(values) != len(MONEY_TOTAL_COLUMNS):
            raise MoneyResultSetContractError(
                f"row {ordinal} of the money-total report carries {len(values)} values, but the"
                f" query projects {len(MONEY_TOTAL_COLUMNS)}: {list(MONEY_TOTAL_COLUMNS)}"
            )
        table, column, cobol_field, cobol_picture, sql_type, row_count, total, negatives = values
        labels = (table, column, cobol_field, cobol_picture, sql_type)
        if not all(isinstance(value, str) for value in labels):
            raise MoneyResultSetContractError(
                f"row {ordinal} of the money-total report carries"
                f" {[type(value).__name__ for value in labels]} in its five label columns"
                f" {list(MONEY_TOTAL_COLUMNS[:5])}; the query projects text for every one of them"
            )
        label = f"{table}.{column}"
        parsed.append(
            MoneyTotalRow(
                target_table=str(table),
                money_column=str(column),
                cobol_field=str(cobol_field),
                cobol_picture=str(cobol_picture),
                sql_type=str(sql_type),
                row_count=_require_whole_number(row_count, "row_count", label),
                total=_require_exact_total(total, label),
                negative_rows=_require_whole_number(negatives, "negative_rows", label),
            )
        )
    return tuple(parsed)


@dataclass(frozen=True)
class MoneyParityLine:
    """One money column's report line paired with the source measurement it is judged against.

    Purpose
    -------
    Hold both sides of one comparison together with the verdict over them, so that the two numbers
    that must agree -- the total AND the negative-row count -- are read from one object and cannot
    be reported from two different runs.

    Parameters
    ----------
    row : MoneyTotalRow
        The line the database published for this column.
    source : SourceMoneyTotal | None
        The measurement recomputed from the source extract, or ``None`` for a column whose layout
        ships no seed extract at all. ``None`` is a normal state and is never coerced to zero.

    Returns
    -------
    MoneyParityLine
        A new immutable pairing of one report line with the measurement it is judged against.

    Raises
    ------
    None
        Construction validates nothing: the row validated itself when it was parsed, the source
        measurement validated itself when it was taken, and the pairing is checked by
        :func:`verify_money_total_rows`, which is the only thing that can know whether a missing
        source is legitimate.
    """

    row: MoneyTotalRow
    source: SourceMoneyTotal | None

    @property
    def verdict(self) -> MoneyParityVerdict:
        """Return the three-state verdict for this column.

        Parameters
        ----------
        None
            Reads both sides.

        Returns
        -------
        MoneyParityVerdict
            :attr:`MoneyParityVerdict.NO_SOURCE` when there is no source measurement,
            :attr:`MoneyParityVerdict.MATCH` when the total AND the negative-row count both agree,
            and :attr:`MoneyParityVerdict.MISMATCH` when either differs.

        Raises
        ------
        None
            Comparing exact values cannot fail.
        """
        # WHY : Alternatives Considered: BOTH numbers are compared, and a difference in either one
        #   alone is a MISMATCH. Comparing the total alone was the obvious alternative and is the
        #   one this pass may not take: a decoder that read the negative overpunches as plain digits
        #   turns every negative amount positive, and where the negatives and positives happen to
        #   balance the total is unchanged while the negative-row count falls to zero. Comparing the
        #   count alone is equally insufficient, because it says nothing about magnitude -- a
        #   decimal point read one place out keeps every sign and multiplies the total by ten. The
        #   repository already records the sign hazard as live: `statement_compat.py` L33-L36
        #   requires `-fsign=EBCDIC` because an ASCII-sign build "would render negative amounts
        #   differently and mismatch the golden".
        if self.source is None:
            return MoneyParityVerdict.NO_SOURCE
        if self.total_matched and self.negative_rows_matched:
            return MoneyParityVerdict.MATCH
        return MoneyParityVerdict.MISMATCH

    @property
    def comparable(self) -> bool:
        """Report whether this line compared a source measurement against the report at all.

        Parameters
        ----------
        None
            Reads :attr:`verdict`.

        Returns
        -------
        bool
            False for the one column whose layout ships no seed extract; True for every other.

        Raises
        ------
        None
            Reading a verdict cannot fail.
        """
        return self.verdict.comparable

    @property
    def verified(self) -> bool:
        """Report whether this line is acceptable in a passing run.

        Parameters
        ----------
        None
            Reads :attr:`verdict` and, for a line with no source measurement, the three figures the
            database published for the column.

        Returns
        -------
        bool
            True when the two sides agreed, and -- for the one column whose layout ships no seed
            extract -- when the target is EXACTLY EMPTY: no row, a zero total and no negative row.
            False for a disagreement, and false for an unmeasurable column that nevertheless holds
            money.

        Raises
        ------
        None
            Reducing validated figures to one verdict cannot fail.
        """
        # WHY : Refactoring Rationale: a NO_SOURCE line used to pass unconditionally, and this is
        #   the expected-empty rule that replaces it. The column reached by this branch is
        #   `ledger.transactions.amount`: no seed extract ships for the transaction master, the
        #   posting job fills it from `ledger.daily_transactions`, and the ETL therefore leaves it
        #   EMPTY -- which is a checkable state, not an unknown one. Certifying any total for it was
        #   the defect, because the three things that produce a nonzero total there are all real
        #   failures a verification run exists to catch: rows left behind by an earlier cutover
        #   attempt into a table nothing here can empty (no role holds DELETE), a load pointed at
        #   the wrong table, or a posting run that started before the migration was verified. Each
        #   one is now a MISMATCH-equivalent failure naming the column.
        # WHY : Assumptions: all THREE figures are required to be empty rather than the row count
        #   alone, even though `MoneyTotalRow.__post_init__` already refuses an empty table that
        #   reports a nonzero total. The redundancy is deliberate and cheap: this property is the
        #   one place a passing run is decided, and it should not depend on another class's
        #   invariant continuing to hold for its verdict to be sound.
        if self.verdict is MoneyParityVerdict.NO_SOURCE:
            return (
                self.row.row_count == 0
                and self.row.total == Decimal(0)
                and self.row.negative_rows == 0
            )
        return self.verdict is MoneyParityVerdict.MATCH

    @property
    def total_matched(self) -> bool:
        """Report whether the two totals are exactly equal.

        Parameters
        ----------
        None
            Reads both sides.

        Returns
        -------
        bool
            True when a source measurement exists and its total equals the reported total to the
            cent. False when they differ, and False when there is no source measurement, because an
            absent measurement is not an agreement.

        Raises
        ------
        None
            Comparing two exact decimals cannot fail.
        """
        # WHY : Assumptions: the tolerance is zero, and that is not fastidiousness. A sign overpunch
        #   read wrongly moves a total by twice the value of the affected rows and a decimal point
        #   read wrongly moves it by a factor of a hundred, but a single mis-decoded digit in one
        #   record of fifty thousand moves it by cents -- so any tolerance at all would let that one
        #   through, and it is the one a reader is least likely to find by eye.
        return self.source is not None and self.source.total == self.row.total

    @property
    def negative_rows_matched(self) -> bool:
        """Report whether the two negative-row counts are exactly equal.

        Parameters
        ----------
        None
            Reads both sides.

        Returns
        -------
        bool
            True when a source measurement exists and its negative-row count equals the reported
            one. False when they differ, and False when there is no source measurement.

        Raises
        ------
        None
            Comparing two integers cannot fail.
        """
        return self.source is not None and self.source.negative_rows == self.row.negative_rows

    @property
    def total_difference(self) -> Decimal | None:
        """Report the reported total's excess over the source total.

        Parameters
        ----------
        None
            Reads both sides.

        Returns
        -------
        Decimal | None
            ``reported - source`` at the reported total's own scale, or ``None`` when there is no
            source measurement to subtract from.

        Raises
        ------
        None
            Exact decimal subtraction cannot fail.
        """
        if self.source is None:
            return None
        return self.row.total - self.source.total

    @property
    def negative_rows_difference(self) -> int | None:
        """Report the reported negative-row count's excess over the source's.

        Parameters
        ----------
        None
            Reads both sides.

        Returns
        -------
        int | None
            ``reported - source``, or ``None`` when there is no source measurement.

        Raises
        ------
        None
            Integer subtraction cannot fail.
        """
        if self.source is None:
            return None
        return self.row.negative_rows - self.source.negative_rows

    def describe(self) -> str:
        """Render this line as one deterministic, privacy-safe string.

        Parameters
        ----------
        None
            Reads both sides.

        Returns
        -------
        str
            The verdict token, the qualified column, the originating COBOL field, both totals, both
            negative-row counts and the record counts, with an absent source rendered as
            :data:`_ABSENT` and annotated ``not comparable``. No field value and no record content
            appears.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        # WHY : Assumptions: the originating COBOL field is carried into the line because it is what
        #   makes a difference actionable without opening a copybook: a reader seeing
        #   `ledger.daily_transactions.amount <- DALYTRAN-AMT` can go straight to the field whose
        #   overpunch is suspect. It is a NAME, not a value, so it discloses nothing.
        source_total = _ABSENT if self.source is None else str(self.source.total)
        source_negatives = _ABSENT if self.source is None else str(self.source.negative_rows)
        source_records = _ABSENT if self.source is None else str(self.source.records)
        line = (
            f"{self.verdict.value} {self.row.qualified_column} <- {self.row.cobol_field}"
            f" total source={source_total} target={self.row.total}"
            f" negative source={source_negatives} target={self.row.negative_rows}"
            f" records source={source_records} target={self.row.row_count}"
        )
        if self.comparable:
            return line
        # WHY : Refactoring Rationale: the per-line note states the verdict as well as the
        #   impossibility of the comparison, matching `MoneyTotalReport.render`. Before the
        #   expected-empty rule an unmeasurable line always passed, so `(not comparable)` carried
        #   the whole story; now the same annotation covers both a correctly-empty column and one
        #   holding money nothing accounts for, and only the first of those passes.
        return (
            f"{line} ({_NOT_COMPARABLE}; {_EXPECTED_EMPTY}"
            f" {'held' if self.verified else 'VIOLATED'})"
        )


@dataclass(frozen=True)
class MoneyTotalReport:
    """Every line of one money-total verification, with a single binary verdict over them.

    Purpose
    -------
    Let one run report every money column instead of stopping at the first that disagrees, while
    still answering one yes-or-no question about the whole load.

    Parameters
    ----------
    lines : tuple[MoneyParityLine, ...]
        The report's lines, in the order the query returned them.

    Returns
    -------
    MoneyTotalReport
        A new immutable report carrying every judged line and one verdict over them.

    Raises
    ------
    None
        Construction validates nothing, because every line was already validated when it was parsed
        and paired. The inapplicability is stated rather than left silent.
    """

    lines: tuple[MoneyParityLine, ...]

    @property
    def mismatches(self) -> tuple[MoneyParityLine, ...]:
        """Return the lines that do not pass.

        Parameters
        ----------
        None
            Reads :attr:`lines`.

        Returns
        -------
        tuple[MoneyParityLine, ...]
            The failing lines, in report order: a line whose money disagreed with the source, and an
            unmeasurable line holding money nothing accounts for. Empty when the load verifies.

        Raises
        ------
        None
            Filtering validated lines cannot fail.
        """
        return tuple(line for line in self.lines if not line.verified)

    @property
    def not_comparable(self) -> tuple[MoneyParityLine, ...]:
        """Return the lines that had no source measurement to compare against.

        Parameters
        ----------
        None
            Reads :attr:`lines`.

        Returns
        -------
        tuple[MoneyParityLine, ...]
            The lines whose verdict is :attr:`MoneyParityVerdict.NO_SOURCE`, in report order.

        Raises
        ------
        None
            Filtering validated lines cannot fail.
        """
        return tuple(line for line in self.lines if not line.comparable)

    @property
    def sign_discrepancies(self) -> tuple[MoneyParityLine, ...]:
        """Return the lines whose negative-row counts disagree.

        Purpose
        -------
        Name the subset that is the signature of a mis-decoded sign, separately from a difference in
        magnitude alone, because the two send an operator to different places: a negative-row count
        that fell to zero points at the overpunch convention, while a total that differs with the
        counts agreeing points at one record.

        Parameters
        ----------
        None
            Reads :attr:`lines`.

        Returns
        -------
        tuple[MoneyParityLine, ...]
            The comparable lines whose negative-row counts differ, in report order.

        Raises
        ------
        None
            Filtering validated lines cannot fail.
        """
        return tuple(
            line for line in self.lines if line.comparable and not line.negative_rows_matched
        )

    @property
    def verified(self) -> bool:
        """Report the one binary verdict this pass produces.

        Parameters
        ----------
        None
            Reads :attr:`lines`.

        Returns
        -------
        bool
            True when no line disagreed, False otherwise. An empty report is NOT verified, because
            a run that judged nothing has proved nothing.

        Raises
        ------
        None
            Reducing validated lines to one verdict cannot fail.
        """
        # WHY : Alternatives Considered: the verdict is BINARY -- verified or not -- and this module
        #   deliberately borrows nothing from the graded aggregate return-code rubric the COBOL
        #   parity suite under `tests/**` uses, in which 4 is a soft warn, 8 a failure and 16 an
        #   abend, aggregated worst-wins. A graded tier was considered for the not-comparable line
        #   and rejected: it would make a non-zero verification result ambiguous between "warned"
        #   and "failed", and a caller branching on the number would then have to know which. That
        #   rubric belongs to the parity oracle; the migration command line commits to a strictly
        #   binary exit status, and a not-comparable line is a PASS here rather than a third tier.
        # WHY : Assumptions: an empty report fails rather than vacuously passing. `all()` over no
        #   lines is True, which would turn "the query returned nothing" -- a lost connection, a
        #   projection that dropped every line -- into a clean bill of health.
        if not self.lines:
            return False
        return all(line.verified for line in self.lines)

    def render(self) -> str:
        """Render the whole report as deterministic, line-diffable text.

        Parameters
        ----------
        None
            Reads :attr:`lines`.

        Returns
        -------
        str
            One header line, one aligned line per money column, and one summary line. No trailing
            newline, so a caller decides how it is emitted.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        # WHY : Assumptions: NOTHING run-varying appears anywhere in this text -- no timestamp, no
        #   duration, no run identifier, no hostname, no process id, no connection detail and no
        #   file path. Both SQL passes already guarantee a fixed row order for exactly this reason,
        #   and a single varying value in the rendering would defeat it: two runs over unchanged
        #   data must diff to nothing, so that a real change stands out instead of arriving inside
        #   a diff an operator has learned to skim.
        # WHY : Assumptions: the column widths are computed from THESE lines, so they are a function
        #   of the data and not of the run. Padding to a hard-coded width would either clip a longer
        #   column name later or leave a ragged column now.
        verdict_width = max((len(line.verdict.value) for line in self.lines), default=0)
        column_width = max((len(line.row.qualified_column) for line in self.lines), default=0)
        field_width = max((len(line.row.cobol_field) for line in self.lines), default=0)
        rendered = [f"money total verification: {len(self.lines)} line(s)"]
        for line in self.lines:
            source_total = _ABSENT if line.source is None else str(line.source.total)
            source_negatives = _ABSENT if line.source is None else str(line.source.negative_rows)
            # WHY : Refactoring Rationale: an unmeasurable line's note now states the verdict
            #   reached over it, where it read only `(not comparable)`. That wording described the
            #   COMPARISON accurately and described the OUTCOME misleadingly: the reader could not
            #   tell a column that is correctly empty from one holding money nothing accounted for,
            #   and both rendered identically while only one of them passes. The expectation is
            #   named as well as the verdict, so a failure here is self-explanatory in the report an
            #   operator pastes into a ticket.
            note = (
                ""
                if line.comparable
                else f"  ({_NOT_COMPARABLE}; {_EXPECTED_EMPTY} "
                f"{'held' if line.verified else 'VIOLATED'})"
            )
            rendered.append(
                f"  {line.verdict.value:<{verdict_width}}"
                f"  {line.row.qualified_column:<{column_width}}"
                f" <- {line.row.cobol_field:<{field_width}}"
                f"  total source={source_total} target={line.row.total}"
                f"  negative source={source_negatives} target={line.row.negative_rows}{note}"
            )
        matched = sum(1 for line in self.lines if line.verdict is MoneyParityVerdict.MATCH)
        rendered.append(
            f"money total verification {'PASSED' if self.verified else 'FAILED'}:"
            f" {matched} matched, {len(self.mismatches)} {_FAILING},"
            f" {len(self.not_comparable)} {_NOT_COMPARABLE},"
            f" {len(self.sign_discrepancies)} sign discrepancies"
        )
        return "\n".join(rendered)


def verify_money_total_rows(
    rows: Iterable[Sequence[object]],
    source_totals: Mapping[tuple[str, str], SourceMoneyTotal],
) -> MoneyTotalReport:
    """Judge a money-total result set without touching a database or a file.

    Purpose
    -------
    Be the pure half of this pass: parsing, cross-field validation, coverage of the nine declared
    money columns in both directions, the pairing against the source measurements, and the binary
    verdict -- all over values the caller supplies. That is what lets the sign-defect case be driven
    with no cluster and no extract available.

    Parameters
    ----------
    rows : Iterable[Sequence[object]]
        The result set as the query returned it, one sequence of eight values per line.
    source_totals : Mapping[tuple[str, str], SourceMoneyTotal]
        Source-side measurements keyed by (schema-qualified table, column), as
        :func:`read_source_totals` returns them.

    Returns
    -------
    MoneyTotalReport
        The paired lines and the single binary verdict over them. A disagreement is reported here,
        not raised, so one run names every column that differs.

    Raises
    ------
    MoneyResultSetContractError
        If the result set breaches the published contract, including a declared money column the
        report omits, a column the report carries that no target declares, a duplicated line, or a
        line whose provenance disagrees with the loader's own declarations.
    MoneyParityVerificationError
        If the derived inventory is not the nine columns over five tables the query publishes, if a
        source measurement is supplied for a column the report does not carry, or if a column whose
        layout ships a seed extract has no source measurement at all.
    """
    declared = declared_money_columns()
    parsed = parse_money_total_rows(rows)
    reported = {row.key: row for row in parsed}
    if len(reported) != len(parsed):
        raise MoneyResultSetContractError(
            f"the money-total report carries {len(parsed)} rows over {len(reported)} distinct"
            " (table, column) pairs, so at least one column is reported twice; the query joins two"
            " closed sets on that pair, so a duplicate means the row source has gained a row"
        )
    # WHY : Assumptions: coverage is checked in BOTH directions, and the missing direction is the
    #   dangerous one. A report that silently drops a money column reads as a passing verification
    #   of money that nothing checked, which is the failure this whole pass exists to prevent. The
    #   unknown direction is refused as well, because a line whose feeding field this module cannot
    #   name is a line it cannot judge, and reporting it as verified would be a guess.
    missing = sorted(key for key in declared if key not in reported)
    if missing:
        raise MoneyResultSetContractError(
            f"the money-total report omits {missing}; a report that drops a money column reads as a"
            " passing verification of money nothing totalled"
        )
    unknown = sorted(key for key in reported if key not in declared)
    if unknown:
        raise MoneyResultSetContractError(
            f"the money-total report carries {unknown}, for which the loader declares no money"
            f" column; the declared columns are {sorted(declared)}"
        )
    unmatched = sorted(key for key in source_totals if key not in reported)
    if unmatched:
        raise MoneyParityVerificationError(
            f"source totals were supplied for {unmatched}, which the money-total report does not"
            " carry; a measurement with nothing to compare it against would be silently discarded"
        )
    return MoneyTotalReport(
        lines=tuple(_pair_line(row, declared[key], source_totals) for key, row in reported.items())
    )


def _pair_line(
    row: MoneyTotalRow,
    column: MoneyColumn,
    source_totals: Mapping[tuple[str, str], SourceMoneyTotal],
) -> MoneyParityLine:
    """Pair one report line with its source measurement, or establish that it legitimately has none.

    Parameters
    ----------
    row : MoneyTotalRow
        The parsed report line.
    column : MoneyColumn
        The loader's declaration of which layout and field feed that line's column.
    source_totals : Mapping[tuple[str, str], SourceMoneyTotal]
        The source-side measurements, keyed by (table, column).

    Returns
    -------
    MoneyParityLine
        The line, carrying its source measurement, or carrying ``None`` where the column's layout
        ships no seed extract at all.

    Raises
    ------
    MoneyResultSetContractError
        If the report's own provenance for the column disagrees with the loader's declaration of
        which field feeds it, or if the supplied measurement was taken over a different field.
    MoneyParityVerificationError
        If the column's layout ships a seed extract and no source measurement was supplied.
    KeyError
        Propagated from the readers' dispatch surface if no reader owns the column's layout.
    """
    # WHY : Assumptions: the report's `cobol_field` is checked against the loader's declaration
    #   rather than trusted, because the two are independent transcriptions of one mapping and the
    #   mapping is not mechanical -- TRAN-AMT becomes plain `amount`, TRAN-CAT-BAL becomes plain
    #   `balance`, DIS-INT-RATE expands into `interest_rate`. If they have drifted apart, this pass
    #   would be totalling one field and reporting another's name beside it.
    if row.cobol_field != column.field.name:
        raise MoneyResultSetContractError(
            f"the money-total report names {row.cobol_field} as the origin of"
            f" {row.qualified_column} while the loader declares {column.field.name}; one of the two"
            " transcriptions of that mapping has drifted, so a total would be reported under the"
            " wrong field's name"
        )
    measurement = source_totals.get(row.key)
    if measurement is None:
        # WHY : Assumptions: an absent measurement is legitimate for EXACTLY the columns whose
        #   layout ships no seed extract, and is refused for every other. `ledger.transactions`
        #   is the one such table -- the posting job fills it from `ledger.daily_transactions` --
        #   so its total is 0.00 immediately after the ETL and there is nothing to compare against.
        #   Treating any absent measurement as not-comparable would let an operator who simply
        #   forgot one extract read five unchecked columns as a clean bill of health, which is the
        #   exact shape of false assurance this pass exists to remove.
        if _ships_seed_extract(column.layout_name):
            raise MoneyParityVerificationError(
                f"no source total was supplied for {row.qualified_column}, whose layout"
                f" {column.layout_name} ships a committed seed extract; a column left unmeasured"
                " would be reported as not comparable and would pass, so the extract is required"
                " rather than optional"
            )
        return MoneyParityLine(row=row, source=None)
    if measurement.field_name != column.field.name:
        raise MoneyResultSetContractError(
            f"the source total offered for {row.qualified_column} was taken over"
            f" {measurement.field_name} and the loader declares {column.field.name}; comparing two"
            " different fields' totals would report a difference that means nothing"
        )
    return MoneyParityLine(row=row, source=measurement)


def money_total_query_path(root: pathlib.Path | None = None) -> pathlib.Path:
    """Locate the money-total query on disk.

    Parameters
    ----------
    root : pathlib.Path | None
        The distribution root holding the ``sql`` directory. ``None`` resolves it from this module's
        own location, which is correct in a source tree and in an editable install.

    Returns
    -------
    pathlib.Path
        The resolved path to ``sql/verify/money_totals.sql``.

    Raises
    ------
    MoneyQueryError
        If nothing readable is at that path. The message names the path and says why it may be
        absent, because the commonest cause is not a missing file but a packaging boundary.
    """
    # WHY : Assumptions: the `sql` directory is NOT part of the installed distribution -- the
    #   package configuration finds packages under `src` only -- so this default is correct in a
    #   source checkout and in an editable install and is expected to fail in a plain wheel install.
    #   That is why the root is a parameter: an operator running from an unpacked distribution
    #   supplies it, and the failure below names the path rather than reporting an empty report.
    # WHY : Alternatives Considered: reading the query through `importlib.resources` was rejected
    #   because it would require the SQL to be packaged as module data, which would put a second
    #   copy of the operator-facing query inside the wheel and let the two drift -- the file an
    #   operator runs with `psql` would no longer be the file this pass executes.
    base = pathlib.Path(__file__).resolve().parents[3] if root is None else pathlib.Path(root)
    path = base / "sql" / "verify" / MONEY_TOTAL_QUERY_NAME
    if not path.is_file():
        raise MoneyQueryError(
            f"the money-total query is not at {path}; the sql directory ships beside the package"
            " rather than inside it, so pass the distribution root explicitly when running from an"
            " installed wheel"
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
        The same lines with every line whose first non-blank characters are ``--`` removed, rejoined
        with newlines.

    Raises
    ------
    None
        Stripping cannot fail. A file that is comment-only strips to blank text, which the caller's
        own blank check reports.
    """
    # WHY : Assumptions: the guards below MUST read the executable text rather than the file,
    #   because the shipped query documents its own decisions in comments -- and two of those
    #   comments would otherwise trip them. It shows an operator the exact psql invocation, whose
    #   line continuation is a backslash, and it uses semicolons in ordinary prose. A guard reading
    #   the raw file would therefore refuse the very file it exists to protect, and the remedy would
    #   have been to remove the documentation Rule 1 requires. This is the same accommodation
    #   `data-migration/tests/test_verification.py` makes for the same reason.
    # WHY : Trade-offs: whole LINE comments only, matched on the first non-blank characters, with no
    #   SQL lexer. A trailing comment on a code line survives, and a `--` inside a string literal
    #   would be misread as a comment. Both are accepted because this is a guard over one committed
    #   file whose shape is asserted by that file's own tests, and because the text that is EXECUTED
    #   is never this stripped form -- it is always the file's own bytes, so a mis-strip can only
    #   ever cause a refusal, never a wrong query.
    return "\n".join(line for line in text.splitlines() if not line.lstrip().startswith("--"))


def read_money_total_query(path: pathlib.Path | None = None) -> str:
    """Read the money-total query verbatim, and refuse one a driver cursor could not run whole.

    Purpose
    -------
    Hand back the query's exact text, unmodified, having confirmed the three mechanical properties a
    cursor depends on. The text is never rewritten, reformatted or interpolated: the file an
    operator runs with ``psql`` and the text this pass executes are the same bytes.

    Parameters
    ----------
    path : pathlib.Path | None
        The query file. ``None`` resolves it through :func:`money_total_query_path`.

    Returns
    -------
    str
        The file's contents exactly as they are on disk.

    Raises
    ------
    MoneyQueryError
        If the file cannot be located or read, if it holds no executable statement, if it carries a
        psql meta-command, if it carries a bound-parameter placeholder, or if it holds more than one
        statement. Every check reads the text with its whole-line comments removed, because the
        shipped query documents its own decisions in comments and two of those comments would
        otherwise trip a check.
    """
    resolved = money_total_query_path() if path is None else pathlib.Path(path)
    if not resolved.is_file():
        raise MoneyQueryError(f"the money-total query is not at {resolved}")
    try:
        text = resolved.read_text(encoding="utf-8")
    except OSError as exc:
        raise MoneyQueryError(
            f"the money-total query at {resolved} cannot be read: {exc.strerror or exc}"
        ) from exc
    executable = _executable_text(text)
    if not executable.strip():
        raise MoneyQueryError(
            f"the money-total query at {resolved} holds no executable statement; every line of it"
            " is a comment, so running it would produce no report at all"
        )
    # WHY : Alternatives Considered: the three properties checked here are the ones that silently
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
    #   verification of one column while claiming to have checked nine.
    if "\\" in executable:
        raise MoneyQueryError(
            f"the money-total query at {resolved} holds a backslash, so it is not pure SQL; a psql"
            " meta-command cannot be run through a driver cursor"
        )
    if "%s" in executable or "%(" in executable:
        raise MoneyQueryError(
            f"the money-total query at {resolved} holds a bound-parameter placeholder; this pass"
            " supplies no parameter, and the query's descriptor rows are literals by design"
        )
    statements = [fragment for fragment in executable.split(";") if fragment.strip()]
    if len(statements) != 1:
        raise MoneyQueryError(
            f"the money-total query at {resolved} holds {len(statements)} statements; a cursor"
            " exposes only the last result set, so all but one report would be lost"
        )
    _require_harmless_query(resolved, executable)
    _require_committed_query(resolved, text)
    return text


def _require_committed_query(resolved: pathlib.Path, text: str) -> None:
    """Establish that the text read is the committed query, by its digest.

    Purpose
    -------
    Close the one substitution the shape checks cannot: a well-formed read of the allow-listed view
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
    MoneyQueryError
        If the digest of ``text`` is not :data:`MONEY_TOTAL_QUERY_DIGEST`.
    """
    # WHY : Assumptions: the WHOLE file is digested, comments and all, rather than the
    #   comment-stripped remainder the shape checks read. The comments carry this query's published
    #   eight-column contract, so a revision rewriting them while leaving the SQL alone has changed
    #   the artifact an operator reads -- and the digest is the identity of that artifact.
    digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
    if digest != MONEY_TOTAL_QUERY_DIGEST:
        # WHY : Trade-offs: the refusal reports both digests and no part of the text, so it
        #   distinguishes a different file from a moved one without putting a query that may hold
        #   literals into a retained log.
        raise MoneyQueryError(
            f"the money-total query at {resolved} has digest {digest}, not the committed"
            f" {MONEY_TOTAL_QUERY_DIGEST}; this pass executes the committed report and nothing"
            " else, so a substituted file is refused even when it reads only the permitted view"
        )


def _require_harmless_query(resolved: pathlib.Path, executable: str) -> None:
    """Establish that a query text can only read, and can only read the verification view.

    Purpose
    -------
    Turn "this is the committed money-total query" from a statement about a filename into two
    checkable properties of the text itself: it begins as a read, and every relation it names is
    either the one allow-listed aggregate view or a common table expression it declares inline.

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
    MoneyQueryError
        If the text does not begin with a reading keyword, or if it reads any relation other than
        :data:`MONEY_TOTAL_QUERY_RELATION` and its own declared expressions.
    """
    # WHY : Refactoring Rationale: these two checks are ADDED because the three that preceded them
    #   -- no meta-command, no bound parameter, one statement -- established that the text was
    #   runnable through a cursor and established nothing whatsoever about what it did. The pass
    #   published an entry point taking arbitrary query text, so a caller could hand it a statement
    #   that read a base table row by row, or wrote, and the report machinery downstream would judge
    #   whatever result set came back. The privilege boundary is still the real defence -- the role
    #   holds SELECT on one aggregate view -- but a defence that lives only in a cluster's grants
    #   cannot be verified from the repository, and this one can.
    # WHY : Alternatives Considered: pinning the query's SHA-256 in this module, which is the
    #   strongest identity check available and was rejected. The digest would have to be updated in
    #   lockstep with every legitimate edit to the SQL file -- including one made by a sibling
    #   workstream for its own reasons -- and a stale digest fails the nightly verification gate
    #   rather than the change that caused it, which is the worst possible place for that failure to
    #   arrive. Checking the text's SHAPE instead cannot be invalidated by a legitimate edit that
    #   keeps the query a read of the verification view, and any edit that does not keep it one is
    #   exactly what should be refused.
    leading = executable.strip().split(None, 1)[0].upper()
    if leading not in _READING_KEYWORDS:
        raise MoneyQueryError(
            f"the money-total query at {resolved} begins with {leading!r} rather than"
            f" {' or '.join(sorted(_READING_KEYWORDS))}; a verification pass executes a read and"
            " nothing else, so a text beginning any other way is refused rather than run"
        )
    declared = {match.group(1).lower() for match in _CTE_PATTERN.finditer(executable)}
    read = {match.group(1).lower() for match in _RELATION_PATTERN.finditer(executable)}
    unexpected = sorted(read - declared - {MONEY_TOTAL_QUERY_RELATION})
    if unexpected:
        raise MoneyQueryError(
            f"the money-total query at {resolved} reads {unexpected}; this pass is entitled to read"
            f" only {MONEY_TOTAL_QUERY_RELATION} and the expressions the query declares itself, so"
            " a text naming another relation is refused -- it is either not this query or not a"
            " query this role may run"
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
    # WHY : Alternatives Considered: the role name is RESOLVED from the configuration module's
    #   schema-to-role map rather than written as a literal, and this function is reproduced here
    #   rather than imported from the sibling pass for the reason recorded at `_ABSENT` above --
    #   `cli.py` invokes each pass alone, so neither should be unable to reach a role name without
    #   the other's module loading. Resolving rather than spelling is what keeps the two passes
    #   agreeing about which role that is even though each asks for it separately: a literal here
    #   would be a second copy free to keep answering plausibly after the map was corrected.
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
        The resolved parameters. Nothing is opened; the configuration module returns parameters and
        never a connection.

    Raises
    ------
    ConfigurationError
        If a parameter or credential cannot be resolved.
    MoneyParityVerificationError
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
    MoneyParityVerificationError
        If they name any other role. The message names both roles and nothing else about the
        settings, so no host, database or credential reaches the message.
    """
    # WHY : Assumptions: a verification pass must be structurally incapable of mutating what it
    #   verifies, and the ROLE is what makes that true rather than the query text. The reporting
    #   role holds SELECT on nine views of one schema and EXECUTE on one read-only lookup
    #   function, and it holds no privilege on a base table, no write privilege of any kind and no
    #   CREATE, so a session on it cannot write a row anywhere even if handed a statement that
    #   tried. The schema is NOT empty -- it owns nine views, one protected table and that function
    #   -- and grounding the guarantee in absent privileges rather than in absent objects is what
    #   keeps it true as relations are added. Running this pass as the batch role would work and
    #   is exactly what
    #   is refused here: that role holds INSERT and UPDATE across ledger and account and DELETE on
    #   three tables, so the act of verifying would carry the authority to change the very balances
    #   being verified -- and would additionally make the pass a row-level disclosure of every one
    #   of them for the sake of nine sums.
    expected = reporting_role()
    if settings.user != expected:
        raise MoneyParityVerificationError(
            f"verification pass 3 connects as {expected!r} and was handed settings for"
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
        An open connection, as the loaders' connect function returns one. The caller closes it; this
        function deliberately does not, because one connection serves the whole report.

    Raises
    ------
    ConfigurationError
        If the driver is not installed, or a parameter cannot be resolved.
    AuroraLoadError
        If the driver is present and the connection attempt fails.
    MoneyParityVerificationError
        If the settings name a role other than the reporting role.
    """
    # WHY : Alternatives Considered: the connection is opened through the connect function the
    #   loaders package publishes, rather than by importing the driver here. That module declares
    #   itself the only place on the load path permitted to import psycopg -- and it keeps that
    #   import inside the function body -- so routing through it both honours the invariant and
    #   keeps THIS module importable on a host with no driver installed, which is what lets the pure
    #   half of this pass be tested without one. Importing psycopg here was the alternative and was
    #   rejected on that invariant; taking an already-open connection remains supported and is what
    #   every other entry point below does, because it is what lets the in-process database double
    #   stand in.
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
    MoneyResultSetContractError
        If the statement yields no row, or a row of any arity other than one. Both mean the object
        supplied is not behaving as a connection, which is reported as such rather than surfaced
        later as an index error naming nothing.
    """
    # WHY : Assumptions: the cursor may or may not be a context manager, so both shapes are handled
    #   -- the same accommodation `fetch_money_total_rows` below makes, for the same reason. The
    #   driver's cursor is one and this package's in-process double returns a plain object.
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        cursor.execute(statement)
        row = cursor.fetchone()
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)
    if row is None:
        raise MoneyResultSetContractError(
            f"the statement {statement!r} returned no row at all; it projects one by construction,"
            " so the connection is not behaving as a database connection"
        )
    values = tuple(row)
    if len(values) != 1:
        raise MoneyResultSetContractError(
            f"the statement {statement!r} projected {len(values)} columns where it projects one;"
            " the connection is not behaving as a database connection"
        )
    return values[0]


def require_reporting_session(connection: Any) -> str:
    """Confirm the LIVE session on a connection authenticates as the read-only reporting role.

    Purpose
    -------
    Ask the server who it thinks the caller is, before any supplied query text is executed on that
    connection, so that a pass which cannot write is a property of the session rather than a
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
    MoneyParityVerificationError
        If the session authenticates as any role other than the reporting role. The message names
        both roles and nothing else about the session, so no host, database or credential reaches
        it.
    MoneyResultSetContractError
        If the connection does not answer the probe as a connection would.
    """
    # WHY : Assumptions: the SESSION is checked and not merely the settings, because every published
    #   entry point here also accepts an already-open connection so the in-process double can stand
    #   in -- so a settings check alone would protect only callers who opened their connection
    #   through `open_reporting_connection`, and a caller that opened a schema-owner connection
    #   would run this pass with write authority over the very columns it was certifying.
    # WHY : Assumptions: `select current_user` is the probe rather than `session_user`, because
    #   current_user is the identifier privilege decisions are actually made against -- it follows a
    #   SET ROLE where session_user does not. A session that authenticated as the reporting role and
    #   then assumed a writable one would pass a session_user check and could still write.
    expected = reporting_role()
    observed = _one_scalar(connection, "select current_user")
    text = observed.strip() if isinstance(observed, str) else str(observed)
    if text != expected:
        raise MoneyParityVerificationError(
            f"verification pass 3 must run on a session for {expected!r} and this connection's"
            f" session is {text!r}; a pass that can write cannot certify what it verifies"
        )
    return text


def fetch_money_total_rows(
    connection: Any,
    *,
    query_path: pathlib.Path | None = None,
) -> tuple[tuple[object, ...], ...]:
    """Execute the committed money-total query and return its result set untouched.

    Purpose
    -------
    Be the one published way to run pass 3's query against a cluster: read the committed text,
    confirm the live session is the read-only reporting role, execute inside a read-only transaction
    under a statement timeout, and hand back exactly what came out.

    Parameters
    ----------
    connection : Any
        An open database connection, supplied by the caller so a double can stand in. Its live
        session is confirmed to be the reporting role before anything is executed.
    query_path : pathlib.Path | None
        Where to read the committed query from. ``None`` resolves it through
        :func:`money_total_query_path`.

    Returns
    -------
    tuple[tuple[object, ...], ...]
        The result set in the order the query returned it, each row as a tuple of column values.

    Raises
    ------
    MoneyQueryError
        If the committed query cannot be located or read, or does not satisfy the shape and
        harmlessness checks :func:`read_money_total_query` applies.
    MoneyParityVerificationError
        If the connection's live session is not the reporting role.
    MoneyResultSetContractError
        If the cursor yields no result set at all, which a ``SELECT`` always does and which
        therefore means the object supplied is not behaving as a connection.
    """
    # WHY : Refactoring Rationale: this function no longer takes QUERY TEXT. It did, and that was
    #   the whole of the injection surface: a published entry point accepting arbitrary SQL, guarded
    #   only by checks that the text was runnable, on a connection this module had just certified as
    #   the one authority allowed to judge the load. A caller -- or a future orchestration step
    #   reaching for the most convenient signature -- could therefore have this pass execute
    #   something other than the committed query and then judge its result set as though it were the
    #   report. Reading the text HERE, from the committed file, makes the executed statement a
    #   property of the distribution rather than of the call, and the `_money_total_rows` helper
    #   below keeps a single injectable seam for the tests that must drive a substituted result set.
    return _money_total_rows(connection, read_money_total_query(query_path))


def _money_total_rows(connection: Any, query: str) -> tuple[tuple[object, ...], ...]:
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
        Query text that has already passed :func:`read_money_total_query`, executed verbatim.
        Nothing is appended, wrapped or interpolated.

    Returns
    -------
    tuple[tuple[object, ...], ...]
        The result set in the order the query returned it, each row as a tuple of column values.

    Raises
    ------
    MoneyParityVerificationError
        If the connection's live session is not the reporting role.
    MoneyResultSetContractError
        If the cursor yields no result set at all.
    """
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        # WHY : Assumptions: the ORDER of these four statements is load-bearing and it begins with
        #   the read-only setting, exactly as the row-count pass orders its own. The driver opens a
        #   transaction implicitly on the first execute, so the first statement decides what the
        #   transaction is for every statement after it -- the identity probe included.
        # WHY : Refactoring Rationale: the probe used to run first and the read-only setting second.
        #   Measured against PostgreSQL 17 rather than assumed: that order is ACCEPTED, because the
        #   engine allows a transaction's access mode to be tightened mid-transaction and refuses
        #   only `SET TRANSACTION ISOLATION LEVEL` after a query, with SQLSTATE 25001. Leading with
        #   the read-only statement therefore fixes no outage; what it fixes is that the probe used
        #   to run in a still-writable transaction, and that the sequence depended on a
        #   vendor-specific allowance rather than on the standard's own first-statement position --
        #   a pooler or proxy that opens the transaction itself need not preserve the allowance.
        # WHY : Assumptions: the identity probe follows in the SAME transaction, through the
        #   published guard, and stays at this single execution site so no other path into this
        #   helper is unguarded. It asks the server for `current_user` rather than trusting the
        #   settings the connection was opened with, and it shares this transaction because every
        #   cursor on one connection does.
        # WHY : Trade-offs: the timeout is stated in milliseconds from a module constant rather
        #   than bound as a parameter, because `SET` accepts no bound parameter. The value is an
        #   `int` constant declared in this module and never caller supplied, so no text from
        #   outside this file reaches the statement.
        cursor.execute(_READ_ONLY_TRANSACTION_STATEMENT)
        require_reporting_session(connection)
        cursor.execute(f"SET LOCAL statement_timeout = {_STATEMENT_TIMEOUT_MILLISECONDS}")
        cursor.execute(query)
        fetched = cursor.fetchall()
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)
    if fetched is None:
        raise MoneyResultSetContractError(
            "the money-total query returned no result set at all; a SELECT always returns one, so"
            " the connection is not behaving as a database connection"
        )
    return tuple(tuple(row) for row in fetched)


def verify_money_totals(
    connection: Any,
    extracts: Iterable[SourceExtract],
    *,
    query_path: pathlib.Path | None = None,
) -> MoneyTotalReport:
    """Run verification pass 3 over the whole migration and report every money column of it.

    Purpose
    -------
    Execute ``data-migration/sql/verify/money_totals.sql`` as-is against a read-only session, total
    the same nine columns independently from the source extracts' own bytes, and compare BOTH the
    exact totals and the negative-row counts -- so that one call answers "is the money that landed
    the money that was sent" for the whole load.

    Parameters
    ----------
    connection : Any
        An open database connection, which must be a session on the reporting role. That is CHECKED
        against the server rather than assumed -- see :func:`require_reporting_session`, which
        :func:`fetch_money_total_rows` calls before executing anything. Supplied rather than opened
        here so that one connection can serve all three passes and so that the in-process double can
        stand in; :func:`open_reporting_connection` opens one.
    extracts : Iterable[SourceExtract]
        The source extracts to recompute totals from, at most one per layout. Every money column
        whose layout ships a committed extract must be covered, or the run is refused.
    query_path : pathlib.Path | None
        Where to read the committed query from. ``None`` resolves it through
        :func:`money_total_query_path`. A path is accepted and query TEXT is not, so the statement
        this pass executes is always the committed one -- see :func:`fetch_money_total_rows`.

    Returns
    -------
    MoneyTotalReport
        Every line of the report and the single binary verdict over them. A disagreement is
        reported, never raised, so one run names every column that differs instead of stopping at
        the first.

    Raises
    ------
    MoneyQueryError
        If the query cannot be located or read, or is not a single pure-SQL statement.
    MoneyResultSetContractError
        If the result set breaches the query's published contract.
    MoneyParityVerificationError
        If the connection's live session is not the reporting role, if the derived inventory is not
        nine columns over five tables, or if a column whose layout ships a seed extract was left
        unmeasured.
    LayoutError
        Propagated from a reader if an extract cannot be decoded from its declared form.
    RecordLengthError
        Propagated from a reader if an extract does not divide into whole records.
    OSError
        Propagated if an extract cannot be read.
    """
    # WHY : Refactoring Rationale: the caller supplies a query PATH and can no longer supply query
    #   TEXT. The text parameter existed because the sql directory ships beside the package rather
    #   than inside it, so a caller running from a wheel needs a way to say where the file is -- and
    #   the ROOT already says that. Accepting text as well meant the one entry point a batch state
    #   would reach for could be handed a statement nobody committed, on a session this pass had
    #   certified as the sole authority entitled to judge the load.
    # WHY : Assumptions: the source side is measured BEFORE the query is executed, and the ordering
    #   is deliberate. Every fault the source read can raise -- :func:`read_source_totals` documents
    #   them: an unreadable extract, a record geometry that does not divide, a layout that cannot be
    #   decoded, two extracts claiming one layout, a value that is not an exact decimal -- therefore
    #   surfaces with no query in flight, ahead of the whole query-side family the Raises section
    #   above lists separately. The ordering is not itself a diagnosis of either family: what it
    #   buys is that the two cannot arrive together, so which SIDE failed follows from the exception
    #   raised rather than from how far a partly-run pass happened to get.
    source_totals = read_source_totals(extracts)
    return verify_money_total_rows(
        fetch_money_total_rows(connection, query_path=query_path), source_totals
    )
