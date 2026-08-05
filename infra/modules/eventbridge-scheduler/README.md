# EventBridge Scheduler module

This reusable module provisions the **nightly trigger** for the migrated CardDemo
batch chain, and nothing else: one EventBridge Scheduler schedule, inside its own
schedule group, whose single target is `states:StartExecution` on the
`carddemo-daily-batch` Step Functions state machine; the least-privilege IAM role
that schedule assumes; and the dead-letter target that captures an invocation the
scheduler could not deliver.

Its source of truth is twofold. The behavioural lineage is the pair of retired
mainframe scheduler definitions, `app/scheduler/CardDemo.ca7` and
`app/scheduler/CardDemo.controlm`, from which this module carries the **intent**
and deliberately not the syntax; both files are reference-only and are never
modified. The resource contract is the four `.tf` files beside this document, and
the tables in [§11](#11-terraform-reference) are generated from them rather than
written by hand.

This README is the **prose half of Rule 1 Explainability**. HCL has no docstring
construct, so the obligation is split in two: the file-header blocks, the
`description` on every variable and output, and the per-argument why-comments live
in the HCL and are linted by [TFLint](../../.tflint.hcl), while the purpose and
the reasoning a reader cannot recover from the code live here. The generated
tables are kept honest against the HCL by
[terraform-docs](../../.terraform-docs.yml), and the whole convention is defined
in the [documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md).

## 1. What this module provisions

| Resource | Role in the trigger |
|---|---|
| `aws_scheduler_schedule_group.this` | The named group the schedule belongs to. Functional rather than organisational — see [§5](#5-design-decisions) |
| `aws_scheduler_schedule.this` | The schedule itself: cron expression, timezone, state, flexible-time-window setting, retry policy, dead-letter target and the templated Step Functions target |
| `aws_iam_role.this` | The execution role the scheduler assumes, trusted only by the scheduler service principal and only on behalf of the group above |
| `aws_iam_role_policy.this` | That role's one permissions policy, enumerating every action it may take |

Five data sources support them: the partition, region and caller identity used to
compose the group ARN, and the two IAM policy documents. Its authored contract
has exactly **five files** — `versions.tf`, `variables.tf`, `main.tf`,
`outputs.tf` and this README.

The authority for the shape is the target architecture's batch-orchestration
design — a nightly cron schedule with a dead-letter target — and decision **D5**,
recorded in
[ADR-005](../../../docs/adr/ADR-005-batch-orchestration.md). That decision applies
the project's guiding principle, *managed orchestration over a self-managed
scheduler*: the calendar is handed to a managed service, and no self-managed
scheduler is provisioned anywhere in this tree.

## 2. Module boundary and usage

**This directory is a MODULE, not a root.** It is never applied on its own. It
declares no `provider`, no `backend` and no `terraform` state configuration, and
it calls **no sibling module**. It is validated transitively whenever
`infra/envs/dev` or `infra/envs/prod` is validated, and the two ARNs it cannot
work without arrive as plain input variables from that environment root.

```hcl
# WHAT: instantiate the nightly trigger from an environment root.
# WHY : Assumptions: the environment root is the only layer that can see both the
#       state machine and the queue, so it is the only place the two producer
#       edges can be wired without one module reaching into another. Both values
#       are module outputs rather than literals, which is what keeps every
#       account identifier and region out of this repository.
module "eventbridge_scheduler" {
  source = "../../modules/eventbridge-scheduler"

  environment       = var.environment
  state_machine_arn = module.step_functions.daily_state_machine_arn
  dead_letter_arn   = module.sqs.error_queue_arn

  # Optional. Shown to make the two conjoined retry settings visible together;
  # both already default to the values below.
  maximum_retry_attempts       = 5
  maximum_event_age_in_seconds = 86400

  tags = local.common_tags
}
```

The module-output references above are the same edges wired by the dev and prod
roots; their contracts come from
[`infra/modules/step-functions-batch`](../step-functions-batch/README.md) and
[`infra/modules/sqs`](../sqs/README.md). **No environment-specific ARN, account
identifier, queue URL or AWS region literal appears anywhere in this module or in
this document**, and neither required ARN carries a default, precisely because
that would embed one environment's identity in reusable code. `environment` also
has no default for a different reason: defaulting it to `dev` would let a
production root with a missing argument plan successfully under the wrong name.

## 3. Baseline intent — the retired scheduler definitions

The CA-7 and Control-M definitions are **retired as syntax**, and their intent is
carried by this module. Because the syntax is gone, this section is where that
intent is recorded. Every figure below was measured directly from the
reference-only source rather than estimated, and each is reproducible with the
commands in [§8](#8-gates).

### 3.1 Control-M: five containers, three of them bracketed

`app/scheduler/CardDemo.controlm` is a 92-line `DEFTABLE` holding **five**
schedule containers — three `FOLDER` and two `SMART_FOLDER` nodes — with
fifteen job nodes between them:

| Container | Lines | Job chain |
|---|---|---|
| `FOLDER "DAILY-TransactionBackup"` | 3–25 | `CLOSEFIL` (4) → `TRANBKP` (8) → `WAITSTEP` (14) → `OPENFIL` (20) |
| `FOLDER "WEEKLY-TransactionTypesDBRefresh"` | 26–31 | `MNTTRDB2` (27) |
| `SMART_FOLDER "WEEKLY-DisclosureGroupsRefresh"` | 32–56 | `CLOSEFIL` (33) → `DISCGRP` (38) → `WAITSTEP` (44) → `OPENFIL` (50) |
| `SMART_FOLDER "WEEKLY-TransactionTypesDBRefresh"` | 57–63 | `TRANEXTR` (58) |
| `FOLDER "MONTHLY-InterestCalculation"` | 64–92 | `CLOSEFIL` (65) → `INTCALC` (69) → `COMBTRAN` (75) → `WAITSTEP` (81) → `OPENFIL` (87) |

**Assumptions:** the count is five and not four, which matters because the two
`SMART_FOLDER` nodes are the only carriers of the weekly cadence attributes in
[§3.2](#32-verified-attribute-census) and are therefore easy to overlook when
reading the file as a list of jobs. `variables.tf` cites the same two nodes at
lines 32 and 57.

Job-to-job sequencing inside a container is expressed by `INCOND`/`OUTCOND`
tokens: a completing job posts a token with `SIGN="+"`, its successor requires
that token, and the token is then withdrawn with `SIGN="-"`. That is a linear DAG
of prerequisites, which is what a state machine expresses — see
[§4](#4-the-quiesce-boundary). **No input in this module relates to step
ordering or to condition codes.**

### 3.2 Verified attribute census

| Attribute | Count | Where it appears | Carried across as |
|---|---|---|---|
| `TIMETO="23:00"` | 15 | every real job node | the must-end-by deadline that argues for `flexible_time_window_mode = "OFF"` |
| `MAXRERUN="5"` | 15 | every real job node | the `maximum_retry_attempts` default of **5** |
| `MAXWAIT="7"` | 15 | every real job node | **nothing — not representable.** See [§6](#6-honest-divergences) |
| `DAYS="ALL"` | 4 | the four `DAILY-TransactionBackup` jobs only | the daily cadence of the default `schedule_expression` |
| `DAYS="SA"` | 2 | the two `SMART_FOLDER` container nodes | documented intent only; no schedule is provisioned for it |
| `INTERVAL="00005M"` | 2 | the two `SMART_FOLDER` container nodes | documented intent only |
| `PRIORITY="AA"` | 2 | the two `SMART_FOLDER` container nodes | no analogue — one schedule has no queue to be prioritised within |
| `MAXRERUN="0"` | 2 | the two `SMART_FOLDER` container nodes | nothing; a container is a grouping, not a unit of work |

**Assumptions:** a bare `DAYS` attribute occurs on **exactly six** nodes in the
whole file — lines 4, 8, 14 and 20 (`ALL`) and lines 32 and 57 (`SA`). A naive
count of `DAYS="0"` returns seventeen, and every one of those seventeen is the
tail of a `MAXDAYS="0"` attribute rather than a `DAYS` attribute at all. The
distinction is recorded because it is the difference between "the monthly jobs
select no day" and "the monthly jobs state no day", and only the second is true.

### 3.3 CA-7: the LJOB listing

`app/scheduler/CardDemo.ca7` is a 570-line `LJOB` listing. Five lines carry the
scheduling intent this module is concerned with:

| Line | Content | Bearing on this module |
|---|---|---|
| 24 | `CLOSEFIL 255 CLOSEFIL CARDDEMO  000 ALL  *NONE* 000652 005 000 1058  06198/0700` | The job identity row; `ALL` is the main-id column, and the quiesce job heads the chain here exactly as it does in Control-M |
| 35 | `. OWNER= *NONE*  JCLLIB=&CARDDEMOPRODJCL  ARFSET= *NONE*` | The job library the scheduler submitted from; replaced by a container image, so nothing here has an input |
| 37 | `. CLASS=,MSGCLASS=A,REGION=4096K,PRTY=000,CPUTM=00023,ELAPTM=2359` | Recorded baseline attributes only — see the caution below |
| 39 | `. DONT SCHEDULE BEFORE 03237 AT 0000` | **The `start_date` analogue.** A floor on the earliest instant the job may be picked up at all, separate from the recurring expression that governs it afterwards |
| 42–43 | `TRIGGERED JOBS` / `   JOB=CBPAUP0J SCHID=030      QTM=0100 LEADTM=0000 SUBMTM=` | Completion-triggered downstream work, which is a workflow edge and not a schedule |

> **Trade-offs:** the `CPUTM` and `ELAPTM` values on line 37 are cited here **only
> as recorded baseline attributes**, and deliberately not used for anything. They
> are not a runtime expectation, a service-level objective, a timeout or a
> capacity claim, and no argument in this module is derived from them. Reading a
> recorded resource figure from one historical system as a prediction about a
> different one is exactly the inference this note exists to block.

The line 39 mapping is why `start_date` exists as an input and why it defaults to
`null`. **Assumptions:** the *concept* is migrated but the *value* is not, because
that Julian instant belongs to one historical cutover; hard-coding any instant
into a reusable module would impose one environment's cutover on every caller, so
an environment performing a cutover supplies its own.

### 3.4 What the baseline fixes, and what it leaves open

Three consequences follow from the census, and they are stated explicitly because
each one turns a value in `variables.tf` from an apparent preference into a
migrated behaviour:

1. **The daily cadence is what "nightly" concretely means.** Only the
   `DAILY-TransactionBackup` jobs carry `DAYS="ALL"`, and that every-day cadence is
   what the default `schedule_expression` reproduces. The deadline attached to it
   is `TIMETO="23:00"` on all fifteen real job nodes — a hard must-end-by bound,
   not a preference, and the reason a flexible window is off by default.
2. **`MAXRERUN="5"` corroborates the scheduler retry-at-5 posture.** The same
   measured value is used independently for the SQS dead-letter
   `maxReceiveCount`. That is what makes five a preserved baseline behaviour
   rather than an arbitrary round number: an operator of the existing system
   already expects a failed delivery to be retried to that depth.
   `infra/README.md` records the same figure for the same reason. Per-state Step
   Functions retries remain a workflow-owned setting; claiming they share this
   value would cross the boundary described in [§4](#4-the-quiesce-boundary) and
   would not match that module's contract.
3. **The weekly and monthly cadences are documented intent, not provisioned
   resources.** The specified scope is the nightly chain, so this module
   provisions one schedule and records the rest here so that nothing in the
   baseline is silently lost. A caller needing another cadence instantiates this
   module again with its own expression; see the monthly divergence in
   [§6](#6-honest-divergences) for why no monthly expression is supplied.

## 4. The quiesce boundary

**The `CLOSEFIL` … `OPENFIL` bracket appears in three of the five containers —
at lines 4/20, 33/50 and 65/87 — and this module deliberately does not
re-implement any part of it.**

Each of those JCL members is an SDSF step (`app/jcl/CLOSEFIL.jcl:22`,
`app/jcl/OPENFIL.jcl:22`) issuing five operator commands against the same five
files, `TRANSACT`, `CCXREF`, `ACCTDAT`, `CXACAIX` and `USRSEC`
(`app/jcl/CLOSEFIL.jcl:26-30`, `app/jcl/OPENFIL.jcl:26-30`). In the target the
bracket is state 1 `QuiesceOnlineWrites` and state 11 `ResumeOnlineWrites` of the
state machine in
[`infra/modules/step-functions-batch`](../step-functions-batch/README.md), where a
Lambda sets and clears a read-only flag rather than driving a terminal.

**Alternatives Considered.** Putting the quiesce here is the plausible-looking
alternative, and naming it is the point of this section: a scheduler that "closes
the files, runs the chain, reopens the files" reads like a complete description of
the nightly window, so the division of labour has to be justified rather than
merely observed. It is rejected for a specific, non-cosmetic reason. **The bracket
must sit inside the unit of work it protects**, so that a chain failing midway
still reopens the files on its way out — which is exactly what the state machine's
failure path does. Splitting it across two Terraform modules would put the closing
half outside the workflow that knows whether the chain finished, and the failure
mode is concrete: the schedule fires, the chain aborts, and the files stay closed
with no online write path until an operator intervenes. This module's only job is
to **start** the machine; the bracket lives inside it.

**Refactoring Rationale.** `WAITSTEP` / `PGM=COBSWAIT`
(`app/jcl/WAITSTEP.jcl:22`) is retired rather than migrated, and it appears three
times in the baseline containers (lines 14, 44, 81). What it did — pause between
two steps so the preceding one could settle — is expressed by the transition
between two states, so reproducing it would add a scheduled invocation whose only
effect is to wait. Likewise the `INCOND`/`OUTCOND` token hand-off of
[§3.1](#31-control-m-five-containers-three-of-them-bracketed) is a prerequisite
graph, not a calendar, and it is therefore not a scheduler concern either.

## 5. Design decisions

Each decision below mirrors a `# WHY :` comment in the HCL, so that the code and
this document cannot drift into disagreeing. Where a bound or a domain is quoted,
it was measured against the pinned provider rather than read from a guide.

**`flexible_time_window_mode` defaults to `OFF`.** *Trade-offs:* a flexible window
shifts the invocation **later** inside the window, which spends part of the margin
ahead of the one budget the baseline actually states — `TIMETO="23:00"`, on all
fifteen real job nodes. What the feature buys is load-spreading and
thundering-herd jitter across many schedules contending for one target, and there
is exactly one schedule here, so the payoff is nil while the cost is real. The
jitter is what is given up, knowingly; `FLEXIBLE` remains an opt-in, and
`variables.tf` validates the mode and the window width as one contract so that
`OFF`-with-a-window and `FLEXIBLE`-without-one are both rejected at plan time
rather than at apply.

**`maximum_retry_attempts` defaults to 5.** *Assumptions:* this is the baseline's
own number, cited to `MAXRERUN="5"` — see
[§3.4](#34-what-the-baseline-fixes-and-what-it-leaves-open). It is deliberately a
**narrowing**: the platform's own default for the field is 185, so accepting that
default would replace a stated baseline value with a platform one roughly
thirty-seven times larger, and a failing nightly trigger would keep being
re-delivered long past the point at which the baseline would have stopped and
reported.

**The target payload always carries the scheduled-time context attribute.**
*Assumptions:* the module merges its own `scheduledTime` key **last**, so a caller
supplying the same key is overridden rather than honoured. This is how the state
machine receives the business date it is running for, and it matters because the
migrated batch takes its business date as a parameter and never reads it from a
container clock — the same contract the baseline JCL states as a `PARM` date token
and the target carries as a `--business-date` argument. Two consequences follow.
A caller able to replace the payload wholesale could drop that key and get a chain
that still runs, still reports success, and dates its work wrongly; and **a
redriven execution replays the same business date**, because the date travels with
the invocation rather than being re-read at retry time. The payload is therefore
additive by contract, not by convention.

> **Assumptions:** the attribute is substituted by the service finding the keyword
> **literally** in the payload, so the angle brackets are part of the token. That
> is why `main.tf` un-escapes them after `jsonencode`, which HTML-escapes `<` and
> `>`. Left escaped, no substitution happens and the state machine is handed the
> placeholder text instead of an instant — and the failure is silent, because the
> schedule fires and the target starts. Only the dated output is wrong.

**The timezone is explicit.** *Assumptions:* `schedule_expression_timezone`
defaults to `UTC` rather than being left to the service, because a wall-clock
deadline is exactly what a zone decides. In a zone that observes a summer shift
the same cron expression fires an hour earlier or later twice a year, moving the
run relative to the `TIMETO` deadline, and an implicit zone gives nobody a place to
notice. Naming it makes the daylight-saving behaviour a recorded decision rather
than an accidental hour shift; a caller who needs the run pinned to a local wall
clock overrides it and accepts the shift knowingly.

**A named schedule group, not the account's `default` group.** *Alternatives
Considered:* omitting the group is legal and places the schedule in `default`, and
two consequences follow that cannot be recovered afterwards. `default` is not a
resource this module would manage, so nothing would carry the caller's tags and
the schedule would be unattributable in cost reporting; and, decisively, **the
group is the granularity at which a schedule's ARN can be bounded**, so without a
distinct group the trust policy's `aws:SourceArn` condition could not be narrowed
past "any schedule in this account".

**The role's grants are enumerated, with no wildcard action anywhere.** The policy
has two unconditional statements and two that appear only when a key is named:

| Statement | Actions | Resource | When |
|---|---|---|---|
| `AllowStartDailyBatchExecution` | `states:StartExecution` | the one supplied state-machine ARN | always |
| `AllowDeadLetterDelivery` | `sqs:SendMessage` | the one supplied queue ARN | always |
| `AllowSchedulePayloadDecryption` | `kms:Decrypt` | the supplied `kms_key_arn` | only when the schedule payload is CMK-encrypted |
| `AllowDeadLetterQueueEncryption` | `kms:GenerateDataKey`, `kms:Decrypt` | the supplied `dead_letter_kms_key_arn` | only when the dead-letter queue is CMK-encrypted |

*Assumptions:* **configuring a dead-letter target does not by itself make one
work.** The scheduler writes the undeliverable invocation using this role, so
without `sqs:SendMessage` on that exact queue the write is refused — and because
the write **is** the failure path, the refusal has nowhere to be reported and the
failed invocation is simply gone. The grant is not an add-on to the
configuration; it is half of it. The same reasoning applies to the fourth
statement: when the queue is encrypted with a customer-managed key, a role lacking
the two data-key actions loses the invocation just as completely and just as
quietly.

*Alternatives Considered:* granting `kms:GenerateDataKey` on the schedule-payload
key too, matching the dead-letter statement, was rejected — the execution role only
**decrypts** an already-stored payload, and the principal that creates or updates
the schedule owns the write-time key permission. Both key statements are emitted
through a `dynamic` block over a one-or-zero element list, so a queue relying on
service-managed encryption mints no key grant at all; granting unconditionally
would be one line shorter and would leave the role holding KMS permissions against
a null resource.

**The permissions policy is a standalone role-policy resource.** *Alternatives
Considered:* the role's `inline_policy` block is shorter, but the pinned provider
marks it deprecated and it manages the role's complete inline-policy set. Mixing
that block with a separately attached policy lets one representation remove what
the other created. A standalone `aws_iam_role_policy` keeps this one policy
independently addressable in state.

**The schedule explicitly waits for that policy.** *Assumptions:* referencing the
role ARN orders creation of the role, but does not create an edge to its separate
policy resource. Without `depends_on`, Terraform may activate the schedule while
the policy is still in flight; an expression becoming due in that window produces
a failed invocation caused only by deployment ordering. The explicit edge removes
that race rather than relying on the schedule not firing during an apply.

**`aws:SourceArn` is scoped to the schedule *group*, not the schedule.**
*Alternatives Considered:* scoping to the individual schedule is the
tighter-looking option and is wrong twice over. It is **unsatisfiable in this
dependency graph** — the schedule consumes the role's ARN, so a trust policy naming
the schedule would close a cycle Terraform cannot resolve — and the service
specifies the group ARN as the value this condition takes. The condition is paired
with `aws:SourceAccount`, because without both any schedule that named this role,
including one in another account, could induce the service to assume it and start
the batch chain. `StringEquals` rather than a pattern test, since the composed
value is a complete ARN carrying no wildcard.

**Tags apply to the group and the role, not to the schedule.** *Assumptions:* the
gap is the service's, not this module's, and it is stated so it is not filed as an
oversight — `aws_scheduler_schedule` exposes no `tags` argument in the pinned
provider's schema, tagging being a group-level concept here. Attributability is
preserved by tagging the group, to which every schedule belongs exactly once.

**Deliberately not provisioned.** Each omission is a decision:

- **No `aws_cloudwatch_event_rule`.** *Alternatives Considered:* a scheduled
  EventBridge Rule would also start a state machine on a cron expression, and it
  does not carry the fields this module depends on — a schedule group to scope
  permissions against, a per-schedule execution role, an explicit evaluation
  timezone, a start instant, a flexible-window setting and a per-target
  dead-letter configuration, all as first-class arguments. Reaching the same
  posture with a Rule means bolting several of them on elsewhere, and the
  dead-letter target is required rather than optional.
- **No self-managed scheduler** — no cron container, no host `crond`, no hosted
  equivalent of the retired definitions. *Trade-offs:* a self-managed scheduler is
  the only option that could reproduce their syntax, and it costs a component to
  patch, monitor and make highly available in order to fire one nightly trigger.
- **No Lambda shim between the schedule and the state machine.** *Alternatives
  Considered:* the shim is the habitual shape and buys nothing, because the
  scheduler calls `StartExecution` itself. It would add a second execution role, a
  second failure mode and a second place for the payload to be rewritten.
- **No second schedule for the weekly or monthly cadences.** *Assumptions:* the
  specified resource scope is the nightly chain, while the monthly source names
  no day to schedule; see [§6](#6-honest-divergences).
- **No `provider` or `backend` block and no nested `module` call.** *Assumptions:*
  this is a reusable module and not a root. The required `terraform` block in
  `versions.tf` carries only the CLI and provider constraints; provider
  configuration and state ownership remain with the calling root.
- **No log group, metric alarm or dashboard.** *Refactoring Rationale:*
  observability is owned by
  [`infra/modules/observability`](../observability/README.md), so retention and
  alarm thresholds are set once per environment rather than differently inside
  each module that emits something.

## 6. Honest divergences

Rule 1 forbids leaving a non-obvious choice undocumented, and that includes a
choice forced by a platform gap. Three are recorded here rather than papered over.

**`MAXWAIT="7"` cannot be represented, and is deliberately not carried over.** The
Control-M attribute — fifteen occurrences — expresses seven days of willingness to
keep waiting for a job's conditions to be met. The nearest field on this platform
is `maximum_event_age_in_seconds`, and its accepted range is **60 to 86400
seconds**, an upper bound of one day. That bound was measured against the pinned
provider (`hashicorp/aws ~> 6.56`, exercised at 6.57.1) by driving it with
out-of-range values, and independently confirmed against the service's API
reference; it is not inferred. Seven days is therefore not expressible at all.
*Trade-offs:* what replaces it is the dead-letter target — instead of waiting for
days, an undeliverable trigger is captured and surfaced. Writing 86400 as though
it were the baseline figure would misrepresent a divergence as a transcription,
which is why the default sits at the top of the range for a different and stated
reason: the two retry settings are a **conjunction**, so a smaller event age would
cut the retrying short before the five attempts carried over from the baseline had
been made, silently overriding the one figure the baseline does state with one it
does not.

**The baseline states no day-of-month for the monthly cadence, so none is
invented.** *Assumptions:* the `MONTHLY-InterestCalculation` container's five job
nodes carry all twelve month flags set, and **no `DAYS` attribute at all** — nor
any `DCAL`, `WDAYS` or `CONFCAL` calendar attribute, none of which occurs anywhere
in the file. The monthly cadence is expressed by the container's *name* and by
month flags that select every month. Any specific day chosen here would therefore
be an operator decision rather than a migrated one, so this module supplies no
monthly expression and says why instead.

**Two inconsistencies observed in the reference material.** These are recorded as
observed artifacts. They are neither reproduced nor fixed, because `app/**` is
reference-only:

| Where | Observation |
|---|---|
| `app/scheduler/CardDemo.controlm:58` | `TRANEXTR` declares `PARENT_FOLDER="WEEKLY-DisclosureGroupsRefresh"` while it is nested inside the `SMART_FOLDER` opened at line 57, whose `FOLDER_NAME` is `WEEKLY-TransactionTypesDBRefresh` |
| `app/scheduler/CardDemo.controlm:81` and `:87` | `WAITSTEP` and `OPENFIL` both declare `JOBISN="4"`. Every other container numbers its jobs 1, 2, 3, 4 with no repeat |

*Assumptions:* neither affects this module, because neither the parent-folder
attribution nor the job ordinal has a target analogue — sequencing is the state
machine's concern. They are logged so that a reader comparing the two files does
not spend time reconciling a discrepancy that is present in the source.

## 7. Outputs and consumption

The environment root closes two inbound edges before it consumes anything from
this module:

- `state_machine_arn` receives the
  [`daily_state_machine_arn`](../step-functions-batch/README.md#outputs) published
  by `step-functions-batch`. The execution policy then scopes
  `states:StartExecution` to that exact machine.
- `dead_letter_arn` receives the
  [`error_queue_arn`](../sqs/README.md#outputs) published by `sqs`. The execution
  policy then scopes `sqs:SendMessage` to that exact queue. When the queue uses a
  customer-managed key, the same root also supplies `dead_letter_kms_key_arn`.

The dev and prod roots publish this module's complete result as
`batch_schedule`, rather than reconstructing identifiers from the naming
convention. Its four members have distinct consumers:

| Output | Consumer and reason |
|---|---|
| `schedule_name` | Operators and runbooks use the service-native name to locate, disable or re-enable the nightly trigger |
| `schedule_arn` | Audit and observability integrations use the unambiguous resource identity when a name alone does not establish the environment |
| `schedule_group_name` | Operators use the group to find the schedule and its tags together; reviewers use it to reconcile the group-scoped `aws:SourceArn` trust boundary |
| `scheduler_role_arn` | IAM inventory and access reviews use the assumed identity to inspect the trigger's effective permissions without opening this module |

**Trade-offs:** none of the four outputs is marked `sensitive`. Names and ARNs
identify resources but confer no permission to use them; hiding them would remove
useful plan and root-output evidence without protecting credential material.
Conversely, the module does not output the target payload or any queue URL,
because neither is part of the identity contract an operator needs.

The generated [Outputs table](#outputs) remains the canonical type-and-description
contract. This section records the cross-module flow and the operational reason
for each value rather than maintaining a second hand-written copy of the HCL.

## 8. Gates

Run the following from the repository root after loading the pinned toolchain.
The flags mirror `.github/workflows/infra-ci.yml`; the workflow substitutes
`$GITHUB_WORKSPACE` for `repo_root`.

```bash
# WHAT: reject Terraform formatting drift without rewriting the checkout.
# WHY : Alternatives Considered: running bare `terraform fmt` in CI would mutate
#       the evidence under review and let the original drift disappear.
set -euo pipefail
terraform fmt -check -recursive infra/

# WHAT: initialize provider schemas without a backend, then validate every root.
# WHY : Assumptions: module validation is transitive through these three roots;
#       backend access and cloud credentials are not required for static checks.
for root in infra/bootstrap infra/envs/dev infra/envs/prod; do
  terraform -chdir="$root" init -backend=false -lockfile=readonly -input=false
  terraform -chdir="$root" validate
done

repo_root="$PWD"

# WHAT: initialize the pinned AWS ruleset and lint the complete Terraform tree.
# WHY : Assumptions: the shared config carries the Rule 1 documentation checks,
#       so a module-local invocation would leave cross-module drift unexamined.
tflint --init --config="$repo_root/infra/.tflint.hcl"
tflint --recursive --config="$repo_root/infra/.tflint.hcl"

# WHAT: prove every generated README region is byte-current with its HCL.
# WHY : Alternatives Considered: generation in CI was rejected because silent
#       mutation would hide the documentation drift the gate must expose.
for dir in infra/bootstrap infra/modules/* infra/envs/dev infra/envs/prod; do
  terraform-docs \
    --config "$repo_root/infra/.terraform-docs.yml" \
    --output-check "$dir"
done
```

Two later workflow gates inspect the same tree rather than changing it:

- The secret scan stages only migration-owned tracked files and fails on
  committed credential material.
- The Checkov gate first records the complete result set with `--soft-fail`, then
  hard-fails on the workflow's explicit, reviewable list of material IAM,
  encryption, audit, network, edge, messaging and workload-isolation checks. It
  asserts that resources and passing checks were found, that no selected check
  failed, and that no parse error occurred. **It deliberately does not use
  `--check HIGH,CRITICAL`: the pinned offline distribution carries no severity
  metadata, so that selector chooses zero checks and creates a false green.**

This module satisfies two policy properties by construction rather than by a
suppression: `aws_scheduler_schedule.this` always has a dead-letter target, and
the role policy contains no wildcard action. There is no `checkov:skip` comment
in the module.

The baseline census in [§3.2](#32-verified-attribute-census) is independently
reproducible. Fixed-string matching is sufficient for the seven reported values;
the separate boundary-aware check below is what prevents `DAYS` from being
confused with the suffix of `MAXDAYS`.

```bash
# WHAT: reproduce the seven cited Control-M attribute counts.
# WHY : Assumptions: each selected value occurs at most once per XML element, so
#       counting exact matches measures attributes rather than matching lines.
control_m=app/scheduler/CardDemo.controlm
for attribute in \
  'TIMETO="23:00"' \
  'MAXRERUN="5"' \
  'MAXWAIT="7"' \
  'DAYS="ALL"' \
  'DAYS="SA"' \
  'INTERVAL="00005M"' \
  'PRIORITY="AA"'
do
  printf '%-22s ' "$attribute"
  grep -oF "$attribute" "$control_m" | wc -l
done

# WHAT: distinguish a real DAYS="0" attribute from MAXDAYS="0".
# WHY : Refactoring Rationale: an unbounded substring count reports seventeen
#       false matches and changes the monthly-cadence conclusion.
printf 'raw DAYS="0":  '
grep -oF 'DAYS="0"' "$control_m" | wc -l
printf 'bare DAYS="0": '
grep -oP '(?<![A-Z_])DAYS="0"' "$control_m" | wc -l
```

## 9. Troubleshooting

### The schedule is enabled but no execution starts

First distinguish calendar evaluation from target delivery. Confirm
`schedule_state`, `schedule_expression`, `schedule_expression_timezone` and
`start_date`; a future start instant or a different explicit zone can correctly
leave an enabled schedule with no eligible invocation. If the schedule reports a
target failure, inspect the dead-letter queue before retrying manually. Then
verify that the role named by `scheduler_role_arn` can call
`states:StartExecution` on the supplied `state_machine_arn`, that its trust policy
matches this schedule group, and, when `kms_key_arn` is set, that the role retains
`kms:Decrypt` on that exact key.

**Assumptions:** do not add a Lambda shim as a diagnostic bypass. A shim can make
the symptom disappear by introducing a different role while leaving the
scheduler's actual trust or target permission broken; inspect the direct
integration instead.

### A failed invocation is not present in the dead-letter queue

Check that `dead_letter_arn` names the intended queue and that the schedule role
still has `sqs:SendMessage` on it. For a customer-managed queue key,
`dead_letter_kms_key_arn` must name that same key so the conditional
`kms:GenerateDataKey` and `kms:Decrypt` grant exists. A queue can be visibly
configured as the dead-letter target while its write is denied, which is why
target configuration and role permission must be diagnosed as one path.

**Trade-offs:** do not broaden either statement to `"*"`. That can mask a wiring
error by allowing the failed invocation to be written to an unintended queue,
turning a visible delivery failure into a cross-environment data-routing defect.

### `terraform-docs` reports drift after an HCL edit

Do not edit anything between the markers by hand. Regenerate in inject mode, then
run the same check-only command CI uses:

```bash
# WHAT: replace only the generated region, then prove that a second render is
#       byte-identical.
# WHY : Assumptions: the begin/end markers are the ownership boundary; prose
#       outside them is hand-written and must survive generation unchanged.
terraform-docs \
  --config infra/.terraform-docs.yml \
  infra/modules/eventbridge-scheduler
terraform-docs \
  --config infra/.terraform-docs.yml \
  --output-check infra/modules/eventbridge-scheduler
```

If generation reports that it has nowhere to inject, restore the two literal
marker-comment lines under [§11](#11-terraform-reference) before running it
again. A missing marker is a failed contract, not an empty reference section.

### An operator must prevent the next invocation

Set `schedule_state = "DISABLED"` in the calling environment root, review the
plan, and apply it through the normal deployment path. The schedule, group, role,
outputs and audit trail remain present, but the service does not trigger a run.

**Alternatives Considered:** removing the module also stops the trigger, but it
simultaneously deletes the identity an operator needs to inspect and creates a
larger restoration plan. `DISABLED` expresses an operational pause without
changing the stack's shape.

## 10. Related documents

- [Infrastructure guide](../../README.md) — module index, toolchain and
  repository-wide validation contract
- [ADR-005: batch orchestration](../../../docs/adr/ADR-005-batch-orchestration.md)
  — authority for the managed scheduler and state-machine boundary
- [Batch orchestration architecture](../../../docs/architecture/batch-orchestration.md)
  — end-to-end trigger and workflow flow
- [Batch operations runbook](../../../docs/runbooks/batch-operations.md) —
  execution diagnosis, restart and redrive procedure
- [Deployment runbook](../../../docs/runbooks/deploy.md) — reviewed path for
  applying a schedule-state or expression change
- [Teardown runbook](../../../docs/runbooks/teardown.md) — dependency-aware
  removal order
- [Step Functions batch module](../step-functions-batch/README.md) — owner of the
  workflow, business-date handling and quiesce bracket
- [SQS module](../sqs/README.md) — owner of the dead-letter queue supplied here
- [Observability module](../observability/README.md) — owner of dashboards,
  alarms and notification routing
- [Documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md) —
  Rule 1 prose and generated-reference convention
- [TFLint configuration](../../.tflint.hcl) and
  [terraform-docs configuration](../../.terraform-docs.yml) — executable
  documentation gates

## 11. Terraform reference

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_iam_role.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role) | resource |
| [aws_iam_role_policy.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/iam_role_policy) | resource |
| [aws_scheduler_schedule.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/scheduler_schedule) | resource |
| [aws_scheduler_schedule_group.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/scheduler_schedule_group) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.assume_role](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.permissions](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_dead_letter_arn"></a> [dead\_letter\_arn](#input\_dead\_letter\_arn) | ARN of the SQS queue that receives an invocation EventBridge Scheduler could not deliver to the state machine. This is what makes a failed nightly trigger captured and inspectable rather than silently lost. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment this schedule belongs to. Supplies the `-<env>` suffix that keeps the dev and prod copies of every resource this module creates distinct. Accepted values: dev, prod. | `string` | n/a | yes |
| <a name="input_state_machine_arn"></a> [state\_machine\_arn](#input\_state\_machine\_arn) | ARN of the `carddemo-daily-batch` Step Functions state machine this schedule starts. Published as an output by infra/modules/step-functions-batch and passed in by the environment root; the schedule's IAM role is granted states:StartExecution on exactly this value. | `string` | n/a | yes |
| <a name="input_dead_letter_kms_key_arn"></a> [dead\_letter\_kms\_key\_arn](#input\_dead\_letter\_kms\_key\_arn) | Customer-managed KMS key encrypting the dead-letter queue, when that queue is CMK-encrypted; null when it relies on SQS-managed encryption. Controls whether the schedule's role is additionally granted kms:GenerateDataKey and kms:Decrypt on that key. | `string` | `null` | no |
| <a name="input_flexible_time_window_minutes"></a> [flexible\_time\_window\_minutes](#input\_flexible\_time\_window\_minutes) | Width in whole minutes of the window an invocation may be shifted within. Must be null when flexible\_time\_window\_mode is OFF and must be an integer from 1 to 1440 when the mode is FLEXIBLE. | `number` | `null` | no |
| <a name="input_flexible_time_window_mode"></a> [flexible\_time\_window\_mode](#input\_flexible\_time\_window\_mode) | Whether EventBridge Scheduler may shift an invocation within a window rather than firing at the exact expression time. OFF fires at the expression time; FLEXIBLE spreads it across the window given by flexible\_time\_window\_minutes. Accepted values: OFF, FLEXIBLE. | `string` | `"OFF"` | no |
| <a name="input_kms_key_arn"></a> [kms\_key\_arn](#input\_kms\_key\_arn) | Customer-managed KMS key used to encrypt the schedule's stored target payload, or null to use the service-owned key. When set, main.tf grants the schedule execution role kms:Decrypt on this exact key so it can read the payload before invoking the state machine. | `string` | `null` | no |
| <a name="input_maximum_event_age_in_seconds"></a> [maximum\_event\_age\_in\_seconds](#input\_maximum\_event\_age\_in\_seconds) | Outer bound, in seconds, on how long retry attempts may continue before the invocation is sent to the dead-letter queue. Accepts 60 to 86400, the range the pinned AWS provider enforces. | `number` | `86400` | no |
| <a name="input_maximum_retry_attempts"></a> [maximum\_retry\_attempts](#input\_maximum\_retry\_attempts) | Retries attempted, with exponential backoff, before the invocation is sent to the dead-letter queue. Accepts 0 to 185, the range the pinned AWS provider enforces. | `number` | `5` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading token shared by the schedule, its schedule group and the IAM role the schedule assumes, so the three resources that make up one nightly trigger are recognisable as a set in the console and in cost reporting. | `string` | `"carddemo"` | no |
| <a name="input_schedule_expression"></a> [schedule\_expression](#input\_schedule\_expression) | Cron expression in the six-field EventBridge Scheduler form `cron(minutes hours day-of-month month day-of-week year)` fixing when the batch chain is started. Only the cron(...) form is accepted; rate(...) and at(...) are rejected. | `string` | `"cron(0 0 * * ? *)"` | no |
| <a name="input_schedule_expression_timezone"></a> [schedule\_expression\_timezone](#input\_schedule\_expression\_timezone) | IANA time zone name, such as UTC or America/New\_York, that the cron expression above is interpreted in. | `string` | `"UTC"` | no |
| <a name="input_schedule_state"></a> [schedule\_state](#input\_schedule\_state) | Whether the schedule fires. ENABLED starts the batch chain on the expression above; DISABLED provisions the schedule, its group and its role but never triggers a run, which lets a dev root stand the trigger up and verify it without running the nightly chain. Accepted values: ENABLED, DISABLED. | `string` | `"ENABLED"` | no |
| <a name="input_start_date"></a> [start\_date](#input\_start\_date) | RFC3339 instant before which the schedule must not fire, or null to let it fire from the moment it is created. | `string` | `null` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Tags applied to the schedule group and to the schedule's IAM role. They are not applied to the schedule itself, because aws\_scheduler\_schedule exposes no tags argument. | `map(string)` | `{}` | no |
| <a name="input_target_input"></a> [target\_input](#input\_target\_input) | Additional key/value pairs merged into the JSON document handed to StartExecution. Keys are added to the payload the module already builds; they cannot remove or replace it. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_schedule_arn"></a> [schedule\_arn](#output\_schedule\_arn) | ARN of that same schedule, for referring to it from outside this module: a dashboard or alarm identifies a schedule by ARN rather than by name, and a runbook step quotes it to establish which environment's trigger an execution came from. |
| <a name="output_schedule_group_name"></a> [schedule\_group\_name](#output\_schedule\_group\_name) | Name of the schedule group the schedule belongs to. The group is functional and not organisational: its ARN is the value the role's trust policy matches on `aws:SourceArn`, so the group is what bounds which schedules may assume that role at all. It is also where this module's tags land, because the schedule resource itself accepts none. |
| <a name="output_schedule_name"></a> [schedule\_name](#output\_schedule\_name) | Name of the nightly EventBridge Scheduler schedule that starts the `carddemo-daily-batch` Step Functions state machine on the cron expression supplied to this module. This is the handle an operator uses to locate that trigger, and the one to name when disabling or re-enabling it. |
| <a name="output_scheduler_role_arn"></a> [scheduler\_role\_arn](#output\_scheduler\_role\_arn) | ARN of the execution role EventBridge Scheduler assumes to act for this schedule. It is permitted to call `states:StartExecution` on the one state machine supplied and `sqs:SendMessage` on the one dead-letter queue supplied, plus the two data-key operations a CMK-encrypted queue needs when one is named, and nothing further. Published so a root can reference the trigger's identity and audit its effective privilege without reading the module. |
<!-- END_TF_DOCS -->
