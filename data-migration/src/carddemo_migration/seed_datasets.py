"""Bind each orchestrator seed-dataset token to everything staging it requires.

Purpose
-------
Hold the ONE authoritative description of the ten seed datasets the nightly chain stages, so
that the orchestrator, the command line and the staging loader all name the same things by the
same names. Before this module existed the three spoke three pairwise-disjoint vocabularies:

* the Step Functions ``seed_datasets`` variable iterated over plural snake-case tokens --
  ``accounts``, ``card_xref``, ``transaction_category_balances`` -- and passed each as
  ``--dataset``;
* the command line validated ``--dataset`` against the copybook layout registry, whose keys are
  short upper-case layout names -- ``ACCOUNT``, ``XREF``, ``TCATBAL``; and
* the staging loader's own family registry is keyed by provisioned generation-dataset segment --
  ``transact-bkup``, ``tcatbalf-bkup`` -- which is a legitimately different axis, naming the ten
  GDG *output* families rather than the ten *input* masters.

The first two disagreements were a live defect and not merely untidy: the orchestrator's
``--dataset=accounts`` could never satisfy a command line that required a layout name, and the
orchestrator additionally supplied none of ``--source``, ``--generation`` or ``--domain``, which
that command line required. Every staging branch therefore failed in argument parsing before
reaching any staging code. This module resolves the token, and the command line derives the rest
from it, so one vocabulary crosses the boundary and the other two are reached by lookup.

Design decisions
----------------
**Geometry is DERIVED, never restated.** A descriptor carries a layout name and nothing about
record widths; :func:`record_length` reads the width from
:mod:`carddemo_migration.copybook.layouts`. Storing the number here as well was the obvious
alternative and was rejected because it creates precisely the drift this module exists to end --
two spellings of one fact, free to disagree after a copybook correction. The layout registry is
already the authority for record geometry, so this module points at it.

**Every descriptor is validated at import.** Each layout name must be registered and each source
object must be a bare file name. A typo therefore fails when the module loads rather than when a
nightly staging branch runs, which is the difference between a build failure and a silent write
to a prefix nothing reads.

**Sources are named, not located.** A descriptor holds a file NAME; the directory it sits in comes
from the deployment through :data:`STAGING_ROOT_VARIABLE`. The container image deliberately ships
no extract -- its Dockerfile copies only ``src/`` and ``sql/`` -- so baking a path in would name a
file that is not in the image. Naming the file and resolving the root at run time lets the same
image stage from a mounted volume, a synced prefix or a checkout without being rebuilt.

Notes
-----
The ten tokens and their bindings were measured against the repository rather than transcribed.
Each source object exists under ``app/data/EBCDIC/`` with the ``AWS.M2.CARDDEMO.`` prefix, and
each one's byte length divides exactly by its layout's declared record length: 15 000/300,
7 500/150, 25 000/500, 2 500/50, 350/350, 2 550/50, 2 500/50, 420/60, 1 080/60 and 800/80.

One binding deserves its own note because it looks wrong until traced. The ``transactions`` token
maps to ``DALYTRAN.PS.INIT`` rather than to a ``TRANSACT`` extract, because **no TRANSACT extract
is shipped** -- the 350-byte transaction master is produced by the posting and backup pipeline. The
substitute is not invented here: ``app/jcl/TRANFILE.jcl:70`` defines the TRANSACT KSDS and REPROs
it from ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT``, so the baseline itself nominates that file as the
transaction master's seed. This module records the baseline's own choice.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Final

from carddemo_migration.copybook import layouts

__all__ = [
    "DEFAULT_GENERATION_RETENTION",
    "STAGING_ROOT_VARIABLE",
    "SEED_DATASETS",
    "SeedDataset",
    "SeedDatasetError",
    "record_length",
    "seed_dataset",
    "seed_dataset_tokens",
]

#: Number of newest logical generations a staged family keeps, the ``LIMIT(5) SCRATCH`` analogue.
#: Every one of the ten baseline generation bases is defined at ``LIMIT(5)``, so this is one value
#: rather than a per-descriptor field: a descriptor may still override it, but none needs to.
DEFAULT_GENERATION_RETENTION: Final[int] = 5

#: Environment variable naming the directory the seed extracts are read from. It is deliberately
#: not defaulted to a path inside the image; see the module docstring's "Sources are named, not
#: located".
STAGING_ROOT_VARIABLE: Final[str] = "CARDDEMO_DATASET_STAGING_ROOT"


class SeedDatasetError(ValueError):
    """Raised when a seed-dataset token is unknown or a descriptor is self-inconsistent.

    Purpose
    -------
    Separate "the orchestrator named a dataset this package does not describe" from every other
    failure, so a caller can report an unknown token as a usage error rather than a broken step.
    """


@dataclass(frozen=True, slots=True)
class SeedDataset:
    """Describe one seed dataset completely enough to stage it without further lookup.

    Purpose
    -------
    Carry the whole binding for one dataset -- the orchestrator's name for it, the copybook layout
    that describes its records, the two prefix segments it is staged under, and the extract it is
    read from -- so no caller has to pair those facts for itself and mispair them.

    Attributes
    ----------
    token : str
        The orchestrator's name, and the only vocabulary that crosses the process boundary. This
        is the value a Step Functions ``Map`` branch passes as ``--dataset``.
    layout_name : str
        Key into :mod:`carddemo_migration.copybook.layouts`. Record geometry is read from there
        rather than duplicated here.
    dataset_segment : str
        The ``<dataset>`` segment of the staged prefix. Held separately from ``token`` because the
        prefix is a storage layout an operator reads and browses, whereas the token is an
        orchestration identifier; they happen to coincide today and are not required to.
    domain : str
        The ``<domain>`` prefix segment, which is the bounded context owning the target table.
    source_object : str
        Bare file name of the extract, resolved against the deployment's staging root.
    retention_limit : int
        Newest logical generations to keep for this family.
    """

    token: str
    layout_name: str
    dataset_segment: str
    domain: str
    source_object: str
    retention_limit: int = DEFAULT_GENERATION_RETENTION


# WHY : Assumptions: the ten entries are ordered as the baseline's own load sequence, which is
#   also dependency order -- accounts and cards before the cross-reference that joins them,
#   reference data before the ledger rows that cite it. Alphabetical order was the alternative and
#   was rejected because a reader scanning this table is usually asking "what must exist before
#   this loads?", and dependency order answers that where alphabetical order hides it.
_SEED_DATASETS: dict[str, SeedDataset] = {
    "accounts": SeedDataset(
        token="accounts",
        layout_name="ACCOUNT",
        dataset_segment="accounts",
        domain="account",
        source_object="AWS.M2.CARDDEMO.ACCTDATA.PS",
    ),
    "cards": SeedDataset(
        token="cards",
        layout_name="CARD",
        dataset_segment="cards",
        domain="card",
        source_object="AWS.M2.CARDDEMO.CARDDATA.PS",
    ),
    "customers": SeedDataset(
        token="customers",
        layout_name="CUSTOMER",
        dataset_segment="customers",
        domain="account",
        source_object="AWS.M2.CARDDEMO.CUSTDATA.PS",
    ),
    "card_xref": SeedDataset(
        token="card_xref",
        layout_name="XREF",
        dataset_segment="card_xref",
        domain="account",
        source_object="AWS.M2.CARDDEMO.CARDXREF.PS",
    ),
    "transactions": SeedDataset(
        token="transactions",
        layout_name="TRAN",
        dataset_segment="transactions",
        domain="ledger",
        # WHY : Assumptions: this names DALYTRAN.PS.INIT and not a TRANSACT extract, because no
        #   TRANSACT extract is shipped -- the transaction master is pipeline-produced. The
        #   substitute is the baseline's own: app/jcl/TRANFILE.jcl:70 REPROs the TRANSACT KSDS
        #   from AWS.M2.CARDDEMO.DALYTRAN.PS.INIT. Leaving the token sourceless was the
        #   alternative and was rejected because the orchestrator's default schedules a staging
        #   branch for it, so a sourceless token is a nightly failure rather than an omission.
        source_object="AWS.M2.CARDDEMO.DALYTRAN.PS.INIT",
    ),
    "disclosure_groups": SeedDataset(
        token="disclosure_groups",
        layout_name="DISGROUP",
        dataset_segment="disclosure_groups",
        domain="reference",
        source_object="AWS.M2.CARDDEMO.DISCGRP.PS",
    ),
    "transaction_types": SeedDataset(
        token="transaction_types",
        layout_name="TRANTYPE",
        dataset_segment="transaction_types",
        domain="reference",
        source_object="AWS.M2.CARDDEMO.TRANTYPE.PS",
    ),
    "transaction_categories": SeedDataset(
        token="transaction_categories",
        layout_name="TRANCAT",
        dataset_segment="transaction_categories",
        domain="reference",
        source_object="AWS.M2.CARDDEMO.TRANCATG.PS",
    ),
    "transaction_category_balances": SeedDataset(
        token="transaction_category_balances",
        layout_name="TCATBAL",
        dataset_segment="transaction_category_balances",
        domain="ledger",
        source_object="AWS.M2.CARDDEMO.TCATBALF.PS",
    ),
    "users": SeedDataset(
        token="users",
        layout_name="SECUSER",
        dataset_segment="users",
        domain="auth",
        # WHY : Assumptions: the security extract ships in EBCDIC form ONLY -- there is no
        #   app/data/ASCII/usrsec.txt -- so this is the single available source rather than one of
        #   a pair. Every other descriptor here also names its EBCDIC form, so the set is uniform.
        source_object="AWS.M2.CARDDEMO.USRSEC.PS",
    ),
}


def _validate_registry() -> None:
    """Refuse a self-inconsistent registry at import time.

    Purpose
    -------
    Turn a mistyped layout name, a mismatched key or a source object carrying a path into an
    immediate import failure, so the defect surfaces in a build rather than in a nightly branch.

    Returns
    -------
    None
        Returns nothing when every descriptor is consistent.

    Raises
    ------
    SeedDatasetError
        If a key disagrees with its descriptor's token, a layout name is not registered, or a
        source object is not a bare file name.
    """
    registered = set(layouts.names())
    for key, descriptor in _SEED_DATASETS.items():
        if key != descriptor.token:
            raise SeedDatasetError(
                f"seed dataset registry key {key!r} disagrees with its token {descriptor.token!r}"
            )
        # WHY : Assumptions: the layout name is checked against the layout registry rather than
        #   trusted, because it is the hinge the whole descriptor turns on: record geometry, field
        #   offsets and the sensitivity policy are all reached through it. A name that is merely
        #   plausible would produce a KeyError deep inside a reader at staging time.
        if descriptor.layout_name not in registered:
            raise SeedDatasetError(
                f"seed dataset {key!r} names layout {descriptor.layout_name!r}, which is not "
                f"registered; the registered layouts are {', '.join(sorted(registered))}"
            )
        # WHY : Assumptions: a source object must be a BARE FILE NAME. A value carrying a
        #   separator would silently escape the deployment's staging root once joined to it --
        #   ``../`` most obviously, but an absolute path just as effectively -- so the constraint
        #   is enforced here where it can be stated once for all ten.
        if "/" in descriptor.source_object or "\\" in descriptor.source_object:
            raise SeedDatasetError(
                f"seed dataset {key!r} names source object {descriptor.source_object!r}; a "
                f"source object must be a bare file name, resolved against the staging root"
            )
        if descriptor.retention_limit < 1:
            raise SeedDatasetError(
                f"seed dataset {key!r} declares retention {descriptor.retention_limit}, which "
                f"would keep no generation at all"
            )


_validate_registry()

#: Read-only view of every seed-dataset descriptor, keyed by orchestrator token.
SEED_DATASETS: Mapping[str, SeedDataset] = MappingProxyType(_SEED_DATASETS)


def seed_dataset_tokens() -> tuple[str, ...]:
    """List every orchestrator seed-dataset token this package describes.

    Purpose
    -------
    Publish the closed set the orchestrator may iterate over, so a caller can validate a token
    and report the legal values without reaching into the registry mapping.

    Returns
    -------
    tuple[str, ...]
        The tokens in the registry's declared dependency order, not sorted.

    Raises
    ------
    None
    """
    # WHY : Trade-offs: declaration order is preserved rather than sorted. Sorting would give a
    #   stable diff but would scramble the dependency order the table is deliberately written in,
    #   and a caller that needs a canonical order can sort what it receives.
    return tuple(_SEED_DATASETS)


def seed_dataset(token: str) -> SeedDataset:
    """Resolve one orchestrator token to its full descriptor.

    Purpose
    -------
    Turn the single value that crosses the process boundary into every other value staging needs,
    so no caller pairs a domain with a dataset for itself.

    Parameters
    ----------
    token : str
        The orchestrator's dataset name, as passed to ``--dataset``.

    Returns
    -------
    SeedDataset
        The descriptor registered for that token.

    Raises
    ------
    SeedDatasetError
        If no descriptor is registered under that token. The message lists every legal value,
        because the caller is usually an orchestrator definition a human has just edited.
    """
    descriptor = _SEED_DATASETS.get(token)
    if descriptor is None:
        raise SeedDatasetError(
            f"unknown seed dataset {token!r}; the registered seed datasets are "
            f"{', '.join(seed_dataset_tokens())}"
        )
    return descriptor


def record_length(descriptor: SeedDataset) -> int:
    """Report a seed dataset's declared fixed-record length.

    Purpose
    -------
    Read record geometry from the layout registry on demand, so the width is single-sourced there
    and this module holds no second copy of it to fall out of date.

    Parameters
    ----------
    descriptor : SeedDataset
        The descriptor whose record length is wanted.

    Returns
    -------
    int
        The declared record length in bytes, from the descriptor's layout.

    Raises
    ------
    SeedDatasetError
        If the descriptor's layout is not registered. Import-time validation makes this
        unreachable for the shipped registry, and it is still raised rather than allowed to
        surface as a ``KeyError`` from the layout registry.
    """
    try:
        return layouts.layout(descriptor.layout_name).reclen
    except KeyError as exc:
        raise SeedDatasetError(
            f"seed dataset {descriptor.token!r} names layout {descriptor.layout_name!r}, which "
            f"is not registered"
        ) from exc
