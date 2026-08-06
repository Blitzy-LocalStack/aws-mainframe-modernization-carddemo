# =============================================================================
# services/batch-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root, argument-driven container image for the CardDemo batch
#   bounded context. The process runs one Spring Batch job and reports its
#   outcome to AWS Step Functions through the container exit status.
#
# Parameters:
#   --job=<name> (string): selects exactly one of
#     preflight-daily-transactions, post-transactions, calculate-interest,
#     backup-transactions, combine-transactions, export, or import.
#   --business-date=<yyyy-MM-dd> (10-character date token): supplies the injected
#     business date; the orchestrated API uses ISO while the application forwards
#     either ISO or compact yyyyMMdd00 input verbatim. The calculate-interest job
#     requires it and the image supplies no default.
#
# Return values:
#   The process status preserves three observable tiers, and the ENTRYPOINT below
#   is what carries them out of the container unchanged:
#   0 (integer): the selected job completed without rejects.
#   4 (integer): the selected job completed and produced soft rejects.
#   >= 8 (integer): argument validation, infrastructure, I/O, or job execution
#     failed.
#
# Exceptions or errors:
#   A datasource or S3 failure, or a missing 'DEFAULT' disclosure-group row,
#   aborts the run in the hard-failure exit tier.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md
#
# WHAT: From the repository root, build this image with:
#       docker build --file services/batch-service/Dockerfile \
#         --tag carddemo-batch-service:validation .
# WHY : Assumptions: the repository root is the build context because the image
#       compiles the unpublished common-lib sibling from source and the inherited
#       Checkstyle gate reads config/checkstyle outside the services reactor.
# =============================================================================

# WHY : Assumptions: the build JDK deliberately matches the runtime's Amazon
#       Corretto vendor and Java 21 major version, preventing a class-file level
#       or vendor-specific behavior from appearing only after the jar is shipped.
# WHY : Refactoring Rationale: the base is pinned by DIGEST and not by tag alone,
#       because a tag is a mutable pointer. The tag below was observed resolving
#       to two different multi-architecture indexes at two different times, so
#       the tag alone does not identify a build input: two builds of this same
#       commit can compile against two different JDK patch levels and produce two
#       different jars, and a bisect over the source cannot see the difference.
#       The digest makes the base an exact input, so a rebuild of a commit is a
#       rebuild and not a re-resolution. It also means an upstream change is a
#       reviewable edit to this line rather than something a rebuild absorbs
#       silently, which is the supply-chain concern named CWE-1357.
# WHY : Assumptions: the digest names the multi-architecture INDEX, not one
#       platform's manifest, so the pin does not silently make this a single
#       architecture build; the daemon still selects the manifest for the build
#       platform. Pinning a platform manifest instead would break a build on the
#       other architecture with a manifest-not-found error rather than an obvious
#       message. The tag is retained beside the digest because the digest alone
#       tells a reader nothing about what it is; the digest is what is enforced.
#       The same convention is already applied at data-migration/Dockerfile.
FROM maven:3.9.16-amazoncorretto-21-al2023@sha256:a7f84ee223e9b6a1ba462667cdb98e282adf79d02fbd1ad3091ad0bb2feff1d7 AS build

# WHY : Assumptions: mirroring the repository's services depth under /build keeps
#       the parent's basedir-activated Checkstyle path valid in both the
#       aggregator and its selected modules.
WORKDIR /build/services

# WHY : Trade-offs: POMs and the documentation-gate configuration precede source
#       files so source-only changes reuse the dependency layer. The extra copy
#       instructions make the Dockerfile longer, but avoid repeating Maven's
#       dependency download, the dominant immutable cost of this image build.
COPY services/pom.xml ./pom.xml
COPY services/common-lib/pom.xml ./common-lib/pom.xml
COPY services/batch-service/pom.xml ./batch-service/pom.xml
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory declared by an
#       aggregator before applying -pl. The copied parent therefore retains only
#       common-lib and batch-service in its build-only module list; otherwise the
#       intentionally absent service directories fail model loading before Maven
#       can select the two permitted projects. The source parent POM is unchanged.
# WHY : Refactoring Rationale: the earlier form of this filter deleted ANY line
#       inside <modules> that contained `<module>` and was not one of the two
#       retained entries, and it counted nothing. Two failure modes followed from
#       that, both silent. An XML comment mentioning a module - exactly the comment
#       a maintainer would add to explain why a module is absent here - was deleted
#       as if it were a module entry. And if either retained module were renamed,
#       the filter would still succeed while emitting a POM that no longer declared
#       it, so the failure surfaced later as an obscure `-pl` resolution error
#       pointing at Maven rather than at this line. The rewrite below fixes both:
#       comments are passed through verbatim, and the transformation ASSERTS its
#       own outcome so a POM-shape change fails here, with the count that was
#       wrong, instead of producing a plausible-looking wrong POM.
# WHY : Assumptions: exactly two module entries must survive (common-lib and
#       batch-service) and at least one must be dropped. The second assertion is
#       what catches a source POM that has already been trimmed or that lists only
#       these two modules - in that case this whole RUN is unnecessary, and
#       discovering it here is better than carrying a no-op transformation.
# WHY : Trade-offs: the awk program is longer and tracks a comment state machine
#       rather than matching one line at a time. That cost buys a filter whose
#       failure is always loud; a shorter filter that can silently emit a subtly
#       wrong POM is the more expensive option once it does.
RUN awk ' \
    in_comment { print; if ($0 ~ /-->/) { in_comment = 0 } next } \
    /<!--/ && $0 !~ /-->/ { print; in_comment = 1; next } \
    /<!--/ { print; next } \
    /<modules>/ { in_modules = 1; print; next } \
    /<\/modules>/ { in_modules = 0; print; next } \
    in_modules && /<module>/ { \
      if ($0 ~ /<module>common-lib<\/module>/ \
          || $0 ~ /<module>batch-service<\/module>/) { kept++; print } \
      else { dropped++ } \
      next \
    } \
    { print } \
    END { \
      if (kept != 2) { \
        printf "pom trim: expected 2 retained <module> entries, kept %d\n", kept \
          > "/dev/stderr"; exit 3 \
      } \
      if (dropped < 1) { \
        printf "pom trim: expected >= 1 dropped <module> entry, dropped %d\n", \
          dropped > "/dev/stderr"; exit 4 \
      } \
    }' pom.xml > pom.xml.reactor \
    && mv pom.xml.reactor pom.xml \
    && mvn -B -ntp -f pom.xml -pl common-lib,batch-service -am \
       dependency:go-offline

COPY services/common-lib/src ./common-lib/src
COPY services/batch-service/src ./batch-service/src

# WHY : Trade-offs: tests are skipped while packaging because
#       .github/workflows/services-ci.yml runs both tiers before any image is
#       built -- `mvn ... clean test` for the Surefire tier and
#       `mvn ... test-compile failsafe:integration-test failsafe:verify` for the
#       Failsafe tier. Assumptions: those are named as two commands rather than one
#       because a whole-reactor `clean verify` does NOT reach either tier's
#       assertion point -- it halts at spring-boot:repackage on the first module
#       with no main class, before Failsafe executes -- so citing it as the command
#       that "executes the suites" would have been false. Checkstyle remains bound
#       to validate and runs before package, so the build-correctness documentation
#       gate is retained here rather than treated as a test. The process-status
#       rubric never relaxes this binary Maven gate.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,batch-service -am \
    clean package -DskipTests

# WHY : Assumptions: the publisher provides no Alpine variant of this Corretto
#       image; `21-alpine` does not resolve and would fail the build. The
#       selection criterion is the highest 21.x `al2023-headless` release the
#       publisher offered when this pin was reviewed -- stated as a criterion
#       rather than as a standing fact, because the publisher can add a newer
#       21.x at any time and a claim that this tag is the highest would then be
#       false without anything in this repository changing. Headless is selected
#       because the batch process needs no AWT or graphics stack.
# WHY : Refactoring Rationale: pinned by digest for the reason given on the build
#       stage above, and the drift it prevents is not hypothetical here: this tag
#       was observed resolving to a different index from the one a local cache
#       held, with different manifests for both amd64 and arm64. Unpinned, the
#       runtime layer of this image is whatever the tag pointed at on the day of
#       the build.
# WHY : Assumptions: the digest is also what makes the HEALTHCHECK below sound.
#       That command invokes /usr/bin/curl, which this image provides and which
#       nothing in this file installs -- verified present in THIS digest, at
#       curl 8.17.0. A base image is entitled to drop a utility between two
#       resolutions of one tag, and if it did, the probe would fail on a missing
#       binary while reporting an unhealthy container: the task would be replaced
#       for a reason that has nothing to do with the job. Pinning the digest
#       means the presence of that binary is a property of a reviewed input
#       rather than of the registry's current state, so the two lines are
#       verified together and must be changed together.
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless@sha256:75f1cdabc3d8758f5499bf67046991bc7797e37156c7445f0048eb6d17c90dcf AS runtime

# WHY : Alternatives Considered: installing shadow-utils solely for useradd and
#       groupadd was rejected because the pinned minimal image omits those tools
#       and retaining their package payload adds no runtime capability. Standard
#       passwd/group records provide the same fixed identity without another
#       package or repository dependency.
# WHY : Assumptions: the ECS task definition does not drop privileges, so the
#       image must guarantee UID/GID 10001 itself. The job writes only below its
#       owned working directory and the platform-provided /tmp directory.
RUN printf '%s\n' 'carddemo:x:10001:' >> /etc/group \
    && printf '%s\n' \
       'carddemo:!:10001:10001:CardDemo batch:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: using the owned application directory as the process working
#       directory keeps relative temporary output inside the only application path
#       the fixed identity may modify.
WORKDIR /app

# WHY : Assumptions: Maven's verified final name is
#       batch-service-1.0.0-SNAPSHOT.jar and the Boot repackage goal makes that
#       archive self-contained. Copying that single artifact excludes sources,
#       build caches, reports, and the reactor's target directories.
COPY --from=build --chown=10001:10001 \
    /build/services/batch-service/target/batch-service-1.0.0-SNAPSHOT.jar \
    /app/app.jar

# WHY : Assumptions: port 8080 is reserved for the actuator health probe only.
#       This argument-driven module publishes no business REST surface and no
#       administrative endpoint that can trigger a job.
EXPOSE 8080

# WHY : Assumptions: the named account resolves to the fixed 10001:10001 identity
#       created above; naming it makes the non-root intent visible in image
#       metadata while the numeric records keep deployment behavior deterministic.
USER carddemo:carddemo

# WHY : Assumptions: the exact pinned headless runtime image contains
#       /usr/bin/curl and does not contain wget, so the probe requires no package
#       installation and fails explicitly on a non-success HTTP response. Both
#       halves were verified by running the digest pinned above: curl 8.17.0 is
#       present at that path and `command -v wget` finds nothing. The claim is
#       tied to the digest and not to the tag, because a probe that names a binary
#       the base no longer ships reports an unhealthy container for a reason
#       unrelated to the job -- so this line and the runtime FROM line must be
#       revisited together.
# WHY : Assumptions: the probe addresses the LOOPBACK interface, which is not a
#       stylistic choice about probes but the only address that answers. The
#       server.address key of
#       services/batch-service/src/main/resources/application.yml binds the
#       listener to 127.0.0.1 so that nothing outside the task can reach it, and
#       a probe naming the container's own routable address would therefore time
#       out on a perfectly healthy task. The two settings are one decision.
# WHY : Trade-offs: a periodic health check is unusual for a run-to-completion
#       task because a correctly finished process has no steady healthy state.
#       It is retained because the service-image contract mandates one and the
#       datasource-aware actuator response can detect a hung dependency while the
#       job is running. ECS and Step Functions still decide success from the
#       process exit status, never from this probe.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["/usr/bin/curl", "--fail", "--silent", "--show-error", \
         "--max-time", "4", "--output", "/dev/null", \
         "http://127.0.0.1:8080/actuator/health"]

# WHY : Refactoring Rationale: app/cbl/CBTRN02C.cbl:229-230 produces the warn
#       tier with `MOVE 4 TO RETURN-CODE`; app/jcl/TRANBKP.jcl:51 consumes the
#       threshold as `COND=(4,LT)`. JCL expresses a SKIP predicate, so the step
#       runs when rc <= 4, while a Step Functions Choice expresses a RUN
#       predicate and must spell that condition directly. Collapsing 4 into
#       either neighboring tier changes the pipeline's observable behavior.
# WHY : Trade-offs: the numeric 0/4/>=8 rubric belongs only to the parity oracle
#       and the container status read by Step Functions. It never reaches Maven,
#       Checkstyle, Surefire, Failsafe, or JUnit, whose gates remain binary.
# WHY : Assumptions: the job writes `TRANSACTIONS REJECTED  :` with two spaces
#       before the colon, exactly as app/cbl/CBTRN02C.cbl:228 does. Direct exec
#       leaves stdout unfiltered and unwrapped so the parity oracle sees those
#       bytes unchanged.
# WHY : Assumptions: an ECS ContainerOverrides.Command replaces CMD and appends
#       its array after this exec-form entrypoint. Step Functions is the only
#       source of `--job=<name> --business-date=<yyyy-MM-dd>` in orchestrated
#       runs; no CMD supplies a fallback or reformats the ten-character token.
#       This preserves the injected-parameter contract at
#       app/jcl/INTCALC.jcl:22 and makes reruns independent of the wall clock.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
