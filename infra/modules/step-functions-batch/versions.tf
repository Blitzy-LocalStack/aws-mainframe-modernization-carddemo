# =============================================================================
# infra/modules/step-functions-batch/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the `step-functions-batch` module, and
#   nothing else. The module itself carries the `carddemo-daily-batch` state
#   machine -- the eleven-state replacement for the nightly JCL chain -- its
#   execution role, its log group and the wiring that lets each state run a
#   Fargate task and wait for it. This file settles two questions only: which
#   Terraform CLI releases may build the module, and which `hashicorp/aws`
#   releases may satisfy it. It declares no resource, no variable, no output
#   and no provider configuration; the sibling files carry those.
#
#   Reusable MODULE, not a Terraform root. infra/envs/dev and infra/envs/prod
#   consume it as `source = "../../modules/step-functions-batch"`, so it is
#   initialised transitively inside those roots' module graphs and is never
#   applied on its own. Every absence noted below follows from that one fact.
#
#   Lineage: the state machine this module provisions replaces the thirty-eight
#   job streams under app/jcl/, whose step ordering is expressed by `COND=`
#   parameters and whose operator bracket is the SDSF quiesce and resume pair
#   app/jcl/CLOSEFIL.jcl and app/jcl/OPENFIL.jcl. That baseline is
#   reference-only and keeps running unchanged; this tree adds a path beside it
#   rather than removing one.
#
# Parameters:
#   None. A `terraform` block takes no inputs -- its arguments are resolved
#   before variables exist, so a version constraint can never be written as
#   `var.*`. The module's input surface is variables.tf. What a reader most
#   often arrives here looking for, the AWS region and the common tag set, is
#   deliberately elsewhere: a module inherits its caller's aws provider
#   configuration, so `region` and `default_tags` are set once in
#   infra/envs/<env> and reach every module from there.
#
# Return values:
#   No Terraform value is produced here. What this file returns to the rest of
#   the package is a CONSTRAINT CONTRACT every calling root must satisfy: a CLI
#   at or above the declared floor, and an AWS provider resolving inside the
#   declared pessimistic range. The state machine ARN this module publishes --
#   the value infra/modules/eventbridge-scheduler consumes as its
#   `state_machine_arn` input -- is declared in outputs.tf, not here.
#
# Exceptions or errors:
#   `terraform init` fails, before any plan exists, when the operator's CLI
#   predates 1.15.0 or when no available `hashicorp/aws` release falls inside
#   `~> 6.56`. Failing that early is the intent: a plan computed against an
#   unintended provider major is worse than no plan at all. Were this file
#   missing, the failure would instead land in infra-ci, where tflint's
#   terraform_required_version reports that the `required_version` attribute is
#   required and terraform_required_providers reports a missing version
#   constraint for aws.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the two constraints below are IDENTICAL to those in the
#     other fifteen modules, deliberately and not by copy-paste inertia. A
#     calling root intersects the constraints of every module in its graph and
#     selects one release of each provider for the whole graph; ranges that
#     differ per module narrow that intersection and, in the limit, empty it.
#     Repeating one contract in nineteen directories is the cost of a single
#     resolved provider version per root.
#   - Alternatives Considered: configuring an aws provider here. Rejected
#     because it makes the module un-composable -- it would fix a region the
#     caller could not override, discard the root's `default_tags` so every
#     resource lost the common tag set, and leave a root no way to pass in an
#     aliased provider. Inheriting the caller's configuration keeps all three
#     open, which is what lets one module body serve both environments.
#   - Assumptions: no `backend` block, because a module never owns state. The
#     state definition belongs to the calling root, in
#     infra/envs/<env>/backend.tf, and infra/bootstrap provisions the versioned
#     bucket and lock table standing behind it. A `backend` block is valid only
#     in a root module, so including one here would not merely be redundant: it
#     would fail `init` for every root that calls this module.
#   - Alternatives Considered: declaring `hashicorp/random`, which the rest of
#     this tree pins at `~> 3.9`. Omitted here for the same reason
#     infra/modules/network and infra/bootstrap omit it: this module derives
#     every value from its inputs and generates no random one, and tflint's
#     terraform_unused_required_providers rule reports any entry a module does
#     not use. Trade-offs: the nineteen directories under infra/ are then not
#     textually identical, which is the lesser of the two costs.
#   - Assumptions: the AWS provider floor is set by a requirement THIS module
#     never exercises. An Aurora Serverless v2 minimum capacity of zero, which
#     infra/modules/aurora-postgresql needs for the development environment,
#     requires provider 5.81.0 or later. This module would run on a far older
#     release, and pinning it lower would only shrink the intersection its
#     calling root has to resolve.
# =============================================================================

terraform {
  # WHAT: floor the CLI at 1.15.0 rather than pinning one release exactly.
  # WHY : Assumptions: 1.15.8 is the release this tree was authored and validated
  #       against, and 1.15.0 is the oldest that accepts the configuration
  #       syntax used across infra/. Trade-offs: a floor lets an operator take a
  #       newer patch or minor release without editing all nineteen infra/
  #       directories, at the cost of not naming one exact build; the calling
  #       root's lock file, not this constraint, is what records the exact
  #       selection a plan was computed against.
  required_version = ">= 1.15.0"

  required_providers {
    # WHAT: admit aws 6.56 and later 6.x, never 7.x.
    # WHY : Alternatives Considered: `= 6.56.0` was rejected for freezing out
    #       provider fixes and forcing a coordinated edit across the tree for
    #       each one, and an open `>= 6.56` was rejected because it would
    #       silently adopt a 7.x major whose breaking changes surface as a
    #       failed plan against configuration nobody touched. `~> 6.56` admits
    #       6.56.x and later 6.x minors but never 7.0, so a major move stays a
    #       deliberate, reviewable edit.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
