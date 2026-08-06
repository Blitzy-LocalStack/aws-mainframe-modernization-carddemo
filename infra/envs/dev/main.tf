# =============================================================================
# infra/envs/dev/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Compose the complete CardDemo development stack from the sixteen reusable
#   infrastructure modules. This root is the only layer allowed to connect
#   producer outputs to consumer inputs, create cross-module IAM documents,
#   package operational Lambdas, and publish runtime configuration to SSM.
#
# Parameters:
#   Every configurable value is declared in variables.tf. Credentials and
#   private keys are generated during apply and never accepted as inputs.
#
# Return values:
#   outputs.tf publishes grouped operational handles and consumes every child
#   module's public output contract.
#
# Errors / Exceptions:
#   Module validation catches malformed values before apply. Runtime bootstrap
#   fails the apply if V0 SQL, credential rotation prerequisites, or the Data
#   API cannot complete; a stack is never reported ready with passwordless
#   service roles.
# =============================================================================

data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  health_check_path = "/actuator/health"
  parameter_prefix  = "/${var.name_prefix}"
  # WHY : Assumptions: the name the internal listeners are certified for is an
  #       input when the operator has a private zone for it and a composed default
  #       otherwise. The composed form is deliberately under `.internal`, which is
  #       not a resolvable public suffix, so a self-signed certificate for it cannot
  #       be mistaken for one that would be trusted anywhere outside this VPC.
  internal_service_dns_name = coalesce(var.internal_service_domain_name, "${var.name_prefix}-${var.environment}.services.internal")

  # WHY : Assumptions: an operator-issued ALB certificate is used when supplied and
  #       the self-signed one otherwise. Both are terminated by the INTERNAL load
  #       balancer, which publishes no public listener, so the trust decision is the
  #       VPC's rather than a browser's -- which is what makes the self-signed
  #       fallback acceptable here and unacceptable at the CloudFront edge, where
  #       infra/modules/cloudfront-spa requires a real certificate unconditionally.
  #       Trade-offs: supplying a certificate means an apply cannot silently fall
  #       back to a self-signed leaf in an environment that has a real one, which is
  #       the failure this input exists to prevent.
  alb_certificate_arn = coalesce(var.alb_certificate_arn, aws_acm_certificate.internal_service.arn)
  jdbc_url            = "jdbc:postgresql://${module.aurora.writer_endpoint}:${module.aurora.port}/${module.aurora.database_name}"
  # WHY : Assumptions: the SPA's public origin is its FIRST alias, not the
  #       distribution's generated cloudfront.net name. cloudfront-spa requires a
  #       certificate and a non-empty alias list in every environment, so viewers
  #       always arrive on an alias and the generated name serves nothing. Reading
  #       the alias also keeps this value out of the distribution's dependency
  #       chain, so the API's CORS configuration -- which is what consumes it --
  #       does not have to wait on, or depend on, the distribution being created.
  spa_origin = "https://${var.cloudfront_aliases[0]}"

  # WHY : Assumptions: these seven contexts are the complete synchronous edge
  #       surface. Batch and data migration have task definitions but no
  #       long-running ECS service or ALB target group. One entry per CONTEXT, not
  #       per path segment: a context may own more than one top-level segment, and
  #       one does, so its patterns list is the place that grows rather than this map.
  # WHY : Assumptions: every pattern carries the /api/v1 prefix the HTTP API's own
  #       route keys publish, so an ALB rule and the edge route it is reached through
  #       name the same path. A pattern without the prefix can never match a request
  #       forwarded from that edge, which is why infra/modules/alb validates it rather
  #       than accepting whatever a root supplies.
  online_services = {
    # WHY : Refactoring Rationale: auth-service carries TWO patterns, not four. An
    #       earlier revision added `/api/v1/users` and `/api/v1/users/*` on the
    #       reading that user administration sat on its own top-level segment. It
    #       does not: the auth contract publishes those five operations at
    #       `/api/v1/auth/users` and `/api/v1/auth/users/{userId}`, which is also
    #       what `SecurityConfig.USER_COLLECTION_PATH_PATTERN` and
    #       `USER_SUBTREE_PATH_PATTERN` gate and what that service's contract test
    #       asserts the two agree on. The wildcard `/api/v1/auth/*` below therefore
    #       already forwards every one of them, and the withdrawn pair named an
    #       address no contract publishes -- so a request to it would have been
    #       forwarded to a service with no handler for it, which reports as an
    #       unimplemented operation rather than as a misrouted one. Withdrawing it
    #       here is the second half of the same fix as withdrawing the
    #       `/api/v1/users` route keys in infra/modules/api-gateway-http; the two
    #       lists have to name the same paths or one of them is describing a
    #       topology that does not exist.
    # WHY : Assumptions: the bare pattern is listed beside the wildcard for the same
    #       reason the gateway pairs a bare key with a greedy one -- `/api/v1/auth/*`
    #       does not match `/api/v1/auth` itself. Two values is well within the five
    #       a single path-pattern condition accepts, so this needs no second rule and
    #       no second priority.
    auth = {
      repository = "auth-service"
      role       = "carddemo_auth"
      priority   = 10
      paths      = ["/api/v1/auth", "/api/v1/auth/*"]
    }
    account = {
      repository = "account-service"
      role       = "carddemo_account"
      priority   = 20
      paths      = ["/api/v1/accounts", "/api/v1/accounts/*"]
    }
    card = {
      repository = "card-service"
      role       = "carddemo_card"
      priority   = 30
      paths      = ["/api/v1/cards", "/api/v1/cards/*"]
    }
    transaction = {
      repository = "transaction-service"
      role       = "carddemo_ledger"
      priority   = 40
      paths      = ["/api/v1/transactions", "/api/v1/transactions/*", "/api/v1/billpay", "/api/v1/billpay/*"]
    }
    reference = {
      repository = "reference-service"
      role       = "carddemo_reference"
      priority   = 50
      paths      = ["/api/v1/reference", "/api/v1/reference/*"]
    }
    authorization = {
      repository = "authorization-service"
      role       = "carddemo_authorization"
      priority   = 60
      paths      = ["/api/v1/authorizations", "/api/v1/authorizations/*"]
    }
    reporting = {
      repository = "reporting-service"
      role       = "carddemo_reporting"
      priority   = 70
      paths      = ["/api/v1/reports", "/api/v1/reports/*"]
    }
  }

  workloads = merge(
    {
      for name, service in local.online_services : name => merge(service, {
        online         = true
        container_name = name
      })
    },
    {
      batch = {
        repository     = "batch-service"
        role           = "carddemo_batch"
        online         = false
        container_name = "batch"
        priority       = null
        paths          = []
      }
      data-migration = {
        repository     = "data-migration"
        role           = null
        online         = false
        container_name = "data-migration"
        priority       = null
        paths          = []
      }
    },
  )

  # WHY : Assumptions: the eight service login roles are named by
  #       data-migration/sql/V0__schemas_and_roles.sql and created there without a
  #       password; this list is the same inventory in the same order, used to build
  #       the ETL's alternate-login map and nothing else. It is written out rather
  #       than derived from local.workloads because reporting and batch share no
  #       one-to-one mapping with a role in that structure -- data-migration has no
  #       role at all -- so deriving it would need a filter that says less than the
  #       list does.
  service_role_names = [
    "carddemo_auth",
    "carddemo_account",
    "carddemo_card",
    "carddemo_transaction",
    "carddemo_reference",
    "carddemo_batch",
    "carddemo_authorization",
    "carddemo_reporting",
  ]

  database_workload_names = toset([
    "auth",
    "account",
    "card",
    "transaction",
    "reference",
    "authorization",
    "reporting",
    "batch",
  ])

  lambda_names = {
    quiesce           = "${var.name_prefix}-${var.environment}-quiesce-online"
    resume            = "${var.name_prefix}-${var.environment}-resume-online"
    database_admin    = "${var.name_prefix}-${var.environment}-database-admin"
    dataset_retention = "${var.name_prefix}-${var.environment}-dataset-retention"
  }

  lambda_log_group_names = {
    for key, function_name in local.lambda_names :
    key => "/aws/lambda/${function_name}"
  }

  # WHY : Refactoring Rationale: reporting-service needs the ad-hoc machine ARN
  #       in its task definition, while that state machine needs the reporting
  #       task-definition ARN. Constructing the deterministic ARN breaks that
  #       otherwise irreducible cycle; a contract assertion below compares it
  #       with the module's real output so naming drift fails the plan.
  adhoc_report_state_machine_arn = "arn:${data.aws_partition.current.partition}:states:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:stateMachine:${var.name_prefix}-${var.environment}-adhoc-report"
}

# -----------------------------------------------------------------------------
# Lambda deployment packages.
# -----------------------------------------------------------------------------

data "archive_file" "online_write_flag" {
  type        = "zip"
  source_file = "${path.root}/../../lambda/online_write_flag.py"
  output_path = "${path.root}/.terraform/online-write-flag.zip"
}

data "archive_file" "database_admin" {
  type        = "zip"
  output_path = "${path.root}/.terraform/database-admin.zip"

  source {
    content  = file("${path.root}/../../lambda/database_admin.py")
    filename = "database_admin.py"
  }

  source {
    content  = file("${path.root}/../../../data-migration/sql/V0__schemas_and_roles.sql")
    filename = "V0__schemas_and_roles.sql"
  }
}

data "archive_file" "dataset_retention" {
  type        = "zip"
  source_file = "${path.root}/../../lambda/dataset_generation_retention.py"
  output_path = "${path.root}/.terraform/dataset-generation-retention.zip"
}

# -----------------------------------------------------------------------------
# Foundational modules and internal TLS material.
# -----------------------------------------------------------------------------

module "cloudfront_spa" {
  source = "../../modules/cloudfront-spa"

  name_prefix    = var.name_prefix
  environment    = var.environment
  s3_kms_key_arn = module.kms.s3_key_arn
  price_class    = var.cloudfront_price_class
  force_destroy  = !var.deletion_protection

  # WHY : Assumptions: the certificate and the alias list are ONE decision and
  #       both are required by the module. A distribution answering only on its
  #       generated cloudfront.net name has to use CloudFront's default
  #       certificate, which pins the viewer security policy to TLSv1, so
  #       "no custom domain" is not an available state in either environment.
  #       Both values are deployment-specific and arrive as TF_VAR_* rather
  #       than from terraform.tfvars.
  acm_certificate_arn = var.cloudfront_acm_certificate_arn
  aliases             = var.cloudfront_aliases
  log_retention_days  = var.log_retention_days

  # WHY : Assumptions: no access-log retention value is passed because the module
  #       publishes no access-log destination -- CloudFront standard logging
  #       records the resolved viewer URI, and this SPA's routes carry account,
  #       card and transaction identifiers. Route-level request history comes from
  #       the API Gateway access log instead, which records the matched route key.
  api_connect_src_origins = var.cloudfront_api_connect_src_origins

  # WHY : Assumptions: this is an ORDERING token, not a value the module uses. The
  #       log-delivery resource asserts it is non-empty so delivery cannot be
  #       created before the S3 key policy that grants the delivery service its
  #       data-key permission exists; without the edge, Terraform is free to create
  #       delivery first and the service rejects the destination.
  #       Assumptions: this does NOT close a cycle, because the distribution itself
  #       depends only on the KEY (s3_kms_key_arn) while the key POLICY is a
  #       separate resource -- so the chain is delivery -> key policy ->
  #       distribution -> key, which terminates.
  s3_kms_key_policy_id = module.kms.s3_key_policy_id
}

module "kms" {
  source = "../../modules/kms"

  name_prefix                 = var.name_prefix
  environment                 = var.environment
  deletion_window_in_days     = var.environment == "dev" ? 7 : 30
  cloudfront_distribution_arn = module.cloudfront_spa.distribution_arn

  # WHY : Assumptions: naming the buckets narrows every S3 grant on this key from
  #       "any bucket that references the key" to these four exact buckets, through
  #       the aws:s3:arn encryption context. It is safe to wire because a bucket
  #       depends on the KEY, not on the key POLICY, so the policy can wait for the
  #       bucket ARNs without either waiting for the other.
  s3_encryption_context_bucket_arns = [
    module.s3_datasets.bucket_arn,
    module.s3_datasets.audit_bucket_arn,
    module.cloudfront_spa.spa_bucket_arn,
    module.cloudfront_spa.log_bucket_arn,
  ]

  # WHY : Assumptions: the CloudWatch Logs delivery service generates the data key
  #       that encrypts each delivered access-log object, so it needs its own grant,
  #       narrowed to this one delivery source.
  cloudwatch_log_delivery_source_arns = [module.cloudfront_spa.log_delivery_source_arn]

  # WHY : Alternatives Considered: also wiring cloudwatch_log_group_arns and
  #       sns_topic_arns, which would narrow those two grants from an
  #       account-and-region pattern to exact ARNs. REJECTED on an apply-ordering
  #       ground rather than a security one: every log group here is created WITH
  #       this key, and CreateLogGroup fails with AccessDenied unless the key policy
  #       already permits the Logs service. Passing the group ARNs makes the policy
  #       depend on the groups, so Terraform would create a group before the policy
  #       that authorizes it and the apply would fail. The same holds for the SNS
  #       topic, which is created with the key. The module therefore renders its
  #       pattern-scoped statements for those two purposes, bounded to this account,
  #       this region and this deployment's name prefix.
}

# -----------------------------------------------------------------------------
# GitHub Actions SPA publication role.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "spa_publication_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # WHY : Assumptions: GitHub's environment subject binds this role to the
    #       named repository AND this Terraform environment. Repository
    #       environment protection rules then control which branches/reviewers
    #       may request the token; a branch wildcard in IAM is neither needed
    #       nor accepted.
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:${var.environment}"]
    }
  }
}

resource "aws_iam_role" "spa_publication" {
  name                 = "${var.name_prefix}-${var.environment}-spa-publication"
  description          = "OIDC-assumed GitHub Actions role that publishes only the ${var.environment} SPA bundle."
  assume_role_policy   = data.aws_iam_policy_document.spa_publication_assume_role.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "spa_publication" {
  statement {
    sid       = "ListSpaOrigin"
    actions   = ["s3:GetBucketLocation", "s3:ListBucket", "s3:ListBucketMultipartUploads"]
    resources = [module.cloudfront_spa.spa_bucket_arn]
  }

  statement {
    sid       = "SynchronizeSpaObjects"
    actions   = ["s3:AbortMultipartUpload", "s3:DeleteObject", "s3:GetObject", "s3:ListMultipartUploadParts", "s3:PutObject"]
    resources = ["${module.cloudfront_spa.spa_bucket_arn}/*"]
  }

  statement {
    sid = "EncryptSpaObjects"
    actions = [
      "kms:Decrypt",
      "kms:DescribeKey",
      "kms:Encrypt",
      "kms:GenerateDataKey*",
      "kms:ReEncrypt*",
    ]
    resources = [module.kms.s3_key_arn]
  }

  statement {
    sid       = "InvalidatePublishedSpa"
    actions   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
    resources = [module.cloudfront_spa.distribution_arn]
  }
}

resource "aws_iam_role_policy" "spa_publication" {
  name   = "${var.name_prefix}-${var.environment}-spa-publication"
  role   = aws_iam_role.spa_publication.id
  policy = data.aws_iam_policy_document.spa_publication.json
}

module "network" {
  source = "../../modules/network"

  name_prefix             = var.name_prefix
  environment             = var.environment
  vpc_cidr                = var.vpc_cidr
  app_container_port      = 8080
  database_port           = 5432
  flow_log_retention_days = var.log_retention_days
  flow_log_kms_key_arn    = module.kms.s3_key_arn
}

module "ecr" {
  source = "../../modules/ecr"

  name_prefix  = var.name_prefix
  environment  = var.environment
  kms_key_arn  = module.kms.s3_key_arn
  force_delete = !var.deletion_protection
}

module "aurora" {
  source = "../../modules/aurora-postgresql"

  name_prefix                  = var.name_prefix
  environment                  = var.environment
  isolated_subnet_ids          = module.network.isolated_data_subnet_ids
  security_group_ids           = [module.network.data_security_group_id]
  kms_key_arn                  = module.kms.aurora_key_arn
  secrets_kms_key_arn          = module.kms.secrets_key_arn
  engine_version               = var.aurora_engine_version
  parameter_group_family       = var.aurora_parameter_group_family
  port                         = module.network.database_port
  min_capacity                 = var.aurora_min_capacity
  max_capacity                 = var.aurora_max_capacity
  seconds_until_auto_pause     = var.aurora_seconds_until_auto_pause
  backup_retention_period      = var.aurora_backup_retention_period
  preferred_backup_window      = var.aurora_preferred_backup_window
  preferred_maintenance_window = var.aurora_preferred_maintenance_window
  deletion_protection          = var.deletion_protection
  skip_final_snapshot          = var.skip_final_snapshot
  enable_http_endpoint         = true
}

resource "tls_private_key" "internal_service" {
  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "internal_service" {
  private_key_pem = tls_private_key.internal_service.private_key_pem

  subject {
    common_name  = local.internal_service_dns_name
    organization = "CardDemo"
  }

  dns_names             = [local.internal_service_dns_name]
  validity_period_hours = 8760
  early_renewal_hours   = 720
  allowed_uses          = ["key_encipherment", "digital_signature", "server_auth"]
}

resource "aws_acm_certificate" "internal_service" {
  private_key      = tls_private_key.internal_service.private_key_pem
  certificate_body = tls_self_signed_cert.internal_service.cert_pem

  lifecycle {
    create_before_destroy = true
  }
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  name_prefix                    = var.name_prefix
  environment                    = var.environment
  execute_command_log_group_name = "/aws/ecs/${var.name_prefix}-${var.environment}/execute-command"
  kms_key_arn                    = module.kms.s3_key_arn
}

# -----------------------------------------------------------------------------
# Runtime control parameters and Lambda IAM.
# -----------------------------------------------------------------------------

resource "aws_ssm_parameter" "online_writes_enabled" {
  name        = "${local.parameter_prefix}/${var.environment}/batch/online-writes-enabled"
  description = "Runtime gate set false while the nightly posting chain owns the write window."
  type        = "String"
  value       = "true"

  lifecycle {
    # WHY : Assumptions: quiesce/resume Lambdas own this value after creation.
    #       Terraform retains the resource and metadata without undoing a live
    #       batch-window transition during an unrelated apply.
    ignore_changes = [value]
  }
}

data "aws_iam_policy_document" "lambda_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.${data.aws_partition.current.dns_suffix}"]
    }
  }
}

resource "aws_iam_role" "lambda" {
  for_each = {
    online_write      = "online-write"
    database_admin    = "database-admin"
    dataset_retention = "dataset-retention"
  }

  name               = "${var.name_prefix}-${var.environment}-${each.value}-lambda"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume_role.json
}

locals {
  lambda_role_log_keys = {
    online_write      = ["quiesce", "resume"]
    database_admin    = ["database_admin"]
    dataset_retention = ["dataset_retention"]
  }
}

data "aws_iam_policy_document" "lambda_logs" {
  for_each = local.lambda_role_log_keys

  statement {
    sid     = "WriteOwnLambdaLogs"
    actions = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = [
      for key in each.value :
      "${module.observability.managed_log_group_arns[key]}:*"
    ]
  }
}

data "aws_iam_policy_document" "online_write_lambda" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["online_write"].json]

  statement {
    sid       = "UpdateOnlineWriteGate"
    actions   = ["ssm:PutParameter"]
    resources = [aws_ssm_parameter.online_writes_enabled.arn]
  }
}

data "aws_iam_policy_document" "database_admin_lambda" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["database_admin"].json]

  statement {
    sid = "UseAuroraDataApi"
    actions = [
      "rds-data:BeginTransaction",
      "rds-data:CommitTransaction",
      "rds-data:ExecuteStatement",
      "rds-data:RollbackTransaction",
    ]
    resources = [module.aurora.cluster_arn]
  }

  statement {
    sid       = "ReadRdsManagedMasterSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [module.aurora.master_user_secret_arn]
  }

  statement {
    sid       = "DecryptRdsManagedMasterSecret"
    actions   = ["kms:Decrypt"]
    resources = [module.kms.aurora_key_arn]
  }
}

data "aws_iam_policy_document" "dataset_retention_lambda" {
  source_policy_documents = [data.aws_iam_policy_document.lambda_logs["dataset_retention"].json]
}

locals {
  lambda_policy_json = {
    online_write      = data.aws_iam_policy_document.online_write_lambda.json
    database_admin    = data.aws_iam_policy_document.database_admin_lambda.json
    dataset_retention = data.aws_iam_policy_document.dataset_retention_lambda.json
  }
}

resource "aws_iam_role_policy" "lambda" {
  for_each = local.lambda_policy_json

  name   = "${var.name_prefix}-${var.environment}-${each.key}"
  role   = aws_iam_role.lambda[each.key].id
  policy = each.value
}

resource "aws_lambda_function" "quiesce" {
  function_name    = local.lambda_names.quiesce
  role             = aws_iam_role.lambda["online_write"].arn
  runtime          = "python3.13"
  handler          = "online_write_flag.handler"
  filename         = data.archive_file.online_write_flag.output_path
  source_code_hash = data.archive_file.online_write_flag.output_base64sha256
  timeout          = 30
  memory_size      = 128

  environment {
    variables = {
      PARAMETER_NAME  = aws_ssm_parameter.online_writes_enabled.name
      EXPECTED_ACTION = "quiesce"
      TARGET_VALUE    = "false"
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "resume" {
  function_name    = local.lambda_names.resume
  role             = aws_iam_role.lambda["online_write"].arn
  runtime          = "python3.13"
  handler          = "online_write_flag.handler"
  filename         = data.archive_file.online_write_flag.output_path
  source_code_hash = data.archive_file.online_write_flag.output_base64sha256
  timeout          = 30
  memory_size      = 128

  environment {
    variables = {
      PARAMETER_NAME  = aws_ssm_parameter.online_writes_enabled.name
      EXPECTED_ACTION = "resume"
      TARGET_VALUE    = "true"
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "database_admin" {
  function_name    = local.lambda_names.database_admin
  role             = aws_iam_role.lambda["database_admin"].arn
  runtime          = "python3.13"
  handler          = "database_admin.handler"
  filename         = data.archive_file.database_admin.output_path
  source_code_hash = data.archive_file.database_admin.output_base64sha256
  timeout          = 300
  memory_size      = 512

  environment {
    variables = {
      DB_CLUSTER_ARN       = module.aurora.cluster_arn
      DB_MASTER_SECRET_ARN = module.aurora.master_user_secret_arn
      DB_NAME              = module.aurora.database_name
      BOOTSTRAP_SQL_FILE   = "V0__schemas_and_roles.sql"
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_function" "dataset_retention" {
  function_name    = local.lambda_names.dataset_retention
  role             = aws_iam_role.lambda["dataset_retention"].arn
  runtime          = "python3.13"
  handler          = "dataset_generation_retention.handler"
  filename         = data.archive_file.dataset_retention.output_path
  source_code_hash = data.archive_file.dataset_retention.output_base64sha256
  timeout          = 120
  memory_size      = 256

  environment {
    variables = {
      RETENTION_COUNT = "5"
    }
  }

  depends_on = [aws_iam_role_policy.lambda]
}

resource "aws_lambda_invocation" "database_bootstrap" {
  function_name = aws_lambda_function.database_admin.function_name
  input         = jsonencode({ action = "bootstrap" })
  triggers = {
    function_code = data.archive_file.database_admin.output_base64sha256
    bootstrap_sql = filesha256("${path.root}/../../../data-migration/sql/V0__schemas_and_roles.sql")
    cluster_arn   = module.aurora.cluster_arn
  }
}

module "secrets" {
  source = "../../modules/secrets"

  name_prefix             = var.name_prefix
  environment             = var.environment
  kms_key_arn             = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumptions: the module owns the rotation function, so it needs the four
  #       coordinates that function uses to reach the database plus the master
  #       secret it authenticates with. It is deliberately NOT given a password:
  #       V0__schemas_and_roles.sql creates the eight service roles with no password
  #       clause, and the first rotation is what applies one, through the RDS Data
  #       API so no credential ever reaches Terraform state, a process argument or a
  #       log. That is also why the cluster exposes an HTTP endpoint.
  #       Alternatives Considered: one of the AWS-published PostgreSQL rotation
  #       functions. Rejected because single-user rotation authenticates with the
  #       credential it is replacing, which a role created without a password does
  #       not have, so it cannot perform the FIRST application at all.
  aurora_cluster_arn       = module.aurora.cluster_arn
  aurora_master_secret_arn = module.aurora.master_user_secret_arn
  aurora_host              = module.aurora.writer_endpoint
  aurora_port              = module.aurora.port
  aurora_database_name     = module.aurora.database_name

  rotation_automatically_after_days = var.rotation_automatically_after_days
  rotation_log_kms_key_arn          = module.kms.s3_key_arn
  rotation_log_retention_in_days    = var.log_retention_days
  rotation_permissions_boundary_arn = var.permissions_boundary_arn

  # WHY : Assumptions: the TLS pair is supplied by the caller rather than generated
  #       in the module, and this root prefers an operator-issued certificate when
  #       one is configured. The self-signed fallback keeps a fresh environment able
  #       to bring its internal HTTPS listeners up before any certificate authority
  #       is involved -- the listeners are internal to the VPC and fronted by the
  #       load balancer, so the trust decision is the ALB's, which is why a
  #       self-signed leaf is acceptable here and would not be at the edge.
  service_tls_certificate = coalesce(var.service_tls_certificate, tls_self_signed_cert.internal_service.cert_pem)
  service_tls_private_key = coalesce(var.service_tls_private_key, tls_private_key.internal_service.private_key_pem)

  depends_on = [
    aws_lambda_invocation.database_bootstrap,
  ]
}

module "cognito" {
  source = "../../modules/cognito"

  name_prefix                    = var.name_prefix
  environment                    = var.environment
  secrets_kms_key_arn            = module.kms.secrets_key_arn
  callback_urls                  = ["${local.spa_origin}/callback"]
  logout_urls                    = [local.spa_origin]
  mfa_configuration              = var.environment == "prod" ? "ON" : "OPTIONAL"
  advanced_security_mode         = var.environment == "prod" ? "ENFORCED" : "AUDIT"
  deletion_protection            = var.deletion_protection ? "ACTIVE" : "INACTIVE"
  secret_recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumptions: the module defaults this to an EMPTY list, so a root that
  #       says nothing gets a pool with no identities and no way to sign on. Two
  #       identities are seeded -- one of each user_type -- because the migrated
  #       system's authorization split is exactly the baseline's 'A'/'U' split
  #       (app/cpy/COCOM01Y.cpy L27-L28) and an environment that cannot exercise
  #       both halves cannot demonstrate the split at all.
  #       Trade-offs: the baseline's ten demo identities
  #       (app/jcl/DUSRSECJ.jcl L35-L44) are NOT all seeded here. The remaining
  #       eight are ordinary rows the ETL loads into auth.users from USRSEC; only
  #       the two that must be able to authenticate before any data is loaded are
  #       created in the pool, so provisioning does not duplicate a data load.
  #       Each initial credential is generated during apply and stored in Secrets
  #       Manager; no password appears here or in any tfvars file.
  seed_users = [
    {
      user_id     = "ADM00001"
      given_name  = "Demo"
      family_name = "Admin"
      user_type   = "A"
    },
    {
      user_id     = "USR00001"
      given_name  = "Demo"
      family_name = "User"
      user_type   = "U"
    },
  ]
}

module "sqs" {
  source = "../../modules/sqs"

  name_prefix = var.name_prefix
  environment = var.environment
  kms_key_arn = module.kms.sqs_key_arn
}

module "s3_datasets" {
  source = "../../modules/s3-datasets"

  name_prefix               = var.name_prefix
  environment               = var.environment
  kms_key_arn               = module.kms.s3_key_arn
  object_created_lambda_arn = aws_lambda_function.dataset_retention.arn
  access_log_bucket_name    = module.observability.access_log_bucket_name
  force_destroy             = !var.deletion_protection
}

data "aws_iam_policy_document" "dataset_retention_s3" {
  statement {
    sid       = "ListDatasetGenerationPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]
  }

  statement {
    sid       = "DeleteObsoleteGenerationObjects"
    actions   = ["s3:DeleteObject"]
    resources = ["${module.s3_datasets.bucket_arn}/*"]
  }
}

resource "aws_iam_role_policy" "dataset_retention_s3" {
  name   = "${var.name_prefix}-${var.environment}-dataset-retention-s3"
  role   = aws_iam_role.lambda["dataset_retention"].id
  policy = data.aws_iam_policy_document.dataset_retention_s3.json
}

# -----------------------------------------------------------------------------
# Runtime parameter publication.
# -----------------------------------------------------------------------------

locals {
  common_runtime_parameters = merge([
    for service in local.database_workload_names : {
      "${service}|SPRING_DATASOURCE_URL" = {
        service          = service
        environment_name = "SPRING_DATASOURCE_URL"
        value            = local.jdbc_url
      }
      "${service}|SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI" = {
        service          = service
        environment_name = "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI"
        value            = module.cognito.issuer_uri
      }
      "${service}|CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID" = {
        service          = service
        environment_name = "CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID"
        value            = module.cognito.user_pool_client_id
      }
    }
  ]...)

  special_runtime_parameters = {
    "auth|CARDDEMO_AUTH_COGNITO_USER_POOL_ID" = {
      service          = "auth"
      environment_name = "CARDDEMO_AUTH_COGNITO_USER_POOL_ID"
      value            = module.cognito.user_pool_id
    }
    "reference|CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE" = {
      service          = "reference"
      environment_name = "CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE"
      value            = module.sqs.date_inquiry_request_queue_url
    }
    "reference|CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE" = {
      service          = "reference"
      environment_name = "CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE"
      value            = module.sqs.inquiry_reply_queue_url
    }
    "reference|CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE" = {
      service          = "reference"
      environment_name = "CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE"
      value            = module.sqs.error_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE"
      value            = module.sqs.account_inquiry_request_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE"
      value            = module.sqs.inquiry_reply_queue_url
    }
    "account|CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE" = {
      service          = "account"
      environment_name = "CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE"
      value            = module.sqs.error_queue_url
    }
    "authorization|CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE" = {
      service          = "authorization"
      environment_name = "CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE"
      value            = module.sqs.pauth_request_queue_url
    }
    "reporting|CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN" = {
      service          = "reporting"
      environment_name = "CARDDEMO_REPORTING_STEP_FUNCTIONS_STATE_MACHINE_ARN"
      value            = local.adhoc_report_state_machine_arn
    }
    "reporting|CARDDEMO_REPORTING_S3_OUTPUT_BUCKET" = {
      service          = "reporting"
      environment_name = "CARDDEMO_REPORTING_S3_OUTPUT_BUCKET"
      value            = module.s3_datasets.bucket_name
    }
  }

  runtime_parameters = merge(
    local.common_runtime_parameters,
    local.special_runtime_parameters,
  )

  platform_parameters = {
    "aurora/host"                       = module.aurora.writer_endpoint
    "aurora/port"                       = tostring(module.aurora.port)
    "aurora/database"                   = module.aurora.database_name
    "datasets/bucket"                   = module.s3_datasets.bucket_name
    "batch/daily-state-machine-arn"     = module.step_functions.daily_state_machine_arn
    "reporting/adhoc-state-machine-arn" = local.adhoc_report_state_machine_arn
  }
}

resource "aws_ssm_parameter" "runtime" {
  for_each = local.runtime_parameters

  name        = "${local.parameter_prefix}/${var.environment}/${each.value.service}/${each.value.environment_name}"
  description = "Runtime value injected as ${each.value.environment_name} for ${each.value.service}."
  type        = "String"
  value       = each.value.value
}

resource "aws_ssm_parameter" "platform" {
  for_each = local.platform_parameters

  name        = "${local.parameter_prefix}/${var.environment}/${each.key}"
  description = "CardDemo ${var.environment} platform endpoint published by Terraform."
  type        = "String"
  value       = each.value
}

locals {
  runtime_parameter_arns_by_service = {
    for service in keys(local.workloads) :
    service => {
      for composite, parameter in aws_ssm_parameter.runtime :
      split("|", composite)[1] => parameter.arn
      if split("|", composite)[0] == service
    }
  }

  database_secret_sources = {
    for service in local.database_workload_names :
    service => {
      SPRING_DATASOURCE_USERNAME = {
        value_from   = "${module.secrets.service_credential_secrets[local.workloads[service].role].arn}:username::"
        resource_arn = module.secrets.service_credential_secrets[local.workloads[service].role].arn
      }
      SPRING_DATASOURCE_PASSWORD = {
        value_from   = "${module.secrets.service_credential_secrets[local.workloads[service].role].arn}:password::"
        resource_arn = module.secrets.service_credential_secrets[local.workloads[service].role].arn
      }
    }
  }

  # WHY : Assumptions: the two names are the ones the container images read --
  #       every service's application.yml resolves its listener material from
  #       ${CARDDEMO_SERVER_TLS_CERTIFICATE} and ${CARDDEMO_SERVER_TLS_PRIVATE_KEY}
  #       -- and infra/modules/ecs-service refuses a load-balanced service that
  #       omits either, so the names are a contract rather than a convention.
  #       Refactoring Rationale: the material is read from the two SCALAR secrets
  #       infra/modules/secrets creates, not from a root-owned JSON document. One
  #       owner for the secret's name, key, recovery window and tags means those
  #       four decisions cannot drift between the credential secrets and the TLS
  #       secrets, and a scalar needs no JSON-key selector, so `value_from` is the
  #       base ARN and IAM authorizes exactly the ARN the container reads.
  tls_secret_sources = {
    CARDDEMO_SERVER_TLS_CERTIFICATE = {
      value_from   = module.secrets.service_tls_secrets["certificate"].value_reference
      resource_arn = module.secrets.service_tls_secrets["certificate"].arn
    }
    CARDDEMO_SERVER_TLS_PRIVATE_KEY = {
      value_from   = module.secrets.service_tls_secrets["private_key"].value_reference
      resource_arn = module.secrets.service_tls_secrets["private_key"].arn
    }
  }

  auth_client_secret_sources = {
    CARDDEMO_AUTH_COGNITO_CLIENT_ID = {
      value_from   = "${module.cognito.app_client_secret_arn}:client_id::"
      resource_arn = module.cognito.app_client_secret_arn
    }
    CARDDEMO_AUTH_COGNITO_CLIENT_SECRET = {
      value_from   = "${module.cognito.app_client_secret_arn}:client_secret::"
      resource_arn = module.cognito.app_client_secret_arn
    }
  }

  # WHY : Assumptions: the ETL image derives a keyed fingerprint for protected
  #       fields (carddemo_migration.copybook.layouts), and the key must be the same
  #       on every run or two loads of one record produce two different tags. It is
  #       therefore a supplied secret rather than generated per task, and it arrives
  #       as a secret rather than a plain variable because it IS key material.
  mask_hmac_secret_sources = {
    CARDDEMO_MASK_HMAC_KEY = {
      value_from   = var.mask_hmac_secret_arn
      resource_arn = var.mask_hmac_secret_arn
    }
  }

  secret_sources_by_workload = {
    for service, workload in local.workloads :
    service => merge(
      contains(local.database_workload_names, service) ? local.database_secret_sources[service] : {},
      workload.online ? local.tls_secret_sources : {},
      service == "auth" ? local.auth_client_secret_sources : {},
      service == "data-migration" ? local.mask_hmac_secret_sources : {},
    )
  }

  environment_variables_by_workload = {
    for service, workload in local.workloads :
    service => merge(
      {
        AWS_REGION           = var.aws_region
        CARDDEMO_ENVIRONMENT = var.environment
      },

      # WHY : Assumptions: the trust-anchor PATH differs per image because each
      #       image installs the bundle at its own location -- data-migration's
      #       Dockerfile places it at /etc/ssl/certs/aws-rds-global-bundle.pem, while
      #       the service images resolve
      #       /etc/ssl/certs/carddemo-rds-ca-bundle.pem, which is the default their
      #       application.yml files already carry. Passing the matching path
      #       explicitly rather than relying on either default keeps the value
      #       visible in the task definition, which is where an operator debugging a
      #       verify-full failure looks first.
      service == "data-migration" ? {
        CARDDEMO_DB_SSL_ROOT_CERT = "/etc/ssl/certs/aws-rds-global-bundle.pem"
        } : {
        CARDDEMO_DB_SSL_ROOT_CERT = "/etc/ssl/certs/carddemo-rds-ca-bundle.pem"
      },

      # WHY : Assumptions: the ETL image reads its whole database configuration from
      #       Parameter Store and requires three settings the Java images do not.
      #       CARDDEMO_PARAMETER_PREFIX names the namespace,
      #       CARDDEMO_DB_SSL_MODE is accepted only at the value
      #       carddemo_migration.config fixes -- it exists so that LOWERING it fails
      #       loudly rather than silently negotiating a weaker mode -- and
      #       CARDDEMO_DB_ALTERNATE_USERS lists, per role, the one alternate login
      #       that role's rotation may present. The alternate names are the `_clone`
      #       logins the credential-rotation function creates for alternating-user
      #       rotation, so without them the ETL would refuse a connection made with
      #       a freshly rotated credential.
      service == "data-migration" ? {
        CARDDEMO_PARAMETER_PREFIX   = local.parameter_prefix
        CARDDEMO_DB_SSL_MODE        = "verify-full"
        CARDDEMO_DB_ALTERNATE_USERS = join(",", [for role in local.service_role_names : "${role}=${role}_clone"])
        } : {
        SPRING_PROFILES_ACTIVE = var.environment
      },
      workload.online ? {
        CARDDEMO_ONLINE_WRITES_PARAMETER = aws_ssm_parameter.online_writes_enabled.name
      } : {},

      # WHY : Assumptions: reporting-service is the one service that reads
      #       X-Forwarded-For, because it is the one that renders an operator-facing
      #       address into a report header. Tomcat honours the header only from a
      #       proxy it trusts, so the pattern must match the load balancer's own
      #       addresses -- which are in this VPC -- and nothing else. It is derived
      #       from var.vpc_cidr rather than written as a literal so a root that
      #       changes its address space cannot leave a pattern behind that silently
      #       stops trusting its own load balancer.
      #       Trade-offs: only the first two octets are matched, which admits any
      #       address inside a /16. Accepted because the alternative -- enumerating
      #       the three private-application subnet ranges as a regex -- reimplements
      #       CIDR arithmetic in a string, and the addresses admitted are private
      #       addresses inside this VPC in either case.
      service == "reporting" ? {
        CARDDEMO_TRUSTED_PROXY_PATTERN = format(
          "%s\\.%s\\.\\d+\\.\\d+",
          split(".", var.vpc_cidr)[0],
          split(".", var.vpc_cidr)[1],
        )
      } : {},
    )
  }
}

# -----------------------------------------------------------------------------
# Per-workload business IAM policies.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "account_runtime" {
  statement {
    sid       = "ConsumeAccountInquiryRequests"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [module.sqs.account_inquiry_request_queue_arn]
  }

  statement {
    sid       = "PublishAccountInquiryResults"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.inquiry_reply_queue_arn, module.sqs.error_queue_arn]
  }

  statement {
    sid       = "UseEncryptedInquiryQueues"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]
  }
}

data "aws_iam_policy_document" "reference_runtime" {
  statement {
    sid       = "ConsumeDateInquiryRequests"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [module.sqs.date_inquiry_request_queue_arn]
  }

  statement {
    sid       = "PublishDateInquiryResults"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.inquiry_reply_queue_arn, module.sqs.error_queue_arn]
  }

  statement {
    sid       = "UseEncryptedInquiryQueues"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]
  }
}

data "aws_iam_policy_document" "authorization_runtime" {
  statement {
    sid       = "ConsumePendingAuthorizationRequests"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [module.sqs.pauth_request_queue_arn]
  }

  statement {
    sid       = "PublishPendingAuthorizationReplies"
    actions   = ["sqs:SendMessage"]
    resources = [module.sqs.pauth_reply_queue_arn]
  }

  statement {
    sid       = "UseEncryptedAuthorizationQueues"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.sqs_key_arn]
  }
}

data "aws_iam_policy_document" "reporting_runtime" {
  statement {
    sid       = "StartAdhocReportExecution"
    actions   = ["states:StartExecution"]
    resources = [local.adhoc_report_state_machine_arn]
  }

  statement {
    sid       = "ListReportOutputBucket"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]
  }

  statement {
    sid       = "WriteReportOutputs"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"]
    resources = ["${module.s3_datasets.bucket_arn}/*"]
  }

  statement {
    sid       = "UseReportOutputKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.s3_key_arn]
  }
}

data "aws_iam_policy_document" "batch_runtime" {
  statement {
    sid       = "ListDatasetBucket"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]
  }

  statement {
    sid       = "ReadWriteDatasetGenerations"
    actions   = ["s3:GetObject", "s3:PutObject", "s3:AbortMultipartUpload"]
    resources = ["${module.s3_datasets.bucket_arn}/*"]
  }

  statement {
    sid       = "UseDatasetKey"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [module.kms.s3_key_arn]
  }
}

data "aws_iam_policy_document" "data_migration_runtime" {
  source_policy_documents = [data.aws_iam_policy_document.batch_runtime.json]

  statement {
    sid = "ReadRuntimeParameters"
    actions = [
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
    ]
    resources = [
      "arn:${data.aws_partition.current.partition}:ssm:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:parameter/${var.name_prefix}/${var.environment}/*",
    ]
  }

  statement {
    sid       = "ReadServiceDatabaseCredentials"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for secret in values(module.secrets.service_credential_secrets) : secret.arn]
  }

  statement {
    sid       = "DecryptServiceDatabaseCredentials"
    actions   = ["kms:Decrypt"]
    resources = [module.kms.secrets_key_arn]
  }
}

locals {
  task_role_policy_json = {
    account        = data.aws_iam_policy_document.account_runtime.json
    reference      = data.aws_iam_policy_document.reference_runtime.json
    authorization  = data.aws_iam_policy_document.authorization_runtime.json
    reporting      = data.aws_iam_policy_document.reporting_runtime.json
    batch          = data.aws_iam_policy_document.batch_runtime.json
    data-migration = data.aws_iam_policy_document.data_migration_runtime.json
  }
}

module "ecs_service" {
  for_each = local.workloads
  source   = "../../modules/ecs-service"

  service_name           = each.key
  environment            = var.environment
  name_prefix            = var.name_prefix
  cluster_arn            = module.ecs_cluster.cluster_arn
  cluster_name           = module.ecs_cluster.cluster_name
  vpc_id                 = module.network.vpc_id
  private_app_subnet_ids = module.network.private_app_subnet_ids
  security_group_ids     = [module.network.app_security_group_id]
  # WHY : Assumptions: a digest is used when one is supplied for this artifact and
  #       the mutable tag otherwise. infra/modules/ecs-service refuses a
  #       non-digest reference when environment is "prod", so this expression is
  #       what lets one root shape serve both postures: dev iterates by pushing over
  #       a tag, production pins the exact build. A tag can be moved after the task
  #       definition is registered, which would let a scale-out event start a
  #       different build than the one that was reviewed.
  image_uri = (
    lookup(var.image_digests, each.value.repository, null) != null
    ? "${module.ecr.repository_urls[each.value.repository]}@${var.image_digests[each.value.repository]}"
    : "${module.ecr.repository_urls[each.value.repository]}:${var.image_tag}"
  )
  ecr_repository_arn            = module.ecr.repository_arns[each.value.repository]
  container_name                = each.value.container_name
  container_port                = module.network.app_container_port
  task_cpu                      = var.ecs_task_cpu
  task_memory                   = var.ecs_task_memory
  attach_load_balancer          = each.value.online
  create_service                = each.value.online
  desired_count                 = each.value.online ? var.ecs_desired_count : 1
  enable_autoscaling            = each.value.online
  min_capacity                  = var.ecs_desired_count
  max_capacity                  = max(var.ecs_desired_count, var.ecs_desired_count * 2)
  health_check_path             = local.health_check_path
  target_protocol               = "HTTPS"
  log_retention_in_days         = var.log_retention_days
  log_group_kms_key_arn         = module.kms.s3_key_arn
  environment_variables         = local.environment_variables_by_workload[each.key]
  ssm_parameter_arns            = local.runtime_parameter_arns_by_service[each.key]
  secret_arns                   = local.secret_sources_by_workload[each.key]
  create_task_role_policy       = contains(keys(local.task_role_policy_json), each.key)
  task_role_policy_json         = lookup(local.task_role_policy_json, each.key, null)
  execution_secret_kms_key_arns = length(local.secret_sources_by_workload[each.key]) > 0 ? [module.kms.secrets_key_arn] : []

  # WHY : Assumptions: the boundary is an account-level policy this deployment
  #       does not create, because a boundary a deployment can rewrite bounds
  #       nothing. Passing it to every task means the inline policy this root
  #       composes per service cannot exceed the account ceiling even if the
  #       document is later widened.
  permissions_boundary_arn = var.permissions_boundary_arn
}

module "alb" {
  source = "../../modules/alb"

  name_prefix                = var.name_prefix
  environment                = var.environment
  subnet_ids                 = module.network.private_app_subnet_ids
  alb_security_group_id      = module.network.alb_security_group_id
  certificate_arn            = local.alb_certificate_arn
  certificate_domain_name    = local.internal_service_dns_name
  access_logs_bucket         = module.observability.access_log_bucket_name
  enable_deletion_protection = var.deletion_protection
  health_check_path          = local.health_check_path
  service_routes = {
    for name, service in local.online_services :
    name => {
      priority         = service.priority
      path_patterns    = service.paths
      target_group_arn = module.ecs_service[name].target_group_arn
    }
  }
}

module "api_gateway" {
  source = "../../modules/api-gateway-http"

  name_prefix                 = var.name_prefix
  environment                 = var.environment
  cognito_issuer_uri          = module.cognito.issuer_uri
  cognito_app_client_ids      = [module.cognito.user_pool_client_id]
  route_authorization_scopes  = module.cognito.interactive_route_authorization_scopes
  alb_listener_arn            = module.alb.https_listener_arn
  private_app_subnet_ids      = module.network.private_app_subnet_ids
  vpc_id                      = module.network.vpc_id
  alb_security_group_id       = module.network.alb_security_group_id
  spa_cors_allow_origins      = [local.spa_origin]
  log_retention_days          = var.log_retention_days
  access_log_kms_key_arn      = module.kms.s3_key_arn
  integration_tls_server_name = local.internal_service_dns_name
}

module "step_functions" {
  source = "../../modules/step-functions-batch"

  name_prefix                        = var.name_prefix
  environment                        = var.environment
  ecs_cluster_arn                    = module.ecs_cluster.cluster_arn
  batch_task_definition_arn          = module.ecs_service["batch"].task_definition_arn
  data_migration_task_definition_arn = module.ecs_service["data-migration"].task_definition_arn
  reporting_task_definition_arn      = module.ecs_service["reporting"].task_definition_arn
  batch_container_name               = module.ecs_service["batch"].container_name
  data_migration_container_name      = module.ecs_service["data-migration"].container_name
  reporting_container_name           = module.ecs_service["reporting"].container_name
  private_app_subnet_ids             = module.network.private_app_subnet_ids
  security_group_ids                 = [module.network.app_security_group_id]
  task_role_arns = [
    module.ecs_service["batch"].task_role_arn,
    module.ecs_service["batch"].execution_role_arn,
    module.ecs_service["data-migration"].task_role_arn,
    module.ecs_service["data-migration"].execution_role_arn,
    module.ecs_service["reporting"].task_role_arn,
    module.ecs_service["reporting"].execution_role_arn,
  ]
  quiesce_function_arn        = aws_lambda_function.quiesce.arn
  resume_function_arn         = aws_lambda_function.resume.arn
  analyze_tables_function_arn = aws_lambda_function.database_admin.arn
  notification_topic_arn      = module.observability.notification_topic_arn
  dataset_bucket_name         = module.s3_datasets.bucket_name
  log_retention_days          = var.log_retention_days
  kms_key_arn                 = module.kms.s3_key_arn
}

module "eventbridge_scheduler" {
  source = "../../modules/eventbridge-scheduler"

  name_prefix             = var.name_prefix
  environment             = var.environment
  state_machine_arn       = module.step_functions.daily_state_machine_arn
  dead_letter_arn         = module.sqs.error_queue_arn
  dead_letter_kms_key_arn = module.kms.sqs_key_arn
  schedule_expression     = var.batch_schedule_expression
  kms_key_arn             = module.kms.s3_key_arn

  depends_on = [aws_iam_role_policy.dataset_retention_s3]
}

module "observability" {
  source = "../../modules/observability"

  name_prefix      = var.name_prefix
  environment      = var.environment
  ecs_cluster_name = module.ecs_cluster.cluster_name
  alb_arn_suffix   = module.alb.alb_arn_suffix
  service_target_group_arn_suffixes = {
    for name in keys(local.online_services) :
    name => module.ecs_service[name].target_group_arn_suffix
  }
  api_gateway_id                  = module.api_gateway.api_id
  api_gateway_stage_name          = module.api_gateway.stage_name
  aurora_cluster_identifier       = module.aurora.cluster_identifier
  aurora_max_capacity             = var.aurora_max_capacity
  queue_names                     = module.sqs.queue_names
  daily_state_machine_arn         = module.step_functions.daily_state_machine_arn
  vpc_flow_log_group_name         = module.network.flow_log_group_name
  cloudfront_distribution_id      = module.cloudfront_spa.distribution_id
  kms_key_arn                     = module.kms.s3_key_arn
  log_retention_days              = var.log_retention_days
  log_group_names                 = local.lambda_log_group_names
  dashboard_service_names         = sort(keys(local.online_services))
  alarm_email_endpoints           = var.alarm_email_endpoints
  access_log_bucket_force_destroy = !var.deletion_protection

  # WHY : Assumptions: a failed credential rotation is silent -- the secret stays
  #       on its previous version and the system keeps working -- so the rotation
  #       function's Errors metric is the only signal that the control stopped
  #       running. The name comes from the secrets module because that module owns
  #       the function.
  rotation_lambda_function_names = [module.secrets.rotation_lambda_name]
}

# WHY : Assumptions: this assertion is the guard on the one deterministically
#       composed ARN used to break the reporting task/state-machine cycle.
resource "terraform_data" "cross_module_contracts" {
  input = {
    expected_adhoc_report_arn = local.adhoc_report_state_machine_arn
    actual_adhoc_report_arn   = module.step_functions.adhoc_report_state_machine_arn
  }

  lifecycle {
    precondition {
      condition     = local.adhoc_report_state_machine_arn == module.step_functions.adhoc_report_state_machine_arn
      error_message = "The deterministic ad-hoc report state-machine ARN no longer matches the step-functions-batch module output; update the cycle-breaking contract and its consumer together."
    }
  }
}
