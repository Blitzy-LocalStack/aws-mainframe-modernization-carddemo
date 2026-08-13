# =============================================================================
# infra/modules/secrets/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the COMPLETE input surface of the `secrets` module -- the module
#   that provisions the Secrets Manager entries holding one credential per
#   service database role, together with the customer-managed-key association
#   and deletion-recovery behaviour those entries are created under. Every
#   variable carries its own `description`, which is also the text
#   terraform-docs injects into README.md, so the surface is not re-listed here.
#
#   The defining property of this file is a thing it does NOT contain. No
#   variable declared here accepts a password, a secret string, a credential
#   value, a certificate, a private key, or a path to one. Every credential this
#   module manages is GENERATED ephemerally and written through a write-only
#   provider argument, so a credential value never appears in this repository in
#   any form and is never retained in state. That omission is deliberate and
#   load-bearing: it is the mechanism that makes "no secrets committed to the
#   repository" a structural property of the configuration rather than something
#   a reviewer has to notice. The variables here are therefore all NON-SECRET --
#   naming components, the key to encrypt under, generation parameters, the
#   master role's login NAME, lifecycle settings and tags -- and every one of
#   them is safe to commit to `infra/envs/<env>/terraform.tfvars`.
#
#   Alternatives Considered: a `master_password`, `secret_string`, `secrets_map`
#   or `service_tls_certificate`/`service_tls_private_key` PEM pair input. Each
#   is the obvious way to model this module and each is why the module exists in
#   this shape: a value that cannot be supplied cannot be committed, whereas a
#   value that can be supplied relies on every future contributor choosing not
#   to put it in a tfvars file.
#
#   Assumptions: this file is parsed as part of a MODULE, never a root. Its
#   provider and CLI constraints come from infra/modules/secrets/versions.tf and
#   its values from infra/envs/dev/main.tf and infra/envs/prod/main.tf. Nothing
#   here reads the AWS API, so no `data` source and no region or account
#   identifier appears in this file. The non-secret CONNECTION coordinates --
#   writer endpoint, listener port and database name -- are deliberately not
#   inputs either: infra/modules/aurora-postgresql publishes them to Parameter
#   Store under the same `<prefix>/<environment>/aurora` path this module
#   composes its secret names from, so accepting them here would make this
#   module a second, silently divergent copy of coordinates another module owns.
#
# Errors / Exceptions:
#   Each `validation` block turns a class of misconfiguration into a
#   `terraform plan` error with an actionable message instead of an AWS API
#   rejection partway through `apply`, which is the reason the checks are worth
#   their length: `apply` creates resources in dependency order, so a value the
#   service rejects late leaves a partially-created stack behind while a
#   plan-time failure creates nothing at all. `environment` and `kms_key_arn`
#   declare no default, so omitting either is itself a plan-time error naming
#   the missing variable.
#
#   Trade-offs: every name-segment variable carries both a charset check and a
#   length ceiling, and the charset checks are a deliberate SUBSET of what
#   Secrets Manager accepts, because the same prefix and environment values are
#   reused as name components by sibling modules whose services are stricter.
#   Validating the strict subset here means one set of values works across the
#   whole stack.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and composition
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = <<-EOT
    First segment of every secret name this module composes, so that all of
    this stack's secrets sort together and are addressable by one IAM resource
    pattern. Also the value sibling modules use as the leading component of
    their resource names, which is why the accepted charset is narrower than
    Secrets Manager alone would require.
  EOT
  type        = string
  default     = "carddemo"

  # Trade-offs: the ceiling is 32 rather than the 512 characters a
  #   Secrets Manager name allows. 512 is not a useful bound here: this
  #   prefix is only the FIRST of three composed segments, and the same
  #   value is reused by sibling modules whose services cap names far
  #   lower than Secrets Manager does. A 32-character budget leaves room
  #   for the environment and per-credential segments under those stricter
  #   caps. Validating against 512 would accept a prefix that composes
  #   into a legal secret name and an illegal name elsewhere in the stack.
  validation {
    condition     = can(regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$", var.name_prefix))
    error_message = "The name_prefix must be lowercase letters, digits and single interior hyphens, starting with a letter -- for example \"carddemo\"."
  }

  validation {
    condition     = length(var.name_prefix) >= 2 && length(var.name_prefix) <= 32
    error_message = "The name_prefix must be 2 to 32 characters, so the composed secret name also fits the stricter name limits of sibling modules."
  }
}

# Assumptions: this module has an `environment` variable even though
#   infra/bootstrap/variables.tf deliberately has none, and the contrast is
#   intentional. The bootstrap root provisions the remote-state backend and is
#   applied ONCE PER AWS ACCOUNT ahead of both environment roots, so it has no
#   environment to be parameterized by. This module is instantiated ONCE PER
#   ENVIRONMENT and both instances coexist in the same account, so the
#   environment segment is what keeps the dev and prod secret names apart.
variable "environment" {
  description = <<-EOT
    Environment segment of every secret name this module composes, which is
    what allows a dev and a prod instantiation to coexist in one AWS account
    without name collisions. Deliberately has no default: the two environment
    roots differ only in values, so a silent default here would let a root that
    forgot to set it write secrets under another environment's names.
  EOT
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$", var.environment))
    error_message = "The environment must be lowercase letters, digits and single interior hyphens, starting with a letter -- for example \"dev\" or \"prod\"."
  }

  # Trade-offs: the 16-character ceiling is a name-segment budget for the
  #   same reason as name_prefix above, not a service limit. It is stated
  #   separately from the charset check so a failure reports which of the
  #   two rules was broken rather than one combined message that leaves the
  #   operator guessing.
  validation {
    condition     = length(var.environment) <= 16
    error_message = "The environment must be 16 characters or fewer, so the composed secret name also fits the stricter limits of sibling modules."
  }
}

# -----------------------------------------------------------------------------
# Encryption
# -----------------------------------------------------------------------------

# Alternatives Considered: resolving this key inside the module with a
#   `data "aws_kms_key"` lookup on an alias. Rejected -- the four
#   customer-managed keys are created by infra/modules/kms and the calling root
#   already holds that module's outputs, so looking the key up here would give
#   two independent places that must agree about the alias. Passing the ARN in
#   also makes the dependency explicit in the root's graph, so the key is
#   provably created before the secrets reference it.
# Assumptions: there is deliberately NO default, because both candidate defaults
#   are wrong: a literal ARN cannot appear in this repository at all, and `null`
#   would silently fall back to the AWS-managed `aws/secretsmanager` key, quietly
#   losing the customer-managed-key property the target posture requires. A
#   missing required variable fails at plan and names itself.
variable "kms_key_arn" {
  description = <<-EOT
    ARN of the customer-managed KMS key that the values of every secret this
    module creates are encrypted under. Supplied by the calling environment
    root from the `kms` module's Secrets Manager key output. Required: there is
    no default, because falling back to the AWS-managed key would silently
    abandon customer-managed encryption for these values.
  EOT
  type        = string

  # Trade-offs: this asserts the ARN's SHAPE, not its identity. It catches a
  #   cross-wired root that passes a non-KMS ARN, which would otherwise surface
  #   as an opaque API error during apply, but it cannot distinguish the Secrets
  #   Manager key from the other three the kms module produces because all four
  #   are KMS key ARNs -- that wiring is verified by the root's own tests.
  #   Stating the limit matters so nobody reads the check as stronger than it is.
  #   The literal "arn:" and ":kms:" fragments are format assertions carrying no
  #   partition, account or key identifier, so they are not ARN literals.
  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:kms:", var.kms_key_arn))
    error_message = "The kms_key_arn must be a KMS key ARN of the form arn:<partition>:kms:... -- pass the kms module Secrets Manager key output."
  }
}

# -----------------------------------------------------------------------------
# Credential generation parameters
#
# Everything in this section parameterizes HOW a credential is generated, or
# names WHICH role a credential belongs to. None of it is, or can be, a
# credential.
#
# WHY : Alternatives Considered: the variable a reader most expects to find
#       at exactly this point is the one that is absent. An input accepting the
#       master password directly (`master_password`, `initial_password`,
#       `secret_string`, `password_override`, `secrets_map` -- the shape is not
#       the point) was considered and REJECTED. Such an input has to be
#       supplied from somewhere, and the only places a Terraform root can
#       supply it from are a committed tfvars file, a committed default, or an
#       environment variable that a CI definition then has to carry. The first
#       two put a credential in this repository outright; the third moves the
#       problem into CI configuration. With no such input in the module's
#       contract, main.tf's only source for a password is a generated one, so
#       "no secrets committed" holds because the configuration cannot express a
#       committed secret -- not because each reviewer catches it. A value that
#       cannot be supplied cannot be committed.
# WHY : Alternatives Considered: the same reasoning excludes a PEM pair
#       (`service_tls_certificate` and `service_tls_private_key`) that would let a
#       caller hand listener material to this module for storage. A private key is
#       credential material, so such inputs would reopen exactly the hole every
#       other input here is shaped to close, and `sensitive = true` on them would
#       change only how a plan RENDERED the value, not whether a tfvars file or a
#       state file could hold it. Declaring them and relying on both roots passing
#       null is a convention rather than a control, so they are absent instead.
#       Assumptions: there is no material for them to carry in any case -- each
#       task mints its own listener key pair and self-signed certificate at
#       startup, so no module and no root holds one.
# WHY : Refactoring Rationale: this replaces a baseline that stored the
#       credential in cleartext in the record itself:
#       `05 SEC-USR-PWD PIC X(08).` at app/cpy/CSUSR01Y.cpy:L21, an
#       eight-character plaintext password inside the USRSEC record. That record
#       lived in a VSAM file defined with JOURNAL(NO) and RECOVERY(NONE)
#       (app/csd/CARDDEMO.CSD:L88-L99), and the CICS resource definitions
#       declare no encryption of any kind anywhere in their 505 lines. Both
#       files are REFERENCE-ONLY and are cited here, never modified. Carrying
#       that field forward -- even as an input whose value happens to be
#       supplied securely -- would have reproduced the defect's shape in a new
#       system. Generating the value instead removes the field, and therefore
#       the defect, rather than relocating it.
# WHY : Trade-offs: marking such an input `sensitive = true` was also
#       considered, and rejected as an answer to the wrong question.
#       `sensitive` suppresses a value in plan output and in the CLI's
#       rendering; it does not stop that value being written into a tfvars
#       file, and it does not keep it out of state. It reduces incidental
#       exposure of a secret that exists. The objective here is that the secret
#       does not exist as an input at all, which no attribute can achieve --
#       only the absence of the declaration can.
# -----------------------------------------------------------------------------

# Assumptions: a username is an identifier and a password is a credential, so
#   they are treated differently on purpose and the asymmetry is worth stating
#   because it looks at first like an inconsistency. This variable exists, has a
#   default and is safe to commit; its password counterpart does not exist at
#   all. Knowing the login name of a database reachable only from the private
#   application subnets grants nothing on its own, and treating the name as a
#   secret would be self-defeating: it has to be legible in the Aurora cluster
#   definition, in connection strings and in operator runbooks.
# Assumptions: the default matches infra/modules/aurora-postgresql's
#   `master_username` default character for character. The two must agree,
#   because the name recorded in a credential document is the role an
#   operator-supplied rotation function escalates through, and a mismatch would
#   apply cleanly and then fail at the first rotation against a role that does
#   not exist.
variable "database_master_username" {
  description = <<-EOT
    Login NAME of the Aurora cluster's master role -- an identifier, never a
    credential -- recorded in each service credential document so that an
    operator-supplied rotation function (see rotation_lambda_arn) knows which
    role to escalate through. The matching PASSWORD is not an input to this
    module and is not stored by it: RDS generates and owns the master password,
    and infra/modules/aurora-postgresql publishes only that secret's ARN.
    Defaults to the same value as that module's own master_username input.
  EOT
  type        = string
  nullable    = false
  default     = "carddemo_admin"

  # Assumptions: the same lower-case PostgreSQL identifier rule that
  #   infra/modules/aurora-postgresql/variables.tf applies to the cluster's
  #   master_username, restated here because PostgreSQL folds unquoted
  #   identifiers: a role name carrying capitals only matches when quoted,
  #   so a credential document naming "Carddemo_Admin" would address a
  #   different role than the one that actually exists.
  validation {
    condition     = can(regex("^[a-z][a-z0-9_]*$", var.database_master_username)) && length(var.database_master_username) <= 63
    error_message = "The database_master_username must be 1 to 63 characters of lower-case letters, digits and underscores, beginning with a letter."
  }

  # Assumptions: "rdsadmin" is reserved by RDS for its own management
  #   user, so a cluster can never have it as a master role. Rejecting it
  #   here -- rather than letting the mismatch surface at the first rotation
  #   -- is worth a check because it is a plausible thing for someone to
  #   try, and the same rejection is made at the cluster itself in
  #   infra/modules/aurora-postgresql/variables.tf.
  validation {
    condition     = var.database_master_username != "rdsadmin"
    error_message = "The database_master_username must not be \"rdsadmin\", which RDS reserves for its own management user."
  }
}

variable "password_length" {
  description = <<-EOT
    Number of alphanumeric characters in each ephemeral initial service-role
    password. A generation parameter, not a credential.
  EOT
  type        = number
  default     = 32

  # Trade-offs: the two bounds have different natures and only one is a service
  #   limit. The floor of 32 is a POLICY floor -- the engine accepts 8, and this
  #   module refuses to generate anything that short because these credentials
  #   are machine-generated and machine-consumed, so a shorter password buys no
  #   human convenience to weigh against it. The ceiling of 128 IS the engine
  #   limit and is the more important half: a longer value generates and stores in
  #   Secrets Manager happily, then fails when the Aurora cluster is created with
  #   it, after the key, the secrets and the networking already exist. The default
  #   sits on the floor so the shortest value the module will generate is also the
  #   value it generates unless a root asks for more.
  validation {
    condition     = var.password_length >= 32 && var.password_length <= 128
    error_message = "The password_length must be 32 to 128 characters: 32 is this module's policy floor for a machine-generated credential and 128 is the engine's own ceiling."
  }
}

# Alternatives Considered: an EMPTY default, on the reasoning that a default here
#   would be a second copy of the service inventory and a copy is what drifts.
#   Rejected because an empty default is not neutral: it produces a plan and an
#   apply that both succeed while creating NOT ONE service credential, so every
#   service then starts, fails to resolve its secret, and reports a configuration
#   error against infrastructure that reported success. The inventory is also not a
#   matter of taste -- the role names are fixed by
#   data-migration/sql/V0__schemas_and_roles.sql, which every service's datasource
#   username has to match character for character -- so the honest expression is a
#   default that states them plus a validation that refuses anything else.
# Assumptions: drift is answered by the validation rather than by the absence of
#   a default. A ninth bounded context, or a further non-context login identity,
#   fails HERE at plan time, naming this variable, instead of applying cleanly
#   against a stale inventory. The same
#   closed-inventory treatment is applied to the repository set in
#   infra/modules/ecr, the dataset prefixes in infra/modules/s3-datasets and the
#   listener rules in infra/modules/alb, so every place the topology is enumerated
#   fails the same way. The set contains only role NAMES, so it is non-secret and
#   belongs in source; main.tf generates each credential ephemerally during apply
#   and writes it straight to Secrets Manager.
variable "service_credential_names" {
  description = <<-EOT
    Names of the database roles that each need their own generated credential; one
    secret is created per element. Fixed at the sixteen LOGIN roles
    data-migration/sql/V0__schemas_and_roles.sql creates, in three tiers:

      * eight RUNTIME roles, the identity each service connects as --
        carddemo_auth, carddemo_account, carddemo_card, carddemo_ledger,
        carddemo_reference, carddemo_batch, carddemo_authorization and
        carddemo_reporting;
      * seven MIGRATION roles, the identity Flyway connects as --
        carddemo_auth_migrator and its six siblings. Reporting has none because
        reporting-service runs no migration;
      * one VERIFICATION role, the identity a post-load verification connects as
        -- carddemo_verifier. It holds SELECT on the five schemas the ETL loads
        into and no write privilege anywhere, and the bootstrap sets
        default_transaction_read_only on it, so a verification cannot alter the
        rows it certifies. It needs a credential because it LOGS IN; being
        read-only makes it no less an authenticated identity.

    The EIGHT owner roles (carddemo_auth_owner and its siblings, including
    carddemo_reporting_owner) are deliberately NOT accepted. Each owns one schema
    and every object in it, each is created NOLOGIN, and so none has a credential
    to generate -- which is the property that makes schema ownership unreachable by
    authentication rather than merely unused. Issuing an owner a credential would
    undo the separation this inventory exists to preserve.

    Note the carddemo_ prefix: these are ROLE names, not the bare schema names, and
    the two are not interchangeable -- "ledger" is the schema, carddemo_ledger is
    the role a service connects as, and carddemo_ledger_owner is the role that owns
    it. Defaulted and validated rather than left to the caller, because a service
    whose secret was never created starts and then fails to resolve it.
  EOT
  type        = set(string)
  default = [
    "carddemo_auth",
    "carddemo_account",
    "carddemo_card",
    "carddemo_ledger",
    "carddemo_reference",
    "carddemo_batch",
    "carddemo_authorization",
    "carddemo_reporting",
    "carddemo_auth_migrator",
    "carddemo_account_migrator",
    "carddemo_card_migrator",
    "carddemo_ledger_migrator",
    "carddemo_reference_migrator",
    "carddemo_batch_migrator",
    "carddemo_authorization_migrator",
    "carddemo_verifier",
  ]
  nullable = false

  # WHY : Assumptions: membership in a closed list is checked rather than a name
  #       SHAPE, because shape was never the real constraint. The sixteen names are
  #       fixed by the bootstrap SQL that creates the roles and binds their
  #       credentials, so any other name is wrong however well-formed it is. A
  #       shape check accepted "ledger", "carddemo_Ledger" was already caught by
  #       the lowercase rule, and "carddemo_reports" would have passed silently --
  #       and only the closed list rejects all three.
  # WHY : Trade-offs: this module now holds a copy of the sixteen role names,
  #       which is exactly the duplication the default above declines to make, and
  #       the two decisions are not in conflict. A stale DEFAULT applies cleanly
  #       and provisions the wrong set silently; a stale VALIDATION LIST fails the
  #       plan with a message naming the value it rejected, so a ninth bounded
  #       context added to the bootstrap SQL and not to this list stops the apply
  #       at review time rather than half-provisioning. The copy is safe in
  #       precisely the direction the default was not.
  # WHY : Assumptions: a SUBSET is accepted rather than all sixteen demanded. A
  #       root that provisions a partial stack, or one environment brought up
  #       ahead of another, is a coherent state this module should not forbid; the
  #       bootstrap SQL is where the requirement that ALL sixteen roles be bound is
  #       enforced, and it raises there naming any role left unbound. Requiring
  #       completeness here as well would put one rule in two places, and the
  #       place that can actually see whether a role authenticates is the one that
  #       should own it.
  validation {
    condition = alltrue([
      for role_name in var.service_credential_names :
      contains([
        "carddemo_auth",
        "carddemo_account",
        "carddemo_card",
        "carddemo_ledger",
        "carddemo_reference",
        "carddemo_batch",
        "carddemo_authorization",
        "carddemo_reporting",
        "carddemo_auth_migrator",
        "carddemo_account_migrator",
        "carddemo_card_migrator",
        "carddemo_ledger_migrator",
        "carddemo_reference_migrator",
        "carddemo_batch_migrator",
        "carddemo_authorization_migrator",
        "carddemo_verifier",
      ], role_name)
    ])
    error_message = "Each service_credential_names element must be lowercase letters, digits and underscores, start with a letter, and be at most 63 characters -- for example \"carddemo_ledger\"."
  }

  # WHY : Assumptions: the inventory holds sixteen names for two reasons rather
  #       than one. The schema-ownership split in V0 section 1 needs two identities
  #       per context: a schema is owned by a NOLOGIN role, so Flyway cannot create
  #       its objects by connecting as the runtime identity and needs an identity of
  #       its own that can `SET ROLE` to the owner, and each of those seven
  #       migration identities has LOGIN and therefore needs a credential. The
  #       sixteenth is the verification identity, which exists because a post-load
  #       verification connecting as a service role would hold INSERT and UPDATE on
  #       the very rows it certifies.
  # WHY : Assumptions: the sixteen role names are a contract with a SQL artifact,
  #       not a naming convention. data-migration/sql/V0__schemas_and_roles.sql
  #       creates exactly these sixteen LOGIN roles, and its own header states that the
  #       names are the source of truth: the datasource username each service
  #       resolves and the secret entry each credential is written to must match
  #       them character for character. A secret created under any other name is
  #       therefore a credential no service reads, and a role left without one is a
  #       service that cannot authenticate -- and BOTH states apply cleanly, which
  #       is what makes an unvalidated inventory here expensive.
  #       Assumptions: equality needs both halves. The setunion comparison alone
  #       proves only that every name supplied is one of the sixteen, so a root
  #       creating a single credential would satisfy it; the count closes that,
  #       because a set holds no duplicates, so sixteen members drawn from a set of
  #       sixteen is that set exactly.
  #       Trade-offs: a ninth bounded context now needs an edit here. Accepted:
  #       that context also needs a schema and a role in the bootstrap SQL, a
  #       repository in infra/modules/ecr and, if it is online, a listener rule in
  #       infra/modules/alb, each of which is enumerated in the module that owns
  #       it, so this file is not the place the addition would be discovered late.
  validation {
    condition = length(var.service_credential_names) == 16 && setunion(var.service_credential_names, [
      "carddemo_auth",
      "carddemo_account",
      "carddemo_card",
      "carddemo_ledger",
      "carddemo_reference",
      "carddemo_batch",
      "carddemo_authorization",
      "carddemo_reporting",
      "carddemo_auth_migrator",
      "carddemo_account_migrator",
      "carddemo_card_migrator",
      "carddemo_ledger_migrator",
      "carddemo_reference_migrator",
      "carddemo_batch_migrator",
      "carddemo_authorization_migrator",
      "carddemo_verifier",
      ]) == toset([
      "carddemo_auth",
      "carddemo_account",
      "carddemo_card",
      "carddemo_ledger",
      "carddemo_reference",
      "carddemo_batch",
      "carddemo_authorization",
      "carddemo_reporting",
      "carddemo_auth_migrator",
      "carddemo_account_migrator",
      "carddemo_card_migrator",
      "carddemo_ledger_migrator",
      "carddemo_reference_migrator",
      "carddemo_batch_migrator",
      "carddemo_authorization_migrator",
      "carddemo_verifier",
    ])
    error_message = "service_credential_names must be exactly the sixteen LOGIN roles data-migration/sql/V0__schemas_and_roles.sql creates: the eight runtime roles carddemo_auth, carddemo_account, carddemo_card, carddemo_ledger, carddemo_reference, carddemo_batch, carddemo_authorization and carddemo_reporting, plus the seven migration roles carddemo_auth_migrator, carddemo_account_migrator, carddemo_card_migrator, carddemo_ledger_migrator, carddemo_reference_migrator, carddemo_batch_migrator and carddemo_authorization_migrator, plus the one verification role carddemo_verifier. Note the carddemo_ prefix: \"ledger\" is the schema name, \"carddemo_ledger\" is the role a service connects as, and \"carddemo_ledger_owner\" is the role that owns the schema. All EIGHT owner roles are excluded because each is created NOLOGIN and has no credential. A secret under any other name is a credential no service reads, and a role left without one is a service that cannot authenticate."
  }
}

# -----------------------------------------------------------------------------
# Lifecycle
# -----------------------------------------------------------------------------

# Trade-offs: this is the sharpest trade-off in the module and both sides are
#   real, which is why it is a variable rather than a constant. A non-zero window
#   is protective: a secret deleted by mistake stays recoverable for that many
#   days. But Secrets Manager keeps the deleted secret's NAME reserved for the
#   whole window, and for this stack that lands squarely on an acceptance
#   criterion -- `destroy` must tear the stack down cleanly and a later `apply`
#   must provision it cleanly. With a 30-day window the destroy succeeds and the
#   next apply fails on every secret, because a secret with each name is still
#   scheduled for deletion; setting 0 force-deletes immediately, making the cycle
#   repeatable but the deletion unrecoverable. So dev sets 0 and can be rebuilt at
#   will while prod keeps a real window, retention and protection flags being
#   exactly the axis on which the two environments legitimately differ.
# Assumptions: the default is the PROTECTED value, not the convenient one. A root
#   that forgets to set this gets recoverable deletion, and the destructive
#   setting must be asked for explicitly; 30 is also the service's own default, so
#   the module adds no surprise of its own.
variable "recovery_window_in_days" {
  description = <<-EOT
    Days a deleted secret remains recoverable before Secrets Manager removes it
    permanently, or 0 to delete immediately with no recovery. Defaults to the
    protected value. Set 0 only in an environment that is expected to be
    destroyed and recreated under the same secret names, because a non-zero
    window keeps those names reserved until it expires and a recreating apply
    will fail while it does.
  EOT
  type        = number
  default     = 30

  validation {
    condition     = var.recovery_window_in_days == 0 || (var.recovery_window_in_days >= 7 && var.recovery_window_in_days <= 30)
    error_message = "The recovery_window_in_days must be 0 for immediate deletion, or 7 to 30 inclusive; Secrets Manager accepts no other value."
  }
}

# Assumptions: this module does NOT implement rotation and does not create a
#   rotation function. Its remit is the credential entries themselves; the only
#   rotation owned anywhere in this package is KMS KEY rotation, in
#   infra/modules/kms. This input is a pass-through hook -- a root that has a
#   rotation function supplies its ARN and main.tf attaches a rotation schedule to
#   every service secret, and a root that has none leaves it null -- so the
#   presence of this variable is not a claim that rotation ships working.
# Alternatives Considered: packaging and creating a rotation Lambda here, which
#   this module once did. Removed because it pulled five cross-module coordinates
#   into the input contract (cluster ARN, RDS-managed master secret ARN, writer
#   endpoint, port and database name) plus a log-group key, a retention value and
#   an IAM permissions boundary -- inputs that existed only to serve a function
#   this module has no remit to own -- and required a third Terraform provider to
#   build the deployment package. The accepted cost is that a deployment wanting
#   rotation supplies a function from its own root, which is the honest position:
#   an alternating-user rotation function needs Data API access to the cluster and
#   read access to the RDS-managed master secret, both of which the ROOT holds.
variable "rotation_lambda_arn" {
  description = <<-EOT
    ARN of an operator-supplied Lambda function that rotates the service
    credentials, or null to configure no rotation. This module does not create a
    rotation function; it only attaches a rotation schedule when both this input
    and rotation_automatically_after_days are supplied. Nullable with a null
    default, so a root that has no rotation function still applies cleanly.
  EOT
  type        = string
  nullable    = true
  default     = null

  # Trade-offs: the check asserts the ARN's SHAPE rather than that the
  #   function exists or that it implements the Secrets Manager rotation
  #   protocol, neither of which a plan-time string check can establish. It
  #   is still worth having, because the commonest failure is a cross-wired
  #   root passing some other module's ARN, and that surfaces here with the
  #   variable's name instead of as an opaque Secrets Manager API rejection
  #   partway through apply.
  validation {
    condition     = var.rotation_lambda_arn == null || can(regex("^arn:[a-z0-9-]+:lambda:", var.rotation_lambda_arn))
    error_message = "The rotation_lambda_arn must be null, or a Lambda function ARN of the form arn:<partition>:lambda:..."
  }
}

# Assumptions: nullable with a null default, and meaningful only alongside
#   rotation_lambda_arn. Nothing in this module rotates a value on its own,
#   so an interval without a function to run is a setting with no effect --
#   which is why the two are supplied together or not at all, and why main.tf
#   creates a rotation schedule only when both are present.
variable "rotation_automatically_after_days" {
  description = <<-EOT
    Interval in days between automatic service-credential rotations, applied
    only when rotation_lambda_arn is also supplied. Null -- the default --
    configures no rotation schedule at all, which is the state of both
    environment roots in this package.
  EOT
  type        = number
  nullable    = true
  default     = null

  validation {
    condition     = var.rotation_automatically_after_days == null || (floor(var.rotation_automatically_after_days) == var.rotation_automatically_after_days && var.rotation_automatically_after_days >= 1 && var.rotation_automatically_after_days <= 1000)
    error_message = "The rotation_automatically_after_days must be null, or a whole number from 1 through 1000."
  }

  # Assumptions: the pair is all-or-nothing, and this is the check that
  #   makes that mean "both or neither" rather than "whatever arrives". An
  #   interval with no function does nothing, and a function with no interval
  #   is a schedule Secrets Manager refuses to create -- so a root that wires
  #   one and forgets the other would otherwise apply cleanly and rotate
  #   nothing, silently.
  validation {
    condition     = (var.rotation_lambda_arn == null) == (var.rotation_automatically_after_days == null)
    error_message = "The rotation_lambda_arn and rotation_automatically_after_days must be supplied together or both left null: an interval with no function rotates nothing, and a function with no interval is not a schedule."
  }
}

# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

# Trade-offs: tags arrive as an input and main.tf applies them to each secret
#   individually, which is more repetitive than the alternative and is
#   nonetheless the only option available. Only a ROOT may configure a provider,
#   so a reusable module cannot state its tags once in a `default_tags` block the
#   way the sibling bootstrap root does -- infra/modules/secrets/versions.tf
#   deliberately declares no provider block, and that absence is what creates the
#   obligation here. Per-resource tagging also keeps the module usable from any
#   root regardless of whether that root sets default_tags, and where a root does
#   set them the two merge rather than conflict.
variable "tags" {
  description = <<-EOT
    Tags applied to every secret this module creates, merged by the caller into
    whatever this stack's common tag set contains. Applied per resource in
    main.tf because a module cannot configure a provider and therefore cannot
    use default_tags. Defaults to empty so the module imposes no tag of its own.
  EOT
  type        = map(string)
  default     = {}
}
