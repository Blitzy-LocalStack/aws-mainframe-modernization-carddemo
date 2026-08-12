# =============================================================================
# infra/modules/cognito/outputs.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete public contract of the CardDemo cognito module. Everything a
#   calling root, a sibling module or a running service can learn about this
#   user pool, it learns from the seventeen outputs below; nothing else in the
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
#   is the twenty-six variables in variables.tf beside it, each carrying its
#   own name, explicit type, description and validation. Recorded explicitly
#   because "this file has no parameters" and "this file's parameters are
#   undocumented" are indistinguishable to a reader otherwise.
#
# Returns:
#   Seventeen values, grouped below in the order a caller wires them: pool
#   identity (user_pool_id, user_pool_arn, user_pool_endpoint), the OIDC issuer
#   (issuer_uri), the app client and a reference to its credential
#   (user_pool_client_id, app_client_secret_arn, app_client_secret_name), the
#   name prefix beneath which credentials for pool accounts are written
#   (credential_secret_name_prefix), the API resource server
#   (resource_server_identifier, interactive_route_authorization_scopes,
#   resource_server_scope_identifiers), the two group names (admin_group_name,
#   user_group_name), the optional hosted-UI domain (hosted_ui_domain), and the
#   seed-user credential references (seed_user_secret_arns,
#   seed_user_secret_names, seed_user_subjects).
#   WHY the count is enumerated rather than just stated: an earlier revision
#   said fourteen while sixteen were declared, and a bare number gives a reader
#   no way to tell WHICH two were missing. Naming every output makes the count
#   checkable against `grep -c '^output "' outputs.tf` and makes an addition
#   that forgets this header visible as a missing name rather than as arithmetic.
#   Every one carries a `description`, which is this file's literal answer to Rule 1's
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
#     password inside an apply-time bootstrap process and hands it straight to
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

# WHY : Refactoring Rationale: the description below named AdminSetUserPassword
#       among the actions this ARN scopes. The calling root's auth task-role
#       statement grants AdminCreateUser, AdminAddUserToGroup and AdminDeleteUser
#       and deliberately does NOT grant AdminSetUserPassword, because
#       services/auth-service creates no credential -- that is the whole point of
#       moving the plaintext password at app/cpy/CSUSR01Y.cpy L21 out of the
#       record. Naming an action no statement carries described a privilege this
#       output does not scope, so the list was corrected to the three that exist.
output "user_pool_arn" {
  description = "ARN of the user pool, for the IAM policy documents the calling root builds. It is what scopes the auth service's task-role statements for AdminCreateUser, AdminAddUserToGroup and AdminDeleteUser to this one pool. Without it those statements can only name a wildcard resource, which is the opposite of the least-privilege posture the migration commits to."
  value       = aws_cognito_user_pool.this.arn
}

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
  value       = aws_cloudformation_stack.app_client.outputs["ClientId"]
}

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
  depends_on  = [terraform_data.app_client_secret_rotation]
}

output "app_client_secret_name" {
  description = "Secrets Manager name of the same entry. Published alongside the ARN because the two are used at different points: an IAM statement scopes to the ARN, while `aws secretsmanager get-secret-value --secret-id` takes the name, which is the form docs/runbooks/deploy.md uses. NO SECRET VALUE IS PUBLISHED. Without it the runbook would have to recover a name from an ARN by string surgery."
  value       = aws_secretsmanager_secret.app_client.name
  depends_on  = [terraform_data.app_client_secret_rotation]
}

# WHY : (1) Refactoring Rationale: this output exists because the auth service now
#       creates pool accounts at run time and has to put each new account's
#       one-time credential somewhere its owner can collect it. It composes that
#       entry's name itself, from this prefix plus a digest of the identifier, and
#       before this output existed the calling root had no way to tell it which
#       prefix to use except by restating this module's own naming rule -- so a
#       change to the rule here would have left the service writing under a name
#       no IAM statement in the root scoped, and every runtime user creation would
#       have failed at the store rather than at plan time.
#       (2) Assumptions: it is the SAME root the seed-user entries at line 1135 of
#       this module's main.tf are written under. Both hold a generated initial
#       credential for one pool identity, and they differ only in which side
#       created that identity -- Terraform for a seeded one, the service for a
#       runtime one -- so putting them under one root means an audit or an
#       operator grant covering pool credentials is one path rather than two that
#       could drift apart.
#       (3) Trade-offs: a prefix rather than a set of names, which is the opposite
#       of how the seed-user entries above are published. It has to be: the names
#       under it are derived at run time from identifiers that do not exist at
#       apply time, so there is no set to enumerate. The consequence is that the
#       root's IAM statement scopes to a wildcard BENEATH this prefix, which is
#       one level less precise than a per-entry ARN and is why the prefix is
#       narrow enough that nothing else is written beneath it.
#       (4) NO SECRET VALUE IS PUBLISHED: this is a name fragment, and the
#       credentials stored beneath it are readable only with GetSecretValue on the
#       entry together with a grant on the key it is encrypted with.
output "credential_secret_name_prefix" {
  description = "Secrets Manager name prefix beneath which one-time credentials for pool accounts are written, as `<name_prefix>-<environment>/cognito`. The auth service composes an entry name from this prefix, a `/runtime-user/` infix and a digest of the account identifier; the calling root passes it to that service and scopes the service's write grant to a wildcard beneath it. It is the same root the seeded initial-password entries use. No secret value is published."
  value       = local.secret_name_prefix
}

# -----------------------------------------------------------------------------
# API resource server
# -----------------------------------------------------------------------------

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

# WHY : Assumptions: the built-in `aws.cognito.signin.user.admin` value is the
#       only scope a USER_PASSWORD_AUTH access token actually carries, so it is
#       the only value a route can require without rejecting every interactive
#       sign-on. Requiring it at the authorizer is what makes an ID token
#       unusable as a bearer credential -- an ID token carries no scope claim at
#       all, so a route with any scope requirement refuses it.
#       Alternatives Considered: requiring one of the custom resource-server
#       scopes published above. Rejected because those are issued only to a
#       client that completed an OAuth flow and requested them; the interactive
#       flow this system uses never receives one, so every protected route would
#       return 401 for a correctly authenticated operator.
output "interactive_route_authorization_scopes" {
  description = "Scope list carried by Cognito access tokens obtained through the direct interactive authentication API and required by api-gateway-http routes. The built-in aws.cognito.signin.user.admin value rejects ID tokens, which have no scope claim, without requiring a custom resource-server scope that USER_PASSWORD_AUTH never issues."
  value       = ["aws.cognito.signin.user.admin"]
}

output "resource_server_scope_identifiers" {
  description = "Fully-qualified scope strings the resource server declares, as the provider composes them from the identifier and each scope name. infra/modules/api-gateway-http consumes them in route_authorization_scopes, where they become the scopes a route requires of a presented token. Without them the calling root would have to rebuild each string by hand, and any divergence would appear only as an authorization failure in production."
  value       = aws_cognito_resource_server.this.scope_identifiers
}

# -----------------------------------------------------------------------------
# Group names -- the invariant cross-language authorization contract
# -----------------------------------------------------------------------------

# WHY : Assumptions: these two names are IMMUTABLE CONSTANTS, not configuration,
#       and these outputs publish them rather than parameterise them. The AAP
#       identity contract fixes the pair, and three languages compile it:
#       services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java
#       holds them as ADMIN_AUTHORITY and USER_AUTHORITY, ui/src/hooks/useAuth.ts
#       exports them as ADMIN_GROUP and USER_GROUP, and the
#       carddemo.security.*-group-name defaults in all seven request-serving
#       services spell them literally. main.tf fixes both values independently of
#       name_prefix, and both outputs read the group resources' own names rather
#       than restating the literals.
#       Refactoring Rationale: an earlier revision of this comment claimed that
#       emitting the names "lets the calling root write one authoritative value
#       to Parameter Store". NO ROOT DOES THAT, and the claim was corrected here
#       rather than made true, for a reason that outlives the comment:
#       JwtRoleConverter's constructor refuses startup when a configured name
#       differs from its compiled authority, so an injected channel could only
#       ever carry the value Java already fixes. Injection would model an
#       unvariable value as configuration and would make a rename look supported
#       when it is a coordinated three-language change. The two group resources
#       in main.tf therefore carry a lifecycle precondition pinning each frozen
#       value, so a rename fails the plan with the list of consumers that must
#       change in the same commit -- which is the plan-visible dependency that
#       matters, expressed where the rename would actually happen.
#       Assumptions: the consumer of both outputs is the calling root's grouped
#       re-export, output "identity" in infra/envs/{dev,prod}/outputs.tf, which
#       publishes this module's whole output map. That makes each name readable
#       with `terraform output identity` as the one authoritative source an
#       operator or script can quote, without any sibling module having to
#       accept a value it could not vary.
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
#       Alternatives Considered: keying these maps by the eight-character
#       SEC-USR-ID from app/cpy/CSUSR01Y.cpy L18, which would make operator
#       lookup convenient. Rejected because Terraform outputs are metadata too:
#       pairing a username with a secret ARN would recreate the identity leak
#       removed from Secrets Manager names and descriptions. The opaque random
#       handle is stable under insertion and removal, while the privileged
#       secret value still carries the username for an operator authorized to
#       retrieve it.
output "seed_user_secret_arns" {
  description = "Secrets Manager ARNs of generated initial passwords, as a map keyed by an opaque 128-bit handle rather than a user id. Published so that an operator can scope a secretsmanager:GetSecretValue grant, or an audit query, to exactly these entries; no root currently reads this output, because seed retrieval is an out-of-band operator step performed with an already-privileged principal rather than a Terraform-wired one. No password or identity value is published; the username remains inside the encrypted secret value for authorized retrieval."
  value = {
    for user_id, secret in aws_secretsmanager_secret.seed_user :
    random_id.seed_user_secret[user_id].hex => secret.arn
  }
  depends_on = [terraform_data.seed_user_credential]
}

output "seed_user_secret_names" {
  description = "Secrets Manager names of the same entries, keyed by the same opaque handle. Published alongside the ARNs because an IAM statement scopes to an ARN while the retrieval command documented in this module README takes a name; that command is the only consumer today, and no root reads this output. No password, user id or personal name is exposed through this output."
  value = {
    for user_id, secret in aws_secretsmanager_secret.seed_user :
    random_id.seed_user_secret[user_id].hex => secret.name
  }
  depends_on = [terraform_data.seed_user_credential]
}

# WHY : Refactoring Rationale: this output exists because the pool and auth.users
#       have to agree, and until now nothing carried the value that makes them
#       agree. services/auth-service/src/main/resources/db/migration/V1__auth.sql
#       declares cognito_sub UUID NOT NULL UNIQUE and seeds no rows, so every row
#       the ETL loads from USRSEC needs a subject that only Cognito can mint. The
#       reader at data-migration/src/carddemo_migration/readers/usrsec.py loads
#       the profile fields and no credential -- the table has no password column
#       -- which leaves the subject as the one column it cannot derive from the
#       80-byte record. Publishing it here, keyed by the eight-character
#       SEC-USR-ID that is the primary key of that table, is what closes the join.
#       Alternatives Considered: having the ETL resolve each subject at load time
#       with cognito-idp admin-get-user. Rejected on two grounds: it would give
#       the migration task a Cognito read grant it needs for nothing else, and it
#       would make the load depend on the pool being reachable from wherever the
#       ETL runs, turning a data step into an identity-provider dependency.
#       Alternatives Considered: keying this map by the same opaque handle the two
#       secret outputs use. Rejected because it would be useless -- the consumer's
#       whole requirement is the pairing of user id to subject, and an opaque
#       handle it cannot resolve back to a user id answers nothing.
#       Trade-offs: this DOES pair a user id with a value, which the two outputs
#       above deliberately refuse to do. The distinction is what is on the other
#       side of the pairing. Those two pair a user id with the LOCATION OF ITS
#       PASSWORD, and possession of the state would then be a map of whose
#       credential to fetch. A subject is not a credential and grants nothing: it
#       is an opaque identifier the identity provider already puts in the `sub`
#       claim of every token that user presents, and every user id here is
#       already legible in the calling root's seed_users input. So the pairing
#       adds no disclosure, while withholding it would leave the NOT NULL column
#       unsatisfiable.
output "seed_user_subjects" {
  description = "Cognito subject (sub) of each seed identity, as a map keyed by the eight-character SEC-USR-ID. Consumed by the calling root, which publishes it to Parameter Store so the ETL can populate auth.users.cognito_sub, declared UUID NOT NULL UNIQUE in V1__auth.sql. Carries no credential: a subject is the opaque identifier already present in the sub claim of every token the user presents."
  value = {
    for user_id, user in aws_cognito_user.seed_user :
    user_id => user.sub
  }
}
