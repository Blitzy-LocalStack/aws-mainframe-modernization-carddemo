# =============================================================================
# services/authorization-service/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image for the CardDemo pending-authorization
#   bounded context, packaging the executable archive this module's POM produces
#   into a runnable ECS Fargate task.
#
# Source of truth:
#   services/authorization-service/pom.xml declares the artifact this image
#   ships. The behaviour that artifact re-expresses comes from the eight COBOL
#   programs of the decoupled authorization extension, cited here by path
#   because they are the specification for what is built:
#     app/app-authorization-ims-db2-mq/cbl/COPAUS0C.cbl   summary list
#     app/app-authorization-ims-db2-mq/cbl/COPAUS1C.cbl   detail view
#     app/app-authorization-ims-db2-mq/cbl/COPAUS2C.cbl   mark fraud
#     app/app-authorization-ims-db2-mq/cbl/COPAUA0C.cbl   queue consumer
#     app/app-authorization-ims-db2-mq/cbl/CBPAUP0C.cbl   expiry purge
#     app/app-authorization-ims-db2-mq/cbl/PAUDBLOD.CBL   segment load
#     app/app-authorization-ims-db2-mq/cbl/PAUDBUNL.CBL   segment unload
#     app/app-authorization-ims-db2-mq/cbl/DBUNLDGS.CBL   GSAM unload
#   Those eight are REFERENCE-ONLY. No COPY instruction below names app/,
#   tests/, scripts/ or samples/, so no baseline file enters a layer and none is
#   modified. They nevertheless remain IN the build context on purpose:
#   .dockerignore:L66-L73 records the decision to retain them so that a reader
#   running this build can verify a cited path from inside the same context.
#
# Parameters:
#   This image REQUIRES no command argument: the process is a long-running HTTP
#   listener, not a job, so it takes no per-run token of the kind the batch
#   image accepts. Any argument that is supplied is forwarded verbatim, because
#   the entry point is the shared listener-material script whose final line
#   replaces itself with the JVM and passes "$@" through.
#   Runtime configuration arrives entirely through environment variables that the
#   ECS task definition supplies. The values this service cannot start without,
#   because their placeholders declare no fallback, are:
#     - SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
#     - SPRING_FLYWAY_USER, SPRING_FLYWAY_PASSWORD -- the migration identity is
#       SEPARATE from the runtime identity above and is not defaulted to it, so the
#       schema owner's credential is never the credential the request path holds.
#     - SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI
#     - CARDDEMO_SECURITY_JWT_EXPECTED_CLIENT_ID
#     - CARDDEMO_MESSAGING_PAUTH_REQUEST_QUEUE
#     - CARDDEMO_MESSAGING_REPLY_QUEUE_ALLOWLIST -- the closed set of reply
#       destinations this consumer will honour. It is a start-time input and not a
#       default because an empty or permissive default would let a request name its
#       own reply queue, which is the confused-deputy shape this allowlist exists to
#       refuse.
#     - CARDDEMO_MESSAGING_HMAC_KEY -- the key the request and reply payloads are
#       authenticated with, replacing the trust the baseline placed in the queue
#       manager itself.
#     - CARDDEMO_ACCOUNT_CONTEXT_BASE_URL -- an authorization decision reads account
#       state this context does not own, so the account context's address is a
#       start-time input.
#     - CARDDEMO_INTERNAL_IDENTITY_AUTHORIZATION_SIGNING_KEY -- the key this context
#       signs its outbound service-to-service assertions with.
#   The list above is the COMPLETE set of no-fallback placeholders across
#   application.yml and application-prod.yml, with ONE deliberate exclusion, and the
#   completeness is mechanically held rather than asserted: common-lib's
#   RuntimeConfigurationContractTest re-derives the placeholder set from those two
#   files and fails the build if any member of it is absent from this file.
#   The one exclusion is CARDDEMO_SERVER_TLS_KEYSTORE_PASSWORD. It has no fallback
#   either, but it is NOT an operator-supplied value and belongs in a different
#   category from every name above: config/docker/generate-listener-material.sh --
#   the entry point named below -- mints the keystore and exports its password itself
#   for this one task's single use, so a deployment that injected one would be
#   overriding material the task generates for itself. The name is documented HERE,
#   as entrypoint-supplied, rather than listed among the values a deployment passes.
#   One further input is required, is NOT an application.yml placeholder, and is
#   therefore easy to miss when reading only the list above:
#     - CARDDEMO_PAGINATION_CURSOR_SIGNING_KEY, the environment form of the property
#       carddemo.pagination.cursor.signing-key, base64 over at least 32 bytes. The
#       shared kernel publishes the keyset-cursor signer only when that property is
#       set -- the bean carries @ConditionalOnProperty on it and the kernel defaults
#       it nowhere, deliberately, so that no deployment can sign cursors with a key
#       another deployment also holds. Omitting it does not silently degrade the
#       pending-authorization summary's paging: it removes the bean,
#       PendingAuthViewMapper requires one, and the context then fails to start.
#   Every entry above is a variable NAME. No value, and therefore no credential,
#   connection string, issuer URI, queue URL or key, appears anywhere in this file.
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
# Deployment lineage:
#   This image replaces a load library, not a server. The baseline's deployable
#   unit was a member of AWS.M2.CARDDEMO.LOADLIB, named at
#   app/csd/CARDDEMO.CSD:L491 and loaded into a CICS region whose extension
#   resources are declared in
#   app/app-authorization-ims-db2-mq/csd/CRDDEMO2.csd. The deployable unit here
#   is this image in Amazon ECR.
#
# Concurrency, and why it improves on the baseline:
#   The baseline funnelled this feature through ONE CICS DB2 thread:
#   CRDDEMO2.csd:L72 declares THREADLIMIT(1) THREADWAIT(YES) on DB2ENTRY
#   AWS01PLN, so a second authorization waited for the first to finish. This
#   image inherits no such ceiling. The ECS service runs several identical tasks
#   behind an internal load balancer, and the context keeps no server-side
#   session state -- the CICS communication area decomposed into signed token
#   claims, request-path parameters and client-side routing -- so an added task
#   is immediately useful. That statelessness is also why this image declares no
#   volume and needs neither session affinity nor a shared filesystem.
#
# Boundaries -- what this file deliberately does NOT do:
#   Building it is a VALIDATION-only build. It proves the definition parses, the
#   pins resolve and the archive assembles; it publishes nothing and it is not
#   the test gate (see the packaging step for where the suite runs). Publication
#   belongs to .github/workflows/deploy.yml, which pushes to Amazon ECR after
#   assuming a deployment role through short-lived OIDC federation. No
#   long-lived registry credential exists in this repository, in this file or in
#   any layer it produces, and no endpoint, queue URL, ARN or account identifier
#   is baked in: every runtime value arrives from Parameter Store or Secrets
#   Manager through the environment variables named above.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md, whose Dockerfile section requires a
#   header plus a justification on the base-image pin and on layer ordering.
#   That obligation is the XML/HCL-style analogue defined by that standard and
#   by the migration plan; it is NOT claimed from the project explainability
#   rule, whose format clause names Javadoc, JSDoc, Python docstrings and C# XML
#   comments and is silent on Dockerfiles. The rule's inline-comment clauses do
#   apply in full, so every justification below carries one of exactly four
#   labels: Alternatives Considered, Refactoring Rationale, Assumptions,
#   Trade-offs.
#
# WHAT: From the repository root, build this image with:
#       docker build --file services/authorization-service/Dockerfile \
#         --tag carddemo-authorization-service:validation .
# WHY : Assumptions: the repository root is the build context, not services/,
#       because the image compiles the unpublished common-lib sibling from source
#       and the inherited Checkstyle documentation gate reads config/checkstyle,
#       which sits OUTSIDE the services reactor. Every COPY below is therefore
#       repository-root-relative and all of them fail if the context is narrowed.
# =============================================================================

# WHY : Trade-offs: both stages are pinned to an exact version AND a digest,
#       never to a floating or `latest` tag. What is given up is automatic pickup
#       of a newer patch level, which now costs an edit here that a reviewer
#       sees. What is bought is that one commit builds one runtime. This
#       repository already holds itself to that standard for its Python test
#       dependencies, calling exact pinning "a hard financial-grade requirement,
#       because a floating dependency could silently break the byte-deterministic
#       golden comparisons" (tests/README.md:L177-L187). The exposure is the same
#       here for a stronger reason: this context posts money, so an unpinned JVM
#       is an unpinned decimal and rounding implementation.
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
COPY services/authorization-service/pom.xml ./authorization-service/pom.xml
COPY config/checkstyle /build/config/checkstyle

# WHY : Assumptions: Maven validates every module directory an aggregator declares
#       before -pl can select from it, so the copied parent must list only the two
#       modules whose directories exist in this stage. The source parent POM is
#       never modified; only the copy inside this layer is trimmed.
# WHY : Assumptions: exactly two module entries must survive (common-lib and
#       authorization-service) and at least one must be dropped. The transformation ASSERTS
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
          || $0 ~ /<module>authorization-service<\/module>/) { kept++; print } \
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
    && mvn -B -ntp -f pom.xml -pl common-lib,authorization-service -am \
       dependency:go-offline

# WHY : Trade-offs: this is the CLOSING half of the layer-ordering decision opened
#       at the POM copies above, and it is placed here so the ordering is legible
#       from both ends rather than only from the top. Sources are copied AFTER
#       dependency resolution, which is what confines an ordinary code change to
#       the two layers below and leaves the resolved dependency layer intact. The
#       compromise is that these two directories are the widest thing this stage
#       copies, so any edit under either invalidates everything after this line;
#       the alternative of copying sources alongside the POMs would additionally
#       invalidate the dependency resolution, turning every one-line change into a
#       full re-resolve.
# WHY : Assumptions: both source trees are required because this module compiles
#       against the shared kernel from source rather than resolving a published
#       artifact for it, which is the same assumption that makes the repository
#       root, and not services/, the correct build context.
COPY services/common-lib/src ./common-lib/src
COPY services/authorization-service/src ./authorization-service/src

# WHY : Trade-offs: tests are skipped while packaging because the suite gate is
#       the repository-root reactor build `mvn -f services/pom.xml clean verify`,
#       whose Surefire and Failsafe executions cover every module in that reactor.
#       This stage selects only common-lib and this module, so running tests here
#       would report a narrower result under a name that reads like the full one.
#       No count is written here deliberately: a figure in the file that CONFIGURES
#       a gate goes stale the moment a module is added, and a reader reconciling it
#       against a build log cannot then tell whether the gate or the comment is
#       wrong. The reactor command above is the authority and cannot go stale.
# WHY : Assumptions: Checkstyle stays bound to `validate` and therefore still runs
#       inside this stage, before `package`, even under -DskipTests. It is the one
#       gate this image build keeps, because a missing Javadoc is a defect in the
#       sources being compiled here rather than a statement about behavior.
RUN mvn -B -ntp --offline -f pom.xml -pl common-lib,authorization-service -am \
    clean package -DskipTests

# WHY : Assumptions: THIS TAG IS NOT SIMPLIFIABLE, and the reason is a property
#       of the publisher's registry rather than a preference. The ECR Public
#       amazoncorretto repository publishes ONLY `-al2` and `-al2023` operating
#       system families, each carrying a `headful`, `headless`, `generic` or
#       `jdk` suffix. It publishes NO Alpine variant at all. An earlier draft of
#       the migration plan named `21-alpine`, reading it as the obvious smaller
#       base; that tag does not exist, so it is not a smaller alternative but a
#       reference that fails to resolve and would have failed every image build.
#       A reader shortening this to `21-alpine`, `:21` or `:latest` therefore
#       breaks the build rather than relaxing it. Verified against the registry
#       tag listing, not inferred from documentation.
# WHY : Assumptions: 21.0.12 is the highest published 21.x patch level in that
#       family. Note that it does not sort last as a string - `21.0.9` compares
#       greater than `21.0.12` lexically - so a version check written as a text
#       comparison will wrongly report this pin as behind.
# WHY : Assumptions: the `headless` suffix, not `headful` or `jdk`. This process
#       serves HTTP and renders nothing, so it needs no AWT, X11 or font stack,
#       and it runs a packaged archive rather than compiling, so it needs no
#       compiler either. Headless is both the smaller image and the accurate
#       description of what the process uses.
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
       'carddemo:!:10001:10001:CardDemo authorization:/app:/bin/false' >> /etc/passwd \
    && mkdir -p /app \
    && chown 10001:10001 /app

# WHY : Assumptions: the owned application directory is the working directory so
#       any relative temporary output stays inside the only application path the
#       fixed identity may modify.
WORKDIR /app

# WHY : Trade-offs: THIS single COPY is what earns the two-stage structure, so
#       the trade is worth stating where it is paid. A one-stage image would be
#       simpler to read and would keep Maven, the JDK's compiler and the whole
#       resolved dependency cache available inside the shipped result for
#       debugging. That is given up deliberately, because those bytes are pulled
#       by the ECS agent on every task placement and on every scale-out event:
#       the mechanism is image pull bytes translating directly into task start
#       latency, and into how long a scale-out lags the load that triggered it.
#       What is kept is a runtime layer holding no build tooling at all, which
#       also removes a compiler and a package resolver from the running task's
#       attack surface.
# WHY : ⚠️ Refactoring Rationale: this path used to spell the version out --
#       authorization-service-1.0.0-SNAPSHOT.jar -- a value this module inherits
#       from services/pom.xml and never declares. A version bump in the parent is
#       a routine edit that touches no file in this module, and it broke this
#       instruction, which is the LAST one in the build: the whole reactor had
#       already compiled, and the error read as a missing artifact rather than as
#       a stale literal. services/authorization-service/pom.xml now pins
#       <finalName>authorization-service</finalName>, so this path is deterministic
#       and independent of the version. The two files are a contract and neither
#       may change the name alone.
# WHY : Assumptions: the Boot repackage goal has made that one archive
#       self-contained, so copying exactly it is sufficient and it leaves sources,
#       build caches, test reports and every other reactor target directory behind
#       in the discarded stage. A renamed artifactId or a withdrawn final name
#       breaks this path at build time rather than silently shipping an empty
#       image.
COPY --from=build --chown=10001:10001 \
    /build/services/authorization-service/target/authorization-service.jar \
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

# WHY : Assumptions: this container does work that ARRIVES ON NO HTTP REQUEST - it
#       consumes an ordered authorization queue and drains a transactional outbox -
#       which is what makes the probe below load-bearing rather than ceremonial. The
#       failure it exists to catch is a task whose QUEUE consumption has stopped
#       while its HTTP connector still answers normally: no caller is waiting on a
#       reply, so nothing else in the system reports it, and the work simply stops
#       being done. The probe's datasource-aware response is what surfaces that.
#       Refactoring Rationale: this note previously called that property unique to
#       this image, which was measurably wrong - account-service, reference-service
#       and batch-service also declare queue listeners. The claim is narrowed to
#       what is true and load-bearing here, because a reader who believed the
#       uniqueness would conclude the sibling images needed no equivalent probe.
# WHY : Assumptions: no message payload is ever written to an image layer. The
#       eighteen-field authorization request carries a sixteen-digit primary
#       account number, so the package charter forbids logging a payload
#       verbatim; an image that cached one for debugging would defeat that
#       discipline permanently, since layers outlive the container.
# WHY : Assumptions: /actuator/health has TWO independent out-of-process consumers,
#       and that is why the path is not a free choice here. This HEALTHCHECK is one;
#       the internal load balancer's target group is the other, and it decides
#       whether the task is in service at all. Both resolve through
#       spring-boot-starter-actuator, declared in this module's POM, so dropping
#       that dependency does not degrade one probe - it breaks both at once, and the
#       presenting symptom is a deployment that rolls itself back with no
#       application error in the logs because every probe simply received a 404.
# WHY : Assumptions: the pinned headless runtime contains /usr/bin/curl - verified
#       inside this exact digest, which also confirms wget is absent - so the probe
#       needs no package installation and fails explicitly on a non-success status.
#       That verification is why no package is installed here: had curl been absent,
#       this instruction would have been a check that could never pass, which leaves
#       a task permanently unhealthy and is worse than declaring no check at all.
# WHY : Assumptions: the probe speaks HTTPS, not HTTP. server.ssl.enabled is true
#       in the base profile, so the connector is TLS and a plaintext request would
#       fail the check on every healthy task - a probe that always fails is worse
#       than none, because it makes a working deployment look broken.
# WHY : Trade-offs: --insecure is accepted for this one request. The certificate is
#       the self-signed leaf the entry point mints for THIS task, and the reason
#       verification cannot succeed is that nothing in this image anchors it --
#       config/docker/generate-listener-material.sh:164 mints it with
#       `SAN=dns:<cn>,dns:localhost,ip:127.0.0.1`, so the loopback address this
#       probe dials IS carried by the certificate.
# WHY : Refactoring Rationale: this note previously reported a MEASUREMENT that
#       does not reproduce -- curl exit 60 with "no alternative certificate subject
#       name matches target ipv4 address 127.0.0.1". Re-measured against the pinned
#       digests, using the generator's own keytool arguments and a loopback TLS
#       listener, the identical request without --insecure exits 60 reporting "SSL
#       certificate OpenSSL verify result: self-signed certificate (18)" and
#       produces no subject-name error. A false measurement is worse than an
#       unsupported claim, because it is the thing a later reader trusts instead of
#       re-checking, and the two causes have different fixes: a name mismatch is
#       corrected in the certificate, an unanchored leaf only by trusting it
#       explicitly or waiving verification as this probe does.
# WHY : Alternatives Considered: exporting the minted leaf at start-up and
#       verifying with --cacert, which the same measurement shows DOES succeed on
#       loopback. Rejected because CMD is a static exec array, so the PEM path would
#       be a literal here while the directory holding it is CARDDEMO_SERVER_TLS_DIR,
#       an overridable variable; a deployment that moved that directory would fail
#       the probe on a healthy task. What is given up is identity checking on a
#       loopback call inside the container's own network namespace, where there is no
#       second party to impersonate; what is kept is that the ENCRYPTED listener is
#       the one proven to answer, which a plaintext probe would not establish.
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
# WHY : Assumptions: heap sizing is delegated to the JVM's own container
#       awareness. Java 21 reads the cgroup memory limit the task definition
#       imposes and derives the heap from it, so this image states no size at
#       all. That is a dependency on runtime behavior worth naming, because it is
#       the reason an apparently missing setting is not an omission.
# WHY : Alternatives Considered: baking a -Xmx or -XX ceiling into the image was
#       rejected. A hard-coded ceiling overrides the cgroup limit rather than
#       tracking it, so the task definition's memory and the image would both
#       have to be edited together to change one number, and the failure mode of
#       editing only one is a container that either wastes its allocation or is
#       killed for exceeding a limit the JVM never saw.
# WHY : Trade-offs: tuning therefore arrives through the JAVA_OPTS environment
#       variable, which the entry point word-splits ahead of -jar. The compromise
#       accepted is that an operator can pass a flag this file never reviewed;
#       what it buys is that changing a JVM flag is a task-definition edit rather
#       than an image rebuild, retag and redeploy, so no image is respun to move
#       one flag.
ENTRYPOINT ["/usr/local/bin/carddemo-entrypoint.sh"]
