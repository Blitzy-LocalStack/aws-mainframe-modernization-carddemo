# =============================================================================
# infra/modules/aurora-postgresql/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input contract of the aurora-postgresql module -- every value
#   a calling root may supply, and nothing else. The module provisions the
#   Aurora PostgreSQL Serverless v2 cluster that replaces the ten VSAM KSDS
#   base clusters and three alternate indexes of the CardDemo mainframe
#   application, each of which was formerly created by an in-stream IDCAMS
#   DEFINE CLUSTER such as app/jcl/ACCTFILE.jcl:36 (KEYS(11 0),
#   RECORDSIZE(300 300), INDEXED), app/jcl/TRANFILE.jcl:49 (KEYS(16 0),
#   RECORDSIZE(350 350)) and app/jcl/DUSRSECJ.jcl:64 (KEYS(8,0),
#   RECORDSIZE(80,80)). Those baseline trees are REFERENCE-ONLY: they are
#   cited throughout this file to ground a decision, and never edited.
#
#   This file is also where the HCL half of the project documentation
#   obligation is discharged for this module. HCL has no docstring construct,
#   so the equivalent -- per the "HCL (Terraform)" section of
#   docs/CODE_DOCUMENTATION_STANDARD.md -- is a header block in every .tf
#   file plus a `description` on every `variable`. A Terraform variable IS a
#   module's public parameter, and `description` is the only place HCL lets it
#   carry documentation, so the description on each block below is that
#   parameter's docstring rather than a courtesy.
#
# Parameters:
#   Twenty-six inputs, each declared below with an explicit `type` and a
#   `description` that states what the value means, its unit or bound where it
#   has one, and what downstream behaviour depends on it. They fall into six
#   groups, in the order they appear:
#
#     Naming and tagging ...... name_prefix, environment, tags
#     Consumed contract ....... isolated_subnet_ids, security_group_ids,
#                               kms_key_arn, master_credential_secret_arn
#     Engine and database ..... engine_version, parameter_group_family,
#                               cluster_parameters, database_name,
#                               master_username, port
#     Serverless v2 capacity .. min_capacity, max_capacity,
#                               seconds_until_auto_pause
#     Durability and
#     protection .............. backup_retention_period,
#                               preferred_backup_window,
#                               preferred_maintenance_window,
#                               deletion_protection, skip_final_snapshot,
#                               copy_tags_to_snapshot, apply_immediately
#     Observability ........... performance_insights_enabled,
#                               performance_insights_retention_period,
#                               enabled_cloudwatch_logs_exports
#
#   The four inputs in the consumed group are not authored values: they are
#   identifiers produced by the network, kms and secrets modules and passed
#   through by whichever environment root wires the three together. Ten inputs
#   carry no `default` at all, which is what forces a root to state them.
#
# Returns:
#   None, and the absence is deliberate rather than a section omitted. A
#   variables.tf declares inputs only; this module's return values are
#   `output` blocks in outputs.tf, which is where the
#   terraform_standard_module_structure rule in infra/.tflint.hcl requires
#   them to live. Nothing declared in this file is readable by a caller.
#
# Errors:
#   Every error this file can raise comes from a `validation` block, and all of
#   them are raised before any resource exists and before any credential is
#   resolved, so an invalid input fails closed rather than half-applied.
#   Because a module is never planned on its own, each message surfaces through
#   the environment root that supplied the value. The blocks guard five classes
#   of defect:
#
#     1. Shape ..... an identifier, ARN, engine version, time window or log
#                    type that AWS will reject at apply time is rejected here
#                    instead, naming the accepted form.
#     2. Range ..... a capacity, retention or port outside the bound the AWS
#                    API accepts.
#     3. Granularity a capacity that is not a half-unit multiple, or an
#                    auto-pause delay that is not a whole number of seconds.
#     4. Ordering .. a maximum capacity below the minimum.
#     5. Coupling .. the two conditional rules that only bite when the minimum
#                    capacity is zero. These are the ones a reader is most
#                    likely to get wrong, so see the capacity section below.
#
#   Each `error_message` names the constraint that was violated and the shape
#   that would satisfy it, because a message reading only "invalid value"
#   tells an operator nothing they did not already know from the failure.
#
#   WHEN a rule is checked is not uniform across the five classes, and the
#   difference was measured against this file rather than assumed. A
#   `validation` whose condition reads only its own variable is evaluated by
#   `terraform validate`. A `validation` whose condition reads ANOTHER variable
#   is deferred to `terraform plan`, because the evaluation context that lets
#   one variable see another does not exist at validate time. Exactly three
#   rules fall in the second group -- the ordering rule and both halves of the
#   coupling rule, all three of which read var.min_capacity -- so `terraform
#   validate` on its own reports a capacity pair of min 4 with max 2 as valid,
#   and `terraform plan` is what rejects it. That does not weaken the
#   invariant: both are pre-apply gates and `apply` always plans first, so no
#   resource is ever created from a pair that violates it. It does mean a
#   pipeline step that runs `validate` and never `plan` exercises the other
#   twenty-four rules but not those three, which is worth knowing before a
#   green `validate` is read as proof that the capacity pair is sound.
#
# WHY (non-obvious design decisions):
#   - Assumption: the capacity invariant is enforced HERE, in HCL, rather than
#     described in the module README. The four capacity facts this file
#     encodes -- the 0-to-256 ACU range, the half-unit granularity, the
#     300-to-86,400-second auto-pause window, and the rule that a zero minimum
#     makes auto-pause mandatory and forces a maximum of at least 1 -- are
#     properties of the Aurora Serverless v2 API, not house preferences. Prose
#     cannot fail a build, so a root that violated one would discover it as a
#     rejected argument during apply, partway through provisioning a data
#     tier.
#   - Alternatives Considered: hosting the cross-variable capacity rules as
#     `lifecycle { precondition }` blocks on the cluster resource in main.tf,
#     which the module design permits. Rejected: a precondition is evaluated
#     against a resource that is already being planned, so the failure is
#     attributed to the resource rather than to the input that caused it,
#     whereas a `validation` block names the offending variable directly. Each
#     rule is therefore enforced in exactly one place -- this file -- and
#     main.tf adds no precondition that would restate one, because the same
#     rule checked twice is two things to keep in step.
#   - Assumption: the cross-variable references all point ONE WAY, into
#     min_capacity. max_capacity and seconds_until_auto_pause each read
#     var.min_capacity, and min_capacity reads neither of them. Terraform
#     builds a dependency graph across variable validations, so a mutual
#     reference between two variables is a cycle and a hard error rather than
#     a subtle one; keeping min_capacity free of outgoing references is what
#     makes the graph acyclic.
#   - Refactoring Rationale: this module holds no credential input of any kind
#     -- no password, no token, no key material, not even marked sensitive.
#     The baseline did the opposite: app/jcl/DUSRSECJ.jcl:35-44 creates the
#     security file from in-stream literals committed to the repository, and
#     app/cpy/CSUSR01Y.cpy:L21 stores the password as plain text. Accepting a
#     credential as a variable here would reproduce that defect one layer up,
#     because a variable has to be given a value somewhere and that somewhere
#     is a tfvars file or a CI input. The master credential is instead
#     generated at apply time by infra/modules/secrets straight into Secrets
#     Manager, and this module receives only its ARN. An ARN names where a
#     secret lives; it is not the secret, which is why
#     master_credential_secret_arn is a legitimate input and a password would
#     not be.
#   - Alternatives Considered: a `region` or `aws_region` input, which many
#     published modules accept. Rejected, and its absence is load-bearing
#     rather than an oversight: this directory is a module, and provider
#     configuration -- region and default_tags included -- belongs to the
#     infra/envs/dev and infra/envs/prod roots that call it. versions.tf
#     declares no `provider` block for the same reason. An input here would
#     compete with the caller's own provider configuration and could not
#     change which region a resource lands in anyway.
#   - Alternatives Considered: read-replica, Aurora Global Database and cache
#     inputs, all three of which a reader might reasonably expect on a
#     database module and none of which is declared. Offering an input is what
#     makes a topology reachable, so declaring them would make out-of-scope
#     topologies buildable by a tfvars edit. Reporting reads reach the writer
#     through read-only cross-schema views, so a replica would add cost and
#     replica-lag semantics that the baseline has no equivalent of; the
#     deployment is single-region across three availability zones; and the
#     baseline has no cache tier, so adding one could only introduce a
#     staleness window that the golden-master parity suite would observe.
#   - Trade-off: `nullable = false` is declared on every input except the
#     three where a null is meaningful -- seconds_until_auto_pause,
#     preferred_backup_window and preferred_maintenance_window, for each of
#     which null means "not set" rather than "empty". The cost is three lines
#     per block; what it buys is that a null reaching a `validation`
#     expression fails with Terraform's own clear message about a null value
#     instead of failing obscurely inside an arithmetic or regex comparison
#     that was never written to expect one.
# =============================================================================


# -----------------------------------------------------------------------------
# Naming and tagging.
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = <<-EOT
    Common prefix from which every resource name in this module is composed --
    the cluster identifier, the DB subnet group, the cluster parameter group
    and the final snapshot identifier. Must satisfy the RDS cluster identifier
    grammar: 1 to 40 characters of lower-case letters, digits and hyphens,
    beginning with a letter, with no consecutive and no trailing hyphen.
    Typically "<project>-<environment>", for example "carddemo-dev".
  EOT
  type        = string
  nullable    = false

  # WHY : Assumption: the grammar checked here is the RDS DB cluster
  #       identifier grammar, not a naming preference, and RDS enforces it on
  #       the composed identifier rather than on this prefix. A single regex
  #       covers all four of its clauses at once: `[a-z]` fixes the leading
  #       character, the `(-[a-z0-9]+)*` group admits a hyphen only when at
  #       least one alphanumeric follows it, which rejects both a trailing
  #       hyphen and a doubled one, and the absence of A-Z rejects upper case.
  #       Checking the clauses separately would need four blocks to say the
  #       same thing.
  validation {
    condition     = can(regex("^[a-z][a-z0-9]*(-[a-z0-9]+)*$", var.name_prefix))
    error_message = "The name_prefix must consist of lower-case letters, digits and hyphens, must begin with a letter, and must contain no consecutive or trailing hyphen."
  }

  # WHY : Trade-off: the ceiling is 40 rather than the 63 RDS itself allows,
  #       because this is a prefix and the module appends to it. The longest
  #       suffix it composes reserves roughly twenty characters, so 40 leaves
  #       headroom for all four composed names while still accommodating a
  #       "<project>-<environment>" prefix comfortably. The accepted cost is
  #       that a caller with an unusually long project name is refused here,
  #       which is the cheaper failure: the alternative is a cluster
  #       identifier that exceeds 63 characters and is rejected by the API
  #       partway through an apply, after the subnet group and parameter group
  #       have already been created.
  validation {
    condition     = length(var.name_prefix) >= 1 && length(var.name_prefix) <= 40
    error_message = "The name_prefix must be between 1 and 40 characters so that the composed RDS identifiers stay within the 63-character limit."
  }
}

variable "environment" {
  description = <<-EOT
    Deployment environment this cluster belongs to, used in resource tagging
    and in composed names. Restricted to "dev" or "prod" because those are
    the only two environment roots the infrastructure defines. The two differ
    only in sizing and retention -- capacity, backup retention, and the
    deletion-protection and final-snapshot flags -- and never in topology.
  EOT
  type        = string
  nullable    = false

  # WHY : Assumption: the domain is closed at two values because the
  #       infrastructure defines exactly two environment roots, infra/envs/dev
  #       and infra/envs/prod, and several defaults in this file are chosen
  #       around that pair -- deletion_protection and skip_final_snapshot in
  #       particular are documented as an asymmetry BETWEEN those two. An open
  #       string would let a third environment inherit prod-shaped defaults
  #       that nobody reviewed for it. Widening the domain is a deliberate
  #       edit here, which is the point.
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "The environment must be either \"dev\" or \"prod\", matching the two environment roots under infra/envs/."
  }
}

variable "tags" {
  description = <<-EOT
    Additional tags merged onto the resources this module owns. Intended for
    module-specific or per-call additions only: account-wide tags are applied
    by the calling root's provider `default_tags` and must not be repeated
    here. Defaults to an empty map, so a caller that relies entirely on
    `default_tags` needs to pass nothing.
  EOT
  type        = map(string)
  nullable    = false
  default     = {}

  # WHY : Assumption: tagging responsibility is split, and the split is not
  #       visible from this module. The calling root configures `default_tags`
  #       on its provider, which the AWS provider applies to every taggable
  #       resource without this module doing anything, so a tag repeated in
  #       both places is redundant at best. Where the two disagree on the same
  #       key the resource-level value wins, which makes a duplicated key a
  #       silent override of an account-wide policy rather than an error --
  #       hence "must not be repeated" in the description rather than a
  #       validation block, since this module cannot see the caller's
  #       default_tags to check it.
}


# -----------------------------------------------------------------------------
# The consumed contract.
#
# None of the four inputs in this section is an authored value. Each is an
# identifier produced by a sibling module -- network, kms or secrets -- and
# passed through by the environment root that wires the three together. All
# four are therefore declared without a `default`: there is no value this
# module could invent that would be correct in any account, and a placeholder
# default would hard-code one account's infrastructure into a shared module.
# -----------------------------------------------------------------------------

variable "isolated_subnet_ids" {
  description = <<-EOT
    Identifiers of the isolated data-tier subnets the DB subnet group is built
    from, supplied by the network module. These must be the ISOLATED subnets --
    the ones with no route to the internet in either direction -- and not the
    private application subnets that carry the ECS tasks. At least two are
    required because a DB subnet group must span two availability zones; the
    environment roots supply three, one per zone of the three-zone VPC.
  EOT
  type        = list(string)
  nullable    = false

  # WHY : Assumption: this module assumes, and cannot verify, that the subnets
  #       it is handed have NO internet route at all -- no default route to an
  #       internet gateway and none to a NAT gateway. That isolation is the
  #       strongest blast-radius control in the whole design, and it is the
  #       reason the cluster needs no publicly-reachable posture of its own.
  #       The assumption is stated because a caller passing the private
  #       application subnets instead would still produce a working cluster and
  #       a clean plan while silently giving the data tier an egress path,
  #       which is a security regression that nothing downstream would report.
  #       The count check below cannot detect that substitution, so the
  #       description carries the requirement in words as well.
  validation {
    condition     = length(var.isolated_subnet_ids) >= 2
    error_message = "At least two isolated subnet identifiers are required, because an RDS DB subnet group must span at least two availability zones."
  }

  # WHY : Assumption: a repeated identifier is a real defect rather than
  #       untidiness. A DB subnet group derives its zone coverage from the
  #       distinct zones of its members, so a list of three ids in which two
  #       are the same covers fewer zones than its length suggests -- and it
  #       still satisfies the count check above, still plans, and still
  #       applies. The failure would only appear as reduced availability
  #       during a zone impairment, which is the worst possible time to
  #       discover it.
  validation {
    condition     = length(distinct(var.isolated_subnet_ids)) == length(var.isolated_subnet_ids)
    error_message = "The isolated_subnet_ids list must not repeat a subnet identifier, because duplicates reduce the availability zones the DB subnet group actually spans."
  }
}

variable "security_group_ids" {
  description = <<-EOT
    Identifiers of the security groups attached to the cluster, supplied by
    the network module, which owns the Aurora security group and its rules.
    The expected group permits ingress on the database port from the
    application tier only. At least one identifier is required.
  EOT
  type        = list(string)
  nullable    = false

  # WHY : Alternatives Considered: creating the security group inside this
  #       module, which is the more self-contained shape and is what many
  #       database modules do. Rejected because an Aurora security group is
  #       one half of a matched pair: the rule that admits the application
  #       tier here has a counterpart egress rule on the application security
  #       group, and the network module owns that side. Splitting the pair
  #       across two modules means a rule change needs edits in two places
  #       that no single plan checks against each other, and a group defined
  #       here could not reference an application group defined there without
  #       one module reaching into the other's state.
  # WHY : Assumption: the group handed in restricts ingress to the application
  #       tier on the database port. This module attaches whatever it is
  #       given, so it cannot check that; the check that DOES belong here is
  #       that the list is not empty, because an empty list is not a no-op --
  #       the cluster falls back to the VPC default security group, whose rules
  #       nobody in this tree authored or reviews.
  validation {
    condition     = length(var.security_group_ids) >= 1
    error_message = "At least one security group identifier is required, because an empty list makes the cluster fall back to the VPC default security group."
  }
}

variable "kms_key_arn" {
  description = <<-EOT
    ARN of the customer-managed KMS key that encrypts the cluster's storage,
    its automated backups and its snapshots, supplied by the kms module.
    Required with no default, so that encryption is always performed with a key
    this project owns and can rotate, audit and revoke.
  EOT
  type        = string
  nullable    = false

  # WHY : Refactoring Rationale: the baseline had no encryption at rest for
  #       this data at all. All eight DEFINE FILE stanzas in
  #       app/csd/CARDDEMO.CSD declare RECOVERY(NONE) and FWDRECOVLOG(NO)
  #       (lines 9, 21, 33, 46, 59, 72, 84 and 96) with no key management of
  #       any kind, so encrypting the replacement cluster is a correction of a
  #       baseline gap rather than a port of existing behaviour.
  # WHY : Assumption: this input is deliberately required rather than
  #       null-defaulted. A nullable key would let the cluster fall back to the
  #       AWS-managed aws/rds key, which still reports as "encrypted" to every
  #       policy scan while giving up the key policy, the rotation schedule and
  #       the ability to revoke access that a customer-managed key exists to
  #       provide. That is a silent downgrade, so the module refuses to guess.
  #       The shape check accepts any AWS partition, because a partition-
  #       specific pattern would break the module in GovCloud and China
  #       regions for no benefit here.
  validation {
    condition     = can(regex("^arn:aws[a-zA-Z-]*:kms:[a-z0-9-]+:[0-9]+:key/.+$", var.kms_key_arn))
    error_message = "The kms_key_arn must be a KMS key ARN of the form arn:aws:kms:<region>:<account-id>:key/<key-id>."
  }
}

variable "master_credential_secret_arn" {
  description = <<-EOT
    ARN of the Secrets Manager secret holding the cluster's master
    credential, supplied by the secrets module, which generates the value at
    apply time and writes it straight to Secrets Manager. This input is a
    REFERENCE to where the credential lives, never the credential itself, so
    it is not marked sensitive and carries no default.
  EOT
  type        = string
  nullable    = false

  # WHY : Refactoring Rationale: a reference is passed rather than a value
  #       because passing a value is precisely the defect being migrated away
  #       from. app/jcl/DUSRSECJ.jcl:35-44 builds the baseline security file
  #       from credential literals committed to the repository, and
  #       app/cpy/CSUSR01Y.cpy:L21 stores a password as plain text. A
  #       `variable` holding a credential -- even one marked sensitive with no
  #       default -- has to be given a value by some caller, which relocates
  #       the literal into a tfvars file or a CI input rather than removing it.
  #       Taking an ARN instead means no credential value appears anywhere in
  #       this repository, which is what makes the no-secrets-in-source
  #       constraint structurally true rather than merely observed.
  # WHY : Assumption: an ARN is not itself sensitive. It names a location and
  #       confers no access -- reading the secret it points at requires an IAM
  #       policy that this module does not grant -- so marking it sensitive
  #       would only redact it from plan output where a reviewer needs to see
  #       which secret is being wired in.
  validation {
    condition     = can(regex("^arn:aws[a-zA-Z-]*:secretsmanager:[a-z0-9-]+:[0-9]+:secret:.+$", var.master_credential_secret_arn))
    error_message = "The master_credential_secret_arn must be a Secrets Manager secret ARN of the form arn:aws:secretsmanager:<region>:<account-id>:secret:<name>."
  }
}


# -----------------------------------------------------------------------------
# Engine and database.
# -----------------------------------------------------------------------------

variable "engine_version" {
  description = <<-EOT
    Aurora PostgreSQL engine version for the cluster, for example a "16.x" or
    "17.x" release. Supplied by the environment root rather than defaulted, so
    that an engine upgrade is an explicit, reviewed change to one file. If the
    root sets min_capacity to 0, the version chosen must be one whose minor
    release supports scaling to zero capacity -- not every minor release does.
  EOT
  type        = string
  nullable    = false

  # WHY : Assumption: scaling to zero capacity is not available on every
  #       Aurora PostgreSQL minor release, and this module cannot check which
  #       release the caller named against that list. The pairing therefore has
  #       to be honoured by whoever sets the two values: an environment root
  #       that sets min_capacity to 0 on too old a minor release gets a
  #       rejected capacity argument at apply time, and the cause is this
  #       input rather than the capacity one that reports the error.
  # WHY : Alternatives Considered: defaulting this to a specific version
  #       string, which would let a caller omit it. Rejected on two counts. A
  #       default silently pins every environment to whatever release was
  #       current when this file was written, so the dev and prod clusters
  #       drift apart the moment one root overrides it and the other does not;
  #       and naming a specific minor release here would assert a
  #       scale-to-zero capability for that release that is not verified in
  #       this file. Only the shape is checked, so a family string such as
  #       "aurora-postgresql16" pasted into the wrong input is rejected.
  validation {
    condition     = can(regex("^[0-9]+(\\.[0-9]+)*$", var.engine_version))
    error_message = "The engine_version must be a numeric PostgreSQL version such as \"16.6\" or \"17\", not an engine or parameter-group family name."
  }
}

variable "parameter_group_family" {
  description = <<-EOT
    Cluster parameter group family, which must match the MAJOR version of
    engine_version -- "aurora-postgresql16" for a 16.x engine,
    "aurora-postgresql17" for a 17.x engine. Supplied by the environment root
    alongside engine_version so that the two are set, and reviewed, together.
  EOT
  type        = string
  nullable    = false

  # WHY : Assumption: this value is coupled to engine_version and the coupling
  #       is not enforceable here. A family naming a different major version
  #       than the engine is accepted by `terraform validate`, survives the
  #       plan, and fails only when RDS is asked to attach the group to the
  #       cluster -- by which point the parameter group and the subnet group
  #       exist and the failure reads as a cluster error rather than a
  #       mismatched pair. Deriving the family from engine_version inside
  #       main.tf was considered and would remove the coupling entirely, but it
  #       would also silently invent a family name for any engine version
  #       whose family is not spelled the way the derivation assumes, which
  #       trades a loud failure for a quiet one. The shape check below at least
  #       rejects a value that is not a family name at all.
  validation {
    condition     = can(regex("^aurora-postgresql[0-9]+$", var.parameter_group_family))
    error_message = "The parameter_group_family must be an Aurora PostgreSQL family such as \"aurora-postgresql16\", matching the major version given in engine_version."
  }
}

variable "cluster_parameters" {
  description = <<-EOT
    Cluster-level PostgreSQL parameter overrides, as a map of parameter name to
    value. Defaults to an empty map, which is the intended configuration: the
    module ships no opinionated tuning and runs the engine defaults for the
    chosen family.
  EOT
  type        = map(string)
  nullable    = false
  default     = {}

  # WHY : Alternatives Considered: shipping a set of tuned defaults here, which
  #       is the usual expectation of a database module. Rejected because this
  #       cluster's correctness is measured against golden-master outputs
  #       produced by the mainframe baseline, and a parameter override is not a
  #       neutral act in that setting. Changing how the planner costs a query,
  #       how much work memory a sort gets or how timestamps and numerics are
  #       rendered can alter row ordering or output formatting, and the parity
  #       comparison is byte-deterministic -- so a tuning change would surface
  #       as a parity failure whose cause is nowhere near the test that
  #       reports it. Performance tuning beyond what parity requires is out of
  #       scope, so the empty default is the deliberate configuration rather
  #       than a placeholder waiting to be filled in. The input still exists
  #       so that a specific, reviewed override can be made from an
  #       environment root without editing this module.
}

variable "database_name" {
  description = <<-EOT
    Name of the initial database created inside the cluster. This module
    creates the DATABASE only -- it creates none of the schemas, roles, tables
    or grants inside it. Lower case by requirement, because PostgreSQL folds
    unquoted identifiers to lower case.
  EOT
  type        = string
  nullable    = false
  default     = "carddemo"

  # WHY : Assumption: the boundary of this module ends at the database, and
  #       this is the single most likely misunderstanding about it. The eight
  #       schemas the application uses -- auth, account, card, ledger,
  #       reference, batch and authorization, plus the read-only cross-schema
  #       views the reporting service reads through -- are created by
  #       data-migration/sql/V0__schemas_and_roles.sql together with the
  #       per-service Flyway migrations at
  #       services/*/src/main/resources/db/migration/V1__<schema>.sql. Nothing
  #       in this module creates, references or grants on any of them. Putting
  #       schema creation here was considered and rejected: schema ownership
  #       follows the service that owns the data, so a module that created all
  #       eight would make every service's schema change an infrastructure
  #       change, and Flyway could no longer version what it did not create.
  # WHY : Assumption: lower case is required rather than merely conventional.
  #       PostgreSQL folds an unquoted identifier to lower case, so a name
  #       containing capitals is only reachable when quoted -- which would have
  #       to be got right in every JDBC URL, every Flyway migration and every
  #       ETL connection string, and produces a "database does not exist" error
  #       wherever it is missed.
  validation {
    condition     = can(regex("^[a-z][a-z0-9_]*$", var.database_name)) && length(var.database_name) <= 63
    error_message = "The database_name must be 1 to 63 characters of lower-case letters, digits and underscores, beginning with a letter."
  }
}

variable "master_username" {
  description = <<-EOT
    Login name of the cluster's master user, as 1 to 63 lower-case letters,
    digits and underscores beginning with a letter, and not "rdsadmin" which
    RDS reserves. The matching password is NOT an input to this module: it is
    generated at apply time into Secrets Manager and reached through
    master_credential_secret_arn. A user name is not a secret, so this input is
    not marked sensitive and does carry a default.
  EOT
  type        = string
  nullable    = false
  default     = "carddemo_admin"

  # WHY : Assumption: "rdsadmin" is reserved by RDS for its own management
  #       user and is rejected at apply time, so it is rejected here where the
  #       message can say why. It is a plausible thing for someone to try,
  #       which is what makes it worth a check rather than a comment.
  # WHY : Assumption: the same lower-case requirement as database_name, for
  #       the same reason -- PostgreSQL folds unquoted identifiers, so a role
  #       name with capitals only matches when quoted, and a GRANT that
  #       silently addresses a different role than the one that connects is
  #       harder to diagnose than a rejected name.
  validation {
    condition     = can(regex("^[a-z][a-z0-9_]*$", var.master_username)) && length(var.master_username) <= 63
    error_message = "The master_username must be 1 to 63 characters of lower-case letters, digits and underscores, beginning with a letter."
  }

  validation {
    condition     = var.master_username != "rdsadmin"
    error_message = "The master_username must not be \"rdsadmin\", which RDS reserves for its own management user."
  }
}

variable "port" {
  description = <<-EOT
    TCP port the cluster listens on. Defaults to 5432, the PostgreSQL default,
    which is the port the Aurora security group in the network module admits
    from the application tier. Changing it here without changing that rule
    makes the cluster unreachable.
  EOT
  type        = number
  nullable    = false
  default     = 5432

  # WHY : Assumption: this value is coupled to an ingress rule this module does
  #       not own. The network module admits application-tier traffic to the
  #       data tier on 5432, so the default is not an arbitrary convention --
  #       it is the value that matches the rule already written there. A port
  #       changed here alone produces a cluster that plans cleanly, applies
  #       cleanly and then refuses every connection, with the cause two
  #       modules away from the symptom. The bound below is the range RDS
  #       accepts for PostgreSQL rather than the full TCP range, so a
  #       privileged port is refused here instead of at apply time.
  validation {
    condition     = var.port >= 1150 && var.port <= 65535
    error_message = "The port must be between 1150 and 65535, the range RDS accepts for a PostgreSQL cluster."
  }
}


# -----------------------------------------------------------------------------
# Serverless v2 capacity -- the invariant.
#
# This is the section the module's safety rests on, so the whole rule set is
# stated once here and then enforced, block by block, below.
#
#   1. Range ......... capacity is expressed in Aurora Capacity Units from 0 to
#                      256 inclusive.
#   2. Granularity ... capacity moves in half-unit steps, so 1.5 is a capacity
#                      and 1.3 is not.
#   3. Window ........ the auto-pause delay is a whole number of seconds from
#                      300 to 86,400 inclusive.
#   4. Ordering ...... the maximum is not below the minimum.
#   5. Coupling ...... a minimum of 0 makes the auto-pause delay MANDATORY and
#                      forces the maximum to at least 1.
#
# Rule 5 is the one a reader would otherwise get wrong, because it is the only
# rule under which a perfectly valid-looking pair of values becomes invalid
# through a third input: min_capacity = 0 with no auto-pause delay set is
# rejected, and so is min_capacity = 0 with max_capacity = 0.5.
#
# Neither of the two cross-variable rules is restated as a `precondition` in
# main.tf. Each is enforced exactly once, here.
#
# The reference direction is one-way by necessity: max_capacity and
# seconds_until_auto_pause both read var.min_capacity, and min_capacity reads
# neither. Terraform resolves variable validations through a dependency graph,
# so a mutual reference between two of them is a cycle and fails outright.
#
# Rules 1 to 3 read only their own variable and are therefore checked by
# `terraform validate`. Rules 4 and 5 read var.min_capacity, and a
# cross-variable condition is deferred to `terraform plan` -- verified against
# this file, not assumed. So a transposed pair is reported by `plan` rather than
# by `validate`, and it is still caught before any resource is created because
# `apply` plans first. The header's Errors section records the consequence for a
# pipeline that runs only `validate`.
# -----------------------------------------------------------------------------

variable "min_capacity" {
  description = <<-EOT
    Minimum Aurora Capacity Units the cluster scales down to while idle, from 0
    to 256 in half-unit steps. A value of 0 lets the cluster pause completely,
    which is intended for dev; prod holds this above 0. Setting it to 0 makes
    seconds_until_auto_pause mandatory and requires max_capacity to be at least
    1, and requires an engine minor version that supports scaling to zero.
    Required with no default, because capacity is one of the few axes on which
    the dev and prod environments deliberately differ.
  EOT
  type        = number
  nullable    = false

  # WHY : Trade-off: a minimum of 0 buys the cluster paying nothing while idle
  #       and accepts a resume delay on the order of fifteen seconds for the
  #       first connection that arrives after a pause. That cost lands
  #       differently on the two workloads this cluster serves, which is why
  #       the choice is per-environment rather than global. For the nightly
  #       batch chain it is immaterial: the chain is a scheduled state machine
  #       whose first step already brackets the run, so a one-off resume at the
  #       front of it changes nothing observable. For interactive use it is a
  #       noted risk -- the delay is paid by whoever makes the first request
  #       after an idle period, which in a development environment is a person
  #       waiting on a screen. Hence 0 in dev, where the saving is real and the
  #       audience tolerant, and above 0 in prod, where it is neither.
  # WHY : Alternatives Considered: giving this input a default. Rejected. A
  #       default would let an environment root omit the single most
  #       consequential value in the module and inherit sizing nobody chose for
  #       it, and a zero default would additionally arm rule 5 silently, so
  #       that omitting an unrelated input produced an error about auto-pause.
  #       Requiring it makes both environments state their capacity explicitly,
  #       which is also what makes the dev-versus-prod difference readable from
  #       the two tfvars files rather than inferred from this one.
  validation {
    condition     = var.min_capacity >= 0 && var.min_capacity <= 256
    error_message = "The min_capacity must be between 0 and 256 Aurora Capacity Units."
  }

  # WHY : Assumption: the half-unit rule is tested arithmetically -- doubling
  #       the value must yield a whole number -- rather than by listing the
  #       permitted values. An enumeration of every half-unit from 0 to 256
  #       would be 513 entries that a reader cannot check and that would have
  #       to be regenerated if AWS widened the range. The arithmetic form is
  #       also exact rather than approximate: every half-unit value is exactly
  #       representable in binary floating point because 0.5 is a power of two,
  #       so a genuine half-unit always doubles to a whole number and a value
  #       like 1.3 never does.
  validation {
    condition     = var.min_capacity * 2 == floor(var.min_capacity * 2)
    error_message = "The min_capacity must be a multiple of 0.5, such as 0, 0.5, 1 or 12.5."
  }
}

variable "max_capacity" {
  description = <<-EOT
    Maximum Aurora Capacity Units the cluster scales up to under load, from 0
    to 256 in half-unit steps, and never below min_capacity. When min_capacity
    is 0 this must be at least 1. Required with no default, for the same reason
    as min_capacity: it is part of the sizing each environment states for
    itself.
  EOT
  type        = number
  nullable    = false

  validation {
    condition     = var.max_capacity >= 0 && var.max_capacity <= 256
    error_message = "The max_capacity must be between 0 and 256 Aurora Capacity Units."
  }

  # WHY : Assumption: the same arithmetic half-unit test as min_capacity, and
  #       deliberately the same expression rather than a shared local. HCL has
  #       no way to share a validation condition between two variables, and a
  #       local in main.tf cannot be referenced from a `validation` block here,
  #       so repeating the expression is the only available form.
  validation {
    condition     = var.max_capacity * 2 == floor(var.max_capacity * 2)
    error_message = "The max_capacity must be a multiple of 0.5, such as 0.5, 1, 4 or 64."
  }

  # WHY : Assumption: rule 4, the ordering constraint, is hosted on
  #       max_capacity rather than on min_capacity, and the choice is not
  #       arbitrary. Only one of the two variables may reference the other
  #       without creating a validation dependency cycle, and putting the
  #       reference here keeps min_capacity free of outgoing references so that
  #       the coupling rule below can also read it. An inverted pair is far
  #       more likely to be a transposed tfvars entry than a deliberate
  #       choice, so failing at validate rather than letting RDS reject it at
  #       apply saves an operator a half-provisioned data tier.
  validation {
    condition     = var.max_capacity >= var.min_capacity
    error_message = "The max_capacity must be greater than or equal to min_capacity."
  }

  # WHY : Assumption: this is half of rule 5, the coupling rule, and it is
  #       split from the auto-pause half on purpose. Both halves fire only when
  #       min_capacity is 0, and a single combined block would have to name two
  #       different inputs in one message, leaving the operator to work out
  #       which of them to change. Split, each message names exactly one input
  #       while still stating the zero-minimum precondition that triggered it,
  #       so the coupling stays visible without the ambiguity. These are two
  #       distinct constraints, not one constraint checked twice.
  validation {
    condition     = var.min_capacity > 0 || var.max_capacity >= 1
    error_message = "When min_capacity is 0 the max_capacity must be at least 1, because a cluster that scales to zero cannot have a maximum below one Aurora Capacity Unit."
  }
}

variable "seconds_until_auto_pause" {
  description = <<-EOT
    Idle time in seconds before a cluster whose min_capacity is 0 pauses, as a
    whole number from 300 to 86,400 inclusive. MANDATORY when min_capacity is
    0 and ignored otherwise, which is why it defaults to null: null means "not
    set", so a cluster with a non-zero minimum passes nothing and the module
    omits the argument entirely.
  EOT
  type        = number
  default     = null

  # WHY : Assumption: null is a meaningful value here rather than an oversight,
  #       which is why this is one of only three inputs in the file without
  #       `nullable = false`. The setting applies solely to a cluster that can
  #       pause, so a prod cluster holding min_capacity above 0 has no correct
  #       number to supply -- and supplying one anyway would read as though
  #       pausing were configured when it can never occur. The condition below
  #       therefore short-circuits on null through a conditional expression
  #       rather than a boolean chain, so the range and whole-number tests are
  #       never applied to a null.
  # WHY : Trade-off: the delay is what decides how often the resume cost
  #       described on min_capacity is actually paid. A short delay pauses
  #       eagerly and saves the most, at the price of paying the resume more
  #       often; a long one rarely pauses and rarely resumes. The module takes
  #       no position on where in the 300-to-86,400 window that balance sits,
  #       because the answer differs between a batch-only cluster and one
  #       somebody is developing against, and both bounds are the API's rather
  #       than this file's.
  validation {
    condition = var.seconds_until_auto_pause == null ? true : (
      var.seconds_until_auto_pause >= 300 &&
      var.seconds_until_auto_pause <= 86400 &&
      floor(var.seconds_until_auto_pause) == var.seconds_until_auto_pause
    )
    error_message = "The seconds_until_auto_pause must be a whole number of seconds between 300 and 86400, or null when min_capacity is above 0."
  }

  # WHY : Assumption: this is the other half of rule 5, and it is the rule most
  #       likely to be missed, because nothing about a null default suggests
  #       that another input can make it compulsory. A cluster configured to
  #       scale to zero with no auto-pause delay set does not fail obviously --
  #       it is a cluster that will not pause, so it quietly keeps the minimum
  #       it was told to release and the whole reason for choosing a zero
  #       minimum is lost without any error. Catching it at validate turns a
  #       silent misconfiguration into a named one.
  validation {
    condition     = var.min_capacity > 0 || var.seconds_until_auto_pause != null
    error_message = "When min_capacity is 0 the seconds_until_auto_pause must be set, because auto-pause is mandatory for a cluster that scales to zero capacity."
  }
}


# -----------------------------------------------------------------------------
# Durability and protection.
#
# Everything in this section restores a property the baseline did not have. All
# eight DEFINE FILE stanzas in app/csd/CARDDEMO.CSD run JOURNAL(NO) (lines 7,
# 19, 31, 44, 57, 70, 82 and 94), RECOVERY(NONE) with FWDRECOVLOG(NO) (lines 9,
# 21, 33, 46, 59, 72, 84 and 96) and BACKUPTYPE(STATIC) (lines 10, 22, 34, 47,
# 60, 73, 85 and 97) -- eight out of eight, with no exception. The data tier
# being migrated therefore had no journalling, no forward recovery and no
# point-in-time recovery of any kind. The defaults below are corrections of
# that gap rather than ports of existing behaviour, which is why they lean
# towards durability wherever an environment has not chosen otherwise.
# -----------------------------------------------------------------------------

variable "backup_retention_period" {
  description = <<-EOT
    Days of automated backups retained, from 1 to 35. This is also the window
    within which point-in-time recovery is possible, so it is the recovery
    capability rather than a storage setting. Defaults to 7; prod raises it and
    dev may lower it, but neither may disable it.
  EOT
  type        = number
  nullable    = false
  default     = 7

  # WHY : Refactoring Rationale: automated backups exist here because the
  #       baseline had none. Every one of the eight CSD file stanzas cited in
  #       this section's header declares RECOVERY(NONE) and FWDRECOVLOG(NO),
  #       so a corrupted or wrongly-posted record in the mainframe data tier
  #       was recoverable only from whatever static backup had last been taken
  #       out of band. Retaining automated backups gives the replacement a
  #       continuous recovery window the original never had.
  # WHY : Assumption: the floor is 1 rather than 0, and that is the API's own
  #       constraint rather than a policy choice -- Aurora has no notion of
  #       disabled automated backups, and 0 is simply rejected. Stating the
  #       floor here means the module cannot be configured into a
  #       no-backup posture even by mistake, which matters because this is a
  #       financial ledger and the reject is silent from the caller's point of
  #       view: a 0 in a tfvars file looks like a deliberate cost saving.
  validation {
    condition     = var.backup_retention_period >= 1 && var.backup_retention_period <= 35
    error_message = "The backup_retention_period must be between 1 and 35 days; Aurora does not permit automated backups to be disabled."
  }
}

variable "preferred_backup_window" {
  description = <<-EOT
    Daily window in which automated backups are taken, in UTC, formatted
    "hh:mm-hh:mm" -- note there is NO day-of-week prefix on this one. Defaults
    to null, which lets RDS assign a window; the environment roots set it
    explicitly so it can be held clear of the nightly batch window.
  EOT
  type        = string
  default     = null

  # WHY : Assumption: this window must not overlap the nightly batch window,
  #       and that window is defined outside this module -- it is a scheduler
  #       cron expression driving a state machine whose first and last steps
  #       quiesce and resume online writes. The overlap matters because the
  #       batch chain is the one period when the cluster is under sustained
  #       write load with online writes deliberately held off, so a backup
  #       landing inside that bracket competes with exactly the work that must
  #       not be slowed. Nothing in this module can see that schedule, so the
  #       requirement is recorded here and honoured by the roots that set both.
  # WHY : Alternatives Considered: defaulting to a specific window rather than
  #       null. Rejected because this module does not know when the batch chain
  #       runs, so any literal chosen here would be a guess that reads as a
  #       decision -- and a guess that happened to collide with the batch
  #       window would be actively harmful while looking deliberate. A null
  #       default makes the module omit the argument, which is at least
  #       honestly unspecified. The accepted cost is that an RDS-assigned
  #       window could itself overlap, which is why the roots set it and why
  #       the description says so rather than leaving it to be inferred.
  # WHY : Assumption: the format differs from preferred_maintenance_window and
  #       the two are easy to confuse, so each is checked against its own
  #       pattern. This one takes no day prefix; the maintenance window
  #       requires one. Pasting either value into the other input is a common
  #       error that AWS rejects at apply time with a message about the
  #       parameter rather than about the mix-up.
  validation {
    condition     = var.preferred_backup_window == null ? true : can(regex("^([01][0-9]|2[0-3]):[0-5][0-9]-([01][0-9]|2[0-3]):[0-5][0-9]$", var.preferred_backup_window))
    error_message = "The preferred_backup_window must be a UTC window of the form \"hh:mm-hh:mm\", for example \"07:00-08:00\", with no day-of-week prefix."
  }
}

variable "preferred_maintenance_window" {
  description = <<-EOT
    Weekly window in which RDS applies maintenance, in UTC, formatted
    "ddd:hh:mm-ddd:hh:mm" with a lower-case three-letter day -- this one DOES
    take a day prefix, unlike preferred_backup_window. Defaults to null, which
    lets RDS assign a window; the environment roots set it explicitly so it can
    be held clear of the nightly batch window.
  EOT
  type        = string
  default     = null

  # WHY : Assumption: the same non-overlap requirement as the backup window and
  #       for a stronger reason. Maintenance can restart the cluster, so a
  #       window that intersects the batch chain risks interrupting a run
  #       between its quiesce and resume steps -- leaving online writes held off
  #       by a state machine whose later steps did not run. Recovery from that
  #       is an operator action, not a retry, which is why the roots pin this
  #       window rather than letting RDS choose it.
  validation {
    condition     = var.preferred_maintenance_window == null ? true : can(regex("^(mon|tue|wed|thu|fri|sat|sun):([01][0-9]|2[0-3]):[0-5][0-9]-(mon|tue|wed|thu|fri|sat|sun):([01][0-9]|2[0-3]):[0-5][0-9]$", var.preferred_maintenance_window))
    error_message = "The preferred_maintenance_window must be a UTC window of the form \"ddd:hh:mm-ddd:hh:mm\" with lower-case day names, for example \"sun:08:00-sun:09:00\"."
  }
}

variable "deletion_protection" {
  description = <<-EOT
    Whether RDS refuses to delete the cluster. Defaults to true, which is what
    prod runs. Dev sets it to false, because a cluster that cannot be deleted
    cannot be torn down, and clean teardown is a requirement of the dev
    environment rather than a convenience.
  EOT
  type        = bool
  nullable    = false
  default     = true

  # WHY : Trade-off: the default is true because the two ways of being wrong
  #       are not symmetric. Defaulting to false and forgetting to raise it in
  #       prod loses a financial ledger to a mistyped destroy and is
  #       unrecoverable beyond the backup window; defaulting to true and
  #       forgetting to lower it in dev produces a failed destroy and a clear
  #       error message. The recoverable failure is the correct one to default
  #       into.
  # WHY : Assumption: dev genuinely needs false, and the reason is specific
  #       rather than a matter of taste. The infrastructure is required to tear
  #       down cleanly with `terraform destroy`, and that is the documented
  #       teardown path. Deletion protection makes the destroy fail outright,
  #       so leaving it enabled in dev would break that requirement -- the
  #       cluster would have to be de-protected by hand first, which is exactly
  #       the manual step the teardown path exists to avoid. This is the
  #       clearest case in the module of a flag whose correct value is opposite
  #       in the two environments.
}

variable "skip_final_snapshot" {
  description = <<-EOT
    Whether deleting the cluster skips taking a final snapshot. Defaults to
    false, so a snapshot is taken and prod keeps a last recoverable copy. Dev
    sets it to true so that teardown leaves nothing behind to pay for or clean
    up. When false, the module supplies the snapshot identifier itself.
  EOT
  type        = bool
  nullable    = false
  default     = false

  # WHY : Trade-off: the same asymmetry as deletion_protection and defaulted the
  #       same way for the same reason -- forgetting to take a final snapshot in
  #       prod is unrecoverable, whereas forgetting to skip one in dev leaves a
  #       cheap artefact behind. The two flags are nonetheless separate inputs
  #       rather than one, because they fail differently: deletion protection
  #       blocks the destroy entirely, while a final snapshot lets it proceed
  #       and leaves a retained copy. Dev needs both relaxed to tear down
  #       cleanly, and collapsing them into a single flag would hide that.
  # WHY : Assumption: RDS requires a final snapshot identifier whenever this is
  #       false, and this module composes that identifier from name_prefix
  #       rather than accepting it as an input. No final_snapshot_identifier
  #       variable is declared, deliberately: an operator-supplied identifier
  #       has to be unique per deletion, so it would have to be edited before
  #       every destroy, and a stale one collides with the snapshot left by the
  #       previous teardown and fails the destroy for a reason unconnected to
  #       whatever was being changed. Composing it removes that failure mode
  #       and one input at the same time.
}

variable "copy_tags_to_snapshot" {
  description = <<-EOT
    Whether the cluster's tags are copied onto its snapshots. Defaults to true
    so that a snapshot stays attributable to its owner and cost centre after
    the cluster it came from no longer exists.
  EOT
  type        = bool
  nullable    = false
  default     = true

  # WHY : Assumption: a snapshot outlives the cluster it was taken from, which
  #       is what makes this worth defaulting on. An untagged snapshot appears
  #       in cost allocation attributed to nothing and in an inventory owned by
  #       nobody, and once the cluster is gone there is no longer any resource
  #       to infer its owner, environment or cost centre from -- the
  #       information cannot be reconstructed later, only lost at the moment
  #       the snapshot is taken. That is also why this is not left to the
  #       caller's provider default_tags: those tag the cluster, not a snapshot
  #       RDS creates on its own during deletion.
}

variable "apply_immediately" {
  description = <<-EOT
    Whether modifications are applied at once instead of being deferred to
    preferred_maintenance_window. Defaults to false, so a change waits for the
    window. Set it to true only for a change that is known to be
    non-disruptive or is urgent enough to accept an interruption.
  EOT
  type        = bool
  nullable    = false
  default     = false

  # WHY : Trade-off: false is chosen and the cost of choosing it is real, so it
  #       is named rather than glossed. Deferring means the running cluster
  #       does not match the plan a reviewer approved until the maintenance
  #       window passes, so a subsequent plan can show a pending change that
  #       looks like drift and is not, and two changes made before the window
  #       are applied together rather than in the order they were reviewed.
  #       Applying immediately removes that gap but can restart the cluster
  #       outside the window, mid-day, against a financial ledger. The
  #       reviewable-gap cost is accepted because it is visible and temporary,
  #       whereas an unscheduled interruption is neither -- and where a change
  #       genuinely must land at once, this input makes that an explicit,
  #       per-change decision rather than a standing posture.
}


# -----------------------------------------------------------------------------
# Observability.
#
# The baseline's only operational record for this data was print output routed
# to the job spool -- SYSOUT and SYSPRINT DD statements in every batch job, for
# example app/jcl/ACCTFILE.jcl:23, :34 and :55 -- and the CSD file stanzas
# added no journalling to it. There was no continuous, queryable record of what
# the data tier itself did. The two mechanisms below restore one.
#
# Retention and alarming for the log GROUPS are owned by the observability
# module, not by this one. This module only nominates which log types the
# cluster publishes.
# -----------------------------------------------------------------------------

variable "performance_insights_enabled" {
  description = <<-EOT
    Whether Performance Insights collects database load and query-level
    telemetry from the cluster. Defaults to true. Encrypted with the same
    customer-managed key given in kms_key_arn, and retained for
    performance_insights_retention_period days.
  EOT
  type        = bool
  nullable    = false
  default     = true

  # WHY : Refactoring Rationale: this replaces a capability the baseline had no
  #       equivalent of. A mainframe batch job's cost was visible only as the
  #       print output cited in this section's header, produced after the job
  #       finished, so a query pattern that degraded gradually left no trace to
  #       look back through. Performance Insights keeps a continuous per-query
  #       record, which is what makes a regression in a migrated batch job
  #       diagnosable at all rather than reproducible only by rerunning it.
  # WHY : Assumption: the retention period below is coupled to this flag and is
  #       ignored while this is false, so the pair is set together. The
  #       telemetry is also encrypted with the cluster's own customer-managed
  #       key rather than a separate one, because query text can echo the
  #       contents of a predicate -- an account or card identifier -- so it is
  #       treated as being as sensitive as the data it was matched against.
}

variable "performance_insights_retention_period" {
  description = <<-EOT
    Days of Performance Insights telemetry retained. AWS accepts only 7, 731,
    or a multiple of 31 from 31 to 713. Defaults to 7, the no-additional-charge
    tier; ignored when performance_insights_enabled is false.
  EOT
  type        = number
  nullable    = false
  default     = 7

  # WHY : Assumption: the permitted values are a genuinely irregular set rather
  #       than a range, which is why the check is written as it is -- two
  #       discrete values plus a multiple-of-31 band. Aurora rejects anything
  #       else, including apparently reasonable numbers like 30, 90 or 365, and
  #       it does so at apply time. Encoding the set here turns a puzzling
  #       API rejection into a message that names the accepted values.
  # WHY : Trade-off: 7 is the default because it is the tier that carries no
  #       additional charge, and the cost of that choice is a short lookback --
  #       a regression noticed more than a week after it appeared cannot be
  #       traced to its onset. That is accepted as the default because an
  #       environment needing a longer history can raise this input, whereas a
  #       longer default would bill every environment for a lookback most of
  #       them never read.
  validation {
    condition = contains([7, 731], var.performance_insights_retention_period) || (
      var.performance_insights_retention_period >= 31 &&
      var.performance_insights_retention_period <= 713 &&
      var.performance_insights_retention_period % 31 == 0
    )
    error_message = "The performance_insights_retention_period must be 7, 731, or a multiple of 31 between 31 and 713."
  }
}

variable "enabled_cloudwatch_logs_exports" {
  description = <<-EOT
    Engine log types the cluster publishes to CloudWatch Logs. For Aurora
    PostgreSQL the accepted values are "postgresql", the engine log, and
    "upgrade", the major-version upgrade log. Defaults to ["postgresql"]. The
    retention of the resulting log groups is set by the observability module,
    not here.
  EOT
  type        = list(string)
  nullable    = false
  default     = ["postgresql"]

  # WHY : Refactoring Rationale: exporting the engine log restores an audit
  #       trail the baseline never had for its data tier. The only record the
  #       mainframe produced was the spool output cited in this section's
  #       header, and the eight CSD file stanzas added no journalling on top of
  #       it, so there was no durable account of errors, connections or
  #       statement failures at the storage layer. The engine log is that
  #       account, and it is exported rather than left on the instance so that
  #       it survives the instance.
  # WHY : Assumption: ownership is split, and the split is the reason no
  #       retention input appears next to this one. Naming a log type here is
  #       what causes RDS to create the log group; how long that group keeps
  #       its data, and what alarms read it, belong to the observability
  #       module, which owns log groups, dashboards and alarms across the whole
  #       tree. A retention input here would compete with that module for the
  #       same setting, and whichever applied last would win silently.
  # WHY : Assumption: the accepted set is engine-specific, so the check is a
  #       set-subtraction against the two values Aurora PostgreSQL supports
  #       rather than a non-empty check. A plausible-looking value borrowed
  #       from another engine -- "audit" and "error" are MySQL log types -- is
  #       accepted by `terraform validate` and rejected by RDS during the
  #       apply, so listing the valid pair here moves that failure forward and
  #       names the alternatives.
  validation {
    condition     = length(setsubtract(var.enabled_cloudwatch_logs_exports, ["postgresql", "upgrade"])) == 0
    error_message = "The enabled_cloudwatch_logs_exports may contain only \"postgresql\" and \"upgrade\", the log types Aurora PostgreSQL supports."
  }
}
