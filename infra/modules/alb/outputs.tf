# =============================================================================
# infra/modules/alb/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE public contract of the `alb` module. main.tf's load-balancing
#   and access-log resources and variables.tf's fifteen inputs are invisible
#   to a caller: every value that crosses the module boundary OUTWARD crosses
#   here, and nothing
#   else about the module is visible at all. Ten outputs, and every one of
#   them exists because a named consumer reads it.
#
#   The callers are the two environment roots, infra/envs/dev/main.tf and
#   infra/envs/prod/main.tf, and they do not consume most of these values
#   themselves -- they WIRE them onward. That is why each output below names
#   its real destination rather than stopping at "the root":
#     - infra/modules/api-gateway-http takes `https_listener_arn` as the target
#       of its VPC Link private integration and `https_server_name` as the
#       certificate identity verified on that hop.
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
#   the fifteen `variable` blocks in variables.tf, each carrying its own type,
#   description and plan-time validation. This section says so explicitly
#   rather than being left out, because a reader who finds no Parameters
#   heading cannot tell whether it was considered and found inapplicable or
#   simply forgotten.
#
# Returns:
#   Ten values -- nine strings and one map of strings. Each carries its own
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
#     - from the resolved access-log bucket .... access_logs_bucket_name and
#       access_logs_bucket_arn.
#     - passed through from variables.tf ... health_check_path, the one
#       health-check contract, plus https_server_name, the certificate identity
#       consumed by API Gateway.
#
# Errors:
#   This file declares no resource and calls no API, so it has no failure mode
#   of its own. Both failures a consumer can meet arise where the values are
#   USED, and are named here so that meeting one is recognised rather than
#   investigated as a defect in this file:
#     - UNKNOWN AT PLAN TIME. All eight resource-derived values read below are
#       `computed` -- confirmed against the hashicorp/aws provider schema
#       rather than assumed -- so none of them holds a value until the module
#       has been applied. A caller that feeds one into a position Terraform
#       must resolve while planning, such as a `count`, a `for_each` key or a
#       provider argument, gets "Invalid count argument" or "Invalid for_each
#       argument" instead of a diff. That is a property of reading a
#       not-yet-created resource, not of this file. `health_check_path` and
#       `https_server_name` are the exceptions because both come from variables.
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
#   - Alternatives Considered: publishing every attribute the resources
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
#   - Assumptions: nothing here is `sensitive`, and that is a decision rather
#     than a default left in place. Not one of the ten is a credential -- an
#     ARN, an ARN suffix, a DNS name, a hosted-zone id, two bucket identifiers,
#     a server name, a map of rule ARNs and a request path are
#     all non-secret infrastructure identifiers. Marking them would redact them
#     from `terraform output` and from plan diffs, which is precisely where an
#     operator following the deploy and teardown runbooks confirms the wired
#     values are the intended ones, so it would cost diagnostics and protect
#     nothing. The real secrets in this package never pass through a module
#     output at all -- they are generated at apply time straight into Secrets
#     Manager and read from there.
#   - Assumptions: this file is part of a MODULE body and is never applied on
#     its own. It is reached by `terraform validate` transitively through
#     whichever environment root calls the module, and it declares no
#     `provider`, `backend` or `terraform` block -- versions.tf holds the
#     version constraints for the whole module.
# =============================================================================

# -----------------------------------------------------------------------------
# Values produced by aws_lb.this -- the load balancer's own identity.
# -----------------------------------------------------------------------------

# WHY : Assumptions: an elastic-load-balancing ARN embeds both the AWS account
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

# WHY : Assumptions: CloudWatch identifies a load balancer by the ARN SUFFIX and
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

# WHY : Assumptions: this name resolves ONLY inside the VPC. main.tf fixes
#       `internal = true`, so the load balancer is given no internet-routable
#       address and this name answers only to a resolver on the inside. It is a
#       diagnostic value and an alias-record target, NOT the system's entry
#       point -- the entry points are the API Gateway HTTP API in front of
#       these services and the CloudFront distribution in front of the
#       single-page application. Recording that here is what stops a reader
#       from handing the name to a browser or a client configuration and
#       concluding the deployment is broken when it fails to resolve.
#       Trade-offs: an output is the ONLY form this value takes, so a consumer
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

# WHY : Assumptions: THIS MODULE creates no aws_route53_record, and the CALLING
#       ROOT does. Both halves are deliberate and together they are the design:
#       DNS ownership sits with the layer that names the service. This output
#       exists precisely so the root can build that alias, and it is consumed --
#       each of infra/envs/dev and infra/envs/prod aliases a VPC-private apex A
#       record at `module.alb.alb_dns_name` with this value as the alias-target
#       zone and evaluate_target_health enabled.
#       Refactoring Rationale: this note previously asserted that "NO
#       aws_route53_record exists at any layer of this package" and that one
#       "would serve no consumer that exists", describing the output as published
#       for a hypothetical future caller. The record exists in both roots and has
#       a live consumer: the internal base URLs the authorization, transaction
#       and account services resolve are built from that name, so
#       service-to-service traffic depends on it. Describing a load-bearing
#       integration as speculative invites a reader to treat this output as
#       unused and remove it -- which would break both roots at plan time.
#       Alternatives Considered: (a) omitting the output -- rejected, and now
#       moot, because a root does consume it; (b) creating the record here --
#       rejected because it would put DNS ownership inside a load-balancer
#       module, leaving the record and the service it names owned by different
#       layers. Publishing the id and creating nothing leaves both decisions with
#       the caller, which is the environment root, and that is what happened.
output "alb_zone_id" {
  description = "Canonical hosted-zone id of the load balancer, required as the alias-target zone of a Route 53 alias record. This module creates no such record by design; each environment root uses this value as the alias-target zone of the VPC-private apex A record that service-to-service calls resolve."
  value       = aws_lb.this.zone_id
}

# -----------------------------------------------------------------------------
# Access-log storage owned by this module.
# -----------------------------------------------------------------------------

output "access_logs_bucket_name" {
  description = "Name of the S3 bucket this module creates for ALB access logs, useful for operator queries and lifecycle integrations without reconstructing the globally-scoped name."

  # WHY : Assumptions: read from the created resource rather than recomposed from
  #       account, region and prefix inputs, so a caller always receives the
  #       actual bucket name even if the naming formula changes.
  value = local.access_logs_target_bucket
}

output "access_logs_bucket_arn" {
  description = "ARN of the module-owned ALB access-log bucket, for exact-resource IAM and observability integrations."

  # WHY : Assumptions: IAM policies consume the ARN while S3 API calls commonly
  #       consume the name. Publishing both avoids callers converting between
  #       two identifiers with different syntaxes.
  value = local.create_access_logs_bucket ? aws_s3_bucket.access_logs[0].arn : null
}

# -----------------------------------------------------------------------------
# Values produced by aws_lb_listener.https and aws_lb_listener_rule.service --
# the routing surface, and the seam the public edge attaches to.
# -----------------------------------------------------------------------------

# WHY : Assumptions: an API Gateway HTTP API private integration over a VPC Link
#       targets a LISTENER, not a load balancer. `alb_arn` cannot stand in for
#       it -- the integration has no way to choose among a load balancer's
#       listeners, so the listener is the addressable unit and its ARN is what
#       has to cross the module boundary. Without this output the public edge
#       cannot be attached to these services at all, which is what makes it the
#       single seam between the API Gateway HTTP API and everything behind it.
#       Consumed by infra/modules/api-gateway-http, wired through the calling
#       environment root.
#       Assumptions: there is exactly ONE listener to publish, so this needs
#       neither a key nor a map. main.tf creates no plaintext listener at
#       all -- the sole client is this HTTPS integration -- so `https` in the
#       name records the protocol as a fact about the only listener there is,
#       rather than distinguishing it from a sibling that does not exist.
output "https_listener_arn" {
  description = "ARN of the HTTPS listener, taken as the target of the API Gateway HTTP API private integration over its VPC Link; the only seam between the public edge and the services behind it."
  value       = aws_lb_listener.https.arn
}

output "https_server_name" {
  description = "Bare DNS name covered by the listener certificate and passed to API Gateway as the private integration's TLS server_name_to_verify value."

  # WHY : Refactoring Rationale: the certificate ARN cannot be decoded into the
  #       DNS identity a TLS client verifies. Re-exporting the caller-supplied
  #       domain gives the environment root one explicit, typed seam between the
  #       ALB certificate contract and API Gateway's tls_config.
  value = var.certificate_domain_name
}

# WHY : Assumptions: the keys are exactly the keys of `var.service_routes`,
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
#       Assumptions: the eighth deployable is NOT among those consumers.
#       batch-service is fronted by no listener rule, creates no target group
#       and exposes no health endpoint at all; it reports its outcome through
#       the task's exit status to the state machine that invoked it. Saying so
#       keeps the count of seven from reading as an off-by-one.
#       Trade-offs: this is the sole exception to the rule that no input is
#       echoed back as an output, and the inconsistency is worth accepting
#       because the alternative is the same path written in eight places --
#       this module and the seven service instantiations -- with nothing
#       keeping them equal to each other.
#       Assumptions: this output is also what makes `var.health_check_path` a
#       REFERENCED declaration. No resource in the module reads that variable,
#       so were this output deleted the variable would become unused and the
#       `terraform_unused_declarations` rule enabled in infra/.tflint.hcl would
#       fail the build. It is recorded because removing an output that "only
#       returns an input" is an obvious tidy-up with a non-obvious consequence.
output "health_check_path" {
  description = "Path the ecs-service target groups and the container health checks probe, republished from the input so that every load-balanced service is configured from one declaration."
  value       = var.health_check_path
}
