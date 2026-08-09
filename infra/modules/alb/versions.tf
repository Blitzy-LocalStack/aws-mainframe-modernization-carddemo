# =============================================================================
# infra/modules/alb/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain contract for the `alb` module -- the minimum Terraform
#   CLI the module is written against, and the single provider it requires
#   (`hashicorp/aws`, which supplies the internal Application Load Balancer, its
#   HTTPS listener and the per-service listener rules routing to the ECS services
#   that replace the CICS region's transaction dispatch). Every `terraform init`
#   that installs the module reads this file, so main.tf, variables.tf and
#   outputs.tf never restate a version constraint.
#
#   Assumptions: this directory is a reusable MODULE, so the provider it requires
#   arrives ALREADY CONFIGURED by the caller -- credentials, `region` and
#   `default_tags` are inherited from the calling root's `provider "aws"` block,
#   because a child module that declares no provider configuration of its own
#   receives the caller's default configuration for that local name.
#
#   Alternatives Considered: configuring the provider here. Rejected on two
#   concrete grounds, both reproduced against CLI 1.15.8 rather than assumed.
#   (a) The environment root owns `region` and `default_tags`; a module-level
#   provider configuration would shadow them, so the tags the root applies to
#   every resource would never reach this module's load balancer, listener and
#   target groups. (b) A module that configures its own provider cannot be called
#   with `count`, `for_each` or `depends_on` and cannot be handed an aliased
#   provider -- Terraform fails `init` outright in both cases. Note the
#   deliberate contrast with infra/bootstrap/versions.tf, which DOES own the only
#   `provider "aws"` block in its tree: bootstrap is a Terraform ROOT, this is a
#   MODULE, so a reader arriving from bootstrap should read the omission here as
#   the rule rather than as an oversight.
#
#   Assumptions: no `backend` block either, because state is configured only at
#   a root. Terraform does not error on a backend block in a child module -- it
#   silently IGNORES it -- which is exactly why one must not be written here: it
#   would be inert configuration implying this module manages state it cannot.
#
#   Assumptions: no `hashicorp/random`, because this module generates no random
#   value; every name it uses is supplied by its caller. Declaring it would leave
#   a requirement no resource consumes, which tflint reports as
#   `terraform_unused_required_providers`.
# =============================================================================

terraform {
  # Alternatives Considered: an exact `= 1.15.8` pin, rejected because adopting
  #   even a patch release would then require an identical edit in every module
  #   directory and both environment roots, while buying no compatibility
  #   guarantee this module needs -- it uses no language feature newer than
  #   1.15.0, which is the earliest release the package's validation covers.
  required_version = ">= 1.15.0"

  required_providers {
    # Trade-offs: one constraint is reused package-wide instead of narrowing the
    #   range per module. `~> 6.56` admits >= 6.56.0 and < 7.0.0, taking provider
    #   fixes and newly exposed attributes while refusing a major whose breaking
    #   changes would have to be absorbed everywhere at once, and it clears the
    #   `>= 5.81` floor another module needs (Aurora accepts a zero minimum
    #   capacity from 5.80.0 onward and the auto-pause argument it then requires
    #   from 5.81.0, so the pair floors at 5.81.0). A single uniform
    #   constraint keeps every module resolvable to one provider build rather
    #   than scattering per-module ranges that could disagree.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
