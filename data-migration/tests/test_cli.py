"""Verify the command-line entry point's accepted surface, dispatch and exit codes."""

from __future__ import annotations

import base64
import hashlib
import json
from datetime import date
from pathlib import Path
from typing import Any

import pytest

from carddemo_migration import cli, seed_datasets
from carddemo_migration.config import ConfigurationError, DatasetStagingSettings
from carddemo_migration.copybook import layouts
from carddemo_migration.credentials import EXIT_FAILED, EXIT_FATAL, EXIT_OK, EXIT_USAGE

# Assumptions: the subcommands asserted here are exactly the ones cli.build_parser
#   registers, and the test states them literally rather than reading them back from the
#   parser. Reading them back would make this test pass for any surface the module
#   happens to expose, including one that had silently lost a command.
_IMPLEMENTED_SUBCOMMANDS = (
    "list-datasets",
    "decode-record",
    "stage-dataset",
    "apply-credentials",
    "load-dataset",
    "verify-row-counts",
    "verify-checksum",
    "verify-money-parity",
)

# Assumptions: the four load and verification commands are registered because their backing
#   modules are present in this distribution -- ``readers/factory.py``, ``loaders/aurora.py``
#   and all three of ``verify/row_counts.py``, ``verify/checksum.py`` and
#   ``verify/money_parity.py``. An earlier revision of this file denied all five on the stated
#   premise that those modules were absent; the premise was true of the distribution it was
#   written against and is false of this one, so the denial is narrowed to the one command that
#   really has no implementation rather than left asserting an absence that no longer holds.
#
# Refactoring Rationale: the two verification commands are spelled ``verify-checksum`` and
#   ``verify-money-parity``, matching the modules that back them, where README.md section 5.2
#   contracted ``verify-checksums`` and ``verify-money-totals``. The table has been corrected to
#   the implemented spelling rather than the parser bent to the contracted one, because the
#   contract named commands nothing implemented and the module names are the spelling every
#   other reference in the distribution already uses.
#
# Assumptions: ``verify-all`` stays denied. It is the only one of the five with no
#   implementation: it would sequence the three passes and stop at the first failure, which
#   needs a dataset-to-source manifest this distribution does not carry -- every verification
#   command here is told its source explicitly. The denial is kept as an explicit test because
#   an unimplemented command that reaches ``--help`` is how an orchestrator comes to be wired to
#   a state that cannot run.
_UNREGISTERED_SUBCOMMANDS = ("verify-all",)

# Assumptions: the decode tests read the SHIPPED extracts rather than fixtures built here,
#   because the command exists to prove a real delivery decodes at its declared geometry and a
#   fixture this file wrote would only prove the file agreed with itself. The paths are
#   resolved from this module's own location so the tests run from any working directory.
_EBCDIC_DIRECTORY = Path(__file__).resolve().parents[2] / "app" / "data" / "EBCDIC"
_ACCOUNT_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.ACCTDATA.PS"
_CARD_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.CARDDATA.PS"
_USER_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.USRSEC.PS"


class _FakeS3Client:
    """Stand in for a boto3 S3 client without building one.

    Purpose
    -------
    Satisfy the staging handler's client seam so no AWS SDK client is constructed, and
    carry the ``meta.region_name`` attribute the handler reports.
    """

    class meta:  # noqa: N801 - mirrors boto3's own lower-case attribute name
        """Expose the region the handler logs, as boto3's client meta does."""

        region_name = "us-east-1"


class _CapableS3Client(_FakeS3Client):
    """Stand in for an S3 client completely enough to run the real staging path.

    Purpose
    -------
    Let a test drive the staging subcommand through the ACTUAL staging implementation rather than
    a monkeypatched stub, so the evidence only the hardened path produces -- a service-verifiable
    checksum and the audit metadata -- can be observed on the recorded request.

    Attributes
    ----------
    puts : list of dict
        Every ``put_object`` request the staging path issued, in order.
    """

    def __init__(self) -> None:
        """Create an empty request log."""
        self.puts: list[dict[str, Any]] = []

    def get_paginator(self, operation_name: str) -> Any:
        """Return a paginator that reports an empty bucket for any prefix."""

        # Assumptions: an empty bucket is enough for this test. Retention cleanup then finds no
        #   rolled-off generation to scratch, which keeps the assertion focused on how the object
        #   was WRITTEN rather than on cleanup arithmetic that test_s3_stage.py already covers.
        class _Empty:
            """Yield one page with no contents and no common prefixes."""

            def paginate(self, **kwargs: Any) -> list[dict[str, Any]]:
                """Return a single empty page."""
                return [{}]

        return _Empty()

    def put_object(self, **kwargs: Any) -> dict[str, str]:
        """Record the request, consuming a streamed body the way the SDK would."""
        body = kwargs.get("Body")
        if body is not None and not isinstance(body, bytes):
            kwargs = {**kwargs, "Body": body.read()}
        self.puts.append(kwargs)
        return {"ETag": "fake"}

    def delete_objects(self, **kwargs: Any) -> dict[str, Any]:
        """Report no deletion errors."""
        return {"Errors": []}


def _test_settings() -> DatasetStagingSettings:
    """Return validated staging settings for a test deployment.

    Returns
    -------
    DatasetStagingSettings
        Settings naming a visibly non-production bucket and environment.
    """
    return DatasetStagingSettings(bucket="carddemo-datasets-test", environment="test")


def _registered_subcommands() -> tuple[str, ...]:
    """Read the subcommand names the parser actually registers.

    Purpose
    -------
    Reach the subparser action once, so each assertion below reads the real surface
    rather than re-deriving it.

    Returns
    -------
    tuple of str
        Every registered subcommand name, in registration order.

    Raises
    ------
    AssertionError
        If the parser registers no subparser action at all, which would mean the entry
        point accepts anything.
    """
    actions = [
        action
        for action in cli.build_parser()._actions
        if hasattr(action, "choices") and action.choices
    ]
    subparser_actions = [action for action in actions if isinstance(action.choices, dict)]
    assert subparser_actions, "the parser registers no subcommands"
    return tuple(subparser_actions[0].choices)


def test_every_implemented_subcommand_is_registered() -> None:
    """Register exactly the subcommands whose backing modules are present."""
    assert _registered_subcommands() == _IMPLEMENTED_SUBCOMMANDS


@pytest.mark.parametrize("name", _UNREGISTERED_SUBCOMMANDS)
def test_contracted_but_unbacked_subcommands_are_absent(name: str) -> None:
    """Refuse a contracted subcommand whose backing module is not in this distribution."""
    assert name not in _registered_subcommands()
    assert cli.main([name]) == EXIT_USAGE


def test_help_reports_success() -> None:
    """Exit zero for --help, because the package Dockerfile's CMD is ["--help"]."""
    with pytest.raises(SystemExit) as raised:
        cli.build_parser().parse_args(["--help"])
    assert raised.value.code == 0
    assert cli.main(["--help"]) == EXIT_OK


def test_missing_subcommand_is_a_usage_error() -> None:
    """Refuse a bare invocation instead of exiting zero having done nothing."""
    assert cli.main([]) == EXIT_USAGE


def test_unknown_subcommand_is_a_usage_error() -> None:
    """Refuse a subcommand that was never registered."""
    assert cli.main(["definitely-not-a-subcommand"]) == EXIT_USAGE


def test_list_datasets_table_lists_every_layout(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Print one row per registered layout, with the declared geometry."""
    assert cli.main(["list-datasets"]) == EXIT_OK
    printed = capsys.readouterr().out
    for name in layouts.names():
        assert name in printed
        assert str(layouts.reclen_of(name)) in printed
    # Assumptions: the header is upper-cased column names, so asserting on it pins the
    #   column set a consumer reads without pinning the column widths, which are sized
    #   from content and would change the moment a longer record name is registered.
    assert "DATASET" in printed
    assert "PROVENANCE" in printed


def test_list_datasets_json_is_parseable_and_ordered(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Emit a JSON array in registry order with the documented keys."""
    assert cli.main(["list-datasets", "--format", "json"]) == EXIT_OK
    rows = json.loads(capsys.readouterr().out)
    assert [row["dataset"] for row in rows] == list(layouts.names())
    assert set(rows[0]) == {"dataset", "copybook", "reclen", "keylen", "provenance"}
    assert rows[0]["reclen"] == layouts.reclen_of(rows[0]["dataset"])


def test_list_datasets_rejects_an_unknown_format() -> None:
    """Refuse a format the command does not render."""
    assert cli.main(["list-datasets", "--format", "xml"]) == EXIT_USAGE


def test_list_datasets_touches_no_client(monkeypatch: pytest.MonkeyPatch) -> None:
    """Read no configuration and build no client, so it runs with no environment."""

    # Trade-offs: the guard replaces the configuration resolver with a raiser rather
    #   than inspecting imports. Asserting on imports would pass while a call still
    #   happened through an already-imported module; a raiser fails loudly the moment
    #   this command reaches for an environment it promises not to need.
    def _refuse() -> None:
        """Fail the test if staging settings are resolved at all."""
        raise AssertionError("list-datasets must not resolve staging settings")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _refuse)
    assert cli.main(["list-datasets"]) == EXIT_OK


def _stage_arguments(source: Path, dataset: str = "transactions") -> list[str]:
    """Build a stage-dataset argument list that pins the source and generation explicitly.

    Purpose
    -------
    Keep one argument set in one place for the tests that assert on forwarding and on exit
    tiers, supplying ``--source`` and ``--generation`` so those tests stay independent of
    the deployment environment.

    Parameters
    ----------
    source : Path
        Value for ``--source``.
    dataset : str, optional
        Value for ``--dataset``; defaults to a registered seed-dataset token.

    Returns
    -------
    list of str
        The argument list, excluding the program name.

    Raises
    ------
    None
    """
    # Refactoring Rationale: the default dataset became the seed-dataset TOKEN
    #   `transactions` where it was the layout name `TRAN`. The two vocabularies were
    #   unified so the orchestrator's own `--dataset=accounts` can be accepted, and a
    #   layout name is no longer a legal value here. `--source` and `--generation` are
    #   still passed even though both are now optional, because these tests assert on
    #   argument forwarding and on exit tiers: letting them be derived would make each one
    #   also depend on a staging-root variable and a reservation round trip, which
    #   test_stage_dataset_derives_every_omitted_argument covers deliberately instead.
    return [
        "stage-dataset",
        "--dataset",
        dataset,
        "--source",
        str(source),
        "--business-date",
        "2022-07-18",
        "--generation",
        "1",
        "--domain",
        "ledger",
    ]


def test_stage_dataset_rejects_an_unknown_dataset(tmp_path: Path) -> None:
    """Refuse a dataset identifier that no layout is registered under."""
    source = tmp_path / "extract.dat"
    source.write_bytes(b"x" * 350)
    assert cli.main(_stage_arguments(source, dataset="NOT-A-DATASET")) == EXIT_USAGE


def test_stage_dataset_reports_an_unreadable_source(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report a failed step, not a usage error, when the extract cannot be read."""
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)
    # Refactoring Rationale: the environment is stubbed here where this test previously relied on
    #   the source being read before the environment was resolved. Source verification now lives
    #   inside the hardened staging call, so the resolver runs first; supplying a resolvable
    #   environment is what keeps this test measuring the thing it names -- the tier an
    #   unreadable extract reports -- rather than the tier an unset variable reports.
    assert cli.main(_stage_arguments(tmp_path / "absent.dat")) == EXIT_FAILED


def test_stage_dataset_reports_the_environment_before_the_extract(tmp_path: Path) -> None:
    """Report the fatal tier when neither the environment nor the extract can be resolved."""
    # Assumptions: this pins an ORDERING decision rather than a value, so the observable
    #   consequence of resolving the environment first is recorded instead of being incidental.
    #   With both broken the environment wins, because nothing can be staged until it resolves.
    assert cli.main(_stage_arguments(tmp_path / "absent.dat")) == EXIT_FATAL


def test_stage_dataset_rejects_a_malformed_business_date(tmp_path: Path) -> None:
    """Refuse a business date that is not an ISO calendar date."""
    source = tmp_path / "extract.dat"
    source.write_bytes(b"x")
    arguments = _stage_arguments(source)
    arguments[arguments.index("2022-07-18")] = "18/07/2022"
    assert cli.main(arguments) == EXIT_USAGE


def test_stage_dataset_reports_an_unresolvable_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report the fatal tier when the staging settings cannot be resolved."""
    source = tmp_path / "extract.dat"
    source.write_bytes(b"x")

    def _unresolvable() -> None:
        """Stand in for a staging resolver that cannot find its parameters."""
        raise ConfigurationError("no dataset bucket parameter")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _unresolvable)
    assert cli.main(_stage_arguments(source)) == EXIT_FATAL


def test_stage_dataset_passes_every_argument_through(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Forward each parsed argument to the hardened staging call unchanged."""
    source = tmp_path / "DALYTRAN.PS"
    payload = bytes(range(256)) * 2
    source.write_bytes(payload)
    recorded: dict[str, Any] = {}
    settings = DatasetStagingSettings(bucket="carddemo-datasets-test", environment="test")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", lambda: settings)
    # Assumptions: the client seam is stubbed rather than letting boto3 build a real
    #   client. Measured reason: boto3.client("s3") does NOT raise without a configured
    #   region -- it resolves to us-east-1 through S3's global-endpoint fallback, where
    #   boto3.client("sqs") raises NoRegionError -- so a real client would make this test
    #   pass by depending on an SDK fallback rather than on anything this module does.
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)

    def _capture(**kwargs: Any) -> cli.StagedObject:
        """Record the staging call and answer with a fixed successful report."""
        recorded.update(kwargs)
        return cli.StagedObject(
            key="ledger/transactions/dt=2022-07-18/gen=0001/DALYTRAN.PS",
            prefix="ledger/transactions/dt=2022-07-18/gen=0001/",
            business_date=date(2022, 7, 18),
            generation=1,
            byte_size=len(payload),
            sha256="0" * 64,
            deleted_generation_prefixes=(),
        )

    monkeypatch.setattr(cli, "stage_dataset_file", _capture)
    assert cli.main(_stage_arguments(source)) == EXIT_OK

    assert recorded["client"] is not None
    assert recorded["settings"] is settings
    assert recorded["dataset"] == "transactions"
    assert recorded["domain"] == "ledger"
    assert recorded["business_date"] == date(2022, 7, 18)
    assert recorded["generation"] == 1
    assert recorded["retention_count"] == cli.DEFAULT_RETENTION_COUNT
    # Refactoring Rationale: the SOURCE PATH is forwarded, where the earlier shape forwarded a
    #   `payload` of bytes this function had already read. Passing the path is what lets the
    #   staging call hold one descriptor across the digest and the transfer; passing bytes made
    #   peak memory scale with the dataset and left the digest describing a separate read.
    assert recorded["source"] == source
    assert "payload" not in recorded
    # Assumptions: the object name defaults to the SOURCE FILE NAME, which is the only
    #   place the provenance of the bytes survives once the prefix has taken the
    #   dataset, the date and the generation.
    assert recorded["object_name"] == "DALYTRAN.PS"


def test_stage_dataset_honours_an_explicit_object_name_and_retention(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Prefer the caller's object name and retention count over the defaults."""
    source = tmp_path / "extract.dat"
    source.write_bytes(b"payload")
    recorded: dict[str, Any] = {}

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)

    def _capture(**kwargs: Any) -> cli.StagedObject:
        """Record the staging call and answer with a report that scratched one prefix."""
        recorded.update(kwargs)
        return cli.StagedObject(
            key="k",
            prefix="p/",
            business_date=date(2022, 7, 18),
            generation=1,
            byte_size=7,
            sha256="0" * 64,
            deleted_generation_prefixes=("old/",),
        )

    monkeypatch.setattr(cli, "stage_dataset_file", _capture)
    arguments = _stage_arguments(source) + ["--object-name", "TRANSACT.BKUP", "--retain", "3"]
    assert cli.main(arguments) == EXIT_OK
    assert recorded["object_name"] == "TRANSACT.BKUP"
    assert recorded["retention_count"] == 3


def test_stage_dataset_maps_a_rejected_request_to_usage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report a usage error when the staging module refuses a caller-supplied value."""
    source = tmp_path / "extract.dat"
    source.write_bytes(b"payload")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)

    def _refuse(**kwargs: Any) -> None:
        """Stand in for a staging call that rejects an out-of-range generation."""
        raise ValueError("generation 0 is outside 1-9999")

    monkeypatch.setattr(cli, "stage_dataset_file", _refuse)
    assert cli.main(_stage_arguments(source)) == EXIT_USAGE


def test_stage_dataset_reaches_the_hardened_staging_path(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Prove the production command runs the hardened implementation, not just any writer."""
    source = tmp_path / "DALYTRAN.PS"
    payload = bytes(range(256)) * 4
    source.write_bytes(payload)
    client = _CapableS3Client()

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    # Refactoring Rationale: the staging call is deliberately NOT monkeypatched here, unlike its
    #   sibling tests. Those assert which arguments are forwarded, which a stub can answer; this
    #   one asserts that the real implementation ran, which a stub cannot. It is the test that
    #   would have caught the defect being fixed: before this change the command called a
    #   byte-oriented entry point that produced none of the evidence below, while a hardened
    #   file-based path sat in the same module with no caller at all. Asserting on evidence only
    #   the hardened path emits is what makes "the production path is the hardened path" checkable
    #   rather than a claim about which name appears in an import.
    assert cli.main(_stage_arguments(source)) == EXIT_OK

    assert len(client.puts) == 1
    request = client.puts[0]
    expected = hashlib.sha256(payload).digest()
    # The service-verifiable checksum: only the hardened path supplies it.
    assert request["ChecksumSHA256"] == base64.b64encode(expected).decode("ascii")
    # The audit anchors written as metadata: only the hardened path records them.
    assert request["Metadata"]["carddemo-sha256"] == expected.hex()
    assert request["Metadata"]["carddemo-byte-size"] == str(len(payload))
    # The declared length, so a truncated stream is refused server-side.
    assert request["ContentLength"] == len(payload)
    # Assumptions: the bytes are asserted verbatim against a 1024-byte payload cycling all 256
    #   values. That content is chosen because it is what a text-mode read, a UTF-8 decode or a
    #   newline translation would visibly corrupt, so a byte-for-byte match is real evidence the
    #   binary discipline held end to end rather than a tautology over printable ASCII.
    assert request["Body"] == payload


def test_stage_dataset_accepts_the_orchestrators_exact_invocation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Stage successfully from only the two arguments Step Functions actually supplies."""
    # WHY : Refactoring Rationale: this is the acceptance test for the defect being fixed. The
    #   orchestrator builds its command as
    #   States.Array('stage-dataset', '--dataset={}', '--business-date={}') and supplies nothing
    #   else, while --source, --generation and --domain were each required=True -- so argparse
    #   aborted with status 2 and no dataset was ever staged. The argv below is that command
    #   verbatim, in the '--flag=value' form the orchestrator emits rather than a space-separated
    #   equivalent, so the test exercises the same parse the deployed definition produces.
    descriptor = seed_datasets.seed_dataset("accounts")
    staging_root = tmp_path / "extracts"
    staging_root.mkdir()
    payload = bytes(range(256)) * 3 + b"x" * (300 - (768 % 300))
    (staging_root / descriptor.source_object).write_bytes(payload)
    client = _CapableS3Client()

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(staging_root))
    monkeypatch.setenv("CARDDEMO_BATCH_RUN_ID", "carddemo-daily-batch-2022-07-18")

    assert (
        cli.main(["stage-dataset", "--dataset=accounts", "--business-date=2022-07-18"]) == EXIT_OK
    )

    # Assumptions: the dataset write is selected by EXCLUDING the conditional claim write, because
    #   reserving a generation now issues its own put. Taking the last put would work today and
    #   would silently start asserting on the wrong request if the order ever changed.
    dataset_writes = [put for put in client.puts if put.get("IfNoneMatch") != "*"]
    assert len(dataset_writes) == 1
    written = dataset_writes[0]
    # Every derived value appears in the key: the domain and prefix segment from the descriptor,
    # the reserved generation, and the object name from the registered source object.
    assert written["Key"] == (f"account/accounts/dt=2022-07-18/gen=0001/{descriptor.source_object}")
    assert written["Body"] == payload


def test_stage_dataset_requires_a_staging_root_when_no_source_is_given(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report the fatal tier, naming the variable, when the extract cannot be located."""
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)
    monkeypatch.delenv(seed_datasets.STAGING_ROOT_VARIABLE, raising=False)

    # WHY : Assumptions: this is the FATAL tier and not the failed tier. The image ships no
    #   extract, so an unset staging root is a deployment that has not been configured rather than
    #   a step whose input went missing, and the two want different operator responses.
    assert (
        cli.main(["stage-dataset", "--dataset=accounts", "--business-date=2022-07-18"])
        == EXIT_FATAL
    )


def test_stage_dataset_requires_an_execution_token_to_reserve_a_generation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Refuse to reserve a generation without the orchestrator identity a retry is keyed on."""
    descriptor = seed_datasets.seed_dataset("users")
    staging_root = tmp_path / "extracts"
    staging_root.mkdir()
    (staging_root / descriptor.source_object).write_bytes(b"u" * 80)

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _CapableS3Client)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(staging_root))
    monkeypatch.delenv("CARDDEMO_BATCH_RUN_ID", raising=False)

    # WHY : Assumptions: the reservation refuses rather than inventing an identity. Any invented
    #   token -- a process id, a host name, a timestamp -- makes every retry look like a new
    #   execution, which is the one case the reservation exists to recognise, so a fabricated
    #   default would silently reintroduce duplicate generations.
    assert (
        cli.main(["stage-dataset", "--dataset=users", "--business-date=2022-07-18"]) == EXIT_FATAL
    )


def test_stage_dataset_refuses_a_layout_name_now_that_the_vocabulary_is_unified(
    tmp_path: Path,
) -> None:
    """Refuse a copybook layout name, which is no longer this subcommand's vocabulary."""
    source = tmp_path / "extract.dat"
    source.write_bytes(b"x" * 350)
    # WHY : Assumptions: 'TRAN' is a real, registered LAYOUT name, so this is not a typo test --
    #   it pins that the two vocabularies were unified onto the orchestrator's tokens rather than
    #   merely widened to accept both. Accepting both was the alternative and was rejected because
    #   one flag with two vocabularies is the ambiguity this change set out to remove.
    assert "TRAN" in layouts.names()
    assert "TRAN" not in seed_datasets.seed_dataset_tokens()
    assert cli.main(_stage_arguments(source, dataset="TRAN")) == EXIT_USAGE


def test_stage_dataset_derives_the_record_length_only_for_a_registered_source(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Apply the fixed-record check to a derived extract and skip it for an explicit path."""
    descriptor = seed_datasets.seed_dataset("card_xref")
    recorded: list[Any] = []

    def _capture(**kwargs: Any) -> cli.StagedObject:
        """Record the staging call and answer with a fixed successful report."""
        recorded.append(kwargs)
        return cli.StagedObject(
            key="k",
            prefix="p/",
            business_date=date(2022, 7, 18),
            generation=1,
            byte_size=50,
            sha256="0" * 64,
            deleted_generation_prefixes=(),
        )

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)
    monkeypatch.setattr(cli, "stage_dataset_file", _capture)

    staging_root = tmp_path / "extracts"
    staging_root.mkdir()
    (staging_root / descriptor.source_object).write_bytes(b"x" * 50)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(staging_root))
    assert (
        cli.main(
            [
                "stage-dataset",
                "--dataset=card_xref",
                "--business-date=2022-07-18",
                "--generation=1",
            ]
        )
        == EXIT_OK
    )
    # WHY : Assumptions: the derived path carries the declared width because every registered
    #   source names the EBCDIC form, and all ten of those divide exactly by their record length.
    assert recorded[-1]["record_length"] == seed_datasets.record_length(descriptor)

    explicit = tmp_path / "cardxref.txt"
    explicit.write_bytes(b"y" * 36)
    assert cli.main(_stage_arguments(explicit, dataset="card_xref")) == EXIT_OK
    # WHY : Assumptions: an explicit path carries NO declared width, and the reason is measured:
    #   app/data/ASCII/cardxref.txt holds 36 data bytes per line where the copybook declares 50,
    #   so applying the check to an operator-supplied path would refuse a perfectly good extract.
    assert recorded[-1]["record_length"] is None


def test_stage_dataset_maps_a_source_refusal_to_failed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report the failed tier for a source refusal even though it is also a ValueError."""
    source = tmp_path / "extract.dat"
    source.write_bytes(b"payload")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)

    def _refuse(**kwargs: Any) -> None:
        """Stand in for a staging call that refuses the extract it was handed."""
        raise cli.DatasetSourceError("the dataset source /x changed identity while staging")

    monkeypatch.setattr(cli, "stage_dataset_file", _refuse)
    # WHY : Assumptions: DatasetSourceError SUBCLASSES ValueError, so the two except clauses are
    #   order-dependent and this pins the order. Catching ValueError first would silently
    #   reclassify every unreadable or substituted extract as a usage error, telling an operator
    #   to fix their arguments when the real fault is in the step's environment.
    assert cli.main(_stage_arguments(source)) == EXIT_FAILED


def test_apply_credentials_delegates_with_an_empty_argument_list(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Delegate to the credential module's own entry point, not to a copied mapping."""
    seen: list[Any] = []

    def _record(argv: Any = None) -> int:
        """Record the argument vector the credential entry point was handed."""
        seen.append(argv)
        return EXIT_FAILED

    monkeypatch.setattr(cli.credentials, "main", _record)
    assert cli.main(["apply-credentials"]) == EXIT_FAILED
    # Assumptions: the empty list must be passed explicitly. Falling back to sys.argv
    #   would hand the delegate this command's own subcommand name, which its parser
    #   accepts no positional argument for, turning a valid invocation into a usage
    #   error.
    assert seen == [[]]


def test_apply_credentials_accepts_no_options() -> None:
    """Refuse an option, because a per-role invocation leaves a deployment half applied."""
    assert cli.main(["apply-credentials", "--role", "carddemo_ledger"]) == EXIT_USAGE


def _decode_arguments(
    source: Path,
    *,
    dataset: str = "ACCOUNT",
    record: int = 1,
) -> list[str]:
    """Build a decode-record argument list with one field overridable per call.

    Purpose
    -------
    Keep the decode tests below from restating the full argument list, so a test reads as
    the one property it asserts rather than as an invocation.

    Parameters
    ----------
    source : Path
        Path passed as ``--source``.
    dataset : str, optional
        Layout name passed as ``--dataset``; defaults to the account master.
    record : int, optional
        One-based ordinal passed as ``--record``; defaults to the first record.

    Returns
    -------
    list of str
        A complete argument list for :func:`cli.main`.

    Raises
    ------
    None
    """
    return [
        "decode-record",
        "--dataset",
        dataset,
        "--source",
        str(source),
        "--record",
        str(record),
    ]


def test_decode_record_decodes_a_shipped_extract(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Decode a record of the shipped account extract through the whole codec stack."""
    # Assumptions: this test is what makes the packed, zoned and EBCDIC codecs EXECUTED
    #   through a caller rather than only unit-tested in isolation. It reads the real
    #   extract, so a defect anywhere from the record cut through the per-field transcode to
    #   the fixed-point assembly surfaces here as a wrong value rather than as nothing.
    # Refactoring Rationale: this case asserted the decoded identifier and balance in CLEAR --
    #   `ACCT-ID == "00000000001"` and `ACCT-CURR-BAL == "194.00"`. Those assertions were
    #   correct against a layout that marked no account field sensitive, and they became a test
    #   that PINNED a disclosure once the account master came under the fail-closed master
    #   disclosure policy: this command redacts every sensitive field through `_redacted`, so
    #   the two values now arrive as keyed tags. The assertions are updated to the policy rather
    #   than the policy relaxed to the assertions, and the codec coverage this case exists for is
    #   preserved by the two checks below, which prove the record decoded at its declared
    #   geometry without reproducing a protected value.
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT)) == EXIT_OK
    fields = json.loads(capsys.readouterr().out)

    # Assumptions: a non-sensitive field is asserted verbatim, and it is the one that proves the
    #   codec stack ran: the open date is assembled from the record's own bytes at a declared
    #   offset, so a wrong cut, a wrong transcode or a wrong offset changes it. A redacted field
    #   could not carry that evidence, which is why the case keeps a clear-value assertion at all.
    assert fields["ACCT-OPEN-DATE"] == "2014-11-20"
    assert fields["ACCT-ACTIVE-STATUS"] == "Y"

    # Assumptions: the protected fields are asserted to be PRESENT and NOT to be their clear
    #   values. Presence is what shows they decoded rather than being dropped; the negative is
    #   what shows the redaction applied. Asserting the tag's exact text was rejected -- the tag
    #   is keyed, so it is stable only for a fixed key and this case supplies none.
    assert "ACCT-ID" in fields
    assert fields["ACCT-ID"] != "00000000001"
    assert "ACCT-CURR-BAL" in fields
    assert fields["ACCT-CURR-BAL"] != "194.00"


def test_decode_record_renders_money_as_a_string(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Emit every value as a JSON string so no consumer re-reads money as a float."""
    # Assumptions: the type is asserted on the PARSED JSON, which is the only place the
    #   distinction is observable. A JSON number would be read into a float by this very
    #   parser, and the whole fixed-point codec stack exists to keep that from happening.
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT)) == EXIT_OK
    fields = json.loads(capsys.readouterr().out)
    assert all(isinstance(value, str) for value in fields.values())


def test_decode_record_redacts_every_sensitive_field(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Never print a stored password, a cardholder name or a card verification value."""
    # Assumptions: the security-relevant assertion is the ABSENCE of the cleartext, and it is
    #   made against the whole rendered output rather than against one field, because a
    #   sensitive value reaching a container log does so through whatever field carried it.
    #   The shipped security extract stores its passwords in plaintext, which is exactly why
    #   a diagnostic that printed them would be a new exposure rather than a cosmetic one.
    assert cli.main(_decode_arguments(_USER_EXTRACT, dataset="SECUSER", record=2)) == EXIT_OK
    printed = capsys.readouterr().out
    assert "PASSWORD" not in printed
    fields = json.loads(printed)
    assert fields["SEC-USR-ID"] == "ADMIN002"
    assert fields["SEC-USR-TYPE"] == "A"
    assert "<" in fields["SEC-USR-PWD"]


def test_decode_record_reveals_only_a_card_number_s_last_four(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Mask a card number to its trailing four digits and suppress the verification value."""
    assert cli.main(_decode_arguments(_CARD_EXTRACT, dataset="CARD")) == EXIT_OK
    fields = json.loads(capsys.readouterr().out)
    assert fields["CARD-NUM"].startswith("*" * 12)
    assert len(fields["CARD-NUM"]) == layouts.layout("CARD").fields[0].length
    assert not fields["CARD-CVV-CD"].isdigit()


def test_decode_record_rejects_an_unknown_dataset(tmp_path: Path) -> None:
    """Refuse an unregistered layout name before opening anything."""
    absent = tmp_path / "never-opened.PS"
    assert cli.main(_decode_arguments(absent, dataset="NOT-A-DATASET")) == EXIT_USAGE
    # Assumptions: the source deliberately does not exist, so a usage result also proves the
    #   name is validated BEFORE any I/O. Were the order reversed this would report a read
    #   failure and the misspelling would be diagnosed as a missing file.
    assert not absent.exists()


def test_decode_record_rejects_a_non_positive_ordinal() -> None:
    """Refuse record zero, because the ordinal this command accepts is one-based."""
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT, record=0)) == EXIT_USAGE


def test_decode_record_reports_an_ordinal_past_the_end_of_the_dataset() -> None:
    """Refuse an ordinal the dataset does not reach rather than printing nothing."""
    beyond = _ACCOUNT_EXTRACT.stat().st_size // layouts.reclen_of("ACCOUNT") + 1
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT, record=beyond)) == EXIT_USAGE


def test_decode_record_reports_an_unreadable_source(tmp_path: Path) -> None:
    """Report a failed step, not a usage error, when the extract cannot be read."""
    assert cli.main(_decode_arguments(tmp_path / "absent.PS")) == EXIT_FAILED


def test_decode_record_reports_a_truncated_dataset(tmp_path: Path) -> None:
    """Report a dataset that does not divide into whole records as a failed step."""
    # Assumptions: the truncation is asserted to be reported for RECORD 1, which the file
    #   does contain in full. That is the point: the division check runs before the first
    #   record is yielded, so a truncated delivery is refused rather than partly loaded.
    truncated = tmp_path / "truncated.PS"
    reclen = layouts.reclen_of("ACCOUNT")
    truncated.write_bytes(_ACCOUNT_EXTRACT.read_bytes()[: reclen * 2 + 13])
    assert cli.main(_decode_arguments(truncated)) == EXIT_FAILED


def test_decode_record_rejects_an_unregistered_code_page() -> None:
    """Report a code page the interpreter does not register as a failed step."""
    arguments = [*_decode_arguments(_ACCOUNT_EXTRACT), "--code-page", "cp-does-not-exist"]
    assert cli.main(arguments) == EXIT_FAILED


def test_decode_record_requires_both_the_dataset_and_the_source() -> None:
    """Refuse an invocation missing either required argument."""
    assert cli.main(["decode-record", "--dataset", "ACCOUNT"]) == EXIT_USAGE
    assert cli.main(["decode-record", "--source", str(_ACCOUNT_EXTRACT)]) == EXIT_USAGE
