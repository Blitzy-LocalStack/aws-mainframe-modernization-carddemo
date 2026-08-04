# =============================================================================
# infra/modules/ecr/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the reusable `ecr` Terraform module, which
#   provisions the ten Amazon ECR container repositories that replace the one
#   shared z/OS CICS load library the whole CardDemo application executed
#   from -- `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)`, reached through two separate
#   library handles at app/csd/CARDDEMO.CSD:L491 and :L496. Every value the
#   module reads is declared here and nowhere else, so this file is the
#   module's published contract with its callers.
#
#   Like every directory under infra/modules/, this one is never applied on
#   its own. It is consumed as `source = "../../modules/ecr"` by the
#   infra/envs/dev and infra/envs/prod roots, so each input below is supplied
#   by one of those two roots.
#
# Parameters -- the ten `variable` blocks below, each carrying its own name,
# explicit `type` and `description`, grouped by what it controls:
#   Repository set ...... repository_names
#   Name composition .... name_prefix, environment
#   Repository policy ... kms_key_arn, image_tag_mutability, scan_on_push
#   Retention ........... max_image_count, untagged_image_expiry_days
#   Destroy behaviour ... force_delete
#   Metadata ............ tags
#
#   Each block's `description` is the authoritative per-parameter text rather
#   than a summary of one: infra/.terraform-docs.yml injects those exact
#   strings into this module's README, so the two cannot drift apart without
#   reddening the generated-documentation check.
#
# Return values:
#   None. A variables file declares what a module accepts and returns nothing
#   itself. The module's return surface -- for each of the ten repositories
#   its URL, name, ARN and registry identifier, all of them attributes the
#   registry computes at apply time -- lives in infra/modules/ecr/outputs.tf.
#   Recorded explicitly rather than omitted, so a reader can tell "this file
#   returns nothing" apart from "this file's return values went undocumented".
#
# Exceptions / errors:
#   - A `validation` block below fails during `terraform plan`, before any
#     resource is touched, printing the `error_message` written beside it.
#     That is the whole reason those blocks exist. Without them an
#     out-of-range value travels to the registry API and is refused at `apply`
#     time by a provider-surfaced error naming neither the variable nor the
#     acceptable range -- possibly after other resources in the same apply
#     have already been created, leaving the environment half-provisioned.
#   - `environment` declares no default, so `plan` stops with a missing
#     required variable when a calling root omits it. That is deliberate; the
#     comment on that variable records why a default would be wrong.
#   - Nothing here can raise at `apply` time. These are declarations, not
#     resources.
#
# WHY (non-obvious design decisions):
#   - Assumptions: every variable declared here is read by
#     infra/modules/ecr/main.tf. That is not tidiness. infra/.tflint.hcl
#     enables `terraform_unused_declarations` (L304) under `force = false`
#     (L97), so an input nothing consumes fails the gating lint step outright.
#     A speculative "might be useful" knob is therefore a build break, which
#     is why the set below is exactly ten and no wider.
#   - Trade-offs: the defaults carry application knowledge -- the ten
#     repository names, the prefix, the retention bounds -- into a module that
#     is nominally generic. Requiring every input from each root was the
#     alternative, and it was rejected because it would restate the identical
#     ten names in infra/envs/dev and infra/envs/prod and hand them somewhere
#     to diverge unnoticed. Each default stays overridable per root, so the
#     cost is confined to this file.
#   - Assumptions: every block below states `nullable` explicitly, and that is
#     load-bearing rather than decorative. Terraform does not read an
#     explicitly passed null as an omission: left at its own true default,
#     `nullable` lets null BECOME the variable's value even where a default
#     exists, so the default is defeated without the caller intending it.
#     `nullable = false` restores the intended reading -- null resolves to the
#     declared default -- and on `environment`, which declares none, it turns a
#     null into a `plan`-time "required variable may not be set to null"
#     instead. Each site records what null would otherwise have done there,
#     because the consequences genuinely differ: an error raised inside this
#     module's own resources rather than at the caller that supplied the null,
#     a silently reversed setting, or an unparseable lifecycle policy.
#   - Refactoring Rationale: the baseline had no typed, validated contract of
#     any kind for its artifact store. That store was registered by a
#     hand-edited batch deck, app/jcl/CBADMCDJ.jcl:L44-L45, whose re-run first
#     required manually uncommenting a destructive `DELETE GROUP(CARDDEMO)` at
#     L42, and the registration it produced had already drifted from what the
#     live region held. Declared inputs that fail at `plan` are what replace
#     that.
# =============================================================================

# WHY : Assumptions: nine Maven modules under services/ yield only EIGHT
#       images. `common-lib` is that ninth module and its absence from the
#       list below is deliberate, not an oversight: its subtree is a POM plus
#       Java sources with no Dockerfile of its own, because it is a
#       compile-time dependency resolved inside each service's Maven build
#       stage rather than a deployable artifact. Adding it would create an
#       eleventh repository that no pipeline ever pushes an image to. The ten
#       entries are exactly the ten Dockerfiles this migration authors: eight
#       at services/<service>/Dockerfile, plus ui/Dockerfile and
#       data-migration/Dockerfile.
#       Alternatives Considered: a `list(string)` instead of a set. Rejected
#       because main.tf drives `for_each` from this collection, and `for_each`
#       over a set keys each instance by its own element value, so a
#       repository's resource address is stable however the collection is
#       ordered. A list invites index-based reasoning, and under it moving one
#       entry shifts every address after it, which Terraform reads as destroy
#       and recreate. A set also makes uniqueness a property of the type
#       rather than something a validation has to assert.
#       Alternatives Considered: deriving the list from the working tree, for
#       instance by globbing for Dockerfiles. Rejected because a `plan` would
#       then depend on which files happen to be checked out rather than on
#       state and inputs alone, so the same configuration would produce
#       different repositories on different machines.
variable "repository_names" {
  description = "Trailing name segment of each container image repository to create, one per deployable artifact; main.tf namespaces each entry as `<name_prefix>-<environment>/<entry>`. Defaults to the ten deployables of this migration: the eight services plus the browser SPA and the ETL image."
  type        = set(string)

  # WHY : Trade-offs: shipping the ten names as a default rather than demanding
  #       them from each root. The cost is that a generic-looking module knows
  #       its application; the benefit is one authoritative list instead of
  #       two copies in two root configurations, which is where the
  #       `CARDDLIB`/`COM2DOLL` divergence recorded in the header began. The
  #       list stays overridable per root, so no caller is locked to it.
  default = [
    "auth-service",
    "account-service",
    "card-service",
    "transaction-service",
    "reference-service",
    "batch-service",
    "authorization-service",
    "reporting-service",
    "ui",
    "data-migration",
  ]

  # WHY : Assumptions: `nullable = false` makes an explicit null resolve to the
  #       ten names above rather than become the value. Left at its true
  #       default, `nullable` would let a root that threads an unset local
  #       hand main.tf a null collection, and `for_each` would fail on it --
  #       so the effect here is that an explicit null still provisions the ten
  #       repositories rather than failing.
  nullable = false

  # WHY : Trade-offs: a few lines of HCL for a readable `plan`-time failure. An
  #       empty set satisfies the type system yet would provision no
  #       repositories at all, so every subsequent image push would fail
  #       against a registry that looks correctly configured.
  validation {
    condition     = length(var.repository_names) > 0
    error_message = "repository_names must contain at least one entry; an empty set provisions no repositories, so every image push would fail against an apparently healthy registry."
  }

  # WHY : Assumptions: the TEN deployables are topology, not preference, so the
  #       set is asserted rather than merely defaulted. The migration ships
  #       exactly ten container images -- the eight Spring Boot services, the
  #       browser SPA and the ETL -- and dev and prod are required to differ only
  #       in sizing and retention, never in what exists. Before this check the
  #       exact-ten default could be replaced wholesale by any non-empty set that
  #       satisfied the name pattern, so a root could apply cleanly against nine
  #       repositories or against ten under other names, and the failure would
  #       arrive later and elsewhere: the deploy workflow pushes to a repository
  #       URI composed from the prefix and the service name, so a missing or
  #       renamed repository presents as a push failure in continuous
  #       integration, or as a task definition referencing an image that was
  #       never pushed.
  #       Trade-offs: this closes an input the module previously left open, so a
  #       genuinely new deployable now needs an edit here as well as wherever it
  #       is built. Accepted, because that is the same edit a new deployable
  #       already needs in the secrets inventory, the schema bootstrap and the
  #       edge routing, and because the alternative -- a default nothing enforces
  #       -- is what the `CARDDLIB`/`COM2DOLL` divergence recorded in this file's
  #       header is an instance of.
  #       Assumptions: equality needs both halves. The setunion test alone proves
  #       only that every name supplied is one of the ten, so a root pushing a
  #       single repository would satisfy it; the count closes that, because a
  #       set already holds no duplicates, so ten members drawn from a set of ten
  #       is that set exactly.
  validation {
    condition = length(var.repository_names) == 10 && setunion(var.repository_names, [
      "auth-service",
      "account-service",
      "card-service",
      "transaction-service",
      "reference-service",
      "batch-service",
      "authorization-service",
      "reporting-service",
      "ui",
      "data-migration",
      ]) == toset([
      "auth-service",
      "account-service",
      "card-service",
      "transaction-service",
      "reference-service",
      "batch-service",
      "authorization-service",
      "reporting-service",
      "ui",
      "data-migration",
    ])
    error_message = "repository_names must be exactly the ten deployables of this migration: auth-service, account-service, card-service, transaction-service, reference-service, batch-service, authorization-service, reporting-service, ui and data-migration. The image inventory is fixed topology, and dev and prod must not differ in it."
  }

  # WHY : Assumptions: the registry rejects a malformed repository name rather
  #       than normalising it, and it does so at `apply` time with a
  #       parameter error that quotes neither this variable nor the rule it
  #       broke. Checking the shape here converts that into a `plan`-time
  #       message an operator can act on. The pattern is the registry's own
  #       naming rule: lowercase alphanumeric segments joined by a single
  #       `.`, `_` or `-`, optionally namespaced further with `/`, and never
  #       opening or closing on a separator.
  validation {
    condition = alltrue([
      for name in var.repository_names : can(regex(
        "^[a-z0-9]+([._-][a-z0-9]+)*(/[a-z0-9]+([._-][a-z0-9]+)*)*$", name
      ))
    ])
    error_message = "Every entry in repository_names must be lowercase alphanumeric segments joined by a single `.`, `_` or `-` and optionally namespaced with `/`, as in `auth-service`; uppercase letters, spaces, and leading, trailing or repeated separators are all rejected by the registry."
  }
}

# WHY : Assumptions: this prefix, not the account itself, is what keeps
#       CardDemo repositories legible beside unrelated ones in the same
#       registry, because a registry is shared by everything in an account
#       and a region. The value matches the prefix the rest of this
#       infrastructure package composes its resource names from, so the ten
#       repositories sort next to the queues, buckets and clusters that serve
#       the same application rather than scattering among them.
variable "name_prefix" {
  description = "Application prefix that opens every repository name, keeping CardDemo repositories grouped and legible within a registry shared by every workload in the same account and region."
  type        = string

  # WHY : Trade-offs: a default makes the module usable from a root that
  #       states only its environment, at the cost of one more place the
  #       application's name appears. Accepted because the alternative --
  #       requiring it from both roots -- gives two roots two chances to spell
  #       it differently, and a mismatch there is invisible until the
  #       repositories appear under two unrelated prefixes.
  default = "carddemo"

  # WHY : Assumptions: `nullable = false` makes an explicit null resolve to the
  #       default above rather than become the value. Without it, a root
  #       threading an unset local would carry the null into main.tf's name
  #       composition, where interpolating it into a string stops `plan` with
  #       an invalid-template-interpolation error pointing at the module's own
  #       locals block -- loud, but blaming the composition rather than the
  #       input that was left null.
  nullable = false

  # WHY : Assumptions: this segment is concatenated straight into a registry
  #       repository name, so it inherits that name's character rule; the
  #       upper length bound exists because the prefix, the environment and
  #       the entry from `repository_names` share one bounded name, and an
  #       unbounded prefix is the only one of the three that could exhaust it.
  validation {
    condition = can(
      regex("^[a-z0-9]([a-z0-9-]{0,30}[a-z0-9])?$", var.name_prefix)
    )
    error_message = "name_prefix must be 1 to 32 characters of lowercase letters, digits and interior hyphens, starting and ending with a letter or digit -- for example `carddemo`."
  }
}

# WHY : Alternatives Considered: one shared registry with images promoted from
#       dev to prod, which is why this input exists and has no default. A
#       registry namespace is unique per account and per region, and both
#       infra/envs/dev and infra/envs/prod instantiate this module with
#       identical topology, differing only in sizing and retention. Without an
#       environment segment the second root applied into one account would
#       collide on all ten repository names. Promotion was rejected on a
#       sharper ground than the collision: the module is instantiated once per
#       root, so a shared registry would place one set of resource addresses
#       under two Terraform states, each apply contesting the other's, and a
#       dev push could overwrite the exact image prod is running. Deriving the
#       segment from a Terraform workspace name was also rejected -- this
#       package uses one state per root rather than workspaces, so a workspace
#       name would be unset and the namespace would silently collapse.
#       Assumptions: no account identifier belongs in the composed name. The
#       registry namespace is already scoped per account and per region, so
#       nothing is left to disambiguate, and infra/bootstrap composes its own
#       state bucket name with an account identifier only because the object
#       storage namespace is global instead. A literal account identifier is
#       additionally forbidden anywhere in this package.
#       The env-suffixed naming this drives is the package convention, not a
#       local invention: the queues, the dataset bucket and the container
#       cluster are all named per environment the same way.
variable "environment" {
  description = "Deployment environment segment of the repository namespace, `dev` or `prod`. Required with no default, because it is the only thing keeping the two environment roots from colliding on all ten repository names within one account and region."
  type        = string

  # WHY : Assumptions: because this input has no default, `nullable = false`
  #       behaves differently here than on the defaulted inputs -- it makes an
  #       explicit null stop `plan` with "required variable may not be set to
  #       null", naming this variable and the line that supplied it. Left at
  #       its true default, the null would instead reach the check below and
  #       fail as an invalid argument to `contains`, an error about a function
  #       rather than about a missing environment, which tells an operator who
  #       threaded an unset local through optional inputs nothing about what
  #       to fix.
  nullable = false

  # WHY : Assumptions: these two are the only environment roots this package
  #       defines. A third value would namespace repositories that no root
  #       ever reads and no pipeline ever pushes to, and nothing downstream
  #       would report it -- the apply would succeed.
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly `dev` or `prod`, the two roots defined under infra/envs/; any other value namespaces repositories that no root reads and no pipeline pushes to."
  }
}

# WHY : Refactoring Rationale: the baseline encrypted nothing at rest, so this
#       input has no counterpart being ported -- it is a documented
#       improvement. All eight file resources in app/csd/CARDDEMO.CSD -- the
#       stanzas opening at L1, L13, L25, L37, L50, L63, L76 and L88 -- are
#       defined `RECOVERY(NONE)` and `JOURNAL(NO)` over a plain `DSNAME(...)`,
#       and the two load-library stanzas at L489-L496, which between them held
#       every executable module the region could run, carry no encryption
#       attribute of any kind.
#       Assumptions: when a value is supplied it arrives as the `kms` module's
#       output, threaded through the calling environment root. It is never
#       written as a literal here, because module outputs are the only
#       sanctioned source of runtime identifiers in this package and a
#       hard-coded key identifier would bind the module to one account.
#       Trade-offs: null is a meaningful value rather than an error, so the
#       module stays usable before any customer-managed key exists -- which
#       matters because key creation and this module sit in the same apply. The
#       accepted cost is that a caller who forgets to wire the key silently
#       receives registry-managed `AES256` instead of failing; the mitigation
#       is the infrastructure pipeline's policy scan, which asserts that
#       encryption is configured at all.
variable "kms_key_arn" {
  description = "Customer-managed KMS key encrypting image layers at rest, supplied by the `kms` module's output through the calling root. Leave null to fall back to the registry's own `AES256` server-side encryption instead of failing."
  type        = string

  # WHY : Assumptions: this is the one input in the file where null is a CHOICE
  #       rather than an absence, which is why it is the one place `nullable`
  #       is true. main.tf branches on exactly this value: non-null selects
  #       `KMS` with the supplied key, null selects `AES256` and passes no key
  #       at all, because the registry refuses a key handed to it alongside
  #       `AES256`. Both settings are written out even though true is already
  #       the language default, so that a reader cannot mistake a meaningful
  #       null for an input somebody forgot to give a value.
  default  = null
  nullable = true
}

# WHY : Alternatives Considered: `MUTABLE`, rejected on two independent
#       grounds.
#       First, the consumer's contract. The deployment workflow tags every
#       image by commit SHA and never by a moving `latest`, because a tag that
#       can be repointed makes a container task definition irreproducible and
#       empties "redeploy the revision that was working" of meaning. Under
#       `IMMUTABLE` a tag resolves to one image digest permanently, so the
#       task definition and the artifact it names stay welded together.
#       Second, the baseline's own measured ambiguity, which is the failure
#       mode being designed out. app/csd/CARDDEMO.CSD defines TWO library
#       handles over ONE dataset: `CARDDLIB` at L489 is `STATUS(ENABLED)` with
#       `RANKING(50)` at L490, `COM2DOLL` at L494 is `STATUS(DISABLED)` with
#       the same `RANKING(50)` at L495, and both name the identical
#       `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` at L491 and L496. `RANKING` is the
#       library search-order value, so which copy of a module actually ran
#       turned on ranking and enable state rather than on artifact identity --
#       and the deploy deck at app/jcl/CBADMCDJ.jcl:L44-L45 registers only the
#       handle the exported region shows disabled, so deck and region had
#       already diverged. Immutable tags remove that entire class of question.
#       Trade-offs: a failed push cannot be retried under the same tag, so the
#       pipeline must always tag with a fresh commit SHA. That constraint is
#       accepted deliberately -- it is precisely what makes an artifact
#       identity trustworthy, and it is why `max_image_count` below rather than
#       tag reuse is what bounds the registry.
#       Alternatives Considered: also admitting the registry's exclusion-filter
#       mutability modes, which make immutability conditional on a tag pattern.
#       Rejected because they are only meaningful alongside an
#       `image_tag_mutability_exclusion_filter` block on the repository, and
#       main.tf declares none; permitting them would let a caller select a mode
#       this module cannot configure, and the resulting behaviour would be
#       neither of the two documented here.
variable "image_tag_mutability" {
  description = "Whether an existing image tag may be repointed by a later push. `IMMUTABLE` binds each tag to one image digest permanently; `MUTABLE` lets a tag be moved."
  type        = string

  # WHY : Assumptions: the default is the decision, because neither environment
  #       root has any reason to override it -- reproducible task definitions
  #       are wanted in dev as much as in prod, and a dev registry that
  #       behaved differently would make a dev rollback rehearsal prove
  #       nothing about prod.
  default = "IMMUTABLE"

  # WHY : Assumptions: `nullable = false` makes an explicit null resolve to
  #       `IMMUTABLE` above rather than become the value. Without it a null
  #       would omit the argument altogether and leave the registry on its own
  #       mutable-tag behaviour -- a silent reversal of the single guarantee
  #       this variable exists to make, with a clean apply and no finding
  #       anywhere to record that it happened.
  nullable = false

  # WHY : Assumptions: the registry takes this value uppercase and rejects any
  #       other spelling at `apply` time. The check is narrower than the
  #       registry's own accepted set on purpose: it admits only the two modes
  #       main.tf implements, excluding the exclusion-filter modes for the
  #       reason given above the block.
  validation {
    condition     = contains(["IMMUTABLE", "MUTABLE"], var.image_tag_mutability)
    error_message = "image_tag_mutability must be exactly `IMMUTABLE` or `MUTABLE`, uppercase; those are the only two modes this module configures."
  }
}

# WHY : Assumptions: the registry itself performs the vulnerability scan,
#       server-side, at the moment an image is pushed, and its findings are
#       read from the registry rather than gated in the pipeline that pushed.
#       That is recorded here because it is the reason no separate
#       container-image-scanning step appears in either the service build
#       workflow or the deployment workflow -- their absence is a consequence
#       of this setting, not an omission, and a future reader who does not know
#       that will add a redundant scanning step to CI.
#       Trade-offs: the variable exists only so a caller can switch scanning
#       off deliberately, which is why the default is the enabled state rather
#       than the neutral one. Leaving it out entirely was the alternative and
#       would have been simpler, but it would also have removed the caller's
#       ability to record such a decision anywhere reviewable.
variable "scan_on_push" {
  description = "Whether the registry scans each image for vulnerabilities server-side as it is pushed, with findings read from the registry rather than gated in the pushing pipeline."
  type        = bool

  # WHY : Assumptions: the infrastructure pipeline's policy scan asserts that
  #       scan-on-push is enabled, so `false` here would hand that gate a
  #       finding on every one of the ten repositories. The enabled default is
  #       what lets the gate pass by construction instead of by suppression.
  default = true

  # WHY : Assumptions: `nullable = false` makes an explicit null resolve to true
  #       above rather than become the value, and that matters more here than
  #       on the other flags: the provider declares `scan_on_push` a REQUIRED
  #       attribute of `image_scanning_configuration`, so a null does not fall
  #       through to some registry default -- it is refused inside main.tf's
  #       nested block, in an error naming that block rather than the input
  #       that produced it.
  nullable = false
}

# WHY : Assumptions: the deployment workflow tags every image with its commit
#       SHA, so each deployment adds a NEW tag rather than moving an existing
#       one -- which is exactly what `image_tag_mutability` above guarantees.
#       The consequence is that the tagged set grows monotonically, by one
#       entry per deployment, forever. A count bound is therefore the right
#       retention mechanism here specifically because immutability rules out
#       the reuse that would otherwise cap it; an age bound would instead
#       delete the last known-good artifact of a repository that simply has
#       not been deployed lately, which is the one image a rollback needs.
#       Trade-offs: the bound is what a rollback can reach back through, so it
#       is set well above the handful of revisions an operator would ever roll
#       back to, and far below the point where retention becomes the
#       registry's dominant cost.
#       Bounded retention is also a preserved discipline rather than a new
#       one: the baseline capped each of its ten generation-dataset bases at
#       five generations with automatic scratch. The analogy stops at the
#       principle, since those limits governed datasets, not images.
variable "max_image_count" {
  description = "Number of tagged images the lifecycle policy retains per repository before expiring the oldest, bounding a tag set that grows by one entry with every deployment."
  type        = number

  # WHY : Trade-offs: thirty retains far more revisions than any realistic
  #       rollback reaches back through, while still turning an unbounded set
  #       into a bounded one. A much smaller bound would risk expiring an
  #       image a long-lived task definition still references; a much larger
  #       one would defer the bound to the point of not being one.
  default = 30

  # WHY : Assumptions: `nullable = false` makes an explicit null resolve to the
  #       default above rather than become the value. Without it the null would
  #       be encoded as a JSON null count inside the lifecycle policy document,
  #       and the registry rejects that document whole -- so a null in this one
  #       input would take the untagged-image rule down with it.
  nullable = false

  # WHY : Assumptions: the registry requires a whole number of at least one
  #       here and refuses the lifecycle policy otherwise, at `apply` time,
  #       naming neither this variable nor the bound. The ceiling of a thousand
  #       is this module's own rather than the registry's: a bound that high is
  #       indistinguishable from having no retention policy, so accepting it
  #       would let a caller satisfy the policy's presence while defeating its
  #       purpose.
  validation {
    condition = (
      var.max_image_count == floor(var.max_image_count)
      && var.max_image_count >= 1
      && var.max_image_count <= 1000
    )
    error_message = "max_image_count must be a whole number between 1 and 1000; at least one tagged image has to be retained, and a bound above 1000 is indistinguishable from no retention policy at all."
  }
}

# WHY : Assumptions: an untagged manifest is unreachable, not merely old, and
#       that is what justifies expiring it on age while tagged images are
#       bounded on count instead. One arises when a push uploads a manifest
#       that never receives a tag -- an interrupted push, a build-cache
#       manifest, or a child manifest of a multi-platform index -- or, where a
#       caller has overridden `image_tag_mutability` to `MUTABLE`, when a tag
#       is moved off the image it used to name. No container task definition
#       can reference any of those, so the two rules in main.tf answer two
#       different questions: how many deployable artifacts to keep, and how
#       long to leave unreferenceable ones lying in the repository.
#       Trade-offs: a nonzero window rather than immediate deletion. Expiring
#       at once would reclaim slightly sooner but would also erase the
#       evidence of an aborted push before anyone could look at it, and would
#       force a full re-upload of layers a retried push could otherwise reuse.
variable "untagged_image_expiry_days" {
  description = "Age in days at which an untagged image becomes eligible for expiry, reclaiming manifests and layers that no container task definition can reference."
  type        = number

  # WHY : Trade-offs: fourteen days outlasts any single aborted-push
  #       investigation, so the evidence and the reusable layers are still
  #       there, while keeping unreferenceable manifests from accumulating
  #       across many deployments. A one-day window would reclaim faster and
  #       routinely delete the layers a retried push would have reused.
  default = 14

  # WHY : Assumptions: `nullable = false` makes an explicit null resolve to the
  #       default above rather than become the value, and it matters for the
  #       same reason it does on `max_image_count` -- both counts are encoded
  #       into ONE lifecycle policy document, so a single null count
  #       invalidates the document rather than only the rule that carried it.
  nullable = false

  # WHY : Assumptions: the registry counts this in whole days and refuses zero,
  #       which is why the floor is one. The ceiling of a year is this module's
  #       own, on the same argument as `max_image_count`: an age bound long
  #       enough never to trigger is a retention policy in name only.
  validation {
    condition = (
      var.untagged_image_expiry_days == floor(var.untagged_image_expiry_days)
      && var.untagged_image_expiry_days >= 1
      && var.untagged_image_expiry_days <= 365
    )
    error_message = "untagged_image_expiry_days must be a whole number between 1 and 365; the registry counts whole days and rejects zero, and a window beyond a year would never expire anything in practice."
  }
}

# WHY : Trade-offs: clean teardown is an acceptance criterion for this package
#       -- `terraform apply` must provision the stack and `terraform destroy`
#       must remove it -- and setting this true is the shortest route to
#       satisfying it. It is nonetheless defaulted false, because the images
#       in these repositories are the deployable artifacts themselves, and a
#       `destroy` that silently discarded them would leave nothing to redeploy
#       from. False makes such a `destroy` fail loudly on the non-empty
#       repository, which is the failure an operator wants: it names what is
#       about to be lost while it can still be prevented. Turning it on is
#       then a per-environment decision recorded in that root's variables, and
#       docs/runbooks/teardown.md carries the deliberate emptying step for the
#       case where the failure is the expected outcome.
#       Assumptions: this is the same call the state-bucket input in
#       infra/bootstrap makes, for the same reason, so the package answers
#       "may destroy delete data?" identically everywhere rather than deciding
#       it once per module.
variable "force_delete" {
  description = "Whether `terraform destroy` may delete a repository that still holds images. False makes such a destroy fail rather than discard the artifacts a redeploy would need."
  type        = bool

  # WHY : Assumptions: an operator who genuinely intends to discard images sets
  #       this in the environment root, where the decision is reviewable in a
  #       diff. Defaulting true would make the same outcome the silent one.
  default = false

  # WHY : Trade-offs: one line of configuration that changes nothing observable
  #       -- an omitted argument and false reach the same registry behaviour --
  #       accepted in exchange for null meaning the same thing on EVERY input
  #       in this file. The cost of dropping it is that a caller would have to
  #       know which inputs read null as "use the default" and which let it
  #       through to the resource, and that distinction is invisible from the
  #       call site.
  nullable = false
}

# WHY : Assumptions: root-level and module-level tags compose additively rather
#       than one replacing the other. The calling root configures
#       `default_tags` on its own `provider "aws"` and every resource created
#       in this module inherits those keys at apply time; this input is for
#       per-module additions layered on top of them.
#       Alternatives Considered: relying on the root's provider `default_tags`
#       alone and declaring no tags input here, which is what infra/bootstrap
#       does. Rejected because a reusable module cannot -- infra/modules/ecr/
#       versions.tf deliberately declares no `provider` block, since a module
#       that configured its own provider would hard-bind the region and the
#       tag set for every caller and could not then be driven by two roots
#       with different configuration. Provider-level `default_tags` is
#       therefore unavailable inside this directory, and an explicit input is
#       the only mechanism left. Both environment roots already expect this,
#       and every module in the package accepts the same input so that call
#       sites stay uniform.
#       Trade-offs: an empty default means a reader of main.tf sees a `tags`
#       argument whose value looks like nothing, and has to know to look at the
#       calling root to find the environment-wide keys. That opacity is
#       accepted because the alternative -- restating an environment-wide tag
#       map at each module call site -- drifts the first time one site is
#       edited and the rest are not.
variable "tags" {
  description = "Per-module tags merged onto every repository, additive to whatever the calling root already applies through its provider `default_tags`."
  type        = map(string)

  # WHY : Assumptions: an empty map is the correct neutral value, not a sign of
  #       an untagged deployment -- the environment-wide keys arrive from the
  #       root's provider regardless of what this input holds, so a module
  #       with nothing extra to add supplies nothing.
  default = {}

  # WHY : Trade-offs: as on `force_delete`, one line that changes nothing
  #       observable -- the provider treats an absent tag map and an empty one
  #       alike, and the root's `default_tags` land either way -- accepted so
  #       that null resolves to the declared default uniformly across this
  #       file. The cost of omitting it is a contract where null means one
  #       thing on eight inputs and something else on the ninth.
  nullable = false
}
