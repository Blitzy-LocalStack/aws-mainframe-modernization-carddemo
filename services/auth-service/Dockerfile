# =============================================================================
# services/auth-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo sign-on and
#   user-administration context: the migrated form of app/cbl/COSGN00C.cbl and the
#   four user-maintenance transactions COUSR00C through COUSR03C, which the
#   baseline runs as five CICS transactions over one shared USRSEC file.
#
# Stages:
#   build   - resolves the reactor's dependencies, then compiles the unpublished
#             common-lib sibling and this module from source in one invocation,
#             producing the repackaged bootable jar under auth-service/target.
#   runtime - carries that one jar plus the shared listener-material entry point,
#             and nothing else from the build stage: no Maven, no compiler, no
#             resolved dependency cache and no sources.
#
# Artifact:
#   One bootable Spring Boot jar at /app/app.jar, owned and executed by the
#   non-root identity 10001:10001, listening on 8080, and probed by the single
#   HEALTHCHECK below against the actuator health endpoint over TLS.
#
# Gates:
#   The Checkstyle documentation gate runs INSIDE this build, not merely in the
#   pipeline. services/pom.xml binds its checkstyle-documentation-gate execution
#   to Maven's validate phase, and validate precedes package, so a missing or
#   incomplete Javadoc in either module compiled here fails `docker build`.
#
# Parameters:
#   This image declares no build argument, and the entry point takes no argument
#   of its own: it forwards "$@", so a `docker run` trailer or an ECS container
#   `command` override reaches the JVM unchanged. There is no per-run token of the
#   kind the batch image accepts, because this process is a long-running HTTP
#   listener rather than a job.
#   Runtime configuration arrives entirely through environment variables that the
#   ECS task definition supplies from Parameter Store and Secrets Manager. The
#   variables whose application.yml placeholders declare NO fallback, so the
#   context cannot start without them, are:
#     - SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
#     - SPRING_FLYWAY_USER, SPRING_FLYWAY_PASSWORD
#     - SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
#     - CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID
#     - CARDDEMO_AUTH_COGNITO_USER_POOL_ID, CARDDEMO_AUTH_COGNITO_CLIENT_ID and
#       CARDDEMO_AUTH_COGNITO_CLIENT_SECRET
#   CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD has no fallback either and is
#   deliberately absent from that list: the entry point mints the keystore and
#   exports its password itself, so a deployment that injected one would be
#   overriding material this task generates for its own single use.
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
# Build:
#   Run from the REPOSITORY ROOT, so that the trailing dot is the repository root
#   and not this module's directory:
#     docker build -f services/auth-service/Dockerfile -t carddemo/auth-service:local .
#   The gating workflow spells the same two flags long-form and tags per commit:
#     docker build --file services/auth-service/Dockerfile \
#       --tag carddemo-auth-service:validation .
#
# WHY (non-obvious design decisions):
#   - Assumptions: the repository root is the build context, not services/,
#     because the image compiles the unpublished common-lib sibling from source
#     and the inherited Checkstyle documentation gate reads config/checkstyle,
#     which sits OUTSIDE the services reactor. Every COPY below is therefore
#     repository-root-relative and all of them fail if the context is narrowed.
#   - Trade-offs: two stages rather than one. Maven, the JDK compiler and the
#     entire resolved dependency cache stay out of the shipped image, which is
#     the difference between shipping a build toolchain to production and
#     shipping a jar; a single-stage image would also make every dependency it
#     downloaded part of the attack surface of a running service. The cost is
#     that the artifact must cross a stage boundary by path, so this file and the
#     module's jar naming are coupled - and that coupling is exactly why the
#     copy below matches a glob instead of a literal version.
#   - Assumptions: nothing here publishes. The only registry hostname in this
#     file belongs to the public base image, and the tag in the Build section
#     names no account and no region, so no line of this file can be mistaken
#     for a publishable reference or carry an account-scoped registry host.
# =============================================================================

# WHY : Assumptions: the build JDK deliberately matches the runtime's Amazon
#       Corretto vendor and Java 21 major version, so a class-file level or
#       vendor-specific behavior cannot first appear after the jar is shipped.
# WHY : Alternatives Considered: a generic maven:3-eclipse-temurin-21 was
#       rejected. It would compile with one vendor's JDK what the other vendor's
#       runtime below executes, and a vendor mismatch is the class of difference
#       that surfaces under load rather than at build time - a JIT or garbage
#       collector default, or a security-provider list, differing between the
#       image that produced the bytecode and the image that runs it. Matching the
#       two costs nothing here, because both vendors publish a Maven image.
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

# WHY : Trade-offs: the POMs and the documentation-gate configuration are copied
#       before any source file, and the dependency resolution below sits in its
#       own layer between the two groups. That split is the whole point of the
#       ordering: a source-only change invalidates the layer that copies sources
#       and everything after it, but not the resolved dependency layer above it,
#       so a one-line Java edit does not re-resolve the dependency tree. The cost
#       is four COPY instructions where one would do, and one more layer in the
#       history than a single copy of the whole reactor would produce.
# WHY : Trade-offs: the accepted residue is that dependency:go-offline is not a
#       guarantee. In a multi-module reactor it can miss a plugin dependency that
#       only a later lifecycle phase asks for, so the warm-up is a best effort
#       rather than a proof of completeness. The split is still worth it because
#       the dependency tree, not the residue, is what dominates a rebuild.
COPY services/pom.xml ./pom.xml
COPY services/common-lib/pom.xml ./common-lib/pom.xml
COPY services/auth-service/pom.xml ./auth-service/pom.xml

# WHY : Assumptions: this destination is absolute where the three above are
#       relative, and the asymmetry is required rather than untidy. config/ is a
#       SIBLING of services/ in the repository, and the parent POM locates the
#       ruleset through profiles that test ../config/checkstyle from the
#       aggregator and ../../config/checkstyle from each module. Mirroring that
#       relationship under /build satisfies both; flattening the ruleset into the
#       reactor directory would satisfy neither, and the gate would fail while
#       looking for a file that had been copied in.
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory an aggregator declares
#       before -pl can select from it, so the copied parent must list only the two
#       modules whose directories exist in this stage. The source parent POM is
#       never modified; only the copy inside this layer is trimmed.
# WHY : Assumptions: exactly two module entries must survive (common-lib and
#       auth-service) and at least one must be dropped. The transformation ASSERTS
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
          || $0 ~ /<module>auth-service<\/module>/) { kept++; print } \
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
    && mvn -B -ntp -f pom.xml -pl common-lib,auth-service -am \
       dependency:go-offline

# WHY : Trade-offs: this is the second half of the split declared above the POM
#       copies, and its position is the whole mechanism. Everything from here down
#       is invalidated by any source edit, which is why the dependency warm-up sits
#       ABOVE it and not below: measured on this reactor, appending one line to one
#       Java file leaves the warm-up layer CACHED and re-runs only the packaging
#       step. Both modules' sources are copied because the reactor compiles both.
COPY services/common-lib/src ./common-lib/src
COPY services/auth-service/src ./auth-service/src

# WHY : Assumptions: -am builds the sibling common-lib from source in this same
#       reactor, and that is a requirement rather than an optimisation. The
#       library is published to no registry, so no artifact exists for Maven to
#       resolve, and naming both modules with -pl still needs -am to bring in the
#       parent POM they inherit their plugin and dependency management from.
# WHY : Trade-offs: -DskipTests, and deliberately NOT -Dmaven.test.skip=true. The
#       suite gate is the repository-root reactor build
#       `mvn -f services/pom.xml clean verify`, whose Surefire and Failsafe
#       executions cover all nine modules; this stage sees two of them, so tests
#       run here would report a narrower result under a name that reads like the
#       full one. This stage also could not run them all: UserRepositoryIT pins
#       the persistence contract against a real PostgreSQL engine through
#       Testcontainers, which needs a Docker daemon an image build does not have.
#       The flag chosen still COMPILES test sources, so a test that no longer
#       compiles still fails this build; -Dmaven.test.skip=true was rejected
#       precisely because it would skip that compilation too and hide the break.
# WHY : Assumptions: -DskipTests suppresses test EXECUTION only - it does not skip
#       Checkstyle. The documentation gate stays bound to `validate`, which runs
#       before `package`, so it still fires inside this stage. It is the one gate
#       this image build keeps, because a missing Javadoc is a defect in the very
#       sources being compiled here rather than a statement about behaviour, and
#       no skip flag, severity downgrade or return-code tolerance may be added
#       anywhere in this file to get past it.
# WHY : Trade-offs: -B and -ntp are set so the log is a reviewable transcript
#       rather than a redrawn progress display, at the cost of losing the
#       interactive output a developer would see locally.
# WHY : Trade-offs: --offline is what turns the warm-up above from a hopeful
#       optimisation into a checked one. Without it, anything go-offline missed
#       would be fetched here quietly, and the dependency layer would look
#       effective while the build kept reaching the network on every rebuild;
#       with it, a miss fails this step by name instead. Measured on this
#       reactor: the packaging step resolves entirely from the local repository
#       and downloads nothing. What is given up is tolerance - a dependency added
#       to either module's POM must be reachable by the warm-up, which is the
#       intended loss, because the alternative hides the very thing being cached.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,auth-service -am \
    clean package -DskipTests

# WHY : Assumptions: the publisher provides NO Alpine variant of this Corretto
#       image. The repository publishes -al2 and -al2023 only, each with the
#       headful, headless, generic and jdk suffixes, and 21.0.12 is the highest
#       published 21.x. So the reflex reach for a smaller base, `21-alpine`, is
#       not a different or larger image - it is a tag that does not resolve, and
#       substituting it fails this build outright at the pull. An earlier draft of
#       the migration plan named exactly that tag, which is why the fact is
#       recorded here beside the line it would be edited on.
# WHY : Trade-offs: headless is chosen over the other three suffixes. It omits the
#       AWT and graphics stack that an HTTP service never loads; generic keeps it,
#       and jdk would additionally ship javac and the whole compiler toolchain
#       into a production runtime, which is the very thing the build stage exists
#       to keep out. What is given up is the ability to render an image or open a
#       font inside the container, neither of which this context does.
# WHY : Refactoring Rationale: the reference is digest-pinned rather than left on
#       the bare tag because this tag has already moved at least once - the
#       registry index for `21.0.12-al2023-headless` and a copy cached earlier on
#       a build host are two different manifest lists that both still resolve. A
#       patch-level tag reading like an exact version is the easiest kind of
#       mutable reference to mistake for an immutable one, so two builds of one
#       commit could otherwise ship two different runtimes with nothing in the
#       build log to say so.
FROM public.ecr.aws/amazoncorretto/amazoncorretto:21.0.12-al2023-headless@sha256:75f1cdabc3d8758f5499bf67046991bc7797e37156c7445f0048eb6d17c90dcf AS runtime

# WHY : Alternatives Considered: running as root was rejected outright. Nothing
#       this image does at run time needs a privileged operation - the JVM reads
#       one jar, writes a keystore under /tmp and binds an unprivileged port - so
#       root would buy no capability while leaving a compromised process holding
#       every privilege the container has.
# WHY : Alternatives Considered: installing shadow-utils solely for useradd and
#       groupadd was rejected. Inspecting the pinned digest shows shadow-utils is
#       not installed and none of useradd, groupadd, adduser or addgroup exists,
#       so the tooling path would mean adding a package, and its payload adds no
#       runtime capability to a process that needs one fixed identity. Appending
#       plain passwd and group records is the accepted fallback: it yields the
#       same fixed 10001:10001 identity with no package and no repository access
#       during the build.
# WHY : Assumptions: the ECS task definition does not drop privileges, so the
#       image must guarantee UID/GID 10001 itself. The process writes only below
#       its owned working directory and the platform-provided /tmp.
RUN printf '%s\n' 'carddemo:x:10001:' >> /etc/group \
    && printf '%s\n' \
       'carddemo:!:10001:10001:CardDemo auth:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: the owned application directory is the working directory so
#       any relative temporary output stays inside the only application path the
#       fixed identity may modify.
WORKDIR /app

# WHY : Assumptions: the glob resolves to exactly one file, and the module POM is
#       what guarantees it. The Boot repackage goal writes the bootable jar under
#       the inherited default name and renames the plain one beside it with a
#       .jar.original suffix, which `*.jar` does not match; services/auth-service/
#       pom.xml deliberately leaves <finalName> uncustomised and records that the
#       image build selects the artifact by a plain jar glob, so the two files are
#       one contract read from both ends.
# WHY : Alternatives Considered: naming auth-service-<version>.jar literally. It
#       reads as the safer, more explicit form and is the opposite: the version
#       lives in the parent POM, so a version bump would break every one of these
#       images at the COPY, and it would break them by writing a path that no
#       longer exists rather than by saying a version changed.
# WHY : Assumptions: the destination path is fixed at /app/app.jar because the
#       shared entry point below execs `java -jar /app/app.jar`; renaming here
#       without renaming there yields an image that builds and cannot start.
COPY --from=build --chown=10001:10001 \
    /build/services/auth-service/target/*.jar \
    /app/app.jar

# WHY : Assumptions: 8080 is a contract with the infrastructure rather than a
#       local preference. It is the one port the security-group design permits
#       from the load balancer to the application tier, so the application.yml
#       server.port, the ALB target group and this declaration must agree; a
#       different port here would produce a task that starts and never receives
#       traffic, which is harder to diagnose than a task that fails to start.
# WHY : Assumptions: 8080 carries BOTH the business REST surface and the actuator
#       health group, over TLS, because the base profile enables server.ssl and
#       declares one connector. A second port was considered and is not available
#       without a management port, which would need its own certificate.
EXPOSE 8080

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

# WHY : Assumptions: NO credential is baked into this image and none can be.
#       CARDDEMO_AUTH_COGNITO_CLIENT_SECRET is the one genuine secret any
#       CardDemo service reads, and it arrives at run time from Secrets Manager
#       through the task definition, never as an ARG, an ENV default or a COPY.
#       That matters more here than in the sibling images because this is the
#       context that replaced a baseline which stored an eight-character
#       password in plaintext at app/cpy/CSUSR01Y.cpy:21 - carrying a secret in
#       an image layer would reproduce the same defect in a new medium, since a
#       layer is readable by anyone who can pull the image.
# WHY : Assumptions: /actuator/health is consumed by TWO independent readers - the
#       ALB target group's health check and this container health check - and
#       application.yml exposes it with show-details never. Its availability is
#       therefore an infrastructure contract, not a convenience: securing that
#       endpoint shut or moving its path would take down both readers at once,
#       one of them silently draining the task from the load balancer.
# WHY : Assumptions: the pinned headless runtime already provides /usr/bin/curl,
#       from the curl-minimal package - verified by inspecting this exact digest,
#       which also confirms that wget is absent - so the probe installs no package
#       and adds no layer, and it fails explicitly on a non-success status.
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
# WHY : Trade-offs: the four durations are configuration, and --start-period is the
#       one that has to be reasoned about. It has to cover a cold JVM start plus
#       the keystore generation this entry point performs plus the Flyway
#       migration the context runs before it binds; set below that, the container
#       is killed while it is legitimately still starting, and the deployment
#       loops on a service that would have become healthy. Set far above it, a
#       task that will never bind is left running for longer before replacement.
#       --retries then tolerates a transient probe failure at three consecutive
#       misses, and --timeout stays under --interval so probes cannot overlap.
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
# WHY : Alternatives Considered: adding -Xmx or -XX container flags was rejected.
#       Java 21 reads the cgroup memory limit and sizes the heap from it, so a
#       hard-coded ceiling would silently override the task definition's memory
#       and have to be edited in two places whenever that changes. JAVA_OPTS is
#       honoured by the script for a deployment that must override something.
ENTRYPOINT ["/usr/local/bin/carddemo-entrypoint.sh"]
