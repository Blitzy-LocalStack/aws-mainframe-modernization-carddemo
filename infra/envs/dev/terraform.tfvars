# =============================================================================
# infra/envs/dev/terraform.tfvars
# -----------------------------------------------------------------------------
# WHAT: the parameter values for the `dev` environment root in this directory.
#       Terraform loads this file automatically for any command run with
#       `-chdir=infra/envs/dev`, and .github/workflows/infra-ci.yml and
#       .github/workflows/deploy.yml additionally pass it as
#       `-var-file=terraform.tfvars`. Every name below is declared in
#       infra/envs/dev/variables.tf, which holds each input's authoritative
#       type, description and validation; what lives HERE is the reason this
#       environment chose the value it did. The file publishes nothing of its
#       own -- outputs.tf is what reports the resolved configuration back, and it
#       echoes the region set below in its `spa_publication` output so the choice
#       does not stay invisible.
#
# WHY : Assumptions: a wrong value here fails at PLAN time with an error naming
#       an INPUT rather than a resource, and it does so from one of three places,
#       never from this file -- a tfvars file has no construct that can check
#       anything. Each `variable` in variables.tf validates its own domain; the
#       modules own the rules that relate two values to each other, which a
#       single-variable check cannot see, such as the Aurora capacity invariant
#       and the Fargate CPU-and-memory pairing; and main.tf's `terraform_data`
#       gates assert the cross-cutting ones, such as the nightly schedule not
#       overlapping the two database windows. The risk this file therefore
#       actually carries is a value that is individually legal and wrong in
#       combination, which is what the per-assignment notes below exist to make
#       visible before someone changes one of a coupled pair alone.
#
# WHY : Assumptions: this file is TRACKED IN GIT and holds no secret, and both
#       halves of that are deliberate. The usual Terraform convention is to
#       ignore `*.tfvars` because they usually carry credentials; applying it
#       here would silently drop a required deliverable, so .gitignore names
#       only `*.auto.tfvars` and `*.auto.tfvars.json` and infra/README.md §3.2
#       records the exception in as many words. What makes committing this safe
#       is structural rather than editorial: variables.tf declares NO credential
#       input at all, so there is nothing here for a credential to be assigned
#       to. The `random` provider generates the platform keys and each service
#       database credential at apply time as `ephemeral` values that never enter
#       state, modules/cognito's bootstrap mints each seed-user password
#       straight into Secrets Manager, and the Aurora master password is
#       generated and rotated by the database service under
#       `manage_master_user_password`. A maintainer tempted to park a
#       "temporary" password here has no input to park it in, which is a
#       stronger guarantee than a convention against trying.
#
# WHY : Assumptions: what this file may vary is a CLOSED set of five axes, the
#       ones specification section 0.4.1.6 permits `dev` and `prod` to differ on
#       -- Aurora capacity floor, ceiling and auto-pause interval; ECS task
#       count and CPU/memory; log retention days; CloudFront price class; and
#       the deletion-protection and final-snapshot flags. Everything else below
#       -- the naming, region and tagging values, the address space, the engine
#       pin and its parameter-group family, backup retention, the two database
#       windows, the nightly schedule and the secret recovery window -- both roots
#       set IDENTICALLY, so a diff of the two files is exactly that axis list plus
#       each root's own environment name, which also appears as a tag value.
#       Trade-offs: holding the rest equal costs real money in an
#       environment nobody depends on, and it is paid because a difference in
#       SHAPE rather than size would leave this environment unable to rehearse a
#       production change, which is the only thing it exists for. The boundary is
#       enforced from both sides, so there is no knob here to reach for even if
#       one were wanted: infra/modules/network deliberately declares no
#       NAT-collapse toggle, no per-endpoint enable flag and no `create_*`
#       switch, it pins the availability-zone count it DOES expose to the single
#       supported value of three, and this root declines to declare the zone
#       count, the subnet arithmetic or the endpoint set as inputs at all.
#
# WHY : Assumptions: THIRTEEN declared inputs are deliberately ABSENT here,
#       because each names a deployment identity -- a certificate, a DNS name,
#       an ARN, an image reference or a notification address -- and the tfvars
#       contract in infra/README.md §9 admits sizing and retention values only,
#       never an endpoint, an ARN or an account identifier. They are
#       `alb_certificate_arn` (a REGIONAL certificate),
#       `internal_service_domain_name` (the bare name that certificate must
#       cover and the API Gateway private integration verifies),
#       `cloudfront_acm_certificate_arn` (issued in us-east-1, a different
#       certificate from the ALB's), `cloudfront_aliases`,
#       `cloudfront_api_connect_src_origins` (which has to name the same API
#       origin the SPA bundle was built against), `image_tag`, `image_digests`,
#       `github_repository`, `github_oidc_provider_arn`,
#       `permissions_boundary_arn`, `mask_hmac_secret_arn`,
#       `mask_hmac_secret_kms_key_arn` and `alarm_email_endpoints`. Every one
#       except `image_digests` is declared with no default, so an apply cannot
#       proceed without it rather than proceeding half-configured;
#       docs/runbooks/deploy.md gives the operator `TF_VAR_` export for each.
#
# WHY : ⚠️ Refactoring Rationale: this header used to name eight of those values
#       and credit "the deployment workflow" with supplying them "as
#       TF_VAR_<name>". Both halves misled. deploy.yml exports no `TF_VAR_` at
#       all -- it writes a generated `deployment.auto.tfvars.json` beside this
#       file and deletes it at the end of the run, which is why .gitignore
#       ignores that pattern and not this one; `TF_VAR_` is how an operator and
#       infra-ci.yml's review plan supply them, as docs/runbooks/deploy.md sets
#       out. And it said nothing at all about `permissions_boundary_arn`,
#       `mask_hmac_secret_arn` or `mask_hmac_secret_kms_key_arn`, each of which
#       is required with no default -- so a reader trusting this header met at
#       plan time exactly the failure the header claimed to have documented
#       away, which is the same fault its previous text recorded for the ALB
#       pair and had not finished fixing. `image_digests` was unnamed too and
#       could not fail a plan, because it defaults to empty.
#
# WHY : Assumptions: `batch_schedule_maximum_event_age_seconds` is the one
#       further absent input, and it is absent because its declared default of
#       one hour is already correct for both roots. It is not inert: the
#       `terraform_data.batch_window_disjoint` gate in main.tf adds it to the
#       cron hour to derive the window in which a retried delivery could still
#       START the nightly chain, and checks that window against the two Aurora
#       windows set below. Anyone setting it here must re-read those.
# =============================================================================

# WHY : Assumptions: these three are IDENTICAL in both roots by construction
#       rather than by coincidence, and none of them is on the closed axis list
#       above. `aws_region` names a public AWS location and confers no access,
#       so committing it discloses nothing; it matches the region infra/bootstrap
#       defaults to, which keeps this root's state bucket and its resources in
#       one place instead of the legal-but-confusing split. `name_prefix` gives
#       the whole stack one greppable identity, and `environment` is pinned by
#       its own validation to the single name this directory owns the state for
#       -- assigning it is what makes the pinning visible to someone reading
#       parameters rather than declarations. Trade-offs: all three restate a
#       declared default, so the file is three lines longer than it strictly
#       needs to be. Accepted because an operator answering "what region and
#       what names does dev use?" reads this file, and a value that is only a
#       default is answered by silence.
aws_region  = "us-east-1"
name_prefix = "carddemo"
environment = "dev"

# WHY : ⚠️ Refactoring Rationale: this read "10.0.0.0/16" against production's
#       "10.1.0.0/16". The two are now the SAME block, deliberately. Specification
#       section 0.4.1.6 fixes a closed set of axes on which the environments may
#       differ -- Aurora capacity floor, ceiling and auto-pause interval, ECS task
#       count and CPU/memory, log retention days, CloudFront price class, and the
#       deletion-protection and final-snapshot flags -- and states that the roots
#       differ "only in sizing and retention and never in topology". An address
#       space is topology, and it is not on that list, so a divergence here was a
#       topology difference between two environments required to have none.
#       Trade-offs: identical CIDRs in two VPCs are legal and cannot conflict, but
#       they do preclude ever peering dev to prod directly. Accepted: the
#       specification places multi-region and disaster-recovery topology out of
#       scope and this package provisions no peering, transit gateway or VPN, so
#       the connectivity being given up is not connectivity either environment has.
#       Alternatives Considered: arguing the CIDR is a legitimate further axis,
#       which is the usual practice for exactly the peering reason above. Rejected
#       because the specification is frozen and its axis list is closed; widening
#       it is a specification change, not a configuration choice.
vpc_cidr = "10.1.0.0/16"

# WHY : Assumptions: every value in this map is descriptive and non-secret,
#       which is load-bearing rather than incidental -- versions.tf applies the
#       map through provider `default_tags`, so it reaches every taggable
#       resource in all sixteen modules, and a tag is readable by any principal
#       that can describe the resource and is exported into cost-allocation
#       reports. `Environment` is the key that makes such a report separate this
#       environment's spend from production's, which is the practical reason the
#       map is set here at all rather than left to its default.
tags = {
  Project     = "carddemo"
  Environment = "dev"
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
aurora_engine_version = "16.8"
# WHY : Assumptions: the family TRACKS the engine major above and is not an
#       independent choice -- a cluster parameter group whose family disagrees
#       with the engine is rejected by the service, and the rejection names the
#       parameter group rather than either value here. It is spelled out rather
#       than derived from the version string because a family is an engine name
#       plus a major, not a substring of a minor version, and computing one from
#       the other would put a string manipulation between an operator and a
#       value the service matches exactly.
aurora_parameter_group_family = "aurora-postgresql16"

# WHY : Trade-offs: a capacity FLOOR of zero, which is the largest single cost
#       difference between this environment and production. A cluster nobody is
#       using pauses and stops accruing Aurora Capacity Unit charges altogether,
#       so an environment nobody has touched since the last change bills for
#       storage and backups but no compute. What is paid is resume latency on the
#       first connection after a pause -- on the order of fifteen seconds -- and
#       it is worth being blunt about how that presents: a developer's first
#       request appears to hang, and nothing in the response says the cluster
#       was asleep. Accepted because this environment is disposable and its
#       heaviest user is a batch chain that absorbs the wait invisibly;
#       infra/envs/prod holds its floor above zero precisely so that no
#       interactive user ever meets it.
#       Assumptions: a zero floor arms two further rules -- the auto-pause
#       interval below becomes MANDATORY, and the ceiling must reach at least
#       one unit. Neither is restated here -- a tfvars file has nowhere to put a
#       `validation` in any case. infra/modules/aurora-postgresql owns both,
#       together with the zero-to-256 range and the half-unit granularity, as
#       validation blocks on its own capacity inputs; a value
#       this file gets wrong therefore fails at plan time with a message naming
#       the module input, which is where the rule lives.
#       Assumptions: a zero floor also needs a recent engine minor and a recent
#       provider. The pin above clears the engine requirement, and the `~> 6.56`
#       constraint in versions.tf clears the provider one -- a zero minimum
#       arrived in 5.80.0 and the auto-pause argument it makes mandatory in
#       5.81.0, so this assignment is what that constraint's lower bound is
#       protecting.
aurora_min_capacity = 0
# WHY : Trade-offs: the CEILING is a spend bound, not a performance target --
#       the cluster only scales toward it under load, so a low ceiling costs
#       nothing while the environment is idle and caps what a runaway query or an
#       oversized fixture can spend before someone notices. What is given up is
#       that a genuinely heavy one-off job here runs slower than the same job
#       would in production, which is the right priority for an environment
#       nothing depends on.
#       Assumptions: this value has a SECOND consumer that its name does not
#       suggest. main.tf passes it to the observability module as well as to the
#       database module, and there it becomes the threshold of the alarm on
#       actual serverless capacity -- so the capacity-exhausted alarm fires at
#       whatever this ceiling is, rather than at any absolute figure. That is
#       intended: a development workload reaching a ceiling this low is worth
#       being told about, and the same alarm in production is scaled by
#       production's much higher ceiling. Anyone raising this value to quiet the
#       alarm has raised the spend bound instead of silencing anything.
#       Alternatives Considered: matching production's ceiling so that a load
#       observation made here transferred. Rejected -- this environment exists to
#       rehearse correctness, not to measure capacity, and the ceiling would then
#       stop bounding the one thing it is here to bound.
aurora_max_capacity = 4
# WHY : Trade-offs: the SHORT end of the interval the module accepts, which runs
#       from 300 to 86400 seconds. The two ends buy opposite things: a short
#       interval pauses an idle cluster sooner and so stops it billing sooner,
#       while a long one avoids paying the resume repeatedly across a session of
#       intermittent use. The short end is taken because the usage this
#       environment actually sees is a burst of work followed by long idleness,
#       which is the case it serves -- and because the resume it makes more
#       frequent is one only a developer pays, never a user.
#       Assumptions: this is set in BOTH roots although it has no effect
#       whenever the floor is above zero, so that the two roots' input surfaces
#       stay identical. An input present in only one of them would be a
#       structural difference between the environments rather than a sizing one,
#       which is the distinction the whole parameterization rests on.
aurora_seconds_until_auto_pause = 300
# WHY : ⚠️ Refactoring Rationale: this read 1 against production's 35 and is now 35.
#       Backup retention is not one of the axes specification section 0.4.1.6
#       enumerates -- its only retention axis is log retention days -- so the
#       divergence was off-axis. Aligning upward rather than reducing production is
#       the safe direction, and the cost is storage for 34 additional days of
#       backups on a cluster whose capacity floor is zero.
aurora_backup_retention_period = 35
# WHY : Assumptions: both windows sit clear of the window in which the nightly
#       chain can START, and that disjointness is ASSERTED rather than intended.
#       `terraform_data.batch_window_disjoint` in main.tf derives the start
#       window from the cron hour in batch_schedule_expression below plus
#       batch_schedule_maximum_event_age_seconds -- because a retried delivery
#       may begin the chain later than the cron hour -- and fails the plan if
#       that window overlaps either value here, or if any of the three wraps
#       midnight. Moving one of these without re-reading the other two is
#       therefore a plan-time error naming the constraint, rather than an
#       overlap that shows up later as an intermittent failure.
#       Trade-offs: the reason to keep them apart is that a backup and the chain
#       act on the SAME cluster, and this environment's capacity ceiling is
#       deliberately low, so an overlap would have the two competing for
#       capacity that is capped on purpose. Maintenance is worse than
#       competition: a maintenance restart of a single-writer cluster is a full
#       outage rather than a rolling one, so an overlap there would abort
#       in-flight loader tasks rather than merely slow them.
#       Assumptions: both values are IDENTICAL to production's. Neither is on the
#       closed axis list, and keeping them equal is what lets a maintenance night
#       rehearsed here happen at the same point relative to the chain as it will
#       there.
#       Alternatives Considered: leaving both unset, which the database module
#       permits by accepting null and letting the service assign a window.
#       Rejected -- an assigned window is chosen with no reference to the batch
#       schedule, so the separation this pair exists to guarantee would become a
#       matter of luck, and the plan-time gate above would have nothing to check.
aurora_preferred_backup_window      = "07:00-08:00"
aurora_preferred_maintenance_window = "sun:09:00-sun:10:00"

# WHY : Task sizing. Trade-offs: the smallest Fargate combination that runs a Spring
#       Boot service at all, and ONE task per service, so a development environment costs
#       one task rather than two. Assumptions: a single task means an ECS deployment is
#       briefly a full interruption for that service, which is acceptable here and is
#       precisely why prod runs two. Alternatives Considered: sizing dev to match prod so
#       that performance observations transferred. Rejected as the wrong purpose for this
#       environment -- correctness rehearsal, not capacity measurement -- and the cost
#       runs continuously while the observation would be occasional.
#       Assumptions: Fargate accepts only certain memory values for a given CPU
#       size, so the first two are NOT independently free -- for the sizes this
#       root admits, the memory must fall between twice and eight times the CPU
#       units and be a whole multiple of 1024 MiB. 512 with 1024 is the smallest
#       pair satisfying that above the 256-unit size. Changing one alone is
#       refused at plan time by the root's own cross-check; without that check it
#       would be refused during apply, against the task-definition registration,
#       whose error names the task definition rather than the value that caused
#       it. Treat them as one edit with two lines.
#       Assumptions: the task count is ALSO the autoscaling floor. main.tf passes
#       this same value as the service module's minimum capacity, which it must,
#       because that module's own floor defaults to two and cross-checks the two
#       against each other -- so a count of one paired with the module default
#       would be rejected at the module boundary. Nothing in either name suggests
#       the other exists, which is why it is recorded at the value that triggers
#       it.
ecs_task_cpu      = 512
ecs_task_memory   = 1024
ecs_desired_count = 1

# WHY : Log retention and edge reach. Trade-offs: SEVEN days of logs, against 365 in prod.
#       A development log is read while debugging the change that produced it and has no
#       audit value, so a longer retention pays storage for data nobody queries.
#       PriceClass_100 restricts CloudFront to its cheapest edge set, which is correct
#       here because the only viewers are developers, and wrong in prod where cardholder
#       traffic arrives from everywhere.
#       Assumptions: the retention days are drawn from an ENUMERATED set the log
#       service accepts, not from a free range, so an arbitrary integer is
#       REJECTED rather than rounded to the nearest accepted period -- six days
#       is not a slightly-off seven, it is an error. Seven is a member of that
#       set. The root's own validation restates the whole set so the rejection
#       happens at plan time naming this input rather than against whichever log
#       group is created first.
#       Assumptions: this ONE value is forwarded to a log-retention input on six
#       different modules and to one log group directly, and their accepted
#       domains are NOT identical -- so the domain that matters is their
#       intersection, not any single module's. Zero is excluded from it even
#       though the log service reads zero as `never expire`, because the content
#       distribution module writes the same number into an object-lifecycle
#       expiration rule and refuses anything at or below zero: a rule that
#       expires nothing looks exactly like a rule that is working. A value legal
#       for the log groups and refused by that one consumer is the kind of
#       mistake that surfaces only once its module is reached.
#       Assumptions: the price class changes WHICH EDGE LOCATIONS serve the
#       distribution and nothing else -- not its origin access control, not its
#       error routing, not its behaviours. That is what keeps it a sizing
#       difference between the roots rather than a topology one, and so eligible
#       for the closed axis list at all; the observable effect is higher latency
#       for a geographically distant viewer, and this environment has none.
log_retention_days     = 7
cloudfront_price_class = "PriceClass_100"
# WHY : Schedule. Assumptions: 02:00 UTC, and the SAME expression in both roots. It is
#       placed before the backup and maintenance windows above so the chain completes
#       against a cluster nothing else is restarting, and it is identical across
#       environments so a rehearsal in development exercises the same ordering against
#       those windows that production will. This is the EventBridge Scheduler expression
#       that replaces the CA-7 and Control-M definitions under app/scheduler, whose
#       intent -- one nightly chain rather than per-job triggers -- is what the single
#       expression carries.
batch_schedule_expression = "cron(0 2 * * ? *)"

# WHY : Trade-offs: the two destructive-operation flags, and the only place this
#       environment deliberately declines a recovery net. Both sides are stated
#       because only one of them is obvious.
#       What is GAINED is that `terraform destroy` completes in ONE step. No
#       preliminary apply is needed to clear a flag first, so tearing this
#       environment down is a single operation a reviewer can read end to end,
#       and docs/runbooks/teardown.md says in as many words to skip its
#       clear-the-protection step entirely for this root. Production inverts
#       both, and there the same destroy command FAILS while the flags are set --
#       and that failure is the flags working correctly, not an obstacle to route
#       around: it forces a separate, separately reviewed apply before anything
#       can be removed, which is why teardown there is two steps and here it is
#       one. Skipping the final snapshot additionally stops a destroy leaving a
#       snapshot behind: a snapshot is deliberately not a dependency of the
#       cluster, so deleting the cluster does not delete it, and it goes on
#       billing after the environment is supposed to be gone.
#       What is ACCEPTED is that a mistaken destroy here CANNOT BE UNDONE --
#       there is no final snapshot to restore from, by construction. The blast
#       radius is also wider than the cluster, which is the part that surprises
#       people: this one value governs the whole environment's teardown posture,
#       so with it false the SPA, dataset, access-log and load-balancer-log
#       buckets carry `force_destroy` and a destroy removes them WITH their
#       contents, including every noncurrent object version. The teardown runbook
#       tabulates six blockers this flag releases -- the cluster, three groups of
#       buckets, the container registry's `force_delete` and the load balancer's
#       own protection -- and the Cognito user pool tracks the same value without
#       appearing in that table. The runbook also flags the buckets as the one
#       case where the surprise is loss rather than survival, so anything that
#       must outlive the environment leaves it before the destroy, not after.
#       Assumptions: that is acceptable HERE and nowhere else because this
#       environment's data is reproducible -- rerunning the ETL against the
#       reference seed datasets under app/data regenerates it, and app/data is
#       read-only reference material that is always present. Production's ledger
#       is reproducible from nothing in this repository, which is the whole
#       reason its flags are inverted rather than merely stricter.
#       Alternatives Considered: carrying production's posture here too, so the
#       teardown procedure was identical in both roots. Rejected -- it would make
#       every destroy/recreate cycle in the environment whose purpose is
#       iteration a two-apply gesture, and it would buy protection for data that
#       a single ETL run reproduces. Note the deliberate asymmetry with the
#       secret recovery window below, which is NOT relaxed the same way and
#       explains at its own assignment why.
deletion_protection = false
skip_final_snapshot = true
# WHY : ⚠️ Refactoring Rationale: this read 0 -- immediate, unrecoverable deletion --
#       against production's 30, and is now 30. It is off-axis for the same reason
#       backup retention is, and it was the most destructive of the three
#       divergences: at 0 a `destroy` erased every generated credential with no
#       recovery window, so an accidental destroy could not be undone. What 0 bought
#       was a repeatable destroy/recreate cycle, because a scheduled-for-deletion
#       secret name cannot be reused until its window elapses. Trade-offs: recreating
#       this environment under the same names now requires
#       `aws secretsmanager delete-secret --force-delete-without-recovery` on
#       the five purpose secrets first, which is one deliberate operator step in place
#       of a standing setting that silently removed the recovery window from every one.
#       Refactoring Rationale: this operator step said SIX purpose secrets and main.tf
#       declares five -- the messaging HMAC key was withdrawn with the injection that
#       read it, and the count was left behind. A count in an operator instruction is
#       not decoration: the operator works down a list, finds five names, and has to
#       decide whether the sixth is a secret they failed to find or a sentence that
#       failed to move. The prose-count gate in .github/workflows/infra-ci.yml now
#       measures the aws_secretsmanager_secret declarations in this root against the
#       counts spelled in its documentation, so the two cannot part again silently.
secret_recovery_window_in_days = 30
# WHY : Assumptions: alarm_email_endpoints is deliberately ABSENT from this file. It
#       is a required input with no default, supplied out of band
#       (TF_VAR_alarm_email_endpoints or the deploy workflow's own variable),
#       because a team address is personal data this repository does not carry.
#       See infra/envs/dev/variables.tf for the full reasoning and
#       docs/runbooks/deploy.md for the operator step.
