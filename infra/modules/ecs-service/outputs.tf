# =============================================================================
# infra/modules/ecs-service/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The entire public surface of the reusable ecs-service module. Sixteen
#   outputs and nothing else -- no resource, no data source, no local and no
#   variable is declared here, because a module's outputs are its return values
#   and this file is the only place they are stated. Each of the two
#   environment roots, infra/envs/dev and infra/envs/prod, instantiates the
#   module eight times, once per bounded context: auth, account, card,
#   transaction, reference, batch, authorization and reporting. Every name
#   below therefore resolves sixteen times across the two roots, and each name
#   is a ONE-WAY CONTRACT -- Terraform resolves module.<instance>.<output> at
#   the caller's own call site, so renaming an output here breaks the caller
#   rather than this file, and the resulting error names the caller's
#   expression instead of this line.
#
# Parameters:
#   None. An output block accepts no parameters. Every value below is read
#   from one of exactly three places, all of them inside this module: a
#   resource declared in the sibling main.tf, a local composed there, or an
#   input declared in the sibling variables.tf. Holding to those three is a
#   standing constraint rather than tidiness, because this directory is never
#   applied directly -- .github/workflows/infra-ci.yml reaches it only
#   transitively, through `init -backend=false` and `validate` on
#   infra/bootstrap, infra/envs/dev and infra/envs/prod. A reference here to an
#   input variables.tf does not declare, or to an attribute main.tf's resources
#   do not expose, therefore surfaces at the calling root rather than here.
#
# Return values:
#   Seventeen values -- sixteen strings and one number -- grouped below in the
#   order main.tf creates the resources behind them: the log group, the two IAM
#   roles, the task definition and its container, the target group, the
#   service, the autoscaling target. HCL declares no type on an output, so
#   every description below states its value's type in words. Five of the
#   seventeen are genuine cross-module contracts and say so where they are
#   declared:
#     - target_group_arn        attached to a listener rule by
#                               infra/modules/alb
#     - task_definition_arn     started by infra/modules/step-functions-batch
#     - container_name          addressed by name in that module's run-task
#                               container overrides
#     - log_group_name          read by infra/modules/observability
#     - target_group_arn_suffix the CloudWatch dimension observability needs
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
#     Refactoring Rationale: this paragraph said "one of the eight", which was
#     measured before the ETL workload was added; the six values were then null
#     for one instantiation out of eight rather than two out of nine. Each description
#     restates its own condition, because a caller reading only this file must
#     learn the nullability here rather than from a failed plan.
#   - A caller that indexes or interpolates one of those six nulls, or passes
#     one into an argument that requires a value, fails at plan -- and the
#     error names the caller's expression, not this file. Wiring the batch
#     instantiation therefore means not reading those six at all.
#   - No output is marked sensitive, so every value appears in plan output in
#     full. That is a decision rather than an oversight and its reasoning is
#     recorded in the closing block of this file.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the outputs mirror main.tf's own dependency
#     ordering rather than being alphabetical or ranked by importance, so the
#     two files can be read side by side in a single order and no output
#     appears before the resource that produces it. Alphabetical was the
#     alternative and would have opened the file with
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

# WHY : Refactoring Rationale: this replaces where the baseline's job and
#       console output went. The JCL wrote it to the JES spool through SYSOUT
#       and SYSPRINT DD statements, which made it readable only from the system
#       the job ran on; infra/modules/observability builds its dashboards,
#       alarms and metric filters on this group instead. Publishing the name is
#       what stops that module recomposing it from name_prefix, service_name
#       and environment and owning a second copy of the composition rule.
#       Assumptions: reading aws_cloudwatch_log_group.this.name rather than
#       local.log_group_name is deliberate. The local is a plain string that
#       resolves whether or not the group exists, while the resource attribute
#       carries a dependency edge, so a consumer creating a metric filter
#       against this name cannot be ordered before the group it filters.
output "log_group_name" {
  description = <<-EOT
    Name string of the CloudWatch Logs group every task in this service writes
    to, in the form /aws/ecs/<name_prefix>-<service_name>-<environment>.
    Consumed by infra/modules/observability, which attaches metric filters and
    builds this service's dashboard and alarm set from it, and by an operator
    tailing one service during the batch window. Never null: the log group is
    created for all eight instantiations, including the batch one, which has a
    task definition and no service.
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
#       Cognito groups, which makes this ARN the entire mechanism by which a
#       per-service privilege boundary is expressible at all. main.tf
#       deliberately attaches no statement of its own to this role, and that is
#       what makes the grant one-directional: a sibling module names this ARN
#       in its own resource policy, so a service's reach is enumerated where
#       the resource is defined rather than assembled inside a module shared by
#       all eight services -- where any grant would necessarily reach all
#       eight.
output "task_role_arn" {
  description = <<-EOT
    ARN string of the IAM role the APPLICATION assumes at run time, as
    distinct from execution_role_arn below, which ECS assumes in order to
    start the task. Consumed by every sibling module that must grant this one
    service access to a resource it owns: infra/modules/sqs in a queue policy,
    infra/modules/kms in a key policy, infra/modules/secrets in a secret
    resource policy, infra/modules/s3-datasets in a bucket policy. This ARN is
    therefore the identity least privilege is expressed against -- main.tf
    writes no policy onto this role, so what the service may reach is exactly
    what a caller grants to this ARN and nothing besides. Never null: both
    roles are created for all eight instantiations.
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
    iam:PassRole. Never null -- the task definition is created for all eight
    instantiations, including the batch one, which has a task definition and no
    service.
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
    that the target group forwards to. Consumed by infra/modules/alb when it
    aligns a listener rule or its own health-check port with this service.
    Never null: variables.tf supplies a default and rejects any value outside
    1024-65535, since a privileged port cannot be bound by the unprivileged
    container user this module runs as.
  EOT
  value       = var.container_port
}


# -----------------------------------------------------------------------------
# The target group. This is the boundary with infra/modules/alb.
# -----------------------------------------------------------------------------

# WHY : Assumptions: the target group lives in this module rather than in alb
#       because registering a target needs this module's container name,
#       container port and health-check path -- three values alb has no other
#       reason to know. That division is why no aws_lb, aws_lb_listener or
#       aws_lb_listener_rule appears anywhere in this module, and why this ARN
#       is published in their place.
#       Trade-offs: publishing a NULLABLE output was chosen over both
#       alternatives to it. Omitting the output for the batch shape is not
#       expressible at all -- an output block is unconditional, so the real
#       choice is a null value or no output for any of the eight
#       instantiations. Splitting the module in two was the other option: a
#       separate ecs-task module carrying only the task definition, the two
#       roles and the log group. That is rejected because it would duplicate
#       exactly those four things across two modules -- the duplication this
#       reusable module exists to prevent -- after which the two copies would
#       drift on everything except the parts that are meant to differ. The
#       accepted cost is that a root wiring the batch instantiation must not
#       pass this value anywhere a value is required.
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
    var.create_service is false -- the batch instantiation, whose tasks Step
    Functions starts one at a time rather than a service holding a desired
    count. Consumed by infra/modules/observability as the ServiceName dimension
    on the AWS/ECS metrics, and by an operator running a describe-services or
    update-service command against one service.
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

# WHY : Alternatives Considered: marking the ARNs sensitive was evaluated and
#       rejected on two independent grounds. First, every value here is an ARN,
#       a name, a port or a composite identifier -- none is a credential and
#       none can be exchanged for one. Second, .github/workflows/infra-ci.yml
#       treats `terraform plan` as a reviewable artifact, and sensitive = true
#       redacts a value from plan output, so marking these would hide the
#       target-group ARN and both role ARNs from the very review that exists to
#       check how a service is wired -- a real loss of review coverage bought
#       for no security gain. The "no secrets committed to the repository"
#       constraint is met structurally elsewhere and deliberately not here:
#       every generated credential is produced by infra/modules/secrets at
#       apply time and written straight into Secrets Manager. Note the
#       asymmetry that makes this safe rather than merely convenient --
#       var.secret_sources and var.ssm_parameter_arns carry only ARNs into this
#       module, never the values behind them, so no secret value is ever in
#       scope to be published.

# WHY : Assumptions: each is owned by another module, and re-exporting a value
#       this module either received as an input or does not own would publish a
#       second apparent source of truth for one fact -- after which a
#       divergence between the two copies becomes possible and nothing reports
#       it.
#       - No load-balancer DNS name, listener ARN or hostname.
#         infra/modules/alb owns the listener and its rules, and
#         infra/modules/api-gateway-http owns the edge in front of that. This
#         module owns the target group and nothing beyond it, which is the
#         boundary target_group_arn above exists to keep explicit.
#       - No cluster ARN and no cluster name. Both arrive as inputs from
#         infra/modules/ecs-cluster, so every caller already holds them;
#         echoing them back would invite the two copies to be read as
#         independent values.
#       - No image URI. It arrives as an input from infra/modules/ecr, and the
#         task definition consumes it unchanged rather than transforming it
#         into something a caller does not already have.
#       - No secret, parameter or KMS key values, and not even their ARNs. Only
#         ARNs were ever passed in, so returning them would add nothing a root
#         does not hold while widening this surface for no consumer.
# =============================================================================
