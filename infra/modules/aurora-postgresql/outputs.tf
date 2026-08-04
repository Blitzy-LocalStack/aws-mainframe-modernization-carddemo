# =============================================================================
# infra/modules/aurora-postgresql/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete public contract of the aurora-postgresql module -- every value
#   a calling root may read from it, and nothing else. The module provisions
#   the Aurora PostgreSQL Serverless v2 cluster that replaces the ten VSAM KSDS
#   base clusters and three alternate indexes of the CardDemo mainframe
#   application, each of which was formerly created by an in-stream IDCAMS
#   DEFINE CLUSTER such as app/jcl/ACCTFILE.jcl:36 (KEYS(11 0),
#   RECORDSIZE(300 300)) and app/jcl/TRANFILE.jcl:49 (KEYS(16 0),
#   RECORDSIZE(350 350)). Those baseline trees are REFERENCE-ONLY: they are
#   cited below to ground a decision, and never edited.
#
#   This file carries more weight than its length suggests, because the ten
#   values below are the ONLY channel through which the rest of the platform
#   learns where its database is and what it is called. A calling environment
#   root writes them into SSM Parameter Store, and each service reads them
#   there at startup through its Spring profile; the batch tasks receive them
#   the same way, as Step Functions container overrides. Nothing downstream
#   hard-codes an endpoint.
#
#   This file is also where the second half of the HCL documentation
#   obligation is discharged for this module. HCL has no docstring construct,
#   so the equivalent -- per the "HCL (Terraform)" section of
#   docs/CODE_DOCUMENTATION_STANDARD.md -- is a header block in every .tf file
#   plus a `description` on every `variable` and every `output`. variables.tf
#   carries the variable half. A Terraform output IS a module's return value,
#   and `description` is the only place HCL lets one carry documentation, so
#   each description below is that return value's docstring rather than a
#   courtesy: a caller reads it across a directory boundary, with no sight of
#   the resource the value came from.
#
# Parameters:
#   None, and the absence is deliberate rather than a section omitted. An
#   outputs.tf declares no `variable`, so this file accepts no input of its
#   own. The module's twenty-six inputs are declared in
#   infra/modules/aurora-postgresql/variables.tf, each with its own `type`,
#   `description` and `validation` there. Exactly one of them --
#   var.security_group_ids -- is read below in preference to a resource
#   attribute, and the reasoning for that one exception sits on its block.
#
# Returns:
#   Ten values, in four groups, in the order they appear below:
#
#     Connection ......... writer_endpoint, reader_endpoint, port,
#                          database_name
#     Identity ........... cluster_identifier, cluster_arn,
#                          cluster_resource_id
#     Network placement .. db_subnet_group_name, security_group_ids
#     Credential ......... master_user_secret_arn
#
#   Every one of the ten is a host name, a port, a name, an identifier or an
#   ARN. Not one of them is a credential, and that distinction is the entire
#   design of the fourth group rather than a happy accident.
#
#   What is deliberately NOT returned, recorded so that each absence reads as
#   a decision rather than an omission, because every one of them is a thing a
#   reader would reasonably expect a database module to publish:
#
#     - No password, no `secret_string`, no decoded secret version and no
#       connection string with credentials embedded in it. The credential
#       reference is published; the credential is not, and the difference is
#       argued on that block.
#     - No reader-instance identifier and no replica endpoint. There is no
#       reader instance to name.
#     - No Aurora Global Database identifier and no cross-region value. The
#       deployment is single-region across three availability zones, and
#       publishing such a value would imply a topology main.tf declines to
#       build.
#     - No writer-instance identifier. A caller has no use for it that the
#       cluster identifier does not serve, and exporting it would invite
#       instance-level wiring against a cluster whose single-instance shape is
#       a capacity decision rather than a contract.
#     - No schema, role or grant name. The module's boundary ends at the
#       database; see the database_name block.
#
# Errors:
#   This file raises none of its own. It declares no `validation` and no
#   `precondition`, and every value below is a direct read of one attribute or
#   one input rather than an expression that could fail. What it does carry are
#   two surprises a consumer can walk into, recorded here because neither is
#   visible from an output's name:
#
#     1. Five of the ten are unknown until apply, and which five was measured
#        against a plan of this module rather than assumed. The values AWS
#        assigns -- writer_endpoint, reader_endpoint, cluster_arn,
#        cluster_resource_id and master_user_secret_arn -- cannot exist before
#        the cluster does, so a plan renders each as "known after apply". A
#        caller may pass them into another resource's arguments freely, but
#        may not use them in a `count`, a `for_each` or a provider
#        configuration, each of which Terraform requires to resolve during
#        plan. The other five -- port, database_name, cluster_identifier,
#        db_subnet_group_name and security_group_ids -- do resolve during
#        plan, because each is an input or is composed from one, and
#        security_group_ids is read from the input precisely to keep it in
#        that group.
#     2. reader_endpoint resolves to the writer. Aurora publishes a reader
#        endpoint whether or not a reader instance exists, and this module
#        provisions exactly one instance. A consumer treating that address as
#        a scale-out read path gets a working connection to the writer, which
#        is not what the name implies -- so the block below says at length
#        what the name cannot.
#
#   Errors from the module as a whole surface through whichever environment
#   root called it, because a module is never planned or applied on its own.
#   They originate in the `validation` blocks in variables.tf and the
#   `precondition` on the cluster in main.tf, and are documented in those two
#   files rather than restated here.
#
# WHY (non-obvious design decisions):
#   - Trade-offs: NOTHING below is marked `sensitive`, and that is a decision
#     taken output by output rather than a default left in place. Two facts
#     drive it. First, `sensitive` is not a confidentiality control: it
#     suppresses display in plan and `terraform output`, while the value still
#     lands in state in cleartext. The control that actually holds here is
#     that no credential VALUE is published in the first place. Second, the
#     marking PROPAGATES -- every downstream expression built from a sensitive
#     value becomes sensitive too, so a datasource URL or an SSM parameter
#     composed from a redacted endpoint is itself redacted, hiding from a
#     reviewer the very wiring they are reviewing. The cost accepted is that
#     an endpoint and an ARN appear in plan output; both are already visible
#     in the AWS console to anyone able to read the plan, and the deploy and
#     data-migration runbooks need `terraform output` to return them so an
#     operator can transcribe and verify a value.
#   - Alternatives Considered: marking master_user_secret_arn `sensitive` on
#     the grounds that it is credential-adjacent. Rejected for a specific
#     reason rather than by the rule above: an ARN names a location and
#     confers no access, since reading what is at that location requires an
#     IAM policy this module does not grant. Redacting it would hide WHICH
#     secret is being wired to WHICH task role -- exactly the fact a reviewer
#     of a least-privilege grant has to check -- while protecting nothing,
#     because the ARN is in state either way.
#   - Alternatives Considered: composing the connection details into a single
#     ready-made JDBC or libpq URL and publishing that instead of the four
#     separate connection values. Rejected on two grounds. A useful URL is one
#     that carries its credential, which would put the credential in an output
#     and in state; and a credential-free URL still fixes a driver dialect and
#     a parameter spelling for every consumer, when the consumers are a Java
#     datasource, a Python ETL loader and a psql invocation in a runbook, which
#     spell the same connection three different ways.
#   - Alternatives Considered: reading the composed names from main.tf's
#     `locals` -- local.cluster_identifier and local.db_subnet_group_name are
#     both in scope here and would be known at plan time. Rejected because a
#     `local` read creates no dependency edge: the name would be published
#     before the cluster or the subnet group existed, so a consumer could
#     order an SSM parameter, a Step Functions state or an IAM policy ahead of
#     the resource it names. Reading the resource attribute makes the
#     dependency real, and Terraform then orders the caller's resources behind
#     the cluster without the caller writing a `depends_on`.
#   - Refactoring Rationale: publishing the location at all is the correction
#     being made. The baseline embedded its data location as a literal inside
#     the deployable -- app/csd/CARDDEMO.CSD:L2 names
#     DSNAME(AWS.M2.CARDDEMO.ACCTDATA.VSAM.KSDS) in the CICS resource
#     definition, and app/jcl/ACCTFILE.jcl:59 and app/jcl/TRANFILE.jcl:72
#     repeat the same dataset names as DSN= operands in JCL -- so moving the
#     data meant editing and redeploying the artifacts that read it. Here the
#     location is discovered at startup from Parameter Store instead, which is
#     why every consumer is required to read these values rather than carry
#     them.
#   - Assumptions: these ten NAMES are a one-way contract. Once
#     infra/envs/dev/main.tf and infra/envs/prod/main.tf reference them, a
#     rename breaks both roots at once and silently widens the change under
#     review from one module to the whole deployment. They are therefore
#     chosen to read naturally at the call site -- module.aurora.writer_endpoint
#     -- and are not churned. The same names are injected into this module's
#     README.md by infra/.terraform-docs.yml, whose freshness CI checks
#     without regenerating it, so adding or re-describing an output here
#     obliges a matching README update in the same change.
# =============================================================================


# -----------------------------------------------------------------------------
# Connection: what a consumer needs to open a session.
# -----------------------------------------------------------------------------

output "writer_endpoint" {
  description = <<-EOT
    DNS name of the cluster's writer endpoint, which is the single address
    every read and every write in the platform resolves to. The calling
    environment root publishes this into SSM Parameter Store, and each
    service reads it there at startup through its Spring profile to compose
    SPRING_DATASOURCE_URL; batch tasks receive it the same way, through Step
    Functions container overrides. No service, container image or tfvars file
    may hard-code it. Unknown until apply, and it changes if the cluster is
    ever replaced -- which is the reason consumers resolve it at startup
    rather than baking it in at build time.
  EOT

  # WHY : Assumptions: this is the writer address specifically, and on a
  #       single-instance cluster that distinction still matters. Aurora
  #       resolves the cluster endpoint to whichever instance is currently
  #       the writer, so it survives a failover or a replacement of the one
  #       instance, whereas the instance's own endpoint does not. Publishing
  #       the instance address instead would produce a value that works until
  #       the first time RDS moves the instance and then fails for a reason
  #       nothing in the consumer changed.
  value = aws_rds_cluster.this.endpoint
}

output "reader_endpoint" {
  description = <<-EOT
    DNS name of the cluster's reader endpoint. Aurora publishes this address
    whether or not a reader instance exists, and this module provisions
    exactly one instance -- the writer -- so it resolves to that same
    writer. It is exported for the reporting service, which reads through
    read-only cross-schema VIEWS under a dedicated SELECT-only database role
    rather than through a replica. It must not be treated as a lag-free
    scale-out read path: with no second instance behind it, it adds no
    capacity and gives no isolation from writer load. Unknown until apply.
  EOT

  # WHY : Assumptions: the absence of a reader instance is the load-bearing
  #       fact about this output, and it is stated here because the name
  #       implies the opposite. main.tf declares one aws_rds_cluster_instance
  #       and records why a replica was rejected: reporting owns no tables,
  #       so it reads views on the writer under a SELECT-only role created by
  #       data-migration/sql/V0__schemas_and_roles.sql, and a replica would
  #       add an always-on instance to pay for plus replica-lag semantics the
  #       baseline has no equivalent of. The ten VSAM files this cluster
  #       replaces each held a single copy, so a report observing a stale
  #       balance is a behaviour this system has never had -- and because
  #       correctness is measured against golden-master outputs, it would
  #       surface as a parity failure attributable to topology rather than to
  #       anything in the report.
  # WHY : Alternatives Considered: omitting this output entirely, on the
  #       grounds that a value which merely aliases another invites misuse.
  #       Rejected because omission is the weaker protection: a consumer that
  #       wanted a read path would compose the reader hostname by hand from
  #       the cluster identifier, which is both undocumented and unchecked.
  #       Publishing it with the explanation attached is what puts the
  #       constraint where the consumer is already looking.
  value = aws_rds_cluster.this.reader_endpoint
}

output "port" {
  description = <<-EOT
    TCP port the cluster listens on, for a consumer composing a datasource
    URL or a matching security-group rule. It is the same port the Aurora
    security group in infra/modules/network admits from the application
    tier, so a root that overrides var.port must change that rule in the
    same change: altered on one side only, the cluster still plans, still
    applies and still reports healthy to Terraform while refusing every
    connection.
  EOT

  # WHY : Assumptions: read from the cluster rather than echoed from
  #       var.port so that the published value is the port the cluster
  #       actually listens on rather than the one it was asked for. The two
  #       agree today; reading the resource means they cannot drift apart
  #       without this output following, which matters because a consumer
  #       uses this value to build both halves of a connection -- the URL and
  #       the firewall rule -- and a silent disagreement between them is the
  #       hardest form of this failure to diagnose.
  value = aws_rds_cluster.this.port
}

output "database_name" {
  description = <<-EOT
    Name of the initial database inside the cluster, for a consumer
    composing a datasource URL. It is a DATABASE and nothing more: this
    module creates none of the schemas, roles, tables, indexes or grants
    inside it. The eight schemas the application uses -- auth, account,
    card, ledger, reference, batch and authorization, plus the read-only
    cross-schema views the reporting service reads through -- are created by
    data-migration/sql/V0__schemas_and_roles.sql together with the
    per-service Flyway migrations at
    services/*/src/main/resources/db/migration/V1__<schema>.sql. A consumer
    that expects a schema to exist because the cluster does will not find
    one.
  EOT

  # WHY : Assumptions: the schema-ownership boundary is restated on this
  #       output because it is the single most likely misunderstanding about
  #       the module, and this value is where a consumer meets it -- a name
  #       that looks like it should be enough to connect and query. It is
  #       enough to connect. Creating the eight schemas here was considered
  #       and rejected in main.tf: schema ownership follows the service that
  #       owns the data, so a module that created all eight would turn every
  #       service's schema change into an infrastructure change, and Flyway
  #       could no longer version what it had not created.
  value = aws_rds_cluster.this.database_name
}


# -----------------------------------------------------------------------------
# Identity: how the cluster is named, and how a policy addresses it.
#
# Three values that a reader could easily take for interchangeable, and are
# not. One names the cluster to an operator, one names it to most IAM actions,
# and one names it to the two IAM surfaces that will not accept an ARN. Each
# block says which is which, because choosing wrongly produces a policy that
# parses, attaches and authorises nothing.
# -----------------------------------------------------------------------------

output "cluster_identifier" {
  description = <<-EOT
    Identifier RDS knows the cluster by, and the name it appears under in the
    console, on an invoice line and in a CloudTrail event. Consumed by the
    deploy and batch-operations runbooks, by the SSM steps that bracket the
    nightly batch window, and by any Step Functions state that acts ON the
    cluster rather than connecting to it. It is not a connection address:
    nothing is reachable at this value.
  EOT

  # WHY : Alternatives Considered: publishing local.cluster_identifier, which
  #       main.tf composes from var.name_prefix and which is therefore known
  #       at plan time. Rejected because a `local` read carries no dependency
  #       edge, so the name would be available before the cluster existed and
  #       a consumer could order an SSM parameter or an IAM policy naming a
  #       cluster that was not there yet. Reading the resource attribute makes
  #       Terraform order the caller's resources behind the cluster with no
  #       `depends_on` written anywhere.
  value = aws_rds_cluster.this.cluster_identifier
}

output "cluster_arn" {
  description = <<-EOT
    ARN of the cluster, for the IAM policy documents the environment root
    attaches to the ECS task roles and the batch execution role. It is the
    resource an rds:Describe*, snapshot or tagging action names. A reference
    that confers nothing on its own: every permission over this cluster comes
    from a policy that neither this module nor this output writes or grants.
    It is NOT the value an IAM database-authentication policy needs -- see
    cluster_resource_id. Unknown until apply.
  EOT

  # WHY : Assumptions: an ARN is an identifier rather than a credential, so
  #       it is published in the clear and unmarked. It is already visible to
  #       anyone who can read a plan or open the console, and a policy author
  #       needs to see exactly which cluster a grant names in order to review
  #       that the grant is narrow.
  value = aws_rds_cluster.this.arn
}

output "cluster_resource_id" {
  description = <<-EOT
    Immutable resource identifier AWS assigns the cluster -- the literal
    "cluster-" followed by an opaque suffix -- which is stable across a
    rename of the cluster identifier. This, and NOT cluster_arn or
    cluster_identifier, is the value that goes inside an IAM
    database-authentication resource ARN --
    `arn:<partition>:rds-db:<region>:<account>:dbuser:<this>/<db-user>` --
    and the value by which the cluster's Performance Insights metrics are
    addressed. Unknown until apply.
  EOT

  # WHY : Assumptions: this output exists because reaching for the ARN in
  #       those two places is the intuitive move and it fails silently. An
  #       rds-db:connect policy built from the cluster ARN is accepted by IAM,
  #       attaches cleanly and then matches no connection attempt at all, so
  #       the symptom is an authentication failure at the database with
  #       nothing wrong reported anywhere in the policy. Publishing the
  #       resource id alongside the ARN, with the difference stated, is what
  #       stops that policy from being written.
  # WHY : Assumptions: it is also the value that survives a rename. The
  #       cluster identifier is composed from var.name_prefix, so a prefix
  #       change moves it; the resource id does not move, which is what makes
  #       it the right anchor for a long-lived IAM statement and for
  #       correlating Performance Insights history across a rename.
  value = aws_rds_cluster.this.cluster_resource_id
}


# -----------------------------------------------------------------------------
# Network placement: where the cluster sits, for the caller that has to build
# the other half of a rule this module does not own.
# -----------------------------------------------------------------------------

output "db_subnet_group_name" {
  description = <<-EOT
    Name of the DB subnet group the cluster is placed in, for a consumer that
    needs to describe or reference the cluster's network placement. The group
    spans the ISOLATED data-tier subnets -- those with no route to the
    internet in either direction, neither to an internet gateway nor to a NAT
    gateway -- and not the private application subnets that carry the ECS
    tasks. Exporting the name does not make the group reusable for another
    engine or cluster: it is built for this one.
  EOT

  # WHY : Assumptions: read from the aws_db_subnet_group resource this module
  #       owns rather than from the cluster's echo of the same name, so the
  #       output depends on the group itself. A consumer describing the
  #       group's subnets is asking about the group, and routing it through
  #       the cluster would make that read wait on the cluster and fail for
  #       cluster reasons.
  # WHY : Assumptions: the isolation this description asserts is a property of
  #       the subnets the caller supplied, which this module cannot verify. A
  #       root that passed the application subnets instead would still get a
  #       working cluster and a clean plan while silently giving the data tier
  #       an egress path, so the requirement is carried in words here, in
  #       var.isolated_subnet_ids' own description, and on the subnet group in
  #       main.tf. Repeating it on the output is deliberate: this is the value
  #       a reader inspects when checking where the cluster actually landed.
  value = aws_db_subnet_group.this.name
}

output "security_group_ids" {
  description = <<-EOT
    Identifiers of the security groups attached to the cluster, echoed back
    from the module's input so that a consumer composing the application
    tier's matching egress rule has the effective value without re-deriving
    it. infra/modules/network OWNS these groups and both halves of the rule
    pair; this module only attaches what it is handed, so nothing about a
    group's rules can be changed through this output.
  EOT

  # WHY : Alternatives Considered: reading
  #       aws_rds_cluster.this.vpc_security_group_ids, which is the value AWS
  #       reports as actually attached and is the more obviously authoritative
  #       source. Rejected for two measured reasons. The provider types that
  #       attribute as set(string) while the input is list(string), so routing
  #       the output through the cluster would silently drop ordering and make
  #       a caller's index read fail to parse. And the attribute is computed,
  #       so it would be unknown until apply, which would bar the caller from
  #       using it in the `for_each` that creating one egress rule per group
  #       naturally wants -- Terraform requires a `for_each` to resolve during
  #       plan.
  # WHY : Trade-offs: echoing an input means this output cannot detect a group
  #       attached to the cluster by something other than this module. That is
  #       accepted because no dependency edge is lost by the echo: the caller
  #       supplied these ids and already depends on the network module that
  #       produced them, so nothing is ordered any differently than it would
  #       be by reading the cluster.
  value = var.security_group_ids
}


# -----------------------------------------------------------------------------
# Credential: a reference, and deliberately nothing more.
# -----------------------------------------------------------------------------

output "master_user_secret_arn" {
  description = <<-EOT
    ARN of the Secrets Manager secret that RDS created and manages for the
    cluster's master credential. This is a REFERENCE to where the credential
    lives -- never the credential, which appears in no output, no variable,
    no plan and no state entry of this module. The environment root uses it
    to grant a task role secretsmanager:GetSecretValue on this one secret,
    together with kms:Decrypt on the same customer-managed key that encrypts
    the cluster; both grants are required, because the secret is encrypted
    with that key. This is the secret that unlocks the cluster -- NOT the
    pre-created secret supplied as var.master_credential_secret_arn, which
    this module reads only to check that the key and the secret share one
    partition, region and account. Consumers resolve the value at runtime
    from Secrets Manager, never from Terraform state. Unknown until apply.
  EOT

  # WHY : Refactoring Rationale: a reference is published where the baseline
  #       published the value. app/cpy/CSUSR01Y.cpy:L21 stores a password as
  #       plain text in a record, inside a file that
  #       app/csd/CARDDEMO.CSD:L94 and L96 define with JOURNAL(NO) and
  #       RECOVERY(NONE). Exporting a credential here -- even redacted from
  #       display -- would reproduce that defect one layer along, because a
  #       `sensitive` output is still written to state in cleartext and state
  #       is a file somebody has to store. RDS generates the credential
  #       inside AWS, rotates it there, and this module never sees it, which
  #       is what makes the no-secrets-in-source constraint structurally true
  #       instead of dependent on a reviewer noticing.
  # WHY : Alternatives Considered: publishing var.master_credential_secret_arn
  #       instead, or in addition. Rejected because only one of the two
  #       secrets actually opens the cluster and publishing both would invite
  #       a grant on the wrong one -- a task role holding
  #       GetSecretValue on the pre-created secret would read a value the
  #       cluster does not accept, and the failure would look like a wrong
  #       password rather than a wrong secret. main.tf sets
  #       manage_master_user_password, so RDS owns the live credential and
  #       this attribute is the only reference to it; the input ARN is used
  #       solely by the cluster's precondition and is left unexported for
  #       that reason.
  # WHY : Assumptions: the index read is safe because main.tf sets
  #       manage_master_user_password to a literal true rather than to a
  #       variable, so the provider always populates exactly one element of
  #       master_user_secret and the list can be neither empty nor longer.
  #       Were that flag ever made conditional, this expression would fail
  #       loudly on an empty list rather than quietly publish a null, which
  #       is the failure mode to prefer for a value that gates access to the
  #       data tier.
  value = aws_rds_cluster.this.master_user_secret[0].secret_arn
}
