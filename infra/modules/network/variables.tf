# =============================================================================
# infra/modules/network/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `network` module -- the three-zone VPC
#   that every other module in this tree is placed into, with its
#   public, private-application and isolated-data subnet tiers, its NAT egress,
#   its ten interface endpoints, its S3 gateway endpoint and its FOUR
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
#   main.tf consumes all fourteen variables and outputs.tf republishes the two
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
#   cannot change the shape of the network. ONE input below is a genuine
#   per-environment lever: flow_log_retention_days. Refactoring Rationale: a second
#   was named here, identity_provider_egress_cidrs, on the reading that narrowing a
#   destination set "changes what one security group may reach, not the shape of the
#   network". That input is withdrawn together with the internet-egress rule it fed,
#   for the reasons recorded at the position it occupied, so the sentence naming it
#   as a lever would now describe an input a caller cannot pass.
#   The endpoint set and the two ports are shared contracts rather than
#   environment levers: the endpoint validation requires the architecture's exact
#   ten services, and both roots use the same port values. The five candidates that
#   failed the topology test are named at the foot of this file, under deliberately
#   absent inputs, so a reader who expects one learns it was considered.
#
#   Refactoring Rationale: this count has now moved twice, and both moves are worth
#   recording because the second reverses part of the first. It once read twelve
#   while fourteen variables were declared, two of them naming one destination set
#   twice -- identity_provider_egress_cidrs and an
#   identity_provider_egress_cidr_blocks that nothing read -- and the duplicate was
#   withdrawn, leaving thirteen. The surviving input has now been withdrawn too,
#   because its default opened application-tier egress to every public address,
#   leaving twelve. It has since moved once more, to THIRTEEN, with the addition of
#   permissions_boundary_arn: every IAM role this package creates carries the account
#   deployment boundary, and the flow-log role this module creates was the one role
#   that did not, so the input is what lets the caller bound it. It has now moved to
#   FOURTEEN, with the addition of approved_additional_interface_endpoint_services:
#   the interface-endpoint set is split into the EIGHT services AAP section 0.4.1.9
#   enumerates and the TWO approved additions beyond them, because one input carrying
#   ten names left the specification's eight unassertable anywhere -- the deviation
#   was described in prose and nothing could check it. Splitting the input is what
#   makes both halves exact sets, and the additions input is where the approval, the
#   functional need and the recurring cost of each addition are recorded. Every copy
#   of the count is re-derived from the declarations
#   rather than decremented by hand, which is what the hand-written-count gate in
#   the infrastructure workflow checks on every change.
#
# Parameters -- fourteen, of which two are required:
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
#                              set(string)  Exact EIGHT AWS services AAP section
#                                           0.4.1.9 enumerates, reached by
#                                           interface endpoint.
#     approved_additional_interface_endpoint_services
#                              set(string)  Exact TWO approved additions beyond
#                                           that eight -- the identity provider
#                                           and the trace-export target -- which
#                                           is what lets the application tier
#                                           need no public egress at all.
#     app_container_port       number       Shared ALB-to-container port.
#     database_port            number       Shared application-to-Aurora port.
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
#   Deployment boundary
#     permissions_boundary_arn string       REQUIRED. Same-account customer-managed
#                                           IAM policy ARN set as the permissions
#                                           boundary on the flow-log role this
#                                           module creates, so no capability it
#                                           composes can exceed the account
#                                           ceiling. Never created here.
#
#   Refactoring Rationale: this index carried app_container_port and database_port
#   TWICE -- once under "Private service connectivity" and again under a "Security
#   group ports" heading -- and the two copies described the same two inputs in
#   different words. The duplicate heading is removed rather than the first
#   listing, because the first sits in declaration order and this index states that
#   it is "a map of the surface, not a second copy of it": a surface map that lists
#   a member twice is already a second copy, and a reader counting inputs from it
#   would have counted one input more than is declared. The port entries under
#   "Private service connectivity" were extended with the cross-module purpose the
#   removed heading carried, so nothing is lost.
#
#   Refactoring Rationale: the same defect had SURVIVED for a second input. This
#   index also carried identity_provider_egress_cidrs twice -- once under "Private
#   service connectivity" and again under an "Identity-provider reachability"
#   heading -- and the two copies disagreed about its TYPE, one saying set(string)
#   and the other list(string) where set(string) is declared. That is worse than
#   the port duplication was: a reader reaching for the type reads whichever copy
#   they find first, and a list is ordered, so believing it would predict plan
#   churn on a reordering that a set cannot produce. The duplicate heading is
#   removed, the surviving entry states the declared type, and the purpose the
#   removed copy carried -- issuer discovery, the key set and the user-pool API,
#   one rule per entry -- is now recorded on the variable's own description and in
#   the WHY block above it, where a reader configuring the input is looking.
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
#   infra/modules/network/outputs.tf. main.tf creates THREE security groups and
#   publishes all three, so the created and published counts now agree.
#   Refactoring Rationale: this said FOUR created against three published, the
#   fourth being an internal group for the interface-endpoint ENIs. That group is
#   withdrawn -- the frozen plan specifies three (AAP section 0.5.1.12) -- and the
#   ENIs carry the application group, so the distinction this note existed to
#   explain no longer exists.
#
# Errors / Exceptions -- what fails, and when:
#   - `environment` has no default, so omitting it stops the calling root with
#     a missing-required-argument error BEFORE a plan is computed, rather than
#     tagging one environment's network with another environment's name.
#   - A malformed `vpc_cidr`, a `name_prefix` or `environment` outside the
#     permitted character set or length, an `az_count` or `subnet_newbits`
#     outside its bounds, an endpoint set that differs from the architecture's
#     ten services, an invalid shared port, or a `flow_log_retention_days`
#     outside the set CloudWatch Logs accepts each fails its `validation` block
#     AT PLAN TIME. Failing there is the entire reason those validations are
#     declared: the same mistakes otherwise surface as service errors partway
#     through an apply, once subnets and gateways exist and the run has to be
#     unwound.
#   - No input here can fail at apply time for a reason this file could have
#     caught, with one stated exception -- `flow_log_kms_key_arn` is not
#     shape-checked, for the reason recorded on that block.
#
#   Assumptions: no input accepts a credential, an account identifier or a
#   Region, and no `default` holds one. `flow_log_kms_key_arn` names an ARN as
#   its CONTRACT and defaults to null, which is what keeps that true.
#   Identifiers travel INWARD only as caller-supplied values and OUTWARD as
#   outputs, so no literal ARN, account identifier, key id, Region, bucket or
#   table name appears anywhere in this file.
#
#   Assumptions: this module configures no aws provider, so the Region and the
#   stack-wide tag set both arrive from the calling root's provider block rather
#   than from an input here; versions.tf records the same for the provider
#   version constraint. That one fact is why `region` is absent from this surface
#   and why `tags` merges rather than replaces.
#
#   Assumptions: flow logging has no counterpart in the baseline this module
#   stands beside. app/csd/CARDDEMO.CSD defines eight files, and each records
#   JOURNAL(NO) (L7) and RECOVERY(NONE) (L9), so there is no journalling or
#   recovery-log setting there to carry across and narrow toward. That baseline
#   is reference-only and keeps running unchanged; this tree adds a path beside
#   it rather than removing one.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and identity
# -----------------------------------------------------------------------------

# Trade-offs: the default spares each root from restating the project name
# on a module it calls once, which is the common case; keeping it a
# variable rather than a literal is what lets this module be reused under
# a different naming scheme without editing it. The cost accepted is that
# two callers may disagree about the prefix, so the name is not a
# guaranteed constant across the tree. Every other directory under infra/
# takes its prefix through a variable of this same name with this same
# default, which is what makes one root's single value reach every module
# that root calls.
variable "name_prefix" {
  description = "Leading component of the Name tag on every resource this module creates, ahead of the tier and the environment, giving the whole network one greppable identity shared with the rest of the stack. Lowercase letters, digits and hyphens only, no leading or trailing hyphen, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    # Assumptions: this prefix is concatenated into names across roughly
    # thirty resources, several of which AWS bounds at 32 characters and
    # restricts to this same character class -- so the bound is the
    # tightest of the downstream limits rather than a preference, and the
    # pattern rejects the space, the underscore and the uppercase letter
    # that would otherwise reach a name the service refuses. Anchoring
    # both ends on an alphanumeric additionally rejects a leading or
    # trailing hyphen, which would double up against the separator placed
    # either side of it. Trade-offs: a legitimately unusual prefix has to
    # be brought inside these bounds first; catching it here costs one
    # plan, catching it in the service costs a partial apply.
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit."
  }
}

# Alternatives Considered: defaulting this to "dev". Rejected, because a
# defaulted environment name is the precise mechanism by which one
# environment's VPC ends up carrying another environment's name tag: the
# caller omits the argument, the module names the network anyway, and the
# mistake survives review because the plan is clean and the tag reads as
# deliberate. This name is the only thing distinguishing two otherwise
# identical deployments in one account, so the safe failure mode is a
# missing-required-variable error, not a mislabelled network. Requiring
# the value costs each root one line.
#
# Alternatives Considered: an allow-list of accepted names, written as
# `contains(["dev", "prod"], var.environment)`, which several sibling
# modules in this tree do apply. Rejected for this module. dev
# and prod are the two roots under infra/envs/, but this module has no
# behavioural dependence on the name whatsoever -- it is a label
# interpolated into tags, and nothing in main.tf branches on it -- so a
# hard allow-list would buy no safety while making the module unusable
# for a third environment without editing it. The character and length
# checks below reject the values that actually break something
# downstream, which is all this module can legitimately assert here.
variable "environment" {
  description = "Trailing component of the Name tag on the VPC, every subnet, every route table, every gateway, every endpoint and every security group, so one environment's network is distinguishable from another's in the same account. Required -- there is no default. Lowercase letters, digits and hyphens only, no leading or trailing hyphen, at most 16 characters."
  type        = string

  validation {
    # Assumptions: the bound is 16 rather than the 32 allowed for
    # name_prefix because this component is appended AFTER the prefix and
    # the tier, so it is the last contributor to a composed name and the
    # one with the least room left within the same downstream limits. The
    # character class matches name_prefix for the reason it holds there --
    # the composed string reaches resources the service restricts to that
    # class -- and anchoring both ends on an alphanumeric rejects the
    # hyphen that would double up against the separator preceding it.
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

# Assumptions: a /16 divided by the default four additional bits yields
# sixteen /20 blocks, of which nine are consumed at three availability
# zones -- one public, one private-application and one isolated-data
# subnet per zone -- leaving seven /20 blocks unallocated. Trade-offs:
# that headroom is claimed address space no resource occupies, accepted
# because a fourth tier or a peered range added later can be carved from
# the same block without renumbering the nine subnets that already
# exist, and renumbering a subnet means replacing it.
variable "vpc_cidr" {
  description = "IPv4 address space the VPC occupies, and the only addressing value this module takes. main.tf subdivides it into `3 * az_count` equally sized subnets -- one public, one private-application and one isolated-data subnet per availability zone -- each `subnet_newbits` bits longer than this prefix, so the block must be large enough to accommodate them all. Changing it after apply forces replacement of the VPC and of every subnet in it."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    # Assumptions: cidrnetmask is used rather than cidrhost because cidrhost
    #       accepts IPv6, while every subnet and VPC resource in this module uses
    #       the IPv4 `cidr_block` argument. cidrnetmask rejects IPv6 and malformed
    #       prefixes, the explicit /16-/28 bound matches the range the VPC API
    #       accepts, and `try` converts a malformed split or prefix into this
    #       input-specific plan-time message.
    condition = try(
      cidrnetmask(var.vpc_cidr) != "" &&
      tonumber(split("/", var.vpc_cidr)[1]) >= 16 &&
      tonumber(split("/", var.vpc_cidr)[1]) <= 28,
      false
    )
    error_message = "vpc_cidr must be a well-formed IPv4 CIDR block with a prefix from /16 through /28, for example 10.0.0.0/16. IPv6 blocks are not accepted by this IPv4-only network module."
  }
}

# Assumptions: three availability zones in one Region is the mandated
# topology for this stack, recorded as decision D8 in
# docs/adr/ADR-008-security-and-identity.md; multi-Region and
# disaster-recovery topology are out of scope. It is an input so the
# arithmetic in main.tf reads the count from one place instead of
# repeating a literal 3 at every netnum, subnet and gateway.
#
# Assumptions: this is NOT a per-environment lever, which is the reading a
# caller is most likely to arrive with. The two environment roots differ
# only in sizing and retention and never in topology, and the reason
# ADR-008 records is specific: a development environment with a different
# network shape would not validate the production one, so a defect in the
# three-zone routing would first appear in production. BOTH
# infra/envs/dev and infra/envs/prod pass 3. An operator reaching for
# this value to reduce development cost is reaching for the wrong one --
# the levers that exist are database capacity, task count, retention and
# price class.
#
# Alternatives Considered: accepting two zones so the module could be
# exercised in a smaller Region. Rejected because the frozen topology is
# three zones in every environment; accepting two would let a valid plan
# omit one third of the subnets and NAT gateways, so development would no
# longer rehearse production. A Region without three usable zones cannot
# host this topology and must fail explicitly rather than receive a
# different one.
variable "az_count" {
  description = "Number of availability zones the network spans, and therefore the number of subnets created in each of the three tiers and the number of NAT gateways. The only supported value is 3, the topology shared by dev and prod."
  type        = number
  default     = 3

  validation {
    # Assumptions: equality to the integer 3 rejects fractions and every
    # alternate topology in one expression, so a caller cannot interpret
    # the variable as a sizing lever merely because it is exposed.
    condition     = var.az_count == 3
    error_message = "az_count must be exactly 3. CardDemo's dev and prod environments share one three-availability-zone topology; two or more than three zones are unsupported topology changes."
  }
}

# Alternatives Considered: three `list(string)` inputs, one per tier,
# each holding an explicit CIDR per zone. Rejected. At three zones that
# is nine hand-maintained CIDRs which must be kept mutually
# non-overlapping, kept in the same order as the zone list, kept
# consistent with az_count, and kept identical in intent between two
# roots -- and nothing checks any of those four properties, so the first
# inconsistency surfaces as a subnet overlapping another or a tier
# missing a zone. One derivation from one block cannot drift out of step
# with itself.
#       Trade-offs: the caller loses control over exactly where each tier lands
#       inside the block, accepted in exchange for an arithmetic that cannot
#       become internally inconsistent. The addressing the derivation produces
#       is documented in this module's README.
variable "subnet_newbits" {
  description = "Number of bits cidrsubnet adds to the vpc_cidr prefix when carving each subnet, which fixes every subnet's size: at the default /16 and 4 additional bits each subnet is a /20. It must admit at least `3 * az_count` distinct subnets, because the three tiers are taken from consecutive netnum ranges of the one block rather than from separate per-tier address lists."
  type        = number
  default     = 4

  validation {
    # Assumptions: this is one combined addressing contract rather than
    # three unrelated bounds. Adding n bits must yield at least
    # `3 * az_count` netnums, the highest netnum actually used
    # (`3 * az_count - 1`) must be accepted by cidrsubnet, and the resulting
    # IPv4 prefix must be no narrower than /28. A /28 contains sixteen
    # addresses, eleven usable after the five VPC reservations; anything
    # narrower is rejected by the subnet API and cannot host a tier.
    # `try` converts malformed cross-variable arithmetic into the explicit
    # message below rather than exposing a cidrsubnet evaluation error.
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
# These four values -- the two halves of the endpoint set and the two shared ports --
# are contracts
# shared with sibling modules rather than independent environment-tuning knobs. main.tf consumes them in endpoint and
# security-group resources, while outputs.tf republishes the two ports so the
# calling root can pass the same values into ecs-service and
# aurora-postgresql.
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: an earlier update removed this input and moved
#       the endpoint names toward literals in main.tf. That shape can provision
#       the endpoints, but it removes the contract a root and generated module
#       documentation can inspect and makes an endpoint-set change look like an
#       implementation edit rather than a topology change. Restoring the set
#       keeps the architecture's exact private-service paths visible and
#       testable at the module boundary.
#
# WHY : Assumptions: the validation requires equality with the EIGHT services
#       specification section 0.4.1.9 names -- not merely a syntactically valid
#       list. A missing entry drops that service's traffic at the application
#       group, and an extra entry adds another billed endpoint and another network
#       path. Neither is an environment variation: dev and prod must have the same
#       topology. S3 is absent because it uses the gateway endpoint created
#       unconditionally in main.tf rather than an interface endpoint.
# WHY : Refactoring Rationale: this input's default and validation carried TEN names
#       -- the eight above plus cognito-idp and xray -- and that is the defect this
#       split repairs. Both additions are approved and both are kept; what could not
#       be done was CHECK them, because one input holding ten names leaves the eight
#       of section 0.4.1.9 asserted nowhere, so the deviation existed only in prose
#       and an eleventh name would have been an ordinary-looking edit to a list that
#       already disagreed with the specification. The eight now stand alone here, as
#       an exact set this module asserts in its own right, and the approved additions
#       are declared by approved_additional_interface_endpoint_services below, where
#       each carries its functional justification and its recurring cost. main.tf
#       creates endpoints over the UNION of the two, so the provisioned set is
#       unchanged at ten and every endpoint keeps identical treatment; the structure
#       follows infra/modules/ecr, which separates the ten deployable images the plan
#       fixes from the one third-party mirror cached beside them for the same reason.
#       Alternatives Considered: keeping one input and adding a second `validation`
#       that asserted the eight were a SUBSET of it. Rejected because a subset test
#       says nothing about what else the set holds, so the eleventh endpoint it is
#       meant to stop still passes -- and the count the specification fixes would
#       still not be readable from any single declaration.
variable "interface_endpoint_services" {
  description = "Exact set of the EIGHT short AWS service names specification section 0.4.1.9 enumerates, each given a private interface endpoint in every environment: ecr.api and ecr.dkr for image pulls, logs for delivery, secretsmanager for credentials, kms for envelope operations, sqs for messaging, states for workflow calls and ssm for configuration. main.tf expands each short name into its Region-qualified service name and creates one endpoint per entry in the UNION of this set and var.approved_additional_interface_endpoint_services; S3 is excluded from both because it uses the separate gateway endpoint."
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
  ]

  validation {
    # Assumptions: set equality checks both halves of the contract in one
    # expression -- no required service may be missing and no
    # unreviewed service may be added. A character-shape regex alone
    # would accept both errors and the plan would remain green.
    # Assumptions: an approved addition does not belong here. It is declared by
    #     approved_additional_interface_endpoint_services, so this set stays
    #     equal to the specification's eight and remains checkable against it.
    condition = var.interface_endpoint_services == toset([
      "ecr.api",
      "ecr.dkr",
      "logs",
      "secretsmanager",
      "kms",
      "sqs",
      "states",
      "ssm",
    ])
    error_message = "interface_endpoint_services must contain exactly the eight services of specification section 0.4.1.9 -- ecr.api, ecr.dkr, logs, secretsmanager, kms, sqs, states and ssm -- and the endpoint set is identical in every environment. An endpoint beyond those eight is an approved addition and belongs in approved_additional_interface_endpoint_services, where its justification and its recurring cost are recorded."
  }
}

# WHY : Assumptions: this input exists because the endpoint inventory carries TWO
#       kinds of claim and the frozen plan fixes the count of only one of them.
#       var.interface_endpoint_services above is the eight services specification
#       sections 0.4.1.6 and 0.4.1.9 enumerate. What this variable holds is the
#       APPROVED DEVIATION from that number: two further endpoints this deployment
#       requires to run at all, ratified in docs/adr/ADR-008-security-and-identity.md
#       with the alternative that was refused and the recurring cost each one carries.
#       Declaring them separately is what makes both counts assertable -- the
#       specification's eight, and the two beyond it -- so an eleventh endpoint is a
#       failed validation rather than one more name in a list that already differed
#       from the specification.
# WHY : Assumptions: cognito-idp is required by the identity path and is exercised on
#       every request-serving start-up and every sign-on. Each service resolves the
#       user pool's issuer document and its JSON web key set through this hostname,
#       and auth-service performs the user-pool operations behind sign-on, the
#       new-password challenge, refresh, revoke and sign-out. With the application
#       tier's egress enumerated -- main.tf creates exactly four egress rules, to the
#       load balancer, to Aurora, to the endpoint ENIs and to the S3 gateway prefix
#       list -- there is no public path for those calls, so without this endpoint they
#       are dropped at the security group and sign-on fails for every user. That is
#       the acceptance criterion AAP section 0.9.1 states as sign-on working end to
#       end, so this endpoint is what makes the enumerated egress and a working
#       sign-on compatible.
# WHY : Assumptions: xray is required by the trace path and has a consumer today.
#       infra/modules/ecs-service attaches an AWS Distro for OpenTelemetry collector
#       sidecar to every task with essential = true, its traces pipeline exports
#       through the awsxray exporter, and each application container is pointed at the
#       sidecar's loopback OTLP receiver -- so spans are created, collected and
#       exported on the path this endpoint carries. The collector runs inside the same
#       egress-enumerated subnets as the workload, so with this endpoint absent every
#       export attempt is dropped at the group and the centralised tracing AAP
#       sections 0.2.1.4 and 0.9.3 require has no destination.
#       Refactoring Rationale: an earlier revision of this reasoning recorded xray as
#       provisioned against NO consumer, on the premise that the collector sidecar was
#       withdrawn from infra/modules/ecs-service. The sidecar is present and essential,
#       so that premise has lapsed; it is corrected rather than carried forward,
#       because a paid-for endpoint recorded as unexercised is exactly the entry a
#       later reviewer would remove -- and removing it would silently disable trace
#       export for every workload.
# WHY : Refactoring Rationale: cognito-idp was WITHDRAWN from the endpoint set for a
#       period, on a finding that was correct about mechanism and wrong about remedy,
#       and the finding is preserved here because the arrangement that answers it is
#       not obvious. main.tf attached ONE shared endpoint policy to every endpoint, and
#       that policy admits only principals in this account -- while the identity calls
#       that matter here are UNAUTHENTICATED by construction: OIDC discovery, the key
#       set, and the sign-on, challenge-response, refresh, revoke and sign-out
#       operations a user-pool client performs before or without holding any IAM
#       credential. Those requests carry no principal for that condition to satisfy,
#       so the shared policy denied them and the symptom would have been every sign-on
#       refused at the endpoint.
# WHY : Assumptions: the remedy is a per-endpoint policy rather than removal, and
#       removal is what makes the difference. The withdrawal's stated fallback was the
#       application-tier egress rule to the provider, but that rule admitted 0.0.0.0/0
#       and is itself withdrawn below -- and .github/workflows/infra-ci.yml now fails
#       any egress rule naming an open destination. So with this name absent AND that
#       rule gone there is no path to the provider at all, and sign-on fails closed
#       rather than privately: the two withdrawals were individually defensible and
#       jointly fatal. main.tf therefore keeps the account-scoped document for the
#       other nine endpoints and attaches an additional statement on this one
#       admitting exactly the five unauthenticated operations by name, with no
#       principal condition. That satisfies both halves -- the private path serves
#       unauthenticated sign-on, and no rule names a public destination -- and it is
#       why infra-ci.yml asserts this name is present in BOTH the default and the
#       validation below.
# WHY : Alternatives Considered: holding the specification's eight literally and
#       restoring the open egress rule for these two dependencies. REFUSED, and this
#       is the alternative the approval record turns on. It reintroduces the finding
#       that rule's withdrawal fixed -- every application task able to open 443 to any
#       public address, in a workload holding cardholder data -- and it contradicts
#       the same section 0.4.1.9 that fixes the count, whose security-group contract
#       permits only load-balancer-to-application on 8080, application-to-Aurora on
#       5432 and application-to-endpoint on 443. Widening an endpoint ENUMERATION
#       leaves that contract intact; restoring public egress breaks it. Of the two
#       readings of one section, the deviation taken is the one that keeps the
#       security property.
#       Alternatives Considered: dropping the two dependencies instead. Refused
#       because each is load-bearing rather than optional: without the identity
#       endpoint no user can sign on, and without the trace endpoint the cross-cutting
#       tracing deliverable has no destination.
# WHY : Trade-offs: the recurring cost of the approval is stated rather than implied.
#       An interface endpoint is billed per endpoint per availability zone per hour
#       plus per GB processed, and this network spans three zones, so these two
#       additions add 2 x 3 = SIX endpoint-zone-hours per hour on top of the
#       specification's 8 x 3 = 24 -- thirty in total. At the us-east-1 list rate
#       recorded in ADR-008 of USD 0.01 per endpoint-zone-hour that is USD 0.06 per
#       hour, about USD 44 per month, and it is accepted as the price of removing an
#       open egress path rather than as an incidental addition. The data-processing
#       term is largely displaced rather than added, because the same traffic
#       otherwise crosses the NAT gateways and accrues their per-GB charge.
variable "approved_additional_interface_endpoint_services" {
  description = "Exact set of the TWO interface-endpoint services approved BEYOND the eight of specification section 0.4.1.9: cognito-idp, which carries the issuer, key-set and user-pool calls every service makes at start-up and on every sign-on, and xray, which carries the trace export of the collector sidecar infra/modules/ecs-service attaches to every task. Both are functionally required because the application tier has no public egress at all, so a dependency with no endpoint here is dropped at the security group rather than routed out. main.tf creates endpoints over the union of this set and var.interface_endpoint_services, so each addition costs three endpoint-zone-hours -- one per availability zone -- and the two together add six to the specification's twenty-four. The approval, the refused alternative of eight endpoints plus internet egress, and the cost arithmetic are recorded in docs/adr/ADR-008-security-and-identity.md."
  type        = set(string)
  default = [
    "cognito-idp",
    "xray",
  ]

  validation {
    # Assumptions: set equality, not a bound on size. A count bound would admit
    #     any two names, so a swapped entry -- one approved endpoint replaced by
    #     an unreviewed one -- would plan cleanly while the approval record
    #     described something else. Equality is what ties the provisioned pair to
    #     the pair ADR-008 ratifies.
    # Assumptions: this set may not be shortened either, and that direction
    #     matters as much as the other. Removing cognito-idp fails every sign-on
    #     and removing xray silently discards every span, in both cases by
    #     dropping the call at the application security group.
    condition = var.approved_additional_interface_endpoint_services == toset([
      "cognito-idp",
      "xray",
    ])
    error_message = "approved_additional_interface_endpoint_services must contain exactly cognito-idp and xray, the two approved additions beyond the eight of specification section 0.4.1.9. Adding a third endpoint, removing one or substituting another is a change to fixed topology and to the recurring cost this module is approved for: it must be ratified in docs/adr/ADR-008-security-and-identity.md and gated in .github/workflows/infra-ci.yml, not made here."
  }
}

# =============================================================================
# The identity-provider egress input is WITHDRAWN, and the withdrawal is the
# security control rather than a simplification.
# -----------------------------------------------------------------------------
# WHY : Refactoring Rationale: an `identity_provider_egress_cidrs` input stood here
#       with a default of `["0.0.0.0/0"]`, and main.tf created one application-tier
#       egress rule per entry. Both are withdrawn. Its premise was that the identity
#       provider "has no private interface endpoint in the specified eight-service
#       set"; that premise lapsed when `cognito-idp` and `xray` joined the exact
#       endpoint set declared above, and what survived was an input whose shipped
#       value let every application task open TCP 443 to ANY public destination in a
#       workload that holds cardholder data. Both environment roots inherited that
#       default, so the narrowing this input advertised was available in principle
#       and applied nowhere.
# WHY : Assumptions: no reachability is lost, and the reason is the endpoint set
#       rather than an argument about likelihood. The provider hosts OIDC discovery
#       and the JSON web key set on the same API host as the user pools API, and that
#       host is what the `cognito-idp` interface endpoint intercepts once
#       `private_dns_enabled` is set -- as it is on every endpoint main.tf creates --
#       so issuer resolution, key-set fetching and sign-on authentication all stay
#       inside the VPC and reach the endpoint group under the existing
#       application-to-endpoint rule.
# WHY : Alternatives Considered: keeping the input and validating that it cannot hold
#       `0.0.0.0/0`, forcing an operator to name exact addresses. Rejected: the
#       provider publishes no managed prefix list for those hosts and its addresses
#       change, so the only honest values are the regional service ranges -- on the
#       order of two hundred prefixes, which exceeds the default per-security-group
#       rule quota and would fail at apply. An input whose only admissible values do
#       not fit reads as a supported narrowing and is not one.
#       Alternatives Considered: an inspection proxy or a domain-filtering firewall in
#       front of that egress. Rejected for this scope: it adds an always-on component
#       and its own failure mode to protect a path the endpoint set removes outright,
#       and AAP section 0.4.1.6 fixes the module inventory this package provisions.
# WHY : Trade-offs: a dependency that is not in `interface_endpoint_services` is now
#       refused at the security group instead of routed through NAT. That is the
#       intended outcome -- adding a dependency becomes a reviewed change to the exact
#       endpoint set, where its per-zone cost is also visible, rather than an
#       invisible reliance on a public path.
# =============================================================================

# Assumptions: 8080 appears in the network rule, the ecs-service target group and
#       every service listener, so it is declared once here and republished by
#       outputs.tf for the calling root to pass into ecs-service. One value shared
#       that way cannot leave the security group admitting a port the listener does
#       not bind.
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
    #       Refactoring Rationale: a second validation used to refuse 443
    #       specifically, on the ground that the interface-endpoint ENIs shared
    #       the application security group and so the task-to-endpoint flow was a
    #       SELF-referencing rule at 443 that a 443 container port would silently
    #       turn into a task-to-task allowance. That premise is TRUE again -- the
    #       ENIs carry the application group once more, because the frozen plan
    #       specifies three security groups -- so it is worth being exact about why
    #       no separate check is needed: the condition below admits only 1024 and
    #       above, and 443 is below 1024, so this variable already cannot take the
    #       one value that would make the self-referencing endpoint rule reach a
    #       listener. That bound is what the module's claim of an unreachable
    #       task-to-task permission rests on, so it must not be relaxed below 1024
    #       without restoring a dedicated endpoint group. The separate check stays
    #       withdrawn rather than re-added, because a constraint kept with a
    #       manufactured reason is
    #       worse than no constraint - the next reader cannot tell which of the
    #       two is load-bearing.
    condition     = var.app_container_port == floor(var.app_container_port) && var.app_container_port >= 1024 && var.app_container_port <= 65535
    error_message = "app_container_port must be a whole number from 1024 to 65535; every service container runs as a non-root user."
  }

}

# Assumptions: 5432 appears in both the network boundary and the
#       aurora-postgresql module, so the root passes `module.network.database_port`
#       into Aurora rather than repeating the number. A custom database port
#       therefore cannot produce a cluster that applies cleanly and is then
#       unreachable through a security-group rule naming a different port.
variable "database_port" {
  description = "TCP port admitted from the application security group to the isolated-data security group and republished for the calling root to pass into aurora-postgresql. The default 5432 matches PostgreSQL; the accepted range is the range Aurora PostgreSQL supports."
  type        = number
  nullable    = false
  default     = 5432

  validation {
    # Assumptions: Aurora PostgreSQL accepts ports 1150 through 65535.
    # Reusing that service bound here is what keeps the security rule and
    # the database module capable of accepting exactly the same values,
    # rather than allowing a port in one module that the other rejects.
    condition     = var.database_port == floor(var.database_port) && var.database_port >= 1150 && var.database_port <= 65535
    error_message = "database_port must be a whole number from 1150 to 65535, the range Aurora PostgreSQL accepts."
  }
}

# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

# Assumptions: the calling root's `provider "aws"` block sets
# `default_tags`, which the provider applies to every resource it
# creates, and this module inherits that provider rather than
# configuring one of its own. Tags supplied here are therefore MERGED
# with the root's rather than replacing them, so this module is not the
# sole tagging authority for the resources it creates -- a reader looking
# for the cost-allocation or ownership tags will find them on the root,
# not here. Without that note it is not possible to tell which of the two
# is authoritative.
# The only tag this module adds on its own initiative is a per-resource
# Name, composed from name_prefix, the tier and environment. main.tf
# merges this map UNDERNEATH that Name, so a caller cannot accidentally
# overwrite the one tag distinguishing two resources of the same kind.
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

# Assumptions: log retention days is among the enumerated values the two
# environment roots are permitted to set differently, because changing it
# alters how much history is kept and what that costs, and alters nothing
# about the shape of the network. Every addressing input above
# deliberately is NOT such a lever, which is what makes this one worth
# pointing out rather than leaving to be inferred: dev and prod are
# expected to differ here, and to agree everywhere else in this file.
variable "flow_log_retention_days" {
  description = "Days the CloudWatch Logs group receiving this VPC's flow logs retains events before they age off, or 0 to retain them indefinitely. This is the one value in this module the dev and prod roots are expected to set differently, and it is the direct analogue of how long a mainframe job log was kept before it aged off the spool."
  type        = number
  default     = 30

  validation {
    # Assumptions: the set is closed because the service accepts these
    # values and nothing else -- 45 is not a retention period, it is
    # an InvalidParameterException raised during apply, after the VPC
    # and every subnet already exist. Listing the accepted values here
    # converts that apply-time API rejection into a plan-time message,
    # so the run never starts.
    # 0 is included because the service reads it as never expire, which
    # is a legitimate choice for an audit trail a compliance regime
    # requires be kept; it is not the default, so indefinite retention
    # is something a root asks for explicitly rather than inherits.
    condition = contains([
      0, 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731,
      1096, 1827, 2192, 2557, 2922, 3288, 3653,
    ], var.flow_log_retention_days)
    error_message = "flow_log_retention_days must be 0, meaning never expire, or one of the retention periods CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288 or 3653."
  }
}

# Assumptions: the key is REQUIRED, and the requirement is enforced by the
# validation on the block below rather than by removing the null default.
# Requiring it outright would make this module -- which depends on no other
# module -- unplannable on its own, because the ARN comes from the sibling kms
# module. The gate keeps standalone planning available behind an explicit
# boolean while making the encrypted path the only one a caller reaches by
# default.
#       Trade-offs: with the opt-out taken, the log group falls back to CloudWatch
#       Logs service-default encryption, which is weaker than a customer-managed key
#       because the key is not one this account controls, rotates or revokes. That is
#       why the opt-out is named, defaulted false, and documented as unsupported for
#       either environment root.
#
# Assumptions: an ARN is named here as a CONTRACT, in a module that
#       hard-codes no identifier of any kind. This is configuration flowing IN
#       as a caller-supplied value, which is categorically different from an
#       identifier written into the tree. The default is null, and no literal
#       ARN, account identifier or key id appears anywhere in this file.
#       `nullable` is stated explicitly rather than left to its default so that
#       a root passing null EXPLICITLY gets null -- the service-default
#       encryption -- instead of silently falling back to this default.
#
# Alternatives Considered: for there being no shape validation here when
# the other six inputs are validated, a regex asserting the
# arn:<partition>:kms:<region>:<account-id>:key/<key-id> form, as
# infra/bootstrap/variables.tf applies to its own key input. Rejected
# here. The value both roots pass is an output of the kms module rather
# than an operator literal, so its shape is guaranteed by construction,
# and a regex tight enough to be worth writing would reject the
# multi-Region key and alias forms the same service accepts. A wrong key
# fails at apply with the service's own diagnostic, which names the key;
# a wrong-shaped one such a regex admitted would fail identically.
variable "flow_log_kms_key_arn" {
  description = "ARN of a customer-managed KMS key with which to encrypt the CloudWatch Logs group receiving this VPC's flow logs. Required unless allow_service_managed_flow_log_encryption is explicitly set true, which is the opt-out reserved for planning this module in isolation without the kms module. Both environment roots pass the key the kms module produces."
  type        = string
  default     = null
  nullable    = true

  # Assumptions: the condition below is what makes the encryption of a network
  #       audit trail a property of this module's contract rather than of what a
  #       caller happens to pass. Without it, a caller that omitted the argument or
  #       passed a value resolving to null through a conditional would get a
  #       flow-log group on service-default encryption, a clean plan and no
  #       diagnostic anywhere -- failing OPEN on a security control. The
  #       composability the null default buys survives as the explicit, named
  #       opt-out below rather than as a silent default.
  # Assumptions: the opt-out is a separate boolean rather than a magic value
  # in this string, because the two carry different information and a reviewer
  # needs to see both. This variable answers "which key"; the boolean answers
  # "is unencrypted acceptable here". A sentinel string such as "none" would
  # fold the second question into the first, so a diff that turned encryption
  # off would look like a diff that changed a key.
  # Alternatives Considered: removing the null default and making the ARN
  # unconditionally required, which is the simplest fail-closed form.
  # Rejected because this module deliberately depends on no other module, and
  # an unconditional requirement makes `terraform plan` on the module alone
  # impossible -- the isolation in which its addressing arithmetic and its
  # endpoint validation are exercised. The gate keeps that capability while
  # making its use deliberate and visible.
  # Assumptions: cross-variable references in a validation condition are
  # available because versions.tf pins required_version to >= 1.15.0 and
  # Terraform has supported them since 1.9. A lifecycle precondition on the
  # log-group resource would express the same rule; a variable validation is
  # chosen because it reports against the INPUT the caller got wrong rather
  # than against a resource the caller did not write.
  validation {
    condition     = var.flow_log_kms_key_arn != null || var.allow_service_managed_flow_log_encryption
    error_message = "flow_log_kms_key_arn is required: the VPC flow-log group carries a network audit trail and must be encrypted with a customer-managed key. Pass the kms module's flow-log key ARN. To plan this module in isolation without the kms module, set allow_service_managed_flow_log_encryption = true explicitly, which is not a supported configuration for either environment root."
  }
}

# Assumptions: this input exists ONLY so that the fail-closed requirement on
# flow_log_kms_key_arn has an explicit escape hatch, and it is declared as its
# own variable so that using the hatch is a visible line in a caller's module
# block rather than an omission. Its default is false, so the safe behaviour
# is what a caller gets without deciding anything -- which is the property a
# silent null default did not have.
# Assumptions: it deliberately does not appear in either environment root.
# Both pass a real key, so both leave this at false, and a future edit that
# introduced it into a root would be a one-line diff a reviewer cannot miss.
# That visibility is the whole mechanism: the control is not stronger than
# before in what it permits, it is stronger in what it makes someone say out
# loud.
# Trade-offs: one more input on a module whose input surface is deliberately
# narrow. Accepted because the alternative shapes are worse -- an
# unconditional requirement removes standalone planning, and no requirement at
# all is the defect being fixed.
variable "allow_service_managed_flow_log_encryption" {
  description = "Whether this module may create the VPC flow-log group on CloudWatch Logs service-default encryption instead of a customer-managed key. False, the default, makes flow_log_kms_key_arn required. True is reserved for planning this module in isolation without the kms module and is not a supported setting for the dev or prod roots."
  type        = bool
  default     = false
  nullable    = false
}

# =============================================================================
# Deliberately absent inputs
# -----------------------------------------------------------------------------
# Each value below is a common input on a VPC module, and none is absent by
# oversight. Adding any of them would also fail terraform_unused_declarations
# until main.tf were changed to read it, which is the point at which the
# reasoning here would have to be overturned rather than overlooked. This
# register names only inputs the module genuinely does not take: an entry naming
# something declared above sends a reader to main.tf for a value a validated
# variable already governs.
#
#   - No `single_nat_gateway`. Alternatives Considered: a boolean collapsing the
#     per-zone NAT gateways to one, which cuts the largest fixed charge in this
#     tier. Rejected because the number of gateways is topology rather than size,
#     and the two roots differ only in sizing and retention -- a development
#     environment with a different network shape would not validate the
#     production one. With one gateway, egress from two of the three zones
#     becomes a cross-zone data path and losing that gateway's zone takes egress
#     from all three. Trade-offs: three hourly gateway charges are accepted;
#     cost is reduced through database capacity, task count, retention and price
#     class rather than by thinning the network.
#
#   - No per-endpoint enable flags, such as `enable_sqs_endpoint`. Alternatives
#     Considered: one boolean per endpoint, so a root could create only the
#     endpoints it uses. Rejected: the reason ADR-008 gives for the endpoints --
#     keeping traffic to AWS APIs off the public path -- applies uniformly to all
#     ten interface endpoints and the S3 gateway endpoint. A per-endpoint switch
#     would let one environment silently resolve a service to a public address
#     while its plan stayed clean and its security groups stayed unchanged.
#
#   - No per-endpoint enable flags, such as `enable_sqs_endpoint` or
#     `enable_states_endpoint`. Alternatives Considered: one boolean per
#     endpoint, so a root could create only the endpoints it uses. Rejected:
#     all ten interface endpoints and the S3 gateway endpoint are this
#     module's responsibility, and the reason ADR-008 gives for them -- keeping
#     traffic to AWS APIs off the public path -- applies uniformly to all eleven
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
#     between logging accepted flows, rejected flows or all of them; ALL is fixed
#     in main.tf, where it carries its own why-comment. Rejected because a
#     rejected-only log shows what was refused and not what succeeded, an
#     accepted-only log the reverse, and neither is an audit trail of the
#     traffic.
#
#   - No `region`. Assumptions: the Region comes from the calling root's
#     `provider "aws"` block, which this module inherits rather than configuring,
#     and main.tf reads it back through a `data "aws_region"` lookup to compose
#     the Region-qualified endpoint service names. A `region` input here could
#     disagree with the provider's, and the disagreement would not itself be an
#     error: resources would be created in the provider's Region while endpoint
#     service names were composed for the input's, which fails at apply with a
#     diagnostic naming neither input.
#
# =============================================================================

# -----------------------------------------------------------------------------
# The maximum permissions the role this module creates may ever hold
# -----------------------------------------------------------------------------
# WHY : Assumptions: a permissions boundary is the only control that bounds what
#       this module's composed inline policies can add up to, because it is
#       evaluated IN ADDITION TO every identity policy -- a statement the boundary
#       does not permit is denied even where an inline policy allows it. Attaching
#       it here rather than trusting the caller means a root that widens an
#       ARN list passed into this module cannot widen past the account's ceiling.
# WHY : ⚠️ Refactoring Rationale: this input did not exist, and the role
#       created here carried NO boundary at all, while
#       infra/envs/*/variables.tf described its `permissions_boundary_arn` as
#       applying to "every role this deployment creates". That description was the
#       promise; this input is what makes it true. infra/modules/ecs-service already
#       bounded its two roles this way, so the shape here is deliberately identical
#       to that one rather than a second convention.
# WHY : Assumptions: the boundary is owned by the ACCOUNT and is supplied, never
#       created here. A boundary a deployment can rewrite bounds nothing.
#       Trade-offs: the input is required and has no default, so a caller must own a
#       boundary policy before it can use this module. Accepted for the same reason
#       ecs-service accepts it: making it optional leaves the control switched off in
#       exactly the environments least likely to notice.
variable "permissions_boundary_arn" {
  description = "Same-account customer-managed IAM policy ARN used as the permissions boundary on the VPC flow-log delivery role this module creates. Required so no capability this module composes can exceed the account's deployment boundary. Supplied by the caller; never created here."
  type        = string
  nullable    = false

  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:policy/[A-Za-z0-9+=,.@_/-]+$", var.permissions_boundary_arn))
    error_message = "permissions_boundary_arn must be an anchored customer-managed IAM policy ARN in a twelve-digit AWS account."
  }
}
