# =============================================================================
# infra/modules/cognito/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the Terraform toolchain and provider contract for the CardDemo
#   cognito module. That module replaces the mainframe sign-on path -- the
#   USRSEC VSAM file defined at app/csd/CARDDEMO.CSD L88 and read by the
#   READ-USER-SEC-FILE paragraph of app/cbl/COSGN00C.cbl (L209-L257) -- with a
#   Cognito user pool, an app client, the carddemo-admin and carddemo-user
#   groups that carry the baseline's 'A' and 'U' user types, and the ten seed
#   users the baseline shipped. Terraform loads every .tf file in a directory as
#   a single configuration, so this file is load-bearing, not informational:
#   without it the module declares no provider requirements at all and no
#   calling root can resolve the providers that main.tf instantiates.
#
# Parameters:
#   Every constraint here is a parameter of that contract, and each one carries
#   its own WHAT/WHY comment at its declaration site below.
#   - required_version (version-constraint string) -- the oldest Terraform CLI
#     permitted to evaluate this module.
#   - required_providers.aws (source plus version constraint) -- hashicorp/aws,
#     the provider behind every user pool, group, app client and Secrets
#     Manager entry the module creates.
#   - required_providers.random (source plus version constraint) --
#     hashicorp/random, the provider that produces each seed user's initial
#     password during apply.
#
# Returns:
#   The aws and random provider namespaces, resolvable by the four sibling
#   files of this module. Provider CONFIGURATION is deliberately not part of
#   that surface: region, default tags and credentials all arrive from the root
#   that invokes the module.
#
# Errors:
#   - A CLI below the declared floor fails at `terraform init` with an
#     unsupported-version error, before any resource is planned.
#   - A provider release outside a constraint below fails at `terraform init`
#     with no matching version available; the .terraform.lock.hcl tracked in
#     each calling root is what fixes the exact build that init selects.
#   - Declaring a provider the module never instantiates fails the gating
#     tflint step (terraform_unused_required_providers) rather than `terraform
#     validate`, so the random entry is legal only while main.tf keeps a
#     random_* resource.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this directory is a reusable child module, invoked as
#     source = "../../modules/cognito" by infra/envs/dev and infra/envs/prod
#     and validated transitively when one of those roots runs init and then
#     validate. It is never applied on its own, which is why it declares
#     provider REQUIREMENTS and carries no provider CONFIGURATION.
#   - Trade-offs: the two provider constraints are ranges rather than exact
#     pins, so two checkouts can resolve different provider builds. Accepted
#     because exact pins force a lockstep edit across every Terraform
#     directories in this package for every patch release, while the lock file
#     committed in each root restores exact reproducibility without that cost.
#   - Refactoring Rationale: the baseline authenticated by comparing a
#     cleartext eight-character field (app/cpy/CSUSR01Y.cpy L21, compared at
#     app/cbl/COSGN00C.cbl L223) whose seed values are committed as in-stream
#     JCL data (app/jcl/DUSRSECJ.jcl L35-L44 -- ten users sharing a single
#     literal). Declaring hashicorp/random here is how that field is removed
#     instead of ported: the module generates credentials during apply and
#     stores them in Secrets Manager, so none is ever authored into a file in
#     this repository.
# =============================================================================

# WHY : Terraform resolves provider REQUIREMENTS per module but provider
#       CONFIGURATION only per root, so a provider block here would shadow the
#       region, credentials and default tags that infra/envs/dev and
#       infra/envs/prod supply. Remote state is likewise a root-only concern,
#       declared by each environment root against the S3 bucket and DynamoDB
#       lock table that infra/bootstrap provisions.
#       Alternatives Considered: adding an aws provider block here so that this
#       directory could be applied standalone -- rejected, because standalone
#       apply is not a use case for a module whose only callers are two
#       environment roots that differ in precisely the values such a block
#       would freeze.
terraform {
  # WHY : a floor rather than a pin. This package is authored and validated on
  #       1.15.8, so the constraint states the oldest CLI that can parse this
  #       configuration and claims nothing narrower.
  #       Alternatives Considered: `= 1.15.8`, rejected because pinning one
  #       patch would force an edit in every Terraform directory of
  #       this package before anyone could adopt 1.15.9; and `~> 1.15`, which
  #       admits later 1.x but refuses a 2.0 CLI outright -- rejected because
  #       the CLI is operator-selected and shared by every directory here,
  #       unlike the providers below, which Terraform resolves per
  #       configuration and where a major bump genuinely can rename or remove
  #       arguments this module sets.
  #       Trade-offs: an open upper bound accepts a CLI newer than any this
  #       package has been exercised on. Accepted because the constraint still
  #       raises the one failure it exists for -- an unsupported-version error
  #       at init, before any resource is planned -- against a CLI too old to
  #       parse this configuration.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : 6.56.0 is the release this package is verified against, and any 6.x
    #       also clears the 5.81.0 minimum that zero-capacity Aurora needs
    #       elsewhere in this package, so every module can share one
    #       constraint. Trade-offs: the range admits later 6.x releases, which
    #       lets a provider fix reach this module with no edit here but means
    #       init alone does not guarantee an identical build -- the lock file
    #       committed in each calling root is what does that. The upper bound
    #       is deliberate: a 7.0 major is free to rename or remove the
    #       aws_cognito_* arguments main.tf sets.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : main.tf generates each seed user's initial password with
    #       random_password during apply and hands it straight to Secrets
    #       Manager. Alternatives Considered: accepting those passwords as
    #       input variables instead -- rejected, because the values would then
    #       have to live in a tfvars file or a CI variable, which is exactly
    #       the "no secrets committed to the repository" constraint this module
    #       exists to satisfy and exactly the defect the baseline exhibits at
    #       app/jcl/DUSRSECJ.jcl L35-L44.
    #       Assumptions: this entry is load-bearing, not defensive. tflint's
    #       unused-declaration checks are enabled in infra/.tflint.hcl and gate
    #       CI, and terraform_unused_required_providers is the one that fires
    #       here -- it reports a provider declared in required_providers but
    #       never used by the module. Dropping the random_* resources from
    #       main.tf without also dropping this entry therefore turns a green
    #       lint red. The sibling infra/bootstrap/versions.tf omits random for
    #       that same reason -- nothing in that root generates a random value
    #       -- so the two files are consistent, not contradictory.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}
