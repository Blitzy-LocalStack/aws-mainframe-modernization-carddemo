"""Verify the online-write bracket is an atomic, owner-verified lease.

Purpose
-------
Exercise ``infra/lambda/online_write_flag.py`` against a fake DynamoDB that enforces the same
conditional-write semantics the real service does, so the properties the bracket depends on are
checked rather than asserted in a comment: only one execution may hold it; only its owner or an
expired lease may release it; and BOTH edges are safe to retry, because the quiesce admits the
execution already recorded on the lease and the release proves ownership before writing the flag
and gives the lease up only after.

Notes
-----
WHY : Trade-offs: this test lives in the ETL package's suite even though its subject is an
infrastructure Lambda, and the reason is that this is the only Python suite continuous integration
executes -- ``.github/workflows/services-ci.yml`` runs ``ruff check .`` and pytest with
``working-directory: data-migration`` and nothing anywhere runs a Python test under ``infra/``. The
alternatives were a suite CI does not run, which protects nothing, or leaving the lease untested,
which for a correctness property of this kind is worse than the misplacement. There is precedent
for reaching across trees from here: ``test_seed_datasets.py`` reads the orchestrator's Terraform
variable for the same reason, because a contract spanning two languages can only be held by a test.

WHY : Assumptions: the module is loaded from its path with a stubbed ``boto3`` rather than imported
as a package member, because it is a Lambda entry point on no import path and it builds its service
clients and reads its required environment at import time. Stubbing the SDK is what lets those
import-time side effects succeed with no AWS account and no credentials.
"""

from __future__ import annotations

import importlib.util
import re
import sys
import types
from pathlib import Path
from typing import Any

import pytest
from botocore.exceptions import ClientError

#: Absolute path of the Lambda under test, derived from this file so the working directory the
#: suite was invoked from cannot change what is loaded.
_LAMBDA_SOURCE = Path(__file__).resolve().parents[2] / "infra" / "lambda" / "online_write_flag.py"

_PARAMETER_NAME = "/carddemo/test/online-writes-enabled"
_LEASE_TABLE = "carddemo-test-online-write-lease"


class _ConditionalCheckFailed(Exception):
    """Stand in for DynamoDB's refusal of a conditional write.

    Purpose
    -------
    Carry the one thing the module reads when deciding whether a write was refused -- the service
    error code inside a ``response`` mapping -- so the refusal paths are reachable without
    botocore.

    Attributes
    ----------
    response : dict
        The minimal error document shape, naming ``ConditionalCheckFailedException``.
    """

    def __init__(self) -> None:
        """Build a refusal carrying the conditional-check error code."""
        super().__init__("ConditionalCheckFailedException")
        self.response = {"Error": {"Code": "ConditionalCheckFailedException"}}


class _FakeDynamoDb:
    """Hold one lease item and enforce the condition expressions the module supplies.

    Purpose
    -------
    Evaluate the condition expressions for real, because a fake that accepted every conditional
    write would let every lease property appear to hold while none did.

    Attributes
    ----------
    items : dict
        The stored items, keyed by partition-key value.
    expressions : list of str
        Every expression the module sent, recorded so a test can assert on their text rather than
        only on their effect.
    """

    def __init__(self) -> None:
        """Create an empty table."""
        self.items: dict[str, dict[str, Any]] = {}
        self.expressions: list[str] = []

    def _resolve_names(self, expression: str, names: dict[str, str]) -> str:
        """Substitute declared expression-attribute aliases back into an expression.

        Purpose
        -------
        Reduce an expression to the attribute names it addresses, and prove along the way that every
        alias it uses was declared and every alias declared was used.

        Parameters
        ----------
        expression : str
            The expression as the module sent it.
        names : dict
            The ``ExpressionAttributeNames`` mapping sent alongside it.

        Returns
        -------
        str
            The expression with each alias replaced by the attribute it stands for.

        Raises
        ------
        AssertionError
            If an alias is declared but unused, or used but undeclared.
        """
        self.expressions.append(expression)
        for alias, attribute in names.items():
            assert alias in expression, f"declared alias {alias!r} is unused in {expression!r}"
            expression = expression.replace(alias, attribute)
        # WHY : Assumptions: an UNDECLARED alias is a hard error here because the real service
        #   answers it with ValidationException rather than a result, and a fake that quietly
        #   tolerated it would let a broken expression pass every test. This is the inverse guard to
        #   the reserved-word check in test_no_expression_spells_the_reserved_owner_attribute: one
        #   proves the alias is declared, the other proves it is used where it must be.
        assert "#" not in expression, f"expression uses an undeclared alias: {expression!r}"
        return expression

    def _condition_holds(
        self, expression: str, key: str, values: dict[str, Any], names: dict[str, str]
    ) -> bool:
        """Evaluate one of the module's condition expressions against the stored item."""
        stored = self.items.get(key)
        # WHY : Assumptions: the expressions are recognised by SHAPE rather than parsed generally,
        #   and each branch is evaluated the way DynamoDB would -- as a disjunction, against the
        #   ``:now`` the CALLER supplied rather than against a clock of the fake's own, because that
        #   is what the service compares. A test therefore reaches expiry by monkeypatching the
        #   module's time, not by adjusting the table. A general parser would be a second
        #   implementation to get wrong; recognising the exact expressions the module sends keeps
        #   the fake honest about what it verifies, and an unrecognised expression fails loudly
        #   below rather than defaulting to true.
        expression = self._resolve_names(expression, names)
        if expression.startswith("attribute_not_exists"):
            # WHY : Refactoring Rationale: the acquire condition is asserted to carry its OWNER
            #   clause, not merely evaluated. Before that clause existed this branch returned only
            #   the expiry test, so a same-execution retry was refused -- and a fake that silently
            #   kept evaluating two clauses would let that regression back in while every test
            #   still passed, because the refusal it produces is a valid outcome shape.
            assert "owner = :owner" in expression, (
                "the acquire condition must admit a lease this execution already owns, or a "
                f"retried quiesce deadlocks against its own lease: {expression!r}"
            )
            if stored is None:
                return True
            if stored["owner"]["S"] == values[":owner"]["S"]:
                return True
            return int(stored["expiresAt"]["N"]) < int(values[":now"]["N"])
        if expression.startswith("owner ="):
            if stored is None:
                return False
            if stored["owner"]["S"] == values[":owner"]["S"]:
                return True
            return int(stored["expiresAt"]["N"]) < int(values[":now"]["N"])
        raise AssertionError(f"unrecognised condition expression: {expression!r}")

    def _refuse_unless_condition_holds(self, key: str, kwargs: dict[str, Any]) -> None:
        """Raise the SDK's refusal when the supplied condition does not hold."""
        expression = kwargs.get("ConditionExpression")
        if expression is not None and not self._condition_holds(
            expression,
            key,
            kwargs.get("ExpressionAttributeValues", {}),
            kwargs.get("ExpressionAttributeNames", {}),
        ):
            raise _ConditionalCheckFailed()

    def put_item(self, **kwargs: Any) -> dict[str, Any]:
        """Store an item when its condition expression holds, otherwise refuse."""
        key = kwargs["Item"]["LeaseName"]["S"]
        self._refuse_unless_condition_holds(key, kwargs)
        self.items[key] = kwargs["Item"]
        return {}

    def update_item(self, **kwargs: Any) -> dict[str, Any]:
        """Mutate the attributes an update expression names, when its condition holds."""
        key = kwargs["Key"]["LeaseName"]["S"]
        self._refuse_unless_condition_holds(key, kwargs)
        # WHY : Assumptions: a real update_item UPSERTS, so an absent item would be created here
        #   rather than refused. That is unreachable while the module's claim condition is in force,
        #   since neither of its clauses can hold for an item that does not exist, so it is asserted
        #   rather than emulated: reaching it means the condition was weakened to something that
        #   would let a release invent the bracket it claims to be giving up.
        assert key in self.items, f"update_item reached with no stored item for {key!r}"
        values = kwargs.get("ExpressionAttributeValues", {})
        update = self._resolve_names(
            kwargs["UpdateExpression"], kwargs.get("ExpressionAttributeNames", {})
        )
        assert update.startswith("SET "), f"unrecognised update expression: {update!r}"
        for assignment in update.removeprefix("SET ").split(", "):
            attribute, _, reference = assignment.partition(" = ")
            self.items[key][attribute] = values[reference]
        return {}

    def delete_item(self, **kwargs: Any) -> dict[str, Any]:
        """Remove an item when its condition expression holds, otherwise refuse."""
        key = kwargs["Key"]["LeaseName"]["S"]
        self._refuse_unless_condition_holds(key, kwargs)
        self.items.pop(key, None)
        return {}

    def get_item(self, **kwargs: Any) -> dict[str, Any]:
        """Return a stored item, as the SDK does, or an empty response when absent."""
        stored = self.items.get(kwargs["Key"]["LeaseName"]["S"])
        return {"Item": stored} if stored is not None else {}


class _FakeSsm:
    """Record parameter writes and serve a settable current value."""

    class exceptions:  # noqa: N801 - mirrors botocore's own lower-case attribute name
        """Expose the one modelled exception the module references."""

        class ParameterNotFound(Exception):
            """Raised when the flag parameter does not exist."""

    def __init__(self) -> None:
        """Start with no parameter stored, no writes recorded and no fault armed."""
        self.value: str | None = None
        self.puts: list[dict[str, Any]] = []
        self.fail_next_put: BaseException | None = None

    def get_parameter(self, **kwargs: Any) -> dict[str, Any]:
        """Return the stored value, or raise the absent-parameter error."""
        if self.value is None:
            raise self.exceptions.ParameterNotFound()
        return {"Parameter": {"Value": self.value, "Version": 1}}

    def put_parameter(self, **kwargs: Any) -> dict[str, Any]:
        """Record a write and store the new value, or raise a single armed fault instead."""
        # WHY : Assumptions: an armed fault is raised BEFORE the value is stored and disarms itself,
        #   because the failure being modelled is a flag write that never landed and a retry that
        #   then succeeds. Raising after storing would model a lost RESPONSE instead, which is a
        #   different case with a different expected outcome and is exercised by invoking the
        #   handler against a flag that is already correct.
        if self.fail_next_put is not None:
            fault, self.fail_next_put = self.fail_next_put, None
            raise fault
        self.puts.append(kwargs)
        self.value = kwargs["Value"]
        return {"Version": len(self.puts) + 1}


def _load(action: str, target: str, monkeypatch: pytest.MonkeyPatch) -> Any:
    """Load the Lambda module configured for one edge, with the AWS SDK stubbed.

    Parameters
    ----------
    action : str
        Value for ``EXPECTED_ACTION``, either ``quiesce`` or ``resume``.
    target : str
        Value for ``TARGET_VALUE``, either ``false`` or ``true``.
    monkeypatch : pytest.MonkeyPatch
        Fixture used to set the module's required environment.

    Returns
    -------
    Any
        The freshly loaded module, carrying ``DYNAMODB`` and ``SSM`` attributes the caller
        replaces with fakes.

    Raises
    ------
    None
    """
    monkeypatch.setenv("PARAMETER_NAME", _PARAMETER_NAME)
    monkeypatch.setenv("EXPECTED_ACTION", action)
    monkeypatch.setenv("TARGET_VALUE", target)
    monkeypatch.setenv("LEASE_TABLE_NAME", _LEASE_TABLE)

    stub = types.ModuleType("boto3")
    stub.client = lambda service, *args, **kwargs: object()  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "boto3", stub)

    # WHY : Assumptions: the module is loaded FRESH for each test under a unique name. It resolves
    #   its configuration into module-level constants at import time, so a cached module would
    #   carry the previous test's action and target and the quiesce and resume edges could not be
    #   exercised in one session.
    name = f"online_write_flag_{action}_{target}"
    spec = importlib.util.spec_from_file_location(name, _LAMBDA_SOURCE)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, name, module)
    spec.loader.exec_module(module)
    return module


def _wire(module: Any, dynamodb: _FakeDynamoDb, ssm: _FakeSsm) -> None:
    """Replace a loaded module's service clients with fakes."""
    module.DYNAMODB = dynamodb
    module.SSM = ssm


def test_a_second_execution_cannot_acquire_a_held_lease(monkeypatch: pytest.MonkeyPatch) -> None:
    """Refuse the bracket to a second execution while the first still holds it."""
    module = _load("quiesce", "false", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    _wire(module, table, ssm)

    first = module._acquire_lease({"leaseSeconds": 3600}, "exec-A")
    second = module._acquire_lease({"leaseSeconds": 3600}, "exec-B")

    # WHY : Refactoring Rationale: this is the property the replaced implementation could not hold
    #   and admitted it could not. Acquisition was a read of the flag followed by a write of it, so
    #   two executions reading in the same instant both concluded they had acquired. A conditional
    #   create makes the service arbitrate, so exactly one of these two calls can succeed.
    assert first == (True, "exec-A", "")
    assert second[0] is False
    # The refusal reports WHO holds it, so an execution history records what it lost to.
    assert second[1] == "exec-A"


def test_an_expired_lease_may_be_taken_over(monkeypatch: pytest.MonkeyPatch) -> None:
    """Allow a new execution to acquire once the previous lease has expired."""
    module = _load("quiesce", "false", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    _wire(module, table, ssm)
    monkeypatch.setattr(module.time, "time", lambda: 1_000_000)

    assert module._acquire_lease({"leaseSeconds": 60}, "exec-A")[0] is True
    # WHY : Assumptions: the clock is advanced past the recorded expiry rather than the item being
    #   edited, because expiry is what the condition tests. Editing the stored item would prove the
    #   fake can be rewritten, not that an expired lease is admitted.
    monkeypatch.setattr(module.time, "time", lambda: 1_000_061)

    taken = module._acquire_lease({"leaseSeconds": 60}, "exec-B")
    # Without this, one crashed execution would block every subsequent night until a human cleared
    # the bracket by hand.
    assert taken == (True, "exec-B", "")


def test_a_release_naming_no_owner_is_refused(monkeypatch: pytest.MonkeyPatch) -> None:
    """Refuse an unconditional release, which previously cleared any bracket at all."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True

    owner, reason = module._claim_lease_release({})

    # WHY : Refactoring Rationale: an absent expectedLeaseOwner used to mean "clear it
    #   unconditionally", and the bracket-finalizer rule relied on that reading -- which is exactly
    #   how it could open the write window while a healthy execution still held the bracket. The
    #   rule's input transformer already extracts the terminating execution's name from the event,
    #   so it can name the owner it means to clear and be checked like every other caller.
    assert owner == ""
    assert reason == "caller named no lease owner"
    assert quiesce.LEASE_KEY in table.items, "the held lease must survive a refused release"


def test_a_release_by_a_different_owner_is_refused(monkeypatch: pytest.MonkeyPatch) -> None:
    """Refuse a release from an execution that does not hold the live lease."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True

    held_before = dict(table.items[quiesce.LEASE_KEY])

    owner, reason = module._claim_lease_release({"expectedLeaseOwner": "exec-B"})

    assert owner == "exec-B"
    assert reason == "lease is held by exec-A"
    # WHY : Assumptions: the refused claim must leave the item BYTE-IDENTICAL, not merely present.
    #   The claim writes an expiry and an owner when it succeeds, so a conditional write that was
    #   refused yet still mutated would hand exec-B a bracket it was just told it could not have.
    assert table.items[quiesce.LEASE_KEY] == held_before


def test_the_owner_claims_then_completes_its_own_release(monkeypatch: pytest.MonkeyPatch) -> None:
    """Give the bracket up in two steps, holding it throughout the first."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    # WHY : Assumptions: patching once covers both modules. `import time` yields the one module
    #   object from sys.modules, so the two edges under test read the same clock and pinning it here
    #   makes the recorded expiry an exact number this test can assert rather than approximate.
    monkeypatch.setattr(module.time, "time", lambda: 1_000_000)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True

    owner, reason = module._claim_lease_release({"expectedLeaseOwner": "exec-A"})

    # WHY : Refactoring Rationale: the claim proving ownership WITHOUT giving the lease up is the
    #   whole of the fix. The release used to delete first, so a flag write that then failed left
    #   online writes disabled with no lease left to prove who was entitled to re-enable them --
    #   and the graph's own recovery re-invoked the release with the same owner, which found no
    #   lease, reported "no lease is held", skipped the write and returned SUCCESS.
    assert (owner, reason) == ("exec-A", "")
    assert module.LEASE_KEY in table.items, "the claim must not surrender the lease"
    stored = table.items[module.LEASE_KEY]
    # The claim shortens the lease to the release window, because its owner has by definition
    # finished the work the bracket protected.
    assert int(stored["expiresAt"]["N"]) == 1_000_000 + module.RELEASE_CLAIM_SECONDS
    assert stored["releaseClaimedAt"]["N"] == "1000000"
    # WHY : Assumptions: acquiredAt is asserted to SURVIVE the claim, which is why the claim is an
    #   update rather than a whole-item put. It is the only record of when the bracket was taken,
    #   and an operator reading a lease that outlived its owner needs it beside releaseClaimedAt to
    #   tell an execution that died mid-batch from one that died mid-release.
    assert stored["acquiredAt"]["N"] == "1000000"

    assert module._complete_lease_release("exec-A") is True
    assert table.items == {}


def test_a_refused_acquisition_leaves_the_flag_untouched(monkeypatch: pytest.MonkeyPatch) -> None:
    """Skip the parameter write entirely when the bracket was not acquired."""
    module = _load("quiesce", "false", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "true"
    _wire(module, table, ssm)
    assert module._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True
    ssm.puts.clear()

    result = module.handler(
        {
            "action": "quiesce",
            "readOnlyFlagParameter": _PARAMETER_NAME,
            "executionName": "exec-B",
            "leaseSeconds": 3600,
        },
        None,
    )

    # WHY : Assumptions: the flag write is the consequence of the lease decision, so a refused
    #   acquisition must not write. Writing anyway would disable online writes on behalf of an
    #   execution that does not own the window, and the owner's own release would then re-enable
    #   them while that execution was still running.
    assert result["leaseAcquired"] is False
    assert result["writePerformed"] is False
    assert ssm.puts == []
    assert ssm.value == "true"


def test_a_successful_acquisition_writes_the_derived_flag(monkeypatch: pytest.MonkeyPatch) -> None:
    """Write the boolean service flag once the bracket has actually been taken."""
    module = _load("quiesce", "false", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "true"
    _wire(module, table, ssm)

    result = module.handler(
        {
            "action": "quiesce",
            "readOnlyFlagParameter": _PARAMETER_NAME,
            "executionName": "exec-A",
            "leaseSeconds": 3600,
        },
        None,
    )

    assert result["leaseAcquired"] is True
    assert result["leaseOwner"] == "exec-A"
    assert result["writePerformed"] is True
    # WHY : Assumptions: the flag keeps its boolean spelling at the same parameter path, because
    #   every online service reads it. Moving the LEASE into a conditional store deliberately did
    #   not move the FLAG, so no online service gains a second thing to read.
    assert ssm.value == "false"
    assert result["onlineWritesEnabled"] is False


def test_an_acquisition_without_an_execution_name_is_refused(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse to record a lease whose owner could never be verified at release."""
    module = _load("quiesce", "false", monkeypatch)
    _wire(module, _FakeDynamoDb(), _FakeSsm())

    # WHY : Assumptions: an unnamed owner is refused rather than defaulted, because the owner is
    #   the only value a later release can be checked against -- a lease recording no owner is one
    #   any caller could clear, which is the defect this rewrite closes, reintroduced by omission.
    with pytest.raises(ValueError, match="executionName"):
        module._acquire_lease({"leaseSeconds": 3600}, "")


def test_the_lease_key_is_scoped_to_the_deployments_parameter(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Derive the lease key from the flag parameter so two deployments cannot collide."""
    module = _load("quiesce", "false", monkeypatch)
    assert module.LEASE_KEY == f"online-write-gate:{_PARAMETER_NAME}"


@pytest.mark.parametrize(
    ("requested", "expected"),
    [(3600, 3600), ("900", 900), (0, 7200), (-5, 7200), (None, 7200), (True, 7200), ("x", 7200)],
)
def test_lease_duration_falls_back_only_for_unusable_values(
    requested: object, expected: int, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Honour a positive duration and fall back for anything that cannot bound a lease."""
    module = _load("quiesce", "false", monkeypatch)
    # WHY : Assumptions: True is included because bool is a subclass of int in Python, so a
    #   payload carrying true would otherwise be honoured as a one-second lease -- a lease that
    #   expires almost immediately and lets the next caller take the bracket from a running owner.
    assert module._lease_seconds({"leaseSeconds": requested}) == expected


def _quiesce_payload(execution: str) -> dict[str, Any]:
    """Build the payload the daily machine's quiesce state sends."""
    return {
        "action": "quiesce",
        "readOnlyFlagParameter": _PARAMETER_NAME,
        "executionName": execution,
        "leaseSeconds": 3600,
    }


def _resume_payload(execution: str) -> dict[str, Any]:
    """Build the payload the daily machine's two release states send."""
    return {
        "action": "resume",
        "readOnlyFlagParameter": _PARAMETER_NAME,
        "executionName": execution,
        "expectedLeaseOwner": execution,
    }


def test_a_retried_quiesce_completes_the_window_its_first_attempt_opened(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Let the same execution finish a quiesce whose flag write failed after the lease was taken."""
    module = _load("quiesce", "false", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "true"
    ssm.fail_next_put = RuntimeError("parameter store unavailable")
    _wire(module, table, ssm)

    # WHY : Assumptions: the fault's TYPE is immaterial -- the handler catches nothing around the
    #   flag write -- so what this arranges is only WHERE in the sequence the invocation dies: after
    #   the lease was taken and before the flag was written. That is the state the quiesce state's
    #   own Retry then re-enters, and it is also the state a lost response or a state timeout leaves
    #   behind.
    with pytest.raises(RuntimeError, match="parameter store unavailable"):
        module.handler(_quiesce_payload("exec-A"), None)
    assert module.LEASE_KEY in table.items
    assert ssm.value == "true", "the failed attempt must not have quiesced the flag"

    retried = module.handler(_quiesce_payload("exec-A"), None)

    # WHY : Refactoring Rationale: before the acquire condition admitted the current owner, this
    #   retry met a condition that could only fail. It reported leaseAcquired false naming ITSELF as
    #   the holder, and CheckQuiesceLeaseAcquired -- which routes on exactly this boolean -- sent it
    #   to the OnlineWriteLeaseUnavailable fail state: an execution deadlocked against its own
    #   lease, with online writes still enabled and the bracket held until it expired.
    assert retried["leaseAcquired"] is True
    assert retried["leaseOwner"] == "exec-A"
    assert retried["writePerformed"] is True
    assert ssm.value == "false"


def test_a_release_whose_flag_write_fails_keeps_the_lease_for_its_retry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Hold the bracket through a failed resume, then let the retry write and release."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True
    ssm.fail_next_put = RuntimeError("parameter store unavailable")

    with pytest.raises(RuntimeError, match="parameter store unavailable"):
        module.handler(_resume_payload("exec-A"), None)

    # WHY : Refactoring Rationale: this is the state the old delete-first order could not produce
    #   safely. It deleted the lease before writing, so a failed write left the flag disabled with
    #   nothing to prove who could re-enable it, and the graph's recovery -- ResumeOnlineWrites
    #   catching into NotifyFailure, then CheckQuiesceLeaseOwnership, then
    #   ResumeOnlineWritesOnFailure with the SAME expectedLeaseOwner -- was refused with "no lease
    #   is held", skipped the write and returned SUCCESS with every online service still read-only.
    assert module.LEASE_KEY in table.items, "a failed release must not have surrendered the bracket"
    assert ssm.value == "false", "online writes must stay disabled until the flag is written"

    retried = module.handler(_resume_payload("exec-A"), None)

    assert retried["writePerformed"] is True
    assert retried["leaseReleased"] is True
    assert retried["releaseRefusedReason"] == ""
    assert ssm.value == "true"
    assert table.items == {}


def test_a_release_retry_completes_the_delete_when_the_flag_is_already_correct(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Finish the delete on a retry that finds the flag written and the lease still held."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    # The state an attempt leaves behind when it claimed, wrote the flag, and then failed to delete.
    ssm.value = "true"
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True
    ssm.puts.clear()

    result = module.handler(_resume_payload("exec-A"), None)

    # WHY : Refactoring Rationale: the delete runs on every successful CLAIM, not only when a write
    #   was performed. Gating it on the write would stall exactly this retry -- the flag is already
    #   at target so nothing needs writing, and the lease would then never be cleared by the only
    #   caller entitled to clear it, leaving the next night to wait out its expiry.
    assert result["writePerformed"] is False
    assert result["leaseReleased"] is True
    assert table.items == {}
    assert ssm.puts == [], "an already-correct flag must not be rewritten"
    assert result["onlineWritesEnabled"] is True


def test_a_release_after_the_lease_is_gone_is_reported_rather_than_failed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Report a release that has nothing left to give up, without failing the invocation."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    # The state a lost RESPONSE leaves behind: the previous attempt wrote the flag and deleted the
    # lease, and only the answer went missing.
    ssm.value = "true"
    _wire(module, table, ssm)

    result = module.handler(_resume_payload("exec-A"), None)

    # WHY : Assumptions: a refusal is DATA on this edge, not an error. The graph's failure path
    #   reaches ResumeOnlineWritesOnFailure on nights when nothing was ever acquired, so raising
    #   here would turn the normal recovery route into an unrecoverable one -- and the flag every
    #   online service reads is already in its target state, which is the outcome the caller wanted.
    assert result["releaseRefusedReason"] == "no lease is held"
    assert result["leaseReleased"] is False
    assert result["writePerformed"] is False
    assert result["onlineWritesEnabled"] is True
    assert table.items == {}


def test_a_retained_lease_is_reported_separately_from_a_refused_release(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Distinguish a flag left alone from a flag restored whose lease outlived it.

    Purpose
    -------
    Bind the ``leaseRetainedReason`` member of the result. The daily machine's release states lift
    it into their result selector by path, so a result that omits it fails the state with a runtime
    error rather than reporting anything; and the member has to mean something different from
    ``releaseRefusedReason``, because the two states an operator must tell apart are "the flag was
    deliberately not touched" and "the flag WAS restored and only the bookkeeping is outstanding".
    Both arms are asserted here, since a constant would satisfy either one alone.

    Returns
    -------
    None
        The test exists for its assertions.

    Raises
    ------
    AssertionError
        If a completed release reports a retained lease, if a release whose delete was refused
        reports none, or if either arm confuses the member with the refusal reason.
    """
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True

    completed = module.handler(_resume_payload("exec-A"), None)

    assert completed["leaseReleased"] is True
    assert completed["leaseRetainedReason"] == ""
    assert completed["releaseRefusedReason"] == ""
    assert completed["onlineWritesEnabled"] is True

    # The state an interrupted delete leaves behind: the claim held and the flag was written, and
    # only the delete did not land. Removing the item under the claim is what makes the conditional
    # delete refuse, which is the outcome the retained-lease member exists to report.
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-B")[0] is True
    ssm.value = "false"
    original_delete = table.delete_item

    def refuse_delete(**kwargs: Any) -> dict[str, Any]:
        """Refuse the conditional delete once, as a lost race with an expiry sweep would."""
        table.items.pop(kwargs["Key"]["LeaseName"]["S"], None)
        return original_delete(**kwargs)

    monkeypatch.setattr(table, "delete_item", refuse_delete)

    retained = module.handler(_resume_payload("exec-B"), None)

    assert retained["leaseReleased"] is False
    assert retained["leaseRetainedReason"] != ""
    # WHY : Assumptions: the refusal reason stays EMPTY on this arm, and that is the whole point of
    #   reporting the two separately. This caller owned the bracket and restored the flag, so
    #   nothing was refused about the release itself -- only the bookkeeping delete did not land.
    assert retained["releaseRefusedReason"] == ""
    assert retained["writePerformed"] is True
    assert retained["onlineWritesEnabled"] is True


def test_no_expression_spells_the_reserved_owner_attribute(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Send the owner attribute only through a declared alias, never as a literal name."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)

    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True
    assert module.handler(_resume_payload("exec-A"), None)["leaseReleased"] is True

    # WHY : Assumptions: this asserts on the expression TEXT because no behavioural test can reach
    #   the defect. `owner` is one of DynamoDB's reserved words, so the real service answers an
    #   expression that spells it literally with ValidationException -- which
    #   _conditional_check_failed does not recognise, so the invocation raises and the release could
    #   never succeed -- while a fake client, having no validator, evaluates it happily. Recording
    #   the expressions and refusing a bare `owner` is the only way the harness can hold a property
    #   the service enforces and the fake cannot.
    # WHY : Assumptions: the pattern excludes a `#` or `:` prefix, because only the bare attribute
    #   name is rejected. `#owner` is the alias, which is the correct spelling, and `:owner` is the
    #   VALUE placeholder, which is not an attribute name at all and is never subject to the
    #   reserved-word rule -- a pattern that missed the second would fail on every conforming
    #   expression here and prove nothing.
    assert table.expressions, "the exercised edges must have sent at least one expression"
    literal = [text for text in table.expressions if re.search(r"(?<![#:\w])owner\b", text)]
    assert literal == [], (
        "these expressions name the reserved word `owner` directly and would be rejected with "
        f"ValidationException: {literal!r}"
    )
    assert module.LEASE_OWNER_EXPRESSION_NAME == f"#{module.LEASE_OWNER_ATTRIBUTE}"


class _FakeStepFunctions:
    """Answer DescribeExecution from a settable status, or raise a settable service error.

    Purpose
    -------
    Let the reconciler's first terminality check be exercised for every answer it must handle --
    a running owner, a terminal owner, an execution the service no longer knows, and a call the
    role is not permitted to make -- without an AWS account.

    Attributes
    ----------
    status : str
        Value returned as the execution's ``status``.
    error : BaseException or None
        Raised instead of answering, when set.
    described : list of str
        Every execution ARN the module asked about.
    """

    def __init__(self, status: str = "SUCCEEDED") -> None:
        """Answer with ``status`` until told otherwise."""
        self.status = status
        self.error: BaseException | None = None
        self.described: list[str] = []

    def describe_execution(self, **kwargs: Any) -> dict[str, Any]:
        """Return the configured status, or raise the configured error."""
        self.described.append(kwargs["executionArn"])
        if self.error is not None:
            raise self.error
        return {"status": self.status, "executionArn": kwargs["executionArn"]}


class _FakeEcs:
    """Serve task listings and descriptions from settable per-desired-status pages.

    Purpose
    -------
    Exercise the reconciler's second terminality check against the two answers that must withhold
    a release -- a task ECS still desires RUNNING, and a task desired STOPPED that has not reached
    STOPPED -- and the one that must allow it.

    Attributes
    ----------
    listings : dict
        Task ARNs keyed by desired status, each optionally followed by a next-page token.
    last_statuses : dict
        ``lastStatus`` keyed by task ARN, for the describe answer.
    filters : list of dict
        Every list_tasks request, recorded so a test can assert the cluster and marker filters.
    """

    def __init__(self) -> None:
        """Start with an idle cluster."""
        self.listings: dict[str, tuple[list[str], str | None]] = {}
        self.last_statuses: dict[str, str] = {}
        self.filters: list[dict[str, Any]] = []
        self.error: BaseException | None = None

    def list_tasks(self, **kwargs: Any) -> dict[str, Any]:
        """Return the configured page for the requested desired status."""
        self.filters.append(dict(kwargs))
        if self.error is not None:
            raise self.error
        arns, token = self.listings.get(kwargs["desiredStatus"], ([], None))
        # WHY : Assumptions: an armed token is served on EVERY page, which is what a listing longer
        #   than the module reads actually looks like. Serving it once would let the module's second
        #   request end the loop cleanly and the truncation guard would never be exercised; the loop
        #   still terminates because the bound is the MODULE's MAX_TASK_PAGES, not the fake's.
        return {"taskArns": arns, "nextToken": token} if token else {"taskArns": arns}

    def describe_tasks(self, **kwargs: Any) -> dict[str, Any]:
        """Return one task per requested ARN, carrying its configured lastStatus."""
        return {
            "tasks": [
                {"taskArn": arn, "lastStatus": self.last_statuses.get(arn, "STOPPED")}
                for arn in kwargs["tasks"]
            ]
        }


def _service_error(code: str, operation: str) -> ClientError:
    """Build the SDK error the module's handlers catch by family.

    Purpose
    -------
    Raise a REAL botocore ``ClientError`` from a fake client, because the module catches
    ``(ClientError, BotoCoreError)`` by type -- a stand-in exception carrying the right response
    document would escape those handlers and fail the invocation instead of being converted into a
    refusal, which is the opposite of the behaviour under test.

    Parameters
    ----------
    code : str
        Service error code, for example ``AccessDeniedException``.
    operation : str
        Operation name the error is attributed to.

    Returns
    -------
    ClientError
        An error whose ``response`` carries the supplied code.

    Raises
    ------
    None
    """
    return ClientError({"Error": {"Code": code, "Message": code}}, operation)


def _wire_reconcile(
    module: Any,
    monkeypatch: pytest.MonkeyPatch,
    steps: _FakeStepFunctions,
    ecs: _FakeEcs,
    *,
    cluster: str = "arn:aws:ecs:us-east-1:123456789012:cluster/carddemo-test",
    started_by: str = "carddemo-test-sfn",
) -> None:
    """Point a loaded module's lazy clients and reconcile settings at fakes.

    Parameters
    ----------
    module : Any
        Module returned by :func:`_load`.
    monkeypatch : pytest.MonkeyPatch
        Fixture used to set the two module-level reconcile constants.
    steps : _FakeStepFunctions
        Stands in for the Step Functions client.
    ecs : _FakeEcs
        Stands in for the ECS client.
    cluster : str
        Value for ``RECONCILE_CLUSTER_ARN``; blank exercises the fail-closed path.
    started_by : str
        Value for ``RECONCILE_TASK_STARTED_BY``; blank exercises the fail-closed path.

    Returns
    -------
    None

    Raises
    ------
    None
    """
    # WHY : Assumptions: the two settings are patched as module ATTRIBUTES rather than as
    #   environment variables, because the module reads them at import time into constants. Setting
    #   the environment after _load has run would change nothing, which is the sort of test that
    #   passes while verifying the opposite of what it claims.
    monkeypatch.setattr(module, "RECONCILE_CLUSTER_ARN", cluster)
    monkeypatch.setattr(module, "RECONCILE_TASK_STARTED_BY", started_by)
    module._LAZY_CLIENTS["stepfunctions"] = steps
    module._LAZY_CLIENTS["ecs"] = ecs


def _quiesce_with_arn(
    quiesce: Any, execution: str, arn: str, seconds: int = 3600
) -> tuple[bool, str, str]:
    """Take the bracket recording both the owner name and its execution ARN."""
    return quiesce._acquire_lease({"leaseSeconds": seconds, "leaseOwnerArn": arn}, execution)


_EXECUTION_ARN = "arn:aws:states:us-east-1:123456789012:execution:carddemo-test-daily-batch:exec-A"


def _reconcile_payload() -> dict[str, Any]:
    """Build the payload the scheduled reconciler rule sends."""
    return {
        "action": "resume",
        "readOnlyFlagParameter": _PARAMETER_NAME,
        "reconcile": True,
        "confirmTasksStopped": True,
        "releasedBy": "batch-bracket-reconciler",
    }


def test_the_acquisition_records_the_owning_execution_arn(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Store the execution ARN beside the owner name, and omit it when none was supplied."""
    module = _load("quiesce", "false", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    _wire(module, table, ssm)

    assert _quiesce_with_arn(module, "exec-A", _EXECUTION_ARN)[0] is True
    stored = table.items[module.LEASE_KEY]
    assert stored[module.LEASE_OWNER_ARN_ATTRIBUTE]["S"] == _EXECUTION_ARN

    # WHY : Assumptions: a hand-run acquisition has no execution ARN to give, so the attribute must
    #   be ABSENT rather than stored empty. An empty string would be handed to DescribeExecution,
    #   which answers with a validation error the reconciler would read as "status unavailable" --
    #   turning a lease that should fall back to expiry into one that can never be reconciled.
    table.items.clear()
    assert module._acquire_lease({"leaseSeconds": 3600}, "exec-B")[0] is True
    assert module.LEASE_OWNER_ARN_ATTRIBUTE not in table.items[module.LEASE_KEY]


def test_reconciliation_releases_a_bracket_whose_execution_and_tasks_are_terminal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Clear a stranded bracket once both terminality facts are established."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("ABORTED"), _FakeEcs()
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    # WHY : Refactoring Rationale: this is the case the finding named -- lease expiry was passive
    #   and nothing ever flipped the flag on its own, so a bracket left by an execution that was
    #   aborted or whose finalizer delivery failed stayed engaged with every online service
    #   read-only until a human noticed.
    assert result["reconciled"] is True
    assert result["leaseReleased"] is True
    assert result["writePerformed"] is True
    assert result["onlineWritesEnabled"] is True
    assert result["leaseOwner"] == "exec-A"
    assert table.items == {}
    # The owner is DERIVED from the lease, because a scheduled caller has nobody to name.
    assert steps.described == [_EXECUTION_ARN]
    assert [entry["startedBy"] for entry in ecs.filters] == ["carddemo-test-sfn"] * 2
    assert [entry["desiredStatus"] for entry in ecs.filters] == ["RUNNING", "STOPPED"]


def test_reconciliation_refuses_while_the_owning_execution_still_runs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Leave the bracket alone while the execution that took it is still RUNNING."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("RUNNING"), _FakeEcs()
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    # WHY : Assumptions: this is the property that makes a CADENCE safe where a staleness threshold
    #   would not be. A legitimate long night is an execution that is still running, so the answer
    #   to "may I release?" is no however often it is asked -- and the flag is not written.
    assert result["reconciled"] is False
    assert result["releaseRefusedReason"] == "owning execution is still RUNNING"
    assert result["writePerformed"] is False
    assert result["onlineWritesEnabled"] is False
    assert ssm.puts == []
    assert module.LEASE_KEY in table.items
    # The task check is not even reached, because the first fact already withholds the release.
    assert ecs.filters == []


def test_reconciliation_refuses_while_a_batch_task_is_still_running(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Withhold the release while a task the chain started is still desired-RUNNING."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("TIMED_OUT"), _FakeEcs()
    ecs.listings["RUNNING"] = (["arn:aws:ecs:us-east-1:123456789012:task/carddemo-test/abc"], None)
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    # WHY : Refactoring Rationale: a terminal EXECUTION does not imply terminal WRITERS. A task
    #   started by a synchronous run-task state outlives the state that started it when that state
    #   times out, so releasing on execution terminality alone would re-enable online writes
    #   underneath a posting container that is still writing -- which is the concurrency finding
    #   this check answers.
    assert result["reconciled"] is False
    assert result["releaseRefusedReason"] == "1 batch task(s) are still desired-RUNNING"
    assert ssm.puts == []
    assert module.LEASE_KEY in table.items


def test_reconciliation_refuses_while_a_stopping_task_has_not_reached_stopped(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Read lastStatus, because StopTask records intent rather than completion."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    draining = "arn:aws:ecs:us-east-1:123456789012:task/carddemo-test/draining"
    steps, ecs = _FakeStepFunctions("FAILED"), _FakeEcs()
    ecs.listings["STOPPED"] = ([draining], None)
    ecs.last_statuses[draining] = "DEACTIVATING"
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    assert result["reconciled"] is False
    assert result["releaseRefusedReason"] == "1 batch task(s) have not reached STOPPED"
    assert module.LEASE_KEY in table.items


def test_reconciliation_fails_closed_without_the_cluster_settings(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse rather than release when task terminality cannot be established at all."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("SUCCEEDED"), _FakeEcs()
    _wire_reconcile(module, monkeypatch, steps, ecs, cluster="", started_by="")
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    # WHY : Assumptions: a misconfigured watchdog must withhold the release, not grant it. The
    #   opposite default would make a forgotten environment variable indistinguishable from an idle
    #   cluster, and the failure would be an unsafe release rather than a late one.
    assert result["reconciled"] is False
    assert "cannot be confirmed without" in result["releaseRefusedReason"]
    assert module.LEASE_KEY in table.items
    assert ecs.filters == []


def test_reconciliation_leaves_an_unexpired_hand_run_lease_alone(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Require expiry when no execution ARN was recorded, and release once it has passed."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("SUCCEEDED"), _FakeEcs()
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    monkeypatch.setattr(module.time, "time", lambda: 1_000_000)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "operator-run")[0] is True

    held = module.handler(_reconcile_payload(), None)

    # WHY : Assumptions: a lease with no ARN cannot be verified against Step Functions at all, so
    #   the fallback is the one fact that is still available -- expiry. Releasing it earlier would
    #   be releasing on no evidence, which is what the finding asked this watchdog to stop doing.
    assert held["reconciled"] is False
    assert held["releaseRefusedReason"] == (
        "lease has not expired and records no owning execution to verify"
    )
    assert steps.described == []

    monkeypatch.setattr(module.time, "time", lambda: 1_009_000)
    released = module.handler(_reconcile_payload(), None)

    assert released["reconciled"] is True
    assert released["leaseOwner"] == "operator-run"
    assert table.items == {}


def test_reconciliation_reports_no_lease_when_nothing_is_held(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Do nothing on the ordinary cycle where no bracket is engaged."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "true"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("SUCCEEDED"), _FakeEcs()
    _wire_reconcile(module, monkeypatch, steps, ecs)

    result = module.handler(_reconcile_payload(), None)

    # WHY : Assumptions: this is the answer on almost every cycle, so it must be cheap and silent.
    #   Nothing is read from Step Functions or ECS and nothing is written, which is what makes a
    #   fifteen-minute cadence a rounding error rather than a cost.
    assert result["reconciled"] is False
    assert result["releaseRefusedReason"] == "no lease is held"
    assert ssm.puts == []
    assert steps.described == []
    assert ecs.filters == []


def test_a_denied_describe_execution_withholds_the_release(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse, without raising, when the owning execution's status cannot be read."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("SUCCEEDED"), _FakeEcs()
    steps.error = _service_error("AccessDeniedException", "DescribeExecution")
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    # WHY : Assumptions: this is the gap between the functions being created and the separate
    #   inline policy that grants states:DescribeExecution landing -- an ordering the environment
    #   root cannot close, because a policy the functions depended on would close a Terraform
    #   dependency cycle. The invocation must therefore SUCCEED with a refusal rather than raise,
    #   so the gap costs one skipped cycle instead of an error and an alarm.
    assert result["reconciled"] is False
    assert "could not be read" in result["releaseRefusedReason"]
    assert "AccessDeniedException" in result["releaseRefusedReason"]
    assert module.LEASE_KEY in table.items


def test_a_vanished_execution_counts_as_terminal(monkeypatch: pytest.MonkeyPatch) -> None:
    """Treat an execution the service no longer knows as stopped, not as unknown."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("SUCCEEDED"), _FakeEcs()
    steps.error = _service_error("ExecutionDoesNotExist", "DescribeExecution")
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    # WHY : Assumptions: a STANDARD execution's history is retained for 90 days, so an ARN the
    #   service cannot find belongs to an execution older than that -- one that demonstrably is not
    #   running. Treating it as unknown would strand the bracket for ever with no path to release.
    assert result["reconciled"] is True
    assert table.items == {}


def test_the_finalizer_payload_confirms_tasks_before_claiming(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse the finalizer's named release while a task it abandoned is still running."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("ABORTED"), _FakeEcs()
    ecs.listings["RUNNING"] = (["arn:aws:ecs:us-east-1:123456789012:task/carddemo-test/live"], None)
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    payload = _resume_payload("exec-A") | {"confirmTasksStopped": True, "finalizer": True}
    result = module.handler(payload, None)

    # WHY : Refactoring Rationale: the finalizer fires the INSTANT an execution terminates, when a
    #   task abandoned by a timed-out synchronous state may still be writing. Without this check the
    #   fast recovery path would re-enable online writes underneath exactly that container; with it
    #   the release is late -- the reconciler picks it up once the task stops -- instead of unsafe.
    assert result["reconciled"] is False
    assert result["releaseRefusedReason"] == "1 batch task(s) are still desired-RUNNING"
    assert ssm.puts == []
    assert module.LEASE_KEY in table.items

    # Once the task has stopped, the SAME payload completes the release.
    ecs.listings["RUNNING"] = ([], None)
    completed = module.handler(payload, None)
    assert completed["leaseReleased"] is True
    assert completed["onlineWritesEnabled"] is True


def test_the_in_graph_release_does_not_consult_ecs(monkeypatch: pytest.MonkeyPatch) -> None:
    """Leave the two in-graph release states on the cheap path they already earn."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("RUNNING"), _FakeEcs()
    ecs.listings["RUNNING"] = (["arn:aws:ecs:us-east-1:123456789012:task/carddemo-test/live"], None)
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_resume_payload("exec-A"), None)

    # WHY : Assumptions: an in-graph release runs INSIDE the execution that owns the lease, after
    #   the graph has confirmed its own tasks stopped, so asking ECS again would be asking a
    #   question the graph has already answered -- and the fake's running task proves the check is
    #   genuinely not consulted rather than merely returning true here.
    assert result["leaseReleased"] is True
    assert result["reconciled"] is False
    assert ecs.filters == []
    assert steps.described == []


def test_a_reconcile_payload_is_refused_by_the_quiesce_function(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse a misdirected watchdog payload instead of taking the bracket it meant to release."""
    module = _load("quiesce", "false", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "true"
    _wire(module, table, ssm)

    with pytest.raises(ValueError, match="may only be sent to the resume function"):
        module.handler(
            {
                "action": "quiesce",
                "readOnlyFlagParameter": _PARAMETER_NAME,
                "executionName": "exec-A",
                "reconcile": True,
            },
            None,
        )

    # WHY : Assumptions: nothing is written on the refusal, which is the point -- the alternative
    #   reading, ignoring an unread key, would have this function DISABLE online writes on the
    #   reconciler's cadence, with the intent visible in the history and inverted in effect.
    assert table.items == {}
    assert ssm.puts == []


def test_a_string_flag_only_counts_when_it_spells_true(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Read a payload flag as a whitelist of affirmatives, never as Python truthiness."""
    module = _load("resume", "true", monkeypatch)

    assert module._payload_flag({"reconcile": True}, "reconcile") is True
    assert module._payload_flag({"reconcile": "true"}, "reconcile") is True
    assert module._payload_flag({"reconcile": " TRUE "}, "reconcile") is True
    # WHY : Assumptions: the string "false" is TRUTHY in Python, so a truthiness test would switch
    #   reconcile mode on for a payload that spells it off -- the one input shape most likely to
    #   arrive from a hand-written invocation or a template that stringifies its booleans.
    assert module._payload_flag({"reconcile": "false"}, "reconcile") is False
    assert module._payload_flag({"reconcile": 1}, "reconcile") is False
    assert module._payload_flag({}, "reconcile") is False


def test_a_truncated_task_listing_withholds_the_release(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Refuse when the cluster holds more marked tasks than the bounded listing can read."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    ssm.value = "false"
    _wire(module, table, ssm)
    steps, ecs = _FakeStepFunctions("SUCCEEDED"), _FakeEcs()
    ecs.listings["STOPPED"] = ([], "more-pages")
    _wire_reconcile(module, monkeypatch, steps, ecs)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert _quiesce_with_arn(quiesce, "exec-A", _EXECUTION_ARN)[0] is True

    result = module.handler(_reconcile_payload(), None)

    # WHY : Assumptions: the page bound exists because an unbounded loop inside a 30-second function
    #   is a timeout, and a cluster carrying 500 tasks with this marker is not one whose window
    #   can be declared quiet. Refusing on truncation is therefore the honest answer rather than a
    #   limitation: the next cycle will read a shorter list.
    assert result["reconciled"] is False
    assert result["releaseRefusedReason"] == "the stopped-task listing was truncated"
    assert module.LEASE_KEY in table.items
