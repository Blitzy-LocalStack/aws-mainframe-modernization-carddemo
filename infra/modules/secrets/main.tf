# =============================================================================
# infra/modules/secrets/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provision the AWS Secrets Manager entries holding this stack's database
#   credentials -- one per service database role -- and, when the calling root
#   supplies a rotation function of its own, attach a rotation schedule to each
#   of them. Aurora owns its separate RDS-managed master credential.
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
#   database_master_username ........... login NAME of the cluster master role,
#                                        recorded in each credential document
#   password_length .................... character count of each generated value
#   service_credential_names ........... one credential is created per element
#   recovery_window_in_days ............ deletion-recovery behaviour
#   rotation_lambda_arn ................ operator-supplied rotation function, or
#                                        null for no rotation
#   rotation_automatically_after_days .. rotation interval, paired with the above
#   tags ............................... applied to every secret created here
#
# Return values:
#   None declared here. The service secret identifiers are published by
#   infra/modules/secrets/outputs.tf, which reads the resources below and gives
#   each output its own `description`.
#
# Errors / Exceptions:
#   Misconfiguration is caught at `plan` by the validation blocks in
#   variables.tf, so it creates nothing. Apply-time failures remain possible
#   when a deleted name is still reserved by an unexpired recovery window, or
#   when the KMS key policy does not admit Secrets Manager.
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
#     historical state version.
#   - Alternatives Considered: this module previously PACKAGED AND CREATED a
#     Python rotation Lambda, with its own execution role, inline policy, log
#     group, invoke permission and source archive. That was removed. Rotation of
#     a SECRET VALUE is not this module's remit -- the only rotation this
#     infrastructure package owns anywhere is KMS KEY rotation, which belongs to
#     infra/modules/kms and its four customer-managed keys -- and owning a
#     function here dragged eight cross-module coordinates into the input
#     contract (cluster ARN, RDS-managed master secret ARN, writer endpoint,
#     port, database name, a log-group key, a log retention value and an IAM
#     permissions boundary) plus a third Terraform provider to build the
#     deployment package. A root that wants rotation now supplies the function's
#     ARN through `rotation_lambda_arn`, which is where the boundary of this
#     module's remit actually falls: an alternating-user rotation function needs
#     Data API access to the cluster and read access to the RDS-managed master
#     secret, and the ROOT holds both.
#   - Alternatives Considered: two further inputs, a PEM certificate and its
#     private key, were also removed. They existed so this module could copy the
#     pair into two Secrets Manager entries, which made a reusable module a
#     second custodian of private-key material; `sensitive = true` on them
#     changed only how a plan RENDERED the value, not whether a tfvars file or a
#     state file could hold it. The material now stops at aws_acm_certificate in
#     the calling root -- the service purpose-built to custody a private key,
#     which accepts it once and never re-exports it -- so there is no second copy
#     for this module to hold. Relying on both roots continuing to pass null was
#     rejected as a convention rather than a control.
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

  # WHY : Assumptions: a rotation schedule is attached only when the calling root
  #       supplies BOTH a function ARN and an interval. This module creates no
  #       rotation function of its own -- see the header -- so an interval with no
  #       function would configure nothing and a function with no interval is a
  #       schedule Secrets Manager refuses. variables.tf refuses the half-supplied
  #       case at plan time, so this expression only has to distinguish "both" from
  #       "neither".
  configure_rotation = var.rotation_lambda_arn != null && var.rotation_automatically_after_days != null

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

resource "aws_secretsmanager_secret_version" "service" {
  for_each = local.service_secret_names

  secret_id = aws_secretsmanager_secret.service[each.key].id

  # WHY : Refactoring Rationale: the write-only argument and ephemeral password
  #       form one control. The value is available while the provider sends it
  #       to Secrets Manager and is absent from state afterwards; the stable
  #       version number prevents an unrelated plan from replacing it merely
  #       because the ephemeral generator produces fresh bytes.
  # WHY : Assumptions: the document carries the role's own login name, the
  #       generated password and the master role's NAME, and deliberately NOT the
  #       cluster's connection coordinates. The writer endpoint, listener port and
  #       database name are non-secret, and infra/modules/aurora-postgresql already
  #       publishes them to Parameter Store under the same
  #       `<prefix>/<environment>/aurora` path this module composes its secret
  #       names from -- so a consumer reads the coordinates from Parameter Store
  #       and only the credential from Secrets Manager. Embedding them here as well
  #       would make this module a second copy of values another module owns, and a
  #       second copy is what comes to describe a different deployment.
  #       masteruser records the ESCALATION IDENTITY an operator-supplied rotation
  #       function needs -- a login name, never its password, which RDS generates
  #       and owns.
  secret_string_wo = jsonencode({
    engine     = "aurora-postgresql"
    username   = each.key
    password   = ephemeral.random_password.service[each.key].result
    masteruser = var.database_master_username
  })

  # WHY : Trade-offs: the write-only version is pinned to the literal 1 rather
  #       than taken from an input. It exists to stop an unrelated plan rewriting
  #       a stored credential merely because the ephemeral generator produced
  #       fresh bytes, and the only reason to advance it would be a deliberate
  #       re-issue of every initial value -- an operation this module has no way
  #       to distinguish from an accidental increment, and one that overwrites
  #       whatever a rotation function has since put in place. Accepted cost: a
  #       deliberate re-issue now means editing this file under review rather than
  #       flipping a tfvars number.
  secret_string_wo_version = 1
}

# WHY : Assumptions: this resource is CONDITIONAL, and the condition is the whole
#       point. This module creates no rotation function; a root that has one
#       supplies its ARN and an interval, and only then is a schedule attached to
#       every service secret. A root that has none -- which is the state of both
#       environment roots in this package -- gets no rotation resource at all,
#       rather than a schedule naming a function that does not exist.
# WHY : Trade-offs: iterated over local.service_secret_names and gated by a
#       for_each on an empty map rather than by `count`, so each schedule keeps the
#       role name as its resource key. Using `count` would key the schedules by
#       POSITION, and adding or removing a role would then renumber every later
#       one, planning a destroy-and-recreate of schedules that did not change.
resource "aws_secretsmanager_secret_rotation" "service" {
  #checkov:skip=CKV_AWS_304:This module does not implement rotation and creates no rotation function -- rotation of a secret VALUE is outside its remit, and the only rotation this infrastructure package owns is KMS key rotation in infra/modules/kms. A schedule is attached only when a calling root supplies both rotation_lambda_arn and rotation_automatically_after_days, and variables.tf bounds that interval to 1 through 1000 days. Neither environment root supplies them, so no schedule is created and the check has no resource to assess.
  for_each = local.configure_rotation ? local.service_secret_names : {}

  secret_id           = aws_secretsmanager_secret.service[each.key].id
  rotation_lambda_arn = var.rotation_lambda_arn

  rotation_rules {
    automatically_after_days = var.rotation_automatically_after_days
  }

  # WHY : Assumptions: the initial value has to exist before a schedule can rotate
  #       it, and Terraform cannot infer that ordering from the arguments above --
  #       both resources reference the secret, not each other. Stating it keeps a
  #       first apply from attaching a schedule to a secret that has no version yet.
  depends_on = [
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
