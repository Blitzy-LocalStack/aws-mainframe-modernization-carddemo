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
import sys
from collections.abc import Sequence
from datetime import date
from pathlib import Path
from typing import Any, Final

from carddemo_migration import credentials
from carddemo_migration.config import (
    ConfigurationError,
    DatasetStagingSettings,
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
from carddemo_migration.loaders.s3_stage import StagedGeneration, stage_generation

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
DEFAULT_RETENTION_COUNT: Final[int] = 5

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
        # Assumptions: every sensitive field in the registry decodes to characters at its
        #   declared width, so mask_field applies directly -- verified across CARD and
        #   CUSTOMER, the only two layouts carrying sensitive fields. The width branch below
        #   is unreachable for that registry and is still written, because mask_field refuses
        #   a mis-width chunk by raising, and a redaction that raised instead of redacting
        #   would abort the command with the value still in the exception's own frame.
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


def _stage_dataset(arguments: argparse.Namespace) -> int:
    """Stage one exported extract into the versioned dataset bucket.

    Purpose
    -------
    Copy one local extract to object storage under the ``dt=``/``gen=`` generation
    prefix convention, byte for byte, and then scratch the generations that roll off.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source``, ``business_date``, ``generation``, ``domain``,
        ``object_name`` and ``retain``.

    Returns
    -------
    int
        :data:`EXIT_OK` when the object is written and retention is enforced,
        :data:`EXIT_USAGE` when an argument is not acceptable, :data:`EXIT_FAILED` when
        the source cannot be read or the write or the scratch fails, or
        :data:`EXIT_FATAL` when the environment could not be resolved.

    Raises
    ------
    None
        Every documented failure becomes a return code. An undocumented failure is
        deliberately not caught, because a bare ``except`` here would report a
        programming error as an operational one.
    """
    # Assumptions: the dataset identifier is checked against the layout registry BEFORE
    #   anything is read or any client is built. The bytes are copied verbatim, so this
    #   command cannot detect a wrong dataset from the payload; the identifier is the
    #   only thing that decides which prefix the object lands under, and a typo would
    #   otherwise stage a real extract under a name nothing reads.
    if arguments.dataset not in layouts.names():
        _LOGGER.error(
            "unknown dataset %r; the registered datasets are %s",
            arguments.dataset,
            ", ".join(layouts.names()),
        )
        return EXIT_USAGE

    source = Path(arguments.source)
    try:
        payload = source.read_bytes()
    except OSError as exc:
        _LOGGER.error("the extract at %s could not be read: %s", source, exc)
        return EXIT_FAILED

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
        "staging to bucket %s (%s deployment) in region %s",
        settings.bucket,
        settings.environment,
        region,
    )

    try:
        staged: StagedGeneration = stage_generation(
            client=client,
            settings=settings,
            domain=arguments.domain,
            dataset=arguments.dataset,
            business_date=arguments.business_date,
            generation=arguments.generation,
            object_name=_resolved_object_name(source, arguments.object_name),
            payload=payload,
            retention_count=arguments.retain,
        )
    except ValueError as exc:
        # Assumptions: the staging module raises ValueError for a generation outside
        #   1-9999, an unacceptable object name and an unacceptable retention count --
        #   all of which are caller-supplied, so they are usage errors rather than
        #   operational ones and must not be reported as a failed step.
        _LOGGER.error("the staging request was not acceptable: %s", exc)
        return EXIT_USAGE

    _LOGGER.info(
        "staged %d bytes to %s and scratched %d rolled-off generation prefix(es)%s",
        len(payload),
        staged.key,
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
            "Copy one local extract verbatim to "
            "<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/<object-name> in the dataset "
            "bucket, then permanently scratch the generations that roll off."
        ),
    )
    stage_dataset.add_argument(
        "--dataset",
        required=True,
        help="dataset identifier; must be one reported by list-datasets",
    )
    stage_dataset.add_argument(
        "--source",
        required=True,
        help="path to the local exported extract, copied byte for byte",
    )
    stage_dataset.add_argument(
        "--business-date",
        required=True,
        type=_business_date,
        help="business date for the dt= segment, as YYYY-MM-DD",
    )
    stage_dataset.add_argument(
        "--generation",
        required=True,
        type=int,
        help="generation number for the gen= segment, 1 to 9999",
    )
    # Assumptions: --domain is REQUIRED here even though it is the bounded-context
    #   segment a dataset belongs to and could in principle be derived. This
    #   distribution holds no dataset-to-context mapping -- the owning schema is
    #   tabulated in README.md section 6.1 and nowhere in code -- so a default would
    #   have to invent that mapping, and an invented default that is wrong writes a real
    #   extract to a prefix nothing reads. Requiring it keeps the caller's intent
    #   explicit until the mapping has an authoritative home.
    stage_dataset.add_argument(
        "--domain",
        required=True,
        help="bounded-context segment of the prefix, for example account or ledger",
    )
    stage_dataset.add_argument(
        "--object-name",
        default=None,
        help="object name within the generation prefix (default: the source file name)",
    )
    stage_dataset.add_argument(
        "--retain",
        type=int,
        default=DEFAULT_RETENTION_COUNT,
        help=(
            "number of newest generations to preserve; the rest are scratched "
            f"(default: {DEFAULT_RETENTION_COUNT}, the baseline's LIMIT(5) SCRATCH)"
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
