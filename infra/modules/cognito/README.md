# `infra/modules/cognito/` — Identity provider for the migrated sign-on path

> **Purpose.** This module provisions the identity provider that replaces the
> mainframe sign-on path: a Cognito user pool standing in for the `USRSEC` VSAM
> file, one confidential app client for the auth service, a resource server and
> its scope vocabulary, the two groups `carddemo-admin` and `carddemo-user` that
> carry the baseline's two user types, an optional hosted-UI domain, and — when a
> caller asks for them — seed identities whose initial credentials are generated
> during apply and written to Secrets Manager rather than authored anywhere.
>
> **Source of truth.** `versions.tf`, `variables.tf`, `main.tf`, `outputs.tf` and
> `seed_user_bootstrap.py` in this directory are authoritative for what the module
> does. Where this document and those files disagree, **the files win** and this
> document is the defect. The behaviour they encode derives from the immutable
> baseline: [`app/cpy/CSUSR01Y.cpy`](../../../app/cpy/CSUSR01Y.cpy),
> [`app/cbl/COSGN00C.cbl`](../../../app/cbl/COSGN00C.cbl),
> [`app/cpy/COCOM01Y.cpy`](../../../app/cpy/COCOM01Y.cpy),
> [`app/csd/CARDDEMO.CSD`](../../../app/csd/CARDDEMO.CSD) and
> [`app/jcl/DUSRSECJ.jcl`](../../../app/jcl/DUSRSECJ.jcl), which are read as the
> specification and are never modified.

Read this README to find out what the module composes, which inputs it requires,
and why each non-obvious argument is set the way it is. HCL has no docstring
construct, so the documentation obligation for Terraform is met in two
halves: the file-header blocks, typed variables, output descriptions and adjacent
rationale inside the four `.tf` files are the mechanical half, linted by
[`infra/.tflint.hcl`](../../.tflint.hcl) and surfaced by
[`infra/.terraform-docs.yml`](../../.terraform-docs.yml); this document is the
**prose half**. The conventions it follows — the four rationale labels in their one
permitted written form, and the paired what-and-why comment idiom in every fenced command
block — are defined in the
[documentation standard](../../../docs/CODE_DOCUMENTATION_STANDARD.md).


## The one place this migration declines parity

Assumptions: everything else in this migration preserves observable behaviour. Identity is the
single documented exception, and it is this module that causes it, so the reasoning
belongs here rather than in a footnote.

Refactoring Rationale: the baseline stores a password in the user record and
compares it in the clear. The evidence below was read directly from the reference
tree, and every line citation is exact.

| Evidence | Location | What it shows |
|---|---|---|
| `05 SEC-USR-PWD PIC X(08).` | `app/cpy/CSUSR01Y.cpy` L21 | An eight-character password held in the clear inside the user record itself |
| `IF SEC-USR-PWD = WS-USER-PWD` | `app/cbl/COSGN00C.cbl` L223 | A direct comparison — unhashed, unsalted, no work factor, no lockout — inside `READ-USER-SEC-FILE` (L209–L257), reached by the keyed `EXEC CICS READ` at L211–L219 |
| Ten in-stream seed records | `app/jcl/DUSRSECJ.jcl` L35–L44 | The seed credentials are committed to this repository in the clear, and the password column holds **one shared eight-character literal, byte-identical for all ten users**. That literal is described here and deliberately never transcribed — reproducing it would plant a credential in the very tree this module exists to keep free of one |
| `JOURNAL(NO)`, `RECOVERY(NONE)` | `app/csd/CARDDEMO.CSD` L94, L96 | The identity store had no journalling, no recovery and no encryption at rest |
| `CONFDATA(NO)`, `RESSEC(NO)`, `CMDSEC(NO)` | `app/csd/CARDDEMO.CSD` L384–L385 on `TRANSACTION(CC00)` | On the one transaction that handles a password, confidential-data suppression was off and CICS resource and command security were both off |

The target does not carry that field forward at all. Cognito owns credential
handling, complexity enforcement, lockout and rotation; the pool's password policy
is deliberately stronger than the baseline's, and `variables.tf` refuses to let a
caller configure the baseline's own eight-character length back in. Downstream,
[`V1__auth.sql`](../../../services/auth-service/src/main/resources/db/migration/V1__auth.sql)
keeps `user_type CHAR(1)` constrained to `'A'` and `'U'` (L49) and
`cognito_sub UUID NOT NULL UNIQUE` (L54), and **no password column of any kind**.

Alternatives Considered: porting `SEC-USR-PWD` forward, either as a `custom:` pool
attribute or as a column on `auth.users`, hashed or not. Rejected because it
preserves the credential store this design exists to eliminate — hashing would
improve the storage and would still leave a second credential authority to keep in
step with the first, when Cognito already owns all of it.

Alternatives Considered: a self-managed user store — a table plus a hashing library
inside the auth service. Rejected because it moves credential handling, policy
enforcement, lockout and rotation into application code that then has to be
maintained and audited, against the migration's guiding principle of preferring a
managed service where that lowers operational burden.

The resulting behavioural change — a baseline credential would not satisfy this
policy — is registered in the
[divergence register](../../../docs/architecture/cobol-to-service-traceability.md)
rather than presented as parity. The decision itself is recorded in
[ADR-008](../../../docs/adr/ADR-008-security-and-identity.md), and the wider
treatment in
[security and identity](../../../docs/architecture/security-and-identity.md).

Assumptions: **what is preserved, so this section is not read as a general licence.** The
eight-character `SEC-USR-ID` (`app/cpy/CSUSR01Y.cpy` L18) remains the user's key
and is the Cognito username. The two-value user-type domain is preserved as the two
groups. The administrator-versus-user branch is preserved. The three sign-on
message strings are preserved verbatim — but service-side, in
`services/auth-service` and
[`ui/src/messages/messages.ts`](../../../ui/src/messages/messages.ts), not here.
This module owns the identity provider and nothing above it.


## The identity record, measured

Assumptions: the record layout is the contract, so the module's attribute set is
derived from it field by field rather than designed independently.

| Copybook field | Line | Picture | Bytes | Disposition in this module |
|---|---|---|---|---|
| `SEC-USR-ID` | L18 | `X(08)` | 8 | The Cognito username, and the key of `auth.users` |
| `SEC-USR-FNAME` | L19 | `X(20)` | 20 | The Cognito **standard** attribute `given_name` |
| `SEC-USR-LNAME` | L20 | `X(20)` | 20 | The Cognito **standard** attribute `family_name` |
| `SEC-USR-PWD` | L21 | `X(08)` | 8 | **Dropped — nothing at all.** The declined-parity decision above |
| `SEC-USR-TYPE` | L22 | `X(01)` | 1 | The custom attribute `custom:user_type`, and the group assignment |
| `SEC-USR-FILLER` | L23 | `X(23)` | 23 | **Dropped as padding**, recorded rather than silent |

`8 + 20 + 20 + 8 + 1 + 23 = 80` bytes, which is the documented `USRSEC.PS` record
length. Two of those six fields are dropped, and both drops are stated because an
absence otherwise leaves no trace at the point it was decided.

Assumptions: `given_name` and `family_name` are Cognito standard attributes and are
therefore **not** redeclared in the pool's `schema`. A standard attribute needs a
schema entry only when it differs from the default, and the pool schema is
immutable once created, so a redundant entry would be a permanent
pool-replacement risk for no gain. Their `X(20)` widths are still enforced —
`variables.tf` validates both against those exact copybook lines.

Assumptions: the user-type domain is **closed at two values** by its condition names in
`app/cpy/COCOM01Y.cpy`: `88 CDEMO-USRTYP-ADMIN VALUE 'A'` at L27 and
`88 CDEMO-USRTYP-USER VALUE 'U'` at L28. `app/cbl/COSGN00C.cbl` L230–L240 branches
exactly two ways on it — `XCTL PROGRAM('COADM01C')` at L232 for an administrator,
`XCTL PROGRAM('COMEN01C')` at L237 otherwise — which becomes `/admin` against
`/menu` routing on the client.

| User type | Group | Precedence |
|---|---|---|
| `'A'` | `carddemo-admin` | 1 |
| `'U'` | `carddemo-user` | 10 |

Assumptions: **these two group names are a cross-language contract, not a naming
preference.** They are what Cognito places in a token's `cognito:groups` claim;
[`JwtRoleConverter`](../../../services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java)
matches these exact strings to produce Spring Security authorities, and
`ui/src/hooks/useAuth.ts` tests them to decide whether the admin routes are
reachable. They are fixed in `main.tf` independently of `var.name_prefix` precisely
so that renaming environment resources cannot silently rename the authorities every
service recognises; renaming a group would break authorization in two languages at
once, and the converter refuses startup when its configured values differ from its
compiled authority contract.

Assumptions: **lower precedence wins in Cognito**, which is the opposite of the
intuitive reading and is why the numbers are explained rather than merely set. A
user in both groups resolves to administrator, mirroring `COSGN00C`'s structure
where the admin test comes first and the user path is the `ELSE` arm. The gap
between 1 and 10 permits another precedence value without renumbering either.


## RACF is mapped, not ported

Refactoring Rationale: RACF has no cloud analogue and is not ported. Its role is
filled by least-privilege IAM task roles plus the pool groups created here, and that substitution is
documented as a **mapping** rather than as a port — no claim of feature equivalence
is made.

Only one half of that mapping belongs to this module. This module provides the
Cognito-group half and **creates no IAM role at all**; the least-privilege task-role
half belongs to [`infra/modules/ecs-service`](../ecs-service/README.md).

Alternatives Considered: setting `role_arn` on the group resources, which the
provider permits and which reads at first glance like the natural way to express the
IAM half. Rejected because that attribute serves Cognito **identity pools** vending
temporary AWS credentials to a client, and this design has no identity pool — the
services are OAuth2 resource servers that validate a JWT, and no browser or user is
ever given AWS credentials. Setting it would attach a role that nothing assumes, so
it is left unset deliberately rather than by oversight.


## What the module provisions

Assumptions: the resource choices below are constrained by the baseline contracts
and by AWS and Terraform behaviour rather than being an open-ended target design.
The final column states the consequence under the plausible alternative instead of
restating the HCL. The generated reference at the end of this document lists the
same resources mechanically.

| Resource | Shape | Why it is this way |
|---|---|---|
| `aws_cognito_user_pool.this` | Singleton | The baseline had exactly one identity store — one `DEFINE FILE(USRSEC)` at `app/csd/CARDDEMO.CSD` L88 — and one sign-on transaction reading it. A second pool would split the meaning of the group claim across two issuers |
| — password policy | Derived from five inputs | Stronger than the baseline by intent, and floored so the baseline's own length cannot be restored |
| — MFA, threat protection | Coupled pairs | A software-token mechanism is emitted exactly when the mode is not `OFF`, and the pool tier is derived from the threat-protection mode rather than accepted as a second input, because an inconsistent pair fails at apply rather than at plan |
| — sign-up, recovery | Admin-only | The baseline had no self-registration path and no self-service reset; these identities carry no email address or telephone number at all, so a mail-based recovery route could never complete |
| — `schema` | One custom attribute | `custom:user_type`, one character wide, carrying `SEC-USR-TYPE`. Authorization does not depend on it — group membership grants authority and this records lineage |
| `aws_cloudformation_stack.app_client` | Confidential client | See the credential path below; this is the most surprising choice in the module |
| `aws_secretsmanager_secret.app_client` | CMK-encrypted | Carries the client id and generated secret for the auth service to read at run time |
| `terraform_data.app_client_secret_rotation` | Rotation bridge | Rotates the secret against the existing client id, so rotation never changes the JWT audience |
| `aws_cognito_resource_server.this` | Identifier + scopes | Creates the scope vocabulary once at the pool that owns it. These scopes are **not** what an interactive sign-on presents — such a token carries only the built-in scope |
| `aws_cognito_user_group.admin` / `.user` | Two explicit resources | The domain is closed at two values, and writing them out is what makes that visible; a `for_each` list would suggest the set is open when it is not |
| `aws_cognito_user_pool_domain.this` | `count`, default zero | Created only when `var.domain_prefix` is set. Nothing authenticates through a hosted page, and a domain prefix is globally unique within a region, so two environments in one region would collide on apply |
| `random_id.seed_user_secret` | Per seed user | A 128-bit handle that keeps the identity out of a secret's **name**, so listing secrets discloses no user id to a principal without permission to read the value |
| `aws_secretsmanager_secret.seed_user` | Per seed user, CMK-encrypted | Holds the one-time initial credential |
| `terraform_data.seed_user` | Per seed user | Creates and converges the pool user; a destroy-time counterpart removes it |
| `aws_cognito_user_in_group.seed_user` | Per seed user | Membership is a separate resource from the user, so a role change is an in-place membership change that does not touch the identity or its credential |

Alternatives Considered: keying per-seed-user resources by list position with
`count`. Rejected because removing one entry from the middle re-indexes every
higher-indexed instance, and Terraform then destroys and recreates users and secrets
that nobody touched — rotating credentials as a side effect of an unrelated edit.
The chosen `for_each` key is the eight-character user id. `var.seed_users` defaults
to an empty list, so a root that says nothing gets the pool, the client and both
groups with no identities at all.


## The user-enumeration trade-off

Trade-offs: this is the one place where a security correction and the migration's
verbatim-message requirement genuinely pull against each other, so both halves are
stated rather than one being quietly dropped.

The baseline answers the two credential failure modes differently on purpose:
`app/cbl/COSGN00C.cbl` L242–L243 returns `Wrong Password. Try again ...` when the
keyed read succeeded but the comparison failed, and L249 returns
`User not found. Try again ...` when the read came back `RESP` 13. The difference
between those two replies tells an unauthenticated caller which user ids exist,
which is user enumeration. The app client therefore sets
`PreventUserExistenceErrors` to `ENABLED`, so the provider answers both cases
identically; the auth service returns the baseline's `Wrong Password. Try again ...`
string for both, `User not found. Try again ...` stays catalogued for traceability
only, and an unrelated provider failure keeps `Unable to verify the User ...`
(L254). The lost discrimination between the two credential failures is a
behavioural divergence and is registered in the
[divergence register](../../../docs/architecture/cobol-to-service-traceability.md).

Alternatives Considered: exposing this as a module input so a root could select the
baseline's distinguishable responses. Rejected because a reachable legacy value
ports the defect — the enumeration channel would be one line of tfvars away, and no
root has a reason to want it. The value is fixed inside the module so the invariant
cannot be overridden from outside it.


## The credential path

Two credentials exist here, and neither is ever authored, printed or published.

**The app client secret.** Refactoring Rationale: CloudFormation creates the
confidential client because it treats the generated client secret as a write-only
service value. The native Terraform resource returns that secret as a computed
attribute and therefore retains it in every historical state version, even when no
output publishes it. The stack exposes only `ClientId`; the rotation bridge reads the
secret from Cognito and writes it into Secrets Manager without it passing through
Terraform. Rotation adds a new secret against the same client id and retains the
previous one for a consumer rollout, because replacing the client instead would
change the id that the JWT authorizer and every token validator use as the audience,
turning a credential rotation into a coordinated identity-contract release.

**A seed user's initial password.** Refactoring Rationale: the baseline commits one
shared credential literal with the seed rows, whereas this path generates an
independent value for each requested identity and leaves no value in source. The
replacement path is:

1. `seed_user_bootstrap.py` generates a policy-compliant value **in process
   memory**, using a cryptographically secure generator seeded per required
   character class and then shuffled.
2. It is applied to the pool user through AWS CLI **JSON files** rather than process
   arguments, so it never appears in a command line, and the pool receives it as a
   **temporary** password that must be changed at first sign-in.
3. The same value is written to that user's Secrets Manager entry, encrypted under
   the customer-managed key supplied as `var.secrets_kms_key_arn`.
4. `outputs.tf` publishes only each entry's **ARN and name**, keyed by the opaque
   handle — never a value, and never a value paired with a user id.
5. An operator retrieves it out of band under their own
   `secretsmanager:GetSecretValue` and `kms:Decrypt` permissions, which leaves a
   record in CloudTrail.

Trade-offs: generating in process memory keeps credentials out of Terraform state,
but the operator must change a revision input to request a deliberate rotation.
State retains only non-credential material: resource identifiers, the opaque
per-user handle, and those revision inputs. The temporary-password window bounds
how long an unretrieved handover value stays useful, and
`var.temporary_password_validity_days` is constrained to a short range for that
reason.

Refactoring Rationale: a customer-managed key rather than the AWS-managed default,
which would also encrypt and would do so silently. A customer-managed key carries a
key policy that can be audited and revoked independently of the secret, so
possession of the secret is not sufficient without the key's permission. The key is
created by [`infra/modules/kms`](../kms/README.md) and passed in by the calling
root; the input is required with no default so a fall-back to the AWS-managed key
cannot happen unnoticed.

```bash
# WHAT: retrieve one seed identity's initial credential from Secrets Manager, using
#       a secret name taken from this module's seed_user_secret_names output.
# WHY : Trade-offs: retrieval is deliberately out of band rather than a Terraform
#       output. An output is printed to the console, written into the state of every
#       consuming root and readable with one command, which would turn a managed,
#       audited secret into an unmanaged copy of itself. The cost is one extra
#       operator step; the gain is that every read is attributable in CloudTrail.
#       Do not echo the result into a shell history, a log or a ticket.
aws secretsmanager get-secret-value \
  --region "<region>" \
  --secret-id "<secret-name>" \
  --query SecretString \
  --output text
```


## Module boundary and usage

Assumptions: this directory is a called module, not a Terraform root. It is **never
applied directly**: it declares no `backend`, no provider configuration and no nested
`module` block, and it calls no sibling module.

```hcl
module "cognito" {
  source = "../../modules/cognito"

  name_prefix         = var.name_prefix
  environment         = var.environment
  secrets_kms_key_arn = module.kms.secrets_key_arn

  callback_urls = ["${local.spa_origin}/callback"]
  logout_urls   = [local.spa_origin]

  mfa_configuration      = var.environment == "prod" ? "ON" : "OPTIONAL"
  advanced_security_mode = var.environment == "prod" ? "ENFORCED" : "AUDIT"
  deletion_protection    = var.deletion_protection ? "ACTIVE" : "INACTIVE"

  seed_users = [
    {
      user_id     = "ADM00001"
      given_name  = "Demo"
      family_name = "Admin"
      user_type   = "A"
    },
  ]
}
```

Assumptions: the calling root does every piece of cross-module wiring, because this
module resolves no data source and reads no sibling's state. `secrets_kms_key_arn`
arrives from the `kms` module's `secrets_key_arn` output rather than as a literal
ARN, and `callback_urls` and `logout_urls` arrive from the distribution that
[`infra/modules/cloudfront-spa`](../cloudfront-spa/README.md) creates. Keeping key
ownership in the module responsible for it is also what prevents a dependency cycle,
and it keeps region, credentials and default tags from disagreeing with the root's
own provider configuration.

Assumptions: of the published values, two matter most outside this module:
**`issuer_uri`** and **`user_pool_client_id`** become the `issuer` and `audience` of the Cognito JWT
authorizer in [`infra/modules/api-gateway-http`](../api-gateway-http/README.md), and
the same two reach every service's OAuth2 resource-server configuration through
Parameter Store, written there by the calling root. Terraform module outputs are the
only source of these identifiers — no service hard-codes an endpoint.

Assumptions: the environment roots that instantiate this module are
[`infra/envs/dev`](../../envs/dev/README.md) and
[`infra/envs/prod`](../../envs/prod/README.md). The deploy and teardown command
sequences belong to those roots and to
[`infra/bootstrap`](../../bootstrap/README.md), not to this directory: see the
[infrastructure guide](../../README.md), the
[deploy runbook](../../../docs/runbooks/deploy.md) and the
[teardown runbook](../../../docs/runbooks/teardown.md), which orders teardown in
reverse with bootstrap destroyed **last**.


## Validation and gates

Assumptions: this module is validated **transitively**. The CI job initialises and
validates only the three Terraform roots, and a root's `terraform init` resolves
`source = "../../modules/cognito"`, so a configuration error here surfaces as a
failure of the root that calls it. The commands below check this directory on its
own, which is useful locally and is the form the sibling modules document too.

```bash
# WHAT: check formatting, resolve providers without a backend, validate the
#       configuration, lint the HCL, and confirm the generated region of this
#       README still matches the .tf files beside it.
# WHY : Assumptions: validate needs initialized provider schemas, while a normal
#       init would demand the remote backend and credentials; -backend=false
#       resolves providers without touching state. -lockfile=readonly is used
#       because this directory's provider lock is committed, and a writable init
#       could otherwise alter it and report as documentation drift no author caused.
terraform fmt -check -recursive infra/modules/cognito
terraform -chdir=infra/modules/cognito init -backend=false -lockfile=readonly -input=false
terraform -chdir=infra/modules/cognito validate
tflint --chdir=infra/modules/cognito --config="$(pwd)/infra/.tflint.hcl"
terraform-docs --config infra/.terraform-docs.yml \
  --output-check infra/modules/cognito
```

Assumptions: [`infra-ci.yml`](../../../.github/workflows/infra-ci.yml) is
authoritative for the gate shape. Every gate in it that covers
this directory is **gating** — none is advisory and none tolerates a non-zero exit:

* **Formatting** — `terraform fmt -check -recursive infra/`. Check mode rather than
  a bare `fmt`, which would rewrite the checkout and let drift pass review.
* **Initialise and validate** — run over the three roots, which is where this module
  is reached from.
* **HCL lint** — `tflint --recursive` against `infra/.tflint.hcl`, whose
  documented-variables and documented-outputs rules are what guarantee the raw
  material this document's generated region renders.
* **Generated-document drift** — `terraform-docs --output-check` over every module
  and root, including this one. It is **check-only by design**: CI is forbidden from
  regenerating and committing, because silent mutation would repair the drift, turn
  the build green and leave the author never knowing the published contract was
  wrong. A stale README therefore fails the build, which is why the two markers and
  everything between them must stay in step with the `.tf` files, and why
  hand-editing inside them is the specific failure this gate exists to catch.
* **Secret scan** — run over migration-owned tracked files, this document included.
* **Policy scan** — Trade-offs: it does **not** select by severity. The offline
  scanner distribution carries no policy severities, so a severity filter would
  select zero checks and report a false green; the gate instead runs one visible
  full scan and then hard-fails against an explicit, reviewable material-security
  check list, with a summary assertion so an empty selection can never pass again.
  The strong password policy, the MFA configuration and the threat-protection
  setting satisfy it by construction. Two suppressions exist in `main.tf`, each
  carrying the scanner's **real** check id and a distinct, specific reason — one
  records that app-client rotation is implemented through service APIs the graph
  check cannot recognise, the other that a one-time handover value would only be
  desynchronised by rotating it. A fabricated id is not an acceptable suppression.

Assumptions: the module is authored and statically validated. Applying a root against a live AWS
account is an operator action outside this scope: no claim is made here that a pool
exists, that an identity has been created, or that anything has been assessed
against a live account.


## What this module does not own

Assumptions: ownership follows the component that implements each contract; this
module deliberately stops at Cognito resources. A reader will otherwise look here
for these:

* `auth.users` and its columns —
  [`V1__auth.sql`](../../../services/auth-service/src/main/resources/db/migration/V1__auth.sql).
* The `cognito:groups`-to-authority conversion —
  [`JwtRoleConverter`](../../../services/common-lib/src/main/java/com/carddemo/common/security/JwtRoleConverter.java).
* The three verbatim sign-on messages — `services/auth-service` and
  [`ui/src/messages/messages.ts`](../../../ui/src/messages/messages.ts).
* The JWT authorizer — [`api-gateway-http`](../api-gateway-http/README.md).
* The KMS keys — [`kms`](../kms/README.md).
* The IAM task roles — [`ecs-service`](../ecs-service/README.md).
* The SPA delivery path — [`cloudfront-spa`](../cloudfront-spa/README.md).
* The Parameter Store writes — the two environment roots.

Assumptions: the ETL boundary is worth stating because the two halves must agree.
`data-migration/src/carddemo_migration/readers/usrsec.py` loads the `USRSEC`
**profile rows** into `auth.users` and loads **no credentials**, because that table
has no password column. Cognito holds the identities and their credentials,
`auth.users` holds the profile rows, `cognito_sub` is the join, and the two must
agree on the eight-character id and the `'A'`/`'U'` type. Note also that `USRSEC`
exists only in EBCDIC form, at
[`app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS`](../../../app/data/EBCDIC/AWS.M2.CARDDEMO.USRSEC.PS),
with no ASCII counterpart under `app/data/ASCII/` — so the reader decodes it rather
than reading text.

Assumptions: the baseline remains exactly where it was. It is reference-only as a
matter of status, not deprecation: the migration adds a path and does not remove one, and the
mainframe sign-on programs, their copybooks and their seed data are untouched. For
the whole picture see [`MIGRATION_README.md`](../../../MIGRATION_README.md).


## Generated Terraform reference

Assumptions: the region below is generated from this module's `.tf` files by
terraform-docs v0.20.0 under [`infra/.terraform-docs.yml`](../../.terraform-docs.yml). Do not edit
it by hand; regenerate it instead, and keep hand-written prose outside the markers.

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
