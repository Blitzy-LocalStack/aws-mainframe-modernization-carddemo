# =============================================================================
# infra/modules/aurora-postgresql/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain and provider contract under which every other file in
#   the `aurora-postgresql` module is parsed. That module provisions the Aurora
#   PostgreSQL Serverless v2 cluster replacing the ten VSAM KSDS base clusters
#   and three alternate indexes of the baseline, each formerly created by an
#   in-stream IDCAMS DEFINE CLUSTER such as app/jcl/ACCTFILE.jcl:36; the
#   replacement is encrypted and backed up, where all eight DEFINE FILE stanzas
#   in app/csd/CARDDEMO.CSD ran RECOVERY(NONE) and JOURNAL(NO). Both baseline
#   trees are REFERENCE-ONLY: cited here, never edited.
#
#   Assumptions: this directory is a reusable MODULE, never a Terraform root. It
#   is consumed as `source = "../../modules/aurora-postgresql"` by
#   infra/envs/dev/main.tf and infra/envs/prod/main.tf, and those roots own
#   provider configuration including region and `default_tags`. This file
#   therefore declares only what a called module may own -- a CLI floor and a
#   provider requirement -- and omits `provider`, `backend`,
#   `configuration_aliases` (the stack is single-region) and every provider the
#   module does not itself use. Inputs and outputs, each carrying its own
#   `description`, are declared in variables.tf and outputs.tf. Both constraints
#   below are evaluated at `terraform init` and fail closed there, before a plan
#   exists, surfacing through whichever root called the module.
#
#   Alternatives Considered: requiring `hashicorp/random`, which would suit the
#   suffix that keeps one incarnation's final-snapshot identifier from colliding
#   with the next one's. Rejected because credential GENERATION is
#   infra/modules/secrets' concern: declaring a second random provider here for
#   one non-credential string would put a provider in this module's contract and
#   make the module look like a second generator. main.tf takes the suffix from
#   the built-in `terraform_data` resource instead, whose generated `id` is
#   created once, kept in state and replaced only when `triggers_replace`
#   changes. `timestamp()` and `uuid()` were rejected at that resource because
#   both re-evaluate on every plan, so the cluster would show a perpetual
#   in-place update for a name that only matters at deletion.
# =============================================================================

terraform {
  # Trade-offs: this package is validated on 1.15.8, yet a floor is
  #   declared instead of that exact patch. A module is consumed by every
  #   root that calls it, so pinning one patch would reject any caller
  #   running a different one and force lockstep CLI upgrades across the
  #   whole tree. The accepted cost is that a caller may run a newer patch
  #   than the one this was validated against, which the environment roots
  #   absorb by validating there.
  required_version = ">= 1.15.0"

  required_providers {
    # Assumptions: a provider of 5.81.0 or later is required before an
    #   Aurora Serverless v2 cluster will accept a zero minimum capacity,
    #   which the dev environment relies on to scale down to nothing while
    #   idle; 6.56 clears that floor. Holding the major at 6 keeps an
    #   unreviewed 7.x from renaming or retiring the cluster arguments
    #   this module sets. The registry address is spelled in full because
    #   the bare `aws` short form is a legacy shorthand.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

  }
}
