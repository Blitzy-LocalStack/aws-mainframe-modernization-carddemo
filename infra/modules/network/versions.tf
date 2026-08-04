# =============================================================================
# infra/modules/network/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the `network` module -- the three-AZ
#   VPC that carries the migrated CardDemo workload, with its public,
#   private-application and isolated-data subnet tiers, NAT egress, interface
#   endpoints and S3 gateway endpoint. This file settles two questions and no
#   others: which Terraform CLI releases may build the module, and which
#   `hashicorp/aws` releases may satisfy it.
#
#   Reusable MODULE, not a Terraform root. infra/envs/dev and infra/envs/prod
#   consume it as `source = "../../modules/network"`, so it is initialised
#   transitively inside those roots' module graphs and is never applied on its
#   own. Every absence noted below follows from that single fact.
#
#   Lineage: the network boundary expressed here replaces the implicit one of
#   the CICS region catalogued in app/csd/CARDDEMO.CSD, whose eight file
#   definitions each record RECOVERY(NONE) and JOURNAL(NO). That baseline is
#   reference-only and keeps running unchanged -- this tree adds a path beside
#   it rather than removing one.
#
# Parameters:
#   None. There is no `variable` here, and a version constraint takes no
#   argument; the module's own inputs live in variables.tf. What a reader most
#   often arrives looking for -- the AWS region and the common tag set -- is
#   deliberately elsewhere: a module inherits its caller's aws provider
#   configuration, so `region` and `default_tags` are set once in
#   infra/envs/<env> and reach every module from there.
#
# Return values:
#   None. No `output` here; the module's outputs -- VPC id, the subnet id
#   lists per tier, the security group ids -- live in outputs.tf. What this
#   file yields is a satisfied constraint: once both bounds hold, every other
#   file in the module may assume them.
#
# Exceptions or errors:
#   `terraform init` fails, before any plan exists, when the operator's CLI
#   predates 1.15.0 or when no available `hashicorp/aws` release falls inside
#   `~> 6.56`. Failing that early is the intent -- a plan computed against an
#   unintended provider major is worse than no plan at all. Were this file
#   missing, the failure would instead land in infra-ci: tflint's
#   terraform_required_version reports that the `required_version` attribute
#   is required, and terraform_required_providers reports a missing version
#   constraint for aws.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: this module configures no aws provider of its
#     own. Doing so was rejected because it makes the module un-composable --
#     it would fix a region the caller could not override, discard the root's
#     `default_tags` so every resource lost the common tag set, and leave a
#     root no way to pass in an aliased provider for a second region.
#     Inheriting the caller's configuration keeps all three open.
#   - Assumptions: no state configuration appears here, because a module never
#     owns state. The state definition belongs to the calling root, in
#     infra/envs/<env>/backend.tf, and infra/bootstrap provisions the
#     versioned bucket and lock table standing behind it.
#   - Alternatives Considered: `hashicorp/random` is pinned across this tree
#     at ~> 3.9, yet it is omitted here. Carrying it for textual symmetry was
#     rejected because this module derives every value from its inputs and
#     generates no random one, and tflint's unused-declaration gate --
#     terraform_unused_required_providers, gating in infra-ci -- reports any
#     entry the module does not use. infra/bootstrap/versions.tf reaches the
#     same conclusion for the same reason. Trade-offs: the directories under
#     infra/ are then not textually identical, which is the
#     lesser cost of the two.
# =============================================================================

terraform {
  # WHAT: floor the CLI at 1.15.0 rather than pinning one release exactly.
  # WHY : Assumptions: 1.15.8 is the release this tree was authored and
  #       validated against, and 1.15.0 is the oldest that accepts the
  #       configuration syntax used across infra/. Trade-offs: a floor lets an
  #       operator take a newer patch or minor release without editing all
  #       infra/ directories, at the cost of not naming one exact
  #       build; the calling root's lock file, not this constraint, is what
  #       records the exact selection a plan was computed against.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Alternatives Considered: `= 6.56.0` was rejected for freezing
    #       out provider fixes and forcing a coordinated edit across the tree
    #       for each one, and an open `>= 6.56` was rejected because it would
    #       silently adopt a 7.x major whose breaking changes surface as a
    #       failed plan against configuration nobody touched. `~> 6.56`
    #       admits 6.56.x and later 6.x minors but never 7.x, so a major move
    #       stays a deliberate, reviewable edit. The floor itself traces to
    #       the sibling aurora-postgresql module, which needs a provider no
    #       older than 5.81.0 to accept a zero minimum Aurora capacity, and
    #       6.56 clears that comfortably. Trade-offs: this module provisions
    #       no database and would run on a far older provider, but repeating
    #       one constraint in every infra/ directory means a calling root
    #       resolves a single aws provider version for its whole module
    #       graph instead of failing on an empty intersection of ranges.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
