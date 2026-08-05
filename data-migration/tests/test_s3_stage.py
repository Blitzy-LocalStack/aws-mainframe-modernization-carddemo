"""Verify S3 logical-generation staging and version-aware SCRATCH cleanup."""

from __future__ import annotations

from datetime import date
from typing import Any

import pytest

from carddemo_migration.config import DatasetStagingSettings
from carddemo_migration.loaders.s3_stage import (
    GenerationRetentionError,
    delete_generation_prefix,
    list_generation_prefixes,
    prune_generations,
    stage_generation,
)


class _FakePaginator:
    """Return deterministic pages selected by operation and prefix."""

    def __init__(self, client: "_FakeS3", operation: str) -> None:
        """Store the fake client and operation to paginate."""
        self._client = client
        self._operation = operation

    def paginate(self, **kwargs: Any) -> list[dict[str, Any]]:
        """Return pages registered for the operation and requested prefix."""
        prefix = kwargs["Prefix"]
        return self._client.pages.get((self._operation, prefix), [{}])


class _FakeS3:
    """Record S3 calls while serving pre-arranged paginator pages."""

    def __init__(self) -> None:
        """Create empty page, write and delete registries."""
        self.pages: dict[tuple[str, str], list[dict[str, Any]]] = {}
        self.puts: list[dict[str, Any]] = []
        self.deletes: list[dict[str, Any]] = []
        self.delete_errors: list[dict[str, str]] = []

    def get_paginator(self, operation_name: str) -> _FakePaginator:
        """Return a paginator bound to the requested operation."""
        return _FakePaginator(self, operation_name)

    def put_object(self, **kwargs: Any) -> dict[str, str]:
        """Record a put operation and return a minimal success response."""
        self.puts.append(kwargs)
        return {"ETag": "fake"}

    def delete_objects(self, **kwargs: Any) -> dict[str, Any]:
        """Record a delete operation and return configured per-object errors."""
        self.deletes.append(kwargs)
        return {"Errors": list(self.delete_errors)}


def _generation_pages(client: _FakeS3) -> None:
    """Register three dates and four valid generation prefixes."""
    family = "ledger/transact-bkup/"
    client.pages[("list_objects_v2", family)] = [
        {
            "CommonPrefixes": [
                {"Prefix": f"{family}dt=2026-08-01/"},
                {"Prefix": f"{family}dt=2026-08-02/"},
                {"Prefix": f"{family}misc/"},
            ]
        }
    ]
    client.pages[("list_objects_v2", f"{family}dt=2026-08-01/")] = [
        {
            "CommonPrefixes": [
                {"Prefix": f"{family}dt=2026-08-01/gen=0002/"},
                {"Prefix": f"{family}dt=2026-08-01/gen=0001/"},
            ]
        }
    ]
    client.pages[("list_objects_v2", f"{family}dt=2026-08-02/")] = [
        {
            "CommonPrefixes": [
                {"Prefix": f"{family}dt=2026-08-02/gen=0002/"},
                {"Prefix": f"{family}dt=2026-08-02/gen=0001/"},
            ]
        }
    ]
    client.pages[("list_objects_v2", f"{family}misc/")] = [
        {"CommonPrefixes": [{"Prefix": f"{family}misc/not-a-generation/"}]}
    ]


def test_list_generation_prefixes_orders_date_then_generation() -> None:
    """Return only valid prefixes in deterministic chronological order."""
    client = _FakeS3()
    _generation_pages(client)

    prefixes = list_generation_prefixes(client, "datasets", "ledger/transact-bkup/")

    assert [item.prefix for item in prefixes] == [
        "ledger/transact-bkup/dt=2026-08-01/gen=0001/",
        "ledger/transact-bkup/dt=2026-08-01/gen=0002/",
        "ledger/transact-bkup/dt=2026-08-02/gen=0001/",
        "ledger/transact-bkup/dt=2026-08-02/gen=0002/",
    ]


def test_prune_generations_permanently_deletes_old_versions_and_markers() -> None:
    """Delete every version and marker under prefixes outside newest-N."""
    client = _FakeS3()
    _generation_pages(client)
    old = "ledger/transact-bkup/dt=2026-08-01/gen=0001/"
    client.pages[("list_object_versions", old)] = [
        {
            "Versions": [{"Key": f"{old}records.dat", "VersionId": "v1"}],
            "DeleteMarkers": [{"Key": f"{old}records.dat", "VersionId": "m1"}],
        }
    ]
    second = "ledger/transact-bkup/dt=2026-08-01/gen=0002/"
    client.pages[("list_object_versions", second)] = [
        {"Versions": [{"Key": f"{second}records.dat", "VersionId": "v2"}]}
    ]
    settings = DatasetStagingSettings(bucket="datasets", environment="dev")

    deleted = prune_generations(client, settings, "ledger", "transact-bkup", 2)

    assert deleted == (old, second)
    deleted_objects = [item for call in client.deletes for item in call["Delete"]["Objects"]]
    assert deleted_objects == [
        {"Key": f"{old}records.dat", "VersionId": "v1"},
        {"Key": f"{old}records.dat", "VersionId": "m1"},
        {"Key": f"{second}records.dat", "VersionId": "v2"},
    ]


def test_stage_generation_writes_before_cleanup() -> None:
    """Write exact bytes at the canonical key and report cleanup results."""
    client = _FakeS3()
    _generation_pages(client)
    oldest = "ledger/transact-bkup/dt=2026-08-01/gen=0001/"
    client.pages[("list_object_versions", oldest)] = [
        {"Versions": [{"Key": f"{oldest}records.dat", "VersionId": "v1"}]}
    ]
    settings = DatasetStagingSettings(bucket="datasets", environment="dev")

    result = stage_generation(
        client,
        settings,
        "ledger",
        "transact-bkup",
        date(2026, 8, 2),
        2,
        "records.dat",
        b"CARDDEMO",
        3,
    )

    assert result.key == "ledger/transact-bkup/dt=2026-08-02/gen=0002/records.dat"
    assert result.deleted_generation_prefixes == (oldest,)
    assert client.puts == [
        {
            "Bucket": "datasets",
            "Key": result.key,
            "Body": b"CARDDEMO",
            "ContentType": "application/octet-stream",
        }
    ]


@pytest.mark.parametrize("value", [0, -1, True, 1.5])
def test_prune_rejects_invalid_retention(value: object) -> None:
    """Reject values that cannot represent a positive generation count."""
    client = _FakeS3()
    settings = DatasetStagingSettings(bucket="datasets", environment="dev")

    with pytest.raises(GenerationRetentionError):
        prune_generations(client, settings, "ledger", "transact-bkup", value)  # type: ignore[arg-type]


def test_delete_generation_reports_partial_s3_failure() -> None:
    """Raise when S3 reports any version it failed to delete."""
    client = _FakeS3()
    prefix = "ledger/transact-bkup/dt=2026-08-01/gen=0001/"
    client.pages[("list_object_versions", prefix)] = [
        {"Versions": [{"Key": f"{prefix}records.dat", "VersionId": "v1"}]}
    ]
    client.delete_errors = [{"Code": "AccessDenied"}]

    with pytest.raises(GenerationRetentionError, match="AccessDenied"):
        delete_generation_prefix(client, "datasets", prefix)
