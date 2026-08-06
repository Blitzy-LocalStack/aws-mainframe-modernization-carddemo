"""Toggle the CardDemo online-write gate used around the nightly batch chain.

Purpose
-------
Update one Systems Manager Parameter Store value to quiesce or resume online
writes. Separate Lambda functions use this same handler with different
``EXPECTED_ACTION`` and ``TARGET_VALUE`` environment settings, which keeps the
state-machine ARNs distinct while single-sourcing the mutation logic.

Environment
-----------
``PARAMETER_NAME``
    Absolute SSM parameter name holding ``true`` or ``false``.
``EXPECTED_ACTION``
    Either ``quiesce`` or ``resume``; mismatched events are rejected.
``TARGET_VALUE``
    Either ``true`` or ``false``; the value written with overwrite enabled.

Returns
-------
dict
    The action, the parameter written, the flag's resulting boolean state, the
    SSM version, the execution name, and the LEASE RECORD the batch state
    machine reads: ``leaseAcquired``, ``leaseOwner``, ``writePerformed`` and
    ``releaseRefusedReason``.

Payload
-------
``action``
    Either ``quiesce`` or ``resume``; must equal ``EXPECTED_ACTION``.
``readOnlyFlagParameter``
    The parameter the caller intends to change. It is **required** and must
    equal ``PARAMETER_NAME``. The caller naming its own subject is what makes a
    misdirected invocation a refusal rather than a silent write to whichever
    parameter this function happens to be configured for.
``expectedLeaseOwner``
    Release edge only, and optional. Absent means an unconditional release, and
    is what the bracket-finalizer rule sends. Present and empty means the caller
    knows it does not hold the bracket, and the release is refused. Present and
    non-empty is a conditional release by the execution that acquired it.
``executionName``
    Optional; logged and echoed back for correlation, and recorded as
    ``leaseOwner`` on a successful acquisition. It never influences which
    parameter is written or the value written to it.

Raises
------
RuntimeError
    If required environment configuration is missing or malformed.
ValueError
    If the payload does not match this function's configured action, or omits
    or contradicts ``readOnlyFlagParameter``.
botocore.exceptions.BotoCoreError
    If the SSM write cannot be completed.

Notes
-----
The caller passes ``readOnlyFlagParameter`` alongside ``action`` on every
invocation. It is a CROSS-CHECK and never a redirect: this function writes
``PARAMETER_NAME`` from its own environment and refuses an invocation that
names anything else.

The flag doubles as the batch write window's LOCK. A quiesce acquires it only
when it is not already engaged and reports the outcome as ``leaseAcquired``
with the acquiring execution as ``leaseOwner``; a release honours
``expectedLeaseOwner`` so that an execution which never took the bracket cannot
clear one another execution holds. Ownership itself is not recorded in the
parameter -- the value is the boolean every online service reads -- so the
release check is bounded by what a boolean can attest: it declines to clear on
behalf of a caller that names no owner, and it declines to write at all when the
flag already holds the value being asked for.

Alternatives Considered: treating the payload value as the parameter to write,
which is what a reader seeing it forwarded would first assume. Rejected because
it would let whoever composes an execution payload steer an overwrite-enabled
SSM write at any parameter this function's role can reach, turning a workflow
input into a configuration-tampering path. Rejected also because the two
invocations of this handler are configured with different target values, so the
parameter and the value have to be decided together and only the environment
holds both.

Alternatives Considered: ignoring the payload value, which is what an earlier
revision did. Rejected because all four callers pass it expressly so that a
quiesce/resume pair naming two different parameters is visible in one place --
the daily state machine on its one quiesce and its two resume invocations, and
the bracket-finalizer rule on the fourth. A value that is passed and never read
records an intent nothing enforces, and the mismatch it was meant to expose
would still reach production.
"""

from __future__ import annotations

import logging
import os
from typing import Any

import boto3

LOGGER = logging.getLogger(__name__)
LOGGER.setLevel(logging.INFO)


def _required_environment(name: str) -> str:
    """Return a non-blank environment value.

    Parameters
    ----------
    name:
        Environment variable to resolve.

    Returns
    -------
    str
        The trimmed value.

    Raises
    ------
    RuntimeError
        If the variable is absent or blank.
    """

    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable {name}")
    return value


PARAMETER_NAME = _required_environment("PARAMETER_NAME")
EXPECTED_ACTION = _required_environment("EXPECTED_ACTION")
TARGET_VALUE = _required_environment("TARGET_VALUE").lower()

if EXPECTED_ACTION not in {"quiesce", "resume"}:
    raise RuntimeError("EXPECTED_ACTION must be quiesce or resume")
if TARGET_VALUE not in {"true", "false"}:
    raise RuntimeError("TARGET_VALUE must be true or false")
if not PARAMETER_NAME.startswith("/"):
    raise RuntimeError("PARAMETER_NAME must be an absolute SSM path")

SSM = boto3.client("ssm")


def _current_flag_state() -> tuple[str, int]:
    """Return the online-write flag's current value and version.

    Returns
    -------
    tuple[str, int]
        The trimmed lower-cased value and its SSM version, or ``("", 0)`` when
        the parameter does not exist yet.
    """

    # WHY : Assumptions: an ABSENT parameter is reported as an empty value rather
    # than raised. Terraform creates it with "true" before either function is
    # invocable, so absence means a hand-run against an environment that was never
    # applied; on the quiesce edge an empty value is not the target value, so the
    # caller acquires the lease and the write creates the parameter -- which is the
    # fail-safe direction, because it disables online writes rather than enabling
    # them on a gate nobody has provisioned.
    try:
        parameter = SSM.get_parameter(Name=PARAMETER_NAME)
    except SSM.exceptions.ParameterNotFound:
        return "", 0
    stored = parameter["Parameter"]
    return str(stored["Value"]).strip().lower(), int(stored["Version"])


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Apply the configured online-write state.

    Parameters
    ----------
    event:
        Step Functions or EventBridge payload carrying ``action`` and
        ``readOnlyFlagParameter``, and optionally ``executionName``.
    context:
        Lambda invocation context. It is intentionally not logged because its
        request metadata is already available in platform logs.

    Returns
    -------
    dict
        Stable result metadata suitable for a Step Functions ``ResultPath``,
        including the lease record the ownership gate and both release edges
        address by reference path.

    Raises
    ------
    ValueError
        If the event action is missing or targets the other function, or if
        ``readOnlyFlagParameter`` is missing or names a different parameter.
    botocore.exceptions.BotoCoreError
        If Parameter Store rejects the update.
    """

    del context
    action = str(event.get("action", "")).strip()
    if action != EXPECTED_ACTION:
        raise ValueError(
            f"Expected action {EXPECTED_ACTION!r}, received {action or '<missing>'!r}"
        )

    # WHY : Refactoring Rationale: every caller already SENDS this field -- the daily
    # state machine sets it on its one quiesce and its two resume invocations, and the
    # bracket-finalizer rule sets it on the fourth -- but an earlier revision never read
    # it, so the field recorded the intent in the execution history while the write went
    # to whatever PARAMETER_NAME happened to hold. Reading it closes the gap between
    # what the history records and what the function does.
    # WHY : Trade-offs: the field is REQUIRED rather than validated-when-present. The two
    # functions sharing this handler differ only by their environment, so a payload aimed
    # at one environment's gate reaches the other's without complaint unless the caller
    # names its own subject. The accepted cost is that a hand-rolled test invocation must
    # name the parameter; that is one line, and it buys a fail-closed contract.
    # WHY : Alternatives Considered: accepting an ABSENT key while refusing a MISMATCHED
    # one, on the grounds that requiring it couples this handler to one caller's payload
    # shape. Rejected on two counts. That coupling already exists and is intended --
    # `action` above is mandatory and arrives in the same payload -- so requiring one
    # further field of it introduces no new coupling. And all four call sites in
    # infra/modules/step-functions-batch/main.tf send the field, so the tolerated case
    # has no caller: what an absence would actually mean is a caller that forgot, and
    # that is the case most worth refusing.
    # WHY : Assumptions: a MISMATCH is never legitimate. It means the caller believes a
    # different parameter governs the online-write gate than the one this function
    # writes, so proceeding would leave the caller's parameter untouched while reporting
    # success -- and on the resume path that is precisely the outcome of leaving every
    # online service read-only after the batch window has closed.
    requested_parameter = str(event.get("readOnlyFlagParameter", "")).strip()
    if not requested_parameter:
        raise ValueError(
            "Payload must name readOnlyFlagParameter; "
            f"this function is configured for {PARAMETER_NAME!r}"
        )
    if requested_parameter != PARAMETER_NAME:
        raise ValueError(
            f"Payload targets parameter {requested_parameter!r}, but this "
            f"function is configured for {PARAMETER_NAME!r}"
        )

    execution_name = str(event.get("executionName", "")).strip()
    current_value, current_version = _current_flag_state()
    already_at_target = current_value == TARGET_VALUE

    # WHY : Assumptions: the flag doubles as the bracket's LOCK, and this block is
    # what turns a blind overwrite into a lease. The quiesce edge acquires only when
    # the flag is not already engaged, so an execution that starts while another
    # execution owns the write window learns that it does not own it instead of
    # taking a bracket it will later release under the first execution's feet.
    # WHY : Trade-offs: acquisition is a read followed by a write and is therefore
    # not atomic. Two executions that read within the same instant can both believe
    # they acquired. The residual window is accepted because the schedule starts one
    # execution per night and the alternative -- a conditional write -- is not
    # offered by Parameter Store for a plain String parameter, so closing it would
    # mean moving the flag into a store with a compare-and-set primitive and giving
    # every online service a second client to read it with.
    # WHY : Alternatives Considered: RAISING on a refused acquisition instead of
    # reporting it. Rejected because the state machine models refusal as data, not as
    # an error: CheckQuiesceLeaseOwnership tests leaseAcquired for BooleanEquals true
    # behind an IsPresent guard, which is only reachable when the call SUCCEEDED and
    # said no. Raising would collapse "another owner holds it" into the same $.failure
    # shape as "Parameter Store is unreachable", and the graph could no longer tell a
    # contended night from a broken one.
    if EXPECTED_ACTION == "quiesce":
        lease_acquired = not already_at_target
        lease_owner = execution_name if lease_acquired else ""
        write_wanted = lease_acquired
        release_refused_reason = ""
    else:
        # WHY : Assumptions: expectedLeaseOwner has THREE states on the release edge
        # and they mean different things. ABSENT is an unconditional release and is
        # what the bracket-finalizer rule sends -- that rule exists precisely to clear
        # a bracket whose owner stopped without releasing it, so it must not be
        # refused for naming no owner. EMPTY is a caller that knows it does not own the
        # lease: the quiesce call reports an empty owner when it was refused, and the
        # in-graph release passes that value straight through, so an empty string is
        # the graph asking to clear a bracket it never took. PRESENT is a conditional
        # release by an owner that believes it holds the flag.
        # WHY : Trade-offs: a PRESENT owner cannot be verified. The flag stores
        # "true"/"false" and nothing else, so no owner is recorded to compare against,
        # and the strongest check the stored value supports is "is the bracket still
        # engaged". Storing the owner in the value was rejected because the value is
        # read by every online service as a boolean and by this handler's own
        # TARGET_VALUE validation; a second, owner-bearing parameter was rejected
        # because it adds a resource, a grant and a module input to close a window the
        # in-graph ownership gate already covers on the failure edge.
        lease_acquired = False
        if "expectedLeaseOwner" in event:
            lease_owner = str(event.get("expectedLeaseOwner", "")).strip()
            release_refused_reason = "" if lease_owner else "caller holds no lease"
        else:
            lease_owner = ""
            release_refused_reason = ""
        write_wanted = not already_at_target and not release_refused_reason

    if write_wanted:
        parameter_version = int(
            SSM.put_parameter(
                Name=PARAMETER_NAME,
                Value=TARGET_VALUE,
                Type="String",
                Overwrite=True,
            )["Version"]
        )
        effective_value = TARGET_VALUE
    else:
        parameter_version = current_version
        effective_value = current_value

    LOGGER.info(
        "event=online_write_gate_updated action=%s enabled=%s written=%s parameter=%s "
        "leaseAcquired=%s leaseOwner=%s refused=%s execution=%s",
        action,
        effective_value or "unset",
        write_wanted,
        PARAMETER_NAME,
        lease_acquired,
        lease_owner or "none",
        release_refused_reason or "none",
        execution_name or "unknown",
    )
    # WHY : Refactoring Rationale: this log line used to carry a parameterConfirmed flag
    # derived from the payload field. It was dropped when the field became required,
    # because the refusal above admits only one value for it -- a log field that cannot
    # vary records nothing and costs a column in every line.
    # WHY : Assumptions: the parameter written is still reported back in the RESPONSE
    # rather than left implicit, because the caller supplied a name and an assertion
    # that it agreed is more useful in an execution history than the absence of a
    # complaint.
    # WHY : Assumptions: onlineWritesEnabled reports the flag's ACTUAL state rather
    # than the state this function was configured to impose, because the two differ
    # whenever a write was skipped and the difference is the whole point of the
    # report. leaseAcquired and leaseOwner are the two members the state machine
    # addresses by path, so they are returned unconditionally -- an absent path in a
    # Parameters field is a runtime error rather than a false, which is why a release
    # edge must be able to read an owner even on a night when nothing was acquired.
    return {
        "action": action,
        "readOnlyFlagParameter": PARAMETER_NAME,
        "onlineWritesEnabled": effective_value == "true",
        "parameterVersion": parameter_version,
        "executionName": execution_name,
        "leaseAcquired": lease_acquired,
        "leaseOwner": lease_owner,
        "writePerformed": write_wanted,
        "releaseRefusedReason": release_refused_reason,
    }
