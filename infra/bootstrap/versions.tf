# =============================================================================
# infra/bootstrap/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain contract for the infra/bootstrap root -- the Terraform
#   CLI constraint and the AWS provider constraint this root is authored and
#   validated against -- and configures the single `provider "aws"` instance
#   every resource in the root inherits.
#
#   This root is the out-of-band step that provisions the remote-state backend (a
#   versioned, encrypted S3 bucket for state plus a DynamoDB lock table) which
#   every other Terraform root in this repository consumes. It is applied once
#   per AWS account, ahead of both environment roots, and torn down after them.
#
# Parameters:
#   This file declares no variable of its own. It consumes two that
#   infra/bootstrap/variables.tf declares:
#     var.aws_region - string. The region this provider targets, and therefore
#                      the region the state bucket and lock table are created in.
#     var.tags       - map(string). The common tag set merged into every taggable
#                      resource in this root through default_tags.
#
# Return values:
#   None. This file declares no output; the root's outputs are declared in
#   infra/bootstrap/outputs.tf.
#
# Errors / Exceptions:
#   `terraform init` in this directory must be run WITHOUT a backend, because no
#   `backend` block is declared here. Use `terraform init -backend=false` to
#   initialize for validation; the root's first apply writes its state locally,
#   as terraform.tfstate beside these files. A CLI outside required_version, or
#   an AWS provider outside the declared constraint, aborts during `init` with a
#   version-constraint error before any resource is evaluated.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this root declares no `backend` block because it CREATES the
#     bucket and lock table a `backend "s3"` stanza would have to find already
#     present. Declaring one here would point the root's state at resources the
#     root has not yet created, which cannot resolve on a clean account.
#   - Trade-offs: provider-level default_tags is the single tagging mechanism for
#     this root, so the common tag map is stated once rather than repeated on
#     every resource; the cost is that those tags are not visible at the resource
#     declaration site in main.tf.
#   - Alternatives Considered: declaring `provider "aws"` at the top of main.tf is
#     equally idiomatic, and was rejected because it separates the provider's
#     version constraint from the provider's configuration across two files.
# =============================================================================

terraform {
  # ⚠️ Refactoring Rationale: this read `~> 1.15.0`, whose pessimistic patch
  # component accepts 1.15.0 through 1.15.x and refuses 1.16.0 as well as 2.x. AAP
  # section 0.6.1.4 states the CLI constraint as `>= 1.15.0`, so the ceiling narrowed a
  # frozen plan and is withdrawn along with the paragraphs that argued for it. The
  # reviewed toolchain is still exactly one release, because CI installs a
  # checksum-pinned Terraform; a constraint here could only refuse a CLI the operator
  # had already installed. All three roots -- this one and both environment roots --
  # now state the AAP floor.
  required_version = ">= 1.15.0"

  required_providers {
    # Trade-offs: the pessimistic operator on the minor accepts 6.56 and any
    # higher 6.x, so provider patch and minor fixes arrive without editing this
    # file, while refusing 7.0.0 -- the major bump is where resource-schema
    # removals and argument renames land. Verified against the Terraform Registry
    # at 6.56.0.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # Alternatives Considered: `hashicorp/random` is deliberately absent. That
    # provider belongs to infra/modules/secrets, which generates database and
    # seed-user credentials directly into Secrets Manager. Nothing in this root
    # generates a random value, and infra/.tflint.hcl enables
    # terraform_unused_declarations as a gating check with no return-code
    # tolerance, so an unused provider declaration fails the build rather than
    # merely reading as untidy. Suffixing the state bucket name with a `random_id`
    # is the tempting reason to add it and is rejected: a random suffix makes the
    # bucket name unreproducible, so an operator without the local state file
    # cannot derive the name of the bucket this root already created.
  }
}

# Alternatives Considered: the sole `provider "aws"` block lives here rather than
# in main.tf. versions.tf answers "which Terraform, which provider, and configured
# how", while main.tf answers "what does this root create". Declaring the provider
# at the top of main.tf is equally idiomatic and was rejected because it splits the
# provider's version constraint from its configuration across two files. This is
# the only provider block in the root -- main.tf declares none -- because two
# unaliased configurations of the same provider is a duplicate-configuration error
# raised at init time.
provider "aws" {
  # Assumptions: the region is variable-driven rather than a literal or
  # `data.aws_region`, because infra/bootstrap/outputs.tf reports the region back
  # so it can be written into each environment root's backend configuration.
  # Resolving both from this one variable makes it impossible for the reported
  # region to disagree with the region the state bucket was actually created in.
  # `data.aws_region` would instead report whatever the ambient credentials
  # resolved to, which is precisely the value that can differ from the operator's
  # intent without anything failing.
  region = var.aws_region

  # Trade-offs: tags are applied through the provider rather than on each
  # resource, so the common map is declared once instead of being copied onto the
  # bucket, its sub-resources and the lock table, and resources in main.tf carry
  # only their own distinguishing `Name` tag. The accepted cost is locality --
  # reading a resource in main.tf does not reveal the tags it will carry, so this
  # block is where a reader must look.
  default_tags {
    tags = var.tags
  }

  # Assumptions: no credential argument appears in this block -- no access_key,
  # secret_key, profile or assume_role. The provider resolves credentials from the
  # operator's ambient AWS configuration, and in CI from short-lived OIDC role
  # assumption, so no long-lived credential is ever committed to this repository.
}
