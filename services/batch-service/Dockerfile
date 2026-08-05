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
FROM maven:3.9.16-amazoncorretto-21-al2023 AS build

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
RUN awk '/<modules>/{in_modules=1} \
    in_modules && /<module>/ \
    && $0 !~ /<module>common-lib<\/module>/ \
    && $0 !~ /<module>batch-service<\/module>/{next} \
    {print} \
    /<\/modules>/{in_modules=0}' pom.xml > pom.xml.reactor \
    && mv pom.xml.reactor pom.xml \
    && mvn -B -ntp -f pom.xml -pl common-lib,batch-service -am \
       dependency:go-offline

COPY services/common-lib/src ./common-lib/src
COPY services/batch-service/src ./batch-service/src

# WHY : Trade-offs: tests are skipped while packaging because
#       .github/workflows/services-ci.yml and
#       `mvn -f services/pom.xml clean verify` execute the unit and integration
#       suites. Checkstyle remains bound to validate and runs before package, so
#       the build-correctness documentation gate is retained rather than treated
#       as a test. The process-status rubric never relaxes this binary Maven gate.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,batch-service -am \
    clean package -DskipTests

# WHY : Assumptions: the publisher provides no Alpine variant of this Corretto
#       image; `21-alpine` does not resolve and would fail the build. Version
#       21.0.12 is the highest published 21.x tag, and headless is selected
#       because the batch process needs no AWT or graphics stack.
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless AS runtime

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
#       installation and fails explicitly on a non-success HTTP response.
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

# WHAT: The process status preserves three observable tiers: 0 is clean, 4 is a
#       completed run with rejects, and 8 or greater is a hard failure.
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