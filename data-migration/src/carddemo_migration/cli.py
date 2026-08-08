"""Command-line entry point for the CardDemo data-migration package.

Purpose
-------
Provide the single invocation the container ``ENTRYPOINT`` and the ``carddemo-migrate``
console script both resolve to, and dispatch it to the subcommand the caller named. The
batch staging state passes its subcommand and options as a container command override,
which is an argument list rather than a shell string, so this module is the one place
that turns that list into work.

Parameters
----------
Command-line arguments only; see :func:`build_parser` for the accepted surface. Every
subcommand reads its environment through :mod:`carddemo_migration.config`, so no
endpoint, credential or bucket name is ever accepted as an argument.

Returns
-------
An integer process status from :func:`main`, following the rubric ``tests/README.md``
section 8 fixes and :mod:`carddemo_migration.credentials` already implements: ``0``
success, ``2`` usage, ``8`` the step did not complete, ``16`` the environment could not
be reached.

Raises
------
SystemExit
    Raised by :mod:`argparse` for ``--help`` and for an unrecognised argument.
    :func:`main` converts both into a return code so an orchestrated container step
    never sees a traceback in place of a status.

Assumptions: only the subcommands whose backing modules are present in this
distribution are registered here. ``load-dataset`` and the three ``verify-*`` passes
that ``README.md`` section 5.2 contracts require ``readers/``, ``loaders/aurora.py``
and ``verify/``, none of which this distribution contains, so they are absent from the
parser rather than registered as commands that fail when invoked. A registered
subcommand that cannot do its work is worse than an unregistered one: ``--help`` would
advertise it, an orchestrator author would wire a state to it, and the failure would
arrive in a deployment rather than at the point where the command was chosen. What this
module guarantees is that every subcommand it lists is fully implemented.

Trade-offs: exit-code constants and the credential step's failure mapping are imported
from :mod:`carddemo_migration.credentials` rather than restated. The cost is that this
module depends on a sibling for four integers; the benefit is that the two entry points
for the same step cannot drift into reporting different statuses for one outcome, which
is exactly the kind of divergence a state machine branching on a numeric code would act
on silently.
"""

import argparse
import json
import logging
import os
import sys
from collections.abc import Sequence
from datetime import date
from pathlib import Path
from typing import Any, Final

from carddemo_migration import credentials, seed_datasets
from carddemo_migration.config import (
    ConfigurationError,
    DatasetStagingSettings,
    quote_identifier,
    resolve_aurora_settings,
    resolve_dataset_staging_settings,
)
from carddemo_migration.copybook import layouts
from carddemo_migration.copybook.ebcdic_codec import (
    EBCDIC_CODE_PAGE,
    EbcdicFieldDecodeError,
    EbcdicRecordLengthError,
    decode_record,
    iter_ebcdic_records,
)
from carddemo_migration.copybook.layouts import RecordSpec
from carddemo_migration.credentials import (
    EXIT_FAILED,
    EXIT_FATAL,
    EXIT_OK,
    EXIT_USAGE,
)
from carddemo_migration.loaders.aurora import AuroraLoadError, connect, load_records, target_for
from carddemo_migration.loaders.s3_stage import (
    DatasetSourceError,
    StagedObject,
    reserve_generation,
    stage_dataset_file,
)
from carddemo_migration.readers.factory import RecordReader
from carddemo_migration.seed_datasets import SeedDatasetError
from carddemo_migration.verify.checksum import digest_records
from carddemo_migration.verify.money_parity import compare_money_totals
from carddemo_migration.verify.row_counts import compare_counts

# Assumptions: the public surface is declared explicitly and in sorted order, matching
#   every other module in this package, so a reader can tell an entry point from a
#   helper without reading the bodies. ``main`` is what both declared entry points
#   resolve to; ``build_parser`` is exported because the tests assert the accepted
#   argument surface directly rather than by invoking each subcommand.
__all__ = [
    "DEFAULT_RETENTION_COUNT",
    "PROGRAM_NAME",
    "build_parser",
    "main",
]

# Assumptions: this is the ``python -m`` spelling and not the console-script name,
#   because it is the form that works from a source tree as well as from an installed
#   distribution. A usage line naming ``carddemo-migrate`` would be wrong in exactly
#   the environment where a reader is most likely to be reading it -- a checkout.
PROGRAM_NAME: Final[str] = "python -m carddemo_migration.cli"

# Assumptions: five is the retention default because it is the ``LIMIT(5) SCRATCH``
#   operand every one of the baseline's ten generation-data-group definitions carries
#   -- app/jcl/DEFGDGB.jcl lines 25-57, app/jcl/DEFGDGD.jcl lines 28-76 and
#   app/jcl/DALYREJS.jcl lines 24-26. It is a default rather than a fixed value because
#   Terraform owns the effective per-family retention and can pass its own.
DEFAULT_RETENTION_COUNT: Final[int] = seed_datasets.DEFAULT_GENERATION_RETENTION

#: Environment variable carrying the orchestrator's execution name, which the generation
#: reservation is keyed on. Step Functions already publishes it to every batch task, so this
#: module reads the value the orchestrator sets rather than introducing a second identifier.
_EXECUTION_TOKEN_VARIABLE: Final[str] = "CARDDEMO_BATCH_RUN_ID"

# Trade-offs: the table columns are the five properties ``layouts`` holds
#   authoritatively. The owning schema and the seed-extract encodings that README
#   section 6.1 tabulates are deliberately NOT printed: they are documentation-owned
#   facts with no representation in this distribution's code, and inventing a second
#   source for them here would let a table in a Markdown file and a mapping in a module
#   disagree with nothing to reconcile them. Section 6.1 remains the place to read them.
_TABLE_COLUMNS: Final[tuple[str, ...]] = (
    "dataset",
    "copybook",
    "reclen",
    "keylen",
    "provenance",
)

# Assumptions: the placeholder is a blank, so a sensitive field whose rendering is not the
#   declared width is masked from a chunk that carries none of the value at all. It exists
#   only to satisfy mask_field's width contract on that path; the redaction it produces is a
#   keyed tag, so nothing about the real value reaches the output through it.
_REDACTION_PLACEHOLDER: Final[str] = " "

_LOGGER: Final[logging.Logger] = logging.getLogger(__name__)

# Assumptions: the two decode failures a delivered extract can produce -- a record that is
#   not the declared length, and a field that cannot be decoded at its declared geometry --
#   are SIBLING ``ValueError`` subclasses rather than one hierarchy, so both are named here.
#   Naming only :class:`~carddemo_migration.copybook.layouts.LayoutError` would leave
#   :class:`~carddemo_migration.copybook.layouts.RecordLengthError` uncaught, which is the
#   commoner of the two in practice: a truncated transfer produces it on the first record.
# WHY (Trade-offs): they are caught rather than propagated because README.md section 5.2
#   contracts a non-zero EXIT for each of them, and an orchestrated batch state branching on
#   a numeric return code cannot branch on a traceback. The cost is that the stack is not
#   printed; the message carries the record number and the field, which is what an operator
#   needs in order to look at the right offset of the right record.
_DECODE_ERRORS: Final[tuple[type[Exception], ...]] = (
    layouts.LayoutError,
    layouts.RecordLengthError,
)

# Assumptions: every load and verification handler refuses on the same set, because each of
#   them can fail for exactly the same four reasons -- an unregistered record name, an extract
#   that does not decode, a target that has no declared mapping, or an environment whose
#   credential cannot be resolved -- and a handler that caught a narrower set would turn one of
#   those four into a traceback purely by where it happened to be invoked from.
_STEP_ERRORS: Final[tuple[type[Exception], ...]] = (
    AuroraLoadError,
    ConfigurationError,
    *_DECODE_ERRORS,
)


def _dataset_rows() -> list[dict[str, Any]]:
    """Describe every registered record layout as one row of the dataset contract.

    Purpose
    -------
    Read the layout registry once and flatten it into rows, so both output formats
    render the same values and cannot diverge.

    Returns
    -------
    list of dict
        One mapping per registered layout, keyed by :data:`_TABLE_COLUMNS`, in the
        registry's own order.

    Raises
    ------
    None
        Every name comes from :func:`layouts.names`, so no accessor below it can raise
        the unknown-name error it reserves for a caller-supplied name.
    """
    # Assumptions: iteration follows layouts.names() rather than sorting, because that
    #   order is the registry's declaration order and matches README section 6.1's
    #   table row for row. Sorting here would produce a listing that reads as a
    #   different contract from the document it is supposed to enumerate.
    return [
        {
            "dataset": name,
            "copybook": layouts.COPYBOOK_OF[name],
            "reclen": layouts.reclen_of(name),
            "keylen": layouts.keylen_of(name),
            "provenance": layouts.provenance_of(name).value,
        }
        for name in layouts.names()
    ]


def _render_table(rows: Sequence[dict[str, Any]]) -> str:
    """Render dataset rows as a fixed-width table sized to its own content.

    Purpose
    -------
    Produce the human-readable form of the dataset contract without a formatting
    dependency.

    Parameters
    ----------
    rows : Sequence of dict
        Rows from :func:`_dataset_rows`.

    Returns
    -------
    str
        The rendered table, with no trailing newline.

    Raises
    ------
    None
    """
    # Trade-offs: column widths are measured from the content rather than fixed. A
    #   fixed width is shorter to write but silently truncates or misaligns the moment a
    #   longer record name is registered, and this table's whole purpose is to be read
    #   instead of a hard-coded list.
    widths = {
        column: max(len(column), *(len(str(row[column])) for row in rows))
        for column in _TABLE_COLUMNS
    }
    lines = [
        "  ".join(column.upper().ljust(widths[column]) for column in _TABLE_COLUMNS),
        "  ".join("-" * widths[column] for column in _TABLE_COLUMNS),
    ]
    lines.extend(
        "  ".join(str(row[column]).ljust(widths[column]) for column in _TABLE_COLUMNS)
        for row in rows
    )
    return "\n".join(line.rstrip() for line in lines)


def _list_datasets(arguments: argparse.Namespace) -> int:
    """Print the dataset contract this distribution's layout registry declares.

    Purpose
    -------
    Let a caller enumerate the migration's datasets and their geometry instead of
    hard-coding them, and do so without touching a database, an object store or a
    credential.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``format``, one of ``table`` or ``json``.

    Returns
    -------
    int
        :data:`EXIT_OK`. The registry is built at import time, so there is no runtime
        condition under which this command can partly succeed.

    Raises
    ------
    None
    """
    rows = _dataset_rows()
    if arguments.format == "json":
        # Assumptions: JSON output is a list of objects rather than an object keyed by
        #   dataset name, because the registry's ORDER is part of the contract this
        #   command publishes and an object's key order is not something a consumer may
        #   rely on. Two-space indentation matches the repository's other JSON payloads.
        print(json.dumps(rows, indent=2))
    else:
        print(_render_table(rows))
    return EXIT_OK


def _decode_record(arguments: argparse.Namespace) -> int:
    """Decode one record of a fixed-length extract and print its fields.

    Purpose
    -------
    Give an operator the one verification step that has to happen BEFORE a load rather
    than after it: prove that a delivered extract decodes at the declared geometry. It
    reads the record at the requested ordinal, decodes it field by field through
    :func:`carddemo_migration.copybook.ebcdic_codec.decode_record`, and prints the field
    map. Nothing is written, no database is opened and no credential is read.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset`` (a registered layout name), ``source`` (the extract path),
        ``record`` (a one-based ordinal) and ``code_page``.

    Returns
    -------
    int
        :data:`EXIT_OK` when the record decodes, :data:`EXIT_USAGE` when the layout name
        or the ordinal is not acceptable, and :data:`EXIT_FAILED` when the extract does
        not decode at the declared geometry.

    Raises
    ------
    None
        Every failure this command can reach is reported as an exit status, because it
        runs as a container command whose caller reads a code and not a traceback.
    """
    # Assumptions: the layout name is validated against the registry before the file is
    #   opened, so an unknown dataset costs no I/O and reports the closed set of names it
    #   could have been. The registry refuses an unknown name with LayoutError -- a
    #   ValueError subclass, NOT a KeyError -- and that exact type is caught rather than
    #   propagated for the reason the Raises section gives.
    try:
        layout = layouts.layout(arguments.dataset)
    except layouts.LayoutError:
        print(
            f"unknown dataset {arguments.dataset!r}; expected one of {', '.join(layouts.names())}",
            file=sys.stderr,
        )
        return EXIT_USAGE

    if arguments.record < 1:
        print("--record is a one-based ordinal, so it must be 1 or greater", file=sys.stderr)
        return EXIT_USAGE

    # Assumptions: the record is reached by ITERATING the dataset rather than by seeking to
    #   ordinal times record length. The two agree on a well-formed extract, and they
    #   disagree exactly where it matters: an extract whose length is not a whole multiple
    #   of the record length is a truncated or misdeclared delivery, and the iterator says
    #   so, where a seek would return a short final span and decode it as though it were
    #   whole.
    try:
        image = None
        for ordinal, candidate in enumerate(
            iter_ebcdic_records(Path(arguments.source), layout), start=1
        ):
            if ordinal == arguments.record:
                image = candidate
                break
        if image is None:
            print(
                f"{arguments.source} holds fewer than {arguments.record} records of "
                f"{layout.reclen} bytes",
                file=sys.stderr,
            )
            return EXIT_USAGE
        fields = decode_record(image, layout, code_page=arguments.code_page)
    except EbcdicRecordLengthError as failure:
        print(str(failure), file=sys.stderr)
        return EXIT_FAILED
    except EbcdicFieldDecodeError as failure:
        print(str(failure), file=sys.stderr)
        return EXIT_FAILED
    except OSError as failure:
        print(f"cannot read {arguments.source}: {failure}", file=sys.stderr)
        return EXIT_FAILED

    # Assumptions: every value is rendered with str() rather than serialised by type,
    #   because a decimal serialised as a JSON number would be re-read by most consumers
    #   as an IEEE-754 double -- which is the one thing the whole codec stack exists to
    #   avoid. A string keeps the exact digits the picture clause declares.
    rendered = {name: str(value) for name, value in fields.items()}
    print(json.dumps(_redacted(rendered, layout), indent=2))
    return EXIT_OK


def _redacted(rendered: dict[str, str], layout: RecordSpec) -> dict[str, str]:
    """Redact every sensitive field of an already-rendered record.

    Purpose
    -------
    Keep a diagnostic command from printing a cardholder name, a card number, a card
    verification value, a national identifier or a stored password, while still proving
    that each of those fields decoded at its declared width.

    Parameters
    ----------
    rendered : dict of str to str
        Field name to rendered value, as produced from a decoded record.
    layout : RecordSpec
        The layout the record was decoded against, carrying each field's sensitivity.

    Returns
    -------
    dict of str to str
        The same mapping with each sensitive field replaced by
        :func:`carddemo_migration.copybook.layouts.mask_field`'s redaction.

    Raises
    ------
    None
    """
    # Alternatives Considered: offering a --reveal flag that prints the cleartext was
    #   considered and rejected. The command exists to prove a delivery decodes at the
    #   declared geometry, and the redactions prove exactly that -- a last-four reveal shows
    #   the card number's own trailing digits, and a keyed tag is stable for one value, so a
    #   maintainer can still tell two records apart field by field. A reveal flag would put
    #   a cardholder's name and a stored password into a container log for a check that never
    #   needed them, and a flag defaulting to safe is still a flag an operator can pass.
    safe: dict[str, str] = {}
    for field in layout.fields:
        value = rendered.get(field.name)
        if value is None:
            continue
        if not field.sensitive:
            safe[field.name] = value
            continue
        # Refactoring Rationale: this comment said every sensitive field decodes to characters
        #   at its declared width, "verified across CARD and CUSTOMER, the only two layouts
        #   carrying sensitive fields", and called the placeholder branch below "unreachable for
        #   that registry". Both halves have since stopped being true, and the second is the
        #   interesting one. ACCOUNT, TCATBAL and EXPORT-ACCOUNT-DATA now carry sensitive fields
        #   too, and the fields they carry are MONEY: a decoded amount renders as "194.00" -- six
        #   characters against a declared span of twelve -- so the width test fails and the
        #   placeholder branch is taken for every one of them. Measured on record 1 of the
        #   shipped account extract: the identifier and the postal code take the direct branch at
        #   matching widths, and all five monetary fields take the placeholder branch.
        # Assumptions: the branch being reached is CORRECT rather than a fallback that happens to
        #   work, and it is what the redaction of a money field should be. mask_field over a
        #   placeholder yields a keyed tag derived from no part of the value, so an amount is
        #   withheld completely instead of being redacted to a suffix of itself -- which is what
        #   the disclosure rule requires for a monetary amount, and which the direct branch could
        #   not have given. The defensive branch turned out to be the one the policy depends on.
        # Assumptions: the branch is still needed for the reason first recorded -- mask_field
        #   refuses a mis-width chunk by raising, and a redaction that raised instead of redacting
        #   would abort the command with the value still live in the exception's own frame.
        if len(value) == field.length:
            safe[field.name] = layouts.mask_field(field, value)
        else:
            safe[field.name] = layouts.mask_field(field, _REDACTION_PLACEHOLDER * field.length)
    return safe


def _resolved_object_name(source: Path, requested: str | None) -> str:
    """Choose the object name a staged generation is written under.

    Purpose
    -------
    Apply the documented default for ``--object-name`` in one place, so the parser's
    help text and the staging call cannot describe different behaviour.

    Parameters
    ----------
    source : Path
        The local extract being staged.
    requested : str or None
        The caller's ``--object-name``, or ``None`` to derive one.

    Returns
    -------
    str
        The requested name, or the source file's own name.

    Raises
    ------
    None
        A name that the staging convention rejects is refused by the staging module,
        which owns the character contract for an object name.
    """
    # Alternatives Considered: deriving the default from the dataset identifier instead
    #   of the source file name, which would give every generation of one dataset an
    #   identical object name. Rejected because the staging prefix already carries the
    #   dataset, the business date and the generation, so the object name is the only
    #   place the provenance of the bytes survives; keeping the source file's name means
    #   an operator reading a bucket can tell which extract produced an object.
    return requested if requested is not None else source.name


def _s3_client() -> Any:
    """Build the S3 client the staging command writes through.

    Purpose
    -------
    Keep client construction behind one seam, so the staging handler can be exercised
    without an AWS SDK client and so the effective region is resolved in one place.

    Returns
    -------
    Any
        A boto3 S3 client, satisfying the staging module's ``S3StagingClient`` protocol.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If the SDK cannot build a client from the ambient configuration.
    """
    # Trade-offs: boto3 is imported here rather than at module scope. The cost is an
    #   import inside a function, which this package's config module also accepts for the
    #   same reason: it keeps `--help` and `list-datasets` runnable with no AWS SDK
    #   import at all, so the entry-point smoke check exercises argument handling without
    #   depending on a client library resolving credentials or a region.
    import boto3

    return boto3.client("s3")


class _StagingEnvironmentError(RuntimeError):
    """Raised when the deployment has not supplied something staging cannot proceed without.

    Purpose
    -------
    Separate "this deployment is not configured" from "this request is wrong" and from "this step
    failed", so the fatal tier is reported for a missing environment variable rather than the
    usage tier. It is deliberately NOT a :class:`ValueError`, because the staging module's own
    refusals are, and the two must not be caught by one clause.
    """


def _resolved_generation(
    arguments: argparse.Namespace,
    client: Any,
    settings: DatasetStagingSettings,
    descriptor: seed_datasets.SeedDataset,
    domain: str,
) -> int:
    """Resolve the generation number to write, reserving one when none was supplied.

    Purpose
    -------
    Let the orchestrator omit ``--generation`` -- which it does, and always did -- by reserving a
    number durably instead of requiring the caller to have computed one.

    Parameters
    ----------
    arguments : argparse.Namespace
        Parsed arguments, read for ``generation``.
    client : Any
        S3 client used for the reservation.
    settings : DatasetStagingSettings
        Validated bucket and prefix settings.
    descriptor : seed_datasets.SeedDataset
        The resolved seed dataset, supplying the prefix segment the reservation is keyed on.
    domain : str
        The bounded-context prefix segment the reservation is keyed on.

    Returns
    -------
    int
        The caller's explicit generation when one was given, otherwise a freshly reserved one.

    Raises
    ------
    _StagingEnvironmentError
        If no generation was given and no orchestrator execution identifier is available to key
        the reservation on.
    GenerationRetentionError
        If the reservation is refused as unacceptable.
    GenerationDiscoveryError
        If the generation space for that business date is exhausted.
    """
    if arguments.generation is not None:
        return int(arguments.generation)

    # Refactoring Rationale: `--generation` became OPTIONAL, and this is what makes that safe.
    #   It was required because a computed "next generation" is unsafe to derive per process:
    #   two branches deriving it concurrently resolve to the same number, and a retried branch
    #   re-derives the number its first attempt already wrote. Requiring it pushed that problem
    #   onto the caller, and the orchestrator did not solve it -- it simply never passed the
    #   argument, so every staging branch failed in argument parsing. `reserve_generation` makes
    #   allocation a conditional create keyed by execution, family and business date, so it is
    #   exclusive between branches and replayable across retries. That is a property the caller
    #   cannot supply, which is why deriving it here is now correct where computing it was not.
    execution_token = os.environ.get(_EXECUTION_TOKEN_VARIABLE, "").strip()
    # Assumptions: the reservation REFUSES to invent an execution identity. Falling back to a
    #   process identifier, a host name or a timestamp was the alternative and was rejected
    #   because each would make every retry look like a new execution, which is exactly the case
    #   the reservation exists to recognise -- a retry that is not recognised consumes a second
    #   generation for a byte-identical copy. The orchestrator already publishes this variable to
    #   every batch task, so an absent value means the command is running outside that context.
    if not execution_token:
        raise _StagingEnvironmentError(
            f"no --generation was given and {_EXECUTION_TOKEN_VARIABLE} is not set, so a "
            f"generation cannot be reserved for {descriptor.token!r}; set "
            f"{_EXECUTION_TOKEN_VARIABLE} to the orchestrator execution name, or pass "
            f"--generation explicitly"
        )
    return reserve_generation(
        client,
        settings,
        domain,
        descriptor.dataset_segment,
        arguments.business_date,
        execution_token,
    )


def _stage_dataset(arguments: argparse.Namespace) -> int:
    """Stage one exported extract into the versioned dataset bucket.

    Purpose
    -------
    Copy one local extract to object storage under the ``dt=``/``gen=`` generation
    prefix convention, byte for byte, and then scratch the generations that roll off.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset`` and ``business_date``, which the orchestrator supplies, and
        the optional ``source``, ``generation``, ``domain``, ``object_name`` and
        ``retain`` overrides. Every optional value has an authoritative default in the
        seed-dataset descriptor, so the two required arguments are sufficient.

    Returns
    -------
    int
        :data:`EXIT_OK` when the object is written and retention is enforced,
        :data:`EXIT_USAGE` when an argument is not acceptable, :data:`EXIT_FAILED` when
        the source cannot be staged -- absent, a symbolic link, not a regular file,
        unreadable, or modified while it was being transferred -- or when the write or the
        scratch fails, or :data:`EXIT_FATAL` when the environment could not be resolved.

    Raises
    ------
    None
        Every documented failure becomes a return code. An undocumented failure is
        deliberately not caught, because a bare ``except`` here would report a
        programming error as an operational one.
    """
    # Refactoring Rationale: the dataset name is resolved through the SEED-DATASET
    #   REGISTRY, where it was previously validated against the copybook layout registry.
    #   That validation could never accept what the orchestrator sends. Step Functions
    #   iterates plural snake-case tokens -- `accounts`, `card_xref`,
    #   `transaction_category_balances` -- while the layout registry is keyed by short
    #   upper-case layout names -- `ACCOUNT`, `XREF`, `TCATBAL` -- so every staging branch
    #   was refused here before any staging code ran. Worse, the orchestrator supplies
    #   only `--dataset` and `--business-date`, while `--source`, `--generation` and
    #   `--domain` were all required, so the command exited in argument parsing with
    #   status 2. Resolving one token and deriving the rest is what makes the
    #   orchestrator's invocation the SAME invocation this command accepts.
    # Assumptions: the token is resolved BEFORE anything is read or any client is built.
    #   The bytes are copied verbatim, so this command cannot detect a wrong dataset from
    #   the payload; the token is the only thing that decides which prefix the object
    #   lands under, and a typo would otherwise stage a real extract where nothing reads.
    try:
        descriptor = seed_datasets.seed_dataset(arguments.dataset)
    except SeedDatasetError as exc:
        _LOGGER.error("%s", exc)
        return EXIT_USAGE

    # Assumptions: the domain, the prefix segment and the retention limit all come from
    #   the descriptor unless the caller overrides them. This is the mapping whose absence
    #   previously forced `--domain` to be required: the owning bounded context was
    #   tabulated in README.md section 6.1 and nowhere in code, so no default could be
    #   offered without inventing it. The registry is now that authoritative home, so the
    #   default is a lookup rather than a guess.
    domain = arguments.domain if arguments.domain is not None else descriptor.domain
    retention = arguments.retain if arguments.retain is not None else descriptor.retention_limit

    # Trade-offs: an explicitly supplied `--source` suppresses the fixed-record-length
    #   check, while a source derived from the descriptor enables it. The asymmetry is
    #   measured rather than arbitrary: every descriptor names the EBCDIC form, and all
    #   ten of those divide exactly by their declared record length (15 000/300, 7 500/150,
    #   25 000/500, 2 500/50, 350/350, 2 550/50, 420/60, 1 080/60, 2 500/50, 800/80), so
    #   the check is free evidence there. The ASCII forms are newline-delimited and their
    #   byte lengths are NOT multiples of the record length, so applying the check to an
    #   operator-supplied path would refuse a perfectly good extract.
    if arguments.source is not None:
        source = Path(arguments.source)
        declared_length: int | None = None
    else:
        staging_root = os.environ.get(seed_datasets.STAGING_ROOT_VARIABLE, "").strip()
        # Assumptions: the staging root is REQUIRED and is never defaulted to a path
        #   inside the image. The container image ships no extract -- its Dockerfile
        #   copies only `src/` and `sql/` -- so any baked-in default would name a file
        #   that is not there, and the command would report a missing extract instead of
        #   a missing configuration. Naming the variable in the diagnostic is what tells
        #   an operator which of the two it actually is.
        if not staging_root:
            _LOGGER.error(
                "no --source was given and %s is not set, so the extract for %r cannot be "
                "located; set %s to the directory holding %s, or pass --source explicitly",
                seed_datasets.STAGING_ROOT_VARIABLE,
                descriptor.token,
                seed_datasets.STAGING_ROOT_VARIABLE,
                descriptor.source_object,
            )
            return EXIT_FATAL
        source = Path(staging_root) / descriptor.source_object
        declared_length = seed_datasets.record_length(descriptor)

    # Refactoring Rationale: the environment is resolved BEFORE the extract is touched, where the
    #   earlier shape read the source bytes first. The ordering changed because source
    #   verification moved inside `stage_dataset_file`, which must open the file exactly once --
    #   pre-reading it here to preserve the old order would restore the second open that the
    #   single-descriptor discipline exists to remove. The resulting order also matches every
    #   other subcommand in this module: argument FORM is checked first and reported as a usage
    #   error, then the environment is resolved and reported as fatal, and only then is any work
    #   attempted. One consequence is worth stating plainly, because it is observable: an absent
    #   extract in an unconfigured environment now reports the fatal tier rather than the failed
    #   tier. That is the more useful of the two answers -- nothing can be staged until the
    #   environment resolves, so the environment is the blocker to fix first.
    try:
        settings: DatasetStagingSettings = resolve_dataset_staging_settings()
    except ConfigurationError as exc:
        _LOGGER.error("the environment could not be resolved: %s", exc)
        return EXIT_FATAL

    client = _s3_client()
    # Assumptions: the effective region is reported rather than required, and the reason
    #   is measured: boto3.client("s3") does NOT raise when no region is configured -- it
    #   resolves to us-east-1 through S3's global-endpoint fallback, where
    #   boto3.client("sqs") raises NoRegionError for the same environment. So an
    #   unset AWS_REGION cannot fail this command; it can only send the write somewhere
    #   the operator did not intend, which then surfaces as a redirect or as bytes in the
    #   wrong bucket. Logging the region beside the key makes that visible in the step's
    #   own output instead of leaving it to be inferred.
    region = getattr(getattr(client, "meta", None), "region_name", None)
    _LOGGER.info(
        "staging %s to bucket %s (%s deployment) in region %s",
        descriptor.token,
        settings.bucket,
        settings.environment,
        region,
    )

    try:
        generation = _resolved_generation(arguments, client, settings, descriptor, domain)
    except _StagingEnvironmentError as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FATAL
    except DatasetSourceError as exc:
        _LOGGER.error("the generation for %r could not be reserved: %s", descriptor.token, exc)
        return EXIT_FAILED
    except ValueError as exc:
        _LOGGER.error("the staging request was not acceptable: %s", exc)
        return EXIT_USAGE

    # Refactoring Rationale: this command stages FROM THE FILE PATH through
    #   `stage_dataset_file`, replacing a `source.read_bytes()` into an in-memory buffer
    #   passed to a byte-oriented staging call. The buffered form was wrong on three
    #   counts that the hardened path fixes together, which is why the change is one
    #   substitution rather than three patches here. It read the whole extract into
    #   memory, so peak usage scaled with the dataset instead of staying bounded. It
    #   opened the pathname separately from the transfer, so the bytes measured and the
    #   bytes sent were not provably the same file. And it wrote the object with no
    #   checksum the service could verify, so an in-flight corruption landed silently.
    #   `stage_dataset_file` holds ONE descriptor across the digest and the transfer,
    #   streams it in bounded chunks, states the length, supplies ChecksumSHA256 so S3
    #   itself rejects a mismatch, and records the digest as object metadata for the later
    #   verification pass.
    # Assumptions: the prefix segment comes from the descriptor rather than from the token
    #   directly. They coincide for all ten shipped datasets and are kept separable
    #   because the prefix is a storage layout an operator browses while the token is an
    #   orchestration identifier; binding them here would make a future rename of either
    #   one silently rewrite the other.
    try:
        staged: StagedObject = stage_dataset_file(
            client=client,
            settings=settings,
            domain=domain,
            dataset=descriptor.dataset_segment,
            business_date=arguments.business_date,
            generation=generation,
            source=source,
            object_name=_resolved_object_name(source, arguments.object_name),
            retention_count=retention,
            record_length=declared_length,
        )
    except DatasetSourceError as exc:
        # Assumptions: a source failure is the FAILED tier, not the usage tier, and it is
        #   caught before the ValueError clause below because DatasetSourceError is a
        #   subclass of it -- ordering the two the other way round would silently
        #   reclassify every unreadable extract as a usage error. An absent, symlinked,
        #   non-regular or mid-flight-modified extract is an operational condition of the
        #   step's environment rather than a mistake in what the operator typed.
        _LOGGER.error("the extract at %s could not be staged: %s", source, exc)
        return EXIT_FAILED
    except ValueError as exc:
        # Assumptions: the staging module raises ValueError for a generation outside
        #   1-9999, an unacceptable object name and an unacceptable retention count --
        #   all of which are caller-supplied, so they are usage errors rather than
        #   operational ones and must not be reported as a failed step.
        _LOGGER.error("the staging request was not acceptable: %s", exc)
        return EXIT_USAGE

    # Trade-offs: the digest is logged beside the key. It costs one line of output and it
    #   buys an operator the anchor the verification pass compares against, so a staged
    #   object can be checked from the step's own log without a separate metadata read.
    _LOGGER.info(
        "staged %d bytes to %s (sha256 %s) and scratched %d rolled-off generation prefix(es)%s",
        staged.byte_size,
        staged.key,
        staged.sha256,
        len(staged.deleted_generation_prefixes),
        "".join(f"\n  scratched {prefix}" for prefix in staged.deleted_generation_prefixes),
    )
    return EXIT_OK


def _apply_credentials(arguments: argparse.Namespace) -> int:
    """Give every service login role the credential it authenticates with.

    Purpose
    -------
    Expose the credential-application step through the same entry point as the other
    subcommands, so the batch bootstrap has one command to invoke.

    Parameters
    ----------
    arguments : argparse.Namespace
        Accepted for dispatch uniformity and deliberately unused; this step takes no
        options, because a per-role invocation is what leaves a deployment half
        applied.

    Returns
    -------
    int
        Whatever :func:`carddemo_migration.credentials.main` returns.

    Raises
    ------
    None
    """
    del arguments
    # Refactoring Rationale: this delegates to credentials.main with an empty argument
    #   list instead of calling apply_service_credentials and re-deriving the status.
    #   That module already owns the mapping from each documented failure to its exit
    #   code, and duplicating the mapping is how the two spellings of one step come to
    #   disagree -- a state machine branching on 8 versus 16 would then act on which
    #   entry point had been wired, not on what happened. The empty list is passed
    #   explicitly so its parser reads no arguments instead of falling back to sys.argv,
    #   which at this point still holds this command's own subcommand name.
    return credentials.main([])


def _business_date(value: str) -> date:
    """Parse a business date in the ten-character ISO form.

    Purpose
    -------
    Give ``--business-date`` a type that rejects a malformed date at parse time, with
    argparse's own usage message, rather than deep inside a staging call.

    Parameters
    ----------
    value : str
        The argument as typed, expected as ``YYYY-MM-DD``.

    Returns
    -------
    datetime.date
        The parsed date.

    Raises
    ------
    argparse.ArgumentTypeError
        If the value is not an ISO calendar date.
    """
    # Assumptions: the ISO form is required rather than the baseline's compact
    #   yyyyMMdd00 token. The staging prefix embeds the date as dt=YYYY-MM-DD, so ISO is
    #   what the object key carries; accepting both spellings would mean two argument
    #   forms producing one key, and the ambiguity would surface only as a mis-sorted
    #   generation listing.
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"{value!r} is not an ISO calendar date in the form YYYY-MM-DD"
        ) from exc


def _reader_and_records(arguments: argparse.Namespace) -> tuple[RecordReader, Any]:
    """Resolve the reader for a record name and open its source in the requested form.

    Purpose
    -------
    Give every load and verification handler one way to obtain decoded records, so the choice
    of record and of seed form is made once rather than in each handler.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source`` and ``encoding``.

    Returns
    -------
    tuple[RecordReader, Any]
        The reader bound to the named layout, and an iterator of decoded records.

    Raises
    ------
    LayoutError
        If the record name is not a registered layout, or the named layout cannot be decoded
        from text at all. The second case is raised lazily, on the first record consumed.
    """
    # WHY : the registry is asked through ``layouts.layout`` rather than indexed into
    #   ``layouts.LAYOUTS``, so an unknown name is refused by the same call, with the same
    #   message and the same exception type, as the ``decode-record`` command already uses.
    #   Indexing the mapping directly would produce a KeyError that had to be re-wrapped, and a
    #   second wording of "that name is not registered" free to drift from the first.
    layout = layouts.layout(arguments.dataset)
    reader = RecordReader(layout)
    source = Path(arguments.source)
    # WHY : the encoding is explicit rather than sniffed from the file. The two forms are
    #   distinguishable only by inspecting bytes for characters outside the ASCII range, and a
    #   seed extract whose records happen to be all-ASCII would sniff as text while being an
    #   EBCDIC dataset -- decoding it as text then yields plausible wrong values rather than an
    #   error, which is the failure this whole verification layer exists to catch.
    if arguments.encoding == "ascii":
        return reader, reader.read_ascii(source)
    return reader, reader.read_ebcdic(source)


def _read_back(connection: Any, target: Any) -> list[dict[str, Any]]:
    """Read the loaded rows back as decoded-shaped records.

    Purpose
    -------
    Rebuild the field-keyed shape a reader yields, out of the columns the target declares, so
    the checksum verifier can digest both sides identically.

    Parameters
    ----------
    connection : Any
        An open database connection.
    target : Any
        The table target whose column mapping is inverted.

    Returns
    -------
    list[dict[str, Any]]
        One mapping per row, keyed by copybook field name.

    Raises
    ------
    None
    """
    fields = tuple(target.columns)
    names = ", ".join(quote_identifier(column) for column in target.columns.values())
    order = ", ".join(quote_identifier(column) for column in target.columns.values())
    statement = f"SELECT {names} FROM {target.qualified_name} ORDER BY {order}"  # noqa: S608
    candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        cursor.execute(statement)
        rows = cursor.fetchall()
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)
    return [dict(zip(fields, row, strict=True)) for row in rows]


def _money_field_names(reader: RecordReader) -> tuple[str, ...]:
    """List the money fields of a record, being its signed display fields.

    Parameters
    ----------
    reader : RecordReader
        The reader whose layout is inspected.

    Returns
    -------
    tuple[str, ...]
        Every loaded field declaring the signed display regime, in declaration order.

    Raises
    ------
    None
    """
    # WHY (Assumptions): a money field is exactly a SIGNED display field. An unsigned display
    #   field is an identifier or a count -- a card number, a credit score -- and totalling one
    #   would produce a number with no meaning that a source-versus-target comparison would then
    #   solemnly confirm.
    return tuple(field.name for field in reader.loaded_fields if field.kind is layouts.Kind.ZONED)


def _load_dataset(arguments: argparse.Namespace) -> int:
    """Bulk-load one decoded dataset into its target table.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source`` and ``encoding``.

    Returns
    -------
    int
        :data:`EXIT_OK` when the load committed, :data:`EXIT_FAILED` when it was rolled back.

    Raises
    ------
    None
    """
    try:
        _, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        connection = connect(resolve_aurora_settings(target.schema))
    except _STEP_ERRORS as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    try:
        written = load_records(connection, target, records)
    except AuroraLoadError as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    _LOGGER.info(
        "loaded record=%s rows=%d into %s.%s",
        arguments.dataset,
        written,
        target.schema,
        target.table,
    )
    print(f"loaded {written} row(s) of {arguments.dataset} into {target.schema}.{target.table}")
    return EXIT_OK


def _verify_row_counts(arguments: argparse.Namespace) -> int:
    """Compare one dataset's record count against its target table's row count.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source`` and ``encoding``.

    Returns
    -------
    int
        :data:`EXIT_OK` when the counts agree, otherwise :data:`EXIT_FAILED`.

    Raises
    ------
    None
    """
    try:
        _, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        connection = connect(resolve_aurora_settings(target.schema))
    except _STEP_ERRORS as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    # WHY : the decode guard is repeated around the CONSUMPTION and not only around the setup
    #   above, because ``_reader_and_records`` returns a lazy iterator: the width contract and
    #   the per-field decode are checked on the first record pulled, which happens inside
    #   ``compare_counts``, after the setup block has already returned successfully.
    try:
        outcome = compare_counts(
            connection, arguments.dataset, target.schema, target.table, records
        )
    except _DECODE_ERRORS as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    print(outcome.describe())
    # WHY : a difference exits FAILED rather than raising. The rubric the runners share treats
    #   8 as a failed check, and a verification command that raised would lose the report line
    #   an operator needs in order to see WHICH side was short.
    return EXIT_OK if outcome.matched else EXIT_FAILED


def _verify_checksum(arguments: argparse.Namespace) -> int:
    """Digest a dataset and compare it against the same digest taken over the loaded rows.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source`` and ``encoding``.

    Returns
    -------
    int
        :data:`EXIT_OK` when the two digests agree, otherwise :data:`EXIT_FAILED`.

    Raises
    ------
    None
    """
    try:
        _, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        connection = connect(resolve_aurora_settings(target.schema))
    except _STEP_ERRORS as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    fields = tuple(target.columns)
    # WHY : the source digest is taken INSIDE the block that closes the connection, even though
    #   it touches no database. Digesting drives the lazy reader, so it is where a width or
    #   decode failure surfaces -- and outside this block that failure would return without ever
    #   closing the connection it had already opened.
    try:
        source_digest = digest_records(records, fields)
        # WHY : the loaded rows are read back and digested through the SAME field order and the
        #   same canonical rendering, so the comparison is between two digests of the same
        #   construction. Comparing a source digest against a value recorded in a file would
        #   only prove the source had not changed, which is not what a load needs verifying.
        loaded = _read_back(connection, target)
    except _DECODE_ERRORS as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    target_digest = digest_records(loaded, fields)
    print(f"source {source_digest.describe()}")
    print(f"target {target_digest.describe()}")
    matched = source_digest.digest == target_digest.digest
    print(("MATCH " if matched else "DIFFER ") + f"{arguments.dataset} -> {target.qualified_name}")
    return EXIT_OK if matched else EXIT_FAILED


def _verify_money_parity(arguments: argparse.Namespace) -> int:
    """Compare a dataset's exact money totals against the target columns' own totals.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source`` and ``encoding``.

    Returns
    -------
    int
        :data:`EXIT_OK` when every money column agrees, otherwise :data:`EXIT_FAILED`.

    Raises
    ------
    None
    """
    try:
        reader, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        connection = connect(resolve_aurora_settings(target.schema))
    except _STEP_ERRORS as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    money_fields = _money_field_names(reader)
    if not money_fields:
        print(f"SKIP {arguments.dataset} declares no signed display field, so it holds no money")
        connection.close()
        return EXIT_OK
    failures = 0
    try:
        # WHY : the records are collected INSIDE the block that closes the connection, and
        #   collected rather than streamed, for two separate reasons. Inside, because draining
        #   the lazy reader is where a width or decode failure surfaces and an early return from
        #   outside would leak the open connection. Collected, because each money field is
        #   totalled in its own pass and a one-shot iterator would silently total zero on every
        #   pass after the first -- a difference of zero that reads as agreement.
        collected = list(records)
        for field in money_fields:
            column = target.columns.get(field)
            if column is None:
                # WHY : a money field the target does not map is reported and not silently
                #   passed. It means the load is dropping a monetary value, which is a finding
                #   even though this particular comparison cannot be made.
                print(f"UNMAPPED {arguments.dataset}.{field} has no target column")
                failures += 1
                continue
            outcome = compare_money_totals(
                connection,
                arguments.dataset,
                target.schema,
                target.table,
                column,
                collected,
                (field,),
            )
            print(outcome.describe())
            failures += 0 if outcome.matched else 1
    except _DECODE_ERRORS as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    return EXIT_OK if failures == 0 else EXIT_FAILED


def build_parser() -> argparse.ArgumentParser:
    """Build the argument parser for every implemented subcommand.

    Purpose
    -------
    Declare the accepted command line in one place, so the parser the tests assert
    against is the parser the container invokes.

    Returns
    -------
    argparse.ArgumentParser
        A parser whose subcommand is required and whose every registered subcommand is
        fully implemented in this module.

    Raises
    ------
    None
    """
    parser = argparse.ArgumentParser(
        prog=PROGRAM_NAME,
        description=(
            "Stage CardDemo extracts into the versioned dataset bucket, apply the "
            "database credentials the service roles authenticate with, report the "
            "record-layout contract the extracts are decoded against, and decode one "
            "record of an extract through that contract to prove it before a load."
        ),
        epilog=(
            "Exit codes follow tests/README.md section 8: 0 success, 2 usage, 8 the "
            "step did not complete, 16 the environment could not be reached. Reads "
            "CARDDEMO_ENVIRONMENT, CARDDEMO_PARAMETER_PREFIX and the TLS trust-anchor "
            "settings from the environment; no endpoint or credential is an argument."
        ),
    )
    # Assumptions: the subcommand is required and the attribute is named "handler", so
    #   dispatch is a single call with no name-to-function table to keep in step. An
    #   optional subcommand would make a bare invocation print nothing and exit zero,
    #   which for a container ENTRYPOINT is indistinguishable from a completed step.
    subcommands = parser.add_subparsers(dest="subcommand", required=True, metavar="<subcommand>")

    list_datasets = subcommands.add_parser(
        "list-datasets",
        help=(
            "print the dataset contract -- identifier, copybook, record length, key "
            "length and provenance -- for every registered record layout"
        ),
        description=(
            "Print the record-layout contract this distribution declares. Touches no "
            "database, no object store and no credential. The owning schema and the "
            "seed-extract encodings are documented in data-migration/README.md section "
            "6.1 and are not printed here."
        ),
    )
    list_datasets.add_argument(
        "--format",
        choices=("table", "json"),
        default="table",
        help="output format; 'table' for reading, 'json' for a consumer (default: table)",
    )
    list_datasets.set_defaults(handler=_list_datasets)

    decode_record_command = subcommands.add_parser(
        "decode-record",
        help="decode one record of a fixed-length extract and print its fields",
        description=(
            "Decode the record at the given one-based ordinal through the per-field "
            "codec stack and print the field map as JSON, with every value rendered as "
            "a string so no consumer re-reads a monetary amount as a floating-point "
            "number. Reads one local file; touches no database, object store or "
            "credential."
        ),
    )
    decode_record_command.add_argument(
        "--dataset",
        required=True,
        help="registered layout name, as printed by list-datasets",
    )
    decode_record_command.add_argument(
        "--source",
        required=True,
        type=Path,
        help="path to the fixed-length extract to read",
    )
    # Assumptions: the ordinal is one-based and defaults to the first record, because an
    #   operator verifying a delivery reads record 1 and because a zero-based default
    #   would make the first invocation of this command disagree with every record count
    #   the runbooks quote.
    decode_record_command.add_argument(
        "--record",
        type=int,
        default=1,
        help="one-based ordinal of the record to decode (default: 1)",
    )
    # Assumptions: the code page defaults to the mainframe-character-set page the seed
    #   extracts are delivered in, and is an argument rather than a constant only because
    #   the same layouts also describe the ASCII copies of the same datasets.
    decode_record_command.add_argument(
        "--code-page",
        default=EBCDIC_CODE_PAGE,
        help=(f"character set of the extract's display fields (default: {EBCDIC_CODE_PAGE})"),
    )
    decode_record_command.set_defaults(handler=_decode_record)

    stage_dataset = subcommands.add_parser(
        "stage-dataset",
        help="stage one exported extract to object storage under the generation prefix",
        description=(
            "Copy one seed extract verbatim to "
            "<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/<object-name> in the dataset "
            "bucket, then permanently scratch the generations that roll off. Only "
            "--dataset and --business-date are required: the domain, the source extract, "
            "the record geometry and the retention limit all come from the seed-dataset "
            "registry, and the generation is reserved."
        ),
    )
    # Assumptions: this --dataset takes a SEED-DATASET TOKEN, whereas decode-record's
    #   --dataset takes a copybook LAYOUT NAME. The two subcommands act on different
    #   things -- staging moves a named dataset's extract, decoding interprets a record
    #   against a named layout -- and each vocabulary is the authoritative one for its own
    #   job. Forcing both onto one vocabulary was considered and rejected: layout names
    #   alone cannot express the ten orchestrator tokens, and tokens alone cannot name the
    #   four layouts (TRNX, REJECT, INTTRAN and the export record) that no seed extract
    #   ships. The help text on each names which it wants so the difference is visible at
    #   the point of use rather than only here.
    stage_dataset.add_argument(
        "--dataset",
        required=True,
        help=(f"seed dataset to stage; one of {', '.join(seed_datasets.seed_dataset_tokens())}"),
    )
    stage_dataset.add_argument(
        "--business-date",
        required=True,
        type=_business_date,
        help="business date for the dt= segment, as YYYY-MM-DD",
    )
    # Refactoring Rationale: --source, --generation and --domain were all REQUIRED and are
    #   now optional overrides. They were required because this distribution held no
    #   dataset-to-context mapping, so no default could be offered without inventing one --
    #   the owning schema was tabulated in README.md section 6.1 and nowhere in code. That
    #   mapping now has an authoritative home in `carddemo_migration.seed_datasets`, so
    #   each default is a lookup rather than a guess. The change is not cosmetic: the
    #   orchestrator supplies only --dataset and --business-date, so while these three were
    #   required every staging branch exited in argument parsing with status 2 and no
    #   dataset was ever staged.
    stage_dataset.add_argument(
        "--source",
        default=None,
        help=(
            "path to the extract, copied byte for byte; defaults to the registered source "
            f"object beneath ${seed_datasets.STAGING_ROOT_VARIABLE}"
        ),
    )
    stage_dataset.add_argument(
        "--generation",
        type=int,
        default=None,
        help=(
            "generation number for the gen= segment, 1 to 9999; reserved automatically when omitted"
        ),
    )
    stage_dataset.add_argument(
        "--domain",
        default=None,
        help=(
            "bounded-context segment of the prefix; defaults to the dataset's owning "
            "context, for example account or ledger"
        ),
    )
    stage_dataset.add_argument(
        "--object-name",
        default=None,
        help="object name within the generation prefix (default: the source file name)",
    )
    stage_dataset.add_argument(
        "--retain",
        type=int,
        default=None,
        help=(
            "number of newest generations to preserve; the rest are scratched "
            f"(default: the dataset's registered limit, {DEFAULT_RETENTION_COUNT}, "
            "the baseline's LIMIT(5) SCRATCH)"
        ),
    )
    stage_dataset.set_defaults(handler=_stage_dataset)

    apply_credentials = subcommands.add_parser(
        "apply-credentials",
        help="apply every service role's credential, then prove each role can log in",
        description=(
            "Apply each generated service credential to the database role that "
            "authenticates with it, then verify every role can log in. Run immediately "
            "after data-migration/sql/V0__schemas_and_roles.sql, which creates the "
            "roles with no password. Takes no options: a per-role invocation is what "
            "leaves a deployment half applied."
        ),
    )
    apply_credentials.set_defaults(handler=_apply_credentials)

    # WHY : the four commands below share one option set -- --dataset, --source and --encoding
    #   -- because they are four questions about the same pairing of a dataset and a table, and a
    #   caller that has just loaded a record verifies it by changing only the verb.
    # Refactoring Rationale: the selector is spelled --dataset, matching decode-record and
    #   stage-dataset, after an earlier draft spelled it --record. That draft made --record mean
    #   two unrelated things depending on the verb: the LAYOUT NAME here, and the one-based
    #   ORDINAL within an extract in decode-record, which still owns that spelling. An operator
    #   following the "change only the verb" path above would have had the option rejected as
    #   unrecognised, which is the precise cost of letting one flag carry two meanings.
    for name, help_text, description, handler in (
        (
            "load-dataset",
            "bulk-load one decoded dataset into its target table",
            "Decode one seed extract and COPY it into the table the owning service declares, "
            "as a single committed unit of work. This is the migrated form of the baseline's "
            "IDCAMS REPRO load steps.",
            _load_dataset,
        ),
        (
            "verify-row-counts",
            "compare a dataset's record count against its target table's row count",
            "Count the records the source extract holds and the rows the target table holds, "
            "and report whether they agree. Catches a load that stopped early or ran twice.",
            _verify_row_counts,
        ),
        (
            "verify-checksum",
            "compare a digest of the source records against a digest of the loaded rows",
            "Digest every mapped field of every source record, read the loaded rows back and "
            "digest them the same way, then compare. Catches a corrupted field where the "
            "counts agree. No field value is printed.",
            _verify_checksum,
        ),
        (
            "verify-money-parity",
            "compare exact money totals between the source extract and the target columns",
            "Total each signed display field from the source bytes and compare against the "
            "database's own SUM of the column it loads into. Catches a sign overpunch or a "
            "decimal point read wrongly, which the other two checks can miss.",
            _verify_money_parity,
        ),
    ):
        command = subcommands.add_parser(name, help=help_text, description=description)
        command.add_argument(
            "--dataset",
            required=True,
            help="record-layout identifier; must be one reported by list-datasets",
        )
        command.add_argument(
            "--source",
            required=True,
            help="path to the local seed extract to decode",
        )
        command.add_argument(
            "--encoding",
            required=True,
            choices=("ascii", "ebcdic"),
            help=(
                "seed form of the extract; declared rather than sniffed, because an "
                "all-ASCII EBCDIC dataset would sniff as text and decode to plausible "
                "wrong values"
            ),
        )
        command.set_defaults(handler=handler)

    return parser


def main(argv: Sequence[str] | None = None) -> int:
    """Run one subcommand as a command and report its process status.

    Purpose
    -------
    Provide the callable both declared entry points resolve to -- the container
    ``ENTRYPOINT`` and the ``carddemo-migrate`` console script -- translating argparse's
    exceptions and each subcommand's outcome into the documented return code.

    Parameters
    ----------
    argv : Sequence of str or None, optional
        The argument list, excluding the program name. ``None`` -- the default -- reads
        :data:`sys.argv`.

    Returns
    -------
    int
        :data:`EXIT_OK`, :data:`EXIT_USAGE`, :data:`EXIT_FAILED` or
        :data:`EXIT_FATAL`.

    Raises
    ------
    None
        ``--help`` and an unrecognised argument both arrive as
        :class:`SystemExit` from argparse and are converted here, because a container
        step's status is the only signal an orchestrator reads.
    """
    parser = build_parser()
    try:
        arguments = parser.parse_args(argv)
    except SystemExit as exc:
        # Assumptions: argparse exits 0 for --help and 2 for a usage error, and the
        #   CMD in this package's Dockerfile is ["--help"], so the help path MUST report
        #   success -- a container whose default command exits non-zero looks like a
        #   broken image to every platform that runs it once to check.
        return EXIT_OK if exc.code in (0, None) else EXIT_USAGE

    # Trade-offs: logging is configured here rather than in each handler, and at INFO so
    #   a staged key and a scratched prefix are both reported. It matches
    #   credentials.main's format exactly so two subcommands of one entry point do not
    #   produce two log shapes for one orchestrated run to parse.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    return int(arguments.handler(arguments))


if __name__ == "__main__":
    # Assumptions: `python -m carddemo_migration.cli` runs this module as __main__, so
    #   this guard is what makes the documented invocation and the container ENTRYPOINT
    #   work. sys.exit is passed the integer directly so the process status is the
    #   return code rather than a truthiness conversion of it.
    sys.exit(main())
