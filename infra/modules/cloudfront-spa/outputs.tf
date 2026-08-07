# =============================================================================
# infra/modules/cloudfront-spa/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete public contract of the `cloudfront-spa` module. Every value
#   this module hands back to a caller is declared here and nowhere else, so
#   this file is the whole of its return surface: whatever appears below is what
#   a caller can see, and whatever is absent is invisible to it no matter what
#   infra/modules/cloudfront-spa/main.tf creates.
#
#   The module provisions the delivery path for the CardDemo single-page
#   application -- a private S3 origin bucket holding the built ui/dist bundle,
#   reached by a CloudFront distribution through an origin access control. What
#   that path replaces is the 3270 datastream delivery of the BMS presentation
#   layer: app/bms/COSGN00.bms:L26-L28 declares the sign-on screen as
#   `COSGN0A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)`, a fixed 24x80 character
#   map, and app/csd/CARDDEMO.CSD:L378-L379 binds it to a CICS transaction with
#   `DEFINE TRANSACTION(CC00) ... PROGRAM(COSGN00C)`. The six values below are
#   what a caller needs in order to publish a build into that path and to
#   address it afterwards.
#
#   WARNING -- these NAMES are a one-way contract. infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf read them and re-export them, and
#   .github/workflows/deploy.yml consumes the bucket name and the distribution
#   id to publish a build. Nothing in this module depends on any of those three
#   files, so the dependency runs one way only and no tool run from this
#   directory can report a break in it: renaming an output below leaves this
#   module valid on its own while failing both environment roots and the
#   deployment path. Adding to this file is safe; renaming in it is a change to
#   those three call sites as well.
#
# Parameters:
#   None. An outputs.tf accepts nothing -- it declares what the module returns.
#   The twelve inputs this module accepts are declared in
#   infra/modules/cloudfront-spa/variables.tf, and not one of them is referenced
#   below: every value here is read from a resource attribute in
#   infra/modules/cloudfront-spa/main.tf instead.
#
# Return values:
#   Six outputs, each of type string, in this order:
#     distribution_id ............ id of the CloudFront distribution, for the
#                                  cache invalidation that follows a publish.
#     distribution_arn ........... ARN used to scope the CloudFront KMS
#                                  service-principal decrypt grant.
#     distribution_domain_name ... the distribution's CloudFront-assigned
#                                  hostname, the SPA's public entry point.
#     spa_bucket_name ............ name of the origin bucket, the destination a
#                                  sync of the built ui/dist output writes to.
#     spa_bucket_arn ............. ARN of that same bucket, the form an IAM
#                                  policy statement needs to scope a grant to
#                                  it and to nothing else.
#     origin_access_control_id ... id of the origin access control the origin
#                                  bucket's policy is conditioned on.
#   None is marked `sensitive`; that decision is recorded below this header.
#
# Errors / failure modes:
#   No output here can fail on its own. An output block has no validation, no
#   API call and no failure mode of its own, so nothing in this file can be the
#   cause of a failed plan or apply. Two behaviours surprise operators
#   nonetheless, and both concern WHEN these values exist rather than whether
#   they are right:
#
#     1. `terraform output` prints nothing, or reports no outputs at all,
#        before a successful apply. Every value below is an attribute the AWS
#        API assigns when the resource is created, so none is known during plan
#        and none is in state until the resource exists. An operator following
#        docs/runbooks/deploy.md reaches for these immediately after the apply
#        step, which is exactly where the empty result is met. A failed or
#        partially-applied run leaves the same symptom, so an empty output is a
#        signal to read the apply result rather than to look for a fault here.
#     2. `terraform output` run in THIS directory prints nothing even after a
#        successful apply. A child module's outputs are read through the root
#        that calls it -- `terraform output` in infra/envs/dev, having that
#        root's own outputs.tf re-export `module.<call label>.<name>` for the
#        label it gave this module in its own `module` block -- because this
#        directory is never applied directly and holds no state of its own.
#
# WHY (non-obvious design decisions):
#   - Assumptions: HCL has no docstring construct, so this header block IS the
#     entry-point documentation for this file, and each output's `description`
#     IS that return value's documentation. Per the "HCL (Terraform)" section
#     of docs/CODE_DOCUMENTATION_STANDARD.md the required form is a file-header
#     block, a `description` on every variable and output, and a why-comment on
#     each non-obvious argument. tflint's terraform_documented_outputs rule
#     makes the description half mechanical and gating; the why-comments below
#     are the half no linter reads, which is why they are written to be
#     specific rather than decorative.
#   - Assumptions: infra/.terraform-docs.yml sets `read-comments: false`, so the
#     generated Outputs table in this module's README.md is built from the
#     `description` strings ALONE and a why-comment never reaches it. That is
#     why each description below is a self-contained single sentence naming its
#     consumer rather than a fragment leaning on the comment beside it: that
#     table is drift-checked in CI, and a description that only makes sense
#     next to its comment renders as a cell explaining nothing.
#   - Trade-offs: this contract is deliberately NARROW -- six outputs where
#     main.tf creates sixteen resources and reads six data sources. The values
#     that are produced and withheld are enumerated at the foot of this file
#     rather than left to be noticed. What a narrow contract costs is that
#     a caller wanting something unpublished must change this file and its
#     README together. What it buys is that every name here has a known
#     consumer, so none of them pins an implementation detail of main.tf in
#     place for the benefit of a caller that does not exist.
#   - Alternatives Considered: one object-typed output carrying all six values,
#     which is fewer blocks and a single reference at each call site. Rejected
#     -- infra/.terraform-docs.yml generates one table row per output, so a
#     single object would publish one opaque row and hide the five descriptions
#     the drift check exists to keep honest, and a caller could then no longer
#     reference one value without destructuring a map whose shape is documented
#     nowhere.
# =============================================================================


# -----------------------------------------------------------------------------
# On `sensitive`: no output below is marked, and that is a decision rather than
# an omission.
#
# Alternatives Considered: marking all six `sensitive = true`, which is the
# defensive default some trees adopt for anything that looks like an
# identifier. Rejected, on two specific grounds rather than as a preference.
# First, none of these six values grants access on its own: the origin bucket
# is private, with this distribution's origin access control as its only reader
# and a bucket policy that additionally denies any request not made over TLS;
# the distribution serves nothing but the public static bundle a browser
# downloads anyway; and an origin access control id is inert without the bucket
# policy that names it. Second, `sensitive` propagates and suppresses -- a root
# re-exporting a sensitive output must mark its own output sensitive too, and
# Terraform then redacts the value from plan output, from `terraform output`
# and from generated documentation. The operator following
# docs/runbooks/deploy.md and docs/runbooks/teardown.md has to READ the bucket
# name and the distribution id to publish a build and to confirm a teardown, so
# marking them would suppress precisely the values the runbooks instruct an
# operator to fetch while hiding nothing an attacker could use.
#
# Assumptions: the genuinely secret material in this package -- database
# credentials and seed-user passwords -- is generated at apply time into Secrets
# Manager by infra/modules/secrets and is never the output of any module, so
# `sensitive` is not this file's mechanism for protecting anything. AAP 0.9.1
# requires that no secret reach the repository, and this file satisfies that by
# returning no secret at all rather than by redacting one.
# -----------------------------------------------------------------------------


# -----------------------------------------------------------------------------
# The distribution.
# -----------------------------------------------------------------------------

output "distribution_id" {
  description = "Id of the CloudFront distribution serving the SPA. The deployment pipeline passes it to a cache invalidation after uploading a new build, and an operator uses it to address the distribution from the CLI."

  # WHY : Assumptions: a new SPA build reuses the SAME object key for its entry
  #       document -- main.tf serves var.default_root_object at `/` and rewrites
  #       its error responses to that same key -- so uploading a build
  #       overwrites a key the edge has already cached, and CloudFront keeps
  #       serving the PREVIOUS document until its time-to-live expires. The
  #       invalidation that corrects this needs the distribution id and nothing
  #       else in the package can supply it, so without this output the
  #       invalidation cannot be issued and the failure is a deploy that reports
  #       success while every viewer keeps loading the old application. The
  #       content-hashed asset filenames the SPA build emits are what confine
  #       that failure to the entry document instead of the whole bundle, which
  #       is what makes it subtle rather than obvious.
  value = aws_cloudfront_distribution.spa.id
}

output "distribution_arn" {
  description = "ARN of the CloudFront distribution serving the SPA. The environment root passes this exact ARN back to the KMS module so the CloudFront service principal can decrypt only this distribution's SSE-KMS origin objects."

  # WHY : Refactoring Rationale: the origin access control and S3 bucket policy
  #       authorize object reads, but KMS evaluates its own key policy. Without
  #       this cross-module handle every asset request returns 403 even though
  #       the bucket policy is correct.
  # WHY : Trade-offs: publishing the ARN adds one narrow producer-to-consumer
  #       seam and avoids an account-wide `distribution/*` condition in the key
  #       policy. The root wires the exact value; callers never reconstruct it.
  value = aws_cloudfront_distribution.spa.arn
}

output "distribution_domain_name" {
  description = "CloudFront-assigned hostname of the distribution. This is the SPA's public entry point, the address that replaces a 3270 terminal session against CICS transaction CC00, and the environment roots re-export it as the deployed front-end host."

  # WHY : Trade-offs: returning a scheme-prefixed URL instead -- a scheme, this
  #       hostname and a trailing slash concatenated -- would be marginally
  #       friendlier to paste, and it is wrong for exactly the reason this module
  #       now guarantees: the distribution ALWAYS has at least one alternate
  #       domain name, because `aliases` is a required non-empty input, so the
  #       meaningful public address is an alias and a composed URL built around
  #       this hostname would confidently publish the address nobody uses. A bare
  #       hostname is published because it asserts nothing about scheme and
  #       nothing about which name is canonical; it remains the value a caller
  #       needs for a DNS alias record pointing its own name here, which is what
  #       this output is actually for.
  value = aws_cloudfront_distribution.spa.domain_name
}

# WHY : Refactoring Rationale: a `distribution_hosted_zone_id` output stood here,
#       republishing `aws_cloudfront_distribution.spa.hosted_zone_id` so a caller
#       could build a Route 53 alias record without hard-coding CloudFront's
#       global zone constant. It was withdrawn. No caller in this package reads
#       it: `grep -rn 'module.cloudfront_spa.distribution_hosted_zone_id' infra/`
#       matches nothing in either environment root, and neither root creates a
#       Route 53 zone or record at all, because DNS delegation for the alias
#       names in `var.aliases` is an operator concern outside this package. An
#       output nothing reads is interface surface with no consumer contract to
#       keep, and `distribution_domain_name` immediately above already gives a
#       DNS consumer the target it needs. Alternatives Considered: retaining it
#       speculatively against a future Route 53 module, rejected because the
#       sixteen-module catalog contains no such module and the attribute is one
#       expression away for any root that later needs it.


# -----------------------------------------------------------------------------
# The origin bucket, published in both of the two forms its consumers need.
# -----------------------------------------------------------------------------

output "spa_bucket_name" {
  description = "Name of the private S3 bucket holding the built SPA bundle, and the destination the deployment pipeline syncs the ui/dist output into. This is neither the dataset bucket owned by the s3-datasets module nor the Terraform remote-state bucket owned by infra/bootstrap."

  # WHY : Assumptions: the consumer is a sync of the built ui/dist output, and
  #       that command addresses its destination as `s3://<name>/`, so the bare
  #       name is the only form it can accept. `.bucket` is read rather than
  #       `.id` even though the AWS provider currently resolves both to the same
  #       string, because that equality is an implementation detail of the
  #       provider's S3 resource while `.bucket` is the attribute DEFINED to be
  #       the bucket name -- reading `.id` would rest on a coincidence for a
  #       value the deployment path cannot tolerate being wrong.
  # WHY : Assumptions: WARNING -- this package contains three versioned,
  #       encrypted S3 buckets, and they are confusable precisely because they
  #       are configured alike. This module owns the SPA bucket, the dataset
  #       bucket belongs to infra/modules/s3-datasets, and the remote-state
  #       bucket belongs to infra/bootstrap. Publishing this one under a name
  #       that says `spa`
  #       rather than a generic `bucket_name` is what stops a caller wiring a
  #       front-end publish at the dataset bucket or, worse, at the state bucket
  #       Terraform itself depends on. main.tf and variables.tf both carry this
  #       same boundary note, deliberately, because the mistake is available at
  #       every one of the three files.
  value = aws_s3_bucket.spa.bucket
}

output "spa_bucket_arn" {
  description = "ARN of the SPA origin bucket, for an IAM policy that grants a deployment role write access to this bucket and to no other. Published alongside spa_bucket_name because the two forms are not interchangeable."

  # WHY : Assumptions: "why two outputs for one bucket" is the first question a
  #       reviewer asks here, and the answer is that an IAM policy statement's
  #       resource element takes an ARN and will not take a bare bucket name, so
  #       a least-privilege grant scoped to this bucket alone is simply not
  #       expressible from spa_bucket_name. The two are therefore published
  #       deliberately rather than redundantly: the name is what the sync command
  #       consumes, and the ARN is what the policy authorising that command
  #       consumes.
  # WHY : Trade-offs: the caller appends the object-level suffix itself rather
  #       than receiving a second, pre-suffixed output. That keeps one output per
  #       real resource attribute, and it is also the more useful shape -- a
  #       publish role needs two statements at two different scopes, listing
  #       granted on the bucket ARN and object writes granted on the object ARN
  #       beneath it, so a single pre-composed value would serve one of them and
  #       silently mislead the other. main.tf composes the same suffix inline for
  #       its own read grant, so the pattern is consistent across the module.
  value = aws_s3_bucket.spa.arn
}

output "log_bucket_arn" {
  description = "ARN of the SSE-S3 (AES256) encrypted S3 destination for CloudFront standard logging v2. Its default encryption is deliberately NOT the supplied customer-managed key, because CloudFront standard log delivery cannot write to a bucket whose default encryption is SSE-KMS; the constraint and its compensating controls are recorded on the encryption resource in main.tf. The KMS module still consumes this ARN as an exact allowed S3 encryption context, so the key policy stays scoped to this bucket rather than widened, and a destination that can carry the key later needs no policy change."
  value       = aws_s3_bucket.logs.arn
}

output "log_delivery_source_arn" {
  description = "Exact CloudWatch Logs delivery-source ARN for the distribution's standard logging v2 stream. The KMS key policy uses it to scope log-delivery data-key generation."
  value       = aws_cloudwatch_log_delivery_source.cloudfront_access.arn
}


# -----------------------------------------------------------------------------
# The origin access control, published as a diagnostic handle.
# -----------------------------------------------------------------------------

output "origin_access_control_id" {
  description = "Id of the origin access control that signs this distribution's requests to the private origin bucket. Published so an operator diagnosing a 403 from the origin can confirm which origin access control the bucket policy is scoped to."

  # WHY : Assumptions: main.tf sets `signing_behavior = "always"` and
  #       `signing_protocol = "sigv4"` on this resource, and the origin bucket's
  #       policy grants `s3:GetObject` to the CloudFront service principal only
  #       when the request's source ARN equals THIS distribution's ARN. A 403
  #       from the origin is therefore almost never a missing object: it is a
  #       signing or key-policy problem, and the first question worth asking is
  #       which origin access control and which distribution the grant is
  #       actually conditioned on. Publishing the id makes that answerable with
  #       `terraform output` instead of a console hunt across two services, and
  #       that is the whole of its value -- no automated consumer reads it.
  # WHY : Trade-offs: an output whose only consumer is a human is still worth
  #       its place in the contract, because the alternative during an incident
  #       is an operator reading raw state or clicking through a console. The
  #       accepted cost is one name in the contract that no pipeline references,
  #       which is why it is the only such name here: the distribution ARN that
  #       the same grant is conditioned on is NOT published, for the reason
  #       recorded in the omissions below.
  value = aws_cloudfront_origin_access_control.spa.id
}


# =============================================================================
# Deliberate omissions
# -----------------------------------------------------------------------------
# Every value named below is produced inside this module and is deliberately
# NOT returned. Each is a reasonable thing to come looking for in this file, so
# each is recorded as a decision rather than left to read as an oversight. The
# governing principle is the one stated in the header: a published name is a
# commitment to a caller, and an output with no consumer pins an implementation
# detail of main.tf in place for nobody's benefit.
#
# No access-log bucket name:
#   Assumptions: aws_s3_bucket.logs is module-private in both directions.
#   Nothing outside this module writes to it -- the CloudWatch Logs delivery
#   destination in main.tf addresses it by ARN -- and nothing outside reads it,
#   because its
#   encryption, versioning and retention are all governed here, the last by
#   var.log_retention_days. Its ARN is published only because the KMS module
#   consumes that exact bucket identity as an encryption-context boundary; the
#   bucket name remains private because no deploy or operator workflow needs it.
#
# No KMS key ARN:
#   Assumptions: the customer-managed key is an INPUT, var.s3_kms_key_arn,
#   owned by infra/modules/kms. Echoing an input straight back out would
#   advertise this module as a source of truth for a value it merely received,
#   and a caller reading the key from here rather than from the kms module would
#   record a dependency edge that does not exist.
#
# No ACM certificate ARN and no aliases:
#   Assumptions: both are required inputs -- var.acm_certificate_arn and
#   var.aliases -- supplied by the environment root, which therefore already
#   holds the values and has no reason to read them back. The same echoing
#   objection applies, and a second one is specific to the certificate: it must
#   be issued in a single fixed region regardless of where the rest of the stack
#   is deployed, so re-publishing it from a module that inherits exactly one
#   provider configuration would invite a caller to treat this module as the
#   place that regional constraint is discovered. variables.tf is where it is
#   both recorded AND enforced -- it asserts the us-east-1 segment of the ARN, so
#   a certificate from the wrong region is refused before any resource exists
#   rather than by CloudFront partway through an apply.
#
# No `sensitive` marking on any output:
#   Recorded above the outputs rather than here, because it is a property of the
#   six values that ARE published rather than the absence of a seventh.
# =============================================================================
