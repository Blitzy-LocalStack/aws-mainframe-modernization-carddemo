# =============================================================================
# infra/modules/step-functions-batch/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The Terraform CLI and provider contract for the `step-functions-batch`
#   module, and nothing else. The module body provisions the batch orchestration
#   layer that replaces the mainframe's nightly job stream: the eleven-work-state
#   `carddemo-daily-batch` state machine, a second and much smaller state machine
#   for on-demand reports, one shared IAM execution role and one CloudWatch log
#   group per machine. Resources, inputs and outputs live in main.tf,
#   variables.tf and outputs.tf, and README.md carries the JCL lineage of the
#   step ordering. The choice of Terraform itself is settled in
#   docs/adr/ADR-009-iac-tool.md.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this is a reusable MODULE, never a Terraform root. Both
#     environment roots consume it by relative source, so it is initialised
#     transitively inside a calling root's module graph and never applied on its
#     own. Every absence follows from that one fact and none becomes appropriate
#     later: a `provider` block would burn one region and one credentials chain
#     into a module both roots share, keep the root's `default_tags` off these
#     resources and bar a caller from passing an aliased provider; a `backend`
#     block fails `init` for every calling root, because Terraform accepts state
#     configuration only in a root module; `experiments` would propagate unstable
#     language features to every caller; and `provider_meta` serves a published
#     provider rather than a first-party module. The deliberate contrast is
#     infra/bootstrap, which IS a root and therefore does own a provider
#     configuration; inverting the two cases is the error to avoid.
#   - Trade-offs: both constraints are character-for-character identical to every
#     sibling module's, which is deliberate rather than copy-paste inertia. A
#     calling root intersects the constraints of every module in its graph and
#     resolves one release of each provider for the whole graph, so a range that
#     differs per module narrows that intersection and, in the limit, empties it.
#     Restating one contract per directory is accepted so each states what it
#     needs on its own terms; letting a module inherit its contract from
#     whichever root loaded it would leave a module reached from an unexpected
#     caller carrying no contract at all.
# =============================================================================

terraform {
  # Assumptions: 1.15.0 is the oldest release that parses what this tree writes
  #   and the package was validated on 1.15.8. A floor rather than an `=` pin
  #   because this module is reached from both environment roots and from the
  #   validate and lint jobs, none of whose CLI it controls, and a reusable module
  #   has no standing to oblige them onto one patch release. The consequence is
  #   the reason to declare it at all: a root on an older CLI stops at `init` with
  #   a version error pointing here instead of failing further along on whichever
  #   construct that CLI could not read.
  required_version = ">= 1.15.0"

  required_providers {
    # Assumptions: the 6.56 line carries every resource main.tf declares,
    #   including `aws_sfn_state_machine` with its `logging_configuration` and
    #   `tracing_configuration` blocks and the partition, region and caller
    #   identity data sources the execution role's policy is composed from.
    #   Naming that surface is what makes the constraint load-bearing rather than
    #   decorative: it records what would break were the range widened downward.
    # Trade-offs: `~> 6.56` admits later 6.x and stops short of 7.0, so provider
    #   patch and minor releases reach this module with no edit here while a major
    #   move -- which may rename or drop an argument this module sets -- has to be
    #   a deliberate, reviewable change. An exact `= 6.56.0` would force every
    #   consuming root onto one build and block provider fixes; an unbounded
    #   `>= 6.56` would admit a breaking major silently, as a failed plan against
    #   configuration nobody had touched.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # Alternatives Considered: declaring `hashicorp/random` here as well, which
    #   infra/modules/secrets and infra/modules/cognito do to generate credentials
    #   straight into Secrets Manager. Rejected on two independent grounds: this
    #   module generates nothing, deriving every name and identifier from its
    #   inputs; and infra/.tflint.hcl enables terraform_unused_required_providers,
    #   which reports an entry no resource in the same directory consumes. Past
    #   the finding, a declared random provider would tell every reader that this
    #   module might mint a credential, and that would be false.
  }
}
