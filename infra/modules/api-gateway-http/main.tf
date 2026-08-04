# =============================================================================
# infra/modules/api-gateway-http/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The public edge of the migrated CardDemo system. This file provisions the
#   whole entry point as seven resources that only make sense together: an API
#   Gateway HTTP API carrying the cross-origin contract for the
#   CloudFront-hosted SPA; a Cognito JWT authorizer that validates the
#   caller's token before a request leaves the edge; a VPC Link into the
#   private application subnets; one private proxy integration onto the
#   internal ALB's listener; one authorized route per published path; a
#   CloudWatch log group; and the stage that binds access logging and
#   throttling to all of it.
#
#   The request path, end to end: the browser SPA calls the API's
#   `execute-api` endpoint over HTTPS; the JWT authorizer validates the bearer
#   token against the Cognito issuer, audience and required scope; the matched
#   route forwards to the single integration; the integration crosses the VPC
#   Link into the private application subnets and reaches the internal ALB
#   listener over TLS; the ALB's per-service rules dispatch to the owning
#   service, which validates the same token again independently.
#
#   Seven of the eight migrated services are published here -- auth, accounts,
#   cards, transactions, reference, authorizations and reports. The eighth,
#   batch-service, is reached only by the Step Functions synchronous run-task
#   call and is deliberately absent from the route table: `var.route_keys`
#   carries a validation that rejects any `/batch` route outright.
#
# Parameters:
#   Not applicable -- this file declares no `variable` block. Every value it
#   consumes, including the ALB listener target, the VPC Link's subnets and
#   security groups, the Cognito issuer and audience, the cross-origin
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
#   - a `stage_name` or a route key that collides with one already deployed
#     fails during `apply`, after the API itself has been created.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: this module calls no sibling module and reads no
#     `data` source that looks up another module's resources. Every
#     cross-module value arrives as a variable. Resolving the ALB listener, the
#     subnets or the user pool by lookup instead would couple this folder to
#     another module's internal resource names and to the account it runs in,
#     so a rename next door would break this module, and it could not be
#     planned at all without live credentials.
#   - Trade-off: because a module has no `provider` block it also has no
#     provider `default_tags`, so tagging cannot be inherited; `var.tags` is
#     the only tag channel available and is merged onto each taggable resource
#     individually. Only four of the seven resources below take tags -- the
#     API, the VPC Link, the log group and the stage. The authorizer, the
#     integration and the route expose no `tags` argument at all, so their
#     omission is the provider's shape and not an oversight here.
#   - Assumption: the policy checks on this directory -- an authorizer on every
#     route, access logging enabled, throttling configured, and TLS on both
#     sides of the edge -- are satisfied structurally rather than by
#     suppression. This file carries no policy-scan skip annotation, no lint
#     ignore annotation and no equivalent of either, and needs none: each
#     property the checks look for is an unconditional argument on a resource
#     below, so a future edit cannot drop one without deleting a visible line.
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
  # WHAT: the single naming stem every resource in this module derives from.
  # WHY : Trade-off: composed once here instead of being repeated per
  #       resource, so a rename is one edit and all seven resources are
  #       provably named consistently. `apigw-http` is the module
  #       discriminator, which keeps this module's resources distinguishable
  #       in a console listing from the fifteen other modules deployed into
  #       the same environment. The cost accepted is one indirection between
  #       reading a resource and knowing its literal name.
  name_stem = "${var.name_prefix}-apigw-http-${var.environment}"

  # WHAT: the tag map applied to each taggable resource below.
  # WHY : (1) Assumption: a module has no `provider` block, so there is no
  #       provider `default_tags` to inherit from and `var.tags` is the only
  #       channel by which a caller's tags can reach these resources.
  #       (2) Trade-off: `var.tags` is the FIRST argument to `merge`, so on a
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

  # WHAT: the CloudWatch Logs group name the stage writes access logs to.
  # WHY : Assumption: the `/aws/vendedlogs/` prefix is load-bearing rather
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

  # WHAT: the structured access-log line the stage emits per request.
  # WHY : (1) Assumption: `correlationId` reads the `x-correlation-id` REQUEST
  #       header, the same header common-lib's correlation filter reads on the
  #       way in and echoes on the way out. Including it is the only thing
  #       that makes an edge log line joinable to the service log lines for
  #       the same request; without it the edge and the services produce two
  #       unrelated logs and a cross-boundary failure cannot be followed. It
  #       is also why `x-correlation-id` belongs in `var.cors_allow_headers`:
  #       a browser strips a header the preflight did not allow.
  #       (2) Trade-off: the field set is bounded to routing and timing facts
  #       -- request identity and time, route key, method, path, protocol,
  #       status, response length, the integration's own status and latency,
  #       and the total response latency. `integrationLatency` beside
  #       `responseLatency` is what separates a slow service from a slow edge;
  #       either one alone cannot. The cost is that a failure needing request
  #       content to diagnose must be reproduced rather than read back, which
  #       is accepted for the reason in (3).
  #       (3) Trade-off: NOTHING that identifies a person or authorises a
  #       caller is logged -- no `Authorization` header, no bearer token, no
  #       JWT claim, and no request or response body. The alternative was to
  #       log the authorizer claims and the request body, which is a common
  #       choice and would make a failed request diagnosable from the log
  #       alone; it is rejected here because the services mask primary account
  #       numbers to their last four digits and never return card verification
  #       values, and a log line carrying a body or a credential would defeat
  #       both of those controls at the one point every single request passes
  #       through -- turning the access log into the least protected copy of
  #       the data the services work hardest to protect. The authorizer and
  #       body context variables are available and are deliberately not
  #       referenced. This is an omission a reader cannot see, so it is stated
  #       rather than left to be inferred from the field list above.
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
    path               = "$context.path"
    protocol           = "$context.protocol"
    status             = "$context.status"
    responseLength     = "$context.responseLength"
    responseLatency    = "$context.responseLatency"
    integrationStatus  = "$context.integration.status"
    integrationLatency = "$context.integration.latency"
    integrationError   = "$context.integrationErrorMessage"
    sourceIp           = "$context.identity.sourceIp"
    correlationId      = "$context.request.header.x-correlation-id"
  })
}

# -----------------------------------------------------------------------------
# The HTTP API -- the front door itself.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_api" "this" {
  name        = local.name_stem
  description = "CardDemo edge: JWT-authorized HTTP API onto the internal ALB."

  # WHAT: selects API Gateway's v2 HTTP API rather than a v1 REST API.
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

  # WHAT: `disable_execute_api_endpoint` is intentionally NOT set here, so the
  #       generated `execute-api` endpoint stays reachable.
  # WHY : Assumption: disabling that endpoint is a reflex hardening step that
  #       would be wrong in this module. No custom domain is provisioned here,
  #       so the `execute-api` endpoint IS the public entry point, and
  #       switching it off would leave the API with no way in at all. Stated
  #       because the absence of the argument would otherwise read as an
  #       oversight to anyone applying that habit.

  # WHAT: the browser cross-origin contract for the CloudFront-hosted SPA.
  # WHY : (1) Trade-off: declared once at the edge instead of implemented in
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
  #       (3) Assumption: `authorization` is required to be present in
  #       `var.cors_allow_headers`, and variables.tf validates that it is,
  #       because the authorizer below reads the bearer token from exactly that
  #       header -- a preflight omitting it fails in the browser only, leaving
  #       the API itself looking healthy.
  cors_configuration {
    allow_origins = var.spa_cors_allow_origins
    allow_methods = var.cors_allow_methods
    allow_headers = var.cors_allow_headers
    max_age       = var.cors_max_age_seconds
  }

  tags = local.tags
}

# -----------------------------------------------------------------------------
# The JWT authorizer -- token validation before a request leaves the edge.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_authorizer" "jwt" {
  api_id = aws_apigatewayv2_api.this.id
  name   = "${local.name_stem}-cognito-jwt"

  # WHAT: uses API Gateway's built-in JWT validation.
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
  #       (2) Assumption: this authorizer does NOT replace validation inside
  #       the services. Each service is also an OAuth2 resource server and
  #       validates the same token independently, because a caller that reaches
  #       the internal ALB directly -- another task already inside the private
  #       application subnets, for instance -- never passes through this edge
  #       and would otherwise be unauthenticated. The reason to keep both is
  #       that specific bypass path, not a general preference for layering.
  authorizer_type = "JWT"

  # WHAT: where in the request the token is read from.
  # WHY : Assumption: the SPA's shared API client sets the bearer token on the
  #       `Authorization` header, so that is the only place worth reading. This
  #       is also the coupling that makes `authorization` mandatory in
  #       `var.cors_allow_headers`; naming it here saves a future reader an
  #       otherwise opaque preflight failure that never reaches this resource.
  identity_sources = ["$request.header.Authorization"]

  # WHAT: which issuer's tokens are accepted, and for which audience.
  # WHY : (1) Assumption: both are inputs and never literals. A hard-coded
  #       issuer URI or app-client identifier would bind this module to one
  #       user pool in one account and one region, which is the opposite of
  #       what a reusable module is for; variables.tf constrains their shape
  #       instead of their value.
  #       (2) Assumption: the identity contract carried here is worth
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
# The VPC Link -- the edge's only path into the private application subnets.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_vpc_link" "this" {
  name = "${local.name_stem}-vpc-link"

  # WHAT: the subnets the link's network interfaces are placed in.
  # WHY : (1) Alternatives Considered: a VPC Link exists because the load
  #       balancer it fronts is INTERNAL and publishes no public listener, and
  #       a private integration across a link is the only way an HTTP API can
  #       reach one. The alternative was a public load balancer and no link at
  #       all; it is rejected because it would expose the service tier directly
  #       and let a caller address a service without passing the authorizer
  #       above -- which would make the edge optional, and an optional edge
  #       enforces nothing.
  #       (2) Assumption: the private APPLICATION subnets, not the public ones.
  #       The topology places only the load balancer and the NAT gateways in
  #       the public subnets, the container tasks in the private application
  #       subnets, and the database in isolated subnets with no internet route
  #       at all. Putting the link in the application tier is what keeps the
  #       whole path from edge to service inside the VPC. variables.tf requires
  #       at least two entries so the link is never pinned to one availability
  #       zone.
  subnet_ids = var.private_app_subnet_ids

  # WHAT: the security group governing the link's network interfaces.
  # WHY : Trade-off: supplied by the caller rather than created here. The
  #       network module owns the complete rule set for this VPC -- exactly
  #       three permitted flows: load balancer to application on 8080,
  #       application to database on 5432, and application to VPC endpoint on
  #       443 -- and creating a second group in this module would split
  #       ownership of that set across two folders, so a rule change would have
  #       to be made twice and could be made inconsistently. The cost accepted
  #       is that this module cannot be applied until the network module has
  #       produced the group.
  security_group_ids = var.vpc_link_security_group_ids

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

  # WHAT: a proxy integration that forwards the client request unmodified.
  # WHY : Assumption: an HTTP API private integration must be of this type --
  #       it is the only integration type that reaches a load balancer listener
  #       across a VPC Link. `AWS_PROXY` targets a Lambda function or an AWS
  #       service action, and the `HTTP` and `MOCK` types are WebSocket-only,
  #       so none of the three can express this hop.
  integration_type = "HTTP_PROXY"

  # WHAT: forwards whatever method the client used.
  # WHY : Assumption: the route table below publishes `ANY` route keys, so the
  #       method is decided by the client and the owning service and not by the
  #       edge. Pinning one method here would quietly drop every other verb on
  #       the same path, and the route would still appear to be published.
  integration_method = "ANY"

  # WHAT: the target of the private integration.
  # WHY : Assumption: this is the load balancer's LISTENER ARN -- not a URL and
  #       not a DNS name -- because a private integration across a VPC Link
  #       addresses a listener by ARN. This is the highest-consequence argument
  #       in the module: a URL here is accepted and produces an ordinary public
  #       proxy integration that leaves the VPC entirely and never touches the
  #       link, so the mistake costs the whole private path and produces no
  #       plan error at all. variables.tf therefore requires the value to begin
  #       with `arn:` and to contain `:listener/`.
  integration_uri = var.alb_listener_arn

  # WHAT: routes the request through the VPC Link created above.
  # WHY : Assumption: `connection_type` would otherwise default to `INTERNET`,
  #       which ignores `connection_id` and sends the request out of the VPC.
  #       The two arguments have to agree for the hop to stay private, so they
  #       are written together rather than in separate places.
  connection_type = "VPC_LINK"
  connection_id   = aws_apigatewayv2_vpc_link.this.id

  # WHAT: the payload format API Gateway uses toward the integration.
  # WHY : Assumption: `1.0` is not a preference here, it is the only value this
  #       integration type accepts. A payload format version is required for
  #       HTTP APIs, and while a Lambda proxy integration may choose `1.0` or
  #       `2.0`, every other integration type -- this one included -- supports
  #       `1.0` alone. `2.0` describes the event envelope handed to a Lambda
  #       function and has no meaning for a proxy hop onto a load balancer, so
  #       it would be rejected rather than merely unhelpful. Written explicitly
  #       instead of left to the provider default so the constraint is visible
  #       at the place it applies.
  payload_format_version = "1.0"

  # WHAT: the upper bound on how long the edge waits for the service.
  # WHY : Assumption: bounded deliberately rather than left to a default,
  #       because the bound is load-bearing for a decision taken elsewhere --
  #       no circuit breaker is adopted anywhere in this system, and the stated
  #       reason is that the only synchronous hops are in-VPC behind an
  #       internal load balancer WITH BOUNDED TIMEOUTS. Remove the bound and
  #       that reasoning stops holding, so the omitted breaker would become a
  #       gap rather than a considered choice. variables.tf keeps the value
  #       inside the range the integration accepts.
  timeout_milliseconds = var.integration_timeout_milliseconds

  # WHAT: verifies the load balancer listener's certificate against this name.
  # WHY : (1) Assumption: emitted UNCONDITIONALLY, which is a deliberate
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
  #       (3) Assumption: TLS on both sides, stated plainly so it can be
  #       checked. The client-facing `execute-api` endpoint serves HTTPS only,
  #       and this leg targets the load balancer's HTTPS listener with the
  #       server identity verified, which assumes the `alb` module configures
  #       that listener for HTTPS as its own documentation states. There is no
  #       plaintext hop on either side of the edge.
  tls_config {
    server_name_to_verify = var.integration_tls_server_name
  }
}

# -----------------------------------------------------------------------------
# The routes -- the published surface, every one of them authorized.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_route" "service" {
  # WHAT: one route instance per published route key.
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
  #       (3) Trade-off: no `$default` catch-all route is defined. An explicit
  #       per-service table is a readable inventory of exactly what this edge
  #       exposes, whereas a catch-all would accept any path and forward it, so
  #       the edge's exposure would no longer be visible by reading it. The
  #       cost accepted is that publishing a new path requires adding a key.
  for_each = toset(var.route_keys)

  api_id    = aws_apigatewayv2_api.this.id
  route_key = each.value
  target    = "integrations/${aws_apigatewayv2_integration.alb.id}"

  # WHAT: attaches the JWT authorizer to this route.
  # WHY : Trade-off: set on the single `for_each`ed resource, which is what
  #       makes an unauthenticated route structurally impossible -- there is no
  #       second route resource, so every key in `var.route_keys` receives
  #       these arguments, and publishing an unauthenticated route would mean
  #       editing this block to remove them. That is the intent: the policy
  #       check on this directory fails on any route without an authorizer, and
  #       it is satisfied here by construction rather than by a suppression
  #       comment. The cost accepted is that this module cannot express a
  #       deliberately public route at all; nothing in this system needs one.
  authorization_type = "JWT"
  authorizer_id      = aws_apigatewayv2_authorizer.jwt.id

  # WHAT: the scope a token must carry to be accepted on this route.
  # WHY : Assumption: a required scope is what distinguishes an ACCESS token
  #       from an IDENTITY token. A Cognito user pool mints both, and both are
  #       signed by the same issuer for the same audience, so the issuer and
  #       audience checks on the authorizer accept either one. Only the access
  #       token carries a `scope` claim; an identity token has none. Requiring
  #       a scope therefore rejects the identity token, which describes who the
  #       user is and is not an authorization credential, where the issuer and
  #       audience checks alone would let it straight through.
  authorization_scopes = var.route_authorization_scopes
}

# -----------------------------------------------------------------------------
# The access log group -- destination for the stage's request log.
# -----------------------------------------------------------------------------

resource "aws_cloudwatch_log_group" "access" {
  # WHAT: the access-log destination consumed by the stage below.
  # WHY : Trade-off: this module owns its own log group rather than receiving
  #       one from the observability module. The group's lifetime is the API's
  #       lifetime, so `terraform destroy` removes the two together and leaves
  #       no orphaned group behind, which is what keeps the teardown clean. The
  #       cost accepted is that the group is not declared alongside the
  #       environment's other log groups; the shared vendedlogs prefix
  #       composed in `locals` keeps it findable regardless.
  name = local.access_log_group_name

  # WHY : Assumption: retention is an input because log retention is one of the
  #       narrow set of levers on which the dev and prod environments are meant
  #       to differ -- alongside capacity, task count and distribution price
  #       class -- while the topology itself stays identical between them. A
  #       literal here would erase that distinction and make the two
  #       environments differ by code instead of by value.
  retention_in_days = var.log_retention_days

  # WHY : (1) Alternatives Considered: requiring a customer-managed key,
  #       rejected because it would make the module unusable in an environment
  #       that has not provisioned one; the input is nullable instead, and a
  #       null resolves to the service-managed key.
  #       (2) Trade-off: when a key IS supplied, the environment gains the
  #       per-domain containment the key layout is designed for -- one key per
  #       data domain, so access to one does not read the others. The cost of
  #       permitting null is that an environment can run without that
  #       containment, which is exactly why the choice belongs to the caller
  #       and is visible in its tfvars rather than decided silently here.
  kms_key_id = var.access_log_kms_key_arn

  tags = local.tags
}

# -----------------------------------------------------------------------------
# The stage -- binds deployment, access logging and throttling to the API.
# -----------------------------------------------------------------------------

resource "aws_apigatewayv2_stage" "this" {
  api_id = aws_apigatewayv2_api.this.id

  # WHY : Assumption: the reasoning behind the stage name is recorded with the
  #       input in variables.tf -- the default `$default` stage contributes no
  #       path segment, so the SPA's base URL and each service's OpenAPI paths
  #       line up without a rewrite anywhere. It is referenced rather than
  #       repeated so the two cannot drift apart.
  name = var.stage_name

  # WHAT: publishes each change as soon as it is applied.
  # WHY : Alternatives Considered: an explicit deployment resource. Rejected
  #       because it would have to be replaced on every route, integration or
  #       authorizer change for that change to become live, so `terraform
  #       apply` on its own would leave the deployed API stale and the operator
  #       sequence the infrastructure README documents -- `init`, then `plan
  #       -out=tfplan`, then `apply tfplan` -- would appear to succeed while
  #       changing nothing a caller can reach. Auto-deploy keeps the applied
  #       state and the live state the same thing.
  auto_deploy = true

  # WHAT: writes one structured log line per request.
  # WHY : Assumption: enabled unconditionally. The field set and the deliberate
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

  # WHAT: the per-route defaults applied to every route on this stage.
  # WHY : (1) Assumption: an unthrottled public front door lets a single client
  #       consume the whole service tier's capacity, which is why an explicit
  #       bound exists here rather than relying on the account-wide default
  #       that a different account would not share.
  #       (2) Trade-off: the limits are set in `default_route_settings` rather
  #       than per route, so they apply to every key in `var.route_keys`,
  #       including keys added later. Per-route settings would let a newly
  #       published route arrive with no limit at all. The cost accepted is
  #       that no individual route can be given its own budget; nothing here
  #       needs one. Both limits are inputs because they are dev/prod levers.
  #       (3) Trade-off: `detailed_metrics_enabled` follows its input, which
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

  tags = local.tags
}
