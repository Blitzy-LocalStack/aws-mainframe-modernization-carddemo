# =============================================================================
# infra/modules/api-gateway-http/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the reusable `api-gateway-http`
#   Terraform module: the single public entry point for the eight migrated
#   CardDemo services -- an API Gateway HTTP API with a Cognito JWT
#   authorizer, reaching the internal ALB over a VPC Link so the private
#   application subnets are never exposed. This file declares ONLY the
#   minimum Terraform CLI release and the AWS provider version range the
#   module's configuration is written against; the resources themselves are
#   in main.tf.
#
# Parameters:
#   Not applicable -- this file declares no `variable` block. The module's
#   input surface (naming, the VPC Link's subnets and security groups, the
#   Cognito issuer and audience, the ALB listener target, the tag map) is
#   declared, each entry with its own `description`, in variables.tf.
#
# Return values:
#   Not applicable -- this file declares no `output` block. The values this
#   module exports to its caller are declared in outputs.tf.
#
# Exceptions / failure modes:
#   Nothing here can fail on its own: a module is never applied directly, it
#   is called as `source = "../../modules/api-gateway-http"`. Both
#   constraints below are therefore enforced in the CALLING root:
#   - a consumer whose Terraform CLI predates 1.15.0 is rejected while the
#     configuration is loaded, with an unsupported-version error;
#   - a consumer whose dependency lock resolves an AWS provider outside
#     `~> 6.56` -- a 7.x release above all -- fails provider selection at
#     `terraform init`.
#   Neither surfaces from inspecting this folder on its own; both surface on
#   `terraform -chdir=infra/envs/<env> init` followed by `validate`.
#
# Deliberately absent (stated so omission does not read as oversight; each
#   is justified again at its point of use below):
#   - no `provider` block for aws: provider configuration is the calling
#     environment root's to own;
#   - no `backend` block: a called module has no state of its own;
#   - no `hashicorp/random` requirement: this module generates no value.
#
# Baseline lineage:
#   Naming the exact artifact set a deployment is built against is not a new
#   idea here: the CICS resource definition already resolved every program
#   from one explicitly named load library --
#   `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` on the `DEFINE LIBRARY(CARDDLIB)`
#   stanza at app/csd/CARDDEMO.CSD:L489-L491, which is REFERENCE-ONLY and is
#   cited, never modified. This file is that same discipline in Terraform.
# =============================================================================

terraform {
  # WHAT: the oldest Terraform CLI release this configuration is declared to
  #       load and evaluate correctly.
  # WHY : (1) Trade-offs: a floor, not the exact release CI installs. Every
  #       directory carries this same constraint -- each module, the
  #       bootstrap root and the two environment roots -- so `= 1.15.8` would
  #       make every CLI bump an edit in all of them, and would break the
  #       moment the pipeline's pinned CLI moved off that patch. The accepted
  #       cost is that a floor does not record which CLI a reader actually
  #       ran, which is what (2) states explicitly.
  #       (2) Assumptions: 1.15.0 is a COMPATIBILITY claim -- the oldest
  #       release permitted -- while 1.15.8 is a VERIFICATION claim: it is the
  #       release .github/workflows/infra-ci.yml installs, so it is the only
  #       one this configuration is exercised on. Read the gap between the two
  #       as permitted-but-unexercised, not as verified.
  required_version = ">= 1.15.0"

  # WHY : Assumptions: no `backend` block belongs beside required_version here.
  #       Terraform applies one selected backend to the whole configuration,
  #       so a backend block inside a called module is not honoured; state for
  #       these resources lands in the calling root's backend, configured in
  #       infra/envs/<env>/backend.tf against the bucket and lock table
  #       infra/bootstrap provisions. Declaring one here would only misdirect
  #       a reader looking for where this module's state is kept.

  required_providers {
    # WHY : (1) Alternatives Considered: `>= 6.56` was rejected because it
    #       admits a 7.x provider, and a provider major release may rename or
    #       remove resource arguments, so the HTTP API, JWT authorizer and VPC
    #       Link arguments main.tf sets could change meaning inside an
    #       otherwise untouched plan. `= 6.56.0` was rejected because adopting
    #       a patch would then be a code change in every directory.
    #       `~> 6.56` admits 6.56.x and any later 6.x minor and refuses 7.x,
    #       which is the boundary that carries the risk.
    #       (2) Trade-offs: a minor-release regression can still reach a plan
    #       under `~>`. That is accepted because the .terraform.lock.hcl each
    #       environment root commits records the version actually selected, so
    #       the constraint is deliberately a range and the lock file is the
    #       reproducible pin.
    #       (3) Assumptions: the 6.x-era floor is inherited infra-wide, not
    #       required by anything in this module. It exists because a provider
    #       at 5.81.0 or later is needed to accept a zero minimum Aurora
    #       capacity, recorded in docs/adr/ADR-003-datastore-targets.md, and
    #       `~> 6.56` clears that. Every module and root repeats the identical
    #       constraint so a provider upgrade is one decision rather than
    #       one per directory -- the pinning discipline recorded in
    #       docs/adr/ADR-009-iac-tool.md.
    #       (4) Assumptions: `source` is written out even though a bare `aws`
    #       would resolve to hashicorp/aws by registry default. Stating it
    #       drops the dependence on that default and gives tflint's
    #       terraform_required_providers rule an explicit value to check, so
    #       the namespace is verified rather than inferred.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Assumptions: `hashicorp/random` is pinned infra-wide, and its
    #       absence from this map is deliberate -- nothing in this module
    #       generates a value. The seed-user and database passwords are
    #       produced by the `secrets` and `cognito` modules and written to
    #       Secrets Manager, which is what keeps credentials out of the
    #       repository. An entry that no resource in the module consumes is
    #       reported by tflint's terraform ruleset as
    #       terraform_unused_required_providers -- confirmed against bundled
    #       ruleset 0.15.0 -- so declaring it would turn a note about intent
    #       into a lint finding.
  }
}

# WHY : Alternatives Considered: declaring a `provider` block for aws at this
#       file's scope, which is the plausible mistake in a file named
#       versions.tf. Rejected on three checkable mechanisms. A module that
#       carries its own provider configuration (a) cannot be handed an
#       aliased provider -- Terraform reports `Cannot override provider
#       configuration ... it cannot accept an overridden configuration
#       provided by the root module`; (b) cannot be called with count,
#       for_each or depends_on -- Terraform reports `Module is incompatible
#       with count, for_each, and depends_on`; and (c) would resolve region,
#       assume_role and default_tags from itself, so the environment tags
#       every other resource carries would never reach the HTTP API, the
#       authorizer or the VPC Link. Those settings are therefore owned only
#       by infra/envs/dev and infra/envs/prod, and this module inherits
#       whatever they configure.
