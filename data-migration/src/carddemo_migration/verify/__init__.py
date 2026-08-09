"""Post-load verification: prove a load landed rather than assume it did.

Purpose
-------
Make this directory a regular package, state what belongs in it, and publish the entry points of
the three independent checks the migration plan requires after a load -- row counts per dataset,
record checksums, and money-total parity against the source files. Each answers a different
failure, which is why there are three and not one.

Parameters
----------
None
    A module takes no argument. Every published callable documents its own parameters at its own
    definition.

Returns
-------
None
    Importing binds names only. It imports NONE of the three verification modules, opens no
    database connection, reads no environment variable and reaches no network; each is resolved on
    first access by :func:`__getattr__` below.

Raises
------
AttributeError
    Raised by attribute access for a name this package does not publish.

The three passes
----------------
:func:`carddemo_migration.verify.row_counts.compare_counts`
    Pass 1. Compares the record count a source dataset holds against the row count its target
    table holds, per dataset. Paired with ``data-migration/sql/verify/row_counts.sql``, which is
    the operator-facing form of the same pass.
:func:`carddemo_migration.verify.checksum.digest_records`
    Pass 2. Canonicalises each record and digests it with SHA-256, so a field corrupted in place is
    detectable even when every count agrees. Deliberately has no SQL counterpart: canonicalisation
    is the point of the pass, and expressing it in SQL would put the canonical form in two places.
:func:`carddemo_migration.verify.money_parity.compare_money_totals`
    Pass 3. Sums each money column exactly, at scale 2, on both sides. The ONLY pass that catches a
    systematically mis-decoded sign overpunch. Paired with
    ``data-migration/sql/verify/money_totals.sql``.

WHY (Refactoring Rationale)
---------------------------
``__all__`` was an empty list and none of the three passes was reachable through this package
boundary, so every consumer imported a submodule directly -- ``from
carddemo_migration.verify.money_parity import compare_money_totals``. That works and it is what
the modules were written for, but it leaves this file documenting three passes it does not
expose, which is the same defect the sibling reader package had: a boundary that describes more
than it publishes has to be read alongside the directory listing to be useful, and the listing is
what a caller was trying to avoid reading.

Assumptions:
------------
The three checks are deliberately not collapsed into a single "verify" routine, because each
catches a class the others miss. A row count catches a truncated or duplicated load and cannot
see a corrupted field. A checksum catches a corrupted field and cannot say which side is wrong
when the counts also differ. A money total catches a decode that shifted a sign or a decimal
point while the character count stayed right -- the failure mode this migration is most exposed
to, because a zoned-decimal sign overpunch read with the wrong convention produces a plausible
number rather than an error. The plan's own words are that a load which "succeeded" without a
money-total check is not evidence of anything.

Trade-offs:
-----------
Every routine here refuses to render a field value. That makes a failure less immediately
diagnosable -- an operator sees which record and which field differ, not what the two values
were -- and it is the right trade: these datasets carry primary account numbers and national
identifiers, and a verification log is retained and readable by every holder of log access. The
masked renderings the readers publish are the supported way to look at a record.
"""

from __future__ import annotations

import importlib
from types import MappingProxyType
from typing import TYPE_CHECKING, Any, Final

if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from collections.abc import Mapping

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

# WHY : Assumptions: the three passes are published in the order the plan runs them -- count, then
#   checksum, then money total -- rather than alphabetically, because the order is load-bearing for
#   a reader: a count mismatch explains a checksum mismatch, and running the cheap pass first is
#   what makes the expensive one's failure interpretable.
PASS_MODULES: Final[tuple[str, ...]] = ("row_counts", "checksum", "money_parity")

# WHY : Assumptions: the surface is CURATED rather than a star re-export of the three modules, and
#   the criterion is the smallest set with which a caller can run each pass and read its result:
#   one entry point and one result type per pass, plus the two money totalling functions a caller
#   comparing an already-loaded total needs. Everything else the three modules publish -- the digest
#   name, the two separators, the money scale -- stays reachable at its owning module, which is the
#   spelling every existing consumer already uses, so curating withdraws nothing.
# WHY : Trade-offs: the values are (module, attribute) pairs rather than the objects themselves, so
#   this constant is inert and building it imports nothing. Importing all three eagerly would pull
#   in the database driver by way of the row-count pass for a caller that only wanted a checksum.
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


def __getattr__(name: str) -> Any:  # noqa: ANN401 -- the surface spans callables, types and modules
    """Resolve a published pass entry point or pass module, importing it on first access.

    Purpose
    -------
    Make the three passes reachable from the package boundary that documents them, without this
    package importing all three -- and with them the database driver -- to make that true.

    Parameters
    ----------
    name : str
        The attribute being looked up. Only the three pass modules and the nine curated entry
        points resolve; anything else is reported as missing.

    Returns
    -------
    Any
        The imported module, class or function.

    Raises
    ------
    AttributeError
        If ``name`` is not part of the published surface. The message names the package, because a
        mistyped pass name is otherwise reported identically to a mistyped attribute on a pass.
    """
    # WHY : Assumptions: the module names are checked before the curated entries, so
    #   `verify.row_counts` continues to mean the MODULE and not some attribute of it -- that
    #   spelling is what every existing consumer uses and it must not change meaning.
    if name in PASS_MODULES:
        return importlib.import_module(f"{__name__}.{name}")
    if name in _EXPORTS:
        module_name, attribute = _EXPORTS[name]
        return getattr(importlib.import_module(f"{__name__}.{module_name}"), attribute)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


def __dir__() -> list[str]:
    """Return the published surface, so interactive completion matches the documented one.

    Returns
    -------
    list[str]
        The sorted contents of :data:`__all__`.

    Raises
    ------
    None
    """
    # WHY : Assumptions: dir() is overridden for the same reason the reader package overrides it --
    #   a lazily resolved name is absent from this package's namespace until it is touched, so the
    #   default implementation would report a surface that grows as a session proceeds.
    return sorted(__all__)
