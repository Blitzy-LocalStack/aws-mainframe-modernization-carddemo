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
# Ownership boundary:
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
#   - Alternatives Considered: a managed random_password resource whose result is
#     retained in state. Rejected: every historical state version would then hold
#     the credential, which is the exposure ephemeral generation plus
#     secret_string_wo exists to close.
#   - Alternatives Considered: packaging a rotation Lambda here, with its own
#     execution role, inline policy, log group, invoke permission and source
#     archive. Rejected: rotation of a SECRET VALUE is not this module's remit --
#     the only rotation this infrastructure package owns anywhere is KMS KEY
#     rotation, which belongs to infra/modules/kms and its four customer-managed
#     keys -- and owning a function here would drag eight cross-module coordinates
#     into the input contract (cluster ARN, RDS-managed master secret ARN, writer
#     endpoint, port, database name, a log-group key, a log retention value and an
#     IAM permissions boundary) plus a third Terraform provider to build the
#     deployment package. A root that wants rotation supplies the function's ARN
#     through `rotation_lambda_arn`, which is where the boundary of this module's
#     remit actually falls: an alternating-user rotation function needs Data API
#     access to the cluster and read access to the RDS-managed master secret, and
#     the ROOT holds both.
#   - Alternatives Considered: two further inputs, a PEM certificate and its
#     private key, were also removed. They existed so this module could copy the
#     pair into two Secrets Manager entries, which made a reusable module a
#     second custodian of private-key material; `sensitive = true` on them
#     changed only how a plan RENDERED the value, not whether a tfvars file or a
#     state file could hold it. There is now no material to stop anywhere: each
#     online task mints its own listener key pair and self-signed certificate at
#     startup (config/docker/generate-listener-material.sh), and the load
#     balancer's certificate is an ACM ARN the operator imported out of band and
#     passes as alb_certificate_arn. Relying on both roots continuing to pass
#     null was rejected as a convention rather than a control.
#   - Refactoring Rationale: this entry said the material "now stops at
#     aws_acm_certificate in the calling root -- the service purpose-built to
#     custody a private key, which accepts it once and never re-exports it".
#     That described an intermediate arrangement in which both roots generated
#     the pair with the tls provider and imported it; every one of those
#     resources is deleted, the tls provider requirement is removed from both
#     roots, and the imported certificate was unreachable in any case because
#     alb_certificate_arn is non-nullable with no default, so no listener could
#     select it. The sentence is corrected rather than deleted because the
#     question it answers -- who holds the private key -- is the one a reader
#     comes to this header for.
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

  # WHY : Assumptions: every name this map produces sits under the `aurora` segment
  #       because every entry this module creates IS a database credential. That is
  #       a shape data-migration/src/carddemo_migration/config.py derives
  #       independently and resolves a DATABASE role's credential from, so an entry
  #       with no database role behind it would be discoverable by a reader that
  #       expects to find one and would carry a value no ALTER ROLE could ever bind.
  #       This is why the machine-identity signing key is created by the environment
  #       root under its own `internal-identity` segment and not here -- see the
  #       withdrawal note at the foot of this file.
  # WHY : Assumptions: a rotation schedule is attached only when the calling root
  #       supplies BOTH a function ARN and an interval. This module creates no
  #       rotation function of its own -- see the header -- so an interval with no
  #       function would configure nothing and a function with no interval is a
  #       schedule Secrets Manager refuses. variables.tf refuses the half-supplied
  #       case at plan time, so this expression only has to distinguish "both" from
  #       "neither".
  configure_rotation = var.rotation_lambda_arn != null && var.rotation_automatically_after_days != null

  # WHY : Trade-offs: one classification tag is worth its cost because Secrets
  #       Manager offers no other grouping. A principal that needs "every
  #       database credential" -- the schema-bootstrap identity is the one that
  #       genuinely does -- must either enumerate ARNs, which then needs editing
  #       every time the role inventory changes, or match a tag condition, and
  #       only publishing the tag makes the second option available. It is also what separates these entries from the
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
# One credential per element of var.service_credential_names -- the sixteen
# LOGIN roles data-migration/sql/V0__schemas_and_roles.sql creates, in two
# tiers. Eight are RUNTIME roles, one per connecting workload: the seven bounded
# contexts (carddemo_auth, carddemo_account, carddemo_card, carddemo_ledger,
# carddemo_reference, carddemo_batch and carddemo_authorization) plus
# carddemo_reporting, the read-only role the reporting service connects as.
# Seven are MIGRATION roles -- carddemo_<context>_migrator, one for each context
# that ships a Flyway migration -- and reporting has none, because
# reporting-service owns no db/migration directory. That script is the source of
# truth for the names, and the secret entry each credential is written to has to
# match a role name character for character, which is why the inventory arrives
# as an input validated against a closed list rather than being restated here.
#
# WHY : Assumptions: the eight SCHEMA-OWNING roles V0 also creates --
#       carddemo_<context>_owner -- deliberately have no entry here. They are
#       NOLOGIN, so they hold no password for this module to store and no
#       credential for an attacker to present; a migration role reaches its
#       owner's authority with SET ROLE inside an already-authenticated session
#       rather than by authenticating as it. Creating an entry for a role that
#       cannot log in would publish a credential that grants nothing while
#       implying the DDL-capable identity is reachable by authentication, which
#       is the precise property the NOLOGIN split removes.
#
# WHY : Alternatives Considered: ONE secret holding every service credential as
#       a single JSON document, which is fewer resources and one name to
#       remember. Rejected on least privilege. A principal can be granted
#       `GetSecretValue` on a secret or not at all -- there is no way to scope
#       the grant to one key inside a document -- so a shared secret would give
#       every service read access to every other service's credential. The
#       target posture is one narrowly scoped grant per task EXECUTION role,
#       which is the identity ECS uses to resolve a container definition's
#       `secrets` block before the container starts, and the
#       schema privileges behind these roles are themselves deliberately
#       separated per bounded context, so a shared secret would hand back at the
#       credential layer exactly the separation the database layer was built to
#       enforce. One secret per role is what keeps a grant expressible.
# WHY : Trade-offs: one secret per role costs sixteen resources and is what lets
#       each ECS execution role receive exactly the resources its workload
#       needs -- its runtime credential and, separately, its migration
#       credential -- rather than a document containing every bounded context's
#       credential. Splitting runtime from migration doubles the entry count for
#       the seven migrating contexts, and that cost buys the property F-10
#       exists to establish: the credential a long-running task holds cannot
#       create, alter or drop anything, because the identity that can is reached
#       only through a second, separately granted secret.
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
  # WHY : Assumptions: the description states that the value is STATIC rather than
  #       attributing subsequent values to a rotation function, because no rotation
  #       function exists. This module implements no rotation, as variables.tf
  #       states at length, and neither environment root supplies a rotation ARN,
  #       so both leave the hook null and no schedule is attached. Trade-offs: this
  #       field is the one DescribeSecret returns unencrypted, so it is where an
  #       operator reads and stops looking -- naming a control that ships nowhere
  #       here would be more misleading than saying nothing. The re-issue procedure
  #       and the risk accepted for a static credential are recorded in
  #       docs/adr/ADR-002-compute-platform.md.
  description = "Login credential for the ${each.key} database role in the ${var.environment} CardDemo stack. Initial value is generated ephemerally and never written to Terraform state. The value is STATIC: no rotation is configured for this stack, so it changes only when an operator re-issues it."

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
  #       to distinguish from an accidental increment. Accepted cost: a deliberate
  #       re-issue now means editing this file under review rather than flipping a
  #       tfvars number, and it replaces ALL of the entries at once because this
  #       literal is shared by every one of them. The procedure and the risk
  #       accepted for a static credential are recorded in
  #       docs/adr/ADR-002-compute-platform.md.
  # WHY : Assumptions: an increment overwrites only the value this module itself
  #       wrote, so the paragraph above scopes the cost to exactly that. Nothing
  #       else writes these entries: the module implements no rotation, as
  #       variables.tf states, and neither environment root supplies a rotation
  #       function, so there is no externally-managed value for an increment to
  #       displace.
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

# -----------------------------------------------------------------------------
# The shared workload credential -- WITHDRAWN.
#
# Refactoring Rationale: this module created one shared Secrets Manager entry
#   holding a message-authentication key that the pending-authorization context
#   used to sign, and the account context to verify, the calls made on behalf of
#   the platform rather than of a signed-on user. It has been removed, along with
#   its generator, its version and its output.
#
#   Two mechanisms existed for that one hop and one had to go. The surviving one
#   is a signed JWT: com.carddemo.common.security.InternalServiceToken mints a
#   short-lived token carrying an issuer, a subject, an audience, a scope and an
#   expiry, and com.carddemo.account.config.InternalApiSecurityConfig verifies it
#   with the framework's own NimbusJwtDecoder on an earlier-ordered filter chain
#   whose security matcher names exact method-and-path pairs and nothing else,
#   enumerated in InternalApiSecurityConfig.internalPaths(). Its key material is
#   TWO internal-identity entries the environment roots create, one per minting
#   caller, and each entry reaches exactly TWO task definitions:
#   CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY goes to authorization,
#   which MINTS with it, and to account, which VERIFIES; and
#   CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY goes to transaction and to
#   account on the same footing. The keys are symmetric, so a verifier holds the
#   same value its signer does, which is why account holds BOTH and why no third
#   holder may be added to either: any additional holder could mint a token the
#   account context accepts on its internal reads. Alternatives Considered: one
#   shared entry for both callers. Rejected -- with shared bytes each caller can
#   mint as the other, so splitting scopes or checking subjects buys nothing.
#   infra/modules/ecs-service asserts each membership as its own biconditional, so
#   a third workload receiving either key, or a listed workload missing it, fails
#   the plan rather than the audit. Assumptions: the withdrawn single-key name
#   CARDDEMO_INTERNAL_IDENTITY_SIGNING_KEY is in no image, no gate and neither
#   environment root, and docs/architecture/security-and-identity.md asserts that
#   absence executably rather than in prose -- so a reader who finds the old name
#   in a comment describing the withdrawal cannot mistake it for a live variable.
#
# Assumptions: the withdrawn form's one advantage is not lost. It bound the
#   method and the path INTO the signature, so a captured credential could not be
#   replayed against another operation. The surviving form asserts the same
#   property on the verifying side instead: its token is accepted only on the
#   exact pairs that chain matches, so a replay elsewhere reaches a chain that
#   knows nothing about it and is refused. This paragraph deliberately names no
#   count of matched pairs -- the replay argument rests on the matcher being
#   EXACT, not on how many pairs it names, so a number here would go stale on the
#   next route while the argument would not. What is gained in exchange is that
#   expiry, length and signature checking are the framework's audited code rather
#   than this repository's.
#
# Trade-offs: an entry that no root consumed would still have been created,
#   costing a stored secret per environment and inviting a reader to wire a
#   service to the mechanism that no longer verifies anything. Removing it makes
#   the provisioned stack match the code that runs.
# -----------------------------------------------------------------------------

# =============================================================================
# Ownership exclusions.
# Assumptions: environment roots that instantiate this module own provider,
#   backend, region, KMS wiring, and sibling composition.
# Refactoring Rationale: IAM grants stay with the consuming principals -- each
#   workload's task EXECUTION role for injected credentials, and the
#   schema-bootstrap identity for the whole set -- while this module creates only
#   credential values and Secrets Manager resources.
# Alternatives Considered: KMS data lookups, hard-coded/example credentials,
#   provisioners, and local command hooks are excluded because they duplicate
#   naming authority or risk exposing generated values in source or apply logs.
# =============================================================================
