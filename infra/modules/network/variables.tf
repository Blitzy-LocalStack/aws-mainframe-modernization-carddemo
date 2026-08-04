# =============================================================================
# infra/modules/network/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `network` module -- the three-zone VPC
#   that every other module in this tree is placed into, with its
#   public, private-application and isolated-data subnet tiers, its NAT egress,
#   its eight interface endpoints, its S3 gateway endpoint and its three
#   security groups. Every value a calling root may configure is declared here
#   and nothing else is: anything absent from the list below is a property of
#   the network fixed in main.tf, not a per-environment choice.
#
#   Every variable declared here is consumed by main.tf or outputs.tf. That is
#   not a stylistic claim -- terraform_unused_declarations is enabled in
#   infra/.tflint.hcl, so a declaration nothing consumes is a lint failure. The
#   consequence for anyone extending this file: an input reserved for later use
#   cannot be added ahead of the code that reads it, and a derived value
#   belongs in main.tf's `locals` rather than here.
#
#   Measured state at this checkpoint, kept distinct from the contract above
#   because conflating the two is how a false claim spreads. main.tf and
#   outputs.tf are authored at a later index of the same plan and are not yet
#   on disk, so a lint run over this directory currently reports seven of the
#   eight as unused -- az_count being the exception, since subnet_newbits
#   validates against it. It also reports the two absent files and the
#   consequently unused aws provider requirement. All ten findings are the
#   documented consequence of an incomplete tree rather than a defect here,
#   they are enumerated in infra/.tflint.hcl and in
#   docs/CODE_DOCUMENTATION_STANDARD.md, and they clear as those two files
#   land. What passes today is everything this file can be held to on its own:
#   HCL parse, `terraform validate`, `terraform fmt -check`, a type and a
#   description on all eight, and the six validations below.
#
#   The distinction between a configurable value and a fixed property carries
#   more weight in this module than in any other, because the two environment
#   roots are required to be identical in TOPOLOGY and to differ only in sizing
#   and retention. An input is therefore admissible here only if varying it
#   cannot change the shape of the network. Exactly one input below is a
#   genuine per-environment lever, flow_log_retention_days; the five candidates
#   that failed the test are named at the foot of this file, under deliberately
#   absent inputs, so a reader who expects one learns it was considered.
#
# Parameters -- eight, of which one is required:
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
#   Tagging
#     tags                     map(string)  Merged onto each taggable resource.
#
#   Flow-log configuration
#     flow_log_retention_days  number       Days the flow-log group retains
#                                           events.
#     flow_log_kms_key_arn     string       Customer-managed key encrypting the
#                                           flow-log group; null selects the
#                                           service default.
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
#   per-tier subnet identifier lists and the three security group identifiers
#   this module publishes to its caller are declared in
#   infra/modules/network/outputs.tf.
#
# Errors / Exceptions -- what fails, and when:
#   - `environment` has no default, so omitting it stops the calling root with
#     a missing-required-argument error BEFORE a plan is computed, rather than
#     tagging one environment's network with another environment's name.
#   - A malformed `vpc_cidr`, a `name_prefix` or `environment` outside the
#     permitted character set or length, an `az_count` or `subnet_newbits`
#     outside its bounds, or a `flow_log_retention_days` outside the set
#     CloudWatch Logs accepts each fails its `validation` block AT PLAN TIME.
#     Failing there is the entire reason those validations are declared: the
#     same mistakes otherwise surface as service errors partway through an
#     apply, once subnets and gateways exist and the run has to be unwound.
#   - No input here can fail at apply time for a reason this file could have
#     caught, with one stated exception -- `flow_log_kms_key_arn` is not
#     shape-checked, for the reason recorded on that block.
#
# WHY (non-obvious design decisions):
#   - Assumption: no input accepts a credential, an account identifier or a
#     Region, and no `default` holds one. `flow_log_kms_key_arn` names an ARN
#     as its CONTRACT and defaults to null, which is what keeps that true.
#     Identifiers travel INWARD only as caller-supplied values and OUTWARD as
#     outputs, so no literal ARN, account identifier, key id, Region, bucket or
#     table name appears anywhere in this file.
#   - Assumption: this module configures no aws provider, so the Region and the
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

# WHAT: a prefix carrying a default, rather than a literal compiled into
#       main.tf.
# WHY : Trade-off: the default spares each root from restating the project name
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
    # WHY : Assumption: this prefix is concatenated into names across roughly
    #       thirty resources, several of which AWS bounds at 32 characters and
    #       restricts to this same character class -- so the bound is the
    #       tightest of the downstream limits rather than a preference, and the
    #       pattern rejects the space, the underscore and the uppercase letter
    #       that would otherwise reach a name the service refuses. Anchoring
    #       both ends on an alphanumeric additionally rejects a leading or
    #       trailing hyphen, which would double up against the separator placed
    #       either side of it. Trade-off: a legitimately unusual prefix has to
    #       be brought inside these bounds first; catching it here costs one
    #       plan, catching it in the service costs a partial apply.
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit."
  }
}

# WHAT: the one input on this surface with no `default`.
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
    # WHY : Assumption: the bound is 16 rather than the 32 allowed for
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

# WHAT: a default sized so the derivation has room, not merely a valid block.
# WHY : Assumption: a /16 divided by the default four additional bits yields
#       sixteen /20 blocks, of which nine are consumed at three availability
#       zones -- one public, one private-application and one isolated-data
#       subnet per zone -- leaving seven /20 blocks unallocated. Trade-off:
#       that headroom is claimed address space no resource occupies, accepted
#       because a fourth tier or a peered range added later can be carved from
#       the same block without renumbering the nine subnets that already
#       exist, and renumbering a subnet means replacing it.
variable "vpc_cidr" {
  description = "IPv4 address space the VPC occupies, and the only addressing value this module takes. main.tf subdivides it into `3 * az_count` equally sized subnets -- one public, one private-application and one isolated-data subnet per availability zone -- each `subnet_newbits` bits longer than this prefix, so the block must be large enough to accommodate them all. Changing it after apply forces replacement of the VPC and of every subnet in it."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    # WHY : Assumption: cidrhost is the well-formedness test because it errors
    #       on anything that is not a valid CIDR block, including values a
    #       regex would accept and the address arithmetic would not, such as
    #       10.0.0.0/33. Wrapping it in `can` turns that error into this
    #       message at plan time; without the check the first failure appears
    #       inside a cidrsubnet call in main.tf's locals, which reports the
    #       arithmetic rather than the input that broke it.
    #       Trade-off: this asserts well-formedness ONLY, and deliberately does
    #       not also bound the prefix length. Whether the block is large enough
    #       to be divided is a relationship between three inputs, not a
    #       property of this one, so it is asserted once on subnet_newbits
    #       below rather than approximated twice and left to disagree.
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "vpc_cidr must be a well-formed IPv4 CIDR block, for example 10.0.0.0/16."
  }
}

# WHAT: the zone count, expressed as an input yet fixed at 3 by both callers.
# WHY : Assumption: three availability zones in one Region is the mandated
#       topology for this stack, recorded as decision D8 in
#       docs/adr/ADR-008-security-and-identity.md; multi-Region and
#       disaster-recovery topology are out of scope. It is an input so the
#       arithmetic in main.tf reads the count from one place instead of
#       repeating a literal 3 at every netnum, subnet and gateway.
#
# WHY : Assumption: this is NOT a per-environment lever, which is the reading a
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
# WHY : Alternatives Considered: for the bounds being exactly 2 and 3,
#       leaving the count unbounded, or bounding it only below. Rejected
#       both. The lower bound of 2 exists solely so the module stays
#       exercisable in a Region offering two usable zones, since a
#       single-zone VPC has no zone-failure behaviour left to preserve. The
#       upper bound is 3 because raising it changes the subnet arithmetic
#       and the NAT gateway count -- that is, it changes the topology, which
#       is the one thing this input must not do.
variable "az_count" {
  description = "Number of availability zones the network spans, and therefore the number of subnets created in each of the three tiers and the number of NAT gateways. Both environment roots pass 3, the mandated topology; the accepted range is 2 to 3 only so the module stays exercisable in a Region that offers two usable zones."
  type        = number
  default     = 3

  validation {
    # WHY : Assumption: the value is a count of zones, so a fraction is
    #       meaningless -- the floor comparison rejects 2.5, which the range
    #       test alone would admit and which would then reach a slice length
    #       and a cidrsubnet netnum as a non-integer, where it fails with a
    #       diagnostic about the function rather than about the input.
    condition     = var.az_count == floor(var.az_count) && var.az_count >= 2 && var.az_count <= 3
    error_message = "az_count must be the whole number 2 or 3; both environment roots pass 3, and a value above 3 would change the subnet arithmetic and the NAT gateway count, which is a topology change."
  }
}

# WHAT: one derivation parameter in place of explicit per-tier subnet lists.
# WHY : Alternatives Considered: three `list(string)` inputs, one per tier,
#       each holding an explicit CIDR per zone. Rejected. At three zones that
#       is nine hand-maintained CIDRs which must be kept mutually
#       non-overlapping, kept in the same order as the zone list, kept
#       consistent with az_count, and kept identical in intent between two
#       roots -- and nothing checks any of those four properties, so the first
#       inconsistency surfaces as a subnet overlapping another or a tier
#       missing a zone. One derivation from one block cannot drift out of step
#       with itself.
#       Trade-off: the caller loses control over exactly where each tier lands
#       inside the block, accepted in exchange for an arithmetic that cannot
#       become internally inconsistent. The addressing the derivation produces
#       is documented in this module's README.
variable "subnet_newbits" {
  description = "Number of bits cidrsubnet adds to the vpc_cidr prefix when carving each subnet, which fixes every subnet's size: at the default /16 and 4 additional bits each subnet is a /20. It must admit at least `3 * az_count` distinct subnets, because the three tiers are taken from consecutive netnum ranges of the one block rather than from separate per-tier address lists."
  type        = number
  default     = 4

  validation {
    # WHY : Assumption: the lower bound is a RELATIONSHIP, not a number. Adding
    #       n bits yields pow(2, n) subnets, and the three tiers need one each
    #       per zone, so the block must admit at least 3 * az_count of them.
    #       Writing that as a computed condition rather than as a literal
    #       minimum is what keeps it correct when az_count changes: at three
    #       zones nine subnets are needed and four bits supply sixteen, but a
    #       hard-coded floor would go stale the moment the zone count moved.
    #       The upper bound of 8 is a size floor stated as a mechanism: at the
    #       default /16 it yields /24 tiers, holding 256 addresses each and 251
    #       usable ones once the five addresses AWS reserves in every subnet
    #       are deducted. That is the smallest tier still accommodating the
    #       tasks, the per-zone interface-endpoint network interfaces for eight
    #       endpoints, and the database instances placed in it. Past 8 the plan
    #       still succeeds and the subnets exhaust at run time instead, which
    #       is the failure this bound converts into a plan-time message.
    condition     = var.subnet_newbits == floor(var.subnet_newbits) && pow(2, var.subnet_newbits) >= 3 * var.az_count && var.subnet_newbits <= 8
    error_message = "subnet_newbits must be a whole number no greater than 8, and large enough that pow(2, subnet_newbits) is at least 3 * az_count, so one subnet per tier per availability zone can be carved from vpc_cidr."
  }
}


# -----------------------------------------------------------------------------
# Tagging
# -----------------------------------------------------------------------------

# WHAT: a map that ADDS to the tag set rather than being the whole of it.
# WHY : Assumption: the calling root's `provider "aws"` block sets
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

# WHAT: the one genuine per-environment lever on this surface.
# WHY : Assumption: log retention days is among the enumerated values the two
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
    # WHY : Assumption: the set is closed because the service accepts these
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

# WHY : Alternatives Considered: for this being optional rather than required,
#       requiring it, so a flow-log group could never be created without a
#       customer-managed key. Rejected, because this module depends on no other
#       module, and a required key ARN would create a hard dependency on the
#       sibling kms module -- the network could then not be planned or applied
#       on its own, which is how it is exercised in isolation.
#       Trade-off: with null the log group falls back to the CloudWatch Logs
#       service-default encryption, which is weaker than a customer-managed key
#       because the key is not one this account controls, rotates or revokes.
#
# WHY : Assumption: that trade-off is acceptable because both environment roots
#       DO pass the customer-managed key ARN the kms module produces, so the
#       encrypted path is the one that actually ships. The null default exists
#       for module-level composability, not as the intended production
#       configuration. Stating that plainly is what keeps the policy scan
#       honest; the alternative is a suppression comment asserting the same
#       thing where nothing can check it.
#
# WHY : Assumption: an ARN is named here as a CONTRACT, in a module that
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
  description = "ARN of a customer-managed KMS key with which to encrypt the CloudWatch Logs group receiving this VPC's flow logs. Null leaves that group on the CloudWatch Logs service-default encryption, which is what allows this module to be planned and applied without the kms module; both environment roots pass a real key, so null is the composability default rather than the intended production setting."
  type        = string
  default     = null
  nullable    = true
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
#     holding that gateway takes egress from all three. Trade-off: three hourly
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
#   - No `region`. Assumption: the Region comes from the calling root's
#     `provider "aws"` block, which this module inherits rather than
#     configuring, and main.tf reads it back through a `data "aws_region"`
#     lookup where it is needed -- to compose the Region-qualified service
#     names of the interface endpoints. A `region` input here could disagree
#     with the provider's, and the disagreement would not itself be an error:
#     resources would be created in the provider's Region while endpoint
#     service names were composed for the input's, which fails at apply with a
#     diagnostic naming neither input. One source for the Region cannot
#     disagree with itself.
# =============================================================================
