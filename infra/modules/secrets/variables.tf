# =============================================================================
# infra/modules/secrets/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the COMPLETE input surface of the `secrets` module -- the module
#   that provisions the Secrets Manager entries holding one credential per
#   service database role, together with the customer-managed-key association
#   and deletion-recovery behaviour those entries are created under.
#
#   The defining property of this file is a thing it does NOT contain. No
#   variable declared here accepts a password, a secret string, a credential
#   value, a certificate, a private key, or a path to one. Every credential this
#   module manages is GENERATED ephemerally and written through a write-only
#   provider argument, so a credential value never appears in this repository in
#   any form and is never retained in state. That omission is deliberate and
#   load-bearing: it is the mechanism that makes "no secrets committed to the
#   repository" a structural property of the configuration rather than something
#   a reviewer has to notice.
#
#   The variables here are therefore all NON-SECRET: naming components, the key
#   to encrypt under, generation parameters (how long a password should be,
#   which roles need one), the master role's login NAME, lifecycle settings, and
#   tags. Every one of them is safe to commit to
#   `infra/envs/<env>/terraform.tfvars`, which is precisely why the module's
#   inputs were chosen to be only these.
#
# Parameters:
#   name_prefix                       - string. First segment of every secret
#                                       name this module composes.
#   environment                       - string. Environment segment of every
#                                       secret name; no default, so a calling
#                                       root must state it.
#   kms_key_arn                       - string. The customer-managed key the
#                                       secret values are encrypted under.
#   database_master_username          - string. Login NAME of the cluster's
#                                       master role, recorded in each credential
#                                       document. An identifier, not a secret.
#   password_length                   - number. Character count for each
#                                       generated password.
#   service_credential_names          - set(string). One credential is created
#                                       per name in this set.
#   recovery_window_in_days           - number. Days a deleted secret stays
#                                       recoverable, or 0 for none.
#   rotation_lambda_arn               - string, nullable. ARN of an
#                                       operator-supplied rotation function, or
#                                       null to configure no rotation.
#   rotation_automatically_after_days - number, nullable. Rotation interval,
#                                       meaningful only alongside
#                                       rotation_lambda_arn.
#   tags                              - map(string). Tags applied to every
#                                       secret this module creates.
#
# Return values:
#   None. A variables file declares no output. The module's outputs -- the
#   secret ARNs and names the calling root wires into the ECS task definitions
#   -- are declared in infra/modules/secrets/outputs.tf, each with its own
#   `description`.
#
# Errors / Exceptions:
#   Each `validation` block below turns a class of misconfiguration into a
#   `terraform plan` error with an actionable message, instead of an AWS API
#   rejection partway through `apply`. That distinction is the reason the
#   checks are worth their length: `apply` creates resources in dependency
#   order, so a value the service rejects late leaves a partially-created
#   stack behind, whereas a `plan`-time failure creates nothing at all.
#   `environment` and `kms_key_arn` declare no default, so omitting either is
#   itself a `plan`-time error naming the missing variable.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: a `master_password` (or `secret_string`, or
#     `secrets_map`, or a `service_tls_certificate`/`service_tls_private_key`
#     PEM pair) input was considered and REJECTED. Each is the obvious way to
#     model this module and each is the reason the module exists in this shape.
#     A value that cannot be supplied cannot be committed; a value that can be
#     supplied relies on every future contributor choosing not to put it in a
#     tfvars file. The detailed rationale, and the baseline defect it replaces,
#     are encoded by the absence of any credential-valued input.
#   - Assumptions: this file is parsed as part of a MODULE, never a root. Its
#     provider and CLI constraints come from
#     infra/modules/secrets/versions.tf, and its values come from
#     infra/envs/dev/main.tf and infra/envs/prod/main.tf. Nothing here reads
#     the AWS API, so no `data` source and no region or account identifier
#     appears in this file.
#   - Assumptions: the non-secret CONNECTION coordinates -- writer endpoint,
#     listener port and database name -- are deliberately NOT inputs here. The
#     aurora-postgresql module publishes them to Parameter Store under the same
#     `<prefix>/<environment>/aurora` path this module composes its secret names
#     from, so a consumer reads the coordinates from Parameter Store and only
#     the credential from Secrets Manager. Accepting them here as well would
#     make this module a second, silently divergent copy of coordinates another
#     module already owns.
#   - Trade-offs: every variable that is a name segment carries both a charset
#     check and a length ceiling, which is more validation than a reader might
#     expect for a string. The charset checks are a deliberate SUBSET of what
#     Secrets Manager itself accepts, because the same prefix and environment
#     values are reused as name components by sibling modules whose services
#     are stricter than Secrets Manager. Validating the strict subset here
#     means one set of values works across the whole stack; validating only
#     what Secrets Manager accepts would let a value through that a sibling
#     module rejects later in the same apply.
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

  # WHY : Trade-offs: the ceiling is 32 rather than the 512 characters a
  #       Secrets Manager name allows. 512 is not a useful bound here: this
  #       prefix is only the FIRST of three composed segments, and the same
  #       value is reused by sibling modules whose services cap names far
  #       lower than Secrets Manager does. A 32-character budget leaves room
  #       for the environment and per-credential segments under those stricter
  #       caps. Validating against 512 would accept a prefix that composes
  #       into a legal secret name and an illegal name elsewhere in the stack.
  validation {
    condition     = can(regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$", var.name_prefix))
    error_message = "The name_prefix must be lowercase letters, digits and single interior hyphens, starting with a letter -- for example \"carddemo\"."
  }

  validation {
    condition     = length(var.name_prefix) >= 2 && length(var.name_prefix) <= 32
    error_message = "The name_prefix must be 2 to 32 characters, so the composed secret name also fits the stricter name limits of sibling modules."
  }
}

# WHY : Assumptions: this module has an `environment` variable even though
#       infra/bootstrap/variables.tf deliberately has none, and the contrast is
#       intentional rather than an inconsistency. The bootstrap root provisions
#       the remote-state backend and is applied ONCE PER AWS ACCOUNT, ahead of
#       both environment roots, so it has no environment to be parameterized by.
#       This module is instantiated ONCE PER ENVIRONMENT, from
#       infra/envs/dev/main.tf and infra/envs/prod/main.tf, and both instances
#       coexist in the same account -- so the environment segment is what keeps
#       the dev and prod secret names from colliding.
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

  # WHY : Trade-offs: the 16-character ceiling is a name-segment budget for the
  #       same reason as name_prefix above, not a service limit. It is stated
  #       separately from the charset check so a failure reports which of the
  #       two rules was broken rather than one combined message that leaves the
  #       operator guessing.
  validation {
    condition     = length(var.environment) <= 16
    error_message = "The environment must be 16 characters or fewer, so the composed secret name also fits the stricter limits of sibling modules."
  }
}

# -----------------------------------------------------------------------------
# Encryption
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: resolving this key inside the module with a
#       `data "aws_kms_key"` lookup on an alias was considered and rejected.
#       The four customer-managed keys (Aurora, S3, Secrets Manager, SQS) are
#       created by infra/modules/kms, and the calling env root already holds
#       that module's outputs. Looking the key up here would duplicate
#       knowledge of how it is named, giving two independent places that must
#       agree about the alias -- the Terraform form of the single-sourcing
#       discipline the house test convention states as "never duplicate a
#       layout; keep it single-sourced" (tests/README.md section 12, item 4).
#       Passing the ARN in also makes the dependency explicit in the root's
#       graph, so the key is provably created before the secrets reference it.
# WHY : Assumptions: there is deliberately NO default. The only two candidate
#       defaults are both wrong: a literal ARN cannot appear in this repository
#       at all, and `null` would silently fall back to the AWS-managed
#       `aws/secretsmanager` key, quietly losing the customer-managed-key
#       property the target security posture requires. A missing required
#       variable fails at plan and names itself; a null default would apply
#       successfully and be wrong.
variable "kms_key_arn" {
  description = <<-EOT
    ARN of the customer-managed KMS key that the values of every secret this
    module creates are encrypted under. Supplied by the calling environment
    root from the `kms` module's Secrets Manager key output. Required: there is
    no default, because falling back to the AWS-managed key would silently
    abandon customer-managed encryption for these values.
  EOT
  type        = string

  # WHY : Trade-offs: this asserts the ARN's SHAPE, not its identity. It
  #       catches a cross-wired root that passes a non-KMS ARN (a queue or
  #       cluster output, say), which would otherwise surface as an opaque API
  #       error during apply. It cannot distinguish the Secrets Manager key
  #       from the other three keys the kms module produces, because all four
  #       are KMS key ARNs -- that wiring is verified by the root's own tests,
  #       not here. Stating the limit matters so nobody reads this check as
  #       stronger than it is. The literal "arn:" and ":kms:" fragments are
  #       format assertions carrying no partition, account or key identifier,
  #       so they are not ARN literals.
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
# WHY : Alternatives Considered: the same reasoning retired a PEM pair
#       (`service_tls_certificate` and `service_tls_private_key`) that this
#       module previously accepted so it could copy the values into two Secrets
#       Manager entries. A private key is credential material, so those inputs
#       reopened exactly the hole every other input here is shaped to close, and
#       `sensitive = true` on them changed only how the plan RENDERED the value,
#       not whether a tfvars file or a state file could hold it. The material now
#       stops at aws_acm_certificate in the calling root, which is the service
#       purpose-built to custody a private key: ACM accepts the key once and
#       never re-exports it, so there is no second copy for this module to hold.
#       Rejected alternative: keeping the inputs and relying on both roots
#       leaving them null -- which is what they in fact did, and which is a
#       convention rather than a control.
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

# WHY : Assumptions: a username is an identifier and a password is a
#       credential, so they are treated differently on purpose, and the
#       asymmetry is worth stating because it looks at first like an
#       inconsistency. This variable exists, has a default and is safe to
#       commit; its password counterpart does not exist at all. Knowing the
#       login name of a database reachable only from the private application
#       subnets grants nothing on its own -- authentication is what the
#       generated password protects. Treating the name as a secret would also
#       be self-defeating: it has to be legible in the Aurora cluster
#       definition, in connection strings and in operator runbooks.
# WHY : Assumptions: the default matches infra/modules/aurora-postgresql's
#       `master_username` default character for character. The two must agree,
#       because the name recorded in a credential document is the role an
#       operator-supplied rotation function escalates through; a mismatch would
#       apply cleanly and then fail at the first rotation against a role that
#       does not exist. Keeping the defaults identical means an environment root
#       that overrides neither is consistent by construction.
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

  # WHY : Assumptions: the same lower-case PostgreSQL identifier rule that
  #       infra/modules/aurora-postgresql/variables.tf applies to the cluster's
  #       master_username, restated here because PostgreSQL folds unquoted
  #       identifiers: a role name carrying capitals only matches when quoted,
  #       so a credential document naming "Carddemo_Admin" would address a
  #       different role than the one that actually exists.
  validation {
    condition     = can(regex("^[a-z][a-z0-9_]*$", var.database_master_username)) && length(var.database_master_username) <= 63
    error_message = "The database_master_username must be 1 to 63 characters of lower-case letters, digits and underscores, beginning with a letter."
  }

  # WHY : Assumptions: "rdsadmin" is reserved by RDS for its own management
  #       user, so a cluster can never have it as a master role. Rejecting it
  #       here -- rather than letting the mismatch surface at the first rotation
  #       -- is worth a check because it is a plausible thing for someone to
  #       try, and the same rejection is made at the cluster itself in
  #       infra/modules/aurora-postgresql/variables.tf.
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

  # WHY : Trade-offs: the two bounds have different natures and only one is a
  #       service limit. The floor of 32 is a POLICY floor: the engine accepts
  #       8, and this module refuses to generate anything that short because
  #       these credentials are machine-generated and machine-consumed, so a
  #       shorter password buys no human convenience to weigh against it. The
  #       ceiling of 128 IS the engine limit, and it is the more important half
  #       of the check: a longer value generates and stores in Secrets Manager
  #       perfectly happily, then fails when the Aurora cluster is created with
  #       it -- after the key, the secrets and the networking already exist. The
  #       apply stops with a partially-created stack, which is far more work to
  #       resolve than the plan-time error this converts it into. The default of
  #       32 sits on the floor so the shortest value the module will generate is
  #       also the value it generates unless a root asks for more.
  validation {
    condition     = var.password_length >= 32 && var.password_length <= 128
    error_message = "The password_length must be 32 to 128 characters: 32 is this module's policy floor for a machine-generated credential and 128 is the engine's own ceiling."
  }
}

# WHY : Refactoring Rationale: this input previously defaulted to the EMPTY
#       set, on the reasoning that a default here would be a second copy of the
#       service inventory and a copy is what drifts. That reasoning was wrong in
#       one specific way, and the correction is recorded rather than quietly
#       applied. An empty default is not neutral: it produces a plan and an apply
#       that both succeed while creating NOT ONE service credential, so every
#       service then starts, fails to resolve its secret, and reports a
#       configuration error against infrastructure that reported success. The
#       inventory is also not a matter of taste -- the eight bounded contexts and
#       their role names are fixed by
#       data-migration/sql/V0__schemas_and_roles.sql, which every service's
#       datasource username has to match character for character -- so the honest
#       expression of it is a default that states the eight names plus a
#       validation that refuses anything else, not an empty set the caller is
#       trusted to fill in correctly.
# WHY : Assumptions: drift is answered by the validation rather than by the
#       absence of a default. A ninth bounded context now fails HERE at plan
#       time, naming this variable, instead of applying cleanly against a stale
#       inventory; that is the opposite of the silent staleness the empty default
#       was chosen to avoid. The same closed-inventory treatment is applied to the
#       repository set in infra/modules/ecr, the dataset prefixes in
#       infra/modules/s3-datasets and the listener rules in infra/modules/alb, so
#       the four places the topology is enumerated all fail the same way.
# WHY : Assumptions: the set contains only role NAMES, so it is non-secret and
#       belongs in source. What it must never contain is a password: main.tf
#       generates each credential ephemerally during apply and writes it straight
#       to Secrets Manager.
variable "service_credential_names" {
  description = <<-EOT
    Names of the per-service database roles that each need their own generated
    credential; one secret is created per element. Fixed at the eight roles
    data-migration/sql/V0__schemas_and_roles.sql creates: the seven that own one
    schema per bounded context (carddemo_auth, carddemo_account, carddemo_card,
    carddemo_ledger, carddemo_reference, carddemo_batch and
    carddemo_authorization) plus carddemo_reporting, the read-only role the
    reporting service connects as. Note the carddemo_ prefix: these are ROLE
    names, not the bare schema names, and the two are not interchangeable --
    "ledger" is the schema and carddemo_ledger is the role that owns it.
    carddemo_reporting_owner is deliberately NOT accepted: it owns the reporting
    schema, is created NOLOGIN, and so has no credential to generate. Defaulted
    and validated rather than left to the caller, because a service whose secret
    was never created starts and then fails to resolve it.
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
  ]
  nullable = false

  # WHY : Assumptions: membership in a closed list is checked rather than a name
  #       SHAPE, because shape was never the real constraint. The eight names are
  #       fixed by the bootstrap SQL that creates the roles and binds their
  #       credentials, so any other name is wrong however well-formed it is. A
  #       shape check accepted "ledger", "carddemo_Ledger" was already caught by
  #       the lowercase rule, and "carddemo_reports" would have passed silently --
  #       and only the closed list rejects all three.
  # WHY : Trade-offs: this module now holds a copy of the eight role names,
  #       which is exactly the duplication the default above declines to make, and
  #       the two decisions are not in conflict. A stale DEFAULT applies cleanly
  #       and provisions the wrong set silently; a stale VALIDATION LIST fails the
  #       plan with a message naming the value it rejected, so a ninth bounded
  #       context added to the bootstrap SQL and not to this list stops the apply
  #       at review time rather than half-provisioning. The copy is safe in
  #       precisely the direction the default was not.
  # WHY : Assumptions: a SUBSET is accepted rather than all eight demanded. A
  #       root that provisions a partial stack, or one environment brought up
  #       ahead of another, is a coherent state this module should not forbid; the
  #       bootstrap SQL is where the requirement that ALL eight roles be bound is
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
      ], role_name)
    ])
    error_message = "Each service_credential_names element must be lowercase letters, digits and underscores, start with a letter, and be at most 63 characters -- for example \"carddemo_ledger\"."
  }

  # WHY : Assumptions: the eight role names are a contract with a SQL artifact,
  #       not a naming convention. data-migration/sql/V0__schemas_and_roles.sql
  #       creates exactly these eight roles, and its own header states that the
  #       names are the source of truth: the datasource username each service
  #       resolves and the secret entry each credential is written to must match
  #       them character for character. A secret created under any other name is
  #       therefore a credential no service reads, and a role left without one is a
  #       service that cannot authenticate -- and BOTH states apply cleanly, which
  #       is what makes an unvalidated inventory here expensive.
  #       Assumptions: equality needs both halves. The setunion comparison alone
  #       proves only that every name supplied is one of the eight, so a root
  #       creating a single credential would satisfy it; the count closes that,
  #       because a set holds no duplicates, so eight members drawn from a set of
  #       eight is that set exactly.
  #       Trade-offs: a ninth bounded context now needs an edit here. Accepted:
  #       that context also needs a schema and a role in the bootstrap SQL, a
  #       repository in infra/modules/ecr and, if it is online, a listener rule in
  #       infra/modules/alb, each of which is enumerated in the module that owns
  #       it, so this file is not the place the addition would be discovered late.
  validation {
    condition = length(var.service_credential_names) == 8 && setunion(var.service_credential_names, [
      "carddemo_auth",
      "carddemo_account",
      "carddemo_card",
      "carddemo_ledger",
      "carddemo_reference",
      "carddemo_batch",
      "carddemo_authorization",
      "carddemo_reporting",
      ]) == toset([
      "carddemo_auth",
      "carddemo_account",
      "carddemo_card",
      "carddemo_ledger",
      "carddemo_reference",
      "carddemo_batch",
      "carddemo_authorization",
      "carddemo_reporting",
    ])
    error_message = "service_credential_names must be exactly the eight roles data-migration/sql/V0__schemas_and_roles.sql creates: carddemo_auth, carddemo_account, carddemo_card, carddemo_ledger, carddemo_reference, carddemo_batch, carddemo_authorization and carddemo_reporting. Note the carddemo_ prefix: \"ledger\" is the schema name and the role that owns it is \"carddemo_ledger\". carddemo_reporting_owner is excluded because it is created NOLOGIN and has no credential. A secret under any other name is a credential no service reads, and a role left without one is a service that cannot authenticate."
  }
}

# -----------------------------------------------------------------------------
# Lifecycle
# -----------------------------------------------------------------------------

# WHY : Trade-offs: this is the sharpest trade-off in the module, and both
#       sides are real, which is why it is a variable rather than a constant.
#       A non-zero window is protective: a secret deleted by mistake stays
#       recoverable for that many days. But Secrets Manager keeps the deleted
#       secret's NAME reserved for the whole window, and its own documentation
#       states the consequence directly -- deleting a secret and immediately
#       creating one with the same name needs back-off and retry logic. For
#       this stack that lands squarely on an acceptance criterion: `destroy`
#       must tear the stack down cleanly and a later `apply` must provision it
#       cleanly. With a 30-day window the destroy succeeds, and the next apply
#       fails on every secret, because a secret with each name is still
#       scheduled for deletion. Setting 0 force-deletes immediately, making the
#       cycle repeatable but the deletion unrecoverable. So the axis is genuine:
#       dev sets 0 and can be rebuilt at will, prod keeps a real window and is
#       protected -- retention and protection flags being exactly the axis on
#       which the two environments legitimately differ.
# WHY : Assumptions: the default is the PROTECTED value, not the convenient
#       one. A root that forgets to set this gets recoverable deletion; the
#       destructive setting must be asked for explicitly. Choosing 0 as the
#       default would make an omission in a production root silently
#       unrecoverable, which is the wrong direction to fail in. 30 is also the
#       service's own default, so the module adds no surprise of its own.
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

# WHY : Assumptions: this module does NOT implement rotation and does not create
#       a rotation function. Its remit is the credential entries themselves; the
#       only rotation in this infrastructure package that is owned anywhere is
#       KMS KEY rotation, which belongs to infra/modules/kms and its four
#       customer-managed keys. This input is therefore a pass-through hook: a
#       root that has a rotation function supplies its ARN and main.tf attaches
#       a rotation schedule to every service secret; a root that has none leaves
#       it null and no rotation is configured. Do not read the presence of this
#       variable as a claim that rotation ships working.
# WHY : Alternatives Considered: this module previously PACKAGED AND CREATED a
#       Python rotation Lambda of its own, together with an execution role, an
#       inline policy, a log group, an invoke permission and an archive of the
#       function source. That was removed. It pulled five cross-module
#       coordinates into this module's input contract (the cluster ARN, the
#       RDS-managed master secret ARN, the writer endpoint, the port and the
#       database name) plus a log-group key, a log retention value and an IAM
#       permissions boundary -- eight inputs that existed only to serve a
#       function this module has no remit to own -- and it required a third
#       Terraform provider to build the deployment package. Accepting an ARN
#       instead keeps the boundary where the module's remit actually ends.
# WHY : Trade-offs: the accepted cost is that a deployment which wants rotation
#       must supply a function from its own root. That is the honest position:
#       an alternating-user rotation function needs Data API access to the
#       cluster and read access to the RDS-managed master secret, both of which
#       the ROOT holds and this module deliberately does not.
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

  # WHY : Trade-offs: the check asserts the ARN's SHAPE rather than that the
  #       function exists or that it implements the Secrets Manager rotation
  #       protocol, neither of which a plan-time string check can establish. It
  #       is still worth having, because the commonest failure is a cross-wired
  #       root passing some other module's ARN, and that surfaces here with the
  #       variable's name instead of as an opaque Secrets Manager API rejection
  #       partway through apply.
  validation {
    condition     = var.rotation_lambda_arn == null || can(regex("^arn:[a-z0-9-]+:lambda:", var.rotation_lambda_arn))
    error_message = "The rotation_lambda_arn must be null, or a Lambda function ARN of the form arn:<partition>:lambda:..."
  }
}

# WHY : Assumptions: nullable with a null default, and meaningful only alongside
#       rotation_lambda_arn. Nothing in this module rotates a value on its own,
#       so an interval without a function to run is a setting with no effect --
#       which is why the two are supplied together or not at all, and why main.tf
#       creates a rotation schedule only when both are present.
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

  # WHY : Assumptions: the pair is all-or-nothing, and this is the check that
  #       makes that mean "both or neither" rather than "whatever arrives". An
  #       interval with no function does nothing, and a function with no interval
  #       is a schedule Secrets Manager refuses to create -- so a root that wires
  #       one and forgets the other would otherwise apply cleanly and rotate
  #       nothing, silently.
  validation {
    condition     = (var.rotation_lambda_arn == null) == (var.rotation_automatically_after_days == null)
    error_message = "The rotation_lambda_arn and rotation_automatically_after_days must be supplied together or both left null: an interval with no function rotates nothing, and a function with no interval is not a schedule."
  }
}

# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

# WHY : Trade-offs: tags arrive as an input and main.tf applies them to each
#       secret individually, which is more repetitive than the alternative and
#       is nonetheless the only option available. The sibling bootstrap root
#       states its tags once, in a provider `default_tags` block, and every
#       taggable resource inherits them. Only a ROOT may configure a provider,
#       so a reusable module cannot do that -- infra/modules/secrets/versions.tf
#       deliberately declares no provider block, and that absence is what
#       creates the obligation here. Per-resource tagging also keeps the module
#       usable from any root regardless of whether that root sets default_tags,
#       and where a root does set them the two merge rather than conflict.
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
