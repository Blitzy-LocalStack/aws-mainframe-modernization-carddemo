# =============================================================================
# infra/modules/observability/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the cross-cutting operational surface for CardDemo: explicitly
#   retained and customer-key-encrypted log groups, a shared access-log
#   destination, one encrypted notification topic, a CloudWatch dashboard, and
#   metric alarms over the exact identifiers supplied by the environment root.
#
#   What it replaces is the ADDRESSABILITY of the baseline's operational
#   signals rather than their content. The batch tree declares 116 `SYSOUT=*`
#   destinations and 98 per-step output declarations across the 38 members of
#   app/jcl -- 80 `SYSPRINT` and 18 `SYSOUT` -- and each was read by an
#   operator opening a job log; app/jcl/POSTTRAN.jcl declares
#   `//STEP15 EXEC PGM=CBTRN02C` at L23 with `//SYSPRINT DD SYSOUT=*` at L26
#   and `//SYSOUT DD SYSOUT=*` at L27, and app/proc/REPROC.prc carries its own
#   pair at L21-L22. Here that output lands in a queryable group and a failure
#   publishes to a topic instead of waiting to be opened.
#
# Parameters:
#   variables.tf owns the complete input contract -- twelve required and
#   sixteen optional inputs, in five categories:
#     naming and tagging ......... `name_prefix`, `environment`, `tags`
#     the log encryption key ..... `kms_key_arn`
#     retention and group set .... `log_retention_days`, `log_group_names`,
#                                  `access_log_bucket_force_destroy`
#     notification ............... `alarm_email_endpoints`
#     producer identifiers and
#     detection defaults ......... the cluster, load-balancer, target-group,
#                                  API, database, queue, state-machine,
#                                  flow-log and distribution identifiers, plus
#                                  the shared evaluation window and the four
#                                  named thresholds
#   Naming, retention and alarm policy are module settings; every metric
#   dimension is a producer output passed by the root. Nothing is read from the
#   ambient environment and nothing is generated inside this module.
#
# Return values:
#   outputs.tf publishes the notification topic ARN and name, the access-log
#   bucket name and ARN, the created log-group names and ARNs, the dashboard
#   name and ARN, and the merged map of alarm ARNs, so a root can wire a
#   producer or an operator runbook without reconstructing a name or an ARN.
#
# Declarations:
#   Purpose for every declaration in this file is stated here, in the one block a
#   language with no docstring construct has available, so that each declaration
#   below carries rationale and nothing else.
#   - `data aws_partition` / `aws_region` / `aws_caller_identity`: exactly three
#     data sources, and every one of them is referenced below.
#   - `aws_cloudwatch_log_group.managed`: one group per producer that owns no
#     log-group resource of its own, named exactly as the root supplies it.
#   - `aws_sns_topic.alerts`: the single destination every alarm below publishes
#     to, and the successor to the baseline's per-job operator notification.
#   - `data aws_iam_policy_document.alerts`: the topic's resource policy, built as
#     a document rather than inline JSON.
#   - `aws_sns_topic_subscription.email`: one subscription per endpoint the root
#     supplies, and none by default.
#   - `aws_s3_bucket.access_logs` and its companion configuration resources: one
#     object destination for the request-level access records that do not go to
#     CloudWatch Logs -- the load balancer's own access log and the dataset
#     bucket's server-access log.
#   - `data aws_iam_policy_document.access_logs`: the destination's resource
#     policy -- one blanket transport Deny followed by three narrowly-scoped
#     delivery Allows.
#   - `locals` (dashboard widgets): the widget list, composed in `locals` and
#     rendered by the single dashboard resource that follows it.
#   - Every `aws_cloudwatch_metric_alarm`: `evaluation_periods`,
#     `datapoints_to_alarm` and `period` together state the evaluation window, and
#     `treat_missing_data` is set explicitly on every alarm and takes one of two
#     values. Each alarm's operational meaning -- the condition it fires on, the
#     question it answers and the action it calls for -- is:
#     - `service_unhealthy`: the load balancer reports an unhealthy target for a
#       service. Is this service's task actually serving traffic? Replace the task
#       or roll back the image revision the `version` metric tag names.
#     - `service_no_healthy_targets`: a service's target group holds no healthy
#       target at all. Is this service serving, as distinct from serving badly?
#       Read the task's stopped reason and its log stream, then correct the image,
#       the configuration or the health-check contract.
#     - `service_5xx`: target-generated server-error responses reach the
#       configured count within one evaluation period. Is this service failing
#       requests, as distinct from being unreachable? Inspect that service's
#       correlated log lines, then roll back or replace the failing task.
#     - `api_5xx`: the HTTP API returns server-error responses at the configured
#       count. Is the failure inside a service or between the edge and the
#       service? Compare against the per-service pair above and investigate the
#       integration and target registration rather than the application when only
#       this one is in alarm.
#     - `dead_letter_depth`: a dead-letter queue holds a visible message. Has any
#       message exhausted every receive attempt the system offers? Inspect that
#       message, correct the cause, and only then redrive it.
#     - `reply_queue_age`: the oldest message waiting on a reply queue reaches the
#       baseline's request/reply expiry interval. Is the requester consuming
#       replies, or are they ageing past the point at which they were meant to be
#       discarded? Inspect the waiting consumer and correlate the stale message
#       before its `expiresAt` is enforced.
#     - `work_queue_age`: the oldest message waiting on a primary work queue has
#       been waiting longer than the configured interval. Is the consumer for this
#       queue still taking work off it? Inspect that consumer's task and log
#       stream, and check whether its service has any healthy target.
#     - `rotation_failure`: a Secrets Manager rotation function reported an
#       invocation error. Did a scheduled credential rotation fail and leave the
#       secret on its previous version? Inspect that function's encrypted log
#       group and reconcile the affected secret version before the next interval.
#     - `batch_failure`: the daily state machine reports a failed or a timed-out
#       execution. Did the nightly chain FAIL, as distinct from completing with
#       business-rule rejects? Read the failed state's step-ledger row and redrive
#       from that state once the cause is corrected.
#     - `aurora_cpu`: cluster processor utilisation stays at or above the
#       configured percentage across the whole evaluation window. Is compute
#       pressure sustained rather than momentary? Read this against the capacity
#       and connection series on the dashboard before changing the environment's
#       maximum capacity units.
#     - `aurora_capacity`: serverless database capacity reaches the maximum the
#       environment root itself configured. Has the cluster exhausted the capacity
#       it is permitted to add? Review the workload, and raise the declared
#       maximum only where the pressure is expected.
#
# Exceptions or errors:
#   - `kms_key_arn` is required and rejects null. variables.tf records that
#     admitting null to select the services' managed encryption was considered
#     and declined, so there is no unencrypted path to fall back to -- which is
#     what makes encryption of these groups and this topic unskippable instead
#     of merely default. The material policy gate checks for the key.
#   - An empty input collection creates no resource of that family and raises
#     no error: empty `log_group_names` creates no group, empty `queue_names`
#     no queue alarm, empty `service_target_group_arn_suffixes` no per-service
#     alarm, empty `rotation_lambda_function_names` no rotation alarm. The
#     alarm is then absent rather than broken, which is why each of those
#     inputs states it in its own description.
#   - A duplicate log-group name or access-log bucket name is an apply-time
#     ownership conflict, which is why this module creates only groups
#     explicitly listed by the root and never recreates groups owned by ECS,
#     API Gateway, Step Functions or the network module.
#   - CloudFront publishes its distribution metrics in one fixed region only,
#     so CloudFront appears here as a dashboard widget and never as an alarm.
#     An alarm on such a metric created under any other provider region
#     receives no datapoint and stays in INSUFFICIENT_DATA -- configured in
#     appearance, unable to fire in fact. versions.tf declares no
#     `configuration_aliases` precisely because of that choice, so adding a
#     CloudFront alarm requires changing both files together.
#   - Input validation rejects a malformed metric dimension before any resource
#     is evaluated, so a full ARN passed where a load balancer's ARN suffix
#     belongs fails while planning rather than producing a permanently empty
#     alarm.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the baseline already expressed structured logging
#     WITH a correlation key, and already centralised its emission, so this
#     module re-expresses an existing concept queryably rather than introducing
#     a new one. app/app-authorization-ims-db2-mq/cpy/CCPAUERY.cpy declares
#     `01 ERROR-LOG-RECORD.` at L19 with eleven fields summing to 122 bytes
#     (L20-L40): an application and a program name, a level whose four values
#     are declared at L26-L29, a subsystem whose six are at L31-L36, two code
#     slots, a message, and at L40 `ERR-EVENT-KEY PIC X(20)` -- the baseline's
#     own correlation identifier. app/app-authorization-ims-db2-mq/cbl/
#     COPAUA0C.cbl performs one `9500-LOG-ERROR` paragraph from fourteen call
#     sites. What that record could not do is answer a question across records,
#     which is the single property added here. The field-level mapping, and the
#     re-basing of the level and subsystem domains, belong to
#     docs/architecture/observability.md and are cited rather than restated.
#   - Assumptions: this directory is a reusable MODULE and not a Terraform root,
#     so it declares no `provider` and no `backend` block. A provider block here
#     would fix region and credentials at the module instead of at the
#     environment, so dev and prod could no longer differ, and it would displace
#     the calling root's `default_tags` from every group, alarm and topic below;
#     a `backend` block is valid only in a root, so including one would not be
#     merely redundant -- it would fail `init` for every root that calls this
#     module. versions.tf records the same reasoning for the version contract.
#   - Assumptions: every monitored identifier arrives through the environment
#     root, so no `module.` reference appears anywhere in this file. Reading a
#     sibling module directly would couple the two and end their independent
#     reuse; deriving a name from `name_prefix` instead would duplicate naming
#     rules the producer module owns, after which a rename leaves a
#     syntactically valid alarm pointed at a dimension no metric publishes --
#     which CloudWatch reports as silence rather than as a wiring error.
#   - Trade-offs: one topic serves the whole environment rather than one per
#     alarm or per service, and two operational properties decided it.
#     Subscription policy is configured ONCE, so adding an alarm does not also
#     require remembering to subscribe to it -- and an alarm nobody is
#     subscribed to is indistinguishable from one that never fires. And an
#     environment's entire alerting surface can be muted or redirected
#     ATOMICALLY during a maintenance window by changing one subscription,
#     instead of by touching every alarm and then having to restore them all.
#     The baseline makes the contrast concrete: it declares `NOTIFY=` on all 38
#     of its job cards, so a push notification is not invented here but
#     preserved and re-addressed -- while redirecting the whole surface there
#     meant editing 38 job cards. Alarm names and descriptions still carry the
#     service and the signal, so one topic does not cost the reader specificity.
#   - Trade-offs: every alarm below rests on a STRUCTURAL condition -- a fact
#     about how the system is built -- and never on a target figure. The
#     repository defines no service-level objectives and none is invented here,
#     so this file states no latency, throughput, availability, error-budget or
#     recovery objective. A threshold arriving as an input is a detection
#     default an operator may tune in either direction without breaking any
#     promise; an invented objective would be indistinguishable in form from a
#     derived one, and a reader would have no way to tell which they were
#     reading. Each alarm therefore records its condition, the question it
#     answers and the action it enables, in its `alarm_description` as well as
#     beside its arguments, so the console shows an operator what this file
#     shows a maintainer.
#   - Assumptions: this file is authored and statically validated -- formatted,
#     validated, linted, drift-checked and policy-scanned. Nothing here reports
#     on a provisioned stack: no dashboard has rendered and no alarm has fired.
#     Applying it against a live account is an operator action outside this
#     scope, so no comment below should be read as an observation of one.
#
# WHAT this module deliberately does NOT declare. Each is a considered
# rejection rather than an omission, recorded because an absence is otherwise
# unreadable:
#   - No self-managed Prometheus or Grafana. Metrics are exported inside each
#     service by micrometer-registry-prometheus, and ECS Container Insights and
#     native service metrics feed the dashboard below. Alternatives Considered:
#     a self-hosted pair, rejected because it adds two stateful services to
#     operate, patch, back up and scale, against a guiding principle that
#     prefers a managed service unless cost or a hard constraint dictates
#     otherwise -- and here neither does.
#   - No Redis or ElastiCache metric surface. Application-level caching is out
#     of scope and the baseline has no cache tier, so introducing one would
#     create a cache-invalidation problem that does not currently exist.
#   - No streaming platform: no Kafka and no Kinesis, hence no consumer-lag,
#     partition or stream-iterator metric. The requirement these queues serve is
#     request/reply, which they satisfy without a log-structured broker.
#   - No multi-region observability. One region and three availability zones,
#     so no cross-region log replication and no cross-region alarm aggregation.
#   - No X-Ray or ADOT resource. Tracing is a cross-cutting requirement, but
#     this module's scope is log groups, dashboards, alarms and the topic;
#     tracing instrumentation belongs to the service task definitions in
#     ecs-service and to the state machine in step-functions-batch, which
#     already declares its own tracing input. The one tracing-adjacent argument
#     here is `tracing_config` on the topic, which is a property of that
#     resource rather than an instrumentation graph.
#   - No log group for the CSD deployment audit trail. app/jcl/CBADMCDJ.jcl
#     declares `//OUTDD DD SYSOUT=*` at L31 beside `//SYSPRINT DD SYSOUT=*` at
#     L32, under `EXEC PGM=DFHCSDUP` at L27 -- the only `OUTDD` in the tree.
#     Its target counterpart is the CI job log plus the uploaded plan artifact,
#     so no group is created for it and none should be looked for.
#   - Nothing here retires or replaces the existing operational model. The 38
#     job cards keep their output class, verbosity and notification
#     declarations, and the 98 per-step output declarations keep routing where
#     they route today: this migration adds a path, it does not remove one.
# =============================================================================

# WHY : Assumptions: the partition, region and account are read from the resolved
#       provider rather than accepted as inputs or written as literals. That is
#       what lets the composed ARNs and service principals below be correct in any
#       partition and region the calling root configures, while keeping no account
#       identifier, no ARN and no deployment region in this file -- a
#       no-secrets-in-source constraint the project holds structurally rather than
#       by review. Trade-offs: reading them means `plan` needs resolved
#       credentials, whereas literals would not; that cost is accepted because a
#       committed account identifier is not.
#       Assumptions: the set is kept to what is used, because the lint gate reports
#       an unreferenced data source in the same way it reports an unused variable.
data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  # WHY : Assumptions: one stem composed once and reused by every group, topic,
  #       dashboard and alarm name below, so the whole observability surface shares
  #       a single greppable identity and an alarm's own name says which
  #       environment raised it. Composing it per resource would let one name
  #       drift from the rest without anything reporting it.
  name_stem = "${var.name_prefix}-${var.environment}"

  # WHY : Assumptions: the account identifier makes the bucket globally unique
  #       without committing one to source, while omitting the region keeps the
  #       name inside S3's 63-character ceiling at the maximum permitted prefix.
  access_log_bucket_name = "${local.name_stem}-logs-${data.aws_caller_identity.current.account_id}"

  # WHY : Assumptions: logical keys, rather than queue-name string matching,
  #       identify queue roles. Queue names may change without silently moving
  #       an alarm from a dead-letter queue to a live queue or vice versa.
  dlq_queue_names = {
    for key, name in var.queue_names : key => name
    if endswith(key, "_dlq")
  }

  reply_queue_names = {
    for key, name in var.queue_names : key => name
    if endswith(key, "_reply")
  }

  # WHY : Refactoring Rationale: the two sets above left the PRIMARY work queues
  #       -- the request queues and the error queue -- with no alarm of any kind,
  #       and the gap is not covered transitively by the dead-letter alarm. A
  #       message reaches a dead-letter queue only after its source queue's
  #       redrive policy exhausts its receives, and exhausting receives requires
  #       a consumer to RECEIVE and fail. A stopped or wedged consumer never
  #       receives, so nothing redrives, the dead-letter queue stays empty and
  #       its alarm stays OK while work piles up on the live queue. That is the
  #       failure this third set exists to make alertable.
  #       Assumptions: membership is decided by EXCLUSION -- neither suffix --
  #       rather than by listing the four queue keys. Listing them would put the
  #       messaging design's queue inventory in a second place, where it could
  #       disagree with infra/modules/sqs; excluding the two roles that already
  #       have their own alarm leaves exactly the queues nothing else watches, and
  #       a queue added by that module gains this alarm without an edit here.
  work_queue_names = {
    for key, name in var.queue_names : key => name
    if !endswith(key, "_dlq") && !endswith(key, "_reply")
  }

  # WHY : Trade-offs: the terminal execution metrics are iterated from a map
  #       rather than written as separate resources, so each arrives under a
  #       distinguishable name from one definition and a further terminal outcome
  #       would be added by extending this map. The keys are the snake_case
  #       Terraform identities and the values are the service's own metric names;
  #       keeping the two apart is what lets an alarm name read as a hyphenated
  #       suffix while the dimension stays exactly what the service publishes.
  #       Refactoring Rationale: `throttled` was added after the set of two was
  #       found to leave a genuine unalerted failure. A throttled execution is one
  #       the service REFUSED to start, so the night's chain does not run at all
  #       and yet no execution fails and none times out -- both watched metrics
  #       stay at zero and the chain's absence is invisible. It is the same class
  #       of gap the comment on the failure alarm records for a chain that never
  #       started, differing in that this one publishes a metric and can therefore
  #       be watched.
  #       Alternatives Considered: adding `ExecutionsAborted` as a fourth entry.
  #       Rejected because an abort is ordinarily deliberate -- an operator or an
  #       automation stopped the execution -- so alarming on it would page whoever
  #       had just performed the stop, which is the notification pattern this
  #       module's own preamble refuses. An abort nobody intended still shows on
  #       the batch dashboard widget, so it is observable without being alertable.
  batch_failure_metrics = {
    failed    = "ExecutionsFailed"
    timed_out = "ExecutionsTimedOut"
    throttled = "ExecutionThrottled"
  }

  # WHY : Assumptions: the caller's map is the BASE and these two keys are layered
  #       over it, so a root cannot accidentally drop the module attribution while
  #       setting its own tags. This is a third layer rather than a replacement:
  #       the calling root's provider `default_tags` already reach every taggable
  #       resource here, `var.tags` is what the root adds for this module, and
  #       these two identify which module created a resource an operator found.
  tags = merge(var.tags, {
    Module      = "observability"
    Environment = var.environment
  })

  # WHY : Assumptions: the account principal is composed rather than written,
  #       because the administrative statement on the topic must name a concrete
  #       principal and the only correct one is the account the provider resolved
  #       to. Trade-offs: omitting that statement entirely was considered; it is
  #       kept because a resource policy that names only a service principal can
  #       leave the topic unmanageable by the account that owns it.
  account_root_arn = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root"

  # WHY : Assumptions: every alarm below is named from `name_stem`, so this prefix
  #       matches all of them and nothing else -- which is what lets the topic
  #       policy bound publication to this environment's own alarms by source ARN
  #       instead of accepting any alarm the service principal speaks for. The
  #       coupling is deliberate but silent, so it is recorded here: an alarm named
  #       outside this stem would be refused publication by that condition.
  alarm_arn_prefix = "arn:${data.aws_partition.current.partition}:cloudwatch:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:alarm:${local.name_stem}-"
}

# -----------------------------------------------------------------------------
# Log groups owned by this module
#
# WHY : Alternatives Considered: creating a group for every producer in the
#       stack. Rejected on an ownership boundary that is a concrete apply-time
#       failure rather than a preference. Each of the eight container services
#       already creates its own group through the ecs-service module, the
#       nightly chain creates one through step-functions-batch, the edge creates
#       its access-log group through api-gateway-http, and the flow-log group
#       belongs to the network module. Declaring any of those here would either
#       collide on the name during apply or -- if the name differed by a
#       character -- leave an empty duplicate that still bills, and an operator
#       who opened the empty one would reasonably conclude the producer had gone
#       silent. A resource stays with the module that owns its lifecycle, so
#       this module parameterises retention and dashboards the rest.
#       Assumptions: what is left is the producer with no Terraform resource of
#       its own -- the batch glue functions the environment root names. Two of
#       them are the direct successors of the baseline's operator-command steps:
#       the eight `EXEC PGM=SDSF` steps declare their own `ISFOUT` and `CMDOUT`
#       spool destinations across FIVE files, not the two dedicated ones, and
#       app/jcl/CLOSEFIL.jcl shows the shape at L22-L24 with its five
#       `CEMT SET FIL(...) CLO` commands at L26-L30, mirrored by
#       app/jcl/OPENFIL.jcl. Their target counterparts quiesce and resume
#       online writes, so they need a log destination for exactly the reason the
#       SDSF steps did. Without an authored group they would be created
#       implicitly on first write, with unlimited retention and no
#       customer-managed key -- which is the failure this resource exists to
#       prevent.
#       Trade-offs: every COBOL business step declared TWO output streams --
#       `SYSPRINT` for runtime and utility messages and `SYSOUT` for `DISPLAY`
#       output -- and a Fargate task collapses stdout and stderr into ONE
#       CloudWatch stream, so the two merge here. That is an accepted,
#       intentional consequence rather than an oversight: the ordering between
#       the two streams becomes observable where it previously was not, and a
#       reader who expects two destinations per step will find one. The
#       compensating property is that a merged stream is timestamp-ordered and
#       queryable as a single sequence, which is what a two-spool split cannot
#       be.
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "managed" {
  # WHY : Trade-offs: `for_each` over the supplied map rather than one resource
  #       per producer. The set then tracks the producer set automatically and a
  #       key names each group in state, whereas copied resources drift the
  #       moment a further producer appears and a `count` index renumbers every
  #       group after any removal, destroying and recreating groups whose names
  #       never changed.
  for_each = var.log_group_names

  # WHY : Assumptions: the full name is taken verbatim from the input rather
  #       than composed from a suffix, because a managed producer writes only to
  #       its own service-defined path and cannot be redirected. Composing a
  #       path here would create a second, empty group beside the one the
  #       producer actually writes.
  name = each.value

  # WHY : Refactoring Rationale: retention is explicit rather than inheriting
  #       the unlimited service default. In the baseline 29 of the 38 jobs
  #       declare MSGCLASS=0, 8 declare MSGCLASS=H and 1 declares MSGCLASS=X, so
  #       whether a job log survived was an incidental side effect of a spool
  #       class chosen per job -- and for the majority it was effectively
  #       transient. Here one reviewable, per-environment period governs every
  #       group this module owns. The material policy gate checks that the value
  #       is set at all, which is why it is never omitted.
  retention_in_days = var.log_retention_days

  # WHY : Refactoring Rationale: all eight `DEFINE FILE` stanzas in
  #       app/csd/CARDDEMO.CSD are declared with journalling and recovery
  #       disabled -- `JOURNAL(NO)` and `JNLREAD(NONE)` through
  #       `RECOVERY(NONE)` and `FWDRECOVLOG(NO)`, at L7-L9 of the first stanza --
  #       so beyond the transient job spool the baseline kept no durable record
  #       of a data change. Encrypting the target's durable record under a
  #       customer-managed key is what makes it admissible as an audit trail
  #       rather than merely available.
  #       Assumptions: the key policy grants the regional CloudWatch Logs
  #       service principal use of this key under an account-and-region-scoped
  #       encryption context. Without that grant the group can be created but
  #       the producer cannot write an encrypted event -- a failure that surfaces
  #       at the producer, not here.
  kms_key_id = var.kms_key_arn

  tags = merge(local.tags, {
    Name     = each.value
    Producer = each.key
  })
}

# -----------------------------------------------------------------------------
# One encrypted notification topic per environment
#
# WHY : Refactoring Rationale: all 38 job cards in app/jcl declare a `NOTIFY=`
#       operand, so the baseline already had the habit of pushing an outcome to a
#       named recipient rather than waiting for one to look; what changes is the
#       address and the granularity, not the idea. Redirecting the baseline's
#       whole alerting surface meant editing 38 job cards; redirecting this one
#       means changing one subscription.
# -----------------------------------------------------------------------------

resource "aws_sns_topic" "alerts" {
  name = "${local.name_stem}-alerts"

  # WHY : Assumptions: the topic carries alarm text naming a service, a queue or
  #       a state machine, and a subscription list of operator addresses, so it
  #       is encrypted under the same customer-managed key as the log groups
  #       above rather than left to the service default. This is the same
  #       treatment the queues receive, and the material policy gate checks it.
  kms_master_key_id = var.kms_key_arn

  # WHY : Assumptions: active tracing lets a Step Functions failure publication
  #       remain connected to the execution that produced it. It records no
  #       message body in this module and changes no delivery semantics. This is
  #       an argument on one resource, not an X-Ray instrumentation graph; the
  #       header records why no such graph is declared here.
  tracing_config = "Active"

  tags = merge(local.tags, {
    Name = "${local.name_stem}-alerts"
  })
}

# WHY : Alternatives Considered: leaving the topic with no resource policy and
#       relying on the account's default owner access. Rejected because an alarm
#       action is delivered by a CloudWatch service principal, not by the
#       account, so the publish grant has to be stated -- and stating it is the
#       opportunity to bound it. Assumptions: NO wildcard principal appears on
#       this topic. An unrestricted publish grant on an alerting topic lets
#       anything in the partition inject a message that reaches whoever is
#       subscribed, and a fabricated alert is more damaging than a missing one
#       because it consumes the response it triggers; the two conditions below
#       confine publication to CloudWatch alarms this environment named.
data "aws_iam_policy_document" "alerts" {
  statement {
    sid    = "AllowAccountAdministration"
    effect = "Allow"
    actions = [
      "sns:AddPermission",
      "sns:DeleteTopic",
      "sns:GetTopicAttributes",
      "sns:ListSubscriptionsByTopic",
      "sns:Publish",
      "sns:Receive",
      "sns:RemovePermission",
      "sns:SetTopicAttributes",
      "sns:Subscribe",
    ]
    resources = [aws_sns_topic.alerts.arn]

    principals {
      type        = "AWS"
      identifiers = [local.account_root_arn]
    }
  }

  statement {
    sid       = "AllowCloudWatchAlarmPublication"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alerts.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.${data.aws_partition.current.dns_suffix}"]
    }

    # WHY : Assumptions: a service principal represents every account the
    #       service acts for. The account and alarm-prefix conditions confine
    #       publication to alarms this environment creates instead of allowing
    #       another account or an unrelated alarm to inject a false alert.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["${local.alarm_arn_prefix}*"]
    }
  }
}

# WHY : Assumptions: the policy is attached as a separate resource rather than
#       through the topic's own `policy` argument, so the document above can
#       reference the topic's ARN in its `resources` list. Setting the policy
#       inline on the topic would make the topic depend on a document that depends
#       on the topic, and that cycle cannot be resolved; the alternative -- naming
#       the topic ARN as a composed literal to break it -- would put a second,
#       silently divergeable copy of the naming rule in this file.
resource "aws_sns_topic_policy" "alerts" {
  arn    = aws_sns_topic.alerts.arn
  policy = data.aws_iam_policy_document.alerts.json
}

# WHY : Assumptions: the endpoint list is an input that defaults to empty, and
#       the emptiness is the decision. An address is a person's contact detail
#       and changes with staffing rather than with the architecture, so it is not
#       recorded in this repository -- no address or number appears in this file,
#       including in an example. Trade-offs: the topic therefore exists with no
#       subscriber until a root supplies one, which keeps every alarm's publish
#       path wired while leaving delivery to be configured where the recipients
#       are actually known. `for_each` over a set rather than `count` over a list
#       keys each subscription by its own endpoint, so removing one address does
#       not renumber and recreate the others.
resource "aws_sns_topic_subscription" "email" {
  for_each = toset(var.alarm_email_endpoints)

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email"
  endpoint  = each.value
}

# -----------------------------------------------------------------------------
# Shared ALB and S3 server-access-log destination
#
# WHY : Alternatives Considered: a destination per producer. Rejected because the
#       two delivery services require conflicting properties on their destination
#       from the ones application logs need, and separating them per producer would
#       multiply that exception rather than confine it. One shared destination
#       keeps the deviation in a single place with a single policy to review, and
#       the prefix structure in the policy below is what keeps each producer's
#       objects distinguishable inside it.
#       Assumptions: this bucket is the TERMINAL destination in the logging graph.
#       That is the fact behind both bounded exceptions recorded on the resources
#       below -- a terminal destination has nowhere further to send its own access
#       records, and it must remain writable by the delivery principals that are
#       the whole reason it exists.
# -----------------------------------------------------------------------------

resource "aws_s3_bucket" "access_logs" {
  #checkov:skip=CKV_AWS_18:This bucket is the terminal access-log destination; sending its own server-access logs back to itself creates a recursive write stream.
  #checkov:skip=CKV_AWS_145:S3 server access logging requires its destination bucket to use SSE-S3; under SSE-KMS the service may deliver objects encrypted with a key the account cannot read, so this destination uses AES256 with public access blocked, versioning, TLS-only access and an exact-source bucket policy as the compensating controls.

  # WHY : Assumptions: both exceptions above are annotated INSIDE this resource
  #       body rather than in the comment block preceding it, and on the bucket
  #       rather than on the encryption configuration further down. Both details
  #       are load-bearing: the scanner attaches an annotation to the block that
  #       encloses it, and it evaluates the default-encryption check against the
  #       bucket together with its encryption settings. An annotation placed above
  #       the block, or on the configuration resource, parses cleanly and reads as
  #       though it applied while suppressing nothing -- a bounded exception that
  #       silently bounds nothing, which is worse than an absent one because it
  #       stops a reviewer looking further. The dataset audit bucket carries its
  #       equivalent exception in exactly this position.
  bucket = local.access_log_bucket_name

  # WHY : Trade-offs: teardown behaviour is an input defaulting to false, so a
  #       destination still holding delivered records stops a `destroy` instead of
  #       silently removing the audit trail it was created to keep. A root that
  #       genuinely wants a clean teardown -- a development environment with no
  #       protection flag set -- opts in explicitly, which makes discarding those
  #       records a stated decision rather than a side effect of tearing the
  #       environment down.
  force_destroy = var.access_log_bucket_force_destroy

  tags = merge(local.tags, {
    Name = local.access_log_bucket_name
  })
}

# WHY : Assumptions: all four controls are set rather than relying on the account
#       default, and they are set together because each closes a different route.
#       Two govern how an access-control list is interpreted and two govern the
#       bucket policy, so enabling a subset leaves the others reachable -- and the
#       records here are request logs that can carry a source address, which is
#       treated as sensitive. Trade-offs: setting them explicitly means an account
#       whose defaults change cannot silently relax this bucket, at the cost of
#       four lines that look redundant while the defaults happen to agree.
resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    # WHY : Assumptions: both delivery services use bucket-owner-full-control
    #       semantics. Enforcing ownership keeps every delivered object under
    #       this account even though a service principal performed the write.
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    apply_server_side_encryption_by_default {
      # WHY : Trade-offs: this terminal destination uses SSE-S3 rather than the
      #       module's customer-managed key because S3 server access logging
      #       constrains it to: a destination under SSE-KMS may receive objects
      #       encrypted with a key the account cannot itself read, which yields a
      #       bucket that looks populated and cannot be queried. Alternatives
      #       Considered: pointing this destination at the module's CMK, rejected
      #       because it converts a readable audit trail into an unreadable one --
      #       the failure is silent, since delivery reports success. The exception
      #       is confined to this one delivery destination; the log groups, the
      #       alert topic and the application datasets all stay customer-key
      #       encrypted, so no application record inherits it.
      sse_algorithm = "AES256"
    }
  }
}

# WHY : Assumptions: versioning is what makes an overwrite or a delete of an
#       access record recoverable, which is the property that lets this bucket be
#       described as an audit trail at all -- the contrast being the baseline's
#       eight CICS file definitions, every one declared `RECOVERY(NONE)` with
#       journalling disabled. Trade-offs: versioning retains a noncurrent copy of
#       every replaced object, so the lifecycle rule below expires those copies
#       promptly; without that pairing the recoverability property would arrive as
#       unbounded storage nobody chose.
resource "aws_s3_bucket_versioning" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    # WHY : Assumptions: an empty filter is the explicit whole-bucket scope. The
    #       provider requires either a filter or a prefix on a rule, and omitting
    #       it is rejected; writing a prefix instead would silently exempt every
    #       object outside that prefix from expiry, which on a destination shared
    #       by two delivery services means one producer's records would accumulate
    #       without limit while the other's aged out.
    filter {}

    # WHY : Assumptions: one retention input governs both CloudWatch events and
    #       access-log objects so an environment has one audit-retention choice
    #       rather than two values that can drift without a policy distinction.
    expiration {
      days = var.log_retention_days
    }

    # WHY : Trade-offs: a noncurrent copy is kept only long enough to be the
    #       recovery window versioning exists to provide, then expired. A longer
    #       hold would grow storage in proportion to overwrite volume for records
    #       whose current version is already retained by the rule above, and these
    #       objects are append-only in normal operation -- a noncurrent version
    #       here means something replaced a delivered record, which is precisely
    #       the event worth being able to recover from and not worth archiving.
    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  # WHY : Assumptions: the noncurrent-version clause above is only meaningful once
  #       versioning is enabled, and Terraform cannot infer that ordering from the
  #       arguments because both resources only reference the bucket. Declaring it
  #       prevents a first apply that configures noncurrent expiry against a
  #       bucket that is still unversioned.
  depends_on = [aws_s3_bucket_versioning.access_logs]
}

# WHY : Assumptions: a delivery service writes as its OWN service principal, not as
#       the account, so a destination with no resource policy accepts nothing and
#       the log simply never arrives -- a failure that reports as an empty bucket
#       rather than as an error. Each Allow therefore names one principal, one
#       action and one object path, and every one carries a source-account
#       condition: a regional delivery principal speaks for every account it serves,
#       so without that condition another account's records could be written into
#       this bucket and would be indistinguishable from this environment's own.
#       Trade-offs: the Deny is listed first and applies to everything, because an
#       explicit Deny overrides every Allow -- so the transport requirement holds
#       for the delivery principals below as well, and adding a fourth Allow later
#       cannot accidentally exempt itself from it.
data "aws_iam_policy_document" "access_logs" {
  statement {
    sid    = "DenyNonTlsRequests"
    effect = "Deny"

    actions = ["s3:*"]
    resources = [
      aws_s3_bucket.access_logs.arn,
      "${aws_s3_bucket.access_logs.arn}/*",
    ]

    # WHY : Assumptions: a wildcard principal is correct here and ONLY here,
    #       because the statement's effect is Deny. A Deny grants nothing -- it
    #       withdraws -- so widening its principal narrows access rather than
    #       widening it, and naming principals instead would leave every
    #       unnamed caller able to reach the bucket over cleartext. The
    #       distinction matters because the same construct on an Allow, or on
    #       the notification topic above, would be the defect this one prevents.
    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid       = "AllowAlbLogDelivery"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/*/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.${data.aws_partition.current.dns_suffix}"]
    }

    # WHY : Assumptions: the resource path confines objects to this account's
    #       AWSLogs segment, and SourceAccount prevents the regional delivery
    #       principal from writing another account's records into this bucket.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    # WHY : Refactoring Rationale: SourceAccount alone was the whole boundary here,
    #       and it is not narrow enough. This bucket is SHARED -- it terminates the
    #       access logs of every load balancer in the account -- so an account-scoped
    #       condition let ANY load balancer in the same account write objects into
    #       this environment's audit trail. That is a real defect rather than a
    #       theoretical one: the two environment roots are designed to coexist in one
    #       account, so dev's load balancer could inject records into prod's log
    #       prefix, and an auditor reading the prefix could not tell which balancer
    #       produced a record.
    # WHY : Assumptions: an ARN PATTERN rather than the load balancer's own ARN, and
    #       the sibling S3 statement below is narrowed the same way for the same
    #       reason. Referencing the ARN would form a module cycle: infra/modules/alb
    #       needs this bucket's NAME before it can be created, so this module cannot
    #       in turn depend on the load balancer that writes to it. The pattern is
    #       composed from the same two inputs infra/modules/alb composes its name
    #       from -- "${var.name_prefix}-alb-${var.environment}" at that module's
    #       local.alb_name -- so the two agree by construction rather than by
    #       coincidence, and a renaming there is a renaming here.
    # WHY : Assumptions: ArnLike with a trailing wildcard is required rather than
    #       StringEquals, because an Application Load Balancer ARN ends in a
    #       service-generated identifier -- loadbalancer/app/<name>/<id> -- that is
    #       not known until the balancer exists. The wildcard covers only that
    #       identifier segment; the account, region and name are all pinned.
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values = [
        "arn:${data.aws_partition.current.partition}:elasticloadbalancing:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:loadbalancer/app/${var.name_prefix}-alb-${var.environment}/*"
      ]
    }
  }

  statement {
    sid       = "AllowAlbBucketAclCheck"
    effect    = "Allow"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.access_logs.arn]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.${data.aws_partition.current.dns_suffix}"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    # WHY : Assumptions: narrowed by the same ARN pattern as the delivery statement
    #       above, for the same reason and by the same construction. This grant is
    #       read-only, so it cannot corrupt the audit trail, but it does disclose the
    #       bucket's ACL to any load balancer in the account. Both statements are
    #       written to the same boundary so that a future reader cannot conclude from
    #       one narrow and one broad statement that the difference was intentional.
    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values = [
        "arn:${data.aws_partition.current.partition}:elasticloadbalancing:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:loadbalancer/app/${var.name_prefix}-alb-${var.environment}/*"
      ]
    }
  }

  statement {
    sid       = "AllowS3ServerAccessLogDelivery"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/s3-access-logs/*"]

    principals {
      type        = "Service"
      identifiers = ["logging.s3.${data.aws_partition.current.dns_suffix}"]
    }

    # WHY : Assumptions: the source pattern admits only the CardDemo dataset
    #       bucket for this environment. It closes the destination without
    #       depending on that bucket's ARN, which would form a module cycle:
    #       datasets needs the destination name before its own ARN exists.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${data.aws_partition.current.partition}:s3:::${var.name_prefix}-datasets-${var.environment}-*"]
    }
  }
}

resource "aws_s3_bucket_policy" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  policy = data.aws_iam_policy_document.access_logs.json

  # WHY : Assumptions: ordering against these two is required and is not implied by
  #       any argument, since all three resources reference only the bucket.
  #       Attaching a policy before the public-access controls exist opens a window
  #       in which the policy is live and unconstrained by them, and attaching it
  #       before ownership is enforced can have the policy evaluated against
  #       different object-ownership semantics from the ones it was written for.
  #       Both are apply-order defects that leave correct-looking configuration
  #       behind, which is why the dependency is declared rather than assumed.
  depends_on = [
    aws_s3_bucket_ownership_controls.access_logs,
    aws_s3_bucket_public_access_block.access_logs,
  ]
}

# -----------------------------------------------------------------------------
# Dashboard
#
# WHY : Alternatives Considered: writing the widget layout inline in the resource.
#       Rejected because two of the three widget groups are CONDITIONAL on an
#       input, and expressing a conditional widget inside a resource argument
#       makes the layout unreadable at the point where the coordinates matter.
#       Composing the list here lets each group carry its own reasoning and lets
#       an absent input contribute an empty list rather than an empty widget.
#       Assumptions: the series shown are the AWS service metrics and Container
#       Insights metrics the producer modules already publish. The three common
#       metric tags the shared kernel contributes -- `service`, `environment` and
#       `version` -- are the join keys for the application meters; they are
#       referenced by docs/architecture/observability.md and are not redefined
#       here, because a fourth definition of that set would be a second contract.
#       Trade-offs: the per-service row order is the caller's, taken from the
#       input as given rather than sorted here. The order an operator reads is
#       roughly the order a request travels, and imposing alphabetical order would
#       put reporting near the front and sign-on second -- neither the sequence of
#       a request nor a sequence anyone reads in.
# -----------------------------------------------------------------------------

locals {
  # WHY : Assumptions: two widgets per row at half width each, so the index
  #       arithmetic places even entries in the left column and odd entries in
  #       the right, six rows of height apart. A single column would make the
  #       per-service rows scroll past the shared widgets below them, which are
  #       the ones an operator correlates against.
  ecs_service_widgets = [
    for index, service in var.dashboard_service_names : {
      type   = "metric"
      x      = (index % 2) * 12
      y      = floor(index / 2) * 6
      width  = 12
      height = 6
      properties = {
        title  = "${service}: ECS tasks and utilisation"
        region = data.aws_region.current.region
        view   = "timeSeries"
        stat   = "Average"
        period = var.alarm_period_seconds
        metrics = [
          ["ECS/ContainerInsights", "RunningTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
          ["ECS/ContainerInsights", "DesiredTaskCount", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
          ["ECS/ContainerInsights", "CpuUtilized", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
          ["ECS/ContainerInsights", "MemoryUtilized", "ClusterName", var.ecs_cluster_name, "ServiceName", service],
        ]
      }
    }
  ]

  # WHY : Assumptions: the shared widgets begin below the last per-service row,
  #       so the offset is computed from the list length rather than fixed. A
  #       constant offset would either overlap the service rows when the caller
  #       passes eight names or leave a band of empty grid when it passes two --
  #       and the input exists precisely so a caller may pass fewer.
  shared_widget_y = ceil(length(var.dashboard_service_names) / 2) * 6

  # WHY : Trade-offs: an empty queue map yields an empty list rather than a
  #       widget with no series. A widget whose metric list is empty renders as a
  #       blank panel that looks like a collection failure, which is a worse
  #       report than the panel being absent; the same pattern is used for the
  #       distribution widget below.
  queue_depth_widget = length(var.queue_names) == 0 ? [] : [{
    type   = "metric"
    x      = 0
    y      = local.shared_widget_y
    width  = 12
    height = 6
    properties = {
      title  = "SQS visible messages"
      region = data.aws_region.current.region
      view   = "timeSeries"
      stat   = "Maximum"
      period = var.alarm_period_seconds
      metrics = [
        for key, name in var.queue_names :
        ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", name, { label = key }]
      ]
    }
  }]

  cloudfront_widget = var.cloudfront_distribution_id == null ? [] : [{
    type   = "metric"
    x      = 12
    y      = local.shared_widget_y + 12
    width  = 12
    height = 6
    properties = {
      title = "CloudFront requests and edge errors"

      # WHY : Assumptions: CloudFront publishes this metric family in one fixed
      #       service region, and a dashboard metric widget carries its own
      #       region field, so the widget selects it independently and the module
      #       keeps its single-provider calling contract. An ALARM cannot borrow
      #       that trick -- an alarm is created in the provider's own region and
      #       has no per-resource region field -- so watching this metric from a
      #       deployment outside us-east-1 would need an aliased provider declared
      #       through `configuration_aliases` in versions.tf, making an extra
      #       provider a mandatory part of the contract for every root, including
      #       roots that never look at CloudFront. versions.tf therefore declares
      #       no alias.
      #       Refactoring Rationale: this paragraph concluded "so no CloudFront
      #       alarm is created", and that conclusion is now wrong: the
      #       aws_cloudwatch_metric_alarm.cloudfront_5xx resource below IS created,
      #       under a count gated on `var.cloudfront_distribution_id != null &&
      #       data.aws_region.current.region == "us-east-1"`. The premise survives
      #       and the conclusion does not, because the gate resolves the tension
      #       rather than conceding to it: when the deployment region ALREADY is
      #       us-east-1 the default provider is in the right region and no alias is
      #       needed, so the alarm is created for free; when it is not, the alarm is
      #       skipped rather than the contract widened. Leaving the old conclusion in
      #       place invited a reader to add the alarm they believed was missing, and
      #       to add the aliased provider along with it.
      #       Trade-offs: this is the one literal region in the file. It is
      #       admissible where a deployment region would not be, because it is a
      #       fixed property of where the service publishes rather than a choice
      #       about where to deploy; parameterising it would invite a caller to
      #       set a region in which the metric does not exist, and the widget
      #       would render empty with nothing to say why.
      region = "us-east-1"
      view   = "timeSeries"
      stat   = "Sum"
      period = var.alarm_period_seconds
      metrics = [
        ["AWS/CloudFront", "Requests", "DistributionId", var.cloudfront_distribution_id, "Region", "Global"],
        ["AWS/CloudFront", "5xxErrorRate", "DistributionId", var.cloudfront_distribution_id, "Region", "Global", { stat = "Average" }],
      ]
    }
  }]

  # WHY : Refactoring Rationale: this widget exists because the application metric
  #       pipeline had a producer and no consumer. Every service declares the
  #       Actuator and a Prometheus registry, the shared kernel's meter filter
  #       stamps three common tags on each meter, and the telemetry sidecar in
  #       infra/modules/ecs-service scrapes that endpoint and exports it through
  #       CloudWatch EMF into the `CardDemo` namespace -- and before this widget,
  #       nothing in this module read that namespace at all. Meters were therefore
  #       being collected, stored and billed while being visible nowhere, which is
  #       the one state worse than not collecting them: the cost is paid and the
  #       benefit is not.
  # WHY : Assumptions: the series are selected by SEARCH expression rather than by
  #       an explicit metric list, and that is forced by how the exporter publishes.
  #       It runs with no dimension roll-up and with resource-to-telemetry conversion
  #       on, so each series carries the FULL dimension set -- the meter's own labels
  #       plus the resource attributes -- and an explicit metric entry has to name
  #       every dimension exactly or it matches nothing and renders an empty panel.
  #       A search matches on the namespace and a metric-name token, so it keeps
  #       returning the series as the label set evolves.
  # WHY : Assumptions: identity is currently spelled TWICE in this namespace and the
  #       search is written to depend on neither spelling. The shared kernel's meter
  #       filter contributes `service`, `environment` and `version`, while the
  #       collector's resource processor contributes `service.name`,
  #       `deployment.environment.name` and `service.version`; both reach the
  #       published series as dimensions. Pinning either set here would make this
  #       panel fail silently if the other were consolidated, so the panel is
  #       dimension-agnostic and the duplication is recorded in
  #       docs/architecture/observability.md rather than depended upon.
  # WHY : Trade-offs: the two series below are FRAMEWORK meters -- a request counter
  #       and heap usage -- because those are the meters that exist today. No
  #       business meter is authored in any service yet, so a panel promising posting
  #       rejects or authorization decisions would be a promise this stack cannot
  #       keep; those series are named as a target in the architecture document and
  #       will appear here through the same search once a job or a service records
  #       them, without an edit to this widget.
  application_meter_widget = [{
    type   = "metric"
    x      = 0
    y      = local.shared_widget_y + 18
    width  = 24
    height = 6
    properties = {
      title  = "Application meters exported to the CardDemo namespace"
      region = data.aws_region.current.region
      view   = "timeSeries"
      period = var.alarm_period_seconds
      metrics = [
        [{ expression = "SEARCH('{CardDemo} http_server_requests', 'Sum', ${var.alarm_period_seconds})", label = "HTTP server requests", id = "requests" }],
        [{ expression = "SEARCH('{CardDemo} jvm_memory_used', 'Average', ${var.alarm_period_seconds})", label = "JVM heap in use", id = "heap" }],
      ]
    }
  }]

  dashboard_widgets = concat(
    local.ecs_service_widgets,
    local.queue_depth_widget,
    local.application_meter_widget,
    [
      {
        type   = "metric"
        x      = 12
        y      = local.shared_widget_y
        width  = 12
        height = 6
        properties = {
          title  = "HTTP edge and load-balancer failures"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Sum"
          period = var.alarm_period_seconds
          metrics = [
            ["AWS/ApiGateway", "5xx", "ApiId", var.api_gateway_id, "Stage", var.api_gateway_stage_name],
            ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix],
            ["AWS/ApplicationELB", "RejectedConnectionCount", "LoadBalancer", var.alb_arn_suffix],
          ]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = local.shared_widget_y + 6
        width  = 12
        height = 6
        properties = {
          title  = "Aurora capacity and connections"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Average"
          period = var.alarm_period_seconds
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBClusterIdentifier", var.aurora_cluster_identifier],
            ["AWS/RDS", "DatabaseConnections", "DBClusterIdentifier", var.aurora_cluster_identifier],
            ["AWS/RDS", "ServerlessDatabaseCapacity", "DBClusterIdentifier", var.aurora_cluster_identifier],
            ["AWS/RDS", "ACUUtilization", "DBClusterIdentifier", var.aurora_cluster_identifier],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = local.shared_widget_y + 6
        width  = 12
        height = 6
        properties = {
          title  = "Daily batch executions"
          region = data.aws_region.current.region
          view   = "timeSeries"
          stat   = "Sum"
          period = var.alarm_period_seconds
          metrics = [
            ["AWS/States", "ExecutionsSucceeded", "StateMachineArn", var.daily_state_machine_arn],
            ["AWS/States", "ExecutionsFailed", "StateMachineArn", var.daily_state_machine_arn],
            ["AWS/States", "ExecutionsTimedOut", "StateMachineArn", var.daily_state_machine_arn],
          ]
        }
      },
      {
        # WHY : Assumptions: this widget QUERIES the network module's flow-log
        #       group by name and does not create it -- the group belongs to the
        #       module that configures the flow log, per the ownership boundary
        #       recorded at the log-group resource above. Only the group name is
        #       taken as an input for that reason.
        #       Trade-offs: the query filters to rejected flows and bounds the
        #       result rather than listing every flow. An unfiltered panel on this
        #       group is dominated by accepted traffic, and the rejected records
        #       are the ones that answer whether a security group or an isolated
        #       subnet is refusing a connection a service expected to make -- the
        #       question this panel exists to answer next to the service alarms.
        #       The bound is what keeps one widget from paging an operator through
        #       a stream during an incident.
        type   = "log"
        x      = 0
        y      = local.shared_widget_y + 12
        width  = 12
        height = 6
        properties = {
          title  = "Rejected VPC flows"
          region = data.aws_region.current.region
          view   = "table"
          query  = "SOURCE '${var.vpc_flow_log_group_name}' | fields @timestamp, srcAddr, dstAddr, srcPort, dstPort, protocol, action | filter action = 'REJECT' | sort @timestamp desc | limit 50"
        }
      },
    ],
    local.cloudfront_widget,
  )
}

resource "aws_cloudwatch_dashboard" "operations" {
  dashboard_name = "${local.name_stem}-operations"

  # WHY : Alternatives Considered: a JSON heredoc was rejected because
  #       Terraform treats it as an opaque string and malformed JSON reaches the
  #       service only during apply. jsonencode validates the object structure
  #       while planning and lets the variable-driven widget lists stay typed.
  #       Assumptions: `periodOverride = "inherit"` keeps each widget on the
  #       period the local above set from `alarm_period_seconds`, so a panel and
  #       the alarm watching the same metric aggregate identically -- otherwise
  #       the board can show a smooth line for a series an alarm has just fired
  #       on, and the two disagree with no visible reason. The relative default
  #       window is a starting view an operator overrides in the console; it is
  #       expressed relatively so the board opens on recent data whenever it is
  #       opened rather than on a fixed point.
  dashboard_body = jsonencode({
    start          = "-PT6H"
    periodOverride = "inherit"
    widgets        = local.dashboard_widgets
  })
}

# -----------------------------------------------------------------------------
# Structurally grounded alarms
#
# Every alarm below states, in its adjacent comment and again in its
# `alarm_description`, three things: the CONDITION it detects, the QUESTION that
# condition answers, and the ACTION the answer enables. An alarm that cannot be
# given all three is not created, because a notification an operator cannot act
# on trains them to disregard the channel -- and a disregarded alarm is worse
# than an absent one.
#
# Two argument groups are shared, and each is a decision rather than a default:
#
# WHY : Trade-offs: an evaluation count above one is what separates a sustained
#       condition from one unlucky period, and its cost is that notification is
#       delayed by that many periods; the detection window is the product of the
#       two inputs, which is why they are read together. `datapoints_to_alarm`
#       is set equal to `evaluation_periods` rather than left to default, so the
#       breach must be CONSECUTIVE: a lower value would fire on scattered
#       datapoints inside the window, which is a different condition from a
#       sustained one and would be reported under the same name. Alarms whose
#       source event is already terminal override the window to a single period,
#       and each says so where it does.
#
# WHY : Alternatives Considered: one blanket value across all nine alarms.
#       Rejected, because the meaning of a missing datapoint differs by metric
#       family and a single value would be right for some and wrong for others.
#       - `notBreaching` is used wherever absence means the counted event did
#         not occur. These metrics are event counts -- unhealthy hosts, server
#         errors, queue depth, message age, function errors, failed executions --
#         and a period that publishes nothing is a period in which nothing went
#         wrong. `missing` would leave such an alarm in INSUFFICIENT_DATA
#         whenever traffic was idle, and an alarm that spends its time in that
#         state is one whose real transitions nobody notices.
#       - `missing` is used for the two Aurora gauges. A gauge that stops
#         reporting has not reported zero; it has stopped, and the cluster may
#         have paused or become unreachable. Preserving the previous state rather
#         than asserting health keeps that distinction visible instead of
#         painting an unreachable cluster green.
#       Assumptions: the batch alarms carry `notBreaching` knowingly, and it
#       leaves one question unanswered -- a chain that never STARTED publishes no
#       execution metric at all, so these alarms report a failed run and not a
#       missing one. That gap is deliberate and is covered elsewhere by design:
#       the invocation itself is the scheduler's concern, and the
#       eventbridge-scheduler module carries a dead-letter target for an
#       invocation that never reached the state machine. Detecting a missing run
#       here instead would mean asserting when a run was due, which is a schedule
#       assumption this module does not hold.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the threshold of zero is DERIVED from the health-check
#       contract, not chosen as a target. The load balancer classifies a target
#       as unhealthy only after its own configured checks have failed, so any
#       non-zero count already IS that verdict and no separate judgement is
#       encoded here.
#       Alternatives Considered: alarming on the service's own emitted health
#       signal instead. Rejected because a task too broken to emit anything is
#       exactly the case this alarm exists for; the target group observes the
#       task from outside and therefore still reports when the task has gone
#       quiet.
#       Assumptions: both dimensions are provider-returned ARN SUFFIXES rather
#       than full ARNs -- `app/<name>/<id>` and `targetgroup/<name>/<id>`. The
#       metric namespace accepts only that form, and a full ARN is accepted by
#       the API while matching no published metric, producing an alarm that never
#       leaves INSUFFICIENT_DATA; variables.tf validates the shape for that
#       reason.
resource "aws_cloudwatch_metric_alarm" "service_unhealthy" {
  # WHY : Trade-offs: one alarm per entry in the supplied map, so an added
  #       service gains an alarm without a module change and an empty map
  #       deliberately creates none. The key names the service in the alarm
  #       name, description and tags, which is what lets one topic serve every
  #       service without the notification losing which one it came from.
  for_each = var.service_target_group_arn_suffixes

  alarm_name          = "${local.name_stem}-${each.key}-unhealthy-targets"
  alarm_description   = "Condition: the load balancer reports an unhealthy target. Question: is ${each.key} serving traffic? Action: replace the task or roll back the image revision."
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/ApplicationELB"
  metric_name         = "UnHealthyHostCount"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }

  tags = merge(local.tags, {
    Service = each.key
    Signal  = "unhealthy-target"
  })
}

# WHY : Refactoring Rationale: this alarm exists because the unhealthy-target
#       alarm above cannot detect the failure it looks like it detects. That one
#       watches `UnHealthyHostCount` for a value above zero, and a target group
#       with NO registered target publishes zero -- so a crash loop that never
#       reaches the health check, an image that cannot be pulled, a task that
#       never registers, and a service scaled to zero all leave it in OK. The two
#       are complements rather than duplicates: one answers "a target is failing
#       its checks", this one answers "there is no target", and only the second
#       covers the outage in which nothing is running.
# WHY : Assumptions: `treat_missing_data` is "breaching" here, in deliberate
#       contrast to every other alarm in this file. The metric STOPS being
#       published when a target group has no registered target at all, so an
#       absent datapoint is not the absence of a signal -- it IS the signal, and
#       treating it as not-breaching would return this alarm to exactly the blind
#       spot it was created to close.
# WHY : Assumptions: the statistic is Minimum rather than Maximum. Across the
#       datapoints of one period a Maximum reports the best moment, so a service
#       that was healthy for one moment and down for the rest of the period would
#       read as healthy; Minimum reports the worst moment, which is the reading an
#       operator needs from an availability alarm.
# WHY : Trade-offs: the threshold is one, so this alarm fires for a service
#       deliberately scaled to zero. That is accepted rather than worked around: no
#       environment root scales an online service to zero, and an alarm that
#       tolerated zero healthy targets would have no condition left to detect.
resource "aws_cloudwatch_metric_alarm" "service_no_healthy_targets" {
  for_each = var.service_target_group_arn_suffixes

  alarm_name          = "${local.name_stem}-${each.key}-no-healthy-targets"
  alarm_description   = "Condition: the target group holds no healthy target. Question: is ${each.key} serving at all? Action: read the task's stopped reason and log stream, then correct the image, the configuration or the health-check contract."
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HealthyHostCount"
  statistic           = "Minimum"
  treat_missing_data  = "breaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }

  tags = merge(local.tags, {
    Service = each.key
    Signal  = "no-healthy-target"
  })
}

# WHY : Assumptions: a 5xx response is a server-side failure BY DEFINITION -- the
#       status class is the server's own admission -- so what the threshold sets
#       is how many such admissions are reported together, not whether a failure
#       occurred. Trade-offs: the input's floor is one rather than zero, because a
#       threshold of zero is breached by the single error a rolling deployment can
#       produce as a task drains, and an alarm that fires on every deployment is
#       one an operator learns to dismiss.
#       Alternatives Considered: counting from the service's own request meter
#       instead. Rejected for the same reason as the alarm above -- the load
#       balancer counts the response even when the service has stopped logging --
#       and kept as a pair with it so that "failing requests" and "not serving"
#       stay two answers rather than one.
resource "aws_cloudwatch_metric_alarm" "service_5xx" {
  for_each = var.service_target_group_arn_suffixes

  alarm_name          = "${local.name_stem}-${each.key}-target-5xx"
  alarm_description   = "Condition: target-generated 5xx responses reach the configured count. Question: is ${each.key} failing requests? Action: inspect that service's correlated logs and roll back or replace the failing task."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.service_error_count_threshold
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/ApplicationELB"
  metric_name         = "HTTPCode_Target_5XX_Count"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    LoadBalancer = var.alb_arn_suffix
    TargetGroup  = each.value
  }

  tags = merge(local.tags, {
    Service = each.key
    Signal  = "target-5xx"
  })
}

# WHY : Alternatives Considered: relying on the per-service alarms alone.
#       Rejected because the two observe different segments: a request rejected at
#       the edge, or one whose integration to the load balancer fails, never
#       reaches a target group and so is counted by neither service alarm. Keeping
#       both is what makes the pair diagnostic -- the combination of which alarms
#       are lit localises the fault, whereas either alone only reports that
#       something failed.
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "${local.name_stem}-api-5xx"
  alarm_description   = "Condition: the HTTP API returns 5xx responses at the configured count. Question: is failure occurring at the edge or integration boundary? Action: compare API access logs with the load-balancer and service alarms."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.service_error_count_threshold
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/ApiGateway"
  metric_name         = "5xx"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    ApiId = var.api_gateway_id
    Stage = var.api_gateway_stage_name
  }

  tags = merge(local.tags, {
    Signal = "api-5xx"
  })
}

# WHY : Assumptions: this threshold is DERIVED and not chosen, and it is the model
#       every other comment in this section follows. A message arrives on a
#       dead-letter queue only after its source queue's redrive policy has been
#       exhausted, and that policy is authored: infra/modules/sqs applies
#       `maxReceiveCount = var.max_receive_count` to all six source queues, with
#       the input defaulting to 5. A depth of one therefore already means five
#       failed receives -- so the smallest breachable value is not a cautious
#       guess, it is the semantics of the queue.
#       Alternatives Considered: raising the threshold to reduce notification
#       volume. Rejected because it does not filter noise, it conceals messages
#       that have already failed every retry; a dead-letter queue is empty in
#       normal operation, so there is no benign depth to tolerate. This is the
#       highest-signal messaging alarm for exactly that reason: nothing else
#       produces the signal.
#       Assumptions: membership is decided by the logical map KEY ending in
#       `_dlq`, not by matching the queue-name string. A queue may be renamed
#       without silently moving an alarm from a dead-letter queue to a live one,
#       or the reverse -- which string matching on a name could do without any
#       diagnostic.
resource "aws_cloudwatch_metric_alarm" "dead_letter_depth" {
  for_each = local.dlq_queue_names

  alarm_name          = "${local.name_stem}-${each.key}-depth"
  alarm_description   = "Condition: a dead-letter queue contains a visible message. Question: has a message exhausted every receive attempt? Action: inspect and redrive the stranded message only after its failure cause is corrected."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.dead_letter_depth_threshold
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    QueueName = each.value
  }

  # WHY : Assumptions: one datapoint overrides the shared evaluation window
  #       because a message reaches this queue only after the source redrive
  #       policy has exhausted its receive limit. Waiting for a second period
  #       would hide a repeated failure rather than filter a transient one -- the
  #       condition this window normally guards against cannot occur here,
  #       because the arrival is already the durable outcome of five attempts.
  tags = merge(local.tags, {
    Queue  = each.key
    Signal = "dead-letter-depth"
  })
}

# WHY : Refactoring Rationale: this alarm is the OBSERVABLE FORM of a genuine
#       semantic gap rather than a health check. The baseline set a per-message
#       expiry on its reply, and the target queue service has no per-message
#       time-to-live at all, so the expiry moved into an attribute the consumer
#       honours. That relocation means an unconsumed reply is no longer discarded
#       by the transport for itself -- it simply waits -- so the only way the
#       condition becomes visible is by watching the age of the oldest message.
#       The gap and its resolution are recorded in docs/adr/ADR-004-messaging.md
#       and docs/architecture/messaging-contracts.md; this resource observes it
#       and does not restate it.
#       Assumptions: the threshold arrives as an input whose default is recovered
#       from that baseline interval rather than selected here, so it is a quoted
#       contract value and not an invented target.
#       Assumptions: membership is decided by the logical map key ending in
#       `_reply`, for the same reason the dead-letter set is keyed rather than
#       name-matched. One datapoint again overrides the shared window: a message
#       that has already aged past its expiry does not become less stale by
#       being observed a second time.
resource "aws_cloudwatch_metric_alarm" "reply_queue_age" {
  for_each = local.reply_queue_names

  alarm_name          = "${local.name_stem}-${each.key}-oldest-message"
  alarm_description   = "Condition: the oldest reply exceeds the baseline request/reply expiry interval. Question: is the requester consuming replies? Action: inspect the waiting consumer and correlate the stale message before its expiresAt is enforced."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.reply_queue_age_threshold_seconds
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    QueueName = each.value
  }

  tags = merge(local.tags, {
    Queue  = each.key
    Signal = "stale-reply"
  })
}

# WHY : Refactoring Rationale: the two messaging alarms above watch dead-letter
#       depth and reply age, and between them they cannot see a stopped consumer
#       on a request queue. Reaching a dead-letter queue requires a consumer to
#       receive a message and fail it the configured number of times, so a consumer
#       that has stopped receiving produces no dead-letter arrival at all; and the
#       reply alarm watches the queues a REQUESTER drains, not the queues a service
#       drains. The result was that the four queues carrying inbound work -- the
#       authorization request queue, the two inquiry request queues and the error
#       queue that is the baseline error queue's successor -- could accumulate
#       indefinitely with every alarm green.
# WHY : Assumptions: the metric is oldest-message AGE rather than depth, and the
#       distinction is what makes the alarm meaningful across queues with very
#       different arrival rates. Depth is a function of both arrival rate and drain
#       rate, so a depth threshold that is quiet on a busy queue is noisy on an idle
#       one; age is a direct statement that a specific message has not been taken,
#       which is the condition regardless of rate.
# WHY : Assumptions: this includes the error queue deliberately. Nothing consumes
#       it in normal operation, so its oldest message ages as soon as anything is
#       written -- which is the intended reading: a message on the error queue is
#       an unhandled fault and it should not sit there unnoticed.
# WHY : Trade-offs: `notBreaching` for missing data, unlike the healthy-target
#       alarm above. An empty queue publishes no age datapoint at all, and an empty
#       queue is the normal state of every queue here, so treating absence as
#       breaching would leave all four alarms permanently in ALARM and the channel
#       would be ignored inside a day.
resource "aws_cloudwatch_metric_alarm" "work_queue_age" {
  for_each = local.work_queue_names

  alarm_name          = "${local.name_stem}-${each.key}-oldest-message"
  alarm_description   = "Condition: the oldest message on ${each.key} exceeds the configured wait. Question: is the consumer for this queue still taking work off it? Action: inspect that consumer's task and log stream and check whether its service has a healthy target."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.work_queue_age_threshold_seconds
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateAgeOfOldestMessage"
  statistic           = "Maximum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    QueueName = each.value
  }

  tags = merge(local.tags, {
    Queue  = each.key
    Signal = "stale-work"
  })
}

# WHY : Assumptions: a rotation failure is silent by construction. Secrets Manager
#       invokes the function on its own schedule with no operator present, and a
#       failed rotation leaves the secret on its previous version -- so the system
#       keeps working while the control that was supposed to be running has
#       stopped. The Errors metric is therefore the only signal that distinguishes
#       "rotated successfully" from "never ran again", and it is watched at a
#       threshold of one because a single failed rotation is already the whole
#       finding.
#       Alternatives Considered: alarming on the absence of successful invocations
#       instead. Rejected because the rotation interval is measured in days, so an
#       absence alarm would have to span a window long enough to be useless for the
#       common case of a function that fails on every attempt.
#       Trade-offs: the input is a set of names rather than a single name, and an
#       empty set creates no alarm. That keeps the module usable by a root that has
#       not provisioned rotation, at the cost of the alarm silently not existing
#       there -- which is why the variable's description says so explicitly.
resource "aws_cloudwatch_metric_alarm" "rotation_failure" {
  for_each = var.rotation_lambda_function_names

  alarm_name          = "${local.name_stem}-${each.value}-rotation-errors"
  alarm_description   = "Condition: rotation function ${each.value} reported an invocation error. Question: did a scheduled credential rotation fail and leave the secret on its previous version? Action: inspect the function's encrypted log group and reconcile the affected secret version before the next interval."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    FunctionName = each.value
  }

  tags = merge(local.tags, {
    Signal = "rotation-errors"
  })
}

# WHY : Assumptions: the batch outcome model is graded, not boolean, and this
#       alarm fires ONLY on the fail and fatal tiers. The grading is a contract
#       recorded in two independent places: tests/README.md section 8 sets out the
#       rubric -- heading at L412, the aggregation rule "aggregate the worst
#       (highest) code" at L415, the tiers at L417-L423 as 0 pass, 4 warn or soft
#       reject, 8 fail, 16 fatal and 2 usage -- and .github/workflows/tests.yml
#       restates it independently at L28-L38. The warn tier is authored in the
#       baseline itself: app/cbl/CBTRN02C.cbl L229-L230 reads
#       `IF WS-REJECT-COUNT > 0` / `MOVE 4 TO RETURN-CODE`, so a night that
#       correctly wrote a business reject returns 4.
#       Alternatives Considered: alarming on any non-zero exit status. Rejected
#       specifically, because it would fire on every run that correctly rejected a
#       record while the pipeline beside it treated that same run as passing --
#       two systems disagreeing about what healthy means, presenting as a stream
#       of notifications nobody can act on. The state machine classifies the warn
#       return code as an explicit continue edge, so `ExecutionsFailed` and
#       `ExecutionsTimedOut` never count it and the graded distinction survives
#       into the alarm set by construction rather than by threshold tuning. Where
#       a run's rejects are themselves anomalous, the reject COUNT is the series
#       to watch, and it is a business meter rather than a state-machine metric.
#       Assumptions: app/jcl/TRANBKP.jcl L51 declares
#       `EXEC PGM=IDCAMS,COND=(4,LT)`, and it is cited here only to be set aside:
#       it gates a step following that job's own delete-and-redefine sequence at
#       L42 and L45, so it is job-local and is not evidence about posting
#       semantics. app/jcl/CREASTMT.JCL L56, L66 and L79 show the other form,
#       `COND=(0,NE)`, a clean-only gate whose target counterpart is the state's
#       default success edge.
#       Trade-offs: a single period, because the chain runs once per night --
#       there is no second execution within a window to corroborate the first, so
#       requiring one would leave a failed night unreported. The pair of metrics
#       is iterated rather than duplicated so a failure and a timeout arrive under
#       distinguishable names; they need different responses, since a timeout may
#       be redrivable unchanged and a failure generally is not.
resource "aws_cloudwatch_metric_alarm" "batch_failure" {
  for_each = local.batch_failure_metrics

  alarm_name          = "${local.name_stem}-batch-${replace(each.key, "_", "-")}"
  alarm_description   = "Condition: the daily state machine reports ${each.value}. Question: did the nightly chain fail rather than take the accepted RC 4 warning edge? Action: inspect the failed state and redrive after the cause is corrected."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.batch_failure_threshold
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = var.alarm_period_seconds
  namespace           = "AWS/States"
  metric_name         = each.value
  statistic           = "Sum"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    # WHY : Assumptions: this dimension takes the FULL state-machine ARN, in
    #       deliberate contrast to the load-balancer alarms above, which take ARN
    #       suffixes. The difference is not a choice available here -- each
    #       namespace publishes the dimension shape it publishes, and a value in
    #       the other form matches no series, so the alarm would sit in
    #       INSUFFICIENT_DATA rather than reporting an error. Reading the suffix
    #       reasoning at the unhealthy-target alarm and applying it here would
    #       therefore break this alarm silently, which is why the contrast is
    #       recorded at both ends rather than only once.
    StateMachineArn = var.daily_state_machine_arn
  }

  tags = merge(local.tags, {
    Signal = "batch-${each.key}"
  })
}

# WHY : Assumptions: on a serverless cluster this is a SCALING signal as much as a
#       saturation one -- sustained high utilisation means the workload is pressed
#       against the capacity it is permitted to add, which is a different finding
#       from a single expensive statement. That is why it is paired with the
#       capacity-ceiling alarm below and why neither is actionable alone.
#       Trade-offs: the threshold is a percentage input rather than a derived
#       value, and it is the one alarm here whose comparison point is a tuning
#       decision rather than a structural fact. It is admissible as a DETECTION
#       DEFAULT -- a point at which to tell a person -- and it is not a commitment
#       about how the cluster performs; the repository defines no such commitment
#       and this file invents none. variables.tf bounds it to 1 through 100
#       because the metric is a percentage, so a value that could alarm
#       permanently or never be crossed is rejected while planning.
resource "aws_cloudwatch_metric_alarm" "aurora_cpu" {
  alarm_name          = "${local.name_stem}-aurora-cpu"
  alarm_description   = "Condition: cluster processor utilisation reaches the configured threshold. Question: is compute pressure sustained on the Aurora cluster? Action: compare capacity and connections before changing the environment's maximum ACUs."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.database_cpu_threshold_percent
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/RDS"
  metric_name         = "CPUUtilization"
  statistic           = "Average"
  treat_missing_data  = "missing"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBClusterIdentifier = var.aurora_cluster_identifier
  }

  tags = merge(local.tags, {
    Signal = "aurora-cpu"
  })
}

# WHY : Assumptions: the comparison value is DERIVED, in the same sense the
#       dead-letter threshold is. It is the exact `aurora_max_capacity` the root
#       passed to the Aurora module, so reaching it is a declared configuration
#       fact being met rather than an independent target being missed -- the
#       cluster cannot scale past a ceiling the configuration set, so at that
#       point queueing rather than scaling is what happens next.
#       Alternatives Considered: alarming at a fraction of the maximum to give
#       earlier notice. Rejected because a fraction is a chosen number with no
#       source in the repository, and it would read as authoritative while being
#       arbitrary; the utilisation alarm above already provides the earlier,
#       explicitly-tuned signal, so this one is left as the unambiguous statement
#       that the configured ceiling was met.
resource "aws_cloudwatch_metric_alarm" "aurora_capacity" {
  alarm_name          = "${local.name_stem}-aurora-capacity-ceiling"
  alarm_description   = "Condition: serverless database capacity reaches the maximum configured by the environment root. Question: has the cluster exhausted the capacity it is permitted to add? Action: review workload and raise the declared maximum only when the pressure is expected."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.aurora_max_capacity
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/RDS"
  metric_name         = "ServerlessDatabaseCapacity"
  statistic           = "Maximum"
  treat_missing_data  = "missing"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBClusterIdentifier = var.aurora_cluster_identifier
  }

  tags = merge(local.tags, {
    Signal = "aurora-capacity"
  })
}

# ---------------------------------------------------------------------------
# Alarm 12 of 13: cluster-level connection saturation.
#
# WHY : Assumptions: THIS IS NOT THE CONNECTION-POOL ALARM, and the two must not
#       be conflated. Two different saturations exist, they publish to two
#       different places, and only one of them is reachable from here:
#         - Pool ACQUISITION failure inside a task -- a HikariCP meter that
#           reaches CloudWatch only through each service's Micrometer registry,
#           under the pool name that service configures. That series is an
#           application meter, not an AWS/RDS metric, so no alarm on it is
#           authorable in this module. It stays a dashboard concern.
#         - CLUSTER connection count -- a first-class AWS/RDS metric on the
#           DBClusterIdentifier dimension, which this module already graphs on
#           its dashboard. It is alarmable here, and this resource alarms it.
#       Refactoring Rationale: the module previously graphed DatabaseConnections
#       without alarming it, and justified the absence with the application-meter
#       argument above. That argument is sound for the first bullet and does not
#       apply to the second, so it was covering a gap it did not actually
#       explain. ADR-003 records "connection count grows with task count" as a
#       named risk precisely because "a sufficiently wide scale-out can exhaust
#       connections before it exhausts capacity" -- that is, this alarm can fire
#       while both aurora_cpu and aurora_capacity stay OK, which is the whole
#       reason it is not redundant with either.
#       Assumptions: the threshold is DERIVED, not chosen. ADR-003 states the
#       relationship as "tasks times pool size, not tasks plus pool size", and
#       the environment root computes exactly that product from the task count
#       and pool size it already configures, so no number here is invented.
#       Trade-offs: a derived ceiling can be exceeded legitimately when a
#       deployment briefly runs old and new tasks together, so the alarm uses the
#       shared evaluation-period count rather than firing on a single period.
resource "aws_cloudwatch_metric_alarm" "aurora_connections" {
  count = var.database_connection_threshold == null ? 0 : 1

  alarm_name          = "${local.name_stem}-aurora-connection-saturation"
  alarm_description   = "Condition: cluster connection count reaches the total the configured service task count and per-task pool size can open. Question: is the cluster running out of connections before it runs out of capacity? Action: reduce per-service pool size or bound the maximum task count; both are compute-side configuration."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.database_connection_threshold
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  statistic           = "Maximum"
  treat_missing_data  = "missing"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  dimensions = {
    DBClusterIdentifier = var.aurora_cluster_identifier
  }

  tags = merge(local.tags, {
    Signal = "aurora-connections"
  })
}

# ---------------------------------------------------------------------------
# Alarm 13 of 13: edge server-error rate at the content delivery network.
#
# WHY : Assumptions: CloudFront metrics publish to us-east-1 ONLY, because a
#       distribution is a global resource with no regional home. That is the real
#       and only constraint on this alarm, and it is narrower than the reason
#       this module used to give for having no CloudFront alarm at all -- which
#       was that global metrics "require a different provider region". They
#       require us-east-1 specifically, and both environment roots already set
#       aws_region to us-east-1, so for every composition this repository
#       actually ships the metrics are in this provider's region and the alarm is
#       creatable. Refactoring Rationale: the previous wording turned a
#       conditional constraint into a blanket impossibility and so omitted a
#       signal that was available the whole time; the guard below expresses the
#       constraint accurately instead.
#       Assumptions: aws_region is an INPUT, not a constant, so a caller may
#       compose this module in another region. The count guard therefore tests
#       the region rather than assuming it: outside us-east-1 the series does not
#       exist for this provider and an alarm on it would sit permanently in
#       INSUFFICIENT_DATA, which reads identically to a control that is passing.
#       Trade-offs: an error RATE is alarmed rather than an error COUNT, because
#       a distribution serving a static single-page application has a request
#       volume that varies by orders of magnitude between working hours and
#       overnight; a count threshold that is meaningful at one volume is noise or
#       silence at the other, whereas a rate is comparable across both.
#       Refactoring Rationale: the presence half of the guard reads
#       create_cloudfront_alarm rather than testing the identifier against null.
#       The identifier is the roots' own module.cloudfront_spa.distribution_id and
#       is unknown before the distribution exists, so a count derived from it
#       failed every plan with `Invalid count argument`; variables.tf records that
#       in full. The REGION half is unchanged, because the provider's region is
#       known during plan and is the constraint that actually decides whether the
#       series exists.
resource "aws_cloudwatch_metric_alarm" "cloudfront_5xx" {
  count = var.create_cloudfront_alarm && data.aws_region.current.region == "us-east-1" ? 1 : 0

  alarm_name          = "${local.name_stem}-cloudfront-5xx-rate"
  alarm_description   = "Condition: percentage of viewer requests answered with a server error by the distribution. Question: is the static delivery path failing, as distinct from the API path the api_5xx alarm watches? Action: compare against the origin bucket's access log before redeploying the built assets."
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = var.cloudfront_5xx_error_rate_threshold_percent
  evaluation_periods  = var.alarm_evaluation_periods
  datapoints_to_alarm = var.alarm_evaluation_periods
  period              = var.alarm_period_seconds
  namespace           = "AWS/CloudFront"
  metric_name         = "5xxErrorRate"
  statistic           = "Average"
  treat_missing_data  = "notBreaching"
  actions_enabled     = true
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]

  # WHY : Assumptions: the Region dimension is the literal string "Global" for a
  #       distribution, not the provider's region. CloudFront publishes every
  #       distribution metric under that fixed value, so substituting the region
  #       here would silently match no series and the alarm would never leave
  #       INSUFFICIENT_DATA.
  dimensions = {
    DistributionId = var.cloudfront_distribution_id
    Region         = "Global"
  }

  tags = merge(local.tags, {
    Signal = "cloudfront-5xx"
  })
}
