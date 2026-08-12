# =============================================================================
# infra/modules/ecr/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain and provider contract for the reusable `ecr` module,
#   which provisions the Amazon ECR container repositories that replace the
#   single z/OS CICS load library the whole CardDemo application used to execute
#   from -- `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` at app/csd/CARDDEMO.CSD:L491.
#   Exactly two things are fixed here and nowhere else in this directory: the
#   minimum Terraform CLI version, and the range of AWS provider versions the
#   module is known to work against. The module is never applied directly; it is
#   consumed as `source = "../../modules/ecr"` by the two environment roots, so
#   it is initialised and validated only transitively through one of them.
#
# Parameters:
#   None. This file declares no `variable` and accepts no inputs; the module's
#   entire input surface lives in variables.tf. Recorded explicitly so a reader
#   can tell "this file has no inputs" apart from "its inputs went undocumented".
#
# Return values:
#   None. What this file returns is a constraint surface every calling root must
#   satisfy: a Terraform CLI of at least 1.15.0, and an `aws` provider resolving
#   inside `~> 6.56`. The root supplies the configured provider itself.
#
# Exceptions / errors:
#   - `terraform init` in a calling root fails with an unsupported-version error
#     on a CLI older than 1.15.0, before any provider is downloaded.
#   - Provider resolution fails when the calling root's `.terraform.lock.hcl`, or
#     any sibling module in the same root, pins `hashicorp/aws` outside
#     `~> 6.56`. Terraform must satisfy every constraint in the graph with one
#     provider version, so a single incompatible pin blocks the whole `init`.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the calling root owns provider CONFIGURATION and this file
#     owns only provider VERSION BOUNDS. That split is what lets dev and prod
#     drive the identical module with different region and tagging
#     configuration; the three deliberate omissions are recorded below the
#     `terraform` block.
#   - Refactoring Rationale: pinning a toolchain preserves a discipline the
#     baseline already practised rather than inventing one -- the z/OS job that
#     deployed the CICS definitions this registry replaces named its vendor
#     library release exactly rather than "whatever is current", and resolved its
#     artifact path from a single symbol. README.md carries the citations.
# =============================================================================

terraform {
  # Assumptions: 1.15.0 is the oldest CLI this package has been exercised against
  # and 1.15.8 is the build it was validated end to end on. The validated patch is
  # named because ">= 1.15.0" states what is permitted, not what was proven, and
  # an operator triaging odd `plan` output needs the known-good reference.
  #
  # Trade-offs: a floor, not an exact `= 1.15.8` pin. An exact pin would force
  # every operator and CI runner onto one CLI build and would break the day a
  # patched CLI shipped for a security fix; a floor admits newer 1.x CLIs while
  # still rejecting pre-1.15 releases outright.
  required_version = ">= 1.15.0"

  required_providers {
    # Assumptions: the package needs at least provider 5.81.0 -- 5.80.0 first
    # accepted a zero minimum capacity on Aurora Serverless v2, and 5.81.0 added
    # the auto-pause argument a zero minimum then makes mandatory, both relied on
    # by the sibling aurora-postgresql module to let dev scale to zero. 6.56
    # clears that comfortably. Every module declares the same constraint so a
    # root resolves one `aws` version for its whole module graph and a single
    # lock file per root stays authoritative. `source` is stated explicitly
    # rather than leaning on the implicit `hashicorp/` namespace lookup, so the
    # address cannot resolve to a same-named provider in another namespace.
    #
    # Alternatives Considered: an exact `= 6.56.0` pin freezes the package on one
    # patch release, turning every provider security fix into an edit of every
    # Terraform directory. A bare `>= 6.56` would let a 7.0 major, with breaking
    # resource schemas, enter unannounced on the next `init -upgrade`. The
    # pessimistic constraint admits 6.56.x patches and later 6.x minors and
    # excludes 7.0, at the cost of a reviewed edit to adopt a major.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}

# Three blocks are absent from this file deliberately, and each absence is a
# decision rather than an omission.
#
# Alternatives Considered: no `provider "aws"`. A module that configures its own
# provider hard-binds the region and the `default_tags` set for every caller,
# which would stop dev and prod driving this same module with different
# configuration -- the exact reuse the module exists to provide. A reusable module
# inherits the already-configured provider from its calling root. The asymmetry
# with infra/bootstrap/versions.tf is intentional: bootstrap is a root, so it owns
# a provider block; this is a called module, so it must not.
#
# Assumptions: no `hashicorp/random`. Every repository name is composed
# deterministically from `var.name_prefix` and `var.environment`, and no password,
# token or unique suffix is generated here. infra/README.md pins the provider for
# the package as a whole; declaring it here would be an unused entry, which
# tflint's `terraform_unused_required_providers` rule fails the build on.
#
# Assumptions: no `backend` and no `cloud`. A called module has no state of its
# own -- its resources are recorded in the calling root's state file -- so backend
# configuration belongs only to the environment roots, which point at the
# versioned S3 bucket and lock table infra/bootstrap provisions. A backend
# declared in a module is not honoured, so adding one would falsely imply this
# directory tracks state independently.
