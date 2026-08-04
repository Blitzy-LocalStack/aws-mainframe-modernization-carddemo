# =============================================================================
# data-migration/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Container image for `carddemo-migration`, the Python ETL that carries
#   CardDemo's mainframe data into the migrated stack. It decodes the eleven
#   fixed-width record layouts declared by the COBOL copybooks -- zoned decimal
#   with sign overpunch, COMP-3 packed decimal, and EBCDIC code pages decoded
#   per field -- bulk-loads each record set into its owning Aurora PostgreSQL
#   schema, stages the generation-dataset families into versioned S3, and then
#   verifies the load three independent ways: row counts per dataset, record
#   checksums, and money-total parity against the source files.
#
#   This is a BATCH image, not a service. It has no listener, no exposed port
#   and no readiness endpoint. The migrated batch state machine starts it
#   through the synchronous `ecs:runTask.sync` integration -- once per dataset
#   in the `StageSeedDatasets` Map state -- and judges the result by the task's
#   terminal state and exit code alone. Every decision below is arranged around
#   a short-lived process whose stdout is its only progress channel.
#
# Build and smoke-test (run from this directory, which IS the build context):
#   docker build -t carddemo-migration:local .
#   docker run --rm carddemo-migration:local --help
#
# State at this checkpoint:
#   This file is authored ahead of most of the package it packages, and the
#   migration lands its artifacts in plan order. `src/carddemo_migration/`
#   currently holds exactly two modules, `__init__.py` and `config.py`; the
#   `cli.py` module this image's ENTRYPOINT names, together with the `copybook/`,
#   `readers/`, `loaders/` and `verify/` subpackages and this package's README,
#   are authored at later indexes of the same plan. Measured consequences, so the
#   two commands above are not read as a promise:
#     - The BUILD succeeds. Both stages complete, the pinned closure installs,
#       and the distribution installs into /opt/venv from the two modules present
#       -- verified by building the wheel those instructions build and installing
#       it, which produced an importable `carddemo_migration`.
#     - The SMOKE TEST does not. `docker run ... --help` resolves ENTRYPOINT plus
#       CMD to `python -m carddemo_migration.cli --help`, which exits 1 with
#       `ModuleNotFoundError: No module named 'carddemo_migration.cli'`. That is
#       the correct, loud failure for a missing module, not a defect in this file,
#       and it turns green the moment `cli.py` lands.
#   No instruction below is to be read as a claim that the CLI it names exists
#   today. Every one of them is written now on purpose: ENTRYPOINT in exec form
#   carrying only the invariant argv is an orchestration contract the batch state
#   machine is written against, and changing that contract after the state machine
#   exists is a breaking change rather than a completion.
#
# WHY (non-obvious design decisions):
#   - Assumptions: the base image is pinned by DIGEST as well as tag, for
#     CORRECTNESS rather than tidiness. A tag is a mutable pointer, and the ETL's
#     decimal and codec behaviour has to agree with the COBOL parity oracle that
#     validates its output -- so a silently republished base would present as a
#     data-parity failure. Both stages name the identical digest. Stated in full
#     at the builder's FROM.
#   - Assumptions: NOTHING enters this image unverified. Three separate supply
#     chains are each locked by digest: the base image (by manifest digest), every
#     Python distribution (by artifact sha256 under `--require-hashes`), and the
#     Aurora certificate bundle (by a pinned content sha256). The one deliberate
#     exception is the libpq5 apt package, whose version is governed by the pinned
#     base image's suite; the reason it cannot itself be version-pinned is
#     recorded at that instruction.
#   - Trade-offs: two stages, so the shipped image carries the INSTALLED
#     distribution and nothing that produced it: neither the source tree nor
#     the build backend. Stated in full at the builder stage banner. The build
#     backend additionally lives in its own environment inside that stage, so it
#     cannot reach the environment that is copied forward.
#   - Assumptions: the image installs the RUNTIME manifest only. The lint-and-test
#     closure is a separate file which is never copied into the build context, so
#     no linter, test runner or coverage tool is present in a shipped artifact.
#   - Trade-offs: the dependency manifest is copied and installed before any
#     application source, which costs an extra COPY and buys a dependency layer
#     that an ordinary source edit does not invalidate.
#   - Assumptions: ENTRYPOINT is an orchestration contract rather than a
#     convenience. The batch orchestrator APPENDS per-dataset arguments to it,
#     which only holds in exec form carrying the invariant part alone.
#   - Assumptions: nothing here names a host, an account, a queue, a bucket or
#     a credential, and no ENV carries a connection default. The ETL resolves
#     those at task launch from Systems Manager Parameter Store and Secrets
#     Manager, which is what makes a checkout of this repository inert on its
#     own rather than merely conventionally clean.
# =============================================================================


# -----------------------------------------------------------------------------
# STAGE 1 -- builder: resolve the pinned closure, install the distribution.
# -----------------------------------------------------------------------------
# WHY : Trade-offs: a single-stage image would be shorter to read and was
#   rejected for what it would LEAVE BEHIND rather than for its size. A PEP 517
#   install resolves a build backend (setuptools) into the image and needs the
#   SOURCE tree -- pyproject.toml and src/ -- present in order to build the
#   distribution at all. Splitting the build off means the shipped image holds
#   the installed package and none of that machinery: there is no build backend
#   in it to keep patched, and no second copy of the source that could shadow
#   the installed package at import time. The accepted costs are a longer build
#   graph and one copy step between stages. Note what this split does NOT buy,
#   so the claim is not overstated in review: every distribution in the closure
#   publishes a wheel for this platform, so NO compiler is installed in either
#   stage and none is being excluded here.
# -----------------------------------------------------------------------------

# WHY : Assumptions: this pin is a PARITY decision. The ETL decodes money with
#   the standard library's `decimal`, `codecs` and `struct` modules, and its
#   output is verified against the COBOL test suite that serves as this
#   migration's functional oracle; that suite is recorded as validated on
#   CPython 3.13 (tests/README.md, section 3 Prerequisites -- reference
#   material that is never modified and, per the COPY note in the runtime
#   stage, never copied into this image). Holding the image on the same
#   interpreter MINOR series keeps those three modules' semantics identical on
#   both sides, so a discrepancy in verification output has to be investigated
#   as a real data difference instead of being written off as an interpreter
#   gap. The claim is deliberately made at the 3.13 minor level and no further:
#   the oracle's recorded patch release and this tag's patch release are NOT
#   the same number, and neither is adjusted to make them agree.
# WHY : Alternatives Considered: a musl-based slim variant was evaluated and
#   rejected. The ETL's PostgreSQL driver is the PLAIN psycopg distribution,
#   which is pure Python and resolves the libpq client library at run time
#   rather than vendoring it, so the image has to supply libpq itself. A musl
#   base changes the C-library surface that driver binds to and pushes the
#   build toward compiling the driver from source, trading a verified
#   wheel-only install for a toolchain this image otherwise has no use for. A
#   Debian slim base keeps the driver on prebuilt artifacts and one runtime
#   shared library.
# WHY : Assumptions: BOTH stages name this reference and must go on naming the
#   SAME one. It is not duplication that can be tidied into a single place: the
#   runtime stage adopts this stage's virtual environment by copying it, and a
#   virtual environment records the absolute path of the interpreter that
#   created it. Two different bases would build against one interpreter and
#   execute against another, failing at import time rather than at build time.
# WHY : Refactoring Rationale: the reference carries a DIGEST as well as the tag,
#   and the digest is what actually pins it. A tag is a mutable pointer: Docker
#   Hub republishes `3.13.14-slim-trixie` whenever the Debian trixie base beneath
#   it is rebuilt, so two builds of this identical Dockerfile could resolve
#   different bytes -- different OpenSSL, different libpq5 candidate, in principle
#   a different CPython patch build -- with nothing in the file or the build log
#   distinguishing them. For this image that is not merely an irreproducibility:
#   the ETL's decimal and codec behaviour has to agree with the COBOL parity
#   oracle that validates its output, and the oracle compares by record checksum
#   and money total, so a silent interpreter or library change would present as a
#   DATA-PARITY failure whose actual cause is the base image. A digest makes the
#   base image as reviewable as every pinned dependency in this tree.
#   Assumptions: the digest names an OCI image INDEX, not one platform's manifest,
#   so it stays correct for every architecture this image is built for -- pinning
#   a per-platform manifest digest instead would silently restrict the build to
#   one architecture.
#   Trade-offs: a security rebuild of the base now requires editing this file
#   twice rather than rebuilding against a moving tag. That is the intended cost:
#   adopting a new base becomes a reviewed commit that says which bytes were
#   adopted, and the tag is retained beside the digest so a reader can still see
#   WHICH release the digest belongs to. 3.13.14 was confirmed to be the newest
#   3.13 slim-trixie release at the time of pinning, so this is a current base and
#   not a frozen old one.
FROM python:3.13.14-slim-trixie@sha256:6771159cd4fa5d9bba1258caf0b82e6b73458c694d178ad97c5e925c2d0e1a91 AS builder

# WHY : Assumptions: it is deliberately NOT the environment's own path. Keeping
#   the checkout at /opt/carddemo while the environment sits at /opt/venv stops
#   the two from overlapping, which is precisely what lets the runtime stage
#   copy the environment on its own and leave every trace of the build behind.
#   A working directory that contained the environment would make that
#   single-directory hand-off impossible to express without shipping the source
#   along with it.
WORKDIR /opt/carddemo

# WHY : Assumptions: the runtime stage relocates exactly ONE directory, so
#   holding the installed set inside a single self-contained prefix is what
#   reduces that hand-off to one instruction with nothing to enumerate.
#   Installing into the base interpreter's site-packages would instead require
#   copying two directories whose paths embed the interpreter version, so a
#   base image bump would silently copy nothing and produce an image that fails
#   only when it runs. `--upgrade-deps` is deliberately not passed: it would
#   pull unpinned installer releases into the build, contradicting the
#   exact-pin doctrine every dependency in this tree is held to.
RUN python -m venv /opt/venv

# WHY : Assumptions: prepending the environment is what makes a bare `pip`
#   here, and a bare `python` in the runtime stage's ENTRYPOINT, resolve to the
#   interpreter that can see the installed distribution. Naming
#   /opt/venv/bin/python absolutely at each use site would behave identically
#   but would spread the environment's location across several instructions
#   instead of stating it once.
ENV PATH="/opt/venv/bin:${PATH}"

# WHAT: the two build-time manifests on their own, ahead of any application
#   source.
# WHY : Trade-offs: this is the deliberate layer ordering, and an extra COPY
#   instruction is what it costs. A build invalidates every layer from the
#   first one whose inputs changed onwards, so copying the source first would
#   re-run the entire dependency install on every edit to a reader or a codec
#   -- the files that change most often -- while these manifests change
#   rarely. Ordered this way, an ordinary source edit reuses the cached
#   dependency layer. The accepted costs are that the build context is read in
#   two passes and that pyproject.toml has to be copied separately further down
#   even though it also carries dependency metadata.
# WHY : Assumptions: requirements-dev.txt is deliberately NOT copied. It is the
#   lint-and-test closure, this image runs neither, and a manifest absent from the
#   build context cannot be installed by a later edit that reaches for it out of
#   convenience. Continuous integration installs it in a checkout instead.
COPY requirements.txt requirements-build.txt ./

# WHY : Assumptions: requirements.txt is the AUTHORITATIVE version set -- exact
#   `==` pins throughout -- whereas pyproject.toml states compatibility FLOORS
#   for an installer that resolves this package on its own. Installing the
#   manifest first fixes every version at its pinned value, so the package
#   install below cannot raise any of them.
# WHY : Assumptions: `--only-binary :all:` turns "no compiler is required" from
#   a lucky property into an enforced one. Every distribution in this closure
#   publishes a wheel for CPython 3.13 on this platform, so demanding binary
#   artifacts changes nothing about what gets installed today; what it changes
#   is the failure mode should a future pin lack a wheel -- the installer
#   reports no matching distribution immediately, instead of starting a source
#   build that then dies deep inside a toolchain this image intentionally never
#   provides. The flag is applied HERE only, and not to the package install
#   below, because that one legitimately builds a local source tree.
# WHY : Trade-offs: `--no-cache-dir` gives up nothing real. A download cache
#   only pays off across repeated installs in one environment and this layer
#   installs once, so retaining it would merely leave archives inside the
#   layer.
# WHY : Refactoring Rationale: `--require-hashes` is the point of this
#   instruction. It previously installed the manifest with exact version pins and
#   no digests, which authenticates nothing -- a version pin names a version, the
#   index names an artifact, and a compromised index or hijacked maintainer
#   account can answer that name with different bytes, silently, in an image that
#   then holds a database credential and every account, customer, card and
#   transaction row it loads. With every artifact hashed, the install either
#   reproduces the reviewed bytes or fails. The flag is passed explicitly even
#   though pip enters hash-checking mode on its own once any requirement carries a
#   hash, because the explicit form is what makes a future unhashed addition an
#   immediate error instead of a silent downgrade of the whole manifest to
#   version-only pinning.
# WHY : Refactoring Rationale: the manifest installed here is now the RUNTIME
#   closure alone. It previously installed the whole file including a linter, a
#   test runner and a coverage tool, on the reasoning that selecting one group
#   would need either a second manifest the plan did not define or the filtering
#   of a comment-delimited block -- and that filtering objection was correct,
#   which is why the split is a separate FILE rather than a parsed section: a file
#   boundary cannot be moved by an edit to a comment. What the old arrangement
#   cost was not image size but attack surface, code present in a production image
#   that no production run imports.
RUN pip install --no-cache-dir --require-hashes --only-binary :all: -r requirements.txt

# WHY : Assumptions: this package uses a src layout -- pyproject.toml declares
#   `where = ["src"]` -- so the directory below is the whole importable tree
#   and there is nothing further to bring in for the build. pyproject.toml
#   declares neither a `readme` nor a `license` file, which is what allows
#   these two paths alone to constitute a buildable source distribution --
#   verified by building the wheel rather than assumed. "Complete" here means
#   complete as BUILD INPUT: pyproject.toml plus the src tree are everything the
#   backend needs, with no third path to remember. It says nothing about how many
#   modules that src tree currently holds, which at this checkpoint is two; see
#   State at this checkpoint in the header.
COPY pyproject.toml ./
COPY src/ ./src/

# WHAT: build the wheel with a hash-verified backend that never enters the
#   shipped environment, then install that wheel offline.
# WHY : Refactoring Rationale: this was one instruction, `pip install --no-deps .`,
#   and it left the build's own supply chain unverified. An ordinary PEP 517
#   install resolves the build backend named in pyproject.toml into an isolated
#   environment by fetching it from the network -- previously at an unbounded
#   `setuptools>=80`, so whichever release was current that day, checked against
#   nothing. A build backend executes arbitrary code during the build and produces
#   the artifact that is then installed and shipped, so it is the component with
#   the broadest reach in the whole image and it was the only one not pinned. The
#   three instructions below close that: the backend is installed from a
#   hash-locked manifest, `--no-build-isolation` makes THAT copy the one that runs
#   rather than a freshly fetched one, and the wheel is then installed with
#   `--no-index` so the final step provably reaches no network at all.
# WHY : Assumptions: the backend goes into a SEPARATE environment at /opt/build,
#   not into /opt/venv, and that separation is the whole reason there are two
#   environments. `--no-build-isolation` requires the backend to be present in the
#   environment running the build, so installing it into /opt/venv would satisfy
#   pip and then ship setuptools inside the runtime image -- reintroducing exactly
#   the build tooling the two-stage split exists to keep out, and reintroducing it
#   into the one environment that IS copied forward. Two environments cost one
#   extra directory in a discarded stage and buy a shipped environment containing
#   only what the ETL imports.
# WHY : Assumptions: `--no-deps` on the wheel install is what protects the pin
#   doctrine. pyproject.toml declares its dependencies as floors on purpose, so
#   the package stays installable beside other software -- which means a
#   dependency-resolving install here would be free to satisfy those floors with
#   NEWER releases than the exact versions installed above, silently replacing
#   them and defeating the reproducibility this closure exists to provide. The
#   layer above already installed the authoritative set, so this has exactly one
#   thing left to add: the package.
# WHY : Trade-offs: the three steps are one RUN instruction rather than three. A
#   separate layer per step would read more clearly and would leave /opt/build and
#   the wheel file inside the image's layer history -- harmless in a discarded
#   build stage, and the reason for combining them is different: the wheel path is
#   produced by the first step and consumed by the third, so splitting them would
#   require naming that path in two instructions instead of one shell variable.
RUN python -m venv /opt/build \
 && /opt/build/bin/pip install --no-cache-dir --require-hashes --only-binary :all: \
      -r requirements-build.txt \
 && /opt/build/bin/pip wheel --no-cache-dir --no-deps --no-build-isolation \
      --wheel-dir /tmp/wheel . \
 && pip install --no-cache-dir --no-deps --no-index \
      --find-links /tmp/wheel carddemo-migration

# WHAT: the AWS-published Aurora certificate bundle, verified against a pinned
#   digest, staged for the runtime stage to adopt.
# WHY : Assumptions: this file is REQUIRED, not optional. The ETL connects with
#   `sslmode=verify-full` and an explicit `sslrootcert`, and
#   src/carddemo_migration/config.py resolves that path from
#   DEFAULT_SSL_ROOT_CERT -- the literal target below -- and REFUSES to produce
#   connection settings when the file is absent. So an image built without this
#   layer does not degrade to an unverified connection; it fails to run, which is
#   the intended direction of failure and is why the path is pinned in both files
#   rather than discovered.
# WHY : Alternatives Considered: trusting the operating system's CA store instead,
#   by pointing `sslrootcert` at the system bundle. Rejected as too broad: the
#   system store trusts every publicly trusted authority, so a certificate issued
#   by any one of them for the cluster's endpoint name would verify, whereas this
#   bundle trusts only the authorities that can legitimately certify an Aurora
#   endpoint. Also considered: baking no bundle and mounting one at run time.
#   Rejected because it makes a security-critical file an operator step that can
#   be forgotten, and the failure would surface as a broken batch window.
# WHY : Assumptions: the download is VERIFIED against a pinned sha256, computed
#   from the bundle AWS publishes -- 165,408 bytes containing 108 certificates. An
#   unverified fetch would be self-defeating in the most literal way: a trust
#   anchor obtained over an unauthenticated channel anchors nothing, and this file
#   is the sole thing standing between the ETL and an impersonated database
#   endpoint. The digest is checked with the standard library rather than with
#   curl, sha256sum or gpg, so this layer adds no package to the build stage.
#   Trade-offs: AWS republishes this bundle when a certificate authority is
#   rotated, and on that day this build fails with a digest mismatch until the
#   value is refreshed. That is the correct behaviour and the same trade-off the
#   pinned base-image digest and the pinned wheel hashes make: a change in bytes
#   this repository did not review stops the build instead of entering the image.
#   Refreshing it is one command whose output goes on the line below --
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
# WHY : Assumptions: the reference here is the SAME tag AND the SAME digest as the
#   builder stage, for the reason recorded there in full -- the virtual environment
#   copied in below records its creating interpreter's absolute path, so the two
#   stages cannot be allowed to diverge. Changing one obliges changing the other
#   in the same commit, and with a digest present that obligation is now
#   mechanically checkable by comparing two strings rather than trusting that one
#   mutable tag resolved the same way twice.
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
# hadolint ignore=DL3008
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install --no-install-recommends -y \
      libpq5 \
 && rm -rf /var/lib/apt/lists/*

# WHY : Assumptions: PYTHONUNBUFFERED is load-bearing rather than cosmetic. A
#   container task's stdout is its only progress channel, and Python interposes
#   a buffered writer whenever stdout is a pipe rather than a terminal -- which
#   is exactly what a container log driver presents. Setting this removes that
#   layer, so each write reaches the descriptor as it is made. Measured on this
#   image: with the setting in place a staging message is readable in the log
#   stream while the task is still running, whereas with it removed the stream
#   stays EMPTY until the process exits, leaving a task that is mid-load
#   indistinguishable from one that is wedged. Telling those two apart is the
#   entire value of a progress log on a job whose steps are long.
# WHY : Assumptions: PYTHONDONTWRITEBYTECODE holds because the bytecode is
#   already present -- the dependency install in the builder stage compiled it
#   into the virtual environment. A run therefore has nothing left to compile,
#   and any write it did attempt would populate a cache that nothing reads,
#   since this process starts once and exits. Suppressing the write is also
#   what lets the application tree stay read-only to the account that runs it,
#   as arranged below.
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# WHY : Assumptions: this is the same PATH the builder stage set, restated
#   because ENV does not cross a stage boundary. It is what makes the bare
#   `python` in ENTRYPOINT resolve to the environment's interpreter, which is
#   the only one that can see the installed distribution.
ENV PATH="/opt/venv/bin:${PATH}"

# WHY : Assumptions: the identifiers are stated explicitly because they form
#   part of the image's interface, not an implementation detail. A task
#   definition or an admission policy asserting "this container does not run as
#   root" compares a NUMBER and cannot resolve a name out of the image's
#   /etc/passwd, so an account created without an explicit identifier --
#   landing wherever the base image's next free value happens to be, and free
#   to move when the base image changes -- would make that assertion unstable,
#   and would equally break any volume whose ownership was granted ahead of
#   time. 10001 sits above the range Debian reserves for system accounts and
#   below the unprivileged account this base already defines, so it collides
#   with nothing present. No home directory is created: the ETL takes its
#   configuration from the task environment rather than a per-account file, and
#   the AWS SDK in this closure was confirmed to initialise with no readable
#   home directory at all.
RUN groupadd --gid 10001 carddemo \
 && useradd --uid 10001 --gid 10001 --no-create-home \
      --shell /usr/sbin/nologin carddemo

# WHY : Assumptions: the SQL assets further down are copied to a path RELATIVE
#   to this line, so this is what decides where they come to rest and what any
#   code resolving them from the process working directory depends on. Reusing
#   the builder's path rather than inventing a second one means a reader
#   comparing the two stages sees one location instead of two that have to be
#   reconciled, and moving it would silently relocate those assets.
WORKDIR /opt/carddemo

# -----------------------------------------------------------------------------
# The COPY set below is deliberately narrow. Read this before widening it.
# -----------------------------------------------------------------------------
# WHY : Assumptions: there is no ignore file beside this Dockerfile, so these
#   COPY paths are the ONLY thing standing between the build context and the
#   shipped image. Replacing them with a whole-context copy would be a real
#   change in behaviour rather than a tidy-up: it would pull in this package's
#   own test tree and this file, and would bake whatever else the directory
#   happens to hold into a published artifact.
# WHY : Assumptions: the mainframe extract files are NOT in this image, and
#   that is deliberate twice over. They are read at run time from the versioned
#   S3 dataset bucket that the staging state populates, so baking a copy in
#   would both inflate the artifact and freeze data that is meant to be
#   supplied per run. They also sit in the repository's reference tree beside
#   the COBOL programs, copybooks, JCL and the parity suite -- material this
#   migration is required to leave byte-identical -- so nothing under app/,
#   tests/, scripts/ or samples/ is copied here. The build context being this
#   directory rather than the repository root puts those trees out of reach
#   structurally, instead of relying on a convention that a future COPY could
#   quietly break.
# -----------------------------------------------------------------------------

# WHAT: adopt the builder's RUNTIME virtual environment, left owned by root.
# WHY : Trade-offs: no `--chown` is applied, so the account that runs the ETL
#   can read and execute the installed code but cannot modify it. Handing
#   ownership to that account is the more common idiom and buys nothing here,
#   because nothing in a single-shot run writes to its own installation; what
#   it would cost is the guarantee that a compromised ETL process cannot
#   rewrite the code its next invocation will execute.
# WHY : Assumptions: /opt/venv holds the runtime closure and the installed package
#   and NOTHING that produced them. The builder deliberately keeps its
#   hash-verified build backend in a second environment at /opt/build, which is not
#   copied here and dies with that stage, and the lint-and-test manifest is never
#   copied into the build context at all. So this one directory is the whole of
#   what ships, and a vulnerability scan of this image reports on code the ETL
#   actually imports rather than on tooling it never runs.
COPY --from=builder /opt/venv /opt/venv

# WHAT: the verified Aurora certificate bundle, at the exact path the ETL resolves.
# WHY : Assumptions: the destination is the literal value of DEFAULT_SSL_ROOT_CERT
#   in src/carddemo_migration/config.py. The two must agree, and the coupling is
#   one-directional and fails closed: resolve_ssl_root_cert() confirms the file
#   exists and is readable and raises a named configuration error otherwise, so a
#   path typo here stops the ETL at start-up rather than producing a connection
#   that skips verification. The override CARDDEMO_DB_SSL_ROOT_CERT exists for a
#   deployment that materialises the bundle elsewhere, and it can only name another
#   absolute path -- it cannot weaken the mode, which is a module constant.
# WHY : Trade-offs: copied from the builder rather than downloaded again here. The
#   fetch and its digest check happen once, in the stage that is discarded, so the
#   runtime stage needs no network access during its build and no HTTP client at
#   all -- and the bytes that were verified are provably the bytes that ship,
#   rather than a second download that happened to have the same name.
COPY --from=builder /tmp/aws-rds-global-bundle.pem \
                    /etc/ssl/certs/aws-rds-global-bundle.pem

# WHAT: the schema-and-role bootstrap and the verification queries.
# WHY : Assumptions: these are data files sitting outside the src layout, so
#   the package install cannot carry them and they have to be copied in their
#   own right. They belong in the image because it is the only component in the
#   migrated stack holding both a database connection and that bootstrap, so
#   the documented data-migration procedure applies them from here rather than
#   requiring an operator with direct database access -- which is what keeps
#   the cutover reproducible from the infrastructure definition alone.
COPY sql/ ./sql/

# -----------------------------------------------------------------------------
# HEALTHCHECK is deliberately ABSENT.
# -----------------------------------------------------------------------------
# WHY : Alternatives Considered: a lightweight probe -- importing the package,
#   or running the CLI's --help -- was considered and rejected, for two reasons
#   of which the second is the stronger. A container health check describes a
#   long-running process, whereas this one runs to completion and the batch
#   orchestrator observes the task's terminal state and exit code through its
#   synchronous run-task integration, a signal no periodic probe contributes
#   to. More decisively, the only question such a probe could answer is whether
#   the interpreter still imports the package, so it would report healthy for
#   the whole run -- including while a load was failing -- and would advertise
#   a liveness guarantee this image does not provide, which is worse than
#   advertising none. That structural check IS worth having, and it belongs in
#   continuous integration as a one-shot assertion that `--help` exits zero,
#   where a failure blocks the image from being published rather than being
#   sampled and discarded at run time. That assertion has no home yet: the
#   workflow that would carry it is authored at a later index of the same plan,
#   and until `cli.py` lands the assertion would fail by design, for the reason
#   recorded in State at this checkpoint in the header.
# -----------------------------------------------------------------------------

# WHY : Assumptions: dropping privilege BEFORE the entry point is what makes
#   the unprivileged account the one the process actually runs as; a USER
#   placed after ENTRYPOINT would be parsed but would not change the running
#   identity. The numeric form is used rather than the account name so that a
#   policy which cannot read the image's /etc/passwd can still verify the
#   process is not root.
# -----------------------------------------------------------------------------
# HEALTHCHECK: a structural import probe, not a liveness claim.
# -----------------------------------------------------------------------------
# WHAT: import the installed package in a subprocess and exit non-zero if the
#   import fails. No network, no database, no object store, no CLI parsing.
# WHY : Refactoring Rationale: this instruction was previously OMITTED, with the
#   argument that a health check describes a long-running process while this
#   image runs to completion, and that a probe answering only "does the package
#   still import" would advertise a liveness guarantee the image cannot provide.
#   Both halves of that are true and neither one supports omission, because the
#   premise it rests on is wrong: an image-level HEALTHCHECK is NOT what the
#   batch orchestrator reads. Amazon ECS takes its container health check from
#   the task definition's own `healthCheck` field and ignores the one baked into
#   the image, and the batch task definition declares none -- the state machine
#   observes the task's terminal state and exit code through the synchronous
#   run-task integration, exactly as the earlier note said. So this instruction
#   cannot keep a one-shot task alive, cannot delay its completion, and cannot
#   displace exit-status monitoring: on the deployed path it is inert.
#   Where it is NOT inert is every other runtime that reads image metadata --
#   `docker run`, Compose, a local smoke test, a registry scan -- and there its
#   presence is what the frozen Dockerfile requirement asks for: a multi-stage
#   build, a non-root user, and a health check. The requirement is not satisfied
#   by an argument for why one is unnecessary.
#   Assumptions: the probe is deliberately the WEAKEST useful one. Importing
#   `carddemo_migration` exercises the interpreter, the virtual environment on
#   PATH, and the installed distribution's own import graph -- which is precisely
#   the class of failure a broken image has (a wheel that did not install, a
#   stage-copy that missed the environment, a Python that cannot find it) and
#   which no amount of correct configuration can mask. It answers nothing about
#   whether a load is succeeding, and it is not intended to: `--interval` and
#   `--retries` are set so a run-to-completion container reports its status once
#   and quickly rather than being sampled repeatedly.
#   Alternatives Considered: probing the CLI's `--help`, which exercises argument
#   parsing as well. Rejected because `--help` writes to standard output and
#   exits through argparse's own SystemExit path, so a probe would either pollute
#   the container's log stream on every interval or need a shell redirect, and
#   the exec form is preferred here for the same reason it is on ENTRYPOINT.
#   Alternatives Considered: a probe that opens a database connection or reads a
#   parameter. Rejected outright: it would make container health depend on
#   another service's availability, so a transient Aurora failover would mark a
#   healthy image unhealthy, and it would need credentials at probe time.
# -----------------------------------------------------------------------------
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=2 \
    CMD ["python", "-c", "import carddemo_migration"]

USER 10001:10001

# WHY : Assumptions: this is an orchestration contract with the batch state
#   machine. The `StageSeedDatasets` Map state starts one synchronous container
#   task per dataset via `ecs:runTask.sync` and supplies that task's arguments
#   as a container override, which REPLACES CMD and is APPENDED to ENTRYPOINT.
#   ENTRYPOINT must therefore carry only what never varies -- the interpreter
#   and the CLI module -- and none of the per-dataset arguments. The exec form
#   is required by the same contract: a shell form would wrap the process in
#   `/bin/sh -c`, which both leaves the interpreter a child that never receives
#   the stop signal directly and turns an appended override into text handed to
#   a shell rather than discrete argv elements.
# WHY : Alternatives Considered: pyproject.toml also declares a console script,
#   and naming it here was rejected. The module form is the one that file
#   documents for exactly this case, because a `python -m` argv is valid
#   whether or not the installed distribution's generated script wrappers are
#   present on PATH, whereas a bare script name silently depends on them.
# WHY : Assumptions: the module this names is authored at a later index of the
#   same plan, so today this ENTRYPOINT resolves to an import that fails with
#   ModuleNotFoundError and exit 1. It is written now, unchanged, because the
#   argv split between ENTRYPOINT and a container override is the contract the
#   batch state machine is authored against; both spellings fail identically
#   while the module is absent, so there is nothing to be gained by deferring the
#   choice and a contract to lose.
ENTRYPOINT ["python", "-m", "carddemo_migration.cli"]

# WHAT: a default argument list, so a bare `docker run` is informative.
# WHY : Assumptions: `--help` is chosen because it is the one argument the CLI is
#   to be guaranteed to accept, which also makes it the argument this default can
#   keep across every later change to the subcommand set. The catalogue of
#   subcommands belongs to this package's README, and naming one here would create
#   a second place for that contract to be stated and to drift out of step.
#   Neither the CLI nor that README exists at this checkpoint, so a bare
#   `docker run` today is informative about the missing module rather than
#   about the subcommands; see State at this checkpoint in the header. This
#   default is never what the batch orchestrator runs, since its container
#   override replaces CMD outright, so it documents the image for a human rather
#   than configuring it for production.
CMD ["--help"]
