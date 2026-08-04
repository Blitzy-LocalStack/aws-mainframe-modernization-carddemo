# =============================================================================
# infra/modules/network/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the `network` module -- the three-availability-
#   zone VPC that carries the migrated CardDemo workload, with its public,
#   private-application and isolated-data subnet tiers, its NAT egress, its
#   eight interface endpoints, its S3 gateway endpoint and its three security
#   groups. Every value a calling environment root can configure is declared
#   here, and nothing else is configurable: anything absent from the list below
#   is a property of the network fixed in main.tf, not a per-environment choice.
#
#   That distinction carries more weight in this module than in any other,
#   because the two environment roots are required to be identical in TOPOLOGY
#   and to differ only in sizing and retention values. A network input is
#   therefore admissible only if varying it cannot change the shape of the
#   network. The four candidates that failed that test are named below, under
#   deliberate absences, so that a reader who expects to find them knows they
#   were considered.
#
#   Nothing here is read from the ambient environment and nothing is generated
#   inside the module. Every input arrives from infra/envs/dev/main.tf or
#   infra/envs/prod/main.tf.
#
# Parameters -- one required, six optional:
#   environment                 string       REQUIRED. Suffixes every name tag.
#   name_prefix                 string       Common resource-name prefix.
#   tags                        map(string)  Network-specific tags.
#   vpc_cidr                    string       Address space the three tiers are
#                                            carved out of.
#   interface_endpoint_services  list(string) AWS services reachable privately.
#   app_container_port          number       Port the load balancer reaches the
#                                            application tier on.
#   database_port               number       Port the application tier reaches
#                                            the isolated data tier on.
#
#   Each block below carries the full `type` and `description` that tflint's
#   terraform_typed_variables and terraform_documented_variables rules require;
#   the summary above is a map of the surface, not a second copy of it.
#
# Return values:
#   None. A variables.tf declares no output, so the VPC identifier, the three
#   per-tier subnet identifier lists and the three security group identifiers
#   this module publishes to its caller are declared in
#   infra/modules/network/outputs.tf.
#
# Errors / Exceptions:
#   `environment` has no default, so omitting it stops the calling root at
#   `plan` with a missing-required-argument error instead of tagging one
#   environment's network with the other's name. Every other input carries a
#   `validation` block that rejects an out-of-domain value at `plan` time rather
#   than letting the service reject it partway through an apply, when subnets
#   and gateways already exist.
#
# WHY (non-obvious design decisions):
#   - Assumptions: THREE deliberate absences, each of which a reader is likely to
#     look for.
#       * No `availability_zone_count`. The target architecture is three zones,
#         single region; multi-region and disaster-recovery topology are out of
#         scope. An input here would let one environment run two zones and the
#         other three, which is a topology difference between the roots, and the
#         roots are required to have none. The count is fixed in main.tf, which
#         reads the region's available zones and takes three.
#       * No `single_nat_gateway`. Collapsing three NAT gateways to one is the
#         standard development cost saving, and it is refused for the same
#         reason: it changes the failure domain, not the size, of the network.
#         One zone's loss would take egress from all three. Cost is reduced in
#         this stack where the AAP permits it -- database capacity, task count,
#         retention and price class -- and not by thinning the network.
#       * No `enable_dns_support` or `enable_dns_hostnames`. Both must be true
#         for an interface endpoint's private DNS to resolve, which is the
#         entire mechanism by which task traffic to AWS APIs stays inside the
#         VPC. Exposing them would expose a way to switch that off silently:
#         the plan would be clean, the endpoints would exist, and every SDK call
#         would resolve to a public address instead.
#   - Assumptions: the two PORT inputs exist for a reason that is not
#     configurability. The same two numbers appear in three modules -- the
#     application container port in `ecs-service`, the database port in
#     `aurora-postgresql`, and both again in this module's security group rules.
#     Declaring them here lets the root pass one value to all three, so a rule
#     and the listener it is meant to admit cannot disagree. A literal in each
#     module would work until one of the three was changed.
#   - Alternatives Considered: taking the three subnet tiers as explicit CIDR
#     lists. Rejected in favour of deriving all nine subnets from `vpc_cidr` in
#     main.tf. Explicit lists are three more inputs that must stay mutually
#     non-overlapping, correctly ordered against the zone list, and consistent
#     between two roots; a deterministic derivation cannot drift, and the
#     addressing it produces is documented in this module's README.
#   - Assumptions: no input here accepts a credential, an account identifier or
#     an ARN, and no default holds one. Identifiers travel OUTWARD from this
#     module as outputs; the project's no-secrets-in-source constraint admits no
#     exception.
#   - Where a comment below reasons about `terraform apply`, it is describing
#     what an input means at that point, not reporting on a provisioned stack.
#     This tree is authored and statically validated -- formatted, validated,
#     planned, linted and policy-scanned; applying it to a live account is an
#     operator action outside this scope.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and environment
# -----------------------------------------------------------------------------

# WHY this is the one input with no `default`. Trade-offs: a defaulted
# environment name is the precise mechanism by which one environment's VPC ends
# up carrying the other environment's name tag. The caller omits the argument,
# the module names the network regardless, and the mistake survives review
# because the plan is clean and the tag reads as deliberate. Requiring the value
# costs each root one line.
#
# WHY the accepted values are exactly two. Assumptions: two environment roots
# exist, infra/envs/dev and infra/envs/prod, and this module is called only from
# those two. A third value would tag a network whose state no root tracks.
variable "environment" {
  description = "Environment name interpolated into the name tag of the VPC, every subnet, every route table, every gateway, every endpoint and every security group, so one environment's network is distinguishable from the other's; must be `dev` or `prod`, the two environments that have a Terraform root under infra/envs/."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\", matching the environment root under infra/envs/ that calls this module."
  }
}

# WHY the characters are checked rather than trusted. Trade-offs: this prefix is
# concatenated into name tags across roughly thirty resources. Restricting it to
# lowercase letters, digits and hyphens keeps every one of those names legal and
# greppable, and catches a space or an uppercase letter at plan time. The cost
# paid is that a legitimately unusual prefix has to be spelled out here first.
#
# WHY the name and the default match the rest of the tree instead of being chosen
# here (Assumption): every directory under infra/ takes its prefix through a
# variable of this name with this default, which is what lets a root pass one
# value to every module it calls.
variable "name_prefix" {
  description = "Prefix concatenated into the name tag of every resource this module creates, ahead of the tier and the environment, giving the whole network one greppable identity shared with the rest of the stack; lowercase letters, digits and hyphens only, at most 32 characters."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix)) && length(var.name_prefix) <= 32
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and hyphens, beginning and ending with a letter or digit -- for example \"carddemo\"."
  }
}

# WHY an empty default reads as complete here rather than as an oversight
# (Assumption): the baseline tag set is not this module's to supply. Each calling
# root configures `default_tags` on its own `provider "aws"` block and the
# provider merges that map into every taggable resource it creates, so every
# subnet and gateway carries the root's common tags whether or not this variable
# is passed. What this input adds is the layer above that.
variable "tags" {
  description = "Tags merged onto the resources this module creates, layered on top of the common tag set the calling root already applies through its provider's `default_tags`; defaults to none, because the baseline tags arrive from the root rather than from this module."
  type        = map(string)
  default     = {}
}

# -----------------------------------------------------------------------------
# Address space
# -----------------------------------------------------------------------------

variable "vpc_cidr" {
  description = "Address space the VPC occupies and the only addressing input this module takes. main.tf derives all nine subnets from it deterministically -- three public, three private-application and three isolated-data, one of each per availability zone -- so a change here moves every subnet together and cannot leave two tiers overlapping."
  type        = string
  default     = "10.0.0.0/16"

  validation {
    # WHY : Assumptions: the prefix length is bounded at both ends, and both
    #       bounds are consequences of the derivation rather than preferences. A
    #       block shorter than /16 wastes address space no tier will use; a block
    #       longer than /20 cannot be divided into nine subnets that each leave
    #       room for the tasks, endpoints and database instances placed in them,
    #       and the failure would appear as a subnet-creation error partway
    #       through an apply rather than as a rejected input. `cidrhost` is used
    #       as the well-formedness check because it errors on anything that is
    #       not a valid CIDR, which `regex` alone would not catch for values like
    #       10.0.0.0/33.
    condition     = can(cidrhost(var.vpc_cidr, 0)) && tonumber(split("/", var.vpc_cidr)[1]) >= 16 && tonumber(split("/", var.vpc_cidr)[1]) <= 20
    error_message = "vpc_cidr must be a valid IPv4 CIDR block with a prefix length between /16 and /20 inclusive, so that nine usably sized subnets can be derived from it."
  }
}

# -----------------------------------------------------------------------------
# Private connectivity to AWS services
# -----------------------------------------------------------------------------

variable "interface_endpoint_services" {
  description = "Short service names to create an interface endpoint for, each expanded in main.tf into the region-qualified endpoint service name. Together with the S3 gateway endpoint that main.tf always creates, these are what keep task traffic to AWS APIs inside the VPC instead of routing it out through NAT."

  type = list(string)

  # Assumptions: the eight defaults are each required by a specific consumer, and
  # none is decorative. `ecr.api` and `ecr.dkr` are both needed to pull an image
  # -- the first authorises, the second transfers, and omitting either leaves a
  # task unable to start. `logs` carries container and state-machine logging.
  # `secretsmanager` is how a service reads the generated database credential
  # that is deliberately not in this repository. `kms` is required transitively
  # by every one of those, because each reads or writes ciphertext under a
  # customer-managed key. `sqs` carries the authorization and inquiry queues.
  # `states` is how the reporting service starts an on-demand execution and how
  # a batch task reports back. `ssm` carries the parameters that replace the JCL
  # DD statements, including the online read-only flag the batch window sets.
  #
  # WHY the S3 endpoint is absent from this list. Assumptions: S3 is reached
  # through a GATEWAY endpoint, which is a route-table entry rather than an
  # elastic network interface, so it is created unconditionally in main.tf and
  # cannot be expressed as an entry here. Listing "s3" would build an interface
  # endpoint for it as well, which is a second, billed path to a service that
  # already has a free one.
  #
  # WHY this is an input at all when the architecture fixes the set. Trade-offs:
  # the list is visible in the plan and in this module's generated
  # documentation, so an operator can see which private paths exist without
  # reading main.tf. The accepted risk is that a caller can REMOVE an entry, and
  # each removal silently degrades a documented path to NAT egress rather than
  # failing -- which is why every entry's consumer is named above.
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
    # WHY : Assumptions: the entries are short names, not full endpoint service
    #       names, and the check enforces that shape. A caller passing the fully
    #       qualified `com.amazonaws.<region>.logs` would have the region
    #       prepended a second time by main.tf, producing a service name the API
    #       rejects during apply. An empty list is permitted deliberately: it is
    #       the only way to express a network with no interface endpoints, and a
    #       caller choosing that has chosen NAT egress for AWS API traffic.
    condition     = alltrue([for s in var.interface_endpoint_services : can(regex("^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$", s)) && !can(regex("^com\\.amazonaws\\.", s))])
    error_message = "Each interface_endpoint_services entry must be a short service name such as \"logs\" or \"ecr.dkr\", not a fully qualified com.amazonaws.<region>.<service> name, which main.tf composes."
  }
}

# -----------------------------------------------------------------------------
# Security group ports
#
# The two inputs below are the only numeric parameters of this module's security
# group rules, and they exist so that one value reaches every module that needs
# it. Egress on 443 to the interface endpoints is NOT an input: it is the port
# the endpoints themselves listen on, so it is fixed in main.tf.
# -----------------------------------------------------------------------------

variable "app_container_port" {
  description = "Port the application tier listens on, which main.tf admits from the load balancer security group to the application security group and from nowhere else. The same value is passed to the ecs-service module as its container port, so the ingress rule and the container it admits traffic to are supplied from one place."
  type        = number
  default     = 8080

  validation {
    # WHY : Assumptions: the floor is 1024 rather than 1. A port below 1024
    #       requires the process to start privileged, and every container image
    #       in this stack runs as a non-root user, so a privileged port here
    #       would produce a container that cannot bind and a security group that
    #       admits traffic to nothing.
    condition     = var.app_container_port >= 1024 && var.app_container_port <= 65535 && floor(var.app_container_port) == var.app_container_port
    error_message = "app_container_port must be a whole number from 1024 to 65535; ports below 1024 need a privileged process, and every container in this stack runs as a non-root user."
  }
}

variable "database_port" {
  description = "Port the isolated data tier listens on, which main.tf admits from the application security group to the data security group and from nowhere else. The same value is passed to the aurora-postgresql module as its port, so the rule and the cluster it protects cannot disagree."
  type        = number
  default     = 5432

  validation {
    # WHY : Trade-offs: the port is validated only as a legal unprivileged port
    #       rather than pinned to the engine's default. Moving a database off its
    #       well-known port is a legitimate hardening choice a root may make, and
    #       pinning the value here would refuse it while buying nothing: a wrong
    #       port produces a connection timeout that names itself clearly, unlike
    #       the failure modes the ARN checks elsewhere in this tree guard
    #       against.
    condition     = var.database_port >= 1024 && var.database_port <= 65535 && floor(var.database_port) == var.database_port
    error_message = "database_port must be a whole number from 1024 to 65535."
  }
}
