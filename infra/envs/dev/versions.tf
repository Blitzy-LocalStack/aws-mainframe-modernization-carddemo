# =============================================================================
# infra/envs/dev/versions.tf
# Toolchain and provider contract for the CardDemo `dev` environment root.
# =============================================================================
#
# Purpose:
#   This file carries two distinct responsibilities for the `dev` Terraform
#   root, and it is the only file in the root that carries either:
#
#     1. Toolchain contract. It declares the minimum Terraform CLI release and
#        the provider version constraints under which every `plan` and `apply`
#        of this environment is expected to have been produced and reviewed.
#
#     2. Sole ownership of `provider "aws"`. This is the ONE place in this
#        root's entire module graph where the AWS provider is configured. Each
#        module under `infra/modules/` declares its own provider
#        version constraints but deliberately omits a `provider "aws"` block
#        body, so every one of them inherits the region and the default tags
#        configured here from whichever environment root calls it --
#        `infra/envs/dev` or `infra/envs/prod`.
#
#   Deliberately NOT here, even though a `backend` block is syntactically part
#   of the same `terraform` block this file opens: remote-state configuration
#   lives in `infra/envs/dev/backend.tf`. Splitting it out keeps the one file an
#   operator edits when the state location moves separate from the one that
#   pins the toolchain, so a backend change cannot disturb a version pin or the
#   provider configuration by accident.
#
# Consumes (both declared in `infra/envs/dev/variables.tf` and valued in
# `infra/envs/dev/terraform.tfvars`; neither is declared here):
#
#   var.aws_region  (string)      The single AWS region this environment is
#                                 provisioned into. Supplied to the provider's
#                                 `region` argument below.
#   var.tags        (map(string)) Common tag keys and values stamped onto every
#                                 taggable resource in this root and in every
#                                 module it calls, by way of `default_tags`.
#
# Provides:
#   One configured, unaliased default AWS provider plus a resolved provider set
#   for `hashicorp/aws`, `hashicorp/random`, `hashicorp/archive` and
#   `hashicorp/tls`. Nothing in this file is
#   referenced by name -- `main.tf` and the modules it calls pick the provider
#   up implicitly as the default for their resource types, which is why adding
#   a provider argument here silently changes behaviour everywhere at once.
#
# Errors:
#   - A Terraform CLI older than the `required_version` floor aborts at
#     `terraform init`, before any provider is fetched or any state is touched.
#   - A provider release outside either constraint below aborts at
#     `terraform init` during provider selection.
#   - A SECOND `provider "aws"` block anywhere in this root -- most plausibly
#     added to `main.tf` by someone who did not know this file owned it -- is a
#     duplicate provider configuration and a hard configuration error at init.
#     `main.tf` must NOT declare one. Provider arguments belong here.
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
    # WHY : `dev` is the environment allowed to run Aurora Serverless at a
    #       minimum capacity of 0, and the provider only accepts a zero minimum
    #       from 5.81.0 onward; 6.56 clears that floor with room to spare. The
    #       floor is the entire reason this constraint is not looser.
    #       Alternatives Considered: an exact `= 6.56.0` pin, rejected because
    #       it blocks provider patch releases while buying nothing this root
    #       needs; and a bare `>= 5.81`, rejected because it has no upper bound
    #       and so would admit a 7.x major whose resource-schema changes would
    #       land unreviewed across every module simultaneously. The `~>`
    #       operator accepts patch and minor releases within 6.x and stops
    #       short of 7.0, which is the behaviour wanted here.
    # WHY : Assumptions: the adjacent lock file currently selects 6.57.1 and
    #       records its checksums. CI initializes with `-lockfile=readonly`, so
    #       moving to another allowed 6.x release requires a reviewed lock-file
    #       diff rather than happening implicitly during validation.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Assumptions: the `secrets` and `cognito` child modules are the two
    #       consumers. Each generates a value at apply time and writes it
    #       straight into Secrets Manager, which is the mechanism that keeps
    #       generated database and seed-user credentials out of source control
    #       structurally rather than by reviewer vigilance. Declaring the
    #       constraint at the root lets Terraform intersect it with those
    #       modules' own constraints and select ONE satisfying release for the
    #       whole graph instead of resolving each module independently.
    #       Alternatives Considered: omitting the declaration, which is the
    #       correct shape for `infra/bootstrap` and `infra/modules/network`
    #       because nothing in either of those generates a random value.
    #       Rejected here, because the declaration is load-bearing: without it
    #       each module resolves `random` independently. Do not "clean this
    #       up" after reading one of those two files.
    #       Two tflint rules are easily confused here, so both are named.
    #       `terraform_unused_declarations` does NOT apply -- it covers
    #       variables, locals and data sources, not providers.
    #       `terraform_unused_required_providers` DOES, and it reports this
    #       declaration wherever it is enabled (it sits in tflint's `all`
    #       preset, not in `recommended`): that rule inspects only the module
    #       it is reading, and every `random_*` resource lives one level down.
    #       The finding therefore persists even after `main.tf` calls those
    #       modules rather than clearing itself, which is why the annotation
    #       below silences that single rule at this single declaration and
    #       nothing else.
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

# WHY : credentials are conspicuously absent. Deployment authenticates by
#       short-lived OIDC federated role assumption in
#       `.github/workflows/deploy.yml`, and an operator running this root by
#       hand supplies credentials through the ambient environment, so the
#       provider resolves them at run time from outside the repository. Nothing
#       resembling a static credential is committed here, which is what makes
#       the project's "no secrets in the repository" constraint hold by
#       construction.
#       Alternatives Considered: a second aliased provider for a peer region,
#       rejected outright. This architecture is single-region across three
#       availability zones, and an aliased provider is precisely the mechanism
#       by which that boundary would quietly widen into a multi-region topology
#       nobody chose.
provider "aws" {
  region = var.aws_region

  # WHY : Trade-offs: every resource created here and inside every called module
  #       inherits these tags without carrying a `tags` argument of its own, so
  #       a reader of `main.tf` sees no tags anywhere and has to know to look in
  #       this file to find them. That opacity is accepted because the
  #       alternative -- repeating a tag map at each module call
  #       sites -- drifts the first time one call site is edited and the others
  #       are not. Each module additionally accepts its own `tags` input that it
  #       merges into its taggable resources, so the two mechanisms compose:
  #       this block supplies the environment-wide keys, the module input
  #       supplies the per-module ones.
  default_tags {
    tags = var.tags
  }
}
