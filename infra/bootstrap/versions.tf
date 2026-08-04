# =============================================================================
# infra/bootstrap/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain contract for the infra/bootstrap root -- the Terraform
#   CLI floor and the AWS provider version constraint this root is authored
#   and validated against -- and configures the single `provider "aws"`
#   instance that every resource in the root inherits.
#
#   This root is the out-of-band step that provisions the remote-state backend
#   (a versioned, encrypted S3 bucket for state plus a DynamoDB lock table)
#   which every other Terraform root in this repository consumes. It is
#   applied once per AWS account, ahead of both environment roots under
#   infra/envs/, and torn down after them.
#
# Parameters:
#   This file declares no variable of its own. It consumes two that
#   infra/bootstrap/variables.tf declares:
#     var.aws_region - string. The region this provider targets, and therefore
#                      the region the state bucket and lock table are created
#                      in.
#     var.tags       - map(string). The common tag set merged into every
#                      taggable resource in this root through default_tags.
#
# Return values:
#   None. This file declares no output. The root's outputs -- including the
#   region, echoed from the same var.aws_region consumed below -- are declared
#   in infra/bootstrap/outputs.tf.
#
# Errors / Exceptions:
#   `terraform init` in this directory must be run WITHOUT a backend, because
#   no `backend` block is declared here (see the WHY note beside the terraform
#   block). Use `terraform init -backend=false` to initialize for validation;
#   the root's first apply writes its state locally, as terraform.tfstate
#   beside these files. A CLI below the required_version floor, or an AWS
#   provider outside the declared constraint, aborts during `init` with a
#   version-constraint error before any resource is evaluated.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this root declares no `backend` block because it CREATES the
#     bucket and lock table that a `backend "s3"` stanza would have to find
#     already present. Declaring one here would make the root's state depend
#     on resources the root has not yet created.
#   - Trade-offs: provider-level default_tags is the single tagging mechanism
#     for this root, so the common tag map is stated once rather than repeated
#     on every resource; the cost is that those tags are not visible at the
#     resource declaration site in main.tf.
#   - Alternatives Considered: declaring `provider "aws"` at the top of
#     main.tf is equally idiomatic, and was rejected because it separates the
#     provider's version constraint from the provider's configuration across
#     two files.
# =============================================================================

terraform {
  # WHY `~> 1.15.0` rather than an open `>= 1.15.0` floor (Refactoring
  # Rationale): the earlier floor was chosen on the reasoning that nothing here
  # uses a language feature a newer 1.x release removes, and that reasoning is
  # about the CONFIGURATION when the risk is in the TOOLCHAIN. A Terraform minor
  # release is where language behaviour, validation semantics and state-format
  # handling change, so an open floor let this root -- the one that creates the
  # state bucket and lock table every other root depends on -- be applied by a
  # CLI no reviewed plan was ever produced under. The pessimistic operator on the
  # patch component accepts 1.15.0 through 1.15.x, which is the
  # supported-major/minor policy this package is reviewed under, and refuses
  # 1.16.0 as well as 2.x.
  # WHY not an exact `= 1.15.8`. Trade-offs: this root is run by operators and by
  # CI against whatever Terraform their image provides, and a patch release
  # cannot change what this file means; an exact pin would break every runner the
  # moment its toolchain moved forward within the series. It is validated on
  # 1.15.8. Moving to a new minor stays a deliberate edit to this one line.
  # WHY this pairs with the lock file. Assumptions: .terraform.lock.hcl beside
  # this file records which provider version and checksums were selected. A CLI
  # from an unvalidated minor could re-resolve or re-format that lock, so the
  # constraint and the lock are one mechanism and neither suffices alone.
  required_version = "~> 1.15.0"

  required_providers {
    # WHY `~> 6.56` rather than an exact `= 6.56.0` -- Trade-offs: the
    # pessimistic operator on the minor accepts 6.56 and any higher 6.x, so
    # provider patch and minor fixes arrive without editing this file, while
    # refusing 7.0.0 -- the major bump is where resource-schema removals and
    # argument renames land. Verified against the Terraform Registry at
    # 6.56.0.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY `hashicorp/random` is deliberately absent -- Alternatives Considered:
    # that provider belongs to infra/modules/secrets, which generates database
    # and seed-user credentials directly into Secrets Manager. Nothing in this
    # root generates a random value, and infra/.tflint.hcl enables
    # terraform_unused_declarations as a gating check in the infra CI workflow
    # with no return-code tolerance, so an unused provider declaration fails
    # the build rather than merely reading as untidy. Adding it to suffix the
    # state bucket name with a `random_id` was the tempting reason and was
    # rejected: a random suffix makes the bucket name unreproducible, so an
    # operator who lost the local state file could no longer derive the name of
    # the bucket this root had already created.
  }

  # WHY there is deliberately NO `backend` block, which would belong exactly
  # here -- Assumptions: this root is what creates the versioned S3 bucket and
  # the DynamoDB lock table that a `backend "s3"` stanza needs to already
  # exist. Configuring that backend here would point the root's state at
  # resources the root has not created yet, which cannot resolve on a clean
  # account. The root therefore keeps LOCAL state, and CI checks it with
  # `terraform init -backend=false` followed by `terraform validate` -- which
  # is also why this root is verifiable with no AWS credentials at all. The
  # environment roots under infra/envs/ are the consumers of the backend this
  # root produces; adding a backend block here would break the bootstrap.
}

# WHY the sole `provider "aws"` block lives in versions.tf rather than main.tf
# Alternatives Considered: versions.tf answers "which Terraform, which
# provider, and configured how", while main.tf answers "what does this root
# create". Declaring the provider at the top of main.tf is equally idiomatic
# and was rejected because it splits the provider's version constraint from
# the provider's configuration across two files, so a reader checking one has
# to open the other. This is the only provider block in the root -- main.tf
# declares none -- because two unaliased configurations of the same provider
# is a duplicate-configuration error raised at init time.
provider "aws" {
  # WHY the region is variable-driven rather than a literal or
  # `data.aws_region` -- Assumptions: infra/bootstrap/outputs.tf reports the
  # region back so it can be written into each environment root's backend
  # configuration. Resolving both from this one variable makes it impossible
  # for the reported region to disagree with the region the state bucket was
  # actually created in. `data.aws_region` would instead report whatever the
  # ambient credentials resolved to, which is precisely the value that can
  # differ from the operator's intent without anything failing.
  region = var.aws_region

  # WHY tags are applied through the provider rather than on each resource
  # Trade-offs: the provider merges default_tags into every taggable resource
  # it creates, so the common map is declared once instead of being copied
  # onto the bucket, its sub-resources and the lock table; resources in
  # main.tf then carry only their own distinguishing `Name` tag. The accepted
  # cost is locality -- reading a resource in main.tf does not reveal the tags
  # it will actually carry, so this block is where a reader must look.
  default_tags {
    tags = var.tags
  }

  # WHY no credential argument appears in this block -- Assumptions: there is
  # deliberately no access_key, secret_key, profile or assume_role. The
  # provider resolves credentials from the operator's ambient AWS
  # configuration, and in CI from short-lived OIDC role assumption, so no
  # long-lived credential is ever committed to this repository.
}
