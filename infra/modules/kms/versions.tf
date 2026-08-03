# =============================================================================
# infra/modules/kms/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain and provider contract for the `kms` module, which
#   provisions the four customer-managed encryption keys -- Aurora, S3, Secrets
#   Manager and SQS -- and their rotation settings. Every other file in the
#   module is parsed against the constraints declared here.
#
#   This directory is a reusable MODULE, never a Terraform root. It is consumed
#   as `source = "../../modules/kms"` by infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf, and it is parsed transitively when one of those
#   roots runs `terraform init -backend=false` followed by `terraform validate`;
#   it is never initialised, planned or applied on its own. That distinction
#   shapes the whole file: it declares only what a called module may own -- a
#   CLI version floor and a provider requirement -- and omits provider
#   configuration, backend configuration, `configuration_aliases` (the stack is
#   single-region) and every provider this module does not itself use. The
#   module's inputs and outputs -- each carrying its own `description` -- are
#   declared in variables.tf and outputs.tf; this file declares none, and no
#   resource, so it has no argument of its own left to document.
#
# WHY (non-obvious design decisions):
#   - Trade-off: the CLI constraint is a floor while the provider constraint is
#     pessimistic. The asymmetry is deliberate -- the CLI is chosen by whichever
#     root invokes this module, whereas the resource schema the module's
#     arguments are written against must not move across a provider major.
#   - Assumption: the calling root configures `provider "aws"` -- the region and
#     the `default_tags` these keys inherit their tags from -- and owns the
#     state backend. Neither is declared here.
#   - Alternatives Considered: requiring `hashicorp/random`, as the sibling
#     `secrets` and `cognito` modules must.
#   Each bullet is expanded, with its mechanism, at the argument it applies to.
# =============================================================================

terraform {
  # Trade-off: a floor (`>=`) rather than a pessimistic pin such as `~> 1.15`.
  # A called module is parsed by whatever CLI its root invokes, so a tight pin
  # here could only be raised in lockstep across all sixteen modules under
  # infra/modules before any one root could move -- and a root running a CLI
  # outside a module's range does not degrade, it stops at `terraform init`. The
  # floor keeps this module callable by every root at or above it while still
  # guaranteeing the language level the configuration is written against: 1.15.0
  # is where the `infra/` tree is authored, and static validation runs on
  # 1.15.8, which this constraint admits.
  required_version = ">= 1.15.0"

  # Assumption: the AWS provider is CONFIGURED by the calling root
  # (infra/envs/dev, infra/envs/prod), which sets the region and the
  # `default_tags` every key here inherits; this block only REQUIRES it, and the
  # root's configuration is what the module then inherits. A `provider "aws"`
  # body inside a called module would be a second, competing configuration for
  # the same provider: the root's region and tags would stop reaching these
  # resources, the caller could no longer override either, and Terraform would
  # reject `count`, `for_each` and `depends_on` on the module block itself.
  #
  # Assumption: state is a root-level concern too, so no `backend` block appears
  # here -- Terraform honours a backend only in the root module and reports one
  # found in a called module as ignored configuration. The S3 backend and its
  # lock table are declared per environment in infra/envs/dev/backend.tf and
  # infra/envs/prod/backend.tf, against the state bucket infra/bootstrap
  # declares.
  required_providers {
    # Alternatives Considered: requiring `hashicorp/random` here as well, which
    # the sibling `secrets` and `cognito` modules genuinely need in order to
    # generate credentials at apply time. Rejected because nothing in this
    # module generates a random value -- KMS key material is created by the
    # service itself, and every other value the module needs is passed in by the
    # caller -- so the requirement would be declared and never used, which
    # tflint's unused-declaration rules report as a finding. That tflint run is
    # a gating step in .github/workflows/infra-ci.yml with no tolerated return
    # code, so an unused requirement fails the pipeline rather than lingering.
    #
    # Trade-off: `~> 6.56` accepts 6.56.x patches and later 6.x minors but
    # refuses 7.0.0, so a provider major release cannot change the
    # `aws_kms_key` argument schema this module is written against between one
    # `init` and the next; adopting such a release becomes an explicit edit
    # here. The lower bound is not arbitrary either: the sibling
    # aurora-postgresql module requires a provider no older than 5.81.0 to
    # express a zero minimum-capacity Aurora cluster, and 6.56 clears that, so
    # one constraint string is repeated verbatim across every module and both
    # environment roots instead of each directory negotiating its own.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
