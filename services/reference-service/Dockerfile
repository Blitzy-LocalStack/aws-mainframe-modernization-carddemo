# =============================================================================
# services/reference-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo reference-data context: the
#   migrated form of app/app-transaction-type-db2/cbl/COTRTLIC.cbl and COTRTUPC.cbl,
#   their batch updater COBTUPDT.cbl, and the queue-driven date conversion
#   app/app-vsam-mq/cbl/CODATE01.cbl. The date-edit rules of app/cbl/CSUTLDTC.cbl are
#   reached THROUGH this context but do not live in it -- DateEditValidator sits in
#   common-lib and this module is its HTTP and queue front door -- so a reader looking
#   for those rules in this image's own sources finds only the endpoint that calls
#   them.
#   Two start-up behaviours of the packaged jar are what the health probe below is
#   sized for. Flyway applies TWO migrations rather than one: V1__reference.sql
#   creates the six `reference` tables and V2__seed_reference.sql seeds them, this
#   being the only module in the migration that carries a V2, because it seeds the
#   mandatory 'DEFAULT' disclosure-group row and the address allow-lists that other
#   contexts read to validate an address. DateInquiryMessageListener then begins
#   polling SQS, which is what replaces CODATE01's IBM MQ front door.
#
# Stages:
#   build   - installs the pinned database trust anchor, resolves the reactor's
#             dependencies, then compiles the unpublished common-lib sibling and this
#             module from source in one invocation, producing the repackaged bootable
#             jar under reference-service/target.
#   runtime - carries that one jar, the verified trust anchor and the shared
#             listener-material entry point, and nothing else from the build stage:
#             no Maven, no compiler, no resolved dependency cache and no sources.
#
# Artifact:
#   One bootable Spring Boot jar at /app/app.jar, owned and executed by the non-root
#   identity 10001:10001, listening on 8080, and probed by the single HEALTHCHECK
#   below against the actuator health endpoint /actuator/health over TLS. This
#   module's POM records that the same endpoint is read by BOTH the ALB target group
#   and this container check, so the two readers cannot disagree about one instance.
#
# Gates:
#   The Checkstyle documentation gate runs INSIDE this build, not merely in the
#   pipeline. services/pom.xml binds its checkstyle-documentation-gate execution to
#   Maven's validate phase, and validate precedes package, so a missing or incomplete
#   Javadoc in either module compiled here fails `docker build` rather than surfacing
#   later in a pipeline step this image never runs.
#
# Parameters:
#   This image declares no build argument, and the entry point takes no argument of
#   its own: it is the exec-form invocation of the shared listener-material script
#   below, whose last line is `exec java ... -jar /app/app.jar "$@"`, so a `docker
#   run` trailer such as --spring.profiles.active=prod and an ECS container `command`
#   override both reach the JVM unchanged. What this image has no equivalent of is a
#   per-run token of the kind the batch image accepts, because this process is a
#   long-running HTTP listener rather than a job.
#   Runtime configuration arrives entirely through environment variables that the
#   ECS task definition supplies from Parameter Store and Secrets Manager. The values
#   this service cannot start without, because their placeholders declare no fallback,
#   are:
#     - SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
#     - SPRING_FLYWAY_USER, SPRING_FLYWAY_PASSWORD
#     - SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
#     - CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID
#     - AWS_REGION
#     - CARDDEMO_REFERENCE_INQUIRY_REQUEST_QUEUE,
#       CARDDEMO_REFERENCE_INQUIRY_REPLY_QUEUE and
#       CARDDEMO_REFERENCE_INQUIRY_ERROR_QUEUE
#   That is the complete set of no-fallback placeholders in application.yml with ONE
#   deliberate exclusion. CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD has no fallback either
#   and is omitted on purpose: the entry point mints the keystore and exports its
#   password itself, so a deployment that injected one would be overriding material
#   this task generates for its own single use.
#   Refactoring Rationale: SPRING_FLYWAY_USER and SPRING_FLYWAY_PASSWORD were absent
#   from this list while application.yml required them, which is the worst shape for a
#   list like this one -- it read as exhaustive and was not, so an operator following
#   it would have provisioned a task that fails during migration with a null
#   migration credential rather than at the missing variable.
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
#       docker build --file services/reference-service/Dockerfile \
#         --tag carddemo-reference-service:validation .
# WHY : Assumptions: the repository root is the build context, not services/,
#       because the image compiles the unpublished common-lib sibling from source
#       and the inherited Checkstyle documentation gate reads config/checkstyle,
#       which sits OUTSIDE the services reactor. Every COPY that reads from the
#       CONTEXT is therefore repository-root-relative and all of them fail if the
#       context is narrowed; the two `COPY --from=build` instructions are stage-
#       relative and are unaffected by the context.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the four rationale labels used throughout this file are the
#     plural, un-parenthesised forms of the Explainability rule's lines 31 to 34 --
#     Alternatives Considered, Refactoring Rationale, Assumptions and Trade-offs.
#     The reference-only baseline under tests/ and scripts/ tags three of the four
#     in the singular -- measured there as Assumption, Trade-off and Alternative
#     Considered, with Refactoring Rationale spelled identically in both -- and the
#     two spellings name the same categories, not different ones.
#     The equivalence is stated once here and the plural is then used without
#     exception, because docs/CODE_DOCUMENTATION_STANDARD.md requires one spelling
#     per file and config/rule1/rule1_gate.py fails the build on any other form --
#     a rationale a grep cannot find is a rationale a review cannot count.
#   - Alternatives Considered: narrowing the context to services/, the Maven
#     reactor root, which would send the daemon a far smaller tree. Rejected on a
#     measurement rather than a preference: config/checkstyle and config/docker both
#     sit OUTSIDE services/, so under that context the Checkstyle COPY and both
#     config/docker COPYs resolve to nothing and the build fails outright -- and the
#     only way to make it succeed would be to drop the documentation gate this
#     module is required to run. It is also the context that
#     .github/workflows/services-ci.yml and deploy.yml pass for all eight service
#     images, so this file and both pipelines agree about one root.
#     Trade-offs: the accepted cost is a genuinely larger context. The committed
#     root .dockerignore removes .git, build output and the tool caches, but it does
#     NOT exclude the reference-only trees, so app/, tests/ and samples/ are still
#     sent to the daemon on every build. That is stated plainly rather than claimed
#     away, because a reader who believed those trees were excluded would draw the
#     wrong conclusion about what an added exclusion here would cost.
#   - Trade-offs: two stages rather than one. Maven, the JDK compiler and the entire
#     resolved dependency cache stay out of the shipped image, which is the
#     difference between shipping a build toolchain to production and shipping a
#     jar; a single-stage image would also make every dependency it downloaded part
#     of a running service's attack surface. The cost is that the artifact must
#     cross a stage boundary by path, so this file is coupled to the module's jar
#     naming -- which is why the copy below names the version the POM sets and why
#     that copy is the line to change when the version moves.
#   - Assumptions: nothing here publishes. The only registry hostname in this file
#     belongs to the public base image, and the tag in the command above names no
#     account and no region, so no line of this file can be mistaken for a
#     publishable reference or carry an account-scoped registry host. The datasource
#     URL, the queue names, the issuer URI and every key ARN arrive at run time from
#     Parameter Store and Secrets Manager through the ECS task definition, which is
#     why this file declares no ENV and no ARG at all.
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
COPY services/pom.xml ./pom.xml
COPY services/common-lib/pom.xml ./common-lib/pom.xml
COPY services/reference-service/pom.xml ./reference-service/pom.xml
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory an aggregator declares
#       before -pl can select from it, so the copied parent must list only the two
#       modules whose directories exist in this stage. The source parent POM is
#       never modified; only the copy inside this layer is trimmed.
# WHY : Assumptions: exactly two module entries must survive (common-lib and
#       reference-service) and at least one must be dropped. The transformation ASSERTS
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
          || $0 ~ /<module>reference-service<\/module>/) { kept++; print } \
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
    && mvn -B -ntp -f pom.xml -pl common-lib,reference-service -am \
       dependency:go-offline

COPY services/common-lib/src ./common-lib/src
COPY services/reference-service/src ./reference-service/src

# WHY : Trade-offs: tests are skipped while packaging because the suite gate is
#       the repository-root reactor build `mvn -f services/pom.xml clean verify`,
#       whose Surefire and Failsafe executions cover all nine modules. This stage
#       sees only common-lib and reference-service, so running tests here would report a
#       narrower result under a name that reads like the full one.
# WHY : Refactoring Rationale: that count read "ten" and was wrong by measurement --
#       services/pom.xml declares NINE modules, common-lib plus eight services. Ten is
#       the number of ECR repositories and of committed Dockerfiles, because ui and
#       data-migration ship images while common-lib is a library that ships none, and
#       borrowing that figure here invented a tenth Maven module. The distinction is
#       worth keeping straight in both directions: a reader who believes there are ten
#       modules goes looking for one that does not exist, and a reader who believes
#       there are nine images builds one too few.
# WHY : Assumptions: the parent reactor at services/pom.xml is the build root, and
#       `-am` is what makes common-lib a required upstream of this module rather than
#       a second invocation to sequence by hand. common-lib is unpublished -- no
#       registry holds a 1.0.0-SNAPSHOT of it -- so `-pl reference-service` alone
#       would fail to resolve the dependency instead of building it, and the two
#       modules must be compiled in one reactor pass for the version to match.
# WHY : Assumptions: -DskipTests suppresses test EXECUTION only - it does not skip
#       Checkstyle. The documentation gate stays bound to `validate`, which runs
#       before `package`, so it still executes inside this stage. The two must not be
#       conflated: no skip flag for that gate, no severity downgrade and no
#       return-code tolerance may be added here or anywhere, and in particular none
#       may be added alongside the flag above for the sake of looking consistent -
#       doing so would silently delete the one gate this image build keeps. It is kept
#       because a missing Javadoc is a defect in the sources being compiled here
#       rather than a statement about behavior.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,reference-service -am \
    clean package -DskipTests

# WHY : Assumptions: the publisher provides no Alpine variant of this Corretto
#       image. The repository publishes only -al2 and -al2023 tags with headful,
#       headless, generic and jdk suffixes, so `21-alpine` is not a smaller
#       alternative to weigh -- it is a tag that does not resolve, and substituting
#       it for image-size reasons fails the build outright rather than producing a
#       leaner image. 21.0.12 is the highest published 21.x tag.
# WHY : Trade-offs: headless rather than headful or generic. A headless JRE omits
#       AWT and Swing, and this process serves HTTP and consumes SQS, so it loads
#       no class from either - the capability given up is one this context cannot
#       reach. What is accepted in exchange is that a future need for server-side
#       image rendering would require changing this tag rather than adding a
#       dependency, which is a visible edit here instead of a silent one downstream.
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
       'carddemo:!:10001:10001:CardDemo reference:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: the owned application directory is the working directory so
#       any relative temporary output stays inside the only application path the
#       fixed identity may modify.
WORKDIR /app

# WHY : Assumptions: reference-service-1.0.0-SNAPSHOT.jar is the artifact this module's POM
#       produces and the Boot repackage goal makes it self-contained, so copying
#       that one file excludes sources, build caches, reports and every reactor
#       target directory from the shipped image.
COPY --from=build --chown=10001:10001 \
    /build/services/reference-service/target/reference-service-1.0.0-SNAPSHOT.jar \
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
USER carddemo:carddemo

# WHY : Trade-offs: the container start period below is 30 seconds even though this
#       context applies the only two-migration Flyway chain in the tree - V2 seeds the
#       transaction types, the categories, the mandatory 'DEFAULT' disclosure-group
#       row and the address allow-lists drawn from the 490 codes of
#       app/cpy/CSLKPCDY.cpy. A longer period was considered and rejected: the health
#       endpoint reports not-ready while Flyway runs, and the retry budget below
#       already covers that, so lengthening the period would only delay detection of a
#       genuinely stuck start.
# WHY : Refactoring Rationale: this note used to justify 30 seconds as "the same as
#       every sibling", which is not true and would have misled the next reader to
#       change it. Measured across services/*/Dockerfile: six siblings use 30s and
#       reporting-service uses 60s, because it renders statements rather than serving
#       lookups. The claim is restated as the reason this value fits THIS context, so
#       it no longer rests on a uniformity that does not exist.
# WHY : Assumptions: spring-boot-starter-actuator is on the classpath -- declared in
#       services/reference-service/pom.xml, whose own comment records that the
#       endpoint is read by BOTH the ALB target group and this container check -- and
#       the ALB probes the SAME /actuator/health path. That shared path is the point:
#       two probes reading one endpoint cannot reach opposite verdicts about one
#       instance, whereas a container check on a bespoke path could report healthy
#       while the load balancer drained the task, or the reverse.
# WHY : Alternatives Considered: the probe tool. curl is used because /usr/bin/curl is
#       present in this pinned digest -- verified by running it, which also confirms
#       wget is ABSENT, so a wget probe would fail on every task. Installing a tool
#       was rejected because it would add a package layer to the shipped image for a
#       binary already in it, and a shell /dev/tcp probe was rejected because it
#       proves only that the port accepts a connection: the connector answers before
#       Flyway finishes, so a TCP probe would report healthy during migration and the
#       HTTP status is what distinguishes listening from ready. curl also fails
#       explicitly on a non-success status thanks to --fail.
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
# WHY : Refactoring Rationale: SPRING_PROFILES_ACTIVE is deliberately NOT set in this
#       image, and the argument above is one of the two ways it arrives instead. What
#       is wrong with baking it into a layer is not style: this exact artifact is
#       promoted from dev to prod by digest, so a fixed profile would force either two
#       images built from one commit -- which makes the digest promoted to prod one
#       nobody tested -- or a stale layer, rather than the task definition, deciding
#       which environment's configuration a task loads. Leaving it unset is what keeps
#       promote-the-same-digest true, and it is why this file declares no ENV at all.
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
