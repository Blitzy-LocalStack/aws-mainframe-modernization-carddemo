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
# WHY (non-obvious design decisions):
#   - Assumptions: the interpreter tag is pinned for CORRECTNESS, not tidiness.
#     The ETL's decimal and codec behaviour has to agree with the COBOL parity
#     oracle that validates its output, which is why one tag is named
#     identically by both stages. Stated in full at the builder's FROM.
#   - Trade-offs: two stages, so the shipped image carries the INSTALLED
#     distribution and nothing that produced it: neither the source tree nor
#     the build backend. Stated in full at the builder stage banner.
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
# WHAT: this stage exists only to produce one populated virtual environment at
#   /opt/venv, which the runtime stage adopts wholesale.
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

# WHAT: the interpreter both stages are built on, pinned to an exact patch tag.
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
# WHY : Assumptions: BOTH stages name this tag and must go on naming the SAME
#   one. It is not duplication that can be tidied into a single place: the
#   runtime stage adopts this stage's virtual environment by copying it, and a
#   virtual environment records the absolute path of the interpreter that
#   created it. Two different bases would build against one interpreter and
#   execute against another, failing at import time rather than at build time.
FROM python:3.13.14-slim-trixie AS builder

# WHAT: the directory this stage assembles and builds the distribution in.
# WHY : Assumptions: it is deliberately NOT the environment's own path. Keeping
#   the checkout at /opt/carddemo while the environment sits at /opt/venv stops
#   the two from overlapping, which is precisely what lets the runtime stage
#   copy the environment on its own and leave every trace of the build behind.
#   A working directory that contained the environment would make that
#   single-directory hand-off impossible to express without shipping the source
#   along with it.
WORKDIR /opt/carddemo

# WHAT: an isolated environment, rather than the base image's own
#   site-packages.
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

# WHAT: the dependency manifest on its own, ahead of any application source.
# WHY : Trade-offs: this is the deliberate layer ordering, and an extra COPY
#   instruction is what it costs. A build invalidates every layer from the
#   first one whose inputs changed onwards, so copying the source first would
#   re-run the entire dependency install on every edit to a reader or a codec
#   -- the files that change most often -- while requirements.txt changes
#   rarely. Ordered this way, an ordinary source edit reuses the cached
#   dependency layer. The accepted costs are that the build context is read in
#   two passes and that pyproject.toml has to be copied separately further down
#   even though it also carries dependency metadata.
COPY requirements.txt ./

# WHAT: install the pinned closure, accepting prebuilt artifacts only and
#   keeping no download cache.
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
# WHY : Trade-offs: the WHOLE manifest is installed, including the three
#   development distributions it groups separately (a linter, a test runner and
#   a coverage tool). Selecting only the runtime group would need either a
#   second manifest, which the migration plan does not define, or filtering a
#   comment-delimited block inside this build -- a step that would silently
#   drop a pin the day that block's wording changed. requirements.txt names
#   this Dockerfile as one of its three consumers precisely so that one file
#   governs all of them, and the price of honouring that is three pure-Python
#   distributions carried in the image.
RUN pip install --no-cache-dir --only-binary :all: -r requirements.txt

# WHAT: the packaging metadata and the importable tree, brought in only now
#   that the dependency layer above has settled.
# WHY : Assumptions: this package uses a src layout -- pyproject.toml declares
#   `where = ["src"]` -- so the directory below is the whole importable tree
#   and there is nothing further to bring in for the build. pyproject.toml
#   declares neither a `readme` nor a `license` file, which is what allows
#   these two paths alone to constitute a complete, buildable source
#   distribution.
COPY pyproject.toml ./
COPY src/ ./src/

# WHAT: install the distribution itself, without letting it resolve
#   dependencies.
# WHY : Assumptions: `--no-deps` is what protects the pin doctrine.
#   pyproject.toml declares its dependencies as lower bounds on purpose, so the
#   package stays installable beside other software -- which means a
#   dependency-resolving install here would be free to satisfy those bounds
#   with NEWER releases than the exact versions installed above, silently
#   replacing them and defeating the reproducibility this closure exists to
#   provide. The layer above already installed the authoritative set, so this
#   instruction has exactly one thing left to add: the package.
RUN pip install --no-cache-dir --no-deps .


# -----------------------------------------------------------------------------
# STAGE 2 -- runtime: the shipped image.
# -----------------------------------------------------------------------------
# WHY : Assumptions: the tag here is the SAME pin as the builder stage, for the
#   reason recorded there in full -- the virtual environment copied in below
#   records its creating interpreter's absolute path, so the two stages cannot
#   be allowed to diverge. Changing one obliges changing the other in the same
#   commit.
FROM python:3.13.14-slim-trixie

# WHAT: add the libpq CLIENT SHARED LIBRARY, and nothing else, from the suite
#   the base image already tracks.
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

# WHAT: two interpreter settings that shape how this container behaves under
#   the batch orchestrator.
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

# WHAT: a dedicated unprivileged account with fixed numeric identifiers.
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

# WHAT: the directory the container starts in, reusing the builder's path.
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

# WHAT: adopt the builder's virtual environment, left owned by root.
# WHY : Trade-offs: no `--chown` is applied, so the account that runs the ETL
#   can read and execute the installed code but cannot modify it. Handing
#   ownership to that account is the more common idiom and buys nothing here,
#   because nothing in a single-shot run writes to its own installation; what
#   it would cost is the guarantee that a compromised ETL process cannot
#   rewrite the code its next invocation will execute.
COPY --from=builder /opt/venv /opt/venv

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
#   sampled and discarded at run time.
# -----------------------------------------------------------------------------

# WHY : Assumptions: dropping privilege BEFORE the entry point is what makes
#   the unprivileged account the one the process actually runs as; a USER
#   placed after ENTRYPOINT would be parsed but would not change the running
#   identity. The numeric form is used rather than the account name so that a
#   policy which cannot read the image's /etc/passwd can still verify the
#   process is not root.
USER 10001:10001

# WHAT: the invariant half of the invocation, in exec form.
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
ENTRYPOINT ["python", "-m", "carddemo_migration.cli"]

# WHAT: a default argument list, so a bare `docker run` is informative.
# WHY : Assumptions: `--help` is chosen because it is the one argument the CLI
#   is guaranteed to accept. The catalogue of subcommands belongs to this
#   package's README, and naming one here would create a second place for that
#   contract to be stated and to drift out of step. This default is never what
#   the batch orchestrator runs, since its container override replaces CMD
#   outright, so it documents the image for a human rather than configuring it
#   for production.
CMD ["--help"]
