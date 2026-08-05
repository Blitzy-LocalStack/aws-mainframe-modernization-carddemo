"""Retain only the five newest CardDemo dataset-generation prefixes.

Purpose
-------
Handle S3 ``ObjectCreated`` notifications for keys shaped as
``<family>/dt=YYYY-MM-DD/gen=NNNN/...``. For each affected family, list all
date/generation prefixes, sort them by business date and generation number,
and delete current objects under every prefix older than the newest five.

Bucket versioning remains authoritative for overwritten-key history. Deleting
an obsolete generation creates delete markers while prior object versions stay
subject to the module's noncurrent-version lifecycle.

Environment
-----------
``RETENTION_COUNT``
    Positive generation count, default ``5``.

Returns
-------
dict
    Families inspected, generation prefixes pruned, and current objects
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
from datetime import date
from typing import Any
from urllib.parse import unquote_plus

import boto3

LOGGER = logging.getLogger(__name__)
LOGGER.setLevel(logging.INFO)

try:
    RETENTION_COUNT = int(os.environ.get("RETENTION_COUNT", "5"))
except ValueError as exc:
    raise RuntimeError("RETENTION_COUNT must be an integer") from exc
if RETENTION_COUNT < 1:
    raise RuntimeError("RETENTION_COUNT must be positive")

S3 = boto3.client("s3")


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
        Valid prefixes sorted oldest to newest.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If S3 listing fails.
    """

    generations: list[str] = []
    for date_prefix in _common_prefixes(bucket, family_prefix):
        for generation_prefix in _common_prefixes(bucket, date_prefix):
            try:
                _generation_sort_key(generation_prefix)
            except (StopIteration, ValueError):
                LOGGER.warning(
                    "event=invalid_generation_prefix bucket=%s prefix=%s",
                    bucket,
                    generation_prefix,
                )
                continue
            generations.append(generation_prefix)
    return sorted(generations, key=_generation_sort_key)


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
        Stable pruning counters.

    Raises
    ------
    ValueError
        If a record lacks its bucket name or object key.
    botocore.exceptions.BotoCoreError
        If S3 listing or deletion fails.
    """

    del context
    affected: set[tuple[str, str]] = set()
    for record in event.get("Records", []):
        try:
            bucket = record["s3"]["bucket"]["name"]
            key = unquote_plus(record["s3"]["object"]["key"])
        except (KeyError, TypeError) as exc:
            raise ValueError("S3 event record lacks bucket or object key") from exc
        family = _family_from_key(key)
        if family is not None:
            affected.add((bucket, family))

    pruned_prefixes = 0
    deleted_objects = 0
    for bucket, family in sorted(affected):
        generations = _list_generations(bucket, family)
        obsolete = generations[:-RETENTION_COUNT]
        for prefix in obsolete:
            deleted_objects += _delete_current_objects(bucket, prefix)
            pruned_prefixes += 1
        LOGGER.info(
            "event=generation_retention_applied bucket=%s family=%s total=%d retained=%d pruned=%d",
            bucket,
            family,
            len(generations),
            min(len(generations), RETENTION_COUNT),
            len(obsolete),
        )

    return {
        "familiesInspected": len(affected),
        "generationPrefixesPruned": pruned_prefixes,
        "currentObjectsDeleted": deleted_objects,
    }
