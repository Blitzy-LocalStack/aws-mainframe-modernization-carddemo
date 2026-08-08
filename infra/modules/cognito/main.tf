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
#                           app_client_secret_rotation_revision
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
#     - aws_cloudformation_stack.app_client.outputs["ClientId"] -- the app client identifier, which
#       is the JWT authorizer's audience and the auth service's client id.
#     - aws_cognito_user_group.admin.name and .user.name -- the two strings that
#       appear in a token's cognito:groups claim and that common-lib's
#       JwtRoleConverter turns into Spring Security authorities.
#     - aws_secretsmanager_secret.app_client.arn and .name, and the same two
#       attributes of aws_secretsmanager_secret.seed_user -- REFERENCES to
#       credentials, never credential values.
#   NO CREDENTIAL IS RETURNED, AND THIS FILE DECLARES NO output BLOCK AT ALL.
#   Generated credential values exist only inside the apply-time bootstrap
#   processes that send them directly to Cognito and Secrets Manager.
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
#       bootstrap script receives the same four class flags the pool uses.
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
#   - Assumptions: RACF has no cloud analogue and is not ported. Its role is
#     filled by least-privilege IAM task roles plus the pool groups created
#     here, and that substitution is a documented MAPPING rather than a port.
#     Only the group half belongs to this module; the IAM half is
#     infra/modules/ecs-service's, and this module creates no IAM role at all.
#   - Assumptions: every resource type, argument name, nested block name and
#     accepted value in this file was read from the pinned provider
#     (hashicorp/aws ~> 6.56, resolved 6.57.1) by dumping its schema, not from
#     documentation prose or recollection. That mattered: deletion_protection is
#     a string rather than a bool, threat protection is still the
#     advanced_security_mode argument of the user_pool_add_ons block in this
#     release with no deprecation marker and no equivalent under any other name,
#     the schema block's length constraints are strings rather than numbers, and
#     aws_cognito_resource_server names its scope fields scope_name and
#     scope_description rather than name and description.
#   - Trade-offs: this is a child module and declares no provider, no backend and
#     no terraform block -- those live in versions.tf beside it and in the
#     calling root. It also calls no sibling module: the Secrets Manager key
#     arrives as var.secrets_kms_key_arn from infra/modules/kms via the root
#     that instantiates both, and is neither looked up by alias nor rebuilt from
#     an account id. The cost is that this directory cannot be applied on its
#     own; the gain is that key ownership stays in the one module responsible
#     for it and that region, credentials and default tags cannot disagree with
#     the root's provider.
# =============================================================================

# WHY : Assumptions: composition is centralised here rather than repeated at each
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
  # WHY : Assumptions: the environment is part of the name rather than only a
  #       tag, because dev and prod are instantiated into the same AWS account
  #       in this design and Cognito pool names are not namespaced by anything
  #       else. A tag would distinguish them in a cost report and would still
  #       let two pools share a name in the console.
  user_pool_name = "${var.name_prefix}-${var.environment}-users"

  # WHY : Assumptions: THESE TWO STRINGS ARE A CROSS-LANGUAGE CONTRACT, NOT A
  #       NAMING PREFERENCE. They are what Cognito puts in a token's
  #       cognito:groups claim, and common-lib's JwtRoleConverter maps these
  #       exact values to Spring Security authorities. They are fixed by the
  #       AAP identity contract rather than composed from var.name_prefix, so a
  #       caller can rename environment resources without silently renaming the
  #       authorities every service recognises. The converter constructor also
  #       checks the two configured outputs during service startup, turning any
  #       cross-layer drift into a startup failure rather than a deployment in
  #       which every protected request is denied.
  admin_group_name = "carddemo-admin"
  user_group_name  = "carddemo-user"

  # WHY : Assumptions: the domain of SEC-USR-TYPE is CLOSED AT TWO VALUES by its
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
  #       Assumptions: the key is unique. variables.tf validates that no user_id
  #       repeats, which is what makes this projection lossless -- without that
  #       check a duplicate would silently collapse two entries into one.
  seed_users_by_id = { for user in var.seed_users : user.user_id => user }

  # WHY : Assumptions: a Secrets Manager name may contain forward slashes, so the
  #       path form groups every credential this module owns under one prefix
  #       that an operator can list with a single call. The environment is in
  #       the prefix rather than only in a tag for the same reason as the pool
  #       name: two environments share an account here.
  secret_name_prefix = "${var.name_prefix}-${var.environment}/cognito"

  # WHY : Trade-offs: OAuth is enabled only when the caller actually supplies a
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

  # WHY : Assumptions: Cognito accepts a narrower symbol set in a password than
  #       random_password offers by default, so the set is stated rather than
  #       left to the default and every character here was checked against the
  #       symbols Cognito documents as acceptable.
  #       Trade-offs: two groups of characters are excluded deliberately. The
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

# WHY : Assumptions: the pool is a singleton in this module rather than a
#       for_each over some list of pools, because the baseline had exactly one
#       identity store -- one DEFINE FILE(USRSEC) at app/csd/CARDDEMO.CSD L88 --
#       and one sign-on transaction reading it. A second pool would have no
#       counterpart to migrate and would split the cognito:groups claim's
#       meaning across two issuers.
resource "aws_cognito_user_pool" "this" {
  name = local.user_pool_name

  # WHY : Assumptions: THIS IS A COUPLING, NOT A SIZING CHOICE, AND IT IS DERIVED
  #       RATHER THAN ACCEPTED AS AN INPUT FOR THAT REASON. Cognito's threat
  #       protection is a feature of the PLUS tier, and a pool left on the
  #       default tier rejects a user_pool_add_ons block naming AUDIT or
  #       ENFORCED -- at apply, after the pool has begun to be created.
  #       Deriving the tier from var.advanced_security_mode makes the two agree
  #       by construction instead of leaving a second input that a root must
  #       remember to raise in step with the first.
  #       Trade-offs: PLUS costs more per monthly active user than the default
  #       tier, and cost is an explicit tie-breaker in this migration. That
  #       price buys compromised-credential detection and adaptive risk scoring,
  #       against a baseline whose sign-on transaction ran with RESSEC(NO) and
  #       CMDSEC(NO) at app/csd/CARDDEMO.CSD L385 and had nothing comparable at
  #       all. A root unwilling to pay it sets advanced_security_mode to OFF,
  #       and this expression drops the pool back to the cheaper tier in the
  #       same step rather than leaving it stranded on PLUS with the feature
  #       switched off.
  user_pool_tier = var.advanced_security_mode == "OFF" ? "ESSENTIALS" : "PLUS"

  # WHY : Trade-offs: a string, and the two accepted values are ACTIVE and
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
  #       Assumptions: the four class requirements are one decision rather than
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

    # WHY : Trade-offs: each seed credential is temporary even though it no
    #       longer enters Terraform state. A short window bounds how long an
    #       unretrieved handover value remains useful. The cost is named
    #       rather than hidden: apply on one day and sign in for the first time
    #       several days later, and the credential has lapsed and needs an
    #       operator-initiated reset. That is preferable to leaving bootstrap
    #       credentials valid indefinitely, which is what the alternative
    #       amounts to.
    temporary_password_validity_days = var.temporary_password_validity_days
  }

  # WHY : Trade-offs: OPTIONAL in dev, and variables.tf structurally requires ON
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

  # WHY : Assumptions: this block IS the mechanism the setting above requires.
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

  # WHY : Assumptions: the attribute name was verified against the pinned
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
  #       Assumptions: closing sign-up does not close user creation -- the admin
  #       API still creates users, which is how the seed identities below and
  #       auth-service's own add-user endpoint work.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  # WHY : Assumptions: admin_only is chosen because it is what the baseline
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
  #       Assumptions: a single mechanism at priority 1. admin_only is not
  #       combinable with the others, and the provider accepts at most two
  #       recovery_mechanism entries in any case.
  account_recovery_setting {
    recovery_mechanism {
      name     = "admin_only"
      priority = 1
    }
  }

  # WHY : Trade-offs: case-INsensitive, and this is a deliberate divergence from
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
  #       Assumptions: this setting is IMMUTABLE once the pool exists. Changing
  #       it later forces the pool to be replaced and takes every user in it, so
  #       it is a decision made once at creation and not tuned afterwards.
  username_configuration {
    case_sensitive = false
  }

  # WHY : Assumptions: exactly ONE attribute is declared, and the three absences
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
  #       Assumptions: schema is IMMUTABLE. Adding or changing an entry after the
  #       pool exists forces the pool to be replaced, taking every user with it,
  #       so the set below is chosen once and the provider's fifty-attribute
  #       ceiling is nowhere near reached.
  schema {
    # WHY : Assumptions: the name carries no prefix here. Cognito prefixes a
    #       declared custom attribute itself, so this entry is what produces the
    #       attribute named "custom:user_type" that aws_cognito_user sets below
    #       -- the two spellings are the same attribute, not a mismatch.
    name                = "user_type"
    attribute_data_type = "String"

    # WHY : Assumptions: stated as false rather than omitted, because the
    #       alternative is not "no setting" but a genuinely different attribute.
    #       A developer-only attribute is invisible to the app client and cannot
    #       be read from a token at all, which would make this value unreadable
    #       by the very service that records the lineage it exists for. Writing
    #       the choice out means a later reader sees that visibility was decided
    #       rather than inherited from a default.
    developer_only_attribute = false

    # WHY : Assumptions: mutable because a user's role genuinely changes -- the
    #       baseline rewrote SEC-USR-TYPE in place through the COUSR02C update
    #       screen -- so an administrator must be able to correct it without
    #       deleting and recreating the identity. That does not make it
    #       client-writable: the app client's write_attributes below
    #       deliberately excludes it, so the ability to change a role stays with
    #       the admin API.
    mutable = true

    # WHY : Assumptions: not required, because Cognito does not permit a custom
    #       attribute to be required. That is why AUTHORIZATION DOES NOT DEPEND
    #       ON THIS ATTRIBUTE -- group membership is what grants authority, and
    #       this attribute exists to trace an identity back to the SEC-USR-TYPE
    #       value it came from. Nothing breaks if it is absent on a user
    #       created outside this module; that user simply has no recorded
    #       lineage to the baseline record.
    required = false

    # WHY : Assumptions: the width is the copybook's, not a round number. It is
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

  # WHY : Assumptions: no merge() call appears here on purpose, and the absence
  #       would otherwise look like a missing merge. The calling root's provider
  #       applies default_tags to every resource in the graph and merges them
  #       with a resource's own tags itself, so passing the input straight
  #       through is additive already; wrapping it in merge() here would only
  #       invite someone to add the environment or the module name a second time
  #       and let a module argument contradict provider configuration the root
  #       owns.
  tags = var.tags
}

# WHY : Assumptions: this client is CONFIDENTIAL and SERVER-SIDE, which is the
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
resource "aws_cloudformation_stack" "app_client" {
  name = "${var.name_prefix}-${var.environment}-app-client"

  # WHY : Refactoring Rationale: CloudFormation creates the confidential client
  #       because it treats the generated client secret as a write-only service
  #       value. The native Terraform resource returns that value as a computed
  #       attribute and therefore retains it in every historical state version,
  #       even when no output publishes it. This stack outputs only ClientId; the
  #       rotation bridge below reads the secret directly from Cognito and writes
  #       it into Secrets Manager without passing it through Terraform.
  template_body = jsonencode({
    AWSTemplateFormatVersion = "2010-09-09"
    Resources = {
      AppClient = {
        Type = "AWS::Cognito::UserPoolClient"
        Properties = merge(
          {
            ClientName                      = "${var.name_prefix}-${var.environment}-app-client"
            UserPoolId                      = aws_cognito_user_pool.this.id
            GenerateSecret                  = true
            ExplicitAuthFlows               = var.explicit_auth_flows
            SupportedIdentityProviders      = ["COGNITO"]
            AllowedOAuthFlowsUserPoolClient = local.oauth_enabled
            AccessTokenValidity             = var.access_token_validity_minutes
            IdTokenValidity                 = var.id_token_validity_minutes
            RefreshTokenValidity            = var.refresh_token_validity_days
            TokenValidityUnits = {
              AccessToken  = "minutes"
              IdToken      = "minutes"
              RefreshToken = "days"
            }
            EnableTokenRevocation = true
            RefreshTokenRotation = {
              Feature                 = "ENABLED"
              RetryGracePeriodSeconds = 0
            }
            # WHY : Trade-offs: this is the one place where a security correction and
            #       the migration's verbatim-message requirement genuinely pull
            #       against each other, so both halves are stated. The baseline
            #       answers the two failure modes differently on purpose:
            #       app/cbl/COSGN00C.cbl L242-L243 returns 'Wrong Password. Try
            #       again ...' when the keyed read succeeded but the comparison
            #       failed, and L249 returns 'User not found. Try again ...' when the
            #       read came back RESP 13. The difference between those two replies
            #       tells an unauthenticated caller which user ids exist, which is
            #       user enumeration. ENABLED makes the provider answer both cases
            #       identically, and auth-service returns the baseline string `Wrong
            #       Password. Try again ...` for both; `User not found. Try again
            #       ...` stays catalogued for traceability only, and unrelated
            #       provider failures keep `Unable to verify the User ...`. The lost
            #       discrimination between the two credential failures is a
            #       behavioural divergence and is registered as
            #       D-SIGNON-EXISTENCE-UNIFORM in section 7.4 of
            #       docs/architecture/cobol-to-service-traceability.md rather than
            #       passed off as parity. The identifier is cited rather than the
            #       document alone, because a claim of registration that names
            #       nothing cannot be checked by search.
            #       Alternatives Considered: exposing this as a module input so a
            #       root could select the baseline's distinguishable responses.
            #       Rejected because a reachable LEGACY value ports the defect: the
            #       enumeration channel would be one tfvars line away, and no root
            #       has a reason to want it. The value is fixed here so the
            #       invariant cannot be overridden from outside the module.
            PreventUserExistenceErrors = "ENABLED"
            ReadAttributes             = ["given_name", "family_name", "custom:user_type"]
            WriteAttributes            = ["given_name", "family_name"]
          },
          local.oauth_enabled ? {
            AllowedOAuthFlows  = ["code"]
            AllowedOAuthScopes = ["openid", "profile"]
            CallbackURLs       = var.callback_urls
            LogoutURLs         = var.logout_urls
          } : {},
        )
      }
    }
    Outputs = {
      ClientId = {
        Value = { Ref = "AppClient" }
      }
    }
  })

  tags = var.tags
}

# WHY : Assumptions: this resource is what makes the confidential client above
#       usable, and without it the design would not work. CloudFormation asks
#       Cognito to generate the value without returning it to Terraform, and
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
#       Manager is the value store the API-driven rotation bridge below
#       updates atomically for auth-service.
resource "aws_secretsmanager_secret" "app_client" {
  # WHY : Assumptions: Cognito now permits more than one secret on one app
  #       client through AddUserPoolClientSecret, so rotation no longer changes
  #       the client id or the JWT audience. Provider 6.57.1 does not model that
  #       API as a resource, and Checkov's graph recognises only an
  #       aws_secretsmanager_secret_rotation attachment. The terraform_data
  #       bridge below invokes Add/List/DeleteUserPoolClientSecret directly,
  #       writes the new value here and deliberately leaves the previously
  #       current secret active for a consumer rollout. This suppression records
  #       why the scanner cannot see the implemented mechanism; it does not
  #       assert that rotation is impossible.
  #checkov:skip=CKV2_AWS_57:Cognito app-client rotation is implemented below through the service Add/List/DeleteUserPoolClientSecret APIs; provider 6.57.1 has no native resource for that mechanism, so this graph check cannot recognize it.
  name = "${local.secret_name_prefix}/app-client"

  # WHY : Assumptions: the description names what the entry is FOR and never any
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
  #       Assumptions: the key is created by infra/modules/kms and passed in by
  #       the calling root, because this module calls no sibling module and
  #       resolves no data source for it. variables.tf makes the input required
  #       with no default precisely so that a fall-back to the AWS-managed key
  #       cannot happen unnoticed.
  kms_key_id = var.secrets_kms_key_arn

  # WHY : Trade-offs: zero in dev, a real window in prod, and the trap is
  #       specific enough to be worth naming. Any NON-ZERO window leaves a
  #       destroyed secret's NAME reserved for its duration, so a destroy
  #       followed by a re-apply collides on a name that no longer appears to
  #       exist anywhere -- which breaks both the teardown criterion this package
  #       is accepted against and ordinary iteration. Zero deletes immediately
  #       and makes destroy-then-apply work. prod accepts the opposite trade,
  #       keeping recoverability and giving up the ability to re-apply at once.
  #       Assumptions: the accepted domain is DISCONTINUOUS -- zero, or seven
  #       through thirty -- which is why variables.tf validates it as a
  #       disjunction rather than a range; values one through six are refused.
  recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumptions: passed through with no merge() call, for the reason
  #       recorded on the user pool's own tags argument -- the calling root's
  #       provider merges its default_tags with a resource's tags itself, so
  #       wrapping this would risk a module argument restating, and then
  #       contradicting, provider configuration the root owns.
  tags = var.tags
}

# WHY : Refactoring Rationale: app-client secret rotation is performed against
#       the existing client id, not by replacing the client. Cognito's
#       AddUserPoolClientSecret API allows two secrets to overlap; the bridge
#       removes only a stale predecessor, adds a new secret, updates the managed
#       secret, and retains the previously current value. Auth-service can
#       therefore reload the new value while both values authenticate, and the
#       next rotation removes the older one.
#       Alternatives Considered: replacing the whole app client to obtain
#       a new provider-generated secret. Rejected because replacement changes
#       the client id that the JWT authorizer, token validators and runtime
#       configuration use as the audience, turning credential rotation into a
#       coordinated identity-contract release. Also considered waiting for a
#       future provider argument; rejected because provider 6.57.1 exposes no
#       app-client-secret collection or rotation resource while the service API
#       already provides the required overlap.
#       Assumptions: the apply host has the AWS CLI and Python 3 available and
#       uses the same short-lived AWS identity as Terraform. docs/runbooks/deploy.md
#       names the required IAM actions and the revision-bump procedure.
resource "terraform_data" "app_client_secret_rotation" {
  triggers_replace = [
    var.app_client_secret_rotation_revision,
    aws_cognito_user_pool.this.id,
    aws_cloudformation_stack.app_client.outputs["ClientId"],
    aws_secretsmanager_secret.app_client.id,
  ]

  provisioner "local-exec" {
    environment = {
      CARDDEMO_USER_POOL_ID      = aws_cognito_user_pool.this.id
      CARDDEMO_APP_CLIENT_ID     = aws_cloudformation_stack.app_client.outputs["ClientId"]
      CARDDEMO_APP_CLIENT_SECRET = aws_secretsmanager_secret.app_client.arn
    }

    # WHY : Assumptions: no secret is placed in the command text, process
    #       arguments or Terraform output. Service responses containing secret
    #       values stay in owner-only files under a private temporary directory,
    #       and the directory is removed on success, failure or interruption.
    #       The script emits no successful
    #       command response, so an apply log contains identifiers and errors
    #       only.
    command = <<-EOT
      set -eu
      umask 077
      export AWS_PAGER=""

      REGION="$${CARDDEMO_USER_POOL_ID%%_*}"
      TEMPORARY_DIRECTORY="$(mktemp -d)"
      trap 'rm -rf "$TEMPORARY_DIRECTORY"' EXIT HUP INT TERM
      DESCRIPTORS_FILE="$TEMPORARY_DIRECTORY/descriptors.json"
      CURRENT_FILE="$TEMPORARY_DIRECTORY/current.json"
      ROTATED_FILE="$TEMPORARY_DIRECTORY/rotated.json"
      MANAGED_FILE="$TEMPORARY_DIRECTORY/managed.json"

      aws cognito-idp list-user-pool-client-secrets \
        --region "$REGION" \
        --user-pool-id "$CARDDEMO_USER_POOL_ID" \
        --client-id "$CARDDEMO_APP_CLIENT_ID" \
        --output json > "$DESCRIPTORS_FILE"

      SECRET_COUNT="$(python3 -c 'import json, sys; print(len(json.load(open(sys.argv[1], encoding="utf-8")).get("ClientSecrets", [])))' "$DESCRIPTORS_FILE")"
      if [ "$SECRET_COUNT" -lt 1 ] || [ "$SECRET_COUNT" -gt 2 ]; then
        echo "Cognito app client must have one or two active secrets before rotation." >&2
        exit 1
      fi

      INITIALISE_SECRET="true"
      if aws secretsmanager get-secret-value \
        --region "$REGION" \
        --secret-id "$CARDDEMO_APP_CLIENT_SECRET" \
        --query SecretString \
        --output text > "$CURRENT_FILE" 2>/dev/null; then
        STORED_CLIENT_ID="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("client_id", ""))' "$CURRENT_FILE")"
        if [ "$STORED_CLIENT_ID" = "$CARDDEMO_APP_CLIENT_ID" ]; then
          INITIALISE_SECRET="false"
        fi
      fi

      if [ "$INITIALISE_SECRET" = "false" ]; then
        CURRENT_SECRET_ID="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1], encoding="utf-8")).get("client_secret_id", ""))' "$CURRENT_FILE")"
        if [ -z "$CURRENT_SECRET_ID" ]; then
          CURRENT_SECRET_ID="$(python3 -c 'import hmac, json, sys; current=json.load(open(sys.argv[1], encoding="utf-8"))["client_secret"]; descriptors=json.load(open(sys.argv[2], encoding="utf-8")).get("ClientSecrets", []); matches=[item.get("ClientSecretId", "") for item in descriptors if hmac.compare_digest(item.get("ClientSecretValue", ""), current)]; print(matches[0] if len(matches) == 1 else "")' "$CURRENT_FILE" "$DESCRIPTORS_FILE")"
        fi
        if [ -z "$CURRENT_SECRET_ID" ]; then
          echo "The managed secret does not identify exactly one active Cognito client secret." >&2
          exit 1
        fi

        if [ "$SECRET_COUNT" -eq 2 ]; then
          STALE_SECRET_ID="$(python3 -c 'import json, sys; current=sys.argv[1]; stale=[item.get("ClientSecretId", "") for item in json.load(open(sys.argv[2], encoding="utf-8")).get("ClientSecrets", []) if item.get("ClientSecretId") != current]; print(stale[0] if len(stale) == 1 else "")' "$CURRENT_SECRET_ID" "$DESCRIPTORS_FILE")"
          if [ -z "$STALE_SECRET_ID" ]; then
            echo "Unable to identify exactly one stale Cognito client secret." >&2
            exit 1
          fi
          aws cognito-idp delete-user-pool-client-secret \
            --region "$REGION" \
            --user-pool-id "$CARDDEMO_USER_POOL_ID" \
            --client-id "$CARDDEMO_APP_CLIENT_ID" \
            --client-secret-id "$STALE_SECRET_ID" \
            >/dev/null
        fi

        aws cognito-idp add-user-pool-client-secret \
          --region "$REGION" \
          --user-pool-id "$CARDDEMO_USER_POOL_ID" \
          --client-id "$CARDDEMO_APP_CLIENT_ID" \
          --output json > "$ROTATED_FILE"
        DESCRIPTOR_KIND="rotated"
        DESCRIPTOR_FILE="$ROTATED_FILE"
      else
        if [ "$SECRET_COUNT" -ne 1 ]; then
          echo "Cognito app-client secret initialization requires exactly one active secret." >&2
          exit 1
        fi
        DESCRIPTOR_KIND="initial"
        DESCRIPTOR_FILE="$DESCRIPTORS_FILE"
      fi

      python3 -c 'import json, sys; source=json.load(open(sys.argv[1], encoding="utf-8")); descriptor=(source["ClientSecrets"][0] if sys.argv[2]=="initial" else source["ClientSecretDescriptor"]); secret_id=descriptor.get("ClientSecretId", ""); secret_value=descriptor.get("ClientSecretValue", ""); assert secret_id and secret_value, "Cognito returned an incomplete app-client secret"; json.dump({"client_id":sys.argv[3],"client_secret":secret_value,"client_secret_id":secret_id},open(sys.argv[4],"w",encoding="utf-8"),separators=(",", ":"))' "$DESCRIPTOR_FILE" "$DESCRIPTOR_KIND" "$CARDDEMO_APP_CLIENT_ID" "$MANAGED_FILE"
      aws secretsmanager put-secret-value \
        --region "$REGION" \
        --secret-id "$CARDDEMO_APP_CLIENT_SECRET" \
        --secret-string "file://$MANAGED_FILE" \
        >/dev/null
    EOT

    interpreter = ["/bin/sh", "-c"]
  }

  depends_on = [aws_cloudformation_stack.app_client]
}

# WHY : Assumptions: without a resource server the ONLY scope a token from this
#       pool can carry is Cognito's built-in one, so there is no vocabulary in
#       which a non-interactive caller could request a narrower grant and
#       nothing in which a future per-route authorization decision could be
#       expressed. Declaring the identifier and the scopes here creates that
#       vocabulary once, at the pool that owns it, instead of leaving each
#       consumer to invent one.
#       Trade-offs: these scopes are NOT what an interactive sign-on presents. A
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

  # WHY : Assumptions: Cognito PREFIXES this identifier onto each scope name to
  #       form the value that appears in a token, so the default
  #       "carddemo-api" with a scope "read" yields the token scope
  #       "carddemo-api/read". A bare name is used rather than the URL shape
  #       that OAuth convention favours, because a URL invites the reading that
  #       the string is an address something can be fetched from, which it is
  #       not.
  identifier = var.resource_server_identifier

  # WHY : Assumptions: the nested field names are scope_name and
  #       scope_description, NOT name and description, and both are required.
  #       That was read from the pinned provider's schema rather than inferred;
  #       the shorter spellings are the intuitive guess and the provider does
  #       not accept them.
  #       Assumptions: an empty scope list is legal, so a root that wants no
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

# WHY : Assumptions: TWO groups exist as two explicit resources rather than one
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

  # WHY : Assumptions: LOWER PRECEDENCE WINS IN COGNITO, which is the opposite of
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

# WHY : Assumptions: the reasoning for declaring exactly two explicit groups, for
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
#       Assumptions: count is used here rather than for_each, and this is the one
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
# WHY : Refactoring Rationale: this five-resource chain is the whole answer to
#       the defect at app/jcl/DUSRSECJ.jcl L35-L44, where the baseline's ten seed
#       identities are loaded from in-stream JCL data whose password column holds
#       ONE eight-character literal, byte-identical for all ten users, committed
#       to source control. Here each identity gets a DISTINCT password that is
#       generated during apply, is never authored into any file, and is issued as
#       a temporary value that must be changed on first sign-in. The baseline's
#       literal is deliberately not transcribed anywhere in this module, not even
#       as an illustration of what it replaces.
#       Assumptions: var.seed_users DEFAULTS TO EMPTY, so a root that says nothing
#       gets the pool, the client and both groups with no users at all, and every
#       resource in this section produces zero instances. That is the expected
#       posture for production; the baseline's ten identities remain readable in
#       the reference tree at the cited lines, and the ETL loads the same records
#       into auth.users, so creating them in a managed identity pool is a
#       deliberate act rather than something that happens by omission.
# -----------------------------------------------------------------------------

# WHY : Refactoring Rationale: a stable random handle separates the public
#       metadata of a Secrets Manager entry from the identity stored inside its
#       encrypted value. The previous secret name embedded SEC-USR-ID and the
#       description embedded both names, so ListSecrets and DescribeSecret
#       disclosed identity data to principals that had no GetSecretValue
#       permission. A 128-bit random id keeps names stable across plans without
#       deriving a guessable token from the low-entropy user id.
resource "random_id" "seed_user_secret" {
  for_each = local.seed_users_by_id

  byte_length = 16
}

# WHY : Assumptions: for_each keyed by user_id, never count, for the reason
#       recorded on local.seed_users_by_id -- with count, removing one entry from
#       the middle of the list re-indexes every later instance and Terraform
#       destroys and recreates secrets and users that did not change, rotating
#       credentials nobody touched. The same keying is used for all five
#       resources in this chain so that one identity's instances share one
#       address across the whole graph.
resource "aws_secretsmanager_secret" "seed_user" {
  # WHY : Assumptions: the same check id the scanner emitted, and inapplicable
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

  # WHY : Assumptions: the opaque handle is generated once and retained in state,
  #       so a plan can rename unrelated resources without changing the secret's
  #       address. The username remains inside secret_string, behind
  #       GetSecretValue and KMS permissions, where an operator who is allowed
  #       to retrieve the credential can still determine who it belongs to.
  name = "${local.secret_name_prefix}/seed-user/${random_id.seed_user_secret[each.key].hex}"

  # WHY : Assumptions: descriptions are readable through non-value metadata APIs,
  #       so this sentence carries purpose and lifecycle only. The identity is
  #       deliberately absent; including a user id or name here would undo the
  #       opaque name above for every principal allowed to list secrets.
  description = "Generated one-time initial credential for a CardDemo seed identity in the ${var.environment} Cognito user pool. Temporary: must be changed at first sign-in."

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

  # WHY : Trade-offs: the recovery-window reasoning is recorded on the app client
  #       secret above and applies identically. It matters more here only in
  #       degree: there are as many of these entries as there are seed
  #       identities, so a reserved name blocks a re-apply once per identity
  #       rather than once.
  recovery_window_in_days = var.secret_recovery_window_in_days

  # WHY : Assumptions: the same pass-through as the two resources above, and the
  #       reasoning on the user pool's tags argument applies unchanged. Worth
  #       one line here because a bare pass-through is exactly what an
  #       accidentally-forgotten merge would also look like.
  tags = var.tags
}

# WHY : Refactoring Rationale: the identity is a NATIVE aws_cognito_user. It was a
#       terraform_data whose triggers_replace listed given_name, family_name,
#       user_type and seed_user_credential_revision, and whose when = destroy
#       provisioner ran admin-delete-user. terraform_data has no notion of an
#       in-place update -- its only response to a changed trigger is destroy then
#       create -- so correcting a spelling in a surname, moving one person between
#       'A' and 'U', or bumping the rotation counter DELETED the Cognito identity
#       and made a new one. Everything Cognito owns and Terraform cannot recreate
#       went with it: a fresh sub (breaking the auth.users.cognito_sub join, which
#       V1__auth.sql declares NOT NULL UNIQUE), the password the person had chosen
#       since handover, their registered MFA factors and their remembered devices.
#       The identity provider treats every one of those changes as an in-place
#       attribute update -- custom:user_type is declared mutable = true in the pool
#       schema above for exactly this reason -- so the destroy was never anything
#       the platform asked for. This resource converges attributes through
#       AdminUpdateUserAttributes and deletes the user only when the resource
#       itself is destroyed, which is the only circumstance in which deletion is
#       what was asked for.
#       Assumptions: sub is computed here and is the join to auth.users. It is
#       published through the seed_user_subjects output so the ETL that loads the
#       USRSEC profile rows can satisfy that NOT NULL UNIQUE column; because sub
#       now survives an attribute change, a value the ETL has already written
#       stays correct instead of being invalidated by the next unrelated edit.
resource "aws_cognito_user" "seed_user" {
  for_each = local.seed_users_by_id

  user_pool_id = aws_cognito_user_pool.this.id

  # WHY : Assumptions: the username IS the eight-character SEC-USR-ID, not a
  #       derived or generated handle. variables.tf enforces the width, and the
  #       same value is the primary key of auth.users, so using it verbatim is
  #       what makes the two halves joinable at all. The pool sets
  #       case_sensitive = false above, so sign-on tolerates either case while
  #       the stored form stays exactly as the baseline record spells it.
  username = each.key

  # WHY : Assumptions: three attributes and no more. given_name and family_name
  #       are Cognito standard attributes carrying SEC-USR-FNAME and
  #       SEC-USR-LNAME; custom:user_type carries SEC-USR-TYPE and is the one
  #       schema entry this pool declares. There is deliberately NO password
  #       attribute of any kind, because SEC-USR-PWD X(08) at
  #       app/cpy/CSUSR01Y.cpy L21 is the field this migration refuses to carry
  #       forward -- Cognito owns the credential and nothing else stores it.
  #       Assumptions: attributes is optional and not computed in the pinned
  #       provider, so only the keys named here are tracked. Server-side
  #       attributes the pool maintains on its own therefore cannot present as
  #       drift, which is why the map does not need an ignore_changes.
  #       Assumptions: authorization does NOT read this attribute. Authority
  #       comes from group membership below, converted from cognito:groups by
  #       JwtRoleConverter. custom:user_type exists to keep the lineage back to
  #       SEC-USR-TYPE legible, so a change to it is a lineage correction rather
  #       than a privilege change -- one more reason it must not force a replace.
  attributes = {
    given_name         = each.value.given_name
    family_name        = each.value.family_name
    "custom:user_type" = each.value.user_type
  }

  # WHY : Trade-offs: SUPPRESS means Cognito generates an initial password and
  #       delivers it to nobody. The alternative is to set temporary_password
  #       here, and it is rejected: the argument is sensitive but it is still
  #       configuration, so the value would have to exist in a variable, in plan
  #       output and in state -- the three places this module's secret handling
  #       exists to keep credentials out of. The accepted cost is that the
  #       generated value is unknown and unusable, which is precisely why the
  #       credential resource below immediately replaces it with a value written
  #       to Secrets Manager. The pool also has no email or phone attribute
  #       configured, so there is no delivery medium for a message to use.
  message_action = "SUPPRESS"

  # WHY : Assumptions: stated rather than left to the provider default so that
  #       disabling a seed identity is a one-word edit that Cognito applies in
  #       place through AdminDisableUser, instead of an operator reaching for a
  #       delete. That is the same distinction this whole resource exists to
  #       draw: withdrawing access is not destroying the identity.
  enabled = true
}

# WHY : Refactoring Rationale: the credential is a separate resource from the
#       identity. The two were one terraform_data, and fusing them is what made
#       every profile edit a deletion. Splitting them puts each change on the
#       mechanism that can express it: an attribute change is an in-place update
#       of the resource above, and a credential change is a replacement of this
#       one, which destroys nothing an operator wants to keep.
#       Assumptions: the trigger set is exactly three values, and each is a real
#       reason the handover credential is no longer valid. seed_user_credential_revision
#       is the operator's deliberate rotation. The secret ARN changes only when
#       the entry has been replaced, and a replaced entry holds no value to hand
#       over. sub changes only when the identity is genuinely new, and a new
#       identity carries the undelivered password Cognito generated above.
#       The three profile fields are deliberately ABSENT: renaming a person or
#       correcting their role is no reason to invalidate their password, and
#       listing them here is exactly what the previous shape did.
#       Assumptions: referencing sub also orders this after the user exists, so
#       no depends_on is needed and admin-set-user-password cannot run first.
#       Assumptions: there is NO when = destroy provisioner, and none should be
#       added. Deleting the identity belongs to aws_cognito_user above, which
#       does it only on its own destroy; re-adding one here would rebuild the
#       destroy-on-replace path this split exists to remove. Nothing else needs
#       undoing either -- the secret is removed by its own resource.
resource "terraform_data" "seed_user_credential" {
  for_each = local.seed_users_by_id

  # WHY : Assumptions: kept although no provisioner reads self, because it is the
  #       only thing in this resource's state that names which identity the
  #       credential belongs to. The secret's name is deliberately opaque and the
  #       password is deliberately absent, so without this an operator reading
  #       state sees a rotation record it cannot attribute to anyone.
  input = {
    user_pool_id = aws_cognito_user_pool.this.id
    username     = aws_cognito_user.seed_user[each.key].username
  }

  triggers_replace = [
    var.seed_user_credential_revision,
    aws_secretsmanager_secret.seed_user[each.key].arn,
    aws_cognito_user.seed_user[each.key].sub,
  ]

  provisioner "local-exec" {
    # WHY : Assumptions: the identity's names and role are NOT passed. The script
    #       sets a password and writes a secret; it does not create a user and
    #       does not own an attribute, so handing it profile data would give it
    #       a second, competing authority over values aws_cognito_user already
    #       converges. The password-policy inputs are passed because the value it
    #       generates has to satisfy the pool policy declared above, and the
    #       policy lives here rather than being rediscovered by an API call.
    environment = {
      CARDDEMO_USER_POOL_ID      = aws_cognito_user_pool.this.id
      CARDDEMO_USER_ID           = aws_cognito_user.seed_user[each.key].username
      CARDDEMO_SEED_SECRET_ARN   = aws_secretsmanager_secret.seed_user[each.key].arn
      CARDDEMO_PASSWORD_LENGTH   = tostring(var.password_minimum_length)
      CARDDEMO_REQUIRE_LOWERCASE = tostring(var.password_require_lowercase)
      CARDDEMO_REQUIRE_UPPERCASE = tostring(var.password_require_uppercase)
      CARDDEMO_REQUIRE_NUMBERS   = tostring(var.password_require_numbers)
      CARDDEMO_REQUIRE_SYMBOLS   = tostring(var.password_require_symbols)
      CARDDEMO_PASSWORD_SYMBOLS  = local.password_special_charset
    }

    command     = "python3 ${path.module}/seed_user_bootstrap.py"
    interpreter = ["/bin/sh", "-c"]
  }
}

# WHY : Assumptions: the mapping is 'A' to the administrator group and 'U' to the
#       user group, from the condition names at app/cpy/COCOM01Y.cpy L27-L28, and
#       it is looked up through local.group_name_by_user_type so that the lookup
#       also carries the dependency on the group resources -- see the note on
#       that local for why a plain string would not.
#       Assumptions: NO DEFAULT ARM AND NO FALLBACK GROUP, and none should be
#       invented. variables.tf already rejects any user_type outside 'A' and 'U'
#       during plan, so an out-of-domain value never reaches this lookup; adding
#       a fallback would convert a plan-time rejection into a silent assignment
#       to the wrong group, which is strictly worse than failing.
#       Assumptions: membership is a separate resource from the user rather than
#       an attribute of it, so a role change is a replacement of THIS resource --
#       one DeleteGroupUser and one AddUserToGroup -- and does not touch the
#       identity or its credential. That is now true of the whole chain and was
#       not before: while the identity was a terraform_data keyed on user_type, a
#       role change destroyed the user underneath this membership, so the claim
#       this comment makes held for the membership alone and was false overall.
#       Assumptions: username is taken from aws_cognito_user rather than from
#       each.key, although the two values are identical. The reference is what
#       carries the dependency, so membership cannot be attempted before the
#       identity exists; a bare each.key compiled fine and needed a depends_on to
#       say the same thing less precisely, and that depends_on has been removed.
#       Trade-offs: the replacement is left as destroy-then-create, and
#       create_before_destroy is deliberately NOT set. Either ordering leaves a
#       brief window during a deliberate role change: the default gives a moment
#       in which the user belongs to no group and therefore holds no authority,
#       while create_before_destroy would give a moment in which they belong to
#       both and, on an 'A'-to-'U' change, still hold administrator authority.
#       Momentarily having too little authority is recoverable by retrying a
#       request; momentarily having too much is not recoverable at all if it is
#       used, so the default is the safer of the two and is kept on purpose.
resource "aws_cognito_user_in_group" "seed_user" {
  for_each = local.seed_users_by_id

  user_pool_id = aws_cognito_user_pool.this.id
  username     = aws_cognito_user.seed_user[each.key].username
  group_name   = local.group_name_by_user_type[each.value.user_type]
}
