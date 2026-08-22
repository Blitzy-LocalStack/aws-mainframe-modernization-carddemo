"""Verify the command-line entry point's accepted surface, dispatch and exit codes."""

from __future__ import annotations

import argparse
import base64
import errno
import hashlib
import inspect
import io
import json
import logging
import re
from collections.abc import Iterator, Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import date
from decimal import Decimal
from pathlib import Path
from typing import TYPE_CHECKING, Any, Final

import pytest
from conftest import FakeServiceError

from carddemo_migration import cli, seed_datasets
from carddemo_migration.config import (
    AuroraConnectionSettings,
    ConfigurationError,
    DatasetStagingSettings,
    role_for_schema,
)
from carddemo_migration.copybook import ebcdic_codec, layouts, packed, zoned
from carddemo_migration.credentials import EXIT_FAILED, EXIT_FATAL, EXIT_OK, EXIT_USAGE
from carddemo_migration.loaders import aurora, s3_stage
from carddemo_migration.loaders.aurora import (
    LoadContext,
    prepare_record,
    target_for,
    target_names,
)
from carddemo_migration.loaders.protected_columns import (
    CardVerificationValueCipher,
    CustomerIdentifierCipher,
    DataKey,
)
from carddemo_migration.readers import (
    card,
    dataset_reader,
    ships_committed_extract,
    tcatbal,
    usrsec,
)
from carddemo_migration.readers.factory import RecordReader
from carddemo_migration.verify import money_parity, row_counts, session
from carddemo_migration.verify.checksum import SEALED_ENVELOPE_MIN_BYTES

# WHY : Refactoring Rationale: the inventory arrives through `money_columns()` where these cases
#   used to import a module-level `MONEY_COLUMNS` constant. The constant was withdrawn from the
#   pass so that a defect in its derivation could not fail every unrelated subcommand at import;
#   the mapping it published is unchanged, so the assertions below read the accessor and say the
#   same thing about the same nine columns.
from carddemo_migration.verify.money_parity import (
    SourceExtract,
    money_columns,
    read_source_totals,
)
from carddemo_migration.verify.money_parity import reporting_role as money_reporting_role
from carddemo_migration.verify.row_counts import (
    NO_DATASET_LABEL,
    SEED_DATASET_BASELINES,
    UNSEEDED_LAYOUT_NAME,
    reporting_role,
)

if TYPE_CHECKING:
    from collections.abc import Mapping

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
# Refactoring Rationale: ``reconcile-sequences`` was added here when the transaction-identifier
#   allocator's cutover ordering hazard was closed. Its position is REGISTRATION order, between
#   ``apply-credentials`` and ``load-dataset``, which is also the order a cutover runs the three
#   in -- credentials, then the allocator's own precondition step declared, then the loads -- and
#   the tuple is compared in order precisely so a reordering that changed that reading fails.
# Refactoring Rationale: ``verify-money-total-report`` was added LAST, which is its registration
#   order, when the whole-migration money pass was wired to a command. ``verify_money_totals`` is
#   the only path that compares strictly-negative ROW COUNTS as well as totals, and it had no
#   caller at all -- so a compensating sign swap, which leaves every total unchanged, passed the
#   registered ``verify-money-parity`` and moved only a count nothing read. Its position after
#   ``verify-row-count-report`` pairs the two whole-migration reports at the end of the surface,
#   which is also the order the three passes run in.
# WHY : Refactoring Rationale: ``verify-all`` appeared TWICE in this tuple and one entry is
#   withdrawn. A tuple admits duplicates, so nothing rejected the literal, and the failure it
#   produced named an ORDER mismatch at the first differing index rather than a duplicate -- which
#   reads as a reordering defect and sends a reader to the wrong place. The surviving entry is LAST,
#   because this tuple is compared in registration order and ``cli.build_parser`` registers
#   ``verify-all`` after both whole-migration reports. The withdrawn entry sat before
#   ``verify-row-count-report``, which was the registration order of a SECOND ``verify-all``
#   registration that no longer exists: two registrations of one subcommand made ``argparse`` raise
#   ``conflicting subparser`` while building the parser, so every subcommand was unreachable, and
#   the earlier of the two was withdrawn there for the reasons recorded at its declaration site.
_IMPLEMENTED_SUBCOMMANDS = (
    "list-datasets",
    "decode-record",
    "stage-dataset",
    "refresh-dataset",
    "apply-credentials",
    "reconcile-sequences",
    "refresh-card-identity",
    "load-dataset",
    "verify-row-counts",
    "verify-checksum",
    "verify-money-parity",
    "verify-row-count-report",
    "verify-money-total-report",
    "verify-all",
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
# WHY : ⚠️ Refactoring Rationale: this roster held "verify-all" and is now EMPTY, because that verb
#   was contracted by README section 5.2 and has since been registered and backed. It is kept as an
#   empty tuple rather than deleted so that the next verb contracted ahead of its implementation has
#   somewhere declared to live, and so the case asserting the roster is empty keeps a subject.
_UNREGISTERED_SUBCOMMANDS: tuple[str, ...] = ()

# Assumptions: the decode tests read the SHIPPED extracts rather than fixtures built here,
#   because the command exists to prove a real delivery decodes at its declared geometry and a
#   fixture this file wrote would only prove the file agreed with itself. The paths are
#   resolved from this module's own location so the tests run from any working directory.
_EBCDIC_DIRECTORY = Path(__file__).resolve().parents[2] / "app" / "data" / "EBCDIC"

# Assumptions: the distribution root is the directory holding both `src` and `sql`, resolved from
#   this file's own location so the tests run from any working directory. It is needed because this
#   suite exercises the INSTALLED package, whose location carries no `sql` tree.
_DISTRIBUTION_ROOT = Path(__file__).resolve().parents[1]
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
_CARD_XREF_SEED = _ASCII_DIRECTORY / "cardxref.txt"

# Assumptions: the two cross-reference columns the schema declares `BIGINT`, named here so the
#   arranged read-back below is built from the COLUMN's type rather than from a guess about which
#   fields are numeric. `XREF-CARD-NUM` is deliberately absent: it is a `CHAR(16)` column, so a
#   driver returns it as characters and it needs no conversion.
_CARD_XREF_INTEGER_COLUMNS = frozenset({"customer_id", "account_id"})

# Assumptions: the record count of the shipped ASCII cross-reference extract, stated so the
#   assertion below cannot be satisfied by a fixture that shrank. Measured rather than assumed:
#   the file holds 36 data bytes per line where the copybook declares 50, so it is read line-wise
#   and its record count is not derivable from its byte length.
_CARD_XREF_SEED_RECORDS = 50

# Assumptions: the transaction-type seed is read by exactly one test below, for the one property
#   that no other shipped dataset can demonstrate: TRANTYPE has a declared load target AND its
#   layout declares no signed field at all, so it is the only extract through which the money
#   command's "this dataset holds no money" branch is reachable at all.
_TRANSACTION_TYPE_SEED = _ASCII_DIRECTORY / "trantype.txt"

# Assumptions: the committed category-balance seed holds fifty records and every one of their
#   balances is zero. Both figures are spelled here rather than recomputed by the assertions that
#   use them, because a test that derives its expectation from the code under test proves only
#   self-consistency. app/data is reference-only and immutable, so neither can go stale.
_CATEGORY_BALANCE_RECORDS = 50
_CATEGORY_BALANCE_TOTAL = Decimal("0.00")


# WHY : Refactoring Rationale: an `_AbsentObject` exception class stood here and is WITHDRAWN. It
#   published `response["Error"]["Code"] == "404"` so a client double could refuse an existence
#   probe without constructing a botocore exception. `conftest.FakeServiceError` already does
#   exactly that, is shared by every double in this suite, and carries the status alongside the
#   code -- so the local class was a second spelling of one contract, free to drift from the one
#   the other doubles honour. Every probe refusal now raises `FakeServiceError("404", 404, ...)`.


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


# WHY : Refactoring Rationale: this double is the FULLER of two that were authored for the same
#   seam, and the leaner one it replaces is worth recording. The leaner version answered the
#   staging path's existence probe by always raising "absent", which is enough for a test that
#   only asserts how the first object is written -- and not enough for any test that seeds an
#   inbound export to be fetched, or that stages the same bytes twice to assert the retry rewrites
#   one key instead of consuming a second generation. This version answers the probe from what it
#   has actually STORED or PUT, so both of those become observable without a test hand-building the
#   state a first successful stage would have left.
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
    heads : list of str
        Every key the staging path probed for an existing generation, in order.
    gets : list of str
        Every key the staging path read a body from, in order.
    stored : dict
        Objects seeded into the double before the run, keyed by ``(bucket, key)``. Each value is
        the payload bytes and the metadata the object carries.
    """

    def __init__(self) -> None:
        """Create an empty request log."""
        self.puts: list[dict[str, Any]] = []
        self.heads: list[str] = []
        self.gets: list[str] = []
        self.stored: dict[tuple[str, str], tuple[bytes, dict[str, str]]] = {}

    def seed_object(
        self, bucket: str, key: str, payload: bytes, *, sha256: str | None = None
    ) -> None:
        """Place an object in the double as though it had been delivered by an export.

        Parameters
        ----------
        bucket : str
            Bucket the object sits in.
        key : str
            Key the object sits at.
        payload : bytes
            The object's bytes.
        sha256 : str | None, optional
            Digest to record as this package's own staging metadata. ``None`` seeds an object
            carrying NO recorded digest, which is what an inbound mainframe export looks like.

        Returns
        -------
        None
            Mutates :attr:`stored`.

        Raises
        ------
        None
        """
        # WHY : Assumptions: the digest is OPTIONAL and defaults to absent, because the two object
        #   populations differ in exactly that respect: an inbound export carries no metadata this
        #   package wrote, while a staged generation carries the digest the staging step recorded.
        #   A double that always seeded a digest could not exercise the unverifiable-digest branch,
        #   which is the branch the inbound inbox actually takes.
        metadata = {"carddemo-sha256": sha256} if sha256 is not None else {}
        self.stored[(bucket, key)] = (payload, metadata)

    def get_object(self, **kwargs: Any) -> dict[str, Any]:
        """Return a seeded object's body, as the SDK's streaming shape.

        Parameters
        ----------
        **kwargs : Any
            The request; ``Bucket`` and ``Key`` are read.

        Returns
        -------
        dict
            A response carrying ``Body`` as bytes.

        Raises
        ------
        FakeServiceError
            With code ``NoSuchKey`` when nothing is seeded under the key.
        """
        key = kwargs["Key"]
        self.gets.append(key)
        entry = self.stored.get((kwargs["Bucket"], key))
        if entry is None:
            raise FakeServiceError("NoSuchKey", 404, f"no object is stored at {key!r}")
        # WHY : Trade-offs: the body is returned as plain BYTES rather than as a file-like object.
        #   The fetch path accepts both by design, and bytes is the shape that keeps this double
        #   from re-implementing a stream to exercise a check about digests and lengths.
        return {"Body": entry[0]}

    def head_object(self, **kwargs: Any) -> dict[str, Any]:
        """Answer the existence probe from what this double has actually stored.

        Parameters
        ----------
        **kwargs : Any
            The request the staging path issued; ``Bucket`` and ``Key`` are read.

        Returns
        -------
        dict
            The metadata and content length recorded by the matching ``put_object``.

        Raises
        ------
        FakeServiceError
            With code ``404`` when no object is stored under the key, which is what the real
            service reports for a HEAD of an absent key.
        """
        key = kwargs["Key"]
        self.heads.append(key)
        seeded = self.stored.get((kwargs["Bucket"], key))
        if seeded is not None:
            return {"Metadata": dict(seeded[1]), "ContentLength": len(seeded[0])}
        # WHY : Assumptions: the answer is derived from this double's own put log rather than
        #   from a separately arranged fixture. Deriving it is what makes the retry semantics
        #   observable in one test -- stage, then stage again -- instead of requiring a test to
        #   hand-build the state a first successful stage would have left, which is exactly the
        #   arrangement that drifts away from what the code really writes.
        for request in reversed(self.puts):
            if request.get("Key") != key:
                continue
            body = request.get("Body")
            return {
                "Metadata": dict(request.get("Metadata") or {}),
                "ContentLength": len(body) if isinstance(body, bytes) else 0,
            }
        # WHY : Assumptions: the absent case reports ``404`` rather than ``NoSuchKey``. A HEAD
        #   response carries no body, so there is no error document for the SDK to read a code
        #   out of and botocore synthesises the code from the HTTP status -- which is why the
        #   staging module admits both spellings and why the double reproduces the HEAD one.
        raise FakeServiceError("404", 404, f"no object is stored at {key!r}")

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


def _subparser(name: str) -> argparse.ArgumentParser:
    """Reach one subcommand's own parser, so its accepted options can be read from the shipped code.

    Purpose
    -------
    Let an assertion compare a message, a runbook or a container override against the options a
    subcommand ACTUALLY accepts, rather than against a second list of them written in a test. The
    two drift, and the direction they drift in is the dangerous one: the copy keeps agreeing with
    the assertion after the parser has stopped agreeing with either.

    Parameters
    ----------
    name : str
        A registered subcommand name.

    Returns
    -------
    argparse.ArgumentParser
        That subcommand's parser.

    Raises
    ------
    AssertionError
        If the name is not registered, because an assertion built on an absent subparser would
        otherwise fail for a reason that reads as unrelated to what it was checking.
    """
    actions = [
        action
        for action in cli.build_parser()._actions
        if hasattr(action, "choices") and isinstance(action.choices, dict)
    ]
    assert actions, "the parser registers no subcommands"
    choices = actions[0].choices
    assert name in choices, f"{name!r} is not a registered subcommand; registered: {tuple(choices)}"
    return choices[name]


def test_every_implemented_subcommand_is_registered() -> None:
    """Register exactly the subcommands whose backing modules are present."""
    assert _registered_subcommands() == _IMPLEMENTED_SUBCOMMANDS


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


# WHY : Assumptions: a REPRESENTATIVE five of the seven unusable forms are named here, and the
#   exhaustive roster with its per-form reasoning lives in `tests/test_mask_key_material.py`, which
#   owns the resolver's contract. What these cases add is the CLASSIFICATION the command line puts
#   on a refusal and the point in the invocation at which it happens -- neither of which that
#   module can assert, because it never runs a subcommand. Restating all seven here would be a
#   second roster to keep in step for no additional property.
_UNUSABLE_MASK_KEYS: tuple[str, ...] = (
    "",
    "   ",
    "notbase64!!",
    base64.b64encode(bytes(range(16))).decode(),
    base64.b64encode(b"\xff" * 48).decode(),
)


@pytest.mark.parametrize("supplied", _UNUSABLE_MASK_KEYS, ids=repr)
def test_unusable_mask_key_material_is_the_environment_tier_and_refused_before_dispatch(
    supplied: str,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Refuse an unusable masking key as an environment fault, before any handler runs.

    Parameters
    ----------
    supplied : str
        The value configured for the masking-key variable.
    monkeypatch : pytest.MonkeyPatch
        Configures the variable and installs a handler that must never be reached.
    caplog : pytest.LogCaptureFixture
        Captures the refusal so it can be asserted logged and value-free.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal escapes as a traceback, is classified as a step failure, or arrives after a
        handler has begun work.
    """
    # WHY : Refactoring Rationale: the key used to be resolved lazily by the first masked value, so
    #   an unusable one reached `main` as a raw `LayoutError` from inside `_redacted` -- status 1,
    #   outside the documented classification -- and, worse, could arrive from
    #   `zoned._render_content` while composing a decode refusal, presenting an environment fault
    #   as a malformed extract.
    # WHY : Assumptions: the status is asserted to be FATAL (16) and not FAILED (8). 16 is
    #   "the environment could not be resolved" and 8 is "the step ran and did not succeed"; no
    #   step has run here, and filing this as 8 would invite the orchestrator to retry a fault no
    #   retry can clear.
    reached: list[str] = []

    def _handler(_arguments: argparse.Namespace) -> int:
        """Record that a handler was entered, which on this path it must not be.

        Parameters
        ----------
        _arguments : argparse.Namespace
            The parsed arguments, unused.

        Returns
        -------
        int
            Never returned on this path; the recording is the assertion.
        """
        reached.append("dispatched")
        return EXIT_OK

    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, supplied)
    monkeypatch.setattr(cli, "_list_datasets", _handler)
    with caplog.at_level(logging.ERROR, logger="carddemo_migration.cli"):
        status = cli.main(["list-datasets"])
    assert status == EXIT_FATAL
    # Assumptions: the subject is `list-datasets` deliberately -- the ONE subcommand that reads no
    #   configuration, opens no client and masks nothing. It therefore has no reason of its own to
    #   fail, so a refusal here can only have come from the boundary check, and the handler patch
    #   proves the check ran BEFORE dispatch rather than the command having failed on its own.
    assert reached == [], "the masking key must be refused before any handler is entered"
    logged = "\n".join(record.getMessage() for record in caplog.records)
    assert layouts.ENV_MASK_HMAC_KEY in logged, "the refusal must name the variable it is about"
    if supplied.strip():
        assert supplied not in logged, "the refusal must not echo candidate key material"


def test_a_conforming_mask_key_leaves_every_subcommand_reachable(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Dispatch normally when the masking key is conforming, and when it is unset.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Configures and removes the masking-key variable.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the boundary check refuses a supported configuration, which would make the check itself
        the outage it was added to prevent.
    """
    # WHY : Assumptions: this control is as important as the refusals above. A check placed before
    #   dispatch fails EVERY subcommand when it is wrong, so the two supported configurations --
    #   conforming material and no material at all -- are asserted to still reach a handler.
    monkeypatch.delenv(layouts.ENV_MASK_HMAC_KEY, raising=False)
    assert cli.main(["list-datasets"]) == EXIT_OK
    monkeypatch.setenv(layouts.ENV_MASK_HMAC_KEY, base64.b64encode(bytes(range(32, 64))).decode())
    assert cli.main(["list-datasets"]) == EXIT_OK


def test_cancellation_is_classified_rather_than_raised(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Report a documented status when an operator interrupts a running subcommand.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Installs a handler that raises the interrupt an operator's SIGINT produces.
    caplog : pytest.LogCaptureFixture
        Captures the cancellation sentence.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the interrupt escapes, or the status falls outside the published classification.
    """

    # WHY : Refactoring Rationale: an interrupt used to propagate, so a cancelled command produced
    #   the interpreter's own `KeyboardInterrupt` traceback and status 130 -- a number no runbook,
    #   no README section and no `Choice` state in the batch state machine documents. Rollback and
    #   cleanup were already correct; what was missing was a classified status and one sentence
    #   saying so.
    # WHY : Assumptions: the interrupt is raised from the HANDLER rather than delivered as a real
    #   signal. A signal delivered to the test process would interrupt the runner rather than the
    #   subcommand, and what is under test is the boundary's handling of the exception -- which is
    #   the same object either way.
    def _interrupted(_arguments: argparse.Namespace) -> int:
        """Raise the interrupt an operator's SIGINT delivers into a running handler.

        Parameters
        ----------
        _arguments : argparse.Namespace
            The parsed arguments, unused.

        Returns
        -------
        int
            Never returned; the interrupt is the point.

        Raises
        ------
        KeyboardInterrupt
            Always.
        """
        raise KeyboardInterrupt

    monkeypatch.setattr(cli, "_list_datasets", _interrupted)
    with caplog.at_level(logging.ERROR, logger="carddemo_migration.cli"):
        status = cli.main(["list-datasets"])
    # Assumptions: the status is asserted to be INSIDE the published set as well as equal to the
    #   failed tier, so a later change that invents a cancellation tier fails here rather than
    #   publishing a fifth status the state machine has no branch for.
    assert status == EXIT_FAILED
    assert status in {EXIT_OK, EXIT_USAGE, EXIT_FAILED, EXIT_FATAL}
    logged = "\n".join(record.getMessage() for record in caplog.records)
    # Assumptions: the sentence must name the SUBCOMMAND, because an operator who interrupted one
    #   of several concurrent container steps needs to know which one stopped.
    assert "list-datasets" in logged
    assert "cancelled" in logged


def _stage_arguments(dataset: str = "transactions") -> list[str]:
    """Build a stage-dataset argument list holding only what the subcommand still accepts.

    Purpose
    -------
    Keep one argument set in one place for the tests that assert on forwarding and on exit tiers.

    Parameters
    ----------
    dataset : str, optional
        Value for ``--dataset``; defaults to a registered seed-dataset token. Either accepted
        spelling resolves, so a layout name is equally valid here.

    Returns
    -------
    list of str
        The argument list, excluding the program name.

    Raises
    ------
    None
    """
    # WHY : Refactoring Rationale: this helper no longer takes a source path, and the reason is
    #   that `stage-dataset` no longer takes `--source`. Nor does it pass `--generation` or
    #   `--domain`, which were withdrawn with it: a caller-named source has no root it can be
    #   contained by, a caller-named generation makes the retention sweep scratch a generation that
    #   is not the oldest, and a caller-named context writes one bounded context's master under
    #   another's prefix. Every coordinate the command needs now comes from the seed registry and
    #   from the durable generation reservation, so the two arguments below are the whole surface.
    # WHY : Assumptions: a test that needs an extract to exist arranges one through
    #   `_prepare_extract`, which writes it at the registered source-object name beneath a staging
    #   root it also points the variable at. That is the ONE shape the command accepts, so
    #   arranging it here instead would hide from each test the fact that it depends on it.
    return [
        "stage-dataset",
        "--dataset",
        dataset,
        "--business-date",
        "2022-07-18",
    ]


def test_stage_dataset_rejects_an_unknown_dataset() -> None:
    """Refuse a dataset identifier the seed registry carries under neither spelling."""
    # WHY : Assumptions: no extract is written and no staging root is pointed anywhere, because
    #   the refusal has to arrive before either is consulted -- the dataset decides which prefix
    #   the bytes land under, so a value that resolves to nothing must be refused before any
    #   environment is read. A test that arranged an extract would pass just as well if the
    #   refusal came afterwards.
    assert cli.main(_stage_arguments(dataset="NOT-A-DATASET")) == EXIT_USAGE


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
    # Refactoring Rationale: the absent extract is expressed as an EMPTY staging root rather than
    #   as `--source` naming a file that is not there, because the command no longer accepts a
    #   caller-supplied path. The condition under test is unchanged and is in fact the one the
    #   deployment actually meets: the root resolves, and the object the registry names has not
    #   been delivered into it.
    # WHY : Assumptions: the generation is pinned, because the reservation is reached BEFORE the
    #   extract is opened and it needs the execution token the orchestrator supplies. Without the
    #   pin this case reports the fatal tier for an absent token, which is a true answer to a
    #   different question than the one it asks.
    _point_staging_root(monkeypatch, tmp_path / "empty-root")
    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments()) == EXIT_FAILED


def test_stage_dataset_reports_the_environment_before_the_extract() -> None:
    """Report the fatal tier when neither the environment nor the extract can be resolved."""
    # Assumptions: this pins an ORDERING decision rather than a value, so the observable
    #   consequence of resolving the environment first is recorded instead of being incidental.
    #   With both broken the environment wins, because nothing can be staged until it resolves.
    # Assumptions: "both broken" is now expressed by arranging NEITHER -- no staging settings and
    #   no staging root -- which is a stronger statement of the same ordering than naming an absent
    #   file was: the extract cannot be resolved at all until the root variable is read, and that
    #   read happens after the settings.
    assert cli.main(_stage_arguments()) == EXIT_FATAL


def test_stage_dataset_rejects_a_malformed_business_date() -> None:
    """Refuse a business date that is not an ISO calendar date."""
    arguments = _stage_arguments()
    arguments[arguments.index("2022-07-18")] = "18/07/2022"
    assert cli.main(arguments) == EXIT_USAGE


def test_stage_dataset_reports_an_unresolvable_environment(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report the fatal tier when the staging settings cannot be resolved."""

    def _unresolvable() -> None:
        """Stand in for a staging resolver that cannot find its parameters."""
        raise ConfigurationError("no dataset bucket parameter")

    _prepare_extract(monkeypatch, tmp_path / "extracts")
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _unresolvable)
    assert cli.main(_stage_arguments()) == EXIT_FATAL


def test_stage_dataset_passes_every_argument_through(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Forward each derived and parsed argument to the hardened staging call unchanged."""
    payload = bytes(range(256)) * 2
    source = _prepare_extract(monkeypatch, tmp_path / "extracts", payload=payload)
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
    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments()) == EXIT_OK

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
    # Assumptions: the object name is the SOURCE OBJECT'S OWN NAME, which is the only place the
    #   provenance of the bytes survives once the prefix has taken the dataset, the date and the
    #   generation. It is read from the descriptor rather than written literally here so this
    #   assertion cannot drift from the registry that decides it.
    # Refactoring Rationale: the name is now DERIVED where the earlier shape read it from the
    #   file an operator named. `--object-name` and `--source` are both withdrawn, so there is one
    #   name a staged object can carry and the later verification pass locates it from the same
    #   registry -- which is what a caller-chosen name silently broke.
    assert recorded["object_name"] == seed_datasets.seed_dataset("transactions").source_object


# WHY : Refactoring Rationale: `test_stage_dataset_honours_an_explicit_object_name_and_retention`
#   stood here and is WITHDRAWN. It asserted that `--object-name TRANSACT.BKUP` and `--retain 3`
#   override the defaults, and neither half survives the argument surface: `--object-name` is
#   withdrawn, because the verification pass locates the staged object from the registry and a
#   caller-chosen name is an object nothing reads; and `--retain 3` is now refused at parse time,
#   because three generations is below the baseline's own `LIMIT(5) SCRATCH` operand and would
#   scratch generations that contract requires to exist. The surviving half of what it covered --
#   the derived object name, and a retention RAISED above the floor -- is asserted by
#   `test_stage_dataset_derives_the_object_name_and_honours_a_raised_retention`, and the refusal
#   below the floor by `test_stage_dataset_refuses_a_retention_below_the_baseline_contract`.


def test_stage_dataset_maps_a_rejected_request_to_usage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report a usage error when the staging module refuses a value it was handed."""
    _prepare_extract(monkeypatch, tmp_path / "extracts")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)

    def _refuse(**kwargs: Any) -> None:
        """Stand in for a staging call that rejects an out-of-range generation."""
        raise ValueError("generation 0 is outside 1-9999")

    monkeypatch.setattr(cli, "stage_dataset_file", _refuse)
    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments()) == EXIT_USAGE


def test_stage_dataset_reaches_the_hardened_staging_path(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Prove the production command runs the hardened implementation, not just any writer."""
    # WHY : Assumptions: the payload is a whole multiple of the declared record length -- three
    #   records of 350 bytes -- because the command now derives that width from the registry for
    #   every staged extract and the real staging call refuses a stream that is not a whole number
    #   of records. It is still a slice of a 256-value cycle, so it retains the property this test
    #   depends on: a text-mode read, a UTF-8 decode or a newline translation would visibly corrupt
    #   it, which is what makes the verbatim comparison below evidence rather than a tautology.
    record_length = seed_datasets.record_length(seed_datasets.seed_dataset("transactions"))
    payload = (bytes(range(256)) * 5)[: record_length * 3]
    # WHY : Assumptions: the returned path is deliberately NOT bound. The helper's effect is what
    #   this case needs -- it writes the extract at the registered source-object name and points
    #   CARDDEMO_DATASET_STAGING_ROOT at the directory holding it -- and `stage-dataset` resolves
    #   that location itself now that `--source` is withdrawn, so a bound path would be a name no
    #   assertion below could legitimately use.
    _prepare_extract(monkeypatch, tmp_path / "extracts", payload=payload)
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
    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments()) == EXIT_OK

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


def test_the_generation_refusal_names_only_remedies_the_parser_accepts(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Require every remedy the generation refusal suggests to be executable against the parser.

    Parameters
    ----------
    tmp_path : Path
        Holds a staging root carrying the extract, so the refusal reached is the reservation's own.
    monkeypatch : pytest.MonkeyPatch
        Binds the staging seams and removes the execution identity.
    caplog : pytest.LogCaptureFixture
        Captures the refusal so its wording can be asserted.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal recommends an option the parser would reject, which costs an operator an
        attempt before it teaches them anything.
    """
    # WHY : Refactoring Rationale: the refusal ended "or pass --generation explicitly", and
    #   `--generation` had been withdrawn from every subparser when the durable reservation replaced
    #   it -- so following the advice produced argparse's usage error and status 2. A refusal that
    #   reads as actionable and is not is worse than a shorter one, because the reader spends the
    #   attempt before learning the option does not exist.
    # WHY : Assumptions: the assertion is written against the PARSER rather than against the removed
    #   string, so it keeps working as a guard: any option name a future refusal suggests must be
    #   one this command actually accepts, and a re-added `--generation` would satisfy it honestly
    #   rather than having to be exempted.
    descriptor = seed_datasets.seed_dataset("users")
    staging_root = tmp_path / "extracts"
    staging_root.mkdir()
    (staging_root / descriptor.source_object).write_bytes(b"u" * 80)

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _CapableS3Client)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(staging_root))
    monkeypatch.delenv("CARDDEMO_BATCH_RUN_ID", raising=False)

    with caplog.at_level(logging.ERROR, logger="carddemo_migration.cli"):
        status = cli.main(["stage-dataset", "--dataset=users", "--business-date=2022-07-18"])
    assert status == EXIT_FATAL
    logged = "\n".join(record.getMessage() for record in caplog.records)
    assert "CARDDEMO_BATCH_RUN_ID" in logged, "the refusal must name the remedy that works"

    # Assumptions: the roster of accepted options is recovered from the SUBPARSER itself rather
    #   than listed here, so this compares the message against the shipped parser and not against a
    #   second copy of it that could drift the same way the message did.
    accepted = {
        option
        for action in _subparser("stage-dataset")._actions
        for option in action.option_strings
    }
    suggested = set(re.findall(r"--[a-z][a-z0-9-]*", logged))
    assert suggested <= accepted, (
        "the refusal suggests options this subcommand does not accept: "
        f"{sorted(suggested - accepted)}"
    )


# WHY : Refactoring Rationale:
#   `test_stage_dataset_refuses_a_layout_name_now_that_the_vocabulary_is_unified` stood here and is
#   WITHDRAWN, because the property it asserted is no longer true and its stated ground does not
#   survive measurement. It refused `--dataset TRAN` on the argument that one flag carrying two
#   vocabularies is an ambiguity to remove. There is no ambiguity: every seed token is lower-case
#   with underscores and every layout name is upper-case, the two sets are therefore disjoint, and
#   `seed_datasets` asserts that disjointness at IMPORT rather than leaving it as a reading of the
#   data -- so a value resolves to exactly one descriptor whichever vocabulary it came from. What
#   the split actually cost was a chain that could not be wired: the batch `Map` state hands one
#   `$.dataset` value to every branch, so while staging read the tokens and loading read the layout
#   names, a staging branch and a load branch could not be driven from one list at all. The
#   superseding property -- that both spellings resolve to the SAME dataset, which is the one
#   failure mode a unified vocabulary could introduce -- is asserted by
#   `test_stage_dataset_resolves_a_layout_name_to_the_same_dataset_as_its_token`, and the
#   parser-level normalisation by
#   `test_every_dataset_selector_normalises_a_seed_token_to_its_layout_name`.


def test_stage_dataset_derives_the_record_length_for_every_staged_extract(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Apply the fixed-record check to every extract, because every extract is now a derived one.

    Purpose
    -------
    Pin that the declared width travels with the staging call rather than being an option a caller
    could switch off, so a truncated or wrongly-blocked extract is refused before its bytes are
    filed under a retained generation.

    Parameters
    ----------
    tmp_path : Path
        Scratch directory that becomes the staging root.
    monkeypatch : pytest.MonkeyPatch
        Used to bind the environment, the client and the staging seams.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the staging call is handed no declared record length.
    """
    # WHY : Refactoring Rationale: this case used to assert an ASYMMETRY -- the width was derived
    #   for a registry-resolved extract and deliberately NOT applied to one an operator named with
    #   `--source`. The asymmetry was correct while that argument existed, and its ground was
    #   measured: `app/data/ASCII/cardxref.txt` holds 36 data bytes per line where the copybook
    #   declares 50, so applying the check to an operator-supplied ASCII file would have refused a
    #   perfectly good extract. With `--source` withdrawn there is no operator-supplied path to
    #   exempt: every staged extract is the object the registry names beneath the staging root, and
    #   every one of those is the EBCDIC form whose byte length divides exactly by its record
    #   length. So the exemption is withdrawn with the argument that needed it, and the check is
    #   now unconditional -- which is the stronger of the two states and the one this case pins.
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

    _prepare_extract(monkeypatch, tmp_path / "extracts", "card_xref")
    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="card_xref")) == EXIT_OK
    # WHY : Assumptions: the expected width is read from the layout registry rather than written as
    #   50 here, so the assertion cannot drift from the copybook the loader and the readers share.
    assert recorded[-1]["record_length"] == seed_datasets.record_length(descriptor)
    # WHY : Assumptions: the layout-name spelling is exercised as well as the token, because the
    #   selector accepts both and the width must be derived either way -- a normalisation that
    #   resolved the dataset but lost its geometry would stage an unchecked stream.
    assert cli.main(_stage_arguments(dataset=descriptor.layout_name)) == EXIT_OK
    assert recorded[-1]["record_length"] == seed_datasets.record_length(descriptor)


def test_stage_dataset_maps_a_source_refusal_to_failed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report the failed tier for a source refusal even though it is also a ValueError."""
    _prepare_extract(monkeypatch, tmp_path / "extracts")

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
    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments()) == EXIT_FAILED


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
    # Assumptions: the account master carries no field any factory in layouts.py had
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


# Assumptions: this is the committed extract the `transactions` seed token names -- the single
#   350-byte record `app/jcl/TRANFILE.jcl` REPROs to prime the TRANSACT cluster. It is read here
#   rather than fabricated because it is the delivery an operator and the nightly chain actually
#   meet, and its `TRAN-CAT-CD` span is four NUL bytes, which is what makes it the shipped subject
#   for a numeric-codec refusal. A hand-built corrupt record would prove only that this file agreed
#   with itself about what corruption looks like.
_TRANSACTION_INITIALIZER_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.DALYTRAN.PS.INIT"


def _published_decode_faults() -> dict[str, type[BaseException]]:
    """Discover every decode-fault class the copybook package publishes, by scanning it.

    Purpose
    -------
    Derive the subject list for the refusal-coverage assertions below from the CODECS rather than
    from a list restated here, so a fault type added to a codec later becomes a new subject
    automatically instead of escaping the command line unnoticed.

    Parameters
    ----------
    None
        The four modules scanned are the whole decode stack: the layout registry, the EBCDIC record
        codec and the two numeric field codecs beneath it.

    Returns
    -------
    dict[str, type[BaseException]]
        Class name to class, for every ``ValueError`` subclass DECLARED in one of those modules.
        Keyed by name so a failing assertion below names the class that is not covered.

    Raises
    ------
    None
    """
    # WHY : Assumptions: `o.__module__ == module.__name__` is what restricts the scan to classes
    #   each module DECLARES rather than merely imports. Without it `ebcdic_codec` would contribute
    #   the two layout errors it imports for its own raising, and the four modules would report
    #   overlapping sets -- which still passes, but stops being a measurement of where each fault
    #   is authored and so stops being able to notice a fault authored somewhere new.
    faults: dict[str, type[BaseException]] = {}
    for module in (layouts, ebcdic_codec, zoned, packed):
        for name, obj in vars(module).items():
            if (
                inspect.isclass(obj)
                and issubclass(obj, ValueError)
                and obj.__module__ == module.__name__
            ):
                faults[name] = obj
    return faults


def test_the_shared_refusal_set_covers_every_published_decode_fault() -> None:
    """Require the command line's decode-refusal set to match every decode fault the codecs raise.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a published decode fault would escape the shared set, which is how one reaches ``main``
        as a traceback and a status outside the documented classification.
    """
    # WHY : Refactoring Rationale: the set held two entries and covered four of the eight faults the
    #   stack publishes -- the two it named plus the two subclasses of those, which is exactly the
    #   coverage pattern that reads as complete. `EbcdicFieldDecodeError`, `ZonedDecimalError`,
    #   `ZonedSpanWidthError` and `PackedDecimalError` are `ValueError` SIBLINGS, so every command
    #   that read an extract could end in a traceback with status 1. This test is written as a scan
    #   rather than as five membership assertions so that the NEXT fault a codec gains is a failure
    #   here rather than a traceback in a deployment.
    faults = _published_decode_faults()
    # Assumptions: the discovery is asserted non-vacuous before it is used. A scan that found
    #   nothing -- a renamed module, a moved class -- would make every assertion below pass while
    #   proving nothing, which is the failure mode a derived subject list invites.
    assert len(faults) >= 8, f"the decode stack must publish at least eight faults, found {faults}"
    for name, fault in sorted(faults.items()):
        assert issubclass(fault, cli._DECODE_ERRORS), (
            f"{name} is a published decode fault and must be covered by the shared refusal set, "
            f"which names {[member.__name__ for member in cli._DECODE_ERRORS]}"
        )
        # Assumptions: the same class must also reach the LOAD and VERIFICATION handlers' set,
        #   because those refuse on `_step_errors()` and not on the constant directly. Asserting
        #   only the constant would let the two diverge, which is the state that produced one
        #   classified status and one traceback for a single malformed file.
        assert issubclass(fault, cli._step_errors()), f"{name} must reach the step refusal set"
    # Assumptions: the widening is asserted to stop at the named classes. A bare `ValueError` must
    #   NOT match, because catching it would report a programming mistake in this package -- a bad
    #   int() over an operator string, a failed enum lookup -- to an operator as a malformed
    #   delivery, sending them to inspect bytes that are fine.
    assert not issubclass(ValueError, cli._DECODE_ERRORS)


def test_decode_record_classifies_a_numeric_codec_refusal_rather_than_raising(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report the failed tier when a field's declared span holds bytes the numeric codec refuses.

    Parameters
    ----------
    capsys : pytest.CaptureFixture[str]
        Captures the refusal so the message can be asserted safe and self-locating.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal escapes as a traceback rather than becoming the documented status.
    """
    # WHY : Assumptions: the subject is the SHIPPED transaction initializer rather than a corrupted
    #   copy of a good extract, because this is the file the nightly chain and the runbook both
    #   name -- so the case that used to end in a traceback is the case an operator actually met.
    assert (
        cli.main(_decode_arguments(_TRANSACTION_INITIALIZER_EXTRACT, dataset="TRAN")) == EXIT_FAILED
    )
    captured = capsys.readouterr()
    # Assumptions: the message must locate the fault to a FIELD, because that is the whole reason
    #   for quoting a package-authored refusal instead of printing a class name: an operator has to
    #   know which span of which record to look at.
    assert "TRAN-CAT-CD" in captured.err
    # Assumptions: no decoded record is printed on this path. A partially decoded field map written
    #   to stdout beside a refusal would read as a successful decode to anything parsing the output.
    assert captured.out == ""


@pytest.mark.parametrize(
    "verb",
    ["verify-row-counts", "verify-checksum", "verify-money-parity"],
)
def test_the_verification_verbs_classify_a_numeric_codec_refusal_rather_than_raising(
    verb: str,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Report the failed tier from every per-dataset verification verb on an undecodable extract.

    Parameters
    ----------
    verb : str
        The verification subcommand under test.
    monkeypatch : pytest.MonkeyPatch
        Binds the database seam so the verb runs end to end without a cluster.
    fake_aurora : FakeAuroraDatabase
        The recording double every patched connection returns.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If any verb ends in a traceback, which is the state that made one malformed file produce
        three different outcomes from three commands that read it the same way.
    """
    # WHY : Assumptions: all three verbs are parametrized rather than one being taken as
    #   representative, because they refuse through three different `except` clauses -- each adding
    #   its own verification error to the shared set -- and a widening applied to the shared half
    #   still has to reach all three. Asserting one would leave the other two able to regress
    #   independently, which is how this class of defect survived a checkpoint.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    status = cli.main(
        [
            verb,
            "--dataset=TRAN",
            f"--source={_TRANSACTION_INITIALIZER_EXTRACT}",
            "--encoding=ebcdic",
        ]
    )
    assert status == EXIT_FAILED


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
        # WHY : Assumptions: the returned settings are re-stamped with the schema's OWN login
        #   role rather than answered verbatim, because the production path now refuses a
        #   credential whose stored user is not the role the schema's tables were granted to. A
        #   double answering one fixed user for every schema would make that refusal fire on every
        #   command -- so the stub would be asserting the check exists rather than letting the
        #   command under test run, and the realistic case (each schema's secret holds its own
        #   role) would go untested.
        return replace(settings, user=role_for_schema(schema))

    def _connect(resolved: AuroraConnectionSettings, *, expected_role: str | None = None) -> object:
        """Open a recording connection from resolved settings, honouring the role expectation.

        Parameters
        ----------
        resolved : AuroraConnectionSettings
            The settings the command resolved.
        expected_role : str | None
            The login role the command expects the credential to authenticate as, or ``None``
            when it states no expectation.

        Returns
        -------
        object
            A recording connection from the double.

        Raises
        ------
        ConfigurationError
            If the resolved user is not the expected role, or propagated from the double if the
            parameters would not have verified the server certificate.
        """
        # WHY : Assumptions: the expectation is CHECKED here rather than accepted and dropped,
        #   because a double that accepted the keyword and ignored it would let the production
        #   check be deleted with every command test still passing. The check mirrors
        #   ``aurora.connect``: agreement is required, and disagreement is a configuration fault.
        if expected_role is not None and resolved.user != expected_role:
            raise ConfigurationError(
                f"the credential for this schema authenticates as {resolved.user!r} but"
                f" {expected_role!r} was expected"
            )
        # WHY : Assumptions: the parameters come from ``as_connection_params`` rather than being
        #   hand-built, so the double's TLS keyword checks are exercised on the same translation
        #   the production path performs. Hand-building the mapping is precisely how the
        #   ``database``-versus-``dbname`` rename gets lost.
        return database.connect(**resolved.as_connection_params())

    monkeypatch.setattr(cli, "resolve_aurora_settings", _resolve)
    # WHY : Assumptions: the collaborator is replaced on the module that DEFINES it rather
    #   than on `cli`, because the command imports the name inside the handler -- a
    #   deliberate deferral recorded in the module header, so that a defect in the loader or
    #   in a verification pass cannot fail the commands that touch neither. A function-local
    #   import resolves its name from the source module's namespace at call time, so binding
    #   a replacement onto `cli` would be read by nothing and the double would go unused --
    #   the test would then exercise the real driver and fail for an unrelated reason.
    # Trade-offs: patching the source module is broader than patching one command's view of
    #   it. That is accepted because the alternative -- reinstating a module-level
    #   indirection on `cli` purely so a test could rebind it -- would put the deferral back
    #   at module scope and undo the isolation it exists to provide.
    monkeypatch.setattr(aurora, "connect", _connect)
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
    assert fake_aurora.copy_statements == [target_for("TCATBAL").stage_copy_statement()]
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


@pytest.mark.parametrize(
    ("command", "expected_exit"),
    [
        ("verify-row-counts", EXIT_FAILED),
        ("verify-checksum", EXIT_FAILED),
        ("verify-money-parity", EXIT_OK),
    ],
)
def test_every_verification_command_accepts_the_category_balance_dataset(
    command: str,
    expected_exit: int,
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
    expected_exit : int
        The status that subcommand must report under this arrangement, spelled per command
        because the three genuinely differ -- see the trade-off note below the invocation.
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
    fake_aurora.arrange_rows("COUNT(*) FROM", [(0,)])
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
    # WHY : the SCHEMA REQUEST is the property this case exists for: a refused target returns
    #   before any connection is opened, so an empty list is the failure this test was written to
    #   catch.
    assert requested == ["ledger"], (
        f"{command} resolved no owning schema for TCATBAL, so the target was refused before any"
        " comparison could be attempted"
    )
    # WHY : Refactoring Rationale: this assertion was `exit_code in {EXIT_OK, EXIT_FAILED}`, which
    #   is a tautology -- every path through every one of the three commands returns one of those
    #   two, so it could not fail and certified nothing. The expectation is now spelled PER COMMAND
    #   because the three genuinely diverge under this one arrangement, which is what the loose
    #   form was papering over: row counts and checksum compare 50 seed records against the 0 rows
    #   arranged and correctly FAIL, while money parity totals TRAN-CAT-BAL over the same 50
    #   records to exactly 0.00 -- every committed category balance is zero -- and correctly MATCHES
    #   the arranged 0.00. Asserting a single shared outcome was therefore impossible, but
    #   asserting the three separately is not, and it pins the divergence as intended rather than
    #   leaving a reader to assume the codes are interchangeable.
    assert exit_code == expected_exit


def _loaded_category_balance_rows() -> list[tuple[object, ...]]:
    """Build the rows a faithful category-balance load would have left in the target table.

    Purpose
    -------
    Give the checksum command a database that answers with exactly what the loader would have
    written, so a PASSED verdict means the command's own assembly -- read, prepare, digest,
    compare -- arrived back at the extract it started from.

    Returns
    -------
    list[tuple[object, ...]]
        One tuple per seed record, its values in the target's comparable-field order.

    Raises
    ------
    LayoutError
        Propagated if the committed seed cannot be decoded against its declared layout.
    OSError
        Propagated if the committed seed cannot be read.
    """
    # WHY : the rows are built through `prepare_record` -- the SAME transformation the load command
    #   applies -- rather than by hand. That is deliberate and is not circular: what is under test
    #   here is the VERIFICATION command's wiring, and the honest arrangement for "the load
    #   succeeded" is the image the loader would have written. Hand-authoring fifty rows would
    #   instead test whether this file can transcribe a copybook, which the reader suite already
    #   covers record by record.
    target = target_for("TCATBAL")
    fields = target.comparable_fields()
    context = LoadContext()
    return [
        tuple(prepare_record(target, record, context)[field] for field in fields)
        for record in tcatbal.read_ascii_category_balances(_CATEGORY_BALANCE_SEED)
    ]


def _verification_arguments(command: str, dataset: str, source: Path) -> list[str]:
    """Build the command line for a verification subcommand over one extract.

    Parameters
    ----------
    command : str
        The verification subcommand to invoke.
    dataset : str
        The record name whose load target is verified.
    source : Path
        The extract to measure the source side from.

    Returns
    -------
    list[str]
        The argument vector, ready for :func:`cli.main`.

    Raises
    ------
    None
        Assembling a list cannot fail.
    """
    return [command, "--dataset", dataset, "--source", str(source), "--encoding", "ascii"]


def test_the_checksum_command_reports_a_verified_dataset(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report agreement and exit OK when every loaded row matches the extract it came from.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the read-back with the loaded image.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the report the command prints.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a faithful load does not verify, or the report does not name what it compared.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    fake_aurora.arrange_rows("transaction_category_balances", _loaded_category_balance_rows())
    exit_code = cli.main(
        _verification_arguments("verify-checksum", "TCATBAL", _CATEGORY_BALANCE_SEED)
    )
    printed = capsys.readouterr().out
    assert exit_code == EXIT_OK
    lines = printed.splitlines()
    # WHY : the exact header is asserted, not merely that something was printed. This report is the
    #   only evidence an operator has that the pass ran against the table they meant, and a header
    #   naming the wrong dataset or the wrong table would make a PASSED verdict certify a
    #   comparison of something else entirely.
    assert lines[0] == (
        'record checksum verification: TCATBAL -> "ledger"."transaction_category_balances"'
    )
    # WHY : the two digest lines are asserted EQUAL. That is the substance of the verdict; the
    #   PASSED word is derived from it, so asserting only the word would pass a report that
    #   printed PASSED above two different digests.
    assert lines[1].removeprefix("  source ") == lines[2].removeprefix("  target ")
    # WHY : Refactoring Rationale: the digest verdict is LOCATED rather than read off the end of the
    #   output, because it is no longer the last line. The command now prints a sealed-column audit
    #   beside the digest -- the digest necessarily excludes any column holding an envelope, so
    #   without the audit a load that stored a null verification value or dropped every enciphered
    #   identifier printed a clean comparison. Asserting a position would have made this case fail
    #   for the arrival of a line it is not about, and asserting only `in printed` would have
    #   accepted a verdict line printed anywhere, including inside the audit's own text.
    assert (
        next(line for line in lines if line.startswith("record checksum verification PASSED"))
        == f"record checksum verification PASSED: {_CATEGORY_BALANCE_RECORDS} position(s)"
        " compared, 0 record(s) differing, 0 position(s) occupied on one side only"
    )
    # WHY : Assumptions: the audit line is asserted PRESENT as well, because "0 column(s)" is the
    #   positive statement that the digest covered the whole row for this record. A command that had
    #   silently stopped auditing would otherwise still satisfy every assertion above.
    assert lines[-1] == (
        'sealed columns TCATBAL -> "ledger"."transaction_category_balances": 0 column(s)'
    )
    # WHY : the POSITION COUNT in that line is what distinguishes this from a pass that verified
    #   nothing. A command handed an empty read-back and an empty extract would also print PASSED.
    assert f"records={_CATEGORY_BALANCE_RECORDS}" in lines[1]


def test_the_checksum_command_fails_and_names_a_corrupted_position(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Exit FAILED and name the position and field of a single corrupted balance.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the read-back with one corrupted row.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the report the command prints.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the corruption passes, or the report names the wrong position or discloses the value.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    rows = _loaded_category_balance_rows()
    corrupted_ordinal = 7
    # WHY : one cent on ONE row of fifty is the smallest corruption this pass exists to find, and
    #   the row chosen is neither the first nor the last -- a boundary row would be caught by a
    #   comparison that only checked the ends, so putting it in the interior is what makes this
    #   case able to fail.
    corrupted = list(rows[corrupted_ordinal - 1])
    corrupted[-1] = Decimal("0.01")
    rows[corrupted_ordinal - 1] = tuple(corrupted)
    fake_aurora.arrange_rows("transaction_category_balances", rows)
    exit_code = cli.main(
        _verification_arguments("verify-checksum", "TCATBAL", _CATEGORY_BALANCE_SEED)
    )
    printed = capsys.readouterr().out
    assert exit_code == EXIT_FAILED
    # WHY : ⚠️ Refactoring Rationale: the expected line now carries the KEY between the
    #   position and the field, because the comparison pairs the two sides by the target's key
    #   rather than by extract position and reports the pair it was comparing. The key is the
    #   account, type and category of the row -- not a balance -- so naming it discloses nothing
    #   the withheld value assertion below is protecting.
    difference = next(line for line in printed.splitlines() if "DIFFER" in line)
    assert difference.startswith(f"  DIFFER  record={corrupted_ordinal}  key=")
    assert "TRAN-CAT-BAL" in difference
    # WHY : Refactoring Rationale: the verdict is located rather than taken from the end of the
    #   output, for the reason recorded on the passing case: the sealed-column audit now prints
    #   after the digest, so the last line is the audit's.
    assert (
        next(
            line
            for line in printed.splitlines()
            if line.startswith("record checksum verification FAILED")
        )
        == f"record checksum verification FAILED: {_CATEGORY_BALANCE_RECORDS} position(s)"
        " compared, 1 record(s) differing, 0 position(s) occupied on one side only"
    )
    # WHY : the corrupted VALUE is asserted absent from the command's own output for the same
    #   reason the comparison report withholds it -- this text goes to a container log read by
    #   whoever holds log access, while the balances it compares are read only by whoever is
    #   entitled to the account. The position and the field name are what an operator acts on.
    assert "0.01" not in printed


def test_the_checksum_command_fails_when_the_load_stopped_early(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Exit FAILED and name the first position never read back when the load ran short.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the read-back one row short.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the report the command prints.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a short load verifies, or the report does not say where it stopped.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    # WHY : exactly ONE row is withheld, which is the hardest short load to notice: the row-count
    #   pass reports fifty against forty-nine and says only that a row is missing, while this pass
    #   says WHICH position never arrived -- the difference between "re-run the load" and "the load
    #   stopped after record forty-nine".
    fake_aurora.arrange_rows("transaction_category_balances", _loaded_category_balance_rows()[:-1])
    exit_code = cli.main(
        _verification_arguments("verify-checksum", "TCATBAL", _CATEGORY_BALANCE_SEED)
    )
    printed = capsys.readouterr().out
    assert exit_code == EXIT_FAILED
    # WHY : ⚠️ Refactoring Rationale: the note reads "for this record" and the line carries the
    #   key, because an unpaired record is reported by the KEY it was looked up under rather than by
    #   its position in the extract. The position is still named, and it is still the last one.
    absent = next(line for line in printed.splitlines() if "ABSENT" in line)
    assert absent.startswith(f"  ABSENT  record={_CATEGORY_BALANCE_RECORDS}  key=")
    assert "no row was read back for this record" in absent
    # WHY : Refactoring Rationale: located rather than positional, for the reason recorded on the
    #   passing case -- the sealed-column audit is printed after the digest verdict.
    assert (
        next(
            line
            for line in printed.splitlines()
            if line.startswith("record checksum verification FAILED")
        )
        == f"record checksum verification FAILED: {_CATEGORY_BALANCE_RECORDS} position(s)"
        " compared, 0 record(s) differing, 1 position(s) occupied on one side only"
    )


def test_the_checksum_command_classifies_a_value_it_cannot_digest(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report a value outside the comparator's domain as a return code, not as a traceback.

    Purpose
    -------
    The admitted domain covers every type the migration's own columns declare, but it is still
    CLOSED -- and when something outside it arrives, the refusal has to reach the caller the way
    every other failure here does. Every command in this module reports a documented failure as a
    return code because the batch state that invokes it branches on one, and this refusal is the
    one that used to escape as a traceback naming the place the digest noticed rather than the
    dataset an operator asked about.

    Assumptions: the value arranged is a ``bool``, which is what a ``BOOLEAN`` column
    returns. No comparable column in this migration is declared that way -- this one is
    ``NUMERIC(11,2)`` -- so a boolean arriving in it means the schema has drifted from the
    contract, which is precisely what a verification pass exists to report rather than to crash on.
    A ``float`` would say the same thing and cannot be used: the recording double refuses one
    outright, on the same exact-cents reasoning this pass applies, so the drift has to be expressed
    with a type the double will carry.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures standard output, which must carry no comparison.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command does not exit FAILED, or prints a comparison over a value it could not
        render.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    drifted = list(_loaded_category_balance_rows())
    # WHY : Assumptions: the first row's LAST value is replaced, which is the balance -- the
    #   comparable field the target declares NUMERIC -- and every other row is left as a faithful
    #   load. That is what makes the run reach the comparator normally and refuse on one value,
    #   rather than failing earlier for a shape the read-back never had.
    drifted[0] = (*drifted[0][:-1], True)
    # WHY : the read-back is arranged on the TABLE name, which is the one fragment of the composed
    #   statement this case does not itself write -- so the arrangement goes stale if the read-back
    #   stops reading that table, rather than silently matching something else.
    fake_aurora.arrange_rows("transaction_category_balances", drifted)

    exit_code = cli.main(
        [
            "verify-checksum",
            "--dataset",
            "TCATBAL",
            "--source",
            str(_CATEGORY_BALANCE_SEED),
            "--encoding",
            "ascii",
        ]
    )

    assert exit_code == EXIT_FAILED
    # WHY : the ABSENCE of a rendered comparison is asserted as well as the code. A comparison
    #   printed beside a non-zero status is the shape an operator skims and reads as a verdict, and
    #   this run reached none.
    assert "record checksum verification" not in capsys.readouterr().out


def test_the_money_command_reports_agreement_for_every_money_field(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report a match per money field and exit OK when the totals agree exactly.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the coalesced sum.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the report the command prints.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If agreement is not reported, or the line omits the record count it compared.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    fake_aurora.arrange_rows("SUM(", [(_CATEGORY_BALANCE_TOTAL,)])
    exit_code = cli.main(
        _verification_arguments("verify-money-parity", "TCATBAL", _CATEGORY_BALANCE_SEED)
    )
    printed = capsys.readouterr().out
    assert exit_code == EXIT_OK
    # WHY : the line is asserted to begin with the verdict token, because that prefix is what an
    #   operator greps a container log for. A report that buried MATCH mid-line would still satisfy
    #   a substring check while breaking the only mechanical read of it anyone performs.
    # WHY : Refactoring Rationale: the expected prefix names the COPYBOOK FIELD between the record
    #   and the arrow, where it named only the record. The pass compares a copybook field against a
    #   column, so the line now states both halves of that pairing: a record carrying five money
    #   fields renders five lines that were otherwise distinguishable only by their target column,
    #   leaving a reader to invert the field-to-column mapping to learn which source field
    #   disagreed. The verdict token is still the first thing on the line, which is the property
    #   this assertion exists to hold.
    assert printed.startswith(
        "MATCH TCATBAL.TRAN-CAT-BAL -> ledger.transaction_category_balances.balance"
    )
    # WHY : the record count is asserted for the reason the arranged zero makes necessary. Every
    #   committed category balance is zero, so a command that totalled NO records would also arrive
    #   at 0.00 and report a match -- the count is the only figure in this line that distinguishes
    #   fifty records agreeing from nothing having been read.
    assert f"records={_CATEGORY_BALANCE_RECORDS}" in printed
    assert f"source={_CATEGORY_BALANCE_TOTAL} target={_CATEGORY_BALANCE_TOTAL}" in printed


def test_the_money_command_fails_on_a_one_cent_difference(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Exit FAILED and report the difference when the loaded total is one cent out.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the sum one cent high.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the report the command prints.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If one cent passes, or the report does not quantify the difference.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    # WHY : one cent is the tolerance and it is zero. This is the command-level counterpart of the
    #   unit assertion in test_verification.py: the difference has to survive the whole path --
    #   reader, comparison, rendering and exit status -- because a pass that detected it internally
    #   and exited OK would be read by CI as a clean migration.
    fake_aurora.arrange_rows("SUM(", [(_CATEGORY_BALANCE_TOTAL + Decimal("0.01"),)])
    exit_code = cli.main(
        _verification_arguments("verify-money-parity", "TCATBAL", _CATEGORY_BALANCE_SEED)
    )
    printed = capsys.readouterr().out
    assert exit_code == EXIT_FAILED
    # WHY : Refactoring Rationale: the copybook field is named between the record and the arrow, for
    #   the reason recorded on the agreeing case.
    assert printed.startswith(
        "DIFFER TCATBAL.TRAN-CAT-BAL -> ledger.transaction_category_balances.balance"
    )
    # WHY : the difference is asserted as an exact SIGNED value, not merely present. Every line the
    #   command prints carries a `difference=` field -- a match renders `+0.00` -- so a bare
    #   substring check would pass against the matching output this test exists to distinguish
    #   itself from, and the sign says which side is short.
    assert "difference=+0.01" in printed
    assert f"records={_CATEGORY_BALANCE_RECORDS}" in printed


def test_the_money_command_says_a_dataset_holds_no_money_rather_than_verifying_it(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report a skip, not a match, for an extract whose layout declares no signed field.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double, which must never be asked for a sum.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the report the command prints.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a moneyless dataset reports a match, or a sum is issued for it anyway.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    exit_code = cli.main(
        _verification_arguments("verify-money-parity", "TRANTYPE", _TRANSACTION_TYPE_SEED)
    )
    printed = capsys.readouterr().out
    assert exit_code == EXIT_OK
    # WHY : SKIP and MATCH must be distinguishable in the output, because they mean opposite
    #   things: one says the money agrees, the other says there was none to compare. An operator
    #   reading a run over every dataset needs to be able to tell which columns were actually
    #   certified, and a moneyless dataset reporting MATCH would inflate that count silently.
    assert printed.startswith("SKIP TRANTYPE declares no signed display field")
    assert "MATCH" not in printed
    # WHY : no sum is issued at all, which is the property that makes the skip cheap AND makes it
    #   provable. A command that queried a sum and then reported a skip would be reading a column
    #   that the layout says does not exist.
    assert not any("SUM(" in statement for statement in fake_aurora.executed_sql())


def test_the_checksum_read_back_pairs_a_composite_key_read_back_out_of_order(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: object,
    aurora_settings: object,
) -> None:
    """Pair a composite-key dataset whose read-back arrives in no particular order.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the driver double and the settings the command resolves.
    fake_aurora : object
        Recording double for the driver, whose statement log carries the read-back.
    aurora_settings : object
        The settings the command resolves its connection from.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a read-back is ordered by the server, or if a scrambled read-back fails to pair.
    """
    # WHY : ⚠️ Refactoring Rationale: this case asserted that the read-back was ORDERED BY the
    #   target's key columns, and that assertion is withdrawn because the pass no longer asks the
    #   server to order it. The defect it was written against was real -- the read-back had been
    #   ordered by every comparable column while the source arrived in extract order, so the two
    #   sides were in two different orders and a correct load compared record-against-wrong-row --
    #   and the remedy is now stronger than a server-side key ordering: `_paired_records` sorts BOTH
    #   sides in this process on the rendered key. That removes the engine's collation from the
    #   comparison, which a server-side ORDER BY on a character key could not, and it makes the
    #   read-back's arrival order irrelevant rather than merely agreed.
    # WHY : Assumptions: the composite-key dataset is still the subject, for the reason the
    #   withdrawn
    #   case gave -- three key columns distinguish "paired on the whole key" from "paired on the
    #   first column" where a single-column key could not -- but the property asserted about it is
    #   now that a read-back in NO agreed order still pairs.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    exit_code = cli.main(
        [
            "verify-checksum",
            "--dataset",
            "TCATBAL",
            "--source",
            str(_CATEGORY_BALANCE_SEED),
            "--encoding",
            "ascii",
        ]
    )

    read_backs = [
        statement for statement, _ in fake_aurora.statements if statement.startswith("SELECT")
    ]
    assert read_backs, "the checksum pass issued no read-back at all"
    assert not any(" ORDER BY " in statement for statement in read_backs), (
        "a keyed read-back was ordered by the server, which puts the engine's collation back into a"
        " comparison the in-process sort exists to keep independent of it"
    )
    # WHY : Assumptions: this case asserts the SHAPE of the read-back and not the verdict, because
    #   the double here is not arranged with rows -- the verdict over a scrambled read-back is
    #   asserted by the sibling case that projects the seed through the target's own
    #   `prepare_record` and reverses it, where a difference could only be the order. Asserting a
    #   verdict here as well would either duplicate that case or, without arranged rows, assert that
    #   an empty read-back disagrees with a fifty-record source, which says nothing about ordering.
    assert exit_code in {EXIT_OK, EXIT_FAILED}


def _conforming_row_count_rows(short_dataset: str | None = None) -> list[tuple[object, ...]]:
    """Build a row-count report covering every declared dataset, optionally one line short.

    Purpose
    -------
    Give the report command a result set it can judge. The pass refuses a report that omits any
    declared (dataset, table) pair, so a partial fixture would fail for coverage rather than for
    the property under test.

    Parameters
    ----------
    short_dataset : str | None
        The dataset whose actual count is one below its baseline, producing a MISMATCH line, or
        ``None`` for a report in which every line matches.

    Returns
    -------
    list[tuple[object, ...]]
        One six-column row per declared pair, in the order the query's own ordering produces them
        being irrelevant to the pass, which keys on the dataset label.

    Raises
    ------
    None
        Composing rows from published declarations cannot fail.
    """
    # WHY : Assumptions: the pairs come from the PUBLISHED declarations -- SEED_DATASET_BASELINES
    #   for the ten seeded lines, and the load target of UNSEEDED_LAYOUT_NAME for the one line
    #   whose table has no seed extract. Listing eleven pairs literally here would put a second
    #   copy of the migration's own inventory in a test file, where it would go stale silently.
    unseeded = target_for(UNSEEDED_LAYOUT_NAME)
    # WHY : Refactoring Rationale: the baseline-free line's actual count is ZERO where this helper
    #   used to state seven. The pass no longer renders a baseline-free line as merely
    #   `(not comparable)`: it applies the expectation such a line IS held to -- a dataset shipping
    #   no seed extract must leave its target empty -- and reports `expected empty: HELD` or
    #   `VIOLATED`. Seven rows in a table nothing seeds is the VIOLATED case, so a "conforming"
    #   fixture carrying it made every caller of this helper fail on the one line that was never
    #   the property under test. Zero is the value the pass's own declaration requires, which is
    #   what makes this fixture conforming rather than merely accepted.
    # Trade-offs: the VIOLATED reading is worth asserting, and is asserted separately by the case
    #   that states the expectation explicitly, rather than by leaving it latent in the shared
    #   fixture where it silently decided two unrelated verdicts.
    rows: list[tuple[object, ...]] = [
        (
            NO_DATASET_LABEL,
            f"{unseeded.schema}.{unseeded.table}",
            None,
            0,
            None,
            "NO_BASELINE",
        )
    ]
    for baseline in SEED_DATASET_BASELINES.values():
        if baseline.target_table is None:
            continue
        actual = (
            baseline.expected_rows - 1
            if baseline.dataset == short_dataset
            else baseline.expected_rows
        )
        delta = actual - baseline.expected_rows
        rows.append(
            (
                baseline.dataset,
                baseline.target_table,
                baseline.expected_rows,
                actual,
                delta,
                "MISMATCH" if delta else "MATCH",
            )
        )
    return rows


def test_the_row_count_report_command_runs_the_shipped_query_on_a_reporting_session(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Run the whole-migration report through the command, over the query file as shipped.

    Purpose
    -------
    Prove the least-privilege verification path is REACHABLE from the command line. The pass was
    delivered and unreached: nothing in the distribution called ``open_reporting_connection`` or
    ``verify_row_counts``, so the only row-count command opened a schema-owner connection. This
    drives the new command end to end -- argument parsing, the reporting connection, the shipped
    query file, the six-column contract and the verdict.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then the report.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command does not verify, does not read the shipped query, or leaves the connection
        open.
    """
    fake_aurora.arrange_rows("current_user", [(reporting_role(),)])
    # WHY : Assumptions: the rows are arranged against a fragment of the SHIPPED query --
    #   the aggregate view it reads -- rather than against text this test supplies, because the
    #   command deliberately takes no query argument. That makes the assertion cover
    #   read_row_count_query over the real file: a query rewritten to read a different relation
    #   would no longer match and the report would come back empty.
    fake_aurora.arrange_rows("v_verification_row_counts", _conforming_row_count_rows())
    connections: list[object] = []

    def _open() -> object:
        """Open a recording connection in place of one on the reporting role.

        Returns
        -------
        object
            A recording connection from the double.

        Raises
        ------
        None
        """
        connection = fake_aurora.connect(
            **{
                "host": "aurora.carddemo.invalid",
                "port": 5432,
                "dbname": "carddemo",
                "user": reporting_role(),
                "sslmode": "verify-full",
                "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
            }
        )
        connections.append(connection)
        return connection

    # WHY : Assumptions: the collaborator is replaced on the module that DEFINES it rather
    #   than on `cli`, because the command imports the name inside the handler -- a
    #   deliberate deferral recorded in the module header, so that a defect in the loader or
    #   in a verification pass cannot fail the commands that touch neither. A function-local
    #   import resolves its name from the source module's namespace at call time, so binding
    #   a replacement onto `cli` would be read by nothing and the double would go unused --
    #   the test would then exercise the real driver and fail for an unrelated reason.
    # Trade-offs: patching the source module is broader than patching one command's view of
    #   it. That is accepted because the alternative -- reinstating a module-level
    #   indirection on `cli` purely so a test could rebind it -- would put the deferral back
    #   at module scope and undo the isolation it exists to provide.
    monkeypatch.setattr(row_counts, "open_reporting_connection", _open)

    # WHY : Assumptions: the root is passed explicitly because this suite runs against the
    #   INSTALLED distribution, where the package-relative default cannot resolve -- the sql tree
    #   ships beside the package rather than inside it, which is the documented behaviour and the
    #   reason the option exists. Passing the checkout's own data-migration directory makes this
    #   test read the same file an operator runs with psql, which is the point of the assertion
    #   below on the shipped query's own relation name.
    exit_code = cli.main(["verify-row-count-report", "--sql-root", str(_DISTRIBUTION_ROOT)])

    assert exit_code == EXIT_OK
    rendered = capsys.readouterr().out
    assert "row count verification PASSED" in rendered
    # WHY : the first two statements are asserted in order. `SET TRANSACTION READ ONLY` leads,
    #   because it states a property OF the transaction and so belongs before anything has run in
    #   it -- the probe included; the session probe follows, inside that same transaction, because
    #   the guard's whole value is running before the query text does. A probe made after the query
    #   would satisfy an exit-code assertion while the report had already run under whatever
    #   authority the connection carried.
    executed = fake_aurora.executed_sql()
    assert "READ ONLY" in executed[0]
    assert "current_user" in executed[1]
    assert any("v_verification_row_counts" in sql for sql in executed[2:])
    # WHY : the connection is asserted CLOSED because the command opens it itself. A verification
    #   step in the batch chain runs to completion and exits, so a leaked connection is not a leak
    #   an operator would ever see -- it is one the cluster's connection limit sees during a rerun.
    assert connections and all(getattr(each, "closed", False) for each in connections)


def test_the_row_count_report_command_refuses_a_writable_session(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Refuse to report at all when the session handed to the pass can write.

    Purpose
    -------
    Assert the command fails closed on the exact condition the previous implementation shipped
    with: a session on a role holding data-modifying privileges over the tables being certified.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe with a writable role.
    capsys : pytest.CaptureFixture[str]
        Captures standard output, which must carry no report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command reports a verdict, or exits anything but FAILED.
    """
    # WHY : the role arranged is `carddemo_reference`, which is what the per-dataset command
    #   genuinely connects as. An obviously wrong value such as "postgres" would pass this test
    #   while leaving the real regression -- a plausible service role -- undetected.
    fake_aurora.arrange_rows("current_user", [("carddemo_reference",)])
    fake_aurora.arrange_rows("v_verification_row_counts", _conforming_row_count_rows())
    # WHY : Assumptions: the collaborator is replaced on `row_counts`, which DEFINES it, because
    #   the report command imports the name inside its handler. A function-local import resolves
    #   from the source module at call time, so a replacement bound onto `cli` is read by nothing.
    monkeypatch.setattr(
        row_counts,
        "open_reporting_connection",
        lambda: fake_aurora.connect(
            **{
                "host": "aurora.carddemo.invalid",
                "port": 5432,
                "dbname": "carddemo",
                "user": "carddemo_reference",
                "sslmode": "verify-full",
                "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
            }
        ),
    )

    exit_code = cli.main(["verify-row-count-report", "--sql-root", str(_DISTRIBUTION_ROOT)])

    assert exit_code == EXIT_FAILED
    # WHY : the ABSENCE of a rendered report is asserted, not merely the exit code. A verdict
    #   printed beside a non-zero status is the shape an operator skims and reads as a pass, and it
    #   would be a verdict reached under an authority that could have changed what it measured.
    assert "row count verification" not in capsys.readouterr().out
    assert not any("v_verification_row_counts" in sql for sql in fake_aurora.executed_sql())


class _SourceServingS3Client(_CapableS3Client):
    """Serve one source extract from object storage as well as accepting staged writes.

    Purpose
    -------
    Let a test drive the whole object-storage path of the staging and load commands -- the fetch of
    a registered extract from a landing prefix, and the write of the generation that follows it --
    without a network, an emulator or a local extract file.

    Attributes
    ----------
    reads : list of dict
        Every ``get_object`` request issued, in order, so a test can assert which key was fetched.
    """

    def __init__(self, bodies: Mapping[str, bytes]) -> None:
        """Create a client serving the given bodies, keyed by object key.

        Parameters
        ----------
        bodies : Mapping[str, bytes]
            The object bodies to serve, keyed by key. Any other key is reported as absent.

        Returns
        -------
        None
            Initialises the request logs.

        Raises
        ------
        None
        """
        super().__init__()
        self._bodies = dict(bodies)
        self.reads: list[dict[str, Any]] = []

    def get_object(self, **kwargs: Any) -> dict[str, Any]:
        """Return one served body as a readable stream with its declared length.

        Parameters
        ----------
        **kwargs : Any
            The get arguments; ``Bucket`` and ``Key`` are read.

        Returns
        -------
        dict[str, Any]
            A response carrying ``Body`` and ``ContentLength``, as the SDK's own does.

        Raises
        ------
        FakeServiceError
            With code ``NoSuchKey`` when nothing is served under the key, which is the shape the
            service reports absence in and the only shape the loader classifies as absence.
        """
        self.reads.append(dict(kwargs))
        key = str(kwargs.get("Key"))
        if key not in self._bodies:
            # WHY : Refactoring Rationale: this raised `FileNotFoundError`, described as standing
            #   in for the service's absent-object error. It did not stand in for it: the loader
            #   classifies absence by the service's own ERROR CODE, and a built-in with no error
            #   document carries none, so an absent object read through this double surfaced as a
            #   provider failure instead. That was invisible while the only absent-object read on
            #   the staging path was a listing, and it failed the moment the generation allocator
            #   began reading a replay record directly. It now raises the same shared service
            #   error, with the same code, as the base double this class extends.
            raise FakeServiceError("NoSuchKey", 404, f"no object is served at {key!r}")
        # WHY : Assumptions: the body is a stream rather than bytes, because that is the shape the
        #   SDK returns and the fetch calls ``read`` on it in bounded chunks. Returning bytes would
        #   let a fetch that forgot the read pass here and fail against the real service.
        return {"Body": io.BytesIO(self._bodies[key]), "ContentLength": len(self._bodies[key])}

    def head_object(self, **kwargs: Any) -> dict[str, Any]:
        """Answer the existence probe for a served body, or defer to the staged-write log.

        Parameters
        ----------
        **kwargs : Any
            The head arguments; ``Key`` is read, and ``Bucket`` is deferred to the base.

        Returns
        -------
        dict[str, Any]
            The declared length of a served body, carrying no recorded digest -- which is what an
            inbound export looks like. For any other key, whatever the base answers.

        Raises
        ------
        FakeServiceError
            From the base, when the key is neither served nor written.
        """
        # WHY : Assumptions: a served body answers the PROBE as well as the read, because the fetch
        #   this double exists to exercise probes before it transfers -- it refuses an object whose
        #   declared length or recorded digest disagrees with what arrives. A double that served
        #   `get_object` and reported the same key absent to `head_object` would report every
        #   registered extract as undelivered, which is a fault in the double and not in the fetch.
        # WHY : Assumptions: NO recorded digest is published, deliberately. Only this package's own
        #   staging step writes `carddemo-sha256`, so an inbound mainframe export carries none, and
        #   publishing one here would exercise a comparison the real delivery cannot make.
        key = str(kwargs.get("Key"))
        if key in self._bodies:
            self.heads.append(key)
            return {"Metadata": {}, "ContentLength": len(self._bodies[key])}
        return super().head_object(**kwargs)


def test_stage_dataset_fetches_the_registered_extract_from_object_storage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Stage from an ``s3://`` extract location, which is the only form a Fargate task can use.

    Purpose
    -------
    Pin the deployable source contract. This is the acceptance test for the staging step's own
    input: the orchestrator's task mounts no filesystem and its image ships no extract, so an
    extract location naming a local directory could never be satisfied by the step that runs it.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to supply the staging settings, the client and the extract location.

    Returns
    -------
    None
        Nothing; a fetch that did not happen, or a staged body that is not the fetched one, is
        reported as an assertion failure.

    Raises
    ------
    None
    """
    descriptor = seed_datasets.seed_dataset("users")
    payload = bytes(range(256)) * 5 + b"u" * (80 - (1280 % 80))
    key = f"source-extracts/{descriptor.source_object}"
    client = _SourceServingS3Client({key: payload})

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setenv(
        seed_datasets.STAGING_ROOT_VARIABLE, "s3://carddemo-datasets-test/source-extracts"
    )
    monkeypatch.setenv("CARDDEMO_BATCH_RUN_ID", "carddemo-daily-batch-2022-07-18")

    assert cli.main(["stage-dataset", "--dataset=users", "--business-date=2022-07-18"]) == EXIT_OK

    # WHY : Refactoring Rationale: the reads are filtered to those OUTSIDE the reservation
    #   bookkeeping prefix, where this compared the whole list. Reserving a generation now reads
    #   the execution's own allocation record directly -- one `GetObject` on
    #   `_generation-claims/run=.../family=USERS` -- instead of listing a claim prefix, so an
    #   unfiltered comparison would fail on a read that says nothing about which extract was
    #   fetched. Filtering by prefix rather than dropping entries by position keeps the assertion
    #   about the extract fetch, which is what this test is for.
    extract_reads = [
        read["Key"] for read in client.reads if not read["Key"].startswith(s3_stage._RUN_CLAIM_ROOT)
    ]
    assert extract_reads == [key]
    dataset_writes = [put for put in client.puts if put.get("IfNoneMatch") != "*"]
    assert len(dataset_writes) == 1
    written = dataset_writes[0]
    assert written["Key"] == f"auth/users/dt=2022-07-18/gen=0001/{descriptor.source_object}"
    # WHY : Assumptions: the staged bytes are asserted against the FETCHED payload rather than
    #   against a length, because a fetch that decoded, re-encoded or newline-translated on the way
    #   through would keep the length of this particular payload and change its content.
    assert written["Body"] == payload
    # WHY : Assumptions: the record-length check is still applied on this path -- the payload is a
    #   multiple of the 80-byte security record precisely so it passes -- because the source is the
    #   registered one, and a fetched extract has exactly the geometry evidence a local one does.
    assert written["ContentLength"] == len(payload)


def test_stage_dataset_reports_a_missing_object_as_a_failed_step(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report the failed tier when the extract location resolves but the object is absent."""
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: _SourceServingS3Client({}))
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, "s3://carddemo-datasets-test/extracts")

    # WHY : Assumptions: this is the FAILED tier and not the fatal one, which is the same split the
    #   filesystem form already makes. The deployment IS configured -- it named a location that
    #   parsed -- so what is missing is the step's input rather than its configuration.
    assert (
        cli.main(["stage-dataset", "--dataset=accounts", "--business-date=2022-07-18"])
        == EXIT_FAILED
    )


def test_stage_dataset_reports_a_malformed_extract_location_as_fatal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse an extract location carrying the object scheme with no bucket, as a fault."""
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, "s3://")

    # WHY : Assumptions: a malformed URI is refused rather than treated as a relative directory
    #   named "s3:", which is what a bare filesystem fallback would do -- and it would then report
    #   a missing file for a location the operator plainly meant as a bucket.
    assert (
        cli.main(["stage-dataset", "--dataset=accounts", "--business-date=2022-07-18"])
        == EXIT_FATAL
    )


def _captured_handler(recorded: dict[str, Any]) -> Any:
    """Build a handler that records the arguments it was called with and reports success.

    Parameters
    ----------
    recorded : dict[str, Any]
        Mapping the handler writes ``dataset``, ``source`` and ``encoding`` into.

    Returns
    -------
    Any
        A handler suitable for monkeypatching over one of the four record subcommands.

    Raises
    ------
    None
    """

    def _handler(arguments: Any) -> int:
        """Record the resolved selector, source, encoding and the bytes at that source."""
        recorded["dataset"] = arguments.dataset
        recorded["source"] = arguments.source
        recorded["encoding"] = arguments.encoding
        # WHY : Assumptions: the source is READ here rather than merely recorded, because that is
        #   the property a handler actually depends on -- a resolved path that no longer holds the
        #   extract by the time the handler runs would satisfy every other assertion in these
        #   cases and fail in production.
        source = Path(arguments.source)
        recorded["fetched_bytes"] = source.read_bytes() if source.is_file() else None
        return EXIT_OK

    return _handler


@pytest.mark.parametrize(
    ("command", "handler_name"),
    [
        ("load-dataset", "_load_dataset"),
        ("verify-row-counts", "_verify_row_counts"),
        ("verify-checksum", "_verify_checksum"),
        ("verify-money-parity", "_verify_money_parity"),
    ],
)
def test_the_record_commands_accept_the_orchestrators_dataset_token(
    command: str, handler_name: str, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Derive the layout, the extract and the seed form from the one token the orchestrator holds.

    Purpose
    -------
    Pin the invocation the batch chain's dataset step issues for the load and for each
    verification pass. Before this contract existed the load required a layout name, a path and a
    seed form -- none of which a Step Functions branch can compose -- so it was invoked by nothing
    and a cutover staged ten extracts while leaving every target table empty.

    Parameters
    ----------
    command : str
        The subcommand under test.
    handler_name : str
        The module attribute holding that subcommand's handler, replaced so this case measures the
        resolution rather than a database round trip.
    tmp_path : Path
        Scratch directory used as a filesystem extract location.
    monkeypatch : pytest.MonkeyPatch
        Used to replace the handler and to supply the extract location.

    Returns
    -------
    None
        Nothing; a selector, source or encoding that was not derived is reported as a failure.

    Raises
    ------
    None
    """
    descriptor = seed_datasets.seed_dataset("card_xref")
    staging_root = tmp_path / "extracts"
    staging_root.mkdir()
    (staging_root / descriptor.source_object).write_bytes(b"x" * 50)

    recorded: dict[str, Any] = {}
    monkeypatch.setattr(cli, handler_name, _captured_handler(recorded))
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(staging_root))

    assert cli.main([command, "--dataset=card_xref"]) == EXIT_OK

    # WHY : Assumptions: the handler sees the LAYOUT name, because that is what it looks the reader
    #   and the target table up by. The token is the orchestrator's vocabulary and stops at the
    #   resolution; carrying it further would mean teaching two more registries to speak it.
    assert recorded["dataset"] == descriptor.layout_name
    assert recorded["source"] == str(staging_root / descriptor.source_object)
    assert recorded["encoding"] == seed_datasets.SEED_SOURCE_ENCODING


def test_load_dataset_fetches_the_registered_extract_from_object_storage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Resolve the load's extract from an ``s3://`` location, as the staging step's own does."""
    descriptor = seed_datasets.seed_dataset("cards")
    payload = b"c" * 150
    key = f"source-extracts/{descriptor.source_object}"
    client = _SourceServingS3Client({key: payload})

    recorded: dict[str, Any] = {}
    monkeypatch.setattr(cli, "_load_dataset", _captured_handler(recorded))
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setenv(
        seed_datasets.STAGING_ROOT_VARIABLE, "s3://carddemo-datasets-test/source-extracts"
    )

    assert cli.main(["load-dataset", "--dataset=cards"]) == EXIT_OK

    assert [read["Key"] for read in client.reads] == [key]
    # WHY : Assumptions: the fetched copy is asserted to have been readable AT THE PATH the handler
    #   was given, and to be gone afterwards. Both halves matter: the first is what the reader
    #   needs, and the second is what keeps a retried step from inheriting a partial copy.
    fetched = Path(recorded["source"])
    assert recorded["fetched_bytes"] == payload
    assert not fetched.exists()


def test_a_layout_name_still_requires_its_source_and_encoding() -> None:
    """Refuse a layout name given without the two options it cannot derive, naming both."""
    # WHY : Assumptions: a layout name derives NOTHING, because a layout is not bound to one
    #   extract -- REJECT and INTTRAN are produced by the pipeline and shipped by nothing -- so
    #   there is no location to infer and inferring the seed form alone would leave a half-derived
    #   invocation whose missing half failed later.
    # WHY : Refactoring Rationale: the layout exercised here is `REJECT` where it was `XREF`, and
    #   the substitution is forced by a measurement rather than chosen. Every one of the eleven
    #   LOADABLE layouts is now carried by the seed registry under both spellings, so a loadable
    #   layout name always resolves to a source object and this refusal is unreachable for one --
    #   `--dataset XREF` and `--dataset card_xref` name the same dataset and must behave
    #   identically, which is the property the unified vocabulary exists to give. `REJECT` is a
    #   layout the pipeline PRODUCES and no extract ships, so it is the case the refusal was
    #   written for: there is no location to infer and the message must name what is missing.
    # WHY : Assumptions: the command is a verification pass rather than the load, because the
    #   refusal is issued by the shared selector resolver before any handler runs, while
    #   `load-dataset` would additionally need a load target that `REJECT` deliberately has not.
    #   Asserting through a verb that cannot reach its handler either way would leave it ambiguous
    #   which of the two refusals had answered.
    assert cli.main(["verify-checksum", "--dataset=REJECT"]) == EXIT_USAGE
    # WHY : Assumptions: the two half-supplied forms are refused as well as the empty one, and each
    #   names the half that is missing. Supplying one of the pair is the shape that fails LATER
    #   otherwise -- a source with no seed form, or a seed form with nowhere to read it from.
    assert cli.main(["verify-checksum", "--dataset=REJECT", "--encoding=ebcdic"]) == EXIT_USAGE
    assert cli.main(["verify-checksum", "--dataset=REJECT", "--source=/absent"]) == EXIT_USAGE


def test_a_dataset_token_without_an_extract_location_is_fatal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report the fatal tier when a token is given and the deployment names no extract location."""
    monkeypatch.delenv(seed_datasets.STAGING_ROOT_VARIABLE, raising=False)
    assert cli.main(["load-dataset", "--dataset=accounts"]) == EXIT_FATAL


_MONEY_REPORT_SOURCES: Final[tuple[tuple[str, Path], ...]] = (
    ("ACCOUNT", _ASCII_DIRECTORY / "acctdata.txt"),
    ("DALYTRAN", _ASCII_DIRECTORY / "dailytran.txt"),
    ("TCATBAL", _CATEGORY_BALANCE_SEED),
    ("DISGROUP", _ASCII_DIRECTORY / "discgrp.txt"),
)


def _money_extract_options(
    sources: tuple[tuple[str, Path], ...] = _MONEY_REPORT_SOURCES,
) -> list[str]:
    """Render the ``--extract`` options for a set of layout-and-path pairings.

    Parameters
    ----------
    sources : tuple[tuple[str, pathlib.Path], ...]
        The pairings to render, defaulting to all four money-bearing datasets.

    Returns
    -------
    list[str]
        The option list, ready to splice into an argument vector.

    Raises
    ------
    None
        Rendering strings cannot fail.
    """
    options: list[str] = []
    for layout_name, path in sources:
        options.extend(("--extract", f"{layout_name}={path}"))
    return options


def _conforming_money_total_rows(
    sources: tuple[tuple[str, Path], ...] = _MONEY_REPORT_SOURCES,
    *,
    encoding: str = "ascii",
    a_cent_out: tuple[str, str] | None = None,
    negative_shift: Mapping[tuple[str, str], int] | None = None,
) -> list[tuple[object, ...]]:
    """Build the money-total report a database holding a correct load would return.

    Purpose
    -------
    Give the command a result set it can judge. The pass refuses a report that omits any declared
    money column, so a partial fixture would fail for coverage rather than for the property under
    test.

    Parameters
    ----------
    sources : tuple[tuple[str, pathlib.Path], ...]
        The extracts whose totals the report should agree with.
    encoding : str
        The form to read those extracts in.
    a_cent_out : tuple[str, str] | None
        The ``(table, column)`` pair whose reported total should be one cent above the source's,
        or ``None`` for a report that agrees everywhere.
    negative_shift : Mapping[tuple[str, str], int] | None
        Per ``(table, column)`` adjustments to the reported count of strictly-negative rows, with
        every total left exactly as the source measures it. This is what expresses a COMPENSATING
        sign swap: two records whose signs are exchanged leave the total untouched, so the only
        signature left is the negative-row count, and it is the one thing the per-dataset money
        pass cannot see.

    Returns
    -------
    list[tuple[object, ...]]
        One eight-value row per declared money column, in the declaration order the query's own
        ordinal produces.

    Raises
    ------
    None
        Reading committed extracts through the published source-side pass cannot fail here.
    """
    # WHY : Trade-offs: the target side is built from `read_source_totals`, which is the pass's own
    #   source-side measurement, so this fixture agrees with the arithmetic rather than
    #   independently re-deriving it. That is deliberate and its limits are stated: the arithmetic
    #   itself -- zoned overpunch decoding, exact totalling, negative counting -- is covered by
    #   test_verification.py against hand-built vectors, and what these tests exist to cover is the
    #   COMMAND: that it parses its pairings, opens a session it proves is the reporting role, reads
    #   the shipped query, judges every declared column and returns the documented status. Totalling
    #   two hundred account records a second time here would test the totals twice and the command
    #   once.
    measured = read_source_totals(
        SourceExtract(layout_name=name, path=path, encoding=encoding) for name, path in sources
    )
    rows: list[tuple[object, ...]] = []
    for key, column in money_columns().items():
        measurement = measured.get(key)
        total = Decimal("0.00") if measurement is None else measurement.total
        negatives = 0 if measurement is None else measurement.negative_rows
        row_count = 0 if measurement is None else measurement.records
        if a_cent_out == key:
            total += Decimal("0.01")
        if negative_shift and key in negative_shift:
            # WHY : Assumptions: the shift is clamped at zero because a negative COUNT is not a
            #   report any database could return, and a fixture that produced one would test the
            #   pass against an input it is entitled to refuse rather than against a swap.
            negatives = max(0, negatives + negative_shift[key])
        rows.append(
            (
                column.target_table,
                column.money_column,
                column.field.name,
                # WHY : Assumptions: the picture and the SQL type are COMPOSED from the field's own
                #   declared digits rather than written out, because the row refuses a picture that
                #   declares no sign or no two decimal places and a type that is not NUMERIC at
                #   scale two. Composing them from the descriptor keeps this fixture conforming for
                #   whichever column it is built for instead of hard-coding nine spellings.
                f"S9({column.field.int_digits:02d})V99",
                f"NUMERIC({column.field.int_digits + 2},2)",
                row_count,
                total,
                negatives,
            )
        )
    return rows


def _bind_money_reporting_connection(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    role: str,
) -> list[object]:
    """Point the money report's connection seam at the double, as a session on one role.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher, scoped to the calling test.
    fake_aurora : FakeAuroraDatabase
        The recording double every patched call returns a connection from.
    role : str
        The login role the connection is opened as.

    Returns
    -------
    list[object]
        A list the patched opener appends each connection to, so a test can assert it was closed.

    Raises
    ------
    None
    """
    opened: list[object] = []

    def _open() -> object:
        """Open a recording connection in place of one on the reporting role.

        Returns
        -------
        object
            A recording connection from the double.

        Raises
        ------
        None
        """
        connection = fake_aurora.connect(
            **{
                "host": "aurora.carddemo.invalid",
                "port": 5432,
                "dbname": "carddemo",
                "user": role,
                "sslmode": "verify-full",
                "sslrootcert": "/nonexistent/synthetic-test-anchor.pem",
            }
        )
        opened.append(connection)
        return connection

    # WHY : Assumptions: the collaborator is replaced on the module that DEFINES it rather
    #   than on `cli`, because the command imports the name inside the handler -- a
    #   deliberate deferral recorded in the module header, so that a defect in the loader or
    #   in a verification pass cannot fail the commands that touch neither. A function-local
    #   import resolves its name from the source module's namespace at call time, so binding
    #   a replacement onto `cli` would be read by nothing and the double would go unused --
    #   the test would then exercise the real driver and fail for an unrelated reason.
    # Trade-offs: patching the source module is broader than patching one command's view of
    #   it. That is accepted because the alternative -- reinstating a module-level
    #   indirection on `cli` purely so a test could rebind it -- would put the deferral back
    #   at module scope and undo the isolation it exists to provide.
    monkeypatch.setattr(money_parity, "open_reporting_connection", _open)
    return opened


def test_the_money_total_report_command_verifies_every_declared_money_column(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Run the whole-migration money pass through the command, over the query file as shipped.

    Purpose
    -------
    Prove the third verification pass is REACHABLE at the level a cutover needs it. The pass was
    delivered and unreached: nothing in the distribution called ``read_source_totals`` or
    ``verify_money_totals``, so the only money command compared one dataset at a time as the schema
    owner, and the whole-migration half could be run only by hand with psql -- which exits zero on
    a report full of mismatches.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then the report.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command does not verify, does not probe the session first, does not read the shipped
        query, does not report every declared column, or leaves the connection open.
    """
    fake_aurora.arrange_rows("current_user", [(money_reporting_role(),)])
    # WHY : Assumptions: the rows are arranged against a fragment of the SHIPPED query -- the
    #   aggregate view it reads -- rather than against text this test supplies, because the command
    #   takes no query argument. A query rewritten to read a different relation would no longer
    #   match and the report would come back empty.
    fake_aurora.arrange_rows("v_verification_money_totals", _conforming_money_total_rows())
    opened = _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            *_money_extract_options(),
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_OK
    rendered = capsys.readouterr().out
    assert "money total verification PASSED" in rendered
    assert f"money total verification: {len(money_columns())} line(s)" in rendered
    # WHY : the one admitted absence is asserted PRESENT and unsourced rather than silently
    #   tolerated: ledger.transactions ships no extract, so its line must read NO_SOURCE while
    #   every other line matches. A report that simply omitted it would be refused for coverage.
    assert "NO_SOURCE" in rendered
    assert "ledger.transactions" in rendered
    executed = fake_aurora.executed_sql()
    # WHY : the first two statements are asserted in order, as for the row-count command above:
    #   `SET TRANSACTION READ ONLY` leads so the transaction is read-only for every statement
    #   including the probe, and the probe follows inside it -- because the guard's whole value is
    #   running before the query does. A probe made afterwards would satisfy an exit-code assertion
    #   while the report had already run under whatever authority it had.
    assert "READ ONLY" in executed[0]
    assert "current_user" in executed[1]
    assert any("v_verification_money_totals" in sql for sql in executed[2:])
    assert opened and all(getattr(each, "closed", False) for each in opened)


def test_the_money_total_report_command_fails_when_one_column_is_a_cent_out(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Fail the whole report, and name the column, when one loaded total differs by a cent.

    Purpose
    -------
    The tolerance is zero by design: a sign overpunch read wrongly moves a total by twice the
    affected rows and a misplaced decimal point by a factor of a hundred, but a single mis-decoded
    digit moves it by cents -- and that is the one a reader is least likely to find by eye.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then the perturbed report.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command reports success, or does not name the mismatching column.
    """
    perturbed = ("account.accounts", "curr_bal")
    fake_aurora.arrange_rows("current_user", [(money_reporting_role(),)])
    fake_aurora.arrange_rows(
        "v_verification_money_totals", _conforming_money_total_rows(a_cent_out=perturbed)
    )
    _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            *_money_extract_options(),
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_FAILED
    rendered = capsys.readouterr().out
    assert "money total verification FAILED" in rendered
    assert "MISMATCH" in rendered
    assert f"{perturbed[0]}.{perturbed[1]}" in rendered
    # WHY : Trade-offs: the report is PRINTED on failure, unlike the refusals below. A total is an
    #   aggregate over a whole column rather than one record's value, and the two totals side by
    #   side are the diagnosis -- which is why this pass renders them where the checksum pass
    #   renders no value at all.
    # WHY : Refactoring Rationale: the summary word asserted here is `failing` where it was
    #   `mismatched`. The pass renamed it deliberately: a baseline-free money column is now held to
    #   the expected-empty rule, so a line can FAIL with no source total to have disagreed with, and
    #   calling that count "mismatched" would name a comparison that never happened. The spelling
    #   now matches the row-count report's, so both reports an operator reads in one run speak one
    #   vocabulary. The per-line `MISMATCH` verdict asserted above is unchanged -- it is still a
    #   genuine disagreement -- so this case still distinguishes a mismatched line from an
    #   unmeasurable one.
    assert "1 failing" in rendered
    assert "MISMATCH" in rendered


def test_the_money_total_report_command_fails_on_a_sign_only_disagreement(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Fail on a negative-row count that differs while every total still agrees exactly.

    Purpose
    -------
    Assert the one property that makes this command worth having beside `verify-money-parity`. A
    compensating sign swap leaves every total unchanged, so a pass comparing totals alone reports
    agreement. The signature that remains is the count of strictly-negative rows, and this is the
    only pass that reads it. The shipped daily-transaction extract carries fifty negative amounts
    among three hundred records, so a report claiming none is exactly the shape a mis-decoded
    overpunch produces -- and exactly the shape the totals cannot betray.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the money report's own reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then the sign-shifted report.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered report, which must carry the discrepancy.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command exits OK, or the report does not name the discrepancy as a SIGN one rather
        than as a plain mismatch.
    """
    swapped = ("ledger.daily_transactions", "amount")
    fake_aurora.arrange_rows("current_user", [(money_reporting_role(),)])
    fake_aurora.arrange_rows(
        "v_verification_money_totals",
        _conforming_money_total_rows(negative_shift={swapped: -50}),
    )
    _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            *_money_extract_options(),
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_FAILED
    rendered = capsys.readouterr().out
    assert "money total verification FAILED" in rendered
    # WHY : the sign-discrepancy count is asserted to be exactly one, not merely non-zero. A pass
    #   that reported this line as a plain mismatch would still fail the exit-code assertion above
    #   while sending an operator to look for one wrong record instead of at the overpunch
    #   convention, which is the whole reason the two counts are kept apart.
    assert "1 sign discrepancies" in rendered
    assert f"{swapped[0]}.{swapped[1]}" in rendered


def test_the_money_total_report_command_refuses_a_writable_session(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Refuse to report at all when the session handed to the pass can write.

    Purpose
    -------
    A verification step must not be able to modify what it verifies, and the role is CHECKED with
    the server rather than assumed -- so this asserts the same fail-closed behaviour the row-count
    report has, on the money half.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe with a writable role.
    capsys : pytest.CaptureFixture[str]
        Captures standard output, which must carry no report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command reports a verdict, or exits anything but FAILED.
    """
    # WHY : `carddemo_batch` is the role the baseline's own money query named before the aggregate
    #   views existed, and it is write-capable over the ledger. An obviously wrong value such as
    #   "postgres" would pass this test while leaving that real regression undetected.
    fake_aurora.arrange_rows("current_user", [("carddemo_batch",)])
    fake_aurora.arrange_rows("v_verification_money_totals", _conforming_money_total_rows())
    _bind_money_reporting_connection(monkeypatch, fake_aurora, "carddemo_batch")

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            *_money_extract_options(),
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_FAILED
    assert "money total verification" not in capsys.readouterr().out
    assert not any("v_verification_money_totals" in sql for sql in fake_aurora.executed_sql())


def test_the_money_total_report_command_refuses_a_seeded_column_left_unmeasured(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Refuse a run that omits an extract a declared money column has, rather than passing it.

    Purpose
    -------
    A column left unmeasured would be reported as not comparable and would pass, so an operator who
    simply forgot one extract could read five unchecked columns as a clean bill of health. That is
    the exact shape of false assurance this pass exists to remove, and the refusal is what removes
    it -- which makes it worth a test of its own rather than being left to the pass's docstring.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then a full report.
    capsys : pytest.CaptureFixture[str]
        Captures standard output, which must carry no report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the incomplete run reports a verdict, or exits anything but FAILED.
    """
    withheld = _MONEY_REPORT_SOURCES[0]
    remaining = _MONEY_REPORT_SOURCES[1:]
    fake_aurora.arrange_rows("current_user", [(money_reporting_role(),)])
    fake_aurora.arrange_rows("v_verification_money_totals", _conforming_money_total_rows())
    _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            *_money_extract_options(remaining),
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_FAILED
    assert withheld[0] == "ACCOUNT"
    assert "money total verification" not in capsys.readouterr().out


def test_the_money_total_report_command_reads_a_pairing_that_declares_its_own_form(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Read one extract in a form of its own while the rest take the command's declared form.

    Purpose
    -------
    The two shipped trees do not agree on file naming or on every value, and one of the documented
    divergences changes money: the disclosure-group ``DEFAULT`` row carries a rate of 15.00 in the
    mainframe-character-set extract and 0.00 in its text twin. An operator loading from one tree
    must be able to total against that same tree, per extract, which is why a pairing may carry a
    third component.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double answering the session probe and then the report.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the override is ignored -- which the differing rate makes visible, because a report built
        from one tree cannot verify against totals taken over the other.
    """
    ascii_totals = read_source_totals(
        [
            SourceExtract(
                layout_name="DISGROUP",
                path=_ASCII_DIRECTORY / "discgrp.txt",
                encoding="ascii",
            )
        ]
    )
    ebcdic_totals = read_source_totals(
        [SourceExtract(layout_name="DISGROUP", path=_DISCLOSURE_GROUP_EXTRACT, encoding="ebcdic")]
    )
    rate = ("reference.disclosure_groups", "interest_rate")
    # WHY : the premise of this test is asserted rather than assumed: if the two trees ever agreed
    #   on this rate, the test would still pass while proving nothing about the override.
    assert ascii_totals[rate].total != ebcdic_totals[rate].total

    rows = _conforming_money_total_rows(_MONEY_REPORT_SOURCES[:3])
    # WHY : the disclosure-group line is rebuilt from the MAINFRAME-CHARACTER-SET reading, so the
    #   report agrees with the tree the pairing names and disagrees with the command's own default
    #   form. Only an honoured override can verify it.
    rebuilt: list[tuple[object, ...]] = []
    for row in rows:
        if (row[0], row[1]) == rate:
            measurement = ebcdic_totals[rate]
            rebuilt.append(
                (
                    *row[:5],
                    measurement.records,
                    measurement.total,
                    measurement.negative_rows,
                )
            )
        else:
            rebuilt.append(row)

    fake_aurora.arrange_rows("current_user", [(money_reporting_role(),)])
    fake_aurora.arrange_rows("v_verification_money_totals", rebuilt)
    _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())

    # WHY : the disclosure-group pairing carries the THIRD component and the other three do not,
    #   so this one argument vector exercises both the per-pairing override and the command's own
    #   default form in a single run.
    options = _money_extract_options(_MONEY_REPORT_SOURCES[:3])
    options.extend(("--extract", f"DISGROUP={_DISCLOSURE_GROUP_EXTRACT}=ebcdic"))

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            *options,
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_OK
    assert "money total verification PASSED" in capsys.readouterr().out


@pytest.mark.parametrize(
    ("pairing", "expected"),
    [
        # WHY : the first three are MISTYPED command lines -- no separator, no layout, no path --
        #   and each is reported as usage, which is the status an orchestrator distinguishes from a
        #   load that did not verify.
        ("ACCOUNT", EXIT_USAGE),
        ("=/tmp/nowhere.txt", EXIT_USAGE),
        ("ACCOUNT=", EXIT_USAGE),
        # WHY : the fourth is a well-formed pairing whose THIRD component is not one of the two
        #   admitted forms, so it is refused by the extract itself rather than by the parsing --
        #   FAILED, not usage. The split is bounded at two separators so that a path containing an
        #   equals sign survives, which is exactly why everything after the second one arrives as
        #   the declared form: `path=ascii=extra` yields the form `ascii=extra`, and the closed set
        #   refuses it. The boundary between the two statuses is asserted here so that it is a
        #   decision on record rather than an accident of the split.
        ("ACCOUNT=path=ascii=extra", EXIT_FAILED),
    ],
)
def test_the_money_total_report_command_refuses_a_malformed_pairing_before_connecting(
    pairing: str,
    expected: int,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
) -> None:
    """Refuse a mistyped or unreadable pairing, with the documented status and no session opened.

    Purpose
    -------
    A mistyped command line and a load that does not verify are different outcomes that an
    orchestrator branches on differently, so the pairings are resolved and refused before any
    connection is opened -- which also means a typo cannot consume a database session.

    Parameters
    ----------
    pairing : str
        The offending option value.
    expected : int
        The status this pairing must produce.
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam, which must never be reached.
    fake_aurora : FakeAuroraDatabase
        Recording double, which must record nothing.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the pairing is accepted, if the status differs from the documented one, or if a
        connection was opened before the refusal.
    """
    opened = _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            "--extract",
            pairing,
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == expected
    assert not opened
    assert not fake_aurora.executed_sql()


def test_the_money_total_report_command_refuses_a_layout_named_twice(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Refuse two extracts naming one layout rather than silently reading one of them.

    Purpose
    -------
    The pass totals at most one extract per layout, so two ``--extract`` values for one layout
    means the operator believes both are being read -- and a verdict reached over half the input
    they supplied is worse than no verdict. The refusal is asserted to happen before any session is
    opened, for the same reason the malformed pairings are: a mistyped command line must not consume
    a database session.

    Refactoring Rationale: the status asserted is USAGE, where the case this property came
    from asserted FAILED. A layout named twice is a mistyped command line rather than a load that
    did not verify, and this module already draws that line explicitly in the pairing parser and in
    the parametrized refusal above it. The property -- refuse, never choose one silently -- is
    unchanged; only the tier follows the taxonomy an orchestrator here branches on.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam, which must never be reached.
    fake_aurora : FakeAuroraDatabase
        Recording double, which must record nothing.
    capsys : pytest.CaptureFixture[str]
        Captures standard output, which must carry no report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the repeated layout is accepted, if a session is opened, or if a report is rendered.
    """
    opened = _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())
    # WHY : Assumptions: the pairing is taken from the SAME declaration the agreeing run uses, so a
    #   dataset renamed in that tuple cannot leave this case naming a path that no longer ships and
    #   passing for the wrong reason -- the refusal must come from the repetition, not from an
    #   unreadable file.
    layout_name, extract_path = _MONEY_REPORT_SOURCES[0]
    repeated = f"{layout_name}={extract_path}"

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            "--extract",
            repeated,
            "--extract",
            repeated,
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_USAGE
    assert "money total verification" not in capsys.readouterr().out
    assert not opened
    assert not fake_aurora.executed_sql()


def test_the_money_total_report_command_refuses_a_layout_with_no_money_column(
    monkeypatch: pytest.MonkeyPatch, fake_aurora: FakeAuroraDatabase
) -> None:
    """Refuse a pairing naming a layout that feeds no money column, before opening a session.

    Purpose
    -------
    Totalling such an extract would contribute nothing to the report, so naming one is a mistake
    worth reporting rather than absorbing -- and it is caught while the pairings are resolved,
    which is why no connection is opened.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the reporting-connection seam, which must never be reached.
    fake_aurora : FakeAuroraDatabase
        Recording double, which must record nothing.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the pairing is accepted, or a connection was opened before the refusal.
    """
    opened = _bind_money_reporting_connection(monkeypatch, fake_aurora, money_reporting_role())

    exit_code = cli.main(
        [
            "verify-money-total-report",
            "--encoding",
            "ascii",
            "--extract",
            f"TRANTYPE={_ASCII_DIRECTORY / 'trantype.txt'}",
            "--sql-root",
            str(_DISTRIBUTION_ROOT),
        ]
    )

    assert exit_code == EXIT_FAILED
    assert not opened
    assert not fake_aurora.executed_sql()


_REFRESH_TOKEN: Final[str] = "transaction_types"


# WHY : ⚠️ Refactoring Rationale: the fragment that keys the double's canned read-back was
#   `ORDER BY "type_cd"` and is now the FROM clause. The checksum pass no longer asks the server to
#   order a keyed read-back -- `_paired_records` sorts both sides in this process on the rendered
#   key, so a server-side ordering would be redundant and would put the engine's collation back
#   into the comparison -- so a fragment naming the ordering matched nothing, the double answered
#   with no rows, and the refresh sequence failed at its checksum step against a correct load. The
#   projected COLUMN LIST is the part of the statement that identifies the read-back regardless of
#   how it is ordered, and unlike the FROM clause it does not also match the row-count pass's own
#   aggregate over the same table -- which, keyed on the FROM clause alone, received the checksum's
#   canned rows and failed one step earlier.
_READ_BACK_FRAGMENT: Final[str] = 'SELECT "type_cd", "description" FROM'


class _SourcePrefixS3Client(_CapableS3Client):
    """Stand in for an S3 client that also SERVES the source-extract prefix.

    Purpose
    -------
    Let the refresh be driven end to end against one client, because it both reads an extract out
    of the provisioned source prefix and writes the staged generation back into the same bucket.
    Splitting those across two doubles would let a test pass while the command reached for a
    second client the deployment does not give it.

    Attributes
    ----------
    objects : dict[str, bytes]
        Object bodies keyed by full object key, which the reads are served from.
    gets : list[dict]
        Every ``get_object`` request issued, in order.
    """

    def __init__(self) -> None:
        """Create an empty object store beside the inherited request log."""
        super().__init__()
        self.objects: dict[str, bytes] = {}
        self.gets: list[dict[str, Any]] = []

    def get_object(self, **kwargs: Any) -> dict[str, Any]:
        """Serve one stored body with the length the service publishes for it.

        Parameters
        ----------
        **kwargs : Any
            The request keywords, read for ``Key``.

        Returns
        -------
        dict[str, Any]
            A response carrying ``Body``, ``ContentLength`` and an empty ``Metadata``.

        Raises
        ------
        FakeServiceError
            With code ``NoSuchKey`` when the key was never stored, which is the shape the service
            reports absence in and the only shape the loader classifies as absence.
        """
        self.gets.append(kwargs)
        key = str(kwargs["Key"])
        if key not in self.objects:
            # WHY : Refactoring Rationale: an absent key raised the `KeyError` of the underlying
            #   dictionary, described as what an absent extract looks like from here. It is not
            #   what one looks like from the loader: absence is classified by the service's own
            #   ERROR CODE, and a `KeyError` carries no error document, so an absent object read
            #   through this double surfaced as a provider failure. That went unnoticed while every
            #   absent-object read on the staging path was a listing, and it failed the moment the
            #   generation allocator began reading its replay record directly. It now raises the
            #   shared service error every other double in this suite raises, with the same code.
            raise FakeServiceError("NoSuchKey", 404, f"no object is stored at {key!r}")
        body = self.objects[key]
        return {"Body": io.BytesIO(body), "ContentLength": len(body), "Metadata": {}}


def _transaction_type_extract() -> bytes:
    """Read the shipped transaction-type extract the refresh tests serve from the bucket.

    Purpose
    -------
    Serve the REAL exported extract rather than bytes this file composed, so the decode, the
    geometry check and the digest are exercised over the delivery an operator actually syncs.

    Returns
    -------
    bytes
        The extract's bytes, exactly as shipped.

    Raises
    ------
    AssertionError
        If the shipped file is not a whole number of records at its declared length, which would
        mean the fixture and the registry disagree before any command ran.
    """
    payload = (_EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.TRANTYPE.PS").read_bytes()
    assert len(payload) % layouts.reclen_of("TRANTYPE") == 0
    return payload


def _expected_transaction_type_rows() -> list[tuple[object, ...]]:
    """Build the read-back rows a correct load of the transaction-type extract would produce.

    Purpose
    -------
    Give the checksum pass a target side derived INDEPENDENTLY of the pass itself -- decoded from
    the same shipped extract, projected through the target's own declarations, in the read-back's
    own key order -- so agreement is evidence the two halves of the comparison were built the same
    way rather than evidence the fixture was copied from the comparison.

    Returns
    -------
    list[tuple[object, ...]]
        One two-column row per record, ordered by the projected columns as the read-back orders
        them.

    Raises
    ------
    AssertionError
        If the decoded records are not already in the read-back's order, which would mean the
        rows and the ordering disagree and the comparison would fail for the wrong reason.
    """
    target = target_for("TRANTYPE")
    reader = RecordReader(layouts.layout("TRANTYPE"))
    prepared = [
        prepare_record(target, record, None)
        for record in reader.read_ebcdic(_EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.TRANTYPE.PS")
    ]
    rows = [tuple(record[field] for field in target.comparable_fields()) for record in prepared]
    assert rows == sorted(rows), (
        "the shipped extract is expected to be in the read-back's own key order, so a difference"
        " reported by the comparison is a difference in the data rather than in the ordering"
    )
    return rows


def _arrange_refresh_answers(
    fake_aurora: FakeAuroraDatabase, rows: list[tuple[object, ...]]
) -> None:
    """Arrange the database answers a verified refresh of the transaction-type dataset needs.

    Purpose
    -------
    State, in one place, what a database holding a correct load would say: the row count the two
    sides agree on, the read-back rows, and the description of the projection they came from.

    Parameters
    ----------
    fake_aurora : FakeAuroraDatabase
        The recording double the answers are arranged on.
    rows : list[tuple[object, ...]]
        The read-back rows, as :func:`_expected_transaction_type_rows` derives them.

    Returns
    -------
    None
        Arranging the answers is the effect.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the fragment is `COUNT(*) FROM` and not `COUNT(*)`, because the bulk
    #   loader's same-key content-conflict probe ALSO selects `count(*)` -- as the leading
    #   total of `count(*), count(*) FILTER (...)`. A bare `COUNT(*)` fragment matches that
    #   probe too, and the most recently arranged matching fragment wins, so the probe would
    #   be answered with this pass's single-column row and refused for the wrong arity: the
    #   refresh then stopped at the load with a diagnostic about a misbehaving cursor. The
    #   probe's own projection puts `) FROM` after its filter, so `COUNT(*) FROM` selects the
    #   row-count query alone and leaves the probe to conftest's arity-derived default.
    fake_aurora.arrange_rows("COUNT(*) FROM", [(len(rows),)])
    fake_aurora.arrange_rows(_READ_BACK_FRAGMENT, rows)
    # WHY : ⚠️ Refactoring Rationale: this arrangement used to declare the cursor's
    #   column DESCRIPTION as well as its rows, for a read-back that resolved each column's
    #   comparison form from the driver's type identifiers. The surviving verifier derives
    #   those rules from the copybook layout and narrows them by the rows themselves, so a
    #   description arranged here would be arranged for nothing.


def test_refresh_dataset_fetches_stages_loads_and_verifies_one_dataset(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Run one dataset's whole cutover from the orchestrator's own two arguments.

    Purpose
    -------
    This is the acceptance test for the defect being fixed. It supplies only what the nightly Map
    branch supplies -- a dataset token and a business date -- and asserts every stage of the
    refresh actually happened: the extract was read from the provisioned source prefix, its bytes
    were staged verbatim under a reserved generation, the records were loaded into the schema that
    owns the table, and all three verification passes ran over the load.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the staging, client and database seams and to set the execution token.
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the per-step report lines the passes print.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refresh does not succeed, reads from a key other than the provisioned one, stages
        bytes other than the extract's, loads through a schema other than the owning one, or skips
        any of the three verification passes.
    """
    payload = _transaction_type_extract()
    descriptor = seed_datasets.seed_dataset(_REFRESH_TOKEN)
    client = _SourcePrefixS3Client()
    source_key = f"migration/source/EBCDIC/{descriptor.source_object}"
    client.objects[source_key] = payload
    rows = _expected_transaction_type_rows()

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setenv("CARDDEMO_BATCH_RUN_ID", "carddemo-daily-batch-2022-07-18")
    requested = _bind_database(monkeypatch, fake_aurora, aurora_settings)
    _arrange_refresh_answers(fake_aurora, rows)

    exit_code = cli.main(
        ["refresh-dataset", f"--dataset={_REFRESH_TOKEN}", "--business-date=2022-07-18"]
    )

    assert exit_code == EXIT_OK
    # WHY : the READ is asserted against the default source prefix, because that prefix is the
    #   whole point of the fix: the state used to name a filesystem path nothing provisions, so
    #   every branch failed on an absent file. Asserting the key proves the read goes to the
    #   deployment's own bucket prefix -- the one the runbook already tells an operator to sync to.
    # WHY : Refactoring Rationale: the reads are filtered to those OUTSIDE the reservation
    #   bookkeeping prefix, where this compared the whole list. This refresh reserves a generation
    #   for two families -- the seed segment and the TRANTYPE.BKUP family it also copies into --
    #   and each reservation reads that execution's own allocation record, so an unfiltered
    #   comparison fails on two reads that say nothing about where the extract came from.
    extract_reads = [
        request["Key"]
        for request in client.gets
        if not str(request["Key"]).startswith(s3_stage._RUN_CLAIM_ROOT)
    ]
    assert extract_reads == [source_key]
    # The staged generations carry the extract's bytes verbatim, excluding the conditional claim.
    dataset_writes = [put for put in client.puts if put.get("IfNoneMatch") != "*"]
    # WHY : Assumptions: TWO generations are written for this token, and the second is not an
    #   incidental extra.
    #   `transaction_types` is one of the three seed extracts the baseline also copies into a
    #   generation family of its own -- app/jcl/DEFGDGD.jcl:36-43 creates the first generation of
    #   TRANTYPE.BKUP from this very file with IEBGENER and SYSIN DD DUMMY -- so a refresh that
    #   wrote
    #   one generation would leave that family with no production writer at all, which is exactly
    #   the
    #   gap this pair of keys proves closed.
    assert len(dataset_writes) == 2
    assert [put["Key"] for put in dataset_writes] == [
        f"reference/transaction_types/dt=2022-07-18/gen=0001/{descriptor.source_object}",
        f"reference/trantype-bkup/dt=2022-07-18/gen=0001/{descriptor.source_object}",
    ]
    # WHY : the two bodies are asserted EQUAL to the same payload rather than merely non-empty,
    #   because "verbatim copy" is the whole contract of the baseline's IEBGENER step: re-encoding
    #   the loaded rows instead would produce a plausible file with a different sign convention or a
    #   different trailing-blank treatment, and no length or key assertion would notice.
    assert [put["Body"] for put in dataset_writes] == [payload, payload]
    # WHY : Assumptions: the schema list is asserted as FOUR requests for the owning schema -- the
    #   load, then each of the three verification passes -- because that count is what distinguishes
    #   a refresh from the staging-only step it replaced. A staging-only run reaches the database
    #   not at all, so an empty list was the old behaviour and any shorter list is a pass that did
    #   not run.
    assert requested == [descriptor.domain] * 4
    assert fake_aurora.copy_statements == [target_for("TRANTYPE").stage_copy_statement()]
    assert len(fake_aurora.copied_rows) == len(rows)
    printed = capsys.readouterr().out
    # Each pass prints its own verdict line, so the three are asserted by their own text.
    assert f"loaded TRANTYPE into {target_for('TRANTYPE').schema}." in printed
    assert "MATCH" in printed
    assert "SKIP TRANTYPE declares no signed display field" in printed


def test_refresh_dataset_stops_at_the_first_verification_that_fails(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Stop the sequence at the failed step and report that step's own status.

    Purpose
    -------
    A refresh that continued past a failed pass would run the next pass over the same bad load and
    print a second report describing the first failure, so the diagnosis an operator reads would be
    noise on top of noise. This plants a row-count disagreement and asserts the checksum read-back
    was never issued at all -- which is a thing only an in-process double can be asked.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the staging, client and database seams and to set the execution token.
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
        If the refresh reports success, or if a later step ran after the failed one.
    """
    payload = _transaction_type_extract()
    descriptor = seed_datasets.seed_dataset(_REFRESH_TOKEN)
    client = _SourcePrefixS3Client()
    client.objects[f"migration/source/EBCDIC/{descriptor.source_object}"] = payload
    rows = _expected_transaction_type_rows()

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setenv("CARDDEMO_BATCH_RUN_ID", "carddemo-daily-batch-2022-07-18")
    requested = _bind_database(monkeypatch, fake_aurora, aurora_settings)
    # WHY : Assumptions: the planted difference is ONE row short rather than zero rows, because a
    #   count of zero is also what an unarranged double answers -- so a short count is the case
    #   that can only come from the arrangement and cannot come from the double's default.
    fake_aurora.arrange_rows("COUNT(*) FROM", [(len(rows) - 1,)])
    fake_aurora.arrange_rows(_READ_BACK_FRAGMENT, rows)
    # WHY : ⚠️ Refactoring Rationale: the cursor's column DESCRIPTION used to be arranged here
    #   too, for a read-back that resolved each column's comparison form from the driver's own type
    #   identifiers. The surviving verifier derives those rules from the copybook layout and narrows
    #   them by the rows themselves, so a description arranged here would be arranged for nothing.

    exit_code = cli.main(
        ["refresh-dataset", f"--dataset={_REFRESH_TOKEN}", "--business-date=2022-07-18"]
    )

    assert exit_code == EXIT_FAILED
    # The load and the row-count pass each opened a connection; the checksum pass never did.
    assert requested == [descriptor.domain] * 2
    # WHY : ⚠️ Assumptions: the check is ANCHORED at the start of the statement rather than matching
    #   the fragment anywhere in it. The load step's own staging table is created with
    #   `CREATE TEMPORARY TABLE ... AS SELECT <the same projection> FROM <the same table> WITH NO
    #   DATA`, so an unanchored match reports the checksum read-back as issued on every run that got
    #   as far as loading -- which is every run this case exercises. Only the checksum read-back
    #   BEGINS with the projection.
    assert not any(
        statement.startswith(_READ_BACK_FRAGMENT) for statement, _ in fake_aurora.statements
    ), (
        "the checksum read-back was issued after the row-count pass had already failed, so the"
        " sequence did not stop at the first failing step"
    )


def test_refresh_dataset_refuses_an_unregistered_dataset(monkeypatch: pytest.MonkeyPatch) -> None:
    """Report the usage tier for a value the registry does not carry, touching nothing.

    Notes
    -----
    Refactoring Rationale: the refused value is `NOT-A-DATASET` where it was the layout name
    `TRANTYPE`. A layout name is no longer unregistered -- the registry carries every seed dataset
    under its token AND its layout name, and `seed_datasets` asserts at import that the two
    spellings cannot collide -- so `TRANTYPE` now resolves to the same descriptor
    `transaction_types`
    does. What this case is about is unchanged: a value that resolves to nothing must be refused
    before any environment is read, because the dataset is what decides which prefix the bytes land
    under.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to fail the test if any environment or client is reached.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal is not the usage tier, or the command reached for an environment first.
    """

    def _refuse() -> None:
        """Fail the test if staging settings are resolved for an unregistered token."""
        raise AssertionError("an unregistered token must be refused before any environment is read")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _refuse)
    assert (
        cli.main(
            ["refresh-dataset", "--dataset=NOT-A-DATASET", "--business-date=2022-07-18"],
        )
        == EXIT_USAGE
    ), "a value the registry carries under neither spelling belongs to the usage tier"


def test_refresh_dataset_reports_an_unresolvable_environment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report the fatal tier when the dataset bucket cannot be resolved.

    Purpose
    -------
    An unresolvable environment is an operator action rather than a data fault, and the two are
    separated by exit tier so an orchestrator's retry policy can tell them apart: retrying a
    missing environment variable will never succeed.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to make the staging resolver raise.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the failure is reported at any tier other than fatal.
    """

    def _unresolvable() -> DatasetStagingSettings:
        """Raise the configuration failure an unset bucket variable produces."""
        raise ConfigurationError("CARDDEMO_DATASET_BUCKET is not set")

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _unresolvable)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)
    assert (
        cli.main(
            ["refresh-dataset", f"--dataset={_REFRESH_TOKEN}", "--business-date=2022-07-18"],
        )
        == EXIT_FATAL
    )


def test_refresh_dataset_reports_a_bad_extract_before_staging_anything(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Refuse an extract that is not a whole number of records, staging and loading nothing.

    Purpose
    -------
    A truncated upload of a fixed-block extract is the failure most likely to produce plausible
    wrong data, because a short extract still divides into whole records for as far as it goes.
    Refusing it at the fetch is what keeps those bytes from becoming the generation a rerun reads
    and the rows a verification pass then certifies.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the staging, client and database seams.
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver, asserted to have been left untouched.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refusal is not the failed tier, or anything was staged or loaded.
    """
    descriptor = seed_datasets.seed_dataset(_REFRESH_TOKEN)
    client = _SourcePrefixS3Client()
    truncated = _transaction_type_extract()[: layouts.reclen_of("TRANTYPE") + 1]
    client.objects[f"migration/source/EBCDIC/{descriptor.source_object}"] = truncated

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setenv("CARDDEMO_BATCH_RUN_ID", "carddemo-daily-batch-2022-07-18")
    requested = _bind_database(monkeypatch, fake_aurora, aurora_settings)

    exit_code = cli.main(
        ["refresh-dataset", f"--dataset={_REFRESH_TOKEN}", "--business-date=2022-07-18"]
    )

    assert exit_code == EXIT_FAILED
    assert client.puts == []
    assert requested == []


def test_refresh_dataset_accepts_a_local_extract_without_reading_the_bucket(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    tmp_path: Path,
) -> None:
    """Refresh from an operator's own file, skipping the fetch and reading no source object.

    Purpose
    -------
    The override exists for the operator reproducing a nightly failure at a terminal, or
    refreshing one master from a corrected file. It has to skip the fetch entirely rather than
    fetch and then ignore, so a refresh can be run where the source prefix holds nothing yet.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the staging, client and database seams and to set the execution token.
    fake_aurora : FakeAuroraDatabase
        Recording double for the driver.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    tmp_path : Path
        Directory holding the operator's local extract.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the refresh does not succeed, or if any object was read from the bucket.
    """
    descriptor = seed_datasets.seed_dataset(_REFRESH_TOKEN)
    local = tmp_path / descriptor.source_object
    local.write_bytes(_transaction_type_extract())
    client = _SourcePrefixS3Client()
    rows = _expected_transaction_type_rows()

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setenv("CARDDEMO_BATCH_RUN_ID", "carddemo-daily-batch-2022-07-18")
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    _arrange_refresh_answers(fake_aurora, rows)

    exit_code = cli.main(
        [
            "refresh-dataset",
            f"--dataset={_REFRESH_TOKEN}",
            "--business-date=2022-07-18",
            f"--source={local}",
        ]
    )

    assert exit_code == EXIT_OK
    # WHY : Refactoring Rationale: the reads are filtered to those OUTSIDE the reservation
    #   bookkeeping prefix, where this asserted the list was empty. The claim this test makes is
    #   that a local override reads no SOURCE object, and that claim is unchanged; what changed is
    #   that reserving a generation reads the execution's own allocation record, which is neither a
    #   source object nor optional. Asserting emptiness would now fail on a read that is not the
    #   one being ruled out.
    source_reads = [
        get for get in client.gets if not str(get["Key"]).startswith(s3_stage._RUN_CLAIM_ROOT)
    ]
    assert source_reads == [], "an override must not read the source prefix at all"


def test_refresh_steps_reconcile_the_allocator_only_for_the_transaction_master() -> None:
    """Append the allocator step to exactly the one dataset whose load occupies its range.

    Purpose
    -------
    The allocator is advanced INSIDE the refresh of the dataset that feeds its table, and only
    that one. Appending it to every dataset would advance a sequence nine loads have nothing to do
    with; omitting it from the transaction master would leave the window this step closes open --
    ``ledger.transaction_id_seq`` starts where its own migration left it, which on a cutover is
    below every identifier the extract carries, so the first interactive add collides.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the allocator step is attached to the wrong set of datasets, or if the five common
        steps are not the five an operator runs by hand.
    """
    staging = "stage the generation"
    # WHY : Refactoring Rationale: the five "common" steps became one common step plus four that are
    #   composed only for a dataset whose layout ships a committed extract, and the expectation is
    #   derived from that predicate rather than asserted for every token. It used to assert all five
    #   unconditionally, which codified the defect: the one unseeded token was scheduled for a
    #   load its extract cannot survive, so its nightly Map branch failed at step 2 of 6 every run.
    loading = (
        "load the target table",
        "verify row counts",
        "verify the record checksum",
        "verify money parity",
    )
    allocator = "reconcile the identifier allocator"
    for token in seed_datasets.SEED_DATASETS:
        descriptor = seed_datasets.seed_dataset(token)
        # WHY : Assumptions: the namespace carries `work_root`, because the composed reading steps
        #   pass it to the shared source resolver -- the composition binds the REAL handlers, so a
        #   member they read has to be present here too. It names a path that is never opened: this
        #   case reads the step LABELS and runs no step, so no source is ever resolved.
        arguments = argparse.Namespace(
            business_date=date(2022, 7, 18),
            encoding="ebcdic",
            retain=None,
            work_root=Path("unused-work-root"),
        )
        labels = tuple(
            label for label, _, _ in cli._refresh_steps(descriptor, arguments, Path("extract.PS"))
        )
        # WHY : the load-and-verify expectation is derived from `ships_committed_extract`, the same
        #   predicate the combined gate's coverage reads, so the refresh and the gate cannot come to
        #   disagree about which datasets have something to load. Writing "except transactions" here
        #   would be a third copy of a rule two modules publish.
        leading = (
            (staging, *loading) if ships_committed_extract(descriptor.layout_name) else (staging,)
        )
        assert labels[: len(leading)] == leading
        # WHY : the expectation is derived from the LOAD TARGET rather than from the token, because
        #   that is what the production predicate compares: two tokens loading into one table would
        #   both need the step, and a token renamed would not change which table it feeds.
        feeds_allocator = target_for(descriptor.layout_name).qualified_name == (
            target_for("TRAN").qualified_name
        )
        # WHY : the backup expectation is derived from the BINDING REGISTRY rather than from a
        #   literal list of three tokens, for the same reason: the registry is what the production
        #   predicate consults, so a binding added or removed there is asserted here without this
        #   test being edited. Its own import-time validation is what proves the three bindings are
        #   consistent; this only proves the step is attached exactly where a binding exists.
        family = seed_datasets.backup_family(token)
        trailing: tuple[str, ...] = ()
        if feeds_allocator:
            trailing += (allocator,)
        if family is not None:
            trailing += (f"stage the {family.dataset_segment} backup generation",)
        # WHY : the two trailing steps are asserted as MUTUALLY EXCLUSIVE rather than merely both
        #   present, because they are: the allocator belongs to the transaction master and the three
        #   backups to reference data, so no dataset can carry both. Asserting the exact tuple is
        #   what would catch a future binding that put a backup family on the transaction master and
        #   silently changed which step ran last.
        assert labels[len(leading) :] == trailing


def test_refresh_stages_but_neither_loads_nor_verifies_an_unseeded_dataset() -> None:
    """Compose no load and no verification for the one dataset that ships no committed extract.

    Purpose
    -------
    Hold the nightly chain to the seed exemption two other modules already publish. The
    ``transactions`` token names ``AWS.M2.CARDDEMO.DALYTRAN.PS.INIT`` -- the single 350-byte record
    ``app/jcl/TRANFILE.jcl`` primes the TRANSACT cluster from, whose unpopulated category code is
    four NUL bytes -- so its refresh staged the generation and then failed decoding at step 2 of 6,
    every run, for that branch of the scheduled Map. Loading it would be wrong even if it decoded:
    ``verify/row_counts.py`` requires ``ledger.transactions`` to hold zero rows after the ETL,
    because posting is what fills it.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a load or a verification step is composed for an unseeded dataset, if the staging step is
        dropped along with them, or if the exemption is applied to a dataset that does ship an
        extract.
    """
    unseeded = [
        token
        for token in seed_datasets.SEED_DATASETS
        if not ships_committed_extract(seed_datasets.seed_dataset(token).layout_name)
    ]
    # WHY : Assumptions: the set is derived and then asserted non-empty, so this case cannot pass
    #   vacuously if the predicate ever answers True for everything -- a parametrised loop over an
    #   empty list collects nothing and reports green, which is the failure mode this suite exists
    #   to avoid elsewhere.
    assert unseeded, "no unseeded dataset remains, so this exemption is no longer under test"

    for token in unseeded:
        descriptor = seed_datasets.seed_dataset(token)
        arguments = argparse.Namespace(
            business_date=date(2022, 7, 18),
            encoding="ebcdic",
            retain=None,
            work_root=Path("unused-work-root"),
        )
        labels = tuple(
            label for label, _, _ in cli._refresh_steps(descriptor, arguments, Path("extract.PS"))
        )
        assert "stage the generation" in labels, (
            f"{token} lost its staging step; the generation family it writes is a real dataset"
            " generation the baseline's own REPRO job reads"
        )
        assert "load the target table" not in labels
        assert not [label for label in labels if label.startswith("verify")]


def _manifest(directory: Path, entries: object) -> Path:
    """Write a verification manifest into a temporary directory and return its path.

    Purpose
    -------
    Give the aggregate-command cases one way to author their input, so a case states only what it
    is varying about the manifest rather than restating the whole document.

    Parameters
    ----------
    directory : Path
        The directory the manifest is written into, which is also the root every relative source in
        it resolves against.
    entries : object
        The value to write under the document's ``datasets`` key. Deliberately typed loosely so a
        case can write a malformed document.

    Returns
    -------
    Path
        The manifest file's path.

    Raises
    ------
    OSError
        If the file cannot be written, which is a broken temporary directory rather than a failed
        assertion.
    """
    path = directory / "verification-manifest.json"
    path.write_text(json.dumps({"datasets": entries}), encoding="utf-8")
    return path


def test_the_checksum_command_verifies_a_load_read_back_in_another_order(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Verify a correct load whose read-back arrives in a different order from the extract.

    Purpose
    -------
    Assert the correspondence the pass establishes, end to end through the command rather than at
    the comparison function it calls. The read-back used to be ordered by every comparable column
    while the source side stayed in extract order, so a correctly loaded master whose extract is
    not already sorted that way was walked record-against-wrong-row and reported as broken. Both
    sides are now put into one key order before they are compared, and this case is what fails if
    either half of that is undone: the arranged rows are the SAME rows in the REVERSE order, so a
    positional walk disagrees on nearly every field of nearly every record while a keyed pairing
    verifies them.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    fake_aurora : FakeAuroraDatabase
        Recording double, arranged to answer the read-back with the rows reversed.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered comparison.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command reports a difference, if the seed is too short to distinguish the two
        orders, or if the read-back statement re-introduces a server-side ordering.
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    reader = RecordReader(layouts.layout("TCATBAL"))
    target = target_for("TCATBAL")
    fields = target.comparable_fields()
    # WHY : Assumptions: the arranged rows are built by projecting the seed through the target's OWN
    #   `prepare_record`, so this case varies the row ORDER and nothing else. Rows written by
    #   hand would also vary the values, and a reported difference could then be either defect;
    #   the one property under test is that the pass pairs a record with its own row rather than
    #   with whatever row occupies its position.
    prepared = [
        prepare_record(target, record, LoadContext())
        for record in reader.read_ascii(_CATEGORY_BALANCE_SEED)
    ]
    extract_order = [tuple(row[name] for name in fields) for row in prepared]
    rows = list(reversed(extract_order))
    assert len(rows) > 1, "the seed must hold at least two records for two orders to differ"
    assert rows != extract_order, (
        "the reversed read-back is identical to the extract order, so this case could pass under"
        " a positional pairing and would assert nothing"
    )
    fake_aurora.arrange_rows(target.table, rows)

    exit_code = cli.main(
        [
            "verify-checksum",
            "--dataset",
            "TCATBAL",
            "--source",
            str(_CATEGORY_BALANCE_SEED),
            "--encoding",
            "ascii",
        ]
    )

    printed = capsys.readouterr().out
    assert exit_code == EXIT_OK, printed
    assert "record checksum verification PASSED" in printed
    assert f"{len(rows)} position(s) compared" in printed
    assert "0 record(s) differing" in printed
    # WHY : ⚠️ Refactoring Rationale: the rendered line asserted here named "position(s) holding two
    #   different keys", and the merged report names the same quantity "position(s) occupied on one
    #   side only". The wording changed with the mechanism: because both sides are SORTED on the
    #   rendered key before the walk, a position can no longer hold two different keys -- the only
    #   way a position ends up unpaired is that one side carries a key the other does not. The count
    #   is the same count and the report reads truer for it.
    assert "0 position(s) occupied on one side only" in printed
    # WHY : Assumptions: the ABSENCE of a server-side ORDER BY is asserted as well as the verdict,
    #   because that clause is what made the two sides disagree and it would additionally make a
    #   text key's ordering depend on the server's collation. A revision that put it back could
    #   still satisfy the verdict above -- the double answers with whatever was arranged, in that
    #   order -- so the verdict alone does not hold the fix in place.
    statements = [sql for sql in fake_aurora.executed_sql() if target.table in sql]
    assert statements, "the read-back never queried the target table"
    assert not any("ORDER BY" in sql.upper() for sql in statements)


def test_verify_all_runs_the_three_passes_in_the_fixed_order_for_every_dataset(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Run passes 1, 2 and 3 over each manifested dataset, in that order, and report success once.

    Purpose
    -------
    Assert the property that makes this command worth having: one invocation whose zero status means
    every declared dataset was verified three ways, in the order the passes are only meaningful in.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to replace the three pass handlers with recorders, so the ORDER is observable without
        a database.
    tmp_path : Path
        Where the manifest and its named sources are written.
    capsys : pytest.CaptureFixture[str]
        Captures the per-pass headings and the final line.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a pass is skipped, run out of order, or run for the wrong dataset.
    """
    # WHY : Alternatives Considered: driving the three real handlers against the recording database
    #   double, which is what the per-pass cases above do. Rejected for THIS case because what is
    #   under test is the sequencing rather than any pass's own behaviour, and three real passes
    #   would make the observation of "row counts, then checksums, then money parity" depend on
    #   arranging three unrelated result sets correctly for two datasets.
    calls: list[tuple[str, str]] = []

    def recorder(label: str) -> object:
        """Build a stand-in pass that records the dataset it was asked to verify.

        Parameters
        ----------
        label : str
            The pass's name, recorded alongside the dataset.

        Returns
        -------
        object
            A callable with the handler signature, always reporting success.

        Raises
        ------
        None
            Building a closure cannot fail.
        """

        def run(arguments: object) -> int:
            """Record one invocation and report success.

            Parameters
            ----------
            arguments : object
                The namespace the aggregate synthesised.

            Returns
            -------
            int
                Always the success status.

            Raises
            ------
            None
                Recording cannot fail.
            """
            calls.append((label, arguments.dataset))
            return EXIT_OK

        return run

    monkeypatch.setattr(
        cli,
        "_VERIFICATION_PASSES",
        (
            ("row counts", recorder("row counts")),
            ("record checksums", recorder("record checksums")),
            ("money parity", recorder("money parity")),
        ),
    )
    (tmp_path / "acctdata.txt").write_text("", encoding="utf-8")
    (tmp_path / "cardxref.txt").write_text("", encoding="utf-8")
    manifest = _manifest(
        tmp_path,
        [
            {"dataset": "ACCOUNT", "source": "acctdata.txt", "encoding": "ascii"},
            {"dataset": "XREF", "source": "cardxref.txt", "encoding": "ascii"},
        ],
    )

    exit_code = cli.main(["verify-all", "--manifest", str(manifest)])

    assert exit_code == EXIT_OK
    assert calls == [
        ("row counts", "ACCOUNT"),
        ("record checksums", "ACCOUNT"),
        ("money parity", "ACCOUNT"),
        ("row counts", "XREF"),
        ("record checksums", "XREF"),
        ("money parity", "XREF"),
    ]
    assert "all three verification passes passed for 2 dataset(s)" in capsys.readouterr().out


def test_verify_all_stops_at_the_first_failing_pass(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Stop the whole run at the first failing pass and report that pass's own status.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to make the second pass fail.
    tmp_path : Path
        Where the manifest and its named source are written.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a later pass runs after a failure, or if the aggregate reports a status the failing pass
        did not.
    """
    # WHY : each pass is blind to the failure the next one catches, so a pass that ran after a
    #   failed predecessor would report a comparison over data already known to be wrong -- and an
    #   operator reading three failures cannot tell which one is the cause.
    reached: list[str] = []

    def passing(arguments: object) -> int:
        """Record the first pass and report success.

        Parameters
        ----------
        arguments : object
            The synthesised namespace.

        Returns
        -------
        int
            The success status.

        Raises
        ------
        None
            Recording cannot fail.
        """
        reached.append("first")
        return EXIT_OK

    def failing(arguments: object) -> int:
        """Record the second pass and report failure.

        Parameters
        ----------
        arguments : object
            The synthesised namespace.

        Returns
        -------
        int
            The failure status the aggregate must propagate.

        Raises
        ------
        None
            Recording cannot fail.
        """
        reached.append("second")
        return EXIT_FAILED

    def unreachable(arguments: object) -> int:
        """Record the third pass, which must never run.

        Parameters
        ----------
        arguments : object
            The synthesised namespace.

        Returns
        -------
        int
            The success status, never reported.

        Raises
        ------
        None
            Recording cannot fail.
        """
        reached.append("third")
        return EXIT_OK

    monkeypatch.setattr(
        cli,
        "_VERIFICATION_PASSES",
        (("row counts", passing), ("record checksums", failing), ("money parity", unreachable)),
    )
    (tmp_path / "acctdata.txt").write_text("", encoding="utf-8")
    manifest = _manifest(
        tmp_path, [{"dataset": "ACCOUNT", "source": "acctdata.txt", "encoding": "ascii"}]
    )

    assert cli.main(["verify-all", "--manifest", str(manifest)]) == EXIT_FAILED
    assert reached == ["first", "second"]


def test_verify_all_resolves_a_relative_source_against_the_manifest(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Resolve each entry's source relative to the manifest, so one manifest travels with a tree.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to capture the source each pass is handed.
    tmp_path : Path
        Holds the manifest in one directory and its extract in a subdirectory.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a relative source is resolved against the process working directory instead, which would
        make the same manifest work or fail depending on where it was run from.
    """
    supplied: list[str] = []

    def capture(arguments: object) -> int:
        """Record the resolved source and report success.

        Parameters
        ----------
        arguments : object
            The synthesised namespace.

        Returns
        -------
        int
            The success status.

        Raises
        ------
        None
            Recording cannot fail.
        """
        supplied.append(arguments.source)
        return EXIT_OK

    monkeypatch.setattr(cli, "_VERIFICATION_PASSES", (("row counts", capture),))
    delivery = tmp_path / "delivery"
    delivery.mkdir()
    (delivery / "acctdata.txt").write_text("", encoding="utf-8")
    manifest = _manifest(
        tmp_path, [{"dataset": "ACCOUNT", "source": "delivery/acctdata.txt", "encoding": "ascii"}]
    )

    assert cli.main(["verify-all", "--manifest", str(manifest)]) == EXIT_OK
    assert supplied == [str(tmp_path / "delivery" / "acctdata.txt")]


@pytest.mark.parametrize(
    ("entries", "expected"),
    [
        ([], "declares no dataset"),
        ([{"dataset": "ACCOUNT", "source": "acctdata.txt"}], "carrying exactly"),
        (
            # WHY : Refactoring Rationale: the expected fragment reads "neither a layout name"
            #   where it read "no layout registers". The refusal now names BOTH admitted
            #   vocabularies, because a manifest accepts either spelling as the four per-dataset
            #   commands do, so a message naming only the layout registry would tell an operator
            #   who mistyped a seed token that tokens are not accepted here at all.
            [{"dataset": "NOT-A-RECORD", "source": "acctdata.txt", "encoding": "ascii"}],
            "neither a layout name",
        ),
        (
            [{"dataset": "ACCOUNT", "source": "acctdata.txt", "encoding": "utf8"}],
            "admitted forms",
        ),
        (
            [{"dataset": "ACCOUNT", "source": "", "encoding": "ascii"}],
            "non-empty string",
        ),
    ],
)
def test_verify_all_refuses_a_malformed_manifest_as_a_usage_error(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    entries: object,
    expected: str,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """Refuse a manifest that cannot drive a complete verification, before any pass runs.

    Purpose
    -------
    Assert the two properties a verification gate's own input needs: an empty declaration cannot
    exit zero having verified nothing, and every entry is validated before the first pass runs
    rather than when the run reaches it.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to make any pass that runs fail the test loudly.
    tmp_path : Path
        Where the manifest is written.
    entries : object
        The malformed ``datasets`` value under test.
    expected : str
        A fragment of the refusal the operator is meant to read.
    caplog : pytest.LogCaptureFixture
        Captures the refusal, which is reported on the logger rather than raised.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a malformed manifest is accepted, or reported as a data failure rather than as the usage
        failure it is.
    """

    # WHY : a malformed manifest exits USAGE rather than FAILED because an orchestrator
    #   distinguishes "the invocation was wrong" from "the data was wrong", and only the second
    #   should send an operator to look at the load.
    def must_not_run(arguments: object) -> int:
        """Fail the test if any pass is reached with a manifest that should have been refused.

        Parameters
        ----------
        arguments : object
            The synthesised namespace.

        Returns
        -------
        int
            Never returns.

        Raises
        ------
        AssertionError
            Always, because reaching this function is the defect.
        """
        raise AssertionError("a pass ran against a manifest that should have been refused")

    monkeypatch.setattr(cli, "_VERIFICATION_PASSES", (("row counts", must_not_run),))
    manifest = _manifest(tmp_path, entries)

    with caplog.at_level(logging.ERROR):
        assert cli.main(["verify-all", "--manifest", str(manifest)]) == EXIT_USAGE
    assert expected in caplog.text


def test_verify_all_reports_an_absent_manifest_rather_than_raising(
    tmp_path: Path, caplog: pytest.LogCaptureFixture
) -> None:
    """Report an unreadable manifest as a usage failure instead of a traceback.

    Parameters
    ----------
    tmp_path : Path
        Supplies a path that holds no file.
    caplog : pytest.LogCaptureFixture
        Captures the refusal.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an absent manifest raises out of the entry point, which is the one thing a container step
        cannot report.
    """
    with caplog.at_level(logging.ERROR):
        assert cli.main(["verify-all", "--manifest", str(tmp_path / "absent.json")]) == EXIT_USAGE
    assert "could not be read" in caplog.text


def test_verify_all_manifest_form_drives_a_real_pass_end_to_end(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Reach the REAL first pass through the manifest form and report a classified status.

    Purpose
    -------
    Cover the manifest form with an invocation that is not stubbed. Every other case here replaces
    :data:`cli._VERIFICATION_PASSES` with recorders, and that is exactly why an unusable command
    stayed green for a whole checkpoint: the synthesised namespace carried the three manifest values
    and NOT the invocation's work directory, so the first real pass raised ``AttributeError`` on
    ``arguments.work_root`` and the documented operator procedure exited 1 on a traceback before
    verifying a single dataset. One real pass invocation is what makes that class of omission a test
    failure rather than a deployment failure.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam to the double.
    tmp_path : Path
        Where the manifest is written; its own directory is the root the relative source resolves
        against.
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
        If the pass never reached the database, or if the aggregate reports a status outside the
        published classification -- which is what an escaping exception produces.
    """
    requested = _bind_database(monkeypatch, fake_aurora, aurora_settings)
    # WHY : Assumptions: an aggregate is arranged to answer ONE row, because a real server returns a
    #   row for `COUNT(*)` even over an empty table and the pass correctly refuses a connection that
    #   returns none. The count is the seed's own record count, so pass 1 MATCHES and the aggregate
    #   goes on to pass 2 -- which is what proves the run got past the first handler rather than
    #   stopping inside it.
    records = sum(1 for line in _CATEGORY_BALANCE_SEED.read_bytes().splitlines() if line.strip())
    fake_aurora.arrange_rows("COUNT(*) FROM", [(records,)])
    manifest = _manifest(
        tmp_path,
        [{"dataset": "TCATBAL", "source": str(_CATEGORY_BALANCE_SEED), "encoding": "ascii"}],
    )

    exit_code = cli.main(["verify-all", "--manifest", str(manifest)])

    # WHY : Assumptions: the SCHEMA REQUEST is the property this case turns on. A pass that raised
    #   before opening a connection records nothing, so an empty list is precisely the failure this
    #   test was written to catch, and it is invisible to any assertion about the status alone.
    assert requested, "no pass reached the database, so the manifest form ran nothing for real"
    assert requested[0] == "ledger"
    # WHY : Trade-offs: the expected status is the CLASSIFIED failure and not success. Pass 2 reads
    #   the loaded rows back and this double has none arranged, so a negative verdict is the honest
    #   outcome; what matters is that the run reported a documented status rather than the 1 an
    #   escaping AttributeError produced. Arranging a full read-back for eleven columns would make
    #   this a checksum test rather than a namespace-contract one.
    assert exit_code in {EXIT_OK, EXIT_FAILED}


def test_verify_all_manifest_passes_carry_the_invocations_work_root(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Give every manifested pass the work directory the invocation opened, not just the selectors.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to record the namespace each pass is invoked with.
    tmp_path : Path
        Where the manifest and its named source are written.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a pass is invoked without ``work_root``, or with one that names no existing directory --
        either of which makes an object-store source unreadable for that pass.
    """
    seen: list[tuple[frozenset[str], object, bool]] = []

    def _capture(arguments: argparse.Namespace) -> int:
        """Record the namespace's members, its work root, and whether that root exists yet.

        Parameters
        ----------
        arguments : argparse.Namespace
            The synthesised namespace.

        Returns
        -------
        int
            The success status, so every declared pass is reached.

        Raises
        ------
        None
            Recording cannot fail.
        """
        # WHY : Assumptions: the directory's existence is evaluated HERE and stored as a flag rather
        #   than asserted after the run. `main` opens the work root as a `TemporaryDirectory` and
        #   removes it on every exit path, so a check made after `cli.main` returns reports False
        #   for a good root and the test would fail for a reason that is not the defect.
        root = getattr(arguments, "work_root", None)
        seen.append((frozenset(vars(arguments)), root, isinstance(root, Path) and root.is_dir()))
        return EXIT_OK

    monkeypatch.setattr(
        cli,
        "_VERIFICATION_PASSES",
        (("row counts", _capture), ("record checksums", _capture), ("money parity", _capture)),
    )
    (tmp_path / "acctdata.txt").write_text("", encoding="utf-8")
    manifest = _manifest(
        tmp_path, [{"dataset": "ACCOUNT", "source": "acctdata.txt", "encoding": "ascii"}]
    )

    assert cli.main(["verify-all", "--manifest", str(manifest)]) == EXIT_OK

    assert len(seen) == 3, "the three mandated passes were not all invoked"
    for members, work_root, live in seen:
        # WHY : Assumptions: the members are asserted EXACTLY rather than by containment, so a
        #   namespace that grew the aggregate command's own `manifest` or `sql_root` fails here. A
        #   per-dataset handler reading one of those would silently take the aggregate's value
        #   instead of its own, which is a wrong answer rather than an error.
        assert set(members) == {"dataset", "source", "encoding", "work_root"}
        assert live, (
            f"a pass was handed {work_root}, which was not a live directory an object-store source"
            " could be materialised into"
        )


def test_verify_all_manifest_accepts_either_dataset_spelling(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Accept a seed-dataset token in a manifest, and hand the passes the layout name.

    Purpose
    -------
    Hold the manifest to the vocabulary README section 5.2 publishes for it: an entry declares "the
    same three values the four commands above take on the command line", and those four accept
    either spelling. Validating against the layout registry alone refused ``transaction_types`` as a
    usage error while ``verify-checksum --dataset transaction_types`` accepted it, so an operator
    transcribing a working command into a manifest met a refusal naming the value they had just used
    successfully.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to record the dataset each pass is invoked with.
    tmp_path : Path
        Where the manifest and its named source are written.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a token spelling is refused, or if the token rather than the layout name is carried into
        the passes -- the registries downstream are keyed by the layout name.
    """
    named: list[str] = []

    def _capture(arguments: argparse.Namespace) -> int:
        """Record the dataset a pass was handed and report success.

        Parameters
        ----------
        arguments : argparse.Namespace
            The synthesised namespace.

        Returns
        -------
        int
            The success status.

        Raises
        ------
        None
            Recording cannot fail.
        """
        named.append(arguments.dataset)
        return EXIT_OK

    monkeypatch.setattr(cli, "_VERIFICATION_PASSES", (("row counts", _capture),))
    (tmp_path / "trantype.txt").write_text("", encoding="utf-8")
    manifest = _manifest(
        tmp_path,
        [{"dataset": "transaction_types", "source": "trantype.txt", "encoding": "ascii"}],
    )

    assert cli.main(["verify-all", "--manifest", str(manifest)]) == EXIT_OK

    # WHY : Assumptions: the expected name is DERIVED from the registry rather than written as
    #   "TRANTYPE", so the assertion states the property -- the token is resolved to its layout --
    #   rather than a pair of literals that could both be edited to agree with a wrong mapping.
    assert named == [seed_datasets.layout_name_for("transaction_types")]


def test_no_contracted_subcommand_remains_unregistered() -> None:
    """Hold the denial roster empty, and prove an unregistered name would still be refused."""
    # Assumptions: the emptiness is asserted DIRECTLY rather than through a parameterised case over
    #   the tuple, because a parameterised case over an empty tuple collects nothing and reports
    #   green -- which is exactly the vacuous pass this suite exists to prevent elsewhere.
    assert _UNREGISTERED_SUBCOMMANDS == ()
    # Assumptions: the refusal mechanism is exercised with a name that is not a command at all, so
    #   the property the withdrawn cases proved -- an unregistered verb is a usage error and not a
    #   silent no-op -- keeps being asserted after its last real subject was delivered.
    assert "verify-all-passes" not in _registered_subcommands()
    assert cli.main(["verify-all-passes"]) == EXIT_USAGE


_CUSTOMER_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.CUSTDATA.PS"


_CATEGORY_BALANCE_EXTRACT = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.TCATBALF.PS"


@pytest.mark.parametrize("name", _UNREGISTERED_SUBCOMMANDS)
def test_contracted_but_unbacked_subcommands_are_absent(name: str) -> None:
    """Refuse a contracted subcommand whose backing module is not in this distribution."""
    assert name not in _registered_subcommands()
    assert cli.main([name]) == EXIT_USAGE


def _pin_generation(monkeypatch: pytest.MonkeyPatch, generation: int = 1) -> None:
    """Substitute the generation reservation with a fixed number.

    Purpose
    -------
    Let a test that measures argument forwarding, an exit tier or a source refusal do so without
    also depending on a reservation round trip. The reservation's own behaviour is covered
    deliberately by the two tests that exercise it.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher, scoped to the calling test.
    generation : int, optional
        The number every reservation answers with.

    Returns
    -------
    None
        Patches the seam.

    Raises
    ------
    None
    """
    # WHY : Assumptions: BOTH the execution token and the reservation call are arranged, and the
    #   pair is deliberate. Patching only `cli.reserve_generation` leaves the handler's own token
    #   guard on the path, which refuses before the reservation is reached -- so every pinned test
    #   would report the fatal tier instead of what it meant to measure. Setting only the token
    #   leaves the reservation to RUN, issuing a conditional put and a get against whatever client
    #   the test bound, which is the round trip these tests were arranged not to depend on.
    #   Together they keep the guard exercised and the round trip out.
    monkeypatch.setenv("CARDDEMO_BATCH_RUN_ID", "carddemo-daily-batch-pinned")
    monkeypatch.setattr(cli, "reserve_generation", lambda *args, **kwargs: generation)


def _point_staging_root(monkeypatch: pytest.MonkeyPatch, root: Path) -> Path:
    """Point the staging-root variable at a directory without writing any extract into it.

    Purpose
    -------
    Serve the tests whose subject is something other than the extract -- a stubbed staging
    call, an unresolvable environment, or the tier an absent extract reports -- so each one
    arranges only the boundary the command now requires.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to set the variable for the duration of one test.
    root : Path
        Directory the command will resolve the extract beneath; created if absent.

    Returns
    -------
    Path
        The directory that was pointed at.

    Raises
    ------
    None
    """
    root.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(root))
    return root


def _prepare_extract(
    monkeypatch: pytest.MonkeyPatch,
    root: Path,
    dataset: str = "transactions",
    *,
    payload: bytes | None = None,
) -> Path:
    """Write the extract a descriptor names beneath ``root`` and point the variable at it.

    Purpose
    -------
    Arrange the one shape the command accepts -- an extract at the registered source-object
    name, immediately beneath the staging root -- so a test states the dataset and the bytes
    and nothing else.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to set the staging-root variable for the duration of one test.
    root : Path
        Directory that becomes the staging root; created if absent.
    dataset : str, optional
        Seed-dataset token whose descriptor names the file; defaults to ``transactions``.
    payload : bytes, optional
        Bytes to write. Defaults to exactly one record of filler, because the command now
        applies the declared record-length check on every invocation and a payload that is
        not a whole multiple of it would be refused before the subject of the test was
        reached.

    Returns
    -------
    Path
        Absolute path of the extract that was written.

    Raises
    ------
    None
    """
    descriptor = seed_datasets.seed_dataset(dataset)
    _point_staging_root(monkeypatch, root)
    extract = root / descriptor.source_object
    if payload is None:
        payload = b"x" * seed_datasets.record_length(descriptor)
    extract.write_bytes(payload)
    return extract


def test_stage_dataset_derives_the_object_name_and_honours_a_raised_retention(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Derive the object name from the extract, and accept a retention ABOVE the contract floor."""
    recorded: dict[str, Any] = {}

    # Assumptions: only the staging ROOT is arranged and no extract is written, because the
    #   staging call itself is stubbed below and never opens one. Writing a file here would
    #   suggest the assertion depended on its contents, which it does not.
    _point_staging_root(monkeypatch, tmp_path / "extracts")
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
    _pin_generation(monkeypatch)
    # WHY : Refactoring Rationale: this asserted that `--object-name TRANSACT.BKUP` and
    #   `--retain 3` were both HONOURED, and both halves of that premise were withdrawn rather than
    #   the flags being renamed. `--object-name` existed only to accompany `--source`; with the
    #   arbitrary source path gone the extract is always the registered object, so the derived
    #   name is the only correct one and an override is unvalidated input with no use. `--retain 3`
    #   is now refused outright: three is BELOW the baseline's `LIMIT(5) SCRATCH` operand, which
    #   every one of the ten generation-data-group definitions carries, so honouring it scratched
    #   generations the contract requires to exist -- and object versions a retention sweep deletes
    #   are gone.
    # WHY : Assumptions: the retention case asserted is a value ABOVE the floor, because the check
    #   is deliberately one-sided. Retaining more history destroys nothing and is a real need before
    #   a risky cutover, so a test pinning only the refusal would leave the safe direction unproven.
    descriptor = seed_datasets.seed_dataset("transactions")
    assert cli.main(_stage_arguments() + ["--retain", "9"]) == EXIT_OK
    assert recorded["object_name"] == descriptor.source_object
    assert recorded["retention_count"] == 9


def test_stage_dataset_refuses_a_retention_below_the_baseline_contract(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Refuse a retention count that would scratch generations the baseline requires to exist."""
    _point_staging_root(monkeypatch, tmp_path / "extracts")
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)

    def _refuse(**kwargs: Any) -> None:
        """Fail the test if a below-floor retention ever reaches the staging call."""
        raise AssertionError("a retention below the contract floor must not reach staging")

    monkeypatch.setattr(cli, "stage_dataset_file", _refuse)
    _pin_generation(monkeypatch)
    # WHY : Assumptions: the refusal is asserted to happen at the USAGE tier and before the staging
    #   call, which the raiser above proves. A check applied inside staging would already have
    #   reserved a generation and opened an extract, and a retention sweep is not undone by an exit
    #   code -- the deleted object versions are gone.
    for below in ("4", "1", "0", "-1"):
        assert cli.main(_stage_arguments() + ["--retain", below]) == EXIT_USAGE


def test_stage_dataset_resolves_a_layout_name_to_the_same_dataset_as_its_token(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Stage identically whether the dataset is named by its token or by its layout name."""
    # WHY : Refactoring Rationale: this REVERSES an earlier test that asserted `--dataset TRAN` was
    #   refused, on the argument that one flag carrying two vocabularies is an ambiguity. That
    #   argument does not survive measurement. Every orchestrator token is lower-case with
    #   underscores and every layout name is upper-case, so the two sets are provably disjoint --
    #   `seed_datasets` asserts that disjointness at import -- and a value therefore resolves to
    #   exactly ONE descriptor. There is no ambiguity to remove, only a vocabulary split to close.
    # Assumptions: the split was not a documentation gap but a chain that could not be wired. The
    #   batch chain's `Map` state iterates one dataset list and hands the same `$.dataset` value to
    #   every branch, so while staging read the tokens and loading read the layout names, a staging
    #   branch and a load branch could not be driven from one list at all.
    # Trade-offs: the property asserted is EQUIVALENCE of the two spellings rather than mere
    #   acceptance of the second. An exit-code-only test would pass for a layout name that resolved
    #   to the wrong descriptor, which is the one failure mode a unified vocabulary could introduce.
    assert "TRAN" in layouts.names()
    assert "TRAN" not in seed_datasets.seed_dataset_tokens()
    assert seed_datasets.layout_name_for("transactions") == "TRAN"

    recorded: list[Any] = []

    def _capture(**kwargs: Any) -> cli.StagedObject:
        """Record the staging call and answer with a fixed successful report."""
        recorded.append(kwargs)
        return cli.StagedObject(
            key="k",
            prefix="p/",
            business_date=date(2022, 7, 18),
            generation=1,
            byte_size=350,
            sha256="0" * 64,
            deleted_generation_prefixes=(),
        )

    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _FakeS3Client)
    monkeypatch.setattr(cli, "stage_dataset_file", _capture)
    _prepare_extract(monkeypatch, tmp_path / "extracts", "transactions")

    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="transactions")) == EXIT_OK
    assert cli.main(_stage_arguments(dataset="TRAN")) == EXIT_OK
    assert len(recorded) == 2
    # Assumptions: the client is excluded from the comparison because each invocation constructs
    #   its own, so the two calls hold two distinct objects by design. Every other forwarded value
    #   -- the domain, the dataset segment, the object name, the declared record length, the
    #   retention count and the resolved source -- is derived from the descriptor, so comparing the
    #   remainder is what proves both spellings resolved to the same one.
    compared = [{key: value for key, value in call.items() if key != "client"} for call in recorded]
    assert compared[0] == compared[1]
    assert compared[0]["dataset"] == seed_datasets.seed_dataset("transactions").dataset_segment


def test_stage_dataset_accepts_no_caller_supplied_source_path() -> None:
    """Refuse an arbitrary extract path outright, because the parser no longer offers one."""
    # WHY : Refactoring Rationale: this replaces a test that asserted the record-length check was
    #   SKIPPED for a caller-supplied `--source`, which was the asymmetry being fixed rather than a
    #   property worth keeping: the descriptor-derived path, whose extract is known fixed-block,
    #   carried the check, and the arbitrary path -- the only one an operator could aim anywhere --
    #   carried none. Both halves of that test are now unreachable, so the property it should have
    #   been pinning is asserted instead: the flag does not exist. Asserting on the parser rather
    #   than on an exit code matters because argparse answers an unknown flag with the same status
    #   2 a bad value gets, so an exit-code test alone would keep passing if the flag came back
    #   with a different name.
    # Assumptions: the search is scoped to the stage-dataset subparser alone, not to every flag
    #   the entry point registers. `--source` remains a legitimate argument of the load and
    #   verification commands, which read a local extract the operator names; what was withdrawn
    #   is the ability to WRITE an arbitrary file into the dataset bucket, so a repository-wide
    #   assertion would fail on commands this change never touched.
    subparsers = next(
        action for action in cli.build_parser()._actions if getattr(action, "choices", None)
    )
    flags = {
        option
        for action in subparsers.choices["stage-dataset"]._actions
        for option in action.option_strings
    }
    assert "--source" not in flags
    assert cli.main(["stage-dataset", "--dataset=accounts", "--source=/etc/passwd"]) == EXIT_USAGE


def test_stage_dataset_always_applies_the_declared_record_length(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Forward the descriptor's declared record length on every staging invocation."""
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
    _prepare_extract(monkeypatch, tmp_path / "extracts", "card_xref")
    _pin_generation(monkeypatch)

    assert (
        cli.main(
            [
                "stage-dataset",
                "--dataset=card_xref",
                "--business-date=2022-07-18",
            ]
        )
        == EXIT_OK
    )
    # WHY : Assumptions: the width is carried because every registered source names the EBCDIC
    #   form, and all ten of those divide exactly by their declared record length -- so the check
    #   is free evidence rather than a constraint the delivered extracts might fail. The newline-
    #   delimited ASCII twin that motivated the old suppression (app/data/ASCII/cardxref.txt holds
    #   36 data bytes per line where the copybook declares 50) is no longer reachable through this
    #   command at all, which is what makes the unconditional check safe.
    assert recorded[-1]["record_length"] == seed_datasets.record_length(descriptor)
    assert recorded[-1]["record_length"] is not None


def test_the_record_helper_dispatches_to_the_owning_reader_not_a_generic_one(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Decode through the module that owns the record, so its own value types survive."""

    def _refuse(*args: Any, **kwargs: Any) -> None:
        """Fail the test if the layout-derived generic reader decodes anything on this path."""
        raise AssertionError("the generic factory reader must not decode on the dispatch path")

    # WHY : Refactoring Rationale: the generic reader's two whole-extract entry points are replaced
    #   with raisers rather than merely counted. This is the assertion that would have caught the
    #   defect: the helper's rationale claimed the owning reader enforced each record's policy while
    #   the code built a `RecordReader` from the layout, and a factory-built reader carries the
    #   geometry and NONE of that policy. A call-count assertion would have passed the moment
    #   somebody reintroduced the generic call beside the owning one; a raiser cannot.
    monkeypatch.setattr(RecordReader, "read_ebcdic", _refuse)
    monkeypatch.setattr(RecordReader, "read_ascii", _refuse)

    # WHY : Assumptions: the explicit form is called rather than the namespace adapter, because the
    #   three selectors are what this test is about and the adapter adds only the work directory an
    #   object-store source would be materialised into -- which a local extract never touches.
    reader, records = cli._records_for("CARD", str(_CARD_EXTRACT), "ebcdic", tmp_path)
    first = next(iter(records))

    # The reader is still returned, and its purpose is now field metadata rather than decoding.
    assert reader.layout is layouts.layout("CARD")
    # WHY : Assumptions: the card verification value is asserted to be a `ProtectedValue` and not a
    #   string. That wrapper is exactly what the generic route dropped, and dropping it is the kind
    #   of defect that reports success: the value decodes, loads and verifies identically while
    #   having lost the one guard that stops it being rendered into a diagnostic or a log.
    assert isinstance(first["CARD-CVV-CD"], card.ProtectedValue)
    assert not isinstance(first["CARD-CVV-CD"], str)


def test_the_record_helper_refuses_a_corpus_the_owning_reader_withholds(tmp_path: Path) -> None:
    """Refuse the character form of a record whose reader publishes no character entry point."""
    # WHY : Assumptions: the refusal is a `LayoutError`, which `cli._step_errors()` catches, so the
    #   command reports a classified non-zero status rather than a traceback. That matters because a
    #   Step Functions state branches on a return code and cannot branch on a stack trace.
    with pytest.raises(layouts.LayoutError, match="publishes no character entry point"):
        cli._records_for("SECUSER", str(_USER_EXTRACT), "ascii", tmp_path)
    # WHY : Refactoring Rationale: the refusal set is read through `cli._step_errors()` where it was
    #   the module constant `cli._STEP_ERRORS`. It became a call because naming the loader's and the
    #   verifier's exception classes at module scope imported both packages before any subcommand
    #   had been chosen, which is what made an unrelated `list-datasets` fail on a verifier's import
    #   defect.
    assert layouts.LayoutError in cli._step_errors()


#: Every subcommand that reaches the database, with the argument vector it is invoked by.
#:
#: Assumptions: the two per-dataset shapes are stated once and shared by the exit-tier tests below,
#: because the tier a failure reports must not depend on which verb was used -- a batch state
#: branches on the number, so one verb reporting 8 where its siblings report 16 is the defect these
#: tests exist to catch.
_DATABASE_COMMANDS: Final[tuple[tuple[str, ...], ...]] = (
    (
        "load-dataset",
        "--dataset",
        "TCATBAL",
        "--source",
        str(_CATEGORY_BALANCE_SEED),
        "--encoding",
        "ascii",
    ),
    (
        "verify-row-counts",
        "--dataset",
        "TCATBAL",
        "--source",
        str(_CATEGORY_BALANCE_SEED),
        "--encoding",
        "ascii",
    ),
    (
        "verify-checksum",
        "--dataset",
        "TCATBAL",
        "--source",
        str(_CATEGORY_BALANCE_SEED),
        "--encoding",
        "ascii",
    ),
    (
        "verify-money-parity",
        "--dataset",
        "TCATBAL",
        "--source",
        str(_CATEGORY_BALANCE_SEED),
        "--encoding",
        "ascii",
    ),
    ("verify-row-count-report",),
    ("reconcile-sequences",),
    ("refresh-card-identity",),
)


@pytest.mark.parametrize("argv", _DATABASE_COMMANDS, ids=lambda argv: argv[0])
def test_a_configuration_failure_reports_the_fatal_tier_not_the_failed_tier(
    argv: tuple[str, ...], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Report 16 rather than 8 when the environment cannot be resolved, from every command.

    Parameters
    ----------
    argv : tuple of str
        The command line to run.
    monkeypatch : pytest.MonkeyPatch
        Used to make every settings resolver unresolvable.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a command reports the failed tier, or lets the failure escape as a traceback.
    """

    def _unresolvable(*args: Any, **kwargs: Any) -> None:
        """Stand in for a resolver whose parameter or secret is not there."""
        raise ConfigurationError("no aurora endpoint parameter")

    # WHY : Assumptions: every resolver a database command can reach is made unresolvable, on the
    #   OWNING module in each case, because the commands no longer re-export them -- each imports
    #   what it needs inside its own handler. Patching one resolver would leave whichever command
    #   happened to use a different one passing for the wrong reason.
    monkeypatch.setattr(cli, "resolve_aurora_settings", _unresolvable)
    monkeypatch.setattr(cli, "resolve_migration_settings", _unresolvable)
    # WHY : Assumptions: the master resolver is patched too, because `refresh-card-identity` is the
    #   one database command that reaches it -- the reporting schema has no `_migrator` login, so
    #   the reconciliation connects as the principal that applied the reporting definition. Leaving
    #   it resolvable would let that command pass this case by connecting rather than by
    #   classifying.
    monkeypatch.setattr(cli, "resolve_master_settings", _unresolvable)
    monkeypatch.setattr(session, "resolve_verifier_settings", _unresolvable)
    monkeypatch.setattr(row_counts, "reporting_settings", _unresolvable)

    # WHY : Assumptions: 16 and not 8, and the distinction is operational rather than cosmetic. The
    #   batch chain RETRIES a failed step, and no number of retries resolves a parameter that was
    #   never published -- so reporting 8 spent every attempt reaching the diagnosis the first
    #   attempt already had, and delayed the one action that helps by the whole retry budget.
    assert cli.main(list(argv)) == EXIT_FATAL


def test_a_client_construction_failure_reports_the_fatal_tier(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Classify an unbuildable S3 client rather than letting it escape as a traceback."""
    _prepare_extract(monkeypatch, tmp_path / "extracts")
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)

    def _no_client() -> None:
        """Stand in for a client factory on a host with no AWS SDK installed."""
        raise ConfigurationError("boto3 is required to stage datasets but is not installed")

    monkeypatch.setattr(cli, "_s3_client", _no_client)
    # WHY : Refactoring Rationale: this is the assertion that would have caught the defect. The
    #   client was constructed on the line AFTER the guard that classifies `ConfigurationError`, and
    #   `_s3_client` documents itself as raising exactly that when the SDK is absent or the
    #   environment names no usable credential -- the two most likely failures on a first
    #   deployment. So the one exception the handler is written to classify was the one that
    #   escaped.
    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments()) == EXIT_FATAL


def test_an_unreadable_extract_reports_the_failed_tier_from_every_consuming_pass(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Translate an OSError raised while consuming records into the documented failed tier."""
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    fake_aurora.arrange_rows("COUNT(*) FROM", [(0,)])
    fake_aurora.arrange_rows("SUM(", [(Decimal("0.00"),)])
    vanished = tmp_path / "tcatbal.txt"
    vanished.write_bytes(b"")

    def _fail_midway(path: Path) -> Any:
        """Fail the way a deleted, denied or mid-stream-truncated extract does.

        Parameters
        ----------
        path : Path
            Accepted to match the readers' own entry-point shape; deliberately unread.

        Yields
        ------
        Mapping[str, object]
            Nothing. The generator raises before its first yield.

        Raises
        ------
        OSError
            Always, on the first record pulled.
        """
        # WHY : Assumptions: NO record is yielded before the failure. Yielding a partial record
        #   first would additionally exercise the loader's field-shape refusal, so a pass could
        #   report the failed tier for that reason instead and the test would no longer measure the
        #   translation it names.
        if False:  # pragma: no cover - present so this is a generator, never entered
            yield {}
        raise OSError(5, "Input/output error")

    # WHY : Refactoring Rationale: the failure is raised from INSIDE the generator rather than at
    #   open time, because that is where it really arrives: the readers are lazy, so a vanished
    #   extract, a denied mode bit or a dropped object-store connection surfaces while a pass is
    #   consuming records -- after the handler's setup block has already returned successfully. A
    #   test that failed the open would have been caught by the old guards and would have proved
    #   nothing about the ones that were missing.
    monkeypatch.setattr(cli, "dataset_reader", lambda name, encoding: _fail_midway)
    for command in ("verify-row-counts", "verify-checksum", "verify-money-parity", "load-dataset"):
        assert (
            cli.main(
                [command, "--dataset", "TCATBAL", "--source", str(vanished), "--encoding", "ascii"]
            )
            == EXIT_FAILED
        ), f"{command} did not classify an unreadable extract"


def test_a_driver_failure_reports_the_failed_tier_from_every_consuming_pass(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Translate the database driver's own error into the documented failed tier."""
    _bind_database(monkeypatch, fake_aurora, aurora_settings)

    class _DriverFailure(Exception):
        """Stand in for the driver's own error base, which the loader publishes for this purpose."""

    # WHY : Assumptions: the driver's error base is SUBSTITUTED rather than a real psycopg error
    #   raised, because the double never reaches psycopg at all -- and substituting it is what
    #   proves the classification reads `aurora.driver_errors()` rather than naming a type of its
    #   own. A test raising `psycopg.Error` directly would pass equally well against a handler that
    #   had hard-coded that class, which is the coupling the published accessor exists to remove.
    monkeypatch.setattr(aurora, "driver_errors", lambda: (_DriverFailure,))

    for fragment in ("count(", "sum(", "select"):
        fake_aurora.arrange_statement_failure(fragment, _DriverFailure("connection closed"))
    for command in ("verify-row-counts", "verify-checksum", "verify-money-parity"):
        assert (
            cli.main(
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
            == EXIT_FAILED
        ), f"{command} did not classify a driver failure"


@pytest.mark.parametrize(
    ("command", "token", "layout_name"),
    [
        ("decode-record", "accounts", "ACCOUNT"),
        ("load-dataset", "transactions", "TRAN"),
        ("verify-row-counts", "card_xref", "XREF"),
        ("verify-checksum", "users", "SECUSER"),
        ("verify-money-parity", "transaction_category_balances", "TCATBAL"),
    ],
)
def test_every_dataset_selector_normalises_a_seed_token_to_its_layout_name(
    command: str, token: str, layout_name: str
) -> None:
    """Accept either dataset spelling on every command keyed by the layout name."""
    # WHY : Refactoring Rationale: the normalisation is asserted at the PARSER, not through five
    #   handler invocations, because the parser is where it now happens -- one `type=` on each
    #   selector rather than a resolution repeated inside each handler. A handler-level test would
    #   pass just as well if each of the five resolved the token for itself, which is the
    #   duplication this arrangement exists to prevent.
    # Assumptions: the layout name is asserted to pass through UNCHANGED as well as the token to
    #   resolve, because a normaliser that mapped both spellings onto a token would break every
    #   registry downstream -- `layouts.layout`, `aurora.target_for` and `readers.dataset_reader`
    #   are all keyed by the layout name.
    parser = cli.build_parser()
    tail = ["--source=/does/not/matter", "--encoding=ascii"]
    if command == "decode-record":
        tail = ["--source=/does/not/matter"]
    from_token = parser.parse_args([command, f"--dataset={token}", *tail])
    from_layout = parser.parse_args([command, f"--dataset={layout_name}", *tail])
    assert from_token.dataset == layout_name
    assert from_layout.dataset == layout_name


def test_decode_record_decodes_a_shipped_extract_named_by_its_seed_token(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Decode the same record whether the dataset is named by token or by layout name."""
    # WHY : Trade-offs: this pairs the parser assertion above with ONE end-to-end case, rather than
    #   running all five commands both ways. The parser proves the resolution and this proves the
    #   resolved value actually reaches a registry and decodes -- a normaliser that produced a
    #   plausible-looking string no reader is keyed by would satisfy the parser test alone.
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT, dataset="ACCOUNT")) == EXIT_OK
    by_layout = json.loads(capsys.readouterr().out)
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT, dataset="accounts")) == EXIT_OK
    by_token = json.loads(capsys.readouterr().out)
    assert by_token == by_layout
    assert by_token


def test_an_unknown_dataset_refusal_names_both_accepted_spellings(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report both vocabularies when a dataset resolves to neither."""
    # WHY : Assumptions: a value reaching the refusal matched neither spelling, because the parser
    #   already mapped every registered token. So the message has to name both sets: an operator
    #   who mistyped a token and saw only the layout names listed would reasonably conclude tokens
    #   are not accepted here and rewrite a working orchestrator definition to match.
    assert cli.main(_decode_arguments(_ACCOUNT_EXTRACT, dataset="ACCOUNTS")) == EXIT_USAGE
    reported = capsys.readouterr().err
    assert "ACCOUNT" in reported
    assert "accounts" in reported
    for spelling in (*layouts.names(), *seed_datasets.seed_dataset_tokens()):
        assert spelling in reported


#: Bucket and prefix the object-store staging tests configure the root to.
#:
#: Assumptions: the values are visibly synthetic and are held as constants so a test asserts on the
#: same bucket and prefix it configured. A literal repeated in the arrange and the assert is how a
#: test comes to pass while proving the wrong key was read.
_INBOX_BUCKET: Final[str] = "carddemo-datasets-test"


_INBOX_PREFIX: Final[str] = "inbox/seed"


def _inbox_root() -> str:
    """Return the object-store root URI the staging tests configure.

    Parameters
    ----------
    None
        Reads the module constants.

    Returns
    -------
    str
        A root naming :data:`_INBOX_BUCKET` and :data:`_INBOX_PREFIX`.

    Raises
    ------
    None
    """
    return f"{seed_datasets.OBJECT_STORE_SCHEME}://{_INBOX_BUCKET}/{_INBOX_PREFIX}"


def test_stage_dataset_reads_its_extract_from_the_object_store_when_the_root_names_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Stage from a delivered object when the configured root is a bucket prefix.

    Purpose
    -------
    Assert the one arrangement the deployment can actually satisfy. The batch task runs on Fargate
    with no volume mount and the image ships no extract, so a root that could only name a local
    directory named a file that did not exist and every staging branch would have reported a
    missing extract for a correctly-provisioned stack.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to set the staging root and to substitute the settings resolver and the client
        factory, so the test needs no environment and no network.

    Returns
    -------
    None
        Nothing; a failure to read the delivered object, or a staged object whose bytes differ
        from the delivered ones, is reported as an assertion failure.

    Raises
    ------
    None
    """
    descriptor = seed_datasets.seed_dataset("card_xref")
    payload = (bytes(range(256)) * 5)[: seed_datasets.record_length(descriptor) * 4]
    client = _CapableS3Client()
    client.seed_object(_INBOX_BUCKET, f"{_INBOX_PREFIX}/{descriptor.source_object}", payload)

    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, _inbox_root())
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)

    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="card_xref")) == EXIT_OK

    # WHY : Assumptions: the delivered key is asserted to have been READ, and the staged object is
    #   asserted to hold the delivered bytes verbatim. Either half alone is satisfiable by a defect:
    #   a read with no write proves nothing landed, and a write of the right length proves nothing
    #   about provenance. Together they pin that the bytes crossed both hops unchanged.
    assert client.gets == [f"{_INBOX_PREFIX}/{descriptor.source_object}"]
    staged = [request for request in client.puts if request.get("Body")]
    assert staged, "no object was staged"
    assert staged[-1]["Body"] == payload
    assert staged[-1]["Metadata"]["carddemo-sha256"] == hashlib.sha256(payload).hexdigest()


def test_stage_dataset_refuses_a_delivered_extract_whose_digest_disagrees(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse a staged-generation source whose bytes are not the bytes its metadata records."""
    descriptor = seed_datasets.seed_dataset("card_xref")
    payload = (bytes(range(256)) * 5)[: seed_datasets.record_length(descriptor) * 4]
    client = _CapableS3Client()
    # WHY : Assumptions: the digest seeded is the digest of DIFFERENT bytes, which is the only shape
    #   that distinguishes a real integrity check from a length check. A truncated transfer changes
    #   the length as well and would be caught by the cheaper comparison; a substituted or
    #   bit-flipped payload of the same length is caught only here.
    client.seed_object(
        _INBOX_BUCKET,
        f"{_INBOX_PREFIX}/{descriptor.source_object}",
        payload,
        sha256=hashlib.sha256(payload + b"x").hexdigest(),
    )

    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, _inbox_root())
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)

    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="card_xref")) == EXIT_FAILED
    assert not [request for request in client.puts if request.get("Body")]


def test_stage_dataset_refuses_a_delivered_extract_of_the_wrong_geometry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse a delivered object whose length is not a whole multiple of the record width."""
    descriptor = seed_datasets.seed_dataset("card_xref")
    # WHY : Assumptions: the record-length check is what covers a delivered object carrying NO
    #   recorded digest, which is every inbound mainframe export -- the digest comparison is skipped
    #   for those by design, so without this the inbox path would have no integrity check at all.
    #   A truncated fixed-block transfer is the failure it catches.
    payload = b"\x00" * (seed_datasets.record_length(descriptor) * 2 + 1)
    client = _CapableS3Client()
    client.seed_object(_INBOX_BUCKET, f"{_INBOX_PREFIX}/{descriptor.source_object}", payload)

    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, _inbox_root())
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)

    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="card_xref")) == EXIT_FAILED
    assert not [request for request in client.puts if request.get("Body")]


def test_stage_dataset_reports_an_undelivered_extract_as_failed_not_fatal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report the failed tier when the configured prefix holds no such object."""
    # WHY : Assumptions: an undelivered extract is the FAILED tier and not the fatal one, because
    #   the environment resolved perfectly -- the bucket and prefix are exactly as configured -- and
    #   the export simply has not landed yet. A batch state retries 8 and escalates 16, and waiting
    #   for a delivery is the retryable one of the two.
    client = _CapableS3Client()
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, _inbox_root())
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)

    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="card_xref")) == EXIT_FAILED


def test_a_root_naming_the_object_store_scheme_with_no_bucket_is_fatal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report the fatal tier for a root that uses the scheme but names nothing."""
    # WHY : Assumptions: this is the FATAL tier, unlike the undelivered extract above, and the
    #   distinction is the whole reason the two tiers exist. A malformed root is a deployment value
    #   an operator has to change; no number of retries makes `s3://` name a bucket.
    monkeypatch.setenv(
        seed_datasets.STAGING_ROOT_VARIABLE, f"{seed_datasets.OBJECT_STORE_SCHEME}://"
    )
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", _CapableS3Client)

    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="card_xref")) == EXIT_FATAL


def test_the_materialised_extract_directory_does_not_survive_the_command(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Remove the task-local copy on the success path, so ephemeral storage does not accumulate."""
    descriptor = seed_datasets.seed_dataset("card_xref")
    payload = (bytes(range(256)) * 5)[: seed_datasets.record_length(descriptor) * 4]
    client = _CapableS3Client()
    client.seed_object(_INBOX_BUCKET, f"{_INBOX_PREFIX}/{descriptor.source_object}", payload)

    observed: list[Path] = []
    real = cli.stage_dataset_file

    def _watch(**kwargs: Any) -> Any:
        """Record the staging root the command materialised into, then stage for real."""
        observed.append(Path(kwargs["staging_root"]))
        return real(**kwargs)

    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, _inbox_root())
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    monkeypatch.setattr(cli, "stage_dataset_file", _watch)

    _pin_generation(monkeypatch)
    assert cli.main(_stage_arguments(dataset="card_xref")) == EXIT_OK
    # WHY : Trade-offs: the directory is observed from INSIDE the staging call rather than guessed
    #   from the temporary-directory prefix, because a prefix scan would pass while the command
    #   staged from somewhere else entirely. Asserting it existed then and is gone now is what
    #   proves the cleanup ran rather than that no directory was ever created.
    assert observed, "the command never staged from a materialised root"
    assert observed[-1].name.startswith("carddemo-extract-")
    assert not observed[-1].exists()


def test_load_dataset_reads_a_source_from_the_object_store(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Load from the staged generation object, which is the only source the batch task can reach.

    Purpose
    -------
    Assert the reading half of the same problem the staging half solves. The batch task has no
    volume mount, so after ``stage-dataset`` has written a generation the only place that extract
    exists is the bucket -- and a load command that could accept nothing but a local path had no
    source at all in the deployment that actually runs it.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind the database seam and the S3 client seam to doubles.
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
    None
    """
    fixed_length_twin = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.TCATBALF.PS"
    payload = fixed_length_twin.read_bytes()
    client = _CapableS3Client()
    key = "ledger/tcatbal/dt=2022-07-18/gen=0001/AWS.M2.CARDDEMO.TCATBALF.PS"
    # WHY : Assumptions: the seeded object carries the digest this package's own staging step would
    #   have recorded, because that is what a STAGED generation looks like -- and it is what makes
    #   the digest comparison live on this path rather than skipped. An inbound export carries none,
    #   and the staging tests above cover that population.
    client.seed_object(_INBOX_BUCKET, key, payload, sha256=hashlib.sha256(payload).hexdigest())
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    requested = _bind_database(monkeypatch, fake_aurora, aurora_settings)

    exit_code = cli.main(
        [
            "load-dataset",
            "--dataset",
            "transaction_category_balances",
            "--source",
            f"{seed_datasets.OBJECT_STORE_SCHEME}://{_INBOX_BUCKET}/{key}",
            "--encoding",
            "ebcdic",
        ]
    )
    assert exit_code == EXIT_OK
    assert requested == ["ledger"]
    assert client.gets == [key]
    # WHY : Assumptions: the row count is derived from the object's own geometry, so the assertion
    #   proves every record of the materialised copy was decoded rather than that some records were.
    #   A truncated transfer that the digest check somehow passed would land here as a short count.
    assert len(fake_aurora.copied_rows) == len(payload) // layouts.reclen_of("TCATBAL")
    assert fake_aurora.commits == 1


def test_load_dataset_refuses_an_object_store_source_whose_digest_disagrees(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Refuse to load bytes that are not the bytes the staged object recorded."""
    fixed_length_twin = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.TCATBALF.PS"
    payload = fixed_length_twin.read_bytes()
    client = _CapableS3Client()
    key = "ledger/tcatbal/dt=2022-07-18/gen=0001/AWS.M2.CARDDEMO.TCATBALF.PS"
    client.seed_object(
        _INBOX_BUCKET, key, payload, sha256=hashlib.sha256(payload + b"x").hexdigest()
    )
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    _bind_database(monkeypatch, fake_aurora, aurora_settings)

    # WHY : Assumptions: the tier is FAILED and the load is asserted not to have happened at all.
    #   A verification layer that reported a tampered source as a failure AFTER committing the rows
    #   would be reporting on data it had already trusted, which is the one outcome an
    #   integrity-checked source exists to prevent.
    assert (
        cli.main(
            [
                "load-dataset",
                "--dataset",
                "transaction_category_balances",
                "--source",
                f"{seed_datasets.OBJECT_STORE_SCHEME}://{_INBOX_BUCKET}/{key}",
                "--encoding",
                "ebcdic",
            ]
        )
        == EXIT_FAILED
    )
    assert fake_aurora.copied_rows == []
    assert fake_aurora.commits == 0


@pytest.mark.parametrize("command", ["verify-row-counts", "verify-checksum", "verify-money-parity"])
def test_every_verification_pass_accepts_an_object_store_source(
    command: str,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Read the staged generation object from all three passes, not only from the load."""
    # WHY : Assumptions: all three passes are exercised rather than one, because each resolves its
    #   own source through the shared helper and a single-pass test would leave two commands able to
    #   regress independently. They are the commands the combined gate composes, so a pass that
    #   cannot reach a remote source makes the gate unrunnable in the deployment.
    fixed_length_twin = _EBCDIC_DIRECTORY / "AWS.M2.CARDDEMO.TCATBALF.PS"
    payload = fixed_length_twin.read_bytes()
    client = _CapableS3Client()
    key = "ledger/tcatbal/dt=2022-07-18/gen=0001/AWS.M2.CARDDEMO.TCATBALF.PS"
    client.seed_object(_INBOX_BUCKET, key, payload, sha256=hashlib.sha256(payload).hexdigest())
    monkeypatch.setattr(cli, "_s3_client", lambda: client)
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    # WHY : Assumptions: the empty-table aggregates are arranged for the same reason the sibling
    #   per-dataset test arranges them -- a real database returns one row for `COUNT(*)` and for a
    #   coalesced `SUM` even over an empty table, and the passes correctly refuse a connection that
    #   returns none. Arranging them makes the double a database; the resulting verdict is still an
    #   honest difference against the extract's records.
    fake_aurora.arrange_rows("COUNT(*) FROM", [(0,)])
    fake_aurora.arrange_rows("SUM(", [(Decimal("0.00"),)])

    exit_code = cli.main(
        [
            command,
            "--dataset",
            "transaction_category_balances",
            "--source",
            f"{seed_datasets.OBJECT_STORE_SCHEME}://{_INBOX_BUCKET}/{key}",
            "--encoding",
            "ebcdic",
        ]
    )
    # WHY : Trade-offs: what is asserted is that the pass MATERIALISED and read the remote source,
    #   not the verdict. The double answers every comparison from an empty table, so a difference is
    #   the correct outcome and asserting it would be asserting the arrangement rather than the
    #   remote-source capability this test exists for.
    assert exit_code in (EXIT_OK, EXIT_FAILED), f"{command} did not reach a verdict"
    assert client.gets == [key]


@dataclass(frozen=True)
class _StubPass:
    """Stand in for one pass result, carrying only the two members the gate reads.

    Purpose
    -------
    Let the gate's own rules -- coverage, order, short-circuit and the binary verdict -- be driven
    without a cluster, an extract or a conforming result set. Those rules are what ``verify-all``
    exists to enforce, so they are what its tests measure.

    Parameters
    ----------
    label : str
        Rendered so a test can tell which pass produced a line.
    verified : bool
        The pass's own verdict.

    Returns
    -------
    None
        Holding two values cannot fail.

    Raises
    ------
    None
    """

    label: str
    verified: bool

    def render(self) -> str:
        """Render the pass as one deterministic line.

        Parameters
        ----------
        None
            Reads :attr:`label` and :attr:`verified`.

        Returns
        -------
        str
            The label and the verdict, with no timestamp, so two runs diff to nothing.

        Raises
        ------
        None
        """
        return f"{self.label}: {'PASSED' if self.verified else 'FAILED'}"


def _arrange_gate(
    monkeypatch: pytest.MonkeyPatch,
    *,
    row_counts_verified: bool = True,
    checksum_verified: bool = True,
    sealed_verified: bool = True,
    money_verified: bool = True,
) -> list[str]:
    """Bind every seam ``verify-all`` reaches, and record which passes actually ran.

    Purpose
    -------
    Give the gate tests one arrangement, so each test reads as the single property it asserts rather
    than as five patches. The recorded order is what makes the mandate's own rules observable.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher, scoped to the calling test.
    row_counts_verified : bool, optional
        The verdict pass 1 reports.
    checksum_verified : bool, optional
        The verdict every pass-2 digest comparison reports.
    sealed_verified : bool, optional
        The verdict every pass-2 sealed-column audit reports.
    money_verified : bool, optional
        The verdict pass 3 reports.

    Returns
    -------
    list of str
        A list each patched pass appends its own name to when it RUNS, in run order.

    Raises
    ------
    None
    """
    from carddemo_migration.verify import checksum, money_parity

    ran: list[str] = []

    def _row_counts(connection: Any, *, query_path: Any = None) -> _StubPass:
        """Record that pass 1 ran and report its arranged verdict."""
        ran.append("row_counts")
        return _StubPass("row counts", row_counts_verified)

    def _audit(**kwargs: Any) -> _StubPass:
        """Record that one pass-2 sealed-column audit ran and report its arranged verdict."""
        ran.append(f"sealed:{kwargs['record_name']}")
        return _StubPass(f"sealed columns {kwargs['record_name']}", sealed_verified)

    def _compare(**kwargs: Any) -> _StubPass:
        """Record that one pass-2 comparison ran and report its arranged verdict."""
        record_name = _drain_digest_streams(kwargs)["record_name"]
        ran.append(f"checksum:{record_name}")
        return _StubPass(f"checksum {record_name}", checksum_verified)

    def _money(connection: Any, extracts: Any, *, query_path: Any = None) -> _StubPass:
        """Record that pass 3 ran, with how many extracts it was offered."""
        ran.append(f"money_parity:{len(tuple(extracts))}")
        return _StubPass("money totals", money_verified)

    # WHY : Refactoring Rationale: every seam is patched on the OWNING module rather than on `cli`.
    #   The handler imports each pass inside itself so that the read-only verbs reach neither the
    #   loader nor the verification package, which means no re-export on `cli` exists to patch.
    #   Patching the authority also reaches every call path, including the ones that resolve the
    #   name themselves.
    monkeypatch.setattr(row_counts, "verify_row_counts", _row_counts)
    monkeypatch.setattr(checksum, "compare_record_digests", _compare)
    # WHY : Assumptions: the sealed-column audit is stubbed here for the same reason the other
    #   three passes are -- these tests assert the gate's ORDER and COVERAGE, and the audit's own
    #   verdict is asserted by the six dedicated `verify-checksum` tests further down. It is still
    #   RECORDED, and its verdict is still arrangeable, so a test below can prove the gate is bound
    #   to it at all rather than merely printing it.
    monkeypatch.setattr(checksum, "audit_sealed_columns", _audit)
    monkeypatch.setattr(money_parity, "verify_money_totals", _money)
    # WHY : Assumptions: the two connection factories are patched SEPARATELY and each records
    #   nothing, because which authority each pass reached is asserted by a dedicated test below
    #   rather than folded into this arrangement. Both answer an object whose only obligation is to
    #   close, since every measurement is patched out.
    monkeypatch.setattr(row_counts, "open_reporting_connection", _NullConnection)
    monkeypatch.setattr(session, "open_verification_connection", _NullConnection)
    # WHY : Assumptions: the read-back is stubbed to an empty list rather than left to reach the
    #   patched connection. `_read_back` issues a real query, and a `_NullConnection` implementing a
    #   cursor purely to satisfy it would be a database double built for a test about ordering.
    monkeypatch.setattr(cli, "_read_back", lambda connection, target: [])
    # WHY : Assumptions: the load context is stubbed because resolving it reaches Parameter Store
    #   and the key-management service for the two targets that declare sealed columns, and these
    #   tests are about the gate's ordering and coverage rules. The handler's behaviour when that
    #   resolution FAILS is asserted separately, by the fatal-tier test below, so stubbing it here
    #   removes an environment dependency rather than a check.
    # WHY : Refactoring Rationale: the stub now supplies WORKING ciphers over a synthetic key, where
    #   it used to answer `None`. The change was forced by a correction on the other side: the
    #   comparison stub above now DRAINS the record streams, as the real comparison does, so
    #   `prepare_record` genuinely runs for every dataset -- and the loader correctly refuses to
    #   project a sealed column without its cipher. A `None` context therefore turned every one of
    #   these ordering tests into an assertion that the card load is refused, which is a real rule
    #   but not the one under test here.
    monkeypatch.setattr(cli, "_load_context_for", lambda target: _gate_load_context())
    return ran


class _NullCursor:
    """Stand in for a cursor over a table the gate tests leave empty.

    Purpose
    -------
    Answer the one read the gate takes for real -- the sealed columns' stored envelopes -- with no
    rows, so a gate test measures pass ORDER and pass PLUMBING without also having to populate two
    tables. Zero stored envelopes against zero expected values is a verified audit, so the audit
    contributes no verdict of its own to these tests.

    Attributes
    ----------
    statements : list[str]
        Every statement executed through this cursor, in order.
    """

    def __init__(self, statements: list[str]) -> None:
        """Create a cursor recording into a shared statement log.

        Parameters
        ----------
        statements : list[str]
            The connection's log, appended to on execute.

        Returns
        -------
        None

        Raises
        ------
        None
        """
        self.statements = statements

    def __enter__(self) -> _NullCursor:
        """Enter the cursor's context.

        Parameters
        ----------
        None

        Returns
        -------
        _NullCursor
            This cursor.

        Raises
        ------
        None
        """
        return self

    def __exit__(self, *exc: object) -> None:
        """Leave the cursor's context.

        Parameters
        ----------
        *exc : object
            The propagating exception triple, ignored.

        Returns
        -------
        None
            Never suppresses.

        Raises
        ------
        None
        """
        return None

    def execute(self, statement: str) -> None:
        """Record a statement without running it.

        Parameters
        ----------
        statement : str
            The statement text.

        Returns
        -------
        None
            Appends to :attr:`statements`.

        Raises
        ------
        None
        """
        self.statements.append(statement)

    def fetchmany(self, size: int) -> list[tuple[object, ...]]:
        """Answer no rows, ending the stream immediately.

        Parameters
        ----------
        size : int
            The batch size requested, ignored.

        Returns
        -------
        list[tuple[object, ...]]
            Always empty.

        Raises
        ------
        None
        """
        # WHY : Assumptions: an empty first batch ENDS the stream, which is the contract
        #   `_stream_rows` reads. A double that answered the same non-empty batch forever would make
        #   a streaming reader loop, so answering nothing is the only safe fixed reply.
        return []


class _NullConnection:
    """Stand in for an open connection that is closed and read only through empty cursors.

    Purpose
    -------
    Satisfy the two connection seams in the gate tests. Every pass measurement is patched out
    EXCEPT the sealed-column audit, which reads the two sealing tables for real, so the double has
    to answer a cursor as well as a close.

    Attributes
    ----------
    closes : int
        How many times this instance was closed.
    cursor_names : list[str | None]
        The name requested for each cursor opened, in order.
    statements : list[str]
        Every statement executed through any of this connection's cursors, in order.
    """

    def __init__(self) -> None:
        """Create an unclosed connection."""
        self.closes = 0
        self.cursor_names: list[str | None] = []
        self.statements: list[str] = []

    def cursor(self, name: str | None = None) -> _NullCursor:
        """Open a cursor, recording the name it was asked for.

        Parameters
        ----------
        name : str | None
            The server-side cursor name, or None for a client-side cursor.

        Returns
        -------
        _NullCursor
            A cursor answering no rows.

        Raises
        ------
        None
        """
        self.cursor_names.append(name)
        return _NullCursor(self.statements)

    def close(self) -> None:
        """Record a close.

        Parameters
        ----------
        None

        Returns
        -------
        None
            Mutates :attr:`closes`.

        Raises
        ------
        None
        """
        self.closes += 1


def _gate_covered_layouts() -> set[str]:
    """Return the layout names the combined gate is required to cover.

    Purpose
    -------
    Derive the expected coverage from the same two authorities the gate itself reads, so a test
    cannot agree with a gate that quietly stopped covering a dataset -- and cannot demand coverage
    of one whose extract does not exist.

    Parameters
    ----------
    None
        Reads the seed registry and the readers' committed-extract declaration.

    Returns
    -------
    set of str
        Every registered dataset's layout name except those whose reader declares no committed
        extract.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the unseeded layouts are excluded, and the exclusion is DERIVED rather than
    #   spelled as "except TRAN". `readers/transaction.py` declares no committed seed dataset
    #   because none ships: the `transactions` token stages the single 350-byte record
    #   `app/jcl/TRANFILE.jcl` primes the TRANSACT cluster from, whose unpopulated category code is
    #   four NUL bytes and does
    #   not decode as a whole transaction. `verify/row_counts.py` says the same thing from the other
    #   side, requiring `ledger.transactions` to hold zero rows after the ETL.
    return {
        seed_datasets.seed_dataset(token).layout_name
        for token in seed_datasets.seed_dataset_tokens()
        if ships_committed_extract(seed_datasets.seed_dataset(token).layout_name)
    }


def test_verify_all_runs_the_three_passes_in_the_mandated_order(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    """Run row counts, then record checksums, then money totals, and report one verdict.

    Purpose
    -------
    Assert the property the combined gate exists for. The three passes are each blind to the defect
    the next one catches, so the order is load-bearing: a count mismatch explains a checksum
    mismatch, which is what makes the expensive pass's failure interpretable.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to bind every pass and connection seam to a recording double.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered report, which is the artifact an operator acts on.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    None
    """
    ran = _arrange_gate(monkeypatch)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))

    assert cli.main(["verify-all"]) == EXIT_OK

    # WHY : Assumptions: the ORDER of the three pass names is asserted, not merely their presence.
    #   The gate declares the order and refuses a reordered set before running anything, so a set
    #   comparison would pass for an arrangement the gate would have rejected -- and the order is
    #   the whole reason a failure is interpretable.
    assert ran[0] == "row_counts"
    assert [name for name in ran if name.startswith("checksum:")], "pass 2 never ran"
    assert ran[-1].startswith("money_parity:")
    assert ran.index("row_counts") < ran.index(
        next(name for name in ran if name.startswith("checksum:"))
    )
    printed = capsys.readouterr().out
    assert "migration verification" in printed
    assert "3 mandatory pass(es)" in printed


def test_verify_all_covers_every_registered_dataset_in_pass_two(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Compare every registered dataset, so the verdict is over the whole load and not a subset."""
    ran = _arrange_gate(monkeypatch)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))

    assert cli.main(["verify-all"]) == EXIT_OK

    # WHY : Assumptions: the expected set is DERIVED from the same authorities the gate reads rather
    #   than listed here, so the assertion cannot agree with a gate that quietly stopped covering a
    #   dataset added later. Coverage is the property the mandate rests on: a verdict of "verified"
    #   over nine of ten datasets is the exact shape of false assurance the rule exists to remove.
    compared = {name.split(":", 1)[1] for name in ran if name.startswith("checksum:")}
    assert compared == _gate_covered_layouts()


def test_verify_all_offers_pass_three_every_money_bearing_extract(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Offer pass 3 one extract per money-bearing layout, which is what it refuses a run without."""
    ran = _arrange_gate(monkeypatch)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))

    assert cli.main(["verify-all"]) == EXIT_OK

    # WHY : Assumptions: the count is derived from the money pass's own declared inventory, because
    #   that is the authority that decides which extracts it requires -- it REFUSES the entire run
    #   when a layout shipping a committed extract contributes no total. A literal count here would
    #   pass while the gate offered the wrong extracts and pass 3 refused its own inputs.
    from carddemo_migration.verify.money_parity import declared_money_columns

    # WHY : Assumptions: the expectation intersects the money inventory with the gate's coverage.
    #   `ledger.transactions` HAS a declared money column and ships no extract, so the money pass
    #   reports it as a not-comparable line and must not be handed a `SourceExtract` for it -- which
    #   it would refuse to construct, because a path that cannot be read is not a source.
    expected = len(
        {column.layout_name for column in declared_money_columns().values()}
        & _gate_covered_layouts()
    )
    offered = next(int(name.split(":", 1)[1]) for name in ran if name.startswith("money_parity:"))
    assert offered == expected


def test_verify_all_gives_pass_three_a_materialised_extract_when_the_root_is_a_bucket(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Materialise an object-store extract for pass 3, and transfer each object only once.

    Purpose
    -------
    Assert the gate works in the ONE configuration the deployment actually runs. Both environment
    roots leave ``dataset_staging_root`` null, so ``infra/modules/step-functions-batch`` composes it
    as ``s3://<bucket>/<prefix>`` and the verification state's container reads that -- yet pass 3
    built its source as ``Path`` of that value, which collapses ``s3://bucket/key`` to the relative
    directory ``s3:/bucket/key``. Passes 1 and 2 materialised correctly, so the gate reported two
    successes and then failed with a bare ENOENT: every local run certified three of three while the
    nightly chain's verification state could not pass at all.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the gate's measurement seams, the staging root, the settings resolver and the client
        factory, so the test needs no network.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If pass 3 is offered a path that is not a readable local file, if any offered path still
        carries the object-store scheme, or if an object is transferred more than once.
    """
    _arrange_gate(monkeypatch)
    # WHY : Assumptions: the money stub is re-bound AFTER `_arrange_gate` so this test observes the
    #   extract PATHS rather than only how many were offered, which is all the shared arrangement
    #   records. The two answers differ precisely in the defect under test: the count was already
    #   correct while every path was unreadable.
    offered: list[tuple[str, bool]] = []

    def _record_money(connection: Any, extracts: Any, *, query_path: Any = None) -> _StubPass:
        """Record each offered extract's path and whether it was readable AT THAT MOMENT.

        Parameters
        ----------
        connection : Any
            The reporting connection, unused.
        extracts : Any
            The source extracts pass 3 was offered.
        query_path : Any, optional
            The committed query's location, unused.

        Returns
        -------
        _StubPass
            A verified stub verdict, so the gate reaches its success path.

        Raises
        ------
        None
            Recording cannot fail.
        """
        # WHY : Assumptions: `is_file()` is evaluated HERE and stored, not asserted after the run.
        #   The work directory is a `TemporaryDirectory` opened by `main` and removed when it
        #   returns, so a check made after `cli.main` would report False for a correctly
        #   materialised extract and the test would fail for the wrong reason.
        offered.extend((str(extract.path), extract.path.is_file()) for extract in extracts)
        return _StubPass("money totals", True)

    monkeypatch.setattr(money_parity, "verify_money_totals", _record_money)

    client = _CapableS3Client()
    delivered = {
        seed_datasets.seed_dataset(token).source_object
        for token in seed_datasets.seed_dataset_tokens()
        if seed_datasets.seed_dataset(token).layout_name in _gate_covered_layouts()
    }
    # WHY : Assumptions: the REAL committed extracts are seeded rather than synthetic bytes, because
    #   the shared arrangement's pass-2 stub drains the record streams exactly as the real
    #   comparison does. Synthetic bytes would fail to decode and the test would report a codec
    #   refusal instead of the resolution defect it exists to catch.
    for source_object in sorted(delivered):
        client.seed_object(
            _INBOX_BUCKET,
            f"{_INBOX_PREFIX}/{source_object}",
            (_EBCDIC_DIRECTORY / source_object).read_bytes(),
        )

    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, _inbox_root())
    monkeypatch.setattr(cli, "resolve_dataset_staging_settings", _test_settings)
    monkeypatch.setattr(cli, "_s3_client", lambda: client)

    assert cli.main(["verify-all"]) == EXIT_OK

    assert offered, "pass 3 was never reached, so nothing about its sources was proved"
    for rendered, readable in offered:
        assert not rendered.startswith(seed_datasets.OBJECT_STORE_SCHEME), (
            f"pass 3 was offered {rendered}, which still carries the object-store scheme"
        )
        assert readable, f"pass 3 was offered {rendered}, which is not a readable local file"
    # WHY : Assumptions: each delivered object is asserted to have been fetched EXACTLY once, which
    #   is what proves the resolution is memoised across passes 2 and 3 rather than repeated. Two
    #   transfers of one object are not merely wasteful: they let the two passes read different
    #   bytes if the object is replaced between them, which is the disagreement this gate exists
    #   to detect rather than to contain.
    fetched = [key for key in client.gets if key.startswith(f"{_INBOX_PREFIX}/")]
    assert sorted(fetched) == sorted(f"{_INBOX_PREFIX}/{name}" for name in delivered)


@pytest.mark.parametrize(
    ("failing", "expected_ran"),
    [("row_counts", ("row_counts",)), ("checksum", ("checksum",)), ("money_parity", ("money",))],
)
def test_verify_all_stops_at_the_first_pass_that_fails(
    failing: str, expected_ran: tuple[str, ...], monkeypatch: pytest.MonkeyPatch
) -> None:
    """Stop at the first negative verdict, so one cause is reported once rather than three times."""
    verdicts = {
        "row_counts_verified": failing != "row_counts",
        "checksum_verified": failing != "checksum",
        "money_verified": failing != "money_parity",
    }
    ran = _arrange_gate(monkeypatch, **verdicts)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))

    assert cli.main(["verify-all"]) == EXIT_FAILED

    # WHY : Assumptions: a failing pass 1 must leave passes 2 and 3 UNRUN, and the reason is not
    #   cost. A count mismatch means the wrong NUMBER of rows arrived, at which point the per-record
    #   comparison and the totals are guaranteed to differ too -- so running them reports three
    #   failures for one cause and makes an operator triage noise.
    if failing == "row_counts":
        assert ran == ["row_counts"]
    elif failing == "checksum":
        assert not [name for name in ran if name.startswith("money_parity:")]
    else:
        assert [name for name in ran if name.startswith("money_parity:")]


def test_verify_all_reports_success_only_when_all_three_passes_verified(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Exit zero only for a complete gate, because a partial run has proved nothing."""
    _arrange_gate(monkeypatch)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))
    assert cli.main(["verify-all"]) == EXIT_OK

    # WHY : Assumptions: each pass is failed IN TURN rather than only one of them, because the
    #   verdict is a conjunction and a single-case test would pass for a handler that read only one
    #   pass's result. `all(())` is True in Python, which is the specific way a gate that ran
    #   nothing comes to certify a load.
    for failing in ("row_counts_verified", "checksum_verified", "money_verified"):
        _arrange_gate(monkeypatch, **{failing: False})
        assert cli.main(["verify-all"]) == EXIT_FAILED, f"{failing} did not fail the gate"


def test_verify_all_audits_every_dataset_sealed_columns_and_lets_a_violation_fail_the_gate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Run the sealed-column audit once per dataset inside pass 2, and honour its verdict.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds every gate seam to a recording double.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a dataset is audited only when it happens to seal something, or if a violated audit
        still certifies the load.
    """
    ran = _arrange_gate(monkeypatch)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))
    assert cli.main(["verify-all"]) == EXIT_OK

    # WHY : Assumptions: the audit is asserted to run for EVERY covered dataset, not only the two
    #   that seal something. The nine seal-free records must be audited too, because "0 column(s)"
    #   is the positive statement that the digest covered their whole row -- and an audit that ran
    #   only where a sealed column exists could not distinguish a record with none from a record
    #   whose sealing declaration was accidentally dropped.
    assert {name.split(":", 1)[1] for name in ran if name.startswith("sealed:")} == (
        _gate_covered_layouts()
    )
    # WHY : Assumptions: the audit's verdict is asserted to fail the WHOLE gate, which is the
    #   property that makes it a check rather than a printout. It is folded in as a part of pass 2,
    #   so a violated audit has to make pass 2 fail and pass 3 not run at all -- reporting the
    #   finding beside a passing gate would let a cutover proceed over a dropped identifier.
    ran = _arrange_gate(monkeypatch, sealed_verified=False)
    assert cli.main(["verify-all"]) == EXIT_FAILED
    assert not [name for name in ran if name.startswith("money_parity:")]


def test_verify_all_reads_the_two_passes_under_two_different_authorities(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Use the reporting role for the server-side queries and the verifier for the row read-back."""
    from carddemo_migration.verify import checksum, money_parity

    seen: dict[str, object] = {}
    reporting = _NullConnection()
    verifying = _NullConnection()

    def _row_counts(connection: Any, *, query_path: Any = None) -> _StubPass:
        """Record which connection pass 1 was handed."""
        seen["row_counts"] = connection
        return _StubPass("row counts", True)

    def _money(connection: Any, extracts: Any, *, query_path: Any = None) -> _StubPass:
        """Record which connection pass 3 was handed."""
        seen["money_parity"] = connection
        return _StubPass("money totals", True)

    def _read_back(connection: Any, target: Any) -> list[Any]:
        """Record which connection the pass-2 read-back was handed."""
        seen["checksum"] = connection
        return []

    def _sealed(connection: Any, target: Any) -> dict[str, list[object]]:
        """Record which connection the pass-2 sealed-column read was handed."""
        seen["sealed"] = connection
        return {column: [] for column in target.sealed_fields().values()}

    monkeypatch.setattr(row_counts, "verify_row_counts", _row_counts)
    monkeypatch.setattr(money_parity, "verify_money_totals", _money)
    monkeypatch.setattr(
        checksum,
        "compare_record_digests",
        lambda **kwargs: _StubPass("checksum", bool(_drain_digest_streams(kwargs))),
    )
    # WHY : Assumptions: the sealed-column audit's VERDICT is stubbed while its READ is left to the
    #   intercepted `_sealed_envelopes` above, which is what records the authority the read was
    #   issued against. Leaving the verdict real would make this test fail on the arranged double's
    #   empty tables -- a correct refusal, but of a load, not of the authority under test here.
    monkeypatch.setattr(
        checksum, "audit_sealed_columns", lambda **kwargs: _StubPass("sealed columns", True)
    )
    monkeypatch.setattr(row_counts, "open_reporting_connection", lambda: reporting)
    monkeypatch.setattr(session, "open_verification_connection", lambda: verifying)
    monkeypatch.setattr(cli, "_read_back", _read_back)
    monkeypatch.setattr(cli, "_sealed_envelopes", _sealed)
    # WHY : Refactoring Rationale: the context supplies WORKING ciphers, where it used to answer
    #   `None`. The digest stub above now drains the record streams as the real comparison does, so
    #   `prepare_record` genuinely runs -- and the loader rightly refuses to project a sealed column
    #   with no cipher, which turned this test into an assertion about that refusal instead of about
    #   which authority each pass reached.
    monkeypatch.setattr(cli, "_load_context_for", lambda target: _gate_load_context())
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))

    assert cli.main(["verify-all"]) == EXIT_OK

    # WHY : Assumptions: the two authorities are asserted to be DIFFERENT objects, not merely
    #   present. Passes 1 and 3 execute the committed whole-schema queries and both check the live
    #   session is the reporting role; pass 2 compares whole rows field by field and needs unmasked
    #   row-level reads the reporting role's masked aggregate views cannot give. Collapsing them
    #   onto one role would either break pass 2 or widen reporting until it could read a primary
    #   account number -- and a test asserting only "a connection was passed" would not notice.
    assert seen["row_counts"] is reporting
    assert seen["money_parity"] is reporting
    assert seen["checksum"] is verifying
    # WHY : Assumptions: the sealed-column read is asserted against the VERIFIER too, and it is the
    #   sharper case of the same rule. That read returns the stored envelope itself -- the most
    #   sensitive column either table holds -- so issuing it on the reporting role would mean the
    #   reporting role could read enciphered identifiers, which is exactly the widening the separate
    #   authority exists to prevent.
    assert seen["sealed"] is verifying
    # WHY : Assumptions: both connections are asserted CLOSED, because the handler opens two and a
    #   leaked session on a read-only role is still a leaked session against Aurora's connection
    #   budget -- and the failing paths return early, so the close has to be in a `finally`.
    assert reporting.closes == 1
    assert verifying.closes == 1


def test_verify_all_offers_no_way_to_narrow_its_coverage() -> None:
    """Register no dataset selector, so a verdict cannot be obtained over a subset."""
    # WHY : Assumptions: the assertion is on the PARSER rather than an exit code. argparse answers
    #   an unknown flag with the same status 2 a bad value gets, so an exit-code test alone would
    #   keep passing if a `--dataset` option came back under a different name.
    subparsers = next(
        action for action in cli.build_parser()._actions if getattr(action, "choices", None)
    )
    flags = {
        option
        for action in subparsers.choices["verify-all"]._actions
        for option in action.option_strings
    }
    assert "--dataset" not in flags
    assert "--source" not in flags
    assert "--encoding" not in flags
    assert {"--source-root", "--sql-root"} <= flags


def test_verify_all_reports_the_fatal_tier_when_no_source_root_is_configured(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report a classified status, not a traceback, when the gate has no extracts to read."""
    _arrange_gate(monkeypatch)
    monkeypatch.delenv(seed_datasets.STAGING_ROOT_VARIABLE, raising=False)
    # WHY : Assumptions: the FAILED tier and not the fatal one, because `SeedDatasetError` is a
    #   source refusal and the gate's own guard raises it -- the tier a per-dataset pass reports
    #   for an unreadable extract. What matters more than which of the two is that it is CLASSIFIED:
    #   the batch state that gates the transition into posting branches on a number, and a traceback
    #   would make an unconfigured root indistinguishable from a failed verification.
    assert cli.main(["verify-all"]) == EXIT_FAILED


def test_verify_all_resolves_a_readable_extract_and_a_real_target_for_every_dataset(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reach a real reader, a real target and a real field set for all eleven datasets.

    Purpose
    -------
    Close the gap the ordering tests above cannot: they stub the comparison, so the per-dataset
    resolution inside pass 2 never runs and a dataset the loop could not resolve would go unnoticed.
    This captures what the comparison would have been handed and consumes the source iterator, so
    an unreadable extract, an unregistered target or an empty field set fails here.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Used to capture the comparison's arguments and to bind the connection seams.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    None
    """
    from carddemo_migration.verify import checksum, money_parity

    captured: list[dict[str, Any]] = []

    def _capture(**kwargs: Any) -> _StubPass:
        """Record one comparison's arguments and consume its source iterator."""
        # WHY : Assumptions: both iterators are DRAINED, through the shared helper that records why.
        #   The captured `source_records` is then a list this test can assert decoded at least one
        #   record from, which an unconsumed generator could never show.
        kwargs = _drain_digest_streams(kwargs)
        captured.append(kwargs)
        return _StubPass(f"checksum {kwargs['record_name']}", True)

    monkeypatch.setattr(
        row_counts, "verify_row_counts", lambda connection, **kw: _StubPass("row counts", True)
    )
    monkeypatch.setattr(
        money_parity,
        "verify_money_totals",
        lambda connection, extracts, **kw: _StubPass("money totals", True),
    )
    monkeypatch.setattr(checksum, "compare_record_digests", _capture)
    monkeypatch.setattr(row_counts, "open_reporting_connection", _NullConnection)
    monkeypatch.setattr(session, "open_verification_connection", _NullConnection)
    monkeypatch.setattr(cli, "_read_back", lambda connection, target: [])
    # WHY : Refactoring Rationale: the context carries WORKING ciphers and a subject for every user
    #   the security extract holds, where a first draft passed a context with none. That draft made
    #   the test nearly vacuous and measurement proved it: three targets declare sealed columns, and
    #   the loader correctly refuses to project one without its cipher -- so the pass-2 loop stopped
    #   at the SECOND dataset and only ACCOUNT was ever resolved. The assertion "captured is
    #   non-empty" passed while ten of eleven datasets went unexercised, which is exactly the gap
    #   this test was added to close.
    # WHY : Assumptions: the data key is a fixed synthetic value from a stub source, so no
    #   key-management service is reached and no real key material exists in the suite. The ciphers
    #   are exercised for their FRAMING, which is what `prepare_record` needs to produce a value.
    monkeypatch.setattr(cli, "_load_context_for", lambda target: _gate_load_context())
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))

    exit_code = cli.main(["verify-all"])

    assert exit_code in (EXIT_OK, EXIT_FAILED)
    # WHY : Assumptions: the coverage is asserted to be the WHOLE registry rather than merely
    #   non-empty, because "non-empty" is what let the vacuous draft above pass. Every registered
    #   dataset must have resolved a reader, a target and a field set, and every one must have
    #   decoded at least one record from its committed extract.
    assert {call["record_name"] for call in captured} == _gate_covered_layouts()
    for call in captured:
        target = target_for(call["record_name"])
        assert call["qualified_table"] == target.qualified_name
        # WHY : Assumptions: the field set is asserted NON-EMPTY and drawn from the TARGET's own
        #   declared keys, because an empty field set digests every record to the same value -- two
        #   sides that agree on nothing would compare equal, which is a passing verdict over an
        #   unread load.
        # WHY : Refactoring Rationale: the membership was first asserted against the LAYOUT's fields
        #   and that was wrong, as `SECUSER` proved: its comparable set includes `cognito_sub`, a
        #   target column produced by the SUBJECT_FOR_USER_ID projection with no copybook field of
        #   that name at all. The target is the authority for what can be compared, because it is
        #   the side that declares the projections.
        assert call["fields"], f"{call['record_name']} resolved no comparable field"
        assert set(call["fields"]) <= set(target.columns)
        assert call["source_records"], f"{call['record_name']} decoded no record"


class _GateDataKeys:
    """Answer every data-key request with one fixed synthetic key.

    Purpose
    -------
    Let the gate's pass-2 loop project the three targets that declare sealed columns without
    reaching a key-management service, so a test about dataset COVERAGE is not gated on a
    provisioned key.

    Attributes
    ----------
    None
        The double is stateless; the same key answers every request.
    """

    def data_key(self, *, key_id: str, encryption_context: Mapping[str, str]) -> Any:
        """Return one fixed data key for any request.

        Parameters
        ----------
        key_id : str
            Ignored. Recorded nowhere, because which key was asked for is not this test's subject.
        encryption_context : Mapping[str, str]
            Ignored, for the same reason.

        Returns
        -------
        DataKey
            A fixed plaintext key and a fixed wrapped form.

        Raises
        ------
        None
        """
        del key_id, encryption_context
        # WHY : Assumptions: the key is a fixed 32 bytes rather than random, so two runs of this
        #   test produce identical ciphertext. That is not needed by the assertions here, but a
        #   random key
        #   would make any future assertion on a projected value non-deterministic, and the fixed
        #   form costs nothing.
        return DataKey(plaintext=bytes(range(32)), wrapped=b"synthetic-wrapped-key")


def _gate_load_context() -> LoadContext:
    """Build a context every registered target can be projected with.

    Purpose
    -------
    Supply both ciphers and a subject for every user identifier the committed security extract
    holds, so the gate's pass-2 loop reaches all eleven datasets instead of stopping at the first
    target that declares a sealed column.

    Parameters
    ----------
    None
        The subjects are derived from the committed extract.

    Returns
    -------
    LoadContext
        A context carrying both ciphers over :class:`_GateDataKeys` and one subject per user.

    Raises
    ------
    None
    """
    keys = _GateDataKeys()
    # WHY : Assumptions: the subjects are DERIVED by reading the extract rather than written out as
    #   ten literals. The loader refuses a user identifier with no published subject, so a literal
    #   list would silently stop covering the security dataset the day the extract gained a user --
    #   which is the same class of staleness this test exists to catch elsewhere.
    subjects = {
        str(record["SEC-USR-ID"]).strip(): "00000000-0000-4000-8000-000000000000"
        for record in dataset_reader("SECUSER", "ebcdic")(_USER_EXTRACT)
    }
    return LoadContext(
        identifier_cipher=CustomerIdentifierCipher(key_id="synthetic-key", keys=keys),
        verification_value_cipher=CardVerificationValueCipher(key_id="synthetic-key", keys=keys),
        subjects=subjects,
    )


def test_stage_dataset_offers_no_override_of_an_authoritative_value() -> None:
    """Register no flag that could misroute a dataset or prune a generation out of turn.

    Purpose
    -------
    Assert the withdrawal of three overrides as a property of the parser rather than of one
    invocation. Each let a caller replace a value the seed registry or the durable reservation is
    the authority for, and each had a concrete destructive reading: a wrong bounded-context segment
    writes one context's master under another's prefix, past a least-privilege policy written per
    prefix; an out-of-sequence generation makes the retention sweep -- which keeps the newest five
    BY NUMBER -- prune a generation that is not the oldest.

    Parameters
    ----------
    None
        Reads the built parser.

    Returns
    -------
    None
        Nothing; a reinstated override is reported as an assertion failure naming it.

    Raises
    ------
    None
    """
    # WHY : Assumptions: the assertion is on the PARSER and not on exit codes. argparse answers an
    #   unknown flag with the same status 2 a bad value gets, so an exit-code test alone would keep
    #   passing if one of these came back under a different name.
    subparsers = next(
        action for action in cli.build_parser()._actions if getattr(action, "choices", None)
    )
    flags = {
        option
        for action in subparsers.choices["stage-dataset"]._actions
        for option in action.option_strings
    }
    for withdrawn in ("--source", "--generation", "--domain", "--object-name"):
        assert withdrawn not in flags, f"{withdrawn} was reinstated"
    # WHY : Assumptions: the two remaining selectors are asserted PRESENT alongside the four
    #   absences, because a parser that had lost `--dataset` would satisfy every absence above while
    #   being unusable -- and `--retain` is kept deliberately, with a floor, rather than removed.
    assert {"--dataset", "--business-date", "--retain"} <= flags


def test_the_orchestrator_passes_no_withdrawn_override() -> None:
    """Keep the state machine's staging argv within the surface the parser still accepts.

    Purpose
    -------
    The two halves cannot import one another, so nothing but a test holds them together. This is the
    same class of check the seed-dataset vocabulary agreement makes, applied to the flags: an
    override withdrawn here while the orchestrator still emitted it would fail every nightly staging
    branch in argument parsing, which is exactly how the three formerly-required flags failed.

    Parameters
    ----------
    None
        Reads the state machine definition from the repository.

    Returns
    -------
    None
        Nothing; a withdrawn flag still present in the definition is an assertion failure.

    Raises
    ------
    None
    """
    definition = (
        Path(__file__).resolve().parents[2]
        / "infra"
        / "modules"
        / "step-functions-batch"
        / "main.tf"
    ).read_text(encoding="utf-8")
    # WHY : Assumptions: the scan is for the flag SPELLINGS anywhere in the definition rather than a
    #   parse of the staging state's argv expression. The expression is built by Terraform functions
    #   across several lines, so a structural read would be a small HCL parser here; the spelling is
    #   what argparse rejects, and no other state has any use for these four flags.
    for withdrawn in ("--generation", "--domain=", "--object-name", "--source="):
        assert withdrawn not in definition, (
            f"the batch definition still passes {withdrawn}, which the command line no longer"
            " accepts; every staging branch would fail in argument parsing"
        )


#: A stored envelope that satisfies the framing the audit checks for.
#:
#: Assumptions: the marker is a REAL one drawn from the published set rather than an invented four
#: bytes, and the body is padded to the published minimum. A hand-picked marker would make this
#: constant agree with the test's own idea of the format instead of the format the writer produces.
_WELL_FORMED_ENVELOPE: Final[bytes] = b"CDCV" + bytes(SEALED_ENVELOPE_MIN_BYTES - 4)


#: A stored value of the right length whose leading bytes name no known envelope kind.
_FOREIGN_ENVELOPE: Final[bytes] = b"XXXX" + bytes(SEALED_ENVELOPE_MIN_BYTES - 4)


def _sealable_source_count(layout_name: str, extract: Path, field_name: str) -> int:
    """Count, straight from the extract, how many records carry a value that must be sealed.

    Purpose
    -------
    Give the sealed-column assertions an expectation measured INDEPENDENTLY of the command under
    test. A literal count would pass just as well against a command that counted rows of the target
    instead of values of the source, which is the one substitution that would make the audit
    circular.

    Parameters
    ----------
    layout_name : str
        The record layout to read.
    extract : Path
        The committed EBCDIC extract for that layout.
    field_name : str
        The copybook field whose sealable values are counted.

    Returns
    -------
    int
        How many records carry a present, non-blank value in that field.

    Raises
    ------
    None
    """
    return sum(
        1
        for record in dataset_reader(layout_name, "ebcdic")(extract)
        if str(record.get(field_name, "")).strip()
    )


def _drain_digest_streams(kwargs: Mapping[str, Any]) -> dict[str, Any]:
    """Consume both record streams a digest comparison was handed, returning them materialised.

    Purpose
    -------
    Let a stubbed comparison stand in for the real one WITHOUT changing what the handler under test
    actually does. Two obligations make draining mandatory rather than tidy, and a stub that skips
    either one measures its own laziness instead of the command.

    Parameters
    ----------
    kwargs : Mapping[str, Any]
        The comparison's keyword arguments, carrying ``source_records`` and ``target_records``.

    Returns
    -------
    dict[str, Any]
        The same arguments with both streams replaced by lists, so a caller can assert on them.

    Raises
    ------
    None
        Whatever a lazy reader raises on being read propagates, which is the point.
    """
    # WHY : Assumptions: the readers are lazy GENERATORS, so an extract that does not exist, does
    #   not divide into whole records or does not decode at its declared geometry raises only when
    #   it is read. A stub that captured the iterator without consuming it would pass for a gate
    #   pointed at an empty directory.
    # WHY : Assumptions: the source stream is also the sealable-value TAP, and the tally refuses to
    #   report counts whose stream never ended. The real comparison drains both sides with
    #   `zip_longest` to exhaustion -- its reporting limit caps only what it lists -- so a stub that
    #   returned early would leave the audit measuring an empty prefix of the extract.
    drained = dict(kwargs)
    for stream in ("source_records", "target_records"):
        drained[stream] = list(kwargs[stream])
    return drained


def _bind_verified_digest(monkeypatch: pytest.MonkeyPatch) -> None:
    """Make the digest comparison always agree, isolating the sealed-column audit.

    Purpose
    -------
    Let a test measure the audit's own contribution to the command's verdict. Reproducing a matching
    digest through the recording double would mean arranging a read-back that renders byte-for-byte
    like the projected source for every comparable field -- so a failure would be ambiguous between
    the audit and the arrangement, which is exactly the ambiguity these tests must not have.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher, scoped to the calling test.

    Returns
    -------
    None
        Patches ``verify.checksum.compare_record_digests`` in place.

    Raises
    ------
    None
    """
    from carddemo_migration.verify import checksum

    def _agreeing(**kwargs: Any) -> _StubPass:
        """Consume both record streams to exhaustion, then report agreement.

        Parameters
        ----------
        **kwargs : Any
            The comparison's keyword arguments, of which the two record streams are read.

        Returns
        -------
        _StubPass
            A verified pass labelled with the record name.

        Raises
        ------
        None
        """
        # WHY : Assumptions: the streams are drained through the shared helper rather than ignored,
        #   for the two reasons stated there. Draining the target side additionally keeps the
        #   streaming read-back cursor exercised on this path.
        return _StubPass(f"digest {_drain_digest_streams(kwargs)['record_name']}", True)

    # WHY : Assumptions: the stub is bound on the OWNING module, not on `cli`, because the handler
    #   imports the comparison inside itself so a read-only command reaches no verifier. There is no
    #   re-export on `cli` left to patch.
    monkeypatch.setattr(checksum, "compare_record_digests", _agreeing)


def _run_checksum_with_envelopes(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    *,
    layout_name: str,
    extract: Path,
    arranged: Mapping[str, Sequence[Sequence[object]]],
) -> int:
    """Run ``verify-checksum`` over one dataset with the sealed columns' rows arranged.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher, scoped to the calling test.
    fake_aurora : FakeAuroraDatabase
        The recording double the command reads through.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings naming an unreachable host.
    layout_name : str
        The dataset to verify.
    extract : Path
        The committed EBCDIC extract to read as the source.
    arranged : Mapping[str, Sequence[Sequence[object]]]
        Per statement fragment, the rows the double answers -- one entry per sealed column.

    Returns
    -------
    int
        The command's exit status.

    Raises
    ------
    None
    """
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    _bind_verified_digest(monkeypatch)
    monkeypatch.setattr(cli, "_load_context_for", lambda target: _gate_load_context())
    for fragment, rows in arranged.items():
        fake_aurora.arrange_rows(fragment, rows)
    return cli.main(
        [
            "verify-checksum",
            f"--dataset={layout_name}",
            f"--source={extract}",
            "--encoding=ebcdic",
        ]
    )


def test_verify_checksum_certifies_the_sealed_column_the_digest_cannot_compare(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report the card verification value's stored envelopes alongside the digest, and pass.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database, the digest stub and the cipher context.
    fake_aurora : FakeAuroraDatabase
        Answers the sealed column's read with one well-formed envelope per card.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered audit.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the audit is not rendered, or its counts do not match the source measurement.
    """
    # WHY : Assumptions: the expected count is taken from the EXTRACT by a second, independent read
    #   rather than asserted as 50, so this measures that the command counted source values. A
    #   command that counted target rows instead would agree with a literal and disagree here the
    #   moment the arranged row count and the record count differ -- which the next test makes so.
    expected = _sealable_source_count("CARD", _CARD_EXTRACT, "CARD-CVV-CD")
    assert expected > 0, "the card extract carries no verification value to seal"
    exit_code = _run_checksum_with_envelopes(
        monkeypatch,
        fake_aurora,
        aurora_settings,
        layout_name="CARD",
        extract=_CARD_EXTRACT,
        arranged={"cvv_encrypted": [(_WELL_FORMED_ENVELOPE,)] * expected},
    )
    reported = capsys.readouterr().out
    assert exit_code == EXIT_OK
    assert "sealed columns CARD" in reported
    assert (
        f"cvv_encrypted: expected {expected}, stored {expected}, malformed 0 [SEALED]" in reported
    )


def test_verify_checksum_fails_when_a_sealed_column_stored_nothing(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Refuse a load that wrote a null where every record supplied a value to seal.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database, the digest stub and the cipher context.
    fake_aurora : FakeAuroraDatabase
        Answers the sealed column's read with a null for every card.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered audit.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command reports success over a column that stored nothing.
    """
    # WHY : Assumptions: this is the exact defect the finding names. Before the audit, the digest
    #   necessarily excluded the sealed columns -- an envelope's initialisation vector is drawn per
    #   value, so its bytes are not a function of its source -- which left a load that dropped every
    #   stored verification value reporting a clean comparison. The digest is stubbed VERIFIED here
    #   precisely so the verdict under test is the audit's alone.
    expected = _sealable_source_count("CARD", _CARD_EXTRACT, "CARD-CVV-CD")
    exit_code = _run_checksum_with_envelopes(
        monkeypatch,
        fake_aurora,
        aurora_settings,
        layout_name="CARD",
        extract=_CARD_EXTRACT,
        arranged={"cvv_encrypted": [(None,)] * expected},
    )
    reported = capsys.readouterr().out
    assert exit_code == EXIT_FAILED
    assert f"cvv_encrypted: expected {expected}, stored 0, malformed 0 [VIOLATED]" in reported


def test_verify_checksum_fails_when_a_stored_envelope_is_not_a_known_envelope(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Refuse a column holding a value of the right length that names no envelope kind.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database, the digest stub and the cipher context.
    fake_aurora : FakeAuroraDatabase
        Answers with well-formed envelopes except for one foreign value.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered audit.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a foreign value is accepted, or is not counted as malformed.
    """
    # WHY : Assumptions: the foreign value is the RIGHT LENGTH and only its marker is wrong, so the
    #   framing check is what has to catch it. A short value would be caught by the length bound
    #   alone and would leave the marker check unexercised -- and the marker is the half that keeps
    #   a value recovered from one sealed column from being read as an envelope of the other kind.
    expected = _sealable_source_count("CARD", _CARD_EXTRACT, "CARD-CVV-CD")
    rows: list[Sequence[object]] = [(_WELL_FORMED_ENVELOPE,)] * (expected - 1)
    rows.append((_FOREIGN_ENVELOPE,))
    exit_code = _run_checksum_with_envelopes(
        monkeypatch,
        fake_aurora,
        aurora_settings,
        layout_name="CARD",
        extract=_CARD_EXTRACT,
        arranged={"cvv_encrypted": rows},
    )
    reported = capsys.readouterr().out
    assert exit_code == EXIT_FAILED
    assert f"cvv_encrypted: expected {expected}, stored {expected}, malformed 1 [VIOLATED]" in (
        reported
    )


def test_verify_checksum_audits_every_sealed_column_of_a_record_that_seals_two(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Read and report both of the customer master's sealed columns, not just the first.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database, the digest stub and the cipher context.
    fake_aurora : FakeAuroraDatabase
        Answers each sealed column's read separately.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered audit.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If either column is unreported, or the two are conflated into one measurement.
    """
    # WHY : Assumptions: the two columns are arranged with DIFFERENT row counts, so a command that
    #   read one column and reused its measurement for both would disagree on the second. Arranging
    #   them identically is how a per-column audit and a single-column audit become
    #   indistinguishable.
    ssn_expected = _sealable_source_count("CUSTOMER", _CUSTOMER_EXTRACT, "CUST-SSN")
    govt_expected = _sealable_source_count("CUSTOMER", _CUSTOMER_EXTRACT, "CUST-GOVT-ISSUED-ID")
    assert ssn_expected and govt_expected
    exit_code = _run_checksum_with_envelopes(
        monkeypatch,
        fake_aurora,
        aurora_settings,
        layout_name="CUSTOMER",
        extract=_CUSTOMER_EXTRACT,
        arranged={
            "ssn_encrypted": [(_WELL_FORMED_ENVELOPE,)] * ssn_expected,
            "govt_issued_id_encrypted": [(_WELL_FORMED_ENVELOPE,)] * (govt_expected - 1),
        },
    )
    reported = capsys.readouterr().out
    assert exit_code == EXIT_FAILED
    assert (
        f"ssn_encrypted: expected {ssn_expected}, stored {ssn_expected}, malformed 0 [SEALED]"
    ) in reported
    assert (
        f"govt_issued_id_encrypted: expected {govt_expected}, stored {govt_expected - 1},"
        " malformed 0 [VIOLATED]"
    ) in reported


def test_verify_checksum_states_the_absence_of_a_sealed_column_rather_than_omitting_it(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Report zero sealed columns for a record whose whole row the digest already covers.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database, the digest stub and the cipher context.
    fake_aurora : FakeAuroraDatabase
        Answers nothing, because no sealed column is read.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered audit.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the line is omitted, or a seal-free record is reported as unverified.
    """
    # WHY : Alternatives Considered: suppressing the line for the nine records that seal nothing.
    #   Rejected because "0 column(s)" is a POSITIVE coverage statement -- it tells an operator the
    #   digest covered the whole row -- and the finding this closes is precisely that the command's
    #   coverage was unstated. An omitted line reads as an unrun check, which is the ambiguity that
    #   let three columns go uncertified.
    exit_code = _run_checksum_with_envelopes(
        monkeypatch,
        fake_aurora,
        aurora_settings,
        layout_name="TCATBAL",
        extract=_CATEGORY_BALANCE_EXTRACT,
        arranged={},
    )
    reported = capsys.readouterr().out
    assert exit_code == EXIT_OK
    assert "sealed columns TCATBAL" in reported
    assert "0 column(s)" in reported


def test_verify_checksum_reads_each_sealed_column_from_its_own_target_table(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Compose the sealed read over the target's own qualified table and quoted column.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database, the digest stub and the cipher context.
    fake_aurora : FakeAuroraDatabase
        Records every executed statement.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a sealed read is missing, unquoted, or aimed at the wrong table.
    """
    ssn_expected = _sealable_source_count("CUSTOMER", _CUSTOMER_EXTRACT, "CUST-SSN")
    _run_checksum_with_envelopes(
        monkeypatch,
        fake_aurora,
        aurora_settings,
        layout_name="CUSTOMER",
        extract=_CUSTOMER_EXTRACT,
        arranged={
            "ssn_encrypted": [(_WELL_FORMED_ENVELOPE,)] * ssn_expected,
            "govt_issued_id_encrypted": [(_WELL_FORMED_ENVELOPE,)] * ssn_expected,
        },
    )
    target = target_for("CUSTOMER")
    # WHY : Assumptions: the assertion reads the target's OWN column names and qualified name rather
    #   than repeating them as literals, so a renamed column moves the expectation with the
    #   declaration instead of leaving a test asserting a column that no longer exists.
    for column in target.sealed_fields().values():
        statement = next(
            (sql for sql in fake_aurora.executed_sql() if f'"{column}"' in sql and "SELECT" in sql),
            None,
        )
        assert statement is not None, f"{column} was never read back"
        assert target.qualified_name in statement


def test_the_sealed_column_set_is_the_exact_complement_of_the_digested_set() -> None:
    """Partition every loadable target's columns into digested and sealed, with no overlap or gap.

    Parameters
    ----------
    None
        Reads the target registry.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any target declares a column that is both digested and sealed, or neither.
    """
    # WHY : Assumptions: this is the invariant that makes the coverage claim checkable at all. A
    #   column in NEITHER set is certified by nothing -- the defect the finding names -- and one
    #   in BOTH would be digested despite its bytes differing on every write, reporting a correct
    #   load as a difference on every run. Deriving both sets from one declaration is what makes
    #   either state unrepresentable, and this asserts the derivation holds for all eleven.
    for name in target_names():
        target = target_for(name)
        digested = set(target.comparable_fields())
        sealed = set(target.sealed_fields())
        assert not digested & sealed, f"{name} declares a column that is both digested and sealed"
        assert digested | sealed == set(target.columns), f"{name} leaves a column uncertified"


def test_the_row_read_back_streams_through_a_named_server_side_cursor(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Ask for a named cursor and consume it in bounded batches rather than fetching everything.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database seam.
    fake_aurora : FakeAuroraDatabase
        Records the cursor names asked for and the batch sizes requested.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the read-back does not ask for a server-side cursor, or reads the result set whole.
    """
    # WHY : Assumptions: the NAME is the whole subject. psycopg buffers an entire result set on
    #   `execute` for an unnamed cursor, so `fetchmany` over one of those bounds nothing at all --
    #   the read would look streamed and still hold every row. Asserting the name is what
    #   distinguishes a genuinely bounded read from a cosmetic one.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    target = target_for("TCATBAL")
    rows = [("t", "a", "c", "1.00")] * 3
    fake_aurora.arrange_rows("FROM " + target.qualified_name, rows)
    connection = aurora.connect(cli.resolve_aurora_settings(target.schema))
    try:
        streamed = list(cli._read_back(connection, target))
    finally:
        connection.close()

    assert len(streamed) == len(rows)
    assert fake_aurora.cursor_names, "the read-back asked for no server-side cursor"
    assert any(name and target.table in name for name in fake_aurora.cursor_names)
    # WHY : Assumptions: the batch size is asserted to be the declared constant rather than merely
    #   positive, because the constant is what bounds peak memory. A reader that passed the whole
    #   expected row count as its batch size would satisfy "positive" while streaming nothing.
    assert cli._READ_BACK_BATCH_ROWS > 0
    for cursor in fake_aurora.cursors:
        assert cli._READ_BACK_BATCH_ROWS in cursor.batch_sizes or not cursor.batch_sizes


def test_the_row_read_back_yields_before_the_whole_result_set_has_been_read(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Produce the first row without having fetched the last, which is what streaming means.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database seam.
    fake_aurora : FakeAuroraDatabase
        Answers a result set larger than one batch.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the reader has consumed the whole result set by the time it yields its first row.
    """
    # WHY : Refactoring Rationale: this asserts the BEHAVIOUR the finding asked for, not the call.
    #   The previous read-back used `fetchall()` and returned a list, so the first row was only
    #   available once the last had been read. A test that only checked `fetchmany` was called could
    #   be satisfied by a loop that drained everything before returning -- the difference is
    #   observable only by looking at what remains unread at the first yield.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    target = target_for("TCATBAL")
    batch = cli._READ_BACK_BATCH_ROWS
    rows = [("t", "a", "c", "1.00")] * (batch + 5)
    fake_aurora.arrange_rows("FROM " + target.qualified_name, rows)
    connection = aurora.connect(cli.resolve_aurora_settings(target.schema))
    try:
        stream = cli._read_back(connection, target)
        first = next(stream)
        assert first
        # WHY : Assumptions: the assertion is on the DOUBLE's remaining rows, because that is the
        #   only place "not yet read" is visible. The arranged set is one row-batch plus five, so a
        #   reader that fetched everything would leave nothing behind.
        remaining = sum(len(cursor.rows) for cursor in fake_aurora.cursors)
        assert remaining >= 5, "the first yield had already consumed the whole result set"
        assert len(list(stream)) == len(rows) - 1
    finally:
        connection.close()


def test_verify_money_parity_reads_the_source_extract_exactly_once(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Total every money field in one traversal, over a reader that can only be read once.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the database seam and the record source.
    fake_aurora : FakeAuroraDatabase
        Answers each money column's server-side total.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.
    capsys : pytest.CaptureFixture[str]
        Captures the rendered comparison.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the command re-reads the source, or totals a field to zero after the first pass.
    """
    # WHY : Assumptions: the source is a ONE-SHOT generator and the extract has FIVE money fields,
    #   so
    #   this arrangement is what the previous shape actually got wrong. It materialised the records
    #   and then re-passed them per field -- and the reason it had to materialise is that a re-pass
    #   over a one-shot reader totals zero on every pass after the first, which is a difference of
    #   zero that reads as agreement. Counting the traversals is how that reappearing is caught.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    target = target_for("ACCOUNT")
    reader, records = cli._records_for("ACCOUNT", str(_ACCOUNT_EXTRACT), "ebcdic", Path("."))
    money_fields = cli._money_field_names(reader)
    assert len(money_fields) > 1, "the account master must carry several money fields"
    traversals = 0

    def _once(layout_name: str, source: str, encoding: str, work_root: Path) -> tuple[Any, Any]:
        """Answer the reader and a generator that counts how many times it is traversed."""
        nonlocal traversals

        def _records() -> Any:
            """Yield every record once, recording the traversal."""
            nonlocal traversals
            traversals += 1
            yield from dataset_reader(layout_name, encoding)(Path(source))

        return reader, _records()

    monkeypatch.setattr(cli, "_records_for", _once)
    for field in money_fields:
        column = target.columns[field]
        fake_aurora.arrange_rows(f'SUM("{column}")', [(Decimal("0.00"),)])

    exit_code = cli.main(
        [
            "verify-money-parity",
            "--dataset=ACCOUNT",
            f"--source={_ACCOUNT_EXTRACT}",
            "--encoding=ebcdic",
        ]
    )
    reported = capsys.readouterr().out

    assert traversals == 1, f"the source was read {traversals} times"
    # WHY : Assumptions: every field is asserted to be REPORTED, not merely that one pass happened.
    #   A single traversal that totalled only the first field would satisfy the count above while
    #   leaving four money columns uncompared -- which is the failure the one-pass rewrite must not
    #   have introduced.
    for field in money_fields:
        assert f"ACCOUNT.{field} ->" in reported
    assert exit_code in (EXIT_OK, EXIT_FAILED)


#: Values the recording double CAN present in place of a count or a total.
#:
#: Assumptions: a binary float is deliberately ABSENT from both parametrisations below, and its
#: absence is a property of the double rather than a gap in the check. `FakeAuroraDatabase`
#: refuses to hold a float in an arranged row at all -- money is exact fixed point end to end --
#: so the one representation a driver mapping is most likely to substitute cannot be arranged on
#: it. The float case is covered at the module level, in `test_verification.py`, against the
#: narrow single-value stub that exists for exactly that reason.
_INEXACT_COUNTS: Final[tuple[object, ...]] = (Decimal("1.5"), "7", True, None)


_INEXACT_TOTALS: Final[tuple[object, ...]] = (Decimal("1.500"), "7.00", Decimal("7"))


@pytest.mark.parametrize("inexact", _INEXACT_COUNTS, ids=str)
def test_verify_row_counts_classifies_an_inexact_count_rather_than_raising(
    inexact: object,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Report the failed tier when the driver answers a count no ``COUNT(*)`` produces.

    Parameters
    ----------
    inexact : object
        A value that is not an exact whole number.
    monkeypatch : pytest.MonkeyPatch
        Binds the database seam.
    fake_aurora : FakeAuroraDatabase
        Answers the count query with the inexact value.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the refusal escapes as a traceback instead of becoming a classified status.
    """
    # WHY : Refactoring Rationale: the exact-count guard lives in `row_counts.count_target_rows` and
    #   was already asserted there, but the PER-DATASET command caught only the operational error
    #   tuple -- so the guard's refusal escaped `main` as a traceback and the classified status the
    #   batch state branches on was lost. That is the reachable half of the finding: the guard
    #   existing is not the same as the guard being usable from the verb that trips it.
    # WHY : Assumptions: the refusal is asserted to be the FAILED tier and not the fatal one. A
    #   count that is not a count means the query is not the query -- a step that did not complete
    #   rather than an unconfigured environment -- and 8 is the status the orchestrator retries.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    fake_aurora.arrange_rows("COUNT(*) FROM", [(inexact,)])
    assert (
        cli.main(
            [
                "verify-row-counts",
                "--dataset=TCATBAL",
                f"--source={_CATEGORY_BALANCE_SEED}",
                "--encoding=ascii",
            ]
        )
        == EXIT_FAILED
    )


@pytest.mark.parametrize("inexact", _INEXACT_TOTALS, ids=str)
def test_verify_money_parity_classifies_an_inexact_total_rather_than_raising(
    inexact: object,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Report the failed tier when the driver answers a total that is not exact at the money scale.

    Parameters
    ----------
    inexact : object
        A value that is not an exact decimal at the declared money scale.
    monkeypatch : pytest.MonkeyPatch
        Binds the database seam.
    fake_aurora : FakeAuroraDatabase
        Answers the total query with the inexact value.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.

    Returns
    -------
    None
        The assertion is the result.

    Raises
    ------
    AssertionError
        If the refusal escapes as a traceback instead of becoming a classified status.
    """
    # WHY : Assumptions: the money command is asserted alongside the count command because it had
    #   the same gap for the same reason, and the two verbs must agree on the tier -- a batch state
    #   branches on the number, so one pass reporting a traceback where its sibling reports 8 is
    #   exactly the inconsistency the shared refusal set was introduced to remove.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    fake_aurora.arrange_rows("SUM(", [(inexact,)])
    assert (
        cli.main(
            [
                "verify-money-parity",
                "--dataset=TCATBAL",
                f"--source={_CATEGORY_BALANCE_SEED}",
                "--encoding=ascii",
            ]
        )
        == EXIT_FAILED
    )


def test_every_verification_pass_refusal_is_classified_by_one_shared_set() -> None:
    """Cover each pass's refusal base, and its specialisations, from one declaration.

    Parameters
    ----------
    None
        Reads the command line's refusal set.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a pass's refusal base is missing, or a specialisation is not covered by it.
    """
    from carddemo_migration.verify.checksum import ChecksumVerificationError, TimestampContractError
    from carddemo_migration.verify.money_parity import (
        MoneyParityVerificationError,
        MoneyQueryError,
        MoneyResultSetContractError,
    )
    from carddemo_migration.verify.row_counts import (
        RowCountVerificationError,
        VerificationQueryError,
    )

    covered = cli._verification_errors()
    assert set(covered) == {
        ChecksumVerificationError,
        MoneyParityVerificationError,
        RowCountVerificationError,
    }
    # WHY : Assumptions: each SPECIALISATION is asserted to be covered by the set rather than listed
    #   in it, because that is the property that makes the set maintainable. A pass that grows a new
    #   refusal derived from its own base is covered the day it is written; a leaf list would leave
    #   it uncaught until an operator saw the traceback.
    for specialisation in (
        TimestampContractError,
        MoneyQueryError,
        MoneyResultSetContractError,
        VerificationQueryError,
    ):
        assert issubclass(specialisation, covered)


def _substituted_sql_root(tmp_path: Path, text: str) -> Path:
    """Build a distribution root whose two committed queries have been replaced.

    Purpose
    -------
    Present the command line with the one input ``--sql-root`` genuinely controls -- a directory --
    holding query files that are readable, single-statement and harmless, yet are not the committed
    queries. That is the substitution the flag would enable if the passes checked only shape.

    Parameters
    ----------
    tmp_path : Path
        The test's temporary directory.
    text : str
        The replacement query text, written to both query files.

    Returns
    -------
    Path
        A root that satisfies ``<root>/sql/verify/<name>`` for both queries.

    Raises
    ------
    None
    """
    # WHY : Assumptions: BOTH queries are replaced from one helper, because `verify-all` reads both
    #   and a root that substituted only one would let the gate fail at pass 1 for the right reason
    #   and never reach the pass that reads the other. Substituting both keeps each test's own
    #   assertion about the pass it names.
    directory = tmp_path / "substituted" / "sql" / "verify"
    directory.mkdir(parents=True)
    for name in ("row_counts.sql", "money_totals.sql"):
        (directory / name).write_text(text, encoding="utf-8")
    return tmp_path / "substituted"


def test_verify_row_count_report_refuses_a_sql_root_that_substitutes_the_committed_query(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Refuse a query that is well formed but is not the one whose digest the pass pins.

    Parameters
    ----------
    tmp_path : Path
        Holds the substituted distribution root.
    monkeypatch : pytest.MonkeyPatch
        Binds the database seam.
    fake_aurora : FakeAuroraDatabase
        Records what, if anything, was executed.
    aurora_settings : AuroraConnectionSettings
        Synthetic settings.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the substituted query runs, or the refusal escapes as a traceback.
    """
    # WHY : Assumptions: the substitute is DELIBERATELY harmless -- a single `SELECT` of six literal
    #   columns, matching the committed report's own column count. A hostile substitute would be
    #   refused by the shape guards alone, which is exactly the reading the finding rejects: shape
    #   checks establish that a cursor can run the text, not that the text is the committed report.
    #   A benign substitute is the only probe that can tell identity verification from shape
    #   verification.
    _bind_database(monkeypatch, fake_aurora, aurora_settings)
    # WHY : Assumptions: the REPORTING connection is bound as well as the loader's, because this
    #   command resolves the reporting role from the environment before it reads the query. Without
    #   the binding the run reports the fatal tier for an unresolvable environment and never reaches
    #   the identity check this test is about.
    fake_aurora.arrange_rows("current_user", [(reporting_role(),)])
    monkeypatch.setattr(
        row_counts,
        "open_reporting_connection",
        lambda: fake_aurora.connect(
            **replace(aurora_settings, user=reporting_role()).as_connection_params()
        ),
    )
    root = _substituted_sql_root(tmp_path, "SELECT 'x', 'y', 1, 1, 0, 'MATCHED'\n")

    exit_code = cli.main(["verify-row-count-report", "--sql-root", str(root)])

    assert exit_code == EXIT_FAILED
    # WHY : Assumptions: the double is asserted to have executed NO substituted text, because a
    #   refusal arriving after execution would already have run an operator-supplied statement on a
    #   live session. The identity check has to precede execution, not explain it afterwards.
    for statement in fake_aurora.executed_sql():
        assert "'MATCHED'" not in statement


def test_verify_all_refuses_a_sql_root_that_substitutes_the_committed_query(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Fail the combined gate when the query root does not hold the committed report.

    Parameters
    ----------
    tmp_path : Path
        Holds the substituted distribution root.
    monkeypatch : pytest.MonkeyPatch
        Binds the gate's connection seams.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the gate certifies a load against a substituted query, or raises instead of classifying.
    """
    # WHY : Assumptions: the gate is exercised with its passes REAL rather than stubbed, because the
    #   subject is the query the passes read. Stubbing them would remove the read. Only the two
    #   connections are doubled, and the refusal happens before either is used.
    from carddemo_migration.verify import row_counts as row_counts_module
    from carddemo_migration.verify import session as session_module

    monkeypatch.setattr(row_counts_module, "open_reporting_connection", _NullConnection)
    monkeypatch.setattr(session_module, "open_verification_connection", _NullConnection)
    monkeypatch.setenv(seed_datasets.STAGING_ROOT_VARIABLE, str(_EBCDIC_DIRECTORY))
    root = _substituted_sql_root(tmp_path, "SELECT 'x', 'y', 1, 1, 0, 'MATCHED'\n")

    assert cli.main(["verify-all", "--sql-root", str(root)]) == EXIT_FAILED


def test_no_verification_command_accepts_query_text(tmp_path: Path) -> None:
    """Offer a query ROOT and never query text, so the executed statement is always committed.

    Parameters
    ----------
    tmp_path : Path
        Unused; present so the signature matches its siblings and the root is available.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any verification command registers a flag through which SQL text could be supplied.
    """
    # WHY : Assumptions: the check is that no flag exists to carry TEXT, a stronger property than
    #   the digest check and what keeps it strong. A digest guard on a path is only as good as there
    #   being no second route in: a `--sql` or `--query` option would hand text straight to a cursor
    #   with the file, and its digest, never consulted.
    # WHY : Assumptions: `--sql` is deliberately NOT probed, and the reason is argparse rather
    #   than this package: prefix abbreviation is on by default, so `--sql` is an unambiguous
    #   abbreviation of the legitimate `--sql-root` and is accepted as one. That is harmless --
    #   the value is still read as a directory and the file found there is still digest-verified
    #   -- but probing it would assert a refusal argparse's own conventions do not make.
    parser = cli.build_parser()
    forbidden = ("--query", "--statement", "--query-text", "--sql-text")
    for command in ("verify-row-count-report", "verify-all"):
        for flag in forbidden:
            with pytest.raises(SystemExit):
                parser.parse_args([command, flag, "SELECT 1"])
    assert tmp_path.exists()


#: A driver failure message shaped like the one psycopg composes for an unreachable cluster.
#:
#: Assumptions: the endpoint, the private address and the port are all synthetic and are held as
#: constants so a test asserts on the same strings it planted. The shape matters more than the
#: values: psycopg's connection failure names all three, which is why this class of message may not
#: be quoted.
_LEAKY_ENDPOINT: Final[str] = "aurora-x.cluster-abc.eu-west-1.rds.amazonaws.com"


_LEAKY_ADDRESS: Final[str] = "10.0.3.42"


_LEAKY_DRIVER_TEXT: Final[str] = (
    f'connection to server at "{_LEAKY_ENDPOINT}" ({_LEAKY_ADDRESS}), port 5432 failed'
)


def test_a_driver_failure_is_reported_without_its_endpoint_or_address() -> None:
    """Report a driver failure by class and SQLSTATE, never by the text naming the cluster.

    Parameters
    ----------
    None
        Builds a driver failure directly.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any part of the driver's own message reaches the rendered report.
    """
    # WHY : Assumptions: a REAL driver exception class is raised rather than a stand-in, because the
    #   rendering branches on membership of the driver's error base and a stand-in would take the
    #   package-authored branch -- which quotes the message, and would therefore pass while proving
    #   the opposite of the intended property.
    import psycopg

    class _Unreachable(psycopg.OperationalError):
        """A connection failure that publishes a SQLSTATE, as the driver does."""

        sqlstate = "08006"

    rendered = cli._reported(_Unreachable(_LEAKY_DRIVER_TEXT))

    assert "08006" in rendered
    assert _Unreachable.__name__ in rendered
    for disclosed in (_LEAKY_ENDPOINT, _LEAKY_ADDRESS, "5432", _LEAKY_DRIVER_TEXT):
        assert disclosed not in rendered, f"{disclosed} reached the report"
    # WHY : Assumptions: a driver failure with NO sqlstate still renders, because a connection that
    #   never reached the server has no SQLSTATE to publish -- and that is exactly the failure whose
    #   message names the endpoint, so a renderer that fell back to quoting would leak on precisely
    #   the case it exists for.
    without_state = cli._reported(psycopg.OperationalError(_LEAKY_DRIVER_TEXT))
    assert without_state == "OperationalError"


def test_an_operating_system_failure_is_reported_without_its_filename() -> None:
    """Report an OSError by class and errno symbol, never by the path it names.

    Parameters
    ----------
    None
        Builds operating-system failures directly.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a filename reaches the rendered report, or the errno symbol does not.
    """
    # WHY : Assumptions: the errno SYMBOL is asserted present, not merely the absence of the path.
    #   Withholding the path is only acceptable because what replaces it is actionable: ENOENT,
    #   EACCES and EISDIR each point an operator at a different remedy, where a bare class name
    #   would point at none.
    secret = "/private/inbox/AWS.M2.CARDDEMO.ACCTDATA.PS"
    for failure, symbol in (
        (FileNotFoundError(errno.ENOENT, "No such file or directory", secret), "ENOENT"),
        (PermissionError(errno.EACCES, "Permission denied", secret), "EACCES"),
        (IsADirectoryError(errno.EISDIR, "Is a directory", secret), "EISDIR"),
    ):
        rendered = cli._reported(failure)
        assert symbol in rendered
        assert type(failure).__name__ in rendered
        assert secret not in rendered


def test_a_package_authored_refusal_is_quoted_but_cannot_forge_a_log_line() -> None:
    """Quote this package's own refusal, with everything outside printable ASCII replaced.

    Parameters
    ----------
    None
        Builds a package refusal directly.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a control character survives, or an allow-listed message is withheld.
    """
    # WHY : Assumptions: a package-authored refusal IS quoted, and that asymmetry is the design.
    #   Those messages are composed here from allow-listed metadata -- an operation, a qualified
    #   table, a row count, a SQLSTATE -- so withholding them would remove the only actionable text
    #   an operator gets while protecting nothing.
    from carddemo_migration.loaders.aurora import AuroraLoadError

    allowed = 'merge into "card"."cards" failed after 50 row(s)'
    assert cli._reported(AuroraLoadError(allowed)) == allowed
    # WHY : Assumptions: the forged line is the CWE-117 case in full -- a carriage return and a line
    #   feed followed by text shaped like a second log record. Sanitising is what keeps a refusal
    #   quotable at all: without it, any message carrying operator-supplied text could invent a
    #   reassuring record beneath a real failure.
    forged = cli._reported(AuroraLoadError("real failure\r\nINFO root: load completed cleanly"))
    assert "\r" not in forged
    assert "\n" not in forged
    assert "real failure" in forged


def test_decode_record_reports_an_unreadable_source_without_echoing_it_verbatim(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    """Sanitize the operator-supplied path before echoing it, and withhold the errno message text.

    Parameters
    ----------
    tmp_path : Path
        Holds the directory used as an unreadable source.
    capsys : pytest.CaptureFixture[str]
        Captures the error stream the handler writes.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a control character survives into the report, or the errno symbol is absent.
    """
    # WHY : Assumptions: an ABSENT path is named rather than a directory, and the distinction is
    #   that the two reach different guards. A directory is refused earlier, by the handler's own
    #   regular-file check, which composes its own already-sanitised message; only an absent or
    #   unopenable path reaches the OSError clause this test is about.
    # WHY : Assumptions: the name carries a carriage return AND a line feed, which is the CWE-117
    #   vector in full: echoed verbatim, either one splits the single line this handler writes, and
    #   the text after it is free to look like a record of its own.
    hostile = tmp_path / "carriage\rreturn\nname.PS"
    assert not hostile.exists()
    assert (
        cli.main(["decode-record", "--dataset=ACCOUNT", f"--source={hostile}", "--record=1"])
        == EXIT_FAILED
    )
    reported = capsys.readouterr().err

    assert "\r" not in reported
    assert reported.count("\n") == 1, "the report forged an extra log line"
    # WHY : Assumptions: the errno symbol is asserted PRESENT and the OSError's own text absent, so
    #   the report is checked to be actionable as well as safe. `str(FileNotFoundError)` repeats the
    #   filename a second time, unsanitised, which is the disclosure the symbol replaces.
    assert "ENOENT" in reported
    assert "No such file or directory" not in reported


def _orchestrator_definition() -> str:
    """Read the nightly state-machine definition from the repository.

    Parameters
    ----------
    None
        Resolves the path from this module's own location.

    Returns
    -------
    str
        The module's HCL, read as text.

    Raises
    ------
    None
    """
    return (
        Path(__file__).resolve().parents[2]
        / "infra"
        / "modules"
        / "step-functions-batch"
        / "main.tf"
    ).read_text(encoding="utf-8")


def _orchestrator_code() -> str:
    """Read the nightly state-machine definition with its comment lines removed.

    Purpose
    -------
    Give the assertions that check for the ABSENCE of something a text that contains only what
    Terraform acts on. The definition documents its own decisions extensively, including by naming
    the very flags and paths that were withdrawn, so a scan over the raw file reports a match in a
    sentence explaining why that match must not appear.

    Parameters
    ----------
    None
        Reads the definition through :func:`_orchestrator_definition`.

    Returns
    -------
    str
        The definition with every whole-line ``#`` comment dropped.

    Raises
    ------
    None
    """
    # WHY : Trade-offs: whole LINE comments only, matched on the first non-blank characters, with no
    #   HCL lexer. A trailing comment on a code line survives, which is harmless here because the
    #   assertions look for absence and a surviving comment can only cause a false FAILURE, never a
    #   false pass -- the same direction of error the package's own SQL comment stripper accepts,
    #   and for the same reason.
    return "\n".join(
        line
        for line in _orchestrator_definition().splitlines()
        if not line.lstrip().startswith("#")
    )


def test_the_orchestrator_loads_reconciles_and_verifies_before_it_posts() -> None:
    """Pin the chain's ordering, so no state can be reached without the gate before it.

    Parameters
    ----------
    None
        Reads the state machine definition from the repository.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If a state is bypassed, or business processing becomes reachable without verification.
    """
    # WHY : Assumptions: the ORDERING is asserted, not merely the presence of the states, because
    #   presence is not the property that matters. Each of the three is a precondition of the next
    #   --
    #   reconciling before the load would advance the identity sequence past rows that are not
    #   there,
    #   and verifying before the reconciliation would certify a sequence still pointing inside the
    #   loaded range -- so a graph holding all three in the wrong order is as wrong as one missing
    #   them.
    # WHY : ⚠️ Refactoring Rationale: the edges named here are the chain's, and two of the six this
    #   case once listed -- `LoadSeedDatasets` and `ReconcileTransactionSequence` -- no longer exist
    #   as states. Neither was deleted: both are WITHDRAWN because the work each did is performed by
    #   the per-dataset `refresh-dataset` branch of the staging Map, which fetches the extract,
    #   stages the generation, LOADS the target table, runs all three verification passes and, for
    #   the transaction master alone, reconciles the identifier allocator -- in that order, inside
    #   one step. Standing them up again as states of their own would run each a second time, and
    #   the ordering property this case exists to hold would then be asserted over a graph whose
    #   states duplicate one another.
    # WHY : ⚠️ Refactoring Rationale: a THIRD state named here -- `VerifyMigration`, with its
    #   `CheckVerificationExitCode` Choice -- is withdrawn as well, and for a different reason from
    #   the other two. It was a TWELFTH work state against the eleven specification section 0.4.1.7
    #   enumerates by name, so it was a topology change rather than a repaired omission. Its
    #   substance is not lost: every one of the three verification passes runs inside the
    #   per-dataset `refresh-dataset` branch, over the dataset that branch just loaded, and a failed
    #   branch fails the staging Map and with it the chain. Verification therefore still precedes
    #   business processing -- one state earlier and per dataset.
    # WHY : Assumptions: the ordering guarantee is UNCHANGED and is still asserted, because the
    #   composition preserves it rather than assuming it away: the load precedes the reconciliation
    #   inside the refresh step -- reconciling first would advance the identity sequence past rows
    #   that are not there -- and verification still stands between the refresh and the
    #   first business-processing state. What moved is where the edges are enforced, from
    #   the graph to the step's own committed step list, which
    #   `test_refresh_steps_reconcile_the_allocator_only_for_the_transaction_master` pins.
    # WHY : ⚠️ Refactoring Rationale: the edges are matched over the COMMENT-STRIPPED definition
    #   with runs of whitespace collapsed, where this case once matched each edge's exact
    #   indentation against the raw file. The old form encoded HCL alignment as a test fixture, so
    #   it broke on a change that preserved every property it exists to assert: nesting the
    #   verification gate inside the staging state indented all three edges, and `terraform fmt`
    #   then realigned them, which turned a passing assertion into three failures without any edge
    #   moving. Collapsing whitespace makes the assertion depend on the graph rather than on the
    #   formatter.
    # WHY : Assumptions: matching over `_orchestrator_code` rather than the raw text is what the
    #   indentation was standing in for. The concern it addressed is real -- the definition
    #   documents its own decisions by naming the very edges it declares, so a bare `Next = "X"`
    #   substring would match a sentence as readily as a transition -- and dropping whole-line
    #   comments removes that class of false match at its source instead of relying on prose being
    #   indented differently from code.
    code = re.sub(r"[ \t]+", " ", _orchestrator_code())
    # WHY : Assumptions: the first of the three is the staging Map's exit edge, which is what makes
    #   the two halves of the ordering meet: the Map is where the load now happens, so an exit edge
    #   naming anything but the gate would put a business state after a refresh that nothing
    #   verified.
    for edge in (
        'Next = "VerifyMigration"',
        'Next = "CheckVerificationExitCode"',
        'Next = "PreflightDailyTransactions"',
    ):
        assert code.count(edge) == 1, f"the chain does not carry exactly one {edge}"
    # WHY : Assumptions: the count of edges naming the first business-processing state is asserted
    #   to
    #   be exactly ONE, which is what makes the gate unavoidable rather than merely present. A
    #   second
    #   edge into it from anywhere -- a catch handler, an added state, the staging Map's own Next --
    #   would give the chain a path that posts against unverified data, and every other assertion
    #   here would still pass. After the gate was nested, the ONE edge is the staging state's own
    #   `Next`: the gate's clean verdict now reaches the first business state by completing its
    #   branch and letting the enclosing state take that edge, rather than by naming it directly.
    assert code.count('= "PreflightDailyTransactions"') == 1


def test_the_orchestrator_names_no_extract_file_and_no_local_staging_path() -> None:
    """Keep the extract inventory in the package's registry and the source in the object store.

    Parameters
    ----------
    None
        Reads the state machine definition from the repository.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the definition names an extract file, or configures a container filesystem path as the
        staging root.
    """
    # WHY : Assumptions: no extract FILE NAME may appear in the definition, because the seed
    #   registry
    #   is the single authority for the eleven of them. A definition that named one would be a
    #   second
    #   inventory free to drift, and the drift's failure mode is a branch that stages the wrong
    #   bytes
    #   under the right dataset's prefix -- which every count and digest downstream would then
    #   confirm as correct.
    definition = _orchestrator_definition()
    for token in seed_datasets.seed_dataset_tokens():
        source_object = seed_datasets.seed_dataset(token).source_object
        assert source_object not in definition, f"the definition names the extract {source_object}"
    # WHY : Assumptions: the staging root is asserted to be an OBJECT-STORE URI composed from the
    #   bucket input. The previous value was an absolute container path that no module mounted and
    #   the image does not populate, so every staging branch would have failed to find its extract
    #   on
    #   every execution -- a defect that only a check on the KIND of value can catch, since any path
    #   string is syntactically fine.
    # WHY : ⚠️ Refactoring Rationale: the local asserted here is `dataset_staging_root`, where it was
    #   `dataset_inbox_uri`. The two were competing spellings of one value -- "where the seed
    #   extracts are" -- composed from two different prefix inputs, and the divergence was the
    #   defect: the verification gate read one prefix while the per-dataset refresh read the other,
    #   so a gate that found nothing and a refresh that found everything were both working as
    #   written. The survivor composes from `dataset_source_extract_prefix`, which is the prefix the
    #   environment roots actually wire from the dataset module's own output, and it is shared by
    #   both consumers so they cannot come to disagree again.
    # WHY : Assumptions: the KIND of value is what is checked, not merely its presence. The value
    #   this replaced was an absolute container path that no module mounts and the image does not
    #   populate, so every staging branch would have failed to find its extract on every
    #   execution --
    #   a defect only a check on the kind can catch, since any path string is syntactically fine.
    assert "Value = local.dataset_staging_root" in definition
    assert (
        "coalesce(\n    var.dataset_staging_root,\n"
        '    "s3://${var.dataset_bucket_name}/${var.dataset_source_extract_prefix}",'
    ) in definition
    assert "/mnt/" not in _orchestrator_code(), "a container filesystem path is configured"
    # WHY : Assumptions: the load and verification states pass NO --source, which is what makes the
    #   assertion above enforceable: if they named a source, the extract file names would have to
    #   appear here whatever the staging root was.
    # WHY : Assumptions: this one reads the COMMENT-STRIPPED text, because the definition explains
    #   at length why it passes no source -- so a scan of the raw file matches the explanation and
    #   reports a failure for the very reasoning that documents the property.
    assert "--source" not in _orchestrator_code()


def test_the_orchestrator_invokes_only_verbs_the_command_line_registers() -> None:
    """Check every verb the definition invokes against the parser's own subcommand list.

    Parameters
    ----------
    None
        Reads the state machine definition and builds the parser.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the definition invokes a verb the command line does not register.
    """
    # WHY : Assumptions: the verbs are read from the DEFINITION and checked against the PARSER, in
    #   that direction. The reverse -- asserting every registered verb is orchestrated -- would be
    #   false and should be: decode-record and list-datasets are operator tools with no place in a
    #   nightly chain. What must never happen is the definition invoking a verb argparse rejects,
    #   because that fails inside the container as a usage error after the task has already started.
    definition = _orchestrator_definition()
    registered = set(next(iter(cli.build_parser()._subparsers._group_actions)).choices)
    invoked = set(re.findall(r"States\.Array\('([a-z][a-z-]+)'", definition))
    assert invoked, "no verb was found in the definition"
    assert invoked <= registered, f"unregistered verbs: {sorted(invoked - registered)}"
    # WHY : Assumptions: the verb this chain's data-migration state depends on is
    #   asserted PRESENT as well, so a state deleted from the graph fails here rather
    #   than silently reducing what the chain does.
    # WHY : ⚠️ Refactoring Rationale: `refresh-dataset` is now the ONLY data-migration verb the
    #   nightly chain invokes, where this line has previously listed three and then two.
    #   `stage-dataset` and `load-dataset` went first: `refresh-dataset` performs the staging, the
    #   load and all three verification passes for one dataset as a single retryable step, binding
    #   the very same handlers those two verbs dispatch to -- which is what keeps a nightly failure
    #   reproducible at a terminal. Asserting either would require the chain to invoke
    #   staging and loading as separate branches again, which is the shape that left a
    #   cutover with bytes in object storage and every table empty when only one of the
    #   two ran.
    # WHY : ⚠️ Refactoring Rationale: `verify-all` was asserted ABSENT here, and that assertion is
    #   withdrawn -- the finding behind it is real and the remedy it pinned was superseded. The
    #   finding: `VerifyMigration` stood as a TOP-LEVEL state, making twelve top-level work states
    #   against the eleven specification section 0.4.1.7 enumerates by name. Two remedies exist for
    #   that. Deleting the state and its verb invocation, which this assertion pinned, buys the
    #   eleven by giving up the whole-migration verification the chain runs -- and sections 0.9.2
    #   and 0.7.7 make that verification a first-class deliverable, on the stated ground that a load
    #   which "succeeded" without a money-total check is not evidence of anything.
    #   `refresh-dataset`'s per-dataset passes are not a substitute: they verify each dataset
    #   against its own source, and neither committed whole-migration query runs at all.
    #   Alternatives Considered, and delivered instead: `StageSeedDatasets` became a `Parallel`
    #   holding one branch, and the branch holds the seed-refresh `Map` followed by the gate, so the
    #   gate is NESTED inside state 2 and the top-level count is eleven with the verification
    #   intact. That is the arrangement `test_step_functions_asl_contract.py` locates by name and
    #   asserts -- the wrapper is a `Parallel`, the `Map` is inside it and the gate is inside it --
    #   with the failure message naming exactly the regression this assertion was reaching for:
    #   putting the gate "back among the top-level states".
    # WHY : Assumptions: BOTH verbs are asserted present, and nothing here asserts an absence. This
    #   case reads verbs out of a definition and cannot see nesting, so an absence assertion is the
    #   wrong instrument for a top-level-count contract: it cannot distinguish a gate that moved
    #   from a gate that was deleted, which is precisely how it came to forbid the delivered shape.
    #   The count contract belongs where the structure is readable, and that is the contract suite
    #   named above. What this case can hold, and does, is that every verb the chain invokes is one
    #   argparse registers, and that neither of the two it depends on has vanished from the graph.
    assert {"refresh-dataset", "verify-all"} <= invoked, (
        "the nightly chain must invoke refresh-dataset per dataset and verify-all once at the"
        " nested gate; a missing verb means a state disappeared from the graph"
    )


class _GenerationListingClient:
    """Answer a generation-prefix listing with a fixed page set.

    Purpose
    -------
    Let the staged-source resolution be exercised without an object store, by presenting the one
    listing call it makes. The double answers a PAGINATOR rather than a bare list call because the
    staging module always paginates, so a double that only implemented the single-call form would
    let a regression to an unpaginated read pass.

    Attributes
    ----------
    pages : list[dict[str, object]]
        The pages the paginator yields.
    requests : list[dict[str, object]]
        The keyword arguments each paginate call received.
    """

    def __init__(self, prefixes: Sequence[str]) -> None:
        """Create a client whose listing reports the given generation prefixes.

        Parameters
        ----------
        prefixes : Sequence[str]
            Complete generation prefixes, each ending in a separator.

        Returns
        -------
        None

        Raises
        ------
        None
        """
        self.pages: list[dict[str, object]] = [
            {"CommonPrefixes": [{"Prefix": prefix} for prefix in prefixes]}
        ]
        self.requests: list[dict[str, object]] = []

    def get_paginator(self, operation: str) -> _GenerationListingClient:
        """Return this object as its own paginator.

        Parameters
        ----------
        operation : str
            The operation name, recorded so a test can assert which listing was used.

        Returns
        -------
        _GenerationListingClient
            This instance.

        Raises
        ------
        None
        """
        self.requests.append({"operation": operation})
        return self

    def paginate(self, **kwargs: object) -> Iterator[dict[str, object]]:
        """Yield the arranged pages, recording the request.

        Parameters
        ----------
        **kwargs : object
            The listing arguments, recorded for assertion.

        Yields
        ------
        dict[str, object]
            One arranged page.

        Raises
        ------
        None
        """
        self.requests.append(dict(kwargs))
        yield from self.pages


def _bind_staged_source(
    monkeypatch: pytest.MonkeyPatch, prefixes: Sequence[str]
) -> _GenerationListingClient:
    """Point the staged-source resolution at a listing double and a synthetic bucket.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        The patcher, scoped to the calling test.
    prefixes : Sequence[str]
        The generation prefixes the listing reports.

    Returns
    -------
    _GenerationListingClient
        The double, so a test can read what was requested of it.

    Raises
    ------
    None
    """
    from carddemo_migration.loaders import s3_stage

    client = _GenerationListingClient(prefixes)
    monkeypatch.setattr(
        cli,
        "resolve_dataset_staging_settings",
        lambda: DatasetStagingSettings(bucket=_INBOX_BUCKET, environment="test"),
    )
    # WHY : Assumptions: the client factory is patched on the OWNING module, because the resolution
    #   imports it inside itself so that the read-only verbs reach neither the loader nor the SDK.
    monkeypatch.setattr(s3_stage, "s3_client", lambda: client)
    return client


def test_an_omitted_source_resolves_the_newest_staged_generation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Read the baseline's ``(0)`` reference: the newest generation, not the first listed.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the listing double and the bucket.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an older generation is chosen, or the object name is not the descriptor's own.
    """
    # WHY : Assumptions: the listing is arranged NEWEST FIRST, deliberately out of chronological
    #   order, so a resolution that took the first entry it saw would pick the wrong generation and
    #   still find an object. Arranging them in order is how a "read the newest" contract and a
    #   "read whatever is first" implementation become indistinguishable.
    descriptor = seed_datasets.seed_dataset("accounts")
    family = f"{descriptor.domain}/{descriptor.dataset_segment}"
    client = _bind_staged_source(
        monkeypatch,
        [
            f"{family}/dt=2022-07-19/gen=0003/",
            f"{family}/dt=2022-07-18/gen=0001/",
            f"{family}/dt=2022-07-18/gen=0002/",
        ],
    )

    resolved = cli._staged_source("ACCOUNT")

    assert resolved == (
        f"{seed_datasets.OBJECT_STORE_SCHEME}://{_INBOX_BUCKET}/{family}"
        f"/dt=2022-07-19/gen=0003/{descriptor.source_object}"
    )
    # WHY : Assumptions: the listing is asserted to have been scoped to the FAMILY prefix, because
    #   an
    #   unscoped listing would enumerate the whole bucket and could resolve a generation belonging
    #   to
    #   a different dataset whose prefix happened to sort later.
    assert any(request.get("Prefix") == f"{family}/" for request in client.requests)


def test_an_omitted_source_reports_a_family_that_holds_no_generation(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Refuse, in the classified failed tier, when the staging step has not produced a generation.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the listing double to an empty result.
    capsys : pytest.CaptureFixture[str]
        Captures output, so the refusal is not printed into the test report.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If an empty family reads as nothing rather than as a refusal, or escapes unclassified.
    """
    # WHY : Assumptions: an empty family is the COMMONEST ordering mistake in the whole chain -- a
    #   load state reached before its staging state ran -- so it must name itself rather than read
    #   as
    #   an empty dataset. An empty read would load zero rows, and every count and total downstream
    #   would then agree with each other about a migration that never happened.
    from carddemo_migration.loaders.s3_stage import GenerationDiscoveryError

    _bind_staged_source(monkeypatch, [])

    with pytest.raises(GenerationDiscoveryError, match="no generation exists"):
        cli._staged_source("ACCOUNT")
    # WHY : Assumptions: membership of the shared refusal set is asserted here rather than left to
    #   the
    #   handler tests, because this refusal is a SIBLING of the object-store errors and not a
    #   subclass
    #   -- which is exactly how it escaped the earlier leaf-based set as a traceback.
    assert issubclass(GenerationDiscoveryError, cli._step_errors())
    capsys.readouterr()


def test_every_loadable_dataset_can_resolve_a_staged_source(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Resolve a staged source for all eleven load targets, so no state needs a source argument.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Binds the listing double and the bucket.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If any declared load target has no resolvable staged source.
    """
    # WHY : Assumptions: the coverage asserted is the LOAD TARGET registry, not the seed-token list,
    #   because the orchestrator's Map iterates one branch per load target. A dataset that could not
    #   resolve a source would fail its branch at run time, in the container, with the chain already
    #   holding the online read-only flag.
    for name in target_names():
        descriptor = seed_datasets.seed_dataset(name)
        family = f"{descriptor.domain}/{descriptor.dataset_segment}"
        _bind_staged_source(monkeypatch, [f"{family}/dt=2022-07-18/gen=0001/"])
        resolved = cli._staged_source(name)
        assert resolved.endswith(descriptor.source_object), name
        assert family in resolved, name


def test_refresh_card_identity_reconciles_the_reporting_relation_and_reports_the_delta(
    monkeypatch: pytest.MonkeyPatch,
    fake_aurora: FakeAuroraDatabase,
    aurora_settings: AuroraConnectionSettings,
    capsys: pytest.CaptureFixture[str],
) -> None:
    """Reconcile the per-card identity relation and report what the run changed.

    Purpose
    -------
    Prove the cutover step that has no other caller. ``sql/V1__reporting_views.sql`` creates
    ``reporting.card_identity`` and backfills it at creation, which on a cutover is before the
    extract exists, so a card loaded afterwards is absent from ``reporting.v_card_xref`` and
    gets no statement. Nothing in the chain reports that omission, so the only protection is
    this step being invoked -- and until it had a command it could not be.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Redirects the master-credential resolver and the driver's connect at their owning
        modules.
    fake_aurora : FakeAuroraDatabase
        The recording database double, arranged to report a relation five cards behind.
    aurora_settings : AuroraConnectionSettings
        Synthetic connection settings, translated through ``as_connection_params``.
    capsys : pytest.CaptureFixture[str]
        Reads the rendered line an operator sees.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the step reports success without calling the procedure, if it does not commit, or if
        the rendered line does not state the delta it measured.
    """
    # WHY : Assumptions: the MASTER resolver is the one patched, because `reporting` is the single
    #   context with no `_migrator` login -- V0 creates seven of those and the reporting objects
    #   are applied by the bootstrap principal -- so the reconciliation reaches its `SET ROLE`
    #   through that principal and through no per-context credential. Patching the migration
    #   resolver instead would leave the command connecting for real.
    monkeypatch.setattr(cli, "resolve_master_settings", lambda: aurora_settings)
    monkeypatch.setattr(
        aurora,
        "connect",
        lambda settings: fake_aurora.connect(**settings.as_connection_params()),
    )

    # WHY : Assumptions: the arranged counts describe five identities REPLACED -- five cards in the
    #   cross-reference with no identity row and five identity rows with no card -- so the two
    #   cardinalities agree while ten rows are wrong. That is deliberately the hardest shape rather
    #   than the simplest: a step that decided whether to act by comparing the two counts would
    #   report "nothing to do" here and leave every one of those ten rows in place, which is the
    #   specific defect the unconditional call exists to prevent.
    fake_aurora.arrange_rows('FROM "reporting"."card_identity"', [[13]])
    fake_aurora.arrange_rows('FROM "account"."card_xref"', [[13]])
    fake_aurora.arrange_rows("WHERE NOT EXISTS", [[5]])

    assert cli.main(["refresh-card-identity"]) == EXIT_OK

    issued = " ".join(fake_aurora.executed_sql())
    # WHY : Assumptions: the procedure call is asserted rather than the counts alone, because a
    #   step that measured the divergence and then failed to close it would report the same
    #   numbers. The call is what makes the postcondition true.
    assert "CALL reporting.refresh_card_identity()" in issued
    assert 'SET ROLE "carddemo_reporting_owner"' in issued
    assert fake_aurora.commits, "an uncommitted reconciliation leaves the relation behind"
    reported = capsys.readouterr().out
    assert "reconciled reporting.card_identity" in reported
    # WHY : Assumptions: the measured delta is asserted in the OPERATOR-visible line, because that
    #   line is the only record afterwards that the step was not merely ceremonial -- the relation
    #   it reconciled looks identical by cardinality before and after.
    assert "gained=5" in reported
    assert "removed=5" in reported


def test_refresh_card_identity_reports_the_failed_tier_when_the_procedure_is_absent(
    monkeypatch: pytest.MonkeyPatch,
    aurora_settings: AuroraConnectionSettings,
) -> None:
    """Refuse rather than report success when the relation cannot be reconciled.

    Purpose
    -------
    Pin the tier a cutover script branches on. A statement run started against an unreconciled
    relation produces no document for the affected cardholders and reports nothing, so this step
    reporting success it did not achieve is the one failure mode that stays invisible until a
    cardholder asks where their statement is.

    Parameters
    ----------
    monkeypatch : pytest.MonkeyPatch
        Replaces the resolver and makes the loader raise its own operational error.
    aurora_settings : AuroraConnectionSettings
        Synthetic connection settings the resolver returns.

    Returns
    -------
    None
        The assertions are the result.

    Raises
    ------
    AssertionError
        If the step reports success, or lets the loader's error escape as a traceback.
    """
    monkeypatch.setattr(cli, "resolve_master_settings", lambda: aurora_settings)

    class _Connection:
        """Stand in for an open connection the step is obliged to close."""

        def __init__(self) -> None:
            """Record that nothing has closed this connection yet."""
            self.closed = False

        def close(self) -> None:
            """Record the close the step performs in its ``finally`` arm."""
            self.closed = True

    connection = _Connection()
    monkeypatch.setattr(aurora, "connect", lambda settings: connection)

    def _absent(*args: object, **kwargs: object) -> None:
        """Stand in for the loader meeting a database with no such procedure."""
        raise aurora.AuroraLoadError("reporting.refresh_card_identity() does not exist")

    monkeypatch.setattr(aurora, "refresh_card_identity", _absent)

    # WHY : Assumptions: 8 and not 16. The environment resolved and the connection opened, so a
    #   retry can succeed once the reporting definition is applied -- which is the distinction the
    #   two tiers carry for the batch chain, and the reason the fatal tier is reserved for a
    #   parameter that was never published.
    assert cli.main(["refresh-card-identity"]) == EXIT_FAILED
    assert connection.closed, "the connection must be released even on the refusal path"
