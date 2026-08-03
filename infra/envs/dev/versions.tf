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
#        of the sixteen modules under `infra/modules/` declares its own provider
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
#   for `hashicorp/aws` and `hashicorp/random`. Nothing in this file is
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
  # WHAT: the oldest Terraform CLI release this root accepts.
  # WHY : Assumption: both CI (`.github/workflows/infra-ci.yml` and
  #       `deploy.yml`) and the operator run 1.15.8, so 1.15.8 is the release a
  #       reviewed plan for this root was actually produced under. The
  #       constraint is a floor rather than an exact `=` pin because pinning one
  #       patch release would break every runner the moment its toolchain image
  #       moved forward, while the floor still rejects a CLI too old to parse
  #       this configuration at all.
  required_version = ">= 1.15.0"

  required_providers {
    # WHAT: the AWS provider, held inside the 6.x major line.
    # WHY : `dev` is the environment allowed to run Aurora Serverless at a
    #       minimum capacity of 0, and the provider only accepts a zero minimum
    #       from 5.81.0 onward; 6.56 clears that floor with room to spare. The
    #       floor is the entire reason this constraint is not looser.
    #       Alternatives Considered: an exact `= 6.56.0` pin, rejected because
    #       it blocks provider patch releases while buying nothing this root
    #       needs; and a bare `>= 5.81`, rejected because it has no upper bound
    #       and so would admit a 7.x major whose resource-schema changes would
    #       land unreviewed across all sixteen modules simultaneously. The `~>`
    #       operator accepts patch and minor releases within 6.x and stops
    #       short of 7.0, which is the behaviour wanted here.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHAT: the random provider, declared for this root's MODULE GRAPH rather
    #       than for any resource in this root's own configuration.
    # WHY : Assumption: the `secrets` and `cognito` child modules are the two
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
  }
}

# WHAT: the single, unaliased AWS provider configuration inherited by this root
#       and by every module it calls.
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

  # WHAT: the sole tagging mechanism for this root.
  # WHY : Trade-off: every resource created here and inside all sixteen modules
  #       inherits these tags without carrying a `tags` argument of its own, so
  #       a reader of `main.tf` sees no tags anywhere and has to know to look in
  #       this file to find them. That opacity is accepted because the
  #       alternative -- repeating a tag map at each of the sixteen module call
  #       sites -- drifts the first time one call site is edited and the others
  #       are not. Each module additionally accepts its own `tags` input that it
  #       merges into its taggable resources, so the two mechanisms compose:
  #       this block supplies the environment-wide keys, the module input
  #       supplies the per-module ones.
  default_tags {
    tags = var.tags
  }
}
