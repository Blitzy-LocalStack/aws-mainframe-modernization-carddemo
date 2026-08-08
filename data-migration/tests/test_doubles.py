"""Verify the shared test doubles and corpus accessors behave like the things they stand in for.

Purpose
-------
Hold the doubles in ``conftest.py`` to the fidelity their own docstrings claim. Every assertion
here is written so that it fails against the behaviour the double had BEFORE this module existed,
because a double is only useful in the direction where it is at least as strict as the thing it
replaces -- a permissive double lets a defect pass a green suite and surface in a deployment.

Refactoring Rationale: this module is new, and its absence is why four separate fidelity defects
survived review in one file. ``conftest.py`` carries roughly a thousand lines of doubles --
a versioned object store, an Aurora database, a record builder, two corpus accessors -- and
apart from the object store none of it had a single consumer. Code with no caller has never had
its claims checked, and each of the four defects was a claim in a docstring or a comment that the
implementation did not honour: terminators documented as preserved but normalised away, a delete
that removed the objects it reported as failed, and a connection context that left a connection
open where the pinned driver closes it.

Assumptions: the doubles are exercised through the PRODUCTION code where a production caller
exists -- the retention test below drives ``delete_generation_prefix`` rather than calling the
double directly -- because that is the only way to show the double and the code under test agree
about the same contract. Where no production caller exists yet, the double is exercised directly
against the behaviour of the real component, cited at the point of use.
"""

from __future__ import annotations

from typing import Any

# Assumptions: the doubles are imported from ``conftest`` by name, which is the only way to reach
#   the CLASSES -- the fixtures hand back instances, and several assertions here are about a
#   class's exception type rather than about an instance. pytest's default import mode puts the
#   tests directory on the path, so the sibling import resolves however the suite is invoked.
# Trade-offs: the import sorter groups ``conftest`` with the third-party block because it cannot
#   tell a sibling test module from an installed distribution. That placement is accepted rather
#   than suppressed, since a per-file ignore would switch the rule off for every future import in
#   this module as well.
import pytest
from conftest import (
    FakeAuroraDatabase,
    FakeClientContractError,
    FakeObjectStore,
    FixtureCorpus,
    SeedCorpus,
)

from carddemo_migration.config import AuroraConnectionSettings, ConfigurationError
from carddemo_migration.copybook.layouts import iter_ascii_text_records
from carddemo_migration.loaders.s3_stage import (
    GenerationRetentionError,
    delete_generation_prefix,
)

# Assumptions: these three are the only committed ASCII seeds carrying a carriage return, and the
#   counts are measured from the tree rather than assumed -- 49 of 50 rows in the category
#   balances, 6 of 7 in the transaction types, and all 18 in the transaction categories. The first
#   two are therefore genuinely MIXED, holding CRLF rows beside a bare-newline final row, which is
#   the only mixed-terminator data this repository has.
_CARRIAGE_RETURN_SEEDS = (
    pytest.param("tcatbal.txt", 49, id="category-balances-49-of-50"),
    pytest.param("trantype.txt", 6, id="transaction-types-6-of-7"),
    pytest.param("trancatg.txt", 18, id="transaction-categories-all-18"),
)


# --------------------------------------------------------------------------------------
# Corpus terminator fidelity
# --------------------------------------------------------------------------------------


@pytest.mark.parametrize(("dataset", "expected"), _CARRIAGE_RETURN_SEEDS)
def test_the_ascii_seed_reader_preserves_every_committed_carriage_return(
    dataset: str, expected: int, seed_corpus: SeedCorpus
) -> None:
    """Return as many carriage returns from the text reader as the file actually holds.

    :param dataset: the committed seed under test.
    :param expected: the number of carriage returns measured in the committed bytes.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a normalised reading is reported as a failure.
    """
    # WHY : Refactoring Rationale: the reader used a default text read, which applies
    #   universal-newline translation and rewrites every CRLF as a bare newline. So a method
    #   documenting "terminators included" returned text holding ZERO carriage returns, and the
    #   count is asserted against the BYTES rather than against a literal so this test measures
    #   the tree instead of restating a number that could drift from it.
    assert seed_corpus.ascii_raw_bytes(dataset).count(b"\r") == expected
    assert seed_corpus.ascii_text(dataset).count("\r") == expected


@pytest.mark.parametrize(("dataset", "expected"), _CARRIAGE_RETURN_SEEDS)
def test_the_ascii_seed_reader_round_trips_the_committed_bytes_exactly(
    dataset: str, expected: int, seed_corpus: SeedCorpus
) -> None:
    """Decode the seed to text that re-encodes to the committed bytes, byte for byte.

    :param dataset: the committed seed under test.
    :param expected: the measured carriage-return count, asserted non-zero so the case is real.
    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; any translation anywhere in the read is reported as a failure.
    """
    # WHY : Trade-offs: this is the strongest available statement and it subsumes the count
    #   assertion above -- a round trip proves NO byte was translated, not merely that the
    #   carriage returns survived. Both are kept because they fail differently: the count says
    #   which terminator was lost, and the round trip says only that something was. A reader
    #   debugging a regression wants the first; a reader wanting the guarantee wants the second.
    raw = seed_corpus.ascii_raw_bytes(dataset)
    assert expected > 0, "this case only means something for a seed that carries a terminator"
    assert seed_corpus.ascii_text(dataset).encode("ascii") == raw


def test_a_mixed_terminator_seed_still_yields_whole_records(seed_corpus: SeedCorpus) -> None:
    """Iterate a seed holding CRLF rows beside a bare-newline row into equal whole records.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a ragged or short record is reported as a failure.
    """
    # WHY : Assumptions: the category balances are 49 CRLF rows plus one bare-newline final row,
    #   so this exercises BOTH terminator shapes in one pass -- which is what the record iterator
    #   states it tolerates and what the normalised reading made impossible to check. Every record
    #   coming back at exactly the declared width is the observable proof that the rule fired on
    #   the CRLF rows and did not over-strip the last one.
    reclen = seed_corpus.reclen("tcatbal.txt")
    records = seed_corpus.ascii_records("tcatbal.txt")

    assert len(records) == 50
    assert {len(record) for record in records} == {reclen}


def test_the_carriage_return_stripping_rule_is_reached_by_committed_data(
    seed_corpus: SeedCorpus,
) -> None:
    """Confirm a CRLF row arrives one character over the declared width before stripping.

    :param seed_corpus: session accessor over ``app/data``.
    :returns: nothing; a row that already fits before stripping is reported as a failure.
    """
    # WHY : Assumptions: this asserts the branch is LIVE rather than merely that the output is
    #   right. Under the normalised reading every line arrived already at the declared width, so
    #   the production rule that removes one trailing carriage return could not fire against any
    #   committed data and was reachable only from a hand-built string. Splitting on the newline
    #   here shows the pre-strip length is width-plus-one, which is the condition the rule exists
    #   for -- and a row over the width is otherwise a record-length error, so without the rule
    #   this seed would be refused outright.
    reclen = seed_corpus.reclen("tcatbal.txt")
    first_line = seed_corpus.ascii_text("tcatbal.txt").split("\n")[0]

    assert first_line.endswith("\r")
    assert len(first_line) == reclen + 1
    assert len(next(iter(iter_ascii_text_records(first_line, reclen)))) == reclen


def test_the_fixture_reader_round_trips_its_committed_bytes_too(
    fixture_corpus: FixtureCorpus,
) -> None:
    """Decode a fixture to text that re-encodes to the committed bytes, byte for byte.

    :param fixture_corpus: session accessor over ``tests/fixtures``.
    :returns: nothing; any translation in the fixture reader is reported as a failure.
    """
    # WHY : Assumptions: the fixture tree holds no carriage return today, so this passes either
    #   way and is not evidence of a bug fixed. It is here to PIN the policy: the sibling reader
    #   feeds the same record iterator, so the first CRLF fixture a contributor adds -- the
    #   obvious way to pin the mixed-terminator rule with controlled data rather than a seed --
    #   would otherwise be silently normalised and would not exercise what it was written for.
    raw = fixture_corpus.raw_bytes("posting/happy_path", "dailytran.txt")
    assert fixture_corpus.text("posting/happy_path", "dailytran.txt").encode("ascii") == raw


# --------------------------------------------------------------------------------------
# Object-store delete fidelity
# --------------------------------------------------------------------------------------


def _two_versions(store: FakeObjectStore, bucket: str = "datasets") -> tuple[str, str]:
    """Hold two distinct objects under one prefix and return their keys.

    :param store: the object-store double to populate.
    :param bucket: the bucket to hold them in.
    :returns: the two keys, in the order they were written.
    """
    prefix = "ledger/transact-bkup/dt=2026-08-02/gen=0001/"
    first, second = f"{prefix}part-a.dat", f"{prefix}part-b.dat"
    store.put_object(Bucket=bucket, Key=first, Body=b"AAAA")
    store.put_object(Bucket=bucket, Key=second, Body=b"BBBB")
    return first, second


def test_an_arranged_delete_failure_leaves_its_own_object_in_place(
    fake_object_store: FakeObjectStore,
) -> None:
    """Report one object as failed, remove the other, and leave the failed one held.

    :param fake_object_store: in-process versioned object store from ``conftest``.
    :returns: nothing; a failed object that was nevertheless removed is reported as a failure.
    """
    # WHY : Refactoring Rationale: the double used to delete EVERY named object and then return
    #   the arranged errors beside them, which modelled partial failure backwards. On a real
    #   bucket an object reported in Errors is still there -- that is what the error means -- so a
    #   retry finds it and removes it. A test asserting recovery against the old double would have
    #   found the failed version already gone and would have passed for the wrong reason.
    first, second = _two_versions(fake_object_store)
    fake_object_store.fail_next_delete_with("AccessDenied", key=first)

    response = fake_object_store.delete_objects(
        Bucket="datasets",
        Delete={
            "Objects": [
                {"Key": first, "VersionId": "v0001"},
                {"Key": second, "VersionId": "v0002"},
            ],
            "Quiet": True,
        },
    )

    assert response["Errors"] == [{"Key": first, "Code": "AccessDenied", "VersionId": "v0001"}]
    assert fake_object_store.keys("datasets") == (first,)


def test_an_unkeyed_arranged_delete_failure_targets_the_first_object(
    fake_object_store: FakeObjectStore,
) -> None:
    """Fail the first object in the batch when the arrangement names no key.

    :param fake_object_store: in-process versioned object store from ``conftest``.
    :returns: nothing; a whole-batch failure is reported as a failure.
    """
    # WHY : Trade-offs: the unkeyed default targets ONE object rather than all of them, so the
    #   modelled failure stays partial. A whole-batch failure would never produce the
    #   half-scratched generation the retention path has to refuse, which is the only state worth
    #   modelling here.
    first, second = _two_versions(fake_object_store)
    fake_object_store.fail_next_delete_with("InternalError")

    response = fake_object_store.delete_objects(
        Bucket="datasets",
        Delete={"Objects": [{"Key": first}, {"Key": second, "VersionId": "v0002"}]},
    )

    assert [entry["Key"] for entry in response["Errors"]] == [first]
    assert second not in fake_object_store.keys("datasets")


def test_a_delete_arrangement_matching_no_named_object_is_a_contract_error(
    fake_object_store: FakeObjectStore,
) -> None:
    """Refuse a batch that leaves an arranged failure unmatched.

    :param fake_object_store: in-process versioned object store from ``conftest``.
    :returns: nothing; a silently ignored arrangement is reported as a failure.
    """
    # WHY : Assumptions: an unmatched arrangement is raised rather than ignored, because a test
    #   that arranged a failure for a key the batch never named would see a fully successful
    #   delete and would assert the partial-failure path while never entering it -- the same
    #   silent-pass class of defect this whole module exists to close.
    first, _ = _two_versions(fake_object_store)
    fake_object_store.fail_next_delete_with("AccessDenied", key="ledger/absent/key.dat")

    with pytest.raises(FakeClientContractError, match="this batch did not name"):
        fake_object_store.delete_objects(
            Bucket="datasets", Delete={"Objects": [{"Key": first, "VersionId": "v0001"}]}
        )


def test_retention_refuses_a_partial_delete_and_leaves_the_failed_version_recoverable(
    fake_object_store: FakeObjectStore,
) -> None:
    """Drive the production retention path into a partial failure and assert both halves.

    :param fake_object_store: in-process versioned object store from ``conftest``.
    :returns: nothing; a tolerated partial delete, or a removed failed version, is a failure.
    """
    # WHY : Assumptions: this drives the PRODUCTION function rather than the double alone, which
    #   is what makes the fidelity fix meaningful: it shows the code under test refuses a
    #   half-completed scratch AND that the double leaves the failed version where a retry can
    #   still reach it. Asserting only the exception would have passed against the old double,
    #   whose store no longer held the object the error had just reported as undeleted.
    prefix = "ledger/transact-bkup/dt=2026-08-02/gen=0001/"
    first, second = _two_versions(fake_object_store)
    fake_object_store.fail_next_delete_with("AccessDenied", key=first)

    with pytest.raises(GenerationRetentionError, match="AccessDenied"):
        delete_generation_prefix(fake_object_store, "datasets", prefix)

    assert fake_object_store.keys("datasets") == (first,)
    assert second not in fake_object_store.keys("datasets")


def test_a_delete_naming_no_version_writes_a_marker_rather_than_removing(
    fake_object_store: FakeObjectStore,
) -> None:
    """Keep every version and add a delete marker when no version identifier is named.

    :param fake_object_store: in-process versioned object store from ``conftest``.
    :returns: nothing; a removed version is reported as a failure.
    """
    # WHY : Assumptions: this is pinned beside the partial-failure cases because it is the other
    #   way a scratch can appear to succeed while nothing is permanently gone. A loader that
    #   omitted the version identifier would believe it had removed a generation while every
    #   version remained recoverable, which on a bucket retaining noncurrent versions means the
    #   retention contract is not being met at all.
    first, _ = _two_versions(fake_object_store)

    fake_object_store.delete_objects(Bucket="datasets", Delete={"Objects": [{"Key": first}]})

    assert first in fake_object_store.keys("datasets")


# --------------------------------------------------------------------------------------
# Aurora connection lifecycle
# --------------------------------------------------------------------------------------


def _connect(database: FakeAuroraDatabase, settings: AuroraConnectionSettings) -> Any:
    """Open one connection through the real settings-to-parameter translation.

    :param database: the Aurora double to connect to.
    :param settings: validated connection settings.
    :returns: the open connection double.
    """
    # WHY : Assumptions: the parameters come from ``as_connection_params`` rather than being
    #   hand-written, so the translation and the TLS keywords the double validates are exercised
    #   on the same path a loader would take. Hand-written parameters would test the double's
    #   validator against input no production caller ever produces.
    return database.connect(**settings.as_connection_params())


def test_a_connection_context_commits_and_then_closes(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Commit once and close on a clean context exit, as the pinned driver does.

    :param fake_aurora: per-test Aurora database double.
    :param aurora_settings: validated synthetic connection settings.
    :returns: nothing; a connection left open is reported as a failure.
    """
    # WHY : Refactoring Rationale: the close is the correction. The double previously committed
    #   and deliberately left the connection open, on the stated grounds that the driver's
    #   connection context ends only the transaction. Read from the installed psycopg 3.3.4,
    #   ``Connection.__exit__`` returns early if already closed, commits or rolls back, then
    #   closes unless the connection belongs to a pool -- so the double was MORE PERMISSIVE than
    #   production, and a loader reusing a connection after its block would pass here and raise
    #   against the real driver.
    with _connect(fake_aurora, aurora_settings) as connection:
        connection.cursor().execute("SELECT 1")

    assert connection.commits == 1
    assert connection.rollbacks == 0
    assert connection.closed is True


def test_a_connection_context_rolls_back_and_then_closes(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Roll back once and still close when the context exits on an exception.

    :param fake_aurora: per-test Aurora database double.
    :param aurora_settings: validated synthetic connection settings.
    :returns: nothing; a commit, or a connection left open, is reported as a failure.
    """
    # WHY : Assumptions: the close is asserted on the FAILING path too, because that is the path
    #   a load actually takes when a record is rejected -- and a connection leaked on the error
    #   path is the one least likely to be noticed, since the test that provoked the error is
    #   already asserting the error.
    connection = _connect(fake_aurora, aurora_settings)
    with pytest.raises(RuntimeError, match="a record was rejected"):
        with connection:
            connection.cursor().execute("SELECT 1")
            raise RuntimeError("a record was rejected")

    assert connection.commits == 0
    assert connection.rollbacks == 1
    assert connection.closed is True


@pytest.mark.parametrize(
    "operation",
    [
        pytest.param(lambda connection: connection.cursor(), id="cursor"),
        pytest.param(lambda connection: connection.commit(), id="commit"),
        pytest.param(lambda connection: connection.rollback(), id="rollback"),
        pytest.param(lambda connection: connection.transaction(), id="transaction"),
    ],
)
def test_every_operation_after_the_context_is_refused(
    operation: Any, fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Refuse any use of a connection whose context has already exited.

    :param operation: the operation attempted after the context exited.
    :param fake_aurora: per-test Aurora database double.
    :param aurora_settings: validated synthetic connection settings.
    :returns: nothing; an accepted post-context operation is reported as a failure.
    """
    # WHY : Assumptions: all four entry points are covered rather than one, because closing the
    #   connection is only useful if every way back in is shut. A double that refused a cursor but
    #   still accepted a commit would let a loader appear to persist work on a connection the real
    #   driver had already closed.
    with _connect(fake_aurora, aurora_settings) as connection:
        connection.cursor().execute("SELECT 1")

    with pytest.raises(FakeClientContractError, match="closed connection"):
        operation(connection)


def test_re_entering_an_already_closed_connection_context_changes_nothing(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Treat a context exit on an already-closed connection as a no-op.

    :param fake_aurora: per-test Aurora database double.
    :param aurora_settings: validated synthetic connection settings.
    :returns: nothing; a second commit or a raised error is reported as a failure.
    """
    # WHY : Assumptions: the early return mirrors the driver's own first line,
    #   ``if self.closed: return``, and it has to be reproduced rather than left to fall through.
    #   Without it the second exit would attempt a commit on a closed connection, which this
    #   double refuses -- so a nested or re-entered block would fail in the double for a reason
    #   the driver does not have.
    connection = _connect(fake_aurora, aurora_settings)
    with connection:
        connection.cursor().execute("SELECT 1")
    assert connection.commits == 1

    with connection:
        pass

    assert connection.commits == 1
    assert connection.rollbacks == 0
    assert connection.closed is True


def test_a_transaction_block_ends_the_unit_of_work_without_closing(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Keep the connection usable across two transaction blocks, closing neither.

    :param fake_aurora: per-test Aurora database double.
    :param aurora_settings: validated synthetic connection settings.
    :returns: nothing; a closed connection between blocks is reported as a failure.
    """
    # WHY : Assumptions: this is the construct the connection context was previously being
    #   confused with, and it is modelled separately exactly as the driver models it. A caller
    #   needing two units of work on one connection uses ``transaction()``, which ends the unit
    #   of work and leaves the connection open -- so correcting the connection context took
    #   nothing away, it moved the non-closing behaviour to the construct that really has it.
    connection = _connect(fake_aurora, aurora_settings)
    with connection.transaction():
        connection.cursor().execute("INSERT INTO auth.users VALUES (1)")
    assert connection.closed is False

    with connection.transaction():
        connection.cursor().execute("INSERT INTO auth.users VALUES (2)")

    assert connection.commits == 2
    assert connection.closed is False
    assert fake_aurora.transactions_opened == 2


def test_a_connection_refuses_parameters_that_would_not_verify_the_server(
    fake_aurora: FakeAuroraDatabase, aurora_settings: AuroraConnectionSettings
) -> None:
    """Refuse a connection whose TLS keywords would not verify the server's certificate.

    :param fake_aurora: per-test Aurora database double.
    :param aurora_settings: validated synthetic connection settings.
    :returns: nothing; an accepted unverified connection is reported as a failure.
    """
    # WHY : Assumptions: this is asserted here because it is the one place the double is
    #   deliberately STRICTER than a real driver, which would happily connect with a weaker mode.
    #   The strictness is the point -- an ETL that reached the cluster without verifying its
    #   certificate would satisfy every functional test while carrying credentials and account
    #   data over a connection nothing authenticated -- so it needs a test to keep it.
    params = dict(aurora_settings.as_connection_params())
    params["sslmode"] = "prefer"

    with pytest.raises(ConfigurationError):
        fake_aurora.connect(**params)
