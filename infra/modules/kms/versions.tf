# =============================================================================
# infra/modules/kms/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain and provider contract for the `kms` module, which
#   provisions the four customer-managed encryption keys -- Aurora, S3, Secrets
#   Manager and SQS -- and their rotation settings. Every other file in the
#   module is parsed against the constraints declared here.
#
#   Assumptions: this directory is a reusable MODULE, never a Terraform root. It
#   is consumed as `source = "../../modules/kms"` by infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf and is parsed transitively when one of those roots
#   runs `terraform init -backend=false` followed by `terraform validate`; it is
#   never initialised, planned or applied on its own. That is why this file
#   declares only what a called module may own -- a CLI version floor and a
#   provider requirement -- and omits provider configuration, backend
#   configuration, `configuration_aliases` (the stack is single-region) and every
#   provider the module does not itself use. Inputs and outputs, each carrying its
#   own `description`, are declared in variables.tf and outputs.tf.
# =============================================================================

terraform {
  # Trade-offs: a floor (`>=`) rather than a pessimistic pin such as `~> 1.15`,
  # because a called module is parsed by whatever CLI its root invokes, so a tight
  # pin here could only be raised in lockstep across every module under
  # infra/modules before any one root could move -- and a root running a CLI
  # outside a module's range does not degrade, it stops at `terraform init`. The
  # floor still guarantees the language level the configuration is written
  # against: 1.15.0 is where the `infra/` tree is authored and static validation
  # runs on 1.15.8, which this constraint admits.
  required_version = ">= 1.15.0"

  # Assumptions: the AWS provider is CONFIGURED by the calling root, which sets
  # the region and the `default_tags` every key here inherits; this block only
  # REQUIRES it. A `provider "aws"` body inside a called module would be a second,
  # competing configuration for the same provider -- the root's region and tags
  # would stop reaching these resources, the caller could no longer override
  # either, and Terraform would reject `count`, `for_each` and `depends_on` on the
  # module block itself. State is a root-level concern for the same reason, so no
  # `backend` block appears here; Terraform honours one only in a root module and
  # reports one found in a called module as ignored configuration.
  required_providers {
    # Alternatives Considered: requiring `hashicorp/random` here as well, which the
    # sibling `secrets` and `cognito` modules genuinely need in order to generate
    # credentials at apply time. Rejected because nothing in this module generates a
    # random value -- KMS key material is created by the service itself and every
    # other value is passed in by the caller -- so the requirement would be declared
    # and never used, which tflint's unused-declaration rules report as a finding in
    # a gating step with no tolerated return code.
    #
    # Trade-offs: `~> 6.56` accepts 6.56.x patches and later 6.x minors but refuses
    # 7.0.0, so a provider major cannot change the `aws_kms_key` argument schema this
    # module is written against between one `init` and the next; adopting one becomes
    # an explicit edit here. The lower bound is not arbitrary either: the sibling
    # aurora-postgresql module requires a provider no older than 5.81.0 to express a
    # zero minimum-capacity cluster, and 6.56 clears that, so one constraint string is
    # repeated verbatim across every module and both environment roots.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
