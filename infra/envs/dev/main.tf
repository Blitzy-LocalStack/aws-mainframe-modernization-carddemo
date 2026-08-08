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

  # WHY : Assumptions: authorization-service reaches the account context over the
  #       INTERNAL load balancer at the same name the listener certificate is issued
  #       for, so this is that name with the required scheme and nothing else -- no
  #       port, no path, no trailing separator. The client's own guard refuses a base
  #       address carrying a path, a query or a fragment, so composing anything more
  #       here would fail at container start rather than at plan.
  # WHY : Assumptions: this single local feeds BOTH the base address and the approved
  #       origin the client compares it against. One expression rather than two is the
  #       point: the comparison exists to catch a base address repointed without
  #       review, and two separately-typed values would instead make an ordinary
  #       deployment fail on a transcription difference while still admitting a
  #       coordinated edit of both.
  account_context_origin = "https://${coalesce(var.internal_service_domain_name, "${var.name_prefix}-${var.environment}.services.internal")}"

  # WHY : Assumptions: the reference context answers on the SAME internal origin, because every
  #       migrated service sits behind the one internal load balancer and is addressed by path.
  #       It is aliased rather than given its own copy of the expression above so that an edit to
  #       the internal domain cannot move one consumer and leave the other pointed at an address
  #       that no longer answers. Alternatives Considered: passing local.account_context_origin
  #       straight into the reference entries below. Rejected because the name would then say
  #       "account" at a call site that configures a reference lookup, which is exactly the kind
  #       of mismatch a later reader corrects in the wrong direction.
  reference_context_origin = local.account_context_origin

  # WHY : Assumptions: an operator-issued ALB certificate is used when supplied and
  #       the self-signed one otherwise. Both are terminated by the INTERNAL load
  #       balancer, which publishes no public listener, so the trust decision is the
  #       VPC's rather than a browser's -- which is what makes the self-signed
  #       fallback acceptable here and unacceptable at the CloudFront edge, where
  #       infra/modules/cloudfront-spa requires a real certificate unconditionally.
  #       Trade-offs: supplying a certificate means an apply cannot silently fall
  #       back to a self-signed leaf in an environment that has a real one, which is
  #       the failure this input exists to prevent.
  alb_certificate_arn = var.alb_certificate_arn
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
  #       two do -- card carries `/api/v1/admin/cards` beside `/api/v1/cards` and
  #       transaction carries `/api/v1/billpay` beside `/api/v1/transactions` -- so
  #       its patterns list is the place that grows rather than this map.
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
    # WHY : (1) Refactoring Rationale: this service is forwarded on FOUR patterns
    #       rather than two, because it publishes three internal read operations on
    #       two prefixes outside its own subtree -- POST /api/v1/card-xrefs/lookup
    #       and GET /api/v1/customers/{customerId}, alongside
    #       GET /api/v1/accounts/{accountId}. The two out-of-subtree prefixes were
    #       absent from this list while the controllers existed, which is not a
    #       cosmetic gap: a listener with no matching rule answers 404 itself, so the
    #       pending-authorization context's cross-reference and customer calls failed
    #       at the load balancer without reaching a task, and no log in the account
    #       service recorded a request at all.
    #       (2) Assumptions: `/api/v1/card-xrefs/lookup` is listed as the EXACT
    #       operation path rather than as a `/api/v1/card-xrefs/*` subtree, and the
    #       narrower form is chosen deliberately. That prefix carries exactly one
    #       published operation and its request body carries an unmasked primary
    #       account number, so forwarding only the one path means any other request
    #       beneath the prefix is refused at the edge instead of reaching the service
    #       to be refused there. The customer prefix uses the subtree form because its
    #       operation is parameterised by identifier and an exact path cannot express
    #       that.
    #       (3) Trade-offs: no bare `/api/v1/card-xrefs` or `/api/v1/customers`
    #       pattern is carried, which is a departure from the pairing the auth and
    #       card entries use. It is forced rather than chosen: a single path-pattern
    #       condition accepts five values, as the card entry below records, and pairing
    #       both new prefixes would need six. Since no operation sits on either bare
    #       prefix, the cost is only that a request to one reports as misrouted from
    #       the load balancer rather than as unimplemented from the service.
    #       (4) Assumptions: these two prefixes are deliberately NOT added to the
    #       api-gateway-http route table. That module is the PUBLIC edge and its
    #       authorizer validates identity-provider tokens; the three operations here
    #       require a machine token the gateway cannot mint and the account service's
    #       internal filter chain refuses an identity-provider token on these paths.
    #       Publishing them at the edge would therefore expose a PAN-carrying request
    #       path to the internet in exchange for no reachable operation.
    account = {
      repository = "account-service"
      role       = "carddemo_account"
      priority   = 20
      paths = [
        "/api/v1/accounts", "/api/v1/accounts/*",
        "/api/v1/card-xrefs/lookup",
        "/api/v1/customers/*"
      ]
    }
    # WHY : Refactoring Rationale: this service is forwarded on FOUR patterns rather
    #       than two, because its contract of record publishes an administrative
    #       card-detail operation under its own `/api/v1/admin/cards` prefix rather
    #       than as a segment beneath the card subtree. The prefix is what removes the
    #       rule-ordering dependency that the suffix spelling placed on the service's
    #       own authority table -- a subtree pattern also matches a suffix beneath it,
    #       so only the table's order kept an ordinary user out of the one operation
    #       that renders a full account number. Forwarding the prefix here is the
    #       first of the two hops that has to know about it; the gateway route keys in
    #       infra/modules/api-gateway-http are the second, and the two lists have to
    #       name the same paths or one of them describes a topology that does not
    #       exist.
    # WHY : Assumptions: four values still fit one path-pattern condition, which
    #       accepts five, so this needs no second rule and no second priority. Each
    #       prefix is listed as a bare pattern beside its wildcard for the same reason
    #       the auth entry above is -- `/api/v1/admin/cards/*` does not match
    #       `/api/v1/admin/cards` itself -- and the bare admin pattern is carried even
    #       though no operation sits on it, so that a request to it reports as an
    #       unimplemented operation from this service rather than as a misrouted one
    #       from the load balancer.
    card = {
      repository = "card-service"
      role       = "carddemo_card"
      priority   = 30
      paths = [
        "/api/v1/cards", "/api/v1/cards/*",
        "/api/v1/admin/cards", "/api/v1/admin/cards/*"
      ]
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
      # WHY : Assumptions: reporting publishes ONE top-level prefix. Its statement
      #       operations are declared beneath it, at /api/v1/reports/statements and
      #       /api/v1/reports/statements/transactions, so the greedy reports pattern
      #       already reaches them. Two /api/v1/statements patterns were listed here
      #       and are removed: they matched a prefix no service answers, and a rule
      #       that matches nothing is worse than absent -- it makes the routing table
      #       read as though a second reporting surface existed.
      paths = ["/api/v1/reports", "/api/v1/reports/*"]
    }
  }

  # WHY : Assumptions: THE CONNECTION BUDGET IS DERIVED HERE BECAUSE THIS IS THE
  #       ONLY PLACE THAT KNOWS BOTH FACTORS. ADR-003 records "connection count
  #       grows with task count" as a named risk and states the relationship as
  #       tasks TIMES pool size rather than tasks plus pool size; the per-task
  #       pool size is a service configuration and the task count is this root's
  #       configuration, so neither the observability module nor a service can
  #       compute the product alone.
  #       Assumptions: the map below mirrors spring.datasource.hikari.maximum-pool-size
  #       in each service's application.yml, and it is NOT uniform -- six services
  #       run a pool of ten while reporting-service and batch-service run four,
  #       the smaller pools reflecting read-mostly and single-task workloads. A
  #       value here that drifts from a service's application.yml understates the
  #       budget, which is why each entry names the service it mirrors rather than
  #       being folded into one multiplier.
  #       Trade-offs: the product uses each workload's MAXIMUM task count, not its
  #       desired count, so the threshold is the total every configured pool could
  #       open at full autoscale. That is deliberately the same philosophy as the
  #       serverless-capacity alarm, whose own rationale rejects alarming at a
  #       fraction of a configured maximum on the ground that a fraction is a
  #       chosen number with no source in the repository. Reaching this figure
  #       means every pool is full and further demand queues; a fraction of it
  #       would fire during ordinary scale-out.
  connection_pool_sizes = {
    auth          = 10
    account       = 10
    card          = 10
    transaction   = 10
    reference     = 10
    authorization = 10
    reporting     = 4
    batch         = 4
  }

  # WHY : Assumptions: an online workload autoscales to twice its desired count
  #       (see the ecs_service max_capacity argument, which sets exactly that),
  #       while batch runs a single task. data-migration is absent from the pool
  #       map above because it holds no JDBC pool: the extract-transform-load
  #       package connects with psycopg for the duration of a load and is not a
  #       long-lived pooled service, so counting it would inflate the budget with
  #       connections no pool ever holds.
  database_connection_budget = sum([
    for name, pool in local.connection_pool_sizes :
    pool * (name == "batch" ? 1 : var.ecs_desired_count * 2)
  ])

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

  # WHY : Assumptions: the eight RUNTIME service login roles are named by
  #       data-migration/sql/V0__schemas_and_roles.sql and created there without a
  #       password; this list is the same inventory in the same order, used to build
  #       the ETL's alternate-login map and nothing else. It is written out rather
  #       than derived from local.workloads because reporting and batch share no
  #       one-to-one mapping with a role in that structure -- data-migration has no
  #       role at all -- so deriving it would need a filter that says less than the
  #       list does.
  # WHY : Assumptions: the SEVEN carddemo_<context>_migrator logins V0 also creates
  #       are deliberately absent from this list, because the list feeds only the
  #       ETL's alternate-login allowlist and the ETL never connects as a migrator
  #       -- carddemo_migration.config.role_for_schema resolves the runtime role for
  #       every schema. The migration credentials are consumed by each service's
  #       Flyway configuration instead, and are projected into task definitions by
  #       local.database_secret_sources below. The eight NOLOGIN
  #       carddemo_<context>_owner roles are absent for a stronger reason: they hold
  #       no credential at all, so there is nothing an allowlist could name.
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

  # Assumptions: these two key prefixes are the complete set of object keys the
  #   reporting workload touches, and they are transcribed from the workload's
  #   own configuration rather than invented here:
  #   services/reporting-service/src/main/resources/application.yml declares
  #   `report-prefix: reports/transaction-detail/` at L1191 and
  #   `statement-prefix: statements/` at L1192, and that file records at L1186
  #   that both are literals in the base document precisely so the layout is the
  #   same in every environment. Nothing else in the bucket is a reporting
  #   artifact.
  #
  # Refactoring Rationale: the reporting task role held `s3:ListBucket` on the
  #   whole bucket and `s3:GetObject`, `s3:PutObject` and
  #   `s3:AbortMultipartUpload` on every object in it. That bucket also holds the
  #   ten nightly dataset generation families, including the transaction backup
  #   and combined generations, whose records carry an unmasked
  #   `TRAN-CARD-NUM PIC X(16)` at offset 262 of the 350-byte layout at
  #   app/cpy/CVTRA05Y.cpy. So the grant let a reporting task enumerate and read
  #   every primary account number the system has ever posted, which is neither
  #   something the workload does nor something it should be able to do. Scoping
  #   the grant to the two prefixes above removes the capability without removing
  #   any behaviour: the two orchestrated states this role serves write into
  #   those prefixes and read nothing.
  #
  # Trade-offs: a prefix condition and an object-ARN restriction are BOTH
  #   applied, rather than either alone, because they bound different calls. An
  #   object ARN cannot bound `ListBucket`, whose resource is the bucket itself
  #   and whose scope is expressed only by the `s3:prefix` condition key; and a
  #   prefix condition does not apply to `GetObject` or `PutObject`, whose scope
  #   is expressed only by the object ARN. Applying one and not the other would
  #   leave the other call unbounded.
  reporting_object_key_prefixes = [
    "reports/transaction-detail/",
    "statements/",
  ]
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

# =============================================================================
# Internal listener material -- DELIBERATELY ABSENT from this root.
# -----------------------------------------------------------------------------
# WHY : Refactoring Rationale: this position held a key generator, a self-signed
#       leaf, an imported ACM certificate and two Secrets Manager entries with
#       their versions -- eight resources that produced ONE RSA private key, wrote
#       it into Terraform state, imported it into ACM, copied it into Secrets
#       Manager and injected it into every online task. Anyone able to read this
#       environment's state file held the server private key of all eight services
#       at once, and the comments here asserted the opposite. All eight are
#       deleted. Each task now mints its OWN key pair and self-signed certificate
#       at startup, in config/docker/generate-listener-material.sh, onto the task's
#       encrypted ephemeral volume, and that material is destroyed with the task.
#       No listener private key exists in state, in Secrets Manager, in a task
#       definition or in plan output, and no two tasks share one.
# WHY : Assumptions: the imported ACM certificate was already DEAD before this
#       change. Its ARN was referenced only through
#       `coalesce(var.alb_certificate_arn, ...)`, and var.alb_certificate_arn is
#       `nullable = false` with no default, so the coalesce could never select it --
#       the root created an ACM certificate on every apply that no listener ever
#       used, while persisting its private key. Deleting it removes a resource, a
#       cost and a key custody, and changes no behaviour.
# WHY : Alternatives Considered: keeping the chain and generating the key with the
#       tls provider's EPHEMERAL resource, writing it through the aws provider's
#       write-only `secret_string_wo` and `private_key_wo` arguments. All three
#       mechanisms exist in the pinned provider versions, and `terraform validate`
#       still refuses the wiring: "Ephemeral values are not valid for
#       \"private_key_pem\", because it is not a write-only attribute and must be
#       persisted to state." The self-signed-certificate resource has no write-only
#       attribute, so the key could be kept out of state only by giving up the
#       certificate entirely.
# WHY : Alternatives Considered: an AWS Private CA, so that ACM generates and holds
#       the key. Rejected on recurring cost for one internal listener behind a
#       private integration, and unnecessary once the tasks certify themselves. The
#       generator script's header records the third rejected option, a Lambda that
#       mints and imports the pair, together with the measurement of the Lambda
#       runtime contents that rules it out.
# =============================================================================

# -----------------------------------------------------------------------------
# Messaging HMAC key.
# -----------------------------------------------------------------------------
#
# WHY : Assumptions: this key exists so that the pending-authorization queue's
#       FIFO group identity can be a purpose-scoped opaque derivation of the card
#       number instead of the card number itself. A group identifier is message
#       METADATA: it sits outside the encrypted body, it is reported in queue
#       telemetry, and it is carried into every log and metric that observes the
#       queue -- which is exactly where ADR-008 requires an account number to be
#       masked. docs/adr/ADR-004-messaging.md states the requirement under
#       "Ordering is grouped by card"; the derivation and its purpose string are
#       specified in docs/architecture/messaging-contracts.md.
# WHY : Refactoring Rationale: this secret did not exist, and only
#       CARDDEMO_MASK_HMAC_KEY was provisioned -- to the data-migration workload
#       alone. authorization therefore had no key at all, so the one per-card
#       stable value it held was the card number and the card number became the
#       published group identity on every reply. Adding the secret here, rather
#       than widening the mask key's distribution, is what keeps the two trust
#       purposes separable.
# WHY : Alternatives Considered: reusing var.mask_hmac_secret_arn for both
#       purposes, which is one fewer secret to provision and rotate. Rejected on
#       two counts: it would give a one-off migration workload that reads
#       cardholder extracts the ability to compute production queue group
#       identities, and rotating either purpose would then require a coordinated
#       stop of an interactive consumer and a batch workload together.
# WHY : Alternatives Considered: accepting the key as an input variable, the way
#       var.mask_hmac_secret_arn is accepted. Rejected because an operator-supplied
#       value has a tfvars file to be committed in, and because nothing outside
#       this stack produces or consumes this key -- unlike the mask key, whose
#       tags must stay stable across extract loads that may predate this root.
#       Generating it here means the "no secrets committed" constraint holds
#       structurally rather than by reviewer vigilance.
# WHY : Assumptions: this is generated ONCE per environment and shared by every
#       producer on the queue, and it is deliberately not per task or per apply in
#       effect. The group identity must be equal for equal cards across producers
#       and across restarts, because that equality IS the per-card ordering
#       guarantee; a value that changed per task would scatter one card's messages
#       across as many groups as there are running tasks and remove the ordering
#       silently. The write-only version pinned to 1 below is what stops an
#       unrelated plan re-issuing it.

ephemeral "random_password" "messaging_hmac" {
  # WHY : Assumptions: this is EPHEMERAL rather than a managed random_password, so
  #       the generated key is available while the provider writes it to Secrets
  #       Manager and is absent from Terraform state afterwards. A managed resource
  #       retains its result in every state file and in every plan artifact, which
  #       for key material means the state file becomes as sensitive as the secret
  #       store it was meant to keep the material out of. This is the same control
  #       infra/modules/secrets applies to the database credentials.
  # WHY : Assumptions: the length is 64 printable characters, which is twice the
  #       32-byte floor com.carddemo.common.security.OpaqueIdentifier enforces.
  #       Sixty-four is chosen rather than exactly 32 because the Java side accepts
  #       the value as raw text when it is not valid base64, so the character count
  #       is the byte count -- and sitting at the floor would make any future
  #       trimming or encoding change fail at container start.
  #       Trade-offs: special characters are excluded. They add entropy per
  #       character, and they are excluded because this value travels as an
  #       environment variable through a task definition and a shell-quoting
  #       accident on any operator path would corrupt the key silently rather than
  #       visibly; the extra length more than compensates for the smaller alphabet.
  length  = 64
  special = false
}

# WHY : Assumptions: the card-selector signing key is generated here and never
#       authored, so no selector key exists in source. It follows the same
#       write-only shape as the messaging key below: an ephemeral generator whose
#       result reaches only secret_string_wo, so the value is absent from state.
# WHY : Assumptions: a SEPARATE secret from the messaging and internal-identity
#       keys, rather than one key reused. Only card-service holds this one, and the
#       biconditional precondition in infra/modules/ecs-service asserts that from
#       both sides; sharing a key would let any holder mint a row address that
#       card-service would open.
ephemeral "random_password" "card_selector" {
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "card_selector" {
  name        = "${var.name_prefix}/${var.environment}/card/selector-signing-key"
  description = "Purpose-scoped key the CardDemo card service seals and opens opaque card row selectors under, in the ${var.environment} environment. Generated by this root and injected into the task as a scalar secret."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "card_selector" {
  secret_id = aws_secretsmanager_secret.card_selector.id

  # WHY : Assumptions: written through secret_string_wo, the write-only argument, so
  #       the generated value never enters state -- the same discipline every other
  #       generated secret in this root uses.
  secret_string_wo = ephemeral.random_password.card_selector.result

  # WHY : Trade-offs: pinned to the literal 1, matching the sibling generated secrets in
  #       this root. The provider rewrites the stored value only when this version
  #       changes, so pinning it is what stops an unrelated plan replacing the key merely
  #       because the ephemeral generator produced fresh bytes. Here that rewrite would be
  #       especially damaging: every selector already issued was sealed under the previous
  #       key, so a silent rotation would make every list row a client still holds
  #       unopenable. Rotating deliberately means incrementing this literal, which is a
  #       visible plan change rather than a side effect.
  secret_string_wo_version = 1
}

resource "aws_secretsmanager_secret" "messaging_hmac" {
  #checkov:skip=CKV2_AWS_57:Rotating this key requires every producer on the pending-authorization queue to adopt the new value in the same instant, because the FIFO group identity must stay equal for equal cards across producers. An unattended rotation function would change the key for one reader at a time and split one card's in-flight messages across two groups, losing the ordering guarantee the key exists to preserve. Rotation is therefore an attended procedure documented in docs/runbooks/batch-operations.md rather than an automatic one.
  name        = "${var.name_prefix}/${var.environment}/messaging/hmac-key"
  description = "Purpose-scoped HMAC key the CardDemo authorization service derives pending-authorization queue group and correlation identities under, in the ${var.environment} environment. Generated by this root and injected into the task as a scalar secret. Distinct from the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "messaging_hmac" {
  secret_id = aws_secretsmanager_secret.messaging_hmac.id

  # WHY : Assumptions: the value is written through secret_string_wo, the write-only
  #       argument, so it reaches Secrets Manager without being recorded in state.
  #       Pairing it with the ephemeral generator above is one control rather than
  #       two: either half alone would still leave the key in a state file.
  secret_string_wo = ephemeral.random_password.messaging_hmac.result

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets. It is
  #       what stops an unrelated plan rewriting the stored key merely because the
  #       ephemeral generator produced fresh bytes -- and here that rewrite would be
  #       worse than for a password, because a changed key changes every group
  #       identity at once and splits in-flight messages for every card
  #       simultaneously. Advancing it is the deliberate re-issue an attended
  #       rotation performs.
  secret_string_wo_version = 1
}

# -----------------------------------------------------------------------------
# Internal machine identity signing key
# -----------------------------------------------------------------------------
# Purpose:
#   The symmetric key the pending-authorization service signs its
#   machine-to-machine bearer token with and the account service verifies that
#   token against. It is the only credential standing between the three internal
#   account-context reads and an unauthenticated caller inside the network.
#
# Why this exists rather than a managed identity-provider grant:
#   Alternatives Considered: a client-credentials grant from the Cognito user
#   pool, which is the conventional way for one service to authenticate to
#   another and would need no key here at all. It is unavailable in this
#   topology: infra/modules/cognito provisions the pool with domain_prefix set to
#   null, so the pool exposes no hosted domain and therefore no token endpoint
#   for a machine caller to obtain a token from. Adding a hosted domain solely to
#   mint machine tokens was rejected as a wider change than the problem warrants
#   -- it would publish an additional internet-facing authentication surface for
#   a caller that never leaves the VPC.
#   Alternatives Considered: mutual TLS between the two tasks. Rejected as
#   disproportionate for a single caller inside one private network, since it
#   would introduce certificate issuance, distribution and rotation for two
#   services and place a certificate expiry on the authorization path.
#   Alternatives Considered: a static shared header value. Rejected outright: it
#   carries no expiry, names no audience and cannot be scoped, so one capture
#   would be a permanent credential for every internal read.
#
# Why it is a SECOND key rather than the messaging key above:
#   Assumptions: the two are separate secrets and the separation is deliberate.
#   The messaging key derives opaque queue-metadata tokens and is held by the
#   authorization service alone; this one is a signing key deliberately shared
#   with exactly one other service. Sharing a single value would mean that
#   rotating the account context's trust anchor also changed every FIFO group
#   identity in flight, and that a holder of either capability could exercise the
#   other.

ephemeral "random_password" "internal_identity" {
  # WHY : Assumptions: EPHEMERAL rather than a managed random_password, for the same
  #       reason as the messaging key above -- a managed resource retains its result
  #       in every state file and every plan artifact, which for a signing key would
  #       make the state file as sensitive as the secret store.
  # WHY : Assumptions: 64 printable characters, twice the 32-byte floor
  #       com.carddemo.common.security.InternalServiceToken enforces and that
  #       account-service config/InternalApiSecurityConfig.java enforces
  #       independently. Sixty-four rather than exactly 32 because both Java sides
  #       accept the value as raw text when it is not valid base64, so the character
  #       count is the byte count, and sitting at the floor would make any future
  #       encoding change fail at container start on both services at once.
  #       Trade-offs: special characters are excluded. They add entropy per
  #       character and are excluded because this value travels as an environment
  #       variable through two task definitions, so a shell-quoting accident on any
  #       operator path would corrupt it silently; the extra length more than
  #       compensates for the smaller alphabet.
  length  = 64
  special = false
}

resource "aws_secretsmanager_secret" "internal_identity" {
  #checkov:skip=CKV2_AWS_57:Rotating this key requires BOTH the authorization service and the account service to adopt the new value in the same instant, because one signs with it and the other verifies against it. An unattended rotation function would change the stored value while one of the two tasks still held the old one, and every internal account-context read would be refused with a 401 for the duration -- which stalls the authorization consumer rather than degrading it. Rotation is therefore an attended procedure that redeploys both services together, documented in docs/runbooks/deploy.md, rather than an automatic one.
  name        = "${var.name_prefix}/${var.environment}/internal-identity/signing-key"
  description = "Symmetric signing key for CardDemo internal machine-to-machine bearer tokens in the ${var.environment} environment: minted by the authorization service, verified by the account service on its three internal account-context read paths. Generated by this root and injected into BOTH tasks as a scalar secret. Distinct from the messaging HMAC key and from the data-migration masking key."

  kms_key_id              = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days
}

resource "aws_secretsmanager_secret_version" "internal_identity" {
  secret_id = aws_secretsmanager_secret.internal_identity.id

  # WHY : Assumptions: written through secret_string_wo, the write-only argument, so
  #       the value reaches Secrets Manager without being recorded in state. Pairing
  #       it with the ephemeral generator above is one control rather than two:
  #       either half alone would still leave the key in a state file.
  secret_string_wo = ephemeral.random_password.internal_identity.result

  # WHY : Trade-offs: pinned to the literal 1, matching infra/modules/secrets and the
  #       messaging key above. Here an unintended rewrite is worse than for a
  #       password, because the two services read the stored value at task start:
  #       rewriting it without redeploying both leaves one signing with a key the
  #       other does not verify, and the symptom is a 401 on every internal read with
  #       nothing in either service's configuration having changed. Advancing it is
  #       the deliberate re-issue an attended rotation performs.
  secret_string_wo_version = 1
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

  # WHY : Assumptions: the handler READS the flag before it writes it, so the grant
  #       needs GetParameter as well as PutParameter. The read is what makes the
  #       quiesce call a lease acquisition rather than a blind overwrite -- it is how
  #       the function learns whether another execution already owns the write window
  #       -- so the two actions are one capability and are granted together.
  # WHY : Trade-offs: both actions are scoped to this ONE parameter ARN rather than to
  #       a path prefix. A prefix would survive renaming the parameter without an IAM
  #       edit; naming the ARN means a rename fails the plan instead, which is the
  #       preferred failure for a resource this role exists solely to toggle.
  statement {
    sid       = "UpdateOnlineWriteGate"
    actions   = ["ssm:GetParameter", "ssm:PutParameter"]
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

  # WHY : Assumptions: the bootstrap function reads every per-role credential because
  #       data-migration/sql/V0__schemas_and_roles.sql applies them and can only do so
  #       from session settings the CALLER supplies -- the script fails closed on any
  #       login role it finds no value for. The grant is enumerated from
  #       module.secrets' own output rather than written as a prefix wildcard, so it
  #       covers exactly the entries that module created and shrinks or grows with the
  #       role inventory instead of standing open over every secret sharing the name
  #       prefix. The Cognito seed-user entries sit under that same prefix, which is
  #       what a wildcard here would additionally have reached.
  # WHY : Trade-offs: this function therefore holds read access to all fifteen database
  #       credentials at once, which is more than any service task holds -- each task
  #       reads exactly its own. That concentration is inherent to a bootstrap step and
  #       is bounded three ways: the function has no other permission, it is invoked
  #       only by Terraform through aws_lambda_invocation, and neither it nor the
  #       statements it sends puts a credential in text a log could capture.
  statement {
    sid       = "ReadServiceCredentialSecrets"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [for secret in module.secrets.service_credential_secrets : secret.arn]
  }

  # WHY : Assumptions: a second key, distinct from the Aurora key above. The RDS-managed
  #       master secret is encrypted under module.kms.aurora_key_arn and the per-role
  #       entries under module.kms.secrets_key_arn, so a single statement naming one key
  #       would leave every GetSecretValue above failing with an access-denied error
  #       that names the KEY rather than the missing grant.
  statement {
    sid       = "DecryptServiceCredentialSecrets"
    actions   = ["kms:Decrypt"]
    resources = [module.kms.secrets_key_arn]
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

      # WHY : Assumptions: the mapping is projected from module.secrets' output, so the
      #       secret NAMES are the ones that module composed and this root restates no
      #       part of its naming convention. A prefix-plus-role-list form was the
      #       alternative and would have put that convention in a fourth place -- the
      #       module, carddemo_migration.config, its contract test, and here -- where
      #       the copy that goes stale is the one nothing validates.
      # WHY : Trade-offs: a JSON object in an environment variable, roughly 1.5 KB at
      #       fifteen entries, against Lambda's 4 KB total. Names are used rather than
      #       ARNs for exactly that reason: ARNs carry an account, a region and a
      #       six-character suffix each, which would take the same mapping past 2.5 KB
      #       and leave little headroom. GetSecretValue resolves either, and the IAM
      #       statement above is scoped to the ARNs regardless.
      DB_CREDENTIAL_SECRETS = jsonencode({
        for role, secret in module.secrets.service_credential_secrets : role => secret.name
      })
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

    # WHY : Assumptions: a change to the ROLE INVENTORY has to re-invoke this, and none
    #       of the three triggers above notices one. Adding a bounded context changes
    #       the bootstrap SQL and so is already covered; replacing a stored credential
    #       is not, and the function is what binds a stored value to its PostgreSQL
    #       role. Hashing the mapping rather than embedding it keeps the trigger a fixed
    #       length and keeps fifteen secret names out of the plan diff.
    credential_inventory = sha256(jsonencode({
      for role, secret in module.secrets.service_credential_secrets : role => secret.name
    }))
  }

  # WHY : Assumptions: stated explicitly even though the trigger above already reads
  #       module.secrets, because the guarantee is about ORDER rather than about value
  #       resolution and a reader should not have to infer it from an expression. The
  #       bootstrap reads every per-role credential and its SQL refuses to commit
  #       without one, so every secret must exist and hold a generated value before the
  #       function is invoked even once.
  depends_on = [module.secrets]
}

module "secrets" {
  source = "../../modules/secrets"

  name_prefix             = var.name_prefix
  environment             = var.environment
  kms_key_arn             = module.kms.secrets_key_arn
  recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumptions: the module is given the master role's NAME and no other
  #       cluster coordinate. It records the name in each credential document as
  #       the escalation identity, and it needs nothing else: the writer endpoint,
  #       the listener port and the database name are non-secret and are already
  #       published to Parameter Store by module.aurora under the same
  #       <prefix>/<environment>/aurora path the credential names are composed
  #       from, so a consumer reads the coordinates there and only the credential
  #       from Secrets Manager. Passing them to the secrets module as well would
  #       make it a second copy of values module.aurora owns.
  #       Alternatives Considered: passing the RDS-managed master secret ARN, as
  #       this root previously did. Rejected with the module-owned rotation
  #       function it existed to serve: a reusable credential-store module has no
  #       remit to hold a reference to the master credential of the cluster its
  #       consumers connect to.
  #       Assumptions: `database_master_username` is deliberately NOT passed. This
  #       root does not set module.aurora's `master_username` either, so both
  #       modules take their own default and those defaults are the same value by
  #       construction -- each states that it must match the other. Wiring the two
  #       together would mean publishing a `master_username` output from the aurora
  #       module, which has a deliberately closed output contract; widening it to
  #       restate a value neither module's caller overrides would buy nothing.
  #       Trade-offs: a root that ever does override the cluster's master role name
  #       must set this input to the same value in the same change, which is why
  #       both variables' descriptions name each other.

  # WHY : Assumptions: neither rotation input is supplied, so no rotation schedule
  #       is created. infra/modules/secrets deliberately implements no rotation and
  #       creates no rotation function -- the only rotation this package owns is KMS
  #       KEY rotation, in module.kms -- and its two rotation inputs are a
  #       pass-through hook for a root that brings a function of its own. This root
  #       brings none, so binding a stored credential to its PostgreSQL role remains
  #       the schema-bootstrap step's responsibility.
  #       Trade-offs: the accepted cost is that a credential is not re-issued on a
  #       schedule until an operator supplies a rotation function and its interval.
  #       Inventing one here instead would recreate, at the root, the same
  #       out-of-scope function that was removed from the module.
  # WHY : Refactoring Rationale: this module carried
  #       `depends_on = [aws_lambda_invocation.database_bootstrap]`, ordering credential
  #       CREATION after the bootstrap that consumes those credentials. The ordering was
  #       exactly backwards and it made a fresh apply unrunnable, not merely untidy:
  #       data-migration/sql/V0__schemas_and_roles.sql applies each credential from a
  #       session setting the caller supplies and RAISES for any login role it finds no
  #       value for, so on a first apply the bootstrap rolled back before any secret
  #       existed to read. The edge is removed and inverted -- the invocation below now
  #       depends on this module -- which is also the only order in which the function's
  #       DB_CREDENTIAL_SECRETS mapping can be resolved at all.
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
  #       says nothing gets a pool with no identities and no way to sign on. The
  #       list below is the baseline's TEN demo identities, transcribed field for
  #       field from app/jcl/DUSRSECJ.jcl L35-L44 -- the job that writes
  #       AWS.M2.CARDDEMO.USRSEC.PS -- at the SEC-USR-ID, SEC-USR-FNAME,
  #       SEC-USR-LNAME and SEC-USR-TYPE offsets of app/cpy/CSUSR01Y.cpy L18-L22.
  #       That covers the baseline's 'A'/'U' authorization split
  #       (app/cpy/COCOM01Y.cpy L27-L28) five identities either way.
  #       Refactoring Rationale: this list was two INVENTED identities, ADM00001
  #       and USR00001, and their intersection with the baseline was empty. That
  #       broke identity continuity end to end rather than merely being untidy.
  #       auth.users declares cognito_sub UUID NOT NULL UNIQUE and seeds no rows
  #       (V1__auth.sql), and the ETL reader loads its rows from USRSEC keyed on
  #       SEC-USR-ID -- so not one loaded row could find a subject, while the two
  #       identities that did have subjects matched no row. Every baseline id is
  #       now present, so every row the ETL loads has exactly one subject and
  #       every subject belongs to exactly one row.
  #       Assumptions: the names are carried in the baseline's UPPER CASE rather
  #       than title-cased. The ETL writes auth.users.first_name and last_name
  #       from those same record bytes, and the pool and the table have to agree
  #       on the value, so title-casing here would manufacture a mismatch on
  #       every one of the ten.
  #       Assumptions: SEC-USR-PWD is NOT transcribed. The baseline record carries
  #       the literal "PASSWORD" in the clear for all ten, and refusing to carry
  #       it is the one place this migration deliberately declines parity. Each
  #       initial credential is generated during apply and written to Secrets
  #       Manager; no password appears here or in any tfvars file.
  seed_users = [
    {
      user_id     = "ADMIN001"
      given_name  = "MARGARET"
      family_name = "GOLD"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN002"
      given_name  = "RUSSELL"
      family_name = "RUSSELL"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN003"
      given_name  = "RAYMOND"
      family_name = "WHITMORE"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN004"
      given_name  = "EMMANUEL"
      family_name = "CASGRAIN"
      user_type   = "A"
    },
    {
      user_id     = "ADMIN005"
      given_name  = "GRANVILLE"
      family_name = "LACHAPELLE"
      user_type   = "A"
    },
    {
      user_id     = "USER0001"
      given_name  = "LAWRENCE"
      family_name = "THOMAS"
      user_type   = "U"
    },
    {
      user_id     = "USER0002"
      given_name  = "AJITH"
      family_name = "KUMAR"
      user_type   = "U"
    },
    {
      user_id     = "USER0003"
      given_name  = "LAURITZ"
      family_name = "ALME"
      user_type   = "U"
    },
    {
      user_id     = "USER0004"
      given_name  = "AVERARDO"
      family_name = "MAZZI"
      user_type   = "U"
    },
    {
      user_id     = "USER0005"
      given_name  = "LEE"
      family_name = "TING"
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

  name_prefix = var.name_prefix
  environment = var.environment
  kms_key_arn = module.kms.s3_key_arn
  # WHY : Refactoring Rationale: this call used to pass
  #       `object_created_lambda_arn = aws_lambda_function.dataset_retention.arn`,
  #       and the module declared the bucket notification and the invoke permission
  #       that wired it up. Both moved here, because an aws_s3_bucket_notification
  #       is a whole-bucket resource and a reusable module that claims it takes the
  #       bucket's only notification slot away from every consumer. The resources
  #       are declared below over module.s3_datasets.bucket_name, so the behaviour
  #       is unchanged and the ownership is where the function is.
  access_log_bucket_name = module.observability.access_log_bucket_name
  force_destroy          = !var.deletion_protection
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

# WHY : Refactoring Rationale: these two resources were previously declared inside
#       infra/modules/s3-datasets, from an `object_created_lambda_arn` input. They
#       are declared here now because an aws_s3_bucket_notification is a
#       WHOLE-BUCKET resource: a reusable module that declares one claims the
#       bucket's only notification slot for every consumer, and the function being
#       wired up belongs to this root, not to the module. The behaviour is
#       unchanged -- the same function, on the same event, over the same bucket.
# WHY : Assumptions: this hook and the module's own noncurrent-version lifecycle
#       rule are complementary rather than alternative. A lifecycle rule bounds the
#       VERSIONS of one object key, whereas each dataset generation is written under
#       a distinct `<family>/dt=.../gen=.../` key, so S3 cannot see generation six as
#       a version of generation five. Enforcing the five-generation limit
#       (the LIMIT(5) SCRATCH analogue of app/jcl/DEFGDGB.jcl) therefore needs a
#       function that lists prefixes, and it is invoked on object creation rather
#       than from a batch state so that ad-hoc and retry writers are covered too.
resource "aws_lambda_permission" "dataset_retention_from_s3" {
  statement_id  = "AllowDatasetGenerationRetentionFromS3"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.dataset_retention.function_name
  principal     = "s3.amazonaws.com"

  # WHY : Assumptions: both SourceArn and SourceAccount are stated. The bucket ARN
  #       binds invocation to this bucket, and the account condition blocks a
  #       confused-deputy request from a same-named bucket in another account --
  #       neither is redundant, because a bucket name is globally unique but an ARN
  #       alone does not prove which account asked.
  source_arn     = module.s3_datasets.bucket_arn
  source_account = data.aws_caller_identity.current.account_id
}

resource "aws_s3_bucket_notification" "dataset_generations" {
  bucket = module.s3_datasets.bucket_name

  lambda_function {
    lambda_function_arn = aws_lambda_function.dataset_retention.arn
    events              = ["s3:ObjectCreated:*"]
  }

  # WHY : Assumptions: the invoke permission must exist before S3 will validate and
  #       store a notification configuration, so this edge is required even though
  #       neither resource references the other. Without it a first apply fails
  #       although both the function and the bucket already exist.
  depends_on = [aws_lambda_permission.dataset_retention_from_s3]
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
    # WHY : Refactoring Rationale: this parameter and the two below did not exist, and
    #       their absence stopped the service from starting at all rather than
    #       degrading it. authorization-service resolves
    #       carddemo.messaging.reply-queue-allowlist and
    #       carddemo.account-context.base-url from these names with NO default -- the
    #       first because an unset allowlist would otherwise leave every requester
    #       silently unanswered, the second because a default address would decline
    #       every authorization with the not-found reason. A placeholder no root
    #       supplies is an unresolvable placeholder, and Spring aborts context refresh
    #       on one, so the task crash-looped.
    # WHY : Assumptions: the allowlist is the pending-authorization REPLY queue and
    #       nothing else. The property is comma-separated so it can hold more than one
    #       address, and it is given exactly one here because this stack provisions
    #       exactly one reply destination; adding a second requester means adding its
    #       queue to this expression, which is the reviewable change it should be.
    # WHY : Assumptions: authorization-service compares each request's reply-to attribute
    #       against this list by EXACT match before it commits a reply, so the list is the
    #       set of destinations a reply may reach. The attribute is chosen by whoever can put
    #       a message on the request queue and a reply carries a card number and an
    #       authorization outcome, so an unlisted address is answered by no reply at all
    #       rather than by a reply sent somewhere unintended. That makes the composition
    #       self-consistent: the only destination the task role may send to is also the only
    #       one the application will send to. It is one entry rather than a wildcard, for the
    #       reason above.

    "authorization|CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST" = {
      service          = "authorization"
      environment_name = "CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST"
      value            = module.sqs.pauth_reply_queue_url
    }
    # WHY : Assumptions: the address is the INTERNAL load balancer's origin, reached
    #       over HTTPS on the certificate this root issues for that same name, and the
    #       account context is selected by the listener's path rules rather than by a
    #       distinct hostname. That is why the value is an origin with no path: the
    #       client appends its own three paths, and its base-address guard refuses a
    #       value carrying a path precisely so those three constants stay the complete
    #       description of what it requests.
    # WHY : Assumptions: this value and the approved origin below come from ONE
    #       expression, so the guard compares a value against itself in a deployed
    #       environment and cannot be tripped by a transcription difference between two
    #       tfvars entries. The guard's purpose is to refuse a base address changed
    #       WITHOUT a corresponding change here, which a shared expression makes a
    #       visible one-line diff rather than a silent one.
    "authorization|CARDDEMO_ACCOUNT_CONTEXT_BASE_URL" = {
      service          = "authorization"
      environment_name = "CARDDEMO_ACCOUNT_CONTEXT_BASE_URL"
      value            = local.account_context_origin
    }
    "authorization|CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN" = {
      service          = "authorization"
      environment_name = "CARDDEMO_ACCOUNT_CONTEXT_APPROVED_ORIGIN"
      value            = local.account_context_origin

    }
    # WHY : Refactoring Rationale: the transaction context needs the same address, and it was
    #       absent here while that context's own configuration read it with no default. Two of
    #       its four migrated programs resolve a card number through the account context's
    #       cross-reference -- READ-CXACAIX-FILE at app/cbl/COTRN02C.cbl:576 and
    #       app/cbl/COBIL00C.cbl:408 -- and the payment program also reads and updates the
    #       account balance there. Without this entry the task would fail to start on an
    #       unresolvable placeholder, which is the correct failure and the wrong place for it:
    #       the address is known here, from the same expression the authorization entry uses.
    # WHY : Assumptions: it reads local.account_context_origin rather than repeating the
    #       expression, so the two callers cannot be pointed at different addresses by an edit
    #       that updates one entry and not the other.
    "transaction|CARDDEMO_ACCOUNT_CONTEXT_BASE_URL" = {
      service          = "transaction"
      environment_name = "CARDDEMO_ACCOUNT_CONTEXT_BASE_URL"
      value            = local.account_context_origin
    }
    # WHY : Refactoring Rationale: the account context reads the reference context's three
    #       address allow-lists, which originate in app/cpy/CSLKPCDY.cpy at L521, L931, L1013 and
    #       L1073 and which the reference schema owns. app/cbl/COACTUPC.cbl edits all three on
    #       every account update, at lines 1600, 1635, 1643 and 1667, so the migrated update path
    #       needs the address. Its service/AddressValidationService.java is an annotated component
    #       whose only dependency is that lookup, so an absent entry stops the context starting
    #       rather than degrading one screen.
    "account|CARDDEMO_REFERENCE_CONTEXT_BASE_URL" = {
      service          = "account"
      environment_name = "CARDDEMO_REFERENCE_CONTEXT_BASE_URL"
      value            = local.reference_context_origin
    }
    "account|CARDDEMO_REFERENCE_CONTEXT_APPROVED_ORIGIN" = {
      service          = "account"
      environment_name = "CARDDEMO_REFERENCE_CONTEXT_APPROVED_ORIGIN"
      value            = local.reference_context_origin
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

    # WHY : Assumptions: the ALIAS name, not the key identifier. An alias survives
    #       replacement of the key behind it, so a key rotation that replaces the
    #       key material's container does not require this task definition to be
    #       revised; a task still holding a replaced identifier would instead fail
    #       on its next card write.
    # WHY : Trade-offs: the consuming property carries NO default on the service
    #       side, deliberately. carddemo.security.cvv.key-id is read by
    #       com.carddemo.card.service.CardVerificationValueCipher through @Value and
    #       refused when blank, so a deployment that failed to publish this
    #       parameter does not start. The alternative -- a default key identifier --
    #       would let the service start against a key nobody chose, and the wrong
    #       key is not a recoverable mistake once values have been written under it:
    #       an envelope is readable only through the key that produced its data key.
    "card|CARDDEMO_SECURITY_CVV_KEY_ID" = {
      service          = "card"
      environment_name = "CARDDEMO_SECURITY_CVV_KEY_ID"
      value            = module.kms.application_key_alias_name
    }

    # WHY : Assumptions: the account workload's protected-identifier key alias, published for the same
    #       reasons as the card alias above and reading from the SAME application key. The two contexts
    #       protect different columns -- a card verification value there, a national identifier and a
    #       government-issued identifier here -- and they are kept apart not by separate keys but by the
    #       encryption CONTEXT each envelope carries, which is authenticated additional data. One key
    #       therefore means one rotation schedule and one grant to manage, while an envelope moved
    #       between the two contexts still fails its authentication check.
    # WHY : Trade-offs: the consuming property carries NO default on the service side, deliberately.
    #       carddemo.security.customer-identifier.key-id is read by
    #       com.carddemo.account.config.CustomerIdentifierProtectionConfig through @Value and refused
    #       when blank, so a deployment that failed to publish this parameter does not start. A default
    #       would let the service start against a key nobody chose, and the wrong key is not a
    #       recoverable mistake once identifiers have been written under it.
    "account|CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID" = {
      service          = "account"
      environment_name = "CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID"
      value            = module.kms.application_key_alias_name
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

    # WHY : Assumptions: this is the ONE value the ETL cannot derive from the data
    #       it is loading. auth.users declares cognito_sub UUID NOT NULL UNIQUE
    #       (V1__auth.sql) and the 80-byte USRSEC record has no such field, so the
    #       reader has to be told each identity's subject by whoever minted it.
    #       Publishing it here reaches the ETL through the path it already uses:
    #       config.parameter_path("identity", "seed-user-subjects") resolves to
    #       exactly this name, and the data_migration_runtime policy in this root
    #       already grants ssm:GetParameter across this prefix, so the value needs
    #       no new grant.
    #       Alternatives Considered: a Terraform output the operator copies into
    #       the load command. Rejected because it makes a correct load depend on a
    #       manual transcription of ten UUIDs, and a mistyped one is a NOT NULL
    #       UNIQUE violation at best and a row bound to the wrong person at worst.
    #       Trade-offs: JSON in a single String parameter rather than one parameter
    #       per identity. A parameter per identity would be individually readable
    #       but would make the ETL guess which ids exist before it can ask for
    #       them; one document is fetched in one call and enumerates its own keys.
    #       It is a String rather than a SecureString because a subject is not a
    #       credential -- it is the value the identity provider already places in
    #       the sub claim of every token -- so encrypting it would imply a secrecy
    #       the value does not have and does not need.
    "identity/seed-user-subjects" = jsonencode(module.cognito.seed_user_subjects)
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

  # WHY : Assumptions: every database workload except reporting applies its own
  #       Flyway migration, so every one of them except reporting needs a SECOND
  #       database credential. Measured against the tree: auth, account, card,
  #       transaction, reference, batch and authorization each own a
  #       src/main/resources/db/migration directory; reporting-service owns none,
  #       because the cross-schema views it reads are created by
  #       data-migration/sql/V1__reporting_views.sql rather than by the service.
  #       data-migration/sql/V0__schemas_and_roles.sql creates a
  #       carddemo_<context>_migrator role for exactly these seven and none for
  #       reporting, so a set derived by subtracting reporting is the same
  #       inventory the bootstrap SQL and infra/modules/secrets both hold. It is
  #       derived rather than written out so the two lists cannot disagree.
  migration_workload_names = setsubtract(local.database_workload_names, ["reporting"])

  # WHY : Assumptions: the two pairs below are two DIFFERENT database identities
  #       for one task, and that separation is the whole point. The
  #       SPRING_DATASOURCE_* pair carries the runtime role, which the bootstrap
  #       SQL grants USAGE on its schema plus SELECT, INSERT and UPDATE on its
  #       tables -- and explicitly REVOKEs CREATE from, so it can neither create
  #       nor drop nor alter anything, and cannot SET ROLE to the identity that
  #       can. The SPRING_FLYWAY_* pair carries carddemo_<context>_migrator, a
  #       member of the NOLOGIN schema owner WITH INHERIT FALSE: it can
  #       authenticate but owns nothing until the service's
  #       spring.flyway.init-sqls issues SET ROLE carddemo_<context>_owner, after
  #       which every object the migration creates belongs to the owner rather
  #       than to any credential a task holds.
  # WHY : Refactoring Rationale: this map projected only the SPRING_DATASOURCE_*
  #       pair, and the runtime role it names was also the owner of the schema and
  #       of every table Flyway created in it -- so the single long-lived
  #       credential that served every request could also ALTER and DROP the
  #       tables it read. Adding the migrator projection is what lets the
  #       bootstrap SQL reduce the runtime role to named DML, because the DDL a
  #       startup migration still legitimately needs now arrives under a second,
  #       separately granted secret.
  # WHY : Trade-offs: the migrator secret is injected into the SERVING task rather
  #       than into a separate migration task, so a serving container does hold a
  #       credential that can reach DDL authority for its own schema. That is the
  #       accepted cost of the shipped architecture, which applies migrations
  #       in-process before the JPA EntityManagerFactory is built so no task can
  #       start against a schema its own code predates. The residual exposure is
  #       bounded three ways: the credential reaches DDL only for the ONE schema
  #       its context owns, it reaches it only after an explicit SET ROLE, and
  #       INHERIT FALSE means a compromised session that does not issue that
  #       SET ROLE can read and write nothing at all.
  database_secret_sources = {
    for service in local.database_workload_names :
    service => merge(
      {
        SPRING_DATASOURCE_USERNAME = {
          value_from   = "${module.secrets.service_credential_secrets[local.workloads[service].role].arn}:username::"
          resource_arn = module.secrets.service_credential_secrets[local.workloads[service].role].arn
        }
        SPRING_DATASOURCE_PASSWORD = {
          value_from   = "${module.secrets.service_credential_secrets[local.workloads[service].role].arn}:password::"
          resource_arn = module.secrets.service_credential_secrets[local.workloads[service].role].arn
        }
      },
      contains(local.migration_workload_names, service) ? {
        SPRING_FLYWAY_USER = {
          value_from   = "${module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn}:username::"
          resource_arn = module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn
        }
        SPRING_FLYWAY_PASSWORD = {
          value_from   = "${module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn}:password::"
          resource_arn = module.secrets.service_credential_secrets["${local.workloads[service].role}_migrator"].arn
        }
      } : {},
    )
  }

  # WHY : Refactoring Rationale: a `tls_secret_sources` map stood here, projecting
  #       two Secrets Manager ARNs into the certificate and private-key environment
  #       variables of every online workload. It is deleted with the entries it
  #       named: listener material is minted by each task rather than injected, so
  #       there is nothing to project and no task role needs read access to a
  #       certificate secret. infra/modules/ecs-service no longer admits either
  #       name, so re-adding this map would now fail its validation.

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

  # WHY : Assumptions: the name is the one the authorization image reads --
  #       application.yml resolves carddemo.messaging.hmac-key from
  #       ${CARDDEMO_MESSAGING_HMAC_KEY} -- and infra/modules/ecs-service asserts
  #       biconditionally that authorization receives it and that no other service
  #       does, so the name is a contract rather than a convention.
  #       Refactoring Rationale: this family did not exist, and the absence was the
  #       whole defect. Only mask_hmac_secret_sources above was defined, and it goes
  #       to data-migration alone; authorization therefore started with no key, and
  #       the only per-card stable value it held was the card number, which then
  #       became the published FIFO group identity on every reply. The entry reads
  #       from the SCALAR secret this root creates, so value_from is the base ARN
  #       with no JSON-key selector and IAM authorizes exactly the ARN the container
  #       reads -- the same shape as tls_secret_sources above and deliberately not
  #       the composite shape auth_client_secret_sources needs.
  messaging_hmac_secret_sources = {
    CARDDEMO_MESSAGING_HMAC_KEY = {
      value_from   = aws_secretsmanager_secret.messaging_hmac.arn
      resource_arn = aws_secretsmanager_secret.messaging_hmac.arn
    }
  }

  # WHY : Assumptions: the name is the one the card image reads -- application.yml
  #       resolves carddemo.security.card-selector.signing-key from
  #       ${CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY} -- and
  #       infra/modules/ecs-service asserts biconditionally that card receives it and
  #       that no other service does, so the name is a contract rather than a
  #       convention. It reads the SCALAR secret above, so value_from is the base ARN
  #       with no JSON-key selector.
  card_selector_secret_sources = {
    CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY = {
      value_from   = aws_secretsmanager_secret.card_selector.arn
      resource_arn = aws_secretsmanager_secret.card_selector.arn
    }
  }

  # WHY : (1) Assumptions: the name is the one BOTH images read --
  #       carddemo.internal-identity.signing-key resolves from
  #       ${CARDDEMO_INTERNAL_IDENTITY_SIGNING_KEY} in
  #       services/authorization-service/src/main/resources/application.yml and in
  #       services/account-service/src/main/resources/application.yml -- and
  #       infra/modules/ecs-service asserts biconditionally that exactly those two
  #       services receive it, so the name is a contract rather than a convention.
  #       (2) Assumptions: this is the ONLY secret family in this root that goes to
  #       TWO workloads, and that is inherent rather than incidental: it is a
  #       symmetric signing key, so the party that signs and the party that verifies
  #       must hold the same bytes. Every other family here is held by one holder,
  #       which is why each of their gates names a single service.
  #       (3) Assumptions: the entry reads from the SCALAR secret this root creates,
  #       so value_from is the base ARN with no JSON-key selector and IAM authorizes
  #       exactly the ARN each container reads -- the same shape as
  #       tls_secret_sources and deliberately not the composite shape
  #       auth_client_secret_sources needs.
  internal_identity_secret_sources = {
    CARDDEMO_INTERNAL_IDENTITY_SIGNING_KEY = {
      value_from   = aws_secretsmanager_secret.internal_identity.arn
      resource_arn = aws_secretsmanager_secret.internal_identity.arn
    }
  }


  # WHY : (1) Assumptions: this maps each workload to the EXACT queue ARNs its task
  #       role may receive from and send to, taking them from the sqs module's own
  #       service_queue_permissions output rather than composing them here. That
  #       output is the single place the per-service boundary is decided, and its own
  #       description instructs a caller to pass these lists rather than granting
  #       values(queue_arns) -- a wildcard grant would let any consumer read any
  #       queue, which for the pending-authorization request queue means reading
  #       cardholder data it has no part in.
  #       (2) Refactoring Rationale: this local did not exist, and the omission had
  #       become a runtime failure rather than a latent gap.
  #       docs/architecture/messaging-contracts.md recorded that the module boundary
  #       was authored but "not yet composed into a deployable stack", which was
  #       accurate while neither inquiry consumer existed. Both now exist and both
  #       SEND -- account-service and reference-service each publish a reply and can
  #       publish a diagnostic -- so without these grants each would poll
  #       successfully and then fail every reply with an access-denied error, which
  #       presents as an unanswered requester rather than as a permissions problem.
  #       (3) Assumptions: a workload absent from this map receives an empty set,
  #       which grants nothing. That is the correct default: batch and
  #       data-migration put no message on any of these queues, and the three
  #       workloads that do are named explicitly so adding a fourth is a visible
  #       edit rather than an inherited grant.
  sqs_permissions_by_workload = {
    authorization = module.sqs.service_queue_permissions.authorization_service
    account       = module.sqs.service_queue_permissions.account_service
    reference     = module.sqs.service_queue_permissions.reference_service
  }

  secret_sources_by_workload = {
    for service, workload in local.workloads :
    service => merge(
      contains(local.database_workload_names, service) ? local.database_secret_sources[service] : {},
      service == "auth" ? local.auth_client_secret_sources : {},
      service == "data-migration" ? local.mask_hmac_secret_sources : {},
      # WHY : Assumptions: gated on the authorization service by exact name, matching
      #       the biconditional precondition in infra/modules/ecs-service. It is the
      #       only producer on the pending-authorization queue this repository
      #       contains; widening the gate would hand key material to tasks that put
      #       no message on that queue and have no use for it.
      service == "authorization" ? local.messaging_hmac_secret_sources : {},

      # WHY : Assumptions: gated on the card service by exact name. It is the only
      #       context that addresses a row by an opaque selector, and the biconditional
      #       precondition in infra/modules/ecs-service refuses both a card task without
      #       the key and any other task holding it.
      service == "card" ? local.card_selector_secret_sources : {},

      # WHY : Assumptions: gated on THREE services by exact name, and the three are the
      #       whole of the gate. The authorization service and the transaction service each
      #       sign an internal bearer token with this key and the account service verifies
      #       both, so all three must hold it and nothing else may: any further holder could
      #       mint a token the account service accepts for its internal reads, which is
      #       precisely the capability the key exists to withhold. The biconditional
      #       precondition in infra/modules/ecs-service asserts the same three from the other
      #       side, so adding a service here without adding it there fails at plan time
      #       rather than silently widening the trust set.
      # WHY : Refactoring Rationale: transaction joined the set because its account-context
      #       client presents a credential. It reads the ACCOUNT context's cross-reference and
      #       balance, and that context admits no anonymous caller on those addresses, so a
      #       client holding no key would be answered 401 on every add and every payment.
      contains(["authorization", "account", "transaction"], service) ? local.internal_identity_secret_sources : {},

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
      #       that role's rotation may present. The `_clone` suffix is the
      #       alternating-user naming convention the operator-managed rotation
      #       procedure in docs/runbooks/deploy.md follows; nothing in this tree
      #       creates those logins, and the allowlist is inert until one exists,
      #       because carddemo_migration.config consults it only when a secret's
      #       username differs from the role its name was derived from.
      #       Refactoring Rationale: this said the clones were created by "the
      #       credential-rotation function". No such function exists -- neither root
      #       supplies infra/modules/secrets a rotation_lambda_arn, so its
      #       aws_secretsmanager_secret_rotation is created with zero instances --
      #       and describing an absent component as the producer of these names told
      #       an operator to look for automation rather than to run the documented
      #       procedure. Publishing the allowlist ahead of any rotation is still
      #       right: it means a rotation does not additionally require a task-
      #       definition change to be accepted.
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

# WHY : Assumptions: the card workload's only privilege beyond its database
#       connection is envelope encryption of ONE stored value. The baseline holds
#       CARD-CVV-CD as three display digits in the clear (app/cpy/CVACT02Y.cpy),
#       and the migrated column holds an envelope instead;
#       com.carddemo.card.service.CardVerificationValueCipher is the only class
#       that reaches a key in order to produce or open one.
# WHY : Trade-offs: two actions, not five. GenerateDataKey covers the entire write
#       path -- the workload receives one data key in plaintext and enciphered form
#       together and enciphers locally -- and Decrypt covers the entire read path.
#       kms:Encrypt is therefore deliberately ABSENT: a role holding it could use
#       this key as a general-purpose encryption oracle for arbitrary plaintext,
#       which nothing in the card path needs. kms:DescribeKey is absent for the
#       same reason, the workload already holding the alias from its configuration.
# WHY : Alternatives Considered: passing this task role to the kms module as
#       application_key_user_role_arns, so the KEY policy named it directly.
#       REJECTED for consistency with every other key in this root -- none of the
#       four *_key_user_role_arns inputs is wired, because the application key
#       policy already delegates authorization to IAM through its account-root
#       statement, and naming a task role in a key policy would make that policy
#       depend on the ECS service that consumes the key's own alias. Granting from
#       the identity side keeps the key and the workloads that use it independently
#       replaceable.
# WHY : Assumptions: the encryption-context condition is what makes this grant
#       specific rather than merely small. Without it the role could encipher and
#       decipher anything under this key; with it, it can work only with ciphertext
#       produced for the card verification value. The identical condition is
#       asserted a second time by the key policy in infra/modules/kms, so removing
#       it from either side still leaves the other enforcing it.
data "aws_iam_policy_document" "card_runtime" {
  statement {
    sid = "EnvelopeEncryptCardVerificationValue"

    actions = [
      "kms:GenerateDataKey*",
      "kms:Decrypt",
    ]

    resources = [module.kms.application_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:EncryptionContext:carddemo:purpose"
      values   = ["card-cvv"]
    }
  }
}

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

  # Assumptions: the listing is bounded by the `s3:prefix` condition key, which
  #   is the ONLY way a list call can be bounded at all -- its resource is the
  #   bucket, so an object-ARN restriction has no effect on it. Without the
  #   condition the grant enumerates every key in the bucket, which is how a
  #   reporting task could discover the nightly transaction generations.
  # Trade-offs: the match is `StringLike` against each prefix followed by a
  #   wildcard rather than `StringEquals` against the bare prefix. A list call
  #   issued for `reports/transaction-detail/2022-07-18/` is a legitimate
  #   narrower listing inside the workload's own prefix, and `StringEquals`
  #   would refuse it while permitting only the exact top-level listing -- which
  #   would make the grant correct on paper and unusable in practice.
  statement {
    sid       = "ListReportOutputPrefixes"
    actions   = ["s3:ListBucket"]
    resources = [module.s3_datasets.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = [for prefix in local.reporting_object_key_prefixes : "${prefix}*"]
    }
  }

  # Assumptions: the write actions are retained and only their scope is
  #   narrowed, because this role IS the writer of both artifacts. The nightly
  #   chain's GenerateStatements and GenerateReports states run a Fargate task
  #   whose definition is `module.ecs_service["reporting"].task_definition_arn`
  #   -- the very definition the online reporting service runs, which is why
  #   ReportingTaskRunner exists to give that image a task mode. Reassigning the
  #   writes to a different role would leave those two states unable to write
  #   their output.
  # Refactoring Rationale: `s3:GetObject` is REMOVED rather than narrowed.
  #   Neither state reads an object: the report is assembled from the read-only
  #   database views and the statement from the same, and the key layout is
  #   deterministic precisely so a rerun replaces rather than appends. A read
  #   grant that no code path exercises is a capability held for no purpose, and
  #   a future download endpoint should acquire it together with the endpoint so
  #   the two are reviewed as one change.
  statement {
    sid     = "WriteReportOutputs"
    actions = ["s3:PutObject", "s3:AbortMultipartUpload"]
    resources = [
      for prefix in local.reporting_object_key_prefixes :
      "${module.s3_datasets.bucket_arn}/${prefix}*"
    ]
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

# WHY : (1) Refactoring Rationale: the auth workload had NO task-role policy at all,
#       which was correct while the identity a user row is bound to arrived as a
#       request field. It no longer does: services/auth-service now creates the pool
#       account itself and reads the provider-minted subject back, because a caller
#       able to nominate that subject was a caller able to decide which pool identity
#       a new row authenticates as -- the signed group claim wins every authorization
#       decision, so a row bound to an administrator's subject while recording 'U'
#       would carry administrator authority with nothing to contradict it. Those
#       calls need this policy; without it every user creation fails with an access
#       denial at the provider and the workload is the only one whose task role holds
#       no statement.
#       (2) Assumptions: the three actions are exactly the three the service issues,
#       and no fourth is granted speculatively. AdminCreateUser and
#       AdminAddUserToGroup are the two halves of provisioning -- the account must
#       exist before it can join a group, and membership is a separate call rather
#       than an attribute -- and AdminDeleteUser is the compensating withdrawal that
#       removes an account whose row write then failed. Notably absent is
#       AdminSetUserPassword: this service creates no credential, which is the whole
#       point of moving the one at app/cpy/CSUSR01Y.cpy L21 out of the record, so
#       granting the ability to set one would hand this task an authority its code
#       has no call site for.
#       (3) Trade-offs: the resource is the single pool ARN rather than a wildcard, so
#       a second pool in the same account is unreachable from this task even by
#       accident. The cost is that the statement cannot be written before the pool
#       exists, which is why it lives in the root beside the module call rather than
#       inside modules/ecs-service where the role is created.
data "aws_iam_policy_document" "auth_runtime" {
  statement {
    sid = "ProvisionPoolIdentities"
    actions = [
      "cognito-idp:AdminCreateUser",
      "cognito-idp:AdminAddUserToGroup",
      "cognito-idp:AdminDeleteUser",
    ]
    resources = [module.cognito.user_pool_arn]
  }
}

locals {
  task_role_policy_json = {
    auth           = data.aws_iam_policy_document.auth_runtime.json
    card           = data.aws_iam_policy_document.card_runtime.json
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
  ecr_repository_arn    = module.ecr.repository_arns[each.value.repository]
  container_name        = each.value.container_name
  container_port        = module.network.app_container_port
  task_cpu              = var.ecs_task_cpu
  task_memory           = var.ecs_task_memory
  attach_load_balancer  = each.value.online
  create_service        = each.value.online
  desired_count         = each.value.online ? var.ecs_desired_count : 1
  enable_autoscaling    = each.value.online
  min_capacity          = var.ecs_desired_count
  max_capacity          = max(var.ecs_desired_count, var.ecs_desired_count * 2)
  health_check_path     = local.health_check_path
  target_protocol       = "HTTPS"
  log_retention_in_days = var.log_retention_days
  log_group_kms_key_arn = module.kms.s3_key_arn
  environment_variables = local.environment_variables_by_workload[each.key]
  ssm_parameter_arns    = local.runtime_parameter_arns_by_service[each.key]
  secret_arns           = local.secret_sources_by_workload[each.key]
  # WHY : Assumptions: the two grants are looked up with a default of the empty set
  #       rather than indexed, so a workload that puts no message on any queue -- batch
  #       and data-migration -- receives no SQS statement at all instead of failing the
  #       plan on a missing map key.
  sqs_receive_queue_arns        = try(local.sqs_permissions_by_workload[each.key].receive, [])
  sqs_send_queue_arns           = try(local.sqs_permissions_by_workload[each.key].send, [])
  create_task_role_policy       = contains(keys(local.task_role_policy_json), each.key)
  task_role_policy_json         = lookup(local.task_role_policy_json, each.key, null)
  execution_secret_kms_key_arns = length(local.secret_sources_by_workload[each.key]) > 0 ? [module.kms.secrets_key_arn] : []

  # WHY : Assumptions: the boundary is an account-level policy this deployment
  #       does not create, because a boundary a deployment can rewrite bounds
  #       nothing. Passing it to every task means the inline policy this root
  #       composes per service cannot exceed the account ceiling even if the
  #       document is later widened.
  permissions_boundary_arn = var.permissions_boundary_arn

  # WHY : Assumptions: no task may be created before the database bootstrap has run.
  #       Every service resolves a credential at startup and then migrates or queries
  #       immediately, so a task that started first would fail authentication against a
  #       role that did not yet exist -- and on a load-balanced service that presents as
  #       a crash loop rather than as a missing precondition. The edge is on the MODULE
  #       rather than on the task definition because the service, not the definition, is
  #       what starts a container.
  # WHY : Refactoring Rationale: this edge was absent. The dependency reached
  #       module.secrets implicitly through local.secret_sources_by_workload, so a task
  #       could not be created before its secret existed -- but nothing ordered it after
  #       the bootstrap that creates the ROLE that secret authenticates as. Terraform was
  #       free to create the services in parallel with the invocation, which is the
  #       narrow window in which a task starts against a database holding no CardDemo
  #       role at all.
  depends_on = [aws_lambda_invocation.database_bootstrap]
}

# =============================================================================
# Internal name resolution for the load balancer.
#
# Purpose:
#   Make local.internal_service_dns_name resolve to the internal load balancer
#   from inside this VPC, so one CardDemo service can reach another by the name
#   the listener certificate covers.
#
# Refactoring Rationale: nothing resolved that name before, at any layer. The
#   name was already used in two places -- as the listener certificate's identity
#   and as the TLS server name the HTTP API verifies on its private integration --
#   and neither needs DNS: the API Gateway integration targets the listener by ARN
#   and resolves nothing. infra/modules/alb records that absence deliberately and
#   publishes alb_zone_id precisely so a caller that later wants a friendly
#   internal name can build the alias itself, noting that creating the record
#   inside a load-balancer module would leave the record and the service it names
#   owned by different layers. This is that caller, and this is that record.
#
# Assumptions: a PRIVATE hosted zone, associated with this VPC alone. The
#   certificate is a publicly-validated ACM certificate for a name the operator
#   owns, so the operator's public DNS may well also answer for it -- and inside
#   this VPC that answer must not be used, because the load balancer is internal
#   and has no publicly routable address. A private zone for exactly this name
#   shadows the public answer for exactly this name and for nothing else: the zone
#   apex IS the hostname, so no sibling name in the same parent domain is
#   affected.
#
# Alternatives Considered: pointing the base URL at the load balancer's
#   AWS-assigned name instead, which resolves inside the VPC with no zone at all.
#   Rejected because the listener certificate covers the operator's name and not
#   the assigned one, so every call would fail hostname verification -- and the
#   remedy for that would be to stop verifying the hostname, which is the control
#   being relied on.
#
# Alternatives Considered: an optional hosted-zone id input, so a root could
#   supply a zone it already owns and this configuration would create only the
#   record. Rejected because an optional input defaulting to null makes the record
#   optional in practice, and an absent record is exactly the defect being fixed:
#   it would surface as every internal lookup failing to connect, in an
#   environment that planned and applied cleanly.
# =============================================================================

resource "aws_route53_zone" "internal_service" {
  name = local.internal_service_dns_name

  # WHY : Assumptions: the comment is what distinguishes this zone in a console
  #       listing from the operator's public zone for the same name, which is the
  #       one place the two are genuinely easy to confuse.
  comment = "Private zone resolving ${local.internal_service_dns_name} to the internal CardDemo load balancer inside the ${var.environment} VPC."

  vpc {
    vpc_id = module.network.vpc_id
  }

  tags = { Name = "${var.name_prefix}-internal-${var.environment}" }
}

resource "aws_route53_record" "internal_service" {
  zone_id = aws_route53_zone.internal_service.zone_id
  name    = local.internal_service_dns_name

  # WHY : Assumptions: an A record with an alias target, not a CNAME. The record is
  #       at the zone APEX, and a CNAME at an apex is prohibited -- the apex must
  #       also carry the zone's own SOA and NS records, which a CNAME may not
  #       coexist with. An alias is the mechanism that answers with an address at an
  #       apex, and it costs nothing per query.
  type = "A"

  alias {
    name    = module.alb.alb_dns_name
    zone_id = module.alb.alb_zone_id

    # WHY : Trade-offs: target health IS evaluated. With it, a caller resolving this
    #       name during a load-balancer outage receives no answer rather than an
    #       address that refuses connections, so the failure arrives as a resolution
    #       failure at once instead of as a connection timeout after the client's
    #       connect budget. The cost is that a resolution failure is a less obvious
    #       diagnostic than a refused connection; the gain is that the pending
    #       authorization consumer, whose calls run inside a transaction holding a
    #       pooled connection, fails fast rather than holding that connection for
    #       the whole timeout.
    evaluate_target_health = true
  }
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
  task_security_group_id             = module.network.app_security_group_id

  # WHY : Assumptions: every role the state machine may run a task AS is
  #       enumerated -- the task role and the task EXECUTION role of each of the
  #       three task definitions above, six entries for three images. The module
  #       turns the list into the Resource of one iam:PassRole statement, so an
  #       omitted entry is not a narrower grant but a run-task that fails with an
  #       access-denied error naming iam:PassRole rather than the missing role.
  pass_role_arns = [
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

  # WHY : Assumptions: the same parameter the two functions already receive
  #       through their own environment variables is named again here, so the
  #       state machine's definition records which flag its quiesce bracket
  #       toggles instead of leaving that discoverable only from the function
  #       resources above. One owner, referenced twice, rather than two
  #       independently maintained spellings.
  # WHY : Assumptions: the value the module puts in each invocation payload is a
  #       CROSS-CHECK and not a redirect. The handler writes the parameter its own
  #       environment names and REFUSES an invocation naming any other, so the two
  #       references above cannot silently disagree -- a root that wired a
  #       different parameter here than into the functions fails the invocation
  #       instead of reporting a resume it never performed. It is therefore not a
  #       second flag and there is no multi-flag capability to configure.
  read_only_flag_parameter_name = aws_ssm_parameter.online_writes_enabled.name

  notification_topic_arn = module.observability.notification_topic_arn
  dataset_bucket_name    = module.s3_datasets.bucket_name
  log_retention_days     = var.log_retention_days

  # WHY : Assumptions: the S3 customer-managed key is the one this stack uses for
  #       CloudWatch log groups as well, so the state machines' two execution log
  #       groups are encrypted under a project-owned key rather than CloudWatch's
  #       service-managed one. The module's input is nullable and defaults to null
  #       precisely so a module can be applied without a key; this root supplies
  #       one, because encryption at rest is one of the properties the migration
  #       adds over a baseline whose every CICS file ran RECOVERY(NONE) JOURNAL(NO).
  log_group_kms_key_arn = module.kms.s3_key_arn
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
  database_connection_threshold   = local.database_connection_budget
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

  # WHY : Assumptions: rotation_lambda_function_names is deliberately left at its
  #       empty default, so no rotation-Errors alarm is created. There is no
  #       rotation function in this stack to alarm on: infra/modules/secrets
  #       implements no rotation and this root supplies none, and the observability
  #       module's own input contract states that an empty set is the correct value
  #       when rotation is not provisioned in the composed root. Naming a function
  #       that does not exist would create an alarm permanently in INSUFFICIENT_DATA,
  #       which is indistinguishable from a control that is running cleanly.
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
