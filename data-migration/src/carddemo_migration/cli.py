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
An integer process status from :func:`main`, which IS the process exit status. It is
binary in meaning: ``0`` says the command did what it was asked, and **any** non-zero
value says it did not. Where a failure can usefully be classified the non-zero value
narrows it -- ``2`` invoked incorrectly, ``8`` the step did not complete, ``16`` the
environment or cluster could not be reached at all -- as ``data-migration/README.md``
section 5.6 specifies and :mod:`carddemo_migration.credentials` already implements.

Raises
------
SystemExit
    Raised by :mod:`argparse` for ``--help`` and for an unrecognised argument.
    :func:`main` converts both into a return code so an orchestrated container step
    never sees a traceback in place of a status.

Alternatives Considered: the repository's graded aggregate return-code rubric was
available to reuse and is deliberately NOT adopted here. That rubric carries a warn tier
in which a return of ``4`` is the documented **green** state, and it earns that tolerance
for one immutable, out-of-scope defect in the COBOL baseline; it belongs to the parity
oracle under ``tests/**`` and to nothing else. Importing it would make a non-zero status
from this CLI ambiguous between "warn, and therefore a pass" and "failed", and the
consequence is precise: a batch state branching on the number would treat a half-finished
load as a success and enable writes over it. This package has no warn tier -- a load that
half worked is a failed load -- which is why the numbers above classify failures only and
no non-zero value is ever a pass.

Alternatives Considered: the command line is built with the standard library's
:mod:`argparse` rather than a third-party CLI framework. The pinned closure in
``data-migration/requirements.txt`` contains no such framework, so adopting one would add
a distribution -- and its own transitive set -- to a hash-locked manifest whose
completeness is what makes an install reproducible, in exchange for flag parsing
:mod:`argparse` already does. Subcommands, ``--key=value`` options, type coercion and a
per-subcommand ``--help`` are all native to it.

Assumptions: only the subcommands whose backing modules are present in this
distribution are registered here. A registered subcommand that cannot do its work is
worse than an unregistered one: ``--help`` would advertise it, an orchestrator author
would wire a state to it, and the failure would arrive in a deployment rather than at
the point where the command was chosen. What this module guarantees is that every
subcommand it lists is fully implemented.

Refactoring Rationale: ``load-dataset``, ``verify-row-counts``, ``verify-checksum`` and
``verify-money-parity`` were absent from the parser for exactly as long as their backing
modules were absent, and they are registered now that :mod:`carddemo_migration.readers`,
:mod:`carddemo_migration.loaders.aurora` and :mod:`carddemo_migration.verify` are in the
distribution. The rule above did not change; what changed is which side of it these four
fall on.

Refactoring Rationale: ``verify-all`` is now registered too, and EVERY command
``README.md`` section 5.2 lists is reachable. It was withheld on the ground that
sequencing the three passes needs a dataset-to-source manifest this distribution does
not carry -- which was a reason to accept a manifest as INPUT, not a reason to withhold
the command. The cost of withholding it was not the missing convenience the old note
claimed: with only the three individual passes reachable, the only way to verify a load
was to run them separately and weigh the results, which is precisely the partial verdict
:mod:`carddemo_migration.verify` states no caller may ask for. The command takes either a
JSON manifest naming datasets in verification order or the same single-dataset triple its
three constituent passes take, and it runs passes 1, 2 and 3 in that order, stopping at
the first that does not agree.

Trade-offs: exit-code constants and the credential step's failure mapping are imported
from :mod:`carddemo_migration.credentials` rather than restated. The cost is that this
module depends on a sibling for four integers; the benefit is that the two entry points
for the same step cannot drift into reporting different statuses for one outcome, which
is exactly the kind of divergence a state machine branching on a numeric code would act
on silently.
"""

# WHY : Refactoring Rationale: annotations are deferred to strings by this import, and the reason
#   is the import structure below rather than style. The loader and the three verification passes
#   are imported INSIDE the handlers that use them, so the names they publish are not bound at
#   definition time -- and an annotation naming one of them would be evaluated then. Without this
#   line the deferral is impossible.
from __future__ import annotations

import argparse
import contextlib
import errno
import functools
import json
import logging
import os
import re
import sys
import tempfile
from collections.abc import Callable, Iterator, Mapping, Sequence
from datetime import date
from pathlib import Path
from typing import TYPE_CHECKING, Any, Final

from carddemo_migration import credentials, seed_datasets
from carddemo_migration.config import (
    ConfigurationError,
    DatasetStagingSettings,
    quote_identifier,
    resolve_aurora_settings,
    resolve_card_verification_value_key_id,
    resolve_customer_identifier_key_id,
    resolve_dataset_staging_settings,
    resolve_migration_settings,
    resolve_seed_user_subjects,
    role_for_schema,
)
from carddemo_migration.copybook import layouts

# WHY : Assumptions: `EbcdicRecordLengthError` is deliberately NOT imported, and its absence is a
#   measurement rather than an omission. It subclasses `carddemo_migration.copybook.layouts`'s
#   `RecordLengthError`, which `_DECODE_ERRORS` below names, so importing it would add a second
#   spelling of a type already covered and invite a future `except` clause to name one of the pair
#   and believe it had covered both. `EbcdicFieldDecodeError` IS imported because it is an
#   independent `ValueError` subclass that no other named type covers.
from carddemo_migration.copybook.ebcdic_codec import (
    EBCDIC_CODE_PAGE,
    EbcdicFieldDecodeError,
    decode_record,
    decode_record_fields,
    iter_ebcdic_records,
)
from carddemo_migration.copybook.layouts import RecordSpec

# WHY : Assumptions: these two refusal types are imported at MODULE scope and that costs no
#   additional module load, which is why it does not conflict with the deferred-import discipline
#   the header records. `carddemo_migration.copybook.ebcdic_codec`, imported immediately above,
#   already imports both `packed` and `zoned` for its own field decoding -- so by the time this
#   line runs both modules are in the interpreter's cache. Deferring them would buy nothing and
#   would put two class names behind a function call in a tuple every `except` clause reads.
from carddemo_migration.copybook.packed import PackedDecimalError
from carddemo_migration.copybook.zoned import ZonedDecimalError
from carddemo_migration.credentials import (
    EXIT_FAILED,
    EXIT_FATAL,
    EXIT_OK,
    EXIT_USAGE,
)
from carddemo_migration.loaders.s3_stage import (
    DatasetSourceError,
    FetchedExtract,
    GenerationConflictError,
    GenerationRetentionError,
    StagedObject,
    StagingServiceError,
    fetch_dataset_extract,
    fetch_extract,
    parse_object_uri,
    reserve_generation,
    s3_client,
    sanitized_for_log,
    stage_dataset_file,
)
from carddemo_migration.readers import dataset_reader, reader_module, ships_committed_extract
from carddemo_migration.readers.factory import RecordReader
from carddemo_migration.seed_datasets import SeedDatasetError

# WHY : Refactoring Rationale: the LOADER and the three VERIFICATION PASSES are deliberately NOT
#   imported here. They are imported inside the handlers that use them, and the change is a
#   correctness fix rather than a start-up optimisation. Every one of those modules reaches
#   `carddemo_migration.config` and, through it, the AWS SDK and the database driver, and
#   `verify.money_parity` additionally builds a money-column inventory by walking the loader's
#   target registry, and `loaders.protected_columns` reaches the key-management client. So a
#   defect in any of them -- an absent psycopg, a target whose mapping does
#   not resolve, a registry that raises while being derived -- failed `--help`, `list-datasets`,
#   `decode-record` and `stage-dataset` too. Those four commands touch no database and no verifier
#   by design, and `list-datasets` in particular is what an operator runs FIRST to learn the
#   contract; having it fail on a verifier's import defect points the diagnosis at the wrong half
#   of the distribution.
# WHY : Assumptions: `loaders.s3_stage` STAYS at module scope, and the asymmetry is deliberate
#   rather than an oversight. `stage-dataset` cannot run without it, so deferring it would move the
#   same import to the same place with no command protected; and unlike the loader it imports only
#   the standard library and `config`, whose own SDK import is already inside a function.
# WHY : Assumptions: the annotation-only names sit under `TYPE_CHECKING`, which is usable because
#   this module begins with `from __future__ import annotations`.
if TYPE_CHECKING:  # pragma: no cover - imported for annotations only
    from carddemo_migration.loaders.aurora import LoadContext, TableTarget
    from carddemo_migration.verify.money_parity import SourceExtract


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

# Refactoring Rationale: this stood as a blank placeholder that was multiplied to the
#   field's declared width and then passed through mask_field, and it is replaced by a literal
#   because that construction became a TRAP the moment its branch became reachable. The tag
#   mask_field returns is an HMAC over the chunk, so a chunk that is a constant run of blanks
#   yields a tag that is constant for the field name -- it looks exactly like the value-derived
#   tag the same function returns on the equal-width path, and a reader comparing two decoded
#   records would see identical tags for two DIFFERENT balances and conclude they matched. That
#   is the "a diff reports no difference where one exists" failure the keyed tag exists to
#   prevent, reintroduced by the one path that could not carry a real chunk.
# Assumptions: a rendering whose length is not the field's declared width is now replaced
#   by this literal, which no field of this corpus can produce and which claims nothing. It is
#   reached for every numeric sensitive field, because a decoded number is rendered as its
#   VALUE -- "194.00" for a twelve-byte zoned balance, "-1234567890.12" for a wide negative one
#   -- so it is shorter or longer than the declared width far more often than equal to it.
# Trade-offs: the value-stability a tag would have offered on that path is given up rather
#   than approximated, and nothing is lost by it. This command decodes ONE record per
#   invocation and never diffs two, so it has no use for stability; and mask_record, which does
#   render whole records for comparison, is unaffected because it slices RAW characters and
#   therefore always passes mask_field a chunk of exactly the declared width.
#   Alternatives Considered: padding or truncating the rendering to the declared width to
#   recover stability, rejected because truncation makes two balances differing only in cents
#   render identically -- the same collision, arrived at by a route that looks correct.
_WITHHELD_RENDERING: Final[str] = "<withheld>"

_LOGGER: Final[logging.Logger] = logging.getLogger(__name__)

# Assumptions: the extended ISO calendar form, and only it -- four digits, a hyphen, two, a
#   hyphen, two. This exists because `date.fromisoformat` is deliberately NOT the shape
#   authority: it accepts the whole of ISO 8601, so it would admit the basic form `20220718`,
#   the baseline's compact `PARM` token `2022071800` and the week form `2022-W27-1`. The
#   staging prefix embeds the date as `dt=YYYY-MM-DD`, so this is the one spelling that reads
#   back from an object key identically to the way it was typed.
_BUSINESS_DATE_PATTERN: Final[re.Pattern[str]] = re.compile(r"[0-9]{4}-[0-9]{2}-[0-9]{2}")

# Assumptions: the decode failures a delivered extract can produce are SIBLING ``ValueError``
#   subclasses rather than one hierarchy, so every one of them is named here. Naming only
#   :class:`~carddemo_migration.copybook.layouts.LayoutError` would leave
#   :class:`~carddemo_migration.copybook.layouts.RecordLengthError` uncaught, which is the
#   commoner of the two in practice: a truncated transfer produces it on the first record.
# WHY : Refactoring Rationale: this set held exactly TWO entries and now holds five, and the three
#   additions are the whole of the remaining decode surface rather than a sample of it. The
#   omission was reachable from every command that reads an extract: the committed
#   `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT` initializer carries four NUL bytes where `TRAN-CAT-CD`
#   is declared, so decoding it raised `ZonedDecimalError` -- a `ValueError` sibling of
#   `LayoutError`, matched by neither entry -- and `decode-record`, `verify-row-counts`,
#   `verify-checksum` and `verify-money-parity` all ended in a raw traceback with status 1, which
#   is outside the 0/2/8/16 classification README section 5.6 publishes and which a Step Functions
#   `Choice` cannot branch on. The membership is now derived from the codecs rather than guessed:
#   `zoned.ZonedSpanWidthError` and `packed.PackedSpanWidthError` are subclasses of the two named
#   bases and so need no entry of their own, and `ebcdic_codec.EbcdicRecordLengthError` is a
#   subclass of `layouts.RecordLengthError` and was already covered -- but
#   `ebcdic_codec.EbcdicFieldDecodeError`, which that codec publishes as its single field-decode
#   fault type, is an independent `ValueError` subclass and was NOT. All three gaps have one cause
#   and are closed together.
# WHY : Alternatives Considered: catching `ValueError` and being done with it. Rejected because it
#   is the one widening that cannot be audited: it would also swallow a `ValueError` raised by a
#   programming mistake in this package -- an int() over an operator-supplied string, a bad enum
#   lookup -- and report it to an operator as though the delivered extract were malformed, sending
#   them to inspect bytes that are fine. Naming the five means a genuinely unexpected `ValueError`
#   still reaches the interpreter, where it belongs.
# WHY : Assumptions: quoting these messages is safe. Every one of them is composed by this
#   distribution, and both numeric codecs already withhold the offending content for a field the
#   layout marks sensitive -- `zoned._render_content` returns a placeholder and `packed` withholds
#   unconditionally -- so the sentence carries the record ordinal, the field name and its declared
#   geometry and no cardholder value. `_reported` sanitises what is left.
# Trade-offs: they are caught rather than propagated because README.md section 5.2
#   contracts a non-zero EXIT for each of them, and an orchestrated batch state branching on
#   a numeric return code cannot branch on a traceback. The cost is that the stack is not
#   printed; the message carries the record number and the field, which is what an operator
#   needs in order to look at the right offset of the right record.
# WHY : Assumptions: the two seed forms are named once here and reached by both the parser's
#   `choices` and the verification manifest's validation, so a form accepted on the command line
#   and a form accepted in a manifest cannot diverge. They are DECLARED rather than sniffed because
#   an all-ASCII EBCDIC dataset sniffs as text and decodes to plausible wrong values.
_SEED_ENCODINGS: Final[tuple[str, ...]] = ("ascii", "ebcdic")

_DECODE_ERRORS: Final[tuple[type[Exception], ...]] = (
    layouts.LayoutError,
    layouts.RecordLengthError,
    EbcdicFieldDecodeError,
    ZonedDecimalError,
    PackedDecimalError,
)

# Refactoring Rationale: the shared refusal set used to be a module constant here. It is now
#   :func:`_step_errors`, assembled at call time, and the change is forced by the deferred imports
#   above rather than chosen: naming the loader's own failure type at module scope imported the
#   loader before any subcommand had been chosen, which is exactly what the deferral exists to
#   prevent. The reasoning the constant carried is unchanged and is restated at that function --
#   every load and verification handler refuses on the same set, because each can fail for the same
#   reasons, and a handler catching a narrower set would turn one of them into a traceback purely by
#   where it happened to be invoked from. The accessor's set is also WIDER than the constant's: it
#   adds `OSError`, the object-store source refusals and the driver's own error base, each of which
#   escaped as a traceback before.


# Refactoring Rationale: the three sealing and subject-resolving projection sets were module
#   constants here and are now locals of :func:`_load_context_for`, for the same reason the shared
#   refusal set became a function: they name members of an enum the loader publishes, so binding
#   them at module scope imported the loader before any subcommand had been chosen. The reasoning
#   they carried is unchanged and is restated where they are now built -- the decision is taken from
#   the target's OWN declaration rather than from a list of record names, so a record acquiring a
#   protected column later acquires its collaborator automatically.


def _load_context_for(target: TableTarget) -> LoadContext:
    """Resolve only the collaborators a target's own declaration asks for.

    Purpose
    -------
    Build the load context lazily and narrowly, so that loading a record with no protected
    column reaches neither the key-management service nor the parameter store, and loading one
    with a protected column resolves exactly the key its owning service reads.

    Parameters
    ----------
    target : TableTarget
        The target about to be loaded. Its declared projections decide what is resolved.

    Returns
    -------
    LoadContext
        A context carrying the ciphers and the subject document the target needs, and nothing
        else.

    Raises
    ------
    ConfigurationError
        If a needed parameter or document cannot be resolved, which is an incomplete environment
        rather than a database that refused.
    """
    # Trade-offs: each collaborator is resolved ONLY when a projection asks for it, rather
    #   than all three up front. Resolving eagerly would make `load-dataset TRANTYPE` -- a
    #   seven-row reference load needing no cipher at all -- require a key-management grant and a
    #   published subject document, so an environment that had provisioned neither could not load
    #   the reference data it needs first. It would also mean a single missing parameter blocked
    #   every load rather than the ones that actually depend on it.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import LoadContext, Projection
    from carddemo_migration.loaders.protected_columns import (
        CardVerificationValueCipher,
        CustomerIdentifierCipher,
        KmsDataKeySource,
    )

    # WHY : Assumptions: the three projection sets are built HERE rather than as module constants,
    #   so that the enum they name is resolved by the one function that reads them. What they are
    #   for is unchanged: `_load_context_for` decides what a target needs from the target's OWN
    #   declaration rather than from a list of record names, so a record that acquires a protected
    #   column later acquires its collaborator automatically -- where a name list would have left
    #   it silently uncollected, and an uncollected identifier cipher is a refused load rather
    #   than a plaintext one, but still a load that fails for a reason nobody wrote down.
    identifier_projections = frozenset({Projection.SEALED_IDENTIFIER})
    verification_value_projections = frozenset({Projection.SEALED_VERIFICATION_VALUE})
    subject_projections = frozenset({Projection.SUBJECT_FOR_USER_ID})
    declared = set(target.projections.values())
    identifier_cipher = None
    verification_cipher = None
    subjects: Mapping[str, str] = {}
    if declared & identifier_projections:
        identifier_cipher = CustomerIdentifierCipher(
            key_id=resolve_customer_identifier_key_id(),
            keys=KmsDataKeySource.from_environment(),
        )
    if declared & verification_value_projections:
        verification_cipher = CardVerificationValueCipher(
            key_id=resolve_card_verification_value_key_id(),
            keys=KmsDataKeySource.from_environment(),
        )
    if declared & subject_projections:
        subjects = resolve_seed_user_subjects()
    return LoadContext(
        identifier_cipher=identifier_cipher,
        verification_value_cipher=verification_cipher,
        subjects=subjects,
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
        # WHY : Assumptions: the refusal names BOTH accepted spellings, because the parser has
        #   already normalised a seed token to its layout name before this runs -- so a value
        #   arriving here matched NEITHER set. A message listing only the layout names would leave
        #   an operator who mistyped a token reasonably concluding that tokens are not accepted
        #   here, and rewriting a working orchestrator definition to match.
        print(
            f"unknown dataset {arguments.dataset!r}; expected a layout name "
            f"({', '.join(layouts.names())}) or a seed dataset token "
            f"({', '.join(seed_datasets.seed_dataset_tokens())})",
            file=sys.stderr,
        )
        return EXIT_USAGE

    if arguments.record < 1:
        print("--record is a one-based ordinal, so it must be 1 or greater", file=sys.stderr)
        return EXIT_USAGE

    # Assumptions: the suppressed set is resolved BEFORE the extract is opened, so the decision
    #   about what may be decoded is made without reference to the bytes and cannot be influenced
    #   by them. It also costs no I/O when it turns out to be empty, which it is for every layout
    #   but one.
    suppressed = _suppressed_field_names(layout)

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
        # Alternatives Considered: a layout carrying a suppressed field is decoded through the
        #   codec's PROJECTION entry point, so the suppressed span is never converted to characters
        #   in this process -- the same rule `readers/export_record.py` applies to the card
        #   verification value. Redacting after a whole-layout decode would put the cleartext in a
        #   local, in this frame, and in the traceback of any exception raised while it is live,
        #   which is a materialization the disclosure policy forbids however briefly it lasts.
        # Trade-offs: two call sites rather than one. `decode_record` is kept for the common case
        #   so the unrestricted decode remains provably the same call it always was, and the
        #   projection is taken only where something must be withheld.
        if suppressed:
            fields = decode_record_fields(
                image,
                layout,
                tuple(field.name for field in layout.fields if field.name not in suppressed),
                code_page=arguments.code_page,
            )
        else:
            fields = decode_record(image, layout, code_page=arguments.code_page)
    # WHY : Refactoring Rationale: this refuses on the SHARED `_DECODE_ERRORS` set where it had two
    #   hand-written clauses naming `EbcdicRecordLengthError` and `EbcdicFieldDecodeError`. Those
    #   two are what the ebcdic codec raises, and they are not what the codecs BENEATH it raise: a
    #   field whose declared span holds a value the numeric decoders refuse arrives as
    #   `ZonedDecimalError` or `PackedDecimalError`, sibling `ValueError` subclasses that neither
    #   clause matched -- so `decode-record` against the committed TRAN initializer, whose
    #   `TRAN-CAT-CD` span is four NUL bytes, ended in a traceback with status 1. Refusing on the
    #   same set the load and verification handlers refuse on is what makes the four commands agree
    #   about what a malformed delivery is, which was the actual defect: the same file decoded by
    #   two commands produced a classified status from one and a traceback from the other.
    # WHY : Assumptions: `_reported` renders the message rather than `str(failure)` directly. All
    #   five members are package-authored refusals so their own wording is quoted, and routing them
    #   through the shared renderer means anything outside printable ASCII in a field name or a
    #   decoded fragment is replaced rather than written into a line-oriented log verbatim.
    except _DECODE_ERRORS as failure:
        print(_reported(failure), file=sys.stderr)
        return EXIT_FAILED
    except OSError as failure:
        # WHY : Assumptions: the path is SANITISED before it is echoed and the failure is
        #   reported by
        #   class and errno symbol rather than by message. An operator-supplied path can carry a
        #   carriage return or a newline, and echoing it verbatim into a line-oriented log lets one
        #   argument forge a second log entry; `str(OSError)` then repeats the same path a second
        #   time through text this package does not compose, so sanitising only the first copy would
        #   leave the forgery in place.
        print(
            f"cannot read {sanitized_for_log(str(arguments.source))}: {_reported(failure)}",
            file=sys.stderr,
        )
        return EXIT_FAILED

    # Assumptions: every value is rendered with str() rather than serialised by type,
    #   because a decimal serialised as a JSON number would be re-read by most consumers
    #   as an IEEE-754 double -- which is the one thing the whole codec stack exists to
    #   avoid. A string keeps the exact digits the picture clause declares.
    rendered = {name: str(value) for name, value in fields.items()}
    print(json.dumps(_redacted(rendered, layout, suppressed), indent=2))
    return EXIT_OK


def _suppressed_field_names(layout: RecordSpec) -> frozenset[str]:
    """Return the fields of one layout that must never be decoded or represented.

    Purpose
    -------
    Ask the reader that OWNS a record which of its fields are suppressed rather than merely
    sensitive, so this command applies the same distinction the owning reader publishes. A
    sensitive field is one whose value must not be printed; a suppressed field is one whose
    value must not exist in this process at all -- the stored password in ``SECUSER`` and the
    card verification value in the export record -- and the two therefore need different
    treatment rather than the same redaction.

    Parameters
    ----------
    layout : RecordSpec
        The layout being decoded, whose registry name selects the owning reader.

    Returns
    -------
    frozenset of str
        The owning reader's ``SUPPRESSED_FIELD_NAMES``, or an empty set when the layout has no
        reader or its reader suppresses nothing.

    Raises
    ------
    None
        A layout with no reader is a normal state -- the derived layouts are readerless on
        purpose -- and is answered with an empty set rather than an error.
    """
    # Alternatives Considered: a mapping from record name to suppressed field names, declared
    #   here or in `layouts`, was rejected because it would be a SECOND authority for a question
    #   the owning reader already answers. The two would then have to be kept in step by review,
    #   and the failure mode of them drifting is that this command prints a derived form of a
    #   value the reader has declared unrepresentable -- the exact defect this function closes.
    # Assumptions: the attribute is read with a default rather than required, because most
    #   readers suppress nothing and declaring an empty constant in each of them to satisfy this
    #   lookup would add a name to twelve modules to serve one caller.
    try:
        module = reader_module(layout.name)
    except KeyError:
        return frozenset()
    return frozenset(getattr(module, "SUPPRESSED_FIELD_NAMES", frozenset()))


def _redacted(
    rendered: dict[str, str], layout: RecordSpec, suppressed: frozenset[str] = frozenset()
) -> dict[str, str]:
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
    suppressed : frozenset of str, optional
        Fields whose value must not be represented in any form. They are rendered as a fixed
        withheld marker and are never passed to the redaction helper. Defaults to none, which
        keeps the behaviour of every layout that suppresses nothing unchanged.

    Returns
    -------
    dict of str to str
        The same mapping with each sensitive field replaced by
        :func:`carddemo_migration.copybook.layouts.mask_field`'s redaction, and each suppressed
        field replaced by the fixed withheld marker.

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
        # Alternatives Considered: the suppression test comes FIRST, before the missing-value test
        #   and before the sensitivity branch, and the order is load-bearing rather than stylistic.
        #   A suppressed field is not decoded at all by the caller, so it arrives here with no
        #   value; reaching the missing-value test first would drop it from the output and lose the
        #   proof that the record carries a field at that offset, and reaching the sensitivity
        #   branch first would hand its characters to `mask_field`, which returns a keyed tag
        #   DERIVED from them. A keyed tag of a stored password is a representation of that
        #   password, and the contract the owning reader publishes for a suppressed field is that no
        #   representation exists -- not its characters, not a digest of them, not a keyed tag of
        #   them.
        # Refactoring Rationale: this branch closes the second door onto the first door's problem.
        #   `readers/usrsec.py` stopped putting the password through the masking helper when its
        #   whole-record rendering was rewritten, but THIS command reaches the same field by a
        #   different route -- a whole-layout decode followed by a per-field redaction -- and so
        #   kept printing `"SEC-USR-PWD": "<tag>"`, a keyed HMAC tag of the plaintext. Fixing one
        #   route and not the other would have left the property true of the reader and false of
        #   the system.
        if field.name in suppressed:
            safe[field.name] = _WITHHELD_RENDERING
            continue
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
            safe[field.name] = _WITHHELD_RENDERING
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
        An S3 client satisfying the staging module's ``S3StagingClient`` protocol.

    Raises
    ------
    ConfigurationError
        If the AWS SDK is not installed, or the environment names no usable region or
        credentials. Propagated from the configuration module's client factory.
    """
    # Refactoring Rationale: this delegates to the staging module's published factory, where
    #   it used to call `boto3.client("s3")` itself. Constructing a client here was a second client
    #   authority in a distribution that documents exactly one, and the duplication had a concrete
    #   consequence rather than only an architectural one: `config.aws_client` MEMOISES its clients
    #   and `config.reset_resolution_cache` discards them by scanning that module's own globals, so
    #   a client built here was invisible to that reset -- a caller that reset the package's
    #   resolution state, a test substituting an endpoint between cases or a long-running process
    #   picking up rotated credentials, would have held a fresh parameter-store client and a stale
    #   S3 client from the same call. Half-moved state is the hardest kind to attribute.
    # Assumptions: this function is KEPT as a one-line delegation rather than deleted and its
    #   call site pointed at the factory directly, because it is the seam the command tests
    #   substitute their in-process double at. Removing it would move that substitution onto an
    #   imported name and make every staging test patch the staging module instead of this one.
    return s3_client()


class _UsageError(ValueError):
    """Raised when an option's VALUE is malformed, as distinct from the step it would have driven.

    Purpose
    -------
    Separate "this command line is wrong" from "this verification failed", so a mistyped pairing
    exits on the usage tier that README.md section 5.6 assigns to a bad invocation rather than on
    the failure tier a real mismatch reports. Without the distinction a state machine branching on
    the exit status would treat an operator's typo as evidence about the data.

    Assumptions
    -----------
    It extends :class:`ValueError` rather than :class:`RuntimeError`, because a malformed option
    value is exactly that -- and the sibling :class:`_StagingEnvironmentError` extends
    :class:`RuntimeError` for the opposite reason, so the two tiers cannot be caught by one clause.
    """


class _StagingEnvironmentError(RuntimeError):
    """Raised when the deployment has not supplied something staging cannot proceed without.

    Purpose
    -------
    Separate "this deployment is not configured" from "this request is wrong" and from "this step
    failed", so the fatal tier is reported for a missing environment variable rather than the
    usage tier. It is deliberately NOT a :class:`ValueError`, because the staging module's own
    refusals are, and the two must not be caught by one clause.
    """


@contextlib.contextmanager
def _registered_extract(
    descriptor: seed_datasets.SeedDataset, client: Any | None = None
) -> Iterator[Path]:
    """Yield a local path holding one seed dataset's registered extract.

    Purpose
    -------
    Resolve the deployment's extract location -- a filesystem directory or an
    ``s3://bucket/prefix`` URI -- to a path on this task's own filesystem, so every command that
    needs the extract as a file works the same way in a checkout and in a Fargate task.

    Parameters
    ----------
    descriptor : seed_datasets.SeedDataset
        The resolved seed dataset whose registered source object is wanted.
    client : Any or None, optional
        S3 client to fetch with when the location is an object-storage URI. ``None`` -- the
        default -- builds one on demand, which is what a command that has no other S3 work does.

    Yields
    ------
    Path
        A readable path to the extract. For a filesystem location it is the operator's own file
        and is left untouched; for an object-storage location it is a copy in a scratch directory
        that is removed when the block exits.

    Raises
    ------
    _StagingEnvironmentError
        If the deployment names no extract location, or names a malformed object-storage URI.
        Both are configuration rather than request faults, so the caller reports the fatal tier.
    DatasetSourceError
        If the object exists but cannot be read, written locally, or arrives truncated.
    """
    # WHY : ⚠️ Refactoring Rationale: this resolution accepts an OBJECT-STORAGE location, and until
    #   it did the nightly staging step could not run at all. The orchestrator set this variable to
    #   an absolute filesystem path -- `/mnt/carddemo-extracts` by default -- and its Fargate task
    #   definition mounts no volume there, the image ships no extract, and no infrastructure in
    #   this repository provisions a filesystem for it. So the step resolved a path that existed
    #   nowhere and every branch failed on a missing file. Reading from the dataset bucket instead
    #   is deployable as authored: the task role already holds s3:GetObject on that bucket for the
    #   generations it writes, so the landing prefix needs no new grant.
    # WHY : Alternatives Considered: provisioning an EFS file system, mounting it on the task and
    #   keeping the filesystem contract. Rejected on cost and on operator burden -- it adds a
    #   file system, mount targets, a security group and a second place to put the same bytes,
    #   and an operator would still have to get the extracts into it, which they do today with
    #   `aws s3 cp`. The filesystem form is KEPT rather than replaced, because it is the form a
    #   developer running the CLI in a checkout uses.
    location = os.environ.get(seed_datasets.STAGING_ROOT_VARIABLE, "").strip()
    if not location:
        raise _StagingEnvironmentError(
            f"{seed_datasets.STAGING_ROOT_VARIABLE} is not set, so the extract for "
            f"{descriptor.token!r} cannot be located; set it to the directory or the "
            f"s3://bucket/prefix location holding {descriptor.source_object}"
        )

    try:
        remote = parse_object_uri(location)
    except ValueError as exc:
        raise _StagingEnvironmentError(
            f"{seed_datasets.STAGING_ROOT_VARIABLE} is not a usable location: {exc}"
        ) from exc

    if remote is None:
        # Assumptions: the filesystem branch yields the operator's own file and copies nothing.
        #   A copy would double the peak disk of a staging step for no benefit, and would also
        #   defeat the symbolic-link refusal in the staging module, which is written against the
        #   path it is given.
        yield Path(location) / descriptor.source_object
        return

    bucket, prefix = remote
    key = f"{prefix}/{descriptor.source_object}" if prefix else descriptor.source_object
    fetch_client = client if client is not None else _s3_client()
    # Assumptions: the scratch directory is created by `tempfile` under the platform temporary
    #   root rather than beside the extract, because the object-storage branch has no local
    #   directory to be beside, and a Fargate task's ephemeral storage is where a temporary file
    #   belongs. It is removed on the way out whether the block returned or raised, so a retried
    #   step never inherits a partial copy from its predecessor.
    with tempfile.TemporaryDirectory(prefix="carddemo-extract-") as scratch:
        destination = Path(scratch) / descriptor.source_object
        # WHY : Refactoring Rationale: the delivery is fetched through `fetch_extract`, where this
        #   branch used to call the plain `fetch_object_to_path`. The plain form transfers bytes and
        #   checks nothing about them, so a delivered extract that had been truncated in flight, or
        #   whose bytes were not the bytes its own recorded digest describes, was staged into a
        #   retained generation and only discovered -- if at all -- by a verification pass reading
        #   the copy. `fetch_extract` probes the object first and refuses the transfer when the
        #   length disagrees with the object's declared length, when the digest disagrees with a
        #   digest this package itself recorded, or when the byte count is not a whole multiple of
        #   the declared record width. It is the same entry point the load and verification verbs
        #   resolve an object-store source through, so one delivery is checked one way.
        # WHY : Assumptions: the record length IS supplied here, unlike the digest, which may be
        #   absent. An inbound mainframe export carries no `carddemo-sha256` metadata -- only this
        #   package's staging step writes that -- so the digest check is skipped for a first
        #   delivery and applied to a re-read of something this package staged. The record width is
        #   known from the copybook either way, and every registered source object is the EBCDIC
        #   fixed-block form whose length divides exactly by it.
        fetched = fetch_extract(
            fetch_client,
            bucket=bucket,
            key=key,
            destination=destination,
            record_length=seed_datasets.record_length(descriptor),
        )
        _LOGGER.info(
            "fetched %d byte(s) of %s from s3://%s/%s (sha256 %s)",
            fetched.byte_size,
            descriptor.token,
            bucket,
            key,
            fetched.sha256,
        )
        yield fetched.path


def _resolved_generation(
    arguments: argparse.Namespace,
    client: Any,
    settings: DatasetStagingSettings,
    descriptor: seed_datasets.SeedDataset,
    domain: str,
    dataset_segment: str,
) -> int:
    """Resolve the generation number to write, reserving one when none was supplied.

    Purpose
    -------
    Reserve the number durably instead of requiring a caller to have computed one, which is what
    let the ``--generation`` option be withdrawn from the command line entirely: the orchestrator
    never passed it, and no operator can now, so this is the only place a number is chosen. A
    caller inside this process may still pin one through the ``generation`` attribute, which is why
    the supplied value is honoured below rather than ignored.

    Parameters
    ----------
    arguments : argparse.Namespace
        Parsed arguments, read for ``generation``.
    client : Any
        S3 client used for the reservation.
    settings : DatasetStagingSettings
        Validated bucket and prefix settings.
    descriptor : seed_datasets.SeedDataset
        The resolved seed dataset, named in the refusal below when no execution identity is
        available.
    domain : str
        The bounded-context prefix segment the reservation is keyed on.
    dataset_segment : str
        The dataset prefix segment the reservation is keyed on, which is the segment the bytes will
        be staged under and not necessarily the descriptor's own.

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
    supplied = getattr(arguments, "generation", None)
    if supplied is not None:
        return int(supplied)

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
    # WHY : Refactoring Rationale: the refusal named ONE remedy and offered TWO, and the second
    #   could not be carried out. It ended "or pass --generation explicitly", but `--generation` was
    #   withdrawn from every subparser when the reservation above replaced it, so an operator
    #   following that advice got argparse's usage error and status 2 -- a refusal that reads as
    #   actionable and sends the reader to a dead end is worse than a shorter one, because it costs
    #   an attempt before it teaches anything. The clause is removed rather than reworded: setting
    #   the execution name is the whole remedy, and it is the same value the orchestrator already
    #   publishes to every batch task.
    # WHY : Assumptions: the parameter itself is NOT removed. `getattr(arguments, "generation",
    #   None)` above still honours a value composed programmatically -- `_refresh_steps` passes
    #   `generation=None` explicitly, and a caller inside this process may pin one -- so the
    #   distinction is that the pin is reachable from Python and not from the command line. Naming a
    #   command-line option in a message is a promise about the parser; naming a parameter is not.
    if not execution_token:
        raise _StagingEnvironmentError(
            f"no generation was given and {_EXECUTION_TOKEN_VARIABLE} is not set, so a "
            f"generation cannot be reserved for {descriptor.token!r}; set "
            f"{_EXECUTION_TOKEN_VARIABLE} to the orchestrator execution name"
        )
    return reserve_generation(
        client,
        settings,
        domain,
        # WHY : Assumptions: the reservation is keyed by the segment the bytes will be STAGED under,
        #   not by the descriptor's own. The two differ whenever `--dataset-segment` redirects the
        #   copy into another family, and reserving under the descriptor's segment there would claim
        #   a number in the family that is not being written and then stage into one whose numbers
        #   nothing had claimed.
        dataset_segment,
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
        Carries ``dataset`` and ``business_date``, which the command line requires, and the
        optional ``dataset_segment`` and ``retain``. It may also carry ``source``,
        ``generation``, ``domain`` and ``object_name`` when the caller is the orchestrated
        refresh, which composes this namespace rather than parsing one; the command line
        registers none of those four and every one of them defaults from the seed-dataset
        descriptor or from the durable generation reservation when absent.

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
    # Assumptions: `--dataset` names exactly ONE dataset and this command stages exactly one
    #   extract per invocation. The batch chain's StageSeedDatasets step is a Step Functions
    #   `Map` state that runs one branch per dataset, so the state machine is what supplies the
    #   fan-out; a subcommand that looped over every dataset would serialise inside a single
    #   task the work the Map runs in parallel, and would collapse ten independently retryable
    #   branches into one all-or-nothing task whose retry re-stages the nine that had already
    #   succeeded.
    # Assumptions: one invocation is therefore idempotent and safely re-runnable, which is
    #   required rather than merely desirable -- Step Functions may retry a Map branch, and the
    #   generation is RESERVED against the execution token rather than computed, so presenting
    #   the same token again returns the same number and the retry rewrites one key instead of
    #   consuming a second generation for a byte-identical copy.
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
    # WHY : Assumptions: the four withdrawn coordinates are read through `getattr` with a ``None``
    #   default rather than as attributes, because this handler serves TWO callers with two
    #   namespaces. An operator invocation arrives from the parser, which no longer registers any
    #   of them, so the attribute is absent; the orchestrated refresh arrives from
    #   `_refresh_steps`, which composes a namespace stating each one explicitly -- a fetched
    #   extract's path, and for the backup family the registry's own context and segment. Reading
    #   them defensively is what lets the withdrawal be real on the command line while the
    #   composed step keeps saying what it means.
    supplied_domain = getattr(arguments, "domain", None)
    domain = supplied_domain if supplied_domain is not None else descriptor.domain
    # WHY : Assumptions: the segment is resolved the same way the domain above it is, and BOTH are
    #   resolved before the generation is reserved. The reservation is keyed by domain and segment,
    #   so resolving after it would reserve a number in one family and stage into another.
    segment = (
        arguments.dataset_segment
        if getattr(arguments, "dataset_segment", None) is not None
        else descriptor.dataset_segment
    )
    retention = arguments.retain if arguments.retain is not None else descriptor.retention_limit

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

    # WHY : Assumptions: the client construction is inside the fatal-tier guard rather than beside
    #   it, because a missing SDK or an unusable credential chain is exactly the class of condition
    #   the environment tier names -- the resolver above answers for parameters and secrets, and
    #   this answers for the client they would have been handed to. Left outside, the refusal
    #   escaped as a traceback, which is neither tier and gives the batch state nothing to route on.
    try:
        client = _s3_client()
    except ConfigurationError as exc:
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
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

    # Trade-offs: an explicitly supplied `--source` suppresses the fixed-record-length
    #   check, while a source derived from the descriptor enables it. The asymmetry is
    #   measured rather than arbitrary: every descriptor names the EBCDIC form, and all
    #   ten of those divide exactly by their declared record length (15 000/300, 7 500/150,
    #   25 000/500, 2 500/50, 350/350, 2 550/50, 420/60, 1 080/60, 2 500/50, 800/80), so
    #   the check is free evidence there. The ASCII forms are newline-delimited and their
    #   byte lengths are NOT multiples of the record length, so applying the check to an
    #   operator-supplied path would refuse a perfectly good extract.
    # Refactoring Rationale: the registered extract is resolved through `_registered_extract`,
    #   which accepts a filesystem directory OR an `s3://bucket/prefix` location, where this
    #   command previously joined the variable to a file name and required the result to be a
    #   local path. That could not be satisfied by the step that runs it: the nightly staging
    #   task runs on Fargate from an image that ships no extract, with no volume mounted at the
    #   configured path and no infrastructure in this repository provisioning one, so every
    #   branch failed on a missing file. The resolution also moved BELOW the client, because the
    #   object-storage form needs one -- which leaves the documented order intact, since the
    #   environment is still resolved before anything is read.
    # Assumptions: the resolution is entered on an `ExitStack` that spans the staging call, so a
    #   fetched copy is still present when `stage_dataset_file` opens it and is removed once the
    #   object has been written. Fetching into a variable and cleaning up afterwards was the
    #   alternative and was rejected: every early return below would then need its own cleanup,
    #   which is exactly the shape a context manager exists to remove.
    # WHY : Assumptions: the CONTAINMENT ROOT the staging loader resolves beneath is the directory
    #   the source itself resolves in, and it is stated rather than defaulted because the loader
    #   opens every component below it with no symbolic-link following. For an operator-supplied
    #   `--source` that is the directory the operator named, so the containment means "the leaf
    #   cannot redirect the read out of the directory you pointed at" -- which is the strongest
    #   statement available when the operator names the file itself. For a fetched extract it is the
    #   task-local directory the fetch wrote into, which nothing else can reach.
    # WHY : Trade-offs: passing the source's own parent rather than a configured root. A configured
    #   root would let this command refuse a source outside it, which sounds stronger and is not:
    #   the operator names an absolute path here, so a root would either have to be inferred from
    #   that same path -- what this does -- or be a second input whose only effect is to refuse
    #   invocations the operator meant.
    with contextlib.ExitStack() as stack:
        supplied_source = getattr(arguments, "source", None)
        if supplied_source is not None:
            source = Path(supplied_source)
            staging_root = source.parent
            declared_length: int | None = None
        else:
            declared_length = seed_datasets.record_length(descriptor)
            try:
                source = stack.enter_context(_registered_extract(descriptor, client))
                staging_root = source.parent
            except _StagingEnvironmentError as exc:
                _LOGGER.error("%s", exc)
                return EXIT_FATAL
            except DatasetSourceError as exc:
                _LOGGER.error("the extract for %r could not be obtained: %s", descriptor.token, exc)
                return EXIT_FAILED

        try:
            generation = _resolved_generation(
                arguments, client, settings, descriptor, domain, segment
            )
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
        # Assumptions: the prefix segment is the one resolved above -- from `--dataset-segment`
        #   when the caller named one and from the descriptor otherwise -- and never the token
        #   itself. They coincide for all ten shipped datasets and are kept separable because the
        #   prefix is a storage layout an operator browses while the token is an orchestration
        #   identifier; binding them here would make a future rename of either one silently
        #   rewrite the other. It is also the segment the generation was reserved under, which is
        #   what keeps the reservation and the write in one family.
        try:
            staged: StagedObject = stage_dataset_file(
                client=client,
                settings=settings,
                domain=domain,
                dataset=segment,
                business_date=arguments.business_date,
                generation=generation,
                source=source,
                staging_root=staging_root,
                object_name=_resolved_object_name(source, getattr(arguments, "object_name", None)),
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
    # Assumptions: this line is emitted AFTER the stack has unwound, so a fetched copy has
    #   already been removed by the time the success is reported. The staged object is what the
    #   next step reads, so nothing downstream depends on the local copy outliving the write.
    _LOGGER.info(
        "staged %d bytes to %s (sha256 %s) and scratched %d rolled-off generation prefix(es)%s",
        staged.byte_size,
        staged.key,
        staged.sha256,
        len(staged.deleted_generation_prefixes),
        "".join(f"\n  scratched {prefix}" for prefix in staged.deleted_generation_prefixes),
    )
    return EXIT_OK


#: Default source-extract prefix inside the dataset bucket.
#
# WHY : Assumptions: this is the destination the data-migration runbook already tells an operator
#   to sync the extracts to -- `aws s3 sync app/data/ s3://<bucket>/migration/source/` -- with the
#   `EBCDIC/` segment the sync itself creates, because every `source_object` in the seed-dataset
#   registry is one of the thirteen mainframe-character-set `.PS` names. Defaulting to the
#   documented location means the orchestrated refresh reads exactly what that documented upload
#   writes; the deployment still passes the value explicitly, so this default is a convenience for
#   an operator running one refresh by hand rather than the authority for the deployed path.
_DEFAULT_EXTRACT_PREFIX: Final[str] = "migration/source/EBCDIC/"

#: Layout whose target table the transaction-identifier allocator serves.
#
# WHY : Assumptions: `reconcile-sequences` advances `ledger.transaction_id_seq`, and the only
#   dataset that loads rows carrying a sequence-format identifier into the table that allocator
#   serves is the transaction master. Naming the LAYOUT rather than the table keeps the binding
#   with the loader's own target declaration -- the schema and table are read from it below -- so a
#   table rename cannot leave this step pointing at a name nothing loads into.
_ALLOCATOR_LAYOUT: Final[str] = "TRAN"


def _fetch_seed_extract(
    descriptor: seed_datasets.SeedDataset, prefix: str, destination: Path
) -> FetchedExtract:
    """Materialise one registered seed extract from the dataset bucket onto a local path.

    Purpose
    -------
    Bridge the one gap that stopped the orchestrated refresh from being runnable at all: the
    extracts are exported from the baseline, are deliberately not in the container image, and the
    deployment holds them in a provisioned prefix of the dataset bucket rather than on a
    filesystem the stack does not provision.

    Parameters
    ----------
    descriptor : seed_datasets.SeedDataset
        The registry entry naming the extract's file name and its declared record length.
    prefix : str
        The source-extract prefix the deployment provisions.
    destination : Path
        Local path to write the bytes to, inside the caller's scratch directory.

    Returns
    -------
    FetchedExtract
        The key read, the path written, the byte count and the digest.

    Raises
    ------
    ConfigurationError
        If the dataset bucket cannot be resolved from the environment.
    DatasetSourceError
        If the object cannot be read, or what arrived contradicts what the object publishes.
    """
    settings: DatasetStagingSettings = resolve_dataset_staging_settings()
    client = _s3_client()
    # Assumptions: the declared record length IS checked on this path, unlike on the `--source`
    #   override below. The registry's `source_object` names are the fixed-block
    #   mainframe-character-set extracts, whose byte length is an exact multiple of the record
    #   length, so a truncated upload is detectable here -- before the bytes are staged as a
    #   generation and before a reader turns the remainder into plausible wrong values. The text
    #   twins are newline-delimited and would fail that check legitimately, which is precisely why
    #   the check follows the fetch path rather than the file.
    fetched = fetch_dataset_extract(
        client=client,
        settings=settings,
        prefix=prefix,
        source_object=descriptor.source_object,
        destination=destination,
        record_length=seed_datasets.record_length(descriptor),
    )
    _LOGGER.info(
        "fetched the %s extract from bucket %s: %s",
        descriptor.token,
        settings.bucket,
        fetched.describe(),
    )
    return fetched


def _refresh_steps(
    descriptor: seed_datasets.SeedDataset,
    arguments: argparse.Namespace,
    extract: Path,
) -> tuple[tuple[str, Callable[[argparse.Namespace], int], argparse.Namespace], ...]:
    """Compose the ordered steps one dataset refresh runs, as handler-and-arguments pairs.

    Purpose
    -------
    State the refresh as the SEQUENCE of steps an operator runs by hand, bound to the very same
    handlers those commands dispatch to. Reimplementing any of them here would let a step behave
    differently inside the refresh than outside it, which is the one difference an operator
    reproducing a nightly failure at a terminal could not see.

    Parameters
    ----------
    descriptor : seed_datasets.SeedDataset
        The registry entry, which supplies the staging token and the record layout name.
    arguments : argparse.Namespace
        The refresh command's own arguments, read for the business date, the seed form, the
        optional local extract and the retention override.
    extract : Path
        The local extract every step reads, whether fetched or supplied.

    Returns
    -------
    tuple[tuple[str, Callable[[argparse.Namespace], int], argparse.Namespace], ...]
        One (label, handler, arguments) triple per step, in the order they must run. A dataset whose
        layout ships no committed extract gets the staging step and the allocator reconciliation
        only, because there is nothing to load and its target is required to stay empty.

    Raises
    ------
    AuroraLoadError
        Propagated from the loader's target registry if the layout has no load target, which
        would mean the registry and the loader disagree about a dataset.
    """
    # WHY : Assumptions: the STAGING step takes the registry TOKEN and every later step takes the
    #   record LAYOUT name, and the two vocabularies are genuinely different -- `accounts` against
    #   `ACCOUNT`, `card_xref` against `XREF`. The descriptor is what binds them, which is why the
    #   refresh resolves it once and derives both rather than accepting two arguments that could
    #   disagree.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import target_for

    # WHY : Assumptions: the generation and the owning context are stated as absent here rather
    #   than forwarded from this command's own arguments, because neither is an argument any more.
    #   Absent, the staging step reserves the generation against the execution token and reads the
    #   context from the descriptor -- which is the same resolution an operator invoking
    #   `stage-dataset` by hand now gets, so the step behaves identically inside the refresh and
    #   outside it. That equivalence is the whole reason this function binds the real handlers.
    staging = argparse.Namespace(
        dataset=descriptor.token,
        business_date=arguments.business_date,
        source=str(extract),
        generation=None,
        domain=None,
        dataset_segment=None,
        object_name=None,
        retain=arguments.retain,
    )
    # WHY : Assumptions: the invocation's work root is carried into every composed namespace,
    #   because the steps are the REAL handlers rather than reimplementations and those handlers
    #   resolve their source through the shared resolver, which materialises an object-store source
    #   into that directory. Omitting it made the composed step diverge from the same step invoked
    #   by hand at exactly the point the composition exists to keep identical.
    reading = argparse.Namespace(
        dataset=descriptor.layout_name,
        source=str(extract),
        encoding=arguments.encoding,
        work_root=arguments.work_root,
    )
    steps: list[tuple[str, Callable[[argparse.Namespace], int], argparse.Namespace]] = [
        ("stage the generation", _stage_dataset, staging),
    ]
    # WHY : Refactoring Rationale: the load and the three verification passes are composed ONLY for
    #   a dataset whose layout ships a committed extract, and they were composed unconditionally.
    #   Exactly one registered token fails that test and it made the nightly chain fail every night:
    #   `transactions` names `AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`, the single 350-byte record
    #   `app/jcl/TRANFILE.jcl` primes the TRANSACT cluster from, whose unpopulated category code is
    #   four NUL bytes -- so reading it as a whole transaction raises a zoned-decimal refusal and
    #   the refresh stopped at step 2 of 6 with status 8 for that branch of the scheduled Map.
    # WHY : Assumptions: loading it would be WRONG rather than merely difficult, which is what makes
    #   skipping the right resolution instead of finding a decodable initializer. Two authorities in
    #   this distribution already say so and the gate already honours both: `readers/transaction.py`
    #   declares `HAS_COMMITTED_SEED_DATASET = False`, and `verify/row_counts.py` names TRAN the
    #   unseeded layout and REQUIRES `ledger.transactions` to hold zero rows after the ETL, because
    #   posting is what fills it. A load of even one record would therefore fail verification pass 1
    #   of the whole migration.
    # WHY : Assumptions: the predicate is `readers.ships_committed_extract` rather than a comparison
    #   against TRAN, so this decision and the combined gate's own coverage decision cannot disagree
    #   -- `_gate_extracts` filters on exactly the same call. Naming the layout here would be a
    #   third copy of a rule two modules already publish.
    # WHY : Trade-offs: the STAGING step above still runs for such a dataset, and the generation it
    #   writes is the point. `app/jcl/TRANFILE.jcl` REPROs that initializer into the TRANSACT
    #   cluster in the baseline, so the bytes are a real dataset generation with real provenance;
    #   staging them keeps the `ledger/transactions` generation family populated and its retention
    #   sweep meaningful, which excluding the token from the orchestrator's Map would have silently
    #   stopped. What is skipped is only the part that cannot be correct.
    if ships_committed_extract(descriptor.layout_name):
        steps.append(("load the target table", _load_dataset, reading))
        # WHY : Assumptions: the three verification passes run in the fixed order 1, 2, 3 and each
        #   is mandatory, because none subsumes another: row counts catch a load that stopped early
        #   or ran twice, the checksum catches a corrupted field where the counts agree, and money
        #   parity catches a sign overpunch or a misplaced decimal point where both the counts and
        #   the field bytes agree. A refresh that reported success on fewer than three would report
        #   a load as verified that nothing had checked in the dimension that failed.
        steps.append(("verify row counts", _verify_row_counts, reading))
        steps.append(("verify the record checksum", _verify_checksum, reading))
        steps.append(("verify money parity", _verify_money_parity, reading))
    family = seed_datasets.backup_family(descriptor.token)
    if family is not None:
        # WHY : Assumptions: the backup generation is staged LAST, after the load and all three
        #   verification passes, and the order carries a meaning worth stating. The reference
        #   creates
        #   these three first generations in app/jcl/DEFGDGD.jcl, a job that runs before any load
        #   and
        #   knows nothing about one -- so ordering is not transcribed from it and is free to be
        #   chosen
        #   here. It is chosen so that a verified refresh is the precondition for a backup: a
        #   generation retained for five nights should hold bytes that were proven to load, not
        #   bytes
        #   that turned out to be unloadable a step later.
        # WHY : Assumptions: the SAME local extract is staged, so the backup is a byte-for-byte copy
        #   of the file the load read, which is what app/jcl/DEFGDGD.jcl's IEBGENER steps do with
        #   SYSIN DD DUMMY. Re-encoding the loaded rows was the alternative and is rejected on the
        #   same grounds recorded on the binding registry: a round trip through the relational
        #   schema
        #   would put a re-encoded record in a dataset the baseline creates by copying.
        # WHY : Assumptions: the backup family's own generation is RESERVED rather than shared with
        #   the primary family's, because the two are separate generation families with separate
        #   retention sweeps and a number reserved in one says nothing about the other. This is a
        #   change from forwarding a caller-pinned `--generation` to both, which is no longer an
        #   argument on either verb; a shared number was the readable outcome of a pin, and with no
        #   pin to share the correct number is each family's own next one.
        # WHY : Assumptions: `--retain` IS still passed through, and the asymmetry with the
        #   generation is deliberate: retention is a policy an operator may legitimately raise for
        #   a whole invocation, and raising it destroys nothing, while a generation number is a
        #   position in a sequence that only the reservation can know.
        steps.append(
            (
                f"stage the {family.dataset_segment} backup generation",
                _stage_dataset,
                argparse.Namespace(
                    dataset=descriptor.token,
                    business_date=arguments.business_date,
                    source=str(extract),
                    generation=None,
                    domain=family.domain,
                    dataset_segment=family.dataset_segment,
                    object_name=None,
                    retain=arguments.retain,
                ),
            )
        )

    allocator_target = target_for(_ALLOCATOR_LAYOUT)
    target = target_for(descriptor.layout_name)
    if (target.schema, target.table) == (allocator_target.schema, allocator_target.table):
        # WHY : Assumptions: the allocator is reconciled INSIDE the refresh of the dataset that
        #   feeds its table, and only that one, rather than as a state of its own. The hazard it
        #   closes is ordering: `ledger.transaction_id_seq` has its starting position derived by its
        #   own Flyway migration from the rows the table held when that migration ran, which on a
        #   cutover is none -- so once the extract is loaded the allocator points into an occupied
        #   range and the first interactive write fails on the primary key. Advancing it in the same
        #   step that loaded the rows means the window between the two is not a window an
        #   orchestration failure can leave open.
        # WHY : Assumptions: the step only ever ADVANCES the allocator, so a repeat refresh is safe.
        steps.append(
            ("reconcile the identifier allocator", _reconcile_sequences, argparse.Namespace())
        )
    return tuple(steps)


def _refresh_dataset(arguments: argparse.Namespace) -> int:
    """Refresh one seed dataset end to end: fetch, stage, load, verify and reconcile.

    Purpose
    -------
    Perform, as one orchestrated step, the whole per-dataset cutover the AAP's data-migration
    sequence describes -- exported flat file to object storage, decoded per field, bulk-loaded per
    schema, then verified three ways -- so the nightly chain's seed-refresh state does the work its
    own documentation claims rather than only the first quarter of it.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, the registry token, and ``business_date``; ``extract_prefix``, the
        provisioned source prefix; ``encoding``, the seed form; the optional ``source`` override
        naming a local extract; and the ``generation``, ``domain`` and ``retain`` staging
        overrides.

    Returns
    -------
    int
        :data:`EXIT_OK` when every step succeeded, :data:`EXIT_USAGE` when the dataset token is
        not registered, :data:`EXIT_FATAL` when the environment could not be resolved, and
        otherwise the status of the FIRST step that did not succeed -- so the caller's status
        names the same failure the step itself reported.

    Raises
    ------
    None
        Every documented failure becomes a return code, because a Step Functions state branches on
        one.
    """
    # WHY : Refactoring Rationale: this command exists because the nightly chain's seed-refresh
    #   state could not do what it documented. That state's Map branch invoked `stage-dataset`
    #   alone, which copies an extract's bytes into a generation prefix and nothing else, while the
    #   state was described as replacing the baseline's ten IDCAMS master-refresh jobs -- each of
    #   which DELETEs, DEFINEs and REPROs a master file into the form the online and batch programs
    #   read. Nothing in the chain decoded a record, loaded a table, verified a load or reconciled
    #   the identifier allocator, so a deployment that ran the chain end to end came up with empty
    #   masters and a green execution history.
    # WHY : Alternatives Considered: expressing the four extra steps as four more Step Functions
    #   states inside the Map branch. Rejected on two grounds. The AAP fixes the chain at ELEVEN
    #   states and describes this one as a Map whose branch is a single `runTask.sync`, so four
    #   more states per branch would be a different machine from the one the architecture
    #   publishes; and each state is a separate task with its own cold start and its own Aurora
    #   connection, so ten datasets would open fifty task lifecycles to do what ten can. The
    #   sequence is also inseparable in practice -- a load whose verification is a different state
    #   can be left committed and unverified by a failure between them -- and one task per dataset
    #   makes the branch's retry replay the whole refresh of that one dataset, which is exactly the
    #   unit that must be idempotent.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import AuroraLoadError

    try:
        descriptor = seed_datasets.seed_dataset(arguments.dataset)
    except SeedDatasetError as exc:
        _LOGGER.error("%s", exc)
        return EXIT_USAGE

    # WHY : Assumptions: the fetched extract lives in a scratch directory that is removed when the
    #   refresh ends, whatever the outcome. The container is ephemeral, so the alternative is not
    #   "keep it for inspection" but "leave it in the task's writable layer until the task exits" --
    #   and one of these extracts is the security file, which carries a plaintext credential in the
    #   baseline. Bounding its lifetime to the step that needs it is the narrower exposure.
    with tempfile.TemporaryDirectory(prefix="carddemo-refresh-") as scratch:
        if arguments.source is not None:
            # WHY : Trade-offs: a local override is kept for the operator who has the extract on
            #   disk already -- reproducing a nightly failure at a terminal, or refreshing one
            #   master from a corrected file. It deliberately skips the fetch and its published
            #   digest check, so the bytes are whatever the operator named; that is the point of an
            #   override, and the staging step still digests and geometry-checks what it uploads.
            extract = Path(arguments.source)
            _LOGGER.info("refreshing %s from the supplied extract at %s", descriptor.token, extract)
        else:
            try:
                fetched = _fetch_seed_extract(
                    descriptor, arguments.extract_prefix, Path(scratch) / descriptor.source_object
                )
            except ConfigurationError as exc:
                _LOGGER.error("the environment could not be resolved: %s", exc)
                return EXIT_FATAL
            except DatasetSourceError as exc:
                _LOGGER.error(
                    "the %s extract could not be fetched from the source prefix: %s",
                    descriptor.token,
                    exc,
                )
                return EXIT_FAILED
            extract = fetched.path

        try:
            steps = _refresh_steps(descriptor, arguments, extract)
        except AuroraLoadError as exc:
            _LOGGER.error("%s", exc)
            return EXIT_FAILED

        for ordinal, (label, handler, step_arguments) in enumerate(steps, start=1):
            _LOGGER.info(
                "refresh %s step %d of %d: %s", descriptor.token, ordinal, len(steps), label
            )
            status = handler(step_arguments)
            if status != EXIT_OK:
                # WHY : Assumptions: the sequence STOPS at the first step that did not succeed and
                #   reports that step's own status verbatim. Continuing would run a verification
                #   pass over a load that failed, whose difference report describes the failed load
                #   rather than the data -- noise on top of a diagnosis. Reporting the step's own
                #   status keeps a usage error a usage error and an environment failure an
                #   environment failure, which is what an orchestrator's retry policy branches on.
                _LOGGER.error(
                    "the refresh of %s stopped at step %d of %d (%s) with status %d",
                    descriptor.token,
                    ordinal,
                    len(steps),
                    label,
                    status,
                )
                return status

    # WHY : Assumptions: the closing line states WHICH refresh happened rather than one sentence
    #   for both shapes, because the two are genuinely different claims and an operator reading
    #   a nightly log has to be able to tell them apart. Saying "loaded and verified" for the
    #   one dataset that is deliberately neither would be the same overstatement the chain used
    #   to make in the other direction, when a state described as replacing ten IDCAMS master
    #   loads only copied bytes.
    if ships_committed_extract(descriptor.layout_name):
        _LOGGER.info(
            "refreshed %s: staged, loaded into the owning schema and verified by all three passes",
            descriptor.token,
        )
    else:
        _LOGGER.info(
            "refreshed %s: staged the generation only. Its layout ships no committed extract -- the"
            " transaction master is produced by posting, not seeded -- and the row-count baseline"
            " requires its table to hold zero rows after the ETL, so there is nothing to load and"
            " nothing to verify against",
            descriptor.token,
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
    # Assumptions: the business date is a PARAMETER and is never defaulted from the clock --
    #   there is deliberately no `date.today()` fallback in this function or anywhere below it.
    #   The baseline injects it the same way, as `PARM='2022071800'` on the job step
    #   (app/jcl/INTCALC.jcl line 22), because the date selects which day's data a run
    #   processes rather than recording when the run happened. A wall-clock read would put a
    #   rerun under a different dt= prefix from the attempt it is meant to reproduce, orphaning
    #   the first attempt's object and leaving a later step reading an empty location; and a
    #   rerun after midnight would silently process a different day.
    # Assumptions: this validator is attached only to the subcommands whose OUTPUT depends on
    #   the date, and is deliberately not a global option. Not every baseline job takes one --
    #   app/jcl/POSTTRAN.jcl line 23 is `EXEC PGM=CBTRN02C` with no PARM at all -- so requiring
    #   it everywhere would invent a parameter the baseline does not have and force a caller of,
    #   say, `load-dataset` to supply a date that reaches nothing it writes.
    # Refactoring Rationale: the shape is checked against `_BUSINESS_DATE_PATTERN` BEFORE
    #   `date.fromisoformat` is consulted, where this previously called `fromisoformat` alone.
    #   That was measured to accept three spellings this command must refuse, because since
    #   Python 3.11 `fromisoformat` parses the whole of ISO 8601 rather than the extended
    #   calendar form only: the baseline's own compact token `2022071800` returns 2022-07-18,
    #   the basic form `20220718` does too, and the week form `2022-W27-1` returns 2022-07-04.
    #   Each is the "two argument forms producing one key" ambiguity the paragraph above says
    #   was rejected, so the code contradicted its own stated contract -- and the week form is
    #   the damaging one, because it silently resolves to a DIFFERENT day from the one its
    #   digits read as, and the dt= segment is built from whatever date comes back.
    # Trade-offs: a caller who holds the baseline's `PARM='2022071800'` token must now
    #   reformat it rather than paste it, and that is the point: the refusal names the accepted
    #   form, whereas silent acceptance produced a correct-looking key from an unintended
    #   spelling and surfaced only later as a mis-sorted generation listing.
    if _BUSINESS_DATE_PATTERN.fullmatch(value) is None:
        raise argparse.ArgumentTypeError(
            f"{value!r} is not an ISO calendar date in the form YYYY-MM-DD"
        )
    # Assumptions: the pattern fixes the SHAPE and `fromisoformat` still decides whether the
    #   date EXISTS, so a well-formed but impossible day such as 2022-02-30 is refused here
    #   rather than reaching the prefix builder.
    try:
        return date.fromisoformat(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(
            f"{value!r} is not an ISO calendar date in the form YYYY-MM-DD"
        ) from exc


def _with_registered_extract_defaults(
    handler: Callable[[argparse.Namespace], int],
) -> Callable[[argparse.Namespace], int]:
    """Let one dataset selector name either a seed-dataset token or a copybook layout.

    Purpose
    -------
    Give the four record commands the same one-argument invocation the staging command already
    accepts, by resolving a seed-dataset token to the layout, the extract and the seed form it
    implies, and holding a fetched extract for as long as the handler runs.

    Parameters
    ----------
    handler : Callable[[argparse.Namespace], int]
        The subcommand handler to wrap. It reads ``dataset``, ``source`` and ``encoding`` and is
        unaware of the two vocabularies its selector accepts.

    Returns
    -------
    Callable[[argparse.Namespace], int]
        A handler that resolves the selector first and then calls the wrapped one, returning
        :data:`EXIT_USAGE` when a layout name is given without the two options it needs,
        :data:`EXIT_FATAL` when the deployment names no extract location, and
        :data:`EXIT_FAILED` when the extract cannot be obtained.

    Raises
    ------
    None
        Every documented failure becomes a return code, exactly as in the handlers it wraps.
    """

    # WHY : ⚠️ Refactoring Rationale: this wrapper is what makes the LOAD reachable from the
    #   orchestrator. The nightly chain's dataset step staged the ten extracts into generation
    #   prefixes and then stopped: `load-dataset` -- the migrated form of the baseline's
    #   `IDCAMS REPRO` into each master -- was invoked by nothing, so a cutover staged bytes into
    #   object storage and left every target table empty. It could not simply be added to the
    #   state machine either, because the orchestrator holds ONE token per dataset while this
    #   command required a layout name, a path and a seed form, none of which the state machine
    #   has any authority to compose.
    # WHY : Assumptions: the two vocabularies are disjoint -- lower-case plural tokens against
    #   short upper-case layout names -- which `seed_datasets.is_seed_dataset_token` records and
    #   which is measured rather than arranged. Adding a second option, `--seed-dataset` beside
    #   `--dataset`, was the alternative and was rejected: it would give an operator two spellings
    #   for one question and a fresh way to state both and disagree.
    # WHY : Trade-offs: the namespace is MUTATED rather than copied, so the handler sees a layout
    #   name where the operator wrote a token. The cost is that a handler's own log line reports
    #   the layout; the resolution line below reports the pairing once, so both names appear in
    #   the step's output. Threading a second, resolved object through four handlers was the
    #   alternative and was rejected as four signature changes for one derivation.
    @functools.wraps(handler)
    def run(arguments: argparse.Namespace) -> int:
        """Resolve the selector, then run the wrapped handler with the derived arguments.

        Parameters
        ----------
        arguments : argparse.Namespace
            The parsed arguments, carrying ``dataset`` and the optional ``source`` and
            ``encoding``. Its ``dataset``, ``source`` and ``encoding`` members are replaced in
            place when the selector names a seed-dataset token.

        Returns
        -------
        int
            The wrapped handler's status, or the usage, fatal or failed status of a resolution
            that could not be completed.

        Raises
        ------
        None
            Every resolution failure becomes a return code, matching the handlers this wraps.
        """
        # WHY : Refactoring Rationale: the branch asks whether the seed registry KNOWS this
        #   identifier, where it used to ask whether the identifier is a seed TOKEN. The parser now
        #   normalises either accepted spelling to the layout name, so a token never reaches here
        #   as a token -- the token test would send every orchestrated invocation down the branch
        #   that demands `--source` and `--encoding`, which is the exact refusal this resolver
        #   exists to remove.
        # WHY : Assumptions: the widened predicate also serves the layout-name spelling of a
        #   registered dataset, which the token test refused. That is correct rather than merely
        #   convenient: a registered dataset HAS a source object, so there is a location to
        #   resolve. The refusal survives for the layouts the registry does not carry -- `REJECT`
        #   and `INTTRAN` are produced by the pipeline and shipped by nothing -- which is the case
        #   the original refusal was written for.
        if not seed_datasets.is_seed_dataset_identifier(arguments.dataset):
            # Assumptions: a layout name still REQUIRES both options, and the refusal names them
            #   rather than defaulting either. A layout is not bound to one extract -- `REJECT`
            #   and `INTTRAN` are produced by the pipeline and shipped by nothing -- so there is
            #   no location this branch could infer, and inferring the seed form alone would leave
            #   a half-derived invocation whose missing half failed later.
            missing = [
                option
                for option, value in (
                    ("--source", arguments.source),
                    ("--encoding", arguments.encoding),
                )
                if value is None
            ]
            if missing:
                _LOGGER.error(
                    "%s names a record layout rather than a seed dataset, so %s must be given; "
                    "the seed datasets, which derive both, are %s",
                    arguments.dataset,
                    " and ".join(missing),
                    ", ".join(seed_datasets.seed_dataset_tokens()),
                )
                return EXIT_USAGE
            return handler(arguments)

        descriptor = seed_datasets.seed_dataset(arguments.dataset)
        try:
            with contextlib.ExitStack() as stack:
                if arguments.source is None:
                    arguments.source = str(stack.enter_context(_registered_extract(descriptor)))
                if arguments.encoding is None:
                    arguments.encoding = seed_datasets.SEED_SOURCE_ENCODING
                _LOGGER.info(
                    "resolved seed dataset %s to layout %s, source %s, encoding %s",
                    descriptor.token,
                    descriptor.layout_name,
                    arguments.source,
                    arguments.encoding,
                )
                arguments.dataset = descriptor.layout_name
                return handler(arguments)
        except _StagingEnvironmentError as exc:
            _LOGGER.error("%s", exc)
            return EXIT_FATAL
        except DatasetSourceError as exc:
            _LOGGER.error("the extract for %r could not be obtained: %s", descriptor.token, exc)
            return EXIT_FAILED

    return run


def _reader_and_records(arguments: argparse.Namespace) -> tuple[RecordReader, Any]:
    """Resolve the reader and records a per-dataset command asked for on its command line.

    Purpose
    -------
    Adapt one namespace to :func:`_records_for`, so the four per-dataset commands read their three
    selectors in one place and the combined gate -- which has no per-dataset namespace to read --
    reaches the same resolution through the explicit form.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source``, ``encoding`` and the ``work_root`` :func:`main` opened.

    Returns
    -------
    tuple[RecordReader, Any]
        Exactly what :func:`_records_for` returns: a reader carried for its field metadata, and an
        iterator of records decoded by the module that owns that record.

    Raises
    ------
    LayoutError
        Propagated from :func:`_records_for`.
    DatasetSourceError
        Propagated from :func:`_records_for`.
    StagingServiceError
        Propagated from :func:`_records_for`.
    """
    # WHY : Refactoring Rationale: this became a two-line adapter over an explicit-parameter core,
    #   where it used to open `Path(arguments.source)` itself and build a generic reader from the
    #   layout. Three things move with the change, and each of them was a real defect rather than a
    #   tidying. A `--source` naming an object-store key was opened as a FILESYSTEM path, so the one
    #   place a staged extract exists in the deployment -- the bucket the staging step wrote it to
    #   -- could not be read at all, and the failure surfaced as a missing file named `s3:/...`. The
    #   records now come from the OWNING reader module rather than a factory-built one, so each
    #   record's own policy is applied: the card record's verification value stays wrapped so it
    #   cannot be logged by accident, the user record refuses a type outside the `'A'`/`'U'` domain
    #   its target's CHECK constraint enforces, and the two transaction records validate their
    #   processing stamps. And the combined gate, which resolves eleven datasets in one invocation
    #   and has no namespace to hand this function, reaches the same resolution through the core
    #   rather than synthesising eleven `Namespace` objects whose member names had to keep matching
    #   this function's reads.
    # WHY : Assumptions: the absent-source fallback resolves the newest STAGED generation, which is
    #   the baseline's own `(0)` reference, and it is a defence rather than the ordinary path. Every
    #   command-line invocation of these four verbs reaches this function with `source` already
    #   filled: the selector resolver derives the registry's own source object for a dataset the
    #   seed registry carries, and refuses outright for a layout it does not. The fallback covers a
    #   caller that composes a namespace without one, and it resolves to the object this package
    #   itself wrote and recorded a digest for -- so what it reads is provably what was staged.
    return _records_for(
        arguments.dataset,
        _staged_source(arguments.dataset) if arguments.source is None else str(arguments.source),
        arguments.encoding,
        Path(arguments.work_root),
    )


#: The identity column every sequential feed row carries, in insertion order.
#
# Assumptions: the name is a literal here rather than derived, because it is not a copybook field
#   at all -- `V1__ledger.sql` declares `ingest_seq` as the primary key of
#   `ledger.daily_transactions` precisely because no extract supplies one. `loaders/aurora.py`
#   records the same column for the same reason; the two agree by citing one migration rather than
#   by one importing the other's constant, and `test_verify.py` holds the pair together.
_INGEST_SEQUENCE_COLUMN: Final[str] = "ingest_seq"


def _read_back(connection: Any, target: Any) -> Iterator[dict[str, Any]]:
    """Stream the loaded rows back as decoded-shaped records.

    Purpose
    -------
    Rebuild the field-keyed shape a reader yields, out of the columns the target declares, so the
    checksum verifier can digest both sides identically -- and do it in bounded batches, so peak
    memory is a function of the batch size rather than of the table.

    Parameters
    ----------
    connection : Any
        An open database connection. It must stay open for as long as the returned iterator is
        consumed, because rows are fetched as they are yielded rather than up front.
    target : Any
        The table target whose column mapping is inverted.

    Yields
    ------
    dict[str, Any]
        One mapping per row, keyed by copybook field name, in the order the statement asks for.

    Raises
    ------
    ChecksumVerificationError
        If the keyless sequential feed answers with an insertion-order column that does not ascend
        strictly, which would make positional pairing unsound.
    """
    # WHY : Refactoring Rationale: the rows are STREAMED through `_stream_rows`, where this function
    #   used to `fetchall()` into a list. Two things were wrong with that and they compound: peak
    #   memory scaled with the table rather than with a batch, and a caller then held every row
    #   while ALSO holding the source side, so verifying a production-sized extract needed both
    #   sides resident at once. The seed extracts are small enough that the list form worked, which
    #   is exactly why it is worth fixing before a real cutover rather than after one.
    # WHY : Assumptions: what the streaming changed is HOW the rows arrive, not WHICH rows or in
    #   what order -- the statement below is composed exactly as it was, including the deliberate
    #   absence of a server-side ordering on a keyed target. A streamed read that also reintroduced
    #   an `ORDER BY` over every comparable column would have exchanged one defect for a worse one;
    #   see the ordering rationale recorded on the statement itself.
    # Assumptions: the read-back is restricted to the target's COMPARABLE fields, which
    #   excludes any column holding an envelope. An envelope's initialisation vector is drawn per
    #   value, so the same identifier enciphered twice differs -- selecting such a column would
    #   make the digest comparison report a difference on every run, against a load that was
    #   correct. The exclusion is the target's own decision, so this function states none of it.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.verify.checksum import ChecksumVerificationError

    fields = target.comparable_fields()
    columns = tuple(target.columns[name] for name in fields)
    names = ", ".join(quote_identifier(column) for column in columns)
    # WHY : ⚠️ Refactoring Rationale: a keyed target carries NO server-side ordering, where it once
    #   ordered by every comparable column in declaration order. That ordering was not the source
    #   side's, and nothing made it so: the source arrives in physical extract order, so any dataset
    #   whose extract is not already sorted by its full column list was compared
    #   record-against-wrong-row from the first row on and reported as wholly corrupt -- a
    #   sequential daily-transaction extract is exactly that shape. Ordering by the KEY was the
    #   intermediate fix, and it is superseded by the paragraph below: both sides are sorted in this
    #   process on the rendered key, so the server has no ordering left to contribute and asking it
    #   for one would put the column's collation back into the comparison.
    # WHY : ⚠️ Assumptions: a KEYED target is read back UNORDERED, and the absence of an ORDER BY
    #   is deliberate rather than an omission. `_paired_records` sorts BOTH sides in this process on
    #   the rendered key, so a server-side ordering would be redundant work -- and worse than
    #   redundant, because a server-side ORDER BY on a character key resolves through the column's
    #   collation while the in-process sort compares the rendered bytes. Asking the server to order
    #   would reintroduce the engine's collation as a participant in a comparison whose whole point
    #   is to be independent of it.
    # WHY : ⚠️ Assumptions: the one target with NO key is the sequential feed, and it is read back
    #   ordered by the identity column that records insertion order -- which IS the extract order
    #   the source side is already in, so positional pairing is sound for it and only for it.
    #   Falling back to the DECLARED COLUMN ORDER here, which an earlier form of this code did,
    #   would have put the feed in an order the extract never had.
    keyed = bool(target.key_columns)
    if keyed:
        statement = f"SELECT {names} FROM {target.qualified_name}"  # noqa: S608
    else:
        statement = (
            f"SELECT {quote_identifier(_INGEST_SEQUENCE_COLUMN)}, {names}"  # noqa: S608
            f" FROM {target.qualified_name}"
            f" ORDER BY {quote_identifier(_INGEST_SEQUENCE_COLUMN)}"
        )
    # WHY : Assumptions: the cursor name carries the TABLE, so two read-backs live in one session
    #   cannot collide on it. A server-side cursor is what makes the batching real -- the driver
    #   buffers a whole result set on `execute` for an unnamed one, so `fetchmany` over that bounds
    #   nothing at all and the read would look streamed while holding every row.
    rows = _stream_rows(connection, statement, f"carddemo_verify_{target.table}")
    if keyed:
        for row in rows:
            yield dict(zip(fields, row, strict=True))
        return
    # WHY : Assumptions: the ingest sequence is VERIFIED to ascend strictly rather than trusted to,
    #   because it is the whole of the sequential feed's correspondence claim: the nth row read back
    #   is the nth record of the extract only if the ordering column really orders the insertions. A
    #   gap is permitted -- an identity column keeps its position after a rolled-back attempt --
    #   while a repeat or a descent is not, since either means two rows share a position.
    # WHY : Assumptions: the check is made ROW BY ROW as the stream is consumed rather than over a
    #   collected list of ordinals, which is what keeps the streaming property while keeping the
    #   guarantee: only the previous ordinal has to be remembered, so nothing accumulates.
    previous: object = None
    for row in rows:
        current = row[0]
        if previous is not None and current <= previous:
            raise ChecksumVerificationError(
                f"{target.qualified_name} returned {_INGEST_SEQUENCE_COLUMN} values that do not"
                " ascend strictly, so the nth row read back cannot be paired with the nth record"
                " of the extract"
            )
        previous = current
        yield dict(zip(fields, row[1:], strict=True))


def _verification_identity(target: Any) -> tuple[str, ...]:
    """Name the record fields a verification pass pairs the two sides of a load by.

    Purpose
    -------
    Translate the target's primary-key COLUMNS into the record FIELD names both sides of the
    comparison are keyed by, so the pairing is by the dataset's own identity rather than by a
    position in a stream.

    Parameters
    ----------
    target : Any
        The table target whose key columns are inverted through its column mapping.

    Returns
    -------
    tuple[str, ...]
        The key field names in the target's declared key order, or an empty tuple for a target that
        declares no key, which pairs by position.

    Raises
    ------
    None
        A key column that does not invert is omitted rather than raising; the target validates its
        own key window on construction, so this cannot arise from a registered dataset.
    """
    # Assumptions: the mapping is inverted here rather than held on the target, because the target
    #   declares the direction it needs for loading -- field to column -- and one authority
    #   inverted at the point of use cannot drift from itself the way two stored maps can.
    fields_of_column = {column: field for field, column in target.columns.items()}
    return tuple(
        fields_of_column[column] for column in target.key_columns if column in fields_of_column
    )


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
    # Assumptions: a money field is exactly a SIGNED display field. An unsigned display
    #   field is an identifier or a count -- a card number, a credit score -- and totalling one
    #   would produce a number with no meaning that a source-versus-target comparison would then
    #   solemnly confirm.
    return tuple(field.name for field in reader.loaded_fields if field.kind is layouts.Kind.ZONED)


def _connect_for(target: TableTarget) -> Any:
    """Open a connection for one load target, as the role its schema's tables were granted to.

    Purpose
    -------
    Resolve a target's schema to its credential and to its expected login role through two
    independent declarations, and refuse to open the connection unless the two agree -- so that
    every command touching a table authenticates as the least-privileged role that holds a grant
    on it rather than as whichever role the environment happened to supply.

    Parameters
    ----------
    target : TableTarget
        The load target whose ``schema`` selects both the credential and the expected role.

    Returns
    -------
    Any
        An open connection, verified to authenticate as the schema's own login role. The caller
        owns closing it. The driver's connection type is not imported here, exactly as
        :func:`carddemo_migration.loaders.aurora.connect` does not import it: the driver is
        resolved inside that function so that this module stays importable without it.

    Raises
    ------
    ConfigurationError
        If the schema is not one of the eight, if the resolved credential is incomplete, or if
        its stored user is not the schema's login role.
    """
    # Assumptions: the owning role is passed as an EXPECTATION rather than trusted to
    #   follow from the schema, so the two independent resolutions have to agree before a
    #   connection is opened. `resolve_aurora_settings` reads a per-schema secret whose stored
    #   user is what the connection actually authenticates as, and `role_for_schema` states what
    #   `sql/V0__schemas_and_roles.sql` granted the table to; a secret rotated to another role, or
    #   a parameter path pointing at the wrong schema's secret, would otherwise succeed as
    #   whichever role it found -- most damagingly as a superuser, which can write every table and
    #   therefore proves nothing about the least-privilege boundary the ETL claims to run inside.
    # Refactoring Rationale: all four database commands resolve their connection here rather
    #   than each calling `connect` directly. The expectation was added to one of them first, and
    #   the asymmetry was itself the defect: the load -- the only command that WRITES -- was the
    #   one still opening an unverified connection.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import connect

    return connect(
        resolve_aurora_settings(target.schema),
        expected_role=role_for_schema(target.schema),
    )


def _load_dataset(arguments: argparse.Namespace) -> int:
    """Bulk-load one decoded dataset into its target table.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source`` and ``encoding``.

    Returns
    -------
    int
        :data:`EXIT_OK` when the load committed, and also when the direct-COPY precondition
        DECLINED it because the target was already populated -- a declined load is the restart
        case succeeding, not failing. :data:`EXIT_FAILED` when it was rolled back.

    Raises
    ------
    None
    """
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import AuroraLoadError, load_records, target_for
    from carddemo_migration.loaders.protected_columns import ProtectedColumnError

    try:
        _, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        # Assumptions: the context is resolved BEFORE the connection is opened, so an
        #   environment missing a key parameter or a subject document fails without having taken a
        #   connection it would then have to close on the error path. It is also the cheaper
        #   failure: a missing parameter is an operator action, and learning it before the database
        #   is touched keeps the two diagnoses apart.
        context = _load_context_for(target)
        connection = _connect_for(target)
    except ConfigurationError as exc:
        # WHY : Assumptions: an unresolvable environment is the FATAL tier and it is caught AHEAD
        #   of the operational tuple below, because the two tiers are read by different actors for
        #   different purposes. The batch state RETRIES a step that reports 8, and no number of
        #   retries publishes a parameter or a secret that was never published -- so reporting the
        #   failed tier here spent the whole retry budget re-reaching a diagnosis the first attempt
        #   already had, and delayed the one action that resolves it. Ordering matters as well as
        #   membership: `ConfigurationError` would otherwise be absorbed by nothing here and escape
        #   as a traceback, which is neither tier.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    except _step_errors() as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    try:
        outcome = load_records(connection, target, records, context)
    except (AuroraLoadError, ProtectedColumnError) as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    # Assumptions: BOTH counts are reported rather than one. On the stage-and-merge path a
    #   load that staged every row and inserted none is a success -- the seed migration had already
    #   written them -- and an operator reading a line that said only "loaded 7 row(s)" would
    #   believe seven rows had been added. The outcome renders the skipped count only when it is
    #   non-zero, so the direct path's line does not carry a number that is always zero.
    # Refactoring Rationale: there is no DECLINED branch, and there was one. Every target now
    #   stages and merges, so a re-run of a completed load reports staged rows with none inserted
    #   rather than refusing to run -- the same information, reached without a row-count
    #   precondition that the identity-keyed daily feed could not be made safe by. A restart is
    #   still safe and still reports success, which is what the withdrawn branch existed for.
    _LOGGER.info(
        "loaded record=%s staged=%d inserted=%d into %s.%s",
        arguments.dataset,
        outcome.staged,
        outcome.inserted,
        target.schema,
        target.table,
    )
    print(f"loaded {arguments.dataset} into {target.schema}.{target.table}: {outcome.describe()}")
    return EXIT_OK


def _reconcile_sequences(arguments: argparse.Namespace) -> int:
    """Advance the transaction-identifier allocator past every loaded identifier.

    Purpose
    -------
    Give the cutover the one step that has to happen between the last load into
    ``ledger.transactions`` and the moment writes are enabled. The allocator's starting position
    is derived by its own Flyway migration from the rows the table held AT MIGRATION TIME, which
    on a cutover is none -- so after the extract is loaded the allocator points into a range the
    table now occupies, and the first interactive add or bill payment collides on the primary key.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries nothing. The step takes no options for the same reason ``apply-credentials`` takes
        none: there is exactly one allocator and one correct value for it, and an option would only
        create a way to set a wrong one.

    Returns
    -------
    int
        :data:`EXIT_OK` when the allocator is past every stored identifier -- whether this run
        advanced it or found it already there -- and :data:`EXIT_FAILED` when it could not be
        reconciled, in which case writes must not be enabled.

    Raises
    ------
    None
    """
    # Assumptions: the MIGRATION credential is resolved, not the runtime one.
    #   `sql/V0__schemas_and_roles.sql` grants the service role `USAGE, SELECT` on the schema's
    #   sequences, which is `nextval` and not `setval`; `setval` needs UPDATE, which only the owner
    #   holds. Resolving the runtime credential here would fail with a permission error at the one
    #   step a cutover cannot skip.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import (
        TRANSACTION_ID_SEQUENCE,
        AuroraLoadError,
        connect,
        reconcile_transaction_id_sequence,
    )

    del arguments
    try:
        connection = connect(resolve_migration_settings("ledger"))
    except ConfigurationError as exc:
        # WHY : Assumptions: the FATAL tier, caught ahead of the operational tuple below for the
        #   reason recorded on the load command: 8 is retried by the batch state and a retry cannot
        #   publish a parameter that was never published.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    except _step_errors() as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    try:
        reconciliation = reconcile_transaction_id_sequence(connection)
    except AuroraLoadError as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    # Trade-offs: a run that changed nothing reports success rather than a distinct status.
    #   The step is defined by its POSTCONDITION -- the allocator is past every stored identifier
    #   -- and that holds equally whether this run moved it or found it already there, which is
    #   what makes the step safe to leave in a cutover script that may be re-run. The rendered line
    #   still says which of the two happened.
    if reconciliation.would_have_collided:
        # Assumptions: logged at WARNING, because this is the defect having been caught. An
        #   allocator poised to reissue a stored identifier would have failed every interactive
        #   write until it climbed past the loaded range, and an operator reading a cutover log
        #   afterwards needs to see that the step was not merely ceremonial.
        _LOGGER.warning(
            "reconciled sequence=%s stored_maximum=%d next_before=%d next_after=%d",
            reconciliation.sequence,
            reconciliation.stored_maximum,
            reconciliation.next_value_before,
            reconciliation.next_value_after,
        )
    else:
        _LOGGER.info(
            "sequence=%s already past stored_maximum=%d at next=%d",
            reconciliation.sequence,
            reconciliation.stored_maximum,
            reconciliation.next_value_after,
        )
    print(f"reconciled {TRANSACTION_ID_SEQUENCE}: {reconciliation.describe()}")
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
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import target_for
    from carddemo_migration.verify.row_counts import compare_counts

    try:
        _, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        connection = _connect_for(target)
    except ConfigurationError as exc:
        # WHY : Assumptions: the FATAL tier, caught ahead of the operational tuple below for the
        #   reason recorded on the load command: 8 is retried by the batch state and a retry cannot
        #   publish a parameter that was never published.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    except _step_errors() as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    # Assumptions: the decode guard is repeated around the CONSUMPTION and not only around the setup
    #   above, because ``_reader_and_records`` returns a lazy iterator: the width contract and
    #   the per-field decode are checked on the first record pulled, which happens inside
    #   ``compare_counts``, after the setup block has already returned successfully.
    try:
        outcome = compare_counts(
            connection, arguments.dataset, target.schema, target.table, records
        )
    # WHY : Assumptions: the refused set spans BOTH tiers -- the operational errors a step can meet
    #   and the refusals a verification pass raises when the value the driver returned is not the
    #   value the query promised. A count that is not an exact whole number, or a total that is not
    #   exact at the money scale, means the query is not the query; reporting that as a traceback
    #   loses the classified status the batch state branches on, and loses it for the one failure
    #   mode that says the verification itself cannot be trusted.
    # WHY : Refactoring Rationale: the operational half is `_step_errors()` where it was the
    #   narrower `_DECODE_ERRORS` pair. The pair covered a layout refusal and a record-length
    #   refusal and NOT `OSError`, so an extract that was deleted, denied or truncated part-way
    #   through the read -- which is where a lazy reader raises, inside this block rather than
    #   the setup above -- escaped
    #   as a traceback from a command whose contract is a classified exit status.
    except (*_step_errors(), *_verification_errors()) as exc:
        _LOGGER.error("%s", _reported(exc))
        return EXIT_FAILED
    finally:
        connection.close()
    print(outcome.describe())
    # Trade-offs: a difference exits FAILED rather than raising. README.md section 5.6 assigns
    #   8 to "the step did not complete", which is what a count mismatch is; and a verification
    #   command that raised would lose the report line an operator needs in order to see WHICH
    #   side was short. The cost is that no stack is printed, which this path has no use for --
    #   the counts disagreeing is data, not a fault in the code that compared them.
    return EXIT_OK if outcome.matched else EXIT_FAILED


def _verify_row_count_report(arguments: argparse.Namespace) -> int:
    """Run the whole-migration row-count report on a read-only session and print every line.

    Purpose
    -------
    Execute ``data-migration/sql/verify/row_counts.sql`` as the least-privilege reporting role and
    report the six-column verdict it publishes, so one invocation answers "did every dataset land,
    in the right quantity" for the whole load rather than for one dataset at a time.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``sql_root``, the directory holding the ``sql`` tree, or ``None`` to resolve it
        from the installed package's own location.

    Returns
    -------
    int
        :data:`EXIT_OK` when every line verified, otherwise :data:`EXIT_FAILED`.

    Raises
    ------
    None
        Every documented failure is reported as a return code, because the batch state that
        invokes this branches on one.
    """
    # Refactoring Rationale: this command exists because the reporting-role verification
    #   path was DELIVERED AND UNREACHED. verify/row_counts.py published
    #   open_reporting_connection, reporting_settings and verify_row_counts, and nothing in the
    #   distribution called any of them -- while verify-row-counts beside this command opened a
    #   SCHEMA-OWNER connection for its per-dataset comparison. So the whole-migration report,
    #   the one designed to run with no write authority over what it certifies, could only be run
    #   by hand in a Python session. Wiring it as a command is what makes the least-privilege path
    #   the one an operator and the batch chain actually take.
    #
    # Alternatives Considered: converting the existing verify-row-counts command to this
    #   path instead of adding a second one. Rejected because the two answer different questions
    #   and need different authority: that command counts records in a LOCAL EXTRACT and compares
    #   them against one table, so it must read the extract and must know which dataset is meant;
    #   this one reads a server-side aggregate over every table and needs no extract at all.
    #   Collapsing them would have forced this report to accept --dataset, --source and --encoding
    #   it does not use.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.verify.row_counts import (
        RowCountVerificationError,
        open_reporting_connection,
        reporting_role,
        row_count_query_path,
        verify_row_counts,
    )

    try:
        connection = open_reporting_connection()
    except ConfigurationError as exc:
        # WHY : Assumptions: the FATAL tier, caught ahead of the operational tuple below for the
        #   reason recorded on the load command: 8 is retried by the batch state and a retry cannot
        #   publish a parameter that was never published.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    # WHY : Assumptions: the refused set spans BOTH tiers -- the operational errors a step can
    #   meet and the verification errors a pass raises when a result set does not hold what a
    #   comparison needs. A pass that answers with an inexact count or a value no column of this
    #   migration holds has established nothing, and this command promises a classified exit
    #   status rather than a traceback; leaving the verification tier out let exactly those
    #   refusals escape as an unhandled type an operator cannot act on.
    except (*_step_errors(), *_verification_errors(), RowCountVerificationError) as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    try:
        # Assumptions: the option names the DISTRIBUTION ROOT and is resolved to the query
        #   file here, rather than naming the file itself. That is the parameter
        #   `row_count_query_path` publishes for this purpose, and its own rationale records why
        #   one is needed: the sql tree ships BESIDE the package rather than inside it, so the
        #   package-relative default is correct in a source checkout and in an editable install and
        #   cannot work from a plain wheel. The container is the wheel case -- its image copies
        #   sql/ to the working directory -- so the documented invocation there passes --sql-root .
        #   Naming the file instead would let two runs execute two different files under one
        #   option, where naming the root keeps the layout the contract.
        query_path = (
            None if arguments.sql_root is None else row_count_query_path(arguments.sql_root)
        )
        report = verify_row_counts(connection, query_path=query_path)
    # WHY : Assumptions: the refused set spans BOTH tiers -- the operational errors a step can
    #   meet and the verification errors a pass raises when a result set does not hold what a
    #   comparison needs. A pass that answers with an inexact count or a value no column of this
    #   migration holds has established nothing, and this command promises a classified exit
    #   status rather than a traceback; leaving the verification tier out let exactly those
    #   refusals escape as an unhandled type an operator cannot act on.
    except (*_step_errors(), *_verification_errors(), RowCountVerificationError) as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    # Assumptions: the role that certified the report is logged and is deliberately NOT
    #   printed. An operator reading the log needs to know which authority the verdict was reached
    #   under, because that is the property this pass rests on; the printed text stays byte-stable
    #   between runs so two runs over unchanged data diff to nothing, and a role name is the kind
    #   of line that would later acquire a host or a database beside it.
    _LOGGER.info("row count report certified by role=%s", reporting_role())
    print(report.render())
    # Trade-offs: a mismatch exits FAILED rather than raising, on the same reasoning as the
    #   per-dataset command above: the rendered report names every short table, and a traceback
    #   would replace the one artifact an operator acts on with the place the code noticed.
    return EXIT_OK if report.verified else EXIT_FAILED


def _verify_checksum(arguments: argparse.Namespace) -> int:
    """Digest a dataset and compare it against the same digest taken over the loaded rows.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``dataset``, ``source`` and ``encoding``.

    Returns
    -------
    int
        :data:`EXIT_OK` when the two digests agree, otherwise :data:`EXIT_FAILED` -- which covers
        a located difference, an unreadable extract, a refused read-back and a value neither side
        can render under its column's form.

    Raises
    ------
    None
        Every documented failure becomes a return code, because this command's contract is a
        binary exit status and a traceback would replace the report an operator acts on with the
        place the code noticed.
    """
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import prepare_record, target_for
    from carddemo_migration.verify.checksum import (
        SealableValueTally,
        audit_sealed_columns,
        canonicalisation_of,
        compare_record_digests,
        deterministic_field_names,
        refined_canonicalisation,
    )

    try:
        reader, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        # Assumptions: the context is resolved here as well as in the load handler, because
        #   the source side is now projected through the target's own declarations and a
        #   SUBJECT_FOR_USER_ID projection needs the published document to produce the value the
        #   column holds.
        # WHY : Assumptions: the sealing collaborators ARE resolved for the two records that
        #   declare a sealing projection -- `CARD` and `CUSTOMER` -- even though their sealed
        #   columns are excluded from the digest. `prepare_record` projects EVERY mapped column
        #   rather than only the comparable ones, so preparing either of those records without a
        #   cipher is refused outright. Verifying a card or a customer checksum therefore needs the
        #   same key-management grant that loading it needs, which is a fact about this command an
        #   operator has to know before a cutover rather than discover during one.
        # WHY : Refactoring Rationale: this comment previously claimed the call "reaches the
        #   key-management service for no dataset this command can compare". That was measurably
        #   false for those two records the moment the pass could compare them at all, and it
        #   pointed an operator at the wrong prerequisite. It is corrected rather than deleted,
        #   because the resolution being NARROW -- `TRANTYPE` still needs no grant -- is the
        #   property worth keeping recorded.
        context = _load_context_for(target)
        connection = _connect_for(target)
    except ConfigurationError as exc:
        # WHY : Assumptions: the FATAL tier, caught ahead of the operational tuple below for the
        #   reason recorded on the load command: 8 is retried by the batch state and a retry cannot
        #   publish a parameter that was never published.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    except _step_errors() as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    # Refactoring Rationale: the source side is digested through `prepare_record`, over the
    #   target's comparable fields, where it used to digest the RAW decoded record over every
    #   mapped field. Both halves of that were wrong once the loader began projecting values. The
    #   raw record carries a description at its fixed width and the stored column holds it
    #   trimmed, so a digest of the raw form differs from the stored form for a load that was
    #   correct; and the full field set includes the envelope columns, whose bytes differ on every
    #   write by design. Digesting what was STORED, over the fields that can be stored
    #   deterministically, is the only construction that compares the two sides of the same load.
    # Refactoring Rationale: the comparable field set is now passed through
    #   `deterministic_field_names`, and the two digests through `compare_record_digests`. Both
    #   halves close a claim this package published and did not keep. `README.md` section 10.1
    #   states that the pass excludes the wall-clock processing stamp by reading the layout's own
    #   `normalize_ts` mark -- nothing read that mark, so a comparison of the two transaction
    #   records differed on every run and reported a correct load as a defect. And section 5.2
    #   states that the pass writes the identifier of every record whose checksum differs -- it
    #   wrote two whole-dataset digests, so a difference was announced with nowhere to look.
    fields = deterministic_field_names(reader.layout, target.comparable_fields())
    try:
        # Alternatives Considered: the loaded rows are read back and digested through the SAME field
        #   order and the same canonical rendering, so the comparison is between two digests of the
        #   same construction. Comparing a source digest against a value recorded in a file would
        #   only prove the source had not changed, which is not what a load needs verifying.
        # Trade-offs: the read-back is collected BEFORE the lockstep walk begins, and it is
        #   collected rather than streamed. The walk consumes both sides together, so the rows have
        #   to exist before it starts; and they are read inside this block because it is the one
        #   that closes the connection, which a lazy cursor consumed after the close could not use.
        # WHY : Assumptions: the streamed read-back is COLLECTED here, and the collection is this
        #   caller's decision rather than the reader's. Two things in this block need the rows more
        #   than once: the lockstep walk consumes both sides together, and
        #   `refined_canonicalisation` below narrows the per-field rules BY the rows themselves. A
        #   generator consumed by the first would leave the second nothing to read. It is collected
        #   inside this block because it is the block that closes the connection, which a lazy
        #   cursor consumed after the close could not use.
        loaded = list(_read_back(connection, target))
        # Assumptions: the raw records are TAPPED on their way into the projection, so the sealed
        #   columns' expected counts come out of the very traversal the digest already makes. The
        #   tap reads the SOURCE value, before `prepare_record` turns it into an envelope, which is
        #   what makes the expectation an independent measurement rather than the writer agreeing
        #   with itself about what it chose to seal.
        tally = SealableValueTally(target.sealed_fields())
        # WHY : ⚠️ Refactoring Rationale: the comparison is now given the per-field
        #   canonicalisation rules and the key to pair by, and it had neither. Without the rules it
        #   rendered the source side's characters against the driver's own types and raised on the
        #   first integral column it met -- which on every dataset here is the primary key -- or,
        #   where it did not raise, compared a zero-padded identifier against the integer the column
        #   stores and called a correct load corrupt. Without the key it paired by position, against
        #   two sides that were in two different orders.
        # WHY : Assumptions: the rules are DERIVED from the reader's own layout rather than declared
        #   here, so the copybook stays the single authority on what each field holds. A table in
        #   this module would be free to disagree with the layout, and the disagreement would show
        #   up as a verification failure against data that was loaded correctly.
        # WHY : ⚠️ Assumptions: the derived rules are then NARROWED by the rows just read back,
        #   because the layout settles what the source characters mean and only the column settles
        #   which form they were stored in. Five fields differ between the two -- the transaction
        #   category code is declared unsigned display and stored as a fixed-width code, per
        #   specification rule T1 -- and comparing those as numbers would pass a corruption that
        #   changed their padding. The narrowing is recorded on `refined_canonicalisation`.
        comparison = compare_record_digests(
            record_name=arguments.dataset,
            qualified_table=target.qualified_name,
            source_records=(
                prepare_record(target, record, context) for record in tally.tap(records)
            ),
            target_records=loaded,
            fields=fields,
            layout=reader.layout,
            canon=refined_canonicalisation(canonicalisation_of(reader.layout), loaded, fields),
            identity=_verification_identity(target),
        )
        # WHY : Refactoring Rationale: the sealed columns are AUDITED beside the digest, and before
        #   this they were not covered at all. The digest necessarily excludes them -- an envelope's
        #   initialisation vector is drawn per value, so the same identifier enciphered twice
        #   differs and any digest over those bytes would report a correct load as corrupt. The
        #   consequence was a coverage hole rather than a cosmetic gap: the card and the customer
        #   loads could have stored a null verification value or dropped every identifier, and this
        #   command would still have printed a clean comparison.
        audit = audit_sealed_columns(
            record_name=arguments.dataset,
            qualified_table=target.qualified_name,
            columns=target.sealed_fields(),
            expected=tally.counts,
            stored_envelopes=_sealed_envelopes(connection, target),
        )
    # Refactoring Rationale: the refused set is `_step_errors()` where it was `_DECODE_ERRORS`, so a
    #   projection failure raised while the source side is being PREPARED is a return code rather
    #   than a traceback. The source records are prepared lazily inside the comparison -- the
    #   generator above is consumed there -- so an `AuroraLoadError` from a projection surfaces
    #   here, in a command whose docstring promises a binary exit status, and the narrower set let
    #   it escape as an unhandled type an operator cannot act on.
    except ConfigurationError as exc:
        # WHY : Assumptions: the FATAL tier, caught ahead of the operational tuple below for the
        #   reason recorded on the load command: 8 is retried by the batch state and a retry cannot
        #   publish a parameter that was never published.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    # WHY : Refactoring Rationale: `KeyError` joins this pass's own errors, and it was in neither
    #   `_step_errors()` nor `_verification_errors()`. A deterministic field absent from the decoded
    #   record or from the read-back row raises it out of `_canonical_values`, so the one failure a
    #   field-name mismatch produces reached an operator as a traceback naming the place the digest
    #   noticed rather than the dataset and table they asked about -- while every other command here
    #   reports a documented failure as a return code the batch state branches on. The comparator's
    #   TYPE refusal needs no clause of its own: `ChecksumValueError` derives from both
    #   `ChecksumVerificationError` and `TypeError`, so `_verification_errors()` already admits it
    #   while the published `TypeError` contract still holds for any other caller.
    # WHY : Trade-offs: catching `KeyError` in a command handler can also absorb a mapping lookup
    #   fault elsewhere in the block, which a narrower rewrap inside the comparator would not.
    #   Accepted, and classified HERE rather than rewrapped, because the comparator already puts the
    #   FIELD NAME in the message: rewrapping would restate it as a second error type for one
    #   condition, and an absent key is the standard signal for exactly this.
    except (*_step_errors(), *_verification_errors(), KeyError) as exc:
        _LOGGER.error("%s", _reported(exc))
        return EXIT_FAILED
    finally:
        connection.close()
    print(comparison.render())
    # WHY : Assumptions: the audit is rendered even for a record that seals NOTHING, because
    #   "0 column(s)" is the positive statement that the digest covered the whole row. Printing it
    #   only when there is something to say would leave a reader unable to tell a full-coverage
    #   record from one whose audit had been skipped.
    print(audit.render())
    # Trade-offs: a difference exits FAILED rather than raising, on the same reasoning as the
    #   row-count report above: the rendered comparison names every located difference, and a
    #   traceback would replace the one artifact an operator acts on with the place code noticed.
    return EXIT_OK if comparison.verified and audit.verified else EXIT_FAILED


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
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.loaders.aurora import target_for
    from carddemo_migration.verify.money_parity import total_source_fields, total_target_money

    try:
        reader, records = _reader_and_records(arguments)
        target = target_for(arguments.dataset)
        connection = _connect_for(target)
    except ConfigurationError as exc:
        # WHY : Assumptions: the FATAL tier, caught ahead of the operational tuple below for the
        #   reason recorded on the load command: 8 is retried by the batch state and a retry cannot
        #   publish a parameter that was never published.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    except _step_errors() as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    money_fields = _money_field_names(reader)
    if not money_fields:
        print(f"SKIP {arguments.dataset} declares no signed display field, so it holds no money")
        connection.close()
        return EXIT_OK
    failures = 0
    try:
        # WHY : ⚠️ Refactoring Rationale: EVERY money field is totalled in ONE traversal of the
        #   records, where this used to `list(records)` and then run a separate pass per field. Both
        #   halves of the old shape were costs the seed extracts are too small to expose.
        #   Materialising made peak memory a function of the whole extract instead of one
        #   record; and
        #   the reason it HAD to materialise was the per-field re-pass, because a one-shot reader
        #   totals zero on every pass after the first -- a difference of zero that reads as
        #   agreement. Totalling every field together removes the re-pass, which removes the need to
        #   materialise, which is why this is one substitution rather than two optimisations.
        # Assumptions: the pass runs INSIDE the block that closes the connection, unchanged.
        #   Draining the lazy reader is where a width or a decode failure surfaces, and an early
        #   return from outside this block would leak the open connection.
        # WHY : Assumptions: the record count is taken by COUNTING the one traversal rather than by
        #   asking the measurement for it, because the one-pass total reports per-field figures
        #   and a
        #   record count is a property of the extract rather than of a field. It is still reported,
        #   because it is what distinguishes agreement over a loaded dataset from agreement over an
        #   empty one -- two zero totals also match.
        counted = 0

        def _counting(source: Any) -> Any:
            """Yield every record once, recording how many were seen.

            Parameters
            ----------
            source : Any
                The lazy record iterator to pass through.

            Yields
            ------
            Any
                Each record, unchanged and exactly once.

            Raises
            ------
            None
                Whatever the underlying reader raises propagates unchanged.
            """
            nonlocal counted
            for record in source:
                counted += 1
                yield record

        measured = total_source_fields(_counting(records), money_fields)
        for field in money_fields:
            column = target.columns.get(field)
            if column is None:
                # Alternatives Considered: a money field the target does not map is reported and not
                #   silently passed. It means the load is dropping a monetary value, which is a
                #   finding even though this particular comparison cannot be made.
                print(f"UNMAPPED {arguments.dataset}.{field} has no target column")
                failures += 1
                continue
            source_total, negative_rows = measured[field]
            target_total = total_target_money(connection, target.schema, target.table, column)
            matched = source_total == target_total
            # WHY : ⚠️ Assumptions: the line names the COPYBOOK FIELD as well as the target column,
            #   where it once named only the record and the column. The pass compares a copybook
            #   field against a column, so both halves of that pairing belong in the line: a record
            #   carrying five money fields otherwise renders five lines distinguishable only by
            #   their target column, which leaves a reader to invert the field-to-column mapping in
            #   their head to learn which source field disagreed.
            # Assumptions: the rendered line carries the two TOTALS, their difference and the
            #   negative-row count, and no individual amount. A total over a dataset is an aggregate
            #   an operator acts on; a single account's balance is not, and this pass reads the
            #   account master.
            print(
                f"{'MATCH' if matched else 'DIFFER'} {arguments.dataset}.{field} ->"
                f" {target.schema}.{target.table}.{column}"
                f" source={source_total} target={target_total}"
                f" difference={target_total - source_total:+} records={counted}"
                f" source_negative_rows={negative_rows}"
            )
            failures += 0 if matched else 1
    # WHY : Assumptions: the refused set spans BOTH tiers -- the operational errors a step can meet
    #   and the refusals a verification pass raises when the value the driver returned is not the
    #   value the query promised. A count that is not an exact whole number, or a total that is not
    #   exact at the money scale, means the query is not the query; reporting that as a traceback
    #   loses the classified status the batch state branches on, and loses it for the one failure
    #   mode that says the verification itself cannot be trusted.
    # WHY : Refactoring Rationale: the operational half is `_step_errors()` where it was the
    #   narrower `_DECODE_ERRORS` pair. The pair covered a layout refusal and a record-length
    #   refusal and NOT `OSError`, so an extract that was deleted, denied or truncated part-way
    #   through the read -- which is where a lazy reader raises, inside this block rather than
    #   the setup above -- escaped
    #   as a traceback from a command whose contract is a classified exit status.
    except (*_step_errors(), *_verification_errors()) as exc:
        _LOGGER.error("%s", _reported(exc))
        return EXIT_FAILED
    finally:
        connection.close()
    return EXIT_OK if failures == 0 else EXIT_FAILED


def _money_report_extracts(
    pairs: Sequence[str], default_encoding: str
) -> tuple[SourceExtract, ...]:
    """Resolve the ``LAYOUT=PATH[=ENCODING]`` pairs an operator supplied into source extracts.

    Purpose
    -------
    Turn the one option this report takes into the typed extracts
    :func:`carddemo_migration.verify.money_parity.verify_money_totals` measures the source half
    from, so a malformed pairing is reported as a usage error naming the offending value rather
    than as a failure inside the pass.

    Parameters
    ----------
    pairs : Sequence[str]
        The raw option values, each ``LAYOUT=PATH`` or ``LAYOUT=PATH=ENCODING``.
    default_encoding : str
        The form to read an extract in when its own pairing does not override it.

    Returns
    -------
    tuple[SourceExtract, ...]
        One extract per pairing, in the order given.

    Raises
    ------
    _UsageError
        If a pairing carries no ``=``, names an empty layout or path, or declares more than three
        components. Each is a mistyped command line rather than a failed verification, so it is
        separated from the pass's own refusals and reported as usage.
    MoneyParityVerificationError
        Propagated from :class:`SourceExtract` when the layout is unknown to the readers, feeds no
        money column, or the declared form is outside the two admitted values.
    """
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.verify.money_parity import SourceExtract

    resolved: list[SourceExtract] = []
    for pair in pairs:
        # Assumptions: the split is bounded at two so that a WINDOWS-style path or any path
        #   containing an equals sign survives. Splitting unbounded would take the first `=` inside
        #   a path as the encoding separator and report a layout no reader owns.
        components = pair.split("=", 2)
        if len(components) < 2:
            raise _UsageError(
                f"--extract {pair!r} is not a LAYOUT=PATH pairing; the layout names the record and"
                " the path names the extract holding it"
            )
        layout_name = components[0].strip()
        path_text = components[1].strip()
        encoding = components[2].strip() if len(components) == 3 else default_encoding
        if not layout_name or not path_text:
            raise _UsageError(
                f"--extract {pair!r} leaves the layout or the path empty; both are required because"
                " neither can be derived from the other"
            )
        # WHY : Assumptions: a repeated layout is REFUSED rather than resolved last-one-wins. The
        #   pass totals at most one extract per layout, so two --extract values for one layout
        #   means the operator believes both are being read, and a verdict reached over half the
        #   input they supplied is worse than no verdict at all.
        # WHY : Trade-offs: this is reported as USAGE rather than as a failed verification, on the
        #   same split the malformed pairings above take. A layout named twice is a mistyped command
        #   line, not a load that did not verify, and the orchestrator branches on the difference --
        #   a usage status is not retried, where a failed verification is investigated.
        if any(extract.layout_name == layout_name for extract in resolved):
            raise _UsageError(
                f"--extract names layout {layout_name!r} more than once; this pass totals at most"
                " one extract per layout, so the run is refused rather than one of them being"
                " chosen"
            )
        resolved.append(
            SourceExtract(layout_name=layout_name, path=Path(path_text), encoding=encoding)
        )
    return tuple(resolved)


def _verify_money_total_report(arguments: argparse.Namespace) -> int:
    """Run the whole-migration money-total report on a read-only session and print every line.

    Purpose
    -------
    Execute ``data-migration/sql/verify/money_totals.sql`` as the least-privilege reporting role,
    recompute the same totals independently from the supplied extracts' own bytes, and report the
    per-column verdict -- so one invocation answers "is the money that landed the money that was
    sent" for the whole load rather than for one dataset at a time.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``extract``, the ``LAYOUT=PATH[=ENCODING]`` pairings to measure the source half
        from; ``encoding``, the form to read a pairing that declares none; and ``sql_root``, the
        directory holding the ``sql`` tree, or ``None`` to resolve it from the installed package's
        own location.

    Returns
    -------
    int
        :data:`EXIT_OK` when every money column agrees on both its exact total and its
        negative-row count, :data:`EXIT_USAGE` when a pairing is malformed, otherwise
        :data:`EXIT_FAILED` -- which covers a disagreement, an unreadable or undecodable extract,
        a session that is not the reporting role, and a query that cannot be located or read.

    Raises
    ------
    None
        Every documented failure is reported as a return code, because the cutover gate that
        invokes this branches on one.
    """
    # Refactoring Rationale: this command exists because the whole-migration money pass was
    #   DELIVERED AND UNREACHED. verify/money_parity.py publishes read_source_totals,
    #   verify_money_totals, open_reporting_connection and require_reporting_session, and nothing
    #   in the distribution called any of them -- the only registered money command was the
    #   per-dataset verify-money-parity, which opens a SCHEMA-OWNER connection and compares one
    #   dataset at a time. So the pass designed to certify every money column of the load with no
    #   write authority over what it certifies could be run only by hand in a Python session, and
    #   the post-load gate the runbook publishes could not include it.
    # Alternatives Considered: extending verify-money-parity to take the whole-migration path when
    #   --dataset was omitted. Rejected because the two answer different questions under different
    #   authority: that command reads ONE extract and compares it against one table as the schema
    #   owner, this one reads a server-side aggregate over every money column as the reporting role
    #   and needs several extracts. One command with two authorities and two shapes would make the
    #   privilege a verification ran under depend on which options were present.
    # Assumptions: the extracts are REQUIRED rather than optional, and the pass itself refuses a
    #   set that leaves a column whose layout ships a committed extract unmeasured. A report with no
    #   source side would print five columns marked not comparable and exit zero, which is the
    #   precise shape of false assurance this pass exists to remove.
    # WHY : Assumptions: this import is local for the reason the module header records --
    #   a defect in the loader or a verification pass must not fail the commands that
    #   touch neither. It resolves once per invocation, from the interpreter's module
    #   cache on any later call.
    from carddemo_migration.verify.money_parity import (
        MoneyParityVerificationError,
        MoneyQueryError,
        MoneyResultSetContractError,
        money_total_query_path,
        verify_money_totals,
    )
    from carddemo_migration.verify.money_parity import (
        open_reporting_connection as open_money_reporting_connection,
    )
    from carddemo_migration.verify.money_parity import reporting_role as money_reporting_role

    try:
        extracts = _money_report_extracts(arguments.extract, arguments.encoding)
    except _UsageError as usage:
        _LOGGER.error("%s", usage)
        return EXIT_USAGE
    except MoneyParityVerificationError as refused:
        _LOGGER.error("%s", refused)
        return EXIT_FAILED

    try:
        connection = open_money_reporting_connection()
    except ConfigurationError as exc:
        # WHY : Assumptions: the FATAL tier, caught ahead of the operational tuple below for the
        #   reason recorded on the load command: 8 is retried by the batch state and a retry cannot
        #   publish a parameter that was never published.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    # WHY : Assumptions: the verification tier is included for the reason recorded on the
    #   row-count report: a pass that could not read its own result set has established nothing, and
    #   this command answers with a classified status rather than a traceback.
    except (*_step_errors(), *_verification_errors(), MoneyParityVerificationError) as exc:
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    try:
        # Assumptions: the option names the DISTRIBUTION ROOT and is resolved to the query file
        #   here, exactly as the row-count report resolves its own, because the sql tree ships
        #   BESIDE the package rather than inside it.
        query_path = (
            None if arguments.sql_root is None else money_total_query_path(arguments.sql_root)
        )
        report = verify_money_totals(connection, extracts, query_path=query_path)
    except (
        *_step_errors(),
        MoneyQueryError,
        MoneyResultSetContractError,
        MoneyParityVerificationError,
        OSError,
    ) as exc:
        # Assumptions: OSError is refused here as well as the pass's own types, because an extract
        #   that cannot be opened is the most likely failure of this command and is an operator
        #   action rather than a defect -- a traceback would report the place the file was opened
        #   instead of which extract is missing.
        _LOGGER.error("%s", exc)
        return EXIT_FAILED
    finally:
        connection.close()
    # Assumptions: the role that certified the report is logged and deliberately NOT printed, on the
    #   same reasoning the row-count report records: the printed text stays byte-stable between runs
    #   so two runs over unchanged data diff to nothing.
    _LOGGER.info("money total report certified by role=%s", money_reporting_role())
    print(report.render())
    # Trade-offs: a mismatch exits FAILED rather than raising, so the rendered report names every
    #   column that differs instead of stopping at the first.
    return EXIT_OK if report.verified else EXIT_FAILED


# Assumptions: the three passes are held in ONE ordered tuple and `verify-all` iterates it, rather
#   than calling the three handlers by name in sequence. The order 1, 2, 3 is contractual --
#   `README.md` section 10 records that each pass is blind to the failure the next one catches, so a
#   run that reached pass 3 without passing pass 1 would report a money total over rows whose count
#   was never checked -- and a tuple makes the order one declaration a reader can see rather than
#   three call sites to compare. Trade-offs: it also makes the sequence UNWEAKENABLE in the way
#   the plan requires: no argument selects a subset, because a subset is not expressible.
_VERIFICATION_PASSES: Final[tuple[tuple[str, Any], ...]] = (
    ("row counts", _verify_row_counts),
    ("record checksums", _verify_checksum),
    ("money parity", _verify_money_parity),
)


#: The manifest key holding the dataset entries, and the three keys each entry must carry.
_MANIFEST_DATASETS_KEY: Final[str] = "datasets"

_MANIFEST_ENTRY_KEYS: Final[tuple[str, ...]] = ("dataset", "source", "encoding")

_MANIFEST_ENCODINGS: Final[tuple[str, ...]] = ("ascii", "ebcdic")


class _ManifestError(ValueError):
    """Raised when the verification manifest is absent, unreadable or malformed.

    Purpose
    -------
    Give the aggregate command one catchable type for every way its input can be wrong, so a bad
    manifest is reported as the documented usage failure rather than as a traceback out of the JSON
    parser. The manifest is operator-authored, so it is the one input of this command most likely to
    be wrong.

    Parameters
    ----------
    None
        Inherits the base exception's own arguments.

    Raises
    ------
    None
        Declaring an exception class raises nothing.
    """


def _manifest_entries(path: Path) -> tuple[tuple[str, str, str], ...]:
    """Read the dataset-to-source manifest the aggregate verification is driven from.

    Purpose
    -------
    Supply the one thing that kept ``verify-all`` unregistered: a declaration of WHICH datasets are
    being verified and WHERE each one's extract is. Every other verification command is told its
    source explicitly on the command line, and an aggregate over every dataset cannot be, because
    this distribution holds no dataset-to-path mapping -- the seed trees are an input an operator
    names, not a location this package knows.

    Parameters
    ----------
    path : Path
        The manifest file. Each entry's ``source`` is resolved RELATIVE TO THIS FILE'S directory
        unless it is already absolute, so one manifest describes a delivery tree wherever that tree
        is mounted.

    Returns
    -------
    tuple[tuple[str, str, str], ...]
        One ``(dataset, source, encoding)`` triple per entry, in the manifest's own declared order.

    Raises
    ------
    _ManifestError
        If the file cannot be read, is not JSON, does not carry a non-empty ``datasets`` array, or
        holds an entry that is not an object carrying exactly the three required string keys with a
        recognised encoding, or names a dataset no layout registers.
    """
    # Alternatives Considered: JSON rather than a second format. It is the standard library's, so it
    #   adds no dependency to a distribution whose whole dependency set is pinned and hash-locked,
    #   and it is the format this repository already uses for its other operator-authored input --
    #   `tests/mocks/localstack_s3_manifest.json` and `samples/**/app_config.json`. A bespoke
    #   line-oriented format would need its own parser, its own quoting rule and its own tests.
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except OSError as unreadable:
        raise _ManifestError(f"the manifest {path} could not be read: {unreadable}") from unreadable
    except json.JSONDecodeError as malformed:
        raise _ManifestError(f"the manifest {path} is not valid JSON: {malformed}") from malformed
    if not isinstance(document, dict):
        raise _ManifestError(
            f"the manifest {path} must be a JSON object carrying a {_MANIFEST_DATASETS_KEY!r} array"
        )
    declared = document.get(_MANIFEST_DATASETS_KEY)
    # Assumptions: an EMPTY array is refused rather than treated as "nothing to verify". A run that
    #   verified no dataset and exited zero is the one outcome an aggregate verification gate must
    #   not have, because it reads as a whole migration certified.
    if not isinstance(declared, list) or not declared:
        raise _ManifestError(
            f"the manifest {path} declares no dataset; a verification that checks nothing cannot"
            f" certify a load, so {_MANIFEST_DATASETS_KEY!r} must be a non-empty array"
        )
    registered = set(layouts.names())
    entries: list[tuple[str, str, str]] = []
    for position, entry in enumerate(declared, start=1):
        if not isinstance(entry, dict) or set(entry) != set(_MANIFEST_ENTRY_KEYS):
            raise _ManifestError(
                f"entry {position} of {path} must be an object carrying exactly"
                f" {', '.join(_MANIFEST_ENTRY_KEYS)}"
            )
        values = tuple(entry[key] for key in _MANIFEST_ENTRY_KEYS)
        if not all(isinstance(value, str) and value for value in values):
            raise _ManifestError(
                f"entry {position} of {path} must give a non-empty string for each of"
                f" {', '.join(_MANIFEST_ENTRY_KEYS)}"
            )
        declared_dataset, source, encoding = values
        # Assumptions: the dataset name and the seed form are validated HERE, before the first
        #   pass runs, rather than being discovered when a later entry fails. An aggregate that
        #   stops at the first failure would otherwise report pass 1 of dataset 1 as green and then
        #   abort on a typo in entry 9, leaving an operator unsure which half of the run to trust.
        # WHY : Refactoring Rationale: the declared name is NORMALISED through `_layout_identity`
        #   before it is validated, and it was not. README section 5.2 states that a manifest entry
        #   declares "the same three values the four commands above take on the command line", and
        #   those four accept either spelling -- the orchestrator's plural snake-case seed token or
        #   the upper-case copybook layout name -- because their `--dataset` is normalised by the
        #   same function. This validation checked the layout registry alone, so `transaction_types`
        #   was refused as usage error 2 in a manifest while `verify-checksum --dataset
        #   transaction_types` accepted it: one documented vocabulary, two behaviours, and an
        #   operator transcribing a working command into a manifest hit the difference.
        # WHY : Assumptions: the NORMALISED name is what the entry carries onward, because that is
        #   what the three handlers, the layout registry and the load-target registry are all keyed
        #   by -- so a token spelling is accepted at the boundary and never travels past it.
        dataset = _layout_identity(declared_dataset)
        if dataset not in registered:
            raise _ManifestError(
                f"entry {position} of {path} names dataset {declared_dataset!r}, which is neither a"
                f" layout name ({', '.join(layouts.names())}) nor a seed dataset token"
                f" ({', '.join(seed_datasets.seed_dataset_tokens())}); list-datasets reports the"
                " admitted names"
            )
        if encoding not in _MANIFEST_ENCODINGS:
            raise _ManifestError(
                f"entry {position} of {path} declares encoding {encoding!r}; the admitted forms are"
                f" {' and '.join(_MANIFEST_ENCODINGS)}"
            )
        resolved = Path(source)
        entries.append(
            (dataset, str(resolved if resolved.is_absolute() else path.parent / resolved), encoding)
        )
    return tuple(entries)


def _pass_arguments(
    arguments: argparse.Namespace, dataset: str, source: str, encoding: str
) -> argparse.Namespace:
    """Compose the namespace one per-dataset verification pass is invoked with.

    Purpose
    -------
    Build the argument object the three per-dataset handlers read, from the selector triple a
    manifest entry declares PLUS every member :func:`main` places on the invocation rather than the
    command line. Composing it in one place is what keeps the aggregate command's invocation
    identical to the per-dataset command's: the three handlers are the real ones, so a member they
    read and this namespace lacks is not a smaller invocation but a failed one.

    Parameters
    ----------
    arguments : argparse.Namespace
        The aggregate command's own namespace, read for the invocation-scoped members. Only
        ``work_root`` is one today.
    dataset : str
        The record layout the pass is to verify, already normalised from either accepted spelling.
    source : str
        The extract the pass is to read: an absolute local path, or a key in the object-store
        scheme.
    encoding : str
        ``ascii`` or ``ebcdic``, as the manifest entry declared it.

    Returns
    -------
    argparse.Namespace
        A namespace carrying ``dataset``, ``source``, ``encoding`` and ``work_root``.

    Raises
    ------
    AttributeError
        If the aggregate command's namespace carries no ``work_root``, which means this was called
        from somewhere other than a dispatched subcommand -- :func:`main` sets it on every one.
    """
    # WHY : Alternatives Considered: forwarding the whole namespace with the three selectors
    #   overwritten -- `argparse.Namespace(**vars(arguments), dataset=..., ...)` -- was rejected. It
    #   would also carry `manifest`, `source_root` and `sql_root` into a per-dataset handler that
    #   reads none of them, so a future handler consulting one of those by mistake would silently
    #   pick up the AGGREGATE command's value instead of failing. Naming the members explicitly
    #   keeps the per-pass invocation exactly as wide as the per-dataset command line is.
    # WHY : Assumptions: `work_root` is read through attribute access rather than `getattr` with a
    #   default. A default would let this compose a namespace pointing at a directory nothing
    #   created, and the failure would then arrive as a materialised object written outside the
    #   invocation's scratch space rather than as the programming error it is.
    return argparse.Namespace(
        dataset=dataset,
        source=source,
        encoding=encoding,
        work_root=arguments.work_root,
    )


def _verify_manifested(arguments: argparse.Namespace) -> int:
    """Run all three verification passes over every manifested dataset, in the fixed order.

    Purpose
    -------
    Give a cutover ONE invocation whose success means the whole load was verified three ways. The
    three passes are individually reachable, and a cutover that ran them one at a time could stop
    half way and still report each command it managed to run as green -- so "verified" would mean
    whatever the operator happened to invoke. This command is the indivisible form.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``manifest``, the path to the dataset-to-source declaration.

    Returns
    -------
    int
        :data:`EXIT_OK` only when every pass of every manifested dataset passed. The status of the
        first failing pass otherwise, so the classification the individual command would have
        reported is the classification this one reports.

    Raises
    ------
    None
        A malformed manifest is converted to :data:`EXIT_USAGE`, because it is an invocation defect
        rather than a data defect and an orchestrator distinguishes the two by status.
    """
    try:
        entries = _manifest_entries(arguments.manifest)
    except _ManifestError as unusable:
        _LOGGER.error("%s", unusable)
        return EXIT_USAGE
    # Assumptions: the datasets are verified in the manifest's own declared ORDER, and the operator
    #   owns that order. A load runs in a dependency order -- a cross-reference after the masters it
    #   points at -- and re-deriving one here would put this command in the business of knowing the
    #   load plan, which the README records as the operator's to name.
    for dataset, source, encoding in entries:
        for label, verify in _VERIFICATION_PASSES:
            print(f"=== {dataset}: {label} ===")
            # WHY : Refactoring Rationale: the namespace is composed by `_pass_arguments`, which
            #   carries the invocation-scoped members forward, where this line built one from the
            #   three manifest values alone. That omission made this command unusable: every pass
            #   resolves its source through `_reader_and_records`, which reads `work_root` -- the
            #   directory `main` opens per invocation and an object-store source is materialised
            #   into -- so the FIRST pass of the FIRST dataset raised AttributeError and the whole
            #   documented manifest procedure exited on a traceback before verifying anything.
            # Trade-offs: each pass is invoked through the SAME handler the individual subcommand
            #   invokes, with a namespace built here, rather than through an extracted body the two
            #   share. What that costs is one synthesised namespace per pass; what it buys is that
            #   there is exactly one implementation of each pass, so this command cannot come to
            #   verify something subtly different from what `verify-checksum` verifies -- which is
            #   the failure mode an aggregate re-implementation has.
            status = verify(_pass_arguments(arguments, dataset, source, encoding))
            if status != EXIT_OK:
                # Assumptions: the run stops at the FIRST failing pass rather than continuing to
                #   collect every failure, and the stop is the contract README section 5.2 states.
                #   Each pass is blind to what the next one catches, so a pass that runs after a
                #   failed predecessor reports a comparison over data already known to be wrong --
                #   and an operator reading three failures cannot tell which is the cause.
                _LOGGER.error(
                    "verification stopped: %s failed the %s pass with status %s",
                    dataset,
                    label,
                    status,
                )
                return status
    print(f"all three verification passes passed for {len(entries)} dataset(s)")
    return EXIT_OK


def _verify_registry(arguments: argparse.Namespace) -> int:
    """Run all three verification passes as one indivisible gate over the whole migration.

    Purpose
    -------
    Make the combined gate the migration plan mandates an actual command. The three passes are each
    blind to the defect the next one catches -- counts miss a corrupted field, checksums miss a sign
    overpunch that both sides decode identically, money totals miss a missing row whose amount is
    zero -- so a load verified by one pass is not verified. Before this existed the passes were four
    separate verbs an operator invoked in whatever order and number they chose, and
    :mod:`carddemo_migration.verify.gate`, which enforces coverage and order, had no caller at all.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``source_root`` (``None`` falls back to the staging-root environment variable),
        ``sql_root`` (``None`` resolves both committed queries from the package's own location) and
        the ``work_root`` :func:`main` opened.

    Returns
    -------
    int
        :data:`EXIT_OK` only when all three passes ran and every one of them verified,
        :data:`EXIT_FAILED` when any pass reached a negative verdict or the gate stopped early, and
        :data:`EXIT_FATAL` when the environment could not be resolved.

    Raises
    ------
    None
        Every documented failure becomes a return code, because the batch state that gates the
        transition into business processing branches on one.
    """
    from carddemo_migration.loaders.aurora import prepare_record, target_for
    from carddemo_migration.verify.checksum import (
        SealableValueTally,
        audit_sealed_columns,
        canonicalisation_of,
        compare_record_digests,
        deterministic_field_names,
        refined_canonicalisation,
    )
    from carddemo_migration.verify.gate import (
        CombinedPassResult,
        VerificationGateError,
        run_verification_gate,
    )
    from carddemo_migration.verify.money_parity import (
        SourceExtract,
        money_total_query_path,
        verify_money_totals,
    )
    from carddemo_migration.verify.row_counts import (
        open_reporting_connection,
        row_count_query_path,
        verify_row_counts,
    )

    # WHY : Assumptions: the corpus is EBCDIC and is not an option. Every descriptor's
    #   `source_object` names the `AWS.M2.CARDDEMO.*.PS` fixed-block form, so that is the only
    #   corpus in which all eleven datasets exist -- `app/data/ASCII` ships no user-security extract
    #   at all and `readers/usrsec.py` publishes no character entry point. An `--encoding` option
    #   would offer a value that cannot cover the set the gate is required to cover.
    encoding = "ebcdic"
    source_root = (
        str(arguments.source_root)
        if arguments.source_root is not None
        else os.environ.get(seed_datasets.STAGING_ROOT_VARIABLE, "").strip()
    )
    work_root = Path(arguments.work_root)
    try:
        tokens = _gate_extracts(source_root)
        sources = {
            token: _gate_source(seed_datasets.seed_dataset(token), source_root) for token in tokens
        }
        # Assumptions: the two connections are opened together and are two DIFFERENT authorities,
        #   not one shared session. Passes 1 and 3 execute the committed whole-schema queries and
        #   both call `require_reporting_session`, so they need the reporting role; pass 2 compares
        #   whole rows field by field and needs UNMASKED row-level reads, which the reporting role's
        #   masked aggregate views cannot give. Collapsing them onto one role would either break
        #   pass 2 or widen the reporting role until it could read a primary account number.
        reporting = open_reporting_connection()
        verifying = _verification_connection()
    except ConfigurationError as exc:
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    except _step_errors() as exc:
        _LOGGER.error("%s", _reported(exc))
        return EXIT_FAILED

    # WHY : Refactoring Rationale: the resolved extracts are memoised HERE, in one closure both
    #   record-reading passes go through, and the change repairs a defect that only the deployed
    #   configuration could produce. `sources` holds whatever `_gate_source` returned, which under
    #   the deployment's own staging root -- `s3://<bucket>/<prefix>`, composed by
    #   infra/modules/step-functions-batch -- is an object-store key rather than a path. Pass 2
    #   reached it through `_records_for`, which materialises such a key into `work_root` first;
    #   pass 3 built `Path(sources[token])` directly, and `Path` collapses `s3://bucket/key` to the
    #   relative directory `s3:/bucket/key`, so the one pass that certifies money totals opened a
    #   file that cannot exist. The gate therefore failed with a bare ENOENT after passes 1 and 2
    #   had already succeeded -- in the deployed form, and only there, which is why a local run
    #   certified 3 of 3 and the nightly chain's verification state could never pass.
    # WHY : Assumptions: the resolution is MEMOISED per token rather than repeated, so pass 3 reads
    #   the very bytes pass 2 read and digested rather than fetching the object a second time. A
    #   second fetch would be two transfers of the same object per dataset and, worse, would let the
    #   two passes disagree about the source if the object were replaced between them -- which is
    #   exactly the disagreement a three-pass gate exists to detect rather than to contain.
    # WHY : Trade-offs: it is memoised LAZILY -- inside the closure -- rather than resolved for all
    #   eleven datasets in the setup block above. Eager resolution was the alternative and is
    #   rejected on two counts: it transfers every extract even when pass 1 fails and no extract
    #   is ever read, and it moves every `materialised ...` log line ahead of pass 1's own
    #   output, so an operator reading a failed run could no longer see which pass read what.
    materialised: dict[str, Path] = {}

    def _local_extract(token: str, layout_name: str) -> Path:
        """Resolve one dataset's configured extract to a local path, at most once per run.

        Parameters
        ----------
        token : str
            The registry token whose extract is wanted. Indexes the resolved-source mapping and
            the memo.
        layout_name : str
            The record layout the extract holds, used to derive the declared fixed-record width a
            materialising transfer is checked against.

        Returns
        -------
        Path
            A local path holding the extract's bytes: the configured path itself when the staging
            root is a directory, or the file the object was materialised into when it is a bucket
            prefix.

        Raises
        ------
        ConfigurationError
            Propagated from :func:`_resolved_source` if an object-store source is configured but no
            client can be constructed.
        DatasetSourceError
            Propagated if the object is absent, or its transferred bytes disagree with its recorded
            length, recorded digest or the declared record geometry.
        StagingServiceError
            Propagated if the provider refuses the probe or the read.
        """
        if token not in materialised:
            materialised[token] = _resolved_source(sources[token], work_root, layout_name)
        return materialised[token]

    def _row_counts() -> Any:
        """Run pass 1: the whole-migration row-count report, server-side.

        Parameters
        ----------
        None
            Closes over the reporting connection and the resolved query root.

        Returns
        -------
        RowCountReport
            Every declared relation's counted rows against its baseline, with one binary verdict.

        Raises
        ------
        RowCountVerificationError
            If the live session is not the reporting role or the derived inventory is wrong.
        VerificationQueryError
            If the committed query cannot be located, read, or is not a single pure statement.
        """
        query_path = (
            None if arguments.sql_root is None else row_count_query_path(arguments.sql_root)
        )
        return verify_row_counts(reporting, query_path=query_path)

    def _checksums() -> Any:
        """Run pass 2: one record-digest comparison per dataset, folded into one result.

        Parameters
        ----------
        None
            Closes over the dataset tokens, their resolved sources and the verifying connection.

        Returns
        -------
        CombinedPassResult
            The per-dataset comparisons in registry order, under one label and one verdict.

        Raises
        ------
        ChecksumVerificationError
            If a digest cannot be taken because a record breaches the timestamp contract.
        LayoutError
            If a layout publishes no entry point for the EBCDIC corpus.
        DatasetSourceError
            If an object-store source is absent or its bytes disagree with its recorded identity.
        """
        parts: list[Any] = []
        for token in tokens:
            layout_name = seed_datasets.seed_dataset(token).layout_name
            # WHY : Assumptions: the source is resolved through `_local_extract` and the LOCAL path
            #   is handed to `_records_for`, rather than handing it the configured value and letting
            #   its own resolver materialise. The two are equivalent for this pass -- a local path
            #   passes through `_resolved_source` untouched -- and going through the memo is what
            #   makes pass 3 read the same materialised file instead of transferring it again.
            reader, records = _records_for(
                layout_name, str(_local_extract(token, layout_name)), encoding, work_root
            )
            target = target_for(layout_name)
            context = _load_context_for(target)
            tally = SealableValueTally(target.sealed_fields())
            # WHY : Assumptions: the gate's pass is given the SAME canonicalisation rules and the
            #   same pairing identity the per-dataset command is given, and it is collected for the
            #   same reason: `refined_canonicalisation` narrows the rules by the rows read back, so
            #   it needs them a second time. Passing neither -- which this pass did while the
            #   per-dataset command passed both -- is not a smaller version of the same check: it
            #   renders the source characters against the driver's own types and raises on the first
            #   integral column, which on every dataset here is the primary key.
            gate_fields = deterministic_field_names(reader.layout, target.comparable_fields())
            gate_rows = list(_read_back(verifying, target))
            parts.append(
                compare_record_digests(
                    record_name=layout_name,
                    qualified_table=target.qualified_name,
                    source_records=(
                        prepare_record(target, record, context) for record in tally.tap(records)
                    ),
                    target_records=gate_rows,
                    fields=gate_fields,
                    layout=reader.layout,
                    canon=refined_canonicalisation(
                        canonicalisation_of(reader.layout), gate_rows, gate_fields
                    ),
                    identity=_verification_identity(target),
                )
            )
            # Assumptions: the sealed-column audit is appended as a PART of this pass rather than
            #   reported beside it, because `SealedColumnAudit` already answers the two members the
            #   gate reads and the aggregate's verdict must therefore include it. Reporting it
            #   outside the aggregate would print the finding and still certify the load.
            parts.append(
                audit_sealed_columns(
                    record_name=layout_name,
                    qualified_table=target.qualified_name,
                    columns=target.sealed_fields(),
                    expected=tally.counts,
                    stored_envelopes=_sealed_envelopes(verifying, target),
                )
            )
        # Assumptions: the parts are folded through `CombinedPassResult`, whose verdict is False for
        #   an EMPTY aggregate. That is the property that matters here: a registry that resolved to
        #   no datasets, or a loop whose iterable was already consumed, would otherwise report a
        #   clean pass over zero comparisons and the gate would certify a load nothing had read.
        return CombinedPassResult(label="record checksums", parts=tuple(parts))

    def _money() -> Any:
        """Run pass 3: exact money totals and negative-row counts over the whole migration.

        Parameters
        ----------
        None
            Closes over the dataset tokens, their resolved sources and the reporting connection.

        Returns
        -------
        MoneyTotalReport
            Every declared money column's exact total and negative-row count against the source
            extract's own, with one binary verdict.

        Raises
        ------
        MoneyParityVerificationError
            If the live session is not the reporting role, or a column whose layout ships a
            committed extract was left unmeasured.
        MoneyQueryError
            If the committed query cannot be located, read, or is not a single pure statement.
        MoneyResultSetContractError
            If the result set breaches the query's published contract.
        """
        # Assumptions: an extract is offered for every dataset and the pass itself decides which it
        #   requires. It refuses the run when a layout that ships a committed extract contributes no
        #   total, and reports a not-comparable line for the one layout that ships none -- so
        #   filtering the set here would be this command second-guessing the authority that owns the
        #   rule, and guessing wrong is how a forgotten extract becomes a clean bill of health.
        # WHY : Assumptions: the path is the one `_local_extract` resolves and NEVER
        #   `Path(sources[token])`. `SourceExtract` opens the path it is given, and the configured
        #   root is an object-store prefix in every deployed environment, so constructing a
        #   `Path` from it produced the relative directory `s3:/bucket/key` and this pass failed
        #   with a bare ENOENT after the other two had passed. Money parity is also the pass with
        #   the least tolerance for reading the wrong bytes: its verdict is an exact total, so a
        #   source that silently differed from the one pass 2 digested would report a mismatch an
        #   operator would attribute to the load.
        extracts = tuple(
            SourceExtract(
                layout_name=seed_datasets.seed_dataset(token).layout_name,
                path=_local_extract(token, seed_datasets.seed_dataset(token).layout_name),
                encoding=encoding,
            )
            for token in tokens
            if _has_money_columns(seed_datasets.seed_dataset(token).layout_name)
        )
        query_path = (
            None if arguments.sql_root is None else money_total_query_path(arguments.sql_root)
        )
        return verify_money_totals(reporting, extracts, query_path=query_path)

    try:
        # Assumptions: the three thunks are handed to the gate in the order the mandate runs them
        #   and under the names it declares, and the gate REFUSES a wrong order or a missing pass
        #   before running anything. That refusal is why this dictionary is written out literally
        #   rather than built from a loop over `GATE_PASSES`: a loop would make the order a
        #   consequence of the gate's own constant and could never disagree with it, which is
        #   exactly the disagreement the refusal exists to catch.
        verification = run_verification_gate(
            {"row_counts": _row_counts, "checksum": _checksums, "money_parity": _money}
        )
    except VerificationGateError as exc:
        # Assumptions: a gate-construction refusal is the FATAL tier. It means this command handed
        #   the gate the wrong passes, which is a defect in the distribution rather than in the
        #   load, and no retry changes it.
        _LOGGER.error("the verification gate was not constructed correctly: %s", _reported(exc))
        return EXIT_FATAL
    except ConfigurationError as exc:
        # Assumptions: a configuration failure is caught HERE as well as around the setup block
        #   above, and the second clause is not redundant. Pass 2 resolves each target's load
        #   context while the gate is running -- a key identifier for a sealed column, the published
        #   subject document for the user record -- so an unresolvable parameter surfaces inside the
        #   gate rather than before it. Without this clause that one class of failure escaped as a
        #   traceback from the one command a cutover gates on.
        _LOGGER.error("the environment could not be resolved: %s", _reported(exc))
        return EXIT_FATAL
    except (*_step_errors(), *_verification_errors()) as exc:
        _LOGGER.error("%s", _reported(exc))
        return EXIT_FAILED
    finally:
        verifying.close()
        reporting.close()
    print(verification.render())
    # Trade-offs: the verdict is BINARY at the exit status while the rendering names every pass and
    #   every line beneath it. A status that distinguished "pass 2 failed" from "pass 3 failed"
    #   was the alternative and was rejected: the batch state's only correct action is the same
    #   either way -- do not proceed into posting -- and the report is where an operator reads
    #   which pass said so.
    return EXIT_OK if verification.verified else EXIT_FAILED


# WHY : Refactoring Rationale: `verify-all` has TWO coverage sources and one gate, and the split is
#   the reconciliation of two independently written forms of the same verb. One was written for an
#   OPERATOR: it takes a manifest declaring dataset, source and seed form per entry, because where a
#   delivery's bytes are is an operator fact the package deliberately holds no mapping for, and it
#   drives the three per-dataset handlers so there is exactly one implementation of each pass. The
#   other was written for the NIGHTLY CHAIN: a container cannot author a manifest, so it resolves
#   every registered extract beneath one root, runs the two committed whole-migration queries, and
#   does so on the SELECT-only verification login. Neither subsumes the other, and dropping either
#   would leave a caller with no form it can use.
# WHY : Assumptions: what the two forms share is the property the verb exists for -- three passes,
#   in the fixed order 1, 2, 3, with no option that can skip one or continue past a failure. The
#   coverage source is what differs, and it is chosen by the presence of `--manifest` rather than by
#   a mode flag, so a caller cannot ask for the manifest form and then not supply a manifest.
# WHY : Alternatives Considered: keeping two subcommands, `verify-all` and something like
#   `verify-gate`. Rejected because the state machine and the runbook would then name different
#   verbs for the same mandate, and the first reader to notice would have to work out which one
#   "verified" meant; a mandate with two spellings is the failure this verb was created to end.
def _verify_all(arguments: argparse.Namespace) -> int:
    """Run all three verification passes as one gate, over a manifest or over the whole registry.

    Purpose
    -------
    Give a cutover and the nightly chain ONE verb whose zero exit means the load was verified three
    ways, in the mandated order, with no option that could narrow it.

    Parameters
    ----------
    arguments : argparse.Namespace
        Carries ``manifest`` -- a path selects the operator form, ``None`` the registry form -- and,
        for the registry form, ``source_root``, ``sql_root`` and the ``work_root`` :func:`main`
        opened.

    Returns
    -------
    int
        :data:`EXIT_OK` only when all three passes ran and all three verified. Otherwise the status
        the failing form reports: the first failing pass's own status for the manifest form, and
        :data:`EXIT_FAILED` or :data:`EXIT_FATAL` for the registry form.

    Raises
    ------
    None
        Every failure is converted to a status, because this verb is what an orchestration state
        branches on.
    """
    if arguments.manifest is not None:
        return _verify_manifested(arguments)
    return _verify_registry(arguments)


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
            "record-layout contract the extracts are decoded against, decode one "
            "record of an extract through that contract to prove it before a load, "
            "bulk-load one dataset into the schema that owns it, verify a load "
            "three independent ways -- row counts, record checksums and exact money "
            "totals -- and run that whole sequence for one dataset as a single "
            "orchestrated refresh."
        ),
        # Assumptions: the epilog cites data-migration/README.md section 5.6 -- this
        #   package's own exit contract -- and deliberately NOT the repository's graded
        #   aggregate rubric. That rubric admits a warn tier in which 4 is a passing
        #   state, and it is scoped to the COBOL parity oracle alone; pointing an operator
        #   at it here would tell them a partly-completed migration step can exit non-zero
        #   and still have passed, which is the one reading this CLI must never invite.
        epilog=(
            "Exit status is binary: 0 succeeded, any non-zero value did not. Failures are "
            "classified as 2 invoked incorrectly, 8 the step did not complete, 16 the "
            "environment could not be reached -- see data-migration/README.md section 5.6. "
            "Reads CARDDEMO_ENVIRONMENT, CARDDEMO_PARAMETER_PREFIX and the TLS "
            "trust-anchor settings from the environment; no endpoint or credential is an "
            "argument."
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
        type=_layout_identity,
        help=(
            "dataset to decode: a layout name printed by list-datasets, or the seed token "
            "stage-dataset takes for the same records"
        ),
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
    # WHY : Refactoring Rationale: `--source`, `--generation`, `--domain` and `--object-name` are
    #   WITHDRAWN. All four were once required, then briefly optional overrides with
    #   registry-derived defaults, and are now absent -- and the step from "optional" to "absent" is
    #   the one that matters. Each let a caller replace a value the seed registry or the durable
    #   generation reservation is the authority for, and each carried a concrete destructive
    #   reading rather than merely a redundant one.
    #
    #   `--source` was the worst of the four twice over. It assumed a path the only environment
    #   that runs this command does not have -- the staging task's definition carries no volume, no
    #   mount point and no file system -- so the flag named a location that could not exist while
    #   the registry knew one that did. And because the containment root for a caller-named path
    #   can only be that path's own parent, an operator-supplied leaf had no root to be contained
    #   BY: the extract is now always resolved beneath the configured staging root, which is a
    #   directory or an `s3://` prefix, and cannot be redirected out of it.
    #
    #   `--domain` selected the bounded-context segment of the destination prefix. The deployment
    #   grants the staging task write access per prefix, so a wrong context does not fail -- it
    #   writes one context's master under another context's prefix, inside a policy boundary
    #   written to keep exactly that from happening.
    #
    #   `--generation` let a caller name the generation number. The retention sweep keeps the
    #   newest five BY NUMBER, so an out-of-sequence number makes the sweep scratch a generation
    #   that is not the oldest, and a scratched generation is gone. The reservation is durable and
    #   keyed by the execution token, so a retried branch already reuses the number its first
    #   attempt claimed -- which is the property the override could only weaken.
    #
    #   `--object-name` named the object inside the generation prefix, which the later verification
    #   pass locates from the registry; a caller-chosen name is a staged object nothing reads.
    #
    # WHY : Trade-offs: an operator who genuinely holds a corrected extract on local disk is served
    #   by `refresh-dataset --source`, which stages the same bytes and then LOADS and VERIFIES
    #   them, so a wrong file is reported rather than filed. Keeping a second, unverified route
    #   here was the alternative and is rejected: the two destructive readings above are reachable
    #   from it and nothing in the orchestrated chain needs it.
    # WHY : Assumptions: this override exists so the ONE staging handler can write a family whose
    #   segment differs from the dataset's own, which is the shape three baseline families need:
    #   app/jcl/DEFGDGD.jcl creates the first generation of TRANTYPE.BKUP, TRANCATG.PS.BKUP and
    #   DISCGRP.BKUP by copying TRANTYPE.PS, TRANCATG.PS and DISCGRP.PS verbatim, so the bytes are
    #   one dataset's and the destination family is another's.
    # WHY : Alternatives Considered: a separate `stage-backup` subcommand. Rejected because it would
    #   be this handler with two arguments substituted, and a second copy of the reservation, the
    #   staging and the scratch would be free to drift from the first -- while an operator would
    #   have to know which of two commands writes a generation.
    stage_dataset.add_argument(
        "--dataset-segment",
        default=None,
        help=(
            "dataset path segment of the prefix; defaults to the dataset's registered segment. Set "
            "it to write a different generation family from the same bytes, as the baseline's "
            "reference-data backups do"
        ),
    )
    # WHY : Assumptions: `--retain` SURVIVES the withdrawal, and it is the one of the six that
    #   should. Retaining MORE history than the contract requires destroys nothing, so the
    #   argument is admitted with a floor rather than removed: `_retention_count` refuses anything
    #   below the baseline's own `LIMIT(5) SCRATCH` operand at parse time, with argparse's usage
    #   message, before a generation has been reserved or an extract opened.
    stage_dataset.add_argument(
        "--retain",
        type=_retention_count,
        default=None,
        help=(
            "number of newest generations to preserve; the rest are scratched "
            f"(default and floor: the dataset's registered limit, {DEFAULT_RETENTION_COUNT}, "
            "the baseline's LIMIT(5) SCRATCH)"
        ),
    )
    stage_dataset.set_defaults(handler=_stage_dataset)

    # Assumptions: this command is registered immediately after `stage-dataset` because it is the
    #   ORCHESTRATED form of the same work plus the four steps that make a refresh a refresh, and
    #   the two share the staging option set for that reason. It takes the same `--dataset` token
    #   vocabulary, so an operator who has run one has run the other.
    # Refactoring Rationale: the nightly seed-refresh state invoked `stage-dataset` alone, which
    #   copies bytes into a generation prefix and stops there, while its own documentation described
    #   it as replacing ten IDCAMS DELETE/DEFINE/REPRO master loads. This command is what closes
    #   that gap in ONE task per dataset, so the state keeps the Map-of-one-task shape the AAP fixes
    #   for it.
    refresh_dataset = subcommands.add_parser(
        "refresh-dataset",
        help="fetch, stage, load and verify one seed dataset as a single orchestrated step",
        description=(
            "Perform the whole per-dataset cutover for one registered seed dataset: fetch the "
            "exported extract from the deployment's provisioned source prefix, stage its bytes as "
            "a new generation, decode it per field and bulk-load it into the schema that owns it, "
            "then run all three verification passes over the load -- and, for the dataset that "
            "feeds the transaction master, advance the identifier allocator past the rows it "
            "loaded. This is the migrated form of one IDCAMS master-refresh job."
        ),
    )
    refresh_dataset.add_argument(
        "--dataset",
        required=True,
        help=(
            "seed-dataset token, one of those list-datasets reports; the record layout, the "
            "owning schema, the source file name, the record geometry and the retention limit "
            "are all resolved from it"
        ),
    )
    refresh_dataset.add_argument(
        "--business-date",
        required=True,
        type=_business_date,
        help="business date the staged generation is filed under, as YYYY-MM-DD",
    )
    refresh_dataset.add_argument(
        "--extract-prefix",
        default=_DEFAULT_EXTRACT_PREFIX,
        help=(
            "prefix inside the dataset bucket holding the exported extracts, which the "
            "deployment provisions and grants this task read access to "
            f"(default: {_DEFAULT_EXTRACT_PREFIX})"
        ),
    )
    refresh_dataset.add_argument(
        "--encoding",
        default="ebcdic",
        choices=("ascii", "ebcdic"),
        help=(
            "seed form of the extract; defaults to ebcdic because every source object the "
            "registry names is a mainframe-character-set .PS extract, and the text twins are a "
            "developer convenience rather than the cutover input"
        ),
    )
    refresh_dataset.add_argument(
        "--source",
        default=None,
        help=(
            "path to a local extract to use instead of fetching one, for an operator refreshing "
            "one master from a corrected file"
        ),
    )
    # WHY : Refactoring Rationale: `--generation` and `--domain` are WITHDRAWN here for the same
    #   two destructive readings recorded on `stage-dataset` above -- an out-of-sequence generation
    #   makes the retention sweep scratch a generation that is not the oldest, and a wrong
    #   bounded-context segment writes one context's master under another's prefix. Withdrawing
    #   them from the staging verb alone would have left both reachable through this one, which
    #   composes the same staging step, so the withdrawal is only real if it is made on both. The
    #   generation is reserved against the execution token and the context comes from the registry;
    #   the backup family this command also stages takes its own context from the registry too.
    # WHY : Assumptions: `--source` is KEPT, and the asymmetry with `stage-dataset` is deliberate.
    #   The orchestrated chain invokes this command with `--dataset`, `--business-date` and
    #   `--extract-prefix` and nothing else -- a committed test asserts the definition names no
    #   local path at all -- so the override is not on the orchestrated path. What it serves is the
    #   one case a bare staging verb cannot: an operator refreshing one master from a corrected
    #   file, where the bytes are then LOADED and put through all three verification passes, so a
    #   wrong file is reported rather than filed under a retained generation.
    refresh_dataset.add_argument(
        "--retain",
        type=_retention_count,
        default=None,
        help=(
            "number of newest generations to preserve; the rest are scratched "
            f"(default and floor: the dataset's registered limit, {DEFAULT_RETENTION_COUNT})"
        ),
    )
    refresh_dataset.set_defaults(handler=_refresh_dataset)

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

    reconcile_sequences = subcommands.add_parser(
        "reconcile-sequences",
        help="advance the transaction-identifier allocator past every loaded identifier",
        description=(
            "Advance ledger.transaction_id_seq past the largest sequence-format identifier "
            "ledger.transactions holds. Run after the last load into that table and BEFORE "
            "writes are enabled: the allocator's starting position is derived by its own "
            "migration from the rows present when the migration ran, which on a cutover is "
            "none. Only ever advances, so a repeat run is a no-op. Takes no options."
        ),
    )
    reconcile_sequences.set_defaults(handler=_reconcile_sequences)

    # Assumptions: the four commands below share one option set -- --dataset, --source and
    #   --encoding -- because they are four questions about the same pairing of a dataset and a
    #   table, and a caller that has just loaded a record verifies it by changing only the verb.
    # Assumptions: all four therefore also share the seed-token resolution, applied by wrapping
    #   each handler below rather than by four copies of it. That is what keeps "change only the
    #   verb" true for the orchestrator as well as for an operator: the state machine's dataset
    #   token is accepted by the load and by each verification pass in the same form.
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
        # WHY : Refactoring Rationale: the selector is normalised through `_layout_identity`, so a
        #   seed token arrives at the handler as the LAYOUT NAME every registry downstream is keyed
        #   by -- `layouts.layout`, `aurora.target_for` and `readers.dataset_reader` all are.
        #   Normalising at the parser rather than inside each handler means the two halves of the
        #   dataset vocabulary agree in ONE place, and it is the direction that works: mapping both
        #   spellings onto a token instead would leave every one of those three registries unable
        #   to resolve what it was handed.
        # WHY : Assumptions: the two spellings cannot collide -- every token is lower-case with
        #   underscores and every layout name is upper-case -- and `seed_datasets` asserts that
        #   disjointness at import rather than leaving it as a reading of the data. So a value that
        #   resolves resolves to exactly one dataset, and accepting both spellings adds no
        #   ambiguity to remove. Refusing the second spelling was the alternative, on the argument
        #   that one flag with two vocabularies is ambiguous; the disjointness measurement is what
        #   retires that argument.
        command.add_argument(
            "--dataset",
            required=True,
            type=_layout_identity,
            help=(
                "either a seed-dataset token or a record-layout identifier reported by "
                "list-datasets; a dataset the seed registry knows derives its extract and seed "
                "form, and any other layout requires --source and --encoding with it"
            ),
        )
        # WHY : ⚠️ Refactoring Rationale: these two are OPTIONAL where both were required, which
        #       is what lets the orchestrator invoke the load with the one value it holds. A Step
        #       Functions branch iterates the seed-dataset token -- `accounts`, `card_xref` -- and
        #       has no authority to compose a layout name, a path or a seed form; requiring all
        #       three meant the load could be invoked by an operator and by nothing else, so a
        #       cutover staged ten extracts into object storage and left every table empty.
        # WHY : Assumptions: neither is defaulted HERE. Both are derived after parsing, by
        #       `_with_registered_extract_defaults`, from the seed-dataset registry and the
        #       deployment's extract location -- so argparse still reports exactly what the
        #       operator wrote, and a layout name given without them is refused with both named.
        command.add_argument(
            "--source",
            default=None,
            help=(
                "path to the local seed extract to decode; omit it with a seed-dataset token, "
                "which resolves the registered extract from the deployment's extract location"
            ),
        )
        command.add_argument(
            "--encoding",
            default=None,
            choices=("ascii", "ebcdic"),
            help=(
                "seed form of the extract; declared rather than sniffed, because an "
                "all-ASCII EBCDIC dataset would sniff as text and decode to plausible "
                "wrong values. Omit it with a seed-dataset token, whose registered extracts "
                "are all in the EBCDIC form"
            ),
        )
        command.set_defaults(handler=_with_registered_extract_defaults(handler))

    # WHY : Refactoring Rationale: a SECOND `verify-all` registration stood here and is withdrawn.
    #   Two registrations of one subcommand is not a duplicated line that the later copy simply
    #   wins -- `argparse` RAISES `conflicting subparser: verify-all` while building the parser, so
    #   every subcommand of this CLI, not just this one, became unreachable. It was found by
    #   invoking the parser rather than by reading it, which is the only way this shape shows up.
    # WHY : Assumptions: the withdrawn copy was the EARLIER design and its help text is the
    #   evidence: it declared pass 2's reach to be 'the three reference records TRANTYPE, TRANCAT
    #   and DISGROUP', which this package no longer does -- `verify/checksum.py` canonicalises by
    #   value class and reaches every record shipping a committed extract. It also offered a
    #   single-dataset `--dataset/--source/--encoding` triple as the alternative to `--manifest`,
    #   where the surviving registration offers the whole-registry form beneath `--source-root`,
    #   which is the form the nightly chain runs and the form docs/runbooks/data-migration.md
    #   invokes. Keeping the triple would have meant two spellings of 'verify everything' whose
    #   coverage differed, which is the ambiguity this command exists to remove.
    # WHY : Trade-offs: the cross-option check that accompanied the withdrawn copy in `main` is
    #   withdrawn with it, because the flag it was gated on -- `requires_dataset_or_manifest` --
    #   was set only by that registration, and it read `arguments.dataset`, which the surviving
    #   registration never defines. Its argument is kept and is worth restating: a malformed
    #   COMMAND LINE must exit with the usage status, not the failure status, because an
    #   orchestrator branching on the code would otherwise treat an operator's typo as a failed
    #   verification. The surviving form has no cross-option rule to enforce -- omitting
    #   `--manifest` selects the registry form rather than leaving anything ambiguous -- so the
    #   requirement is met by there being no such state, and `argparse` still answers an unknown
    #   option or an unparsable value with the usage status on its own.

    # Assumptions: this command is registered on its own rather than inside the loop above,
    #   because it takes NONE of that loop's three options. It reads a server-side aggregate over
    #   every table instead of a local extract, so it has no dataset to name, no file to read and
    #   no seed form to declare -- and accepting three options it ignored would invite an operator
    #   to believe the report was scoped to whichever dataset was passed.
    verify_row_count_report = subcommands.add_parser(
        "verify-row-count-report",
        help="run the whole-migration row-count report as the read-only reporting role",
        description=(
            "Execute data-migration/sql/verify/row_counts.sql on a session for the "
            "carddemo_reporting role and print its six-column verdict for every dataset at "
            "once. The session's role is checked against the server before the query runs, so a "
            "pass that could write cannot certify the load. Unlike verify-row-counts, this "
            "reads no local extract."
        ),
    )
    # Trade-offs: the root is optional and defaults to resolution from the package's own
    #   location, which is correct in a source checkout and in an editable install and is expected
    #   to fail in a plain wheel -- the sql directory ships BESIDE the package rather than inside
    #   it. Naming the option is what lets an operator running from an unpacked distribution point
    #   at the same file they would run with psql, rather than at a second copy in a wheel.
    verify_row_count_report.add_argument(
        "--sql-root",
        default=None,
        type=Path,
        help=(
            "directory holding the sql tree, whose verify/row_counts.sql is executed; omit it in "
            "a source checkout or an editable install, and pass . in the container image, whose "
            "working directory holds the copied tree"
        ),
    )
    verify_row_count_report.set_defaults(handler=_verify_row_count_report)

    # Assumptions: this command is registered on its own for the same reason its row-count sibling
    #   is -- it takes NEITHER the three-option set the per-dataset commands share NOR that
    #   sibling's option-free shape. It needs several extracts because it certifies every money
    #   column of the load at once, and it needs no --dataset because it is scoped to all of them.
    # Refactoring Rationale: the third verification pass had a whole-migration form and no way to
    #   run it. Pass 1's whole-migration report was registered above; pass 3's was not, so the
    #   post-load gate could reach two of the three passes at the level the cutover needs and the
    #   money pass only one dataset at a time under the schema owner's authority.
    verify_money_total_report = subcommands.add_parser(
        "verify-money-total-report",
        help="run the whole-migration money-total report as the read-only reporting role",
        description=(
            "Execute data-migration/sql/verify/money_totals.sql on a session for the "
            "carddemo_reporting role, recompute every money total independently from the supplied "
            "extracts' own bytes, and print the per-column verdict -- exact total and "
            "negative-row count -- for every money column of the migration at once. The session's "
            "role is checked against the server before the query runs, so a pass that could write "
            "cannot certify the load. Unlike verify-money-parity, one invocation covers every "
            "money column rather than one dataset's."
        ),
    )
    # Trade-offs: the pairing is one option repeated rather than a directory the command scans. A
    #   root would have to know each layout's file name in each of the two shipped forms, and the
    #   two trees do not agree -- app/data/ASCII/acctdata.txt against
    #   app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS -- so a scan would either hard-code one naming
    #   convention or guess. Naming each extract explicitly is longer to type once and cannot
    #   silently measure the wrong file.
    verify_money_total_report.add_argument(
        "--extract",
        action="append",
        default=[],
        required=True,
        metavar="LAYOUT=PATH[=ENCODING]",
        help=(
            "one source extract to recompute totals from, given as the record-layout identifier, "
            "the path to the extract, and optionally that extract's own seed form; repeat the "
            "option once per extract. Every money column whose layout ships a committed extract "
            "must be covered or the run is refused, which today is ACCOUNT, DALYTRAN, TCATBAL and "
            "DISGROUP -- ledger.transactions ships none because the posting job fills it"
        ),
    )
    verify_money_total_report.add_argument(
        "--encoding",
        required=True,
        choices=("ascii", "ebcdic"),
        help=(
            "seed form of every extract that does not declare its own; declared rather than "
            "sniffed, because an all-ASCII EBCDIC dataset would sniff as text and decode to "
            "plausible wrong values"
        ),
    )
    verify_money_total_report.add_argument(
        "--sql-root",
        default=None,
        type=Path,
        help=(
            "directory holding the sql tree, whose verify/money_totals.sql is executed; omit it "
            "in a source checkout or an editable install, and pass . in the container image, "
            "whose working directory holds the copied tree"
        ),
    )
    verify_money_total_report.set_defaults(handler=_verify_money_total_report)

    verify_all = subcommands.add_parser(
        "verify-all",
        help="run all three verification passes over every manifested dataset, in order",
        description=(
            "Run verification pass 1 (row counts), then pass 2 (record checksums), then pass 3 "
            "(money parity), stopping at the first failure. With --manifest the coverage is the "
            "datasets that manifest declares, verified through the same handlers the per-dataset "
            "verbs use. Without it the coverage is the WHOLE registry, resolved beneath "
            "--source-root, and the two committed whole-migration queries are executed on the "
            "read-only verification login -- which is the form the nightly chain runs and the one "
            "a cutover gates on. The order is fixed either way and no option can skip a pass or "
            "continue past a failure: each pass is blind to the failure the next one catches, so a "
            "partial run cannot certify a load. Exits zero only when all three passed."
        ),
    )
    # Trade-offs: the manifest is REQUIRED and has no default, so this command cannot be run in a
    #   configuration where it silently verifies fewer datasets than a cutover contains. A default
    #   pointing at, say, `app/data/ASCII` would have made the common case shorter and would also
    #   have made a truncated delivery tree look like a passing verification.
    verify_all.add_argument(
        "--manifest",
        default=None,
        type=Path,
        help=(
            "path to a JSON manifest declaring the datasets to verify: an object with a "
            "non-empty 'datasets' array whose entries each carry 'dataset', 'source' and "
            "'encoding'. A relative source is resolved against the manifest's own directory. "
            "Omit it to verify every registered seed dataset beneath --source-root instead"
        ),
    )
    # Trade-offs: the root is OPTIONAL and falls back to the same environment variable
    #   `stage-dataset` reads, so the batch task needs no argument it does not already have on its
    #   task definition. Naming it is what lets an operator verify against a checkout while the
    #   deployment verifies against the delivered prefix, with no second variable to keep in step.
    verify_all.add_argument(
        "--source-root",
        default=None,
        help=(
            "directory or object-store prefix holding the extracts, each located by the registry's "
            f"own source-object name; used only when --manifest is omitted, and itself defaults to "
            f"{seed_datasets.STAGING_ROOT_VARIABLE}"
        ),
    )
    # Assumptions: the option names the DISTRIBUTION ROOT and both committed queries are resolved
    #   beneath it, for the reason `verify-row-count-report` records at its own declaration: the sql
    #   tree ships beside the package rather than inside it, so the package-relative default is
    #   correct in a checkout and in an editable install and cannot work from a plain wheel.
    verify_all.add_argument(
        "--sql-root",
        default=None,
        type=Path,
        help=(
            "directory holding the sql tree, whose verify/row_counts.sql and verify/"
            "money_totals.sql are executed by the registry form; omit it in a source checkout or "
            "an editable install, and pass . in the container image, whose working directory holds "
            "the copied tree"
        ),
    )
    verify_all.set_defaults(handler=_verify_all)

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
        The process exit status, and it is binary in meaning: :data:`EXIT_OK` (``0``) says
        the subcommand did what it was asked, and every other value says it did not.
        :data:`EXIT_USAGE` (``2``), :data:`EXIT_FAILED` (``8``) and :data:`EXIT_FATAL`
        (``16``) classify *which* failure occurred, per ``data-migration/README.md``
        section 5.6; none of them is a pass. The repository's graded aggregate rubric, in
        which ``4`` is a passing warn tier, is deliberately not used -- see this module's
        docstring for why that distinction is load-bearing here.

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

    # WHY : Refactoring Rationale: the masking key is resolved HERE, once, before any handler runs
    #   and before the work root is opened. It used to be resolved lazily by the first call that
    #   masked a value, which put the refusal in two places no operator would look: `_redacted`,
    #   while PRINTING a successfully decoded record, and `zoned._render_content`, while composing
    #   the message of a decode failure. The second is the one worth engineering against -- an
    #   unusable environment variable was reported as though the delivered extract had failed to
    #   decode, so the diagnosis pointed at the bytes instead of at the configuration.
    # WHY : Assumptions: an unusable key is classified as EXIT_FATAL (16) rather than EXIT_FAILED
    #   (8), because README section 5.6 reserves 16 for "the environment could not be resolved" and
    #   8 for "the step ran and did not succeed". No step has run at this point. The distinction is
    #   load-bearing for the orchestrator: a `Choice` on 16 means an operator must fix
    #   configuration, where 8 means the data or the database is at fault, and misfiling this as 8
    #   would send a retry at a fault no retry can clear.
    # WHY : Assumptions: an UNSET variable raises nothing and is left unset. Running without a
    #   supplied key is a supported configuration -- the process-scoped random key is then used --
    #   so this validates what was supplied rather than requiring that something be supplied.
    # WHY : Alternatives Considered: wrapping the handler call in `except layouts.LayoutError` and
    #   classifying it there. Rejected because a `LayoutError` is also exactly what a malformed
    #   extract raises, so one clause could not tell an unusable key from an unusable record and
    #   would have had to choose a single status for both -- reporting a configuration fault as a
    #   data fault or the reverse. Failing before dispatch means each keeps its own status.
    try:
        layouts.require_mask_key_material()
    except layouts.LayoutError as unusable:
        _LOGGER.error(_reported(unusable))
        return EXIT_FATAL

    # WHY : Assumptions: every invocation runs inside ONE temporary directory, handed to the
    #   handler as `work_root`, and it is opened here rather than per handler so that a command
    #   which fetches an extract from the object store has somewhere to put it that is removed on
    #   every exit path -- including a refusal. A handler-local directory was the alternative and
    #   would have had to be repeated in each of the handlers that fetch, which is how one of them
    #   comes to leak a file into the container's writable layer.
    with tempfile.TemporaryDirectory(prefix=_EXTRACT_WORK_PREFIX) as work:
        arguments.work_root = Path(work)
        # WHY : Refactoring Rationale: cancellation is caught and classified where it used to
        #   propagate. An operator interrupting a long load got the interpreter's own handling: a
        #   `KeyboardInterrupt` traceback and status 130, which is outside the 0/2/8/16 set this
        #   entry point publishes and which the orchestrator's exit-code `Choice` states have no
        #   branch for -- so a cancelled task fell to the catch-all failure path carrying a number
        #   nothing in this distribution documents.
        # WHY : Assumptions: the status is EXIT_FAILED (8), which is "the step did not complete".
        #   There is deliberately no cancellation tier: adding a fifth status would change a
        #   published classification every runbook, README section 5.6 and every `Choice` in the
        #   state machine already agrees on, to distinguish a case an orchestrator cannot cause --
        #   nothing sends SIGINT to a Fargate task, so this path is reached only by a human at a
        #   terminal, who has the log line rather than the number.
        # WHY : Assumptions: NOTHING is rolled back or cleaned up here, because both are already
        #   correct without it. The loader closes its connection in a `finally`, so an interrupted
        #   delivery is rolled back by the backend and leaves no orphaned session; and returning
        #   from inside this `with` runs the temporary directory's own cleanup, so a partially
        #   fetched extract is removed on this path exactly as on every other. Repeating either
        #   here would be a second owner for work that has one.
        # WHY : Assumptions: `BaseException` is not caught, only `KeyboardInterrupt`. A
        #   `SystemExit` raised by a handler must keep its own status, and catching the base class
        #   would also swallow it.
        try:
            return int(arguments.handler(arguments))
        except KeyboardInterrupt:
            _LOGGER.error(
                "%s was cancelled before it completed; any database work it had started was"
                " rolled back and its temporary files are removed",
                arguments.subcommand,
            )
            return EXIT_FAILED


# =============================================================================
# The combined verification gate
# -----------------------------------------------------------------------------
# WHY : Refactoring Rationale: everything from here to `_gate_source` supports one subcommand,
#   `verify-all`, which runs the three passes in a mandated order over every dataset it is told to
#   cover and reduces the result to one exit status. The reason it is a verb of its own rather than
#   a shell loop over the three per-dataset verbs is that a loop's coverage is whatever the loop
#   happens to contain, which is how the runbook step it replaces went wrong twice; this verb takes
#   no option that can skip a pass or continue past a failure.
# WHY : Assumptions: the helpers here stream and sanitize where the per-dataset verbs do not. A
#   read-back for the gate is consumed in bounded batches through a server-side cursor rather than
#   materialised, and a provider refusal is reduced to its service code and status before it is
#   logged, so an assumed role ARN, an account identifier or a resolved endpoint cannot reach the
#   execution log. Both are properties of running unattended inside the nightly chain.
# =============================================================================


#: Rows fetched per round trip when the loaded table is read back for a digest comparison.
#:
#: Trade-offs: one thousand is chosen between two bad endpoints. A batch of one makes the comparison
#: one round trip per row, which dominates the cost of the digest itself on a table of any size; a
#: batch equal to the table restores the whole-result buffering this constant exists to remove. A
#: thousand rows of the widest comparable projection -- sixteen fields on the customer master -- is
#: tens of kilobytes, so peak memory stays bounded while the round-trip count stays proportional to
#: the table divided by a thousand.
_READ_BACK_BATCH_ROWS: Final[int] = 1000


#: Prefix for the task-local directory an object-store extract is materialised into.
#:
#: Assumptions: the directory is created under the platform temporary location rather than under a
#: configured path, because the only requirement is that it be writable, private to this task and
#: removed afterwards -- and Fargate's ephemeral task storage is exactly that. A configured work
#: directory would be one more deployment value to get wrong for no property gained. The prefix
#: names the package so a directory surviving an abrupt task kill is attributable.
_EXTRACT_WORK_PREFIX: Final[str] = "carddemo-extract-"


# Assumptions: reading an extract can fail for reasons that are neither a decode fault nor a
#   configuration fault -- the file is gone, the mode bits deny it, the volume is full, the object
#   store dropped the connection mid-stream -- and every one of them arrives as an `OSError` from
#   inside a LAZY iterator, so it surfaces while a pass is consuming records rather than while it is
#   setting up. Naming the type once here is what lets every consumption site translate it into the
#   documented failed tier instead of letting a stack trace escape a batch step.
_SOURCE_READ_ERRORS: Final[tuple[type[Exception], ...]] = (OSError,)


# Assumptions: a source that is an object-store key is materialised before it is decoded, and that
#   resolution has failure modes of its own -- the object was never delivered or its bytes disagree
#   with its recorded digest, the provider refused the probe or the read, the value named the scheme
#   with no bucket, or the dataset family holds no staged generation at all. Every one is the FAILED
#   tier rather than the fatal one: the environment resolved, and what did not is the source.
#   Naming them here rather than in each handler is what keeps a remote source and a local one
#   reporting the same tier for the same class of fault.
# Refactoring Rationale: the staging module's own BASE is named rather than its three leaf refusals,
#   and the change was forced by a real gap. `GenerationDiscoveryError` -- raised when a dataset
#   family holds no generation, which is precisely what an omitted `--source` finds when the staging
#   step has not run -- is a SIBLING of `DatasetSourceError`, not a subclass, so the leaf list
#   left the commonest ordering mistake in the whole chain escaping as a traceback. Naming the
#   base covers it, and covers the next sibling too.
_REMOTE_SOURCE_ERRORS: Final[tuple[type[Exception], ...]] = (
    GenerationRetentionError,
    SeedDatasetError,
)


# Assumptions: every load and verification handler refuses on the same set, because each of
#   them can fail for exactly the same reasons -- an unregistered record name, an extract that
#   does not decode, a target that has no declared mapping, or a session whose authority is wrong
#   -- and a handler that caught a narrower set would turn one of those into a traceback purely by
#   where it happened to be invoked from.
# Refactoring Rationale: `ConfigurationError` is NO LONGER in this set and is caught by its own
#   clause ahead of it in every handler, so that it reports the FATAL tier. Grouping it here meant
#   an unresolvable parameter, an absent secret and a missing AWS SDK all returned 8 -- "the step
#   did not complete" -- which is the tier a batch state RETRIES. Retrying a step whose environment
#   is not configured cannot succeed, so the retry burned every attempt and the execution failed
#   several minutes later with the same diagnosis it had at the first attempt. 16 is the tier that
#   says stop and fix the deployment, and `data-migration/README.md` section 5.6 already assigned
#   it that meaning.
# Assumptions: `VerificationSessionError` STAYS in this tuple, deliberately, even though it also
#   describes a deployment fault. It means the session authenticated as a role that is not the
#   read-only verifier -- the server was reached and answered, so the environment is not
#   unreachable; what failed is the step's own precondition. It is also the one of these a retry can
#   legitimately clear, because the credential it needs may still be being applied.
def _step_errors() -> tuple[type[Exception], ...]:
    """Return the exception types a load or verification step reports as a failed step.

    Purpose
    -------
    Assemble the refusal set at CALL time rather than at import, so the three classes that live in
    the loader and the verification package are resolved only by a command that already needs
    those modules.

    Parameters
    ----------
    None
        The set is fixed; what varies is when the modules holding it are imported.

    Returns
    -------
    tuple[type[Exception], ...]
        The loader's own failure type, the protected-column refusal, the verification-session
        refusal, the two decode errors, :class:`OSError`, the three object-store source refusals,
        and the database driver's own exception base when the driver is installed.

    Raises
    ------
    ConfigurationError
        Propagated if the loader package cannot be imported at all, which on this path means the
        distribution is incomplete rather than the step having failed.
    """
    # WHY : Refactoring Rationale: this was the module constant `_STEP_ERRORS`, and it had to become
    #   a function for the same reason the imports moved: naming three classes from the loader and
    #   the verification package at module scope imported both packages before any subcommand had
    #   been chosen. A caller reads it as one call in an `except` clause, which is the same shape a
    #   tuple constant had at the use site.
    # WHY : Refactoring Rationale: `OSError` and the DRIVER's own error base are in this set and
    #   were not. Both escaped as tracebacks, and both escaped from places a narrower guard could
    #   not have covered by being placed differently. The readers are LAZY, so a vanished extract, a
    #   denied mode bit and a truncated object-store stream all surface as `OSError` inside the call
    #   that CONSUMES records -- after the setup block returned successfully. And a driver failure
    #   can arrive at either end: `select current_user` in the verification-session guard is a query
    #   too, so a dropped connection failed during setup while a refused aggregate failed during
    #   consumption. One set covering both ends is why this is a single function rather than a setup
    #   set and a consumption set -- two sets is how the setup half came to be the narrower one.
    from carddemo_migration.loaders.aurora import AuroraLoadError, driver_errors
    from carddemo_migration.loaders.protected_columns import ProtectedColumnError
    from carddemo_migration.verify.session import VerificationSessionError

    return (
        AuroraLoadError,
        ProtectedColumnError,
        VerificationSessionError,
        *_DECODE_ERRORS,
        *_SOURCE_READ_ERRORS,
        *_REMOTE_SOURCE_ERRORS,
        *driver_errors(),
    )


def _reported(exc: BaseException) -> str:
    """Render one failure for a retained log, disclosing only what this package authored.

    Purpose
    -------
    Stand between every failure this command line catches and the log line it writes. Two classes of
    failure reach these handlers and only one may be quoted: a refusal this package composed, whose
    wording is already an allow-listed description, and a failure composed by a provider or by the
    operating system, whose text names infrastructure this package must not put in a log.

    Parameters
    ----------
    exc : BaseException
        The failure that was caught.

    Returns
    -------
    str
        For a package-authored refusal, its own message with anything outside printable ASCII
        replaced. For a driver failure, the exception class name and the SQLSTATE if the driver
        published one. For an operating-system failure, the class name and the errno symbol.

    Raises
    ------
    None
        This runs on a failure path, so a failure that publishes none of the attributes read here
        yields a shorter sentence rather than an error.
    """
    # WHY : Assumptions: a DRIVER failure's message is never quoted, and the reason is specific
    #   rather than general caution. psycopg composes a connection failure as `connection to server
    #   at "<endpoint>" (<address>), port <port> failed: ...` -- so quoting it writes the cluster
    #   endpoint and its private address into a retained container log, from a handler whose only
    #   job was to report that a step did not complete. The class name and the SQLSTATE identify the
    #   fault at least as usefully and name no infrastructure.
    # WHY : Assumptions: an OSError's message is not quoted either, for the same reason in a
    #   different namespace: `str(OSError)` from a failed open carries the FILENAME, which for a
    #   staging read is an operator-supplied path and for a work-directory read is an internal one.
    #   The errno symbol -- ENOENT, EACCES, EISDIR -- is what an operator acts on.
    # WHY : Alternatives Considered: sanitising `str(exc)` for control characters and quoting it
    #   anyway. Rejected because sanitising addresses log FORGERY and not disclosure: an endpoint
    #   written in printable ASCII survives it unchanged, which is precisely the text that must not
    #   be written.
    from carddemo_migration.loaders.aurora import driver_errors

    drivers = driver_errors()
    if drivers and isinstance(exc, drivers):
        state = getattr(exc, "sqlstate", None)
        return (
            f"{type(exc).__name__} (SQLSTATE {sanitized_for_log(state)})"
            if state
            else type(exc).__name__
        )
    if isinstance(exc, OSError):
        symbol = errno.errorcode.get(exc.errno or 0, "unknown errno")
        return f"{type(exc).__name__} ({symbol})"
    return sanitized_for_log(exc)


def _verification_errors() -> tuple[type[Exception], ...]:
    """List the refusals a verification pass raises when its own measurement cannot be trusted.

    Purpose
    -------
    Give the four verification commands and the combined gate ONE set, so a pass's refusal reaches
    the same classified exit status whichever verb asked for it. The three per-pass bases cover
    their specialisations too -- the query-identity and result-set-contract refusals all derive from
    them -- so naming the bases is what keeps a newly-added refusal covered by default.

    Parameters
    ----------
    None
        Reads the three verification modules.

    Returns
    -------
    tuple[type[Exception], ...]
        The row-count, checksum and money-parity refusal bases.

    Raises
    ------
    None
    """
    # WHY : Refactoring Rationale: this set exists because two commands did not have it. The
    #   per-dataset `verify-row-counts` and `verify-money-parity` handlers caught only the
    #   operational tuple, so a refusal from their OWN pass -- a count that is not an exact whole
    #   number, a total that is not exact at the money scale -- escaped `main` as a traceback. Both
    #   are precisely the case the guard was added for: the value the driver returned is not the
    #   value the query promised, which means the query is not the query. Reporting that as a
    #   traceback loses the classified status the batch state branches on, and it does so for the
    #   one failure mode that indicates the verification itself cannot be trusted.
    # WHY : Assumptions: the BASES are named rather than the leaf refusals.
    #   `VerificationQueryError`, `MoneyQueryError`, `MoneyResultSetContractError` and
    #   `TimestampContractError` each derive from one of these three, so a leaf list would have to
    #   be extended by hand every time a pass grows a new refusal -- and the failure mode of
    #   forgetting is an uncaught traceback rather than anything a test would notice.
    from carddemo_migration.verify.checksum import ChecksumVerificationError
    from carddemo_migration.verify.money_parity import MoneyParityVerificationError
    from carddemo_migration.verify.row_counts import RowCountVerificationError

    return (ChecksumVerificationError, MoneyParityVerificationError, RowCountVerificationError)


def _layout_identity(identifier: str) -> str:
    """Resolve either accepted ``--dataset`` spelling to the layout name it names.

    Purpose
    -------
    Give every subcommand ONE dataset vocabulary. ``stage-dataset`` is driven by the orchestrator's
    plural snake-case seed tokens while ``decode-record``, ``load-dataset`` and the three
    verification passes are keyed by the upper-case copybook layout names, so this turns whichever
    spelling arrived into the layout name the registries downstream are indexed by.

    Parameters
    ----------
    identifier : str
        The raw ``--dataset`` value. A registered seed token, a layout name, or neither.

    Returns
    -------
    str
        The layout name a seed token maps to, or ``identifier`` unchanged when it is not a
        registered token.

    Raises
    ------
    None
        An unresolvable value is returned untouched rather than refused here.
    """
    # Refactoring Rationale: an unknown value is deliberately passed THROUGH rather than refused
    #   here, and the reason is that the seed registry is a strict SUBSET of what these commands
    #   accept. Ten datasets ship as seed extracts, but fourteen layouts are decodable and eleven
    #   are loadable -- `EXPORT`, `REJECT`, `INTTRAN`, `TRNX` and `DALYTRAN` have no seed token at
    #   all. Refusing here against the ten would therefore reject four decodable layouts that
    #   `decode-record` has always accepted. Each command already refuses against its OWN closed
    #   set, which is the correct set for that command, so this function widens the vocabulary
    #   without narrowing any command's reach.
    #
    # Assumptions: the two spellings cannot collide, which `seed_datasets` asserts at import --
    #   every token is lower-case with underscores and every layout name is upper-case. So a value
    #   that resolves here resolves to exactly one dataset, and mapping it is not a guess.
    try:
        return seed_datasets.layout_name_for(identifier)
    except SeedDatasetError:
        return identifier


def _stage_resolved_extract(
    *,
    client: Any,
    settings: DatasetStagingSettings,
    descriptor: seed_datasets.SeedDataset,
    arguments: argparse.Namespace,
    domain: str,
    retention: int,
    generation: int,
    staging_root: Path,
    source: Path,
    declared_length: int,
) -> int:
    """Stage one already-located extract and report the outcome as an exit status.

    Purpose
    -------
    Hold the staging write and its five failure classifications in one place, so the two ways an
    extract can be LOCATED -- a local directory or an object-store prefix materialised to task-local
    storage -- reach an identical write path and identical diagnostics.

    Parameters
    ----------
    client : Any
        S3 client used for the write and the retention sweep.
    settings : DatasetStagingSettings
        Validated bucket and environment.
    descriptor : seed_datasets.SeedDataset
        The resolved seed dataset, supplying the prefix segment and the token used in diagnostics.
    arguments : argparse.Namespace
        Carries ``business_date`` and the optional ``object_name`` override.
    domain : str
        Owning bounded context for the prefix, already defaulted from the descriptor.
    retention : int
        Generations to keep, already defaulted from the descriptor.
    generation : int
        The reserved generation number.
    staging_root : Path
        The trusted boundary the source is resolved beneath.
    source : Path
        The extract, relative to ``staging_root``.
    declared_length : int
        The layout's declared fixed-record width.

    Returns
    -------
    int
        :data:`EXIT_OK` when the object is staged, :data:`EXIT_FAILED` for a conflict, a provider
        refusal or an unusable extract, and :data:`EXIT_USAGE` for an unacceptable request value.

    Raises
    ------
    None
        Every documented failure becomes a return code, because a batch state branches on one.
    """
    try:
        staged: StagedObject = stage_dataset_file(
            client=client,
            settings=settings,
            domain=domain,
            dataset=descriptor.dataset_segment,
            business_date=arguments.business_date,
            generation=generation,
            source=source,
            staging_root=staging_root,
            object_name=_resolved_object_name(source, None),
            retention_count=retention,
            record_length=declared_length,
        )
    except GenerationConflictError as exc:
        # Assumptions: an immutable-generation conflict is the FAILED tier and is caught before
        #   DatasetSourceError, because a retry will not clear it: the generation already holds
        #   different bytes, and this command never overwrites one. The remedy is a decision --
        #   stage the new extract as the next generation -- so the message states it rather than
        #   leaving the operator to infer it from a status code.
        _LOGGER.error(
            "the extract for %s was not staged: %s",
            sanitized_for_log(descriptor.token),
            _reported(exc),
        )
        return EXIT_FAILED
    except StagingServiceError as exc:
        # Assumptions: a provider failure is the FAILED tier as well, and it is reported through the
        #   staging module's allow-listed metadata rather than the SDK's own text. The distinction
        #   from the conflict above is that this one IS worth retrying, which is why Step Functions
        #   is given a failed step rather than a fatal one.
        _LOGGER.error("the object store refused the staging request: %s", _reported(exc))
        return EXIT_FAILED
    except DatasetSourceError as exc:
        # Assumptions: a source failure is the FAILED tier, not the usage tier, and it is
        #   caught before the ValueError clause below because DatasetSourceError is a
        #   subclass of it -- ordering the two the other way round would silently
        #   reclassify every unreadable extract as a usage error. An absent, symlinked,
        #   non-regular or mid-flight-modified extract is an operational condition of the
        #   step's environment rather than a mistake in what the operator typed.
        _LOGGER.error(
            "the extract %s beneath %s could not be staged: %s",
            sanitized_for_log(source),
            sanitized_for_log(staging_root),
            _reported(exc),
        )
        return EXIT_FAILED
    except ValueError as exc:
        # Assumptions: the staging module raises ValueError for a generation outside
        #   1-9999, an unacceptable object name and an unacceptable retention count --
        #   all of which are caller-supplied, so they are usage errors rather than
        #   operational ones and must not be reported as a failed step.
        _LOGGER.error("the staging request was not acceptable: %s", _reported(exc))
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


def _retention_count(value: str) -> int:
    """Parse ``--retain`` and refuse a value below the baseline's retention contract.

    Purpose
    -------
    Enforce the floor at PARSE time, with argparse's own usage message, rather than deep inside a
    staging call that has already reserved a generation and opened an extract.

    Parameters
    ----------
    value : str
        The argument as typed.

    Returns
    -------
    int
        The retention count, guaranteed to be at least :data:`DEFAULT_RETENTION_COUNT`.

    Raises
    ------
    argparse.ArgumentTypeError
        If the value is not an integer, or is below the floor.
    """
    # WHY : Assumptions: the floor is the baseline's own `LIMIT(5) SCRATCH` operand and not an
    #   arbitrary minimum. Every one of the ten generation-data-group definitions in the baseline --
    #   app/jcl/DEFGDGB.jcl lines 25-57, app/jcl/DEFGDGD.jcl lines 28-76 and app/jcl/DALYREJS.jcl
    #   lines 24-26 -- carries LIMIT(5), so five generations is a contract the migration preserves
    #   rather than a default it chose. A lower value does not retain less; it SCRATCHES
    #   generations the contract says must exist, and versions a retention sweep deletes are gone.
    # WHY : Trade-offs: a value ABOVE the floor is accepted. Retaining more history destroys
    #   nothing and is a real need before a risky cutover, so the check is one-sided by design
    #   rather than an equality that would forbid the safe direction along with the unsafe one.
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError(f"{value!r} is not an integer") from exc
    if parsed < DEFAULT_RETENTION_COUNT:
        raise argparse.ArgumentTypeError(
            f"--retain must be at least {DEFAULT_RETENTION_COUNT}, the baseline's LIMIT(5) SCRATCH"
            f" contract; {parsed} would scratch generations that contract requires to exist"
        )
    return parsed


def _staged_source(layout_name: str) -> str:
    """Resolve a dataset's already-staged generation object, the baseline's ``(0)`` reference.

    Purpose
    -------
    Give the load and verification commands a source they can find WITHOUT being told one, so the
    orchestrator's states need no knowledge of extract file names and no filesystem at all. The
    generation this resolves is the one the staging step wrote, which is what makes the chain read
    what it staged rather than re-reading whatever an operator left in a directory.

    Parameters
    ----------
    layout_name : str
        The record layout, already normalised from either accepted dataset spelling.

    Returns
    -------
    str
        The object-store URI of the newest staged generation's extract.

    Raises
    ------
    SeedDatasetError
        If the layout has no registered seed dataset, so no staging prefix can be derived.
    ConfigurationError
        If the dataset bucket is not configured.
    GenerationDiscoveryError
        If the family holds no generation, which means the staging step has not run.
    StagingServiceError
        If the provider refuses the prefix listing.
    """
    from carddemo_migration.loaders.s3_stage import current_generation, s3_client

    # WHY : Assumptions: this resolves the CURRENT generation and not the one this execution
    #   reserved, which is the baseline's own semantics rather than a convenience. A JCL step that
    #   consumes a generation data group references `(0)`, the newest generation, and never a
    #   generation number it computed; `current_generation` is the resolver written for exactly that
    #   reference and it FAILS on an empty family, so a load whose staging step never ran reports
    #   that instead of reading nothing.
    # WHY : Alternatives Considered: reading the operator-populated inbox prefix instead. Rejected
    #   because the inbox holds bytes this package did not write, so it carries none of the digest
    #   metadata the integrity-checked fetch compares against -- and because a load reading the
    #   inbox would not be reading what was staged, which is the property the retained generation
    #   exists to give. The inbox is still read, once, by the staging step itself.
    # WHY : Assumptions: the object NAME inside the generation prefix is the descriptor's own
    #   `source_object`, because that is what `_resolved_object_name` derives from the extract the
    #   staging step materialised -- an inbox key whose last segment is that same name. Deriving it
    #   from the descriptor rather than listing the prefix keeps this a single HEAD-free composition
    #   and cannot pick up a stray object an operator dropped beside the extract.
    descriptor = seed_datasets.seed_dataset(layout_name)
    settings = resolve_dataset_staging_settings()
    generation = current_generation(
        s3_client(), settings, descriptor.domain, descriptor.dataset_segment
    )
    return seed_datasets.ObjectStoreLocation(
        bucket=settings.bucket, key=f"{generation.prefix}{descriptor.source_object}"
    ).describe()


def _records_for(
    layout_name: str, source: str, encoding: str, work_root: Path
) -> tuple[RecordReader, Any]:
    """Resolve the reader for a record name and open its source in the requested form.

    Purpose
    -------
    Give every load and verification path one way to obtain decoded records, so the choice of
    record and of seed form is made once rather than in each handler and once more in the gate.

    Parameters
    ----------
    layout_name : str
        The record layout, already normalised from either accepted dataset spelling.
    source : str
        A local path, or a key in the object-store scheme, holding the extract.
    encoding : str
        ``ascii`` or ``ebcdic``. Declared, never sniffed.
    work_root : Path
        The invocation-scoped work directory an object-store source is materialised into.

    Returns
    -------
    tuple[RecordReader, Any]
        A reader bound to the named layout, carried for its FIELD METADATA only, and an iterator
        of records decoded by the module that owns that record.

    Raises
    ------
    LayoutError
        If the record name is not a registered layout, if no reader owns the layout, or if the
        layout publishes no entry point for the requested corpus.
    DatasetSourceError
        If an object-store source is absent or its bytes disagree with its recorded identity.
    StagingServiceError
        If the provider refuses the probe or the read of an object-store source.
    """
    # Alternatives Considered: the registry is asked through ``layouts.layout`` rather than indexed
    #   into ``layouts.LAYOUTS``, so an unknown name is refused by the same call, with the same
    #   message and the same exception type, as the ``decode-record`` command already uses. Indexing
    #   the mapping directly would produce a KeyError that had to be re-wrapped, and a second
    #   wording of "that name is not registered" free to drift from the first.
    layout = layouts.layout(layout_name)
    # Refactoring Rationale: the records now come from the OWNING READER MODULE, resolved through
    #   `readers.dataset_reader`, where they used to come from a generic `RecordReader` built here
    #   from the layout. The rationale this replaces claimed the owning reader enforced each
    #   record's policy, and that claim was false of the code beneath it: a factory-built reader
    #   carries the geometry and the suppression contract and NONE of the per-record policy the
    #   twelve modules add. Four contracts were bypassed, and each of them is the kind of defect
    #   that reports success. `card` wraps the card verification value in a `ProtectedValue` so it
    #   cannot be rendered or logged by accident -- the generic reader returned bare text. `usrsec`
    #   refuses a user type outside the `'A'`/`'U'` domain the target's CHECK constraint enforces --
    #   the generic reader passed any character through, so the load failed in the database with a
    #   constraint violation instead of in the reader with a record number. `dalytran` and
    #   `transaction` validate the shape of their processing timestamps -- the generic reader
    #   accepted anything of the right width. And `transaction` treats an ABSENT extract as the
    #   normal state it is for that one record, because no `TRANSACT` dataset ships in either
    #   corpus -- the generic reader reported an open failure for a condition that is not one.
    # Assumptions: the `RecordReader` is still built and still returned, and its purpose is now
    #   narrow and stated: it supplies `layout` and `loaded_fields` to the two handlers that select
    #   money fields and deterministic field names. It decodes nothing on this path. Keeping it is
    #   what leaves the return type and all four call sites unchanged while the records they consume
    #   change provenance, and the factory is the right home for layout-derived field metadata --
    #   that is what it is for.
    reader = RecordReader(layout)
    # Refactoring Rationale: the source is resolved through `_resolved_source` rather than being
    #   `Path(source)` directly, so a `--source` naming an object-store key is fetched to
    #   the invocation's work directory and integrity-checked first. Requiring a local path was a
    #   contract the deployment could not meet: the batch task has no volume mount, so the only
    #   place a staged extract exists is the bucket the staging step wrote it to. A local path still
    #   passes through untouched, which is what keeps the operator procedure over a checkout
    #   working.
    resolved = _resolved_source(source, work_root, layout.name)
    # Alternatives Considered: the encoding is explicit rather than sniffed from the file. The two
    #   forms are distinguishable only by inspecting bytes for characters outside the ASCII range,
    #   and a seed extract whose records happen to be all-ASCII would sniff as text while being an
    #   EBCDIC dataset -- decoding it as text then yields plausible wrong values rather than an
    #   error, which is the failure this whole verification layer exists to catch.
    # Assumptions: the corpus is passed to the resolver and this module still holds NO per-dataset
    #   encoding rule -- notably none forcing `SECUSER` onto the EBCDIC path, even though that
    #   record genuinely ships in one form only and `app/data/ASCII/usrsec.txt` does not exist.
    #   The rule stays the readers' own: `usrsec` publishes no character entry point at all, so
    #   `readers.dataset_reader` refuses that pair by absence rather than by a rule written here,
    #   and the refusal arrives as a `LayoutError` that `_step_errors()` covers, which the handler
    #   converts to a classified non-zero status rather than a traceback.
    # Alternatives Considered: encoding the same rule again here, as a table mapping each layout
    #   to its permitted seed form. Rejected because README.md section 5.2 records that the
    #   seed-extract encodings are a documentation-owned fact with no representation in this
    #   distribution's code; adding one would create a second authority free to disagree with
    #   both section 6.1 and the readers, and the readers are the side that can actually enforce it.
    return reader, dataset_reader(layout.name, encoding)(resolved)


def _resolved_source(raw: str, work_root: Path, layout_name: str) -> Path:
    """Resolve a ``--source`` value to a local path, materialising an object-store source.

    Purpose
    -------
    Let the load and verification commands read the extract the staging step actually wrote, rather
    than requiring a filesystem the batch task does not have. A ``--source`` naming an object-store
    key is fetched to the invocation's work directory and verified against the object's own recorded
    length and digest before a single record is decoded; a ``--source`` naming a local path is
    returned unchanged.

    Parameters
    ----------
    raw : str
        The ``--source`` value: a local path, or a key in the object-store scheme.
    work_root : Path
        The invocation-scoped work directory :func:`main` opened, which a materialised object is
        written into.
    layout_name : str
        The record layout, used to derive the declared fixed-record width the transfer is checked
        against.

    Returns
    -------
    Path
        A local path holding the extract's bytes.

    Raises
    ------
    ConfigurationError
        If a source in the object-store scheme is given but no client can be constructed.
    DatasetSourceError
        If the object is absent, or the transferred bytes disagree with the object's own recorded
        length, recorded digest or the declared record geometry.
    StagingServiceError
        If the provider refuses the probe or the read.
    SeedDatasetError
        If the source names the object-store scheme but no bucket.
    """
    location = seed_datasets.object_store_location(raw)
    if location is None:
        # WHY : Assumptions: a value that is not in the object-store scheme is returned as a PATH
        #   untouched, with no existence check here. The readers open it and report an absent or
        #   unreadable extract themselves, and one of them -- `transaction` -- treats absence as the
        #   normal state it is, because no TRANSACT extract ships in either corpus. A check here
        #   would override that record's own contract with a generic refusal.
        return Path(raw)
    # WHY : Assumptions: the destination name is derived from the KEY's last segment rather than
    #   from the layout or the dataset token, so two sources fetched in one invocation cannot
    #   collide on one filename. The work directory is private to the invocation, so the name only
    #   has to be unique within it.
    leaf = location.key.rsplit("/", 1)[-1] or layout_name
    fetched = fetch_extract(
        _s3_client(),
        bucket=location.bucket,
        key=location.key,
        destination=work_root / leaf,
        record_length=layouts.reclen_of(layout_name),
    )
    _LOGGER.info(
        "materialised %d byte(s) of %s from %s (sha256 %s)",
        fetched.byte_size,
        sanitized_for_log(layout_name),
        sanitized_for_log(location.describe()),
        fetched.sha256,
    )
    return fetched.path


def _stream_rows(connection: Any, statement: str, cursor_name: str) -> Iterator[tuple[Any, ...]]:
    """Read one statement's rows in bounded batches, preferring a server-side cursor.

    Parameters
    ----------
    connection : Any
        An open database connection. Must stay open for as long as the returned iterator is
        consumed.
    statement : str
        The statement to execute. Composed by the caller from quoted identifiers only.
    cursor_name : str
        The name to declare the server-side cursor under. Must be unique among the cursors live in
        the same session.

    Yields
    ------
    tuple[Any, ...]
        One row per yield, in the order the statement asks for.

    Raises
    ------
    None
        A driver failure propagates to the caller, which classifies it.
    """
    # WHY : Refactoring Rationale: the rows are STREAMED, where the read-back used to `fetchall()`
    #   into a list and return it. Two things were wrong with that and they compound. Peak memory
    #   scaled with the table rather than with a batch, and the caller then held every row while
    #   ALSO holding the source side -- so verifying a production-sized extract needed both sides
    #   resident at once. The seed extracts are small enough that the list form worked, which is
    #   exactly why this is worth fixing before a real cutover rather than after one.
    # WHY : Assumptions: a SERVER-SIDE cursor is requested by name and a client-side one is used
    #   when the driver does not offer names. That distinction is the whole benefit: psycopg buffers
    #   the entire result set on `execute` for a client-side cursor, so `fetchmany` over one of
    #   those bounds nothing. The name is the caller's, so two reads live in one session cannot
    #   collide.
    # WHY : Trade-offs: the fallback is entered on TypeError from the constructor rather than by
    #   reading the driver's signature. Inspection reads better and fails worse: `Connection.cursor`
    #   may be a C-level callable whose signature cannot be read, and an inspection that raised
    #   there would disable streaming for the real driver while leaving it on for every double.
    try:
        candidate = connection.cursor(name=cursor_name)
    except TypeError:
        candidate = connection.cursor()
    cursor = candidate.__enter__() if hasattr(candidate, "__enter__") else candidate
    try:
        cursor.execute(statement)
        while True:
            batch = cursor.fetchmany(_READ_BACK_BATCH_ROWS)
            if not batch:
                return
            yield from batch
    finally:
        if hasattr(candidate, "__exit__"):
            candidate.__exit__(None, None, None)


def _sealed_envelopes(connection: Any, target: Any) -> Mapping[str, Iterator[object]]:
    """Offer each sealed column's stored envelopes as its own bounded stream.

    Purpose
    -------
    Supply the measurement :func:`verify.checksum.audit_sealed_columns` needs for the columns the
    digest comparison must exclude, so those columns are certified by presence and framing rather
    than by nothing at all.

    Parameters
    ----------
    connection : Any
        An open database connection. Must stay open for as long as the streams are consumed.
    target : Any
        The table target whose sealing projections name the columns to read.

    Returns
    -------
    Mapping[str, Iterator[object]]
        Column name to a lazy stream of that column's stored values, ``None`` for a null. Empty for
        the nine loadable targets that seal nothing, which issues no statement at all.

    Raises
    ------
    None
        A driver failure propagates from the stream to its consumer, which classifies it.
    """
    # WHY : Assumptions: each column gets its OWN single-column stream rather than one multi-column
    #   read. The audit finishes one column before it starts the next, so at most one cursor is live
    #   and peak memory stays a function of the batch size; a single wide read would have to be
    #   buffered per column to be consumed that way, which is the materialisation this avoids.
    # WHY : Trade-offs: that costs one sequential scan per sealed column -- two for the customer
    #   master, one for the card master, none for the other nine. Accepted because the alternative
    #   is holding every envelope of every column resident, and an envelope is the widest value
    #   either table stores.
    return {
        column: _stream_column(connection, target, column, index)
        for index, column in enumerate(target.sealed_fields().values())
    }


def _stream_column(connection: Any, target: Any, column: str, index: int) -> Iterator[object]:
    """Stream one column's stored values, in the table's own order.

    Parameters
    ----------
    connection : Any
        An open database connection. Must stay open for as long as the iterator is consumed.
    target : Any
        The table target being read.
    column : str
        The column to read. Quoted before composition, never interpolated raw.
    index : int
        The column's position among the sealed columns, used only to keep the cursor name unique.

    Yields
    ------
    object
        One stored value per yield, ``None`` for a null.

    Raises
    ------
    None
        A driver failure propagates to the consumer, which classifies it.
    """
    # WHY : Assumptions: the read is deliberately UNORDERED. The audit counts values and inspects
    #   each one's framing, and neither measurement depends on the order they arrive in -- so
    #   imposing one would buy nothing and would ask the server to sort the widest column in the
    #   table.
    statement = f"SELECT {quote_identifier(column)} FROM {target.qualified_name}"  # noqa: S608
    for row in _stream_rows(connection, statement, f"carddemo_sealed_{target.table}_{index}"):
        yield row[0]


def _verification_connection() -> Any:
    """Open the one certified read-only session a per-dataset verification may run on.

    Purpose
    -------
    Give the three verification handlers an authority that cannot alter what they certify, and
    keep the choice of that authority out of the handlers so none of them can make it differently.

    Parameters
    ----------
    None
        The role is fixed. There is deliberately no parameter selecting an identity: the defect
        this function replaces was precisely a verification that connected as whichever role the
        dataset's schema resolved to.

    Returns
    -------
    Any
        An open connection whose live session has been certified as ``carddemo_verifier`` and set
        read-only by the server. The caller owns closing it.

    Raises
    ------
    VerificationSessionError
        If the resolved settings or the live session name another role, or if the server does not
        confirm the session is read-only.
    ConfigurationError
        Propagated when the driver is absent or a parameter or secret cannot be resolved.
    AuroraLoadError
        Propagated when the driver is present and the connection attempt fails.
    """
    # Refactoring Rationale: the three verification handlers used `_connect_for(target)`,
    #   which authenticates as the bounded context's own service role -- and that role holds
    #   SELECT, INSERT and UPDATE on exactly the rows being certified. A verifier able to write
    #   what it verifies certifies nothing: a defect in one of these handlers could have repaired
    #   the evidence an operator was relying on it to judge, and the load step immediately before
    #   uses the same credential, so a verification "passing" told you nothing that the load had
    #   not already told you. `carddemo_verifier` holds SELECT on the five loaded schemas and no
    #   write privilege anywhere, and its session is set read-only by the server.
    # Assumptions: the LOAD handler keeps `_connect_for`, deliberately. It has to write, so
    #   giving it the read-only identity would break it; the distinction being drawn is between the
    #   step that changes the data and the step that judges it, and each connects as the least
    #   authority its own job needs.
    # Trade-offs: the import is INSIDE the function rather than at module scope. Resolving the
    #   verifier's settings reaches Parameter Store and Secrets Manager through the configuration
    #   module, so a module-scope import would make every unrelated verb -- help, the dataset
    #   listings, staging -- depend on the verification session module resolving cleanly.
    from carddemo_migration.verify.session import open_verification_connection

    return open_verification_connection()


def _gate_extracts(source_root: str) -> tuple[str, ...]:
    """Return the registered dataset tokens the combined gate covers, in registry order.

    Purpose
    -------
    Name the closed set of datasets the gate reads, from the registry rather than from an argument,
    so an operator cannot narrow the gate by naming fewer datasets and still receive a verdict.

    Parameters
    ----------
    source_root : str
        The configured root, checked here only for being non-empty so the refusal names the setting
        rather than surfacing later as a per-dataset resolution failure.

    Returns
    -------
    tuple[str, ...]
        Every registered seed-dataset token whose layout ships a committed extract, in declaration
        order, which is the baseline's own load sequence.

    Raises
    ------
    SeedDatasetError
        If ``source_root`` is empty.
    """
    # WHY : Assumptions: the coverage is the whole registry MINUS the layouts that ship no committed
    #   extract, and it is deliberately not selectable by the caller. The gate exists to answer one
    #   question about one load, and a `--dataset` option on it would let a caller receive the word
    #   "verified" over a subset -- which is the precise shape of false assurance the three-pass
    #   mandate exists to remove. Narrowing belongs to the four per-dataset verbs, which report on
    #   exactly what they were asked about and claim nothing more.
    # WHY : Refactoring Rationale: the unseeded filter is applied here, and it was ADDED after the
    #   first draft covered every registered token unconditionally. That draft failed on
    #   measurement rather than in review: the `transactions` token stages `DALYTRAN.PS.INIT`, the
    #   single 350-byte record `app/jcl/TRANFILE.jcl` primes the TRANSACT cluster from, and that
    #   record's unpopulated category code is four NUL bytes -- so READING it as a whole transaction
    #   raises a zoned-decimal error and the gate died at its second dataset. Two independent
    #   authorities already said so: `readers/transaction.py` declares no committed seed dataset,
    #   and `verify/row_counts.py` names TRAN the unseeded layout and requires `ledger.transactions`
    #   to hold ZERO rows after the ETL, because posting is what fills it.
    # WHY : Assumptions: the filter reads `readers.ships_committed_extract` rather than naming TRAN,
    #   so this decision and the money pass's own required-extract decision cannot disagree. Naming
    #   the layout here would be a third copy of a rule two modules already publish.
    if not source_root:
        raise SeedDatasetError(
            f"{seed_datasets.STAGING_ROOT_VARIABLE} is not set and --source-root was not given, so"
            " the gate has no extracts to verify against"
        )
    return tuple(
        token
        for token in seed_datasets.seed_dataset_tokens()
        if ships_committed_extract(seed_datasets.seed_dataset(token).layout_name)
    )


def _has_money_columns(layout_name: str) -> bool:
    """Report whether the committed money-total inventory declares a column fed by this layout.

    Parameters
    ----------
    layout_name : str
        A record layout name.

    Returns
    -------
    bool
        True when at least one declared money column reads its value from that layout.

    Raises
    ------
    MoneyParityVerificationError
        Propagated if the derived inventory is not the declared nine columns over five tables.
    """
    # WHY : Assumptions: the answer is DERIVED from the money pass's own declared inventory rather
    #   than from a list of layout names written here. `SourceExtract` refuses a layout that feeds
    #   no money column, so a hard-coded list out of step with the inventory would make the gate
    #   refuse its own extracts -- and the inventory is the side that can change, because it is
    #   derived from the loader's column declarations.
    from carddemo_migration.verify.money_parity import declared_money_columns

    return any(column.layout_name == layout_name for column in declared_money_columns().values())


def _gate_source(descriptor: seed_datasets.SeedDataset, source_root: str) -> str:
    """Resolve one descriptor's extract to a value ``_records_for`` accepts.

    Parameters
    ----------
    descriptor : seed_datasets.SeedDataset
        The dataset whose extract is being located.
    source_root : str
        A local directory or an object-store prefix.

    Returns
    -------
    str
        An absolute local path, or a key in the object-store scheme.

    Raises
    ------
    SeedDatasetError
        If the root is empty or names the object-store scheme with no bucket.
    """
    # WHY : Assumptions: the local branch JOINS the root to the file name here, unlike the staging
    #   command which passes the two separately. Staging keeps them apart because it opens the file
    #   through a component-by-component descent that refuses a symbolic link and needs to know
    #   which prefix is the trusted boundary; the verification passes only read, and they read
    #   through the readers' own hardened open, so a joined path loses nothing here.
    location = seed_datasets.extract_location(descriptor, source_root)
    if isinstance(location, seed_datasets.ObjectStoreLocation):
        return location.describe()
    return str(Path(source_root) / location)


if __name__ == "__main__":
    # WHY : Refactoring Rationale: this guard is the LAST statement in the module, and the
    #   position is load-bearing rather than stylistic. `python -m` executes the module body
    #   top-to-bottom as __main__, so `main()` runs at the point the guard sits and every name
    #   bound BELOW it is still undefined. While the guard sat above the combined verification
    #   gate, `build_parser` reached `_layout_identity` and `main` reached `_EXTRACT_WORK_PREFIX`
    #   before either was bound, so the documented invocation raised NameError and every
    #   subcommand became unreachable -- while `carddemo-migrate`, which imports the module and
    #   therefore binds the whole body first, kept working. Two published entry points that
    #   disagree is the failure this ordering removes; appending anything after this block
    #   reintroduces it.
    # Assumptions: `python -m carddemo_migration.cli` runs this module as __main__, so
    #   this guard is what makes the documented invocation and the container ENTRYPOINT
    #   work. sys.exit is passed the integer directly so the process status is the
    #   return code rather than a truthiness conversion of it.
    # Refactoring Rationale: the entry point is this guard on THIS module, and no
    #   `__main__.py` is added to the package. pyproject.toml publishes
    #   `python -m carddemo_migration.cli` and `carddemo-migrate = carddemo_migration.cli:main`,
    #   and a package-level `__main__.py` would make `python -m carddemo_migration` a THIRD,
    #   undocumented spelling -- one the runbooks, this package's README and the batch state
    #   machine's container overrides never issue. Two entry points into one CLI drift: the
    #   unpublished one acquires no argument handling and no exit mapping, and an operator who
    #   found it would get argparse's bare usage error where the published form reports a
    #   classified status.
    sys.exit(main())
