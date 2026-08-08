# =============================================================================
# infra/modules/cognito/variables.tf
# -----------------------------------------------------------------------------
# Purpose:
#   The complete input surface of the CardDemo cognito module. Cognito replaces
#   the mainframe sign-on path: the USRSEC VSAM file defined at
#   app/csd/CARDDEMO.CSD L88 and read by the READ-USER-SEC-FILE paragraph of
#   app/cbl/COSGN00C.cbl (L209-L257). Every value declared here is consumed by
#   main.tf to build the user pool, its password and threat-protection policy,
#   the browser app client, the carddemo-admin and carddemo-user groups that
#   carry the baseline's 'A' and 'U' user types, the optional hosted-UI domain,
#   and the ten seed users the baseline shipped. Nothing else configures this
#   module, so an input missing from this file is a value main.tf cannot use.
#
# Parameters:
#   Every variable below is one parameter of that surface, and each carries its
#   name, an explicit `type` and a `description` -- the three things a caller
#   needs in order to supply it -- plus a WHAT/WHY comment wherever the choice
#   is not evident from the name. They are grouped by the resource they
#   configure rather than alphabetically, so that a reader following main.tf
#   meets them in the same order: identity, encryption, password policy, threat
#   protection, app client, hosted-UI domain, environment parameterization,
#   seed users, tags.
#
# Returns:
#   Nothing. A variables file declares inputs and evaluates to no value of its
#   own; this module's return surface -- user pool id and ARN, app client id,
#   group names, seed-user secret names -- is declared in outputs.tf beside it.
#   Recorded explicitly because "returns nothing" and "returns undocumented"
#   are indistinguishable to a reader otherwise.
#
# Errors:
#   Raised by the `validation` blocks below, which are this file's only error
#   surface. Each rejects an out-of-domain value during plan, before any
#   resource is touched, and each error_message names the authority for the
#   bound it enforces: a copybook field and its width, or a limit read from the
#   pinned provider. Inputs with no validation block are those whose only real
#   constraint is a free-form string the provider itself accepts as given.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: NO VARIABLE HERE ACCEPTS A CREDENTIAL, AND THAT
#     ABSENCE IS THE POINT OF THE FILE. The baseline authenticated by comparing
#     a cleartext eight-character field (app/cpy/CSUSR01Y.cpy L21, compared at
#     app/cbl/COSGN00C.cbl L223) whose seed values are committed as in-stream
#     JCL data at app/jcl/DUSRSECJ.jcl L35-L44 -- ten users sharing one
#     literal, legible to anyone holding the repository. main.tf instead
#     generates each initial password inside a bootstrap process during apply and
#     hands it straight to Secrets Manager, so no credential is ever authored
#     into this tree.
#     Alternatives Considered: a seed-user password input, or a password member
#     on the seed_users object type. Both rejected, because such an input has
#     to be fed either from a terraform.tfvars in infra/envs/dev or
#     infra/envs/prod -- both tracked in version control -- or from a CI
#     variable, and either route reintroduces the exact defect this module
#     exists to remove. An absent declaration cannot be read, so the decision
#     is recorded here and again at seed_users rather than left to inference.
#   - Assumptions: this is a child module, invoked as
#     source = "../../modules/cognito" by infra/envs/dev and infra/envs/prod,
#     and it inherits their provider. That is why no `region` and no
#     `default_tags` variable appears: both are provider configuration the
#     calling root already owns, and restating them here would let a module
#     input silently disagree with the provider actually in effect. The `tags`
#     variable at the end is additive to those default tags, never a
#     replacement for them.
#   - Assumptions: every attribute name, accepted-value set and numeric bound
#     asserted in the comments below was read from the pinned provider
#     (hashicorp/aws ~> 6.56, resolved 6.57.1) by dumping its schema and then
#     submitting deliberately invalid values to see what it refuses. None was
#     inferred from documentation prose, because several are counter-intuitive:
#     deletion_protection is a string rather than a bool, a Secrets Manager
#     recovery window of 3 is refused outright, and a token validity of 60 with
#     no unit block is read as 60 hours and rejected.
#   - Trade-offs: several defaults here are deliberately stronger than the
#     baseline rather than faithful to it -- password length, character-class
#     complexity, availability of a second factor, and uniform sign-on failure
#     responses. Functional parity governs everywhere else in this migration;
#     identity is the one place parity is explicitly declined. What is accepted
#     in exchange is that a baseline credential would not satisfy the target
#     policy, which is the intent: porting an eight-character cleartext
#     password unchanged would carry the defect forward under the name of
#     parity.
# =============================================================================

# -----------------------------------------------------------------------------
# Naming and identity
# -----------------------------------------------------------------------------

# WHY : Assumptions: the bound is not cosmetic. main.tf builds names by
#       concatenating this value, the environment and a per-resource suffix, so
#       the widest composed name is a Secrets Manager path carrying an
#       eight-character user id from app/cpy/CSUSR01Y.cpy L18. Capping the
#       prefix keeps every such name inside the tightest limit any of them
#       meets, and restricting the charset to lowercase letters, digits and
#       hyphens keeps one value legal in all three namespaces at once --
#       Secrets Manager paths, Cognito names, and the DNS-style label a
#       hosted-UI domain prefix has to be.
#       Alternatives Considered: accepting any string and letting the services
#       reject a bad one at apply. Rejected because that failure arrives after
#       part of the module has already been created, leaving a half-built pool
#       to clean up by hand, whereas a validation block fails during plan.
variable "name_prefix" {
  description = "Lowercase token prefixed to environment-specific resource names such as the user pool, app client and Secrets Manager entries. The authorization groups are invariant carddemo-admin and carddemo-user values and deliberately do not inherit this prefix."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,19}$", var.name_prefix))
    error_message = "name_prefix must be 2-20 characters, start with a lowercase letter, and contain only lowercase letters, digits and hyphens. The charset is the intersection of what Secrets Manager names, Cognito names and a DNS-style hosted-UI domain label all accept; the 20-character ceiling leaves room for the environment and an eight-character user id from SEC-USR-ID (app/cpy/CSUSR01Y.cpy L18) in every composed name."
  }
}

# WHY : Assumptions: deliberately has no default, unlike name_prefix. The value
#       selects the sizing and retention posture below, and a default would let
#       a root that forgot to set it apply the other environment's posture
#       under the right name -- a prod pool tagged prod but carrying dev's
#       deletion and recovery settings. Requiring it makes that mistake a plan
#       error instead.
#       Assumptions: the domain is closed at two values because AAP 0.2.1.1
#       parameterizes exactly two roots, infra/envs/dev and infra/envs/prod. It
#       is worth noting the asymmetry with the sibling infra/bootstrap, which
#       declares no environment variable at all: bootstrap provisions the state
#       backend once per AWS account and is therefore environment-agnostic,
#       whereas this module is instantiated once per environment.
variable "environment" {
  description = "Deployment environment this user pool serves, either dev or prod. Selects the sizing and retention posture and distinguishes composed resource names within a single AWS account."
  type        = string

  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment must be exactly \"dev\" or \"prod\" -- the only two roots this infrastructure package parameterizes (infra/envs/dev and infra/envs/prod). Add a third root before adding a third value here."
  }
}

# -----------------------------------------------------------------------------
# Encryption input, supplied by the calling root from infra/modules/kms
# -----------------------------------------------------------------------------

# WHY : Assumptions: the key is created by infra/modules/kms and passed in by
#       infra/envs/dev or infra/envs/prod. This module calls no sibling module
#       and resolves no data source for it, so the ARN can only arrive as an
#       input -- which also keeps key ownership and key rotation in the one
#       module responsible for them.
#       Alternatives Considered: giving this a default of null and letting
#       Secrets Manager fall back to the AWS-managed key. Rejected outright:
#       the fallback is silent and it still encrypts, so the module would
#       appear to work while quietly dropping the customer-managed-key
#       requirement that AAP 0.4.1.9 states for exactly this data. A required
#       variable turns that from an invisible downgrade into a plan error.
#       Also considered and rejected: reconstructing the ARN in this module
#       from an account id and an alias, which would need both an account
#       lookup and a hard-coded partition -- neither of which belongs in a
#       module that already receives the value it needs.
variable "secrets_kms_key_arn" {
  description = "ARN of the customer-managed KMS key used to encrypt the Secrets Manager entries that hold each seed user's generated initial password. Required with no default: supplied by the calling root from the kms module's output so that a fallback to the AWS-managed key cannot happen unnoticed."
  type        = string

  validation {
    condition     = startswith(var.secrets_kms_key_arn, "arn:") && strcontains(var.secrets_kms_key_arn, ":kms:")
    error_message = "secrets_kms_key_arn must be a KMS key ARN: it has to begin with \"arn:\" and name the kms service. A key id, an alias name or another service's ARN would be accepted by Terraform here and then rejected by Secrets Manager at apply, after part of this module had already been created."
  }
}

# -----------------------------------------------------------------------------
# Password policy. These are POLICY settings, not credentials: they describe the
# shape a password must have, and none of them carries a password value.
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: the baseline field is SEC-USR-PWD PIC X(08)
#       (app/cpy/CSUSR01Y.cpy L21) -- exactly eight characters, compared in the
#       clear at app/cbl/COSGN00C.cbl L223. The default here is deliberately
#       longer than that, and the validation floor deliberately refuses to let a
#       caller configure the baseline's own length back in. Porting 8 unchanged
#       would preserve the number while discarding the reason it was ever
#       adequate, which was nothing.
#       Trade-offs: a longer minimum normally trades usability for strength, but
#       not here -- the bootstrap script generates these passwords, and
#       the only human contact with one is a single change at first sign-in. The
#       usual objection therefore does not apply, which is why the default sits
#       above the common enterprise floor rather than at it.
#       Assumptions: the ceiling of 99 is the pinned provider's own upper bound,
#       observed by submitting 100 and reading the rejection; the floor of 12 is
#       this module's posture rather than the provider's, whose floor is 6.
variable "password_minimum_length" {
  description = "Minimum length Cognito enforces on a user password. Defaults well above the baseline's eight-character SEC-USR-PWD field, and cannot be lowered to it."
  type        = number
  default     = 14

  validation {
    condition     = var.password_minimum_length >= 12 && var.password_minimum_length <= 99
    error_message = "password_minimum_length must be between 12 and 99. 99 is the pinned provider's upper bound for password_policy.minimum_length. The floor is 12 rather than the provider's 6 so that no caller can configure a policy weaker than the migration's documented improvement on SEC-USR-PWD PIC X(08) (app/cpy/CSUSR01Y.cpy L21)."
  }
}

# WHY : Assumptions: one comment covers all four because they are one decision,
#       not four -- the baseline enforced no complexity rule whatsoever, so
#       there is no per-class baseline behaviour to preserve or diverge from
#       individually, and repeating the same justification four times would say
#       less rather than more.
#       Trade-offs: requiring every class narrows the space of acceptable
#       passwords, which is a genuine cost when a human chooses one. Accepted
#       for the same reason as the length above: these values are generated, so
#       the constraint is absorbed by the bootstrap generator rather than by a person.
#       They are variables rather than hard-coded true so that the security
#       posture stays visible to the operator reading the environment root,
#       instead of being buried where only a module edit could reveal it.
variable "password_require_lowercase" {
  description = "Whether a password must contain a lowercase letter. Part of the complexity policy the baseline had no equivalent for."
  type        = bool
  default     = true
}

variable "password_require_uppercase" {
  description = "Whether a password must contain an uppercase letter. Part of the complexity policy the baseline had no equivalent for."
  type        = bool
  default     = true
}

variable "password_require_numbers" {
  description = "Whether a password must contain a digit. Part of the complexity policy the baseline had no equivalent for."
  type        = bool
  default     = true
}

variable "password_require_symbols" {
  description = "Whether a password must contain a symbol. Part of the complexity policy the baseline had no equivalent for."
  type        = bool
  default     = true
}

# WHY : Trade-offs: each seed user's initial password is generated during apply,
#       which means it exists in Terraform state as well as in Secrets Manager.
#       A short window bounds how long that copy is worth anything, and it is
#       the only control this module has over a value it cannot avoid producing.
#       The cost is real and is named rather than glossed: apply on one day and
#       first sign-in some days later, and the credential has lapsed. The remedy
#       is an operator-initiated reset through the Cognito admin API, which is
#       preferable to the alternative of leaving ten bootstrap credentials valid
#       indefinitely.
#       Assumptions: the provider accepts 0 through 365 here, observed by
#       submitting 366 and reading the rejection. The ceiling is held at 7
#       because this input describes a bootstrap credential, and a value in the
#       hundreds would quietly turn it into a permanent one.
variable "temporary_password_validity_days" {
  description = "Days a generated initial password remains valid before the seed user must be reset. Bounds the usefulness of a value that necessarily exists in Terraform state as well as in Secrets Manager."
  type        = number
  default     = 1

  validation {
    condition     = var.temporary_password_validity_days >= 1 && var.temporary_password_validity_days <= 7
    error_message = "temporary_password_validity_days must be between 1 and 7. The pinned provider accepts 0-365, but this input governs a bootstrap credential that also lives in Terraform state; a longer window would make it a durable credential rather than a temporary one."
  }
}

# -----------------------------------------------------------------------------
# Threat protection
# -----------------------------------------------------------------------------

# WHY : Trade-offs: OPTIONAL rather than ON. ON compels every user to enrol a
#       factor before they can complete a sign-in, which would block all ten
#       seed users on their first authentication -- the one sign-in that has to
#       succeed for a freshly provisioned environment to be usable at all.
#       OPTIONAL keeps the baseline's single-factor flow working while making a
#       second factor available to any user who enrols one, so the capability
#       the baseline entirely lacked is present without gating provisioning on
#       it.
#       Alternatives Considered: OFF, rejected because it removes the
#       capability rather than deferring the obligation; and ON, appropriate
#       once real users exist and reachable by setting this input in the
#       environment root without touching the module.
#       Assumptions: ON and OPTIONAL are not self-sufficient. Cognito requires
#       at least one MFA mechanism to be configured on the pool, which main.tf
#       supplies through the separate software_token_mfa_configuration block --
#       so these two settings are coupled, and changing this input to ON or
#       OPTIONAL without that block present fails at apply rather than at plan.
variable "mfa_configuration" {
  description = "Multi-factor posture for the user pool: OFF, ON (compulsory) or OPTIONAL (available but not required). Paired by main.tf with a software-token MFA mechanism, without which ON and OPTIONAL are rejected at apply. Production is structurally required to be ON; the default suits dev only."
  type        = string
  default     = "OPTIONAL"

  validation {
    condition     = contains(["OFF", "ON", "OPTIONAL"], var.mfa_configuration)
    error_message = "mfa_configuration must be one of OFF, ON or OPTIONAL -- the exact set the pinned provider accepts, observed by submitting an out-of-domain value and reading the rejection."
  }

  # WHY : Refactoring Rationale: the trade-off above argues OPTIONAL correctly for
  #       a freshly provisioned DEV pool and then let that argument stand for
  #       production too, which is where it stops holding. OPTIONAL does not
  #       merely defer the obligation, it never imposes it: a user who never
  #       enrols a factor authenticates on a password alone forever, so a leaked
  #       seed credential in production is a complete authentication. The
  #       first-sign-in problem the default solves is a DEV problem -- it is dev
  #       that is created and destroyed repeatedly by whoever is working on it --
  #       and it does not justify a production pool that cannot require a second
  #       factor. This validation makes the production posture a property of the
  #       module rather than a value a root has to remember to set, so omitting
  #       the input in prod fails to plan instead of silently producing
  #       single-factor authentication.
  #       Assumptions: the check reads var.environment, which this module already
  #       declares and closes at dev and prod, so the two inputs are evaluated
  #       together and the message can name the environment that triggered it.
  #       Trade-offs: ON compels every user to enrol a factor before completing a
  #       sign-in, INCLUDING any seed identity a production root chose to create.
  #       That is the accepted cost and it is small, because seed_users now
  #       defaults to an empty list: a production pool is expected to onboard its
  #       identities through the approved mechanism, where factor enrolment is
  #       part of onboarding rather than an obstacle to it.
  #       Alternatives Considered: leaving prod free to choose and relying on a
  #       policy scan to report OPTIONAL. Rejected because a scan reports after
  #       the fact and can be waived, whereas a variable validation cannot be
  #       satisfied by anything except the correct value.
  validation {
    condition     = var.environment != "prod" || var.mfa_configuration == "ON"
    error_message = "mfa_configuration must be \"ON\" when environment is \"prod\". OPTIONAL never requires a second factor, so a leaked password would be a complete authentication; the OPTIONAL default exists for dev, whose pool is created and destroyed repeatedly."
  }
}

# WHY : Assumptions: the attribute name was verified rather than assumed, and
#       this is worth recording because it is a moving target elsewhere. In the
#       pinned provider the setting still lives inside the user_pool_add_ons
#       block as advanced_security_mode, it carries no deprecation marker, it is
#       required whenever that block is present, and a search of every
#       aws_cognito_* resource in the provider finds no threat-protection
#       attribute under any other name. This input is therefore named after the
#       argument main.tf actually sets.
#       Trade-offs: AUDIT rather than ENFORCED as the default. ENFORCED blocks a
#       sign-in the service scores as high risk, and the ten seed users first
#       authenticate from whatever address the operator happens to run from --
#       an unrecognised device from an unrecognised location, which is precisely
#       the combination that scores highest. Defaulting to ENFORCED would make
#       a correctly provisioned environment look broken on its first sign-in.
#       AUDIT records the same signal without acting on it, and prod raises the
#       value from its environment root.
#       Alternatives Considered: OFF, rejected because it collects nothing at
#       all and so leaves no signal to raise the posture against later. The
#       choice also has a cost dimension, since threat protection is a paid
#       feature tier and OFF is the only mode outside it; that dimension is
#       argued in docs/adr/ADR-008 rather than restated here.
variable "advanced_security_mode" {
  description = "Cognito threat-protection posture: OFF, AUDIT (record risk signals only) or ENFORCED (act on them). Passed through to the advanced_security_mode argument of the user_pool_add_ons block, which is where the pinned provider exposes this setting. Production is structurally required to be ENFORCED; the default suits dev only."
  type        = string
  default     = "AUDIT"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.advanced_security_mode)
    error_message = "advanced_security_mode must be one of OFF, AUDIT or ENFORCED -- the exact set the pinned provider accepts for user_pool_add_ons.advanced_security_mode, observed by submitting an out-of-domain value and reading the rejection."
  }

  # WHY : Refactoring Rationale: the trade-off above ends with "prod raises the
  #       value from its environment root", which describes an intention rather
  #       than a control. AUDIT records a risk signal and applies no response, so
  #       a sign-in the service scores as high risk -- credential stuffing, an
  #       impossible-travel pattern, a password known to have been breached --
  #       succeeds exactly as a normal one does and is merely written down. In
  #       production that is detection without protection. The
  #       first-sign-in-from-an-unrecognised-address problem the AUDIT default
  #       solves is a DEV problem, and it does not justify a production pool that
  #       observes an attack and lets it through. This validation moves the
  #       production posture from an intention into a property of the module, so
  #       a root that omits the input fails to plan rather than deploying AUDIT.
  #       Assumptions: the check reads var.environment, which this module already
  #       declares and closes at dev and prod, so both inputs are evaluated
  #       together.
  #       Trade-offs: ENFORCED can block a legitimate sign-in that scores high,
  #       which is a real operational cost and is the reason the default is not
  #       ENFORCED everywhere. It is accepted in production because the response
  #       to a blocked legitimate sign-in is a recoverable support path, whereas
  #       the response to an admitted malicious one is an incident. The cost
  #       dimension -- threat protection is a paid feature tier, and OFF is the
  #       only mode outside it -- is argued in docs/adr/ADR-008 rather than
  #       restated here.
  #       Alternatives Considered: requiring ENFORCED in both environments.
  #       Rejected because dev is created and destroyed repeatedly from whatever
  #       address an operator happens to be at, which is precisely the pattern
  #       that scores highest, so a correctly provisioned dev environment would
  #       look broken on its very first sign-in.
  validation {
    condition     = var.environment != "prod" || var.advanced_security_mode == "ENFORCED"
    error_message = "advanced_security_mode must be \"ENFORCED\" when environment is \"prod\". AUDIT records a high-risk sign-in and admits it anyway, which is detection without protection; the AUDIT default exists for dev, whose operators sign in from unrecognised addresses by definition."
  }
}

# -----------------------------------------------------------------------------
# App client used by the auth service on the browser's behalf
#
# WHY : Assumptions: the distinction decides every input in this group, so it is
#       stated once at the top of it. The browser never speaks to this pool. It
#       posts the credential its sign-on screen collects to the auth service,
#       which authenticates against the pool with the flow named in
#       var.explicit_auth_flows and returns the resulting token. A public client
#       would be the right shape for a browser that redirected to a hosted
#       sign-in page and completed an authorization code exchange itself, and
#       that design was rejected in the note on var.domain_prefix because it
#       would strand the transcribed sign-on screen and its three verbatim
#       replies. Nothing in ui/.env.example configures this pool, and no client
#       secret may ever reach the browser bundle.
# -----------------------------------------------------------------------------

# WHY : Assumptions: an app client permits only the flows named here, so this
#       input is the allow-list that decides whether the sign-on mechanism works
#       at all. USER_PASSWORD_AUTH is what lets the auth service submit a user id
#       and a password in one call and receive tokens or a challenge; without it
#       the pool refuses the request and sign-on is impossible regardless of what
#       any other setting says. Refresh is deliberately absent from this list:
#       main.tf enables refresh-token rotation, and Cognito requires the auth
#       service to call GetTokensFromRefreshToken instead of initiating the
#       incompatible REFRESH_TOKEN_AUTH flow. That is now a checkable statement
#       rather than an assumption about code elsewhere: the call is made by
#       CognitoIdentityService.exchangeRefreshToken, reached from its renewTokens
#       method and served at POST /api/v1/auth/refresh, and it supplies the client
#       secret as a request member because that is how this API proves a
#       confidential client -- the initiated flows use a keyed digest instead.
# WHY : Refactoring Rationale: this exclusion previously rested on a premise that
#       was not yet true. The service implemented no renewal at all, so forbidding
#       the flow on the grounds that another API was used in its place described an
#       arrangement that did not exist, and a reader checking the claim would have
#       found no such call. The renewal operation the contract had always declared
#       is now implemented against that API, which makes the premise and the
#       exclusion agree. The alternative -- relaxing this to admit
#       ALLOW_REFRESH_TOKEN_AUTH -- was rejected because the provider rejects that
#       flow alongside rotation, so it would have traded a stale comment for a
#       combination that fails at apply.
# WHY : Trade-offs: the default names exactly one flow and nothing else. The
#       flows deliberately excluded are worth listing, because each is a
#       plausible addition. ADMIN_USER_PASSWORD_AUTH performs the same
#       authentication through the ADMIN API, which additionally requires the
#       caller's IAM identity to hold pool administration permissions -- a
#       heavier task-role grant for no functional gain here, since the client
#       secret already authenticates the caller. USER_SRP_AUTH avoids sending the
#       password to the pool at all by proving knowledge of it, which is the
#       right choice for an untrusted client and buys nothing for a server-side
#       caller already reaching the pool over TLS, while costing a multi-round
#       exchange. CUSTOM_AUTH would introduce trigger Lambdas this migration does
#       not define. ALLOW_REFRESH_TOKEN_AUTH is also excluded and cannot be added
#       while refresh-token rotation is enabled, because Cognito rejects that
#       combination. Any other flow can be added by a root that needs it; none is
#       enabled speculatively, because an enabled flow is an available
#       authentication path whether or not anything uses it.
# WHY : Assumptions: the ALLOW_ prefix is required by the provider, which rejects
#       the unprefixed legacy spellings on a client that names any prefixed flow.
#       The validation below enforces it so the mistake is caught at plan time
#       rather than mid-apply.
variable "explicit_auth_flows" {
  description = "Authentication flows the confidential app client may initiate, as ALLOW_-prefixed names. Defaults to USER_PASSWORD_AUTH for server-side sign-on. REFRESH_TOKEN_AUTH is forbidden because refresh-token rotation requires the auth service to use GetTokensFromRefreshToken instead."
  type        = list(string)
  default     = ["ALLOW_USER_PASSWORD_AUTH"]
  nullable    = false

  validation {
    condition     = length(var.explicit_auth_flows) > 0
    error_message = "explicit_auth_flows must name at least one flow; an app client with no permitted flow cannot authenticate anyone, which presents as a pool that rejects every correct credential."
  }

  validation {
    condition = alltrue([
      for flow in var.explicit_auth_flows : startswith(flow, "ALLOW_")
    ])
    error_message = "Every explicit_auth_flows entry must carry the ALLOW_ prefix -- for example \"ALLOW_USER_PASSWORD_AUTH\". The pinned provider rejects the legacy unprefixed spellings once any prefixed value is present, and the rejection arrives during apply rather than at plan."
  }

  validation {
    condition     = contains(var.explicit_auth_flows, "ALLOW_USER_PASSWORD_AUTH")
    error_message = "explicit_auth_flows must include \"ALLOW_USER_PASSWORD_AUTH\". It is the flow services/auth-service uses to authenticate the credential submitted by the sign-on screen transcribed from app/bms/COSGN00.bms; without it the pool refuses that call and no user can sign in."
  }

  validation {
    condition     = !contains(var.explicit_auth_flows, "ALLOW_REFRESH_TOKEN_AUTH")
    error_message = "explicit_auth_flows must not include \"ALLOW_REFRESH_TOKEN_AUTH\" while refresh-token rotation is enabled. The auth service renews sessions with GetTokensFromRefreshToken, which is the rotation-compatible API."
  }
}

# WHY : Assumptions: a resource server is what makes an API's own scope names
#       exist inside the pool at all. Without one, the only scope a token from
#       this pool can carry is the pool's own built-in
#       aws.cognito.signin.user.admin, so there is no vocabulary for a
#       machine-to-machine caller to request a narrower grant with, and nothing
#       for a future route-level or method-level authorization decision to be
#       expressed in. Declaring the identifier and the scopes here creates that
#       vocabulary once, at the pool, rather than leaving each consumer to invent
#       one.
# Trade-offs: this is the important one on this input. These custom scopes are NOT
#       what
#       the browser sign-on path presents. A token obtained through the pool's
#       authentication API carries aws.cognito.signin.user.admin and no custom
#       scope, because custom scopes are granted only through the OAuth flows
#       (authorization code, client credentials) that this design deliberately
#       does not use for interactive sign-on. Requiring a custom scope on the
#       edge routes would therefore reject every token a signed-in user holds,
#       which is why infra/modules/api-gateway-http defaults its route
#       authorization scope to the built-in one instead. What these scopes are
#       for is the client-credentials caller a later integration will need -- a
#       batch or partner client that holds no user identity -- and the point of
#       declaring them now is that the vocabulary is defined by the module that
#       owns the pool rather than improvised by whoever adds that client.
# WHY : Assumptions: the identifier is a bare name rather than a URL. Cognito
#       accepts either, and prefixes the identifier onto each scope to form the
#       value that appears in a token, so this default yields scope strings such
#       as `carddemo-api/read`. A URL-shaped identifier is the OAuth convention
#       and was rejected here because it invites the reading that the string is
#       an address something can be fetched from, which it is not.
variable "resource_server_identifier" {
  description = "Identifier of the resource server registered for the migrated API. Cognito prefixes it onto each scope name, so \"carddemo-api\" with a scope \"read\" yields the token scope \"carddemo-api/read\"."
  type        = string
  default     = "carddemo-api"
  nullable    = false

  validation {
    condition     = can(regex("^[A-Za-z0-9][A-Za-z0-9._:/-]*$", var.resource_server_identifier)) && !strcontains(var.resource_server_identifier, " ")
    error_message = "resource_server_identifier must be a non-blank name of letters, digits and the punctuation . _ : / - , beginning with a letter or digit. A space is refused because a token's scope claim is a SPACE-DELIMITED list, so an identifier containing one would split into two unmatchable scopes."
  }
}

variable "resource_server_scopes" {
  description = "Custom scopes registered under the resource server, each a name and a human-readable description. Intended for machine-to-machine callers using the client-credentials grant; interactive sign-on tokens carry the pool's built-in aws.cognito.signin.user.admin scope instead."
  type = list(object({
    name        = string
    description = string
  }))
  default = [
    { name = "read", description = "Read migrated CardDemo record data through the API." },
    { name = "write", description = "Create or modify migrated CardDemo record data through the API." },
  ]
  nullable = false

  # WHY : Trade-offs: two coarse scopes rather than one per bounded context. Eight
  #       context-shaped scopes would look more precise and would be misleading,
  #       because the authorization decision that actually matters in this system
  #       is the administrator-versus-user split, and that rides on the signed
  #       cognito:groups claim which every service re-derives for itself. A scope
  #       per context would imply the edge enforced a per-context boundary it does
  #       not, and it would then have to be kept in step with the service catalog
  #       by hand. Read and write are the two distinctions a non-interactive
  #       caller genuinely needs.
  #       Assumptions: an empty list is permitted, so a root that wants no custom
  #       scope vocabulary at all can pass one; main.tf then registers the
  #       resource server with no scope, which the provider accepts.
  validation {
    condition = alltrue([
      for scope in var.resource_server_scopes :
      can(regex("^[A-Za-z0-9][A-Za-z0-9._-]*$", scope.name))
    ])
    error_message = "Every resource_server_scopes name must be letters, digits and the punctuation . _ - , beginning with a letter or digit, and must contain no space or slash. A slash would collide with the separator Cognito inserts between the identifier and the name, and a space would split the resulting scope inside a token's space-delimited scope claim."
  }

  validation {
    condition = alltrue([
      for scope in var.resource_server_scopes : length(trimspace(scope.description)) > 0
    ])
    error_message = "Every resource_server_scopes entry must carry a non-blank description. The provider requires one, and it is the text an operator reads when deciding whether a client should be granted the scope."
  }

  validation {
    condition     = length(distinct([for scope in var.resource_server_scopes : scope.name])) == length(var.resource_server_scopes)
    error_message = "resource_server_scopes must not repeat a scope name; main.tf keys its per-scope configuration by the name, so a duplicate is a key collision reported as an internal error rather than as the data problem it is."
  }
}

# WHY : Assumptions: these are inputs rather than values this module derives,
#       because the address they name is the SPA's CloudFront domain and that is
#       produced by infra/modules/cloudfront-spa. This module calls no sibling
#       module and reads no other module's state, so the only way the domain can
#       reach it is through the calling root that instantiates both -- which is
#       also the only place the two modules' outputs and inputs are visible
#       together.
#       Assumptions: they default to empty rather than to a guessed address, and
#       the empty default is the EXPECTED configuration rather than a placeholder.
#       No redirect occurs in this design at all: the auth service authenticates
#       server-side through the flow named in var.explicit_auth_flows, so there is
#       no browser round trip to return from. An empty list produces a client with
#       no redirect target, which is coherent; a stand-in URL would instead be a
#       valid-looking redirect target pointing somewhere nobody controls. Both
#       inputs are retained rather than deleted so that a root adding a
#       hosted-UI-based client later has the surface to configure it, and so that
#       the validations below fix the transport posture in advance.
#       Trade-offs: the validation admits loopback over cleartext http alongside
#       https, because a developer running the SPA locally has no certificate.
#       Any other cleartext host is refused, since a redirect carrying a token
#       over plain http exposes it in transit.
#       Refactoring Rationale: the loopback exception is expressed as an ANCHORED
#       pattern over the whole URL rather than as a prefix test. An earlier
#       revision of both validations below asked only whether the value STARTS
#       WITH "http://localhost" or "http://127.0.0.1", and a prefix cannot tell a
#       host from the beginning of one: "http://localhost.attacker.example/cb"
#       and "http://127.0.0.1.attacker.example/cb" both start with those strings
#       and are ordinary internet hosts under someone else's control. Cognito
#       would then hold an approved redirect target that delivers an
#       authorization code, and with it the user's session, to that host over
#       cleartext -- and the configuration would read as a documented local
#       development allowance. The pattern below therefore requires the host to
#       be exactly localhost or 127.0.0.1, optionally followed by a numeric port,
#       and then either the end of the string or a path separator, which is the
#       only place a host name can legitimately end.
variable "callback_urls" {
  description = "Absolute URLs Cognito may redirect to after a successful sign-in. Supplied by the calling root from the CloudFront distribution that serves the SPA, since this module cannot read that sibling module's output itself."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for url in var.callback_urls :
      can(regex("^https://[^/?#@]+([/?#]|$)", url))
      || can(regex("^http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?([/?#]|$)", url))
    ])
    error_message = "Every callback_urls entry must be an absolute https URL, or an http URL whose host is EXACTLY localhost or 127.0.0.1, with an optional numeric port, for local development. A cleartext host that merely begins with localhost or 127.0.0.1 -- http://localhost.example.com, say -- is an ordinary internet host and is refused, because the redirect carries an authorization code."
  }
}

variable "logout_urls" {
  description = "Absolute URLs Cognito may redirect to after a sign-out. Supplied by the calling root from the CloudFront distribution that serves the SPA, on the same reasoning as callback_urls."
  type        = list(string)
  default     = []

  # WHY : Assumptions: the pattern is character-for-character the one on
  #       callback_urls, including its anchored loopback exception, and the
  #       reasoning recorded there applies unchanged. Stating it identically is
  #       deliberate: a sign-out redirect is a redirect Cognito has approved, so a
  #       spoofable host here is a spoofable host in the pool, and two validations
  #       that differ by a character are how one of them ends up weaker than the
  #       reader assumes.
  validation {
    condition = alltrue([
      for url in var.logout_urls :
      can(regex("^https://[^/?#@]+([/?#]|$)", url))
      || can(regex("^http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?([/?#]|$)", url))
    ])
    error_message = "Every logout_urls entry must be an absolute https URL, or an http URL whose host is EXACTLY localhost or 127.0.0.1 with an optional numeric port. The constraint matches callback_urls character for character so the two cannot drift into different transport postures."
  }
}

# WHY : Assumptions: the unit is in each variable's NAME because the provider
#       reads a bare number against a companion token_validity_units block, and
#       defaults that block to hours when it is absent. A value of 60 with no
#       units block is therefore interpreted as 60 hours and rejected for
#       exceeding the 24-hour ceiling -- verified by submitting exactly that.
#       Naming the unit here obliges main.tf to set token_validity_units to
#       minutes, minutes and days to match, and makes a mismatch visible at the
#       declaration instead of only in the provider's error text.
#       Trade-offs: short access and id token lifetimes bound the window in which
#       a stolen token is usable, at the cost of the SPA having to refresh. That
#       cost is paid by ui/src/api/client.ts once, silently, for every screen.
#       Alternatives Considered: long-lived access tokens to avoid implementing
#       refresh at all. Rejected because an access token cannot be revoked --
#       lengthening its life lengthens exactly the window the short lifetime
#       exists to close.
variable "access_token_validity_minutes" {
  description = "Access token lifetime in minutes. main.tf pairs this with a token_validity_units block set to minutes, without which the provider would read the number as hours."
  type        = number
  default     = 60

  validation {
    condition     = var.access_token_validity_minutes >= 5 && var.access_token_validity_minutes <= 1440
    error_message = "access_token_validity_minutes must be between 5 and 1440. The pinned provider requires this token's lifetime to fall between 5 minutes and 24 hours, so 1440 is the ceiling once the unit is minutes."
  }
}

variable "id_token_validity_minutes" {
  description = "Identity token lifetime in minutes, carrying the cognito:groups claim that common-lib's JwtRoleConverter turns into authorities. Paired by main.tf with a token_validity_units block set to minutes."
  type        = number
  default     = 60

  validation {
    condition     = var.id_token_validity_minutes >= 5 && var.id_token_validity_minutes <= 1440
    error_message = "id_token_validity_minutes must be between 5 and 1440. The pinned provider requires this token's lifetime to fall between 5 minutes and 24 hours, the same bound as the access token."
  }
}

# WHY : Trade-offs: this is deliberately the longest-lived credential of the
#       three, which is the opposite of the intuitive arrangement and so is
#       recorded here rather than left to be inferred from the numbers. It is
#       the only one of the three that can be revoked, because main.tf enables
#       token revocation on the client; the access and id tokens cannot be
#       recalled once issued and are therefore the ones held to minutes. Making
#       the durable credential the recallable one is what allows a compromised
#       session to be ended at all, and 30 days keeps the SPA from forcing a
#       fresh sign-on during ordinary use.
#       Alternatives Considered: matching the refresh token to the access
#       token's lifetime for symmetry. Rejected because it would make the
#       revocable credential expire as fast as the irrevocable ones, which
#       costs the user a sign-on without shortening the window that actually
#       matters -- the access token was already short.
variable "refresh_token_validity_days" {
  description = "Refresh token lifetime in days. Deliberately the longest-lived of the three tokens because it is the only revocable one. Paired by main.tf with a token_validity_units block set to days."
  type        = number
  default     = 30

  validation {
    condition     = var.refresh_token_validity_days >= 1 && var.refresh_token_validity_days <= 3650
    error_message = "refresh_token_validity_days must be between 1 and 3650. The pinned provider requires this token's lifetime to fall between 1 hour and 87600 hours, so once the unit is days the usable range is 1 to 3650."
  }
}

# WHY : Assumptions: incrementing this integer is the explicit operator trigger
#       for the custom app-client-secret rotation resource in main.tf. A date or
#       free-form string was rejected because it can change accidentally through
#       formatting; a monotonic integer makes every rotation a reviewable,
#       one-line plan change and never carries credential material.
variable "app_client_secret_rotation_revision" {
  description = "Monotonic, non-secret revision that triggers the Cognito app-client-secret rotation bridge. Incrementing it adds a new active client secret, updates the Secrets Manager value, and retains the previously current secret for a zero-downtime consumer rollout."
  type        = number
  default     = 1
  nullable    = false

  validation {
    condition = (
      var.app_client_secret_rotation_revision >= 1 &&
      floor(var.app_client_secret_rotation_revision) == var.app_client_secret_rotation_revision
    )
    error_message = "app_client_secret_rotation_revision must be an integer greater than or equal to 1. Increment it by one for each reviewed app-client-secret rotation."
  }
}

variable "seed_user_credential_revision" {
  description = "Monotonic operator-controlled revision for deliberate seed-user temporary-password regeneration. Ordinary applies keep it stable, so users are not reset."
  type        = number
  nullable    = false
  default     = 1

  validation {
    condition     = var.seed_user_credential_revision >= 1 && floor(var.seed_user_credential_revision) == var.seed_user_credential_revision
    error_message = "seed_user_credential_revision must be an integer greater than or equal to 1."
  }
}

# -----------------------------------------------------------------------------
# Optional hosted-UI domain
# -----------------------------------------------------------------------------

# WHY : Alternatives Considered: always provisioning the hosted UI, which is what
#       the authorization-code-with-PKCE flow would require. Rejected because
#       nothing authenticates through it -- AAP 0.4.1.9 specifies a user pool
#       with an app client and a token obtained by the application, and
#       ui/src/screens/signon renders the baseline's own sign-on screen from
#       app/bms/COSGN00.bms rather than delegating to a Cognito-rendered page.
#       Provisioning a sign-in surface nobody authenticates through would add a
#       second, unused entry point to the identity provider. It would also strand
#       the three verbatim baseline sign-on replies the migration is required to
#       preserve, because a hosted page emits its own wording.
# WHY : Assumptions: which authentication mechanism IS used, recorded here because
#       this variable's default is only coherent once that is stated. The auth
#       service calls the pool's authentication API directly with the
#       USER_PASSWORD_AUTH flow enabled by var.explicit_auth_flows below,
#       computing the SECRET_HASH from the app client's id, the submitted user id
#       and the app client's generated SECRET, and driving whatever challenge the
#       pool returns. The client is therefore CONFIDENTIAL and server-side only,
#       which is why var.callback_urls and var.logout_urls default to empty and
#       why no variable in ui/.env.example configures this pool at all. The
#       pool's OAuth TOKEN endpoint is not used and could not be: it implements
#       the authorization-code, client-credentials and refresh-token grants and
#       implements no password grant, so it has no request that exchanges a user
#       id and a password for a token.
#       Assumptions: opt-in is also the safer default for a second and unrelated
#       reason. A hosted-UI domain prefix is globally unique within a region, so
#       a default value would make two environments in one region collide on
#       apply -- and it would do so at the moment the second environment was
#       created, not when the default was chosen.
#       Assumptions: nullable is left at its default of true and the default is
#       null, so absence is expressible. The alternative convention, an empty
#       string standing for absence, would make "not configured" and
#       "configured to nothing" the same value.
variable "domain_prefix" {
  description = "Prefix for an optional Cognito-hosted sign-in domain. Null, the default, means no hosted-UI domain is created, which is the expected configuration: the auth service authenticates the submitted credential against the Cognito API server-side, so no browser redirect to a hosted page occurs."
  type        = string
  default     = null

  validation {
    condition = var.domain_prefix == null || (
      length(var.domain_prefix) <= 63 &&
      can(regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$", var.domain_prefix))
    )
    error_message = "domain_prefix must be null, or at most 63 characters of lowercase letters, digits and interior hyphens. 63 is the pinned provider's own upper bound for the domain argument, observed by submitting 64 characters and reading the rejection; leading and trailing hyphens are excluded because the value becomes a DNS label. Cognito additionally reserves prefixes containing aws, amazon or cognito, and rejects those service-side."
  }
}

# -----------------------------------------------------------------------------
# Environment parameterization. dev and prod differ here and nowhere else in
# this module: the topology they produce is identical.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this input is a STRING, not a bool, because the pinned
#       provider's attribute is a string accepting ACTIVE or INACTIVE -- read
#       from the provider schema and confirmed by submitting an out-of-domain
#       value. A bool would be the intuitive shape and it is the wrong one; it
#       is declared as a string so the value passes through unconverted rather
#       than being translated in main.tf, where the mapping would have to be
#       re-derived by whoever read it next.
#       Trade-offs: INACTIVE is the default so that `terraform destroy` completes
#       without a preceding update to release the pool -- the teardown criterion
#       this package is accepted against. prod sets ACTIVE from its environment
#       root, accepting that a deliberate two-step teardown is the right cost
#       for a pool holding real identities.
variable "deletion_protection" {
  description = "Whether the user pool is protected from deletion: ACTIVE or INACTIVE. A string rather than a bool because that is the type the pinned provider's deletion_protection attribute takes. Defaults to INACTIVE so dev tears down in one step; prod sets ACTIVE."
  type        = string
  default     = "INACTIVE"

  validation {
    condition     = contains(["ACTIVE", "INACTIVE"], var.deletion_protection)
    error_message = "deletion_protection must be exactly \"ACTIVE\" or \"INACTIVE\" -- the pinned provider's accepted values for this string attribute. Booleans true and false are not accepted by the provider and are not accepted here either."
  }
}

# WHY : Trade-offs: 0 by default, meaning a destroyed secret is removed
#       immediately with no recovery period. Any non-zero window leaves the
#       secret NAME reserved for the duration, so a destroy followed by a
#       re-apply collides on a name that no longer appears to exist -- which
#       breaks the destroy-then-reapply cycle that the teardown criterion and
#       ordinary iteration both depend on. prod sets a real window and accepts
#       that its secrets cannot be recreated under the same names until it
#       lapses, because there the recoverability is worth more than the ability
#       to re-apply at once.
#       Assumptions: the accepted domain is genuinely discontinuous, which is why
#       the validation is written as a disjunction rather than a range. The
#       provider takes 0, or any value from 7 to 30; submitting 3 is refused by
#       two separate provider validators at once. A naive range of 0 to 30 would
#       accept 1 through 6 here and then fail at apply.
variable "secret_recovery_window_in_days" {
  description = "Recovery window applied to the Secrets Manager entries holding seed-user credentials. Either 0 for immediate deletion or 7 to 30 days; the domain excludes 1 to 6. Defaults to 0 so that destroy and re-apply do not collide on a reserved secret name."
  type        = number
  default     = 0

  validation {
    condition     = var.secret_recovery_window_in_days == 0 || (var.secret_recovery_window_in_days >= 7 && var.secret_recovery_window_in_days <= 30)
    error_message = "secret_recovery_window_in_days must be 0, or between 7 and 30. The pinned provider accepts exactly that discontinuous domain and refuses values 1 through 6; 0 means immediate deletion with no recovery period."
  }
}

# -----------------------------------------------------------------------------
# Seed users
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: THE OBJECT TYPE HAS NO CREDENTIAL MEMBER, AND
#       THE DEFAULT CARRIES NO CREDENTIAL VALUE. This restates the header's
#       governing decision at the one declaration where its absence would
#       otherwise read as an oversight. The baseline's own seed data is the
#       reason: at app/jcl/DUSRSECJ.jcl L35-L44 the ten records below appear in
#       the clear, and bytes 49-56 of every one of them hold the same
#       eight-character literal -- one shared password, identical across all ten
#       users, committed to source control. That literal is deliberately not
#       transcribed anywhere in this file, not even as an illustration. main.tf
#       generates a distinct password per user inside the apply-time bootstrap
#       and writes each to Secrets Manager under the customer-managed key from
#       secrets_kms_key_arn, so the credential exists only where it can be
#       rotated and audited.
#       Alternatives Considered: adding a password or temporary_password member
#       so callers could seed known credentials for testing. Rejected: the value
#       would have to be supplied from a tracked tfvars file or a CI variable,
#       which is the defect above with a new name.
#       Assumptions: exactly four members, because those are the four fields of
#       app/cpy/CSUSR01Y.cpy that survive the migration. SEC-USR-ID PIC X(08) at
#       L18 becomes user_id, SEC-USR-FNAME PIC X(20) at L19 becomes given_name,
#       SEC-USR-LNAME PIC X(20) at L20 becomes family_name, and SEC-USR-TYPE
#       PIC X(01) at L22 becomes user_type. The record's other two fields are
#       absent on purpose and for different reasons: SEC-USR-PWD at L21 is the
#       credential discussed above, and SEC-USR-FILLER PIC X(23) at L23 is
#       padding to the 80-byte record length rather than data, dropped under the
#       rule that FILLER is dropped and the drop recorded.
#       Assumptions: the names are the record values with their X(20) trailing
#       blanks trimmed, because in a fixed-width record those blanks are padding
#       and not part of the value.
#       Trade-offs: a root that does supply identities writes each on one line
#       rather than spread over six, which is wider than this file's comment
#       margin. Accepted deliberately -- one row per record is the shape the
#       source data has, so entries can be read straight against
#       app/jcl/DUSRSECJ.jcl to confirm nothing was transposed. The baseline's
#       roster is five type 'A' and five type 'U', which is what makes both
#       groups populated when a root chooses to seed it.
#       Assumptions: an empty list is permitted rather than rejected, so no
#       minimum-length validation appears below, and it is now also the DEFAULT
#       for the reason recorded immediately above the declaration. A root that
#       manages its identities out of band -- or a prod root that declines to
#       create demo identities at all -- gets the pool, the client and both
#       groups by saying nothing. The alternative, requiring at least one entry,
#       would force every environment to carry demo users in order to use the
#       module.

# WHY : Refactoring Rationale: this input previously DEFAULTED to the baseline's
#       ten identities, five of them administrators, and the default was the
#       defect. A default is what a caller gets by saying nothing, so a
#       production root that simply omitted this argument -- the single most
#       likely omission in a reusable module, because the input reads as
#       optional -- would silently create five administrators whose user ids are
#       published in this repository at app/jcl/DUSRSECJ.jcl L35-L44 and are
#       therefore known to anyone who can read the tree. Their passwords are
#       generated and stored in the secret store rather than committed, so the
#       exposure is not a credential, and that is exactly what makes it easy to
#       under-rate: five known administrator usernames in a production pool are
#       five confirmed targets for a credential-stuffing or reset-abuse attempt,
#       and the reset path is a support process rather than a cryptographic one.
#       An empty default inverts that: seeding demo identities becomes something
#       a root must ASK for, in a file a reviewer reads, and omitting the
#       argument produces a pool with the client and both groups and no users.
#       Trade-offs: the ten identities are no longer available for free, so the
#       dev root now states them explicitly if it wants them. That is one block
#       in one tfvars file, and it is where a reader looking for "which
#       identities exist in this environment" would expect to find the answer
#       anyway. The alternative that preserved convenience -- defaulting to the
#       ten only when environment is dev -- was rejected because a variable
#       default cannot be conditional in Terraform, so it would have to be
#       expressed as a default plus a validation forbidding it in prod, which is
#       a strictly more complicated way to reach the same posture and leaves the
#       demo identities as the thing that happens by accident.
#       Assumptions: the ten identities themselves are not lost. They remain
#       readable in the reference baseline at the cited lines, and the ETL loads
#       the same records into auth.users from the USRSEC dataset, so the roster
#       still has a single source; what changes is that creating them in a
#       managed identity pool is now deliberate.
variable "seed_users" {
  description = "Identities created in the pool and assigned to the carddemo-admin or carddemo-user group by user_type. Defaults to an EMPTY list, so a root that says nothing gets the pool, the app client and both groups with no users; a root wanting the baseline's ten demo identities (app/jcl/DUSRSECJ.jcl L35-L44) lists them explicitly. Carries no password: main.tf generates each initial credential during apply and stores it in Secrets Manager."
  type = list(object({
    user_id     = string
    given_name  = string
    family_name = string
    user_type   = string
  }))
  default = []

  validation {
    condition = alltrue([
      for user in var.seed_users : length(user.user_id) == 8
    ])
    error_message = "Every seed_users entry must have a user_id of exactly 8 characters -- the width of SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy L18. The id is both the Cognito username and the key of auth.users, which services/auth-service declares from the same field, so a different width would silently break the join to that table and to the rows the ETL reader loads from USRSEC."
  }

  validation {
    condition = alltrue([
      for user in var.seed_users : contains(["A", "U"], user.user_type)
    ])
    error_message = "Every seed_users entry must have a user_type of exactly \"A\" or \"U\". The domain is closed at two values by the condition names on SEC-USR-TYPE: 88 CDEMO-USRTYP-ADMIN VALUE 'A' and 88 CDEMO-USRTYP-USER VALUE 'U' at app/cpy/COCOM01Y.cpy L27-L28. main.tf therefore creates exactly two groups, carddemo-admin and carddemo-user, with no third group and no fallback -- so a third value would leave that user in no group at all rather than raising an error at apply."
  }

  validation {
    condition = alltrue([
      for user in var.seed_users :
      length(user.given_name) >= 1 && length(user.given_name) <= 20 &&
      length(user.family_name) >= 1 && length(user.family_name) <= 20
    ])
    error_message = "Every seed_users entry must have a non-empty given_name and family_name of at most 20 characters each -- the widths of SEC-USR-FNAME and SEC-USR-LNAME PIC X(20) at app/cpy/CSUSR01Y.cpy L19-L20. The copybook width is the contract these names are carried under, so a longer value could not be written back into the record it came from."
  }

  validation {
    condition     = length(distinct([for user in var.seed_users : user.user_id])) == length(var.seed_users)
    error_message = "seed_users must not repeat a user_id. main.tf keys its per-user resources by this value, so a duplicate is a key collision that Terraform reports as a confusing internal error about a repeated key rather than as the data problem it is."
  }
}

# -----------------------------------------------------------------------------
# Tags
# -----------------------------------------------------------------------------

# WHY : Assumptions: additive, never a substitute. The calling root's provider
#       already applies default_tags to every resource in the graph, so this
#       input exists for tags that are specific to the identity provider rather
#       than common to the environment, and main.tf merges it over those
#       defaults instead of replacing them. Declaring a default_tags input here
#       instead would let a module argument contradict provider configuration
#       the root owns.
#       Assumptions: "taggable" is narrower than it looks, and the limit is the
#       provider's rather than this module's choice. Of the resources main.tf
#       creates, only the user pool and the seed-user secrets expose a tags
#       argument; the app client, both groups, each user, each group membership
#       and the optional domain expose none. Tags therefore reach two of those
#       resource types and cannot be made to reach the rest, which is worth
#       knowing before anyone relies on tag-based cost allocation covering the
#       whole module.
variable "tags" {
  description = "Additional tags merged over the calling root's provider default_tags on the resources in this module that accept tags, which are the user pool and the seed-user Secrets Manager entries. The remaining Cognito resources expose no tags argument in the pinned provider."
  type        = map(string)
  default     = {}
}
