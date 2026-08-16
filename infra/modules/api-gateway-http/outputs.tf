# =============================================================================
# infra/modules/api-gateway-http/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The COMPLETE public interface of the reusable `api-gateway-http` module --
#   the single public entry point for the migrated CardDemo services: an API
#   Gateway HTTP API with a Cognito JWT authorizer, reaching the internal ALB
#   over a VPC Link so the private application subnets are never exposed.
#   Whatever is declared here is what the two environment roots and every
#   downstream reader can consume; whatever is omitted is invisible to them.
#
#   Six values, and deliberately no more: the invoke endpoint a client calls,
#   the seven identifiers a caller needs in order to attach something to what
#   this module built, and the inventory of routes this API publishes WITHOUT an
#   authorizer. The resources these values are read from are in main.tf, the
#   inputs that configure those resources are in variables.tf, and the toolchain
#   and provider contract is in versions.tf.
#
#   These nine are also the mechanism behind a property stated for the whole
#   migration -- no service hard-codes an endpoint. `api_endpoint_url` below is
#   the only place the address of this API is produced: the calling environment
#   root writes it to Parameter Store, and the SPA reads it from there through
#   `VITE_API_BASE_URL`, which ui/.env.example:L71 carries as a variable NAME
#   with no value beside it. That file states the same contract from the
#   consuming side -- "supplied per environment from the API Gateway endpoint
#   Terraform emits as an output", at ui/.env.example:L65. Without this output
#   there would be nothing for that deliberately empty variable to be filled
#   from, and the address would have to be written into the repository.
#
# Parameters:
#   Not applicable -- this file declares no `variable` block and nothing here
#   takes an argument. Every value this module consumes arrives as an input
#   that is typed, defaulted where appropriate and described in variables.tf.
#
# Return values:
#   This file IS the module's return values, so the obligation to state the
#   type and description of what is returned lands on the five `description`
#   attributes below rather than on a signature. HCL INFERS an output's type
#   instead of declaring it, so each description carries the whole obligation:
#   what the value is, what kind of thing it is (a URL, or an opaque
#   provider-assigned identifier), which resource attribute it is read from,
#   and who consumes it. TFLint's terraform_documented_outputs rule decides
#   only that a description is PRESENT; whether one is informative it cannot
#   assess, so that half is a review obligation with no machine backing, and
#   each description below is written to answer a caller who cannot see
#   main.tf and should not have to read it.
#
#   The order is most-used-first rather than alphabetical: a caller almost
#   always wants the endpoint, sometimes wants the API id, and rarely wants the
#   stage name, the authorizer id or the VPC Link id.
#   infra/.terraform-docs.yml re-sorts these rows when it generates this module's
#   README, so ordering for the reader of the HCL costs the generated table
#   nothing.
#
# Exceptions / failure modes:
#   Nothing in this file can fail on its own. A module is never applied
#   directly -- it is called as `source = "../../modules/api-gateway-http"` --
#   so these are values of the CALLING root, reached there as
#   `module.api_gateway_http.<name>`, and never of this directory.
#   - Four of the five are not known before the calling root applies the
#     module. A root that references one in the same run that creates the API
#     sees a plan-time UNKNOWN rather than an error, and the value resolves
#     during apply, so a dependent resource can be planned while the endpoint
#     is still unreadable -- ordinary, and not a defect. `stage_name` is the one
#     exception and does resolve at plan time, for the reason recorded at its own
#     block below.
#   - The failure that actually matters is a RENAME. A
#     `module.api_gateway_http.<name>` reference to a name that no longer
#     exists is an unsupported-attribute error at PLAN time in both
#     infra/envs/dev and infra/envs/prod -- two callers, one hard error each,
#     and neither is something a downstream file can work around. Deleting an
#     output does the same. These names are therefore an interface and not an
#     implementation detail, which is the whole reason the set below is kept
#     narrow: an output can be added later, but one that already exists cannot
#     be renamed or withdrawn without breaking every caller at once.
#   - A value read from the WRONG attribute fails in neither place. It plans,
#     applies, and returns a well-formed string that is simply not the address
#     the caller needed. That is why the two attribute choices open to
#     interpretation are justified at their own blocks below instead of being
#     left for a reader to infer.
#
# WHY (non-obvious design decisions):
#   - Alternatives Considered: NO output here is marked `sensitive`, and the
#     decision is written down rather than left implied, because marking
#     outputs sensitive by reflex is the plausible alternative. None of the
#     five is a credential: the endpoint is public by construction -- it is
#     the address a browser is handed -- and the four identifiers name resources
#     rather than granting access to them, so holding one confers nothing that
#     the JWT check at the edge and the IAM policies behind it do not still
#     require. Marking the endpoint sensitive
#     would actively break
#     the documented workflow, because Terraform then redacts it from
#     `terraform output`, which is precisely how an operator reads it to
#     configure the SPA build. Trade-offs: the accepted cost is that these
#     values appear in plan output and in state in the clear; that is the same
#     exposure the browser already has, so redaction would obscure them from
#     the operator without withholding anything from anyone else.
#   - Trade-offs: exactly five outputs, where more could be published. This
#     module also knows its access-log group name and ARN, its VPC Link security
#     group id, the integration id, the API ARN and the API execution ARN, and
#     each is deliberately withheld. The cost accepted is that a caller needing
#     one of those must widen this interface in a later change. The reason for
#     accepting it is that the reverse is not available: every name published
#     here is one two environment roots may come to depend on, and the rename
#     failure above makes removing it a breaking change. A narrow interface stays
#     cheap to widen; a wide one cannot be narrowed. Four names were in fact
#     withdrawn in a later change, which was possible only because a
#     repository-wide search proved no caller referenced any of them -- the note
#     at the foot of this file records that check.
#   - Assumptions: every value below is read from a RESOURCE ATTRIBUTE, never
#     from a `var.*` and never from a literal. A resource attribute is what
#     the provider reports for what was created, so an output built from one
#     cannot claim a value that was requested but never came into being, and
#     cannot carry an environment's identity into the repository. That is also
#     why no account id, ARN, issuer URI, pool id, subnet id, region or
#     endpoint literal appears anywhere in this file; the single illustrative
#     URL below is written as obvious `<placeholder>` text.
#
# Deliberately absent (stated so omission does not read as oversight):
#   - no `provider` and no `backend` block: both belong to the environment
#     roots, and versions.tf records why a called module can hold neither;
#   - no `variable`, `resource`, `data`, `locals` or `module` block: this file
#     is the output contract and nothing else, which is also the placement
#     TFLint's terraform_standard_module_structure rule requires;
#   - no explicit ordering-dependency argument on any output: Terraform already
#     derives ordering from the attribute reference each `value` makes, so
#     declaring one would add an edge to the graph that asserts nothing the
#     reference does not, and a false edge is harder to remove later than to
#     never add;
#   - no `precondition` block: a `value` that reads one attribute of a
#     resource has no invariant left to assert that the resource itself, and
#     the input validations in variables.tf, have not already established.
#
# Baseline lineage:
#   The baseline published its addressable surface as eighteen
#   `DEFINE TRANSACTION(<id>) ... PROGRAM(<name>)` pairs spanning
#   app/csd/CARDDEMO.CSD:L306-L480 -- four-character identifiers a terminal
#   operator typed -- and `api_endpoint_url` is where the equivalent surface is
#   published for HTTP. That file is REFERENCE-ONLY: it is cited by line here
#   and never modified, and the deployment path it belongs to remains exactly
#   as it is. This module adds an entry point beside it; it removes none.
# =============================================================================

output "api_endpoint_url" {
  description = "Base HTTPS URL of this API, read from the stage's `invoke_url` so it already carries any stage path segment. The front door for every published CardDemo service and the only address the browser SPA is given: the environment root writes it to Parameter Store, and the SPA reads it from there as `VITE_API_BASE_URL`. Shaped `<api-id>.execute-api.<region>.amazonaws.com` for the reserved `$default` stage, where both bracketed parts are placeholders."

  # WHY : Alternatives Considered: `aws_apigatewayv2_api.this.api_endpoint` is
  #       the obvious candidate and is rejected. It reports the API's base URL
  #       with NO stage path segment, whereas `invoke_url` composes the URL of
  #       the stage that was actually created. `var.stage_name` defaults to the
  #       reserved `$default`, which contributes no segment, so the two
  #       attributes agree in the default case -- and that agreement is exactly
  #       what makes the wrong one dangerous. It would survive every plan and
  #       every review while the default held, then publish a URL missing the
  #       `/<stage>` prefix the moment a caller chose a named stage, which
  #       variables.tf:L1420-L1422 records as prepending that segment to every
  #       path. The damage would surface as a 404 in the browser at run time,
  #       with nothing wrong anywhere in the configuration. Reading the stage
  #       attribute composes correctly for every value that input can take.
  value = aws_apigatewayv2_stage.this.invoke_url
}

output "api_id" {
  description = "Provider-assigned identifier of the HTTP API, from `aws_apigatewayv2_api.this.id`. An opaque short string and not an address -- `api_endpoint_url` above is what a client calls. Consumed wherever the API must be NAMED rather than reached: associating a resource this module does not create, scoping an IAM condition to this one API, or locating it in the console."

  # WHY : Assumptions: a reader may reasonably expect this to be the address,
  #       since both it and the endpoint are short strings the API produces and
  #       either will satisfy a `string` input without complaint. It is not an
  #       address, and the description says so first: a caller that passes this
  #       where a URL belongs gets a request that resolves nowhere rather than
  #       a type error at plan time. The two are separate outputs because
  #       neither substitutes for the other -- IAM and resource associations
  #       cannot take a URL, and a browser cannot take an id.
  value = aws_apigatewayv2_api.this.id
}

output "stage_name" {
  description = "Name of the stage that was created, read back from `aws_apigatewayv2_stage.this.name` rather than echoed from the input that asked for it. Consumed to address the stage in a CloudWatch metric dimension or a stage-scoped API call; when it is the reserved `$default` it contributes no path segment, which is why the endpoint above needs no rewriting."

  # WHY : (1) Alternatives Considered: echoing the input directly is simpler
  #       and is rejected. An output built from a variable reports what the
  #       caller ASKED for; one built from the resource reports what the
  #       provider created, so any normalisation or defaulting applied on the
  #       way through stays visible to the caller instead of being hidden
  #       behind a value this module handed to itself. The two agree today,
  #       which is the reason to prefer the attribute rather than a reason to
  #       skip the distinction: on the day they disagree, the attribute is the
  #       one that describes the stage a metric or a stage-scoped call will
  #       actually address.
  #       (2) Trade-offs: the cost is NOT plan-time knowability, though it
  #       would be easy to assume it were and to reject the attribute on that
  #       basis. `name` is a CONFIGURED argument on the stage rather than a
  #       computed one, so a plan resolves this output to the same string the
  #       input carries instead of deferring it -- checked against a plan of
  #       this module, which reports this value as known while the four
  #       genuinely computed outputs here are deferred. The cost that is real
  #       is a dependency edge: reading the attribute makes this output depend
  #       on the stage being in the graph, where reading the variable would
  #       depend on nothing at all. That edge is accepted because it is the
  #       ordering the value's own meaning implies -- there is no name of a
  #       created stage until there is a created stage.
  value = aws_apigatewayv2_stage.this.name
}

output "authorizer_id" {
  description = "Identifier of the Cognito JWT authorizer, from `aws_apigatewayv2_authorizer.jwt.id`. Names the check that validates a caller's bearer token against the pool issuer and the accepted audience at the edge, before a request reaches any integration. Consumed by a caller attaching this same authorizer to a route created outside this module, so that route is validated identically."

  # WHY : (1) Trade-offs: published so a route created OUTSIDE this module can
  #       carry this same authorizer instead of being left unauthenticated.
  #       Publishing it does advertise that extension point, and every route
  #       this module creates already attaches the authorizer unconditionally,
  #       so a route added elsewhere that omitted it would defeat the
  #       authorizer-on-every-route property the policy check on this
  #       directory exists to hold -- and no gate in this folder can see that
  #       route to report it. The id is exported regardless, because
  #       withholding it prevents no unauthorized route: anyone able to add a
  #       route can add an open one without this value, so keeping it back
  #       would only make the authorized alternative the harder of the two to
  #       reach.
  #       (2) Assumptions: this identifies the authorizer and is not a token, a
  #       signing key or a claim. It grants nothing on its own, which is why it
  #       is published in the clear alongside the other four.
  value = aws_apigatewayv2_authorizer.jwt.id
}

output "vpc_link_id" {
  description = "Identifier of the VPC Link, from `aws_apigatewayv2_vpc_link.this.id`. Names the private path this API takes into the application subnets to reach the internal load balancer, which is what keeps every service off the public internet. Consumed by a caller adding a further private integration that should reuse this link rather than provision a second one."

  # WHY : Trade-offs: published so an additional private integration can reuse
  #       this link instead of standing up its own. A VPC Link is among the
  #       slower resources here to reach an available state, it is charged for
  #       as long as it exists, and one link can serve many private
  #       integrations -- so a caller unable to see this id would have to
  #       create a duplicate, paying twice and waiting again to reach the same
  #       subnets. The cost accepted is that a reusing caller becomes coupled
  #       to this module's lifecycle: destroying this module takes the link out
  #       from under that integration. Publishing the id therefore makes reuse
  #       possible without making it automatic, leaving that coupling an
  #       explicit decision in the caller's own configuration.
  value = aws_apigatewayv2_vpc_link.this.id
}

# =============================================================================
# The four outputs that used to stand here, and why they no longer do.
# -----------------------------------------------------------------------------
# WHY : Refactoring Rationale: `vpc_link_security_group_id`,
#       `access_log_group_name`, `access_log_group_arn` and `public_route_keys`
#       were published here and have been withdrawn, returning this file to the
#       five outputs the module's contract admits: the endpoint URL, the API id,
#       the stage name, the authorizer id and the VPC Link id. A repository-wide
#       search confirmed that no caller referenced any of the four -- neither
#       infra/envs/dev nor infra/envs/prod, and no sibling module -- so the
#       withdrawal breaks nothing, which is the only condition under which
#       removing from a one-way contract is safe at all.
# WHY : Assumptions: withdrawing them is not a loss of the properties they
#       reported, and it is worth naming where each property still lives.
#       The VPC Link's security group has exactly one egress rule, TCP 443 to the
#       internal ALB group, and that rule is declared in main.tf where it can be
#       read directly. Both access-log-group attributes are derivable from
#       `api_id`, and the group's own retention and encryption are set in main.tf.
#       The unauthenticated surface `public_route_keys` reported is bounded at its
#       source instead: `var.public_route_keys` is validated against the four
#       exact `/api/v1/auth` method-and-path keys it admits and nothing else, so
#       the exposure is constrained by the input contract rather than merely
#       observable in an output.
#       Trade-offs: an output read from the created resources is auditable in a
#       way an input validation is not -- it reports what exists rather than what
#       is permitted. That is the real cost of this removal and it is accepted,
#       because a narrow interface can be widened later by adding an output
#       whereas a published name cannot be withdrawn once two environment roots
#       depend on it, and the exposure this one reported is already closed by a
#       validation that no plan can bypass.
# =============================================================================
