# =============================================================================
# infra/modules/cloudfront-spa/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain and provider contract for the `cloudfront-spa`
#   Terraform module: the minimum Terraform CLI version this module's HCL is
#   written against, and the AWS provider constraint through which every
#   resource in the module resolves. The module's other files (main.tf,
#   variables.tf, outputs.tf) are planned and validated against the constraints
#   declared here, which is why this file itself declares no infrastructure.
#
#   The module provisions the delivery path for the CardDemo single-page
#   application: a private S3 origin bucket holding the built SPA assets, plus a
#   CloudFront distribution that reaches that bucket through an origin access
#   control and routes error responses back to the SPA entry point so
#   client-side deep links resolve.
#
#   What it replaces: the 3270 datastream delivery of the CardDemo BMS
#   presentation layer. That layer is 21 BMS mapsets in total -- 17 base mapsets
#   under app/bms, carrying 902 DFHMDF field definitions between them, plus 4
#   mapsets in the two extension trees -- and every map is a fixed 24x80
#   character screen, as app/bms/COSGN00.bms:L26-L28 declares with
#   `COSGN0A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)`. A browser SPA served from
#   CloudFront over a private S3 origin supersedes those screens; the terminal
#   datastream has no cloud analogue and is not emulated.
#
# Parameters:
#   None. This file declares no `variable` block, and that is a decision rather
#   than an omission: every input this module accepts is declared in
#   infra/modules/cloudfront-spa/variables.tf. A reader who finds no parameters
#   here has found the whole truth, not a gap.
#
# Return values:
#   None. This file declares no `output` block. It declares constraints, which
#   Terraform consumes at init time rather than returning to a caller; every
#   value this module returns is declared in
#   infra/modules/cloudfront-spa/outputs.tf.
#
# Errors / failure modes:
#   - An operator or CI runner whose Terraform CLI is older than 1.15.0 is
#     rejected at `terraform init` in the calling root, before any plan is
#     produced, by an unsupported-Terraform-version error naming this
#     constraint.
#   - A provider resolution that cannot satisfy `~> 6.56` -- because a lock file
#     pins an incompatible AWS provider, or because another module in the same
#     root declares a disjoint constraint -- is rejected at `terraform init` by
#     a no-available-provider-version error.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: these constraints sit in their own versions.tf
#     instead of at the top of main.tf so that the module's README.md
#     Requirements table is generated from one predictable file, and so a
#     constraint change reviews as a one-file diff rather than hiding inside a
#     resource change.
#   - Assumptions: HCL has no docstring construct, so this header block IS the
#     entry-point docstring for this file. Rationale for each individual
#     argument is carried adjacent to that argument below rather than duplicated
#     up here, so a reader editing a constraint sees why it is what it is
#     without scrolling.
# =============================================================================

terraform {
  # WHY : Alternatives Considered: an exact `= 1.15.8` pin -- 1.15.8 being the
  #       version this package was validated on -- was rejected because it
  #       rejects every operator and CI runner already on a newer 1.15.x patch
  #       while buying no compatibility guarantee the floor does not already
  #       give. A floor guarantees the language features this module's HCL
  #       relies on and leaves patch upgrades unblocked.
  #       Trade-offs: a floor cannot protect against a breaking change in a new
  #       Terraform minor. That risk is accepted here and controlled elsewhere:
  #       the infra CI workflow pins the CLI version it runs, so drift is caught
  #       by a failing pipeline rather than by widening this constraint.
  required_version = ">= 1.15.0"

  required_providers {
    # WHAT: resolve the AWS provider from the public registry, constrained to
    #       the 6.56-or-newer 6.x series.
    # WHY : Assumptions: `hashicorp/aws` resolves from the public Terraform
    #       Registry. This package declares no private registry, network mirror
    #       or credentialed provider source, so the bare `hashicorp/` namespace
    #       is unambiguous.
    #       Assumptions: 6.56 is a capability floor, not a preference. A
    #       5.81.0-or-newer provider is required for the zero-minimum Aurora
    #       serverless capacity this package's data tier uses, and 6.56 clears
    #       that comfortably. Holding every module in this package on the
    #       identical constraint is what lets a single root resolve one shared
    #       .terraform.lock.hcl across all of them; a module that disagreed
    #       would make the root's constraint set unsatisfiable at init.
    #       Alternatives Considered: a bare `>= 6.56` was rejected because it
    #       admits the 7.x major, and AWS-provider majors carry breaking
    #       resource-schema changes that would surface as a plan-time failure in
    #       whichever root upgraded first instead of as a reviewed change. `~>`
    #       lets only the rightmost stated component float, so with two segments
    #       this resolves to >= 6.56.0 and < 7.0.0: 6.x patch and minor releases
    #       are admitted, the next major is not.
    #       Trade-offs: admitting 6.x minors does mean a minor release can change
    #       resource behaviour without a constraint edit. That is accepted
    #       because the per-root .terraform.lock.hcl is what actually freezes the
    #       resolved provider build, and it moves only under an explicit
    #       `terraform init -upgrade` that a reviewer sees as a lock-file diff.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}

# =============================================================================
# Deliberate omissions
# -----------------------------------------------------------------------------
# Every block named below is absent on purpose. Each one is a reasonable thing
# to expect in a *.tf file, so each is recorded as a decision rather than left
# to read as an oversight.
#
# No `provider` block -- not configured, not aliased, not empty:
#   Assumptions: this directory is a module, not a Terraform root. It is
#   instantiated by infra/envs/dev and infra/envs/prod and inherits their
#   provider configuration, region and default tagging included.
#   Alternatives Considered: configuring an aws provider here was rejected
#   because a module carrying its own provider configuration cannot be
#   instantiated by two roots that differ in region, and it breaks the provider
#   inheritance those roots depend on.
#
# No `backend` block:
#   Assumptions: only Terraform roots have backends. State for this module lives
#   in the calling root's backend: the backend.tf of each environment root
#   targets the versioned S3 bucket and DynamoDB lock table that
#   infra/bootstrap provisions, and infra/bootstrap itself carries no backend
#   because it is what creates them.
#   Consequence worth knowing when validating: this module is never
#   initialised or applied on its own. It is checked transitively, when a
#   calling root initialises, and the infra CI workflow validates those roots
#   with `init -backend=false` so no credential and no live state are needed to
#   verify the HCL.
#
# No `hashicorp/random` provider:
#   Alternatives Considered: this package does use hashicorp/random, but only
#   where a value must be generated -- the seed-user and database passwords
#   owned by the `secrets` and `cognito` modules. This module creates a bucket,
#   an origin access control and a distribution, and generates no value of its
#   own, so declaring the provider was rejected.
#   Trade-offs: declaring it anyway would cost nothing at apply time but would
#   trip tflint's `terraform_unused_declarations` rule, which is a gating check
#   in the infra CI workflow. The omission is therefore enforced by the
#   pipeline, not merely tidy.
#
# No `tags` variable and no `default_tags` block:
#   Alternatives Considered: a per-module tagging input was rejected because the
#   calling root's provider `default_tags` block is the single tagging mechanism
#   for this package, and a second source of tags here would let the two
#   disagree on the same resource.
# =============================================================================
