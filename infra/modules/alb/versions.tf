# =============================================================================
# infra/modules/alb/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain contract for the `alb` module -- the minimum
#   Terraform CLI the module is written against, and the single provider it
#   requires (`hashicorp/aws`, which supplies the internal Application Load
#   Balancer, its HTTPS listener and the per-service listener rules that route
#   to the ECS services replacing the CICS region's transaction dispatch).
#   This file is the module's toolchain entry point: every `terraform init`
#   that installs the module reads it, so the module's other files (main.tf,
#   variables.tf, outputs.tf) never restate a version constraint. It declares
#   NOTHING else -- no provider configuration, no backend, no variable, no
#   output, no resource -- for the reasons recorded under Design decisions below.
#
# Parameters:
#   HCL has no parameter list, so the analogue is the two constraints declared
#   here. Both are version-constraint strings:
#     - `required_version` (string, ">= 1.15.0") -- admits Terraform CLI
#       1.15.0 and every later release; excludes every CLI below 1.15.0.
#     - `required_providers.aws.version` (string, "~> 6.56") -- admits
#       hashicorp/aws >= 6.56.0 and < 7.0.0; excludes 6.55.x and earlier, and
#       excludes any 7.x. Its companion `source` (string, "hashicorp/aws")
#       fixes the registry address so the local name `aws` is never resolved
#       by guesswork.
#
# Returns:
#   HCL has no return value, so the analogue is what this file makes available
#   to the rest of the module: a resolved `hashicorp/aws` provider satisfying
#   `~> 6.56`, ALREADY CONFIGURED by the caller. Its credentials, `region` and
#   `default_tags` are inherited from the calling environment root's
#   `provider "aws"` block, because a child module that declares no provider
#   configuration of its own receives the caller's default configuration for
#   that local name.
#
# Errors:
#   - `Unsupported Terraform Core version` at `terraform init` when the CLI is
#     older than 1.15.0.
#   - Provider installation failure at `terraform init` when no hashicorp/aws
#     release satisfying `~> 6.56` is reachable -- an unreachable registry, or
#     a dependency lock file pinning an out-of-range version.
#   - `Module is incompatible with count, for_each, and depends_on`, or
#     `Cannot override provider configuration`, at `terraform init` if a
#     `provider "aws"` block is ever added to this module: Terraform then
#     classifies it as a legacy module and rejects any caller that uses those
#     meta-arguments or passes an aliased provider. A SECOND non-aliased
#     `provider "aws"` block within this module would instead raise
#     `Duplicate provider configuration`. All three were reproduced against
#     CLI 1.15.8 rather than assumed.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: no `provider "aws"` block here -- configuring
#     the provider inside the module was evaluated and rejected on two
#     concrete grounds. (a) The environment root owns `region` and
#     `default_tags`; a module-level provider configuration would shadow them,
#     so the tags the root applies to every resource would never reach this
#     module's load balancer, listener and target groups. (b) A module that
#     configures its own provider cannot be called with `count`, `for_each` or
#     `depends_on`, and cannot be handed an aliased provider by its caller --
#     Terraform fails `init` outright in both cases, as reproduced above.
#     Provider configuration is therefore owned exclusively by the environment
#     roots, infra/envs/dev and infra/envs/prod. Note the deliberate contrast
#     with infra/bootstrap/versions.tf, which DOES own the only
#     `provider "aws"` block in its tree: bootstrap is a Terraform ROOT, this
#     is a reusable MODULE. A reader arriving from bootstrap should read the
#     omission here as the rule, not as an oversight.
#   - Assumptions: no `backend` block here -- state is configured only at the
#     root. `backend.tf` exists solely in infra/envs/dev and infra/envs/prod,
#     pointing at the versioned S3 bucket and the DynamoDB lock table that
#     infra/bootstrap provisions. Terraform does not error on a backend block
#     in a child module -- it silently IGNORES it (reproduced on CLI 1.15.8),
#     which is exactly why one must not be written here: it would be inert
#     configuration implying this module manages state that it cannot manage.
#   - Assumptions: no `hashicorp/random` provider -- this module generates no
#     random value -- every name it uses is supplied by its caller. The wider
#     package does pin `hashicorp/random ~> 3.9` where random values are
#     genuinely produced, but declaring it here would leave a requirement no
#     resource consumes, which tflint reports as
#     `terraform_unused_required_providers` -- the provider-specific sibling of
#     `terraform_unused_declarations`, which covers only unused variables,
#     locals and data sources. infra/bootstrap/versions.tf records the
#     identical reasoning for the identical omission, so the two files agree.
# =============================================================================

terraform {
  # WHY : Assumptions: the infra/ package is authored and validated on Terraform
  #       1.15.8, and 1.15.0 is the earliest release that validation covers.
  #       Alternatives Considered: an exact `= 1.15.8` pin was rejected because
  #       adopting even a patch release would then require an identical edit in
  #       every module directory and both environment roots, while buying no
  #       compatibility guarantee this module needs -- it uses no language
  #       feature newer than 1.15.0.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Assumptions: hashicorp/aws 6.56.0 is published, so `~> 6.56`
    #       resolves; it admits >= 6.56.0 and < 7.0.0, taking provider bug
    #       fixes and newly exposed resource attributes while refusing a major
    #       version whose breaking changes would have to be absorbed across all
    #       every module at once.
    #       Trade-offs: this one constraint is reused package-wide instead of
    #       narrowing the range per module. A `>= 5.81` floor is required
    #       elsewhere in the package (Aurora accepts a zero minimum capacity
    #       only from that provider release onward) and `~> 6.56` clears it, so
    #       a single uniform constraint keeps every module resolvable to one
    #       provider build rather than scattering per-module ranges that could
    #       disagree.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
