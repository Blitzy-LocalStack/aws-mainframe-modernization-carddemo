# =============================================================================
# infra/modules/secrets/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provision the AWS Secrets Manager entries holding this stack's database
#   credentials -- the Aurora PostgreSQL master credential, and one credential
#   per service database role -- and GENERATE every one of those values during
#   `terraform apply` rather than read any of them from source.
#
#   The generation step is the point of this module rather than a detail of it.
#   No variable in infra/modules/secrets/variables.tf accepts a password, a
#   secret string, or a path to one, so the only value this file is able to
#   write into a secret is one the hashicorp/random provider produced during the
#   run. The project constraint "no secrets committed to the repository"
#   therefore holds because the configuration cannot EXPRESS a committed
#   credential -- not because a reviewer notices one.
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
#   database_master_username ........... the login NAME stored beside the
#                                        generated master password
#   password_length .................... character count of each generated value
#   service_credential_names ........... one credential is created per element
#   recovery_window_in_days ............ deletion-recovery behaviour
#   rotation_lambda_arn,
#   rotation_automatically_after_days .. together enable scheduled rotation
#   tags ............................... applied to every secret created here
#
# Return values:
#   None declared here. The secret ARNs and names that the calling root wires
#   into the Aurora cluster and the ECS task definitions are published by
#   infra/modules/secrets/outputs.tf, which reads the resources below and gives
#   each output its own `description`.
#
# Errors / Exceptions:
#   Misconfiguration is caught at `plan` by the validation blocks in
#   variables.tf, so it creates nothing. Three failures remain reachable only at
#   `apply`, and each is documented at the argument that governs it: a generated
#   password containing a character the database engine refuses (see
#   `override_special`); a secret name still reserved by an earlier deletion (see
#   `recovery_window_in_days` here and its trade-off note in variables.tf); and a
#   rotation configuration whose function cannot reach the cluster (see the
#   rotation resources). Nothing in this file reads the AWS API, so it resolves
#   no data source and names no region, account or ARN literal.
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
#     recorded from the input side in variables.tf, beside
#     `database_master_username`.
#   - Assumptions: a generated value IS at rest somewhere, as plaintext, in
#     Terraform state. Nothing here conceals that, and no reader should conclude
#     otherwise, because concluding otherwise leads directly to the state bucket
#     being treated as ordinary storage. It is the reason infra/bootstrap
#     provisions a VERSIONED, ENCRYPTED, TLS-only state bucket with a DynamoDB
#     lock table; the reason both environment roots point their backend at that
#     bucket; and the reason .gitignore excludes `*.tfstate*`. That ignore rule's
#     own note states what it does and does not protect against, and it is worth
#     reading here rather than assuming: state secrecy is a property of the
#     backend, not of this file.
# =============================================================================

# -----------------------------------------------------------------------------
# Composed names, tags and the rotation gate
# -----------------------------------------------------------------------------

locals {
  # WHAT: the grouping segment every secret name below sits under.
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

  # WHY : Assumptions: `master` is safe as a leaf because every service role
  #       name is prefixed `carddemo_` by
  #       data-migration/sql/V0__schemas_and_roles.sql, and var.name_prefix's
  #       charset forbids an underscore, so no composed service name can ever
  #       collide with this one.
  database_master_secret_name = "${local.secret_name_root}/master"

  # WHAT: role name to secret name, one entry per service database role.
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

  # WHAT: whether a scheduled-rotation configuration is created at all.
  # WHY : Assumptions: BOTH rotation inputs are required to be non-null, not
  #       just the function ARN, and the reason is the provider's schema rather
  #       than a preference. `aws_secretsmanager_secret_rotation` declares
  #       `rotation_rules` as a single REQUIRED block, so the block cannot be
  #       omitted while the resource exists, and a rules block carrying neither
  #       an interval nor a schedule expression is refused by the service --
  #       which would fail the apply after the secrets themselves exist.
  #       variables.tf states that a function supplied without an interval is a
  #       legitimate on-demand-only configuration; the faithful expression of
  #       that is to create no scheduled-rotation resource, since a schedule is
  #       precisely what an on-demand-only configuration lacks. An operator then
  #       rotates by naming the function on the rotate call. The reverse pairing
  #       -- an interval without a function -- is already refused at plan by that
  #       variable's own cross-reference validation, so it cannot reach here.
  rotation_enabled = var.rotation_lambda_arn != null && var.rotation_automatically_after_days != null

  # WHAT: the single tag this module contributes on top of the caller's set.
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
# Aurora PostgreSQL master credential
# -----------------------------------------------------------------------------

resource "random_password" "database_master" {
  length = var.password_length

  # WHY : Assumptions: this argument encodes an EXTERNAL contract -- the set of
  #       characters the database engine accepts in a master password -- and it
  #       is the single most commonly missed detail in this resource. The engine
  #       refuses the forward slash, the at sign, the double quote and a space.
  #       The provider's default special set omits three of those four but DOES
  #       include the at sign, so leaving this argument off yields a password
  #       that generates and stores perfectly and is then REJECTED when the
  #       cluster is created with it -- by which point the key, the secrets and
  #       the networking already exist, so the apply stops holding a partially
  #       created stack. That is far more work to unpick than the one character
  #       it came from. The value below is the provider's default set with the
  #       at sign removed; the other three were never in it.
  # WHY : Trade-offs: narrowing the alphabet is the correct fix rather than
  #       compensating for it elsewhere. The generator's default alphabet is 83
  #       characters -- 62 alphanumeric plus 21 specials -- and removing one
  #       leaves 82, which costs 0.56 bits of entropy across the whole
  #       32-character default length. That is not a quantity worth trading a
  #       failed apply for, and it is quantified here rather than asserted so
  #       nobody re-adds the character believing the cost was material.
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_secretsmanager_secret" "database_master" {
  name = local.database_master_secret_name

  # WHY : Assumptions: a description names what the entry holds and who reads
  #       it, and never any part of the value. Secrets Manager does not encrypt
  #       this field, and it is returned by `DescribeSecret`, which is a far
  #       more widely granted permission than `GetSecretValue` -- so anything
  #       written here is readable by principals deliberately not trusted with
  #       the credential.
  description = "Aurora PostgreSQL master credential for the ${var.environment} CardDemo stack. Value generated at apply time by the hashicorp/random provider and never present in the repository. Consumed by infra/modules/aurora-postgresql at cluster creation and by operators for break-glass access."

  # WHY : Refactoring Rationale: the store being replaced had no encryption at
  #       rest at all -- the VSAM file holding the baseline credentials is
  #       defined with JOURNAL(NO) and RECOVERY(NONE) FWDRECOVLOG(NO) at
  #       app/csd/CARDDEMO.CSD:L88-L96, and nothing in those 505 lines of CICS
  #       resource definitions declares encryption of any kind. Naming a
  #       customer-managed key is what closes that gap here, and it has to be
  #       named explicitly: omitting the argument does not fail, it silently
  #       falls back to the AWS-managed `aws/secretsmanager` key, which still
  #       encrypts and so leaves a stack that looks correct while having dropped
  #       the customer-managed-key requirement. variables.tf refuses a default
  #       for this input for the same reason.
  kms_key_id = var.kms_key_arn

  # WHY : Trade-offs: this is a genuine axis, which is why it is an input rather
  #       than a constant here. A non-zero window keeps a deleted secret
  #       recoverable, and also keeps its NAME reserved for the duration -- so a
  #       `destroy` followed by a re-`apply` fails on a name that no longer
  #       appears to exist anywhere. dev therefore sets 0 and can be rebuilt at
  #       will; prod keeps a real window and accepts that it cannot be recreated
  #       under the same names until the window lapses. The full reasoning and
  #       the accepted domain live on the variable itself.
  recovery_window_in_days = var.recovery_window_in_days

  # WHY : Trade-offs: tags are applied per resource rather than inherited,
  #       because only a ROOT may configure a provider and therefore only a root
  #       may set `default_tags` -- infra/modules/secrets/versions.tf declares no
  #       provider block, and that absence is what creates this obligation. The
  #       module's own tag is merged LAST so a caller cannot silently overwrite
  #       the classification an IAM tag condition may depend on; every other key
  #       the caller supplies passes through untouched, and where the calling
  #       root does set `default_tags` the two sets merge rather than conflict.
  tags = merge(var.tags, local.module_tags)
}

resource "aws_secretsmanager_secret_version" "database_master" {
  secret_id = aws_secretsmanager_secret.database_master.id

  # WHY : Assumptions: exactly two keys, `username` and `password`, because that
  #       is the document data-migration/src/carddemo_migration/config.py reads
  #       -- its credential helper requires both and refuses a document in which
  #       either is absent or blank.
  # WHY : Alternatives Considered: the fuller "database secret" shape that the
  #       AWS-supplied rotation functions expect, carrying `engine`, `host`,
  #       `port` and `dbname` alongside the credential. Rejected here for a
  #       structural reason rather than a stylistic one: the cluster endpoint is
  #       an OUTPUT of infra/modules/aurora-postgresql, and that module takes
  #       this secret's ARN as an INPUT, so writing the host into this value
  #       would make the two modules mutually dependent -- a cycle Terraform
  #       reports as a hard error, not a warning. The endpoint is published
  #       instead as the sibling `<prefix>/<environment>/aurora/host` parameter,
  #       which is where a rotation function resolves it from.
  # WHY : Trade-offs: `jsonencode()` rather than a built-up string. jsonencode
  #       escapes whatever it is handed, so the document stays valid for every
  #       value the generation parameters can produce -- including after
  #       `override_special` above is widened, and including a value written by a
  #       rotation function rather than by this configuration. A concatenated
  #       document would be correct only for as long as the character set
  #       happens to exclude a double quote and a backslash, which is a silent
  #       coupling between two distant arguments that nothing checks.
  secret_string = jsonencode({
    username = var.database_master_username
    password = random_password.database_master.result
  })

  lifecycle {
    # WHY : Assumptions: after creation the STORED value is authoritative, not
    #       this configuration. Without this, the next `terraform apply` after
    #       any out-of-band rotation would rewrite the secret back to the
    #       originally generated value, and every consumer already holding the
    #       rotated credential would immediately fail to authenticate -- an
    #       outage caused by an apply that changed nothing a reviewer asked for.
    # WHY : Trade-offs: the accepted compromise is that Terraform stops managing
    #       this value once it exists, which is precisely the intent and not a
    #       limitation. Viewed from the other side, it is also why a later change
    #       to `password_length` regenerates the random value without rewriting
    #       the stored secret: the credential in use is never replaced as a side
    #       effect of a parameter edit.
    # WHY : Alternatives Considered: the provider's write-only `secret_string_wo`
    #       pairing, and an `ephemeral "random_password"` to feed it. Both were
    #       evaluated and rejected. The write-only argument keeps the value out
    #       of state for THIS resource, but `random_password.result` is still
    #       recorded in state, so the exposure the header documents is unchanged
    #       while the ignore-on-drift protection above becomes a version counter
    #       this module has no input for. An ephemeral generator does remove the
    #       state copy, and regenerates on every plan, so the stored credential
    #       would be replaced on every run -- rotating every service's password
    #       out from under it as a side effect of an unrelated apply.
    ignore_changes = [secret_string]
  }
}

# WHY : Refactoring Rationale: rotation is configured through a SEPARATE
#       resource, and the obvious alternative is not merely untidy but invalid.
#       `aws_secretsmanager_secret` carried `rotation_lambda_arn` and a
#       `rotation_rules` block in earlier provider majors; both were removed
#       from that resource's schema before the `~> 6.56` major this tree pins in
#       infra/modules/secrets/versions.tf. Writing them inline therefore fails
#       `terraform validate` outright with an unsupported-argument error, so this
#       is a schema fact rather than a style choice, and it is recorded because
#       every older example of this pattern shows the inline form.
# WHY : Assumptions: no rotation function is in scope anywhere in this
#       migration, and this resource does not create one. Do not read its
#       presence as rotation being implemented: the key rotation the design does
#       provide is configured in infra/modules/kms on the four customer-managed
#       keys, which protects the KEY rather than the value. What these arguments
#       provide is a supplied function being wirable without this module
#       changing shape, and a policy scanner's rotation expectation being
#       satisfiable once one is supplied. The mechanism is load-bearing in the
#       wider design even so: data-migration/sql/V0__schemas_and_roles.sql
#       creates each service role with LOGIN and no password clause, and names
#       the rotation function configured through these two inputs as what
#       applies each generated credential to its role -- because the alternative
#       it rejects is an operator reading a credential out of the store and
#       typing an `ALTER ROLE` statement with the value inline by hand, which
#       lands that value in a shell history, in a psql history file, and in the
#       server log whenever statement logging is anything but off.
resource "aws_secretsmanager_secret_rotation" "database_master" {
  # WHY : Trade-offs: gated on `local.rotation_enabled` -- both inputs non-null
  #       -- for the schema reason recorded on that local. Gating the RESOURCE
  #       rather than the block is what makes the disabled case expressible at
  #       all, since a `dynamic "rotation_rules"` producing zero blocks would
  #       fail validation against a block the schema declares as required.
  count = local.rotation_enabled ? 1 : 0

  secret_id           = aws_secretsmanager_secret.database_master.id
  rotation_lambda_arn = var.rotation_lambda_arn

  # WHY : Assumptions: rotation is NOT triggered by the apply that configures
  #       it, even though the provider's default is to trigger it. The ordering
  #       the design depends on runs the schema bootstrap first, so the roles
  #       exist, and only then rotates. Leaving this at its default inverts that:
  #       the apply would configure rotation and immediately invoke the function
  #       against a cluster whose roles data-migration/sql/V0__schemas_and_roles.sql
  #       has not yet created, so the rotation fails and the apply reports an
  #       error against infrastructure that is in fact correct. The first
  #       rotation is triggered explicitly by the bootstrap step instead.
  rotate_immediately = false

  rotation_rules {
    automatically_after_days = var.rotation_automatically_after_days
  }

  # WHY : Assumptions: rotation stages a new value against the AWSCURRENT
  #       label, so a version has to exist first. Nothing in the arguments above
  #       references the version resource, so without this Terraform is free to
  #       create the rotation configuration and the first version concurrently --
  #       an apply-order race that only sometimes has a version present, which is
  #       the hardest class of failure to reproduce afterwards.
  depends_on = [aws_secretsmanager_secret_version.database_master]
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
# WHY : Trade-offs: the rationale for `override_special`, `kms_key_id`,
#       `recovery_window_in_days`, the merged tags, `jsonencode`, the ignored
#       `secret_string` and the rotation gate is IDENTICAL to the master
#       credential above and is deliberately not repeated. Four restatements of
#       one argument add nothing a reader cannot get by scrolling up, and the
#       project's documentation rule forbids comments that add nothing. Only
#       what differs for these resources is commented below.
# -----------------------------------------------------------------------------

resource "random_password" "service" {
  # WHY : Assumptions: keyed on the role name, matching
  #       `local.service_secret_names`, so the two families line up and
  #       `random_password.service[each.key]` resolves in the version resource
  #       below without a second mapping. The set is iterated directly rather
  #       than through that map because a password needs the role's identity for
  #       keying only, not its composed secret name.
  for_each = var.service_credential_names

  length           = var.password_length
  override_special = "!#$%&*()-_=+[]{}<>:?"
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
  description = "Login credential for the ${each.key} database role in the ${var.environment} CardDemo stack. Value generated at apply time by the hashicorp/random provider and never present in the repository. Read by that role's own service through its Spring datasource configuration."

  # WHY : the reasoning for these three is stated once on
  #       aws_secretsmanager_secret.database_master above -- the
  #       customer-managed key against app/csd/CARDDEMO.CSD:L88-L96, the
  #       destroy-then-reapply cost of a non-zero recovery window, and why tags
  #       are merged per resource with this module's own key last. This pointer
  #       is here rather than a fourth copy of the paragraphs, for the reason
  #       recorded in the section note above.
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = var.recovery_window_in_days
  tags                    = merge(var.tags, local.module_tags)
}

resource "aws_secretsmanager_secret_version" "service" {
  for_each = local.service_secret_names

  secret_id = aws_secretsmanager_secret.service[each.key].id

  # WHY : Assumptions: the `username` is the ROLE name itself, taken from the
  #       map key, and this is the one place these credentials differ in
  #       substance from the master credential -- which takes its user name from
  #       an input, because a cluster's master user is named independently of any
  #       schema. data-migration/src/carddemo_migration/config.py asserts the
  #       `username` it reads against the schema's owning role precisely so that
  #       a mis-targeted rotation or a secret repaired with another role's
  #       contents is caught rather than connected with, so writing anything but
  #       the role name here would make every load fail that check.
  secret_string = jsonencode({
    username = each.key
    password = random_password.service[each.key].result
  })

  lifecycle {
    # WHY : same three reasons as
    #       aws_secretsmanager_secret_version.database_master above: an apply
    #       after an out-of-band rotation would otherwise revert the stored value
    #       and break the holder of the rotated credential; the stored value is
    #       authoritative once it exists; and the write-only and ephemeral
    #       alternatives were evaluated and rejected there.
    ignore_changes = [secret_string]
  }
}

resource "aws_secretsmanager_secret_rotation" "service" {
  # WHY : Trade-offs: the same both-inputs-non-null gate as the master
  #       credential, expressed as an empty set rather than a zero count because
  #       this family is iterated. Keying the enabled case on
  #       `local.service_secret_names` keeps the instance addresses identical to
  #       the secrets they configure, so enabling rotation later adds instances
  #       without disturbing any existing one.
  for_each = local.rotation_enabled ? local.service_secret_names : {}

  # WHY : `rotate_immediately`, the required single `rotation_rules` block and
  #       the ordering behind `depends_on` are all argued on
  #       aws_secretsmanager_secret_rotation.database_master above, and the
  #       reasons hold unchanged here. The one difference worth noting is that
  #       `depends_on` names the whole version family rather than one instance,
  #       because Terraform accepts only a resource address in `depends_on` --
  #       not an indexed one built from `each.key` -- so every rotation instance
  #       waits for all nine versions. The cost is a slightly wider ordering
  #       constraint than each instance strictly needs; the alternative is no
  #       ordering guarantee at all.
  secret_id           = aws_secretsmanager_secret.service[each.key].id
  rotation_lambda_arn = var.rotation_lambda_arn
  rotate_immediately  = false

  rotation_rules {
    automatically_after_days = var.rotation_automatically_after_days
  }

  depends_on = [aws_secretsmanager_secret_version.service]
}

# =============================================================================
# Deliberately absent -- recorded so that a later editor does not add any of it
# back believing it was overlooked. Every entry is a real error in this file, not
# a preference.
#
#   - No `provider` block, and no `region`. This directory is a module; the
#     infra/envs/dev and infra/envs/prod roots that call it own provider
#     configuration. A provider block here would either be ignored or compete
#     with the caller's, and it would hard-wire a region into a module that has
#     to stay reusable across regions.
#   - No `backend` block. A module cannot declare one: state belongs to the
#     calling root.
#   - No nested `module` call. A module in this tree does not call a sibling;
#     composition happens in the environment root, which is the only place that
#     can see the whole graph.
#   - No `data "aws_kms_key"` lookup. The key arrives as `var.kms_key_arn` from
#     infra/modules/kms by way of the calling root. Resolving it here would
#     duplicate knowledge of how the key is named, leaving two places that have
#     to agree -- the Terraform form of the house single-sourcing discipline
#     stated in tests/README.md section 12 as "never duplicate a layout; keep it
#     single-sourced".
#   - No `aws_iam_policy`, `aws_iam_role` or `aws_secretsmanager_secret_policy`.
#     Read grants belong to the consuming infra/modules/ecs-service and to the
#     environment root. Granting from here would make this module depend on the
#     service inventory -- it would have to know which task role reads which
#     secret -- and that coupling is what the classification tag in `locals`
#     exists to avoid.
#   - No hard-coded credential, no example value, no `changeme` placeholder and
#     no commented-out credential. The only password-shaped things in this file
#     are the two `random_password` resources, `var.password_length`, and the
#     `jsonencode` references to a generated attribute.
#   - No `local-exec` or `remote-exec` provisioner, and no `null_resource`. A
#     provisioner that echoed or wrote a generated value would put it into the
#     apply log, which is the one place all of the above is arranged to keep it
#     out of.
#   - No `output` and no `variable` block. Both live in the files named for
#     them, which is what tflint's terraform_standard_module_structure rule
#     enforces and what the generated module documentation reads.
# =============================================================================
