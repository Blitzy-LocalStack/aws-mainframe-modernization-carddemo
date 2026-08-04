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
#     Environment knobs ... price_class, log_retention_days
#     Retention and
#       protection ........ spa_noncurrent_version_retention_days,
#                           force_destroy
#     Delivery and
#       SPA routing ....... default_root_object, acm_certificate_arn, aliases,
#                           minimum_protocol_version, web_acl_arn
#   Each carries an explicit `type` and a `description`. Two are required and
#   have no default; the other ten default to values that stand up a working
#   single-region distribution with no DNS, certificate or web-ACL prerequisite.
#
# Return values:
#   None. A variables.tf returns nothing to a caller -- it declares what the
#   module accepts. Every value this module hands back is declared in
#   infra/modules/cloudfront-spa/outputs.tf.
#
# Errors / failure modes:
#   Nine `validation` blocks reject a bad value while Terraform is evaluating
#   variables, which happens at the START of a plan and before the AWS provider
#   is asked to create anything. Each surfaces as `Error: Invalid value for
#   variable`, naming the variable and carrying the message written beside it:
#     - name_prefix ....... empty, longer than 20 characters, or containing
#                           anything other than lower-case letters, digits and
#                           interior hyphens. Two separate blocks, so the
#                           message names which of the two rules was broken.
#     - environment ....... any value other than dev or prod.
#     - price_class ....... any value CloudFront does not accept.
#     - log_retention_days and spa_noncurrent_version_retention_days ...
#                           zero, negative, or fractional.
#     - acm_certificate_arn and aliases ...
#                           null, or an empty list, when environment is prod.
#                           The pair is one decision; see the Refactoring
#                           Rationale on acm_certificate_arn for why the
#                           default-certificate path cannot be used there.
#     - minimum_protocol_version ...
#                           any of the four legacy viewer policies that accept
#                           TLS 1.0 or 1.1. A deny-list rather than an
#                           allow-list, for the reason recorded on the variable.
#
#   Two of the nine read ANOTHER variable -- both prod rules read
#   var.environment -- so Terraform defers them from `terraform validate` to
#   `terraform plan`. The other seven are evaluated by `validate`. Both stages
#   precede every resource, so a prod distribution cannot be created without a
#   certificate and an alias either way; a pipeline step that validates without
#   planning simply does not exercise those two.
#
#   Three failures are NOT caught here, and are named so nobody looks for them
#   in this file:
#     - `environment` and `s3_kms_key_arn` have no default, so omitting either
#       is rejected by Terraform itself at the call site before any validation
#       in this file runs.
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
#     differ only in sizing and retention and never in topology, so the only
#     two knobs they actually disagree on are price_class and
#     log_retention_days. Nothing below can change the module's shape -- there
#     is no toggle to skip the bucket, skip access logging, or add a second
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
  description = "Name prefix shared by every resource this module creates, so the SPA bucket, log bucket, origin access control and distribution group together in the console and in cost reports."
  type        = string
  default     = "carddemo"

  # WHAT: an upper bound of 20 characters, derived rather than picked.
  # WHY : Assumptions: main.tf composes the bucket names deterministically from
  #       this prefix, the environment, a role suffix, the AWS account id and
  #       the region -- the same composition infra/modules/s3-datasets uses --
  #       because the S3 bucket namespace is GLOBAL. A bare `carddemo-spa`
  #       belongs to whichever account created it first, and every later
  #       account gets BucketAlreadyExists. Folding in the account id and
  #       region is what makes the name collision-free without a random
  #       suffix, and it is also what consumes most of the 63 characters S3
  #       allows a general-purpose bucket name.
  #       The worst-case budget for `<prefix>-<environment>-spa-logs-<account
  #       id>-<region>` is 4 for `prod`, 8 for `spa-logs`, 12 for the account
  #       id, 15 for the longest region name AWS publishes in any partition,
  #       and 4 for the hyphens joining five segments: 43 characters. 63 minus
  #       43 is the 20 below, so 20 is the exact largest prefix that cannot
  #       overflow rather than a cautious round number -- at 20 the worst case
  #       composes a name of exactly 63 characters, and a 21-character prefix
  #       composes 64.
  #       Trade-offs: checking the derived budget on the input here, rather than
  #       checking the composed name in main.tf, reports the problem against
  #       the value an operator actually typed and does it during plan. The
  #       alternative surfaces the same mistake as an S3 InvalidBucketName
  #       partway through an apply, after neighbouring resources exist.
  validation {
    condition     = length(var.name_prefix) >= 1 && length(var.name_prefix) <= 20
    error_message = "name_prefix must be 1 to 20 characters. It is combined with the environment, a role suffix, the account id and the region to form S3 bucket names, which AWS limits to 63 characters."
  }

  # WHAT: lower-case letters and digits, with hyphens allowed only between
  #       them.
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

  # WHAT: the value is restricted to the two environments that exist.
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
  description = "ARN of the customer-managed KMS key that encrypts the SPA origin bucket and its access-log bucket at rest."
  type        = string

  # WHAT: supplied by the caller, with no default and no data-source lookup.
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

  # WHAT: the narrowest tier as the default.
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
  # WHAT: the three values CloudFront accepts, enumerated.
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

  # WHAT: a bounded default rather than indefinite retention.
  # WHY : Trade-offs: access logs are charged for as long as they are stored,
  #       and their investigative value decays -- an incident is nearly always
  #       reconstructed from recent log objects and hardly ever from old ones.
  #       Storing every object indefinitely bills forever for the rare case;
  #       expiring them aggressively loses the common one. A bounded default
  #       with a per-root override is the compromise, and it is one of the two
  #       axes this module's dev and prod configurations may differ on.
  #
  # WHAT: a whole number greater than zero.
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

  # WHAT: noncurrent versions expire on a schedule rather than never.
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
  description = "Whether this module's buckets may be deleted while they still hold objects; a teardown protection flag, not an environment-shape switch."
  type        = bool
  default     = false

  # WHAT: false, so a populated bucket refuses to be deleted.
  # WHY : Trade-offs: with false, `terraform destroy` stops rather than deleting
  #       a bucket that still holds the deployed SPA and its access logs, and
  #       an operator has to purge the objects first -- the manual step
  #       docs/runbooks/teardown.md documents. What that costs is a teardown
  #       that is not a single command. What it buys is that a mistyped or
  #       mistargeted destroy cannot silently take the deployed front end and
  #       its audit trail with it, and because versioning is on, "purge" here
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
  # WHAT: this is not a topology toggle, and nothing about the module's shape
  #       changes with it.
  # WHY : Assumptions: worth stating because a bool input in a module is often
  #       exactly that. The dev and prod roots are required to differ only in
  #       sizing and retention, so a flag that decided whether a bucket exists
  #       at all would breach that constraint. This one only decides whether a
  #       delete is permitted to proceed; both buckets exist either way.
}


# -----------------------------------------------------------------------------
# Delivery and SPA-routing inputs.
#
# The first of these carries the single most consequential behaviour in the
# module. The remaining three are a set: aliases and minimum_protocol_version
# are meaningful only when acm_certificate_arn is supplied, and main.tf treats
# all three together.
# -----------------------------------------------------------------------------

variable "default_root_object" {
  description = "Document CloudFront returns for / and, deliberately, the same document its 403 and 404 responses rewrite to, so a client-side deep link resolves instead of 404ing."
  type        = string
  default     = "index.html"

  # WHAT: this one value serves two purposes -- the root document, and the
  #       target of the error-response rewrite that makes deep links work.
  # WHY : Assumptions: ui/src/router.tsx owns all twenty-one screen routes on
  #       the CLIENT. The origin bucket holds the built bundle and nothing
  #       resembling a server, so a request for a deep route such as
  #       /account/update arrives at S3 as a key that simply does not exist:
  #       S3 answers 404, or 403 when the origin access control forbids listing
  #       rather than disclosing which keys are absent. Either way the viewer
  #       gets an error for a route the application handles perfectly well once
  #       the bundle is loaded. main.tf therefore maps both 403 and 404 back to
  #       this document with a 200 status, which delivers the bundle and lets
  #       the router resolve the path from the address bar.
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

variable "acm_certificate_arn" {
  description = "ARN of an ACM certificate for a custom domain, which must be issued in us-east-1; null leaves the distribution on the default CloudFront certificate and domain name."
  type        = string
  default     = null
  nullable    = true

  # WHAT: the certificate is supplied, never created here.
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
  # WHAT: null by default, with `nullable` stated explicitly.
  # WHY : Assumptions: `nullable = true` is not decoration next to a null
  #       default -- it is the only coherent pairing. Terraform rejects the
  #       configuration outright with "A null default value is not valid when
  #       nullable=false", so the alternative is not merely undesirable, it
  #       does not load. Stating it explicitly documents that an environment
  #       root passing null through a conditional expression is expressing "no
  #       custom domain" and is not making a mistake.
  #       Trade-offs: with no certificate the distribution answers on its
  #       generated cloudfront.net domain name, and CloudFront then PINS the
  #       viewer security policy to TLSv1 regardless of what
  #       minimum_protocol_version below asks for. Two consequences follow, and
  #       both are worth knowing before choosing this default. A policy scan
  #       looking for a modern minimum TLS version will report the distribution
  #       even though the module asked for one, and passing any value other
  #       than TLSv1 alongside the default certificate produces a PERPETUAL
  #       plan diff, because the API stores TLSv1 and the configuration keeps
  #       asking for something else. An environment that has to satisfy that
  #       scan therefore supplies a certificate here; the default keeps dev
  #       deployable without owning a domain, which is the compromise being
  #       accepted rather than an oversight.
  #
  # WHAT: the default is now available to dev ONLY, enforced rather than advised.
  # WHY : Refactoring Rationale: the Trade-off above described the consequence
  #       accurately and then left the choice open in every environment, which
  #       made the weaker option reachable exactly where it must not be. On the
  #       default certificate CloudFront PINS the viewer security policy to
  #       TLSv1 -- not as a floor this module could raise, but as the stored
  #       value -- so a production distribution left on that path serves the
  #       operator console and every account, card and transaction view it
  #       renders to browsers negotiating TLS 1.0 and 1.1. Both are withdrawn
  #       protocols with practical attacks against them, and nothing in the plan
  #       output would have said so, because the configuration would still read
  #       minimum_protocol_version = "TLSv1.2_2021" while the API stored TLSv1.
  #       That is the worst shape a weakness can take: a setting that looks
  #       correct in the source and is inert in the deployment.
  #       Assumptions: an environment either owns a domain or it does not, and
  #       "prod" is the one that does -- the alternative is that production
  #       serves the application from a generated cloudfront.net name, which no
  #       operator would accept for a system carrying card data. So requiring
  #       the certificate in prod costs a real deployment nothing it was not
  #       already going to configure, while dev keeps the property that made the
  #       default worth having: creatable and destroyable repeatedly by anyone,
  #       with no hosted zone, no certificate and no validation records.
  #       Alternatives Considered: making the certificate required
  #       unconditionally, which would make the rule uniform and needs no
  #       cross-variable reference. Rejected because it would force every dev
  #       and review deployment to own a domain in us-east-1, and the reliable
  #       response to that is a shared long-lived certificate ARN pasted into a
  #       tfvars file -- so the rule would be satisfied while the practice got
  #       worse. Also considered: leaving this to the CI policy scan, which
  #       already reports an outdated viewer policy. Rejected because the scan
  #       reports the SYMPTOM (a TLSv1 policy) in a run someone must read and
  #       act on, whereas this rule refuses the CAUSE before a plan completes.
  validation {
    condition     = var.environment != "prod" || var.acm_certificate_arn != null
    error_message = "acm_certificate_arn is required when environment is \"prod\". Without a certificate CloudFront serves the default cloudfront.net domain and PINS the viewer security policy to TLSv1, so the distribution accepts TLS 1.0 and 1.1 from browsers no matter what minimum_protocol_version asks for -- and the configuration still reads as though it asked for TLS 1.2. Supply a certificate issued in us-east-1 together with at least one entry in aliases."
  }
}

variable "aliases" {
  description = "Extra domain names the distribution answers on; meaningful only together with acm_certificate_arn, whose certificate must cover every name listed here."
  type        = list(string)
  default     = []

  # WHAT: an empty list by default, and a pair with acm_certificate_arn rather
  #       than an independent setting.
  # WHY : Assumptions: CloudFront refuses an alternate domain name that the
  #       supplied certificate does not cover, and refuses any alias at all
  #       while the default certificate is in use. The two inputs are therefore
  #       one decision expressed in two variables, and main.tf treats them that
  #       way; setting this alone cannot work.
  #       Trade-offs: an empty default means the module stands up with no DNS
  #       prerequisite whatsoever -- no hosted zone, no certificate, no
  #       validation records -- which is what lets dev be created and destroyed
  #       repeatedly by anyone without owning a domain. The cost is that a
  #       production deployment needs two arguments rather than none, and that
  #       cost falls on the environment that has a domain to configure anyway.
  #       Alternatives Considered: a single optional `domain_name` string,
  #       since one alias is the common case. Rejected -- a certificate
  #       covering both apex and www is equally common, and a string would have
  #       to be widened to a list later, which is a breaking change to both
  #       roots for no gain over starting as a list.

  # WHAT: a cross-variable validation, reading acm_certificate_arn.
  # WHY : Assumptions: "setting this alone cannot work" is stated twice above and
  #       is now ENFORCED, because a documented impossibility that plans cleanly
  #       is still applied. CloudFront refuses an alternate domain name while the
  #       default certificate is in use, so the invalid pair -- aliases without a
  #       certificate -- previously reached apply and failed there, partway
  #       through creating a distribution, with an error naming the API argument
  #       rather than the two inputs that disagree. Both halves of one decision
  #       are visible in one plan, so the check belongs at plan time.
  #       Trade-offs: only this direction is refused. A certificate supplied with
  #       no alias is permitted, because it is a coherent staging step: the
  #       certificate can be issued and validated in one change and the domain
  #       cut over in the next, whereas an alias with no certificate is never a
  #       working state.
  #       Assumptions: each entry is also checked for the SHAPE of a domain name.
  #       An alias is a bare host -- no scheme, no port, no path -- and CloudFront
  #       rejects anything else at apply; a wildcard label is admitted because a
  #       certificate covering *.example.com is the usual way to serve several
  #       subdomains from one distribution.
  validation {
    condition     = length(var.aliases) == 0 || var.acm_certificate_arn != null
    error_message = "aliases requires acm_certificate_arn: CloudFront refuses an alternate domain name while the distribution is using the default certificate, so the two inputs are one decision and setting aliases alone can never work."
  }

  validation {
    condition = alltrue([
      for alias in var.aliases :
      can(regex("^(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$", lower(alias)))
    ])
    error_message = "Each aliases entry must be a bare domain name, optionally with a leading wildcard label -- app.example.com or *.example.com. A scheme, a port, a path or a trailing dot is refused by CloudFront at apply."
  }

  #
  # WHY : Assumptions: in prod this list must be non-empty, for the reason
  #       recorded at length on acm_certificate_arn. The two inputs are one
  #       decision, and a certificate supplied with no alias leaves the
  #       distribution answering only on its generated cloudfront.net name --
  #       which is the same TLSv1-pinned outcome the certificate was supplied to
  #       avoid, reached by configuring half of the pair. Checking both halves
  #       is what makes the certificate requirement mean what it says.
  #       Trade-offs: this rule reads var.environment, so Terraform defers it to
  #       plan time rather than evaluating it during `terraform validate`. A
  #       pipeline step that validates without planning therefore does not
  #       exercise it; `plan` and `apply` both do, and both precede any
  #       resource, so no distribution is created without it either way.
  validation {
    condition     = var.environment != "prod" || length(var.aliases) > 0
    error_message = "aliases must list at least one domain name when environment is \"prod\". A certificate with no alias leaves the distribution answering only on its generated cloudfront.net name, where CloudFront pins the viewer security policy to TLSv1 -- the same outcome the certificate was supplied to avoid. Every name listed must be covered by acm_certificate_arn."
  }
}

variable "minimum_protocol_version" {
  description = "Minimum TLS version the distribution accepts from viewers, applied only when acm_certificate_arn is supplied; CloudFront pins the default certificate to TLSv1 regardless."
  type        = string
  default     = "TLSv1.2_2021"

  # WHAT: a modern security policy as the default, not the oldest accepted one.
  # WHY : Assumptions: the infrastructure pipeline includes a policy scan that
  #       fails on HIGH and CRITICAL findings, and an outdated viewer TLS
  #       policy is a standard finding in every scanner's CloudFront ruleset.
  #       Defaulting to the current recommended policy means a distribution
  #       with a certificate passes that scan without a per-root override,
  #       which is the only way a default here is worth having.
  #       Assumptions: this setting is also REQUIRED once a certificate is
  #       supplied -- CloudFront will not accept an ACM certificate ARN without
  #       both a minimum protocol version and an SNI support method -- so this
  #       is not an optional refinement of acm_certificate_arn but the other
  #       half of the same argument.
  #       Deliberately cross-referenced: read the `Trade-offs:` paragraph on
  #       acm_certificate_arn before changing this. With the default
  #       certificate the value is inert and any setting other than TLSv1
  #       creates a perpetual plan diff, so main.tf applies this only on the
  #       certificate path. It is documented in both places precisely because
  #       the interaction is invisible from either one alone.
  #
  # WHAT: no `validation` ENUMERATING the accepted policies, but one refusing
  #       the outdated ones.
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
  #       CloudFront itself stores on the default-certificate path. That is not
  #       a contradiction: main.tf applies this input only on the certificate
  #       path, so the pinned TLSv1 of a dev distribution is never expressed as
  #       an input, and refusing the string prevents the one configuration that
  #       would state it deliberately.
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
  # WHAT: null by default, with `nullable` stated explicitly, exactly as on
  #       acm_certificate_arn.
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
#   a flag that removes the log bucket in dev removes exactly the evidence
#   needed to investigate the prod incident it was supposed to be rehearsing
#   for. Where a capability genuinely is optional -- the certificate, the
#   aliases, the web ACL -- it is expressed as a VALUE that may be null or
#   empty rather than as a boolean that changes the resource graph.
#
# No input accepting a pre-existing bucket name:
#   Assumptions: this module creates its own origin and log buckets and names
#   them itself. Accepting a name would make it possible to aim this module at
#   the dataset bucket owned by infra/modules/s3-datasets or the remote-state
#   bucket owned by infra/bootstrap. All three are versioned, encrypted S3
#   buckets, so nothing about such a mistake would look wrong -- and the
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
