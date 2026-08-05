# =============================================================================
# infra/modules/cloudfront-spa/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input contract of the `cloudfront-spa` module. Every value the
#   module accepts from a caller is declared here and nowhere else, so this file
#   is the whole of its public parameter surface: infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf are written against the twelve names below, and
#   renaming one of them is a breaking change to both roots at once.
#
#   The module itself provisions the delivery path for the CardDemo single-page
#   application -- a private S3 origin bucket holding the built assets, reached
#   by a CloudFront distribution through an origin access control, with error
#   responses routed back to the SPA entry document so client-side deep links
#   resolve. What that path replaces on the mainframe side is recorded in
#   infra/modules/cloudfront-spa/versions.tf and is not repeated here.
#
#   WARNING: the origin bucket these inputs configure is neither of the other
#   two S3 buckets in this package. The dataset bucket belongs to
#   infra/modules/s3-datasets and the Terraform remote-state bucket belongs to
#   infra/bootstrap. All three are versioned and encrypted, which is exactly
#   what makes them easy to confuse, and no input below accepts an
#   externally-created bucket name: this module names and creates its own.
#
# Parameters:
#   Twelve variables in five groups, declared below in this order.
#     Naming .............. name_prefix, environment
#     Cross-module ........ s3_kms_key_arn
#     Environment knobs ... price_class
#     Retention and
#       protection ........ spa_noncurrent_version_retention_days,
#                           force_destroy
#     Delivery and
#       SPA routing ....... default_root_object, api_connect_src_origins,
#                           acm_certificate_arn, aliases,
#                           minimum_protocol_version, web_acl_arn
#   Each carries an explicit `type` and a `description`. Five are required and
#   have no default -- environment, s3_kms_key_arn, api_connect_src_origins,
#   acm_certificate_arn and aliases -- because each states either an identity
#   this module cannot invent or a security decision a caller must make
#   explicitly. The other seven default to values that stand up a working
#   single-region distribution with no DNS or web-ACL prerequisite.
#
# Return values:
#   None. A variables.tf returns nothing to a caller -- it declares what the
#   module accepts. Every value this module hands back is declared in
#   infra/modules/cloudfront-spa/outputs.tf.
#
# Errors / failure modes:
#   Ten `validation` blocks reject a bad value while Terraform is evaluating
#   variables, which happens at the START of a plan and before the AWS provider
#   is asked to create anything. Each surfaces as `Error: Invalid value for
#   variable`, naming the variable and carrying the message written beside it:
#     - name_prefix ....... empty, longer than 20 characters, or containing
#                           anything other than lower-case letters, digits and
#                           interior hyphens. Two separate blocks, so the
#                           message names which of the two rules was broken.
#     - environment ....... any value other than dev or prod.
#     - price_class ....... any value CloudFront does not accept.
#     - spa_noncurrent_version_retention_days ...
#                           zero, negative, or fractional.
#     - api_connect_src_origins ...
#                           any entry that is not a bare https:// scheme and
#                           host, so a wildcard, a path or a trailing slash is
#                           refused. An empty list is accepted and yields
#                           `connect-src 'self'`, which fails closed.
#     - acm_certificate_arn and aliases ...
#                           a malformed or non-us-east-1 certificate ARN, an
#                           empty alias list, or an alias carrying a scheme,
#                           port, path or trailing dot. Both are unconditional
#                           and apply in every environment; see the Refactoring
#                           Rationale on acm_certificate_arn for why the
#                           default-certificate path cannot be used at all.
#     - minimum_protocol_version ...
#                           any of the four legacy viewer policies that accept
#                           TLS 1.0 or 1.1. A deny-list rather than an
#                           allow-list, for the reason recorded on the variable.
#
#   Every one of the ten reads only the variable it is declared on, so all ten
#   are evaluated by `terraform validate` and none is deferred to `terraform
#   plan`. That is a deliberate consequence of making the certificate and alias
#   rules unconditional rather than environment-conditional: a rule that reads
#   var.environment is deferred to plan, so a pipeline step that validates
#   without planning does not exercise it, and the TLSv1 exposure the
#   certificate requirement exists to remove would reach an apply unchallenged
#   in exactly the pipeline shape that checks least.
#
#   Three failures are NOT caught here, and are named so nobody looks for them
#   in this file:
#     - the five inputs with no default are rejected by Terraform itself at the
#       call site, before any validation in this file runs, so a root that
#       omits one gets `Error: Missing required argument` naming it rather
#       than a message written here.
#     - An ACM certificate issued outside us-east-1 is a perfectly well-formed
#       ARN and is indistinguishable from a valid one here; it fails when
#       CloudFront rejects the distribution during apply.
#     - A KMS key whose policy does not grant CloudFront `kms:Decrypt` is also
#       a well-formed ARN. It applies cleanly and then answers 403 to every
#       viewer request. See the comments on acm_certificate_arn and
#       s3_kms_key_arn respectively.
#
# WHY (non-obvious design decisions):
#   - Assumptions: HCL has no docstring construct, so this header block IS the
#     entry-point documentation for this file, and each variable's
#     `description` IS that parameter's documentation. tflint's
#     terraform_documented_variables and terraform_typed_variables rules make
#     both mechanical rather than aspirational, and the lint step that runs
#     them is gating. Rationale for an individual default or validation is
#     carried adjacent to it below rather than collected up here, so a reader
#     changing one value sees why it is what it is without scrolling.
#   - Trade-offs: this contract is deliberately NARROW. The dev and prod roots
#     differ only in sizing and retention and never in topology. Nothing below
#     can change the module's shape -- there is no toggle to skip the bucket,
#     enable identifier-bearing access logging, or add a second
#     origin. What that costs is flexibility this package has no use for; what
#     it buys is that a defect reproduced in dev reproduces in prod, because
#     the two configurations differ only in numbers.
#   - Alternatives Considered: collapsing all twelve into one object-typed
#     `config` variable with `optional()` attributes, which is fewer blocks and
#     one argument at each call site. Rejected -- infra/.terraform-docs.yml
#     generates the Inputs table of this module's README.md with one row per
#     variable, showing each type, default and required flag, and that table is
#     drift-checked. A single object input would publish one opaque row and
#     hide every default and every description the check exists to keep honest.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming inputs.
#
# These two compose every resource name the module creates. They come first
# because the S3 bucket names they feed are the only names here that must be
# globally unique and are length-bounded, and so the only ones that can fail an
# apply for a reason having nothing to do with CloudFront.
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = "Name prefix shared by every resource this module creates, so the SPA bucket, response-header policy, origin access control and distribution group together in the console and in cost reports."
  type        = string
  default     = "carddemo"

  # WHY : Assumptions: main.tf composes the bucket names deterministically from
  #       this prefix, the environment, a role suffix, the AWS account id and
  #       the region -- the same composition infra/modules/s3-datasets uses --
  #       because the S3 bucket namespace is GLOBAL. A bare `carddemo-spa`
  #       belongs to whichever account created it first, and every later
  #       account gets BucketAlreadyExists. Folding in the account id and
  #       region is what makes the name collision-free without a random
  #       suffix, and it is also what consumes most of the 63 characters S3
  #       allows a general-purpose bucket name.
  #       The worst-case budget for
  #       `<prefix>-<environment>-spa-<account-id>-<region>` is 4 for `prod`, 3
  #       for `spa`, 12 for the account id, 15 for the longest region name AWS
  #       publishes in any partition, and 4 for the joining hyphens: 38
  #       characters. A 20-character cap composes at most 58 and deliberately
  #       leaves five characters of safety for a future region name without
  #       weakening the globally-unique deterministic convention.
  #       Trade-offs: checking the derived budget on the input here, rather than
  #       checking the composed name in main.tf, reports the problem against
  #       the value an operator actually typed and does it during plan. The
  #       alternative surfaces the same mistake as an S3 InvalidBucketName
  #       partway through an apply, after neighbouring resources exist.
  validation {
    condition     = length(var.name_prefix) >= 1 && length(var.name_prefix) <= 20
    error_message = "name_prefix must be 1 to 20 characters. It is combined with the environment, a role suffix, the account id and the region to form S3 bucket names, which AWS limits to 63 characters."
  }

  # WHY : Assumptions: S3 rejects an upper-case character outright in a
  #       general-purpose bucket name and requires the name to begin and end
  #       with a letter or a digit. The anchors are what enforce that second
  #       rule through a prefix rather than on the finished name: `carddemo-`
  #       would compose `carddemo--dev-...`, and an empty prefix would compose
  #       a name beginning with a hyphen.
  #       Alternatives Considered: normalising the value in main.tf with
  #       lower(replace(...)) instead of rejecting it. Rejected -- silently
  #       renaming an operator's input leaves the bucket in the console not
  #       matching what the tfvars file says, and a later corrected prefix that
  #       normalises to the same string produces no diff at all, which is a
  #       worse outcome than an error.
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix))
    error_message = "name_prefix must contain only lower-case letters, digits and interior hyphens, and must begin and end with a letter or digit, because an S3 bucket name admits nothing else."
  }
}

variable "environment" {
  description = "Environment discriminator embedded in every resource name, so the dev and prod stacks can coexist without colliding on a globally unique bucket name."
  type        = string

  # WHY : Alternatives Considered: defaulting to "dev", which is the shorter
  #       call site and the obvious convenience. Rejected for one specific
  #       failure: infra/envs/prod/main.tf omitting the argument would then
  #       plan cleanly and build a complete, correctly-formed set of dev-named
  #       resources in the prod account. Because those names collide with
  #       nothing -- the real dev stack lives in a different account -- neither
  #       Terraform nor AWS would object, and the mistake would surface only
  #       when someone read the console. A missing required argument is caught
  #       instead by Terraform at the call site, naming this variable.

  # WHY : Assumptions: this package defines exactly two environment roots,
  #       infra/envs/dev and infra/envs/prod, and nothing consumes a third
  #       value. A typo such as "prd" is a perfectly good string, so without
  #       this block it provisions a parallel, correctly-formed set of
  #       resources that no root owns, that no later plan reconciles because no
  #       root passes that value again, and that has to be found and destroyed
  #       by hand.
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be either dev or prod, matching the two roots under infra/envs. No other value is provisioned or torn down by this package."
  }
}

# -----------------------------------------------------------------------------
# Cross-module input.
#
# The one value this module cannot derive for itself. It is produced elsewhere
# in the package and handed in by the environment root; this module calls no
# sibling module and looks nothing up.
# -----------------------------------------------------------------------------

variable "s3_kms_key_arn" {
  description = "ARN of the customer-managed KMS key that encrypts the private SPA origin bucket at rest."
  type        = string

  # WHY : Assumptions: the value is one of the four customer-managed keys
  #       infra/modules/kms creates, and the environment root wires that
  #       module's output into this argument. Resolving the key here with a
  #       data source instead would make this module depend on a key already
  #       existing under a name it guessed, which turns a wiring mistake into a
  #       plan-time lookup failure that names no module as responsible.

  # WHY : Assumptions: the ARN on its own is not sufficient for this to work.
  #       The key POLICY on that key must also grant the CloudFront service
  #       principal `kms:Decrypt`, conditioned on this module's distribution,
  #       or the origin access control cannot read an SSE-KMS object and
  #       CloudFront answers 403 to every asset while the bucket, the
  #       distribution and the key each look correct in isolation. That grant
  #       belongs to infra/modules/kms, not here. It is recorded at this
  #       argument because this is the first place a reader debugging that 403
  #       will look, and the module that must change is not this one.

  # WHY : Alternatives Considered: making this optional and falling back to
  #       S3-managed encryption when it is null. Rejected -- every file
  #       resource in the baseline was defined RECOVERY(NONE) and JOURNAL(NO)
  #       (app/csd/CARDDEMO.CSD:L7,L9), and encrypting the replacement storage
  #       under a key this package owns and rotates is one of the deliberate
  #       improvements over that baseline. A fallback would let a root that
  #       merely forgot the argument apply successfully and produce a bucket
  #       encrypted well enough to pass a scan while quietly giving up the
  #       customer-managed key.
  #       Alternatives Considered: a `validation` block asserting the string
  #       looks like a KMS ARN. Declined -- a syntactically perfect ARN says
  #       nothing about the policy grant described above, which is the failure
  #       that actually occurs, so the check would buy confidence it cannot
  #       justify while rejecting nothing the provider does not already reject.
}

variable "s3_kms_key_policy_id" {
  description = "Identifier of the fully applied S3 key policy. CloudFront logging v2 reads it only as an ordering token so delivery cannot start before the exact distribution and delivery-source KMS grants exist."
  type        = string

  validation {
    condition     = length(trimspace(var.s3_kms_key_policy_id)) > 0
    error_message = "s3_kms_key_policy_id must be the non-empty aws_kms_key_policy.s3 identifier returned by the kms module."
  }
}

# -----------------------------------------------------------------------------
# The two environment knobs.
#
# These are the only two inputs the dev and prod roots are expected to disagree
# on for this module. Both are sizing or retention values; neither changes what
# the module builds.
# -----------------------------------------------------------------------------

variable "price_class" {
  description = "CloudFront edge-location tier that serves the SPA; one of the two values the dev and prod roots deliberately differ on for this module."
  type        = string
  default     = "PriceClass_100"

  # WHY : Trade-offs: PriceClass_100 restricts the distribution to the cheapest
  #       edge locations, which raises latency for viewers far from them and
  #       lowers the per-request and data-transfer cost everywhere. That is the
  #       right side of the trade for this package, whose deployment is
  #       single-region by design -- multi-region and disaster-recovery
  #       topology are out of scope -- so a viewer population spread across
  #       every continent is not a case this default has to serve. The package
  #       resolves a close call toward the lower-cost option and records it,
  #       which is what this comment is. A root that needs broader coverage
  #       raises the value rather than editing the module.
  #
  # WHY : Assumptions: CloudFront accepts exactly these three, and they are a
  #       closed set rather than a growing one -- unlike the TLS security
  #       policies, which is why minimum_protocol_version below is deliberately
  #       left unvalidated and this is not. A value such as PriceClass_50 is a
  #       well-formed string that would otherwise reach the CloudFront API and
  #       fail during apply, after the origin bucket and its policy already
  #       exist, leaving the stack half-built for a typo.
  validation {
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.price_class)
    error_message = "price_class must be PriceClass_100, PriceClass_200 or PriceClass_All. CloudFront accepts no other tier, and an unrecognised value fails during apply rather than during plan."
  }
}

variable "log_retention_days" {
  description = "Days a CloudFront access-log object is kept before the log bucket's lifecycle rule expires it; the second of the two values the dev and prod roots differ on."
  type        = number
  default     = 30

  # WHY : Trade-offs: access logs are charged for as long as they are stored,
  #       and their investigative value decays -- an incident is nearly always
  #       reconstructed from recent log objects and hardly ever from old ones.
  #       Storing every object indefinitely bills forever for the rare case;
  #       expiring them aggressively loses the common one. A bounded default
  #       with a per-root override is the compromise, and it is one of the two
  #       axes this module's dev and prod configurations may differ on.
  #
  # WHY : Assumptions: the value is written straight into an S3 lifecycle
  #       expiration rule, which counts whole days and has no meaning at zero
  #       or below. Terraform's `number` type is the reason this block is
  #       needed at all: it accepts 0, -1 and 30.5 just as readily as 30, so
  #       `floor(...) == ...` is what rejects a fraction. Without the check, a
  #       fractional value reaches the S3 API and fails during apply; a zero or
  #       negative one is worse, because a rule that expires nothing looks
  #       exactly like a rule that is working.
  validation {
    condition     = var.log_retention_days > 0 && floor(var.log_retention_days) == var.log_retention_days
    error_message = "log_retention_days must be a whole number greater than zero, because it becomes the day count of an S3 lifecycle expiration rule."
  }
}

# -----------------------------------------------------------------------------
# Retention and protection flags.
#
# Both belong to the same family as the deletion-protection and final-snapshot
# flags elsewhere in this package: they govern what survives a teardown, not
# what gets built. Either root may simply take the default.
# -----------------------------------------------------------------------------

variable "spa_noncurrent_version_retention_days" {
  description = "Days a superseded SPA build is kept as a noncurrent object version before expiry, which is what bounds the storage cost of keeping front-end rollback available."
  type        = number
  default     = 30

  # WHY : Trade-offs: versioning is enabled on the SPA origin bucket so that a
  #       bad front-end deploy can be rolled back to the previous build by
  #       restoring the prior object versions. That is what makes "revert the
  #       SPA" an actual capability and not a claim in a runbook. The cost is
  #       that every build ever deployed would otherwise be stored forever, and
  #       a SPA bundle is a large number of small objects, so the accumulation
  #       is real. Expiring noncurrent versions bounds it while leaving a
  #       rollback window wide enough to cover the interval between a deploy
  #       and someone noticing it was wrong.
  #       Alternatives Considered: retaining a fixed COUNT of noncurrent
  #       versions instead of a duration, which is how the dataset bucket
  #       expresses the mainframe generation limit it has to reproduce.
  #       Rejected here because this module reproduces no such limit: a SPA has
  #       no generation-dataset contract, and what a front-end rollback window
  #       is measured in is elapsed time, not builds -- ten deploys in one day
  #       would otherwise consume an entire count-based window.
  validation {
    condition     = var.spa_noncurrent_version_retention_days > 0 && floor(var.spa_noncurrent_version_retention_days) == var.spa_noncurrent_version_retention_days
    error_message = "spa_noncurrent_version_retention_days must be a whole number greater than zero, because it becomes the day count of an S3 noncurrent-version expiration rule."
  }
}

variable "force_destroy" {
  description = "Whether the SPA origin bucket may be deleted while it still holds objects; a teardown protection flag, not an environment-shape switch."
  type        = bool
  default     = false

  # WHY : Trade-offs: with false, `terraform destroy` stops rather than deleting
  #       a bucket that still holds the deployed SPA and its access logs, and
  #       an operator has to purge the objects first -- the manual step
  #       docs/runbooks/teardown.md documents. What that costs is a teardown
  #       that is not a single command. What it buys is that a mistyped or
  #       mistargeted destroy cannot silently take the deployed front end with it,
  #       and because versioning is on, "purge" here
  #       means every version, which is precisely the irreversible act worth
  #       making deliberate.
  #       This deliberately matches the choice infra/bootstrap makes for the
  #       remote-state bucket, so the whole package behaves the same way under
  #       destroy instead of one module being the exception nobody remembers.
  #       Alternatives Considered: defaulting to true, which would satisfy the
  #       acceptance criterion that destroy tears the stack down cleanly with
  #       no manual step at all. Rejected -- it makes every accidental destroy
  #       irreversible in exchange for convenience during an intentional one,
  #       and the runbook already covers the intentional case. A root that
  #       genuinely wants unattended teardown, an ephemeral test environment
  #       for instance, sets this to true explicitly and thereby records that
  #       it accepted the consequence.
  #
  # WHY : Assumptions: worth stating because a bool input in a module is often
  #       exactly that. The dev and prod roots are required to differ only in
  #       sizing and retention, so a flag that decided whether a bucket exists
  #       at all would breach that constraint. This one only decides whether a
  #       delete is permitted to proceed; the bucket exists either way.
}


# -----------------------------------------------------------------------------
# Delivery and SPA-routing inputs.
#
# The certificate, aliases and minimum protocol form one required set in every
# environment. The API origin is separate and feeds only the CSP connect-src.
# -----------------------------------------------------------------------------

variable "default_root_object" {
  description = "Document CloudFront returns for / and, deliberately, the same document the viewer-request routing function rewrites client-side routes to, so a deep link resolves instead of 404ing."
  type        = string
  default     = "index.html"

  # WHY : Assumptions: ui/src/router.tsx owns all twenty-one screen routes on
  #       the CLIENT. The origin bucket holds the built bundle and nothing
  #       resembling a server, so a request for a deep route such as
  #       /account/update would arrive at S3 as a key that simply does not exist:
  #       S3 answers 404, or 403 when the origin access control forbids listing
  #       rather than disclosing which keys are absent. Either way the viewer
  #       would get an error for a route the application handles perfectly well
  #       once the bundle is loaded. main.tf therefore rewrites those paths to
  #       this document in a viewer-request function, before the origin is
  #       consulted, which delivers the bundle and lets the router resolve the
  #       path from the address bar.
  #       Refactoring Rationale: that rewriting was previously done by mapping 403
  #       and 404 to this document with a 200 status. It worked for routes and was
  #       wrong for files: every missing asset also became a 200 carrying HTML, so
  #       an undeployed bundle looked healthy to anything watching status codes.
  #       Because `custom_error_response` is distribution-wide it could not tell
  #       the two apart, so the routing decision moved to the function and the
  #       error responses now preserve a 404 status while still returning this
  #       document as the body.
  #       Assumptions: this is load-bearing rather than cosmetic because the
  #       migration made navigation client-side ON PURPOSE. The mainframe
  #       transferred control between programs with EXEC CICS XCTL -- the
  #       sign-on program's branch to the admin or main menu at
  #       app/cbl/COSGN00C.cbl:L245 is the canonical instance -- and the
  #       replacement for that verb is a client-side route change, not a
  #       server round trip. Without the rewrite, every one of those routes
  #       works while navigating and breaks on a bookmark or a hard refresh,
  #       which is the one failure users find immediately and developers never
  #       do.
  #       Refactoring Rationale: the rewrite target is derived from this single
  #       variable instead of being written twice, once as the root object and
  #       once in each error-response block. Two literals drift: someone
  #       renaming the entry document updates the one they were looking at, the
  #       root still serves and only deep links break, and the resulting bug
  #       looks like a routing problem rather than a typo.
  #
  # WHY : Alternatives Considered: asserting the value ends in .html, or is
  #       non-empty. Declined -- the name has to match whatever ui/vite.config
  #       emits as the entry document, this module cannot see that build, and a
  #       guess encoded here would reject a legitimate rename. An empty or
  #       wrong value is caught immediately and unambiguously by the first
  #       request to the distribution, so a check here adds a constraint
  #       without adding information.
}

variable "api_connect_src_origins" {
  description = "Origins the SPA is permitted to reach with fetch or XHR, added to the content-security policy's connect-src directive alongside 'self'. Scheme and host only, no path and no trailing slash. Empty means same-origin only."
  type        = list(string)

  # WHY : Assumptions: every other directive in that policy is
  #       environment-invariant and is written as a literal in main.tf, matching
  #       ui/nginx.conf byte for byte. `connect-src` cannot be, because the SPA
  #       calls an API Gateway HTTP API on a DIFFERENT origin whose hostname is
  #       created by another module and differs per environment. The container
  #       image cannot know it and so states the tightest invariant form it can,
  #       `'self' https:`; this input is what lets the distribution narrow that to
  #       the exact origins, which is the whole reason the two policies are allowed
  #       to differ in this one directive.
  #       Assumptions: the value is a list because more than one origin is
  #       legitimately needed as the system grows -- the API today, and a Cognito
  #       token endpoint if the SPA is ever pointed at one directly -- and a single
  #       string would have to be split somewhere, which is a parsing step this
  #       avoids.
  #
  # WHY : Trade-offs: no default is supplied, so every caller must state the value
  #       even if only as an empty list. That is deliberate for a security control:
  #       a default would let a root omit it silently, and the omission would not
  #       be visible until a browser blocked an API call in a deployed environment.
  #       Requiring it moves the decision to `terraform plan`, where it is
  #       reviewable. An explicit empty list still fails closed rather than open --
  #       it yields `connect-src 'self'`, which blocks the cross-origin call
  #       visibly instead of permitting any origin.
  #
  # WHY : Assumptions: the validation rejects the three shapes that would silently
  #       weaken or break the directive. A wildcard defeats the point of naming
  #       origins at all. A path is not part of a CSP source expression and is
  #       ignored by the browser, so including one produces a policy that looks
  #       narrower than it is. A trailing slash makes the source fail to match the
  #       origin it was meant to name, which presents as a blocked API call with a
  #       policy that appears correct on inspection.
  validation {
    condition = alltrue([
      for origin in var.api_connect_src_origins :
      can(regex("^https://[a-z0-9][a-z0-9.-]*[a-z0-9](:[0-9]{1,5})?$", origin))
    ])
    error_message = "Each entry in api_connect_src_origins must be an https:// scheme and host, optionally with a port, and must not contain a path, a trailing slash or a wildcard."
  }
}

variable "acm_certificate_arn" {
  description = "ARN of the ACM certificate the distribution serves to viewers, which must be issued in us-east-1 and must cover every name in `aliases`; REQUIRED in every environment, because the default CloudFront certificate pins the viewer security policy to TLSv1."
  type        = string
  nullable    = false

  # WHY : Assumptions: CloudFront requires the certificate to live in us-east-1
  #       NO MATTER WHICH REGION the rest of the stack is deployed to, because
  #       the distribution is a global resource that reads its certificate from
  #       that one region. This is the least obvious constraint in the module
  #       and the likeliest source of a confusing failure: a certificate issued
  #       correctly in the deployment region is a perfectly well-formed ARN,
  #       indistinguishable from a usable one at this boundary, and it is
  #       rejected only when CloudFront is asked to use it partway through an
  #       apply. Supplying the certificate from the root, rather than creating
  #       it here, is what keeps that us-east-1 provider alias out of a module
  #       that otherwise inherits exactly one provider configuration.
  #
  # WHY : Refactoring Rationale: this input previously defaulted to null and was
  #       refused only when `environment` was "prod", with the default-certificate
  #       path documented as a deliberate convenience for dev. Both halves of that
  #       arrangement are withdrawn. On the default certificate CloudFront PINS
  #       the viewer security policy to TLSv1 -- not as a floor this module could
  #       raise, but as the stored value -- so any distribution on that path
  #       accepts TLS 1.0 and 1.1 while the configuration still reads
  #       `minimum_protocol_version = "TLSv1.2_2021"`. A weakness that looks
  #       correct in the source and is inert in the deployment is the worst shape
  #       a weakness can take, and a dev or review environment is not a place it
  #       is acceptable: the same operator console and the same account, card and
  #       transaction views are rendered there, against migrated card data, over
  #       two withdrawn protocols with practical attacks against them.
  #       Assumptions: the environment-conditional rule also made the transport
  #       posture depend on the SPELLING of a string input, so a root whose
  #       environment read "production", "PROD" or "prd" silently received the
  #       weak path while satisfying the rule. A requirement that holds for every
  #       value of every other input cannot be evaded that way.
  #       Alternatives Considered: keeping the prod-only rule and relying on the
  #       infrastructure pipeline's policy scan to report dev. Rejected -- the
  #       scan reports the symptom in a run somebody must read and act on, and a
  #       finding the module deliberately created is not a control. Also
  #       considered: raising `minimum_protocol_version` on the default path.
  #       Rejected because it is not a floor there at all: CloudFront stores
  #       TLSv1 regardless, so the only effect is a plan that never converges.
  #       Trade-offs: the cost is real and is stated rather than minimised. Every
  #       environment, dev included, now needs a validated certificate in
  #       us-east-1 covering the names in `aliases` before this module can be
  #       applied, so a stack is no longer creatable by an operator holding
  #       nothing but credentials. That prerequisite is ONE certificate per
  #       environment, issued once and reused across create and destroy cycles,
  #       and both roots declare the certificate and its domain names as explicit
  #       inputs -- so the requirement is visible where the values are supplied
  #       rather than discovered partway through an apply.
  #
  # WHY : Refactoring Rationale: that constraint was documented in three places
  #       and enforced in none, and it is the module's least obvious requirement:
  #       a certificate issued correctly in the deployment region is a well-formed
  #       ARN, indistinguishable from a usable one at this boundary, and is
  #       rejected only when CloudFront is asked to use it partway through an
  #       apply that has already begun creating a distribution. Asserting the
  #       shape here moves that failure to plan time, where it costs nothing.
  #       Assumptions: the pattern admits any partition, so the check does not
  #       assume the commercial one, and it deliberately asserts nothing beyond
  #       shape -- whether the certificate exists, is validated and covers every
  #       alias is knowable only to the service, and a check pretending otherwise
  #       would give false confidence.
  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:acm:us-east-1:[0-9]{12}:certificate/[0-9a-f-]+$", var.acm_certificate_arn))
    error_message = "acm_certificate_arn must be an ACM certificate ARN issued in us-east-1 -- arn:<partition>:acm:us-east-1:<account-id>:certificate/<id>. CloudFront is a global resource and reads its viewer certificate only from us-east-1, so a certificate issued in the deployment region is a well-formed ARN that CloudFront rejects partway through an apply."
  }
}

variable "aliases" {
  description = "Domain names the distribution answers on; REQUIRED and non-empty, and every name must be covered by the certificate in acm_certificate_arn."
  type        = list(string)
  nullable    = false

  # WHY : Assumptions: CloudFront refuses an alternate domain name that the
  #       supplied certificate does not cover, and refuses any alias at all
  #       while the default certificate is in use. The two inputs are therefore
  #       one decision expressed in two variables, and main.tf treats them that
  #       way.
  #       Refactoring Rationale: this list previously defaulted to empty and was
  #       required only in prod, so that dev could stand up with no DNS
  #       prerequisite. That is withdrawn for the reason recorded on
  #       acm_certificate_arn, and specifically because a certificate supplied
  #       with no alias leaves the distribution answering only on its generated
  #       cloudfront.net name -- reaching the same TLSv1-pinned outcome the
  #       certificate was supplied to avoid, by configuring half of the pair.
  #       Requiring both halves everywhere is what makes the certificate
  #       requirement mean what it says.
  #       Trade-offs: the accepted cost is that no environment stands up without
  #       a domain name it controls. That cost is the same certificate
  #       prerequisite already accepted above rather than a second one, since a
  #       certificate covering no name would be useless.
  #       Alternatives Considered: a single required `domain_name` string, since
  #       one alias is the common case. Rejected -- a certificate covering both
  #       apex and www is equally common, and a string would have to be widened
  #       to a list later, which is a breaking change to both roots for no gain
  #       over starting as a list.

  # WHY : Refactoring Rationale: this replaces two earlier checks that are now
  #       vacuous or superseded -- one refused aliases without a certificate,
  #       which cannot occur while the certificate is required, and one required
  #       a non-empty list in prod only, which is the environment-spelling
  #       weakness described on acm_certificate_arn. One unconditional rule
  #       expresses the requirement with nothing left to interpret, and it is
  #       evaluated during `terraform validate` rather than deferred to plan,
  #       because it reads no other variable.
  #       Assumptions: each entry is also checked for the SHAPE of a domain name.
  #       An alias is a bare host -- no scheme, no port, no path -- and CloudFront
  #       rejects anything else at apply; a wildcard label is admitted because a
  #       certificate covering *.example.com is the usual way to serve several
  #       subdomains from one distribution.
  validation {
    condition     = length(var.aliases) > 0
    error_message = "aliases must list at least one domain name. A certificate with no alias leaves the distribution answering only on its generated cloudfront.net name, where CloudFront pins the viewer security policy to TLSv1 -- the same outcome acm_certificate_arn is required to avoid. Every name listed must be covered by that certificate."
  }

  validation {
    condition = alltrue([
      for alias in var.aliases :
      can(regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", lower(alias)))
    ])
    error_message = "Each aliases entry must be a bare domain name, optionally with a leading wildcard label -- app.example.com or *.example.com. A scheme, a port, a path or a trailing dot is refused by CloudFront at apply."
  }

}

variable "minimum_protocol_version" {
  description = "Minimum TLS version the distribution accepts from viewers; always applied, because the distribution always serves a supplied ACM certificate and never the default one."
  type        = string
  default     = "TLSv1.2_2021"

  # WHY : Assumptions: the infrastructure pipeline includes a policy scan that
  #       fails on HIGH and CRITICAL findings, and an outdated viewer TLS
  #       policy is a standard finding in every scanner's CloudFront ruleset.
  #       Defaulting to the current recommended policy means a distribution
  #       with a certificate passes that scan without a per-root override,
  #       which is the only way a default here is worth having.
  #       Assumptions: this setting is REQUIRED by the service alongside a
  #       certificate -- CloudFront will not accept an ACM certificate ARN
  #       without both a minimum protocol version and an SNI support method --
  #       so it is not an optional refinement of acm_certificate_arn but the
  #       other half of the same argument.
  #       Refactoring Rationale: this value used to be applied by main.tf only on
  #       the certificate path, because on the default-certificate path
  #       CloudFront stores TLSv1 regardless and any other value produced a
  #       perpetual plan diff. That conditional is gone: the certificate is now
  #       required in every environment, so there is no path on which this input
  #       is inert, and the floor it states is the floor the deployment enforces.
  #
  # WHY : Alternatives Considered: a `contains(...)` allow-list like the one on
  #       price_class. Rejected on evidence, and this rejection still stands:
  #       the AWS provider already carries its own enumeration of these policy
  #       names and has demonstrably lagged the service, rejecting a newer TLS
  #       1.3 policy that CloudFront itself accepted. Copying that list into
  #       this module would reproduce exactly that failure one layer further out
  #       -- an operator wanting the newer policy would be blocked by this file
  #       even after upgrading the provider that had caught up. The set is open
  #       and growing, unlike price_class, so the provider stays the single
  #       place it is enumerated.
  # WHY : Refactoring Rationale: what the paragraph above got wrong was
  #       concluding that because the ACCEPTED set cannot be closed, nothing can
  #       be checked. The set of policies that must be REFUSED is closed and
  #       does not grow: AWS publishes exactly four legacy viewer policies below
  #       the TLS 1.2 floor -- SSLv3, TLSv1, TLSv1_2016 and TLSv1.1_2016 -- and
  #       will publish no more, because new policies floor at 1.2 or higher.
  #       Refusing a fixed deny-list therefore holds the floor without closing
  #       the ceiling: a policy family released tomorrow passes untouched, and
  #       the four that accept withdrawn protocols cannot be selected. That
  #       asymmetry is the whole point -- an allow-list of a growing set ages
  #       badly, a deny-list of a closed set does not.
  #       Assumptions: the floor is TLS 1.2 because TLS 1.0 and 1.1 are withdrawn
  #       and have practical attacks against them, and because the sibling `alb`
  #       module already pins the same floor on its listener. One floor across
  #       the two internet-facing edges is what makes the transport posture a
  #       property of the package rather than of whichever module a reader
  #       happens to open.
  #       Trade-offs: "TLSv1" is refused here even though it is the value
  #       CloudFront stores on its own default certificate. There is no longer
  #       any tension in that: the default certificate is unreachable in this
  #       module, so TLSv1 is never an outcome, and refusing the string prevents
  #       the one remaining configuration that could state it deliberately.
  validation {
    condition = !contains(
      ["SSLv3", "TLSv1", "TLSv1_2016", "TLSv1.1_2016"],
      var.minimum_protocol_version
    )
    error_message = "minimum_protocol_version must not be SSLv3, TLSv1, TLSv1_2016 or TLSv1.1_2016. Those four are the only CloudFront viewer policies that accept TLS 1.0 or 1.1, both withdrawn protocols, and no future policy will join them. Use TLSv1.2_2021 or a newer family; the accepted set is deliberately left open so a policy released after this module was written needs no edit here."
  }
}


# -----------------------------------------------------------------------------
# The optional web-ACL association.
# -----------------------------------------------------------------------------

variable "web_acl_arn" {
  description = "ARN of a WAFv2 web ACL to associate with the distribution; null associates none, which is this package's default and documented posture."
  type        = string
  default     = null
  nullable    = true

  # WHY : Alternatives Considered: provisioning a web ACL inside this module so
  #       that every deployment gets one. Rejected on two independent grounds,
  #       and this comment is the documented reason for the absence rather than
  #       an admission of a gap -- a policy scan that flags a distribution with
  #       no web ACL is answered here.
  #       First, scope: this package's module catalog is a fixed set and
  #       contains no WAF module. Adding a seventeenth, with its own rule
  #       groups, rate limits, logging destination and per-environment tuning,
  #       is a body of work that belongs to a deliberate decision rather than
  #       to a side effect of authoring a CloudFront module.
  #       Second, and the substantive reason: this distribution serves nothing
  #       worth protecting with a web ACL. Its entire payload is the public,
  #       static, read-only SPA bundle -- there is no form handler, no query
  #       interpretation and no credential at the origin, and the origin bucket
  #       is private with the distribution's origin access control as its only
  #       reader. Every authenticated or state-changing operation goes to the
  #       API Gateway HTTP API instead, which is where a request is
  #       authenticated by the Cognito JWT authorizer and where request-level
  #       filtering would actually be doing something. Attaching a web ACL here
  #       would bill per request for inspecting immutable asset fetches while
  #       leaving the surface that matters exactly as protected as it already
  #       is.
  #       Trade-offs: keeping the association as an INPUT rather than omitting
  #       the capability is what makes this reversible. An environment that has
  #       a web ACL -- because an organisation-wide policy supplies one, or
  #       because a rate limit becomes wanted in front of the bundle -- attaches
  #       it by passing one argument, with no change to this module and no
  #       change to the other environment. The absence in a default deployment
  #       is a recorded decision; the capability is not missing.
  #
  # WHY : Assumptions: same mechanism as there -- Terraform rejects a null
  #       default alongside `nullable = false`, so this pairing is the only one
  #       that loads, and stating it records that an explicit null from a root
  #       means "associate nothing" rather than "argument forgotten". main.tf
  #       reads the null as the signal to emit no association at all, which is
  #       different from associating an empty value.
}

# =============================================================================
# Deliberate omissions
# -----------------------------------------------------------------------------
# Every input named below is absent on purpose. Each one is a reasonable thing
# to look for in a module of this kind, so each is recorded as a decision rather
# than left to read as an oversight. tflint's terraform_unused_declarations rule
# is the mechanical half of this: an input declared here and wired to nothing in
# main.tf fails the gating lint step, so "declare it now, use it later" is not
# available and every absence below has to be either justified or fixed.
#
# No `tags` input:
#   Alternatives Considered: a per-module tag map, which is the common module
#   convention. Rejected because the calling root's provider `default_tags`
#   block is the single tagging mechanism for this package, and a second source
#   of tags reaching the same resources lets the two disagree -- one of them
#   silently winning per attribute, which is far harder to diagnose than having
#   one source. infra/modules/cloudfront-spa/versions.tf records the same
#   rejection for the same reason, and this file does not reintroduce what that
#   file rules out.
#
# No `region` or `aws_region` input:
#   Assumptions: the region comes from the provider configuration this module
#   inherits from its calling root, and main.tf reads it from the aws_region
#   data source where it needs the value for name composition. A region
#   variable could disagree with the inherited provider, and the provider wins:
#   the resources would be created in one region while carrying the name of
#   another, which is the kind of defect that survives a plan review because
#   the plan looks internally consistent.
#
# No topology switch -- no `enable_logging`, `create_bucket`, `enable_waf`,
# `origin_failover` or `enable_ipv6`:
#   Assumptions: the dev and prod roots are required to differ only in sizing
#   and retention, never in topology, and each of those flags is a lever that
#   would let one root build a materially different stack from the other. The
#   value of the constraint is that a defect found in dev is reachable in prod;
#   a flag that skipped the distribution, the origin bucket or the response
#   header policy in dev would remove exactly the surface the prod incident it
#   was supposed to be rehearsing for happens on. Where a capability genuinely
#   is optional -- the certificate, the
#   aliases, the web ACL -- it is expressed as a VALUE that may be null or
#   empty rather than as a boolean that changes the resource graph.
#
# No input accepting a pre-existing bucket name:
#   Assumptions: this module creates its own origin bucket and names it itself.
#   Accepting a name would make it possible to aim this module at the dataset
#   bucket owned by infra/modules/s3-datasets or the remote-state bucket owned
#   by infra/bootstrap. All three are versioned, encrypted S3 buckets, so
#   nothing about such a mistake would look wrong -- and the
#   outcome of the second case is a CloudFront distribution serving Terraform
#   state, which contains generated credentials in plaintext, to the public
#   internet. The omission is what makes that unreachable rather than merely
#   discouraged.
#
# No `sensitive = true` on any input above:
#   Assumptions: none of the twelve carries a secret. They are names, an
#   environment discriminator, resource ARNs, an edge tier, day counts, a
#   boolean and a document name; ARNs are identifiers, not credentials, and
#   they appear in state and in the console regardless. Marking one sensitive
#   would redact it from plan output -- so a reviewer could no longer see which
#   key or which web ACL a change points at -- while protecting nothing.
#
# No credential, password or other secret input of any kind:
#   Assumptions: no secret is committed to this repository, and this module
#   needs none: a CloudFront distribution reaches its origin through an origin
#   access control, which is an identity-based grant in the bucket policy
#   rather than a shared secret. Where this package genuinely needs a
#   credential, the value is generated at apply time into Secrets Manager by
#   the module that owns it, and never travels through a variable declared in
#   source.
# =============================================================================
