# =============================================================================
# infra/modules/observability/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete public contract of the `observability` module. main.tf
#   declares one encrypted notification topic, one shared terminal access-log
#   bucket, a caller-sized set of CloudWatch log groups, one operations
#   dashboard and THIRTEEN metric alarms. Nothing about them is reachable outside
#   this directory except through the nine outputs below, so this file -- not
#   main.tf -- is what a caller programs against.
#
#   The output NAMES are a ONE-WAY CONTRACT rather than an implementation
#   detail, and the direction matters because it is counter-intuitive. No
#   sibling module references this module: `step-functions-batch` never reads
#   `module.observability.notification_topic_arn`, and `alb` and `s3-datasets`
#   never read the bucket name. The ENVIRONMENT ROOT does the wiring -- it
#   reads an output here and passes the value into another module's input. The
#   three names already consumed that way -- in both infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf, whose observability wiring is identical -- are
#   `managed_log_group_arns`, read inside
#   `data "aws_iam_policy_document" "lambda_logs"`; `access_log_bucket_name`,
#   read twice, by `module "s3_datasets"` and by `module "alb"`; and
#   `notification_topic_arn`, read by `module "step_functions"`. Renaming any of
#   them therefore breaks BOTH environment roots and no sibling module, which
#   is the reason a rename cannot be treated as a local edit.
#
#   WHY the consumers are named by enclosing BLOCK and not by line number: an
#   earlier revision of this header cited four line numbers, and one of them had
#   already drifted -- `notification_topic_arn` had moved nine lines down the
#   root -- so the citation pointed a reader at unrelated configuration. A block
#   label moves with the block it names and is greppable, which a line number is
#   not.
#
#   The other SIX outputs have NO consumer, and saying so is the point of this
#   paragraph. Three consumed and nine published is not an oversight: a reader who
#   sees nine outputs beside a claim that renaming one "breaks both environment
#   roots" would reasonably infer that all nine are wired, and only three are.
#   `notification_topic_name`, `access_log_bucket_arn`, `managed_log_group_names`,
#   `dashboard_name`, `dashboard_arn` and `alarm_arns` are published as
#   OPERATOR AND DISCOVERY contracts: their audience is a human running
#   `terraform output` or a runbook step, not another Terraform block. That is why
#   `docs/runbooks/batch-operations.md` can name a dashboard and a log group
#   without hard-coding either, and why an incident responder can list every alarm
#   this environment created without reading main.tf.
#
#   Trade-offs: the six could be deleted, which would make the output set exactly
#   the wiring surface and let `terraform_unused_declarations` speak for the whole
#   file. They are kept because a module whose alarms and dashboard are
#   unnameable from outside forces every runbook to hard-code a constructed name,
#   and a hard-coded name drifts silently when the naming convention changes,
#   whereas an output that no longer resolves fails the plan. The cost of keeping
#   them is exactly this paragraph: without it the six read as wiring, and a
#   maintainer looking for their callers finds none and cannot tell whether that
#   is the design or a regression. Their `description` text carries the same
#   statement, so the generated table in README.md says it too.
#
# Parameters:
#   None. An `output` block accepts no input. The module's THIRTY-TWO inputs
#   are declared in variables.tf, which carries the type, the description and
#   the domain validation of each; every value published below is read from a
#   resource attribute in main.tf rather than from one of those inputs.
#
#   WHY the count is stated as a measured number: this header said thirty-one
#   while variables.tf declared thirty-two, so the one number in this file a
#   reader could check against another file was the one that was wrong. It is
#   recounted from `grep -c '^variable "' variables.tf` rather than adjusted by
#   one, because an off-by-one corrected by another off-by-one stays wrong.
#
# Return values:
#   Nine outputs. Six are a single string -- the topic ARN and name, the bucket
#   name and ARN, and the dashboard name and ARN. Three are maps: two carry the
#   managed log groups' names and ARNs keyed by the same producer key main.tf
#   iterated, and one carries every alarm ARN keyed by alarm family and
#   instance. Three of the nine are read by an environment root and six are
#   operator-facing only, as the section above sets out. Each `description`
#   states which identifier form it is, what a caller does with it, and -- for
#   the six -- that no Terraform block reads it, because
#   infra/.terraform-docs.yml sets its `read-comments` key to false, so a
#   `description` is the ONLY text that reaches the generated table in
#   README.md -- a rationale written in a comment beside an output never
#   appears there.
#
# Exceptions or errors:
#   * An `output` block raises no error of its own. Its value is either
#     computed from a resource attribute or the plan that would have produced
#     it has already failed for a reason main.tf or variables.tf reports.
#   * A resource address or attribute that main.tf does not declare fails
#     `terraform validate` in the calling root, which is where this module is
#     validated -- it declares no provider of its own.
#   * Nothing below is marked `sensitive`, so every value here appears in plain
#     `terraform plan` output and in `terraform output`. That is the intended
#     consequence and the section on non-sensitivity states why.
#   * A change to a MAP KEY is a breaking change for a caller even when the
#     resource set is untouched, because a caller indexes by that key --
#     `module.observability.managed_log_group_arns["quiesce"]` resolves by key,
#     not by position. Neither TFLint nor `terraform fmt` reports it; only the
#     calling root's own plan does, and only for the keys that root indexes.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the map keys published here are exactly the keys main.tf gave
#     its `for_each` expressions, and those come from the caller. The log-group
#     keys are the four the environment roots compose in
#     `local.lambda_log_group_names` -- quiesce, resume, database_admin and
#     dataset_retention -- and the alarm keys are the service names, queue
#     keys, rotation function names and terminal batch outcomes those roots
#     supply. Re-keying on a composed resource NAME instead would embed
#     var.name_prefix and var.environment in the key, so every `for_each` a
#     caller wrote over one of these maps would rename its instances between
#     dev and prod and destroy and recreate resources whose configuration had
#     not changed.
#   - Trade-offs: a map rather than a list for every collection. A list is
#     shorter to read and it re-indexes: removing one service from
#     var.service_target_group_arn_suffixes would shift every later element, so
#     a caller holding index 2 would silently begin reading a different
#     alarm. A map key survives insertion and removal of its neighbours, and
#     the accepted cost is that a caller must know the key -- which is why each
#     description names the key domain.
#   - Alternatives Considered: publishing one identifier form per resource and
#     letting a caller derive the other. It is the obvious economy and it is
#     rejected because the derivation is string assembly over an account
#     identifier and a region: a caller would have to compose the ARN itself,
#     which puts an account identifier into the calling configuration and adds
#     a caller-identity data source to every root that wanted one. main.tf
#     resolves those two facts once, from the provider the root configured; the
#     section on name-versus-ARN records which consumer needs which form.
#   - Refactoring Rationale: the baseline had no addressable observability
#     surface to publish. app/jcl/POSTTRAN.jcl routes its step output to two
#     spool destinations, its `SYSPRINT DD SYSOUT=*` statement at L26 and its
#     `SYSOUT DD SYSOUT=*` statement at L27, under the `MSGCLASS=0` on its job
#     card at L1 -- a destination named only by a spool class, reachable by an
#     operator at a terminal, and impossible to name in a policy. Its
#     notification target was likewise per-job, `NOTIFY=&SYSUID` at L2 on each
#     of the 38 job cards. Publishing a log-group ARN and a topic ARN as module
#     outputs is what makes both addressable by configuration instead, which is
#     why this file is the contract and not a convenience.
#   - Assumptions: five things are deliberately NOT published, and the section
#     on deliberate absences names each one with its reason, because an absent
#     output is as much a decision as a present one and nothing in the lint
#     gate can report it.
# =============================================================================

# -----------------------------------------------------------------------------
# Nothing below is marked `sensitive`.
#
# WHY : Alternatives Considered: marking the topic ARN -- or the whole file --
#       `sensitive`, on the reading that an identifier naming part of a
#       deployment ought to be withheld. Every value here is an ADDRESS or a
#       NAME, not a credential: holding the topic ARN confers no ability to
#       publish to it, because publication is bounded by the topic's own
#       resource policy in main.tf, which admits the account principal and
#       CloudWatch alarms named under this environment's stem and nothing else.
#       Holding a log-group ARN confers no ability to write to that group,
#       which is granted by the task and function role policies the calling
#       root composes. Withholding the identifier protects nothing that the
#       authorization boundary is not already protecting.
# WHY : Trade-offs: the marking would not merely be redundant, it would cost
#       three things. Sensitivity PROPAGATES, so every expression a caller
#       built from one of these values -- each root's
#       `data "aws_iam_policy_document" "lambda_logs"`, for instance -- would become
#       sensitive too and be redacted in its own right. It would blank these
#       values in `terraform plan`, which is the artifact
#       .github/workflows/infra-ci.yml produces for a human to read: a plan
#       that cannot show WHICH log group a policy grants write access to cannot
#       be reviewed for least privilege, which is the review this module's
#       ARNs exist to make possible. And it would force every calling root to
#       unwrap each value with `nonsensitive()` before use, which is a marking
#       and an immediate un-marking that leaves only the noise behind.
# WHY : Assumptions: what is genuinely sensitive in this module arrives as an
#       INPUT and is not echoed back. var.kms_key_arn and
#       var.alarm_email_endpoints are both listed under deliberate absences
#       below for that reason.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# A name AND an ARN are published for the topic, the bucket, the dashboard and
# the log groups. Neither form substitutes for the other.
#
# WHY : Assumptions: the two forms are not interchangeable at the point of use,
#       and that is a property of the consuming APIs rather than a preference.
#       An IAM policy `Resource` element accepts an ARN and rejects a bare
#       name, which is the whole mechanism by which a task or function role is
#       scoped to one log group instead of to every group in the account -- the
#       least-privilege property each root's `lambda_logs` policy depends on. A
#       CloudWatch metric dimension, a container log-driver `awslogs-group`
#       option and a Logs Insights `SOURCE` clause each accept the bare name
#       and reject an ARN; main.tf's own flow-log widget demonstrates the last
#       of those, querying `SOURCE '<group name>'` from
#       var.vpc_flow_log_group_name. An S3 destination argument -- `alb`'s
#       access-log bucket and `s3-datasets`' audit destination, both wired from
#       here -- takes the bucket name and not its ARN.
# WHY : Trade-offs: publishing both doubles the string count for those
#       resources, and a caller that only ever configures a producer will use
#       half of it. The accepted cost is measured against the alternative,
#       which is not "one fewer output" but "a caller composing the other form
#       itself": that reintroduces an account identifier and a region into the
#       calling configuration, and produces a plausible-looking string that is
#       wrong whenever a partition, a region or a name changes.
# WHY : Assumptions: every value is read from the resource attribute rather than
#       recomposed from the naming locals in main.tf, even where the name is
#       composed there and could be reused directly. Reading the attribute
#       means the published value is the one the service holds, so a name the
#       provider normalised, or one changed in main.tf without this file being
#       touched, cannot leave the two out of step.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# What is deliberately NOT published, and why each absence is a decision.
#
# WHY : Assumptions: var.kms_key_arn is an INPUT this module receives from the
#       `kms` module through the calling root, which already holds it.
#       Republishing it would present this module as the key's owner and invite
#       a caller to source it from here rather than from the module that
#       creates and rotates it, which is where a key policy change has to be
#       reviewed.
# WHY : Assumptions: var.log_retention_days is likewise an input the root sets,
#       and it is one of the few values dev and prod are meant to differ on.
#       Echoing it back would make a root's own variable reachable by two
#       paths, one of which looks authoritative and is not.
# WHY : Alternatives Considered: publishing the notification subscription
#       endpoints, so a caller could confirm what was subscribed. Rejected
#       outright. They are inputs, and an endpoint is a person's contact
#       detail: echoing one back would place it in `terraform output`, in plan
#       output and in state for no consumer at all. variables.tf records the
#       same reasoning against var.alarm_email_endpoints, whose default is
#       empty for exactly this reason. No contact detail of any kind appears in
#       this module.
# WHY : Alternatives Considered: a companion `alarm_names` map keyed
#       identically to `alarm_arns`. Rejected because the ARN already serves
#       both consumers the map exists for: an IAM `Resource` element accepts
#       only the ARN, and a composite alarm's rule identifies a child alarm by
#       ARN as readily as by name. A second identically-keyed map would double
#       the surface and add a second place for an alarm family to be forgotten
#       when one is added to main.tf.
# WHY : Assumptions: no output publishes an aggregate the caller cannot act on
#       -- there is no count, no list of alarm states and no "all resources"
#       object. TFLint's unused-declaration rule covers variables and locals
#       and does NOT report an unused output, so an output nobody consumes
#       cannot be caught mechanically; every one below therefore names its
#       consumer in its own description, and an identifier that could not name
#       one was left out rather than published for symmetry.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# The notification topic. One per environment, and the target every alarm in
# this module already publishes to.
#
# WHY : Refactoring Rationale: the baseline declared its notification target
#       per job -- `NOTIFY=&SYSUID` at app/jcl/POSTTRAN.jcl L2, repeated on all
#       38 job cards -- so redirecting the whole alerting surface meant editing
#       38 members. Publishing one topic identifier is what reduces that to one
#       subscription change, and it is the property the ARN output exists to
#       deliver rather than an incidental convenience.
# -----------------------------------------------------------------------------

output "notification_topic_arn" {
  description = "ARN of the single per-environment notification topic that every alarm in this module already publishes both its ALARM and its OK transition to. A calling root passes this value into the step-functions-batch module's notification_topic_arn input, where it becomes the target the nightly chain's Catch path publishes a failure to, and uses it as the alarm_actions target of any alarm authored outside this module. Because one topic serves the whole environment, muting or redirecting that environment's alerting is a change to this topic's subscriptions rather than to every alarm."
  value       = aws_sns_topic.alerts.arn
}

output "notification_topic_name" {
  description = "Bare name of the notification topic, carrying neither the account nor the region part of an ARN. CloudWatch dimensions the AWS/SNS metric family on TopicName, so this is the value a caller needs to place this topic's own delivery counts on a board or to alarm on its failed deliveries -- the signal that reports a failure in the alerting path itself, which no alarm publishing THROUGH that path can report. An ARN is not accepted as that dimension. No Terraform block in this repository reads this output: it is an operator and board-author contract, published so the topic can be named from `terraform output` rather than reconstructed from a naming convention."
  value       = aws_sns_topic.alerts.name
}

# -----------------------------------------------------------------------------
# The shared terminal access-log destination.
#
# WHY : Assumptions: one destination is shared by the load balancer and the
#       dataset bucket rather than one being created per producer, so the two
#       bounded encryption and self-logging exceptions main.tf records on it
#       are confined to a single reviewable place. Publishing both its name and
#       its ARN is what lets the producers be configured by name while an
#       access grant is still scoped by ARN.
# -----------------------------------------------------------------------------

output "access_log_bucket_name" {
  description = "Name of the shared terminal access-log destination this module owns. A calling root passes it into the alb module's access_logs_bucket input and the s3-datasets module's access_log_bucket_name input, so both delivery services write into one destination whose public-access block, encryption, versioning, lifecycle rules and exact-source bucket policy are reviewed together. Both of those arguments take a bucket name and reject an ARN."
  value       = aws_s3_bucket.access_logs.bucket
}

output "access_log_bucket_arn" {
  description = "ARN of that same access-log destination, for an IAM policy Resource element -- granting an operator or a log-analysis task read access to the delivered records without granting it across every bucket in the account. The two producer modules take the bucket name output instead, because an IAM Resource element does not accept a bare bucket name and an S3 destination argument does not accept an ARN. No Terraform block in this repository reads this output: it is published for the operator or log-analysis grant described above, which is written outside this repository."
  value       = aws_s3_bucket.access_logs.arn
}

# -----------------------------------------------------------------------------
# The log groups this module owns, in both forms, keyed by producer.
#
# WHY : Assumptions: the keys are var.log_group_names' own keys, which both
#       environment roots compose in `local.lambda_log_group_names` as quiesce,
#       resume, database_admin and dataset_retention. The first two are the
#       direct successors of the baseline's operator-command steps: app/jcl/
#       CLOSEFIL.jcl runs `EXEC PGM=SDSF` at L22 and sends its output to
#       `ISFOUT` and `CMDOUT` at L23-L24, with the five `CEMT SET FIL(...) CLO`
#       commands it issues at L26-L30, and app/jcl/OPENFIL.jcl mirrors it. A
#       destination reached that way is named only by a spool class and is
#       therefore impossible to name in a policy. The key here is what makes
#       the target destination nameable, so it is part of the contract rather
#       than an internal label.
# WHY : Trade-offs: the ARN map is published with the all-streams `:*` suffix
#       removed, so a caller appends it unconditionally -- which is exactly
#       what both environment roots do in their `lambda_logs` policy, where a
#       logs:CreateLogStream/logs:PutLogEvents statement requires the suffixed
#       form. The provider already trims that suffix from a log group's `arn`
#       attribute, and `trimsuffix` is applied anyway so that the PUBLISHED
#       form is defined here rather than inherited: the constraint in
#       versions.tf admits any later 6.x provider minor, and the matching data
#       source returned the suffixed form until the provider's 4.0 release, so
#       the two forms are not hypothetical. Without the normalisation a form
#       change would silently produce a doubled suffix in a caller's policy and
#       an IAM statement matching no resource.
# -----------------------------------------------------------------------------

output "managed_log_group_names" {
  description = "Map of producer key to the exact CloudWatch log-group name for the groups this module creates, keyed as var.log_group_names is keyed. A caller uses a name wherever an ARN is not accepted: a container log-driver awslogs-group option, a Logs Insights SOURCE clause, and the LogGroupName metric dimension. Each value is read from the created group rather than re-derived from a path convention, so it is the destination the producer actually writes to. No Terraform block in this repository reads this output -- the roots wire the ARN map instead, for an IAM Resource element -- so this one is a discovery contract for a Logs Insights query or a console link."
  value = {
    for key, group in aws_cloudwatch_log_group.managed : key => group.name
  }
}

output "managed_log_group_arns" {
  description = "Map of the same producer keys to log-group ARNs, published without the all-streams :* suffix so a caller appends it unconditionally. Both environment roots index this map inside their lambda_logs IAM policy document to scope logs:CreateLogStream and logs:PutLogEvents to one group per function role rather than to every group in the account, which is what makes least privilege reachable at log-group granularity instead of by wildcard. Keys match managed_log_group_names exactly, so the two maps are indexed with one key set."
  value = {
    for key, group in aws_cloudwatch_log_group.managed : key => trimsuffix(group.arn, ":*")
  }
}

# -----------------------------------------------------------------------------
# State-machine execution-failure controls.
#
# WHY : Assumptions: these two maps exist so that the consumption this module now
#       performs is VISIBLE from outside it. infra/modules/step-functions-batch
#       publishes each machine's execution log-group name and states that this
#       module attaches a metric filter and a log-based alarm to it; publishing
#       what was actually created lets a root, a runbook or a reviewer confirm the
#       pairing without reading either module's resource blocks.
# -----------------------------------------------------------------------------

output "state_machine_metric_filter_names" {
  description = "Map of state-machine key to the name of the metric filter this module created over that machine's execution log group. Empty when the caller passed no state_machine_log_group_names. Each filter counts terminal ExecutionFailed and ExecutionTimedOut events -- an abort is excluded, as it is for the service-level metric -- into the CardDemo namespace under state_machine_execution_failures_<key>."
  value = {
    for key, filter in aws_cloudwatch_log_metric_filter.state_machine_execution_failure : key => filter.name
  }
}

output "state_machine_execution_failure_alarm_names" {
  description = "Map of state-machine key to the name of the alarm this module created on that machine's execution-failure metric, notifying the module's own topic. Empty when the caller passed no state_machine_log_group_names. An operator scoping a composite alarm or an escalation reads this map rather than composing an alarm name from a convention."
  value = {
    for key, alarm in aws_cloudwatch_metric_alarm.state_machine_execution_failure : key => alarm.alarm_name
  }
}

# -----------------------------------------------------------------------------
# The operations dashboard.
#
# WHY : Assumptions: the dashboard is published by name because a name is what a
#       procedure can carry. A console deep-link URL embeds an account
#       identifier and a region, neither of which belongs in a committed
#       document, so a runbook that has to send a reader to this board names it
#       and lets the reader's own console resolve it.
# -----------------------------------------------------------------------------

output "dashboard_name" {
  description = "Name of the operations dashboard main.tf composes, which is the argument both a console deep-link and the CloudWatch GetDashboard call take. It is the identifier a deploy or batch-operations procedure uses to send a reader to the board, because a console URL would carry an account identifier and a region and neither may be committed to this repository. No Terraform block reads this output; the runbook step that needs the board is its consumer, which is exactly why the name is published rather than left to be constructed."
  value       = aws_cloudwatch_dashboard.operations.dashboard_name
}

output "dashboard_arn" {
  description = "ARN of that dashboard, for an IAM policy Resource element granting a read-only operator access to this board alone rather than to every dashboard in the account. A deep-link and an API call both take the name output instead, so neither form makes the other redundant. No Terraform block reads this output: the read-only grant it exists for is authored outside this repository, so it is an operator contract rather than wiring."
  value       = aws_cloudwatch_dashboard.operations.dashboard_arn
}

# -----------------------------------------------------------------------------
# Every alarm ARN, in one map keyed by family and instance.
#
# WHY : Assumptions: the key is `<family>/<instance>` for an iterated family and
#       the bare family name for a single-instance alarm, and the family prefix
#       is load-bearing rather than decorative. service_unhealthy,
#       service_no_healthy_targets and service_5xx all iterate the SAME input,
#       var.service_target_group_arn_suffixes, so their key sets are identical:
#       without the prefix, `merge` would collapse three alarms per service
#       into one entry and silently keep whichever argument came last, which is
#       a lost alarm that nothing reports. reply_queue_age and work_queue_age
#       have the same property in weaker form -- both compose the same alarm
#       name suffix from disjoint subsets of var.queue_names -- so one grammar
#       is applied to all nine iterated families rather than only where a
#       collision exists today.
# WHY : Trade-offs: one map over all FOURTEEN families rather than one output per
#       family. Fourteen outputs would let each carry its own description into
#       the generated README, which is what the deliberate-absence section
#       argues for elsewhere; it is declined here because nine of the fourteen
#       are caller-sized, so their instance count is unknown to this file and
#       only a map can express them at all. Two more are count-gated, so an
#       output per family would additionally have to publish a null for each
#       closed gate. The accepted cost is that the fourteen families share one
#       description, and the key grammar above is what keeps the map
#       self-describing in its place.
# WHY : Assumptions: this map must list every alarm family main.tf declares. An
#       alarm added there and omitted here is invisible to both gates --
#       `terraform validate` passes because the map is still well-formed, and
#       TFLint passes because the output still carries a description -- and it
#       surfaces only as a caller unable to reach an alarm it can see in the
#       console.
# -----------------------------------------------------------------------------

output "alarm_arns" {
  description = "Map of alarm ARNs covering all THIRTEEN alarm families this module creates, keyed <family>/<instance> for the eight families iterated per service, per queue, per rotation function or per terminal batch outcome, and by bare family name for the five single-instance alarms. Three of those five are unconditional (api_5xx, aurora_cpu, aurora_capacity); the remaining two are present only when their gate is open -- aurora_connections when database_connection_threshold is set, and cloudfront_5xx when a distribution id is supplied and the region is us-east-1 -- so their keys are absent rather than null when they are not created. A caller composes a composite alarm over a chosen subset of families, attaches an action beyond this module's notification topic, or scopes an IAM Resource element to these alarms -- each of which needs the ARN and none of which then has to rediscover an alarm by its composed name. No Terraform block in this repository reads this output today -- every alarm here already routes to this module's own notification topic, so no root has needed to attach a second action -- which makes it a discovery contract: it is how an incident responder enumerates what this environment actually alarms on without reading main.tf."
  value = merge(
    {
      for service, alarm in aws_cloudwatch_metric_alarm.service_unhealthy :
      "service_unhealthy/${service}" => alarm.arn
    },
    {
      for service, alarm in aws_cloudwatch_metric_alarm.service_no_healthy_targets :
      "service_no_healthy_targets/${service}" => alarm.arn
    },
    {
      for service, alarm in aws_cloudwatch_metric_alarm.service_5xx :
      "service_5xx/${service}" => alarm.arn
    },
    {
      for queue, alarm in aws_cloudwatch_metric_alarm.dead_letter_depth :
      "dead_letter_depth/${queue}" => alarm.arn
    },
    {
      for queue, alarm in aws_cloudwatch_metric_alarm.reply_queue_age :
      "reply_queue_age/${queue}" => alarm.arn
    },
    {
      for queue, alarm in aws_cloudwatch_metric_alarm.work_queue_age :
      "work_queue_age/${queue}" => alarm.arn
    },
    {
      for function_name, alarm in aws_cloudwatch_metric_alarm.rotation_failure :
      "rotation_failure/${function_name}" => alarm.arn
    },
    {
      for outcome, alarm in aws_cloudwatch_metric_alarm.batch_failure :
      "batch_failure/${outcome}" => alarm.arn
    },

    # WHY : Assumptions: the per-machine execution-failure family is merged here for
    #       the same reason the two families below it are -- this output promises EVERY
    #       alarm, and a family created by main.tf but absent from this map is an alarm
    #       a caller composing a composite alarm or an IAM Resource element silently
    #       does not cover. It is caller-sized, keyed by the state-machine key the root
    #       supplied, and empty when a root composes no state machine.
    {
      for machine, alarm in aws_cloudwatch_metric_alarm.state_machine_execution_failure :
      "state_machine_execution_failure/${machine}" => alarm.arn
    },
    {
      api_5xx         = aws_cloudwatch_metric_alarm.api_5xx.arn
      aurora_cpu      = aws_cloudwatch_metric_alarm.aurora_cpu.arn
      aurora_capacity = aws_cloudwatch_metric_alarm.aurora_capacity.arn
    },

    # WHY : Refactoring Rationale: these two families were MISSING from this map while
    #       main.tf created them, so two of the families the module built were absent
    #       from what it published. The omission is worse than a documentation gap, because the stated
    #       purpose of this output is to let a caller compose a composite alarm or scope
    #       an IAM Resource element over "all" alarms: a caller doing either got a set
    #       that silently excluded database connection exhaustion and CloudFront error
    #       rate, and no expression anywhere would have failed to reveal it.
    # WHY : Assumptions: both are merged CONDITIONALLY rather than read directly,
    #       because both are count-gated resources and a bare `.arn` on a count-indexed
    #       resource is an error. aurora_connections exists only when
    #       database_connection_threshold is set; cloudfront_5xx only when a
    #       distribution id is supplied AND the region is us-east-1, which is where
    #       CloudFront publishes its metrics. When a gate is closed the key is ABSENT
    #       from the map rather than present with a null, so a caller iterating the map
    #       never has to filter nulls and `lookup` reports the truth.
    length(aws_cloudwatch_metric_alarm.aurora_connections) > 0 ? {
      aurora_connections = aws_cloudwatch_metric_alarm.aurora_connections[0].arn
    } : {},
    length(aws_cloudwatch_metric_alarm.cloudfront_5xx) > 0 ? {
      cloudfront_5xx = aws_cloudwatch_metric_alarm.cloudfront_5xx[0].arn
    } : {},
  )
}
