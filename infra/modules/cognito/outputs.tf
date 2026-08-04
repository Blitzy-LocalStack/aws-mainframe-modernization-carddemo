# =============================================================================
# infra/modules/cognito/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete public contract of the CardDemo cognito module. Everything a
#   calling root, a sibling module or a running service can learn about this
#   user pool, it learns from the fourteen outputs below; nothing else in the
#   module is visible outside it. Cognito replaces the mainframe sign-on path
#   -- the USRSEC VSAM file defined at app/csd/CARDDEMO.CSD L88 and read by the
#   READ-USER-SEC-FILE paragraph of app/cbl/COSGN00C.cbl (L209-L257) -- so
#   these are the values the migrated system authenticates against. They are
#   consumed by infra/modules/api-gateway-http for its Cognito JWT authorizer,
#   by infra/envs/dev and infra/envs/prod which write them to Parameter Store,
#   by the OAuth2 resource-server block of every
#   services/*/src/main/resources/application*.yml that reads them back, and by
#   docs/runbooks/deploy.md for the seed-user credential procedure.
#
# Parameters:
#   None. An outputs file declares no inputs. This module's parameter surface
#   is the twenty-five variables in variables.tf beside it, each carrying its
#   own name, explicit type, description and validation. Recorded explicitly
#   because "this file has no parameters" and "this file's parameters are
#   undocumented" are indistinguishable to a reader otherwise.
#
# Returns:
#   Fourteen values, grouped below in the order a caller wires them: pool
#   identity, the OIDC issuer, the app client and a reference to its
#   credential, the API resource server, the two group names, the optional
#   hosted-UI domain, and the seed-user credential references. Every one
#   carries a `description`, which is this file's literal answer to Rule 1's
#   return-value clause and is also the only thing
#   infra/.terraform-docs.yml renders into the module README whose freshness CI
#   drift-checks -- that generator is configured `read-comments: false`, so a
#   comment here is invisible to it while a `description` is not.
#
# Errors:
#   None raised. An output evaluates an expression over resources main.tf has
#   already created, so this file has no validation surface and no failure mode
#   of its own. What carries weight instead is what is deliberately ABSENT,
#   because an absence leaves no trace at the point it was decided: no output
#   carries a credential value, none is marked `sensitive`, and none hard-codes
#   a region, an account identifier or an ARN. Each omission is justified
#   below or at the output it concerns.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: NO OUTPUT BELOW PUBLISHES A CREDENTIAL VALUE, AND
#     THAT ABSENCE IS THE POINT OF THE FILE. The baseline authenticated by
#     comparing a cleartext eight-character field -- app/cpy/CSUSR01Y.cpy L21,
#     compared at app/cbl/COSGN00C.cbl L223 -- and shipped its seed passwords
#     as committed in-stream JCL data. main.tf instead generates every initial
#     password with random_password during apply and hands it straight to
#     Secrets Manager; this file publishes the ARN and the NAME of each of
#     those entries and never their contents, so a credential is retrieved out
#     of band under the reader's own secretsmanager:GetSecretValue and
#     kms:Decrypt permissions and every retrieval is recorded in CloudTrail.
#     Alternatives Considered: a `sensitive = true` password output, for
#     operator convenience. Rejected, because `sensitive` is not a control that
#     applies to this problem: it redacts a value from console display and does
#     nothing else. The value is still written verbatim into the state of every
#     root that consumes the module and is still recoverable with one
#     `terraform output -json`, so the effect would be to turn a managed,
#     audited secret into an unmanaged, unaudited copy of itself sitting in
#     state -- reintroducing in a new place the exact defect this module exists
#     to remove.
#   - Assumptions: no output here is marked `sensitive`, and that uniformity is
#     a decision rather than an oversight. Every value published is either a
#     public identifier or a reference. A pool identifier, a client
#     identifier, an issuer URI and two group names all travel in ordinary
#     request traffic and inside every token the pool issues; a Secrets Manager
#     ARN or name identifies WHERE a credential lives and is useless to anyone
#     without GetSecretValue on the KMS-encrypted entry it points at. Marking
#     any of them sensitive would obstruct the workflows that need them --
#     a calling root writing them to Parameter Store, an operator reading them
#     with `terraform output` -- while protecting nothing. There is a
#     mechanical edge to the same rule, and it is the second independent reason
#     no credential output exists: an output whose expression reads a sensitive
#     attribute must itself be marked sensitive or Terraform fails during plan,
#     and the pinned provider declares
#     aws_cognito_user_pool_client.client_secret sensitive, so such an output
#     could not be written in the unmarked form used throughout this file even
#     if it were wanted.
#   - Trade-offs: the four rationale labels here are written in the plural form
#     that docs/CODE_DOCUMENTATION_STANDARD.md fixes as the single permitted
#     spelling, and that Rule 1 itself uses. main.tf beside this file uses the
#     singular abbreviations, so the two read differently and a reviewer
#     comparing only those two files may take it for an inconsistency. The
#     plural is kept anyway because these labels are grepped before they are
#     read: one spelling makes an audit of the tree against Rule 1's validation
#     gate complete, whereas two spellings of one category make it silently
#     partial, and a rationale a search cannot find is a rationale a review
#     cannot count. variables.tf and versions.tf in this same module already
#     use the plural form.
#   - Assumptions: five values a reader might expect are omitted deliberately.
#     The app client's `client_secret` attribute, published below only as a
#     reference for the reasons above. The seed users' `sub` and `status`
#     attributes, which are per-user runtime state with no consumer -- every
#     caller and runbook identifies a seed user by the eight-character
#     identifier from app/cpy/CSUSR01Y.cpy L18, which is already the key of
#     both secret maps. The pool's `creation_date` and
#     `estimated_number_of_users`, which are observability data rather than
#     contract. The hosted-UI domain's CloudFront attributes, which carry
#     meaning only for a custom domain this module does not create. And any
#     separate list of seed user identifiers, because the two secret maps are
#     already keyed by exactly that set and a third view of it could disagree
#     with them.
# =============================================================================

# -----------------------------------------------------------------------------
# Pool identity
# -----------------------------------------------------------------------------

# WHAT: the pool's own identifier, and the identifier embedded in issuer_uri.
# WHY : Assumptions: this is published even though issuer_uri already contains
#       it, because the two are consumed by different callers for different
#       reasons. A JWT authorizer wants the issuer as one opaque string, while
#       an administrative API call and an operator command want the bare
#       identifier. Making either caller recover one from the other by string
#       surgery is exactly the parsing this module exists to remove.
output "user_pool_id" {
  description = "Identifier of the Cognito user pool that replaces the USRSEC VSAM file defined at app/csd/CARDDEMO.CSD L88. The calling root writes it to Parameter Store; services/auth-service reads it from there and passes it as UserPoolId on every Cognito administrative call implementing the COUSR00C-COUSR03C user CRUD screens; an operator passes it as --user-pool-id. Without it nothing can address the pool at all."
  value       = aws_cognito_user_pool.this.id
}

output "user_pool_arn" {
  description = "ARN of the user pool, for the IAM policy documents the calling root builds. It is what scopes the auth service's task-role statements for AdminCreateUser, AdminSetUserPassword and AdminAddUserToGroup to this one pool. Without it those statements can only name a wildcard resource, which is the opposite of the least-privilege posture the migration commits to."
  value       = aws_cognito_user_pool.this.arn
}

# WHAT: the pool's host-and-path form, carrying no URI scheme.
# WHY : Assumptions: this is published in addition to issuer_uri, not instead
#       of it, because the scheme-less form is an interface in its own right --
#       it is the host a caller needs to reach the pool's JWKS document
#       directly, and the form AWS documents for naming a user pool as a
#       provider. Publishing it alongside the composed issuer also makes that
#       composition auditable by a caller rather than opaque, since the one is
#       visibly the other with a scheme prefixed.
output "user_pool_endpoint" {
  description = "Host and path of the user pool, shaped cognito-idp.<region>.amazonaws.com/<user-pool-id> with no scheme. It is the value issuer_uri below is composed from, and it is what a caller needs when it must name the pool without a scheme or build a JWKS URL by hand. Without it such a caller would have to strip the scheme back off issuer_uri."
  value       = aws_cognito_user_pool.this.endpoint
}

# -----------------------------------------------------------------------------
# OIDC issuer
# -----------------------------------------------------------------------------

# WHAT: the pool's OpenID Connect issuer, composed by prefixing the scheme onto
#       the pool's own endpoint attribute.
# WHY : Alternatives Considered: composing the issuer from a discovered region
#       instead, joining a data.aws_region attribute and the pool identifier
#       around a literal service prefix and partition suffix. Rejected on four
#       counts. It requires a `data` block in a file whose entire job is
#       outputs. data.aws_region reports the region the CALLING ROOT's provider
#       is configured for rather than the region the pool is in, so a pool
#       created through an aliased provider elsewhere would yield a wrong
#       issuer that still passes every validation and fails only later, when a
#       token is verified. It hard-codes both the service prefix and the
#       commercial partition suffix, which are wrong in GovCloud and in China
#       -- the pinned provider carries several other partition forms for this
#       same service. And in the pinned provider (hashicorp/aws ~> 6.56,
#       resolved 6.57.1) the region data source's `name` attribute is
#       deprecated, so that route also carries an attribute-rename hazard.
#       Reading the pool's endpoint is region-correct and partition-correct by
#       construction, because the value comes from the pool itself.
#       Assumptions: the endpoint attribute carries no scheme, so exactly one
#       has to be prefixed. Verified against the pinned provider's own schema
#       and against the form AWS documents for this same value, which is the
#       service host and the pool identifier with no scheme; the OIDC issuer is
#       that string with the scheme prepended. This is the one place in the
#       file where a template is genuinely required, so the interpolation here
#       is not the single-reference wrapper that tflint's
#       terraform_deprecated_interpolation rule rejects.
output "issuer_uri" {
  description = "OpenID Connect issuer URI of the user pool, shaped https://cognito-idp.<region>.amazonaws.com/<user-pool-id>. Two consumers need this exact string verbatim: infra/modules/api-gateway-http takes it as cognito_issuer_uri and makes it its JWT authorizer's jwt_configuration.issuer, and each Spring Boot service's OAuth2 resource-server issuer-uri property reads it back from Parameter Store where the calling root writes it. Publishing it already composed means neither consumer re-derives it and neither can get the composition wrong; without it every token-validating component would assemble its own copy of a value that must be identical in all of them."
  value       = "https://${aws_cognito_user_pool.this.endpoint}"
}

# -----------------------------------------------------------------------------
# App client, and a reference to its credential
# -----------------------------------------------------------------------------

# WHAT: the app client identifier every issued token names in its audience
#       claim.
# WHY : Assumptions: this client is CONFIDENTIAL rather than public -- main.tf
#       sets generate_secret = true -- so the identifier published here is one
#       half of a credential pair, and it is still safe to publish because a
#       client identifier is carried in the audience claim of every token the
#       pool issues and in every authorization request made against it. Only
#       the secret half must not travel, and it appears below solely as a
#       reference. This is recorded because the reflexive reading of a Cognito
#       client identifier -- that it is public because the client is public --
#       does not hold in this module: the browser never speaks to the pool.
#       ui/src/screens/signon posts to services/auth-service, which holds the
#       secret and computes the secret hash server-side, which is what keeps
#       the three verbatim sign-on replies at app/cbl/COSGN00C.cbl L242-L243,
#       L249 and L254 reachable instead of stranding them behind a hosted
#       sign-in page.
output "user_pool_client_id" {
  description = "Identifier of the app client the auth service authenticates through. infra/modules/api-gateway-http takes it in cognito_app_client_ids and makes it the JWT authorizer's jwt_configuration.audience, so a token whose audience claim falls outside that set is rejected at the edge before any integration runs; services/auth-service sends it as ClientId on every authentication call; and the calling root writes it to Parameter Store for both. Without it the edge cannot pin which client's tokens it accepts."
  value       = aws_cognito_user_pool_client.this.id
}

# WHAT: the ARN and the name of the Secrets Manager entry that holds this app
#       client's identifier and its generated secret -- the reference to the
#       credential, never the credential.
# WHY : Alternatives Considered: publishing the client secret itself, so the
#       calling root could inject it directly into the service's task
#       definition. Rejected in main.tf and rejected again here, because a
#       Terraform output is printed to the console, written into the state of
#       every root that consumes it, and read back with a single command --
#       publishing it would convert a managed secret into a printed one, and
#       the service resolves the value at run time under its own task-role
#       permissions in any case. Both attributes are published rather than one
#       because they answer different questions: an IAM policy statement scopes
#       to an ARN, while the retrieval command takes a name.
#       Assumptions: the `_arn` and `_name` suffixes are load-bearing in these
#       names. They make it structurally impossible to read either output as
#       the secret's contents, which a bare name would not, and they match the
#       shape of the seed-user maps further down so the whole credential
#       surface of this module reads the same way.
output "app_client_secret_arn" {
  description = "Secrets Manager ARN of the entry holding the app client's identifier and generated secret. Consumed by the IAM policy statement in the calling root that scopes secretsmanager:GetSecretValue for the auth service's task role to this one entry rather than to every secret in the account. NO SECRET VALUE IS PUBLISHED -- this is the reference through which one is resolved at run time. Without it that statement can only be written against a wildcard resource."
  value       = aws_secretsmanager_secret.app_client.arn
}

output "app_client_secret_name" {
  description = "Secrets Manager name of the same entry. Published alongside the ARN because the two are used at different points: an IAM statement scopes to the ARN, while `aws secretsmanager get-secret-value --secret-id` takes the name, which is the form docs/runbooks/deploy.md uses. NO SECRET VALUE IS PUBLISHED. Without it the runbook would have to recover a name from an ARN by string surgery."
  value       = aws_secretsmanager_secret.app_client.name
}

# -----------------------------------------------------------------------------
# API resource server
# -----------------------------------------------------------------------------

# WHAT: the resource server's identifier, and the fully-qualified names of the
#       scopes declared beneath it.
# WHY : Assumptions: the scope identifiers are published exactly as the
#       provider computes them rather than rebuilt by a caller. A scope's wire
#       form is the resource-server identifier and the scope name joined by a
#       separator, and infra/modules/api-gateway-http needs precisely that form
#       in route_authorization_scopes to gate a route. Letting the calling root
#       perform the join itself would place the same rule in two files, and a
#       mismatch between them would surface as a request being refused at run
#       time rather than as a plan-time error.
output "resource_server_identifier" {
  description = "Identifier of the Cognito resource server representing the CardDemo API. It is the namespace every scope below is qualified by, and the calling root writes it to Parameter Store for the services that declare required scopes. Without it a caller cannot tell which resource server a scope string belongs to."
  value       = aws_cognito_resource_server.this.identifier
}

output "resource_server_scope_identifiers" {
  description = "Fully-qualified scope strings the resource server declares, as the provider composes them from the identifier and each scope name. infra/modules/api-gateway-http consumes them in route_authorization_scopes, where they become the scopes a route requires of a presented token. Without them the calling root would have to rebuild each string by hand, and any divergence would appear only as an authorization failure in production."
  value       = aws_cognito_resource_server.this.scope_identifiers
}

# -----------------------------------------------------------------------------
# Group names -- the cross-language authorization contract
# -----------------------------------------------------------------------------

# WHAT: the two group names that appear in a token's group claim.
# WHY : Assumptions: these are outputs even though they are effectively
#       constants, because they are a contract spanning three languages rather
#       than a value any one of them owns.
#       services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java
#       matches these exact strings to turn the group claim into Spring
#       Security authorities, and ui/src/hooks/useAuth.ts tests them to decide
#       whether the admin routes are reachable. Emitting them from the module
#       that creates them lets the calling root write one authoritative value
#       to Parameter Store, instead of the same literal being maintained
#       independently in HCL, Java and TypeScript and drifting in whichever one
#       is edited last.
#       Refactoring Rationale: both read the group resource's own `name`
#       attribute rather than restating the string or re-reading the local that
#       composed it, so an output cannot drift from the group that was actually
#       created -- if a later change alters how main.tf builds these names,
#       these outputs follow it without being touched.
output "admin_group_name" {
  description = "Name of the group carrying the baseline's administrator user type: SEC-USR-TYPE 'A' (app/cpy/CSUSR01Y.cpy L22), whose condition name is 88 CDEMO-USRTYP-ADMIN VALUE 'A' at app/cpy/COCOM01Y.cpy L27. It appears in a token's group claim, where common-lib's JwtRoleConverter turns it into a Spring Security authority, and it is what routes a signed-in administrator to the admin screens -- the client-side equivalent of the transfer to COADM01C at app/cbl/COSGN00C.cbl L232. Without it no component can name the group it must test for."
  value       = aws_cognito_user_group.admin.name
}

output "user_group_name" {
  description = "Name of the group carrying the baseline's ordinary user type: SEC-USR-TYPE 'U' (app/cpy/CSUSR01Y.cpy L22), whose condition name is 88 CDEMO-USRTYP-USER VALUE 'U' at app/cpy/COCOM01Y.cpy L28. Consumed exactly as the administrator group is, and it routes to the main menu -- the client-side equivalent of the transfer to COMEN01C at app/cbl/COSGN00C.cbl L237. The two names together are the whole authorization domain, which the copybook closes at these two values."
  value       = aws_cognito_user_group.user.name
}

# -----------------------------------------------------------------------------
# Optional hosted-UI domain
# -----------------------------------------------------------------------------

# WHAT: the hosted-UI domain prefix when one was created, and null when none
#       was.
# WHY : Assumptions: null is the ordinary result here rather than an error
#       state, and a consumer has to handle it. var.domain_prefix defaults to
#       null because the browser authenticates through services/auth-service
#       against the Cognito API rather than through a hosted sign-in page, so
#       main.tf's domain resource has a count of zero on the default path and
#       this output is null in both environments as they are configured today.
#       Alternatives Considered: indexing element zero inside a `try` that
#       falls back to null, which returns the same value. `one` is used instead
#       because it states the intent exactly -- null for an empty list, the
#       single element for a one-element list, and an error for two or more --
#       whereas `try` would also absorb an unrelated failure inside the
#       expression and misreport it as an absent domain.
output "hosted_ui_domain" {
  description = "Hosted-UI domain prefix of the user pool, or null when var.domain_prefix was left at its default and no domain was created. The calling root consumes it only if it wires a hosted sign-in or sign-out URL; every other consumer ignores it. Because null is the default-path result, a caller that interpolates it without a null check produces a malformed URL rather than a plan-time error."
  value       = one(aws_cognito_user_pool_domain.this[*].domain)
}

# -----------------------------------------------------------------------------
# Seed-user credential references
# -----------------------------------------------------------------------------

# WHAT: one Secrets Manager ARN and one Secrets Manager name per seed user,
#       each keyed by that user's identifier -- references to the generated
#       initial passwords, never the passwords.
# WHY : Refactoring Rationale: the retrieval path is deliberately out of band.
#       An operator following docs/runbooks/deploy.md reads a seed user's
#       initial password with `aws secretsmanager get-secret-value --secret-id`
#       against the name below, which requires secretsmanager:GetSecretValue on
#       that entry and kms:Decrypt on the customer-managed key, and which
#       leaves a record in CloudTrail. That audit trail is the entire reason
#       the value is not published here: the baseline's seed passwords were
#       legible to anyone holding the repository, and a Terraform output would
#       make these legible to anyone holding the state, which is the same
#       defect relocated rather than fixed.
#       Alternatives Considered: a list of objects, or two parallel lists,
#       rather than two maps. Rejected because a list re-indexes when a user is
#       added to or removed from var.seed_users, so an operator who looked up a
#       user by position would afterwards read another user's entry with no
#       indication anything had changed. A map keyed by the identifier is
#       stable under insertion and removal, and the key is the eight-character
#       SEC-USR-ID from app/cpy/CSUSR01Y.cpy L18 -- the same key main.tf uses
#       in local.seed_users_by_id to drive every per-user resource, so the two
#       views cannot disagree.
output "seed_user_secret_arns" {
  description = "Secrets Manager ARNs of the generated initial passwords, as a map keyed by the eight-character user identifier (SEC-USR-ID, app/cpy/CSUSR01Y.cpy L18). Consumed by the IAM policy statements in the calling root that scope secretsmanager:GetSecretValue to exactly these entries rather than to every secret in the account. NO PASSWORD VALUE IS PUBLISHED -- this is the reference through which one is retrieved. Without it a least-privilege policy for seed-credential retrieval cannot be expressed."
  value       = { for user_id, secret in aws_secretsmanager_secret.seed_user : user_id => secret.arn }
}

output "seed_user_secret_names" {
  description = "Secrets Manager names of the same entries, keyed the same way. Published alongside the ARNs because the two serve different steps: an IAM statement scopes to an ARN, while the `aws secretsmanager get-secret-value --secret-id` call in docs/runbooks/deploy.md takes a name. NO PASSWORD VALUE IS PUBLISHED. Without it the runbook would have to recover each name from an ARN by string surgery."
  value       = { for user_id, secret in aws_secretsmanager_secret.seed_user : user_id => secret.name }
}

