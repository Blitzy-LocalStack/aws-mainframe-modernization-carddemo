"""Verify the command-line entry point's accepted surface, dispatch and exit codes."""

from __future__ import annotations

import base64
import hashlib
import json
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import TYPE_CHECKING, Any

import pytest

from carddemo_migration import cli, seed_datasets
from carddemo_migration.config import (
    AuroraConnectionSettings,
    ConfigurationError,
    DatasetStagingSettings,
)
from carddemo_migration.copybook import ebcdic_codec, layouts
from carddemo_migration.credentials import EXIT_FAILED, EXIT_FATAL, EXIT_OK, EXIT_USAGE
from carddemo_migration.loaders.aurora import target_for
from carddemo_migration.readers import usrsec

if TYPE_CHECKING:
    from conftest import FakeAuroraDatabase

# Assumptions: the subcommands asserted here are exactly the ones cli.build_parser
#   registers, in registration order, and the test states them literally rather than reading
#   them back from the parser. Reading them back would make this test pass for any surface
#   the module happens to expose, including one that had silently lost a command.
# Refactoring Rationale: the last four were moved here out of the denial tuple below when
#   their backing modules landed -- readers/, loaders/aurora.py and verify/ are now in the
#   distribution, so a parser that did NOT advertise them would be the defect. Updating this
#   tuple is deliberately the work required to change the delivered surface: a test that read
#   the parser back would have accepted the four new commands silently, and would equally have
#   accepted their disappearance.
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
# Assumptions: the disclosure-group extract is read for one reason the other three cannot
#   serve. DIS-INT-RATE is the only SIGNED ZONED field the corpus disclosure allowlist in
#   layouts.py admits, so it is the only shipped value through which this command's end-to-end
#   assembly of a sign-overpunched fixed-point number stays ASSERTABLE now that every account
#   balance and transaction amount is withheld from the rendering.
_DISCLOSURE_GROUP_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.DISCGRP.PS"

# Assumptions: the load and verification tests below read the ASCII seed rather than the EBCDIC
#   twin, because the runbook nominates the ASCII tree as the authoritative form for the datasets
#   an operator loads and it is the form those documented commands name. The EBCDIC path is
#   already covered record-by-record by the reader suite, so exercising it again here would test
#   the codec a second time rather than the command.
_ASCII_DIRECTORY = Path(__file__).resolve().parents[2] / "app" / "data" / "ASCII"
_CATEGORY_BALANCE_SEED = _ASCII_DIRECTORY / "tcatbal.txt"


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

    # Refactoring Rationale: the fixed-point half of this proof read
    #   ACCT-CURR-BAL == "194.00", and it is moved to DIS-INT-RATE because layouts.py now
    #   WITHHOLDS every account balance from a diagnostic rendering. Asserting the cleartext
    #   balance would have held this command to the fail-open behaviour that closure removed,
    #   and asserting the redaction instead would have proved nothing about the codecs -- a tag
    #   is computed from the rendered characters, so a wrong value yields a different tag that
    #   this test has no independent way to predict. The disclosure-group rate keeps the
    #   property intact: 15.00 is assembled from the six EBCDIC bytes "00150{" through the same
    #   cp037 transcode and the same sign-overpunch branch, and the allowlist admits it because
    #   it is seeded reference configuration rather than a customer's money.
    assert cli.main(_decode_arguments(_DISCLOSURE_GROUP_EXTRACT, dataset="DISGROUP")) == EXIT_OK
    group = json.loads(capsys.readouterr().out)
    assert group["DIS-ACCT-GROUP-ID"] == "A000000000"
    assert group["DIS-INT-RATE"] == "15.00"


def test_decode_record_withholds_account_money_and_its_postal_code(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Never print a balance, a credit limit, a cycle total or a postal code.

    Purpose
    -------
    Assert the fail-closed master disclosure policy at the one boundary an operator actually
    sees: the decoded record this command writes to standard output. Both halves of the policy
    are asserted together -- the withheld fields must not appear in clear, and the admitted
    fields must still appear -- because either half alone is satisfied by a command that has
    stopped being useful.

    Parameters
    ----------
    capsys : pytest.CaptureFixture[str]
        Captures standard output, which is where the decoded record is written and therefore
        the only place a disclosure can surface. It is read once, because ``readouterr``
        drains the buffer.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command exits non-zero, if a withheld field's decoded value appears anywhere in
        the output, if a withheld field renders anything but the fixed literal, if the postal
        code renders as anything but a keyed tag of the declared width, or if an allowlisted
        field stops rendering in clear.
    """
    # WHY (Assumptions): the account master carries no field any factory in layouts.py had
    #   marked sensitive, so before the corpus disclosure allowlist existed this command printed
    #   every one of its thirteen fields in cleartext -- five money fields among them. The
    #   assertion is therefore made on the ABSENCE of the decoded cleartext rather than on the
    #   presence of a tag: a rendering that reverted to disclosure would still contain a tag for
    #   some other field, so only the absence distinguishes the two behaviours.
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT)) == EXIT_OK
    printed = capsys.readouterr().out
    fields = json.loads(printed)

    # Assumptions: each expected cleartext is the value the codecs really produce for
    #   record one of the shipped extract, captured by running the command against it. Naming
    #   the actual values is what makes this a disclosure test rather than a shape test -- a
    #   check that merely looked for a "<" would pass on a rendering that printed the balance
    #   in one field and a tag in another.
    withheld = {
        "ACCT-CURR-BAL": "194.00",
        "ACCT-CREDIT-LIMIT": "5000.00",
        "ACCT-CASH-CREDIT-LIMIT": "500.00",
        "ACCT-CURR-CYC-CREDIT": "0.00",
        "ACCT-CURR-CYC-DEBIT": "0.00",
    }
    for name, cleartext in withheld.items():
        assert cleartext not in printed, f"{name}'s decoded value must not be printed"
        # Assumptions: a money field renders the fixed literal and NOT a keyed tag, and
        #   the exact literal is asserted rather than a leading "<". A decoded number's text is
        #   a different length from the declared field width, so the only chunk available to a
        #   tag on this path would be a constant -- which would yield a tag that is equal for
        #   two different balances while looking value-derived. Asserting the literal is what
        #   pins the command to the honest rendering instead of the plausible one.
        assert fields[name] == "<withheld>", f"{name} must render the withheld literal"

    # Trade-offs: the postal code is asserted alongside the money because it is the
    #   member of this record whose classification is least obvious -- it is neither money nor
    #   an identifier -- and it is withheld deliberately, on the ground that ten characters of
    #   postal code narrow a household where the two-character state code does not.
    # Assumptions: it renders a keyed TAG rather than the literal, because it is a
    #   character field whose decoded rendering is its own ten bytes and therefore already the
    #   declared width. Both renderings are asserted in one test so that the distinction between
    #   them is pinned as a consequence of the field's width and not of a hand-kept list.
    postal = fields["ACCT-ADDR-ZIP"]
    assert postal != "<withheld>"
    assert postal.startswith("<") and postal.endswith(">")
    assert len(postal) == layouts.layout("ACCOUNT").field("ACCT-ADDR-ZIP").length

    # Assumptions: the three lifecycle dates, the status code and the key stay in
    #   cleartext, and that half is asserted in the same test for the reason the codec
    #   diagnostics use: "the balance was withheld" is only evidence of a POLICY if something
    #   the policy admits is still disclosed. A command that had begun redacting everything
    #   would otherwise pass every assertion above while having become useless.
    # Refactoring Rationale: the key is asserted PRESENT and NOT in clear, where this case
    #   first asserted `ACCT-ID == "00000000001"`. The account master is closed under the
    #   fail-closed master disclosure policy and `_ACCOUNT_MASTER_DISCLOSABLE_FIELDS` does not
    #   name `ACCT-ID`, so the field is sensitive and this command renders it as a keyed tag. The
    #   assertion is moved to the policy rather than the policy relaxed to the assertion, which is
    #   the same direction `test_decode_record_decodes_a_shipped_extract` above records for the
    #   identifier and the balance. The exact tag text is not asserted because the tag is keyed and
    #   this case supplies no key.
    assert "ACCT-ID" in fields
    assert fields["ACCT-ID"] != "00000000001"

    # Assumptions: the status and the expiry stay in clear and are asserted verbatim, and
    #   they are what keep this a disclosure test rather than a redact-everything test. Both are
    #   named by the master allowlist, so a change that began withholding them would fail here.
    assert fields["ACCT-ACTIVE-STATUS"] == "Y"
    assert fields["ACCT-OPEN-DATE"] == "2014-11-20"
    assert fields["ACCT-EXPIRAION-DATE"] == "2025-05-20"
    assert fields["ACCT-REISSUE-DATE"] == "2025-05-20"


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
    # WHY : Refactoring Rationale: this asserted only that the password rendering CONTAINED a
    #   '<', which was satisfied by the keyed HMAC tag this command used to print for it -- a tag
    #   derived from the plaintext, and therefore a representation of it. The exact marker is now
    #   pinned, because "contains a bracket" is true of both the correct rendering and the defect
    #   it replaced, and a test that cannot tell them apart could not have caught the defect.
    # Assumptions: the marker is asserted as a LITERAL rather than read from cli's own constant.
    #   What is under test is the observable output an operator sees, so a change to the literal
    #   is a change to that contract and should fail here rather than pass because both sides
    #   moved together.
    assert fields["SEC-USR-PWD"] == "<withheld>"


def test_decode_record_never_decodes_a_suppressed_field(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Decode every field of the security record except the stored password, which is skipped.

    Parameters
    ----------
    capsys : pytest.CaptureFixture
        Captures the rendered field map.
    monkeypatch : pytest.MonkeyPatch
        Replaces the codec's per-field decode with a recording wrapper.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the suppressed span is converted to characters, or if withholding it costs the
        output the field's line.
    """
    decoded: list[str] = []
    original = ebcdic_codec._decode_one_field

    def recording(record: bytes, field: layouts.FieldSpec, code_page: str) -> object:
        """Record the field name, then decode exactly as the codec would."""
        decoded.append(field.name)
        return original(record, field, code_page)

    # WHY : the observation is made at the CODEC rather than on the output, because the output
    #   cannot distinguish "never decoded" from "decoded and then replaced". That distinction is
    #   the whole of this property: a cleartext password that exists in a local for one statement
    #   is in this process's memory and in the traceback of anything raised while it is live.
    monkeypatch.setattr(ebcdic_codec, "_decode_one_field", recording)
    assert cli.main(_decode_arguments(_USER_EXTRACT, dataset="SECUSER")) == EXIT_OK
    fields = json.loads(capsys.readouterr().out)

    suppressed = next(iter(usrsec.SUPPRESSED_FIELD_NAMES))
    assert suppressed not in decoded, f"{suppressed} must never be decoded"
    assert decoded, "the recording wrapper saw no field at all, so it proves nothing"
    # WHY : the field must still APPEAR, so an operator verifying that a delivery decodes at the
    #   declared geometry can still count offsets across the whole record. Dropping the line would
    #   withhold the value and the evidence together.
    assert list(fields) == [field.name for field in layouts.layout("SECUSER").fields]
    assert fields[suppressed] == "<withheld>"


def test_decode_record_withholds_a_suppressed_field_independently_of_the_mask_key(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Render the suppressed field identically under two keys, while the redacted ones differ.

    Parameters
    ----------
    capsys : pytest.CaptureFixture
        Captures each rendered field map.
    monkeypatch : pytest.MonkeyPatch
        Supplies each masking key through the environment.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the suppressed rendering varies with the key -- which would mean it is derived from
        the value -- or if the merely-sensitive fields stop being keyed.
    """
    renderings: list[dict[str, str]] = []
    for material in (bytes(range(32)), bytes(range(32, 64))):
        # WHY : the key is supplied through the environment rather than by reloading the layouts
        #   module, because the masker resolves the key at CALL time. Reloading a shared module
        #   inside a test replaces the class and enum objects every other test holds, which was
        #   measured to break twenty-six unrelated tests in this suite.
        monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, base64.b64encode(material).decode())
        assert cli.main(_decode_arguments(_USER_EXTRACT, dataset="SECUSER")) == EXIT_OK
        renderings.append(json.loads(capsys.readouterr().out))

    first, second = renderings
    suppressed = next(iter(usrsec.SUPPRESSED_FIELD_NAMES))
    # WHY : key-independence is the operational test for "not derived from the value". A keyed tag
    #   changes when the key changes; a fixed marker cannot. The name field is asserted in the
    #   opposite direction in the same test, so a change that flattened EVERY redaction to a
    #   constant -- losing the ability to tell two records apart field by field -- fails here too.
    assert first[suppressed] == second[suppressed] == "<withheld>"
    assert first["SEC-USR-FNAME"] != second["SEC-USR-FNAME"]


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


def _bind_database(
    monkeypatch: pytest.MonkeyPatch,
    database: FakeAuroraDatabase,
    settings: AuroraConnectionSettings,
) -> list[str]:
    """Point the command line's database seam at the recording double and record the schemas.

    Purpose
    -------
    Let a command be driven end to end -- argument parsing, reader selection, target resolution
    and the database call -- without a cluster, while capturing which schema each command asked
    for. That capture is the assertion that matters for a newly-added target: the command must
    authenticate as the schema that OWNS the table, and a target declared under the wrong schema
    would still load cleanly against a double that ignored the question.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher, scoped to the calling test.
    database : FakeAuroraDatabase
        The recording double every patched connect call returns a connection from.
    settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host, returned for every schema.

    Returns
    -------
    list[str]
        A list the patched resolver appends each requested schema name to, in call order.

    Raises
    ------
    None
    """
    requested: list[str] = []

    def _resolve(schema: str) -> AuroraConnectionSettings:
        """Record the schema a command asked for and return the synthetic settings.

        Parameters
        ----------
        schema : str
            The owning schema the command resolved from its load target.

        Returns
        -------
        AuroraConnectionSettings
            The same synthetic settings for every schema.

        Raises
        ------
        None
        """
        requested.append(schema)
        return settings

    def _connect(resolved: AuroraConnectionSettings) -> object:
        """Open a recording connection from resolved settings.

        Parameters
        ----------
        resolved : AuroraConnectionSettings
            The settings the command resolved.

        Returns
        -------
        object
            A recording connection from the double.

        Raises
        ------
        ConfigurationError
            Propagated from the double if the parameters would not have verified the server
            certificate.
        """
        # WHY : Assumptions: the parameters come from ``as_connection_params`` rather than being
        #   hand-built, so the double's TLS keyword checks are exercised on the same translation
        #   the production path performs. Hand-building the mapping is precisely how the
        #   ``database``-versus-``dbname`` rename gets lost.
        return database.connect(**resolved.as_connection_params())

    monkeypatch.setattr(cli, "resolve_aurora_settings", _resolve)
    monkeypatch.setattr(cli, "connect", _connect)
    return requested


def test_load_dataset_loads_the_category_balance_seed_into_the_ledger_schema(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Load every record of the shipped category-balance seed through one committed COPY.

    Purpose
    -------
    Prove the delivered path end to end for the one dataset the verification SQL declared and
    the loader could not fill: the real reader over the real seed, the real target, and the real
    COPY statement. A target asserted only against its own declaration would not have caught the
    reader and the target disagreeing about a field name, which is the failure that surfaces on
    the first record rather than at declaration time.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command does not succeed, asks for a schema other than the owning one, issues a
        statement other than the target's own COPY, loads a record count other than the seed's,
        or fails to commit exactly once.
    """
    requested = _bind_database(monkeypatch, fake_aurora, aurora_settings)
    exit_code = cli.main(
        [
            "load-dataset",
            "--dataset",
            "TCATBAL",
            "--source",
            str(_CATEGORY_BALANCE_SEED),
            "--encoding",
            "ascii",
        ]
    )
    assert exit_code == EXIT_OK
    # WHY : Assumptions: `ledger` and not `account`, even though every record leads with an
    #   account identifier. The table belongs to the context that owns the posting unit of work,
    #   so the load must authenticate as that context's role -- and asking for `account` would
    #   reach a role with no grant on the table, which fails at run time rather than here.
    assert requested == ["ledger"]
    assert fake_aurora.copy_statements == [target_for("TCATBAL").copy_statement()]
    # WHY : Assumptions: the expected row count is DERIVED from the seed rather than written as
    #   50, so the assertion cannot agree with a reader that stopped early on a seed which later
    #   grew. It counts LINES and not bytes-over-record-length: this seed is the line-oriented
    #   ASCII form, it is one of the three that terminate with CRLF, and its final line carries
    #   no terminator at all -- so a byte-length division is wrong by both the terminator width
    #   and that last record. The fixed-length division is the right derivation for the EBCDIC
    #   twin, whose 2500 bytes over the 50-byte record is what the verification SQL states.
    expected_rows = sum(
        1 for line in _CATEGORY_BALANCE_SEED.read_bytes().splitlines() if line.strip()
    )
    fixed_length_twin = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.TCATBALF.PS"
    assert expected_rows == fixed_length_twin.stat().st_size // layouts.reclen_of("TCATBAL")
    assert len(fake_aurora.copied_rows) == expected_rows
    assert fake_aurora.commits == 1
    assert fake_aurora.rollbacks == 0
    # WHY : the money value is asserted to arrive as an exact Decimal at the column's own scale.
    #   Every record of this seed carries a zoned zero with the sign overpunched, so a field
    #   read as characters would hand the driver the overpunch character itself and the load
    #   would fail on a NUMERIC column -- or, worse, succeed against a permissive one.
    balances = {row[-1] for _, row in fake_aurora.copied_rows}
    assert all(isinstance(balance, Decimal) for balance in balances)
    assert all(balance.as_tuple().exponent == -2 for balance in balances)


@pytest.mark.parametrize("command", ["verify-row-counts", "verify-checksum", "verify-money-parity"])
def test_every_verification_command_accepts_the_category_balance_dataset(
    command: str,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Reach the database for the category-balance dataset from all three verification passes.

    Purpose
    -------
    The three passes each resolve a load target before they do anything else, so a dataset with
    no target was refused by all three even though the whole-schema SQL registered it. This
    asserts the refusal is gone from every one of them rather than from the load command alone,
    which is what the finding required: row counts, checksum and money parity all reach the
    table.

    Parameters
    ----------
    command : str
        One of the three verification subcommands.
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a pass resolved no schema, which is what a refused target produces, or resolved one
        other than the owning schema.
    """
    requested = _bind_database(monkeypatch, fake_aurora, aurora_settings)
    # WHY : Assumptions: an aggregate is arranged to return ONE row holding zero, because a real
    #   database always returns a row for `COUNT(*)` and for a coalesced `SUM` even over an empty
    #   table -- and the passes correctly refuse a connection that returns none, calling it "not
    #   behaving as a database connection". Arranging the empty-table answer is therefore what
    #   makes the double a database rather than what makes the assertion pass: the comparison
    #   still reports a difference against the seed's records, which is the honest outcome for a
    #   table nothing loaded into.
    fake_aurora.arrange_rows("COUNT(*)", [(0,)])
    fake_aurora.arrange_rows("SUM(", [(Decimal("0.00"),)])
    exit_code = cli.main(
        [
            command,
            "--dataset",
            "TCATBAL",
            "--source",
            str(_CATEGORY_BALANCE_SEED),
            "--encoding",
            "ascii",
        ]
    )
    # WHY : Trade-offs: the SCHEMA REQUEST is asserted and the exit code deliberately is not.
    #   The double answers every query with the empty result set the test arranged, so a pass
    #   comparing 50 source records against 0 target rows correctly reports a difference and
    #   exits FAILED. That outcome is right, and asserting it would be asserting the double's
    #   arrangement. What the finding is about is whether the pass gets far enough to ask -- a
    #   refused target returns before any connection is opened, so an empty list is the failure.
    assert requested == ["ledger"], (
        f"{command} resolved no owning schema for TCATBAL, so the target was refused before any"
        " comparison could be attempted"
    )
    assert exit_code in {EXIT_OK, EXIT_FAILED}
