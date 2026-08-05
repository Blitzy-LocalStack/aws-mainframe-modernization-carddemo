# =============================================================================
# infra/modules/eventbridge-scheduler/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain and provider contract for the `eventbridge-scheduler`
#   module: the reusable module that provisions the nightly schedule which
#   starts the `carddemo-daily-batch` Step Functions state machine, together
#   with the dead-letter target that captures an invocation the scheduler could
#   not deliver. Handing the calendar to a managed scheduler is what removes the
#   self-managed scheduler from the target architecture altogether.
#
#   The module carries the *intent* of the retired mainframe scheduler
#   definitions -- the CA-7 LJOB trigger chain in app/scheduler/CardDemo.ca7 and
#   the Control-M INCOND/OUTCOND folders in app/scheduler/CardDemo.controlm --
#   rather than their syntax. Those files remain reference-only, and the
#   job-to-job sequencing they encode moves into the state machine, which leaves
#   this module responsible for one thing only: firing that machine on a
#   calendar.
#
#   This file is the version contract and nothing more; it provisions no
#   resource. The resources live in main.tf, the inputs in variables.tf and the
#   exported values in outputs.tf. Those four files together with this one are
#   the entire module.
#
# Declares:
#   - required_version   the minimum Terraform CLI feature level.
#   - required_providers exactly one entry, `hashicorp/aws`.
#
# Deliberately does NOT declare -- each choice justified inline below:
#   - provider "aws"     provider configuration belongs to the calling root.
#   - hashicorp/random   this module generates no random value.
#   - backend / cloud    Terraform accepts state configuration only in a root
#                        module, so the absence of these is structural rather
#                        than an omission. Each root in this tree --
#                        infra/bootstrap, infra/envs/dev and infra/envs/prod --
#                        configures its own state.
#
# Inputs / Outputs:
#   None. This file declares no `variable` and no `output`, so the house
#   obligation to give every variable and output a `description` is discharged
#   in variables.tf and outputs.tf rather than here.
#
# Consumed by:
#   infra/envs/dev and infra/envs/prod, each via
#   `source = "../../modules/eventbridge-scheduler"`. The module is never
#   applied on its own; it is validated transitively when a root is validated.
# =============================================================================

terraform {
  # WHY : Trade-offs: a reusable module is loaded by separate roots, and
  #       Terraform intersects every `required_version` constraint it
  #       encounters. A `~>` or `=` constraint here would therefore let this
  #       module veto a CLI its callers had already agreed on, and the failure
  #       would surface as an unsatisfiable-constraint error far from the file
  #       that caused it. Stating a floor keeps the module honest about what it
  #       actually needs and leaves the pinning decision with infra/bootstrap,
  #       infra/envs/dev and infra/envs/prod, which is where an operator looks
  #       for it. Exercised against 1.15.8.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Alternatives Considered: an exact `= 6.56.0` pin was the
    #       alternative and is rejected -- inside a single minor range it adds
    #       no safety, because `~>` already refuses the next major and the
    #       resource-schema churn that comes with it, while it does block patch
    #       releases carrying provider fixes. Two reasons the constraint is
    #       6.56 specifically. First, it is the one constraint every module in
    #       this tree uses, so they all compose inside a single root without
    #       a provider-version conflict; Terraform intersects the constraints
    #       of every module it loads, so one divergent pin is enough to make
    #       that intersection empty. Second, it is recent enough to expose the
    #       `aws_scheduler_schedule` and `aws_scheduler_schedule_group`
    #       resources this module is built on, which provider generations
    #       predating the EventBridge Scheduler service do not carry at all.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Assumptions: `hashicorp/random` is absent on purpose. This module
    #       derives every name from its inputs and generates no password and no
    #       name suffix, so it has nothing to ask a random provider for; the
    #       credential generation that does need it belongs to
    #       infra/modules/secrets. Declaring it here anyway would not be inert:
    #       tflint reports a provider that is declared in `required_providers`
    #       but unused by the module (terraform_unused_required_providers), and
    #       infra/.tflint.hcl gates the build on that ruleset.
    #       infra/bootstrap/versions.tf records the same omission for the same
    #       reason.
  }
}

# WHY : Alternatives Considered: there is deliberately no `provider "aws"`
#       block in this file, and adding one is the plausible-looking wrong
#       answer. A reusable module inherits its provider configuration -- region,
#       default_tags and any alias -- from whichever root calls it, which keeps
#       the root the single place region and tagging are decided and lets dev
#       and prod differ without editing a module. Configuring the provider
#       inside the module would duplicate that decision across every module,
#       and it would also break the two composition patterns this tree relies
#       on: a root could no longer pass an aliased provider in through
#       `providers = {}`, and the module could no longer be instantiated
#       per-item with `for_each`. Note the deliberate contrast with
#       infra/bootstrap/versions.tf, which *does* own the only `provider "aws"`
#       block in its tree -- bootstrap is a root rather than a module, so
#       configuring the provider is precisely its job. Inverting these two cases
#       is the mistake this comment exists to prevent.
