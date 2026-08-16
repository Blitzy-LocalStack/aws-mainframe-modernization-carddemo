# =============================================================================
# infra/modules/aurora-postgresql/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the single Aurora PostgreSQL Serverless v2 cluster that carries
#   every record datastore of the CardDemo application, and nothing else. It
#   replaces TEN VSAM KSDS base clusters, each of which was formerly created by
#   an in-stream IDCAMS DEFINE CLUSTER step: app/jcl/ACCTFILE.jcl:36
#   (KEYS(11 0), RECORDSIZE(300 300)), app/jcl/CARDFILE.jcl:50
#   (16 0)/(150 150), app/jcl/XREFFILE.jcl:39 (16 0)/(50 50),
#   app/jcl/CUSTFILE.jcl:46 (9 0)/(500 500), app/jcl/DISCGRP.jcl:36
#   (16 0)/(50 50), app/jcl/TCATBALF.jcl:36 (17 0)/(50 50),
#   app/jcl/TRANCATG.jcl:36 (6 0)/(60 60), app/jcl/TRANFILE.jcl:49
#   (16 0)/(350 350), app/jcl/TRANTYPE.jcl:36 (2 0)/(60 60) and
#   app/jcl/DUSRSECJ.jcl:64 (KEYS(8,0), RECORDSIZE(80,80)). It also replaces
#   the three alternate indexes -- app/jcl/CARDFILE.jcl:83 KEYS(11 16),
#   app/jcl/XREFFILE.jcl:72 KEYS(11,25) and the TRANSACT index KEYS(26 304)
#   defined identically twice, at app/jcl/TRANIDX.jcl:25 and
#   app/jcl/TRANFILE.jcl:82 -- plus the extension contexts' IMS segments and
#   Db2 tables. Every baseline path named anywhere in this file is
#   REFERENCE-ONLY: it is cited to ground a decision, and never edited.
#
#   One data source and four resources are declared, in dependency order:
#
#     aws_secretsmanager_secret_version (data)
#                                       reads the master credential that
#                                       infra/modules/secrets generated, which
#                                       is the single authority for that
#                                       credential in the whole stack.
#     aws_db_subnet_group ............. pins the cluster into the isolated
#                                       data-tier subnets.
#     aws_rds_cluster_parameter_group . carries the two transport-security
#                                       parameters and any reviewed override.
#     aws_rds_cluster ................. the cluster, encrypted with a
#                                       customer-managed key, backed up, and
#                                       scaled by Serverless v2 capacity.
#     aws_rds_cluster_instance ........ exactly ONE instance, the writer.
#
#   What this file deliberately does NOT declare. Each absence is recorded so
#   that it reads as a decision rather than as an oversight, because every one
#   of them is a thing a reader would reasonably expect to find here:
#
#     - No `provider`, `terraform` or `backend` block. This directory is a
#       module, not a root: infra/envs/dev and infra/envs/prod call it and each
#       of those roots owns provider configuration including region and
#       default_tags, while versions.tf owns the toolchain and provider
#       constraints. A module cannot carry a backend at all.
#     - No `variable` and no `output` block; see Parameters and Returns below.
#     - No `data` source resolving another module's RESOURCES by name, and no
#       `module` block. The single data source below resolves a Secrets Manager
#       entry by the ARN its caller passed in, so it depends on a value it was
#       handed rather than on a sibling module's naming scheme; that is the
#       distinction the first WHY below draws, and it is why one data source is
#       present while the coupling it warns about still is not.
#     - No second master credential. This module does NOT set
#       manage_master_user_password, and the argument's absence is now
#       load-bearing: setting it would make RDS generate a credential of its own
#       beside the one infra/modules/secrets already generated, leaving the
#       cluster with two and every consumer pointed at the wrong one. The
#       why-comment on master_password records what that cost in full.
#     - No schema, role, grant, table, index or extension -- and therefore no
#       `postgresql` provider, no `null_resource`, no provisioner and no SQL
#       string anywhere. This module's boundary ends at the DATABASE. The eight
#       schemas (auth, account, card, ledger, reference, batch, authorization,
#       plus the read-only cross-schema views the reporting service reads
#       through) are created by data-migration/sql/V0__schemas_and_roles.sql
#       and by the per-service Flyway migrations at
#       services/*/src/main/resources/db/migration/V1__<schema>.sql. The
#       secondary indexes that replace the three alternate indexes are created
#       by those same migrations; IDCAMS BLDINDEX (app/jcl/CARDFILE.jcl:110,
#       app/jcl/XREFFILE.jcl:100, app/jcl/TRANFILE.jcl:109,
#       app/jcl/TRANIDX.jcl:52) is retired rather than ported, because
#       PostgreSQL maintains an index transactionally and has no separate build
#       step to schedule.
#     - No security group and no KMS key. infra/modules/network owns the Aurora
#       security group and both halves of its matched rule pair, and
#       infra/modules/kms owns the customer-managed keys. This module attaches
#       what it is handed.
#     - No reader instance, no Aurora Global Database, no
#       `replication_source_identifier` and no cross-region resource. See the
#       comment on the writer instance for the reasoning, which is load-bearing
#       rather than incidental.
#     - No `availability_zones` argument. Naming zones explicitly pins the
#       cluster to a zone list that RDS otherwise derives from the subnet
#       group, and a later change to that list forces the cluster to be
#       replaced -- destroying a financial ledger to alter a value the subnet
#       group already expresses correctly.
#     - No capacity `precondition`. The five capacity rules are enforced once,
#       as `validation` blocks in variables.tf, and are deliberately not
#       restated here; that file records the same decision from its side.
#
# Parameters:
#   None, and the absence is deliberate rather than a section omitted. A main.tf
#   declares no `variable`, so this file accepts no input directly: it consumes
#   the inputs declared in infra/modules/aurora-postgresql/variables.tf, each of
#   which carries its own `type`, `description` and `validation` there. Every one
#   of them is consumed here or in outputs.tf, which is what keeps
#   terraform_unused_declarations in infra/.tflint.hcl quiet -- and is why the
#   input that named a separately-created master-credential secret was removed
#   rather than left declared: it had no consumer once RDS became the sole owner
#   of that credential.
#
# Returns:
#   None, for the same reason and recorded for the same purpose. A main.tf
#   declares no `output`, so nothing here is readable by a calling root. The
#   module's return values -- the cluster endpoint, port, identifier, the
#   master-credential secret reference and the resource names
#   composed below -- are `output` blocks in
#   infra/modules/aurora-postgresql/outputs.tf, which is where
#   terraform_standard_module_structure requires them to live.
#
# Errors:
#   Errors from this file fall into two groups, and the difference between them
#   is when they are raised. Because a module is never planned or applied on its
#   own, every one of them surfaces through whichever environment root called
#   it.
#
#   Raised before anything is created, by the `precondition` on the cluster:
#     - The cluster's data key and the key encrypting the RDS-managed master
#       credential do not sit in the same AWS partition, region and account. That
#       is a wiring defect in the calling root rather than a bad value in either
#       input, so neither input's own `validation` in variables.tf can see it -- a
#       `validation` reads one variable, and this rule compares two. The message
#       names both ARNs' differing fields so the mis-wired module call is
#       identifiable without reading the plan.
#
#   Raised by AWS during apply, and expected rather than defended against,
#   because nothing in HCL can evaluate any of them:
#     - `parameter_group_family` naming a different major version than
#       `engine_version`. RDS rejects the group when it is attached to the
#       cluster, by which point the subnet group and the parameter group exist,
#       so the failure reads as a cluster error rather than as the mismatched
#       pair it is. Both inputs are supplied by the same root for that reason.
#     - `min_capacity` of 0 on an engine minor release that does not support
#       scaling to zero. The capacity rules in variables.tf enforce the ACU
#       range, the half-unit granularity and the auto-pause coupling, but the
#       set of minor releases that permit a zero minimum is not knowable from
#       HCL, so this pairing is honoured by whoever sets the two values.
#     - `deletion_protection` still true when a destroy is attempted. RDS
#       refuses the deletion; this is the intended behaviour in prod and the
#       reason dev sets the flag to false.
#       (A `final_snapshot_identifier` collision used to be listed here, when the
#       identifier was one fixed name per `name_prefix`. It no longer is: the
#       identifier now carries a generated suffix that differs per cluster
#       incarnation, so a repeated create-destroy cycle cannot claim a name a
#       previous teardown left behind. The reasoning is at
#       `local.final_snapshot_identifier`.)
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: a relational engine at all. Decision D3, recorded
#     in docs/adr/ADR-003-datastore-targets.md, evaluated a key-value store for
#     this data and rejected it on two grounds that the baseline itself
#     establishes. First, referential integrity: the transaction-category to
#     transaction-type relationship is asserted in the baseline as a Db2
#     constraint whose delete behaviour is RESTRICT, which a key-value store
#     cannot express and would have to reimplement in application code across
#     every writer. Second, atomicity: posting is genuinely multi-record --
#     app/cbl/CBTRN02C.cbl paragraph 2000-POST-TRANSACTION at L424 performs
#     2700-UPDATE-TCATBAL (L441), 2800-UPDATE-ACCOUNT-REC (L442) and
#     2900-WRITE-TRANSACTION-FILE (L443) as one unit of work, so a store
#     without a multi-record transaction would make a partially-posted state
#     observable where the baseline has none, and the golden-master parity
#     comparison would correctly report it as a regression.
#   - Refactoring Rationale: encryption at rest with a customer-managed key,
#     automated backups, and engine-log export are CORRECTIONS of a measured
#     baseline gap rather than ports of existing behaviour, which is why they
#     are not configurable downwards. All EIGHT DEFINE FILE stanzas in
#     app/csd/CARDDEMO.CSD (L1, L13, L25, L37, L50, L63, L76, L88) declare
#     JOURNAL(NO) (L7, L19, L31, L44, L57, L70, L82, L94), RECOVERY(NONE) with
#     FWDRECOVLOG(NO) (L9, L21, L33, L46, L59, L72, L84, L96) and
#     BACKUPTYPE(STATIC) (L10, L22, L34, L47, L60, L73, L85, L97) -- eight out
#     of eight, with no exception, and READINTEG(UNCOMMITTED) throughout. The
#     data tier being migrated therefore had no journalling, no forward
#     recovery, no point-in-time recovery and no encryption of any kind. Two of
#     those eight stanzas name .AIX.PATH datasets (CARDAIX at L14, CXACAIX at
#     L65) rather than base clusters, which is why the verified base-cluster
#     figure is ten while the CSD carries eight file resources.
#   - Assumptions: the module composes every resource name from
#     var.name_prefix and never accepts one as an input, so a caller cannot
#     produce two resources here whose names disagree about which cluster they
#     belong to. The composition is centralised in the `locals` block below for
#     that reason.
# =============================================================================


# -----------------------------------------------------------------------------
# Composed names and derived values.
#
# Every identifier this module creates is composed here rather than at its point
# of use, and no resource name appears as a literal anywhere in the file.
#
# WHY : Assumptions: an RDS DB cluster identifier is not a free-form string. It
#       must be 1 to 63 characters of lower-case letters, digits and hyphens,
#       must begin with a letter, and must contain no consecutive and no
#       trailing hyphen -- and RDS enforces that grammar on the COMPOSED value,
#       not on the prefix a caller supplied. Composing all five identifiers from
#       one validated prefix in one place is what makes that grammar checkable:
#       variables.tf constrains var.name_prefix to the same charset and caps it
#       at 40 characters so that the longest suffix appended below still leaves
#       the composed value inside the 63-character ceiling. Spreading the
#       composition across five resources would mean five places for a suffix to
#       be added that nobody re-measured against that ceiling.
# WHY : Trade-offs: the suffixes are distinct words rather than one shared stem,
#       so the four AWS resources are individually identifiable in a console
#       listing, an invoice line and a CloudTrail event without cross-referencing
#       anything. The accepted cost is five string literals in this block that a
#       rename would have to touch together; the alternative -- deriving them
#       from each other -- would make the cluster identifier change whenever the
#       subnet group's did, and an identifier change on an RDS cluster is a
#       replacement, not an update.
# -----------------------------------------------------------------------------

locals {
  cluster_identifier           = "${var.name_prefix}-aurora"
  db_subnet_group_name         = "${var.name_prefix}-aurora-subnets"
  cluster_parameter_group_name = "${var.name_prefix}-aurora-cluster-params"
  writer_instance_identifier   = "${var.name_prefix}-aurora-writer"

  # WHY : Refactoring Rationale: this was `"${var.name_prefix}-aurora-final"`,
  #       one fixed name, and a fixed name cannot survive the very lifecycle it
  #       exists for. A snapshot OUTLIVES the cluster it was taken from -- that is
  #       its purpose -- and a snapshot identifier is unique per account and
  #       region, so the second teardown of a stack that was destroyed and stood
  #       up again is rejected by RDS for a name the previous teardown already
  #       claimed. The destroy then fails at the last resource, leaving the
  #       cluster in place, with an error naming a collision rather than the
  #       lifecycle that caused it. `terraform destroy` tearing the stack down
  #       cleanly is an acceptance criterion of this infrastructure, and a
  #       repeatable one, so the name has to differ per incarnation.
  # WHY : Alternatives Considered: (a) an operator-supplied identifier as an
  #       input, rejected because it has to be edited before every destroy and a
  #       forgotten edit reproduces exactly this collision; (b) `timestamp()`,
  #       rejected because it is evaluated on every plan, so the argument would
  #       differ from the stored value on each run and the cluster would show a
  #       perpetual in-place update for a name that only matters at deletion;
  #       (c) deleting the previous snapshot as part of the next apply, rejected
  #       outright because it destroys the last recoverable copy of a financial
  #       ledger to make a name available; and (d) a `random_id` resource, which
  #       carried exactly the right semantics but required a THIRD provider in a
  #       module whose declared provider set is `hashicorp/aws` alone. The
  #       built-in `terraform_data` resource below supplies the same property with
  #       no provider at all: its generated `id` is created once, kept in state,
  #       and replaced only when `triggers_replace` changes.
  # WHY : Assumptions: the composed name stays inside the RDS 255-character
  #       identifier limit with room to spare: the prefix is capped by
  #       var.name_prefix's own length validation, and the suffix adds sixteen
  #       characters plus one separator. The hyphens are stripped from the
  #       generated identifier before it is truncated because an RDS snapshot
  #       identifier may not contain two consecutive hyphens, and a truncation that
  #       ended on one would place a second hyphen next to the separator.
  final_snapshot_identifier = "${var.name_prefix}-aurora-final-${substr(replace(terraform_data.final_snapshot_suffix.id, "-", ""), 0, 16)}"

  # WHY : Refactoring Rationale: these two parameters are re-asserted here even
  #       though variables.tf already refuses a var.cluster_parameters map that
  #       omits or weakens either one, because the two checks act on different
  #       things and neither substitutes for the other. That `validation` rejects
  #       an INPUT and names the offending variable at plan time, which is the
  #       better error; this merge fixes the ARGUMENT, so the cluster cannot be
  #       created without both parameters even if that validation were later
  #       relaxed. This is not the same rule checked twice -- it is a plan-time
  #       gate plus a structural guarantee -- and the distinction matters because
  #       of what the defaults are. rds.force_ssl defaults to 0 on Aurora
  #       PostgreSQL 16 and earlier, so a cluster that simply did not mention it
  #       would ACCEPT a cleartext connection and stream account, customer, card
  #       and transaction rows in the clear to any client that did not ask for
  #       TLS, with nothing in the plan output saying so because the parameter
  #       was never named. password_encryption is the companion: md5 stores every
  #       service credential as an unsalted verifier that is brute-forceable
  #       offline, and it too fails silently. Isolating the data tier in subnets
  #       with no internet route bounds who can observe that traffic; it does not
  #       make it unreadable.
  # WHY : Assumptions: this is the SERVER half of a two-sided requirement. The
  #       client half lives in each service's application.yml and in
  #       data-migration/src/carddemo_migration/config.py as sslmode=verify-full,
  #       and data-migration/sql/V0__schemas_and_roles.sql asserts the same two
  #       requirements from the database side. Neither half is redundant: without
  #       the server parameter a misconfigured client can downgrade to cleartext,
  #       and without the client settings a forced-TLS server still accepts a
  #       connection that verified no certificate at all.
  cluster_parameters = merge(
    var.cluster_parameters,
    {
      "rds.force_ssl"       = "1"
      "password_encryption" = "scram-sha-256"
    }
  )

  # WHY : Assumptions: tagging responsibility is split and this merge covers only
  #       this module's half. The calling root configures `default_tags` on its
  #       provider, which the AWS provider applies to every taggable resource
  #       without this module doing anything, so account-wide tags are
  #       deliberately not repeated here -- var.tags is documented as
  #       module-specific additions only.
  # WHY : Trade-offs: Environment is merged LAST, so it wins over a same-named
  #       key in var.tags. That ordering resolves a contradiction rather than
  #       creating one: the environment is already supplied as its own typed,
  #       validated input, so a caller whose tags map disagreed with it would
  #       have stated the environment twice and differently. Deciding in favour
  #       of the validated input is the only resolution that cannot silently
  #       mislabel the cluster; the accepted cost is that this one key is not
  #       overridable through var.tags.
  tags = merge(
    var.tags,
    {
      Environment = var.environment
    }
  )

  parameter_name_root = "${var.parameter_prefix}/${var.environment}/aurora"
}


# -----------------------------------------------------------------------------
# The final-snapshot name suffix.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this resource exists ONLY so that the snapshot name differs
#       between one incarnation of the cluster and the next; the reasoning, and
#       the four rejected alternatives, are recorded at
#       `local.final_snapshot_identifier` above. It creates nothing in AWS, and it
#       needs no provider: `terraform_data` is built into Terraform itself, which
#       is what lets this module keep the single-provider contract its
#       versions.tf declares.
# WHY : Assumptions: `triggers_replace` names the cluster identifier, so the
#       suffix is regenerated exactly when that identifier changes -- and an
#       identifier change on an RDS cluster is a REPLACEMENT, which means the
#       outgoing cluster may leave a final snapshot behind under the old name
#       while the incoming one needs a name of its own. Without the trigger the
#       suffix would survive the replacement and the second teardown would
#       collide again, which is the failure this resource was added to remove. A
#       destroy and a fresh apply are covered without any trigger, because the
#       value goes with the state.
resource "terraform_data" "final_snapshot_suffix" {
  triggers_replace = local.cluster_identifier
}






# -----------------------------------------------------------------------------
# The DB subnet group: where the cluster lives.
# -----------------------------------------------------------------------------

resource "aws_db_subnet_group" "this" {
  name = local.db_subnet_group_name

  # WHY : Assumptions: these are the ISOLATED data-tier subnets -- the ones with
  #       no route to the internet in either direction, neither to an internet
  #       gateway nor to a NAT gateway -- and not the private application subnets
  #       that carry the ECS tasks. That isolation is the strongest
  #       blast-radius control in the whole design and is the reason the cluster
  #       needs no publicly-reachable posture of its own. This module cannot
  #       verify it: a caller that passed the application subnets instead would
  #       still get a working cluster and a clean plan, while silently giving the
  #       data tier an egress path that nothing downstream would report. The
  #       requirement therefore lives in this comment and in the input's own
  #       description, and the choice of input name is the only enforcement
  #       available.
  # WHY : Assumptions: at least two subnets in DISTINCT availability zones are
  #       required, because a DB subnet group derives its zone coverage from the
  #       distinct zones of its members and RDS refuses a group that spans fewer
  #       than two. variables.tf checks both the count and the absence of
  #       duplicates for that reason; the deployment is single-region across
  #       three zones, so the environment roots supply three.
  subnet_ids = var.isolated_subnet_ids

  # WHY : Trade-offs: a description is set explicitly even though it is optional,
  #       because the AWS provider otherwise writes "Managed by Terraform" --
  #       true of every resource in the account and therefore useless to an
  #       operator reading a console listing during an incident. Naming the tier
  #       is what makes a subnet group holding application subnets visibly wrong.
  description = "Isolated data-tier subnets for the ${local.cluster_identifier} Aurora PostgreSQL cluster."

  tags = local.tags
}


# -----------------------------------------------------------------------------
# The cluster parameter group: how the engine behaves.
# -----------------------------------------------------------------------------

resource "aws_rds_cluster_parameter_group" "this" {
  name = local.cluster_parameter_group_name

  # WHY : Assumptions: the family must match the MAJOR version of
  #       var.engine_version -- aurora-postgresql16 for a 16.x engine,
  #       aurora-postgresql17 for a 17.x engine -- and the coupling is not
  #       enforceable from HCL. A family naming a different major version passes
  #       `terraform validate`, survives the plan, and fails only when RDS is
  #       asked to attach the group to the cluster, which the header records as
  #       an expected apply-time error. Deriving the family from
  #       var.engine_version here was considered and rejected: it would remove
  #       the coupling but would also invent a family name for any engine version
  #       whose family is not spelled the way the derivation assumed, trading a
  #       loud failure for a quiet one.
  family = var.parameter_group_family

  description = "Cluster parameters for the ${local.cluster_identifier} Aurora PostgreSQL cluster."

  # WHY : Alternatives Considered: shipping a set of tuned parameters, which is
  #       the usual expectation of a database module. Rejected, and the reason is
  #       specific to this migration rather than a general preference: this
  #       cluster's correctness is measured against golden-master outputs
  #       produced by the mainframe baseline, and the comparison is
  #       byte-deterministic. Changing how the planner costs a query, how much
  #       work memory a sort receives, or how a numeric or timestamp is rendered
  #       can alter row ordering or output formatting, so a tuning change would
  #       surface as a parity failure whose cause is nowhere near the test that
  #       reports it. Performance tuning beyond what parity requires is out of
  #       scope, so the two security parameters merged in `locals` are the whole
  #       of what this module asserts; anything else is a reviewed, explicit
  #       override from an environment root.
  dynamic "parameter" {
    for_each = local.cluster_parameters

    content {
      name  = parameter.key
      value = parameter.value

      # WHY : Trade-offs: pending-reboot is applied to every parameter rather
      #       than immediate, and the cost of that is real but bounded. AWS
      #       accepts pending-reboot for both static and dynamic parameters,
      #       whereas immediate is REJECTED for a static one -- and
      #       rds.force_ssl is static on some Aurora PostgreSQL versions, so
      #       immediate would fail the apply outright on exactly the parameter
      #       that must not be omitted. The accepted cost is that a later change
      #       to a dynamic parameter waits for a reboot instead of taking effect
      #       at once. It does not affect the values this module cares about: the
      #       group is attached when the cluster is created, so every parameter
      #       in it is in force from the cluster's first boot.
      #       Alternatives Considered: per-parameter apply methods, which would
      #       let dynamic parameters apply immediately. Rejected because
      #       var.cluster_parameters is a map of name to value with nowhere to
      #       carry a third field, and widening it to a map of objects would push
      #       an AWS implementation detail into every environment root's tfvars
      #       to buy a faster path for parameters this module ships none of.
      apply_method = "pending-reboot"
    }
  }

  tags = local.tags
}



# -----------------------------------------------------------------------------
# The cluster.
#
# This is the resource that replaces the ten VSAM KSDS base clusters enumerated
# in the header. Every argument below that had a reasonable alternative carries
# the reasoning for the value chosen.
# -----------------------------------------------------------------------------

resource "aws_rds_cluster" "this" {
  cluster_identifier = local.cluster_identifier

  # WHY : Alternatives Considered: recorded on the file header, because the
  #       choice is decision D3 rather than an argument-local one -- a key-value
  #       store cannot express the baseline's RESTRICT delete behaviour, and the
  #       three-write posting unit of work at app/cbl/CBTRN02C.cbl:L424-L444
  #       needs a real multi-record transaction. See
  #       docs/adr/ADR-003-datastore-targets.md.
  engine = "aurora-postgresql"

  # WHY : Assumptions: this is the single easiest thing in the file to get wrong,
  #       so it is stated explicitly rather than left to the provider default.
  #       Serverless v2 capacity is expressed as a
  #       `serverlessv2_scaling_configuration` block on a PROVISIONED-mode
  #       cluster whose instances use the serverless instance class. It is NOT
  #       `engine_mode = "serverless"`, which selects Aurora Serverless v1 -- a
  #       different product with different scaling granularity, different pause
  #       semantics and no seconds_until_auto_pause control. Both spellings plan
  #       and apply, so choosing v1 by accident would produce a working cluster
  #       whose capacity behaviour silently differs from everything documented
  #       about this one.
  engine_mode = "provisioned"

  # WHY : Assumptions: no version is defaulted anywhere in this module, so the
  #       value is always a reviewed choice made in an environment root. The
  #       pairing that matters is with var.min_capacity: scaling to zero requires
  #       a recent enough PostgreSQL MINOR release, and the set of releases that
  #       permit it is not knowable from HCL, so a zero minimum on too old a
  #       minor fails at apply and reports against the capacity argument rather
  #       than against this one.
  engine_version = var.engine_version

  # WHY : Assumptions: the three values are governed by four API properties that
  #       are easy to violate and are enforced as `validation` blocks in
  #       variables.tf rather than restated here -- capacity runs from 0 to 256
  #       Aurora Capacity Units, moves in HALF-unit steps, the auto-pause delay
  #       is a whole number of seconds from 300 to 86,400, and a minimum of 0
  #       makes that delay MANDATORY while forcing the maximum to at least 1.
  #       The last of those is the one a reader would otherwise get wrong,
  #       because a valid-looking pair becomes invalid through a third input.
  #       seconds_until_auto_pause is nullable precisely so that a cluster
  #       holding a non-zero minimum passes nothing and the argument is omitted:
  #       supplying a delay there would read as though pausing were configured
  #       when it can never occur.
  # WHY : Trade-offs: a zero minimum buys a cluster that costs nothing while idle
  #       and accepts a resume delay on the order of fifteen seconds for the
  #       first connection after a pause. That cost lands differently on the two
  #       workloads this cluster serves, which is why the value is
  #       per-environment rather than global. For the nightly batch chain it is
  #       immaterial -- the chain is a scheduled state machine whose first state
  #       already brackets the run by quiescing online writes, so one resume at
  #       the front of it changes nothing observable. For interactive use it is a
  #       noted risk, because the delay is paid by whoever makes the first
  #       request after an idle period, which in a development environment is a
  #       person waiting on a screen. Hence zero in dev and above zero in prod.
  # WHY : Refactoring Rationale: this block replaces per-dataset manual capacity
  #       planning outright. Nine of the ten base clusters were hand-sized with
  #       CYLINDERS(1 5) on the single named volume VOLUMES(AWSHJ1) -- see
  #       app/jcl/ACCTFILE.jcl:37-38 -- and the tenth, USRSEC, was sized
  #       separately with TRACKS(45,15) and named no volume at all
  #       (app/jcl/DUSRSECJ.jcl:69). Ten independent allocations on one volume
  #       meant capacity was a per-file guess that had to be revisited by hand
  #       whenever any file grew; one ACU range replaces all ten with a bound
  #       the service enforces continuously.
  serverlessv2_scaling_configuration {
    min_capacity             = var.min_capacity
    max_capacity             = var.max_capacity
    seconds_until_auto_pause = var.seconds_until_auto_pause
  }

  # WHY : Refactoring Rationale: this is a literal and not an input, because the
  #       baseline it corrects had no encryption at rest for this data at all --
  #       all eight DEFINE FILE stanzas in app/csd/CARDDEMO.CSD declare
  #       RECOVERY(NONE) with FWDRECOVLOG(NO) (L9, L21, L33, L46, L59, L72, L84,
  #       L96) and no key management of any kind across the file's 505 lines.
  #       Exposing this as a variable would make an unencrypted financial ledger
  #       reachable by a tfvars edit, and the policy scan that checks it reports
  #       at HIGH, so the check is satisfied by construction rather than by a
  #       suppression. There is no environment in which false is the right value,
  #       which is the test for whether something should be configurable.
  storage_encrypted = true

  # WHY : Alternatives Considered: the AWS-managed aws/rds key, which is what
  #       RDS uses when no key is named. Rejected, and the input is required with
  #       no default so the fallback is unreachable: the managed key still
  #       reports as "encrypted" to every policy scan while giving up the key
  #       policy, the rotation schedule and the ability to revoke access that a
  #       customer-managed key exists to provide. That is a silent downgrade, so
  #       the module refuses to guess -- there is deliberately no
  #       `try(var.kms_key_arn, ...)` or null-coalescing fallback here. The key
  #       is one of the four customer-managed keys owned by infra/modules/kms,
  #       which this module consumes and does not create.
  kms_key_id = var.kms_key_arn

  # WHY : Assumptions: referencing the group this module created, rather than
  #       taking a group name as an input, is what guarantees the cluster and the
  #       subnet group cannot disagree about which subnets the cluster sits in.
  #       The isolation property itself is assumed of var.isolated_subnet_ids and
  #       is recorded on the subnet group above.
  db_subnet_group_name = aws_db_subnet_group.this.name

  # WHY : Alternatives Considered: creating the security group in this module,
  #       which is the more self-contained shape and is what many database
  #       modules do. Rejected because an Aurora security group is one half of a
  #       matched pair: the rule admitting the application tier here has a
  #       counterpart egress rule on the application security group, and
  #       infra/modules/network owns that side. Splitting the pair across two
  #       modules would mean a rule change needs edits in two places that no
  #       single plan checks against each other, and a group defined here could
  #       not reference an application group defined there without one module
  #       reaching into the other's state. The expected group admits the
  #       application tier on the database port and nothing else.
  vpc_security_group_ids = var.security_group_ids

  # WHY : Assumptions: this value is coupled to an ingress rule this module does
  #       not own. infra/modules/network admits application-tier traffic to the
  #       data tier on 5432, so the default is the value that matches the rule
  #       already written there rather than an arbitrary convention. Changed here
  #       alone, it produces a cluster that plans cleanly, applies cleanly and
  #       then refuses every connection, with the cause two modules away from the
  #       symptom.
  port = var.port

  # WHY : Assumptions: this module creates the DATABASE and stops. It creates
  #       none of the schemas, roles, tables, indexes or grants inside it, and
  #       this is the single most likely misunderstanding about the module's
  #       boundary. The eight schemas are created by
  #       data-migration/sql/V0__schemas_and_roles.sql together with the
  #       per-service Flyway migrations at
  #       services/*/src/main/resources/db/migration/V1__<schema>.sql. Creating
  #       them here was considered and rejected: schema ownership follows the
  #       service that owns the data, so a module that created all eight would
  #       make every service's schema change an infrastructure change, and Flyway
  #       could no longer version what it did not create.
  database_name = var.database_name

  # WHY : Assumptions: only the login NAME is set here. The password is not an
  #       argument of this resource at all -- manage_master_user_password below
  #       makes RDS generate and own it -- so this is the one half of the
  #       credential that is not a secret and does not belong in one. The name
  #       carries validation this module needs, in variables.tf: the lower-case
  #       identifier rule and the refusal of the RDS-reserved "rdsadmin".
  # WHY : Alternatives Considered: taking the password from a Secrets Manager
  #       entry generated by infra/modules/secrets and setting it here as
  #       master_password. Rejected because Terraform records every resource
  #       argument in state, so that shape relocates the credential into the
  #       state file instead of removing it, and it reintroduces the two-author
  #       problem this module's next paragraph records as already fixed. The
  #       break-glass path an operator needs is the RDS-managed secret published
  #       by this module as master_user_secret_arn.
  master_username = var.master_username

  # WHY : Refactoring Rationale: there is deliberately NO password argument in
  #       this file, of any spelling. The alternative -- reading the generated
  #       value out of Secrets Manager and passing it as a password argument --
  #       was rejected because Terraform records every resource argument in
  #       state, so it would move the credential out of the repository and into
  #       the state file rather than removing it. That is the same defect the
  #       migration is correcting, one layer along: the baseline stored an
  #       eight-character plaintext password in the record itself
  #       (app/cpy/CSUSR01Y.cpy:L21) inside a file defined with JOURNAL(NO) and
  #       RECOVERY(NONE) (app/csd/CARDDEMO.CSD:L94, L96). With this argument the
  #       credential is generated inside AWS, is rotatable by RDS, and is never
  #       materialised in a plan, in state, or in this repository -- which is what
  #       makes the no-secrets-in-source constraint structurally true rather than
  #       dependent on every reviewer catching it.
  # WHY : Refactoring Rationale: RDS is the SOLE owner of this credential, and
  #       that is now expressed once rather than twice. The module previously
  #       also required a `master_credential_secret_arn` naming a secret the
  #       sibling secrets module generated -- and never consumed it for anything
  #       except a cross-ARN precondition, because `manage_master_user_password`
  #       makes RDS create and populate a secret of its own. Two authorities
  #       existed for one credential: the cluster authenticated against the
  #       RDS-managed value while an operator following the input contract would
  #       have read the other one, which is a break-glass path that silently does
  #       not open. The unused input is therefore removed here and the duplicate
  #       secret is removed from infra/modules/secrets, leaving one credential
  #       with one owner, published by this module as `master_user_secret_arn`.
  # WHY : Refactoring Rationale: the secret RDS creates is encrypted with the
  #       SECRETS key, not the cluster's data key. The earlier reasoning -- that a
  #       master credential is as sensitive as the data it unlocks, so one key
  #       means one policy and one rotation schedule to audit -- had the domain
  #       boundary backwards. infra/modules/kms provisions four keys precisely so
  #       that a data class and its credentials are separable: the Aurora key's
  #       trust list names the principals that read and write ENCRYPTED DATA,
  #       while the secrets key's names the principals that read STORED
  #       CREDENTIALS, and those two sets are deliberately different. Encrypting
  #       the credential with the data key means every principal granted decrypt
  #       for cluster storage can also decrypt the master credential, which
  #       collapses the boundary the four keys exist to draw -- and it does so
  #       invisibly, because both configurations report the cluster and its
  #       secret as encrypted with a customer-managed key.
  # WHY : Assumptions: this is the only argument in the module that names a key
  #       other than var.kms_key_arn, so the two inputs are shape-checked
  #       independently in variables.tf and compared for partition, region and
  #       account by the precondition below.
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.secrets_kms_key_arn

  # WHY : Refactoring Rationale: schema bootstrap, service-credential rotation
  #       and the nightly AnalyzeTables state use the RDS Data API so their
  #       Lambda packages remain boto3-only and need no VPC-attached PostgreSQL
  #       driver. variables.tf fixes this true to prevent a deployable but
  #       administratively unreachable cluster.
  enable_http_endpoint = var.enable_http_endpoint

  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.this.name

  # WHY : Refactoring Rationale: this is a recovery capability the baseline did
  #       not have, not a storage setting. Every one of the eight CSD file
  #       stanzas declares RECOVERY(NONE), FWDRECOVLOG(NO) and
  #       BACKUPTYPE(STATIC), so a wrongly-posted or corrupted record in the
  #       mainframe data tier was recoverable only from whatever static backup
  #       had last been taken out of band. variables.tf floors this at 1 because
  #       Aurora has no notion of disabled automated backups and rejects 0, which
  #       means the module cannot be configured into a no-backup posture even by
  #       mistake.
  backup_retention_period = var.backup_retention_period

  # WHY : Assumptions: this window must not overlap the nightly batch window, and
  #       that window is defined outside this module -- a scheduler cron
  #       expression driving a state machine whose first and last states quiesce
  #       and resume online writes. The overlap matters because the batch chain
  #       is the one period when the cluster is under sustained write load with
  #       online writes deliberately held off, so a backup landing inside that
  #       bracket competes with exactly the work that must not be slowed.
  #       Nothing here can see that schedule, so the requirement is honoured by
  #       the environment roots that set both; null lets RDS assign a window,
  #       which is honestly unspecified rather than a guess that reads as a
  #       decision.
  preferred_backup_window = var.preferred_backup_window

  # WHY : Assumptions: the same non-overlap requirement as the backup window, for
  #       a stronger reason. Maintenance can restart the cluster, so a window
  #       intersecting the batch chain risks interrupting a run between its
  #       quiesce and resume states -- leaving online writes held off by a state
  #       machine whose later states never ran. Recovering from that is an
  #       operator action rather than a retry, which is why the roots pin this
  #       window instead of letting RDS choose it. Note the format differs from
  #       the backup window: this one takes a day-of-week prefix and that one does
  #       not, so variables.tf checks each against its own pattern.
  preferred_maintenance_window = var.preferred_maintenance_window

  # WHY : Assumptions: a snapshot outlives the cluster it came from, and that is
  #       what makes this worth setting. An untagged snapshot appears in cost
  #       allocation attributed to nothing and in an inventory owned by nobody,
  #       and once the cluster is gone there is no resource left to infer its
  #       owner, environment or cost centre from -- the information cannot be
  #       reconstructed later, only lost at the moment the snapshot is taken.
  copy_tags_to_snapshot = var.copy_tags_to_snapshot

  # WHY : Refactoring Rationale: exporting the engine log restores an audit trail
  #       the baseline never had for its data tier. The only operational record
  #       the mainframe produced was print output routed to the job spool --
  #       `//SYSPRINT DD SYSOUT=*` at app/jcl/ACCTFILE.jcl:23, :34 and :55, and
  #       the same pattern in every other batch job -- and the eight CSD file
  #       stanzas added no journalling on top of it, so there was no durable
  #       account of errors, connections or statement failures at the storage
  #       layer. Exporting rather than leaving the log on the instance is what
  #       makes it survive the instance.
  # WHY : Assumptions: naming a log type here is what causes RDS to create the
  #       log group, but how long that group keeps its data and what alarms read
  #       it belong to infra/modules/observability, which owns log groups,
  #       dashboards and alarms across the whole tree. A retention argument here
  #       would compete with that module for the same setting and whichever
  #       applied last would win silently, which is why no retention input exists
  #       in this module at all.
  enabled_cloudwatch_logs_exports = var.enabled_cloudwatch_logs_exports

  # WHY : Trade-offs: the two environments need opposite values here, and the
  #       consequence of each is specific rather than a matter of taste. In prod,
  #       protection guards a financial ledger against a mistyped destroy that is
  #       unrecoverable beyond the backup window. In dev, protection would BLOCK
  #       `terraform destroy` outright, and clean teardown from that command is
  #       an acceptance criterion of this infrastructure and the documented path
  #       in docs/runbooks/teardown.md -- so leaving it enabled there would mean
  #       de-protecting the cluster by hand first, which is exactly the manual
  #       step the teardown path exists to remove. variables.tf defaults it to
  #       true because forgetting to raise it in prod loses the ledger while
  #       forgetting to lower it in dev produces a failed destroy and a clear
  #       error message, and the recoverable failure is the correct one to
  #       default into.
  deletion_protection = var.deletion_protection

  # WHY : Trade-offs: the same dev-versus-prod asymmetry as deletion protection,
  #       but a DIFFERENT failure mode, which is why the two are separate inputs
  #       rather than one flag. Deletion protection blocks the destroy entirely;
  #       a final snapshot lets the destroy proceed and leaves a retained copy
  #       behind. Prod wants that copy as its last recoverable state; dev wants
  #       teardown to leave nothing to pay for or clean up, so it needs both
  #       relaxed. Collapsing them into a single flag would hide that they fail
  #       differently.
  # WHY : Assumptions: RDS requires an identifier whenever a final snapshot is
  #       taken and rejects the pairing if it is missing, so the identifier is
  #       supplied conditionally rather than unconditionally -- passing a name
  #       alongside skip_final_snapshot = true would describe a snapshot that is
  #       never created, and reading this file would then suggest dev retains one.
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : local.final_snapshot_identifier

  # WHY : Trade-offs: deferring is the default and its cost is real, so it is
  #       named rather than glossed. While a change is pending, the running
  #       cluster does not match the plan a reviewer approved, so a subsequent
  #       plan can show a pending change that looks like drift and is not, and
  #       two changes made before the window are applied together rather than in
  #       the order they were reviewed. Applying immediately removes that gap but
  #       can restart the cluster outside the window, mid-day, against a
  #       financial ledger. The reviewable-gap cost is accepted because it is
  #       visible and temporary while an unscheduled interruption is neither, and
  #       exposing it as an input makes an urgent change an explicit per-change
  #       decision instead of a standing posture.
  apply_immediately = var.apply_immediately

  tags = local.tags

  lifecycle {
    # WHY : Assumptions: this is the one invariant in the module that no
    #       `validation` block in variables.tf can express, which is why it is a
    #       precondition here rather than there. A `validation` sees a single
    #       variable; this rule compares two. Both keys are produced by
    #       infra/modules/kms and wired in by an environment root, so a root that
    #       passed one of them from another account, another region or a
    #       pre-existing key elsewhere would produce two individually valid ARNs
    #       that cannot both serve this cluster -- and the defect would be in the
    #       root's `module` block rather than in either value. RDS rejects a key
    #       outside the cluster's own region, so the check is real rather than
    #       decorative even though the common wiring makes it hold trivially.
    #       Both ARNs are already shape-checked in variables.tf, which guarantees
    #       enough colon-separated fields for the index reads below to be safe.
    # WHY : Alternatives Considered: leaving this to apply time. Rejected because
    #       the two failures are not equivalent: RDS would reject the key when
    #       the cluster is created, after the subnet group and the parameter
    #       group already exist, and would report a key error rather than the
    #       cross-module mis-wire that caused it. A precondition fails the plan
    #       with nothing created and names the constraint that was violated.
    #       This is deliberately NOT a restatement of the capacity rules, which
    #       variables.tf owns and which are enforced exactly once, there.
    precondition {
      condition = (
        split(":", var.kms_key_arn)[1] == split(":", var.secrets_kms_key_arn)[1] &&
        split(":", var.kms_key_arn)[3] == split(":", var.secrets_kms_key_arn)[3] &&
        split(":", var.kms_key_arn)[4] == split(":", var.secrets_kms_key_arn)[4]
      )
      error_message = "The kms_key_arn and secrets_kms_key_arn must name the same AWS partition, region and account, because both keys serve this one cluster -- the first encrypts its storage and backups, the second the master credential RDS manages for it. Compare fields 2, 4 and 5 of each ARN: the data key is partition \"${split(":", var.kms_key_arn)[1]}\", region \"${split(":", var.kms_key_arn)[3]}\", account \"${split(":", var.kms_key_arn)[4]}\", while the secrets key is partition \"${split(":", var.secrets_kms_key_arn)[1]}\", region \"${split(":", var.secrets_kms_key_arn)[3]}\", account \"${split(":", var.secrets_kms_key_arn)[4]}\". Both values come from infra/modules/kms, so the defect is in the module wiring in the calling environment root rather than in either ARN on its own."
    }
  }
}

resource "aws_ssm_parameter" "connection" {
  for_each = {
    host     = aws_rds_cluster.this.endpoint
    port     = tostring(aws_rds_cluster.this.port)
    database = aws_rds_cluster.this.database_name
  }

  name        = "${local.parameter_name_root}/${each.key}"
  description = "CardDemo ${var.environment} Aurora ${each.key}; generated from the live cluster resource."
  type        = "String"
  value       = each.value

  # WHY : Assumptions: connection coordinates are identifiers, not credentials,
  #       so String is deliberate. Passwords remain in Secrets Manager and no
  #       SecureString value is duplicated into Parameter Store.
  tags = local.tags
}



# -----------------------------------------------------------------------------
# The writer instance -- and the reader instance that is deliberately absent.
#
# ONE instance is declared here. A reader looking for a second one, or for a
# `count` over reader instances, should find this explanation instead:
#
# WHY : Alternatives Considered: an Aurora reader instance to serve reporting
#       reads. Rejected, and the absence is load-bearing rather than an
#       oversight. Reporting owns no tables at all: the reporting service reads
#       through read-only cross-schema VIEWS on this writer, under a dedicated
#       database role holding SELECT-only grants created by
#       data-migration/sql/V0__schemas_and_roles.sql. A replica would therefore
#       add a second always-on instance to pay for AND introduce replica-lag
#       semantics that the baseline has no equivalent of -- the ten VSAM files it
#       replaces had a single copy each, so a report reading a slightly stale
#       balance is a behaviour this system has never had. That matters beyond
#       cost, because correctness here is measured against golden-master outputs:
#       a report that observed a pre-update balance would be a parity failure
#       whose cause is a topology decision rather than any logic in the report.
#       A SELECT-only role over views on the writer satisfies the requirement
#       with neither cost.
# WHY : Alternatives Considered: an Aurora Global Database, a
#       `replication_source_identifier`, or any cross-region secondary. Rejected
#       as out of scope: the deployment is single-region across three
#       availability zones, and multi-region disaster-recovery topology is
#       explicitly excluded. Declaring one here would make an unreviewed
#       topology reachable, since offering the resource is what makes it
#       buildable.
#       There is consequently no `count`, no `for_each` and no `promotion_tier`
#       anywhere in this file. Aurora needs no promotion tier to choose a writer
#       when there is only ever one instance to choose.
# -----------------------------------------------------------------------------

resource "aws_rds_cluster_instance" "this" {
  identifier         = local.writer_instance_identifier
  cluster_identifier = aws_rds_cluster.this.id

  # WHY : Assumptions: this specific class is what makes the instance draw its
  #       capacity from the cluster's serverlessv2_scaling_configuration. A
  #       fixed-size class such as db.r6g.large would be accepted by both the
  #       provider and RDS, would silently IGNORE the configured ACU range, and
  #       would bill for provisioned capacity around the clock -- so the two
  #       halves of the Serverless v2 shape are only correct together. Naming it
  #       as a literal rather than an input is deliberate: a fixed class here
  #       would contradict the capacity block above, and there is no environment
  #       in which that combination is wanted.
  instance_class = "db.serverless"

  # WHY : Assumptions: referencing the cluster's own attributes makes the two
  #       provably consistent and removes a class of perpetual diff. Passing
  #       var.engine_version here independently would let the instance and the
  #       cluster disagree after RDS applied a minor upgrade to the cluster, and
  #       every subsequent plan would then propose changing the instance back.
  engine         = aws_rds_cluster.this.engine
  engine_version = aws_rds_cluster.this.engine_version

  db_subnet_group_name = aws_db_subnet_group.this.name

  # WHY : Refactoring Rationale: a literal rather than an input, for the same
  #       reason as storage_encrypted. The instance sits in subnets with no route
  #       to the internet in either direction, so public accessibility would
  #       contradict the tier design outright -- and the contradiction would be
  #       silent, because the argument would apply cleanly and simply publish an
  #       endpoint that the routing then made unreachable, or reachable, depending
  #       on a subnet configuration this module cannot see. The policy scan checks
  #       this at HIGH, and it is satisfied by construction rather than by a
  #       suppression. There is no environment in which true is the right value.
  publicly_accessible = false

  # WHY : Refactoring Rationale: this replaces a capability the baseline had no
  #       equivalent of. A mainframe batch job's cost was visible only as the
  #       spool output cited on the cluster's log-export argument, produced after
  #       the job had finished, so a query pattern that degraded gradually left
  #       no trace to look back through. Continuous per-query telemetry is what
  #       makes a performance regression in a migrated batch job diagnosable at
  #       all rather than reproducible only by rerunning it.
  # WHY : Assumptions: the key and the retention period are both coupled to the
  #       enable flag and are therefore passed conditionally. RDS rejects a
  #       retention period or a key on an instance where Performance Insights is
  #       disabled, so passing them unconditionally would make
  #       performance_insights_enabled = false unusable -- the argument would be
  #       accepted by Terraform and refused by AWS. The telemetry is encrypted
  #       with var.kms_key_arn rather than a separate key because query text can
  #       echo the contents of a predicate, an account or card identifier among
  #       them, so it is treated as being exactly as sensitive as the data it was
  #       matched against.
  performance_insights_enabled          = var.performance_insights_enabled
  performance_insights_kms_key_id       = var.performance_insights_enabled ? var.kms_key_arn : null
  performance_insights_retention_period = var.performance_insights_enabled ? var.performance_insights_retention_period : null

  # WHY : Assumptions: both values are deliberately the same ones the cluster
  #       uses. An instance maintenance window outside the cluster's would let
  #       RDS restart the single writer at a time the cluster's own window was
  #       chosen to avoid, which for a one-instance cluster is a full outage
  #       rather than a rolling one -- and the whole reason the cluster's window
  #       is pinned is to keep it clear of the nightly batch chain.
  preferred_maintenance_window = var.preferred_maintenance_window
  apply_immediately            = var.apply_immediately

  # WHY : Trade-offs: this is held false because the environment roots pin a
  #       LONG-TERM SUPPORT minor release, and AWS documents that staying on an
  #       LTS minor requires automatic minor version upgrade to be switched off
  #       -- left at the provider default of true, RDS would move the cluster
  #       off the reviewed LTS minor on its own schedule and the three-year
  #       support horizon the pin was chosen for would silently evaporate. The
  #       second effect is just as costly and less obvious: an out-of-band minor
  #       upgrade leaves var.engine_version describing a release the cluster is
  #       no longer running, so every subsequent plan shows a spurious version
  #       diff and a real one becomes impossible to see. Alternatives Considered:
  #       exposing this as a module input was rejected because it is not an
  #       environment-shaped choice -- it follows from the pinning strategy
  #       itself, so both roots would have to pass the same value and a root
  #       that passed true would quietly break its own version pin. Patch-level
  #       fixes are NOT forgone: Aurora patches clusters on an LTS minor to that
  #       release's latest patch version annually.
  auto_minor_version_upgrade = false

  tags = local.tags
}
