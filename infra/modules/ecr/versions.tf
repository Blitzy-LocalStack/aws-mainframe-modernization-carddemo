# =============================================================================
# infra/modules/ecr/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain and provider contract for the reusable `ecr`
#   Terraform module, which provisions the ten Amazon ECR container
#   repositories that replace the single z/OS CICS load library the whole
#   CardDemo application used to execute from -- the
#   `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` library defined at
#   app/csd/CARDDEMO.CSD:L491. Exactly two things are fixed here and nowhere
#   else in this directory: the minimum Terraform CLI version, and the range
#   of AWS provider versions the module is known to work against.
#
#   This module is never applied directly. It is consumed as
#   `source = "../../modules/ecr"` by the infra/envs/dev and infra/envs/prod
#   roots, so it is initialised and validated only transitively, through one
#   of those roots.
#
# Parameters:
#   None. This file declares no `variable` blocks and accepts no inputs; the
#   module's entire input surface lives in infra/modules/ecr/variables.tf.
#   Recorded explicitly rather than omitted, so a reader can tell "this file
#   has no inputs" apart from "this file's inputs went undocumented".
#
# Return values:
#   No `output` blocks; the module's outputs live in
#   infra/modules/ecr/outputs.tf. What this file returns is a constraint
#   surface that every calling root must satisfy: (1) a Terraform CLI of at
#   least 1.15.0, and (2) an `aws` provider resolving inside `~> 6.56`. The
#   root supplies the configured provider itself -- this file only bounds
#   which versions of it are acceptable.
#
# Exceptions / errors:
#   - `terraform init` in a calling root fails with an unsupported-version
#     error when the operator's CLI is older than 1.15.0. The run aborts
#     before any provider is downloaded, so the failure is unambiguous.
#   - Provider resolution fails when the calling root's `.terraform.lock.hcl`,
#     or any sibling module in the same root, pins `hashicorp/aws` to a
#     version outside `~> 6.56`. Terraform must satisfy every constraint in
#     the graph with a single provider version, so one incompatible pin
#     anywhere in the root blocks the whole `init`.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: pinning a toolchain is not a new constraint this
#     migration invents; it preserves a discipline the baseline already
#     practised. The z/OS job that deployed the CICS resource definitions
#     this container registry replaces named its vendor toolchain library
#     exactly, never "whatever release is current": app/jcl/CBADMCDJ.jcl:L29
#     pins its STEPLIB to `DSN=OEM.CICSTS.V05R06M0.CICS.SDFHLOAD,DISP=SHR`,
#     naming CICS Transaction Server release V05R06M0 outright. That same job
#     also resolves its artifact path from one symbol,
#     `SET HLQ=AWS.M2.CARDDEMO` at L25, consumed once at L45 as
#     `DSNAME01(&HLQ..LOADLIB)`, which is the same instinct that keeps this
#     directory's version contract in one file rather than repeating it
#     beside every resource.
#   - Assumptions: the calling root owns provider *configuration* and this
#     file owns only provider *version bounds*. The split is what lets dev
#     and prod drive the identical module with different region and tagging
#     configuration; see the three deliberate omissions recorded below the
#     `terraform` block.
# =============================================================================

terraform {
  # 1.15.0 is the oldest CLI this infrastructure package has been exercised
  # against, and 1.15.8 is the build it was actually validated end to end on.
  # The validated patch is named here because ">= 1.15.0" states what is
  # permitted, not what was proven -- an operator triaging odd `plan` output
  # on a 1.15.x CLI needs to know which build is the known-good reference.
  #
  # Trade-offs: a floor, not an exact `= 1.15.8` pin. An exact pin would force
  # every operator and CI runner onto one CLI build and would break the day a
  # patched CLI shipped for a security fix; a floor admits newer 1.x CLIs
  # while still rejecting pre-1.15 releases outright.
  required_version = ">= 1.15.0"

  required_providers {
    # Floor: this infrastructure package needs at least provider 5.81.0. Two
    # releases set that: 5.80.0 first accepted a zero minimum capacity on Aurora
    # Serverless v2, and 5.81.0 added the auto-pause-seconds argument a zero
    # minimum then makes mandatory -- both relied on by the sibling
    # aurora-postgresql module to let the dev environment scale to zero, so the
    # floor is the later of the two. 6.56 clears that comfortably.
    # Every module in the package declares this same constraint so that a
    # root resolves one `aws` provider version for its entire module graph
    # and a single lock file per root stays authoritative.
    #
    # Trade-offs: `~>` bounded at the minor position admits 6.56.x patches and
    # later 6.x minors while excluding 7.0. That buys provider bug and
    # security fixes without editing the version contract in every Terraform
    # directory in this package, at the cost of never adopting a 7.0 major
    # without an explicit, reviewed change.
    #
    # Alternatives Considered: an exact `= 6.56.0` pin was rejected because it
    # freezes the package on one patch release, turning every provider
    # security fix into an edit of every Terraform directory. A bare
    # `>= 6.56` was rejected because it would let a 7.0 major, with breaking
    # resource schemas, enter unannounced on the next `init -upgrade`.
    #
    # Assumptions: `source` is stated explicitly rather than leaning on
    # Terraform's implicit `hashicorp/` namespace lookup, so the address can
    # never resolve to a same-named provider published under a different
    # namespace.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}

# No `provider "aws"` block here, deliberately.
# Alternatives Considered: configuring the provider inside this module was
# rejected. A module that configures its own provider hard-binds the region
# and the `default_tags` set for every caller, which would make it impossible
# for infra/envs/dev and infra/envs/prod to drive this same module with
# different configuration -- the exact reuse this module exists to provide. A
# reusable module therefore inherits the already-configured provider from its
# calling root. The asymmetry with infra/bootstrap/versions.tf is intentional
# and not an oversight: bootstrap is a root, so it owns a `provider "aws"`
# block; this is a called module, so it must not.

# No `hashicorp/random` provider here, deliberately.
# Assumptions: every repository name this module creates is composed
# deterministically from `var.name_prefix` and `var.environment`, and no
# password, token or unique suffix is generated, so nothing in this module
# needs a random value. infra/README.md pins `hashicorp/random ~> 3.9` for the
# package as a whole; declaring it here regardless would be an unused
# declaration, which tflint's gating unused-declaration rules fail the build
# on -- `terraform_unused_required_providers` catches precisely an unused
# `required_providers` entry, which is what this would be.

# No `backend` block and no `cloud` block here, deliberately.
# Assumptions: a called module has no state of its own -- the resources it
# declares are recorded in the calling root's state file. Backend
# configuration therefore belongs only to infra/envs/dev/backend.tf and
# infra/envs/prod/backend.tf, which point at the versioned S3 bucket and the
# DynamoDB lock table that infra/bootstrap provisions. A backend declared in a
# module is not honoured, so adding one would create a false impression that
# this directory tracks state independently.
