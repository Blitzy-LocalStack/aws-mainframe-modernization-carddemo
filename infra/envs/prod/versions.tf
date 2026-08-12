# =============================================================================
# infra/envs/prod/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the CardDemo PRODUCTION environment
#   root. It fixes the admitted Terraform CLI SERIES -- both a floor and a
#   ceiling, so a CLI from an unreviewed minor is refused rather than merely
#   noted -- constrains every provider this root's module graph is allowed to
#   resolve, and configures the single AWS provider that every child module
#   inherits.
#
#   SOLE OWNER OF `provider "aws"`. This file holds the only AWS provider
#   configuration in the entire production module graph. Every module under
#   infra/modules/ deliberately declares provider REQUIREMENTS without a provider
#   CONFIGURATION, so each inherits the region and the default_tags established
#   here from whichever environment root calls it, which is what lets one module
#   tree serve both `dev` and `prod` unchanged. It consumes exactly two inputs,
#   var.aws_region and var.tags, both declared in infra/envs/prod/variables.tf,
#   and reads no other variable, local, data source or module output.
#
#   Assumptions: this root is structurally identical to infra/envs/dev by design,
#   so the `terraform` block and every provider constraint is the same in both.
#   Production differs from development only in sizing and retention values, and
#   every one of those lives in terraform.tfvars rather than here. A reader
#   tempted to narrow a constraint "because production does not need it" should
#   read the per-pin rationale below first: at least one of these floors is set
#   by a requirement this root does not itself exercise.
#
#   Deliberately NOT declared here:
#     - no `backend` block          -- infra/envs/prod/backend.tf owns the state
#     - no second `provider "aws"`  -- a duplicate is an init-time error
#     - no `provider "random"` body -- that provider takes no configuration
#     - no provider alias           -- single-region deployment
#     - no variable, output, resource, module, locals or data block
#
#   - Assumptions: this root is structurally identical to infra/envs/dev by
#     design, so the `terraform` block and all three provider constraints are the
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
  # Refactoring Rationale: this line previously carried the open floor,
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
  # Assumptions: `~> 1.15.0` is a strict SUBSET of the `>= 1.15.0` the
  #       dependency inventory records, so narrowing it here honours that floor
  #       rather than departing from it -- every CLI this root now admits also
  #       satisfies the inventory constraint.
  # Assumptions: the committed .terraform.lock.hcl beside this file does
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
  # Trade-offs: a ceiling rejects a runner whose toolchain image has moved
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
    # Assumptions: `~> 6.56` establishes a floor inside the supported 6.x major
    #   line rather than pinning one minor series, and its floor is set by a
    #   requirement THIS root never exercises -- an Aurora Serverless v2 minimum
    #   capacity of zero needs provider 5.80.0 or later and the auto-pause
    #   argument it makes mandatory needs 5.81.0, flooring the pair at 5.81.0,
    #   while the production
    #   Aurora minimum is held above zero in terraform.tfvars, so scale-to-zero
    #   and its resume latency are a development-only affordance. The pin is
    #   deliberately not narrowed for this root anyway, because the floor belongs
    #   to the shared infra/modules/aurora-postgresql contract rather than to
    #   either caller: were production to constrain the provider differently from
    #   development, one module source could resolve two different provider
    #   versions and a plan reviewed against development would stop being evidence
    #   for production.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # Alternatives Considered: omitting this entry and letting each child module
    #   carry its own `random` constraint. Rejected: no resource in THIS directory
    #   uses the provider -- only the `secrets` and `cognito` modules generate
    #   values with it -- yet a root-level constraint is the only thing that holds
    #   the whole graph to one version of it, whereas per-child constraints can be
    #   satisfied independently and drift apart. Declaring the requirement with no
    #   matching `provider "random"` block is correct and complete, because that
    #   provider takes no configuration.
    # Trade-offs: because no resource here references it, tflint's
    #   `terraform_unused_required_providers` rule reports this entry -- the rule
    #   is evaluated per directory and does not follow child-module usage. It stays
    #   enabled globally in infra/.tflint.hcl so a genuinely unused declaration in
    #   any module is still reported, and only this reviewed, graph-level
    #   declaration is excepted, with the identical annotation in
    #   infra/envs/dev/versions.tf.
    # tflint-ignore: terraform_unused_required_providers
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }

    # Assumptions: this entry and the `tls` entry below take this root past the
    #   two providers the package's dependency inventory names, and the divergence
    #   is recorded rather than removed because both are CONSUMED by main.tf.
    #   `data "archive_file"` packages the Lambda functions the batch state
    #   machine's quiesce, analyze and resume states invoke, and
    #   `aws_lambda_function` accepts only a `filename`, an S3 object or a
    #   container image -- there is no inline source form. Packages are assembled
    #   deterministically from repository sources during plan; an external zip
    #   command would add an untracked build step whose bytes Terraform could not
    #   hash into each function's source_code_hash. The only ways to drop this
    #   provider would be to commit a binary zip, hiding reviewed source behind an
    #   opaque artifact, or to delete the functions, which would delete three
    #   batch states.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }

    # Refactoring Rationale: a `tls` provider requirement stood here, declared
    #       for a key generator and a self-signed-certificate resource in main.tf
    #       that fed an imported ACM certificate and two Secrets Manager entries. All
    #       of those are deleted -- the listener key they produced was persisted in
    #       Terraform state and shared by every online task -- so the requirement is
    #       removed with its last consumer. Leaving a declared-but-unused provider
    #       would be reported by the recursive lint's unused-required-providers rule
    #       and would let `init` keep fetching a provider nothing resolves against.
    # Assumptions: the deletion is safe to make here because no resource in
    #       this root's own graph uses the provider any more, and no CHILD module
    #       declares it either -- unlike `random`, whose declaration above is
    #       load-bearing precisely because two child modules consume it. Listener
    #       material is now minted inside each task by
    #       config/docker/generate-listener-material.sh, which needs no Terraform
    #       provider at all, and the load balancer's own certificate is the
    #       operator-supplied ACM ARN in var.alb_certificate_arn.
  }
}

# Assumptions: infra/envs/prod/main.tf must NOT declare a second `provider "aws"`
#   block. Terraform treats two unaliased configurations for the same provider as
#   a duplicate-configuration error at `terraform init`, so a well-meant second
#   block breaks the root outright instead of merging with this one. Every module
#   under infra/modules/ inherits THIS configuration, region and default_tags
#   included, which is exactly why none of them defines its own.
provider "aws" {
  # Assumptions: the region is supplied by var.aws_region rather than written as
  #   a literal, so this file stays identical between the two environment roots
  #   and the region remains a terraform.tfvars decision. A literal here would
  #   also be the one place a deployment target could silently disagree with the
  #   backend's own region.
  region = var.aws_region

  # Trade-offs: this is the SOLE tagging mechanism for the production graph. The
  #   alternative -- threading a `tags` variable through every module and merging
  #   it into a `tags` argument on every resource -- was rejected because it makes
  #   correct tagging depend on every module author remembering to wire it, and a
  #   single omission yields an untagged resource that no plan review surfaces.
  #   Provider-level default_tags cannot be forgotten: a module would have to opt
  #   out to escape it. The accepted cost is indirection, since the tag values are
  #   no longer visible beside the resources that carry them. The migrated
  #   baseline made the same trade: every `DEFINE` in app/csd/CARDDEMO.CSD carries
  #   `GROUP(CARDDEMO)`, so no CICS resource could be defined outside the group
  #   that owns it.
  default_tags {
    tags = var.tags
  }
}
