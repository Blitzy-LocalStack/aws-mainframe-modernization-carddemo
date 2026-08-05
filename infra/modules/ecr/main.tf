# =============================================================================
# infra/modules/ecr/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the ten Amazon ECR container repositories that hold every
#   deployable artifact of the migrated CardDemo application -- the eight
#   Spring Boot services, the browser SPA and the ETL image -- one repository
#   per artifact, each independently versioned, scanned and retained.
#
#   What those ten replace is ONE shared z/OS load library. The online CICS
#   region reached `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` through two library
#   handles, `CARDDLIB` at app/csd/CARDDEMO.CSD:L489 and `COM2DOLL` at :L494,
#   both naming that identical dataset at :L491 and :L496; and eleven of the
#   thirty-eight batch jobs under app/jcl/ named the same dataset as a
#   STEPLIB. A single library therefore held every executable module the
#   online region and the batch tier could run, with no per-artifact
#   isolation, retention or scanning available to any of them. This file is
#   where that one store becomes ten separable ones.
#
# Parameters:
#   None declared here. This file CONSUMES inputs and declares none; all ten
#   live in infra/modules/ecr/variables.tf with their type, default,
#   nullability and `description`. The ones read here, and what each decides:
#     repository_names ............ which artifacts get a repository
#     name_prefix, environment .... the two segments of the composed name
#     image_tag_mutability ........ whether a pushed tag may be repointed
#     scan_on_push ................ registry-side vulnerability scanning
#     kms_key_arn ................. customer-managed key, or null for AES256
#     max_image_count ............. how many tagged images are retained
#     untagged_image_expiry_days .. when an untagged image becomes eligible
#     force_delete ................ whether destroy may delete stored images
#     tags ........................ per-module tags, additive to the root's
#
# Return values:
#   No `output` blocks; those live in infra/modules/ecr/outputs.tf. What this
#   file produces for that file to publish is, per repository, four
#   registry-computed attributes: `repository_url` -- what the deployment
#   workflow pushes to and what a container task definition names -- plus
#   `name`, `arn` and `registry_id`. Every one of them is computed at apply
#   time by the registry, which is why none of them is composed by hand
#   anywhere in this module.
#
# Exceptions / errors:
#   - Apply fails with an already-exists error on the repository when two
#     roots are applied into one account and region carrying the same
#     `environment` value. A registry namespace is scoped per account and per
#     region, so the environment segment composed in the locals block below
#     is the only thing holding the two roots apart; give both the same value
#     and all ten names collide at once.
#   - Apply fails on the lifecycle policy when the encoded document is
#     malformed or its counts fall outside what the registry accepts. The
#     repository is created before its policy is attached, so this leaves a
#     repository with no retention policy rather than no repository; a re-run
#     after correcting the input attaches the policy without recreating
#     anything. The `validation` blocks in variables.tf exist to turn most of
#     this class of failure into a plan-time message instead.
#   - `terraform destroy` fails on a repository that still holds images while
#     `var.force_delete` is false. That failure is deliberate rather than
#     incidental; the reasoning is recorded on the argument itself.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the baseline registration was not idempotent.
#     Re-running the deck that defined the load library first required an
#     operator to manually uncomment a destructive `DELETE GROUP(CARDDEMO)`
#     -- the instruction sits at app/jcl/CBADMCDJ.jcl:L38 and the
#     commented-out command at :L42 -- and that deck defines only `COM2DOLL`
#     at :L44-L45, the very handle the exported region shows
#     `STATUS(DISABLED)`, so the deploy deck and the live region had already
#     drifted apart. A declarative apply converges on re-run instead of
#     needing a destructive prelude, and idempotency is an acceptance
#     criterion for this infrastructure package.
#   - Assumptions: this directory is a MODULE, never a root. It is consumed
#     as `source = "../../modules/ecr"` by infra/envs/dev and
#     infra/envs/prod, so it is initialised and validated only through one of
#     those roots, and every input above is supplied by one of them.
#   - Trade-offs: the resource set here is deliberately just two resource
#     types. infra/.terraform-docs.yml generates the `resources` table it
#     injects into this module's README from exactly what is declared in this
#     file, so any resource added is also a README rendered stale and a
#     drift check reddened. Five resources a reader might reasonably expect
#     and will not find are each recorded at the point they would have been
#     written, because an omission a reviewer would look for is itself a
#     decision.
# =============================================================================

# No `provider "aws"` block here, and none anywhere in this directory.
# Assumptions: a reusable module inherits the provider its calling root has
# already configured. Declaring one here would hard-bind this module to a
# single region and a single `default_tags` set, so infra/envs/dev and
# infra/envs/prod could not drive it with different configuration -- the reuse
# this module exists to provide. infra/modules/ecr/versions.tf records the
# same decision beside the version constraints it does declare, and the `tags`
# argument below exists because of it.

locals {
  # WHY : Alternatives Considered: composing the prefix and the environment at
  #       each site that needs a repository name instead of once here.
  #       Rejected because a name assembled in ten places has ten places to
  #       edit and nine chances to be edited incompletely, and the baseline
  #       carries the matching failure on record: two library handles,
  #       `CARDDLIB` at app/csd/CARDDEMO.CSD:L489 and `COM2DOLL` at :L494,
  #       were both left pointing at the same dataset with opposite `STATUS`,
  #       one registered by a batch deck (`CHANGEAGENT(CSDBATCH)` at :L493)
  #       and one through an interactive API (`CHANGEAGENT(CSDAPI)` at :L498).
  #       One-place composition is also the instinct the baseline itself
  #       showed where it did parameterise: app/jcl/CBADMCDJ.jcl:L25 sets
  #       `HLQ` once and :L45 consumes it once as `DSNAME01(&HLQ..LOADLIB)`.
  #       Alternatives Considered: one shared registry with images promoted
  #       from dev to prod, and therefore no environment segment at all.
  #       Rejected on a sharper ground than the name collision that would
  #       follow from it: this module is instantiated once per environment
  #       root, so a shared registry would place one set of resource
  #       addresses under two Terraform states, each apply contesting the
  #       other's, and a dev push could overwrite the exact image prod is
  #       running. The environment segment is what keeps the two roots
  #       provisioning disjoint namespaces from identical topology, and it
  #       matches how the rest of this package names per-environment
  #       resources.
  #       Assumptions: no account identifier belongs in this name. A registry
  #       namespace is already unique per account and per region, so an
  #       account identifier would disambiguate nothing. The contrast worth
  #       knowing is infra/bootstrap, which composes its state bucket name
  #       WITH an account identifier precisely because the object-storage
  #       namespace is global; a repository name sits in the other category,
  #       alongside the lock table bootstrap names without one. A literal
  #       account identifier is additionally forbidden anywhere in this
  #       package.
  #       Assumptions: the registry treats `/` as a namespace separator
  #       WITHIN a repository name, so `<prefix>-<environment>/<artifact>` is
  #       one repository whose name happens to contain a slash -- not a path
  #       whose parent has to be created first, and not two nested objects.
  #       Trade-offs: a map keyed by the bare artifact name and valued by the
  #       namespaced one, rather than a set of namespaced names. The key is
  #       what every resource address and every output below is keyed by, so
  #       the deployment workflow and the container service definitions look
  #       a repository up by the artifact they are deploying instead of
  #       re-deriving a string. The accepted cost is that `each.key` and
  #       `each.value` mean different things below and a reader has to notice
  #       which is which.
  repository_names = {
    for name in var.repository_names :
    name => "${var.name_prefix}-${var.environment}/${name}"
  }
}

# No `data "aws_caller_identity"` here, and no data source of any kind.
# Assumptions: nothing in this module needs to know the account it is applying
# into. The composed name above needs no account identifier, and the
# repository address a pusher or a task definition uses is the registry's own
# computed `repository_url` attribute, published by outputs.tf.
# Alternatives Considered: reading the caller identity and assembling that
# address by hand from the account identifier and the region. Rejected twice
# over. A hand-assembled address is a second, independently maintained copy of
# something the registry already returns, so the day either the account or the
# region it was built from stops matching the repository, a push authenticates
# successfully and then targets a registry that holds nothing -- a failure that
# reads as a missing image rather than as a wrong address. And an unreferenced
# data source fails the gating unused-declaration lint rule outright. Having
# the account identifier in scope at all is the temptation that produces the
# hand-assembled form, so it is not fetched.

resource "aws_ecr_repository" "this" {
  # WHY : Alternatives Considered: `count`, and ten near-identical `resource`
  #       blocks. `count` addresses instances by position, so removing one
  #       artifact from the middle of the collection renumbers every instance
  #       after it and Terraform reads the renumbering as destroy-and-recreate
  #       of repositories that did not change -- which for this resource means
  #       deleting stored images. Iterating a collection whose keys are the
  #       artifact names gives each repository a permanent address that no
  #       reordering can move. Ten hand-written blocks were rejected for the
  #       reason recorded on the locals block above: they put one setting in
  #       ten places and hand it somewhere to diverge.
  #       Assumptions: there are exactly TEN artifacts, and `common-lib` is
  #       not one of them. services/ holds NINE Maven modules and the ninth is
  #       `common-lib`, a compile-time library resolved inside each service's
  #       own Maven build stage: its subtree carries a POM, a README and Java
  #       sources but no Dockerfile, so no image is ever built from it. Eight
  #       service images plus ui/Dockerfile plus data-migration/Dockerfile is
  #       where ten comes from. Adding that ninth Maven module here would
  #       provision an eleventh repository no pipeline ever pushes to and no
  #       task definition ever pulls from, and nothing would report it -- the
  #       apply would succeed.
  for_each = local.repository_names

  # WHY : Assumptions: `each.value` is the namespaced name and `each.key` the
  #       bare artifact name, per the map built above. The registry stores the
  #       namespaced form, while every resource address, policy lookup and
  #       output key in this module uses the bare one.
  name = each.value

  # WHY : Alternatives Considered: `MUTABLE`, rejected on two independent
  #       grounds. First, the consumer's contract: the deployment workflow
  #       tags every image by commit SHA and never by a moving `latest`, so
  #       under a repointable tag a container task definition would name an
  #       artifact that can change beneath it and "redeploy the revision that
  #       was working" would stop identifying anything. Second, that is the
  #       baseline's measured failure mode rather than a hypothetical one:
  #       `CARDDLIB` (app/csd/CARDDEMO.CSD:L489, `STATUS(ENABLED)` at :L490)
  #       and `COM2DOLL` (:L494, `STATUS(DISABLED)` at :L495) both named the
  #       identical `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` at :L491 and :L496 at
  #       an equal `RANKING(50)`, and `RANKING` is the library search-order
  #       value -- so which copy of a module actually ran turned on ranking
  #       and enable state rather than on the identity of the artifact. An
  #       immutable tag resolves to one image digest permanently, which
  #       removes that entire question instead of answering it once.
  #       Trade-offs: a failed push cannot be retried under the same tag, so
  #       the pipeline must always tag with a fresh commit SHA. That
  #       constraint is accepted deliberately -- it is exactly what makes a
  #       tag trustworthy as an artifact identity -- and it is why the
  #       retention rule below bounds the tag set by count rather than
  #       relying on tag reuse to cap it.
  image_tag_mutability = var.image_tag_mutability

  # WHY : Trade-offs: clean teardown is an acceptance criterion for this
  #       package -- `terraform apply` must provision the stack and
  #       `terraform destroy` must remove it -- and `true` is the shortest
  #       route to satisfying it against a repository that still holds
  #       images. It is passed through rather than fixed true here because
  #       the images in these repositories ARE the deployable artifacts: a
  #       destroy that silently discarded them would leave nothing to
  #       redeploy from. The input defaults false, so such a destroy fails on
  #       the non-empty repository and names what is about to be lost while
  #       it can still be prevented; docs/runbooks/teardown.md carries the
  #       deliberate emptying step for the case where losing them is the
  #       intent, and turning this on is then a per-environment decision
  #       visible in that root's variables. infra/bootstrap makes the same
  #       call for its state bucket, so the package answers "may destroy
  #       delete data?" identically everywhere.
  #       Alternatives Considered: `lifecycle { prevent_destroy = true }` on
  #       this resource. Rejected because it would make the teardown
  #       acceptance criterion unmeetable -- no destroy of the environment
  #       could succeed whatever the operator intended, and the block cannot
  #       be overridden from a variable -- whereas this argument leaves the
  #       decision with the caller and records it where a diff shows it.
  force_delete = var.force_delete

  # WHY : Assumptions: root-level and module-level tags compose additively
  #       rather than one replacing the other. The calling root configures
  #       `default_tags` on its own `provider "aws"` and every repository
  #       created here inherits those keys at apply time; this argument
  #       carries only what this module adds on top of them, which is why an
  #       empty map is a correct value and not an untagged deployment.
  #       Alternatives Considered: leaning on the root's provider
  #       `default_tags` alone and setting no `tags` here, which is what
  #       infra/bootstrap does. Rejected because bootstrap is a root and owns
  #       a `provider` block, whereas this directory is a module and
  #       deliberately declares none -- so provider-level `default_tags` is
  #       simply not available inside it, and an explicit argument is the only
  #       mechanism left.
  tags = var.tags

  # WHY : Assumptions: the registry performs the vulnerability scan itself,
  #       SERVER-SIDE, at the moment an image is pushed, and its findings are
  #       then read from the registry rather than gated in the pipeline that
  #       pushed. That is recorded here because it is the reason no separate
  #       container-image-scanning step appears in the service build workflow
  #       or in the deployment workflow: their absence is a consequence of
  #       this setting, and a reader who does not know that will add a
  #       redundant scanning step to continuous integration. The same
  #       mechanism bounds what this setting can promise -- a finding surfaces
  #       after the push has already succeeded, so it is not a push-blocking
  #       control and must not be relied on as one.
  image_scanning_configuration {
    scan_on_push = var.scan_on_push
  }

  # No `aws_ecr_registry_scanning_configuration` resource in this module.
  # Alternatives Considered: configuring scanning at the registry level, which
  # is where scan rules and repository filters are set. Rejected because that
  # resource is account-wide and region-wide rather than per repository, while
  # this module is instantiated by two environment roots: both roots would
  # then declare the same single registry-level object in two separate
  # Terraform states, and each apply would revert the other's, indefinitely
  # and without either state recording a conflict. The repository-level
  # scan-on-push argument above is per repository and is therefore safe to
  # declare from either root, which is also exactly what this module's brief
  # asks for.

  # WHY : Refactoring Rationale: nothing in the baseline was encrypted at
  #       rest, so this block ports nothing -- it is a documented
  #       improvement. All eight file resources in app/csd/CARDDEMO.CSD, the
  #       stanzas opening at L1, L13, L25, L37, L50, L63, L76 and L88, are
  #       defined `RECOVERY(NONE)` over a plain `DSNAME(...)`, and the two
  #       load-library stanzas at L489-L496 that between them held every
  #       executable module the region could run carry no encryption
  #       attribute of any kind.
  #       Refactoring Rationale: the former nullable branch silently selected
  #       registry-managed AES256 when a caller forgot the key. Every complete
  #       root already provisions the shared data CMK, so treating its absence
  #       as a valid mode made the documented architecture optional.
  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }
}

# No `aws_ecr_repository_policy` resource in this module, and no repository
# policy of any kind.
# Alternatives Considered: attaching a policy to grant another account pull
# access, which is the usual reason one appears. Rejected because nothing in
# this architecture needs it: the deployment workflow pushes from inside the
# same account under a short-lived federated role, and the container task
# execution roles pull from inside the same account as well, so both paths are
# already authorised by their own identity policies. The absence is also the
# compliance -- the infrastructure pipeline's policy scan checks specifically
# that no public repository policy exists, so a policy added here "to let
# something else pull" reddens that gate rather than merely widening access.
# Should a genuine cross-account consumer ever exist, that belongs in a
# reviewed change to this file rather than in an out-of-band edit no state
# file knows about.

resource "aws_ecr_lifecycle_policy" "this" {
  # WHY : Assumptions: driving this from the SAME `local.repository_names` map
  #       as the repository above is what guarantees exactly one policy per
  #       repository. Iterating `aws_ecr_repository.this` directly would read
  #       a little more plainly but would derive these keys from a resource;
  #       taking both from one source collection makes a key that exists in
  #       one resource and not the other impossible to write.
  for_each = local.repository_names

  # WHY : Alternatives Considered: `each.value`, which holds the identical
  #       string -- the map above composed it and the repository was named
  #       from it -- and would read more briefly here. Rejected because
  #       Terraform derives its dependency graph from references, so
  #       `each.value` would leave this policy with no edge to the repository
  #       it attaches to: the two could be created in either order, and an
  #       attach against a repository that does not exist yet fails the apply
  #       for a reason that looks like a registry error rather than an
  #       ordering one. Reading the attribute off the resource states the
  #       dependency the value alone cannot.
  repository = aws_ecr_repository.this[each.key].name

  # WHY : Alternatives Considered: a heredoc holding the JSON literally.
  #       Rejected because a heredoc is an opaque string to Terraform -- a
  #       trailing comma, a mistyped key or a stray indent survives
  #       `terraform validate` untouched and is refused by the registry at
  #       apply time, after the repository already exists. `jsonencode` is
  #       handed an HCL structure that `validate` type-checks and cannot emit
  #       invalid JSON, and it renders the two counts below as JSON numbers
  #       rather than as the quoted strings the registry would reject.
  policy = jsonencode({
    rules = [
      {
        # WHY : Assumptions: an untagged manifest is UNREFERENCEABLE rather
        #       than merely old, which is why age is the right measure for it
        #       while tagged images are bounded by count below. One appears
        #       when a push uploads a manifest that never receives a tag --
        #       an interrupted push, a build-cache manifest, a child manifest
        #       of a multi-platform index -- or, where a caller has
        #       overridden the mutability argument above, when a tag is moved
        #       off the image it used to name. No container task definition
        #       can reference any of those, so expiring them by age reclaims
        #       storage without touching anything deployable, and the window
        #       is long enough that an aborted push can still be inspected
        #       and its layers reused by a retry.
        #       Trade-offs: this rule is evaluated FIRST, and the order is
        #       load-bearing rather than cosmetic. The rule below selects
        #       `any`, so were it evaluated first it would sweep untagged
        #       manifests on their position within the retained count and
        #       this age window would never decide anything. The registry
        #       additionally requires the `any` rule to carry the highest
        #       priority in the document, so ascending priority and correct
        #       evaluation order are the same arrangement here.
        rulePriority = 1
        description  = "Expire untagged images older than the configured window; no container task definition can reference an untagged manifest."
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.untagged_image_expiry_days
        }
        action = {
          type = "expire"
        }
      },
      {
        # WHY : Assumptions: the deployment workflow tags every image with its
        #       commit SHA, so each deployment adds a NEW tag rather than
        #       moving an existing one -- which is precisely what the
        #       immutable-tag argument above guarantees. The consequence is
        #       that the tagged set grows monotonically, by one entry per
        #       deployment, with nothing capping it. A count bound is the
        #       right mechanism for exactly that reason: it keeps the N most
        #       recent deployable artifacts, which is the set a rollback
        #       selects from, whereas an age bound would delete the last
        #       known-good image of a repository that simply has not been
        #       deployed lately -- the one image a rollback most needs.
        #       Alternatives Considered: `tagStatus = "tagged"` narrowed by a
        #       tag prefix or pattern list, leaving untagged manifests
        #       entirely to the rule above. Rejected because commit SHAs share
        #       no prefix and match no useful pattern, so such a filter would
        #       have nothing meaningful to hold; `any` at the highest priority
        #       reaches the same images anyway, because the rule above has
        #       already claimed the untagged ones.
        #       Bounded retention is a preserved discipline rather than a new
        #       one: the baseline capped each of its ten generation-dataset
        #       bases at five generations. The analogy stops at the principle,
        #       since those limits governed datasets and not images.
        rulePriority = 2
        description  = "Retain only the configured number of most recent images, bounding a tag set that grows by one entry per deployment."
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = var.max_image_count
        }
        action = {
          type = "expire"
        }
      },
    ]
  })
}

# No `aws_ecr_replication_configuration`, no `aws_ecr_pull_through_cache_rule`
# and no cross-region resource of any kind in this module.
# Assumptions: the target topology is single-region across three availability
# zones, and multi-region and disaster-recovery topology is explicitly out of
# scope for this migration. Replication has no destination to replicate to
# under that topology, and a pull-through cache would introduce an upstream
# registry as a build-time dependency of every image these repositories hold,
# which is a supply-chain change no requirement asks for.
