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
#     generates each initial password with random_password during apply and
#     hands it straight to Secrets Manager, so no credential is ever authored
#     into this tree.
#     Alternatives Considered: a seed-user password input, or a password member
#     on the seed_users object type. Both rejected, because such an input has
#     to be fed either from a terraform.tfvars in infra/envs/dev or
#     infra/envs/prod -- both tracked in version control -- or from a CI
#     variable, and either route reintroduces the exact defect this module
#     exists to remove. An absent declaration cannot be read, so the decision
#     is recorded here and again at seed_users rather than left to inference.
#   - Assumption: this is a child module, invoked as
#     source = "../../modules/cognito" by infra/envs/dev and infra/envs/prod,
#     and it inherits their provider. That is why no `region` and no
#     `default_tags` variable appears: both are provider configuration the
#     calling root already owns, and restating them here would let a module
#     input silently disagree with the provider actually in effect. The `tags`
#     variable at the end is additive to those default tags, never a
#     replacement for them.
#   - Assumption: every attribute name, accepted-value set and numeric bound
#     asserted in the comments below was read from the pinned provider
#     (hashicorp/aws ~> 6.56, resolved 6.57.1) by dumping its schema and then
#     submitting deliberately invalid values to see what it refuses. None was
#     inferred from documentation prose, because several are counter-intuitive:
#     deletion_protection is a string rather than a bool, a Secrets Manager
#     recovery window of 3 is refused outright, and a token validity of 60 with
#     no unit block is read as 60 hours and rejected.
#   - Trade-off: several defaults here are deliberately stronger than the
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

# WHAT: the short token main.tf composes every resource name from -- the user
#       pool, the two group names, and each seed user's Secrets Manager entry.
# WHY : Assumption: the bound is not cosmetic. main.tf builds names by
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
  description = "Lowercase token prefixed to every resource name this module creates (user pool, carddemo-admin and carddemo-user groups, and the per-seed-user Secrets Manager entries). Combined with var.environment to keep dev and prod names distinct within one account."
  type        = string
  default     = "carddemo"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,19}$", var.name_prefix))
    error_message = "name_prefix must be 2-20 characters, start with a lowercase letter, and contain only lowercase letters, digits and hyphens. The charset is the intersection of what Secrets Manager names, Cognito names and a DNS-style hosted-UI domain label all accept; the 20-character ceiling leaves room for the environment and an eight-character user id from SEC-USR-ID (app/cpy/CSUSR01Y.cpy L18) in every composed name."
  }
}

# WHAT: which of the two parameterized environments this instance belongs to.
# WHY : Assumption: deliberately has no default, unlike name_prefix. The value
#       selects the sizing and retention posture below, and a default would let
#       a root that forgot to set it apply the other environment's posture
#       under the right name -- a prod pool tagged prod but carrying dev's
#       deletion and recovery settings. Requiring it makes that mistake a plan
#       error instead.
#       Assumption: the domain is closed at two values because AAP 0.2.1.1
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

# WHAT: the customer-managed KMS key that encrypts each seed user's generated
#       initial password in Secrets Manager. Lands on
#       aws_secretsmanager_secret.kms_key_id.
# WHY : Assumption: the key is created by infra/modules/kms and passed in by
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

# WHAT: the shortest password the pool will accept. Lands on
#       aws_cognito_user_pool.password_policy.minimum_length.
# WHY : Refactoring Rationale: the baseline field is SEC-USR-PWD PIC X(08)
#       (app/cpy/CSUSR01Y.cpy L21) -- exactly eight characters, compared in the
#       clear at app/cbl/COSGN00C.cbl L223. The default here is deliberately
#       longer than that, and the validation floor deliberately refuses to let a
#       caller configure the baseline's own length back in. Porting 8 unchanged
#       would preserve the number while discarding the reason it was ever
#       adequate, which was nothing.
#       Trade-off: a longer minimum normally trades usability for strength, but
#       not here -- main.tf generates these passwords with random_password, and
#       the only human contact with one is a single change at first sign-in. The
#       usual objection therefore does not apply, which is why the default sits
#       above the common enterprise floor rather than at it.
#       Assumption: the ceiling of 99 is the pinned provider's own upper bound,
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

# WHAT: the four character-class requirements, each landing on the matching
#       aws_cognito_user_pool.password_policy.require_* argument.
# WHY : Assumption: one comment covers all four because they are one decision,
#       not four -- the baseline enforced no complexity rule whatsoever, so
#       there is no per-class baseline behaviour to preserve or diverge from
#       individually, and repeating the same justification four times would say
#       less rather than more.
#       Trade-off: requiring every class narrows the space of acceptable
#       passwords, which is a genuine cost when a human chooses one. Accepted
#       for the same reason as the length above: these values are generated, so
#       the constraint is absorbed by random_password rather than by a person.
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

# WHAT: how long a generated initial password stays usable before the seed user
#       must be reset. Lands on
#       aws_cognito_user_pool.password_policy.temporary_password_validity_days.
# WHY : Trade-off: each seed user's initial password is generated during apply,
#       which means it exists in Terraform state as well as in Secrets Manager.
#       A short window bounds how long that copy is worth anything, and it is
#       the only control this module has over a value it cannot avoid producing.
#       The cost is real and is named rather than glossed: apply on one day and
#       first sign-in some days later, and the credential has lapsed. The remedy
#       is an operator-initiated reset through the Cognito admin API, which is
#       preferable to the alternative of leaving ten bootstrap credentials valid
#       indefinitely.
#       Assumption: the provider accepts 0 through 365 here, observed by
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

# WHAT: whether a second authentication factor is off, available, or compulsory.
#       Lands on aws_cognito_user_pool.mfa_configuration.
# WHY : Trade-off: OPTIONAL rather than ON. ON compels every user to enrol a
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
#       Assumption: ON and OPTIONAL are not self-sufficient. Cognito requires
#       at least one MFA mechanism to be configured on the pool, which main.tf
#       supplies through the separate software_token_mfa_configuration block --
#       so these two settings are coupled, and changing this input to ON or
#       OPTIONAL without that block present fails at apply rather than at plan.
variable "mfa_configuration" {
  description = "Multi-factor posture for the user pool: OFF, ON (compulsory) or OPTIONAL (available but not required). Paired by main.tf with a software-token MFA mechanism, without which ON and OPTIONAL are rejected at apply."
  type        = string
  default     = "OPTIONAL"

  validation {
    condition     = contains(["OFF", "ON", "OPTIONAL"], var.mfa_configuration)
    error_message = "mfa_configuration must be one of OFF, ON or OPTIONAL -- the exact set the pinned provider accepts, observed by submitting an out-of-domain value and reading the rejection."
  }
}

# WHAT: the threat-protection posture. Lands on
#       aws_cognito_user_pool.user_pool_add_ons.advanced_security_mode.
# WHY : Assumption: the attribute name was verified rather than assumed, and
#       this is worth recording because it is a moving target elsewhere. In the
#       pinned provider the setting still lives inside the user_pool_add_ons
#       block as advanced_security_mode, it carries no deprecation marker, it is
#       required whenever that block is present, and a search of every
#       aws_cognito_* resource in the provider finds no threat-protection
#       attribute under any other name. This input is therefore named after the
#       argument main.tf actually sets.
#       Trade-off: AUDIT rather than ENFORCED as the default. ENFORCED blocks a
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
  description = "Cognito threat-protection posture: OFF, AUDIT (record risk signals only) or ENFORCED (act on them). Passed through to the advanced_security_mode argument of the user_pool_add_ons block, which is where the pinned provider exposes this setting."
  type        = string
  default     = "AUDIT"

  validation {
    condition     = contains(["OFF", "AUDIT", "ENFORCED"], var.advanced_security_mode)
    error_message = "advanced_security_mode must be one of OFF, AUDIT or ENFORCED -- the exact set the pinned provider accepts for user_pool_add_ons.advanced_security_mode, observed by submitting an out-of-domain value and reading the rejection."
  }
}

# -----------------------------------------------------------------------------
# App client used by the browser single-page application
# -----------------------------------------------------------------------------

# WHAT: the redirect targets Cognito will return a user to after sign-in and
#       after sign-out. Land on aws_cognito_user_pool_client.callback_urls and
#       .logout_urls.
# WHY : Assumption: these are inputs rather than values this module derives,
#       because the address they name is the SPA's CloudFront domain and that is
#       produced by infra/modules/cloudfront-spa. This module calls no sibling
#       module and reads no other module's state, so the only way the domain can
#       reach it is through the calling root that instantiates both -- which is
#       also the only place the two modules' outputs and inputs are visible
#       together.
#       Assumption: they default to empty rather than to a guessed address. An
#       empty list produces a pool whose client has no redirect target
#       configured, which is a coherent state for the API-direct authentication
#       flow described below; a stand-in URL would instead be a valid-looking
#       redirect target pointing somewhere nobody controls.
#       Trade-off: the validation admits loopback over cleartext http alongside
#       https, because a developer running the SPA locally has no certificate.
#       Any other cleartext host is refused, since a redirect carrying a token
#       over plain http exposes it in transit.
variable "callback_urls" {
  description = "Absolute URLs Cognito may redirect to after a successful sign-in. Supplied by the calling root from the CloudFront distribution that serves the SPA, since this module cannot read that sibling module's output itself."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for url in var.callback_urls :
      startswith(url, "https://") || startswith(url, "http://localhost") || startswith(url, "http://127.0.0.1")
    ])
    error_message = "Every callback_urls entry must be an absolute https URL, or an http URL on localhost or 127.0.0.1 for local development. Cleartext http to any other host is refused because the redirect carries an identity token."
  }
}

variable "logout_urls" {
  description = "Absolute URLs Cognito may redirect to after a sign-out. Supplied by the calling root from the CloudFront distribution that serves the SPA, on the same reasoning as callback_urls."
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for url in var.logout_urls :
      startswith(url, "https://") || startswith(url, "http://localhost") || startswith(url, "http://127.0.0.1")
    ])
    error_message = "Every logout_urls entry must be an absolute https URL, or an http URL on localhost or 127.0.0.1 for local development. The constraint matches callback_urls so the two cannot drift into different transport postures."
  }
}

# WHAT: how long an access token and an id token stay valid, in minutes, and how
#       long a refresh token stays valid, in days. Land on the matching
#       aws_cognito_user_pool_client validity arguments.
# WHY : Assumption: the unit is in each variable's NAME because the provider
#       reads a bare number against a companion token_validity_units block, and
#       defaults that block to hours when it is absent. A value of 60 with no
#       units block is therefore interpreted as 60 hours and rejected for
#       exceeding the 24-hour ceiling -- verified by submitting exactly that.
#       Naming the unit here obliges main.tf to set token_validity_units to
#       minutes, minutes and days to match, and makes a mismatch visible at the
#       declaration instead of only in the provider's error text.
#       Trade-off: short access and id token lifetimes bound the window in which
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

# WHAT: how long a refresh token stays valid, in days -- the one token of the
#       three measured in days rather than minutes.
# WHY : Trade-off: this is deliberately the longest-lived credential of the
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

# WHAT: whether the identity provider distinguishes "no such user" from "wrong
#       password" in what it returns. Lands on
#       aws_cognito_user_pool_client.prevent_user_existence_errors.
# WHY : Trade-off: this is the one place where a security correction and the
#       migration's verbatim-message requirement genuinely pull against each
#       other, so both halves are stated. The baseline answers the two failure
#       modes differently on purpose: app/cbl/COSGN00C.cbl L242-L243 returns
#       'Wrong Password. Try again ...' when the keyed read succeeded but the
#       comparison failed, and L249 returns 'User not found. Try again ...' when
#       the read came back RESP 13. The difference between those two replies
#       tells an unauthenticated caller which user ids exist, which is user
#       enumeration.
#       ENABLED makes the identity provider answer both cases the same way. The
#       message TEXT is still preserved verbatim where the migration requires it
#       -- in services/auth-service and in ui/src/messages/messages.ts, which is
#       where user-visible strings live -- but the provider no longer reveals
#       which of the two applies. The resulting loss of discrimination between
#       the two failure modes is a behavioural divergence, and it belongs in the
#       register at docs/architecture/cobol-to-service-traceability.md rather
#       than being passed off as parity.
#       Alternatives Considered: LEGACY, which reproduces the baseline's
#       distinguishable responses exactly. Rejected as a default because it
#       ports the defect rather than the behaviour, but left reachable as a
#       value so that the posture is a decision the environment root states
#       explicitly rather than one buried in this module.
variable "prevent_user_existence_errors" {
  description = "Whether Cognito returns a uniform error for both an unknown user and a bad password (ENABLED) or distinguishes them as the baseline did (LEGACY). ENABLED closes the user-enumeration channel that app/cbl/COSGN00C.cbl L242-L249 opens."
  type        = string
  default     = "ENABLED"

  validation {
    condition     = contains(["ENABLED", "LEGACY"], var.prevent_user_existence_errors)
    error_message = "prevent_user_existence_errors must be either ENABLED or LEGACY -- the exact set the pinned provider accepts, observed by submitting an out-of-domain value and reading the rejection."
  }
}

# -----------------------------------------------------------------------------
# Optional hosted-UI domain
# -----------------------------------------------------------------------------

# WHAT: the prefix of a Cognito-hosted sign-in domain. When null, main.tf
#       creates no aws_cognito_user_pool_domain at all.
# WHY : Alternatives Considered: always provisioning the hosted UI. Rejected
#       because the SPA does not use it -- AAP 0.4.1.9 specifies a user pool
#       with an app client and a token obtained by the application, and
#       ui/src/screens/signon renders the baseline's own sign-on screen from
#       app/bms/COSGN00.bms rather than delegating to a Cognito-rendered page.
#       Provisioning a sign-in surface nobody authenticates through would add a
#       second, unused entry point to the identity provider.
#       Assumption: opt-in is also the safer default for a second and unrelated
#       reason. A hosted-UI domain prefix is globally unique within a region, so
#       a default value would make two environments in one region collide on
#       apply -- and it would do so at the moment the second environment was
#       created, not when the default was chosen.
#       Assumption: nullable is left at its default of true and the default is
#       null, so absence is expressible. The alternative convention, an empty
#       string standing for absence, would make "not configured" and
#       "configured to nothing" the same value.
variable "domain_prefix" {
  description = "Prefix for an optional Cognito-hosted sign-in domain. Null, the default, means no hosted-UI domain is created, which is the expected configuration because the SPA authenticates against the Cognito API directly."
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

# WHAT: whether the user pool refuses to be deleted. Lands on
#       aws_cognito_user_pool.deletion_protection.
# WHY : Assumption: this input is a STRING, not a bool, because the pinned
#       provider's attribute is a string accepting ACTIVE or INACTIVE -- read
#       from the provider schema and confirmed by submitting an out-of-domain
#       value. A bool would be the intuitive shape and it is the wrong one; it
#       is declared as a string so the value passes through unconverted rather
#       than being translated in main.tf, where the mapping would have to be
#       re-derived by whoever read it next.
#       Trade-off: INACTIVE is the default so that `terraform destroy` completes
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

# WHAT: how long a deleted seed-user secret stays recoverable. Lands on
#       aws_secretsmanager_secret.recovery_window_in_days.
# WHY : Trade-off: 0 by default, meaning a destroyed secret is removed
#       immediately with no recovery period. Any non-zero window leaves the
#       secret NAME reserved for the duration, so a destroy followed by a
#       re-apply collides on a name that no longer appears to exist -- which
#       breaks the destroy-then-reapply cycle that the teardown criterion and
#       ordinary iteration both depend on. prod sets a real window and accepts
#       that its secrets cannot be recreated under the same names until it
#       lapses, because there the recoverability is worth more than the ability
#       to re-apply at once.
#       Assumption: the accepted domain is genuinely discontinuous, which is why
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

# WHAT: the identities main.tf creates in the pool and places into the
#       carddemo-admin or carddemo-user group according to user_type.
# WHY : Refactoring Rationale: THE OBJECT TYPE HAS NO CREDENTIAL MEMBER, AND
#       THE DEFAULT CARRIES NO CREDENTIAL VALUE. This restates the header's
#       governing decision at the one declaration where its absence would
#       otherwise read as an oversight. The baseline's own seed data is the
#       reason: at app/jcl/DUSRSECJ.jcl L35-L44 the ten records below appear in
#       the clear, and bytes 49-56 of every one of them hold the same
#       eight-character literal -- one shared password, identical across all ten
#       users, committed to source control. That literal is deliberately not
#       transcribed anywhere in this file, not even as an illustration. main.tf
#       generates a distinct password per user with random_password during apply
#       and writes each to Secrets Manager under the customer-managed key from
#       secrets_kms_key_arn, so the credential exists only where it can be
#       rotated and audited.
#       Alternatives Considered: adding a password or temporary_password member
#       so callers could seed known credentials for testing. Rejected: the value
#       would have to be supplied from a tracked tfvars file or a CI variable,
#       which is the defect above with a new name.
#       Assumption: exactly four members, because those are the four fields of
#       app/cpy/CSUSR01Y.cpy that survive the migration. SEC-USR-ID PIC X(08) at
#       L18 becomes user_id, SEC-USR-FNAME PIC X(20) at L19 becomes given_name,
#       SEC-USR-LNAME PIC X(20) at L20 becomes family_name, and SEC-USR-TYPE
#       PIC X(01) at L22 becomes user_type. The record's other two fields are
#       absent on purpose and for different reasons: SEC-USR-PWD at L21 is the
#       credential discussed above, and SEC-USR-FILLER PIC X(23) at L23 is
#       padding to the 80-byte record length rather than data, dropped under the
#       rule that FILLER is dropped and the drop recorded.
#       Assumption: the names are the record values with their X(20) trailing
#       blanks trimmed, because in a fixed-width record those blanks are padding
#       and not part of the value.
#       Trade-off: each identity is written on one line rather than spread over
#       six, which is wider than this file's comment margin. Accepted
#       deliberately -- one row per record is the shape the source data has, so
#       the ten entries can be read straight against app/jcl/DUSRSECJ.jcl to
#       confirm nothing was transposed. Five type 'A' and five type 'U', which
#       is what makes both groups populated in a freshly provisioned pool.
#       Assumption: an empty list is permitted rather than rejected, so no
#       minimum-length validation appears below. A root that manages its
#       identities out of band -- or a prod root that declines to create demo
#       identities at all -- must be able to pass an empty list and still get
#       the pool, the client and both groups. The alternative, requiring at
#       least one entry, would force every environment to carry demo users in
#       order to use the module.
variable "seed_users" {
  description = "Identities created in the pool and assigned to the carddemo-admin or carddemo-user group by user_type. Defaults to the ten identities the baseline seeds at app/jcl/DUSRSECJ.jcl L35-L44. Carries no password: main.tf generates each initial credential during apply and stores it in Secrets Manager. An empty list is valid and creates the pool, client and groups with no users."
  type = list(object({
    user_id     = string
    given_name  = string
    family_name = string
    user_type   = string
  }))
  default = [
    { user_id = "ADMIN001", given_name = "MARGARET", family_name = "GOLD", user_type = "A" },
    { user_id = "ADMIN002", given_name = "RUSSELL", family_name = "RUSSELL", user_type = "A" },
    { user_id = "ADMIN003", given_name = "RAYMOND", family_name = "WHITMORE", user_type = "A" },
    { user_id = "ADMIN004", given_name = "EMMANUEL", family_name = "CASGRAIN", user_type = "A" },
    { user_id = "ADMIN005", given_name = "GRANVILLE", family_name = "LACHAPELLE", user_type = "A" },
    { user_id = "USER0001", given_name = "LAWRENCE", family_name = "THOMAS", user_type = "U" },
    { user_id = "USER0002", given_name = "AJITH", family_name = "KUMAR", user_type = "U" },
    { user_id = "USER0003", given_name = "LAURITZ", family_name = "ALME", user_type = "U" },
    { user_id = "USER0004", given_name = "AVERARDO", family_name = "MAZZI", user_type = "U" },
    { user_id = "USER0005", given_name = "LEE", family_name = "TING", user_type = "U" },
  ]

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

# WHAT: extra tags merged onto the taggable resources this module creates.
# WHY : Assumption: additive, never a substitute. The calling root's provider
#       already applies default_tags to every resource in the graph, so this
#       input exists for tags that are specific to the identity provider rather
#       than common to the environment, and main.tf merges it over those
#       defaults instead of replacing them. Declaring a default_tags input here
#       instead would let a module argument contradict provider configuration
#       the root owns.
#       Assumption: "taggable" is narrower than it looks, and the limit is the
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
