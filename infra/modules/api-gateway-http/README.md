# API Gateway HTTP module

This module creates the public HTTP API, exact Cognito JWT authorizer, private
VPC Link integration, route inventory, CORS policy and KMS-encrypted access
logs.

## Design decisions

**Refactoring Rationale:** sign-on, challenge and refresh must be callable
before a token exists, so only those exact POST routes are public. User
administration and every other route retain JWT and scope enforcement.

**Assumptions:** the root supplies a dedicated VPC Link security group and a
server name covered by the internal ALB certificate. That makes the private hop
TLS-authenticated without widening the application task group.

## Validation

```bash
terraform -chdir=infra/modules/api-gateway-http init -backend=false
terraform -chdir=infra/modules/api-gateway-http validate
tflint --chdir=infra/modules/api-gateway-http --config="$(pwd)/infra/.tflint.hcl"
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
| [aws_apigatewayv2_api.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_api) | resource |
| [aws_apigatewayv2_authorizer.jwt](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_authorizer) | resource |
| [aws_apigatewayv2_integration.alb](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_integration) | resource |
| [aws_apigatewayv2_route.public](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_route) | resource |
| [aws_apigatewayv2_route.service](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_route) | resource |
| [aws_apigatewayv2_stage.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_stage) | resource |
| [aws_apigatewayv2_vpc_link.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/apigatewayv2_vpc_link) | resource |
| [aws_cloudwatch_log_group.access](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudwatch_log_group) | resource |
| [aws_security_group.vpc_link](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/security_group) | resource |
| [aws_vpc_security_group_egress_rule.vpc_link_to_alb_https](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_egress_rule) | resource |
| [aws_vpc_security_group_ingress_rule.alb_from_vpc_link_https](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/vpc_security_group_ingress_rule) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_alb_listener_arn"></a> [alb\_listener\_arn](#input\_alb\_listener\_arn) | ARN of the internal ALB's HTTPS listener, produced by the `alb` module. Becomes the private integration's `integration_uri`, so it is the one destination every authenticated request is forwarded to across the VPC Link. Shaped `arn:<partition>:elasticloadbalancing:<region>:<aws-account-id>:listener/app/<lb-name>/<lb-id>/<listener-id>`. | `string` | n/a | yes |
| <a name="input_alb_security_group_id"></a> [alb\_security\_group\_id](#input\_alb\_security\_group\_id) | Security group attached to the internal ALB. This module adds only the ingress rule from its dedicated VPC Link group on TCP 443. | `string` | n/a | yes |
| <a name="input_cognito_app_client_ids"></a> [cognito\_app\_client\_ids](#input\_cognito\_app\_client\_ids) | Cognito app client ids whose tokens this API accepts, produced by the `cognito` module. Becomes the JWT authorizer's `jwt_configuration.audience`, so a token whose audience claim falls outside this set is rejected at the edge before any integration runs. | `list(string)` | n/a | yes |
| <a name="input_cognito_issuer_uri"></a> [cognito\_issuer\_uri](#input\_cognito\_issuer\_uri) | OpenID Connect issuer URI of the Cognito user pool, produced by the `cognito` module and passed through by the environment root. Becomes the JWT authorizer's `jwt_configuration.issuer`, so it is what every request's token is validated against. Shaped `https://<cognito-issuer>/<user-pool-id>`. | `string` | n/a | yes |
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment environment this instance of the module belongs to, `dev` or `prod`. Composed into every resource name alongside `name_prefix`, and the axis along which the environment roots vary sizing and retention. | `string` | n/a | yes |
| <a name="input_integration_tls_server_name"></a> [integration\_tls\_server\_name](#input\_integration\_tls\_server\_name) | Server name the private integration verifies against the certificate the internal ALB listener presents. REQUIRED with no default: main.tf always emits the integration's tls\_config from it, so every hop from this edge to the load balancer is TLS with the server identity checked. | `string` | n/a | yes |
| <a name="input_private_app_subnet_ids"></a> [private\_app\_subnet\_ids](#input\_private\_app\_subnet\_ids) | Ids of the private application subnets the VPC Link places its network interfaces in, produced by the `network` module. They determine which availability zones the edge can reach the internal ALB from. | `list(string)` | n/a | yes |
| <a name="input_spa_cors_allow_origins"></a> [spa\_cors\_allow\_origins](#input\_spa\_cors\_allow\_origins) | Exact origins permitted to call this API from a browser: the distribution serving the SPA, produced by the `cloudfront-spa` module. Becomes `cors_configuration.allow_origins`, so an origin outside this list fails the browser's preflight. Shaped `https://<distribution-domain>`. | `list(string)` | n/a | yes |
| <a name="input_vpc_id"></a> [vpc\_id](#input\_vpc\_id) | VPC in which this module creates the dedicated API Gateway VPC Link security group, supplied by the network module. | `string` | n/a | yes |
| <a name="input_access_log_kms_key_arn"></a> [access\_log\_kms\_key\_arn](#input\_access\_log\_kms\_key\_arn) | ARN of the customer-managed KMS key encrypting the stage's access-log group, produced by the `kms` module. Null selects the CloudWatch Logs service-managed key instead. | `string` | `null` | no |
| <a name="input_cors_allow_headers"></a> [cors\_allow\_headers](#input\_cors\_allow\_headers) | Request headers a browser may send cross-origin. Becomes `cors_configuration.allow_headers`; every header the SPA sets on an authenticated JSON request has to appear here or the browser withholds the request after the preflight. | `list(string)` | <pre>[<br/>  "authorization",<br/>  "content-type",<br/>  "x-correlation-id"<br/>]</pre> | no |
| <a name="input_cors_allow_methods"></a> [cors\_allow\_methods](#input\_cors\_allow\_methods) | HTTP methods advertised to the browser in the preflight response. Becomes `cors_configuration.allow_methods`; the default is the set the migrated services' contracts expose, plus OPTIONS for the preflight exchange itself. | `list(string)` | <pre>[<br/>  "GET",<br/>  "POST",<br/>  "PUT",<br/>  "DELETE",<br/>  "OPTIONS"<br/>]</pre> | no |
| <a name="input_cors_expose_headers"></a> [cors\_expose\_headers](#input\_cors\_expose\_headers) | Response headers a browser is permitted to READ cross-origin. Becomes `cors_configuration.expose_headers`. Defaults to the correlation identifier common-lib's CorrelationIdFilter writes on every response and the location header a report submission returns; without an entry here a header is present on the wire and unreadable from script. | `list(string)` | <pre>[<br/>  "x-correlation-id",<br/>  "location"<br/>]</pre> | no |
| <a name="input_cors_max_age_seconds"></a> [cors\_max\_age\_seconds](#input\_cors\_max\_age\_seconds) | Upper bound, in seconds, on how long a browser may cache this API's preflight response. Becomes `cors_configuration.max_age`. | `number` | `300` | no |
| <a name="input_detailed_metrics_enabled"></a> [detailed\_metrics\_enabled](#input\_detailed\_metrics\_enabled) | Whether the stage emits per-route CloudWatch metrics -- count, latency and 4XX/5XX broken out by route key -- in addition to the API-wide aggregates it emits regardless. | `bool` | `true` | no |
| <a name="input_integration_timeout_milliseconds"></a> [integration\_timeout\_milliseconds](#input\_integration\_timeout\_milliseconds) | Upper bound, in milliseconds, the private integration waits for the internal ALB to respond before the edge abandons the request and returns a gateway timeout. Applied to every integration this module creates. | `number` | `29000` | no |
| <a name="input_log_retention_days"></a> [log\_retention\_days](#input\_log\_retention\_days) | Retention applied to the stage's access-log group in CloudWatch Logs. One of the narrow set of values on which the dev and prod roots deliberately differ; 0 retains log events indefinitely. | `number` | `30` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Leading token of every name this module composes, as `<name_prefix>-<environment>-<resource>`: the HTTP API, the VPC Link, the stage and the access-log group. Supplied by the environment root so a second independent copy of the stack can stand up in one account without colliding on a name. | `string` | `"carddemo"` | no |
| <a name="input_public_route_keys"></a> [public\_route\_keys](#input\_public\_route\_keys) | Route keys created WITHOUT the JWT authorizer, for paths that must be reachable before a token exists. Defaults to the single sign-on route POST /api/v1/auth/signon, which auth-service answers by calling the Cognito user pool with the confidential app client's secret. Set to an empty list to publish no unauthenticated route. Every other route on this API comes from var.route\_keys and carries the authorizer. | `list(string)` | <pre>[<br/>  "POST /api/v1/auth/signon",<br/>  "POST /api/v1/auth/challenge",<br/>  "POST /api/v1/auth/refresh"<br/>]</pre> | no |
| <a name="input_public_route_throttling_burst_limit"></a> [public\_route\_throttling\_burst\_limit](#input\_public\_route\_throttling\_burst\_limit) | Token-bucket depth for the unauthenticated routes in public\_route\_keys, applied as a per-route override on the stage. Deliberately far below throttling\_burst\_limit because an anonymous route is reachable without any credential. | `number` | `20` | no |
| <a name="input_public_route_throttling_rate_limit"></a> [public\_route\_throttling\_rate\_limit](#input\_public\_route\_throttling\_rate\_limit) | Steady-state requests per second sustained on the unauthenticated routes in public\_route\_keys, applied as a per-route override on the stage. Deliberately far below throttling\_rate\_limit for the same reason as its burst counterpart. | `number` | `10` | no |
| <a name="input_route_authorization_scopes"></a> [route\_authorization\_scopes](#input\_route\_authorization\_scopes) | Scopes every route requires in the token's scope claim, applied by main.tf to each route's authorization\_scopes. Defaults to the Cognito user pool's built-in aws.cognito.signin.user.admin scope, which is the scope an interactively signed-in access token carries and which an identity token carries not at all, so the requirement rejects the wrong token kind at the edge. | `list(string)` | <pre>[<br/>  "aws.cognito.signin.user.admin"<br/>]</pre> | no |
| <a name="input_route_keys"></a> [route\_keys](#input\_route\_keys) | HTTP API route keys to create, each attached to the JWT authorizer AND given var.route\_authorization\_scopes by main.tf. Every key is versioned under the published `/api/v1` path prefix. The default exposes the SEVEN online bounded contexts as a matched pair of keys apiece -- the bare collection prefix and the greedy subtree beneath it -- under path segments matching the SPA's API client modules. batch-service is deliberately absent: it has no ALB target to route to. An environment root may extend the list without editing the module. | `list(string)` | <pre>[<br/>  "ANY /api/v1/auth",<br/>  "ANY /api/v1/auth/{proxy+}",<br/>  "ANY /api/v1/accounts",<br/>  "ANY /api/v1/accounts/{proxy+}",<br/>  "ANY /api/v1/cards",<br/>  "ANY /api/v1/cards/{opaqueCardId}",<br/>  "ANY /api/v1/cards/{opaqueCardId}/{proxy+}",<br/>  "ANY /api/v1/transactions",<br/>  "ANY /api/v1/transactions/{proxy+}",<br/>  "ANY /api/v1/reference",<br/>  "ANY /api/v1/reference/{proxy+}",<br/>  "ANY /api/v1/authorizations",<br/>  "ANY /api/v1/authorizations/{proxy+}",<br/>  "ANY /api/v1/reports",<br/>  "ANY /api/v1/reports/{proxy+}"<br/>]</pre> | no |
| <a name="input_stage_name"></a> [stage\_name](#input\_stage\_name) | Name of the single stage this module creates. `$default` is the reserved name for a stage that serves requests with no stage segment in the path. | `string` | `"$default"` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Tags merged onto every taggable resource this module creates -- the HTTP API, the stage, the VPC Link and the access-log group. Supplied by the environment root, which owns the tagging scheme. | `map(string)` | `{}` | no |
| <a name="input_throttling_burst_limit"></a> [throttling\_burst\_limit](#input\_throttling\_burst\_limit) | Token-bucket depth applied by default to every route on the stage: how many requests above the steady rate the edge absorbs before it starts rejecting. Shields the seven online services behind it from one client's spike. | `number` | `200` | no |
| <a name="input_throttling_rate_limit"></a> [throttling\_rate\_limit](#input\_throttling\_rate\_limit) | Steady-state request rate, in requests per second, the stage sustains by default on every route. Applied together with throttling\_burst\_limit as the rate at which the bucket refills. | `number` | `100` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_access_log_group_arn"></a> [access\_log\_group\_arn](#output\_access\_log\_group\_arn) | ARN of the HTTP API access-log group, consumed by the exact encryption-context KMS policy assembled by the environment root. |
| <a name="output_access_log_group_name"></a> [access\_log\_group\_name](#output\_access\_log\_group\_name) | Name of the HTTP API access-log group, consumed by operator diagnostics and centralized observability inventory. |
| <a name="output_api_endpoint_url"></a> [api\_endpoint\_url](#output\_api\_endpoint\_url) | Base HTTPS URL of this API, read from the stage's `invoke_url` so it already carries any stage path segment. The front door for every published CardDemo service and the only address the browser SPA is given: the environment root writes it to Parameter Store, and the SPA reads it from there as `VITE_API_BASE_URL`. Shaped `<api-id>.execute-api.<region>.amazonaws.com` for the reserved `$default` stage, where both bracketed parts are placeholders. |
| <a name="output_api_id"></a> [api\_id](#output\_api\_id) | Provider-assigned identifier of the HTTP API, from `aws_apigatewayv2_api.this.id`. An opaque short string and not an address -- `api_endpoint_url` above is what a client calls. Consumed wherever the API must be NAMED rather than reached: associating a resource this module does not create, scoping an IAM condition to this one API, or locating it in the console. |
| <a name="output_authorizer_id"></a> [authorizer\_id](#output\_authorizer\_id) | Identifier of the Cognito JWT authorizer, from `aws_apigatewayv2_authorizer.jwt.id`. Names the check that validates a caller's bearer token against the pool issuer and the accepted audience at the edge, before a request reaches any integration. Consumed by a caller attaching this same authorizer to a route created outside this module, so that route is validated identically. |
| <a name="output_public_route_keys"></a> [public\_route\_keys](#output\_public\_route\_keys) | List of the route keys this API publishes with NO authorizer, each read back from the `route_key` of a created `aws_apigatewayv2_route.public` instance rather than echoed from the input that asked for it. Every OTHER route on this API requires a valid Cognito access token carrying the required scope, so this list is the complete set of paths reachable by an unauthenticated caller. Consumed by an operator or a review that needs the edge's unauthenticated exposure as a fact from the plan, not as a claim from a comment; empty when the caller published none. |
| <a name="output_stage_name"></a> [stage\_name](#output\_stage\_name) | Name of the stage that was created, read back from `aws_apigatewayv2_stage.this.name` rather than echoed from the input that asked for it. Consumed to address the stage in a CloudWatch metric dimension or a stage-scoped API call; when it is the reserved `$default` it contributes no path segment, which is why the endpoint above needs no rewriting. |
| <a name="output_vpc_link_id"></a> [vpc\_link\_id](#output\_vpc\_link\_id) | Identifier of the VPC Link, from `aws_apigatewayv2_vpc_link.this.id`. Names the private path this API takes into the application subnets to reach the internal load balancer, which is what keeps every service off the public internet. Consumed by a caller adding a further private integration that should reuse this link rather than provision a second one. |
| <a name="output_vpc_link_security_group_id"></a> [vpc\_link\_security\_group\_id](#output\_vpc\_link\_security\_group\_id) | Identifier of the dedicated security group this module creates for the VPC Link. Its only egress is TCP 443 to the internal ALB security group, whose matching ingress rule this module also owns. |
<!-- END_TF_DOCS -->
