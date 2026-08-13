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
``LEASE_TABLE_NAME``
    DynamoDB table holding the one lease item that arbitrates ownership of the
    write window.
``BATCH_TASK_CLUSTER_ARN``, ``BATCH_TASK_STARTED_BY``
    OPTIONAL, and read only by a release that has been asked to confirm the
    chain's ECS tasks are terminal -- the scheduled reconciler and the
    bracket-finalizer rule. The quiesce function shares this handler and never
    reads them, which is why they are optional rather than required. A release
    asked to confirm while either is blank REFUSES, because it cannot establish
    the fact it was told to establish.

Returns
-------
dict
    The action, the parameter written, the flag's resulting boolean state, the
    SSM version, the execution name, and the LEASE RECORD the batch state
    machine reads: ``leaseAcquired``, ``leaseOwner``, ``writePerformed``,
    ``leaseReleased``, ``reconciled`` and ``releaseRefusedReason``.

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
    Release edge only, and REQUIRED there. It must name the execution whose
    bracket is being given up; absent or empty is refused, because a release
    that names no owner is one any caller could use to open the write window
    under a running execution. All three release callers name it: the daily
    machine's two release states pass ``$.quiesce.leaseOwner``, and the
    bracket-finalizer rule passes the terminating execution's name from the
    event it fires on.
``executionName``
    REQUIRED on the quiesce edge, where it is recorded as the lease's
    ``leaseOwner``: a lease naming no owner is one any later caller could clear,
    so an unnamed acquisition is refused rather than stored. Optional on the
    release edge, which takes the owner it verifies from
    ``expectedLeaseOwner`` and uses this field only for correlation. On neither
    edge does it influence which parameter is written or the value written
    to it.
``leaseOwnerArn``
    Quiesce edge, optional. The ARN of the acquiring execution, stored beside
    the owner name so the scheduled reconciler can ask Step Functions whether
    that execution is still running. Absent on a hand-run acquisition, which is
    why the reconciler falls back to requiring expiry when it is missing.
``reconcile``
    Release edge only. Asks for a RECONCILING release: no owner is supplied,
    the owner is derived from the stored lease, and the release proceeds only
    once the owning execution and every task it started are terminal. Refused
    outright on the quiesce edge, where the key would otherwise be ignored and
    the invocation would take the bracket it was scheduled to give up.
``confirmTasksStopped``
    Release edge. Requires the chain's ECS tasks to be confirmed terminal
    before the lease is claimed. The bracket-finalizer rule sets it because it
    fires the instant an execution terminates, when a task abandoned by a
    timed-out synchronous state may still be writing. The in-graph release
    states do not, because the graph confirms its own tasks stopped before
    reaching them.

Raises
------
RuntimeError
    If required environment configuration is missing or malformed.
ValueError
    If the payload does not match this function's configured action, omits or
    contradicts ``readOnlyFlagParameter``, or omits ``executionName`` on the
    quiesce edge.
botocore.exceptions.BotoCoreError
    If the SSM write cannot be completed.

Notes
-----
The caller passes ``readOnlyFlagParameter`` alongside ``action`` on every
invocation. It is a CROSS-CHECK and never a redirect: this function writes
``PARAMETER_NAME`` from its own environment and refuses an invocation that
names anything else.

The write window's LOCK is a conditionally-written DynamoDB item, and the flag is
the boolean every online service reads. They are two records with two different
jobs: the lease arbitrates which execution owns the window, and the flag reports
whether writes are permitted. A quiesce takes the lease with one conditional
create and then writes the flag as the CONSEQUENCE of having taken it; a release
proves ownership of the lease, writes the flag, and only then gives the lease up.

Both edges are therefore RETRY-SAFE, which matters because each is invoked by a
state that retries its own Lambda faults, and because the daily machine's failure
path invokes the release a second time after a failed one:

* A quiesce whose flag write fails leaves the lease held by its own execution.
  The retry finds that lease, recognises itself as the owner, refreshes the
  expiry and completes the flag write, so the window is quiesced by the attempt
  that finishes rather than abandoned by the attempt that started.
* A release whose flag write fails leaves the lease still held, because the
  lease is deleted last. The retry re-proves the same ownership, completes the
  flag write and then deletes, so writes are re-enabled exactly once by whichever
  attempt gets through both operations.

Neither edge writes the flag on behalf of a caller that does not hold the lease:
a quiesce refused by another live owner writes nothing, and a release that cannot
prove ownership writes nothing and reports the reason as
``releaseRefusedReason``. Neither edge writes the flag when it already holds the
value being asked for, and a release still completes its delete in that case --
which is what makes a retry after a successful flag write and a failed delete
converge instead of stalling.

The bracket therefore has THREE release paths and they are ordered by how much
they can prove. The in-graph release states are the primary path and prove the
most: they run inside the execution that owns the lease, after the graph has
confirmed its own tasks stopped. The bracket-finalizer rule is the fast recovery
path for an execution that was terminated before reaching them, and it proves
ownership from the terminating execution's own name plus a task-terminality
check. The scheduled reconciler is the backstop for a bracket no event ever
released, and it proves the most laboriously: it reads the owner out of the lease
and requires both that the owning execution has stopped running and that no task
carrying the chain's startedBy marker is short of STOPPED. All three converge on
the same claim-write-delete sequence, so none of them is a second implementation
of the release.

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
#: have to name the same attributes the writes set, and a divergence between the two would
#: silently make a condition test an attribute nothing writes.
LEASE_KEY_ATTRIBUTE = "LeaseName"
LEASE_OWNER_ATTRIBUTE = "owner"
LEASE_EXPIRES_ATTRIBUTE = "expiresAt"

#: Attribute recording when a release was claimed. Diagnostic only, and deliberately kept when the
#: claim rewrites the item: an operator inspecting a lease that outlived its owner can tell an
#: execution that died mid-batch, which has no such attribute, from one that died mid-release.
LEASE_RELEASE_CLAIMED_ATTRIBUTE = "releaseClaimedAt"

#: Expression alias standing in for the owner attribute. Every expression that names the owner
#: uses this placeholder together with an ``ExpressionAttributeNames`` entry, never the attribute
#: name itself.
#: WHY : Assumptions: ``owner`` is one of DynamoDB's 573 RESERVED WORDS -- the published list is
#:   case-insensitive and carries OWNER between OVERRIDE and PAD -- so an expression that spells it
#:   literally is answered with ValidationException "Attribute name is a reserved keyword", not
#:   with a result. That code is not a refused condition, so :func:`_conditional_check_failed`
#:   reports False for it and the invocation raises: without the alias the release edge could never
#:   succeed against the real service, and no test could catch it, because the fake clients the
#:   tests use do not validate expressions.
#: WHY : Alternatives Considered: RENAMING the stored attribute to a word that is not reserved,
#:   such as ``leaseOwner``. Rejected because the attribute name is already published in the
#:   lease-record contract comment in infra/modules/step-functions-batch/main.tf and read by
#:   operators straight off a deployed table, so renaming would change a documented record to work
#:   around an expression-syntax rule. An alias changes only the expression.
#: WHY : Trade-offs: only the one name that needs an alias has one. LeaseName, expiresAt and
#:   releaseClaimedAt were each checked against the same reserved list and are absent from it, so
#:   aliasing them too would add indirection that buys nothing.
LEASE_OWNER_EXPRESSION_NAME = "#owner"

#: Seconds a lease is honoured for when the caller supplies no explicit duration. The graph passes
#: its own state-machine timeout, which is the longest a running execution can hold the bracket, so
#: this default is only reached by a hand-run invocation.
DEFAULT_LEASE_SECONDS = 7200

#: Seconds a lease is honoured for once its owner has CLAIMED it for release. The claim proves
#: ownership without giving the lease up, so the flag can be written while the bracket is still
#: demonstrably held, and this is how long that in-flight window lasts.
#: WHY : Assumptions: this is far shorter than DEFAULT_LEASE_SECONDS because every caller that
#:   claims has already finished the work the bracket protects. Both release states are the last
#:   states on their paths -- ResumeOnlineWrites continues to BatchSucceeded and
#:   ResumeOnlineWritesOnFailure ends the failure path -- and the bracket-finalizer rule fires only
#:   for an execution that has already terminated. Shortening the expiry at that moment is what
#:   stops a release that never completes from locking the next night out for whatever remained of
#:   the original two-hour lease.
#: WHY : Assumptions: the value does NOT have to cover the caller's own retry chain, because a
#:   retry is admitted by the OWNER clause of the claim condition rather than by expiry -- an
#:   expired claim is still re-claimable by the execution named on it. What it has to cover is one
#:   invocation's claim-write-delete sequence, so no other execution can take the bracket while
#:   this one is between the claim and the delete. Both functions are deployed with a 30-second
#:   Lambda timeout (infra/envs/dev/main.tf and infra/envs/prod/main.tf), so five minutes covers
#:   that sequence an order of magnitude over, and it is also the ceiling the graph already puts on
#:   one attempt of the release state (var.state_timeout_seconds.ResumeOnlineWrites defaults
#:   to 300).
RELEASE_CLAIM_SECONDS = 300

#: Attribute recording the ARN of the execution that took the lease. The owner attribute holds the
#: execution NAME, which is what every release names, but a name cannot be handed to
#: DescribeExecution -- so the reconciler needs the ARN as well.
#: WHY : Assumptions: it is written by the acquisition and read only by the reconciler, so a lease
#:   taken before this attribute existed, or by a hand-run invocation that omitted it, simply has
#:   no ARN. :func:`_reconcilable_lease_owner` treats that as "terminality cannot be proved from
#:   the execution" rather than as an error, and falls back to requiring expiry.
LEASE_OWNER_ARN_ATTRIBUTE = "leaseOwnerArn"

#: Cluster and startedBy marker identifying the batch tasks a stranded bracket may still have
#: running. Both are OPTIONAL at import, unlike every other setting in this module, because the
#: same handler serves the quiesce function -- which never reconciles and would fail at cold start
#: if these were required of it.
#: WHY : Trade-offs: reconciliation FAILS CLOSED when either is blank. Refusing to release is the
#:   safe direction: a bracket that stays engaged one cycle longer leaves online writes disabled,
#:   whereas a release granted without confirming tasks re-enables them underneath a posting
#:   container that is still writing.
RECONCILE_CLUSTER_ARN = os.environ.get("BATCH_TASK_CLUSTER_ARN", "").strip()
RECONCILE_TASK_STARTED_BY = os.environ.get("BATCH_TASK_STARTED_BY", "").strip()

#: Pages of task ARNs the residual-task check will read per desired status before giving up and
#: reporting the cluster unconfirmed.
#: WHY : Assumptions: one page is 100 task ARNs and the daily chain runs nine task states, so five
#:   pages is two orders of magnitude of headroom. The bound exists because an unbounded loop in a
#:   30-second function is a timeout, and because a cluster with 500 tasks carrying this marker is
#:   not one whose batch window can be declared quiet.
MAX_TASK_PAGES = 5

#: Lazily-created clients for the two services only the reconciler talks to.
#: WHY : Refactoring Rationale: these are not created at import beside the SSM and DynamoDB
#:   clients because the quiesce function shares this module and never reconciles; constructing
#:   them eagerly would add two client initialisations to every cold start of both functions to
#:   serve one scheduled caller of one of them.
_LAZY_CLIENTS: dict[str, Any] = {}


def _client(service: str) -> Any:
    """Return a memoised boto3 client for ``service``.

    Parameters
    ----------
    service:
        boto3 service name, ``stepfunctions`` or ``ecs``.

    Returns
    -------
    Any
        The client, created on first use and reused thereafter.

    Raises
    ------
    botocore.exceptions.BotoCoreError
        If the client cannot be constructed.
    """
    if service not in _LAZY_CLIENTS:
        _LAZY_CLIENTS[service] = boto3.client(service)
    return _LAZY_CLIENTS[service]


def _error_code(exc: BaseException) -> str:
    """Return the service error code carried by an SDK exception, or an empty string.

    Parameters
    ----------
    exc:
        Exception raised by an AWS SDK call.

    Returns
    -------
    str
        The ``Error.Code`` member when the exception carries one.

    Raises
    ------
    None
        Every lookup is defensive, because this runs while another exception is being handled.
    """
    response = getattr(exc, "response", None)
    if not isinstance(response, dict):
        return ""
    error = response.get("Error")
    if not isinstance(error, dict):
        return ""
    return str(error.get("Code", ""))


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
        The lookup is defensive, because this runs while another exception is already being
        handled and raising here would replace the original failure with a less informative one.
    """
    return _error_code(exc) == "ConditionalCheckFailedException"


def _stored_lease_item() -> dict[str, Any]:
    """Return the stored lease item in DynamoDB attribute-value form, or an empty mapping.

    Purpose
    -------
    Single-source the ONE strongly-consistent read of the lease, so the refusal paths that want
    only the owner and the reconciler that wants the owner, the expiry and the owning execution
    ARN cannot drift into two reads with two consistency choices.

    Returns
    -------
    dict[str, Any]
        The item as returned by DynamoDB, or ``{}`` when no lease is stored or it cannot be read.

    Raises
    ------
    None
        A read failure yields an empty mapping. Both callers treat that as "no lease", which is
        the fail-closed direction on each: a refused acquisition still reports its refusal, and the
        reconciler declines to release rather than releasing a bracket it could not read.
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
        return {}
    return stored if isinstance(stored, dict) else {}


def _lease_string(item: dict[str, Any], attribute: str) -> str:
    """Return a string attribute of a lease item, or an empty string when it is absent.

    Parameters
    ----------
    item:
        Lease item in DynamoDB attribute-value form.
    attribute:
        Attribute name to read.

    Returns
    -------
    str
        The stored value, or ``""`` when the attribute is absent or not a string.

    Raises
    ------
    None
        An unexpected shape yields an empty string, which every caller treats as absent.
    """
    value = item.get(attribute)
    return str(value.get("S", "")) if isinstance(value, dict) else ""


def _lease_number(item: dict[str, Any], attribute: str) -> int:
    """Return a numeric attribute of a lease item, or zero when it is absent or unparseable.

    Parameters
    ----------
    item:
        Lease item in DynamoDB attribute-value form.
    attribute:
        Attribute name to read.

    Returns
    -------
    int
        The stored value, or ``0`` when the attribute is absent, not numeric, or unparseable.

    Raises
    ------
    None
        Callers distinguish zero from a real value themselves; the reconciler treats a zero expiry
        as "no expiry recorded" and refuses, which is the fail-closed reading.
    """
    value = item.get(attribute)
    if not isinstance(value, dict):
        return 0
    try:
        return int(str(value.get("N", "0")))
    except ValueError:
        return 0


def _stored_lease_owner() -> str:
    """Return the owner recorded on the stored lease, or an empty string when none is stored.

    Purpose
    -------
    Report who holds the bracket after a conditional write was refused -- an acquisition that lost
    the race, or a release claim that could not prove ownership -- so an execution history records
    which execution it lost to rather than only that it lost.

    Returns
    -------
    str
        The recorded owner, or ``""`` when no lease is stored or it records no owner.

    Raises
    ------
    None
        A read failure yields an empty string, for the reason :func:`_stored_lease_item` records.
    """
    return _lease_string(_stored_lease_item(), LEASE_OWNER_ATTRIBUTE)


def _owning_execution_is_terminal(execution_arn: str) -> tuple[bool, str]:
    """Report whether the execution recorded on the lease has stopped running.

    Purpose
    -------
    Establish the first of the two facts a reconciling release needs: that the execution which took
    the bracket is no longer running, so releasing cannot re-enable online writes underneath a
    chain that is still working.

    Parameters
    ----------
    execution_arn:
        Execution ARN recorded on the lease, or ``""`` when none was recorded.

    Returns
    -------
    tuple[bool, str]
        Whether the execution is known to be terminal, and a refusal reason when it is not.

    Raises
    ------
    None
        A status that cannot be read is reported as NOT terminal rather than raised, because the
        caller's only use for the answer is deciding whether to release, and an unreadable status
        must decide that the same way a running execution does.
    """
    if not execution_arn:
        return False, "stored lease records no owning execution to verify"

    try:
        described = _client("stepfunctions").describe_execution(executionArn=execution_arn)
        status = str(described.get("status", "")).strip()
    except (ClientError, BotoCoreError) as exc:
        code = _error_code(exc)
        # WHY : Assumptions: an execution the service cannot find is terminal, not unknown. A
        # STANDARD execution's history is retained for 90 days, so ExecutionDoesNotExist here
        # means the ARN belongs to an execution older than that retention or to a machine that
        # has been replaced -- in either case it demonstrably is not running, and treating it as
        # unknown would strand the bracket for ever with no path to release it.
        if code in {"ExecutionDoesNotExist", "ResourceNotFoundException"}:
            return True, ""
        return False, f"owning execution status could not be read ({code or 'service error'})"

    # WHY : Assumptions: RUNNING is the only non-terminal status a Step Functions execution has;
    # SUCCEEDED, FAILED, TIMED_OUT, ABORTED and PENDING_REDRIVE all mean no state is executing.
    # PENDING_REDRIVE is included in the terminal set deliberately: it is a failed execution
    # awaiting an operator's redrive, and a redrive re-enters the graph at the failed state, which
    # re-acquires the lease through the ordinary quiesce path.
    if status == "RUNNING":
        return False, "owning execution is still RUNNING"
    return True, ""


def _list_task_arns(desired_status: str) -> tuple[list[str], bool]:
    """List the batch chain's task ARNs in one desired status.

    Parameters
    ----------
    desired_status:
        ``RUNNING`` or ``STOPPED``, passed through to ECS as the desired-status filter.

    Returns
    -------
    tuple[list[str], bool]
        The task ARNs found, and whether the listing was truncated at
        :data:`MAX_TASK_PAGES`.

    Raises
    ------
    botocore.exceptions.ClientError
        If ECS refuses the call. The caller converts that into a refusal.
    botocore.exceptions.BotoCoreError
        If the call cannot be made.
    """
    ecs = _client("ecs")
    arns: list[str] = []
    token: str | None = None
    for _ in range(MAX_TASK_PAGES):
        request = {
            "cluster": RECONCILE_CLUSTER_ARN,
            "startedBy": RECONCILE_TASK_STARTED_BY,
            "desiredStatus": desired_status,
        }
        if token:
            request["nextToken"] = token
        page = ecs.list_tasks(**request)
        arns.extend(str(arn) for arn in page.get("taskArns") or [])
        token = page.get("nextToken") or None
        if not token:
            return arns, False
    return arns, True


def _residual_batch_tasks_unconfirmed() -> tuple[bool, str]:
    """Report whether any batch task the chain started might still be running.

    Purpose
    -------
    Establish the second fact a reconciling or finalizing release needs. An ECS task started by a
    synchronous run-task state OUTLIVES the state that started it when that state times out or its
    execution is aborted -- Step Functions stops waiting, the container does not stop -- so an
    execution being terminal does not by itself mean its writers are.

    Returns
    -------
    tuple[bool, str]
        Whether terminality is UNCONFIRMED, and the reason when it is.

    Raises
    ------
    None
        Every failure path reports unconfirmed rather than raising, so an unreadable cluster
        withholds the release instead of failing the invocation that would have withheld it anyway.
    """
    if not RECONCILE_CLUSTER_ARN or not RECONCILE_TASK_STARTED_BY:
        return True, (
            "batch task terminality cannot be confirmed without BATCH_TASK_CLUSTER_ARN and "
            "BATCH_TASK_STARTED_BY"
        )

    try:
        running, running_truncated = _list_task_arns("RUNNING")
        if running:
            return True, f"{len(running)} batch task(s) are still desired-RUNNING"
        if running_truncated:
            return True, "the running-task listing was truncated"

        # WHY : Assumptions: a desired-STOPPED task is not necessarily a stopped one. StopTask
        # records intent; ECS then sends SIGTERM, waits out the container stop timeout and only
        # then SIGKILLs, so a container that is still flushing a transaction appears here with
        # desiredStatus STOPPED and lastStatus RUNNING or DEACTIVATING. Reading lastStatus is what
        # turns "we asked it to stop" into "it has stopped".
        stopped, stopped_truncated = _list_task_arns("STOPPED")
        if stopped_truncated:
            return True, "the stopped-task listing was truncated"
        if stopped:
            described = _client("ecs").describe_tasks(
                cluster=RECONCILE_CLUSTER_ARN,
                tasks=stopped[:100],
            )
            unstopped = [
                task
                for task in described.get("tasks") or []
                if str(task.get("lastStatus", "")).upper() != "STOPPED"
            ]
            if unstopped:
                return True, f"{len(unstopped)} batch task(s) have not reached STOPPED"
    except (ClientError, BotoCoreError) as exc:
        return True, f"batch task status could not be read ({_error_code(exc) or 'service error'})"

    return False, ""


def _reconcilable_lease_owner() -> tuple[str, str]:
    """Decide whether a stranded bracket may be released, and on whose behalf.

    Purpose
    -------
    Implement the scheduled watchdog's decision. It runs with no owner supplied -- nobody is left
    to name one -- so it derives the owner from the stored lease and releases only once BOTH the
    owning execution and the tasks that execution started are demonstrably terminal.

    Returns
    -------
    tuple[str, str]
        The owner the release should name, and a refusal reason which is empty when the release may
        proceed. The owner is returned even on a refusal, so the invocation record names the lease
        that was inspected.

    Raises
    ------
    None
        Every uncertain answer is a refusal, for the reason the two checks above record.
    """
    item = _stored_lease_item()
    if not item:
        return "", "no lease is held"

    owner = _lease_string(item, LEASE_OWNER_ATTRIBUTE)
    if not owner:
        return "", "stored lease records no owner"

    expires_at = _lease_number(item, LEASE_EXPIRES_ATTRIBUTE)
    if expires_at <= 0:
        return owner, "stored lease records no expiry"

    execution_arn = _lease_string(item, LEASE_OWNER_ARN_ATTRIBUTE)
    expired = expires_at < int(time.time())

    # WHY : Refactoring Rationale: the execution check is attempted whether or not the lease has
    # expired, and a terminal execution is sufficient on its own. Requiring expiry as well was the
    # narrower alternative and was rejected because it makes the watchdog slow exactly when it
    # matters: an ABORTED execution's lease carries the state machine's whole timeout as its
    # expiry, so a bracket left by an operator stopping the chain would keep online writes
    # disabled for the remainder of that window even though nothing was running. Proving the
    # execution terminal is a STRONGER fact than expiry, not a weaker one.
    # WHY : Assumptions: expiry remains the fallback for a lease with no execution ARN -- one taken
    # by a hand-run invocation. Such a lease cannot be verified against Step Functions at all, so
    # an unexpired one is left alone and an expired one is released on the strength of the task
    # check, which is the behaviour this watchdog was asked for.
    if execution_arn:
        terminal, reason = _owning_execution_is_terminal(execution_arn)
        if not terminal:
            return owner, reason
    elif not expired:
        return owner, "lease has not expired and records no owning execution to verify"

    unconfirmed, reason = _residual_batch_tasks_unconfirmed()
    if unconfirmed:
        return owner, reason
    return owner, ""


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
    later release under the first execution's feet. The write is IDEMPOTENT for the owner: a
    second call from the execution already recorded on the lease renews it and succeeds, so a
    retried invocation completes the window its first attempt opened.

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
        Acquisition is reported as True when this execution took the lease, when it took over an
        expired one, and when it already held it -- the three cases in which the caller may go on
        to quiesce the flag.

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
    # WHY : Assumptions: the owning execution's ARN is stored ALONGSIDE its name rather than
    # instead of it. The name is what every release names and what an operator reads; the ARN is
    # the only form DescribeExecution accepts, and the scheduled reconciler needs it to establish
    # that a lease outliving its owner outlived a TERMINAL owner. Deriving one from the other was
    # the alternative and would put a state-machine ARN and a name-mangling rule in this function.
    # WHY : Trade-offs: it is written when the caller supplies it and omitted otherwise, rather
    # than being required like the name. A hand-run invocation has no execution ARN to give, and
    # refusing one would make the bracket impossible to take by hand during an incident; the cost
    # is that such a lease can only be reconciled after it expires, which the reconciler records.
    lease_owner_arn = str(event.get("leaseOwnerArn", "")).strip()
    item: dict[str, Any] = {
        LEASE_KEY_ATTRIBUTE: {"S": LEASE_KEY},
        LEASE_OWNER_ATTRIBUTE: {"S": execution_name},
        LEASE_EXPIRES_ATTRIBUTE: {"N": str(expires_at)},
        "acquiredAt": {"N": str(now)},
    }
    if lease_owner_arn:
        item[LEASE_OWNER_ARN_ATTRIBUTE] = {"S": lease_owner_arn}
    try:
        DYNAMODB.put_item(
            TableName=LEASE_TABLE_NAME,
            Item=item,
            # WHY : Alternatives Considered: the condition admits an EXPIRED lease as well as an
            # absent one. Requiring absence alone was the simpler alternative and was rejected
            # because it makes one crashed execution block every subsequent night for ever -- the
            # bracket would need an operator to clear it by hand. Admitting an expired lease is
            # what makes the mechanism self-healing, and it is safe because the expiry the graph
            # supplies is the state machine's own timeout, so a lease can only be expired once its
            # owner can no longer be running.
            # WHY : Refactoring Rationale: the third clause admits a lease this SAME execution
            # already holds, and without it the acquisition was not idempotent. The quiesce state
            # retries its own Lambda faults, and a fault the platform reports after the write
            # landed -- a lost response, a throttle on the way back, an attempt the state timed out
            # -- leaves a lease owned by this execution and a flag not yet written. The retry then
            # met a condition that could only fail, reported leaseAcquired false naming ITSELF as
            # the holder, and CheckQuiesceLeaseAcquired routed it to OnlineWriteLeaseUnavailable:
            # an execution deadlocked against its own lease, with online writes still enabled and
            # the bracket held until it expired. Admitting the owner turns the retry into the
            # completion of the first attempt.
            # WHY : Trade-offs: admitting the owner also REFRESHES the expiry, because the item is
            # written whole. That is the wanted behaviour on this edge -- the retry is the start of
            # the window in practice -- and it cannot extend one execution's hold at another's
            # expense, since the clause matches only a lease recording this same execution name.
            ConditionExpression=(
                f"attribute_not_exists({LEASE_KEY_ATTRIBUTE}) "
                f"OR {LEASE_EXPIRES_ATTRIBUTE} < :now "
                f"OR {LEASE_OWNER_EXPRESSION_NAME} = :owner"
            ),
            ExpressionAttributeNames={LEASE_OWNER_EXPRESSION_NAME: LEASE_OWNER_ATTRIBUTE},
            ExpressionAttributeValues={
                ":now": {"N": str(now)},
                ":owner": {"S": execution_name},
            },
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
        # WHY : Assumptions: reaching here means a LIVE lease held by a DIFFERENT execution, since
        # the condition above admits an absent lease, an expired one and this execution's own. The
        # owner reported back therefore can never be the caller itself, so an operator reading
        # leaseOwner on a refusal is always reading the run that has to finish first.
        return False, _stored_lease_owner(), ""
    return True, execution_name, ""


def _claim_lease_release(event: dict[str, Any]) -> tuple[str, str]:
    """Prove a caller may give up the online-write bracket, without giving it up yet.

    Purpose
    -------
    Take the release's ownership decision BEFORE the flag is written, and record the claim on the
    lease itself, so the flag is written while the bracket is still demonstrably held and the lease
    is still there to be found if that write fails. A caller that cannot prove ownership is refused
    here and never reaches the write; a caller that can is protected from a takeover for
    :data:`RELEASE_CLAIM_SECONDS` while it writes. :func:`_complete_lease_release` finishes what
    this starts.

    Parameters
    ----------
    event:
        Invocation payload, read for ``expectedLeaseOwner``.

    Returns
    -------
    tuple[str, str]
        The owner the caller claimed, and a refusal reason which is empty when the claim held -- in
        which case the caller may write the flag and then complete the release.

    Raises
    ------
    botocore.exceptions.ClientError
        For any service failure other than a refused condition.
    """
    # WHY : Refactoring Rationale: expectedLeaseOwner is now VERIFIED, where previously it could
    # only be recorded. The earlier code said so plainly -- the flag stores "true"/"false" and
    # nothing else, so no owner existed to compare against and the strongest check available was
    # "is the bracket still engaged". The lease item records the owner, so the comparison the
    # payload always implied is now the condition on this claim, and the delete that follows the
    # flag write re-asserts it.
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
        # WHY : Alternatives Considered: writing the item WHOLE with put_item, which is what the
        # acquisition does. Rejected because a claim must not invent the attributes it does not
        # know: put_item replaces the item, so acquiredAt -- the only record of when the bracket
        # was taken -- would be dropped at exactly the moment an operator investigating a stuck
        # release wants it. Reading the item first and re-writing it would reintroduce the
        # read-then-write race that the conditional write exists to remove, so the claim mutates
        # the three attributes it owns and leaves the rest alone.
        DYNAMODB.update_item(
            TableName=LEASE_TABLE_NAME,
            Key={LEASE_KEY_ATTRIBUTE: {"S": LEASE_KEY}},
            # WHY : Assumptions: the claim SETS the owner as well as the expiry, even though the
            # condition has just matched on it. The two differ in the takeover case: when the
            # condition is satisfied by expiry rather than by ownership, the item still names the
            # execution that let it lapse, and a caller whose own claim later lapsed would then be
            # refused by both clauses. Writing the owner makes the claimant the owner, so its
            # retries are admitted by the owner clause however long the flag write takes.
            UpdateExpression=(
                f"SET {LEASE_EXPIRES_ATTRIBUTE} = :expires, "
                f"{LEASE_RELEASE_CLAIMED_ATTRIBUTE} = :now, "
                f"{LEASE_OWNER_EXPRESSION_NAME} = :owner"
            ),
            # WHY : Trade-offs: an EXPIRED lease may be claimed by any named caller, not only by
            # its owner. The alternative -- owner match only -- was rejected for the same reason
            # the acquisition admits an expired lease: a crashed owner would otherwise leave a
            # bracket that only a human could clear. The cost is that a caller can claim a lease
            # it never held once that lease has expired, which is acceptable precisely because an
            # expired lease is one whose owner can no longer be running.
            # WHY : Assumptions: an item that does not exist fails BOTH clauses -- there is no
            # owner to match and no expiry to have passed -- so a claim never creates a lease. That
            # matters because update_item would otherwise upsert one, and a release that invented
            # the bracket it was about to give up would report success having proved nothing.
            ConditionExpression=(
                f"{LEASE_OWNER_EXPRESSION_NAME} = :owner OR {LEASE_EXPIRES_ATTRIBUTE} < :now"
            ),
            ExpressionAttributeNames={LEASE_OWNER_EXPRESSION_NAME: LEASE_OWNER_ATTRIBUTE},
            ExpressionAttributeValues={
                ":owner": {"S": claimed_owner},
                ":expires": {"N": str(now + RELEASE_CLAIM_SECONDS)},
                ":now": {"N": str(now)},
            },
        )
    # Re-raised below unless it is a refused condition, which is an expected outcome.
    except Exception as exc:
        if not _conditional_check_failed(exc):
            raise
        # WHY : Assumptions: a refused claim covers two cases and both must leave the flag alone.
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


def _complete_lease_release(claimed_owner: str) -> bool:
    """Delete a lease whose release this caller has already claimed and whose flag it has written.

    Purpose
    -------
    Finish the release, after the flag has been written, so the bracket is only given up once the
    window it protected is provably open. Being the LAST write of the release is what makes the edge
    safe to retry as a whole: the claim before it is idempotent for the owner and the flag write is
    idempotent by value, so a retry re-proves ownership, finds or makes the flag correct, and
    reaches this delete.

    Parameters
    ----------
    claimed_owner:
        Owner returned by :func:`_claim_lease_release`, re-asserted as this delete's condition.

    Returns
    -------
    bool
        True when the lease was deleted, False when the delete was refused because the lease is no
        longer this caller's to delete.

    Raises
    ------
    botocore.exceptions.ClientError
        For any service failure other than a refused condition.
    """
    now = int(time.time())
    try:
        DYNAMODB.delete_item(
            TableName=LEASE_TABLE_NAME,
            Key={LEASE_KEY_ATTRIBUTE: {"S": LEASE_KEY}},
            # WHY : Assumptions: the condition is re-asserted rather than assumed from the claim,
            # because the claim's protection is bounded by RELEASE_CLAIM_SECONDS and the flag write
            # sits between the two. An unconditional delete would be a delete of whatever lease
            # happens to be stored now, which on a flag write slower than the claim window means
            # deleting the lease of the execution that has since taken the bracket.
            # WHY : Trade-offs: the expiry clause is kept so a claim that lapsed mid-write can still
            # be completed. It is a completion allowance, not an authorization: if another execution
            # took the lease in that gap, the owner clause fails against its name and the expiry
            # clause fails against its fresh expiry, so the delete is refused and its bracket
            # survives. In the deployed graph the gap cannot open at all -- RELEASE_CLAIM_SECONDS is
            # 300 and both functions run under a 30-second Lambda timeout -- so this clause is
            # insurance against a caller that does not share those bounds rather than a path the
            # nightly chain takes.
            ConditionExpression=(
                f"{LEASE_OWNER_EXPRESSION_NAME} = :owner OR {LEASE_EXPIRES_ATTRIBUTE} < :now"
            ),
            ExpressionAttributeNames={LEASE_OWNER_EXPRESSION_NAME: LEASE_OWNER_ATTRIBUTE},
            ExpressionAttributeValues={
                ":owner": {"S": claimed_owner},
                ":now": {"N": str(now)},
            },
        )
    # Re-raised below unless it is a refused condition, which is an expected outcome.
    except Exception as exc:
        if not _conditional_check_failed(exc):
            raise
        # WHY : Trade-offs: a refused delete is REPORTED, not raised, and the caller logs it as a
        # warning. Raising would fail an invocation whose whole purpose -- putting the flag every
        # online service reads into its target state -- has already succeeded, and on the resume
        # edge that means failing an execution that has just re-enabled online writes. What is left
        # behind is one lease item that expires on its own, which the acquisition's expired-lease
        # clause already treats as available.
        return False
    return True


def _payload_flag(event: dict[str, Any], name: str) -> bool:
    """Read a boolean payload member that may arrive as a JSON boolean or as a string.

    Parameters
    ----------
    event:
        Invocation payload.
    name:
        Member to read.

    Returns
    -------
    bool
        True when the member is the boolean ``True`` or a string spelling ``true``.

    Raises
    ------
    None
        Any other value is False. An unrecognised value must not enable a mode, which is why the
        check is a whitelist of affirmatives rather than Python truthiness -- the string ``"false"``
        is truthy and would otherwise switch the mode on.
    """
    value = event.get(name)
    if isinstance(value, bool):
        return value
    return isinstance(value, str) and value.strip().lower() == "true"


def _release_subject(event: dict[str, Any]) -> tuple[dict[str, Any], bool, str]:
    """Decide which lease a release names, and whether it may be attempted at all.

    Purpose
    -------
    Fold the three ways the bracket is given up into ONE claim. An in-graph release names its own
    owner and needs no further proof, because the graph confirmed its tasks stopped before reaching
    the release. The bracket-finalizer rule names the terminating execution but fires the instant
    that execution ended, so its tasks may still be draining and it asks for them to be confirmed.
    The scheduled reconciler names nobody, so the owner is derived from the stored lease and both
    terminality facts are required. After this function returns, all three are the same claim.

    Parameters
    ----------
    event:
        Invocation payload, read for ``reconcile`` and ``confirmTasksStopped``.

    Returns
    -------
    tuple[dict[str, Any], bool, str]
        The payload the claim should be made with, whether reconciliation was requested, and a
        refusal reason that stops the release before the claim when it is non-empty.

    Raises
    ------
    None
        Every check that cannot be completed becomes a refusal, which withholds the release.
    """
    if _payload_flag(event, "reconcile"):
        owner, refusal = _reconcilable_lease_owner()
        # WHY : Assumptions: the discovered owner is written into a COPY of the payload and the
        # ordinary claim then runs unchanged. That is deliberate: it makes the reconciling release
        # provably identical to an in-graph one from the claim onward, so the conditional-write
        # ordering that makes the release retry-safe has exactly one implementation rather than a
        # second one written for the watchdog.
        return {**event, "expectedLeaseOwner": owner}, True, refusal

    if _payload_flag(event, "confirmTasksStopped"):
        unconfirmed, reason = _residual_batch_tasks_unconfirmed()
        if unconfirmed:
            return event, False, reason

    return event, False, ""


def handler(event: dict[str, Any], context: Any) -> dict[str, Any]:
    """Apply the configured online-write state.

    Parameters
    ----------
    event:
        Step Functions or EventBridge payload carrying ``action`` and
        ``readOnlyFlagParameter``, plus ``executionName`` and the optional
        ``leaseOwnerArn`` on the quiesce edge, and on the release edge either
        ``expectedLeaseOwner`` or the ``reconcile`` flag that derives it, with
        ``confirmTasksStopped`` where the caller cannot vouch for the chain's
        tasks itself.
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
        If the event action is missing or targets the other function, if
        ``readOnlyFlagParameter`` is missing or names a different parameter, if
        ``executionName`` is omitted on the quiesce edge, or if a ``reconcile``
        payload is sent to the quiesce function.
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

    # WHY : Assumptions: a reconcile payload is refused on the quiesce edge rather than ignored.
    # The key only means anything to a release, so a watchdog invocation misdirected at the quiesce
    # function would otherwise TAKE the bracket it was scheduled to give up -- disabling online
    # writes on a cadence, silently, with the payload's intent visible in the history and inverted
    # in effect. Refusing costs one comparison and turns that into a named error.
    if _payload_flag(event, "reconcile") and EXPECTED_ACTION != "resume":
        raise ValueError(
            "A reconcile payload may only be sent to the resume function; this function is "
            f"configured for {EXPECTED_ACTION!r}"
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
    # WHY : Refactoring Rationale: on the release edge the bracket is now given up in TWO writes
    # BRACKETING the flag write -- claim, write, delete -- where the lease used to be deleted first.
    # The old order surrendered the bracket before the window was open, so any failure of the flag
    # write left online writes disabled with no lease left to prove who was entitled to enable
    # them, and the graph's own recovery made that concrete rather than theoretical:
    # ResumeOnlineWrites catches into NotifyFailure, which routes to CheckQuiesceLeaseOwnership and
    # on to ResumeOnlineWritesOnFailure with the SAME expectedLeaseOwner. That second invocation
    # found no lease, was refused with "no lease is held", skipped the write on the strength of the
    # refusal and returned SUCCESS -- an execution reporting its bracket closed while every online
    # service was still read-only, needing a human to put the flag back by hand. Claiming first and
    # deleting last makes the retry re-prove ownership and finish the job.
    if EXPECTED_ACTION == "quiesce":
        lease_acquired, lease_owner, release_refused_reason = _acquire_lease(event, execution_name)
        release_claimed = False
        reconciled = False
        write_wanted = lease_acquired and not already_at_target
    else:
        lease_acquired = False
        release_event, reconcile_requested, release_refused_reason = _release_subject(event)
        if release_refused_reason:
            lease_owner = str(release_event.get("expectedLeaseOwner", "")).strip()
            release_claimed = False
        else:
            lease_owner, release_refused_reason = _claim_lease_release(release_event)
            release_claimed = not release_refused_reason
        reconciled = reconcile_requested and release_claimed
        write_wanted = release_claimed and not already_at_target

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

    # WHY : Refactoring Rationale: the delete runs on EVERY successful claim, deliberately including
    # the case where the flag needed no write. Gating it on write_wanted instead would stall exactly
    # the retry this ordering exists to serve: an attempt that wrote the flag and then failed before
    # the delete comes back to a flag already at target, so write_wanted is false, and the lease
    # would then never be cleared by the only caller entitled to clear it. Because the flag write is
    # idempotent by value and this delete is conditional, running it on the claim converges whether
    # the previous attempt got as far as the flag or not.
    lease_released = False
    if release_claimed:
        lease_released = _complete_lease_release(lease_owner)
        if not lease_released:
            # WHY : Assumptions: this is logged at WARNING rather than raised because the flag --
            # the only thing an online service reads -- is already in its target state, so the
            # outcome the caller asked for has been achieved and failing here would report the
            # opposite. The residue is a lease item nobody owns any more, which expires on its own;
            # the warning is what lets an operator correlate that item with the execution that left
            # it.
            LOGGER.warning(
                "event=online_write_lease_release_incomplete parameter=%s leaseOwner=%s "
                "enabled=%s reason=lease_no_longer_deletable_by_claimant",
                PARAMETER_NAME,
                lease_owner or "none",
                effective_value or "unset",
            )

    LOGGER.info(
        "event=online_write_gate_updated action=%s enabled=%s written=%s parameter=%s "
        "leaseAcquired=%s leaseOwner=%s leaseReleased=%s reconciled=%s refused=%s execution=%s",
        action,
        effective_value or "unset",
        write_wanted,
        PARAMETER_NAME,
        lease_acquired,
        lease_owner or "none",
        lease_released,
        reconciled,
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
    # WHY : Assumptions: leaseReleased reports whether the lease was actually DELETED, which
    # writePerformed cannot stand in for. The two diverge in exactly the case this ordering
    # exists to serve -- a retry that finds the flag already correct performs no write and
    # still completes the delete -- so a reader of the execution history needs both to tell a
    # release that finished from one that only wrote. It is reported on the quiesce edge too,
    # as a constant false, because a member that appears on one edge and not the other cannot
    # be addressed by a reference path that does not know which edge produced the result.
    # WHY : Assumptions: leaseRetainedReason answers the OPPOSITE question to
    # releaseRefusedReason and is reported separately from it, because the two describe states an
    # operator must not confuse. A refusal means the flag was deliberately left alone -- this caller
    # does not own the bracket -- while a retained lease means the flag WAS restored and only the
    # bookkeeping outlived it. Folding them together would leave onlineWritesEnabled as the only way
    # to tell a stranded window from a refused one, which is the ambiguity the strand hid behind.
    # WHY : Assumptions: it is DERIVED from the state above rather than re-deciding the outcome, so
    # it cannot disagree with leaseReleased. It is empty whenever nothing is outstanding -- the
    # quiesce edge, a refused release, and a release whose delete completed -- and names the cause
    # only when this caller claimed the release and the delete did not complete.
    lease_retained_reason = ""
    if release_claimed and not lease_released:
        lease_retained_reason = "release delete refused; the lease expires on its own"

    return {
        "action": action,
        "readOnlyFlagParameter": PARAMETER_NAME,
        "onlineWritesEnabled": effective_value == "true",
        "parameterVersion": parameter_version,
        "executionName": execution_name,
        "leaseAcquired": lease_acquired,
        "leaseOwner": lease_owner,
        "writePerformed": write_wanted,
        "leaseReleased": lease_released,
        "reconciled": reconciled,
        "releaseRefusedReason": release_refused_reason,
        "leaseRetainedReason": lease_retained_reason,
    }
