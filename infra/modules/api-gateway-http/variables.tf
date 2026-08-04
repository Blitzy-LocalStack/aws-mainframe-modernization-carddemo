# =============================================================================
# infra/modules/api-gateway-http/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE input surface of the reusable `api-gateway-http` Terraform
#   module -- the single public entry point for the seven ONLINE migrated
#   CardDemo services: an API Gateway HTTP API with a Cognito JWT authorizer,
#   reaching the internal ALB over a VPC Link so the private application subnets
#   are never exposed. The eighth deployable, batch-service, is reached only by
#   the batch state machine's synchronous run-task call and is published here by
#   no route at all. Every value this module needs from a sibling module arrives
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
#   - Trade-offs: the cost of that choice is exactly this file's size. Six of
#     the twenty-one inputs exist only to carry a value another module already
#     computed, and the environment root must wire each one. Accepted in
#     exchange for zero cross-module coupling: the wiring is longer to write
#     once, and it cannot break from a distance.
#   - Assumptions: `nullable = false` is the policy on every input here except
#     the two documented as nullable at their own declarations. It is
#     load-bearing twice over. On a REQUIRED input, leaving `nullable` at its
#     default of true lets a caller pass `null`, which then reaches the
#     validation expression -- so `startswith(null, "https://")` fails with a
#     generic invalid-function-argument error instead of the message written
#     here. On a DEFAULTED input, `nullable = false` is the setting that makes
#     an explicit `null` fall back to the declared default rather than arrive
#     as `null` and break a `for_each` in main.tf. The policy is stated once
#     here; the single deliberate exception is justified where it is declared.
#     Refactoring Rationale: there were two. `integration_tls_server_name` was
#     the second, and it is now required and non-null, because its null value
#     instructed main.tf to emit no TLS configuration on the private integration
#     and so produced a plaintext hop by default; the full reasoning is at that
#     declaration.
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
  # WHY : (1) Assumptions: the prefix is composed into a CloudWatch Logs group
  #       name, where `/` is the hierarchy separator -- a prefix containing one
  #       would silently relocate the group in the console tree rather than
  #       fail -- and into resource names this tree compares and sorts as
  #       lowercase, so an uppercase character would produce names differing
  #       only in case across resources.
  #       (2) Assumptions: the trailing character is constrained as well,
  #       because the composition always appends `-<environment>`. A prefix
  #       ending in a hyphen yields `carddemo--dev`, which is legal and so
  #       would never be caught by anything downstream.
  #       (3) Trade-offs: the 20-character bound is deliberately conservative
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

  # WHY : Assumptions: the accepted set is closed rather than free-form because
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
  # WHY : (1) Assumptions: this does not duplicate provider-level tagging, which
  #       is the reasonable first reading. A root can set `default_tags` on its
  #       `provider "aws"` block and have every resource pick them up, and
  #       infra/bootstrap does exactly that -- but a MODULE has no provider
  #       block of its own (versions.tf records why it cannot hold one), so
  #       there is no provider-level place inside this module to attach
  #       anything. What the calling root's `default_tags` sets is inherited
  #       automatically; this map is the channel for whatever the root wants on
  #       THIS module's resources specifically.
  #       (2) Trade-offs: the cost is that each taggable resource in main.tf has
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

  # WHY : (1) Assumptions: an input, never a literal. A literal issuer would
  #       bind this module to one user pool in one account and one region, so
  #       the same module could not serve both environments and could not be
  #       pointed at a pool that already exists. The `cognito` module owns pool
  #       creation; this module only needs to know which pool to trust.
  #       (2) Assumptions: the `https://` prefix is the one property worth
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
  #       (2) Assumptions: an input, never a literal, for the same reason as
  #       cognito_issuer_uri above.
  #       (3) Assumptions: an empty list is refused rather than read as "accept
  #       any audience". An authorizer with no audience validates only that a
  #       token came from the right pool, so a token minted for a different
  #       application in that same pool would be honoured here.
  #
  # WHAT: what this authorizer actually enforces, recorded at the input that
  #       configures it because it is the module's whole reason for existing.
  # WHY : Assumptions: authorization rides on a SIGNED claim and never on a
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

  # WHY : (1) Assumptions: a LISTENER ARN specifically -- not a URL, not the
  #       load balancer's DNS name and not the load balancer's own ARN. An HTTP
  #       API private integration reaches its target through a VPC Link by
  #       naming a listener or a service-discovery service by ARN. Supplying a
  #       URL here does not yield a slightly different private integration: it
  #       yields a public HTTP_PROXY integration that resolves the name over
  #       the internet and never touches the VPC Link, silently undoing the
  #       isolation the VPC Link exists to provide. That failure reads as
  #       success in a plan, which is why the shape is asserted here.
  #       (2) Trade-offs: this is a shape check, not an existence check. It
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

  # WHY : (1) Assumptions: the PRIVATE APPLICATION tier -- not the public tier
  #       and not the isolated data tier. The VPC spans three availability
  #       zones with the ECS tasks and the internal ALB in the private
  #       application subnets and Aurora in isolated subnets that hold no
  #       internet route at all. A VPC Link placed in the public subnets would
  #       put the edge's interfaces in the one tier that does have a route out,
  #       which removes the reason for having a VPC Link.
  #       (2) Trade-offs: at least two entries are required rather than one.
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

  # WHY : (1) Assumptions: supplied by the `network` module rather than created
  #       here. The VPC's permitted flows are enumerated in one place -- load
  #       balancer to application on 8080, application to Aurora on 5432,
  #       application to interface endpoint on 443 -- and the group on this
  #       side is one half of a matched pair whose other half is a rule on the
  #       ALB's own group. A group invented inside this module is one those
  #       rules were never written against, so the integration would be
  #       reachable only if the ALB's group happened to admit it.
  #       (2) Assumptions: an empty list is refused. A VPC Link with no group of
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
  description = "HTTP API route keys to create, each attached to the JWT authorizer AND given var.route_authorization_scopes by main.tf. The default exposes the SEVEN online bounded contexts as a matched pair of keys apiece -- the bare collection prefix and the greedy subtree beneath it -- under path prefixes matching the SPA's API client modules. batch-service is deliberately absent: it has no ALB target to route to. An environment root may extend the list without editing the module."
  type        = list(string)
  nullable    = false
  default = [
    "ANY /auth",
    "ANY /auth/{proxy+}",
    "ANY /accounts",
    "ANY /accounts/{proxy+}",
    "ANY /cards",
    "ANY /cards/{proxy+}",
    "ANY /transactions",
    "ANY /transactions/{proxy+}",
    "ANY /reference",
    "ANY /reference/{proxy+}",
    "ANY /authorizations",
    "ANY /authorizations/{proxy+}",
    "ANY /reports",
    "ANY /reports/{proxy+}",
  ]

  # WHAT: two `ANY` keys per online bounded context -- `/<prefix>` and
  #       `/<prefix>/{proxy+}` -- for auth, account, card, transaction,
  #       reference, authorization and reporting. Fourteen keys, seven services,
  #       and no batch key.
  # WHY : Refactoring Rationale: an eighth context, `/batch`, was published here
  #       and has been REMOVED, and the removal is the point rather than a
  #       tidy-up. It described "operator-facing job endpoints", but batch-service
  #       publishes no such endpoints: it has no api package, no controller and no
  #       published contract, and its only invocation path is the synchronous
  #       run-task call a Step Functions state makes, passing the job name and the
  #       business date as container command arguments. The route therefore
  #       exposed a prefix with nothing behind it, and it did so at the one place
  #       in this architecture that is reachable from the internet. Two
  #       independent reasons to delete it: an internet-reachable path into the
  #       batch container is a surface no requirement asks for, and its presence
  #       invited exactly the operator HTTP trigger that
  #       services/batch-service/pom.xml and that service's package charter both
  #       record as considered and rejected -- a second invocation path, with a
  #       second authorization surface to design and defend, and two argument
  #       sources for one job that could disagree.
  # WHY : Assumptions: removing the route is not the whole fix, because a route is
  #       only the EDGE -- a caller already inside the private application tier
  #       reaches a listener without traversing it. That half is closed in the
  #       service itself, and closed more completely than a filter chain closes
  #       it: services/batch-service/pom.xml declares neither
  #       spring-boot-starter-web nor spring-boot-starter-actuator, so the batch
  #       container starts no servlet container and opens no listening port at
  #       all. There is no in-VPC HTTP surface left to authorize, which is also
  #       why the edge route had nothing to integrate with. Neither half is
  #       redundant: this list keeps the prefix unpublished, and the absent web
  #       starters keep there being nothing to publish.
  # WHY : (1) Alternatives Considered: enumerating every operation at the edge,
  #       one route key per method and path. Rejected because each service
  #       already publishes an OpenAPI 3.1 contract that IS the authoritative
  #       operation list, so a per-operation route table would restate that
  #       contract in a second place and drift from it whenever an operation
  #       was added -- and the drift would present as a 404 from the edge for
  #       an operation the service implements. The division of labour is
  #       deliberate: the edge authenticates and routes coarsely, the service
  #       validates the request against its own contract.
  #       (2) Trade-offs: one key per service rather than a single catch-all
  #       `ANY /{proxy+}`. The catch-all is shorter and needs no maintenance;
  #       its cost is that the route table stops being an inventory of what is
  #       exposed, so nothing in a plan distinguishes a reachable service from
  #       an unreachable one, and there is no per-route handle to attach a
  #       route-level authorizer or throttle override to later. The accepted
  #       cost of the per-service form is one line of this list per service.
  #       (3) Assumptions: this list is the set of routes the authorizer is
  #       attached to, and no unauthenticated route is contemplated. main.tf
  #       attaches the authorizer to every key built from this list AND applies
  #       var.route_authorization_scopes to each one; the policy scan in
  #       .github/workflows/infra-ci.yml fails at HIGH and CRITICAL on a route
  #       with no authorizer, and that gate is met by construction here rather
  #       than by a suppression.
  #       (4) Refactoring Rationale: `/batch` was previously published here and
  #       has been REMOVED, because there was nothing behind it. Batch is the one
  #       ECS instantiation created with attach_load_balancer and create_service
  #       both false (see infra/modules/ecs-service/variables.tf), so it has no
  #       target group, no long-running service and no ALB listener rule; a batch
  #       route at the edge would authenticate a caller and then fail to
  #       integrate, which presents as an edge fault rather than as the missing
  #       target it is. Its only invocation path is the Step Functions
  #       synchronous run-task call, which passes job selection and the business
  #       date as container overrides and never traverses this API. The seven
  #       remaining prefixes correspond one-to-one with
  #       ui/src/api/{auth,accounts,cards,transactions,reference,authorization,
  #       reporting}.ts, so a path is written once and reads the same way on
  #       both sides of the edge.
  #       (5) Refactoring Rationale: each context now carries a BARE key
  #       alongside its greedy one, because a greedy `{proxy+}` matches one or
  #       more trailing segments and therefore does NOT match the collection
  #       prefix itself. With the greedy key alone, `GET /accounts` -- a
  #       collection request every list screen issues -- returns the API's
  #       404 with no integration attempted, while `GET /accounts/123` succeeds:
  #       a half-reachable service, and the half that fails is the one a browser
  #       hits first. The pair is the smallest form that covers both, and it is
  #       still an input with a default rather than a hardcoded local, so a root
  #       needing an extra key, or one with its own throttle, adds it without
  #       editing this module.
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

  # WHY : Assumptions: batch-service cannot be reached through this API at all,
  #       and that is an invariant of the target architecture rather than a
  #       default a root may reverse. The batch ECS instantiation creates no
  #       service and no target group, so the ALB has no batch rule and no batch
  #       target for an integration to point at; a route added here would plan
  #       and apply cleanly and then fail every request. Rejecting the prefix at
  #       plan time names the reason, whereas the runtime symptom is an
  #       integration failure that looks like a broken service.
  #       Trade-offs: this constrains an input the module otherwise leaves open.
  #       Accepted, because the openness exists so a root can add a route to a
  #       service that EXISTS behind the load balancer, and batch is the one
  #       context for which no such target can exist.
  validation {
    condition = alltrue([
      for key in var.route_keys :
      !can(regex("^[A-Z]+ /batch(/|$)", key))
    ])
    error_message = "route_keys must not publish a /batch route: batch-service runs as one-shot Step Functions tasks with no ECS service and no ALB target group, so an edge route for it has nothing to integrate with."
  }

  # WHY : Assumptions: a greedy key without its bare sibling is the defect this
  #       default was corrected for, so the pairing is enforced rather than only
  #       demonstrated. A root that overrode the list with greedy keys alone
  #       would reintroduce exactly the half-reachable service described above,
  #       and the symptom -- a 404 on the collection path and a success one
  #       segment deeper -- reads as a service fault rather than as a routing
  #       omission.
  #       Trade-offs: the check is one-directional. A bare key with no greedy
  #       sibling is permitted, because exposing a collection without its
  #       subtree is a coherent thing to want (a list-only surface), whereas the
  #       reverse never is.
  validation {
    condition = alltrue([
      for key in var.route_keys :
      contains(var.route_keys, replace(key, "/{proxy+}", ""))
      if endswith(key, "/{proxy+}")
    ])
    error_message = "Every greedy \"<METHOD> /<prefix>/{proxy+}\" entry in route_keys must be accompanied by the bare \"<METHOD> /<prefix>\" entry: a greedy proxy matches one or more trailing segments, so without the bare key a request to the collection prefix itself is answered 404 by the API with no integration attempted."
  }

  # WHY : Assumptions: a duplicate route key is not a harmless repetition. main.tf
  #       drives aws_apigatewayv2_route from this list, so two identical entries
  #       collide on one resource address, and the API itself refuses two routes
  #       with the same key. Catching it here names the duplicated key instead of
  #       leaving a for_each or provider error to be read backwards.
  validation {
    condition     = length(distinct(var.route_keys)) == length(var.route_keys)
    error_message = "route_keys must not contain duplicate entries: each route key may be created only once on an HTTP API."
  }
}

# WHAT: the authorization scopes every route created from var.route_keys requires,
#       applied by main.tf to each route's authorization_scopes.
# WHY : (1) Assumptions: attaching the JWT authorizer is NOT the same as
#       authorizing, and the gap it leaves is the reason this input exists. The
#       authorizer validates the token's signature, its issuer and its time
#       claims and then admits it. One Cognito user pool mints TWO token kinds
#       from the same signing key: an ACCESS token, whose `token_use` claim is
#       `access` and which carries a `scope` claim, and an IDENTITY token, whose
#       `token_use` claim is `id` and which carries NO scope claim at all. Both
#       are validly signed by the configured issuer, so an authorizer with no
#       scope requirement accepts an identity token as authorization for an API
#       call -- and an identity token is the one a browser is most likely to have
#       to hand, so the gap is reachable rather than theoretical. Requiring any
#       scope at all rejects it: the route requires the token's scope claim to
#       include at least one listed value, and a token with no scope claim can
#       satisfy no such requirement.
#       (2) Assumptions: the default names the pool's BUILT-IN scope rather than
#       one of the custom scopes infra/modules/cognito registers on its resource
#       server, and the choice is deliberate. A token obtained through the pool's
#       authentication API -- which is how every interactive sign-on in this
#       system obtains one -- carries `aws.cognito.signin.user.admin` and no
#       custom scope, because custom scopes are granted only through the OAuth
#       flows this design does not use for sign-on. Defaulting to a custom scope
#       would therefore reject every signed-in user while looking more precise,
#       which is the failure mode worth naming: the more restrictive-looking
#       value is the one that breaks the system.
#       (3) Assumptions: this is a coarse token-kind gate and not the
#       authorization decision. Who may reach an administrative endpoint is
#       decided by each service's filter chain from the signed `cognito:groups`
#       claim, and each service additionally re-checks `token_use` and the
#       accepted client id from its own configuration. The edge and the service
#       assert the same claim from opposite ends, so neither is trusted alone.
#       (4) Trade-offs: one list applied to every route rather than a per-route
#       map. A map would allow a stricter scope on, say, the administrative card
#       route, and was rejected because the per-route distinction that matters
#       here is the group claim rather than the scope, so a scope map would imply
#       an edge-enforced boundary that does not exist and would have to be kept
#       in step with the service catalog by hand. A root needing per-route scopes
#       can add the map alongside this input without changing its meaning.
#       (5) Assumptions: an empty list is refused. An empty
#       `authorization_scopes` is not "no opinion" -- it removes the requirement
#       entirely and restores the behaviour this input exists to correct, so it
#       is rejected rather than accepted as a way to opt out.
variable "route_authorization_scopes" {
  description = "Scopes every route requires in the token's scope claim, applied by main.tf to each route's authorization_scopes. Defaults to the Cognito user pool's built-in aws.cognito.signin.user.admin scope, which is the scope an interactively signed-in access token carries and which an identity token carries not at all, so the requirement rejects the wrong token kind at the edge."
  type        = list(string)
  default     = ["aws.cognito.signin.user.admin"]
  nullable    = false

  validation {
    condition     = length(var.route_authorization_scopes) > 0
    error_message = "route_authorization_scopes must name at least one scope. An empty list removes the scope requirement altogether, which lets a validly signed Cognito IDENTITY token authorize an API call because an identity token carries no scope claim to test."
  }

  validation {
    condition = alltrue([
      for scope in var.route_authorization_scopes :
      length(trimspace(scope)) > 0 && !strcontains(scope, " ")
    ])
    error_message = "Every route_authorization_scopes entry must be a single non-blank scope with no embedded space. A token's scope claim is a space-delimited list, so an entry containing a space would be compared as one unmatchable value rather than as the two scopes it appears to name."
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
  #       (2) Trade-offs: exact origins mean the list must be updated when the
  #       SPA gains a distribution or a hostname, and a missed update presents
  #       as a browser-only failure that a curl reproduction will not show.
  #       Accepted: the update is one element in a root's tfvars, and the
  #       alternative is the exposure above.
  #       (3) Assumptions: no default. No origin is correct for both
  #       environments, so a default would let a root apply with the wrong one
  #       silently instead of failing to plan.
  validation {
    condition     = length(var.spa_cors_allow_origins) > 0
    error_message = "The spa_cors_allow_origins list must hold at least one exact origin; the SPA cannot call an API that allows no origin."
  }

  # WHY : Refactoring Rationale: this check previously tested only
  #       `!contains(var.spa_cors_allow_origins, "*")`, and equality against the
  #       bare asterisk is not the same thing as refusing a wildcard. An HTTP API
  #       recognises `http://*` and `https://*` as wildcard origin patterns too,
  #       so `spa_cors_allow_origins = ["https://*"]` passed the old validation
  #       word for word while permitting EVERY https origin on the internet --
  #       the exposure the paragraph above says is refused, reachable by a value
  #       the check did not test for. The replacement stops enumerating forbidden
  #       spellings and instead requires each entry to MATCH the shape of an exact
  #       origin, which refuses every wildcard form including any spelling nobody
  #       has thought of yet.
  # WHY : Assumptions: an origin is a scheme, a host and an optional port, and
  #       nothing else. The pattern therefore admits `https://` followed by at
  #       least one dot-separated label of letters, digits and hyphens, optionally
  #       followed by a colon and one to five digits, and then the end of the
  #       string. Because the pattern is anchored at both ends and its host class
  #       excludes the asterisk, every wildcard form is refused; because it ends
  #       there, so are a path, a query, a fragment and a trailing slash, none of
  #       which an Origin header ever carries and each of which would silently
  #       fail to match a real browser origin at run time -- a value that looks
  #       configured and rejects the SPA anyway. Userinfo is excluded by the same
  #       host class, since neither `@` nor `:` before the port is admitted.
  # WHY : Trade-offs: the localhost exception is written as two explicit
  #       alternatives rather than folded into the general host pattern. A
  #       developer running the SPA on the loopback interface has no certificate,
  #       so cleartext has to be permitted THERE and must remain refused
  #       everywhere else; naming the two loopback hosts literally is what keeps
  #       the exception from widening to any host that happens to resolve
  #       locally. Accepted cost: a root serving the SPA from a differently named
  #       local host has to use https or 127.0.0.1.
  validation {
    condition = alltrue([
      for origin in var.spa_cors_allow_origins :
      can(regex("^https://[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+(:[0-9]{1,5})?$", origin)) ||
      can(regex("^http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?$", origin))
    ])
    error_message = "Every spa_cors_allow_origins entry must be one EXACT origin: \"https://<host>\" with an optional \":<port>\", or \"http://localhost\" or \"http://127.0.0.1\" with an optional port for local development. Wildcards in every form are refused -- \"*\", \"https://*\" and \"http://*\" are all treated as wildcard origin patterns by an HTTP API and would make the API callable from any page -- and so are a path, query, fragment, trailing slash or userinfo component, none of which an Origin header carries."
  }

  # WHY : Assumptions: an ORIGIN is a scheme, a host and an optional port, and
  #       nothing else -- no path, no trailing slash, no query, no wildcard
  #       anywhere. A browser compares the request's origin against this value
  #       byte for byte, so `https://example.cloudfront.net/` with its trailing
  #       slash never matches the origin `https://example.cloudfront.net` and
  #       every preflight fails. That failure appears only in a browser, so a
  #       curl reproduction of the same request still succeeds and the
  #       configuration reads as correct; refusing the malformed shape at plan
  #       time is the only place it is cheap to find.
  #       Assumptions: cleartext http is admitted for loopback ONLY, on the same
  #       reasoning as the callback validation in infra/modules/cognito: a
  #       developer running the SPA locally has no certificate, while any other
  #       cleartext origin would let a token-bearing request be read in transit.
  #       The loopback host is matched exactly rather than by prefix, because a
  #       prefix test also admits attacker-controlled names that merely BEGIN
  #       with localhost, such as http://localhost.example.com.
  #       Trade-offs: a subdomain wildcard such as https://*.example.com is
  #       refused outright even though API Gateway would store it. Accepted: a
  #       wildcard origin is honoured by no browser on a credentialed request, so
  #       storing one produces a configuration that looks permissive and behaves
  #       as if the origin were absent.
  validation {
    condition = alltrue([
      for origin in var.spa_cors_allow_origins :
      can(regex("^https://[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+(:[0-9]{1,5})?$", lower(origin)))
      || can(regex("^http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?$", lower(origin)))
    ])
    error_message = "Each spa_cors_allow_origins entry must be an exact origin: https:// followed by a dotted host name and an optional port, or http://localhost or http://127.0.0.1 with an optional port for local development. A path, a trailing slash, a wildcard host or any other cleartext host is refused."
  }

  # WHY : Assumptions: duplicates are not harmless here. API Gateway stores the
  #       list as given, so a repeated origin makes the plan and the console
  #       disagree with the reviewed inventory, and it hides the more likely
  #       mistake behind it -- two entries differing only in case, which the
  #       browser treats as one origin and a reader as two distinct ones. The
  #       comparison is case-insensitive because an origin's scheme and host are.
  validation {
    condition     = length(distinct([for origin in var.spa_cors_allow_origins : lower(origin)])) == length(var.spa_cors_allow_origins)
    error_message = "The spa_cors_allow_origins list must not repeat an origin, including two entries differing only in letter case: a browser treats those as one origin."
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
  #       (2) Assumptions: OPTIONS is listed alongside the methods the SPA
  #       issues because the preflight exchange is itself an OPTIONS request.
  #       Listing it keeps the advertised set and the methods actually seen at
  #       the edge the same, so a reader comparing access logs against this
  #       list finds no unexplained method.
  validation {
    condition     = length(var.cors_allow_methods) > 0
    error_message = "The cors_allow_methods list must hold at least one method, otherwise no cross-origin request from the SPA can pass its preflight."
  }

  # WHY : Assumptions: the enumeration argued for above has to be enforced to be
  #       real. `["*"]` is accepted by API Gateway as allow-all, so without this
  #       check the documented reasoning could be reversed by one tfvars entry,
  #       and the result would advertise methods no service implements. The
  #       method set is closed and small, so listing it here costs nothing and
  #       also catches a lowercase or misspelled verb, which a browser would
  #       simply never match.
  validation {
    condition = alltrue([
      for method in var.cors_allow_methods :
      contains(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"], method)
    ])
    error_message = "Each cors_allow_methods entry must be one of GET, POST, PUT, PATCH, DELETE, HEAD or OPTIONS, in upper case. \"*\" is refused: it advertises methods no migrated service implements, so a preflight would pass for a request the service then rejects with 405."
  }

  validation {
    condition     = length(distinct(var.cors_allow_methods)) == length(var.cors_allow_methods)
    error_message = "The cors_allow_methods list must not repeat a method; a duplicated verb makes the advertised set disagree with the reviewed inventory without changing behaviour."
  }
}

variable "cors_allow_headers" {
  description = "Request headers a browser may send cross-origin. Becomes `cors_configuration.allow_headers`; every header the SPA sets on an authenticated JSON request has to appear here or the browser withholds the request after the preflight."
  type        = list(string)
  default     = ["authorization", "content-type", "x-correlation-id"]
  nullable    = false

  # WHY : (1) Assumptions: `authorization` is not optional. The SPA
  #       authenticates by putting a bearer token in that header, and a header
  #       absent from this list is withheld by the browser -- so omitting it
  #       does not weaken authentication, it makes every authenticated request
  #       from the browser fail its preflight while a curl reproduction of the
  #       same request still succeeds. That asymmetry is a failure a reader
  #       should not have to discover empirically.
  #       (2) Assumptions: `content-type` is required because a JSON body makes
  #       a request non-simple, so every POST and PUT the SPA issues carries it
  #       and triggers a preflight that checks for it.
  #       (3) Assumptions: `x-correlation-id` is the client-supplied correlation
  #       identifier common-lib's CorrelationIdFilter reads on the way in and
  #       echoes on the way out. Without it here the browser cannot send one,
  #       so a trace begins at the edge and the client's own identifier never
  #       joins it -- the request is still served, which is what makes the
  #       omission easy to miss.
  validation {
    condition     = length(var.cors_allow_headers) > 0
    error_message = "The cors_allow_headers list must hold at least one header; it has to include the authorization header for any authenticated browser request to be sent."
  }

  # WHY : Assumptions: `"*"` must be refused rather than discouraged. API Gateway
  #       accepts it, and a browser ignores it on a credentialed request, so a
  #       wildcard here does not widen what the SPA may send -- it silently
  #       NARROWS it to the CORS-safelisted headers, which excludes
  #       authorization. Every authenticated request from the browser then fails
  #       its preflight while the configuration reads as maximally permissive,
  #       which is the most misleading state this input can be left in.
  #       Assumptions: the authorization header is required outright, for the
  #       reason given above: it is the one header without which no authenticated
  #       browser request is sent at all. Header names are compared lowercased
  #       because HTTP header names are case-insensitive and API Gateway echoes
  #       this list verbatim.
  #       Trade-offs: a header shape check is applied as well, which refuses names
  #       carrying spaces or separators that are not valid in a token. It rejects
  #       a typo at plan time rather than letting the browser drop the header and
  #       leave a request failing for a reason nothing reports.
  validation {
    condition = alltrue([
      for header in var.cors_allow_headers :
      can(regex("^[a-z0-9!#$%&'*+.^_`|~-]+$", lower(header)))
    ])
    error_message = "Each cors_allow_headers entry must be a single HTTP header name made of token characters. \"*\" is refused: browsers ignore a wildcard on credentialed requests, so it would silently withhold the authorization header instead of permitting every header."
  }

  validation {
    condition     = contains([for header in var.cors_allow_headers : lower(header)], "authorization")
    error_message = "The cors_allow_headers list must include the authorization header: the SPA authenticates with a bearer token in that header, and a header absent from this list is withheld by the browser, so every authenticated request would fail its preflight."
  }

  validation {
    condition     = length(distinct([for header in var.cors_allow_headers : lower(header)])) == length(var.cors_allow_headers)
    error_message = "The cors_allow_headers list must not repeat a header name, including two entries differing only in letter case: HTTP header names are case-insensitive."
  }
}

variable "cors_max_age_seconds" {
  description = "Upper bound, in seconds, on how long a browser may cache this API's preflight response. Becomes `cors_configuration.max_age`."
  type        = number
  default     = 300
  nullable    = false

  # WHY : (1) Trade-offs: a bound is set rather than left unset. Unset, a
  #       browser re-runs the preflight for each non-simple request pattern, so
  #       every POST and PUT from the SPA costs two round trips instead of one.
  #       Caching removes the second; the cost is that a change to the allowed
  #       headers or methods is not seen by a browser still holding a cached
  #       preflight until that copy is discarded.
  #       (2) Trade-offs: the chosen magnitude keeps that window narrow. A bound
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

  # WHY : (1) Assumptions: an input rather than a constant because retention is
  #       explicitly one of the levers the two environment roots vary -- they
  #       are specified as identical in topology and different only in sizing
  #       and retention -- so a constant here would either over-retain in dev
  #       or under-retain in prod.
  #       (2) Assumptions: the accepted set is enumerated rather than
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

  # WHAT: nullable, defaulting to null -- the single deliberate exception to
  #       this file's `nullable = false` policy.
  # WHY : (1) Alternatives Considered: making the key ARN required. Rejected
  #       because it would make a provisioned customer-managed key a
  #       precondition for standing up the edge at all, so the module could not
  #       be exercised in an environment where the `kms` module has not run.
  #       Null is a meaningful value here rather than a missing one: main.tf
  #       resolves it conditionally and the group falls back to the
  #       service-managed key, which still encrypts the log data at rest.
  #       (2) Trade-offs: the cost is that omitting the key succeeds quietly, so
  #       an environment intended to use a customer-managed key can be applied
  #       without one and nothing fails. Accepted because the environment roots
  #       pass the key explicitly, and the alternative blocks every use of the
  #       module without one. This is the same nullable-with-null-default shape
  #       infra/bootstrap/variables.tf uses for its state-encryption key.
}

variable "throttling_burst_limit" {
  description = "Token-bucket depth applied by default to every route on the stage: how many requests above the steady rate the edge absorbs before it starts rejecting. Shields the seven online services behind it from one client's spike."
  type        = number
  default     = 200
  nullable    = false

  # WHY : (1) Trade-offs: throttling is configured here rather than left to the
  #       account-level default. That default is a per-account, per-region
  #       allowance shared by every API in the account, so relying on it means
  #       one client's burst against this API draws down an allowance the rest
  #       of the account shares, and nothing in this configuration records what
  #       the edge is willing to pass. Setting it makes the limit a property of
  #       this stage and a reviewable line in a plan. The cost is that a
  #       genuine load increase now needs a value change here, which is
  #       accepted because that change is also the record of the decision.
  #       (2) Assumptions: setting it is what makes the throttling check in the
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

  # WHY : (1) Trade-offs: burst and rate are two knobs rather than one because
  #       they describe different things -- rate is how fast the bucket
  #       refills, burst is how deep it is. A single number could not express
  #       "absorb a short spike without raising the sustained ceiling", and the
  #       SPA issues several requests per screen turn, so a screen that loads a
  #       list plus its lookups needs a depth above the steady rate to avoid
  #       being throttled during one user's ordinary navigation.
  #       (2) Assumptions: both defaults are starting bounds an environment root
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

  # WHY : (1) Trade-offs: on by default even though it costs more than the
  #       aggregates, which are emitted either way. Per-route dimensions are
  #       billed per metric, so the charge scales with the number of route keys
  #       -- fourteen by default here, a bare and a greedy key for each of the
  #       seven online contexts -- times the metrics each emits. Accepted
  #       because centralized logging, metrics and tracing are a cross-cutting
  #       requirement of this migration, and without per-route dimensions an
  #       elevated 5XX rate is visible only as one API-wide number: it says the
  #       edge is failing without saying which service is, which is the first
  #       question asked.
  #       (2) Assumptions: it is an input, so an environment that prefers the
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

  # WHY : (1) Assumptions: the bound is load-bearing rather than decorative. The
  #       decision to adopt no circuit-breaker library rests on the synchronous
  #       hops being in-VPC behind an internal load balancer WITH BOUNDED
  #       TIMEOUTS, so this is one of the bounds that argument depends on. Left
  #       unbounded, a stalled upstream would hold edge connections instead of
  #       failing, and the reasoning for having no breaker would no longer
  #       hold.
  #       (2) Trade-offs: the default sits just inside the ceiling the field
  #       accepts rather than well below it, which makes the edge the LAST
  #       component to give up. A service that is slow but does answer
  #       therefore returns its own structured error body; a tighter edge bound
  #       would replace that body with a generic gateway timeout and discard
  #       the diagnosis the service had already produced. A root wanting the
  #       edge to fail ahead of its services lowers this value.
  #       (3) Assumptions: the validated range is the one the integration field
  #       accepts, a 50-millisecond floor up to a 30-second ceiling. A value
  #       above the ceiling is refused by the API during apply, so checking it
  #       here converts a mid-apply rejection into a plan-time message.
  validation {
    condition     = var.integration_timeout_milliseconds >= 50 && var.integration_timeout_milliseconds <= 30000
    error_message = "The integration_timeout_milliseconds value must be between 50 and 30000 inclusive, the range an HTTP API integration timeout accepts."
  }
}

variable "integration_tls_server_name" {
  description = "Server name the private integration verifies against the certificate the internal ALB listener presents. REQUIRED with no default: main.tf always emits the integration's tls_config from it, so every hop from this edge to the load balancer is TLS with the server identity checked."
  type        = string
  nullable    = false

  # WHAT: required and non-null. This variable is no longer an exception to this
  #       file's `nullable = false` policy; `access_log_kms_key_arn` is now the
  #       only one.
  # WHY : Refactoring Rationale: this input previously defaulted to null, and
  #       main.tf read null as "emit no `tls_config` block at all". The reasoning
  #       recorded for that -- that this module cannot see whether the listener's
  #       certificate matches the name the VPC Link resolves, so asserting a name
  #       would be asserting something unknown -- was correct about the NAME and
  #       drew the wrong conclusion about the BLOCK. A private integration with no
  #       TLS configuration does not fall back to TLS without verification; it
  #       sends the request over plain HTTP. Every request crossing that hop
  #       carries the caller's bearer token in an Authorization header, and the
  #       responses carry account, customer and transaction data, so the default
  #       put credentials and personal data in cleartext on the VPC Link for any
  #       root that simply did not set this input -- which, being the default, is
  #       every root that had not thought about it. Requiring the value inverts
  #       that: a root cannot plan without stating the name the certificate
  #       presents, and there is no configuration of this module that produces a
  #       plaintext integration.
  # WHY : Alternatives Considered: keeping the null default and relying on VPC
  #       placement for confidentiality -- the load balancer is internal, the
  #       subnets have no internet route. Rejected because network placement is
  #       not encryption: it bounds WHO can observe the hop, not WHETHER the hop
  #       is readable, and anything with a foothold in the private application
  #       tier (a compromised task, a mirrored interface, a misconfigured flow
  #       destination) reads a token it can then replay. Also considered: a
  #       boolean `enable_integration_tls` alongside the optional name, so the
  #       intent would at least be explicit. Rejected because it offers a
  #       supported way to turn encryption off, and a failure mode reachable by
  #       editing one value is worth removing rather than documenting -- the same
  #       reasoning the `alb` module applies to its own `internal` setting.
  # WHY : Assumptions: the value the caller supplies must be a name the ALB
  #       listener's certificate actually covers. That is a property of the
  #       certificate the `alb` module was given, which this module still cannot
  #       see, so the coupling is real and is unchanged by making the input
  #       required: a name the certificate does not cover fails the handshake and
  #       presents as an integration failure rather than as a naming mismatch. The
  #       difference is that the failure is now a WRONG name rather than a
  #       silently absent protection, and a wrong name is loud.
  # WHY : Trade-offs: the shape check below is a hostname check and deliberately
  #       not a check that the name matches the certificate, which cannot be
  #       expressed here. It catches the two errors an author actually makes -- a
  #       URL pasted in place of a host, and a blank value -- at plan time.
  validation {
    condition     = can(regex("^[A-Za-z0-9*]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$", var.integration_tls_server_name))
    error_message = "The integration_tls_server_name value must be a bare hostname such as \"internal.carddemo.example\" -- no scheme, no port and no path. A leading \"*\" label is admitted because a wildcard certificate legitimately presents one."
  }
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
  #       (2) Assumptions: the environment is already distinguished by
  #       `name_prefix` and `environment` in the API's own name, so a stage
  #       name repeating it would add a path segment encoding something the
  #       resource name already states.
  #       (3) Trade-offs: with one stage there is no path-based way to serve two
  #       revisions of the API side by side behind one endpoint. That matches
  #       the deployment model chosen for this migration -- rolling service
  #       updates rather than blue-green or canary -- so the capability a named
  #       stage would add is one nothing here uses.
  validation {
    condition     = length(trimspace(var.stage_name)) > 0
    error_message = "The stage_name value must not be blank; use \"$default\" for a stage serving requests with no stage path segment."
  }
}
