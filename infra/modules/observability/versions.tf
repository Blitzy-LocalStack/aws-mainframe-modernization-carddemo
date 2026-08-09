# =============================================================================
# infra/modules/observability/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the Terraform CLI and AWS provider version contract for the
#   `observability` module, and nothing else. The module itself carries
#   CardDemo's centralized logging, metric dashboards, alarms and operator
#   notification (AAP 0.4.1.6) -- the cloud equivalents of the mainframe
#   job-log and console surfaces this migration replaces: `MSGCLASS` job-log
#   routing and `NOTIFY=&SYSUID` operator notification on the JOB card
#   (app/jcl/POSTTRAN.jcl:1-2), and the `SYSPRINT` / `SYSOUT DD SYSOUT=*` job
#   streams (app/jcl/POSTTRAN.jcl:26-27). Every CICS file in the baseline is
#   defined `RECOVERY(NONE) JOURNAL(NO)` (app/csd/CARDDEMO.CSD:7,9), so this
#   observability surface is ADDED by the migration rather than ported from an
#   existing one. This file declares no resource, no variable, no output and
#   no provider configuration; the four sibling files carry those.
#
# Parameters:
#   NONE. A `terraform` block accepts no inputs: its arguments are resolved
#   before variables exist, so a version constraint can never be written as
#   `var.*`. The module's input surface is variables.tf and its returned
#   values are outputs.tf. Stated explicitly rather than omitted, because an
#   absent parameter section reads identically to an overlooked one.
#
# Return values:
#   No Terraform value is produced here. What this file returns to the rest of
#   the package is a CONSTRAINT CONTRACT that every calling root must satisfy:
#   a Terraform CLI at or above the declared floor, and an AWS provider that
#   resolves inside the declared pessimistic range. The `Requirements` table
#   generated into this module's README.md is rendered from this contract, so
#   a change here without regenerating that README is a drift failure.
#
# Exceptions or errors:
#   A CLI below the floor aborts `terraform init` / `terraform validate` with
#   an unsupported-core-version error; a provider that cannot resolve inside
#   the range aborts provider selection during `init`. Both fail BEFORE any
#   resource is evaluated, which is precisely why the contract is declared
#   here instead of being discovered at apply time against live
#   infrastructure. This module is never applied on its own -- it is validated
#   transitively through whichever root calls it -- so these are the only
#   errors this file can raise.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this directory is a reusable MODULE, not a Terraform root.
#     It is consumed as `module "observability" { source = ... }` by the
#     infra/envs/dev and infra/envs/prod roots, and every decision below
#     follows from that single fact. Three of those decisions are deliberate
#     ABSENCES, recorded here because a reader who finds no `provider` block
#     cannot otherwise tell a considered omission from an oversight.
#   - Alternatives Considered: no `provider "aws"` block. The sibling
#     infra/bootstrap/versions.tf legitimately owns the only `provider "aws"`
#     block in its configuration -- region plus `default_tags` -- because
#     bootstrap IS a root. Mirroring that shape here was rejected: a provider
#     block inside a shared module fixes region and credentials at the module
#     rather than at the environment, so dev and prod could no longer differ;
#     it displaces the root's `default_tags`, dropping package-wide tagging
#     from every log group, alarm and topic this module creates; and it stops
#     the caller passing a provider in explicitly. With no provider block the
#     module inherits the calling root's configuration, which is what lets one
#     module body serve both environments.
#   - Assumptions: no `backend` block. State is configured by roots only --
#     infra/bootstrap keeps local state because it is what CREATES the state
#     bucket, while infra/envs/dev and infra/envs/prod use the S3 backend that
#     bootstrap provisioned. A `backend` block is valid only in a root module,
#     so including one here would not be merely redundant: it would fail
#     `init` for every root that calls this module.
#   - Trade-offs: no `hashicorp/random` provider. infra/README.md pins
#     `hashicorp/random ~> 3.9` for the package as a whole, but that provider
#     exists to generate database and seed-user passwords at apply time, and
#     this module generates no random value -- its resources are log groups, a
#     dashboard, alarms and an SNS topic. Declaring it anyway would trip
#     tflint's `terraform_unused_required_providers` rule, which reports any
#     provider present in `required_providers` that no resource in the module
#     uses, and would make every caller resolve a provider it never uses. Only
#     providers whose resources actually appear in main.tf are declared here,
#     which is the same omission infra/bootstrap/versions.tf makes for the
#     same reason.
#   - Trade-offs: CloudFront metrics are surfaced through a dashboard widget,
#     NOT through an alarm, so this block declares no `configuration_aliases`.
#     CloudFront publishes its metrics to a single region only. A CloudWatch
#     ALARM on a CloudFront metric therefore needs a provider configured for
#     that region, and the sanctioned mechanism for a module to demand one is
#     `configuration_aliases` on the `aws` entry below -- which would make an
#     aliased provider a mandatory part of the calling contract for EVERY
#     root, including roots that never inspect CloudFront. A dashboard metric
#     widget instead carries its own `region` field per widget, so the same
#     signal is surfaced while the module stays instantiable with the default
#     provider alone. This places a contract on main.tf: CloudFront appears as
#     a dashboard widget and never as an `aws_cloudwatch_metric_alarm`; if
#     that changes, `configuration_aliases` must be added here in the same
#     change. Recorded because the failure mode is silent -- an alarm created
#     in any other region against a CloudFront metric receives no datapoint
#     and stays in INSUFFICIENT_DATA, so it appears configured yet can never
#     fire, masking the very outage it was created to report.
# =============================================================================

terraform {
  # WHY : a FLOOR rather than an exact pin, because `required_version` is
  #       checked against the CLI actually running and three separate roots
  #       (infra/bootstrap, infra/envs/dev, infra/envs/prod) plus the CI
  #       runner each invoke this module. Trade-offs: an exact `= 1.15.8` pin
  #       would reject a root on any later patch release for no compatibility
  #       reason, while a floor below 1.15.0 would claim support for a CLI
  #       this package was never exercised against -- 1.15.8 is the version
  #       it is validated on. Alternatives Considered: `~> 1.15` was rejected
  #       because nothing here uses syntax introduced in 1.15, so forbidding
  #       a 1.16 CLI would constrain every caller without protecting any.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : `~> 6.56` admits 6.56.x and any later 6.x minor but excludes 7.0,
    #       so a major-version provider bump -- where this provider's breaking
    #       changes land -- cannot arrive unreviewed. Assumptions: the floor is
    #       set by a concrete capability rather than a preference: a
    #       zero-minimum Aurora serverless capacity requires provider 5.80.0
    #       or later, and the auto-pause argument it makes mandatory requires
    #       5.81.0, so the pair floors at 5.81.0 and 6.56 clears it with margin. Trade-offs: every
    #       module in this package declares this identical constraint, because
    #       a calling root resolves ONE provider version for all the
    #       modules at once and a divergent constraint in any single module
    #       makes the whole root unresolvable.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
