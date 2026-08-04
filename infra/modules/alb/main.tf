# =============================================================================
# infra/modules/alb/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The runtime substance of the `alb` module: one INTERNAL Application Load
#   Balancer, its single HTTPS listener, and one listener rule per online
#   bounded context, driven by `var.service_routes`. Together they are the
#   structural successor to the CICS region's transaction dispatch table --
#   the eighteen DEFINE TRANSACTION stanzas at app/csd/CARDDEMO.CSD L306-L488
#   each bind a four-character transaction identifier to a named program, and a
#   path-based listener rule binding a request to a named target group is that
#   same dispatch expressed for HTTP. The correspondence is structural, not a
#   port, and the stanza count is deliberately NOT a route count: CDV1 names
#   PROGRAM(COCRDSEC), for which the baseline ships no source at all, so not
#   every stanza has a migrated target.
#
#   SEVEN rules are created, not eight. `var.service_routes` is validated to
#   exactly the seven online contexts -- auth, account, card, transaction,
#   reference, authorization and reporting -- and batch is absent by
#   construction rather than by policy: its ecs-service instantiation creates
#   neither a service nor a target group, so no rule could forward to it, and
#   the Step Functions synchronous run-task integration invokes it without
#   traversing this load balancer at all.
#
#   Dependency direction is one-way. This file CONSUMES identifiers produced
#   elsewhere -- by the network module, by the seven load-balanced ecs-service
#   instantiations, by ACM and by the module owning the logging bucket -- and
#   calls no module itself. What it deliberately does not create is enumerated
#   in the block below the header, each omission with the module that owns it.
#
# Parameters:
#   Declared in variables.tf with their types, defaults, descriptions and
#   plan-time validation, and not restated here: the Inputs table generated
#   into README.md is produced from those same declarations, so a copy in this
#   file would be a third place to keep in step. This file reads twelve of the
#   thirteen inputs; `health_check_path` is consumed by outputs.tf alone, for
#   the reason recorded against it in the omissions block.
#
#   FOUR of them are wired from another module's output rather than authored by
#   hand, so a wrong value is a mis-wiring in the calling environment root
#   rather than a defect in this file:
#     - `subnet_ids` (list(string)) -- the load-balancer-role subnet ids, from
#       the network module.
#     - `alb_security_group_id` (string) -- the load-balancer security group,
#       created and owned by the network module and only attached here.
#     - `target_group_arn` (string) inside each `service_routes` entry -- from
#       the seven load-balanced ecs-service instantiations.
#     - `certificate_arn` (string) -- from ACM, provisioned and validated
#       outside this package.
#   `access_logs_bucket` (string) is wired the same way, from the module that
#   owns the logging bucket, and is named apart because it carries a bucket
#   NAME rather than an identifier or an ARN.
#
# Returns:
#   HCL has no return value, so the analogue is what outputs.tf publishes from
#   the three resources declared here. Those outputs are declared and described
#   there, not here; what downstream consumers take is:
#     - the load-balancer ARN, DNS name and hosted-zone id, from aws_lb.this.
#       The DNS name is PUBLISHED rather than written into a DNS record, for
#       the reason recorded in the omissions block.
#     - the HTTPS listener ARN, from aws_lb_listener.https. This is the
#       load-bearing one: it is the private-integration target that
#       infra/modules/api-gateway-http attaches to through its VPC Link, so it
#       is the single seam between the public edge and everything behind it.
#     - the listener-rule set, from aws_lb_listener_rule.service, keyed by
#       service name so a caller can assert WHICH contexts are routed rather
#       than only how many.
#     - `health_check_path`, republished unchanged -- again, see the omissions
#       block for why a module that creates no target group publishes it.
#
# Errors:
#   variables.tf rejects malformed and crossed-wire inputs at PLAN time, before
#   any API call. What remains are the failures that can only surface during
#   APPLY, named here so that a reader meeting one recognises it rather than
#   looking for a defect in this file:
#     - fewer than two subnets in DISTINCT availability zones. The count and
#       the duplicate-id check are enforced at plan time, but two ids naming
#       two subnets in the SAME zone satisfy both and are refused by the
#       service, because an application load balancer requires two zones.
#     - a composed load-balancer name over the 32-character `aws_lb` limit.
#       The joint budget check on `name_prefix` and `environment` makes this
#       unreachable through the documented inputs; it is named because that
#       limit is the entire reason the check exists.
#     - a duplicate `priority` across listener rules. Uniqueness within
#       `service_routes` is checked at plan time; a collision with a rule
#       created on the same listener from outside this module is not visible
#       to it.
#     - an access-log bucket whose policy does not admit the regional ELB
#       log-delivery principal. The load balancer cannot grant itself that
#       write, so creation fails on the delivery test.
#     - a certificate that is not ISSUED, or not in the load balancer's own
#       region. A listener cannot present a certificate from another region,
#       and both cases are rejected when the listener is created.
#
# WHY (non-obvious design decisions):
#   - Assumptions: this file is a MODULE body and is never applied on its own.
#     The environment roots call it as source = "../../modules/alb", and it is
#     reached by `terraform validate` transitively through whichever root calls
#     it. It therefore declares no `provider`, no `backend` and no `terraform`
#     block: the provider's region and `default_tags` belong to the caller, and
#     versions.tf holds the version constraints. A provider block here would
#     additionally stop either root from calling this module with `count`,
#     `for_each` or `depends_on`, which versions.tf records against the same
#     omission and reproduced against CLI 1.15.8.
#   - Alternatives Considered: THREE resources and no more. A target group per
#     service is the obvious fourth and belongs to ecs-service instead. The
#     omissions are gathered into one block below rather than scattered as
#     asides, because the failure mode is a later reader adding one back
#     helpfully, and an omission with no owner named reads as an oversight.
#   - Alternatives Considered: ONE listener carrying path rules, rather than a
#     listener per service or host-based conditions. All seven contexts sit
#     behind one internal name and one certificate, so the path is the only
#     discriminator that needs no further infrastructure. A listener per
#     service would need a distinct port per service and a matching
#     security-group rule for each, widening the very matrix the network
#     module owns exclusively.
# =============================================================================

# -----------------------------------------------------------------------------
# What this module deliberately does NOT create, and which module owns each.
#
# Every entry below is something a reader could reasonably expect to find in a
# load-balancer module. Each is absent on purpose, and naming the owner is the
# point of writing them down together.
#
#   aws_lb_target_group -- owned by ecs-service, beside the task definition,
#     task role, log group and autoscaling policy it scales with. The
#     consequence worth stating is that the HEALTH-CHECK attributes live there
#     too, because path, protocol, thresholds and matcher are all target-group
#     arguments. This module nonetheless declares `health_check_path` and
#     outputs.tf republishes it unchanged, so the environment root can feed ONE
#     identical value to all seven ecs-service instantiations and to each
#     image's own container health check. That is what makes this module the
#     declared single source of truth for the health-check contract WITHOUT
#     owning the resource, and it is why the variable is not dead and must not
#     be deleted as unused.
#
#   aws_security_group and aws_security_group_rule -- owned by network. The
#     permitted matrix is fixed for the whole package (load balancer to
#     application on 8080, application to Aurora on 5432, application to
#     interface endpoint on 443) and expressed once, in the module that creates
#     the group. This module only attaches it by id, takes no port, protocol or
#     CIDR input, and names no unrestricted range anywhere, so there is no path
#     through this file by which that matrix could be widened.
#
#   aws_acm_certificate -- provisioned and DNS-validated outside this package;
#     only `certificate_arn` crosses the boundary. A certificate ARN embeds an
#     account identifier and a region, neither of which may appear anywhere in
#     this tree, so an input is the only admissible form.
#
#   aws_s3_bucket and its bucket policy -- owned by the module that creates the
#     logging bucket. A load balancer cannot grant itself the write it needs,
#     so the policy admitting the regional log-delivery principal is a property
#     of the bucket rather than of the producer writing to it.
#
#   aws_route53_record -- created by nobody, at any layer, deliberately.
#     api-gateway-http reaches this load balancer through a VPC Link private
#     integration, which targets the listener by ARN rather than resolving a
#     name, so a record would serve no consumer. The DNS name is published as
#     an output instead of being written down as a literal anywhere.
#
#   aws_wafv2_web_acl_association -- out of scope for this migration. The
#     policy scan's web-application-firewall requirement applies to a
#     PUBLIC-facing load balancer; this one is internal, so the check does not
#     apply to it and is not suppressed. Nothing in this module skips, ignores
#     or excepts a policy check: every check it is subject to is satisfied by
#     configuration.
#
#   Any HTTP listener -- created by nobody. The rationale is recorded on
#     aws_lb_listener.https below and, at its other end, in variables.tf
#     against the absent create_http_listener input.
# -----------------------------------------------------------------------------

locals {
  # WHAT: the load-balancer name, composed from the prefix and the environment
  #       around a fixed "-alb" infix rather than accepted whole as an input.
  # WHY : Assumptions: an `aws_lb` name is capped at 32 characters and may
  #       neither begin nor end with a hyphen, and the cap binds on the PAIR of
  #       values rather than on either alone. variables.tf enforces that budget
  #       at plan time -- `name_prefix` at most 20 characters, and the two
  #       values together at most 27, which is 32 less the four characters of
  #       "-alb" and one separator -- so an over-long name is reported against
  #       the input that caused it instead of surfacing as an apply-time
  #       rejection after the plan was already approved.
  #       Alternatives Considered: accepting the finished name as a single
  #       input. Rejected because the environment discriminator would then be
  #       optional in practice, and two environments could compose one name;
  #       building it here makes the discriminator structural.
  alb_name = "${var.name_prefix}-alb-${var.environment}"

  # WHAT: the module's own tag contribution, with the caller's map applied last.
  # WHY : Assumptions: the calling root's provider `default_tags` block already
  #       carries the account-wide keys, and resource-level tags MERGE with
  #       those rather than replacing them. This map therefore adds only what a
  #       root-level default cannot express -- which tier a resource belongs to,
  #       true of this module's resources and of nothing else in the root. One
  #       key is added and no taxonomy beyond it.
  #       Trade-offs: `var.tags` is merged LAST, so the caller's value for a
  #       colliding key wins. variables.tf rejects a tag the module would add
  #       unconditionally, on the ground that a caller could not then remove it
  #       without a module change; this precedence is what answers that
  #       objection -- the key is contributed by default and remains the
  #       caller's to override or blank.
  tags = merge({ Component = "alb" }, var.tags)
}

resource "aws_lb" "this" {
  # WHY : Assumptions: the composed name and the 32-character limit it is built
  #       to respect are decided once in `locals` above.
  name = local.alb_name

  # WHAT: internal, written as a literal and reachable from no input.
  # WHY : Alternatives Considered: an internet-facing load balancer was
  #       rejected because it would BYPASS THE COGNITO JWT AUTHORIZER THAT API
  #       GATEWAY ENFORCES AT THE EDGE, moving authentication out of a single
  #       managed edge control and into seven separate service configurations.
  #       The only public entry points in the whole target architecture are that
  #       API Gateway HTTP API and the CloudFront distribution in front of the
  #       single-page-application bucket; this load balancer sits behind the
  #       HTTP API through a VPC Link private integration and is never directly
  #       reachable from the internet.
  #       Alternatives Considered: expressing this as a boolean input so a
  #       caller could choose. Rejected because exposing all seven services
  #       would then be one wrong line in a tfvars file away, with nothing in
  #       the module able to detect it. A failure mode reachable by editing a
  #       single value is worth removing rather than documenting, so the choice
  #       is not offered at all; variables.tf records the same decision against
  #       the absent input, and the two are one decision stated at both ends.
  #       Refactoring Rationale: the baseline enforced nothing equivalent at
  #       dispatch. Every one of the eighteen DEFINE TRANSACTION stanzas in
  #       app/csd/CARDDEMO.CSD (L306-L488) carries RESSEC(NO) CMDSEC(NO), so
  #       CICS ran no per-transaction resource or command security check when it
  #       dispatched a transaction to its program. Centralising authentication
  #       at one managed edge is the decision that closes that gap, and it holds
  #       only while everything behind the edge is unreachable without passing
  #       through it.
  #       Assumptions: because this load balancer is internal, the policy scan's
  #       requirement that a PUBLIC-facing load balancer sit behind a
  #       web-application firewall does not apply to it. That is coverage by
  #       construction rather than an exception -- no check is skipped and no
  #       suppression is written anywhere in this module.
  internal = true

  # WHAT: an application load balancer rather than a network load balancer.
  # WHY : Assumptions: routing here is decided per REQUEST PATH, and a path
  #       condition is a layer-7 construct. A network load balancer forwards by
  #       listener port alone and can express no path or host condition at all,
  #       so reproducing this routing table on one would take a listener and a
  #       distinct port per service, plus a matching security-group rule for
  #       each, widening the matrix the network module owns. It also could not
  #       terminate TLS under the negotiated-policy floor the listener pins.
  load_balancer_type = "application"

  # WHAT: the load-balancer-role subnets, exactly as the caller supplies them.
  # WHY : Assumptions: in this package the caller passes the PUBLIC subnets --
  #       the tier that carries only the load balancer and the NAT gateways --
  #       and that is NOT in tension with `internal = true` above. Subnet
  #       placement decides which availability zones the nodes occupy and which
  #       route tables they use; `internal = true` is what withholds the public
  #       addresses and the internet-routable name, and it does so whatever
  #       those subnets' route tables say. Reading "public subnets" and
  #       "internal" together as a contradiction is the expected mistake, so the
  #       reconciliation is recorded at both ends -- here and on the input.
  subnets = var.subnet_ids

  # WHAT: the group created by the network module, attached by id.
  # WHY : Assumptions: the permitted traffic matrix is fixed for the whole
  #       package -- load balancer to application on 8080, application to Aurora
  #       on 5432, application to interface endpoint on 443 -- and it is
  #       expressed once, in the module that creates this group. This module
  #       only ATTACHES it: it declares no aws_security_group and no
  #       aws_security_group_rule, accepts no port, protocol or CIDR input, and
  #       names no unrestricted range anywhere. There is consequently no path
  #       through this file by which that matrix could be widened, which is the
  #       property that owning the group in exactly one module exists to buy.
  security_groups = [var.alb_security_group_id]

  # WHAT: HTTP headers that are not RFC-compliant are discarded here rather
  #       than forwarded to a service.
  # WHY : Assumptions: without this, a header the load balancer itself will not
  #       parse is passed through verbatim, and the load balancer and the
  #       service can then disagree about where one request ends -- which is
  #       precisely the request-smuggling and header-injection path between the
  #       edge and the seven services. Discarding them at the one hop both sides
  #       share removes the disagreement, rather than requiring seven Spring
  #       Boot applications to reject the same malformed input identically. The
  #       policy scan gates this at HIGH severity (checkov CKV_AWS_131), so the
  #       argument is also what keeps that scan clean by configuration.
  drop_invalid_header_fields = true

  # WHAT: deletion protection taken from an input rather than fixed here.
  # WHY : Trade-offs: protection flags are one of the very few axes the two
  #       environments are permitted to differ on -- they differ in sizing,
  #       retention and protection, never in topology -- and the reason is
  #       concrete rather than stylistic: with protection on, `terraform
  #       destroy` fails on this resource, and tearing the stack down cleanly is
  #       an acceptance criterion for the package. The dev root therefore
  #       overrides this to false in its terraform.tfvars while the module keeps
  #       the protective value: variables.tf defaults it to true, which is what
  #       the HIGH-severity policy check expects (checkov CKV_AWS_150), so an
  #       OMISSION lands on the safe side and forgetting the input is never
  #       indistinguishable from deliberately choosing it.
  enable_deletion_protection = var.enable_deletion_protection

  # WHY : Assumptions: the accepted range, and why the service's own default is
  #       retained deliberately rather than by inertia, are recorded on the
  #       input in variables.tf. They are not restated here, because two copies
  #       of one rationale drift apart and the input is where a caller changing
  #       the value looks first.
  idle_timeout = var.idle_timeout

  # WHAT: access logging, enabled unconditionally -- no argument, input or
  #       branch in this module can turn it off.
  # WHY : Trade-offs: a caller-controlled `enabled` flag was considered and
  #       rejected. The policy scan gates load-balancer access logging at HIGH
  #       severity (checkov CKV_AWS_91), so a switchable path would let a caller
  #       produce a non-compliant load balancer merely by omitting an argument,
  #       and this module would have supplied the means. Requiring the bucket
  #       instead makes the compliant configuration the only configuration the
  #       module is able to build.
  #       Refactoring Rationale: the baseline recorded nothing equivalent -- all
  #       eight file resources in app/csd/CARDDEMO.CSD are defined
  #       RECOVERY(NONE) JOURNAL(NO), so no access record existed and none could
  #       be reconstructed after the fact. These logs are the only record of
  #       which caller reached which service.
  #       Assumptions: the bucket already carries the policy admitting the
  #       regional ELB log-delivery principal to write to it. That is a property
  #       of the bucket and belongs to the module that creates it -- a load
  #       balancer cannot grant itself the write -- so an absent policy fails
  #       the delivery test at apply rather than anything declared here.
  access_logs {
    bucket  = var.access_logs_bucket
    prefix  = var.access_logs_prefix
    enabled = true
  }

  # WHY : Assumptions: the merge and its precedence are decided once in `locals`
  #       above, and all three resources read the same map so their tag sets
  #       cannot drift apart.
  tags = local.tags
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn

  # WHY : Assumptions: the port is fixed rather than accepted as an input. The
  #       only client is the API Gateway private integration over the VPC Link,
  #       which targets this listener directly, so a non-default port would
  #       reach the same listener while additionally having to be mirrored in
  #       the load-balancer security group -- a group the network module owns
  #       and this one may not widen. There is therefore nothing an input could
  #       buy here except a second place for the two to disagree.
  port = 443

  # WHAT: HTTPS, and this is the ONLY listener the module creates.
  # WHY : Assumptions: encryption in transit is required end to end, and this is
  #       the hop at which it would most easily be dropped. A plaintext listener
  #       is a HIGH-severity finding on both counts the policy scan makes --
  #       checkov CKV_AWS_2 on a listener that is not HTTPS, and CKV_AWS_378 on
  #       a load balancer using the HTTP protocol. CKV_AWS_378 is a GRAPH check
  #       spanning a listener and the target group behind it, so it returns no
  #       result when this module is scanned alone: the target groups belong to
  #       ecs-service, so the relationship it walks is not present here. It is
  #       named anyway because it does cover aws_lb_listener, and it is the
  #       check that would fire once the two are scanned together.
  #       Alternatives Considered: an additional HTTP listener performing an
  #       HTTP-to-HTTPS redirect, which is the usual way those checks are
  #       satisfied while a plaintext port stays open. Rejected because the sole
  #       client is the API Gateway private integration reaching this load
  #       balancer over its VPC Link, and that integration is configured for
  #       HTTPS -- so a plaintext listener would carry no traffic while still
  #       accepting connections, which is unused attack surface rather than
  #       compatibility. Declining to create one satisfies the
  #       plaintext-listener checks BY CONSTRUCTION rather than by a suppression
  #       a reviewer would have to adjudicate. variables.tf records the same
  #       decision against the absent create_http_listener input; the two are
  #       one decision stated at both of its ends, not two that happen to agree.
  protocol = "HTTPS"

  # WHY : Assumptions: the negotiated floor is knowable only from this argument.
  #       Omitting it would not leave the choice to AWS neutrally -- for every
  #       method other than the console the service default is the 2016-vintage
  #       policy, which still accepts TLS 1.0 and 1.1. The value, and why the
  #       input stays open rather than being constrained to an allow-list, are
  #       recorded on the input in variables.tf, whose default floors
  #       negotiation at TLS 1.2 and so satisfies checkov CKV_AWS_103.
  ssl_policy = var.ssl_policy

  # WHY : Assumptions: the certificate is issued and its domain validated
  #       outside this module, in the load balancer's OWN REGION -- a listener
  #       cannot present a certificate from another region -- and only the ARN
  #       crosses the boundary, so the private key never leaves ACM and nothing
  #       secret is passed here. It is an input for a second reason that is not
  #       about reuse: a certificate ARN embeds an account identifier and a
  #       region, and no ARN literal, account identifier or region name appears
  #       anywhere in this module.
  certificate_arn = var.certificate_arn

  # WHAT: a request matching no listener rule is answered 404 by the load
  #       balancer itself, without reaching any service.
  # WHY : Alternatives Considered: forwarding unmatched requests to a default
  #       service. Rejected because it makes the routing table's coverage
  #       invisible -- a typo in one path pattern would still be served, with a
  #       200, by whichever service happened to be the default, and the only
  #       symptom would be a wrong response body. Answering 404 makes an
  #       unrouted path an explicit outcome a smoke test can assert on.
  #       Refactoring Rationale: this is behaviour preserved rather than
  #       invented. CICS rejected an unrecognised four-character transaction
  #       identifier instead of dispatching it to an arbitrary program, so "no
  #       such route" is the baseline's own answer to the same question.
  #       Assumptions: the body is JSON because every route behind this listener
  #       is a JSON API, so a client parses this response with the same reader
  #       it already uses for a service's own error payload instead of failing
  #       on unexpected HTML. It is built with `jsonencode` rather than written
  #       as an escaped string literal so the quoting cannot be got wrong.
  default_action {
    # WHY : Assumptions: the action TYPE is spelled with a hyphen while the
    #       nested block that configures it is spelled with an underscore. That
    #       asymmetry is the provider's, not a typo: `type` is validated against
    #       an enum of API values, and `terraform validate` rejects
    #       "fixed_response" there outright. It is noted because the two
    #       spellings sit three lines apart and look like an inconsistency to
    #       correct.
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = jsonencode({ error = "not_found" })
      status_code  = "404"
    }
  }

  # WHY : Assumptions: the same map every resource in this file tags with, for
  #       the reason recorded once on `locals` above.
  tags = local.tags
}

resource "aws_lb_listener_rule" "service" {
  # WHAT: one rule per entry in the routing map, keyed by the service name.
  # WHY : Alternatives Considered: `count` over a list of route objects.
  #       Rejected because `count` keys each rule by its INDEX, so inserting or
  #       reordering an entry shifts every later index and Terraform then plans
  #       to destroy and recreate rules whose configuration did not change --
  #       briefly removing routing for services nobody touched. A map key is
  #       derived from the service name and is stable, so a routing edit plans
  #       against exactly the one rule it changes, and that key is also what
  #       appears in plan output and in the resource address a reader debugs by.
  #       Assumptions: variables.tf fixes the key SET at the seven online
  #       bounded contexts and rejects any other key, so the number of rules is
  #       a property of the module rather than of whatever map a caller passes.
  for_each = var.service_routes

  listener_arn = aws_lb_listener.https.arn

  # WHY : Assumptions: priorities must be unique on a listener and are evaluated
  #       lowest-first, so they ARE the routing precedence between two patterns
  #       that could both match a request. The caller supplies them rather than
  #       the module deriving them from map ordering: a derived priority would
  #       silently renumber every existing rule the moment a context was added
  #       or renamed, producing a large diff over rules that did not change and
  #       a window in which two rules contend for one request. variables.tf
  #       checks both the accepted range and uniqueness across the map at plan
  #       time, so a collision is named before any rule exists.
  priority = each.value.priority

  # WHY : Assumptions: the target group is created by this context's ecs-service
  #       instantiation, beside the task definition and task role it scales
  #       with, and only its ARN crosses the boundary -- this module creates no
  #       aws_lb_target_group, for the reason given in the omissions block
  #       above. The onward hop is encrypted as well as this one: those target
  #       groups fix an HTTPS target protocol and each service terminates TLS
  #       itself, so no cleartext segment exists between the edge and a task.
  action {
    type             = "forward"
    target_group_arn = each.value.target_group_arn
  }

  # WHY : Alternatives Considered: host-based conditions instead of path-based.
  #       Rejected because all seven contexts sit behind ONE internal name and
  #       ONE certificate, so the path is the only discriminator that needs no
  #       further infrastructure -- host-based routing would require seven names
  #       and seven certificate subject alternative names, and would put a DNS
  #       change on the critical path of every routing change, for no routing
  #       capability a path condition lacks.
  #       Assumptions: every entry lists at least one pattern, checked at plan
  #       time, because a listener rule cannot be created without a condition.
  condition {
    path_pattern {
      values = each.value.path_patterns
    }
  }

  # WHY : Assumptions: the same map every resource in this file tags with, for
  #       the reason recorded once on `locals` above. Every rule carries the
  #       identical set, so a tag-based cost or access query returns the whole
  #       routing table rather than a subset of it.
  tags = local.tags
}
