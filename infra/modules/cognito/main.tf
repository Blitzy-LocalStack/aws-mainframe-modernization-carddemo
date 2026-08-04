# =============================================================================
# infra/modules/cognito/main.tf
# -----------------------------------------------------------------------------
# Purpose:
#   Provisions the migrated CardDemo identity provider. It replaces the
#   mainframe sign-on path in its entirety: the USRSEC VSAM file defined at
#   app/csd/CARDDEMO.CSD L88-L99 and read by the READ-USER-SEC-FILE paragraph of
#   app/cbl/COSGN00C.cbl (L209-L257) become a Cognito user pool; the two user
#   types carried by SEC-USR-TYPE become two pool groups; and the ten seed
#   identities loaded by app/jcl/DUSRSECJ.jcl become optional pool users whose
#   initial credentials are generated during apply rather than authored
#   anywhere. Concretely this file creates the user pool and its password,
#   multi-factor and threat-protection policy, one confidential app client, the
#   Secrets Manager entry that carries that client's generated secret, the API
#   resource server and its scope vocabulary, the carddemo-admin and
#   carddemo-user groups, an optional hosted-UI domain, and per seed user a
#   generated password, a Secrets Manager entry holding it, the pool user
#   itself, and that user's group membership.
#
#   THIS FILE IS WHERE THE MIGRATION DECLINES FUNCTIONAL PARITY. Every other
#   part of this migration preserves observable behaviour; identity is the one
#   documented exception, and the reasoning is recorded under WHY below and
#   again beside the password policy it produces.
#
# Parameters:
#   All twenty-five inputs declared in the sibling variables.tf, every one of
#   which is consumed here -- the module is linted with
#   terraform_unused_declarations enabled, so an unconsumed input is a build
#   failure rather than dead weight. Each input's USE is explained at the
#   argument it lands on rather than restated here, so that a reader following
#   an argument finds the reasoning beside it:
#     - naming ............ name_prefix, environment
#     - encryption ........ secrets_kms_key_arn
#     - password policy ... password_minimum_length, password_require_lowercase,
#                           password_require_uppercase, password_require_numbers,
#                           password_require_symbols,
#                           temporary_password_validity_days
#     - threat posture .... mfa_configuration, advanced_security_mode
#     - app client ........ explicit_auth_flows, callback_urls, logout_urls,
#                           access_token_validity_minutes,
#                           id_token_validity_minutes,
#                           refresh_token_validity_days,
#                           prevent_user_existence_errors
#     - API vocabulary .... resource_server_identifier, resource_server_scopes
#     - hosted UI ......... domain_prefix
#     - environment ....... deletion_protection, secret_recovery_window_in_days
#     - identities ........ seed_users
#     - tagging ........... tags
#
# Returns:
#   Resource attributes, read by the sibling outputs.tf and consumed by other
#   modules through the calling root. The load-bearing ones, and who needs them:
#     - aws_cognito_user_pool.this.id and .arn -- the pool identifier and ARN.
#     - aws_cognito_user_pool.this.endpoint -- the host half of the OIDC issuer
#       URI. infra/modules/api-gateway-http builds its JWT authorizer issuer
#       from it, and every service's OAuth2 resource-server configuration reads
#       the same value out of Parameter Store, written there by the calling root.
#     - aws_cognito_user_pool_client.this.id -- the app client identifier, which
#       is the JWT authorizer's audience and the auth service's client id.
#     - aws_cognito_user_group.admin.name and .user.name -- the two strings that
#       appear in a token's cognito:groups claim and that common-lib's
#       JwtRoleConverter turns into Spring Security authorities.
#     - aws_secretsmanager_secret.app_client.arn and .name, and the same two
#       attributes of aws_secretsmanager_secret.seed_user -- REFERENCES to
#       credentials, never credential values.
#   NO CREDENTIAL IS RETURNED, AND THIS FILE DECLARES NO output BLOCK AT ALL.
#   The generated seed passwords and the generated app client secret exist here
#   only on the resource arguments that produce and store them.
#
# Errors:
#   The coupling and failure surfaces, recorded because each is a pair of
#   arguments that must agree and each fails at APPLY rather than at plan:
#     - mfa_configuration ON or OPTIONAL requires at least one MFA mechanism on
#       the pool. The dynamic software_token_mfa_configuration block below is
#       that mechanism, and it is emitted exactly when the mode is not OFF.
#     - advanced_security_mode AUDIT or ENFORCED requires the pool to be on the
#       PLUS feature tier. user_pool_tier is therefore derived from the mode
#       rather than accepted as a second, independently settable input.
#     - The token validity numbers are unitless. token_validity_units must name
#       minutes, minutes and days to match the three inputs, or the provider
#       reads each number as hours and rejects it against the 24-hour ceiling.
#     - A generated password must satisfy the pool's own password policy. The
#       random_password character-class minimums are derived from the same four
#       inputs the policy uses, so the two cannot disagree.
#     - A non-zero secret_recovery_window_in_days leaves a destroyed secret's
#       NAME reserved, so destroy-then-apply collides on a name that no longer
#       appears to exist.
#     - A hosted-UI domain prefix is globally unique per region, so two
#       environments in one region collide on apply if both set one.
#     - The user pool schema and username_configuration are immutable once the
#       pool exists; changing either forces the pool to be replaced, taking
#       every user in it.
#
# WHY (non-obvious design decisions):
#   - Refactoring Rationale: the baseline authenticated by comparing a cleartext
#     eight-character field. SEC-USR-PWD PIC X(08) at app/cpy/CSUSR01Y.cpy L21
#     holds the password inside the user record itself, and
#     app/cbl/COSGN00C.cbl L223 compares it with `IF SEC-USR-PWD = WS-USER-PWD`
#     -- unhashed, unsalted, no work factor, no lockout. The store it lived in
#     was declared with JOURNAL(NO) at app/csd/CARDDEMO.CSD L94 and
#     RECOVERY(NONE) at L96, so there was no journalling, no recovery and no
#     encryption on it either; and the sign-on transaction ran with CONFDATA(NO)
#     at L384 and RESSEC(NO) CMDSEC(NO) at L385, so confidential-data
#     suppression and CICS resource and command security were all off on the one
#     transaction that handles a password. Worst of all, the seed credentials
#     are committed to this repository: app/jcl/DUSRSECJ.jcl L35-L44 carries ten
#     in-stream records whose password column holds a single eight-character
#     literal, byte-identical for all ten users. That literal is deliberately
#     not transcribed anywhere in this file. Cognito removes the field rather
#     than porting it: the pool owns credential handling, complexity
#     enforcement, lockout and rotation, and no password value is authored into
#     this tree at all.
#   - Alternatives Considered: porting SEC-USR-PWD forward, either as a
#     `custom:` pool attribute or as a column on auth.users, hashed or not.
#     Rejected because it preserves the credential store this design exists to
#     eliminate -- a hash would improve the storage and would still leave a
#     second credential authority to keep in step with the first, while Cognito
#     already owns all of it. Also considered: a self-managed user store, a
#     table plus a hashing library inside auth-service. Rejected because it
#     moves credential handling, password-policy enforcement, lockout and
#     rotation into application code that then has to be maintained and
#     audited, against the guiding principle to prefer managed services where
#     they lower operational burden.
#   - Assumption: RACF has no cloud analogue and is not ported. Its role is
#     filled by least-privilege IAM task roles plus the pool groups created
#     here, and that substitution is a documented MAPPING rather than a port.
#     Only the group half belongs to this module; the IAM half is
#     infra/modules/ecs-service's, and this module creates no IAM role at all.
#   - Assumption: every resource type, argument name, nested block name and
#     accepted value in this file was read from the pinned provider
#     (hashicorp/aws ~> 6.56, resolved 6.57.1) by dumping its schema, not from
#     documentation prose or recollection. That mattered: deletion_protection is
#     a string rather than a bool, threat protection is still the
#     advanced_security_mode argument of the user_pool_add_ons block in this
#     release with no deprecation marker and no equivalent under any other name,
#     the schema block's length constraints are strings rather than numbers, and
#     aws_cognito_resource_server names its scope fields scope_name and
#     scope_description rather than name and description.
#   - Trade-off: this is a child module and declares no provider, no backend and
#     no terraform block -- those live in versions.tf beside it and in the
#     calling root. It also calls no sibling module: the Secrets Manager key
#     arrives as var.secrets_kms_key_arn from infra/modules/kms via the root
#     that instantiates both, and is neither looked up by alias nor rebuilt from
#     an account id. The cost is that this directory cannot be applied on its
#     own; the gain is that key ownership stays in the one module responsible
#     for it and that region, credentials and default tags cannot disagree with
#     the root's provider.
# =============================================================================

# WHAT: every composed name and every derived value the resources below share.
# WHY : Assumption: composition is centralised here rather than repeated at each
#       resource because the names are a contract, not decoration -- the group
#       names are read by code in two other languages, and the secret names are
#       what an operator greps for. Keeping them in one block makes the whole
#       naming contract auditable in one screen. The width bound that makes the
#       composition safe is enforced upstream: variables.tf caps name_prefix at
#       twenty characters of lowercase letters, digits and hyphens precisely so
#       that every name built here stays inside the tightest limit any of the
#       three namespaces involved imposes -- Cognito names, Secrets Manager
#       names, and the DNS-style label a hosted-UI domain prefix must be.
locals {
  # WHY : Assumption: the environment is part of the name rather than only a
  #       tag, because dev and prod are instantiated into the same AWS account
  #       in this design and Cognito pool names are not namespaced by anything
  #       else. A tag would distinguish them in a cost report and would still
  #       let two pools share a name in the console.
  user_pool_name = "${var.name_prefix}-${var.environment}-users"

  # WHY : Assumption: THESE TWO STRINGS ARE A CROSS-LANGUAGE CONTRACT, NOT A
  #       NAMING PREFERENCE. They are what Cognito puts in a token's
  #       cognito:groups claim, and three separate consumers match on them
  #       literally: common-lib's JwtRoleConverter maps them to Spring Security
  #       authorities, ui/src/hooks/useAuth.ts reads them out of the token, and
  #       the SPA's admin-only routes test for the admin one. They are composed
  #       from var.name_prefix rather than hard-coded so that the whole module
  #       carries one naming scheme, and with that variable's default of
  #       "carddemo" they resolve to exactly "carddemo-admin" and
  #       "carddemo-user" -- the two names those consumers expect.
  #       Trade-off: the composition means a root that changes name_prefix
  #       changes the group names and silently breaks authorization in Java and
  #       TypeScript at once, because a token would then carry a group nobody
  #       matches and every user would resolve to no authority rather than to an
  #       error. That coupling is accepted in exchange for one naming scheme,
  #       and it is stated here loudly rather than discovered later.
  admin_group_name = "${var.name_prefix}-admin"
  user_group_name  = "${var.name_prefix}-user"

  # WHY : Assumption: the domain of SEC-USR-TYPE is CLOSED AT TWO VALUES by its
  #       condition names -- 88 CDEMO-USRTYP-ADMIN VALUE 'A' and
  #       88 CDEMO-USRTYP-USER VALUE 'U' at app/cpy/COCOM01Y.cpy L27-L28 -- and
  #       app/cbl/COSGN00C.cbl L230-L240 branches exactly two ways on it. So
  #       this map has two entries and no default arm, and variables.tf already
  #       rejects any third value during plan, which is why no fallback is
  #       invented here.
  #       Alternatives Considered: mapping to local.admin_group_name and
  #       local.user_group_name, which are the same strings and read more
  #       naturally. Rejected for a specific and easily-missed reason: a plain
  #       string would give aws_cognito_user_in_group no dependency on the group
  #       resources, so Terraform would be free to create a membership before
  #       the group it names exists and the apply would fail on a race that
  #       reproduces only sometimes. Referencing the resource attributes makes
  #       the ordering an edge in the graph instead of a coincidence.
  group_name_by_user_type = {
    "A" = aws_cognito_user_group.admin.name
    "U" = aws_cognito_user_group.user.name
  }

  # WHY : Alternatives Considered: iterating var.seed_users with count. Rejected,
  #       and this is the single most consequential shape decision in the file.
  #       count addresses instances by list POSITION, so removing one entry from
  #       the middle of the list shifts every later element down one index;
  #       Terraform then reads that as "instance 4 changed identity, instance 5
  #       changed identity, ..." and destroys and recreates every seed user and
  #       every Secrets Manager entry after the removed one, rotating
  #       credentials that nobody touched. Keying by user_id makes each
  #       instance's address the eight-character SEC-USR-ID it belongs to, so
  #       removing one entry removes exactly one user.
  #       Assumption: the key is unique. variables.tf validates that no user_id
  #       repeats, which is what makes this projection lossless -- without that
  #       check a duplicate would silently collapse two entries into one.
  seed_users_by_id = { for user in var.seed_users : user.user_id => user }

  # WHY : Assumption: a Secrets Manager name may contain forward slashes, so the
  #       path form groups every credential this module owns under one prefix
  #       that an operator can list with a single call. The environment is in
  #       the prefix rather than only in a tag for the same reason as the pool
  #       name: two environments share an account here.
  secret_name_prefix = "${var.name_prefix}-${var.environment}/cognito"

  # WHY : Trade-off: OAuth is enabled only when the caller actually supplies a
  #       redirect target, rather than being a separate input a root could set
  #       inconsistently. Cognito refuses an authorization-code flow on a client
  #       with no callback URL, so the two are not independently choosable, and
  #       deriving one from the other removes a way to configure a client that
  #       cannot be created. With var.callback_urls at its documented default of
  #       empty this evaluates false and the client is authentication-API-only,
  #       which is the EXPECTED configuration for this design: the auth service
  #       authenticates server-side and no browser redirect occurs.
  #       Alternatives Considered: enabling the code flow unconditionally so the
  #       client is ready for a hosted-UI root later. Rejected because it would
  #       make every apply fail until someone supplied a callback URL, which
  #       turns an unused capability into a hard prerequisite.
  oauth_enabled = length(var.callback_urls) > 0

  # WHY : Assumption: Cognito accepts a narrower symbol set in a password than
  #       random_password offers by default, so the set is stated rather than
  #       left to the default and every character here was checked against the
  #       symbols Cognito documents as acceptable.
  #       Trade-off: two groups of characters are excluded deliberately. The
  #       double quote and the backslash are the two characters JSON must
  #       escape, and this value is stored inside a JSON document, so including
  #       them would make the stored form differ visibly from the password and
  #       invite a mis-transcription. The shell metacharacters -- dollar,
  #       backtick, single quote, pipe, ampersand, angle brackets, semicolon and
  #       parentheses -- are excluded because an operator retrieving a bootstrap
  #       credential pastes it into a terminal, and a value that needs quoting
  #       there is a value that gets corrupted there. What remains is thirteen
  #       symbols, which is ample entropy at the fourteen-character default
  #       length.
  password_special_charset = "!#%*+-:=?@^_~"
}

# WHAT: the user pool that replaces the USRSEC VSAM file.
# WHY : Assumption: the pool is a singleton in this module rather than a
#       for_each over some list of pools, because the baseline had exactly one
#       identity store -- one DEFINE FILE(USRSEC) at app/csd/CARDDEMO.CSD L88 --
#       and one sign-on transaction reading it. A second pool would have no
#       counterpart to migrate and would split the cognito:groups claim's
#       meaning across two issuers.
resource "aws_cognito_user_pool" "this" {
  name = local.user_pool_name

  # WHY : Assumption: THIS IS A COUPLING, NOT A SIZING CHOICE, AND IT IS DERIVED
  #       RATHER THAN ACCEPTED AS AN INPUT FOR THAT REASON. Cognito's threat
  #       protection is a feature of the PLUS tier, and a pool left on the
  #       default tier rejects a user_pool_add_ons block naming AUDIT or
  #       ENFORCED -- at apply, after the pool has begun to be created.
  #       Deriving the tier from var.advanced_security_mode makes the two agree
  #       by construction instead of leaving a second input that a root must
  #       remember to raise in step with the first.
  #       Trade-off: PLUS costs more per monthly active user than the default
  #       tier, and cost is an explicit tie-breaker in this migration. That
  #       price buys compromised-credential detection and adaptive risk scoring,
  #       against a baseline whose sign-on transaction ran with RESSEC(NO) and
  #       CMDSEC(NO) at app/csd/CARDDEMO.CSD L385 and had nothing comparable at
  #       all. A root unwilling to pay it sets advanced_security_mode to OFF,
  #       and this expression drops the pool back to the cheaper tier in the
  #       same step rather than leaving it stranded on PLUS with the feature
  #       switched off.
  user_pool_tier = var.advanced_security_mode == "OFF" ? "ESSENTIALS" : "PLUS"

  # WHY : Trade-off: a string, and the two accepted values are ACTIVE and
  #       INACTIVE rather than true and false. A bool is the intuitive shape and
  #       is the wrong one; the value is passed through from the input
  #       unconverted so that the mapping is not re-derived here, where a reader
  #       would have to trust it. The dev default of INACTIVE is what lets
  #       `terraform destroy` complete in one step, which is the teardown
  #       criterion this package is accepted against; prod sets ACTIVE and
  #       accepts a deliberate two-step teardown for a pool holding real
  #       identities.
  deletion_protection = var.deletion_protection

  # WHY : Refactoring Rationale: THIS BLOCK IS WHERE PARITY IS DECLINED, AND THE
  #       DIVERGENCE IS THE POINT OF IT. The baseline's credential was
  #       SEC-USR-PWD PIC X(08) at app/cpy/CSUSR01Y.cpy L21 -- exactly eight
  #       characters, no complexity rule of any kind, compared in the clear at
  #       app/cbl/COSGN00C.cbl L223 -- and its ten seed values are committed in
  #       the open at app/jcl/DUSRSECJ.jcl L35-L44 as one shared literal. A
  #       faithful port would reproduce the length and discard the reason it was
  #       ever thought sufficient, which was nothing. The floor and the four
  #       class requirements below are therefore deliberately stronger than the
  #       baseline, variables.tf refuses to let a caller configure the
  #       baseline's own length back in, and the resulting behavioural change --
  #       a baseline credential would not satisfy this policy -- is registered
  #       in docs/architecture/cobol-to-service-traceability.md rather than
  #       passed off as parity.
  #       Assumption: the four class requirements are one decision rather than
  #       four, which is why they share this comment. The baseline enforced no
  #       complexity rule at all, so there is no per-class behaviour to preserve
  #       or diverge from individually. They are inputs rather than hard-coded
  #       true so that the posture is legible in the environment root an
  #       operator reads, and a complete policy is also what the gating policy
  #       scan expects to find here.
  password_policy {
    minimum_length    = var.password_minimum_length
    require_lowercase = var.password_require_lowercase
    require_uppercase = var.password_require_uppercase
    require_numbers   = var.password_require_numbers
    require_symbols   = var.password_require_symbols

    # WHY : Trade-off: each seed credential is generated during apply, which
    #       means it exists in Terraform state as well as in Secrets Manager --
    #       see the note on random_password below, where that is faced directly
    #       rather than glossed. A short window is the only control this module
    #       has over how long that copy is worth anything. The cost is named
    #       rather than hidden: apply on one day and sign in for the first time
    #       several days later, and the credential has lapsed and needs an
    #       operator-initiated reset. That is preferable to leaving bootstrap
    #       credentials valid indefinitely, which is what the alternative
    #       amounts to.
    temporary_password_validity_days = var.temporary_password_validity_days
  }

  # WHY : Trade-off: OPTIONAL in dev, and variables.tf structurally requires ON
  #       in prod. ON compels every user to enrol a factor before completing a
  #       sign-in, which would block all ten seed users on the very first
  #       authentication -- the one sign-in that has to succeed for a freshly
  #       provisioned environment to be usable at all. OPTIONAL keeps the
  #       baseline's single-factor flow working while making a second factor
  #       available, so a capability the baseline entirely lacked is present
  #       without gating provisioning on it.
  #       Alternatives Considered: OFF, which removes the capability rather than
  #       deferring the obligation, and ON everywhere, which is right once real
  #       users exist and is exactly what prod is held to.
  mfa_configuration = var.mfa_configuration

  # WHY : Assumption: this block IS the mechanism the setting above requires.
  #       Cognito rejects mfa_configuration ON or OPTIONAL on a pool with no MFA
  #       mechanism configured, so the two are coupled and an inconsistent pair
  #       fails at apply rather than at plan. Emitting the block exactly when
  #       the mode is not OFF makes the pair consistent by construction.
  #       Alternatives Considered: declaring the block unconditionally.
  #       Rejected -- it pairs an enabled mechanism with a mode of OFF, which is
  #       a contradiction the service has no reason to accept and which would
  #       only ever surface as an apply-time error.
  #       Alternatives Considered: SMS as the mechanism instead. Rejected
  #       because it needs an SNS caller role and a phone number, and these
  #       identities carry neither -- the seed record at
  #       app/cpy/CSUSR01Y.cpy L17-L23 has no telephone field at all.
  dynamic "software_token_mfa_configuration" {
    for_each = var.mfa_configuration == "OFF" ? [] : [1]

    content {
      enabled = true
    }
  }

  # WHY : Assumption: the attribute name was verified against the pinned
  #       provider rather than assumed, because this part of Cognito's surface
  #       has moved elsewhere. In aws 6.57.1 the setting is still
  #       advanced_security_mode inside user_pool_add_ons, it is REQUIRED
  #       whenever that block is present, it carries no deprecation marker, and
  #       a search of every aws_cognito_* resource in that provider finds no
  #       threat-protection attribute under any other name.
  #       Alternatives Considered: emitting the block unconditionally with the
  #       mode passed straight through, including OFF. Rejected because OFF is
  #       also the only mode available outside the paid tier, so the block would
  #       then have to be reconciled with a pool deliberately left on the
  #       cheaper tier; omitting it entirely says the same thing with nothing to
  #       reconcile.
  dynamic "user_pool_add_ons" {
    for_each = var.advanced_security_mode == "OFF" ? [] : [var.advanced_security_mode]

    content {
      advanced_security_mode = user_pool_add_ons.value
    }
  }

  # WHY : Refactoring Rationale: self-registration is closed because the
  #       baseline had no self-registration path whatsoever. A user existed only
  #       if an administrator created it through COUSR01C or if the
  #       app/jcl/DUSRSECJ.jcl load wrote it, and there is no program in the
  #       baseline through which an unauthenticated caller could create one.
  #       Leaving sign-up open would add a capability the system never had, on a
  #       pool whose groups grant application authority.
  #       Assumption: closing sign-up does not close user creation -- the admin
  #       API still creates users, which is how the seed identities below and
  #       auth-service's own add-user endpoint work.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  # WHY : Assumption: admin_only is chosen because it is what the baseline
  #       actually did, not because recovery is unimportant. A forgotten
  #       password in the baseline required an administrator to rewrite the
  #       USRSEC record; there was no self-service path, and this mechanism is
  #       that posture expressed natively.
  #       Alternatives Considered: verified_email, which is the usual default
  #       and is unusable here for a concrete reason -- these identities carry
  #       no email address at all. The seed record at
  #       app/cpy/CSUSR01Y.cpy L17-L23 has an id, two names, a password and a
  #       type and nothing else, so a pool configured for email recovery would
  #       advertise a recovery route that could never complete. Configuring a
  #       mechanism that cannot work is worse than configuring none, because it
  #       reads as a working one.
  #       Assumption: a single mechanism at priority 1. admin_only is not
  #       combinable with the others, and the provider accepts at most two
  #       recovery_mechanism entries in any case.
  account_recovery_setting {
    recovery_mechanism {
      name     = "admin_only"
      priority = 1
    }
  }

  # WHY : Trade-off: case-INsensitive, and this is a deliberate divergence from
  #       the baseline rather than a faithful mapping. The baseline key is
  #       SEC-USR-ID PIC X(08) at app/cpy/CSUSR01Y.cpy L18 holding upper-case
  #       ids, and the keyed read at app/cbl/COSGN00C.cbl L211-L219 matches
  #       RIDFLD byte-exactly, so a lower-case entry there simply failed to find
  #       the record. Case-sensitivity here would preserve that and would also
  #       permit two DISTINCT pool identities differing only in case, and both
  #       of them would then collide on the same eight-byte SEC-USR-ID key --
  #       the primary key of auth.users and the key the ETL loads USRSEC under.
  #       Two identities that cannot be told apart downstream is the worse
  #       failure, so case-insensitivity is chosen and the divergence -- this
  #       pool accepts a sign-on the baseline would have rejected -- is
  #       registered in docs/architecture/cobol-to-service-traceability.md.
  #       Assumption: this setting is IMMUTABLE once the pool exists. Changing
  #       it later forces the pool to be replaced and takes every user in it, so
  #       it is a decision made once at creation and not tuned afterwards.
  username_configuration {
    case_sensitive = false
  }

  # WHY : Assumption: exactly ONE attribute is declared, and the three absences
  #       are each deliberate. Mapping from app/cpy/CSUSR01Y.cpy:
  #         L19 SEC-USR-FNAME PIC X(20) -> given_name
  #         L20 SEC-USR-LNAME PIC X(20) -> family_name
  #         L21 SEC-USR-PWD   PIC X(08) -> NOTHING AT ALL
  #         L22 SEC-USR-TYPE  PIC X(01) -> custom:user_type, declared below
  #         L23 SEC-USR-FILLER PIC X(23) -> dropped as padding
  #       given_name and family_name are Cognito STANDARD attributes and are
  #       therefore NOT redeclared: the provider documents that a standard
  #       attribute needs a schema entry only when it differs from the default
  #       configuration, and since schema is immutable, a redundant entry is a
  #       permanent replacement risk for no gain. Their X(20) widths are still
  #       enforced -- variables.tf validates both at twenty characters against
  #       those exact copybook lines.
  #       L21 is the declined-parity decision restated at the one place its
  #       absence would otherwise be invisible: there is no password attribute,
  #       custom or standard, because Cognito owns the credential.
  #       L23 is dropped because FILLER pads the record to its 80-byte length
  #       and is not data; the drop is recorded rather than silent.
  #       Assumption: schema is IMMUTABLE. Adding or changing an entry after the
  #       pool exists forces the pool to be replaced, taking every user with it,
  #       so the set below is chosen once and the provider's fifty-attribute
  #       ceiling is nowhere near reached.
  schema {
    # WHY : Assumption: the name carries no prefix here. Cognito prefixes a
    #       declared custom attribute itself, so this entry is what produces the
    #       attribute named "custom:user_type" that aws_cognito_user sets below
    #       -- the two spellings are the same attribute, not a mismatch.
    name                = "user_type"
    attribute_data_type = "String"

    # WHY : Assumption: stated as false rather than omitted, because the
    #       alternative is not "no setting" but a genuinely different attribute.
    #       A developer-only attribute is invisible to the app client and cannot
    #       be read from a token at all, which would make this value unreadable
    #       by the very service that records the lineage it exists for. Writing
    #       the choice out means a later reader sees that visibility was decided
    #       rather than inherited from a default.
    developer_only_attribute = false

    # WHY : Assumption: mutable because a user's role genuinely changes -- the
    #       baseline rewrote SEC-USR-TYPE in place through the COUSR02C update
    #       screen -- so an administrator must be able to correct it without
    #       deleting and recreating the identity. That does not make it
    #       client-writable: the app client's write_attributes below
    #       deliberately excludes it, so the ability to change a role stays with
    #       the admin API.
    mutable = true

    # WHY : Assumption: not required, because Cognito does not permit a custom
    #       attribute to be required. That is why AUTHORIZATION DOES NOT DEPEND
    #       ON THIS ATTRIBUTE -- group membership is what grants authority, and
    #       this attribute exists to trace an identity back to the SEC-USR-TYPE
    #       value it came from. Nothing breaks if it is absent on a user
    #       created outside this module; that user simply has no recorded
    #       lineage to the baseline record.
    required = false

    # WHY : Assumption: the width is the copybook's, not a round number. It is
    #       one character because SEC-USR-TYPE is PIC X(01) at
    #       app/cpy/CSUSR01Y.cpy L22, and the domain is closed at 'A' and 'U' by
    #       the condition names at app/cpy/COCOM01Y.cpy L27-L28, so one
    #       character is the whole contract. The bounds are STRINGS in this
    #       provider even though they express numbers; that is the schema's
    #       shape, verified rather than assumed, and quoting them is not a
    #       stylistic choice.
    string_attribute_constraints {
      min_length = "1"
      max_length = "1"
    }
  }

  # WHY : Assumption: no merge() call appears here on purpose, and the absence
  #       would otherwise look like a missing merge. The calling root's provider
  #       applies default_tags to every resource in the graph and merges them
  #       with a resource's own tags itself, so passing the input straight
  #       through is additive already; wrapping it in merge() here would only
  #       invite someone to add the environment or the module name a second time
  #       and let a module argument contradict provider configuration the root
  #       owns.
  tags = var.tags
}

# WHAT: the single app client through which the migrated system authenticates.
# WHY : Assumption: this client is CONFIDENTIAL and SERVER-SIDE, which is the
#       decision that governs every argument below and is stated once here.
#       The browser never speaks to this pool. ui/src/screens/signon renders the
#       baseline's own sign-on screen, transcribed from app/bms/COSGN00.bms, and
#       posts what it collects to services/auth-service; that service
#       authenticates against the pool with the flow named in
#       var.explicit_auth_flows, computing the request's SECRET_HASH from this
#       client's id, the submitted user id and this client's generated secret,
#       and returns the resulting token. This is also why var.callback_urls and
#       var.logout_urls default to empty and why nothing in ui/.env.example
#       configures this pool.
#       Alternatives Considered: a PUBLIC client with generate_secret disabled,
#       which is the right shape for a browser that redirects to a hosted
#       sign-in page and completes an authorization-code exchange itself.
#       Rejected for a specific behavioural reason rather than a stylistic one:
#       a hosted page emits its own wording, which would strand the three
#       verbatim baseline sign-on replies at app/cbl/COSGN00C.cbl L242-L243,
#       L249 and L254 that this migration is required to preserve. Since the
#       exchange therefore happens server-side, a secret CAN be held safely --
#       it never enters a browser bundle -- and holding one authenticates the
#       caller as well as the user.
resource "aws_cognito_user_pool_client" "this" {
  name         = "${var.name_prefix}-${var.environment}-app-client"
  user_pool_id = aws_cognito_user_pool.this.id

  # WHY : Assumption: enabled BECAUSE this client is server-side, and the
  #       reasoning is the exact inverse of the usual advice. A secret must never
  #       be issued to a client that ships to a browser, because anything in a
  #       bundle is readable and the secret would be published rather than kept.
  #       Here the only holder is services/auth-service running in a private
  #       subnet, so the secret is a second authentication factor ON THE CALLER:
  #       possession of a stolen user password alone is not enough to obtain a
  #       token, because the request must also carry a correct SECRET_HASH.
  #       Trade-off: the secret is itself a credential that has to be stored and
  #       distributed, which is a burden a public client does not have. It is
  #       accepted because the storage is the same managed, customer-key-
  #       encrypted path the seed credentials use -- see the Secrets Manager
  #       entry immediately below -- and because the alternative was rejected on
  #       the behavioural grounds recorded above, not on this one.
  generate_secret = true

  # WHY : Assumption: an app client permits ONLY the flows named here, so this
  #       is the allow-list that decides whether sign-on works at all rather
  #       than a hardening knob. variables.tf defaults it to the password flow
  #       plus refresh and structurally requires the password flow to be
  #       present, because that is the one call through which auth-service can
  #       submit a user id and a password together and receive tokens or a
  #       challenge.
  #       Trade-off: naming the password flow means the password does reach the
  #       pool, over TLS, which SRP would avoid by proving knowledge instead.
  #       SRP is the right choice for an UNTRUSTED client and buys little for a
  #       server-side caller already authenticated by a client secret, while
  #       costing a multi-round exchange; it is reachable through this input for
  #       a root that wants it. What matters against the baseline is that no
  #       component of this system ever compares a stored password: the pool
  #       verifies a salted hash it alone holds, which is the direct antithesis
  #       of app/cbl/COSGN00C.cbl L223 reading SEC-USR-PWD out of a record and
  #       comparing it as text.
  explicit_auth_flows = var.explicit_auth_flows

  # WHY : Assumption: stated explicitly rather than left to the provider's
  #       default so that adding a federated identity provider becomes a visible
  #       edit here instead of something a pool silently begins to accept. No
  #       external provider is federated into this pool -- the baseline had one
  #       identity store and one sign-on program, and there is nothing to
  #       federate with.
  supported_identity_providers = ["COGNITO"]

  # WHY : Assumption: derived from whether a redirect target exists, for the
  #       reason recorded on local.oauth_enabled -- Cognito refuses a code flow
  #       with no callback URL, so these cannot be chosen independently. With
  #       the documented empty default the client is authentication-API-only.
  #       Assumption: the scopes requested here are the pool's BUILT-IN ones and
  #       deliberately NOT the custom scopes registered on the resource server
  #       below. A custom scope is granted only through the OAuth flows, so
  #       requiring one on an interactively-obtained token would reject every
  #       token a signed-in user holds; the resource server's vocabulary exists
  #       for a future client-credentials caller instead.
  allowed_oauth_flows_user_pool_client = local.oauth_enabled
  allowed_oauth_flows                  = local.oauth_enabled ? ["code"] : null
  allowed_oauth_scopes                 = local.oauth_enabled ? ["openid", "profile"] : null

  # WHY : Assumption: both are supplied by the calling root from the CloudFront
  #       distribution that serves the SPA, because this module calls no sibling
  #       module and cannot read that module's output itself. They are set here
  #       even though this design performs no redirect, so that the surface
  #       exists for a root that later adds a hosted-UI client; with the
  #       documented empty default they register no redirect target, which is
  #       coherent, rather than a plausible-looking URL pointing somewhere
  #       nobody controls.
  callback_urls = var.callback_urls
  logout_urls   = var.logout_urls

  # WHY : Assumption: THE NUMBERS AND THE UNITS BLOCK MUST AGREE, and the block
  #       is not optional decoration. The provider reads each validity as a bare
  #       number against token_validity_units and defaults that block to HOURS
  #       when it is absent, so the sixty below would be read as sixty hours and
  #       rejected against the twenty-four-hour ceiling. The units named here
  #       are minutes, minutes and days, matching the three inputs whose own
  #       names carry their unit for exactly this reason.
  #       Trade-off: the refresh token is deliberately the LONGEST-lived of the
  #       three, which is the opposite of the intuitive arrangement. It is the
  #       only one that can be revoked -- see enable_token_revocation below --
  #       whereas an access or id token cannot be recalled once issued and is
  #       therefore held to minutes. Making the durable credential the
  #       recallable one is what allows a compromised session to be ended at
  #       all; the cost is that the SPA must refresh, paid once in
  #       ui/src/api/client.ts for every screen.
  access_token_validity  = var.access_token_validity_minutes
  id_token_validity      = var.id_token_validity_minutes
  refresh_token_validity = var.refresh_token_validity_days

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }

  # WHY : Assumption: this is what makes the refresh token's long lifetime
  #       defensible rather than merely convenient. Without revocation a leaked
  #       refresh token is usable for its whole configured life and nothing can
  #       stop it; with revocation a compromised session can be ended centrally.
  #       The two settings are one decision and are argued together above.
  enable_token_revocation = true

  # WHY : Trade-off: this is the one place where a security correction and the
  #       migration's verbatim-message requirement genuinely pull against each
  #       other, so both halves are recorded. The baseline answers the two
  #       failure modes DIFFERENTLY on purpose -- app/cbl/COSGN00C.cbl L242-L243
  #       reports a wrong password when the keyed read succeeded but the
  #       comparison failed, and L249 reports an unknown user when the read
  #       returned RESP 13 -- and the difference between those two replies tells
  #       an unauthenticated caller which user ids exist. That is user
  #       enumeration, and the ids in question are published in this repository
  #       at app/jcl/DUSRSECJ.jcl L35-L44.
  #       ENABLED makes the identity provider answer both cases identically. The
  #       message TEXT is still preserved verbatim where the migration requires
  #       it -- in services/auth-service and ui/src/messages/messages.ts, which
  #       is where user-visible strings live, and deliberately not in a .tf file
  #       -- while the provider stops revealing which case applies. The resulting
  #       loss of discrimination between the two failure modes is a behavioural
  #       divergence and is registered in
  #       docs/architecture/cobol-to-service-traceability.md.
  #       Alternatives Considered: LEGACY, which reproduces the baseline's
  #       distinguishable responses exactly. Rejected as the default because it
  #       ports the defect rather than the behaviour, but left reachable through
  #       this input so the posture is a decision an environment root states.
  prevent_user_existence_errors = var.prevent_user_existence_errors

  # WHY : Assumption: both lists are stated rather than left at the provider's
  #       permissive default, and the asymmetry between them is the point.
  #       read_attributes includes the user type so auth-service can record the
  #       lineage of an identity back to SEC-USR-TYPE; write_attributes omits it
  #       so that the client which authenticates a user cannot rewrite that
  #       user's role. The baseline changed SEC-USR-TYPE only through the
  #       admin-only COUSR02C screen, and excluding it here keeps role change on
  #       the admin API where an IAM identity is required.
  #       Assumption: this does not weaken authorization, because AUTHORIZATION
  #       RIDES ON THE GROUP CLAIM. cognito:groups is a group claim rather than
  #       a schema attribute, so it is not governed by read_attributes at all
  #       and still reaches common-lib's JwtRoleConverter intact. Rewriting the
  #       attribute would corrupt traceability rather than escalate privilege --
  #       which is why this is a correctness control, not a privilege boundary.
  #       Trade-off: an explicit read list means an attribute added later is
  #       invisible to this client until it is added here too. Accepted: these
  #       identities carry exactly the three attributes the copybook record
  #       supports, and a silent omission is easier to notice than a silent
  #       write.
  read_attributes  = ["given_name", "family_name", "custom:user_type"]
  write_attributes = ["given_name", "family_name"]
}

# WHAT: the Secrets Manager entry carrying the app client's generated secret.
# WHY : Assumption: this resource is what makes the confidential client above
#       usable, and without it the design would not work. generate_secret
#       produces a value only Cognito and this state know, and
#       services/auth-service needs it to compute a request's SECRET_HASH. The
#       secret store is the only route between the two that does not put a
#       credential somewhere it must not be.
#       Alternatives Considered: publishing the value from outputs.tf so the
#       calling root could pass it into the service's environment. Rejected
#       outright -- a Terraform output is displayed on the console, written into
#       any consuming root's state, and trivially read with `terraform output`,
#       so it would turn a managed secret into a printed one. outputs.tf
#       publishes this entry's NAME and ARN, which is a reference rather than a
#       credential, and the service resolves the value at run time under its own
#       task-role permissions.
#       Alternatives Considered: an SSM Parameter Store SecureString instead.
#       Rejected for consistency and capability -- the seed credentials below
#       already live in Secrets Manager, splitting one module's credentials
#       across two stores doubles the number of places to audit, and Secrets
#       Manager supports the managed rotation this value will eventually want.
resource "aws_secretsmanager_secret" "app_client" {
  # WHY : Assumption: this suppression names the check id the scanner actually
  #       emitted on this resource, observed by running it rather than guessed,
  #       and the check is genuinely inapplicable here rather than inconvenient.
  #       It is a graph check that passes only when a rotation resource is
  #       attached to the secret, and no such resource can be attached to THIS
  #       secret: Cognito exposes no operation that regenerates an existing app
  #       client's secret, because the value is produced when the client is
  #       created. "Rotating" it therefore means replacing the app client, and
  #       with it the client id that the JWT authorizer's audience and every
  #       service's resource-server configuration are bound to -- which is a
  #       coordinated redeployment, not something a rotation function can
  #       perform unattended.
  #       Alternatives Considered: attaching a rotation resource driven by a
  #       custom function that creates a replacement client and repoints its
  #       consumers. Rejected as out of scope and as the wrong shape: this
  #       migration defines no such function, and a rotation that changes an
  #       identifier other systems hold is a release procedure rather than a
  #       rotation. The check is not in the severity band the policy gate fails
  #       on, so this records why the finding stands rather than silencing a
  #       gating failure.
  #checkov:skip=CKV2_AWS_57:Cognito cannot regenerate an app client secret in place -- the value is set at client creation, so rotation would replace the client and change the client id that the JWT authorizer and every service configuration are bound to.
  name = "${local.secret_name_prefix}/app-client"

  # WHY : Assumption: the description names what the entry is FOR and never any
  #       part of what it contains. A description is metadata, readable by
  #       anyone who can list secrets without permission to read the value
  #       itself, so putting any fragment of a credential here would defeat the
  #       resource's whole purpose.
  description = "Client id and generated client secret for the ${var.environment} CardDemo Cognito app client, read at run time by auth-service to compute the SECRET_HASH on an authentication request."

  # WHY : Refactoring Rationale: a CUSTOMER-MANAGED key rather than the
  #       AWS-managed default, which would also encrypt and would do so
  #       silently. A customer-managed key carries a key policy that can be
  #       audited and revoked independently of the secret, and revoking it
  #       renders the stored value unreadable without touching the secret -- a
  #       containment step that does not exist with the default key. Set against
  #       the baseline the contrast is total: the identity store it replaces was
  #       declared with JOURNAL(NO) at app/csd/CARDDEMO.CSD L94 and
  #       RECOVERY(NONE) at L96 and was not encrypted at all.
  #       Assumption: the key is created by infra/modules/kms and passed in by
  #       the calling root, because this module calls no sibling module and
  #       resolves no data source for it. variables.tf makes the input required
  #       with no default precisely so that a fall-back to the AWS-managed key
  #       cannot happen unnoticed.
  kms_key_id = var.secrets_kms_key_arn

  # WHY : Trade-off: zero in dev, a real window in prod, and the trap is
  #       specific enough to be worth naming. Any NON-ZERO window leaves a
  #       destroyed secret's NAME reserved for its duration, so a destroy
  #       followed by a re-apply collides on a name that no longer appears to
  #       exist anywhere -- which breaks both the teardown criterion this package
  #       is accepted against and ordinary iteration. Zero deletes immediately
  #       and makes destroy-then-apply work. prod accepts the opposite trade,
  #       keeping recoverability and giving up the ability to re-apply at once.
  #       Assumption: the accepted domain is DISCONTINUOUS -- zero, or seven
  #       through thirty -- which is why variables.tf validates it as a
  #       disjunction rather than a range; values one through six are refused.
  recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumption: passed through with no merge() call, for the reason
  #       recorded on the user pool's own tags argument -- the calling root's
  #       provider merges its default_tags with a resource's tags itself, so
  #       wrapping this would risk a module argument restating, and then
  #       contradicting, provider configuration the root owns.
  tags = var.tags
}

# WHAT: the version holding the client id and the generated client secret.
# WHY : Alternatives Considered: storing the secret alone as a bare string.
#       Rejected because auth-service needs the client id in the same breath --
#       a SECRET_HASH is computed over the user id, the client id and the secret
#       -- so a bare string would force a second lookup or an environment
#       variable that could drift out of step with the secret it belongs to. A
#       JSON object keyed client_id and client_secret keeps the pair together
#       and self-describing. The client id is not itself confidential; it
#       travels here because it is useless apart from the secret and harmful to
#       separate from it.
resource "aws_secretsmanager_secret_version" "app_client" {
  secret_id = aws_secretsmanager_secret.app_client.id

  # WHY : Assumption: the client_secret attribute is marked sensitive by the
  #       provider, so it is redacted in plan output and in any CLI display, and
  #       jsonencode carries it through without that marking being lost.
  #       Trade-off: as with every value Terraform manages, this one is present
  #       in state -- faced directly in the note on random_password below, where
  #       the same trade-off applies to the seed credentials and the mitigations
  #       are enumerated. It is stated rather than glossed here too, because a
  #       claim that a managed secret is absent from state would be false.
  secret_string = jsonencode({
    client_id     = aws_cognito_user_pool_client.this.id
    client_secret = aws_cognito_user_pool_client.this.client_secret
  })
}

# WHAT: the resource server that gives the migrated API its own scope names
#       inside this pool.
# WHY : Assumption: without a resource server the ONLY scope a token from this
#       pool can carry is Cognito's built-in one, so there is no vocabulary in
#       which a non-interactive caller could request a narrower grant and
#       nothing in which a future per-route authorization decision could be
#       expressed. Declaring the identifier and the scopes here creates that
#       vocabulary once, at the pool that owns it, instead of leaving each
#       consumer to invent one.
#       Trade-off: these scopes are NOT what an interactive sign-on presents. A
#       token obtained through the pool's authentication API carries the
#       built-in scope and no custom one, because custom scopes are granted only
#       through the OAuth grants this design deliberately does not use for
#       sign-on -- which is why infra/modules/api-gateway-http defaults its
#       route scope to the built-in value instead. What these are for is the
#       client-credentials caller a later integration will need, and the gain
#       from declaring them now is that the vocabulary is fixed by the module
#       that owns the pool rather than improvised by whoever adds that client.
resource "aws_cognito_resource_server" "this" {
  user_pool_id = aws_cognito_user_pool.this.id
  name         = "${var.name_prefix}-${var.environment}-api"

  # WHY : Assumption: Cognito PREFIXES this identifier onto each scope name to
  #       form the value that appears in a token, so the default
  #       "carddemo-api" with a scope "read" yields the token scope
  #       "carddemo-api/read". A bare name is used rather than the URL shape
  #       that OAuth convention favours, because a URL invites the reading that
  #       the string is an address something can be fetched from, which it is
  #       not.
  identifier = var.resource_server_identifier

  # WHY : Assumption: the nested field names are scope_name and
  #       scope_description, NOT name and description, and both are required.
  #       That was read from the pinned provider's schema rather than inferred;
  #       the shorter spellings are the intuitive guess and the provider does
  #       not accept them.
  #       Assumption: an empty scope list is legal, so a root that wants no
  #       custom vocabulary passes one and this loop emits no block -- the
  #       resource server is then registered with no scope, which the provider
  #       accepts. That is why no guard against an empty list appears here.
  dynamic "scope" {
    for_each = var.resource_server_scopes

    content {
      scope_name        = scope.value.name
      scope_description = scope.value.description
    }
  }
}

# WHAT: the administrator group, carrying SEC-USR-TYPE value 'A'.
# WHY : Assumption: TWO groups exist as two explicit resources rather than one
#       for_each over a list, because the domain is CLOSED at two values and
#       writing it out is what makes that visible in the code. 88
#       CDEMO-USRTYP-ADMIN VALUE 'A' and 88 CDEMO-USRTYP-USER VALUE 'U' at
#       app/cpy/COCOM01Y.cpy L27-L28 are the whole domain, and
#       app/cbl/COSGN00C.cbl L230-L240 branches exactly two ways on it -- XCTL
#       to COADM01C for an administrator, XCTL to COMEN01C otherwise. There is
#       no third group, no fallback group and no wildcard, and a list would
#       suggest the set is open when it is not.
resource "aws_cognito_user_group" "admin" {
  name         = local.admin_group_name
  user_pool_id = aws_cognito_user_pool.this.id
  description  = "Administrators. Carries SEC-USR-TYPE value 'A', the condition name CDEMO-USRTYP-ADMIN at app/cpy/COCOM01Y.cpy L27, which app/cbl/COSGN00C.cbl L230 tests to route a user to the admin menu."

  # WHY : Assumption: LOWER PRECEDENCE WINS IN COGNITO, which is the opposite of
  #       the intuitive reading and is why the number is explained rather than
  #       just set. When a user belongs to more than one group, the group with
  #       the lowest precedence supplies the effective role, so putting the
  #       administrator group at 1 and the user group at 10 resolves such a user
  #       to administrator. That mirrors app/cbl/COSGN00C.cbl L230-L240 exactly,
  #       where the admin test comes first and the user path is the ELSE arm.
  #       The gap between 1 and 10 leaves room for a group to be inserted
  #       between them without renumbering either.
  precedence = 1

  # WHY : Alternatives Considered: attaching an IAM role here, which the
  #       resource permits and which reads at first glance like the natural way
  #       to express the RACF-to-IAM half of the security mapping. Rejected
  #       because that attribute serves Cognito IDENTITY POOLS vending temporary
  #       AWS credentials to a client, and this design has no identity pool: the
  #       services are OAuth2 resource servers that validate a JWT, and no
  #       browser or user is ever given AWS credentials. Setting it would attach
  #       a role nothing assumes. The IAM half of that mapping belongs to
  #       infra/modules/ecs-service's task roles; this module creates no IAM
  #       role, and role_arn is left unset for that reason rather than by
  #       oversight.
}

# WHAT: the ordinary-user group, carrying SEC-USR-TYPE value 'U'.
# WHY : Assumption: the reasoning for declaring exactly two explicit groups, for
#       the precedence direction, and for leaving role_arn unset is recorded on
#       the administrator group above and applies here unchanged. It is not
#       repeated, because two comments that differ by a word are how one of them
#       drifts.
resource "aws_cognito_user_group" "user" {
  name         = local.user_group_name
  user_pool_id = aws_cognito_user_pool.this.id
  description  = "Ordinary users. Carries SEC-USR-TYPE value 'U', the condition name CDEMO-USRTYP-USER at app/cpy/COCOM01Y.cpy L28, which is the ELSE arm at app/cbl/COSGN00C.cbl L235 routing a user to the main menu."
  precedence   = 10
}

# WHAT: an optional Cognito-hosted sign-in domain, created only when a prefix is
#       supplied.
# WHY : Alternatives Considered: provisioning the hosted UI unconditionally,
#       which an authorization-code flow would require. Rejected for TWO
#       INDEPENDENT reasons, either of which would be sufficient. First, nothing
#       authenticates through it: ui/src/screens/signon renders the baseline's
#       own sign-on screen from app/bms/COSGN00.bms and auth-service completes
#       the exchange server-side, so a hosted page would be a second, unused
#       entry point to the identity provider -- and it would emit its own
#       wording, stranding the three verbatim baseline replies at
#       app/cbl/COSGN00C.cbl L242-L243, L249 and L254. Second, a hosted-UI
#       domain prefix is GLOBALLY UNIQUE WITHIN A REGION, so creating one
#       unconditionally would make two environments in one region collide on
#       apply -- and the collision would surface when the second environment was
#       created, not when the default was chosen.
#       Assumption: count is used here rather than for_each, and this is the one
#       resource in the file where that is correct. The subject is a single
#       optional resource governed by a nullability test, not a collection
#       keyed by identity, so there is no re-indexing hazard of the kind that
#       rules count out for the seed users below -- the instance is either
#       present or absent and has no neighbours to shift.
resource "aws_cognito_user_pool_domain" "this" {
  count = var.domain_prefix == null ? 0 : 1

  domain       = var.domain_prefix
  user_pool_id = aws_cognito_user_pool.this.id
}


# -----------------------------------------------------------------------------
# Seed identities, and the credential chain that provisions them.
#
# WHAT: for each requested identity -- a generated password, a Secrets Manager
#       entry holding it, the pool user, and that user's group membership.
# WHY : Refactoring Rationale: this five-resource chain is the whole answer to
#       the defect at app/jcl/DUSRSECJ.jcl L35-L44, where the baseline's ten seed
#       identities are loaded from in-stream JCL data whose password column holds
#       ONE eight-character literal, byte-identical for all ten users, committed
#       to source control. Here each identity gets a DISTINCT password that is
#       generated during apply, is never authored into any file, and is issued as
#       a temporary value that must be changed on first sign-in. The baseline's
#       literal is deliberately not transcribed anywhere in this module, not even
#       as an illustration of what it replaces.
#       Assumption: var.seed_users DEFAULTS TO EMPTY, so a root that says nothing
#       gets the pool, the client and both groups with no users at all, and every
#       resource in this section produces zero instances. That is the expected
#       posture for production; the baseline's ten identities remain readable in
#       the reference tree at the cited lines, and the ETL loads the same records
#       into auth.users, so creating them in a managed identity pool is a
#       deliberate act rather than something that happens by omission.
# -----------------------------------------------------------------------------

# WHAT: one generated initial password per requested identity.
# WHY : Alternatives Considered: random_string, which produces an equally random
#       value and is the obvious substitute. Rejected for one concrete,
#       verifiable difference: random_password marks its result SENSITIVE in the
#       provider schema, so the value is redacted in plan output and in CLI
#       display, while random_string's result is not and would be printed in
#       full by any plan a reviewer pastes into a ticket or a CI log. The
#       generated entropy is identical; the handling is not.
#       Trade-off: THE GENERATED VALUE IS PRESENT IN TERRAFORM STATE, and saying
#       otherwise would be false. Terraform must remember what it created in
#       order to detect drift, so a generated credential is necessarily
#       persisted. Four mitigations bound that honestly, and every one of them is
#       checkable rather than aspirational: state lives in the versioned,
#       encrypted, publicly-inaccessible S3 bucket with a TLS-only bucket policy
#       provisioned by infra/bootstrap; the root .gitignore makes any *.tfstate*
#       uncommittable, so a state file cannot reach this repository even by
#       accident; the value is issued as a TEMPORARY password below, so it cannot
#       become a standing credential; and it expires after
#       var.temporary_password_validity_days, which defaults to a single day.
#       Alternatives Considered: generating the passwords outside Terraform and
#       injecting them, which would keep them out of state. Rejected because they
#       would then have to come from a tracked tfvars file or a CI variable,
#       which is the committed-credential defect above wearing a different name.
resource "random_password" "seed_user" {
  for_each = local.seed_users_by_id

  # WHY : Assumption: the length is the POOL'S OWN minimum, so the two cannot
  #       disagree -- a generated value shorter than the policy would be rejected
  #       when the user is created, and the service's error text does not say
  #       which rule was broken.
  #       Assumption: the class minimums below can never exceed this length, so
  #       no clamp is needed here. At most four classes are required, one
  #       character each, and variables.tf floors this input at twelve; four can
  #       never exceed twelve. That invariant is what makes the plain expression
  #       safe, and it lives in variables.tf rather than here.
  length = var.password_minimum_length

  # WHY : Assumption: EVERY ONE OF THESE EIGHT ARGUMENTS IS DERIVED FROM THE SAME
  #       INPUT THE POOL'S OWN POLICY USES, which is the point of the
  #       duplication and not an accident. The policy states what a password must
  #       contain; the generator states what this password does contain. Deriving
  #       both from one input makes the pair consistent by construction, whereas
  #       hard-coding either side would let a caller who disables a class still
  #       receive a value containing it, or -- far worse -- enable a class the
  #       generator does not honour and get a value the pool then refuses at user
  #       creation.
  #       Assumption: the enabling flag and the minimum are separate arguments in
  #       this provider, so both are needed. Setting a minimum of one while
  #       leaving the class disabled would be contradictory, so each minimum is
  #       gated on its own flag.
  lower     = var.password_require_lowercase
  min_lower = var.password_require_lowercase ? 1 : 0

  upper     = var.password_require_uppercase
  min_upper = var.password_require_uppercase ? 1 : 0

  numeric     = var.password_require_numbers
  min_numeric = var.password_require_numbers ? 1 : 0

  special     = var.password_require_symbols
  min_special = var.password_require_symbols ? 1 : 0

  # WHY : Assumption: the symbol set is restricted deliberately, and the two
  #       reasons for each exclusion are recorded on
  #       local.password_special_charset -- Cognito accepts a narrower set than
  #       this provider offers by default, and the excluded characters are the
  #       ones that would be mangled by JSON escaping or by a shell. Leaving this
  #       at the default risks a value the pool rejects or an operator
  #       mis-transcribes.
  override_special = local.password_special_charset
}

# WHAT: one Secrets Manager entry per identity, holding that identity's
#       generated credential.
# WHY : Assumption: for_each keyed by user_id, never count, for the reason
#       recorded on local.seed_users_by_id -- with count, removing one entry from
#       the middle of the list re-indexes every later instance and Terraform
#       destroys and recreates secrets and users that did not change, rotating
#       credentials nobody touched. The same keying is used for all five
#       resources in this chain so that one identity's instances share one
#       address across the whole graph.
resource "aws_secretsmanager_secret" "seed_user" {
  # WHY : Assumption: the same check id the scanner emitted, and inapplicable
  #       here for a DIFFERENT reason than on the app client secret above, which
  #       is why the two are not one comment. This entry holds a ONE-TIME
  #       handover value. The pool issues it as a temporary password that must be
  #       changed at first sign-in, after which Cognito alone holds the user's
  #       real credential and this entry is deliberately stale. A rotation
  #       function writing a fresh random value here would not change any
  #       password -- it would only make the stored value diverge from the one
  #       that works, turning a correct record into a misleading one.
  #       Alternatives Considered: a rotation function that also calls the
  #       Cognito admin API to set the new value as the user's password.
  #       Rejected: it would overwrite whatever password the user chose at first
  #       sign-in, so a control intended to limit credential lifetime would
  #       instead reset real users on a timer. The lifetime of the value this
  #       entry does hold is bounded by var.temporary_password_validity_days
  #       instead, which is the control that actually fits it.
  #checkov:skip=CKV2_AWS_57:Holds a one-time temporary handover credential that Cognito forces the user to change at first sign-in; rotating this entry would not change any password, only make the stored value diverge from the real one. Lifetime is bounded by temporary_password_validity_days instead.
  for_each = local.seed_users_by_id

  # WHY : Assumption: the user id is carried into the name VERBATIM, in the case
  #       the baseline record holds it in, so that the secret name matches the
  #       eight-character SEC-USR-ID at app/cpy/CSUSR01Y.cpy L18 exactly and an
  #       operator can grep for a secret using the id they read out of
  #       app/jcl/DUSRSECJ.jcl. It is deliberately not lower-cased for
  #       tidiness -- that would break the correspondence for no gain, and
  #       Secrets Manager accepts upper-case names.
  name = "${local.secret_name_prefix}/seed-user/${each.value.user_id}"

  # WHY : Assumption: the description identifies WHICH identity the entry belongs
  #       to and carries no part of the credential. A description is readable by
  #       anyone permitted to list secrets, without permission to read a value,
  #       so a fragment of a password here would leak past the very boundary this
  #       resource exists to draw. The user id and the names are not
  #       confidential -- they are published in the reference baseline -- and the
  #       password is not mentioned in any form.
  description = "Generated initial credential for CardDemo seed identity ${each.value.user_id} (${each.value.given_name} ${each.value.family_name}) in the ${var.environment} Cognito user pool. Temporary: must be changed at first sign-in."

  # WHY : Refactoring Rationale: the same customer-managed-key reasoning as the
  #       app client secret above, and it applies with more force here because
  #       this is the credential that replaces SEC-USR-PWD directly. The store
  #       being replaced had no encryption, no journalling and no recovery --
  #       JOURNAL(NO) at app/csd/CARDDEMO.CSD L94 and RECOVERY(NONE) at L96 --
  #       and its seed values were committed in the clear. A customer-managed key
  #       gives an auditable, revocable key policy that the AWS-managed default
  #       does not, so possession of the secret is not sufficient without the
  #       key's permission.
  kms_key_id = var.secrets_kms_key_arn

  # WHY : Trade-off: the recovery-window reasoning is recorded on the app client
  #       secret above and applies identically. It matters more here only in
  #       degree: there are as many of these entries as there are seed
  #       identities, so a reserved name blocks a re-apply once per identity
  #       rather than once.
  recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumption: the same pass-through as the two resources above, and the
  #       reasoning on the user pool's tags argument applies unchanged. Worth
  #       one line here because a bare pass-through is exactly what an
  #       accidentally-forgotten merge would also look like.
  tags = var.tags
}

# WHAT: the version carrying each identity's username and generated credential.
# WHY : Alternatives Considered: storing the password as a bare string. Rejected
#       because a bare value does not say whose it is, so an operator or a future
#       rotation function would need a second call to learn the username --
#       and the conventional Secrets Manager shape, which the console's own
#       credential view and the managed rotation contract both expect, is a JSON
#       object keyed username and password. Carrying both together makes the
#       entry self-describing and rotatable without a lookup.
#       Alternatives Considered: the provider's write-only secret_string_wo
#       argument, which would keep this copy of the value out of state. Rejected
#       for a concrete reason rather than a stylistic one: a write-only value
#       propagates a change only when its companion version number is bumped by
#       hand, so a regenerated password would silently fail to reach the secret
#       and the stored value would diverge from the one Cognito holds. The gain
#       was marginal in any case -- random_password persists the same value in
#       state regardless -- so losing update propagation was the larger cost.
resource "aws_secretsmanager_secret_version" "seed_user" {
  for_each = local.seed_users_by_id

  secret_id = aws_secretsmanager_secret.seed_user[each.key].id

  # WHY : Assumption: the temporary nature of the credential is a property of the
  #       Cognito user, not of this entry, so it is not encoded as a field here.
  #       It is stated in the secret's description instead, where an operator
  #       reads it before retrieving the value, and it is ENFORCED by the pool's
  #       temporary_password_validity_days -- a field in this document would be
  #       advisory and could drift from the setting that actually governs.
  secret_string = jsonencode({
    username = each.value.user_id
    password = random_password.seed_user[each.key].result
  })
}

# WHAT: the pool user for each requested identity.
# WHY : Assumption: the username is the eight-character SEC-USR-ID from
#       app/cpy/CSUSR01Y.cpy L18, unchanged, because that id is the join key
#       across the whole migrated system: it is the Cognito username here, the
#       primary key of auth.users in services/auth-service, and the key the ETL
#       loads the USRSEC dataset under. variables.tf validates the width at
#       exactly eight for that reason. The pool declares no username_attributes
#       and no alias_attributes, so this literal id is the sign-on identifier
#       rather than an email or a phone number standing in for one.
resource "aws_cognito_user" "seed_user" {
  for_each = local.seed_users_by_id

  user_pool_id = aws_cognito_user_pool.this.id
  username     = each.value.user_id

  # WHY : Assumption: stated explicitly rather than relying on the default,
  #       because a disabled seed identity would present as a correct credential
  #       that is rejected at sign-on -- a failure mode that looks like a
  #       password problem and is not. The baseline had no enabled flag on the
  #       record at app/cpy/CSUSR01Y.cpy L17-L23: a user existed or it did not,
  #       so enabled is the faithful mapping of "the record is present".
  enabled = true

  # WHY : Assumption: three attributes, mapped field for field from
  #       app/cpy/CSUSR01Y.cpy -- L19 SEC-USR-FNAME to given_name, L20
  #       SEC-USR-LNAME to family_name, L22 SEC-USR-TYPE to custom:user_type.
  #       The custom: prefix appears here and not in the pool's schema
  #       declaration because Cognito adds it itself; the two spellings are one
  #       attribute.
  #       Assumption: NO EMAIL AND NO PHONE ATTRIBUTE IS SET, because the source
  #       record has neither. That is not an omission to be filled in later; it
  #       is why account recovery on this pool is admin_only and why message
  #       delivery is suppressed below.
  #       Assumption: the type value is passed through as the copybook's single
  #       character rather than expanded to a word, so a reader can compare it
  #       directly against SEC-USR-TYPE and against the group mapping. It is
  #       recorded for TRACEABILITY only -- group membership below is what grants
  #       authority.
  attributes = {
    given_name         = each.value.given_name
    family_name        = each.value.family_name
    "custom:user_type" = each.value.user_type
  }

  # WHY : Trade-off: TEMPORARY rather than permanent, and the provider offers
  #       both arguments so the choice is real. A temporary password puts the
  #       user in a force-change state, so the generated value cannot become a
  #       standing credential no matter how long it sits in the secret store or
  #       in state -- which is the mitigation the note on random_password relies
  #       on. The cost is a mandatory password change on first sign-in, and that
  #       the value lapses after var.temporary_password_validity_days and then
  #       needs an operator-initiated reset.
  #       Alternatives Considered: the permanent-password argument, which would
  #       let a seed identity sign in indefinitely with the generated value.
  #       Rejected because it would turn a bootstrap credential into a
  #       long-lived one and would recreate, in a managed store, exactly the
  #       durable shared credential that app/jcl/DUSRSECJ.jcl L35-L44 committed
  #       in the clear.
  temporary_password = random_password.seed_user[each.key].result

  # WHY : Assumption: suppression is required rather than tidy. Cognito's default
  #       on admin-created users is to DELIVER an invitation, and its default
  #       medium is SMS; these identities carry neither an email address nor a
  #       phone number, as the attribute map above shows, so a delivery attempt
  #       has nowhere to go. Suppressing it makes provisioning succeed
  #       deterministically instead of depending on how the service handles an
  #       undeliverable invitation.
  #       Alternatives Considered: setting desired_delivery_mediums instead of
  #       suppressing. Rejected -- it selects BETWEEN channels and cannot express
  #       "no channel exists", so it would not remove the delivery attempt. It is
  #       deliberately left unset for that reason: with delivery suppressed there
  #       is no medium to choose.
  #       Trade-off: nobody is notified that the identity was created, so the
  #       credential reaches its owner only when an operator retrieves it from
  #       the secret store. Accepted, and appropriate: an out-of-band handover is
  #       the correct path for a bootstrap credential, and it is the only path
  #       available to identities with no contact attribute.
  message_action = "SUPPRESS"
}

# WHAT: each identity's membership of the group its SEC-USR-TYPE selects.
# WHY : Assumption: the mapping is 'A' to the administrator group and 'U' to the
#       user group, from the condition names at app/cpy/COCOM01Y.cpy L27-L28, and
#       it is looked up through local.group_name_by_user_type so that the lookup
#       also carries the dependency on the group resources -- see the note on
#       that local for why a plain string would not.
#       Assumption: NO DEFAULT ARM AND NO FALLBACK GROUP, and none should be
#       invented. variables.tf already rejects any user_type outside 'A' and 'U'
#       during plan, so an out-of-domain value never reaches this lookup; adding
#       a fallback would convert a plan-time rejection into a silent assignment
#       to the wrong group, which is strictly worse than failing.
#       Assumption: membership is a separate resource from the user rather than
#       an attribute of it, so a role change is an in-place membership change and
#       does not touch the identity or its credential.
resource "aws_cognito_user_in_group" "seed_user" {
  for_each = local.seed_users_by_id

  user_pool_id = aws_cognito_user_pool.this.id
  username     = aws_cognito_user.seed_user[each.key].username
  group_name   = local.group_name_by_user_type[each.value.user_type]
}

