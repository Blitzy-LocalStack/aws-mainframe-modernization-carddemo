"""Stage CardDemo dataset generations into versioned object storage.

Purpose
-------
Reproduce the baseline's generation-data-group behaviour on object storage. A generation is
written beneath ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/``, a baseline ``(+1)`` reference
becomes a NEW generation prefix, a ``(0)`` reference resolves to the newest existing one, and
the newest configured number of logical generations is kept while older ones are scratched.

This module owns four things that deliberately live nowhere else in the package:

* **The inventory of generation families.** There are TEN, and the count is the single easiest
  thing in this migration to get wrong; see :data:`GENERATION_FAMILIES`.
* **Discovery of the current and next generation number**, read from the prefixes that exist
  in the bucket rather than from any counter this process holds.
* **Verbatim transfer of a dataset's bytes**, in both directions -- out to a generation prefix and
  in from an object-storage landing prefix -- opened in binary and never decoded, transcoded,
  re-encoded, padded, stripped or newline-translated on the way through. Refactoring Rationale: the
  inbound direction was added because the task that stages these datasets runs on Fargate, which
  has no operator filesystem to read an extract from; see :func:`fetch_object_to_path`.
* **The logical-generation retention count**, which bucket lifecycle configuration cannot
  express; see the retention note below.

Two retention layers, and they are not interchangeable
------------------------------------------------------
Assumptions: ``infra/modules/s3-datasets`` provisions the bucket, its ten dataset prefixes,
bucket versioning and the noncurrent-version lifecycle rule, and nothing here creates,
versions, configures or sets a lifecycle on any of it -- doing so would give the bucket two
sources of truth that could disagree while both appeared to work. That module's
``noncurrent_version_retention`` variable defaults to 5, which is the ``LIMIT(5)`` analogue
for repeated writes of ONE key.

It cannot be the analogue for the generations themselves. A lifecycle rule counts versions of
a single key, whereas ``gen=0001/`` and ``gen=0002/`` are separate keys rather than revisions
of one, so nothing in a bucket configuration can keep "the newest five generations". That
count is therefore enforced here, by ordering prefixes on business date then generation number
and permanently deleting every object version and delete marker beneath the prefixes that roll
off. With the count at five, staging a sixth logical generation scratches the oldest complete
prefix, which is what ``LIMIT(5) SCRATCH`` did.

Assumptions: the retention count is supplied by the caller and defaults to
:data:`DEFAULT_GENERATION_RETENTION`, which mirrors that Terraform variable's own default. An
earlier revision of this module cited a Terraform output named
``generation_retention_by_family``; ``infra/modules/s3-datasets/outputs.tf`` publishes no such
output -- its outputs are the bucket name and ARN, the dataset prefixes and URIs, the
non-generation prefixes and URIs, ``noncurrent_version_retention``, and the audit-trail
values. The citation is corrected here rather than dropped, because a reader who had learned
the old name needs to be told which value actually carries the number.

What this module deliberately does not do
-----------------------------------------
* It does not decode. Not per record, not per field, not at all. Record-boundary and
  sign-overpunch interpretation belongs to ``carddemo_migration.copybook`` and the readers;
  a staged object is a byte-for-byte copy of the extract it came from.
* It does not read a clock. Every ``dt=`` value arrives as a parameter; see
  :func:`parse_business_date`.
* It does not construct an AWS client from a literal endpoint, region or credential; see
  :func:`s3_client`.
* It touches no money value, so no fixed-point-versus-float question arises in it, and none
  may be introduced.
* It imports no database driver. ``psycopg`` belongs solely to
  ``carddemo_migration.loaders.aurora``.
* It performs no work at import time -- no client, no environment read, no logging
  configuration -- because ``carddemo_migration.cli`` imports this module lazily inside its
  handlers so that ``--help`` and the copybook-only subcommands need no AWS SDK at all.
"""

from __future__ import annotations

import base64
import hashlib
import io
import os
import re
import stat
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from types import MappingProxyType
from typing import IO, Any, Final, Protocol

from carddemo_migration import config
from carddemo_migration.config import DatasetStagingSettings
from carddemo_migration.copybook import layouts

__all__ = [
    "DEFAULT_GENERATION_RETENTION",
    "GENERATION_DIGITS",
    "GENERATION_FAMILIES",
    "MAX_GENERATION",
    "MIN_GENERATION",
    "PSEUDO_FILESYSTEM_ROOTS",
    "DatasetSourceError",
    "GenerationConflictError",
    "GenerationDiscoveryError",
    "GenerationFamily",
    "GenerationPrefix",
    "GenerationRetentionError",
    "FetchedExtract",
    "S3StagingClient",
    "StagedObject",
    "StagingServiceError",
    "delete_generation_prefix",
    "family",
    "family_names",
    "fetch_object_to_path",
    "fetch_extract",
    "current_generation",
    "extract_source_key",
    "family_prefix",
    "fetch_dataset_extract",
    "parse_object_uri",
    "latest_generation",
    "list_generation_prefixes",
    "next_generation",
    "parse_business_date",
    "prune_generations",
    "reserve_generation",
    "s3_client",
    "sanitized_for_log",
    "stage_dataset_file",
    "stage_family_file",
]

#: Maximum number of keys one ``DeleteObjects`` request accepts. The batching in
#: :func:`delete_generation_prefix` exists because of this service limit.
_MAX_DELETE_OBJECTS: Final[int] = 1000

#: Scheme that marks a location as an object-storage URI rather than a filesystem path; see
#: :func:`parse_object_uri`.
_OBJECT_URI_SCHEME: Final[str] = "s3://"

#: Trailing shape of a valid generation prefix, anchored so that only the fixed
#: ``dt=YYYY-MM-DD/gen=NNNN/`` convention matches and any other child path is ignored.
_GENERATION_SUFFIX: Final[re.Pattern[str]] = re.compile(r"dt=(\d{4}-\d{2}-\d{2})/gen=(\d{4})/\Z")

#: Number of digits the ``gen=`` component carries, always zero-padded. Stated here so the
#: discovery and validation in this module agree with the prefix builder in
#: ``carddemo_migration.config``, which renders the same width.
GENERATION_DIGITS: Final[int] = 4

#: Lowest generation number a staged prefix may carry. One rather than zero, because the
#: baseline's first relative reference is ``(+1)`` and its first catalogued generation is
#: ``G0001V00``; ``gen=0000`` would have no baseline counterpart.
MIN_GENERATION: Final[int] = 1

#: Highest generation number the four-digit component can express.
MAX_GENERATION: Final[int] = 10**GENERATION_DIGITS - 1

#: Default number of newest logical generations to retain, mirroring the ``LIMIT(5)`` every one
#: of the ten baseline definitions declares and the default of
#: ``infra/modules/s3-datasets``'s ``noncurrent_version_retention`` variable.
DEFAULT_GENERATION_RETENTION: Final[int] = 5

#: The one accepted spelling of a business date. Anchored to four digits, two and two, so that
#: the alternative ISO forms :meth:`datetime.date.fromisoformat` accepts on Python 3.11 and
#: later are refused; see :func:`parse_business_date`.
_BUSINESS_DATE_PATTERN: Final[re.Pattern[str]] = re.compile(r"\A\d{4}-\d{2}-\d{2}\Z")

#: Content type recorded on every staged object. Deliberately the opaque binary type for both
#: the EBCDIC and the ASCII datasets; see :func:`stage_dataset_file`.
_STAGED_CONTENT_TYPE: Final[str] = "application/octet-stream"

#: Read size for the digest pass. Large enough that the 250 000-byte export extract is covered
#: in four reads, small enough that memory does not scale with the dataset.
_DIGEST_CHUNK_BYTES: Final[int] = 65536

#: Object-metadata key carrying the staged payload's hex SHA-256.
_SHA256_METADATA_KEY: Final[str] = "carddemo-sha256"

#: Object-metadata key carrying the staged payload's exact byte length.
_BYTE_SIZE_METADATA_KEY: Final[str] = "carddemo-byte-size"

#: Mount points whose contents are generated by the kernel rather than stored, and which a
#: dataset extract therefore never legitimately lives under.
#:
#: Assumptions: the deny-list is by MOUNT POINT rather than by file name, because the exposure is
#: not one file. ``/proc/self/environ`` publishes the task's whole environment -- including every
#: injected credential -- and it is a regular file of zero declared length that reads as a stream,
#: so a staging step that accepted it would upload the process's secrets into a bucket. Its
#: siblings are no safer: ``/proc/self/cmdline``, ``/proc/self/mem``, and anything under ``/sys``
#: or ``/dev``.
PSEUDO_FILESYSTEM_ROOTS: Final[tuple[str, ...]] = ("/proc", "/sys", "/dev", "/run")

#: Characters a staged leaf name and any externally-supplied path text may carry when it is
#: logged: printable ASCII, excluding the control range at both ends.
#:
#: Assumptions: this is a LOG-SAFETY boundary, not a filesystem one. A newline, a carriage return
#: or an ANSI escape inside a name lets that name forge an additional line in a retained log --
#: CWE-117 -- and the log is what an incident is reconstructed from. The alphabet is the same one
#: ``credentials.py`` admits for a password, and for the same reason: it is the range that survives
#: every transport in this pipeline unchanged.
_LOGGABLE_TEXT: Final[re.Pattern[str]] = re.compile(r"\A[\x20-\x7e]*\Z")

#: What one unsafe character becomes when a name is rendered for a diagnostic.
_REPLACEMENT_CHARACTER: Final[str] = "?"

#: Service error codes meaning "the object is not there", which is the ordinary answer to the
#: existence probe on a first staging attempt rather than a failure.
#:
#: Assumptions: both spellings are admitted. ``HeadObject`` reports a missing key as ``404`` with
#: no code on some paths and as ``NoSuchKey`` on others depending on the caller's permissions, and
#: treating one of them as a failure would turn every first attempt into a refused staging step.
_ABSENT_OBJECT_CODES: Final[frozenset[str]] = frozenset({"404", "NoSuchKey", "NotFound"})


# WHY : Assumptions: every check in this module RAISES one of the three typed errors below, and
#   there is no ``assert`` anywhere in it. That is a correctness requirement rather than a style
#   preference: the interpreter strips ``assert`` statements entirely under ``-O``, and the
#   container image that runs the staging step is free to set ``PYTHONOPTIMIZE``, so an assertion
#   is not a validation -- it is a validation that disappears in exactly the environment where a
#   bad generation number or an absent extract does real damage. Each class subclasses a standard
#   library error so that a caller's existing ``except`` clause keeps working; the base is
#   ``ValueError`` because these report an unusable supplied value, which is the same choice the
#   sibling copybook errors make.
class GenerationRetentionError(ValueError):
    """Report an unsafe staging or generation-cleanup operation.

    Purpose
    -------
    Give every caller-supplied staging fault one type to catch: an unusable generation number,
    object name or retention count, and a cleanup the service refused to complete.
    """

    # WHY : Refactoring Rationale: this derived from ``RuntimeError`` and now derives from
    #   ``ValueError``. The old base was not merely unidiomatic, it broke a live caller:
    #   ``carddemo_migration.cli`` guards its staging call with ``except ValueError`` and its
    #   own comment states that this module raises ``ValueError`` for a generation outside
    #   1-9999, an unacceptable object name and an unacceptable retention count. Under
    #   ``RuntimeError`` none of those was caught, so a mistyped ``--object-name`` escaped the
    #   handler as an uncaught exception instead of returning the usage exit code the command
    #   documents. ``ValueError`` is also the base the sibling copybook errors already use --
    #   ``LayoutError``, ``RecordLengthError``, ``ZonedDecimalError``, ``PackedDecimalError``
    #   -- so a caller catching ``ValueError`` around a decode-then-stage sequence now gets
    #   uniform behaviour. Nothing that caught this class by name is affected.


class DatasetSourceError(GenerationRetentionError):
    """Report a local extract that cannot be staged.

    Purpose
    -------
    Distinguish a fault in the bytes the caller offered -- an absent path, a directory, an
    unreadable file, or a length that contradicts the family's declared record length -- from
    a fault in the generation bookkeeping, so a caller can react to the two differently while
    a caller that does not care still catches the shared base.
    """


class GenerationDiscoveryError(GenerationRetentionError):
    """Report that the next generation number cannot be determined.

    Purpose
    -------
    Signal that discovery ran but produced no usable answer. Two cases reach it, and raising is
    the whole point in both. A family whose four-digit generation space is exhausted has no next
    number: silently reusing :data:`MAX_GENERATION` would overwrite the newest good generation
    with the next run's output. And a family holding NO generation has no current one:
    :func:`current_generation` raises rather than answering with nothing, because a consuming
    step that received nothing would go on to read an empty dataset and report a successful run
    over zero records.
    """


class StagingServiceError(GenerationRetentionError):
    """Report that the object store refused or failed a staging request.

    Purpose
    -------
    Give a provider or transport failure one named, sanitized type, distinct from a fault in the
    extract and from a fault in the generation bookkeeping. Before this class existed, the SDK's
    own exception escaped the staging path unchanged: it reached the command as a traceback whose
    text carries the bucket, the endpoint, the assumed role's ARN and the request identifier, and
    which a container log then retains.

    Assumptions: an instance carries ALLOW-LISTED metadata only -- the operation, the bucket, the
    key, the exception class name and the service's own error code and HTTP status -- and is always
    raised with the provider exception suppressed, so nothing that logs ``exc_info`` can recover
    the original text through ``__cause__``.
    """


class GenerationConflictError(GenerationRetentionError):
    """Report that a generation already holds different bytes and may not be rewritten.

    Purpose
    -------
    Name the one staging outcome that is neither a success nor a transient failure: the key exists,
    it holds a payload whose digest differs from the one this attempt measured, and a generation is
    a point-in-time record rather than a mutable slot. Retrying will not help and overwriting would
    destroy the evidence a verification pass compares against.
    """


def sanitized_for_log(text: object) -> str:
    """Render external text so it cannot forge a line in a retained log.

    Purpose
    -------
    Make every path, leaf name and prefix this module names in a message safe to write to a
    container log, by replacing anything outside printable ASCII with a single visible marker.

    Parameters
    ----------
    text : object
        The value to render. Accepted as ``object`` rather than ``str`` because the callers pass
        paths, and a path renders through :func:`str` rather than being one.

    Returns
    -------
    str
        The rendering, with every character outside the printable ASCII range replaced. The
        length is preserved, so a truncation is still visible as one.

    Raises
    ------
    None
        A failure path must not fail: this function is called from diagnostics.
    """
    rendered = str(text)
    if _LOGGABLE_TEXT.fullmatch(rendered):
        return rendered
    # WHY : Alternatives Considered: escaping with `repr` or percent-encoding, either of which
    #   would preserve the original bytes. Rejected because preserving them is the wrong goal
    #   here: the name is being shown to a human reading a log, and a reversible encoding of a
    #   forged newline still gives an attacker a way to make the log say something. One visible
    #   marker per unsafe character says exactly as much as an operator needs -- "this name
    #   carried something it should not" -- and can say nothing else.
    return "".join(
        character if _LOGGABLE_TEXT.fullmatch(character) else _REPLACEMENT_CHARACTER
        for character in rendered
    )


class _Paginator(Protocol):
    """Describe the boto3 paginator surface used by this module.

    Purpose
    -------
    Name the one method this module calls on a paginator, so that the client protocol below can
    state what :meth:`S3StagingClient.get_paginator` is expected to return without depending on
    the SDK's own generated paginator classes.
    """

    def paginate(self, **kwargs: Any) -> Any:
        """Yield service response pages for the supplied operation arguments.

        Purpose
        -------
        Iterate a listing operation's pages so a caller never has to carry a continuation
        token.

        Parameters
        ----------
        **kwargs : Any
            Operation arguments passed through to the service, such as ``Bucket``, ``Prefix``
            and ``Delimiter``.

        Returns
        -------
        Any
            An iterable of response pages, each a mapping. Typed loosely because the SDK builds
            its response shapes at run time from service models, so no importable static type
            exists to annotate.

        Raises
        ------
        botocore.exceptions.ClientError
            Raised by a real client if the service refuses the listing, for example when the
            bucket does not exist or the caller lacks permission.
        """


class S3StagingClient(Protocol):
    """Describe the small S3 client surface required for staging and cleanup.

    Purpose
    -------
    State the three operations this module needs, so a caller can supply either a real boto3
    client or a narrow test double without either one having to model the rest of the service.

    Assumptions: naming only three operations is what keeps the module testable without a
    network, and it also bounds the IAM policy the staging task needs -- a reader can see from
    this protocol that listing, writing and version-deletion are the whole permission set.
    """

    def get_paginator(self, operation_name: str) -> _Paginator:
        """Return a paginator for a listing operation.

        Purpose
        -------
        Obtain the paged form of a listing call, which is the only form this module uses because
        a family's prefixes and a prefix's object versions can both exceed one response page.

        Parameters
        ----------
        operation_name : str
            The operation to paginate. This module passes only ``list_objects_v2`` and
            ``list_object_versions``.

        Returns
        -------
        _Paginator
            A paginator bound to that operation.

        Raises
        ------
        botocore.exceptions.OperationNotPageableError
            Raised by a real client if the named operation has no paginator.
        """

    def put_object(self, **kwargs: Any) -> Any:
        """Write one staged generation object.

        Purpose
        -------
        Transfer a dataset's bytes to one key. This module supplies ``Body`` as either a bytes
        object or an open binary file handle, and never as text.

        Parameters
        ----------
        **kwargs : Any
            Request arguments. This module supplies ``Bucket``, ``Key``, ``Body`` and
            ``ContentType`` always; ``ContentLength``, ``ChecksumSHA256`` and ``Metadata`` when
            staging from a file; and ``IfNoneMatch`` when claiming a generation, where the
            conditional create is the whole point of the call.

        Returns
        -------
        Any
            The service response mapping. This module does not read it, because a non-error
            return is the whole signal it needs.

        Raises
        ------
        botocore.exceptions.ClientError
            Raised by a real client if the service refuses the write. A refused conditional
            create carries a code in :data:`_CLAIM_CONFLICT_CODES` and is an expected outcome
            of generation allocation rather than a failure.
        """

    def head_object(self, **kwargs: Any) -> Any:
        """Read one object's metadata without reading its body.

        Purpose
        -------
        Learn whether a generation key already exists and, if it does, what digest the staging
        step that wrote it recorded -- so a retry can tell "the same bytes are already there"
        from "different bytes are already there" without transferring anything.

        Parameters
        ----------
        **kwargs : Any
            Request arguments. This module supplies ``Bucket`` and ``Key`` only.

        Returns
        -------
        Any
            The service response mapping. This module reads ``Metadata`` and ``ContentLength``
            and nothing else.

        Raises
        ------
        botocore.exceptions.ClientError
            Raised by a real client if the object does not exist, which is the ordinary case for
            a first attempt and is recognised by its error code rather than by type.
        """

    def get_object(self, **kwargs: Any) -> Any:
        """Read one object's body, for a generation claim record or for a delivered extract.

        Purpose
        -------
        Serve the module's two reads: the execution token stored in a claim, so a retried
        staging step can recognise the claim its own first attempt created, and the bytes of a
        source extract, so a task with no operator filesystem can stage and load one.
        :func:`fetch_object_to_path`, :func:`fetch_dataset_extract` and :func:`fetch_extract` all
        stream an extract to a local path before it is staged and decoded; the digest this package
        published with the object is additionally checked where it carries one.

        Parameters
        ----------
        **kwargs : Any
            Request arguments. This module supplies ``Bucket`` and ``Key`` always, and
            ``ChecksumMode`` when reading an extract so the service returns whatever checksum
            the object was written with.

        Returns
        -------
        Any
            The service response mapping. A claim read consumes ``Body`` whole; an extract read
            consumes ``Body`` in bounded chunks and additionally reads ``ContentLength``, and
            ``ChecksumSHA256`` and ``Metadata`` where the object carries them. Refactoring
            Rationale: this description previously said the bodies read here are claim tokens of
            a few tens of bytes and never dataset content, which the extract fetches made
            untrue -- an extract body is dataset content and is measured in tens of kilobytes. It
            is read in bounded chunks rather than in one call for exactly that reason, so the peak
            memory of a fetch does not scale with the extract.

        Raises
        ------
        botocore.exceptions.ClientError
            Raised by a real client if the object cannot be read.

        Notes
        -----
        Assumptions: the extract read consumes ``Body`` in bounded chunks rather than calling
        ``read()`` with no argument, because the extracts run to hundreds of kilobytes each and a
        whole-body read would make peak memory scale with the dataset. The claim read is a few
        tens of bytes and goes through the same loop, so there is one path and not two.
        """

    def delete_objects(self, **kwargs: Any) -> Any:
        """Permanently delete explicitly named object versions or delete markers.

        Purpose
        -------
        Remove up to :data:`_MAX_DELETE_OBJECTS` specific versions in one request, which is how
        a rolled-off generation is scratched on a versioned bucket.

        Parameters
        ----------
        **kwargs : Any
            Request arguments. This module supplies ``Bucket`` and a ``Delete`` mapping holding
            ``Objects`` -- each a ``Key`` and ``VersionId`` pair -- and ``Quiet``.

        Returns
        -------
        Any
            The service response mapping, whose ``Errors`` member this module inspects because
            the operation reports per-object failures rather than raising for them.

        Raises
        ------
        botocore.exceptions.ClientError
            Raised by a real client if the service refuses the whole request. A failure
            affecting only some keys is reported in the response instead.
        """


def _provider_failure(operation: str, bucket: str, key: str, exc: BaseException) -> str:
    """Describe a provider failure using allow-listed metadata and none of its message text.

    Purpose
    -------
    Produce the one sentence a refused or failed service call is reported with, carrying enough to
    act on -- what was attempted, against which bucket and key, with which exception class, service
    error code and HTTP status -- and carrying no part of the provider's own text.

    Parameters
    ----------
    operation : str
        What was being attempted, in words an operator reads: ``the staged write``, ``the
        existence probe``, ``the generation listing``, ``the generation scratch``.
    bucket : str
        The bucket the request named.
    key : str
        The key or prefix the request named. Rendered log-safe.
    exc : BaseException
        The failure. Only its class name and its allow-listed diagnostic attributes are read.

    Returns
    -------
    str
        The sanitized description.

    Raises
    ------
    None
        This is called on a failure path and must not fail: a provider that publishes none of the
        allow-listed attributes yields a shorter sentence rather than an error.
    """
    # WHY : Assumptions: the provider's own message is NOT read, and that is the whole purpose
    #   rather than an incidental omission. A botocore `ClientError` renders as "An error occurred
    #   (AccessDenied) when calling the PutObject operation: User:
    #   arn:aws:sts::<account>:assumed-role/<role>/<task-id> is not authorized ...", so
    #   interpolating `str(exc)` -- which every call site in this module used to do -- publishes the
    #   account identifier, the task role and the task identifier into a retained log. An endpoint
    #   or connection error carries the resolved endpoint host for the same reason.
    # WHY : Assumptions: the CLASS NAME and the service ERROR CODE are included and are safe to
    #   include. Both are fixed identifiers chosen by the provider -- `AccessDenied`,
    #   `NoSuchBucket`, `EndpointConnectionError` -- so neither can carry request content, and
    #   between them they are the most useful thing an operator can be told about a failure whose
    #   text is withheld.
    code = config.error_code(exc)
    response = getattr(exc, "response", None)
    status: object = None
    if isinstance(response, Mapping):
        metadata = response.get("ResponseMetadata")
        if isinstance(metadata, Mapping):
            status = metadata.get("HTTPStatusCode")
    named = ", ".join(
        f"{label}={value}"
        for label, value in (("code", code), ("http", status))
        if value not in (None, "")
    )
    detail = f" [{named}]" if named else ""
    # WHY : Assumptions: the bucket and key are named as LABELLED FIELDS rather than rendered as a
    #   URI. A URI form reads more naturally, and it would put a scheme literal in this module --
    #   which `test_the_staging_module_constructs_no_literal_endpoint` forbids, because a hard-coded
    #   scheme or host is how a client ends up addressing something other than the configured
    #   endpoint. The labels carry the same two facts and cannot be mistaken for an address.
    return (
        f"{operation} for bucket {sanitized_for_log(bucket)} key {sanitized_for_log(key)} failed:"
        f" {type(exc).__name__}{detail}"
    )


def _paginate(
    client: S3StagingClient,
    operation_name: str,
    purpose: str,
    **request: object,
) -> Iterator[Mapping[str, Any]]:
    """Iterate a paginated listing, translating any provider failure into a sanitized error.

    Purpose
    -------
    Put every listing call in this module behind one translation, so a refused or failed listing
    reports allow-listed metadata instead of escaping as the SDK's own exception -- whose text
    carries the bucket, the endpoint, the assumed-role ARN and the request identifier.

    Parameters
    ----------
    client : S3StagingClient
        S3 client the paginator is obtained from.
    operation_name : str
        ``list_objects_v2`` or ``list_object_versions``.
    purpose : str
        What the listing is for, in words an operator reads, named in a failure.
    **request : object
        The paginator's request arguments. ``Bucket`` and ``Prefix`` are read for the diagnostic.

    Yields
    ------
    Mapping[str, Any]
        Each response page, in the order the service returns them.

    Raises
    ------
    StagingServiceError
        If the paginator cannot be obtained or a page cannot be fetched.
    """
    bucket = str(request.get("Bucket", ""))
    prefix = str(request.get("Prefix", ""))
    try:
        pages = iter(client.get_paginator(operation_name).paginate(**request))
    except Exception as exc:  # noqa: BLE001 - re-raised as one sanitized staging failure
        raise StagingServiceError(_provider_failure(purpose, bucket, prefix, exc)) from None
    # WHY : Assumptions: only `next` is guarded, and the `yield` sits OUTSIDE every `try`. That
    #   placement is load-bearing: a consumer that raises inside its own `for` body closes this
    #   generator, which throws `GeneratorExit` at the yield -- and if the yield were inside the
    #   `except Exception` above, the consumer's own domain error would be swallowed and re-reported
    #   as a provider failure. Guarding the fetch alone keeps the two kinds of failure separate.
    while True:
        try:
            page = next(pages)
        except StopIteration:
            return
        except Exception as exc:  # noqa: BLE001 - re-raised as one sanitized staging failure
            raise StagingServiceError(_provider_failure(purpose, bucket, prefix, exc)) from None
        yield page


@dataclass(frozen=True, order=True, slots=True)
class GenerationPrefix:
    """One validated logical generation prefix in chronological sort order.

    Purpose
    -------
    Carry a discovered generation as a comparable value, so that ordering a family's
    generations is a sort rather than a string comparison on the prefix.

    Parameters
    ----------
    business_date : date
        Business date encoded in the ``dt=`` segment.
    generation : int
        Four-digit generation number encoded in the ``gen=`` segment.
    prefix : str
        Complete prefix ending in ``/``.

    Returns
    -------
    GenerationPrefix
        A frozen, ordered instance.

    Raises
    ------
    None
        The parser that builds these validates the segments before construction.

    Attributes
    ----------
    business_date : date
        As the parameter of the same name.
    generation : int
        As the parameter of the same name.
    prefix : str
        As the parameter of the same name.
    """

    # WHY : Assumptions: field ORDER is load-bearing, because ``order=True`` derives the
    #   comparison from it. Business date must precede generation so that a family sorts the
    #   way the baseline catalogue did -- every generation of an earlier date before any
    #   generation of a later one -- and ``prefix`` sits last so it never influences the
    #   ordering. Sorting on the prefix string instead would happen to work only while the
    #   date stays ISO-ordered and the generation stays zero-padded, and would break silently
    #   the first time either changed.
    business_date: date
    generation: int
    prefix: str


@dataclass(frozen=True, slots=True)
class StagedObject:
    """Describe a staged extract together with its audit anchors.

    Purpose
    -------
    Report what :func:`stage_dataset_file` transferred, including the two values that make a
    later readback check authoritative rather than self-reported: the exact byte length and
    the SHA-256 of the bytes as they were read from disk.

    Parameters
    ----------
    key : str
        Complete object key written to S3.
    prefix : str
        Generation prefix the object was written beneath, ending in ``/``.
    business_date : date
        Business date encoded into the prefix.
    generation : int
        Generation number encoded into the prefix.
    byte_size : int
        Exact number of bytes read from the source extract and sent to S3.
    sha256 : str
        Lower-case hex SHA-256 of those same bytes.
    deleted_generation_prefixes : tuple[str, ...]
        Old logical generation prefixes permanently deleted after the write.

    Returns
    -------
    StagedObject
        A frozen instance.

    Raises
    ------
    None
        Every field is supplied by the staging function that already validated it.

    Attributes
    ----------
    key : str
        As the parameter of the same name.
    prefix : str
        As the parameter of the same name.
    business_date : date
        As the parameter of the same name.
    generation : int
        As the parameter of the same name.
    byte_size : int
        As the parameter of the same name.
    sha256 : str
        As the parameter of the same name.
    deleted_generation_prefixes : tuple[str, ...]
        As the parameter of the same name.
    """

    key: str
    prefix: str
    business_date: date
    generation: int
    byte_size: int
    sha256: str
    deleted_generation_prefixes: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class GenerationFamily:
    """One baseline generation-data-group base and where its generations are staged.

    Purpose
    -------
    Bind a dataset path segment to the bounded context that owns it, to the baseline definition
    it replaces, and to the record geometry that definition declared, so that a caller naming a
    family cannot pair it with the wrong domain or invent a record length for it.

    Parameters
    ----------
    dataset : str
        The dataset path segment, matching the key this family is registered under and the
        prefix ``infra/modules/s3-datasets`` provisions.
    domain : str
        The bounded context that owns the data, forming the leading path segment. One of
        ``ledger``, ``reference`` or ``reporting``.
    base_name : str
        The baseline generation-data-group base this family replaces, as the defining job
        names it.
    defined_in : str
        Repository-relative path of the job holding the authoritative definition.
    definition_lines : str
        The line citation for that definition's ``NAME``, ``LIMIT`` and ``SCRATCH`` operands.
    record_length : int
        The record length the baseline declares for this family's data, in bytes.
    layout_name : str or None
        Name of the registered copybook layout describing one record, or ``None`` where the
        family carries formatted output rather than a copybook record.
    retention_limit : int
        The generation limit the baseline definition declares. Five for every family.

    Returns
    -------
    GenerationFamily
        A frozen instance.

    Raises
    ------
    None
        The registry below is a constant transcribed from the defining jobs; nothing computes
        these values at run time.

    Attributes
    ----------
    dataset : str
        As the parameter of the same name.
    domain : str
        As the parameter of the same name.
    base_name : str
        As the parameter of the same name.
    defined_in : str
        As the parameter of the same name.
    definition_lines : str
        As the parameter of the same name.
    record_length : int
        As the parameter of the same name.
    layout_name : str or None
        As the parameter of the same name.
    retention_limit : int
        As the parameter of the same name.
    """

    dataset: str
    domain: str
    base_name: str
    defined_in: str
    definition_lines: str
    record_length: int
    layout_name: str | None
    retention_limit: int = DEFAULT_GENERATION_RETENTION

    def generation_prefix(
        self,
        settings: DatasetStagingSettings,
        business_date: date,
        generation: int,
    ) -> str:
        """Build this family's generation prefix through the one canonical builder.

        Purpose
        -------
        Let a caller address a generation by family alone, so the domain travels with the
        dataset instead of being supplied alongside it and possibly mismatched.

        Parameters
        ----------
        settings : DatasetStagingSettings
            Validated staging settings owning the prefix layout.
        business_date : date
            Business date to encode as ``dt=YYYY-MM-DD``.
        generation : int
            Generation number to encode as a zero-padded ``gen=``.

        Returns
        -------
        str
            The prefix, ending in a forward slash.

        Raises
        ------
        ConfigurationError
            Propagated from the builder if the business date is a datetime rather than a date,
            or the generation is not an integer within 0 to :data:`MAX_GENERATION`.
        """
        # WHY : Assumptions: this DELEGATES and never formats. The builder on
        #   ``DatasetStagingSettings`` is documented as the only place the prefix layout is
        #   written, and its docstring states that enumerating the families is this module's
        #   job rather than its own. Composing the string here instead would put the layout in
        #   two places, and the two would then be able to disagree about where a dataset lives
        #   while a writer and a reader each looked correct in isolation.
        return settings.generation_prefix(self.domain, self.dataset, business_date, generation)


# WHY : Assumptions: there are TEN families, not six, and the arithmetic is written out so it
#   can be added up rather than trusted. They come from three ``DEFINE GENERATIONDATAGROUP``
#   blocks, which an exhaustive search of the baseline shows are the authoritative ones:
#   SIX in ``app/jcl/DEFGDGB.jcl`` (names at L25, L31, L37, L43, L49 and L55), THREE in
#   ``app/jcl/DEFGDGD.jcl`` (L28, L51, L74) and ONE in ``app/jcl/DALYREJS.jcl`` (L25, inside
#   the DEFINE opened at L24). 6 + 3 + 1 = 10. Every one declares ``LIMIT(5)`` and ``SCRATCH``
#   on the two lines following its ``NAME`` operand.
#   Counting SIX is the specific error this note exists to prevent, and it is an easy one:
#   ``DEFGDGB.jcl`` is headed as the definitions needed by the project and defines six bases in
#   a single step, so it reads as complete on its own. Provisioning or staging six would fail
#   nothing -- the four missing families' steps would still write their objects, into prefixes
#   carrying no retention contract, and their generations would accumulate without limit. One
#   of the four is the reject stream, which is the audit trail of every transaction the posting
#   run declined to post.
# WHY : Assumptions: the ``dataset`` keys and ``domain`` values are transcribed from
#   ``infra/modules/s3-datasets``, whose ``dataset_families`` variable carries a Terraform
#   ``validation`` asserting both a length of ten and this exact key set, and they match the
#   catalogue ``data-migration/README.md`` section 9.1 publishes. Three artifacts therefore
#   state the same inventory independently, and a name invented here would put the ETL out of
#   step with the prefixes that actually exist in the bucket.
# WHY : Assumptions: ``record_length`` is read from the defining or consuming job, never
#   guessed, and each value was cross-checked against the registered copybook layout it names
#   -- ``reclen_of`` returns 350 for TRAN and INTTRAN, 50 for TCATBAL and DISGROUP, 60 for
#   TRANTYPE and TRANCAT, and 430 for REJECT, matching the LRECL each job declares. ``tranrept``
#   is the one family with no ``layout_name``: its 133-byte record is a formatted report line
#   declared at ``app/jcl/TRANREPT.jcl:L78``, not a copybook record, so naming a layout for it
#   would assert a decode contract that does not exist.
_GENERATION_FAMILIES: dict[str, GenerationFamily] = {
    # Six families from app/jcl/DEFGDGB.jcl -- five ledger and one reporting.
    "transact-bkup": GenerationFamily(
        dataset="transact-bkup",
        domain="ledger",
        base_name="AWS.M2.CARDDEMO.TRANSACT.BKUP",
        defined_in="app/jcl/DEFGDGB.jcl",
        definition_lines="L25 NAME, L26 LIMIT(5), L27 SCRATCH",
        record_length=350,
        layout_name="TRAN",
    ),
    "transact-daly": GenerationFamily(
        dataset="transact-daly",
        domain="ledger",
        base_name="AWS.M2.CARDDEMO.TRANSACT.DALY",
        defined_in="app/jcl/DEFGDGB.jcl",
        definition_lines="L31 NAME, L32 LIMIT(5), L33 SCRATCH",
        record_length=350,
        layout_name="TRAN",
    ),
    "tranrept": GenerationFamily(
        dataset="tranrept",
        domain="reporting",
        base_name="AWS.M2.CARDDEMO.TRANREPT",
        defined_in="app/jcl/DEFGDGB.jcl",
        definition_lines="L37 NAME, L38 LIMIT(5), L39 SCRATCH",
        record_length=133,
        layout_name=None,
    ),
    "tcatbalf-bkup": GenerationFamily(
        dataset="tcatbalf-bkup",
        domain="ledger",
        base_name="AWS.M2.CARDDEMO.TCATBALF.BKUP",
        defined_in="app/jcl/DEFGDGB.jcl",
        definition_lines="L43 NAME, L44 LIMIT(5), L45 SCRATCH",
        record_length=50,
        layout_name="TCATBAL",
    ),
    "systran": GenerationFamily(
        dataset="systran",
        domain="ledger",
        base_name="AWS.M2.CARDDEMO.SYSTRAN",
        defined_in="app/jcl/DEFGDGB.jcl",
        definition_lines="L49 NAME, L50 LIMIT(5), L51 SCRATCH",
        record_length=350,
        layout_name="INTTRAN",
    ),
    "transact-combined": GenerationFamily(
        dataset="transact-combined",
        domain="ledger",
        base_name="AWS.M2.CARDDEMO.TRANSACT.COMBINED",
        defined_in="app/jcl/DEFGDGB.jcl",
        definition_lines="L55 NAME, L56 LIMIT(5), L57 SCRATCH",
        record_length=350,
        layout_name="TRAN",
    ),
    # Three reference families from app/jcl/DEFGDGD.jcl.
    "trantype-bkup": GenerationFamily(
        dataset="trantype-bkup",
        domain="reference",
        base_name="AWS.M2.CARDDEMO.TRANTYPE.BKUP",
        defined_in="app/jcl/DEFGDGD.jcl",
        definition_lines="L28 NAME, L29 LIMIT(5), L30 SCRATCH",
        record_length=60,
        layout_name="TRANTYPE",
    ),
    "trancatg-bkup": GenerationFamily(
        dataset="trancatg-bkup",
        domain="reference",
        base_name="AWS.M2.CARDDEMO.TRANCATG.PS.BKUP",
        defined_in="app/jcl/DEFGDGD.jcl",
        definition_lines="L51 NAME, L52 LIMIT(5), L53 SCRATCH",
        record_length=60,
        layout_name="TRANCAT",
    ),
    "discgrp-bkup": GenerationFamily(
        dataset="discgrp-bkup",
        domain="reference",
        base_name="AWS.M2.CARDDEMO.DISCGRP.BKUP",
        defined_in="app/jcl/DEFGDGD.jcl",
        definition_lines="L74 NAME, L75 LIMIT(5), L76 SCRATCH",
        record_length=50,
        layout_name="DISGROUP",
    ),
    # WHY : Assumptions: the tenth family is the one most easily missed, because it is defined
    #   in a job named for the reject dataset itself rather than in either ``DEFGDG*`` job. Its
    #   430-byte record is the posting reject contract -- the 350-byte daily-transaction record
    #   extended by a reason code and description -- and the registered REJECT layout reports
    #   exactly that length.
    "dalyrejs": GenerationFamily(
        dataset="dalyrejs",
        domain="ledger",
        base_name="AWS.M2.CARDDEMO.DALYREJS",
        defined_in="app/jcl/DALYREJS.jcl",
        definition_lines="L24 DEFINE, L25 NAME, L26 LIMIT(5), L27 SCRATCH",
        record_length=430,
        layout_name="REJECT",
    ),
}

# WHY : Trade-offs: the registry is exposed through a read-only view rather than as the
#   dictionary itself. The cost is one indirection on every lookup; what it buys is that the
#   inventory cannot be mutated by a caller that happens to hold it, which matters because this
#   IS the prefix topology and the topology must be identical in every environment. It is the
#   same protection ``carddemo_migration.config`` gives its schema-to-role tables.
GENERATION_FAMILIES: Mapping[str, GenerationFamily] = MappingProxyType(_GENERATION_FAMILIES)


def family_names() -> tuple[str, ...]:
    """Return every registered generation family name in declaration order.

    Purpose
    -------
    Let a caller enumerate the families -- to walk them, or to report the closed set of names a
    rejected one could have been -- without reaching into the registry mapping.

    Parameters
    ----------
    None
        The registry is a module constant.

    Returns
    -------
    tuple[str, ...]
        The ten dataset path segments, in the order the registry declares them.

    Raises
    ------
    None
        Reading a constant cannot fail.
    """
    # WHY : Assumptions: declaration order is preserved rather than sorted, because the order
    #   groups the families by defining job -- six, then three, then one -- which is what lets a
    #   reader of the output check the count against the three sources instead of trusting it.
    return tuple(_GENERATION_FAMILIES)


def family(name: str) -> GenerationFamily:
    """Look up one generation family by its dataset path segment.

    Purpose
    -------
    Resolve a caller-supplied family name against the registry, so that an unknown name is
    refused before any client is built or any byte is read.

    Parameters
    ----------
    name : str
        The dataset path segment, for example ``transact-bkup``. Surrounding whitespace is
        ignored; the comparison is otherwise exact.

    Returns
    -------
    GenerationFamily
        The registered family.

    Raises
    ------
    GenerationRetentionError
        If ``name`` is not text, is blank, or is not one of the ten registered families.
    """
    if not isinstance(name, str):
        raise GenerationRetentionError("generation family name is not text")
    stripped = name.strip()
    if not stripped:
        raise GenerationRetentionError("generation family name is empty")
    resolved = _GENERATION_FAMILIES.get(stripped)
    if resolved is None:
        # WHY : Trade-offs: the message lists every accepted name. It is long, and that is the
        #   point: the failure this guards is a typo or a family a caller invented, and both are
        #   fixed by seeing the closed set. Reporting only the rejected name would leave an
        #   operator to find the inventory in a Terraform variable file.
        raise GenerationRetentionError(
            f"unknown generation family {stripped!r}; expected one of {', '.join(family_names())}"
        )
    return resolved


def parse_business_date(value: str) -> date:
    """Parse the injected business date that supplies the ``dt=`` path component.

    Purpose
    -------
    Turn the caller's business date into a :class:`~datetime.date`, refusing anything that is
    not exactly ten characters of ISO-8601 calendar date. There is no default and no fallback:
    a business date that was not supplied is an error, never a value this function invents.

    Parameters
    ----------
    value : str
        The business date as ``YYYY-MM-DD``.

    Returns
    -------
    date
        The parsed calendar date.

    Raises
    ------
    GenerationRetentionError
        If ``value`` is not text, is blank, is not exactly ten characters, or does not name a
        real calendar date.
    """
    # WHY : Assumptions: the business date is a REQUIRED PARAMETER and this module reads no
    #   clock -- there is no ``date.today``, ``datetime.now``, ``datetime.utcnow`` or
    #   ``time.time`` anywhere on the staging path. The baseline is explicit about this:
    #   ``app/jcl/INTCALC.jcl:22`` is
    #       //STEP15 EXEC PGM=CBACT04C,PARM='2022071800'
    #   so the date is injected as a literal job parameter, and none of that step's nine DD
    #   statements at L23-L41 consults a clock. The consequence of getting this wrong is
    #   specific rather than stylistic: a clock read makes a rerun land under a different
    #   ``dt=`` prefix from the run it was meant to reproduce, which orphans the first
    #   generation and destroys the byte-for-byte comparison the golden-master parity strategy
    #   depends on. Defaulting to "today" would do exactly that, silently.
    if not isinstance(value, str):
        raise GenerationRetentionError("business date is not text")
    text = value.strip()
    if not text:
        raise GenerationRetentionError(
            "business date is empty; it must be supplied as YYYY-MM-DD and is never defaulted"
        )

    # WHY : Assumptions: the SHAPE is matched before parsing, because
    #   ``date.fromisoformat`` became permissive in Python 3.11 and this package targets 3.13.
    #   Measured against the pinned interpreter rather than assumed: it accepts the compact
    #   ``20220718`` and, more awkwardly, the ISO week form ``2022-W29-1``, which is also
    #   exactly ten characters and so slips past a width test -- an earlier revision of this
    #   function checked only the length and did let it through. Both parse to a real date, so
    #   neither fails later; they simply let one business day be written three ways. Anchoring
    #   the pattern forces the single spelling the prefix convention publishes, which is what
    #   keeps the ``dt=`` segment reconcilable with the parameter an operator passed.
    if not _BUSINESS_DATE_PATTERN.match(text):
        raise GenerationRetentionError(
            f"business date {text!r} is not the YYYY-MM-DD form; the compact and ISO week forms "
            f"are refused so that one business day has one spelling"
        )
    try:
        parsed = date.fromisoformat(text)
    except ValueError as exc:
        raise GenerationRetentionError(
            f"business date {text!r} is not a valid calendar date"
        ) from exc

    # WHY : Alternatives Considered: no datetime guard is written here, and its absence is
    #   deliberate rather than an omission. ``date.fromisoformat`` returns a ``date`` and the
    #   pattern above admits only the calendar form, so a branch testing for a ``datetime``
    #   could never execute -- an unreachable check that a reader would nonetheless have to
    #   reason about. The case it would guard is real but arrives by a different route: a caller
    #   holding a ``datetime`` and passing it straight to the prefix builder. That builder
    #   already refuses one, for the stated reason that the partition is a day and cannot carry
    #   a time of day, so the guard exists exactly once and on the path that can reach it.
    return parsed


def s3_client() -> S3StagingClient:
    """Build the S3 client this module stages through.

    Purpose
    -------
    Obtain a client whose region, credentials and endpoint all come from the ambient
    environment, so that one code path serves both the batch staging task and the local
    emulator the tests run against.

    Parameters
    ----------
    None
        Everything the client needs is resolved from the environment.

    Returns
    -------
    S3StagingClient
        A client satisfying the operations this module uses.

    Raises
    ------
    ConfigurationError
        If the AWS SDK is not installed, or the environment names no usable region or
        credentials.
    """
    # WHY : Alternatives Considered: no ``endpoint_url``, no ``region_name``, no credential and
    #   no bucket name is passed literally, anywhere. botocore at the pinned 1.43.50 honours the
    #   ``AWS_ENDPOINT_URL`` environment variable natively, and that is the same variable the
    #   reference emulator helper under ``tests/helpers/`` declares as its canonical override
    #   and exports for the suite. So the SAME code runs against the emulator in a test and
    #   against the real service inside the batch staging task, with no branch and no flag.
    #   Passing a literal endpoint -- or branching on a "running locally" boolean to decide
    #   whether to pass one -- would create a second code path that only one of the two
    #   environments ever exercises, so a defect in either would be invisible from the other.
    # WHY : Alternatives Considered: the client is obtained from
    #   ``carddemo_migration.config`` rather than by calling ``boto3.client("s3")`` here.
    #   Building it locally was written first and rejected for a concrete reason: config's
    #   factory is memoised, and ``config.reset_resolution_cache`` discards every memoised
    #   client by scanning that module's own globals. A client constructed in THIS module would
    #   not be in those globals, so a caller that reset the package's resolution state -- a test
    #   substituting an endpoint between cases, or a long-running process picking up rotated
    #   credentials -- would get a fresh parameter store client and a stale S3 client from the
    #   same call, which is the hardest kind of stale-state defect to attribute because only
    #   half the state moved. Routing through config keeps one authority for construction and
    #   one for invalidation.
    # WHY : Assumptions: the factory is reached by its PUBLIC name. It was private when this call
    #   was written, and ``config`` promoted it precisely because a public function here depending
    #   on a private name there is a contract no import check can protect -- that module was free to
    #   rename it, and did. The private spelling is now absent and is asserted absent by
    #   ``tests/test_config_name_contract.py``, so the old call raised ``AttributeError`` on the
    #   first real staging run while every test still passed, because nothing exercised this
    #   function. ``tests/test_loaders.py`` now reads this module's call graph and fails if the
    #   client is obtained anywhere other than the published factory.
    return config.aws_client("s3")


def _require_retention_count(retention_count: int) -> int:
    """Return a positive whole-number retention count.

    Purpose
    -------
    Refuse a retention count that cannot express "keep the newest N generations", before any
    prefix is deleted on the strength of it.

    Parameters
    ----------
    retention_count : int
        Number of newest logical generations to retain.

    Returns
    -------
    int
        The unchanged validated count.

    Raises
    ------
    GenerationRetentionError
        If the value is a boolean, non-integer or less than one.
    """
    # WHY : Assumptions: ``bool`` is excluded before the integer check because it subclasses
    #   ``int``, so ``True`` would otherwise pass as a count of one and scratch every generation
    #   but the newest -- a destructive outcome from what is almost certainly a mistyped
    #   argument. Zero is refused for the same reason: it would leave no generation at all.
    if isinstance(retention_count, bool) or not isinstance(retention_count, int):
        raise GenerationRetentionError("generation retention count is not an integer")
    if retention_count < 1:
        raise GenerationRetentionError("generation retention count must be at least one")
    return retention_count


def _require_object_name(object_name: str) -> str:
    """Return a safe single-segment object name.

    Purpose
    -------
    Keep the leaf name a leaf, so that a staged object cannot land outside the generation
    prefix its caller asked for.

    Parameters
    ----------
    object_name : str
        Leaf name appended beneath a generation prefix.

    Returns
    -------
    str
        The stripped object name.

    Raises
    ------
    GenerationRetentionError
        If the name is blank, not text, contains a slash, is a dot segment, or carries a character
        outside the visible ASCII range.
    """
    # WHY : Assumptions: a slash and the two dot segments are refused because the name is
    #   concatenated onto a prefix that has already been validated. Allowing ``../`` or an
    #   embedded slash would let the leaf deepen or escape the hierarchy, so an object would be
    #   written where neither the retention walk nor a reader looking by convention would find
    #   it -- and the write itself would still succeed.
    if not isinstance(object_name, str):
        raise GenerationRetentionError("generation object name is not text")
    stripped = object_name.strip()
    if not stripped:
        raise GenerationRetentionError("generation object name is empty")
    if "/" in stripped or stripped in {".", ".."}:
        raise GenerationRetentionError("generation object name must be one non-dot path segment")
    # WHY : Assumptions: the leaf is restricted to VISIBLE ASCII, and the refusal is at the
    #   boundary rather than a rendering at each log site. A name carrying a newline or a carriage
    #   return can forge an additional line in every retained log that ever names the key -- this
    #   module's own staging line, the command's success line, and a later verification's
    #   diagnostic -- which is CWE-117, and a log is what an incident is reconstructed from.
    #   Sanitizing at each site would leave the next site that forgets. Trade-offs: a legitimate
    #   extract whose name is not ASCII is refused. That is accepted, and it costs nothing here:
    #   all ten registered datasets name upper-case ASCII objects, and S3 itself recommends this
    #   character range for keys because anything outside it needs special handling in every
    #   consumer.
    if not _LOGGABLE_TEXT.fullmatch(stripped):
        raise GenerationRetentionError(
            f"the generation object name {sanitized_for_log(stripped)} carries a character"
            " outside the visible ASCII range; a name that can carry a line break can forge a"
            " line in every log that records the staged key"
        )
    return stripped


def _require_staged_generation(generation: int) -> int:
    """Return a generation number inside the range a staged prefix can carry.

    Purpose
    -------
    Refuse a generation number that the four-digit ``gen=`` component cannot express, and
    refuse zero, which the baseline never catalogues as a written generation.

    Parameters
    ----------
    generation : int
        The generation number a caller wants to write.

    Returns
    -------
    int
        The unchanged validated generation number.

    Raises
    ------
    GenerationRetentionError
        If the value is a boolean, a non-integer, or outside :data:`MIN_GENERATION` to
        :data:`MAX_GENERATION` inclusive.
    """
    # WHY : Trade-offs: this is a NARROWER check than the prefix builder's, which accepts zero
    #   because it also serves the family-prefix derivation below. Writing is held to 1-9999
    #   while derivation may use 0, and the asymmetry is deliberate: a written ``gen=0000`` has
    #   no baseline counterpart, since the first relative reference is ``(+1)`` and the first
    #   catalogued generation is ``G0001V00``.
    if isinstance(generation, bool) or not isinstance(generation, int):
        raise GenerationRetentionError("dataset generation is not an integer")
    if not MIN_GENERATION <= generation <= MAX_GENERATION:
        raise GenerationRetentionError(
            f"dataset generation {generation} is outside the range "
            f"{MIN_GENERATION} to {MAX_GENERATION}"
        )
    return generation


def family_prefix(
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
) -> str:
    """Derive the ``<domain>/<dataset>/`` prefix a family's generations sit beneath.

    Purpose
    -------
    Produce the prefix that every generation of one dataset shares, which is what discovery and
    retention both list against.

    Parameters
    ----------
    settings : DatasetStagingSettings
        Validated staging settings owning the prefix layout.
    domain : str
        Bounded-context segment.
    dataset : str
        Dataset-family segment.

    Returns
    -------
    str
        The family prefix, ending in a forward slash.

    Raises
    ------
    ConfigurationError
        Propagated from the builder if either segment is blank or contains a forward slash.
    """
    # WHY : Alternatives Considered: the family prefix is derived by asking the canonical
    #   builder for a sample and truncating at ``dt=``, rather than by concatenating
    #   ``f"{domain}/{dataset}/"`` here. Concatenating is shorter and was rejected: it is a
    #   second, independent statement of the layout, so a change to the builder's shape would
    #   leave this function listing a prefix that no longer contains anything, and an empty
    #   listing looks exactly like a family with no generations yet. Truncating a generated
    #   sample means the two cannot diverge -- and it keeps the builder's validation of both
    #   segments, which a local f-string would skip.
    # WHY : Assumptions: ``date.min`` and generation ``0`` are placeholders that never reach a
    #   key. They are chosen because the builder accepts them -- its range starts at zero -- and
    #   because everything after ``dt=`` is discarded, so their values cannot influence the
    #   result. This is why ``_require_staged_generation`` guards writes separately instead of
    #   the builder being tightened to reject zero.
    sample = settings.generation_prefix(domain, dataset, date.min, 0)
    return sample.split("dt=", 1)[0]


def _parse_generation_prefix(
    candidate: str,
    prefix: str,
) -> GenerationPrefix | None:
    """Parse one common prefix, returning ``None`` for unrelated child paths.

    Purpose
    -------
    Recognise the fixed ``dt=/gen=`` convention and nothing else, so that retention can never
    select a path that was not written by this module.

    Parameters
    ----------
    candidate : str
        Common prefix returned by S3.
    prefix : str
        Validated ``<domain>/<dataset>/`` prefix expected at the front.

    Returns
    -------
    GenerationPrefix | None
        Parsed generation, or ``None`` when the child path does not match the fixed
        ``dt=/gen=`` convention.

    Raises
    ------
    GenerationRetentionError
        If a matching date segment contains an impossible calendar date.
    """
    # WHY : Trade-offs: a non-matching child path is IGNORED rather than reported, while a
    #   path that matches the shape but carries an impossible date is raised. The asymmetry is
    #   deliberate. A bucket legitimately holds paths this module did not write, so treating
    #   every one as an error would make retention unusable; but ``dt=2022-02-30/gen=0001/``
    #   matches the digit pattern and cannot be ordered against its siblings, and guessing at
    #   its position is how a deletion walk removes the wrong prefix.
    if not candidate.startswith(prefix):
        return None
    match = _GENERATION_SUFFIX.fullmatch(candidate[len(prefix) :])
    if match is None:
        return None
    try:
        business_date = date.fromisoformat(match.group(1))
    except ValueError as exc:
        raise GenerationRetentionError(
            f"generation prefix contains an invalid business date: {candidate}"
        ) from exc
    return GenerationPrefix(
        business_date=business_date,
        generation=int(match.group(2)),
        prefix=candidate,
    )


def list_generation_prefixes(
    client: S3StagingClient,
    bucket: str,
    prefix: str,
) -> tuple[GenerationPrefix, ...]:
    """List valid logical generations beneath one dataset family.

    Purpose
    -------
    Enumerate what has actually been staged for a family, in the order the baseline catalogue
    would have held it, so that discovery and retention both work from the bucket's own state.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for paginated prefix discovery.
    bucket : str
        Dataset bucket name.
    prefix : str
        Validated ``<domain>/<dataset>/`` prefix.

    Returns
    -------
    tuple[GenerationPrefix, ...]
        Chronologically sorted generations, oldest first. Unknown child paths are ignored so
        cleanup never deletes an object outside the fixed convention.

    Raises
    ------
    GenerationRetentionError
        If a matching prefix contains an invalid date.
    """
    # WHY : Trade-offs: the walk is two levels of delimited listing -- date prefixes, then
    #   generation prefixes under each -- rather than one flat listing of every key in the
    #   family. A flat listing was the obvious alternative and was rejected on cost: it returns
    #   one entry per OBJECT, so a family holding five generations of the 500-record export
    #   extract returns every one of those keys just to learn five prefix names. Delimited
    #   listing returns one entry per prefix, and the accepted cost is one request per date
    #   rather than one per family.
    date_prefixes: set[str] = set()
    for page in _paginate(
        client,
        "list_objects_v2",
        "the generation listing",
        Bucket=bucket,
        Prefix=prefix,
        Delimiter="/",
    ):
        date_prefixes.update(
            item["Prefix"]
            for item in page.get("CommonPrefixes", [])
            if isinstance(item.get("Prefix"), str)
        )

    generations: set[GenerationPrefix] = set()
    for date_prefix in sorted(date_prefixes):
        for page in _paginate(
            client,
            "list_objects_v2",
            "the generation listing",
            Bucket=bucket,
            Prefix=date_prefix,
            Delimiter="/",
        ):
            for item in page.get("CommonPrefixes", []):
                candidate = item.get("Prefix")
                if not isinstance(candidate, str):
                    continue
                parsed = _parse_generation_prefix(candidate, prefix)
                if parsed is not None:
                    generations.add(parsed)
    # WHY : Assumptions: results are collected in a set and then sorted, because a paginator
    #   may legitimately return the same common prefix on two pages and a duplicate would make
    #   the newest-N slice below drop one generation too many.
    return tuple(sorted(generations))


def latest_generation(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
) -> GenerationPrefix | None:
    """Resolve the baseline ``(0)`` reference -- the newest generation that exists.

    Purpose
    -------
    Answer "which generation would a ``(0)`` reference read?" for one family, so a consuming
    step reads the same generation the baseline job would have read. This is the LOW-LEVEL query
    and it treats an empty family as an answer; :func:`current_generation` is the strict resolver
    a step that cannot proceed without a generation should call.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for paginated prefix discovery.
    settings : DatasetStagingSettings
        Validated bucket settings and the canonical prefix builder.
    domain : str
        Bounded-context segment.
    dataset : str
        Dataset-family segment.

    Returns
    -------
    GenerationPrefix | None
        The newest generation in the family, or ``None`` when the family holds none.

    Raises
    ------
    GenerationRetentionError
        If a discovered prefix carries an invalid business date.
    ConfigurationError
        If either path segment is unacceptable to the prefix builder.
    """
    # WHY : Assumptions: ``(0)`` is resolved across the WHOLE family rather than within one
    #   business date, which is what the published convention states: a ``(+1)`` reference
    #   becomes a new prefix and a ``(0)`` reference resolves to the newest valid prefix in the
    #   family. It matters at a date boundary -- ``app/jcl/COMBTRAN.jcl`` reads
    #   ``TRANSACT.BKUP(0)`` at L24 and ``SYSTRAN(0)`` at L26, and a date-scoped answer would
    #   return nothing on the first run of a new business day even though the generation the
    #   merge needs exists under the previous one.
    generations = list_generation_prefixes(
        client, settings.bucket, family_prefix(settings, domain, dataset)
    )
    return generations[-1] if generations else None


def current_generation(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
) -> GenerationPrefix:
    """Resolve the baseline ``(0)`` reference, refusing a family that holds no generation.

    Purpose
    -------
    Answer "which generation does a ``(0)`` reference read?" for a consuming step that cannot
    proceed without one, so an absent generation is a typed failure rather than an empty read.
    This is the resolver a batch step calls; :func:`latest_generation` is the lower-level query
    for a caller that treats absence as a legitimate answer.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for paginated prefix discovery.
    settings : DatasetStagingSettings
        Validated bucket settings and the canonical prefix builder.
    domain : str
        Bounded-context segment.
    dataset : str
        Dataset-family segment.

    Returns
    -------
    GenerationPrefix
        The newest generation in the family. Never ``None``: an empty family raises instead.

    Raises
    ------
    GenerationDiscoveryError
        If the family holds no generation, so no ``(0)`` reference can be resolved.
    GenerationRetentionError
        If a discovered prefix carries an invalid business date.
    ConfigurationError
        If either path segment is unacceptable to the prefix builder.
    """
    # WHY : Alternatives Considered: this is a SECOND function rather than a flag on
    #   :func:`latest_generation`, and the two are kept apart because they answer different
    #   questions and their callers need different outcomes. A retention pass asks "is there
    #   anything here?" and an empty family is a normal answer; a consuming batch step asks "read
    #   the current generation", and for it an empty family means the producing step did not run.
    #   A boolean parameter would put both meanings behind one name, so a caller reading the call
    #   site could not tell which contract was in force -- and the strict contract is the one the
    #   baseline's own semantics carry: a JCL step referencing ``TRANSACT.BKUP(0)`` against an
    #   empty generation data group fails the step rather than reading nothing.
    # WHY : Assumptions: the failure is `GenerationDiscoveryError`, which already exists for the
    #   other way discovery yields no usable answer -- an exhausted generation space -- so a caller
    #   handling "the generation I need cannot be determined" catches one class for both. A new
    #   exception type was rejected because it would split one operator response across two names.
    resolved = latest_generation(client, settings, domain, dataset)
    if resolved is None:
        raise GenerationDiscoveryError(
            f"no generation exists for {domain}/{dataset}, so a (0) reference cannot be resolved;"
            " the step that stages this family has not produced a generation yet"
        )
    return resolved


def next_generation(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
    business_date: date,
) -> int:
    """Resolve the baseline ``(+1)`` reference -- the next generation number to write.

    Purpose
    -------
    Compute the generation number a new write should carry, from the ``gen=`` prefixes that
    already exist under the target business date.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for paginated prefix discovery.
    settings : DatasetStagingSettings
        Validated bucket settings and the canonical prefix builder.
    domain : str
        Bounded-context segment.
    dataset : str
        Dataset-family segment.
    business_date : date
        The injected business date the new generation belongs to.

    Returns
    -------
    int
        :data:`MIN_GENERATION` when the business date holds no generation yet, otherwise one
        more than the highest generation number already present under that date.

    Raises
    ------
    GenerationRetentionError
        If a discovered prefix carries an invalid business date.
    GenerationDiscoveryError
        If the business date already holds generation :data:`MAX_GENERATION`, leaving no next
        number the four-digit component can express.
    ConfigurationError
        If either path segment is unacceptable to the prefix builder.
    """
    # WHY : Alternatives Considered: the next number is DISCOVERED from the prefixes in the
    #   bucket rather than tracked in a counter this process holds. A counter was rejected on
    #   two concrete failures. First, the batch chain stages through a Step Functions ``Map``
    #   state that runs one containerised branch per dataset, and each branch is a fresh
    #   process, so every branch's counter would start from the same base and two branches would
    #   compute the same value -- and the second write would land on the first one's key.
    #   Second, and worse, a counter knows nothing about previous runs: Step Functions may
    #   RETRY a ``Map`` branch, and a retried branch would recompute the number the failed
    #   attempt already used and overwrite a generation that had completed. The bucket's own
    #   prefix listing is the one durable, shared state that every branch and every attempt
    #   agrees on, so it is the only correct source for the answer.
    # WHY : Assumptions: ``(+1)`` is scoped to the TARGET BUSINESS DATE, unlike the family-wide
    #   ``(0)`` above. The prefix partitions by day, so a second staging run for the same
    #   injected date must become that date's ``gen=0002`` rather than a number derived from
    #   some later date's generations -- otherwise re-running one day after a subsequent day had
    #   been staged would skip generation numbers and make the two dates' sequences
    #   uncomparable. Ordering across the family is still total, because
    #   :class:`GenerationPrefix` compares on date before generation.
    prefix = family_prefix(settings, domain, dataset)
    generations = list_generation_prefixes(client, settings.bucket, prefix)
    same_date = [item.generation for item in generations if item.business_date == business_date]
    if not same_date:
        return MIN_GENERATION
    highest = max(same_date)
    if highest >= MAX_GENERATION:
        raise GenerationDiscoveryError(
            f"the generation space for {prefix} on {business_date.isoformat()} is exhausted at "
            f"gen={MAX_GENERATION:0{GENERATION_DIGITS}d}; no further generation can be staged "
            f"for that business date"
        )
    return highest + 1


_CLAIM_SEGMENT: Final[str] = "_claims/"
#: Service error codes a conditional create returns when another writer already claimed the key.
#: ``PreconditionFailed`` is S3's 412 answer to an unsatisfied ``If-None-Match``;
#: ``ConditionalRequestConflict`` is the 409 answer when two conditional writes race each other.
#: Both mean "somebody else got there first", which is a normal outcome of the allocation loop
#: below rather than an error, so both are treated as a refused claim and not re-raised.
_CLAIM_CONFLICT_CODES: Final[frozenset[str]] = frozenset(
    {"PreconditionFailed", "ConditionalRequestConflict"}
)


def _claim_prefix(
    settings: DatasetStagingSettings, domain: str, dataset: str, business_date: date
) -> str:
    """Build the prefix holding one business date's generation claims for one family.

    Purpose
    -------
    Place claim records in a sibling of the ``gen=`` prefixes under the same business date, so a
    claim is discoverable from the family and date alone without colliding with a generation.

    Parameters
    ----------
    settings : DatasetStagingSettings
        Validated bucket settings carrying the canonical prefix builder.
    domain : str
        Bounded-context segment.
    dataset : str
        Dataset-family segment.
    business_date : date
        The business date whose claims are wanted.

    Returns
    -------
    str
        The claim prefix, ending in a forward slash.

    Raises
    ------
    ConfigurationError
        If a path segment or the business date is unacceptable to the prefix builder.
    """
    # WHY : Alternatives Considered: the claim prefix is derived by TRUNCATING a real generation
    #   prefix at its ``gen=`` component rather than being composed from the segments here. The
    #   composed form was rejected because it would restate the layout -- domain, dataset, the
    #   ``dt=`` spelling and the ISO date format -- in a second place, and the two spellings would
    #   then be free to drift. Deriving keeps one builder authoritative for the whole layout, the
    #   same technique :func:`family_prefix` already uses to obtain the family root.
    # WHY : Assumptions: claims sit in a ``_claims/`` SIBLING of the ``gen=NNNN/`` prefixes rather
    #   than inside one, and two consequences follow that a reader should not have to discover.
    #   First, they are invisible to generation discovery: :func:`_parse_generation_prefix` refuses
    #   the segment, so a claim can never be mistaken for a staged generation. Second, and stated
    #   plainly because it is a real operational cost, :func:`prune_generations` does NOT remove
    #   them -- it deletes generation prefixes, and a claim is not one -- so claims accumulate at
    #   roughly one small object per staging step per family. Pruning them alongside generations
    #   was considered and rejected: a claim outliving its generation is harmless, whereas deleting
    #   a claim whose execution may still retry would hand that retry a fresh generation and
    #   reintroduce the duplicate this mechanism exists to prevent. The bucket's lifecycle
    #   configuration is the right place to expire them, since it can do so on age rather than on
    #   a guess about whether an execution has finished.
    generation_prefix = settings.generation_prefix(domain, dataset, business_date, MIN_GENERATION)
    return f"{generation_prefix.split('gen=', 1)[0]}{_CLAIM_SEGMENT}"


def _claim_key(
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
    business_date: date,
    generation: int,
) -> str:
    """Build the key of the claim record for one generation of one family and business date.

    Purpose
    -------
    Name the single object whose existence means "this generation number is taken", so claiming
    a generation is one conditional create rather than a read followed by a write.

    Parameters
    ----------
    settings : DatasetStagingSettings
        Validated bucket settings carrying the canonical prefix builder.
    domain : str
        Bounded-context segment.
    dataset : str
        Dataset-family segment.
    business_date : date
        The business date the generation belongs to.
    generation : int
        The generation number being claimed.

    Returns
    -------
    str
        The claim record's key. It is an object key, not a prefix, so it has no trailing slash.

    Raises
    ------
    ConfigurationError
        If a path segment or the business date is unacceptable to the prefix builder.
    """
    prefix = _claim_prefix(settings, domain, dataset, business_date)
    return f"{prefix}gen={generation:0{GENERATION_DIGITS}d}"


def _claimed_generation(key: str, claim_prefix: str) -> int | None:
    """Read the generation number out of a claim key, or report that it is not one.

    Purpose
    -------
    Keep claim-key parsing in one place, so a stray object under the claim prefix is ignored
    rather than being mistaken for a claim and skewing allocation.

    Parameters
    ----------
    key : str
        A key discovered under the claim prefix.
    claim_prefix : str
        The claim prefix the key was listed under.

    Returns
    -------
    int or None
        The generation the claim names, or ``None`` when the key is not a well-formed claim.

    Raises
    ------
    None
        An unparseable key yields ``None``, because refusing the whole allocation over one
        unrecognised object would let anything written under the prefix block staging outright.
    """
    if not key.startswith(claim_prefix):
        return None
    remainder = key[len(claim_prefix) :]
    matched = re.fullmatch(rf"gen=(\d{{{GENERATION_DIGITS}}})", remainder)
    if matched is None:
        return None
    number = int(matched.group(1))
    if number < MIN_GENERATION or number > MAX_GENERATION:
        return None
    return number


def _existing_claim(
    client: S3StagingClient, bucket: str, claim_prefix: str, execution_token: str
) -> int | None:
    """Find the generation this execution already claimed for a family and business date.

    Purpose
    -------
    Make a retried staging step reuse the generation its first attempt allocated, so a retry
    re-writes one key rather than consuming a second generation with identical bytes.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the listing and the claim reads.
    bucket : str
        Dataset bucket holding the claims.
    claim_prefix : str
        Prefix under which this family and business date keep their claims.
    execution_token : str
        Token identifying the orchestrator execution whose claim is wanted.

    Returns
    -------
    int or None
        The generation already claimed by this execution, or ``None`` if it holds none.

    Raises
    ------
    GenerationDiscoveryError
        If a claim record exists but cannot be read.
    """
    # WHY : Alternatives Considered: the replay lookup LISTS the claims and reads each one, rather
    #   than deriving a key from the execution token and fetching it directly. A token-keyed
    #   record would be one request instead of N, and it was rejected because it cannot make the
    #   generation itself exclusive: two executions would each create their own token-keyed record
    #   and both could name the same generation, which is precisely the collision this function
    #   exists to prevent. Keying the record BY GENERATION is what makes the conditional create
    #   below a mutual exclusion, and the cost of that choice is this bounded scan. It is bounded
    #   by the number of generations one business date holds, which the family retention limit
    #   keeps small -- five for every one of the ten provisioned families.
    candidates: dict[int, str] = {}
    for page in _paginate(
        client,
        "list_objects_v2",
        "the generation-claim listing",
        Bucket=bucket,
        Prefix=claim_prefix,
    ):
        for item in page.get("Contents", []):
            key = item.get("Key")
            if not isinstance(key, str):
                continue
            number = _claimed_generation(key, claim_prefix)
            if number is not None:
                candidates[number] = key
    for number in sorted(candidates):
        try:
            response = client.get_object(Bucket=bucket, Key=candidates[number])
            holder = response["Body"].read()
        except Exception as exc:  # noqa: BLE001 - re-raised below as a named staging failure
            # WHY : Refactoring Rationale: this interpolated `{exc}`, so a refused or failed claim
            #   read published the SDK's own text -- the assumed-role ARN, the account identifier
            #   and the request identifier -- into a message `cli.py` writes to a container log.
            #   The metadata is now allow-listed and the provider exception is suppressed with
            #   `from None`, so nothing logging `exc_info` can recover the original text.
            raise GenerationDiscoveryError(
                _provider_failure("the generation-claim read", bucket, candidates[number], exc)
            ) from None
        # WHY : Assumptions: the stored token is compared as BYTES decoded strictly rather than
        #   being trusted as text. A claim body this process did not write -- anything else that
        #   put an object under the prefix -- may not be valid UTF-8 at all, and a lenient decode
        #   would turn those bytes into replacement characters that could never match any token
        #   and would silently be treated as another execution's claim. Refusing to match on an
        #   undecodable body reaches the same conclusion honestly.
        try:
            recorded = holder.decode("utf-8")
        except UnicodeDecodeError:
            continue
        if recorded == execution_token:
            return number
    return None


def _claim_generation(client: S3StagingClient, bucket: str, key: str, execution_token: str) -> bool:
    """Attempt to claim one generation with a single conditional create.

    Purpose
    -------
    Turn "is this generation free, and may I have it?" into one atomic service operation, so two
    concurrent allocators cannot both conclude the same number is available.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the conditional write.
    bucket : str
        Dataset bucket holding the claims.
    key : str
        The claim record's key, naming the generation being claimed.
    execution_token : str
        Token recorded as the claim's body, identifying the holder.

    Returns
    -------
    bool
        True when this call created the claim, False when it already existed.

    Raises
    ------
    Exception
        Any service failure other than a conditional-write conflict is re-raised unchanged,
        because only a conflict is an expected outcome of the allocation loop.
    """
    # WHY : Refactoring Rationale: allocation is a CONDITIONAL CREATE, replacing a
    #   list-then-take-the-maximum-then-add-one sequence. That sequence had no atomicity at any
    #   point: two allocators listing the same prefix both read the same highest generation, both
    #   computed the same successor, and the second write landed on the first one's key -- a lost
    #   update with no error anywhere. ``If-None-Match: *`` makes the service itself arbitrate, so
    #   exactly one caller can create a given claim and the loser is told so.
    try:
        client.put_object(
            Bucket=bucket,
            Key=key,
            Body=execution_token.encode("utf-8"),
            ContentType="text/plain; charset=utf-8",
            IfNoneMatch="*",
        )
    except Exception as exc:  # noqa: BLE001 - only a conflict is swallowed; see below
        # WHY : Trade-offs: the conflict is recognised by the service's own ERROR CODE rather than
        #   by exception type, reusing the defensive reader the configuration module already
        #   applies for the same purpose. Catching ``ClientError`` by type was the alternative and
        #   was rejected on two counts: it would import botocore into a module that deliberately
        #   stays importable without the SDK, and it would make the conflict path unreachable from
        #   a test using this module's own client protocol, which is satisfied by any object with
        #   the right methods. Anything that is not a recognised conflict is re-raised unchanged.
        # WHY : Refactoring Rationale: the reader is called by its PUBLISHED name, where this line
        #   read `config._error_code`. This function is reached from `reserve_generation`, which
        #   this module publishes, so a public path here depended on a private name there -- and a
        #   private name carries no promise of surviving a rename, which would have turned every
        #   claim conflict into a re-raised failure at the one moment two concurrent runs met.
        if config.error_code(exc) in _CLAIM_CONFLICT_CODES:
            return False
        # WHY : Refactoring Rationale: a non-conflict failure was re-raised UNCHANGED, so every
        #   other provider fault on the claim path -- a denied `PutObject`, an expired credential,
        #   a connection timeout -- escaped `reserve_generation` as the SDK's own exception with
        #   the SDK's own text. It is now reported through allow-listed metadata like every other
        #   provider failure in this module, and the original is suppressed rather than chained.
        raise StagingServiceError(
            _provider_failure("the generation claim", bucket, key, exc)
        ) from None
    return True


def reserve_generation(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
    business_date: date,
    execution_token: str,
) -> int:
    """Reserve the generation number this execution will write, durably and exactly once.

    Purpose
    -------
    Allocate the baseline ``(+1)`` generation so that concurrent allocators cannot collide and a
    retried execution reuses the number its first attempt took, rather than consuming a fresh
    generation for a second copy of the same bytes.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for claim discovery and the conditional claim writes.
    settings : DatasetStagingSettings
        Validated bucket settings and the canonical prefix builder.
    domain : str
        Bounded-context segment.
    dataset : str
        Dataset-family segment.
    business_date : date
        The injected business date the new generation belongs to. Never derived from a clock.
    execution_token : str
        Non-blank token identifying the orchestrator execution. The same token must be presented
        by every retry of the same logical step, and a different one by every distinct step.

    Returns
    -------
    int
        The reserved generation number, between :data:`MIN_GENERATION` and
        :data:`MAX_GENERATION`. Calling again with the same token returns the same number.

    Raises
    ------
    GenerationRetentionError
        If the execution token is blank, or a discovered prefix carries an invalid business date.
    GenerationDiscoveryError
        If the generation space for that business date is exhausted, or a claim cannot be read.
    ConfigurationError
        If a path segment or the business date is unacceptable to the prefix builder.
    """
    # WHY : Assumptions: a blank token is REFUSED rather than defaulted. The token is the only
    #   thing that distinguishes "this is my retry, give me my generation back" from "this is a
    #   new step, give me a fresh one". Defaulting it -- to the empty string, a hostname or a
    #   process identifier -- would make every caller that omitted it share one identity, so two
    #   unrelated steps would silently reuse each other's generation. Failing here forces the
    #   caller to state which of the two cases it is in.
    if not isinstance(execution_token, str) or not execution_token.strip():
        raise GenerationRetentionError("generation reservation requires a non-blank token")

    claim_prefix = _claim_prefix(settings, domain, dataset, business_date)
    replayed = _existing_claim(client, settings.bucket, claim_prefix, execution_token)
    if replayed is not None:
        return replayed

    # WHY : Assumptions: the search starts from the highest number that is either already STAGED
    #   or already CLAIMED, so the two sources are considered together. Starting from the staged
    #   generations alone would re-offer a number another execution has claimed but not yet
    #   written, and starting from the claims alone would re-offer a number staged before claims
    #   existed. Taking the maximum of both is what makes the allocator correct across a bucket
    #   that predates this mechanism.
    staged = next_generation(client, settings, domain, dataset, business_date)
    claimed = _claimed_generations(client, settings.bucket, claim_prefix)
    candidate = max(staged, max(claimed) + 1 if claimed else MIN_GENERATION)

    while candidate <= MAX_GENERATION:
        claim_key = _claim_key(settings, domain, dataset, business_date, candidate)
        if _claim_generation(client, settings.bucket, claim_key, execution_token):
            return candidate
        # WHY : Trade-offs: a refused claim advances to the NEXT number rather than re-listing the
        #   prefix. Re-listing would cost a request per collision and could still be stale by the
        #   time the next claim is attempted, so the loop would be no more correct and slower.
        #   Advancing converges: each iteration either wins a number or proves that one is taken,
        #   and the four-digit ceiling bounds the work.
        candidate += 1

    raise GenerationDiscoveryError(
        f"the generation space for {claim_prefix} on {business_date.isoformat()} is exhausted at "
        f"gen={MAX_GENERATION:0{GENERATION_DIGITS}d}; no further generation can be reserved "
        f"for that business date"
    )


def _claimed_generations(
    client: S3StagingClient, bucket: str, claim_prefix: str
) -> tuple[int, ...]:
    """List the generation numbers already claimed under one claim prefix.

    Purpose
    -------
    Report which numbers are spoken for but possibly not yet staged, so the allocator does not
    offer a number another execution is in the middle of writing.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the listing.
    bucket : str
        Dataset bucket holding the claims.
    claim_prefix : str
        Prefix under which this family and business date keep their claims.

    Returns
    -------
    tuple[int, ...]
        The claimed generation numbers in ascending order, empty when none are claimed.

    Raises
    ------
    None
        An unparseable key under the prefix is ignored rather than failing the listing.
    """
    numbers: set[int] = set()
    for page in _paginate(
        client,
        "list_objects_v2",
        "the generation-claim listing",
        Bucket=bucket,
        Prefix=claim_prefix,
    ):
        for item in page.get("Contents", []):
            key = item.get("Key")
            if not isinstance(key, str):
                continue
            number = _claimed_generation(key, claim_prefix)
            if number is not None:
                numbers.add(number)
    return tuple(sorted(numbers))


def _delete_batch(
    client: S3StagingClient,
    bucket: str,
    objects: list[dict[str, str]],
) -> None:
    """Delete one API-sized batch and reject every partial failure.

    Purpose
    -------
    Remove a batch of explicitly named object versions and confirm the service removed all of
    them, so a retention run cannot report success over a prefix it only half deleted.

    Parameters
    ----------
    client : S3StagingClient
        S3 client performing the deletion.
    bucket : str
        Dataset bucket name.
    objects : list[dict[str, str]]
        Key/version-id pairs, no more than :data:`_MAX_DELETE_OBJECTS`.

    Returns
    -------
    None
        The batch is deleted in place.

    Raises
    ------
    GenerationRetentionError
        If S3 reports any per-object deletion error.
    """
    # WHY : Trade-offs: ``Quiet`` is requested, which suppresses the per-object SUCCESS entries
    #   while still returning the errors. The cost is that a successful call reports nothing to
    #   log; what it buys is that the response size stays bounded when a prefix holds many
    #   versions, and the deleted prefixes are reported by the caller anyway.
    try:
        response = client.delete_objects(
            Bucket=bucket,
            Delete={"Objects": objects, "Quiet": True},
        )
    except Exception as exc:  # noqa: BLE001 - re-raised as one sanitized staging failure
        # WHY : Assumptions: a whole-request refusal is translated here while a PER-OBJECT failure
        #   is read from the response below, because the service reports the two differently and
        #   they mean different things. A refused request is usually one missing permission; a
        #   partial failure is usually a versioning or object-lock condition on specific versions.
        raise StagingServiceError(
            _provider_failure(
                "the generation scratch", bucket, objects[0]["Key"] if objects else "", exc
            )
        ) from None
    errors = response.get("Errors", [])
    if errors:
        # WHY : Trade-offs: the message carries the distinct error CODES and not the keys they
        #   came from. A key under a generation prefix ends in the extract's own file name,
        #   which is operationally useful but unbounded in count, so a failure over a thousand
        #   versions would emit a thousand paths into a log. The codes are what distinguish the
        #   two cases an operator acts on differently -- a missing permission from a versioning
        #   or lock condition -- and the prefix being scratched is already reported by the
        #   caller, so the location is not lost.
        codes = sorted(
            {str(item.get("Code", "Unknown")) for item in errors if isinstance(item, dict)}
        )
        raise GenerationRetentionError(
            "S3 refused generation cleanup objects with error codes: " + ", ".join(codes)
        )


def delete_generation_prefix(
    client: S3StagingClient,
    bucket: str,
    generation_prefix: str,
) -> None:
    """Permanently delete every object version and marker beneath a generation.

    Purpose
    -------
    Scratch one rolled-off logical generation completely, so the prefix leaves no recoverable
    remnant -- which is what the baseline's ``SCRATCH`` operand meant.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used to list and delete versions.
    bucket : str
        Dataset bucket name.
    generation_prefix : str
        Complete ``dt=/gen=/`` prefix selected for SCRATCH.

    Returns
    -------
    None
        All discovered versions and delete markers are permanently removed.

    Raises
    ------
    GenerationRetentionError
        If any delete batch reports a partial failure.
    """
    # WHY : Assumptions: every VERSION and every DELETE MARKER is named explicitly, because the
    #   bucket is versioned. A plain delete on a versioned bucket does not remove anything -- it
    #   adds a delete marker and keeps the data as a noncurrent version -- so a retention run
    #   built that way would report the generation scratched while every byte of it remained
    #   billable and recoverable, which is the opposite of ``SCRATCH``. Delete markers are
    #   included as well so the prefix is left with nothing at all rather than with tombstones.
    pending: list[dict[str, str]] = []
    for page in _paginate(
        client,
        "list_object_versions",
        "the generation scratch listing",
        Bucket=bucket,
        Prefix=generation_prefix,
    ):
        for collection_name in ("Versions", "DeleteMarkers"):
            for item in page.get(collection_name, []):
                key = item.get("Key")
                version_id = item.get("VersionId")
                if not isinstance(key, str) or not isinstance(version_id, str):
                    continue
                pending.append({"Key": key, "VersionId": version_id})
                # WHY : Assumptions: the batch is flushed at the service's own limit of 1000
                #   keys per ``DeleteObjects`` request. Accumulating everything and sending one
                #   request would fail with a malformed-request error on any prefix holding more
                #   than that, which is reachable for the transaction families.
                if len(pending) == _MAX_DELETE_OBJECTS:
                    _delete_batch(client, bucket, pending)
                    pending = []
    if pending:
        _delete_batch(client, bucket, pending)


def prune_generations(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
    retention_count: int,
) -> tuple[str, ...]:
    """Scratch every logical generation older than the newest configured count.

    Purpose
    -------
    Enforce the ``LIMIT(5) SCRATCH`` analogue on logical generations, which bucket lifecycle
    configuration cannot express because separate ``gen=`` prefixes are separate keys rather
    than versions of one key.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for discovery and permanent deletion.
    settings : DatasetStagingSettings
        Validated bucket settings and the canonical prefix builder.
    domain : str
        Dataset bounded-context segment.
    dataset : str
        Dataset-family segment.
    retention_count : int
        Number of newest generations to preserve.

    Returns
    -------
    tuple[str, ...]
        Prefixes deleted, from oldest to newest. Empty when the family holds no more than
        ``retention_count`` generations.

    Raises
    ------
    GenerationRetentionError
        If the retention count is invalid, a discovered prefix carries an invalid date, or a
        delete reports a partial failure.
    ConfigurationError
        If either path segment is unacceptable to the prefix builder.
    """
    count = _require_retention_count(retention_count)
    generations = list_generation_prefixes(
        client, settings.bucket, family_prefix(settings, domain, dataset)
    )
    # WHY : Assumptions: the slice keeps the LAST ``count`` entries because
    #   :func:`list_generation_prefixes` returns them oldest first. Note that ``[:-count]`` is
    #   correct for the empty case as well -- with fewer generations than the count it yields
    #   nothing -- whereas an index arithmetic form such as ``[0:len - count]`` would produce a
    #   negative bound and silently select from the wrong end.
    stale = generations[:-count]
    for generation in stale:
        delete_generation_prefix(client, settings.bucket, generation.prefix)
    return tuple(generation.prefix for generation in stale)


def parse_object_uri(value: str) -> tuple[str, str] | None:
    """Split an ``s3://bucket/prefix`` location into its bucket and normalised key prefix.

    Purpose
    -------
    Let one deployment setting name either a filesystem directory or an object-storage prefix, so
    the same image reads extracts from a mounted volume in a checkout and from a bucket on Fargate
    without a second variable to keep consistent with the first.

    Parameters
    ----------
    value : str
        The location to classify, already stripped of surrounding whitespace by its caller.

    Returns
    -------
    tuple[str, str] or None
        The bucket and the key prefix -- with no leading or trailing separator, and empty when the
        URI names the bucket root -- or ``None`` when the value is not an object-storage URI at
        all, which is how a caller recognises a filesystem path.

    Raises
    ------
    ValueError
        If the value carries the object-storage scheme but names no bucket, or names one
        containing whitespace. A malformed URI is refused rather than treated as a path, because
        falling back would report "no such file or directory" for a location the operator plainly
        meant as a bucket.
    """
    # WHY : Assumptions: only the ``s3://`` scheme is recognised, and the alternative -- accepting
    #   the virtual-hosted or path-style HTTPS endpoint forms as well -- was rejected. Those forms
    #   embed a region and an endpoint, which this package resolves from the environment precisely
    #   so that no location literal can send a request somewhere the deployment did not configure;
    #   admitting them here would reintroduce that by the back door.
    if not value.startswith(_OBJECT_URI_SCHEME):
        return None
    remainder = value[len(_OBJECT_URI_SCHEME) :]
    bucket, _, prefix = remainder.partition("/")
    if not bucket or any(character.isspace() for character in bucket):
        raise ValueError(
            f"{value!r} carries the {_OBJECT_URI_SCHEME} scheme but names no usable bucket; the "
            f"accepted form is {_OBJECT_URI_SCHEME}bucket/prefix"
        )
    # WHY : Assumptions: the prefix is normalised to carry NO trailing separator here and the key
    #   is composed with one below, so a root written as ``s3://bucket/extracts`` and one written
    #   as ``s3://bucket/extracts/`` resolve to the same key. Both spellings are natural for an
    #   operator to write, and a doubled or missing separator produces a key that exists nowhere
    #   rather than an error naming the mistake.
    return bucket, prefix.strip("/")


def fetch_object_to_path(
    client: S3StagingClient,
    bucket: str,
    key: str,
    destination: Path,
) -> int:
    """Copy one object's bytes verbatim to a local path and report how many were written.

    Purpose
    -------
    Give a task with no operator filesystem a local copy of one source extract, so the staging and
    loading paths that require a path can be satisfied on Fargate.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the read.
    bucket : str
        Bucket holding the source extract.
    key : str
        Key of the source extract.
    destination : Path
        Local path to write. Its parent directory must already exist, which is the caller's
        scratch directory.

    Returns
    -------
    int
        The number of bytes written, which the caller may compare against a declared record
        geometry.

    Raises
    ------
    DatasetSourceError
        If the object cannot be read, if the local write fails, or if the number of bytes received
        disagrees with the length the service declared -- a truncated extract that was staged or
        loaded would be a silently short dataset, which is the one outcome this function must not
        produce.
    """
    # WHY : Assumptions: the body is streamed in bounded chunks rather than read with a single
    #   ``read()``, for the same reason ``stage_dataset_file`` streams its upload: peak memory then
    #   stays flat instead of scaling with the extract. The chunk size is the digest chunk this
    #   module already uses, so one constant governs both directions.
    try:
        response = client.get_object(Bucket=bucket, Key=key)
    except Exception as exc:  # noqa: BLE001 - re-raised below as a named source failure
        raise DatasetSourceError(
            f"the source extract s3://{bucket}/{key} could not be read: {exc}"
        ) from exc

    declared = response.get("ContentLength")
    written = 0
    try:
        body = response["Body"]
        # WHY : Assumptions: BOTH body shapes are accepted -- a streaming object with `read`, which
        #   is what the SDK returns, and a plain `bytes`, which is what an in-process double
        #   supplies and what any caller that has already materialised a small object would hand
        #   over. The alternative was to demand `read` and require every double to re-implement a
        #   stream, which buys nothing: the chunking below exists to keep peak memory flat over a
        #   cutover-sized extract, and a body that is ALREADY bytes has no memory left to save.
        # WHY : Trade-offs: the shape is tested with `hasattr` rather than by catching the
        #   `AttributeError` a `.read` on bytes raises. Catching it would be indistinguishable from
        #   a genuine transfer fault raised inside a real stream's own `read`, and this function
        #   reports every such fault as one named source failure -- so the two would arrive at the
        #   operator as the same diagnostic with different causes.
        reader = body if hasattr(body, "read") else io.BytesIO(body)
        with open(destination, "wb") as handle:
            while True:
                chunk = reader.read(_DIGEST_CHUNK_BYTES)
                if not chunk:
                    break
                handle.write(chunk)
                written += len(chunk)
    except OSError as exc:
        raise DatasetSourceError(
            f"the source extract s3://{bucket}/{key} could not be written to {destination}: {exc}"
        ) from exc
    except Exception as exc:  # noqa: BLE001 - re-raised below as a named source failure
        raise DatasetSourceError(
            f"the source extract s3://{bucket}/{key} could not be transferred: {exc}"
        ) from exc

    # WHY : Assumptions: the declared length is compared when the service states one and the check
    #   is skipped when it does not, rather than being required. A real client always reports
    #   ``ContentLength``; a narrow test double need not, and demanding it would make this function
    #   untestable without modelling a response header that has nothing to do with the transfer.
    #   Trade-offs: on such a double a truncation would go unnoticed here -- which is acceptable
    #   because the caller's record-geometry check is the second net, and it is the one that
    #   catches a short extract whatever the transport said about it.
    if isinstance(declared, int) and declared != written:
        raise DatasetSourceError(
            f"the source extract s3://{bucket}/{key} declares {declared} byte(s) and "
            f"{written} were received, so the copy is incomplete"
        )
    return written


@dataclass(frozen=True)
class _HeldSource:
    """Carry one open extract descriptor together with the identity recorded at open time.

    Purpose
    -------
    Keep the handle and the ``fstat`` result that describes it in a single value, so every stage
    of a staging step -- the digest, the geometry check, the transfer and the final
    re-examination -- refers to the same descriptor rather than re-resolving the path.

    Attributes
    ----------
    path : Path
        The path the descriptor was opened from. Carried for diagnostics only; it is never
        re-opened, because re-opening is the defect this type exists to prevent.
    handle : IO[bytes]
        The single open binary handle, read twice: once to digest, once to transfer.
    identity : os.stat_result
        The ``fstat`` result taken immediately after the open, compared again after the transfer.
    """

    path: Path
    handle: IO[bytes]
    identity: os.stat_result


def _require_real_filesystem(root: Path) -> None:
    """Refuse a staging root that lies on a kernel-generated filesystem.

    Purpose
    -------
    Close the one way a component-safe walk can still reach a pseudo-file: if the ROOT itself is
    under ``/proc`` or a sibling, every path beneath it is one, and no amount of no-symlink
    resolution helps.

    Parameters
    ----------
    root : Path
        The staging root, as supplied.

    Returns
    -------
    None
        Returns when the root is not under a denied mount point.

    Raises
    ------
    DatasetSourceError
        If the root is under any of :data:`PSEUDO_FILESYSTEM_ROOTS`.
    """
    # WHY : Assumptions: the comparison is on the LEXICALLY normalised absolute form and is made
    #   before anything is opened. Resolving symbolic links first would be stronger against a
    #   root deliberately pointed at `/proc` through a link, and the walk below closes that from
    #   the other side: every component beneath the root is opened with `O_NOFOLLOW`, and the leaf
    #   must sit on the same device as the root -- so a link into another filesystem is refused
    #   there rather than here.
    absolute = Path(os.path.normpath(root.absolute()))
    for denied in PSEUDO_FILESYSTEM_ROOTS:
        candidate = Path(denied)
        if absolute == candidate or candidate in absolute.parents:
            raise DatasetSourceError(
                f"the staging root {sanitized_for_log(root)} is under {denied}, which the kernel"
                " generates rather than stores; no dataset extract lives there, and a file read"
                " from it would publish process state rather than records"
            )


def _relative_components(source: Path, root: Path) -> tuple[str, ...]:
    """Reduce a source path to the components to walk beneath the root, refusing any escape.

    Purpose
    -------
    Decide, LEXICALLY and before any filesystem call, which names the walk is allowed to open --
    so that ``..``, an absolute path outside the root and a root-relative path are all resolved
    into the same one-or-more plain components, or refused.

    Parameters
    ----------
    source : Path
        The extract path, either absolute or relative to ``root``.
    root : Path
        The staging root the extract must live beneath.

    Returns
    -------
    tuple[str, ...]
        One or more path components, none of which is ``.``, ``..``, empty or absolute.

    Raises
    ------
    DatasetSourceError
        If the path is empty, carries a parent reference, or is absolute and does not lie beneath
        the root.
    """
    absolute_root = Path(os.path.normpath(root.absolute()))
    # WHY : Assumptions: the lexical reduction runs on `normpath`, which does NOT consult the
    #   filesystem, and that is the correct tool for this half of the job. `Path.resolve` would
    #   follow symbolic links -- so a link inside the root pointing outside it would be reduced to
    #   its target, and the target would then pass a beneath-the-root test it should fail. The
    #   kernel-enforced half of the check is the `O_NOFOLLOW` walk below; this half decides only
    #   which NAMES are eligible to be walked.
    if source.is_absolute():
        candidate = Path(os.path.normpath(source))
        try:
            relative = candidate.relative_to(absolute_root)
        except ValueError as exc:
            raise DatasetSourceError(
                f"the dataset source {sanitized_for_log(source)} is not beneath the staging root"
                f" {sanitized_for_log(root)}; an extract is staged from inside that directory and"
                " from nowhere else"
            ) from exc
    else:
        relative = Path(os.path.normpath(source))
    components = tuple(part for part in relative.parts if part not in {"", "."})
    if not components:
        raise DatasetSourceError(
            f"the dataset source {sanitized_for_log(source)} names no file beneath the staging"
            f" root {sanitized_for_log(root)}"
        )
    if os.pardir in components:
        raise DatasetSourceError(
            f"the dataset source {sanitized_for_log(source)} carries a parent reference, so it"
            " could name a file outside the staging root; every component must descend"
        )
    return components


def _descend(parent: int, component: str, source: Path) -> int:
    """Open one directory component beneath an already-open directory, following no link.

    Parameters
    ----------
    parent : int
        Descriptor of the directory to open relative to.
    component : str
        One path component, already validated as a plain name.
    source : Path
        The whole source path, named in a diagnostic.

    Returns
    -------
    int
        A descriptor for the child directory. The caller closes it.

    Raises
    ------
    DatasetSourceError
        If the component is absent, is a symbolic link, or is not a directory.
    """
    try:
        return os.open(
            component, os.O_RDONLY | os.O_NOFOLLOW | os.O_DIRECTORY | os.O_CLOEXEC, dir_fd=parent
        )
    except OSError as exc:
        raise DatasetSourceError(
            f"the dataset source {sanitized_for_log(source)} cannot be resolved beneath the"
            f" staging root: the component {sanitized_for_log(component)} is not a directory this"
            f" step may descend into ({exc.strerror})"
        ) from exc


@contextmanager
def _held_source(source: Path, root: Path) -> Iterator[_HeldSource]:
    """Open a local extract once, beneath the staging root, and hold that one descriptor.

    Purpose
    -------
    Resolve the extract with the kernel enforcing every step -- one component at a time, relative
    to an open directory, following no symbolic link anywhere on the way -- and then hold the
    single descriptor that resolution produced for the whole staging step, so the bytes digested
    and the bytes uploaded are provably the same file and that file is provably inside the root.

    Parameters
    ----------
    source : Path
        Local path of the extract to stage, absolute beneath ``root`` or relative to it.
    root : Path
        The staging root. The extract must resolve beneath it, on the same filesystem.

    Yields
    ------
    _HeldSource
        The open handle together with the ``fstat`` identity recorded at open time.

    Raises
    ------
    DatasetSourceError
        If either argument is not a :class:`~pathlib.Path`, the root is not a real directory or
        lies on a kernel-generated filesystem, the source does not resolve beneath the root, any
        component is a symbolic link, the resolved file is not a regular file, or it sits on a
        different filesystem from the root.
    """
    # WHY : Refactoring Rationale: this replaces a single `open(..., opener=O_NOFOLLOW)`, which
    #   guarded only the FINAL component. That was CWE-22 and CWE-367 together and it was
    #   reachable: an intermediate directory that was itself a symbolic link was traversed, so a
    #   link at any level above the extract redirected the read outside the staging hierarchy
    #   entirely -- and the code said so in a comment that judged closing it "disproportionate".
    #   It is not disproportionate; the previous shape let `/proc/self/environ` be staged into a
    #   bucket. Every component is now opened relative to the descriptor of its parent with
    #   `O_NOFOLLOW`, which is the `openat2(RESOLVE_BENEATH)` behaviour expressed with the calls
    #   the standard library publishes on the pinned interpreter.
    if not isinstance(source, Path) or not isinstance(root, Path):
        raise DatasetSourceError("dataset source path and staging root must both be paths")
    _require_real_filesystem(root)
    components = _relative_components(source, root)
    try:
        root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
    except OSError as exc:
        # WHY : Trade-offs: the ROOT is opened following symbolic links, unlike every component
        #   beneath it. The root is operator-supplied configuration -- a mount point or a volume
        #   path -- and refusing a symlinked mount point would refuse ordinary deployments for no
        #   gain: the root is the boundary being trusted, not a name an untrusted producer can
        #   influence. What matters is that nothing BELOW it may leave it, which the walk enforces
        #   and the device check confirms.
        raise DatasetSourceError(
            f"the staging root {sanitized_for_log(root)} cannot be opened as a directory:"
            f" {exc.strerror}"
        ) from exc
    descriptors = [root_fd]
    try:
        parent = root_fd
        for component in components[:-1]:
            parent = _descend(parent, component, source)
            descriptors.append(parent)
        # WHY : Assumptions: a MISSING SOURCE IS A HARD, RECORDED ERROR and never a silent empty
        #   upload. This is the discipline the reference emulator seeder states for the same job,
        #   and the failure it prevents is quiet: an empty object satisfies a later existence
        #   check and a row-count verification against it reports zero rows loaded, which reads as
        #   "the extract was empty" rather than "the extract was never there". Naming the path at
        #   the point of failure is what separates those two.
        try:
            leaf_fd = os.open(
                components[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC, dir_fd=parent
            )
        except OSError as exc:
            # WHY : Trade-offs: every open failure is reported through ONE message shape naming
            #   the path and the operating system's own reason, rather than being pre-classified
            #   into "absent", "is a link" and "unreadable" by separate probes. The cost is that
            #   the caller reads the errno text to tell them apart; what it buys is that the
            #   diagnosis describes the open that actually failed. A pre-classifying probe can
            #   disagree with the real open -- it passes, then the transfer's open fails -- which
            #   is the very split-brain this single-open design exists to remove.
            raise DatasetSourceError(
                f"the dataset source {sanitized_for_log(source)} cannot be read: {exc.strerror}"
            ) from exc
        descriptors.append(leaf_fd)
        # WHY : Assumptions: the descriptor is examined and REFUSED before it is wrapped in a file
        #   object, not after. `os.fdopen` on a directory raises `IsADirectoryError` from inside
        #   the standard library, so a directory source would escape this function as an untyped
        #   `OSError` rather than as the named refusal a caller catches -- and a directory is
        #   exactly one of the things this check exists to refuse. Examining the raw descriptor
        #   first keeps the diagnosis here.
        identity = os.fstat(leaf_fd)
        # WHY : Assumptions: the regular-file test runs on the HELD DESCRIPTOR through ``fstat``
        #   rather than on the path through ``stat``. A path-based test answers for whatever the
        #   name resolves to at that instant, which is not necessarily what the open descriptor
        #   refers to; ``fstat`` answers for the object being read. The refusal matters because a
        #   directory, FIFO or character device can be opened for reading and would then stage
        #   either nothing or an unbounded stream in place of a dataset.
        if not stat.S_ISREG(identity.st_mode):
            raise DatasetSourceError(
                f"the dataset source {sanitized_for_log(source)} is not a regular file"
            )
        # WHY : Assumptions: the extract must sit on the SAME FILESYSTEM as the root, and this is
        #   what makes "reject pseudo-filesystems" enforceable without parsing the mount table.
        #   procfs, sysfs and devtmpfs each have their own device number, so a bind mount of one
        #   inside the staging root -- the one remaining way a kernel-generated file could be
        #   reached by a walk that never leaves the root -- is refused here. Trade-offs: a
        #   legitimate nested mount inside the extracts directory is refused too. That is
        #   accepted: the root is a single volume holding one set of extracts, and an operator who
        #   genuinely needs a second volume points the root at it for that run.
        if identity.st_dev != os.fstat(root_fd).st_dev:
            raise DatasetSourceError(
                f"the dataset source {sanitized_for_log(source)} sits on a different filesystem"
                f" from the staging root {sanitized_for_log(root)}; an extract is staged from the"
                " root's own volume, so that a kernel-generated filesystem cannot be reached"
                " from inside it"
            )
        # WHY : Assumptions: the file object TAKES OWNERSHIP of the leaf descriptor, so the leaf
        #   is dropped from the list this function closes. Closing it twice would raise on the
        #   second close, on a path that is otherwise succeeding.
        descriptors.pop()
        handle = os.fdopen(leaf_fd, "rb")
    except BaseException:
        for descriptor in reversed(descriptors):
            os.close(descriptor)
        raise
    try:
        yield _HeldSource(path=source, handle=handle, identity=identity)
    finally:
        handle.close()
        for descriptor in reversed(descriptors):
            os.close(descriptor)


def _digest_held_source(held: _HeldSource) -> tuple[int, bytes]:
    """Measure a held extract's exact length and SHA-256 without altering a byte.

    Purpose
    -------
    Produce the two audit anchors a later readback is checked against, reading the already-open
    descriptor in binary and in bounded chunks so memory does not scale with the dataset.

    Parameters
    ----------
    held : _HeldSource
        The open extract handle to measure. Read from its start and left positioned at its end.

    Returns
    -------
    tuple[int, bytes]
        The exact byte length, and the raw 32-byte SHA-256 of those same bytes.

    Raises
    ------
    DatasetSourceError
        If the descriptor cannot be read to the end.
    """
    # WHY : Assumptions: the file is opened in BINARY mode -- ``"rb"``, never ``"r"`` -- and no
    #   encoding, newline or error-handling argument is supplied anywhere on this path. These
    #   extracts are not text. The export extract at
    #   ``app/data/EBCDIC/AWS.M2.CARDDEMO.EXPORT.DATA.PS`` is 250 000 bytes, exactly 500 records
    #   of 500 bytes, and uses ALL 256 distinct byte values, including 4 153 NUL (0x00), 5 LF
    #   (0x0A), 11 CR (0x0D) and 5 SUB (0x1A). Each of those counts names a different concrete
    #   corruption: a text-mode read newline-translates the 5 LF and 11 CR; a UTF-8 decode
    #   replaces every byte at or above 0x80 with the replacement character; the 5 SUB bytes
    #   truncate a text read on any platform honouring the DOS end-of-file convention; and the
    #   4 153 NUL bytes break anything treating the payload as a C string. Any one of them makes
    #   the SHA-256 recorded here disagree with the bytes on disk, which is exactly the check
    #   that would then be meaningless.
    # WHY : Refactoring Rationale: the raw digest is returned rather than a hex string, because
    #   two encodings of the same digest are now needed -- lower-case hex for the object metadata
    #   and the caller's report, and base64 for the service's own ``ChecksumSHA256`` parameter.
    #   Returning the bytes lets both be derived from one measurement, so the value the service
    #   verifies and the value recorded for audit cannot drift apart.
    digest = hashlib.sha256()
    byte_size = 0
    try:
        held.handle.seek(0)
        while True:
            chunk = held.handle.read(_DIGEST_CHUNK_BYTES)
            if not chunk:
                break
            byte_size += len(chunk)
            digest.update(chunk)
    except OSError as exc:
        raise DatasetSourceError(
            f"the dataset source {held.path} could not be read: {exc}"
        ) from exc
    return byte_size, digest.digest()


def _require_stable_source(held: _HeldSource, byte_size: int) -> None:
    """Confirm the held extract was not replaced or resized while it was being staged.

    Purpose
    -------
    Re-examine the descriptor after the transfer and refuse if the file it refers to has changed
    identity or length, so a substitution mid-flight is reported rather than staged silently.

    Parameters
    ----------
    held : _HeldSource
        The open extract handle, carrying the identity recorded when it was opened.
    byte_size : int
        The length measured during the digest pass.

    Returns
    -------
    None
        Returns nothing when the descriptor still describes the same unchanged file.

    Raises
    ------
    DatasetSourceError
        If the descriptor cannot be examined, or its identity, length or modification time no
        longer matches what was recorded.
    """
    # WHY : Assumptions: holding one descriptor removes the substitution that swaps the NAME, but
    #   not the one that rewrites the FILE the descriptor already refers to -- a writer with the
    #   same inode open can truncate or append while this step streams. So the identity is
    #   re-read at the end and compared. Inode and device catch a name rebound to a different
    #   file, size catches a truncate or append, and the nanosecond modification time catches an
    #   in-place rewrite that happens to preserve the length.
    try:
        current = os.fstat(held.handle.fileno())
    except OSError as exc:
        raise DatasetSourceError(
            f"the dataset source {held.path} could not be re-examined after transfer: {exc}"
        ) from exc
    recorded = held.identity
    if (current.st_ino, current.st_dev) != (recorded.st_ino, recorded.st_dev):
        raise DatasetSourceError(
            f"the dataset source {held.path} changed identity while it was being staged; "
            f"the staged object cannot be trusted to hold the digested bytes"
        )
    # WHY : Trade-offs: the size compared is the DIGESTED length rather than the length recorded
    #   at open time. The digest pass is what fixes the anchor the staged object is verified
    #   against, so that is the length the upload must agree with; comparing against the open-time
    #   size would pass a file that grew between the open and the digest and then stopped.
    if current.st_size != byte_size:
        raise DatasetSourceError(
            f"the dataset source {held.path} was {byte_size} bytes when digested and is "
            f"{current.st_size} bytes now; it was modified while it was being staged"
        )
    if current.st_mtime_ns != recorded.st_mtime_ns:
        raise DatasetSourceError(
            f"the dataset source {held.path} was modified in place while it was being staged; "
            f"the staged object cannot be trusted to hold the digested bytes"
        )


def _describe_record_geometry(layout_name: str) -> str:
    """Render a layout's field geometry for a diagnostic, disclosing no content.

    Purpose
    -------
    Name where each field of a record sits when reporting a length fault, so the report is
    actionable without any byte of the extract reaching it.

    Parameters
    ----------
    layout_name : str
        Name of a registered copybook layout.

    Returns
    -------
    str
        Space-separated field descriptions of the form ``NAME[start,end) KIND``, with any field
        marked sensitive rendered as withheld.

    Raises
    ------
    LayoutError
        If ``layout_name`` is not a registered layout.
    """
    # WHY : Trade-offs: the report names FIELD, OFFSET, LENGTH and KIND only -- never content.
    #   That is what ``FieldSpec.describe`` is for, and it is used rather than the dataclass
    #   ``repr`` because ``describe`` names four attributes explicitly and therefore cannot begin
    #   printing content if a component is added later. The compromise accepted is that a reader
    #   sees where staging failed but not what was there, which is the correct trade for these
    #   records: the account, card and customer extracts carry primary account numbers,
    #   card verification values and national identifiers, and a length fault is not a reason to
    #   emit one into a log line.
    # WHY : Alternatives Considered: ``layouts.mask_field`` is deliberately NOT used here, even
    #   though it is the package's redactor. It takes the field's content chunk and returns a
    #   redacted rendering of it, so calling it would require this module to read record content
    #   -- the one thing a byte-verbatim staging path must never do. The sensitivity FLAG that
    #   drives that redactor is honoured instead, so a withheld field is still marked as such.
    record = layouts.layout(layout_name)
    return " ".join(
        f"{field.describe()}{' withheld' if field.sensitive else ''}" for field in record.fields
    )


def _require_record_length(source: Path, byte_size: int, record_length: int, dataset: str) -> None:
    """Confirm a fixed-block extract's length is a whole multiple of its record length.

    Purpose
    -------
    Catch a truncated or over-long fixed-block extract before it is staged, since a partial
    trailing record is undetectable once the object is in the bucket and would decode as a
    short record much later.

    Parameters
    ----------
    source : Path
        Local path of the extract, named in the diagnostic.
    byte_size : int
        The extract's exact measured length.
    record_length : int
        The record length the family's baseline definition declares.
    dataset : str
        Dataset-family segment, named in the diagnostic.

    Returns
    -------
    None
        Returns nothing when the length divides exactly, INCLUDING when it is zero.

    Raises
    ------
    DatasetSourceError
        If ``byte_size`` is not a whole multiple of ``record_length``.
    """
    # WHY : Assumptions: this is ARITHMETIC ON A BYTE COUNT and not a decode. Nothing is read
    #   back, no field is interpreted and no code page is applied -- the check is
    #   ``byte_size % record_length``, which is why it is safe on a path that must not decode.
    # WHY : Refactoring Rationale: zero was REFUSED here and is now accepted, because zero is an
    #   exact multiple and this check measures divisibility rather than presence. The refusal was
    #   wrong in both directions at once. A genuinely empty period is a real state -- the daily
    #   transaction feed has one on any day with no activity, and the suite's own `empty_input`
    #   scenario exists for it -- so refusing zero turned a correct extract into a failed batch
    #   step. And the refusal was reachable only on the descriptor-derived path, so an explicitly
    #   supplied source bypassed it entirely: the one shape that needed the guard least had it, and
    #   the one that needed it most did not. Absence is a DIFFERENT fault and is still refused, by
    #   `_held_source`, which cannot open a file that is not there -- so "the extract was empty"
    #   and "the extract was never there" remain distinguishable, which is the property that
    #   mattered.
    remainder = byte_size % record_length
    if remainder:
        raise DatasetSourceError(
            f"the dataset source {sanitized_for_log(source)} for family"
            f" {sanitized_for_log(dataset)} holds {byte_size} bytes, which is not a whole multiple"
            f" of the declared {record_length}-byte record length ({remainder} trailing bytes)"
        )


def _staged_digest(client: S3StagingClient, bucket: str, key: str) -> tuple[str, int] | None:
    """Read the digest and length a generation key already carries, if it exists at all.

    Purpose
    -------
    Answer the only question that distinguishes a harmless retry from a destructive overwrite:
    does this key already hold bytes, and are they the bytes this attempt is about to send?

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the metadata read.
    bucket : str
        The dataset bucket.
    key : str
        The generation key being written.

    Returns
    -------
    tuple[str, int] | None
        The recorded hex digest and byte length, or ``None`` when the key does not exist. A key
        that exists but carries no recorded digest yields an empty digest and its content length,
        which the caller treats as an unverifiable existing payload.

    Raises
    ------
    StagingServiceError
        If the probe fails for any reason other than the object being absent.
    """
    try:
        response = client.head_object(Bucket=bucket, Key=key)
    except Exception as exc:  # noqa: BLE001 - classified by service code, then re-raised sanitized
        # WHY : Assumptions: absence is recognised by the service's own ERROR CODE through the
        #   published reader, not by exception type. Catching botocore's `ClientError` by type
        #   would import the SDK into a module that deliberately stays importable without it, and
        #   would make this path unreachable from a test using this module's own client protocol --
        #   which is satisfied by any object with the right methods. This is the same discipline
        #   `_claim_generation` applies to a conditional-write conflict.
        if config.error_code(exc) in _ABSENT_OBJECT_CODES:
            return None
        raise StagingServiceError(
            _provider_failure("the existence probe", bucket, key, exc)
        ) from None
    metadata = response.get("Metadata") if isinstance(response, Mapping) else None
    recorded = ""
    if isinstance(metadata, Mapping):
        candidate = metadata.get(_SHA256_METADATA_KEY)
        if isinstance(candidate, str):
            recorded = candidate
    length = response.get("ContentLength") if isinstance(response, Mapping) else None
    return recorded, length if isinstance(length, int) else -1


# WHY : Refactoring Rationale: a second `FetchedExtract` dataclass was declared here, carrying
#   `path`, `byte_size`, `sha256` and `recorded_sha256` but no `key`. It is withdrawn and its one
#   distinctive member is folded into the surviving declaration below. Two classes of one name in
#   one module is not a duplication Python reports: the later binding silently replaces the
#   earlier, so whichever constructor was written against the withdrawn shape raised a keyword
#   error at run time and no import-time check saw it.


def fetch_extract(
    client: S3StagingClient,
    *,
    bucket: str,
    key: str,
    destination: Path,
    record_length: int | None = None,
) -> FetchedExtract:
    """Materialise one extract from the object store and prove the bytes arrived intact.

    Purpose
    -------
    Give the migration commands a source that does not depend on a filesystem the task does not
    have. The batch task runs on Fargate with no volume mount and an image that ships no extract,
    so a root naming a local directory named a file that could not exist. This reads the object the
    export (or a prior staging step) delivered, writes it to task-local storage, and verifies the
    result against the object's OWN recorded length and digest before any caller decodes a record.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the metadata probe and the body read.
    bucket : str
        The bucket holding the extract.
    key : str
        The extract's key.
    destination : Path
        Where to write the bytes. Must not already exist; the parent directory must.
    record_length : int | None, optional
        Declared fixed-record width. When given, a length that is not a whole multiple of it is
        refused, which catches a truncated transfer that the digest check cannot see because a
        truncated object has a digest of its own.

    Returns
    -------
    FetchedExtract
        The written path and the measured length and digest.

    Raises
    ------
    DatasetSourceError
        If the object does not exist, if the bytes written disagree with the length or the digest
        the object recorded, if the length contradicts ``record_length``, or if the destination
        cannot be written.
    StagingServiceError
        If the provider refuses the probe or the read, carrying allow-listed metadata only.
    """
    probe = _staged_digest(client, bucket, key)
    if probe is None:
        # WHY : Assumptions: absence is a SOURCE fault and not a provider fault, so it is reported
        #   as `DatasetSourceError`. The distinction drives the caller's retry decision: a provider
        #   refusal is worth retrying and an extract that was never delivered is not, and reporting
        #   both the same way spends every attempt reaching the diagnosis the first one had.
        raise DatasetSourceError(
            f"the extract {sanitized_for_log(key)} is not present in bucket "
            f"{sanitized_for_log(bucket)}, so it cannot be read"
        )
    recorded_digest, recorded_length = probe

    try:
        response = client.get_object(Bucket=bucket, Key=key)
    except Exception as exc:  # noqa: BLE001 - re-raised as a sanitized staging-domain failure
        raise StagingServiceError(_provider_failure("the extract read", bucket, key, exc)) from None

    body = response.get("Body") if isinstance(response, Mapping) else None
    if body is None:
        raise StagingServiceError(
            f"the extract read of {sanitized_for_log(key)} in bucket {sanitized_for_log(bucket)}"
            " returned no body"
        )

    digest = hashlib.sha256()
    written = 0
    # WHY : Assumptions: the destination is created with O_EXCL and O_NOFOLLOW, so this can neither
    #   overwrite an existing file nor be redirected through a symbolic link a co-tenant process
    #   planted in the work directory. The same discipline the local staging path applies to
    #   reading is applied here to writing, because a materialised extract is about to be treated
    #   as trusted input and the file it lands in is the whole basis of that trust.
    try:
        handle_fd = os.open(
            destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600
        )
    except OSError as exc:
        raise DatasetSourceError(
            f"the extract {sanitized_for_log(key)} could not be written to task-local storage:"
            f" {exc.strerror or type(exc).__name__}"
        ) from None
    try:
        with os.fdopen(handle_fd, "wb") as handle:
            for chunk in _body_chunks(body, bucket, key):
                written += len(chunk)
                digest.update(chunk)
                handle.write(chunk)
    except OSError as exc:
        raise DatasetSourceError(
            f"the extract {sanitized_for_log(key)} could not be written to task-local storage:"
            f" {exc.strerror or type(exc).__name__}"
        ) from None

    if recorded_length >= 0 and written != recorded_length:
        raise DatasetSourceError(
            f"the extract {sanitized_for_log(key)} transferred {written} byte(s) where the object"
            f" declares {recorded_length}, so the transfer was incomplete"
        )
    computed = digest.hexdigest()
    # WHY : Trade-offs: the digest comparison is SKIPPED when the object carries no recorded digest,
    #   rather than the fetch being refused. An inbound export delivered by the mainframe export
    #   carries no `carddemo-sha256` metadata -- only this package's own staging step writes that --
    #   so refusing would make the object-store inbox unusable for the one delivery it exists for.
    #   The length check above still applies to that case, and the record-length check below is what
    #   catches a truncated fixed-block transfer, so an unverifiable digest is not an unchecked
    #   transfer. The measured digest is returned either way so the caller can record it.
    if recorded_digest and computed != recorded_digest:
        raise DatasetSourceError(
            f"the extract {sanitized_for_log(key)} hashes to {computed} where the object records"
            f" {sanitized_for_log(recorded_digest)}, so these are not the bytes that were staged"
        )
    if record_length is not None and record_length > 0 and written % record_length:
        raise DatasetSourceError(
            f"the extract {sanitized_for_log(key)} holds {written} byte(s), which is not a whole"
            f" multiple of the declared record length {record_length}"
        )
    return FetchedExtract(
        key=key,
        path=destination,
        byte_size=written,
        sha256=computed,
        recorded_sha256=recorded_digest,
    )


def _body_chunks(body: Any, bucket: str, key: str) -> Iterator[bytes]:
    """Yield an object body in bounded chunks, whatever shape the client returned it in.

    Purpose
    -------
    Keep memory bounded by the chunk size rather than by the dataset, and accept both the streaming
    body a real client returns and the plain bytes a narrow test double supplies.

    Parameters
    ----------
    body : Any
        The ``Body`` member of a get-object response: an object with ``read(n)``, or bytes.
    bucket : str
        The bucket, used only to name the failure.
    key : str
        The key, used only to name the failure.

    Yields
    ------
    bytes
        Successive chunks of at most :data:`_DIGEST_CHUNK_BYTES`, in order.

    Raises
    ------
    StagingServiceError
        If the body cannot be read, or is neither readable nor bytes.
    """
    # WHY : Alternatives Considered: requiring a streaming body and refusing bytes. Rejected
    #   because it would force every test double to implement a file-like object to exercise a
    #   check about digests and lengths, and the chunking loop would then be reachable only from a
    #   double whose only purpose was to satisfy it. Accepting both keeps ONE code path measuring
    #   the bytes, whichever shape they arrived in.
    if isinstance(body, bytes | bytearray):
        payload = bytes(body)
        for offset in range(0, len(payload), _DIGEST_CHUNK_BYTES):
            yield payload[offset : offset + _DIGEST_CHUNK_BYTES]
        return
    reader = getattr(body, "read", None)
    if reader is None:
        raise StagingServiceError(
            f"the extract read of {sanitized_for_log(key)} in bucket {sanitized_for_log(bucket)}"
            " returned a body that is neither readable nor bytes"
        )
    while True:
        try:
            chunk = reader(_DIGEST_CHUNK_BYTES)
        except Exception as exc:  # noqa: BLE001 - re-raised as a sanitized staging-domain failure
            raise StagingServiceError(
                _provider_failure("the extract read", bucket, key, exc)
            ) from None
        if not chunk:
            return
        yield bytes(chunk)


def stage_dataset_file(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
    business_date: date,
    generation: int,
    source: Path,
    *,
    staging_root: Path,
    object_name: str | None = None,
    retention_count: int = DEFAULT_GENERATION_RETENTION,
    record_length: int | None = None,
) -> StagedObject:
    """Stage a local extract's bytes verbatim as one generation, then enforce retention.

    Purpose
    -------
    Copy one extract from disk into its generation prefix byte for byte, record the exact length
    and SHA-256 of what was sent, and scratch the generations that roll off.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the write, discovery and cleanup.
    settings : DatasetStagingSettings
        Validated bucket and prefix settings.
    domain : str
        Dataset bounded-context segment.
    dataset : str
        Dataset-family segment.
    business_date : date
        Injected business date encoded into the staged prefix. Never derived from a clock.
    generation : int
        Generation number to write, between :data:`MIN_GENERATION` and :data:`MAX_GENERATION`.
        Use :func:`next_generation` to resolve the baseline ``(+1)`` form.
    source : Path
        Local path of the extract to stage, absolute beneath ``staging_root`` or relative to it.
    staging_root : Path
        Directory the extract must resolve beneath. Every component below it is opened with no
        symbolic-link following, so no name can redirect the read outside this directory.
    object_name : str or None
        Leaf name beneath the generation prefix. Defaults to the source file's own name.
    retention_count : int
        Number of newest logical generations to keep. Defaults to
        :data:`DEFAULT_GENERATION_RETENTION`.
    record_length : int or None
        Declared record length to check the extract's length against, or ``None`` to stage
        without a length check.

    Returns
    -------
    StagedObject
        The written key and prefix, the business date and generation it carries, the exact byte
        length and SHA-256 of the staged bytes, and any prefixes scratched afterwards. A key that
        already holds exactly these bytes is reported as staged without being rewritten.

    Raises
    ------
    DatasetSourceError
        If the source does not resolve beneath ``staging_root``, is not a regular file, cannot be
        read, sits on a different filesystem from the root, or contradicts ``record_length``.
    GenerationConflictError
        If the generation key already holds a payload whose digest differs from this extract's.
    StagingServiceError
        If the object store refuses or fails the existence probe or the write.
    GenerationRetentionError
        If the object name, generation or retention count is unacceptable, or cleanup reports a
        partial failure.
    ConfigurationError
        If a path segment or the business date is unacceptable to the prefix builder.
    """
    # WHY : Assumptions: everything from ``staging_root`` onwards is KEYWORD-ONLY, and the marker
    #   was added when the root was introduced rather than left for later. Inserting a required
    #   parameter into a positional list silently re-binds every argument after it -- a caller that
    #   passed the object name positionally would have handed it to the root and received "object
    #   name is not text" from a check three calls away, which is exactly what happened to this
    #   suite before the marker went in. Keyword-only makes that mistake a TypeError at the call.
    leaf = _require_object_name(object_name if object_name is not None else Path(source).name)
    number = _require_staged_generation(generation)
    count = _require_retention_count(retention_count)

    prefix = settings.generation_prefix(domain, dataset, business_date, number)
    key = f"{prefix}{leaf}"

    # WHY : Refactoring Rationale: the extract is opened ONCE and every subsequent stage works
    #   from that one descriptor. The earlier shape called a readability probe, then a digest
    #   helper, then the transfer, each re-opening the pathname -- three opens that could resolve
    #   to three different files. Holding the descriptor is what makes the digest an anchor for
    #   the bytes actually uploaded rather than for whatever the name meant at digest time.
    with _held_source(Path(source), Path(staging_root)) as held:
        # WHY : Trade-offs: the extract is read TWICE from the held descriptor -- once to digest
        #   it, once to transfer it -- and the extra pass is accepted deliberately. Digesting the
        #   stream as it uploaded would read once, but the digest would then describe whatever the
        #   SDK happened to send rather than what is on disk, which is self-reported and cannot
        #   detect the very corruption it exists to detect. Buffering the whole extract to hash
        #   and send one copy was the other alternative and was rejected because memory would then
        #   scale with the dataset. Two bounded passes over ONE descriptor give an anchor a
        #   readback can be checked against independently, at no re-open risk.
        byte_size, raw_digest = _digest_held_source(held)
        sha256 = raw_digest.hex()
        if record_length is not None:
            try:
                _require_record_length(held.path, byte_size, record_length, dataset)
            except DatasetSourceError as exc:
                registered = _GENERATION_FAMILIES.get(dataset)
                layout_name = registered.layout_name if registered is not None else None
                if layout_name is None:
                    raise
                raise DatasetSourceError(
                    f"{exc}; the {layout_name} record is laid out as "
                    f"{_describe_record_geometry(layout_name)}"
                ) from exc

        # WHY : Refactoring Rationale: the key is PROBED before it is written, where the earlier
        #   shape wrote unconditionally and its own comment argued that re-staging was harmless
        #   because "a retry that re-writes the same key with the same bytes is harmless on a
        #   versioned bucket". The premise was never checked: nothing compared the bytes, so the
        #   same code path also silently overwrote a generation with DIFFERENT bytes. That is the
        #   one thing a generation must not permit -- it is a point-in-time record that a
        #   verification pass compares against, and the digest an operator read from an earlier
        #   run's log would then describe a payload the bucket no longer holds. The two cases are
        #   now separated: identical bytes are an idempotent success and are not rewritten at all,
        #   and different bytes are refused.
        existing = _staged_digest(client, settings.bucket, key)
        if existing is not None:
            recorded, recorded_length = existing
            if recorded == sha256:
                # WHY : Trade-offs: the write is SKIPPED rather than repeated. Repeating it would
                #   also be correct and would cost one full transfer of the extract on every
                #   retried branch; skipping it additionally avoids creating a noncurrent version
                #   that carries no new information and consumes one of the five the lifecycle
                #   rule retains. Retention still runs below, because a retry must leave the
                #   family in the same state a first success would.
                deleted = prune_generations(client, settings, domain, dataset, count)
                return StagedObject(
                    key=key,
                    prefix=prefix,
                    business_date=business_date,
                    generation=number,
                    byte_size=byte_size,
                    sha256=sha256,
                    deleted_generation_prefixes=deleted,
                )
            raise GenerationConflictError(
                f"generation {number:0{GENERATION_DIGITS}d} of"
                f" {sanitized_for_log(domain)}/{sanitized_for_log(dataset)} already holds"
                f" {recorded_length} byte(s) with digest"
                f" {sanitized_for_log(recorded) or '(none recorded)'}, and this extract is"
                f" {byte_size} byte(s) with digest {sha256}; a generation is a point-in-time"
                " record and is never rewritten. Stage the new extract as the next generation"
                " instead"
            )

        # WHY : Assumptions: the payload is streamed DIRECTLY FROM THE HELD DESCRIPTOR, with no
        #   temporary copy anywhere. Round-tripping a binary EBCDIC extract through an
        #   intermediate write is precisely how its NUL bytes and sign-overpunch bytes get
        #   mangled, and the reference emulator seeder avoids a temp copy for that exact reason.
        #   The handle is binary, so the bytes the service receives are the bytes the digest
        #   above measured -- and it is rewound explicitly, because the digest pass left it at
        #   end of file and a stream starting there would upload nothing.
        try:
            held.handle.seek(0)
            client.put_object(
                Bucket=settings.bucket,
                Key=key,
                Body=held.handle,
                ContentType=_STAGED_CONTENT_TYPE,
                # WHY : Trade-offs: ``ContentLength`` is stated from the measured size. It costs
                #   one more argument and it buys a service-side rejection if the stream ends
                #   early, so a truncated transfer fails the write instead of producing a short
                #   object that a later readback would have to catch.
                ContentLength=byte_size,
                # WHY : Alternatives Considered: the digest is ALSO supplied as the service's own
                #   ``ChecksumSHA256`` parameter, base64 as that parameter requires, and not only
                #   written as metadata below. The two are not interchangeable. Metadata is an
                #   opaque string the service stores without ever reading, so on its own it
                #   records a claim that the upload was intact; the checksum parameter makes S3
                #   recompute SHA-256 over the bytes it received and REJECT the write when they
                #   disagree. Relying on metadata alone was the earlier shape and it could not
                #   detect an in-flight corruption at all -- the object would land, carrying a
                #   digest describing bytes it did not contain, and the mismatch would surface
                #   only when something later chose to verify. The pinned botocore 1.43.50
                #   accepts this parameter on ``PutObject``.
                ChecksumSHA256=base64.b64encode(raw_digest).decode("ascii"),
                # WHY : Trade-offs: the anchors are also written as object metadata, not only
                #   returned to this caller. The cost is two small headers per object; what it
                #   buys is that the verification pass can read the expected digest from the
                #   object itself rather than from whatever the staging step happened to log, so
                #   the check stays authoritative rather than self-reported even when the two
                #   run in different processes on different days.
                Metadata={
                    _SHA256_METADATA_KEY: sha256,
                    _BYTE_SIZE_METADATA_KEY: str(byte_size),
                },
            )
        except OSError as exc:
            raise DatasetSourceError(
                f"the dataset source {sanitized_for_log(held.path)} could not be transferred:"
                f" {exc.strerror}"
            ) from exc
        except Exception as exc:  # noqa: BLE001 - re-raised below as one sanitized staging failure
            # WHY : Refactoring Rationale: only `OSError` was caught here, so every provider and
            #   transport failure -- `AccessDenied`, `NoSuchBucket`, an expired credential, a
            #   checksum mismatch the service itself detected, a connection timeout -- escaped as
            #   the SDK's own exception with the SDK's own text. `cli.py` logs that text, so the
            #   assumed-role ARN, the account identifier and the resolved endpoint reached a
            #   retained log. They are now reported through allow-listed metadata, and the provider
            #   exception is suppressed with `from None` so nothing logging `exc_info` can recover
            #   the original text through `__cause__`.
            raise StagingServiceError(
                _provider_failure("the staged write", settings.bucket, key, exc)
            ) from None

        # WHY : Assumptions: the descriptor is re-examined AFTER the transfer rather than before
        #   it. A check beforehand can only report the state at that moment, which the transfer
        #   then invalidates; checking afterwards is what establishes that nothing moved across
        #   the whole window the digest is meant to cover.
        _require_stable_source(held, byte_size)

    # WHY : Trade-offs: re-staging the SAME BYTES is tolerated rather than refused, and re-staging
    #   different bytes is refused above. Step Functions may retry a ``Map`` branch, and turning a
    #   recoverable retry into a failed execution would be the wrong trade -- the baseline takes
    #   the same view of a re-run: ``app/jcl/DEFGDGB.jcl`` follows every one of its six definitions
    #   with ``IF LASTCC=12 THEN SET MAXCC=0`` at L29, L35, L41, L47, L53 and L59, so re-running it
    #   is a deliberate no-op rather than an error. (That guard is specific to that job;
    #   ``DEFGDGD.jcl`` and ``DALYREJS.jcl`` carry none, and ``app/jcl/ACCTFILE.jcl:27`` uses the
    #   related ``IF MAXCC LE 08 THEN SET MAXCC = 0``.) What the baseline does NOT do is let a
    #   re-run write different content into a catalogued generation, which is why the digest decides
    #   which of the two cases a retry is.
    deleted = prune_generations(client, settings, domain, dataset, count)
    return StagedObject(
        key=key,
        prefix=prefix,
        business_date=business_date,
        generation=number,
        byte_size=byte_size,
        sha256=sha256,
        deleted_generation_prefixes=deleted,
    )


def stage_family_file(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    family_name: str,
    business_date: date,
    source: Path,
    *,
    staging_root: Path,
    generation: int | None = None,
    object_name: str | None = None,
    retention_count: int | None = None,
    check_record_length: bool = False,
    execution_token: str | None = None,
) -> StagedObject:
    """Stage a local extract into one registered generation family.

    Purpose
    -------
    Stage by family name, so the bounded-context domain, the retention limit and the declared
    record length all come from the registry instead of from the caller, and resolve the
    baseline ``(+1)`` form when no generation is given.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the write, discovery and cleanup.
    settings : DatasetStagingSettings
        Validated bucket and prefix settings.
    family_name : str
        One of the ten registered dataset path segments; see :func:`family_names`.
    business_date : date
        Injected business date encoded into the staged prefix. Never derived from a clock.
    source : Path
        Local path of the extract to stage, absolute beneath ``staging_root`` or relative to it.
    staging_root : Path
        Directory the extract must resolve beneath, component by component, following no symbolic
        link.
    generation : int or None
        Explicit generation number, or ``None`` to reserve the next one -- the baseline ``(+1)``
        form. Reserving requires ``execution_token``.
    object_name : str or None
        Leaf name beneath the generation prefix. Defaults to the source file's own name.
    retention_count : int or None
        Number of newest logical generations to keep, or ``None`` to use the family's own
        declared limit.
    check_record_length : bool
        When true, require the extract's length to be a whole multiple of the family's declared
        record length.
    execution_token : str or None
        Token identifying the orchestrator execution, required when ``generation`` is ``None``.
        Presenting the same token again returns the same generation, so a retried step re-writes
        one key instead of consuming a second generation for identical bytes.

    Returns
    -------
    StagedObject
        The same report :func:`stage_dataset_file` produces.

    Raises
    ------
    GenerationRetentionError
        If the family name is unknown, the generation or retention count is unacceptable, or no
        execution token was supplied for a reservation.
    GenerationDiscoveryError
        If the family's generation space for that business date is exhausted.
    DatasetSourceError
        If the source does not resolve beneath ``staging_root``, cannot be staged, or contradicts
        the family's declared record length.
    GenerationConflictError
        If the generation already holds a payload with a different digest.
    StagingServiceError
        If the object store refuses or fails a request on the staging path.
    ConfigurationError
        If the business date is unacceptable to the prefix builder.
    """
    # WHY : Assumptions: the family name is resolved against the registry FIRST, before a byte
    #   is read or a listing is issued. Staging copies bytes verbatim, so this function cannot
    #   detect a wrong family from the payload -- the name is the only thing deciding which
    #   prefix the object lands under, and a typo would otherwise put a real extract somewhere
    #   nothing reads and nothing ages out.
    registered = family(family_name)

    # WHY : Alternatives Considered: the record-length check is OPT-IN rather than always on.
    #   Applying it unconditionally was rejected on measured evidence: the thirteen EBCDIC
    #   datasets are fixed-block with no terminators, so their lengths do divide exactly -- the
    #   account extract is 15 000 bytes of 300-byte records -- but the nine ASCII datasets are
    #   newline-delimited, and their terminators are not even uniform between files, so their
    #   byte lengths are NOT multiples of the copybook record length and an unconditional check
    #   would refuse a perfectly good extract. Staging itself is indifferent: both forms are
    #   uploaded byte for byte, and record-boundary interpretation belongs to the codecs and
    #   readers rather than here.
    declared_length = registered.record_length if check_record_length else None

    # WHY : Refactoring Rationale: when no generation is given the number is RESERVED rather than
    #   merely discovered. :func:`next_generation` answers "what would a ``(+1)`` reference
    #   resolve to?" and is still the right answer to that question, but it is a read: two
    #   branches asking it concurrently receive the same number, and a retry asking it after its
    #   first attempt already wrote receives the number AFTER that write and stages a duplicate
    #   generation of identical bytes. :func:`reserve_generation` makes the answer exclusive and
    #   replayable, which is why allocation goes through it and the plain query does not.
    if generation is None:
        # WHY : Assumptions: allocation REQUIRES an execution token and refuses to invent one.
        #   Without it there is no way to tell a retry from a new step, so the reservation could
        #   not be idempotent and the duplicate-generation defect would survive the change.
        if execution_token is None:
            raise GenerationRetentionError(
                "staging without an explicit generation requires an execution token, so a retry "
                "can reuse the generation its first attempt reserved"
            )
        number = reserve_generation(
            client,
            settings,
            registered.domain,
            registered.dataset,
            business_date,
            execution_token,
        )
    else:
        number = generation
    return stage_dataset_file(
        client=client,
        settings=settings,
        domain=registered.domain,
        dataset=registered.dataset,
        business_date=business_date,
        generation=number,
        source=source,
        staging_root=staging_root,
        object_name=object_name,
        retention_count=(
            registered.retention_limit if retention_count is None else retention_count
        ),
        record_length=declared_length,
    )


@dataclass(frozen=True, slots=True)
class FetchedExtract:
    """One source extract materialised from object storage onto a local path.

    Purpose
    -------
    Carry what a caller has to know about a fetched extract -- where it came from, where it now
    is, how many bytes arrived and their digest -- so a staging step can log the transfer and a
    later step can read the same bytes without asking the service again.

    Parameters
    ----------
    key : str
        Full object key the extract was read from.
    path : Path
        Local path the bytes were written to.
    byte_size : int
        Exact number of bytes written.
    sha256 : str
        Lower-case hexadecimal SHA-256 of the bytes written, computed here rather than taken
        from the service.

    Returns
    -------
    FetchedExtract
        A frozen record of the transfer.

    Raises
    ------
    None
        The fetch validates before constructing this.

    Attributes
    ----------
    key : str
        As the parameter of the same name.
    path : Path
        As the parameter of the same name.
    byte_size : int
        As the parameter of the same name.
    sha256 : str
        As the parameter of the same name.
    """

    key: str
    path: Path
    byte_size: int
    sha256: str
    # WHY : Assumptions: this member DEFAULTS to the empty string, so a caller that fetched an
    #   object carrying no published digest constructs the same type without stating an absence.
    #   The empty string rather than None because every consumer compares it to a hex digest, and
    #   a two-valued type would put an `is None` check at each of those sites for one case.
    recorded_sha256: str = ""

    def describe(self) -> str:
        """Render the transfer as one log-safe line.

        Parameters
        ----------
        None
            Reads the four members.

        Returns
        -------
        str
            The key, the byte count and the digest. No byte of the extract itself appears,
            because these extracts carry account balances, card numbers and, in one case, a
            plaintext credential.

        Raises
        ------
        None
            Rendering cannot fail.
        """
        return f"{self.key} ({self.byte_size} bytes, sha256 {self.sha256})"


def extract_source_key(prefix: str, source_object: str) -> str:
    """Compose the object key one registered source extract is read from.

    Purpose
    -------
    Join the deployment's source-extract prefix to the file name the seed-dataset registry
    declares, in ONE place, so the orchestrator, the runbook and this module cannot each
    normalise the separator differently.

    Parameters
    ----------
    prefix : str
        The source-extract prefix, with or without a trailing separator. An empty or blank value
        is refused rather than treated as the bucket root.
    source_object : str
        The extract's file name as the registry declares it.

    Returns
    -------
    str
        The full object key, exactly one separator between the two parts.

    Raises
    ------
    DatasetSourceError
        If either part is blank, if the prefix is absolute, or if the file name carries a path
        separator. A name with a separator would silently move the read out of the prefix the
        deployment granted, which is the one error here that could read another prefix's object
        and report success.
    """
    cleaned_prefix = prefix.strip()
    cleaned_object = source_object.strip()
    if not cleaned_prefix:
        raise DatasetSourceError(
            "a source-extract prefix is required; reading from the bucket root would put the "
            "extracts outside the prefix the deployment provisions and grants read access to"
        )
    if not cleaned_object:
        raise DatasetSourceError("a source extract file name is required")
    # WHY : Assumptions: a LEADING separator is refused rather than stripped. S3 keys have no
    #   root, so `/migration/source/` is a key beginning with an empty segment -- a different,
    #   working prefix that nothing writes to. Refusing it names the mistake; stripping it would
    #   silently accept two spellings of one intent and hide a copy-paste from a filesystem path.
    if cleaned_prefix.startswith("/"):
        raise DatasetSourceError(
            f"the source-extract prefix {prefix!r} begins with a separator; an object key has no "
            "root, so a leading separator names a different prefix rather than the same one"
        )
    if "/" in cleaned_object or "\\" in cleaned_object:
        raise DatasetSourceError(
            f"the source extract name {source_object!r} carries a path separator; the name comes "
            "from the seed-dataset registry and must stay inside the granted prefix"
        )
    return f"{cleaned_prefix.rstrip('/')}/{cleaned_object}"


def _require_published_digest(
    response: Mapping[str, Any], key: str, byte_size: int, digest: bytes
) -> None:
    """Check a fetched extract against whatever length and digest the object publishes.

    Purpose
    -------
    Turn the service's own metadata into a transfer check, so a truncated or altered extract is
    refused at the fetch rather than decoded into plausible wrong values further down.

    Parameters
    ----------
    response : Mapping[str, Any]
        The ``GetObject`` response mapping.
    key : str
        The object key, for the diagnostics.
    byte_size : int
        Bytes actually written locally.
    digest : bytes
        Raw SHA-256 of the bytes actually written.

    Returns
    -------
    None
        Returns when every published value that exists agrees with what arrived.

    Raises
    ------
    DatasetSourceError
        If the published content length, the service checksum or this package's own metadata
        digest disagrees with what was written.
    """
    declared_length = response.get("ContentLength")
    if isinstance(declared_length, int) and declared_length != byte_size:
        raise DatasetSourceError(
            f"the extract at {key} declares {declared_length} bytes and {byte_size} arrived, so "
            "the transfer was truncated or the object changed while it was being read"
        )
    # WHY : Assumptions: the SERVICE checksum is compared only when the object carries one. An
    #   object uploaded by `aws s3 sync` may publish a CRC32 rather than a SHA-256, and one
    #   uploaded before checksums were requested publishes none at all, so an absent value is a
    #   property of the upload rather than a defect. What is refused is a PRESENT value that
    #   disagrees, because that is corruption the service itself has detected the other half of.
    published = response.get("ChecksumSHA256")
    if isinstance(published, str) and published.strip():
        arrived = base64.b64encode(digest).decode("ascii")
        if published.strip() != arrived:
            raise DatasetSourceError(
                f"the extract at {key} publishes a SHA-256 checksum that does not match the "
                "bytes that arrived, so the object was altered or the transfer corrupted them"
            )
    # WHY : Refactoring Rationale: this package's OWN metadata digest is checked as well, because
    #   an extract may have been placed by `stage-dataset` rather than by an operator's sync --
    #   the staging writer publishes `carddemo-sha256` and `carddemo-byte-size` on every object it
    #   writes. Reading them back closes the loop on a copy this distribution made itself, and it
    #   costs nothing when they are absent.
    metadata = response.get("Metadata")
    if not isinstance(metadata, Mapping):
        return
    recorded = str(metadata.get(_SHA256_METADATA_KEY, "")).strip().lower()
    if recorded and recorded != digest.hex():
        raise DatasetSourceError(
            f"the extract at {key} carries a {_SHA256_METADATA_KEY} of {recorded} and the bytes "
            "that arrived digest to a different value, so the object and its metadata disagree"
        )
    recorded_size = str(metadata.get(_BYTE_SIZE_METADATA_KEY, "")).strip()
    if recorded_size and recorded_size != str(byte_size):
        raise DatasetSourceError(
            f"the extract at {key} carries a {_BYTE_SIZE_METADATA_KEY} of {recorded_size} and "
            f"{byte_size} bytes arrived, so the object and its metadata disagree"
        )


def fetch_dataset_extract(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    prefix: str,
    source_object: str,
    destination: Path,
    record_length: int | None = None,
) -> FetchedExtract:
    """Materialise one source extract from object storage onto a local path.

    Purpose
    -------
    Give the orchestrated refresh a source it can actually read. The seed extracts are exported
    from the baseline and cannot be shipped inside the container image, so the deployment holds
    them in a provisioned prefix of the dataset bucket and this is the read that brings one of
    them into the task.

    Parameters
    ----------
    client : S3StagingClient
        The object-store client, whose ``get_object`` is the only operation used here.
    settings : DatasetStagingSettings
        Resolved staging settings, read for the bucket name.
    prefix : str
        The source-extract prefix the deployment provisions.
    source_object : str
        The extract's file name as the seed-dataset registry declares it.
    destination : Path
        Local path to write the bytes to. Its parent must exist; the file is created and must
        not already exist, so no earlier attempt's bytes can be mistaken for this one's.
    record_length : int | None
        Declared record length to check the byte count against, or ``None`` to skip the check --
        which is correct for the newline-delimited text twins, whose byte length is not a
        multiple of the record length at all.

    Returns
    -------
    FetchedExtract
        The key read, the local path written, the byte count and the locally computed digest.

    Raises
    ------
    DatasetSourceError
        If the key cannot be composed, if the destination already exists, if the object cannot be
        read, if it publishes a length or digest that disagrees with what arrived, or if the byte
        count is not a whole number of records when one was declared.
    botocore.exceptions.ClientError
        Propagated from a real client for a failure the service reports, notably an absent object
        or a denied read -- both of which name the key in the SDK's own message.
    """
    key = extract_source_key(prefix, source_object)
    if destination.exists():
        raise DatasetSourceError(
            f"the fetch destination {destination} already exists; it is refused rather than "
            "overwritten so that a partial earlier attempt cannot be staged as this one's bytes"
        )
    # WHY : Assumptions: `ChecksumMode` is requested so the service returns the object's stored
    #   checksum where it has one. Without it a SHA-256 written at upload is simply not reported,
    #   and the strongest available end-to-end check would be silently unavailable.
    response = client.get_object(Bucket=settings.bucket, Key=key, ChecksumMode="ENABLED")
    body = response.get("Body")
    if body is None or not hasattr(body, "read"):
        raise DatasetSourceError(
            f"the read of {key} returned no readable body, so the extract cannot be materialised"
        )
    hasher = hashlib.sha256()
    byte_size = 0
    # WHY : Trade-offs: the body is streamed in bounded chunks and hashed AS IT IS WRITTEN, which
    #   is the opposite of the two-pass discipline `stage_dataset_file` uses on a local file, and
    #   the asymmetry is deliberate. There, two passes over one held descriptor buy a digest of
    #   what is on disk rather than of what was sent. Here the bytes exist only as a stream that
    #   can be read once, so the choice is between hashing during the write and reading the file
    #   back afterwards; hashing during the write is what lets the published digest be checked at
    #   all, and the caller's own staging pass will digest the written file independently anyway.
    # WHY : Assumptions: the destination is opened "xb" -- create, fail if present -- and in
    #   BINARY mode. Binary matters more here than anywhere else in this module: these are EBCDIC
    #   fixed-block extracts whose sign-overpunch and packed-decimal bytes are not text in any
    #   encoding, and a text-mode write would translate line endings and corrupt them silently.
    try:
        with destination.open("xb") as sink:
            while True:
                chunk = body.read(_DIGEST_CHUNK_BYTES)
                if not chunk:
                    break
                sink.write(chunk)
                hasher.update(chunk)
                byte_size += len(chunk)
    except FileExistsError as exists:
        raise DatasetSourceError(
            f"the fetch destination {destination} already exists; it is refused rather than "
            "overwritten so that a partial earlier attempt cannot be staged as this one's bytes"
        ) from exists
    except OSError as unwritable:
        raise DatasetSourceError(
            f"the extract at {key} could not be written to {destination}: {unwritable}"
        ) from unwritable
    finally:
        # WHY : Assumptions: the stream is closed even on a failed write, because a real SDK body
        #   holds an open connection from the pool. Leaking one per failed branch would exhaust
        #   the pool over a ten-dataset Map long before the run failed for the original reason.
        closer = getattr(body, "close", None)
        if callable(closer):
            closer()
    digest = hasher.digest()
    _require_published_digest(response, key, byte_size, digest)
    if record_length is not None:
        _require_record_length(destination, byte_size, record_length, source_object)
    return FetchedExtract(key=key, path=destination, byte_size=byte_size, sha256=digest.hex())
