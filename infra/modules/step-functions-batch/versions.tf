# =============================================================================
# infra/modules/step-functions-batch/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The Terraform CLI and provider contract for the `step-functions-batch`
#   module, and nothing else. The module body provisions the batch
#   orchestration layer that replaces the mainframe's nightly job stream: the
#   eleven-state `carddemo-daily-batch` state machine, a second and much
#   smaller state machine for on-demand reports, one shared IAM execution role,
#   and one CloudWatch log group per machine. This file declares no resource,
#   no variable, no output and no provider configuration -- main.tf,
#   variables.tf and outputs.tf carry those, and the five files together are
#   the whole module.
#
#   Reusable MODULE, never a Terraform root. infra/envs/dev and infra/envs/prod
#   each consume it as `source = "../../modules/step-functions-batch"`, so it
#   is initialised transitively inside a calling root's module graph and is
#   never applied on its own. Every absence recorded below follows from that
#   one fact.
#
#   Lineage. The daily machine carries the step ordering of the thirty-eight
#   job streams under app/jcl/, of which app/jcl/POSTTRAN.jcl is the
#   representative case: a single `EXEC PGM=` step at L23 with nine DD
#   statements behind it, including the newly-created reject stream at
#   L34-L38 whose `(+1)` relative generation becomes a new object generation
#   rather than a new catalogued dataset. The second machine carries the
#   ad-hoc submission path that app/csd/CARDDEMO.CSD:L499-L501 defines as a
#   transient-data queue writing to the internal reader. Both baseline
#   artifacts are reference-only and keep running unchanged; this tree adds a
#   path beside them rather than removing one.
#
#   The choice of Terraform itself is settled in
#   docs/adr/ADR-009-iac-tool.md and is deliberately not re-argued here.
#
# Parameters:
#   None, and none is possible. A `terraform` block is evaluated before
#   variables exist, so neither constraint below can be written as `var.*` or
#   overridden by a caller. The module's input surface is variables.tf. The two
#   settings a reader most often arrives here looking for are elsewhere by
#   design: the target region and the common tag set belong to the caller's
#   provider configuration in infra/envs/<env>, which this module inherits.
#   The constraint values themselves are not chosen here either -- they are the
#   package-wide pins recorded in infra/README.md, section 3.1.
#
# Return values:
#   No Terraform value is produced. What this file returns to the rest of the
#   package is a constraint contract every calling root must satisfy and,
#   once satisfied, the provider surface main.tf is entitled to build on:
#   `aws_sfn_state_machine` with its `logging_configuration` and
#   `tracing_configuration` blocks, `aws_iam_role`, `aws_iam_role_policy`,
#   `aws_cloudwatch_log_group`, and the `aws_partition`, `aws_region` and
#   `aws_caller_identity` data sources the execution role's policy is composed
#   from. The state machine identifier that infra/modules/eventbridge-scheduler
#   and services/reporting-service each start an execution against is published
#   by outputs.tf, not here.
#
# Exceptions or errors:
#   Both constraints fail at `terraform init`, before any plan exists. A root
#   running a CLI below the declared floor is refused with an explicit version
#   error naming this constraint, and a module graph that cannot resolve
#   `hashicorp/aws` inside the declared range is refused as unsatisfiable. A
#   root whose committed lock file already pins a release outside that range
#   fails the same way until the lock is regenerated. Failing at init is the
#   intent: a plan computed against an unintended provider major is worse than
#   no plan, because it reads as success. Were this file absent the failure
#   would move to the lint gate instead, where infra/.tflint.hcl enables
#   terraform_required_version for the missing floor and
#   terraform_required_providers for the unconstrained provider.
#
# WHY (non-obvious design decisions):
#   - Assumptions: both constraints are character-for-character identical to
#     those in the other fifteen modules, which is deliberate rather than
#     copy-paste inertia. A calling root intersects the constraints of every
#     module in its graph and resolves one release of each provider for the
#     whole graph, so a range that differs per module narrows that
#     intersection and, in the limit, empties it.
#   - Trade-offs: restating one contract in nineteen directories is accepted so
#     that every directory states what it needs on its own terms. The cost is
#     nineteen edits to move a pin; the alternative of letting a module inherit
#     its version contract from whichever root loaded it was rejected, because
#     a module reached from an unexpected caller would then carry no contract
#     at all and would resolve against whatever that caller happened to allow.
#   - Alternatives Considered: configuring an `aws` provider here, and
#     declaring `hashicorp/random` beside it. Both are rejected, each for its
#     own reason, and each reason is recorded at the point of use below rather
#     than summarised here.
#   - Refactoring Rationale: the baseline expressed no platform contract of any
#     kind. A job stream such as app/jcl/POSTTRAN.jcl names the program it runs
#     and the datasets it needs, but says nothing about the system level
#     required to run them, so a mismatch surfaced part-way through a night's
#     chain as a failing step rather than as a refusal to start. Declaring the
#     CLI floor and the provider range is the correction of that specific gap,
#     and it is why this file exists at all in a module whose resources are
#     declared elsewhere.
# =============================================================================

terraform {
  # WHY : Assumptions: every directory under infra/ is authored against the
  #       1.15 language level and the package was validated on 1.15.8, so
  #       1.15.0 is the oldest release that parses what this tree writes. A
  #       floor rather than an `=` pin because this module is reached from both
  #       environment roots and from the validate and lint jobs, none of whose
  #       CLI it controls; an exact pin would oblige every one of them onto a
  #       single identical patch release, which a reusable module has no
  #       standing to dictate. The consequence of the floor is the reason to
  #       declare it at all: a root on an older CLI stops at `init` with a
  #       version error that points here, instead of failing further along on
  #       whichever construct that CLI could not read. The roots bound the
  #       minor line as well, at `~> 1.15.0`, because a root is the directory
  #       an operator actually runs.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Assumptions: the resources main.tf declares are those the 6.56 line
    #       carries -- `aws_sfn_state_machine` together with its
    #       `logging_configuration` and `tracing_configuration` blocks,
    #       `aws_iam_role`, `aws_iam_role_policy` and
    #       `aws_cloudwatch_log_group`, plus the `aws_partition`,
    #       `aws_region` and `aws_caller_identity` data sources the execution
    #       role's policy is composed from. Release 6.56.0 was verified against
    #       the Terraform Registry rather than assumed to exist. Naming that
    #       surface is what makes the constraint load-bearing instead of
    #       decorative: it records precisely what would break were the range
    #       widened downward.
    #       Trade-offs: `~> 6.56` admits 6.56.x through 6.99.x and stops short
    #       of 7.0. Accepted, so provider patch and minor releases reach this
    #       module with no edit here, while a major move -- which may rename or
    #       drop an argument this module sets -- has to be a deliberate,
    #       reviewable change. Two alternatives were rejected. An exact
    #       `= 6.56.0` would force every consuming root onto one provider build
    #       and block provider fixes while buying nothing this module needs. An
    #       unbounded `>= 6.56` would admit a breaking major silently, as a
    #       failed plan against configuration nobody had touched.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Alternatives Considered: declaring it here so that all nineteen
    #       directories read alike, which is the tempting move because
    #       infra/modules/secrets and infra/modules/cognito do declare it to
    #       generate the database credential and the seed-user passwords
    #       straight into Secrets Manager. Rejected on two independent grounds.
    #       This module generates nothing: it derives every name and every
    #       identifier it needs from its inputs, and two state machines, one
    #       execution role and two log groups have nothing to ask a random
    #       provider for. And infra/.tflint.hcl enables
    #       terraform_unused_required_providers, which reports an entry no
    #       resource in the same directory consumes. Past the finding, a
    #       declared provider tells every reader that this module might mint a
    #       credential, and that would be false. Fourteen directories in this
    #       package record the same omission for the same reason, so the
    #       absence here is the tree's convention rather than an oversight.
  }
}

# -----------------------------------------------------------------------------
# Deliberate absences. Everything below is a declaration this file does not
# make, recorded so that none of them reads as an oversight to be repaired.
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: adding one, which is the plausible-looking
#       wrong answer precisely because the provider is already named a few
#       lines above. `required_providers` and `provider` are different
#       declarations and only the first belongs to a module: it states a
#       REQUIREMENT -- which source address, and which version range must be
#       resolvable -- whereas a `provider` block CONFIGURES an already-resolved
#       provider with a region, a credentials chain and a default tag set.
#       Configuring it here would burn one region and one credentials chain
#       into the module, and four things would follow. The module could not be
#       used in another account or region at all. infra/envs/dev and
#       infra/envs/prod could no longer differ only in sizing and retention
#       while sharing one topology, which is the property that lets one module
#       body serve both. A root could no longer hand in an aliased provider
#       through `providers = {}`. And the root's `default_tags` would stop
#       reaching these resources, so the common tag set would quietly vanish
#       from the state machines and their log groups. Inheriting the caller's
#       configuration keeps all four open. Note the deliberate contrast with
#       infra/bootstrap, which is a root and therefore does own a provider
#       configuration; inverting those two cases is the error this comment
#       exists to prevent.

# WHY : Assumptions: each is structural, so none of them becomes appropriate
#       once the module is complete.
#       - No `backend` block. Terraform accepts state configuration only in a
#         root module, so a backend here would not merely be redundant, it
#         would fail `init` for every root that calls this module. State
#         belongs to the caller, in infra/envs/<env>/backend.tf, against the
#         versioned bucket and lock table infra/bootstrap provisions.
#       - No `provider` block and no `hashicorp/random`, for the two reasons
#         recorded above.
#       - No `experiments`. It opts a directory into unstable language
#         features whose behaviour can change between CLI releases, and this
#         module uses none. A shared module is the worst place to accept that,
#         because the instability would propagate to every calling root.
#       - No `provider_meta`. It exists so a provider can attribute requests
#         to the module that issued them, which serves a published provider
#         rather than a first-party module in this repository.
#       - `.terraform.lock.hcl` is generated by module-level `terraform init`
#         and committed under this repository's module-lock convention. It is
#         not hand-authored here: the lock records the exact provider package
#         used for isolated module validation, while a calling root still
#         resolves and locks the combined graph independently.
# =============================================================================
