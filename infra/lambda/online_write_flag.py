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
import time
from typing import Any

import boto3
from botocore.exceptions import BotoCoreError, ClientError

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
LEASE_TABLE_NAME = _required_environment("LEASE_TABLE_NAME")

if EXPECTED_ACTION not in {"quiesce", "resume"}:
    raise RuntimeError("EXPECTED_ACTION must be quiesce or resume")
if TARGET_VALUE not in {"true", "false"}:
    raise RuntimeError("TARGET_VALUE must be true or false")
if not PARAMETER_NAME.startswith("/"):
    raise RuntimeError("PARAMETER_NAME must be an absolute SSM path")

SSM = boto3.client("ssm")
DYNAMODB = boto3.client("dynamodb")

#: Partition-key value of the single lease item. The bracket is global to a deployment -- there is
#: one online-write window, not one per dataset -- so one item governs it, and the parameter path
#: names it so two deployments sharing a table cannot collide.
LEASE_KEY = f"online-write-gate:{PARAMETER_NAME}"

#: Attribute names of the lease item. Held as constants because the condition expressions below
#: reference them as expression-attribute names and a divergence between a write and a condition
#: would silently make the condition test an attribute nothing sets.
LEASE_KEY_ATTRIBUTE = "LeaseName"
LEASE_OWNER_ATTRIBUTE = "owner"
LEASE_EXPIRES_ATTRIBUTE = "expiresAt"

#: Seconds a lease is honoured for when the caller supplies no explicit duration. The graph passes
#: its own state-machine timeout, which is the longest a running execution can hold the bracket, so
#: this default is only reached by a hand-run invocation.
DEFAULT_LEASE_SECONDS = 7200


def _conditional_check_failed(exc: BaseException) -> bool:
    """Report whether an SDK exception is a refused conditional write.

    Purpose
    -------
    Recognise the one service answer that is an expected outcome rather than a fault -- another
    owner holds the lease, or the lease the caller believes it holds is not the one stored -- so
    it can be reported as data instead of raised as an error.

    Parameters
    ----------
    exc:
        Exception raised by a DynamoDB call.

    Returns
    -------
    bool
        True when the exception is a conditional-check failure.

    Raises
    ------
    None
        Every lookup is defensive, because this runs while another exception is already being
        handled and raising here would replace the original failure with a less informative one.
    """
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return False
    error = response.get("Error")
    if not isinstance(error, dict):
        return False
    return error.get("Code") == "ConditionalCheckFailedException"


def _stored_lease_owner() -> str:
    """Return the owner recorded on the stored lease, or an empty string when none is stored.

    Purpose
    -------
    Report who holds the bracket after an acquisition was refused, so an execution history records
    which execution it lost to rather than only that it lost.

    Returns
    -------
    str
        The recorded owner, or ``""`` when no lease is stored or it records no owner.

    Raises
    ------
    None
        A read failure yields an empty string. This runs only on the refusal path, where the
        acquisition result is already decided, so failing here would turn a correctly-refused
        acquisition into a broken invocation.
    """
    try:
        stored = DYNAMODB.get_item(
            TableName=LEASE_TABLE_NAME,
            Key={LEASE_KEY_ATTRIBUTE: {"S": LEASE_KEY}},
            # WHY : Assumptions: the read is STRONGLY consistent. An eventually consistent read
            # can return the state before the write that just refused this caller, which would
            # report "no owner" for a lease that demonstrably exists -- the most confusing
            # possible answer at exactly the moment an operator is reading the log to find out
            # who holds the bracket.
            ConsistentRead=True,
        ).get("Item")
    # WHY : Trade-offs: this catches the SDK's two exception families by name rather than catching
    # every exception. It is the only swallowing handler in this module -- the two conditional-write
    # handlers below re-raise anything that is not a refused condition -- so it is the one place a
    # blind catch could hide a programming error in this file behind an empty owner string. Naming
    # the families keeps a service or transport failure absorbed, as the Raises note requires, while
    # letting a TypeError or AttributeError here surface as the fault it is.
    except (ClientError, BotoCoreError):
        return ""
    if not isinstance(stored, dict):
        return ""
    owner = stored.get(LEASE_OWNER_ATTRIBUTE)
    return str(owner.get("S", "")) if isinstance(owner, dict) else ""


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


def _lease_seconds(event: dict[str, Any]) -> int:
    """Return the lease duration this acquisition should record.

    Purpose
    -------
    Bound how long a lease survives its owner, so an execution that stops without releasing
    cannot hold the bracket for ever.

    Parameters
    ----------
    event:
        Invocation payload, read for ``leaseSeconds``.

    Returns
    -------
    int
        The requested duration when it is a positive integer, otherwise
        :data:`DEFAULT_LEASE_SECONDS`.

    Raises
    ------
    None
        An unusable value falls back to the default rather than failing the invocation, because
        the graph already supplies a sound value and a hand-run must not be blocked by omitting it.
    """
    # WHY : Assumptions: the graph passes its own state-machine timeout as leaseSeconds, which is
    # the correct ceiling because it IS the longest a running execution can hold the bracket. A
    # shorter value would let a still-running execution's lease expire underneath it; a longer one
    # would leave a dead execution's lease blocking the next night.
    requested = event.get("leaseSeconds")
    if isinstance(requested, bool):
        return DEFAULT_LEASE_SECONDS
    if isinstance(requested, int) and requested > 0:
        return requested
    if isinstance(requested, str):
        try:
            parsed = int(requested.strip())
        except ValueError:
            return DEFAULT_LEASE_SECONDS
        if parsed > 0:
            return parsed
    return DEFAULT_LEASE_SECONDS


def _acquire_lease(event: dict[str, Any], execution_name: str) -> tuple[bool, str, str]:
    """Attempt to take the online-write bracket with one atomic conditional write.

    Purpose
    -------
    Let exactly one execution own the write window at a time, so an execution that starts while
    another owns the bracket learns that it does not own it instead of taking a bracket it would
    later release under the first execution's feet.

    Parameters
    ----------
    event:
        Invocation payload, read for ``leaseSeconds``.
    execution_name:
        Name of the execution asking for the bracket, recorded as the durable owner.

    Returns
    -------
    tuple[bool, str, str]
        Whether the lease was acquired, the owner now recorded, and a refusal reason. The reason
        is empty on this edge because a refused acquisition is reported through the boolean.

    Raises
    ------
    ValueError
        If no execution name was supplied, since an unnamed owner cannot be verified at release.
    botocore.exceptions.ClientError
        For any service failure other than a refused condition.
    """
    # WHY : Assumptions: an acquisition REQUIRES a named execution. The owner is the only thing a
    # later release can be checked against, so a lease recording no owner is one that any caller
    # could clear -- which is the defect this rewrite exists to close, reintroduced by omission.
    if not execution_name:
        raise ValueError(
            "A quiesce call must supply executionName; the lease owner cannot be verified at "
            "release time without it"
        )

    now = int(time.time())
    expires_at = now + _lease_seconds(event)
    try:
        DYNAMODB.put_item(
            TableName=LEASE_TABLE_NAME,
            Item={
                LEASE_KEY_ATTRIBUTE: {"S": LEASE_KEY},
                LEASE_OWNER_ATTRIBUTE: {"S": execution_name},
                LEASE_EXPIRES_ATTRIBUTE: {"N": str(expires_at)},
                "acquiredAt": {"N": str(now)},
            },
            # WHY : Alternatives Considered: the condition admits an EXPIRED lease as well as an
            # absent one. Requiring absence alone was the simpler alternative and was rejected
            # because it makes one crashed execution block every subsequent night for ever -- the
            # bracket would need an operator to clear it by hand. Admitting an expired lease is
            # what makes the mechanism self-healing, and it is safe because the expiry the graph
            # supplies is the state machine's own timeout, so a lease can only be expired once its
            # owner can no longer be running.
            ConditionExpression=(
                f"attribute_not_exists({LEASE_KEY_ATTRIBUTE}) OR {LEASE_EXPIRES_ATTRIBUTE} < :now"
            ),
            ExpressionAttributeValues={":now": {"N": str(now)}},
        )
    # Re-raised below unless it is a refused condition, which is an expected outcome.
    except Exception as exc:
        if not _conditional_check_failed(exc):
            raise
        # WHY : Alternatives Considered: RAISING on a refused acquisition instead of reporting it.
        # Rejected because the state machine models refusal as data, not as an error: the graph
        # tests leaseAcquired for BooleanEquals true and routes a false to its own state. Raising
        # would collapse "another owner holds it" into the same failure shape as "the lease store
        # is unreachable", and the graph could no longer tell a contended night from a broken one.
        return False, _stored_lease_owner(), ""
    return True, execution_name, ""


def _release_lease(event: dict[str, Any]) -> tuple[str, str]:
    """Give up the online-write bracket, only for an owner that holds it or a lease that expired.

    Purpose
    -------
    Ensure a release can only clear the bracket the caller actually owns, so a chain that never
    acquired it -- or a watchdog firing for a different execution -- cannot open the write window
    while a real owner is still inside it.

    Parameters
    ----------
    event:
        Invocation payload, read for ``expectedLeaseOwner``.

    Returns
    -------
    tuple[str, str]
        The owner the caller claimed, and a refusal reason which is empty when the lease was
        released.

    Raises
    ------
    botocore.exceptions.ClientError
        For any service failure other than a refused condition.
    """
    # WHY : Refactoring Rationale: expectedLeaseOwner is now VERIFIED, where previously it could
    # only be recorded. The earlier code said so plainly -- the flag stores "true"/"false" and
    # nothing else, so no owner existed to compare against and the strongest check available was
    # "is the bracket still engaged". The lease item records the owner, so the comparison the
    # payload always implied is now the condition on the delete.
    # WHY : Assumptions: an ABSENT or EMPTY expectedLeaseOwner is REFUSED rather than treated as
    # "clear it unconditionally". That reading was what let the bracket-finalizer rule open the
    # write window while a healthy execution still held it. The rule does not need the unconditional
    # form: its input transformer already extracts the terminating execution's name from the event,
    # so it can name the owner it means to clear and be checked like every other caller.
    claimed_owner = str(event.get("expectedLeaseOwner", "")).strip()
    if not claimed_owner:
        return "", "caller named no lease owner"

    now = int(time.time())
    try:
        DYNAMODB.delete_item(
            TableName=LEASE_TABLE_NAME,
            Key={LEASE_KEY_ATTRIBUTE: {"S": LEASE_KEY}},
            # WHY : Trade-offs: an EXPIRED lease may be cleared by any named caller, not only by
            # its owner. The alternative -- owner match only -- was rejected for the same reason
            # the acquisition admits an expired lease: a crashed owner would otherwise leave a
            # bracket that only a human could clear. The cost is that a caller can clear a lease
            # it never held once that lease has expired, which is acceptable precisely because an
            # expired lease is one whose owner can no longer be running.
            ConditionExpression=(
                f"{LEASE_OWNER_ATTRIBUTE} = :owner OR {LEASE_EXPIRES_ATTRIBUTE} < :now"
            ),
            ExpressionAttributeValues={
                ":owner": {"S": claimed_owner},
                ":now": {"N": str(now)},
            },
        )
    # Re-raised below unless it is a refused condition, which is an expected outcome.
    except Exception as exc:
        if not _conditional_check_failed(exc):
            raise
        # WHY : Assumptions: a refused delete covers two cases and both must leave the flag alone.
        # Either another execution owns a live lease -- clearing the flag would open the write
        # window underneath it -- or no lease is stored at all, in which case there is no bracket
        # to give up. Reporting the refusal rather than raising keeps the resume edge able to run
        # on a night this execution never acquired anything, which is the normal case for the
        # in-graph release after a refused acquisition.
        stored_owner = _stored_lease_owner()
        reason = (
            f"lease is held by {stored_owner}" if stored_owner else "no lease is held"
        )
        return claimed_owner, reason
    return claimed_owner, ""


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

    # WHY : Refactoring Rationale: acquisition and release are now CONDITIONAL WRITES against a
    # durable lease item, replacing a read of the flag followed by a write of it. The earlier
    # shape could not be a lease and its own comment said so: two executions reading within the
    # same instant both concluded they had acquired, and the release edge had nothing to compare a
    # claimed owner against because the flag stores only "true"/"false". Both gaps are closed by
    # moving the LEASE into a store with a compare-and-set primitive while leaving the FLAG where
    # it is. The flag remains exactly what every online service reads -- a boolean at the same
    # parameter path -- and is now written as a CONSEQUENCE of the lease decision rather than
    # being the lease itself. No online service gains a second client or a second thing to read.
    if EXPECTED_ACTION == "quiesce":
        lease_acquired, lease_owner, release_refused_reason = _acquire_lease(event, execution_name)
        write_wanted = lease_acquired and not already_at_target
    else:
        lease_acquired = False
        lease_owner, release_refused_reason = _release_lease(event)
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
