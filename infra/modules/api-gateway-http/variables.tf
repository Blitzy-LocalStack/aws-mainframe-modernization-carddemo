# =============================================================================
# infra/modules/api-gateway-http/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE input surface of the reusable `api-gateway-http` Terraform
#   module -- the single public entry point for the eight migrated CardDemo
#   services: an API Gateway HTTP API with a Cognito JWT authorizer, reaching
#   the internal ALB over a VPC Link so the private application subnets are
#   never exposed. Every value this module needs from a sibling module arrives
#   HERE, as a variable the calling environment root supplies; the module
#   itself calls no sibling module and reads no data source. The resources
#   these inputs configure are in main.tf, and the toolchain and provider
#   contract is in versions.tf.
#
# Parameters:
#   Twenty-one inputs, grouped below in the order a reader needs them --
#   naming and tagging, identity, private integration and VPC Link, routing,
#   CORS, observability and rate limiting, then integration tuning and stage.
#   Each carries an explicit `type` and a `description` stating what the value
#   is, which module produces it, and what it controls. Those descriptions are
#   this module's public documentation: infra/.terraform-docs.yml renders them
#   into the Inputs table of this module's README.md, and TFLint's
#   terraform_typed_variables and terraform_documented_variables rules make
#   both attributes mandatory rather than conventional.
#
# Return values:
#   Not applicable -- this file declares no `output` block. The values this
#   module exports to its caller, the invoke endpoint above all, are declared
#   in outputs.tf.
#
# Exceptions / failure modes:
#   A module is never applied on its own, so every failure below surfaces in
#   the CALLING root -- `terraform -chdir=infra/envs/<env> plan` -- and it
#   surfaces at PLAN time, before any resource is created or changed. That is
#   the whole reason these are `validation` blocks rather than prose: a
#   malformed value is refused while the graph is still being evaluated,
#   instead of being sent to the AWS API and rejected part-way through an
#   apply that has already built other resources. The conditions are:
#   - `name_prefix` outside lowercase letters, digits and interior hyphens, or
#     longer than 20 characters;
#   - `environment` outside {dev, prod};
#   - `cognito_issuer_uri` not beginning `https://`;
#   - `cognito_app_client_ids` empty, or holding a blank entry;
#   - `alb_listener_arn` not shaped like a listener ARN, meaning an `arn:`
#     prefix and an `:listener/` segment;
#   - `private_app_subnet_ids` with fewer than two entries;
#   - `vpc_link_security_group_ids` empty;
#   - `route_keys` empty, or an entry that is not a method or `ANY`, a single
#     space, then a path beginning `/`;
#   - `spa_cors_allow_origins` empty, or holding `*`;
#   - `cors_allow_methods` or `cors_allow_headers` empty;
#   - `cors_max_age_seconds` outside 0-86400;
#   - `log_retention_days` not one of the values CloudWatch Logs accepts;
#   - `throttling_burst_limit` or `throttling_rate_limit` not positive;
#   - `integration_timeout_milliseconds` outside 50-30000;
#   - `stage_name` blank.
#   A `null` reaches none of those conditions -- see the nullable policy below.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: every cross-module value is an INPUT, and this
#     module calls no sibling module and reads no `data` source. The two
#     plausible alternatives were a `data` source lookup -- resolving the user
#     pool by name to derive its issuer, say -- and a nested `module "cognito"`
#     call. Both were rejected on one mechanism: each would bind this module to
#     another module's internals and to the account and region it happens to
#     run in, so it could no longer be pointed at a pool that already exists,
#     and a change to that other module's outputs would reach in here. Taking
#     the values in keeps this module a pure function of its inputs, and keeps
#     the wiring visible in the environment root -- the one file where a reader
#     can see the whole graph at once.
#   - Trade-off: the cost of that choice is exactly this file's size. Six of
#     the twenty-one inputs exist only to carry a value another module already
#     computed, and the environment root must wire each one. Accepted in
#     exchange for zero cross-module coupling: the wiring is longer to write
#     once, and it cannot break from a distance.
#   - Assumption: `nullable = false` is the policy on every input here except
#     the two documented as nullable at their own declarations. It is
#     load-bearing twice over. On a REQUIRED input, leaving `nullable` at its
#     default of true lets a caller pass `null`, which then reaches the
#     validation expression -- so `startswith(null, "https://")` fails with a
#     generic invalid-function-argument error instead of the message written
#     here. On a DEFAULTED input, `nullable = false` is the setting that makes
#     an explicit `null` fall back to the declared default rather than arrive
#     as `null` and break a `for_each` in main.tf. The policy is stated once
#     here; each of the two deliberate exceptions is justified where it is
#     declared.
#
# Deliberately absent (stated so omission does not read as oversight):
#   - no `provider` or `backend` block: both belong to infra/envs/dev and
#     infra/envs/prod, and versions.tf records why a called module can hold
#     neither;
#   - no `locals`, `resource`, `data`, `output` or `module` block: this file is
#     the input contract and nothing else;
#   - no value that identifies an environment, in a description or in a
#     default. No account id, ARN, issuer URI, pool id, subnet id,
#     security-group id, key ARN, domain or region appears anywhere in this
#     file; illustrative values are written as obvious `<placeholder>` text.
#     That is what keeps environment identity out of the repository as a
#     structural property rather than as a convention someone remembers;
#   - no custom-domain, WAF, usage-plan or API-key input: none belongs to this
#     module's described scope, and adding one would widen it.
#
# Baseline lineage:
#   One enumerated, authenticated front door is inherited here rather than
#   invented. The CICS resource definition published the application's entire
#   public surface as eighteen `DEFINE TRANSACTION(<id>) ... PROGRAM(<name>)`
#   pairs spanning app/csd/CARDDEMO.CSD:L306-L480, the sign-on entry point
#   among them -- `DEFINE TRANSACTION(CC00)` at L378 resolving to
#   `PROGRAM(COSGN00C)` at L379. `route_keys` is that enumeration restated for
#   HTTP, and the JWT authorizer stands where CC00 stood. That file is
#   REFERENCE-ONLY: it is cited by line here and never modified.
# =============================================================================

# -----------------------------------------------------------------------------
# Group A -- naming and tagging
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = "Leading token of every name this module composes, as `<name_prefix>-<environment>-<resource>`: the HTTP API, the VPC Link, the stage and the access-log group. Supplied by the environment root so a second independent copy of the stack can stand up in one account without colliding on a name."
  type        = string
  default     = "carddemo"
  nullable    = false

  # WHAT: lowercase letters and digits, optional interior hyphens, 1-20 chars.
  # WHY : (1) Assumption: the prefix is composed into a CloudWatch Logs group
  #       name, where `/` is the hierarchy separator -- a prefix containing one
  #       would silently relocate the group in the console tree rather than
  #       fail -- and into resource names this tree compares and sorts as
  #       lowercase, so an uppercase character would produce names differing
  #       only in case across resources.
  #       (2) Assumption: the trailing character is constrained as well,
  #       because the composition always appends `-<environment>`. A prefix
  #       ending in a hyphen yields `carddemo--dev`, which is legal and so
  #       would never be caught by anything downstream.
  #       (3) Trade-off: the 20-character bound is deliberately conservative
  #       rather than derived from one service's documented ceiling. The
  #       composed string has to satisfy the SHORTEST of the several name
  #       limits this module's resources impose, and naming a precise figure
  #       here would be a claim about each of those services; 20 leaves room
  #       for the environment token and the per-resource discriminator without
  #       making that claim.
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]{0,18}[a-z0-9])?$", var.name_prefix))
    error_message = "The name_prefix value must be 1-20 characters of lowercase letters, digits and interior hyphens, starting and ending with a letter or digit."
  }
}

variable "environment" {
  description = "Deployment environment this instance of the module belongs to, `dev` or `prod`. Composed into every resource name alongside `name_prefix`, and the axis along which the environment roots vary sizing and retention."
  type        = string
  nullable    = false

  # WHY : Assumption: the accepted set is closed rather than free-form because
  #       exactly two environment roots exist, infra/envs/dev and
  #       infra/envs/prod, and they are specified to differ only in sizing and
  #       retention and never in topology. An open string would accept a third
  #       value with no root behind it, so the stack would carry the name of an
  #       environment nothing provisions or tears down -- and that surfaces as
  #       an orphaned resource name rather than as a rejected plan.
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "The environment value must be either \"dev\" or \"prod\", the two environment roots this tree provides."
  }
}

variable "tags" {
  description = "Tags merged onto every taggable resource this module creates -- the HTTP API, the stage, the VPC Link and the access-log group. Supplied by the environment root, which owns the tagging scheme."
  type        = map(string)
  default     = {}
  nullable    = false

  # WHAT: tags threaded in as data rather than attached by a provider setting.
  # WHY : (1) Assumption: this does not duplicate provider-level tagging, which
  #       is the reasonable first reading. A root can set `default_tags` on its
  #       `provider "aws"` block and have every resource pick them up, and
  #       infra/bootstrap does exactly that -- but a MODULE has no provider
  #       block of its own (versions.tf records why it cannot hold one), so
  #       there is no provider-level place inside this module to attach
  #       anything. What the calling root's `default_tags` sets is inherited
  #       automatically; this map is the channel for whatever the root wants on
  #       THIS module's resources specifically.
  #       (2) Trade-off: the cost is that each taggable resource in main.tf has
  #       to merge the map explicitly, so a resource added later without the
  #       merge is untagged and nothing fails. Accepted because the only
  #       alternative -- a provider block here -- would also take ownership of
  #       region and role assumption, detaching the module from the root's
  #       configuration entirely.
}

# -----------------------------------------------------------------------------
# Group B -- identity: the JWT authorizer contract
# -----------------------------------------------------------------------------

variable "cognito_issuer_uri" {
  description = "OpenID Connect issuer URI of the Cognito user pool, produced by the `cognito` module and passed through by the environment root. Becomes the JWT authorizer's `jwt_configuration.issuer`, so it is what every request's token is validated against. Shaped `https://<cognito-issuer>/<user-pool-id>`."
  type        = string
  nullable    = false

  # WHY : (1) Assumption: an input, never a literal. A literal issuer would
  #       bind this module to one user pool in one account and one region, so
  #       the same module could not serve both environments and could not be
  #       pointed at a pool that already exists. The `cognito` module owns pool
  #       creation; this module only needs to know which pool to trust.
  #       (2) Assumption: the `https://` prefix is the one property worth
  #       asserting locally. The authorizer fetches the pool's signing keys
  #       from this issuer over TLS, so a bare host or an `http://` URL is not
  #       a slower issuer -- it is one the authorizer cannot use at all. The
  #       check catches that paste error at plan time; without it the value is
  #       accepted and the failure appears on live traffic.
  validation {
    condition     = startswith(var.cognito_issuer_uri, "https://") && length(trimspace(var.cognito_issuer_uri)) > 8
    error_message = "The cognito_issuer_uri value must be a non-empty URI beginning with \"https://\", because the authorizer retrieves the pool's signing keys from it over TLS."
  }
}

variable "cognito_app_client_ids" {
  description = "Cognito app client ids whose tokens this API accepts, produced by the `cognito` module. Becomes the JWT authorizer's `jwt_configuration.audience`, so a token whose audience claim falls outside this set is rejected at the edge before any integration runs."
  type        = list(string)
  nullable    = false

  # WHY : (1) Alternatives Considered: a single `string`, which reads more
  #       simply and was rejected. The authorizer's `audience` is a SET of
  #       accepted client ids, so modelling it as one string would make adding
  #       a second client -- a machine-to-machine client alongside the browser
  #       SPA, for instance -- a change to this module's type signature and to
  #       every caller, instead of one more element in a list the root already
  #       passes.
  #       (2) Assumption: an input, never a literal, for the same reason as
  #       cognito_issuer_uri above.
  #       (3) Assumption: an empty list is refused rather than read as "accept
  #       any audience". An authorizer with no audience validates only that a
  #       token came from the right pool, so a token minted for a different
  #       application in that same pool would be honoured here.
  #
  # WHAT: what this authorizer actually enforces, recorded at the input that
  #       configures it because it is the module's whole reason for existing.
  # WHY : Assumption: authorization rides on a SIGNED claim and never on a
  #       field the client supplies. The baseline carried its user-type
  #       discriminator in the record field `SEC-USR-TYPE PIC X(01)` at
  #       app/cpy/CSUSR01Y.cpy:L22, whose two values are 'A' and 'U'. Those map
  #       to the Cognito groups `carddemo-admin` and `carddemo-user`, and
  #       common-lib's JwtRoleConverter turns the `cognito:groups` claim into
  #       Spring Security authorities, so an administrative route is guarded by
  #       the pool's signature over that claim. The mapping and its rationale
  #       are recorded in docs/adr/ADR-008-security-and-identity.md. The
  #       referenced copybook is REFERENCE-ONLY: cited by line, never modified.
  validation {
    condition     = length(var.cognito_app_client_ids) > 0
    error_message = "The cognito_app_client_ids list must hold at least one app client id, because an authorizer with no audience accepts a token minted for any application in the pool."
  }

  validation {
    condition = alltrue([
      for client_id in var.cognito_app_client_ids : length(trimspace(client_id)) > 0
    ])
    error_message = "Every element of cognito_app_client_ids must be a non-blank app client id; a blank entry widens the accepted audience by accident."
  }
}

# -----------------------------------------------------------------------------
# Group C -- private integration and VPC Link
# -----------------------------------------------------------------------------

variable "alb_listener_arn" {
  description = "ARN of the internal ALB's HTTPS listener, produced by the `alb` module. Becomes the private integration's `integration_uri`, so it is the one destination every authenticated request is forwarded to across the VPC Link. Shaped `arn:<partition>:elasticloadbalancing:<region>:<aws-account-id>:listener/app/<lb-name>/<lb-id>/<listener-id>`."
  type        = string
  nullable    = false

  # WHY : (1) Assumption: a LISTENER ARN specifically -- not a URL, not the
  #       load balancer's DNS name and not the load balancer's own ARN. An HTTP
  #       API private integration reaches its target through a VPC Link by
  #       naming a listener or a service-discovery service by ARN. Supplying a
  #       URL here does not yield a slightly different private integration: it
  #       yields a public HTTP_PROXY integration that resolves the name over
  #       the internet and never touches the VPC Link, silently undoing the
  #       isolation the VPC Link exists to provide. That failure reads as
  #       success in a plan, which is why the shape is asserted here.
  #       (2) Trade-off: this is a shape check, not an existence check. It
  #       catches a URL, a DNS name or a load-balancer ARN pasted in by
  #       mistake, and it cannot catch a well-formed listener ARN belonging to
  #       the wrong load balancer. Confirming existence would need a `data`
  #       source, which is precisely the coupling this module declines -- see
  #       the header.
  validation {
    condition     = startswith(var.alb_listener_arn, "arn:") && can(regex(":listener/", var.alb_listener_arn))
    error_message = "The alb_listener_arn value must be a load-balancer LISTENER ARN, beginning \"arn:\" and containing a \":listener/\" segment; a URL or DNS name here produces a public integration that bypasses the VPC Link."
  }
}

variable "private_app_subnet_ids" {
  description = "Ids of the private application subnets the VPC Link places its network interfaces in, produced by the `network` module. They determine which availability zones the edge can reach the internal ALB from."
  type        = list(string)
  nullable    = false

  # WHY : (1) Assumption: the PRIVATE APPLICATION tier -- not the public tier
  #       and not the isolated data tier. The VPC spans three availability
  #       zones with the ECS tasks and the internal ALB in the private
  #       application subnets and Aurora in isolated subnets that hold no
  #       internet route at all. A VPC Link placed in the public subnets would
  #       put the edge's interfaces in the one tier that does have a route out,
  #       which removes the reason for having a VPC Link.
  #       (2) Trade-off: at least two entries are required rather than one.
  #       With a single subnet the front door's interfaces sit in one
  #       availability zone, so losing that zone takes the whole API down even
  #       though the services behind it are spread across three. The floor is
  #       two rather than three so the module stays usable in a two-zone
  #       region, which is a weaker guarantee than the three-zone topology the
  #       environment roots actually pass it.
  validation {
    condition     = length(var.private_app_subnet_ids) >= 2
    error_message = "The private_app_subnet_ids list must hold at least two subnet ids, so the VPC Link's interfaces span more than one availability zone."
  }
}

variable "vpc_link_security_group_ids" {
  description = "Security groups attached to the VPC Link's network interfaces, produced by the `network` module. They are the source side of the edge-to-application flow, so they govern what the VPC Link may reach on the internal ALB."
  type        = list(string)
  nullable    = false

  # WHY : (1) Assumption: supplied by the `network` module rather than created
  #       here. The VPC's permitted flows are enumerated in one place -- load
  #       balancer to application on 8080, application to Aurora on 5432,
  #       application to interface endpoint on 443 -- and the group on this
  #       side is one half of a matched pair whose other half is a rule on the
  #       ALB's own group. A group invented inside this module is one those
  #       rules were never written against, so the integration would be
  #       reachable only if the ALB's group happened to admit it.
  #       (2) Assumption: an empty list is refused. A VPC Link with no group of
  #       its own falls back to the VPC's default security group, whose rules
  #       this tree neither writes nor reviews, so the edge's reach would be
  #       whatever that group happens to allow.
  validation {
    condition     = length(var.vpc_link_security_group_ids) > 0
    error_message = "The vpc_link_security_group_ids list must hold at least one security group id, otherwise the VPC Link falls back to the VPC's default group."
  }
}

# -----------------------------------------------------------------------------
# Group D -- routing
# -----------------------------------------------------------------------------

variable "route_keys" {
  description = "HTTP API route keys to create, one per bounded context, each attached to the JWT authorizer by main.tf. The default exposes the eight migrated services under path prefixes matching the SPA's API client modules; an environment root may extend the list without editing the module."
  type        = list(string)
  nullable    = false
  default = [
    "ANY /auth/{proxy+}",
    "ANY /accounts/{proxy+}",
    "ANY /cards/{proxy+}",
    "ANY /transactions/{proxy+}",
    "ANY /reference/{proxy+}",
    "ANY /authorizations/{proxy+}",
    "ANY /reports/{proxy+}",
    "ANY /batch/{proxy+}",
  ]

  # WHAT: one `ANY /<prefix>/{proxy+}` key per bounded context -- auth,
  #       account, card, transaction, reference, authorization, reporting and
  #       batch.
  # WHY : (1) Alternatives Considered: enumerating every operation at the edge,
  #       one route key per method and path. Rejected because each service
  #       already publishes an OpenAPI 3.1 contract that IS the authoritative
  #       operation list, so a per-operation route table would restate that
  #       contract in a second place and drift from it whenever an operation
  #       was added -- and the drift would present as a 404 from the edge for
  #       an operation the service implements. The division of labour is
  #       deliberate: the edge authenticates and routes coarsely, the service
  #       validates the request against its own contract.
  #       (2) Trade-off: one key per service rather than a single catch-all
  #       `ANY /{proxy+}`. The catch-all is shorter and needs no maintenance;
  #       its cost is that the route table stops being an inventory of what is
  #       exposed, so nothing in a plan distinguishes a reachable service from
  #       an unreachable one, and there is no per-route handle to attach a
  #       route-level authorizer or throttle override to later. The accepted
  #       cost of the per-service form is one line of this list per service.
  #       (3) Assumption: this list is the set of routes the authorizer is
  #       attached to, and no unauthenticated route is contemplated. main.tf
  #       attaches the authorizer to every key built from this list; the policy
  #       scan in .github/workflows/infra-ci.yml fails at HIGH and CRITICAL on
  #       a route with no authorizer, and that gate is met by construction here
  #       rather than by a suppression.
  #       (4) Assumption: `/batch` has no counterpart among the SPA's API
  #       client modules because batch-service is driven by the batch state
  #       machine rather than by the browser; its key carries the
  #       operator-facing job endpoints. The other seven prefixes correspond
  #       one-to-one with
  #       ui/src/api/{auth,accounts,cards,transactions,reference,authorization,
  #       reporting}.ts, so a path is written once and reads the same way on
  #       both sides of the edge.
  #       (5) Trade-off: a greedy `{proxy+}` matches one or more trailing
  #       segments, so a request to a bare collection prefix does not match the
  #       key covering everything beneath it. This is an input with a default
  #       rather than a hardcoded local precisely so a root can add such a key
  #       -- or one needing its own throttle -- with no change to this module.
  validation {
    condition     = length(var.route_keys) > 0
    error_message = "The route_keys list must hold at least one route key; an API with no route accepts no request."
  }

  validation {
    condition = alltrue([
      for key in var.route_keys :
      can(regex("^(ANY|GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS) /", key))
    ])
    error_message = "Each entry in route_keys must be an HTTP method or ANY, then a single space, then a path beginning with \"/\" -- for example \"ANY /accounts/{proxy+}\"."
  }
}


# -----------------------------------------------------------------------------
# Group E -- CORS for the CloudFront-hosted SPA
# -----------------------------------------------------------------------------

variable "spa_cors_allow_origins" {
  description = "Exact origins permitted to call this API from a browser: the distribution serving the SPA, produced by the `cloudfront-spa` module. Becomes `cors_configuration.allow_origins`, so an origin outside this list fails the browser's preflight. Shaped `https://<distribution-domain>`."
  type        = list(string)
  nullable    = false

  # WHY : (1) Alternatives Considered: permitting `"*"` and documenting it as
  #       discouraged. Rejected, and refused by a validation instead, on two
  #       independent mechanisms. A wildcard origin makes this API callable
  #       from any page on the internet, so any site a signed-in user visits
  #       can issue requests to it from that user's browser. And a wildcard is
  #       not even a working configuration for a credentialed request: the
  #       browser itself refuses a `*` origin when credentials are included, so
  #       a wildcard set here would surface later as a CORS failure that reads
  #       like a configuration bug rather than as the exposure it is. A
  #       validation makes it impossible rather than inadvisable.
  #       (2) Trade-off: exact origins mean the list must be updated when the
  #       SPA gains a distribution or a hostname, and a missed update presents
  #       as a browser-only failure that a curl reproduction will not show.
  #       Accepted: the update is one element in a root's tfvars, and the
  #       alternative is the exposure above.
  #       (3) Assumption: no default. No origin is correct for both
  #       environments, so a default would let a root apply with the wrong one
  #       silently instead of failing to plan.
  validation {
    condition     = length(var.spa_cors_allow_origins) > 0
    error_message = "The spa_cors_allow_origins list must hold at least one exact origin; the SPA cannot call an API that allows no origin."
  }

  validation {
    condition     = !contains(var.spa_cors_allow_origins, "*")
    error_message = "The spa_cors_allow_origins list must not hold \"*\": a wildcard origin makes the API callable from any page, and browsers refuse a wildcard origin on credentialed requests."
  }
}

variable "cors_allow_methods" {
  description = "HTTP methods advertised to the browser in the preflight response. Becomes `cors_configuration.allow_methods`; the default is the set the migrated services' contracts expose, plus OPTIONS for the preflight exchange itself."
  type        = list(string)
  default     = ["GET", "POST", "PUT", "DELETE", "OPTIONS"]
  nullable    = false

  # WHY : (1) Alternatives Considered: `["*"]`, which API Gateway accepts as
  #       allow-all. Rejected because it advertises methods no service
  #       implements, so a browser's preflight would succeed for a method the
  #       service then answers with 405 -- moving the failure out of the
  #       preflight, where it is legible, and into the real request.
  #       Enumerating keeps the edge's advertised surface equal to the
  #       implemented surface.
  #       (2) Assumption: OPTIONS is listed alongside the methods the SPA
  #       issues because the preflight exchange is itself an OPTIONS request.
  #       Listing it keeps the advertised set and the methods actually seen at
  #       the edge the same, so a reader comparing access logs against this
  #       list finds no unexplained method.
  validation {
    condition     = length(var.cors_allow_methods) > 0
    error_message = "The cors_allow_methods list must hold at least one method, otherwise no cross-origin request from the SPA can pass its preflight."
  }
}

variable "cors_allow_headers" {
  description = "Request headers a browser may send cross-origin. Becomes `cors_configuration.allow_headers`; every header the SPA sets on an authenticated JSON request has to appear here or the browser withholds the request after the preflight."
  type        = list(string)
  default     = ["authorization", "content-type", "x-correlation-id"]
  nullable    = false

  # WHY : (1) Assumption: `authorization` is not optional. The SPA
  #       authenticates by putting a bearer token in that header, and a header
  #       absent from this list is withheld by the browser -- so omitting it
  #       does not weaken authentication, it makes every authenticated request
  #       from the browser fail its preflight while a curl reproduction of the
  #       same request still succeeds. That asymmetry is a failure a reader
  #       should not have to discover empirically.
  #       (2) Assumption: `content-type` is required because a JSON body makes
  #       a request non-simple, so every POST and PUT the SPA issues carries it
  #       and triggers a preflight that checks for it.
  #       (3) Assumption: `x-correlation-id` is the client-supplied correlation
  #       identifier common-lib's CorrelationIdFilter reads on the way in and
  #       echoes on the way out. Without it here the browser cannot send one,
  #       so a trace begins at the edge and the client's own identifier never
  #       joins it -- the request is still served, which is what makes the
  #       omission easy to miss.
  validation {
    condition     = length(var.cors_allow_headers) > 0
    error_message = "The cors_allow_headers list must hold at least one header; it has to include the authorization header for any authenticated browser request to be sent."
  }
}

variable "cors_max_age_seconds" {
  description = "Upper bound, in seconds, on how long a browser may cache this API's preflight response. Becomes `cors_configuration.max_age`."
  type        = number
  default     = 300
  nullable    = false

  # WHY : (1) Trade-off: a bound is set rather than left unset. Unset, a
  #       browser re-runs the preflight for each non-simple request pattern, so
  #       every POST and PUT from the SPA costs two round trips instead of one.
  #       Caching removes the second; the cost is that a change to the allowed
  #       headers or methods is not seen by a browser still holding a cached
  #       preflight until that copy is discarded.
  #       (2) Trade-off: the chosen magnitude keeps that window narrow. A bound
  #       at the field's maximum would maximise the saving and would equally
  #       mean a corrected CORS configuration is ignored by already-open
  #       browser sessions for as long as the browser chooses to honour it; a
  #       small bound keeps the correction observable. The value is a ceiling
  #       on the browser's discretion rather than an instruction -- a browser
  #       may cache for less, and several cap it well below the field's
  #       maximum.
  validation {
    condition     = var.cors_max_age_seconds >= 0 && var.cors_max_age_seconds <= 86400
    error_message = "The cors_max_age_seconds value must be between 0 and 86400 inclusive, the range the CORS max-age field accepts."
  }
}

# WHAT: `allow_credentials` is deliberately not an input, and main.tf leaves it
#       off.
# WHY : Alternatives Considered: exposing it as a variable. Rejected because
#       the SPA authenticates with a bearer token in the `Authorization` header
#       rather than with a cookie, so a credentialed CORS exchange would have
#       nothing to carry; and enabling it interacts with the wildcard question
#       above -- a browser refuses `*` together with credentials, so the two
#       settings constrain each other. Leaving it off keeps the credential path
#       in exactly one place: the header the JWT authorizer reads.

# -----------------------------------------------------------------------------
# Group F -- observability and rate limiting
# -----------------------------------------------------------------------------

variable "log_retention_days" {
  description = "Retention applied to the stage's access-log group in CloudWatch Logs. One of the narrow set of values on which the dev and prod roots deliberately differ; 0 retains log events indefinitely."
  type        = number
  default     = 30
  nullable    = false

  # WHY : (1) Assumption: an input rather than a constant because retention is
  #       explicitly one of the levers the two environment roots vary -- they
  #       are specified as identical in topology and different only in sizing
  #       and retention -- so a constant here would either over-retain in dev
  #       or under-retain in prod.
  #       (2) Assumption: the accepted set is enumerated rather than
  #       range-checked because CloudWatch Logs accepts only these discrete
  #       values. A number between two of them is not rounded to the nearer
  #       one, it is rejected by the API during apply -- exactly the mid-apply
  #       failure this validation pulls forward to plan time.
  validation {
    condition = contains(
      [0, 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653],
      var.log_retention_days
    )
    error_message = "The log_retention_days value must be 0 for indefinite retention, or one of the discrete day counts CloudWatch Logs accepts: 1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653."
  }
}

variable "access_log_kms_key_arn" {
  description = "ARN of the customer-managed KMS key encrypting the stage's access-log group, produced by the `kms` module. Null selects the CloudWatch Logs service-managed key instead."
  type        = string
  default     = null
  nullable    = true

  # WHAT: nullable, defaulting to null -- the first of the two deliberate
  #       exceptions to this file's `nullable = false` policy.
  # WHY : (1) Alternatives Considered: making the key ARN required. Rejected
  #       because it would make a provisioned customer-managed key a
  #       precondition for standing up the edge at all, so the module could not
  #       be exercised in an environment where the `kms` module has not run.
  #       Null is a meaningful value here rather than a missing one: main.tf
  #       resolves it conditionally and the group falls back to the
  #       service-managed key, which still encrypts the log data at rest.
  #       (2) Trade-off: the cost is that omitting the key succeeds quietly, so
  #       an environment intended to use a customer-managed key can be applied
  #       without one and nothing fails. Accepted because the environment roots
  #       pass the key explicitly, and the alternative blocks every use of the
  #       module without one. This is the same nullable-with-null-default shape
  #       infra/bootstrap/variables.tf uses for its state-encryption key.
}

variable "throttling_burst_limit" {
  description = "Token-bucket depth applied by default to every route on the stage: how many requests above the steady rate the edge absorbs before it starts rejecting. Shields the eight services behind it from one client's spike."
  type        = number
  default     = 200
  nullable    = false

  # WHY : (1) Trade-off: throttling is configured here rather than left to the
  #       account-level default. That default is a per-account, per-region
  #       allowance shared by every API in the account, so relying on it means
  #       one client's burst against this API draws down an allowance the rest
  #       of the account shares, and nothing in this configuration records what
  #       the edge is willing to pass. Setting it makes the limit a property of
  #       this stage and a reviewable line in a plan. The cost is that a
  #       genuine load increase now needs a value change here, which is
  #       accepted because that change is also the record of the decision.
  #       (2) Assumption: setting it is what makes the throttling check in the
  #       infrastructure policy scan pass by construction, rather than by a
  #       suppression in the scanner's configuration.
  validation {
    condition     = var.throttling_burst_limit > 0
    error_message = "The throttling_burst_limit value must be greater than zero; a zero burst rejects every request, including the first."
  }
}

variable "throttling_rate_limit" {
  description = "Steady-state request rate, in requests per second, the stage sustains by default on every route. Applied together with throttling_burst_limit as the rate at which the bucket refills."
  type        = number
  default     = 100
  nullable    = false

  # WHY : (1) Trade-off: burst and rate are two knobs rather than one because
  #       they describe different things -- rate is how fast the bucket
  #       refills, burst is how deep it is. A single number could not express
  #       "absorb a short spike without raising the sustained ceiling", and the
  #       SPA issues several requests per screen turn, so a screen that loads a
  #       list plus its lookups needs a depth above the steady rate to avoid
  #       being throttled during one user's ordinary navigation.
  #       (2) Assumption: both defaults are starting bounds an environment root
  #       is expected to override. They are positive and finite so the stage
  #       always carries an explicit ceiling, not because either figure
  #       predicts a load.
  validation {
    condition     = var.throttling_rate_limit > 0
    error_message = "The throttling_rate_limit value must be greater than zero; a zero steady rate never refills the burst bucket."
  }
}

variable "detailed_metrics_enabled" {
  description = "Whether the stage emits per-route CloudWatch metrics -- count, latency and 4XX/5XX broken out by route key -- in addition to the API-wide aggregates it emits regardless."
  type        = bool
  default     = true
  nullable    = false

  # WHY : (1) Trade-off: on by default even though it costs more than the
  #       aggregates, which are emitted either way. Per-route dimensions are
  #       billed per metric, so the charge scales with the number of route keys
  #       -- eight by default here -- times the metrics each emits. Accepted
  #       because centralized logging, metrics and tracing are a cross-cutting
  #       requirement of this migration, and without per-route dimensions an
  #       elevated 5XX rate is visible only as one API-wide number: it says the
  #       edge is failing without saying which service is, which is the first
  #       question asked.
  #       (2) Assumption: it is an input, so an environment that prefers the
  #       cheaper aggregate-only signal can turn it off without a change here.
}

# -----------------------------------------------------------------------------
# Group G -- integration tuning and stage
# -----------------------------------------------------------------------------

variable "integration_timeout_milliseconds" {
  description = "Upper bound, in milliseconds, the private integration waits for the internal ALB to respond before the edge abandons the request and returns a gateway timeout. Applied to every integration this module creates."
  type        = number
  default     = 29000
  nullable    = false

  # WHY : (1) Assumption: the bound is load-bearing rather than decorative. The
  #       decision to adopt no circuit-breaker library rests on the synchronous
  #       hops being in-VPC behind an internal load balancer WITH BOUNDED
  #       TIMEOUTS, so this is one of the bounds that argument depends on. Left
  #       unbounded, a stalled upstream would hold edge connections instead of
  #       failing, and the reasoning for having no breaker would no longer
  #       hold.
  #       (2) Trade-off: the default sits just inside the ceiling the field
  #       accepts rather than well below it, which makes the edge the LAST
  #       component to give up. A service that is slow but does answer
  #       therefore returns its own structured error body; a tighter edge bound
  #       would replace that body with a generic gateway timeout and discard
  #       the diagnosis the service had already produced. A root wanting the
  #       edge to fail ahead of its services lowers this value.
  #       (3) Assumption: the validated range is the one the integration field
  #       accepts, a 50-millisecond floor up to a 30-second ceiling. A value
  #       above the ceiling is refused by the API during apply, so checking it
  #       here converts a mid-apply rejection into a plan-time message.
  validation {
    condition     = var.integration_timeout_milliseconds >= 50 && var.integration_timeout_milliseconds <= 30000
    error_message = "The integration_timeout_milliseconds value must be between 50 and 30000 inclusive, the range an HTTP API integration timeout accepts."
  }
}

variable "integration_tls_server_name" {
  description = "Server name the integration verifies against the certificate the internal ALB listener presents, for the case where that certificate's subject does not match the name the VPC Link resolves. Null omits the TLS configuration block entirely."
  type        = string
  default     = null
  nullable    = true

  # WHAT: nullable, defaulting to null -- the second of the two deliberate
  #       exceptions to this file's `nullable = false` policy.
  # WHY : (1) Alternatives Considered: a non-null default such as the ALB's own
  #       DNS name. Rejected because it would assert a certificate arrangement
  #       this module cannot see: whether the listener's certificate matches
  #       the resolved name is a property of the certificate the `alb` module
  #       was given, not of anything here. A wrong assertion fails the
  #       handshake, which presents as an opaque integration failure rather
  #       than as the naming mismatch it is.
  #       (2) Assumption: null is a distinct instruction, not an absent value.
  #       main.tf reads it as "emit no `tls_config` block at all" through a
  #       `dynamic` block, so the module works unchanged with a certificate
  #       that matches the resolved name and with one that does not, and no
  #       sentinel string has to stand in for "unset".
}

variable "stage_name" {
  description = "Name of the single stage this module creates. `$default` is the reserved name for a stage that serves requests with no stage segment in the path."
  type        = string
  default     = "$default"
  nullable    = false

  # WHY : (1) Alternatives Considered: a named stage such as `v1`, or the
  #       environment name. Rejected because a named stage prepends its name to
  #       the invoke URL's path, so `/accounts/...` is served at
  #       `/<stage>/accounts/...`. Every route key here, every path in the
  #       services' own OpenAPI documents and every path the SPA's API client
  #       modules build would then have to add or strip that extra segment, and
  #       each place that forgot would be a 404 or a doubled prefix. The
  #       reserved `$default` name yields an invoke URL with no stage segment,
  #       so one path is written once and means the same thing at the edge, in
  #       the contract and in the client.
  #       (2) Assumption: the environment is already distinguished by
  #       `name_prefix` and `environment` in the API's own name, so a stage
  #       name repeating it would add a path segment encoding something the
  #       resource name already states.
  #       (3) Trade-off: with one stage there is no path-based way to serve two
  #       revisions of the API side by side behind one endpoint. That matches
  #       the deployment model chosen for this migration -- rolling service
  #       updates rather than blue-green or canary -- so the capability a named
  #       stage would add is one nothing here uses.
  validation {
    condition     = length(trimspace(var.stage_name)) > 0
    error_message = "The stage_name value must not be blank; use \"$default\" for a stage serving requests with no stage path segment."
  }
}

