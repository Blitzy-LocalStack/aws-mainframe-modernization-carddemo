# =============================================================================
# infra/modules/ecr/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The entire public contract of the reusable `ecr` module. This directory is
#   never applied on its own -- it is consumed as
#   `source = "../../modules/ecr"` by the infra/envs/dev and infra/envs/prod
#   roots -- so everything a caller can see of the ELEVEN container repositories
#   provisioned in infra/modules/ecr/main.tf -- the ten deployables specification
#   section 0.4.1.6 fixes plus the one approved third-party mirror ratified in
#   docs/adr/ADR-002-compute-platform.md -- is what the four blocks below
#   publish, and nothing else.
#
#   Every value here is an attribute the registry COMPUTES at apply time,
#   read straight off the resource; none is assembled from parts. That is the
#   governing rule for runtime identifiers in this package -- module outputs
#   are the only sanctioned source of an endpoint or an identifier, and no
#   consumer hard-codes one. Two consumers read these values, both through
#   the calling root: the deployment workflow, which pushes the ten built
#   images under a short-lived federated role, and the sibling ecs-service
#   module, whose task definitions and task-execution policies are wired from
#   them.
#
#   Refactoring Rationale: that sentence described the workflow as mirroring a pinned
#   third-party telemetry sidecar image into an eleventh repository, and an earlier
#   revision of this block then recorded that both the mirror and the sidecar had been
#   WITHDRAWN. Neither withdrawal is in this tree: infra/modules/ecs-service composes
#   the collector for every workload with enable_telemetry_collector defaulting to
#   true and no environment root overriding it, main.tf projects its iteration
#   collection from setunion(var.repository_names,
#   var.third_party_mirror_repository_names), and both environment roots resolve
#   repository_urls["aws-otel-collector"] when they compose their task definitions. So
#   the maps below name ELEVEN repositories, ten holding images this repository builds
#   and one holding a mirrored upstream image.
#
#   Assumptions: the distinction matters to a reader of `repository_urls`
#   specifically, because the two kinds of repository carry DIFFERENT TAG
#   CONVENTIONS. Every deployable is pushed under the release tag
#   .github/workflows/deploy.yml chooses per run; the mirrored image carries the
#   collector's own upstream release tag, because the artifact is not built here and
#   tagging it with a CardDemo commit would claim a provenance it does not have. That
#   is why the mirror has its own step rather than one more iteration of the
#   build-and-push loop, and it is why any consumer that iterates the KEYS of this
#   map must select the tag per key.
#   Refactoring Rationale: this paragraph said the mirror "is not a key the scan loop
#   can wait on", and the deployment workflow's vulnerability gate had been written to
#   match -- one tag for every key it iterated. That combination is a defect, not a
#   contract: the gate iterates this map, so it asked the registry for the mirror at a
#   release tag that is never pushed, read the empty result as a missing scan and
#   refused every deployment. The gate now selects the tag per repository and waits on
#   the mirror at its own upstream tag, and .github/workflows/infra-ci.yml asserts
#   that branch is present, so the mirror is scanned like everything else here.
#
# Parameters:
#   None. This file declares no `variable` block and accepts nothing; the
#   module's input surface is the ELEVEN `variable` blocks in
#   infra/modules/ecr/variables.tf -- eleven declarations, not ten, because
#   var.repository_names holds the ten deployables as one list and
#   var.third_party_mirror_repository_names declares the single mirror
#   separately, which is also the boundary the prose-count gate in
#   .github/workflows/infra-ci.yml slices this inventory on by name. What it reads instead are the attributes
#   of `aws_ecr_repository.this`, the resource infra/modules/ecr/main.tf
#   creates once per entry in `local.repository_names` -- the union of the ten
#   asserted deployables with the one approved mirror, so ELEVEN entries and
#   never fewer.
#   Refactoring Rationale: this said the mirror input was empty, so the union
#   resolved to ten. That held while nothing was mirrored; it is false now that
#   both environment roots pass aws-otel-collector, and a comment describing ten
#   maps beside a resource that creates eleven is the discrepancy a reader would
#   resolve by deleting the mirror -- which leaves every task's essential
#   collector sidecar unpullable. Recorded explicitly rather than omitted, so a
#   reader can tell "this file has no inputs" apart from "this file's inputs
#   went undocumented".
#
# Return values:
#   repository_urls ... map, keyed by logical artifact name, of the registry
#                      address each image is pushed to and pulled from
#   repository_names .. map, keyed by logical artifact name, of the
#                      namespaced name the registry actually stores
#   repository_arns ... map, keyed by logical artifact name, of the ARN an
#                      IAM policy statement is scoped to
#   registry_id ....... string, the one registry all eleven repositories live in
#
#   These four NAMES are a one-way contract rather than an implementation
#   detail. Both environment roots transcribe them, so renaming one here
#   breaks both roots at once; and this module's README carries a generated
#   Outputs table injected from these exact names and descriptions, so a
#   rename that misses either place reddens the generated-documentation drift
#   check instead of failing loudly at plan.
#
# Exceptions / errors:
#   - Nothing here can raise at apply time. These are expressions over an
#     already-created resource, not resources.
#   - An output naming a key absent from `aws_ecr_repository.this` would fail
#     at plan time with an invalid-index error. That is precisely why all three
#     maps below are PROJECTED from `aws_ecr_repository.this` with a `for`
#     expression rather than listing the eleven repositories a second time: a key
#     present in an output and absent from the resource cannot be written at
#     all, so the failure class is removed instead of guarded against.
#   - `registry_id` would fail at plan time with an index-out-of-range error
#     against an empty repository collection. It cannot be empty: variables.tf
#     asserts on var.repository_names both a non-empty set and a length of
#     exactly TEN before any resource is touched, and the mirror input that
#     main.tf unions with it can only ever ADD to that collection. The dependency
#     is recorded on that output.
#     Refactoring Rationale: this number has been reconciled in both directions
#     as the mirror input was defaulted in and emptied again, and the provisioned
#     total is eleven today. It is deliberately stated as an assertion about
#     var.repository_names rather than as a total, so that neither a mirror added
#     nor a mirror withdrawn can make it stale: the proof offered here is
#     non-emptiness of the deployable set, and a union with a second set
#     preserves it whatever that set holds.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the baseline had no machine-readable inventory of
#     where its artifacts lived. One load library served the whole
#     application and was registered TWICE under two handles -- `CARDDLIB` at
#     app/csd/CARDDEMO.CSD:L489, `STATUS(ENABLED)` at :L490, and `COM2DOLL` at
#     :L494, `STATUS(DISABLED)` at :L495 -- both naming the identical
#     `DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` at :L491 and :L496 at an equal
#     `RANKING(50)`. Establishing where a module actually loaded from meant
#     reading a 505-line resource-definition export and reasoning about
#     ranking and enable state, and the nearest thing to a listing was
#     `LIST   GROUP(CARDDEMO)` at app/jcl/CBADMCDJ.jcl:L159, which echoed the
#     installed definitions into a job spool for a person to read. These
#     outputs are what replaces that spool listing: all eleven locations are
#     enumerable from `terraform output` and from a reviewed plan, in a form a
#     pipeline consumes directly.
#   - Assumptions: the key set of all three maps is exactly `each.key` from
#     main.tf's `for_each` -- the bare artifact name, not the namespaced one.
#     Projecting from the resource is what keeps that true without restating
#     it anywhere.
# =============================================================================

# Every map below is keyed by the LOGICAL artifact name, and that shape is a
# decision rather than a default.
# Alternatives Considered: publishing these as `list(string)`. Rejected
# because a consumer would then have to index by position, and a position is
# not a fact about a repository -- it is a fact about the ordering of
# `var.repository_names`. Adding, removing or reordering one entry silently
# shifts every index after it, so a task definition would begin pulling a
# different bounded context's image with nothing anywhere reporting an error:
# the plan succeeds, the apply succeeds, and the wrong service runs. A map
# keyed by the artifact name gives each value a stable key that states what it
# identifies, and it is the form the deployment workflow and the ecs-service
# module look a repository up by.
# Assumptions: the keys are exactly the keys of `local.repository_names` -- the
# ten asserted deployables plus the one approved mirror, eleven keys today, and
# whatever a reviewed change to either input makes it -- because each map is
# projected from the very collection
# main.tf's `for_each` iterates rather than from a second list written here.
# That is what makes the key sets of the resource and of these outputs
# incapable of drifting apart, and it is deliberately stated against the local
# rather than against var.repository_names: an output that tracked only the
# deployable input would stop publishing a repository the module had actually
# created. variables.tf owns the canonical ten names, and restating them here
# would create a second copy that no gate compares against the first, so an
# entry added there and forgotten here would publish nine repositories out of
# ten and report nothing.

output "repository_urls" {
  description = "Map keyed by logical artifact name, such as `auth-service`, whose values are the registry addresses each image is pushed to and pulled from; each is read from that repository's provider-computed `repository_url` attribute rather than composed from an account identifier and a region."

  # WHY : Alternatives Considered: assembling this address here instead, from
  #       an account identifier, the registry's regional host and the
  #       repository name. Rejected on three independent grounds. It needs the
  #       account identifier, which means a `data "aws_caller_identity"` --
  #       and main.tf omits that data source deliberately, recording at its
  #       L146-L160 that having the account in scope at all is the temptation
  #       which produces the hand-assembled form. It would also write a
  #       registry host pattern into source, coupling this module to one
  #       partition's DNS layout, and a literal account identifier and a
  #       literal registry host are both forbidden anywhere in this directory.
  #       And a hand-built address is a second, independently maintained copy
  #       of something the registry already returns: the day the account or
  #       region it was built from stops matching the repository, a push
  #       authenticates successfully and then targets a registry that holds
  #       nothing -- a failure which reads as a missing image rather than as a
  #       wrong address.
  #       Assumptions: module outputs are the only sanctioned source of a
  #       runtime endpoint or identifier in this package, and no consumer
  #       hard-codes one. The provider resolves `repository_url` at apply time
  #       from the registry that actually exists, so the value is correct by
  #       construction rather than by agreement between two files that can
  #       diverge.
  value = {
    for key, repository in aws_ecr_repository.this :
    key => repository.repository_url
  }
}

output "repository_names" {
  description = "Map keyed by logical artifact name whose values are the full namespaced names the registry stores, in the composed `<name_prefix>-<environment>/<artifact>` form, for IAM resource matching and for registry CLI calls that take a repository name rather than an address."

  # WHY : Assumptions: the key and the value are deliberately NOT the same
  #       string, and a reader has to notice which is which. main.tf builds a
  #       map of bare artifact name to namespaced name and names each
  #       repository from the value, so `each.key` is `auth-service` while the
  #       stored name carries the prefix and environment segments ahead of it.
  #       This output publishes the stored form under the bare key, which is
  #       why it is not redundant with the key set a caller already has.
  #       Alternatives Considered: recomposing the namespaced name at each
  #       consumer from the prefix, the environment and the artifact name.
  #       Rejected because that spreads one naming convention across every
  #       consumer, and a consumer that composed it slightly differently
  #       would fail against a registry that looks correctly configured --
  #       which is the same class of divergence the two baseline library
  #       handles cited in the header are an instance of.
  value = {
    for key, repository in aws_ecr_repository.this :
    key => repository.name
  }
}

output "repository_arns" {
  description = "Map keyed by logical artifact name whose values are each repository's ARN, for scoping least-privilege IAM policy statements to one repository instead of to every repository in the registry."

  # WHY : Assumptions: an IAM policy statement scoped to one of these values
  #       is what keeps a compromised task-execution role from pulling another
  #       bounded context's image, so this output exists to make the narrow
  #       scope expressible at all -- the sibling ecs-service module consumes
  #       one of these values per service and the alternative it would
  #       otherwise need is a wildcard resource, which the infrastructure
  #       pipeline's policy scan rejects.
  #       Alternatives Considered: letting each consumer parse an ARN out of
  #       the corresponding registry address. Rejected because it would encode
  #       the registry's address format in every consumer that did it, which
  #       is the same coupling the attribute-over-composition reasoning on
  #       `repository_urls` rejects, and ecs-service records the identical
  #       decision on its own input rather than reconstructing the value.
  value = {
    for key, repository in aws_ecr_repository.this :
    key => repository.arn
  }
}

output "registry_id" {
  description = "Single string, not a map, because all the repositories in this module share one registry: its identifier, for a consumer that must authenticate to that registry before pushing or pulling. It is an AWS account identifier, so it must not be echoed into a build log."

  # WHY : Assumptions: this output is a RESOLVED ATTRIBUTE and not a literal,
  #       and the distinction is the whole reason publishing it is permitted.
  #       This directory forbids a literal account identifier written into
  #       source; a value the provider reads back from the registry at apply
  #       time is the opposite of that, and it is exactly the mechanism the
  #       package prescribes for runtime identifiers. Recorded here so a later
  #       reviewer does not read the output as the violation it resembles and
  #       delete it.
  #       Trade-offs: nothing suppresses this value, so it appears in
  #       `terraform output` and in a plan -- which is what makes the wiring
  #       auditable, at the cost of an account identifier sitting in a plan
  #       artifact. The cost is accepted and mitigated rather than ignored:
  #       the infrastructure pipeline excludes `.terraform/` and `*.tfstate*`
  #       from anything it uploads, and the deployment workflow verifies its
  #       identity without echoing the account into its log. A consumer of
  #       this output inherits that obligation.
  #       Alternatives Considered: `one(distinct([...]))` over all eleven
  #       repositories, which would additionally ASSERT that they share one
  #       registry. Rejected because the attribute is unknown at plan time, so
  #       distinctness cannot be decided until after apply -- the assertion
  #       would be unknown exactly when a check would be worth having, in
  #       exchange for an expression whose plan-time behaviour depends on
  #       unknown-value propagation.
  #       Assumptions: every repository here reports the same registry, so any
  #       one of them answers for all -- a registry is scoped per account and
  #       per region, and all eleven are created through the single provider
  #       configuration the calling root supplies. Index zero of the
  #       key-sorted list is taken because `values` orders by key, which makes
  #       the choice deterministic rather than incidental. It is safe only
  #       because the collection is never empty, and that is guaranteed
  #       upstream: variables.tf asserts on var.repository_names a non-empty set
  #       and a length of exactly TEN at plan time, before any resource is
  #       touched, and the mirror input main.tf unions with it -- empty by
  #       default -- can only add to that collection, never shrink it.
  value = values(aws_ecr_repository.this)[0].registry_id
}

# Nothing above is marked `sensitive`, deliberately.
# Assumptions: a repository address, a repository name and a repository ARN
# are not secrets. They are the addresses a build pushes to and the resources
# an IAM statement scopes against, and the registry authenticates every
# caller regardless of who knows them; secrecy is not what protects it.
# Trade-offs: marking them sensitive would suppress them from
# `terraform output` and redact them from the plan, which would defeat the
# only mechanism the environment roots have for wiring these values into the
# deployment workflow and the ecs-service module, and would hide from review
# exactly the values a reviewer needs to confirm. Readability is therefore
# chosen over redaction, and the one value where that has a real cost --
# `registry_id` -- carries the handling note on the output itself rather than
# relying on a blanket flag to do the thinking.

# Four outputs, and deliberately not more.
# Alternatives Considered: three further outputs were each considered and
# rejected, because an unused output is still a public surface a consumer can
# come to depend on and this file cannot then be narrowed without a breaking
# change. A per-repository map of registry identifiers would repeat one value
# eleven times, since all eleven repositories share a single registry. A flattened
# list of addresses would reintroduce exactly the positional indexing the map
# shape above exists to prevent. And a convenience map of complete image URIs
# with a tag already appended would put image tagging in this module, whereas
# the tag is a commit SHA the deployment workflow chooses per push -- this
# module would have to invent one, and every consumer would then inherit a
# tagging convention it never selected.
