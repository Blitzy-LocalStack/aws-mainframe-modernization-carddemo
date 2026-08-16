# =============================================================================
# infra/modules/ecs-service/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The entire public surface of the reusable ecs-service module. Seventeen
#   outputs and nothing else -- no resource, no data source, no local and no
#   variable is declared here, because a module's outputs are its return values
#   and this file is the only place they are stated. Each of the two
#   environment roots, infra/envs/dev and infra/envs/prod, instantiates the
#   module NINE times, once per key of its `workloads` local: the seven online
#   contexts auth, account, card, transaction, reference, authorization and
#   reporting, plus the two non-online workloads batch and data-migration. Every
#   name below therefore resolves EIGHTEEN times across the two roots, and each
#   name is a ONE-WAY CONTRACT -- Terraform resolves module.<instance>.<output> at
#   the caller's own call site, so renaming an output here breaks the caller
#   rather than this file, and the resulting error names the caller's
#   expression instead of this line.
#
#   Assumptions: all three counts in this paragraph are MEASUREMENTS derived the
#   same way, and each is checkable in one command:
#   `grep -c '^output "' infra/modules/ecs-service/outputs.tf` gives seventeen,
#   each root's `local.workloads` has nine keys -- seven from `local.online_services`
#   plus `batch` and `data-migration` -- and the product with two roots is eighteen.
#   Trade-offs: three figures in one paragraph is three things to keep true, and
#   they are stated anyway because every section of this file quotes them; a file
#   whose sections disagree is worse than one that is uniformly stale, since a
#   reader then has no way to tell which section was measured.
#
# Parameters:
#   None. An output block accepts no parameters. Every value below is read from
#   one of exactly three places, all inside this module: a resource declared in
#   main.tf, a local composed there, or an input declared in variables.tf. That is
#   a standing constraint rather than tidiness, because this directory is never
#   applied directly -- CI reaches it only transitively -- so a reference to an
#   input variables.tf does not declare, or to an attribute main.tf's resources do
#   not expose, surfaces at the calling root rather than here.
#
# Return values:
#   Seventeen values -- sixteen strings and one number -- grouped below in the
#   order main.tf creates the resources behind them: the log group, the two IAM
#   roles, the task definition and its container, the target group, the
#   service, the autoscaling target. HCL declares no type on an output, so
#   every description below states its value's type in words. SIX of the
#   seventeen are wired into a sibling module by name at a root call site, and
#   say so where they are declared. Each entry below was verified by reading the
#   expression that consumes it rather than by recollection:
#     - target_group_arn        attached to a listener rule by infra/modules/alb,
#                               through the roots' `service_routes` map
#     - task_definition_arn     started by infra/modules/step-functions-batch, for
#                               the batch, data-migration and reporting instances
#     - container_name          addressed by name in that module's run-task
#                               container overrides, same three instances
#     - target_group_arn_suffix the CloudWatch dimension
#                               infra/modules/observability needs, passed for
#                               the seven online services
#     - task_role_arn           and
#     - execution_role_arn      enumerated into step-functions-batch's
#                               `pass_role_arns`, six entries for three images,
#                               which become the Resource of one iam:PassRole
#                               statement
#
#   Assumptions: `log_group_name` is deliberately NOT among the six, even though
#   infra/modules/observability plainly consumes log groups. That module receives
#   `log_group_names` from the roots' `local.lambda_log_group_names` and
#   `vpc_flow_log_group_name` from the network module, and no root expression reads
#   this module's `log_group_name` at all. Trade-offs: a cross-module inventory is
#   only worth stating if it is exact -- over-crediting one output makes a safe
#   rename look dangerous, and under-crediting one makes a dangerous rename look
#   safe -- which is why each entry above cites the expression that consumes it.
#
#   Assumptions: the eleven names not listed above are NOT dead. Each root
#   re-exports this module's complete output object per workload as its
#   `ecs_workloads` output, so every name here is readable with
#   `terraform output ecs_workloads` -- that grouped re-export is their consumer,
#   and it is an operator and script contract rather than a module one. They are
#   distinguished from the six above because renaming one of those six breaks a
#   sibling module's wiring at plan time, whereas renaming one of the eleven
#   changes only what an operator reads.
#
# Exceptions or errors:
#   - Six outputs are null for two of the nine instantiations rather than
#     absent: service_name, service_arn, target_group_arn, target_group_name,
#     target_group_arn_suffix and autoscaling_target_resource_id. Each root
#     instantiates this module once per key of its `workloads` local -- seven
#     online services plus `batch` and `data-migration` -- and passes
#     create_service, attach_load_balancer and enable_autoscaling all as that
#     key's `online` flag. ADR-002 runs batch as Step-Functions-invoked Fargate
#     tasks rather than as a long-running service, and the ETL is a load step
#     rather than a service at all, so both are declared not online and the
#     three resources behind those six values are never created for either.
#     Assumptions: "two of the nine" is measured against each root's
#     `local.workloads`, whose non-online keys are `batch` and `data-migration`.
#     Each output's own description restates its nullability condition as well,
#     because a caller reading only the description it uses must learn the
#     nullability there rather than from a failed plan.
#   - A caller that indexes or interpolates one of those six nulls, or passes
#     one into an argument that requires a value, fails at plan -- and the
#     error names the caller's expression, not this file. Wiring the batch
#     instantiation therefore means not reading those six at all.
#   - No output is marked sensitive, so every value appears in plan output in
#     full. That is a decision rather than an oversight and its reasoning is
#     recorded in the closing block of this file.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: the outputs mirror main.tf's own dependency
#     ordering rather than being alphabetical or ranked by importance, so the two
#     files can be read side by side in one order and no output appears before the
#     resource that produces it. Alphabetical would have opened the file with
#     autoscaling_target_resource_id -- the most conditional value in it -- and
#     split the three target-group outputs away from one another.
#   - Alternatives Considered: every conditional value is read with one(),
#     which returns the single element of a zero-or-one list or else null.
#     Wrapping a bare [0] index in try() was the alternative and is rejected:
#     try() also absorbs errors it was not aimed at, so a mistyped attribute
#     would quietly resolve to null instead of failing validation, whereas
#     one() expresses zero-or-one exactly and lets every unrelated error
#     through.
#   - Trade-offs: publishing a nullable value was chosen over both
#     alternatives to it. The full reasoning sits at target_group_arn below,
#     which is where the choice bites hardest.
# =============================================================================


# -----------------------------------------------------------------------------
# Log destination.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this group is where the baseline's SYSOUT and SYSPRINT job
#       output goes, and publishing the NAME is what stops a consumer recomposing
#       it from name_prefix, service_name and environment and owning a second copy
#       of the composition rule. Reading aws_cloudwatch_log_group.this.name rather
#       than local.log_group_name is equally deliberate: the local is a plain
#       string that resolves whether or not the group exists, while the resource
#       attribute carries a dependency edge, so a consumer creating a metric filter
#       against this name cannot be ordered before the group it filters.
output "log_group_name" {
  description = <<-EOT
    Name string of the CloudWatch Logs group every task in this service writes
    to, in the form /aws/ecs/<name_prefix>-<service_name>-<environment>.
    Reaches the environment's `ecs_workloads` output, which publishes this whole
    object per workload, and is read from there by an operator tailing one
    service during the batch window. No sibling MODULE consumes it: both roots
    build this service's dashboard and alarm set inside
    infra/modules/observability from the cluster name, the target-group ARN
    suffix and the metric dimensions, and pass this module no log-group name at
    all. Never null: the log group is created for all nine instantiations,
    including the two -- batch and data-migration -- that have a task definition
    and no service.
  EOT
  value       = aws_cloudwatch_log_group.this.name
}

# WHY : Assumptions: main.tf already trims the trailing ":*" into
#       local.log_group_arn, because the two Resource forms its execution
#       policy needs differ only by that suffix and the provider attribute has
#       not consistently carried one. Publishing that normalised local rather
#       than the raw attribute means every consumer receives the same form, so
#       no caller can append ":*" to a value that already ended in one and
#       produce a Resource element matching nothing.
#       Alternatives Considered: publishing aws_cloudwatch_log_group.this.arn
#       untouched, which is the more literal value. Rejected because it would
#       push the normalisation main.tf performs internally out to every caller,
#       and a caller that skipped it would fail by silently matching no log
#       group rather than by erroring.
output "log_group_arn" {
  description = <<-EOT
    ARN string of the same log group, normalised to carry no trailing ":*"
    suffix. Consumed wherever an ARN rather than a name is required -- a
    CloudWatch Logs resource policy, a subscription-filter destination, or an
    IAM statement scoping logs:PutLogEvents -- while the awslogs driver and
    observability's metric filters take the name form above. A consumer
    scoping to the log STREAMS inside the group appends ":*" to this value
    itself. Never null, for the same reason as the name.
  EOT
  value       = local.log_group_arn
}


# -----------------------------------------------------------------------------
# The two IAM roles.
# -----------------------------------------------------------------------------

# WHY : Assumptions: RACF has no cloud analogue and is not ported. Its role is
#       filled by one least-privilege task role per service together with
#       Cognito groups, which makes this ARN the mechanism by which a
#       per-service privilege boundary is expressible at all.
# WHY : Refactoring Rationale: this note claimed that "main.tf deliberately
#       attaches no statement of its own to this role" and that what a service
#       may reach is therefore "exactly what a caller grants to this ARN and
#       nothing besides". Both statements were false. main.tf attaches THREE
#       inline policies to this role -- aws_iam_role_policy.task from the
#       caller's own document, and the module-composed task_sqs and
#       task_online_write_gate -- and the last two exist
#       precisely so that a caller cannot widen or replace them. The accurate
#       division is stated in the description below, because a reader deciding
#       where to grant something has to know that this role already carries
#       module-owned statements.
# WHY : Assumptions: the one-directional property the old note was reaching for
#       is real and is narrower than it claimed: the module writes no BUSINESS
#       resource access onto this role. Queue actions are bounded to the exact
#       ARNs a caller passes and the flag read is bounded to one parameter, so no
#       statement composed here can reach another bounded context's data.
# WHY : Refactoring Rationale: a fourth policy, task_telemetry, stood beside these
#       and is withdrawn with the collector sidecar it served -- it granted this
#       role log-stream writes on its own group plus the two X-Ray ingestion
#       actions, and the latter was the only wildcard Resource anywhere on this
#       role. Both counts above are restated rather than left standing, and the
#       role's posture is now stronger than the sentence they were qualifying:
#       neither a wildcard action nor a wildcard resource reaches it.
output "task_role_arn" {
  description = <<-EOT
    ARN string of the IAM role the APPLICATION assumes at run time, as
    distinct from execution_role_arn below, which ECS assumes in order to
    start the task. This ARN is the identity least privilege is expressed
    against, and both roots read it: infra/modules/step-functions-batch takes
    the batch, data-migration and reporting values as the Resource of its one
    iam:PassRole statement, and a resource-owning module names it in a resource
    policy where a grant belongs with the resource rather than with the
    workload. The role is not empty when it arrives: main.tf attaches the
    caller's task_role_policy_json plus two module-composed statements -- the
    exact-queue actions and the one-parameter write-gate read -- so what the
    service may reach is those three things and nothing besides. Never null: both
    roles are
    created for all nine instantiations.
  EOT
  value       = aws_iam_role.task.arn
}

# WHY : Assumptions: about the AWS API shape rather than a preference. Both
#       aws_iam_role_policy and aws_iam_role_policy_attachment identify their
#       target role by NAME, while every resource policy that grants TO a role
#       names it by ARN, so the two forms are not interchangeable and a caller
#       holding only one of them would have to derive the other. main.tf uses
#       both forms on this very role for exactly that reason -- the role's id
#       for its inline policy and its name for the managed-policy attachments.
output "task_role_name" {
  description = <<-EOT
    Name string -- not the ARN -- of the same application task role. Consumed
    by an environment root that attaches a further policy to the role after
    this module returns, since the Terraform resources that attach a policy
    take a role name while the resource policies that grant to a role take the
    ARN above. Never null.
  EOT
  value       = aws_iam_role.task.name
}

# WHY : Assumptions: one trust policy backs both roles, because the difference
#       between them is what each may DO and not who may assume it -- ECS
#       assumes the execution role to start the task, before any container
#       exists, and assumes the task role on the application's behalf once it
#       is running. Publishing both ARNs under names that state which is which
#       is the cheapest available guard against a caller granting application
#       permissions to the wrong one, a mistake that produces no error at all:
#       the apply succeeds, and the application is denied at run time.
output "execution_role_arn" {
  description = <<-EOT
    ARN string of the IAM role ECS ITSELF assumes, before the container
    exists, to pull the image from Amazon ECR, resolve the configured
    Parameter Store parameters and Secrets Manager secrets, and open the log
    stream. This is not the role the application code runs as -- that is
    task_role_arn above, and conflating the two is the classic ECS IAM
    mistake, because a permission the application needs granted here is never
    seen by the application. Consumed by an environment root or an audit
    reviewer that needs to name the role; main.tf already grants it inline
    everything this module requires of it. Never null.
  EOT
  value       = aws_iam_role.execution.arn
}

# WHY : Assumptions: the same AWS API shape recorded at task_role_name above.
#       Published even though this module writes the execution role's only
#       policy itself, because a root attaching an AWS-managed policy to it
#       would otherwise be the single caller forced to cut a name back out of
#       an ARN.
output "execution_role_name" {
  description = <<-EOT
    Name string -- not the ARN -- of the same task execution role, published
    for the same name-versus-ARN reason given at task_role_name above.
    Consumed by a root attaching an additional policy to the execution role.
    Never null.
  EOT
  value       = aws_iam_role.execution.name
}


# -----------------------------------------------------------------------------
# The task definition and its single container.
# -----------------------------------------------------------------------------

# WHY : Assumptions: ADR-002 puts batch on Step-Functions-invoked Fargate tasks
#       rather than on a long-running service, so for that one instantiation
#       this output and container_name below are the WHOLE interface -- a state
#       machine needs a task definition it can start and a container it can
#       address, and nothing else this module produces. That is precisely why
#       main.tf creates the task definition unconditionally while gating the
#       service, and why this output is never null.
#       Trade-offs: a revision-qualified ARN pins a consumer to the revision
#       that was current at apply. That is reproducible, which is what a
#       reviewed plan wants, but it does not pick up a later revision;
#       task_definition_family below is the unpinned form. Both are published
#       so the caller chooses, rather than this module choosing on its behalf.
output "task_definition_arn" {
  description = <<-EOT
    Revision-qualified ARN string of the task definition, so the value changes
    every time a new revision is registered. Consumed by
    infra/modules/step-functions-batch, whose responsibility includes
    task-definition wiring: its state machine starts a task through the
    synchronous run-task integration, and its execution role scopes
    ecs:RunTask to this ARN alongside ecs:StopTask, ecs:DescribeTasks and
    iam:PassRole. Never null -- the task definition is created for all nine
    instantiations, including the two -- batch and data-migration -- that have a
    task definition and no service.
  EOT
  value       = aws_ecs_task_definition.this.arn
}

# WHY : Assumptions: ECS resolves an unqualified family name to that family's
#       latest ACTIVE revision, whereas a revision-qualified ARN addresses one
#       fixed revision. Those are two genuinely different contracts rather than
#       two spellings of one, so both are published and the choice belongs to
#       the caller that knows which behaviour it wants.
#       Trade-offs: publishing both admits a caller mixing them, pinning one
#       state while floating another. The alternative was publishing only the
#       pinned ARN, which would have forced any caller wanting
#       latest-revision behaviour to rebuild the family string from
#       name_prefix, service_name and environment -- re-deriving a composed
#       name this module already owns, and owning a second copy of the
#       composition rule.
output "task_definition_family" {
  description = <<-EOT
    Family name string of the task definition, carrying no revision suffix.
    Consumed by a caller that wants ECS to resolve the family's latest active
    revision at run time instead of the revision this apply registered -- a
    Step Functions state that should pick up a redeployed image without a
    Terraform apply, for instance. Never null.
  EOT
  value       = aws_ecs_task_definition.this.family
}

output "task_definition_family_arn" {
  description = "Revisionless task-definition family ARN assembled from the module's provider-resolved partition, Region and account identity. Step Functions consumes this form so workflow creation does not depend on the reporting task definition that later reads the workflow ARN from Parameter Store."
  value       = "arn:${data.aws_partition.current.partition}:ecs:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:task-definition/${local.resource_name}"
}

# WHY : Assumptions: a ContainerOverrides entry in the synchronous run-task
#       integration identifies the container it overrides BY NAME, and every
#       batch step passes its arguments that way, so this name is a value two
#       modules have to agree on rather than a label. Publishing the resolved
#       local rather than var.container_name is the entire point of this
#       output: the input is null for the seven services that let the module
#       derive a name from service_name, so a consumer reading the input would
#       receive null for exactly the callers that did not set it, and would
#       have to re-implement main.tf's coalesce to recover the real value.
output "container_name" {
  description = <<-EOT
    Name string of the single container inside the task definition, already
    resolved -- it is var.container_name where a caller set one and
    var.service_name otherwise, so a consumer never has to know which
    happened. Consumed by infra/modules/step-functions-batch, whose run-task
    container overrides address this container by name in order to pass each
    batch step its arguments. Never null.
  EOT
  value       = local.container_name
}

# WHY : Assumptions: the application security group admits load-balancer traffic
#       on this port and no other, so a listener or a health probe configured
#       against a different port is refused by the security group rather than
#       by the task, and the symptom is a target that never turns healthy
#       rather than an error naming the port. Publishing the number keeps the
#       single admitted port derived from one input instead of repeated as a
#       literal in two modules.
output "container_port" {
  description = <<-EOT
    Port number -- a number, not a string -- that the container listens on and
    that the target group this module creates forwards to. Published so the
    environment's `ecs_workloads` output records the port each workload was
    registered on; infra/modules/alb does NOT consume it and declares no
    container-port input, because the target group lives here and the listener
    rules there forward to the group rather than to a port. Both roots supply
    the value in the other direction, from infra/modules/network's single
    app_container_port. Never null: variables.tf supplies a default and rejects
    any value outside 1024-65535, since a privileged port cannot be bound by the
    unprivileged container user this module runs as.
  EOT
  value       = var.container_port
}


# -----------------------------------------------------------------------------
# The target group. This is the boundary with infra/modules/alb.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the target group lives in this module rather than in alb
#       because registering a target needs this module's container name, container
#       port and health-check path -- three values alb has no other reason to know.
#       That division is why no aws_lb, aws_lb_listener or aws_lb_listener_rule
#       appears anywhere in this module, and why this ARN is published in their
#       place.
#       Trade-offs: publishing a NULLABLE output was chosen over both alternatives.
#       Omitting the output for the batch shape is not expressible at all -- an
#       output block is unconditional, so the real choice is a null value or no
#       output for any instantiation. Splitting out a separate ecs-task module
#       carrying the task definition, the two roles and the log group was the other
#       option, rejected because it would duplicate exactly those four things
#       across two modules -- the duplication this reusable module exists to
#       prevent -- after which the copies would drift on everything except the
#       parts meant to differ. The accepted cost is that a root wiring the batch
#       instantiation must not pass this value where a value is required.
output "target_group_arn" {
  description = <<-EOT
    ARN string of the load-balancer target group this service's tasks register
    into, or null. This is the module's PRIMARY CROSS-MODULE CONTRACT:
    infra/modules/alb owns the load balancer, its listener and the per-service
    listener rules, and attaches this ARN as one rule's forward target. That
    boundary is deliberate and this output is the single point at which the two
    modules meet -- this module owns the target group, alb owns everything in
    front of it -- so renaming this output breaks infra/modules/alb, which
    resolves it by name at its own call site. Null when either
    var.create_service or var.attach_load_balancer is false, which is the batch
    instantiation: Step Functions starts its tasks directly, nothing reaches
    them over HTTP, and there is no listener rule to attach.
  EOT
  value       = one(aws_lb_target_group.this[*].arn)
}

# WHY : Assumptions: a target-group NAME and the TargetGroup dimension value are
#       different strings that look similar enough to be confused. The
#       description says which is which because getting it wrong fails
#       silently: an alarm built on the name matches no metric, so it never
#       fires, which is indistinguishable from a correct alarm on a healthy
#       service.
output "target_group_name" {
  description = <<-EOT
    Name string of the same target group, or null under exactly the same
    condition as target_group_arn. At most 32 characters: a readable service
    stem followed by the stable replacement hash that lets create-before-destroy
    provision a new group before the old name is released.
    Consumed where a human-readable identifier is wanted rather than an ARN: an
    operator locating the group in the console, or a runbook step naming it.
    NOT the value a CloudWatch TargetGroup dimension takes -- that is
    target_group_arn_suffix below.
  EOT
  value       = one(aws_lb_target_group.this[*].name)
}

# WHY : Assumptions: about the CloudWatch metric schema rather than about
#       Terraform. The AWS/ApplicationELB namespace identifies a target
#       group by its ARN SUFFIX, and the provider exposes exactly that form
#       as its own attribute, so publishing it is the difference between a
#       consumer reading a documented value and a consumer splitting an ARN
#       on "/" and trusting the segment count never to change. Without this
#       output an observability caller would have no correct option but that
#       string surgery.
output "target_group_arn_suffix" {
  description = <<-EOT
    Trailing portion of the target-group ARN, in the form
    targetgroup/<name>/<id>, or null under the same condition as
    target_group_arn. Consumed by infra/modules/observability: the TargetGroup
    dimension on the AWS/ApplicationELB metrics -- healthy host count,
    per-target response time, the HTTP 5xx counts -- takes this suffix and not
    the full ARN, so a dashboard or alarm built from the ARN matches nothing
    and reports nothing. Published separately for that reason alone.
  EOT
  value       = one(aws_lb_target_group.this[*].arn_suffix)
}


# -----------------------------------------------------------------------------
# The service.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the AWS/ECS namespace identifies a service by its plain
#       name paired with a ClusterName dimension, whereas AWS/ApplicationELB
#       identifies a target group by an ARN suffix. Both are stated explicitly
#       because the inconsistency is AWS's rather than this module's, and
#       guessing either one wrong yields an alarm that never fires and so looks
#       exactly like a healthy service.
output "service_name" {
  description = <<-EOT
    Name string of the long-running ECS service, or null when
    var.create_service is false -- the batch and data-migration instantiations,
    whose tasks Step Functions starts one at a time rather than a service
    holding a desired count. Reaches the environment's `ecs_workloads` output
    and is read from there by an operator running a describe-services or
    update-service command against one service. No sibling MODULE consumes it:
    infra/modules/observability alarms this fleet through the ALB target groups
    and the cluster-level AWS/ECS metrics and is passed no service name.
  EOT
  value       = one(aws_ecs_service.this[*].name)
}

# WHY : Assumptions: a service name is unique only within one cluster, while the
#       ARN carries the account, the region and the cluster with it. The two
#       are therefore not interchangeable in an IAM Resource element or in any
#       record meant to outlive the cluster it named. Both forms are published
#       because the CloudWatch dimension needs the name and an IAM statement
#       needs the ARN, and deriving either from the other is not work a caller
#       should be left to do.
output "service_arn" {
  description = <<-EOT
    ARN string of the same ECS service, or null under exactly the same
    condition as service_name. Consumed where the service must be identified
    unambiguously rather than merely described: an IAM statement scoping an
    action to this one service, a deployment audit record, or the runbook step
    that brackets the batch window with the SSM read-only flag and has to
    record which services it quiesced and resumed.
  EOT
  value       = one(aws_ecs_service.this[*].arn)
}


# -----------------------------------------------------------------------------
# Autoscaling.
# -----------------------------------------------------------------------------

# WHY : Assumptions: that composite form is an Application Auto Scaling API
#       contract and not a naming convention -- a service is addressed as
#       service/<cluster name>/<service name> and nothing else is accepted. It
#       is also the reason variables.tf declares cluster_name separately from
#       cluster_arn, a pair that otherwise looks redundant: the alternative was
#       cutting the name back out of the ARN with element(split("/", ...)),
#       brittle string surgery over a value the calling root already holds.
#       Publishing the composed identifier keeps that reasoning in one place
#       instead of repeating it in every root that adds a second policy.
output "autoscaling_target_resource_id" {
  description = <<-EOT
    Composite identifier string of the registered Application Auto Scaling
    scalable target, in the literal form
    service/<cluster-name>/<service-name>, or null when either
    var.create_service or var.enable_autoscaling is false -- the batch
    instantiation sets both. Consumed by an environment root attaching a
    further scaling policy to the same target, a memory-based one alongside the
    CPU target-tracking policy this module already creates, for instance: an
    aws_appautoscaling_policy must repeat this exact resource_id, and
    re-deriving it at the call site would mean recomposing a string this module
    has already resolved.
  EOT
  value       = one(aws_appautoscaling_target.this[*].resource_id)
}


# -----------------------------------------------------------------------------
# Two decisions about this surface as a whole.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: marking the ARNs sensitive was rejected on two
#       independent grounds. Every value here is an ARN, a name, a port or a
#       composite identifier -- none is a credential and none can be exchanged for
#       one; and infra-ci.yml treats `terraform plan` as a reviewable artifact,
#       which `sensitive = true` would redact, hiding the target-group ARN and both
#       role ARNs from the review that exists to check how a service is wired. What
#       makes that safe rather than merely convenient is that var.secret_sources
#       and var.ssm_parameter_arns carry only ARNs into this module, never the
#       values behind them, so no secret value is ever in scope to be published;
#       the "no secrets committed" constraint is met structurally in
#       infra/modules/secrets, which writes every generated credential straight
#       into Secrets Manager.

# WHY : Assumptions: nothing owned by another module is re-exported, because
#       publishing a value this module either received as an input or does not own
#       would create a second apparent source of truth for one fact, after which a
#       divergence between the copies becomes possible and nothing reports it. So
#       there is no load-balancer DNS name, listener ARN or hostname (alb owns the
#       listener and its rules, api-gateway-http owns the edge, and this module
#       owns the target group and nothing beyond it); no cluster ARN or name (both
#       arrive as inputs, so every caller already holds them); no image URI (it
#       arrives as an input and the task definition consumes it unchanged); and no
#       secret, parameter or KMS key values, not even their ARNs, since only ARNs
#       were ever passed in and returning them would widen this surface for no
#       consumer.
# =============================================================================
