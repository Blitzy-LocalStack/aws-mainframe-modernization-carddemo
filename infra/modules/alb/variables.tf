# =============================================================================
# infra/modules/alb/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE input surface of the `alb` module -- the internal Application
#   Load Balancer that fronts the SEVEN ONLINE Spring Boot services replacing
#   the CICS region's transaction dispatch, its single HTTPS listener, and the
#   per-service listener rules that route to those services. The eighth
#   deployable, batch-service, is not fronted by it at all: its ecs-service
#   instantiation creates no service and no target group, and Step Functions
#   invokes it directly through the synchronous run-task integration. Everything main.tf
#   consumes is declared here, and everything a caller must supply is defined
#   here; the module reads no configuration from anywhere else.
#
#   The callers are the two environment roots, infra/envs/dev and
#   infra/envs/prod, and they WIRE these values rather than author them:
#   `subnet_ids` and `alb_security_group_id` come from the network module, the
#   `target_group_arn` inside each `service_routes` entry from the seven
#   load-balanced ecs-service instantiations, and the certificate ARN plus its
#   verified DNS name from the environment's ACM/DNS authority. The module owns
#   its access-log bucket and delivery policy directly.
#
# Parameters:
#   The module's parameters ARE the `variable` blocks below, and each one
#   carries its own `type` and `description`. That pair IS the parameter
#   documentation and it is deliberately not restated in this header:
#   restating it would create a second copy to keep in step with a third,
#   because the Inputs table generated into README.md -- whose freshness is
#   drift-checked in CI -- is produced from those same declarations.
#
#   Fourteen parameters in two groups, and the groups are the declaration
#   order:
#     - Six REQUIRED, carrying no default, because no safe value can be
#       invented for an identifier only the caller knows: environment,
#       subnet_ids, alb_security_group_id, certificate_arn,
#       certificate_domain_name, service_routes.
#     - Eight DEFAULTED, where a default is both safe and the compliant
#       choice: name_prefix, ssl_policy, access_logs_prefix,
#       legacy_elb_log_delivery_account_arn, enable_deletion_protection,
#       idle_timeout, health_check_path, tags.
#
# Returns:
#   None. A variables file has no return value. What this module publishes to
#   its callers is declared in outputs.tf -- which is also where
#   `health_check_path` is republished, for the reason recorded on that
#   variable.
#
# Errors:
#   The `validation` blocks are this file's error surface. Each fails at PLAN
#   time, before any resource is created and before any API call, and each
#   carries an error_message naming the limit it enforces instead of
#   restating its own condition. They fall into three kinds:
#     - AWS service limits that would otherwise surface as an opaque
#       apply-time rejection: the 32-character load-balancer name, the
#       idle-timeout range, the listener-rule priority range.
#     - Crossed wires between modules, where two different identifiers are
#       both opaque strings: a subnet id passed where a security-group id
#       belongs, or a listener certificate ARN paired with an invalid DNS name.
#     - Invariants AWS cannot check on our behalf: two availability zones
#       actually distinct, listener-rule priorities actually unique.
#
#   One further error is Terraform's rather than this file's -- omitting any
#   of the six required variables fails with "No value for required
#   variable", naming it. That is precisely what carrying no default buys.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: `internal` is NOT an input, and main.tf fixes it
#     to true -- an internet-facing load balancer, and a boolean letting the
#     caller choose, were both evaluated and rejected. The only public entry
#     points in the target architecture are the API Gateway HTTP API, which
#     enforces a Cognito JWT authorizer at the edge, and the CloudFront
#     distribution in front of the single-page application; this load balancer
#     sits behind that HTTP API through a VPC Link. Reaching a service
#     directly would skip the authorizer entirely, and expressed as a BOOLEAN
#     that outcome is one wrong line in a tfvars file away, with every online
#     service exposed and nothing in the module able to detect it. A failure
#     mode reachable by editing one value is worth removing rather than
#     documenting, so the choice is not offered.
#     Lineage: every one of the eighteen DEFINE TRANSACTION stanzas in
#     app/csd/CARDDEMO.CSD (L306-L488) carries RESSEC(NO) CMDSEC(NO), so the
#     baseline ran no per-transaction resource or command security check at
#     dispatch. Centralising authentication at a single managed edge is the
#     decision that closes that gap, and it holds only while the load balancer
#     behind it is unreachable from the internet.
#   - Alternatives Considered: no HTTP-listener input -- a plaintext listener
#     redirecting to HTTPS was evaluated and rejected, so no
#     `create_http_listener`, `http_port` or `redirect_http_to_https` variable
#     exists and main.tf creates no such listener. The sole client is the API
#     Gateway private integration over the VPC Link, which is configured for
#     HTTPS, so a plaintext listener would carry no traffic while still
#     accepting connections. It also means the policy scan's checks against
#     plaintext load-balancer listeners pass BY CONSTRUCTION rather than by a
#     suppression a reviewer would have to adjudicate.
#   - Assumptions: no `vpc_id` -- an aws_lb takes no VPC; it derives one from
#     its subnets. The resource that does need a VPC is aws_lb_target_group,
#     and this module owns none: target groups belong to the ecs-service
#     module, beside the task definition, task role and log group they scale
#     with. Declaring `vpc_id` here would leave an input no resource reads,
#     which the tflint configuration reports as
#     terraform_unused_declarations and fails the build on. It is recorded as
#     a decision because nearly every load-balancer example declares one, so
#     its absence reads as an oversight until explained.
#   - Assumptions: no port, protocol, CIDR, ingress or egress input -- the
#     load-balancer security group is created by network and only CONSUMED here.
#     api-gateway-http adds the exact SG-referenced VPC-Link-to-ALB 443 ingress
#     because it is the only module that can see both endpoints without a
#     dependency cycle. This module declares no rule and exposes no arbitrary or
#     unrestricted range.
#   - Trade-offs: declaration order is required-then-defaulted rather than
#     alphabetical. Alphabetical is the obvious alternative and is easier to
#     scan for one known name, but it interleaves the inputs a caller MUST
#     supply with the ones it may ignore, so the minimum call cannot be read
#     off the file. Grouping puts that minimum first. The cost is that a
#     reader hunting a single name may look in two places, and the generated
#     Inputs table in README.md gives them a sorted view anyway.
#   - Assumptions: nothing here is marked `sensitive`, because none of these inputs
#     carries a secret. The certificate is referenced by ARN and domain name
#     while its private key never leaves ACM, and no password, token or key
#     material is accepted at all.
#     `sensitive` is therefore withheld deliberately -- applying it would
#     redact these values from plan output, which is exactly where a reviewer
#     confirms that the subnets and routes are the intended ones.
#   - Assumptions: the hop OUT of this load balancer is encrypted too, and this
#     module is not where that is configured. Traffic arrives on the single
#     HTTPS listener under the TLS 1.2 floor `ssl_policy` pins, and it leaves
#     for a task over HTTPS as well, because the ecs-service module fixes its
#     target group and health check at `target_protocol = "HTTPS"` and each
#     service terminates TLS on 8080 through the `server.ssl` block in its
#     application.yml. So the encrypted path runs viewer to edge to load
#     balancer to task, with no cleartext segment anywhere -- which matters
#     because the segment this module hands off to crosses the private
#     application subnets carrying the Authorization header and account, card
#     and transaction data, and being inside a VPC bounds who can observe that
#     traffic without making it unreadable.
#     Recorded HERE, in the module that owns the listener, because this is
#     where a reader looks to find out how far encryption reaches and it is the
#     one thing about the path this file cannot show them: `service_routes`
#     names target groups it does not create, so the protocol of the onward hop
#     is invisible from this module even though the module is the reason the
#     hop exists. Two wrong conclusions are available without this note --
#     that terminating TLS at the listener is the end of the requirement, or
#     that this module should own the target groups so it can set their
#     protocol -- and the second would split target-group ownership across two
#     modules, which the `vpc_id` note above rejects for the same reason.
#     Trade-offs: an Application Load Balancer does not verify the certificate a
#     target presents, so the onward hop is encrypted but not mutually
#     authenticated. That residual is accepted because target registration is
#     controlled by the ecs-service module and the load-balancer security group
#     admits nothing else, and it is stated so that HTTPS on the back end is
#     not read as more than it is.
# =============================================================================

# -----------------------------------------------------------------------------
# Required inputs -- no default, because every one of them is an identifier or
# a policy decision that belongs to the calling environment root. Five are
# wired from another module's output; the sixth, `environment`, is the root's
# own name for itself.
# -----------------------------------------------------------------------------

variable "environment" {
  description = "Environment discriminator composed into the load-balancer name and tags, distinguishing one environment's load balancer from another's."
  type        = string

  # WHY : Assumptions: this value is composed into the load-balancer name, and
  #       an aws_lb name accepts only alphanumerics and hyphens and may not
  #       begin or end with one -- which is what the anchors reject. Lowercase
  #       is narrower than AWS requires and is narrowed on purpose: the same
  #       discriminator is composed into S3 bucket names and DNS labels
  #       elsewhere in this package, neither of which admits uppercase, so one
  #       spelling holds across the tree instead of a value that is legal here
  #       and refused two modules away. The pattern also requires at least one
  #       character, so an empty string is rejected by the same check rather
  #       than needing its own.
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.environment))
    error_message = "environment must be lowercase alphanumerics with interior hyphens only, and must neither begin nor end with a hyphen."
  }

  # WHY : Assumptions: main.tf composes the load-balancer name from this value
  #       and `name_prefix`, and an aws_lb name is capped at 32 characters, so
  #       the cap binds on the PAIR rather than on either value alone -- 32
  #       less the four characters of the "-alb" suffix and one separator
  #       leaves 27 to share. Asserting it here, where both values are in
  #       scope, turns an apply-time rejection arriving after the plan was
  #       approved into a plan-time message naming the budget.
  #       Alternatives Considered: capping each value independently at some
  #       fraction of 27, which needs no cross-variable reference. Rejected --
  #       every split either forbids a long prefix paired with a short
  #       environment name that would in fact fit, or admits a pair that does
  #       not. Cross-variable references in a `validation` condition are
  #       accepted from Terraform 1.9 onward and versions.tf floors the CLI at
  #       1.15.0, so the exact check is available; it was confirmed against
  #       CLI 1.15.8 rather than assumed.
  validation {
    condition     = length(var.name_prefix) + length(var.environment) <= 27
    error_message = "name_prefix and environment together must not exceed 27 characters, so the composed load-balancer name fits the 32-character aws_lb limit."
  }
}

variable "subnet_ids" {
  description = "Ids of the subnets the load balancer places its nodes in, one per availability zone, supplied from the network module."
  type        = list(string)

  # WHY : Assumptions: the module is tier-agnostic. It places the load balancer
  #       in whatever subnets the caller passes and asserts nothing about
  #       which tier they belong to. In this package the caller passes the
  #       public subnets, which carry only the load balancer and the NAT
  #       gateways, and that is NOT in tension with the load balancer being
  #       internal: `internal = true` in main.tf is what withholds public
  #       addresses and the internet-routable name, and it does so whatever
  #       the subnets' route tables say. Reading "public subnets" and
  #       "internal" together as a contradiction is the expected mistake, so
  #       the reconciliation is recorded here instead of left to be
  #       re-derived.
  validation {
    condition     = length(var.subnet_ids) >= 2
    error_message = "subnet_ids must contain at least two subnet ids, because an Application Load Balancer requires two or more availability zones."
  }

  # WHY : Trade-offs: a second condition for what looks like the same check,
  #       accepted because the first one can pass while delivering nothing it
  #       exists to guarantee. Two copies of one id satisfy "at least two" and
  #       still land every node in ONE zone, so the count only means what it
  #       appears to mean once the ids are known to differ.
  validation {
    condition     = length(distinct(var.subnet_ids)) == length(var.subnet_ids)
    error_message = "subnet_ids must not repeat a subnet id; duplicates satisfy the two-subnet minimum while leaving the load balancer in a single availability zone."
  }
}

variable "alb_security_group_id" {
  description = "Id of the load-balancer security group created and owned by the network module; attached to the load balancer and never modified here."
  type        = string

  # WHY : Assumptions: the permitted traffic matrix is fixed for the whole
  #       package. The network module creates this group and api-gateway-http
  #       adds only the SG-referenced VPC Link ingress on 443. This module only
  #       ATTACHES it -- no security-group resource and no port, protocol or
  #       CIDR input -- so there is no path through this interface by which the
  #       group could be widened or opened to unrestricted ingress.
  #       Assumptions: the network module publishes several opaque identifier
  #       strings -- subnet ids, a VPC id and this group id -- and Terraform
  #       type-checks all of them as `string`, so a crossed wire between two
  #       of its outputs is invisible until the API rejects it mid-apply. The
  #       prefix is the one part of the id that distinguishes them, so
  #       checking it converts that into a plan-time message. Only the prefix
  #       is checked: the body is 8 or 17 hexadecimal characters depending on
  #       when the group was created, so pinning a length would reject valid
  #       ids.
  validation {
    condition     = can(regex("^sg-", var.alb_security_group_id))
    error_message = "alb_security_group_id must be a security group id beginning with \"sg-\"; a subnet or VPC id from the same module is the usual mistake."
  }
}

variable "certificate_arn" {
  description = "ARN of the ACM certificate the HTTPS listener presents, provisioned and validated outside this module."
  type        = string
  nullable    = false

  # WHY : Assumptions: the certificate is issued and its domain validated
  #       outside this module, and only its ARN crosses the boundary -- the
  #       private key never leaves ACM, so nothing secret is passed here and
  #       the value needs no `sensitive` marking. It is an input rather than a
  #       literal for a second reason that is not about reuse: a certificate
  #       ARN embeds an account identifier and a region, and neither may
  #       appear anywhere in this tree.
  #       Refactoring Rationale: a complete shape check is retained at the
  #       module boundary because the environment root exposes this as an
  #       operator-supplied non-secret input. A listener accepts only an ACM
  #       certificate ARN in the same region; a crossed KMS or CloudFront ARN
  #       is otherwise rejected only after the load balancer exists.
  validation {
    condition     = can(regex("^arn:[a-z0-9-]+:acm:[a-z0-9-]+:[0-9]{12}:certificate/[0-9a-f-]+$", var.certificate_arn))
    error_message = "certificate_arn must be a complete regional ACM certificate ARN of the form arn:<partition>:acm:<region>:<account-id>:certificate/<id>."
  }
}

variable "certificate_domain_name" {
  description = "Bare DNS name covered by certificate_arn and verified by the API Gateway private integration when it connects to this HTTPS listener."
  type        = string
  nullable    = false

  # WHY : Assumptions: the ALB listener consumes the ARN while API Gateway must
  #       verify a DNS name, and Terraform cannot derive the latter from the
  #       former. Requiring both values makes that cross-module TLS prerequisite
  #       explicit instead of leaving the private integration to guess.
  validation {
    condition     = can(regex("^[A-Za-z0-9*]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$", var.certificate_domain_name))
    error_message = "certificate_domain_name must be a bare DNS name covered by the listener certificate, with no scheme, port or path."
  }
}

variable "service_routes" {
  description = "Per-service listener rules keyed by service name; each entry gives the rule priority, the path patterns to match and the target group to forward to. Keyed by the SEVEN online bounded contexts and only those: batch has no target group to forward to."

  # WHY : Assumptions: the environment root supplies one entry per bounded
  #       context -- eight of them: auth, account, card, transaction,
  #       reference, batch, authorization and reporting -- and each
  #       `target_group_arn` is the target-group output of that context's
  #       ecs-service instantiation. main.tf iterates this map with `for_each`,
  #       so the map KEY becomes each listener rule's resource key and appears
  #       in plan output as the service name.
  #       Refactoring Rationale: batch is NOT one of them, and the count here is
  #       seven rather than eight for a mechanical reason rather than a policy
  #       one. The batch instantiation of infra/modules/ecs-service is created
  #       with attach_load_balancer and create_service both false, so it produces
  #       no target group at all and its target-group output is null. A batch
  #       entry in this map could therefore only carry a null or a fabricated
  #       ARN: the null fails at apply while creating a listener rule, and a
  #       fabricated one creates a rule that resolves to nothing. Batch is
  #       invoked exclusively by the Step Functions synchronous run-task
  #       integration, which does not traverse this load balancer, so there is
  #       nothing for a rule to route to. The edge modules agree by construction:
  #       infra/modules/api-gateway-http publishes no /batch route either.
  #       Alternatives Considered: (a) hard-coding the seven service names and
  #       their path patterns inside the module: rejected because a routing
  #       change would then be a change to the module every environment
  #       shares, and the module would stop being reusable; routing policy
  #       belongs in the environment root beside the rest of the wiring. What is
  #       fixed inside the module instead is the SET of keys, which is topology
  #       rather than policy and must be identical in dev and prod.
  #       (b) list(object(...)) instead of a map: rejected because `for_each`
  #       over a list keys each rule by its INDEX, so reordering the entries
  #       shifts every later index and Terraform plans to destroy and recreate
  #       rules that did not change. A map key is stable, so a routing edit
  #       touches one rule and nothing else.
  type = map(object({
    priority         = number
    path_patterns    = list(string)
    target_group_arn = string
  }))

  # WHY : Assumptions: an empty map satisfies the type constraint and produces a
  #       listener with no per-service rule at all, which plans and applies
  #       cleanly. Without this check the one failure that looks like success
  #       -- a load balancer that resolves but carries no service traffic --
  #       would reach apply unremarked.
  validation {
    condition     = length(var.service_routes) > 0
    error_message = "service_routes must contain at least one route; routing to the services is the whole purpose of this module."
  }

  # WHY : Assumptions: the KEY SET is topology, not policy, and the AAP fixes it
  #       at seven online bounded contexts. dev and prod are required to differ
  #       only in sizing and retention, so a root that routed six services, or
  #       eight, would produce a load balancer whose reach differs between the
  #       environment a change is tested in and the one it runs in -- and the
  #       missing one presents as a 404 from the edge for a service that is
  #       running and healthy. Set equality is asserted in both directions here:
  #       a missing key and an unexpected key are equally wrong.
  #       Trade-offs: this closes an input the earlier revision left open, so
  #       standing up a genuinely new bounded context now needs a module change
  #       as well as a root change. Accepted, and cheap: a ninth context needs an
  #       ECR repository, a schema, a role and a secret, every one of which is
  #       likewise enumerated in the module that owns it, so this file is not the
  #       place the addition would be noticed.
  #       Assumptions: the batch key cannot appear here at all, for the mechanical
  #       reason recorded above -- it has no target group. The set below is
  #       therefore the seven online contexts exactly.
  #       Assumptions: equality needs both halves of the test. The setunion
  #       comparison alone proves only that every key supplied is one of the
  #       seven, so a root routing a single service would satisfy it; pairing it
  #       with the count closes that, because map keys are already unique, so
  #       seven distinct keys drawn from a set of seven is that set exactly.
  validation {
    condition = length(var.service_routes) == 7 && setunion(keys(var.service_routes), [
      "auth", "account", "card", "transaction", "reference", "authorization", "reporting",
      ]) == toset([
      "auth", "account", "card", "transaction", "reference", "authorization", "reporting",
    ])
    error_message = "service_routes must be keyed by exactly the seven online bounded contexts: auth, account, card, transaction, reference, authorization and reporting. \"batch\" is not among them because its ECS instantiation creates no service and no target group, so no listener rule can forward to it."
  }

  # WHY : Assumptions: a listener rule requires at least one condition, and
  #       `list(string)` is satisfied by the empty list, so the type constraint
  #       alone admits an entry describing a rule that cannot be built.
  validation {
    condition     = alltrue([for route in values(var.service_routes) : length(route.path_patterns) > 0])
    error_message = "every service_routes entry must list at least one path pattern; a listener rule cannot be created without a condition."
  }

  # WHY : Assumptions: the API version travels in the PATH, and this load balancer
  #       sits BEHIND the HTTP API that publishes those paths. Every route key that
  #       edge publishes is under `/api/v1/`, and the integration forwards the
  #       request path unchanged, so a pattern here that omits the prefix can never
  #       match anything the gateway sends. The symptom of that divergence is this
  #       listener's own 404 for a service that is running and healthy, produced by
  #       a routing table that reads as though it covers the service -- so the
  #       check exists to make the two edge layers unable to disagree by
  #       configuration.
  #       Alternatives Considered: rewriting or stripping the prefix at the gateway
  #       so this layer could keep matching unversioned paths. Rejected because
  #       forwarding the path untouched is what lets a reader see, from either
  #       layer alone, exactly which requests reach which service; a rewrite would
  #       split that fact across two places and neither would be authoritative.
  #       Trade-offs: the check pins the MAJOR version this module routes, so
  #       publishing `/api/v2` alongside `/api/v1` needs an edit here as well as at
  #       the gateway. That is the intent rather than the cost: a second live
  #       version is a deliberate act on both edge layers, not something one
  #       environment's tfvars can introduce on one of them only.
  validation {
    condition = alltrue([
      for route in values(var.service_routes) :
      alltrue([for pattern in route.path_patterns : startswith(pattern, "/api/v1/")])
    ])
    error_message = "every service_routes path pattern must begin with \"/api/v1/\", the versioned prefix the api-gateway-http route keys publish; a pattern without it can never match a request forwarded from that edge."
  }

  # WHY : Assumptions: `priority` is supplied by the caller rather than derived
  #       from map ordering, because priorities must be unique on a listener
  #       and are evaluated lowest-first -- they ARE the routing precedence.
  #       Deriving them from the map would silently renumber every existing
  #       rule the moment a service was added or renamed, and for overlapping
  #       patterns that changes which service serves a request.
  #       1 to 50000 is the range a listener rule accepts, and 0 is the value
  #       most likely to be tried as a first priority; it is refused. Checking
  #       the bound here names the offending service at plan time, whereas at
  #       apply the rules are created concurrently and the failure lands after
  #       some of them already exist.
  validation {
    condition     = alltrue([for route in values(var.service_routes) : route.priority >= 1 && route.priority <= 50000])
    error_message = "every service_routes priority must be between 1 and 50000, the range an Application Load Balancer listener rule accepts."
  }

  # WHY : Trade-offs: five separate conditions on one variable rather than one
  #       compound condition, accepted because a compound condition can carry
  #       only ONE error_message -- a caller who duplicated a priority would be
  #       told to check their routes instead of being told which invariant
  #       broke. Uniqueness is the invariant an author is most likely to break,
  #       because every entry is individually valid and only the SET is wrong,
  #       so it cannot be seen by reading any one entry.
  validation {
    condition     = length(distinct([for route in values(var.service_routes) : route.priority])) == length(var.service_routes)
    error_message = "service_routes priorities must be unique; two rules on one listener cannot share a priority."
  }
}

# -----------------------------------------------------------------------------
# Defaulted inputs -- each default is the value that is both safe and
# compliant when a caller says nothing, so an OMISSION never produces a weaker
# configuration than a deliberate choice would. Where an environment root is
# expected to override one, the reason is recorded on that variable.
# -----------------------------------------------------------------------------

variable "name_prefix" {
  description = "Name prefix composed into the load-balancer name and tags, shared with the rest of the CardDemo infrastructure package."
  type        = string

  # WHY : Assumptions: "carddemo" is the name the application already carries
  #       throughout the repository -- every CICS resource in
  #       app/csd/CARDDEMO.CSD is defined GROUP(CARDDEMO), and the load library
  #       it dispatches from is named for it -- so defaulting to it means the
  #       load balancer is findable by the same word as everything else, and a
  #       caller who omits the input gets the package convention rather than an
  #       accident.
  default = "carddemo"

  # WHY : Assumptions: the same charset reasoning as `environment` -- an aws_lb
  #       name admits only alphanumerics and hyphens and forbids a leading or
  #       trailing one -- with lowercase narrowed deliberately, because this
  #       same prefix is composed into S3 bucket names elsewhere in the package
  #       where uppercase is invalid.
  validation {
    condition     = can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.name_prefix))
    error_message = "name_prefix must be lowercase alphanumerics with interior hyphens only, and must neither begin nor end with a hyphen."
  }

  # WHY : Assumptions: 20 leaves at least 7 of the 27-character budget shared
  #       with `environment` for the environment name -- see that variable's
  #       joint check for where 27 comes from. The cap is stated here as well
  #       as there so that an over-long prefix is reported AGAINST THE PREFIX,
  #       rather than as a joint-budget failure that reads as though the
  #       environment name were at fault.
  validation {
    condition     = length(var.name_prefix) <= 20
    error_message = "name_prefix must not exceed 20 characters, leaving room for the environment discriminator and the \"-alb\" suffix within the 32-character aws_lb name limit."
  }
}

variable "ssl_policy" {
  description = "Predefined ELB security policy the HTTPS listener negotiates with. The module accepts only the TLS 1.3 policy with a TLS 1.2 floor because no environment may lower the listener protocol."
  type        = string

  # WHY : Assumptions: this policy negotiates TLS 1.3 with a TLS 1.2 FLOOR, so
  #       it satisfies the policy scan's HIGH-severity check that a
  #       load-balancer listener accepts nothing below TLS 1.2 (checkov
  #       CKV_AWS_103). The default carries more weight than a default usually
  #       does, because omitting the argument does not leave the choice to AWS
  #       neutrally: for every method other than the console the service
  #       default is the 2016-vintage policy, which still accepts TLS 1.0 and
  #       1.1 and fails that same check. Stating the policy explicitly is the
  #       only way the negotiated floor is knowable from the configuration.
  #       Trade-offs: a policy admitting TLS 1.0 or 1.1 was rejected outright
  #       rather than offered for compatibility. The only client is the API
  #       Gateway private integration reaching this listener over the VPC
  #       Link, and it negotiates modern TLS, so there is no legacy client
  #       whose breakage would be the price of refusing them.
  #       Alternatives Considered: accepting any policy whose name appears to
  #       include TLS 1.2. Rejected because the name is not a machine-readable
  #       guarantee about every cipher and protocol the managed policy enables;
  #       admitting a new family is therefore a reviewed module change rather
  #       than a caller-controlled way to bypass the shared floor.
  default = "ELBSecurityPolicy-TLS13-1-2-2021-06"

  validation {
    condition     = var.ssl_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06"
    error_message = "ssl_policy must remain ELBSecurityPolicy-TLS13-1-2-2021-06. The internal listener's TLS 1.2 floor is non-overridable in every environment."
  }
}

variable "access_logs_bucket" {
  description = <<-EOT
    Name of an existing S3 bucket to write load-balancer access logs to. Leave
    null to have this module create and own the bucket, with encryption, a public
    access block, a TLS-only policy and the ELB log-delivery grant. Supply the
    shared terminal bucket published by infra/modules/observability when one stack
    should have a single log destination; both environment roots do.
  EOT
  type        = string
  default     = null

  # WHY : Assumptions: the value is a bucket NAME rather than an ARN, because that
  #       is what the aws_lb access_logs block takes. A shape check is deliberately
  #       limited to the S3 naming rules -- this module cannot prove the bucket
  #       carries the delivery grant ALB validates while enabling logging, and the
  #       owning module's own policy is what does.
  validation {
    condition     = var.access_logs_bucket == null || can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.access_logs_bucket))
    error_message = "The access_logs_bucket must be a valid S3 bucket name of 3 to 63 lower-case characters, or null to have this module create its own."
  }
}

variable "access_logs_prefix" {
  description = "Key prefix under which the load balancer writes access-log objects inside the logging bucket."
  type        = string

  # WHY : Assumptions: this module now owns a dedicated logging bucket, and ELB
  #       still writes beneath `<prefix>/AWSLogs/...`. Keeping an explicit
  #       prefix makes the delivery-policy resource and operator queries share
  #       one visible object root instead of relying on the empty-prefix special
  #       case. Defaulting it means that structure exists when the caller says
  #       nothing.
  default = "alb"

  # WHY : Assumptions: the load balancer supplies its own separator between the
  #       prefix and the rest of the key. A trailing slash therefore produces a
  #       doubled separator in every object key, and a leading one produces an
  #       empty first path segment. Neither fails the write -- what fails,
  #       silently, is the lifecycle rule or the query written against the
  #       intended prefix, which then matches nothing.
  validation {
    condition     = !startswith(var.access_logs_prefix, "/") && !endswith(var.access_logs_prefix, "/")
    error_message = "access_logs_prefix must neither begin nor end with \"/\"; the load balancer supplies the separator between the prefix and the log key."
  }
}

variable "legacy_elb_log_delivery_account_arn" {
  description = "Optional regional ELB service-account root ARN used only where the legacy pre-service-principal access-log delivery model remains required. Null uses the modern logdelivery.elasticloadbalancing.amazonaws.com principal alone."
  type        = string
  default     = null

  # WHY : Alternatives Considered: looking the legacy account up through the
  #       deprecated aws_elb_service_account data source was rejected because it
  #       hides a regional prerequisite inside this module and cannot represent
  #       partitions uniformly. An explicit optional ARN keeps the exceptional
  #       legacy grant visible in the environment root while the modern service
  #       principal remains the default path.
  validation {
    condition     = var.legacy_elb_log_delivery_account_arn == null || can(regex("^arn:[a-z0-9-]+:iam::[0-9]{12}:root$", var.legacy_elb_log_delivery_account_arn))
    error_message = "legacy_elb_log_delivery_account_arn must be null or an IAM account-root ARN of the form arn:<partition>:iam::<account-id>:root."
  }
}

variable "enable_deletion_protection" {
  description = "Whether the load balancer refuses deletion until the protection is cleared."
  type        = bool

  # WHY : Assumptions: true is the value the HIGH-severity policy scan expects
  #       on a load balancer (checkov CKV_AWS_150), and it is the default so
  #       that an OMISSION lands on the safe side -- forgetting this input
  #       costs one deliberate step before a delete, whereas defaulting to
  #       false would make forgetting it indistinguishable from choosing it.
  #       Trade-offs: the dev root overrides this to false in its
  #       terraform.tfvars, which is one of the few differences the two
  #       environments are permitted -- they differ in sizing, retention and
  #       protection flags, never in topology. The reason is concrete rather
  #       than a preference: with protection on, `terraform destroy` fails on
  #       this resource, and tearing the stack down cleanly is an acceptance
  #       criterion for the package. The override therefore lives with the
  #       environment that gets torn down, and the module keeps the protective
  #       value.
  default = true
}

variable "idle_timeout" {
  description = "Seconds the load balancer holds an idle connection open before closing it."
  type        = number

  # WHY : Assumptions: 60 is the service's own default and it is retained
  #       deliberately rather than by inertia. Every route behind this listener
  #       is a synchronous request-and-response API; the long-poll behaviour
  #       this migration preserves belongs to the queue consumers, which reach
  #       SQS directly and never traverse the load balancer. There is therefore
  #       no held-open HTTP request for a higher value to protect, and raising
  #       it would only keep failed connections occupying a node for longer.
  default = 60

  # WHY : Assumptions: 1 to 4000 seconds is the range the load balancer accepts,
  #       and 0 is the value most likely to be tried to mean "no timeout" --
  #       it is refused. Naming the range at plan time is what keeps that from
  #       surfacing as an attribute rejection during creation.
  validation {
    condition     = var.idle_timeout >= 1 && var.idle_timeout <= 4000
    error_message = "idle_timeout must be between 1 and 4000 seconds, the range an Application Load Balancer accepts."
  }
}

variable "health_check_path" {
  description = "Path the target groups and container health checks probe, requested over HTTPS by the ecs-service target groups. Republished by outputs.tf; this module creates no target group."
  type        = string

  # WHY : Assumptions: NO resource in this module reads this value. The module
  #       creates no aws_lb_target_group -- target groups belong to the
  #       ecs-service module, beside the tasks they track -- so this variable
  #       is consumed by outputs.tf alone. It is declared here because the
  #       health-check path has to be ONE value across the seven online services
  #       this load balancer fronts, and republishing it as an output is what
  #       lets the environment root feed the identical string to every one of
  #       those ecs-service instantiations and to the container health check in
  #       each image. The batch instantiation is not among them and takes no
  #       health-check path: it creates no target group, declares neither a web
  #       server nor an actuator, and reports its outcome through the task's
  #       process exit status, which the invoking state machine reads. That output is also what makes
  #       this a referenced declaration rather than an unused one, which the
  #       lint gate would otherwise fail on. This is recorded because the next
  #       reader has exactly two wrong moves available: delete the variable as
  #       unused, or add a target group here and split its ownership across two
  #       modules.
  #       Assumptions: the default is "/actuator/health" because every service
  #       depends on Spring Boot Actuator for precisely this endpoint -- that
  #       health endpoint being consumed by the target group and the container
  #       health check is the reason the dependency is in the build at all.
  #       Assumptions: this is a PATH and carries no scheme, which is what lets
  #       one string serve two consumers that reach it differently. The
  #       ecs-service target groups probe it over HTTPS, per the encrypted-hop
  #       note in this file's header; each image's own HEALTHCHECK probes it
  #       from inside the container. Adding a scheme here would break one of
  #       the two, and it is the reason no protocol input appears beside this
  #       one -- the protocol belongs to whoever creates the target group.
  default = "/actuator/health"

  # WHY : Assumptions: the value is used verbatim as an absolute request path,
  #       so a value such as "actuator/health" is not corrected -- it probes a
  #       path that does not exist, which presents as every target failing its
  #       health check rather than as a configuration error, and sends a
  #       reader looking at the services instead of at this input.
  validation {
    condition     = startswith(var.health_check_path, "/")
    error_message = "health_check_path must begin with \"/\"; a health-check path is absolute."
  }
}

variable "tags" {
  description = "Additional tags merged onto the resources this module creates, beyond the provider-level default tags."
  type        = map(string)

  # WHY : Assumptions: the environment root's provider block already applies
  #       `default_tags` to every resource in the run, and resource-level tags
  #       MERGE with those rather than replacing them. This input therefore
  #       exists for keys a root-level default cannot express -- one naming the
  #       load-balancer tier, for instance, which is true of this module's
  #       resources and of nothing else in the root. Without that distinction
  #       the variable reads as a duplicate of `default_tags`.
  #       Trade-offs: the default is an empty map, so the module contributes no
  #       tag of its own unless asked. Having it default to a tag of the
  #       module's own choosing was the alternative and was rejected, because a
  #       tag added unconditionally cannot be removed by a caller without a
  #       module change, and a key colliding with a `default_tags` key
  #       overrides it silently.
  default = {}
}
