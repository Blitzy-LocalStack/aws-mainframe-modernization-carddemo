# =============================================================================
# ui/Dockerfile
# -----------------------------------------------------------------------------
# Purpose:
#   Builds the non-root container image that serves the CardDemo browser SPA: the
#   migrated form of the 17 base BMS mapsets under app/bms plus the 4 extension
#   mapsets, re-expressed as client-side routes. The image holds only static
#   assets and a web server; it reaches no database, consumes no queue and holds
#   no credential.
#
# Parameters:
#   This image takes no command arguments. It reads two environment variables at
#   RUN time, both consumed by docker-entrypoint.sh before nginx starts:
#     CARDDEMO_API_BASE_URL (required) -- the API base URL this container is to
#       call, either an absolute https URL ending in /api/v1 or the same-origin
#       path /api/v1. The entrypoint publishes it as /config.json in the document
#       root and derives the policy's connect-src origin from it.
#     CARDDEMO_API_CONNECT_SRC_ORIGINS (optional) -- additional https origins to
#       admit in connect-src, space separated, for a deployment that calls an
#       origin besides the API.
#   Refactoring Rationale: this section previously said the image "reads no
#   environment variable at run time" and that every value is compiled into the
#   bundle. The first half was true of the image as it stood and the second half
#   was not: the bundle is built BEFORE the environment that creates the API
#   endpoint exists, so VITE_API_BASE_URL is necessarily empty in it, and the
#   container therefore served an application whose every request was
#   unconfigured. The VITE_-prefixed variables in ui/.env.example remain build
#   inputs for `npm run dev` and for a locally built bundle; the deployed and
#   containerised paths are configured at run time by the document above.
#
# Return values:
#   0 (integer): nginx exited after a requested shutdown.
#   non-zero (integer): the runtime configuration was absent or malformed, the
#     configuration failed to load, or the process terminated abnormally. ECS
#     reads that status to decide whether to replace the task.
#
# Exceptions or errors:
#   A type error, a lint violation or a Vite resolution failure aborts the build
#   stage, so a broken bundle is never copied into the runtime stage. At run time
#   an unset or malformed CARDDEMO_API_BASE_URL, an unsafe connect-src origin, or
#   a document root that cannot be written aborts the entrypoint with a stated
#   reason on stderr and before the listener binds; an invalid ui/nginx.conf
#   aborts for the same reason, and in every case the task never reports healthy.
#
# Governing convention:
#   docs/CODE_DOCUMENTATION_STANDARD.md
#
#   From the repository root, build this image with:
#       docker build --file ui/Dockerfile --tag carddemo-ui:validation ui
#   Assumptions: the build context is ui/ rather than the repository root,
#   unlike the service images. Those compile an unpublished sibling module and
#   read config/checkstyle, both of which sit outside their own directory; this
#   package is self-contained -- its manifest, lock file, sources and server
#   configuration all live under ui/ -- so a narrower context copies less and
#   makes an accidental dependency on a file outside this tree fail immediately.
# =============================================================================

# Assumptions: the build stage's Node major version matches the floor
#   ui/package.json declares in engines.node, which is ^22.23.2 || >=24.18.1. A
#   lower image would install a tree whose own constraint it violates and report
#   it only as an npm EBADENGINE warning.
# Assumptions: what sets that floor is now a SECURITY release, not a package
#   constraint. react-router 8.3.0 remains the highest floor any dependency in the
#   lock file asks for and it only asks for ^22.12.0 || >=24.0.0; the floor is
#   raised past it because Node's 29 July 2026 release fixed eleven CVEs, three of
#   them HIGH, and 22.23.1 predates all three: CVE-2026-56846, in which an HTTP/2
#   peer evades the `maxSessionMemory` accounting that is the server's only bound
#   on per-session memory; CVE-2026-56848, a heap use-after-free reachable through
#   bundled nghttp2; and CVE-2026-58043, in which the Permission Model treats a
#   granted path as a radix prefix and so over-grants to sibling paths that merely
#   share leading characters. 22.23.2 and 24.18.1 are the first releases on their
#   respective lines carrying all three fixes, which is why the second arm of the
#   range is >=24.18.1 and not the >=24.0.0 the dependency floor alone would allow.
# Trade-offs: only the first two of those CVEs touch anything this stage does, and
#   neither is reached here -- nothing in `npm ci`, `tsc` or `vite build` serves
#   HTTP/2 or runs under the Permission Model, and the built assets this stage
#   emits are static files served by nginx in the runtime stage below, so no Node
#   process survives into production at all. The patch is taken regardless: the
#   alternative is shipping a build toolchain that a scanner correctly flags, and
#   arguing unreachability in a review every time, which costs more than one digest.
# Refactoring Rationale: the reference is digest-pinned rather than left on the
#   bare tag, and the digest is the MULTI-PLATFORM INDEX digest read with
#   `docker buildx imagetools inspect`, not the platform manifest digest
#   `docker images` reports, so linux/amd64 and linux/arm64 builders both resolve
#   it. A patch-level tag reading like an exact version is the easiest kind of
#   mutable reference to mistake for an immutable one, so two builds of one commit
#   could otherwise ship two different toolchains with nothing in the log to say so.
#   The digest below was re-read for 22.23.2 and its media type confirmed to be
#   application/vnd.oci.image.index.v1+json, so it is an index digest as required.
FROM node:22.23.2-alpine@sha256:c610fcdfb1d5b4740dd70c284ed3cb16bb857e0f7166196e36a5501df7a3aa32 AS build

WORKDIR /build

# Trade-offs: the manifest and lock file are copied before any source file so that
#   a source-only change reuses the resolved dependency layer. Two COPY
#   instructions instead of one make this file marginally longer and avoid
#   repeating a 310-package install, which dominates the cost of this build.
COPY package.json package-lock.json ./

# Assumptions: `npm ci` rather than `npm install`, because it installs exactly the
#   tree package-lock.json pins and fails if the manifest and the lock disagree.
#   `npm install` would silently resolve a newer satisfying version, which is how a
#   pinned toolchain stops being pinned -- and this package's TypeScript pin is
#   load-bearing: raising it without also raising typescript-eslint leaves the JSDoc
#   documentation gate installable and silently inert.
# Assumptions: neither --legacy-peer-deps nor --force appears, and the absence is
#   the decision rather than an oversight. Either flag makes npm resolve past a
#   peer conflict instead of refusing, which would defeat the peer range that holds
#   that TypeScript ceiling in place -- so the guard named just above would be
#   disarmed by the very command that installs it. A peer conflict reported here
#   means the version set in ui/package.json is wrong; the response is to correct
#   that manifest, never to widen this line.
# Trade-offs: --no-audit and --no-fund trim output and one registry round trip and
#   touch neither resolution nor verification -- every package's integrity hash
#   from the lock is still checked. Auditing belongs to CI, where a finding lands
#   in a reviewable run and can be triaged; left on here it would instead fail
#   image builds of an already-reviewed commit whenever an advisory is published
#   later, which makes the build non-reproducible for a reason that has nothing to
#   do with its inputs.
RUN npm ci --no-audit --no-fund

# Trade-offs: these are the second half of the split above and their position is
#   the mechanism -- every layer from here down is invalidated by any source edit,
#   so a one-character change in a screen component re-runs only the bundle step
#   and leaves the 310-package layer cached.
# Alternatives Considered: `COPY . .`, which is one line instead of three and was
#   rejected. It would send the host's own node_modules, resolved for a
#   developer's platform rather than this image's, straight over the tree npm ci
#   has just installed; it would carry a stale local dist that the runtime stage
#   below would then serve in place of this build's output; and it would pick up
#   any .env a developer happens to hold, baking one machine's values into a
#   shared artifact. ui/.dockerignore excludes exactly those paths, but naming the
#   inputs here is the stronger of the two guards: an ignore file records what must
#   not arrive, whereas this list records what the build actually reads, so a new
#   input that nobody copies fails the build outright instead of silently resolving
#   to whatever the context happened to hold.
COPY tsconfig.json tsconfig.node.json vite.config.ts index.html ./
COPY public ./public
COPY src ./src

# Assumptions: `npm run build` is invoked rather than `vite build` directly,
#   because that script runs the two TypeScript projects' checks first --
#   esbuild strips types without checking them, so a bare `vite build` emits a
#   bundle containing type errors quite happily. The image build therefore keeps
#   the compiler gate even though the full lint and test suites belong to CI.
RUN npm run build

# Assumptions: the stable nginx branch is chosen over mainline 1.31.x
#   deliberately. A static asset server needs no mainline feature, and stable
#   receives the longer patch window.
# Refactoring Rationale: digest-pinned for the same reason as the build stage, and
#   with the index digest for the same reason.
# Trade-offs: a second FROM rather than a single stage. The cost is this file's
#   length and a build that cannot simply be run in place; what it buys is a
#   runtime image carrying no toolchain whatsoever -- no Node, no npm, no resolved
#   package tree, no compiler and no source -- because only the built bundle and a
#   server cross the stage boundary. The ECR repository this image is pushed to
#   scans on push, and such a report cannot distinguish a finding against a
#   build-time dependency from one against a serving dependency, so each package
#   left behind here is a whole class of finding that can never be raised against
#   the deployed artifact.
FROM nginx:1.30.4-alpine@sha256:97d490c12ba55b4946b01546d1c3ed324e8d41ab1c9fcb2a616aa470620e5b46 AS runtime

# Alternatives Considered: reusing the image's own `nginx` account, which already
#   exists at UID 101. Rejected because that identity is the publisher's and can
#   move between patch releases, whereas the CardDemo task definitions do not drop
#   privileges and so the image must guarantee a fixed identity itself. 10001:10001
#   is the same identity every service image in this repository creates, so one
#   volume permission or task-level constraint applies uniformly.
# Assumptions: plain passwd and group records rather than adduser, because this
#   minimal image's BusyBox adduser would add no capability the two records do not
#   already give and would need a writable /etc/shadow.
RUN printf '%s\n' 'carddemo:x:10001:' >> /etc/group \
    && printf '%s\n' \
       'carddemo:!:10001:10001:CardDemo UI:/usr/share/nginx/html:/bin/false' \
       >> /etc/passwd

# Assumptions: every path nginx writes to is handed to the fixed identity
#   explicitly, because nginx ABORTS at start-up rather than degrading if it cannot
#   open its pid file or a worker temp directory. /var/cache/nginx holds the five
#   default worker temp paths; /run/nginx holds the pid file the entrypoint below
#   relocates there; the document root and the site directory are owned so a future
#   asset copy cannot need root.
# Refactoring Rationale: the pid file gets its OWN directory and the packaged
#   top-level configuration is repointed at it, rather than /run being granted
#   wholesale. Two failure modes were measured inside this exact digest and both are
#   silent-looking. Chowning /var/run grants nothing at all, because it is a symlink
#   to /run and `chown -R` does not follow a symlink argument -- nginx then aborts
#   with "open() /run/nginx.pid failed (13: Permission denied)", which reads like a
#   configuration error rather than a permission one. And overriding the path with a
#   `-g "pid ..."` global directive on the command line aborts too, with
#   "pid directive is duplicate", because the packaged file already declares one.
#   Editing that declaration and owning one subdirectory leaves /run itself
#   root-owned.
# Assumptions: the `user` directive is dropped in the same edit because a non-root
#   master cannot honour it; nginx ignores it with a warning on every start, and a
#   permanent warning trains a reader to ignore the log this container has almost
#   nothing else to say in.
RUN mkdir -p /var/cache/nginx /run/nginx \
    && sed -i \
       -e '/^user  *nginx;/d' \
       -e 's#^pid  *[^;]*;#pid        /run/nginx/nginx.pid;#' \
       /etc/nginx/nginx.conf \
    && grep -q '^pid        /run/nginx/nginx.pid;$' /etc/nginx/nginx.conf \
    && mkdir -p /etc/nginx/templates \
    && chown -R 10001:10001 \
       /var/cache/nginx /run/nginx /usr/share/nginx/html /etc/nginx/conf.d \
       /etc/nginx/templates

# Assumptions: the packaged default site is REPLACED rather than added beside,
#   because nginx includes every file in conf.d and two servers on port 8080 would
#   leave which one answers to include order. ui/nginx.conf is the single site
#   definition: it serves the SPA history fallback that makes all 21 client-side
#   routes resolve to one document, sets the security headers and the asset cache
#   policy, and publishes the /healthz endpoint the check below probes.
# Refactoring Rationale: it is installed as a TEMPLATE and no longer as the live
#   configuration. Its Content-Security-Policy carried `connect-src 'self' https:`,
#   which admits a fetch to any host on the internet -- the exfiltration path the
#   directive exists to close. The API origin genuinely is not knowable when this
#   image is built, but it IS knowable when the container starts, so the file now
#   carries a placeholder that docker-entrypoint.sh replaces with exact origins at
#   start. The image therefore stays environment-agnostic, so dev and prod still
#   share one reviewed artifact, while the header a browser receives names hosts.
#   The packaged default.conf is still replaced, by the rendered output, so the
#   include-order reasoning above is unchanged.
COPY --chown=10001:10001 nginx.conf /etc/nginx/templates/default.conf.template

# Assumptions: the renderer is copied executable rather than invoked through a
#   shell, so the ENTRYPOINT below stays in exec form and nginx still inherits
#   PID 1 through `exec`.
# Assumptions: it renders the policy AND publishes /config.json into the document
#   root copied below, which is why it must run as the same uid that owns that
#   directory. Both are start-time work for the same reason -- neither the API
#   origin nor the API base URL is knowable when this image is built.
COPY --chown=10001:10001 --chmod=0555 docker-entrypoint.sh /usr/local/bin/carddemo-entrypoint.sh

# Assumptions: the bundle is copied from the build stage's outDir, which
#   ui/vite.config.ts sets to `dist`. That is the third link of the chain that file
#   documents: Vite emits into dist, this instruction copies it, and nginx.conf
#   returns its index.html as the history fallback.
COPY --from=build --chown=10001:10001 /build/dist/ /usr/share/nginx/html/

# Assumptions: 8080 rather than the image's default 80, because a non-root process
#   cannot bind a privileged port and because the internal load balancer's target
#   group forwards to 8080 for every CardDemo service. ui/nginx.conf listens on the
#   same port and the two must agree.
# Assumptions: this instruction declares that contract for a reader and for
#   tooling and enforces nothing; the binding is ui/nginx.conf's `listen`. Editing
#   this number alone would leave the image serving 8080 while announcing another
#   port, and editing only the `listen` would leave an accurate declaration in
#   front of a port on which nothing answers -- so neither is ever changed by
#   itself.
EXPOSE 8080

# Assumptions: the named account resolves to the 10001:10001 records created
#   above; naming it rather than repeating the numbers keeps the non-root intent
#   legible in image metadata while those records keep the identity deterministic.
# Trade-offs: privileges are dropped HERE, after every instruction that needs to
#   write as root -- the ownership fixes and the bundle copy -- and before the
#   probe and the entry point, which need none. Dropping earlier would fail those
#   copies outright; not dropping at all is what the port above is coupled to,
#   because a root process could bind 80 whereas this one deliberately cannot, and
#   that is the whole reason ui/nginx.conf listens on 8080.
USER carddemo:carddemo

# Assumptions: the probe speaks plain HTTP, unlike the service images. This origin
#   sits behind CloudFront, which terminates TLS at the edge, so nginx serves
#   cleartext inside the VPC and an HTTPS probe would fail on every healthy task.
# Assumptions: /healthz is probed rather than /index.html, because ui/nginx.conf
#   answers it from the server block itself with access logging off. Probing the
#   document would additionally assert that the bundle copied, which sounds
#   stronger but makes every probe a disk read and writes a log line every
#   30 seconds for the life of the task.
# Refactoring Rationale: this note previously justified wget by asserting that the
#   image "ships BusyBox wget and no curl". The second half was measured false
#   inside this exact digest -- /usr/bin/curl is present, a real binary owned by
#   the curl-8.21.0-r0 package -- so the probe would have run either way and the
#   reason given did not hold. What actually decides it is durability rather than
#   availability: /usr/bin/wget is a symlink to /bin/busybox and so belongs to the
#   base userland, which no later `apk del` slimming step can take away, whereas a
#   separately packaged curl is exactly what such a step removes. Losing the
#   probe's binary does not fail loudly; it leaves a serving container permanently
#   unhealthy and restarted on a loop, with a healthy access log and nothing to
#   say why.
# Trade-offs: --spider asks for headers only and --tries=1 suppresses wget's own
#   internal retrying, so one probe is exactly one request and the retry policy
#   lives in --retries below instead of being split across two mechanisms that
#   would multiply into far more requests than either number suggests.
# Trade-offs: the four durations are configuration, and each is a bound rather
#   than a prediction. --start-period has only to cover nginx binding a listener
#   after the entrypoint renders one template and writes one small document; there
#   is no JVM to warm and no migration to run here, which is why it is 5s where
#   the service images need 30s. The probe's own --timeout=4 sits under the 5s
#   --timeout so wget reports the failure itself rather than being killed
#   mid-request, which keeps a slow response distinguishable from a dead one, and
#   that --timeout in turn stays under --interval so two probes cannot overlap.
#   --retries=3 then withholds the unhealthy verdict until three consecutive
#   misses, so an isolated dropped packet does not recycle a container that is
#   serving.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
    CMD ["/usr/bin/wget", "--quiet", "--tries=1", "--timeout=4", \
         "--spider", "http://127.0.0.1:8080/healthz"]

# Assumptions: the exec form with `daemon off` so nginx is PID 1 and receives
#   SIGTERM directly, letting it drain connections. Daemonising would fork the
#   master away from PID 1 and the task would be killed on timeout instead. That
#   property is PRESERVED through the renderer: it ends in `exec nginx -g 'daemon
#   off;'`, which replaces the shell rather than spawning a child, so nginx is
#   still PID 1 and still receives SIGTERM directly.
# Assumptions: the image's own /docker-entrypoint.sh is deliberately not used. Its
#   template rendering is guarded by a root check and is SKIPPED for a non-root
#   user, which this image is, so the placeholder would be served verbatim inside a
#   live header -- see docker-entrypoint.sh for the full reasoning.
ENTRYPOINT ["/usr/local/bin/carddemo-entrypoint.sh"]
