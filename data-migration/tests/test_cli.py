"""Verify the command-line entry point's accepted surface, dispatch and exit codes."""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path
from typing import Any

import pytest

from carddemo_migration import cli
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
)

# Assumptions: these five are contracted in README.md section 5.2 but their backing
#   modules -- readers/, loaders/aurora.py and verify/ -- are absent from this
#   distribution, so the parser must NOT advertise them. The test is written as an
#   explicit denial because an unimplemented command that reaches `--help` is how an
#   orchestrator comes to be wired to a state that cannot run.
_UNREGISTERED_SUBCOMMANDS = (
    "load-dataset",
    "verify-row-counts",
    "verify-checksums",
    "verify-money-totals",
    "verify-all",
)

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


def _stage_arguments(source: Path, dataset: str = "TRAN") -> list[str]:
    """Build a complete stage-dataset argument list.

    Purpose
    -------
    Keep the required-argument set in one place, so a test that varies one argument does
    not restate the other five.

    Parameters
    ----------
    source : Path
        Value for ``--source``.
    dataset : str, optional
        Value for ``--dataset``; defaults to a registered dataset.

    Returns
    -------
    list of str
        The argument list, excluding the program name.

    Raises
    ------
    None
    """
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


def test_stage_dataset_reports_an_unreadable_source(tmp_path: Path) -> None:
    """Report a failed step, not a usage error, when the extract cannot be read."""
    assert cli.main(_stage_arguments(tmp_path / "absent.dat")) == EXIT_FAILED


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
    """Forward each parsed argument to the staging call unchanged."""
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

    def _capture(**kwargs: Any) -> cli.StagedGeneration:
        """Record the staging call and answer with a fixed successful generation."""
        recorded.update(kwargs)
        return cli.StagedGeneration(
            key="ledger/TRAN/dt=2022-07-18/gen=0001/DALYTRAN.PS",
            deleted_generation_prefixes=(),
        )

    monkeypatch.setattr(cli, "stage_generation", _capture)
    assert cli.main(_stage_arguments(source)) == EXIT_OK

    assert recorded["client"] is not None
    assert recorded["settings"] is settings
    assert recorded["dataset"] == "TRAN"
    assert recorded["domain"] == "ledger"
    assert recorded["business_date"] == date(2022, 7, 18)
    assert recorded["generation"] == 1
    assert recorded["payload"] == payload
    assert recorded["retention_count"] == cli.DEFAULT_RETENTION_COUNT
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

    def _capture(**kwargs: Any) -> cli.StagedGeneration:
        """Record the staging call and answer with a generation that deleted one prefix."""
        recorded.update(kwargs)
        return cli.StagedGeneration(key="k", deleted_generation_prefixes=("old/",))

    monkeypatch.setattr(cli, "stage_generation", _capture)
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

    monkeypatch.setattr(cli, "stage_generation", _refuse)
    assert cli.main(_stage_arguments(source)) == EXIT_USAGE


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
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT)) == EXIT_OK
    fields = json.loads(capsys.readouterr().out)
    assert fields["ACCT-ID"] == "00000000001"
    assert fields["ACCT-CURR-BAL"] == "194.00"
    assert fields["ACCT-OPEN-DATE"] == "2014-11-20"


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
