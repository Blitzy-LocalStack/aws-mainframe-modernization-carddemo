# =============================================================================
# infra/modules/sqs/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete public contract of the `sqs` module. main.tf declares twelve
#   queues -- six primary queues and one dead-letter queue for each -- and
#   nothing about them is visible outside this directory except what this file
#   publishes: for every one of the twelve, its queue URL, its ARN and its bare
#   name.
#
#   Each of those three forms exists because a different consumer can use only
#   that form. A URL is the address the SQS SendMessage, ReceiveMessage and
#   DeleteMessage calls take, so the URLs are what a calling root writes to
#   Parameter Store for a service to read at startup -- no service holds a
#   queue address of its own. An ARN is what an IAM policy `Resource` list and
#   a redrive target accept, so the ARNs are what the task-role policies owned
#   by the ecs-service and step-functions-batch modules are built from. A bare
#   name is what CloudWatch uses as the QueueName dimension of every SQS
#   metric, so the names are what the observability module alarms and
#   dashboards are built from.
#
#   The output NAMES below are consequently a one-way contract and not an
#   implementation detail. infra/envs/dev and infra/envs/prod reference them,
#   the two policy-owning modules reference them through those roots, and
#   authorization-service, account-service and reference-service reach their
#   queues through the parameters those roots write. Renaming one here breaks
#   every one of those callers at once.
#
#   Parameters: none. An `output` block accepts no input. The module's eleven
#   inputs are declared in variables.tf, which carries the type, the
#   description and the domain validation of each one.
#
#   Return values: the forty outputs below. Thirty-six are a single string
#   each -- the URL, the ARN and the name of one queue -- and each carries its
#   own `description` naming which queue it addresses, which baseline IBM MQ
#   queue that queue replaces, which of the three forms it is and who consumes
#   it. Three map(string) values carry the same URLs, ARNs and names keyed by
#   the twelve resource labels main.tf uses, and one object map publishes the
#   exact per-service IAM send/receive boundaries.
#
#   Errors:
#     * An `output` block raises no error of its own. Its value is either
#       computed from a resource attribute or the plan that would have produced
#       it has already failed for a reason main.tf or variables.tf reports.
#     * The failure this file exists to prevent is downstream and worth naming,
#       because it surfaces in the most expensive place. A service resolves its
#       queue URL from Parameter Store when its container starts, so an
#       identifier that is unpublished, misnamed or wired to the wrong
#       parameter does not fail at `apply` -- it fails as a task that will not
#       start, after the infrastructure has reported success.
#
# WHY (non-obvious design decisions):
#   - Assumptions: these outputs are the ONLY source of a runtime queue address
#     anywhere in the system. The messaging design admits no alternative path:
#     a queue URL is published here, written to Parameter Store by the calling
#     root, and read by the service at startup, so nothing is baked into a
#     container image or a configuration file and the same image is promotable
#     from dev to prod unchanged. ecs-service records the receiving half of
#     that same contract on its `ssm_parameter_arns` input.
#   - Trade-offs: thirty-six flat outputs AND three maps carrying the same values,
#     rather than one shape or the other. The duplication is real and is
#     accepted; the section on shape below states what each shape buys and what
#     the duplication costs.
#   - Alternatives Considered: nothing here is marked `sensitive`, and that is a
#     decision rather than an omission. The section below gives the reasoning
#     and what marking them would break.
#   - Refactoring Rationale: the baseline addressed its queues by compiling the
#     name into the program -- MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME at
#     app/app-vsam-mq/cbl/CODATE01.cbl:243 and at
#     app/app-vsam-mq/cbl/COACCT01.cbl:294, and a hard-coded reply-queue name
#     per flow at CODATE01.cbl:147 and COACCT01.cbl:198. A queue could not then
#     be renamed, moved or duplicated without recompiling every program that
#     named it. Publishing the address as a module output is what removes that
#     coupling, which is why this file is the contract and not a convenience.
# =============================================================================

# -----------------------------------------------------------------------------
# Three forms per queue, and why none of the three is redundant.
#
# WHY : Alternatives Considered: publishing one form per queue -- the URL, say,
#       or the ARN -- and letting a caller derive the rest. It is the obvious
#       economy and it is rejected because the derivation is string assembly
#       over an account identifier and a region: a caller would have to compose
#       "arn:aws:sqs:<region>:<aws-account-id>:<name>", or the matching endpoint
#       form, for itself. That would put an account identifier into the calling
#       configuration, add a `data "aws_caller_identity"` to every root that
#       wanted one, and produce a plausible-looking string that is wrong
#       whenever a partition, a region or a name changes. main.tf declines a
#       data source for exactly this reason; publishing all three forms is what
#       lets every caller stay declined too.
# WHY : Assumptions: the three forms are not interchangeable at the point of
#       use, and that is a property of the consuming APIs rather than a
#       preference. An SQS API call accepts a URL and rejects an ARN; an IAM
#       policy `Resource` and a `redrive_policy` target accept an ARN and
#       reject a URL; a CloudWatch metric dimension takes the bare name and
#       neither of the other two. A queue with only one of its three
#       identifiers published is therefore reachable by only one of its three
#       consumers.
# WHY : Assumptions: `url` and `name` are read from the resource rather than
#       recomposed from the naming locals in main.tf, even though the name is
#       composed there and could be re-used directly. Reading the attribute
#       means the value published is the one the service actually holds, so a
#       name the provider normalised, or one changed in main.tf without this
#       file being touched, cannot leave the two out of step.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Nothing here is marked `sensitive`.
#
# WHY : Alternatives Considered: marking the URLs -- or the whole file -- as
#       `sensitive`, on the reading that anything naming a queue is a detail of
#       the deployment. A queue URL and a queue ARN are ADDRESSES, not
#       credentials: holding one confers no access at all, because every send,
#       receive and delete against these queues is authorised by the
#       identity-based task-role policies owned by the ecs-service and
#       step-functions-batch modules. The resource policies in main.tf contain
#       denials only, so there is still no path by which possession of an
#       address becomes permission to use it. Withholding the address protects
#       nothing.
# WHY : Trade-offs: the marking would not be merely redundant, it would cost
#       three things that are worth more than the appearance of caution.
#       Sensitivity PROPAGATES: every downstream expression that consumed one
#       of these values would become sensitive too, so the Parameter Store
#       entries and the policy documents built from them would be redacted as
#       well. It would blank these values in `terraform plan` output, which is
#       the artifact .github/workflows/infra-ci.yml produces for a human to
#       review -- a plan that cannot show which queue a policy grants access to
#       cannot be reviewed for least privilege. And it would force every
#       calling root to unwrap each value with `nonsensitive()` before using
#       it, which is a marking and an immediate un-marking that leaves only the
#       noise behind.
# WHY : Assumptions: what genuinely is sensitive in this module is the
#       encryption key, and it is an INPUT rather than an output --
#       var.kms_key_arn arrives from the calling root, which already holds it,
#       so echoing it back would add a key ARN to this module's public surface
#       for no consumer at all. It is deliberately absent below.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# The six dead-letter queues are published too.
#
# WHY : Assumptions: a dead-letter queue whose identifiers are unpublished is a
#       dead-letter queue nobody can operate, and operating it needs a
#       different form for each of three tasks. Reading the message that
#       defeated a consumer is a ReceiveMessage call and needs the URL.
#       Alarming on its depth is a CloudWatch metric on
#       ApproximateNumberOfMessagesVisible, dimensioned on QueueName, and needs
#       the bare name -- which is the concrete consumer of the five
#       *_dlq_name outputs, through the observability module's
#       dead_letter_depth_threshold input. Standard-queue recovery may use
#       sqs:StartMessageMoveTask; the authorization FIFO dead-letter queues deny
#       that unfiltered operation and instead require controlled per-message
#       replay after reconciliation.
# WHY : Trade-offs: this doubles the output count, and a caller that only ever
#       sends and receives will use half of it. That is accepted because the
#       unused half is precisely the half needed on the day something fails,
#       and a dead-letter queue is provisioned for no other day. main.tf's own
#       note on dead-letter retention makes the same argument about the
#       evidence outliving the failure; publishing the identifiers is what
#       makes that evidence reachable.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Shape: thirty-six flat outputs, three maps over the same values, then one
# per-service IAM-boundary map.
#
# WHY : Alternatives Considered: three maps alone -- queue_urls, queue_arns and
#       queue_names keyed by logical name -- and nothing else. It is a third of
#       the text and it iterates directly. It is not sufficient on its own
#       because a map collapses twelve `description` strings into one: the reader
#       of the generated module README would find a single row reading "map of
#       queue name to URL" where the per-queue rows would have said which
#       baseline queue each address replaces and who consumes it. That is the
#       Return-values clause this file is answerable for, and
#       infra/.terraform-docs.yml is configured with read-comments disabled, so
#       a `description` is the only text that reaches that README -- a
#       rationale written in a comment beside a map key would not appear in it
#       at all.
# WHY : Alternatives Considered: thirty-six flat outputs alone, with no maps. It
#       documents perfectly and iterates not at all. Two consumers genuinely
#       iterate: the calling root creates one Parameter Store entry per queue
#       URL, and the observability module creates one dead-letter depth alarm
#       per queue. With flat outputs only, each of those becomes twelve
#       near-identical resource blocks in the caller, which is twelve places for
#       one of them to be forgotten. The maps make each a single `for_each`.
# WHY : Trade-offs: publishing both means every value appears twice in this
#       file, and a queue added to main.tf must be added in both places or the
#       two disagree. The exposure is bounded rather than open-ended: the queue
#       set is fixed at six plus six by the messaging design, and
#       variables.tf refuses a per-queue toggle for the same reason, so "add a
#       queue" is not a routine edit. The cost is paid once here; the
#       alternative charges every caller either a lost description or twelve
#       repeated blocks.
# WHY : Assumptions: the map keys are the resource labels main.tf uses --
#       pauth_request, pauth_request_dlq, pauth_reply, pauth_reply_dlq,
#       account_inquiry_request, account_inquiry_request_dlq,
#       date_inquiry_request, date_inquiry_request_dlq, inquiry_reply,
#       inquiry_reply_dlq, error and error_dlq -- rather than the composed queue
#       names. A composed name embeds var.name_prefix and var.environment, so a
#       caller keying on it would produce configuration whose resource
#       addresses changed with the environment, and every `for_each` over the
#       map would rename its instances between dev and prod. The labels are
#       stable across both roots.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Ordering: each primary queue precedes its dead-letter queue here, which is
# the reverse of main.tf.
#
# WHY : Trade-offs: main.tf declares each dead-letter queue FIRST because the
#       primary's redrive_policy reads its ARN, so that file reads in the order
#       it executes. No such edge exists between two outputs, and the reader of
#       a contract is looking for a queue rather than for its failure path, so
#       the primary leads here. The cost is that the two files in the same
#       directory list the same twelve queues in different orders; the benefit is
#       that each file is ordered by what its own reader is looking for.
#       terraform-docs sorts the generated README alphabetically regardless, so
#       neither order reaches the documentation.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Pending authorization: request. The FIFO queue replacing
# AWS.M2.CARDDEMO.PAUTH.REQUEST, and its dead-letter queue.
# -----------------------------------------------------------------------------

output "pauth_request_queue_url" {
  description = "Queue URL of the FIFO pending-authorization request queue, which replaces the baseline's AWS.M2.CARDDEMO.PAUTH.REQUEST, listed as the \"Input queue for authorization requests\" at app/app-authorization-ims-db2-mq/README.md:278. A URL is the address the SQS send, receive and delete calls take, so a calling root writes this value to Parameter Store for authorization-service to resolve at startup and receive authorization requests from."
  value       = aws_sqs_queue.pauth_request.url
}

output "pauth_request_queue_arn" {
  description = "ARN of the FIFO pending-authorization request queue (baseline AWS.M2.CARDDEMO.PAUTH.REQUEST). An ARN is the form an IAM policy Resource list and a redrive target accept, so a calling root places this value in the task-role policy statement -- owned by the ecs-service module -- that permits authorization-service to receive and delete from this queue. A queue URL is not accepted there."
  value       = aws_sqs_queue.pauth_request.arn
}

output "pauth_request_queue_name" {
  description = "Bare name of the FIFO pending-authorization request queue, carrying neither the account and region parts of an ARN nor the endpoint host of a URL. CloudWatch dimensions every SQS metric on QueueName, so this is the value the observability module needs to alarm on this queue's depth or age or to place it on a dashboard."
  value       = aws_sqs_queue.pauth_request.name
}

output "pauth_request_dlq_url" {
  description = "Queue URL of the dead-letter queue that receives a pending-authorization request after max_receive_count failed receives. An operator establishing what defeated the consumer reads the message body with a receive call against this URL, which is the only way to see a payload SQS has moved off the request queue."
  value       = aws_sqs_queue.pauth_request_dlq.url
}

output "pauth_request_dlq_arn" {
  description = "ARN of the dead-letter queue serving the FIFO pending-authorization request queue. It is the Resource form an IAM statement names for reviewed receive/delete access during reconciliation. Native sqs:StartMessageMoveTask is explicitly denied because it cannot filter one message group and can interleave quarantined messages with new traffic; replay is controlled per message after the failed group is reconciled."
  value       = aws_sqs_queue.pauth_request_dlq.arn
}

output "pauth_request_dlq_name" {
  description = "Bare name of the dead-letter queue serving the FIFO pending-authorization request queue. This is the QueueName dimension the observability module's dead-letter depth alarm is built on -- the alarm whose threshold defaults to the smallest breachable value, because this queue is empty in normal operation and any depth above zero is a real failure."
  value       = aws_sqs_queue.pauth_request_dlq.name
}

# -----------------------------------------------------------------------------
# Pending authorization: reply. The FIFO queue replacing
# AWS.M2.CARDDEMO.PAUTH.REPLY, and its dead-letter queue.
# -----------------------------------------------------------------------------

output "pauth_reply_queue_url" {
  description = "Queue URL of the FIFO pending-authorization reply queue, which replaces the baseline's AWS.M2.CARDDEMO.PAUTH.REPLY, listed as the \"Output queue for authorization responses\" at app/app-authorization-ims-db2-mq/README.md:279. Written to Parameter Store for authorization-service, whose transactional outbox publishes a reply here with a send call once the decision it reports has committed."
  value       = aws_sqs_queue.pauth_reply.url
}

output "pauth_reply_queue_arn" {
  description = "ARN of the FIFO pending-authorization reply queue (baseline AWS.M2.CARDDEMO.PAUTH.REPLY). This is the Resource a calling root names in the task-role policy statement granting authorization-service permission to send replies, and the form a consumer of those replies is granted receive permission against."
  value       = aws_sqs_queue.pauth_reply.arn
}

output "pauth_reply_queue_name" {
  description = "Bare name of the FIFO pending-authorization reply queue. Consumed by the observability module as the QueueName metric dimension; its retention exceeds the complete configured retry budget, while consumer-side expiresAt remains the authority that decides whether a reply is still actionable."
  value       = aws_sqs_queue.pauth_reply.name
}

output "pauth_reply_dlq_url" {
  description = "Queue URL of the dead-letter queue that receives a pending-authorization reply after max_receive_count failed receives. An operator investigating an authorization whose reply never arrived reads it here; the source retention is validated to survive every receive cycle, and this fourteen-day quarantine keeps the failed delivery available for reconciliation."
  value       = aws_sqs_queue.pauth_reply_dlq.url
}

output "pauth_reply_dlq_arn" {
  description = "ARN of the dead-letter queue serving the FIFO pending-authorization reply queue. Named by reviewed receive/delete permissions for reconciliation. Native sqs:StartMessageMoveTask is denied because unfiltered bulk redrive cannot preserve the per-card ordering boundary across quarantine."
  value       = aws_sqs_queue.pauth_reply_dlq.arn
}

output "pauth_reply_dlq_name" {
  description = "Bare name of the dead-letter queue serving the FIFO pending-authorization reply queue. Consumed by the observability module as the QueueName dimension of its dead-letter depth alarm."
  value       = aws_sqs_queue.pauth_reply_dlq.name
}


# -----------------------------------------------------------------------------
# Account inquiry: request. The account-service-exclusive standard queue
# refining the shared baseline CARDDEMO.REQUEST.QUEUE, and its dead-letter
# queue.
# -----------------------------------------------------------------------------

output "account_inquiry_request_queue_url" {
  description = "Queue URL of the standard account-details inquiry request queue consumed only by account-service. It is one of two target queues refining the baseline's shared CARDDEMO.REQUEST.QUEUE; producer-side routing prevents account-service and reference-service competing for a message only the other can process."
  value       = aws_sqs_queue.account_inquiry_request.url
}

output "account_inquiry_request_queue_arn" {
  description = "ARN of the account-details inquiry request queue. The account-service task role receives and deletes only on this ARN, while the producer receives SendMessage on this exact ARN; reference-service receives no permission to it."
  value       = aws_sqs_queue.account_inquiry_request.arn
}

output "account_inquiry_request_queue_name" {
  description = "Bare name of the account-details inquiry request queue, used as the CloudWatch QueueName dimension for account-flow depth and age without date-flow traffic obscuring either metric."
  value       = aws_sqs_queue.account_inquiry_request.name
}

output "account_inquiry_request_dlq_url" {
  description = "Queue URL of the dead-letter queue receiving account-details inquiries after max_receive_count failed receives. Its flow-specific destination prevents an operator redriving the message to the date queue."
  value       = aws_sqs_queue.account_inquiry_request_dlq.url
}

output "account_inquiry_request_dlq_arn" {
  description = "ARN of the dead-letter queue serving the account-details inquiry request queue, for exact operator receive and StartMessageMoveTask permissions."
  value       = aws_sqs_queue.account_inquiry_request_dlq.arn
}

output "account_inquiry_request_dlq_name" {
  description = "Bare name of the dead-letter queue serving account-details inquiry requests, used as the QueueName dimension of its flow-specific dead-letter alarm."
  value       = aws_sqs_queue.account_inquiry_request_dlq.name
}

# -----------------------------------------------------------------------------
# Date inquiry: request. The reference-service-exclusive standard queue
# refining the shared baseline CARDDEMO.REQUEST.QUEUE, and its dead-letter
# queue.
# -----------------------------------------------------------------------------

output "date_inquiry_request_queue_url" {
  description = "Queue URL of the standard date-conversion inquiry request queue consumed only by reference-service. Producer-side routing to this URL prevents an account-service consumer from stealing and hiding a CODATE01-equivalent request."
  value       = aws_sqs_queue.date_inquiry_request.url
}

output "date_inquiry_request_queue_arn" {
  description = "ARN of the date-conversion inquiry request queue. The reference-service task role receives and deletes only on this ARN, while account-service receives no permission to it."
  value       = aws_sqs_queue.date_inquiry_request.arn
}

output "date_inquiry_request_queue_name" {
  description = "Bare name of the date-conversion inquiry request queue, used as the CloudWatch QueueName dimension for date-flow depth and age independently of account inquiries."
  value       = aws_sqs_queue.date_inquiry_request.name
}

output "date_inquiry_request_dlq_url" {
  description = "Queue URL of the dead-letter queue receiving date-conversion inquiries after max_receive_count failed receives. Its flow-specific destination keeps redrive on the reference-service path."
  value       = aws_sqs_queue.date_inquiry_request_dlq.url
}

output "date_inquiry_request_dlq_arn" {
  description = "ARN of the dead-letter queue serving date-conversion inquiry requests, for exact operator receive and StartMessageMoveTask permissions."
  value       = aws_sqs_queue.date_inquiry_request_dlq.arn
}

output "date_inquiry_request_dlq_name" {
  description = "Bare name of the dead-letter queue serving date-conversion inquiry requests, used as the QueueName dimension of its flow-specific dead-letter alarm."
  value       = aws_sqs_queue.date_inquiry_request_dlq.name
}

# -----------------------------------------------------------------------------
# Account inquiry: reply. The standard queue replacing CARDDEMO.RESPONSE.QUEUE,
# and its dead-letter queue. This one queue serves BOTH inquiry flows; the
# description below carries the reason, because the baseline's two per-flow
# reply-queue names are otherwise read as two queues missing here.
# -----------------------------------------------------------------------------

output "inquiry_reply_queue_url" {
  description = "Queue URL of the standard account-inquiry reply queue, which replaces the baseline's CARDDEMO.RESPONSE.QUEUE, defined as DEFINE QLOCAL('CARDDEMO.RESPONSE.QUEUE') at app/app-vsam-mq/README.md:54. This ONE queue serves both inquiry flows even though the baseline hard-codes a reply-queue name per flow -- MOVE 'CARD.DEMO.REPLY.DATE' TO REPLY-QUEUE-NAME at app/app-vsam-mq/cbl/CODATE01.cbl:147 and MOVE 'CARD.DEMO.REPLY.ACCT' TO REPLY-QUEUE-NAME at app/app-vsam-mq/cbl/COACCT01.cbl:198 -- because the target routes a reply by the replyToQueueUrl message attribute the request carries rather than by a per-flow queue. No second reply queue is missing. Written to Parameter Store for account-service and reference-service to publish replies to."
  value       = aws_sqs_queue.inquiry_reply.url
}

output "inquiry_reply_queue_arn" {
  description = "ARN of the standard account-inquiry reply queue (baseline CARDDEMO.RESPONSE.QUEUE, plus the two per-flow reply names at CODATE01.cbl:147 and COACCT01.cbl:198 that this single queue absorbs). This is the Resource a calling root names to let account-service and reference-service send replies, and to let the requester receive them."
  value       = aws_sqs_queue.inquiry_reply.arn
}

output "inquiry_reply_queue_name" {
  description = "Bare name of the standard account-inquiry reply queue. Consumed by the observability module as the QueueName metric dimension. Because one queue carries both flows' replies, its metrics are shared: a backlog here is not attributable to the date flow or the account flow from queue metrics alone, and attribution comes from the correlation identifier on each message and from consumer-side metrics instead."
  value       = aws_sqs_queue.inquiry_reply.name
}

output "inquiry_reply_dlq_url" {
  description = "Queue URL of the dead-letter queue that receives an account-inquiry reply after max_receive_count failed receives. The reply source retention is validated to survive the complete retry budget, and an operator reads the quarantined message here using its opaque correlation identifier to tie it back to the request."
  value       = aws_sqs_queue.inquiry_reply_dlq.url
}

output "inquiry_reply_dlq_arn" {
  description = "ARN of the dead-letter queue serving the standard account-inquiry reply queue. Named as the Resource of the IAM statement that lets an operator drain it or issue the sqs:StartMessageMoveTask call that returns its messages to the inquiry reply queue."
  value       = aws_sqs_queue.inquiry_reply_dlq.arn
}

output "inquiry_reply_dlq_name" {
  description = "Bare name of the dead-letter queue serving the standard account-inquiry reply queue. Consumed by the observability module as the QueueName dimension of its dead-letter depth alarm."
  value       = aws_sqs_queue.inquiry_reply_dlq.name
}


# -----------------------------------------------------------------------------
# Terminal error sink. The standard queue replacing CARD.DEMO.ERROR, and its
# dead-letter queue.
# -----------------------------------------------------------------------------

output "error_queue_url" {
  description = "Queue URL of the standard terminal error queue, which replaces the baseline's CARD.DEMO.ERROR -- the single error sink both inquiry programs write to, at MOVE 'CARD.DEMO.ERROR' TO ERROR-QUEUE-NAME in app/app-vsam-mq/cbl/CODATE01.cbl:243 and app/app-vsam-mq/cbl/COACCT01.cbl:294. Written to Parameter Store for every producer in the messaging design, so any service that cannot complete a message exchange reports it to one place rather than to a sink of its own."
  value       = aws_sqs_queue.error.url
}

output "error_queue_arn" {
  description = "ARN of the standard terminal error queue (baseline CARD.DEMO.ERROR). This is the Resource a calling root names in the send-permission statement of every producing service's task-role policy, and in the receive-permission statement of whatever drains the sink to raise an alert or record the failure."
  value       = aws_sqs_queue.error.arn
}

output "error_queue_name" {
  description = "Bare name of the standard terminal error queue. Consumed by the observability module as the QueueName metric dimension. This queue's depth is the one queue metric that is a direct failure signal rather than a throughput signal, because nothing writes to it in normal operation."
  value       = aws_sqs_queue.error.name
}

output "error_dlq_url" {
  description = "Queue URL of the dead-letter queue serving the terminal error queue. An error sink with a dead-letter queue of its own reads oddly and is deliberate: the error queue is consumed like any other, so a message that defeats even the error handler would otherwise be redelivered indefinitely or lost, and this URL is where an operator can still read it."
  value       = aws_sqs_queue.error_dlq.url
}

output "error_dlq_arn" {
  description = "ARN of the dead-letter queue serving the terminal error queue. Named as the Resource of the IAM statement that lets an operator drain it or issue the sqs:StartMessageMoveTask call that returns its messages to the error queue."
  value       = aws_sqs_queue.error_dlq.arn
}

output "error_dlq_name" {
  description = "Bare name of the dead-letter queue serving the terminal error queue. Consumed by the observability module as the QueueName dimension of its dead-letter depth alarm; a non-zero depth here means a failure report itself failed to be processed, which is the deepest failure this module can surface."
  value       = aws_sqs_queue.error_dlq.name
}

# -----------------------------------------------------------------------------
# The same values, keyed for iteration. Each map carries all twelve queues; the
# keys are the twelve resource labels main.tf uses, and are listed in each
# description so a caller need not read main.tf to know what it may index.
# -----------------------------------------------------------------------------

output "queue_urls" {
  description = "Map of logical queue name to queue URL for all twelve queues, keyed by the resource labels main.tf uses. The account_inquiry_request and date_inquiry_request keys are intentionally separate so each consumer resolves only its own work queue."
  value = {
    pauth_request               = aws_sqs_queue.pauth_request.url
    pauth_request_dlq           = aws_sqs_queue.pauth_request_dlq.url
    pauth_reply                 = aws_sqs_queue.pauth_reply.url
    pauth_reply_dlq             = aws_sqs_queue.pauth_reply_dlq.url
    account_inquiry_request     = aws_sqs_queue.account_inquiry_request.url
    account_inquiry_request_dlq = aws_sqs_queue.account_inquiry_request_dlq.url
    date_inquiry_request        = aws_sqs_queue.date_inquiry_request.url
    date_inquiry_request_dlq    = aws_sqs_queue.date_inquiry_request_dlq.url
    inquiry_reply               = aws_sqs_queue.inquiry_reply.url
    inquiry_reply_dlq           = aws_sqs_queue.inquiry_reply_dlq.url
    error                       = aws_sqs_queue.error.url
    error_dlq                   = aws_sqs_queue.error_dlq.url
  }
}

output "queue_arns" {
  description = "Map of logical queue name to queue ARN for all twelve queues, keyed identically to queue_urls. Callers pass only the exact per-service subset into ecs-service's sqs_send_queue_arns and sqs_receive_queue_arns; using values(...) for all queues would defeat the confused-deputy boundary."
  value = {
    pauth_request               = aws_sqs_queue.pauth_request.arn
    pauth_request_dlq           = aws_sqs_queue.pauth_request_dlq.arn
    pauth_reply                 = aws_sqs_queue.pauth_reply.arn
    pauth_reply_dlq             = aws_sqs_queue.pauth_reply_dlq.arn
    account_inquiry_request     = aws_sqs_queue.account_inquiry_request.arn
    account_inquiry_request_dlq = aws_sqs_queue.account_inquiry_request_dlq.arn
    date_inquiry_request        = aws_sqs_queue.date_inquiry_request.arn
    date_inquiry_request_dlq    = aws_sqs_queue.date_inquiry_request_dlq.arn
    inquiry_reply               = aws_sqs_queue.inquiry_reply.arn
    inquiry_reply_dlq           = aws_sqs_queue.inquiry_reply_dlq.arn
    error                       = aws_sqs_queue.error.arn
    error_dlq                   = aws_sqs_queue.error_dlq.arn
  }
}

# WHY : Refactoring Rationale: exposing the undifferentiated queue_arns map is
#       useful for operations and dangerous as an IAM shortcut. A root that
#       grants values(queue_arns) makes every service a producer and consumer of
#       every queue, so an inbound replyToQueueUrl becomes an effective
#       authorization decision. These three closed sets are the values intended
#       for ecs-service's sqs_send_queue_arns and sqs_receive_queue_arns inputs;
#       each SendMessage statement is therefore bounded to an environment-owned
#       reply/error queue even if message validation regresses.
# WHY : Refactoring Rationale: the batch_service entry below was ABSENT, and its
#       absence was the infrastructure half of a feature that was dead end to
#       end. batch-service ships a queue configuration gated on the error sink's
#       address, and its BatchStepLedger reports every terminal step failure
#       through it -- but no root published the address and no policy granted the
#       send, so the gate never opened and the sink stayed empty however a night
#       ended. Granting send here is what makes the Java path reachable; the
#       address itself is published by each environment root as the batch
#       workload's own runtime parameter.
# WHY : Assumptions: the entry grants SEND on the error queue and RECEIVE on
#       nothing, and the empty receive list is a statement rather than a
#       placeholder. batch-service consumes no queue at all: no reference batch
#       program contains an MQ verb, the module is argument-driven from its
#       --job= option, and its own profile switches listener startup off. An
#       entry with a receive list would license a consumer this module does not
#       have and cannot acquire without a code change that would fail review.
output "service_queue_permissions" {
  description = "Exact per-service SQS IAM boundaries. authorization-service receives pauth_request and sends only pauth_reply; account-service receives only account_inquiry_request and sends only inquiry_reply/error; reference-service receives only date_inquiry_request and sends only inquiry_reply/error; batch-service receives NOTHING and sends only error. Pass these lists to ecs-service rather than granting values(queue_arns)."
  value = {
    authorization_service = {
      receive = [aws_sqs_queue.pauth_request.arn]
      send    = [aws_sqs_queue.pauth_reply.arn]
    }
    # WHY : Refactoring Rationale: this entry did not exist, and its absence was the IAM
    #       half of a producer that could not publish. batch-service carries a queue
    #       configuration whose whole purpose is to notify this terminal sink that a run
    #       failed -- the migration plan scopes such a configuration to exactly two
    #       bounded contexts, batch and authorization -- and the task role it ran under
    #       held no sqs:SendMessage statement for any queue at all. So the first send the
    #       producer ever attempted would have been refused, on the failure path, which is
    #       the least-exercised path in the deployment.
    # WHY : Assumptions: receive is EMPTY and stays empty. batch-service declares no
    #       listener of any kind: it selects its work from a command argument the
    #       orchestrator supplies and from its own tables, never from a queue. Granting a
    #       receive action it has no consumer for would let a batch task drain a queue
    #       another context is the sole consumer of, which is the failure mode a per-service
    #       boundary exists to make impossible. ecs-service derives the cardinality of its
    #       queue policy from the LENGTH of each list, so a send-only entry produces a
    #       send-only statement rather than an empty receive statement.
    # WHY : Assumptions: the send list is the error queue alone and not the reply queues the
    #       two inquiry contexts also send to. batch answers no requester, so a reply
    #       destination is a capability it can only misuse.
    batch_service = {
      receive = []
      send    = [aws_sqs_queue.error.arn]
    }
    account_service = {
      receive = [aws_sqs_queue.account_inquiry_request.arn]
      send = [
        aws_sqs_queue.inquiry_reply.arn,
        aws_sqs_queue.error.arn,
      ]
    }
    reference_service = {
      receive = [aws_sqs_queue.date_inquiry_request.arn]
      send = [
        aws_sqs_queue.inquiry_reply.arn,
        aws_sqs_queue.error.arn,
      ]
    }
  }
}

output "queue_names" {
  description = "Map of logical queue name to bare queue name for all twelve queues, keyed identically to queue_urls. CloudWatch dimensions SQS metrics on QueueName, so the split account/date request keys also split their depth, age and dead-letter signals."
  value = {
    pauth_request               = aws_sqs_queue.pauth_request.name
    pauth_request_dlq           = aws_sqs_queue.pauth_request_dlq.name
    pauth_reply                 = aws_sqs_queue.pauth_reply.name
    pauth_reply_dlq             = aws_sqs_queue.pauth_reply_dlq.name
    account_inquiry_request     = aws_sqs_queue.account_inquiry_request.name
    account_inquiry_request_dlq = aws_sqs_queue.account_inquiry_request_dlq.name
    date_inquiry_request        = aws_sqs_queue.date_inquiry_request.name
    date_inquiry_request_dlq    = aws_sqs_queue.date_inquiry_request_dlq.name
    inquiry_reply               = aws_sqs_queue.inquiry_reply.name
    inquiry_reply_dlq           = aws_sqs_queue.inquiry_reply_dlq.name
    error                       = aws_sqs_queue.error.name
    error_dlq                   = aws_sqs_queue.error_dlq.name
  }
}
