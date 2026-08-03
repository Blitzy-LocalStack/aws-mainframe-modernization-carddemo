# =============================================================================
# infra/modules/s3-datasets/versions.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Toolchain and provider contract for the `s3-datasets` module: the oldest
#   Terraform CLI able to parse and plan this module, and the one provider it
#   requires. The module's other four files (main.tf, variables.tf, outputs.tf
#   and README.md) are authored and validated against this surface.
#
#   The module provisions the versioned, encrypted S3 bucket that carries the
#   mainframe baseline's TEN generation-data-group families -- six defined in
#   app/jcl/DEFGDGB.jcl, three in app/jcl/DEFGDGD.jcl and one in
#   app/jcl/DALYREJS.jcl, every one at LIMIT(5) SCRATCH -- as dataset prefixes
#   keyed by domain, dataset, date and generation, plus a lifecycle rule
#   retaining five noncurrent versions as the LIMIT(5) analogue.
#
#   This is a reusable MODULE and is never applied on its own. It is called as
#   `source = "../../modules/s3-datasets"` from the infra/envs/dev and
#   infra/envs/prod roots, so the two constraints below are enforced
#   transitively whenever one of those roots is initialised.
#
#   WARNING: the bucket this module creates is NOT the Terraform remote-state
#   bucket. Remote state is owned solely by infra/bootstrap, which is applied
#   once per AWS account out of band. Both are versioned, encrypted S3
#   buckets, which is exactly why they are easy to confuse -- and why aiming
#   one at the other would put application datasets and Terraform state in a
#   single blast radius.
#
# Parameters:
#   None. This file declares no `variable` -- module inputs live in
#   variables.tf. That split is the five-file shape every module under
#   infra/modules follows, and it keeps this contract legible on its own.
#
# Return values:
#   No `output` either; those live in outputs.tf. What this file yields is the
#   constraint contract itself: the `required_version` and `required_providers`
#   entries that tflint's terraform_required_version and
#   terraform_required_providers rules assert are present, and that
#   terraform-docs renders into the Requirements and Providers tables of
#   infra/modules/s3-datasets/README.md. A version edited here leaves that
#   generated table stale until the README is regenerated.
#
# Errors:
#   `terraform init` fails before downloading anything when the CLI is older
#   than the floor below. Provider installation fails when no released
#   hashicorp/aws version satisfies the constraint below. Declaring a
#   `provider` block in this file would raise a duplicate provider
#   configuration error in the calling root at init time.
#
# WHY (non-obvious design decisions):
#   - Assumption: this directory is a module, not a root, so provider
#     configuration -- region, default tags -- is inherited from whichever
#     root calls it. `required_providers` states WHAT provider the module
#     needs, while the root decides HOW it is configured. Note the deliberate
#     contrast with infra/bootstrap/versions.tf, which does configure the
#     provider inline: bootstrap is a root, not a module, so the asymmetry
#     between the two files is intended and neither one is wrong.
#   - Alternatives Considered: configuring the provider inline here was
#     rejected. A module-level provider configuration either collides with
#     the calling root's or silently becomes a second, differently configured
#     one, and it would bind the module to a single region and tag set --
#     defeating the reuse across the dev and prod roots that is the whole
#     reason this code is a module.
#   - Assumption: hashicorp/random is deliberately absent even though
#     infra/README.md pins it for the package and infra/modules/secrets
#     genuinely uses it to generate credentials at apply time. Nothing here
#     draws a random value: the bucket name is composed deterministically in
#     main.tf from the name prefix, environment, account id and region. The
#     unused-declaration rules gating .github/workflows/infra-ci.yml --
#     terraform_unused_declarations, and terraform_unused_required_providers
#     for a provider entry specifically -- fail the build on a provider that
#     is declared and never used, which is exactly what an idle random entry
#     would be here; infra/bootstrap/versions.tf omits it for that reason.
# =============================================================================

terraform {
  # Trade-off: a floor, not an exact pin. It admits any newer 1.x CLI so a
  # developer or runner already on a later patch is not blocked, while still
  # rejecting a CLI too old to parse this configuration. 1.15.8 is the version
  # the whole infra/ package was validated on and the version CI installs; the
  # floor is deliberately looser than that pin because the two do different
  # jobs -- CI guarantees one reproducible validation, the floor guarantees a
  # minimum capability. Pinning `= 1.15.8` here was rejected: it would force
  # every consumer of this module onto a single patch release.
  required_version = ">= 1.15.0"

  required_providers {
    # Assumption: `~>` on a two-segment constraint resolves to >= 6.56, < 7.0,
    # admitting 6.56.x and any later 6.y minor while excluding the 7.0 major.
    # Major provider releases rename and remove resource arguments, so that
    # upgrade stays a deliberate, reviewable edit instead of arriving with the
    # next `init`. The 6.56 floor is a package-wide pin rather than a need of
    # this module: a sibling module requires a provider new enough to accept a
    # zero minimum Aurora Serverless capacity (floor 5.81.0), and every
    # directory under infra/ carries the same constraint so they all resolve
    # one provider version. 6.56 clears that floor with room to spare.
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.56"
    }
  }
}
