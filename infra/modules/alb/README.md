# ALB module

This module creates the internal Application Load Balancer, its HTTPS listener
and the seven path-based rules for online bounded contexts. Target groups remain
owned by `ecs-service`, and the environment root supplies the certificate,
network group and dedicated access-log bucket.

## Design decisions

**Alternatives Considered:** an internet-facing load balancer would bypass the
API Gateway Cognito authorizer. The load balancer is therefore internal and is
reachable only through the VPC Link security-group edge composed by the root.

**Trade-offs:** one listener and path rules avoid a port and listener per
service. The accepted coupling is that route priorities and path inventories are
environment-root topology and must remain identical between dev and prod.

## Validation

```bash
terraform -chdir=infra/modules/alb init -backend=false
terraform -chdir=infra/modules/alb validate
tflint --chdir=infra/modules/alb --config="$(pwd)/infra/.tflint.hcl"
```

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
