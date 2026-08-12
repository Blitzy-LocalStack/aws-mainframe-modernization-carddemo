# =============================================================================
# infra/envs/dev/versions.tf
# Toolchain and provider contract for the CardDemo `dev` environment root.
# =============================================================================
#
# Purpose:
#   This file carries two distinct responsibilities for the `dev` Terraform
#   root, and it is the only file in the root that carries either:
#
#     1. Toolchain contract. It declares the admitted Terraform CLI SERIES --
#        a pessimistic constraint with both a floor and a ceiling, not an open
#        minimum -- together with the provider version constraints under which
#        every `plan` and `apply` of this environment is expected to have been
#        produced and reviewed.
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
#   for `hashicorp/aws`, `hashicorp/random` and `hashicorp/archive`. Nothing in
#   this file is referenced by name -- `main.tf` and the modules it calls pick the provider
#   up implicitly as the default for their resource types, which is why adding
#   a provider argument here silently changes behaviour everywhere at once.
#
# Errors:
#   - A Terraform CLI outside the `required_version` series -- older than
#     1.15.0 OR 1.16.0 and newer -- aborts at `terraform init`, before any
#     provider is fetched or any state is touched. Both directions are
#     deliberate; see the rationale at the constraint itself.
#   - A provider release outside either constraint below aborts at
#     `terraform init` during provider selection.
#   - A SECOND `provider "aws"` block anywhere in this root -- most plausibly
#     added to `main.tf` by someone who did not know this file owned it -- is a
#     duplicate provider configuration and a hard configuration error at init.
#     `main.tf` must NOT declare one. Provider arguments belong here.
# =============================================================================

terraform {
  # Refactoring Rationale: `~> 1.15.0` replaces an open `>= 1.15.0` floor.
  #       The pessimistic operator on the patch component accepts
  #       1.15.0 through 1.15.x and refuses 1.16.0 as well as 2.x. That is the
  #       toolchain this package is actually reviewed under: it is validated on
  #       1.15.8, and the environment contract states the constraint in those
  #       terms and forbids installing 1.16 or 1.17. A Terraform MINOR release
  #       is where language behaviour, validation semantics and state-format
  #       handling change, so an open floor let a reviewed plan be discharged --
  #       and this environment's state be rewritten -- by a CLI no plan was ever
  #       produced under. `infra/bootstrap/versions.tf` already carries `~>
  #       1.15.0` for exactly this reason; the two environment roots now agree
  #       with it instead of contradicting it.
  # WHY : Refactoring Rationale: this line previously carried the open floor,
  #       argued on the grounds that a root ceiling "made the tree
  #       self-inconsistent" because the sixteen modules under infra/modules/
  #       declare `>= 1.15.0`. That argument is WITHDRAWN, because it describes
  #       constraint intersection as a conflict when intersection is precisely
  #       how Terraform combines version constraints: a root at `~> 1.15.0`
  #       calling modules at `>= 1.15.0` yields one effective constraint,
  #       `~> 1.15.0`, with no disagreement to resolve. The asymmetry is
  #       deliberate and load-bearing rather than accidental. A root directory is
  #       the only thing an operator or CI ever runs `init`, `plan` and `apply`
  #       against, so it is the only place a toolchain ceiling can be enforced at
  #       the moment state is written; a shared module is reached only through a
  #       root, so a floor there keeps one module tree reusable by any caller
  #       that satisfies its own ceiling. Reviewer guidance on this finding
  #       states the same division explicitly: environment roots take the
  #       pessimistic constraint, shared modules may retain a minimum floor.
  # WHY : Assumptions: `~> 1.15.0` is a strict SUBSET of the `>= 1.15.0` the
  #       dependency inventory records, so narrowing it here honours that floor
  #       rather than departing from it -- every CLI this root now admits also
  #       satisfies the inventory constraint.
  # WHY : Assumptions: the committed .terraform.lock.hcl beside this file does
  #       NOT guard the toolchain, and an earlier note here claiming it did is
  #       WITHDRAWN as false. Terraform's dependency lock file locks PROVIDERS
  #       and nothing else: this root's lock contains three `provider` blocks
  #       (aws, random, archive) with their versions and checksums, and no CLI
  #       version entry of any kind, so no CLI release can be admitted or refused
  #       by it. The count is three rather than four because the `tls` provider was
  #       removed with its last consumer -- see the note at required_providers. The two mechanisms are complementary and neither
  #       suffices alone -- the constraint below decides which CLI may run, the
  #       lock plus CI's `-lockfile=readonly` decides which provider builds that
  #       CLI may resolve. `infra/bootstrap/versions.tf` states the same pairing.
  # WHY : Trade-offs: a ceiling rejects a runner whose toolchain image has moved
  #       forward to 1.16, and the package cannot fix that from inside itself --
  #       the CLI is supplied by the operator or the CI runner rather than
  #       resolved from a registry. That refusal is the intended behaviour here,
  #       not a defect: a runner that silently moved a minor is exactly the case
  #       the constraint exists to catch, and the remedy is to pin the runner's
  #       Terraform to the reviewed series. Adopting a new minor stays a
  #       deliberate, reviewable edit to this one line in three files -- both
  #       environment roots and the bootstrap root.
  required_version = "~> 1.15.0"

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

    # WHY : Refactoring Rationale: a `tls` provider requirement stood here, declared
    #       for a key generator and a self-signed-certificate resource in main.tf
    #       that fed an imported ACM certificate and two Secrets Manager entries. All
    #       of those are deleted -- the listener key they produced was persisted in
    #       Terraform state and shared by every online task -- so the requirement is
    #       removed with its last consumer. Leaving a declared-but-unused provider
    #       would be reported by the recursive lint's unused-required-providers rule
    #       and would let `init` keep fetching a provider nothing resolves against.
    # WHY : Assumptions: the deletion is safe to make here because no resource in
    #       this root's own graph uses the provider any more, and no CHILD module
    #       declares it either -- unlike `random`, whose declaration above is
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
