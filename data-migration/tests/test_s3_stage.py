"""Verify S3 logical-generation staging and version-aware SCRATCH cleanup."""

from __future__ import annotations

import base64
import hashlib
import io
import os
import re
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Final

import pytest

from carddemo_migration.config import DatasetStagingSettings
from carddemo_migration.loaders import s3_stage
from carddemo_migration.loaders.s3_stage import (
    PSEUDO_FILESYSTEM_ROOTS,
    DatasetSourceError,
    GenerationConflictError,
    GenerationRetentionError,
    StagingServiceError,
    delete_generation_prefix,
    extract_source_key,
    fetch_dataset_extract,
    fetch_object_to_path,
    list_generation_prefixes,
    parse_object_uri,
    prune_generations,
    reserve_generation,
    sanitized_for_log,
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
        """Return pages registered for the operation and requested prefix.

        Purpose
        -------
        Serve the module's listings either from pages a test registered or, failing that, from
        the objects the fake actually holds -- honouring ``Delimiter`` the way the service does,
        so a listing sees the same prefixes a real bucket would report.

        Parameters
        ----------
        **kwargs : Any
            Listing arguments. ``Prefix`` selects the pages; ``Delimiter`` selects between a
            key listing and a rolled-up common-prefix listing.

        Returns
        -------
        list[dict[str, Any]]
            One or more response pages, each carrying ``Contents``, ``CommonPrefixes`` or
            neither.

        Raises
        ------
        None
            An unregistered prefix over an empty store yields a single empty page, which is what
            the service returns for a prefix nothing is stored under.
        """
        prefix = kwargs["Prefix"]
        registered = self._client.pages.get((self._operation, prefix))
        if registered is not None:
            return registered
        # WHY : Assumptions: an unregistered prefix answers with the objects the fake has been
        #   made to hold, filtered by prefix, rather than with an empty page. Reservation markers
        #   are created by the code under test rather than pre-registered, so a fake that only
        #   ever replayed registered pages could never show the allocator its own earlier claim
        #   -- which is precisely the state the retry-reuse and collision tests turn on.
        matching = [key for key in sorted(self._client.objects) if key.startswith(prefix)]
        delimiter = kwargs.get("Delimiter")
        if delimiter:
            # WHY : Refactoring Rationale: a DELIMITED listing now rolls the matching keys up into
            #   common prefixes, where this double returned raw keys under every listing. The
            #   difference decides a real property rather than a detail of the double: a
            #   reservation marker lives INSIDE the ``gen=NNNN/`` prefix it reserves, so it is
            #   only the roll-up that makes a claimed-but-unstaged generation visible to
            #   `list_generation_prefixes` -- and that visibility is the whole reason the marker
            #   sits there rather than in a sibling prefix. Returning keys instead would have let
            #   the allocator re-offer a number another writer had already claimed and the suite
            #   would have reported it as correct.
            rolled: list[str] = []
            for key in matching:
                remainder = key[len(prefix) :]
                head, separator, _ = remainder.partition(delimiter)
                if not separator:
                    continue
                candidate = f"{prefix}{head}{delimiter}"
                if candidate not in rolled:
                    rolled.append(candidate)
            return [{"CommonPrefixes": [{"Prefix": item} for item in rolled]}] if rolled else [{}]
        contents = [{"Key": key} for key in matching]
        return [{"Contents": contents}] if contents else [{}]


class _FakeS3:
    """Record S3 calls while serving pre-arranged paginator pages."""

    def __init__(self) -> None:
        """Create empty page, object, write, read and delete registries."""
        self.pages: dict[tuple[str, str], list[dict[str, Any]]] = {}
        self.objects: dict[str, bytes] = {}
        self.head_calls: list[dict[str, Any]] = []
        self.metadata: dict[str, dict[str, str]] = {}
        self.get_overrides: dict[str, dict[str, Any]] = {}
        self.puts: list[dict[str, Any]] = []
        self.gets: list[dict[str, Any]] = []
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
        # WHY : Assumptions: the METADATA a put carries is stored beside the body, because the
        #   existence probe reads the digest the staging step recorded and decides idempotence from
        #   it. A double that dropped the metadata reported every re-stage of identical bytes as a
        #   different-bytes conflict, which is the opposite of the behaviour under test.
        metadata = kwargs.get("Metadata")
        if isinstance(metadata, dict):
            self.metadata[key] = {str(name): str(value) for name, value in metadata.items()}
        return {"ETag": "fake"}

    def head_object(self, **kwargs: Any) -> dict[str, Any]:
        """Return a stored object's metadata and length, without its body.

        Purpose
        -------
        Serve the existence probe the staging loader issues before it writes a generation, so a
        retry can tell "the same bytes are already there" from "different bytes are already
        there" without transferring anything.

        Parameters
        ----------
        **kwargs : Any
            The head arguments; ``Bucket`` and ``Key`` are read.

        Returns
        -------
        dict[str, Any]
            The stored metadata and the content length, and deliberately no ``Body``.

        Raises
        ------
        _ConditionalConflict
            Reported as ``404`` when the key holds nothing, which the loader must read as
            "nothing is staged there yet" rather than as a failure.
        """
        # WHY : Assumptions: this double answers the probe from the SAME registries `put_object`
        #   writes and `get_object` reads, so a probe cannot disagree with a body the test wrote.
        #   A separate registry was the alternative and would have let a test stage bytes the
        #   probe could not see, which is the one arrangement the idempotency branch must not be
        #   exercised under.
        key = kwargs["Key"]
        if key not in self.objects:
            raise _ConditionalConflict("404")
        self.head_calls.append(kwargs)
        response: dict[str, Any] = {
            "ContentLength": len(self.objects[key]),
            "Metadata": dict(self.metadata.get(key, {})),
        }
        response.update(self.get_overrides.get(key, {}))
        return response

    def get_object(self, **kwargs: Any) -> dict[str, Any]:
        """Return a stored object body wrapped the way the SDK returns it."""
        key = kwargs["Key"]
        if key not in self.objects:
            raise _ConditionalConflict("NoSuchKey")
        self.gets.append(kwargs)
        body = self.objects[key]
        # WHY : Refactoring Rationale: the response now carries the LENGTH and any METADATA the
        #   key was stored with, where it used to carry the body alone. The extract fetch checks
        #   what arrived against what the object publishes, so a double that published nothing
        #   could exercise only the branch where there is nothing to check -- which is the branch
        #   that cannot detect a truncated transfer.
        response: dict[str, Any] = {
            "Body": io.BytesIO(body),
            "ContentLength": len(body),
            "Metadata": dict(self.metadata.get(key, {})),
        }
        # WHY : Assumptions: an override is MERGED LAST so a test can publish a length or a
        #   checksum that disagrees with the body on purpose. Corruption in transit is otherwise
        #   unreachable here: this double is the storage as well as the transport, so its body and
        #   its metadata always agree unless a test makes them disagree.
        response.update(self.get_overrides.get(key, {}))
        return response

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


#: Instant the allocation-order cases date their claim markers from.
#:
#: WHY : Assumptions: a fixed literal is used rather than a value read from the clock, for the
#: reason every other fixture in this suite states -- a clock read makes the ordering under test
#: depend on when the suite runs, and the property being asserted is a RELATIVE order that a
#: fixed base expresses exactly.
_ALLOCATION_BASE: Final[datetime] = datetime(2026, 9, 1, 12, 0, 0, tzinfo=timezone.utc)


def _claim_marker(client: _FakeS3, generation_prefix: str, minutes_after_base: int) -> None:
    """Give one generation a claim marker with a chosen allocation instant.

    Purpose
    -------
    Let a test state when a generation was ALLOCATED independently of the business date in its
    key, which is the whole distinction generation retention has to make.

    Parameters
    ----------
    client : _FakeS3
        The double whose registries the marker is written into.
    generation_prefix : str
        Prefix of the generation being marked, ending in ``/``.
    minutes_after_base : int
        Offset from :data:`_ALLOCATION_BASE`; a larger value is a more recent allocation.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY : Assumptions: the marker is written into BOTH registries the double answers from --
    #   `objects` so `head_object` finds it rather than reporting 404, and `get_overrides` so the
    #   response carries a `LastModified`. Writing only the override would leave the head raising
    #   404 and the generation ordering at the sentinel, which is the state these tests exist to
    #   distinguish from a real instant.
    key = f"{generation_prefix}_generation.claim"
    client.objects[key] = b"run-token"
    client.get_overrides[key] = {
        "LastModified": _ALLOCATION_BASE + timedelta(minutes=minutes_after_base)
    }


def test_prune_generations_retires_the_oldest_allocation_not_the_oldest_key_date() -> None:
    """Order the retention window by allocation instant rather than by the date in the key."""
    client = _FakeS3()
    _generation_pages(client)
    family = "ledger/transact-bkup/"
    # WHY : Assumptions: the allocation order is deliberately the REVERSE of the key-date order,
    #   because that is the only arrangement in which the two orderings disagree and therefore the
    #   only one that can tell them apart. Under the previous ordering the victim would have been
    #   dt=2026-08-01/gen=0001 -- the earliest date -- while it is in fact the most recently
    #   allocated generation of the four.
    _claim_marker(client, f"{family}dt=2026-08-02/gen=0002/", 0)
    _claim_marker(client, f"{family}dt=2026-08-02/gen=0001/", 10)
    _claim_marker(client, f"{family}dt=2026-08-01/gen=0002/", 20)
    _claim_marker(client, f"{family}dt=2026-08-01/gen=0001/", 30)
    settings = DatasetStagingSettings(bucket="datasets", environment="dev")

    deleted = prune_generations(client, settings, "ledger", "transact-bkup", 3)

    assert deleted == (f"{family}dt=2026-08-02/gen=0002/",)


def test_prune_generations_never_scratches_the_generation_this_call_staged() -> None:
    """Withhold the caller's own generation, so a back-dated run cannot delete its own output."""
    client = _FakeS3()
    _generation_pages(client)
    family = "ledger/transact-bkup/"
    # WHY : Assumptions: this reproduces the reported defect exactly. A run whose business date
    #   precedes every existing partition allocates a generation that sorts FIRST by key date, so
    #   the staging call that created it selected it as the oldest and scratched it while logging a
    #   successful stage. Here the fresh generation is additionally given no marker, so it orders
    #   at the sentinel and the ordering alone would still choose it -- which is what makes the
    #   protection, rather than the ordering, the thing this case decides.
    backdated = f"{family}dt=2026-07-01/gen=0001/"
    client.pages[("list_objects_v2", family)] = [
        {
            "CommonPrefixes": [
                {"Prefix": f"{family}dt=2026-07-01/"},
                {"Prefix": f"{family}dt=2026-08-01/"},
                {"Prefix": f"{family}dt=2026-08-02/"},
            ]
        }
    ]
    client.pages[("list_objects_v2", f"{family}dt=2026-07-01/")] = [
        {"CommonPrefixes": [{"Prefix": backdated}]}
    ]
    _claim_marker(client, f"{family}dt=2026-08-01/gen=0001/", 0)
    _claim_marker(client, f"{family}dt=2026-08-01/gen=0002/", 10)
    _claim_marker(client, f"{family}dt=2026-08-02/gen=0001/", 20)
    _claim_marker(client, f"{family}dt=2026-08-02/gen=0002/", 30)
    settings = DatasetStagingSettings(bucket="datasets", environment="dev")

    protected = prune_generations(
        client, settings, "ledger", "transact-bkup", 4, protected_prefix=backdated
    )

    assert protected == ()
    assert client.deletes == []
    # WHY : Assumptions: the same call WITHOUT the protection is asserted immediately afterwards,
    #   because "nothing was deleted" is also what a broken discovery would report. Showing that
    #   the unprotected form does select this prefix establishes that the ordering really did
    #   choose it and the guard is what spared it.
    unprotected = prune_generations(client, settings, "ledger", "transact-bkup", 4)

    assert unprotected == (backdated,)


def test_prune_generations_refuses_when_a_generation_age_probe_fails() -> None:
    """Raise rather than order a generation at the sentinel when its marker cannot be read."""

    class _RefusingHead(_FakeS3):
        """A double whose claim-marker probe fails with a non-absent service code."""

        def head_object(self, **kwargs: Any) -> dict[str, Any]:
            """Refuse every claim-marker probe with an access failure.

            Purpose
            -------
            Produce the one condition that must NOT be read as "this generation has no marker".

            Parameters
            ----------
            **kwargs : Any
                The head arguments; ``Key`` is read.

            Returns
            -------
            dict[str, Any]
                Never returns for a claim marker; delegates for any other key.

            Raises
            ------
            _ConditionalConflict
                Carrying ``AccessDenied``, which is not an absent-object code.
            """
            if str(kwargs["Key"]).endswith("_generation.claim"):
                raise _ConditionalConflict("AccessDenied")
            return super().head_object(**kwargs)

    client = _RefusingHead()
    _generation_pages(client)
    settings = DatasetStagingSettings(bucket="datasets", environment="dev")

    # WHY : Assumptions: the failure must surface rather than default, because ordering an
    #   unreadable generation at the sentinel puts it FIRST in line for permanent deletion -- so a
    #   transient permission fault would be indistinguishable from an aged-out generation and
    #   would scratch live data. Nothing may be deleted on the way to the refusal either, which is
    #   why the delete log is asserted empty as well as the exception being raised.
    with pytest.raises(StagingServiceError):
        prune_generations(client, settings, "ledger", "transact-bkup", 2)

    assert client.deletes == []


#: Date partition every reservation test in this module allocates under.
#:
#: WHY : Assumptions: the reservation tests share ONE partition literal rather than each
#: spelling it out, because the marker key, the listing registration and the assertion all have
#: to name the same partition for a race to be reproduced at all -- and three independent
#: literals is how one of them ends up naming a different date and the test passes by allocating
#: from an empty partition instead of a contended one.
_DATE_PREFIX: Final[str] = "ledger/transact-bkup/dt=2026-08-02/"
#: The marker object name the reservation contract puts inside each ``gen=NNNN/`` prefix.
#:
#: WHY : Assumptions: this is read from the module under test rather than transcribed, so a test
#: cannot assert a spelling the implementation has stopped using. The cross-language tests below
#: are what pin the spelling itself, against the batch tier's own declaration.
_MARKER_NAME: Final[str] = s3_stage._CLAIM_OBJECT_NAME


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
        staging_root=source.parent,
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
        client,
        _settings(),
        "ledger",
        "transact-bkup",
        date(2026, 8, 2),
        1,
        source,
        staging_root=source.parent,
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
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            link,
            staging_root=link.parent,
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
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            directory,
            staging_root=directory.parent,
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
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            source,
            staging_root=source.parent,
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
    # WHY : Assumptions: the competing marker is planted directly in the fake's object store
    #   rather than by calling the reservation, so the collision is on the FIRST candidate and the
    #   allocation loop must advance rather than merely returning what discovery suggested.
    client.objects[f"{_DATE_PREFIX}gen=0001/{_MARKER_NAME}"] = b"exec-other"

    reserved = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )

    assert reserved == 2


def test_reserve_generation_loses_a_race_it_could_not_see_coming() -> None:
    """Refuse a generation another writer already claimed even when discovery cannot see it."""
    client = _FakeS3()
    settings = _settings()
    # WHY : Assumptions: the competing marker is planted in the object store while the date
    #   partition is registered to list as EMPTY, which is the only arrangement that exercises the
    #   conditional create as an arbiter. It reproduces the real race: two allocators list the
    #   partition, both see nothing, both compute the same candidate, and one of them must be told
    #   no. Every other ordering lets DISCOVERY separate the two callers, so the condition is
    #   never consulted -- which was measured, not assumed. With the marker visible in the
    #   listing, deleting `IfNoneMatch` from the request changed no test result at all; this
    #   arrangement is what makes the exclusivity property observable.
    client.pages[("list_objects_v2", _DATE_PREFIX)] = [{}]
    client.objects[f"{_DATE_PREFIX}gen=0001/{_MARKER_NAME}"] = b"exec-other"

    reserved = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )

    assert reserved == 2
    # WHY : Assumptions: the refused attempt is also asserted, because returning 2 alone would
    #   pass if the allocator had simply started counting from 2 for an unrelated reason. Seeing
    #   gen=0001 attempted and gen=0002 written is what shows the first was tried and refused.
    #   The replay record is filtered out of the comparison by prefix rather than by position,
    #   because it is written through the same conditional create and would otherwise appear here
    #   as a third entry that says nothing about the generation race.
    attempted = [
        put["Key"]
        for put in client.puts
        if put.get("IfNoneMatch") == "*" and put["Key"].startswith(_DATE_PREFIX)
    ]
    assert attempted == [
        f"{_DATE_PREFIX}gen=0001/{_MARKER_NAME}",
        f"{_DATE_PREFIX}gen=0002/{_MARKER_NAME}",
    ]


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


#: The batch tier's generation allocator, read as text for the shared-contract assertions.
#:
#: WHY : Assumptions: the two implementations are located relative to THIS FILE rather than to
#: the working directory, matching ``tests/test_config_name_contract.py``, so the assertions hold
#: whether the suite is invoked from the repository root or from ``data-migration``.
_JAVA_ALLOCATOR: Final[Path] = (
    Path(__file__).resolve().parents[2]
    / "services"
    / "batch-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "carddemo"
    / "batch"
    / "service"
    / "DatasetGenerationService.java"
)
#: The batch tier's family enumeration, whose constant names are the replay-record family tokens.
_JAVA_FAMILY_ENUM: Final[Path] = (
    Path(__file__).resolve().parents[2]
    / "services"
    / "batch-service"
    / "src"
    / "main"
    / "java"
    / "com"
    / "carddemo"
    / "batch"
    / "dto"
    / "DatasetGeneration.java"
)
#: The Terraform module that provisions the bucket and scopes the replay records' lifecycle rule.
_DATASETS_MODULE_MAIN_TF: Final[Path] = (
    Path(__file__).resolve().parents[2] / "infra" / "modules" / "s3-datasets" / "main.tf"
)


def _java_string_constant(source: Path, name: str) -> str:
    """Read one ``String`` constant's literal value out of a Java source file.

    Purpose
    -------
    Compare this package's reservation literals against the batch tier's own declarations
    without compiling Java or duplicating the values here, which is what would let the two
    drift while every test still passed.

    Parameters
    ----------
    source : Path
        The Java file to read.
    name : str
        The constant's identifier, for example ``CLAIM_OBJECT_NAME``.

    Returns
    -------
    str
        The declared literal, with the surrounding quotes removed.

    Raises
    ------
    AssertionError
        If the file declares no such ``String`` constant, which means the batch tier renamed or
        removed it and this contract no longer has two sides.
    """
    # WHY : Assumptions: the pattern accepts any modifier order and either visibility, because
    #   two of the four literals are private to the allocator and two are public. Requiring
    #   `public` would have made the private pair unreadable here and left half the key shape
    #   unasserted, which is the half a reader is least likely to check by hand.
    match = re.search(
        rf'\bString\s+{re.escape(name)}\s*=\s*"([^"]*)"\s*;',
        source.read_text(encoding="utf-8"),
    )
    assert match is not None, f"{source} declares no String constant named {name}"
    return match.group(1)


def _java_dataset_families() -> dict[str, tuple[str, str]]:
    """Read the batch tier's ten family constants as name to domain and dataset segment.

    Purpose
    -------
    Recover the enum constant NAMES, which are the family tokens the batch tier writes into a
    replay record's key, together with the path segments this package keys its own registry on.

    Returns
    -------
    dict[str, tuple[str, str]]
        Constant name mapped to its domain segment and its dataset segment.

    Raises
    ------
    AssertionError
        If the enumeration cannot be located, so an empty result can never be mistaken for
        agreement.
    """
    source = _JAVA_FAMILY_ENUM.read_text(encoding="utf-8")
    # WHY : Assumptions: the constants are matched on the three-argument constructor call the
    #   enum declares -- baseline base name, domain, dataset segment -- rather than on
    #   indentation or on a bare identifier. The file also declares a second enumeration whose
    #   constants take ONE argument, so an identifier-only pattern would collect those too and
    #   the count assertion below would report agreement over the wrong set.
    declared = {
        match.group(1): (match.group(3), match.group(4))
        for match in re.finditer(
            r'\b([A-Z][A-Z0-9_]*)\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*\)',
            source,
        )
    }
    assert declared, f"{_JAVA_FAMILY_ENUM} declares no three-argument family constants"
    return declared


def test_the_reservation_object_names_match_the_batch_tier_declarations() -> None:
    """Declare the claim marker and the replay-record key shape identically in both tiers."""
    # WHY : Assumptions: all four literals are asserted rather than only the marker name,
    #   because a replay record is found by its WHOLE key. Agreeing on the root while disagreeing
    #   on `run=` or on `/family=` produces two records per run under one root, and each tier
    #   then reads the one it wrote and allocates a second generation for the same retry -- the
    #   exact defect this contract exists to prevent, with no listing anywhere that looks wrong.
    assert _java_string_constant(_JAVA_ALLOCATOR, "CLAIM_OBJECT_NAME") == (
        s3_stage._CLAIM_OBJECT_NAME
    )
    assert _java_string_constant(_JAVA_ALLOCATOR, "RUN_CLAIM_ROOT") == s3_stage._RUN_CLAIM_ROOT
    assert _java_string_constant(_JAVA_ALLOCATOR, "RUN_CLAIM_RUN_MARKER") == (
        s3_stage._RUN_CLAIM_RUN_MARKER
    )
    assert _java_string_constant(_JAVA_ALLOCATOR, "RUN_CLAIM_FAMILY_SEPARATOR") == (
        s3_stage._RUN_CLAIM_FAMILY_SEPARATOR
    )


def test_the_replay_record_root_matches_the_terraform_module_declaration() -> None:
    """Scope the module's lifecycle rule and task-role grant to the prefix both tiers write."""
    match = re.search(
        r'generation_claim_prefix\s*=\s*"([^"]*)"',
        _DATASETS_MODULE_MAIN_TF.read_text(encoding="utf-8"),
    )

    # WHY : Assumptions: the Terraform local is asserted as the THIRD side of the same contract
    #   rather than assumed to follow the code. It is what the environment roots publish as the
    #   `generation_claim_prefix` output and scope the batch task role's Get and Put to, so a
    #   prefix that agrees between the two languages and disagrees with the module produces an
    #   AccessDenied on the first allocation of a deployed run -- green everywhere a test looks.
    assert match is not None, f"{_DATASETS_MODULE_MAIN_TF} declares no generation_claim_prefix"
    assert match.group(1) == s3_stage._RUN_CLAIM_ROOT


def test_every_generation_family_maps_to_a_batch_tier_enum_constant() -> None:
    """Derive each family token from the dataset segment exactly as the batch tier names it."""
    declared = _java_dataset_families()

    # WHY : Assumptions: the two inventories are compared as SETS of dataset segments first, so a
    #   family added to one tier and not the other fails here rather than at the token
    #   derivation. A missing family would otherwise simply not be iterated and the loop below
    #   would pass over ten agreements while the eleventh family had no counterpart at all.
    assert {segment for _, segment in declared.values()} == set(s3_stage.GENERATION_FAMILIES)
    assert len(declared) == len(s3_stage.GENERATION_FAMILIES) == 10

    for constant, (domain, segment) in declared.items():
        family = s3_stage.GENERATION_FAMILIES[segment]
        assert family.domain == domain
        # WHY : Assumptions: the token this package DERIVES is held against the constant name the
        #   batch tier DECLARES, one family at a time. That is the pair a replay record's key is
        #   built from on each side, so an upper-casing or hyphen rule that held for nine
        #   families and not the tenth would be caught on the tenth instead of averaging out.
        assert s3_stage._run_claim_family_token(segment) == constant


def test_reserve_generation_skips_a_generation_the_batch_tier_claimed() -> None:
    """Refuse a number the Java allocator already claimed, reading its marker as it wrote it."""
    client = _FakeS3()
    settings = _settings()
    # WHY : Assumptions: the marker key is composed from the literal read out of the JAVA source
    #   rather than from this package's own constant, which is what makes this a cross-language
    #   case rather than a second self-consistency check. The body is a run identifier the batch
    #   tier would have written, and it is deliberately not one this suite reserves under.
    claimed_by_java = (
        f"{_DATE_PREFIX}gen=0001/{_java_string_constant(_JAVA_ALLOCATOR, 'CLAIM_OBJECT_NAME')}"
    )
    client.objects[claimed_by_java] = b"batch-run-0001"

    reserved = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "exec-A"
    )

    # WHY : Assumptions: skipping is asserted through the RESERVED NUMBER and through the marker
    #   the Java claim is still holding, because a Python writer that had overwritten the marker
    #   would also return 2 on the next call and the number alone cannot tell the two apart.
    assert reserved == 2
    assert client.objects[claimed_by_java] == b"batch-run-0001"


def test_reserve_generation_replays_the_generation_the_batch_tier_recorded() -> None:
    """Return the number the Java allocator recorded for this run rather than taking a new one."""
    client = _FakeS3()
    settings = _settings()
    root = _java_string_constant(_JAVA_ALLOCATOR, "RUN_CLAIM_ROOT")
    run_marker = _java_string_constant(_JAVA_ALLOCATOR, "RUN_CLAIM_RUN_MARKER")
    separator = _java_string_constant(_JAVA_ALLOCATOR, "RUN_CLAIM_FAMILY_SEPARATOR")
    # WHY : Assumptions: the record is planted under a key composed entirely from the Java
    #   declarations, including the family token, so this case fails if either tier changes any
    #   part of the key shape. The body is the unpadded decimal form the batch tier writes --
    #   `Integer.toString` -- and NOT the zero-padded `gen=` segment, because a record written
    #   with the padded form would parse to the same number here and hide a real divergence.
    client.objects[f"{root}{run_marker}batch-run-0007{separator}TRANSACT_BKUP"] = b"7"

    replayed = reserve_generation(
        client, settings, "ledger", "transact-bkup", date(2026, 8, 2), "batch-run-0007"
    )

    assert replayed == 7
    # WHY : Assumptions: the absence of any write is asserted alongside the number. A replay that
    #   returned 7 and still created a marker or a second record would consume nothing visible in
    #   the return value while leaving bookkeeping the other tier would later read as a claim.
    assert client.puts == []


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


@pytest.mark.parametrize(
    ("location", "expected"),
    [
        ("s3://datasets/source-extracts", ("datasets", "source-extracts")),
        ("s3://datasets/source-extracts/", ("datasets", "source-extracts")),
        ("s3://datasets/nested/prefix", ("datasets", "nested/prefix")),
        ("s3://datasets", ("datasets", "")),
        ("s3://datasets/", ("datasets", "")),
        ("/mnt/carddemo-extracts", None),
        ("relative/extracts", None),
    ],
)
def test_parse_object_uri_classifies_a_location(
    location: str, expected: tuple[str, str] | None
) -> None:
    """Split an object-storage location and report a filesystem one as not being a URI.

    Purpose
    -------
    Pin the two spellings an operator naturally writes -- with and without a trailing separator --
    to the same bucket and prefix, because a doubled or missing separator produces a key that
    exists nowhere rather than an error naming the mistake.

    Parameters
    ----------
    location : str
        The location to classify.
    expected : tuple[str, str] or None
        The bucket and normalised prefix, or ``None`` for a filesystem path.

    Returns
    -------
    None
        Nothing; a mis-split location is reported as an assertion failure.

    Raises
    ------
    None
    """
    assert parse_object_uri(location) == expected


@pytest.mark.parametrize("location", ["s3://", "s3:///prefix", "s3:// bucket/prefix"])
def test_parse_object_uri_refuses_a_bucketless_location(location: str) -> None:
    """Refuse a location carrying the scheme with no usable bucket, rather than falling back."""
    # WHY : Assumptions: a malformed URI is REFUSED rather than treated as a relative path named
    #   "s3:", which is what a permissive classifier would hand back. The refusal names the
    #   accepted form, so an operator who mistyped the bucket learns that rather than meeting a
    #   missing-file error for a location they plainly meant as a bucket.
    with pytest.raises(ValueError, match="bucket"):
        parse_object_uri(location)


class _SourceObjectS3:
    """Serve source-extract bodies with a declared length, as the SDK's own response does.

    Purpose
    -------
    Exercise the inbound transfer against a double that reports ``ContentLength``, which the
    module's shared double deliberately omits. The declared length is what makes a truncated
    transfer detectable, so a test of that refusal needs a response that carries one.

    Attributes
    ----------
    objects : dict[str, bytes]
        Bodies served, keyed by object key.
    declared_length : int or None
        Length to report instead of the true one, or ``None`` to report the true one. Set it to
        provoke the mismatch a truncated transfer produces.
    """

    def __init__(self) -> None:
        """Create an empty store reporting truthful lengths.

        Returns
        -------
        None
            Initialises the served bodies and the length override.

        Raises
        ------
        None
        """
        self.objects: dict[str, bytes] = {}
        self.declared_length: int | None = None

    def get_object(self, **kwargs: Any) -> dict[str, Any]:
        """Return one served body as a stream, with the length this double declares.

        Parameters
        ----------
        **kwargs : Any
            The get arguments; ``Key`` is read.

        Returns
        -------
        dict[str, Any]
            A response carrying ``Body`` and ``ContentLength``.

        Raises
        ------
        KeyError
            If the key is not served, standing in for the service's absent-object error.
        """
        key = kwargs["Key"]
        if key not in self.objects:
            raise KeyError(f"no object is served at {key!r}")
        body = self.objects[key]
        length = self.declared_length if self.declared_length is not None else len(body)
        return {"Body": io.BytesIO(body), "ContentLength": length}


def test_fetch_object_to_path_copies_bytes_verbatim(tmp_path: Path) -> None:
    """Copy a source extract to a local path byte for byte and report the count.

    Purpose
    -------
    Establish the inbound half of the module's verbatim-transfer guarantee. The staging and load
    commands both read the fetched file as a fixed-length EBCDIC image, so a text decode, a
    newline translation or a truncation here would corrupt every record downstream.

    Parameters
    ----------
    tmp_path : Path
        Scratch directory the copy is written into.

    Returns
    -------
    None
        Nothing; a mismatch in bytes or in the reported count is an assertion failure.

    Raises
    ------
    None
    """
    client = _SourceObjectS3()
    payload = bytes(range(256)) * 3
    client.objects["source-extracts/AWS.M2.CARDDEMO.ACCTDATA.PS"] = payload
    destination = tmp_path / "ACCTDATA.PS"

    written = fetch_object_to_path(
        client=client,
        bucket="datasets",
        key="source-extracts/AWS.M2.CARDDEMO.ACCTDATA.PS",
        destination=destination,
    )

    assert written == len(payload)
    # Assumptions: the payload cycles all 256 byte values, which is what a text decode or a
    #   newline translation would visibly corrupt while leaving a length-only assertion green.
    assert destination.read_bytes() == payload


def test_fetch_object_to_path_refuses_a_truncated_transfer(tmp_path: Path) -> None:
    """Refuse a transfer that received fewer bytes than the service declared."""
    client = _SourceObjectS3()
    client.objects["extracts/short.dat"] = b"1234567890"
    client.declared_length = 40

    # WHY : Assumptions: a short copy is refused HERE rather than left to the record-geometry
    #   check, because a truncation that happens to land on a record boundary would pass that
    #   check and load a silently short dataset -- which is the one outcome a verification pass
    #   comparing row counts against the same truncated file could not detect either.
    with pytest.raises(DatasetSourceError, match="incomplete"):
        fetch_object_to_path(
            client=client,
            bucket="datasets",
            key="extracts/short.dat",
            destination=tmp_path / "short.dat",
        )


def test_fetch_object_to_path_reports_an_unreadable_object(tmp_path: Path) -> None:
    """Report an absent or refused object as a named source failure, not a client error."""
    client = _SourceObjectS3()

    with pytest.raises(DatasetSourceError, match="could not be read"):
        fetch_object_to_path(
            client=client,
            bucket="datasets",
            key="extracts/absent.dat",
            destination=tmp_path / "absent.dat",
        )


_BINARY_EXTRACT: Final[bytes] = bytes(range(256)) * 2 + b"\xc0\xd0\x4b\x00"


def test_extract_source_key_joins_the_prefix_and_the_registered_name() -> None:
    """Compose one key with exactly one separator, whatever spelling the prefix arrives in.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the two spellings of a prefix do not compose the same key.
    """
    expected = "migration/source/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS"
    assert extract_source_key("migration/source/EBCDIC/", "AWS.M2.CARDDEMO.ACCTDATA.PS") == expected
    assert extract_source_key("migration/source/EBCDIC", "AWS.M2.CARDDEMO.ACCTDATA.PS") == expected
    assert extract_source_key(" migration/source/EBCDIC/ ", " AWS.M2.CARDDEMO.ACCTDATA.PS ") == (
        expected
    )


@pytest.mark.parametrize(
    ("prefix", "source_object"),
    [
        # WHY : a blank prefix would read from the bucket ROOT, which is outside the prefix the
        #   deployment grants and where nothing is uploaded -- so it is refused rather than
        #   treated as "no prefix".
        ("", "AWS.M2.CARDDEMO.ACCTDATA.PS"),
        ("   ", "AWS.M2.CARDDEMO.ACCTDATA.PS"),
        # WHY : a LEADING separator names a key whose first segment is empty. It is a different,
        #   working prefix, so accepting it would read an object nothing wrote and report success.
        ("/migration/source/EBCDIC/", "AWS.M2.CARDDEMO.ACCTDATA.PS"),
        ("migration/source/EBCDIC/", ""),
        # WHY : a separator inside the NAME would move the read out of the granted prefix, which
        #   is the one composition error here that could reach another prefix's object.
        ("migration/source/EBCDIC/", "../EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS"),
        ("migration/source/EBCDIC/", "EBCDIC\\ACCTDATA.PS"),
    ],
)
def test_extract_source_key_refuses_a_composition_that_would_leave_the_prefix(
    prefix: str, source_object: str
) -> None:
    """Refuse every spelling that would read outside the provisioned prefix.

    Parameters
    ----------
    prefix : str
        The prefix under test.
    source_object : str
        The extract name under test.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a composition that leaves the prefix is admitted.
    """
    with pytest.raises(DatasetSourceError):
        extract_source_key(prefix, source_object)


def test_fetch_dataset_extract_writes_the_bytes_verbatim_and_digests_them(tmp_path: Path) -> None:
    """Materialise an extract byte for byte, report its digest, and ask for its checksum.

    Purpose
    -------
    The extracts are fixed-block mainframe-character-set files whose sign-overpunch and
    packed-decimal bytes are not text in any encoding, so the transfer has to be binary end to end.
    This asserts the written file against the source bytes rather than against a printable sample,
    which is what makes a newline translation or an encoding round-trip visible.

    Parameters
    ----------
    tmp_path : Path
        Scratch directory the extract is written into.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the bytes, the count, the digest or the requested checksum mode differ.
    """
    client = _FakeS3()
    key = "migration/source/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS"
    client.objects[key] = _BINARY_EXTRACT
    destination = tmp_path / "AWS.M2.CARDDEMO.TRANTYPE.PS"

    fetched = fetch_dataset_extract(
        client=client,
        settings=_settings(),
        prefix="migration/source/EBCDIC/",
        source_object="AWS.M2.CARDDEMO.TRANTYPE.PS",
        destination=destination,
    )

    assert destination.read_bytes() == _BINARY_EXTRACT
    assert fetched.key == key
    assert fetched.path == destination
    assert fetched.byte_size == len(_BINARY_EXTRACT)
    assert fetched.sha256 == hashlib.sha256(_BINARY_EXTRACT).hexdigest()
    # WHY : the checksum mode is asserted because without it the service does not return a stored
    #   SHA-256 at all, so the strongest end-to-end check would be silently unavailable while the
    #   code that compares it looked correct.
    assert client.gets and client.gets[0]["ChecksumMode"] == "ENABLED"
    # WHY : the digest and the byte count are asserted absent from the rendering's INPUTS but
    #   present in it, because this line is written to a log an operator reads while a nightly
    #   window is open, and no byte of an extract carrying balances and card numbers may be in it.
    assert fetched.describe() == f"{key} ({len(_BINARY_EXTRACT)} bytes, sha256 {fetched.sha256})"


def test_fetch_dataset_extract_checks_the_digest_this_package_publishes(tmp_path: Path) -> None:
    """Refuse an object whose own recorded digest disagrees with the bytes that arrived.

    Purpose
    -------
    An extract may have been placed by this distribution's own staging writer, which records
    ``carddemo-sha256`` and ``carddemo-byte-size`` on every object it writes. Reading them back
    closes the loop on a copy this package made itself, and a disagreement is corruption rather
    than a difference of opinion.

    Parameters
    ----------
    tmp_path : Path
        Scratch directory the extract would be written into.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the mismatch is admitted, or the refusal does not name the metadata key.
    """
    client = _FakeS3()
    key = "migration/source/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS"
    client.objects[key] = _BINARY_EXTRACT
    client.metadata[key] = {"carddemo-sha256": hashlib.sha256(b"other bytes").hexdigest()}

    with pytest.raises(DatasetSourceError, match="carddemo-sha256"):
        fetch_dataset_extract(
            client=client,
            settings=_settings(),
            prefix="migration/source/EBCDIC/",
            source_object="AWS.M2.CARDDEMO.TRANTYPE.PS",
            destination=tmp_path / "extract.PS",
        )


@pytest.mark.parametrize(
    ("override", "expected"),
    [
        # WHY : a declared length below what arrived is what a truncated transfer looks like from
        #   the service's side, and it is the failure most likely to produce plausible wrong data:
        #   a short fixed-block extract still divides into whole records.
        ({"ContentLength": 11}, "truncated"),
        # WHY : a published SHA-256 that disagrees is corruption the service has detected the
        #   other half of, so it is refused even though the transfer itself reported success.
        (
            {"ChecksumSHA256": base64.b64encode(hashlib.sha256(b"other").digest()).decode()},
            "SHA-256",
        ),
        ({"Metadata": {"carddemo-byte-size": "11"}}, "carddemo-byte-size"),
    ],
)
def test_fetch_dataset_extract_refuses_a_published_value_that_disagrees(
    override: dict[str, Any], expected: str, tmp_path: Path
) -> None:
    """Refuse every published value that contradicts the bytes that arrived.

    Parameters
    ----------
    override : dict[str, Any]
        The response member to publish in contradiction of the body.
    expected : str
        Text the refusal must carry, so each case is distinguished by its own diagnosis.
    tmp_path : Path
        Scratch directory the extract would be written into.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a contradiction is admitted, or the wrong contradiction is reported.
    """
    client = _FakeS3()
    key = "migration/source/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS"
    client.objects[key] = _BINARY_EXTRACT
    client.get_overrides[key] = override

    with pytest.raises(DatasetSourceError, match=expected):
        fetch_dataset_extract(
            client=client,
            settings=_settings(),
            prefix="migration/source/EBCDIC/",
            source_object="AWS.M2.CARDDEMO.TRANTYPE.PS",
            destination=tmp_path / "extract.PS",
        )


def test_fetch_dataset_extract_refuses_bytes_that_are_not_whole_records(tmp_path: Path) -> None:
    """Refuse a fixed-block extract whose byte count is not a multiple of its record length.

    Purpose
    -------
    A short upload of a fixed-block extract is the defect this check exists for, and it has to be
    caught before the bytes are staged as a generation: a staged partial extract is then the
    generation a rerun reads.

    Parameters
    ----------
    tmp_path : Path
        Scratch directory the extract would be written into.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a partial record is admitted.
    """
    client = _FakeS3()
    key = "migration/source/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS"
    client.objects[key] = b"\x00" * 61

    with pytest.raises(DatasetSourceError):
        fetch_dataset_extract(
            client=client,
            settings=_settings(),
            prefix="migration/source/EBCDIC/",
            source_object="AWS.M2.CARDDEMO.TRANTYPE.PS",
            destination=tmp_path / "extract.PS",
            record_length=60,
        )


def test_fetch_dataset_extract_refuses_an_existing_destination(tmp_path: Path) -> None:
    """Refuse to overwrite a path, so no earlier attempt's bytes can be staged as this one's.

    Parameters
    ----------
    tmp_path : Path
        Scratch directory holding the pre-existing file.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the existing file is overwritten, or its bytes are altered.
    """
    client = _FakeS3()
    key = "migration/source/EBCDIC/AWS.M2.CARDDEMO.TRANTYPE.PS"
    client.objects[key] = _BINARY_EXTRACT
    destination = tmp_path / "extract.PS"
    destination.write_bytes(b"an earlier attempt")

    with pytest.raises(DatasetSourceError, match="already exists"):
        fetch_dataset_extract(
            client=client,
            settings=_settings(),
            prefix="migration/source/EBCDIC/",
            source_object="AWS.M2.CARDDEMO.TRANTYPE.PS",
            destination=destination,
        )
    assert destination.read_bytes() == b"an earlier attempt"


class _ProviderRefusal(Exception):
    """Stand in for a provider failure whose own text carries facts a log must not retain.

    Purpose
    -------
    Reproduce the two things the staging module reads from a refused request -- the service error
    code and the HTTP status -- inside an exception whose message deliberately carries an assumed
    role ARN, an account identifier and a resolved endpoint, so a test can prove none of them
    survives into the error the command logs.

    Attributes
    ----------
    response : dict
        The error document shape, carrying the code and status the module allow-lists.
    """

    #: Text that must never appear in a sanitized staging failure, held here so the assertions
    #: and the message they check cannot drift apart.
    LEAKED: tuple[str, ...] = (
        "arn:aws:sts::123456789012:assumed-role/carddemo-data-migration",
        "https://s3.eu-west-1.amazonaws.com",
        "X-Amz-Request-Id: 8EXAMPLE9",
    )

    def __init__(self, code: str = "AccessDenied", status: int = 403) -> None:
        """Build a refusal carrying one service error code, HTTP status and leaky text.

        Parameters
        ----------
        code : str
            Service error code to report.
        status : int
            HTTP status the service would have returned.

        Returns
        -------
        None

        Raises
        ------
        None
        """
        super().__init__(f"{code}: not authorised; " + "; ".join(self.LEAKED))
        self.response = {
            "Error": {"Code": code, "Message": "; ".join(self.LEAKED)},
            "ResponseMetadata": {"HTTPStatusCode": status},
        }


def test_sanitized_for_log_replaces_every_control_character() -> None:
    """Render external text so it cannot forge an extra line in a retained log."""
    rendered = sanitized_for_log("REC\nORDS\r\x1b[31m.DAT\x00")
    # WHY : Assumptions: the length is asserted alongside the content. A sanitiser that DELETED
    #   unsafe characters would also satisfy a content-only assertion while quietly changing what
    #   two different names render as -- so "A\nB" and "AB" would become indistinguishable in a
    #   log, which is the confusion the sanitiser exists to prevent.
    assert rendered == "REC?ORDS??[31m.DAT?"
    assert len(rendered) == len("REC\nORDS\r\x1b[31m.DAT\x00")
    assert "\n" not in rendered
    assert "\x1b" not in rendered


def test_stage_dataset_file_refuses_a_control_character_in_the_object_name(tmp_path: Path) -> None:
    """Refuse a leaf name carrying a newline, which would forge a line in the step's log."""
    source = tmp_path / "records.dat"
    source.write_bytes(b"CARDDEMO")
    client = _FakeS3()

    with pytest.raises(GenerationRetentionError):
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            source,
            staging_root=tmp_path,
            object_name="RECORDS\n2026-08-02 INFO staged 0 bytes.DAT",
        )
    assert client.puts == []


def test_stage_dataset_file_refuses_an_intermediate_symlinked_component(tmp_path: Path) -> None:
    """Refuse a source whose intermediate directory is a link, not only its final component."""
    outside = tmp_path / "outside"
    outside.mkdir()
    (outside / "records.dat").write_bytes(b"SECRET")
    root = tmp_path / "extracts"
    root.mkdir()
    (root / "inner").symlink_to(outside, target_is_directory=True)
    client = _FakeS3()

    # WHY : Refactoring Rationale: this is the test the previous shape could not pass. The source
    #   opened only its FINAL component with O_NOFOLLOW, so a link at any level above the extract
    #   was traversed silently and the read left the staging hierarchy entirely -- CWE-22. Naming
    #   the intermediate component in the assertion is what pins that the walk refuses at the link
    #   rather than at the leaf, which is the difference between the two implementations.
    with pytest.raises(DatasetSourceError, match="component inner"):
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            Path("inner/records.dat"),
            staging_root=root,
        )
    assert client.puts == []


def test_stage_dataset_file_refuses_a_parent_reference_in_the_source(tmp_path: Path) -> None:
    """Refuse a source carrying ``..``, which could name a file outside the staging root."""
    root = tmp_path / "extracts"
    root.mkdir()
    (tmp_path / "records.dat").write_bytes(b"SECRET")
    client = _FakeS3()

    with pytest.raises(DatasetSourceError, match="parent reference"):
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            Path("../records.dat"),
            staging_root=root,
        )
    assert client.puts == []


def test_stage_dataset_file_refuses_an_absolute_source_outside_the_root(tmp_path: Path) -> None:
    """Refuse an absolute path that does not lie beneath the staging root."""
    root = tmp_path / "extracts"
    root.mkdir()
    elsewhere = tmp_path / "records.dat"
    elsewhere.write_bytes(b"SECRET")
    client = _FakeS3()

    with pytest.raises(DatasetSourceError, match="not beneath the staging root"):
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            elsewhere,
            staging_root=root,
        )
    assert client.puts == []


def test_stage_dataset_file_accepts_an_absolute_source_beneath_the_root(tmp_path: Path) -> None:
    """Accept an absolute path that does lie beneath the root, reduced to its components."""
    root = tmp_path / "extracts"
    (root / "nested").mkdir(parents=True)
    source = root / "nested" / "records.dat"
    source.write_bytes(b"CARDDEMO")
    client = _FakeS3()

    # WHY : Assumptions: the accepting case is asserted beside the three refusals, because a
    #   beneath-the-root check that refused everything would satisfy all of them. This is what
    #   makes the walk a boundary rather than a blanket denial, and it also pins that a nested
    #   directory -- which the staged families use -- is still reachable.
    result = stage_dataset_file(
        client,
        _settings(),
        "ledger",
        "transact-bkup",
        date(2026, 8, 2),
        1,
        source,
        staging_root=root,
    )
    assert client.objects[result.key] == b"CARDDEMO"


@pytest.mark.parametrize("denied", PSEUDO_FILESYSTEM_ROOTS)
def test_stage_dataset_file_refuses_a_kernel_generated_staging_root(denied: str) -> None:
    """Refuse a staging root under a filesystem the kernel generates rather than stores."""
    client = _FakeS3()

    # WHY : Assumptions: the refusal is asserted for EVERY denied mount point rather than for
    #   /proc alone, because the exposure is not one file. /proc/self/environ publishes the
    #   container's whole environment -- every injected credential -- and /sys, /dev and /run each
    #   publish state of their own; a deny-list tested at one entry would let the others through.
    with pytest.raises(DatasetSourceError, match="the kernel"):
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            Path("environ"),
            staging_root=Path(denied) / "self",
        )
    assert client.puts == []


def test_stage_dataset_file_refuses_a_source_on_another_filesystem(tmp_path: Path) -> None:
    """Refuse an extract that resolves beneath the root but sits on a different volume."""
    filesystem_root = Path(tmp_path.anchor)
    if os.stat(filesystem_root).st_dev == os.stat(tmp_path).st_dev:
        # WHY : Trade-offs: the test SKIPS rather than arranging its own mount. Creating one needs
        #   privileges a test suite must not assume and leaves state behind if it fails midway;
        #   using a mount the environment already has keeps the check real -- an actual second
        #   device, not a patched stat -- at the cost of not running everywhere.
        pytest.skip("the temporary directory shares a filesystem with the root")
    source = tmp_path / "records.dat"
    source.write_bytes(b"CARDDEMO")
    client = _FakeS3()

    with pytest.raises(DatasetSourceError, match="different filesystem"):
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            source,
            staging_root=filesystem_root,
        )
    assert client.puts == []


def test_stage_dataset_file_accepts_a_zero_byte_extract_as_zero_records(tmp_path: Path) -> None:
    """Stage an empty fixed-record extract as zero records rather than refusing it."""
    source = tmp_path / "DALYTRAN.PS"
    source.write_bytes(b"")
    client = _FakeS3()

    # WHY : Refactoring Rationale: zero was REFUSED by the record-length check and is now accepted,
    #   because zero is an exact multiple and the check measures divisibility rather than presence.
    #   A genuinely empty period is real -- the daily transaction feed has one on any day with no
    #   activity, and the COBOL suite ships an `empty_input` scenario for it -- so the refusal
    #   turned a correct extract into a failed batch step. Absence remains a different fault and is
    #   still refused, which the sibling tests above cover.
    result = stage_dataset_file(
        client,
        _settings(),
        "ledger",
        "transact-bkup",
        date(2026, 8, 2),
        1,
        source,
        staging_root=tmp_path,
        record_length=350,
    )
    assert result.byte_size == 0
    assert client.objects[result.key] == b""
    assert result.sha256 == hashlib.sha256(b"").hexdigest()


def test_stage_dataset_file_is_idempotent_when_the_same_bytes_are_already_staged(
    tmp_path: Path,
) -> None:
    """Skip the transfer on a retry that would write byte-identical content, and still prune."""
    source = tmp_path / "records.dat"
    source.write_bytes(b"CARDDEMO")
    client = _FakeS3()
    arguments = (
        client,
        _settings(),
        "ledger",
        "transact-bkup",
        date(2026, 8, 2),
        1,
        source,
    )

    first = stage_dataset_file(*arguments, staging_root=tmp_path)
    second = stage_dataset_file(*arguments, staging_root=tmp_path)

    # WHY : Assumptions: the second attempt reports the SAME report the first did, and issues no
    #   second write. Repeating the write would also be correct and would cost one full transfer
    #   plus one noncurrent version carrying no new information -- one of the five the lifecycle
    #   rule retains -- on every retried Map branch.
    assert second == first
    dataset_writes = [put for put in client.puts if put.get("Key") == first.key]
    assert len(dataset_writes) == 1
    # WHY : Assumptions: retention still runs on the skipped path. A retry must leave the family
    #   in the state a first success would, and a skipped write that also skipped the scratch would
    #   leave a rolled-off generation behind for as long as the branch kept being retried.
    assert second.deleted_generation_prefixes == first.deleted_generation_prefixes


def test_stage_dataset_file_refuses_to_rewrite_a_generation_with_different_bytes(
    tmp_path: Path,
) -> None:
    """Refuse a second attempt whose content differs, because a generation is never rewritten."""
    source = tmp_path / "records.dat"
    source.write_bytes(b"CARDDEMO")
    client = _FakeS3()
    first = stage_dataset_file(
        client,
        _settings(),
        "ledger",
        "transact-bkup",
        date(2026, 8, 2),
        1,
        source,
        staging_root=tmp_path,
    )
    source.write_bytes(b"DIFFERENT")

    with pytest.raises(GenerationConflictError) as raised:
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            source,
            staging_root=tmp_path,
        )

    message = str(raised.value)
    # WHY : Assumptions: BOTH digests are named. A message naming only the incoming one leaves an
    #   operator unable to tell which earlier run wrote the generation, and the digest they read
    #   from that run's log is exactly the value that identifies it.
    assert first.sha256 in message
    assert hashlib.sha256(b"DIFFERENT").hexdigest() in message
    assert "never rewritten" in message
    # The stored bytes are the FIRST attempt's, so the refusal protected the evidence.
    assert client.objects[first.key] == b"CARDDEMO"
    assert len([put for put in client.puts if put.get("Key") == first.key]) == 1


def test_stage_dataset_file_reports_a_provider_refusal_without_the_sdk_text(tmp_path: Path) -> None:
    """Translate a refused write into allow-listed metadata, carrying none of the SDK's message."""
    source = tmp_path / "records.dat"
    source.write_bytes(b"CARDDEMO")
    client = _FakeS3()

    def _refuse(**kwargs: Any) -> dict[str, str]:
        """Refuse the staged write the way an unauthorised task role would."""
        raise _ProviderRefusal()

    client.put_object = _refuse  # type: ignore[method-assign]

    with pytest.raises(StagingServiceError) as raised:
        stage_dataset_file(
            client,
            _settings(),
            "ledger",
            "transact-bkup",
            date(2026, 8, 2),
            1,
            source,
            staging_root=tmp_path,
        )

    message = str(raised.value)
    # WHY : Refactoring Rationale: this is the assertion that would have caught the defect. Only
    #   OSError was caught around the write, so every provider failure escaped as the SDK's own
    #   exception -- and cli.py logs that text, which put the assumed-role ARN, the account
    #   identifier, the resolved endpoint and the request identifier into a retained container log.
    for leaked in _ProviderRefusal.LEAKED:
        assert leaked not in message
    assert "amazonaws" not in message
    assert "arn:aws" not in message
    # What it DOES carry is enough to act on: what was attempted, where, and the service's verdict.
    assert "the staged write" in message
    assert "bucket datasets" in message
    assert "ledger/transact-bkup/dt=2026-08-02/gen=0001/records.dat" in message
    assert "_ProviderRefusal" in message
    assert "code=AccessDenied" in message
    assert "http=403" in message
    # WHY : Assumptions: the provider exception is SUPPRESSED rather than chained. A chained cause
    #   is recoverable through __cause__, and anything logging exc_info would print the original
    #   text -- so sanitising the message while chaining the cause would leak exactly what the
    #   sanitising was for.
    assert raised.value.__cause__ is None
    assert raised.value.__suppress_context__ is True


def test_list_generation_prefixes_reports_a_failed_listing_as_a_sanitized_error() -> None:
    """Translate a refused listing into the same sanitized staging failure a write gets."""
    client = _FakeS3()

    def _refuse(operation_name: str) -> _FakePaginator:
        """Refuse to build a paginator the way a missing s3:ListBucket permission would."""
        raise _ProviderRefusal("AccessDenied", 403)

    client.get_paginator = _refuse  # type: ignore[method-assign]

    with pytest.raises(StagingServiceError) as raised:
        list_generation_prefixes(client, "datasets", "ledger/transact-bkup/")

    message = str(raised.value)
    for leaked in _ProviderRefusal.LEAKED:
        assert leaked not in message
    assert "code=AccessDenied" in message
    assert raised.value.__cause__ is None


def test_a_consumer_error_inside_a_listing_is_not_reported_as_a_provider_failure() -> None:
    """Let a domain error raised while consuming pages escape as itself, not as a service error."""
    prefix = "ledger/transact-bkup/dt=2026-08-01/gen=0001/"
    client = _FakeS3()
    # WHY : Assumptions: exactly the service's own batch limit of versions is arranged, because
    #   that is what forces the delete to be FLUSHED FROM INSIDE the pagination loop. Fewer
    #   versions flush after the loop has finished, where the generator is no longer live and the
    #   property under test would not be exercised at all.
    client.pages[("list_object_versions", prefix)] = [
        {
            "Versions": [
                {"Key": f"{prefix}records-{index:04d}.dat", "VersionId": f"v{index}"}
                for index in range(s3_stage._MAX_DELETE_OBJECTS)
            ]
        }
    ]
    client.delete_errors = [{"Code": "AccessDenied"}]

    with pytest.raises(GenerationRetentionError) as raised:
        delete_generation_prefix(client, "datasets", prefix)

    # WHY : Refactoring Rationale: this pins the placement of the `yield` OUTSIDE every `try` in
    #   the pagination helper. A consumer that raises inside its own `for` body closes the
    #   generator, which throws GeneratorExit at the yield -- so a yield sitting inside an
    #   `except Exception` would swallow the consumer's own domain error and re-report it as a
    #   provider failure, telling an operator the object store had refused a listing when what
    #   really failed was a delete. The two are acted on differently, which is why the distinction
    #   is asserted rather than assumed.
    assert not isinstance(raised.value, StagingServiceError)
    assert "AccessDenied" in str(raised.value)
