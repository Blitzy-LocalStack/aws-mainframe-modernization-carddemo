# infra/modules/alb

This document is the prose half of the Terraform explainability contract for the
ALB module. Its sources of truth are the sibling
[`versions.tf`](versions.tf), [`variables.tf`](variables.tf),
[`main.tf`](main.tf), and [`outputs.tf`](outputs.tf) files, the target
architecture in the [infrastructure package guide](../../README.md), and the
REFERENCE-only CICS definitions in
[`app/csd/CARDDEMO.CSD`](../../../app/csd/CARDDEMO.CSD). The generated reference
inside the terraform-docs markers is derived only from the sibling HCL. The
[code documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md)
defines the Purpose, Parameters, Return values, Exceptions, and rationale
obligations that this README discharges for HCL.

## 1. Overview

This is one of the sixteen reusable modules under `infra/modules/`. It provisions
the target responsibility in the architecture plan's own terms: an **internal
Application Load Balancer, one HTTPS listener, and per-service rules**. It also
owns the secured fallback storage needed to make mandatory ALB access logging
deployable when a caller does not supply the shared observability bucket. Target
groups remain with `ecs-service`; the public edge remains with
`api-gateway-http`.

**Refactoring Rationale:** the module is net-new infrastructure, but it separates
the edge, routing, workload, and network ownership boundaries so that changing a
listener rule cannot also widen a security group or replace a service target
group. The [Terraform ADR](../../../docs/adr/ADR-009-iac-tool.md) records the
package-wide IaC choice, while this README records the ALB-specific consequences.

## 2. This is a module, not a root

The module is **never applied directly**. An environment root composes it:

```hcl
# WHAT: call the reusable ALB module from an environment root.
# WHY : the root owns provider configuration, state and environment values;
#       this child owns only the resources declared by its public contract.
module "alb" {
  source = "../../modules/alb"
  # ... inputs from the environment root
}
```

That boundary has three consequences:

1. There is no `provider "aws"` block here. `infra/envs/dev` and
   `infra/envs/prod` own the region and provider `default_tags`, and the module
   inherits the caller's default AWS provider.
2. There is no `backend` block here. State configuration exists only in the
   environment roots' `backend.tf` files and points at the backend provisioned by
   `infra/bootstrap`.
3. CI validates this module transitively by running `terraform init
   -backend=false` and `terraform validate` on each calling root. Backend-free
   initialization resolves providers and local modules without contacting remote
   state.

**Alternatives Considered:** applying this directory as a root was rejected
because it would require duplicating provider and backend configuration already
owned by each environment. That duplication would let region, tags, and state
placement disagree with the root that supplies the module's inputs.

Use the [package deploy and teardown guide](../../README.md#6-deploy) and the
[deploy](../../../docs/runbooks/deploy.md) and
[teardown](../../../docs/runbooks/teardown.md) runbooks for complete operational
sequences; this module README does not duplicate them.

## 3. Internal, not internet-facing

An API Gateway HTTP API with a Cognito JWT authorizer fronts this ALB through a
VPC Link. The only public entry points are that HTTP API and the CloudFront
distribution serving the SPA; the ALB is never directly reachable from the
internet. The [API and UI ADR](../../../docs/adr/ADR-006-api-and-ui.md) and
[security architecture](../../../docs/architecture/security-and-identity.md)
carry the package-wide edge design.

**Alternatives Considered:** an internet-facing ALB was rejected because it
would provide a path around the Cognito JWT authorizer enforced by API Gateway.
Authentication would then have to be duplicated across seven online service
configurations, and a missed service would be reachable without the managed edge
control.

**Assumption:** `internal = true` is hard-coded rather than exposed as an input.
Making it a `tfvars` switch would turn direct exposure of every routed service
into a one-line configuration mistake. Removing the choice makes that failure
mode unrepresentable.

Subnet placement and ALB scheme are separate contracts. The module accepts
`subnet_ids` without assigning a tier to them. The package architecture describes
load-balancer-role public subnets, while the settled environment roots pass
`module.network.private_app_subnet_ids`; either composition remains non-public
because `internal = true` withholds public addresses and an internet-routable DNS
name regardless of the route table attached to the selected subnets.

**Trade-off:** keeping `subnet_ids` tier-agnostic leaves placement with the
environment root, where network outputs are available, instead of coupling this
module to one network layout. The invariant retained here is the one that closes
the authorizer-bypass path: the ALB scheme cannot become public.

**Assumption:** because the ALB is internal, a policy check requiring a
public-facing load balancer to be associated with a WAF is out of scope **by
construction**. This module creates no WAF association and carries no scanner
suppression.

## 4. Security-group contract: one listener ingress path

The package contract is: **"Security groups permit only
load-balancer-to-application on 8080, application-to-Aurora on 5432 and
application-to-endpoint on 443."** The VPC Link reaches the ALB's HTTPS listener
on 443 through an exact security-group-to-security-group edge; no public CIDR is
part of that path.

**Assumption:** the `network` module creates and owns the ALB security group, and
the environment root supplies its identifier as `alb_security_group_id`. This
module only attaches that identifier. It declares no `aws_security_group`, no
`aws_security_group_rule`, accepts no port or CIDR input, and contains no
`0.0.0.0/0` route or rule. The cross-module VPC Link ingress edge is owned by
`api-gateway-http`, which can reference both endpoint security groups without
creating a dependency cycle.

The HTTPS listener on port 443 is the module's only listener. No plaintext HTTP
listener exists, so port 80 is not opened for forwarding or redirecting.

**Alternatives Considered:** an HTTP-to-HTTPS redirect listener was rejected
because the only client is the API Gateway private integration, which connects
over HTTPS. A plaintext listener would accept traffic no supported caller needs,
add another ingress rule to the network-owned matrix, and provide no migration
capability.

See the [security and identity ADR](../../../docs/adr/ADR-008-security-and-identity.md)
for the broader least-privilege and identity decisions.

## 5. Baseline lineage: the routing table this module succeeds

**Refactoring Rationale:** this module has **no source file in the baseline**; it
is net-new. Its lineage is structural: a CICS four-character transaction
identifier dispatched to a named program was the baseline routing table, while
an ALB path rule dispatching to a named target group is the target routing
table's structural successor.

The 505-line base CSD's eighteen transaction stanzas occupy L306-L488. The final
stanza starts at L479 and ends at L488, immediately before
`DEFINE LIBRARY(CARDDLIB)` at L489.

| Transaction | Program | Transaction line | `PROGRAM` line |
|---|---|---:|---:|
| `CAUP` | `COACTUPC` | L306 | L308 |
| `CAVW` | `COACTVWC` | L317 | L318 |
| `CA00` | `COADM01C` | L327 | L328 |
| `CB00` | `COBIL00C` | L337 | L338 |
| `CCDL` | `COCRDSLC` | L347 | L348 |
| `CCLI` | `COCRDLIC` | L357 | L358 |
| `CCUP` | `COCRDUPC` | L367 | L369 |
| `CC00` | `COSGN00C` | L378 | L379 |
| `CDV1` | `COCRDSEC` | L388 | L390 |
| `CM00` | `COMEN01C` | L399 | L400 |
| `CR00` | `CORPT00C` | L409 | L410 |
| `CT00` | `COTRN00C` | L419 | L420 |
| `CT01` | `COTRN01C` | L429 | L430 |
| `CT02` | `COTRN02C` | L439 | L440 |
| `CU00` | `COUSR00C` | L449 | L450 |
| `CU01` | `COUSR01C` | L459 | L460 |
| `CU02` | `COUSR02C` | L469 | L470 |
| `CU03` | `COUSR03C` | L479 | L480 |

`CDV1` -> `COCRDSEC` is the one dangling pair: no matching `.cbl` member exists.
It has no target service, route, or path pattern. The eighteen CSD entries are
therefore lineage evidence, **not** a template for the target route count; the
module routes the seven online bounded contexts declared by `service_routes`.

Every one of the eighteen stanzas carries `RESSEC(NO) CMDSEC(NO)`, so CICS
performed no per-transaction resource or command security check at dispatch.
Centralizing authentication at the API Gateway edge closes that gap only while
the ALB behind it remains unreachable from the internet.

The authoritative
[COBOL-to-service traceability matrix](../../../docs/architecture/cobol-to-service-traceability.md)
contains the complete mapping. This section carries only the ALB-relevant slice.

## 6. What this module provisions and deliberately omits

The settled HCL contains eight managed resource blocks. Five are conditional
fallback access-log storage; three are the load-balancing surface.

| Managed resource | Cardinality and purpose |
|---|---|
| `aws_s3_bucket.access_logs` | Zero or one encrypted fallback bucket when `access_logs_bucket` is null |
| `aws_s3_bucket_ownership_controls.access_logs` | Enforces bucket-owner object ownership on the fallback bucket |
| `aws_s3_bucket_public_access_block.access_logs` | Blocks every public ACL and policy path on the fallback bucket |
| `aws_s3_bucket_server_side_encryption_configuration.access_logs` | Applies S3-managed AES-256 encryption to fallback log objects |
| `aws_s3_bucket_policy.access_logs` | Requires TLS and grants bounded ALB log delivery |
| `aws_lb.this` | The internal Application Load Balancer |
| `aws_lb_listener.https` | The sole HTTPS listener, with a fixed 404 default action |
| `aws_lb_listener_rule.service` | Seven `for_each` path rules, keyed by online service name |

Four data sources derive deployment context without introducing literal account
or region values:

| Data source | Purpose |
|---|---|
| `aws_caller_identity.current` | Supplies the caller account identifier for fallback bucket and delivery-policy composition |
| `aws_region.current` | Supplies the configured provider region for the same runtime composition |
| `aws_partition.current` | Keeps generated policy ARNs partition-aware |
| `aws_iam_policy_document.access_logs` | Builds the fallback bucket's TLS and delivery policy |

**Refactoring Rationale:** the following omissions are ownership boundaries, not
unfinished work. Keeping each resource beside the lifecycle it changes prevents
this routing module from acquiring network, workload, certificate, or DNS
ownership merely because the ALB consumes those values.

| Not created here | Owning module or layer | Why |
|---|---|---|
| `aws_lb_target_group` | `ecs-service` | Target group, task definition, task role, log group, and autoscaling lifecycle change together; this module consumes target-group ARNs |
| `aws_security_group` and rules | `network`, with the VPC Link edge in `api-gateway-http` | The fixed port and source-group matrix must not be widened by a routing module |
| `aws_acm_certificate` | Environment root and ACM | The root supplies a validated same-region certificate ARN; no certificate identifier is embedded here |
| Shared access-log bucket | `observability` | Both roots supply its output; this module creates the secured fallback only when no shared bucket is supplied |
| `aws_route53_record` | Caller, if an internal friendly name is required | API Gateway targets the listener ARN through VPC Link and does not need an ALB DNS record |
| WAF web-ACL association | Out of scope for an internal ALB | The public-ALB WAF condition does not apply and is not suppressed |
| Plaintext HTTP listener | No module; deliberately absent | The only supported client uses HTTPS, so a redirect listener would add unused plaintext ingress |

## 7. The `/actuator/health` contract

Target groups belong to `ecs-service`, so their protocol, matcher, thresholds,
and health-check path are configured there. This module nevertheless declares
`health_check_path` with the default `/actuator/health` and republishes it as an
output. That creates one public declaration for the path consumed by each of the
seven online services' target group and container health check. The batch
workload is excluded because it has no target group, web server, or Actuator
endpoint; its process exit status is the health signal consumed by orchestration.

The settled environment roots feed one root local to both the ALB and
`ecs-service` calls rather than round-tripping the value through
`module.alb.health_check_path`. The output remains the module's reusable
consumer-facing seam, so another caller can use the module default without
copying it.

**Refactoring Rationale:** no resource in this module reads
`var.health_check_path`; `outputs.tf` is its only in-module consumer. Removing the
output would therefore make the variable unused and fail the gating
`terraform_unused_declarations` rule. Adding a target group here merely to consume
the value was rejected because it would split workload lifecycle ownership
between this module and `ecs-service`.

## 8. Inputs, outputs, and generated reference

The generated tables below provide names, types, defaults, descriptions, and
provider-derived resources. The narrative here adds the supplier and consumer
information that terraform-docs cannot infer.

### 8.1 Input suppliers and operational contracts

**Assumption:** each supplier is named because an input contract is incomplete
without its composition boundary. Defaulted inputs are chosen so omission cannot
disable logging, lower the TLS floor, or remove deletion protection; neutral
defaults such as `tags = {}` leave environment-owned policy with the caller.

| Input | Supplier | Contract beyond the generated row |
|---|---|---|
| `environment` | Environment root | Distinguishes the deployment in names and tags; lowercase validation and the joint name budget fail malformed values during planning |
| `subnet_ids` | A `network` output selected by the root | At least two distinct IDs are required; the module is tier-agnostic, and the settled roots select the private-application set |
| `alb_security_group_id` | `network.alb_security_group_id` | The module attaches the group and never adds or widens a rule |
| `certificate_arn` | Environment root, from an issued ACM certificate | The certificate must be validated and belong to the ALB's provider region |
| `certificate_domain_name` | Environment root's internal service DNS identity | Republished for the API Gateway private integration's TLS server-name verification |
| `service_routes` | Environment root, combining route topology with seven `ecs-service` target-group ARNs | Each map value contains `priority`, `path_patterns`, and `target_group_arn`; keys are exactly the seven online contexts, paths start with `/api/v1/`, and priorities are unique and evaluated lowest-first |
| `name_prefix` | Environment root or the `carddemo` default | Combined with `environment` and `-alb`; plan-time validation keeps the result within the 32-character ALB limit |
| `ssl_policy` | The module default; callers cannot weaken it | Validation locks the TLS 1.3 policy with a TLS 1.2 floor, so a root cannot re-enable TLS 1.0 or 1.1 |
| `access_logs_bucket` | `observability.access_log_bucket_name`, or null | Null creates the secured fallback bucket here; either path keeps logging enabled, so there is no disable switch |
| `access_logs_prefix` | The `alb` default or an explicit caller override | Defines the object-key root and rejects leading or trailing slashes that would diverge from lifecycle and query prefixes |
| `legacy_elb_log_delivery_account_arn` | Environment root only where a legacy regional grant is required | Null uses the modern service-principal path; an exceptional legacy account-root grant must be explicit |
| `enable_deletion_protection` | Environment root's protection setting | Defaults true so omission is protective; dev supplies false so the documented destroy path can remove the ALB and fallback logs |
| `idle_timeout` | The 60-second module default or an explicit root override | Synchronous APIs need no long-poll allowance; validation keeps the value inside the ALB-supported range |
| `health_check_path` | The module default or the root's shared health-path local | One absolute path feeds the seven online target groups and container checks; §7 explains the output indirection |
| `tags` | Optional environment-root additions | Merged after the module's component tag and alongside provider `default_tags`, so caller keys retain precedence |

**Alternatives Considered:** a list for `service_routes` was rejected because
`count` would bind resource identity to position. The map's service-name keys are
stable `for_each` addresses, so insertion or reordering does not recreate
unrelated listener rules. Explicit priorities remain necessary because they are
the request-match precedence and must be unique on one listener.

### 8.2 Output consumers

| Output | Consumer and use |
|---|---|
| `alb_arn` | Calling root, operator tooling, or IAM/alarm composition that must name the exact ALB |
| `alb_arn_suffix` | `observability`, because the `AWS/ApplicationELB` `LoadBalancer` metric dimension accepts the ARN suffix rather than the full ARN |
| `alb_dns_name` | In-VPC operator diagnostics and a caller that elects to create an internal alias; it is not a public endpoint |
| `alb_zone_id` | A caller creating that optional Route 53 alias, paired with `alb_dns_name` |
| `access_logs_bucket_name` | Operator queries and lifecycle integrations; it resolves to the shared supplied bucket or the module-owned fallback |
| `access_logs_bucket_arn` | Exact-resource IAM integration when the fallback bucket is module-owned; it is null when a shared external bucket is supplied |
| `https_listener_arn` | `api-gateway-http`, as the VPC Link private-integration target; the integration targets the listener, not the load balancer |
| `https_server_name` | The API Gateway TLS configuration, as the certificate identity to verify; the settled roots pass the same source local directly |
| `listener_rule_arns` | Calling-root assertions and targeted operations, keyed by the same stable service names as `service_routes` |
| `health_check_path` | Seven online `ecs-service` target groups and container checks; the settled roots use the same root local, while the output preserves the reusable module contract |

The settled roots directly reference `https_listener_arn` and
`alb_arn_suffix`; the other outputs deliberately support operator diagnostics,
optional aliasing, fallback-bucket integration, and reusable callers. Per the
[package configuration-flow contract](../../README.md#91-configuration-flows-outward-never-inward),
module outputs are the admissible source of runtime identifiers; no service
hard-codes an ALB endpoint.

**Assumption:** none of the ten outputs is marked `sensitive`. They are
infrastructure identifiers, a request path, or a nullable bucket ARN rather than
credentials. Redacting them from plans and `terraform output` would obstruct
wiring diagnostics without protecting a secret; credentials are generated at
apply time and written directly to Secrets Manager.

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |

### Resources

| Name | Type |
|------|------|
| [aws_lb.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb) | resource |
| [aws_lb_listener.https](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb_listener) | resource |
| [aws_lb_listener_rule.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/lb_listener_rule) | resource |
| [aws_s3_bucket.access_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_ownership_controls.access_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.access_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.access_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.access_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_iam_policy_document.access_logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_partition.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/partition) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_alb_security_group_id"></a> [alb\_security\_group\_id](#input\_alb\_security\_group\_id) | Id of the load-balancer security group created and owned by the network module; attached to the load balancer and never modified here. | `string` | n/a | yes |
| <a name="input_certificate_arn"></a> [certificate\_arn](#input\_certificate\_arn) | ARN of the ACM certificate the HTTPS listener presents, provisioned and validated outside this module. | `string` | n/a | yes |
| <a name="input_certificate_domain_name"></a> [certificate\_domain\_name](#input\_certificate\_domain\_name) | Bare DNS name covered by certificate\_arn and verified by the API Gateway private integration when it connects to this HTTPS listener. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment discriminator composed into the load-balancer name and tags, distinguishing one environment's load balancer from another's. | `string` | n/a | yes |
| <a name="input_service_routes"></a> [service\_routes](#input\_service\_routes) | Per-service listener rules keyed by service name; each entry gives the rule priority, the path patterns to match and the target group to forward to. Keyed by the SEVEN online bounded contexts and only those: batch has no target group to forward to. | <pre>map(object({<br/>    priority         = number<br/>    path_patterns    = list(string)<br/>    target_group_arn = string<br/>  }))</pre> | n/a | yes |
| <a name="input_subnet_ids"></a> [subnet\_ids](#input\_subnet\_ids) | Ids of the subnets the load balancer places its nodes in, one per availability zone, supplied from the network module. | `list(string)` | n/a | yes |
| <a name="input_access_logs_bucket"></a> [access\_logs\_bucket](#input\_access\_logs\_bucket) | Name of an existing S3 bucket to write load-balancer access logs to. Leave<br/>null to have this module create and own the bucket, with encryption, a public<br/>access block, a TLS-only policy and the ELB log-delivery grant. Supply the<br/>shared terminal bucket published by infra/modules/observability when one stack<br/>should have a single log destination; both environment roots do. | `string` | `null` | no |
| <a name="input_access_logs_prefix"></a> [access\_logs\_prefix](#input\_access\_logs\_prefix) | Key prefix under which the load balancer writes access-log objects inside the logging bucket. | `string` | `"alb"` | no |
| <a name="input_enable_deletion_protection"></a> [enable\_deletion\_protection](#input\_enable\_deletion\_protection) | Whether the load balancer refuses deletion until the protection is cleared. | `bool` | `true` | no |
| <a name="input_health_check_path"></a> [health\_check\_path](#input\_health\_check\_path) | Path the target groups and container health checks probe, requested over HTTPS by the ecs-service target groups. Republished by outputs.tf; this module creates no target group. | `string` | `"/actuator/health"` | no |
| <a name="input_idle_timeout"></a> [idle\_timeout](#input\_idle\_timeout) | Seconds the load balancer holds an idle connection open before closing it. | `number` | `60` | no |
| <a name="input_legacy_elb_log_delivery_account_arn"></a> [legacy\_elb\_log\_delivery\_account\_arn](#input\_legacy\_elb\_log\_delivery\_account\_arn) | Optional regional ELB service-account root ARN used only where the legacy pre-service-principal access-log delivery model remains required. Null uses the modern logdelivery.elasticloadbalancing.amazonaws.com principal alone. | `string` | `null` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Name prefix composed into the load-balancer name and tags, shared with the rest of the CardDemo infrastructure package. | `string` | `"carddemo"` | no |
| <a name="input_ssl_policy"></a> [ssl\_policy](#input\_ssl\_policy) | Predefined ELB security policy the HTTPS listener negotiates with. The module accepts only the TLS 1.3 policy with a TLS 1.2 floor because no environment may lower the listener protocol. | `string` | `"ELBSecurityPolicy-TLS13-1-2-2021-06"` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Additional tags merged onto the resources this module creates, beyond the provider-level default tags. | `map(string)` | `{}` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_access_logs_bucket_arn"></a> [access\_logs\_bucket\_arn](#output\_access\_logs\_bucket\_arn) | ARN of the module-owned ALB access-log bucket, for exact-resource IAM and observability integrations. |
| <a name="output_access_logs_bucket_name"></a> [access\_logs\_bucket\_name](#output\_access\_logs\_bucket\_name) | Name of the S3 bucket this module creates for ALB access logs, useful for operator queries and lifecycle integrations without reconstructing the globally-scoped name. |
| <a name="output_alb_arn"></a> [alb\_arn](#output\_alb\_arn) | ARN of the internal Application Load Balancer, identifying it uniquely as an alarm target and in IAM policy conditions. |
| <a name="output_alb_arn_suffix"></a> [alb\_arn\_suffix](#output\_alb\_arn\_suffix) | Trailing ARN segments that identify the load balancer to CloudWatch, supplied as the LoadBalancer metric dimension in the AWS/ApplicationELB namespace. |
| <a name="output_alb_dns_name"></a> [alb\_dns\_name](#output\_alb\_dns\_name) | VPC-internal DNS name assigned to the load balancer; it resolves only from inside the VPC, because the load balancer is internal. |
| <a name="output_alb_zone_id"></a> [alb\_zone\_id](#output\_alb\_zone\_id) | Canonical hosted-zone id of the load balancer, required as the alias-target zone of a Route 53 alias record; this module creates no such record. |
| <a name="output_health_check_path"></a> [health\_check\_path](#output\_health\_check\_path) | Path the ecs-service target groups and the container health checks probe, republished from the input so that every load-balanced service is configured from one declaration. |
| <a name="output_https_listener_arn"></a> [https\_listener\_arn](#output\_https\_listener\_arn) | ARN of the HTTPS listener, taken as the target of the API Gateway HTTP API private integration over its VPC Link; the only seam between the public edge and the services behind it. |
| <a name="output_https_server_name"></a> [https\_server\_name](#output\_https\_server\_name) | Bare DNS name covered by the listener certificate and passed to API Gateway as the private integration's TLS server\_name\_to\_verify value. |
| <a name="output_listener_rule_arns"></a> [listener\_rule\_arns](#output\_listener\_rule\_arns) | ARNs of the per-service listener rules, keyed by service name identically to the service\_routes input, so a caller can address one context's rule by name rather than by position. |
<!-- END_TF_DOCS -->

## 9. Validation and gating

Every category below is gating in
[`.github/workflows/infra-ci.yml`](../../../.github/workflows/infra-ci.yml);
none is an advisory report. The first four pass for the settled tree. The
security category is reported with its measured findings rather than described
as green when its hard Checkov sub-gate is not.

| Gate | Invocation or configuration | Module behavior and measured status |
|---|---|---|
| 1. Canonical formatting | `terraform fmt -check -recursive infra/` | Passes; check mode verifies the sibling HCL without rewriting reviewed source |
| 2. Terraform validation | Backend-free `init` plus `validate` on `infra/bootstrap`, `infra/envs/dev`, and `infra/envs/prod` | Passes; each root resolves this local child with the root-owned provider configuration |
| 3. TFLint | Recursive run with [`infra/.tflint.hcl`](../../.tflint.hcl) | Passes; all 15 variables are typed and described, all 10 outputs are described, declarations are used, names are snake_case, and HCL comments use `#` syntax |
| 4. terraform-docs drift | Version-pinned check-only run with [`infra/.terraform-docs.yml`](../../.terraform-docs.yml) | Passes across all nineteen governed directories; this generated region is byte-identical to the settled HCL |
| 5. Security and policy | Migration-owned Gitleaks scan, bounded-exception assertions, and the hard Checkov material-security baseline | The module secret scan passes and ALB checks `CKV_AWS_91` and `CKV_AWS_103` pass; Checkov still reports `CKV_AWS_21` and `CKV_AWS_145` on the conditional fallback bucket |

The two open ALB findings are concrete: the fallback bucket has no versioning
resource and uses S3-managed AES-256 rather than KMS. Supplying the shared
`observability` bucket makes the fallback resource count zero at apply, but
Checkov evaluates the conditional resource block statically, so that root wiring
does not clear either finding. A clean hard gate requires the fallback HCL to
provide both controls.

**Trade-off:** the workflow uses an explicit material-check list because its
offline Checkov distribution does not carry reliable severity metadata;
selecting `HIGH,CRITICAL` there would select no checks and report a false green
result. The full scan still records the wider result set.

**Assumption:** this module uses no policy suppression of any kind: no
`#checkov:skip`, no `--skip-check`, and no module-specific ignore file. The
internal-ALB WAF condition is inapplicable by construction rather than waived.

## 10. Regenerating the Terraform reference

Run both commands from the repository root:

```bash
# WHAT: regenerate only the terraform-docs region in this module README.
# WHY : Assumption: the pinned repository configuration owns marker text,
#       heading depth, section selection and byte ordering; hand-written tables
#       drift from it.
terraform-docs --config infra/.terraform-docs.yml infra/modules/alb

# WHAT: perform the same non-mutating drift check used by CI.
# WHY : Trade-off: check-only mode reports a stale file instead of repairing it,
#       keeping the generated contract reviewable rather than hiding the defect
#       by rewriting the file during validation.
terraform-docs --config infra/.terraform-docs.yml \
  --output-check infra/modules/alb
```

The CI command is check-only and will not repair a stale README. Any change to
`versions.tf`, `variables.tf`, `main.tf`, or `outputs.tf` must regenerate the
injected region in the same change. The marker lines themselves and every byte of
hand-written prose outside them are not generator-owned.

## 11. Troubleshooting and failure modes

| Symptom | Cause | Resolution |
|---|---|---|
| Planning rejects fewer than two `subnet_ids` or repeated IDs | An ALB needs nodes in at least two distinct subnets | Supply at least two unique subnet IDs from the intended `network` output |
| Apply rejects two different subnets in one availability zone | ID uniqueness cannot prove availability-zone diversity, but the ALB service requires at least two zones | Select subnets from at least two availability zones; the ordered network outputs provide one per zone |
| Planning rejects the composed ALB name | `name_prefix`, `environment`, separators, and the suffix exceed the 32-character `aws_lb` limit | Shorten `name_prefix` or `environment`; the joint validation reports the budget before apply |
| Planning rejects `service_routes` priorities | Two entries share a priority, or a priority is outside 1-50000 | Assign one unique value per rule; lower numbers are evaluated first |
| Planning rejects route keys or paths | The map is not the exact seven online contexts, an entry has no pattern, or a pattern omits `/api/v1/` | Build the map from the environment root's online-service topology and preserve the versioned prefix |
| ALB access logs are not delivered | A supplied shared bucket lacks the required delivery grant, or a legacy region needs its explicit account-root grant | Correct the owning `observability` bucket policy, supply the exceptional legacy ARN, or use the module-owned fallback by passing null |
| Listener creation rejects the certificate | The certificate is not issued, is not validated, or belongs to another region | Supply an issued ACM certificate from the same provider region and the matching bare domain through the environment root |
| `terraform destroy` is blocked | `enable_deletion_protection` is true | Set the environment's protection value false, apply that reviewed change, then follow the [teardown runbook](../../../docs/runbooks/teardown.md) |
| terraform-docs reports the README out of date | A sibling `.tf` contract changed without regenerating the injected region | Run both §10 commands and commit the generated change with the HCL change |
| TFLint reports `terraform_unused_declarations` | A variable, local, or data source has no reference | Remove the false contract or consume it legitimately; do **not** add `vpc_id`, because `aws_lb` takes no VPC ID and this module owns no target group |
| Checkov reports `CKV_AWS_21` and `CKV_AWS_145` | The conditional fallback log bucket is not versioned and uses S3-managed encryption | Add versioning and KMS encryption to the fallback-bucket HCL; passing a shared bucket does not remove a statically scanned resource block |

**Trade-off:** validations catch shape, ranges, key sets, and duplicate IDs during
planning, but they cannot prove that two distinct subnet IDs belong to distinct
availability zones or that an external bucket policy grants delivery. Those
account-state checks remain apply-time failures, so this table names both the
owner and the corrective action.

## 12. Zero-secrets posture

No deployment-specific certificate ARN, AWS account identifier, region, DNS
name, hostname, bucket name, credential, or endpoint is authored in this README.
The certificate and optional shared log bucket enter as inputs; account and
region are read through provider data sources for runtime composition; the ALB
DNS name leaves as an output. terraform-docs is configured not to inject resolved
output values, so the generated reference describes contracts rather than one
environment's state.

Environment `terraform.tfvars` files carry sizing, retention, and protection
choices only. Database and Cognito seed credentials are generated during apply
and written directly to Secrets Manager instead of passing through this module,
its outputs, or version control. Saved plans and state remain excluded because
they contain resolved values.

**Assumption:** this posture is structural rather than dependent on a reviewer
recognizing every credential shape. Input/output boundaries keep deployed
identifiers out of source, the migration-owned Gitleaks gate scans committed
content, and the [package secrets and configuration flow](../../README.md#9-secrets-and-configuration-flow)
documents the remaining controls.
