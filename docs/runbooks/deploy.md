# CardDemo AWS deployment runbook

## Document contract

**Purpose**: Provision one CardDemo environment from the committed Terraform, publish the ten
container images, migrate its database, roll out the services, rotate the confidential Cognito app
client when required, and verify the candidate business flows.

**Source of truth**: The environment roots under `infra/envs/`, the module inputs and outputs under
`infra/modules/`, the service manifests under `services/`, and the AAP deployment and security
requirements. The mainframe assets under `app/**` remain reference-only.

| Parameter | Kind | Description |
|:---|:---|:---|
| `<env>` | enum | `dev` or `prod`; selects the corresponding environment root. |
| `<aws-region>` | AWS region | Region configured by the environment root and the short-lived deployment identity. |
| `<deploy-role-arn>` | IAM role reference | Federated deployment role; never commit its resolved value. |
| `<account-registry>` | ECR registry hostname | Resolved at run time from the target account into the `registry` shell variable by Step 2a, never written into this document. |
| `<commit-sha>` | immutable image tag | Source revision used for all ten images in one release. |
| `<planfile>` | local file path | Saved Terraform plan, reviewed before it is applied. Name it with a `.tfplan` suffix so `.gitignore` L326-L327 excludes it; a bare `tfplan` has no suffix to match and stays trackable, and a saved plan embeds the resolved value of every attribute the apply will set alongside a copy of the prior state. The concrete names used below are `bootstrap.tfplan`, `ecr.tfplan`, `<env>.tfplan` and `rotation.tfplan`. Never commit it. |
| `<next-rotation-revision>` | positive integer | Monotonic Cognito app-client-secret rotation trigger. |
| `<regional-alb-certificate-arn>` | ACM certificate ARN | REGIONAL certificate presented by the internal ALB HTTPS listener, covering `<internal-service-name>`. Required; no default exists. |
| `<internal-service-name>` | bare DNS hostname | The name that certificate covers and that the API Gateway private integration verifies. Required; no default exists. |
| `<us-east-1-spa-certificate-arn>` | ACM certificate ARN | Certificate for the SPA distribution, which CloudFront reads only from `us-east-1`. Required; no default exists. |
| `<spa-hostname>` | bare DNS hostname | Alias the SPA distribution answers on; must be covered by the certificate above. |
| `<api-hostname>` | bare DNS hostname | Host of the deployed API, used to narrow the SPA's permitted request origin after the apply. |
| `<oidc-provider-arn>` | IAM provider ARN | The account's GitHub OIDC provider, published by `infra/bootstrap`. |
| `<iam-permissions-boundary-arn>` | IAM policy ARN | The organisation's own IAM guardrail. This package does not create it. |
| `<owner>`/`<repo>` | repository slug | Owner and name of the repository the publication role is granted to. |
| `<oncall-address>` | email address | Alarm recipient. At least one is required in both environments. |
| `<mask-hmac-secret-arn>` | Secrets Manager ARN | Entry holding the masking HMAC key. Its ARN is an input; its value is created out of band. |
| `<mask-hmac-cmk-arn>` | KMS key ARN | Customer-managed key encrypting the masking secret. Must share the secret's account and Region. |
| `<name-prefix>` | name prefix | The selected root's `name_prefix` variable, which prefixes the Secrets Manager entry names below. |
| `<cluster-name>` | ECS cluster name | Read from the environment's Terraform output at the point of use. |
| `<service-name>` | ECS service name | The service being rolled. Substitute each consumer in turn where a step names several. |
| `<auth-service-name>`, `<account-service-name>`, `<minting-service-name>` | ECS service names | The specific services a rotation step must roll; named separately because rolling the wrong one widens an outage. |
| `<user-pool-id>`, `<app-client-id>` | Cognito identifiers | Read from the environment's Terraform output; neither is a secret. |
| `<app-client-secret-arn>` | Secrets Manager ARN | Entry holding the Cognito app-client secret payload. Never print its value. |
| `<seed-user-secret-name>` | Secrets Manager name | Entry holding one seed user's generated initial password. Resolve it from the `credential_secret_name_prefix` output; do not paste a resolved name back into this file. |
| `<secret-name>` | Secrets Manager name fragment | The single key being replaced in a rotation step, substituted into the entry name. |
| `<role-name>` | database role name | The PostgreSQL role whose credential is being replaced. The role name is also its secret name. |
| `<master-username>`, `<master-secret-arn>` | database master identity | The cluster master role and the Secrets Manager entry holding its credential. |
| `<parameter-prefix>` | Parameter Store prefix | Prefix beneath which the environment's runtime configuration is published. |
| `<trust-anchor-path>` | local file path | Certificate bundle used to verify the database server, because every service pins `sslmode=verify-full`. |
| `<planfile>` | local file path | Saved plan; listed once above and reused by every `plan`/`apply` pair. |
| `<csp-planfile>` | local file path | Saved plan for the SECOND apply that narrows the content-security policy in Step 6b. Named separately from `<planfile>` so the two are not overwritten by each other; the first apply's plan is the one Step 3 reviewed. |

Three further tokens are **naming patterns rather than operator inputs**, and are shown as
placeholders only so the shape of a generated name is visible: `<context>` in
`carddemo_<context>_owner` and `carddemo_<context>_migrator`, `<role>` in `<role>_migrator`, and
`<module>` in `python -m carddemo_migration.<module>`. Do not attempt to supply a value for these --
substitute the bounded context or module you are acting on.

**Expected outcome / success signal**: Terraform exits zero after applying the reviewed saved plan;
all long-running ECS services reach a stable state; health checks report `UP`; Flyway reports no
pending migration; and every smoke flow below returns its expected success or documented business
reject.

**Failure modes and handling**: Stop before apply when the plan contains an unexplained destroy,
when the remote-state backend is absent, when image digests do not match the intended commit, or
when a credential would be written to a tracked file. Do not edit an applied Flyway migration.
Use the failure table below and [teardown.md](teardown.md) for lock recovery and protected teardown.

---

## Scope and deployment boundary

This runbook provides operator commands; it is not evidence that a live AWS environment has been
provisioned. A real `terraform apply` and the cost it incurs remain operator actions. The migration
adds an AWS path and leaves the existing z/OS and AWS Mainframe Modernization paths intact.

Assumptions: the infrastructure in this repository is **authored and statically validated only**.
`terraform fmt -check`, a credential-free `init -backend=false`, `validate`, `tflint` and the policy
and generated-document checks run as build gates in
[`infra-ci.yml`](../../.github/workflows/infra-ci.yml); none of them contacts an AWS account. Static
success is therefore evidence about the configuration and **not** evidence that any environment
exists, that any resource has been created, or that any capacity, latency or throughput figure has
been measured. No load test and no benchmark has been run. Read every command below as the procedure
an operator would follow, not as a record of one already performed.

There are exactly three Terraform roots, and every command in this runbook addresses one of them:

| Root | Applied | Holds |
|:---|:---|:---|
| `infra/bootstrap` | Once per AWS account, out of band | The remote-state backend: a versioned, encrypted S3 bucket and a DynamoDB lock table |
| `infra/envs/dev` | Per change to the development environment | The development environment root, sized down independently of production |
| `infra/envs/prod` | Per change to the production environment | The production environment root |

Each root composes the same sixteen reusable modules under `infra/modules/`: `network`, `kms`,
`secrets`, `ecr`, `aurora-postgresql`, `ecs-cluster`, `ecs-service`, `alb`, `api-gateway-http`,
`cognito`, `sqs`, `step-functions-batch`, `eventbridge-scheduler`, `s3-datasets`, `cloudfront-spa`
and `observability`. Because both environment roots call the same modules, the topology they
provision is identical and only the sizing and retention inputs differ.

---

## Prerequisites

- **Terraform 1.15.8.** Every root declares `required_version = ">= 1.15.0"`; 1.15.8 is the version
  this configuration is validated on.
- **Providers**: `hashicorp/aws` constrained `~> 6.56` and `hashicorp/random` constrained `~> 3.9` --
  the only two providers any root declares, matching AAP §0.6.1.4. The tracked
  `.terraform.lock.hcl` in each root resolves those constraints to **aws 6.57.1** and
  **random 3.9.0**. Verify the lock file; never rewrite it as a way of moving a version.
- **AWS CLI** with the Cognito `add-user-pool-client-secret`,
  `list-user-pool-client-secrets`, and `delete-user-pool-client-secret` operations.
- **Maven 3.9.16 on Java 21**, Node.js compatible with `ui/package.json`, Python 3.13, Docker, and `jq`.
- A clean checkout and a short-lived federated AWS session. Do not create access keys for this
  procedure.

The image builds pin these base images. They are listed because a wrong tag here fails a build
outright rather than degrading a deployment, and one of them is easy to get wrong in a way the
Dockerfile itself gives no hint of:

| Stage | Pinned base image | Declared in |
|:---|:---|:---|
| Java build | `maven:3.9.16-amazoncorretto-21-al2023` | each `services/*/Dockerfile` |
| Java runtime (eight services) | `public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless` | each `services/*/Dockerfile` |
| UI build | `node:22.23.2-alpine` | `ui/Dockerfile` |
| UI runtime | `nginx:1.30.4-alpine` | `ui/Dockerfile` |
| ETL build and runtime | `python:3.13.14-slim-trixie` | `data-migration/Dockerfile` |

Every one of those references additionally carries an `@sha256:` digest in the Dockerfile, so the tag
above is documentation and the digest is the contract. Read the tag from the file rather than from
this table if the two ever disagree.

Assumptions: the UI build stage is **22.23.2**, not 22.23.1. 22.23.2 is the Node patch that carries
the current security floor for this image, and `ui/Dockerfile` pins it by digest -- a runbook that
names 22.23.1 sends an operator looking for a tag this build does not use.

Alternatives Considered: an Alpine/musl base for the Java runtime, which would be a materially
smaller image. Rejected, and the reason is registry-specific rather than a blanket claim that no such
image exists -- one does. Official Alpine Corretto 21 images are published on Docker Hub from the
`corretto/corretto-docker` project as `amazoncorretto:21-alpine`, `21-alpine3.24` and
`21.0.12-alpine`. What is true of the registry this build actually pulls from is narrower: the
Corretto **ECR Public** repository `public.ecr.aws/amazoncorretto/amazoncorretto` publishes only the
`-al2` and `-al2023` tag families, so `public.ecr.aws/amazoncorretto/amazoncorretto:21-alpine`
resolves to nothing and fails the build. Three things then decide the choice against switching
registries to take the Alpine image. Alpine links musl rather than glibc, and a long-running JVM
carrying fixed-point money arithmetic is the last workload to move to a different libc for an image-
size gain. AL2023 package versions correspond to Amazon Linux Security Advisories, which is what
lets a scan finding be traced to a published advisory. And the build stage
(`maven:3.9.16-amazoncorretto-21-al2023`) is itself AL2023, so keeping the runtime on AL2023 keeps
one libc across both stages instead of compiling on glibc and running on musl.

Trade-offs: `nginx` is held on the **stable** branch rather than mainline 1.31.x because a static
asset server needs no mainline feature and stable receives a longer patch window.

```bash
# WHAT: verifies that the deployment tools resolve before any state or registry operation.
# WHY : Assumptions: a missing or wrong-major tool is discovered before an apply can partially
#       change shared infrastructure.
# WHY : Assumptions: the interpreter is checked as `python3` and not as `python`. On a current
#       Linux image the unsuffixed name is frequently absent even where 3.13 is installed, so
#       `python --version` reports "command not found" for a prerequisite that is in fact met --
#       and every Python command in this runbook names `python3` or the environment's own
#       interpreter by path, so `python3` is the name that has to resolve.
terraform version
aws --version
mvn --version
node --version
python3 --version
docker version
```

Static validation is a separate path from the deploy path below, and confusing the two wastes an
apply. The credential-free form initialises without a backend and can therefore only check syntax:

```bash
# WHAT: checks formatting, builds the Lambda deployment packages, then initialises and validates
#       every Terraform root -- today `infra/bootstrap`, `infra/envs/dev` and `infra/envs/prod` --
#       without contacting AWS.
# WHY : Assumptions: -backend=false initialises WITHOUT the remote state, so this form needs no
#       credentials -- and for the same reason it cannot plan or apply. An operator who runs this
#       and then tries to apply is working against an uninitialised backend. Use the
#       backend-enabled init in Step 3 for a real deployment.
# WHY : Refactoring Rationale: this block validated `infra/envs/dev` alone and ran BEFORE the
#       Lambda archives were built, so on a clean checkout the exact documented command failed on
#       its first root and `infra/bootstrap` and `infra/envs/prod` were never validated at all.
#       Both `validate` and `plan` evaluate `filebase64sha256` over `infra/lambda/dist/*.zip`, so
#       the packages have to exist first. The builder runs WITHOUT `--check` here for the same
#       reason [`infra-ci.yml`](../../.github/workflows/infra-ci.yml) omits it at this point: the
#       archives do not exist yet, and verifying them against the sources they were just built
#       from would assert nothing. Step 2c below owns the packaging detail and the `--check` form.
# WHY : Assumptions: the environment roots are reached by the GLOB `infra/envs/*/` rather than by
#       naming dev and prod, so a third environment cannot be added and then silently skipped by a
#       loop that still reports green; `infra/bootstrap` is named explicitly because it is not an
#       environment and does not sit under `infra/envs`. The closing comparison then asserts BOTH
#       that every root the glob discovered validated and that there were at least three of them --
#       the equality catches a failing root in a longer list, and the lower bound, which matches the
#       assertion CI makes over the same loop, catches a glob that matched nothing. Adding a fourth
#       environment is therefore not a failure, but skipping one is.
# WHY : Assumptions: -lockfile=readonly is required rather than tidy. Nineteen
#       `.terraform.lock.hcl` files are committed under `infra/` and are what pin aws 6.57.1 and
#       random 3.9.0; without the flag an `init` may rewrite the lock it was meant to honour, which
#       turns a verification run into an unreviewed provider change.
# WHY : Trade-offs: the block counts successes and ends in an explicit `false` rather than opening
#       with `set -e`, so it stays safe to paste into an interactive shell -- `set -e` there makes
#       the operator's own shell exit on the first failing command. The counter is what carries the
#       failure out: a root whose init or validate fails does not increment it, so the closing
#       comparison against the number of roots discovered makes the block exit non-zero and names
#       the gap. Ending on a bare `echo` would have reported the gap and still exited 0, which is
#       the false green this block exists to avoid.
# WHY : Trade-offs: the three stages are one `&&` chain, so a tree that fails `fmt -check` does not
#       go on to build archives, and a build that fails does not go on to validate. Left unchained
#       the block still exited non-zero -- the roots would fail on the missing archives -- but the
#       one line an operator needed would sit above several hundred lines of consequential output.
terraform fmt -check -recursive infra/ &&
  python3 infra/lambda/build_packages.py &&
  { roots=(infra/bootstrap infra/envs/*/)
    validated=0
    for root in "${roots[@]}"; do
      terraform -chdir="$root" init -backend=false -lockfile=readonly -input=false &&
        terraform -chdir="$root" validate &&
        validated=$((validated + 1))
    done
    if [ "$validated" -eq "${#roots[@]}" ] && [ "$validated" -ge 3 ]; then
      echo "validated $validated of ${#roots[@]} Terraform roots"
    else
      echo "validated only $validated of ${#roots[@]} Terraform roots; expected all, at least 3" >&2
      false
    fi; }
```

```bash
# WHAT: installs the pinned TFLint rulesets, then lints every Terraform directory under infra/.
# WHY : Refactoring Rationale: this gate was described as `tflint --config infra/.tflint.hcl` run
#       from the repository root. That invocation is NOT recursive -- it inspects the current
#       directory only, and the repository root holds no Terraform -- so it exits 0 with no
#       findings while checking none of `infra/**`; measured on 0.64.0. A lint gate that passes
#       without reading the tree it governs is worse than an absent one, because its green result
#       is quoted as evidence.
# WHY : Assumptions: the recursive pass starts FROM `infra/`, because --recursive traverses every
#       directory beneath the working directory. From the repository root that traversal also
#       enters `services/`, `ui/`, `data-migration/` and, on a workspace that has built,
#       `services/*/target` and `ui/node_modules` -- none of which holds HCL. It is run in a
#       subshell so the operator's own working directory is unchanged afterwards.
# WHY : Assumptions: --config stays ABSOLUTE, which is why it is not shortened to `.tflint.hcl`
#       once the working directory is `infra/`. Under --recursive TFLint changes into each visited
#       directory and resolves a relative --config against THAT directory, so `--config=.tflint.hcl`
#       is found in `infra/` and nowhere else: measured from `infra/` on 0.64.0 it aborts with
#       "Failed to load TFLint config; failed to load file: open .tflint.hcl: no such file or
#       directory" at the first module directory. Dropping --config altogether is worse than that
#       failure, because each directory then finds no configuration, falls back to TFLint's
#       built-in defaults -- neither the pinned AWS ruleset nor the notice-level documentation
#       rules among them -- and the run exits 0 having checked almost nothing.
#       `infra/.tflint.hcl` records both cases in its own header and states that the absolute-path
#       requirement is not a preference.
# WHY : Assumptions: `--init` is a separate call because the AWS ruleset that configuration pins is
#       downloaded and signature-verified before any linting, and --recursive does not install it.
#       CI pins TFLint itself at 0.64.0 by checksum, so reproduce a CI finding with that version.
#       The configuration sets `force = false`, so a finding exits 2 and only a clean tree exits 0.
# WHY : Trade-offs: the two calls are chained rather than listed, so a failed or partial `--init`
#       cannot be followed by a lint pass whose own result is then meaningless. It costs nothing
#       when the plugins are already installed -- `--init` prints "All plugins are already
#       installed" and exits 0 -- and it means the block's exit status is the first real failure.
repository_root="$(pwd)"
tflint --init --config="$repository_root/infra/.tflint.hcl" &&
  ( cd "$repository_root/infra" && tflint --recursive --config="$repository_root/infra/.tflint.hcl" )
```

These same checks, plus a policy scan and a `terraform-docs` drift check, are owned by
[`infra-ci.yml`](../../.github/workflows/infra-ci.yml) and run on every pull request in this order.
They are repeated here only so an operator can reproduce a CI failure locally.

**Note**: if you install Terraform through the `hashicorp/setup-terraform` action rather than
directly, set `terraform_wrapper: false`. Assumptions: that action installs a wrapper which rewrites
exit codes, and `terraform plan -detailed-exitcode` is three-valued -- 0 no changes, 1 error, 2
changes present. Under the wrapper a "changes present" result can be read as success, which silently
defeats any check built on that exit code.

---

## Deployment path

```mermaid
graph LR
    A[Assume deployment role] --> B[Bootstrap remote state]
    B --> B1[Resolve the four backend values]
    B1 --> C0[Init backend, apply module.ecr only]
    C0 --> C[Build, push and pin ten images<br/>and mirror the collector]
    C --> D[Plan and apply environment]
    D --> E[Apply schemas and load data]
    E --> F[Roll out services]
    F --> F1[Narrow CSP, publish SPA with config.json]
    F1 --> G[Smoke verification]
%% The registry apply sits BEFORE the image build because the ten ECR repositories are
%% Terraform-managed: on a clean account there is nothing to push to until it has run.
%% Narrowing the CSP sits AFTER the environment apply because the API origin it names does
%% not exist until that apply creates it.
    %% Step 3 then re-runs init, which is idempotent, and plans the whole environment against
    %% the digests the push returned.
```

---

## Step 0 - Obtain short-lived credentials

Use the organization's federated or SSO process to assume `<deploy-role-arn>`, then export only the
temporary values it returns.

```bash
# WHAT: confirms the current shell is using the intended short-lived deployment identity.
# WHY : Assumptions: every later command inherits this identity; checking it once prevents a plan
#       from being created against a different account or role than the reviewer expects.
aws sts get-caller-identity --query Arn --output text
```

Do not paste the returned account identifier or credentials into a file, issue, or review comment.

---

## Step 1 - Bootstrap the remote-state backend

Run this once per AWS account before either environment root performs a backend-enabled `init`.

```bash
# WHAT: initialises the bootstrap root without a remote backend.
# WHY : Assumptions: the S3 state bucket and DynamoDB lock table do not exist yet, so asking this
#       root to use them would create a circular prerequisite.
terraform -chdir=infra/bootstrap init -backend=false -input=false
```

```bash
# WHAT: creates a reviewable bootstrap plan.
# WHY : Alternatives Considered: applying directly was rejected because the state bucket, lock
#       table and KMS policy are account-wide controls whose exact names and deletion settings must
#       be reviewed before creation.
terraform -chdir=infra/bootstrap plan -out="bootstrap.tfplan"
```

```bash
# WHAT: applies the exact bootstrap plan that was reviewed.
# WHY : Assumptions: a saved plan keeps the reviewed graph and the applied graph identical; a
#       second implicit plan could incorporate an unreviewed file or provider change.
terraform -chdir=infra/bootstrap apply "bootstrap.tfplan"
```

The bootstrap root is intentionally outside the normal deployment workflow. Running it from a
workflow that already needs the backend would be circular.

### Step 1a - Resolve the four partial-backend values

`infra/envs/<env>/backend.tf` is a **partial** backend: it commits only `key` and `encrypt`, because
the bucket name embeds the account identifier, the lock table and the customer-managed key ARN do the
same, and the Region is a deployment property. Nothing supplies those four at `init` unless this step
does, and `infra/bootstrap` is the only place they exist. Export them once here; every
backend-enabled command in this runbook -- the registry `init` in Step 2, the environment `init` in
Step 3, and every `plan`/`apply` that follows -- reads these four variables and nothing else.

```bash
# WHAT: read the four partial-backend values out of the bootstrap root and export them under the
#       same names deploy.yml uses for its protected GitHub environment variables.
# WHY : Assumptions: the values are READ from bootstrap rather than typed, because three of the four
#       identify the account. Typing them is how a `dev` root is initialised against another
#       environment's state, which succeeds and then plans a destroy of resources this root never
#       created. Reading them also means the bootstrap root is the single source, so a state
#       relocation is one apply and no runbook edit.
# WHY : Assumptions: `output -raw` is used rather than `-json` because each of the four is a bare
#       string, and `-raw` fails non-zero on a name the root does not publish instead of printing
#       `null` into a `-backend-config` value -- which would initialise against a bucket literally
#       named "null". These four names are the ones `infra/bootstrap/outputs.tf` publishes; the root
#       also publishes `state_audit_bucket_name` and `state_object_access_trail_arn`, which the
#       backend does not take.
# WHY : Trade-offs: the KMS key ARN is passed even though `infra/bootstrap` sets the bucket's DEFAULT
#       encryption to SSE-KMS under that same key. Supplying it states the client's requirement in
#       agreement with the bucket rather than relying on the default alone, which is the posture
#       `infra/envs/<env>/backend.tf` records for its committed `encrypt = true`.
export CARDDEMO_TF_STATE_BUCKET="$(terraform -chdir=infra/bootstrap output -raw state_bucket_name)"
export CARDDEMO_TF_STATE_REGION="$(terraform -chdir=infra/bootstrap output -raw aws_region)"
export CARDDEMO_TF_STATE_LOCK_TABLE="$(terraform -chdir=infra/bootstrap output -raw state_lock_table_name)"
export CARDDEMO_TF_STATE_KMS_KEY_ID="$(terraform -chdir=infra/bootstrap output -raw state_kms_key_arn)"
```

Assumptions: `dynamodb_table` is the argument name Terraform 1.15.8's S3 backend takes for the lock
table, and it emits a deprecation warning in favour of S3-native `use_lockfile` locking. That warning
is **expected output** of every `init` below, not a misconfiguration:
`infra/envs/<env>/backend.tf` records why the argument is kept and what has to happen in both roots
together before it can go away.

---

## Step 2 - Build and publish the ten images

The eight service images are `auth-service`, `account-service`, `card-service`,
`transaction-service`, `reference-service`, `batch-service`, `authorization-service`, and
`reporting-service`. The other two are `ui` and `data-migration`. `common-lib` is a Maven library,
not an image. **Ten is the whole inventory**: `infra/modules/ecr` declares those exact ten names and
validates the set for length and membership, so an eleventh name is a plan-time failure rather than
an extra repository.

Refactoring Rationale: an earlier form of this section recorded the pinned AWS Distro for
OpenTelemetry collector mirror, and the eleventh repository holding it, as **withdrawn**. Both are
live, and the claim is corrected here because it contradicted the step that populates them. **Ten is
the built inventory, not the registry's**: `infra/modules/ecr` takes the ten names above and the
separate `third_party_mirror_repository_names` input, and provisions the union -- so the ten-deployable
count of AAP §0.4.1.6 stays assertable while the cache stays visible. `infra/modules/ecs-service`
runs the collector sidecar in every task with `enable_telemetry_collector` defaulting to `true`, so
the mirror must be populated before the apply: see
[Step 2b](#step-2b---mirror-the-pinned-telemetry-collector-image).

**Order of operations, and why it is not the intuitive one.** The registry has to exist before the
first push, and the pushed images' digests have to exist before the environment apply, so this step
brackets the apply rather than simply preceding it:

| Sub-step | What it does | Why it sits here |
|:---|:---|:---|
| 2 (this section) | Runs the Maven, SPA and ETL build gates | A failing gate must stop the release before anything is published |
| [2c](#step-2c---build-the-operational-lambda-packages) | Builds the three Lambda archives | Every `plan` in this runbook evaluates `filebase64sha256` over them, and the first of those plans is 2d |
| [2d](#step-2d---provision-the-artifact-registry-before-the-first-push) | Applies `module.ecr` alone | The ten repositories a push needs are created by the environment root, so nothing can be pushed before this |
| [2e](#step-2e---build-tag-and-push-the-ten-images) | Builds, tags, pushes and records digests | Produces the `image_digests` map the environment apply consumes |
| [2f](#step-2f---refuse-a-release-on-a-critical-or-high-registry-finding) | Reads each image's registry scan | The only window where the finding exists and no task definition names the image yet |
| [3](#step-3---provision-the-environment) | Plans and applies the whole environment | Consumes `image_tag` and the digests from 2e |

**Nine of the ten run as ECS tasks.** `ui` is published to the SPA origin bucket in Step 6 and no
task definition names it, which is why the digest map assembled at the end of this step carries nine
entries rather than ten. In `prod` that map is not optional: `infra/modules/ecs-service` validates
`image_uri` and rejects a mutable tag when `environment == "prod"`, so a production apply fails at
plan time unless every one of the nine task artifacts has a digest here.

> The blocks in this step and in Steps 6 and 7 are written to run under `set -euo pipefail` in one
> shell, in the order given, and several end in `exit 1` so a failed check stops the sequence rather
> than letting a later step build on it. Paste them into a script, or run them in a subshell -- an
> `exit 1` pasted into an interactive login shell closes it.

These ten are what this repository **builds**. The registry holds one more -- the pinned telemetry
collector this repository does not build -- and populating it is [Step 2b](#step-2b---mirror-the-pinned-telemetry-collector-image),
which is required before Step 6 rather than optional.

```bash
# WHAT: builds all nine Maven modules and runs the Java validation and test gates.
# WHY : Assumptions: Checkstyle is bound to Maven validate and common-lib is a dependency of the
#       services, so building only one image can miss a shared-kernel failure.
mvn -B -f services/pom.xml clean verify
```

```bash
# WHAT: installs the locked UI dependency graph, then type-checks, lints, tests and bundles the SPA.
# WHY : Alternatives Considered: npm install was rejected because it may resolve versions outside
#       package-lock.json; npm ci refuses lock drift and makes the built bundle reproducible.
# WHY : Assumptions: these four script names are the ones ui/package.json actually declares. Script
#       names are owned by that manifest, not by this runbook -- confirm them there rather than
#       guessing, because npm reports a missing script as an error that reads like a build failure.
npm --prefix ui ci
npm --prefix ui run typecheck
npm --prefix ui run lint
npm --prefix ui run test
npm --prefix ui run build
```

The ETL gates run in an environment **of their own**, created here. It is not the repository `.venv`
that runs the COBOL parity suite, and the two cannot be merged: `tests/requirements-test.txt` pins
`cryptography==49.0.0` while `data-migration/requirements.txt` pins `cryptography==50.0.0`, and both
manifests are installed with `--require-hashes`, so one interpreter cannot satisfy both. Installing
either into the other's environment silently replaces a hash-locked pin the other depends on.

```bash
# WHAT: create the ETL's own virtual environment, install its hash-locked dependency and build
#       closures, then install the package itself.
# WHY : Assumptions: `--without-pip` is required rather than preferred. This image ships a Python
#       with the `ensurepip` payload removed, so a plain `python3 -m venv` aborts with
#       "Command '... -m ensurepip ...' returned non-zero exit status 1" and leaves an unusable
#       directory behind. Creating the environment without pip and bootstrapping pip explicitly is
#       the form that works on a host with or without `ensurepip`.
# WHY : Assumptions: the four commands are ordered and the order is load-bearing. The dev closure
#       carries the runtime closure by include, so it is installed first; the build closure pins the
#       PEP 517 backend by digest, so it is installed before anything is built; and the package is
#       installed LAST because `data-migration/pyproject.toml` deliberately puts no source directory
#       on pytest's import path -- the suite imports the INSTALLED distribution, so a packaging
#       defect fails here rather than in a deployment.
# WHY : Assumptions: `--no-build-isolation --no-deps` is what makes the build use the backend just
#       pinned by digest instead of resolving one from the network, and stops the install from
#       re-resolving the dependency closure the two hash-locked manifests already fixed. Without this
#       step there is no `carddemo_migration` module and no `carddemo-migrate` console script at all:
#       the package is src-layout, so nothing is importable from the checkout.
# WHY : Trade-offs: `.venv` is matched by `.gitignore` at any depth, so this directory cannot be
#       committed. It is deliberately inside `data-migration/` rather than in a temporary directory
#       so that a later step in this runbook, and the data-migration runbook, can name one path.
python3 -m venv data-migration/.venv --without-pip
curl -sSf https://bootstrap.pypa.io/get-pip.py | data-migration/.venv/bin/python -
data-migration/.venv/bin/python -m pip install --require-hashes -r data-migration/requirements-dev.txt
data-migration/.venv/bin/python -m pip install --require-hashes -r data-migration/requirements-build.txt
data-migration/.venv/bin/python -m pip install --no-build-isolation --no-deps ./data-migration
```

```bash
# WHAT: creates the repository-root environment and puts pip inside it.
# WHY : Assumptions: the environment lives at the REPOSITORY ROOT rather than inside
#       data-migration/, because that is where every other document in this repository already
#       points -- `MIGRATION_README.md`, `data-migration/README.md` §4, `tests/README.md` §3 and
#       [data-migration.md](data-migration.md) all name the repository `.venv`, and a second
#       location documented here alone would be the one an operator gets wrong.
#       `.gitignore` already ignores `.venv/`, so nothing this creates is committable.
# WHY : Trade-offs: that one environment holds ONE of two closures at a time, and the install below
#       is therefore not skippable on the grounds that the directory already exists. The ETL closure
#       and the parity oracle's `tests/requirements-test.txt` closure share sixteen distributions and
#       agree on fifteen of them; they disagree on `cryptography`, which the ETL pins at 50.0.0 and
#       the oracle at 49.0.0. Each documented block reinstalls its own hash-locked closure first, so
#       the order the two are run in does not matter -- but running the gates without the install
#       does.
# WHY : Assumptions: an environment is required rather than advisable -- a current system Python is
#       PEP 668 externally managed and refuses a direct install with
#       "externally-managed-environment".
# WHY : Trade-offs: `--without-pip` with an explicit bootstrap is used unconditionally rather than
#       only as a fallback. On an image whose apt pip seed was removed, plain `python3 -m venv .venv`
#       fails with a non-zero `ensurepip` status and leaves a half-built directory that the next
#       command then reports as a missing interpreter; `tests/README.md` §3 records the same two
#       lines for the same reason. On an image that does ship `ensurepip` these two commands are
#       simply equivalent to the one, so the sequence works on both rather than branching on the
#       image.
# WHY : Trade-offs: the bootstrap script itself is fetched rather than pinned, so the pip that
#       installs the hash-locked closure is the current release. That is accepted because the
#       closure it installs is verified artifact by artifact in the next command, which is the
#       property that matters -- and it is the same bootstrap the existing suite documents.
python3 -m venv .venv --without-pip
curl -fsSL https://bootstrap.pypa.io/get-pip.py | ./.venv/bin/python
./.venv/bin/python -m pip --version
```

### Step 2a - Provision the registry and resolve its addresses

The ten repositories are Terraform-managed, so they exist only after an apply, and a push to a
repository that does not exist fails with `name unknown`. Complete Step 3's export block and its
backend-enabled `init` before this sub-step, then return here: `init` is idempotent, so Step 3
re-running it costs nothing, and `plan` requires a value for every no-default variable even when it
is narrowed with `-target`. Step 2c's Lambda archives must also already exist -- the Prerequisites
block above builds them, and `deploy.yml` builds them immediately before this same initialization.

```bash
# WHAT: creates the ten Terraform-managed ECR repositories ahead of any push, then reads each
#       repository's registry address and the registry hostname they share.
# WHY : Alternatives Considered: creating the repositories with ad-hoc `aws ecr create-repository`
#       calls. Rejected because the full plan in Step 3 would then discover unmanaged resources it
#       neither created nor configured -- scan-on-push, tag immutability and the lifecycle policy
#       all live in module.ecr -- so a narrowed apply is what establishes them under the same state.
# WHY : Assumptions: the addresses are read from `registry.repository_urls` rather than from a
#       top-level ecr_repository_urls output, because each root publishes ONE aggregate output per
#       module and `registry` is the whole ecr module. `jq -e` is used so an absent key exits
#       non-zero instead of yielding an empty string that would later push to `:<commit-sha>` with
#       no repository in front of it.
# WHY : Assumptions: the registry hostname is derived from the first repository URL rather than
#       composed from an account identifier and a Region, so no account-identifying value is typed
#       into this procedure or its transcript.
# WHY : Trade-offs: the saved plan is named `ecr.tfplan`. A narrowed plan carries resolved state
#       data like any other, and `.gitignore` L326-L327 matches on the `.tfplan` suffix.
# WHY : Trade-offs: the four commands are chained rather than listed, where `deploy.yml` gets the
#       same property from `set -euo pipefail`. That option is not used here because this runbook is
#       pasted into an operator's own interactive shell, which `set -e` would exit on the first
#       failure. Chaining matters most between plan and apply: `ecr.tfplan` persists, so a plan that
#       fails followed by an unconditional apply would apply the PREVIOUS run's saved plan.
root="infra/envs/<env>"
terraform -chdir="$root" plan -input=false -var-file=terraform.tfvars -target=module.ecr -out=ecr.tfplan &&
  terraform -chdir="$root" apply -input=false ecr.tfplan &&
  repositories="$(terraform -chdir="$root" output -json registry | jq -e '.repository_urls')" &&
  registry="$(jq -r 'to_entries[0].value | split("/")[0]' <<<"$repositories")"
```

```bash
# WHAT: installs the hash-locked ETL development closure, then the pinned build backend, then the
#       ETL distribution itself.
# WHY : Assumptions: `--require-hashes` makes pip verify every resolved artifact against the sha256
#       the manifest records and REJECT any unpinned or unhashed requirement, including transitive
#       ones. It is used here as a verified property of these two manifests -- both are
#       hash-complete -- rather than as a habit: the flag fails closed against a merely
#       version-pinned file.
# WHY : Assumptions: `requirements-dev.txt` INCLUDES the runtime manifest rather than restating it
#       and adds the pinned Ruff, pytest and coverage releases, so one install produces the whole
#       lint-and-test closure and the two files cannot disagree about a version they both hold.
# WHY : Assumptions: the distribution is installed rather than put on the import path. It is a src
#       layout, so `import carddemo_migration` cannot resolve from the checkout, and that is
#       deliberate -- the tests then exercise the artifact the container runs instead of the source
#       tree, which is how an unpackaged subpackage or a missing console entry point is caught here
#       rather than in the container.
# WHY : Assumptions: the install is NOT editable. `pip install -e` links the source tree back onto
#       the import path, which defeats the property above and makes one committed assertion fail --
#       `test_the_money_query_refuses_to_guess_where_it_lives` expects the package-relative lookup of
#       `sql/verify/money_totals.sql` to be refused, and it is refused only when the package is
#       imported from site-packages rather than from beside the `sql` directory.
#       `.github/workflows/services-ci.yml` installs the same non-editable way.
# WHY : Assumptions: `--no-build-isolation` reuses the hash-locked backend just installed instead of
#       resolving an unpinned one, and `--no-deps` keeps the closure exactly as the first command
#       fixed it -- without it pip may fetch an unhashed artifact and undercut that closure.
# WHY : Assumptions: the path carries an explicit leading `./`. The bare token `data-migration` is a
#       legal package-index name, so pip would resolve an unrelated project from PyPI instead of
#       this directory.
./.venv/bin/python -m pip install --require-hashes -r data-migration/requirements-dev.txt
./.venv/bin/python -m pip install --require-hashes -r data-migration/requirements-build.txt
./.venv/bin/python -m pip install --no-build-isolation --no-deps ./data-migration
```

```bash
# WHAT: validates and tests the Python migration package before its image is built, and proves its
#       documented invocation resolves.
# WHY : Assumptions: the ETL is the boundary that decodes fixed-width and packed data, so syntax
#       success without its codec and loader tests is not sufficient evidence.
# WHY : Assumptions: both tools come from the ETL environment created above. Neither is present in
#       the repository `.venv` -- `./.venv/bin/ruff` does not exist there at all -- so running them
#       from it fails with "No such file or directory", which reads as a missing tool rather than as
#       the wrong environment.
data-migration/.venv/bin/ruff check data-migration
data-migration/.venv/bin/python -m pytest data-migration/tests
data-migration/.venv/bin/carddemo-migrate --help
```

**Note**: the ETL is invoked as `data-migration/.venv/bin/python -m carddemo_migration.<module>`, or
equivalently through the `carddemo-migrate` console script the wheel declares. The available
subcommands and their arguments are owned by `data-migration/src/carddemo_migration/cli.py` and
documented in `data-migration/README.md`; the `--help` call above prints the authoritative list, so
confirm a verb there rather than assuming one. Loading and verifying the data is a separate procedure
with its own ordering and checks -- follow [data-migration.md](data-migration.md) for it rather than
driving the ETL from this runbook.

### Step 2a - Set the twelve deployment inputs

Each environment root declares twelve variables non-nullable with no default, so **no** `plan`
addressing that root can run until every one is set -- including the targeted registry plan two
sub-steps below, and including a destroy plan. They are absent from `terraform.tfvars` deliberately:
an ARN and a hostname belong to a deployment, not to the repository, and none of them is a secret.

The registry and the ten repository addresses are Terraform-managed, so they must exist before
anything is pushed and before Docker can authenticate to them. Provision the registry alone with a
saved targeted plan, read the addresses back from state, and only then log in. This is the same
ordering `deploy.yml` uses and for the same reason: creating repositories with ad-hoc CLI calls leaves
Terraform discovering unmanaged resources on the first full plan.

**The twelve non-defaulted root inputs and the release tag are set first.** A targeted plan is still
a plan, so it evaluates every root variable before it prunes the graph, and a root variable with no
default and no value stops it. Step 3 documents what each of the twelve is and where its value comes
from; the block below is where they are exported.

```bash
# WHAT: fixes the immutable release tag and supplies the twelve root inputs no committed variable
#       file can carry, before the first Terraform command of the sequence runs.
# WHY : Refactoring Rationale: this block used to sit in Step 3, after the targeted registry plan in
#       this step. Following the runbook in order therefore reached `plan -target=module.ecr` with
#       none of the twelve set, and Terraform stopped with "No value for required variable" -- so the
#       documented deploy could not proceed past its first Terraform command. deploy.yml avoids the
#       same trap by composing all twelve into deployment.auto.tfvars.json BEFORE its init; this is
#       the operator-shell equivalent, placed where the ordering requires it.
# WHY : Assumptions: the reviewed commit is the tag. A rollback has to name the bytes it wants, and
#       a commit is the only identifier that is both stable and traceable to a review.
# WHY : Assumptions: the repositories are IMMUTABLE, so a re-push of an existing tag is REFUSED by
#       the registry rather than silently repointing it. If a build has to be repeated for the same
#       commit, the earlier images are already published and correct -- re-read their digests below
#       instead of rebuilding. Do not "fix" the refusal by inventing a suffix.
# WHY : Alternatives Considered: publishing `latest`. Rejected because a mutable-only tag leaves an
#       ECS task definition unable to name the exact bytes a rollback needs, and it is the reason
#       the repositories are immutable in the first place.
export CARDDEMO_IMAGE_TAG="$(git rev-parse --verify HEAD)"
printf '%s' "$CARDDEMO_IMAGE_TAG" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$'
export TF_VAR_image_tag="$CARDDEMO_IMAGE_TAG"

# WHY : Assumptions: the ALB certificate is REGIONAL and the SPA certificate must be issued in
#       us-east-1, because CloudFront reads a viewer certificate only from that region. Two
#       certificates are needed rather than one for that reason alone, and passing a regional ARN
#       to the distribution -- or a us-east-1 ARN to a load balancer in another region -- fails at
#       apply after other resources have already been created.
# WHY : Assumptions: no listener PRIVATE KEY appears here or anywhere else in this runbook. Each
#       online task mints its own key pair and self-signed certificate at startup
#       (config/docker/generate-listener-material.sh), so the only certificate an operator supplies
#       is the load balancer's own -- the one hop whose peer actually verifies it.
export TF_VAR_alb_certificate_arn="<regional-alb-certificate-arn>"
export TF_VAR_internal_service_domain_name="<internal-service-name>"
export TF_VAR_cloudfront_acm_certificate_arn="<us-east-1-spa-certificate-arn>"
export TF_VAR_cloudfront_aliases='["<spa-hostname>"]'
export TF_VAR_github_repository="<owner>/<repo>"
export TF_VAR_github_oidc_provider_arn="<oidc-provider-arn>"

# WHY : Assumptions: cloudfront_api_connect_src_origins is set to an EMPTY list for every plan up to
#       and including the environment apply, because the API origin does not exist until that apply
#       creates it. The variable is required and non-nullable by design -- a content-security control
#       a root could omit silently would not be a control -- so a value has to be supplied now, and
#       the empty list is the fail-closed one: it yields `connect-src 'self'` and permits no
#       cross-origin call at all. Step 3 narrows it to the real origin and re-applies before the SPA
#       is published; publishing the SPA against the empty value would ship a client that cannot
#       call its own API.
export TF_VAR_cloudfront_api_connect_src_origins='[]'

# WHY : Assumptions: both of these name an account-scoped resource this package does not create:
#       the permissions boundary is the organisation's own IAM guardrail, and the mask key is the
#       HMAC material the card and reporting services derive their masking from. Neither is a secret
#       VALUE -- both are ARNs of things that hold one -- which is why they belong here rather than
#       in Secrets Manager lookups, and why they are absent from terraform.tfvars.
export TF_VAR_permissions_boundary_arn="<iam-permissions-boundary-arn>"
export TF_VAR_mask_hmac_secret_arn="<mask-hmac-secret-arn>"
export TF_VAR_mask_hmac_secret_kms_key_arn="<mask-hmac-cmk-arn>"

# WHY : Assumptions: at least one alarm recipient is REQUIRED, in both environments, and it is
#       supplied here rather than in terraform.tfvars because an on-call or team address is
#       personal data this repository does not carry. Both roots declare the input with no
#       default and refuse an empty list, so omitting it stops `plan` -- which is deliberate:
#       previously both tfvars files set it to `[]`, and the environment then provisioned a
#       notification topic, thirteen alarms and every alarm action pointing at it with ZERO
#       SUBSCRIBERS. Every alarm fired correctly into nothing, and because the dashboards,
#       alarms and topic all existed the gap was invisible until an incident was missed.
# WHY : Trade-offs: an email subscription is not live until the recipient CONFIRMS it, so this
#       export provisions the target but does not by itself complete delivery. The confirmation
#       step follows the apply and is listed with the post-apply checks in Step 3.
export TF_VAR_alarm_email_endpoints='["<oncall-address>"]'

# WHAT: supplies a placeholder digest for each of the nine task artifacts, for the registry pass
#       only.
# WHY : Assumptions: image_digests has a default of {} and would not by itself stop a plan, but the
#       nine placeholders are set anyway because deploy.yml sets them at the same point and for the
#       same reason: a `prod` root composes every task image URI by digest, and a targeted plan that
#       evaluated that composition against an empty map would fail on the immutability validation
#       rather than on anything to do with the registry. A syntactically valid digest that names no
#       image is safe here because no task definition is in the targeted graph.
# WHY : Assumptions: these values are REPLACED, not merged, by the real digests read back after the
#       push. The assembly at the end of this step writes them into deployment.auto.tfvars.json,
#       which Terraform loads at a HIGHER precedence than a TF_VAR_ environment variable -- so the
#       real map wins. The export is unset there as well, so the precedence rule is not the only
#       thing standing between a placeholder and a task definition.
export TF_VAR_image_digests="$(jq -nc --arg d "sha256:$(printf '0%.0s' $(seq 64))" '
  ["auth-service","account-service","card-service","transaction-service","reference-service",
   "batch-service","authorization-service","reporting-service","data-migration"]
  | map({key:., value:$d}) | from_entries')"
```

```bash
# WHAT: initialises the environment root against the bootstrapped remote state, supplying all four
#       values the partial backend leaves unset.
# WHY : Assumptions: infra/envs/<env>/backend.tf declares the S3 backend PARTIALLY -- it fixes only
#       the state key -- so bucket, region, dynamodb_table and kms_key_id must be supplied on the
#       command line. Omitting them makes init prompt for the bucket interactively, and with
#       -input=false it fails outright; there is no default that would silently do the right thing.
# WHY : Assumptions: every value comes from the Step 1 bootstrap outputs, which is the only place
#       they are authoritative. Do not retype them from memory: a wrong dynamodb_table produces an
#       apply that takes no lock, and a wrong kms_key_id produces one that cannot decrypt state.
# WHY : Alternatives Considered: committing a fully-specified backend block per environment.
#       Rejected because the bucket, table and key are account-scoped bootstrap output, so hard-
#       coding them would bind the roots to one account and make the bootstrap unusable elsewhere.
# WHY : Assumptions: the Lambda archives are built FIRST. They are build output rather than tracked
#       files, and the roots evaluate filebase64sha256 over them, so any plan -- including the
#       targeted one below -- fails on a missing path before it reaches the registry. Step 2c
#       documents the builder in full; this line is the same call, placed where the ordering
#       requires it, and it is what deploy.yml does immediately before its own init.
export AWS_REGION="<aws-region>"
export CARDDEMO_ENV="<env>"                      # dev | prod
root="infra/envs/${CARDDEMO_ENV}"

python3 infra/lambda/build_packages.py

bootstrap="$(terraform -chdir=infra/bootstrap output -json)"
export TF_STATE_BUCKET="$(jq -er '.state_bucket_name.value' <<<"$bootstrap")"
export TF_STATE_REGION="$(jq -er '.aws_region.value' <<<"$bootstrap")"
export TF_STATE_LOCK_TABLE="$(jq -er '.state_lock_table_name.value' <<<"$bootstrap")"
export TF_STATE_KMS_KEY_ID="$(jq -er '.state_kms_key_arn.value' <<<"$bootstrap")"

terraform -chdir="$root" init -input=false -lockfile=readonly \
  -backend-config="bucket=$TF_STATE_BUCKET" \
  -backend-config="region=$TF_STATE_REGION" \
  -backend-config="dynamodb_table=$TF_STATE_LOCK_TABLE" \
  -backend-config="kms_key_id=$TF_STATE_KMS_KEY_ID"
```

> The four names read above are `state_bucket_name`, `aws_region`, `state_lock_table_name` and
> `state_kms_key_arn`, and they are owned by `infra/bootstrap/outputs.tf` -- note that the region is
> published as `aws_region`, not `state_region`. `jq -er` exits non-zero on a missing key rather than
> exporting an empty string that `init` would then report as a malformed backend.

```bash
# WHAT: creates the ten Terraform-managed ECR repositories and nothing else, then records their
#       registry addresses as a flat name-to-URL map.
# WHY : Assumptions: -target is legitimate here and only here. The registry is a true prerequisite
#       of the image push, and the push is a prerequisite of the digests the full plan consumes, so
#       the graph genuinely has to be applied in two passes. Every later apply in this runbook is
#       untargeted.
# WHY : Assumptions: the addresses are read from the aggregate `registry` output. The roots publish
#       one aggregate per module, so `registry.repository_urls` is the map; there is no top-level
#       ecr_repository_urls output and a read of one fails after the targeted apply has run.
terraform -chdir="$root" plan -input=false -target=module.ecr -out=ecr.tfplan
terraform -chdir="$root" apply -input=false ecr.tfplan
terraform -chdir="$root" output -json registry | jq -e '.repository_urls' > ecr-repositories.json

# WHAT: derives the registry host the docker login below needs from any one repository address.
# WHY : Assumptions: the host is the portion before the first slash of a repository URL, so it is
#       derived rather than retyped -- an account id or region typed by hand authenticates to a
#       registry the push then cannot reach.
export CARDDEMO_REGISTRY="$(jq -er 'to_entries[0].value | split("/")[0]' ecr-repositories.json)"
```

```bash
# WHAT: authenticates Docker to the target ECR registry without placing the password in argv.
# WHY : Alternatives Considered: passing the password as a command argument was rejected because
#       process listings and shell history retain it; password-stdin confines it to the pipe.
# WHY : Assumptions: it runs AFTER the registry exists and its host has been derived above. Logging
#       in to a registry that has not been created yet succeeds against the account's ECR endpoint
#       and then fails on the first push with "repository does not exist", which reads like an
#       authentication problem and is not one.
aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$CARDDEMO_REGISTRY"
```

Repository names are `<name_prefix>-<environment>/<artifact>` -- for example
`carddemo-dev/auth-service` -- and every repository is created `IMMUTABLE`. The tag every image
carries is `$CARDDEMO_IMAGE_TAG`, fixed to the reviewed commit at the start of this step, and it can
be pushed exactly once.

```bash
# WHAT: writes the ten-row build manifest: artifact name, Dockerfile, and build context.
# WHY : Assumptions: the eight service builds take the repository ROOT as their context and not
#       services/<name>. Each service Dockerfile copies services/common-lib, config/checkstyle and
#       config/docker, all of which sit outside the service directory -- building from the service
#       directory fails on the first COPY. ui and data-migration take their own directories because
#       they reference nothing above themselves.
# WHY : Assumptions: this manifest is the same ten rows deploy.yml builds. Keep the two identical;
#       a name here that no repository matches fails the jq lookup in the loop below rather than
#       pushing to a wrong address.
cat > carddemo-images.tsv <<'IMAGES'
auth-service	services/auth-service/Dockerfile	.
account-service	services/account-service/Dockerfile	.
card-service	services/card-service/Dockerfile	.
transaction-service	services/transaction-service/Dockerfile	.
reference-service	services/reference-service/Dockerfile	.
batch-service	services/batch-service/Dockerfile	.
authorization-service	services/authorization-service/Dockerfile	.
reporting-service	services/reporting-service/Dockerfile	.
ui	ui/Dockerfile	ui
data-migration	data-migration/Dockerfile	data-migration
IMAGES
```

```bash
# WHAT: builds, tags, pushes and reads back the digest of each of the ten images, accumulating the
#       nine task digests into image-digests.json.
# WHY : Assumptions: the digest is READ BACK from the registry rather than taken from the local
#       build. ECR computes the manifest digest on receipt, and that is the value a task definition
#       must name; a locally-computed one can differ and would produce a task definition pointing at
#       nothing.
# WHY : Assumptions: `ui` is pushed but deliberately excluded from the map. Nothing runs it as a
#       task -- Step 6 publishes the built bundle to the SPA origin bucket -- so a digest for it
#       would configure nothing, and both environment roots refuse `ui` as an image_digests key.
# WHY : Alternatives Considered: one long docker build --tag straight at the registry address, with
#       no local name. Rejected because the intermediate local tag is what lets an operator inspect
#       or scan the exact artifact before it leaves the machine.
# WHY : Trade-offs: the loop stops at the first failure rather than continuing and reporting at the
#       end. A partially published release is easier to reason about when it stops at the artifact
#       that failed.
set -euo pipefail
digests='{}'
while IFS=$'\t' read -r name dockerfile context; do
  repository="$(jq -er --arg name "$name" '.[$name]' ecr-repositories.json)"
  image="${repository}:${CARDDEMO_IMAGE_TAG}"

  docker build --file "$dockerfile" --tag "carddemo/${name}:${CARDDEMO_IMAGE_TAG}" "$context"
  docker tag "carddemo/${name}:${CARDDEMO_IMAGE_TAG}" "$image"
  docker push "$image"

  repository_name="${repository#*/}"
  digest="$(aws ecr describe-images \
    --region "$AWS_REGION" \
    --repository-name "$repository_name" \
    --image-ids "imageTag=${CARDDEMO_IMAGE_TAG}" \
    --query 'imageDetails[0].imageDigest' --output text)"
  printf '%s %s %s\n' "$name" "$repository_name" "$digest"

  if [ "$name" != "ui" ]; then
    digests="$(jq --arg name "$name" --arg digest "$digest" '. + {($name): $digest}' <<<"$digests")"
  fi
done < carddemo-images.tsv
jq -c . <<<"$digests" > image-digests.json
```

```bash
# WHAT: waits for each pushed image's scan-on-push result and refuses the release on a CRITICAL or
#       HIGH finding.
# WHY : Assumptions: scan_on_push is enabled on every repository by infra/modules/ecr, but it runs
#       AFTER the push succeeds, so it is not a push-blocking control. This block is the consumer
#       that makes it one: the images exist so they can be scanned, and no task definition names
#       them yet, so refusing here costs a release rather than an outage.
# WHY : Assumptions: the waiter is allowed to fail. It exits non-zero for any terminal state other
#       than COMPLETE, and the status is then read explicitly so each state gets its own decision.
# WHY : Trade-offs: UNSUPPORTED_IMAGE is reported and allowed through. There is no finding set to
#       gate on, and refusing would let a registry capability rather than a vulnerability block a
#       release; the compensating control is that every base image is digest-pinned and reviewed.
# WHY : Alternatives Considered: an override flag so an operator can deploy past a finding.
#       Deliberately absent -- an override that exists is an override that is used, and the remedy
#       here is to advance a base-image pin and rebuild, which is a reviewed change not a waived one.
blocking=0
while IFS= read -r name; do
  repository="$(jq -er --arg name "$name" '.[$name]' ecr-repositories.json)"
  repository_name="${repository#*/}"

  aws ecr wait image-scan-complete --region "$AWS_REGION" \
    --repository-name "$repository_name" \
    --image-id "imageTag=${CARDDEMO_IMAGE_TAG}" || true

  findings="$(aws ecr describe-image-scan-findings --region "$AWS_REGION" \
    --repository-name "$repository_name" \
    --image-id "imageTag=${CARDDEMO_IMAGE_TAG}" --output json 2>/dev/null || echo '{}')"
  status="$(jq -r '.imageScanStatus.status // "UNKNOWN"' <<<"$findings")"
  critical="$(jq -r '.imageScanFindings.findingSeverityCounts.CRITICAL // 0' <<<"$findings")"
  high="$(jq -r '.imageScanFindings.findingSeverityCounts.HIGH // 0' <<<"$findings")"
  medium="$(jq -r '.imageScanFindings.findingSeverityCounts.MEDIUM // 0' <<<"$findings")"
  printf '%s:%s status=%s critical=%s high=%s medium=%s\n' \
    "$repository_name" "$CARDDEMO_IMAGE_TAG" "$status" "$critical" "$high" "$medium"

  case "$status" in
    COMPLETE)
      if [ "$critical" -gt 0 ] || [ "$high" -gt 0 ]; then blocking=1; fi ;;
    UNSUPPORTED_IMAGE)
      : ;;
    *)
      blocking=1 ;;
  esac
done < <(jq -r 'keys[]' ecr-repositories.json)

if [ "$blocking" -ne 0 ]; then
  echo "refusing to deploy: blocking image scan findings above" >&2
  exit 1
fi
```

```bash
# WHAT: asserts the map holds exactly the nine admissible task artifacts, then hands it to the
#       environment root as an auto-loaded variable file.
# WHY : Assumptions: the assertion is what makes the production path work. infra/modules/ecs-service
#       refuses a mutable tag when environment == "prod", so a map missing even one of the nine
#       fails the production plan; catching that here names the missing artifact instead of leaving
#       an operator to read a validation error against an image_uri.
# WHY : Assumptions: the filename suffix matters. Terraform auto-loads *.auto.tfvars.json from the
#       root directory, so no -var-file argument is needed and no later command can forget it.
#       .gitignore excludes *.auto.tfvars.json, so the file cannot be committed.
# WHY : Alternatives Considered: passing nine -var arguments on the plan command line. Rejected
#       because the same nine would then have to be repeated, identically, on every plan and apply
#       in this runbook -- and one omission produces a task definition on a mutable tag.
expected='["account-service","auth-service","authorization-service","batch-service","card-service","data-migration","reference-service","reporting-service","transaction-service"]'
jq -e --argjson expected "$expected" '(keys | sort) == ($expected | sort)' image-digests.json \
  || { echo "image-digests.json does not hold exactly the nine task artifacts" >&2; exit 1; }
jq -e 'all(.[]; test("^sha256:[a-f0-9]{64}$"))' image-digests.json \
  || { echo "image-digests.json holds a value that is not a lowercase sha256 digest" >&2; exit 1; }

jq -n --argjson digests "$(cat image-digests.json)" '{image_digests: $digests}' \
  > "${root}/deployment.auto.tfvars.json"

# WHAT: removes the placeholder digest map exported for the registry pass.
# WHY : Assumptions: the file written above already outranks a TF_VAR_ environment variable, so this
#       is belt and braces rather than the mechanism. It is here because the two would otherwise
#       both be live for the rest of the session, and an operator who later deletes the file -- as
#       deploy.yml does at the end of its run -- would silently fall back to nine digests that name
#       no image. Unsetting it makes that fallback impossible.
unset TF_VAR_image_digests
```

> `deployment.auto.tfvars.json` carries no secret. It holds nine content digests and nothing else,
> and the Step 3 variable table below lists the values that are supplied separately.

### Step 2b - Nothing to supply for issuer reachability

Refactoring Rationale: this paragraph recorded the pinned AWS Distro for OpenTelemetry collector
mirror, and its eleventh ECR repository, as **withdrawn**. That record was wrong against the shipped
modules and is corrected here rather than carried forward, because an operator who believed it would
skip a step the apply depends on. `infra/modules/ecs-service` declares
`enable_telemetry_collector` with `default = true` and runs the collector as a sidecar in **every**
task; `infra/modules/network` enumerates the application tier's egress instead of allowing
`0.0.0.0/0`; and Amazon ECR Public has neither an interface endpoint nor a managed prefix list -- so
without the mirror no task can pull its sidecar and none reaches `RUNNING`. Both environment roots
name the repository (`local.telemetry_collector_repository = "aws-otel-collector"`) and resolve the
image from `module.ecr.repository_urls[...]`, and `infra/modules/ecr` provisions it from the separate
`third_party_mirror_repository_names` input -- which is precisely how the **ten-deployable** count of
AAP §0.4.1.6 stays assertable while the cache stays visible: ten built plus one mirrored. Populating
it is [Step 2b](#step-2b---mirror-the-pinned-telemetry-collector-image), and it is required before
Step 6 rather than optional.

Refactoring Rationale: this step also carried an operator procedure for **`identity_provider_egress_cidrs`**
-- derive the Region's public AWS address ranges from `ip-ranges.amazonaws.com`, aggregate them, and
set them as application-tier egress so a task could resolve a public Cognito issuer. That whole
procedure is **deleted, and the input no longer exists.** `infra/modules/network` withdrew it because
its `0.0.0.0/0` default was the security defect: an operator following the procedure was being asked
to open the application tier to a public address range in order to reach one endpoint. There is
nothing to supply here, and an attempt to set the name in `terraform.tfvars` is discarded silently by
Terraform as an undeclared variable, which is worse than an error.

**The sign-on path is served privately instead.** The VPC provisions **ten** interface endpoints --
`ecr.api`, `ecr.dkr`, `logs`, `secretsmanager`, `kms`, `sqs`, `states`, `ssm`, `xray` and
`cognito-idp` -- plus the S3 gateway endpoint. AAP §0.4.1.9 names eight; `xray` and `cognito-idp` are
documented additions beyond it, and each earns its place: `xray` keeps trace export off the public
path, and `cognito-idp` is what replaced the withdrawn egress rule. With private DNS enabled on the
`cognito-idp` endpoint, a task resolves the issuer, fetches OIDC discovery metadata and the JSON web
key set, and performs sign-on entirely inside the VPC.

Alternatives Considered: reaching the provider through the NAT gateway with no endpoint at all, which
needs no endpoint policy. Rejected because it puts unauthenticated sign-on traffic on the public path
and reintroduces the broad egress rule this replaced. The endpoint instead carries a per-endpoint
policy admitting exactly the unauthenticated OIDC discovery, key-set and sign-on operations, with
every administrative call left to the account-scoped shared policy -- so the private path is narrower
than the rule it replaced, not merely different.

Assumptions: the application tier's egress is therefore Aurora, the ten interface endpoints, the S3
gateway prefix list and the internal load balancer. Nothing outside the VPC, and no operator decision
at this point in the deployment.

```bash
# WHAT: supplies the inputs no committed variable file can carry.
# WHY : Assumptions: the ALB certificate is REGIONAL and the SPA certificate must be issued in
#       us-east-1, because CloudFront reads a viewer certificate only from that region. Two
#       certificates are needed rather than one for that reason alone, and passing a regional ARN
#       to the distribution -- or a us-east-1 ARN to a load balancer in another region -- fails at
#       apply after other resources have already been created.
# WHY : Assumptions: no listener PRIVATE KEY appears here or anywhere else in this runbook. Each
#       online task mints its own key pair and self-signed certificate at startup
#       (config/docker/generate-listener-material.sh), so the only certificate an operator supplies
#       is the load balancer's own -- the one hop whose peer actually verifies it.
export TF_VAR_alb_certificate_arn="<regional-alb-certificate-arn>"
export TF_VAR_internal_service_domain_name="<internal-service-name>"
export TF_VAR_cloudfront_acm_certificate_arn="<us-east-1-spa-certificate-arn>"
export TF_VAR_cloudfront_aliases='["<spa-hostname>"]'
# WHY : Assumptions: this starts EMPTY and is narrowed after the apply, because the API origin does
#       not exist until the apply creates it. An empty list yields `connect-src 'self'`, which is the
#       fail-closed value: it permits no cross-origin call at all, and the SPA is not published until
#       Step 6 has narrowed it. The variable's own validation refuses a wildcard, so there is no
#       first-pass value that would remove the second apply.
export TF_VAR_cloudfront_api_connect_src_origins='[]'
export TF_VAR_image_tag="<commit-sha>"
export TF_VAR_github_repository="<owner>/<repo>"
export TF_VAR_github_oidc_provider_arn="<oidc-provider-arn>"
# WHY : Assumptions: these last two were absent from this block although both are declared with
#       no default, so following it exactly still left `plan` refusing to run. Both name an
#       account-scoped resource this package does not create: the permissions boundary is the
#       organisation's own IAM guardrail, and the mask key is the HMAC material the card and
#       reporting services derive their masking from. Neither is a secret VALUE -- both are ARNs
#       of things that hold one -- which is why they belong here rather than in Secrets Manager
#       lookups, and why they are absent from terraform.tfvars.
export TF_VAR_permissions_boundary_arn="<iam-permissions-boundary-arn>"
export TF_VAR_mask_hmac_secret_arn="<mask-hmac-secret-arn>"
export TF_VAR_mask_hmac_secret_kms_key_arn="<mask-hmac-cmk-arn>"
# WHY : Assumptions: at least one alarm recipient is REQUIRED, in both environments, and it is
#       supplied here rather than in terraform.tfvars because an on-call or team address is
#       personal data this repository does not carry. Both roots declare the input with no
#       default and refuse an empty list, so omitting it stops `plan` -- which is deliberate:
#       previously both tfvars files set it to `[]`, and the environment then provisioned a
#       notification topic, thirteen alarms and every alarm action pointing at it with ZERO
#       SUBSCRIBERS. Every alarm fired correctly into nothing, and because the dashboards,
#       alarms and topic all existed the gap was invisible until an incident was missed.
#       Trade-offs: an email subscription is not live until the recipient CONFIRMS it, so this
#       export provisions the target but does not by itself complete delivery. The
#       confirmation step follows the apply and is listed with the post-apply checks below.
export TF_VAR_alarm_email_endpoints='["<oncall-address>"]'
```

Never publish only `latest`: a mutable-only tag prevents an ECS task definition from identifying the
exact bytes required for rollback.

```bash
# WHAT: builds, tags and pushes each of the ten images, prints the digest the registry recorded for
#       each, and accumulates the nine task images' digests into one map.
# WHY : Assumptions: the third field is the build CONTEXT and it is deliberately NOT the same for
#       every image. Each of the eight service Dockerfiles is written against the repository ROOT --
#       it copies services/<name>/pom.xml, the unpublished services/common-lib source,
#       config/checkstyle for the inherited documentation gate, and config/docker for the shared
#       entry point and the database trust-anchor installer -- so a narrower context leaves config/
#       outside the context entirely and the build fails at its first COPY of it. `ui` and
#       `data-migration` copy nothing from outside their own trees and each carries its own
#       .dockerignore at that context root, so those two keep the narrower context. A wrong context
#       is a build failure rather than a degraded image, which is why all three fields are stated.
# WHY : Trade-offs: the table is space-separated and read with the shell's default word splitting,
#       where the same table in deploy.yml is tab-separated. A tab is invisible in a document and
#       does not survive copying reliably; none of the three fields contains a space, so word
#       splitting is sufficient and the table stays readable.
# WHY : Assumptions: the table is fed on file descriptor 3 rather than on standard input, so a
#       command inside the loop that reads standard input cannot swallow the remaining rows and
#       silently publish fewer than ten images.
# WHY : Assumptions: each digest is read back from the registry with `describe-images` rather than
#       parsed out of the `docker push` transcript. ECR computed it, so reading it from the
#       repository is the one form that cannot disagree with what the registry holds.
# WHY : Trade-offs: each step in the loop body ends in `|| break` and the block closes on a count,
#       rather than the loop opening with `set -e`. A `while` loop under `set -e` is exactly where
#       that option is least useful -- it would exit the operator's interactive shell mid-publish --
#       and without the guards a failed build would be followed by a push of whatever bytes that tag
#       last pointed at. Breaking stops at the first failure and leaves the count short, so the
#       block exits non-zero here rather than leaving the short map for the gate below to catch.
tag="<commit-sha>"
digests='{}'
published=0
while read -r name dockerfile context <&3; do
  repository="$(jq -er --arg name "$name" '.[$name]' <<<"$repositories")" || break
  docker build --file "$dockerfile" --tag "${repository}:${tag}" "$context" || break
  docker push "${repository}:${tag}" || break
  digest="$(aws ecr describe-images --region "<aws-region>" \
    --repository-name "${repository#*/}" \
    --image-ids "imageTag=${tag}" \
    --query 'imageDetails[0].imageDigest' --output text)" || break
  printf '%s %s\n' "$name" "$digest"
  # ui is deliberately absent from the map; the block below this one explains why.
  if [ "$name" != "ui" ]; then
    digests="$(jq --arg name "$name" --arg digest "$digest" '. + {($name): $digest}' <<<"$digests")" || break
  fi
  published=$((published + 1))
done 3<<'IMAGES'
auth-service services/auth-service/Dockerfile .
account-service services/account-service/Dockerfile .
card-service services/card-service/Dockerfile .
transaction-service services/transaction-service/Dockerfile .
reference-service services/reference-service/Dockerfile .
batch-service services/batch-service/Dockerfile .
authorization-service services/authorization-service/Dockerfile .
reporting-service services/reporting-service/Dockerfile .
ui ui/Dockerfile ui
data-migration data-migration/Dockerfile data-migration
IMAGES
if [ "$published" -eq 10 ]; then
  echo "published $published images at tag $tag"
else
  echo "published only $published of 10 images at tag $tag; the failure is above this line" >&2
  false
fi
```

```bash
# WHAT: proves the digest map is complete and well-formed, then exports it for the Step 3 plan.
# WHY : Assumptions: the map holds NINE entries for TEN images, and the difference is deliberate
#       rather than an omission. `ui` is published to the SPA bucket and runs no ECS task, so a
#       digest for it would configure nothing; both roots restrict image_digests to exactly the
#       eight services plus data-migration and refuse any other key by name.
# WHY : Assumptions: each value is checked against ^sha256:[a-f0-9]{64}$ here because that is the
#       pattern both roots validate, so a truncated or upper-case digest fails beside the push that
#       produced it rather than at plan time.
# WHY : Trade-offs: production does not merely prefer digests. infra/modules/ecs-service refuses a
#       mutable tag there, so an artifact missing from this map fails the prod plan with
#       "production image_uri values must end in an immutable @sha256:<64 lowercase hex characters>
#       digest". An incomplete map is therefore a stopped release, not a silently weaker one.
# WHY : Assumptions: the value is exported as JSON. Terraform parses a TF_VAR_ value for a complex
#       type as HCL and HCL admits a JSON object, which is the same convention the
#       TF_VAR_cloudfront_aliases export in Step 3 uses for a list.
# WHY : Trade-offs: the check and the export are chained, so a map that fails the check is not
#       exported at all. Listing them as two statements would have run the export unconditionally
#       and, because `export` succeeds, left the block reporting 0 while carrying a rejected map
#       into Step 3 -- where dev would accept it and only prod would refuse.
jq -e 'length == 9 and all(.[]; test("^sha256:[a-f0-9]{64}$"))' <<<"$digests" &&
  export TF_VAR_image_digests="$digests"
```

```bash
# WHAT: waits for each pushed image's scan and refuses the release on a critical or high finding.
# WHY : Refactoring Rationale: infra/modules/ecr enables scan-on-push for every repository, but a
#       scan-on-push runs AFTER the push has already succeeded, so by itself it blocks nothing and
#       its findings had no reader. This is that reader. It runs after every push and before the
#       Step 3 apply, which is the one window in which a finding is both available and still free
#       to act on: the images exist so they can be scanned, and no task definition names them yet,
#       so refusing costs a release rather than an outage.
# WHY : Assumptions: the waiter is allowed to fail and the status is then read explicitly, so each
#       terminal state gets its own decision instead of all of them becoming one failure.
#       UNSUPPORTED_IMAGE is reported and allowed through, because there is no finding set to gate
#       on and refusing would let a registry capability rather than a vulnerability block a
#       release; every base image here is version-pinned and reviewed, which is the compensating
#       control. Any other non-COMPLETE status blocks, because a gate that cannot read its
#       evidence must not report a pass.
# WHY : Trade-offs: no override flag is offered. A newly published advisory against an unchanged
#       base image can block a release of unrelated code, and that is the accepted cost -- the
#       remedy is to advance the affected base-image pin and rebuild, which is a reviewed change
#       rather than a waived finding.
# WHY : Trade-offs: the loop accumulates a flag and the block ends in an explicit `false`, so a
#       refusal is a non-zero exit and not merely a message. Every repository is scanned before the
#       decision, which is deliberate -- stopping at the first blocking image would hide the other
#       nine findings and turn one rebuild into several. Ending on the `echo` alone would have
#       printed "refusing to apply" and still exited 0, which is the shape of gate this block is
#       written to be the opposite of.
blocking=0
for name in $(jq -r 'keys[]' <<<"$repositories"); do
  repository="$(jq -er --arg name "$name" '.[$name]' <<<"$repositories")"
  repository_name="${repository#*/}"
  aws ecr wait image-scan-complete --region "<aws-region>" \
    --repository-name "$repository_name" --image-id "imageTag=${tag}" || true
  findings="$(aws ecr describe-image-scan-findings --region "<aws-region>" \
    --repository-name "$repository_name" --image-id "imageTag=${tag}" --output json || echo '{}')"
  status="$(jq -r '.imageScanStatus.status // "UNKNOWN"' <<<"$findings")"
  critical="$(jq -r '.imageScanFindings.findingSeverityCounts.CRITICAL // 0' <<<"$findings")"
  high="$(jq -r '.imageScanFindings.findingSeverityCounts.HIGH // 0' <<<"$findings")"
  printf '%s:%s status=%s critical=%s high=%s\n' "$repository_name" "$tag" "$status" "$critical" "$high"
  case "$status" in
    COMPLETE) if [ "$critical" -gt 0 ] || [ "$high" -gt 0 ]; then blocking=1; fi ;;
    UNSUPPORTED_IMAGE) ;;
    *) blocking=1 ;;
  esac
done
if [ "$blocking" -eq 0 ]; then
  echo "image vulnerability gate: PASS"
else
  echo "refusing to apply: advance the affected base-image pin and rebuild" >&2
  false
fi
```

The deployment role needs `ecr:DescribeImageScanFindings` for the gate above, alongside the push
actions; the registry-actions table in Step 3 lists all four families together.

### Step 2b - Mirror the pinned telemetry collector image

`infra/modules/ecs-service` runs an AWS Distro for OpenTelemetry collector as a sidecar in every task
it registers: it scrapes each service's Actuator Prometheus endpoint on task loopback, receives OTLP
from the one-shot tasks, and exports metrics to CloudWatch and spans to X-Ray. Both environment roots
reference it from **this deployment's own registry**, at
`carddemo-<env>/aws-otel-collector:v0.49.0`, and that repository is the eleventh
`infra/modules/ecr` provisions -- the single entry of `third_party_mirror_repository_names`, held in
its own input so the ten deployables Step 2 builds stay an exactly asserted set.

Assumptions: the mirror exists because a task cannot reach the public registry. The application tier
holds no egress rule to any public destination, and Amazon ECR Public is not served by the `ecr.api`
and `ecr.dkr` interface endpoints, so a task pulling `public.ecr.aws/...` directly would fail to start
with no route to fix it.

**This step is not optional and it cannot be deferred past Step 6.** The sidecar is `essential`, so a
task whose collector cannot be pulled never reaches `RUNNING`; the failure surfaces as a
`CannotPullContainerError` on a stopped task rather than as anything Terraform reports. `ecs-service`
refuses a private collector reference with no repository ARN at plan time, but no plan can detect an
**empty** repository -- the ARN is present, the grant is issued, and there is simply nothing at that
tag -- so this step is the only control over that case.

Ordering: the repository must exist before anything can be pushed to it, which is the same
prerequisite the ten image pushes in Step 2 have. On a **first** deployment, run Step 3's
backend-enabled `init` and its `export` block first, create the registry alone with the targeted apply
below, then push the ten images and this mirror, then continue with the rest of Step 3. On a
**subsequent** deployment the repository already exists and only the push commands apply -- and they
are a no-op unless `local.telemetry_collector_image_tag` in the environment root has moved.

```bash
# WHAT: creates the artifact registry alone, then reads back the mirror repository's address.
# WHY : Assumptions: -target is used here deliberately and is not a workaround. The registry is the
#       one module whose resources must exist BEFORE the images the rest of the environment
#       references, so the alternative is a first apply that fails on task definitions naming images
#       that are not pushed yet.
# WHY : Assumptions: the address is read from the `registry` root output rather than composed from an
#       account identifier and a Region. Each root publishes one aggregate output per module, so the
#       map of logical artifact name to registry address is `registry.repository_urls`; a
#       hand-assembled address authenticates successfully and then targets a registry that holds
#       nothing.
env="<dev-or-prod>"
terraform -chdir="infra/envs/${env}" apply -target=module.ecr
collector_repository="$(terraform -chdir="infra/envs/${env}" output -json registry |
  jq -er '.repository_urls["aws-otel-collector"]')"
collector_repository_name="${collector_repository#*/}"
```

```bash
# WHAT: pulls the reviewed upstream collector by digest, tags it as the mirror's immutable tag, and
#       pushes it into the private repository.
# WHY : Assumptions: the tag is the UPSTREAM collector version, not this release's commit SHA. The
#       artifact is a third-party image this repository does not build, so tagging it with a CardDemo
#       commit would assert a provenance it does not have and oblige a re-push on every release of
#       unrelated code. It must match `local.telemetry_collector_image_tag` in the environment root,
#       which is what the task definition resolves.
# WHY : Assumptions: the SOURCE is named by digest and not by its version tag. An upstream release tag
#       is immutable by convention, and a convention is not a control; this is the one container
#       attached to every workload, so the pull names the reviewed OCI index digest and the tag is
#       used only as the mirror's label.
# WHY : Trade-offs: two `docker login` calls are needed rather than one. The public registry and the
#       private one are different registries with different credentials, and the public token is
#       issued only from us-east-1 regardless of where this deployment runs.
# WHY : Assumptions: `--platform linux/amd64` is explicit because that index carries an arm64
#       manifest as well, and the task definitions pin `X86_64`/`LINUX`. Mirroring from an arm64
#       workstation without the flag pushes the arm64 manifest under the same tag, which pulls
#       successfully and then fails inside the task with an exec-format error -- a failure that reads
#       as a broken image rather than as a wrong architecture. Confirm the index with
#       `docker buildx imagetools inspect "$upstream"` if the platform is in doubt.
collector_tag="v0.49.0"
collector_digest="sha256:d2bdfff2c377c3d71d78bd5d9ce9862fd535b12134a5739d87a07801297cf9fd"
upstream="public.ecr.aws/aws-observability/aws-otel-collector@${collector_digest}"
mirror="${collector_repository}:${collector_tag}"

aws ecr-public get-login-password --region us-east-1 |
  docker login --username AWS --password-stdin public.ecr.aws
aws ecr get-login-password --region "<aws-region>" |
  docker login --username AWS --password-stdin "<account-registry>"

docker pull --platform linux/amd64 "$upstream"
docker tag "$upstream" "$mirror"
docker push "$mirror"
```

```bash
# WHAT: reads the digest the registry now holds for that tag and records it as the collector's entry
#       in image_digests.
# WHY : Assumptions: the mirror's digest is NOT the upstream digest, so it must be read back rather
#       than reused. The copy travels through the local daemon, which resolves one platform manifest
#       out of the upstream multi-platform index -- the task definitions pin `X86_64`/`LINUX`, so that
#       manifest is the right one -- and the pushed object is therefore a different manifest with its
#       own digest.
# WHY : Assumptions: the key is `aws-otel-collector`, which both roots admit in `image_digests`
#       alongside the nine task images. Each root prefers a recorded digest and falls back to the
#       immutable tag, so a plan run before any mirror exists still resolves; recording the digest is
#       what makes the registered task definition state WHICH collector bytes ran.
# WHY : Trade-offs: re-pushing an existing tag is not a no-op. The repository is IMMUTABLE-tagged, so
#       the registry refuses it with `ImageTagAlreadyExistsException`; read the digest first and skip
#       the push commands above when one is already there. A `describe-images` failure naming any
#       other exception is a real failure -- an access denial or a throttle -- and must not be read as
#       "absent".
aws ecr describe-images --repository-name "$collector_repository_name"   --image-ids "imageTag=${collector_tag}"   --query 'imageDetails[0].imageDigest' --output text
export TF_VAR_image_digests='{"aws-otel-collector":"<mirror-digest>", ...}'
```

#### The application tier reaches no public destination, and identity resolves privately

Do **not** look for an egress input to configure here. `infra/modules/network` once carried
`identity_provider_egress_cidrs`, and that input and the security-group rule it keyed are both
withdrawn: the application group's permitted flows are enumerated in the module and admit Aurora on
5432, the interface endpoints on 443, the S3 gateway prefix list, and the internal load balancer --
nothing outside the VPC, and nothing an operator can widen from a `tfvars` file.

Assumptions: in-task issuer resolution still works, and that is why the input could be withdrawn
rather than merely defaulted empty. The module provisions **ten** interface endpoints -- `ecr.api`,
`ecr.dkr`, `logs`, `secretsmanager`, `kms`, `sqs`, `states`, `ssm`, `xray` and `cognito-idp` -- plus
the S3 gateway endpoint, and private DNS makes the provider's public API hostname resolve to the
`cognito-idp` endpoint's network interface. Each service builds its JWT decoder at context refresh, so
issuer and JWKS reachability is a start-up dependency; it is satisfied over that endpoint. The `xray`
endpoint is the collector sidecar's export path from the step above, so both of the two endpoints
beyond AAP §0.4.1.9's eight are justified by a function this deployment actually performs.

Trade-offs: an endpoint costs an hourly charge per availability zone where a NAT route would have
carried the same traffic. That is accepted in both environments for one reason -- it is the only form
in which the application tier can hold no route to the internet at all -- and it is the reason the NAT
gateways carry no application-tier traffic today.

---

### Step 2c - Build the operational Lambda packages

The three archives the environment roots deploy as Lambda functions are **build output**, not
tracked files. Build them before any `terraform validate`, `plan` or `apply` in this runbook.

```bash
# WHAT: assembles dist/online-write-flag.zip, dist/database-admin.zip and
#       dist/dataset-generation-retention.zip from the reviewed sources beside them.
# WHY : Refactoring Rationale: these were built INSIDE Terraform by `archive` provider data
#       sources. That provider is not in the frozen dependency inventory (AAP §0.6.1.4 names the
#       Terraform CLI, `hashicorp/aws` and `hashicorp/random`), and an in-file comment recording
#       the divergence does not amend the plan, so packaging moved here. The builder uses only the
#       Python standard library, so this step adds no dependency to the prerequisites above.
# WHY : Assumptions: the archives are byte-deterministic -- member timestamps and modes are pinned
#       -- so rebuilding produces an identical `source_code_hash` and a plan shows no function
#       change unless a handler actually changed. That is why they can safely be gitignored rather
#       than committed, and why a plan produced on a runner matches one produced here.
# WHY : Assumptions: skipping this step does not produce a subtly wrong deployment. Both
#       `validate` and `plan` evaluate `filebase64sha256` over each archive, so a missing package
#       fails immediately and names the path it could not read.
python3 infra/lambda/build_packages.py

# WHAT: proves the built archives match the sources, writing nothing.
# WHY : Assumptions: use this before quoting a plan as evidence -- it compares bytes rather than
#       timestamps, so it answers exactly "was this plan produced from these handlers".
python3 infra/lambda/build_packages.py --check
```

---

### Step 2d - Initialise the environment root and provision the artifact registry

The ten repositories the images are pushed to are **Terraform-managed**, so they do not exist on a
clean account until this root has created them. That makes the registry the first thing the
environment root applies, and it is applied on its own: a targeted plan establishes the registry
under the same state the full apply will use, so nothing is created outside Terraform's knowledge.

```bash
# WHAT: initialises the selected environment root against the bootstrapped remote state.
# WHY : Assumptions: all FOUR partial-backend values are passed, because backend.tf commits only
#       `key` and `encrypt`. Omitting them does not fall back to a default -- Terraform prompts for
#       the bucket, and in a non-interactive shell with `-input=false` it fails outright, which is
#       why this is the exact command and not a shortened one. The four variables come from
#       Step 1a; run that step first in a fresh shell.
# WHY : Assumptions: `-lockfile=readonly` is used so that initialisation can select only the
#       provider releases the tracked `.terraform.lock.hcl` already records. Without it an `init`
#       may write a new lock entry, which silently moves the provider a plan is produced under.
# WHY : Trade-offs: this command emits a deprecation warning for `dynamodb_table`. That is expected
#       output; see Step 1a.
terraform -chdir="infra/envs/<env>" init -input=false -lockfile=readonly \
  -backend-config="bucket=${CARDDEMO_TF_STATE_BUCKET}" \
  -backend-config="region=${CARDDEMO_TF_STATE_REGION}" \
  -backend-config="dynamodb_table=${CARDDEMO_TF_STATE_LOCK_TABLE}" \
  -backend-config="kms_key_id=${CARDDEMO_TF_STATE_KMS_KEY_ID}"
```

```bash
# WHAT: creates and applies a plan limited to the artifact registry, then reads back the address of
#       each of the ten repositories.
# WHY : Alternatives Considered: creating the repositories with ad-hoc `aws ecr create-repository`
#       calls. Rejected because Terraform would then discover unmanaged resources on the full plan,
#       and because those repositories would carry none of the scan-on-push, KMS encryption or
#       lifecycle configuration `infra/modules/ecr` attaches.
# WHY : Assumptions: the read is `output -json registry | jq -e '.repository_urls'`, because the root
#       publishes ONE aggregate output per module and `registry` is the whole `ecr` module. There is
#       no top-level `ecr_repository_urls`; reading that name fails with "Output
#       \"ecr_repository_urls\" not found" AFTER the targeted apply has already run. `jq -e` makes an
#       absent member exit non-zero rather than write `null` into the file the next step reads.
# WHY : Assumptions: the address is read from each repository's provider-computed `repository_url`
#       rather than composed from an account identifier and a registry host. A hand-composed address
#       authenticates successfully and then targets a registry that holds nothing, which reads as a
#       missing image rather than as a wrong address.
terraform -chdir="infra/envs/<env>" plan -input=false -var-file=terraform.tfvars \
  -target=module.ecr -out=ecr.tfplan
terraform -chdir="infra/envs/<env>" apply -input=false ecr.tfplan
terraform -chdir="infra/envs/<env>" output -json registry \
  | jq -e '.repository_urls' > ecr-repositories.json
```

### Step 2e - Build, push and pin the ten images

```bash
# WHAT: authenticates Docker to this deployment's own registry without placing the password in argv.
# WHY : Alternatives Considered: passing the password as a command argument was rejected because
#       process listings and shell history retain it; password-stdin confines it to the pipe.
# WHY : Assumptions: the registry host is derived from the repository addresses just read rather than
#       typed, so the login and the push cannot target different registries.
registry="$(jq -r 'to_entries[0].value | split("/")[0]' ecr-repositories.json)"
aws ecr get-login-password --region "<aws-region>" \
  | docker login --username AWS --password-stdin "$registry"
```

```bash
# WHAT: builds, tags and pushes all ten images at the release tag, then records the immutable digest
#       the registry returned for each of the nine that run as a task.
# WHY : Assumptions: the third column is the build CONTEXT and the three distinct values are
#       load-bearing. Every service Dockerfile is written against the repository root -- it copies
#       services/<name>/pom.xml, the unpublished services/common-lib source, config/checkstyle for
#       its inherited documentation gate and config/docker for the shared entry point -- so a
#       narrower context fails at the first COPY of config/ with
#       "/config/docker/generate-listener-material.sh: not found". The ui and data-migration images
#       copy nothing from outside their own trees and each carries its own .dockerignore at that
#       context root, which is why they keep the narrower contexts.
# WHY : Assumptions: `--tag` names the release tag and never `latest`. The root refuses `latest` by
#       validation, and a mutable-only tag prevents a task definition from identifying the exact
#       bytes a rollback has to return to.
# WHY : Assumptions: the digest is read back from the registry with `describe-images` rather than
#       parsed out of the push output. The registry is the authority for what it stored, and the
#       push transcript is not machine-readable across Docker versions.
# WHY : Assumptions: `--repository-name` takes `${repository#*/}` and NOT `$name`. The two differ:
#       `infra/modules/ecr/main.tf` composes each repository as
#       `"${var.name_prefix}-${var.environment}/${name}"`, so the ECR repository is
#       `carddemo-<env>/auth-service` while the map key is the bare `auth-service`. `#*/` removes the
#       shortest leading match -- the registry host only -- leaving the namespaced name the API wants;
#       `##*/` would remove the namespace too and fail with RepositoryNotFoundException. Passing
#       `$name` fails the same way, which is why the address the push used is the address the digest
#       lookup derives from.
# WHY : Assumptions: `ui` is deliberately EXCLUDED from the digest map. The browser bundle is
#       published to S3 by Step 6 and its image runs no ECS task, so a digest for it would configure
#       nothing -- and `image_digests` validates its keys against the nine artifacts that do run as
#       tasks, so including `ui` fails the plan with a message naming it.
# WHY : Trade-offs: this rebuilds images that services-ci.yml and ui-ci.yml already validated. The
#       deployment rebuild is the artifact that actually ships; the review-time build proves the
#       Dockerfiles before any account credential exists.
# WHY : Assumptions: the tag is taken from `TF_VAR_image_tag`, exported in Step 2a, rather than typed
#       again here. The two must be the same string -- Terraform records it on every task definition
#       and it is how the pushed image is found again in the registry -- and two independent
#       substitutions of `<commit-sha>` is exactly how they end up differing by a character.
tag="$TF_VAR_image_tag"
digests='{}'
while IFS=$'\t' read -r name dockerfile context; do
  repository="$(jq -er --arg name "$name" '.[$name]' ecr-repositories.json)"
  docker build --file "$dockerfile" --tag "${repository}:${tag}" "$context"
  docker push "${repository}:${tag}"
  digest="$(aws ecr describe-images --region "<aws-region>" \
    --repository-name "${repository#*/}" \
    --image-ids "imageTag=${tag}" \
    --query 'imageDetails[0].imageDigest' --output text)"
  if [ "$name" != "ui" ]; then
    digests="$(jq --arg name "$name" --arg digest "$digest" \
      '. + {($name): $digest}' <<<"$digests")"
  fi
done <<'IMAGES'
auth-service	services/auth-service/Dockerfile	.
account-service	services/account-service/Dockerfile	.
card-service	services/card-service/Dockerfile	.
transaction-service	services/transaction-service/Dockerfile	.
reference-service	services/reference-service/Dockerfile	.
batch-service	services/batch-service/Dockerfile	.
authorization-service	services/authorization-service/Dockerfile	.
reporting-service	services/reporting-service/Dockerfile	.
ui	ui/Dockerfile	ui
data-migration	data-migration/Dockerfile	data-migration
IMAGES
printf '%s\n' "$digests" | jq -e 'length == 9'
```

```bash
# WHAT: hands the nine captured digests to Terraform as the `image_digests` input.
# WHY : Assumptions: this is the variable `infra/envs/<env>/variables.tf` declares for exactly this
#       purpose -- any artifact named in it is deployed BY DIGEST instead of by the mutable tag, and
#       `infra/modules/ecs-service` refuses a mutable tag outright in production, so an incomplete
#       map fails the plan there with the artifact's name rather than deploying a tag.
# WHY : Assumptions: the value is exported as JSON because the variable's type is `map(string)`; each
#       value is validated against `^sha256:[a-f0-9]{64}$`, which is exactly what the registry
#       returned above, so a truncated or mis-copied digest fails at plan rather than at task start.
# WHY : Trade-offs: `image_tag` is still set (Step 2a) even though every task runs by digest. It is
#       what the push above tagged, and the tag is how an operator finds the image again in the
#       registry; the digest is what the task definition names.
export TF_VAR_image_digests="$digests"
```

The ECR module enables scan-on-push, so inspect each repository's scan findings before the full apply
in Step 3.

---

## Step 3 - Provision the environment

Backend-enabled `init` is required for a real apply. The `-backend=false` form used in CI validates
syntax only and must not precede a production apply.

**The deployment-specific inputs were exported at the start of Step 2** and are still set in the same
shell. They are exported there rather than here because the targeted registry plan in Step 2 is
itself a `plan`, and a `plan` refuses to run until every non-defaulted variable has a value -- so
the exports have to precede the first Terraform command of the sequence, not the last. If this step
is being run in a fresh shell, go back and re-run that block before continuing.

The twelve deployment inputs were exported in Step 2a and the nine image digests in Step 2e. In a
fresh shell, re-run both export blocks and Step 1a before continuing -- Terraform reads them from the
environment, and an unset one stops `plan` rather than defaulting.

**Both environment roots declare exactly twelve variables with no default**, and those twelve are
what the Step 2a export block supplies: `alarm_email_endpoints`, `alb_certificate_arn`,
`cloudfront_acm_certificate_arn`, `cloudfront_aliases`, `cloudfront_api_connect_src_origins`,
`github_oidc_provider_arn`, `github_repository`, `image_tag`, `internal_service_domain_name`,
`mask_hmac_secret_arn`, `mask_hmac_secret_kms_key_arn` and `permissions_boundary_arn`. `plan` refuses
to run until every one of them is set.

`image_digests` is a **thirteenth** `TF_VAR_` export and is not one of those twelve, because it has
a default of `{}`. Step 2a exports it from the digests the push returned. It is optional only in
`dev`: `infra/modules/ecs-service` refuses a mutable tag in `prod`, so a prod plan without the
complete nine-artifact map fails on the first service it reaches.

**The automated path supplies the same twelve values.**
[`deploy.yml`](../../.github/workflows/deploy.yml) composes them into an untracked
`deployment.auto.tfvars.json` that it deletes at the end of the run, and
[`infra-ci.yml`](../../.github/workflows/infra-ci.yml) passes them as `TF_VAR_` for its review plan.
Ten arrive from protected GitHub environment variables; two are taken from the run itself so they
cannot disagree with the commit being deployed. A configured environment therefore needs the twelve
`CARDDEMO_*` variables below plus the four `CARDDEMO_TF_STATE_*` backend values.

| GitHub environment variable | Terraform variable | Notes |
|:---|:---|:---|
| `CARDDEMO_ALB_CERTIFICATE_ARN` | `alb_certificate_arn` | Regional ACM ARN covering `internal_service_domain_name` |
| `CARDDEMO_INTERNAL_SERVICE_DOMAIN_NAME` | `internal_service_domain_name` | Bare DNS name the ALB certificate covers |
| `CARDDEMO_CLOUDFRONT_ACM_CERTIFICATE_ARN` | `cloudfront_acm_certificate_arn` | Must be issued in **us-east-1** |
| `CARDDEMO_CLOUDFRONT_ALIASES_JSON` | `cloudfront_aliases` | JSON list, e.g. `["app.example.com"]`; must be non-empty |
| `CARDDEMO_GITHUB_OIDC_PROVIDER_ARN` | `github_oidc_provider_arn` | Output of `infra/bootstrap`, created once per account |
| `CARDDEMO_PERMISSIONS_BOUNDARY_ARN` | `permissions_boundary_arn` | Organisation IAM guardrail |
| `CARDDEMO_MASK_HMAC_SECRET_ARN` | `mask_hmac_secret_arn` | ARN of the masking HMAC secret. The secret's **value** is the operator's to create and must be canonical standard base64 of at least 32 random bytes -- see the note below |
| `CARDDEMO_MASK_HMAC_SECRET_KMS_KEY_ARN` | `mask_hmac_secret_kms_key_arn` | ARN of the customer-managed KMS key that encrypts the secret above. Must be in the same account and Region as the secret, which the root validates by comparing the two ARNs. The AWS-managed `alias/aws/secretsmanager` key is **not** accepted -- it cannot be granted to one principal |
| `CARDDEMO_ALARM_EMAIL_ENDPOINTS_JSON` | `alarm_email_endpoints` | JSON list of alarm recipients; both roots refuse an empty list, so a topic can never be provisioned with no subscriber |
| `CARDDEMO_IMAGE_DIGESTS_JSON` | `image_digests` | Has a default. `infra-ci.yml` passes it so a review plan reflects the deployed images; `deploy.yml` instead computes real digests from the images it just pushed and patches them in after the push |
| `CARDDEMO_AWS_REGION` | *(not a variable)* | Region for the ECR login and image push |
| `CARDDEMO_DEPLOY_ROLE_ARN` | *(not a variable)* | Role the workflow assumes by OIDC |
| *(workflow input, defaulting to `github.sha`)* | `image_tag` | An operator may pin an explicit tag for a redeploy; left empty it resolves to the commit being deployed, so it cannot silently disagree with it |
| *(run context `github.repository`)* | `github_repository` | Not operator-set, so the publication role cannot be granted to another repository |

`cloudfront_api_connect_src_origins` is the twelfth of those required variables and is deliberately
**not** an environment variable. The API endpoint does not exist until the apply creates it, so
`deploy.yml` supplies an empty list -- which yields `connect-src 'self'` and permits nothing -- and
narrows it to the real origin after the apply, before the SPA is published.

### The registry actions the deployment role needs

`CARDDEMO_DEPLOY_ROLE_ARN` names a role this package does not create, so its policy is the
operator's. Assumptions: beyond the Terraform permissions the apply itself needs, four registry
action families are called directly and the calling step fails without them. The manual path in
[2e](#step-2e---build-tag-and-push-the-ten-images) and
[2f](#step-2f---refuse-a-release-on-a-critical-or-high-registry-finding) calls the same four as
[`deploy.yml`](../../.github/workflows/deploy.yml), so one policy serves both:

| Action | Which step needs it | Why |
|:---|:---|:---|
| `ecr:GetAuthorizationToken` | image push -- 2e | The `docker login` that precedes every push |
| `ecr:BatchCheckLayerAvailability`, `ecr:InitiateLayerUpload`, `ecr:UploadLayerPart`, `ecr:CompleteLayerUpload`, `ecr:PutImage` | image push -- 2e | Writing the ten built images |
| `ecr:DescribeImages` | image push -- 2e | Reading back each pushed digest, which is what the `image_digests` map is assembled from |
| `ecr:DescribeImageScanFindings` | vulnerability gate -- 2f | Reading the scan result the gate refuses a deployment on |

Trade-offs: the last one is the newest and the easiest to omit, because nothing else in the run
uses it. Without it the gate step fails with an access denial rather than passing quietly, which
is the intended direction -- a gate that cannot read its evidence must not report a pass.

### The masking secret's value has a format the ETL enforces

`mask_hmac_secret_arn` names a secret this configuration neither creates nor rotates, so its
**value** is created out of band. That value is the HMAC key the extract-transform-load image
derives every redaction tag with, and the image now **refuses to run** rather than accept weak
material: it requires canonical standard base64 decoding to at least 32 bytes -- the HMAC-SHA-256
output size -- and refuses a passphrase, the URL-safe alphabet, a non-canonical spelling, anything
shorter, and material that is a single repeated byte. Create the value with:

```bash
# WHAT: generates 32 random bytes, encodes them as canonical standard base64, and stores the result
#       as the first version of the masking secret under a named customer-managed key.
# WHY : Trade-offs: the value travels on STDIN via --secret-string fileb:///dev/stdin rather than as
#       an argv value. An argv value is readable from the process table by any local process for as
#       long as the call runs, and it is retained by the shell's history file and echoed by `set -x`;
#       a transcript of this runbook would then contain the live key. The pipeline keeps it in memory
#       between two processes instead. `set +o xtrace` is issued explicitly because a traced shell
#       would defeat the pipeline by echoing the command's expansion.
# WHY : Assumptions: --kms-key-id is required, not optional. Omitting it is not neutral: Secrets
#       Manager then seals the value under the account's AWS-managed aws/secretsmanager key, whose
#       policy admits any principal in the account holding the matching Secrets Manager permission,
#       so the decrypt the data-migration task performs succeeds through a far wider policy than the
#       one task that needs it -- and the root can neither name nor grant the key actually in use.
#       Both roots therefore declare mask_hmac_secret_kms_key_arn as a required customer-managed key
#       ARN and refuse the AWS-managed alias, and each root grants kms:Decrypt on exactly that key to
#       the data-migration task role alone, confined to use through Secrets Manager.
# WHY : Assumptions: --query null keeps the new version identifier out of the transcript, for the
#       same reason the value itself is kept out of it.
set +o xtrace
python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())' \
  | tr -d '\n' \
  | aws secretsmanager create-secret \
  --name "carddemo/<env>/mask-hmac" \
  --kms-key-id "<mask-hmac-cmk-arn>" \
  --secret-string fileb:///dev/stdin \
  --query "null" --output text
```

Create the key first, with a policy admitting this account, and pass the same ARN to both the
command above and the `mask_hmac_secret_kms_key_arn` variable.

Assumptions: an EXISTING secret created without that flag is not repaired by supplying the variable
-- its stored versions are sealed under the account's managed key, and the grant this root writes
names a different one, so the task fails to decrypt. Re-keying the entry is the repair.

**Re-keying and rotating are two different operations, and only one of them is usually wanted.**
Decide which before running either, because the wrong choice is not visible afterwards:

- **Re-key, preserving the value.** `update-secret --kms-key-id` changes the key the entry is
  encrypted under and leaves the material alone. Secrets Manager re-encrypts the entry's **labelled**
  versions under the new key -- the one staged `AWSCURRENT` and the one staged `AWSPREVIOUS` -- which
  is why this is a repair rather than a setting for next time. Versions carrying no staging label are
  not re-encrypted, so an entry with unlabelled history keeps material readable only under the old
  key: one more reason not to schedule that key's deletion on the same day. The caller needs three
  things at once, and a re-key missing any of them fails rather than half-completing:
  `secretsmanager:UpdateSecret` on the entry, `kms:Decrypt` on the **old** key, and
  `kms:GenerateDataKey` with `kms:Encrypt` on the **new** one.
- **Rotate, replacing the value.** Only when the material itself must go -- it fails the format rule
  above, or it is known to have been exposed. This is not a repair for a key mismatch, and it has a
  consequence a re-key does not.

Where both are wanted, re-key **first** and then put the value, so the new version is sealed under
the intended key from the moment it is written.

```bash
# WHAT: re-points an existing masking secret at the customer-managed key, leaving the stored value
#       unchanged, and proves the version the task reads decrypts under the new key.
# WHY : Assumptions: the value is deliberately not re-supplied. It is the key every masking tag was
#       derived from, so a repair that replaces it fixes the encryption and breaks the data's
#       comparability -- see the rotation block below for what that costs.
# WHY : Alternatives Considered: treating `describe-secret` as the verification was rejected. It
#       reports the key the entry is CONFIGURED with, which is exactly what the console shows and
#       exactly what looked correct in the half-state this procedure used to leave behind. Only a
#       successful read proves a version decrypts, so the re-key is followed by both: the describe
#       reports the intent, and the read exercises it against the version the ETL will load.
# WHY : Assumptions: --query null keeps the material out of the transcript while still exercising
#       the decrypt path, so a failure here is a real failure to decrypt rather than an artefact of
#       how the value was printed. `set +o xtrace` is issued for the same reason it is issued when
#       the value is created -- a traced shell would echo what the redaction exists to withhold.
# WHY : Trade-offs: the old key stays usable until this passes. Scheduling its deletion in the same
#       window was rejected: a key pending deletion cannot decrypt, so any unlabelled version left
#       under it, and any re-key that did not take, becomes unreadable before anyone has established
#       which key is actually in use.
set +o xtrace
aws secretsmanager update-secret --secret-id "carddemo/<env>/mask-hmac" --kms-key-id "<mask-hmac-cmk-arn>"
aws secretsmanager describe-secret --secret-id "carddemo/<env>/mask-hmac" \
  --query 'KmsKeyId' --output text
aws secretsmanager get-secret-value --secret-id "carddemo/<env>/mask-hmac" \
  --version-stage AWSCURRENT --query "null" --output text
```

The re-key is complete when `describe-secret` reports `<mask-hmac-cmk-arn>` **and**
`get-secret-value` exits zero having printed nothing. Either one alone is the half-state: a reported
key with a failing read means the version the ETL loads is still sealed under the previous key, and a
successful read with an unexpected key means the grant this root writes names something else.

Rotate only on the second intent above.

```bash
# WHAT: replaces the masking secret's value with 32 fresh random bytes in canonical standard base64,
#       on an entry that already exists.
# WHY : Alternatives Considered: re-running the create-secret pipeline above was rejected, and it is
#       the mistake this block exists to prevent -- `create-secret` fails outright on an entry that
#       already exists, and it fails AFTER the value has been generated, so the operator retrying by
#       hand is the one left holding live key material in a shell. `put-secret-value` is the
#       operation for an existing entry and stages the new version as AWSCURRENT.
# WHY : Assumptions: no --kms-key-id appears here because the key is a property of the ENTRY, not of
#       a version: a put seals the new version under whatever key the entry currently names. That is
#       why a re-key precedes a rotation when both are wanted, rather than the two being independent.
# WHY : Trade-offs: the value travels on STDIN via --secret-string fileb:///dev/stdin, for the reason
#       the creation pipeline gives -- an argv value is readable from the process table, retained by
#       shell history and echoed by `set -x`. `set +o xtrace` is issued explicitly because a traced
#       shell would echo the expansion the pipeline exists to avoid, and --query null keeps the new
#       version identifier out of the transcript.
# WHY : Assumptions: this is NOT a neutral repair, and the consequence is data-shaped rather than
#       operational. Every masking tag the ETL has written was derived from the material being
#       replaced, so the next run re-derives every tag and a verification pass that spans the
#       rotation compares tags produced under two different keys -- reporting differences that are
#       not differences in the data. Rotate between load campaigns, never inside one.
set +o xtrace
python3 -c 'import base64,secrets; print(base64.b64encode(secrets.token_bytes(32)).decode())' \
  | tr -d '\n' \
  | aws secretsmanager put-secret-value \
  --secret-id "carddemo/<env>/mask-hmac" \
  --secret-string fileb:///dev/stdin \
  --query "null" --output text
```

Assumptions: no service roll follows either command. The value reaches exactly one consumer -- the
ETL task, as `CARDDEMO_MASK_HMAC_KEY`, projected only where the workload is `data-migration`
[`infra/envs/prod/main.tf` L3151-L3155, L3413] -- and that task reads it at start-up, so the next
invocation picks up whatever is current. There is no long-running task holding a stale copy, which is
what distinguishes this from the credential rotations later in this runbook that do require a roll.

Assumptions: the format refusal is stated here rather than only in the ETL's own README because the
failure surfaces during a batch run, long after the apply that wired the ARN succeeded -- an apply
cannot validate a secret's contents, and the operator who creates the secret is the one who needs
the rule. The tag-regeneration cost of replacing the value is stated with the command that replaces
it, above, so the two cannot be read apart. `data-migration/README.md` §5.7.1 carries the full rule
set.

Assumptions: the export block and the environment-variable table of this step are two spellings of
one input set -- the twelve variables both roots declare with no default -- and the spelling is
load-bearing in both. Terraform discards a variable it does not declare without reporting anything,
so a name that drifts sets nothing: `plan` then either refuses on the variable it never received or
proceeds on a default the operator did not intend, and neither outcome names the input that caused
it. Trade-offs: three artifacts have to agree on this vocabulary -- this runbook, the two workflows,
and each root's `variables.tf` -- and rather than rely on the agreement,
[`infra-ci.yml`](../../.github/workflows/infra-ci.yml) reads all three. It fails when a required
variable has no `export TF_VAR_` line here, and when a line here names a variable no root declares,
so a drifted name is a build failure instead of a plan-time surprise.

```bash
# WHAT: initialises the selected environment against the bootstrapped remote state.
# WHY : Assumptions: the S3 backend, lock table and key from Step 1 must already exist; otherwise
#       this command fails before a plan can be written.
# WHY : Assumptions: all FOUR partial-backend values are passed again here, identically to Step 2d.
#       This is a repeat rather than a redundancy: `backend.tf` commits only `key` and `encrypt`, so
#       an `init` in a shell that has not exported the four cannot resolve the bucket and -- with
#       `-input=false` -- fails instead of prompting. Re-run Step 1a first if this is a fresh shell.
#       Re-running `init` against an already-initialised root is a no-op beyond provider selection.
# WHY : Assumptions: the four values are this deployment's own `CARDDEMO_TF_STATE_*` variables, the
#       same four names `.github/workflows/deploy.yml` passes. Where they are not to hand they can
#       be read back from the bootstrap root -- `terraform -chdir=infra/bootstrap output -raw`
#       `state_bucket_name`, `aws_region`, `state_lock_table_name`, `state_kms_key_arn` -- which
#       needs that root's LOCAL, gitignored state on this machine.
terraform -chdir="infra/envs/<env>" init -input=false -lockfile=readonly \
  -backend-config="bucket=${CARDDEMO_TF_STATE_BUCKET}" \
  -backend-config="region=${CARDDEMO_TF_STATE_REGION}" \
  -backend-config="dynamodb_table=${CARDDEMO_TF_STATE_LOCK_TABLE}" \
  -backend-config="kms_key_id=${CARDDEMO_TF_STATE_KMS_KEY_ID}"
```

> The four `TF_STATE_*` variables are exported from the bootstrap outputs in Step 2. If this shell is
> new, re-run that export block before this command: an unset variable makes `init` receive `bucket=`
> and report a malformed backend configuration.

```bash
# WHAT: writes the proposed environment change to a saved plan.
# WHY : Trade-offs: a saved plan adds one local artifact and becomes stale if configuration changes,
#       but it guarantees that the reviewed change is the one apply executes. Re-plan after any
#       source, variable, state, or provider change.
# WHY : Assumptions: the name is `<env>.tfplan` -- `dev.tfplan` or `prod.tfplan` -- and the suffix
#       is what matters. This plan embeds the resolved value of every attribute the apply will set,
#       which for this stack includes the generated Aurora master password and the Cognito seed-user
#       passwords, so its exposure is a state file's. `.gitignore` L326-L327 match on the `.tfplan`
#       suffix and on `.tfplan.*` for the `terraform show -json` rendering, so a bare `tfplan` has
#       no suffix to match and stays trackable: `git check-ignore -v infra/envs/dev/dev.tfplan`
#       resolves, while the same command on `infra/envs/dev/tfplan` returns nothing. Delete the
#       artifact once the apply is complete.
terraform -chdir="infra/envs/<env>" plan -out="<env>.tfplan"
```

Review every create, update, replacement, and destroy. The baseline CICS deployment deck required
manual delete edits and contains duplicate or stale definitions; the saved Terraform plan is the
cloud control that makes comparable drift visible before execution.

```bash
# WHAT: applies the reviewed environment plan without re-planning or auto-approving.
# WHY : Refactoring Rationale: declarative apply converges the resource graph without editing a
#       deployment deck between runs, while the saved-plan boundary prevents an unreviewed graph
#       from replacing the reviewed one.
terraform -chdir="infra/envs/<env>" apply "<env>.tfplan"
```

---

## Step 4 - Apply database schemas and migrate data

### Establish the database connection context first

Every `psql` invocation in this runbook depends on this block, and the ETL procedure in
[data-migration.md](data-migration.md) is run from the same shell. Nothing below names a host, a
database, a role or a credential, because they are all set once here and inherited through the `PG*`
environment.

> Like Steps 2, 6 and 7, the blocks in this step are written to run under `set -euo pipefail` in one
> shell, in the order given. That is what makes a `jq -er` that finds no field abort the step instead
> of exporting the literal string `null` as a credential, and what makes a failed check stop the
> sequence. Paste them into a script or a subshell.

**Where this runs matters, and there is exactly one sanctioned way to reach the cluster.** Aurora
sits in the isolated data subnets: no internet route, and one ingress rule admitting the application
security group on the database port only. Nothing on a laptop can open a socket to it, so the
reachability step below is a precondition of every `psql` in this runbook, not an optional aid.

The sanctioned path is a Session Manager port forward through an SSM-managed target that already
carries the application security group. Establish it in a **second terminal** and leave it running
for the whole of Step 4; the commands in this step run in the first terminal.

```bash
# WHAT: prints the two network values an administrative target must be placed with, then opens a
#       Session Manager port forward from localhost:5432 to the Aurora writer endpoint.
# WHY : Assumptions: the target instance is operator-supplied and is the one manual prerequisite of
#       this step. The IaC deliberately provisions no bastion -- a permanently reachable host in the
#       application tier is a standing attack surface for a database an operator touches twice per
#       release. It must sit in one of the private-app subnets printed below, carry the app security
#       group printed below (that is what satisfies Aurora's single ingress rule), and be registered
#       with SSM. No inbound port and no public address is required on it.
# WHY : Assumptions: AWS-StartPortForwardingSessionToRemoteHost forwards to a host the TARGET can
#       reach, so the target needs no psql and no repository checkout of its own -- libpq runs on
#       the workstation and only the TCP stream crosses the session.
# WHY : Alternatives Considered: the RDS Data API, which is enabled on this cluster and needs no
#       network path at all. Rejected as the documented path because it takes one statement per
#       call with an explicit begin/commit around any multi-statement file, which would turn the
#       single transactional grant file below into a hand-sequenced batch whose failure modes an
#       operator has to reason about mid-apply. It remains the correct fallback when no in-VPC
#       target can be provided.
# WHY : Alternatives Considered: `aws ecs execute-command` into a running service task. Rejected
#       because the service images are headless JRE runtimes -- they carry no psql client and no
#       copy of data-migration/sql, so the grant file below could not be read from inside one.
network="$(terraform -chdir="infra/envs/<env>" output -json network)"
jq -er '{appSecurityGroupId: .app_security_group_id, privateAppSubnetIds: .private_app_subnet_ids}' <<<"$network"

aws ssm start-session \
  --region "$AWS_REGION" \
  --target "<the operator-supplied SSM instance id>" \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "host=$(terraform -chdir="infra/envs/<env>" output -json database | jq -er '.writer_endpoint'),portNumber=5432,localPortNumber=5432"
```

A `psql` that times out after this is a network fact -- the target is outside the app security group,
or the forward is not running -- and not a credential problem.

```bash
# WHAT: installs the pinned, digest-verified AWS RDS certificate bundle for TLS verification.
# WHY : Assumptions: sslmode=verify-full below verifies both the chain and the hostname, and it can
#       only do that against a trust anchor. Without this file libpq has no root store for the RDS
#       CA and every connection fails on certificate verification -- which reads like an outage.
# WHY : Assumptions: the script fetches over HTTPS and refuses to write the target unless the
#       downloaded bytes match its pinned SHA-256, so this is a verified pin rather than a fetch.
#       It exits 64 if no target path is given; the argument is required.
# WHY : Assumptions: .gitignore excludes *.pem, so the bundle cannot be committed from the tree.
export PGSSLROOTCERT="$PWD/rds-ca-bundle.pem"
config/docker/install-rds-trust-anchor.sh "$PGSSLROOTCERT"
```

```bash
# WHAT: resolves the endpoint, port and database name from the environment root's database output.
# WHY : Assumptions: these come from Terraform rather than from an operator's notes. The writer
#       endpoint changes whenever the cluster is replaced or restored, and a stale hostname connects
#       to nothing or -- worse, after a restore -- to the wrong cluster.
# WHY : Assumptions: database_name is the INITIAL database only. The eight service schemas live
#       inside it; there is no per-service database to select.
# WHY : Alternatives Considered: assembling a single libpq URI and passing it with psql -d.
#       Rejected because the URI would then have to be repeated on every psql in two runbooks, and
#       one omission silently falls back to libpq's defaults -- localhost, the OS user, no TLS. The
#       PG* environment is stated once and every later command inherits it.
database="$(terraform -chdir="infra/envs/<env>" output -json database)"
export PGHOST="$(jq -er '.writer_endpoint' <<<"$database")"
export PGPORT="$(jq -er '.port' <<<"$database")"
export PGDATABASE="$(jq -er '.database_name' <<<"$database")"

# WHAT: sends the socket to the local end of the port forward while leaving PGHOST as the name TLS
#       is verified against.
# WHY : Assumptions: libpq connects to PGHOSTADDR but presents and verifies PGHOST, so this is the
#       one combination that keeps sslmode=verify-full meaningful across a forward. Overwriting
#       PGHOST with 127.0.0.1 instead would make the certificate's subject-alternative names fail
#       to match, and the only way to get past that is to weaken the mode -- which is the defect.
# WHY : Assumptions: PGPORT stays the cluster port because localPortNumber above is the same 5432.
#       Choose a different local port only if 5432 is already bound locally, and set PGPORT to
#       match; the two must agree or every connection is refused.
# WHY : Trade-offs: unset PGHOSTADDR when running these commands from a host that already has a
#       route to the cluster -- an in-VPC administrative host rather than a forward. Everything
#       else in the context is unchanged.
export PGHOSTADDR=127.0.0.1

# WHAT: requires full TLS verification on every connection made from this shell.
# WHY : Assumptions: verify-full and not require. `require` encrypts but verifies nothing, so it
#       accepts any certificate and defeats the trust anchor installed above; verify-full is the
#       only mode that checks the chain AND that the hostname matches, which is what makes a
#       redirected endpoint fail instead of connecting.
export PGSSLMODE=verify-full
```

```bash
# WHAT: fetches the RDS-managed master credential into the environment without emitting it.
# WHY : Assumptions: the master role is the right role for the two files below. V0 needs CREATEROLE
#       and V2 grants on tables owned by the NOLOGIN owner roles, so neither can run as a service
#       runtime login. No per-service credential is used in this step.
# WHY : Assumptions: `set +o xtrace` is not decoration. If the operator enabled tracing, the
#       assignment below would be echoed with the secret expanded, which is exactly the disclosure
#       this shape exists to prevent.
# WHY : Assumptions: the caller's tracing state is READ into xtrace_was_enabled and restored only
#       if it was on. A bare `set -o xtrace` at the end would TURN TRACING ON in a shell that never
#       had it, and every later command -- including the ones that expand PGPASSWORD -- would then
#       echo its arguments. Restoring a state is not the same operation as enabling it.
# WHY : Assumptions: the value is CAPTURED, never printed. Command substitution keeps the plaintext
#       inside the shell; a bare `--query SecretString --output text` would put the password on the
#       terminal and into the scrollback, the session log and any recorded transcript.
# WHY : Alternatives Considered: psql -W and typing the password. Rejected because the RDS-managed
#       password is a long generated string nobody transcribes correctly, and reading it out to
#       type it is the disclosure again with extra steps.
# WHY : Assumptions: `jq -er` and not `jq -r` on both fields. `jq -r` prints the literal text
#       `null` for an absent field, so a secret whose shape changed would silently produce the
#       password "null" and fail authentication with a message that points at the credential
#       instead of at the secret. `-e` exits non-zero on null and stops the step here.
# WHY : Trade-offs: PGPASSWORD is visible in this process's own environment for the life of the
#       shell. Accepted, because the alternative -- a ~/.pgpass file -- writes the same plaintext
#       to disk where it outlives the session. Close the shell when the step is done.
xtrace_was_enabled=0
case "$-" in *x*) xtrace_was_enabled=1 ;; esac
set +o xtrace
secret_arn="$(jq -er '.master_user_secret_arn' <<<"$database")"
secret="$(aws secretsmanager get-secret-value --region "$AWS_REGION" --secret-id "$secret_arn" --query SecretString --output text)"
export PGUSER="$(jq -er '.username' <<<"$secret")"
export PGPASSWORD="$(jq -er '.password' <<<"$secret")"
unset secret
if [ "$xtrace_was_enabled" = 1 ]; then set -o xtrace; fi

# WHAT: reports which secret and version were used, disclosing no secret value.
# WHY : Assumptions: an operator still needs to prove WHICH credential a migration ran under. The
#       ARN and the version identifier answer that; the password answers nothing an audit needs.
aws secretsmanager describe-secret --region "$AWS_REGION" --secret-id "$secret_arn" \
  --query '{arn:ARN,rotationEnabled:RotationEnabled,lastChanged:LastChangedDate}'
```

```bash
# WHAT: proves the context works before any migration runs, printing no credential.
# WHY : Assumptions: this is the cheapest possible failure. A connectivity, TLS or privilege problem
#       discovered here costs one query; discovered halfway through a grant file it leaves the
#       database in a state an operator has to reason about.
# WHY : Assumptions: ssl is asserted from the server's own view rather than from PGSSLMODE, because
#       the variable records what was requested and pg_stat_ssl records what was negotiated.
psql -v ON_ERROR_STOP=1 -Atc "SELECT current_user, current_database(), version()"
psql -v ON_ERROR_STOP=1 -Atc "SELECT ssl, version FROM pg_stat_ssl WHERE pid = pg_backend_pid()"
```


The database reaches its cutover state in **six ordered sub-steps**, and every one of them depends on
the one before it. The order is stated once, here, because the two places it is easy to get wrong are
both silent: applying the operator SQL before the Flyway chains have created the tables it names, and
loading data before the delete grants and views the loaders and verifiers read are in place.

| # | What | Who runs it | Gate before continuing |
|:---|:---|:---|:---|
| 4a | Establish TLS trust and the admin connection | operator | a query returns from the writer endpoint |
| 4b | `V0__schemas_and_roles.sql` — 8 schemas, 3 role tiers | **Terraform**, during the Step 3 apply | 8 schemas and 16 login roles present |
| 4c | 24 Flyway migrations across the 7 owning services | **each service, at start-up** | every owning schema has its history rows |
| 4d | `V1` views, `V2` delete grants, `V3` verification surfaces | operator, in that order | each file's paired `verify/` script exits 0 |
| 4e | Stage, decode, load, verify the record data | operator | all three verification passes exit 0 |
| 4f | Reconcile the identifier allocator, then switch | operator | allocator is past every loaded identifier |

### Step 4a - Establish TLS trust and the admin connection

**Run 4a through 4f in one shell session.** 4a resolves the cluster facts, the trust anchor and the
`carddemo_expect` gate helper into that session, and every later sub-step reads them; starting 4d in a
fresh shell fails on an empty `$writer_endpoint` and an undefined `carddemo_expect`. If a session is
lost, re-run 4a — it is read-only and re-runnable — rather than substituting values by hand.

Every credential this system issues pins `sslmode=verify-full`, so a connection without a trust
anchor is refused by design rather than downgraded. Two facts about reach have to be settled before
the first command:

- **The Data API reaches the cluster from anywhere.** `enable_http_endpoint` is `true`
  (`infra/envs/<env>/main.tf`) and the Data API is a public AWS endpoint, so
  `aws rds-data execute-statement` needs no network path into the VPC. It executes **one** statement
  per call, which is why it is used for the verification queries below and not for the SQL files.
- **`psql` needs a path into the VPC, and that path is an operator prerequisite.** Aurora sits in
  subnets with no internet route (AAP §0.4.1.9), the sixteen modules of AAP §0.4.1.6 include no
  bastion, and `infra/modules/ecs-service` does not set `enable_execute_command`. The `psql`
  invocations in 4d therefore require a shell that **already** has reach to the writer endpoint on
  the cluster port — an account-provided administrative path such as a VPN or Direct Connect
  attachment, a peered administrative subnet, or a host in the private-app tier. Settle that reach
  before starting Step 4; it is not something this runbook creates, and discovering its absence at
  4d means stopping a cutover midway.

  > The reason `psql` is needed at all, rather than the Data API used everywhere else here, is
  > narrow and measured: `infra/lambda/database_admin.py` already contains the only dollar-quote-aware
  > statement splitter in the repository and already takes the file to apply from a
  > `BOOTSTRAP_SQL_FILE` environment variable — but `infra/lambda/build_packages.py` puts only
  > `V0__schemas_and_roles.sql` into its archive, so `V1`, `V2` and `V3` are not reachable through
  > it. Until that archive carries them, the three operator files have to be applied by a client that
  > can send a whole script, and `psql` is that client.

```bash
# WHAT: resolves the cluster's connection facts and the RDS-managed master secret handle from the
#       root output, and fetches the server trust anchor.
# WHY : Assumptions: read from the `database` aggregate output. The root publishes one aggregate per
#       module and no top-level `database_writer_endpoint`; `infra/envs/<env>/outputs.tf` records
#       that choice explicitly, so `terraform output -raw database_writer_endpoint` fails by design.
#       `jq -er` makes an absent member exit non-zero rather than yield `null`.
# WHY : Assumptions: only the SECRET HANDLE is read, never the secret value. The Data API resolves
#       the credential itself from that ARN, so no password reaches this shell, its history or its
#       process table -- which is why the verification queries below use the Data API even where a
#       psql session already exists.
# WHY : Trade-offs: the trust bundle is downloaded rather than committed. A committed bundle would be
#       one more thing to rotate in this repository, and a stale one presents as a TLS failure whose
#       cause is a file age; fetching it makes the trust anchor as current as the run.
# WHY : Assumptions: the anchor lands at a path this block DEFINES rather than at a placeholder the
#       reader substitutes. Every later command references the same variable, so there is one path to
#       get right instead of one per invocation, and the file is removed by the cleanup at the end of
#       4d rather than left in the operator's home directory.
database="$(terraform -chdir="infra/envs/<env>" output -json database)"
cluster_arn="$(jq -er '.cluster_arn' <<<"$database")"
master_secret_arn="$(jq -er '.master_user_secret_arn' <<<"$database")"
db_name="$(jq -er '.database_name' <<<"$database")"
writer_endpoint="$(jq -er '.writer_endpoint' <<<"$database")"
db_port="$(jq -er '.port' <<<"$database")"
export CARDDEMO_TRUST_ANCHOR="${TMPDIR:-/tmp}/carddemo-rds-global-bundle.pem"
curl -fsSL -o "$CARDDEMO_TRUST_ANCHOR" \
  "https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem"
```

```bash
# WHAT: proves the cluster answers and reports the server version, using no network path into the VPC.
# WHY : Assumptions: this is the first command that can fail for a reason worth distinguishing -- a
#       cluster still resuming from zero capacity answers this after roughly fifteen seconds, whereas
#       a missing Data API permission fails immediately with AccessDenied. Running it before anything
#       else means that distinction is made once rather than inside a later step's failure.
aws rds-data execute-statement \
  --resource-arn "$cluster_arn" \
  --secret-arn "$master_secret_arn" \
  --database "$db_name" \
  --sql "SELECT version()" \
  --query 'records[0][0].stringValue' --output text
```

```bash
# WHAT: defines the one assertion helper every gate in 4b, 4c and 4d uses, so each gate fails on the
#       spot instead of printing a number for a human to compare.
# WHY : Refactoring Rationale: the earlier form of these gates printed a count and left the
#       comparison to the reader. A count that reads plausibly -- 7 schemas instead of 8, 23
#       migrations instead of 24 -- is exactly the value that gets glanced at and accepted, and the
#       consequence surfaces later as a load failure whose cause is two sub-steps upstream. Comparing
#       in the shell makes the wrong value impossible to walk past.
# WHY : Alternatives Considered: asserting inside the SQL with a `DO $$ ... RAISE EXCEPTION $$` block
#       so the server refuses. Rejected for the Data API path: `$$` inside a double-quoted `--sql`
#       argument is expanded by the shell to its PID before the statement is ever sent, and quoting
#       around that collides with the single quotes the statements already use for schema names.
# WHY : Trade-offs: `return 1` rather than `exit 1`. These blocks are pasted into an interactive
#       administrative shell; `exit` would close it and lose the resolved connection facts above.
#       `return` sets `$?`, so `carddemo_expect ... && next-command` still short-circuits.
carddemo_expect() {
  if [ "$2" = "$3" ]; then
    printf 'OK   %s = %s\n' "$1" "$3"
  else
    printf 'FAIL %s: expected %s, got "%s" -- stop here, do not continue to the next sub-step\n' \
      "$1" "$2" "$3" >&2
    return 1
  fi
}
```

### Step 4b - Confirm the bootstrap SQL was applied

**`V0__schemas_and_roles.sql` is not an operator step on this path.** The Step 3 apply invokes the
database-admin Lambda (`aws_lambda_invocation.database_bootstrap` in `infra/envs/<env>/main.tf`),
which resolves every role credential from Secrets Manager, binds each one as a Data API **parameter**
named `carddemo.credential.<role>`, and executes the file inside **one** transaction. That mechanism
is why the operator does not run the file: `V0` reads each password out of a session setting rather
than taking it as literal text, so a bare `psql -f data-migration/sql/V0__schemas_and_roles.sql`
fails on the first role it cannot resolve — and if it did not, the password would be in the shell
history. `module.ecs_service` declares `depends_on` that invocation, so no service can start before
it has committed.

```bash
# WHAT: reads back the bootstrap invocation's own recorded result, then counts what it created.
# WHY : Assumptions: the invocation result is published as `runtime_configuration`.`database_bootstrap_result`,
#       so the apply's own record of the bootstrap is readable without re-running anything. It is
#       checked FIRST because a bootstrap that never ran and one that ran and failed leave the same
#       empty database, and only this value distinguishes them.
# WHY : Assumptions: the counts are asserted with `carddemo_expect` from 4a rather than printed. V0
#       creates eight schemas and sixteen LOGIN roles -- eight runtime, seven migrator, one verifier
#       -- and a partially applied bootstrap is the state that would otherwise be discovered by a
#       service failing to start.
# WHY : Assumptions: the sixteen is counted from the file, not assumed. V0 issues sixteen
#       `CREATE ROLE ... LOGIN` statements: eight `carddemo_<context>` runtime logins, seven
#       `carddemo_<context>_migrator` logins for the seven owning contexts, and one
#       `carddemo_verifier`. The eight `carddemo_<context>_owner` roles are NOLOGIN and are
#       deliberately excluded by the `rolcanlogin` predicate.
terraform -chdir="infra/envs/<env>" output -json runtime_configuration \
  | jq -er '.database_bootstrap_result'
schema_count="$(aws rds-data execute-statement \
  --resource-arn "$cluster_arn" --secret-arn "$master_secret_arn" --database "$db_name" \
  --sql "SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('auth','account','card','ledger','reference','batch','authorization','reporting')" \
  --query 'records[0][0].longValue' --output text)"
carddemo_expect 'V0 schemas' 8 "$schema_count"
login_role_count="$(aws rds-data execute-statement \
  --resource-arn "$cluster_arn" --secret-arn "$master_secret_arn" --database "$db_name" \
  --sql "SELECT count(*) FROM pg_roles WHERE rolcanlogin AND rolname LIKE 'carddemo\\_%'" \
  --query 'records[0][0].longValue' --output text)"
carddemo_expect 'V0 login roles' 16 "$login_role_count"
```

If the bootstrap has to be re-applied outside Terraform — a cluster restored from a snapshot taken
before it ran, for instance — re-invoke the same Lambda rather than reproducing its mechanism by
hand. It is idempotent, and it is the only thing that holds the credential-binding contract.

```bash
# WHAT: re-runs the bootstrap through the function that owns it.
# WHY : Alternatives Considered: applying the file by hand after issuing one
#       `SELECT set_config('carddemo.credential.<role>', '<password>', false);` per LOGIN role in the
#       same session. Rejected: it is sixteen passwords typed into a shell, it puts every one of them
#       in the history, and it has to be redone identically on every re-apply. The Lambda binds them
#       as Data API parameters, so none is ever text in a command.
# WHY : Assumptions: the member is `lambda_arns.database_admin`. The aggregate spells the map
#       `lambda_arns`, not `lambda_function_arns`, and carries four entries -- `quiesce`, `resume`,
#       `database_admin` and `dataset_retention`; `jq -er` fails on a wrong spelling rather than
#       invoking nothing.
aws lambda invoke --function-name \
  "$(terraform -chdir="infra/envs/<env>" output -json runtime_configuration | jq -er '.lambda_arns.database_admin')" \
  --payload '{"action":"bootstrap"}' --cli-binary-format raw-in-base64-out /dev/stdout
```

### Step 4c - Confirm the Flyway chains completed

What `V0` established, and which every later sub-step depends on:
`data-migration/sql/V0__schemas_and_roles.sql` creates the eight service schemas and the **three
tiers of role** behind them: eight `NOLOGIN` `carddemo_<context>_owner` roles that own each schema
and everything a migration creates in it, seven `carddemo_<context>_migrator` logins that are
members of those owners `WITH INHERIT FALSE`, and eight runtime logins holding `USAGE` plus
`SELECT`, `INSERT` and `UPDATE` with `CREATE` explicitly revoked. `DELETE` is **not** among the
schema-wide grants and is not withheld by oversight: three published operations do delete rows, and
each is granted one table at a time by `data-migration/sql/V2__runtime_delete_grants.sql`, applied in
4d below. Owning services then apply their
Flyway migrations **under the migration credential**, injected as `SPRING_FLYWAY_USER` and
`SPRING_FLYWAY_PASSWORD` from that context's `<role>_migrator` secret; each service's
`spring.flyway.init-sqls` issues `SET ROLE carddemo_<context>_owner` first, which is what makes the
resulting tables belong to the `NOLOGIN` owner rather than to any credential a task holds.
`reporting-service` owns no source tables, ships no migration and therefore has no migration role;
it reads only the masked security-barrier views granted to its read-only role.

> A service that receives the runtime credential but not the migration credential fails at startup
> on an unresolved `SPRING_FLYWAY_USER` placeholder — deliberately, because the alternative is
> migrating as the runtime role, which the bootstrap SQL leaves without `CREATE`. Both environment
> roots project the pair for all seven migrating workloads, and `infra/modules/ecs-service`
> requires it of each of them, so an omission fails at plan time.

The batch role's cross-schema grants are deliberately limited to the account and ledger objects
needed to preserve the posting unit of work as one database transaction. Do not replace those grants
with schema-wide privileges.

**`V0` itself is not an operator command.** `infra/lambda/database_admin.py` applies it through the
Aurora Data API, and the environment root invokes that function during `apply`
(`aws_lambda_invocation.database_bootstrap`), so the schemas and all three role tiers exist by the
time Step 3 returns. Do not run `V0` by hand: it would execute a second time against a database that
already has the roles, and the Lambda is the path whose result the apply gates on. The connection
context above exists for `V2`, for the verification queries in Step 5, and for the ETL procedure in
[data-migration.md](data-migration.md).

Once every owning service has completed its Flyway migration, apply the table-specific delete
grants. This file is separate from `V0` for a structural reason rather than a stylistic one: `V0`
runs before a single table exists, so the only privilege forms available to it are
`GRANT ... ON ALL TABLES IN SCHEMA` and `ALTER DEFAULT PRIVILEGES`, and neither can name one table.
`V2` names **six** tables across three schemas and the other twenty-plus in those schemas must not
have `DELETE`, so the grant has to be expressed where the tables are already there to be named.

| Grantee role | Table |
|:---|:---|
| `carddemo_auth` | `auth.users` |
| `carddemo_reference` | `reference.transaction_types` |
| `carddemo_reference` | `reference.transaction_categories` |
| `carddemo_authorization` | `"authorization".auth_reply_outbox` |
| `carddemo_authorization` | `"authorization".pending_auth_detail` |
| `carddemo_authorization` | `"authorization".pending_auth_summary` |

```bash
# WHAT: grants DELETE on exactly those six tables to those three runtime roles, then proves no other
#       table in the same schemas acquired it.
# WHY : Assumptions: this runs under the master credential established above. Every GRANT here is on
#       a table owned by a NOLOGIN carddemo_<context>_owner role, so a runtime or migration login
#       cannot execute the file -- it fails with insufficient privilege, per-statement, inside the
#       single transaction the file wraps itself in.
# WHY : Assumptions: it runs AFTER the auth, reference and authorization Flyway chains. Every one of
#       the six tables must already exist; against an empty schema the file fails on its first GRANT
#       and rolls back whole, which is the intended outcome rather than a partial privilege set.
# WHY : Trade-offs: withholding these grants does not make the system safer, it makes shipped
#       capabilities fail at runtime -- user deletion, the reference-service delete routes, the
#       authorization purge over pending summary and detail, and OutboxPublisher.purgePublished()'s
#       retention sweep over a table whose every row carries a PAN. The narrower risk of naming six
#       tables is preferred to the broader risk of an unbounded table of card-bearing rows.
# WHY : Alternatives Considered: folding these grants into V0 with schema-wide DELETE. Rejected
#       because it would hand DELETE on every present and future table in three schemas to a runtime
#       login, which is the blast radius this file exists to avoid.

```
**Seven services own migrations, not eight**, and the twenty-four files divide unevenly: `auth` 7,
`reference` 4, `authorization` 4, `ledger` 3, `account` 2, `card` 2, `batch` 2. `reporting-service`
owns none. Nothing in this runbook applies them — each service runs its own chain at start-up, so the
Step 3 apply is what triggers them, and this sub-step is the gate that says they finished.

```bash
# WHAT: sums every owning schema's SUCCESSFUL migration rows and refuses to continue unless the total
#       equals the number of migration files this checkout ships.
# WHY : Assumptions: the history table is per-schema, because each service migrates only its own
#       schema and Flyway resolves the bare table name against that schema's search path. A single
#       shared history table would let one service's chain report another's state.
# WHY : Assumptions: `success` is asserted, not just presence. Flyway records a FAILED migration as a
#       row too, so counting rows alone reports a broken chain as a complete one.
# WHY : Trade-offs: the expected total is COUNTED from the checkout rather than written here as a
#       literal. A literal would catch a file deleted from the tree, but it rots the moment a
#       migration is legitimately added -- the gate would then fail on a correct deployment, which is
#       the failure mode that gets a gate deleted. The tree is the right reference because Step 2e
#       built the running images from this same checkout. The measured breakdown in the paragraph
#       above is what makes a tree that has LOST a file visible, and it is prose precisely so it does
#       not silently agree with the shortfall.
# WHY : Assumptions: `"authorization"` is double-quoted inside the statement. It is a reserved word in
#       SQL, so an unquoted reference is a syntax error rather than a missing-relation error.
applied_migrations="$(aws rds-data execute-statement \
  --resource-arn "$cluster_arn" --secret-arn "$master_secret_arn" --database "$db_name" \
  --sql "SELECT sum(n) FROM (
           SELECT count(*) n FROM auth.flyway_schema_history WHERE success
           UNION ALL SELECT count(*) FROM account.flyway_schema_history WHERE success
           UNION ALL SELECT count(*) FROM card.flyway_schema_history WHERE success
           UNION ALL SELECT count(*) FROM ledger.flyway_schema_history WHERE success
           UNION ALL SELECT count(*) FROM reference.flyway_schema_history WHERE success
           UNION ALL SELECT count(*) FROM batch.flyway_schema_history WHERE success
           UNION ALL SELECT count(*) FROM \"authorization\".flyway_schema_history WHERE success
         ) t" \
  --query 'records[0][0].longValue' --output text)"
carddemo_expect 'successful Flyway migrations' \
  "$(ls services/*/src/main/resources/db/migration/*.sql | wc -l | tr -d ' ')" \
  "$applied_migrations"
```

On `FAIL`, one chain has not completed. Find which one before applying anything in 4d — the operator
SQL below names tables those chains create, so it fails on a partial schema in a way that reads as a
defect in the SQL rather than as a missing migration.

```bash
# WHAT: reports the per-schema split, so a shortfall names the service whose chain to investigate
#       instead of only the total.
# WHY : Assumptions: run only when the gate above printed FAIL. It is a diagnostic rather than a
#       gate: the sum is what decides whether to continue, and this narrows a failing sum to one
#       service's CloudWatch log group.
for s in auth account card ledger reference batch '"authorization"'; do
  printf '%-16s %s\n' "$s" "$(aws rds-data execute-statement \
    --resource-arn "$cluster_arn" --secret-arn "$master_secret_arn" --database "$db_name" \
    --sql "SELECT count(*) FROM ${s}.flyway_schema_history WHERE success" \
    --query 'records[0][0].longValue' --output text)"
done
```

### Step 4d - Apply the three operator SQL files, in order

These three are **not** part of any Flyway chain and nothing applies them automatically. Each names
objects the chains create, so all three must come after 4c and before any load. They are applied in
file order because each depends on the previous one: `V1` creates the reporting views, `V2` grants
the three table-specific deletes, and `V3` creates the verification surfaces the load checks read.

Run them from the shell 4a established has reach from. `V1` and `V3` issue their own
`SET LOCAL ROLE` to become the owning role, so the connected principal must be a **member** of those
`NOLOGIN` owners — the cluster master user is, which is why the master secret is the credential for
this sub-step. `V2` grants privileges and therefore needs a principal with grant authority on the
objects it names.

```bash
# WHAT: resolves the RDS-managed master credential into a mode-0600 passfile and sets the libpq
#       environment that every `psql` invocation below connects through.
# WHY : Assumptions: the password is NOT an input this runbook asks for and is not stored anywhere in
#       this repository. `manage_master_user_password = true` on the cluster
#       (`infra/modules/aurora-postgresql/main.tf`) makes RDS generate it and own the secret; the only
#       handle Terraform publishes is `database`.`master_user_secret_arn`, read in 4a. The username
#       comes out of the same secret rather than being typed, because the RDS-managed secret's
#       document is exactly `{"username":..., "password":...}` and hard-coding the name would drift
#       the moment `master_username` is overridden in a root.
# WHY : Alternatives Considered: (1) a `postgresql://user:password@host/db` URI, rejected because the
#       password would be in argv -- visible in `ps`, in the shell history and in any `set -x` trace.
#       (2) `PGPASSWORD`, rejected because it is inherited by every child process of this shell and
#       readable from `/proc/<pid>/environ`. (3) `psql -W` prompting, rejected because a
#       forty-character generated password cannot be typed reliably and the operator would paste it
#       into the terminal anyway. A passfile is the mechanism libpq documents for exactly this, and
#       `umask 077` before `mktemp -d` means the file is 0600 from creation rather than after a
#       `chmod` that leaves a window.
# WHY : Assumptions: the secret is read TWICE rather than cached in a shell variable. One read pipes
#       straight into the passfile and the other yields only the username, so the password never
#       occupies a shell variable, never appears in `set -x` output and cannot be recalled from the
#       history. The second API call costs a fraction of a second.
# WHY : Assumptions: backslash and colon are escaped in both fields, and nothing else is. Those are
#       the only two characters libpq treats specially in a passfile entry, and RDS-generated
#       passwords do contain punctuation -- an unescaped colon would silently truncate the entry and
#       present as an authentication failure against a password that is correct.
# WHY : Assumptions: connection facts go into `PG*` variables rather than into a URI. The trust anchor
#       is a file path, and a path in a URI's `sslrootcert` parameter would need percent-encoding;
#       `PGSSLROOTCERT` takes it literally. `verify-full` is not negotiable here -- it is what every
#       issued credential pins, so testing the operator path at a weaker mode would prove nothing
#       about the path the services use.
# WHY : Assumptions: the prior umask is captured and restored by the cleanup at the end of 4d. These
#       blocks are pasted into a shell the operator keeps using; leaving `077` set would make every
#       file created for the rest of the cutover -- including the ETL's own staging output in 4e --
#       group- and world-unreadable, which is a change nobody asked for and would be hard to trace
#       back to here.
carddemo_prior_umask="$(umask)"
umask 077
carddemo_pgdir="$(mktemp -d)"
export PGPASSFILE="$carddemo_pgdir/pgpass"
export PGHOST="$writer_endpoint"
export PGPORT="$db_port"
export PGDATABASE="$db_name"
export PGSSLMODE=verify-full
export PGSSLROOTCERT="$CARDDEMO_TRUST_ANCHOR"
aws secretsmanager get-secret-value \
  --secret-id "$master_secret_arn" --query SecretString --output text \
  | jq -er --arg host "$PGHOST" --arg port "$PGPORT" '
      def libpq: gsub("\\\\"; "\\\\") | gsub(":"; "\\:");
      "\($host):\($port):*:\(.username|libpq):\(.password|libpq)"
    ' > "$PGPASSFILE"
PGUSER="$(aws secretsmanager get-secret-value \
  --secret-id "$master_secret_arn" --query SecretString --output text | jq -er '.username')"
export PGUSER
carddemo_expect 'psql principal' "$PGUSER" "$(psql -Atqc 'SELECT current_user')"
```

```bash
# WHAT: applies the three operator SQL files in order and runs each one's paired verification script.
# WHY : Assumptions: `psql` is used and the Data API is not, because all three files are
#       multi-statement and two of them carry dollar-quoted blocks -- `V1` four and `V3` four. The
#       Data API takes one statement per call, and splitting these files on `;` in a shell would cut
#       inside a `$$ ... $$` body and produce a syntax error that names a line the author never
#       wrote. The only correct splitter in this repository is inside the database-admin Lambda, and
#       that archive packages `V0` alone.
# WHY : Assumptions: `ON_ERROR_STOP=1` on every invocation. Without it psql reports success for a
#       script whose middle statement failed, which here would mean a view or a grant silently
#       absent and discovered by a load.
# WHY : Trade-offs: three separate verification scripts rather than one. Each is paired with the file
#       that creates what it checks, so a failure names the file to re-apply rather than the set.
# WHY : Assumptions: no connection string is passed. The block above exported the full libpq
#       environment, so each invocation carries only what differs between them -- which is the file.
psql -v ON_ERROR_STOP=1 -f data-migration/sql/V1__reporting_views.sql
psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/reporting_view_privileges.sql

psql -v ON_ERROR_STOP=1 -f data-migration/sql/V2__runtime_delete_grants.sql
psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/runtime_delete_grants.sql

psql -v ON_ERROR_STOP=1 -f data-migration/sql/V3__verification_surfaces.sql

# WHY : Assumptions: this role-isolation check runs once, after all three files, rather than being
#       paired with one of them. It proves no login role granted membership in an owning role also
#       holds SUPERUSER or BYPASSRLS -- a property of the roles V0 created, which V1 and V2 then
#       grant against, so it is only meaningful once every grant is in place.
psql -v ON_ERROR_STOP=1 -f data-migration/sql/verify/alternate_database_users.sql
```

```bash
# WHAT: proves V3's three aggregate-only relations exist, which is the gate 4e's verification reads
#       through.
# WHY : Assumptions: existence is checked here rather than by running V3's own paired scripts.
#       `verify/row_counts.sql` and `verify/money_totals.sql` read these relations but compare LOADED
#       data, so they cannot pass before 4e; running them now would report a failure that is only the
#       absence of rows. Checking the relations exist separates "the surface is missing" from "the
#       load disagrees", which are different repairs.
verification_views="$(aws rds-data execute-statement \
  --resource-arn "$cluster_arn" --secret-arn "$master_secret_arn" --database "$db_name" \
  --sql "SELECT count(*) FROM pg_views WHERE (schemaname, viewname) IN
           (('reporting','v_verification_row_counts'),
            ('reporting','v_verification_money_totals'),
            ('auth','v_verification_row_counts'))" \
  --query 'records[0][0].longValue' --output text)"
carddemo_expect 'V3 verification surfaces' 3 "$verification_views"
```

```bash
# WHAT: removes the passfile and the trust anchor, and clears the libpq environment.
# WHY : Assumptions: this runs at the END of 4d and not later. 4e connects as the ETL's own
#       `carddemo_migration` credential, resolved by the loader from Secrets Manager, so nothing
#       after this sub-step needs the master credential -- and leaving a master passfile on an
#       operator workstation for the rest of a cutover is the avoidable exposure.
# WHY : Alternatives Considered: `rm -f` alone. `shred -u` is preferred because the file held a live
#       administrative credential; `rm` unlinks without overwriting. The `||` fallback keeps the block
#       working where `shred` is absent, which is the case on some minimal images.
# WHY : Trade-offs: the trust anchor is removed too, even though it is public and not a secret. It is
#       re-fetched by 4a in one command, and leaving it behind is what makes a later run silently use
#       a months-old bundle.
shred -u "$PGPASSFILE" 2>/dev/null || rm -f "$PGPASSFILE"
rm -f "$CARDDEMO_TRUST_ANCHOR"
rmdir "$carddemo_pgdir"
umask "$carddemo_prior_umask"
unset PGPASSFILE PGUSER PGHOST PGPORT PGDATABASE PGSSLMODE PGSSLROOTCERT \
      CARDDEMO_TRUST_ANCHOR carddemo_pgdir carddemo_prior_umask
```

`V2` is separate from `V0` for a structural reason rather than a stylistic one: `V0` runs before a
single table exists, so the only privilege forms available to it are
`GRANT ... ON ALL TABLES IN SCHEMA` and `ALTER DEFAULT PRIVILEGES`, and neither can name one table.
**Six** tables need `DELETE`, across three runtime roles — `auth.users` for `carddemo_auth`;
`reference.transaction_types` and `reference.transaction_categories` for `carddemo_reference`; and
`"authorization".auth_reply_outbox`, `pending_auth_detail` and `pending_auth_summary` for
`carddemo_authorization` — and every other table in those schemas must not have it, so the grant has
to be expressed where the tables are already there to be named. `verify/runtime_delete_grants.sql`
asserts exactly that pairing and returns rows only when it is broken.

> Trade-offs: withholding those grants does not make the system safer, it makes shipped capabilities
> fail at runtime — the reference-service delete routes, the user-delete screen, the pending
> authorization purge, and `OutboxPublisher.purgePublished()`'s hourly retention sweep over a table
> whose every row carries a PAN. The narrower risk of naming six tables is preferred to the broader
> risk of an unbounded table of card-bearing rows.

> The `ON DELETE RESTRICT` constraint on `reference.transaction_categories` is unaffected by the
> grant and is what still refuses a delete of a referenced transaction type — the privilege decides
> whether the role may *attempt* the delete, the constraint decides whether a *row* may go, and
> `reference-service` turns the resulting foreign-key violation into its contracted `409`. Verified
> against PostgreSQL 17.10: with the grant in place the attempt returns SQLSTATE `23503`, not
> `42501`, so the two outcomes stay distinguishable to the service.

### Step 4e - Stage, load and verify the record data

[data-migration.md](data-migration.md) owns this movement in full — copybook decoding, staged
generations, the eleven record-length contracts, and the three mandatory verification passes. Two
ordering facts belong here rather than there, because they are what makes that runbook's first command
legal:

- It runs from the **ETL environment built in Step 2**, not from the repository `.venv`.
- It runs **after 4d**, because the loaders write through the grants `V2` created and the verifiers
  read the surfaces `V3` created.

```bash
# WHAT: applies each service role's credential, proves each can log in, then runs every verification
#       pass over every manifested dataset.
# WHY : Assumptions: `apply-credentials` precedes any load. The roles exist from 4b but authenticate
#       only once their credentials are applied, so a load attempted first fails as an authentication
#       error rather than as a missing step.
# WHY : Assumptions: `verify-all` is the gate, and its exit status is the whole signal -- 0 succeeded,
#       2 invoked incorrectly, 8 the step did not complete, 16 the environment could not be reached.
#       Any non-zero value stops the cutover here; see data-migration.md for the per-dataset commands
#       and for the pre-load recovery point a failure returns to.
data-migration/.venv/bin/carddemo-migrate apply-credentials
data-migration/.venv/bin/carddemo-migrate verify-all
```

### Step 4f - Reconcile the identifier allocator, then switch

```bash
# WHAT: advances the transaction-identifier allocator past every identifier the load inserted.
# WHY : Assumptions: this is REQUIRED after a load and not a tidying step. The loaded rows carry
#       identifiers taken from the mainframe extracts, and the allocator that issues new ones starts
#       from its own sequence -- so without this, the first transaction the application creates
#       collides with a loaded row and fails on the primary key. It is deliberately separate from the
#       load so that a re-run of one dataset does not re-derive the allocator from a partial table.
data-migration/.venv/bin/carddemo-migrate reconcile-sequences
```

The switch itself is **Step 6's SPA publication**, and that is the only reason to keep it until last:
API Gateway is reachable from the moment the Step 3 apply creates it, but no browser can call it until
`config.json` names the endpoint and the content-security policy permits its origin. Publishing the
SPA before 4e's verification passes have exited zero is what turns a load defect into something a
user sees, which is exactly the ordering [data-migration.md](data-migration.md) states as its gate.

---

## Step 5 - Seed reference data

`reference-service`'s `V2__seed_reference.sql` carries the reference data every other context reads.
Its header at L12-L18 and its footer at L683-L695 state the seeded population as a contract, and this
step verifies that contract rather than re-applying it -- the file
(`services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql`) runs inside
that service's Flyway chain in Step 4:

**Verify the `'DEFAULT'` disclosure-group row exists.** Interest accrual looks up a specific
disclosure group and, when that lookup misses, retries under the group named `DEFAULT` -- the
behaviour the baseline reaches on VSAM status 23 in `app/cbl/CBACT04C.cbl`. With the row missing there
is nothing for that retry to resolve to, and the migrated job **fails hard rather than accruing
anything**: `InterestCalculationService.defaultRateFor` throws
`IllegalStateException("no disclosure group is keyed by the substituted key ...")`, naming the
substituted key so the displaced component is visible. That mirrors the baseline exactly — the
reference read at `app/cbl/CBACT04C.cbl:444` carries no `INVALID KEY` clause at all, and `:446-459`
accepts only file status `'00'`, displaying `ERROR READING DEFAULT DISCLOSURE GROUP` and abending
otherwise. **No wrong-rate money is written.** Returning zero would have been the silent failure, and
it is precisely what both implementations refuse to do.

What the operator sees, and what it leaves behind:

- The whole accrual pass runs as **one** Spring Batch tasklet transaction, so every interest
  transaction and account update the pass had produced before the miss is rolled back. There is no
  partially-accrued state to unpick.
- `BatchStepLedger` records the failed attempt in its **own** transaction, so the failure survives
  that rollback and is visible in `batch.batch_run` with its attempt counted.
- The process exits at the hard-failure tier, `BatchReturnCode.HARD_FAILURE` = `8`. The state
  machine's `CheckInterestExitCode` choice takes its `Default` edge to `NotifyFailure`, so the
  nightly chain stops at that step and the alarm topic carries it.

Recovery is seed-then-redrive, not re-run: apply the missing row, confirm it, then redrive the **same**
failed execution. Steps the ledger already recorded as completed replay their recorded code rather
than repeating their writes, and the failed interest step opens a fresh attempt.
[batch-operations.md](batch-operations.md) carries the redrive command.

The key column is **`acct_group_id`**, and the primary key is
`(acct_group_id, tran_type_cd, tran_cat_cd)`. There is no `group_cd` and no `group_id` column; a
query naming either fails with `42703 undefined_column`, which reads like a missing table rather than
a wrong column name. The fallback group is not one row: it is one row per transaction type and
category combination the accrual can ask for, so verifying that "a DEFAULT row exists" would pass
with sixteen of the seventeen combinations missing and still accrue at the wrong rate for whichever
combination was absent.

```bash
# WHAT: asserts the DEFAULT fallback set is complete across all its type and category dimensions,
#       and reports the seeded reference counts.
# WHY : Assumptions: the fallback is only exercised by accounts whose specific group is missing, so
#       a smoke test over well-formed accounts never touches it. A missing DEFAULT combination
#       therefore survives every functional check and surfaces as wrong money in a later accrual,
#       which is why it is asserted here by exact set comparison rather than inferred from a
#       passing job.
# WHY : Assumptions: the seventeen expected combinations are enumerated, not counted. They are the
#       seventeen 'DEFAULT   ' rows of
#       services/reference-service/src/main/resources/db/migration/V2__seed_reference.sql -- four
#       categories under type 01, three under 02, three under 03, three under 04, one under 05, two
#       under 06 and one under 07 -- and the same seventeen combinations are seeded for each of the
#       three groups A000000000, DEFAULT and ZEROAPR, fifty-one rows in total.
# WHY : Alternatives Considered: asserting count(*) = 17, which is what a first pass writes.
#       Rejected because cardinality is not set equality: seventeen rows carrying the WRONG
#       seventeen type/category pairs satisfies a count and still leaves the accrual with no row to
#       resolve for the combination it actually asks for. The comparison below is two-way, so it
#       fails on a missing combination AND on an unseeded extra, and it names which.
# WHY : Alternatives Considered: SELECT 1 ... WHERE acct_group_id = 'DEFAULT', the shape this
#       replaces. Rejected because LIMIT-1 existence is satisfied by a single surviving row and says
#       nothing about the other sixteen, and because the accrual resolves a group by all three key
#       columns -- so the dimensions are the thing that has to be present.
# WHY : Assumptions: acct_group_id is CHAR(10), so 'DEFAULT' compares equal to the blank-padded
#       stored value under bpchar semantics; no rpad is needed here. rtrim is applied to the two
#       code columns instead, because bpchar values carry their padding into a text concatenation
#       and '01  /0001' would match nothing in the expected array.
# WHY : Assumptions: `e <> ALL (present)` is correct over an empty array -- it is true, so a table
#       with no DEFAULT rows at all reports all seventeen as missing rather than silently passing.
#       Both columns are NOT NULL, so no NULL can turn the comparison into UNKNOWN.
psql -v ON_ERROR_STOP=1 -f - <<'SQL'
DO $$
DECLARE
  expected text[] := ARRAY[
    '01/0001','01/0002','01/0003','01/0004',
    '02/0001','02/0002','02/0003',
    '03/0001','03/0002','03/0003',
    '04/0001','04/0002','04/0003',
    '05/0001',
    '06/0001','06/0002',
    '07/0001'];
  present    text[];
  missing    text[];
  unexpected text[];
BEGIN
  SELECT coalesce(array_agg(p ORDER BY p), ARRAY[]::text[]) INTO present
    FROM (SELECT rtrim(tran_type_cd) || '/' || rtrim(tran_cat_cd) AS p
            FROM reference.disclosure_groups
           WHERE acct_group_id = 'DEFAULT') s;
  SELECT array_agg(e ORDER BY e) INTO missing
    FROM unnest(expected) e WHERE e <> ALL (present);
  SELECT array_agg(a ORDER BY a) INTO unexpected
    FROM unnest(present) a WHERE a <> ALL (expected);
  IF missing IS NOT NULL OR unexpected IS NOT NULL THEN
    RAISE EXCEPTION
      'DEFAULT disclosure-group fallback set does not match the seeded contract: % of 17 combinations present; missing %; unexpected %',
      cardinality(present), coalesce(missing, ARRAY[]::text[]), coalesce(unexpected, ARRAY[]::text[]);
  END IF;
  RAISE NOTICE 'DEFAULT fallback set verified: all 17 type/category combinations present, no extras.';
END $$;
SQL
psql -v ON_ERROR_STOP=1 -c "SELECT acct_group_id, count(*) AS combinations
  FROM reference.disclosure_groups GROUP BY acct_group_id ORDER BY acct_group_id"
psql -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM reference.transaction_types"
psql -v ON_ERROR_STOP=1 -c "SELECT count(*) FROM reference.transaction_categories"
```

| Table | Rows | Source |
|:---|---:|:---|
| `reference.transaction_types` | 7 | `app/data/ASCII/trantype.txt` |
| `reference.transaction_categories` | 18 | `app/data/ASCII/trancatg.txt` |
| `reference.disclosure_groups` | 51 | `app/data/EBCDIC/AWS.M2.CARDDEMO.DISCGRP.PS`; three group ids x 17 rows each |
| `reference.us_phone_area_codes` | 490 | `app/cpy/CSLKPCDY.cpy` allow-list; 410 class `G` + 80 class `E` |
| `reference.us_states` | 56 | `app/cpy/CSLKPCDY.cpy` allow-list |
| `reference.us_state_zip_prefixes` | 240 | `app/cpy/CSLKPCDY.cpy` allow-list |

Assumptions: the disclosure-group row names the EBCDIC extract where the other five name ASCII,
because `V2__seed_reference.sql` reads that one dataset from the EBCDIC twin -- the two twins differ
on one interest rate, and the ASCII conversion is the lossy one. The remaining five twins are
byte-identical, so naming the ASCII file keeps those cells checkable in a text editor.

Assumptions: 490 counts the phone-area-code table alone, not the lookup population. The three lookup
tables carry 786 rows between them, and the three reference tables another 76, for 862 in total.
Address validation reads all three lookup tables, so a check that stops at 490 leaves two of the
three -- 296 of the 786 rows -- unverified.

**Verify the `DEFAULT` disclosure group is complete, at all seventeen type/category combinations.**
`DEFAULT` is not one row. `V2__seed_reference.sql` L335-L361 seeds seventeen of them -- transaction
type `01` categories `0001`-`0004`; types `02`, `03` and `04` categories `0001`-`0003`; type `05`
category `0001`; type `06` categories `0001`-`0002`; and type `07` category `0001` -- and interest
accrual can require any one of them. `app/cbl/CBACT04C.cbl` L210-L212 builds the lookup key
from the account's own disclosure group together with the category-balance row's type and category
code; L436-L438 replaces **only** the group id with the literal `DEFAULT` when the first read returns
VSAM status `23` and re-reads. The type and category travel unchanged into the second read, so the
group has to carry a row for every combination a category-balance row can present.

Assumptions: a missing `DEFAULT` row does not mis-price an accrual, it terminates the run.
`1200-A-GET-DEFAULT-INT-RATE` at `app/cbl/CBACT04C.cbl` L443-L460 tolerates nothing on the re-read:
any status other than `00` moves 12 into `APPL-RESULT` at L449, which fails the `APPL-AOK` test
declared at L134 as `VALUE 0`, so it displays `ERROR READING DEFAULT DISCLOSURE GROUP` at L455 and
performs `9999-ABEND-PROGRAM` at L458 -- an abend that moves 999 to `ABCODE` and calls `CEE3ABD` at
L628-L632. The first read is the tolerant one: L422 accepts `00` or `23` and L431-L434 abends on
anything else. So the failure is loud and it stops the accrual, which is why this is asserted before a
batch window rather than discovered inside one.

```bash
# WHAT: fails unless the DEFAULT disclosure group holds seventeen rows AND covers every
#       (tran_type_cd, tran_cat_cd) combination present anywhere in the table.
# WHY : Alternatives Considered: a bare existence test -- SELECT 1 ... WHERE acct_group_id =
#       'DEFAULT' -- passes on a single surviving row. The fallback re-read carries the type and
#       category of whichever category-balance row is being accrued, so a partial DEFAULT group
#       abends on the first uncovered combination and passes an existence test until then. The
#       EXCEPT names the uncovered combinations instead of reporting a bare shortfall.
# WHY : Assumptions: the group column is acct_group_id, and it is the first member of the composite
#       primary key (acct_group_id, tran_type_cd, tran_cat_cd) declared at
#       services/reference-service/src/main/resources/db/migration/V1__reference.sql L339-L340. Any
#       other spelling fails with an undefined-column error, which reads as a broken check rather
#       than as a missing seed row. The unpadded literal is correct against the CHAR(10) declaration
#       at L311: bpchar comparison ignores the trailing pad, so 'DEFAULT' matches the stored
#       'DEFAULT   ' the seed writes.
# WHY : Trade-offs: the assertion is raised inside the engine rather than printed for a reader,
#       because a printed count is only as reliable as the operator comparing it. ON_ERROR_STOP
#       turns the RAISE into a non-zero psql exit, so a deployment script cannot continue past it.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
DECLARE
    default_rows integer;
    uncovered    text;
BEGIN
    SELECT count(*) INTO default_rows
      FROM reference.disclosure_groups
     WHERE acct_group_id = 'DEFAULT';

    SELECT string_agg(format('%s/%s', tran_type_cd, tran_cat_cd), ', ' ORDER BY tran_type_cd, tran_cat_cd)
      INTO uncovered
      FROM (SELECT DISTINCT tran_type_cd, tran_cat_cd FROM reference.disclosure_groups
            EXCEPT
            SELECT tran_type_cd, tran_cat_cd FROM reference.disclosure_groups
             WHERE acct_group_id = 'DEFAULT') AS uncovered_combination;

    IF default_rows <> 17 OR uncovered IS NOT NULL THEN
        RAISE EXCEPTION
            'DEFAULT disclosure group incomplete: % of 17 rows; combinations with no DEFAULT row: %',
            default_rows, coalesce(uncovered, 'none');
    END IF;
END
$$;
SQL
```

```bash
# WHAT: fails unless all six seeded reference tables hold exactly the row counts
#       V2__seed_reference.sql declares, naming every table that disagrees and by how much.
# WHY : Assumptions: the expected values are the ones V2__seed_reference.sql L683-L695 states as its
#       contract, so a change to the seed and a change to this check are visible in one diff. Every
#       count is asserted in one statement because a short load is rarely confined to one table --
#       an interrupted apply leaves several partially loaded, and stopping at the first mismatch
#       hides the rest.
# WHY : Trade-offs: the three lookup tables are asserted here rather than left to the address
#       validation smoke check. A missing state or ZIP-prefix code rejects a customer address that
#       should be accepted, which reads as a validation rule rather than as missing seed data, and
#       nothing in the reject message points back to this table.
psql -v ON_ERROR_STOP=1 <<'SQL'
DO $$
DECLARE
    mismatch text;
BEGIN
    SELECT string_agg(format('%s has %s, expected %s', relation, actual, expected), '; ' ORDER BY relation)
      INTO mismatch
      FROM (VALUES
            ('reference.transaction_types',        7, (SELECT count(*) FROM reference.transaction_types)),
            ('reference.transaction_categories',  18, (SELECT count(*) FROM reference.transaction_categories)),
            ('reference.disclosure_groups',       51, (SELECT count(*) FROM reference.disclosure_groups)),
            ('reference.us_phone_area_codes',    490, (SELECT count(*) FROM reference.us_phone_area_codes)),
            ('reference.us_states',               56, (SELECT count(*) FROM reference.us_states)),
            ('reference.us_state_zip_prefixes',  240, (SELECT count(*) FROM reference.us_state_zip_prefixes))
           ) AS t(relation, expected, actual)
     WHERE actual <> expected;

    IF mismatch IS NOT NULL THEN
        RAISE EXCEPTION 'seeded reference counts disagree with V2__seed_reference.sql: %', mismatch;
    END IF;
END
$$;
SQL
```

Expected: three groups at seventeen combinations each, fifty-one disclosure rows in total, seven
transaction types and eighteen transaction categories.

Also verify the transaction-category foreign key is still `ON DELETE RESTRICT`. That constraint
preserves the baseline `XTRNTYCAT` relationship, and `reference-service` translates the resulting
foreign-key violation into a `409` conflict rather than exposing a database error. `RESTRICT` and
`CASCADE` differ here in a way no test over unreferenced rows would reveal: under `CASCADE`, deleting
a transaction type would silently delete every category beneath it.

---

## Step 6 - Roll out services and verify health

The deployment model is rolling ECS service replacement. Blue-green and canary deployment are out
of scope.

Roll-out is proved in four steps, and none of them is optional: the services reach a stable task
count, every **primary** deployment reports `COMPLETED`, every load-balancer target reports `healthy`
against `/actuator/health`, and each service's own log shows a completed start with no errors after
it. A service can be "stable" with a failed primary deployment rolled back onto the previous task
definition, which is exactly the case the first check alone would pass.

```bash
# WHAT: derives the cluster name and the per-workload service, target-group and log-group inventory
#       from the environment outputs.
# WHY : Assumptions: the names are read from Terraform rather than typed. Service names are composed
#       from name_prefix and environment inside infra/modules/ecs-service, so a typed name silently
#       describes a service that does not exist and every check below then passes vacuously.
# WHY : Assumptions: `ecs_workloads` is keyed by workload -- the seven online services plus `batch`
#       and `data-migration`. The last two run as scheduled tasks with no service and no target
#       group, which is why the loops below select on a non-null target_group_arn rather than
#       iterating every key.
cluster="$(terraform -chdir="infra/envs/<env>" output -json ecs_cluster | jq -er '.cluster_name')"
workloads="$(terraform -chdir="infra/envs/<env>" output -json ecs_workloads)"
services="$(jq -r '[.[] | select(.target_group_arn != null) | .service_name] | join(" ")' <<<"$workloads")"
printf 'cluster=%s\nservices=%s\n' "$cluster" "$services"
```

```bash
# WHAT: waits for every online service to reach a stable desired-task state after its task
#       definition changes.
# WHY : Assumptions: Terraform finishing proves the control plane accepted the update; this wait
#       proves replacement tasks passed container and load-balancer health checks.
# WHY : Trade-offs: the waiter polls for up to roughly ten minutes per invocation and then fails.
#       A timeout here is a signal, not a flake -- read the stopped reason before re-running it.
aws ecs wait services-stable --region "$AWS_REGION" --cluster "$cluster" --services $services
```

```bash
# WHAT: asserts every service's PRIMARY deployment reached rolloutState COMPLETED, and fails loudly
#       while any has not.
# WHY : Refactoring Rationale: the projection here is a hash rather than a multi-select list. A
#       multi-select list cannot contain `key:value` pairs -- `[serviceName:@.id,rolloutState]` is a
#       JMESPath PARSE ERROR, not a query that returns something unhelpful -- so a check written that
#       way fails with "Expecting: comma, got: colon" and reports nothing about the deployment. This
#       form was parsed against the projection to confirm it selects the primary deployment.
# WHY : Assumptions: it selects deployments[?status=='PRIMARY'] and indexes |[0]. A service carries
#       an ACTIVE deployment as well during a rolling replacement, and the ACTIVE one is the OLD task
#       definition -- reading its rolloutState reports the previous deployment as the current one.
# WHY : Assumptions: the count is asserted rather than eyeballed. `length(...)` returns the number of
#       services whose primary deployment has not completed, so the check is fail-closed: it stays
#       non-zero until every one of them has.
aws ecs describe-services --region "$AWS_REGION" --cluster "$cluster" --services $services \
  --query "services[].{service:serviceName,rollout:deployments[?status=='PRIMARY']|[0].rolloutState,reason:deployments[?status=='PRIMARY']|[0].rolloutStateReason}" \
  --output table

incomplete="$(aws ecs describe-services --region "$AWS_REGION" --cluster "$cluster" --services $services \
  --query "length(services[?deployments[?status=='PRIMARY' && rolloutState!='COMPLETED']])" --output text)"
if [ "$incomplete" != "0" ]; then
  echo "refusing to continue: $incomplete service(s) have an incomplete primary deployment" >&2
  exit 1
fi
```

```bash
# WHAT: asserts every load-balancer target reports healthy, which is the explicit /actuator/health
#       assertion for each service.
# WHY : Assumptions: this IS the actuator check. infra/envs/<env> sets health_check_path to
#       /actuator/health and target_protocol to HTTPS, so a target in state `healthy` is one whose
#       task answered 200 on that path over TLS. There is no external URL to curl instead: the API
#       Gateway route table publishes only /api/v1 business routes, deliberately, so the actuator is
#       reachable on the internal listener and nowhere else.
# WHY : Assumptions: it fails on ANY target that is not healthy, including `unused` and `draining`.
#       A service whose only target is draining has no capacity, and treating that as acceptable
#       because nothing is literally `unhealthy` is the failure this refuses.
# WHY : Alternatives Considered: curling https://<task-ip>:8080/actuator/health directly from inside
#       the VPC. Kept as the diagnostic below rather than the assertion, because each task mints its
#       own self-signed listener certificate, so a direct curl needs -k and then proves nothing about
#       TLS -- whereas the target group's own health check is the check the platform acts on.
unhealthy=0
while IFS=$'\t' read -r workload target_group_arn; do
  states="$(aws elbv2 describe-target-health --region "$AWS_REGION" \
    --target-group-arn "$target_group_arn" \
    --query 'TargetHealthDescriptions[].{target:Target.Id,port:Target.Port,state:TargetHealth.State,reason:TargetHealth.Reason}' \
    --output json)"
  printf '== %s\n%s\n' "$workload" "$states"
  bad="$(jq '[.[] | select(.state != "healthy")] | length' <<<"$states")"
  total="$(jq 'length' <<<"$states")"
  if [ "$total" -eq 0 ] || [ "$bad" -ne 0 ]; then unhealthy=1; fi
done < <(jq -r '. | to_entries[] | select(.value.target_group_arn != null) | [.key, .value.target_group_arn] | @tsv' <<<"$workloads")

if [ "$unhealthy" -ne 0 ]; then
  echo "refusing to continue: at least one target group has no targets or an unhealthy target" >&2
  exit 1
fi
```

```bash
# WHAT: proves each service reached steady state in its own log and logged no error after starting.
# WHY : Assumptions: a healthy target proves the actuator answered; it does not prove the service
#       started cleanly. A context that failed to bind an optional listener, or a Flyway chain that
#       logged a warning, leaves the health endpoint UP and the evidence only in the log.
# WHY : Assumptions: the start marker is Spring Boot's own "Started <Application> in" line, which
#       every one of the eight Java entry points emits. Root and com.carddemo loggers are at INFO in
#       application.yml, so the line is present without changing any level.
# WHY : Assumptions: the ERROR sweep runs over the SAME window, so it reports failures after the
#       start rather than every error the log group ever held.
# WHY : Refactoring Rationale: the loop now COUNTS its findings and exits non-zero, where it
#       previously printed one line per service and returned success regardless. A service with
#       zero start markers has not reached steady state, and printing that fact next to seven
#       healthy lines is exactly how it gets missed -- the rollout then proceeds to the smoke pass,
#       which fails somewhere unrelated. The offending log lines are printed for any group with
#       errors so the operator reads the evidence rather than re-deriving the query.
since="$(( $(date -u +%s) - 1800 ))000"
missing_start=0
error_groups=0
while IFS=$'\t' read -r workload log_group; do
  started="$(aws logs filter-log-events --region "$AWS_REGION" \
    --log-group-name "$log_group" --start-time "$since" \
    --filter-pattern '"Started" "in"' \
    --query 'length(events)' --output text)"
  errors="$(aws logs filter-log-events --region "$AWS_REGION" \
    --log-group-name "$log_group" --start-time "$since" \
    --filter-pattern 'ERROR' \
    --query 'length(events)' --output text)"
  printf '%-24s started_lines=%s error_lines=%s log_group=%s\n' "$workload" "$started" "$errors" "$log_group"
  if [ "$started" -lt 1 ]; then
    missing_start=$((missing_start + 1))
  fi
  if [ "$errors" -ne 0 ]; then
    error_groups=$((error_groups + 1))
    aws logs filter-log-events --region "$AWS_REGION" \
      --log-group-name "$log_group" --start-time "$since" \
      --filter-pattern 'ERROR' --max-items 20 \
      --query 'events[].[timestamp,message]' --output text >&2
  fi
done < <(jq -r '. | to_entries[] | select(.value.target_group_arn != null) | [.key, .value.log_group_name] | @tsv' <<<"$workloads")

if [ "$missing_start" -ne 0 ]; then
  printf 'refusing to continue: %s service(s) logged no "Started ... in" line in the last 30 minutes\n' "$missing_start" >&2
  exit 1
fi
if [ "$error_groups" -ne 0 ]; then
  printf 'refusing to continue: %s service(s) logged ERROR after starting; the lines are above\n' "$error_groups" >&2
  exit 1
fi
```

Expect `started_lines` of at least one per service and `error_lines` of zero. The gate is deliberate:
an error logged in the thirty minutes after a rollout is either a real defect or a transient that has
since cleared, and both cases want the lines read rather than a passing exit code. Re-run the block
once a transient has cleared; do not weaken it.

```bash
# WHAT: the diagnostic to run when one of the four checks above fails.
# WHY : Assumptions: the stopped reason is the only place a pull failure, a missing Parameter Store
#       key or an unresolved secret is stated plainly. Reading the log first for those cases wastes
#       a cycle, because the container never got far enough to log.
aws ecs describe-services --region "$AWS_REGION" --cluster "$cluster" --services "<service-name>" \
  --query 'services[0].events[0:10].[createdAt,message]' --output table

aws ecs list-tasks --region "$AWS_REGION" --cluster "$cluster" --service-name "<service-name>" \
  --desired-status STOPPED --query 'taskArns' --output text

aws ecs describe-tasks --region "$AWS_REGION" --cluster "$cluster" --tasks "<stopped-task-arn>" \
  --query 'tasks[0].{stopCode:stopCode,stoppedReason:stoppedReason,containers:containers[].{name:name,reason:reason,exitCode:exitCode,image:image}}'
```

Also confirm the image digest the running task definition names matches the digest published for
this release in Step 2, and check the Parameter Store and Secrets Manager references before retrying.

### Publish the SPA

The browser client is served from the S3 origin behind CloudFront, **not** from the `ui` container
image, so rolling the services above does not publish it. The bundle is published in four ordered
sub-steps, and the order is the whole point: the runtime configuration document and the
content-security policy are both derived from the API endpoint, which does not exist until the Step 3
apply created it.

#### 6a - Derive the API base URL and its origin

`npm run build` does **not** emit `config.json`. Nothing in `ui/public/` produces one and no such
file is tracked, so `ui/dist/config.json` exists only because this step writes it. Publishing without
it serves a bundle that fetches `/config.json`, receives the SPA fallback document, fails to parse it
and refuses to render — `ui/src/api/runtimeConfig.ts` rejects a malformed document rather than
falling back to a built-in default, deliberately, so a misconfiguration cannot present as a working
page talking to nothing.

```bash
# WHAT: reads the API endpoint the apply created and derives the two values the SPA needs from it --
#       the base URL the client calls, and the bare origin the policy permits.
# WHY : Assumptions: the endpoint is read from the `api_gateway` aggregate output, because that is
#       the only place it exists. The root publishes one aggregate per module and no top-level
#       `api_endpoint_url`; reading that name fails with "Output \"api_endpoint_url\" not found".
#       `jq -er` is used so an absent member exits non-zero rather than yielding `null`, which would
#       otherwise be appended to and published as the string `null/api/v1`.
# WHY : Assumptions: `/api/v1` is APPENDED here rather than read from Terraform. The output carries
#       the stage's `invoke_url`, which addresses the API and not the operations published under it;
#       every route key in `infra/modules/api-gateway-http` carries the `/api/v1` prefix itself, and
#       the SPA's own client appends only the operation path (`/auth/signon`). A base URL without the
#       prefix therefore produces a 404 from the gateway on every call, and
#       `normalizeApiBaseUrl` in `ui/src/api/runtimeConfig.ts` refuses the value outright rather than
#       silently repairing it.
# WHY : Assumptions: the trailing slash is stripped before appending. The stage is pinned to
#       `$default` (`infra/modules/api-gateway-http/variables.tf` refuses any other name), and the
#       provider renders that stage's `invoke_url` with a trailing slash, so appending directly would
#       yield `//api/v1` -- a value both the entrypoint's shape check and the browser's reject.
# WHY : Assumptions: the ORIGIN is derived separately and carries no path. A `connect-src` source is
#       matched by origin; a path in it is ignored by some agents and rejected by others, and the
#       Terraform variable below refuses one by validation.
gateway="$(terraform -chdir="infra/envs/<env>" output -json api_gateway)"
api_endpoint_url="$(jq -er '.api_endpoint_url' <<<"$gateway")"
api_base_url="${api_endpoint_url%/}/api/v1"
api_origin="$(printf '%s' "$api_base_url" | sed -E 's#^(https://[^/]+).*$#\1#')"
printf 'api_base_url=%s\napi_origin=%s\n' "$api_base_url" "$api_origin"
```

```bash
# WHAT: refuses to continue unless both derived values match the shapes their consumers enforce.
# WHY : Assumptions: these are the same two expressions the consumers apply -- the first is the
#       absolute-URL form `ui/docker-entrypoint.sh` accepts and `normalizeApiBaseUrl` mirrors, the
#       second is the origin form the `cloudfront_api_connect_src_origins` validation applies. They
#       are checked HERE because the alternatives both fail late and unhelpfully: a bad base URL
#       reaches the browser as a blank screen after publication, and a bad origin fails the apply
#       below after a plan has been reviewed.
# WHY : Trade-offs: this duplicates a rule that also lives in two other places. The duplication is
#       accepted because the cost of the three disagreeing is a value that one accepts and another
#       refuses, which is exactly the state this check makes impossible to publish.
# WHY : Trade-offs: the guard is a function returning non-zero rather than a `|| exit 1`. These blocks
#       are pasted into an interactive shell, where `exit` closes the terminal and discards
#       `api_base_url` and `api_origin` along with it -- so the operator's recovery from a shape
#       failure would begin by re-deriving the values they were told were wrong.
carddemo_require_shape() {
  if printf '%s' "$2" | grep -Eq "$3"; then
    printf 'OK   %s = %s\n' "$1" "$2"
  else
    printf 'FAIL %s: "%s" does not match %s -- stop here, do not publish\n' "$1" "$2" "$3" >&2
    return 1
  fi
}
carddemo_require_shape 'api_base_url' "$api_base_url" \
  '^https://[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?(:[0-9]{1,5})?(/[A-Za-z0-9._~-]+)*/api/v1$'
carddemo_require_shape 'api_origin' "$api_origin" \
  '^https://[a-z0-9][a-z0-9.-]*[a-z0-9](:[0-9]{1,5})?$'
```

#### 6b - Narrow the content-security policy to that origin

`cloudfront_api_connect_src_origins` was set to `[]` in Step 2a because the origin did not exist yet,
and `[]` renders `connect-src 'self'` — the fail-closed value, which permits no cross-origin call at
all. A bundle published under it loads and then has every request refused by the **browser** rather
than by a service, which reaches an operator as an unexplained failure on whichever screen asked
first. The variable refuses a wildcard by validation, so there is no first-pass value that removes
this second apply.

```bash
# WHAT: re-applies the environment with the policy narrowed to exactly the resolved API origin.
# WHY : Assumptions: the value is built with `jq -c` from the derived variable rather than typed, so
#       the origin the policy permits cannot differ from the origin the bundle calls. Those two
#       disagreeing is the one failure mode this whole sub-step exists to prevent.
# WHY : Assumptions: this is a full apply, not a targeted one. The distribution's response-headers
#       policy is the only thing that changes, but scoping the plan with `-target` would suppress
#       drift elsewhere in the graph from a review that is about to run an apply anyway.
# WHY : Trade-offs: the plan is saved and applied from the file, so the change reviewed is the change
#       executed. Expect exactly one resource update -- the response-headers policy -- and treat any
#       other proposed change as drift to investigate before applying.
export TF_VAR_cloudfront_api_connect_src_origins="$(jq -cn --arg o "$api_origin" '[$o]')"
terraform -chdir="infra/envs/<env>" plan -out="<csp-planfile>"
terraform -chdir="infra/envs/<env>" apply "<csp-planfile>"
```

#### 6c - Write and validate the runtime configuration document

```bash
# WHAT: writes ui/dist/config.json in the exact shape the SPA reads, then parses it back and
#       re-checks the value it carries.
# WHY : Assumptions: the document is a single-member object `{"apiBaseUrl": "<value>"}`. That is the
#       contract `ui/src/api/runtimeConfig.ts` enforces and the same document
#       `ui/docker-entrypoint.sh` writes for the container path, so both delivery paths publish one
#       shape rather than two spellings of it.
# WHY : Assumptions: `jq -n` composes it rather than `printf`, because jq escapes the value it is
#       given. The derived value cannot contain a quote after the check in 6a, so this is not
#       load-bearing today -- it is what keeps the step correct if the endpoint shape ever widens.
# WHY : Assumptions: the document is read back and re-validated after writing. A write that produced
#       the wrong bytes -- a full disk, a stale `dist/` from an earlier build -- is otherwise
#       discovered by the first visitor rather than here.
# WHY : Trade-offs: the read-back reports its outcome rather than only setting an exit status. A bare
#       `test ... = ...` is silent on both paths, so the one thing this check exists to surface would
#       be visible only to an operator who thought to echo `$?`.
jq -n --arg apiBaseUrl "$api_base_url" '{apiBaseUrl: $apiBaseUrl}' > ui/dist/config.json
written_api_base_url="$(jq -er '.apiBaseUrl' ui/dist/config.json)"
if [ "$written_api_base_url" = "$api_base_url" ]; then
  printf 'OK   ui/dist/config.json apiBaseUrl = %s\n' "$written_api_base_url"
else
  printf 'FAIL ui/dist/config.json carries "%s", expected "%s" -- do not publish\n' \
    "$written_api_base_url" "$api_base_url" >&2
fi
```

#### 6d - Upload the bundle, then the configuration, then invalidate

Both publication targets come from the `spa_publication` root output, whose keys are
`spa_bucket_name` and `spa_distribution_id`.

```bash
# WHAT: reads the publication target from the root output and uploads the built bundle to it.
# WHY : Assumptions: the values are read from `spa_publication` rather than from a top-level
#       spa_bucket_name or cloudfront_distribution_id output, because neither of those exists --
#       the aggregate carries them, and it spells the second one spa_distribution_id. jq -e is
#       used so a missing key exits non-zero instead of yielding an empty string that would
#       publish to `s3:///`.
# WHY : Assumptions: `config.json` is written before this sync (6c) rather than after, so the
#       `--delete` here cannot remove a document uploaded out of order, and the object it uploads is
#       corrected by the explicit cache-control upload that follows.
publication="$(terraform -chdir="infra/envs/<env>" output -json spa_publication)"
bucket="$(jq -er '.spa_bucket_name' <<<"$publication")"
distribution="$(jq -er '.spa_distribution_id' <<<"$publication")"
aws s3 sync ui/dist "s3://${bucket}/" --delete
```

```bash
# WHAT: re-uploads only the runtime configuration document with an explicit no-cache directive.
# WHY : Trade-offs: this is the one object rewritten in place on every deployment -- every other
#       asset is content-hashed and therefore safely cacheable indefinitely. Left cacheable, a
#       newly published bundle would be handed the PREVIOUS deployment's endpoint by an edge cache,
#       which presents as a working page talking to the wrong environment rather than as a failure.
aws s3 cp ui/dist/config.json "s3://${bucket}/config.json" --cache-control "no-cache, no-store, must-revalidate"
```

```bash
# WHAT: invalidates the entry document and the SPA route fallback only.
# WHY : Trade-offs: a wildcard invalidation of /* would also evict every content-hashed asset,
#       which costs a full cache refill for no benefit -- a hashed filename cannot be stale. Paying
#       for two paths keeps the rest of the distribution warm.
aws cloudfront create-invalidation --distribution-id "${distribution}" --paths "/index.html" "/config.json"
```

#### The `ui` image is the same contract, expressed as one variable

The `ui` image built in Step 2e is not part of this rollout: it runs no ECS task, which is why it is
excluded from `image_digests`. It exists for the case where the bundle is served from a container
instead of from CloudFront, and it takes the SAME value 6a derived, under one required variable:

```bash
# WHAT: runs the SPA image, serving the bundle it already carries against the resolved API.
# WHY : Assumptions: `CARDDEMO_API_BASE_URL` is REQUIRED and takes exactly the two shapes 6a
#       validates -- an absolute https URL ending in /api/v1, or the same-origin path /api/v1. The
#       entrypoint writes `config.json` itself from that value, so this path needs no `config.json`
#       step of its own, and it refuses to start on an unset or malformed value rather than serving
#       a page that cannot reach an API.
# WHY : Assumptions: nothing needs to be passed for the policy. The entrypoint derives the
#       `connect-src` origin from the same variable and unions it in, so the origin the policy
#       permits cannot contradict the origin the bundle calls.
#       `CARDDEMO_API_CONNECT_SRC_ORIGINS` remains available for an ADDITIONAL bare https origin --
#       space-separated, no path, no wildcard -- and is not needed for the API itself.
# WHY : Assumptions: the image address is read from `ecr-repositories.json`, written by Step 2d, and
#       the tag is the release tag Step 2e pushed. The tag is written as the placeholder rather than
#       reused from a shell variable, because this block is commonly run in a session that never ran
#       Step 2e -- and an unset variable would resolve the address to a bare `<repository>:`.
# WHY : Assumptions: 8080 is the container port, from `ui/nginx.conf`'s `listen 8080` and the image's
#       own `EXPOSE 8080`. It is not the default 80: the shared ALB target group forwards to 8080 for
#       every CardDemo workload, and the image serves unprivileged.
docker run --rm -p 8080:8080 \
  -e CARDDEMO_API_BASE_URL="$api_base_url" \
  "$(jq -er '.ui' ecr-repositories.json):<commit-sha>"
```

### Rotate the Cognito app-client secret

The Cognito module uses a custom API bridge because AWS provider 6.57.1 does not model multiple app
client secrets. It adds a new active secret to the existing client, updates the KMS-encrypted
Secrets Manager value, and leaves the previously current secret active during rollout. The next
rotation removes that predecessor before adding another, so the client never has more than two
active secrets.

**Rotation is triggered by a reviewed source change, not by a command-line variable.** The trigger is
`infra/modules/cognito`'s own `app_client_secret_rotation_revision` input -- a `number`, defaulting to
`1`, validated as an integer of at least 1. Neither environment root declares a variable of its own
for it and neither currently passes it, so it sits at that default until an operator writes it. To
rotate, add or increment the argument in the selected root's `module "cognito"` block:

```hcl
module "cognito" {
  source = "../../modules/cognito"
  # ... existing arguments unchanged ...
  app_client_secret_rotation_revision = <next-rotation-revision>
}
```

Refactoring Rationale: this step previously passed
`-var="cognito_app_client_secret_rotation_revision=..."`. No root declares that name, and Terraform
**rejects a `-var` naming an undeclared variable outright** -- "Value for undeclared variable",
exit 1 on 1.15.8 -- so the command could not run at all. That is the least harmful of the three
available failures, and the reason to fix the mechanism rather than the spelling: the same undeclared
name written into `terraform.tfvars` draws only a *warning* and plans on, and set through a `TF_VAR_`
environment variable it is discarded in *silence*. Either of those would have reported a successful
apply while rotating nothing. Keeping the revision in the root source instead makes each rotation a one-line diff a
reviewer can see, which is the property the module's own comment asks of it -- and it is why the
value is a monotonic integer rather than a timestamp or a free-form string that formatting could
change by accident.

Then plan and apply with the ordinary untargeted pair. Review that the plan replaces only
`terraform_data.app_client_secret_rotation`: its `triggers_replace` holds the revision, the user-pool
id, the app-client id and the secret id, so a plan touching the client itself means something other
than the revision moved.

```bash
# WHAT: plans one explicit app-client-secret rotation without changing the Cognito client id.
# WHY : Assumptions: the monotonic revision is a non-secret operator trigger; changing it is the
#       reviewable event that invokes AddUserPoolClientSecret during apply.
terraform -chdir="infra/envs/<env>" plan -var="cognito_app_client_secret_rotation_revision=<next-rotation-revision>" -out="rotation.tfplan"
```

```bash
# WHAT: applies the reviewed rotation and updates the Secrets Manager current version.
# WHY : Trade-offs: the previously current secret remains active until the next rotation so running
#       auth-service tasks keep authenticating while replacement tasks load the new value.
terraform -chdir="infra/envs/<env>" apply "rotation.tfplan"
```

```bash
# WHAT: restarts auth-service so every task resolves the new current secret.
# WHY : Assumptions: the service reads the secret during startup; without a rollout, long-running
#       tasks continue using the still-valid predecessor and the rotation is not fully adopted.
aws ecs update-service --region "<aws-region>" --cluster "<cluster-name>" --service "<auth-service-name>" --force-new-deployment
```

```bash
# WHAT: lists only app-client secret identifiers and creation dates after rotation.
# WHY : Assumptions: querying no ClientSecretValue proves the two-secret overlap without copying a
#       credential into terminal output or an operator log.
aws cognito-idp list-user-pool-client-secrets --region "<aws-region>" --user-pool-id "<user-pool-id>" --client-id "<app-client-id>" --query "ClientSecrets[].{id:ClientSecretId,created:ClientSecretCreateDate}"
```

The deployment role needs only the scoped Cognito add/list/delete client-secret actions and
Secrets Manager get/put permissions for the app-client secret, plus KMS use through Secrets Manager.
Do not grant wildcard secret access.

### If the rotation refuses: two active secrets, neither identified

The bridge **fails closed** when the client has two active secrets and the stored payload identifies
neither of them, printing the client id, the secret ARN and this remedy. It does not guess. Either of
the two unidentified secrets may be the credential running tasks are authenticating with, and
deleting that one takes the deployment down until every task is redeployed — so an apply that cannot
tell them apart stops instead of choosing.

This state is not reachable through the module's own lifecycle: a first apply finds one secret and
mints the second, and every rotation after that finds two of which one is recorded. It arises from an
out-of-band change to the client, or from a managed payload written before `client_secret_id` was
recorded in it. Resolve it by hand, then re-apply:

```bash
# WHAT: lists the identifiers and creation dates of the active secrets, and the stored payload's own
#       recorded identifier, so the two can be compared.
# WHY : Assumptions: no ClientSecretValue is queried and none is printed. Cognito returns a secret's
#       value only from the call that creates it, so the value in Secrets Manager cannot be matched
#       against a descriptor -- the recorded identifier is the only link between them, which is why
#       restoring it is the fix rather than a workaround.
aws cognito-idp list-user-pool-client-secrets --region "<aws-region>" --user-pool-id "<user-pool-id>" --client-id "<app-client-id>" --query "ClientSecrets[].{id:ClientSecretId,created:ClientSecretCreateDate}"
aws secretsmanager get-secret-value --region "<aws-region>" --secret-id "<app-client-secret-arn>" --query "SecretString" --output text | python3 -c 'import json,sys; print(json.load(sys.stdin).get("client_secret_id", "<absent>"))'
```

Then take exactly one of the two actions below.

* **The stored value is the one in use, and its identifier is simply absent.** Confirm which
  descriptor it is — the deployment's last recorded rotation time is the usual evidence — and add
  that identifier to the payload as `client_secret_id`, preserving `client_id` and `client_secret`
  unchanged. The next apply then recognises it and prunes the other.
* **The stored value is not in use, or you cannot establish which descriptor it is.** Delete the
  secret you have positively established is unused with
  `aws cognito-idp delete-user-pool-client-secret`, leaving one active. The next apply finds a free
  slot, mints into it and records the new identifier.

Do not delete a secret you have not established is unused. If neither action can be taken with
confidence, force a fresh deployment of `auth-service` first: every task then loads the value
currently in Secrets Manager, which makes that value demonstrably the one in use.

### Rotate a service database credential (operator-managed)

**No rotation schedule ships.** `infra/modules/secrets` creates one Secrets Manager entry per
service database role and generates each initial value at apply time, but it implements no rotation
function, and neither environment root supplies one through `rotation_lambda_arn` — so
`aws_secretsmanager_secret_rotation` is created with zero instances and **a stored database
credential is static until an operator replaces it**. The only rotation this package provisions
automatically is KMS *key* rotation, in `infra/modules/kms`.

Replacement itself is delivered and scripted, so no operator ever types a credential: the value is
changed inside Secrets Manager, and `carddemo_migration.credentials` reads it back, derives the
role's SCRAM-SHA-256 verifier **locally**, issues the `ALTER ROLE` that stores the verifier, and
then logs in as every role to prove the stored verifier matches what each service will read. What is
operator-managed is the *decision to run it* and the interval between runs.

Rotate one role at a time, and complete the whole sequence for that role before starting another.

```bash
# WHAT: replaces the stored value for one role's credential with a freshly generated one.
# WHY : Refactoring Rationale: this block claimed the generated value "never exists in argv, in
#       shell history or in a file" while placing it in argv itself -- the composed JSON document,
#       password included, was the expansion of --secret-string "$(...)". The claim was the right
#       requirement and the command did not meet it. The document now reaches the call on STDIN via
#       --secret-string fileb:///dev/stdin, so the requirement the comment states is the one the
#       command implements.
# WHY : Trade-offs: an argv value is readable from the process table by any local process for as
#       long as the call runs, is retained by the shell's history file, and is echoed by `set -x`.
#       `set +o xtrace` is issued explicitly because a traced shell would defeat the pipeline by
#       echoing the expansion the pipeline exists to avoid.
# WHY : Assumptions: get-random-password still has Secrets Manager produce the material, so the
#       password is never generated locally; --query null keeps the new version identifier out of
#       the transcript. The role name IS the secret name, matching V0__schemas_and_roles.sql
#       character for character.
set +o xtrace
aws secretsmanager get-random-password --region "<aws-region>" \
  --exclude-punctuation --password-length 32 --query RandomPassword --output text \
  | python3 -c 'import json,sys; print(json.dumps({"engine":"aurora-postgresql","username":"<role-name>","password":sys.stdin.read().strip(),"masteruser":"<master-username>"}))' \
  | aws secretsmanager put-secret-value --region "<aws-region>" --secret-id "<role-name>" \
  --secret-string fileb:///dev/stdin \
  --query "null" --output text
```

```bash
# WHAT: applies the replaced value to the PostgreSQL role and verifies every role can still log in.
# WHY : Trade-offs: the applicator deliberately takes no --role option, so it re-applies EVERY
#       role's current stored value rather than only the one just replaced. That is the safer
#       shape: a per-role invocation is what leaves a deployment half applied, and re-applying an
#       unchanged value is a no-op that additionally re-proves the other roles still authenticate.
CARDDEMO_ENVIRONMENT="<env>" \
CARDDEMO_PARAMETER_PREFIX="<parameter-prefix>" \
CARDDEMO_DB_MASTER_SECRET="<master-secret-arn>" \
CARDDEMO_DB_SSL_MODE="verify-full" \
CARDDEMO_DB_SSL_ROOT_CERT="<trust-anchor-path>" \
./.venv/bin/python -m carddemo_migration.credentials
```

```bash
# WHAT: restarts the one service that authenticates with the replaced role.
# WHY : Assumptions: a task resolves its credential from Secrets Manager at startup, so a running
#       task keeps using the value it already holds -- which the database no longer accepts once
#       the ALTER ROLE above has been applied. Rolling the service is what completes the rotation,
#       and it is why the sequence is performed one role at a time.
aws ecs update-service --region "<aws-region>" --cluster "<cluster-name>" --service "<service-name>" --force-new-deployment
```

Exit codes follow the return-code rubric in `tests/README.md` section 8: **0** applied and verified,
**2** usage, **8** a role could not be applied or verified, **16** the environment could not be
reached. A non-zero code means the credential in Secrets Manager and the verifier in PostgreSQL may
disagree for that role — re-run the applicator, which is idempotent, before rolling any service.

The identity running the applicator needs `secretsmanager:GetSecretValue` on the per-role entries
and the master secret, `kms:Decrypt` through Secrets Manager on the secrets CMK, and the ability to
connect to the cluster as the master role. It needs no write access to any secret. Do not grant
wildcard secret access, and do not grant the applicator identity to a service task role — a task
reads exactly one entry, its own.

**If a schedule is wanted.** Supply a rotation function's ARN and an interval to
`infra/modules/secrets` through `rotation_lambda_arn` and `rotation_automatically_after_days`, and
name that function in `infra/modules/observability`'s `rotation_lambda_function_names` so its
invocation errors alarm. Both inputs are a pass-through hook and default to null; nothing in this
package provides the function. Note the constraint recorded in `V0__schemas_and_roles.sql`: the
rotation functions AWS publishes for PostgreSQL authenticate with the credential they are replacing,
so they cannot perform a first application against a freshly created role, and a function used here
must escalate through the master identity instead.

### Replace an internal-identity signing key (operator-managed, and NOT a rolling change)

The selected environment root — not `infra/modules/secrets`, which composes database credentials
only — creates **two** further entries, `<name-prefix>/<env>/internal-identity/authorization-signing-key`
and `<name-prefix>/<env>/internal-identity/transaction-signing-key`, with `<name-prefix>` being that
root's `name_prefix` variable. Each holds the symmetric key of the machine-to-machine bearer token
one calling context presents to the account context on the internal reads described in
[security-and-identity.md](../architecture/security-and-identity.md): `authorization-service` signs
with the first, `transaction-service` signs with the second, and `account-service` verifies against
both because it holds both. Each stores a **bare string** rather than a JSON document, each is
written through the write-only argument so the value never reaches state, and each is injected into
exactly **two** task definitions — the authorization key as
`CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY` into `authorization` and `account`, the
transaction key as `CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY` into `transaction` and
`account`. No rotation function ships for either, so each is static until an operator replaces it;
each resource carries a recorded `checkov` suppression stating that reason rather than leaving the
omission unexplained.

> Refactoring Rationale: this section described **one** entry injected into two task definitions and
> a verifier built with a single key. Both were wrong, and in the same direction. The key was in fact
> injected into three task definitions, which made the two calling contexts interchangeable — with
> shared bytes, the subject a token asserts is a value its holder writes rather than a property the
> verifier can check, so either caller could mint as the other. The keys are now per caller and the
> verifier selects between them by the `kid` the token carries. The operational consequence for this
> procedure is a narrower blast radius, described next.

> **This replacement has a refusal window, and the window is unavoidable with one stored value per
> caller — but it is now confined to ONE caller.** Each consuming task reads its value once at
> startup, and the verifier holds exactly one key per subject, with no predecessor the way the
> Cognito app-client rotation above does. So from the moment the first of that key's two tasks is
> rolled until the second finishes, that caller's minter and the verifier disagree and **that
> caller's internal account-context reads are refused 401**. The other caller is unaffected, because
> its key is a different entry and neither task holding it is rolled.
>
> Replacing the **authorization** key stops pending-authorization decisions for the duration; queue
> messages are not lost, because a refused decision leaves the message to be redelivered and the
> request queue's dead-letter threshold is five receives, so a window shorter than five
> redeliveries drains rather than discards. Replacing the **transaction** key refuses the internal
> reads that back the transaction context's account lookups for the duration; those are
> request-driven rather than queue-driven, so they surface to a caller instead of draining, which is
> the reason to do this outside a traffic window rather than to rely on redelivery.
>
> Trade-offs: the alternative — teaching the verifier to accept a current and a previous key per
> subject, as the app-client rotation does — was not built, because it doubles the number of keys
> that can mint an accepted token for the entire interval between replacements, and each key's
> interruption is confined to one caller. Perform this inside the batch quiesce bracket, or during a
> period with no traffic for the affected caller, rather than adding a second simultaneously-valid
> key.

Advance that entry's `secret_string_wo_version` in a reviewed diff if the value should be regenerated
by Terraform. To replace one without an apply, do all three steps as one sequence and do not stop
between them. Substitute the one key you are replacing for `<secret-name>`, and its own two services
below — `authorization` and `account`, or `transaction` and `account`.

```bash
# WHAT: replaces the stored key with freshly generated bytes, without the value ever appearing in a
#       command line.
# WHY : Trade-offs: the value travels on STDIN via --secret-string fileb:///dev/stdin rather than as
#       an argv value. An argv value is readable by any process that can list the process table for
#       as long as the call runs, and it is recorded by the shell's own history and by `set -x`; a
#       transcript of this runbook would then contain the live key. The pipeline keeps it in memory
#       between two processes instead.
# WHY : Assumptions: --exclude-punctuation matches the generator the roots use (special = false),
#       and the 32-character floor matches the 32-BYTE minimum both consuming services enforce at
#       startup -- a shorter value makes both fail to start rather than fail to authenticate.
#       --query null keeps the new version identifier out of the transcript.
set +o xtrace   # a traced shell would echo the value this pipeline is written to hide
aws secretsmanager get-random-password --region "<aws-region>" \
  --exclude-punctuation --password-length 32 --query RandomPassword --output text \
  | tr -d '\n' \
  | aws secretsmanager put-secret-value --region "<aws-region>" \
  --secret-id "<name-prefix>/<env>/internal-identity/<secret-name>" \
  --secret-string fileb:///dev/stdin \
  --query "null" --output text
```

```bash
# WHAT: rolls that key's TWO consuming services, together rather than one after the other.
# WHY : Trade-offs: issued as two calls in immediate succession because ECS has no primitive for
#       replacing two services atomically. Ordering does not remove the window -- rolling the
#       minter first produces new credentials the old verifier refuses, and rolling the verifier
#       first produces a verifier that refuses the old credentials -- so the objective is to
#       SHORTEN the window, not to sequence it away.
# WHY : Assumptions: only the replaced key's minter is rolled. Rolling the OTHER caller too would
#       widen the outage to a service whose key did not change, which is the property the per-caller
#       split exists to provide.
aws ecs update-service --region "<aws-region>" --cluster "<cluster-name>" --service "<minting-service-name>" --force-new-deployment
aws ecs update-service --region "<aws-region>" --cluster "<cluster-name>" --service "<account-service-name>" --force-new-deployment
```

```bash
# WHAT: confirms both deployments reached a steady state, and fails while either has not.
# WHY : Refactoring Rationale: this query was
#       `services[].deployments[?status=='PRIMARY'].[serviceName:@.id,rolloutState]`, which is not
#       valid JMESPath -- a multi-select LIST cannot hold `key:value` pairs, so the CLI rejects it
#       with "Expecting: comma, got: colon" and the window was being declared closed on the strength
#       of a command that never ran. The hash form below was parsed against the projection to
#       confirm it selects the primary deployment's rollout state.
# WHY : Assumptions: it indexes `deployments[?status=='PRIMARY']|[0]`, because a rolling replacement
#       also leaves an ACTIVE deployment on the OLD task definition and reading that one reports the
#       previous deployment as the current one.
# WHY : Assumptions: PRIMARY reaching COMPLETED on both is the observable end of the mismatch;
#       treating the update-service calls above as the end would declare success while the old
#       tasks are still draining and still refusing.
services="<minting-service-name> <account-service-name>"
aws ecs wait services-stable --region "<aws-region>" --cluster "<cluster-name>" --services $services

aws ecs describe-services --region "<aws-region>" --cluster "<cluster-name>" --services $services \
  --query "services[].{service:serviceName,rollout:deployments[?status=='PRIMARY']|[0].rolloutState,reason:deployments[?status=='PRIMARY']|[0].rolloutStateReason}" \
  --output table

incomplete="$(aws ecs describe-services --region "<aws-region>" --cluster "<cluster-name>" --services $services \
  --query "length(services[?deployments[?status=='PRIMARY' && rolloutState!='COMPLETED']])" --output text)"
if [ "$incomplete" != "0" ]; then
  echo "key-replacement window still OPEN: $incomplete service(s) incomplete" >&2
  exit 1
fi
```

The identity performing this needs `secretsmanager:PutSecretValue` on that one entry,
`kms:GenerateDataKey` and `kms:Decrypt` through Secrets Manager on the secrets CMK, and
`ecs:UpdateService` and `ecs:DescribeServices` on the two services. It needs no access to any
database credential entry, no access to the other caller's key entry, and no task role should ever
be granted it — a task reads its own entry and never writes it.

---

### Rotate a sealed-token key: the pagination cursor and the card selector (operator-managed)

Two secrets in each environment root hold keys that seal a token a **client** may still be
holding: `<name-prefix>/<env>/pagination/cursor-signing-key`, read by
`com.carddemo.common.web.CursorToken`, and `<name-prefix>/<env>/security/card-selector-signing-key`,
read by the card context's selector sealer. Both carry a recorded `checkov` suppression for
`CKV2_AWS_57` stating that rotation is an **attended** procedure documented here. This section is
that procedure.

> Refactoring Rationale: both suppressions cited "an attended procedure … documented in
> `docs/runbooks/deploy.md`" while no such procedure existed in this file. A suppression whose
> justification points at a missing document is indistinguishable from an unjustified one: the
> reviewer who accepts it is accepting a promise, and the operator who needs it finds nothing. The
> two are documented together because their justifications differ only in what a stale token costs,
> and writing one and not the other would leave the same defect behind under a different name.

**They differ in blast radius, and the difference decides when you may run each.**

| Key | What a token names | Cost of invalidating every outstanding token |
|:---|:---|:---|
| `pagination/cursor-signing-key` | a position in **one** browse, and it is *meant* to expire | an operator mid-browse gets a refused cursor and re-lists. Costs a re-listing, never data |
| `security/card-selector-signing-key` | a card row's **stable address**, which a client may hold for as long as a list stays on screen | every single-card route reached from an already-rendered list stops resolving until the list is refreshed |

So the cursor key may be rotated in any low-traffic window, while the selector key should be rotated
inside the batch quiesce bracket or at a time with no interactive traffic — because the failure it
produces looks to a user like a card that has disappeared rather than like a page that needs
reloading.

**Neither rotation is a rolling change, and neither has a dual-key grace period.** Each consuming
task reads its value once at start-up and the sealer holds exactly one key, with no predecessor.
Teaching either to accept a current and a previous key was considered and not built: it would double
the number of keys that can open a token for the whole interval between rotations, and the failure it
avoids is a refused token that costs a retry rather than data.

**The cursor key has SEVEN consumers, not five.** The environment roots bind
`pagination/cursor-signing-key` to every online workload -- `auth-service`, `account-service`,
`card-service`, `transaction-service`, `reference-service`, `reporting-service` and
`authorization-service` -- and bind `security/card-selector-signing-key` to `card-service` alone.
`batch` and `data-migration` receive neither.

> Refactoring Rationale: this paragraph named five consumers and omitted `auth-service` and
> `card-service`. That is not a documentation nicety: a rotation that rolls five of seven leaves two
> tasks sealing and opening cursors under the previous key, so a browse succeeds or is refused
> depending on which task answers. An intermittent fault of that shape is materially harder to
> diagnose than the clean refusal a complete roll produces, and it is exactly what this section
> exists to prevent. The list is now derived from the roots' own `secret_sources_by_workload` map
> rather than restated, so it cannot drift again.

Derive the consumer list rather than typing it:

```bash
# WHAT: resolves the ECS service names that bind the key being rotated.
# WHY : Assumptions: the seven cursor consumers are the seven ONLINE workloads, which are exactly the
#       entries of ecs_workloads carrying a target group -- batch and data-migration carry none and
#       bind neither key. Deriving the list from the deployed inventory is what makes a rotation
#       complete by construction instead of by an operator remembering seven names.
# WHY : Alternatives Considered: hard-coding the seven names here. Rejected because that is the
#       defect being fixed -- a list in prose drifted from the roots once already.
cluster="$(terraform -chdir="infra/envs/<env>" output -json ecs_cluster | jq -er '.cluster_name')"
workloads="$(terraform -chdir="infra/envs/<env>" output -json ecs_workloads)"

# The cursor key: all seven online services.
cursor_services="$(jq -r '[.[] | select(.target_group_arn != null) | .service_name] | join(" ")' <<<"$workloads")"

# The selector key: card-service alone.
selector_service="$(jq -r '.["card-service"].service_name' <<<"$workloads")"

printf 'cursor consumers: %s\nselector consumer: %s\n' "$cursor_services" "$selector_service"
test "$(wc -w <<<"$cursor_services")" -eq 7 || { echo "expected seven cursor consumers" >&2; exit 1; }
```

Substitute the key you are rotating for `<secret-name>` below, and set `rotation_services` to
`$cursor_services` or `$selector_service` accordingly.

```bash
# WHAT: replaces the stored key with freshly generated bytes, without the value ever appearing in a
#       command line.
# WHY : Trade-offs: the value travels on STDIN via --secret-string fileb:///dev/stdin. An argv value
#       is readable from the process table by any local process for as long as the call runs, is
#       retained by the shell's history file, and is echoed by `set -x`; a transcript of this runbook
#       would then contain the live key. `set +o xtrace` is issued explicitly for that last reason.
# WHY : Assumptions: --exclude-punctuation matches the generator both roots use (special = false),
#       and 32 characters matches the 32-BYTE floor the sealers enforce at start-up -- a shorter
#       value makes every consumer fail to start rather than fail to open a token, which is the
#       safer of the two failures but is still a failure to avoid. --query null keeps the new
#       version identifier out of the transcript.
set +o xtrace
aws secretsmanager get-random-password --region "<aws-region>" \
  --exclude-punctuation --password-length 32 --query RandomPassword --output text \
  | tr -d '\n' \
  | aws secretsmanager put-secret-value --region "<aws-region>" \
  --secret-id "<name-prefix>/<env>/<secret-name>" \
  --secret-string fileb:///dev/stdin \
  --query "null" --output text
```

```bash
# WHAT: rolls every service that binds the rotated key.
# WHY : Assumptions: ALL of that key's consumers are rolled, and rolling only some is the one
#       mistake this step exists to prevent. Two tasks holding different cursor keys will refuse
#       each other's tokens, so a partially rolled fleet produces a browse that works or fails
#       depending on which task answers -- an intermittent fault, which is materially harder to
#       diagnose than the clean refusal a complete roll produces.
# WHY : Trade-offs: issued as separate calls because ECS has no primitive for replacing several
#       services atomically. Ordering does not remove the window, so the objective is to shorten it.
# WHY : Refactoring Rationale: the loop iterated a single "<service-name>" placeholder, so following
#       it literally rolled ONE service and left the rest of the fleet on the old key. It now
#       iterates the derived list, and the word count assertion above refuses to start a cursor
#       rotation that has not resolved all seven.
rotation_services="$cursor_services"    # or: rotation_services="$selector_service"
for service in $rotation_services ; do
  aws ecs update-service --region "$AWS_REGION" --cluster "$cluster" \
    --service "$service" --force-new-deployment --query 'null' --output text
done
```

```bash
# WHAT: confirms every rolled deployment reached a steady state, and FAILS while any has not.
# WHY : Refactoring Rationale: the query here was
#       `services[].deployments[?status=='PRIMARY'].[serviceName:@.id,rolloutState]`, which is not a
#       valid JMESPath expression at all -- a multi-select LIST cannot contain `key:value` pairs, so
#       the CLI rejects it with "Expecting: comma, got: colon" and the check reported nothing. A
#       rotation was therefore being declared complete on the strength of a command that never ran.
#       The hash form below was parsed against the projection to confirm it selects the primary
#       deployment's rollout state.
# WHY : Assumptions: it selects `deployments[?status=='PRIMARY']|[0]`. During a rolling replacement a
#       service also carries an ACTIVE deployment, and that one is the OLD task definition -- reading
#       its rolloutState reports the previous deployment as the current one.
# WHY : Assumptions: PRIMARY reaching COMPLETED is the observable end of the mixed-key interval;
#       treating the update-service calls as the end would declare success while old tasks are still
#       draining and still sealing tokens under the previous key.
# WHY : Assumptions: the loop is fail-closed. It keeps waiting while any primary deployment is
#       incomplete and exits non-zero on timeout, so a stalled roll cannot be mistaken for a
#       finished one.
aws ecs wait services-stable --region "$AWS_REGION" --cluster "$cluster" --services $rotation_services

aws ecs describe-services --region "$AWS_REGION" --cluster "$cluster" --services $rotation_services \
  --query "services[].{service:serviceName,rollout:deployments[?status=='PRIMARY']|[0].rolloutState,reason:deployments[?status=='PRIMARY']|[0].rolloutStateReason}" \
  --output table

incomplete="$(aws ecs describe-services --region "$AWS_REGION" --cluster "$cluster" --services $rotation_services \
  --query "length(services[?deployments[?status=='PRIMARY' && rolloutState!='COMPLETED']])" --output text)"
if [ "$incomplete" != "0" ]; then
  echo "rotation INCOMPLETE: $incomplete service(s) still on the previous key" >&2
  exit 1
fi
```

Advance that secret's `secret_string_wo_version` in a reviewed diff instead if the value should be
regenerated by Terraform. The identity performing the manual form needs
`secretsmanager:PutSecretValue` on that one entry, `kms:GenerateDataKey` and `kms:Decrypt` through
Secrets Manager on the secrets CMK, and `ecs:UpdateService` and `ecs:DescribeServices` on that key's
consumers. No task role should ever hold `PutSecretValue`: a task reads these entries and never
writes them.

---

## Step 7 - Smoke-verify candidate business flows

Retrieve a generated seed credential only through Secrets Manager, and hand it straight to the
request that consumes it. Seed users are created by the apply with generated initial passwords
written beneath the name prefix the `credential_secret_name_prefix` output publishes, so no
credential is ever typed, committed, or chosen by an operator -- and the block below is written so
that none is displayed either.

Every block in this step runs under `set -euo pipefail` in one shell, in file order, and the two
helpers below are what make it a verification rather than a transcript. Define them first.

```bash
# WHAT: defines the two helpers every block in this step uses -- an HTTP status ASSERTION and a
#       tracing guard that restores the caller's tracing state rather than switching tracing on.
# WHY : Refactoring Rationale: the flows below previously PRINTED each status with curl's
#       `-w 'http=%{http_code} (expect 200)'`. A printed status is not a check: the sequence
#       continues on a 500, later flows build on a session that was never established, and the
#       operator has to read nine lines of output to notice. expect_status compares and exits 1, so
#       the first wrong status stops the pass at the flow that produced it.
# WHY : Refactoring Rationale: hide_trace/show_trace replace paired `set +o xtrace` and
#       `set -o xtrace 2>/dev/null || true`. The second of those does not restore anything -- it
#       ENABLES tracing, so in a shell that never had it every later command echoed its arguments,
#       including the ones expanding SEED_PASSWORD and ACCESS_TOKEN. That turned a disclosure guard
#       into a disclosure. show_trace re-enables only what hide_trace found on.
# WHY : Assumptions: $- inside a function reports the shell's current option letters, and bash does
#       not scope `set` to a function body, so hiding and restoring from inside these two is
#       equivalent to doing it inline. Step 4 states the same contract inline because it needs it
#       once; here it is needed seven times.
# WHY : Assumptions: expect_status writes the failure to stderr and the confirmation to stdout, so
#       a wrapper capturing only stdout still shows the pass line and a `2>` capture isolates the
#       failure. Neither stream ever receives a token or a password.
expect_status() {   # expect_status <expected> <actual> <label>
  if [ "$2" != "$1" ]; then
    printf '%s: expected HTTP %s, got %s\n' "$3" "$1" "$2" >&2
    exit 1
  fi
  printf '%s: HTTP %s as expected\n' "$3" "$2"
}

hide_trace() { case "$-" in *x*) CARDDEMO_XTRACE=1 ;; *) CARDDEMO_XTRACE=0 ;; esac; set +o xtrace; }
show_trace() { if [ "${CARDDEMO_XTRACE:-0}" = 1 ]; then set -o xtrace; fi; }
```

```bash
# WHAT: lists the seed-user secret names for this environment and selects one, disclosing no
#       credential and no account inventory.
# WHY : Assumptions: the map is keyed by an OPAQUE handle rather than by user id, deliberately, so
#       that possession of the state is not a map of whose credential to fetch. Pick any entry: the
#       username needed for sign-on is inside the payload, not in the key.
seed_names="$(terraform -chdir="infra/envs/<env>" output -json cognito | jq -er '.seed_user_secret_names')"
jq -r 'keys[]' <<<"$seed_names"
seed_secret="$(jq -er 'to_entries[0].value' <<<"$seed_names")"
```

```bash
# WHAT: loads one seed user's generated initial credential into shell variables WITHOUT emitting the
#       password, then reports only non-secret metadata.
# WHY : Refactoring Rationale: this block previously ended in
#       `get-secret-value ... --query SecretString --output text`, which prints the whole payload --
#       username AND generated password -- to the terminal. That places a live credential in the
#       scrollback, in any session recording, and in the log of any wrapper the operator ran the
#       runbook under, and no later step can retract it. The value is now captured by command
#       substitution and never written to a stream.
# WHY : Refactoring Rationale: the baseline published its demo sign-on credentials in its own
#       deployment documentation, so anyone with the document had the credentials and rotating them
#       meant editing prose. Nothing here carries a credential: the value is generated at apply time
#       into Secrets Manager and read back at the moment it is needed, which is why this runbook can
#       be public and the deployment still not be.
# WHY : Assumptions: hide_trace is required, not decorative. Under tracing the shell echoes each
#       assignment with the substitution already expanded, which reproduces exactly the disclosure
#       this shape removes; show_trace restores only the tracing state the caller arrived with.
# WHY : Assumptions: the username IS printed and the password is not. The user id is already legible
#       in the root's own `seed_users` input, so it discloses nothing new, and the operator needs it
#       to sign on; the password discloses everything and is needed only by curl.
# WHY : Trade-offs: SEED_PASSWORD lives in this shell's environment for the life of the shell.
#       Accepted over writing it to a file, which outlives the session. Close the shell afterwards.
# WHY : Alternatives Considered: printing the payload and having the operator copy the password into
#       the next command. Rejected -- that is the disclosure, restated as a workflow.
hide_trace
seed_payload="$(aws secretsmanager get-secret-value --region "$AWS_REGION" --secret-id "$seed_secret" --query SecretString --output text)"
SEED_USER="$(jq -er '.username' <<<"$seed_payload")"
SEED_PASSWORD="$(jq -er '.password' <<<"$seed_payload")"
unset seed_payload
show_trace

printf 'seed user: %s\n' "$SEED_USER"
aws secretsmanager describe-secret --region "$AWS_REGION" --secret-id "$seed_secret" \
  --query '{arn:ARN,lastChanged:LastChangedDate}'
```

The identity running that block needs `secretsmanager:GetSecretValue` on that one entry and
`kms:Decrypt` through Secrets Manager on the key it is encrypted with. **No task role holds either
grant for a seed-user entry**: a seed credential is consumed once, by an operator, at this step, so
granting it to a running service would widen the blast radius for no runtime purpose.

```bash
# WHAT: resolves the API base URL every smoke command below is issued against.
# WHY : Assumptions: this is the API Gateway HTTP API invoke URL, which already includes the stage,
#       so the paths below are appended to it unchanged. The internal ALB is not reachable from
#       outside the VPC and is not the smoke-test entry point.
export CARDDEMO_API="$(terraform -chdir="infra/envs/<env>" output -json api_gateway | jq -er '.api_endpoint_url')"
```

**Every published route carries the `/api/v1` prefix, including the four unauthenticated auth
routes.** `POST /auth/signon` does not exist; the route is `POST /api/v1/auth/signon`. A request to
the unprefixed path is not routed by the HTTP API and returns `404 Not Found`, which reads like a
service outage rather than a wrong path.

The first sign-on with a generated password is expected to require a change; the challenge exchange
below is that step, not a failure.

### Flow 1 - Sign-on and first-use challenge

```bash
# WHAT: signs on, completes the first-use password challenge when one is returned, and keeps the
#       access token for every authenticated flow below.
# WHY : Assumptions: a generated initial password is issued as a challenge, so the FIRST response is
#       expected to be a challenge rather than a token set. Treating that as a failure is the most
#       common misreading of this step.
# WHY : Assumptions: the challenge completion needs userId, session and newPassword -- the session
#       comes from the challenge response and expires, so the two calls are one sequence.
# WHY : Assumptions: -sS -o file -w '%{http_code}' is used instead of piping the body to jq so the
#       status code is asserted separately from the body. A 401 body is valid JSON and would parse.
# WHY : Assumptions: 200 is the only acceptable status for BOTH calls. A challenge is carried in a
#       200 body as challengeName, not as a 4xx, so asserting 200 does not conflict with the
#       challenge being expected -- and a 401 from a wrong seed secret now stops the pass here
#       instead of leaving every later flow to fail on an empty bearer token.
hide_trace
signon_status="$(curl -sS -o signon.json -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/auth/signon" \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg u "$SEED_USER" --arg p "$SEED_PASSWORD" '{userId:$u,password:$p}')")"
show_trace
expect_status 200 "$signon_status" signon
jq -r 'if .challengeName? then "challenge: \(.challengeName)" else "tokens issued" end' signon.json

# Complete the challenge only when one was returned. NEW_PASSWORD must satisfy the pool policy.
hide_trace
if jq -e '.challengeName? // empty' signon.json > /dev/null; then
  challenge_status="$(curl -sS -o signon.json -w '%{http_code}' \
    -X POST "$CARDDEMO_API/api/v1/auth/challenge" \
    -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg u "$SEED_USER" --arg s "$(jq -r .session signon.json)" --arg n "$NEW_PASSWORD" \
      '{userId:$u,session:$s,newPassword:$n}')")"
else
  challenge_status=200
fi
ACCESS_TOKEN="$(jq -r '.accessToken' signon.json)"
REFRESH_TOKEN="$(jq -r '.refreshToken' signon.json)"
show_trace
rm -f signon.json
expect_status 200 "$challenge_status" challenge

# WHAT: proves a usable bearer token was obtained without echoing it.
# WHY : Assumptions: the emptiness test runs with tracing suppressed, because under tracing the
#       shell would echo the expanded token as the command's own argument -- the disclosure the
#       guard exists to prevent, reintroduced by the check that proves the guard worked.
hide_trace
if [ -z "$ACCESS_TOKEN" ] || [ "$ACCESS_TOKEN" = null ]; then
  show_trace
  echo 'sign-on returned no access token; the flows below cannot run' >&2
  exit 1
fi
show_trace
```

> `signon.json` holds live tokens while it exists, which is why it is deleted in the same block.
> Never leave it in the working tree.
>
> Sign-out is **Flow 10**, at the end of this step. It revokes the grant these flows authenticate
> with, so running it here would leave every flow below unauthenticated.

### Flow 2 - Account view, validate and conflict

```bash
# WHAT: reads one account, captures its ETag, revalidates an update against it, and proves a stale
#       version is refused.
# WHY : Assumptions: /api/v1/accounts/update/validate performs the full validation chain and WRITES
#       NOTHING, so it is the safe way to exercise the update path on a live environment.
# WHY : Assumptions: If-Match is REQUIRED on the update route, and the value comes from the view
#       response's ETag. A deliberately wrong value is the conflict probe -- it is refused before
#       anything is written, so it is non-destructive.
# WHY : Assumptions: the expected conflict status is 409, which is the optimistic-concurrency
#       response the COBOL before-image comparison became. A 200 here would mean the version check
#       is not being enforced.
# WHY : Refactoring Rationale: the stale precondition is DERIVED from the live one -- the live tag
#       with a fixed prefix -- where it was previously the hard-coded literal "0". A hard-coded
#       token is a wager that it differs from whatever the row currently holds, and a wager the
#       operator cannot check before making it: on the wrong row the precondition MATCHES and the
#       conflict probe becomes a real update against a live account. Prefixing the tag the view
#       just returned cannot equal that tag, so the refusal is guaranteed by construction and the
#       probe writes nothing whatever the row's version happens to be.
# WHY : Assumptions: the precondition is compared for equality against an opaque server-issued
#       token and is not parsed, so any non-blank value that differs yields 409 rather than 400.
#       That is what makes a derived, deliberately-unmatchable string a valid probe.
ACCOUNT_ID="<11-digit-account-id>"
etag="$(curl -sS -D - -o account.json \
  -X POST "$CARDDEMO_API/api/v1/accounts/view" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg a "$ACCOUNT_ID" '{accountId:$a}')" \
  | awk 'tolower($1) == "etag:" { gsub("\r", "", $2); print $2 }')"
printf 'account etag=%s\n' "$etag"
test -n "$etag" || { echo 'account view returned no ETag; the conflict probe below would be unsafe' >&2; exit 1; }

validate_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/accounts/update/validate" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -H "If-Match: $etag" --data @account-update.json)"
expect_status 200 "$validate_status" account-update-validate

stale_etag="\"stale-$(printf '%s' "$etag" | tr -d '"')\""
printf 'derived stale etag=%s\n' "$stale_etag"

stale_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/accounts/update" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -H "If-Match: $stale_etag" --data @account-update.json)"
expect_status 409 "$stale_status" account-stale-update
```

> Build `account-update.json` from the `account.json` body this block wrote, changing at most one
> allowed field. The request schema is `AccountUpdateRequest` in
> `services/account-service/src/main/resources/openapi/account-api.yaml`; take the field list from
> there rather than from this runbook.

### Flow 3 - Card list, lookup and conflict

```bash
# WHAT: lists cards narrowed by account, resolves one card to its opaque key, and proves a stale
#       version is refused on update.
# WHY : Assumptions: no primary account number appears in a request line or a query string on any
#       card route. Search and lookup are POSTs carrying the number in the BODY, and every later call
#       addresses the card by the opaque cardKey the response returns, so no card number can reach an
#       access log, a referrer header or a browser history.
# WHY : Assumptions: the stale-version PUT is non-destructive ONLY because the version it submits
#       cannot be the row's current one. The card route compares the submitted version to the stored
#       version for equality, so it is the derivation below -- live version plus one -- that makes
#       the 409 certain. The administrative full-number read sits on /api/v1/admin/cards/{cardKey}
#       and is not part of this smoke pass.
# WHY : Refactoring Rationale: this block submitted the literal "version": 0 before. Cards loaded by
#       the cutover ETL have never been updated, so their stored version IS 0 -- the probe therefore
#       matched, passed the concurrency check, and REWROTE the embossed name, status and expiry of a
#       live card with the smoke-test values in the body. Deriving the version from the lookup and
#       adding one is the same probe with the mutation removed.
card_search_status="$(curl -sS -o card-search.json -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/cards/search" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg a "$ACCOUNT_ID" '{accountId:$a}')")"
expect_status 200 "$card_search_status" card-search
jq '{count:(.items|length)}' card-search.json

card_lookup_status="$(curl -sS -o card.json -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/cards/lookup" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg c "<16-digit-card-number>" '{cardNumber:$c}')")"
expect_status 200 "$card_lookup_status" card-lookup
card_key="$(jq -er '.cardKey' card.json)"
card_version="$(jq -er '.version' card.json)"
stale_version=$((card_version + 1))
printf 'card version=%s, probing with %s\n' "$card_version" "$stale_version"

stale_card_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X PUT "$CARDDEMO_API/api/v1/cards/$card_key" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data "$(jq -nc --argjson v "$stale_version" \
    '{embossedName:"SMOKE TEST",activeStatus:"Y",expiryMonth:"12",expiryYear:"2030",version:$v}')")"
expect_status 409 "$stale_card_status" card-stale-update
rm -f card-search.json card.json
```

### Flow 4 - Transaction add and list

```bash
# WHAT: previews a transaction without writing it, then lists transactions for the same card.
# WHY : Assumptions: OMITTING `confirmation` makes the add route a PREVIEW that returns 200 and
#       writes nothing; only an affirmative Y confirms and returns 201. This smoke pass previews, so
#       it leaves no ledger row behind. Send confirmation:"Y" only if a posted row is wanted, and
#       record that it is test data.
# WHY : Assumptions: the amount is a fixed-point STRING, never a JSON number. A JSON number is
#       parsed into an IEEE-754 double by most clients, which is exactly the exactness loss the money
#       path exists to prevent -- so the list response must echo the same decimal string.
preview_status="$(curl -sS -o transaction-preview.json -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/transactions" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data @transaction-add.json)"
expect_status 200 "$preview_status" transaction-preview
jq '{amount:.amount,preview:true}' transaction-preview.json

list_status="$(curl -sS -o transaction-list.json -w '%{http_code}' -G "$CARDDEMO_API/api/v1/transactions" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  --data-urlencode "cardNumber=<16-digit-card-number>")"
expect_status 200 "$list_status" transaction-list
jq '{count:(.items|length),first:.items[0].amount}' transaction-list.json
rm -f transaction-preview.json transaction-list.json
```

> Build `transaction-add.json` from the `TransactionCreateRequest` schema in
> `services/transaction-service/src/main/resources/openapi/transaction-api.yaml`. Leave
> `confirmation` out for the preview.

### Flow 5 - Bill pay

```bash
# WHAT: previews a bill payment, returning the payable balance and posting nothing.
# WHY : Assumptions: as with the add route, an absent or declining `confirmation` makes this a
#       preview -- the payable balance is computed and returned, and no ledger or account row is
#       written. That is what makes bill pay safe to exercise against a live environment.
# WHY : Assumptions: only "Y" or "y" confirms. Any other value, including absence, previews.
billpay_status="$(curl -sS -o billpay.json -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/billpay" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg a "$ACCOUNT_ID" '{accountId:$a}')")"
expect_status 200 "$billpay_status" billpay-preview
jq '.' billpay.json
rm -f billpay.json
```

### Flow 6 - Transaction-type reference data

```bash
# WHAT: reads the reference types, then proves a referenced type cannot be deleted.
# WHY : Assumptions: the expected result of the delete is 409, produced by the ON DELETE RESTRICT
#       foreign key that preserves the baseline XTRNTYCAT relationship. The row is NOT removed, so
#       the probe is non-destructive -- but only for a type that categories actually reference.
# WHY : Refactoring Rationale: the type code is now DERIVED from the category listing, where it was
#       a `<referenced-type-cd>` placeholder the operator filled in by eye. An unreferenced code
#       substituted there is not refused: RESTRICT has nothing to restrict, so the DELETE SUCCEEDS
#       and permanently removes seeded reference data from a live environment. Reading the first
#       category's own typeCd makes the referencing row the thing that chose the code, so a 409 is
#       structurally guaranteed and the probe cannot delete.
# WHY : Assumptions: a 204 from this call is a finding rather than a success -- with a derived code
#       it can only mean the constraint is missing -- which is why the status is asserted as 409
#       instead of printed.
types_status="$(curl -sS -o reference-types.json -w '%{http_code}' \
  "$CARDDEMO_API/api/v1/reference/transaction-types" -H "Authorization: Bearer $ACCESS_TOKEN")"
expect_status 200 "$types_status" reference-type-list
jq '[.items[].typeCd]' reference-types.json

categories_status="$(curl -sS -o reference-categories.json -w '%{http_code}' \
  "$CARDDEMO_API/api/v1/reference/transaction-categories" -H "Authorization: Bearer $ACCESS_TOKEN")"
expect_status 200 "$categories_status" reference-category-list
referenced_type="$(jq -er '.items[0].typeCd' reference-categories.json)"
printf 'probing DELETE against referenced type %s\n' "$referenced_type"

delete_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X DELETE "$CARDDEMO_API/api/v1/reference/transaction-types/$referenced_type" \
  -H "Authorization: Bearer $ACCESS_TOKEN")"
expect_status 409 "$delete_status" reference-type-delete-restricted
rm -f reference-types.json reference-categories.json
```

### Flow 7 - Transaction posting batch

```bash
# WHAT: starts one daily posting execution for an explicit business date and reads its outcome.
# WHY : Assumptions: the business date is a PARAMETER and never the wall clock, which is what makes a
#       rerun reproducible. This is the same contract the JCL expressed as PARM='2022071800'.
# WHY : Assumptions: a non-zero reject count is a WARN outcome and not a failure -- the chain has a
#       soft-warn edge for it, mirroring the baseline's COND=(4,LT). Read the reject count before
#       concluding the batch failed.
# WHY : Refactoring Rationale: the outcome is POLLED to a terminal status and then asserted, where
#       this block previously described the execution once immediately after starting it. One
#       describe issued that early always reports RUNNING, so the printed line said nothing about
#       whether the chain succeeded -- and a FAILED, TIMED_OUT or ABORTED run looked identical to a
#       healthy one in the transcript.
daily_arn="$(terraform -chdir="infra/envs/<env>" output -json batch_orchestration | jq -er '.daily_state_machine_arn')"
execution_arn="$(aws stepfunctions start-execution --region "$AWS_REGION" \
  --state-machine-arn "$daily_arn" \
  --input "$(jq -nc --arg d "<YYYY-MM-DD>" '{businessDate:$d}')" \
  --query executionArn --output text)"
printf 'execution=%s\n' "$execution_arn"

# WHY : Assumptions: 120 polls at 30 seconds bounds the wait at one hour, which is longer than the
#       chain's own aggregate state timeouts -- so exhausting the loop means the execution is stuck
#       rather than slow, and failing is the correct response.
batch_status=RUNNING
for _ in $(seq 1 120); do
  batch_status="$(aws stepfunctions describe-execution --region "$AWS_REGION" \
    --execution-arn "$execution_arn" --query status --output text)"
  case "$batch_status" in RUNNING) sleep 30 ;; *) break ;; esac
done

aws stepfunctions describe-execution --region "$AWS_REGION" --execution-arn "$execution_arn" \
  --query '{status:status,startDate:startDate,stopDate:stopDate}'

if [ "$batch_status" != SUCCEEDED ]; then
  printf 'daily posting execution ended %s; read the failed state before rerunning\n' "$batch_status" >&2
  aws stepfunctions get-execution-history --region "$AWS_REGION" --execution-arn "$execution_arn" \
    --reverse-order --max-items 20 \
    --query 'events[?type==`ExecutionFailed` || type==`TaskFailed` || type==`TaskStateAborted`].[type,taskFailedEventDetails.error,taskFailedEventDetails.cause]' >&2
  exit 1
fi
```

### Flow 8 - Pending authorization (FIFO request/reply)

```bash
# WHAT: publishes one eighteen-field authorization request and reads the correlated reply.
# WHY : Assumptions: the wire format IS the contract. The payload is exactly eighteen comma-delimited
#       fields, each space-padded to its declared width, for a total of 170 characters -- the widths
#       are 6,6,16,4,4,6,6,6,14,4,3,2,15,22,13,2,9,15 and the money field is sign, ten integer digits,
#       a point and two fraction digits. The consumer refuses a payload of any other length, so the
#       length assertion below is the check that matters before sending.
# WHY : Assumptions: MessageGroupId is the CARD NUMBER and MessageDeduplicationId is the TRANSACTION
#       ID. The group preserves per-card ordering exactly as the IBM MQ queue did; the deduplication
#       id gives exactly-once acceptance inside the five-minute window.
# WHY : Assumptions: replyToQueueUrl, correlationId and contentType are all REQUIRED attributes. The
#       consumer refuses a message whose reply destination is absent or not allow-listed, so a
#       missing attribute fails the message rather than producing a reply nobody receives.
# WHY : Assumptions: expiresAt is deliberately OMITTED. An absent expiry means no expiry, which is
#       what a smoke message wants; the attribute exists because SQS has no per-message time-to-live
#       and the baseline set a five-second one.
queues="$(terraform -chdir="infra/envs/<env>" output -json messaging)"
request_url="$(jq -er '.pauth_request_queue_url' <<<"$queues")"
reply_url="$(jq -er '.pauth_reply_queue_url' <<<"$queues")"

card="<16-digit-card-number>"
txn="<15-char-transaction-id>"
pad() { printf '%-*.*s' "$1" "$1" "$2"; }
body="$(printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s' \
  "$(pad 6 '250101')" "$(pad 6 '120000')" "$(pad 16 "$card")" "$(pad 4 '01')" \
  "$(pad 4 '1230')" "$(pad 6 '0100')" "$(pad 6 'SMOKE')" "$(pad 6 '000000')" \
  '+0000000012.34' "$(pad 4 '5411')" "$(pad 3 'USA')" "$(pad 2 '05')" \
  "$(pad 15 'SMOKEMERCH')" "$(pad 22 'SMOKE TEST MERCHANT')" "$(pad 13 'SMOKETOWN')" \
  "$(pad 2 'NY')" "$(pad 9 '10001')" "$(pad 15 "$txn")")"
test "${#body}" -eq 170 || { echo "payload is ${#body} characters, must be 170" >&2; exit 1; }

# WHY : Refactoring Rationale: the correlation id is now a NAMED variable that the receive loop
#       matches on, where the send previously generated it inline and the receive printed whatever
#       message happened to arrive first. On a queue carrying any other traffic that reads back a
#       stranger's reply and reports it as this flow's result -- and, worse, consumes it. The loop
#       below returns a non-matching message to the queue by leaving its visibility timeout to
#       expire rather than deleting it, and only deletes the one it matched.
correlation="smoke-pauth-$(date -u +%s)"
aws sqs send-message --region "$AWS_REGION" --queue-url "$request_url" \
  --message-body "$body" \
  --message-group-id "$card" \
  --message-deduplication-id "$txn" \
  --message-attributes "$(jq -nc --arg r "$reply_url" --arg c "$correlation" \
    '{replyToQueueUrl:{DataType:"String",StringValue:$r},
      correlationId:{DataType:"String",StringValue:$c},
      contentType:{DataType:"String",StringValue:"text/csv"}}')" \
  --query '{messageId:MessageId,sequenceNumber:SequenceNumber}'

# WHY : Assumptions: twelve receives at a twenty-second long poll bounds the wait at four minutes,
#       which is far longer than the consumer's own processing time, so exhausting the loop means no
#       reply was produced rather than that the reply was slow.
# WHY : Assumptions: length(Body) is asserted at 63 -- the reply is six fields at widths 16, 15, 6,
#       2, 4 and 14 -- because a reply of the wrong length is a codec regression that a printed body
#       does not make obvious.
reply_body=""
for _ in $(seq 1 12); do
  received="$(aws sqs receive-message --region "$AWS_REGION" --queue-url "$reply_url" \
    --wait-time-seconds 20 --max-number-of-messages 10 \
    --message-attribute-names correlationId)"
  reply_body="$(jq -r --arg c "$correlation" \
    '(.Messages // []) | map(select(.MessageAttributes.correlationId.StringValue == $c)) | .[0].Body // empty' \
    <<<"$received")"
  receipt="$(jq -r --arg c "$correlation" \
    '(.Messages // []) | map(select(.MessageAttributes.correlationId.StringValue == $c)) | .[0].ReceiptHandle // empty' \
    <<<"$received")"
  if [ -n "$reply_body" ]; then
    aws sqs delete-message --region "$AWS_REGION" --queue-url "$reply_url" --receipt-handle "$receipt"
    break
  fi
done

if [ -z "$reply_body" ]; then
  printf 'no authorization reply correlated to %s arrived; check the FIFO consumer and its DLQ\n' "$correlation" >&2
  exit 1
fi
printf 'reply=%s\n' "$reply_body"
test "${#reply_body}" -eq 63 || { echo "reply is ${#reply_body} characters, must be 63" >&2; exit 1; }
```

The reply is six comma-delimited fields at widths 16, 15, 6, 2, 4 and 14 -- card number,
transaction id, authorization id code, response code, response reason and approved amount, 63
characters in total. Fraud-marking is exercised over the API:
`POST /api/v1/authorizations/search` with `{"accountId": ...}` to list, then the fraud route on the
selected key.

### Flow 9 - Account inquiry (standard request/reply)

```bash
# WHAT: publishes one fixed-width account inquiry and reads the correlated reply.
# WHY : Assumptions: the message is exactly 1000 characters -- a four-character function code, an
#       eleven-character account key, and 985 characters of filler. The consumer measures the message
#       rather than parsing it loosely, so a short body is refused.
# WHY : Assumptions: the function code for an account inquiry is INQA. Any other value is reported as
#       unrecognised rather than being guessed at.
# WHY : Assumptions: the inquiry queues are STANDARD, not FIFO -- inquiry has no ordering requirement
#       -- so no MessageGroupId or MessageDeduplicationId is sent, and sending one would be rejected.
inquiry_request_url="$(jq -er '.inquiry_request_queue_url' <<<"$queues")"
inquiry_reply_url="$(jq -er '.inquiry_reply_queue_url' <<<"$queues")"

inquiry_body="$(printf 'INQA%s%985s' "$ACCOUNT_ID" '')"
test "${#inquiry_body}" -eq 1000 || { echo "inquiry payload is ${#inquiry_body} characters, must be 1000" >&2; exit 1; }

# WHY : Assumptions: the same correlated receive loop as Flow 8 is used and for the same reason --
#       a standard queue makes no ordering promise at all, so the first message received is even
#       less likely to be this flow's reply than on the FIFO queue.
inquiry_correlation="smoke-inquiry-$(date -u +%s)"
aws sqs send-message --region "$AWS_REGION" --queue-url "$inquiry_request_url" \
  --message-body "$inquiry_body" \
  --message-attributes "$(jq -nc --arg r "$inquiry_reply_url" --arg c "$inquiry_correlation" \
    '{replyToQueueUrl:{DataType:"String",StringValue:$r},
      correlationId:{DataType:"String",StringValue:$c},
      contentType:{DataType:"String",StringValue:"text/plain"}}')" \
  --query 'MessageId'

inquiry_reply=""
for _ in $(seq 1 12); do
  received="$(aws sqs receive-message --region "$AWS_REGION" --queue-url "$inquiry_reply_url" \
    --wait-time-seconds 20 --max-number-of-messages 10 \
    --message-attribute-names correlationId)"
  inquiry_reply="$(jq -r --arg c "$inquiry_correlation" \
    '(.Messages // []) | map(select(.MessageAttributes.correlationId.StringValue == $c)) | .[0].Body // empty' \
    <<<"$received")"
  receipt="$(jq -r --arg c "$inquiry_correlation" \
    '(.Messages // []) | map(select(.MessageAttributes.correlationId.StringValue == $c)) | .[0].ReceiptHandle // empty' \
    <<<"$received")"
  if [ -n "$inquiry_reply" ]; then
    aws sqs delete-message --region "$AWS_REGION" --queue-url "$inquiry_reply_url" --receipt-handle "$receipt"
    break
  fi
done

if [ -z "$inquiry_reply" ]; then
  printf 'no inquiry reply correlated to %s arrived; check the inquiry consumer and its DLQ\n' "$inquiry_correlation" >&2
  exit 1
fi
printf 'inquiry reply length=%s\n' "${#inquiry_reply}"
```

### Flow 10 - Sign-out and grant revocation

Run this **last**. It revokes the grant every flow above authenticates with, so a sign-out placed
earlier leaves the rest of the pass unauthenticated.

```bash
# WHAT: revokes the held grant, then proves the revocation by showing the same refresh token is
#       refused.
# WHY : Assumptions: the second call is the one that proves the first. A sign-out that only dropped
#       client state would leave this renewal succeeding for the token's full thirty-day life, so
#       the expected result is a REFUSAL and a 200 on the refresh is the finding.
# WHY : Refactoring Rationale: this block sat inside Flow 1, immediately after the token was
#       obtained. Physically placing it there revoked the session before Flows 2 to 9 ran, so every
#       authenticated flow below it received 401 and the pass could never verify the candidate
#       business flows the acceptance criterion names. Moving it to the end is the fix; the prose
#       note in Flow 1 telling the operator to "run this last" was not, because the commands are
#       written to be executed in file order.
hide_trace
signout_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/auth/signout" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg r "$REFRESH_TOKEN" '{refreshToken:$r}')")"

refresh_status="$(curl -sS -o /dev/null -w '%{http_code}' \
  -X POST "$CARDDEMO_API/api/v1/auth/refresh" \
  -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg r "$REFRESH_TOKEN" '{refreshToken:$r}')")"
show_trace

expect_status 204 "$signout_status" signout
expect_status 401 "$refresh_status" refresh-after-signout
unset ACCESS_TOKEN REFRESH_TOKEN SEED_PASSWORD
```

### What each flow proves

| Flow | Verification |
|:---|:---|
| Sign-on | Authenticate through `POST /api/v1/auth/signon`; complete `POST /api/v1/auth/challenge` when the temporary credential requires a change. |
| Sign-out | Revoke the held grant through `POST /api/v1/auth/signout`, then verify the same refresh token is refused by `POST /api/v1/auth/refresh`. A sign-out that only cleared the browser would leave that renewal succeeding for the token's full thirty-day life, so the second call is the one that proves the first. |
| Account view/update | Read one account, revalidate an update against its ETag, and verify a stale version returns `409`. |
| Card list/update | List cards narrowed by account, then resolve one card through `POST /api/v1/cards/lookup` and address detail/update by the opaque `cardKey` that lookup and every list row return. No primary account number appears in a request line or a query string on any card route, so none can reach an access log, a referrer header or a browser history; the administrative full-number read sits on its own `/api/v1/admin/cards/{cardKey}` path and returns the number in the response body only. |
| Transaction add/list | Preview a fixed-point amount and verify the list returns the same decimal string. |
| Bill pay | Preview one payment; confirm only if a posted row is wanted, and verify the account and ledger effects commit together. |
| Posting batch | Start the Step Functions execution for an explicit business date and verify posted, rejected, and return-code outcomes. |
| Pending authorization | Publish a 170-character request and verify ordered request/reply and fraud-mark behavior. |
| Account inquiry | Publish a 1000-character request and verify the correlated standard-queue reply. |
| Transaction-type reference | Read reference data; verify referenced types cannot be deleted (`409`). |

Use [batch-operations.md](batch-operations.md) for batch execution and parity-oracle detail.

### Reading the parity oracle's result

The COBOL suite in `tests/**` is the behavioural oracle this migration is verified against, and it
remains reference-only -- neither its sources nor its pinned dependencies are modified. It runs from
the repository root through `scripts/run_tests.sh`, which sources `scripts/test_env.sh` itself and
executes six stages: `build`, `unit`, `integration`, `e2e`, an optional coverage combine, and
`audit`. It aggregates a worst-case condition code across them:

| Code | Meaning |
|:---|:---|
| 0 | Pass. |
| 2 | Usage error. Deliberately never aggregated, so a mistyped invocation cannot masquerade as a warn. |
| 4 | Warn or soft reject. |
| 8 | Fail. |
| 16 | Fatal or abend. |

**Read the aggregate this way: 0 and 4 both mean proceed, 8 and 16 mean stop and investigate.**
Assumptions: 4 is expected rather than exceptional, because a program the open-source compiler
cannot build against the immutable baseline is reported as a warn and the remaining stages still
run -- `scripts/run_tests.sh` L209-L211 sets that contract, and `--require-cobol` is the switch that
escalates those skips into hard failures for a caller that wants the stricter reading. Trade-offs:
treating 4 as a stop instead would block every deployment on a condition no permitted change can
clear, since `app/**` is reference-only. Where a migrated service is deliberately unlike the program
it came from,
[cobol-to-service-traceability.md](../architecture/cobol-to-service-traceability.md) is the register
that says so; this section decides only how to read a return code.

`tests/README.md` L3-L6 states that where a script and that README disagree, **the script is
authoritative**. The same precedence applies to this section: it describes the runner's behaviour,
and `scripts/run_tests.sh` decides it.

---

## Environment parameterisation

| Concern | dev | prod |
|:---|:---|:---|
| Aurora capacity | May scale to zero with auto-pause configured. | Minimum remains above zero. |
| ECS sizing | Smaller task size and desired count. | Production task size and redundant desired count. |
| Log retention | Shorter. | Longer. |
| CloudFront price class | Restricted. | Full configured class. |
| Protection | Destructive iteration permitted. | Deletion protection and final snapshot required. |

Trade-offs: keeping topology identical means a dev validation exercises the same network and
service graph as prod. It does not prove prod capacity behavior, and scale-to-zero resume behavior
is a dev-only characteristic.

---

## Idempotency

Re-running `plan` and `apply` against an unchanged configuration converges: it creates no duplicate
resource, requires no edit to any file, and reports no change. That property is uniform across all
sixteen modules because it comes from the execution model rather than from anything written per
resource.

Assumptions: an operator re-running this runbook needs no per-step precaution, because convergence is
a property of the execution model and not of a device written into each resource. The mainframe path
is different in kind, and the table records what re-running each of its jobs requires -- it is
relevant to an operator who runs both paths, since the two answer "may I resubmit this?" in
different places:

| Device | Where | What a re-run requires |
|:---|:---|:---|
| `IF LASTCC=12 THEN SET MAXCC=0` | `app/jcl/DEFGDGB.jcl` L29, L35, L41, L47, L53, L59 | Nothing; the already-exists condition code is reset once per DEFINE |
| `IEFBR14` with `DISP=(MOD,DELETE)` | `app/jcl/PRTCATBL.jcl` L21-L25 | Nothing; a delete-if-exists preamble step runs ahead of the real work |
| An "uncomment the DELETE" instruction | `app/jcl/CBADMCDJ.jcl` L38 and L42 | An edit to the deck before resubmission, so the submitted artifact differs from the reviewed one |
| No guard | `app/jcl/DALYREJS.jcl` L21-L28 | Manual removal of the generation group first; resubmitting with it present returns a non-zero condition code and the job fails |

Trade-offs: the guard is per-DEFINE rather than per-job, so whether a deck is safe to resubmit is
answered only by reading it. Terraform answers the same question once, for every root, which is why
this runbook carries no per-step re-run precaution. No file under `app/**` is edited by this
migration; the citations above are read from the reference tree as it stands.

---

## No secrets by construction

Database credentials, seed-user temporary passwords, and rotated Cognito app-client secrets are
generated or returned during apply and written to Secrets Manager. Tracked `terraform.tfvars` files
contain non-secret sizing and retention only. `ui/.env.example` documents names without values, and
state and local environment files are excluded from commits.

The operator path holds the same property. No step in this runbook asks for a password: the cluster
master credential is generated and owned by RDS, and Step 4d resolves it from its secret into a
mode-0600 passfile that the same sub-step shreds — so it is never in argv, never in an exported
`PGPASSWORD` readable from `/proc`, never in a shell variable, and never in the shell history. Every
other database interaction here goes through the Data API, which takes the secret's ARN and resolves
the credential server-side, so the value does not reach the operator's machine at all.

Refactoring Rationale: the baseline published seed credentials with its deployment material.
Generate-at-apply plus managed retrieval makes the no-committed-secret constraint structural rather
than dependent on a reviewer noticing a credential-shaped string.

---

## Concurrency and the state lock

Allow one apply per environment. The DynamoDB state lock is the cloud analogue of exclusive
read-write access to the shared CICS definition store. Queue another deployment instead of
canceling an active apply: interruption can leave both partial resources and an abandoned lock.
See [teardown.md](teardown.md) for the reviewed force-unlock procedure.

---

## Failure handling

| Failure | Operator response |
|:---|:---|
| Environment `init` reports a missing backend | Stop and complete Step 1; do not switch to local state. |
| Environment `init` fails on an unset backend value, or reports `bucket` as required | The four `-backend-config` values were not exported. `backend.tf` commits only `key` and `encrypt`; re-run Step 1a in this shell, then repeat the `init`. Do not answer the omission by editing `backend.tf`. |
| `psql` in Step 4d cannot reach the writer endpoint | The private path is an operator prerequisite (Step 4a). Establish it and resume at 4d; 4a–4c need no such path and do not have to be repeated. |
| Plan shows unexplained destroys or replacements | Stop, inspect state and configuration, and produce a new plan after correction. |
| Apply is interrupted | Inspect the environment and lock; follow `teardown.md` before force-unlocking. |
| Service fails health checks | Inspect stopped reason, logs, image digest, and runtime parameter/secret references. |
| Flyway checksum mismatch | Do not edit an applied migration; add a new versioned migration. |
| Cognito rotation sees an unexpected secret count | Stop; reconcile active secret identifiers and the current managed secret before retrying. |
| Prod destroy is blocked | Treat the protection as intentional and follow the explicit teardown procedure. |

---

## Out of scope

An operator will not find multi-region disaster recovery, blue-green or canary deployment, Kafka,
Kinesis, Redis, ElastiCache, read replicas, the Db2 rewards roadmap item, IMS DC, SFTP integration,
or exposed distributed transactions in this package. They are outside this migration's scope.

---

## Mainframe path remains available

`app/**`, `samples/**`, `scripts/**`, and `tests/**` remain reference-only and operable. This AWS
deployment adds a path; it does not remove or rewrite the existing one. The untouched path is also
why rollback does not require an un-migration of the baseline.

---

## Related documents

- [Teardown](teardown.md)
- [Data migration](data-migration.md)
- [Batch operations](batch-operations.md)
- [Security and identity](../architecture/security-and-identity.md)
- [Code documentation standard](../CODE_DOCUMENTATION_STANDARD.md)
- [Migration guide](../../MIGRATION_README.md)
- [Repository overview](../../README.md)
