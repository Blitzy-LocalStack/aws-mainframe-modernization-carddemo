# =============================================================================
# infra/modules/eventbridge-scheduler/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain and provider contract for the `eventbridge-scheduler`
#   module: the reusable module that provisions the nightly schedule which starts
#   the `carddemo-daily-batch` Step Functions state machine, together with the
#   dead-letter target that captures an invocation the scheduler could not
#   deliver. Handing the calendar to a managed scheduler is what removes the
#   self-managed scheduler from the target architecture altogether.
#
#   The module carries the INTENT of the retired mainframe scheduler definitions
#   -- the CA-7 LJOB trigger chain in app/scheduler/CardDemo.ca7 and the Control-M
#   INCOND/OUTCOND folders in app/scheduler/CardDemo.controlm -- rather than their
#   syntax. Those files are reference-only, and the job-to-job sequencing they
#   encode moves into the state machine, which leaves this module responsible for
#   one thing: firing that machine on a calendar. Resources live in main.tf,
#   inputs in variables.tf and exported values in outputs.tf.
#
#   Alternatives Considered: a `provider "aws"` block here, which is the
#   plausible-looking wrong answer. A reusable module inherits its provider
#   configuration -- region, default_tags and any alias -- from whichever root
#   calls it, which keeps the root the single place region and tagging are
#   decided and lets dev and prod differ without editing a module. Configuring
#   the provider here would duplicate that decision across every module and would
#   break the two composition patterns this tree relies on: a root could no
#   longer pass an aliased provider through `providers = {}`, and the module could
#   no longer be instantiated per-item with `for_each`. Note the deliberate
#   contrast with infra/bootstrap/versions.tf, which DOES own the only
#   `provider "aws"` block in its tree, because bootstrap is a root rather than a
#   module. `backend` and `cloud` are absent structurally for the same class of
#   reason: Terraform accepts state configuration only in a root module.
# =============================================================================

terraform {
  # Trade-offs: a reusable module is loaded by separate roots and Terraform
  #   intersects every `required_version` constraint it encounters, so a `~>` or
  #   `=` constraint here would let this module veto a CLI its callers had
  #   already agreed on, surfacing as an unsatisfiable-constraint error far from
  #   the file that caused it. A floor keeps the module honest about what it
  #   needs and leaves the pinning decision with the roots, which is where an
  #   operator looks for it. Exercised against 1.15.8.
  required_version = ">= 1.15.0"

  required_providers {
    # Alternatives Considered: an exact `= 6.56.0` pin, rejected because inside a
    #   single minor range it adds no safety -- `~>` already refuses the next
    #   major and its resource-schema churn -- while it does block patch releases
    #   carrying provider fixes. 6.56 specifically because it is the one
    #   constraint every module in this tree uses, so they all compose inside a
    #   single root without a provider-version conflict (Terraform intersects the
    #   constraints of every module it loads, so one divergent pin is enough to
    #   empty that intersection), and because it exposes the
    #   `aws_scheduler_schedule` and `aws_scheduler_schedule_group` resources this
    #   module is built on, which provider generations predating the EventBridge
    #   Scheduler service do not carry at all.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # Assumptions: `hashicorp/random` is absent on purpose. This module derives
    #   every name from its inputs and generates no password and no name suffix;
    #   the credential generation that does need it belongs to
    #   infra/modules/secrets. Declaring it here would not be inert -- tflint
    #   reports a provider declared in `required_providers` but unused by the
    #   module (terraform_unused_required_providers), and infra/.tflint.hcl gates
    #   the build on that ruleset.
  }
}
