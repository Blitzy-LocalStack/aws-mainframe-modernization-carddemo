# CloudFront SPA module

This reusable Terraform module implements AAP sections 0.4.1.6, 0.4.1.9, and
0.5.1.12 by placing the CardDemo single-page application in a private S3
origin behind CloudFront, an origin access control, viewer-request routing,
security headers, custom TLS, and standard access logging. It replaces the
3270 terminal's BMS presentation-delivery path, as selected by decision D6 in
[ADR-006](../../../docs/adr/ADR-006-api-and-ui.md); it does not replace CICS
transaction routing or the mainframe load library.

HCL has no docstring construct, so this README carries what a docstring would:
what the module is for, what it assumes, and why each non-obvious choice was
made. The mechanical half of that obligation is enforced by
[TFLint](../../.tflint.hcl) and the
[terraform-docs configuration](../../.terraform-docs.yml), and the required HCL
form is defined by the
[code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md).
The four Terraform files in this directory are the executable source of truth.
Files under `app/**` are read-only lineage used to measure the path being
replaced; this module does not modify them.

## What it provisions

| Resource group | Contract that matters |
|---|---|
| Private SPA origin bucket | All four S3 public-access-block flags are enabled; ACLs are disabled with `BucketOwnerEnforced`; versioning is enabled; objects use the customer-managed KMS key supplied by the environment root; the bucket policy denies non-TLS requests; lifecycle rules expire superseded builds and abort incomplete multipart uploads. |
| Origin access control and origin policy | CloudFront signs every origin request with SigV4. The bucket grants `s3:GetObject` only to the CloudFront service principal and only when `AWS:SourceArn` equals this distribution's ARN, preventing another distribution from reusing the grant. |
| CloudFront distribution | Viewers are redirected to HTTPS; a required custom certificate and aliases make `minimum_protocol_version` effective in every environment; the managed caching-optimised policy, a custom security-headers policy, optional WAF association, and standard access logging are attached. |
| SPA route and error handling | A published viewer-request function rewrites a dotless client route to `default_root_object` before S3 sees it. Origin 403 and 404 responses remain honest 404 responses, use the entry document as the response body, and are not negatively cached. |
| Internal access-log destination | A second private, versioned S3 bucket receives CloudFront standard logging v2 through the CloudWatch Logs delivery service. It uses TLS-only policies, service-specific write conditions, SSE-S3 compatibility encryption, and lifecycle expiry controlled by `log_retention_days`. |

## Why origin access control is required

The origin is reachable only through the distribution. That boundary depends
on origin access control (OAC), not merely on the bucket having an obscure
name.

Alternatives Considered: a public-read origin bucket was rejected because
its S3 endpoint would answer viewers directly. Direct access would make the
distribution's access logging, viewer TLS policy, WAF association point,
security headers, and route handling optional, and it would contradict the
four public-access-block controls asserted by policy scanning.

Alternatives Considered: a legacy origin access identity (OAI) was rejected
on capability, not age. OAI cannot read an object encrypted under a
customer-managed KMS key, so adopting it would force the SPA origin from
SSE-KMS to SSE-S3 and remove the key-policy boundary required by this package.
OAC signs the request with SigV4 and permits the KMS-backed origin.

Assumptions: `signing_behavior = "always"` keeps control of origin
authentication at the distribution. `"never"` would make the private origin
unreachable, while `"no-override"` would let a viewer-supplied `Authorization`
header decide whether CloudFront signs a request, producing intermittent 403
responses whose cause varies with the request. The provider schema makes OAC
and the legacy `s3_origin_config` path mutually exclusive, so the module
declares only OAC.

Both displaced designs are plausible. Recording their concrete failure modes
is necessary because the final OAC block alone cannot explain why neither
alternative is safe here.

## Why SPA route handling exists

A browser route and an S3 object key are different namespaces. AAP section
0.4.1.4 maps the 21 BMS mapsets to a 21-route SPA target, and
[`ui/src/router.tsx`](../../../ui/src/router.tsx) owns client-side route
resolution. The checked-in router mounts **every authored screen** — all 21
directories under [`ui/src/screens`](../../../ui/src/screens), reached through 22
path patterns because the user-update screen is mounted both bare and with a
selected user id — beside a root redirect and a `*` catch-all. The CloudFront
mechanism is deliberately independent of that list, so adding a route changes the
SPA alone and requires no infrastructure allow-list.

Refactoring Rationale: this paragraph said the router "declares four concrete
route patterns and a catch-all", which was a count taken while the SPA was
partially authored and never revisited. It understated the delivered surface by
seventeen screens and, worse, invited the reader to conclude that the other
seventeen mapsets were unmigrated — a conclusion the router refutes. The
replacement names the source of truth rather than a number that only a reader
comparing two trees could falsify: "every authored screen" is checkable by
listing one directory, and the two counts beside it are stated as measurements of
that directory and of the router's own route table.

The failure chain without the viewer-request function is:

1. A bookmark or hard refresh requests a dotless route such as
   `/account/update` directly from CloudFront.
2. Without a rewrite, CloudFront asks S3 for an object at that path. No such
   object exists because the SPA build contains an entry document and static
   assets, not one object per client route.
3. The OAC grant intentionally includes `s3:GetObject` but not
   `s3:ListBucket`. S3 therefore reports a missing key as `403 AccessDenied`
   rather than `404 NoSuchKey`.
4. Handling only 404 would consequently leave the normal private-origin
   missing-key response broken. The module handles both 403 and 404 and
   normalises either one to an HTTP 404.

The viewer-request function prevents that chain for routes. It examines the
last path segment: a segment without a dot is rewritten to
`default_root_object`, while a segment containing a dot remains an asset
request. A genuinely absent asset therefore stays a 404 instead of receiving
an HTML document with a successful status.

Refactoring Rationale: a distribution-wide 403/404-to-200 fallback was
rejected because `custom_error_response` cannot be scoped to one cache
behaviour and cannot distinguish a client route from a missing file. It made an
undeployed asset look healthy to monitors and caused browsers to receive HTML
where JavaScript was expected. Moving route selection into the viewer-request
function separates routes from files; the remaining custom error responses
preserve an honest 404 status.

Assumptions: both custom error responses set
`error_caching_min_ttl = 0`. A negative result held at an edge would otherwise
outlive the upload that supplies the requested object, making the same
deployment succeed for one viewer and fail for another according to edge-cache
state.

Alternatives Considered: an extension allow-list was rejected because an
unlisted extension would be treated as a route and receive HTML, recreating the
missing-asset defect. Lambda@Edge could perform the same classification, but
it adds request latency and per-invocation cost for logic supported by the
restricted CloudFront Functions runtime.

[`ui/nginx.conf`](../../../ui/nginx.conf) applies the same route-versus-file
boundary to the containerised UI path: an extension-bearing request must
resolve as a file, while the SPA fallback owns client navigation. CloudFront
plus S3 is the deployed UI path, so the two implementations must agree.

The navigation model is client-side by design. A mainframe transfer such as the
`EXEC CICS XCTL` pair at `app/cbl/COSGN00C.cbl:L231-L236`, which is the admin and
non-admin arm of a single `IF`, becomes one `navigate()` decision selected from the
JWT group claim. The edge rewrite is what lets that
model survive a bookmark or page reload rather than working only during an
already-running browser session.

## Delivery path from BMS to the SPA

The lineage census makes the replacement boundary measurable:

| Base mapset in `app/bms` | `DFHMDF` fields | Base mapset in `app/bms` | `DFHMDF` fields |
|---|---:|---|---:|
| `COACTUP` | 128 | `COACTVW` | 100 |
| `COTRN00` | 89 | `COUSR00` | 89 |
| `COCRDLI` | 72 | `COTRN02` | 61 |
| `COTRN01` | 56 | `CORPT00` | 42 |
| `COSGN00` | 37 | `COCRDUP` | 34 |
| `COCRDSL` | 31 | `COUSR02` | 29 |
| `COADM01` | 28 | `COMEN01` | 28 |
| `COUSR01` | 28 | `COUSR03` | 26 |
| `COBIL00` | 24 |  |  |

Those 17 base mapsets carry **902 `DFHMDF` fields**. Four extension mapsets add
`COPAU00` and `COPAU01` under
`app/app-authorization-ims-db2-mq/bms`, plus `COTRTLI` and `COTRTUP` under
`app/app-transaction-type-db2/bms`, for **21 mapsets in total**. Every base
and extension mapset declares a fixed **24×80** screen; the declaration at
`app/bms/COSGN00.bms:L26-L28` is the cited evidence and the same size was
verified across all 21 files.

The mainframe path delivered those maps as a 3270 datastream. This module
replaces only that presentation-delivery mechanism with static SPA assets over
HTTPS from CloudFront edge locations. The route handling supports the
one-route-per-mapset migration target without encoding route names in HCL.

Assumptions: the lineage files are specifications, not edit targets. The
[ALB module](../alb/README.md) and
[API Gateway module](../api-gateway-http/README.md) replace CICS transaction
routing. The [ECR module](../ecr/README.md), not this module, replaces the load
library declared by `DEFINE LIBRARY(CARDDLIB)` and its `DSNAME01` at
`app/csd/CARDDEMO.CSD:L489-L491`.

## ⚠️ Three-bucket warning and the logging boundary

> **The label refers to three ownership concerns, not a physical bucket
> count.** This module owns exactly two physical buckets: the private SPA
> origin and its internal CloudFront access-log destination. They form the SPA
> delivery concern. Neither is the versioned dataset bucket owned by
> [`infra/modules/s3-datasets`](../s3-datasets/README.md), which carries the
> ten GDG-equivalent dataset families, nor the Terraform state bucket owned by
> [`infra/bootstrap`](../../bootstrap/README.md). SPA delivery, datasets, and
> state have three distinct owners and four physical buckets; do not conflate
> or duplicate them.

The access-log bucket is created here because logging is part of the
distribution's gating contract. Owning the bucket, its policy, lifecycle, and
logging-v2 delivery resources in one module makes it impossible to create the
distribution while omitting its compatible destination. Taking a bucket ARN as
an input would split that invariant across owners, while calling a sibling
module would invert the environment root's responsibility for composition.
The deterministic name carries a `spa-logs` role segment plus the environment,
account, and Region; its ARN is published for policy wiring even though the
bucket remains an internal part of this delivery concern.

Trade-offs: encryption is intentionally asymmetric. SPA objects use the
customer-managed KMS key, while access-log objects use S3-managed `AES256`.
CloudFront standard log delivery cannot write to a destination whose default
encryption is SSE-KMS; changing the log bucket to `aws:kms` would stop log
delivery rather than strengthen a working path.

Refactoring Rationale: the ownership-control asymmetry associated with
legacy CloudFront logging is absent. Both buckets use
`BucketOwnerEnforced`. Standard logging v2 authorises the CloudWatch Logs
delivery service through a bucket policy, so it does not need the legacy
canonical-user ACL or `BucketOwnerPreferred`. This removes the second
authorisation plane that the older delivery mechanism required.

Alternatives Considered: a random bucket-name suffix was rejected. The
account and Region already make the deterministic names globally unique, and
the deployment pipeline must be able to address the SPA bucket by the name
exported from Terraform rather than discover an unrelated random token.

## Inputs, assumptions, and outputs

The generated reference lists the complete contract: 14 inputs and eight
outputs. The tables provide types and defaults; the prose below records the
cross-module relationships those values create.

Refactoring Rationale: this sentence said nine outputs. `outputs.tf` declares
eight — `distribution_id`, `distribution_arn`, `distribution_domain_name`,
`spa_bucket_name`, `spa_bucket_arn`, `log_bucket_arn`, `log_delivery_source_arn`
and `origin_access_control_id` — and the consumer table below has always listed
exactly those eight, so the prose count disagreed with the table beneath it as
well as with the HCL. The count is corrected rather than the table extended,
because a ninth output would be a contract addition and none is wanted here.

### What the module consumes

| Input group | Values | Ownership |
|---|---|---|
| Required identity and encryption | `environment`, `s3_kms_key_arn`, `s3_kms_key_policy_id` | The environment root supplies the discriminator and wires the KMS module's key and applied-policy identifier. |
| Required viewer and API boundary | `acm_certificate_arn`, `aliases`, `api_connect_src_origins` | The environment root supplies the custom viewer certificate, every hostname it covers, and the API origins admitted by the SPA content-security policy. |
| Delivery controls with defaults | `name_prefix`, `default_root_object`, `minimum_protocol_version`, `price_class`, `web_acl_arn` | The caller may override these controls; the certificate and aliases remain required regardless of defaults elsewhere. |
| Retention and teardown controls | `log_retention_days`, `spa_noncurrent_version_retention_days`, `force_destroy` | These values change retention or deletion protection, not the resource topology. |

Assumptions: `s3_kms_key_arn` is necessary but not sufficient. The key
policy must grant the CloudFront service principal `kms:Decrypt`, scoped to
this distribution, or OAC reaches the bucket but cannot decrypt its objects.
That grant is owned by the [KMS module](../kms/README.md). The environment root
passes `distribution_arn` back to KMS so the grant can be narrowed to the
resource that needs it.

Assumptions: the ACM certificate is mandatory and must be issued in
`us-east-1`, regardless of the Region containing the S3 bucket. The variable
validation rejects any other certificate Region before apply, and `aliases`
must be non-empty. There is no default-certificate branch: it was removed
because CloudFront pins that branch to a `TLSv1` viewer policy. Consequently,
`minimum_protocol_version` always applies, and its validation rejects the
legacy policies that admit TLS 1.0 or 1.1.

Assumptions: `s3_kms_key_policy_id` is an ordering token, not a credential.
CloudFront logging v2 consumes it in a lifecycle precondition so delivery
cannot start before the exact distribution and delivery-source grants exist.
The value is not used to look up or configure a key.

Assumptions: `api_connect_src_origins` is the only cross-origin API list
written into the content-security policy. An empty list produces
`connect-src 'self'`; schemes, paths, wildcards, and trailing slashes are
rejected so a caller cannot silently widen the browser boundary with a
malformed origin.

The module calls no sibling module and performs no name-based discovery.
`infra/envs/dev` and `infra/envs/prod` own all composition.

### What the module defines

Renaming or removing any output is a breaking change for the consumers below.

| Output | Consumer and purpose |
|---|---|
| `distribution_arn` | The environment root passes the exact distribution identity into the KMS key policy rather than granting an account-wide distribution wildcard. |
| `distribution_domain_name` | Environment outputs and DNS wiring use the CloudFront-assigned host as the distribution target; the custom alias remains the viewer-facing name. |
| `distribution_id` | [The deployment workflow](../../../.github/workflows/deploy.yml) invalidates the entry document after publishing a build. |
| `log_bucket_arn` | The environment root includes the log destination in its exact S3 policy context, which keeps the KMS key policy scoped to this bucket rather than widened. The destination's own default encryption is **SSE-S3 (`AES256`)**, not the supplied customer-managed key, because CloudFront standard log delivery cannot write to an SSE-KMS bucket, as the three-bucket warning above records. |
| `log_delivery_source_arn` | The KMS key policy scopes CloudWatch Logs delivery permissions to this exact CloudFront source. |
| `origin_access_control_id` | Operators use the identifier to confirm which OAC is attached while diagnosing an origin 403. |
| `spa_bucket_arn` | Publisher IAM policy and KMS policy wiring use the ARN form for resource scoping. |
| `spa_bucket_name` | The deployment workflow syncs the built `ui/dist/` bundle to the name form accepted by S3 commands. |

Assumptions: terraform-docs renders output descriptions directly from
`outputs.tf`, so the generated `log_bucket_arn` row and the row above it are the
same sentence and cannot disagree. Refactoring Rationale: they once did. The
generated row described the destination as CMK-encrypted while
`aws_s3_bucket_server_side_encryption_configuration.logs` declared `AES256`, and
this paragraph recorded the disagreement instead of resolving it — which left a
generated document asserting a stronger control than the resource carried, in the
one place a reviewer is most likely to read a control from. The output's
description in `outputs.tf` now states the `AES256` boundary and the delivery
constraint that forces it, the generated region was regenerated from it, and the
check-only documentation gate keeps the two byte-identical from here on.

The SPA receives its API endpoint through the environment variables documented
in [`ui/.env.example`](../../../ui/.env.example). Neither this README nor the
deployment workflow embeds an API endpoint, bucket name, distribution
identifier, account identifier, or certificate ARN.

### Where the published interface exceeds the enumerated one, and why

The interface this module declares is wider than the twelve inputs and five
outputs the module's own charter enumerates. Each addition is stated here rather
than left for a reader to discover from the generated table, because an
unexplained extra input or output is exactly the pattern Rule 1 forbids.

| Addition | Consumer that requires it | Why it cannot be dropped |
|---|---|---|
| input `s3_kms_key_policy_id` | `s3_kms_key_policy_id = module.kms.s3_key_policy_id` in both roots | CloudFront standard logging v2 and an OAC read of an SSE-KMS object both need the S3 key policy's CloudFront grants **already applied**. Terraform cannot infer that ordering from the ARN alone, because an ARN is known before the policy is written. The input is consumed purely as an ordering token. |
| input `api_connect_src_origins` | `api_connect_src_origins = var.cloudfront_api_connect_src_origins` in both roots | The response-headers policy's content-security-policy `connect-src` directive must name the API Gateway origin the SPA fetches from, or every authenticated call is blocked by the browser. The value is environment-specific and is supplied as a `TF_VAR_` environment variable rather than committed. |
| output `distribution_arn` | the KMS module's CloudFront `kms:Decrypt` grant, and an IAM policy statement, in both roots | The grant is scoped to *this* distribution's ARN. Without the output a root would have to hard-code an ARN, which is the thing the module exists to prevent. |
| output `log_bucket_arn` | the KMS module's exact allowed S3 encryption contexts, in both roots | The log destination is created in-module by necessity (see the three-bucket callout above), so its ARN is the only way a sibling can scope a grant to it. |
| output `log_delivery_source_arn` | `cloudwatch_log_delivery_source_arns` on the KMS module, in both roots | Log-delivery data-key generation is scoped to the exact delivery source. |

Trade-offs: the accepted cost is a wider interface than the charter enumerates.
Removing any one of the members above breaks a root that consumes it, and each
exists to keep a KMS grant or a browser policy scoped to an exact resource rather
than widened to a wildcard — so the narrower interface would be bought with a
broader permission, which is the wrong trade. Assumptions: the rule applied to
every member is *consumed or withdrawn*, not *convenient to publish*, which is why
no output exists for a DNS consumer that `distribution_domain_name` already
serves.


## Module boundary and usage

This directory is a **module, not a root**. It is never applied directly.
`infra/envs/dev` and `infra/envs/prod` call it with
`source = "../../modules/cloudfront-spa"`. It declares no `provider` block, no
`backend` block, and no sibling-module call. Provider configuration, default
tags, and remote state belong to the calling root; each root's `backend.tf`
uses the state infrastructure owned by
[`infra/bootstrap`](../../bootstrap/README.md).

The environment-root wiring has this shape:

```hcl
module "cloudfront_spa" {
  source = "../../modules/cloudfront-spa"

  name_prefix    = var.name_prefix
  environment    = var.environment
  s3_kms_key_arn = module.kms.s3_key_arn

  price_class   = var.cloudfront_price_class
  force_destroy = !var.deletion_protection

  acm_certificate_arn = var.cloudfront_acm_certificate_arn
  aliases             = var.cloudfront_aliases
  log_retention_days  = var.log_retention_days

  api_connect_src_origins = var.cloudfront_api_connect_src_origins
  s3_kms_key_policy_id    = module.kms.s3_key_policy_id
}
```

The `module.kms` references illustrate environment-root composition; they do
not cause this module to call KMS. The defaults remain in effect for
`default_root_object`, `minimum_protocol_version`,
`spa_noncurrent_version_retention_days`, and `web_acl_arn` in this wiring.

The CI workflow validates the module transitively by initialising each
environment root without a backend and running `terraform validate`. A local
module-only `init -backend=false` plus `validate` is a static schema check, not
an apply and not an operating model. Deployment and teardown procedures remain
centralised in the [infrastructure guide](../../README.md), the
[deployment runbook](../../../docs/runbooks/deploy.md), and the
[teardown runbook](../../../docs/runbooks/teardown.md).

## Environment parameterization

Dev and prod differ for this module only in **CloudFront price class** and
**log retention**, never topology. This is the same closed axis recorded in
the [infrastructure guide](../../README.md). AAP section 0.2.2 fixes the
deployment boundary at one Region and three availability zones and excludes
multi-Region and disaster-recovery topology.

`force_destroy` and `spa_noncurrent_version_retention_days` are protection and
retention controls, not shape switches. The same `force_destroy` value governs
both module-owned buckets so a teardown cannot remove the SPA bucket and stall
with an independently protected log bucket. Its default is `false`; the
version-aware purge belongs to the
[teardown runbook](../../../docs/runbooks/teardown.md), where the operator can
review the destructive scope.

Trade-offs: the default price class restricts the edge-location set to
control request and transfer cost, accepting higher latency for viewers far
from that set. A root can broaden the class without changing the distribution
topology. Log retention is independently adjustable because stored audit data
has an environment-specific cost and investigation horizon.

## Deliberate decisions

1. **Custom viewer TLS is unconditional.** Refactoring Rationale: the
   default-certificate branch was removed rather than narrowed because
   CloudFront stores `TLSv1` for that branch even when source code appears to
   request a modern policy. Required aliases and a `us-east-1` certificate make
   the configured minimum protocol effective in every environment.
2. **The response-headers policy is module-owned.** It sets a content-security
   policy, `nosniff`, frame denial, strict-origin referrer handling, and HSTS.
   Alternatives Considered: an AWS-managed response-headers policy was
   rejected because no managed policy can include the environment-specific
   `connect-src` origins supplied by `api_connect_src_origins`.
   Trade-offs: `style-src 'unsafe-inline'` is retained because the Ant Design
   theme injects runtime style elements whose hash cannot be known at build
   time, while `data:` is admitted only for image and font assets the build
   inlines. Cross-origin referrers omit the path so a route identifier is not
   disclosed. HSTS preload remains off because preload submission is a
   domain-owner decision that cannot be safely reversed by this module.
3. **Only `GET` and `HEAD` reach the origin.** Assumptions: the origin is a
   static bundle, so write verbs have no valid action there. `OPTIONS` is also
   excluded because browser API preflights go to API Gateway rather than this
   distribution.
4. **HTTP redirects to HTTPS instead of returning 403.**
   Alternatives Considered: `https-only` would refuse plaintext without
   serving content, but it would turn a typed or bookmarked bare hostname into
   an error. Redirecting reaches the same encrypted endpoint without exposing a
   plaintext response body.
5. **The managed caching-optimised policy is paired with targeted
   invalidation.** Content-hashed assets can remain cached, while the entry
   document reuses one key and must be invalidated with `distribution_id`
   after a publish. Legacy `forwarded_values` is absent because the provider
   makes it mutually exclusive with `cache_policy_id`.
   Alternatives Considered: a literal managed-policy identifier was
   rejected because an opaque identifier does not reveal the selected cache
   semantics during review; resolving `Managed-CachingOptimized` by name keeps
   the intent visible. A custom cache policy adds no useful variation because
   content-hashed assets need no cookie, header, or query-string cache key.
6. **Standard logging v2 replaces legacy `logging_config`.**
   Alternatives Considered: legacy logging was rejected because it offers no
   field allow-list. The v2 delivery retains 14 transport and routing fields
   but excludes query strings, cookies, and referrers; the retained URI stem
   carries an opaque route token rather than card data. Omitting logging was
   also rejected because it would remove the edge record needed to diagnose
   TLS, routing, and cache failures.
7. **Logging delivery resources use a `us-east-1` regional override.**
   Assumptions: CloudFront's logging control plane is fixed there even when
   the destination bucket is in the deployment Region. The destination waits
   for ownership controls, encryption, and the exact source-scoped policy so
   registration cannot race a bucket that cannot accept an object. Its write
   grant checks both the source account and the exact delivery-source ARN so a
   different account or logging source cannot reuse the service-principal
   permission.
8. **Both buckets are versioned and lifecycle-bounded.**
   Trade-offs: versioning preserves a rollback path but accumulates
   noncurrent objects. The SPA horizon is configurable; log objects expire at
   `log_retention_days`, and overwritten log versions receive a one-day
   backstop because CloudFront normally writes each log key once. Incomplete
   multipart uploads are aborted after seven days because their billed parts do
   not appear in normal object listings and a SPA upload has no legitimate
   week-long transfer.
9. **A web ACL is not created by default.** Alternatives Considered:
   adding a WAF module to every environment was rejected because the AAP's
   fixed module catalogue contains no such owner and this distribution serves
   public, static, read-only assets with no origin credentials. Authenticated
   and state-changing requests go to the API Gateway HTTP API behind a Cognito
   JWT authorizer, where request inspection applies to the meaningful surface.
   `web_acl_arn` remains available so an environment-owned ACL can be attached
   without changing this module.
10. **The deployed bundle is served from S3 and CloudFront, not an ECS task.**
    Alternatives Considered: reusing the containerised `ui` image behind the
    internal ALB was rejected because immutable files do not justify a Fargate
    task and target group, and routing every asset fetch through the API load
    balancer would mix public delivery traffic with authenticated operations.
    The container path remains useful for local execution and keeps its route
    and header behaviour aligned with the edge path.
11. **The routing function is published and runs at `viewer-request`.**
    Assumptions: publication places the function in the LIVE stage referenced
    by the distribution; a DEVELOPMENT-only function cannot serve traffic.
    Running before the cache lookup makes classification independent of cache
    state, while `origin-request` would execute only on misses. Rewriting the
    root is idempotent with `default_root_object`, so the two mechanisms cannot
    create a loop.
12. **The SPA bucket enables an S3 bucket key.** Trade-offs: grouping
    SSE-KMS operations under a bucket key reduces per-object KMS decrypt request
    volume on edge cache misses, but CloudTrail records a coarser KMS audit
    event covering multiple objects instead of one event per object.
13. **IPv6, edge compression, and SNI-only TLS are enabled.**
    Assumptions: an IPv6-only viewer must be able to reach the same
    distribution, and JavaScript, CSS, and HTML benefit from edge gzip or Brotli
    without requiring pre-compressed objects. Alternatives Considered: a
    dedicated-IP certificate was rejected because its recurring charge buys
    compatibility only for clients too old to execute the React application.
14. **Resource tags and Region are inherited rather than duplicated.**
    Alternatives Considered: a module `tags` input was rejected because it
    would create a second source that can disagree with the root provider's
    `default_tags`. Reading the Region from the inherited provider also
    prevents a separate string input from naming one Region while resources are
    created in another.
15. **The buckets do not enable S3 server-access logging.**
    Alternatives Considered: adding a third module-owned log bucket or
    self-logging either expands the ownership boundary or makes a bucket log
    writes generated by its own log stream. CloudFront standard logging v2
    already records every viewer request that reaches this delivery path with a
    curated field set.

## Validation gates

Every command below is gating and has no tolerated non-zero result. CI validates
the environment roots because that is where provider configuration and concrete
input values exist; the module-only validation is an additional static contract
check and never an apply.

```bash
# WHAT: check canonical formatting without mutating committed HCL.
# WHY : Trade-offs: CI treats formatting drift as a review failure rather than
#       rewriting it, so a regression is visible instead of silently repaired.
terraform fmt -check -recursive infra/

# WHAT: initialise and validate all Terraform roots without remote state.
# WHY : Assumptions: environment-root validation resolves this module with real wiring, and
#       bootstrap remains part of the same repository-wide gate.
for root in infra/bootstrap infra/envs/dev infra/envs/prod; do
  terraform -chdir="$root" init -backend=false -lockfile=readonly
  terraform -chdir="$root" validate
done

# WHAT: validate the module's own provider-schema contract.
# WHY : Assumptions: this isolates an invalid resource argument from root-level
#       wiring errors, so a failure names the layer that owns it.
terraform -chdir=infra/modules/cloudfront-spa init \
  -backend=false -lockfile=readonly
terraform -chdir=infra/modules/cloudfront-spa validate

# WHAT: initialise plugins and lint every Terraform directory.
# WHY : Assumptions: the absolute config path prevents recursive lint from falling
#       open -- a relative one resolves against the linted directory and finds nothing.
tflint --init --config="$PWD/infra/.tflint.hcl"
(
  cd infra
  tflint --recursive --config="$(pwd)/.tflint.hcl"
)

# WHAT: verify that the generated reference still matches the HCL.
# WHY : Trade-offs: CI is check-only, so a stale reference stays stale until a human
#       resolves it; an auto-fix step would hide the drift by rewriting this file.
terraform-docs --config infra/.terraform-docs.yml \
  --output-check infra/modules/cloudfront-spa
```

The workflow also scans migration-owned tracked files for secrets and runs its
bounded HIGH/CRITICAL infrastructure policy set. The six CloudFront/S3 review
items are implemented or documented as follows:

| Review item | Construction or documented boundary |
|---|---|
| Bucket public access | Both buckets enable all four public-access-block controls, disable ACL grants with `BucketOwnerEnforced`, and deny non-TLS requests. |
| Encryption at rest | The SPA bucket uses the supplied customer-managed key and an S3 bucket key. The logging destination uses the SSE-S3 compatibility exception required by CloudFront standard log delivery; a generic CMK-only scanner must bound any exception to this one destination rather than suppress the control tree-wide. |
| Bucket versioning | Both buckets enable versioning before their noncurrent-version lifecycle rules are installed. |
| Distribution access logging | Standard logging v2 is implemented by the CloudWatch Logs source, destination, and delivery resources. The legacy-argument-only scanner finding is scoped out because the distribution's `logging_config` field cannot express this safer field allow-list. |
| HTTPS and viewer TLS | HTTP redirects to HTTPS; the required custom certificate makes the validated modern `minimum_protocol_version` effective. |
| WAF posture | WAF association is not one of the workflow's selected material checks. Its absence is nevertheless documented: the fixed module catalogue has no WAF owner, the distribution serves immutable public assets, authenticated operations use API Gateway plus Cognito JWT validation, and `web_acl_arn` permits an environment-owned attachment. |

## Troubleshooting

| Symptom | Cause and action |
|---|---|
| CloudFront returns 403 for every asset | Confirm that the KMS key policy grants the CloudFront service principal `kms:Decrypt` for `distribution_arn`, and that the SPA bucket policy's `AWS:SourceArn` equals the same distribution. Use `origin_access_control_id` to verify the attached OAC, then inspect the [KMS module](../kms/README.md). |
| Origin requests return intermittent 403 responses | Confirm OAC uses `signing_behavior = "always"` and the origin uses the regional S3 domain. A viewer-controlled `Authorization` override or a SigV4 Region mismatch can look like a bucket-policy failure even when the policy is correct. |
| A deep route fails on bookmark or hard refresh | Confirm the published viewer-request function is associated at `viewer-request`, and that its rewrite target matches `default_root_object`. The 403/404 blocks intentionally preserve a 404 status; they do not replace the route function. Compare the classifier with [`ui/nginx.conf`](../../../ui/nginx.conf). |
| A missing JavaScript or CSS asset returns the SPA document | Confirm the request's last segment contains its extension and that the 403/404 mappings return status 404 rather than 200. A 200 response indicates the distribution-wide fallback that the viewer function replaced. |
| A published SPA build is not visible | CloudFront can retain the entry document under its stable key. Use the environment root's `distribution_id` for the entry-document invalidation performed by the [deployment workflow](../../../.github/workflows/deploy.yml); content-hashed assets do not require a broad invalidation. |
| Certificate validation or distribution creation fails | The ACM certificate must be issued in `us-east-1`, be validated, and cover every value in `aliases`. The variable validation catches the Region and alias shape; CloudFront verifies certificate status and name coverage. |
| Policy scanning reports a legacy viewer TLS policy | The checked-in module has no default-certificate branch. Confirm the caller supplies the required certificate and aliases and has not replaced the viewer-certificate block; then verify `minimum_protocol_version` is not one of the rejected TLS 1.0/1.1 policies. |
| No CloudFront access-log objects arrive | Keep the log bucket on `AES256`, verify the delivery-source ARN and source account conditions in its policy, confirm the three logging-v2 resources use `us-east-1`, and confirm `s3_kms_key_policy_id` is the applied policy identifier. Changing the bucket default to SSE-KMS stops delivery. |
| Destroy fails with a non-empty bucket | `force_destroy` defaults to `false`, and versioning means deleting visible current objects is insufficient. Follow the version-aware purge in the [teardown runbook](../../../docs/runbooks/teardown.md) for the reviewed environment root. |
| terraform-docs reports drift | Run the pinned generator locally with `infra/.terraform-docs.yml`, review the Requirements, Providers, Resources, Inputs, and Outputs changes, and commit the regenerated region. CI checks only and does not mutate this README. |

## Related documents

| Document | Relationship |
|---|---|
| [Infrastructure guide](../../README.md) | Module catalogue, environment parameterization, validation, deployment, and teardown boundary |
| [TFLint configuration](../../.tflint.hcl) | Mechanical HCL documentation, type, naming, and AWS lint rules |
| [terraform-docs configuration](../../.terraform-docs.yml) | Pinned generator, section selection, ordering, and byte-exact injection markers |
| [ADR-006: API and User Interface](../../../docs/adr/ADR-006-api-and-ui.md) | Decision D6 selecting the React SPA and S3/CloudFront delivery path |
| [ADR-009: Infrastructure-as-Code Tool](../../../docs/adr/ADR-009-iac-tool.md) | Terraform selection and state-management trade-offs |
| [Deployment runbook](../../../docs/runbooks/deploy.md) | Root-level provisioning, SPA publish, invalidation, and verification |
| [Teardown runbook](../../../docs/runbooks/teardown.md) | Reviewed destroy plan and version-aware bucket purge |
| [Code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md) | The HCL documentation form and the prose/mechanical split |
| [Migration guide](../../../MIGRATION_README.md) | Package-level build, deploy, run, migration, validation, and rollback entry points |
| [`ui/src/router.tsx`](../../../ui/src/router.tsx) | Client-side route ownership |
| [`ui/nginx.conf`](../../../ui/nginx.conf) | Container-path route-versus-file fallback parity |
| [`ui/.env.example`](../../../ui/.env.example) | Names of browser build-time environment variables without deployed values |
| [Infrastructure CI workflow](../../../.github/workflows/infra-ci.yml) | Exact check-only Terraform and policy gates |

## Generated reference

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_cloudfront_distribution.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_distribution) | resource |
| [aws_cloudfront_function.spa_router](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_function) | resource |
| [aws_cloudfront_origin_access_control.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_origin_access_control) | resource |
| [aws_cloudfront_response_headers_policy.security_headers](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_response_headers_policy) | resource |
| [aws_cloudwatch_log_delivery.cloudfront_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_delivery) | resource |
| [aws_cloudwatch_log_delivery_destination.cloudfront_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_delivery_destination) | resource |
| [aws_cloudwatch_log_delivery_source.cloudfront_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_delivery_source) | resource |
| [aws_s3_bucket.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_lifecycle_configuration.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_lifecycle_configuration.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_ownership_controls.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_ownership_controls.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_policy.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_public_access_block.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_versioning.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_s3_bucket_versioning.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_cloudfront_cache_policy.caching_optimized](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/cloudfront_cache_policy) | data source |
| [aws_iam_policy_document.logs_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_acm_certificate_arn"></a> [acm\_certificate\_arn](#input\_acm\_certificate\_arn) | ARN of the ACM certificate the distribution serves to viewers, which must be issued in us-east-1 and must cover every name in `aliases`; REQUIRED in every environment, because the default CloudFront certificate pins the viewer security policy to TLSv1. | `string` | n/a | yes |
| <a name="input_aliases"></a> [aliases](#input\_aliases) | Domain names the distribution answers on; REQUIRED and non-empty, and every name must be covered by the certificate in acm\_certificate\_arn. Each dot-separated label is limited to 63 characters and each complete entry to 253, the limits DNS itself imposes; a leading wildcard marker is not measured as a label but does count toward the total. | `list(string)` | n/a | yes |
| <a name="input_api_connect_src_origins"></a> [api\_connect\_src\_origins](#input\_api\_connect\_src\_origins) | Origins the SPA is permitted to reach with fetch or XHR, added to the content-security policy's connect-src directive alongside 'self'. Scheme and host only, no path and no trailing slash. Empty means same-origin only. | `list(string)` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment discriminator embedded in every resource name, so the dev and prod stacks can coexist without colliding on a globally unique bucket name. | `string` | n/a | yes |
| <a name="input_s3_kms_key_arn"></a> [s3\_kms\_key\_arn](#input\_s3\_kms\_key\_arn) | ARN of the customer-managed KMS key that encrypts the private SPA origin bucket at rest. | `string` | n/a | yes |
| <a name="input_s3_kms_key_policy_id"></a> [s3\_kms\_key\_policy\_id](#input\_s3\_kms\_key\_policy\_id) | Identifier of the fully applied S3 key policy. CloudFront logging v2 reads it only as an ordering token so delivery cannot start before the exact distribution and delivery-source KMS grants exist. | `string` | n/a | yes |
| <a name="input_default_root_object"></a> [default\_root\_object](#input\_default\_root\_object) | Document CloudFront returns for / and, deliberately, the same document the viewer-request routing function rewrites client-side routes to, so a deep link resolves instead of 404ing. | `string` | `"index.html"` | no |
| <a name="input_force_destroy"></a> [force\_destroy](#input\_force\_destroy) | Whether the SPA origin bucket may be deleted while it still holds objects; a teardown protection flag, not an environment-shape switch. | `bool` | `false` | no |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Days a CloudFront access-log object is kept before the log bucket's lifecycle rule expires it; the second of the two values the dev and prod roots differ on. | `number` | `30` | no |
| <a name="input_minimum_protocol_version"></a> [minimum\_protocol\_version](#input\_minimum\_protocol\_version) | Minimum TLS version the distribution accepts from viewers; always applied, because the distribution always serves a supplied ACM certificate and never the default one. | `string` | `"TLSv1.2_2021"` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Name prefix shared by every resource this module creates, so the SPA bucket, response-header policy, origin access control and distribution group together in the console and in cost reports. THIS module imposes 1 to 20 characters of lower-case letters, digits and interior hyphens, beginning and ending with a letter or digit; the environment roots forward one prefix to sixteen modules and so enforce the narrower INTERSECTION of all of them. | `string` | `"carddemo"` | no |
| <a name="input_price_class"></a> [price\_class](#input\_price\_class) | CloudFront edge-location tier that serves the SPA; one of the two values the dev and prod roots deliberately differ on for this module. | `string` | `"PriceClass_100"` | no |
| <a name="input_spa_noncurrent_version_retention_days"></a> [spa\_noncurrent\_version\_retention\_days](#input\_spa\_noncurrent\_version\_retention\_days) | Days a superseded SPA build is kept as a noncurrent object version before expiry, which is what bounds the storage cost of keeping front-end rollback available. | `number` | `30` | no |
| <a name="input_web_acl_arn"></a> [web\_acl\_arn](#input\_web\_acl\_arn) | ARN of a WAFv2 web ACL to associate with the distribution; null associates none, which is this package's default and documented posture. | `string` | `null` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_distribution_arn"></a> [distribution\_arn](#output\_distribution\_arn) | ARN of the CloudFront distribution serving the SPA. The environment root passes this exact ARN back to the KMS module so the CloudFront service principal can decrypt only this distribution's SSE-KMS origin objects. |
| <a name="output_distribution_domain_name"></a> [distribution\_domain\_name](#output\_distribution\_domain\_name) | CloudFront-assigned hostname of the distribution. This is the SPA's public entry point, the address that replaces a 3270 terminal session against CICS transaction CC00, and the environment roots re-export it as the deployed front-end host. |
| <a name="output_distribution_id"></a> [distribution\_id](#output\_distribution\_id) | Id of the CloudFront distribution serving the SPA. The deployment pipeline passes it to a cache invalidation after uploading a new build, and an operator uses it to address the distribution from the CLI. |
| <a name="output_log_bucket_arn"></a> [log\_bucket\_arn](#output\_log\_bucket\_arn) | ARN of the SSE-S3 (AES256) encrypted S3 destination for CloudFront standard logging v2. Its default encryption is deliberately NOT the supplied customer-managed key, because CloudFront standard log delivery cannot write to a bucket whose default encryption is SSE-KMS; the constraint and its compensating controls are recorded on the encryption resource in main.tf. The KMS module still consumes this ARN as an exact allowed S3 encryption context, so the key policy stays scoped to this bucket rather than widened, and a destination that can carry the key later needs no policy change. |
| <a name="output_log_delivery_source_arn"></a> [log\_delivery\_source\_arn](#output\_log\_delivery\_source\_arn) | Exact CloudWatch Logs delivery-source ARN for the distribution's standard logging v2 stream. The KMS key policy uses it to scope log-delivery data-key generation. |
| <a name="output_origin_access_control_id"></a> [origin\_access\_control\_id](#output\_origin\_access\_control\_id) | Id of the origin access control that signs this distribution's requests to the private origin bucket. Published so an operator diagnosing a 403 from the origin can confirm which origin access control the bucket policy is scoped to. |
| <a name="output_spa_bucket_arn"></a> [spa\_bucket\_arn](#output\_spa\_bucket\_arn) | ARN of the SPA origin bucket, for an IAM policy that grants a deployment role write access to this bucket and to no other. Published alongside spa\_bucket\_name because the two forms are not interchangeable. |
| <a name="output_spa_bucket_name"></a> [spa\_bucket\_name](#output\_spa\_bucket\_name) | Name of the private S3 bucket holding the built SPA bundle, and the destination the deployment pipeline syncs the ui/dist output into. This is neither the dataset bucket owned by the s3-datasets module nor the Terraform remote-state bucket owned by infra/bootstrap. |
<!-- END_TF_DOCS -->
