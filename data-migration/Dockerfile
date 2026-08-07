# =============================================================================
# data-migration/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Container image for `carddemo-migration`, the Python ETL that carries
#   CardDemo's mainframe data into the migrated stack. It decodes the eleven
#   fixed-width record layouts declared by the COBOL copybooks -- zoned decimal
#   with sign overpunch, COMP-3 packed decimal, and EBCDIC code pages decoded per
#   field -- bulk-loads each record set into its owning Aurora PostgreSQL schema,
#   stages the generation-dataset families into versioned S3, and verifies the
#   load three independent ways: row counts per dataset, record checksums, and
#   money-total parity against the source files.
#
#   Assumptions: this is a BATCH image, not a service. It has no listener, no
#   exposed port and no readiness endpoint. The migrated batch state machine
#   starts it through the synchronous `ecs:runTask.sync` integration -- once per
#   dataset in the `StageSeedDatasets` Map state -- and judges the result by the
#   task's terminal state and exit code alone. Every decision below is arranged
#   around a short-lived process whose stdout is its only progress channel, and
#   each is justified where it is made rather than previewed here.
#
#   Assumptions: NOTHING enters this image unverified. Three separate supply
#   chains are each locked by digest -- the base image by manifest digest, every
#   Python distribution by artifact sha256 under `--require-hashes`, and the
#   Aurora certificate bundle by a pinned content sha256. The one deliberate
#   exception is the libpq5 apt package, whose version is governed by the pinned
#   base image's suite, for the reason recorded at that instruction. Nothing here
#   names a host, an account, a queue, a bucket or a credential, and no ENV
#   carries a connection default: the ETL resolves those at task launch from
#   Systems Manager Parameter Store and Secrets Manager, which is what makes a
#   checkout of this repository inert on its own.
#
# Build and smoke-test (run from this directory, which IS the build context):
#   docker build -t carddemo-migration:local .
#   docker run --rm carddemo-migration:local --help
# =============================================================================


# -----------------------------------------------------------------------------
# STAGE 1 -- builder: resolve the pinned closure, install the distribution.
# -----------------------------------------------------------------------------
# Trade-offs: a single-stage image would be shorter to read and is rejected for
#   what it would LEAVE BEHIND rather than for its size. A PEP 517 install
#   resolves a build backend into the image and needs the SOURCE tree present in
#   order to build the distribution at all; splitting the build off means the
#   shipped image holds the installed package and none of that machinery, so
#   there is no build backend in it to keep patched and no second copy of the
#   source that could shadow the installed package at import time. The accepted
#   costs are a longer build graph and one copy step between stages. What the
#   split does NOT buy, so the claim is not overstated: every distribution in the
#   closure publishes a wheel for this platform, so no compiler is installed in
#   either stage and none is being excluded.
# -----------------------------------------------------------------------------

# Assumptions: this pin is a PARITY decision. The ETL decodes money with the
#   standard library's `decimal`, `codecs` and `struct` modules and its output is
#   verified against the COBOL test suite that serves as this migration's
#   functional oracle, which is recorded as validated on CPython 3.13
#   (tests/README.md section 3 -- reference material, never modified and never
#   copied into this image). Holding the image on the same interpreter MINOR
#   series keeps those three modules' semantics identical on both sides, so a
#   discrepancy in verification output has to be investigated as a real data
#   difference rather than written off as an interpreter gap. The claim is made at
#   the 3.13 minor level and no further: the oracle's recorded patch release and
#   this tag's are not the same number, and neither is adjusted to agree.
# Alternatives Considered: a musl-based slim variant. Rejected -- the ETL's
#   PostgreSQL driver is the PLAIN psycopg distribution, which is pure Python and
#   resolves libpq at run time rather than vendoring it, so the image has to
#   supply libpq itself. A musl base changes the C-library surface that driver
#   binds to and pushes the build toward compiling the driver from source,
#   trading a verified wheel-only install for a toolchain this image otherwise
#   has no use for.
# Assumptions: BOTH stages name this reference and must go on naming the SAME
#   one. It is not duplication that can be tidied into a single place: the runtime
#   stage adopts this stage's virtual environment by copying it, and a virtual
#   environment records the absolute path of the interpreter that created it, so
#   two different bases would build against one interpreter and execute against
#   another, failing at import time rather than at build time.
# Assumptions: the DIGEST is what actually pins this, not the tag. A tag is a
#   mutable pointer -- `3.13.14-slim-trixie` is republished whenever the Debian
#   trixie base beneath it is rebuilt -- so two builds of this identical
#   Dockerfile could otherwise resolve different bytes with nothing in the file
#   or the build log distinguishing them. For this image that is not merely
#   irreproducibility: the parity oracle compares by record checksum and money
#   total, so a silent interpreter or library change would present as a
#   DATA-PARITY failure whose actual cause is the base image. The digest names an
#   OCI image INDEX rather than one platform's manifest, so it stays correct for
#   every architecture this image is built for.
#   Trade-offs: a security rebuild of the base now requires editing this file
#   twice rather than rebuilding against a moving tag. That is the intended cost:
#   adopting a new base becomes a reviewed commit that says which bytes were
#   adopted, and the tag is retained beside the digest so a reader can still see
#   WHICH release the digest belongs to. 3.13.14 was confirmed to be the newest
#   3.13 slim-trixie release at the time of pinning, so this is a current base and
#   not a frozen old one.
#   Assumptions: current does NOT mean finding-free, and the difference is stated
#   here because a digest pin invites the opposite assumption. This exact base,
#   scanned at medium severity and above, carries 79 known Debian-package findings
#   of which 4 are critical; being the newest 3.13
#   slim-trixie release means there is no newer patch of the same line to move to,
#   not that the line is clean. What the runtime stage removes from that set, what
#   it deliberately does not, and why, are recorded at the apt instruction in that
#   stage. This stage keeps the full set because it is discarded: nothing from it
#   reaches a published artifact except the virtual environment and one verified
#   certificate bundle.
FROM python:3.13.14-slim-trixie@sha256:6771159cd4fa5d9bba1258caf0b82e6b73458c694d178ad97c5e925c2d0e1a91 AS builder

# Assumptions: deliberately NOT the environment's own path. Keeping the checkout
#   at /opt/carddemo while the environment sits at /opt/venv stops the two from
#   overlapping, which is what lets the runtime stage copy the environment on its
#   own and leave every trace of the build behind. A working directory that
#   contained the environment would make that single-directory hand-off
#   impossible to express without shipping the source along with it.
WORKDIR /opt/carddemo

# Assumptions: the runtime stage relocates exactly ONE directory, so holding the
#   installed set inside a single self-contained prefix reduces that hand-off to
#   one instruction with nothing to enumerate. Installing into the base
#   interpreter's site-packages would instead require copying two directories
#   whose paths embed the interpreter version, so a base image bump would
#   silently copy nothing and produce an image that fails only when it runs.
#   `--upgrade-deps` is deliberately not passed: it would pull unpinned installer
#   releases into the build, contradicting the exact-pin doctrine every dependency
#   in this tree is held to.
RUN python -m venv /opt/venv

# Assumptions: prepending the environment is what makes a bare `pip` here, and a
#   bare `python` in the runtime stage's ENTRYPOINT, resolve to the interpreter
#   that can see the installed distribution. Naming /opt/venv/bin/python
#   absolutely at each use site would behave identically but would spread the
#   environment's location across several instructions instead of stating it once.
ENV PATH="/opt/venv/bin:${PATH}"

# Trade-offs: copying build manifests ahead of application source preserves the
#   dependency layer across ordinary source edits, at the cost of an extra COPY
#   instruction and a build context read in two passes. A build invalidates every
#   layer from the first one whose inputs changed onwards, so copying the source
#   first would re-run the entire dependency install on every edit to a reader or
#   a codec -- the files that change most often -- while these manifests change
#   rarely.
# Assumptions: requirements-dev.txt is deliberately NOT copied. It is the
#   lint-and-test closure, this image runs neither, and a manifest absent from the
#   build context cannot be installed by a later edit that reaches for it out of
#   convenience. Continuous integration installs it in a checkout instead.
COPY requirements.txt requirements-build.txt ./

# Assumptions: requirements.txt is the AUTHORITATIVE version set -- exact `==`
#   pins throughout -- whereas pyproject.toml states compatibility FLOORS for an
#   installer that resolves this package on its own. Installing the manifest first
#   fixes every version at its pinned value, so the package install below cannot
#   raise any of them.
# Assumptions: `--require-hashes` is the point of this instruction. A version pin
#   names a version while the index names an artifact, so a compromised index or
#   hijacked maintainer account can answer that name with different bytes,
#   silently, in an image that then holds a database credential and every account,
#   customer, card and transaction row it loads. With every artifact hashed the
#   install either reproduces the reviewed bytes or fails. The flag is passed
#   explicitly even though pip enters hash-checking mode once any requirement
#   carries a hash, because the explicit form makes a future unhashed addition an
#   immediate error rather than a silent downgrade of the whole manifest to
#   version-only pinning.
# Assumptions: `--only-binary :all:` turns "no compiler is required" from a lucky
#   property into an enforced one. Every distribution in this closure publishes a
#   wheel for CPython 3.13 on this platform, so it changes nothing about what gets
#   installed today; what it changes is the failure mode should a future pin lack
#   a wheel -- the installer reports no matching distribution immediately instead
#   of starting a source build that dies deep inside a toolchain this image never
#   provides. It is applied HERE only and not to the package install below,
#   because that one legitimately builds a local source tree.
# Assumptions: the manifest installed here is the RUNTIME closure alone, and the
#   lint-and-test closure is a separate FILE rather than a parsed section of this
#   one, because a file boundary cannot be moved by an edit to a comment. What a
#   combined manifest would cost is not image size but attack surface: code
#   present in a production image that no production run imports.
# Trade-offs: `--no-cache-dir` gives up nothing real. A download cache only pays
#   off across repeated installs in one environment and this layer installs once,
#   so retaining it would merely leave archives inside the layer.
RUN pip install --no-cache-dir --require-hashes --only-binary :all: -r requirements.txt

# Assumptions: this package uses a src layout -- pyproject.toml declares
#   `where = ["src"]` -- so the directory below is the whole importable tree and
#   there is nothing further to bring in for the build. pyproject.toml declares
#   neither a `readme` nor a `license` file, which is what allows these two paths
#   alone to constitute a buildable source distribution.
COPY pyproject.toml ./
COPY src/ ./src/

# Assumptions: the wheel is built with a hash-verified backend that never enters
#   the shipped environment, then installed offline. An ordinary PEP 517 install
#   resolves the build backend named in pyproject.toml by fetching whichever
#   release satisfies an unbounded floor that day, checked against nothing -- and
#   a build backend executes arbitrary code during the build and produces the
#   artifact that is then installed and shipped, so it has the broadest reach of
#   any component in the image. The three steps below close that: the backend is
#   installed from a hash-locked manifest, `--no-build-isolation` makes THAT copy
#   the one that runs rather than a freshly fetched one, and the wheel is
#   installed with `--no-index` so the final step provably reaches no network.
# Assumptions: the backend goes into a SEPARATE environment at /opt/build rather
#   than /opt/venv, and that separation is the whole reason there are two
#   environments. `--no-build-isolation` requires the backend to be present in the
#   environment running the build, so installing it into /opt/venv would satisfy
#   pip and then ship the build backend inside the runtime image -- reintroducing
#   exactly the tooling the two-stage split exists to keep out, into the one
#   environment that IS copied forward.
# Assumptions: `--no-deps` on the wheel install protects the pin doctrine.
#   pyproject.toml declares its dependencies as floors on purpose, so a
#   dependency-resolving install here would be free to satisfy those floors with
#   NEWER releases than the exact versions installed above, silently replacing
#   them. The layer above already installed the authoritative set, so this has
#   exactly one thing left to add: the package.
# Trade-offs: the three steps are one RUN instruction rather than three, because
#   the wheel path is produced by the first step and consumed by the third, so
#   splitting them would require naming that path in two instructions instead of
#   one shell variable.
RUN python -m venv /opt/build \
 && /opt/build/bin/pip install --no-cache-dir --require-hashes --only-binary :all: \
      -r requirements-build.txt \
 && /opt/build/bin/pip wheel --no-cache-dir --no-deps --no-build-isolation \
      --wheel-dir /tmp/wheel . \
 && pip install --no-cache-dir --no-deps --no-index \
      --find-links /tmp/wheel carddemo-migration

# Assumptions: the AWS-published Aurora certificate bundle is required, and is
#   verified against a pinned sha256 before the runtime stage adopts it. The ETL
#   connects with `sslmode=verify-full` and an explicit `sslrootcert`, and
#   src/carddemo_migration/config.py resolves that path from DEFAULT_SSL_ROOT_CERT
#   -- the literal target below -- and REFUSES to produce connection settings when
#   the file is absent, so an image built without this layer does not degrade to
#   an unverified connection: it fails to run.
# Alternatives Considered: trusting the operating system's CA store instead.
#   Rejected as too broad -- the system store trusts every publicly trusted
#   authority, so a certificate issued by any one of them for the cluster's
#   endpoint name would verify, whereas this bundle trusts only the authorities
#   that can legitimately certify an Aurora endpoint. Also considered: baking no
#   bundle and mounting one at run time, rejected because it makes a
#   security-critical file an operator step that can be forgotten, and the failure
#   would surface as a broken batch window.
# Assumptions: an unverified fetch would be self-defeating in the most literal
#   way -- a trust anchor obtained over an unauthenticated channel anchors nothing
#   -- so the digest is checked, with the standard library rather than with curl,
#   sha256sum or gpg, which is what keeps this layer from adding a package to the
#   build stage.
#   Trade-offs: AWS republishes this bundle when a certificate authority is
#   rotated, and on that day this build fails with a digest mismatch until the
#   value is refreshed. That is the correct behaviour and the same trade-off the
#   pinned base-image digest and the pinned wheel hashes make. Refreshing it is
#   one command whose output goes on the EXPECTED line below --
#   `python -c "import hashlib,urllib.request as u;
#   print(hashlib.sha256(u.urlopen(URL).read()).hexdigest())"`.
RUN python - <<'PYVERIFY'
import hashlib
import urllib.request

URL = "https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem"
EXPECTED = "e5bb2084ccf45087bda1c9bffdea0eb15ee67f0b91646106e466714f9de3c7e3"
TARGET = "/tmp/aws-rds-global-bundle.pem"

with urllib.request.urlopen(URL, timeout=120) as response:
    payload = response.read()

actual = hashlib.sha256(payload).hexdigest()
if actual != EXPECTED:
    raise SystemExit(
        f"Aurora CA bundle digest mismatch: expected {EXPECTED}, got {actual}. "
        "AWS republishes this bundle when a certificate authority is rotated; "
        "verify the new bundle and update EXPECTED in data-migration/Dockerfile."
    )
if b"-----BEGIN CERTIFICATE-----" not in payload:
    raise SystemExit("Aurora CA bundle contains no PEM certificate block.")

with open(TARGET, "wb") as handle:
    handle.write(payload)
PYVERIFY


# -----------------------------------------------------------------------------
# STAGE 2 -- runtime: the shipped image.
# -----------------------------------------------------------------------------
# Assumptions: the reference here is the SAME tag AND the SAME digest as the
#   builder stage, for the reason recorded there -- the virtual environment copied
#   in below records its creating interpreter's absolute path, so the two stages
#   cannot be allowed to diverge. With a digest present that obligation is
#   mechanically checkable by comparing two strings rather than trusting that one
#   mutable tag resolved the same way twice.
# WHY : Assumptions: this stage inherits the base's package set and then reduces it
#   in the instruction below, so the image that SHIPS is not simply this base. The
#   reduction is deliberate, measured and bounded, and it is argued where it
#   happens rather than here, because a reader auditing the shipped package set
#   needs the reasoning beside the command that changes it.
FROM python:3.13.14-slim-trixie@sha256:6771159cd4fa5d9bba1258caf0b82e6b73458c694d178ad97c5e925c2d0e1a91

# WHY : Assumptions: the plain psycopg distribution is pure Python and loads
#   libpq at run time through ctypes instead of shipping it, so without this
#   package `import psycopg` fails outright with "libpq library not found" --
#   confirmed against this exact base image, which carries no libpq of its own.
#   The RUNTIME library is installed rather than the development package
#   because nothing in this image compiles against libpq: headers, the static
#   archive and a compiler-oriented dependency chain would all be dead weight
#   in a shipped artifact.
# WHY : Trade-offs: this is the one dependency in the image with no exact
#   version pin, and that is a considered exception rather than an oversight.
#   Debian drops superseded binary packages from a suite once a point release
#   replaces them, so pinning an exact package version would turn a routine
#   security update into a hard build failure with no way to rebuild the prior
#   image. The base image tag is the pin that actually governs this layer,
#   because it fixes the suite the package is drawn from. The package lists are
#   removed in the same instruction so that metadata never becomes a layer of
#   its own. The linter rule that asks for a pinned apt version (DL3008) is
#   therefore suppressed for THIS instruction alone, on the line below, rather
#   than disabled in a shared configuration: scoping it here keeps the
#   exception visible at the point it applies and leaves the rule enforced
#   everywhere else, so a future unpinned install elsewhere is still reported.
# WHY : Refactoring Rationale: this instruction now also PURGES perl-base, which
#   the base image ships and nothing in this image runs. The measurement that
#   forced the change, taken on the built image at medium severity and above: 79
#   findings before the purge (4 critical, 19 high, 56 medium), every one against a
#   Debian package and none against the Python closure, with perl-base alone
#   carrying ALL FOUR criticals and four of the nineteen highs. After the purge:
#   ZERO critical and 15 high, and perl-base is the only package that leaves the
#   report. Counts move as a vulnerability database is updated -- a review of this
#   image reported 42 findings with 1 critical and 2 high from a different database
#   and filter -- so the durable claim is the SHAPE: the findings are all in the
#   operating-system layer, and the criticals were all in one removable package.
#   Nothing here invokes perl -- the ETL is a Python entry point, the account is
#   created below with groupadd and useradd rather than the perl adduser script,
#   and both ENTRYPOINT and HEALTHCHECK are exec-form invocations of python, so no
#   interpreter beyond CPython participates in a run.
# WHY : Assumptions: the purge is in the SAME instruction as the install, not a
#   later one. A removal in its own layer leaves the removed bytes in the layer
#   beneath it, so the package would still sit in the image's history and a scanner
#   reading the assembled filesystem would still be right to report it.
# WHY : Assumptions: --allow-remove-essential is required because Debian flags
#   perl-base as essential, and apt refuses without it. The flag is accepted here
#   rather than worked around because the removal is verified: apt's own purge plan
#   removes perl-base ALONE with no cascade, and the image was rebuilt and
#   exercised afterwards -- the module imports, the command-line entry point
#   answers, and dpkg still enumerates the remaining packages. That last check
#   matters, because an image whose package database were broken would report
#   FEWER findings by hiding its own inventory rather than by shipping less.
# WHY : Alternatives Considered: purging more of what a Python ETL never calls --
#   libsqlite3-0, the ncurses set, the util-linux family, which between them carry
#   most of the remaining HIGH findings. Rejected on evidence rather than on
#   caution: apt's purge plan for any of them cascades into util-linux's
#   pre-dependency chain and fails to resolve, so removing them would mean forcing
#   dpkg past a broken dependency graph, and an image with an inconsistent package
#   database is a worse artifact than one carrying a library it never loads.
#   Recorded so the next reader does not repeat the attempt.
# WHY : Alternatives Considered: a distroless or Alpine base, which would ship
#   none of these packages. Rejected because the base image tag is fixed by the
#   migration plan and, more substantively, because this image's fixed-point and
#   EBCDIC codec behaviour is validated against a COBOL parity oracle running on
#   the same CPython build -- a different libc or interpreter build would make a
#   parity difference indistinguishable from a base-image difference.
# hadolint ignore=DL3008
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends -y \
      libpq5 \
 && DEBIAN_FRONTEND=noninteractive apt-get purge -y --allow-remove-essential \
      perl-base \
 && rm -rf /var/lib/apt/lists/*

# Assumptions: PYTHONUNBUFFERED is load-bearing rather than cosmetic. A container
#   task's stdout is its only progress channel, and Python interposes a buffered
#   writer whenever stdout is a pipe rather than a terminal -- exactly what a
#   container log driver presents. Measured on this image: with the setting in
#   place a staging message is readable in the log stream while the task is still
#   running, whereas with it removed the stream stays EMPTY until the process
#   exits, leaving a task that is mid-load indistinguishable from one that is
#   wedged.
# Assumptions: PYTHONDONTWRITEBYTECODE holds because the bytecode is already
#   present -- the dependency install in the builder stage compiled it into the
#   virtual environment -- so a run has nothing left to compile and any write it
#   did attempt would populate a cache that nothing reads. Suppressing the write
#   is also what lets the application tree stay read-only to the account that runs
#   it, as arranged below.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# Assumptions: the same PATH the builder stage set, restated because ENV does not
#   cross a stage boundary. It is what makes the bare `python` in ENTRYPOINT
#   resolve to the environment's interpreter, the only one that can see the
#   installed distribution.
ENV PATH="/opt/venv/bin:${PATH}"

# Assumptions: the identifiers are stated explicitly because they form part of
#   the image's interface, not an implementation detail. A task definition or an
#   admission policy asserting "this container does not run as root" compares a
#   NUMBER and cannot resolve a name out of the image's /etc/passwd, so an account
#   created without an explicit identifier -- landing wherever the base image's
#   next free value happens to be, and free to move when the base image changes --
#   would make that assertion unstable. 10001 sits above the range Debian reserves
#   for system accounts and below the unprivileged account this base already
#   defines, so it collides with nothing present. No home directory is created:
#   the ETL takes its configuration from the task environment, and the AWS SDK in
#   this closure was confirmed to initialise with no readable home directory.
RUN groupadd --gid 10001 carddemo \
 && useradd --uid 10001 --gid 10001 --no-create-home \
      --shell /usr/sbin/nologin carddemo

# Assumptions: the SQL assets further down are copied to a path RELATIVE to this
#   line, so this decides where they come to rest and what any code resolving them
#   from the process working directory depends on. Reusing the builder's path
#   rather than inventing a second one means a reader comparing the two stages
#   sees one location instead of two that have to be reconciled.
WORKDIR /opt/carddemo

# -----------------------------------------------------------------------------
# The COPY set below is deliberately narrow. Read this before widening it.
# -----------------------------------------------------------------------------
# Assumptions: these COPY paths are the only thing standing between the build
#   context and the shipped image, so replacing them with a whole-context copy
#   would be a real change in behaviour rather than a tidy-up: it would pull in
#   this package's own test tree and this file, and would bake whatever else the
#   directory happens to hold into a published artifact.
# Assumptions: the mainframe extract files are NOT in this image, deliberately
#   twice over. They are read at run time from the versioned S3 dataset bucket
#   that the staging state populates, so baking a copy in would both inflate the
#   artifact and freeze data that is meant to be supplied per run; and they sit in
#   the repository's reference tree beside the COBOL programs, copybooks, JCL and
#   the parity suite -- material this migration is required to leave
#   byte-identical -- so nothing under app/, tests/, scripts/ or samples/ is
#   copied here. The build context being this directory rather than the repository
#   root puts those trees out of reach structurally, instead of relying on a
#   convention a future COPY could quietly break.
# -----------------------------------------------------------------------------

# Trade-offs: the builder's runtime environment remains root-owned; no `--chown`
#   is applied, so the account that runs the ETL
#   can read and execute the installed code but cannot modify it. Handing
#   ownership to that account is the more common idiom and buys nothing here,
#   because nothing in a single-shot run writes to its own installation; what
#   it would cost is the guarantee that a compromised ETL process cannot
#   rewrite the code its next invocation will execute.
# WHY : Assumptions: /opt/venv holds the runtime closure and the installed package
#   and NOTHING that produced them. The builder deliberately keeps its
#   hash-verified build backend in a second environment at /opt/build, which is not
#   copied here and dies with that stage, and the lint-and-test manifest is never
#   copied into the build context at all. So this one directory is the whole of the
#   PYTHON closure that ships, and a scan attributes nothing to build tooling the
#   image never runs.
# WHY : Refactoring Rationale: that sentence used to end "and a vulnerability scan
#   of this image reports on code the ETL actually imports rather than on tooling
#   it never runs", which claimed more than the arrangement delivers. A scan of
#   this image reports on the BASE IMAGE's Debian packages as well, and that is
#   where every finding actually is: measured on the built image at medium severity
#   and above, 79 findings, all 79 against Debian packages and ZERO against any of
#   the twelve Python distributions in this directory. The single-directory hand-off is therefore
#   real and worth keeping -- it is why the Python side is clean and why no build
#   backend, compiler or test dependency appears in the scan at all -- but it says
#   nothing about the operating system underneath, and the corrected sentence
#   claims only what it does. What the operating-system side does about those
#   findings is recorded at the runtime apt instruction above.
COPY --from=builder /opt/venv /opt/venv

# Assumptions: the verified bundle is copied to the literal DEFAULT_SSL_ROOT_CERT
#   path in src/carddemo_migration/config.py. The two must agree, and the coupling
#   is one-directional and fails closed: resolve_ssl_root_cert() confirms the file
#   exists and is readable and raises a named configuration error otherwise, so a
#   path typo here stops the ETL at start-up rather than producing a connection
#   that skips verification. The override CARDDEMO_DB_SSL_ROOT_CERT exists for a
#   deployment that materialises the bundle elsewhere and can only name another
#   absolute path -- it cannot weaken the mode, which is a module constant.
# Trade-offs: copied from the builder rather than downloaded again here, so the
#   runtime stage needs no network access during its build and no HTTP client at
#   all, and the bytes that were verified are provably the bytes that ship.
COPY --from=builder /tmp/aws-rds-global-bundle.pem \
                    /etc/ssl/certs/aws-rds-global-bundle.pem

# Assumptions: the schema bootstrap and verification queries sit outside the src
#   layout, so the package install cannot carry them and they have to be copied in
#   their own right. They belong in the image because it is the only component in
#   the migrated stack holding both a database connection and that bootstrap, so
#   the documented data-migration procedure applies them from here rather than
#   requiring an operator with direct database access.
COPY sql/ ./sql/

# Assumptions: dropping privilege before the entry point makes the unprivileged
#   account the one the process actually runs as; a USER placed after ENTRYPOINT
#   would be parsed but would not change the running identity. The numeric form is
#   used rather than the account name so that a policy which cannot read the
#   image's /etc/passwd can still verify the process is not root.
# -----------------------------------------------------------------------------
# Assumptions: the HEALTHCHECK is a structural import probe, not a liveness
#   claim. It imports the installed package with no network, database,
#   object-store or CLI dependency; ECS batch orchestration still judges the
#   one-shot task by terminal state and exit code.
# Alternatives Considered: probing CLI help, which would write on every interval;
#   or probing Aurora or Parameter Store, which would confuse dependency
#   availability with image integrity and would require credentials.
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=2 \
    CMD ["python", "-c", "import carddemo_migration"]

USER 10001:10001

# Assumptions: this is an orchestration contract with the batch state machine.
#   The `StageSeedDatasets` Map state starts one synchronous container task per
#   dataset via `ecs:runTask.sync` and supplies that task's arguments as a
#   container override, which REPLACES CMD and is APPENDED to ENTRYPOINT.
#   ENTRYPOINT must therefore carry only what never varies -- the interpreter and
#   the CLI module -- and none of the per-dataset arguments. The exec form is
#   required by the same contract: a shell form would wrap the process in
#   `/bin/sh -c`, which leaves the interpreter a child that never receives the
#   stop signal directly and turns an appended override into text handed to a
#   shell rather than discrete argv elements.
# Alternatives Considered: naming the console script pyproject.toml also
#   declares. Rejected -- a `python -m` argv is valid whether or not the installed
#   distribution's generated script wrappers are present on PATH, whereas a bare
#   script name silently depends on them. A run against an image whose package
#   does not expose `carddemo_migration.cli` exits 1 with `ModuleNotFoundError`,
#   which is the loud failure the orchestrator needs rather than a silent no-op.
ENTRYPOINT ["python", "-m", "carddemo_migration.cli"]

# Assumptions: `--help` is the stable, non-mutating default for a human-run
#   container. Step Functions replaces CMD with per-dataset arguments, so this
#   default does not configure production execution.
CMD ["--help"]
