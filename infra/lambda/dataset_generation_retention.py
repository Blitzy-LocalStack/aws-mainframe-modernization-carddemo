"""Retain only the five most recently created CardDemo dataset-generation prefixes.

Purpose
-------
Handle S3 ``ObjectCreated`` notifications for keys shaped as
``<family>/dt=YYYY-MM-DD/gen=NNNN/...``. For each affected family, list all
date/generation prefixes, order them by the sequence in which the generations
were CREATED, and delete current objects under every prefix outside the newest
five -- never including the generation the triggering write landed in.

Bucket versioning remains authoritative for overwritten-key history. Deleting
an obsolete generation creates delete markers while prior object versions stay
subject to the module's noncurrent-version lifecycle.

WHY : Refactoring Rationale: this handler ordered generations by the business
      date embedded in the key. A back-dated catch-up run therefore wrote the
      newest generation of a family under the OLDEST date, so this handler --
      fired by that very write -- ranked it outside the newest five and deleted
      the object that had just triggered it, while logging a retention pass that
      looked correct. A generation data group orders by creation sequence and has
      no notion of the content date its dataset carries, so creation sequence is
      what the ordering below reads. Two changes carry it: the creation instant
      of each generation's ``_generation.claim`` reservation marker is the primary
      sort key, and the generation prefixes named by the triggering event are
      withheld from deletion outright.
WHY : Assumptions: the primary sort key is the reservation marker's creation
      timestamp because ``DatasetGenerationService`` in ``services/batch-service``
      writes that marker exactly once, under a conditional guard, at the moment it
      claims the generation number, and it now orders its own in-process retention
      by the same object. Reading the same evidence is what keeps the two
      implementations from disagreeing about which generation is current -- either
      may be the one that retires a generation the other created.
WHY : Trade-offs: the pass costs one head request per generation prefix, plus a
      listing of a prefix whose marker is absent. A family holds at most the
      retention count plus the generation being written, so that is a handful of
      requests per notification; the alternative -- a monotonic counter held
      outside the bucket -- would need durable state that the batch tier could not
      see and would put the two orderings back into potential disagreement.

Environment
-----------
``RETENTION_COUNT``
    Positive generation count, default ``5``.

Returns
-------
dict
    Families inspected, generation prefixes pruned, generation prefixes
    withheld because the triggering write landed in them, and current objects
    deleted.

Raises
------
RuntimeError
    If retention configuration is invalid.
ValueError
    If an S3 event record lacks required bucket/key fields.
botocore.exceptions.BotoCoreError
    If S3 listing or deletion fails.
"""

from __future__ import annotations

import logging
import os
from collections.abc import Iterable
from datetime import date, datetime, timezone
from typing import Any
from urllib.parse import unquote_plus

import boto3
from botocore.exceptions import ClientError

LOGGER = logging.getLogger(__name__)
LOGGER.setLevel(logging.INFO)

try:
    RETENTION_COUNT = int(os.environ.get("RETENTION_COUNT", "5"))
except ValueError as exc:
    raise RuntimeError("RETENTION_COUNT must be an integer") from exc
if RETENTION_COUNT < 1:
    raise RuntimeError("RETENTION_COUNT must be positive")

S3 = boto3.client("s3")

# WHY : Assumptions: this literal is the same object name
#       ``DatasetGenerationService.CLAIM_OBJECT_NAME`` writes in
#       ``services/batch-service`` and ``_CLAIM_OBJECT_NAME`` writes in
#       ``data-migration/src/carddemo_migration/loaders/s3_stage.py``. Three
#       components declare it because none of them can import from the others -- a
#       Java service, a Python package and a zipped handler with no shared module --
#       so the name is a cross-tier contract kept by review rather than by linkage,
#       and it is named here rather than composed so a search for it finds every
#       declaration.
CLAIM_OBJECT_NAME = "_generation.claim"

# WHY : Assumptions: the epoch is a usable sentinel for "creation instant unknown"
#       because no marker and no staged object can carry it -- both are written by a
#       running task decades later -- so a prefix ordered at the epoch is
#       unambiguously one with no readable evidence rather than one created very
#       early. It is timezone-aware because every timestamp S3 reports is, and
#       comparing an aware timestamp with a naive one raises rather than ordering.
#       Prefixes sharing the sentinel are separated by the date-and-number
#       tie-break, so the outcome stays deterministic instead of listing-dependent.
UNDATED_CREATION = datetime(1970, 1, 1, tzinfo=timezone.utc)


def _family_from_key(key: str) -> str | None:
    """Return the family prefix for a generation key.

    Parameters
    ----------
    key:
        Decoded S3 object key.

    Returns
    -------
    str | None
        Prefix ending in ``/`` before the ``dt=`` segment, or ``None`` for a
        non-generation object.

    Raises
    ------
    None
        Invalid key shapes are ignored rather than failing unrelated writes.
    """

    segments = key.split("/")
    for index, segment in enumerate(segments):
        if segment.startswith("dt=") and index + 1 < len(segments):
            if segments[index + 1].startswith("gen=") and index > 0:
                return "/".join(segments[:index]) + "/"
            return None
    return None


def _generation_prefix_from_key(key: str) -> str | None:
    """Return the generation prefix one staged object key sits beneath.

    Parameters
    ----------
    key:
        Decoded S3 object key.

    Returns
    -------
    str | None
        Prefix ending in ``dt=YYYY-MM-DD/gen=NNNN/``, or ``None`` for a
        non-generation object.

    Raises
    ------
    None
        Invalid key shapes are ignored rather than failing unrelated writes,
        exactly as the family reader above ignores them.
    """
    # WHY : Assumptions: this is the operational analogue of "never scratch the
    #       generation the current run allocated". The handler cannot see a run
    #       identifier, but the notification that woke it names the object that was
    #       just written, and the generation holding that object is by definition
    #       the current one. Deriving the prefix from the event is therefore the
    #       only evidence of currency available here, and it is authoritative:
    #       the write has already happened.
    segments = key.split("/")
    for index, segment in enumerate(segments):
        if segment.startswith("dt=") and index + 1 < len(segments):
            if segments[index + 1].startswith("gen=") and index > 0:
                return "/".join(segments[: index + 2]) + "/"
            return None
    return None


def _common_prefixes(bucket: str, prefix: str) -> Iterable[str]:
    """Yield one-level S3 common prefixes below a prefix.

    Parameters
    ----------
    bucket:
        Bucket name.
    prefix:
        Parent key prefix ending in ``/``.

    Returns
    -------
    Iterable[str]
        Common-prefix values returned by paginated listing.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If S3 listing fails.
    """

    paginator = S3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix, Delimiter="/"):
        for entry in page.get("CommonPrefixes", []):
            yield entry["Prefix"]


def _generation_sort_key(prefix: str) -> tuple[date, int]:
    """Parse a generation prefix into a sortable business-date/number pair.

    The pair is the TIE-BREAK of the retention ordering and no longer its primary
    key: two generations created within one timestamp granularity are separated by
    it, and a family whose prefixes carry no readable creation evidence at all is
    ordered by it alone. It also remains the validity test that decides whether a
    listed child is a generation prefix, because a prefix this function refuses is
    one no writer under the shared convention produced.

    Parameters
    ----------
    prefix:
        Prefix ending in ``dt=YYYY-MM-DD/gen=NNNN/``.

    Returns
    -------
    tuple[date, int]
        Parsed date and generation number.

    Raises
    ------
    ValueError
        If either segment is malformed.
    """

    segments = [segment for segment in prefix.split("/") if segment]
    date_segment = next(segment for segment in segments if segment.startswith("dt="))
    generation_segment = next(
        segment for segment in segments if segment.startswith("gen=")
    )
    business_date = date.fromisoformat(date_segment.removeprefix("dt="))
    generation_text = generation_segment.removeprefix("gen=")
    if not generation_text.isdigit():
        raise ValueError("Generation number must contain ASCII digits")
    return business_date, int(generation_text)


def _newest_object_instant(bucket: str, prefix: str) -> datetime:
    """Return the newest current-object timestamp beneath one generation prefix.

    Parameters
    ----------
    bucket:
        Bucket name.
    prefix:
        Generation prefix to inspect.

    Returns
    -------
    datetime
        Newest ``LastModified`` among the current objects, or
        :data:`UNDATED_CREATION` when the prefix holds none.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If S3 listing fails.
    """
    # WHY : Assumptions: this is the fallback for a generation carrying no
    #       reservation marker, which is a real state rather than a defensive one:
    #       `services/reporting-service`'s GenerationKeys writes on-demand report
    #       artifacts into this key shape and reserves nothing, and its own
    #       documentation records that it relies on this handler for retention. Such
    #       a family would otherwise have every prefix at the sentinel and be
    #       ordered by content date alone -- which is the defect being removed.
    #       Trade-offs: the timestamp read here is the last time the generation was
    #       WRITTEN rather than when it was allocated, so re-staging an object into
    #       an older generation would promote it. That is accepted only where no
    #       marker exists, because there is no allocation instant to read; the
    #       marker is written once under a conditional guard and cannot move.
    # WHY : Assumptions: a prefix this handler has already pruned holds no current
    #       object, so it falls to the sentinel and stays the oldest entry rather
    #       than being promoted by the delete markers left over it. Re-pruning it is
    #       a listing that deletes nothing.
    newest = UNDATED_CREATION
    paginator = S3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        for entry in page.get("Contents", []):
            stamp = entry.get("LastModified")
            if stamp is not None and stamp > newest:
                newest = stamp
    return newest


def _creation_instant(bucket: str, prefix: str) -> datetime:
    """Return the instant one generation was created, for the retention ordering.

    Parameters
    ----------
    bucket:
        Bucket name.
    prefix:
        Generation prefix ending in ``dt=YYYY-MM-DD/gen=NNNN/``.

    Returns
    -------
    datetime
        Creation timestamp of the generation's reservation marker, or the newest
        current-object timestamp beneath the prefix when no marker exists, or
        :data:`UNDATED_CREATION` when neither is available.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If S3 metadata or listing access fails.
    ClientError
        If the marker read is refused for any reason other than absence, because
        an authorisation or key-management failure must stop the pass rather than
        default the prefix to the sentinel and make it the first candidate for
        deletion.
    """
    try:
        marker = S3.head_object(Bucket=bucket, Key=f"{prefix}{CLAIM_OBJECT_NAME}")
    except ClientError as exc:
        # WHY : Assumptions: a head request reports absence as one of three codes
        #       depending on the request form and the service surface -- the bare
        #       status, the typed no-such-key code, or the head-specific not-found
        #       code -- so all three are read as absence and everything else is
        #       re-raised. Treating every refusal as absence would silently order a
        #       live generation at the sentinel; treating absence as a failure would
        #       stop retention for a family whose writer reserves nothing.
        if exc.response.get("Error", {}).get("Code") not in {
            "404",
            "NoSuchKey",
            "NotFound",
        }:
            raise
        return _newest_object_instant(bucket, prefix)

    last_modified = marker.get("LastModified")
    if last_modified is None:
        return _newest_object_instant(bucket, prefix)
    return last_modified


def _list_generations(bucket: str, family_prefix: str) -> list[str]:
    """List valid date/generation prefixes for one dataset family.

    Parameters
    ----------
    bucket:
        Bucket name.
    family_prefix:
        Dataset family prefix.

    Returns
    -------
    list[str]
        Valid prefixes ordered by creation sequence, least recently created
        first, with the business date and generation number breaking ties.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If S3 listing fails.
    """

    ordered: list[tuple[datetime, date, int, str]] = []
    for date_prefix in _common_prefixes(bucket, family_prefix):
        for generation_prefix in _common_prefixes(bucket, date_prefix):
            try:
                business_date, generation = _generation_sort_key(generation_prefix)
            except (StopIteration, ValueError):
                LOGGER.warning(
                    "event=invalid_generation_prefix bucket=%s prefix=%s",
                    bucket,
                    generation_prefix,
                )
                continue
            # WHY : Assumptions: the creation instant is resolved into a tuple here
            #       rather than supplied as a sort key function, so each prefix is
            #       read exactly once. A key function issuing a request per
            #       comparison would scale with the sort's comparison count and
            #       could observe two different answers for one prefix mid-sort,
            #       which a sort implementation is entitled to reject.
            ordered.append(
                (
                    _creation_instant(bucket, generation_prefix),
                    business_date,
                    generation,
                    generation_prefix,
                )
            )
    ordered.sort(key=lambda entry: (entry[0], entry[1], entry[2]))
    return [entry[3] for entry in ordered]


def _delete_current_objects(bucket: str, prefix: str) -> int:
    """Delete every current object beneath one obsolete generation prefix.

    Parameters
    ----------
    bucket:
        Bucket name.
    prefix:
        Obsolete generation prefix.

    Returns
    -------
    int
        Number of current keys deleted.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If listing or batch deletion fails.
    """

    deleted = 0
    paginator = S3.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix):
        objects = [{"Key": entry["Key"]} for entry in page.get("Contents", [])]
        for offset in range(0, len(objects), 1000):
            batch = objects[offset : offset + 1000]
            if batch:
                S3.delete_objects(
                    Bucket=bucket,
                    Delete={"Objects": batch, "Quiet": True},
                )
                deleted += len(batch)
    return deleted


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Prune obsolete generations for every family touched by an S3 event.

    Parameters
    ----------
    event:
        S3 notification payload.
    context:
        Lambda context, unused because platform logs already include request
        metadata.

    Returns
    -------
    dict
        Stable pruning counters, including how many aged-out prefixes were
        withheld because the triggering write landed in them.

    Raises
    ------
    ValueError
        If a record lacks its bucket name or object key.
    botocore.exceptions.BotoCoreError
        If S3 listing or deletion fails.
    """

    del context
    # WHY : Assumptions: the protected prefixes are collected per family rather
    #       than globally, because one notification batch can name writes into two
    #       families and a global set would have to be searched for membership in
    #       the wrong family's listing. Keying them by the same pair the families
    #       are keyed by keeps each family's decision reading only its own
    #       evidence.
    affected: dict[tuple[str, str], set[str]] = {}
    for record in event.get("Records", []):
        try:
            bucket = record["s3"]["bucket"]["name"]
            key = unquote_plus(record["s3"]["object"]["key"])
        except (KeyError, TypeError) as exc:
            raise ValueError("S3 event record lacks bucket or object key") from exc
        family = _family_from_key(key)
        if family is None:
            continue
        protected = affected.setdefault((bucket, family), set())
        generation_prefix = _generation_prefix_from_key(key)
        if generation_prefix is not None:
            protected.add(generation_prefix)

    pruned_prefixes = 0
    withheld_prefixes = 0
    deleted_objects = 0
    for (bucket, family), protected in sorted(affected.items()):
        generations = _list_generations(bucket, family)
        # WHY : Assumptions: the slice keeps the LAST RETENTION_COUNT entries
        #       because the listing is ordered least recently created first, and the
        #       negative-slice form is correct for the short case as well -- with
        #       fewer generations than the count it yields nothing, whereas index
        #       arithmetic would compute a negative bound and select from the wrong
        #       end. Protection is applied AFTER the window rather than before it,
        #       so in the ordinary case the generation just written is the newest
        #       and is never a candidate; only a tie on the store's timestamp
        #       granularity leaves the family holding one extra, which the next
        #       notification retires.
        obsolete = [
            prefix
            for prefix in generations[:-RETENTION_COUNT]
            if prefix not in protected
        ]
        withheld = len(generations[:-RETENTION_COUNT]) - len(obsolete)
        for prefix in obsolete:
            deleted_objects += _delete_current_objects(bucket, prefix)
            pruned_prefixes += 1
        withheld_prefixes += withheld
        LOGGER.info(
            "event=generation_retention_applied bucket=%s family=%s total=%d retained=%d"
            " pruned=%d withheld=%d",
            bucket,
            family,
            len(generations),
            len(generations) - len(obsolete),
            len(obsolete),
            withheld,
        )

    return {
        "familiesInspected": len(affected),
        "generationPrefixesPruned": pruned_prefixes,
        "generationPrefixesWithheld": withheld_prefixes,
        "currentObjectsDeleted": deleted_objects,
    }
