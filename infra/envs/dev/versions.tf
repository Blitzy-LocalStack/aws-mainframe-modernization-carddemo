# =============================================================================
# infra/envs/dev/versions.tf
# Toolchain and provider contract for the CardDemo `dev` environment root.
# =============================================================================
#
# Purpose:
#   This file carries two distinct responsibilities for the `dev` Terraform
#   root, and it is the only file in the root that carries either:
#
#     1. Toolchain contract. It declares the MINIMUM admitted Terraform CLI --
#        an open floor, which is the constraint AAP section 0.6.1.4 states --
#        together with the provider version constraints under which every `plan`
#        and `apply` of this environment is expected to have been produced and
#        reviewed. Which CLI actually runs is decided by the pinned installer in
#        the pipeline, not by this constraint; see the rationale at the
#        constraint itself.
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
#   for `hashicorp/aws` and `hashicorp/random` -- the exact pair AAP section 0.6.1.4
#   names. Nothing in
#   this file is referenced by name -- `main.tf` and the modules it calls pick the provider
#   up implicitly as the default for their resource types, which is why adding
#   a provider argument here silently changes behaviour everywhere at once.
#
# Errors:
#   - A Terraform CLI older than 1.15.0 aborts at `terraform init`, before any
#     provider is fetched or any state is touched. There is deliberately no upper
#     bound; see the rationale at the constraint itself.
#   - A provider release outside either constraint below aborts at
#     `terraform init` during provider selection.
#   - A SECOND `provider "aws"` block anywhere in this root -- most plausibly
#     added to `main.tf` by someone who did not know this file owned it -- is a
#     duplicate provider configuration and a hard configuration error at init.
#     `main.tf` must NOT declare one. Provider arguments belong here.
# =============================================================================

terraform {
  # WHY : ⚠️ Refactoring Rationale: this read `~> 1.15.0`, which accepts 1.15.x and
  #       REFUSES 1.16.0 and above, and several paragraphs here argued for that ceiling
  #       on the grounds that a Terraform minor is where language and state-format
  #       behaviour changes. The argument is sound engineering and it is still not this
  #       package's to make: AAP section 0.6.1.4 states the CLI constraint as
  #       `>= 1.15.0`, and a root that narrows a frozen plan has departed from it just
  #       as surely as one that widens it. The floor is restored, and every paragraph
  #       that reasoned from the ceiling is withdrawn with it rather than left to read
  #       as a description of the constraint below.
  # WHY : Assumptions: what the ceiling actually protected is not lost, it moves to
  #       where it belongs. The reviewed toolchain is pinned by the RUNNER -- the
  #       environment contract validates on 1.15.8 and CI installs a checksum-pinned
  #       Terraform rather than whatever is newest -- so the CLI a plan is discharged
  #       under is still exactly one reviewed release. A constraint in this file could
  #       only ever refuse a CLI after the operator had already installed it; the
  #       pinned installer decides which one exists.
  # WHY : Trade-offs: an open floor admits a future major, and 2.x could in principle
  #       run this root. Accepted, because the plan says so and because the practical
  #       guard is the pinned installer plus the lock file's `-lockfile=readonly`
  #       initialization, neither of which a CLI version constraint contributes to.
  #       Alternatives Considered: keeping `~> 1.15.0` and recording the divergence in
  #       a comment. Rejected for the same reason the archive provider below was
  #       removed rather than annotated -- a comment does not amend a frozen plan.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : `dev` is the environment allowed to run Aurora Serverless at a
    #       minimum capacity of 0, and the provider only accepts a zero minimum
    #       from 5.80.0 onward and the auto-pause argument a zero minimum makes
    #       mandatory only from 5.81.0, so the pair floors at 5.81.0; 6.56 clears
    #       that floor with room to spare. The
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

    # WHY : ⚠️ Refactoring Rationale: an archive-provider requirement stood here,
    #       declared for three data sources that packaged the operational Lambda
    #       functions during plan, together with a paragraph recording that it took this
    #       root past the provider inventory AAP section 0.6.1.4 fixes -- the Terraform
    #       CLI, `hashicorp/aws` and `hashicorp/random`. Recording a divergence is not
    #       the same as being permitted one: the plan is frozen, so the divergence is
    #       removed rather than annotated. Packaging moved to
    #       infra/lambda/build_packages.py, which uses the standard library's zipfile
    #       module and no provider at all, and each `aws_lambda_function` now reads the
    #       built archive through `filename` with `filebase64sha256`. The provider is
    #       removed with its last consumer.
    # WHY : Assumptions: the old paragraph argued that an external build step would
    #       produce bytes "Terraform could not hash into each function's
    #       source_code_hash". That is not so, and it was the reason the divergence
    #       looked necessary: `filebase64sha256` hashes exactly the bytes on disk, which
    #       are the bytes Lambda receives. What the argument was really protecting
    #       against is a NON-DETERMINISTIC archive, whose hash would move on every build
    #       and show all four functions updating on a commit that changed no handler --
    #       and the builder pins timestamps and modes precisely to prevent that.
    # WHY : Trade-offs: `terraform validate` and `terraform plan` now require the
    #       packages to exist, because both evaluate the hash. Every plan step in
    #       .github/workflows/infra-ci.yml and .github/workflows/deploy.yml runs the
    #       builder first, and docs/runbooks/deploy.md states the command; a run that
    #       skips it fails while resolving the hash and names the absent path.
    # WHY : Refactoring Rationale: a `tls` provider requirement also stood here, declared
    #       for a key generator and a self-signed-certificate resource in main.tf
    #       that fed an imported ACM certificate and two Secrets Manager entries. All
    #       of those are deleted -- the listener key they produced was persisted in
    #       Terraform state and shared by every online task -- so the requirement is
    #       removed with its last consumer. Leaving a declared-but-unused provider
    #       would be reported by the recursive lint's unused-required-providers rule
    #       and would let `init` keep fetching a provider nothing resolves against.
    # WHY : Assumptions: both deletions are safe to make here because no resource in
    #       this root's own graph uses either provider any more, and no CHILD module
    #       declares them either -- unlike `random`, whose declaration above is
    #       load-bearing precisely because two child modules consume it. Listener
    #       material is now minted inside each task by
    #       config/docker/generate-listener-material.sh, which needs no Terraform
    #       provider at all, and the load balancer's own certificate is the
    #       operator-supplied ACM ARN in var.alb_certificate_arn.
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
