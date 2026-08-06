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
  # WHY : Refactoring Rationale: this line briefly carried `~> 1.15.0`, on the
  #       reasoning that a Terraform MINOR release is where language behaviour and
  #       state handling change, so a ceiling kept a reviewed plan from being
  #       discharged by an unvalidated toolchain. It has been returned to the
  #       assigned `>= 1.15.0`. The ceiling was the only place in the whole infra/
  #       tree where a version constraint disagreed with its siblings: the
  #       bootstrap root and all sixteen modules under infra/modules/ declare
  #       `>= 1.15.0`, and a module cannot be initialised except through a root, so
  #       a root that refused 1.16 while every module accepted it made the tree
  #       self-inconsistent and made the two environment roots differ from each
  #       other in the one file the package requires to be structurally identical.
  # WHY : Trade-offs: a floor accepts a CLI newer than the 1.15.8 this package is
  #       validated on. That is the accepted cost of a shared contract, and it is
  #       the right side to err on here, because the CLI is supplied by the
  #       operator or the CI runner rather than resolved from a registry -- a
  #       ceiling rejects a runner whose toolchain image moved forward, which is a
  #       failure the package cannot fix from inside itself. Provider selection is
  #       constrained the opposite way, and that asymmetry is deliberate.
  # WHY : Assumptions: the guard against an unvalidated toolchain is the committed
  #       .terraform.lock.hcl beside this file, not the CLI constraint. The lock
  #       records the exact provider versions and checksums a reviewed plan was
  #       produced under, and CI runs with `-lockfile=readonly`, so a newer CLI
  #       cannot silently re-resolve a provider -- it can only fail, visibly, on a
  #       lock it is not allowed to rewrite.
  required_version = ">= 1.15.0"

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
    # WHY : Assumptions: this entry and the `tls` entry below take this root past
    #       the two providers -- `hashicorp/aws` and `hashicorp/random` -- that the
    #       package's dependency inventory names, and the divergence is recorded
    #       here rather than removed. Both are CONSUMED by main.tf, so the
    #       declarations are not orphans: `data "archive_file"` packages the four
    #       Lambda functions the batch state machine's quiesce, analyze and resume
    #       states invoke, and `aws_lambda_function` accepts only a `filename`, an
    #       S3 object or a container image -- there is no inline source form. The
    #       only ways to drop this provider would be to commit a binary zip, which
    #       hides reviewed source behind an opaque artifact, or to delete the
    #       functions, which would delete three of the eleven batch states.
    #       Trade-offs: because the provider IS used here, deleting the declaration
    #       while keeping the resources is not the smaller change it looks like --
    #       tflint's `terraform_required_providers` rule requires a version
    #       constraint for every provider a directory actually uses, so an
    #       undeclared-but-used provider fails the gating lint run AND lets `init`
    #       resolve an arbitrary major.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }

    # WHY : Refactoring Rationale: the internal API-to-ALB-to-task TLS chain needs
    #       a certificate and a PEM producer with no committed private key. This
    #       provider generates both during apply, which is what let two required
    #       PEM input variables be removed from this root: material that is
    #       generated has no variable to arrive through and therefore no tfvars
    #       file to be committed in.
    # WHY : Assumptions: like `archive` above, this entry exceeds the two providers
    #       the package's dependency inventory names, and is kept for the same
    #       reason -- it is consumed. `tls_private_key` and `tls_self_signed_cert`
    #       in main.tf feed `aws_acm_certificate` for the internal HTTPS listener
    #       and the two root-owned Secrets Manager entries the tasks read their
    #       listener material from. Dropping the provider would mean requiring an
    #       operator-supplied certificate ARN, which contradicts the acceptance
    #       criterion that the stack deploy end to end from the provided IaC with
    #       no manual dependency.
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
