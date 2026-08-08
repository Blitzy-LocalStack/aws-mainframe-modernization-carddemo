"""Compare the record count of a source dataset against the row count of its target table.

Purpose
-------
Catch the coarsest and most common load failure: a load that stopped early, ran twice, or
silently skipped records. This is the check that makes "the load succeeded" mean something.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from decimal import Decimal
from typing import Any

from carddemo_migration.config import quote_identifier

__all__ = ["RowCountComparison", "compare_counts", "count_source_records", "count_target_rows"]


@dataclass(frozen=True)
class RowCountComparison:
    """The outcome of one row-count comparison.

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
    """

    record_name: str
    qualified_table: str
    source_records: int
    target_rows: int

    @property
    def matched(self) -> bool:
        """Report whether the two counts agree.

        Returns
        -------
        bool
            True when the source and target counts are equal.
        """
        return self.source_records == self.target_rows

    @property
    def difference(self) -> int:
        """Report the target's excess over the source, which is negative when rows are missing.

        Returns
        -------
        int
            ``target_rows - source_records``.
        """
        return self.target_rows - self.source_records

    def describe(self) -> str:
        """Render the outcome as one privacy-safe line.

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

    Raises
    ------
    None
    """
    # WHY : Trade-offs: the records are consumed and discarded one at a time rather than
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
    # WHY : Assumptions: the identifiers are quoted through the shared helper rather than
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
    return int(row[0])


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
