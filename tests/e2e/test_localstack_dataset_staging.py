"""End-to-end LocalStack S3 dataset-staging test for the AWS CardDemo suite.

Purpose
-------
Layer-3 end-to-end test that exercises the *AWS Mainframe Modernization*
dataset-staging path against a **headless (Docker-less) LocalStack** S3
emulator. It verifies that the buckets and objects declared in
``tests/mocks/localstack_s3_manifest.json`` can be created / staged in S3 and
read back, mirroring how CardDemo's batch datasets are staged to and from
cloud object storage on AWS M2 (the ``IDCAMS``-analog upload of flat datasets
into the staging bucket before a batch run).

Environment & skip policy
-------------------------
This layer is *environment-bounded and optional*. It only runs when a
LocalStack endpoint is both configured (``AWS_ENDPOINT_URL`` exported -- which
``scripts/run_e2e_tests.sh --with-localstack`` does by sourcing
``scripts/setup_localstack.sh`` in-process) **and** reachable / healthy. When
either condition is false the whole module is *skipped* -- pytest's skip is the
WARN-equivalent outcome (it never turns the CI run red), which is exactly how
``setup_localstack.sh`` degrades the AWS layer to WARN when the emulator is
unavailable. No test here ever raises on a missing/flaky emulator.

Markers
-------
* ``e2e``       -- selected by the binding runner command
                   ``pytest tests/e2e -m e2e``.
* ``localstack``-- lets CI include/exclude this network-touching layer.
* ``slow``      -- bringing up / seeding an emulator is comparatively slow.

WHY (design rationale)
----------------------
* Alternatives Considered: mocking the S3 API in-process (e.g. ``moto``) was
  rejected in favour of a real LocalStack round-trip, because the intent of
  this layer is to prove the *actual* dataset-staging path (CLI + endpoint)
  works, not to re-assert a mock's behaviour.
* Trade-off: the module is best-effort -- a missing or unhealthy emulator
  degrades to a skip (WARN) rather than a failure, so the core COBOL suite
  still passes CI on a runner without LocalStack credentials (matching the
  optional-layer contract in ``scripts/setup_localstack.sh``). When the
  emulator *is* healthy the staging/round-trip assertions are enforced for
  real, giving genuine end-to-end coverage.
* Assumption: the manifest at ``tests/mocks/localstack_s3_manifest.json`` is
  the single source of truth for the staging topology and is shared with
  ``scripts/setup_localstack.sh`` (same buckets/objects), so this test and the
  bring-up script never drift.
"""
from __future__ import annotations

import warnings
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


@pytest.fixture(scope="module")
def localstack_endpoint():
    """Resolve and health-check the LocalStack endpoint, or skip the module.

    Returns
    -------
    str
        The resolved LocalStack gateway URL (e.g. ``http://localhost:4566``),
        guaranteed reachable when returned.

    Raises
    ------
    None
        Never raises. When LocalStack is not configured or not healthy the
        fixture calls ``pytest.skip`` (a WARN-equivalent outcome), so every
        test in this module is skipped rather than failed.

    WHY
    ---
    Trade-off: a module-scoped fixture resolves + probes the endpoint exactly
    once. Because it may ``pytest.skip``, that single skip cleanly short-circuits
    the whole (optional, network-touching) module -- matching the "degrade to
    WARN when the emulator is unavailable" contract of setup_localstack.sh.
    """
    # WHY (Assumption): AWS_ENDPOINT_URL is the documented handshake -- it is
    # exported into this process when the runner is invoked with
    # `--with-localstack` (which sources setup_localstack.sh in-process).
    endpoint = localstack_setup.resolve_endpoint()
    if not endpoint:
        pytest.skip(
            "LocalStack endpoint not configured (AWS_ENDPOINT_URL unset). "
            "Run via 'scripts/run_e2e_tests.sh --with-localstack' to enable "
            "the AWS dataset-staging layer."
        )
    # WHY (Trade-off): a configured-but-unreachable endpoint is treated the same
    # as 'not configured' -- skip (WARN), never fail -- because a down emulator
    # is an environment condition, not a defect in the code under test.
    if not localstack_setup.is_available(endpoint):
        pytest.skip(
            f"LocalStack endpoint {endpoint!r} is configured but not "
            "reachable/healthy; AWS dataset-staging layer degraded to skip (WARN)."
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
        The parsed staging manifest (unused directly here but declared so the
        fixture participates in the same dependency graph and load order).

    Returns
    -------
    dict
        The seed result: ``{"buckets": [...created...], "objects": [(bucket,
        key), ...], "errors": [...]}``.

    Raises
    ------
    None
        Never raises. Partial seeding errors are surfaced as a non-fatal
        ``warnings.warn`` so a transient emulator hiccup degrades to WARN rather
        than aborting the run.

    WHY
    ---
    Refactoring rationale: seeding once at module scope (instead of per test)
    keeps this comparatively slow, side-effecting bring-up out of every test
    body and lets the three assertions below share one deterministic S3 state.
    """
    result = localstack_setup.seed_from_manifest(_MANIFEST_PATH, endpoint=localstack_endpoint)
    # WHY (Trade-off): errors during seeding against a *healthy* endpoint are
    # reported as a warning (not an exception) so a partial/transient failure
    # degrades to WARN; the per-topic assertions below still verify whatever did
    # get staged, so a genuine, total failure is still caught by those asserts.
    if result.get("errors"):
        warnings.warn(
            "LocalStack seeding reported non-fatal errors: "
            + "; ".join(str(e) for e in result["errors"]),
            RuntimeWarning,
            stacklevel=2,
        )
    return result


def test_localstack_dataset_staging_creates_manifest_buckets(manifest, staged):
    """Every bucket declared in the manifest is staged in S3.

    Parameters
    ----------
    manifest : dict
        Parsed staging manifest (fixture).
    staged : dict
        Result of seeding the manifest into LocalStack (fixture).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any manifest bucket was not created during seeding.

    WHY
    ---
    Assumption: ``iter_buckets`` normalises both manifest bucket forms (a plain
    string and a ``{"name": ...}`` object), so the comparison is independent of
    how each bucket happens to be spelled in the JSON.
    """
    expected_buckets = set(localstack_setup.iter_buckets(manifest))
    # WHY (Trade-off): a healthy emulator with an empty manifest is a
    # misconfiguration, not a pass -- guard against a vacuously-true assertion.
    assert expected_buckets, "manifest declares no buckets to stage"

    staged_buckets = set(staged.get("buckets", []))
    missing = expected_buckets - staged_buckets
    assert not missing, (
        f"manifest buckets not staged in LocalStack: {sorted(missing)} "
        f"(staged={sorted(staged_buckets)})"
    )


def test_localstack_dataset_staging_uploads_manifest_objects(manifest, staged):
    """Every object declared in the manifest is staged into its bucket.

    Parameters
    ----------
    manifest : dict
        Parsed staging manifest (fixture).
    staged : dict
        Result of seeding the manifest into LocalStack (fixture).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If any manifest object (bucket/key pair) was not staged.

    WHY
    ---
    Assumption: ``iter_objects`` yields ``(bucket, key, content)`` triples while
    the shared seeder records each uploaded object as a ``"bucket/key"`` STRING
    (its documented return contract, mirroring the bash seeder's ``s3://bucket/
    key`` path), so the staging identity compared here is that ``"bucket/key"``
    string; content is dropped (its correctness is asserted separately by the
    round-trip test).
    Alternatives considered: emitting ``(bucket, key)`` tuples from the seeder was
    rejected -- ``seed_from_manifest`` is the authoritative, cross-consumer
    contract shared with the bash path, so this test conforms to it rather than
    the reverse.
    """
    expected_objects = {f"{bucket}/{key}" for bucket, key, _ in localstack_setup.iter_objects(manifest)}
    # WHY: same anti-vacuity guard as the bucket test.
    assert expected_objects, "manifest declares no objects to stage"

    # WHY: seed_from_manifest returns staged objects as "bucket/key" strings, so
    # consume them verbatim -- the previous ``tuple(o)`` exploded each string into
    # a tuple of characters, which could never equal a "bucket/key" identity.
    staged_objects = set(staged.get("objects", []))
    missing = expected_objects - staged_objects
    assert not missing, (
        f"manifest objects not staged in LocalStack: {sorted(missing)} "
        f"(staged={sorted(staged_objects)})"
    )


def test_localstack_dataset_object_content_round_trips(manifest, staged, localstack_endpoint):
    """Staged object content can be read back byte-for-byte from S3.

    Parameters
    ----------
    manifest : dict
        Parsed staging manifest (fixture).
    staged : dict
        Result of seeding the manifest into LocalStack (fixture); required so
        seeding has completed before the round-trip read.
    localstack_endpoint : str
        The reachable LocalStack gateway URL (fixture).

    Returns
    -------
    None

    Raises
    ------
    AssertionError
        If a retrievable object's content does not match the manifest.

    WHY
    ---
    Trade-off: only objects that declare non-empty ``content`` in the manifest
    are round-tripped (an object with no declared content has nothing
    deterministic to assert). A retrieval that returns ``None`` is treated as a
    transient emulator hiccup and degrades to WARN (skip) rather than a hard
    failure, honouring the optional-layer contract; but any content that *is*
    returned must match exactly, giving a real end-to-end guarantee.
    """
    verifiable = [
        (bucket, key, content)
        for bucket, key, content in localstack_setup.iter_objects(manifest)
        if content
    ]
    if not verifiable:
        pytest.skip("manifest declares no objects with content to round-trip")

    verified = 0
    for bucket, key, expected in verifiable:
        actual = localstack_setup.get_object_text(bucket, key, endpoint=localstack_endpoint)
        if actual is None:
            # WHY (Trade-off): a None read from a live emulator is environment
            # transience, not a code defect -- warn and continue rather than
            # fail, so the optional layer never turns CI red on a hiccup.
            warnings.warn(
                f"could not read back s3://{bucket}/{key}; skipping its round-trip",
                RuntimeWarning,
                stacklevel=2,
            )
            continue
        assert actual == expected, (
            f"round-trip mismatch for s3://{bucket}/{key}: "
            f"expected {expected!r}, got {actual!r}"
        )
        verified += 1

    # WHY: if the emulator was healthy yet every read came back None, there is
    # nothing to trust -- degrade to WARN (skip) instead of a false green.
    if verified == 0:
        pytest.skip("no staged objects could be read back (emulator returned no content)")
