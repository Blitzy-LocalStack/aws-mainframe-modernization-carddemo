# =============================================================================
# infra/modules/network/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `network` module -- the three-zone VPC
#   that every other module in this tree is placed into, with its
#   public, private-application and isolated-data subnet tiers, its NAT egress,
#   its eight interface endpoints, its S3 gateway endpoint and its FOUR
#   security groups. Every value a calling root may configure or share with a
#   dependent module is declared here and nothing else is: anything absent
#   from the list below is a property of the network fixed in main.tf, not a
#   per-environment choice.
#
#   Every variable declared here is consumed by main.tf or outputs.tf. That is
#   not a stylistic claim -- terraform_unused_declarations is enabled in
#   infra/.tflint.hcl, so a declaration nothing consumes is a lint failure. The
#   consequence for anyone extending this file: an input reserved for later use
#   cannot be added ahead of the code that reads it, and a derived value
#   belongs in main.tf's `locals` rather than here.
#
#   main.tf consumes all thirteen variables and outputs.tf republishes the two
#   shared ports so the environment root can pass the exact values enforced by
#   the security groups into ecs-service and aurora-postgresql. That round trip
#   is deliberate: a literal 8080 or 5432 repeated in three modules works only
#   until one copy changes, whereas a value exposed once by this module cannot
#   leave the listener and the rule that admits it disagreeing.
#
#   The distinction between a configurable value and a fixed property carries
#   more weight in this module than in any other, because the two environment
#   roots are required to be identical in TOPOLOGY and to differ only in sizing
#   and retention. An input is therefore admissible here only if varying it
#   cannot change the shape of the network. Two inputs below are genuine
#   per-environment levers: flow_log_retention_days, and
#   identity_provider_egress_cidrs, which an environment may narrow to its
#   provider's exact address ranges. Narrowing the latter changes what one
#   security group may reach, not the shape of the network - the tiers, their
#   routes and every group-to-group flow are identical whichever value is
#   supplied - so it passes the topology test the same way retention does.
#   The endpoint set and the two ports are shared contracts rather than
#   environment levers: the endpoint validation requires the architecture's
#   exact eight services, and both roots use the same port values. The five
#   candidates that failed the topology test are named at the foot of this file,
#   under deliberately absent inputs, so a reader who expects one learns it was
#   considered.
#
#   Refactoring Rationale: this count and the three copies of it in main.tf and
#   outputs.tf read twelve while fourteen variables were declared, two of them
#   naming one destination set twice -- identity_provider_egress_cidrs, which
#   main.tf reads, and an identity_provider_egress_cidr_blocks that nothing read.
#   The duplicate is withdrawn rather than wired, because the surviving input
#   carries the same open default for the same stated reason and validates more:
#   it is a set, so reordering cannot churn a plan, it rejects a malformed block,
#   and the rule itself refuses a value naming this VPC's own CIDR. All four
#   counts are then re-derived from the declarations rather than decremented,
#   which is what the hand-written-count gate in the infrastructure workflow
#   checks on every change.
#
# Parameters -- thirteen, of which one is required:
#
#   Naming and identity
#     name_prefix              string       Leading component of each Name tag.
#     environment              string       REQUIRED. Trailing component of
#                                           every Name tag.
#
#   Addressing
#     vpc_cidr                 string       Address space the tiers are carved
#                                           out of.
#     az_count                 number       Availability zones spanned, and so
#                                           subnets per tier and gateway count.
#     subnet_newbits           number       Prefix bits cidrsubnet adds when
#                                           carving each subnet.
#
#   Private service connectivity
#     interface_endpoint_services
#                              set(string)  Exact eight AWS services reached by
#                                           interface endpoint.
#     identity_provider_egress_cidrs
#                              set(string)  Destinations the application tier may
#                                           reach on 443 for Cognito, which is
#                                           not in the endpoint set above.
#     app_container_port       number       Shared ALB-to-container port.
#     database_port            number       Shared application-to-Aurora port.
#
#   Identity-provider reachability
#     identity_provider_egress_cidrs
#                              list(string) IPv4 destinations the application
#                                           group may reach on 443 for issuer
#                                           discovery, the key set and the user
#                                           pools API. One rule per entry.
#
#   Tagging
#     tags                     map(string)  Merged onto each taggable resource.
#
#   Flow-log configuration
#     flow_log_retention_days  number       Days the flow-log group retains
#                                           events.
#     flow_log_kms_key_arn     string       Customer-managed key encrypting the
#                                           flow-log group. Required unless the
#                                           opt-out below is set.
#     allow_service_managed_flow_log_encryption
#                              bool         Explicit opt-out permitting
#                                           service-default encryption; false,
#                                           and unsupported in either root.
#
#   Refactoring Rationale: this index carried app_container_port and database_port
#   TWICE -- once under "Private service connectivity" and again under a "Security
#   group ports" heading -- and the two copies described the same two inputs in
#   different words. The duplicate heading is removed rather than the first
#   listing, because the first sits in declaration order and this index states that
#   it is "a map of the surface, not a second copy of it": a surface map that lists
#   a member twice is already a second copy, and a reader counting inputs from it
#   would have counted thirteen where twelve are declared. The port entries under
#   "Private service connectivity" were extended with the cross-module purpose the
#   removed heading carried, so nothing is lost.
#
#   Each block below carries the full `type` and `description` that tflint's
#   terraform_typed_variables and terraform_documented_variables rules require.
#   The index above is a map of the surface, not a second copy of it, and it is
#   the descriptions -- never these one-liners and never the comments -- that
#   terraform-docs renders into this module's README, because
#   infra/.terraform-docs.yml sets read-comments to false.
#
# Return values:
#   None. A variables.tf declares no output. The VPC identifier, the three
#   per-tier subnet identifier lists and the three consumer-facing security
#   group identifiers this module publishes to its caller are declared in
#   infra/modules/network/outputs.tf. main.tf creates FOUR security groups; the
#   fourth guards the interface endpoints, has no consumer outside the module
#   that creates it, and is therefore deliberately not published -- so "three"
#   here counts published identifiers, not groups.
#
# Errors / Exceptions -- what fails, and when:
#   - `environment` has no default, so omitting it stops the calling root with
#     a missing-required-argument error BEFORE a plan is computed, rather than
#     tagging one environment's network with another environment's name.
#   - A malformed `vpc_cidr`, a `name_prefix` or `environment` outside the
#     permitted character set or length, an `az_count` or `subnet_newbits`
#     outside its bounds, an endpoint set that differs from the architecture's
#     eight services, an invalid shared port, or a `flow_log_retention_days`
#     outside the set CloudWatch Logs accepts each fails its `validation` block
#     AT PLAN TIME. Failing there is the entire reason those validations are
#     declared: the same mistakes otherwise surface as service errors partway
#     through an apply, once subnets and gateways exist and the run has to be
#     unwound.
#   - No input here can fail at apply time for a reason this file could have
#     caught, with one stated exception -- `flow_log_kms_key_arn` is not
#     shape-checked, for the reason recorded on that block.
#
# WHY (non-obvious design decisions):
#   - Assumptions: no input accepts a credential, an account identifier or a
#     Region, and no `default` holds one. `flow_log_kms_key_arn` names an ARN
#     as its CONTRACT and defaults to null, which is what keeps that true.
#     Identifiers travel INWARD only as caller-supplied values and OUTWARD as
#     outputs, so no literal ARN, account identifier, key id, Region, bucket or
#     table name appears anywhere in this file.
#   - Assumptions: this module configures no aws provider, so the Region and the
#     stack-wide tag set both arrive from the calling root's provider block
#     rather than from an input here; versions.tf records the same for the
#     provider version constraint. That one fact is why `region` is absent from
#     this surface and why `tags` merges rather than replaces.
#   - Where a comment below reasons about `terraform apply`, it describes what
#     an input MEANS at that point; it does not report on a provisioned stack.
#     This tree is authored and statically validated -- formatted, validated,
#     planned, linted and policy-scanned -- and applying it to a live account
#     is an operator action outside this scope.
#   - Lineage: flow logging has no counterpart in the baseline this module
#     stands beside. app/csd/CARDDEMO.CSD defines eight files, and each records
#     JOURNAL(NO) (L7) and RECOVERY(NONE) (L9), so there is no journalling or
#     recovery-log setting there to carry across and narrow toward. That
#     baseline is reference-only and keeps running unchanged; this tree adds a
#     path beside it rather than removing one.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and identity
# -----------------------------------------------------------------------------

# WHY : Trade-offs: the default spares each root from restating the project name
#       on a module it calls once, which is the common case; keeping it a
#       variable rather than a literal is what lets this module be reused under
#       a different naming scheme without editing it. The cost accepted is that
#       two callers may disagree about the prefix, so the name is not a
#       guaranteed constant across the tree. Every other directory under infra/
#       takes its prefix through a variable of this same name with this same
#       default, which is what makes one root's single value reach every module
#       that root calls.
variable "name_prefix" {
  description = "Leading component of the Name tag on every resource this module creates, ahead of the tier and the environment, giving the whole network one greppable identity shared with the rest of the stack. Lowercase letters, digits and hyphens only, no leading or trailing hyphen, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    # WHY : Assumptions: this prefix is concatenated into names across roughly
    #       thirty resources, several of which AWS bounds at 32 characters and
    #       restricts to this same character class -- so the bound is the
    #       tightest of the downstream limits rather than a preference, and the
    #       pattern rejects the space, the underscore and the uppercase letter
    #       that would otherwise reach a name the service refuses. Anchoring
    #       both ends on an alphanumeric additionally rejects a leading or
    #       trailing hyphen, which would double up against the separator placed
    #       either side of it. Trade-offs: a legitimately unusual prefix has to
    #       be brought inside these bounds first; catching it here costs one
    #       plan, catching it in the service costs a partial apply.
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit."
  }
}

# WHY : Alternatives Considered: defaulting this to "dev". Rejected, because a
#       defaulted environment name is the precise mechanism by which one
#       environment's VPC ends up carrying another environment's name tag: the
#       caller omits the argument, the module names the network anyway, and the
#       mistake survives review because the plan is clean and the tag reads as
#       deliberate. This name is the only thing distinguishing two otherwise
#       identical deployments in one account, so the safe failure mode is a
#       missing-required-variable error, not a mislabelled network. Requiring
#       the value costs each root one line.
#
# WHY : Alternatives Considered: an allow-list of accepted names, written as
#       `contains(["dev", "prod"], var.environment)`, which several sibling
#       modules in this tree do apply. Rejected for this module. dev
#       and prod are the two roots under infra/envs/, but this module has no
#       behavioural dependence on the name whatsoever -- it is a label
#       interpolated into tags, and nothing in main.tf branches on it -- so a
#       hard allow-list would buy no safety while making the module unusable
#       for a third environment without editing it. The character and length
#       checks below reject the values that actually break something
#       downstream, which is all this module can legitimately assert here.
variable "environment" {
  description = "Trailing component of the Name tag on the VPC, every subnet, every route table, every gateway, every endpoint and every security group, so one environment's network is distinguishable from another's in the same account. Required -- there is no default. Lowercase letters, digits and hyphens only, no leading or trailing hyphen, at most 16 characters."
  type        = string

  validation {
    # WHY : Assumptions: the bound is 16 rather than the 32 allowed for
    #       name_prefix because this component is appended AFTER the prefix and
    #       the tier, so it is the last contributor to a composed name and the
    #       one with the least room left within the same downstream limits. The
    #       character class matches name_prefix for the reason it holds there --
    #       the composed string reaches resources the service restricts to that
    #       class -- and anchoring both ends on an alphanumeric rejects the
    #       hyphen that would double up against the separator preceding it.
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.environment)) && length(var.environment) <= 16
    error_message = "environment must be 1 to 16 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit."
  }
}

# -----------------------------------------------------------------------------
# Addressing
#
# The three inputs below are one arithmetic rather than three independent
# knobs: main.tf derives all `3 * az_count` subnets from `vpc_cidr` by handing
# cidrsubnet a netnum range per tier plus `subnet_newbits` additional bits.
# Their validations therefore constrain one another -- subnet_newbits is
# checked against az_count rather than against a fixed minimum -- and changing
# any of the three moves every subnet together.
# -----------------------------------------------------------------------------

# WHY : Assumptions: a /16 divided by the default four additional bits yields
#       sixteen /20 blocks, of which nine are consumed at three availability
#       zones -- one public, one private-application and one isolated-data
#       subnet per zone -- leaving seven /20 blocks unallocated. Trade-offs:
#       that headroom is claimed address space no resource occupies, accepted
#       because a fourth tier or a peered range added later can be carved from
#       the same block without renumbering the nine subnets that already
#       exist, and renumbering a subnet means replacing it.
variable "vpc_cidr" {
  description = "IPv4 address space the VPC occupies, and the only addressing value this module takes. main.tf subdivides it into `3 * az_count` equally sized subnets -- one public, one private-application and one isolated-data subnet per availability zone -- each `subnet_newbits` bits longer than this prefix, so the block must be large enough to accommodate them all. Changing it after apply forces replacement of the VPC and of every subnet in it."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    # WHY : Refactoring Rationale: cidrhost accepts IPv6, although every subnet
    #       and VPC resource in this module uses the IPv4 `cidr_block` argument.
    #       cidrnetmask rejects IPv6 and malformed prefixes, while the explicit
    #       /16-/28 bound matches the range the VPC API accepts. `try` converts a
    #       malformed split or prefix into this input-specific plan-time message.
    condition = try(
      cidrnetmask(var.vpc_cidr) != "" &&
      tonumber(split("/", var.vpc_cidr)[1]) >= 16 &&
      tonumber(split("/", var.vpc_cidr)[1]) <= 28,
      false
    )
    error_message = "vpc_cidr must be a well-formed IPv4 CIDR block with a prefix from /16 through /28, for example 10.0.0.0/16. IPv6 blocks are not accepted by this IPv4-only network module."
  }
}

# WHY : Assumptions: three availability zones in one Region is the mandated
#       topology for this stack, recorded as decision D8 in
#       docs/adr/ADR-008-security-and-identity.md; multi-Region and
#       disaster-recovery topology are out of scope. It is an input so the
#       arithmetic in main.tf reads the count from one place instead of
#       repeating a literal 3 at every netnum, subnet and gateway.
#
# WHY : Assumptions: this is NOT a per-environment lever, which is the reading a
#       caller is most likely to arrive with. The two environment roots differ
#       only in sizing and retention and never in topology, and the reason
#       ADR-008 records is specific: a development environment with a different
#       network shape would not validate the production one, so a defect in the
#       three-zone routing would first appear in production. BOTH
#       infra/envs/dev and infra/envs/prod pass 3. An operator reaching for
#       this value to reduce development cost is reaching for the wrong one --
#       the levers that exist are database capacity, task count, retention and
#       price class.
#
# WHY : Alternatives Considered: accepting two zones so the module could be
#       exercised in a smaller Region. Rejected because the frozen topology is
#       three zones in every environment; accepting two would let a valid plan
#       omit one third of the subnets and NAT gateways, so development would no
#       longer rehearse production. A Region without three usable zones cannot
#       host this topology and must fail explicitly rather than receive a
#       different one.
variable "az_count" {
  description = "Number of availability zones the network spans, and therefore the number of subnets created in each of the three tiers and the number of NAT gateways. The only supported value is 3, the topology shared by dev and prod."
  type        = number
  default     = 3

  validation {
    # WHY : Assumptions: equality to the integer 3 rejects fractions and every
    #       alternate topology in one expression, so a caller cannot interpret
    #       the variable as a sizing lever merely because it is exposed.
    condition     = var.az_count == 3
    error_message = "az_count must be exactly 3. CardDemo's dev and prod environments share one three-availability-zone topology; two or more than three zones are unsupported topology changes."
  }
}

# WHY : Alternatives Considered: three `list(string)` inputs, one per tier,
#       each holding an explicit CIDR per zone. Rejected. At three zones that
#       is nine hand-maintained CIDRs which must be kept mutually
#       non-overlapping, kept in the same order as the zone list, kept
#       consistent with az_count, and kept identical in intent between two
#       roots -- and nothing checks any of those four properties, so the first
#       inconsistency surfaces as a subnet overlapping another or a tier
#       missing a zone. One derivation from one block cannot drift out of step
#       with itself.
#       Trade-offs: the caller loses control over exactly where each tier lands
#       inside the block, accepted in exchange for an arithmetic that cannot
#       become internally inconsistent. The addressing the derivation produces
#       is documented in this module's README.
variable "subnet_newbits" {
  description = "Number of bits cidrsubnet adds to the vpc_cidr prefix when carving each subnet, which fixes every subnet's size: at the default /16 and 4 additional bits each subnet is a /20. It must admit at least `3 * az_count` distinct subnets, because the three tiers are taken from consecutive netnum ranges of the one block rather than from separate per-tier address lists."
  type        = number
  default     = 4

  validation {
    # WHY : Assumptions: this is one combined addressing contract rather than
    #       three unrelated bounds. Adding n bits must yield at least
    #       `3 * az_count` netnums, the highest netnum actually used
    #       (`3 * az_count - 1`) must be accepted by cidrsubnet, and the resulting
    #       IPv4 prefix must be no narrower than /28. A /28 contains sixteen
    #       addresses, eleven usable after the five VPC reservations; anything
    #       narrower is rejected by the subnet API and cannot host a tier.
    #       `try` converts malformed cross-variable arithmetic into the explicit
    #       message below rather than exposing a cidrsubnet evaluation error.
    condition = try(
      var.subnet_newbits == floor(var.subnet_newbits) &&
      var.subnet_newbits >= 0 &&
      pow(2, var.subnet_newbits) >= 3 * var.az_count &&
      tonumber(split("/", var.vpc_cidr)[1]) + var.subnet_newbits <= 28 &&
      cidrsubnet(var.vpc_cidr, var.subnet_newbits, 3 * var.az_count - 1) != "",
      false
    )
    error_message = "subnet_newbits must be a non-negative whole number that carves all 3 * az_count subnets from vpc_cidr, admits highest netnum 3 * az_count - 1, and leaves a resulting IPv4 prefix no narrower than /28."
  }
}

# -----------------------------------------------------------------------------
# Private service connectivity
#
# These three values are contracts shared with sibling modules rather than
# independent environment-tuning knobs. main.tf consumes them in endpoint and
# security-group resources, while outputs.tf republishes the two ports so the
# calling root can pass the same values into ecs-service and
# aurora-postgresql.
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: an earlier update removed this input and moved
#       the eight names toward literals in main.tf. That shape can provision
#       the endpoints, but it removes the contract a root and generated module
#       documentation can inspect and makes an endpoint-set change look like an
#       implementation edit rather than a topology change. Restoring the set
#       keeps the architecture's exact private-service paths visible and
#       testable at the module boundary.
#
# WHY : Assumptions: the validation requires equality with the ten services
#       named by the target design -- not merely a syntactically valid list.
#       A missing entry silently sends that service's traffic through NAT, and
#       an extra entry adds another billed endpoint and another network path.
#       Neither is an environment variation: dev and prod must have the same
#       topology. S3 is absent because it uses the gateway endpoint created
#       unconditionally in main.tf rather than an interface endpoint.
# WHY : Refactoring Rationale: this set held EIGHT names and the exact-set
#       validation made that count authoritative, so the two services the runtime
#       actually needs and this list omitted could not be added by a root as a
#       variation -- the omission had to be corrected here. xray carries the
#       telemetry sidecar's trace export and cognito-idp carries issuer and
#       signing-key resolution plus the administrative pool operations; the
#       reasoning for each is recorded at the endpoint resource in main.tf. With
#       the application group's egress enumerated rather than allow-all, a service
#       missing from this set is not routed through NAT -- it is dropped at the
#       group, which is why an omission here is an outage and not a cost.
variable "interface_endpoint_services" {
  description = "Exact set of short AWS service names given private interface endpoints in every environment: ecr.api and ecr.dkr for image pulls, logs for delivery, secretsmanager for credentials, kms for envelope operations, sqs for messaging, states for workflow calls, ssm for configuration, xray for the telemetry sidecar's trace export and cognito-idp for identity-provider issuer, signing-key and administrative calls. main.tf expands each short name into its Region-qualified service name; S3 is excluded because it uses the separate gateway endpoint."
  type        = set(string)
  default = [
    "ecr.api",
    "ecr.dkr",
    "logs",
    "secretsmanager",
    "kms",
    "sqs",
    "states",
    "ssm",
    "xray",
    "cognito-idp",
  ]

  validation {
    # WHY : Assumptions: set equality checks both halves of the contract in one
    #       expression -- no required service may be missing and no
    #       unreviewed service may be added. A character-shape regex alone
    #       would accept both errors and the plan would remain green.
    condition = var.interface_endpoint_services == toset([
      "ecr.api",
      "ecr.dkr",
      "logs",
      "secretsmanager",
      "kms",
      "sqs",
      "states",
      "ssm",
      "xray",
      "cognito-idp",
    ])
    error_message = "interface_endpoint_services must contain exactly ecr.api, ecr.dkr, logs, secretsmanager, kms, sqs, states, ssm, xray and cognito-idp; the endpoint set is identical in every environment."
  }
}

# WHY : Refactoring Rationale: this input exists because the identity provider
#       is the one managed dependency on a task's start-up path that has no
#       private interface endpoint in the specified eight-service set. Every
#       service is an OAuth2 resource server that resolves its Cognito issuer and
#       fetches the JWK set while the application context is still starting, so
#       with no egress rule for it the tasks do not degrade - they fail their
#       health checks and never enter service. Making the destination an input
#       rather than a literal in main.tf is what keeps the rule narrowable: an
#       environment that knows its provider's addresses can pin them here without
#       editing the module, and the value it used is visible in that
#       environment's tfvars rather than buried in a shared file.
#
#       Alternatives Considered: (a) omitting the input and writing 0.0.0.0/0
#       into main.tf directly - rejected, that is indistinguishable from the
#       allow-all rule this module deliberately removed and gives an operator no
#       way to tighten it; (b) making the input required with no default -
#       rejected, there is no value that is correct in every account, so a
#       required input would make the module unusable until an operator
#       researched provider addresses that AWS may change underneath them;
#       (c) a boolean toggle - rejected, it can only choose between "open" and
#       "broken" and cannot express a narrowed set at all.
#       Trade-offs: the default admits any destination on 443, so out of the box
#       this is one open outbound flow. What bounds it is that it is TLS-only,
#       that it is a named rule carrying its purpose in its description so it is
#       identifiable in a plan diff and in a flow log, that it is the only such
#       rule in the module, and that an environment can replace the default with
#       an exact set. A set type is used rather than a list so ordering cannot
#       churn the plan, and main.tf keys one rule per entry so tightening the
#       set removes rules individually.
variable "identity_provider_egress_cidrs" {
  description = "Destination CIDR blocks the application security group may reach on TCP 443 for Cognito identity-provider calls: the JWK set every service fetches at start-up and the user-pool admin API auth-service calls. One egress rule is created per entry. The default permits any destination because the provider is a public regional endpoint whose addresses AWS may change; an environment that has determined the exact ranges may narrow this set without editing the module."
  type        = set(string)
  default     = ["0.0.0.0/0"]

  validation {
    # WHY : Assumptions: an empty set is rejected rather than treated as "no
    #       egress needed". Silently creating no rules would leave every service
    #       unable to start, and the failure would surface as a task that never
    #       passes its health check rather than as a plan error - the most
    #       expensive place to discover it. An operator who genuinely wants no
    #       identity-provider egress has to say so by removing the rule, which
    #       is a code change that gets reviewed.
    condition     = length(var.identity_provider_egress_cidrs) > 0
    error_message = "identity_provider_egress_cidrs must contain at least one CIDR block; every service fetches the Cognito JWK set during start-up, so an empty set prevents all nine services from entering service."
  }

  validation {
    # WHY : Assumptions: cidrhost fails the plan on anything that is not a
    #       well-formed CIDR block, so it validates shape without this module
    #       carrying an address-format regex of its own. Alternatives Considered:
    #       a regex over dotted-quad plus prefix length. Rejected - it would
    #       accept 999.0.0.0/8 and reject nothing that matters, whereas the
    #       built-in function applies the provider's own parser.
    condition = alltrue([
      for block in var.identity_provider_egress_cidrs :
      can(cidrhost(block, 0))
    ])
    error_message = "Every entry in identity_provider_egress_cidrs must be a well-formed IPv4 CIDR block, for example 0.0.0.0/0 or 52.94.0.0/16."
  }
}

# WHY : Refactoring Rationale: this input was removed even though 8080 appears
#       in the network rule, the ecs-service target group and every service
#       listener. Restoring it lets outputs.tf give the calling root one value
#       to pass to ecs-service, so changing the listener can never leave the
#       security group admitting a different port.
variable "app_container_port" {
  description = "TCP port admitted from the load-balancer security group to the application security group and republished for the calling root to pass into every ecs-service container and target group. The default 8080 matches the Spring Boot listeners; using the output rather than repeating the number keeps the rule and the listener aligned."
  type        = number
  nullable    = false
  default     = 8080

  validation {
    # WHY : Assumptions: every service image runs as a non-root user, so a port
    #       below 1024 cannot be bound by the process even though a security
    #       group can admit it. The whole-number test prevents a fractional
    #       value reaching the provider and failing with a less useful type
    #       conversion diagnostic.
    condition     = var.app_container_port == floor(var.app_container_port) && var.app_container_port >= 1024 && var.app_container_port <= 65535
    error_message = "app_container_port must be a whole number from 1024 to 65535; every service container runs as a non-root user."
  }

  validation {
    # WHY : Assumptions: 443 is refused specifically, and the reason is a
    #       property of another rule rather than of this port. The interface
    #       endpoint ENIs share the application security group, so the
    #       task-to-endpoint flow is a SELF-referencing rule on that group at
    #       443. While no task listens on 443 that rule reaches no application
    #       listener; set this input to 443 and the same rule would silently
    #       become a task-to-task allowance, which is the widening the isolated
    #       tiers exist to prevent. Refusing the value here makes that bound
    #       enforced rather than merely true today.
    #       Alternatives Considered: allowing 443 and narrowing the self
    #       reference to the endpoint ENIs' addresses. Rejected because an
    #       endpoint ENI's address is assigned at creation and is not knowable
    #       when the rule is planned, so the narrowing cannot be expressed.
    condition     = var.app_container_port != 443
    error_message = "app_container_port must not be 443; the application security group self-references 443 for the interface endpoint ENIs, so a 443 container port would turn that rule into a task-to-task allowance."
  }
}

# WHY : Refactoring Rationale: this input was removed even though 5432 appears
#       in both the network boundary and the aurora-postgresql module. Restoring
#       it lets the root pass `module.network.database_port` into Aurora, so a
#       custom database port cannot produce a cluster that applies cleanly and
#       is then unreachable through a stale security-group rule.
variable "database_port" {
  description = "TCP port admitted from the application security group to the isolated-data security group and republished for the calling root to pass into aurora-postgresql. The default 5432 matches PostgreSQL; the accepted range is the range Aurora PostgreSQL supports."
  type        = number
  nullable    = false
  default     = 5432

  validation {
    # WHY : Assumptions: Aurora PostgreSQL accepts ports 1150 through 65535.
    #       Reusing that service bound here is what keeps the security rule and
    #       the database module capable of accepting exactly the same values,
    #       rather than allowing a port in one module that the other rejects.
    condition     = var.database_port == floor(var.database_port) && var.database_port >= 1150 && var.database_port <= 65535
    error_message = "database_port must be a whole number from 1150 to 65535, the range Aurora PostgreSQL accepts."
  }
}

# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

# WHY : Assumptions: the calling root's `provider "aws"` block sets
#       `default_tags`, which the provider applies to every resource it
#       creates, and this module inherits that provider rather than
#       configuring one of its own. Tags supplied here are therefore MERGED
#       with the root's rather than replacing them, so this module is not the
#       sole tagging authority for the resources it creates -- a reader looking
#       for the cost-allocation or ownership tags will find them on the root,
#       not here. Without that note it is not possible to tell which of the two
#       is authoritative.
#       The only tag this module adds on its own initiative is a per-resource
#       Name, composed from name_prefix, the tier and environment. main.tf
#       merges this map UNDERNEATH that Name, so a caller cannot accidentally
#       overwrite the one tag distinguishing two resources of the same kind.
variable "tags" {
  description = "Additional tags merged onto every taggable resource this module creates, on top of the provider-level default_tags the calling root sets and underneath the per-resource Name tag this module composes. Network-specific tags belong here; tags common to the whole stack belong on the root's provider block, so that every module receives them without being passed them."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# Flow-log configuration
#
# VPC flow logs are this stack's audit trail for network traffic, and the two
# inputs below are the whole of what is configurable about them. That the logs
# are captured at all, and that they capture ALL traffic rather than only
# accepted or only rejected flows, are properties fixed in main.tf -- see the
# deliberately absent inputs at the foot of this file.
# -----------------------------------------------------------------------------

# WHY : Assumptions: log retention days is among the enumerated values the two
#       environment roots are permitted to set differently, because changing it
#       alters how much history is kept and what that costs, and alters nothing
#       about the shape of the network. Every addressing input above
#       deliberately is NOT such a lever, which is what makes this one worth
#       pointing out rather than leaving to be inferred: dev and prod are
#       expected to differ here, and to agree everywhere else in this file.
variable "flow_log_retention_days" {
  description = "Days the CloudWatch Logs group receiving this VPC's flow logs retains events before they age off, or 0 to retain them indefinitely. This is the one value in this module the dev and prod roots are expected to set differently, and it is the direct analogue of how long a mainframe job log was kept before it aged off the spool."
  type        = number
  default     = 30

  validation {
    # WHY : Assumptions: the set is closed because the service accepts these
    #       values and nothing else -- 45 is not a retention period, it is
    #       an InvalidParameterException raised during apply, after the VPC
    #       and every subnet already exist. Listing the accepted values here
    #       converts that apply-time API rejection into a plan-time message,
    #       so the run never starts.
    #       0 is included because the service reads it as never expire, which
    #       is a legitimate choice for an audit trail a compliance regime
    #       requires be kept; it is not the default, so indefinite retention
    #       is something a root asks for explicitly rather than inherits.
    condition = contains([
      0, 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731,
      1096, 1827, 2192, 2557, 2922, 3288, 3653,
    ], var.flow_log_retention_days)
    error_message = "flow_log_retention_days must be 0, meaning never expire, or one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

# WHY : Assumptions: the key is REQUIRED, and the requirement is enforced by the
#       validation on the block below rather than by removing the null default.
#       Requiring it outright would make this module -- which depends on no other
#       module -- unplannable on its own, because the ARN comes from the sibling kms
#       module. The gate keeps standalone planning available behind an explicit
#       boolean while making the encrypted path the only one a caller reaches by
#       default.
#       Trade-offs: with the opt-out taken, the log group falls back to CloudWatch
#       Logs service-default encryption, which is weaker than a customer-managed key
#       because the key is not one this account controls, rotates or revokes. That is
#       why the opt-out is named, defaulted false, and documented as unsupported for
#       either environment root.
#
# WHY : Refactoring Rationale: this block previously argued that the optional form
#       was acceptable "because both environment roots DO pass the customer-managed
#       key ARN the kms module produces, so the encrypted path is the one that
#       actually ships". Two things were wrong with resting the control there. It
#       made the encryption of a network audit trail a property of caller habit
#       rather than of the module's contract, so any new caller -- or a conditional
#       in an existing one that evaluated to null -- silently got the weaker path
#       with a clean plan. And it is precisely the reasoning a policy scan cannot
#       check, which the same note claimed to be avoiding: "stating that plainly is
#       what keeps the policy scan honest" describes a comment, and a comment is what
#       a suppression is. The requirement is now a plan-time condition, so the claim
#       and the enforcement are the same artifact.
#
# WHY : Assumptions: an ARN is named here as a CONTRACT, in a module that
#       hard-codes no identifier of any kind. This is configuration flowing IN
#       as a caller-supplied value, which is categorically different from an
#       identifier written into the tree. The default is null, and no literal
#       ARN, account identifier or key id appears anywhere in this file.
#       `nullable` is stated explicitly rather than left to its default so that
#       a root passing null EXPLICITLY gets null -- the service-default
#       encryption -- instead of silently falling back to this default.
#
# WHY : Alternatives Considered: for there being no shape validation here when
#       the other six inputs are validated, a regex asserting the
#       arn:<partition>:kms:<region>:<account-id>:key/<key-id> form, as
#       infra/bootstrap/variables.tf applies to its own key input. Rejected
#       here. The value both roots pass is an output of the kms module rather
#       than an operator literal, so its shape is guaranteed by construction,
#       and a regex tight enough to be worth writing would reject the
#       multi-Region key and alias forms the same service accepts. A wrong key
#       fails at apply with the service's own diagnostic, which names the key;
#       a wrong-shaped one such a regex admitted would fail identically.
variable "flow_log_kms_key_arn" {
  description = "ARN of a customer-managed KMS key with which to encrypt the CloudWatch Logs group receiving this VPC's flow logs. Required unless allow_service_managed_flow_log_encryption is explicitly set true, which is the opt-out reserved for planning this module in isolation without the kms module. Both environment roots pass the key the kms module produces."
  type        = string
  default     = null
  nullable    = true

  # WHY : Refactoring Rationale: this validation is NEW and it reverses how the
  #       null default behaves. The block above previously reasoned that null was
  #       acceptable because "both environment roots DO pass the customer-managed
  #       key ARN", and that was true of the tree as it stood -- but it made the
  #       encryption of a network audit trail a property of what every caller
  #       happens to pass rather than of what this module will accept. A caller who
  #       omitted the argument, or who passed a value that resolved to null through
  #       a conditional, got a flow-log group on service-default encryption, a clean
  #       plan and no diagnostic anywhere. That is failing OPEN on a security
  #       control, and the composability argument for it survives intact below as an
  #       explicit, named opt-out rather than as a silent default.
  # WHY : Assumptions: the opt-out is a separate boolean rather than a magic value
  #       in this string, because the two carry different information and a reviewer
  #       needs to see both. This variable answers "which key"; the boolean answers
  #       "is unencrypted acceptable here". A sentinel string such as "none" would
  #       fold the second question into the first, so a diff that turned encryption
  #       off would look like a diff that changed a key.
  # WHY : Alternatives Considered: removing the null default and making the ARN
  #       unconditionally required, which is the simplest fail-closed form.
  #       Rejected because this module deliberately depends on no other module, and
  #       an unconditional requirement makes `terraform plan` on the module alone
  #       impossible -- the isolation in which its addressing arithmetic and its
  #       endpoint validation are exercised. The gate keeps that capability while
  #       making its use deliberate and visible.
  # WHY : Assumptions: cross-variable references in a validation condition are
  #       available because versions.tf pins required_version to >= 1.15.0 and
  #       Terraform has supported them since 1.9. A lifecycle precondition on the
  #       log-group resource would express the same rule; a variable validation is
  #       chosen because it reports against the INPUT the caller got wrong rather
  #       than against a resource the caller did not write.
  validation {
    condition     = var.flow_log_kms_key_arn != null || var.allow_service_managed_flow_log_encryption
    error_message = "flow_log_kms_key_arn is required: the VPC flow-log group carries a network audit trail and must be encrypted with a customer-managed key. Pass the kms module's flow-log key ARN. To plan this module in isolation without the kms module, set allow_service_managed_flow_log_encryption = true explicitly, which is not a supported configuration for either environment root."
  }
}

# WHY : Assumptions: this input exists ONLY so that the fail-closed requirement on
#       flow_log_kms_key_arn has an explicit escape hatch, and it is declared as its
#       own variable so that using the hatch is a visible line in a caller's module
#       block rather than an omission. Its default is false, so the safe behaviour
#       is what a caller gets without deciding anything -- which is the property a
#       silent null default did not have.
# WHY : Assumptions: it deliberately does not appear in either environment root.
#       Both pass a real key, so both leave this at false, and a future edit that
#       introduced it into a root would be a one-line diff a reviewer cannot miss.
#       That visibility is the whole mechanism: the control is not stronger than
#       before in what it permits, it is stronger in what it makes someone say out
#       loud.
# WHY : Trade-offs: one more input on a module whose input surface is deliberately
#       narrow. Accepted because the alternative shapes are worse -- an
#       unconditional requirement removes standalone planning, and no requirement at
#       all is the defect being fixed.
variable "allow_service_managed_flow_log_encryption" {
  description = "Whether this module may create the VPC flow-log group on CloudWatch Logs service-default encryption instead of a customer-managed key. False, the default, makes flow_log_kms_key_arn required. True is reserved for planning this module in isolation without the kms module and is not a supported setting for the dev or prod roots."
  type        = bool
  default     = false
  nullable    = false
}

# =============================================================================
# Deliberately absent inputs
# -----------------------------------------------------------------------------
# Each value below is one a reader is likely to look for, because each is a
# common input on a VPC module and each has an obvious, reasonable form. None
# of them is absent by oversight. Adding any of them would also fail the same
# lint rule that governs everything above -- terraform_unused_declarations --
# until main.tf were changed to read it, which is the point at which the
# reasoning recorded here would have to be overturned rather than overlooked.
#
# Refactoring Rationale: TWO entries were removed from this block because each
# asserted the absence of an input that is declared above it. One read "No
# `interface_endpoint_services`" and one read "No application or database port
# input"; all three of those inputs exist, with defaults and validations, and the
# port pair carries its own Refactoring Rationale recording its restoration. An
# absent-inputs register is read as an authority on what the module does NOT take,
# so a stale entry is worse here than anywhere else in the file: a reader looking
# for the endpoint set would conclude it must be edited in main.tf and would not
# find the validated input that governs it. The port entry additionally claimed
# that "infra/modules/aurora-postgresql validates its port as exactly 5432" -- it
# validates the range 1150 to 65535 -- so removing the entry also removes a
# measured statement that was wrong. The reasoning worth keeping was already on the
# variables themselves and is not restated here.
#
#   - No `single_nat_gateway`. Alternatives Considered: a boolean collapsing
#     the per-zone NAT gateways to one, which is the standard development cost
#     saving and cuts the largest fixed charge in this tier. Rejected on two
#     independent grounds. First, the two environment roots differ only in
#     sizing and retention and never in topology, and the number of gateways is
#     topology, not size. Second, the reason ADR-008 records for that rule
#     applies exactly here: a development environment with a different network
#     shape would not validate the production one, so a defect in the
#     three-gateway routing would first appear in production. The availability
#     consequence is the concrete cost -- with one gateway, egress from two of
#     the three zones becomes a cross-zone data path, and the loss of the zone
#     holding that gateway takes egress from all three. Trade-offs: three hourly
#     gateway charges are accepted instead. Cost is reduced in this stack where
#     it can be -- database capacity, task count, retention and price class --
#     and not by thinning the network.
#
#   - No per-endpoint enable flags, such as `enable_sqs_endpoint` or
#     `enable_states_endpoint`. Alternatives Considered: one boolean per
#     endpoint, so a root could create only the endpoints it uses. Rejected:
#     all eight interface endpoints and the S3 gateway endpoint are this
#     module's responsibility, and the reason ADR-008 gives for them -- keeping
#     traffic to AWS APIs off the public path -- applies uniformly to all nine
#     rather than more strongly to some. A per-endpoint switch would let one
#     environment silently resolve a service to a public address while its plan
#     stayed clean and its security groups stayed unchanged, which is the
#     failure mode hardest to notice.
#
#   - No `create_*` or `enabled` module-level switch. Alternatives Considered:
#     a boolean gating every resource, so a root could call the module and
#     receive nothing. Rejected: a module that can be instantiated as a no-op
#     invites a root that half-configures its own network -- some resources
#     from the module, the rest written by hand beside it -- and the addressing
#     derivation above then has no single owner. A root that does not want this
#     network omits the module block.
#
#   - No `flow_log_traffic_type`. Alternatives Considered: exposing the choice
#     between logging accepted flows, rejected flows or all of them; ALL is
#     fixed in main.tf, where it carries its own why-comment. Rejected here
#     because a rejected-only log shows what was refused and not what
#     succeeded, an accepted-only log the reverse, and neither is an audit
#     trail of the traffic. There is also nothing in the baseline to narrow
#     toward: the eight file definitions in app/csd/CARDDEMO.CSD each record
#     JOURNAL(NO) (L7) and RECOVERY(NONE) (L9), so no journalling scope exists
#     there to carry across.
#
#   - No `region`. Assumptions: the Region comes from the calling root's
#     `provider "aws"` block, which this module inherits rather than
#     configuring, and main.tf reads it back through a `data "aws_region"`
#     lookup where it is needed -- to compose the Region-qualified service
#     names of the interface endpoints. A `region` input here could disagree
#     with the provider's, and the disagreement would not itself be an error:
#     resources would be created in the provider's Region while endpoint
#     service names were composed for the input's, which fails at apply with a
#     diagnostic naming neither input. One source for the Region cannot
#     disagree with itself.
#
# =============================================================================

