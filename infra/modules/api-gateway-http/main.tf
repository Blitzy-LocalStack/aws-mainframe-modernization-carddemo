# =============================================================================
# infra/modules/api-gateway-http/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The public edge of the migrated CardDemo system. This file provisions the
#   whole entry point as eleven resources that only make sense together: an API
#   Gateway HTTP API carrying the cross-origin contract for the
#   CloudFront-hosted SPA; a Cognito JWT authorizer that validates the
#   caller's token before a request leaves the edge; a dedicated VPC Link
#   security group plus its exact 443 egress/ALB-ingress pair; a VPC Link into
#   the private application subnets; one private proxy integration onto the
#   internal ALB's listener; one authorized route per published path; one
#   unauthenticated route per enumerated pre-token path; a CloudWatch log
#   group; and the stage that binds access logging and throttling to all of it.
#
#   The request path, end to end: the browser SPA calls the API's
#   `execute-api` endpoint over HTTPS; the JWT authorizer validates the bearer
#   token against the Cognito issuer, audience and required scope; the matched
#   route forwards to the single integration; the integration crosses the VPC
#   Link into the private application subnets and reaches the internal ALB
#   listener over TLS; the ALB's per-service rules dispatch to the owning
#   service, which validates the same token again independently.
#
#   Exactly one path is reached WITHOUT a token, and it is the path that issues
#   tokens: `POST /auth/signon`, the sign-on exchange. It is published by its
#   own route resource with `authorization_type = "NONE"`, from its own input
#   (`var.public_route_keys`) that is validated to an exact method and path
#   under `/auth`, refuses `ANY` and every greedy matcher, and must be disjoint
#   from `var.route_keys`. It carries a throttle an order of magnitude tighter
#   than the authorized default. Everything else on this stage requires a valid
#   access token carrying the required scope.
#
#   Seven of the eight migrated services are published here -- auth, accounts,
#   cards, transactions, reference, authorizations and reports. The eighth,
#   batch-service, is reached only by the Step Functions synchronous run-task
#   call and is deliberately absent from the route table: `var.route_keys`
#   carries a validation that rejects any `/batch` route outright, and
#   `var.public_route_keys` admits no prefix other than `/auth` at all.
#
# Parameters:
#   Not applicable -- this file declares no `variable` block. Every value it
#   consumes, including the ALB listener target, VPC id, ALB security group,
#   VPC Link subnets, Cognito issuer and audience, the cross-origin
#   allow-lists, the throttle limits and the tag map, is declared with its own
#   `description` in variables.tf.
#
# Return values:
#   Not applicable -- this file declares no `output` block. The API endpoint,
#   the identifiers and the stage details this module exports to its caller
#   are declared in outputs.tf.
#
# Exceptions / failure modes:
#   Nothing here fails on its own: a module is never applied directly, it is
#   called as `source = "../../modules/api-gateway-http"`. Every failure below
#   therefore surfaces on the CALLING root's `plan` or `apply`, and none of
#   them surfaces from inspecting this folder:
#   - an `alb_listener_arn` that is not a listener ARN produces an integration
#     that does not traverse the VPC Link at all, so the request never reaches
#     the internal ALB. variables.tf constrains the input to an ARN containing
#     `:listener/` precisely because the wrong shape here fails quietly rather
#     than loudly;
#   - a `cognito_issuer_uri` that does not match the pool the tokens were
#     minted by rejects every request with a 401 at the edge, before any
#     service is reached;
#   - a VPC Link placed in subnets with no route to the ALB leaves requests
#     timing out at the integration rather than failing to deploy;
#   - a `spa_cors_allow_origins` entry that does not match the CloudFront
#     distribution's origin exactly fails preflight in the browser only -- the
#     API itself stays healthy and the failure is invisible server-side;
#   - a route key that collides with one already deployed fails during `apply`,
#     after the API itself has been created. stage_name is pinned to `$default`
#     at plan time, so it cannot move the endpoint behind a new path segment.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: this module calls no sibling module and reads no
#     `data` source that looks up another module's resources. Every
#     cross-module value arrives as a variable. Resolving the ALB listener, the
#     subnets or the user pool by lookup instead would couple this folder to
#     another module's internal resource names and to the account it runs in,
#     so a rename next door would break this module, and it could not be
#     planned at all without live credentials.
#   - Trade-offs: because a module has no `provider` block it also has no
#     provider `default_tags`, so tagging cannot be inherited; `var.tags` is
#     the only tag channel available and is merged onto each taggable resource
#     individually. Five of the eleven resources below take tags -- the API, the
#     dedicated security group, the VPC Link, the log group and the stage. The
#     security-group rule resources, authorizer, integration and both route
#     resources expose no `tags` argument, so their omission is the provider's
#     shape and not an oversight here.
#   - Assumptions: the policy checks on this directory -- access logging enabled,
#     throttling configured, and TLS on both sides of the edge -- are satisfied
#     structurally rather than by suppression: each property the checks look for
#     is an unconditional argument on a resource below, so a future edit cannot
#     drop one without deleting a visible line.
#   - Trade-offs: "an authorizer on every route" is the ONE check property that is
#     not met by every route here, and it carries the only policy-scan skip in
#     this file -- a single scoped `checkov:skip=CKV_AWS_309` on
#     `aws_apigatewayv2_route.public`, with its reason on the same line. The
#     exception cannot spread: the public routes live in a separate resource, so
#     no authorized key can lose its authorizer by accident; the keys come from
#     an input validated down to the exact three pre-token POST operations, with
#     `ANY` and greedy matchers refused at plan time; and a disjointness check
#     against `var.route_keys` prevents one key from being both. The exposure is
#     published in `outputs.tf` so it is auditable from a plan rather than only
#     from this comment.
#
# Deliberately absent (stated so omission does not read as oversight; each is
#   justified again at its point of use below):
#   - no `provider` and no `backend` block: both belong to the calling root;
#   - no `module` block and no `data` source: see the first WHY above;
#   - no `variable` or `output` block: those are variables.tf and outputs.tf;
#   - no custom domain, ACM certificate or DNS record: the `execute-api`
#     endpoint IS the entry point in this design;
#   - no IAM role, and no account-level CloudWatch Logs role: an HTTP API
#     private integration needs no execution role, and an account-wide
#     resource must not be created by a module that may be instantiated more
#     than once;
#   - no web-application-firewall association, usage plan, API key, request
#     validator, mock integration or Lambda authorizer: the native JWT
#     authorizer replaces the last of those outright.
#
# Baseline lineage:
#   The baseline expressed its public dispatch surface in the CICS resource
#   definition, as eighteen `DEFINE TRANSACTION(<id>) ... PROGRAM(<name>)`
#   pairs running from `CAUP` to `COACTUPC` at app/csd/CARDDEMO.CSD:L306 and
#   L308 through `CU03` to `COUSR03C` at L479-L480; the route table below is
#   that surface's analogue. Its authentication front door was
#   `DEFINE TRANSACTION(CC00)` at L378 with `PROGRAM(COSGN00C)` at L379, the
#   analogue of the JWT authorizer. Each of those eighteen stanzas states
#   `RESSEC(NO) CMDSEC(NO)` -- at L315, L375 and L476 among the rest -- so
#   resource- and command-level checking was carried by the programs
#   themselves rather than by the region. The `'A'`/`'U'` discriminator those
#   programs read, `SEC-USR-TYPE PIC X(01)` at app/cpy/CSUSR01Y.cpy:L22, is
#   the field the `carddemo-admin` / `carddemo-user` group mapping below
#   carries forward. Both files are REFERENCE-ONLY: they are cited here and
#   never modified, and the deployment paths they belong to remain exactly as
#   they are. This module adds an entry point beside them; it removes none.
# =============================================================================

locals {
  # WHY : Trade-offs: composed once here instead of being repeated per
  #       resource, so a rename is one edit and all seven resources are
  #       provably named consistently. `apigw-http` is the module
  #       discriminator, which keeps this module's resources distinguishable
  #       in a console listing from the fifteen other modules deployed into
  #       the same environment. The cost accepted is one indirection between
  #       reading a resource and knowing its literal name.
  name_stem = "${var.name_prefix}-apigw-http-${var.environment}"

  # WHY : (1) Assumptions: a module has no `provider` block, so there is no
  #       provider `default_tags` to inherit from and `var.tags` is the only
  #       channel by which a caller's tags can reach these resources.
  #       (2) Trade-offs: `var.tags` is the FIRST argument to `merge`, so on a
  #       key collision the module's own three keys win. That precedence is
  #       deliberate rather than incidental: `Module` and `Component` record
  #       which folder created a resource, and a caller able to overwrite them
  #       could make a resource claim an origin it does not have -- which is
  #       the one thing an operator reads these tags to find out. Environment
  #       is fixed for the same reason: it has to agree with the name stem
  #       above, and it cannot if a caller may set it independently. The cost
  #       is that these three keys are not caller-overridable.
  tags = merge(var.tags, {
    Module      = "api-gateway-http"
    Component   = "edge"
    Environment = var.environment
  })

  # WHY : Assumptions: the `/aws/vendedlogs/` prefix is load-bearing rather
  #       than cosmetic. A log group under that prefix accepts API Gateway
  #       access logs without an account-level CloudWatch Logs role and
  #       without a log-group resource policy, whereas an arbitrary prefix can
  #       require one. That account-level role is account-wide and
  #       single-valued, so a module that may be instantiated more than once
  #       must neither create nor contend for it -- which is why the header
  #       lists it as deliberately absent. Choosing this prefix is therefore
  #       what keeps the module self-contained; a caller that overrode the
  #       convention would have to take ownership of that account-level
  #       resource itself.
  access_log_group_name = "/aws/vendedlogs/apigateway/${local.name_stem}"

  # WHY : (1) Assumptions: requestId is the supported HTTP API context identity.
  #       The integration below copies that same value into X-Request-Id, which
  #       common-lib places in the requestId MDC field. That supported pair is
  #       what makes an edge line joinable to service lines. The caller's
  #       X-Correlation-Id remains a separate application identity and is still
  #       allowed by CORS, but is deliberately not read through an unsupported
  #       access-log context expression.
  #       (2) Trade-offs: the field set is bounded to routing and timing facts
  #       -- request identity and time, route key, method, protocol, status,
  #       response length, the integration's own status and latency, and the
  #       total response latency. `integrationLatency` beside `responseLatency`
  #       is what separates a slow service from a slow edge; either one alone
  #       cannot. The cost is that a failure needing request content to
  #       diagnose must be reproduced rather than read back, which is accepted
  #       for the reason in (3).
  #       (3) Refactoring Rationale: `$context.path` WAS logged here and has
  #       been REMOVED, and the removal is the point rather than a tidy-up. The
  #       field carried the RESOLVED request path, and in this system a resolved
  #       path is personal data: AAP 0.7.1 replaces the CICS COMMAREA's
  #       selection context with REST path parameters, so `/cards/{cardNum}`
  #       resolves to a path containing a full primary account number and
  #       `/accounts/{acctId}` to one containing an account id. Writing that
  #       into a durable log is CWE-532, and it defeats the masking the services
  #       perform at the one point every single request passes through -- the
  #       access log would become the least protected copy of the very
  #       identifiers the services work hardest to mask. `routeKey` is retained
  #       in its place: it is the route TEMPLATE (`ANY /cards/{proxy+}`), which
  #       is a constant drawn from `var.route_keys` and cannot vary with the
  #       caller's data, so it answers "which surface was called" without
  #       answering "whose record". Alternatives Considered: logging a hashed or
  #       tokenised path, rejected because a per-request hash is not joinable to
  #       anything and a stable hash of a small, dense identifier space (a
  #       sixteen-digit card number) is trivially reversible by enumeration, so
  #       it would carry the disclosure while looking as though it did not; and
  #       dropping the path only in prod, rejected because a dev environment
  #       loaded from app/data seed records still holds real-shaped account and
  #       card numbers, so the exposure is not a production-only concern. The
  #       cost accepted is that a failing request can no longer be tied to a
  #       specific record from the edge log alone; `requestId` and
  #       `correlationId` remain, and the owning service's log is where a
  #       request is followed to a record under that service's own redaction.
  #       (4) Assumptions: `sourceIp` is retained but is SENSITIVE rather than
  #       neutral. A caller's address is personal data under most privacy
  #       regimes and, unlike the path, it is the only field that supports the
  #       abuse investigation an internet-facing edge exists to make possible --
  #       correlating one actor's requests across accounts. It is kept for that
  #       reason and not because it is harmless; the controls that make keeping
  #       it defensible are the group's retention (`var.log_retention_days`) and
  #       its encryption key (`var.access_log_kms_key_arn`), which is why prod
  #       is required to supply a customer-managed key at the log group below.
  #       (5) Trade-offs: NOTHING that authorises a caller is logged -- no
  #       `Authorization` header, no bearer token, no JWT claim, and no request
  #       or response body. The alternative was to log the authorizer claims and
  #       the request body, which is a common choice and would make a failed
  #       request diagnosable from the log alone; it is rejected for the same
  #       reason as the path in (3). The authorizer and body context variables
  #       are available and are deliberately not referenced. This is an omission
  #       a reader cannot see, so it is stated rather than left to be inferred
  #       from the field list above.
  #       (4) Alternatives Considered: hand-written JSON. `jsonencode` is used
  #       instead because it emits one valid, deterministically ordered line
  #       and cannot produce the unbalanced-brace format string that a
  #       hand-edited literal can -- a malformed format is accepted at apply
  #       and only discovered when the logs turn out to be unparseable.
  access_log_format = jsonencode({
    requestId          = "$context.requestId"
    requestTime        = "$context.requestTime"
    routeKey           = "$context.routeKey"
    httpMethod         = "$context.httpMethod"
    protocol           = "$context.protocol"
    status             = "$context.status"
    responseLength     = "$context.responseLength"
    responseLatency    = "$context.responseLatency"
    integrationStatus  = "$context.integration.status"
    integrationLatency = "$context.integration.latency"
    integrationError   = "$context.integrationErrorMessage"
    sourceIp           = "$context.identity.sourceIp"
    extendedRequestId  = "$context.extendedRequestId"
  })
}

# -----------------------------------------------------------------------------
# The HTTP API -- the front door itself.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_api" "this" {
  name        = local.name_stem
  description = "CardDemo edge: JWT-authorized HTTP API onto the internal ALB."

  # WHY : (1) Alternatives Considered: a v1 REST API, rejected on two concrete
  #       counts. An HTTP API has a NATIVE JWT authorizer, so validating a
  #       Cognito token at the edge needs no Lambda authorizer function to
  #       write, deploy, grant invoke permission to and pay per invocation for;
  #       a REST API would have required exactly that function and its whole
  #       lifecycle, for a check the platform already performs. And the
  #       per-request charge is lower, which tells for a workload that is a
  #       browser SPA issuing many small calls.
  #       (2) Alternatives Considered: gRPC for the transport as a whole,
  #       rejected because REST/JSON is directly consumable by a browser SPA
  #       and by the existing external integrations, whereas gRPC would require
  #       a proxy for browser traffic with no offsetting gain. Recorded in
  #       docs/adr/ADR-006-api-and-ui.md.
  protocol_type = "HTTP"

  # WHY : Assumptions: disabling that endpoint is a reflex hardening step that
  #       would be wrong in this module. No custom domain is provisioned here,
  #       so the `execute-api` endpoint IS the public entry point, and
  #       switching it off would leave the API with no way in at all. Stated
  #       because the absence of the argument would otherwise read as an
  #       oversight to anyone applying that habit.

  # WHY : (1) Trade-offs: declared once at the edge instead of implemented in
  #       each of the seven published services. One declaration cannot drift;
  #       seven implementations can, and a mismatch between two of them would
  #       surface as a UI that works on one route and fails preflight on
  #       another. The cost accepted is that an individual service cannot vary
  #       its own cross-origin policy, which nothing in this system requires.
  #       (2) Alternatives Considered: `allow_origins = ["*"]`, rejected on two
  #       independent grounds -- a wildcard makes this front door callable from
  #       any page on the internet, and a browser refuses a wildcard origin on
  #       a credentialed request, so it would not even work for the SPA that
  #       needs it. variables.tf enforces this structurally by refusing a bare
  #       asterisk and either scheme-plus-asterisk spelling of it, and by
  #       requiring each entry to be an exact origin, so the wildcard cannot be
  #       reintroduced by passing a different value.
  #       (3) Assumptions: `authorization` is required to be present in
  #       `var.cors_allow_headers`, and variables.tf validates that it is,
  #       because the authorizer below reads the bearer token from exactly that
  #       header -- a preflight omitting it fails in the browser only, leaving
  #       the API itself looking healthy.
  #       (4) Assumptions: `expose_headers` is set as well as `allow_headers`, and
  #       the two are independent directions rather than one setting. Without an
  #       `expose_headers` entry a browser lets script read only the CORS-safelisted
  #       response headers, so the correlation identifier the shared filter writes
  #       onto every response, and the location header a report submission answers
  #       with, are both present on the wire and unreadable from the page. The
  #       failure mode is the reason this needs stating: the request succeeds, the
  #       header is visible in a network panel, and the read returns nothing.
  cors_configuration {
    allow_origins  = var.spa_cors_allow_origins
    allow_methods  = var.cors_allow_methods
    allow_headers  = var.cors_allow_headers
    expose_headers = var.cors_expose_headers
    max_age        = var.cors_max_age_seconds
  }

  tags = local.tags
}

# -----------------------------------------------------------------------------
# The JWT authorizer -- token validation before a request leaves the edge.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id = aws_apigatewayv2_api.this.id
  name   = "${local.name_stem}-cognito-jwt"

  # WHY : (1) Refactoring Rationale: authenticating at the edge means the seven
  #       published services validate an already-signed token rather than each
  #       implementing its own sign-on. The mechanism is what makes this a real
  #       change rather than a like-for-like port of the baseline sign-on
  #       transaction cited in the header: in the baseline the session
  #       structure is storage the client echoes back between screen turns, so
  #       a client could in principle assert its own user type. Here the client
  #       asserts nothing -- the group claim arrives inside a token signed by
  #       the issuer, and a caller cannot produce one without the issuer's
  #       signing key. Recorded in docs/adr/ADR-008-security-and-identity.md.
  #       (2) Assumptions: this authorizer does NOT replace validation inside
  #       the services. Each service is also an OAuth2 resource server and
  #       validates the same token independently, because a caller that reaches
  #       the internal ALB directly -- another task already inside the private
  #       application subnets, for instance -- never passes through this edge
  #       and would otherwise be unauthenticated. The reason to keep both is
  #       that specific bypass path, not a general preference for layering.
  authorizer_type = "JWT"

  # WHY : Assumptions: the SPA's shared API client sets the bearer token on the
  #       `Authorization` header, so that is the only place worth reading. This
  #       is also the coupling that makes `authorization` mandatory in
  #       `var.cors_allow_headers`; naming it here saves a future reader an
  #       otherwise opaque preflight failure that never reaches this resource.
  identity_sources = ["$request.header.Authorization"]

  # WHY : (1) Assumptions: both are inputs and never literals. A hard-coded
  #       issuer URI or app-client identifier would bind this module to one
  #       user pool in one account and one region, which is the opposite of
  #       what a reusable module is for; variables.tf constrains their shape
  #       instead of their value.
  #       (2) Assumptions: the identity contract carried here is worth
  #       recording, because this resource is where it enters the system and
  #       every authority decision downstream depends on it. The baseline
  #       holds a one-character discriminator, `SEC-USR-TYPE PIC X(01)` at
  #       app/cpy/CSUSR01Y.cpy:L22, whose values are `'A'` and `'U'`. Those
  #       two values map to the Cognito groups `carddemo-admin` and
  #       `carddemo-user`; common-lib's role converter turns the
  #       `cognito:groups` claim into Spring Security authorities; and the
  #       administrative routes are guarded by that signed claim rather than by
  #       any field the client supplies.
  jwt_configuration {
    issuer   = var.cognito_issuer_uri
    audience = var.cognito_app_client_ids
  }
}

# -----------------------------------------------------------------------------
# Dedicated VPC Link security path -- exact SG-to-SG TCP 443 only.
# -----------------------------------------------------------------------------

resource "aws_security_group" "vpc_link" {
  name_prefix = "${local.name_stem}-vpc-link-"
  description = "Dedicated source security group for the CardDemo API Gateway VPC Link."
  vpc_id      = var.vpc_id

  # WHY : Refactoring Rationale: no inline ingress or egress blocks are used.
  #       Separate rule resources below make the only permitted flow explicit
  #       and avoid the provider-created allow-all egress rule a generic VPC
  #       Link group would otherwise retain.
  tags = local.tags
}

resource "aws_vpc_security_group_egress_rule" "vpc_link_to_alb_https" {
  security_group_id            = aws_security_group.vpc_link.id
  referenced_security_group_id = var.alb_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443

  # WHY : Assumptions: the VPC Link targets the ALB's sole HTTPS listener, so
  #       TCP 443 is the complete destination contract. Referencing the ALB
  #       security group instead of a CIDR admits only interfaces carrying that
  #       group and survives subnet-address changes.
  description = "Allow the API Gateway VPC Link to reach the internal ALB HTTPS listener."
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_vpc_link_https" {
  security_group_id            = var.alb_security_group_id
  referenced_security_group_id = aws_security_group.vpc_link.id
  ip_protocol                  = "tcp"
  from_port                    = 443
  to_port                      = 443

  # WHY : Refactoring Rationale: this is the destination half absent from the
  #       previous network contract. Owning both halves here is the only place
  #       the VPC Link source group and the ALB destination group are visible
  #       together without introducing a module cycle.
  description = "Allow only the dedicated API Gateway VPC Link group into the ALB HTTPS listener."
}

# -----------------------------------------------------------------------------
# The VPC Link -- the edge's only path into the private application subnets.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_vpc_link" "this" {
  name = "${local.name_stem}-vpc-link"

  # WHY : (1) Alternatives Considered: a VPC Link exists because the load
  #       balancer it fronts is INTERNAL and publishes no public listener, and
  #       a private integration across a link is the only way an HTTP API can
  #       reach one. The alternative was a public load balancer and no link at
  #       all; it is rejected because it would expose the service tier directly
  #       and let a caller address a service without passing the authorizer
  #       above -- which would make the edge optional, and an optional edge
  #       enforces nothing.
  #       (2) Assumptions: the private APPLICATION subnets, not the public ones.
  #       The topology places only the load balancer and the NAT gateways in
  #       the public subnets, the container tasks in the private application
  #       subnets, and the database in isolated subnets with no internet route
  #       at all. Putting the link in the application tier is what keeps the
  #       whole path from edge to service inside the VPC. variables.tf requires
  #       at least two entries so the link is never pinned to one availability
  #       zone.
  subnet_ids = var.private_app_subnet_ids

  # WHY : Refactoring Rationale: using a caller-supplied list left this module
  #       unable to prove that any member had the matching destination rule.
  #       Creating one group and both SG-reference rules above makes the private
  #       integration's connectivity part of this module's graph.
  security_group_ids = [aws_security_group.vpc_link.id]

  tags = local.tags
}

# -----------------------------------------------------------------------------
# The private integration -- one integration for the whole API.
# -----------------------------------------------------------------------------
# WHY : Alternatives Considered: a single integration serves every route,
#       rather than one integration per published service. The internal load
#       balancer already performs per-service dispatch through its own listener
#       rules, so an integration per service would place the same routing
#       decision in two places and let the two drift -- a path could be routed
#       at the edge to a service the load balancer sends elsewhere. Keeping
#       dispatch in exactly one place leaves the load balancer's rule set as
#       the single description of which service owns which path.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_integration" "alb" {
  api_id = aws_apigatewayv2_api.this.id

  # WHY : Assumptions: an HTTP API private integration must be of this type --
  #       it is the only integration type that reaches a load balancer listener
  #       across a VPC Link. `AWS_PROXY` targets a Lambda function or an AWS
  #       service action, and the `HTTP` and `MOCK` types are WebSocket-only,
  #       so none of the three can express this hop.
  integration_type = "HTTP_PROXY"

  # WHY : Assumptions: the route table below publishes `ANY` route keys, so the
  #       method is decided by the client and the owning service and not by the
  #       edge. Pinning one method here would quietly drop every other verb on
  #       the same path, and the route would still appear to be published.
  integration_method = "ANY"

  # WHY : Assumptions: this is the load balancer's LISTENER ARN -- not a URL and
  #       not a DNS name -- because a private integration across a VPC Link
  #       addresses a listener by ARN. This is the highest-consequence argument
  #       in the module: a URL here is accepted and produces an ordinary public
  #       proxy integration that leaves the VPC entirely and never touches the
  #       link, so the mistake costs the whole private path and produces no
  #       plan error at all. variables.tf therefore requires the value to begin
  #       with `arn:` and to contain `:listener/`.
  integration_uri = var.alb_listener_arn

  # WHY : Assumptions: `connection_type` would otherwise default to `INTERNET`,
  #       which ignores `connection_id` and sends the request out of the VPC.
  #       The two arguments have to agree for the hop to stay private, so they
  #       are written together rather than in separate places.
  connection_type = "VPC_LINK"
  connection_id   = aws_apigatewayv2_vpc_link.this.id

  # WHY : Assumptions: `1.0` is not a preference here, it is the only value this
  #       integration type accepts. A payload format version is required for
  #       HTTP APIs, and while a Lambda proxy integration may choose `1.0` or
  #       `2.0`, every other integration type -- this one included -- supports
  #       `1.0` alone. `2.0` describes the event envelope handed to a Lambda
  #       function and has no meaning for a proxy hop onto a load balancer, so
  #       it would be rejected rather than merely unhelpful. Written explicitly
  #       instead of left to the provider default so the constraint is visible
  #       at the place it applies.
  payload_format_version = "1.0"

  # WHY : Refactoring Rationale: HTTP API access logs expose
  #       `$context.requestId`; they do not expose an arbitrary request header
  #       through a `$context.request.header.*` expression. Mapping the supported
  #       context value into X-Request-Id makes the exact identity logged at the
  #       edge available to CorrelationIdFilter as its separate requestId MDC
  #       field, while the caller's X-Correlation-Id remains independent.
  # WHY : Assumptions: bounded deliberately rather than left to a default,
  #       because the bound is load-bearing for a decision taken elsewhere --
  #       no circuit breaker is adopted anywhere in this system, and the stated
  #       reason is that the only synchronous hops are in-VPC behind an
  #       internal load balancer WITH BOUNDED TIMEOUTS. Remove the bound and
  #       that reasoning stops holding, so the omitted breaker would become a
  #       gap rather than a considered choice. variables.tf keeps the value
  #       inside the range the integration accepts.
  timeout_milliseconds = var.integration_timeout_milliseconds

  # WHY : (1) Assumptions: emitted UNCONDITIONALLY, which is a deliberate
  #       departure from an earlier shape of this module in which the input was
  #       nullable and this block was `dynamic`, emitted only when a name had
  #       been supplied.
  #       (2) Refactoring Rationale: a private integration with no `tls_config`
  #       does not fall back to TLS without verification -- it sends the
  #       request over PLAIN HTTP. Under the nullable shape, any root that
  #       simply omitted the input received a cleartext hop carrying bearer
  #       tokens and account, customer and transaction data across the VPC
  #       Link, with nothing in the plan to indicate it. variables.tf closed
  #       that path by making the input required and non-nullable, so a
  #       conditional here would now be a branch that is always taken while
  #       still implying a supported plaintext mode exists. An unconditional
  #       block states that there is none.
  #       (3) Assumptions: TLS on both sides, stated plainly so it can be
  #       checked. The client-facing `execute-api` endpoint serves HTTPS only,
  #       and this leg targets the load balancer's HTTPS listener with the
  #       server identity verified, which assumes the `alb` module configures
  #       that listener for HTTPS as its own documentation states. There is no
  #       plaintext hop on either side of the edge.
  tls_config {
    server_name_to_verify = var.integration_tls_server_name
  }

  # WHY : (1) Assumptions: the access log below records `$context.requestId` and
  #       nothing else that identifies the request, because that is the identity this
  #       API is documented to expose. Without this header the service never sees that
  #       value: the shared correlation filter would mint an identity the edge has
  #       never seen, so the edge line and the service lines for that request would
  #       carry two unrelated values and could not be joined at all -- and a request
  #       with no client-supplied header is the ordinary case, not the exception.
  #       Stamping it here gives the service the same value twice over: it is logged
  #       in its own `requestId` field, and it is what the correlation identity falls
  #       back to when the caller supplied none.
  #       (2) Refactoring Rationale: the obvious shape was to overwrite
  #       `x-correlation-id` itself with `$context.requestId`. That was rejected
  #       because the filter's contract is to echo a client-supplied identifier
  #       VERBATIM -- overwriting would destroy the value the caller correlates on,
  #       and appending would hand the filter a multi-valued header it would have to
  #       disambiguate. A second, dedicated header leaves the client's value
  #       untouched and gives the filter a fallback to adopt when there is none, so
  #       both cases join on this one value: the service logs it in its own
  #       `requestId` field on every request, and additionally adopts it as the
  #       correlation identity on the requests where the caller supplied none.
  #       (3) Assumptions: `overwrite` rather than `append`, so a caller cannot
  #       supply this header itself and have its value survive alongside the edge's.
  #       The identity in the service log has to be one the edge actually issued,
  #       otherwise a caller could make two unrelated requests appear as one.
  #       (4) Trade-offs: the header name is a literal shared with
  #       com.carddemo.common.web.CorrelationIdFilter, which reads it, and the two
  #       have to agree by convention because they sit in different trees with no
  #       build-time bond between them. The alternative was a module input, rejected
  #       because it would let the two drift by configuration and the symptom would
  #       be silent -- a fallback that never fires and a join that quietly stops
  #       working. A shared literal named on both sides fails visibly instead.
  request_parameters = {
    "overwrite:header.x-request-id" = "$context.requestId"
  }
}

# -----------------------------------------------------------------------------
# The routes -- the published surface, and the single documented exception.
# -----------------------------------------------------------------------------


resource "aws_apigatewayv2_route" "service" {
  # WHY : (1) Alternatives Considered: `for_each` over a variable rather than
  #       `count` over a list. `for_each` keys instance state by the route key
  #       itself, so adding or reordering an entry leaves every other route
  #       untouched. With `count` the instances are keyed by list POSITION, so
  #       inserting a key near the front shifts every later index and Terraform
  #       destroys and recreates each shifted route -- an avoidable
  #       interruption on the public edge for what was meant to be an addition.
  #       (2) Alternatives Considered: a static route resource per service,
  #       rejected because a route table that is DATA lets an environment root
  #       publish a further bounded context by changing a value, with no edit
  #       to this module or to the fifteen beside it.
  #       (3) Trade-offs: no `$default` catch-all route is defined. An explicit
  #       per-service table is a readable inventory of exactly what this edge
  #       exposes, whereas a catch-all would accept any path and forward it, so
  #       the edge's exposure would no longer be visible by reading it. The
  #       cost accepted is that publishing a new path requires adding a key.
  for_each = toset(var.route_keys)

  api_id    = aws_apigatewayv2_api.this.id
  route_key = each.value
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"

  # WHY : (1) Trade-offs: set unconditionally on this resource rather than through
  #       a per-key lookup, so no key in `var.route_keys` can reach the edge
  #       without an authorizer. A map of key to authorization type was the
  #       alternative and was rejected: it would put the authenticated-versus-not
  #       decision into a value an environment root supplies, where a typo silently
  #       publishes an open route. Here the only way to publish an unauthenticated
  #       route is to name it in `var.public_route_keys`, which is validated down to
  #       one exact method and path and is served by the separate resource below.
  #       (2) Assumptions: the policy check on this directory requires that no route
  #       is unauthenticated WITHOUT a documented reason -- not that no such route
  #       exists. Every route here is authenticated by construction; the one that is
  #       not is the sign-on route, which is deliberately public because a caller has
  #       no token before it succeeds, and its reason is recorded on the resource
  #       that declares it rather than in a suppression comment.
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id

  # WHY : Assumptions: a postcondition rather than a `check` block, because the
  #       two differ in consequence and only one of them is a gate. A failed
  #       `check` assertion is reported as a WARNING and the apply proceeds; a
  #       failed `postcondition` fails the plan. An assertion that the public
  #       edge is authorized must not be advisory, so the erroring form is used.
  #       `self` reads the resource's own resolved attribute rather than the
  #       literal above it, which is what makes this a check on the route as
  #       CONFIGURED instead of a restatement of the assignment.
  lifecycle {
    postcondition {
      condition     = self.authorization_type == "JWT" && self.authorizer_id != null
      error_message = "Every route built from var.route_keys must carry the JWT authorizer. Route \"${self.route_key}\" resolved to authorization_type \"${self.authorization_type}\". A deliberately public route belongs in var.public_route_keys, which is validated to admit token-issuance keys only; it must never be reached by removing the authorizer from this resource."
    }
  }

  # WHY : Assumptions: a required scope is what distinguishes an ACCESS token
  #       from an IDENTITY token. A Cognito user pool mints both, and both are
  #       signed by the same issuer for the same audience, so the issuer and
  #       audience checks on the authorizer accept either one. Only the access
  #       token carries a `scope` claim; an identity token has none. Requiring
  #       a scope therefore rejects the identity token, which describes who the
  #       user is and is not an authorization credential, where the issuer and
  #       audience checks alone would let it straight through.
  authorization_scopes = var.route_authorization_scopes
}

resource "aws_apigatewayv2_route" "public" {
  #checkov:skip=CKV_AWS_309:Only the exact pre-token POST operations are public, because a caller cannot present the JWT these operations exist to issue or renew; var.public_route_keys is validated down to that closed set.
  # WHY : (1) Assumptions: sign-on is the route that MINTS the token every other
  #       route requires, so attaching the JWT authorizer to it would deadlock the
  #       whole surface -- a caller could never obtain a credential, because
  #       obtaining one would itself require presenting one. This matches the
  #       sign-on program's position in the baseline: it is the entry transaction,
  #       reached with no prior identity, and it is what establishes the identity
  #       every later screen carries.
  #       (2) Alternatives Considered: having the browser authenticate directly
  #       against the user pool so that no sign-on route exists at all. Rejected
  #       because the app client this edge's authorizer validates against is
  #       confidential -- it holds a client secret -- so the exchange has to happen
  #       server-side in a component that can hold that secret, which means an
  #       unauthenticated route has to exist to reach it.
  #       (3) Alternatives Considered: keeping sign-on in the resource above and
  #       selecting the authorizer per key. Rejected because a conditional
  #       authorizer turns "is this route open?" into a question about an
  #       expression rather than about which resource declares the route. Two
  #       resources make the answer readable at a glance: the set above is
  #       authenticated, this one is not, and this one is held by validation to
  #       exactly one method on exactly one path.
  #       (4) Trade-offs: an open route is an unauthenticated surface, so it is
  #       throttled independently on the stage below instead of sharing the
  #       account-level allowance the authenticated routes sit on. The cost
  #       accepted is that a burst of sign-on attempts is rejected at the edge
  #       before it reaches the service, which is the intended behaviour for the
  #       one route an unauthenticated caller can reach.
  #       (5) Assumptions: an empty `var.public_route_keys` produces no instance of
  #       this resource, so an environment fronting a pool whose app client is
  #       public -- where the browser can perform the exchange itself -- publishes
  #       no open route at all, with no edit to this module.
  for_each = toset(var.public_route_keys)

  api_id    = aws_apigatewayv2_api.this.id
  route_key = each.value
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"

  # WHY : Trade-offs: `"NONE"` is written out even though it is the argument's own
  #       default. Stating it makes the single deliberately open route greppable --
  #       a reader scanning for `authorization_type` finds both the JWT set and
  #       this exception -- where an omission would read as something forgotten.
  #       The cost is one redundant line, which is the cheaper half of the trade
  #       against an open route that looks accidental.
  authorization_type = "NONE"
}

# -----------------------------------------------------------------------------
# The access log group -- destination for the stage's request log.
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "access" {
  # WHY : Trade-offs: this module owns its own log group rather than receiving
  #       one from the observability module. The group's lifetime is the API's
  #       lifetime, so `terraform destroy` removes the two together and leaves
  #       no orphaned group behind, which is what keeps the teardown clean. The
  #       cost accepted is that the group is not declared alongside the
  #       environment's other log groups; the shared vendedlogs prefix
  #       composed in `locals` keeps it findable regardless.
  name = local.access_log_group_name

  # WHY : Assumptions: retention is an input because log retention is one of the
  #       narrow set of levers on which the dev and prod environments are meant
  #       to differ -- alongside capacity, task count and distribution price
  #       class -- while the topology itself stays identical between them. A
  #       literal here would erase that distinction and make the two
  #       environments differ by code instead of by value.
  retention_in_days = var.log_retention_days

  # WHY : (1) Alternatives Considered: requiring a customer-managed key
  #       unconditionally, rejected because it would make the module unusable in
  #       an environment that has not provisioned one; the input is nullable
  #       instead, and a null resolves to the CloudWatch Logs service-managed
  #       key, which still encrypts the data at rest.
  #       (2) Trade-offs: when a key IS supplied, the environment gains the
  #       per-domain containment the key layout is designed for -- one key per
  #       data domain, so access to one does not read the others. Permitting null
  #       means an environment can run without that containment, which is why the
  #       choice is the caller's and is visible in its tfvars rather than decided
  #       silently here -- but only for dev, per the precondition below.
  kms_key_id = var.access_log_kms_key_arn

  # WHY : Refactoring Rationale: the null default was previously defended on the
  #       grounds that "the environment roots pass the key explicitly", and that
  #       was not true -- infra/envs/dev and infra/envs/prod contain only
  #       variables.tf and versions.tf, so no root passes this or any other
  #       value, and nothing anywhere required a customer-managed key for this
  #       group. The claim was therefore load-bearing and unfounded at once: it
  #       was the sole reason given for accepting a silent fallback. Rather than
  #       restate the gap in prose, the requirement is asserted here, so that the
  #       environment in which it matters cannot be applied without the key and
  #       the environment in which it does not stays usable without one.
  # WHY : Assumptions: this group is not an arbitrary log destination, which is
  #       why it is worth a hard gate rather than a documented preference. It is
  #       the one log in the system that records every request crossing the
  #       internet boundary, and it retains `sourceIp` -- a caller's address,
  #       which is personal data and which the access-log rationale above keeps
  #       deliberately. A customer-managed key is what makes reading that history
  #       an auditable, separately-revocable grant (kms:Decrypt on one key)
  #       instead of an ambient consequence of holding CloudWatch Logs read
  #       access.
  #       Trade-offs: keyed on `var.environment` rather than on a new boolean
  #       input. A boolean would be a second thing to set correctly and could be
  #       set to false in prod, which is the case it exists to prevent; deriving
  #       the requirement from the environment name means prod cannot opt out.
  #       The cost is that an environment named something other than "prod" is
  #       not covered -- accepted because variables.tf constrains
  #       `var.environment` to the two names this migration provisions.
  lifecycle {
    precondition {
      condition     = var.environment != "prod" || var.access_log_kms_key_arn != null
      error_message = "The prod environment must encrypt this API's access-log group with a customer-managed KMS key: pass the CloudWatch Logs key produced by infra/modules/kms as access_log_kms_key_arn. This group records every request crossing the internet boundary and retains the caller's source IP, so a customer-managed key is what makes reading that history a separately-revocable kms:Decrypt grant rather than an ambient consequence of CloudWatch Logs read access. Dev may leave it null and fall back to the service-managed key."
    }
  }

  tags = local.tags
}

# -----------------------------------------------------------------------------
# The stage -- binds deployment, access logging and throttling to the API.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_stage" "this" {
  api_id = aws_apigatewayv2_api.this.id

  # WHY : Assumptions: the reasoning behind the stage name is recorded with the
  #       input in variables.tf -- the default `$default` stage contributes no
  #       path segment, so the SPA's base URL and each service's OpenAPI paths
  #       line up without a rewrite anywhere. It is referenced rather than
  #       repeated so the two cannot drift apart.
  name = var.stage_name

  # WHY : Alternatives Considered: an explicit deployment resource. Rejected
  #       because it would have to be replaced on every route, integration or
  #       authorizer change for that change to become live, so `terraform
  #       apply` on its own would leave the deployed API stale and the operator
  #       sequence the infrastructure README documents -- `init`, then `plan
  #       -out=tfplan`, then `apply tfplan` -- would appear to succeed while
  #       changing nothing a caller can reach. Auto-deploy keeps the applied
  #       state and the live state the same thing.
  auto_deploy = true

  # WHY : Assumptions: enabled unconditionally. The field set and the deliberate
  #       exclusions are justified where the format is composed in `locals`
  #       above and are not repeated here. Logging is not optional at this
  #       resource because an edge with no access log leaves no record of which
  #       caller reached which route, so a request that fails somewhere between
  #       the browser and the service cannot even be placed on one side of the
  #       edge or the other.
  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.access.arn
    format          = local.access_log_format
  }

  # WHY : (1) Assumptions: an unthrottled public front door lets a single client
  #       consume the whole service tier's capacity, which is why an explicit
  #       bound exists here rather than relying on the account-wide default
  #       that a different account would not share.
  #       (2) Trade-offs: the limits are set as STAGE DEFAULTS rather than
  #       enumerated per route, so they apply to every key in `var.route_keys`,
  #       including keys added later. Enumerating per route would let a newly
  #       published route arrive with no limit at all. The cost accepted is that
  #       tightening one route takes an explicit override, and exactly one exists
  #       -- the public sign-on route below, which is the only route an anonymous
  #       caller can reach and therefore the only one whose budget cannot be
  #       attributed to a credential. Both limits are inputs because they are
  #       dev/prod levers.
  #       (3) Trade-offs: `detailed_metrics_enabled` follows its input, which
  #       defaults on, because metrics are a stated cross-cutting requirement
  #       alongside centralized logging and tracing, and per-route metrics are
  #       what let one slow service be identified without reading logs first.
  #       The accepted cost is the per-route metric charge, which is precisely
  #       why it stays an input a dev environment can switch off.
  default_route_settings {
    throttling_burst_limit   = var.throttling_burst_limit
    throttling_rate_limit    = var.throttling_rate_limit
    detailed_metrics_enabled = var.detailed_metrics_enabled
  }

  # WHY : (1) Assumptions: the defaults above are sized for callers that have
  #       already presented a token, so behind every request there is an account an
  #       operator can identify and act on. The pre-token routes have no such unit
  #       -- every caller is anonymous until sign-on succeeds -- so leaving them on
  #       the same allowance would let anonymous traffic consume the whole edge
  #       budget and starve the callers that did authenticate.
  #       (2) Trade-offs: the two values are inputs held by validation to be no
  #       larger than the stage defaults, so this block can only ever TIGHTEN. A
  #       literal pair was the alternative and was rejected because burst tolerance
  #       is one of the levers dev and prod are meant to differ on; an unbounded
  #       override was rejected because it would let the open routes become the
  #       most permissive on the edge. Lowering `default_route_settings` for every
  #       route instead was also rejected: it would throttle authenticated list and
  #       paging traffic -- the browse screens issue a request per page turn -- to a
  #       bound chosen for credential attempts.
  #       (3) Alternatives Considered: counting sign-on attempts in the service
  #       instead. Rejected because the request would already have crossed the
  #       edge, the load balancer and a task's connection pool before being
  #       counted, so the cost of a rejected attempt would be paid by exactly the
  #       tier the limit exists to protect.
  #       (4) Assumptions: `dynamic` rather than a static block, because
  #       `var.public_route_keys` may legitimately be empty -- an environment that
  #       publishes no open route gets no override, and a static block would
  #       instead pin a limit to a route key that does not exist. Following the
  #       same input the routes do also keeps the override set and the public route
  #       set from drifting apart.
  #       (5) Trade-offs: `detailed_metrics_enabled` is set unconditionally to
  #       `true` here rather than following `var.detailed_metrics_enabled` as the
  #       default block does. A dev environment switching per-route metrics off to
  #       avoid their charge is reasonable for the authorized routes; doing so on an
  #       unauthenticated one would remove the only per-route signal that shows a
  #       spike in anonymous sign-on attempts. The accepted cost is the metric
  #       charge for these routes in every environment.
  dynamic "route_settings" {
    for_each = toset(var.public_route_keys)

    content {
      route_key                = route_settings.value
      throttling_burst_limit   = var.public_route_throttling_burst_limit
      throttling_rate_limit    = var.public_route_throttling_rate_limit
      detailed_metrics_enabled = true
    }
  }

  tags = local.tags
}
