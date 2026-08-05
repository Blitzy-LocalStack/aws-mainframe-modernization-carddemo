"""Stage one dataset generation and enforce the LIMIT/SCRATCH retention contract.

Purpose
-------
Write a generation beneath ``<domain>/<dataset>/dt=YYYY-MM-DD/gen=NNNN/`` and
keep only the newest configured generation prefixes. S3 lifecycle cannot enforce
that count: distinct ``gen=`` prefixes are distinct keys, not versions of one
key. This module therefore centralises prefix discovery, ordering and
version-aware permanent deletion so every writer uses one SCRATCH implementation.

The retention count is supplied from the Terraform
``generation_retention_by_family`` output. The same Terraform input also governs
same-key noncurrent-version lifecycle, giving the deployment one numeric source
for the two different retention layers.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date
from typing import Any, Protocol

from carddemo_migration.config import DatasetStagingSettings

_MAX_DELETE_OBJECTS = 1000
_GENERATION_SUFFIX = re.compile(r"dt=(\d{4}-\d{2}-\d{2})/gen=(\d{4})/\Z")


class GenerationRetentionError(RuntimeError):
    """Report an unsafe staging or generation-cleanup operation."""


class _Paginator(Protocol):
    """Describe the boto3 paginator surface used by this module."""

    def paginate(self, **kwargs: Any) -> Any:
        """Yield service response pages for the supplied operation arguments."""


class S3StagingClient(Protocol):
    """Describe the small S3 client surface required for staging and cleanup."""

    def get_paginator(self, operation_name: str) -> _Paginator:
        """Return a paginator for ``list_objects_v2`` or ``list_object_versions``."""

    def put_object(self, **kwargs: Any) -> Any:
        """Write one staged generation object."""

    def delete_objects(self, **kwargs: Any) -> Any:
        """Permanently delete explicitly named object versions or delete markers."""


@dataclass(frozen=True, order=True, slots=True)
class GenerationPrefix:
    """One validated logical generation prefix in chronological sort order.

    Parameters
    ----------
    business_date : date
        Business date encoded in the ``dt=`` segment.
    generation : int
        Four-digit generation number encoded in the ``gen=`` segment.
    prefix : str
        Complete prefix ending in ``/``.
    """

    business_date: date
    generation: int
    prefix: str


@dataclass(frozen=True, slots=True)
class StagedGeneration:
    """Describe the object written and any old generation prefixes scratched.

    Parameters
    ----------
    key : str
        Complete object key written to S3.
    deleted_generation_prefixes : tuple[str, ...]
        Old logical generation prefixes permanently deleted after the write.
    """

    key: str
    deleted_generation_prefixes: tuple[str, ...]


def _require_retention_count(retention_count: int) -> int:
    """Return a positive whole-number retention count.

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
    if isinstance(retention_count, bool) or not isinstance(retention_count, int):
        raise GenerationRetentionError("generation retention count is not an integer")
    if retention_count < 1:
        raise GenerationRetentionError("generation retention count must be at least one")
    return retention_count


def _require_object_name(object_name: str) -> str:
    """Return a safe single-segment object name.

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
        If the name is blank, not text, contains a slash or is a dot segment.
    """
    if not isinstance(object_name, str):
        raise GenerationRetentionError("generation object name is not text")
    stripped = object_name.strip()
    if not stripped:
        raise GenerationRetentionError("generation object name is empty")
    if "/" in stripped or stripped in {".", ".."}:
        raise GenerationRetentionError("generation object name must be one non-dot path segment")
    return stripped


def _parse_generation_prefix(
    candidate: str,
    family_prefix: str,
) -> GenerationPrefix | None:
    """Parse one common prefix, returning ``None`` for unrelated child paths.

    Parameters
    ----------
    candidate : str
        Common prefix returned by S3.
    family_prefix : str
        Validated ``<domain>/<dataset>/`` prefix expected at the front.

    Returns
    -------
    GenerationPrefix | None
        Parsed generation, or ``None`` when the child path does not match the
        fixed ``dt=/gen=`` convention.

    Raises
    ------
    GenerationRetentionError
        If a matching date segment contains an impossible calendar date.
    """
    if not candidate.startswith(family_prefix):
        return None
    match = _GENERATION_SUFFIX.fullmatch(candidate[len(family_prefix) :])
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
    family_prefix: str,
) -> tuple[GenerationPrefix, ...]:
    """List valid logical generations beneath one dataset family.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for paginated prefix discovery.
    bucket : str
        Dataset bucket name.
    family_prefix : str
        Validated ``<domain>/<dataset>/`` prefix.

    Returns
    -------
    tuple[GenerationPrefix, ...]
        Chronologically sorted generations. Unknown child paths are ignored so
        cleanup never deletes an object outside the fixed convention.

    Raises
    ------
    GenerationRetentionError
        If a matching prefix contains an invalid date.
    """
    paginator = client.get_paginator("list_objects_v2")
    date_prefixes: set[str] = set()
    for page in paginator.paginate(Bucket=bucket, Prefix=family_prefix, Delimiter="/"):
        date_prefixes.update(
            item["Prefix"]
            for item in page.get("CommonPrefixes", [])
            if isinstance(item.get("Prefix"), str)
        )

    generations: set[GenerationPrefix] = set()
    for date_prefix in sorted(date_prefixes):
        for page in paginator.paginate(Bucket=bucket, Prefix=date_prefix, Delimiter="/"):
            for item in page.get("CommonPrefixes", []):
                candidate = item.get("Prefix")
                if not isinstance(candidate, str):
                    continue
                parsed = _parse_generation_prefix(candidate, family_prefix)
                if parsed is not None:
                    generations.add(parsed)
    return tuple(sorted(generations))


def _delete_batch(
    client: S3StagingClient,
    bucket: str,
    objects: list[dict[str, str]],
) -> None:
    """Delete one API-sized batch and reject every partial failure.

    Parameters
    ----------
    client : S3StagingClient
        S3 client performing the deletion.
    bucket : str
        Dataset bucket name.
    objects : list[dict[str, str]]
        Key/version-id pairs, no more than the S3 limit of 1000.

    Returns
    -------
    None
        The batch is deleted in place.

    Raises
    ------
    GenerationRetentionError
        If S3 reports any per-object deletion error.
    """
    response = client.delete_objects(
        Bucket=bucket,
        Delete={"Objects": objects, "Quiet": True},
    )
    errors = response.get("Errors", [])
    if errors:
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
    paginator = client.get_paginator("list_object_versions")
    pending: list[dict[str, str]] = []
    for page in paginator.paginate(Bucket=bucket, Prefix=generation_prefix):
        for collection_name in ("Versions", "DeleteMarkers"):
            for item in page.get(collection_name, []):
                key = item.get("Key")
                version_id = item.get("VersionId")
                if not isinstance(key, str) or not isinstance(version_id, str):
                    continue
                pending.append({"Key": key, "VersionId": version_id})
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
        Number of newest generations to preserve, supplied from Terraform's
        effective per-family retention output.

    Returns
    -------
    tuple[str, ...]
        Prefixes deleted from oldest to newest.

    Raises
    ------
    GenerationRetentionError
        If the retention count is invalid or a delete fails.
    """
    count = _require_retention_count(retention_count)
    sample = settings.generation_prefix(domain, dataset, date.min, 0)
    family_prefix = sample.split("dt=", 1)[0]
    generations = list_generation_prefixes(client, settings.bucket, family_prefix)
    stale = generations[:-count]
    for generation in stale:
        delete_generation_prefix(client, settings.bucket, generation.prefix)
    return tuple(generation.prefix for generation in stale)


def stage_generation(
    client: S3StagingClient,
    settings: DatasetStagingSettings,
    domain: str,
    dataset: str,
    business_date: date,
    generation: int,
    object_name: str,
    payload: bytes,
    retention_count: int,
) -> StagedGeneration:
    """Write one generation object and then enforce newest-N retention.

    Parameters
    ----------
    client : S3StagingClient
        S3 client used for the write and cleanup.
    settings : DatasetStagingSettings
        Validated bucket and prefix settings.
    domain : str
        Dataset bounded-context segment.
    dataset : str
        Dataset-family segment.
    business_date : date
        Business date encoded into the staged prefix.
    generation : int
        Four-digit generation number.
    object_name : str
        Single leaf name beneath the generation prefix.
    payload : bytes
        Exact bytes written as the staged dataset object.
    retention_count : int
        Number of newest logical generations to keep.

    Returns
    -------
    StagedGeneration
        Written key plus any old prefixes scratched after the successful write.

    Raises
    ------
    GenerationRetentionError
        If the object name, payload or retention count is invalid, or cleanup
        reports a partial failure.
    """
    leaf = _require_object_name(object_name)
    count = _require_retention_count(retention_count)
    if not isinstance(payload, bytes):
        raise GenerationRetentionError("generation payload must be bytes")

    prefix = settings.generation_prefix(domain, dataset, business_date, generation)
    key = f"{prefix}{leaf}"
    client.put_object(
        Bucket=settings.bucket,
        Key=key,
        Body=payload,
        ContentType="application/octet-stream",
    )
    deleted = prune_generations(client, settings, domain, dataset, count)
    return StagedGeneration(key=key, deleted_generation_prefixes=deleted)
