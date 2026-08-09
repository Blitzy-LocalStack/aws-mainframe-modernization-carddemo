# =============================================================================
# infra/modules/ecs-service/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the Terraform toolchain and AWS provider contract for the reusable
#   `ecs-service` module. The module carries exactly one CardDemo service onto
#   ECS Fargate -- task definition, task role, log group, load-balancer target
#   group and autoscaling -- and each of the two environment roots,
#   infra/envs/dev and infra/envs/prod, instantiates it nine times, once per
#   workload: the eight Java services -- auth, account, card, transaction,
#   reference, batch, authorization and reporting -- plus the data-migration
#   ETL, which needs a task definition and no service. Every constraint
#   declared below is therefore inherited by eighteen module instances across
#   the two roots.
#
# Parameters:
#   None. This file declares no `variable` and reads none; its only inputs are
#   the two constraints written inside the `terraform` block itself. The
#   module's input surface -- cluster, container image, CPU, memory, task
#   count, subnets, security groups, tags -- is declared in the sibling
#   variables.tf, which is the single place a caller's arguments are accepted
#   and typed.
#
# Return values:
#   No `output` either; outputs.tf owns those. What this file returns is a
#   CONTRACT rather than a value: a minimum Terraform CLI version and a window
#   of acceptable hashicorp/aws provider versions, both inherited by every
#   caller of the module. Terraform intersects that window with the calling
#   root's own constraint, so the effective provider version is the narrower of
#   the two.
#
# Exceptions or errors:
#   - A root running a CLI below the declared floor fails at `terraform init`
#     with an unsupported-Terraform-version error, before any resource in this
#     module is evaluated.
#   - A root whose tracked .terraform.lock.hcl resolves hashicorp/aws outside
#     the `~>` window fails at `terraform init`, because the locked version no
#     longer matches the configured constraints.
#   - A root that has not configured an `aws` provider fails at
#     `terraform plan` once this module's resources are evaluated, because the
#     module deliberately configures none -- see the closing comment.
#
# WHY (non-obvious design decisions):
#   - Assumptions: no `backend` block appears here, because a module holds no
#     state of its own. This directory is never applied directly; it is reached
#     only through `terraform -chdir=infra/envs/<env>`, and infra-ci.yml
#     initialises the three roots -- infra/bootstrap, infra/envs/dev and
#     infra/envs/prod -- with `init -backend=false`, which is what validates
#     this module transitively.
#   - Assumptions: because that validation is transitive and never happens in
#     isolation, a reference to an undeclared input would surface at the
#     calling root rather than here. Every input main.tf consumes must
#     therefore be declared in variables.tf; that is a standing constraint on
#     the module as a whole, so it belongs on its entry point.
#   - Refactoring Rationale: HCL has no docstring or file-header construct of
#     its own, so this header adopts the house form already measured in
#     .github/workflows/tests.yml -- 79-column rulers, the repository-relative
#     path alone on the second line, then Purpose and a WHY list. Inventing a
#     new shape for the first .tf file in the repository would have left the
#     modules that follow with no precedent to match.
#   - Trade-offs: one parameterised module is preferred over nine bespoke
#     per-workload definitions, and app/csd/CARDDEMO.CSD is the evidence that
#     this matches the baseline's own shape -- all 18 `DEFINE TRANSACTION`
#     stanzas (L306-L488) are attribute-identical, with ISOLATE(YES),
#     TASKDATAKEY(USER), ACTION(BACKOUT), PRIORITY(1), RESTART(NO),
#     PROFILE(DFHCICST) and TRANCLASS(DFHTCL00) each occurring exactly 18
#     times, so the CICS region already expressed 18 workloads through one
#     repeated template differing only in name and target program. The
#     accepted cost is that a service needing a genuinely different shape must
#     extend this module's inputs rather than fork the module.
# =============================================================================

terraform {
  # WHY : Assumptions: the infrastructure package is validated on 1.15.8, and it
  #       depends on provider behaviour that no older release can express -- a
  #       zero minimum Aurora capacity (provider 5.80.0) together with the
  #       auto-pause argument it makes mandatory (5.81.0) requires 5.81.0,
  #       which the constraint below clears comfortably. A floor (`>=`) rather
  #       than an exact pin (`=`) is deliberate: a module must not forbid its
  #       caller from running a newer CLI, and one toolchain is shared by all
  #       nine instantiations in both roots, so pinning here would dictate the
  #       CLI version for the entire repository from inside a leaf module.
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

    # WHY : Assumptions: nothing in this module generates a random value. Every
    #       generated credential -- database passwords, seed-user passwords --
    #       is produced by infra/modules/secrets at apply time and written
    #       straight into Secrets Manager, which is what makes "no secrets
    #       committed to the repository" structurally true rather than merely
    #       observed. Declaring an unused requirement here would additionally
    #       raise tflint's terraform_unused_required_providers rule -- the rule
    #       that fires on a required_providers entry no resource consumes --
    #       which gates the infrastructure pipeline.
  }
}

# WHY : Alternatives Considered: declaring a provider here was evaluated and
#       rejected on two counts. First, it would move `region` and
#       `default_tags` out of the calling root's control, and the environment
#       roots are the single place environment parameterization is allowed to
#       live -- dev and prod differ only in sizing and retention, never in
#       topology. Second, a module-local provider configuration cannot be
#       varied per instantiation, so it would fix all nine workloads within a
#       root to one identical configuration. Contrast
#       infra/bootstrap/versions.tf, which DOES own a provider block:
#       bootstrap is a root, not a module, so there it is the only correct
#       place for one. Both files are right, and the difference between them is
#       root versus module rather than an inconsistency.
