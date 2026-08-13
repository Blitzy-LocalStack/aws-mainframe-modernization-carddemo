"""Bind each orchestrator seed-dataset token to everything staging it requires.

Purpose
-------
Hold the ONE authoritative description of the eleven seed datasets the nightly chain stages, so
that the orchestrator, the command line and the staging loader all name the same things by the
same names. Before this module existed the three spoke three pairwise-disjoint vocabularies:

* the Step Functions ``seed_datasets`` variable iterated over plural snake-case tokens --
  ``accounts``, ``card_xref``, ``transaction_category_balances`` -- and passed each as
  ``--dataset``;
* the command line validated ``--dataset`` against the copybook layout registry, whose keys are
  short upper-case layout names -- ``ACCOUNT``, ``XREF``, ``TCATBAL``; and
* the staging loader's own family registry is keyed by provisioned generation-dataset segment --
  ``transact-bkup``, ``tcatbalf-bkup`` -- which is a legitimately different axis, naming the ten
  GDG *output* families rather than the eleven *input* masters.

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

**Sources are named, not located.** A descriptor holds a file NAME and never a container of it, so
the same name resolves against whichever container the caller supplies: an object-storage prefix
for :func:`carddemo_migration.loaders.s3_stage.extract_source_key`, or the root named by
:data:`STAGING_ROOT_VARIABLE` for the ``stage-dataset`` and ``decode-record`` subcommands. The
container image deliberately ships no extract -- its Dockerfile copies only ``src/`` and ``sql/``
-- so baking a path in would name a file that is not in the image. Naming the file and resolving
the container at run time lets the same image read from a mounted volume, an object-storage landing
prefix or a checkout without being rebuilt.

Assumptions: that root is a filesystem directory **or** an ``s3://bucket/prefix`` URI, and it is
resolved by :mod:`carddemo_migration.loaders.s3_stage` rather than here, because this module
locates nothing. The URI form exists because a task has no operator filesystem to mount: it starts
from the image, and the image holds no extract, so a root naming a local directory can only be
satisfied by an operator who has arranged a volume -- which the orchestrator does not provision.

Refactoring Rationale: this paragraph said the directory "comes from the deployment through
:data:`STAGING_ROOT_VARIABLE`", and the ORCHESTRATED branch does not set that variable. The nightly
branch runs ``refresh-dataset``, which resolves the name against the dataset bucket's
source-extract prefix passed as ``--extract-prefix``; the filesystem variable was withdrawn from
the state machine because nothing in the deployable package provisioned a filesystem to satisfy it.
The variable itself is KEPT, and is not vestigial: it is how an operator points ``stage-dataset`` at
an extract -- a local one from a checkout, which is the one path that needs neither credentials nor
a bucket, or an object-storage one when a task is invoked by hand.

**One seed form for all ten.** Every descriptor names the EBCDIC dataset -- the
``AWS.M2.CARDDEMO.`` family under ``app/data/EBCDIC/`` -- and :func:`_validate_registry` asserts
that naming, so :data:`SEED_SOURCE_ENCODING` states the seed form ONCE for the whole registry
rather than per descriptor. Alternatives Considered: a per-descriptor ``encoding`` field. Rejected
because ten copies of one value are ten chances to disagree, and because the fact being recorded
is a property of the registry -- these are the EBCDIC extracts -- rather than of any single row.

**A root may be an object-store prefix, not only a directory.** :func:`object_store_location`
reads :data:`STAGING_ROOT_VARIABLE` and reports whether it names a bucket prefix or a local
directory, and :func:`extract_location` resolves one descriptor against whichever it is. This
exists because "resolving the root at run time" was true of the code and false of the deployment:
the batch task runs on Fargate with no volume mount and an image holding no extract, so a local
root named a directory that could not exist and every staging branch would have reported a missing
file. An object-store prefix is a location the export can actually deliver to and the task can
actually read, and it is provisioned by the same Terraform that provisions the dataset bucket.
Both forms are kept because the local one is what an operator uses to stage from a checkout.

Notes
-----
The eleven tokens and their bindings were measured against the repository rather than transcribed.
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
from pathlib import Path
from types import MappingProxyType
from typing import Final

from carddemo_migration.copybook import layouts

__all__ = [
    "BACKUP_FAMILIES",
    "DEFAULT_GENERATION_RETENTION",
    "EBCDIC_SOURCE_PREFIX",
    "SEED_SOURCE_ENCODING",
    "STAGING_ROOT_VARIABLE",
    "SEED_DATASETS",
    "BackupFamily",
    "SeedDataset",
    "SeedDatasetError",
    "backup_family",
    "is_seed_dataset_identifier",
    "is_seed_dataset_token",
    "OBJECT_STORE_SCHEME",
    "ObjectStoreLocation",
    "dataset_identifiers",
    "extract_location",
    "layout_name_for",
    "object_store_location",
    "record_length",
    "seed_dataset",
    "seed_dataset_tokens",
]

#: Number of newest logical generations a staged family keeps, the ``LIMIT(5) SCRATCH`` analogue.
#: Every one of the ten baseline generation bases is defined at ``LIMIT(5)``, so this is one value
#: rather than a per-descriptor field: a descriptor may still override it, but none needs to.
DEFAULT_GENERATION_RETENTION: Final[int] = 5

#: Environment variable naming where the seed extracts are read from -- either a filesystem
#: directory or an object-store prefix in the :data:`OBJECT_STORE_SCHEME` scheme. It is read by the
#: ``stage-dataset`` and ``decode-record`` subcommands and by the ``verify-all`` gate, and it is
#: deliberately not defaulted to a path inside the image; see the module docstring's "Sources are
#: named, not located".
#:
#: Refactoring Rationale: the note here read that the orchestrated nightly branch does NOT set this
#: variable, on the grounds that the branch runs ``refresh-dataset`` and is given its source-extract
#: prefix as an argument. That is still true of the refresh branch and is no longer true of the
#: chain: the verification gate resolves every registered extract from this variable and refuses to
#: run when it is unset, so the state machine supplies it to both states from one composed value.
#: Calling it an operator-invocation affordance would now understate the deployed contract.
STAGING_ROOT_VARIABLE: Final[str] = "CARDDEMO_DATASET_STAGING_ROOT"

#: Dataset-name prefix every registered source object carries, which is the naming convention of
#: the EBCDIC extracts under ``app/data/EBCDIC/``. It is asserted at import rather than assumed,
#: because it is what makes :data:`SEED_SOURCE_ENCODING` one fact instead of ten: the ASCII forms
#: of the same data are lower-case ``*.txt`` names, so a descriptor that had drifted onto one of
#: them would no longer carry this prefix and would be refused here rather than decoded wrongly.
EBCDIC_SOURCE_PREFIX: Final[str] = "AWS.M2.CARDDEMO."

#: Seed form every registered extract is in, spelled as the ``--encoding`` value that selects it.
#:
#: Assumptions: this is a property of the registry rather than of a descriptor, and it is used ONLY
#: to supply a default when a caller names a seed token instead of a layout and a path. A caller
#: that states ``--encoding`` still overrides it, because an operator decoding a hand-converted
#: extract is a legitimate case this default must not take away.
SEED_SOURCE_ENCODING: Final[str] = "ebcdic"

#: URI scheme that marks a root as an object-store prefix rather than a local directory.
#:
#: Assumptions: the scheme is spelled HERE rather than in the staging loader, and the placement is
#: load-bearing rather than stylistic. `test_the_staging_module_constructs_no_literal_endpoint`
#: asserts that no executable string literal in the staging loader contains `"://"`, because a
#: literal endpoint in that module would create a second code path only one environment exercises.
#: This module holds no client and constructs no request, so the vocabulary lives here and the
#: loader receives an already-parsed bucket and key.
OBJECT_STORE_SCHEME: Final[str] = "s3"

#: Separator between the scheme and the authority, held as a constant so the parser below and the
#: renderer on :class:`ObjectStoreLocation` cannot spell one URI two ways.
_SCHEME_SEPARATOR: Final[str] = "://"


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


# WHY : Assumptions: the eleven entries are ordered as the baseline's own load sequence, which is
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
    # WHY : Refactoring Rationale: this entry was MISSING and its absence made the combined
    #   verification gate unrunnable. `ledger.daily_transactions` is one of the eleven declared load
    #   targets, its `amount` column is one of the nine the committed money-total query totals, and
    #   `readers/dalytran.py` declares a committed seed extract -- so verification pass 3 REQUIRES
    #   a DALYTRAN source total and refuses the whole run without one. With no token, no staging
    #   branch and no load branch could be scheduled for it either, so the table stayed empty
    #   while the query totalled it. AAP section 0.4.1.5 names `DALYTRAN.PS / CVTRA06Y / 350` as
    #   one of the eleven dataset-to-copybook-to-record-length contracts, so this is a gap being
    #   closed rather than a dataset being invented.
    # WHY : Assumptions: this is a DIFFERENT extract from the one the `transactions` token names.
    #   That token names the 350-byte `DALYTRAN.PS.INIT` initializer the baseline REPROs the
    #   TRANSACT cluster from; this one names `DALYTRAN.PS`, the 105 000-byte daily-transaction
    #   input -- exactly 300 records of 350 bytes. Two tokens over two files that differ by a suffix
    #   looks like a duplicate until traced, which is why the distinction is recorded here.
    "daily_transactions": SeedDataset(
        token="daily_transactions",
        layout_name="DALYTRAN",
        dataset_segment="daily_transactions",
        domain="ledger",
        source_object="AWS.M2.CARDDEMO.DALYTRAN.PS",
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
        #   is enforced here where it can be stated once for all eleven.
        if "/" in descriptor.source_object or "\\" in descriptor.source_object:
            raise SeedDatasetError(
                f"seed dataset {key!r} names source object {descriptor.source_object!r}; a "
                f"source object must be a bare file name, resolved against the staging root"
            )
        # WHY : Assumptions: the source object must carry :data:`EBCDIC_SOURCE_PREFIX`, and this
        #   check is what lets :data:`SEED_SOURCE_ENCODING` be a single constant. The seed data
        #   ships in two forms -- the EBCDIC datasets named ``AWS.M2.CARDDEMO.*`` and the ASCII
        #   forms named ``*.txt`` -- and only the first is fixed-length with sign overpunch. A
        #   descriptor edited onto an ASCII form would otherwise keep the EBCDIC default and decode
        #   text as packed bytes, which produces plausible wrong numbers rather than an error.
        if not descriptor.source_object.startswith(EBCDIC_SOURCE_PREFIX):
            raise SeedDatasetError(
                f"seed dataset {key!r} names source object {descriptor.source_object!r}, which "
                f"does not carry the {EBCDIC_SOURCE_PREFIX!r} prefix every EBCDIC extract has; "
                f"the registry's single {SEED_SOURCE_ENCODING} seed form assumes that naming"
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


def is_seed_dataset_token(value: str) -> bool:
    """Report whether a value names a registered seed dataset.

    Purpose
    -------
    Let a caller that accepts EITHER a seed-dataset token or a copybook layout name decide which
    vocabulary it was given, without using an exception for a routine either-or.

    Parameters
    ----------
    value : str
        The value to classify, as written on a command line.

    Returns
    -------
    bool
        ``True`` when a descriptor is registered under that exact value, ``False`` otherwise --
        including for a layout name, which is the other vocabulary a caller may be handed.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the two vocabularies are DISJOINT and that is what makes one option able
    #   to accept both unambiguously. Every token here is lower-case and plural
    #   (``accounts``, ``card_xref``), and every key of
    #   :mod:`carddemo_migration.copybook.layouts` is a short upper-case layout name
    #   (``ACCOUNT``, ``XREF``), so no value can be both. The disjointness is measured rather than
    #   arranged, and if a future layout were named in lower case this predicate would still be
    #   correct -- it answers only "is this a registered token" -- but the caller's fallback to the
    #   layout registry would become ambiguous, which is why the property is recorded here.
    return value in _SEED_DATASETS


def is_seed_dataset_identifier(value: str) -> bool:
    """Report whether a value names a registered seed dataset in EITHER accepted spelling.

    Purpose
    -------
    Let a caller that has already normalised its selector decide whether the registry carries the
    dataset, rather than whether the caller happened to be handed the token spelling. A command
    whose parser maps a token to its layout name never sees a token, so a token-only predicate
    would answer ``False`` for every dataset the registry does carry.

    Parameters
    ----------
    value : str
        The value to classify, as written on a command line or as normalised from it.

    Returns
    -------
    bool
        ``True`` when a descriptor is registered under that value as a token or as its layout name,
        ``False`` for any other value -- including a layout that is decodable but ships no seed
        extract, which is exactly the case a caller must still refuse.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the two mappings tested here are the SAME two :func:`seed_dataset`
    #   resolves through and :func:`dataset_identifiers` enumerates, so a value this predicate
    #   admits is a value that function resolves and a value it rejects is one the refusal message
    #   names both vocabularies for. Membership is tested against the mappings rather than against
    #   the published tuple so the answer does not depend on that tuple's ordering contract.
    # WHY : Alternatives Considered: widening :func:`is_seed_dataset_token` in place. Rejected
    #   because that predicate answers a genuinely different question -- "was I handed the token
    #   spelling" -- which the staging path asks when it reports which vocabulary a caller used;
    #   collapsing the two would leave that caller unable to tell them apart.
    return value in _SEED_DATASETS or value in _BY_LAYOUT_NAME


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
    descriptor = _SEED_DATASETS.get(token) or _BY_LAYOUT_NAME.get(token)
    if descriptor is None:
        raise SeedDatasetError(
            f"unknown seed dataset {token!r}; the registered seed datasets are "
            f"{', '.join(dataset_identifiers())}"
        )
    return descriptor


#: Every descriptor a second time, keyed by the copybook LAYOUT name instead of the token.
#:
#: Refactoring Rationale: this index exists so that ONE dataset vocabulary is accepted everywhere.
#: Before it, `stage-dataset` took the eleven plural snake-case tokens while `decode-record`,
#: `load-dataset` and the three verification passes took the upper-case layout names -- so the
#: batch chain's `Map` state, which iterates one dataset list and hands the same `$.dataset` value
#: to every branch, could not drive a staging branch and a load branch from one list. Two
#: vocabularies for one set of records was not a documentation gap; it was a chain that could not
#: be wired.
#:
#: Assumptions: accepting both spellings introduces NO ambiguity, and that is a measured property
#: rather than a hope: every token is lower-case with underscores and every layout name is
#: upper-case, so the two sets are provably disjoint and a value resolves to exactly one descriptor.
#: The closing check below asserts that disjointness at import, because the day a layout is named
#: `accounts` is the day this index would answer two things for one string.
_BY_LAYOUT_NAME: Final[Mapping[str, SeedDataset]] = MappingProxyType(
    {descriptor.layout_name: descriptor for descriptor in _SEED_DATASETS.values()}
)


def dataset_identifiers() -> tuple[str, ...]:
    """Return every identifier a ``--dataset`` argument accepts, in one closed list.

    Purpose
    -------
    Publish the accepted vocabulary as ONE list, so a diagnostic and a help string enumerate what
    a caller may actually pass rather than half of it.

    Parameters
    ----------
    None
        The list is derived from the registry.

    Returns
    -------
    tuple[str, ...]
        The eleven orchestrator tokens in declaration order, then the eleven layout names they
        map to.

    Raises
    ------
    None
    """
    # WHY : Trade-offs: the tokens come FIRST and the layout names second, rather than the whole
    #   list being sorted. The tokens are what the orchestrator and the runbooks pass, so they are
    #   what an operator reading a refusal message is most likely to have meant; sorting would
    #   interleave the two spellings of one dataset and read as twenty-two rather than eleven.
    return (*_SEED_DATASETS, *_BY_LAYOUT_NAME)


@dataclass(frozen=True, slots=True)
class ObjectStoreLocation:
    """One object-store bucket and key, parsed out of a URI so no caller re-parses it.

    Parameters
    ----------
    bucket : str
        The bucket name, never empty.
    key : str
        The key or key prefix beneath it. Empty when the URI named a bucket root, and never
        leading or trailing with a separator.

    Returns
    -------
    None
        Construction validates nothing; :func:`object_store_location` is what parses and validates.

    Raises
    ------
    None
        Holding two validated strings cannot fail.
    """

    bucket: str
    key: str

    def child(self, name: str) -> ObjectStoreLocation:
        """Return the location of one object beneath this prefix.

        Parameters
        ----------
        name : str
            A bare object name, as a descriptor's ``source_object`` is.

        Returns
        -------
        ObjectStoreLocation
            The same bucket, with ``name`` appended beneath :attr:`key`.

        Raises
        ------
        SeedDatasetError
            If ``name`` is empty, absolute, or carries a path separator or a parent reference. The
            check is here rather than at the call site because this is the only place a caller-
            supplied name is joined to a trusted prefix.
        """
        # WHY : Assumptions: the join refuses a separator and a parent reference even though S3 has
        #   no directory semantics, and the reason is that the resulting key is used to build a
        #   least-privilege IAM condition on a prefix. A name carrying `../` would produce a key
        #   that reads as beneath the prefix here and resolves elsewhere in a policy evaluator, so
        #   the two would disagree about what was authorised. Descriptors are already validated to
        #   be bare file names, so this refuses only a value that did not come from one.
        if not name or name.startswith("/") or "/" in name or name in {".", ".."}:
            raise SeedDatasetError(
                f"object name {name!r} is not a bare file name, so it cannot be resolved beneath a "
                "trusted prefix"
            )
        return ObjectStoreLocation(
            bucket=self.bucket, key=f"{self.key}/{name}" if self.key else name
        )

    def describe(self) -> str:
        """Render the location as the URI it was parsed from.

        Parameters
        ----------
        None
            Reads :attr:`bucket` and :attr:`key`.

        Returns
        -------
        str
            The canonical URI. Safe to log: a bucket name and a key are not credentials, and every
            key this module builds is a descriptor's own file name beneath a configured prefix.

        Raises
        ------
        None
        """
        return f"{OBJECT_STORE_SCHEME}{_SCHEME_SEPARATOR}{self.bucket}/{self.key}".rstrip("/")


def object_store_location(setting: str) -> ObjectStoreLocation | None:
    """Report whether a configured root names an object-store prefix, and parse it if it does.

    Purpose
    -------
    Give one answer to "is this root a bucket prefix or a directory", so the staging command and
    the verification gate branch on a parsed value rather than each testing the string themselves.

    Parameters
    ----------
    setting : str
        The raw value of :data:`STAGING_ROOT_VARIABLE`, already stripped of surrounding whitespace.

    Returns
    -------
    ObjectStoreLocation | None
        The parsed bucket and prefix, or ``None`` when the setting does not use
        :data:`OBJECT_STORE_SCHEME` and is therefore a local directory.

    Raises
    ------
    SeedDatasetError
        If the setting uses the scheme but names no bucket, which is a deployment mistake worth
        reporting as one rather than treating as a directory called ``s3:``.
    """
    # WHY : Trade-offs: the scheme test is exact and case-sensitive, and a value using any OTHER
    #   scheme is reported as a local directory rather than refused. Refusing every unknown scheme
    #   was the alternative and was rejected because a Windows-style drive letter and a POSIX path
    #   holding a colon would both be caught by it, and this package has exactly one remote form to
    #   recognise. Anything else being a path is the correct default for a variable documented as
    #   naming a directory.
    prefix = f"{OBJECT_STORE_SCHEME}{_SCHEME_SEPARATOR}"
    if not setting.startswith(prefix):
        return None
    remainder = setting[len(prefix) :].strip("/")
    bucket, _, key = remainder.partition("/")
    if not bucket:
        raise SeedDatasetError(
            f"{STAGING_ROOT_VARIABLE} names the {OBJECT_STORE_SCHEME} scheme but no bucket, so no "
            "extract can be located"
        )
    return ObjectStoreLocation(bucket=bucket, key=key.strip("/"))


def extract_location(descriptor: SeedDataset, setting: str) -> Path | ObjectStoreLocation:
    """Resolve one descriptor's extract against a configured root of either form.

    Purpose
    -------
    Turn a descriptor plus a root into the one thing the caller reads from, so the two-form root is
    handled in ONE place instead of at every command that needs an extract.

    Parameters
    ----------
    descriptor : SeedDataset
        The dataset whose ``source_object`` is being located.
    setting : str
        The raw value of :data:`STAGING_ROOT_VARIABLE`.

    Returns
    -------
    Path | ObjectStoreLocation
        The object's location in the store when the root names a prefix, or the source object as a
        path RELATIVE to the local root when it names a directory.

    Raises
    ------
    SeedDatasetError
        If the setting is empty, or names the object-store scheme with no bucket.
    """
    # WHY : Assumptions: the local branch returns a RELATIVE path and not the root joined to the
    #   file name. The staging loader resolves each component against the descriptor of its parent
    #   with no symbolic link followed, and it needs the boundary itself to do that -- a pre-joined
    #   absolute path would have already lost which prefix was the trusted one. The remote branch
    #   has no such distinction to preserve, because a key is not traversed.
    if not setting:
        raise SeedDatasetError(
            f"{STAGING_ROOT_VARIABLE} is not set, so the extract {descriptor.source_object} cannot "
            "be located"
        )
    remote = object_store_location(setting)
    if remote is None:
        return Path(descriptor.source_object)
    return remote.child(descriptor.source_object)


def layout_name_for(identifier: str) -> str:
    """Resolve any accepted dataset identifier to the copybook layout name.

    Purpose
    -------
    Give every command one way to turn the value a caller passed into the record identity the
    readers, the layout registry and the load targets are all keyed by.

    Parameters
    ----------
    identifier : str
        An orchestrator token or a layout name. The comparison is exact and case-sensitive, which
        is what keeps the two spellings distinguishable.

    Returns
    -------
    str
        The layout name, which is ``identifier`` itself when a layout name was passed.

    Raises
    ------
    SeedDatasetError
        If the identifier is neither a registered token nor the layout name of one, with the closed
        list of both spellings.
    """
    # WHY : Assumptions: this resolves through `seed_dataset` rather than indexing either mapping,
    #   so an unknown value is refused by ONE call with ONE message. Two lookups would produce two
    #   wordings of "that name is not registered" and they would drift.
    return seed_dataset(identifier).layout_name


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


@dataclass(frozen=True, slots=True)
class BackupFamily:
    """One generation family the baseline seeds directly from a seed extract.

    Purpose
    -------
    Bind a seed-dataset token to the ``.BKUP`` generation family whose FIRST generation the
    baseline creates by copying that extract verbatim, so the staging step can reproduce the copy
    without any component restating which extract feeds which family.

    Parameters
    ----------
    token : str
        The seed-dataset token this family is seeded from; must be a registered token.
    domain : str
        The bounded context that owns the family, and the first segment of its prefix.
    dataset_segment : str
        The S3-safe family segment, matching the key ``infra/modules/s3-datasets`` uses in
        ``var.dataset_families``.
    mainframe_base : str
        The generation-data-group base name the family replaces, for provenance.
    driver : str
        The ``file:line`` of the baseline step that performs the copy, for provenance.
    declared_record_length : int
        The record length that step's ``DCB`` declares, kept so the copy can be checked against
        the geometry the baseline wrote rather than only against the layout registry.

    Returns
    -------
    BackupFamily
        A frozen binding.

    Raises
    ------
    None
        Import-time validation checks the bindings.

    Attributes
    ----------
    token : str
        As the parameter of the same name.
    domain : str
        As the parameter of the same name.
    dataset_segment : str
        As the parameter of the same name.
    mainframe_base : str
        As the parameter of the same name.
    driver : str
        As the parameter of the same name.
    declared_record_length : int
        As the parameter of the same name.
    """

    token: str
    domain: str
    dataset_segment: str
    mainframe_base: str
    driver: str
    declared_record_length: int


# WHY : Assumptions: THREE families and no fourth. ``app/jcl/DEFGDGD.jcl`` is the only baseline job
#   that both DEFINES a generation base and immediately creates its first generation, and it does
#   so three times with ``IEBGENER``: TRANTYPE.BKUP from TRANTYPE.PS at lines 36-43,
#   TRANCATG.PS.BKUP from TRANCATG.PS at lines 59-66, and DISCGRP.BKUP from DISCGRP.PS at lines
#   82-89. Each ``SYSUT1``/``SYSUT2`` pair is a verbatim copy with ``SYSIN DD DUMMY``, so no
#   record is reformatted and no field is touched.
# WHY : Alternatives Considered: producing these three from the RELATIONAL tables after the load,
#   the way the transaction and category-balance backups are produced. Rejected because it would
#   not be the same bytes: a round trip through the reference schema re-encodes every field from
#   the decoded value, so a difference in sign convention or trailing-blank handling would appear
#   in a dataset the baseline creates by copying. Copying the extract is what the baseline does and
#   it is also the only form that cannot drift from it.
# WHY : Trade-offs: the seven remaining families are absent from this map on purpose, and the
#   absence is not an omission. Four are written by ``batch-service`` from the ledger it just
#   updated -- transact-bkup, transact-daly, tcatbalf-bkup and systran -- one by the posting step's
#   reject stream, dalyrejs, one by the combine step, transact-combined, and one by the report
#   state, tranrept. None of the seven is a copy of a seed extract, so none can be produced here.
_BACKUP_FAMILIES: dict[str, BackupFamily] = {
    "transaction_types": BackupFamily(
        token="transaction_types",
        domain="reference",
        dataset_segment="trantype-bkup",
        mainframe_base="AWS.M2.CARDDEMO.TRANTYPE.BKUP",
        driver="app/jcl/DEFGDGD.jcl:36-43",
        declared_record_length=60,
    ),
    "transaction_categories": BackupFamily(
        token="transaction_categories",
        domain="reference",
        dataset_segment="trancatg-bkup",
        mainframe_base="AWS.M2.CARDDEMO.TRANCATG.PS.BKUP",
        driver="app/jcl/DEFGDGD.jcl:59-66",
        declared_record_length=60,
    ),
    "disclosure_groups": BackupFamily(
        token="disclosure_groups",
        domain="reference",
        dataset_segment="discgrp-bkup",
        mainframe_base="AWS.M2.CARDDEMO.DISCGRP.BKUP",
        driver="app/jcl/DEFGDGD.jcl:82-89",
        declared_record_length=50,
    ),
}


def _validate_backup_families() -> None:
    """Check every backup-family binding against the registries it depends on, at import.

    Purpose
    -------
    Fail the module load rather than a nightly branch when a binding names an unregistered token,
    or when the record length the baseline's copy step declares disagrees with the layout the
    extract is read at. A disagreement there would mean the copy is not the copy the baseline made.

    Parameters
    ----------
    None
        Reads the module-level bindings.

    Returns
    -------
    None
        Returns when every binding is consistent.

    Raises
    ------
    SeedDatasetError
        If a binding names a token that is not registered, if the mapping key and the binding's own
        token disagree, or if the declared record length differs from the layout's.
    """
    for key, family in _BACKUP_FAMILIES.items():
        if key != family.token:
            raise SeedDatasetError(
                f"backup family keyed {key!r} carries token {family.token!r}; the two must agree"
            )
        if key not in _SEED_DATASETS:
            raise SeedDatasetError(
                f"backup family {key!r} names a seed-dataset token that is not registered"
            )
        declared = record_length(_SEED_DATASETS[key])
        if declared != family.declared_record_length:
            raise SeedDatasetError(
                f"backup family {key!r} declares {family.declared_record_length}-byte records from "
                f"{family.driver}, but the extract's layout declares {declared}"
            )


_validate_backup_families()

#: Read-only view of the backup-family bindings, keyed by seed-dataset token.
BACKUP_FAMILIES: Mapping[str, BackupFamily] = MappingProxyType(_BACKUP_FAMILIES)


def backup_family(token: str) -> BackupFamily | None:
    """Report the generation family a seed extract also seeds, when it seeds one.

    Purpose
    -------
    Answer the one question the staging step asks -- does copying this extract also create a
    baseline generation, and under which family -- without the caller holding a second copy of the
    three bindings.

    Parameters
    ----------
    token : str
        A seed-dataset token. An unregistered token is reported as having no backup family rather
        than raising, because the caller has already resolved the descriptor by the time it asks.

    Returns
    -------
    BackupFamily | None
        The binding when the token seeds a family, otherwise ``None``.

    Raises
    ------
    None
        An unknown token yields ``None``.
    """
    return _BACKUP_FAMILIES.get(token)


# WHY : Assumptions: the two spellings are proven DISJOINT at import rather than only in the test
#   suite, because `dataset_identifiers` publishes them as one closed list and `seed_dataset`
#   resolves a value through both indexes. If a token were ever spelled the same as a layout name,
#   one string would resolve to two descriptors and whichever index was consulted first would
#   silently decide which dataset a command acted on. That is exactly the class of defect a
#   single-vocabulary change is meant to remove, so it is refused at import.
# WHY : Trade-offs: the failure is a `SeedDatasetError` raised from this module's own import, which
#   stops every command rather than only the one that would have been ambiguous. That bluntness is
#   correct here: an ambiguous dataset vocabulary means no command's `--dataset` argument can be
#   trusted, and discovering it at import is strictly better than discovering it when a load lands
#   in the wrong table.
_COLLIDING_IDENTIFIERS: Final[frozenset[str]] = frozenset(_SEED_DATASETS) & frozenset(
    _BY_LAYOUT_NAME
)
if _COLLIDING_IDENTIFIERS:
    raise SeedDatasetError(
        "an orchestrator token and a copybook layout name must never be spelled alike, because"
        " both are accepted for --dataset and one string would then name two datasets; the"
        f" colliding identifiers are {sorted(_COLLIDING_IDENTIFIERS)}"
    )
