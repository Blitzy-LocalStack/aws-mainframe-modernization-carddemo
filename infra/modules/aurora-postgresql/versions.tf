# =============================================================================
# infra/modules/aurora-postgresql/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain and provider contract under which every other file
#   in the aurora-postgresql module is parsed, planned and applied. That
#   module provisions the Aurora PostgreSQL Serverless v2 cluster that
#   replaces the ten VSAM KSDS base clusters and three alternate indexes of
#   the CardDemo mainframe application, each of which was formerly created by
#   an in-stream IDCAMS DEFINE CLUSTER such as app/jcl/ACCTFILE.jcl:36. The
#   replacement cluster is encrypted and backed up, whereas all eight DEFINE
#   FILE stanzas in app/csd/CARDDEMO.CSD ran RECOVERY(NONE) and JOURNAL(NO).
#   Both baseline trees are REFERENCE-ONLY: they are cited here, never edited.
#
# Parameters:
#   None. This file declares no `variable` block and so accepts no input; the
#   module's inputs are declared in variables.tf. The absence is recorded
#   explicitly so that it reads as deliberate rather than as a missing
#   section.
#
# Returns:
#   None. This file declares no `output` block and so exports no value to a
#   calling root; the module's outputs are declared in outputs.tf. Recorded
#   explicitly for the same reason.
#
# Errors:
#   Both constraints below are evaluated during `terraform init` and fail
#   closed there, before a plan exists and before any resource is touched. A
#   CLI older than the required_version floor is rejected as an unsupported
#   version, and an AWS provider outside the declared range makes provider
#   selection fail rather than quietly resolving to a version whose Aurora
#   arguments differ from the ones this module writes. Because a module is
#   never initialised on its own, both errors surface through whichever
#   environment root called it.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the AWS provider is floored at 6.56 because a provider of
#     5.81.0 or later is required before an Aurora Serverless v2 cluster will
#     accept a zero minimum capacity, which the dev environment depends on to
#     scale down to nothing while idle.
#   - Trade-offs: required_version is a floor rather than the exact 1.15.8 this
#     package is validated on, so that a shared module does not dictate a CLI
#     patch level to the roots that consume it.
#   - Alternatives Considered: a `provider "aws"` block was rejected here.
#     This directory is a module, not a root: infra/envs/dev and
#     infra/envs/prod call it and each of those roots owns provider
#     configuration, including region and default_tags. Configuring a provider
#     here would collide with the caller's own configuration and leave the
#     module usable by only one environment, defeating the reason it is
#     shared.
#   - Refactoring Rationale: the random provider is now required for one
#     non-credential value: the stable suffix on a final-snapshot identifier.
#     The master password is still generated and managed inside AWS by RDS;
#     adding this provider does not move credential ownership into Terraform.
#     A generated suffix is preferred to `timestamp()` or `uuid()` because
#     those functions change on every plan and would produce perpetual drift.
# =============================================================================

terraform {
  # WHY : Trade-offs: this package is validated on 1.15.8, yet a floor is
  #       declared instead of that exact patch. A module is consumed by every
  #       root that calls it, so pinning one patch would reject any caller
  #       running a different one and force lockstep CLI upgrades across the
  #       whole tree. The accepted cost is that a caller may run a newer patch
  #       than the one this was validated against, which the environment roots
  #       absorb by validating there.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Assumptions: a provider of 5.81.0 or later is required before an
    #       Aurora Serverless v2 cluster will accept a zero minimum capacity,
    #       which the dev environment relies on to scale down to nothing while
    #       idle; 6.56 clears that floor. Holding the major at 6 keeps an
    #       unreviewed 7.x from renaming or retiring the cluster arguments
    #       this module sets. The registry address is spelled in full because
    #       the bare `aws` short form is a legacy shorthand.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Assumptions: this is one of the two providers the whole package
    #       admits, and it is already present in both environment roots' lock
    #       files because infra/modules/secrets generates its credentials with
    #       it -- so declaring it here adds a dependency edge inside this module
    #       and no new provider to any deployment. The major is held for the same
    #       reason as the AWS one: an unreviewed 4.x could change how a generated
    #       value is kept, and this value's stability across applies is the whole
    #       point of using a resource rather than a function.
    # WHY : Alternatives Considered: composing the suffix from `timestamp()` or
    #       from `uuid()` instead, which would need no provider at all. Both are
    #       rejected at `local.final_snapshot_identifier`, where the argument is
    #       recorded: each is re-evaluated on every plan, so the cluster would
    #       show a perpetual in-place update for a value that only matters at
    #       deletion.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}
