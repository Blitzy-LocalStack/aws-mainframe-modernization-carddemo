# =============================================================================
# infra/envs/prod/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the CardDemo PRODUCTION environment
#   root. It fixes the minimum Terraform CLI version, constrains every provider
#   this root's module graph is allowed to resolve, and configures the single
#   AWS provider that every child module inherits.
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
#   - Assumptions: this root is structurally identical to infra/envs/dev by
#     design, so the `terraform` block and all four provider constraints are the
#     same in both roots. Production differs from development only in sizing and
#     retention values, and every one of those lives in terraform.tfvars, never
#     here. A reader tempted to narrow a constraint "because production does not
#     need it" should read the per-pin rationale below first: at least one of
#     these floors is set by a requirement this root does not itself exercise.
#   - Trade-offs: the backend configuration sits in its own file rather than
#     nested in this `terraform` block. That costs one extra file per root, and
#     buys the ability to run `terraform init -backend=false` so CI can resolve
#     and validate exactly this contract without any AWS credential.
# =============================================================================

terraform {
  # WHY : Refactoring Rationale: this was `>= 1.15.0`, an open-ended floor, and
  #       the openness was the defect. A bare floor admits every future release
  #       of the 1.x line, so the CLI that produced the reviewed plan and the CLI
  #       an operator runs six months later need not be the same MINOR at all --
  #       and a Terraform minor release is exactly where language behaviour,
  #       validation semantics and state-format handling change. The reviewed
  #       artifact for this root is a plan produced on 1.15.8; admitting 1.16 or
  #       1.20 would let that review be discharged by a toolchain nobody
  #       validated against.
  # WHY : Trade-offs: `~> 1.15.0` rather than an exact `= 1.15.8`. The pessimistic
  #       operator on the patch component accepts 1.15.0 through 1.15.x and
  #       refuses 1.16.0 and 2.x, which is the supported-major/minor policy this
  #       package is reviewed under. An exact pin would have to be edited for
  #       every patch release even though a patch release cannot change what this
  #       file means, and it would break every runner the moment its toolchain
  #       image moved forward within the series. Moving to a new MINOR is
  #       deliberately a reviewed edit to this line, in the same commit as the
  #       plan produced under it.
  # WHY : Assumptions: this constraint is what makes the committed
  #       .terraform.lock.hcl beside this file meaningful. The lock records the
  #       provider versions and checksums selected under a given CLI; a CLI from
  #       an unvalidated minor could legitimately re-resolve or re-format it, so
  #       the two controls are one mechanism and neither is sufficient alone.
  required_version = "~> 1.15.0"

  required_providers {
    # WHY : Assumptions: `~> 6.56` means `>= 6.56.0, < 7.0.0`; it establishes a
    #       floor inside the supported 6.x major line rather than pinning one
    #       minor series. This constraint is shared with infra/envs/dev and is
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
    #       evidence for production. The adjacent lock file selects AWS provider
    #       6.57.1 with reviewed checksums, and CI's `-lockfile=readonly` prevents
    #       a plan from silently resolving another allowed 6.x release. Provider
    #       upgrades therefore require an explicit lock-file diff.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Alternatives Considered: omitting this entry and letting each child
    #       module carry its own `random` constraint. Rejected. Only two of the
    #       two modules generate values with this provider -- `secrets` for
    #       the database credential and `cognito` for the seed users -- so no
    #       resource in THIS directory uses it, yet a root-level constraint is
    #       the only thing that holds the whole graph to one version of it,
    #       whereas per-child constraints can be satisfied independently and
    #       drift apart. Declaring the requirement with no matching
    #       `provider "random"` block is correct and complete: that provider
    #       takes no configuration, so there is nothing for a block to carry.
    # WHY : Trade-offs: because no resource in this directory references it, a
    #       tflint run that enables `terraform_unused_required_providers` will
    #       flag this entry -- that rule is evaluated per directory and does not
    #       follow child-module usage. The rule sits outside tflint's
    #       recommended preset, and a graph-wide version anchor is worth the
    #       finding; `terraform_unused_declarations` is a separate rule and does
    #       not apply, since it targets unused variables, locals, data sources
    #       and provider ALIASES, and this root declares no alias.
    # WHY : Refactoring Rationale: this root declares the random provider for its
    #       child modules rather than using it directly, so the recursive lint's
    #       unused-required-providers rule reports it here. The rule stays enabled
    #       globally in infra/.tflint.hcl so a genuinely unused declaration in any
    #       module is still reported; only this reviewed, graph-level declaration is
    #       excepted. The identical annotation and reasoning sit in
    #       infra/envs/dev/versions.tf.
    # tflint-ignore: terraform_unused_required_providers
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }

    # WHY : Refactoring Rationale: Lambda deployment packages are assembled
    #       deterministically from repository sources during plan. An external
    #       zip command would add an untracked build step whose bytes Terraform
    #       could not hash into each function's source_code_hash.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }

    # WHY : Refactoring Rationale: the internal API→ALB→task TLS chain needs a
    #       certificate and PEM producer with no committed private key. The TLS
    #       provider generates both into encrypted state during apply.
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.1"
    }
  }
}

# WHY : Assumptions: infra/envs/prod/main.tf must NOT declare a second
#       `provider "aws"` block. Terraform treats two unaliased configurations
#       for the same provider as a duplicate-configuration error at
#       `terraform init`, so a well-meant second block breaks the root outright
#       instead of merging with this one. The note lives at the definition so
#       that a reader about to add a provider in main.tf meets the reason at
#       the source. Every module under infra/modules/ inherits THIS
#       configuration, region and default_tags included, which is exactly why
#       none of them defines its own.
provider "aws" {
  # WHY : Assumptions: the region is supplied by var.aws_region rather than
  #       written as a literal, so this file stays identical between the two
  #       environment roots and the region remains a terraform.tfvars decision.
  #       A literal here would also be the one place a deployment target could
  #       silently disagree with the backend's own region.
  region = var.aws_region

  # WHY : Trade-offs: this is the SOLE tagging mechanism for the production
  #       graph. The alternative -- threading a `tags` variable through every
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
