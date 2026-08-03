-- =============================================================================
-- services/batch-service/src/main/resources/db/migration/V1__batch.sql
-- -----------------------------------------------------------------------------
-- Purpose:
--   The one and only Flyway migration shipped by batch-service, and the single
--   normative data-definition source of truth for the BATCH bounded context. It
--   creates every object the `batch` schema owns:
--
--     batch.batch_run  the durable step ledger. One row per (orchestrator run,
--                      step) pair, recording when the step started, when it
--                      finished, the lifecycle state it reached and the process
--                      exit status it published. It is simultaneously the
--                      restart record the mainframe baseline never had and the
--                      per-step idempotency key that makes an orchestrator
--                      redrive of an already-completed step a no-op.
--
--     batch.BATCH_*    the batch framework's own job-repository tables and
--                      sequences, placed in this schema so that the framework's
--                      durable job state and this module's step ledger live
--                      under one owner and inside one backup and restore
--                      boundary.
--
--   This module owns `batch` and nothing else. It writes rows into two further
--   schemas under narrowly scoped grants and reads a third, but it declares no
--   structure in any of them; see "Scope" below.
--
-- Preconditions (what must already be true when Flyway applies this file):
--   - The schema `batch` and the login role that owns it, carddemo_batch, exist
--     already. Both are created by data-migration/sql/V0__schemas_and_roles.sql
--     -- the schema at its lines 340 to 341 -- which runs against the target
--     database before any service starts. This file therefore issues no CREATE
--     SCHEMA and no CREATE ROLE, and Flyway is configured with schema creation
--     switched off so that a missing schema fails loudly here instead of being
--     conjured with the wrong owner.
--   - The connecting role is carddemo_batch, so every object below is created
--     owned by it. That ownership is what makes the default privileges granted
--     in V0 section 4 actually fire for these objects.
--   - Flyway is scoped to this module's own classpath location and to the
--     `batch` schema for both the migration and its own schema-history table.
--   - The framework's job-repository schema initializer is switched off, so
--     this file is the only writer of those tables.
--   - The persistence provider runs in validate-only mode against these
--     objects. Every column name and column type below is therefore half of a
--     two-way contract with the entity mapping, and the Java type each column
--     requires is stated at the column itself rather than left to inference.
--
-- Post-state established (what exists after this file has been applied):
--   - One table, batch.batch_run, with seven columns and five named
--     constraints: a surrogate identity primary key, a composite uniqueness
--     constraint over (run_id, step_name), a lifecycle-state domain check, an
--     exit-status tier check, and a state-versus-timestamp coherence check.
--   - Table and column comments carrying the contract into the database itself,
--     so an operator inspecting the table sees the reasoning without this file.
--   - Six job-repository tables and three named sequences, all prefixed BATCH_
--     inside the `batch` schema, matching the framework's published layout
--     exactly, plus the implicit sequence backing the identity column above.
--   - On batch_run, exactly two indexes: the primary key and the unique index
--     PostgreSQL creates for the uniqueness constraint. No index is declared by
--     hand anywhere in this file, on batch_run or on the framework tables, and
--     that absence is deliberate and justified where it would have gone.
--   - No schema, no role, no privilege change and no seed row of any kind.
--
-- Fails when:
--   - Flyway core is on the classpath without its PostgreSQL companion
--     artifact. Per-database support was moved out of Flyway core, so the core
--     artifact alone compiles, packages and passes every test, then aborts on
--     first container start with no database plugin found. The failure is at
--     run time, not build time, which is why both artifacts are declared side
--     by side in services/batch-service/pom.xml.
--   - The framework's job-repository schema initializer is left enabled. It
--     would create the same six tables from its own script and race this
--     migration, and whichever ran second would fail on objects that already
--     exist. Nothing below uses IF NOT EXISTS, precisely so that such a race
--     surfaces as an error rather than as a silent skip.
--   - Any column name or column type below disagrees with the entity mapping.
--     Validate-only mode compares the mapped type against the reported type and
--     refuses to start, which is the whole reason that mode was chosen: the
--     mismatch is reported at deployment rather than at the first query.
--   - The schema `batch` does not exist, or the connecting role cannot create
--     objects in it. Both mean the bootstrap in data-migration/sql has not run
--     against this database.
--   - The filename is altered. Flyway recognises a versioned migration only
--     with the exact V<version>__<description>.sql form; a single underscore
--     where the two-underscore separator belongs makes the file invisible, and
--     the schema is then never created with no error to show for it.
--
-- Derivation:
--   batch.batch_run has no baseline record layout behind it. It is not migrated
--   from a copybook; it is the durable form of a capability the baseline lacked
--   entirely, and its column list is set by the target design rather than by
--   app/cpy. The exit-status column is the single exception: its value domain
--   is read straight from app/cbl/CBTRN02C.cbl lines 229 and 230. Everything
--   under app/** is reference-only, cited by path and line, and never modified.
--
-- Governing convention:
--   docs/CODE_DOCUMENTATION_STANDARD.md, whose SQL section states the
--   obligation for this file type as a header block plus a why-comment on each
--   non-obvious constraint or index. SQL has no docstring construct, so this
--   header block is the module-entry-point equivalent, and each why-comment
--   below names at least one of Alternatives Considered, Refactoring Rationale,
--   Assumptions or Trade-offs. No linter reads this file -- the Checkstyle
--   configuration narrows itself to Java sources -- so review is the only gate
--   on that obligation, which is a reason to be stricter here, not looser.
--
-- WHY (non-obvious design decisions):
--   - Refactoring Rationale: the uniqueness constraint over (run_id, step_name)
--     is the mechanism that makes a redrive idempotent, and it replaces
--     nothing. There is no baseline checkpoint or restart contract for this
--     pipeline at all: the only RESTART= among the thirty-eight jobs in app/jcl
--     is commented out, at app/jcl/DEFGDGD.jcl line 2, which carries the //*
--     comment prefix, and no CHKPT= appears in any of them. Detail at the
--     constraint itself.
--   - Alternatives Considered: this module writes into two schemas it does not
--     own, which looks like a database-per-service violation until the reason
--     is stated. A saga and a transactional outbox with compensating reversal
--     were both evaluated and both rejected. Detail in the Scope section below.
--   - Assumptions: Flyway is confined to the `batch` schema, and this file
--     honours that by declaring no object outside it. Detail in Scope.
--   - Assumptions: Flyway needs a second artifact beyond its core to speak to
--     PostgreSQL at all, and omitting it fails at run time rather than at build
--     time. Detail in Scope.
--   - Assumptions: the framework's job-repository tables are created here, by
--     name, rather than by the framework's own initializer. Detail at the
--     section-3 heading.
--   - Assumptions: no transaction-control statement appears anywhere in this
--     file, unlike the bootstrap script it depends on. Detail in Scope.
--   - Trade-offs: the exit-status column is a small signed integer and not a
--     wider one, and the lifecycle column is a variable-length character type
--     and not an enumerated type of its own. Both give up something; detail at
--     each column.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. Scope -- one schema, and the three this file deliberately leaves alone
--
-- This module reaches four schemas at run time. It owns exactly one of them,
-- and the distinction is the whole content of this section:
--
--   batch      OWNED. Every object in this file lives here.
--   ledger     WRITTEN, under a narrowly scoped grant. Structure is declared by
--              transaction-service in its own V1__ledger.sql.
--   account    WRITTEN, under a narrowly scoped grant. Structure is declared by
--              account-service in its own V1__account.sql.
--   reference  READ ONLY, with no write grant at all. Structure and the
--              mandatory seed rows are declared by reference-service.
--
-- Those three are named here only so a reader knows where their definitions
-- live. They are named at table granularity and never at column granularity: a
-- column name repeated here would become a third statement of a contract this
-- file does not own, and the third statement is the one that drifts.
--
-- WHY : Alternatives Considered: the cross-schema write grants exist because
--       the nightly posting job commits three writes as ONE unit of work, and
--       two of the three records land in a schema this module does not own. In
--       app/cbl/CBTRN02C.cbl the paragraph 2000-POST-TRANSACTION. at line 424
--       performs, in order, 2700-UPDATE-TCATBAL at line 440, then
--       2800-UPDATE-ACCOUNT-REC at line 441, then 2900-WRITE-TRANSACTION-FILE
--       at line 442, and reaches its EXIT at line 444 having committed all
--       three together. Two designs that would have preserved
--       database-per-service purity were evaluated against that, and both were
--       rejected:
--
--       A saga over per-service databases replaces the single atomic commit
--       with a sequence of independently committed steps plus compensating
--       reversals. That makes states observable the baseline cannot produce --
--       a posted transaction whose category balance has not yet moved, for one
--       -- and the golden masters would flag those as parity failures,
--       correctly.
--
--       A transactional outbox with a compensating reversal fails for the same
--       concrete reason and adds a second: the reversal is itself a committed
--       write, so the intermediate state is not merely observable but durable.
--
--       Keeping one cluster and narrowing the grant is the lower-risk option
--       and the only one of the three that leaves the unit of work intact, so
--       it is the ONE documented exception to database-per-service purity in
--       the whole design. AAP section 0.4.1.3 records it as such.
--
-- WHY : Assumptions: the grants that make that exception work are NOT issued
--       here. data-migration/sql/V0__schemas_and_roles.sql owns the entire
--       privilege graph -- schema-level USAGE at its line 494 and the default
--       privileges that follow it -- and this file issues no GRANT, no REVOKE,
--       no CREATE ROLE and no CREATE SCHEMA of any kind. Splitting the graph
--       across nine migrations was the alternative and it cannot work: a grant
--       inside one service's migration could not cover a table another service
--       later adds to its own schema, and every per-service migration is
--       contractually forbidden from issuing one. The consequence to act on is
--       that a permission error in a running job is a bootstrap defect to
--       report against that file, never something to patch by adding a GRANT
--       here.
--
-- WHY : Assumptions: Flyway resolves this module's migrations from this
--       module's own classpath location, and both the migration and Flyway's
--       own schema-history table are pinned to `batch`. The history table has
--       to sit in the schema it describes, because this module connects under a
--       role scoped to that schema and could not otherwise read its own
--       migration state. Pinning the schema explicitly also means nothing below
--       depends on the connection search path being ordered a particular way:
--       the two mechanisms agree independently instead of one quietly relying
--       on the other. Every object below is additionally written
--       schema-qualified, so a search path that changed would still not move an
--       object.
--
-- WHY : Assumptions: Flyway can apply this file at all only because the
--       PostgreSQL-specific companion artifact is on the classpath beside
--       Flyway core. Per-database support was moved out of core into one module
--       per database, so core alone resolves, compiles, packages and passes
--       every test in this module, then aborts at first container start because
--       no database plugin claims the connection. Nothing in this file can
--       detect that, and no build gate in this repository can either -- which
--       is precisely why the two coordinates are declared adjacent to one
--       another in services/batch-service/pom.xml, both taking their version
--       from the parent so they cannot drift apart. A reader who removes the
--       companion as an apparently redundant dependency breaks deployment
--       rather than the build.
--
-- WHY : Assumptions: there is no BEGIN and no COMMIT anywhere in this file, and
--       that is a genuine difference from the bootstrap script it depends on
--       rather than an omission. V0__schemas_and_roles.sql wraps itself in an
--       explicit transaction at its line 175 because it is NOT Flyway-managed
--       and sits on no service's Flyway classpath, so nothing else would give
--       it atomicity. This file is Flyway-managed and PostgreSQL makes
--       data-definition statements transactional, so Flyway already runs the
--       whole file in one transaction that it commits only after recording the
--       version. A COMMIT written here would end that transaction early and
--       leave the objects created but the version unrecorded -- which reads as
--       a pending migration over a populated schema, the single worst state to
--       restart from.
-- -----------------------------------------------------------------------------


-- =============================================================================
-- 2. batch.batch_run -- the durable step ledger
--
-- One row per (orchestrator run, step) pair. The row is inserted when a step
-- begins and updated when it ends, so at any instant the table answers two
-- questions the baseline could not answer at all: which steps of this run have
-- already completed, and with what exit status.
--
-- Those two answers are what make an orchestrator redrive safe. A redrive
-- restarts an execution at the state that failed, and a step whose row is
-- already terminal for this run can then return immediately instead of doing
-- its work twice. AAP section 0.4.1.3 fixes the column list as
-- (run_id, step_name, status, started_at, finished_at, return_code); the
-- surrogate key below is in addition to those six, not one of them.
--
-- WHY : Assumptions: every column type here is one half of a two-way contract
--       with the entity mapping, because the persistence provider runs in
--       validate-only mode against this table and compares the mapped type
--       against the type the driver reports. The Java type each column requires
--       is therefore recorded AT the column rather than left to inference, and
--       every character column below is variable-length for that reason and not
--       for a storage one. This module is the only one in the reactor validated
--       this way -- the peer ledger context disables provider schema handling
--       entirely -- so this is the one place in the build where data-definition
--       and mapping must agree, and the only place where a disagreement is
--       caught for you rather than by a reader.
-- WHY : Assumptions: that check is narrower than it first appears, and knowing
--       where it stops is what tells a reader which agreements it does NOT
--       police. It DOES reject a mapped integer of a different width from the
--       column, a mapped ordinal enumeration against a character column, and a
--       mapped column name that is absent. It does NOT reject a differing
--       declared length, and it does NOT reject an offset-bearing temporal type
--       mapped onto a zone-less column, because the provider normalises that
--       one before comparing. So the widths chosen below and the zone-less
--       decision at started_at are held by this file and by review, not by the
--       startup check; only the type and the name are mechanically enforced.
-- =============================================================================

CREATE TABLE batch.batch_run (

    -- WHAT: surrogate key, generated by the database. Java type: Long.
    -- WHY : Alternatives Considered: (run_id, step_name) is a perfectly good
    --       natural key and was evaluated as the primary key. It was rejected
    --       because both of its parts are externally supplied strings -- the
    --       run identifier comes from the orchestrator and the step name from
    --       the state machine definition -- so a rename on either side would
    --       rewrite primary key values that rows elsewhere may already quote in
    --       a log or an incident record. The natural key keeps its full force
    --       as a uniqueness constraint below; only its role as the row's
    --       identity is declined.
    -- WHY : Assumptions: BY DEFAULT rather than ALWAYS. The persistence
    --       provider omits this column from its INSERT and reads the generated
    --       value back, which either form satisfies. BY DEFAULT additionally
    --       lets a fixture or a recovery script supply an explicit identifier
    --       without the overriding clause that ALWAYS would demand, and it is
    --       the form the provider itself emits for this dialect, so the two
    --       agree.
    -- WHY : Trade-offs: BY DEFAULT gives up the guarantee ALWAYS would provide.
    --       An explicit identifier does not advance the backing sequence, so a
    --       load that supplies its own values leaves the sequence behind them
    --       and the next generated value can collide with one already stored.
    --       That is accepted because the only writer in normal operation is the
    --       provider, which never supplies the column; a recovery or fixture
    --       load that does supply it is a deliberate act whose operator can
    --       reset the sequence afterwards. The alternative, ALWAYS, would make
    --       that recovery path need an OVERRIDING SYSTEM VALUE clause it is
    --       easy to omit, and the omission fails at the worst moment.
    id            BIGINT       GENERATED BY DEFAULT AS IDENTITY,

    -- WHAT: the orchestrator execution this step belongs to. Java type: String.
    -- WHY : Assumptions: the value is the orchestrator's own execution name, so
    --       the width is its bound and not a preference -- an execution name is
    --       limited to 80 characters, and a column narrower than that would
    --       reject a legitimate run while a wider one would advertise room the
    --       producer cannot use. It is deliberately NOT the business date:
    --       app/jcl/INTCALC.jcl line 22 passes the business date as
    --       PARM='2022071800' and app/jcl/POSTTRAN.jcl line 23 passes none at
    --       all, so the date is a job parameter that reruns re-supply
    --       identically. Keying the ledger on it would make a deliberate rerun
    --       of the same date collide with the run it is meant to repeat.
    run_id        VARCHAR(80)  NOT NULL,

    -- WHAT: the state-machine step this row records. Java type: String.
    -- WHY : Assumptions: 100 characters, matching the width the batch
    --       framework's own step-execution table gives its step name in section
    --       3 below. The two columns name the same steps, so a reader comparing
    --       this ledger against the framework's job history must never find one
    --       able to hold a name the other truncates.
    step_name     VARCHAR(100) NOT NULL,

    -- WHAT: lifecycle state of this step. Java type: the BatchRunStatus
    --       enumeration, persisted by NAME.
    -- WHY : Alternatives Considered: persisting the enumeration by ordinal
    --       position into a small integer was rejected outright. An ordinal is
    --       unreadable in a database session at the moment an operator most
    --       needs to read it, and reordering the Java constants would silently
    --       redefine the meaning of every row already stored. Storing the name
    --       costs a few bytes per row on a table bounded by the nightly chain.
    -- WHY : Trade-offs: a native enumerated type would let the database enforce
    --       the domain by itself. It was declined because adding a value to a
    --       native enumerated type is a schema change that must be sequenced
    --       against a deployment, whereas the check constraint below expresses
    --       exactly the same domain in a form any reader can see in this file.
    --       The width is 20 rather than the 9 the longest current name needs,
    --       so a future state name does not force a column alteration; the
    --       check constraint, not the width, is what actually bounds the
    --       domain.
    status        VARCHAR(20)  NOT NULL,

    -- WHAT: when this step began. Java type: LocalDateTime.
    -- WHY : Assumptions: a zone-less timestamp, matching the repository-wide
    --       temporal contract that TimestampFormatter in
    --       com.carddemo.common.time establishes for the migrated code, and
    --       matching the framework's own job-history timestamps in section 3 so
    --       that a step in this ledger and the same step in the job history can
    --       be compared without converting one of them first. A zoned type was
    --       considered there and rejected there; re-deciding it here would put
    --       two temporal conventions inside one schema.
    started_at    TIMESTAMP(6) NOT NULL,

    -- WHAT: when this step reached a terminal state, or null while it runs.
    --       Java type: LocalDateTime.
    -- WHY : Assumptions: nullable is load-bearing rather than permissive. Null
    --       is the only honest representation of a step that has begun and not
    --       yet ended, and it is what lets a redrive distinguish a step that
    --       completed from one whose container died mid-flight. The coherence
    --       constraint below is what stops that nullability from also
    --       permitting a terminal row with no end time.
    finished_at   TIMESTAMP(6),

    -- WHAT: the process exit status this step published, or null while it runs.
    --       Java type: Short.
    -- WHY : Assumptions: this column is the ONE legitimate numeric surface for
    --       the mainframe condition-code rubric in this migration, because the
    --       orchestrator reads the batch container's process exit status to
    --       decide whether the chain continues. The rubric must not reach a
    --       build or test gate, which is binary: encoding a tier there would
    --       either lose the distinction between a warn and a failure or turn a
    --       correctly-written business reject into a red build.
    -- WHY : Trade-offs: a small signed integer rather than a wider one. The
    --       published domain is enumerated by the tier check below and its
    --       largest value is 16, three orders of magnitude inside this type,
    --       and AAP section 0.4.1.3 derives bounded small integers to exactly
    --       this type. What is given up is headroom this domain has no use for;
    --       what is bought is consistency with the other bounded numeric code
    --       the batch chain writes, the reject reason drawn from
    --       app/cbl/CBTRN02C.cbl line 181. The cost of the choice is that the
    --       mapped Java type must be the matching narrow one, which is why it
    --       is named above rather than left to inference.
    return_code   SMALLINT,

    -- WHY : Assumptions: constraints are named rather than left to the server
    --       to name, because a violated constraint reports its own name and
    --       nothing else. A generated name tells an operator reading a failed
    --       nightly run which table and column were involved but not which rule
    --       was broken, and these five rules mean five different things.
    CONSTRAINT pk_batch_run PRIMARY KEY (id),

    -- WHY : Refactoring Rationale: this constraint is the redrive-idempotency
    --       mechanism, and it replaces NOTHING -- there is no baseline
    --       checkpoint or restart contract for the batch pipeline to port. The
    --       only RESTART= among the thirty-eight jobs in app/jcl is commented
    --       out: app/jcl/DEFGDGD.jcl line 2 reads //*  RESTART=STEP30 with the
    --       literal //* comment prefix, and the JOB05067 that trails it sits in
    --       the sequence field that any reader ignores. No CHKPT= appears in
    --       any of those thirty-eight jobs, and no base batch program under
    --       app/cbl contains checkpoint logic of its own. A failed step
    --       therefore had to be resubmitted by hand, from the top, with no
    --       durable record of what had already completed -- and re-running a
    --       completed posting step is not a wasted rerun, it posts twice.
    --       Uniqueness over the pair is what forecloses that: a redrive of an
    --       already-recorded step collides instead of inserting, so the step
    --       becomes a no-op rather than a repetition. It is also what makes the
    --       repository's lookup by run and step provably single-valued, so the
    --       optional it returns is a genuine optional and not a silent
    --       first-of-many.
    --
    --       Scope the claim exactly as written above. The bare token CHKPT does
    --       occur elsewhere in the tree -- four programs under
    --       app/app-authorization-ims-db2-mq carry WK-CHKPT-ID counters and
    --       CBPAUP0C.cbl line 355 issues an actual EXEC DLI CHKP -- but that is
    --       the authorization context's hierarchical-database checkpoint, not a
    --       JCL restart contract, and it belongs to a different bounded context
    --       and a different service. An unqualified claim would appear to be
    --       falsified by one grep.
    CONSTRAINT uq_batch_run_run_step UNIQUE (run_id, step_name),

    -- WHY : Assumptions: the three names are the whole domain of the
    --       BatchRunStatus enumeration, and this constraint is what keeps the
    --       database's notion of that domain from drifting away from the Java
    --       one. Validate-only mode checks that the column exists with a
    --       compatible type; it does not check that the values in it are legal.
    --       Without this constraint a typo in a hand-written recovery statement
    --       would store a state no redrive could interpret, and the row would
    --       look valid until the night it was read.
    CONSTRAINT ck_batch_run_status
        CHECK (status IN ('STARTED', 'COMPLETED', 'FAILED')),

    -- WHY : Assumptions: the exit status is drawn from a three-tier rubric and
    --       from nothing wider. 0 is clean completion. 4 is a soft warn in
    --       which the work completed and downstream states may still run, and
    --       it has exactly one origin in the entire baseline --
    --       app/cbl/CBTRN02C.cbl line 229 reads IF WS-REJECT-COUNT > 0 and line
    --       230 reads MOVE 4 TO RETURN-CODE. 8 and above is a hard failure the
    --       state's catch handler routes to failure notification. The gaps are
    --       the point: 1 to 3 and 5 to 7 are not tiers the baseline publishes,
    --       so storing one would record an outcome the orchestrator has no rule
    --       for. Null is admitted because a step that has not finished has
    --       published no exit status at all.
    -- WHY : Trade-offs: the graded values the baseline uses INTERNALLY are
    --       deliberately not admitted as their own tiers. app/cbl/CBTRN02C.cbl
    --       sets 8 at line 563 and 12 at line 569 into a working-storage result
    --       field and signals end of file with 16 at line 352, and none of
    --       those ever reaches a process exit status. Folding 12 into the
    --       failure tier loses the distinction between two internal conditions;
    --       admitting it as a tier of its own would invent an orchestration
    --       outcome the baseline never published. The first cost is the smaller
    --       one and is the one accepted.
    CONSTRAINT ck_batch_run_return_code
        CHECK (return_code IS NULL
               OR return_code = 0
               OR return_code = 4
               OR return_code >= 8),

    -- WHY : Assumptions: a step is either running or terminal, and the two
    --       nullable columns above must agree with the lifecycle column about
    --       which. A STARTED row that already carried an end time and an exit
    --       status would tell a redrive that the step is still in flight while
    --       simultaneously telling it how the step ended, and a COMPLETED or
    --       FAILED row with no end time would leave the run's duration
    --       unrecoverable. Either shape is unrepresentable in reality, so it is
    --       made unrepresentable here rather than left for a reader to assume.
    -- WHY : Trade-offs: the exit status is required to be absent while a step
    --       runs but is NOT required to be present once it is terminal. That
    --       asymmetry is intentional: a container killed by the platform
    --       reaches a terminal state without ever publishing an exit status,
    --       and demanding one would make the ledger unable to record precisely
    --       the failure an operator most needs recorded.
    CONSTRAINT ck_batch_run_lifecycle
        CHECK ((status = 'STARTED'
                AND finished_at IS NULL
                AND return_code IS NULL)
               OR (status <> 'STARTED'
                   AND finished_at IS NOT NULL))
);


-- WHY : Trade-offs: no index is declared beyond the primary key and the unique
--       index PostgreSQL creates for uq_batch_run_run_step, and that absence is
--       recorded rather than left silent so a later reader does not mistake it
--       for an oversight. Every access path this ledger serves is either a
--       lookup by run and step, which the unique index answers exactly, or a
--       scan of one run's steps, which the same index answers on its leading
--       column. The table's cardinality is bounded by the nightly chain -- AAP
--       section 0.4.1.7 defines eleven states, of which the ones that write
--       here are fewer still -- so any further index would be maintained on
--       every step transition and read by nothing. No peer context queries this
--       table either: reporting-service reads the transaction data and starts
--       state machine executions, and it deliberately declares no job
--       repository and no ledger of its own, so there is no external query
--       shape to serve. The cost accepted is that an unanticipated future query
--       may need an index added in a later migration, which is the cheaper
--       mistake to make.
-- WHY : Assumptions: the comments below are written into the database itself,
--       not only into this file. An operator inspecting the table during a
--       failed nightly run reads them from the session without access to this
--       repository, and that session is exactly where the reasoning is needed
--       most. They restate the contract for that reader; they do not duplicate
--       the rationale above, which stays here where it is reviewable.

COMMENT ON TABLE batch.batch_run IS
    'Durable step ledger for the nightly batch chain. One row per (run_id, '
    'step_name) pair. Provides the restart record and the per-step idempotency '
    'key that make an orchestrator redrive of an already-completed step a '
    'no-op. The mainframe baseline had no checkpoint or restart contract for '
    'this pipeline, so this table is an addition and not a port.';

COMMENT ON COLUMN batch.batch_run.id IS
    'Surrogate key. The natural key (run_id, step_name) is enforced separately '
    'by uq_batch_run_run_step; it is not the identity because both of its '
    'parts are externally supplied strings.';

COMMENT ON COLUMN batch.batch_run.run_id IS
    'Orchestrator execution name for this run, bounded at 80 characters by the '
    'orchestrator. Deliberately not the business date: the date is a job '
    'parameter that reruns re-supply identically.';

COMMENT ON COLUMN batch.batch_run.step_name IS
    'State-machine step this row records. Width matches the batch framework '
    'step-execution table in this schema so neither can hold a name the other '
    'truncates.';

COMMENT ON COLUMN batch.batch_run.status IS
    'Lifecycle state: STARTED, COMPLETED or FAILED. Stored by name, not by '
    'ordinal, so a session reads it directly and reordering the Java constants '
    'cannot redefine stored rows. Domain enforced by ck_batch_run_status.';

COMMENT ON COLUMN batch.batch_run.started_at IS
    'When this step began. Zone-less, matching the repository temporal '
    'contract and the framework job-history timestamps in this schema.';

COMMENT ON COLUMN batch.batch_run.finished_at IS
    'When this step reached a terminal state; null while it runs. The null is '
    'how a redrive tells a completed step from one whose container died '
    'mid-flight.';

COMMENT ON COLUMN batch.batch_run.return_code IS
    'Process exit status published to the orchestrator: 0 clean, 4 soft warn '
    '(the chain continues), 8 or above hard failure. Null while the step runs, '
    'and also when a killed container reached a terminal state without '
    'publishing one. The 4 tier originates at app/cbl/CBTRN02C.cbl:229-230.';


-- =============================================================================
-- 3. The batch framework's job-repository tables and sequences
--
-- Six tables and three sequences holding the framework's own durable job state:
-- which job instances exist, which executions each has had, the parameters each
-- execution ran with, the step executions inside it, and the serialised
-- execution contexts that let a restarted step resume where it stopped.
-- Together with batch.batch_run in section 2 they supply restart: the framework
-- knows how far a job got, and the ledger knows whether a step's effects are
-- already applied.
--
-- WHY : Assumptions: these objects are created HERE, by name, and the
--       framework's own schema initializer is switched off in this module's
--       configuration. The two settings are one contract with this file. Left
--       enabled, that initializer would run the framework's script against the
--       same schema on startup and race Flyway; whichever ran second would fail
--       on objects that already exist, and it would fail during container start
--       rather than during a migration, where the diagnosis is much harder. No
--       statement below uses IF NOT EXISTS, so if the race ever does happen it
--       is reported instead of silently skipped.
-- WHY : Assumptions: every table and column name, every type, every constraint
--       name and every sequence name below is transcribed from the framework's
--       own PostgreSQL schema script for the version this build resolves, and
--       none of them is chosen here. The framework reaches these tables through
--       hand-written statements that name their columns literally, so a name
--       invented locally would compile, migrate and then fail at the first job
--       launch. The only change made to the transcription is the schema
--       qualifier: the configured table prefix is schema-qualified, which the
--       framework concatenates onto both its table names AND its sequence
--       names, so the sequences must carry the same qualifier as the tables or
--       the first identifier the framework asks for will not exist. PostgreSQL
--       folds these unquoted identifiers to lower case, and the framework's own
--       statements are unquoted too, so both sides fold identically; nothing
--       below is quoted, deliberately, because quoting one side and not the
--       other is what would break the match.
-- WHY : Trade-offs: the transcription is kept verbatim rather than restyled to
--       this file's own conventions -- no renamed constraints, no added
--       comments, no reordered columns, no added indexes. That gives up
--       internal consistency with section 2, which is written in this
--       repository's voice. What it buys is a block that can be diffed directly
--       against the framework's script when the framework version moves, which
--       is the only maintenance this block will ever need and the one thing a
--       restyled copy would make hard. The framework declares no index beyond
--       the primary keys and the one unique constraint below, and none is added
--       here for the same reason.
-- =============================================================================

CREATE TABLE batch.BATCH_JOB_INSTANCE (
    JOB_INSTANCE_ID BIGINT  NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_NAME VARCHAR(100) NOT NULL,
    JOB_KEY VARCHAR(32) NOT NULL,
    constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY)
) ;

CREATE TABLE batch.BATCH_JOB_EXECUTION (
    JOB_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
    VERSION BIGINT,
    JOB_INSTANCE_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    constraint JOB_INST_EXEC_FK foreign key (JOB_INSTANCE_ID)
    references batch.BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
) ;

CREATE TABLE batch.BATCH_JOB_EXECUTION_PARAMS (
    JOB_EXECUTION_ID BIGINT NOT NULL,
    PARAMETER_NAME VARCHAR(100) NOT NULL,
    PARAMETER_TYPE VARCHAR(100) NOT NULL,
    PARAMETER_VALUE VARCHAR(2500),
    IDENTIFYING CHAR(1) NOT NULL,
    constraint JOB_EXEC_PARAMS_FK foreign key (JOB_EXECUTION_ID)
    references batch.BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE batch.BATCH_STEP_EXECUTION (
    STEP_EXECUTION_ID BIGINT  NOT NULL PRIMARY KEY,
    VERSION BIGINT NOT NULL,
    STEP_NAME VARCHAR(100) NOT NULL,
    JOB_EXECUTION_ID BIGINT NOT NULL,
    CREATE_TIME TIMESTAMP NOT NULL,
    START_TIME TIMESTAMP DEFAULT NULL,
    END_TIME TIMESTAMP DEFAULT NULL,
    STATUS VARCHAR(10),
    COMMIT_COUNT BIGINT,
    READ_COUNT BIGINT,
    FILTER_COUNT BIGINT,
    WRITE_COUNT BIGINT,
    READ_SKIP_COUNT BIGINT,
    WRITE_SKIP_COUNT BIGINT,
    PROCESS_SKIP_COUNT BIGINT,
    ROLLBACK_COUNT BIGINT,
    EXIT_CODE VARCHAR(2500),
    EXIT_MESSAGE VARCHAR(2500),
    LAST_UPDATED TIMESTAMP,
    constraint JOB_EXEC_STEP_FK foreign key (JOB_EXECUTION_ID)
    references batch.BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE TABLE batch.BATCH_STEP_EXECUTION_CONTEXT (
    STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    constraint STEP_EXEC_CTX_FK foreign key (STEP_EXECUTION_ID)
    references batch.BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
) ;

CREATE TABLE batch.BATCH_JOB_EXECUTION_CONTEXT (
    JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
    SHORT_CONTEXT VARCHAR(2500) NOT NULL,
    SERIALIZED_CONTEXT TEXT,
    constraint JOB_EXEC_CTX_FK foreign key (JOB_EXECUTION_ID)
    references batch.BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
) ;

CREATE SEQUENCE batch.BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE batch.BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
CREATE SEQUENCE batch.BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
