"""Verify S3 logical-generation staging and version-aware SCRATCH cleanup."""

from __future__ import annotations

import base64
import hashlib
import io
import os
from datetime import date
from pathlib import Path
from typing import Any

import pytest

from carddemo_migration.config import DatasetStagingSettings
from carddemo_migration.loaders.s3_stage import (
    DatasetSourceError,
    GenerationRetentionError,
    delete_generation_prefix,
    list_generation_prefixes,
    prune_generations,
    reserve_generation,
    stage_dataset_file,
)


class _ConditionalConflict(Exception):
    """Stand in for the service error a refused conditional create raises.

    Purpose
    -------
    Reproduce the only part of a real ``ClientError`` the staging module reads -- the service
    error code inside a ``response`` mapping -- so the claim-collision path is reachable without
    importing botocore into a test.

    Attributes
    ----------
    response : dict
        The minimal error document shape, carrying the code the module matches on.
    """

    def __init__(self, code: str = "PreconditionFailed") -> None:
        """Build a conflict carrying one service error code.

        Parameters
        ----------
        code : str
            Service error code to report, defaulting to S3's answer to an unsatisfied
            ``If-None-Match``.

        Returns
        -------
        None

        Raises
        ------
        None
        """
        super().__init__(code)
        self.response = {"Error": {"Code": code}}


class _FakePaginator:
    """Return deterministic pages selected by operation and prefix."""

    def __init__(self, client: "_FakeS3", operation: str) -> None:
        """Store the fake client and operation to paginate."""
        self._client = client
        self._operation = operation

    def paginate(self, **kwargs: Any) -> list[dict[str, Any]]:
        """Return pages registered for the operation and requested prefix."""
        prefix = kwargs["Prefix"]
        registered = self._client.pages.get((self._operation, prefix))
        if registered is not None:
            return registered
        # WHY : Assumptions: an unregistered prefix answers with the objects the fake has been
        #   made to hold, filtered by prefix, rather than with an empty page. Generation CLAIMS
        #   are created by the code under test rather than pre-registered, so a fake that only
        #   ever replayed registered pages could never show the allocator its own earlier claim
        #   -- which is precisely the state the retry-reuse and collision tests turn on.
        contents = [{"Key": key} for key in sorted(self._client.objects) if key.startswith(prefix)]
        return [{"Contents": contents}] if contents else [{}]


class _FakeS3:
    """Record S3 calls while serving pre-arranged paginator pages."""

    def __init__(self) -> None:
        """Create empty page, object, write and delete registries."""
        self.pages: dict[tuple[str, str], list[dict[str, Any]]] = {}
        self.objects: dict[str, bytes] = {}
        self.puts: list[dict[str, Any]] = []
        self.deletes: list[dict[str, Any]] = []
        self.delete_errors: list[dict[str, str]] = []

    def get_paginator(self, operation_name: str) -> _FakePaginator:
        """Return a paginator bound to the requested operation."""
        return _FakePaginator(self, operation_name)

    def put_object(self, **kwargs: Any) -> dict[str, str]:
        """Record a put, honouring ``IfNoneMatch`` as a real conditional create would."""
        key = kwargs["Key"]
        # WHY : Assumptions: the request is logged BEFORE the condition is evaluated, so a
        #   refused conditional create still leaves a trace. Logging only successful writes was
        #   the first shape and it made the allocator's refused attempt invisible, so a test could
        #   see which generation was finally written but not that an earlier one had been tried
        #   and denied -- which is the half that shows the condition did the arbitrating.
        self.puts.append(kwargs)
        # WHY : Assumptions: the fake enforces the CONDITION rather than merely recording that it
        #   was requested. A fake that accepted every conditional write would let the allocation
        #   loop pass while giving two callers the same generation, so the test would assert the
        #   parameter was present and prove nothing about exclusivity -- the property that
        #   actually matters.
        if kwargs.get("IfNoneMatch") == "*" and key in self.objects:
            raise _ConditionalConflict()
        body = kwargs["Body"]
        self.objects[key] = body if isinstance(body, bytes) else body.read()
        return {"ETag": "fake"}

    def get_object(self, **kwargs: Any) -> dict[str, Any]:
        """Return a stored object body wrapped the way the SDK returns it."""
        key = kwargs["Key"]
        if key not in self.objects:
            raise _ConditionalConflict("NoSuchKey")
        return {"Body": io.BytesIO(self.objects[key])}

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


def _settings() -> DatasetStagingSettings:
    """Build the staging settings every test in this module writes through.

    Returns
    -------
    DatasetStagingSettings
        Settings naming the fake bucket and the development deployment.

    Raises
    ------
    None
    """
    return DatasetStagingSettings(bucket="datasets", environment="dev")


def test_stage_dataset_file_writes_before_cleanup(tmp_path: Path) -> None:
    """Write exact bytes at the canonical key and report cleanup results."""
    client = _FakeS3()
    _generation_pages(client)
    oldest = "ledger/transact-bkup/dt=2026-08-01/gen=0001/"
    client.pages[("list_object_versions", oldest)] = [
        {"Versions": [{"Key": f"{oldest}records.dat", "VersionId": "v1"}]}
    ]
    source = tmp_path / "records.dat"
    source.write_bytes(b"CARDDEMO")

    result = stage_dataset_file(
        client,
        _settings(),
        "ledger",
        "transact-bkup",
        date(2026, 8, 2),
        2,
        source,
        retention_count=3,
    )

    assert result.key == "ledger/transact-bkup/dt=2026-08-02/gen=0002/records.dat"
    assert result.deleted_generation_prefixes == (oldest,)
    assert client.objects[result.key] == b"CARDDEMO"


def test_stage_dataset_file_supplies_a_service_verifiable_checksum(tmp_path: Path) -> None:
    """Send the digest as ChecksumSHA256 so S3 itself rejects a corrupted transfer."""
    client = _FakeS3()
    payload = bytes(range(256)) * 3
    source = tmp_path / "EXPORT.PS"
    source.write_bytes(payload)

    result = stage_dataset_file(
        client, _settings(), "ledger", "transact-bkup", date(2026, 8, 2), 1, source
    )

    expected = hashlib.sha256(payload).digest()
    request = client.puts[-1]
    # WHY : Trade-offs: the assertion pins the BASE64 spelling rather than merely checking the
    #   parameter is present. S3 defines ChecksumSHA256 as base64 of the 32 raw digest bytes, so
    #   sending the hex spelling -- the intuitive mistake, since hex is what the audit metadata
    #   and the report carry -- would be accepted by a permissive fake yet rejected by the real
    #   service. Pinning the encoding is what makes this test able to catch that.
    assert request["ChecksumSHA256"] == base64.b64encode(expected).decode("ascii")
    assert request["ContentLength"] == len(payload)
    assert result.sha256 == expected.hex()
    assert result.byte_size == len(payload)
    assert request["Metadata"]["carddemo-sha256"] == expected.hex()


def test_stage_dataset_file_refuses_a_symlinked_source(tmp_path: Path) -> None:
    """Refuse an extract path whose final component is a symbolic link."""
    real = tmp_path / "real.dat"
    real.write_bytes(b"CARDDEMO")
    link = tmp_path / "extract.dat"
    link.symlink_to(real)
    client = _FakeS3()

    with pytest.raises(DatasetSourceError, match="cannot be read"):
        stage_dataset_file(
            client, _settings(), "ledger", "transact-bkup", date(2026, 8, 2), 1, link
        )
    # WHY : Assumptions: nothing may be written when the source is refused. Asserting the refusal
    #   alone would pass even if the object had already been staged before the check ran, which
    #   would leave a bucket holding bytes from a path the step declined to trust.
    assert client.puts == []


def test_stage_dataset_file_refuses_a_directory_source(tmp_path: Path) -> None:
    """Refuse a source that opens successfully but is not a regular file."""
    client = _FakeS3()
    directory = tmp_path / "not-a-file"
    directory.mkdir()

    with pytest.raises(DatasetSourceError):
        stage_dataset_file(
            client, _settings(), "ledger", "transact-bkup", date(2026, 8, 2), 1, directory
        )
    assert client.puts == []


def test_stage_dataset_file_detects_a_source_modified_mid_transfer(tmp_path: Path) -> None:
    """Refuse to report success when the held file changed while it was being staged."""
    source = tmp_path / "records.dat"
    source.write_bytes(b"CARDDEMO")
    client = _FakeS3()
    original = client.put_object

    def _rewrite_then_put(**kwargs: Any) -> dict[str, str]:
        """Append to the source between the digest pass and the identity re-check."""
        result = original(**kwargs)
        with open(source, "ab") as handle:
            handle.write(b"MORE")
        # WHY : Assumptions: the modification time is advanced explicitly. The append alone
        #   changes st_size, which this test's assertion would already catch, but pinning the
        #   timestamp forward keeps the scenario honest about what a real concurrent writer does
        #   and stops the test passing only because of filesystem timestamp granularity.
        os.utime(source, ns=(0, 0))
        return result

    client.put_object = _rewrite_then_put  # type: ignore[method-assign]

    with pytest.raises(DatasetSourceError, match="while it was being staged"):
        stage_dataset_file(
            client, _settings(), "ledger", "transact-bkup", date(2026, 8, 2), 1, source
        )


def test_reserve_generation_is_idempotent_for_one_execution() -> None:
    """Return the same generation when the same execution token reserves twice."""
    client = _FakeS3()
    settings = _settings()

    first = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )
    second = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )

    # WHY : Refactoring Rationale: this is the property the replaced list-then-maximum-then-add-one
    #   allocator could not hold. A retry re-ran the query, saw its own first attempt's completed
    #   write, and returned the NEXT number -- staging a second generation of identical bytes and
    #   consuming one of the five the family retains. Reuse is what makes a retry a rewrite.
    assert first == second == 1


def test_reserve_generation_gives_two_executions_distinct_generations() -> None:
    """Refuse to hand the same generation to two different execution tokens."""
    client = _FakeS3()
    settings = _settings()

    first = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )
    second = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-B"
    )

    assert (first, second) == (1, 2)
    assert first != second


def test_reserve_generation_advances_past_a_claim_another_writer_won() -> None:
    """Take the next number when the conditional create loses a race for the first."""
    client = _FakeS3()
    settings = _settings()
    # Assumptions: the competing claim is planted directly in the fake's object store rather
    #   than by calling the reservation, so the collision is on the FIRST candidate and the
    #   allocation loop must advance rather than merely returning what discovery suggested.
    client.objects["ledger/transact-bkup/dt=2026-08-02/_claims/gen=0001"] = b"exec-other"

    reserved = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )

    assert reserved == 2


def test_reserve_generation_loses_a_race_it_could_not_see_coming() -> None:
    """Refuse a generation another writer already claimed even when discovery cannot see it."""
    client = _FakeS3()
    settings = _settings()
    claim_prefix = "ledger/transact-bkup/dt=2026-08-02/_claims/"
    # WHY : Assumptions: the competing claim is planted in the object store while the claim prefix
    #   is registered to list as EMPTY, which is the only arrangement that exercises the
    #   conditional create as an arbiter. It reproduces the real race: two allocators list the
    #   prefix, both see nothing, both compute the same candidate, and one of them must be told
    #   no. Every other ordering lets DISCOVERY separate the two callers, so the condition is
    #   never consulted -- which was measured, not assumed. With the claims visible in the
    #   listing, deleting `IfNoneMatch` from the request changed no test result at all; this
    #   arrangement is what makes the exclusivity property observable.
    client.pages[("list_objects_v2", claim_prefix)] = [{}]
    client.objects[f"{claim_prefix}gen=0001"] = b"exec-other"

    reserved = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )

    assert reserved == 2
    # WHY : Assumptions: the refused attempt is also asserted, because returning 2 alone would
    #   pass if the allocator had simply started counting from 2 for an unrelated reason. Seeing
    #   gen=0001 attempted and gen=0002 written is what shows the first was tried and refused.
    attempted = [put["Key"] for put in client.puts if put.get("IfNoneMatch") == "*"]
    assert attempted == [f"{claim_prefix}gen=0001", f"{claim_prefix}gen=0002"]


def test_reserve_generation_refuses_a_blank_execution_token() -> None:
    """Refuse to reserve without an identity that can distinguish a retry from a new step."""
    client = _FakeS3()

    with pytest.raises(GenerationRetentionError, match="non-blank token"):
        reserve_generation(client, _settings(), "ledger", "transact-bkup", date(2026, 8, 2), "   ")


def test_reserve_generation_skips_a_generation_already_staged() -> None:
    """Reserve above the highest generation the bucket already holds for that date."""
    client = _FakeS3()
    _generation_pages(client)

    reserved = reserve_generation(
        client, _settings(), "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )

    # WHY : Assumptions: the registered pages hold gen=0001 and gen=0002 for this date, so the
    #   first free number is 3. This pins that the allocator considers STAGED generations and not
    #   only claims, which matters for a bucket written before claims existed -- otherwise it
    #   would re-offer gen=0001 and overwrite real data.
    assert reserved == 3


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
