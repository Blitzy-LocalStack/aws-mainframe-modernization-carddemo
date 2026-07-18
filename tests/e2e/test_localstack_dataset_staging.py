"""End-to-end LocalStack S3 dataset-staging test for the AWS CardDemo suite.

Purpose
-------
Layer-3 end-to-end test that exercises the *AWS Mainframe Modernization*
dataset-staging path against a **headless (Docker-less) LocalStack** S3
emulator. It verifies that the buckets and objects declared in
``tests/mocks/localstack_s3_manifest.json`` are actually created / staged in S3
and can be read back **byte-for-byte**, mirroring how CardDemo's batch datasets
are staged to and from cloud object storage on AWS M2 (the ``IDCAMS``-analog
upload of flat datasets into the staging bucket before a batch run).

Authoritative verification (QA finding F7)
------------------------------------------
Every assertion here queries **S3 itself** -- ``bucket_exists`` / ``head_object``
/ ``get_object_bytes`` on the live endpoint -- and NEVER trusts the summary dict
the seeder returns. That is the crux of finding F7: a previous version asserted
against ``seed_from_manifest``'s own return value, so a fabricated summary could
"pass" with zero real S3 traffic. The seed step still runs (via the ``staged``
fixture, so the objects are present), but its return value is deliberately not
consulted by any assertion. Each object is verified three ways -- server-side
``ContentLength`` vs. its declared ``size``, exact byte equality against the
source fixture (read with a **binary-safe** getter so BINARY EBCDIC survives),
and a SHA-256 digest match against the manifest's declared checksum.

Because the manifest stages a real ASCII dataset (``app/data/ASCII/discgrp.txt``)
and a real BINARY EBCDIC dataset (``app/data/EBCDIC/AWS.M2.CARDDEMO.ACCTDATA.PS``)
via ``source_file``, a DECLARED object that cannot be HEAD-ed or whose bytes do
not round-trip is a HARD failure -- there is no "warn and continue" escape hatch
for a declared object (also F7).

Environment & skip / require policy (QA finding F4)
---------------------------------------------------
This layer is *environment-bounded and optional by default*. It runs only when a
LocalStack endpoint is both configured (``AWS_ENDPOINT_URL`` exported -- which
``scripts/run_e2e_tests.sh --with-localstack`` does by sourcing
``scripts/setup_localstack.sh`` in-process) **and** reachable / healthy.

* Default (developer) mode: an absent/unreachable emulator ``skip``s the module
  -- a WARN-equivalent outcome that never turns CI red, matching how
  ``setup_localstack.sh`` degrades the AWS layer to WARN.
* Required mode: when ``CARDDEMO_REQUIRE_LOCALSTACK`` is truthy the SAME absence
  becomes a HARD FAILURE instead of a skip, so a ``--with-localstack`` CI job can
  never exit green with an all-skipped report that hides a missing required
  layer. The skip-vs-fail decision is delegated to the shared ``localstack_gate``
  fixture in ``tests/conftest.py`` (the pytest peer of the identically-named bash
  flag), keeping one uniform policy across the bash and Python layers.

Markers
-------
* ``e2e``       -- selected by the binding runner command
                   ``pytest tests/e2e -m e2e``.
* ``localstack``-- lets CI include/exclude this network-touching layer.
* ``slow``      -- bringing up / seeding an emulator is comparatively slow.

WHY (design rationale)
----------------------
* Alternatives Considered: mocking the S3 API in-process (e.g. ``moto``) was
  rejected in favour of a real LocalStack round-trip, because the intent of this
  layer is to prove the *actual* dataset-staging path (CLI + endpoint) works, not
  to re-assert a mock's behaviour.
* Trade-off: the module is optional-by-default (skip/WARN when the emulator is
  absent) so the core COBOL suite still passes CI on a runner without LocalStack;
  but when the emulator IS present, staging is verified for real and
  authoritatively (F7), and CI can escalate absence to failure (F4).
* Assumption: the manifest at ``tests/mocks/localstack_s3_manifest.json`` is the
  single source of truth for the staging topology and is shared with
  ``scripts/setup_localstack.sh`` (same buckets/objects/checksums), so this test
  and the bring-up script never drift. ``source_file`` paths in the manifest are
  repo-relative and resolved against ``CARDDEMO_REPO_ROOT`` (or this file's
  repo-root ancestor), exactly as both seeders resolve them.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path

import pytest

# WHY (Assumption): helpers are single-sourced under tests/helpers/ and the repo
# root is on PYTHONPATH (exported by scripts/run_e2e_tests.sh), so the package
# import resolves regardless of pytest's import mode. The helper is deliberately
# best-effort and NEVER raises, which is what lets this module degrade to a skip
# instead of an error when the emulator is absent.
from tests.helpers import localstack_setup

# Binding runner contract: `pytest tests/e2e -m e2e` selects this module, so the
# e2e marker is mandatory. localstack + slow additionally let CI toggle this
# environment-bounded, comparatively slow layer on demand.
pytestmark = [pytest.mark.e2e, pytest.mark.localstack, pytest.mark.slow]

# Single source of truth for the staging topology. WHY: the same manifest drives
# scripts/setup_localstack.sh, so pointing the test at it guarantees the seeded
# resources and the asserted resources cannot diverge.
_MANIFEST_PATH = Path(__file__).resolve().parents[1] / "mocks" / "localstack_s3_manifest.json"


def _repo_root() -> Path:
    """Resolve the repository root used to interpret manifest ``source_file`` paths.

    Purpose:
        Give the byte-verification assertions the same base directory the two
        seeders use, so a ``source_file`` such as ``app/data/ASCII/discgrp.txt``
        resolves to the identical on-disk file the seeder uploaded.

    Parameters:
        None.

    Returns:
        pathlib.Path: ``CARDDEMO_REPO_ROOT`` when that environment variable is set
        (the value ``scripts/test_env.sh`` exports), otherwise this file's
        repo-root ancestor (``tests/e2e/`` -> ``parents[2]``).

    Raises:
        None.
    """
    # WHY (Assumption): CARDDEMO_REPO_ROOT is exported by test_env.sh and consumed
    # identically by the bash seeder and localstack_setup.seed_from_manifest, so
    # honouring it FIRST keeps all three resolvers in lockstep. The ancestor
    # fallback keeps a direct ``pytest`` invocation (no env sourced) working.
    env_root = os.environ.get("CARDDEMO_REPO_ROOT")
    return Path(env_root) if env_root else Path(__file__).resolve().parents[2]


def _expected_object_bytes(obj: dict, repo_root: Path) -> bytes:
    """Compute the exact bytes an object is expected to hold in S3.

    Purpose:
        Derive the ground-truth payload for a manifest object from the SAME two
        sources the seeders use -- a ``source_file`` on disk (uploaded verbatim,
        binary included) or an inline ``content`` string (UTF-8 encoded) -- so
        the round-trip assertion compares against reality, not a restatement.

    Parameters:
        obj (dict): A single manifest object entry (keys ``bucket``/``key`` plus
            exactly one of ``source_file`` / ``content``, and optional
            ``sha256``/``size``).
        repo_root (pathlib.Path): Base directory against which a repo-relative
            ``source_file`` is resolved.

    Returns:
        bytes: The expected object body. For a ``source_file`` object this is the
        file's raw bytes; for an inline object it is ``content.encode("utf-8")``;
        an object declaring neither yields ``b""``.

    Raises:
        FileNotFoundError: If a declared ``source_file`` does not exist under
            ``repo_root`` -- a HARD error on purpose, because a manifest that
            references a missing fixture is a real defect, not a skip condition.
    """
    # WHY (Trade-off): a source_file is read as raw BYTES (never decoded) so the
    # 15 000-byte binary EBCDIC dataset -- NUL/overpunch bytes, no trailing
    # newline -- is compared exactly as stored. Inline content mirrors the
    # seeders' `printf '%s'` / UTF-8 temp-file write (no trailing newline added),
    # so content.encode("utf-8") reproduces precisely what was uploaded.
    source_file = obj.get("source_file")
    if source_file:
        return (repo_root / source_file).read_bytes()
    content = obj.get("content") or ""
    return content.encode("utf-8")


@pytest.fixture(scope="module")
def localstack_endpoint(localstack_gate):
    """Resolve and health-check the LocalStack endpoint, or gate the module.

    Parameters
    ----------
    localstack_gate : Callable[[str], NoReturn]
        The shared require-or-skip gate from ``tests/conftest.py``. When the
        endpoint precondition is unmet this fixture calls it with a specific
        reason; the gate HARD-FAILS under ``CARDDEMO_REQUIRE_LOCALSTACK`` and
        cleanly skips otherwise (QA finding F4).

    Returns
    -------
    str
        The resolved LocalStack gateway URL (e.g. ``http://localhost:4566``),
        guaranteed reachable when returned.

    Raises
    ------
    Failed
        (via the gate) when ``CARDDEMO_REQUIRE_LOCALSTACK`` is set and the
        endpoint is unset or unreachable.
    Skipped
        (via the gate) when the flag is unset and the endpoint is unavailable.

    WHY
    ---
    Refactoring rationale: the endpoint is resolved + probed exactly once at
    module scope. Delegating the unavailable-case decision to ``localstack_gate``
    (instead of a bare ``pytest.skip``) is what upgrades this layer from
    "always-skippable" to "required when CI asks for it" without duplicating the
    fail-vs-skip policy here.
    """
    # WHY (Assumption): AWS_ENDPOINT_URL is the documented handshake -- it is
    # exported into this process when the runner is invoked with
    # `--with-localstack` (which sources setup_localstack.sh in-process).
    endpoint = localstack_setup.resolve_endpoint()
    if not endpoint:
        localstack_gate(
            "LocalStack endpoint not configured (AWS_ENDPOINT_URL unset); "
            "run via 'scripts/run_e2e_tests.sh --with-localstack' to enable the "
            "AWS dataset-staging layer"
        )
    # WHY (Trade-off): a configured-but-unreachable endpoint is treated the same
    # as 'not configured' -- both routed through the SAME gate -- because a down
    # emulator is an environment condition. Under CARDDEMO_REQUIRE_LOCALSTACK the
    # gate still escalates it to a failure, so a CI job that demanded the layer is
    # told the truth instead of seeing a green skip.
    if not localstack_setup.is_available(endpoint):
        localstack_gate(
            f"LocalStack endpoint {endpoint!r} is configured but not "
            "reachable/healthy; AWS dataset-staging layer unavailable"
        )
    return endpoint


@pytest.fixture(scope="module")
def manifest():
    """Load the S3 dataset-staging manifest.

    Returns
    -------
    dict
        The parsed manifest with ``buckets`` and ``objects`` keys. On any read
        error the helper returns an empty ``{"buckets": [], "objects": []}``
        shape rather than raising.

    Raises
    ------
    None
        Never raises (delegates to the best-effort ``load_manifest`` helper).
    """
    return localstack_setup.load_manifest(_MANIFEST_PATH)


@pytest.fixture(scope="module")
def staged(localstack_endpoint, manifest):
    """Seed the manifest into LocalStack S3 exactly once for the module.

    Parameters
    ----------
    localstack_endpoint : str
        The reachable LocalStack gateway URL (from the ``localstack_endpoint``
        fixture); its presence guarantees the emulator is healthy.
    manifest : dict
        The parsed staging manifest (declared so the fixture participates in the
        same dependency graph and load order).

    Returns
    -------
    dict
        The seed result ``{"buckets": [...], "objects": [...], "errors": [...]}``.
        **Deliberately not consulted by any assertion** -- it is returned only so
        a test could log it. The staging *facts* are read back from S3 instead
        (QA finding F7).

    Raises
    ------
    AssertionError
        If seeding against a HEALTHY endpoint reports any error. Because the
        endpoint is known-healthy by the time this runs, a seed error is a real
        defect, not a transient environment condition, so it fails hard rather
        than degrading to WARN (QA finding F7).

    WHY
    ---
    Refactoring rationale: seeding once at module scope keeps this comparatively
    slow, side-effecting bring-up out of every test body and lets the assertions
    below share one deterministic S3 state -- which they then verify against S3
    directly, never against this dict.
    """
    result = localstack_setup.seed_from_manifest(_MANIFEST_PATH, endpoint=localstack_endpoint)
    # WHY (finding F7 -- no warn-skip): the endpoint is already proven healthy, so
    # a seeding error is a genuine failure to stage a declared dataset. Surfacing
    # it as a hard assertion (rather than the previous warnings.warn) prevents a
    # partial/failed seed from masquerading as a passing optional layer.
    assert not result.get("errors"), (
        "LocalStack seeding reported errors against a healthy endpoint: "
        + "; ".join(str(e) for e in result["errors"])
    )
    return result


def test_localstack_dataset_staging_creates_manifest_buckets(
    manifest, staged, localstack_endpoint
):
    """Every bucket declared in the manifest actually exists in S3.

    Parameters
    ----------
    manifest : dict
        Parsed staging manifest (fixture).
    staged : dict
        Seeding side-effect fixture (ensures buckets were created); its contents
        are intentionally not inspected here.
    localstack_endpoint : str
        The reachable LocalStack gateway URL (fixture) the existence checks target.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the manifest declares no buckets, or if any declared bucket is absent
        from S3 (verified via ``head-bucket``), or if the authoritative
        ``list-buckets`` view is missing one.

    WHY
    ---
    Finding F7: existence is proven with ``bucket_exists`` (an S3 ``head-bucket``)
    and cross-checked against ``list_buckets`` (S3 ``list-buckets``) -- both read
    the emulator directly, so a fabricated seed summary cannot make this pass.
    Assumption: ``iter_buckets`` normalises both manifest bucket forms (a plain
    string and a ``{"name": ...}`` object), so the comparison is independent of
    how each bucket is spelled in the JSON.
    """
    expected_buckets = set(localstack_setup.iter_buckets(manifest))
    # WHY (Trade-off): a healthy emulator with an empty manifest is a
    # misconfiguration, not a pass -- guard against a vacuously-true assertion.
    assert expected_buckets, "manifest declares no buckets to stage"

    # Authoritative per-bucket existence check (S3 head-bucket).
    for bucket in sorted(expected_buckets):
        assert localstack_setup.bucket_exists(bucket, endpoint=localstack_endpoint), (
            f"declared bucket {bucket!r} does not exist in S3 (head-bucket failed)"
        )

    # Cross-check against S3's own bucket listing so a partially-broken head-bucket
    # cannot hide a gap. WHY: list-buckets is a second, independent S3 read.
    listed = set(localstack_setup.list_buckets(endpoint=localstack_endpoint))
    missing = expected_buckets - listed
    assert not missing, (
        f"manifest buckets absent from S3 list-buckets: {sorted(missing)} "
        f"(listed={sorted(listed)})"
    )


def test_localstack_dataset_staging_uploads_manifest_objects(
    manifest, staged, localstack_endpoint
):
    """Every object declared in the manifest actually exists in its S3 bucket.

    Parameters
    ----------
    manifest : dict
        Parsed staging manifest (fixture).
    staged : dict
        Seeding side-effect fixture; its contents are intentionally not inspected.
    localstack_endpoint : str
        The reachable LocalStack gateway URL (fixture) the HEAD checks target.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If the manifest declares no objects, or if any declared object cannot be
        HEAD-ed in S3 (i.e. is missing).

    WHY
    ---
    Finding F7: presence is proven with ``head_object`` against the live endpoint
    for EVERY declared object -- including the ``source_file`` (real ASCII and
    BINARY EBCDIC) objects the old ``if content`` filter silently skipped. A
    missing declared object is a HARD failure; there is no warn-and-continue.
    """
    objects = manifest.get("objects") or []
    # WHY: same anti-vacuity guard as the bucket test.
    assert objects, "manifest declares no objects to stage"

    for obj in objects:
        bucket = obj.get("bucket")
        key = obj.get("key")
        assert bucket and key, f"manifest object missing bucket/key: {obj!r}"
        meta = localstack_setup.head_object(bucket, key, endpoint=localstack_endpoint)
        assert meta is not None, (
            f"declared object s3://{bucket}/{key} not found in S3 (head-object failed)"
        )


def test_localstack_dataset_object_content_round_trips(
    manifest, staged, localstack_endpoint
):
    """Every staged object round-trips byte-for-byte (size + bytes + SHA-256).

    Parameters
    ----------
    manifest : dict
        Parsed staging manifest (fixture).
    staged : dict
        Seeding side-effect fixture (ensures objects were uploaded); its contents
        are intentionally not inspected.
    localstack_endpoint : str
        The reachable LocalStack gateway URL (fixture) the reads target.

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any declared object's server-side ``ContentLength`` disagrees with its
        expected/declared size, if its bytes read back from S3 differ from the
        source fixture, or if its SHA-256 differs from the manifest's declared
        checksum.
    FileNotFoundError
        (via :func:`_expected_object_bytes`) if a declared ``source_file`` fixture
        is missing -- a real defect, surfaced rather than skipped.

    WHY
    ---
    Finding F7 -- this is the authoritative heart of the fix. For EVERY declared
    object (no ``if content`` filter, so BINARY EBCDIC and the real ASCII dataset
    are included) it verifies three independent facts against S3: (1) HEAD
    ``ContentLength`` equals the expected byte count (and the declared ``size``);
    (2) the raw bytes fetched with the BINARY-SAFE getter equal the source
    fixture exactly; (3) the SHA-256 of those bytes equals the manifest's declared
    digest. A ``None`` read is a HARD failure -- the old "warn and continue on
    None / skip when nothing verified" escape hatch is gone, because a declared
    object that will not read back is precisely the staging defect this layer
    exists to catch.
    Trade-off: byte + digest comparison is stricter than a text ``==`` and works
    for binary payloads a text decode would corrupt, at the cost of reading each
    object body once -- acceptable for a handful of deterministic fixtures.
    """
    objects = manifest.get("objects") or []
    assert objects, "manifest declares no objects to round-trip"

    repo_root = _repo_root()
    for obj in objects:
        bucket = obj.get("bucket")
        key = obj.get("key")
        assert bucket and key, f"manifest object missing bucket/key: {obj!r}"

        expected = _expected_object_bytes(obj, repo_root)

        # (1) Server-side size via HEAD -- must match the expected byte count and,
        # when the manifest declares one, the declared `size` too.
        meta = localstack_setup.head_object(bucket, key, endpoint=localstack_endpoint)
        assert meta is not None, (
            f"declared object s3://{bucket}/{key} not found in S3 (head-object failed)"
        )
        content_length = meta.get("ContentLength")
        assert content_length == len(expected), (
            f"ContentLength mismatch for s3://{bucket}/{key}: "
            f"S3 reports {content_length}, expected {len(expected)}"
        )
        declared_size = obj.get("size")
        if declared_size is not None:
            assert declared_size == len(expected), (
                f"manifest 'size' for s3://{bucket}/{key} ({declared_size}) "
                f"disagrees with the source fixture ({len(expected)})"
            )

        # (2) Exact bytes via the BINARY-SAFE getter -- proves binary EBCDIC
        # survives the round-trip (a text decode would corrupt it).
        actual = localstack_setup.get_object_bytes(bucket, key, endpoint=localstack_endpoint)
        assert actual is not None, (
            f"could not read back s3://{bucket}/{key} (get-object returned None)"
        )
        assert actual == expected, (
            f"byte round-trip mismatch for s3://{bucket}/{key}: "
            f"read {len(actual)} bytes, expected {len(expected)} bytes"
        )

        # (3) SHA-256 digest vs. the manifest's declared checksum (when present),
        # the audit anchor a financial dataset-staging workflow needs.
        declared_sha = obj.get("sha256")
        if declared_sha:
            actual_sha = hashlib.sha256(actual).hexdigest()
            assert actual_sha == declared_sha, (
                f"SHA-256 mismatch for s3://{bucket}/{key}: "
                f"got {actual_sha}, manifest declares {declared_sha}"
            )
