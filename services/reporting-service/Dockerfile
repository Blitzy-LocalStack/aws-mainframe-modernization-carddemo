# =============================================================================
# services/reporting-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo REPORTING AND STATEMENT
#   bounded context. Four reference programs are its behavioural oracle; they are
#   read and never modified, and the uppercase .CBL spellings below are the
#   on-disk spellings, so a lowercase citation is a dead reference:
#     app/cbl/CORPT00C.cbl  the online report request, CICS transaction CR00.
#     app/cbl/CBTRN03C.cbl  the transaction detail report writer, whose record is
#                           133 columns wide (FD-REPTFILE-REC at L85).
#     app/cbl/CBSTM03A.CBL  the statement generator, emitting TWO widths --
#                           80-byte plain text (FD-STMTFILE-REC at L45) and
#                           100-byte markup (FD-HTMLFILE-REC at L47).
#     app/cbl/CBSTM03B.CBL  its dynamically called data-access subprogram.
#
# What this image replaces:
#   app/csd/CARDDEMO.CSD L489 defines LIBRARY(CARDDLIB) and L491 gives it
#   DSNAME01(AWS.M2.CARDDEMO.LOADLIB) -- the load library the CICS region loaded
#   these programs from. This image is that deployment unit's replacement: the
#   deployment workflow pushes it to Amazon ECR under a short-lived federated
#   role, and ECS Fargate runs it behind an internal load balancer. Nothing here
#   loads a module from a library at run time, so the mechanism is replaced
#   rather than emulated. The ad-hoc submission path in the same resource --
#   TDQUEUE(JOBS) at L499, DDNAME(INREADER) at L501 and RECORDSIZE(80) at L502 --
#   is likewise replaced, by a state-machine execution rather than by job text
#   written to an internal reader.
#
# Stages:
#   build    resolves the reactor's dependency graph, then compiles the
#            unpublished common-lib sibling and this module from source in one
#            invocation, producing the repackaged bootable jar under
#            reporting-service/target.
#   runtime  carries that one jar plus the shared listener-material entry point,
#            and nothing else from the build stage: no Maven, no compiler, no
#            resolved dependency cache and no sources.
#
# Artifact:
#   One bootable Spring Boot jar at /app/app.jar, owned and executed by the fixed
#   non-root identity 10001:10001, listening on 8080 over TLS, and probed by the
#   single HEALTHCHECK below against the Actuator health endpoint.
#
# Gates:
#   The Checkstyle documentation gate runs INSIDE this build, not merely in the
#   pipeline. services/pom.xml binds its checkstyle-documentation-gate execution
#   to Maven's validate phase with failOnViolation true and violationSeverity
#   warning, and validate precedes package, so an absent or incomplete Javadoc in
#   either module compiled here fails `docker build` rather than surfacing later.
#
# Parameters:
#   This image declares no build argument. The entry point takes no argument of
#   its own and forwards "$@", which is load-bearing here because ONE image
#   serves TWO invocation modes:
#     (no arguments)  ReportingApplication starts the HTTPS listener and stays
#                     up. This is the ECS service mode.
#     --job=<name>    ReportingTaskRunner takes the run instead, builds a context
#                     with no web application at all, and exits with a status.
#                     The accepted names are generate-statements, generate-reports
#                     and generate-report, each with its own required date or
#                     report-type options. This is the mode the batch statement
#                     and report states invoke through container overrides.
#   Everything else arrives as environment variables that the ECS task definition
#   supplies from AWS Systems Manager Parameter Store and AWS Secrets Manager.
#   The placeholders in application.yml that declare NO fallback, so the listener
#   cannot start without them, are the datasource triple, the resource-server
#   issuer URI, the expected client id, the state-machine identifier, the
#   artifact-store bucket, the artifact signing key, and the listener keystore
#   credential -- the last of which the entry point mints for this one task
#   rather than reading from the deployment.
#
# Return values:
#   0 (integer)         the JVM exited after a requested shutdown, or the
#                       selected --job= task completed.
#   non-zero (integer)  the context failed to start, the task failed or its
#                       command line was malformed, or the JVM terminated
#                       abnormally. ECS reads that status to decide whether to
#                       replace the task, and the batch state machine reads it to
#                       decide whether its state failed.
#
# Exceptions or errors:
#   An unset no-fallback variable, an unreachable datasource, an unreachable token
#   issuer or an absent reporting view stops the start-up or the first query, so
#   the task never reports healthy. This module owns NO schema and runs NO
#   migration, so it cannot create what it reads: an absent view or grant is a
#   defect against data-migration/sql/V0__schemas_and_roles.sql and
#   data-migration/sql/V1__reporting_views.sql, never something for this image to
#   repair.
#
# What this file must never contain, and does not:
#   no credential, no connection string, no endpoint host, no ARN, no bucket
#   name, no signing key, no user-pool identifier, no registry account and no
#   environment name. Every one of those is a deployment value, and the two
#   verifications that keep this true are the absence of any ARG or ENV carrying
#   one, and the fact that every COPY below names one narrow path -- three module
#   POMs, the Checkstyle ruleset directory, two source trees and one start-up
#   program -- so no file outside those seven can reach a layer.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md. Its Dockerfile section (L828-L842)
#   requires a header PLUS a justification on the base-image pin and on layer
#   ordering, and its "four labels, and their one permitted written form" section
#   (L230-L253) fixes every label as an unmarked token followed by a colon, so no
#   label below carries emphasis markup.
#
# Build:
#   Run from the REPOSITORY ROOT, so that the trailing dot is the repository root
#   and not this module's directory:
#     docker build -f services/reporting-service/Dockerfile -t carddemo/reporting-service:local .
#   The gating workflow spells the same two flags long-form and tags per commit:
#     docker build --file services/reporting-service/Dockerfile \
#       --tag carddemo-reporting-service:validation .
#
# WHY (non-obvious design decisions, each expanded at the instruction it governs):
#   - Assumptions: the build context is the repository root, not services/ and
#     not this directory. The image compiles the unpublished common-lib sibling
#     from source, and the inherited documentation gate reads config/checkstyle --
#     both of which sit OUTSIDE this module's tree.
#   - Trade-offs: two stages rather than one, so Maven, the JDK compiler and the
#     whole resolved dependency cache stay out of the shipped image.
#   - Assumptions: nothing here publishes. The only registry hostname in this file
#     belongs to the public base image, and the example tags above name no account
#     and no region, so no line can be mistaken for a publishable reference.
# =============================================================================

# WHY : Assumptions: services/pom.xml sets maven.compiler.release to 21 and its
#       enforcer rule requires that Java, so the build JDK must satisfy 21 and
#       the runtime must be able to load what it emits. Matching the VENDOR as
#       well is the part that is not forced by the POM and is chosen anyway: a
#       vendor difference between the JDK that compiles and the JVM that runs
#       shows up as a garbage-collector default or a security-provider ordering,
#       which is the class of difference that appears under load rather than at
#       build time.
# WHY : Alternatives Considered: maven:3-eclipse-temurin-21 was evaluated and
#       rejected. It would compile with one vendor's JDK what the other vendor's
#       runtime executes, and matching the two costs nothing here because both
#       vendors publish a Maven image; the generic tag would additionally float
#       its Maven minor version.
# WHY : Assumptions: the digest is the MULTI-PLATFORM INDEX digest, read with
#       `docker buildx imagetools inspect`, not the platform manifest digest that
#       `docker images` reports, so an amd64 and an arm64 builder both resolve it.
#       The tag is retained beside it so a reader sees the version without a
#       registry round trip.
FROM maven:3.9.16-amazoncorretto-21-al2023@sha256:a7f84ee223e9b6a1ba462667cdb98e282adf79d02fbd1ad3091ad0bb2feff1d7 AS build

# WHY : Assumptions: services/pom.xml locates the Checkstyle ruleset through two
#       mutually exclusive profiles -- checkstyle-config-beside-reactor-root tests
#       ${basedir}/../config/checkstyle from the aggregator, and
#       checkstyle-config-one-level-above-reactor-root tests
#       ${basedir}/../../config/checkstyle from each module. Only a layout that
#       keeps config/ a SIBLING of services/ satisfies both, and flattening would
#       satisfy neither, so the gate would fail while looking for a file that had
#       in fact been copied in.
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

# WHY : Trade-offs: this ordering is the mandatory layer-ordering justification
#       for this file. Copying the POMs first and the sources later puts the
#       dependency warm-up BETWEEN the two groups, so editing one line of Java
#       invalidates the source layer and everything after it while leaving the
#       resolved dependency layer cached. The accepted cost is the four COPY
#       instructions that precede the warm-up, and one more layer than a single
#       copy of the whole reactor would have produced -- and that single copy is
#       exactly the rejected alternative, because it re-resolves the entire Spring
#       Boot 4.1.0, AWS SDK 2.49.3 and Testcontainers 2.0.5 graph on every source
#       edit.
# WHY : Trade-offs: a blanket copy of the context is refused outright. This
#       module ships no .dockerignore of its own and must not, so the only
#       exclusions in force are the repository-root ones, which drop build output
#       and local state but deliberately do NOT drop app/**, tests/** or
#       samples/** -- the COBOL baseline, the parity suite and the mainframe build
#       samples. Naming each path explicitly is what keeps those trees out of every
#       layer; the cost is that adding a sibling dependency means editing this
#       file, which is the visible edit it should be.
COPY services/pom.xml ./pom.xml
COPY services/common-lib/pom.xml ./common-lib/pom.xml
COPY services/reporting-service/pom.xml ./reporting-service/pom.xml

# WHY : Assumptions: the asymmetry is required rather than untidy. config/ is a
#       SIBLING of services/ in the repository, and the two profiles named at
#       WORKDIR above resolve the ruleset relative to the aggregator and to each
#       module. Mirroring that sibling relationship under /build satisfies both
#       readings; copying the ruleset into the reactor directory would satisfy
#       neither. Both files in the directory are needed, not just the ruleset:
#       checkstyle.xml wires suppressions.xml through a SuppressionFilter whose
#       optional attribute is false, so an absent companion fails the gate.
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory an aggregator declares
#       before -pl can select from it, so a copied parent still listing all nine
#       modules fails on the seven directories this stage never copies. Only the
#       COPY inside this layer is rewritten; services/pom.xml itself is never
#       modified, here or anywhere.
# WHY : Assumptions: exactly two module entries must survive -- common-lib and
#       reporting-service -- and at least one must be dropped. Both counts are
#       ASSERTED, so a renamed or already-trimmed module list fails here with the
#       count that was wrong, instead of emitting a plausible-looking POM whose
#       breakage surfaces later as an obscure -pl resolution error.
# WHY : Trade-offs: several extra lines over a one-line filter, paid because a
#       comment that MENTIONS a module -- the very comment a maintainer would add
#       to explain an absence -- would otherwise be deleted as though it were a
#       module entry, and deleted silently.
# WHY : Alternatives Considered: copying all nine module POMs so the copied parent
#       could stay verbatim. Rejected on two grounds: it would carry seven POMs for
#       modules this stage never builds, and a module added to the reactor later
#       would still break this build -- at Maven's own "child module does not
#       exist" complaint rather than at the trim's named assertion, which is the
#       less legible of the two failures.
RUN awk ' \
    in_comment { print; if ($0 ~ /-->/) { in_comment = 0 } next } \
    /<!--/ && $0 !~ /-->/ { print; in_comment = 1; next } \
    /<!--/ { print; next } \
    /<modules>/ { in_modules = 1; print; next } \
    /<\/modules>/ { in_modules = 0; print; next } \
    in_modules && /<module>/ { \
      if ($0 ~ /<module>common-lib<\/module>/ \
          || $0 ~ /<module>reporting-service<\/module>/) { kept++; print } \
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
    && mvn -B -ntp -f pom.xml -pl common-lib,reporting-service -am \
       dependency:go-offline

# WHY : Trade-offs: this is the second half of the split declared above the POM
#       copies, and its POSITION is the whole mechanism. Everything from here down
#       is invalidated by any source edit, which is why the warm-up sits above it
#       rather than below: appending a line to one Java file leaves the warm-up
#       layer cached and re-runs only the packaging step. The residue accepted is
#       that dependency:go-offline is a best effort rather than a proof -- in a
#       multi-module reactor it can miss a plugin dependency a later phase asks
#       for -- and the flag on the next instruction is what exposes that instead
#       of hiding it.
COPY services/common-lib/src ./common-lib/src
COPY services/reporting-service/src ./reporting-service/src

# WHY : Assumptions: -am is a requirement here, not an optimisation. common-lib is
#       published to no registry, so no artifact exists for Maven to resolve, and
#       naming both modules with -pl still needs -am to bring in the parent POM
#       they inherit their plugin and dependency management from.
# WHY : Trade-offs: -DskipTests, and deliberately NOT -Dmaven.test.skip=true. The
#       suite gate is the repository-root reactor build
#       `mvn -f services/pom.xml clean verify`, whose Surefire and Failsafe
#       executions cover all nine modules; this stage sees two of them, so tests
#       run here would report a narrower result under a name that reads like the
#       full one. This stage also could not run them all: the module's repository
#       integration tests pin the persistence contract against a real PostgreSQL
#       engine through Testcontainers, which needs a Docker daemon an image build
#       does not have. The flag chosen still COMPILES test sources, so a test that
#       no longer compiles still fails this build; -Dmaven.test.skip=true was
#       rejected precisely because it would skip that compilation and hide the
#       break.
# WHY : Assumptions: -DskipTests suppresses test execution only -- it does not
#       skip Checkstyle. The gate stays bound to validate, which runs before
#       package, so it fires in this stage. It is the one gate this image build
#       keeps, because a missing Javadoc is a defect in the very sources being
#       compiled here rather than a statement about behaviour. Nothing in this
#       file may be added to get past it: not -Dcheckstyle.skip, not a
#       severity downgrade, not a return-code tolerance, and not a second
#       configLocation.
# WHY : Trade-offs: --offline is what turns that layer from an optimisation into a
#       verified one. Without it, anything the warm-up missed would be fetched
#       here quietly and the dependency layer would look effective while every
#       rebuild still reached the network; with it, a miss fails this step by
#       name. Measured on this reactor, the packaging step resolves entirely from
#       the local repository and downloads nothing. What is given up is tolerance:
#       a dependency added to either module's POM must be reachable by the warm-up
#       -- the intended loss, because the alternative hides the very thing being
#       cached.
# WHY : Trade-offs: -B and -ntp give up the interactive, redrawn progress display
#       a developer sees locally, in exchange for a log whose lines are stable
#       enough to grep for the gate's own verdict in a CI artifact.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,reporting-service -am \
    clean package -DskipTests

# WHY : Assumptions: the publisher provides NO Alpine variant of this Corretto
#       image. The repository publishes -al2 and -al2023 only, each with the
#       headful, headless, generic and jdk suffixes, and 21.0.12 is the highest
#       published 21.x. So the reflex reach for a smaller base, `21-alpine`, is
#       not a different or larger image -- it is a tag that does not resolve, and
#       substituting it fails this build outright at the pull. An earlier draft of
#       the migration plan named exactly that tag, which is why the fact is
#       recorded here beside the line it would be edited on.
# WHY : Trade-offs: headless omits the AWT and graphics stack that a report and
#       statement writer never loads; generic keeps it, and jdk would additionally
#       ship javac and the whole compiler toolchain into a production runtime,
#       which is the very thing the build stage exists to keep out. What is given
#       up is the ability to render an image or open a font inside the container.
#       This context writes 133-column report text, 80-byte statement text and
#       100-byte markup -- all character data -- so it needs neither.
# WHY : Refactoring Rationale: the reference is digest-pinned rather than left on
#       the bare tag because this tag has already moved at least once -- the
#       registry index for `21.0.12-al2023-headless` and a copy cached earlier on
#       a build host are two different manifest lists that both still resolve. A
#       patch-level tag reading like an exact version is the easiest kind of
#       mutable reference to mistake for an immutable one, so two builds of one
#       commit could otherwise ship two different runtimes with nothing in the
#       build log to say so. A floating `21`, `latest` or `stable` is refused for
#       the same reason, only more so.
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless@sha256:75f1cdabc3d8758f5499bf67046991bc7797e37156c7445f0048eb6d17c90dcf AS runtime

# WHY : Alternatives Considered: running as root was rejected outright. Nothing
#       this image does at run time needs a privileged operation -- the JVM reads
#       one jar, writes a keystore under /tmp and binds an unprivileged port -- so
#       root would buy no capability while leaving a compromised process holding
#       every privilege the container has.
# WHY : Alternatives Considered: installing shadow-utils solely for useradd and
#       groupadd was rejected. Inspecting this exact pinned digest shows that none
#       of useradd, groupadd or adduser exists in it, so the tooling path would
#       mean adding a package whose payload adds no runtime capability to a process
#       that needs one fixed identity. Appending plain group and account records is
#       the accepted fallback: it yields the same fixed 10001:10001 identity with
#       no package and no repository access during the build.
# WHY : Assumptions: the ECS task definition does not drop privileges, so the
#       image must guarantee the identity itself. The process writes only below
#       this owned directory and the platform-provided /tmp, which is why no other
#       path is chowned.
RUN printf '%s\n' 'carddemo:x:10001:' >> /etc/group \
    && printf '%s\n' \
       'carddemo:!:10001:10001:CardDemo reporting:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: any relative output a library opens then lands inside the
#       only application path the fixed identity may modify, so a write that was
#       not anticipated fails on permissions rather than succeeding somewhere
#       unowned. Report and statement artifacts do not land here at all: they are
#       streamed to object storage by the module's own sink types.
WORKDIR /app

# WHY : Assumptions: the glob resolves to exactly one file, and the module POM is
#       what guarantees it. The Boot repackage goal writes the bootable jar under
#       the inherited default name and renames the plain one beside it with a
#       .jar.original suffix, which `*.jar` does not match; measured in this
#       stage, `ls -1` over the same target directory returns a single path. The
#       file services/reporting-service/pom.xml deliberately leaves finalName
#       uncustomised and records that the image build selects the artifact by a
#       plain jar glob, so the two files are one contract read from both ends.
# WHY : Alternatives Considered: naming reporting-service-<version>.jar literally.
#       It reads as the safer, more explicit choice and is the opposite: the
#       version lives in the parent POM, so a version bump would break this image
#       at the COPY, and break it by writing a path that no longer exists rather
#       than by saying a version changed.
# WHY : Refactoring Rationale: an earlier revision of this Dockerfile did name the
#       versioned jar literally, which contradicted the decision its own module POM
#       records at the finalName element -- two files stating opposite contracts,
#       with only one of them enforced by a build. The glob restores the single
#       contract the POM documents.
# WHY : Assumptions: /app/app.jar is depended on by the shared entry point, which
#       execs `java -jar /app/app.jar`; renaming here without renaming there
#       yields an image that builds and cannot start. The ownership is set during
#       the copy so no later chown layer duplicates the file.
COPY --from=build --chown=10001:10001 \
    /build/services/reporting-service/target/*.jar \
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

# WHY : Assumptions: 8080 is a contract with the infrastructure rather than a local
#       preference. infra/modules/ecs-service defaults container_port to 8080 and
#       the security-group design permits only load-balancer-to-application on it,
#       so application.yml's server.port, the target group and this declaration
#       must agree; a different port here produces a task that starts and never
#       receives traffic, which is harder to diagnose than one that fails to start.
# WHY : Trade-offs: one port carries BOTH the report and statement endpoints and
#       the Actuator health, info and Prometheus endpoints, over TLS, because the
#       base profile enables server.ssl and declares one connector. A separate
#       management port was considered and would need its own certificate and its
#       own security-group rule, and it would let the probe succeed on a connector
#       the traffic never uses -- the exact disagreement the single port prevents.
EXPOSE 8080

# WHY : Assumptions: the container memory limit comes from the Fargate task
#       definition, so a ratio tracks that definition while an absolute size would
#       have to be edited in lockstep with it. Java 21 is container-aware and reads
#       the cgroup limit, but its default share of that limit is tuned for a host
#       running many processes, not for a task whose entire allocation belongs to
#       one JVM. Measured inside this exact runtime digest, MaxRAMPercentage
#       defaults to 25.0, so a 1 GiB limit yields a 256 MiB ceiling and three
#       quarters of the task's memory is unavailable to the heap; at 50.0 the same
#       limit yields 512 MiB, and what is left is headroom for metaspace, thread
#       stacks and the direct buffers the AWS SDK clients use to stream report and
#       statement bytes.
# WHY : Refactoring Rationale: the ratio was 75.0, justified by "a task whose
#       entire allocation belongs to one JVM" -- and this task's allocation does
#       not. infra/modules/ecs-service adds an ESSENTIAL aws-otel-collector
#       sidecar to every workload, and neither container carries a container-level
#       hard memory limit, so the JVM's cgroup view is the WHOLE task allocation
#       and 75.0 sized the heap as though the sidecar were absent. This context is
#       the worst place in the estate for that error, because it is also started
#       directly by the batch state machine to render statements and reports, where
#       it streams whole result sets through the SDK's direct buffers -- the
#       non-heap region the ratio does not govern. The budget that replaces the
#       assumption, at the 1024 MiB dev allocation: 192 MiB for the collector (its
#       memory_limiter is configured at limit_mib 128 with spike_limit_mib 32, plus
#       Go runtime overhead), about 250 MiB of JVM non-heap, and the heap on top.
#       At 75.0 that is roughly 1210 MiB against a 1024 MiB task; at 50.0 it is
#       roughly 954 MiB. The same ratio against the 2048 MiB prod allocation yields
#       a 1024 MiB heap with about 580 MiB spare.
# WHY : Alternatives Considered: keeping 75.0 and giving each container an explicit
#       hard `memory` limit in the task definition so the JVM's cgroup limit
#       excluded the sidecar. Rejected on two measurements from the module: a hard
#       limit on an ESSENTIAL container turns a collector spike into a killed TASK
#       rather than degraded telemetry, and 75.0 of the remaining 832 MiB plus
#       250 MiB of non-heap would still exceed that container's own limit. The ratio
#       had to come down either way.
# WHY : Refactoring Rationale: an earlier revision of this file rejected "-Xmx or
#       -XX container flags" as a single category, on the ground that a hard-coded
#       ceiling would silently override the task definition's memory. That
#       reasoning is sound for -Xmx and does not transfer to MaxRAMPercentage,
#       which states a ratio rather than a size and therefore tracks the task
#       definition instead of contradicting it. Treating the two as one category is
#       what left the heap on an unchosen default, so they are separated here:
#       -Xmx stays rejected for the original reason, and the percentage is set.
# WHY : Alternatives Considered: JAVA_TOOL_OPTIONS, which the JVM picks up with no
#       cooperation from the entry point at all. Rejected because EVERY JVM started
#       in this image would inherit it, including the keytool invocation the entry
#       point makes and any `java -version` a diagnostic runs, each of which would
#       then print a "Picked up JAVA_TOOL_OPTIONS" line onto its own output.
#       JAVA_OPTS is read by exactly the one process this is meant to configure.
# WHY : Trade-offs: the entry point expands ${JAVA_OPTS} unquoted and word-splits
#       it (config/docker/generate-listener-material.sh:189), so a task definition
#       setting JAVA_OPTS replaces this value wholesale rather than adding to it,
#       and a deployment that needs another flag must restate the percentage
#       alongside it. That is accepted because merging a container-set value with
#       an image-set one needs a wrapper that parses JVM arguments, and a wrapper
#       that silently reorders or drops a flag is a worse failure than a variable a
#       reader can see being replaced.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50.0"

# WHY : Assumptions: the datasource URL and its two credentials, the token issuer
#       URI, the expected client id, the state-machine identifier, the
#       artifact-store bucket, the artifact signing key and the region all arrive
#       at RUN TIME from AWS Systems Manager Parameter Store and AWS Secrets
#       Manager, resolved by the Spring Cloud AWS parameter-store and
#       secrets-manager starters this module's POM declares and by the config
#       imports application.yml lists. No service hard-codes an endpoint, so none
#       of those values may appear here as an ARG default, an ENV or a copied file.
#       Publication authenticates by short-lived federated role assumption, so
#       there is no long-lived credential in existence to bake in even by mistake.
# WHY : Trade-offs: SPRING_PROFILES_ACTIVE is deliberately not set. One built image
#       is meant to run unchanged in every environment and the task definition
#       selects the profile at launch; baking one in would require an image per
#       environment and would make behaviour depend on which environment happened
#       to build it, so the artifact could no longer be promoted. Accepted cost:
#       the image cannot be run correctly by `docker run` with no environment
#       supplied -- the right bias, because that failure is immediate and legible
#       whereas a wrong baked-in profile is silent.

# WHY : Refactoring Rationale: the listener material is minted by THIS TASK at
#       startup rather than injected from a managed credential store. It used to
#       be one shared pair, generated in each environment root, persisted in
#       Terraform state and handed to every online task -- so a state reader held
#       the server private key of all eight services. The program below draws a
#       fresh 2048-bit RSA key and a self-signed leaf into a PKCS#12 keystore on
#       the task's own encrypted ephemeral volume, which is destroyed with the
#       task, and its own header records the alternatives measured before this one.
# WHY : Assumptions: /usr/bin/keytool, /usr/bin/tr, /usr/bin/head and a readable
#       /dev/urandom were each verified inside this exact runtime digest, so the
#       program needs no package and this stage installs none.
# WHY : Trade-offs: `COPY --chmod` needs BuildKit, and the gating workflow invokes
#       plain `docker build` over each Dockerfile, so relying on it would make
#       image builds depend on which builder the runner happens to select. The cost
#       is one extra layer holding one mode change.
COPY config/docker/generate-listener-material.sh /usr/local/bin/carddemo-entrypoint.sh
RUN chmod 0555 /usr/local/bin/carddemo-entrypoint.sh

# WHY : Assumptions: the NUMERIC form is chosen so the image behaves the same
#       wherever the name cannot be resolved -- under a read-only root filesystem,
#       and under admission policies that require a non-root user by inspecting the
#       numeric id rather than reading /etc/passwd. The named record written above
#       still exists, so `id` inside the container resolves it and the non-root
#       intent stays legible; nothing depends on that resolution succeeding.
# WHY : Refactoring Rationale: an earlier revision declared the account by name.
#       That reads more clearly and is strictly weaker: a policy or runtime that
#       evaluates the numeric id sees an unresolved name as "not proven non-root",
#       so the clearer spelling is the one that can fail admission.
USER 10001:10001

# WHY : Assumptions: /actuator/health has TWO independent readers, and that is an
#       infrastructure contract rather than a convenience. The module
#       infra/modules/ecs-service defaults health_check_path to /actuator/health
#       and probes it with target_protocol, whose only accepted value is HTTPS, and
#       its task definition deliberately declares NO container health check so that
#       this one is the container-level definition of healthy. Securing that
#       endpoint shut, moving its path or changing its port would break both readers
#       at once, one of them silently draining the task from the load balancer. It
#       also requires spring-boot-starter-actuator, which this module's POM
#       declares for these consumers specifically: removing that dependency breaks
#       both checks together.
# WHY : Assumptions: /usr/bin/curl is present in this exact runtime digest --
#       verified by running it there, which also confirms that wget is absent -- so
#       the probe installs no package and adds no layer. It is named by absolute
#       path because the exec form below resolves no PATH.
# WHY : Alternatives Considered: the shell form `CMD curl -f <url> || exit 1`. The
#       exec form is used instead, and `--fail` carries the same guarantee: curl
#       exits non-zero on a 4xx or 5xx, and Docker reads any non-zero exit as
#       unhealthy, so the `|| exit 1` that form needs to make its intent explicit
#       has nothing left to add here. What the exec form buys is that no shell is
#       spawned inside the container on every probe interval -- one less process,
#       and one less place for an environment-dependent expansion to change what is
#       actually requested.
# WHY : Assumptions: server.ssl.enabled is true in the base profile, so the
#       connector is TLS and a plaintext request would fail this check on every
#       healthy task -- a probe that always fails is worse than none, because it
#       makes a working deployment look broken.
# WHY : Trade-offs: the certificate is minted for this task and the reason
#       verification cannot succeed is the trust ANCHOR, not the name.
#       config/docker/generate-listener-material.sh:164 mints the leaf with
#       `SAN=dns:<cn>,dns:localhost,ip:127.0.0.1`, so the loopback address this
#       probe dials IS carried by the certificate; the leaf is self-signed and no
#       store in this image holds its issuer. Measured against the pinned digests:
#       the identical request without --insecure exits 60 reporting "SSL certificate
#       OpenSSL verify result: self-signed certificate (18)", with no subject-name
#       error.
# WHY : Refactoring Rationale: this note used to say the subject "names the service,
#       never 127.0.0.1", which the generator contradicts on its own line, and which
#       sent a reader looking for a naming defect that does not exist. The two causes
#       have different fixes, so naming the wrong one is not a harmless
#       simplification.
# WHY : Alternatives Considered: exporting the leaf at start-up and verifying with
#       --cacert, which the same measurement shows succeeds on loopback. Rejected
#       because CMD is a static exec array, so the PEM path would be a literal here
#       while the directory holding it is CARDDEMO_SERVER_TLS_DIR, an overridable
#       variable; a deployment that moved it would fail this probe on a healthy task.
#       What is given up is identity checking on a call that never leaves the
#       container's own network namespace, where there is no other party to
#       impersonate; what is kept is that the encrypted listener is the one actually
#       proven to answer.
# WHY : Trade-offs: --start-period is the one that has to be reasoned about, and it
#       is set higher here than in the sibling images. It has to cover a cold JVM
#       start, the keystore mint the entry point performs, the Parameter Store and
#       Secrets Manager config imports application.yml declares, and a first
#       datasource connection made with full certificate verification. Set below
#       that, the container is killed while it is legitimately still starting and
#       the deployment loops on a service that would have become healthy; set far
#       above it, a task that will never bind is left running longer before
#       replacement. --retries then tolerates a transient miss at three
#       consecutive failures, and --timeout stays under --interval so probes cannot
#       overlap.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD ["/usr/bin/curl", "--fail", "--silent", "--show-error", "--insecure", \
         "--max-time", "4", "--output", "/dev/null", \
         "https://127.0.0.1:8080/actuator/health"]

# WHY : Assumptions: the program's last line is `exec java ${JAVA_OPTS:-} -jar
#       /app/app.jar "$@"` (config/docker/generate-listener-material.sh:189), so
#       the JVM still becomes PID 1 and still receives SIGTERM directly from ECS,
#       letting Spring run its shutdown hooks within the graceful-shutdown budget
#       application.yml sets. `exec` is what preserves that: a program that merely
#       CALLED java would leave a shell as PID 1, and a shell does not forward the
#       signal, so the task would be killed on timeout instead.
# WHY : Alternatives Considered: ENTRYPOINT ["java", "-jar", "/app/app.jar"] with
#       no start-up program. Rejected because the listener keystore credential
#       placeholder in application.yml declares no fallback, so a context started
#       that way stops during start-up, and because the target group's protocol is
#       fixed at HTTPS -- a task with no certificate never turns healthy. The
#       property that form exists to protect, a JVM at PID 1 receiving SIGTERM, is
#       kept by the `exec` above, so nothing is traded away.
# WHY : Alternatives Considered: minting the keystore in a separate ECS container
#       that shares a volume. Rejected: a sidecar or init container adds a
#       task-definition dependency and a shared writable volume between two
#       containers for material only one of them reads, and it puts the private key
#       somewhere a second process can open it.
# WHY : Assumptions: the program takes no argument of its own and forwards "$@",
#       so a container override of `--job=generate-statements --business-date=...`
#       reaches ReportingTaskRunner and selects the non-web task context, while the
#       same image with no argument starts the listener. Dropping the forwarding
#       would silently reduce this image to one of its two modes.
# WHY : Trade-offs: this image is proven to BUILD, and its identity, health probe
#       and artifact are proven by inspecting the built image. No push to a live
#       registry, no `terraform apply` and no deployment to a live AWS account
#       happens here; each is an operator action outside this scope. The accepted
#       consequence is that a failure reachable only against real managed services
#       -- an absent parameter, a denied grant -- surfaces at deployment rather
#       than here, which is why every such value is a no-fallback placeholder that
#       stops start-up loudly instead of defaulting.
ENTRYPOINT ["/usr/local/bin/carddemo-entrypoint.sh"]
