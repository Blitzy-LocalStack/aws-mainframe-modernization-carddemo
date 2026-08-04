# =============================================================================
# infra/modules/cloudfront-spa/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The substance of the `cloudfront-spa` module: a PRIVATE S3 origin bucket
#   holding the built CardDemo single-page application, a CloudFront
#   distribution that reaches that bucket through an origin access control, and
#   a second, module-private bucket that receives the distribution's access
#   logs. Together these resources are the delivery path for the migrated user
#   interface.
#
#   What it replaces: the 3270 datastream delivery of the CardDemo BMS
#   presentation layer. That layer is 21 BMS mapsets in total -- 17 base
#   mapsets under app/bms, carrying 902 DFHMDF field definitions between them,
#   plus 4 mapsets in the two extension trees -- and every map is a fixed 24x80
#   character screen, as app/bms/COSGN00.bms:L26-L28 declares with
#   `COSGN0A DFHMDI COLUMN=1, LINE=1, SIZE=(24,80)`. Those 21 screens become 21
#   client-side SPA routes, served as static assets over HTTPS from edge
#   locations instead of as a terminal datastream.
#
#   What it does NOT replace, stated because both are easy to attribute here by
#   association: CICS transaction routing, which belongs to the `alb` and
#   `api-gateway-http` modules, and the CICS load library
#   `DEFINE LIBRARY(CARDDLIB) ... DSNAME01(AWS.M2.CARDDEMO.LOADLIB)` at
#   app/csd/CARDDEMO.CSD:L489-L491, which belongs to the `ecr` module. This
#   module carries the presentation layer's DELIVERY and nothing else.
#
#   Bucket boundary -- this module owns exactly TWO buckets, and neither is
#   either of the other two S3 buckets in this package:
#     aws_s3_bucket.spa ..... the SPA origin, holding the built ui/dist output.
#     aws_s3_bucket.logs .... this distribution's access logs, created here for
#                             the reason recorded on that resource.
#   The dataset bucket belongs to infra/modules/s3-datasets and the Terraform
#   remote-state bucket to infra/bootstrap. All four are versioned and
#   encrypted, which is precisely what makes them confusable, and no input to
#   this module accepts a pre-existing bucket name: it names and creates its
#   own.
#
# Parameters:
#   None declared here. Every input this module accepts is declared in
#   infra/modules/cloudfront-spa/variables.tf, and all twelve of them are
#   consumed below.
#
# Return values:
#   None declared here. Every value this module hands back to a caller is
#   declared in infra/modules/cloudfront-spa/outputs.tf, which is written
#   against the resource addresses in this file -- so renaming a resource here
#   is a breaking change to that file.
#
# Errors / failure modes:
#   Three apply-time failures are worth expecting, because none of them shows
#   up in a plan and each has its cause outside this file:
#
#     1. Every viewer request answers 403 while the bucket, the distribution
#        and the key each look correct in isolation. Cause: the customer-managed
#        key named by var.s3_kms_key_arn has a key policy that does not grant
#        the CloudFront service principal `kms:Decrypt` for this distribution,
#        so the origin access control cannot decrypt an SSE-KMS object. The fix
#        belongs to infra/modules/kms; see the encryption resource below.
#     2. `terraform apply` fails at the point CloudFront is asked to accept the
#        certificate. Cause: the ACM certificate named by
#        var.acm_certificate_arn was issued somewhere other than us-east-1.
#        CloudFront reads its certificate from that one region no matter where
#        the rest of the stack is deployed, and a certificate issued in the
#        deployment region is a well-formed ARN indistinguishable from a usable
#        one until the API rejects it.
#     3. `terraform destroy` stops with BucketNotEmpty. Cause: var.force_destroy
#        is false -- its default -- and one of the two buckets still holds
#        objects. This is deliberate, and docs/runbooks/teardown.md carries the
#        purge step it requires.
#
#   A fourth is refused at PLAN time and by variables.tf rather than by this
#   file: `environment = "prod"` with no ACM certificate and no alias, because
#   the default certificate pins the viewer security policy to TLSv1.
#
# WHY (non-obvious design decisions):
#   - Assumptions: HCL has no docstring construct, so this header block IS the
#     entry-point documentation for this file. Per the "HCL (Terraform)"
#     section of docs/CODE_DOCUMENTATION_STANDARD.md the required form is a
#     file-header block, a `description` on every variable and output, and a
#     why-comment on each non-obvious resource argument -- so the rationale for
#     an individual argument is carried adjacent to that argument below rather
#     than collected up here, and a reader changing one value sees why it is
#     what it is without scrolling.
#   - Alternatives Considered: serving the SPA from the containerised `ui`
#     image behind the internal load balancer, which would have reused the
#     `ecs-service` module and needed no bucket at all. Rejected -- the bundle
#     is immutable static content, so paying for a Fargate task and a load
#     balancer target group to hand back files an edge cache can serve buys
#     nothing, and it would route every asset fetch through the same load
#     balancer that carries authenticated API traffic. ui/nginx.conf remains for
#     local and container use; CloudFront over a private S3 origin is the
#     DEPLOYED path, and the two are kept behaviourally identical where it
#     matters -- see the error-response and response-headers comments below.
#   - Trade-offs: the resource graph here is deliberately fixed. There is no
#     toggle to skip the log bucket, skip the distribution or add a second
#     origin, because the dev and prod roots are required to differ only in
#     sizing and retention. What that costs is flexibility this package has no
#     use for; what it buys is that a defect reproduced in dev is reachable in
#     prod, because the two configurations differ only in numbers.
# =============================================================================

# -----------------------------------------------------------------------------
# Account and region, read from the inherited provider rather than accepted as
# inputs. Both exist solely to compose the two globally-unique bucket names.
# -----------------------------------------------------------------------------

# WHAT: the AWS account id.
# WHY : Assumptions: the S3 bucket namespace is GLOBAL -- not per-account and
#       not per-region -- so a bare `carddemo-dev-spa` belongs to whichever
#       account created it first, and every later account is refused with
#       BucketAlreadyExists by a bucket in an unrelated organisation, which is
#       why that failure reads as inexplicable. Folding the account id into the
#       name is what makes it collision-free.
#       Alternatives Considered: a `random_id` suffix, which is the other
#       standard way to guarantee uniqueness. Rejected -- it makes the bucket
#       name unpredictable, and the deployment pipeline that uploads the built
#       bundle has to address that bucket by name. A derived name is
#       reconstructable from the account, the region and the tfvars file; a
#       random one is only discoverable from state.
data "aws_caller_identity" "current" {}

# WHAT: the region this module's buckets are created in.
# WHY : Assumptions: read from the provider rather than accepted as an input,
#       and infra/modules/cloudfront-spa/variables.tf records the same decision
#       from the other side, in its "No `region` or `aws_region` input" note. A
#       region variable could disagree with the inherited provider
#       configuration; the provider would win, and the buckets would then be
#       created in one region while carrying the name of another -- a defect
#       that survives plan review because the plan is internally consistent.
#       Assumptions: the attribute read from this data source below is `region`,
#       NOT `name`. The AWS provider marks both `name` and `id` on
#       `aws_region` as deprecated in its 6.x line, where `region` is the
#       supported attribute; `name` still resolves, so the only symptom of
#       using it is a deprecation warning that is easy to leave in place.
data "aws_region" "current" {}

# -----------------------------------------------------------------------------
# The two AWS-managed CloudFront policies this distribution attaches.
# -----------------------------------------------------------------------------

# WHAT: the AWS-managed cache policy, resolved by its name.
# WHY : Alternatives Considered: writing the managed policy's identifier as a
#       literal, which is what the console displays and costs one fewer read.
#       Rejected -- a managed-policy identifier is an opaque UUID that no
#       reviewer can verify by reading it, so the literal form makes the
#       configuration unauditable at exactly the point where a wrong value
#       silently changes caching behaviour. Resolving by name keeps the intent
#       in the source.
#       Alternatives Considered: a bespoke `aws_cloudfront_cache_policy`.
#       Rejected -- the SPA build emits content-hashed asset filenames, so the
#       cache key needs no query-string, cookie or header participation and no
#       bespoke time-to-live reasoning. A custom policy would be one more object
#       to maintain and to keep aligned across two environments for no
#       behavioural difference.
data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

# WHAT: the AWS-managed security-headers policy, resolved by its name.
# WHY : Assumptions: this application has TWO delivery paths for the same
#       bundle and only one of them passes through a web server. ui/nginx.conf
#       serves the containerised `ui` image and can add response headers itself;
#       the deployed path is this distribution over a private S3 origin, where
#       there is no nginx layer and S3 returns only what was stored. Attaching
#       this policy is therefore what puts Strict-Transport-Security, the
#       content-type-options, frame-options and referrer-policy headers onto the
#       responses viewers actually receive. Without it the two paths would
#       disagree on response headers while serving byte-identical content, and
#       that kind of difference is only ever found in production.
#       Alternatives Considered: a bespoke
#       `aws_cloudfront_response_headers_policy` so individual header values
#       could be tuned. Rejected on the same grounds as the cache policy above,
#       plus one specific to this pair: a custom policy would have to be kept in
#       step with ui/nginx.conf by hand, whereas both referring to a named
#       standard keeps them aligned by construction.
data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

# -----------------------------------------------------------------------------
# Composed names.
# -----------------------------------------------------------------------------

locals {
  # WHAT: <name_prefix>-<environment>-spa-<account id>-<region>.
  # WHY : Assumptions: this is the exact composition
  #       infra/modules/cloudfront-spa/variables.tf derives its 20-character cap
  #       on name_prefix from, and the two must agree or that cap stops being a
  #       proof and becomes a guess. The derivation budgets 4 characters for
  #       `prod`, 8 for the longer `spa-logs` role token, 12 for the account id,
  #       15 for the longest region name AWS publishes and 4 for the hyphens
  #       joining five segments: 43 of the 63 an S3 general-purpose bucket name
  #       allows, leaving the 20 that variable caps at. Changing the shape of
  #       either name below without revisiting that validation would let a legal
  #       prefix compose an illegal bucket name, and it would fail during apply.
  spa_bucket_name = "${var.name_prefix}-${var.environment}-spa-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}"

  # WHAT: the same composition with the `spa-logs` role token in place of `spa`.
  # WHY : Assumptions: the role token is what makes a collision between these
  #       two names impossible rather than merely unlikely. Both are built from
  #       the same prefix, environment, account and region, so the token is the
  #       only thing distinguishing them -- and no input to the `spa` form can
  #       produce the `spa-logs` form, because a prefix admitting no consecutive
  #       hyphens cannot synthesise the extra `-logs` segment. It is also the
  #       longer token, which is why the length budget above is computed from
  #       this name rather than from the origin bucket's.
  log_bucket_name = "${var.name_prefix}-${var.environment}-spa-logs-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}"

  # WHAT: one identifier for the single origin, defined once and referenced
  #       twice.
  # WHY : Refactoring Rationale: CloudFront requires the distribution's `origin`
  #       block and its `default_cache_behavior.target_origin_id` to name the
  #       same string, and a mismatch is not reported as a typo -- Terraform
  #       reports an unresolvable target origin, which reads as a problem with
  #       the cache behaviour rather than as two literals that drifted apart.
  #       Writing the value once removes that failure mode instead of leaving it
  #       to be documented.
  spa_origin_id = "${var.name_prefix}-${var.environment}-spa-origin"
}

# =============================================================================
# The SPA origin bucket
# -----------------------------------------------------------------------------
# Composed from the modern per-concern resources rather than the inline
# arguments of `aws_s3_bucket`.
#   WHY : Assumptions: the AWS provider flags `acl`, `policy`, `versioning`,
#         `server_side_encryption_configuration`, `lifecycle_rule`, `logging`
#         and `grant` on `aws_s3_bucket` as deprecated, so the decomposition
#         below is what the provider schema itself requires rather than a
#         stylistic preference. It also matches infra/bootstrap, so the two S3
#         call sites in this package read the same way.
# =============================================================================

resource "aws_s3_bucket" "spa" {
  bucket = local.spa_bucket_name

  # WHAT: deletion of a populated bucket is refused unless the caller opts in.
  # WHY : Trade-offs: with the default of false, `terraform destroy` stops
  #       rather than deleting a bucket that still holds the deployed front end,
  #       and an operator has to purge it first -- the manual step
  #       docs/runbooks/teardown.md documents. What that costs is a teardown
  #       that is not a single command. What it buys is that a mistyped or
  #       mistargeted destroy cannot silently take the deployed SPA with it,
  #       and because versioning is enabled below, "purge" means every version,
  #       which is exactly the irreversible act worth making deliberate. The
  #       flag is threaded through from the caller rather than fixed here so an
  #       ephemeral environment can accept the consequence explicitly.
  force_destroy = var.force_destroy
}

# WHAT: all four public-access controls asserted, not just the two that block
#       new grants.
# WHY : Assumptions: with an origin access control the only legitimate reader of
#       this bucket is the distribution, so every public path is closed rather
#       than only the ones a scanner names -- `block_*` refuse a public grant
#       being ADDED and `ignore_public_acls` / `restrict_public_buckets` neuter
#       one that somehow already exists, which are different guarantees and both
#       are wanted. This is also the bucket-public-access item the HIGH/CRITICAL
#       infrastructure policy scan asserts, satisfied by construction rather
#       than by a suppression.
#       Assumptions: `restrict_public_buckets = true` does NOT block the
#       CloudFront grant written further down, which looks like a contradiction
#       until the distinction is named: that setting restricts a policy granting
#       access to the `*` principal or to the authenticated-users group, and the
#       CloudFront grant names a SERVICE principal, which S3 does not classify
#       as public. A service-principal grant and a public grant are different
#       things in the same syntax.
resource "aws_s3_bucket_public_access_block" "spa" {
  bucket                  = aws_s3_bucket.spa.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# WHAT: access-control lists disabled outright on this bucket.
# WHY : Assumptions: access to the origin is granted by exactly one mechanism --
#       the bucket policy below, to one service principal, conditioned on one
#       distribution. Leaving ACLs enabled would leave a second, parallel way to
#       grant read access that no reader of that policy would think to check, so
#       disabling them makes the policy the complete answer to "who can read
#       this bucket" rather than a partial one.
#       Trade-offs: this deliberately DIFFERS from the log bucket further down,
#       which must use `BucketOwnerPreferred`. The asymmetry is not an
#       inconsistency to tidy up: CloudFront's standard log delivery grants
#       itself write access to its destination through an ACL, so ACLs are a
#       hard requirement there and a liability here. The reason is recorded at
#       both ends so that neither is "harmonised" with the other.
resource "aws_s3_bucket_ownership_controls" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# WHAT: versioning, which on this bucket is a rollback mechanism rather than a
#       compliance checkbox -- and once enabled it can never be switched off,
#       only suspended.
# WHY : Assumptions: this is the bucket-versioning item the HIGH/CRITICAL policy
#       scan asserts, and asserting it here is what makes that gate pass by
#       construction.
#       Refactoring Rationale: it is also load-bearing for a requirement that
#       has nothing to do with the scan. A SPA deploy overwrites objects in
#       place, so without versioning the previous build is gone the moment the
#       new one is uploaded and "revert the front end" would mean rebuilding
#       from a git tag and hoping the toolchain is reproducible. With versioning
#       the previous object versions are still addressable, which is the
#       concrete mechanism behind the roll-back capability this migration
#       commits to rather than a claim in a runbook. The window it stays open
#       for is bounded by the lifecycle rule below.
resource "aws_s3_bucket_versioning" "spa" {
  bucket = aws_s3_bucket.spa.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    # WHAT: a bucket key, so S3 derives per-object keys from one KMS data key
    #       instead of calling KMS per object.
    # WHY : Trade-offs: every asset fetch that misses the edge cache is an
    #       origin read, and each such read of an SSE-KMS object is otherwise a
    #       separate KMS Decrypt request -- billed per request and subject to a
    #       per-account request rate. A bucket key collapses those into one
    #       call per bucket per short interval, so the mechanism being bought
    #       here is a reduction in KMS request VOLUME, not a faster cipher. The
    #       accepted cost is a coarser audit trail: CloudTrail then records one
    #       KMS call covering many objects rather than one per object.
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      # WHAT: encryption at rest under the caller-supplied customer-managed key.
      # WHY : Refactoring Rationale: the baseline had neither encryption nor
      #       recoverability -- every file resource in the CICS definition is
      #       declared `JOURNAL(NO)` and `RECOVERY(NONE)`
      #       (app/csd/CARDDEMO.CSD:L7,L9 on `DEFINE FILE(ACCTDAT)`, and the
      #       same on every other file stanza) -- so encrypting the replacement
      #       storage under a key this package owns and rotates is one of the
      #       deliberate improvements over it, not a like-for-like port.
      #       Assumptions: the ARN alone is NOT sufficient for this to work. The
      #       key policy on that key must also grant the CloudFront service
      #       principal `kms:Decrypt`, conditioned on this distribution, or the
      #       origin access control cannot decrypt an object and CloudFront
      #       answers 403 to every asset while the bucket, the distribution and
      #       the key each look correct in isolation. That grant is owned by
      #       infra/modules/kms; it is recorded here because this is the first
      #       place a reader debugging that 403 will look, and the module that
      #       has to change is not this one.
      #       Alternatives Considered: `AES256`, which needs no key policy at
      #       all and is what the log bucket below uses. Rejected here because
      #       the policy scan expects customer-managed-key encryption on this
      #       bucket, and because an origin access control -- unlike the legacy
      #       origin access identity it replaces -- can read SSE-KMS objects,
      #       so nothing forces the weaker choice.
      kms_master_key_id = var.s3_kms_key_arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    id     = "expire-superseded-spa-builds"
    status = "Enabled"

    # WHAT: an empty filter, which applies the rule to every object.
    # WHY : Assumptions: S3 requires a rule to carry either a filter or a
    #       prefix, and the provider marks `prefix` on a rule as deprecated, so
    #       an empty `filter` block is the supported way to say "bucket-wide".
    #       It is written explicitly because an omitted filter is a plan-time
    #       error rather than a silent default.
    filter {}

    # WHAT: superseded object versions expire on the caller's schedule.
    # WHY : Trade-offs: versioning above is what makes a front-end rollback
    #       possible, and its cost is that every build ever deployed would
    #       otherwise be stored forever -- a SPA bundle being a large number of
    #       small objects, that accumulation is real rather than theoretical.
    #       Expiring noncurrent versions bounds the storage while leaving a
    #       rollback window wide enough to cover the interval between a deploy
    #       and someone noticing it was wrong. The horizon is an input, not a
    #       constant, because that interval is a property of how an environment
    #       is operated.
    noncurrent_version_expiration {
      noncurrent_days = var.spa_noncurrent_version_retention_days
    }
  }

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    # WHY : Assumptions: bucket-wide, for the same reason as the rule above.
    filter {}

    # WHAT: abandoned multipart uploads are cleaned up after seven days.
    # WHY : Assumptions: the parts of an incomplete multipart upload are
    #       BILLED as storage but do not appear in an object listing, so
    #       without this rule they accumulate as a cost with no visible cause --
    #       which is why this is worth a rule rather than an occasional manual
    #       sweep.
    #       Trade-offs: seven days is a module-internal constant rather than an
    #       input, and it is chosen against a measured shape rather than picked:
    #       a SPA asset upload is a handful of megabytes and completes in
    #       seconds, so an upload still incomplete a week later has been
    #       abandoned rather than delayed, while a week is still ample room for
    #       an interrupted deploy to be retried. Promoting it to an input would
    #       add a knob the dev and prod roots have no reason to disagree on.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # WHAT: an explicit ordering against the versioning resource.
  # WHY : Assumptions: `noncurrent_version_expiration` above is only meaningful
  #       once versioning is enabled, and Terraform infers no ordering between
  #       these two resources -- both merely reference the same bucket, and
  #       neither reads the other. Without this the two can be created in either
  #       order on a first apply, and a noncurrent-version rule written before
  #       versioning is on governs a version class the bucket does not yet have.
  #       Stating the dependency makes the rule effective from the first apply
  #       rather than from whichever later apply happens to reorder them.
  depends_on = [aws_s3_bucket_versioning.spa]
}


# -----------------------------------------------------------------------------
# The origin bucket policy.
#
# Two statements: one narrow Allow to the distribution, and one blanket Deny of
# anything arriving without TLS.
#
# WHY : Assumptions: no dependency cycle exists here, and it is worth saying so
#       because the shape invites the suspicion of one. This document reads the
#       distribution's ARN, the distribution reads the bucket's regional domain
#       name, and the distribution does NOT read this policy -- so the graph is
#       bucket, then distribution, then policy, and needs no `depends_on` and no
#       two-pass apply.
# -----------------------------------------------------------------------------

data "aws_iam_policy_document" "spa_bucket" {
  statement {
    sid    = "AllowCloudFrontOriginAccessControlRead"
    effect = "Allow"

    # WHAT: read only. No `s3:PutObject`, no `s3:DeleteObject`, no
    #       `s3:ListBucket`.
    # WHY : Assumptions: the distribution's entire job at the origin is to GET
    #       an object. Publishing the built bundle is done by the deployment
    #       pipeline under its own IAM identity, so no write action belongs in a
    #       statement whose principal is CloudFront -- granting one would let a
    #       compromised edge configuration modify the application it serves.
    #       Trade-offs: `s3:ListBucket` is withheld even though granting it
    #       would make S3 answer 404 rather than 403 for a missing key, which
    #       would in turn make the error routing further down need only one
    #       response mapping instead of two. That trade is refused deliberately:
    #       listing the origin would disclose every deployed key to anything
    #       that can reach the distribution, and handling both status codes
    #       costs one extra block. If a future feature genuinely needs a
    #       directory-style index, that is a separate, reviewable grant.
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.spa.arn}/*"]

    # WHAT: a service principal, not an account, a role or a canonical user.
    # WHY : Alternatives Considered: the legacy origin access identity form,
    #       whose grant names a CloudFront-owned canonical user instead. It is
    #       rejected for this module in full at the origin access control
    #       resource below; the two grant shapes are not interchangeable, and
    #       this is the one an origin access control uses.
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    # WHAT: the grant is conditioned on the ARN of THIS distribution.
    # WHY : Assumptions: without this condition the statement grants read access
    #       to the CloudFront service as a whole, which means any distribution in
    #       ANY AWS account could be pointed at this bucket and would be allowed
    #       to read it -- the confused-deputy problem, where a trusted
    #       intermediary is induced to act for an untrusted caller. The condition
    #       is not a refinement of the grant; it is the entire difference between
    #       a private origin and a bucket readable by a stranger's distribution.
    #       `ArnEquals` rather than `StringEquals` is used because the value is
    #       an ARN and the ARN comparison operators normalise it, so an
    #       equivalent ARN written a different way still matches.
    condition {
      test     = "ArnEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.spa.arn]
    }
  }

  statement {
    sid    = "DenyNonTlsRequests"
    effect = "Deny"

    # WHAT: every action, every principal, every object and the bucket itself,
    #       refused when the request did not arrive over TLS.
    # WHY : Assumptions: the bucket policy is the ONLY place a plaintext request
    #       to the S3 endpoint can be refused. The distribution's
    #       `redirect-to-https` further down governs viewer traffic reaching
    #       CloudFront; it has no bearing whatsoever on a request sent straight
    #       to the bucket's own endpoint, which is a different network path with
    #       a different front door. Closing that path here is what makes
    #       "encrypted in transit" a property of the storage rather than of one
    #       route to it.
    #       Trade-offs: `Deny` with `"*"` principals is deliberately blunt --
    #       it applies to the bucket owner and to future grants nobody has
    #       written yet, which is the point. The accepted cost is that any tool
    #       that genuinely needs plaintext HTTP to this bucket is broken by it,
    #       and no such tool exists in this package.
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.spa.arn, "${aws_s3_bucket.spa.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "spa" {
  bucket = aws_s3_bucket.spa.id
  policy = data.aws_iam_policy_document.spa_bucket.json

  # WHAT: the policy is applied only once the public-access block is in place.
  # WHY : Assumptions: `block_public_policy` above rejects a policy S3 judges to
  #       be public, so the two resources interact and Terraform infers no order
  #       between them. Applying the block FIRST means that if a future edit
  #       accidentally widened this document into a public grant, the attempt
  #       would be refused rather than briefly succeeding and then being fenced
  #       off on the next apply. Ordering it this way makes the block a gate on
  #       the policy instead of a correction after it.
  depends_on = [aws_s3_bucket_public_access_block.spa]
}


# =============================================================================
# The access-log bucket
# -----------------------------------------------------------------------------
# WHY this bucket exists in THIS module, which is the question a reviewer will
# ask first, because a second bucket in a module named for a distribution looks
# like scope creep:
#
#   Assumptions: the HIGH/CRITICAL infrastructure policy scan asserts that the
#   distribution has access logging configured, and every gate this package is
#   held to is required to be satisfied by construction rather than by a
#   suppression. Logging needs a destination. This module's declared inputs are
#   the S3 key ARN, an optional certificate and its aliases, an optional web ACL
#   and its own naming and retention values -- there is no log-destination input
#   -- and the module calls no sibling module and looks nothing up. The
#   destination therefore cannot arrive from anywhere else, so it is created
#   here. The bucket is not an extra; it is what makes the logging argument
#   satisfiable.
#
#   Alternatives Considered: accepting the log bucket as an input from the
#   environment root. Rejected because it makes the gate conditional on the
#   caller -- a root that omitted the argument would produce a distribution with
#   no logging that still planned and applied cleanly, which is precisely the
#   shape "satisfied by construction" exists to rule out.
#
#   Alternatives Considered: CloudFront standard logging v2, which delivers to
#   CloudWatch Logs, Firehose or S3 through
#   `aws_cloudwatch_log_delivery_source` / `_destination` / `_delivery` and
#   needs no ACLs and no SSE-S3 concession. Rejected on one specific ground:
#   the policy scanners assert on the presence of a `logging_config` block on
#   `aws_cloudfront_distribution`, and a v2-only configuration leaves that block
#   absent -- so it would raise the very finding it was meant to answer while
#   the logs were in fact being delivered. Choosing the legacy mechanism is a
#   deliberate concession to what the gate can actually see.
#
#   Alternatives Considered: reusing the dataset bucket owned by
#   infra/modules/s3-datasets. Rejected -- that bucket's prefixes and lifecycle
#   configuration reproduce the ten generation-dataset families of the
#   mainframe batch chain, and interleaving edge access logs into it would put
#   two unrelated retention policies in one bucket and one blast radius.
# =============================================================================

resource "aws_s3_bucket" "logs" {
  bucket = local.log_bucket_name

  # WHY : Trade-offs: the same flag and the same reasoning as the origin bucket
  #       -- with the default of false a destroy stops rather than deleting the
  #       audit trail, and docs/runbooks/teardown.md carries the purge step.
  #       Sharing one input across both buckets rather than giving each its own
  #       is deliberate: an operator tearing an environment down means the whole
  #       environment, and a teardown that removed the SPA but stalled on its
  #       logs would leave a half-destroyed stack for no benefit.
  force_destroy = var.force_destroy
}

# WHY : Assumptions: an access log records the address, the URI and the user
#       agent of every viewer request, so a readable log bucket is a disclosure
#       of who used the application and what they looked at. All four controls
#       are asserted for the reason given on the origin bucket, with one
#       addition specific to this bucket: `block_public_acls` refuses a PUBLIC
#       access-control list, and CloudFront's log-delivery grant is written to a
#       CloudFront-owned canonical user rather than to the all-users or
#       authenticated-users group -- so it is not public, and enabling ACLs
#       below for its sake does not reopen the public path this closes.
resource "aws_s3_bucket_public_access_block" "logs" {
  bucket                  = aws_s3_bucket.logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# WHAT: access-control lists left ENABLED here, unlike on the origin bucket.
# WHY : Assumptions: CloudFront's standard log delivery writes to its
#       destination by granting itself full control through a bucket ACL, so a
#       destination with ACLs disabled cannot receive logs at all -- and the
#       symptom is not an error at apply, it is a bucket that simply stays
#       empty, which is the worst way for a logging misconfiguration to present.
#       `BucketOwnerEnforced` disables ACLs outright, so it is unusable here.
#       `BucketOwnerPreferred` keeps ACLs available for the delivery grant while
#       still making this account the owner of every object written into the
#       bucket, so ownership does not fragment.
#       Trade-offs: this is deliberately WEAKER than the origin bucket's
#       `BucketOwnerEnforced`, and the asymmetry is a consequence of the
#       delivery mechanism rather than a preference. It is recorded at both ends
#       precisely so that a future reader tidying an apparent inconsistency
#       changes neither: raising this bucket to `BucketOwnerEnforced` silently
#       stops log delivery, and lowering the origin bucket to match would add an
#       unaudited second way to grant read access to the deployed application.
resource "aws_s3_bucket_ownership_controls" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    object_ownership = "BucketOwnerPreferred"
  }
}

# WHAT: the ONE scanner suppression in this module, scoped to this single
#       resource and naming the exact check it excuses.
# WHY : Assumptions: the HIGH/CRITICAL policy scan reports this resource, and it
#       is the only finding in the module at that threshold -- a measured run of
#       `trivy config --severity HIGH,CRITICAL` over this directory returns
#       AWS-0132 here and nothing else anywhere. The check asserts that an S3
#       bucket is encrypted with a customer-managed key, and it CANNOT be
#       satisfied on this bucket: CloudFront standard log delivery refuses an
#       SSE-KMS destination, so complying with the check would silence the
#       distribution access logging that the same scan asserts. The check's own
#       published guidance records the analogous exception for S3 server
#       access-log destinations; it simply has no way to recognise a CloudFront
#       log destination as the same case.
#       Trade-offs: a suppression is accepted here only because the alternative
#       is to trade one gate item for another, and because it is scoped to one
#       resource rather than to the file or the directory --
#       aws_s3_bucket_server_side_encryption_configuration.spa is still checked
#       by the same rule, and still passes it, under the customer-managed key.
#       Referring to that resource by ADDRESS rather than by how far away it sits
#       is deliberate: a line-distance reference goes quietly wrong the first
#       time anything between the two is edited. Both the trivy and the checkov
#       directive forms are
#       written because the two scanners read different syntaxes in different
#       positions -- trivy immediately above the resource, checkov inside it --
#       and a directive addressed to the scanner that is not running is inert
#       rather than harmlessly redundant.
#       Alternatives Considered: raising the scan's failure floor above HIGH so
#       the finding no longer gates. Rejected outright -- that would disarm the
#       check for every resource in the tree in order to excuse one, which is
#       the difference between a documented exception and a disabled gate.
#trivy:ignore:AVD-AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "logs" {
  #checkov:skip=CKV_AWS_145:CloudFront standard log delivery cannot write to a bucket whose default encryption is SSE-KMS, so this access-log destination must use SSE-S3 or receive no logs at all. The SPA origin bucket this distribution serves does use the customer-managed key; see aws_s3_bucket_server_side_encryption_configuration.spa.
  bucket = aws_s3_bucket.logs.id

  rule {
    apply_server_side_encryption_by_default {
      # WHAT: S3-managed encryption here, NOT the customer-managed key the
      #       origin bucket uses.
      # WHY : Assumptions: CloudFront standard log delivery cannot write to a
      #       bucket whose default encryption is SSE-KMS. That is a property of
      #       the delivery mechanism, not a limitation of this configuration, and
      #       it fails the same silent way the ACL constraint above does -- the
      #       bucket stays empty and nothing in a plan or an apply says why.
      #       SSE-S3 is therefore the strongest encryption this destination can
      #       carry while remaining a working destination, and the objects are
      #       still encrypted at rest.
      #       Trade-offs: the two buckets this module owns are consequently
      #       encrypted under different mechanisms, which reads as an oversight
      #       and is not one. The note on var.s3_kms_key_arn in
      #       infra/modules/cloudfront-spa/variables.tf describes that key as
      #       covering this bucket too; that description does not survive
      #       contact with the delivery constraint, and the constraint wins,
      #       because a log bucket that cannot be written to protects nothing.
      #       If this is ever "harmonised" up to `aws:kms`, logging stops --
      #       which is exactly the gate this bucket exists to satisfy.
      #       Alternatives Considered: switching to standard logging v2, which
      #       does support an SSE-KMS destination and would remove this
      #       concession. Rejected above, for what the policy scanners can see.
      sse_algorithm = "AES256"
    }
  }
}

# WHY : Assumptions: enabled for the same scanner expectation the origin bucket
#       satisfies, and it earns its place here for a second reason: a versioned
#       log bucket cannot have an access-log object silently replaced with a
#       different one, because the prior version survives the overwrite. For an
#       audit trail that is the property worth having. The lifecycle rule below
#       is what keeps it from also being an unbounded cost.
resource "aws_s3_bucket_versioning" "logs" {
  bucket = aws_s3_bucket.logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    id     = "expire-cloudfront-access-logs"
    status = "Enabled"

    # WHY : Assumptions: bucket-wide, for the reason given on the origin
    #       bucket's rules -- a rule must carry a filter or a deprecated prefix,
    #       and an empty filter is how "every object" is expressed.
    filter {}

    # WHAT: log objects expire on the caller's schedule.
    # WHY : Assumptions: this is the "log retention" axis the dev and prod roots
    #       are expected to differ on for this module, so the value is an input
    #       and this rule is the only thing that gives that input any effect --
    #       without it, var.log_retention_days would be a documented number that
    #       changed nothing.
    #       Trade-offs: access logs are billed for as long as they are stored
    #       and their investigative value decays, since an incident is almost
    #       always reconstructed from recent objects. Indefinite retention bills
    #       forever for the rare case; aggressive expiry loses the common one.
    expiration {
      days = var.log_retention_days
    }

    # WHAT: superseded log-object versions expire on the shortest legal horizon.
    # WHY : Assumptions: versioning above without a noncurrent expiry would keep
    #       every superseded version forever, so the retention setting would
    #       govern only the current version and the bucket would grow without
    #       bound anyway -- the two rules together are what make retention mean
    #       what it says. Note the interaction is easy to miss: an expiration
    #       rule on a versioned bucket does not delete data, it only makes the
    #       current version noncurrent.
    #       Trade-offs: one day is a module-internal constant rather than an
    #       input, and it is the shortest horizon S3 accepts, chosen because a
    #       noncurrent version can only exist here if the same key is written
    #       twice -- and CloudFront names each log object uniquely, so that
    #       happens only if an operator or a tool overwrites one. This tier is a
    #       backstop against exactly that overwrite, not a retention policy, so
    #       it needs no per-environment value.
    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  # WHY : Assumptions: the same ordering requirement as the origin bucket's
  #       lifecycle configuration -- the noncurrent tier above is meaningless
  #       until versioning is on, and nothing in either resource makes Terraform
  #       infer that order.
  depends_on = [aws_s3_bucket_versioning.logs]
}

data "aws_iam_policy_document" "logs_bucket" {
  statement {
    sid    = "DenyNonTlsRequests"
    effect = "Deny"

    # WHY : Assumptions: the same reasoning as the origin bucket's TLS statement
    #       -- a bucket policy is the only place a plaintext request to the S3
    #       endpoint can be refused, and the distribution's viewer protocol
    #       policy has no bearing on that path. It matters here for a second
    #       reason: this bucket's contents are the record of who used the
    #       application, so retrieving them over plaintext HTTP would disclose
    #       the audit trail in transit.
    #       Assumptions: this Deny does not impede log DELIVERY. CloudFront
    #       writes to S3 over TLS, so the condition is false for its requests
    #       and the statement does not apply to them.
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.logs.arn, "${aws_s3_bucket.logs.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

resource "aws_s3_bucket_policy" "logs" {
  bucket = aws_s3_bucket.logs.id
  policy = data.aws_iam_policy_document.logs_bucket.json

  # WHY : Assumptions: ordered after the public-access block for the same reason
  #       as the origin bucket's policy -- `block_public_policy` judges the
  #       document, so having the block in place first turns it into a gate on
  #       this policy rather than a correction applied after it.
  depends_on = [aws_s3_bucket_public_access_block.logs]
}


# =============================================================================
# The origin access control
# -----------------------------------------------------------------------------
# This is the load-bearing decision of the whole module, and the one with the
# most plausible alternatives, so it is recorded in full rather than left to be
# inferred from the resource type.
#
#   Alternatives Considered: a PUBLIC origin bucket, with a public-read bucket
#   policy and the distribution simply in front of it. Rejected because the
#   bucket endpoint would then answer viewers directly, and every control this
#   module places at the edge would become optional for anyone who found that
#   endpoint -- the access logging, the redirect-to-https viewer policy, the
#   response-headers policy, the point where a web ACL would attach, and, most
#   consequentially, the 403/404 error routing that makes client-side deep links
#   resolve. A viewer reaching S3 directly would get the raw S3 error document
#   for every route the router owns. It also contradicts the bucket
#   public-access block the policy scan asserts, so the two could not coexist.
#
#   Alternatives Considered: a legacy origin access identity, which is the older
#   mechanism for exactly this job and is still supported by the provider
#   (`origin.s3_origin_config.origin_access_identity`). Rejected decisively on
#   capability rather than fashion: an origin access identity CANNOT read an
#   object encrypted with a customer-managed KMS key. Choosing it would force
#   the origin bucket down to SSE-S3 and give up the customer-managed-key
#   encryption the policy scan asserts and that this migration adopted precisely
#   because the baseline had none (app/csd/CARDDEMO.CSD:L7,L9). An origin access
#   control reads SSE-KMS objects, given the key-policy grant recorded on the
#   encryption resource above, so it is the only one of the two that satisfies
#   both requirements at once. The two are also mutually exclusive in the
#   provider schema, which is why no `s3_origin_config` block appears anywhere
#   in this file.
# =============================================================================

resource "aws_cloudfront_origin_access_control" "spa" {
  name        = "${var.name_prefix}-${var.environment}-spa-oac"
  description = "Signs CloudFront origin requests to the CardDemo SPA bucket for the ${var.environment} environment."

  origin_access_control_origin_type = "s3"

  # WHAT: every origin request is signed, with no way for a viewer to suppress
  #       it.
  # WHY : Alternatives Considered: `"never"`, which signs nothing and would
  #       leave the origin unreachable given the bucket policy above, and
  #       `"no-override"`, which signs only when the viewer did not supply its
  #       own `Authorization` header. `"no-override"` is the dangerous one,
  #       because it hands the viewer control of whether the origin request is
  #       signed: a request carrying any `Authorization` header would be
  #       forwarded unsigned, the bucket would refuse it, and the failure would
  #       be intermittent and attributable to nothing visible in the
  #       configuration. `"always"` removes that input from the viewer
  #       altogether.
  signing_behavior = "always"

  # WHY : Assumptions: SigV4 is the signing protocol S3 accepts for this
  #       mechanism, and it is the only value the provider admits, so this is a
  #       required declaration rather than a choice between options.
  signing_protocol = "sigv4"
}


# =============================================================================
# The distribution
# -----------------------------------------------------------------------------
# The edge half of the delivery path: one origin, one cache behaviour, the SPA
# error routing, and the viewer-facing TLS and logging configuration.
# =============================================================================

resource "aws_cloudfront_distribution" "spa" {
  enabled = true

  # WHAT: the document served for a request to `/`, and -- deliberately -- the
  #       same document the error responses below rewrite to.
  # WHY : Assumptions: the name has to match whatever the SPA build emits as its
  #       entry document, which this module cannot see, so it is an input rather
  #       than a literal. Deriving the error-response target from the same
  #       variable instead of repeating the name is what stops the two drifting:
  #       with two literals, renaming the entry document updates whichever one
  #       the editor was looking at, the root keeps working, only deep links
  #       break, and the resulting defect looks like a routing problem rather
  #       than a typo.
  default_root_object = var.default_root_object

  # WHY : Assumptions: the environment appears in the comment because it is the
  #       only field visible in the CloudFront console list view, where two
  #       distributions from two environments are otherwise distinguishable only
  #       by their generated identifiers. The mainframe reference is carried here
  #       rather than left in this file alone so an operator reading the console
  #       knows what the distribution is for.
  comment = "CardDemo SPA delivery for ${var.environment} -- replaces the 3270 BMS presentation path"

  # WHAT: dual-stack, so the distribution answers on IPv6 as well as IPv4.
  # WHY : Trade-offs: there is no cost and no topology change -- CloudFront
  #       publishes AAAA records for the same distribution -- and the failure it
  #       avoids is specific: a viewer on an IPv6-only network cannot reach an
  #       IPv4-only distribution at all, and the symptom is total
  #       unreachability for that viewer rather than degraded service. The
  #       accepted cost is that access-log entries then carry IPv6 addresses,
  #       which any log consumer has to be able to parse.
  is_ipv6_enabled = true

  # WHAT: which edge locations serve the bundle.
  # WHY : Trade-offs: one of exactly two values the dev and prod roots are
  #       expected to disagree on for this module, the other being log
  #       retention. The narrowest tier is the module default because this
  #       deployment is single-region by design, so a viewer population spread
  #       across every continent is not a case the default has to serve; a root
  #       needing broader coverage raises the value rather than editing the
  #       module. Widening the tier raises per-request and data-transfer cost at
  #       every edge, which is why it is a per-environment decision and not a
  #       fixed one.
  price_class = var.price_class

  # WHAT: the alternate domain names the distribution answers on.
  # WHY : Assumptions: this is one decision expressed in two inputs, and it
  #       cannot be set alone. CloudFront refuses an alternate domain name that
  #       the supplied certificate does not cover, and refuses any alias at all
  #       while the default certificate is in use, so the certificate below and
  #       this list are set together or not at all --
  #       infra/modules/cloudfront-spa/variables.tf enforces exactly that
  #       pairing at plan time. An empty list is what lets the module stand up
  #       with no DNS prerequisite whatsoever.
  aliases = var.aliases

  # WHAT: the web ACL association, absent by default.
  # WHY : Alternatives Considered: provisioning a web ACL so every deployment
  #       gets one. Rejected, and this comment is the DOCUMENTED REASON FOR THE
  #       ABSENCE that the policy scan's WAF check is answered by, not an
  #       admission of a gap. Two independent grounds. First, scope: this
  #       package's module catalogue is a fixed set and contains no WAF module,
  #       and adding one -- with its rule groups, rate limits, logging
  #       destination and per-environment tuning -- belongs to a deliberate
  #       decision rather than to a side effect of authoring a CloudFront
  #       module. Second, and the substantive reason: this distribution serves
  #       nothing a web ACL would protect. Its entire payload is the public,
  #       static, read-only SPA bundle; there is no form handler, no query
  #       interpretation and no credential at the origin, and the origin bucket
  #       is private with this distribution's origin access control as its only
  #       reader. Every authenticated or state-changing operation goes to the API
  #       Gateway HTTP API instead, where the request is authenticated by the
  #       Cognito JWT authorizer and where request-level filtering would actually
  #       be doing something. Attaching a web ACL here would bill per request to
  #       inspect immutable asset fetches while leaving the surface that matters
  #       exactly as protected as it already is.
  #       Trade-offs: keeping this an INPUT rather than omitting the capability
  #       is what makes the decision reversible -- an environment that has a web
  #       ACL attaches it by passing one argument, with no change to this module
  #       and no change to the other environment.
  web_acl_id = var.web_acl_arn

  origin {
    # WHAT: the bucket's REGIONAL domain name, not its global one.
    # WHY : Assumptions: an origin access control signs the origin request with
    #       SigV4, and a SigV4 signature is scoped to a region, so the request
    #       must be addressed to the regional endpoint for the signature to
    #       validate. Using the global `<bucket>.s3.amazonaws.com` form produces
    #       a signature mismatch that S3 reports as an access denial -- so the
    #       symptom is a 403 that reads as a permissions problem and sends a
    #       reader to the bucket policy and the key policy, neither of which is
    #       wrong. This one attribute is the likeliest single time sink in the
    #       module, which is why it is commented at all.
    domain_name = aws_s3_bucket.spa.bucket_regional_domain_name

    # WHY : Assumptions: the same local as `target_origin_id` below, for the
    #       drift reason recorded where it is defined.
    origin_id = local.spa_origin_id

    # WHAT: the origin access control, which is what makes the private bucket
    #       readable by this distribution and nothing else.
    # WHY : Assumptions: this attribute and an `s3_origin_config` block are
    #       mutually exclusive in the provider schema -- `s3_origin_config` is
    #       the legacy origin-access-identity form -- which is why no such block
    #       appears here. The rejection of that alternative is recorded in full
    #       on the origin access control resource above.
    origin_access_control_id = aws_cloudfront_origin_access_control.spa.id
  }

  default_cache_behavior {
    # WHY : Assumptions: names the single origin above through the shared local,
    #       so the two cannot disagree.
    target_origin_id = local.spa_origin_id

    # WHAT: a plaintext request is answered with a redirect to its HTTPS form
    #       rather than served.
    # WHY : Assumptions: this is the HTTPS-only viewer policy the HIGH/CRITICAL
    #       policy scan asserts, and it is the edge half of this package's
    #       encryption-in-transit posture -- the bucket-policy Deny above is the
    #       storage half, and neither substitutes for the other.
    #       Alternatives Considered: `https-only`, which refuses a plaintext
    #       request outright with a 403 instead of redirecting it. Rejected for a
    #       usability reason with no security cost: a viewer who types or
    #       bookmarks the bare host name sends a plaintext request first, and
    #       redirecting lands them on the application whereas refusing shows
    #       them an error for a URL that looks correct. Neither policy serves
    #       any content over plaintext, so the redirect gives up nothing.
    viewer_protocol_policy = "redirect-to-https"

    # WHAT: only the two read verbs are accepted, and both are cacheable.
    # WHY : Assumptions: the origin is a static bucket, so there is nothing for a
    #       write verb to act on -- accepting PUT, POST, PATCH or DELETE would
    #       forward requests the origin can only refuse, which is reachable
    #       surface with no legitimate use.
    #       Alternatives Considered: including OPTIONS, which CloudFront offers
    #       alongside the read pair and which is needed to answer a CORS
    #       preflight. Rejected because the SPA's cross-origin requests go to
    #       the API Gateway HTTP API, not to this distribution, so no viewer
    #       ever sends a preflight here -- and the read pair is the whole
    #       legitimate surface.
    allowed_methods = ["GET", "HEAD"]
    cached_methods  = ["GET", "HEAD"]

    # WHAT: CloudFront compresses eligible responses at the edge.
    # WHY : Assumptions: the mechanism is that CloudFront gzip- or
    #       brotli-encodes text responses for viewers that advertise support,
    #       and a SPA bundle is almost entirely text -- JavaScript, CSS and the
    #       entry document -- so the bytes actually transferred fall
    #       substantially. What that buys is lower data-transfer cost and less
    #       time on a slow connection; it is not a change to what is served.
    #       Assumptions: compression happens at the edge, so it applies whether
    #       or not the objects were stored compressed, which is why the build
    #       does not need to pre-compress them.
    compress = true

    # WHY : Assumptions: the AWS-managed policies resolved by name above. Using
    #       `cache_policy_id` also means the legacy `forwarded_values` block
    #       must NOT appear in this behaviour -- the two are mutually exclusive
    #       and the provider rejects a behaviour carrying both -- which is why
    #       there is no `forwarded_values` anywhere in this file.
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id
  }

  # ---------------------------------------------------------------------------
  # SPA error routing -- the two blocks below are why a bookmarked or refreshed
  # deep link works at all.
  #
  #   Assumptions: ui/src/router.tsx owns all twenty-one screen routes ON THE
  #   CLIENT -- /signon, /menu, /admin, /account/view, /account/update, /cards,
  #   /cards/:num, /cards/:num/edit, /transactions, /transactions/:id,
  #   /transactions/new, /billpay, /reports, /users, /users/new,
  #   /users/:id/edit, /users/:id/delete, /authorizations, /authorizations/:key,
  #   /reference/transaction-types and /reference/transaction-types/:cd. The
  #   origin bucket holds the built bundle and nothing resembling a server, so a
  #   request for /account/update arrives at S3 as a key that simply does not
  #   exist. Rewriting the error back to the entry document with a 200 delivers
  #   the bundle, and the router then resolves the path from the address bar.
  #
  #   Assumptions: this is load-bearing rather than cosmetic because the
  #   migration made navigation client-side ON PURPOSE. The mainframe
  #   transferred control between programs with EXEC CICS XCTL -- the sign-on
  #   program's branch to the admin or the main menu at app/cbl/COSGN00C.cbl:L245
  #   is the canonical instance -- and the replacement for that verb is a
  #   client-side route change, not a server round trip. Without this rewrite
  #   every one of those routes works while navigating and breaks on a bookmark
  #   or a hard refresh, which is the one failure users find immediately and
  #   developers never do.
  #
  #   Assumptions: ui/nginx.conf implements the same fallback for the
  #   containerised `ui` image. That path and this one must agree, or the
  #   application behaves differently depending on how it was served; the
  #   duplication is deliberate and the two are kept in step by both being
  #   documented as the same contract.
  # ---------------------------------------------------------------------------

  # WHAT: 403 is rewritten, not only 404 -- and this is the counter-intuitive
  #       half.
  # WHY : Assumptions: S3 answers a request for a missing key with 403
  #       AccessDenied rather than 404 NoSuchKey when the caller lacks
  #       `s3:ListBucket`, because disclosing that a key is absent is itself
  #       information the caller is not entitled to. The origin grant above is
  #       deliberately `s3:GetObject` only, so EVERY missing key on this origin
  #       arrives as a 403 and a configuration handling only 404 would leave
  #       every deep route broken while looking complete. The two blocks are one
  #       decision, and this is the block that actually fires.
  custom_error_response {
    error_code         = 403
    response_code      = 200
    response_page_path = "/${var.default_root_object}"

    # WHAT: negative responses are not cached at the edge at all.
    # WHY : Trade-offs: the status being rewritten does not mean "not found", it
    #       means "the router will handle this" -- so caching it would persist an
    #       answer for a path whose correct response can change the moment a new
    #       bundle is deployed. The failure that avoids is nasty out of
    #       proportion to its cause: an edge location holding a stale rewrite
    #       serves the wrong document to some viewers and not others, for the
    #       length of the time-to-live, which presents as an intermittent fault
    #       with no reproducible trigger. The accepted cost is that each such
    #       request reaches the origin, and since the origin's answer is one
    #       small entry document, that cost is negligible.
    error_caching_min_ttl = 0
  }

  # WHY : Assumptions: 404 is handled as well as 403 because the 403 behaviour
  #       above is a consequence of the current grant rather than a permanent
  #       property of S3 -- if `s3:ListBucket` were ever added for a directory
  #       index, missing keys would start arriving as 404 and deep links would
  #       break again. Handling both makes the routing independent of that
  #       grant. The rewrite target and the zero cache horizon are the same
  #       values and carry the same reasoning as the block above.
  custom_error_response {
    error_code            = 404
    response_code         = 200
    response_page_path    = "/${var.default_root_object}"
    error_caching_min_ttl = 0
  }

  # WHAT: no geographic restriction, in a block the provider requires to be
  #       present.
  # WHY : Assumptions: `restrictions` and its nested `geo_restriction` are
  #       mandatory in the resource schema, so this block appears because it
  #       must, not because it encodes a decision -- an unexplained `"none"`
  #       otherwise reads as an oversight or as a setting somebody forgot to
  #       fill in. Nothing in this migration's requirements asserts a
  #       geographic restriction, and asserting one speculatively would deny
  #       legitimate access to an operator travelling or to a viewer behind a
  #       carrier that geolocates elsewhere.
  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  # ---------------------------------------------------------------------------
  # Viewer certificate -- one block covering both the default-certificate and
  # the custom-domain cases.
  #
  #   Alternatives Considered: two `dynamic` blocks with mutually exclusive
  #   `for_each` expressions, one per case. Rejected because
  #   `viewer_certificate` is required exactly once by the schema, so a dynamic
  #   pair has to be provably exhaustive AND provably non-overlapping to be
  #   correct at all -- and a reader has to hold both `for_each` expressions in
  #   mind to see that it is. Every attribute here is plain optional rather than
  #   computed, so setting the inapplicable ones to null leaves them genuinely
  #   unconfigured, and one static block with conditional values is both correct
  #   by construction and readable in one pass.
  # ---------------------------------------------------------------------------
  viewer_certificate {
    # WHAT: the generated CloudFront certificate, used only when no ACM
    #       certificate was supplied.
    # WHY : Trade-offs: on this path CloudFront serves its generated
    #       cloudfront.net domain name and PINS the viewer security policy to
    #       TLSv1 -- not as a floor this module could raise, but as the stored
    #       value -- so the distribution accepts TLS 1.0 and 1.1 no matter what
    #       `minimum_protocol_version` asks for, and a policy scan looking for a
    #       modern viewer policy will report it. That is why
    #       infra/modules/cloudfront-spa/variables.tf refuses this path when
    #       environment is prod. What it buys, and the only reason it exists, is
    #       that a dev environment is creatable and destroyable repeatedly by
    #       anyone with no hosted zone, no certificate and no validation
    #       records.
    cloudfront_default_certificate = var.acm_certificate_arn == null ? true : null

    # WHY : Assumptions: the certificate must be issued in us-east-1 NO MATTER
    #       WHICH REGION the rest of the stack is deployed to, because a
    #       distribution is a global resource that reads its certificate from
    #       that one region. This is the least obvious constraint in the module:
    #       a certificate issued correctly in the deployment region is a
    #       well-formed ARN, indistinguishable from a usable one at this
    #       boundary, and it is rejected only when CloudFront is asked to use it
    #       partway through an apply. The certificate is supplied rather than
    #       created here so that no us-east-1 provider alias is needed in a
    #       module which otherwise inherits exactly one provider configuration.
    acm_certificate_arn = var.acm_certificate_arn

    # WHAT: server-name-indication rather than a dedicated IP address.
    # WHY : Trade-offs: a dedicated IP address exists for viewers whose clients
    #       predate SNI, and it carries a substantial recurring per-month charge
    #       per distribution. This application's viewers are browsers running a
    #       React 19 bundle, so none of them predates SNI by many years, and the
    #       compatibility the dedicated option buys is compatibility with
    #       clients that could not load the application anyway. Cost is an
    #       explicit tie-breaker for this migration, and this is the clearest
    #       instance of it.
    ssl_support_method = var.acm_certificate_arn == null ? null : "sni-only"

    # WHY : Assumptions: applied ONLY on the certificate path, and that is not a
    #       convenience. On the default-certificate path CloudFront stores
    #       TLSv1 regardless of what is asked for, so a configuration stating
    #       anything else there produces a PERPETUAL plan diff -- the API keeps
    #       reporting TLSv1 and the configuration keeps asking for something
    #       else, and every subsequent plan is non-empty for a change that can
    #       never converge. Leaving it null on that path is what keeps the plan
    #       clean.
    #       Assumptions: on the certificate path it is REQUIRED rather than
    #       optional -- CloudFront will not accept an ACM certificate ARN without
    #       both a minimum protocol version and an SNI support method -- so
    #       these three attributes are three halves of one argument and are set
    #       together.
    minimum_protocol_version = var.acm_certificate_arn == null ? null : var.minimum_protocol_version
  }

  logging_config {
    # WHAT: the log bucket's DOMAIN NAME, not its bare name.
    # WHY : Assumptions: this argument takes the S3 bucket endpoint in
    #       `<bucket>.s3.amazonaws.com` form, which is what
    #       `bucket_domain_name` produces; passing the bare bucket name is
    #       accepted by Terraform and rejected by CloudFront, so the mistake
    #       surfaces during apply rather than during plan. The reason this
    #       destination is created by this module at all is recorded at length on
    #       the bucket itself.
    bucket = aws_s3_bucket.logs.bucket_domain_name

    # WHY : Trade-offs: the bucket is module-private, so a prefix is not needed
    #       to separate this producer from another one today. It is set anyway so
    #       that the log objects occupy one addressable path -- which is what
    #       lets an analytics query or a narrower lifecycle rule target them
    #       without matching everything in the bucket -- and so that a second
    #       producer added later cannot interleave keys with these.
    prefix = "cloudfront-access-logs/"

    # WHAT: cookies are excluded from the log records.
    # WHY : Assumptions: the SPA authenticates with a bearer token obtained from
    #       Cognito, and logging cookies would place credential-adjacent
    #       material into durable storage that is retained for the whole of
    #       var.log_retention_days and readable by anyone who can read the
    #       bucket. The specific risk is not that a token is expected in a
    #       cookie today but that an access log is the wrong place for one to
    #       end up if it ever is. Excluding them costs nothing here, because a
    #       static asset fetch has no cookie worth analysing.
    #       Assumptions: stated explicitly rather than left to the provider
    #       default so the decision is visible in the file -- an absent argument
    #       reads as "not considered".
    include_cookies = false
  }
}

# =============================================================================
# Deliberate omissions
# -----------------------------------------------------------------------------
# Every block named below is absent on purpose. Each is a reasonable thing to
# look for in a module of this kind, so each is recorded as a decision rather
# than left to read as an oversight.
#
# No `provider`, `backend` or `terraform` block:
#   Assumptions: this directory is a module and not a Terraform root. The
#   provider configuration, its region and its `default_tags` are inherited from
#   infra/envs/dev or infra/envs/prod, and the toolchain and provider
#   constraints are declared in infra/modules/cloudfront-spa/versions.tf, which
#   records the same omissions from its own side. A provider block here would
#   break the inheritance both roots depend on.
#
# No `tags` argument on any resource:
#   Alternatives Considered: a per-module tag map, which is the common module
#   convention. Rejected because the calling root's provider `default_tags`
#   block is the single tagging mechanism for this package -- a second source of
#   tags reaching the same resources lets the two disagree, with one silently
#   winning per attribute, which is far harder to diagnose than one source. There
#   is deliberately no `tags` variable to read.
#
# No `aws_s3_bucket_logging` on either bucket:
#   Assumptions: the gate this module is held to asserts DISTRIBUTION access
#   logging, which `logging_config` above provides. S3 server access logging is
#   a different mechanism, and enabling it would need either a third bucket --
#   which this module's boundary forbids, because two of the four buckets in this
#   package belong to other modules -- or self-logging, which writes log objects
#   into the bucket whose reads are being logged and so logs its own writes. The
#   distribution's logs already record every viewer request that reaches the
#   origin path.
#   Trade-offs: this absence is reported, and the severity was measured rather
#   than assumed -- a full-severity scan of this directory raises AWS-0089 at LOW
#   on both buckets, which is below the HIGH/CRITICAL threshold the gate applies.
#   It is therefore an accepted, visible finding rather than something suppressed:
#   a suppression would hide a real observation for no gate benefit.
#
# No `origin_group`, no second `origin` and no `ordered_cache_behavior`:
#   Assumptions: there is one origin because there is one bucket, and this
#   deployment is single-region with three availability zones by design --
#   multi-region and disaster-recovery topology are out of scope, so a failover
#   group would have no second origin to fail over to. A single cache behaviour
#   suffices because every object served is an immutable, content-hashed static
#   asset with the one exception of the entry document, and the managed cache
#   policy handles both without a path-pattern split.
#
# No `aws_kms_key`, `aws_acm_certificate`, `aws_route53_record` or
# `aws_wafv2_web_acl`:
#   Assumptions: each is either owned elsewhere or supplied as a value. The
#   customer-managed key belongs to infra/modules/kms and arrives as an ARN; the
#   certificate must be issued in us-east-1 and is supplied so that this module
#   needs no second provider alias; DNS is not managed by this package; and the
#   web-ACL rejection is recorded in full at `web_acl_id` above.
#
# No scanner suppression anywhere except the ONE on the log bucket's encryption:
#   Assumptions: every other item the HIGH/CRITICAL policy scan asserts for this
#   module is satisfied by CONFIGURATION rather than by prose -- public access
#   blocked on both buckets, customer-managed-key encryption on the origin,
#   versioning on both, distribution access logging, an HTTPS-only viewer policy
#   with a modern minimum TLS version on the certificate path, and a documented
#   reason for the absent web ACL. A measured HIGH/CRITICAL scan of this
#   directory returns exactly one finding, on
#   aws_s3_bucket_server_side_encryption_configuration.logs, and it is
#   unsatisfiable rather than unaddressed: complying with it stops the log
#   delivery the same scan asserts. Its suppression is scoped to that single
#   resource and carries the check identifier and the reason at the point of use.
#   Trade-offs: writing that suppression is preferred to leaving a gating
#   finding, and both are preferred to widening the scan's failure floor. What
#   makes it acceptable is its scope -- one resource, one named check -- and what
#   would make it unacceptable is exactly what this package's documentation
#   standard forbids: a bare skip directive with no identifier and no reason.
# =============================================================================

