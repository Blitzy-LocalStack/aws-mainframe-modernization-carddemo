# =============================================================================
# services/account-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo account, customer and
#   card-cross-reference context: the migrated form of app/cbl/COACTVWC.cbl and
#   COACTUPC.cbl online, the batch readers CBACT01C, CBACT03C and CBCUS01C, and the
#   queue-driven inquiry app/app-vsam-mq/cbl/COACCT01.cbl.
#
# Stages:
#   build   - resolves the reactor's dependencies, then compiles the unpublished
#             common-lib sibling and this module from source in one invocation,
#             producing the repackaged bootable jar under account-service/target.
#   runtime - carries that one jar plus the shared listener-material entry point,
#             and nothing else from the build stage: no Maven, no compiler, no
#             resolved dependency cache and no sources.
#
# Artifact:
#   One bootable Spring Boot jar at /app/app.jar, owned and executed by the
#   non-root identity 10001:10001, listening on 8080, and probed by the single
#   HEALTHCHECK below against the actuator health endpoint /actuator/health over
#   TLS. This module's POM records that the same endpoint is read by BOTH the ALB
#   target group and this container check, and application.yml declares the path,
#   so the two readers cannot disagree about one instance.
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
#     - CARDDEMO_SECURITY_CUSTOMER_IDENTIFIER_KEY_ID
#     - CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY and
#       CARDDEMO_INTERNAL_IDENTITY_TRANSACTION_SIGNING_KEY
#     - CARDDEMO_REFERENCE_CONTEXT_BASE_URL
#     - AWS_REGION
#     - CARDDEMO_ACCOUNT_INQUIRY_REQUEST_QUEUE, CARDDEMO_ACCOUNT_INQUIRY_REPLY_QUEUE
#       and CARDDEMO_ACCOUNT_INQUIRY_ERROR_QUEUE
#   That list is the complete set of no-fallback placeholders in application.yml
#   with ONE deliberate exclusion. CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD has no
#   fallback either and is omitted on purpose: the entry point mints the keystore
#   and exports its password itself, so a deployment that injected one would be
#   overriding material this task generates for its own single use.
#   One further input is required and is NOT a placeholder, so it does not appear
#   in the list above and is easy to miss:
#     - CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY, the environment form of the
#       property carddemo.pagination.cursor.signing-key, base64 over at least 32
#       bytes. The shared kernel publishes the keyset-cursor signer only when that
#       property is set, and it is defaulted nowhere on purpose so that no
#       deployment can sign cursors with a key someone else also holds. Omitting it
#       does not degrade paging; it removes the bean, and the context then fails to
#       start because the read services require it.
#   Every entry above is a variable NAME. No value, and therefore no credential,
#   endpoint, queue URL, account identifier or key ARN, appears in this file.
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
#   Label form: this file uses the PLURAL labels "Alternatives Considered:",
#   "Refactoring Rationale:", "Assumptions:" and "Trade-offs:", spelled as Rule 1
#   and the house convention in tests/README.md section 12 spell them.
#   config/checkstyle/checkstyle.xml and its suppressions file use the singular in
#   file forms instead; that variant is deliberately NOT followed here, so please
#   do not harmonise this file toward it.
#   Comment shape: instruction comments are WHY-only, matching the sibling service
#   images. Rule 1 forbids a comment that restates what the code does, and beside a
#   Dockerfile instruction a "what" line is almost always that restatement, so the
#   "what" is carried by the Purpose, Stages, Artifact and Build sections above
#   instead. The one exception is the reactor-trimming step, whose mechanism is not
#   evident from reading it and which therefore states what it does as well as why.
#
# Build:
#   Run from the REPOSITORY ROOT, so that the trailing dot is the repository root
#   and not this module's directory:
#     docker build -f services/account-service/Dockerfile -t carddemo/account-service:local .
#   The gating workflow spells the same two flags long-form and tags per commit:
#     docker build --file services/account-service/Dockerfile \
#       --tag carddemo-account-service:validation .
#
# WHY (non-obvious design decisions):
#   - Assumptions: the repository root is the build context, not services/,
#     because the image compiles the unpublished common-lib sibling from source
#     and the inherited Checkstyle documentation gate reads config/checkstyle,
#     which sits OUTSIDE the services reactor. Every COPY below is therefore
#     repository-root-relative and all of them fail if the context is narrowed.
#     Running `docker build .` from inside services/account-service/ does not
#     produce a smaller image, it produces no image at all: the first COPY names a
#     path the narrowed context does not contain.
#   - Alternatives Considered: a module-scoped context was evaluated and rejected
#     twice over. It cannot reach common-lib, which no registry carries because
#     com.carddemo:carddemo-services is never published, and it cannot reach
#     config/checkstyle, which the gate addresses relative to the repository root.
#     Pre-installing common-lib into a shared local repository from a separate
#     image was also rejected: it splits one reproducible build into two coupled
#     ones, and the second would compile against whatever the first happened to
#     leave behind rather than against this commit's sources.
#   - Trade-offs: two stages rather than one. Maven, the JDK compiler and the
#     entire resolved dependency cache stay out of the shipped image, which is the
#     difference between shipping a build toolchain to production and shipping a
#     jar. The cost is that the artifact must cross a stage boundary by path, so
#     this file and the module's jar naming are coupled.
#   - Trade-offs: the build context is the whole repository, which is larger than
#     this module. The repository-root .dockerignore already excludes .git, every
#     target/, node_modules/, dist/, .terraform/ and the virtual environments, so
#     the transferred context stays small; no .dockerignore is authored here,
#     because build-context scope is a repository-wide concern rather than this
#     module's.
#   - Assumptions: nothing here publishes. The only registry hostname in this file
#     belongs to the public base image, and the tags in the Build section above
#     name no account and no region, so no line of this file can be mistaken for a
#     publishable reference or carry an account-scoped registry host.
# =============================================================================

# WHY : Assumptions: the build JDK deliberately matches the runtime's Amazon
#       Corretto vendor and Java 21 major version, so a class-file level or
#       vendor-specific behavior cannot first appear after the jar is shipped.
# WHY : Alternatives Considered: a generic maven:3-eclipse-temurin-21 was
#       rejected. It would compile with one vendor's JDK what the other vendor's
#       runtime below executes, and a vendor mismatch is the class of difference
#       that surfaces under load rather than at build time - a garbage collector
#       default or a security-provider list differing between the image that
#       produced the bytecode and the image that runs it. Matching the two costs
#       nothing here, because both vendors publish a Maven image.
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
# WHY : Assumptions: config/checkstyle belongs in THIS layer, beside the POMs, and
#       not with the sources below. The inherited checkstyle-documentation-gate
#       execution is bound to `validate`, which precedes `compile`, so the ruleset
#       has to be present before the dependency step runs; arriving with the
#       sources would be too late and the gate would fail for want of its own
#       configuration rather than for a documentation defect.
# WHY : Assumptions: the whole directory is copied, not checkstyle.xml alone,
#       because that ruleset declares a SuppressionFilter on
#       ${config_loc}/suppressions.xml with its optional flag false. The filter is
#       fail-closed by design, so a missing companion file is a hard error, and
#       copying one file of the pair would break the build in a way that reads like
#       a ruleset problem.
COPY services/pom.xml ./pom.xml
COPY services/common-lib/pom.xml ./common-lib/pom.xml
COPY services/account-service/pom.xml ./account-service/pom.xml
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory an aggregator declares
#       before -pl can select from it, so the copied parent must list only the two
#       modules whose directories exist in this stage. The source parent POM is
#       never modified; only the copy inside this layer is trimmed.
# WHY : Assumptions: exactly two module entries must survive (common-lib and
#       account-service) and at least one must be dropped. The transformation ASSERTS
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
          || $0 ~ /<module>account-service<\/module>/) { kept++; print } \
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
    && mvn -B -ntp -f pom.xml -pl common-lib,account-service -am \
       dependency:go-offline

# WHY : Trade-offs: the sources land here, after the dependency step above, which
#       is what makes that step's layer reusable: editing a Java file invalidates
#       this layer and everything below it, and nothing above it.
# WHY : Assumptions: the ENTIRE src tree is copied, including src/test, rather than
#       src/main alone. Two inherited settings require it - the documentation gate
#       runs with includeTestSourceDirectory true, so test sources are audited, and
#       -DskipTests below still COMPILES them. Narrowing this to src/main would
#       silently shrink the gate's coverage and then fail at test compilation.
COPY services/common-lib/src ./common-lib/src
COPY services/account-service/src ./account-service/src

# WHY : Trade-offs: tests are skipped while packaging because the suite gate is
#       the repository-root reactor build `mvn -f services/pom.xml clean verify`,
#       whose Surefire and Failsafe executions cover all nine modules. This stage
#       sees only common-lib and account-service, so running tests here would report a
#       narrower result under a name that reads like the full one. The Testcontainers
#       backed *RepositoryIT classes also need a Docker daemon, which a builder
#       running this very build cannot be assumed to nest.
# WHY : Assumptions: Checkstyle stays bound to `validate` and therefore still runs
#       inside this stage, before `package`, even under -DskipTests. It is the one
#       gate this image build keeps, because a missing Javadoc is a defect in the
#       sources being compiled here rather than a statement about behavior.
#       -DskipTests skips test EXECUTION only: it neither skips test compilation
#       nor relaxes the documentation gate, which still runs with failOnViolation
#       true at warning severity over main AND test sources.
# WHY : Assumptions: skipping test EXECUTION is the ONLY build-shortening this
#       stage is permitted, and the alternatives are named in prose here rather
#       than written as literal flags so that no reader mistakes the prohibition
#       for a switch to turn on. A Checkstyle skip property, or one grading its
#       violation threshold down, would disarm the documentation gate outright.
#       Maven's broader test-skip property would additionally skip test
#       COMPILATION, letting a broken test source tree ship as a green image. And
#       any return-code tolerance appended to this command would convert every
#       failure above into a successful build. None of the four appears here, and
#       none may be added.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,account-service -am \
    clean package -DskipTests

# WHY : Assumptions: the publisher provides NO Alpine variant of this Corretto
#       image. The repository publishes -al2 and -al2023 only, each with the
#       headful, headless, generic and jdk suffixes, and 21.0.12 is the highest
#       published 21.x. So the reflex reach for a smaller base, `21-alpine`, is not
#       a different or larger image - it is a tag that does not resolve, and
#       substituting it fails this build outright at the pull. An earlier draft of
#       the migration plan named exactly that tag, which is why the fact is
#       recorded here beside the line it would be edited on.
# WHY : Trade-offs: headless is chosen over the other three suffixes. It omits the
#       AWT and graphics stack that an HTTP service never loads; generic keeps it,
#       and jdk would additionally ship javac and the whole compiler toolchain into
#       a production runtime, which is the very thing the build stage exists to
#       keep out. What is given up is the ability to render an image or open a font
#       inside the container, neither of which this context does.
# WHY : Alternatives Considered: a floating tag such as `21`, or `latest`, was
#       rejected on both stages. An unpinned base lets the runtime change with no
#       commit to point at, so a behaviour difference between two builds of one
#       revision would have no explanation in the repository.
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
       'carddemo:!:10001:10001:CardDemo account:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: the owned application directory is the working directory so
#       any relative temporary output stays inside the only application path the
#       fixed identity may modify.
WORKDIR /app

# WHY : Assumptions: account-service.jar is the artifact this module's POM
#       produces and the Boot repackage goal makes it self-contained, so copying
#       that one file excludes sources, build caches, reports and every reactor
#       target directory from the shipped image.
# WHY : ⚠️ Refactoring Rationale: this path no longer carries the module VERSION.
#       It read account-service-1.0.0-SNAPSHOT.jar, which wrote a version this
#       Dockerfile cannot read -- it is inherited from services/pom.xml -- into a
#       literal here, so a version bump in that file broke this build at its LAST
#       instruction, after the whole reactor had already compiled, and reported a
#       missing artifact rather than a stale literal. This module's POM now sets
#       <finalName>account-service</finalName>, which is the arrangement
#       services/card-service already used, so the name is a contract between the
#       two files and neither may change it alone.
COPY --from=build --chown=10001:10001 \
    /build/services/account-service/target/account-service.jar \
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
#       metadata while the numeric records keep behavior deterministic. USER is set
#       BEFORE the entry point so the JVM is never started as root and no later
#       instruction can switch back; nothing below re-declares USER root, and no
#       path in the image is made world-writable to compensate.
USER carddemo:carddemo

# WHY : Assumptions: this image carries NO ENV instruction, and that absence is a
#       decision rather than an omission. Every value the context reads - the
#       datasource URL and credentials, the Flyway role, the JWT issuer URI and
#       expected client id, the customer-identifier key id, the two internal
#       identity signing keys, the reference context base URL, the region and the
#       three inquiry queue names - arrives at run time from Parameter Store and
#       Secrets Manager through the task definition. Baking any of them into a
#       layer would make one image environment-specific AND readable by anyone who
#       can pull it, since a layer is not a secret store.
# WHY : Assumptions: SPRING_PROFILES_ACTIVE is deliberately NOT set here either.
#       The same image artifact is promoted from dev to prod unchanged, so the
#       profile is chosen by the task definition; pinning it in the image would
#       either ship two different images or let a stale layer decide which
#       environment's configuration a task loads.
# WHY : Assumptions: the three inquiry queue names are supplied as NAMES, not
#       URLs, and the image carries no endpoint override, so the SQS client
#       resolves them against the real regional endpoint using the task role.
#       Baking a queue URL would pin an account id into an image layer; baking
#       an endpoint override would let a production task be pointed at an
#       emulator by a stale layer rather than by a deliberate deployment.
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
