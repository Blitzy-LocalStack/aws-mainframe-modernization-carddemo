# =============================================================================
# services/transaction-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo LEDGER bounded context and
#   runs it as a Spring Boot executable jar on ECS Fargate behind an INTERNAL
#   application load balancer. For the `ledger` tables alone, this image is what
#   replaces the z/OS runtime in which app/jcl/POSTTRAN.jcl and four CICS
#   transactions used to execute:
#     CT00  transaction list  app/cbl/COTRN00C.cbl
#     CT01  transaction view  app/cbl/COTRN01C.cbl
#     CT02  transaction add   app/cbl/COTRN02C.cbl
#     CB00  bill payment      app/cbl/COBIL00C.cbl
#   The context OWNS the PostgreSQL schema `ledger` and its four tables --
#   transactions, daily_transactions, transaction_rejects and
#   transaction_category_balances -- so this one image carries both the online
#   surface of those four transactions and the schema they read and write. The COBOL
#   named above is the behavioural oracle: it is cited by path and never modified.
#
# Stages:
#   build   - resolves the reactor's dependencies, then compiles the unpublished
#             common-lib sibling and this module from source in one invocation,
#             producing the repackaged bootable jar under transaction-service/target.
#   runtime - carries that one jar, the verified database trust anchor and the shared
#             listener-material entry point, and nothing else from the build stage:
#             no Maven, no compiler, no resolved dependency cache and no sources.
#
# Artifact:
#   One bootable Spring Boot jar at /app/app.jar, owned and executed by the non-root
#   identity 10001:10001, listening on 8080 over TLS, and probed by the single
#   HEALTHCHECK below against the actuator health endpoint /actuator/health. This
#   module's POM records that the same endpoint is read by BOTH the load balancer
#   target group and this container check, and application.yml declares the path, so
#   the two readers cannot disagree about one instance.
#
# Gates:
#   The Checkstyle documentation gate runs INSIDE this build, not merely in the
#   pipeline. services/pom.xml binds its checkstyle-documentation-gate execution to
#   Maven's validate phase, and validate precedes package, so a missing or incomplete
#   Javadoc in either module compiled here fails `docker build` itself.
#
# Parameters:
#   This image takes no command arguments. The entrypoint is the exec-form JVM
#   invocation below and the process is a long-running HTTP listener, so there is
#   no per-run token of the kind the batch image accepts.
#   Runtime configuration arrives entirely through environment variables that the
#   ECS task definition supplies from Parameter Store and Secrets Manager, whose
#   values are produced by the Terraform outputs of infra/envs/{dev,prod}. The
#   variables whose application.yml placeholders declare NO fallback, so the context
#   cannot start without them, are:
#     - SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
#     - SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
#     - CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID
#   Every entry above is a variable NAME. No value, and therefore no credential,
#   connection string, issuer URI, queue URL, account identifier or key ARN, appears
#   anywhere in this file: it declares no ENV and no ARG at all, so there is nothing
#   here for a deployment to inherit a secret from and nothing for a reader to
#   mistake for one. CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD has no fallback either and
#   is deliberately omitted from the list: the entry point mints the keystore and
#   exports its password itself, so a deployment that injected one would be
#   overriding material this task generates for its own single use.
#
# Return values:
#   The container's exit status is the JVM's, because the JVM is PID 1. Three codes
#   are expected and they do not all mean what a bare "zero or non-zero" reading
#   would suggest, which is why each is named:
#     0   (integer): the JVM completed a shutdown it initiated itself.
#     143 (integer): 128 + SIGTERM, and the NORMAL result of an orchestrator stop or
#           of an ECS rolling deployment draining this task. The signal reaches the
#           JVM directly, Spring runs its graceful-shutdown phase, and the process
#           then exits on the signal. This is success, not an abnormal termination --
#           measured at 2s against a 10s stop grace.
#     137 (integer): 128 + SIGKILL, meaning the grace period elapsed before shutdown
#           finished, or the task was killed for exceeding its memory limit. This one
#           IS a failure and is the code to alarm on.
#   Any other non-zero status means the context failed to start, so the task never
#   turns healthy and the load balancer never registers it.
#
# Exceptions or errors:
#   An unset variable from the list above, an unreachable datasource, an
#   unreachable JWT issuer, or a Flyway checksum mismatch aborts the start before
#   the listener binds, so the task never reports healthy.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md
#   Label form: this file uses the PLURAL labels "Alternatives Considered:",
#   "Refactoring Rationale:", "Assumptions:" and "Trade-offs:", spelled as Rule 1 and
#   the house convention in tests/README.md section 12 spell them.
#   config/checkstyle/checkstyle.xml and its suppressions file use singular in-file
#   forms instead; that variant is deliberately NOT followed here, so please do not
#   harmonise this file toward it. The two forms are never mixed.
#   Comment shape: instruction comments are WHY-only, matching the sibling service
#   images. Rule 1 forbids a comment that restates what the code does, and beside a
#   Dockerfile instruction a "what" line is almost always that restatement, so the
#   "what" is carried by the Purpose, Stages, Artifact and Build sections instead.
#   The one exception is the reactor-trimming step, whose mechanism is not evident
#   from reading it and which therefore states what it does as well as why.
#
# Build:
# WHAT: From the REPOSITORY ROOT, so that the trailing dot is the repository root and
#       not this module's directory:
#         docker build -f services/transaction-service/Dockerfile \
#           -t carddemo/transaction-service:local .
#       The gating workflow spells the same two flags long-form and tags per commit:
#         docker build --file services/transaction-service/Dockerfile \
#           --tag carddemo-transaction-service:validation .
#
# WHY (non-obvious design decisions):
#   - Assumptions: the repository root is the build context, not services/, because
#     the image compiles the unpublished common-lib sibling from source, the
#     inherited Checkstyle documentation gate reads config/checkstyle, and the
#     runtime stage copies the shared entry point and the trust-anchor installer from
#     config/docker. All three sit OUTSIDE the services reactor, so every COPY below
#     is repository-root-relative and all of them fail if the context is narrowed.
#     Running `docker build .` from inside services/transaction-service/ does not
#     produce a smaller image, it produces no image at all: the first COPY names a
#     path the narrowed context does not contain, and the parent this module
#     declares at <relativePath>../pom.xml</relativePath> is not there to resolve.
#   - Alternatives Considered: a module-scoped context, rejected twice over. It
#     cannot reach common-lib, which no registry carries because
#     com.carddemo:carddemo-services is never published, and it cannot reach
#     config/checkstyle, which the gate addresses relative to the repository root.
#     Pre-installing common-lib into a shared local repository from a separate image
#     was also rejected: it splits one reproducible build into two coupled ones, and
#     the second would compile against whatever the first happened to leave behind
#     rather than against this commit's sources.
#   - Alternatives Considered: a single-stage image, which is shorter to read and
#     would keep Maven, the JDK compiler and the whole resolved dependency cache
#     available inside the shipped result for debugging. Given up deliberately: those
#     bytes are pulled by the ECS agent on every task placement and every scale-out
#     event, so the mechanism is image-pull bytes translating directly into task
#     start latency and into how long a scale-out lags the load that triggered it.
#     What is kept is a runtime layer holding no build tooling at all, which also
#     removes a compiler and a package resolver from the running task's attack
#     surface. The cost is that the artifact must cross a stage boundary by path, so
#     this file and the module's jar naming are one coupled contract.
#   - Assumptions: both base images are matched on JDK vendor and major version --
#     Amazon Corretto 21 on each stage -- so neither the class-file major version nor
#     the trust-store contents can diverge between compile time and run time.
#   - Trade-offs: the POMs and the documentation-gate configuration are copied in a
#     layer of their own, ahead of any source file, so that a source-only edit reuses
#     the resolved dependency layer. Detail and the accepted cost sit beside that
#     COPY set.
#   - Assumptions: the image creates and selects a FIXED non-root identity itself,
#     because the ECS task definition does not drop privileges. Detail sits beside
#     the passwd and group records.
#   - Assumptions: the HEALTHCHECK below probes the SAME actuator path the load
#     balancer target group probes, which makes it a deployment contract rather than
#     a convenience. Detail sits beside the instruction.
#   - Trade-offs: the build context is the whole repository, which is larger than
#     this module. The repository-root .dockerignore already excludes .git, every
#     target/, node_modules/, dist/, .terraform/ and the virtual environments, so the
#     transferred context stays small; no .dockerignore is authored here, because
#     build-context scope is a repository-wide concern rather than this module's.
#   - Assumptions: nothing here publishes. The only registry hostnames in this file
#     belong to the public base images, and the tags in the Build section above name
#     no account and no region, so no line of this file can be mistaken for a
#     publishable reference or carry an account-scoped registry host.
# =============================================================================

# WHY : Assumptions: the build JDK deliberately matches the runtime's Amazon
#       Corretto VENDOR and Java 21 MAJOR VERSION, so two specific things cannot
#       diverge between compile time and run time. (1) The class-file major version
#       the compiler emits is one the runtime accepts, so no
#       UnsupportedClassVersionError can appear only after the jar is shipped.
#       (2) The trust store is the same distribution's cacerts, so a TLS handshake
#       this build proves is a handshake the runtime can also make -- a mismatched
#       vendor is the classic source of a build that passes and an image that fails
#       on its first outbound TLS connection, which for this module is the JWT
#       issuer's key set. Neither failure is visible in a compile log, which is why
#       the pairing is pinned rather than left to whichever JDK is convenient.
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
#       dependency layer. The mechanism is that this COPY set is the cache KEY of the
#       resolution layer beneath it: while these four paths are byte-identical the
#       daemon reuses that layer, and Maven's dependency download -- which dominates
#       the otherwise immutable cost of this build -- is not repeated. Two costs are
#       accepted. One extra layer, and a coupling that must be understood rather than
#       merely tolerated: because the POMs alone are the key, a dependency added to a
#       POM DOES invalidate the layer and re-resolve, which is correct, while a
#       dependency that changes without any POM changing would NOT -- so no
#       version may be sourced from anything but these files. Every version this
#       module uses comes from the parent POM copied here, which is what keeps the
#       key complete and the reuse honest.
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
#       breakage surfaces downstream as an obscure -pl resolution error.
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

# WHY : Trade-offs: this is the far end of the layer split the POM copy above opens,
#       and it is stated here as well as there because a reader who arrives at this
#       point sees only two paths and not the reason they are separated from the
#       descriptors. Everything above this line is cache-reusable across a source
#       edit; everything from here down is rebuilt by one. That is the whole benefit,
#       and the cost is that the two ends of one decision have to stay in agreement.
# WHY : Assumptions: the SOURCE subdirectory is named, not the module directory. Two
#       things break if the module directory is copied wholesale instead. (1) The POM
#       would land in the same layer as the sources, which collapses the split the
#       previous layer exists to create -- so a comment-only source edit would
#       re-resolve every dependency. (2) The host's target/ would be copied into the
#       stage, where a stale locally-built jar can be picked up by the glob that
#       selects the artifact in the runtime stage. The repository-root .dockerignore
#       excludes target/, so this is defence in depth rather than the only guard.
# WHY : Assumptions: common-lib's sources are copied because the shared kernel is
#       compiled here from this commit rather than resolved: com.carddemo is published
#       to no registry, so there is no version of it to download and the reactor is
#       the only thing that can produce it.
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
#       image must guarantee UID/GID 10001 itself -- nothing outside this file will
#       do it, and a container whose image omits this runs the JVM as root. The
#       process writes only below its owned working directory and the
#       platform-provided /tmp.
# WHY : Assumptions: the identity is pinned to a FIXED numeric 10001:10001 rather
#       than left to whatever non-root default a base image happens to ship, for two
#       reasons that a name alone does not cover. (1) File ownership on any mounted
#       volume is expressed numerically by the kernel, not by name, so a fixed UID is
#       what makes ownership deterministic across a rebuild and across the
#       task-definition volume this service may be given. (2) The pin survives a
#       base-image update that changes or withdraws the default user: were this file
#       to rely on that default, a patch-level bump of the runtime tag could silently
#       change the UID that owns /app and the jar, and the failure would surface as a
#       permission error at start-up rather than as an image change.
RUN printf '%s\n' 'carddemo:x:10001:' >> /etc/group \
    && printf '%s\n' \
       'carddemo:!:10001:10001:CardDemo transaction:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: the owned application directory is the working directory so
#       any relative temporary output stays inside the only application path the
#       fixed identity may modify.
WORKDIR /app

# WHY : ⚠️ Refactoring Rationale: this path no longer spells the module VERSION out.
#       It read transaction-service-1.0.0-SNAPSHOT.jar, and 1.0.0-SNAPSHOT is a value
#       this module never declares -- it is inherited from services/pom.xml. Bumping
#       it there is a routine edit that touches no file in this module, and it broke
#       this instruction, which is the LAST one in the build: the whole reactor had
#       already compiled before the failure, and the error read as a missing artifact
#       rather than as a stale literal. The glob removes the coupling to a version
#       this file cannot read.
# WHY : Assumptions: the glob resolves to EXACTLY ONE path, and two independent facts
#       are what guarantee it rather than luck. (1) The Boot repackage goal writes the
#       bootable jar under the inherited default name and renames the plain archive
#       beside it with a .jar.original suffix, which `*.jar` does not match. (2) This
#       module binds no test-jar goal -- only common-lib does -- so no second archive
#       is produced here. Measured in this stage, `ls -1` over the same target
#       directory returns a single path. Were a second archive ever added, the daemon
#       would refuse a multi-source COPY onto a file destination and fail the build
#       loudly, which is the failure mode to want here.
# WHY : Alternatives Considered: setting <finalName>transaction-service</finalName> in
#       the module POM and copying that stable literal, which is what
#       services/account-service, card-service and authorization-service do. Rejected
#       for this module because its POM deliberately leaves finalName uncustomised and
#       its plugin block records that the inherited default is what ships; changing
#       the artifact name from inside this file would make the Dockerfile the author
#       of a naming decision the POM owns, and the two files would then state
#       opposite contracts with only one of them enforced by a build.
#       services/reporting-service already answered this identical question with a
#       plain jar glob, so the reactor has one shape for the finalName-unset case
#       rather than two.
# WHY : Assumptions: the Boot repackage goal has made that one archive
#       self-contained, so copying exactly it is sufficient and it leaves sources,
#       build caches, test reports and every other reactor target directory behind in
#       the discarded stage. /app/app.jar is depended on by the shared entry point,
#       which execs `java -jar /app/app.jar`, so renaming the destination here
#       without renaming it there yields an image that builds and cannot start. The
#       ownership is set during the copy so no subsequent chown layer duplicates the
#       file.
COPY --from=build --chown=10001:10001 \
    /build/services/transaction-service/target/*.jar \
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
# WHY : Trade-offs: the NAMED form is used with full knowledge that hadolint reports
#       DL3066 against it -- "non-numeric user-id may not be resolvable by host
#       system" -- and the finding is accepted rather than silenced, because the
#       condition it warns about cannot arise here: the passwd and group records this
#       name resolves through are written by the immediately preceding RUN in this
#       same image, so resolution never depends on a host database. Verified in the
#       built image, where the process reports `carddemo` with uid 10001. What is
#       given up is the one orchestrator behaviour that requires a numeric USER --
#       Kubernetes `runAsNonRoot` admission, which cannot verify a name -- and that is
#       acceptable because ADR-002 places these services on ECS Fargate, whose task
#       definition reads the resolved uid. The same finding appears identically in all
#       eight service images; it is not suppressed anywhere, so a future move to a
#       numeric USER would be one uniform change rather than a per-image discovery.
USER carddemo:carddemo

# WHY : Assumptions: the image sets no locale, no timezone and no JVM number
#       format, and that omission is deliberate. Money in this context is
#       BigDecimal at scale 2 and crosses the wire as a JSON string, so no
#       formatting decision may be taken by the runtime environment; a
#       container-level locale would be exactly such a decision, and one that
#       could differ between two tasks of the same service.
# WHY : Assumptions: /actuator/health is a DEPLOYMENT CONTRACT, not a convenience,
#       because the very same path is polled by a second consumer defined outside
#       this image: the load balancer target group that decides whether to route to
#       this task. application.yml declares the path and exposes it unauthenticated
#       for exactly these two unauthenticated pollers, and this module's POM records
#       the pairing beside spring-boot-starter-actuator. Changing the path here, or
#       dropping this instruction, does not merely weaken a local check: target
#       registration never succeeds, so the balancer cycles a task that is serving
#       correctly, and the two readers would be describing one instance differently.
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
# WHY : Alternatives Considered: adding -Xmx or -XX container flags was rejected.
#       Java 21 reads the cgroup memory limit and sizes the heap from it, so a
#       hard-coded ceiling would silently override the task definition's memory
#       and have to be edited in two places whenever that changes. JAVA_OPTS is
#       honoured by the script for a deployment that must override something.
ENTRYPOINT ["/usr/local/bin/carddemo-entrypoint.sh"]
