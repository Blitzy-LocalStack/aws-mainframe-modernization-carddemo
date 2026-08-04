# =============================================================================
# infra/modules/ecs-service/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Carries exactly one CardDemo service onto ECS Fargate. One instantiation
#   provisions a CloudWatch log group, a task execution role, an application
#   task role, a task definition, a load-balancer target group, the service
#   itself, and a target-tracking autoscaling policy. Each of the two
#   environment roots, infra/envs/dev and infra/envs/prod, instantiates the
#   module eight times -- once per bounded context: auth, account, card,
#   transaction, reference, batch, authorization and reporting -- so the
#   resources declared once below materialise as sixteen independent sets.
#   This is the CICS region's role, decomposed. app/csd/CARDDEMO.CSD ran all
#   eighteen transactions inside a single address space; here each bounded
#   context becomes an independently deployable, independently scaled service,
#   and the region's other responsibilities move to sibling modules --
#   ecs-cluster holds the capacity, alb holds the listener, cognito holds
#   sign-on, observability holds the dashboards and alarms.
#
# Parameters:
#   None are declared here. Every input this file reads is declared in the
#   sibling variables.tf, which is the one place a caller's arguments are
#   accepted, typed and validated. Forty-eight variables are declared there
#   and every one of them is consumed by this file, because the module has no
#   other consumer of them. That reconciliation is a standing constraint
#   rather than tidiness: this directory is never applied directly, so
#   .github/workflows/infra-ci.yml validates it only transitively, through
#   `init -backend=false` and `validate` on infra/bootstrap, infra/envs/dev
#   and infra/envs/prod. A reference here to an input variables.tf does not
#   declare would therefore surface at the calling root rather than here, and
#   tflint's terraform_unused_declarations rule fires in the other direction
#   on any variable, local or data source that nothing consumes.
#
# Return values:
#   None are declared here either; outputs.tf owns those. One of them is a
#   genuine cross-module contract and is the reason the target group lives in
#   this module at all: the target-group ARN. infra/modules/alb owns the load
#   balancer, its listener and the per-service listener rules, and it attaches
#   that ARN as a rule's forward target. The boundary is restated at the
#   target group below, because it is the single point where two modules meet.
#
# Exceptions or errors:
#   - A task_cpu and task_memory pair outside Fargate's permitted matrix is
#     rejected by RegisterTaskDefinition at apply. variables.tf validates the
#     whole matrix cross-variable, so the normal failure is the earlier one at
#     plan; the apply-time rejection is only the backstop.
#   - A task role missing a permission the application needs does not fail the
#     apply at all. The task starts, the application cannot reach its queue,
#     secret or key, and the symptom is a task that reaches RUNNING and then
#     fails its target-group health check until the deployment circuit breaker
#     returns the service to its previous task set. That indirection is why
#     the task role's contents are the caller's to declare.
#   - A target group still referenced by a listener rule in alb cannot be
#     destroyed, so a change to an immutable target-group attribute fails the
#     apply unless the replacement is created first.
#   - A service created before its execution role's inline policy exists fails
#     ECS's own permission validation with an error naming the role, because
#     the service references the role and not the policy.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the blocks below are ordered by dependency --
#     locals and data, then the log group, the two roles, the task definition,
#     the target group, the service, and finally autoscaling -- rather than
#     alphabetically. Read top to bottom, no reference appears before the
#     thing it refers to, which mirrors how the AWS resources actually
#     compose: autoscaling addresses the service, the service addresses the
#     task definition and the target group, and the execution policy
#     addresses the log group. Alphabetical order was the alternative and
#     would have opened with the autoscaling policy and split the two roles
#     around the target group.
#   - Assumptions: three of the resources are conditional, because one of the
#     eight instantiations is not a long-running service at all. The gating is
#     explained once, at the target group, and reused at the service and the
#     autoscaling pair.
# =============================================================================

# -----------------------------------------------------------------------------
# Composed names, tags and container-definition fragments.
# -----------------------------------------------------------------------------

# WHAT: every name, tag set and container-definition fragment the resources
#       below share, resolved once so that no two of them can derive the same
#       value differently.
locals {
  # WHY : Assumptions: variables.tf bounds name_prefix at twelve characters and
  #       service_name at fourteen, and environment is dev or prod, so this
  #       composed name is at most 12 + 1 + 14 + 1 + 4 = 32 characters. That is
  #       exactly the ceiling an Elastic Load Balancing target-group name may
  #       not exceed, and it is why those two bounds exist at all. The
  #       arithmetic is recorded here because a reader relaxing either bound
  #       needs to know what it was buying.
  resource_name = "${var.name_prefix}-${var.service_name}-${var.environment}"

  # WHY : Trade-offs: substr is a structural guard rather than active
  #       truncation -- it is a no-op for every input variables.tf admits, and
  #       it takes effect only if a later edit relaxes one of those two length
  #       bounds. trimsuffix then removes a hyphen the truncation could leave
  #       trailing, which a target-group name may not carry. The accepted cost
  #       is that two relaxed names could truncate to the same string and
  #       collide instead of failing loudly; the bounds in variables.tf are
  #       what actually prevent that, and this only keeps the failure from
  #       being an opaque rejection from the load-balancing API.
  target_group_name = trimsuffix(substr(local.resource_name, 0, 32), "-")

  # WHY : Trade-offs: coalesce resolves the null default here rather than in
  #       variables.tf, because a variable default cannot reference another
  #       variable. The seven services with no cross-module contract get
  #       service_name; the batch instantiation sets container_name explicitly,
  #       because infra/modules/step-functions-batch addresses that container
  #       BY NAME in its run-task container overrides, making the name a
  #       contract both modules have to agree on.
  container_name = coalesce(var.container_name, var.service_name)

  # WHY : Refactoring Rationale: the log-group name is composed here rather
  #       than left for the provider to generate, because the execution role's
  #       policy has to scope logs:PutLogEvents to this one group's ARN. The
  #       /aws/ecs/ prefix is what files all eight services under one path, so
  #       an operator reading logs during the batch window is not hunting
  #       across unrelated prefixes.
  log_group_name = "/aws/ecs/${local.resource_name}"

  # WHY : Assumptions: the log-group ARN is normalised before use because the
  #       two Resource forms the policy needs differ only by a :* suffix, and
  #       the ARN the provider exposes has not consistently carried one.
  #       Trimming first and appending explicitly makes both statements correct
  #       under either form, rather than producing a doubled suffix that
  #       matches nothing.
  log_group_arn = trimsuffix(aws_cloudwatch_log_group.this.arn, ":*")

  # WHY : Assumptions: the calling root configures default_tags on its provider
  #       and the provider merges those into every taggable resource, so this
  #       merge exists only for the module's own two keys. var.tags is merged
  #       last, which is what lets a caller override either of them.
  tags = merge({
    Environment = var.environment
    Service     = var.service_name
  }, var.tags)

  # WHY : Assumptions: ECS stores the environment array in the order supplied,
  #       and the provider compares the rendered container definitions as a
  #       string, so the order has to be stable across plans or every plan
  #       proposes a new task-definition revision for no change. Terraform
  #       already iterates a map in lexical key order; sorting the keys makes
  #       that dependence visible, so a later edit that iterates something
  #       other than a map cannot lose the property silently.
  container_environment = [
    for key in sort(keys(var.environment_variables)) : {
      name  = key
      value = var.environment_variables[key]
    }
  ]

  # WHY : Assumptions: ECS resolves a Parameter Store ARN and a Secrets Manager
  #       ARN through the same container secrets entry -- one name and one
  #       valueFrom -- which is why two typed inputs collapse into one list
  #       here. They stay two inputs at the module boundary because the
  #       execution role needs ssm:GetParameters for one store and
  #       secretsmanager:GetSecretValue for the other, and those same ARNs are
  #       what scope both statements.
  #       Trade-offs: merge gives secret_arns precedence, so a name declared in
  #       both maps resolves from Secrets Manager alone. Rejecting the overlap
  #       instead would need a precondition, and the precedence is
  #       deterministic and documented, so the ambiguity is resolved rather
  #       than merely permitted.
  secret_sources = merge(var.ssm_parameter_arns, var.secret_arns)

  container_secrets = [
    for key in sort(keys(local.secret_sources)) : {
      name      = key
      valueFrom = local.secret_sources[key]
    }
  ]

  # WHY : Assumptions: a volume name cannot contain a separator, so each path
  #       becomes a name by dropping the leading slash and replacing the rest
  #       with hyphens -- /var/cache/app becomes var-cache-app. Keying a map by
  #       that derived name is deliberate: two different paths that derived the
  #       same name would fail at plan with a duplicate-key error naming the
  #       collision, instead of at apply with a rejection from ECS.
  writable_volumes = {
    for path in var.writable_mount_paths :
    replace(trimprefix(path, "/"), "/", "-") => path
  }

  # WHY : Assumptions: readonly_root_filesystem defaults to true, so a JVM
  #       writing a heap dump on out-of-memory, a temporary file or its own
  #       scratch data needs at least one writable path. Each entry becomes one
  #       Fargate ephemeral volume and the matching mount point, so the
  #       read-only root holds everywhere except the paths a caller names.
  container_mount_points = [
    for name, path in local.writable_volumes : {
      containerPath = path
      readOnly      = false
      sourceVolume  = name
    }
  ]

  # WHY : Assumptions: ECS rejects health_check_grace_period_seconds outright
  #       on a service with no load balancer, so the value has to become null
  #       rather than merely be ignored. The grace period only means anything
  #       while a target group is deciding whether a newly started task is
  #       healthy.
  health_check_grace_period = (
    var.attach_load_balancer ? var.health_check_grace_period_seconds : null
  )
}

# -----------------------------------------------------------------------------
# Discovered account and region context.
# -----------------------------------------------------------------------------

# WHAT: the region the calling root's provider is configured for.
# WHY : Assumptions: the awslogs driver needs the region as a literal string
#       inside the container definition, and this module must not carry one.
#       Reading it from the provider is what lets the same module text run in
#       whatever region the root chooses. The region attribute is read rather
#       than name, which hashicorp/aws marks deprecated.
data "aws_region" "current" {}

# WHAT: the account the calling root's credentials belong to.
# WHY : Assumptions: the account identifier is needed for the source-account
#       condition on the trust policy below, and discovering it is the only way
#       to have that condition without writing a twelve-digit account number
#       into the repository.
data "aws_caller_identity" "current" {}

# -----------------------------------------------------------------------------
# IAM policy documents.
# -----------------------------------------------------------------------------

# WHAT: the trust policy both roles below share -- who is allowed to assume
#       them, as distinct from what they may then do.
# WHY : Assumptions: ecs-tasks.amazonaws.com is the principal for both roles.
#       ECS assumes the execution role to start the task, before the container
#       exists, and assumes the task role on the application's behalf once it
#       is running. One trust policy for two roles is correct precisely because
#       the difference between them is what each is permitted to do, not who
#       may assume it.
data "aws_iam_policy_document" "task_assume_role" {
  statement {
    sid     = "AllowEcsTasksAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      identifiers = ["ecs-tasks.amazonaws.com"]
      type        = "Service"
    }

    # WHY : Assumptions: without a source-account condition the trust policy
    #       names a service principal that any account's ECS could in
    #       principle present, which is the confused-deputy shape. Binding it
    #       to the discovered account means only this account's ECS can assume
    #       either role. The value is read from a data source rather than
    #       written as a literal so that no account number reaches the
    #       repository, which is the same reasoning that keeps every endpoint
    #       and credential out of it.
    condition {
      test     = "StringEquals"
      values   = [data.aws_caller_identity.current.account_id]
      variable = "aws:SourceAccount"
    }
  }
}

# WHAT: the execution role's permissions -- everything ECS itself needs to do
#       on this task's behalf in order to start it and keep its logs flowing.
# WHY : Alternatives Considered: attaching the AWS-managed
#       AmazonECSTaskExecutionRolePolicy was rejected. That policy grants ECR
#       pull and log write against every resource in the account, so each of
#       the eight services would be able to pull every other service's image
#       and write into every other service's log group. Every statement below
#       instead names its actions and scopes them to the ARNs this
#       instantiation was handed, which is what makes the per-service boundary
#       real rather than nominal. This matters because RACF has no cloud
#       analogue and is not being ported: least-privilege task roles are what
#       replace it, so a convenience policy here would quietly remove the
#       control it stands in for.
data "aws_iam_policy_document" "execution" {
  # WHY : Assumptions: this is the only statement whose Resource is a wildcard,
  #       and the reason is the AWS API rather than convenience.
  #       GetAuthorizationToken returns a registry-wide token and accepts no
  #       resource qualifier, so there is nothing narrower that could be
  #       written. Note what stays narrow even here: the ACTION is named, never
  #       ecr:*. A wildcard action is what the pipeline's policy scan rejects,
  #       and neither role in this module contains one.
  statement {
    sid       = "AllowEcrAuthorization"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # WHY : Assumptions: these three actions are the image pull itself, and they
  #       are scoped to the single repository this service's image lives in, so
  #       a compromised execution role cannot pull another bounded context's
  #       image. The repository ARN arrives as an input from infra/modules/ecr
  #       through the root rather than being reconstructed from the image URI,
  #       because parsing an ARN out of a registry reference would encode the
  #       registry hostname format in this module.
  statement {
    sid    = "AllowEcrImagePull"
    effect = "Allow"

    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:GetDownloadUrlForLayer",
    ]

    resources = [var.ecr_repository_arn]
  }

  # WHY : Assumptions: logs:CreateLogGroup is deliberately absent, and its
  #       absence is a decision rather than an omission. Terraform creates the
  #       group below with a retention period and a key, so a task able to
  #       create groups could only ever create an unmanaged one with neither --
  #       a permission with no legitimate use here, and one that would let a
  #       misconfigured service silently log outside the retention policy. Both
  #       Resource forms are required: the group ARN authorises writes to the
  #       group, and the :* suffix covers the log streams inside it.
  statement {
    sid    = "AllowLogWrite"
    effect = "Allow"

    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]

    resources = [
      local.log_group_arn,
      "${local.log_group_arn}:*",
    ]
  }

  # WHY : Assumptions: dynamic with a one-or-zero element for_each is what
  #       makes an absent input produce no statement at all. The alternatives
  #       are both worse: a statement with an empty Resource list is not a
  #       valid policy, and a statement with a wildcard Resource would defeat
  #       the scoping this whole document exists for. The same construction is
  #       reused for the two remaining stores below.
  dynamic "statement" {
    for_each = length(var.ssm_parameter_arns) > 0 ? [true] : []

    content {
      sid       = "AllowSsmParameterRead"
      effect    = "Allow"
      actions   = ["ssm:GetParameters"]
      resources = values(var.ssm_parameter_arns)
    }
  }

  dynamic "statement" {
    for_each = length(var.secret_arns) > 0 ? [true] : []

    content {
      sid       = "AllowSecretRead"
      effect    = "Allow"
      actions   = ["secretsmanager:GetSecretValue"]
      resources = values(var.secret_arns)
    }
  }

  # WHY : Assumptions: kms:Decrypt is needed only where a parameter or a secret
  #       is encrypted with a customer-managed key from infra/modules/kms.
  #       Anything encrypted with an AWS-managed key is decrypted through the
  #       owning service's own grant, so an empty kms_key_arns is the ordinary
  #       case rather than a gap, and granting decrypt unconditionally would
  #       hand every service the ability to use keys it was never given.
  dynamic "statement" {
    for_each = length(var.kms_key_arns) > 0 ? [true] : []

    content {
      sid       = "AllowKmsDecrypt"
      effect    = "Allow"
      actions   = ["kms:Decrypt"]
      resources = var.kms_key_arns
    }
  }
}


# -----------------------------------------------------------------------------
# Log destination.
# -----------------------------------------------------------------------------

# WHAT: the single log destination for every task this service runs, in both
#       the long-running and the batch shape.
# WHY : Refactoring Rationale: the baseline wrote job and console output to the
#       JES spool through SYSOUT and SYSPRINT DD statements, which meant the
#       output was readable only from the system the job ran on. One CloudWatch
#       log group per service is the equivalent destination, and
#       infra/modules/observability builds its dashboards and alarms on top of
#       these groups rather than creating a parallel set of its own -- which is
#       why the group is created here, where the service that writes to it is
#       defined, rather than centrally.
resource "aws_cloudwatch_log_group" "this" {
  name = local.log_group_name

  # WHY : Trade-offs: retention is one of only three things the two environment
  #       roots are permitted to differ on -- with task count and task size --
  #       and topology is never one of them. A short dev retention costs less
  #       to store; a long prod retention is what makes an incident
  #       investigable after the fact, and the two pull in opposite directions,
  #       which is exactly why it is an input rather than a constant.
  retention_in_days = var.log_retention_in_days

  # WHY : Assumptions: null selects the CloudWatch Logs AWS-managed key, which
  #       is still encryption at rest; a customer-managed key ARN from
  #       infra/modules/kms is what additionally makes the rotation schedule
  #       and the key's own audit trail this account's. Either value is an
  #       improvement rather than a port, because the baseline defined every
  #       VSAM file with RECOVERY(NONE) and no encryption at all.
  kms_key_id = var.log_group_kms_key_arn

  tags = local.tags
}

# -----------------------------------------------------------------------------
# Task execution role and application task role.
# -----------------------------------------------------------------------------

# WHAT: the role ECS assumes in order to start a task -- to pull the image,
#       resolve the configured parameters and secrets, and open the log stream.
# WHY : Assumptions: this is deliberately a different role from the task role
#       below, and the split is not ceremonial. ECS uses this one before the
#       container exists, so what it needs is fully determined by this module's
#       own resources and is therefore this module's to compose. What the
#       application needs once it is running is not.
resource "aws_iam_role" "execution" {
  name               = "${local.resource_name}-execution"
  description        = "ECS task execution role for ${local.resource_name}."
  assume_role_policy = data.aws_iam_policy_document.task_assume_role.json
  tags               = local.tags
}

# WHAT: the sole grant the execution role ever receives. Nothing else in this
#       module or reachable from it adds a permission to that role, so the
#       document generated above is the complete, auditable list of what ECS
#       may do on this task's behalf.
# WHY : Alternatives Considered: a standalone aws_iam_policy plus an attachment
#       was rejected. The document is generated from this instantiation's own
#       ARNs and is meaningful for no other role, so a managed policy would add
#       an independently addressable object that outlives the role and could be
#       attached elsewhere. An inline policy is deleted with the role, which
#       matches the lifetime the permissions actually have.
resource "aws_iam_role_policy" "execution" {
  name   = "${local.resource_name}-execution"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution.json
}

# WHAT: the role the application itself assumes at run time. It starts with no
#       permissions at all and gains only what the calling root attaches.
# WHY : Alternatives Considered: composing a union of every service's needs
#       inside this module was rejected outright. Because one module body has
#       to satisfy all eight instantiations, that union would grant the
#       reporting service the authorization service's queues and the auth
#       service's secrets -- every service every other service's access, which
#       is the exact opposite of the per-service least privilege that stands in
#       for RACF here. The consequence of composing nothing is stronger than a
#       convention: no wildcard action can reach the task role from this
#       module, because this module writes no task-role statement whatsoever.
#       The caller passes what its own service needs -- its own queues, its own
#       secrets, its own key usage -- and nothing else is reachable.
resource "aws_iam_role" "task" {
  name               = "${local.resource_name}-task"
  description        = "Application task role for ${local.resource_name}."
  assume_role_policy = data.aws_iam_policy_document.task_assume_role.json
  tags               = local.tags
}

# WHY : Assumptions: count rather than an unconditional resource, because a
#       null policy document is not the same as an empty one -- an inline
#       policy resource with a null body is invalid, not permissive. A service
#       whose permissions are entirely managed-policy shaped therefore carries
#       no inline policy resource at all rather than an empty one.
resource "aws_iam_role_policy" "task" {
  count = var.task_role_policy_json != null ? 1 : 0

  name   = "${local.resource_name}-task"
  role   = aws_iam_role.task.id
  policy = var.task_role_policy_json
}

# WHY : Assumptions: for_each over a set here rather than count over the list,
#       because an attachment keyed by its own policy ARN is stable under
#       reordering. With count the addresses would be positional, so inserting
#       one ARN at the head of the list would destroy and recreate every
#       attachment after it for no change in effect. Contrast the conditional
#       resources in this file, which use count precisely because a
#       zero-or-one gate is what count expresses directly.
resource "aws_iam_role_policy_attachment" "task" {
  for_each = toset(var.task_role_managed_policy_arns)

  role       = aws_iam_role.task.name
  policy_arn = each.value
}


# -----------------------------------------------------------------------------
# Task definition.
# -----------------------------------------------------------------------------

# WHAT: the immutable description of one running container -- its image, size,
#       identity, configuration, writable storage and log destination. A new
#       revision is registered on every change, and the service below rolls
#       onto it.
# WHY : Assumptions: created unconditionally, including for the one
#       instantiation that has no service. ADR-002 puts batch on
#       Step-Functions-invoked Fargate tasks rather than on a long-running
#       service, and the state machine's synchronous run-task integration needs
#       exactly this and nothing more: a registered task definition it can
#       start with per-step container overrides.
resource "aws_ecs_task_definition" "this" {
  family = local.resource_name

  # WHY : Assumptions: Fargate requires cpu and memory at the task level and
  #       rejects any pair outside its published matrix, so these are not free
  #       numbers. variables.tf validates the whole matrix cross-variable,
  #       which turns what would be an apply-time RegisterTaskDefinition
  #       rejection into a plan-time error naming both values.
  cpu    = var.task_cpu
  memory = var.task_memory

  # WHY : Alternatives Considered: EC2 and Lambda were both rejected, per
  #       ADR-002. EC2 would add instance patching and capacity management for
  #       a workload that is eight uniform request-response services. Lambda
  #       cannot host them: the batch steps that share this module's task
  #       definitions exceed its fifteen-minute execution ceiling, and a warm
  #       JDBC connection pool has no natural home in an invocation-scoped
  #       runtime, so every request would pay connection setup.
  requires_compatibilities = ["FARGATE"]

  # WHY : Assumptions: awsvpc is required by Fargate rather than selected among
  #       options. It is what gives each task its own elastic network interface
  #       and its own address inside the private application subnets, which is
  #       in turn why the target group below registers targets by IP.
  network_mode = "awsvpc"

  execution_role_arn = aws_iam_role.execution.arn
  task_role_arn      = aws_iam_role.task.arn

  # WHY : Assumptions: Linux is the first of this migration's non-negotiable
  #       constraints, and X86_64 matches the architecture of the base images
  #       the service Dockerfiles are built on. Naming both explicitly rather
  #       than letting Fargate infer them means an image built for another
  #       architecture fails at registration, where the error names the
  #       mismatch, instead of at task start where it does not.
  runtime_platform {
    cpu_architecture        = "X86_64"
    operating_system_family = "LINUX"
  }

  # WHY : Assumptions: one Fargate ephemeral volume per writable path, carrying
  #       a name and nothing else. Fargate supports only bind-mount host
  #       volumes and no volume driver configuration, so there is nothing
  #       further to set; the backing storage is the task's own ephemeral
  #       storage and it is discarded with the task, which is the property that
  #       makes it safe for scratch data and unsafe for anything else.
  dynamic "volume" {
    for_each = local.writable_volumes

    content {
      name = volume.key
    }
  }

  container_definitions = jsonencode([
    {
      name  = local.container_name
      image = var.image_uri

      # WHY : Assumptions: a single-container task, so the container has to be
      #       essential. A lone non-essential container would let the task sit
      #       in RUNNING with nothing actually serving, and ECS would not
      #       replace it.
      essential = true

      # WHY : Assumptions: container_user carries a numeric uid, which must
      #       match the non-root user the service's own Dockerfile creates -- a
      #       uid absent from the image fails at task start. This is a
      #       preserved property rather than an invented hardening measure:
      #       app/csd/CARDDEMO.CSD sets TASKDATAKEY(USER) on all eighteen of
      #       its DEFINE TRANSACTION stanzas and EXECKEY(USER) on all eighteen
      #       DEFINE PROGRAM stanzas, so the baseline already refused to run
      #       application work in the privileged CICS storage key. Dropping to
      #       a non-root uid here is the same decision expressed in the new
      #       runtime, not a new one.
      user = var.container_user

      # WHY : Assumptions: the privileged key is deliberately absent rather
      #       than present and false. AWS documents the parameter as not
      #       supported for tasks run on Fargate, and Fargate's security model
      #       states plainly that privileged containers are unavailable there,
      #       so asserting even the false value risks a RegisterTaskDefinition
      #       rejection this module has no way to verify against a real Fargate
      #       control plane.
      #       Alternatives Considered: an explicit false reads as more
      #       auditable, and it was rejected for exactly that reason -- the
      #       omission is what Fargate accepts unambiguously, Fargate's
      #       effective default is unprivileged, and both spellings therefore
      #       describe the identical running container. Choosing the spelling
      #       that cannot be rejected costs nothing that this comment does not
      #       restore.

      # WHY : Trade-offs: a read-only root filesystem removes the write access
      #       an attacker needs in order to drop a binary into the image's own
      #       tree, and the accepted cost is that every path the JVM writes to
      #       has to be named up front. writable_mount_paths defaults to /tmp
      #       alone, which is where a heap dump on out-of-memory and the JVM's
      #       own scratch files land; a service needing more has to say so,
      #       which is the point.
      readonlyRootFilesystem = var.readonly_root_filesystem

      mountPoints = local.container_mount_points

      # WHY : Assumptions: awsvpc gives the task its own interface, so the
      #       container port is a port on that interface and no host port is
      #       mapped at all. tcp is named explicitly because ECS stores a
      #       default protocol in the revision it registers; omitting it here
      #       leaves the rendered JSON and the registered revision different,
      #       and the provider compares them as strings, so every subsequent
      #       plan would propose a new revision for no change.
      portMappings = [
        {
          containerPort = var.container_port
          protocol      = "tcp"
        }
      ]

      # WHY : Assumptions: environment carries non-secret configuration only,
      #       and variables.tf enforces that at the boundary with a namespace
      #       allowlist and an outright refusal of secret-bearing names.
      #       Anything with a value worth protecting travels instead as a
      #       reference in secrets, which ECS resolves at task start from
      #       Parameter Store or Secrets Manager. That is what keeps every
      #       endpoint, connection string, password and token out of the task
      #       definition, where it would otherwise be readable by anyone able
      #       to describe it -- and it is the same set of ARNs that scopes the
      #       execution policy above, so configuration by reference is what
      #       keeps that policy wildcard-free rather than being a separate
      #       discipline.
      environment = local.container_environment
      secrets     = local.container_secrets

      # WHY : Assumptions: awslogs is one of the three log drivers Fargate
      #       supports and the only one of them that needs no sidecar
      #       container. The group named is the one created above rather than
      #       an arbitrary string, which is precisely what lets the execution
      #       policy scope logs:PutLogEvents to a single ARN instead of to
      #       every log group in the account.
      logConfiguration = {
        logDriver = "awslogs"

        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.this.name
          "awslogs-region"        = data.aws_region.current.region
          "awslogs-stream-prefix" = var.service_name
        }
      }

      # WHY : Alternatives Considered: a container-level healthCheck was
      #       considered and deliberately left out. The command a container
      #       health check runs has to exist inside the image, and only the
      #       service's own Dockerfile knows which binaries its base image
      #       actually ships -- a headless Corretto runtime image carries no
      #       curl. Each Dockerfile therefore owns its HEALTHCHECK, where the
      #       base image is pinned and known, and the target group below owns
      #       the check that decides whether a task receives traffic. A third
      #       check here, with a command this module cannot verify against an
      #       image it never sees, would add a failure mode without adding a
      #       signal.
    }
  ])

  tags = local.tags
}


# -----------------------------------------------------------------------------
# Load-balancer target group.
# -----------------------------------------------------------------------------

# WHAT: the pool of task addresses the shared internal load balancer forwards
#       this service's traffic to, together with the health check that decides
#       which of those addresses is currently eligible.
# WHY : Assumptions: the module boundary is worth stating here because this is
#       the one point at which two modules meet. infra/modules/alb owns the
#       load balancer, its listener and the per-service listener rules; this
#       module owns the target group and publishes its ARN, which alb attaches
#       as a rule's forward target. Putting the target group in alb instead
#       would have forced alb to re-derive this module's container name,
#       container port and health-check path -- three values it has no other
#       reason to know. That is also why no aws_lb, aws_lb_listener or
#       aws_lb_listener_rule appears anywhere in this file.
#       Trade-offs: count gates this resource on two inputs rather than one.
#       attach_load_balancer is false for the batch instantiation, which has a
#       task definition and no service at all, and create_service is the
#       broader gate; requiring both means a target group is never created with
#       nothing that could register in it.
#       Alternatives Considered: a separate ecs-task module for the batch shape
#       was rejected. It would duplicate the task definition, both roles and
#       the log group -- precisely the duplication this module exists to
#       prevent -- and the two shapes would then drift apart on everything
#       except the parts that differ. The accepted cost is three boolean inputs
#       that seven of the eight callers never touch. count rather than for_each
#       because a zero-or-one conditional is what count expresses directly;
#       for_each would need a synthetic key carrying no meaning.
resource "aws_lb_target_group" "this" {
  count = var.create_service && var.attach_load_balancer ? 1 : 0

  name   = local.target_group_name
  port   = var.container_port
  vpc_id = var.vpc_id

  # WHY : Assumptions: one protocol input drives both the forwarded traffic and
  #       the health probe below, because a probe on a different protocol from
  #       the traffic proves the wrong thing -- it would report a task healthy
  #       on a hop that real requests never take.
  protocol = var.target_protocol

  # WHY : Assumptions: awsvpc tasks have their own interfaces and no host port,
  #       so ip is the only target type able to address them at all; instance
  #       targets are impossible under Fargate. This is an AWS contract rather
  #       than a preference, which is why it is a literal and not an input.
  target_type = "ip"

  # WHY : Trade-offs: the deregistration delay is a ceiling on how long the
  #       load balancer keeps draining a target it has removed, not an estimate
  #       of how long a request takes. A shorter value returns capacity to the
  #       pool sooner during a rolling replacement; a longer one is what stops
  #       an in-flight request from being cut off mid-response.
  deregistration_delay = var.deregistration_delay

  # WHY : Assumptions: statelessness is what makes this entire module viable,
  #       so stickiness is disabled explicitly rather than merely omitted,
  #       which is what makes the decision auditable in a plan rather than
  #       inferable from an absence. app/cpy/COCOM01Y.cpy:L19-L44 defines the
  #       CARDDEMO-COMMAREA that carried every scrap of continuity between
  #       screen turns: CDEMO-FROM-TRANID and CDEMO-FROM-PROGRAM,
  #       CDEMO-TO-TRANID and CDEMO-TO-PROGRAM, CDEMO-USER-ID and
  #       CDEMO-USER-TYPE with its 'A' and 'U' condition names, the customer,
  #       account and card selection fields, CDEMO-LAST-MAP and
  #       CDEMO-LAST-MAPSET, and the CDEMO-PGM-CONTEXT re-entry discriminator.
  #       All of it is gone, decomposed into four separate mechanisms:
  #       navigation became client-side router history, identity became signed
  #       token claims, selection context became request path parameters, and
  #       the re-entry discriminator was eliminated outright. No task therefore
  #       holds anything a later request needs, and two properties this module
  #       depends on follow directly -- autoscaling may remove a task without
  #       losing session state, and a rolling deployment may replace every task
  #       for the same reason. A sticky cookie would pin a browser to one task
  #       and quietly reintroduce exactly the coupling the migration removed,
  #       making both of those safe operations unsafe again.
  stickiness {
    enabled = false

    # WHY : Assumptions: the provider requires a stickiness type even when
    #       stickiness is disabled, so lb_cookie is named to satisfy the schema
    #       rather than to select a behaviour. Nothing reads it while enabled
    #       is false.
    type = "lb_cookie"
  }

  health_check {
    enabled = true

    # WHY : Assumptions: /actuator/health is Spring Boot Actuator's endpoint,
    #       and every service includes the actuator starter for this consumer
    #       specifically. The same endpoint answers the load balancer here and
    #       the HEALTHCHECK in each service's own Dockerfile, so a task is
    #       judged by one definition of healthy rather than two.
    path = var.health_check_path

    # WHY : Assumptions: the same protocol as the forwarded traffic, for the
    #       reason given at protocol above.
    protocol = var.target_protocol

    # WHY : Assumptions: traffic-port rather than a literal keeps the probe on
    #       whatever port the target group forwards to, so container_port stays
    #       a single input that cannot fall out of step with itself.
    port = "traffic-port"

    matcher             = var.health_check_matcher
    interval            = var.health_check_interval
    timeout             = var.health_check_timeout
    healthy_threshold   = var.healthy_threshold
    unhealthy_threshold = var.unhealthy_threshold
  }

  tags = local.tags

  # WHY : Assumptions: a target group referenced by a listener rule in alb
  #       cannot be destroyed while that reference exists, so replacing this
  #       resource in place -- which a change to name or target_type forces --
  #       fails the apply with a resource-in-use error naming the listener
  #       unless the replacement exists first and the rule can be repointed
  #       onto it.
  #       Trade-offs: because the name is deterministic rather than a generated
  #       prefix, a replacement that kept the same name would collide on it. A
  #       forced replacement therefore has to arrive with a changed name --
  #       which is the case that actually matters, since name is itself the
  #       attribute most likely to force one.
  lifecycle {
    create_before_destroy = true
  }
}


# -----------------------------------------------------------------------------
# The service.
# -----------------------------------------------------------------------------

# WHAT: the running service -- how many copies of the task definition ECS keeps
#       alive, where it places them, and how a new revision replaces the old.
# WHY : Assumptions: absent for the batch instantiation, gated on the same
#       create_service input explained at the target group above. ADR-002 runs
#       batch as Step-Functions-invoked tasks, so batch needs the task
#       definition and no long-running service holding a desired count.
resource "aws_ecs_service" "this" {
  count = var.create_service ? 1 : 0

  name            = local.resource_name
  cluster         = var.cluster_arn
  task_definition = aws_ecs_task_definition.this.arn
  desired_count   = var.desired_count

  # WHY : Alternatives Considered: a FARGATE_SPOT capacity-provider strategy
  #       was rejected. A spot interruption replaces tasks on two minutes'
  #       notice regardless of what they are serving, and the acceptance
  #       criterion for this migration is that the candidate business flows --
  #       sign-on, account view and update, card list and update, transaction
  #       add and list, bill pay -- work end to end. Interrupting one of those
  #       mid-request to reduce capacity cost is the wrong trade against the
  #       thing the migration is actually judged on.
  launch_type = "FARGATE"

  # WHY : Trade-offs: the platform version is pinned rather than left at
  #       LATEST, so a platform change arrives as a reviewed edit instead of on
  #       the next task start, where it would be indistinguishable from an
  #       application regression. The accepted cost is that a new platform's
  #       fixes need that edit before they reach the service.
  platform_version = var.platform_version

  # WHY : Alternatives Considered: this is the rolling deployment controller,
  #       and both alternatives are explicitly out of scope for this migration.
  #       Blue-green through a CODE_DEPLOY controller was rejected: it requires
  #       a second, green target group, a CodeDeploy application and deployment
  #       group, and an appspec traffic-shifting configuration, none of which
  #       this infrastructure package provisions -- which is why no
  #       aws_codedeploy_ resource and no second aws_lb_target_group appears
  #       anywhere in this file. Canary was rejected on the same ground: it is
  #       a CodeDeploy traffic-shifting configuration rather than an ECS-native
  #       capability, so selecting it would pull in the identical four
  #       resources. ECS rolling replacement needs none of them; it replaces
  #       tasks inside the one target group under the percentage bounds below.
  #       The operational contract that follows is worth writing down because
  #       it is what an operator actually does: roll forward is a rolling
  #       deployment with an updated image tag, and roll back is
  #       `terraform -chdir=infra/envs/<env> destroy`.
  deployment_controller {
    type = "ECS"
  }

  # WHY : Assumptions: these two percentages ARE the rolling strategy, and it
  #       is the pair that makes it zero-downtime -- a minimum of one hundred
  #       means no existing task is stopped before its replacement is healthy,
  #       and a maximum of two hundred is the headroom that lets the
  #       replacement exist alongside it. Lowering the minimum would trade
  #       serving capacity during a deployment for a smaller peak task
  #       footprint.
  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  deployment_maximum_percent         = var.deployment_maximum_percent

  # WHY : Assumptions: the circuit breaker is a property of the rolling
  #       controller above, not a second deployment strategy, and saying so
  #       matters because the word rollback reads like blue-green at a glance.
  #       It watches whether the new tasks reach a steady state and, if they do
  #       not, returns the service to the last known-good task set -- inside
  #       the single target group, with no traffic shifting and no green
  #       environment, so it introduces none of the resources rejected above.
  #       Trade-offs: it is gated rather than unconditional so that a caller
  #       debugging a task which will not start can keep the failed revision in
  #       place to inspect, instead of having it rolled away before the logs
  #       are read.
  dynamic "deployment_circuit_breaker" {
    for_each = var.enable_deployment_circuit_breaker ? [true] : []

    content {
      enable   = true
      rollback = true
    }
  }

  # WHY : Refactoring Rationale: assign_public_ip is a hard-coded false rather
  #       than an input, and that is the decision rather than an oversight. The
  #       tasks sit in the private application subnets, and
  #       infra/modules/network provisions interface endpoints for the ECR API
  #       and Docker registry, CloudWatch Logs, Secrets Manager, KMS, SQS, Step
  #       Functions and SSM plus an S3 gateway endpoint, so every AWS API call
  #       a task makes stays inside the VPC and needs no public address.
  #       Exposing this as a variable would let one caller quietly defeat that
  #       tiering for one service, and a network boundary a caller can opt out
  #       of is not a boundary -- so the choice is withheld rather than
  #       defaulted.
  network_configuration {
    assign_public_ip = false
    security_groups  = var.security_group_ids
    subnets          = var.private_app_subnet_ids
  }

  # WHY : Assumptions: keyed off the target group's own presence rather than
  #       off attach_load_balancer a second time, so the block and the resource
  #       it references cannot disagree. There is no arrangement of inputs that
  #       produces a load_balancer block pointing at a target group that was
  #       never created.
  dynamic "load_balancer" {
    for_each = toset(aws_lb_target_group.this[*].arn)

    content {
      container_name   = local.container_name
      container_port   = var.container_port
      target_group_arn = load_balancer.value
    }
  }

  # WHY : Assumptions: ECS rejects this argument outright on a service with no
  #       load balancer, so it has to resolve to null rather than to a harmless
  #       number -- see the local that computes it. The grace period only means
  #       anything while a target group is deciding whether a newly started
  #       task is healthy: it is the window in which a slow JVM start is not
  #       yet counted as a failure, which is why it exists at all for a Spring
  #       Boot service.
  health_check_grace_period_seconds = local.health_check_grace_period

  # WHY : Assumptions: managed tags with SERVICE propagation put the service's
  #       own tags onto each task ECS starts, so cost allocation and per-task
  #       traceability both come from the tag set already declared here. The
  #       alternative was a second tagging mechanism at the task level, which
  #       could drift from this one and then disagree about which service a
  #       cost belonged to.
  enable_ecs_managed_tags = true
  propagate_tags          = "SERVICE"

  tags = local.tags

  # WHY : Assumptions: ECS validates the execution role's permissions when the
  #       service is created, and the service references the ROLE while the
  #       permissions live in a separate inline-policy resource. Terraform
  #       infers ordering only from references, so without this explicit edge
  #       it may create the service before the policy exists, and the apply
  #       fails intermittently in a way that reads like a transient AWS error
  #       rather than a missing dependency.
  depends_on = [aws_iam_role_policy.execution]

  # WHY : Trade-offs: Application Auto Scaling writes desired_count at run
  #       time, so Terraform has to stop reconciling it -- otherwise every plan
  #       after a scaling event shows a diff and every apply fights the scaler
  #       back to the configured number. var.desired_count therefore sets the
  #       INITIAL count only. The residual cost is worth stating rather than
  #       glossing: ignore_changes cannot be made conditional in HCL, so when
  #       enable_autoscaling is false a manual desired-count change also stops
  #       being reverted. The unconditional form is chosen anyway, because the
  #       alternative -- two nearly identical service resources differing only
  #       in a lifecycle block -- would duplicate every other argument here and
  #       split the deployment configuration across both copies.
  lifecycle {
    ignore_changes = [desired_count]
  }
}

# -----------------------------------------------------------------------------
# Autoscaling.
# -----------------------------------------------------------------------------

# WHAT: registers the service as a scalable target, which is what allows its
#       desired count to be changed by anything other than Terraform.
# WHY : Assumptions: gated on create_service as well as enable_autoscaling,
#       because a scalable target addresses a service by name and there is no
#       service to address in the batch shape.
resource "aws_appautoscaling_target" "this" {
  count = var.create_service && var.enable_autoscaling ? 1 : 0

  service_namespace = "ecs"

  # WHY : Assumptions: this composite string is an Application Auto Scaling API
  #       contract rather than a naming convention -- a service is addressed as
  #       service/<cluster name>/<service name> and nothing else is accepted.
  #       It is also the reason variables.tf declares cluster_name separately
  #       from cluster_arn, which would otherwise look redundant: the
  #       alternative was splitting the name back out of the ARN with
  #       element(split("/", ...)), which is brittle string surgery over a
  #       value the calling root already holds and can simply pass.
  resource_id = "service/${var.cluster_name}/${aws_ecs_service.this[0].name}"

  # WHY : Assumptions: DesiredCount is the only scalable dimension ECS exposes
  #       for a service, so this is a fixed string rather than a choice among
  #       options.
  scalable_dimension = "ecs:service:DesiredCount"

  # WHY : Trade-offs: these two bounds are the real cost control, because
  #       target tracking will otherwise add capacity for as long as the metric
  #       stays above target. The minimum is what keeps the service available
  #       across an availability-zone loss rather than merely running.
  min_capacity = var.min_capacity
  max_capacity = var.max_capacity

  tags = local.tags
}

# WHAT: the single scaling policy -- how the scalable target above decides to
#       add or remove tasks.
# WHY : Assumptions: gated identically to the scalable target it attaches to,
#       so the two can never exist apart.
resource "aws_appautoscaling_policy" "cpu" {
  count = var.create_service && var.enable_autoscaling ? 1 : 0

  name               = "${local.resource_name}-cpu"
  resource_id        = aws_appautoscaling_target.this[0].resource_id
  scalable_dimension = aws_appautoscaling_target.this[0].scalable_dimension
  service_namespace  = aws_appautoscaling_target.this[0].service_namespace

  # WHY : Alternatives Considered: step scaling was rejected because it needs
  #       hand-tuned alarm thresholds and the baseline supplies no data from
  #       which to derive them. app/csd/CARDDEMO.CSD gives all eighteen
  #       transactions the same PRIORITY(1) and the same TRANCLASS(DFHTCL00)
  #       dispatch class -- both attributes occur exactly eighteen times -- so
  #       there is no per-transaction demand signal anywhere in the source to
  #       build steps out of. Target tracking needs exactly one number, and
  #       that number is one an operator can reason about from observed
  #       utilisation rather than invent. Worth noting the direction of travel:
  #       per-service autoscaling is a documented improvement over that single
  #       uniform dispatch class, not a port of it, because the baseline could
  #       not give one transaction more capacity than another.
  policy_type = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value = var.autoscaling_target_cpu_utilization

    # WHY : Assumptions: average CPU across the service is the predefined
    #       metric that needs no custom metric plumbing, and it is the right
    #       signal for these services specifically -- they spend their time on
    #       JSON serialisation and exact fixed-point arithmetic rather than
    #       blocked on a connection pool, so CPU rises with real demand instead
    #       of flattening under contention the way a request-count metric
    #       would.
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    # WHY : Trade-offs: the two cooldowns are deliberately asymmetric, and the
    #       asymmetry is the decision rather than an accident of defaults.
    #       Scaling out is cheap and immediately reversible, so its cooldown is
    #       the shorter of the two; scaling in removes capacity that an
    #       arriving request cannot get back quickly, so its cooldown is the
    #       longer one. Making them equal would either add capacity too slowly
    #       under a rising load or shed it too eagerly on a momentary dip.
    scale_in_cooldown  = var.autoscaling_scale_in_cooldown
    scale_out_cooldown = var.autoscaling_scale_out_cooldown
  }
}

