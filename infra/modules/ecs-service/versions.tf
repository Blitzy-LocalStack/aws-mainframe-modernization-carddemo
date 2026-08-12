# =============================================================================
# infra/modules/ecs-service/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the Terraform toolchain and AWS provider contract for the reusable
#   `ecs-service` module, which carries exactly one CardDemo service onto ECS
#   Fargate -- task definition, task role, log group, load-balancer target group
#   and autoscaling. Each environment root instantiates it once per workload, so
#   every constraint below is inherited by every instance in both roots.
#
# Parameters:
#   None. This file declares no `variable`; the module's input surface is
#   declared in the sibling variables.tf, which is the single place a caller's
#   arguments are accepted and typed.
#
# Return values:
#   None. What this file returns is a CONTRACT rather than a value: a minimum
#   Terraform CLI version and a window of acceptable hashicorp/aws provider
#   versions. Terraform intersects that window with the calling root's own
#   constraint, so the effective provider version is the narrower of the two.
#
# Exceptions or errors:
#   - A root running a CLI below the declared floor fails at `terraform init`
#     before any resource in this module is evaluated.
#   - A root whose tracked .terraform.lock.hcl resolves hashicorp/aws outside
#     the `~>` window fails at `terraform init`.
#   - A root that has not configured an `aws` provider fails at `terraform plan`
#     once this module's resources are evaluated, because the module
#     deliberately configures none -- see the closing comment.
#
# WHY (non-obvious design decisions):
#   - Assumptions: no `backend` block appears here, because a module holds no
#     state of its own. This directory is never applied directly; it is reached
#     only through `terraform -chdir=infra/envs/<env>`, and infra-ci.yml
#     initialises the roots with `init -backend=false`, which is what validates
#     this module transitively. Because that validation is never in isolation, a
#     reference to an undeclared input surfaces at the calling root rather than
#     here, so every input main.tf consumes must be declared in variables.tf.
#   - Trade-offs: one parameterised module is preferred over a bespoke
#     definition per workload. The accepted cost is that a service needing a
#     genuinely different shape must extend this module's inputs rather than
#     fork the module; the baseline's own CICS transaction definitions took the
#     same shape, differing only in name and target program. README.md carries
#     the evidence and the per-instance inventory.
# =============================================================================

terraform {
  # WHY : Assumptions: the package is validated on Terraform 1.15.8 and depends on
  #       provider behaviour no older release expresses -- a zero minimum Aurora
  #       capacity together with the auto-pause argument it makes mandatory needs
  #       provider 5.81.0, which the constraint below clears comfortably. A floor
  #       (`>=`) rather than an exact pin is deliberate: a leaf module must not
  #       forbid its caller from running a newer CLI, nor dictate the CLI version
  #       for every root that reaches it.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Trade-offs: the pessimistic constraint admits 6.56 and later 6.x
    #       releases but excludes 7.x, so a provider major bump cannot silently
    #       change resource schemas underneath nine instantiations at once;
    #       the accepted cost is that a genuinely required 7.x feature needs an
    #       explicit, reviewed edit to this line. Note the division of labour:
    #       .terraform.lock.hcl is tracked in this repository -- .gitignore
    #       excludes .terraform/, *.tfstate* and *.tfplan but not the lock file
    #       -- so each root pins the exact resolved version by checksum. This
    #       constraint is the window; the lock file is the pin.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Assumptions: no random provider is required, because nothing in this
    #       module generates a value. Every generated credential is produced by
    #       infra/modules/secrets at apply time and written straight into Secrets
    #       Manager. Declaring an unused requirement would also raise tflint's
    #       terraform_unused_required_providers rule, which gates the pipeline.
  }
}

# WHY : Alternatives Considered: declaring a `provider` block here was rejected on
#       two counts. It would move `region` and `default_tags` out of the calling
#       root's control, and the environment roots are the single place environment
#       parameterization is allowed to live; and a module-local provider
#       configuration cannot be varied per instantiation, so it would fix every
#       workload within a root to one identical configuration.
#       infra/bootstrap/versions.tf does own a provider block because bootstrap is
#       a root rather than a module, which is the difference between the two files
#       rather than an inconsistency.
