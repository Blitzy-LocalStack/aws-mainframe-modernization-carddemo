"""Verify the online-write bracket is an atomic, owner-verified lease.

Purpose
-------
Exercise ``infra/lambda/online_write_flag.py`` against a fake DynamoDB that enforces the same
conditional-write semantics the real service does, so the two properties the bracket depends on --
only one execution may hold it, and only its owner or an expired lease may release it -- are
checked rather than asserted in a comment.

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
import sys
import types
from pathlib import Path
from typing import Any

import pytest

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
    Evaluate the two condition expressions for real, because a fake that accepted every
    conditional write would let both lease properties appear to hold while neither did.

    Attributes
    ----------
    items : dict
        The stored items, keyed by partition-key value.
    now : int
        The clock the conditions are evaluated against, so expiry can be reached without waiting.
    """

    def __init__(self, now: int = 1_000_000) -> None:
        """Create an empty table with a controllable clock."""
        self.items: dict[str, dict[str, Any]] = {}
        self.now = now

    def _condition_holds(self, expression: str, key: str, values: dict[str, Any]) -> bool:
        """Evaluate one of the module's two condition expressions against the stored item."""
        stored = self.items.get(key)
        # WHY : Assumptions: the two expressions are recognised by SHAPE rather than parsed
        #   generally, and each branch is evaluated the way DynamoDB would. A general parser would
        #   be a second implementation to get wrong; recognising the exact expressions the module
        #   sends keeps the fake honest about what it verifies, and an unrecognised expression
        #   fails loudly below rather than defaulting to true.
        if expression.startswith("attribute_not_exists"):
            if stored is None:
                return True
            return int(stored["expiresAt"]["N"]) < int(values[":now"]["N"])
        if expression.startswith("owner ="):
            if stored is None:
                return False
            if stored["owner"]["S"] == values[":owner"]["S"]:
                return True
            return int(stored["expiresAt"]["N"]) < int(values[":now"]["N"])
        raise AssertionError(f"unrecognised condition expression: {expression!r}")

    def put_item(self, **kwargs: Any) -> dict[str, Any]:
        """Store an item when its condition expression holds, otherwise refuse."""
        key = kwargs["Item"]["LeaseName"]["S"]
        expression = kwargs.get("ConditionExpression")
        if expression is not None and not self._condition_holds(
            expression, key, kwargs.get("ExpressionAttributeValues", {})
        ):
            raise _ConditionalCheckFailed()
        self.items[key] = kwargs["Item"]
        return {}

    def delete_item(self, **kwargs: Any) -> dict[str, Any]:
        """Remove an item when its condition expression holds, otherwise refuse."""
        key = kwargs["Key"]["LeaseName"]["S"]
        expression = kwargs.get("ConditionExpression")
        if expression is not None and not self._condition_holds(
            expression, key, kwargs.get("ExpressionAttributeValues", {})
        ):
            raise _ConditionalCheckFailed()
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
        """Start with no parameter stored and no writes recorded."""
        self.value: str | None = None
        self.puts: list[dict[str, Any]] = []

    def get_parameter(self, **kwargs: Any) -> dict[str, Any]:
        """Return the stored value, or raise the absent-parameter error."""
        if self.value is None:
            raise self.exceptions.ParameterNotFound()
        return {"Parameter": {"Value": self.value, "Version": 1}}

    def put_parameter(self, **kwargs: Any) -> dict[str, Any]:
        """Record a write and store the new value."""
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
    table.now = 1_000_061

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

    owner, reason = module._release_lease({})

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

    owner, reason = module._release_lease({"expectedLeaseOwner": "exec-B"})

    assert owner == "exec-B"
    assert reason == "lease is held by exec-A"
    assert quiesce.LEASE_KEY in table.items


def test_the_owner_can_release_its_own_lease(monkeypatch: pytest.MonkeyPatch) -> None:
    """Release the bracket for the execution that holds it."""
    module = _load("resume", "true", monkeypatch)
    table, ssm = _FakeDynamoDb(), _FakeSsm()
    _wire(module, table, ssm)
    quiesce = _load("quiesce", "false", monkeypatch)
    _wire(quiesce, table, ssm)
    assert quiesce._acquire_lease({"leaseSeconds": 3600}, "exec-A")[0] is True

    owner, reason = module._release_lease({"expectedLeaseOwner": "exec-A"})

    assert (owner, reason) == ("exec-A", "")
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
