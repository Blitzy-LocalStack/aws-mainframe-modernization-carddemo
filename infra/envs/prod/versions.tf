# =============================================================================
# infra/envs/prod/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the CardDemo PRODUCTION environment
#   root. It states the MINIMUM admitted Terraform CLI -- an open floor, which is
#   the constraint AAP section 0.6.1.4 fixes; which CLI actually runs is decided by
#   the pipeline's pinned installer -- constrains every provider this root's module
#   graph is allowed to resolve, and configures the single AWS provider that every
#   child module inherits.
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
  # ⚠️ Refactoring Rationale: this read `~> 1.15.0`, which accepts 1.15.x and REFUSES
  #   1.16.0 and above, and the paragraphs here argued for that ceiling at length --
  #   including a reply to earlier reviewer guidance. The engineering argument is sound
  #   and it is still not this package's to make: AAP section 0.6.1.4 states the CLI
  #   constraint as `>= 1.15.0`, and a root that NARROWS a frozen plan has departed
  #   from it just as surely as one that widens it. The floor is restored and every
  #   paragraph reasoning from the ceiling is withdrawn with it, rather than left to
  #   read as a description of the constraint below.
  # Assumptions: what the ceiling protected is not lost, it moves to where it belongs.
  #   The reviewed toolchain is pinned by the RUNNER -- CI installs a checksum-pinned
  #   Terraform rather than whatever is newest, and the environment contract validates
  #   on 1.15.8 -- so the CLI a production plan is discharged under is still exactly
  #   one reviewed release. A constraint in this file could only refuse a CLI after the
  #   operator had installed it; the pinned installer decides which one exists.
  # Trade-offs: an open floor admits a future major. Accepted, because the plan says so
  #   and because the practical guards are the pinned installer and
  #   `-lockfile=readonly` initialization, to neither of which a CLI constraint
  #   contributes. Alternatives Considered: keeping the ceiling and recording the
  #   divergence in a comment -- rejected for the same reason the archive provider
  #   below was removed rather than annotated, that a comment does not amend a frozen
  #   plan. Both environment roots and the bootstrap root now state the AAP floor.
  required_version = ">= 1.15.0"

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

    # ⚠️ Refactoring Rationale: an archive-provider requirement stood here, declared
    #   for the data sources that packaged the operational Lambda functions during plan,
    #   alongside a paragraph recording that it took this root past the provider
    #   inventory AAP section 0.6.1.4 fixes -- the Terraform CLI, `hashicorp/aws` and
    #   `hashicorp/random`. Recording a divergence is not the same as being permitted
    #   one: the plan is frozen, so the divergence is removed rather than annotated.
    #   Packaging moved to infra/lambda/build_packages.py, which uses the standard
    #   library's zipfile module and no provider at all, and each `aws_lambda_function`
    #   now reads the built archive through `filename` with `filebase64sha256`.
    # Assumptions: the old paragraph argued an external build step would produce bytes
    #   "Terraform could not hash into each function's source_code_hash". That is not
    #   so, and it is why the divergence looked necessary: `filebase64sha256` hashes
    #   exactly the bytes on disk, which are the bytes Lambda receives. The real risk
    #   was a NON-DETERMINISTIC archive whose hash moved on every build; the builder
    #   pins timestamps and modes to prevent it.
    # Trade-offs: `terraform validate` and `terraform plan` now require the packages to
    #   exist, because both evaluate the hash. Both pipeline plan steps and
    #   docs/runbooks/deploy.md run the builder first, and a run that skips it fails
    #   while resolving the hash and names the absent path.
    # Refactoring Rationale: a `tls` provider requirement also stood here, declared
    #       for a key generator and a self-signed-certificate resource in main.tf
    #       that fed an imported ACM certificate and two Secrets Manager entries. All
    #       of those are deleted -- the listener key they produced was persisted in
    #       Terraform state and shared by every online task -- so the requirement is
    #       removed with its last consumer. Leaving a declared-but-unused provider
    #       would be reported by the recursive lint's unused-required-providers rule
    #       and would let `init` keep fetching a provider nothing resolves against.
    # Assumptions: both deletions are safe to make here because no resource in
    #       this root's own graph uses either provider any more, and no CHILD module
    #       declares them either -- unlike `random`, whose declaration above is
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
