# =============================================================================
# infra/envs/prod/terraform.tfvars
# -----------------------------------------------------------------------------
# WHAT: the non-secret parameter values for the PRODUCTION root in this
#       directory. Terraform loads this file automatically for any command run
#       with `-chdir=infra/envs/prod`, and .github/workflows/infra-ci.yml and
#       .github/workflows/deploy.yml additionally pass it as
#       `-var-file=terraform.tfvars`. Every name below is declared in
#       infra/envs/prod/variables.tf, which holds each input's authoritative
#       type, description and validation; what lives HERE is the reason
#       production chose the value it did. The file publishes nothing of its own
#       -- outputs.tf reports the resolved configuration back after an apply.
#
# WHY : Assumptions: this file is TRACKED IN GIT and holds no secret. What makes
#       that safe is structural rather than editorial: variables.tf declares no
#       credential input at all, so there is nothing here for a credential to be
#       assigned to. The database credential is generated and rotated by RDS
#       under `manage_master_user_password`, the seed-user passwords are minted
#       straight into Secrets Manager by modules/cognito's bootstrap, and the
#       masking key is an operator-owned secret this root only NAMES. The
#       tfvars contract in infra/README.md §9 admits sizing and retention values
#       only -- never an endpoint, an ARN, an account identifier or an address.
#
# WHY : Assumptions: THIRTEEN declared inputs are deliberately ABSENT from this
#       file, because each names a deployment identity -- a certificate, a DNS
#       name, an ARN, an image reference or a notification address. They are
#       `alb_certificate_arn` (a REGIONAL certificate),
#       `internal_service_domain_name` (the bare name that certificate must
#       cover and that the API Gateway private integration verifies),
#       `cloudfront_acm_certificate_arn` (issued in us-east-1, a different
#       certificate from the ALB's), `cloudfront_aliases`,
#       `cloudfront_api_connect_src_origins` (which has to name the same API
#       origin the SPA bundle was built against), `image_tag`, `image_digests`,
#       `github_repository`, `github_oidc_provider_arn`,
#       `permissions_boundary_arn`, `mask_hmac_secret_arn`,
#       `mask_hmac_secret_kms_key_arn` and `alarm_email_endpoints`. Every one
#       except `image_digests` is declared with no default, so an apply cannot
#       proceed without it rather than proceeding half-configured. That set is
#       identical in dev, which is what keeps the two roots' surfaces symmetrical.
#
# WHY : ⚠️ Refactoring Rationale: this header named EIGHT of those values and
#       credited "the deployment workflow" with supplying each as
#       `TF_VAR_<name>`. Both halves misled, in exactly the way
#       infra/envs/dev/terraform.tfvars already records. It omitted
#       `alarm_email_endpoints`, `permissions_boundary_arn`,
#       `mask_hmac_secret_arn` and `mask_hmac_secret_kms_key_arn` -- each
#       required with no default -- so an operator trusting it met at plan time
#       the very "No value for required variable" failure the header claimed to
#       have documented away, on the one root where a half-configured apply
#       touches live infrastructure. And deploy.yml exports no `TF_VAR_` at all:
#       it writes a generated `deployment.auto.tfvars.json` beside this file,
#       merges the pushed image digests into it, and deletes it at the end of the
#       run -- which is why .gitignore ignores `*.auto.tfvars.json` and not this
#       file. `TF_VAR_` is the OTHER mechanism, the one an operator uses per
#       docs/runbooks/deploy.md and infra-ci.yml's credentialed review plan uses
#       for its own plan. `image_digests` was unnamed too, and it is the one
#       absent input that could not fail a plan, because it defaults to empty.
#
# WHY : Assumptions: `batch_schedule_maximum_event_age_seconds` is the one
#       further absent input, and it is absent because its declared default of
#       one hour is already correct for both roots. It is not inert: the
#       `terraform_data.batch_window_disjoint` gate in main.tf adds it to the
#       cron hour to derive the window in which a retried delivery could still
#       START the nightly chain, and checks that window against the two Aurora
#       windows set below. Anyone setting it here must re-read those.
# =============================================================================

# WHY : Assumptions: these three are IDENTICAL in both roots by construction rather
#       than by coincidence, and none of them is a sizing or retention axis.
#       `aws_region` names a public AWS location and confers no access, so committing
#       it discloses nothing; it matches the region infra/bootstrap defaults to, which
#       keeps this root's state bucket and the resources it describes in one place
#       instead of the legal-but-confusing split. `name_prefix` gives the whole stack
#       one greppable identity. `environment` is the one NON-SIZING input that
#       legitimately differs between the roots, because it names the environment
#       rather than describing its size: its own validation in variables.tf pins it to
#       the single name this directory holds state for, every module composes it into
#       resource names, and it is the `Environment` tag value that provider
#       `default_tags` then applies stack-wide -- so a wrong value here would not fail,
#       it would silently label production as something else. Trade-offs: all three
#       restate a declared default, so the file is three lines longer than it strictly
#       needs to be. Accepted because an operator asking which region and which names
#       PRODUCTION uses reads this file, and a value that is only a default is answered
#       by silence.
aws_region  = "us-east-1"
name_prefix = "carddemo"
environment = "prod"

# WHY : Assumptions: the SAME address space dev uses, deliberately. Specification
#       section 0.4.1.6 closes the set of axes on which the two roots may differ --
#       Aurora capacity floor, ceiling and auto-pause interval, ECS task count and
#       CPU/memory, log retention days, CloudFront price class, and the
#       deletion-protection and final-snapshot flags -- and requires that they differ
#       "only in sizing and retention and never in topology". An address space is
#       topology and is not on that list, so diverging here would cost dev its standing
#       as a rehearsal for this root on precisely the axis a rehearsal exists to cover.
#       Trade-offs: two VPCs sharing one block are legal and cannot conflict, but they
#       can never be peered to each other. Accepted because this package provisions no
#       peering, transit gateway or VPN and multi-region topology is out of scope, so
#       the connectivity given up is connectivity neither environment has.
#       Alternatives Considered: giving production its own block, which is the usual
#       practice for exactly that peering reason. Rejected because widening the closed
#       axis list is a specification change rather than a configuration choice.
vpc_cidr = "10.1.0.0/16"

# WHY : Assumptions: every value in this map is descriptive and non-secret, which is
#       load-bearing rather than incidental -- versions.tf applies the map through
#       provider `default_tags`, so it reaches every taggable resource in all sixteen
#       modules, and a tag is readable by any principal that can describe the resource
#       and is exported into cost-allocation reports. `Environment` is the key that
#       makes such a report separate this environment's spend from development's, which
#       is the practical reason the map is set here at all rather than left to its
#       default; `ManagedBy` tells an operator who finds an untracked-looking resource
#       that Terraform owns it and a console edit will be reverted on the next apply.
tags = {
  Project     = "carddemo"
  Environment = "prod"
  ManagedBy   = "terraform"
}

# WHY : Refactoring Rationale: this pin was "16.6", whose Aurora STANDARD SUPPORT
#       ended on 2026-05-31 -- a cluster created from it would either be force
#       upgraded on Aurora's schedule or attract Extended Support charges, and in
#       either case the version this file claims to deploy would stop being the
#       version running. Trade-offs: 16.8 is chosen over the newest available
#       16.x because AWS designates it a LONG-TERM SUPPORT release (published
#       2025-04-07), which carries a minimum three-year availability horizon
#       instead of the twelve months a standard minor gets, so this pin needs
#       reviewing once every few years rather than every year. What is given up
#       is new engine features added after 16.8 -- nothing this workload uses,
#       since the schemas need only ordinary relational features -- while
#       critical security and stability patches still arrive, because Aurora
#       patches LTS clusters to that release's latest patch version annually.
#       Alternatives Considered: 17.x LTS would also be supported, but a major
#       version change would also move aurora_parameter_group_family below and
#       is a larger change than closing a support-calendar gap. Assumptions: 16.8
#       is comfortably above the 16.3 minor that Serverless v2 scale-to-zero
#       requires, which dev depends on through aurora_min_capacity = 0.
#
#       The marker line below is MACHINE-READ by the "Verify the Aurora engine
#       pin against its support review horizon" gate in infra-ci.yml, which fails
#       the build once the horizon is reached or passed. WHY a declared horizon
#       rather than a live lookup: the static validation job holds no AWS
#       credentials and must run offline, so a gate that queried the support
#       calendar would be skipped exactly when it mattered; a declared date
#       cannot silently age out because its expiry is what breaks the build.
# aurora-engine-support-review: 16.8 by 2028-04-07
aurora_engine_version         = "16.8"
aurora_parameter_group_family = "aurora-postgresql16"
# WHY : Capacity. Trade-offs: a MINIMUM of 2 rather than the zero dev uses. Scaling to
#       zero would save money between the nightly window and the working day, and it is
#       declined here because the first request after a pause waits roughly fifteen
#       seconds for a resume -- a latency an interactive operator would read as an
#       outage, and one the batch chain would absorb into its own window. Holding two
#       capacity units keeps the writer warm at the smallest size that is always
#       available. Alternatives Considered: a maximum of 4 as in dev. Rejected because the
#       posting and interest jobs are set-based and read the whole ledger, so the ceiling
#       has to admit a burst the nightly chain genuinely produces; 32 bounds a runaway
#       rather than sizing the workload, which scaling does. Both figures are whole
#       multiples of the half-unit step the module's validation requires, and both sit
#       inside the 0-256 range it enforces -- the range rules live there, not here.
#
#       Refactoring Rationale: the auto-pause line below carried the claim that "the
#       aurora module passes nothing when the minimum is non-zero". That is not what the
#       module does. modules/aurora-postgresql/main.tf L521-525 passes
#       seconds_until_auto_pause into serverlessv2_scaling_configuration
#       UNCONDITIONALLY; it is AWS that ignores an auto-pause delay while the capacity
#       floor is above zero. The distinction is worth the correction because a reader who
#       believed the module filtered the value would also believe lowering the minimum to
#       zero were a one-line change, when in fact this value goes live the moment they do
#       it. Assumptions: 300 is therefore a floor-compliant standing value rather than an
#       active setting -- the lowest the module's 300-86400 second validation admits,
#       chosen so that if the floor is ever dropped to zero the cluster releases capacity
#       at the earliest permitted point instead of holding it on a delay nobody picked.
aurora_min_capacity             = 2
aurora_max_capacity             = 32
aurora_seconds_until_auto_pause = 300
# WHY : Retention. Trade-offs: 35 days, the maximum Aurora's automated backups allow.
#       Production ledger rows are not reproducible from any source -- the baseline
#       extracts seeded the migration once and every posting since is original -- so the
#       recovery objective is a true point-in-time restore and the window is bought at
#       its longest. Dev holds 1 for the opposite reason.
aurora_backup_retention_period = 35
# WHY : Windows. Assumptions: both windows sit AFTER the nightly batch chain, which
#       batch_schedule_expression below starts at 02:00 UTC. A maintenance restart of a
#       single-writer cluster is a full outage rather than a rolling one, so overlapping
#       it with the chain would fail a run mid-posting; the backup window is placed
#       first so a snapshot captures the night's posted state before maintenance may
#       restart anything. Trade-offs: identical to dev's windows on purpose, so that a
#       maintenance night rehearsed in development happens at the same point relative to
#       the chain here.
aurora_preferred_backup_window      = "07:00-08:00"
aurora_preferred_maintenance_window = "sun:09:00-sun:10:00"

# WHY : Assumptions: there is deliberately NO read-replica input to set here, and the
#       absence is documented rather than left silent, since a reader arriving at this
#       root is the one most likely to go looking for such an input.
#       modules/aurora-postgresql declares a single aws_rds_cluster_instance, so this
#       cluster has one writer and no reader; specification section 0.2.2 places read
#       replicas out of scope and routes reporting reads to the writer through read-only
#       cross-schema views instead. Alternatives Considered: adding a replica for the
#       reporting load. Rejected here because it would introduce replica-lag semantics
#       into statement and report output that the golden-master parity oracle compares
#       byte-for-byte, so the reads have to see the writer's own committed state. It is
#       also why the maintenance window above is a full outage rather than a rolling one.

# WHY : Task sizing. Trade-offs: double dev's CPU and memory, and TWO tasks per service
#       rather than one. The second task is not for throughput; it is what makes a rolling
#       ECS deployment a rolling one -- with a single task the deployment is an outage, and
#       with two the load balancer always has a healthy target. Assumptions: the services
#       are stateless with no session store (AAP section 0.7.1), so a second task needs no
#       sticky routing and adds no coordination.
ecs_task_cpu      = 1024
ecs_task_memory   = 2048
ecs_desired_count = 2

# WHY : Log retention and edge reach. Trade-offs: 365 days of logs, against 7 in dev.
#       These logs are the audit trail for a financial workload and are the only record of
#       who read which account, so retention is set to a year rather than to what
#       debugging needs. PriceClass_All serves cardholders from every edge location,
#       accepting the higher per-request cost that dev declines with PriceClass_100.
log_retention_days     = 365
cloudfront_price_class = "PriceClass_All"
# WHY : Schedule. Assumptions: 02:00 UTC, and the SAME expression in both roots. It is
#       placed before the backup and maintenance windows above so the chain completes
#       against a cluster nothing else is restarting, and it is identical across
#       environments so a rehearsal in development exercises the same ordering against
#       those windows that production will. This is the EventBridge Scheduler expression
#       that replaces the CA-7 and Control-M definitions under app/scheduler, whose
#       intent -- one nightly chain rather than per-job triggers -- is what the single
#       expression carries.
batch_schedule_expression = "cron(0 2 * * ? *)"

# WHY : Destructive-operation flags. Trade-offs: the FIRST TWO are inverted from dev, and
#       each is a deliberate obstacle rather than a default. deletion_protection true
#       makes `terraform destroy` FAIL on the cluster rather than succeed, and
#       skip_final_snapshot false forces a final snapshot before any deletion Aurora does
#       permit. The cost is that tearing production down is not one command -- which is
#       the intent: docs/runbooks/teardown.md documents the two-step procedure, clearing
#       protection in its own reviewed apply and only then planning the destroy. A destroy
#       that fails against this root is the flags working, not a fault to route around.
#       An accidental destroy of production data is unrecoverable, so the flags are set to
#       make the accident impossible rather than merely unlikely.
#
#       Refactoring Rationale: this block read "all three inverted from dev". Only two
#       are. secret_recovery_window_in_days is 30 in BOTH roots, so a reader checking the
#       claim against infra/envs/dev/terraform.tfvars would find it false with no way to
#       tell which of the two files was wrong -- and the natural repair, "invert it", would
#       have changed a value that is correct. Its 30 days is not an inversion but the same
#       deliberate choice each root makes independently: a deleted secret stays restorable
#       for the window, against the alternative of force-deleting it without recovery.
#       Assumptions: the recovery window is what makes a secret NAME unavailable for reuse
#       until it elapses, which is why dev records the same value as the cost of a
#       repeatable teardown; production wants exactly that obstruction, so the two roots
#       agree here for opposite reasons rather than by inheritance.
#
#       Assumptions: the final snapshot the second flag forces is NOT a roll-back asset on
#       its own. It is encrypted under this environment's Aurora key, which the same
#       destroy schedules for deletion, so it is readable only under the key-ordering step
#       in that runbook. Stating it plainly matters because "a final snapshot was taken"
#       otherwise reads as a recovery guarantee it does not provide unaided.
#
#       Assumptions: these two flags are also WHY production deploys behind a GitHub
#       environment approval while dev applies automatically (.github/workflows/deploy.yml
#       L25-26 and L45) -- so changing them has a consequence beyond this file. The
#       baseline analogue is app/jcl/CBADMCDJ.jcl L2, whose NOTIFY=&SYSUID told an operator
#       after a destructive admin job had already run; the approval gate notifies AND
#       blocks first, which is the whole difference this file's values are protecting.
deletion_protection            = true
skip_final_snapshot            = false
secret_recovery_window_in_days = 30
# WHY : alarm_email_endpoints is deliberately ABSENT from this file. It is a required
#       input with no default, supplied out of band (TF_VAR_alarm_email_endpoints or
#       the deploy workflow's own variable), because an on-call address is personal
#       data this repository does not carry. See infra/envs/prod/variables.tf for the
#       full reasoning and docs/runbooks/deploy.md for the operator step.
