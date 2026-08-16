# =============================================================================
# infra/modules/cognito/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the Terraform toolchain and provider contract for the CardDemo
#   cognito module, which stands in for the mainframe sign-on path -- the USRSEC
#   VSAM file defined at app/csd/CARDDEMO.CSD L88 and read by the
#   READ-USER-SEC-FILE paragraph of app/cbl/COSGN00C.cbl (L209-L257). Terraform
#   loads every .tf file in a directory as one configuration, so this file is
#   load-bearing rather than informational: without it the module declares no
#   provider requirements and no calling root can resolve the providers main.tf
#   instantiates.
#
# Parameters:
#   - required_version (version-constraint string) -- the oldest Terraform CLI
#     permitted to evaluate this module.
#   - required_providers.aws (source plus version constraint) -- hashicorp/aws,
#     the provider behind the user pool, its groups, resource server, domain,
#     seed users and the Secrets Manager entries that hold their handover
#     credentials.
#   - required_providers.random (source plus version constraint) --
#     hashicorp/random, which supplies the opaque suffix in each seed-user
#     secret's name.
#
# Returns:
#   The aws and random provider namespaces, resolvable by the sibling files of
#   this module. Provider CONFIGURATION is deliberately not part of that
#   surface: region, default tags and credentials all arrive from the root that
#   invokes the module.
#
# Errors:
#   - A CLI below the declared floor fails at `terraform init` with an
#     unsupported-version error, before any resource is planned.
#   - A provider release outside a constraint below fails at `terraform init`
#     with no matching version available; the .terraform.lock.hcl tracked in
#     each calling root is what fixes the exact build init selects.
#   - Declaring a provider the module never instantiates fails the gating tflint
#     step (terraform_unused_required_providers) rather than `terraform
#     validate`, so the random entry is legal only while main.tf keeps a
#     random_* resource.
# =============================================================================

# WHY : Assumptions: Terraform resolves provider REQUIREMENTS per module but
#       provider CONFIGURATION only per root, so a provider block here would shadow the
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
  # WHY : Assumptions: a floor rather than a pin. This package is authored and
  #       validated on 1.15.8, so the constraint states the oldest CLI that can
  #       parse this configuration and claims nothing narrower.
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
  #       raises the one failure it exists for -- an unsupported-version error at
  #       init, before any resource is planned -- against a CLI too old to parse
  #       this configuration.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Assumptions: 6.56.0 is the release this package is verified against,
    #       and any 6.x also clears the 5.81.0 minimum that zero-capacity Aurora and its
    #       mandatory auto-pause argument together need (5.80.0 and 5.81.0)
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

    # WHY : Assumptions: main.tf generates each seed user's initial password with
    #       random_password during apply and hands it straight to Secrets
    #       Manager. Alternatives Considered: accepting those passwords as
    #       input variables instead -- rejected, because the values would then
    #       have to live in a tfvars file or a CI variable, which is exactly
    #       the "no secrets committed to the repository" constraint this module
    #       exists to satisfy and exactly the defect the baseline exhibits at
    #       app/jcl/DUSRSECJ.jcl L35-L44.
    #       Assumptions: this entry is load-bearing, not defensive. tflint's
    #       terraform_unused_required_providers rule is enabled in
    #       infra/.tflint.hcl and gates CI, so dropping the random_* resources
    #       from main.tf without also dropping this entry turns a green lint red.
    #       The sibling infra/bootstrap/versions.tf omits random because nothing
    #       in that root generates a random value, so the two files agree.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}
