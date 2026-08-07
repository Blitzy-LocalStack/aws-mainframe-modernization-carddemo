# =============================================================================
# infra/modules/secrets/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Fixes the toolchain and provider contract for the `secrets` module: the
#   minimum Terraform language level the configuration relies on, and the exact
#   provider majors whose resource schemas variables.tf, main.tf and outputs.tf
#   are written against. The module generates its service database passwords at
#   apply time, so the random provider is a genuine requirement rather than an
#   incidental one; it packages no deployment artifact and imports no
#   certificate material, so it needs no third provider.
#
#   Assumptions: this directory is a reusable MODULE, not a root. It is invoked
#   as `source = "../../modules/secrets"` from the dev and prod environment
#   roots, and those roots own the only `provider` blocks -- hence the deliberate
#   absence of `provider "aws"`, `provider "random"` and `backend`. A provider
#   block here would be ignored or would fight the caller's configuration and
#   would hard-wire a region into a module that has to stay reusable; a module
#   cannot declare a backend at all, because state belongs to the calling root.
#   `configuration_aliases` is absent for the same class of reason: nothing here
#   targets a second region or account, so an alias would force every caller to
#   pass an explicit provider map for no gain.
#
#   Trade-offs: because no provider block exists here, no `default_tags` exists
#   either, so main.tf must apply tags PER RESOURCE from `var.tags` rather than
#   inheriting them -- the absence here is what creates that obligation, so the
#   two files have to be read together. Exactly two providers are declared and
#   no more, matching the two the whole infra/ tree admits; a third entry no
#   resource consumed would be reported by tflint's
#   terraform_unused_required_providers rule.
#
#   Refactoring Rationale: `hashicorp/archive` is absent because rotation of a
#   secret VALUE is not this module's remit. A root that supplies a rotation
#   function through `rotation_lambda_arn` packages it in its own configuration,
#   where it already packages the Lambdas the batch state machine invokes.
# =============================================================================

terraform {
  # Trade-offs: deliberately `>=` and not `~>`. The CLI is supplied by the
  #   operator or the CI runner rather than resolved from a registry, so a
  #   ceiling here would reject a newer CLI a calling root legitimately
  #   standardises on; the floor still turns a version mismatch into one clear
  #   error at `init` rather than a confusing parse failure on an older CLI.
  #   1.15.0 is the language level this tree is authored against and 1.15.8 is
  #   what it is validated on. The providers below are constrained the opposite
  #   way, because a provider IS resolved from a registry.
  required_version = ">= 1.15.0"

  required_providers {
    # Assumptions: one AWS provider constraint string is shared verbatim across
    #   the whole infra/ tree so that composed modules have an intersecting range
    #   -- Terraform selects one exact version satisfying every constraint in the
    #   configuration. The 6.56 floor is inherited from the sibling
    #   aurora-postgresql module, which needs >= 5.81.0 to express a zero-minimum
    #   Aurora Serverless capacity range.
    # Trade-offs: `~> 6.56` admits later 6.x minors but refuses 7.0.0, so a
    #   provider major cannot change the resource schemas this module is written
    #   against between one `init` and the next. An exact constraint was rejected
    #   because it would duplicate in every module the responsibility the
    #   adjacent `.terraform.lock.hcl` already carries, and CI reads that lock
    #   with `-lockfile=readonly` so a version change is a reviewed diff.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }

    # Alternatives Considered: omitting this entry, as infra/bootstrap correctly
    #   does because nothing in that root generates a random value. Rejected for
    #   the mirror-image reason: main.tf declares `random_password` resources, so
    #   without this entry `terraform init` cannot resolve them and the module
    #   fails to initialise at all. Generating those passwords at apply time
    #   straight into Secrets Manager is what keeps every credential out of
    #   version control, so this entry carries that guarantee.
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
  }
}
