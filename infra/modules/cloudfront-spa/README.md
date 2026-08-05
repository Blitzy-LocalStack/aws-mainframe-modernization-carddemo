# CloudFront SPA module

This module creates the private SPA origin bucket, origin access control,
CloudFront distribution, response-headers policy, custom TLS configuration and
CMK-encrypted standard logging v2 destination.

## Design decisions

**Refactoring Rationale:** both 403 and 404 responses are rewritten to the SPA
entry document because S3 can return 403 for a missing key when list permission
is intentionally absent. That preserves hard-refresh behavior for client-side
routes.

**Trade-offs:** every environment requires a validated custom certificate and
TLS 1.2-or-newer policy. Dev accepts the same certificate lifecycle so transport
posture does not diverge from production.

## Validation

```bash
terraform -chdir=infra/modules/cloudfront-spa init -backend=false
terraform -chdir=infra/modules/cloudfront-spa validate
tflint --chdir=infra/modules/cloudfront-spa --config="$(pwd)/infra/.tflint.hcl"
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
| [aws_cloudfront_distribution.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_distribution) | resource |
| [aws_cloudfront_function.spa_router](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_function) | resource |
| [aws_cloudfront_origin_access_control.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_origin_access_control) | resource |
| [aws_cloudfront_response_headers_policy.security_headers](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudfront_response_headers_policy) | resource |
| [aws_cloudwatch_log_delivery.cloudfront_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_delivery) | resource |
| [aws_cloudwatch_log_delivery_destination.cloudfront_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_delivery_destination) | resource |
| [aws_cloudwatch_log_delivery_source.cloudfront_access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_delivery_source) | resource |
| [aws_s3_bucket.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket) | resource |
| [aws_s3_bucket_lifecycle_configuration.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_lifecycle_configuration.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_lifecycle_configuration) | resource |
| [aws_s3_bucket_ownership_controls.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_ownership_controls.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_ownership_controls) | resource |
| [aws_s3_bucket_policy.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_policy.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_policy) | resource |
| [aws_s3_bucket_public_access_block.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_public_access_block.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_public_access_block) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_server_side_encryption_configuration.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_server_side_encryption_configuration) | resource |
| [aws_s3_bucket_versioning.logs](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_s3_bucket_versioning.spa](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/s3_bucket_versioning) | resource |
| [aws_caller_identity.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/caller_identity) | data source |
| [aws_cloudfront_cache_policy.caching_optimized](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/cloudfront_cache_policy) | data source |
| [aws_iam_policy_document.logs_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_iam_policy_document.spa_bucket](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/iam_policy_document) | data source |
| [aws_region.current](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/data-sources/region) | data source |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_acm_certificate_arn"></a> [acm\_certificate\_arn](#input\_acm\_certificate\_arn) | ARN of the ACM certificate the distribution serves to viewers, which must be issued in us-east-1 and must cover every name in `aliases`; REQUIRED in every environment, because the default CloudFront certificate pins the viewer security policy to TLSv1. | `string` | n/a | yes |
| <a name="input_aliases"></a> [aliases](#input\_aliases) | Domain names the distribution answers on; REQUIRED and non-empty, and every name must be covered by the certificate in acm\_certificate\_arn. | `list(string)` | n/a | yes |
| <a name="input_api_connect_src_origins"></a> [api\_connect\_src\_origins](#input\_api\_connect\_src\_origins) | Origins the SPA is permitted to reach with fetch or XHR, added to the content-security policy's connect-src directive alongside 'self'. Scheme and host only, no path and no trailing slash. Empty means same-origin only. | `list(string)` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Environment discriminator embedded in every resource name, so the dev and prod stacks can coexist without colliding on a globally unique bucket name. | `string` | n/a | yes |
| <a name="input_s3_kms_key_arn"></a> [s3\_kms\_key\_arn](#input\_s3\_kms\_key\_arn) | ARN of the customer-managed KMS key that encrypts the private SPA origin bucket at rest. | `string` | n/a | yes |
| <a name="input_s3_kms_key_policy_id"></a> [s3\_kms\_key\_policy\_id](#input\_s3\_kms\_key\_policy\_id) | Identifier of the fully applied S3 key policy. CloudFront logging v2 reads it only as an ordering token so delivery cannot start before the exact distribution and delivery-source KMS grants exist. | `string` | n/a | yes |
| <a name="input_default_root_object"></a> [default\_root\_object](#input\_default\_root\_object) | Document CloudFront returns for / and, deliberately, the same document the viewer-request routing function rewrites client-side routes to, so a deep link resolves instead of 404ing. | `string` | `"index.html"` | no |
| <a name="input_force_destroy"></a> [force\_destroy](#input\_force\_destroy) | Whether the SPA origin bucket may be deleted while it still holds objects; a teardown protection flag, not an environment-shape switch. | `bool` | `false` | no |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Days a CloudFront access-log object is kept before the log bucket's lifecycle rule expires it; the second of the two values the dev and prod roots differ on. | `number` | `30` | no |
| <a name="input_minimum_protocol_version"></a> [minimum\_protocol\_version](#input\_minimum\_protocol\_version) | Minimum TLS version the distribution accepts from viewers; always applied, because the distribution always serves a supplied ACM certificate and never the default one. | `string` | `"TLSv1.2_2021"` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Name prefix shared by every resource this module creates, so the SPA bucket, response-header policy, origin access control and distribution group together in the console and in cost reports. | `string` | `"carddemo"` | no |
| <a name="input_price_class"></a> [price\_class](#input\_price\_class) | CloudFront edge-location tier that serves the SPA; one of the two values the dev and prod roots deliberately differ on for this module. | `string` | `"PriceClass_100"` | no |
| <a name="input_spa_noncurrent_version_retention_days"></a> [spa\_noncurrent\_version\_retention\_days](#input\_spa\_noncurrent\_version\_retention\_days) | Days a superseded SPA build is kept as a noncurrent object version before expiry, which is what bounds the storage cost of keeping front-end rollback available. | `number` | `30` | no |
| <a name="input_web_acl_arn"></a> [web\_acl\_arn](#input\_web\_acl\_arn) | ARN of a WAFv2 web ACL to associate with the distribution; null associates none, which is this package's default and documented posture. | `string` | `null` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_distribution_arn"></a> [distribution\_arn](#output\_distribution\_arn) | ARN of the CloudFront distribution serving the SPA. The environment root passes this exact ARN back to the KMS module so the CloudFront service principal can decrypt only this distribution's SSE-KMS origin objects. |
| <a name="output_distribution_domain_name"></a> [distribution\_domain\_name](#output\_distribution\_domain\_name) | CloudFront-assigned hostname of the distribution. This is the SPA's public entry point, the address that replaces a 3270 terminal session against CICS transaction CC00, and the environment roots re-export it as the deployed front-end host. |
| <a name="output_distribution_hosted_zone_id"></a> [distribution\_hosted\_zone\_id](#output\_distribution\_hosted\_zone\_id) | Route 53 hosted-zone id of the CloudFront distribution, consumed by environment roots when they create the custom SPA alias without hard-coding CloudFront's global zone id. |
| <a name="output_distribution_id"></a> [distribution\_id](#output\_distribution\_id) | Id of the CloudFront distribution serving the SPA. The deployment pipeline passes it to a cache invalidation after uploading a new build, and an operator uses it to address the distribution from the CLI. |
| <a name="output_log_bucket_arn"></a> [log\_bucket\_arn](#output\_log\_bucket\_arn) | ARN of the CMK-encrypted S3 destination for CloudFront standard logging v2. The KMS module consumes it as an exact allowed S3 encryption context. |
| <a name="output_log_delivery_source_arn"></a> [log\_delivery\_source\_arn](#output\_log\_delivery\_source\_arn) | Exact CloudWatch Logs delivery-source ARN for the distribution's standard logging v2 stream. The KMS key policy uses it to scope log-delivery data-key generation. |
| <a name="output_origin_access_control_id"></a> [origin\_access\_control\_id](#output\_origin\_access\_control\_id) | Id of the origin access control that signs this distribution's requests to the private origin bucket. Published so an operator diagnosing a 403 from the origin can confirm which origin access control the bucket policy is scoped to. |
| <a name="output_spa_bucket_arn"></a> [spa\_bucket\_arn](#output\_spa\_bucket\_arn) | ARN of the SPA origin bucket, for an IAM policy that grants a deployment role write access to this bucket and to no other. Published alongside spa\_bucket\_name because the two forms are not interchangeable. |
| <a name="output_spa_bucket_name"></a> [spa\_bucket\_name](#output\_spa\_bucket\_name) | Name of the private S3 bucket holding the built SPA bundle, and the destination the deployment pipeline syncs the ui/dist output into. This is neither the dataset bucket owned by the s3-datasets module nor the Terraform remote-state bucket owned by infra/bootstrap. |
<!-- END_TF_DOCS -->
