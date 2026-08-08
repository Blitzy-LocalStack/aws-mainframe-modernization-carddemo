"""Bulk-load decoded baseline records into Aurora PostgreSQL through server-side COPY.

Purpose
-------
Provide the load half of the migration: take the decoded records a reader yields and put them
into the target table the owning service declares, using PostgreSQL's own bulk path rather
than a statement per row. This is the migrated form of the baseline's ``IDCAMS REPRO`` load
steps, which is what ``app/jcl/ACCTFILE.jcl`` and its nine siblings each perform for one
dataset.

WHY (Alternatives Considered)
-----------------------------
An ``INSERT`` per row, or an executemany. Rejected because a dataset load is the one operation
in this package where volume is the whole problem: COPY streams rows into the server in a
single statement with one parse and no per-row round trip, and psycopg 3 exposes it as a
context manager that this module writes rows into. The requirements file already records this
as the reason psycopg 3 is pinned rather than psycopg2 -- "the Aurora bulk loaders drive
server-side COPY through psycopg 3 copy context manager", and psycopg2 offers neither that nor
its pipeline mode.

WHY (Assumptions)
-----------------
The column mapping is DECLARED per record and is not derived from the field name. It cannot be
derived: the target column names are the owning service's, and the transformation is not
mechanical -- ``CUST-ID`` becomes ``customer_id``, ``CUST-FIRST-NAME`` becomes ``first_name``,
``DIS-ACCT-GROUP-ID`` becomes ``acct_group_id`` and ``TRAN-TYPE`` becomes ``type_cd``. A
hyphen-to-underscore rule would produce a different name for every one of those and the load
would fail on the first column, or worse, load into the wrong one where a coincidence lined up.
Declaring the mapping is what makes this module the anti-corruption layer for the load
direction, and it is where the baseline's three misspellings are corrected.

WHY (Trade-offs)
----------------
Two records are deliberately absent from :data:`TARGETS` and cannot be loaded by this module:
the customer record and the card record. Their target tables declare protected columns --
``account.customers.ssn_encrypted``, ``account.customers.govt_issued_id_encrypted`` and
``card.cards.cvv_encrypted`` -- which are ``BYTEA`` holding ciphertext produced by the owning
service's own cipher, under a key this package has no access to and should not have. Supplying
a plaintext value would defeat the column's whole purpose, and inventing a placeholder would
put unreadable rows in a table declared ``NOT NULL``. Loading those two records is therefore an
application-side step that runs the service's cipher over the decoded values this package's
readers already produce; the boundary is stated here rather than discovered when a load fails.
"""

from __future__ import annotations

import contextlib
from collections.abc import Iterable, Iterator, Mapping
from dataclasses import dataclass
from decimal import Decimal
from types import MappingProxyType
from typing import Any, Final, Protocol

from carddemo_migration.config import (
    AuroraConnectionSettings,
    ConfigurationError,
    quote_identifier,
)

__all__ = [
    "TARGETS",
    "AuroraLoadError",
    "TableTarget",
    "connect",
    "load_records",
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
    the driver explicit: one COPY context manager and a row count.
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
        """Commit the open transaction."""
        ...  # pragma: no cover - Protocol declaration

    def rollback(self) -> None:
        """Roll the open transaction back."""
        ...  # pragma: no cover - Protocol declaration


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

    Raises
    ------
    ValueError
        If the schema, table or mapping is empty.
    """

    schema: str
    table: str
    columns: Mapping[str, str]

    def __post_init__(self) -> None:
        """Refuse a target that names no table or no column.

        Raises
        ------
        ValueError
            If any component is empty.
        """
        if not self.schema.strip() or not self.table.strip():
            raise ValueError("a table target must name both a schema and a table")
        if not self.columns:
            raise ValueError(
                f"target {self.schema}.{self.table} maps no column, so a load would insert"
                " nothing; a target with no mapping is a declaration error rather than an"
                " empty load"
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

    def row_of(self, record: Mapping[str, str | Decimal | bytes]) -> tuple[object, ...]:
        """Project one decoded record into this target's column order.

        Parameters
        ----------
        record : Mapping[str, str | Decimal | bytes]
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
        return tuple(record[field] for field in self.columns)


# WHY : Assumptions: every mapping below was read from the owning service's own Flyway
#   migration rather than derived, and each records one anti-corruption decision. The three
#   baseline misspellings are corrected here: the account and card expiration dates, spelled
#   `EXPIRAION` in the copybooks, and the authorization merchant category code. `FILLER` never
#   appears, because a reader never publishes it.
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
        "TRANTYPE": TableTarget(
            schema="reference",
            table="transaction_types",
            columns=MappingProxyType(
                {
                    "TRAN-TYPE": "type_cd",
                    "TRAN-TYPE-DESC": "description",
                }
            ),
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
        ),
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
        If the record has no declared target, with the two cipher-dependent records named
        explicitly so the message distinguishes "not supported here" from "misspelled".
    """
    try:
        return TARGETS[record_name]
    except KeyError as exc:
        cipher_bound = {"CUSTOMER", "CARD"}
        if record_name in cipher_bound:
            raise AuroraLoadError(
                f"record {record_name} has no load target in this module because its table"
                " declares protected columns holding ciphertext produced by the owning"
                " service's cipher under a key this package does not hold; decode it here and"
                " load it through that service"
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
    records: Iterable[Mapping[str, str | Decimal | bytes]],
) -> int:
    """Bulk-load decoded records into one table and commit.

    Purpose
    -------
    Stream every record through one server-side COPY, then commit once, so the load is one
    unit of work whose partial application is not observable.

    Parameters
    ----------
    connection : _Connection
        An open connection. The caller owns closing it.
    target : TableTarget
        Where the records load and which column each field becomes.
    records : Iterable[Mapping[str, str | Decimal | bytes]]
        Decoded records, as a reader yields them.

    Returns
    -------
    int
        The number of rows written.

    Raises
    ------
    AuroraLoadError
        If a record does not carry a mapped field, or the COPY fails. The transaction is
        rolled back before the error is raised.
    """
    written = 0
    try:
        with _cursor_of(connection) as cursor, cursor.copy(target.copy_statement()) as stream:
            for record in records:
                stream.write_row(target.row_of(record))
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
    return written
