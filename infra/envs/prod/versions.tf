# =============================================================================
# infra/envs/prod/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the CardDemo PRODUCTION environment
#   root. It fixes the minimum Terraform CLI version, constrains every provider
#   this root's module graph is allowed to resolve, and configures the single
#   AWS provider that all sixteen child modules inherit.
#
#   SOLE OWNER OF `provider "aws"`. This file holds the ONLY AWS provider
#   configuration in the entire production module graph. Every module under
#   infra/modules/ deliberately declares provider *requirements* without a
#   provider *configuration*, so each one inherits the region and the
#   default_tags established here from whichever environment root calls it.
#   That inheritance is what lets one module tree serve both `dev` and `prod`
#   unchanged.
#
#   Consumes exactly two inputs, both declared in infra/envs/prod/variables.tf:
#     - var.aws_region  the region this environment deploys into
#     - var.tags        the tag set applied to every taggable resource
#   It reads no other variable, no local, no data source and no module output.
#
#   Deliberately NOT declared here:
#     - no `backend` block          -- infra/envs/prod/backend.tf owns the state
#     - no second `provider "aws"`  -- a duplicate is an init-time error
#     - no `provider "random"` body -- that provider takes no configuration
#     - no provider alias           -- single-region deployment
#     - no variable, output, resource, module, locals or data block
#
# WHY (non-obvious design decisions):
#   - Assumption: this root is structurally identical to infra/envs/dev by
#     design, so the `terraform` block and both provider constraints are the
#     same in both roots. Production differs from development only in sizing and
#     retention values, and every one of those lives in terraform.tfvars, never
#     here. A reader tempted to narrow a constraint "because production does not
#     need it" should read the per-pin rationale below first: at least one of
#     these floors is set by a requirement this root does not itself exercise.
#   - Trade-off: the backend configuration sits in its own file rather than
#     nested in this `terraform` block. That costs one extra file per root, and
#     buys the ability to run `terraform init -backend=false` so CI can resolve
#     and validate exactly this contract without any AWS credential.
# =============================================================================

terraform {
  # WHAT: floor for the Terraform CLI permitted to plan or apply this root.
  # WHY : Trade-off: a floor rather than an exact pin. CI validates on 1.15.8,
  #       so an exact `= 1.15.8` would need editing for every patch release even
  #       though no patch release can change what this file means. The floor is
  #       still restrictive where it matters: 1.15.0 is the oldest CLI this
  #       configuration has been validated against, so an unvalidated older
  #       binary fails at init rather than producing a plan nobody reviewed.
  required_version = ">= 1.15.0"

  required_providers {
    # WHAT: pins the AWS provider to the 6.56 minor series (6.56.0 verified as
    #       the constraint's floor; 6.57.1 resolves within it).
    # WHY : Assumption: this constraint is shared with infra/envs/dev and is
    #       identical in both roots by design. Its floor is set by a requirement
    #       THIS root never exercises: an Aurora Serverless v2 minimum capacity
    #       of zero needs provider 5.81.0 or later, and `~> 6.56` comfortably
    #       clears that. Production never sets a zero minimum -- the production
    #       Aurora minimum is held above zero in terraform.tfvars, so
    #       scale-to-zero and its resume latency are a development-only
    #       affordance. The pin is deliberately NOT narrowed for this root
    #       anyway, because the floor belongs to the shared
    #       infra/modules/aurora-postgresql contract rather than to either
    #       caller: were production to constrain the provider differently from
    #       development, one module source could resolve two different provider
    #       versions, and a plan reviewed against development would stop being
    #       evidence for production.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHAT: pins the random provider, which generates the Aurora master
    #       password and the Cognito seed-user passwords at apply time.
    # WHY : Alternatives Considered: omitting this entry and letting each child
    #       module carry its own `random` constraint. Rejected. Only two of the
    #       sixteen modules generate values with this provider -- `secrets` for
    #       the database credential and `cognito` for the seed users -- so no
    #       resource in THIS directory uses it, yet a root-level constraint is
    #       the only thing that holds the whole graph to one version of it,
    #       whereas per-child constraints can be satisfied independently and
    #       drift apart. Declaring the requirement with no matching
    #       `provider "random"` block is correct and complete: that provider
    #       takes no configuration, so there is nothing for a block to carry.
    # WHY : Trade-off: because no resource in this directory references it, a
    #       tflint run that enables `terraform_unused_required_providers` will
    #       flag this entry -- that rule is evaluated per directory and does not
    #       follow child-module usage. The rule sits outside tflint's
    #       recommended preset, and a graph-wide version anchor is worth the
    #       finding; `terraform_unused_declarations` is a separate rule and does
    #       not apply, since it targets unused variables, locals, data sources
    #       and provider ALIASES, and this root declares no alias.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}

# WHAT: the one and only AWS provider configuration for the production root.
# WHY : Assumption: infra/envs/prod/main.tf must NOT declare a second
#       `provider "aws"` block. Terraform treats two unaliased configurations
#       for the same provider as a duplicate-configuration error at
#       `terraform init`, so a well-meant second block breaks the root outright
#       instead of merging with this one. The note lives at the definition so
#       that a reader about to add a provider in main.tf meets the reason at
#       the source. Every module under infra/modules/ inherits THIS
#       configuration, region and default_tags included, which is exactly why
#       none of them defines its own.
provider "aws" {
  # WHAT: region for every AWS API call this root makes.
  # WHY : Assumption: the region is supplied by var.aws_region rather than
  #       written as a literal, so this file stays identical between the two
  #       environment roots and the region remains a terraform.tfvars decision.
  #       A literal here would also be the one place a deployment target could
  #       silently disagree with the backend's own region.
  region = var.aws_region

  # WHAT: applies var.tags to every taggable resource this root creates,
  #       including resources created inside all sixteen child modules.
  # WHY : Trade-off: this is the SOLE tagging mechanism for the production
  #       graph. The alternative -- threading a `tags` variable through sixteen
  #       modules and merging it into a `tags` argument on every resource -- was
  #       rejected because it makes correct tagging depend on every module
  #       author remembering to wire it, and a single omission yields an
  #       untagged resource that no plan review surfaces. Provider-level
  #       default_tags cannot be forgotten: a module would have to opt out to
  #       escape it. The accepted cost is indirection -- the tag values are no
  #       longer visible beside the resources that carry them, so a reader has
  #       to come here to learn what everything is tagged with. The migrated
  #       baseline made the same trade: every `DEFINE` in app/csd/CARDDEMO.CSD
  #       carries `GROUP(CARDDEMO)`, so no CICS resource could be defined
  #       outside the group that owns it.
  default_tags {
    tags = var.tags
  }
}
