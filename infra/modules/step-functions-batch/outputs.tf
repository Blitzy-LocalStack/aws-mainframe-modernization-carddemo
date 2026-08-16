# =============================================================================
# infra/modules/step-functions-batch/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete published contract of the `step-functions-batch` module. Every
#   value a caller may depend on passes through this file, and nothing else
#   does: the four workflow entry points, the four per-machine execution roles,
#   the four execution-log groups, the two out-of-execution bracket-release
#   rules with their dead-letter queue and alarm inventory, the resolved
#   seed-extract staging location this module composes, and one DEPRECATED alias
#   kept for a compatibility window. Anything else a consumer
#   wants is module internals, and each deliberate omission is recorded at the
#   foot of this file rather than left looking like a gap.
#
# Parameters:
#   None. An outputs.tf declares no input, and this module's input contract is
#   variables.tf. Every value below is an attribute of a resource declared in
#   main.tf, or the one staging location main.tf composes from several of its
#   inputs, with the single exception of the deprecated alias in group 7, which is
#   an input read straight back out and says so at its own declaration; see the
#   deprecation recorded below.
#
# Return values:
#   Every value is a string unless stated otherwise, and none is sensitive.
#     1. State machines -- an ARN and a name for each of the four.
#        infra/modules/eventbridge-scheduler consumes the daily ARN as its
#        `state_machine_arn` target and infra/modules/observability consumes it
#        as `daily_state_machine_arn`; the environment roots publish the other
#        three for discovery and assert the ad-hoc one against the ARN they
#        compose to break the reporting task-definition dependency cycle.
#     2. Execution roles -- two `map(string)` values keyed `daily`, `adhoc`,
#        `dataset` and `authz`, so an environment root can inventory or extend
#        one machine's role without a change to shared module code.
#     3. Execution log groups -- an ARN and a name for each of the four,
#        published for operator discovery and for a Logs Insights query. NO module
#        or root consumes any of the eight today, and the Refactoring Rationale in
#        the WHY section below records why they are published anyway rather than
#        described as something they are not.
#     4. Bracket-release rules -- an ARN and a name for the terminal-status
#        finalizer rule and for the scheduled reconciler rule, so an operator
#        can tell which of the two released a quiesce bracket that no state in
#        the execution history released.
#     5. Bracket-release dead letter -- the queue's ARN and its URL, the two
#        forms docs/runbooks/batch-operations.md reads, plus a `list(string)` of
#        the FOUR alarm names guarding that release path -- the two EventBridge
#        delivery alarms, the finalizer function's own error alarm and the dead-letter
#        queue's depth alarm. Refactoring Rationale: this said THREE, and the value
#        returns four; the count was written before the queue-depth alarm was added
#        and was never re-derived. An operator who reads a count instead of the list
#        would have looked for a missing alarm that is in fact present.
#     6. Seed-extract staging location -- the location every staging and load
#        task receives, read from each root's `batch_orchestration` output by
#        docs/runbooks/data-migration.md.
#     7. One DEPRECATED alias, `dataset_source_extract_prefix`, echoing this
#        module's identically named input for one compatibility window. Its
#        replacement and its removal boundary are stated at its declaration, and
#        it is the only value here that may be removed without a further window.
#
# Exceptions or errors:
#   Three things a consumer must not read into a value being published here.
#   Publishing an ARN grants nothing: no principal may start any of these four
#   machines until a policy of its own allows `states:StartExecution` on that
#   exact resource, and this module attaches no such policy to anyone.
#   Publishing an ARN stores nothing either: this module writes no SSM Parameter
#   Store entry, so the ad-hoc report ARN reaches services/reporting-service
#   only because the environment root puts it there. And nothing here describes
#   a schedule -- the nightly trigger belongs to
#   infra/modules/eventbridge-scheduler, which consumes the daily ARN rather
#   than being described by this module. Terraform resolves no output before the
#   resource behind it exists, so a failed apply withholds the value instead of
#   publishing a composed guess.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: an output that re-exported an input is DEPRECATED
#     rather than withdrawn, and the two-step is the decision.
#     `dataset_source_extract_prefix` returns this module's identically named
#     INPUT verbatim -- a value infra/modules/s3-datasets owns and already
#     publishes as `source_extract_prefix`, and that the environment roots merely
#     pass in -- so one string has two publishers and a reader cannot tell which
#     module decided it. That is why it is deprecated. It was justified in place by
#     the claim that the data-migration runbook needed the bare prefix to build a
#     key without parsing a URI in shell; that runbook reads
#     `source_extract_uri` from the roots' `datasets` output and the resolved root
#     from `batch_orchestration` instead, so the justification described a consumer
#     that does not exist. It was briefly REMOVED on that ground, and the removal
#     was the error: the paragraph below declares these names an external contract
#     reaching past this repository's Terraform, and no repository search can prove
#     that no remote-state or automation consumer addresses this key of the roots'
#     `batch_orchestration` object. So the name is kept for one compatibility
#     window with the replacement and the removal boundary stated at its own
#     declaration, which is a deprecation an owner can act on rather than an
#     unresolvable reference in somebody else's plan. The composed
#     `dataset_staging_root` stays on its own terms, because that value is this
#     module's own decision rather than a caller's.
#   - Refactoring Rationale: this file grew from one machine's identity to four
#     as main.tf did. The dataset round trip and the authorization extract are
#     operator-invoked rather than nightly, and the batch jobs behind them had
#     no runnable entry point in a provisioned environment before those machines
#     existed, so their identities are published on exactly the same terms as
#     the daily and ad-hoc machines instead of being treated as internals.
#   - Assumptions: the output NAMES here are a contract that reaches past this
#     repository's Terraform. Both environment roots publish this module whole
#     as `batch_orchestration`, and docs/runbooks/batch-operations.md and
#     docs/runbooks/data-migration.md address individual keys of that object by
#     name in operator commands, so renaming one is a breaking change to a
#     documented procedure rather than a refactor.
# =============================================================================

# -----------------------------------------------------------------------------
# Daily batch state machine
# -----------------------------------------------------------------------------

# WHY : Trade-offs: an ARN and a name are both published for the same machine,
#       and that pairing repeats for the other three machines, the roles and the
#       log groups. The redundancy is accepted because the alternative leaves a
#       consumer that needs the name cutting it out of the ARN, and splitting an
#       ARN on its colons is string surgery against a format whose segment count
#       is not ours to guarantee: such a consumer breaks silently if a segment is
#       ever added, whereas a published attribute cannot. Argued here once and
#       not repeated at each pair below.
output "daily_state_machine_arn" {
  description = "ARN of the eleven-work-state daily batch machine. The EventBridge Scheduler module targets this value and observability scopes the batch-failure alarm to it."
  value       = aws_sfn_state_machine.daily.arn
}

output "daily_state_machine_name" {
  description = "Name of the daily batch machine, used in operator commands, execution-history queries and dashboard dimensions."
  value       = aws_sfn_state_machine.daily.name
}

# -----------------------------------------------------------------------------
# Ad-hoc report state machine
# -----------------------------------------------------------------------------

# WHY : Assumptions: this module publishes the ad-hoc machine's identity and does
#       nothing else on its behalf. It writes no SSM Parameter Store entry, and
#       it attaches no `states:StartExecution` grant to
#       services/reporting-service's task role -- a module that reached into
#       another service's IAM would put that grant beyond the reach of the root
#       which owns the service. Both are the environment root's work: it
#       publishes the ARN under its reporting parameter path, grants the call in
#       reporting-service's own runtime policy, and asserts in a precondition
#       that the ARN it composes to break the task-definition dependency cycle
#       still equals this output.
# WHY : Assumptions: a second machine exists because the baseline submitted an
#       on-demand report by writing job cards to the CICS transient data queue
#       defined at app/csd/CARDDEMO.CSD:499-505, whose ERROROPTION(IGNORE) at
#       :501 discarded a failed write without telling the caller, whereas
#       `states:StartExecution` answers with an execution identity or an error.
#       docs/architecture/batch-orchestration.md carries the full account.
output "adhoc_report_state_machine_arn" {
  description = "ARN of the ad-hoc report machine. The environment root asserts the ARN it composes for reporting-service against this output, publishes that copy to Parameter Store, and grants states:StartExecution on this exact resource in reporting-service's own runtime policy."
  value       = aws_sfn_state_machine.adhoc.arn
}

output "adhoc_report_state_machine_name" {
  description = "Name of the ad-hoc report machine, used in execution-history queries and report-operations diagnostics."
  value       = aws_sfn_state_machine.adhoc.name
}

# -----------------------------------------------------------------------------
# Operator-invoked state machines
# -----------------------------------------------------------------------------

# WHY : Assumptions: these two are published for the same pair of consumers the
#       ad-hoc machine's identity is -- a principal calling
#       `states:StartExecution`, and the environment root granting that call on
#       exactly this resource -- and on the same terms: publishing an ARN is
#       discovery and never a grant. Each root reads both from these outputs
#       rather than composing them from the name prefix and environment, so a
#       rename inside this module cannot leave a published value addressing a
#       machine that does not exist.
# WHY : Trade-offs: neither machine is reachable from the nightly schedule, and
#       that is the point rather than an omission. The jobs behind them --
#       app/jcl/CBEXPORT.jcl and app/jcl/CBIMPORT.jcl for the round trip, the
#       authorization segment unload and load for the extract -- are standalone
#       job cards an operator submits in the baseline, named in neither scheduler
#       definition under app/scheduler/, so an operator-started machine keeps
#       that execution model. Nightly states would instead produce a full extract
#       every night whether one was asked for or not.
output "dataset_roundtrip_state_machine_arn" {
  description = "ARN of the operator-invoked dataset export/import round-trip machine. An operator or automation starts it with a single `businessDate` input; the environment root grants states:StartExecution on exactly this resource to any principal that needs it."
  value       = aws_sfn_state_machine.dataset_roundtrip.arn
}

output "dataset_roundtrip_state_machine_name" {
  description = "Name of the dataset round-trip state machine, for a console link or a CLI invocation that addresses it by name."
  value       = aws_sfn_state_machine.dataset_roundtrip.name
}

output "authorization_extract_state_machine_arn" {
  description = "ARN of the operator-invoked authorization-extract state machine, which runs the pending-authorization segment export and the extract load. The environment root publishes it under its authorization parameter path for discovery and grants states:StartExecution on this exact resource to the principal that starts it."
  value       = aws_sfn_state_machine.authorization_extract.arn
}

output "authorization_extract_state_machine_name" {
  description = "Name of the operator-invoked authorization-extract state machine, which is what an operator passes to start-execution."
  value       = aws_sfn_state_machine.authorization_extract.name
}

# -----------------------------------------------------------------------------
# Execution roles
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: these two were scalars naming one shared execution
#       role. They are maps keyed by machine -- daily, adhoc, dataset, authz --
#       because each machine now holds its own role scoped to the task
#       definitions, pass-roles and functions its own definition references. A
#       scalar would have had to pick one of the four and would have made the
#       other three invisible to the IAM inventory these outputs exist for.
# WHY : Alternatives Considered: keeping the roles entirely private to the module
#       was the alternative, and it is rejected because an environment-specific
#       grant would then require an edit to shared module code -- the coupling a
#       reusable module exists to prevent. The counterweight is that publishing a
#       role is not an invitation to widen it: the module's own inline policy
#       names individual API actions and no wildcard action at all, so a policy a
#       root attaches from outside must stay as narrowly scoped. It is also why
#       the name is published beside the ARN, because
#       aws_iam_role_policy_attachment takes a role name and will not accept an
#       ARN.
output "execution_role_arns" {
  description = "ARN of each state machine's execution role, keyed by machine (daily, adhoc, dataset, authz), for IAM inventory and policy auditing by the environment root."
  value       = { for key, role in aws_iam_role.this : key => role.arn }
}

output "execution_role_names" {
  description = "Name of each state machine's execution role, keyed by machine (daily, adhoc, dataset, authz), used by operator and compliance queries that address IAM roles by name."
  value       = { for key, role in aws_iam_role.this : key => role.name }
}

# -----------------------------------------------------------------------------
# Execution log groups
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: these eight values are DISCOVERY contracts, and the
#       descriptions below used to describe them as inputs to work that does not
#       exist -- "the metric filters, subscription filters and log-based alarms
#       observability attaches to this exact group". No metric filter, subscription
#       filter or log-based alarm is attached to any of these four groups anywhere in
#       this repository, and NO module or environment root reads any of the eight
#       outputs. A description that names a consumer which does not exist is worse
#       than one that names none: a reader wires nothing because they believe it is
#       already wired, and an auditor reads the alarm coverage as broader than it is.
#       Each description below now states what the value IS and what an operator or a
#       future root may do with it, in the conditional, and claims no current reader.
# WHY : Assumptions: they are published rather than deleted, and that is a choice with
#       a reason. The alternative -- letting a consumer find these groups by naming
#       convention, since each is /aws/vendedlogs/states/ followed by its machine's
#       name -- would make the group name an implicit contract that nothing validates,
#       so a rename inside this module would leave a future dashboard or filter
#       addressing a group that no longer exists and reporting nothing rather than
#       failing. Publishing the identities means the same rename fails at plan time in
#       whichever root wires the two together. Trade-offs: eight outputs with no
#       current reader, accepted because output names are a contract this file states
#       is external, so adding one later is cheap and removing one is not.
# WHY : Assumptions: this module owns these four log groups and nothing else in
#       the observability tier. Dashboards, the notification topic and every
#       alarm except the four guarding the out-of-execution bracket release
#       belong to infra/modules/observability.
output "daily_log_group_arn" {
  description = "ARN of the encrypted CloudWatch log group receiving daily-machine execution events. Discovery only in the sense that matters here: no module or root reads this ARN, because modules/observability takes the group by NAME. A metric filter and an execution-failure alarm ARE attached to the group itself through that path, so this value is the identity a further consumer would scope to rather than evidence the group is unwatched. It is the identity a future consumer would scope one to, or that an operator names in a Logs Insights query."
  value       = aws_cloudwatch_log_group.daily.arn
}

output "daily_log_group_name" {
  description = "Name of the CloudWatch log group receiving daily-machine execution events. Both environment roots pass this name to modules/observability as the `daily` entry of state_machine_log_group_names, which attaches a metric filter counting terminal ExecutionFailed and ExecutionTimedOut events and an alarm on that metric, so the group is a consumed contract rather than a discovery value."
  value       = aws_cloudwatch_log_group.daily.name
}

output "adhoc_report_log_group_arn" {
  description = "ARN of the encrypted CloudWatch log group receiving ad-hoc report execution events. Discovery only in the sense that matters here: no module or root reads this ARN, because modules/observability takes the group by NAME. A metric filter and an execution-failure alarm ARE attached to the group itself through that path, so this value is the identity a further consumer would scope to rather than evidence the group is unwatched."
  value       = aws_cloudwatch_log_group.adhoc.arn
}

output "adhoc_report_log_group_name" {
  description = "Name of the CloudWatch log group receiving ad-hoc report execution events. Both environment roots pass this name to modules/observability as the `adhoc` entry of state_machine_log_group_names, which attaches a metric filter counting terminal ExecutionFailed and ExecutionTimedOut events and an alarm on that metric, so the group is a consumed contract rather than a discovery value."
  value       = aws_cloudwatch_log_group.adhoc.name
}

output "dataset_roundtrip_log_group_arn" {
  description = "ARN of the CloudWatch log group the dataset round-trip machine writes its execution history to. Discovery only in the sense that matters here: no module or root reads this ARN, because modules/observability takes the group by NAME. A metric filter and an execution-failure alarm ARE attached to the group itself through that path, so this value is the identity a further consumer would scope to rather than evidence the group is unwatched."
  value       = aws_cloudwatch_log_group.dataset_roundtrip.arn
}

output "dataset_roundtrip_log_group_name" {
  description = "Name of the CloudWatch log group the dataset round-trip machine writes to. Both environment roots pass this name to modules/observability as the `dataset` entry of state_machine_log_group_names, which attaches a metric filter counting terminal ExecutionFailed and ExecutionTimedOut events and an alarm on that metric, so the group is a consumed contract rather than a discovery value."
  value       = aws_cloudwatch_log_group.dataset_roundtrip.name
}

output "authorization_extract_log_group_arn" {
  description = "ARN of the log group the authorization-extract state machine writes its execution history to. Discovery only in the sense that matters here: no module or root reads this ARN, because modules/observability takes the group by NAME. A metric filter and an execution-failure alarm ARE attached to the group itself through that path, so this value is the identity a further consumer would scope to rather than evidence the group is unwatched."
  value       = aws_cloudwatch_log_group.authorization_extract.arn
}

output "authorization_extract_log_group_name" {
  description = "Name of the log group the authorization-extract state machine writes its execution history to. Both environment roots pass this name to modules/observability as the `authz` entry of state_machine_log_group_names, which attaches a metric filter counting terminal ExecutionFailed and ExecutionTimedOut events and an alarm on that metric, so the group is a consumed contract rather than a discovery value."
  value       = aws_cloudwatch_log_group.authorization_extract.name
}

# -----------------------------------------------------------------------------
# Out-of-execution bracket release
# -----------------------------------------------------------------------------

# WHY : Assumptions: the finalizer rule is published because it is the only part
#       of the quiesce bracket that lives outside the execution, so nothing in an
#       execution history reveals it. An operator asking why the read-only flag
#       cleared without a resume state having run, and an alarm on the rule's
#       failed-invocation metric, both need to address it by identity rather than
#       by reading this module. A FailedInvocations count on this rule is the one
#       signal that the bracket was left set -- the condition the rule exists to
#       prevent.
# WHY : Assumptions: the reconciler rule is published on the same terms as the
#       finalizer rule. It is the other half of the out-of-execution release, and
#       an operator reading a flag that cleared with no resume state in the
#       history needs to be able to tell which of the two cleared it.
output "bracket_finalizer_rule_arn" {
  description = "ARN of the EventBridge rule that releases the online write quiesce bracket when a daily execution terminates without having released it in-graph. Consumers scope failed-invocation alarms to this exact rule."
  value       = aws_cloudwatch_event_rule.daily_finalizer.arn
}

output "bracket_finalizer_rule_name" {
  description = "Name of the bracket finalizer rule, used in operator diagnostics and CloudWatch metric dimensions when explaining a resume that no state in the execution history performed."
  value       = aws_cloudwatch_event_rule.daily_finalizer.name
}

output "bracket_reconciler_rule_arn" {
  description = "ARN of the scheduled EventBridge rule that asks the resume function to release a quiesce bracket no event released, once its owning execution and tasks are terminal."
  value       = aws_cloudwatch_event_rule.bracket_reconciler.arn
}

output "bracket_reconciler_rule_name" {
  description = "Name of the bracket reconciler rule, used in operator diagnostics and CloudWatch metric dimensions when explaining a resume that no state in the execution history performed."
  value       = aws_cloudwatch_event_rule.bracket_reconciler.name
}

output "bracket_release_dead_letter_queue_arn" {
  description = "ARN of the queue retaining bracket-release invocations that EventBridge could not deliver. Operators read it to find which execution's bracket was never released; nothing consumes it automatically."
  value       = aws_sqs_queue.bracket_release_dlq.arn
}

output "bracket_release_dead_letter_queue_url" {
  description = "URL of the bracket-release dead-letter queue, for the receive-message and purge commands the batch-operations runbook publishes."
  value       = aws_sqs_queue.bracket_release_dlq.url
}

# WHY : Refactoring Rationale: an earlier draft of the paragraph above left an
#       alarm on the finalizer's FailedInvocations metric to "the observability
#       root" to attach, and no root attached one -- a signal nobody owns is a
#       signal nobody receives. main.tf therefore declares that alarm and the two
#       beside it, and this output exists so a root can still see what was
#       created, an alarm inventory being a compliance artifact, without becoming
#       responsible for creating it.
output "bracket_release_alarm_names" {
  description = "Names of every alarm guarding the out-of-execution bracket release: one per release rule's failed invocations, one for the resume function's errors and one for the dead-letter queue's depth."
  value = concat(
    [for alarm in aws_cloudwatch_metric_alarm.release_delivery_failed : alarm.alarm_name],
    [
      aws_cloudwatch_metric_alarm.release_function_errors.alarm_name,
      aws_cloudwatch_metric_alarm.release_dead_letters.alarm_name,
    ],
  )
}

# -----------------------------------------------------------------------------
# Seed-extract staging location
# -----------------------------------------------------------------------------

# WHY : Assumptions: the resolved staging root is published because an operator
#       has to put the seed extracts where the nightly staging branches will look
#       for them, and the only authority on that location is the composition
#       main.tf performs over three of its inputs.
# WHY : Trade-offs: the RESOLVED root is published rather than the inputs it is
#       composed from, so an operator reading it sees the effect of the optional
#       override -- the value the tasks actually receive. Reading the inputs back
#       instead would report the composed default even where an override is in
#       force.
output "dataset_staging_root" {
  description = "Resolved location the data-migration container reads seed extracts from, as passed to every staging and load task in CARDDEMO_DATASET_STAGING_ROOT. Either an s3 URI over the dataset bucket and the source extract prefix or, where an operator overrides it, an absolute filesystem path. The data-migration runbook reads it from each root's batch_orchestration output to confirm the nightly chain and the operator commands resolve the same place."
  value       = local.dataset_staging_root
}

# WHY : (1) Refactoring Rationale: this output is DEPRECATED and is republished
#       rather than deleted. It was withdrawn outright on the ground that it
#       re-exported an input another module owns, which remains true and is why it
#       is deprecated -- but a removal is a breaking change to a name this file
#       itself declares to be an external contract, and both environment roots
#       expose this module whole as `batch_orchestration`, so a consumer of that
#       object may address this key from remote state or from an operator command
#       outside this repository. A repository search cannot prove such a consumer
#       absent, and the failure mode of guessing wrong is an unresolvable
#       reference in somebody else's plan rather than anything this repository's own
#       gates would catch. Keeping the name for a compatibility window costs one
#       string and converts that class of breakage into a deprecation an owner can
#       act on.
# WHY : (2) Assumptions: the MIGRATION BOUNDARY is stated here rather than left to
#       a changelog, because a deprecation with no stated end is indistinguishable
#       from a permanent output. A consumer needing the prefix reads
#       `source_extract_prefix` from the roots' `datasets` output, which is
#       infra/modules/s3-datasets's own publication of the value it decides; a
#       consumer needing the location the tasks actually read reads
#       `dataset_staging_root` above, which honours the optional override this
#       key cannot see. This alias is removed in the next MAJOR revision of this
#       module's output contract, and it is the one output here that may be removed
#       without a further deprecation window because this block is the window.
# WHY : (3) Trade-offs: the value is read back from the input rather than from a
#       resource, so it reports the composed default even where
#       `dataset_staging_root` is overridden -- which is exactly the defect that
#       motivated the withdrawal and is restated in the description so a reader
#       cannot adopt it unaware. It is deliberately NOT redefined to return the
#       resolved root instead: an alias that answered a different question under the
#       same name would break the consumers it exists to protect, silently, which is
#       worse than the breakage it is standing in for.
output "dataset_source_extract_prefix" {
  description = "DEPRECATED, retained for one compatibility window and removed in the next major revision of this module's output contract. S3 key prefix inside the dataset bucket holding the exported baseline extracts the seed-refresh state reads, echoed verbatim from this module's identically named input. It reports the COMPOSED DEFAULT and cannot see the dataset_staging_root override, so it does not necessarily name the location the tasks read. Replace it with source_extract_prefix from the roots' datasets output, which infra/modules/s3-datasets owns and decides, or with dataset_staging_root above where the location the tasks actually resolve is what is wanted."
  value       = var.dataset_source_extract_prefix
}

# -----------------------------------------------------------------------------
# Sensitivity and deliberate omissions
# -----------------------------------------------------------------------------

# WHY : Assumptions: not one output above is marked `sensitive`, and that is a
#       decision rather than an oversight. Every published value is a resource
#       identifier -- an ARN, a name, a queue URL or a composed object-storage
#       location -- and none of them is a credential, a connection string or a
#       key. Marking a non-secret would suppress it from plan output and from a
#       plain `terraform output`, which makes review and the runbook commands
#       above harder while protecting nothing. An output that did carry a secret
#       would be evidence that the value belongs in Secrets Manager with only a
#       reference published.
# WHY : Alternatives Considered: six further values were considered for
#       publication and each is deliberately absent, recorded here so that none
#       is added back as though it had been forgotten.
#         - No execution ARN and no execution status. Each describes one
#           execution at one moment rather than infrastructure, so Terraform
#           would hold a value that is stale as soon as it is written.
#         - No schedule expression and no schedule ARN. The nightly trigger
#           belongs to infra/modules/eventbridge-scheduler, which consumes the
#           daily ARN; publishing a schedule here would describe a resource this
#           module does not create.
#         - No dataset bucket name, prefix family or ARN. The bucket, its ten
#           generation-dataset prefix families and their retention rule belong to
#           infra/modules/s3-datasets; this module consumes the bucket name and
#           creates nothing inside it.
#         - No task-definition, container-name or cluster value. Those arrive as
#           inputs from infra/modules/ecs-service and infra/modules/ecs-cluster,
#           and re-exporting an input launders ownership: a reader can no longer
#           tell which module decided the value, and the dependency graph reads
#           as though this module had produced it.
#         - No dashboard and no notification topic. Both belong to
#           infra/modules/observability, whose topic this module consumes as the
#           destination its catch handlers publish to.
#         - No Amazon States Language definition string. A consumer that needs to
#           inspect a definition should read main.tf; publishing one would let a
#           root come to depend on the internal shape of a state machine, which
#           is exactly what the module boundary exists to prevent.
