# =============================================================================
# services/card-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo card context: the migrated
#   form of app/cbl/COCRDLIC.cbl, COCRDSLC.cbl and COCRDUPC.cbl online together with
#   the batch reader app/cbl/CBACT02C.cbl. The baseline's base VSAM cluster and its
#   CARDAIX alternate index become one table with a real secondary index.
#
# Stages:
#   build   - resolves the reactor's dependencies, then compiles the unpublished
#             common-lib sibling and this module from source in one invocation,
#             producing the repackaged bootable jar under card-service/target.
#   runtime - carries that one jar plus the shared listener-material entry point,
#             and nothing else from the build stage: no Maven, no compiler, no
#             resolved dependency cache and no sources.
#
# Parameters:
#   Build context (REQUIRED): the REPOSITORY ROOT, never services/. The reason is
#   mechanical rather than stylistic and is spelled out on the first COPY group
#   below; in short, config/checkstyle is a SIBLING of services/ and Docker cannot
#   COPY from outside its context.
#
#   Build arguments: this image declares NO build argument. Nothing about the
#   image varies per environment, which is what lets one built artifact serve
#   every environment.
#
#   Command arguments: the entry point takes no argument of its own and forwards
#   "$@" (config/docker/generate-listener-material.sh:189), so a `docker run`
#   trailer or an ECS container `command` override reaches the JVM unchanged.
#   There is no per-run token of the kind the batch image accepts, because this
#   process is a long-running TLS listener rather than a job.
#
#   Runtime configuration arrives entirely through environment variables that the
#   ECS task definition supplies from AWS Systems Manager Parameter Store and AWS
#   Secrets Manager. NONE of them is baked into this image, and no VALUE for any
#   of them appears anywhere in this file: the names below are the whole contract.
#
#   Variables whose application.yml placeholder declares NO fallback, so the
#   context cannot start without them:
#     - SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
#     - SPRING_FLYWAY_USER, SPRING_FLYWAY_PASSWORD
#     - SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
#     - CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID
#     - CARDDEMO_SECURITY_CVV_KEY_ID
#     - CARDDEMO_SECURITY_CARD_SELECTOR_SIGNING_KEY
#   CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD also declares no fallback, and is the
#   one member of that class the deployment does NOT supply: the entry point mints
#   it per task and exports it before the JVM starts.
#
#   Variables that are optional because their placeholder carries a default, and
#   which are named here so that a reader can tell the two classes apart:
#     - SPRING_PROFILES_ACTIVE            selects application-dev.yml or -prod.yml
#     - SERVER_PORT                       overrides the listen port
#     - MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE   widens or narrows actuator
#     - CARDDEMO_COGNITO_ADMIN_GROUP_NAME, CARDDEMO_COGNITO_USER_GROUP_NAME
#     - CARDDEMO_DB_SSL_ROOT_CERT
#     - CARDDEMO_SERVER_TLS_KEYSTORE, CARDDEMO_SERVER_TLS_KEY_ALIAS
#     - JAVA_OPTS                         see the ENV instruction below
#   The task additionally receives AWS_REGION, which this image neither sets nor
#   defaults. It is named here because no placeholder in application.yml refers to
#   it and its absence would otherwise look like an oversight: this module ships a
#   key management client whose region and credentials resolve through the SDK's
#   own default provider chains rather than through Spring configuration (see the
#   Javadoc on config/KmsConfig.java), so an unset region surfaces as an SDK
#   client construction failure with nothing in this file or that one to point at.
#
# Return values:
#   A two-stage image whose final layer runs the executable Spring Boot layered
#   jar as the non-root identity 10001:10001, listening on 8080 over TLS, and
#   reporting health at /actuator/health.
#   Process exit status, which ECS reads to decide whether to replace the task:
#     0 (integer): the JVM exited after a requested shutdown.
#     non-zero (integer): the context failed to start or the JVM terminated
#       abnormally.
#
# Exceptions or errors:
#   These are the concrete ways this build and this container fail. Each is
#   intentional, so a reader who hits one knows it is the design working.
#     1. `docker build` fails at Maven `validate` if any Java source in this
#        module or in common-lib violates the Checkstyle Javadoc gate. That gate
#        is deliberate and must never be bypassed; see the first COPY group.
#     2. `docker build` fails at `validate` if config/checkstyle is absent from
#        the build context, because the ruleset's SuppressionFilter is declared
#        optional="false" and therefore fail-closed.
#     3. `docker build` fails at a COPY, before Maven runs at all, if the context
#        is not the repository root. This is the same root cause as 2 reaching the
#        build earlier. Every COPY in this file names a repository-root-relative
#        path, so in a narrowed context all seven fail; BuildKit resolves local
#        context checksums concurrently and reports whichever it settles on first,
#        so the terminal message names an arbitrary one of them rather than
#        reliably naming config/checkstyle. Measured against a services/ context,
#        all seven are reported: "/config/checkstyle": not found alongside
#        "/services/pom.xml", the two sibling POMs, both src trees and
#        "/config/docker/generate-listener-material.sh". A reader who sees only
#        the last of those should not conclude the ruleset path is fine.
#     4. The container fails to start if any no-fallback variable listed above is
#        unset, if SPRING_DATASOURCE_URL is unreachable, if the JWT issuer is
#        unreachable, or if a Flyway checksum mismatch or a failed migration
#        aborts the run.
#     5. The HEALTHCHECK reports unhealthy until the context is fully initialised
#        and the datasource is reachable, so a task that cannot reach its database
#        never joins the load balancer rather than serving errors.
#
# Provenance:
#   The baseline loads this context's three online programs from the CICS load
#   library declared at app/csd/CARDDEMO.CSD:489-491, where
#   DEFINE LIBRARY(CARDDLIB) names DSNAME01(AWS.M2.CARDDEMO.LOADLIB). This image
#   is the container-registry equivalent of that library and is an ADDITIONAL
#   deployment path alongside it: the migration adds a path and removes none. That
#   CSD, and every file under app/, is REFERENCE ONLY and is never modified; it is
#   cited here by path and line and in no other way.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md
#
# WHAT: From the repository root, build this image with:
#       docker build --file services/card-service/Dockerfile \
#         --tag carddemo-card-service:validation .
# WHY : Assumptions: the repository root is the build context, not services/,
#       because the image compiles the unpublished common-lib sibling from source
#       and the inherited Checkstyle documentation gate reads config/checkstyle,
#       which sits OUTSIDE the services reactor. Every COPY below is therefore
#       repository-root-relative and all of them fail if the context is narrowed.
# WHY : Assumptions: building this image is a VALIDATION exercise. It proves the
#       two pins resolve, that both modules pass the documentation gate and that
#       the jar is produced and lands where the entry point looks for it. It
#       publishes nothing: no registry push, no deployment and no live account are
#       involved at any point in this file.
# =============================================================================

# WHY : Assumptions: the build JDK deliberately matches the runtime's Amazon
#       Corretto vendor and Java 21 major version, so a class-file level or
#       vendor-specific behavior cannot first appear after the jar is shipped.
# WHY : Assumptions: the digest is the MULTI-PLATFORM INDEX digest read with
#       `docker buildx imagetools inspect`, not the platform manifest digest that
#       `docker images` reports, so linux/amd64 and linux/arm64 builders both
#       resolve it. The tag is retained beside it so a reader can see the version
#       without a registry round trip.
FROM maven:3.9.16-amazoncorretto-21-al2023@sha256:a7f84ee223e9b6a1ba462667cdb98e282adf79d02fbd1ad3091ad0bb2feff1d7 AS build

# WHY : Assumptions: mirroring the repository's services depth under /build keeps
#       the parent POM's basedir-activated Checkstyle path valid in both the
#       aggregator and the two modules selected from it.
WORKDIR /build/services

# WHY : Refactoring Rationale: this image shipped no database trust anchor, and
#       nothing about it looked broken. application.yml connects with
#       sslmode=verify-full and resolves sslrootcert to
#       /etc/ssl/certs/carddemo-rds-ca-bundle.pem, both environment roots pass that
#       same literal as CARDDEMO_DB_SSL_ROOT_CERT, and infra/modules/ecs-service
#       refuses to render a task definition that omits it -- so every layer of the
#       configuration named a file that no layer of the image created. pgjdbc opens
#       that path before it opens a socket, so the omission was not a silent
#       downgrade to an unverified connection: it was eight services that cannot
#       reach their database, reporting an unreadable-file error while reading as
#       correctly configured everywhere a reviewer would look.
# WHY : Assumptions: the fetch, the digest verification and the reasoning behind
#       both live in config/docker/install-rds-trust-anchor.sh, and are stated once
#       there rather than eight times here. The script pins the bundle by SHA-256,
#       so this layer either installs the exact reviewed bytes or fails the build.
# WHY : Trade-offs: this sits at the TOP of the build stage, above the POM copies,
#       for the same layer-ordering reason they sit above the sources -- its only
#       input is the script, so a Java edit does not re-fetch the bundle and a
#       rebuild pays the network cost once. The cost is that a change to the script
#       invalidates the dependency warm-up below it, which is the rarer edit of the
#       two.
# WHY : Alternatives Considered: fetching it in the runtime stage instead, which
#       would need one fewer COPY. Rejected because it would put the script and a
#       scratch download into the shipped image and add a network step to the layer
#       that ships; fetching here and copying one verified file forward keeps the
#       runtime stage to exactly the file the JVM reads.
COPY config/docker/install-rds-trust-anchor.sh /tmp/install-rds-trust-anchor.sh
RUN /bin/sh /tmp/install-rds-trust-anchor.sh /tmp/carddemo-rds-ca-bundle.pem

# WHY : Trade-offs: POMs and the documentation-gate configuration are copied
#       before any source file so that a source-only change reuses the resolved
#       dependency layer. The extra COPY instructions make this file longer, but
#       they avoid repeating Maven's dependency download, which dominates the
#       otherwise immutable cost of this build.
# WHY : Trade-offs: the accepted residue is that dependency:go-offline is not a
#       guarantee. In a multi-module reactor it can miss a plugin dependency that
#       only a later lifecycle phase asks for, so the warm-up below is a best
#       effort rather than a proof of completeness and the `package` step may
#       still resolve a straggler. The split is kept because the dependency tree,
#       not the residue, is what dominates a rebuild.
# WHY : Alternatives Considered: a BuildKit cache mount for ~/.m2, which would
#       make the warm-up unnecessary by persisting the local repository between
#       builds. Rejected because it needs a `# syntax=` directive and a builder
#       that supports cache mounts, whereas the gating workflow invokes plain
#       `docker build` over each Dockerfile; the image would then build or fail
#       depending on which builder a runner happened to select. Ordinary layer
#       caching works on every builder, which is the property that matters here.
COPY services/pom.xml ./pom.xml
COPY services/common-lib/pom.xml ./common-lib/pom.xml
COPY services/card-service/pom.xml ./card-service/pom.xml

# WHY : Assumptions: this is the instruction that forces the build context to be
#       the repository root, and the mechanism is worth naming exactly because a
#       narrower context fails in a way that reads like an unrelated problem. The
#       shared ruleset declares its suppression filter at
#       config/checkstyle/checkstyle.xml:257-260 with optional="false", which is
#       FAIL-CLOSED: if suppressions.xml cannot be resolved Checkstyle raises an
#       error instead of continuing with no suppressions in force. That ruleset is
#       driven by the checkstyle-documentation-gate execution in
#       services/pom.xml:849-850, bound to the `validate` phase with
#       failOnViolation true and violationSeverity warning at :939-940. Because
#       validate precedes package, an unreachable ruleset stops the build before a
#       single class is compiled.
# WHY : Assumptions: the destination is absolute where the three POM paths above
#       are relative, and the asymmetry is required rather than untidy. config/ is
#       a SIBLING of services/ in the repository, and the parent POM locates the
#       ruleset through profiles that test ../config/checkstyle from the
#       aggregator (services/pom.xml:1247) and ../../config/checkstyle from each
#       module (:1267). Mirroring that relationship under /build satisfies both at
#       once; flattening it into the reactor directory would satisfy neither.
# WHY : Alternatives Considered: a narrower services/ build context, which would
#       copy less and read more naturally given where this file lives. It does not
#       actually work: the configLocation the gate resolves still has to reach
#       config/checkstyle, which sits outside services/, so the narrower context
#       merely moves the same failure earlier. Passing -Dcheckstyle.skip to make
#       it work is PROHIBITED here and anywhere else in this file; the correct
#       resolution is the wider context, which is why it is the documented one.
# WHY : Alternatives Considered: vendoring a copy of the ruleset into this module,
#       which would make a services/ context viable. Rejected because the ruleset
#       is deliberately single-sourced under config/checkstyle for all nine
#       modules, and a per-module copy is a second place to edit that drifts
#       silently: this module would then lint under a different ruleset than its
#       siblings while every build still reported green.
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory an aggregator declares
#       before -pl can select from it, so the copied parent must list only the two
#       modules whose directories exist in this stage. The source parent POM is
#       never modified; only the copy inside this layer is trimmed.
# WHY : Assumptions: exactly two module entries must survive (common-lib and
#       card-service) and at least one must be dropped. The transformation ASSERTS
#       both counts, so a renamed or already-trimmed module list fails here with
#       the count that was wrong instead of emitting a plausible-looking POM whose
#       breakage surfaces later as an obscure -pl resolution error.
# WHY : Trade-offs: tracking XML comment state costs several lines over a
#       single-line filter. It is paid because a comment mentioning a module - the
#       very comment a maintainer would add to explain an absence - would
#       otherwise be deleted as though it were a module entry, silently.
RUN awk ' \
    in_comment { print; if ($0 ~ /-->/) { in_comment = 0 } next } \
    /<!--/ && $0 !~ /-->/ { print; in_comment = 1; next } \
    /<!--/ { print; next } \
    /<modules>/ { in_modules = 1; print; next } \
    /<\/modules>/ { in_modules = 0; print; next } \
    in_modules && /<module>/ { \
      if ($0 ~ /<module>common-lib<\/module>/ \
          || $0 ~ /<module>card-service<\/module>/) { kept++; print } \
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
    && mvn -B -ntp -f pom.xml -pl common-lib,card-service -am \
       dependency:go-offline

# WHY : Trade-offs: the sources are copied AFTER the descriptor and ruleset layer
#       and after the dependency warm-up, which is the entire reason the copies are
#       split into two groups rather than done as one. A one-line Java edit
#       invalidates this layer and everything below it while leaving the resolved
#       dependency layer above it intact. Reversing the order would be shorter to
#       read and would re-resolve the whole dependency tree on every source edit.
COPY services/common-lib/src ./common-lib/src
COPY services/card-service/src ./card-service/src

# WHY : Assumptions: -am ("also make") is what builds the common-lib sibling from
#       source in this same reactor invocation. That sibling is never published to
#       a repository, so without -am this stage would try to resolve
#       com.carddemo:common-lib from ~/.m2, find nothing, and fail; with it, a
#       clean checkout is sufficient to build this image and no pre-install step
#       is required of the caller.
# WHY : Trade-offs: tests are skipped while packaging because the suite gate is
#       the repository-root reactor build `mvn -f services/pom.xml clean verify`,
#       whose Surefire and Failsafe executions cover all nine modules. This stage
#       sees only common-lib and card-service, so running tests here would report a
#       narrower result under a name that reads like the full one. The module's own
#       CardRepositoryIT could not run here in any case: it starts a real database
#       through Testcontainers, and there is no container runtime inside this build.
# WHY : Assumptions: -DskipTests skips TEST EXECUTION only. It does NOT skip the
#       documentation gate, and the distinction matters because a reader may assume
#       otherwise: Checkstyle stays bound to `validate`, validate still precedes
#       package, and a missing or incomplete Javadoc in either module compiled here
#       still fails this instruction. It is the one gate this image build keeps,
#       because a missing Javadoc is a defect in the sources being compiled here
#       rather than a statement about behavior. -Dcheckstyle.skip is prohibited.
# WHY : Assumptions: -DskipTests is used rather than -Dmaven.test.skip, which would
#       also skip test COMPILATION. That distinction is load-bearing here: this
#       module consumes common-lib's test-jar (the shared layering rules), and that
#       artifact is assembled from compiled test classes, so skipping test
#       compilation would leave the reactor unable to attach it.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,card-service -am \
    clean package -DskipTests

# WHY : Assumptions: the publisher provides no Alpine variant of this Corretto
#       image; `21-alpine` does not resolve and would fail the build. 21.0.12 is
#       the highest published 21.x tag and headless is selected because an HTTP
#       service needs no AWT or graphics stack.
# WHY : Refactoring Rationale: the reference is digest-pinned rather than left on
#       the bare tag because this tag has already moved at least once - the
#       registry index for `21.0.12-al2023-headless` and a copy cached earlier on
#       a build host are two different manifest lists that both still resolve. A
#       patch-level tag reading like an exact version is the easiest kind of
#       mutable reference to mistake for an immutable one, so two builds of one
#       commit could otherwise ship two different runtimes with nothing in the
#       build log to say so.
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless@sha256:75f1cdabc3d8758f5499bf67046991bc7797e37156c7445f0048eb6d17c90dcf AS runtime

# WHY : Alternatives Considered: installing shadow-utils solely for useradd and
#       groupadd was rejected because this minimal image omits those tools and
#       their package payload adds no runtime capability. Plain passwd and group
#       records give the same fixed identity with no extra package or repository.
# WHY : Assumptions: the ECS task definition does not drop privileges, so the
#       image must guarantee UID/GID 10001 itself. The process writes only below
#       its owned working directory and the platform-provided /tmp.
RUN printf '%s\n' 'carddemo:x:10001:' >> /etc/group \
    && printf '%s\n' \
       'carddemo:!:10001:10001:CardDemo card:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: the owned application directory is the working directory so
#       any relative temporary output stays inside the only application path the
#       fixed identity may modify.
WORKDIR /app

# WHY : Assumptions: card-service.jar is the artifact this module's POM
#       produces and the Boot repackage goal makes it self-contained, so copying
#       that one file excludes sources, build caches, reports and every reactor
#       target directory from the shipped image.
# WHY : Assumptions: this path is deliberately NOT the sibling convention. This
#       module's POM sets <finalName>card-service</finalName>, so its artifact is
#       card-service.jar and not the card-service-1.0.0-SNAPSHOT.jar that every
#       other service produces. Copying the sibling name would fail at this, the
#       LAST instruction of the build, after the whole reactor had already
#       compiled - the most expensive point at which to discover a wrong path -
#       and the error would read as a missing artifact rather than a wrong name.
COPY --from=build --chown=10001:10001 \
    /build/services/card-service/target/card-service.jar \
    /app/app.jar

# WHY : Assumptions: the destination is the literal path application.yml resolves
#       sslrootcert to and both environment roots pass as
#       CARDDEMO_DB_SSL_ROOT_CERT, so the three statements are one contract read
#       from three places rather than three independent settings that happen to
#       agree. A gate in .github/workflows/services-ci.yml asserts that agreement
#       and asserts this file is present and parseable in the built image, because
#       the failure it prevents is invisible until a task tries to connect.
# WHY : Assumptions: /etc/ssl/certs is a SYMLINK in this base --
#       /etc/pki/ca-trust/extracted/pem/directory-hash -- so the file actually
#       lands under /etc/pki and is reached through the link at run time. Verified
#       by building against the pinned digest and reading the file back through
#       both paths. It is worth stating because a reader assumes a real directory,
#       and because a copy into a symlinked destination is the kind of thing that
#       silently becomes a regular file called "certs" under a different builder.
# WHY : Trade-offs: no --chown here, unlike the jar above. The anchor is
#       root-owned and world-readable (0644), which is what a trust anchor should
#       be: the application user must read it and no process in the task should be
#       able to replace it. Verified readable as UID 10001 against the pinned
#       digest. Placing it inside the hashed trust directory does NOT add it to the
#       default OpenSSL trust store -- that lookup uses hash-named links -- so the
#       bundle is used only where it is named explicitly.
COPY --from=build /tmp/carddemo-rds-ca-bundle.pem \
    /etc/ssl/certs/carddemo-rds-ca-bundle.pem

# WHY : Assumptions: 8080 carries BOTH the business REST surface and the actuator
#       health group, over TLS, because the base profile enables server.ssl and
#       declares one connector. A second port was considered and is not available
#       without a management port, which would need its own certificate.
# WHY : Assumptions: 8080 is not a free choice. The security-group design admits
#       load-balancer-to-application traffic on 8080 and on no other port, and the
#       load balancer's target group polls the actuator health path there, so the
#       listener has to be on that port for a task ever to turn healthy. The three
#       artifacts move together or not at all.
# WHY : Assumptions: this instruction is METADATA ONLY and enforces nothing at run
#       time. It documents the contract and feeds tooling that reads image
#       metadata; it neither opens the port nor binds it. The actual bind comes
#       from the server port property, which is why SERVER_PORT is listed in the
#       header as an override that has to be made in all three places at once.
EXPOSE 8080

# WHY : Assumptions: the heap ceiling is declared as a PERCENTAGE of the memory
#       the container is actually given, so it scales with whatever the task
#       definition allocates and never has to be edited alongside it. Java 21 is
#       container-aware and reads the cgroup limit, but its default share of that
#       limit is tuned for a host running many processes, not for a task whose
#       entire allocation belongs to one JVM. Measured inside this exact runtime
#       digest: MaxRAMPercentage defaults to 25.0, so under a 1 GiB limit the heap
#       ceiling is 256 MiB and three quarters of the task's paid-for memory is
#       unavailable to it; at 50.0 the same limit yields 512 MiB.
# WHY : Refactoring Rationale: the ratio was 75.0 and is now 50.0, because 75.0
#       was derived from an assumption this task does not satisfy -- that the whole
#       allocation belongs to one JVM. It does not: infra/modules/ecs-service adds
#       an ESSENTIAL aws-otel-collector sidecar to every workload, and neither
#       container carries a container-level hard memory limit, so the JVM's cgroup
#       view is the WHOLE task allocation and 75.0 sized the heap as though the
#       sidecar were not there. The budget that replaces the assumption, at the
#       1024 MiB dev allocation: 192 MiB for the collector (its memory_limiter is
#       configured at limit_mib 128 with spike_limit_mib 32, plus Go runtime
#       overhead), about 250 MiB of JVM non-heap that MaxRAMPercentage does not
#       govern at all -- metaspace, code cache, thread stacks, GC structures and
#       direct buffers -- and the heap ceiling on top. At 75.0 that totals roughly
#       1210 MiB against a 1024 MiB task and the task is OOM-killed under load; at
#       50.0 it totals roughly 954 MiB and fits. The same ratio against the 2048 MiB
#       prod allocation yields a 1024 MiB heap and about 580 MiB of headroom.
# WHY : Alternatives Considered: keeping 75.0 and giving each container an explicit
#       hard `memory` limit in the task definition instead -- the collector at its
#       reservation and the application at the remainder -- so that the JVM's cgroup
#       limit excluded the sidecar. Rejected for two reasons measured from the
#       module: a hard limit on an ESSENTIAL container turns a collector spike into
#       a killed TASK rather than degraded telemetry, and even with the sidecar
#       excluded, 75.0 of the remaining 832 MiB plus 250 MiB of non-heap still
#       exceeds that container's own limit. The ratio had to come down either way,
#       so the change that fixes it is the ratio alone.
# WHY : Refactoring Rationale: an earlier revision of this file rejected "-Xmx or
#       -XX container flags" as a single category, on the ground that a hard-coded
#       ceiling would silently override the task definition's memory and have to be
#       edited in two places. That reasoning is sound for -Xmx and does not
#       transfer to MaxRAMPercentage, which states a ratio rather than a size and
#       therefore tracks the task definition instead of contradicting it. Treating
#       the absolute and the relative form as one category is what left the heap on
#       a default nobody had chosen, so the two are separated here: -Xmx stays
#       rejected for the original reason, and the percentage is set explicitly.
# WHY : Trade-offs: this is a DEFAULT, not a floor. The entry point expands
#       ${JAVA_OPTS} unquoted and word-splits it
#       (config/docker/generate-listener-material.sh:189), so a task definition
#       setting JAVA_OPTS replaces this value wholesale rather than adding to it,
#       and a deployment that needs another flag must restate the percentage
#       alongside it. That is accepted because the alternative, merging a
#       container-set value with an image-set one, needs a wrapper that parses JVM
#       arguments, and a wrapper that silently reorders or drops a flag is a worse
#       failure than a variable a reader can see being replaced.
# WHY : Alternatives Considered: JAVA_TOOL_OPTIONS, which the JVM reads with no
#       cooperation from the entry point at all. Rejected because EVERY JVM started
#       in this image would inherit it, including a diagnostic `keytool` or `java
#       -version` invocation, each of which would then print a "Picked up
#       JAVA_TOOL_OPTIONS" line onto its output. JAVA_OPTS is read only by the one
#       process this is meant to configure.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50.0"

# WHY : Trade-offs: SPRING_PROFILES_ACTIVE is deliberately NOT set here, and its
#       absence is the decision rather than an omission. One built image is meant
#       to run unchanged in every environment, and the ECS task definition selects
#       the profile at launch. Baking one in would require a separate image per
#       environment and would make the image's behavior depend on which environment
#       happened to build it, so the artifact could no longer be promoted between
#       them. Accepted cost: the image cannot be run correctly with `docker run`
#       and no environment supplied, which is the right bias because that failure
#       is immediate and legible whereas a wrong baked-in profile is silent.
# WHY : Assumptions: the same reasoning covers every other deployment-specific
#       value, and none of them is present in this image either: no datasource URL,
#       no database or Flyway credential, no JWT issuer URI, no expected client id,
#       no key identifier, no signing key and no region. They arrive at run time
#       from Parameter Store and Secrets Manager through the task definition, which
#       is what keeps this file free of secrets by construction rather than by
#       review.


# WHY : Refactoring Rationale: the listener key pair and certificate are minted
#       by THIS TASK at startup rather than injected from Secrets Manager. They
#       used to be one shared pair, generated by a managed `tls_private_key` in
#       each environment root, persisted in Terraform state and handed to every
#       online task -- so a state reader held the server private key of all eight
#       services. The script below draws a fresh 2048-bit RSA key and a
#       self-signed leaf into a PKCS#12 keystore on the task's own encrypted
#       ephemeral volume, which is destroyed with the task, and its own header
#       records the three alternatives measured and rejected before this one.
# WHY : Assumptions: the script needs no package. /usr/bin/keytool, /usr/bin/tr,
#       /usr/bin/head and a readable /dev/urandom were each verified inside this
#       exact runtime digest, which is why the hardening below adds no layer of
#       dependencies to carry a certificate generator.
# WHY : Trade-offs: the mode is set by a separate RUN rather than by `COPY
#       --chmod`. `--chmod` needs BuildKit, and the gating workflow invokes plain
#       `docker build` over each Dockerfile, so relying on it would make image
#       builds depend on which builder the runner happens to select. The cost is
#       one extra layer holding one mode change.
COPY config/docker/generate-listener-material.sh /usr/local/bin/carddemo-entrypoint.sh
RUN chmod 0555 /usr/local/bin/carddemo-entrypoint.sh

# WHY : Assumptions: the named account resolves to the 10001:10001 identity
#       created above; naming it keeps the non-root intent visible in image
#       metadata while the numeric records keep behavior deterministic.
USER carddemo:carddemo

# WHY : Assumptions: nothing in this image widens what a response may contain.
#       The card verification value is never serialised and a primary account
#       number is masked to its last four digits outside the administrative
#       detail endpoint; both are decided in the mapper and the shared error
#       handler, so no image-level setting can relax them. Recorded here
#       because this is the context whose payloads carry those fields.
# WHY : Assumptions: the pinned headless runtime contains /usr/bin/curl - verified
#       inside this exact digest, which also confirms wget is absent - so the probe
#       needs no package installation and fails explicitly on a non-success status.
# WHY : Assumptions: the probe speaks HTTPS, not HTTP. server.ssl.enabled is true
#       in the base profile, so the connector is TLS and a plaintext request would
#       fail the check on every healthy task - a probe that always fails is worse
#       than none, because it makes a working deployment look broken.
# WHY : Trade-offs: --insecure is accepted for this one request, and the reason is
#       the trust ANCHOR rather than the name. config/docker/
#       generate-listener-material.sh mints the leaf with
#       `SAN=dns:<cn>,dns:localhost,ip:127.0.0.1`, so the loopback address this
#       probe dials IS carried by the certificate; what cannot succeed is
#       anchoring, because the leaf is self-signed and no store in this image holds
#       its issuer. Measured against the pinned digests rather than assumed: the
#       identical request without --insecure exits 60 reporting "SSL certificate
#       OpenSSL verify result: self-signed certificate (18)", and no
#       subject-name error appears at all.
# WHY : Refactoring Rationale: this note used to say the subject "names the
#       service, never 127.0.0.1", which the generator contradicts on its own line
#       164 and which sent a reader looking for a naming problem that does not
#       exist. The distinction matters because the two causes have different fixes:
#       a name mismatch would be corrected in the certificate, and an unanchored
#       leaf can only be corrected by trusting it explicitly or by waiving
#       verification as this probe does.
# WHY : Alternatives Considered: exporting the minted leaf at start-up and
#       verifying with --cacert, which was MEASURED to work -- with the exported PEM
#       the identical loopback request succeeds. Rejected because CMD is a static
#       exec array, so the PEM path would be a literal here while the directory
#       holding it is CARDDEMO_SERVER_TLS_DIR, an overridable variable; a
#       deployment that moved that directory would then fail the probe on a healthy
#       task, which is the outcome this file already argues is worse than no probe.
#       What is given up by waiving verification is identity checking on a call
#       that never leaves this container's own network namespace, where there is no
#       second party to impersonate; what is kept is that the ENCRYPTED listener is
#       the one proven to answer.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["/usr/bin/curl", "--fail", "--silent", "--show-error", "--insecure", \
         "--max-time", "4", "--output", "/dev/null", \
         "https://127.0.0.1:8080/actuator/health"]

# WHY : Assumptions: the entry point is the generator script, and `exec java`
#       is its last line -- so the JVM still becomes PID 1 and still receives
#       SIGTERM directly from ECS, letting Spring run its shutdown hooks and the
#       task drain. That property is why the previous instruction used the exec
#       form, and `exec` inside the script is what preserves it: a script that
#       merely CALLED java would leave the shell as PID 1, and a shell does not
#       forward the signal, so the task would be killed on timeout instead.
# WHY : Assumptions: the script takes no argument of its own and forwards "$@",
#       so `docker run <image> --spring.profiles.active=prod` and an ECS
#       container `command` override both still reach the JVM unchanged.
# WHY : Alternatives Considered: keeping the exec-form java entry point and
#       generating the keystore in a separate ECS container that shares a volume.
#       Rejected: a sidecar or init container adds a task-definition dependency
#       and a shared writable volume between two containers for material that
#       only one of them ever reads, and it puts the key on a volume a second
#       process can open. Generating it in the same process tree keeps the key
#       reachable only by the JVM's own user.
# WHY : Alternatives Considered: passing JVM flags as literal arguments on this
#       instruction rather than through the JAVA_OPTS variable set above. Rejected
#       because the entry point is a script that ends in `exec java ${JAVA_OPTS:-}
#       -jar /app/app.jar "$@"`, so an argument placed here would arrive AFTER
#       -jar and be handed to the application as a program argument instead of
#       being interpreted by the JVM. The variable is the only position in this
#       arrangement where a JVM flag is read as one. -Xmx remains rejected in
#       either position, for the reason recorded on that ENV instruction.
ENTRYPOINT ["/usr/local/bin/carddemo-entrypoint.sh"]
