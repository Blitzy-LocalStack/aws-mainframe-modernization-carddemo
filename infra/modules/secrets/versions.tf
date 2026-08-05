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
#   The module generates its service database passwords at apply time and
#   imports environment-issued TLS material through required sensitive inputs.
#   The random provider is therefore a genuine requirement for the database
#   family rather than an incidental one; certificate issuance remains outside
#   Terraform and needs no third provider.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this directory is a reusable MODULE, not a root. It is
#     invoked as `source = "../../modules/secrets"` from the dev and prod
#     environment roots, and those roots own the only `provider` blocks. Hence
#     the deliberate absence below of `provider "aws"`, `provider "random"`
#     and `backend`: a provider block here would be ignored or would fight the
#     caller's configuration, and it would hard-wire a region into a module
#     that has to stay reusable across regions. A module cannot declare a
#     backend at all, because state belongs to the calling root.
#   - Trade-offs: because no provider block exists here, no `default_tags`
#     exists either, so main.tf must apply tags PER RESOURCE from `var.tags`
#     rather than inheriting them. The absence below is what creates that
#     obligation, so the two files have to be read together.
#   - Alternatives Considered: `configuration_aliases` was evaluated and
#     rejected. Nothing in this module targets a second region or a second
#     account, and declaring an alias would force every caller to pass an
#     explicit provider map for no gain. `experiments`, `cloud` and
#     `provider_meta` are absent for the same reason: nothing here needs them.
#   - Trade-offs: exactly three providers are declared and no more. A fourth entry
#     that no resource consumed would be reported by tflint's
#     terraform_unused_required_providers rule, so this list is kept as a
#     precise statement of what the module actually uses.
# =============================================================================

terraform {
  # WHY : Assumptions: 1.15.0 is the language level this whole tree is
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
    # WHY : Assumptions: one AWS provider major-line constraint is shared across
    #       the entire infra/ tree so that composed modules have an intersecting
    #       range. Terraform selects one exact version satisfying every
    #       constraint in the configuration. The 6.56 floor is inherited from
    #       the sibling
    #       aurora-postgresql module, which needs >= 5.81.0 to express a
    #       zero-minimum Aurora Serverless capacity range; 6.56 clears that
    #       comfortably, and matching it here keeps this module composable
    #       with that sibling.
    # WHY : Trade-offs: `~> 6.56` means `>= 6.56.0, < 7.0.0`, so later 6.x
    #       minor releases remain compatible with this module contract. The
    #       adjacent `.terraform.lock.hcl` currently selects 6.57.1 and records
    #       its checksums; CI uses `-lockfile=readonly`, so any exact-version or
    #       checksum change is a reviewed lock-file diff rather than an ambient
    #       upgrade. An exact constraint here was rejected because it would
    #       duplicate the lock's responsibility in every module.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # WHY : Alternatives Considered: the alternative was to omit this entry
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

    # WHY : Assumptions: the rotation Lambda source is committed as readable
    #       Python and packaged deterministically during planning. Checking in a
    #       binary zip would hide the reviewed source behind an opaque artifact;
    #       a local-exec zip command would make correctness depend on whichever
    #       shell utilities happen to exist on the apply host.
    archive = {
      source  = "hashicorp/archive"
      version = "~> 2.7"
    }
  }
}
