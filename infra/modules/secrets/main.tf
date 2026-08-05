# =============================================================================
# infra/modules/secrets/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provision the AWS Secrets Manager entries holding this stack's database
#   credentials -- one per service database role -- and rotate those values
#   against Aurora through a module-owned Lambda and RDS Data API. Aurora owns
#   its separate RDS-managed master credential.
#
#   The generation step is the point of this module rather than a detail of it.
#   No variable in infra/modules/secrets/variables.tf accepts a password, a
#   secret string, or a path to one, so the only value this file is able to
#   write into a secret is one an ephemeral random_password produced during the
#   run. The write-only provider argument sends it to Secrets Manager without
#   recording it in Terraform state.
#
# Ownership boundary -- read this before consolidating anything:
#   This module owns DATABASE and application credentials. It does NOT own the
#   Cognito seed-user secrets. infra/modules/cognito creates the user pool, the
#   carddemo-admin and carddemo-user groups, and the seed users, and writes each
#   seed user's generated initial password into a Secrets Manager entry of its
#   own -- the notes on that module's `secrets_kms_key_arn` and
#   `recovery_window_in_days` inputs name `aws_secretsmanager_secret` arguments
#   belonging to ITS resources, not to the ones below. Both sets of secrets land
#   in the same account and region under the same name prefix, so they read as
#   one concern. They are not one concern: two modules declaring the same secret
#   would each track it in their own state, so every apply of either would
#   overwrite the other's value and the stored credential would alternate with
#   whichever module ran last. Keep them separate.
#
#   This directory is a reusable MODULE, not a root. It is invoked as
#   `source = "../../modules/secrets"` from infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf, and those roots own the provider and backend
#   configuration; infra/modules/secrets/versions.tf records that decision and
#   the obligations it creates here.
#
# Parameters -- every input this file consumes, all declared in variables.tf:
#   name_prefix, environment ........... composed into every secret name
#   kms_key_arn ........................ customer-managed key each value is
#                                        encrypted under
#   aurora_cluster_arn,
#   aurora_master_secret_arn,
#   aurora_host, aurora_port,
#   aurora_database_name ............... live cluster rotation contract
#   password_length .................... character count of each generated value
#   service_credential_names ........... one credential is created per element
#   recovery_window_in_days ............ deletion-recovery behaviour
#   initial_secret_version ............. write-only initial-value revision
#   rotation_automatically_after_days .. mandatory rotation interval
#   rotation_log_retention_in_days ..... Lambda log retention
#   rotation_permissions_boundary_arn .. execution-role maximum permissions
#   tags ............................... applied to every secret created here
#
# Return values:
#   None declared here. Service secret and rotation Lambda identifiers are
#   published by
#   infra/modules/secrets/outputs.tf, which reads the resources below and gives
#   each output its own `description`.
#
# Errors / Exceptions:
#   Misconfiguration is caught at `plan` by the validation blocks in
#   variables.tf, so it creates nothing. Apply-time failures remain possible
#   when a deleted name is reserved, a permissions boundary blocks a required
#   action, or Data API cannot reach the target cluster.
#
# WHY (decisions recorded once here because they govern the whole file):
#   - Refactoring Rationale: what this replaces is a credential store that kept
#     its passwords in the clear, and the evidence is specific.
#     `05 SEC-USR-PWD PIC X(08).` at app/cpy/CSUSR01Y.cpy:L21 declares an
#     eight-character password inside the eighty-byte USRSEC record;
#     app/cbl/COSGN00C.cbl:L223 compares it directly
#     (`IF SEC-USR-PWD = WS-USER-PWD`), so the cleartext had to be readable at
#     sign-on; the file holding it is defined with JOURNAL(NO) and
#     RECOVERY(NONE) FWDRECOVLOG(NO) at app/csd/CARDDEMO.CSD:L88-L96, so there
#     was no encryption at rest; and app/jcl/DUSRSECJ.jcl:L35-L44 seeds it from
#     an in-stream card deck of ten records committed to version control, every
#     one of them carrying the same eight-character password literal, five typed
#     as administrators and five as ordinary users. The migrated system does not
#     carry that field forward AT ALL -- it is the one place where behavioural
#     parity is deliberately declined, and the divergence is registered in
#     docs/architecture/cobol-to-service-traceability.md rather than applied
#     silently. All four files are REFERENCE-ONLY: they are cited here and never
#     modified, and the defect is deliberately not "fixed" in COBOL. The
#     mainframe path continues to run exactly as it does; this module adds a
#     second path rather than removing that one.
#   - Alternatives Considered: accepting a credential as an input variable --
#     `master_password`, `initial_password`, `secrets_map`; the shape is not the
#     point -- and letting a caller supply it. Rejected, because a variable has
#     to be given a value somewhere, and the only places a Terraform root can
#     supply one from are a committed tfvars file, a committed default, or an
#     environment variable that a CI definition then has to carry. Each of the
#     three relocates the literal rather than removing it, and each turns the
#     no-secrets constraint back into a matter of reviewer discipline.
#     Generating the value at apply time is what makes it structural: a value
#     that cannot be supplied cannot be committed. The same reasoning is
#     recorded from the input side in variables.tf.
#   - Refactoring Rationale: ephemeral generation plus secret_string_wo replaces
#     the former random_password resource, whose result was retained in every
#     historical state version. The stable initial_secret_version is what keeps
#     an unrelated apply from rewriting the stored value.
# =============================================================================

# -----------------------------------------------------------------------------
# Composed names, tags and rotation identity
# -----------------------------------------------------------------------------

locals {
  # WHY : Assumptions: the shape `<prefix>/<environment>/aurora/<leaf>` is not
  #       this file's invention and cannot be chosen freely here.
  #       data-migration/src/carddemo_migration/config.py derives exactly that
  #       shape -- its `database_secret_name()` returns
  #       `parameter_path("aurora", role_for_schema(schema))`, and
  #       `parameter_path()` joins the prefix, the environment name and the
  #       given segments with single separators -- so an ETL load resolves a
  #       schema owner's credential from `<prefix>/<environment>/aurora/<role>`.
  #       Composing the name the same way here is what makes the two artifacts
  #       agree structurally instead of by memory.
  #       The `aurora` segment is deliberately SHARED with the three non-secret
  #       Parameter Store values that module resolves -- host, port and database.
  #       A credential authenticates against exactly the endpoint those name, so
  #       one grouping means a prefix or environment change moves the endpoint
  #       and the credential as a unit and the two cannot come to describe
  #       different deployments. Sharing a path weakens nothing, because
  #       Parameter Store and Secrets Manager are separate services with
  #       separate permissions: a path is a name, not an access grant.
  # WHY : Trade-offs: NO leading slash, even though the Parameter Store prefix
  #       that ETL module resolves carries one. Secrets Manager accepts `/`
  #       INSIDE a name, but a lookup whose `SecretId` BEGINS with `/` is
  #       rejected as an invalid identifier, so a leading slash here would
  #       create every secret successfully and then fail every read by name --
  #       an apply that reports success against a stack whose consumers cannot
  #       resolve a credential, which is the worst available failure shape.
  #       var.name_prefix's own charset validation already refuses a leading
  #       slash, so emitting one would additionally contradict this module's
  #       declared input contract.
  # WHY : Assumptions: no account-id suffix, unlike the bootstrap state bucket.
  #       An S3 bucket name has to be unique across every account in the
  #       partition, which is why that one carries the suffix; a Secrets Manager
  #       name is unique only per account and per region, so a dev and a prod
  #       instantiation are already separated by the environment segment. The
  #       absence is a consequence of the service's namespace rather than an
  #       omission, and it is stated because the asymmetry between the two
  #       modules otherwise reads as one.
  secret_name_root = "${var.name_prefix}/${var.environment}/aurora"

  # WHY : Trade-offs: a map keyed by role name rather than a plain list, so
  #       every per-service resource below can be keyed identically and
  #       `random_password.service[each.key]` resolves without a second lookup
  #       table. Keying on the role name also means adding or removing a role
  #       moves only that role's resources; a list would key on POSITION, so
  #       removing an element would renumber every later one and Terraform would
  #       plan to destroy and recreate secrets that did not change -- taking
  #       their stored credentials with them.
  service_secret_names = {
    for role_name in var.service_credential_names :
    role_name => "${local.secret_name_root}/${role_name}"
  }

  rotation_function_name  = "${var.name_prefix}-${var.environment}-database-credential-rotation"
  rotation_log_group_name = "/aws/lambda/${local.rotation_function_name}"

  # WHY : Trade-offs: one classification tag is worth its cost because Secrets
  #       Manager offers no other grouping. A task role that needs "every
  #       database credential" must either enumerate ARNs -- which then needs
  #       editing every time the role inventory changes -- or match a tag
  #       condition, and only publishing the tag makes the second option
  #       available. It is also what separates these entries from the
  #       infra/modules/cognito seed-user secrets described in the header, which
  #       share this account, this region and this name prefix. This module
  #       grants no policy itself and deliberately does not; see the rejections
  #       at the foot of this file.
  module_tags = {
    SecretCategory = "database-credential"
  }

  # WHY : Assumptions: the TLS material lives under its own name root rather than
  #       beside the database credentials, because the two have different owners
  #       and different lifecycles -- a credential is replaced by the rotation
  #       function, a certificate by whoever issued it -- and an operator listing
  #       one prefix should not have to filter the other out.
  #       Trade-offs: the pair is conditional rather than required. A root that has
  #       no certificate to hand can still apply this module and reach a running
  #       database tier, which is what makes an incremental stand-up possible;
  #       supplying exactly one half is refused by a validation on the inputs
  #       instead, since half a pair configures nothing.
  tls_secret_name_root = "${var.name_prefix}/${var.environment}/tls"

  service_tls_certificate_secret_name = "${local.tls_secret_name_root}/certificate"
  service_tls_private_key_secret_name = "${local.tls_secret_name_root}/private-key"

  tls_module_tags = {
    SecretCategory = "service-tls-material"
  }

  create_service_tls_secrets = var.service_tls_certificate != null && var.service_tls_private_key != null
}

# -----------------------------------------------------------------------------
# Per-service database role credentials
#
# One credential per element of var.service_credential_names -- the eight roles
# data-migration/sql/V0__schemas_and_roles.sql creates: the seven owning one
# schema per bounded context (auth, account, card, ledger, reference, batch and
# authorization) plus carddemo_reporting, the read-only role the reporting
# service connects as. That script is the source of truth for the names, and the
# secret entry each credential is written to has to match a role name character
# for character, which is why the inventory arrives as an input validated
# against a closed list rather than being restated here.
#
# WHY : Alternatives Considered: ONE secret holding every service credential as
#       a single JSON document, which is fewer resources and one name to
#       remember. Rejected on least privilege. A task role can be granted
#       `GetSecretValue` on a secret or not at all -- there is no way to scope
#       the grant to one key inside a document -- so a shared secret would give
#       every service read access to every other service's credential. The
#       target posture is one narrowly scoped grant per task role, and the
#       schema privileges behind these roles are themselves deliberately
#       separated per bounded context, so a shared secret would hand back at the
#       credential layer exactly the separation the database layer was built to
#       enforce. One secret per role is what keeps a grant expressible.
# WHY : Trade-offs: one secret per role costs eight resources and is what lets
#       each ECS execution role receive one exact GetSecretValue resource rather
#       than a document containing every bounded context's credential.
# -----------------------------------------------------------------------------

ephemeral "random_password" "service" {
  # WHY : Assumptions: keyed on the role name, matching
  #       `local.service_secret_names`, so the two families line up and
  #       `ephemeral.random_password.service[each.key]` resolves in the version resource
  #       below without a second mapping. The set is iterated directly rather
  #       than through that map because a password needs the role's identity for
  #       keying only, not its composed secret name.
  for_each = var.service_credential_names

  length  = var.password_length
  special = false
}

resource "aws_secretsmanager_secret" "service" {
  # WHY : Trade-offs: iterated over the composed-name map rather than the raw
  #       set, so `each.key` is the role and `each.value` is its secret name and
  #       neither has to be rebuilt here. Composing the name inline instead
  #       would put the convention in two places, and the convention is exactly
  #       what the ETL module resolves independently -- so a second copy is a
  #       second thing that can drift out of agreement with it.
  for_each = local.service_secret_names

  name = each.value

  # WHY : Assumptions: the role name is safe to state in a description while the
  #       credential is not, and the distinction is the same one variables.tf
  #       draws between an identifier and a credential. The role name is already
  #       public in data-migration/sql/V0__schemas_and_roles.sql and has to be
  #       legible in a connection string and in a runbook; naming it here is what
  #       lets an operator tell which of eight near-identical entries they are
  #       looking at, using a field that `DescribeSecret` returns unencrypted.
  description = "Login credential for the ${each.key} database role in the ${var.environment} CardDemo stack. Initial value is generated ephemerally and never written to Terraform state; subsequent values are managed by the rotation Lambda."

  # WHY : Refactoring Rationale: every service credential is encrypted under
  #       the dedicated Secrets Manager CMK. The baseline stored credentials in
  #       an unencrypted VSAM record; falling back to aws/secretsmanager would
  #       encrypt but would surrender the project-owned key policy and audit
  #       boundary this migration explicitly requires.
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = merge(var.tags, local.module_tags)
}

data "archive_file" "rotation" {
  type        = "zip"
  source_file = "${path.module}/rotation_lambda.py"
  output_path = "${path.module}/.terraform/database-credential-rotation.zip"
}

data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "rotation_assume_role" {
  statement {
    sid     = "AllowLambdaAssumeRole"
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }

  }
}

data "aws_iam_policy_document" "rotation" {
  statement {
    sid    = "WriteFunctionLogs"
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
    ]
    resources = ["${aws_cloudwatch_log_group.rotation.arn}:*"]
  }

  statement {
    sid    = "ManageServiceSecretVersions"
    effect = "Allow"
    actions = [
      "secretsmanager:DescribeSecret",
      "secretsmanager:GetSecretValue",
      "secretsmanager:PutSecretValue",
      "secretsmanager:UpdateSecretVersionStage",
    ]
    resources = [for secret in aws_secretsmanager_secret.service : secret.arn]
  }

  statement {
    sid       = "ReadAuroraMasterSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue"]
    resources = [var.aurora_master_secret_arn]
  }

  # WHY : Assumptions: GetRandomPassword has no resource-level permission in
  #       Secrets Manager. The wildcard is limited to this one non-resource
  #       action; every read and write statement above remains ARN-scoped.
  statement {
    sid       = "GeneratePendingPassword"
    effect    = "Allow"
    actions   = ["secretsmanager:GetRandomPassword"]
    resources = ["*"]
  }

  statement {
    sid    = "ApplyCredentialThroughDataApi"
    effect = "Allow"
    actions = [
      "rds-data:BeginTransaction",
      "rds-data:CommitTransaction",
      "rds-data:ExecuteStatement",
      "rds-data:RollbackTransaction",
    ]
    resources = [var.aurora_cluster_arn]
  }

  statement {
    sid    = "UseSecretsManagerKey"
    effect = "Allow"
    actions = [
      "kms:Decrypt",
      "kms:DescribeKey",
      "kms:GenerateDataKey",
    ]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${data.aws_region.current.region}.amazonaws.com"]
    }
  }
}

resource "aws_cloudwatch_log_group" "rotation" {
  name              = local.rotation_log_group_name
  retention_in_days = var.rotation_log_retention_in_days
  kms_key_id        = var.rotation_log_kms_key_arn
  tags              = merge(var.tags, local.module_tags)
}

resource "aws_iam_role" "rotation" {
  name                 = local.rotation_function_name
  description          = "Applies rotating CardDemo service credentials through the Aurora Data API."
  assume_role_policy   = data.aws_iam_policy_document.rotation_assume_role.json
  permissions_boundary = var.rotation_permissions_boundary_arn
  tags                 = merge(var.tags, local.module_tags)

  lifecycle {
    precondition {
      condition     = split(":", var.rotation_permissions_boundary_arn)[4] == data.aws_caller_identity.current.account_id
      error_message = "rotation_permissions_boundary_arn must belong to the deployment AWS account."
    }
  }
}

resource "aws_iam_role_policy" "rotation" {
  name   = local.rotation_function_name
  role   = aws_iam_role.rotation.id
  policy = data.aws_iam_policy_document.rotation.json
}

resource "aws_lambda_function" "rotation" {
  function_name = local.rotation_function_name
  description   = "Alternating-user rotation for the eight CardDemo Aurora service roles."
  role          = aws_iam_role.rotation.arn
  runtime       = "python3.13"
  handler       = "rotation_lambda.lambda_handler"
  filename      = data.archive_file.rotation.output_path

  source_code_hash               = data.archive_file.rotation.output_base64sha256
  timeout                        = 60
  memory_size                    = 256
  reserved_concurrent_executions = length(var.service_credential_names)

  environment {
    variables = {
      AURORA_CLUSTER_ARN       = var.aurora_cluster_arn
      AURORA_MASTER_SECRET_ARN = var.aurora_master_secret_arn
      AURORA_HOST              = var.aurora_host
      AURORA_PORT              = tostring(var.aurora_port)
      AURORA_DATABASE          = var.aurora_database_name
      SECRET_ROLE_MAP = jsonencode({
        for role_name, secret in aws_secretsmanager_secret.service : secret.arn => role_name
      })
      LOG_LEVEL = "INFO"
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.rotation,
    aws_iam_role_policy.rotation,
  ]

  tags = merge(var.tags, local.module_tags)
}

resource "aws_lambda_permission" "secrets_manager" {
  for_each = aws_secretsmanager_secret.service

  statement_id   = "AllowSecretsManager-${replace(each.key, "_", "-")}"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.rotation.function_name
  principal      = "secretsmanager.amazonaws.com"
  source_account = data.aws_caller_identity.current.account_id
  source_arn     = each.value.arn
}

resource "aws_secretsmanager_secret_version" "service" {
  for_each = local.service_secret_names

  secret_id = aws_secretsmanager_secret.service[each.key].id

  # WHY : Refactoring Rationale: the write-only argument and ephemeral password
  #       form one control. The value is available while the provider sends it
  #       to Secrets Manager and is absent from state afterwards; the stable
  #       version number prevents an unrelated plan from replacing it merely
  #       because the ephemeral generator produces fresh bytes.
  secret_string_wo = jsonencode({
    engine    = "aurora-postgresql"
    host      = var.aurora_host
    port      = var.aurora_port
    dbname    = var.aurora_database_name
    username  = each.key
    password  = ephemeral.random_password.service[each.key].result
    masterarn = var.aurora_master_secret_arn
  })
  secret_string_wo_version = var.initial_secret_version
}

resource "aws_secretsmanager_secret_rotation" "service" {
  for_each = local.service_secret_names

  secret_id           = aws_secretsmanager_secret.service[each.key].id
  rotation_lambda_arn = aws_lambda_function.rotation.arn

  # WHY : Refactoring Rationale: the initial rotation is the automated password
  #       application bridge. The Lambda can create an absent base role and its
  #       bounded clone with non-administrative attributes, so first apply does
  #       not depend on an operator typing ALTER ROLE or on the schema bootstrap
  #       having already run. V0 then converges ownership and grants onto the
  #       base role; the active clone inherits them through explicit membership.
  rotate_immediately = true

  rotation_rules {
    automatically_after_days = var.rotation_automatically_after_days
  }

  depends_on = [
    aws_lambda_permission.secrets_manager,
    aws_secretsmanager_secret_version.service,
  ]
}

# =============================================================================
# Ownership exclusions.
# Assumptions: environment roots that instantiate this module own provider,
#   backend, region, KMS wiring, and sibling composition.
# Refactoring Rationale: IAM grants stay with consuming task roles, while this
#   module creates only credential values and Secrets Manager resources.
# Alternatives Considered: KMS data lookups, hard-coded/example credentials,
#   provisioners, and local command hooks are excluded because they duplicate
#   naming authority or risk exposing generated values in source or apply logs.
# =============================================================================

# =============================================================================
# Service TLS material
# -----------------------------------------------------------------------------
# WHY : Assumptions: these belong in this module rather than in an environment
#       root because a root that creates a secret directly has to reproduce this
#       module's naming, key, recovery-window and tagging decisions, and a second
#       copy of those decisions is a second place for them to drift. Two scalar
#       secrets rather than one JSON document because the ECS `valueFrom`
#       reference for a scalar needs no JSON-key suffix, and the task definition
#       injects the two values into two separate environment variables.
#       Trade-offs: unlike the service credentials below, these follow their
#       inputs -- there is no ignore_changes rule -- because the authority for
#       this material is the caller that issued the certificate, not a rotation
#       function. Renewing a certificate is therefore an ordinary apply.
# =============================================================================
resource "aws_secretsmanager_secret" "service_tls_certificate" {
  # WHY : Assumptions: the TLS pair is created only when a caller supplies imported
  #       material. An environment root that issues its own certificate -- both roots
  #       in this package do, from the `tls` provider -- owns that secret itself and
  #       passes the resulting reference to infra/modules/ecs-service directly, so
  #       creating a second, empty entry here would reserve a name, cost a KMS-encrypted
  #       version and give a task role a second plausible ARN to be pointed at. The
  #       count is what lets one module serve both shapes without either carrying the
  #       other's resources.
  # WHY : Alternatives Considered: making the two inputs mandatory, so this module is
  #       always the owner. Rejected because it would force every root to hold PEM
  #       material as an input variable, and a root that can mint its own has no source
  #       for one -- the requirement is that service TLS material live in Secrets
  #       Manager under the secrets key, not that this module be the thing that puts it
  #       there.
  count = local.create_service_tls_secrets ? 1 : 0

  name        = local.service_tls_certificate_secret_name
  description = "PEM certificate presented by CardDemo service HTTPS listeners in the ${var.environment} environment. Imported from the environment certificate authority and injected into ECS tasks as a scalar secret."

  # WHY : Refactoring Rationale: every web service enables Spring TLS and refuses
  #       to start without certificate material. Storing it under the Secrets
  #       Manager CMK gives the mandatory runtime value an IaC owner instead of
  #       leaving deployment to an undocumented manual injection.
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = merge(var.tags, local.tls_module_tags)
}

resource "aws_secretsmanager_secret" "service_tls_private_key" {
  count = local.create_service_tls_secrets ? 1 : 0

  name        = local.service_tls_private_key_secret_name
  description = "PEM private key paired with the CardDemo service HTTPS certificate in the ${var.environment} environment. Imported through a sensitive input and injected into ECS tasks as a scalar secret."

  # WHY : Assumptions: the private key uses the same CMK and lifecycle as its
  #       certificate, but remains a separate entry so task IAM can enumerate
  #       exactly the two scalar ARNs and Spring can consume each independently.
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = merge(var.tags, local.tls_module_tags)
}

resource "aws_secretsmanager_secret_version" "service_tls_certificate" {
  count = local.create_service_tls_secrets ? 1 : 0

  secret_id = aws_secretsmanager_secret.service_tls_certificate[0].id

  # WHY : Assumptions: a scalar value is deliberate. ECS can inject this base
  #       ARN directly into SERVER_SSL_CERTIFICATE, while a JSON document would
  #       require a key-qualified reference and a different base ARN for IAM.
  # WHY : Trade-offs: no ignore_changes rule appears here. Unlike a rotated
  #       database password, imported certificate material remains the source of
  #       truth, so replacing the input during renewal must create a new secret
  #       version rather than preserve stale material.
  secret_string = var.service_tls_certificate
}

resource "aws_secretsmanager_secret_version" "service_tls_private_key" {
  count = local.create_service_tls_secrets ? 1 : 0

  secret_id = aws_secretsmanager_secret.service_tls_private_key[0].id

  # WHY : Assumptions: the imported key is the renewal authority, so this version
  #       intentionally follows input changes. Adding ignore_changes here would
  #       leave a renewed certificate paired with an old key and make every
  #       listener fail during startup.
  secret_string = var.service_tls_private_key
}
