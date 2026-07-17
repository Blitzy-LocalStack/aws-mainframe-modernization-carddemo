"""Headless LocalStack lifecycle + S3 seeding helper for the CardDemo test suite.

Purpose
-------
This module is the **Python peer** of ``scripts/setup_localstack.sh``. Where the
bash script *brings LocalStack up* (agent token -> ``localstack ephemeral
create`` -> export the gateway URL as ``AWS_ENDPOINT_URL`` -> seed S3), this
module lets the pytest end-to-end layer (``tests/e2e/test_localstack_dataset_staging.py``)
*verify, read, and seed* against that same endpoint, using the **same manifest
schema** so a bash-seeded and a Python-seeded bucket are byte-identical.

It mirrors, one-for-one, the three externally-observable contracts the bash
script publishes:

1. **Endpoint discovery.** The script exports ``AWS_ENDPOINT_URL`` *and* writes a
   single line ``AWS_ENDPOINT_URL=<url>`` to ``<workspace>/localstack.env``. This
   module's :func:`resolve_endpoint` reads exactly those two sources, in that
   order.
2. **Health probe.** The script polls ``"$AWS_ENDPOINT_URL/_localstack/health"``
   in a bounded loop; :func:`is_available` / :func:`wait_until_ready` issue the
   identical HTTP GET and poll the identical path.
3. **Manifest seeding.** The script reads ``tests/mocks/localstack_s3_manifest.json``
   with two ``jq`` filters -- ``(.buckets // [])[] | if type=="object" then .name
   else . end`` and ``(.objects // [])[] | [.bucket, .key, (.content // "")] |
   @tsv``. :func:`iter_buckets` / :func:`iter_objects` reproduce those exact
   normalizations in Python so the two seeders never diverge.

Design decisions (WHY)
----------------------
* **``subprocess`` to ``awslocal``, never ``boto3``.** *(Alternatives Considered)*
  A ``boto3`` client would be the obvious idiomatic choice, but ``boto3`` is
  **not** in the suite's pinned dependencies (``tests/requirements-test.txt`` /
  AAP Section 0.6.1 lists only ``awscli`` + ``awscli-local`` and the ``localstack``
  CLI). Importing it would add an unlisted, unavailable dependency and break a
  bare runner, so every AWS call is issued through the already-present
  ``awslocal`` CLI, and health is probed with the stdlib :mod:`urllib` -- no
  third-party import anywhere.
* **Best-effort; nothing here ever raises to fail a test run.** *(Trade-off)* The
  AWS layer is *optional* (AAP Section 0.8.2): a runner with no LocalStack
  credentials must still pass the core COBOL suite. Every function therefore
  returns a status (``bool`` / ``None`` / a summary ``dict``) and logs a
  diagnostic on failure instead of propagating an exception. The E2E test -- not
  this module -- decides to ``pytest.skip(...)`` when the endpoint is
  unreachable. This matches the bash script's "reachability gap -> WARN" policy.
* **Bounded-poll readiness, not a fixed sleep.** *(Refactoring rationale)* The
  legacy demo scripts (``scripts/run_full_batch.sh`` / ``run_posting.sh``, this
  module's ``source_files``) paced jobs with fixed ``sleep`` calls.
  :func:`wait_until_ready` instead polls the health endpoint and returns the
  instant it is healthy, capped by a deadline computed from :func:`time.monotonic`
  -- race-free and never longer than necessary.
* **Standard library only.** *(Determinism / isolation)* Only :mod:`os`,
  :mod:`sys`, :mod:`json`, :mod:`shutil`, :mod:`subprocess`, :mod:`time`,
  :mod:`urllib.request` / :mod:`urllib.error`, and :class:`pathlib.Path` are
  imported, so the module loads on any runner without installing anything.

Packaging
---------
There is **no** ``__init__.py`` anywhere under ``tests/``; the package resolves
as a PEP 420 namespace package via ``PYTHONPATH=<repo_root>`` and is imported as
``from tests.helpers.localstack_setup import ...``.

Explainability
--------------
Per the project's mandatory Explainability rule, every symbol below carries a
docstring stating Purpose / Parameters / Returns / Raises, and each non-obvious
decision is annotated with a WHY comment documenting at least one of Alternatives
Considered, Refactoring Rationale, Assumptions, or Trade-offs.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

# ---------------------------------------------------------------------------
# Module constants -- the externally-observable contract shared with the bash
# script. They are named and centralised here (rather than inlined at each call
# site) so a future change to, say, the health path is made in ONE place and the
# Python/bash pair cannot drift.
# ---------------------------------------------------------------------------

#: LocalStack readiness endpoint path appended to the gateway URL. Mirrors the
#: exact path polled by scripts/setup_localstack.sh ("$AWS_ENDPOINT_URL/_localstack/health").
_HEALTH_PATH = "/_localstack/health"

#: Environment variable the bash script exports and this module reads first when
#: resolving the endpoint. GnuCOBOL/AWS tooling both key off this name.
_ENV_VAR = "AWS_ENDPOINT_URL"

#: Name of the file the bash script writes into the test workspace, containing a
#: single "AWS_ENDPOINT_URL=<url>" line. This module parses that same file as the
#: second-choice endpoint source.
_ENVFILE_NAME = "localstack.env"

#: Repo-relative location of the shared S3 seeding manifest. Kept identical to the
#: path scripts/setup_localstack.sh loads so both seeders consume one source file.
_DEFAULT_MANIFEST_RELPATH = "tests/mocks/localstack_s3_manifest.json"

#: Diagnostic prefix, matching the bash script's "[localstack] ..." log style so
#: interleaved bash/Python output reads as one coherent stream.
_LOG_PREFIX = "[localstack]"


def _diag(message: str) -> None:
    """Write a single best-effort diagnostic line to standard error.

    Purpose:
        Emit a human-readable diagnostic without ever interrupting control flow,
        so the caller's best-effort contract is preserved even while explaining
        why an optional operation was skipped or failed.

    Parameters:
        message (str): The diagnostic text to print (the ``[localstack]`` prefix
            is added automatically).

    Returns:
        None

    Raises:
        None. Any failure to write to stderr is deliberately swallowed -- a
        logging helper must never become the reason a test aborts.
    """
    # WHY (Trade-off): stderr (not stdout) keeps diagnostics off the data stream a
    # test might be capturing, and mirrors the bash script's `echo ... >&2`. The
    # blanket except is intentional: emitting a diagnostic can never be allowed to
    # raise inside a "never hard-fail" helper.
    try:
        print(f"{_LOG_PREFIX} {message}", file=sys.stderr)
    except Exception:  # noqa: BLE001 - see WHY above; logging must not raise.
        pass


def _repo_root() -> Path:
    """Return the repository root inferred from this file's location.

    Purpose:
        Provide a dependable fallback base for locating the shared manifest and
        the bash bring-up script when the ``CARDDEMO_REPO_ROOT`` environment
        variable is not set (e.g. a developer running a single test by hand).

    Parameters:
        None

    Returns:
        pathlib.Path: The absolute path ``<repo_root>``, computed as the parent
        two levels above this file (``tests/helpers/localstack_setup.py`` ->
        ``tests/helpers`` -> ``tests`` -> ``<repo_root>``).

    Raises:
        None.
    """
    # WHY (Assumption): the suite's directory layout is fixed -- this helper always
    # lives at "<repo_root>/tests/helpers/localstack_setup.py" -- so parents[2] is
    # a stable, dependency-free way to find the root that does not rely on the
    # current working directory (which pytest-xdist workers may change).
    return Path(__file__).resolve().parents[2]


def _read_endpoint_from_envfile(envfile: Path) -> str | None:
    """Parse an ``AWS_ENDPOINT_URL=<url>`` line out of a ``localstack.env`` file.

    Purpose:
        Recover the endpoint the bash bring-up script persisted to
        ``<workspace>/localstack.env`` so a separately-spawned Python process
        (which does not inherit the script's exported environment) can discover
        it.

    Parameters:
        envfile (pathlib.Path): Absolute path to the candidate ``localstack.env``
            file. It may not exist; that is treated as "no endpoint here".

    Returns:
        str | None: The URL value if a well-formed ``AWS_ENDPOINT_URL=`` line is
        present and non-empty, otherwise ``None``.

    Raises:
        None. All file/parse errors are caught and reported as ``None`` to honour
        the best-effort contract.
    """
    # WHY (Trade-off): the bash script writes the value UNQUOTED
    # (`printf 'AWS_ENDPOINT_URL=%s\n'`), but a hand-edited file might wrap it in
    # quotes, so we strip a single pair of surrounding quotes defensively. We scan
    # line-by-line (rather than sourcing the file) because this is data, not code
    # we should execute -- reading it must never run arbitrary shell.
    try:
        if not envfile.is_file():
            return None
        for raw_line in envfile.read_text(encoding="utf-8", errors="replace").splitlines():
            line = raw_line.strip()
            # Skip blanks and comments; tolerate an optional leading "export ".
            if not line or line.startswith("#"):
                continue
            if line.startswith("export "):
                line = line[len("export "):].lstrip()
            key, sep, value = line.partition("=")
            if sep != "=" or key.strip() != _ENV_VAR:
                continue
            value = value.strip()
            # Strip one matching pair of surrounding single or double quotes.
            if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
                value = value[1:-1]
            return value or None
    except OSError as exc:
        _diag(f"could not read env file {envfile}: {exc}")
    return None


def resolve_endpoint(workspace: str | os.PathLike[str] | None = None) -> str | None:
    """Resolve the LocalStack gateway endpoint using the bash script's publish order.

    Purpose:
        Return the URL the pytest layer should target, discovered exactly the way
        ``scripts/setup_localstack.sh`` publishes it, so bash-driven and
        Python-driven runs agree on one endpoint.

    Lookup order (WHY -- mirrors how the bash script publishes the endpoint):
        1. The ``AWS_ENDPOINT_URL`` environment variable, if set and non-empty
           (the script ``export``\\ s it; a sourcing caller inherits it directly).
        2. The line ``AWS_ENDPOINT_URL=<url>`` inside
           ``<workspace>/localstack.env``, where ``<workspace>`` is the
           ``workspace`` argument when given, else ``$CARDDEMO_TEST_WORKSPACE``
           (the script writes this file for exactly this cross-process handoff).
        3. ``None`` -- no endpoint is known; the caller should skip the AWS layer.

    Parameters:
        workspace (str | os.PathLike[str] | None): Directory expected to contain
            ``localstack.env``. When ``None``, ``$CARDDEMO_TEST_WORKSPACE`` is
            consulted; if that too is unset, only the environment variable
            (step 1) can supply an endpoint.

    Returns:
        str | None: The resolved endpoint URL, or ``None`` when neither source
        provides one.

    Raises:
        None. This function is total: every failure path returns ``None``.
    """
    # Step 1: the exported environment variable wins -- it is the freshest and is
    # how a caller that *sourced* the bash script receives the value.
    env_value = os.environ.get(_ENV_VAR)
    if env_value and env_value.strip():
        return env_value.strip()

    # Step 2: fall back to the persisted env file in the (arg or default) workspace.
    ws = workspace if workspace is not None else os.environ.get("CARDDEMO_TEST_WORKSPACE")
    if ws:
        endpoint = _read_endpoint_from_envfile(Path(ws) / _ENVFILE_NAME)
        if endpoint:
            return endpoint

    # Step 3: genuinely unknown -- the optional AWS layer will be skipped upstream.
    return None


def is_available(endpoint: str | None = None, *, timeout: float = 2.0) -> bool:
    """Report whether a LocalStack instance answers its health check.

    Purpose:
        A fast, non-raising readiness probe that issues the SAME health request
        the bash script polls, so "is LocalStack up?" is answered identically by
        both peers.

    Parameters:
        endpoint (str | None): Gateway base URL (e.g. ``http://localhost:4566``).
            When ``None``, :func:`resolve_endpoint` is used to discover it.
        timeout (float): Per-request socket timeout in seconds (keyword-only).
            Kept short by default so an unreachable host fails fast rather than
            stalling the caller.

    Returns:
        bool: ``True`` when the health endpoint returns an HTTP 2xx status;
        ``False`` on any non-2xx status, connection error, timeout, or when no
        endpoint can be resolved.

    Raises:
        None. Every exception is caught and mapped to ``False`` -- this is a
        completion probe, and a probe must never itself abort the run.
    """
    ep = endpoint if endpoint is not None else resolve_endpoint()
    if not ep:
        # No endpoint known -> definitively "not available" (not an error).
        return False

    # WHY (Refactoring rationale): a real HTTP GET against /_localstack/health is
    # the completion signal (not a fixed sleep). rstrip('/') avoids a doubled
    # slash when the endpoint already ends in '/'. We open with an explicit timeout
    # so a black-holed socket cannot outlast the caller's budget.
    url = ep.rstrip("/") + _HEALTH_PATH
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:  # noqa: S310 - loopback emulator health URL only.
            # getcode() is available on all supported Pythons; treat any 2xx as up.
            status = response.getcode()
            return status is not None and 200 <= status < 300
    except urllib.error.HTTPError as exc:
        # The server answered, just not 2xx. Only a 2xx means "ready".
        # WHY (Assumption): LocalStack health returns 200 when ready; a 4xx/5xx here
        # means the gateway is reachable but not healthy, which is still "not ready".
        return 200 <= exc.code < 300
    except (urllib.error.URLError, OSError, ValueError):
        # URLError covers connection refused / DNS / timeout; OSError covers lower
        # level socket issues; ValueError guards a malformed endpoint string.
        return False


def wait_until_ready(
    endpoint: str | None = None,
    *,
    timeout: float = 60.0,
    interval: float = 2.0,
) -> bool:
    """Poll the health endpoint until LocalStack is ready or a deadline elapses.

    Purpose:
        Provide race-free readiness the way the bash script's bounded loop does --
        returning the instant the instance is healthy and never waiting longer
        than ``timeout`` -- replacing the fixed ``sleep`` pacing of the legacy
        demo scripts this module derives from.

    Parameters:
        endpoint (str | None): Gateway base URL. When ``None``,
            :func:`resolve_endpoint` is used (re-resolved once up front).
        timeout (float): Overall readiness budget in seconds (keyword-only). A
            non-positive value degenerates to a single immediate probe.
        interval (float): Delay in seconds between probes (keyword-only). Clamped
            to a small positive minimum so the loop cannot spin.

    Returns:
        bool: ``True`` as soon as :func:`is_available` succeeds within the budget;
        ``False`` if the deadline passes without a healthy response (including
        when no endpoint can be resolved).

    Raises:
        None. Delegates entirely to the non-raising :func:`is_available`.
    """
    ep = endpoint if endpoint is not None else resolve_endpoint()
    if not ep:
        _diag("no endpoint resolved; cannot wait for readiness")
        return False

    # WHY (Refactoring rationale): time.monotonic() -- not time.time() -- drives the
    # deadline so a wall-clock adjustment (NTP step, container clock skew) during
    # the wait cannot extend or truncate the budget. The per-probe socket timeout
    # is derived from `interval` so a single slow probe cannot exceed one interval.
    probe_timeout = max(0.1, min(interval, 5.0))
    poll_interval = max(0.1, interval)
    deadline = time.monotonic() + max(0.0, timeout)

    # Probe once immediately, then loop while the deadline has not passed. This
    # guarantees at least one attempt even when timeout <= 0.
    while True:
        if is_available(ep, timeout=probe_timeout):
            return True
        if time.monotonic() >= deadline:
            _diag(f"instance at {ep} not healthy within {timeout}s")
            return False
        # Sleep, but never past the deadline (avoids one wasted over-long nap).
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(poll_interval, remaining))


def _awslocal(
    args: list[str],
    *,
    endpoint: str | None = None,
    timeout: float = 60,
    text: bool = True,
) -> subprocess.CompletedProcess | None:
    """Run an ``awslocal`` command, returning the result or ``None`` if unavailable.

    Purpose:
        Single choke point for every AWS interaction in this module. Using the
        ``awslocal`` CLI (a thin wrapper that points ``awscli`` at LocalStack)
        keeps the module dependency-free of ``boto3``.

    Parameters:
        args (list[str]): Argument vector passed after the ``awslocal`` program
            name, e.g. ``["s3", "mb", "s3://bucket"]``. Not shell-interpreted.
        endpoint (str | None): When provided, exported as ``AWS_ENDPOINT_URL`` in
            the child environment so ``awslocal`` targets that gateway
            (keyword-only).
        timeout (float): Maximum seconds to allow the command to run before it is
            killed and treated as a failure (keyword-only).
        text (bool): When ``True`` (default), capture ``stdout``/``stderr`` as
            decoded ``str`` (UTF-8, errors replaced); when ``False``, capture raw
            ``bytes`` (keyword-only). ``get_object_text`` uses ``False`` so it can
            decode an object with a caller-chosen encoding.

    Returns:
        subprocess.CompletedProcess | None: The completed process (inspect
        ``.returncode`` / ``.stdout`` / ``.stderr``), or ``None`` when
        ``awslocal`` is absent from ``PATH`` or the command could not be launched
        or timed out.

    Raises:
        None. ``FileNotFoundError``/``OSError``/``TimeoutExpired`` are caught and
        reported as ``None`` so a missing optional CLI never aborts a test.
    """
    # WHY (Alternatives Considered): a boto3 client was rejected because boto3 is
    # not in the pinned test dependencies (AAP Section 0.6.1); shelling out to the
    # already-installed `awslocal` CLI keeps this module import-clean. We probe
    # PATH with shutil.which FIRST so an absent CLI yields a clean None rather than
    # a FileNotFoundError the caller would have to handle.
    if shutil.which("awslocal") is None:
        _diag("'awslocal' not found on PATH; AWS operations skipped")
        return None

    # Copy (never replace) the environment so PATH/locale and any inherited AWS
    # settings survive; only overlay the endpoint when the caller pinned one.
    child_env = os.environ.copy()
    if endpoint:
        child_env[_ENV_VAR] = endpoint

    cmd = ["awslocal", *args]
    try:
        # WHY (Trade-off): capture_output=True keeps the child's chatter out of the
        # test's own stdout/stderr while still making it available for diagnostics.
        # A hard `timeout` bounds every call so a hung emulator cannot stall CI.
        if text:
            return subprocess.run(  # noqa: S603 - fixed argv, no shell, trusted CLI name.
                cmd,
                env=child_env,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout,
                check=False,
            )
        return subprocess.run(  # noqa: S603 - fixed argv, no shell, trusted CLI name.
            cmd,
            env=child_env,
            capture_output=True,
            timeout=timeout,
            check=False,
        )
    except subprocess.TimeoutExpired:
        _diag(f"awslocal timed out after {timeout}s: {' '.join(args)}")
        return None
    except OSError as exc:
        # Covers the race where `awslocal` disappears between the which() probe and
        # the exec, plus any other launch-level failure.
        _diag(f"failed to launch awslocal: {exc}")
        return None


def load_manifest(manifest_path: str | os.PathLike[str] | None = None) -> dict:
    """Load and parse the shared S3 seeding manifest.

    Purpose:
        Read the same ``tests/mocks/localstack_s3_manifest.json`` the bash script
        consumes, returning its parsed contents so the Python seeder works from
        an identical source of truth.

    Parameters:
        manifest_path (str | os.PathLike[str] | None): Explicit manifest path. When
            ``None``, the path is resolved as ``$CARDDEMO_REPO_ROOT/{relpath}`` if
            ``CARDDEMO_REPO_ROOT`` is set, otherwise ``<repo_root>/{relpath}``
            derived from this file's location, where ``{relpath}`` is
            ``tests/mocks/localstack_s3_manifest.json``.

    Returns:
        dict: The parsed manifest object. An empty ``dict`` is returned (with a
        diagnostic) when the file is missing, unreadable, not valid JSON, or does
        not decode to a JSON object -- never ``None``, so callers can always
        ``.get(...)`` safely.

    Raises:
        None. Every I/O and parse failure is caught and downgraded to ``{}``.
    """
    # Resolve the path: explicit arg wins; else prefer the environment's repo root
    # (authoritative in CI) and fall back to the location-derived root for a
    # developer running a single test outside the runner harness.
    if manifest_path is not None:
        path = Path(manifest_path)
    else:
        env_root = os.environ.get("CARDDEMO_REPO_ROOT")
        base = Path(env_root) if env_root else _repo_root()
        path = base / _DEFAULT_MANIFEST_RELPATH

    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        _diag(f"manifest not present ({path}); seeding will be a no-op")
        return {}
    except OSError as exc:
        _diag(f"could not read manifest {path}: {exc}")
        return {}

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as exc:
        _diag(f"manifest {path} is not valid JSON: {exc}")
        return {}

    if not isinstance(data, dict):
        # WHY (Assumption): the schema is a top-level object with .buckets/.objects.
        # A JSON array/scalar would break every downstream .get, so it is rejected
        # up front rather than crashing iter_buckets/iter_objects later.
        _diag(f"manifest {path} did not decode to a JSON object; ignoring")
        return {}
    return data


def iter_buckets(manifest: dict) -> list[str]:
    """Normalize the manifest's ``buckets`` list into plain bucket names.

    Purpose:
        Reproduce, in Python, the bash script's bucket ``jq`` filter
        ``(.buckets // [])[] | if type=="object" then .name else . end`` so the
        two seeders create exactly the same set of buckets.

    Parameters:
        manifest (dict): A manifest object as returned by :func:`load_manifest`.
            A missing or non-list ``buckets`` key yields an empty result.

    Returns:
        list[str]: Bucket names in manifest order. String entries are used
        verbatim; object entries contribute their ``name`` field. Empty,
        whitespace-only, or malformed entries are skipped (with a diagnostic).

    Raises:
        None.
    """
    result: list[str] = []
    buckets = manifest.get("buckets")
    if not isinstance(buckets, list):
        # Mirrors `(.buckets // [])`: an absent/na list simply produces nothing.
        return result

    for entry in buckets:
        # WHY (Assumption -- BINDING schema): a bucket is EITHER a plain string OR
        # an object with a `.name` key (documented in localstack_s3_manifest.json's
        # _schema). We accept both forms and defensively skip anything else so one
        # bad row cannot abort the whole seed (best-effort parity with bash, which
        # would emit "null" and later skip an empty name).
        if isinstance(entry, str):
            name = entry.strip()
        elif isinstance(entry, dict):
            raw_name = entry.get("name")
            name = raw_name.strip() if isinstance(raw_name, str) else ""
        else:
            _diag(f"ignoring unsupported bucket entry of type {type(entry).__name__}")
            continue
        if name:
            result.append(name)
    return result


def iter_objects(manifest: dict) -> list[tuple[str, str, str]]:
    """Normalize the manifest's ``objects`` list into ``(bucket, key, content)`` rows.

    Purpose:
        Reproduce, in Python, the bash script's object ``jq`` filter
        ``(.objects // [])[] | [.bucket, .key, (.content // "")] | @tsv`` so the
        two seeders upload exactly the same objects with the same payloads.

    Parameters:
        manifest (dict): A manifest object as returned by :func:`load_manifest`.
            A missing or non-list ``objects`` key yields an empty result.

    Returns:
        list[tuple[str, str, str]]: One ``(bucket, key, content)`` tuple per valid
        object entry, in manifest order. ``content`` defaults to the empty string
        when the field is absent or ``null`` (mirroring ``.content // ""``). Rows
        missing a non-empty ``bucket`` or ``key`` are skipped, exactly as the bash
        loop's ``[ -z "$_bucket" ] && continue`` / ``[ -z "$_key" ] && continue``.

    Raises:
        None.
    """
    result: list[tuple[str, str, str]] = []
    objects = manifest.get("objects")
    if not isinstance(objects, list):
        return result

    for entry in objects:
        if not isinstance(entry, dict):
            _diag(f"ignoring unsupported object entry of type {type(entry).__name__}")
            continue
        raw_bucket = entry.get("bucket")
        raw_key = entry.get("key")
        bucket = raw_bucket.strip() if isinstance(raw_bucket, str) else ""
        key = raw_key.strip() if isinstance(raw_key, str) else ""
        # WHY (Assumption): bucket and key are REQUIRED; content is OPTIONAL and
        # defaults to empty. `(.content // "")` treats both a missing key AND a JSON
        # null as "", so we coerce anything that is not a str to "" as well, keeping
        # byte-for-byte parity with the tsv the bash loop would have produced.
        raw_content = entry.get("content")
        content = raw_content if isinstance(raw_content, str) else ""
        if not bucket or not key:
            _diag(f"skipping object with empty bucket/key (bucket={bucket!r}, key={key!r})")
            continue
        result.append((bucket, key, content))
    return result


def _bucket_exists_output(text_output: str) -> bool:
    """Decide whether an ``s3 mb`` failure is the benign "already exists" case.

    Purpose:
        Distinguish a genuinely failed bucket creation from the harmless case
        where the bucket already exists, so re-seeding an already-seeded instance
        is idempotent (as the bash script treats it).

    Parameters:
        text_output (str): The combined ``stdout``/``stderr`` text from an
            ``awslocal s3 mb`` invocation.

    Returns:
        bool: ``True`` if the output indicates the bucket already exists;
        ``False`` otherwise.

    Raises:
        None.
    """
    # WHY (Assumption): AWS/LocalStack report a pre-existing bucket with one of
    # these markers. Matching on them lets a repeated seed succeed without error,
    # mirroring the bash script's "mb non-zero -> verify existence" tolerance.
    lowered = (text_output or "").lower()
    return (
        "bucketalreadyownedbyyou" in lowered
        or "bucketalreadyexists" in lowered
        or "already exists" in lowered
        or "already own" in lowered
    )


def seed_from_manifest(
    manifest_path: str | os.PathLike[str] | None = None,
    *,
    endpoint: str | None = None,
) -> dict:
    """Create the manifest's buckets and upload its objects, best-effort.

    Purpose:
        Provide the Python equivalent of the bash script's S3 seeding loop:
        create every declared bucket and upload every declared object so the
        dataset-staging tests have deterministic fixtures to assert against.

    Parameters:
        manifest_path (str | os.PathLike[str] | None): Manifest to seed from;
            resolved by :func:`load_manifest` when ``None``.
        endpoint (str | None): Gateway URL to target; resolved by
            :func:`resolve_endpoint` when ``None`` (keyword-only).

    Returns:
        dict: A summary with three lists -- ``{"buckets": [...successfully
        ensured bucket names...], "objects": [...successfully uploaded
        "bucket/key" strings...], "errors": [...human-readable failure
        strings...]}``. A non-empty ``errors`` list signals partial or total
        failure without raising.

    Raises:
        None. Each row is attempted independently inside its own guard; one bad
        bucket or object records an error and the loop continues (best-effort),
        never aborting the whole seed and never propagating an exception.
    """
    summary: dict = {"buckets": [], "objects": [], "errors": []}

    ep = endpoint if endpoint is not None else resolve_endpoint()
    if not ep:
        # No endpoint -> nothing can be seeded, but this is a soft condition.
        summary["errors"].append("no endpoint resolved; nothing seeded")
        _diag("seed_from_manifest: no endpoint resolved; nothing seeded")
        return summary

    manifest = load_manifest(manifest_path)
    if not manifest:
        summary["errors"].append("manifest empty or unreadable; nothing seeded")
        return summary

    # --- buckets --------------------------------------------------------------
    for bucket in iter_buckets(manifest):
        # WHY (Trade-off): we call `s3 mb` then tolerate an "already exists" result,
        # rather than pre-checking with `s3 ls`, because the create+tolerate path is
        # one round-trip and is exactly how the bash seeder behaves.
        proc = _awslocal(["s3", "mb", f"s3://{bucket}"], endpoint=ep)
        if proc is None:
            summary["errors"].append(f"awslocal unavailable creating bucket {bucket}")
            continue
        combined = f"{proc.stdout or ''}\n{proc.stderr or ''}"
        if proc.returncode == 0 or _bucket_exists_output(combined):
            summary["buckets"].append(bucket)
        else:
            summary["errors"].append(
                f"mb failed for {bucket} (rc={proc.returncode}): {combined.strip()}"
            )

    # --- objects --------------------------------------------------------------
    for bucket, key, content in iter_objects(manifest):
        # WHY (Trade-off): the payload is staged to a temp file and uploaded with
        # `s3 cp <file> s3://...`, rather than piped inline, because `cp` from a
        # real file transfers arbitrary bytes faithfully and matches the bash
        # loop's `printf '%s' "$content" > "$_tmp"; awslocal s3 cp "$_tmp" ...`.
        tmp_path: str | None = None
        try:
            fd, tmp_path = tempfile.mkstemp(prefix="ls-seed-")
            # Write via the low-level fd, then close it, so the file is fully
            # flushed before awslocal reads it.
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                handle.write(content)
            proc = _awslocal(["s3", "cp", tmp_path, f"s3://{bucket}/{key}"], endpoint=ep)
            if proc is None:
                summary["errors"].append(f"awslocal unavailable uploading {bucket}/{key}")
            elif proc.returncode == 0:
                summary["objects"].append(f"{bucket}/{key}")
            else:
                detail = (proc.stderr or proc.stdout or "").strip()
                summary["errors"].append(
                    f"cp failed for {bucket}/{key} (rc={proc.returncode}): {detail}"
                )
        except OSError as exc:
            # Temp-file creation/write failure is still soft: record and move on.
            summary["errors"].append(f"local error staging {bucket}/{key}: {exc}")
        finally:
            # Always clean the scratch file; a leaked temp must never accumulate.
            if tmp_path is not None:
                try:
                    os.unlink(tmp_path)
                except OSError:
                    pass

    return summary


def list_objects(bucket: str, *, endpoint: str | None = None) -> list[str]:
    """List the object keys currently present in a bucket.

    Purpose:
        Give tests a simple way to assert which objects exist, wrapping
        ``awslocal s3 ls s3://<bucket> --recursive`` and returning just the keys.

    Parameters:
        bucket (str): Bucket name to list (without the ``s3://`` scheme).
        endpoint (str | None): Gateway URL; resolved by :func:`resolve_endpoint`
            when ``None`` (keyword-only).

    Returns:
        list[str]: Object keys (full prefixes, e.g. ``carddemo/datasets/ascii/
        DISCGRP.PS``) in the order ``awslocal`` reports them. An empty list is
        returned on any failure or when the bucket has no objects.

    Raises:
        None.
    """
    ep = endpoint if endpoint is not None else resolve_endpoint()
    if not ep:
        return []
    if not bucket:
        _diag("list_objects called with an empty bucket name")
        return []

    # WHY (Refactoring rationale): `--recursive` makes `s3 ls` print one line per
    # object with the FULL key, which is what a test wants to assert on; without it
    # `s3 ls` prints directory-style prefixes that are awkward to compare.
    proc = _awslocal(["s3", "ls", f"s3://{bucket}", "--recursive"], endpoint=ep)
    if proc is None or proc.returncode != 0:
        return []

    keys: list[str] = []
    for line in (proc.stdout or "").splitlines():
        # Each `s3 ls --recursive` line is "<date> <time> <size> <key>"; the key is
        # everything after the third whitespace-delimited field. Splitting with
        # maxsplit=3 preserves any spaces inside the key itself.
        parts = line.split(maxsplit=3)
        if len(parts) == 4:
            keys.append(parts[3])
    return keys


def get_object_text(
    bucket: str,
    key: str,
    *,
    endpoint: str | None = None,
    encoding: str = "utf-8",
) -> str | None:
    """Fetch an S3 object's body and return it decoded as text.

    Purpose:
        Let tests read back a staged object's content for assertions, wrapping
        ``awslocal s3 cp s3://<bucket>/<key> -`` (stream to stdout) and decoding
        the bytes with a caller-chosen encoding.

    Parameters:
        bucket (str): Bucket name (without the ``s3://`` scheme).
        key (str): Object key within the bucket.
        endpoint (str | None): Gateway URL; resolved by :func:`resolve_endpoint`
            when ``None`` (keyword-only).
        encoding (str): Text encoding used to decode the retrieved bytes
            (keyword-only). Defaults to UTF-8; decoding uses ``errors="replace"``
            so an unexpected byte never raises.

    Returns:
        str | None: The decoded object body on success, or ``None`` on any
        failure (no endpoint, missing object, ``awslocal`` unavailable, non-zero
        exit).

    Raises:
        None.
    """
    ep = endpoint if endpoint is not None else resolve_endpoint()
    if not ep:
        return None
    if not bucket or not key:
        _diag("get_object_text called with an empty bucket or key")
        return None

    # WHY (Trade-off): request raw bytes (text=False) rather than letting the CLI
    # wrapper decode, so THIS function controls the decoding via the caller's
    # `encoding`. `-` as the cp destination streams the object body to stdout,
    # avoiding a temp file for a read-only fetch.
    proc = _awslocal(
        ["s3", "cp", f"s3://{bucket}/{key}", "-"],
        endpoint=ep,
        text=False,
    )
    if proc is None or proc.returncode != 0:
        return None

    body = proc.stdout
    if body is None:
        return ""
    if isinstance(body, bytes):
        return body.decode(encoding, errors="replace")
    # Defensive: if a future change makes stdout already-str, return it as-is.
    return body


def bring_up(name: str | None = None) -> str | None:
    """Trigger the canonical bash bring-up and return the resolved endpoint.

    Purpose:
        Offer a pure-Python convenience so an E2E test can start LocalStack
        without invoking a shell itself. This delegates to the single source of
        truth -- ``scripts/setup_localstack.sh`` -- rather than re-implementing
        the token/ephemeral-create flow in Python.

    Parameters:
        name (str | None): Optional label for the instance. It is exported to the
            child process as ``CARDDEMO_LS_NAME`` for forward compatibility; the
            canonical script currently self-names its instance, so this value is
            advisory only.

    Returns:
        str | None: The resolved endpoint after bring-up (via
        :func:`resolve_endpoint`, with a fallback that scans the script's own
        output for the gateway URL), or ``None`` if ``bash`` or the script is
        unavailable, the script failed, or no endpoint could be resolved.

    Raises:
        None. Launch failures and a non-zero script exit are reported as ``None``.

    Notes:
        The canonical bring-up path remains the bash script itself
        (``scripts/setup_localstack.sh``); this helper exists purely so a
        Python-only test can trigger it and immediately learn the endpoint.
    """
    # WHY (Alternatives Considered): re-porting the agent-token + ephemeral-create
    # flow into Python was rejected -- it would duplicate security-sensitive logic
    # (the token allowlist, cleanup traps) that already lives, reviewed, in the
    # bash script. Delegating keeps ONE implementation of that flow.
    if shutil.which("bash") is None:
        _diag("'bash' not found on PATH; cannot run setup_localstack.sh")
        return None

    env_root = os.environ.get("CARDDEMO_REPO_ROOT")
    base = Path(env_root) if env_root else _repo_root()
    script = base / "scripts" / "setup_localstack.sh"
    if not script.is_file():
        _diag(f"bring-up script not found: {script}")
        return None

    child_env = os.environ.copy()
    if name:
        # Advisory only; the script self-names, but exporting keeps a future
        # name-aware script backward compatible with today's callers.
        child_env["CARDDEMO_LS_NAME"] = name

    try:
        # WHY (Trade-off): a generous but bounded timeout -- bring-up includes a
        # network token request plus a readiness poll -- so a wedged run still
        # returns control instead of hanging the test session indefinitely.
        proc = subprocess.run(  # noqa: S603 - fixed argv, no shell, repo-local script.
            ["bash", str(script)],
            env=child_env,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=300,
            check=False,
        )
    except subprocess.TimeoutExpired:
        _diag("setup_localstack.sh timed out during bring-up")
        return None
    except OSError as exc:
        _diag(f"failed to launch setup_localstack.sh: {exc}")
        return None

    # First choice: the endpoint the script persisted (env var it exported is not
    # visible across the subprocess boundary, but the localstack.env file is).
    endpoint = resolve_endpoint()
    if endpoint:
        return endpoint

    # Fallback: scavenge the script's own stdout for the gateway URL it printed.
    # WHY (Refactoring rationale): a child process cannot mutate the parent's
    # environment, and CARDDEMO_TEST_WORKSPACE may differ between parent and child,
    # so parsing the script's reported endpoint is a robust last resort.
    for line in (proc.stdout or "").splitlines():
        stripped = line.strip()
        # The script writes/echoes "AWS_ENDPOINT_URL=<url>" and also logs a
        # "gateway endpoint: <url>" line; accept either shape.
        if stripped.startswith(f"{_ENV_VAR}="):
            candidate = stripped.split("=", 1)[1].strip().strip("'\"")
            if candidate:
                return candidate
        marker = "gateway endpoint:"
        if marker in stripped:
            candidate = stripped.split(marker, 1)[1].strip().split()[0]
            if candidate.startswith("http"):
                return candidate

    _diag("bring-up completed but no endpoint could be resolved")
    return None

