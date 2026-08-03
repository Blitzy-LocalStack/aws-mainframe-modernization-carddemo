# =============================================================================
# infra/modules/secrets/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Declares the toolchain and provider contract for the `secrets` Terraform
#   module. variables.tf, main.tf and outputs.tf in this directory are all
#   authored under the constraints fixed here, which makes this file the
#   module's foundation: it sets the minimum Terraform language level the
#   configuration relies on, and the exact provider majors whose resource
#   schemas the module's resources are written against.
#
#   The module provisions Secrets Manager entries whose values are generated
#   at apply time rather than read from source. That is the mechanism by which
#   no credential ever reaches this repository, and it is why a random-value
#   provider is a genuine requirement here rather than an incidental one.
#
# WHY (non-obvious design decisions):
#   - Assumption: this directory is a reusable MODULE, not a root. It is
#     invoked as `source = "../../modules/secrets"` from the dev and prod
#     environment roots, and those roots own the only `provider` blocks. Hence
#     the deliberate absence below of `provider "aws"`, `provider "random"`
#     and `backend`: a provider block here would be ignored or would fight the
#     caller's configuration, and it would hard-wire a region into a module
#     that has to stay reusable across regions. A module cannot declare a
#     backend at all, because state belongs to the calling root.
#   - Trade-off: because no provider block exists here, no `default_tags`
#     exists either, so main.tf must apply tags PER RESOURCE from `var.tags`
#     rather than inheriting them. The absence below is what creates that
#     obligation, so the two files have to be read together.
#   - Alternatives Considered: `configuration_aliases` was evaluated and
#     rejected. Nothing in this module targets a second region or a second
#     account, and declaring an alias would force every caller to pass an
#     explicit provider map for no gain. `experiments`, `cloud` and
#     `provider_meta` are absent for the same reason: nothing here needs them.
#   - Trade-off: exactly two providers are declared and no more. A third entry
#     that no resource consumed would be reported by tflint's
#     terraform_unused_required_providers rule, so this list is kept as a
#     precise statement of what the module actually uses.
# =============================================================================

terraform {
  # WHY : Assumption -- 1.15.0 is the language level this whole tree is
  #       authored against and the tree is validated on 1.15.8, so the floor
  #       exists to turn a version mismatch into one clear error at `init`
  #       instead of a confusing parse or plan-time failure partway through a
  #       run on an older CLI. It is deliberately `>=` and not `~>`: the CLI is
  #       supplied by the operator or the CI runner rather than resolved from a
  #       registry, so a ceiling here would reject a newer CLI that a calling
  #       root legitimately standardises on. The providers below are
  #       constrained the opposite way, and for a different reason.
  required_version = ">= 1.15.0"

  required_providers {
    # WHY : Assumption -- one AWS provider major.minor is pinned across the
    #       ENTIRE infra/ tree so that two modules composed into the same root
    #       can never disagree. Terraform has to select a single version
    #       satisfying every constraint in the configuration, so a divergent
    #       pin in one module becomes an init-time resolution failure for the
    #       whole root. The 6.56 floor is inherited from the sibling
    #       aurora-postgresql module, which needs >= 5.81.0 to express a
    #       zero-minimum Aurora Serverless capacity range; 6.56 clears that
    #       comfortably, and matching it here keeps this module composable
    #       with that sibling.
    # WHY : Trade-off -- `~>` rather than an exact `=` pin. An exact pin would
    #       freeze provider patch releases and require a commit for each one,
    #       whereas `~>` accepts a patch update but refuses a minor bump, so
    #       resource schemas cannot change underneath a plan that was reviewed
    #       against this file.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Alternatives Considered -- the alternative was to omit this entry
    #       entirely, which is what infra/bootstrap correctly does, because
    #       nothing in that root generates a random value and an unused
    #       provider would be flagged by tflint's
    #       terraform_unused_required_providers rule. Omission is rejected
    #       here for the mirror-image reason: main.tf declares
    #       `random_password` resources, so without this entry `terraform
    #       init` cannot resolve them and the module fails to initialise at
    #       all. Generating those passwords at apply time straight into
    #       Secrets Manager is what keeps every credential out of version
    #       control, so this entry carries that guarantee rather than being a
    #       convenience.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}
