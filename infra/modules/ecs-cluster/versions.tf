# =============================================================================
# infra/modules/ecs-cluster/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain floor and the AWS provider contract under which
#   every other file in the `ecs-cluster` module -- variables.tf, main.tf and
#   outputs.tf -- is evaluated.
#
#   This directory is a REUSABLE MODULE and never a Terraform root. It is
#   consumed as `module "ecs_cluster" { source = "../../modules/ecs-cluster" }`
#   from infra/envs/dev/main.tf and infra/envs/prod/main.tf, so it is never
#   applied on its own: it is validated transitively when a calling root runs
#   `terraform init -backend=false` and then `terraform validate`.
#
# Parameters:
#   None. This file declares no `variable` blocks. The module's inputs are
#   declared in variables.tf and its results are exposed by outputs.tf.
#
# Provides (the HCL analogue of a return value):
#   Two constraints that every calling root inherits and must satisfy -- the
#   Terraform CLI floor `required_version`, and the provider requirement
#   `required_providers.aws`. Terraform intersects these with the constraints
#   declared by the calling root and by every other module in the same
#   configuration, then selects one provider version for the whole graph.
#
# Failure modes (the HCL analogue of a raised exception):
#   - A calling root running a Terraform CLI older than the declared floor
#     fails at `terraform init` with an unsatisfied `required_version` error.
#   - A calling root whose own provider constraint cannot intersect `~> 6.56`
#     fails provider selection, and no plan is produced.
#   - A `provider` block added to this file would NOT be rejected: it would
#     silently take precedence over the one the calling root configures, and
#     would make this a legacy module that callers may not invoke with
#     `count`, `for_each` or `depends_on`. See the rationale at the foot of
#     the block.
#   This file also declares no `backend` block, because only a root configures
#   state: a module inherits the state configuration of the root that calls it.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: `required_version` is an open-ended floor rather
#     than an exact pin, because a shared module is consumed by roots that
#     carry constraints of their own.
#   - Trade-off: the provider is bounded to one minor series, so patch releases
#     arrive unattended but a provider major cannot.
#   - Alternatives Considered: no `provider` block is declared here, so the
#     calling root keeps control of region, account and provider aliasing.
#   - Alternatives Considered: `hashicorp/random` is deliberately absent,
#     because this module generates no random value and an unused provider
#     requirement fails the HCL lint gate.
# =============================================================================

terraform {
  # WHAT: the oldest Terraform CLI this module's configuration is written for.
  # WHY : Alternatives Considered: an exact `= 1.15.8` pin and a `~> 1.15`
  #       constraint were both rejected. Terraform intersects the constraints
  #       of the calling root and of every module in the configuration, so an
  #       exact pin here would make this module unusable by any root on a
  #       different patch release while buying no correctness; and `~> 1.15`
  #       would forbid a future 1.16 that still satisfies everything this
  #       module uses. An open-ended floor states only what the module actually
  #       needs. 1.15.8 is the release the infrastructure package is validated
  #       against.
  required_version = ">= 1.15.0"

  required_providers {
    # WHAT: the one provider this module requires, bounded to a minor series.
    # WHY : Trade-off: `~> 6.56` accepts 6.56.x patch releases but refuses a
    #       provider major, so a breaking change to a resource schema cannot
    #       reach this module through an unattended upgrade. The compromise
    #       accepted is that moving to a provider major becomes a deliberate,
    #       reviewed edit of this line.
    # WHY : Assumption: every module and every root under infra/ declares this
    #       identical constraint, so Terraform resolves one provider version
    #       for the whole configuration; a constraint that diverged in a single
    #       module would force provider re-selection across the calling root.
    #       The 6.56 floor is set by what the package needs elsewhere rather
    #       than by this module -- a provider new enough to accept a zero
    #       minimum Aurora Serverless v2 capacity. That reasoning belongs to
    #       docs/adr/ADR-003-datastore-targets.md and
    #       docs/adr/ADR-009-iac-tool.md and is deliberately not repeated here.
    # WHY : Assumption: `source` is written out instead of being left to
    #       implicit resolution, so the registry namespace is unambiguous both
    #       to a reader and to any calling root that declares an aws
    #       requirement of its own. It is kept adjacent to `version` so that
    #       `terraform fmt` aligns the pair, which is the canonical form the
    #       `terraform fmt -check -recursive infra/` gate enforces.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY: Alternatives Considered: declaring one uniform provider set across
    #      all sixteen modules was rejected. This module creates no random
    #      value, and infra/.tflint.hcl gates the unused-declaration checks --
    #      `terraform_unused_required_providers` is the one that fires on a
    #      requirement no resource consumes -- so a `hashicorp/random` entry
    #      here would fail the lint run rather than sit harmlessly beside the
    #      aws one. The sibling modules that do generate credentials declare
    #      it; its absence here is a deliberate consequence of that gate.
  }

  # WHY: Alternatives Considered: giving this module its own `provider` block
  #      was rejected, and the decisive reason is that Terraform does NOT
  #      reject it -- a local provider configuration is accepted as a legacy
  #      form, so the harm is silent rather than loud. Such a configuration
  #      takes precedence inside the module, so the region and the account the
  #      calling root selected would be quietly overridden for every resource
  #      declared here; Terraform additionally classifies the module as legacy,
  #      and a legacy module may not be invoked with `count`, `for_each` or
  #      `depends_on`, which would cost the calling root the ability to stamp
  #      out more than one cluster. Declaring the configuration only in the
  #      root keeps the region, the account, provider aliasing and all three of
  #      those meta-arguments available to the caller.
  #      Assumption: the calling root configures exactly one default aws
  #      provider -- two in a single directory is a duplicate-configuration
  #      error at init -- and this module inherits it together with its region
  #      and its `default_tags`, which is how the resources in main.tf are
  #      tagged without restating the tag set.
}
