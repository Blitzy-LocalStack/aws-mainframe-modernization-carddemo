"""Total the money fields of a dataset exactly, and compare against the target's own total.

Purpose
-------
Catch the failure this migration is most exposed to and which the other two checks can miss: a
decode that read a zoned-decimal sign overpunch with the wrong convention, or shifted an implied
decimal point. Both produce a plausible number rather than an error, so the record count agrees
and, where the digest is computed from the same wrong decode on both sides, a digest can agree
too. A total computed from the SOURCE bytes and compared against the DATABASE's own SUM is the
check that does not share that assumption.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass
from decimal import Decimal
from typing import Any, Final

from carddemo_migration.config import quote_identifier

__all__ = [
    "MONEY_SCALE",
    "MoneyParity",
    "compare_money_totals",
    "total_source_money",
    "total_target_money",
]

# WHY : Assumptions: every money column in this system is NUMERIC(p,2) and every money field in
#   the baseline is a display field with two implied decimal places, so a total is compared at
#   scale 2 exactly. Comparing at the raw decimal's own scale would let 1.5 and 1.50 differ.
MONEY_SCALE: Final[int] = 2


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
    """

    record_name: str
    qualified_column: str
    source_total: Decimal
    target_total: Decimal
    records: int

    @property
    def matched(self) -> bool:
        """Report whether the two totals are exactly equal at scale 2.

        Returns
        -------
        bool
            True when the totals agree to the cent.
        """
        return self.source_total == self.target_total

    @property
    def difference(self) -> Decimal:
        """Report the target's excess over the source.

        Returns
        -------
        Decimal
            ``target_total - source_total`` at scale 2.
        """
        return (self.target_total - self.source_total).quantize(self.source_total)

    def describe(self) -> str:
        """Render the outcome as one line.

        Returns
        -------
        str
            A line naming the dataset, the column and both totals. WHY these values MAY be
            rendered where a field value may not: an aggregate over a whole dataset is not
            attributable to any individual, which is exactly the property that makes a total
            publishable and a balance not.
        """
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
        If no field is named, or a named field's value is not an exact decimal -- which means
        the field is not a money field and totalling it would be meaningless.
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
    # WHY : Assumptions: the running total is exact throughout -- Decimal addition of
    #   scale-2 values is exact and never rounds -- and the final quantize only fixes the
    #   SCALE for comparison. No rounding mode is supplied because none can be needed; if one
    #   were, the inputs would not have been scale-2 money.
    return total.quantize(Decimal(1).scaleb(-MONEY_SCALE)), counted


def total_target_money(connection: Any, schema: str, table: str, column: str) -> Decimal:
    """Read the target's own total for one money column.

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
        The total at scale 2. An empty table totals to zero rather than to null.

    Raises
    ------
    ValueError
        If the query returns no row at all.
    """
    # WHY : Assumptions: COALESCE wraps the SUM because SUM over zero rows is NULL, not zero,
    #   and a null total compared against an exact zero would report a difference on an empty
    #   table that has nothing wrong with it.
    statement = (
        f"SELECT COALESCE(SUM({quote_identifier(column)}), 0)"  # noqa: S608
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
    return Decimal(row[0]).quantize(Decimal(1).scaleb(-MONEY_SCALE))


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
