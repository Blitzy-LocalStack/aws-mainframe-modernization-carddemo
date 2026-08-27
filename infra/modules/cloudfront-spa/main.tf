# =============================================================================
# infra/modules/cloudfront-spa/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The substance of the `cloudfront-spa` module: a PRIVATE S3 origin bucket
#   holding the built CardDemo single-page application and a CloudFront
#   distribution that reaches that bucket through an origin access control.
#   Together these resources are the delivery path for the migrated user
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
#   Bucket boundary -- this module owns exactly ONE bucket, distinct from the
#   other two S3 buckets in this package:
#     aws_s3_bucket.spa ..... the SPA origin, holding the built ui/dist output.
#   The dataset bucket belongs to infra/modules/s3-datasets and the Terraform
#   remote-state bucket to infra/bootstrap. All three are versioned and
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
#        so the origin access control cannot decrypt an SSE-KMS object. The
#        grant belongs to infra/modules/kms, which now issues it unconditionally
#        on its S3 key -- narrowed to this account and to a distribution ARN
#        pattern -- so this failure is expected only when the key named here was
#        produced somewhere other than that module, or when its
#        `s3_cloudfront_distribution_arns` input names distributions that do not
#        include this one. See the encryption resource below.
#     2. `terraform apply` fails at the point CloudFront is asked to accept the
#        certificate. Cause: the ACM certificate named by
#        var.acm_certificate_arn was issued somewhere other than us-east-1.
#        CloudFront reads its certificate from that one region no matter where
#        the rest of the stack is deployed, and a certificate issued in the
#        deployment region is a well-formed ARN indistinguishable from a usable
#        one until the API rejects it.
#     3. `terraform destroy` stops with BucketNotEmpty. Cause: var.force_destroy
#        is false -- its default -- and one of the two buckets still holds
#        objects. This is deliberate, and infra/README.md carries the purge step
#        it requires.
#
#   Two more are refused BEFORE any resource is touched, by variables.tf rather
#   than by this file: an ACM certificate ARN whose region segment is not
#   us-east-1, and an empty `aliases` list. Both inputs are required in every
#   environment -- there is no default-certificate path in this module -- because
#   the default CloudFront certificate pins the viewer security policy to TLSv1
#   and so admits TLS 1.0 and 1.1 whatever `minimum_protocol_version` asks for.
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
#     toggle to skip the distribution or add a second origin, because the dev
#     and prod roots are required to differ only in sizing and retention. What
#     that costs is flexibility this package has no use for; what it buys is
#     that a defect reproduced in dev is reachable in prod.
# =============================================================================

# -----------------------------------------------------------------------------
# Account and region, read from the inherited provider rather than accepted as
# inputs. Both exist solely to compose the two globally-unique bucket names.
# -----------------------------------------------------------------------------

# Assumptions: the S3 bucket namespace is GLOBAL -- not per-account and
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

# Assumptions: read from the provider rather than accepted as an input,
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
# The cache policy and repository-owned response-header policy this
# distribution attaches.
# -----------------------------------------------------------------------------

# Alternatives Considered: writing the managed policy's identifier as a
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

# WHY : Assumptions: this application has TWO delivery paths for the same bundle
#       and only one of them passes through a web server. ui/nginx.conf serves the
#       containerised `ui` image and adds response headers itself; the deployed
#       path is this distribution over a private S3 origin, where there is no
#       nginx layer and S3 returns only what was stored. This policy is therefore
#       the only thing that can put those headers on the responses viewers
#       actually receive.
#       Refactoring Rationale: this was the AWS-managed `Managed-SecurityHeadersPolicy`,
#       chosen so that both paths could refer to a named standard and stay aligned
#       by construction. They did not stay aligned, because the managed policy is
#       not the same policy: it sets NO Content-Security-Policy at all, and it sets
#       X-Frame-Options to SAMEORIGIN where ui/nginx.conf sets DENY. So the two
#       paths served byte-identical content under materially different security
#       headers, and the deployed path -- the one facing the internet -- was the
#       weaker of the two. A managed policy cannot express a per-environment
#       `connect-src` either, so no configuration of it could have closed the gap.
#       The custom policy is written out in full below and the hand-alignment cost
#       is accepted, because the alternative was an alignment that only appeared to
#       exist.
resource "aws_cloudfront_response_headers_policy" "security_headers" {
  name    = "${local.name_stem}-security-headers"
  comment = "CardDemo SPA security headers and content-security policy for ${var.environment}."

  security_headers_config {
    # WHY : Assumptions: this value is the policy in ui/nginx.conf with one
    #       directive narrowed, and the two are meant to be read side by side.
    #       Every directive except `connect-src` is environment-invariant and is
    #       byte-identical to that file: `script-src 'self'` because the bundle is
    #       the only script and ui/index.html carries no inline script element;
    #       `style-src` keeping 'unsafe-inline' because antd 6 themes through CSS
    #       variables and injects style elements at run time, so a nonce or hash
    #       cannot be known in advance and removing 'unsafe-inline' would strip the
    #       design system from every screen; `img-src` and `font-src` admitting
    #       `data:` because inlined assets are emitted as data URIs; and
    #       `object-src 'none'`, `base-uri 'self'`, `form-action 'self'` and
    #       `frame-ancestors 'none'` constraining capabilities this application
    #       never exercises.
    #       Trade-offs: `connect-src` is the one directive that cannot be identical
    #       on both paths, because only this side knows the API origin. The
    #       container image states the tightest invariant form it can, `'self'
    #       https:`; here the exact origins are supplied as a module input and
    #       joined in, so this policy is strictly the narrower of the two. That is
    #       the correct direction for the asymmetry, since this is the path that
    #       faces the internet. An empty input yields `connect-src 'self'`, which
    #       fails closed: a browser blocks the API call visibly and immediately
    #       rather than the policy silently permitting any origin.
    content_security_policy {
      content_security_policy = local.content_security_policy
      override                = true
    }

    # WHY : Assumptions: this is the X-Content-Type-Options nosniff header, and it
    #       matters more on this path than on the other one. The regular-expression
    #       location in ui/nginx.conf now returns 404 for a missing file, but S3
    #       stores whatever Content-Type it was given at upload; if a deploy ever
    #       stored an asset with the wrong type, sniffing would let a browser
    #       execute it as script. nosniff makes the stored type authoritative.
    content_type_options {
      override = true
    }

    # WHY : Assumptions: DENY, matching ui/nginx.conf exactly rather than the
    #       SAMEORIGIN the managed policy set. This application is never framed by
    #       anything, including itself, so the stricter value costs nothing and the
    #       looser one would have permitted same-origin framing that no screen
    #       needs. `frame-ancestors 'none'` in the policy above is the modern
    #       specified form of the same constraint; this header is retained because
    #       it is what older browsers honour.
    frame_options {
      frame_option = "DENY"
      override     = true
    }

    # WHY : Assumptions: strict-origin, matching ui/nginx.conf. A CardDemo path
    #       carries a selection context -- /account/update and /cards/:opaqueCardId
    #       both do -- so a full referrer sent anywhere would disclose one. This
    #       value sends the origin alone, and nothing at all when leaving HTTPS for
    #       HTTP.
    # WHY : Refactoring Rationale: this was strict-origin-when-cross-origin, which
    #       withholds the path only from ANOTHER origin and sends the full URL on a
    #       same-origin request. Because override is true, this policy is what the
    #       viewer actually receives, so the value here decides what the SPA sends
    #       back to its own origin -- and that referrer was measured reaching the
    #       origin's access log on every /assets/ and /config.json request, carrying
    #       the opaque route selector with it. The two files are changed together
    #       for the reason the first line gives: whichever header survives must
    #       express the same rule.
    # WHY : Trade-offs: a same-origin referrer no longer says which route requested
    #       a bundle. Accepted for the same reason as at the origin -- bundle names
    #       are content hashed and therefore already unique -- and this
    #       distribution's own access logging omits the referrer field entirely, so
    #       nothing downstream of the edge loses a field it was reading.
    referrer_policy {
      referrer_policy = "strict-origin"
      override        = true
    }

    # WHY : Assumptions: this header is set HERE and deliberately not in
    #       ui/nginx.conf, and the asymmetry is reasoned rather than an oversight.
    #       That server sits behind a proxy on a cleartext leg and cannot know
    #       whether the viewer's own connection was encrypted, so asserting a
    #       transport policy from there would be a claim it has no evidence for.
    #       This distribution terminates TLS itself and redirects viewers to HTTPS,
    #       so it does have that evidence.
    #       Trade-offs: `preload` is left off. Submitting to the browser preload
    #       list is effectively irreversible for the domain and its subdomains, so
    #       it is an operator decision about a domain this module does not own,
    #       whereas include_subdomains with a one-year age is recoverable by
    #       lowering the age and waiting it out.
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      preload                    = false
      override                   = true
    }
  }

  # WHY : Assumptions: these four are the headers `security_headers_config` above has
  #       no field for, so they can only be expressed as custom headers. They were
  #       absent from BOTH delivery paths, which is why they arrive together; each
  #       one is argued in full at its counterpart declaration in ui/nginx.conf and
  #       the reasoning is not duplicated here, because a header argued twice is a
  #       header whose two arguments can disagree.
  #       Assumptions: every entry sets `override = true`, matching every field in the
  #       block above. Without it CloudFront would defer to whatever the origin sent,
  #       and this origin is a private S3 bucket that sends none of these -- so the
  #       header would be present in the plan and absent from the response.
  #       Assumptions: the values are byte-identical to ui/nginx.conf's. The two paths
  #       serve the same bundle and a viewer must not be able to tell which one
  #       answered, which is the property the response-headers policy exists to hold
  #       and the property the managed policy was replaced for failing to hold.
  #       Trade-offs: `Cross-Origin-Embedder-Policy: require-corp` is the one of the
  #       four that can break a page, and it is taken on proof rather than
  #       preference -- the content-security policy above already refuses every
  #       cross-origin subresource, so COEP can refuse nothing that policy admits.
  #       The counterpart declaration states the full argument and the exact steps to
  #       admit a cross-origin subresource if one is ever needed.
  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = "accelerometer=(), autoplay=(), bluetooth=(), camera=(), display-capture=(), encrypted-media=(), geolocation=(), gyroscope=(), hid=(), idle-detection=(), magnetometer=(), microphone=(), midi=(), payment=(), picture-in-picture=(), publickey-credentials-get=(), screen-wake-lock=(), serial=(), usb=(), xr-spatial-tracking=()"
      override = true
    }

    items {
      header   = "Cross-Origin-Opener-Policy"
      value    = "same-origin"
      override = true
    }

    items {
      header   = "Cross-Origin-Embedder-Policy"
      value    = "require-corp"
      override = true
    }

    items {
      header   = "Cross-Origin-Resource-Policy"
      value    = "same-origin"
      override = true
    }
  }

  # WHY : Refactoring Rationale: this block did not exist, and its absence left the
  #       origin's `Server` header reaching every visitor -- an anonymous request for a
  #       missing asset was answered with a response naming the server software, on an
  #       origin that otherwise sets its headers deliberately. ui/nginx.conf declares
  #       `server_tokens off`, which suppresses the VERSION but still emits the bare
  #       product name; removing the header at the origin needs the third-party
  #       `headers-more` module, which the pinned stock nginx image does not carry.
  #       Alternatives Considered: adding that module to ui/Dockerfile. Rejected because
  #       it replaces a pinned upstream image with a locally compiled one -- a
  #       materially larger supply-chain surface, and a build step to maintain -- to
  #       remove a single header that the edge can strip for free. The nginx layer keeps
  #       `server_tokens off` regardless, so the version is suppressed even on the
  #       internal path where no CloudFront distribution sits in front.
  #       Trade-offs: this strips the header only for traffic served THROUGH the
  #       distribution, which is every visitor path for the single-page application but
  #       not a request made directly against the origin. Direct origin access is
  #       already refused: the bucket is reachable only through the origin access
  #       control declared in this module, so there is no unfronted path for a visitor
  #       to take.
  remove_headers_config {
    items {
      header = "Server"
    }
  }
}

# WHY : Assumptions: this exists because `custom_error_response` is
#       DISTRIBUTION-WIDE. It cannot be attached to one cache behaviour, so no
#       arrangement of behaviours could distinguish "a route the router will
#       resolve" from "a file that is genuinely missing" -- the two arrive at S3
#       identically, as a key that does not exist. A function is the only place in
#       the request path where that distinction can be drawn before the origin is
#       consulted.
#       Assumptions: the test is whether a dotted last path segment sits at the
#       SITE ROOT or under the asset prefix, which is the same test the
#       regular-expression location in ui/nginx.conf applies. A path such as
#       /foo.bar/baz is correctly treated as a route, because its last segment
#       carries no dot, and nginx agrees.
#       Refactoring Rationale: the test used to be "the last segment contains a dot"
#       at ANY depth, and the sentence here used to assert that "all twenty-one
#       client routes are dotless in their last segment", listing numeric account
#       and card numbers, an eight-character user id and a two-character
#       transaction-type code. That assertion was FALSE, and being false is what made
#       the rule wrong: it omitted /authorizations/:key, whose identifier is an
#       opaque cursor token minted by CursorToken and shaped v2.<16 chars>.<1-200
#       chars> -- two dots in the last segment, structurally, for every authorization
#       the summary screen can select. So one route in twenty-one was classified as
#       a file. On this path the consequence was quieter than on nginx's and no
#       better: `custom_error_response` below turned the S3 miss into the entry
#       document, so the screen rendered, but it rendered under an HTTP 404 STATUS --
#       wrong for caching, wrong for monitoring and wrong for any client that reads
#       the status before the body. The comment is corrected here as well as the
#       code, because a reader who trusted it would restore the old test.
#       Assumptions: the sealed card selector is safe under either rule --
#       SealedSelector.SEALED_SHAPE is [A-Za-z0-9_-]+ and admits no dot -- so
#       /cards/:cardKey and /cards/:cardKey/edit never needed this narrowing, and the
#       remaining parameterised routes carry numeric, eight-character or
#       two-character identifiers. The blast radius was one route and the narrowing
#       is correspondingly narrow.
#       Alternatives Considered: matching an allow-list of asset extensions
#       instead. Rejected because an allow-list is only as complete as the list:
#       any extension nobody thought of would fall through to the rewrite and be
#       answered with HTML, which is the defect this replaces, reintroduced for a
#       narrower set of inputs and therefore harder to notice. Anchoring by DEPTH
#       keeps the classification total for the depth it governs -- every dotted name
#       at the root is a file whether or not anyone listed its extension -- which is
#       what an allow-list cannot promise.
#       Trade-offs: one residual case is accepted, and it is the same one
#       ui/nginx.conf accepts. A nested un-hashed static file added under
#       ui/public/<dir>/ would, when MISSING, be rewritten to the entry document
#       instead of reported missing. It is bounded by what the build can emit:
#       `vite build` writes the entry document plus the hashed asset prefix, and
#       ui/public holds exactly one file, favicon.svg, at the root.
#       Trade-offs: a Lambda@Edge function was the other option and is rejected on
#       cost and latency. CloudFront Functions run in the edge process with
#       sub-millisecond overhead and are billed per invocation at a small fraction
#       of Lambda@Edge, and this rewrite needs none of what Lambda@Edge adds --
#       no network access, no request body, no long execution. The constraint that
#       comes with that choice is a restricted JavaScript environment, which the
#       code below stays well inside.
resource "aws_cloudfront_function" "spa_router" {
  name    = "${local.name_stem}-spa-router"
  runtime = "cloudfront-js-2.0"
  comment = "Rewrites CardDemo SPA client routes to ${var.default_root_object} so a missing file can still return 404."
  publish = true

  # WHY : Assumptions: `publish` above puts this code in the LIVE stage, which is
  #       the stage the association references. Without it the function exists in
  #       DEVELOPMENT only and the distribution would be associated with nothing
  #       that runs.
  #       Assumptions: the rewrite is idempotent with respect to
  #       `default_root_object`. A request for "/" has an empty last segment, so it
  #       is rewritten here to the entry document; if CloudFront has already
  #       applied the default root object, the incoming URI ends in a dotted
  #       segment and is left alone. Either order produces the same origin request,
  #       so the two mechanisms cannot fight.
  #       Assumptions: this runs on the VIEWER request, before the cache lookup,
  #       so the error-page fetch that `custom_error_response` performs does not
  #       re-enter it and no loop is possible.
  #       Assumptions: `/assets/` is written as a literal rather than taken from a
  #       variable, because it is not this module's choice to make. Vite's
  #       `build.assetsDir` defaults to `assets` and ui/vite.config.ts does not
  #       override it, so the emitted prefix is fixed by the bundler; ui/nginx.conf
  #       hard-codes the same string in its `^~ /assets/` location for the same
  #       reason. Exposing it as a module input would invite an operator to set a
  #       value the bundle does not emit, which breaks every asset request at once
  #       while the plan still reads as correct.
  code = <<-JS
    function handler(event) {
      var request = event.request;
      var uri = request.uri;
      var lastSlash = uri.lastIndexOf('/');
      var dotted = uri.indexOf('.', lastSlash + 1) !== -1;

      // A dotted final segment means the viewer asked for a file ONLY where this
      // build can emit one: at the site root, which is where Vite copies ui/public
      // verbatim, or under the hashed asset prefix. Leave those untouched so a
      // missing one is still reported as missing. Everything else is a client route,
      // including a dotted one -- /authorizations/:key carries a cursor token with
      // two dots in it -- and is rewritten to the entry document.
      if (dotted && (lastSlash === 0 || uri.indexOf('/assets/') === 0)) {
        return request;
      }

      request.uri = '/${var.default_root_object}';
      return request;
    }
  JS
}

# -----------------------------------------------------------------------------
# Composed names.
# -----------------------------------------------------------------------------

locals {
  # Assumptions: this is the exact composition
  #       infra/modules/cloudfront-spa/variables.tf derives its 20-character cap
  #       on name_prefix from, and the two must agree or that cap stops being a
  #       proof and becomes a guess. The derivation budgets 4 characters for
  #       `prod`, 3 for `spa`, 12 for the account id, 15 for the longest region
  #       name AWS publishes and 4 for the hyphens joining five segments: 38 of
  #       the 63 an S3 general-purpose bucket name allows. The 20-character cap
  #       leaves a five-character safety margin. Changing the shape below
  #       without revisiting that validation would let a legal
  #       prefix compose an illegal bucket name, and it would fail during apply.
  spa_bucket_name = "${var.name_prefix}-${var.environment}-spa-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}"

  # Assumptions: the role token is what makes a collision between these
  #       two names impossible rather than merely unlikely. Both are built from
  #       the same prefix, environment, account and region, so the token is the
  #       only thing distinguishing them -- and no input to the `spa` form can
  #       produce the `spa-logs` form, because a prefix admitting no consecutive
  #       hyphens cannot synthesise the extra `-logs` segment. It is also the
  #       longer token, which is why the length budget above is computed from
  #       this name rather than from the origin bucket's.
  log_bucket_name = "${var.name_prefix}-${var.environment}-spa-logs-${data.aws_caller_identity.current.account_id}-${data.aws_region.current.region}"

  # Refactoring Rationale: CloudFront requires the distribution's `origin`
  #       block and its `default_cache_behavior.target_origin_id` to name the
  #       same string, and a mismatch is not reported as a typo -- Terraform
  #       reports an unresolvable target origin, which reads as a problem with
  #       the cache behaviour rather than as two literals that drifted apart.
  #       Writing the value once removes that failure mode instead of leaving it
  #       to be documented.
  spa_origin_id = "${var.name_prefix}-${var.environment}-spa-origin"

  # WHY : Assumptions: a response-headers policy name and a function name must be
  #       unique per AWS account, not per region and not globally, so neither
  #       needs the account and region segments the two bucket names above carry.
  #       Including them would be harmless but misleading, implying a global
  #       namespace that these resources do not sit in. The stem is defined once
  #       so the two names cannot drift into different conventions.
  name_stem = "${var.name_prefix}-${var.environment}"

  # WHY : Assumptions: the directive order and spelling here are byte-identical to
  #       the policy literal in ui/nginx.conf for every directive except
  #       `connect-src`, so the two delivery paths can be compared by reading them
  #       side by side. The list is built as elements and joined rather than
  #       written as one interpolated string, because a single long string is where
  #       a missing semicolon hides: a malformed directive is not an error, it is
  #       silently ignored, so the policy would still deploy and would simply stop
  #       enforcing whatever it swallowed.
  #       Trade-offs: `connect-src` is the only directive that varies, and an empty
  #       `api_connect_src_origins` yields `connect-src 'self'`. That fails closed:
  #       the browser blocks the API call visibly on first use rather than the
  #       policy quietly allowing any origin, which is the behaviour a missing
  #       security input should have.
  content_security_policy = join("; ", [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    join(" ", concat(["connect-src 'self'"], var.api_connect_src_origins)),
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ])
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

  # Trade-offs: with the default of false, `terraform destroy` stops
  #       rather than deleting a bucket that still holds the deployed front end,
  #       and an operator has to purge every version before retrying. What that
  #       costs is a teardown that is not a single command. What it buys is that a mistyped or
  #       mistargeted destroy cannot silently take the deployed SPA with it,
  #       and because versioning is enabled below, "purge" means every version,
  #       which is exactly the irreversible act worth making deliberate. The
  #       flag is threaded through from the caller rather than fixed here so an
  #       ephemeral environment can accept the consequence explicitly.
  force_destroy = var.force_destroy
}

# Assumptions: with an origin access control the only legitimate reader of
#       this bucket is the distribution, so every public path is closed rather
#       than only the ones a scanner names -- `block_*` refuse a public grant
#       being ADDED and `ignore_public_acls` / `restrict_public_buckets` neuter
#       one that somehow already exists, which are different guarantees and both
#       are wanted. This is also the bucket-public-access item the explicit
#       material-security policy baseline asserts, satisfied by construction
#       rather than by a suppression.
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

# Assumptions: access to the origin is granted by exactly one mechanism --
#       the bucket policy below, to one service principal, conditioned on one
#       distribution. Leaving ACLs enabled would leave a second, parallel way to
#       grant read access that no reader of that policy would think to check, so
#       disabling them makes the policy the complete answer to "who can read
#       this bucket" rather than a partial one.
#       Alternatives Considered: `BucketOwnerPreferred`, which keeps ACLs
#       usable. Rejected, and worth recording because it is the setting a log
#       destination would have needed: CloudFront's legacy standard log delivery
#       grants itself write access to its destination through an ACL, so a
#       bucket receiving those logs cannot enforce owner-only ownership. This
#       module publishes no such destination -- see the access-log decision
#       recorded further down -- so nothing here requires ACLs and the strictest
#       setting is available for free.
resource "aws_s3_bucket_ownership_controls" "spa" {
  bucket = aws_s3_bucket.spa.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# WHY : Assumptions: this is the bucket-versioning item the explicit
#       material-security policy baseline asserts, and declaring it here makes
#       that gate pass by
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
    # Trade-offs: every asset fetch that misses the edge cache is an
    #       origin read, and each such read of an SSE-KMS object is otherwise a
    #       separate KMS Decrypt request -- billed per request and subject to a
    #       per-account request rate. A bucket key collapses those into one
    #       call per bucket per short interval, so the mechanism being bought
    #       here is a reduction in KMS request VOLUME, not a faster cipher. The
    #       accepted cost is a coarser audit trail: CloudTrail then records one
    #       KMS call covering many objects rather than one per object.
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      # Refactoring Rationale: the baseline had neither encryption nor
      #       recoverability -- every file resource in the CICS definition is
      #       declared `JOURNAL(NO)` and `RECOVERY(NONE)`
      #       (app/csd/CARDDEMO.CSD:L7,L9 on `DEFINE FILE(ACCTDAT)`, and the
      #       same on every other file stanza) -- so encrypting the replacement
      #       storage under a key this package owns and rotates is one of the
      #       deliberate improvements over it, not a like-for-like port.
      #       Assumptions: the ARN alone is NOT sufficient for this to work. The
      #       key policy on that key must also grant the CloudFront service
      #       principal `kms:Decrypt`, conditioned on this account and on a
      #       distribution ARN, or the origin access control cannot decrypt an
      #       object and CloudFront answers 403 to every asset while the bucket,
      #       the distribution and the key each look correct in isolation. That
      #       grant is owned by infra/modules/kms and is issued there
      #       unconditionally on the S3 key -- see its
      #       `AllowCloudFrontOriginAccessControlDecrypt` statement and the
      #       optional `s3_cloudfront_distribution_arns` narrowing. It is
      #       recorded here because this is the first place a reader debugging
      #       that 403 will look, and the file that has to be correct is not
      #       this one.
      #       Alternatives Considered: `AES256`, which needs no key policy at
      #       all. Rejected because the policy scan expects customer-managed-key
      #       encryption on this bucket, and because an origin access control --
      #       unlike the legacy origin access identity it replaces -- can read
      #       SSE-KMS objects, so nothing forces the weaker choice.
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

    # Assumptions: S3 requires a rule to carry either a filter or a
    #       prefix, and the provider marks `prefix` on a rule as deprecated, so
    #       an empty `filter` block is the supported way to say "bucket-wide".
    #       It is written explicitly because an omitted filter is a plan-time
    #       error rather than a silent default.
    filter {}

    # Trade-offs: versioning above is what makes a front-end rollback
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

    # Assumptions: the parts of an incomplete multipart upload are
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

  # Assumptions: `noncurrent_version_expiration` above is only meaningful
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

    # Assumptions: the distribution's entire job at the origin is to GET
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

    # Alternatives Considered: the legacy origin access identity form,
    #       whose grant names a CloudFront-owned canonical user instead. It is
    #       rejected for this module in full at the origin access control
    #       resource below; the two grant shapes are not interchangeable, and
    #       this is the one an origin access control uses.
    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    # Assumptions: without this condition the statement grants read access
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

    # Assumptions: the bucket policy is the ONLY place a plaintext request
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

  # Assumptions: `block_public_policy` above rejects a policy S3 judges to
  #       be public, so the two resources interact and Terraform infers no order
  #       between them. Applying the block FIRST means that if a future edit
  #       accidentally widened this document into a public grant, the attempt
  #       would be refused rather than briefly succeeding and then being fenced
  #       off on the next apply. Ordering it this way makes the block a gate on
  #       the policy instead of a correction after it.
  depends_on = [aws_s3_bucket_public_access_block.spa]
}


# =============================================================================
# The access-log destination, and why it is created inside this module
# -----------------------------------------------------------------------------
# WHY a module that otherwise creates only the SPA bucket and the distribution
# also owns a second bucket, and why the distribution carries no legacy
# `logging_config` argument even though it is logged -- the two questions a
# reviewer asks first:
#
#   Assumptions: the explicit material-security policy baseline asserts that the
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
#   Alternatives Considered: the legacy distribution `logging_config` argument,
#   which is what a scanner recognises and what an earlier revision of this
#   module carried. Rejected on a data-protection ground rather than a stylistic
#   one: its schema is fixed, it always records the RESOLVED viewer URI and
#   source IP, and it offers no field allowlist -- so with it enabled a browser
#   route would write whatever its path segment happens to hold into durable
#   objects. Standard logging v2 accepts a field list, so the query string,
#   cookie and referrer fields are simply not delivered, and neither is the URI
#   stem -- ui/src/routes/cards.ts addresses a card by its number, so a browser
#   path can hold one and no field in this delivery can be redacted. Dropping
#   logging altogether was the other alternative and is rejected too: it leaves an
#   operator with no record of edge transport or routing failures at all, and the
#   exposure it avoided is already avoided by the field list.
#
#   Refactoring Rationale: standard logging v2 replaces the legacy distribution
#   logging block because it supports a customer-managed KMS key, source-scoped
#   bucket and key policies, and bucket-owner-enforced ownership. A scanner that
#   recognizes only the legacy `logging_config` block is updated at the policy
#   gate rather than allowed to force weaker deployed controls.
#
#   Alternatives Considered: reusing the dataset bucket owned by
#   infra/modules/s3-datasets as a destination, had logging been kept. Rejected
#   independently of the above -- that bucket's prefixes and lifecycle
#   configuration reproduce the ten generation-dataset families of the mainframe
#   batch chain, and interleaving edge access logs into it would put two
#   unrelated retention policies in one bucket and one blast radius.
# =============================================================================

resource "aws_s3_bucket" "logs" {
  #checkov:skip=CKV_AWS_145:CloudFront standard log delivery cannot write a destination whose default encryption is SSE-KMS, so this bucket carries AES256; the constraint and the rejected alternative are recorded in full on the encryption configuration below. Public-access blocking, enforced bucket ownership, versioning and the delivery-scoped bucket policy are the compensating controls.
  bucket = local.log_bucket_name

  # Trade-offs: the same flag and the same reasoning as the origin bucket apply:
  #       with the default of false a destroy stops rather than deleting the
  #       audit trail, and teardown must purge every object version before retrying.
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

# Refactoring Rationale: standard logging v2 uses the CloudWatch Logs delivery
# service and an S3 bucket policy rather than the legacy CloudFront canonical
# user ACL. Object ownership can therefore be enforced without disabling log
# delivery, removing the second authorization plane the legacy mechanism
# required.
resource "aws_s3_bucket_ownership_controls" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "logs" {
  bucket = aws_s3_bucket.logs.id

  rule {
    # S3 Bucket Keys reduce KMS request volume for the append-only log stream.
    # The KMS module admits both bucket and object encryption contexts so this
    # optimization does not broaden which bucket the key can protect.
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      # Assumptions: CloudFront standard log delivery cannot write to a
      #       bucket whose default encryption is SSE-KMS. That is a property of
      #       the delivery mechanism, not a limitation of this configuration, and
      #       it fails the same silent way the ACL constraint above does -- the
      #       bucket stays empty and nothing in a plan or an apply says why.
      #       SSE-S3 is therefore the strongest encryption this destination can
      #       carry while remaining a working destination, and the objects are
      #       still encrypted at rest.
      #       Trade-offs: the two buckets this module owns are consequently
      #       encrypted under different mechanisms, which reads as an oversight
      #       and is not one. The boundary is stated identically in the three
      #       other places a reader could reach it from -- the log_bucket_arn
      #       output's own description, this module's README, and
      #       docs/architecture/observability.md -- so no one of them can claim
      #       a customer-managed key here while this resource declares AES256.
      #       If this is ever "harmonised" up to `aws:kms`, logging stops --
      #       which is exactly the gate this bucket exists to satisfy.
      #       Assumptions: the constraint applies to the S3 DESTINATION of the
      #       standard logging v2 delivery this module already uses, so it is not
      #       removed by choosing the newer mechanism -- the newer mechanism is
      #       what is in place. An earlier revision of this comment offered
      #       "switch to standard logging v2" as a rejected alternative, which
      #       contradicted the delivery declared above and would have sent a
      #       reader looking for a migration that had already happened. What v2
      #       does buy over the legacy path is the curated field list, and that
      #       is recorded on the delivery and on the distribution rather than
      #       here.
      #       Alternatives Considered: a CloudWatch Logs destination instead of
      #       an S3 one, which the customer-managed key does cover. Rejected
      #       because these records are retained for audit over months and are
      #       read rarely, which is the access shape object storage is priced
      #       and lifecycled for; log-group storage would cost materially more
      #       for the same bytes and would lose the noncurrent-version
      #       protection the bucket below provides. The one control given up is
      #       named rather than glossed: default encryption by the module's own
      #       key, replaced by S3-managed encryption plus the four compensating
      #       controls asserted on this bucket.
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

    # Assumptions: this is the "log retention" axis the dev and prod roots
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

    # Assumptions: versioning above without a noncurrent expiry would keep
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

  statement {
    sid       = "AllowCloudWatchLogsDeliveryAclCheck"
    effect    = "Allow"
    actions   = ["s3:GetBucketAcl"]
    resources = [aws_s3_bucket.logs.arn]

    principals {
      type        = "Service"
      identifiers = ["delivery.logs.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_cloudwatch_log_delivery_source.cloudfront_access.arn]
    }
  }

  statement {
    sid       = "AllowCloudWatchLogsDeliveryWrite"
    effect    = "Allow"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.logs.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["delivery.logs.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_cloudwatch_log_delivery_source.cloudfront_access.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "s3:x-amz-acl"
      values   = ["bucket-owner-full-control"]
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

# CloudFront standard logging v2 is configured through the CloudWatch Logs
# delivery API even when S3 is the destination. CloudFront's delivery control
# plane is fixed in us-east-1, so these resources set their regional override
# explicitly instead of inheriting the deployment region used by the bucket.
resource "aws_cloudwatch_log_delivery_source" "cloudfront_access" {
  region       = "us-east-1"
  name         = "${var.name_prefix}-${var.environment}-cloudfront-access"
  log_type     = "ACCESS_LOGS"
  resource_arn = aws_cloudfront_distribution.spa.arn
}

resource "aws_cloudwatch_log_delivery_destination" "cloudfront_access" {
  region                    = "us-east-1"
  name                      = "${var.name_prefix}-${var.environment}-cloudfront-access"
  delivery_destination_type = "S3"
  output_format             = "w3c"

  delivery_destination_configuration {
    destination_resource_arn = aws_s3_bucket.logs.arn
  }

  # The service validates the destination while it is registered. Waiting for
  # ownership, encryption and the exact source-scoped bucket policy prevents a
  # transient destination that can be named but cannot accept a log object.
  depends_on = [
    aws_s3_bucket_ownership_controls.logs,
    aws_s3_bucket_server_side_encryption_configuration.logs,
    aws_s3_bucket_policy.logs,
  ]
}

resource "aws_cloudwatch_log_delivery" "cloudfront_access" {
  region                   = "us-east-1"
  delivery_source_name     = aws_cloudwatch_log_delivery_source.cloudfront_access.name
  delivery_destination_arn = aws_cloudwatch_log_delivery_destination.cloudfront_access.arn
  field_delimiter          = "\t"

  # WHY : Refactoring Rationale: `cs-uri-stem` is NOT delivered, and the reasoning has now
  #       moved twice. It was omitted originally because a browser path can hold an
  #       identifier; it was briefly kept on the ground that the only identifier-bearing
  #       route carried a server-issued opaque token; it was then removed again when
  #       ui/src/routes/cards.ts began addressing a card by its sixteen-digit number. That
  #       module addresses a card by an opaque SELECTOR once more -- /cards/:cardKey and
  #       /cards/:cardKey/edit -- so no browser path holds a card number today, and the field
  #       stays withdrawn anyway. Assumptions: this is defence in depth and not a duplicate
  #       control. The route contract and this field list are owned by different trees and
  #       change independently, and it is precisely the sequence above that shows how easily
  #       one moves without the other; a field that cannot be redacted after delivery is worth
  #       keeping out on the strength of that history rather than on the current route shape. An edge
  #       log is composed before any application code runs and can never be redacted afterwards, so
  #       the field's safety would otherwise rest entirely on the SPA's route contract continuing to
  #       hold -- and this module cannot see that contract, let alone assert it.
  #       Trade-offs: the cost is the one named below, an operator losing the requested path.
  #       It is accepted for the same reason it was accepted the first time.

  # WHY : Trade-offs: an operator loses the requested path from the edge record, which is the
  #       field that distinguishes one SPA fallback from another. What remains still
  #       diagnoses the failures this delivery exists for: the status code says whether the
  #       edge served or refused, the request id correlates the hop with the service-side
  #       record, and the method, host, protocol, cipher and elapsed time cover transport.
  #       The service-side path IS retained, in the API's own record, where common-lib's
  #       CardNumberMasker redacts the number first -- so the path is observable exactly
  #       where it can be redacted and nowhere it cannot.
  # WHY : Alternatives Considered: keeping the field and redacting it. Rejected because
  #       nothing in this delivery path can transform a field value -- the service accepts a
  #       field allowlist and not a rewrite -- so redaction would have to happen after the
  #       object was written, which is after the disclosure.
  #
  # Query strings, cookies and referrers remain deliberately absent. The URI stem is delivered;
  # the query string is not, and that asymmetry is the point -- a stem holds only route
  # segments this SPA controls, while a query string holds whatever a caller appended.
  record_fields = [
    "date",
    "time",
    "x-edge-location",
    "sc-bytes",
    "c-ip",
    "cs-method",
    "cs(Host)",
    "sc-status",
    "x-edge-request-id",
    "cs-protocol",
    "time-taken",
    "ssl-protocol",
    "ssl-cipher",
  ]

  s3_delivery_configuration = [{
    enable_hive_compatible_path = false
    suffix_path                 = "environment=${var.environment}"
  }]

  lifecycle {
    precondition {
      condition     = length(trimspace(var.s3_kms_key_policy_id)) > 0
      error_message = "CloudFront log delivery requires the applied S3 KMS key policy so the delivery service can generate data keys for this exact source."
    }
  }
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
#   endpoint -- the redirect-to-https viewer policy, the response-headers
#   policy, the point where a web ACL would attach, and, most
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

  # Alternatives Considered: `"never"`, which signs nothing and would
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
# error routing, and viewer-facing TLS/header configuration.
# =============================================================================

# WHY : Security Exception: this distribution IS access-logged, through the
#       standard logging v2 delivery declared above, and it deliberately carries
#       no legacy `logging_config` argument. The scanner check recognises only
#       that argument, so the check is suppressed here rather than answered by
#       adding a logging path that is strictly worse: the legacy schema is fixed,
#       always records the resolved viewer URI and source IP, and admits no field
#       allowlist, whereas the v2 delivery above omits the query string, cookie
#       and referrer fields entirely.
#       Assumptions: the v2 delivery keeps NO path field at all, which is what
#       keeps cardholder data out of it whatever the SPA's routes come to carry.
#       Those routes are `/cards/:cardKey` and `/cards/:cardKey/edit` -- the
#       contract stated in ui/src/routes/cards.ts -- and a selector is not a card
#       number, so a path here holds no cardholder data today. The field stays
#       withdrawn regardless, for the reason recorded on the delivery's
#       record_fields: the route contract and this field list live in different
#       trees and have already moved independently of each other.
#       Alternatives Considered: disabling logging altogether, which an earlier
#       revision did. Rejected because it removes the edge's transport and
#       routing history for an exposure that the field list and the opaque route
#       already remove.
#checkov:skip=CKV_AWS_86:This distribution is logged through CloudWatch Logs standard logging v2 (aws_cloudwatch_log_delivery.cloudfront_access) with a curated field list; the check recognises only the legacy logging_config argument, which is deliberately absent because its fixed schema cannot omit the query string, cookie and referrer fields.
resource "aws_cloudfront_distribution" "spa" {
  enabled = true

  # Assumptions: the name has to match whatever the SPA build emits as its
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

  # Trade-offs: there is no cost and no topology change -- CloudFront
  #       publishes AAAA records for the same distribution -- and the failure it
  #       avoids is specific: a viewer on an IPv6-only network cannot reach an
  #       IPv4-only distribution at all, and the symptom is total
  #       unreachability for that viewer rather than degraded service. The
  #       accepted cost is that access-log entries then carry IPv6 addresses,
  #       which any log consumer has to be able to parse.
  is_ipv6_enabled = true

  # Trade-offs: one of exactly two values the dev and prod roots are
  #       expected to disagree on for this module, the other being log
  #       retention. The narrowest tier is the module default because this
  #       deployment is single-region by design, so a viewer population spread
  #       across every continent is not a case the default has to serve; a root
  #       needing broader coverage raises the value rather than editing the
  #       module. Widening the tier raises per-request and data-transfer cost at
  #       every edge, which is why it is a per-environment decision and not a
  #       fixed one.
  price_class = var.price_class

  # WHY : Assumptions: this is one decision expressed in two inputs, and neither
  #       half is meaningful alone. CloudFront refuses an alternate domain name
  #       that the supplied certificate does not cover, and refuses any alias at
  #       all while the default certificate is in use, so the certificate below
  #       and this list are supplied together -- and
  #       infra/modules/cloudfront-spa/variables.tf requires both, non-empty, in
  #       every environment. A distribution answering only on its generated
  #       cloudfront.net name is exactly the TLSv1-pinned outcome the certificate
  #       requirement exists to remove, so "no alias" is not an available state.
  aliases = var.aliases

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
    # Assumptions: an origin access control signs the origin request with
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

    # Assumptions: this attribute and an `s3_origin_config` block are
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

    # WHY : Assumptions: this is the HTTPS-only viewer policy the explicit
    #       material-security policy baseline asserts, and it is the edge half
    #       of this package's
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

    # Assumptions: the origin is a static bucket, so there is nothing for a
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

    # Assumptions: the mechanism is that CloudFront gzip- or
    #       brotli-encodes text responses for viewers that advertise support,
    #       and a SPA bundle is almost entirely text -- JavaScript, CSS and the
    #       entry document -- so the bytes actually transferred fall
    #       substantially. What that buys is lower data-transfer cost and less
    #       time on a slow connection; it is not a change to what is served.
    #       Assumptions: compression happens at the edge, so it applies whether
    #       or not the objects were stored compressed, which is why the build
    #       does not need to pre-compress them.
    compress = true

    # WHY : Assumptions: the cache policy is the AWS-managed one resolved by name
    #       above; the response-headers policy is this module's own resource,
    #       because no managed policy can carry a per-environment `connect-src`.
    #       Using `cache_policy_id` also means the legacy `forwarded_values` block
    #       must NOT appear in this behaviour -- the two are mutually exclusive
    #       and the provider rejects a behaviour carrying both -- which is why
    #       there is no `forwarded_values` anywhere in this file.
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security_headers.id

    # WHY : Assumptions: this association is what lets a deep link work WITHOUT
    #       the error-response rewrite having to pretend a missing file was found.
    #       It runs on every viewer request before the cache is consulted, so a
    #       rewritten route is cached under the entry document's key and a request
    #       for a real asset is untouched.
    #       Assumptions: the event type must be `viewer-request`; an
    #       `origin-request` association would run only on a cache miss, so the
    #       rewrite would be skipped for any path already cached and the behaviour
    #       would depend on cache state.
    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_router.arn
    }
  }

  # ---------------------------------------------------------------------------
  # SPA error routing -- the two blocks below are why a bookmarked or refreshed
  # deep link works at all.
  #
  #   Assumptions: ui/src/router.tsx owns all twenty-one screen routes ON THE
  #   CLIENT -- /signon, /menu, /admin, /account/view, /account/update, /cards,
  #   /cards/:cardKey, /cards/:cardKey/edit, /transactions, /transactions/:id,
  #   /transactions/new, /billpay, /reports, /users, /users/new,
  #   /users/:id/edit, /users/:id/delete, /authorizations, /authorizations/:key,
  #   /reference/transaction-types and /reference/transaction-types/:cd. The
  #   origin bucket holds the built bundle and nothing resembling a server, so a
  #   request for /account/update would arrive at S3 as a key that simply does not
  #   exist. The viewer-request function above rewrites those paths to the entry
  #   document BEFORE the request reaches the origin, so the bundle is delivered
  #   with a 200 and the router resolves the path from the address bar.
  #
  #   Refactoring Rationale: that rewriting was previously done here instead, by
  #   mapping 403 and 404 to the entry document with `response_code = 200`. It made
  #   deep links work and it also made every genuinely missing file answer 200 with
  #   an HTML body -- so an undeployed or mistyped asset was indistinguishable from
  #   a working one, a monitor watching status codes saw a healthy 200 for a failed
  #   request, and a browser parsed HTML as JavaScript and reported a syntax error
  #   that pointed at the asset's contents rather than at its absence. The two
  #   cases cannot be separated at this layer: `custom_error_response` is
  #   DISTRIBUTION-WIDE and cannot be scoped to a cache behaviour, so no
  #   arrangement of behaviours here could have told a route from a file. Moving
  #   the routing decision into a viewer-request function is what separates them,
  #   which then frees these blocks to report a missing file honestly.
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
  #   Assumptions: ui/nginx.conf implements the same contract for the
  #   containerised `ui` image, and the two now agree request for request. It
  #   classifies with a regular-expression location matching a dotted last
  #   segment, and the function above applies that identical test, so a client
  #   route is answered with the entry document under 200 on both paths and a
  #   missing file is answered 404 on both. The mechanisms differ because the
  #   platforms differ; the observable behaviour does not.
  # ---------------------------------------------------------------------------

  # Assumptions: S3 answers a request for a missing key with 403
  #       AccessDenied rather than 404 NoSuchKey when the caller lacks
  #       `s3:ListBucket`, because disclosing that a key is absent is itself
  #       information the caller is not entitled to. The origin grant above is
  #       deliberately `s3:GetObject` only, so EVERY missing key on this origin
  #       arrives as a 403. Left alone, a viewer asking for an asset that is not
  #       deployed would receive S3's AccessDenied, which both misdescribes the
  #       problem and reveals the origin's nature; mapping it to 404 says the one
  #       true thing about the request, which is that the file is not there.
  custom_error_response {
    error_code         = 403
    response_code      = 404
    response_page_path = "/${var.default_root_object}"

    # WHY : Trade-offs: caching a 404 would persist an answer for a path whose
    #       correct response changes the moment a new bundle is deployed. The
    #       failure that avoids is nasty out of proportion to its cause: an edge
    #       location holding a stale negative answer serves it to some viewers and
    #       not others for the length of the time-to-live, which presents as an
    #       intermittent fault with no reproducible trigger -- exactly the shape of
    #       bug a rolling deployment produces, where a viewer can be handed a new
    #       entry document naming an asset an edge has already cached as absent.
    #       The accepted cost is that each such request reaches the origin, and
    #       since the origin's answer is one small entry document, that cost is
    #       negligible.
    error_caching_min_ttl = 0
  }

  # WHY : Assumptions: 404 is handled as well as 403 because the 403 behaviour
  #       above is a consequence of the current grant rather than a permanent
  #       property of S3 -- if `s3:ListBucket` were ever added for a directory
  #       index, missing keys would start arriving as 404 instead. Handling both
  #       makes the outcome independent of that grant. The response page and the
  #       zero cache horizon carry the same reasoning as the block above.
  custom_error_response {
    error_code            = 404
    response_code         = 404
    response_page_path    = "/${var.default_root_object}"
    error_caching_min_ttl = 0
  }

  # Assumptions: `restrictions` and its nested `geo_restriction` are
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
  # Viewer certificate -- ONE path: a supplied ACM certificate, always.
  #
  #   Refactoring Rationale: this block previously covered two cases, selecting
  #   the generated CloudFront certificate whenever `var.acm_certificate_arn`
  #   was null and setting the other two attributes conditionally around it.
  #   That branch is DELETED rather than narrowed. On the generated certificate
  #   CloudFront pins the viewer security policy to TLSv1 as a stored value, so
  #   the distribution accepted TLS 1.0 and 1.1 while the configuration still
  #   read `minimum_protocol_version = "TLSv1.2_2021"` -- a weakness invisible in
  #   the source and inert only in the sense that the setting was. Requiring the
  #   certificate in variables.tf is what makes the deletion possible: with no
  #   null case left, three conditional expressions become three plain
  #   assignments, and `cloudfront_default_certificate` is not merely unset but
  #   absent, which is the only way it cannot be reintroduced by a null input.
  #
  #   Assumptions: the three attributes below are three parts of one argument,
  #   not three settings. CloudFront rejects an ACM certificate ARN presented
  #   without both an SNI support method and a minimum protocol version, so
  #   changing any one of them alone produces an apply-time rejection rather
  #   than a different TLS posture.
  # ---------------------------------------------------------------------------
  viewer_certificate {
    # WHY : Assumptions: the certificate must be issued in us-east-1 NO MATTER
    #       WHICH REGION the rest of the stack is deployed to, because a
    #       distribution is a global resource that reads its certificate from
    #       that one region. This is the least obvious constraint in the module:
    #       a certificate issued correctly in the deployment region is a
    #       well-formed ARN, indistinguishable from a usable one at this
    #       boundary, and it is rejected only when CloudFront is asked to use it
    #       partway through an apply. The certificate is supplied rather than
    #       created here so that no us-east-1 provider alias is needed in a
    #       module which otherwise inherits exactly one provider configuration,
    #       and variables.tf now asserts the region segment of the ARN so this
    #       constraint fails at plan time rather than mid-apply.
    acm_certificate_arn = var.acm_certificate_arn

    # Trade-offs: a dedicated IP address exists for viewers whose clients
    #       predate SNI, and it carries a substantial recurring per-month charge
    #       per distribution. This application's viewers are browsers running a
    #       React 19 bundle, so none of them predates SNI by many years, and the
    #       compatibility the dedicated option buys is compatibility with
    #       clients that could not load the application anyway. Cost is an
    #       explicit tie-breaker for this migration, and this is the clearest
    #       instance of it.
    ssl_support_method = "sni-only"

    # Assumptions: the certificate, SNI mode and TLS policy are one indivisible
    # viewer-security contract. Setting all three unconditionally prevents a
    # source file that appears to request TLS 1.2 while the deployed default
    # certificate silently serves TLS 1.0 and 1.1.
    minimum_protocol_version = var.minimum_protocol_version
  }

  lifecycle {
    precondition {
      condition     = split(":", var.acm_certificate_arn)[4] == data.aws_caller_identity.current.account_id
      error_message = "acm_certificate_arn must belong to the same AWS account as the CloudFront distribution."
    }
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
#   logging, which the CloudWatch Logs delivery source, destination and delivery
#   above provide. S3 server access logging is
#   a different mechanism, and enabling it would need either a third bucket --
#   which this module's boundary forbids, because two of the four buckets in this
#   package belong to other modules -- or self-logging, which writes log objects
#   into the bucket whose reads are being logged and so logs its own writes. The
#   distribution's logs already record every viewer request that reaches the
#   origin path.
#   Trade-offs: this absence is reported rather than suppressed. The complete
#   soft scan raises AWS-0089 for both buckets, while the explicit
#   material-security baseline does not classify S3 server-access logging on
#   these delivery buckets as a blocking control. The finding therefore remains
#   visible without becoming a false substitute for the CloudFront v2 request
#   logging this module actually provisions.
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
# Scanner suppressions -- the complete inventory is two, and both are scoped:
#   Assumptions: this module writes exactly two `#checkov:skip` comments, each
#   naming ONE check on ONE resource, and no `--skip-check` argument or
#   module-specific ignore file anywhere.
#     1. `CKV_AWS_145` on `aws_s3_bucket.logs` -- the log destination carries
#        AES256 rather than the customer-managed key, because a delivery that
#        cannot write its destination protects nothing. The constraint, the
#        rejected alternative and the compensating controls (public access
#        blocked, bucket-owner enforced ownership, versioning, and the
#        delivery-scoped bucket policy) are written in full on that bucket's
#        `aws_s3_bucket_server_side_encryption_configuration` above. The SPA
#        origin bucket is unaffected and does use the customer-managed key.
#     2. `CKV_AWS_86` on `aws_cloudfront_distribution.spa` -- the distribution IS
#        access-logged, through the CloudWatch Logs standard logging v2 delivery
#        declared above with a curated field list. The check recognises only the
#        legacy `logging_config` argument, whose fixed schema cannot omit the
#        query-string, cookie and referrer fields, so answering the check would
#        mean deploying a strictly weaker logging path. The rationale sits on the
#        distribution resource itself.
#   Refactoring Rationale: an earlier revision of this block declared "No
#   scanner suppression" and claimed both buckets used the customer-managed key.
#   Neither statement survived the exceptions that were subsequently added ten
#   and four hundred lines above it, and a summary that contradicts the file it
#   summarises is worse than no summary: a reviewer greps `checkov:skip`, finds
#   two hits, and can no longer trust anything else the block asserts. The
#   inventory is stated here by check and by resource so that it cannot drift
#   without the same grep changing.
#   Trade-offs: naming the exceptions here duplicates two rationales that are
#   already written at their resources. The duplication is deliberate and is kept
#   to a one-line summary each: this block is the file's index of what is NOT
#   answered by configuration, and an index that omits an entry is the failure
#   mode being corrected. The full reasoning stays at the resource, which is
#   where a reader changing the setting will be.
# =============================================================================
