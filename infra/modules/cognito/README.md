# Cognito module

This module creates the user pool, app client, resource-server scopes,
`carddemo-admin` and `carddemo-user` groups, optional opaque seed identities and
Secrets Manager entries for generated bootstrap and client credentials.

## Design decisions

**Assumptions:** group names are invariants shared with the Spring JWT role
converter. They are not caller-configurable, preventing an identity group that
maps to no application authority.

**Trade-offs:** production requires MFA and enforced threat protection while
development uses optional MFA and audit mode. Token rotation remains enabled in
both environments with the same minimal reuse grace.

## Validation

```bash
terraform -chdir=infra/modules/cognito init -backend=false
terraform -chdir=infra/modules/cognito validate
tflint --chdir=infra/modules/cognito --config="$(pwd)/infra/.tflint.hcl"
```

<!-- BEGIN_TF_DOCS -->
### Requirements

| Name | Version |
|------|---------|
| <a name="requirement_terraform"></a> [terraform](#requirement\_terraform) | >= 1.15.0 |
| <a name="requirement_aws"></a> [aws](#requirement\_aws) | ~> 6.56 |
| <a name="requirement_random"></a> [random](#requirement\_random) | ~> 3.9 |

### Providers

| Name | Version |
|------|---------|
| <a name="provider_aws"></a> [aws](#provider\_aws) | 6.57.1 |
| <a name="provider_random"></a> [random](#provider\_random) | 3.9.0 |
| <a name="provider_terraform"></a> [terraform](#provider\_terraform) | n/a |

### Resources

| Name | Type |
|------|------|
| [aws_cloudformation_stack.app_client](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cloudformation_stack) | resource |
| [aws_cognito_resource_server.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_resource_server) | resource |
| [aws_cognito_user_group.admin](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_group) | resource |
| [aws_cognito_user_group.user](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_group) | resource |
| [aws_cognito_user_in_group.seed_user](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_in_group) | resource |
| [aws_cognito_user_pool.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_pool) | resource |
| [aws_cognito_user_pool_domain.this](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/cognito_user_pool_domain) | resource |
| [aws_secretsmanager_secret.app_client](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [aws_secretsmanager_secret.seed_user](https://registry.terraform.io/providers/hashicorp/aws/latest/docs/resources/secretsmanager_secret) | resource |
| [random_id.seed_user_secret](https://registry.terraform.io/providers/hashicorp/random/latest/docs/resources/id) | resource |
| [terraform_data.app_client_secret_rotation](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |
| [terraform_data.seed_user](https://registry.terraform.io/providers/hashicorp/terraform/latest/docs/resources/data) | resource |

### Inputs

| Name | Description | Type | Default | Required |
|------|-------------|------|---------|:--------:|
| <a name="input_environment"></a> [environment](#input\_environment) | Deployment environment this user pool serves, either dev or prod. Selects the sizing and retention posture and distinguishes composed resource names within a single AWS account. | `string` | n/a | yes |
| <a name="input_secrets_kms_key_arn"></a> [secrets\_kms\_key\_arn](#input\_secrets\_kms\_key\_arn) | ARN of the customer-managed KMS key used to encrypt the Secrets Manager entries that hold each seed user's generated initial password. Required with no default: supplied by the calling root from the kms module's output so that a fallback to the AWS-managed key cannot happen unnoticed. | `string` | n/a | yes |
| <a name="input_access_token_validity_minutes"></a> [access\_token\_validity\_minutes](#input\_access\_token\_validity\_minutes) | Access token lifetime in minutes. main.tf pairs this with a token\_validity\_units block set to minutes, without which the provider would read the number as hours. | `number` | `60` | no |
| <a name="input_advanced_security_mode"></a> [advanced\_security\_mode](#input\_advanced\_security\_mode) | Cognito threat-protection posture: OFF, AUDIT (record risk signals only) or ENFORCED (act on them). Passed through to the advanced\_security\_mode argument of the user\_pool\_add\_ons block, which is where the pinned provider exposes this setting. Production is structurally required to be ENFORCED; the default suits dev only. | `string` | `"AUDIT"` | no |
| <a name="input_app_client_secret_rotation_revision"></a> [app\_client\_secret\_rotation\_revision](#input\_app\_client\_secret\_rotation\_revision) | Monotonic, non-secret revision that triggers the Cognito app-client-secret rotation bridge. Incrementing it adds a new active client secret, updates the Secrets Manager value, and retains the previously current secret for a zero-downtime consumer rollout. | `number` | `1` | no |
| <a name="input_callback_urls"></a> [callback\_urls](#input\_callback\_urls) | Absolute URLs Cognito may redirect to after a successful sign-in. Supplied by the calling root from the CloudFront distribution that serves the SPA, since this module cannot read that sibling module's output itself. | `list(string)` | `[]` | no |
| <a name="input_deletion_protection"></a> [deletion\_protection](#input\_deletion\_protection) | Whether the user pool is protected from deletion: ACTIVE or INACTIVE. A string rather than a bool because that is the type the pinned provider's deletion\_protection attribute takes. Defaults to INACTIVE so dev tears down in one step; prod sets ACTIVE. | `string` | `"INACTIVE"` | no |
| <a name="input_domain_prefix"></a> [domain\_prefix](#input\_domain\_prefix) | Prefix for an optional Cognito-hosted sign-in domain. Null, the default, means no hosted-UI domain is created, which is the expected configuration: the auth service authenticates the submitted credential against the Cognito API server-side, so no browser redirect to a hosted page occurs. | `string` | `null` | no |
| <a name="input_explicit_auth_flows"></a> [explicit\_auth\_flows](#input\_explicit\_auth\_flows) | Authentication flows the confidential app client may initiate, as ALLOW\_-prefixed names. Defaults to USER\_PASSWORD\_AUTH for server-side sign-on. REFRESH\_TOKEN\_AUTH is forbidden because refresh-token rotation requires the auth service to use GetTokensFromRefreshToken instead. | `list(string)` | <pre>[<br/>  "ALLOW_USER_PASSWORD_AUTH"<br/>]</pre> | no |
| <a name="input_id_token_validity_minutes"></a> [id\_token\_validity\_minutes](#input\_id\_token\_validity\_minutes) | Identity token lifetime in minutes, carrying the cognito:groups claim that common-lib's JwtRoleConverter turns into authorities. Paired by main.tf with a token\_validity\_units block set to minutes. | `number` | `60` | no |
| <a name="input_logout_urls"></a> [logout\_urls](#input\_logout\_urls) | Absolute URLs Cognito may redirect to after a sign-out. Supplied by the calling root from the CloudFront distribution that serves the SPA, on the same reasoning as callback\_urls. | `list(string)` | `[]` | no |
| <a name="input_mfa_configuration"></a> [mfa\_configuration](#input\_mfa\_configuration) | Multi-factor posture for the user pool: OFF, ON (compulsory) or OPTIONAL (available but not required). Paired by main.tf with a software-token MFA mechanism, without which ON and OPTIONAL are rejected at apply. Production is structurally required to be ON; the default suits dev only. | `string` | `"OPTIONAL"` | no |
| <a name="input_name_prefix"></a> [name\_prefix](#input\_name\_prefix) | Lowercase token prefixed to environment-specific resource names such as the user pool, app client and Secrets Manager entries. The authorization groups are invariant carddemo-admin and carddemo-user values and deliberately do not inherit this prefix. | `string` | `"carddemo"` | no |
| <a name="input_password_minimum_length"></a> [password\_minimum\_length](#input\_password\_minimum\_length) | Minimum length Cognito enforces on a user password. Defaults well above the baseline's eight-character SEC-USR-PWD field, and cannot be lowered to it. | `number` | `14` | no |
| <a name="input_password_require_lowercase"></a> [password\_require\_lowercase](#input\_password\_require\_lowercase) | Whether a password must contain a lowercase letter. Part of the complexity policy the baseline had no equivalent for. | `bool` | `true` | no |
| <a name="input_password_require_numbers"></a> [password\_require\_numbers](#input\_password\_require\_numbers) | Whether a password must contain a digit. Part of the complexity policy the baseline had no equivalent for. | `bool` | `true` | no |
| <a name="input_password_require_symbols"></a> [password\_require\_symbols](#input\_password\_require\_symbols) | Whether a password must contain a symbol. Part of the complexity policy the baseline had no equivalent for. | `bool` | `true` | no |
| <a name="input_password_require_uppercase"></a> [password\_require\_uppercase](#input\_password\_require\_uppercase) | Whether a password must contain an uppercase letter. Part of the complexity policy the baseline had no equivalent for. | `bool` | `true` | no |
| <a name="input_refresh_token_validity_days"></a> [refresh\_token\_validity\_days](#input\_refresh\_token\_validity\_days) | Refresh token lifetime in days. Deliberately the longest-lived of the three tokens because it is the only revocable one. Paired by main.tf with a token\_validity\_units block set to days. | `number` | `30` | no |
| <a name="input_resource_server_identifier"></a> [resource\_server\_identifier](#input\_resource\_server\_identifier) | Identifier of the resource server registered for the migrated API. Cognito prefixes it onto each scope name, so "carddemo-api" with a scope "read" yields the token scope "carddemo-api/read". | `string` | `"carddemo-api"` | no |
| <a name="input_resource_server_scopes"></a> [resource\_server\_scopes](#input\_resource\_server\_scopes) | Custom scopes registered under the resource server, each a name and a human-readable description. Intended for machine-to-machine callers using the client-credentials grant; interactive sign-on tokens carry the pool's built-in aws.cognito.signin.user.admin scope instead. | <pre>list(object({<br/>    name        = string<br/>    description = string<br/>  }))</pre> | <pre>[<br/>  {<br/>    "description": "Read migrated CardDemo record data through the API.",<br/>    "name": "read"<br/>  },<br/>  {<br/>    "description": "Create or modify migrated CardDemo record data through the API.",<br/>    "name": "write"<br/>  }<br/>]</pre> | no |
| <a name="input_secret_recovery_window_in_days"></a> [secret\_recovery\_window\_in\_days](#input\_secret\_recovery\_window\_in\_days) | Recovery window applied to the Secrets Manager entries holding seed-user credentials. Either 0 for immediate deletion or 7 to 30 days; the domain excludes 1 to 6. Defaults to 0 so that destroy and re-apply do not collide on a reserved secret name. | `number` | `0` | no |
| <a name="input_seed_user_credential_revision"></a> [seed\_user\_credential\_revision](#input\_seed\_user\_credential\_revision) | Monotonic operator-controlled revision for deliberate seed-user temporary-password regeneration. Ordinary applies keep it stable, so users are not reset. | `number` | `1` | no |
| <a name="input_seed_users"></a> [seed\_users](#input\_seed\_users) | Identities created in the pool and assigned to the carddemo-admin or carddemo-user group by user\_type. Defaults to an EMPTY list, so a root that says nothing gets the pool, the app client and both groups with no users; a root wanting the baseline's ten demo identities (app/jcl/DUSRSECJ.jcl L35-L44) lists them explicitly. Carries no password: main.tf generates each initial credential during apply and stores it in Secrets Manager. | <pre>list(object({<br/>    user_id     = string<br/>    given_name  = string<br/>    family_name = string<br/>    user_type   = string<br/>  }))</pre> | `[]` | no |
| <a name="input_tags"></a> [tags](#input\_tags) | Additional tags merged over the calling root's provider default\_tags on the resources in this module that accept tags, which are the user pool and the seed-user Secrets Manager entries. The remaining Cognito resources expose no tags argument in the pinned provider. | `map(string)` | `{}` | no |
| <a name="input_temporary_password_validity_days"></a> [temporary\_password\_validity\_days](#input\_temporary\_password\_validity\_days) | Days a generated initial password remains valid before the seed user must be reset. Bounds the usefulness of a value that necessarily exists in Terraform state as well as in Secrets Manager. | `number` | `1` | no |

### Outputs

| Name | Description |
|------|-------------|
| <a name="output_admin_group_name"></a> [admin\_group\_name](#output\_admin\_group\_name) | Name of the group carrying the baseline's administrator user type: SEC-USR-TYPE 'A' (app/cpy/CSUSR01Y.cpy L22), whose condition name is 88 CDEMO-USRTYP-ADMIN VALUE 'A' at app/cpy/COCOM01Y.cpy L27. It appears in a token's group claim, where common-lib's JwtRoleConverter turns it into a Spring Security authority, and it is what routes a signed-in administrator to the admin screens -- the client-side equivalent of the transfer to COADM01C at app/cbl/COSGN00C.cbl L232. Without it no component can name the group it must test for. |
| <a name="output_app_client_secret_arn"></a> [app\_client\_secret\_arn](#output\_app\_client\_secret\_arn) | Secrets Manager ARN of the entry holding the app client's identifier and generated secret. Consumed by the IAM policy statement in the calling root that scopes secretsmanager:GetSecretValue for the auth service's task role to this one entry rather than to every secret in the account. NO SECRET VALUE IS PUBLISHED -- this is the reference through which one is resolved at run time. Without it that statement can only be written against a wildcard resource. |
| <a name="output_app_client_secret_name"></a> [app\_client\_secret\_name](#output\_app\_client\_secret\_name) | Secrets Manager name of the same entry. Published alongside the ARN because the two are used at different points: an IAM statement scopes to the ARN, while `aws secretsmanager get-secret-value --secret-id` takes the name, which is the form docs/runbooks/deploy.md uses. NO SECRET VALUE IS PUBLISHED. Without it the runbook would have to recover a name from an ARN by string surgery. |
| <a name="output_hosted_ui_domain"></a> [hosted\_ui\_domain](#output\_hosted\_ui\_domain) | Hosted-UI domain prefix of the user pool, or null when var.domain\_prefix was left at its default and no domain was created. The calling root consumes it only if it wires a hosted sign-in or sign-out URL; every other consumer ignores it. Because null is the default-path result, a caller that interpolates it without a null check produces a malformed URL rather than a plan-time error. |
| <a name="output_interactive_route_authorization_scopes"></a> [interactive\_route\_authorization\_scopes](#output\_interactive\_route\_authorization\_scopes) | Scope list carried by Cognito access tokens obtained through the direct interactive authentication API and required by api-gateway-http routes. The built-in aws.cognito.signin.user.admin value rejects ID tokens, which have no scope claim, without requiring a custom resource-server scope that USER\_PASSWORD\_AUTH never issues. |
| <a name="output_issuer_uri"></a> [issuer\_uri](#output\_issuer\_uri) | OpenID Connect issuer URI of the user pool, shaped https://cognito-idp.<region>.amazonaws.com/<user-pool-id>. Two consumers need this exact string verbatim: infra/modules/api-gateway-http takes it as cognito\_issuer\_uri and makes it its JWT authorizer's jwt\_configuration.issuer, and each Spring Boot service's OAuth2 resource-server issuer-uri property reads it back from Parameter Store where the calling root writes it. Publishing it already composed means neither consumer re-derives it and neither can get the composition wrong; without it every token-validating component would assemble its own copy of a value that must be identical in all of them. |
| <a name="output_resource_server_identifier"></a> [resource\_server\_identifier](#output\_resource\_server\_identifier) | Identifier of the Cognito resource server representing the CardDemo API. It is the namespace every scope below is qualified by, and the calling root writes it to Parameter Store for the services that declare required scopes. Without it a caller cannot tell which resource server a scope string belongs to. |
| <a name="output_resource_server_scope_identifiers"></a> [resource\_server\_scope\_identifiers](#output\_resource\_server\_scope\_identifiers) | Fully-qualified scope strings the resource server declares, as the provider composes them from the identifier and each scope name. infra/modules/api-gateway-http consumes them in route\_authorization\_scopes, where they become the scopes a route requires of a presented token. Without them the calling root would have to rebuild each string by hand, and any divergence would appear only as an authorization failure in production. |
| <a name="output_seed_user_secret_arns"></a> [seed\_user\_secret\_arns](#output\_seed\_user\_secret\_arns) | Secrets Manager ARNs of generated initial passwords, as a map keyed by an opaque 128-bit handle rather than a user id. Consumed by IAM policy statements in the calling root that scope secretsmanager:GetSecretValue to exactly these entries. No password or identity value is published; the username remains inside the encrypted secret value for authorized retrieval. |
| <a name="output_seed_user_secret_names"></a> [seed\_user\_secret\_names](#output\_seed\_user\_secret\_names) | Secrets Manager names of the same entries, keyed by the same opaque handle. Published alongside the ARNs because an IAM statement scopes to an ARN while the retrieval command in docs/runbooks/deploy.md takes a name. No password, user id or personal name is exposed through this output. |
| <a name="output_user_group_name"></a> [user\_group\_name](#output\_user\_group\_name) | Name of the group carrying the baseline's ordinary user type: SEC-USR-TYPE 'U' (app/cpy/CSUSR01Y.cpy L22), whose condition name is 88 CDEMO-USRTYP-USER VALUE 'U' at app/cpy/COCOM01Y.cpy L28. Consumed exactly as the administrator group is, and it routes to the main menu -- the client-side equivalent of the transfer to COMEN01C at app/cbl/COSGN00C.cbl L237. The two names together are the whole authorization domain, which the copybook closes at these two values. |
| <a name="output_user_pool_arn"></a> [user\_pool\_arn](#output\_user\_pool\_arn) | ARN of the user pool, for the IAM policy documents the calling root builds. It is what scopes the auth service's task-role statements for AdminCreateUser, AdminSetUserPassword and AdminAddUserToGroup to this one pool. Without it those statements can only name a wildcard resource, which is the opposite of the least-privilege posture the migration commits to. |
| <a name="output_user_pool_client_id"></a> [user\_pool\_client\_id](#output\_user\_pool\_client\_id) | Identifier of the app client the auth service authenticates through. infra/modules/api-gateway-http takes it in cognito\_app\_client\_ids and makes it the JWT authorizer's jwt\_configuration.audience, so a token whose audience claim falls outside that set is rejected at the edge before any integration runs; services/auth-service sends it as ClientId on every authentication call; and the calling root writes it to Parameter Store for both. Without it the edge cannot pin which client's tokens it accepts. |
| <a name="output_user_pool_endpoint"></a> [user\_pool\_endpoint](#output\_user\_pool\_endpoint) | Host and path of the user pool, shaped cognito-idp.<region>.amazonaws.com/<user-pool-id> with no scheme. It is the value issuer\_uri below is composed from, and it is what a caller needs when it must name the pool without a scheme or build a JWKS URL by hand. Without it such a caller would have to strip the scheme back off issuer\_uri. |
| <a name="output_user_pool_id"></a> [user\_pool\_id](#output\_user\_pool\_id) | Identifier of the Cognito user pool that replaces the USRSEC VSAM file defined at app/csd/CARDDEMO.CSD L88. The calling root writes it to Parameter Store; services/auth-service reads it from there and passes it as UserPoolId on every Cognito administrative call implementing the COUSR00C-COUSR03C user CRUD screens; an operator passes it as --user-pool-id. Without it nothing can address the pool at all. |
<!-- END_TF_DOCS -->
