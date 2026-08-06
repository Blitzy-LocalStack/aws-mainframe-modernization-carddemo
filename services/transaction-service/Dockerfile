# =============================================================================
# services/transaction-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo transaction ledger context:
#   the migrated form of app/cbl/COTRN00C.cbl, COTRN01C.cbl and COTRN02C.cbl together
#   with the bill-payment transaction app/cbl/COBIL00C.cbl.
#
# Parameters:
#   This image takes no command arguments. The entrypoint is the exec-form JVM
#   invocation below and the process is a long-running HTTP listener, so there is
#   no per-run token of the kind the batch image accepts.
#   Runtime configuration arrives entirely through environment variables that the
#   ECS task definition supplies. The values this service cannot start without,
#   because their placeholders declare no fallback, are:
#     - SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
#     - SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
#     - CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID
#     - CARDDEMO_SERVER_TLS_CERTIFICATE, CARDDEMO_SERVER_TLS_PRIVATE_KEY
#
# Return values:
#   0 (integer): the JVM exited after a requested shutdown.
#   non-zero (integer): the context failed to start or the JVM terminated
#     abnormally. ECS reads that status to decide whether to replace the task.
#
# Exceptions or errors:
#   An unset variable from the list above, an unreachable datasource, an
#   unreachable JWT issuer, or a Flyway checksum mismatch aborts the start before
#   the listener binds, so the task never reports healthy.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md
#
# WHAT: From the repository root, build this image with:
#       docker build --file services/transaction-service/Dockerfile \
#         --tag carddemo-transaction-service:validation .
# WHY : Assumptions: the repository root is the build context, not services/,
#       because the image compiles the unpublished common-lib sibling from source
#       and the inherited Checkstyle documentation gate reads config/checkstyle,
#       which sits OUTSIDE the services reactor. Every COPY below is therefore
#       repository-root-relative and all of them fail if the context is narrowed.
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

# WHY : Trade-offs: POMs and the documentation-gate configuration are copied
#       before any source file so that a source-only change reuses the resolved
#       dependency layer. The extra COPY instructions make this file longer, but
#       they avoid repeating Maven's dependency download, which dominates the
#       otherwise immutable cost of this build.
COPY services/pom.xml ./pom.xml
COPY services/common-lib/pom.xml ./common-lib/pom.xml
COPY services/transaction-service/pom.xml ./transaction-service/pom.xml
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory an aggregator declares
#       before -pl can select from it, so the copied parent must list only the two
#       modules whose directories exist in this stage. The source parent POM is
#       never modified; only the copy inside this layer is trimmed.
# WHY : Assumptions: exactly two module entries must survive (common-lib and
#       transaction-service) and at least one must be dropped. The transformation ASSERTS
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
          || $0 ~ /<module>transaction-service<\/module>/) { kept++; print } \
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
    && mvn -B -ntp -f pom.xml -pl common-lib,transaction-service -am \
       dependency:go-offline

COPY services/common-lib/src ./common-lib/src
COPY services/transaction-service/src ./transaction-service/src

# WHY : Trade-offs: tests are skipped while packaging because the suite gate is
#       the repository-root reactor build `mvn -f services/pom.xml clean verify`,
#       whose Surefire and Failsafe executions cover all ten modules. This stage
#       sees only common-lib and transaction-service, so running tests here would report a
#       narrower result under a name that reads like the full one.
# WHY : Assumptions: Checkstyle stays bound to `validate` and therefore still runs
#       inside this stage, before `package`, even under -DskipTests. It is the one
#       gate this image build keeps, because a missing Javadoc is a defect in the
#       sources being compiled here rather than a statement about behavior.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,transaction-service -am \
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
       'carddemo:!:10001:10001:CardDemo transaction:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: the owned application directory is the working directory so
#       any relative temporary output stays inside the only application path the
#       fixed identity may modify.
WORKDIR /app

# WHY : Assumptions: transaction-service-1.0.0-SNAPSHOT.jar is the artifact this module's POM
#       produces and the Boot repackage goal makes it self-contained, so copying
#       that one file excludes sources, build caches, reports and every reactor
#       target directory from the shipped image.
COPY --from=build --chown=10001:10001 \
    /build/services/transaction-service/target/transaction-service-1.0.0-SNAPSHOT.jar \
    /app/app.jar

# WHY : Assumptions: 8080 carries BOTH the business REST surface and the actuator
#       health group, over TLS, because the base profile enables server.ssl and
#       declares one connector. A second port was considered and is not available
#       without a management port, which would need its own certificate.
EXPOSE 8080

# WHY : Assumptions: the named account resolves to the 10001:10001 identity
#       created above; naming it keeps the non-root intent visible in image
#       metadata while the numeric records keep behavior deterministic.
USER carddemo:carddemo

# WHY : Assumptions: the image sets no locale, no timezone and no JVM number
#       format, and that omission is deliberate. Money in this context is
#       BigDecimal at scale 2 and crosses the wire as a JSON string, so no
#       formatting decision may be taken by the runtime environment; a
#       container-level locale would be exactly such a decision, and one that
#       could differ between two tasks of the same service.
# WHY : Assumptions: the pinned headless runtime contains /usr/bin/curl - verified
#       inside this exact digest, which also confirms wget is absent - so the probe
#       needs no package installation and fails explicitly on a non-success status.
# WHY : Assumptions: the probe speaks HTTPS, not HTTP. server.ssl.enabled is true
#       in the base profile, so the connector is TLS and a plaintext request would
#       fail the check on every healthy task - a probe that always fails is worse
#       than none, because it makes a working deployment look broken.
# WHY : Trade-offs: --insecure is accepted for this one request. The certificate is
#       supplied by the deployment and its subject names the service, never
#       127.0.0.1, so verification could not succeed from inside the container
#       regardless of how correct the certificate is. What is given up is identity
#       checking on a loopback call inside the container's own network namespace,
#       where there is no other party to impersonate; what is kept is that the
#       encrypted listener is the one actually proven to answer.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["/usr/bin/curl", "--fail", "--silent", "--show-error", "--insecure", \
         "--max-time", "4", "--output", "/dev/null", \
         "https://127.0.0.1:8080/actuator/health"]

# WHY : Assumptions: the exec form is used so the JVM is PID 1 and receives SIGTERM
#       directly from ECS, letting Spring run its shutdown hooks. A shell form
#       would interpose /bin/sh, which does not forward the signal, and the task
#       would be killed on timeout instead of draining.
# WHY : Alternatives Considered: adding -Xmx or -XX container flags was rejected.
#       Java 21 reads the cgroup memory limit and sizes the heap from it, so a
#       hard-coded ceiling would silently override the task definition's memory
#       and have to be edited in two places whenever that changes.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
