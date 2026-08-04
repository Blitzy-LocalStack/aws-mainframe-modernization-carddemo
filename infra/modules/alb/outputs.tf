# =============================================================================
# infra/modules/alb/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE public contract of the `alb` module. main.tf's three resources
#   and variables.tf's thirteen inputs are both invisible to a caller: every
#   value that crosses the module boundary OUTWARD crosses it here, and nothing
#   else about the module is visible at all. Seven outputs, and every one of
#   them exists because a named consumer reads it.
#
#   The callers are the two environment roots, infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf, and they do not consume most of these values
#   themselves -- they WIRE them onward. That is why each output below names
#   its real destination rather than stopping at "the root":
#     - infra/modules/api-gateway-http takes `https_listener_arn` as the target
#       of its VPC Link private integration. That is the single seam between
#       the public edge and everything behind it.
#     - the seven load-balanced infra/modules/ecs-service instantiations take
#       `health_check_path`, one identical value each.
#     - infra/modules/observability takes `alb_arn_suffix` as a CloudWatch
#       metric dimension.
#   Renaming an output here is therefore a BREAKING change in three places at
#   once: both environment roots, and the generated Outputs table injected into
#   this module's README.md.
#
# Parameters:
#   None. An `output` block takes no parameters. The module's parameters are
#   the thirteen `variable` blocks in variables.tf, each carrying its own type,
#   description and plan-time validation. This section says so explicitly
#   rather than being left out, because a reader who finds no Parameters
#   heading cannot tell whether it was considered and found inapplicable or
#   simply forgotten.
#
# Returns:
#   Seven values -- six strings and one map of strings. Each carries its own
#   `description`, and those are deliberately not restated here: the Outputs
#   table generated into README.md is produced from the same declarations, so a
#   copy in this header would be a third place to keep in step. Grouped by what
#   produces them:
#     - from aws_lb.this ................... alb_arn, alb_arn_suffix,
#       alb_dns_name and alb_zone_id.
#     - from aws_lb_listener.https ......... https_listener_arn, the
#       load-bearing one.
#     - from aws_lb_listener_rule.service .. listener_rule_arns, a map(string)
#       keyed by service name.
#     - passed through from variables.tf ... health_check_path, the one
#       deliberate re-export of an input.
#
# Errors:
#   This file declares no resource and calls no API, so it has no failure mode
#   of its own. Both failures a consumer can meet arise where the values are
#   USED, and are named here so that meeting one is recognised rather than
#   investigated as a defect in this file:
#     - UNKNOWN AT PLAN TIME. All six resource attributes read below are
#       `computed` -- confirmed against the hashicorp/aws provider schema
#       rather than assumed -- so none of them holds a value until the module
#       has been applied. A caller that feeds one into a position Terraform
#       must resolve while planning, such as a `count`, a `for_each` key or a
#       provider argument, gets "Invalid count argument" or "Invalid for_each
#       argument" instead of a diff. That is a property of reading a
#       not-yet-created resource, not of this file. `health_check_path` is the
#       exception, because it comes from a variable and is known at plan time.
#     - HARD-CODING INSTEAD OF READING. A consumer that writes one of these
#       values as a literal rather than referencing the output produces a
#       configuration that plans and applies cleanly, and that is silently
#       wrong from the moment the load balancer is replaced -- a replacement
#       takes a new ARN, a new DNS name and a new listener ARN. Terraform
#       reports nothing, because a literal is valid HCL. That there is no error
#       message for it is exactly why the prohibition is written down: a module
#       output is the only admissible source of a runtime endpoint or
#       identifier in this tree.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: publishing every attribute the three resources
#     expose, which needs no decisions and is the path of least resistance.
#     Rejected in favour of one test -- an output must have a NAMED consumer.
#     That test is what excludes `aws_lb.this.id`, which for an aws_lb carries
#     the same value as `arn` and gives one value two names to drift between,
#     and what excludes echoing back the certificate, security group, subnets,
#     bucket and tags the caller already holds. `health_check_path` is the
#     single deliberate exception to that no-echo rule, and its reason is
#     recorded on the output itself so the two decisions are not read as
#     contradicting one another.
#   - Alternatives Considered: a composed base URL, assembled from the DNS name
#     with the listener's scheme and port, so a consumer could call a service
#     without building anything. Rejected outright. This load balancer is
#     internal and is reached through the API Gateway VPC Link private
#     integration, so a ready-made URL is an invitation to bypass that edge and
#     with it the Cognito JWT authorizer enforced there. main.tf fixes
#     `internal = true` to make the bypass impossible at the network layer, and
#     withholding the URL keeps this file from undermining the same posture at
#     the configuration layer.
#   - Assumption: nothing here is `sensitive`, and that is a decision rather
#     than a default left in place. Not one of the seven is a credential -- an
#     ARN, an ARN suffix, a DNS name, a hosted-zone id and a request path are
#     all non-secret infrastructure identifiers. Marking them would redact them
#     from `terraform output` and from plan diffs, which is precisely where an
#     operator following the deploy and teardown runbooks confirms the wired
#     values are the intended ones, so it would cost diagnostics and protect
#     nothing. The real secrets in this package never pass through a module
#     output at all -- they are generated at apply time straight into Secrets
#     Manager and read from there.
#   - Assumption: this file is part of a MODULE body and is never applied on
#     its own. It is reached by `terraform validate` transitively through
#     whichever environment root calls the module, and it declares no
#     `provider`, `backend` or `terraform` block -- versions.tf holds the
#     version constraints for the whole module.
# =============================================================================

# -----------------------------------------------------------------------------
# Values produced by aws_lb.this -- the load balancer's own identity.
# -----------------------------------------------------------------------------

# WHAT: the load balancer's ARN, read from the resource rather than composed.
# WHY : Assumption: an elastic-load-balancing ARN embeds both the AWS account
#       identifier and the region. Composing one by interpolation would
#       therefore require both to appear as literals somewhere in this tree,
#       and a twelve-digit account identifier in source is exactly the class of
#       literal this package excludes everywhere -- so reading the attribute is
#       not merely tidier, it is what keeps the account identifier out of the
#       repository entirely.
#       Consumed by the calling environment root, which passes it wherever an
#       alarm target or an IAM policy condition has to name this exact load
#       balancer rather than any load balancer.
output "alb_arn" {
  description = "ARN of the internal Application Load Balancer, identifying it uniquely as an alarm target and in IAM policy conditions."
  value       = aws_lb.this.arn
}

# WHAT: the trailing identifying segments of the ARN, published BESIDE the full
#       ARN rather than instead of it, because the two are not interchangeable.
# WHY : Assumption: CloudWatch identifies a load balancer by the ARN SUFFIX and
#       not by the ARN. The `LoadBalancer` dimension in the AWS/ApplicationELB
#       namespace accepts only those trailing segments, so an alarm or a
#       dashboard widget built from `alb_arn` matches no metric and reports no
#       data -- which presents as a permanently quiet alarm rather than as a
#       wiring error, and a quiet alarm looks exactly like a healthy one.
#       Consumed by infra/modules/observability, which owns the log groups,
#       dashboards and alarms for this package.
#       Alternatives Considered: letting that consumer derive the suffix by
#       slicing `alb_arn`. Rejected because any such slice depends on the
#       internal shape of the ARN, which belongs to the provider and not to us,
#       and the provider already exposes the exact value -- deriving it would
#       reimplement a published attribute less reliably than reading it.
output "alb_arn_suffix" {
  description = "Trailing ARN segments that identify the load balancer to CloudWatch, supplied as the LoadBalancer metric dimension in the AWS/ApplicationELB namespace."
  value       = aws_lb.this.arn_suffix
}

# WHAT: the DNS name AWS assigns the load balancer, published as an output and
#       written as a literal nowhere in this repository.
# WHY : Assumption: this name resolves ONLY inside the VPC. main.tf fixes
#       `internal = true`, so the load balancer is given no internet-routable
#       address and this name answers only to a resolver on the inside. It is a
#       diagnostic value and an alias-record target, NOT the system's entry
#       point -- the entry points are the API Gateway HTTP API in front of
#       these services and the CloudFront distribution in front of the
#       single-page application. Recording that here is what stops a reader
#       from handing the name to a browser or a client configuration and
#       concluding the deployment is broken when it fails to resolve.
#       Trade-off: an output is the ONLY form this value takes, so a consumer
#       must read the module rather than paste a name. The asymmetry is
#       accepted deliberately: a pasted name outlives the load balancer it
#       named and then points at nothing while silently remaining valid
#       configuration, whereas a reference cannot go stale. Consumed by the
#       calling environment root for operator diagnostics, and as the alias
#       target described on `alb_zone_id`.
output "alb_dns_name" {
  description = "VPC-internal DNS name assigned to the load balancer; it resolves only from inside the VPC, because the load balancer is internal."
  value       = aws_lb.this.dns_name
}

# WHAT: the canonical hosted-zone id of the load balancer -- the other half of
#       what a Route 53 alias record needs, alongside `alb_dns_name`.
# WHY : Assumption: NO aws_route53_record exists at any layer of this package,
#       and that absence is deliberate rather than pending. api-gateway-http
#       reaches this load balancer through a VPC Link private integration which
#       targets the listener by ARN and resolves no name at all, so a record
#       would serve no consumer that exists. The zone id is published
#       regardless, so that a caller which later wants a friendly internal
#       name can build the alias itself without this module acquiring ownership
#       of DNS for a record it does not need.
#       Alternatives Considered: (a) omitting the output until something needs
#       it -- rejected because it makes adding an alias a change to the module
#       every environment shares, rather than a change in the one root that
#       wants it; (b) creating the record here -- rejected because it would put
#       DNS ownership inside a load-balancer module, leaving the record and the
#       service it names owned by different layers. Publishing the id and
#       creating nothing leaves both decisions with the caller, which is the
#       environment root.
output "alb_zone_id" {
  description = "Canonical hosted-zone id of the load balancer, required as the alias-target zone of a Route 53 alias record; this module creates no such record."
  value       = aws_lb.this.zone_id
}

# -----------------------------------------------------------------------------
# Values produced by aws_lb_listener.https and aws_lb_listener_rule.service --
# the routing surface, and the seam the public edge attaches to.
# -----------------------------------------------------------------------------

# WHAT: the ARN of the single HTTPS listener. This is the most load-bearing
#       value the module publishes.
# WHY : Assumption: an API Gateway HTTP API private integration over a VPC Link
#       targets a LISTENER, not a load balancer. `alb_arn` cannot stand in for
#       it -- the integration has no way to choose among a load balancer's
#       listeners, so the listener is the addressable unit and its ARN is what
#       has to cross the module boundary. Without this output the public edge
#       cannot be attached to these services at all, which is what makes it the
#       single seam between the API Gateway HTTP API and everything behind it.
#       Consumed by infra/modules/api-gateway-http, wired through the calling
#       environment root.
#       Assumption: there is exactly ONE listener to publish, so this needs
#       neither a key nor a map. main.tf creates no plaintext listener at
#       all -- the sole client is this HTTPS integration -- so `https` in the
#       name records the protocol as a fact about the only listener there is,
#       rather than distinguishing it from a sibling that does not exist.
output "https_listener_arn" {
  description = "ARN of the HTTPS listener, taken as the target of the API Gateway HTTP API private integration over its VPC Link; the only seam between the public edge and the services behind it."
  value       = aws_lb_listener.https.arn
}

# WHAT: the per-service listener rules, as a map from service name to rule ARN.
# WHY : Assumption: the keys are exactly the keys of `var.service_routes`,
#       because main.tf iterates that same map with `for_each`. A caller
#       reading this map therefore learns WHICH bounded contexts are routed,
#       not merely how many rules got created, and variables.tf pins that key
#       set to the seven online contexts -- so the shape is a property of the
#       module rather than of whatever map a caller happens to pass.
#       Alternatives Considered: a plain list of ARNs, which is shorter to
#       write. Rejected for the same reason main.tf iterates with `for_each`
#       over a map instead of `count` over a list: a list position is an index,
#       so inserting or reordering one entry renumbers every later one, and a
#       caller that addressed "the third rule" silently begins addressing a
#       different service. A name is stable, so introducing an eighth context
#       would leave the addressing of the existing seven untouched.
#       Consumed by the calling environment root, which uses it to assert that
#       the routing table it wired is the one that actually exists.
output "listener_rule_arns" {
  description = "ARNs of the per-service listener rules, keyed by service name identically to the service_routes input, so a caller can address one context's rule by name rather than by position."
  value = {
    for service_name, rule in aws_lb_listener_rule.service :
    service_name => rule.arn
  }
}

# -----------------------------------------------------------------------------
# Passed through from variables.tf -- the one deliberate re-export of an input.
# -----------------------------------------------------------------------------

# WHAT: the health-check path, republished unchanged. This is the only output
#       here that reads a variable instead of a resource attribute.
# WHY : Refactoring Rationale: this module creates NO aws_lb_target_group, so
#       there is no resource here for the path to configure -- target groups
#       belong to infra/modules/ecs-service, beside the task definition and
#       task role they scale with, and every health-check argument -- path,
#       protocol, thresholds and matcher -- lives with them.
#       What this module is instead is the DECLARED SINGLE SOURCE OF TRUTH for
#       the contract: variables.tf declares the path once, with its default,
#       and this output hands that one value to the environment root, which
#       feeds the identical string to each of the seven load-balanced
#       ecs-service instantiations and to the health check built into each
#       service image.
#       One declaration, one value in every consumer, and a change to the path
#       is a change in a single place.
#       Assumption: the eighth deployable is NOT among those consumers.
#       batch-service is fronted by no listener rule, creates no target group
#       and exposes no health endpoint at all; it reports its outcome through
#       the task's exit status to the state machine that invoked it. Saying so
#       keeps the count of seven from reading as an off-by-one.
#       Trade-off: this is the sole exception to the rule that no input is
#       echoed back as an output, and the inconsistency is worth accepting
#       because the alternative is the same path written in eight places --
#       this module and the seven service instantiations -- with nothing
#       keeping them equal to each other.
#       Assumption: this output is also what makes `var.health_check_path` a
#       REFERENCED declaration. No resource in the module reads that variable,
#       so were this output deleted the variable would become unused and the
#       `terraform_unused_declarations` rule enabled in infra/.tflint.hcl would
#       fail the build. It is recorded because removing an output that "only
#       returns an input" is an obvious tidy-up with a non-obvious consequence.
output "health_check_path" {
  description = "Path the ecs-service target groups and the container health checks probe, republished from the input so that every load-balanced service is configured from one declaration."
  value       = var.health_check_path
}
