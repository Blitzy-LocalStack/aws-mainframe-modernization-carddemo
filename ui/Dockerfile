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
#   This image takes no command arguments and reads no environment variable at
#   run time. Every value the SPA needs is compiled INTO the bundle by Vite at
#   build time from the VITE_-prefixed variables named in ui/.env.example
#   (VITE_API_BASE_URL, VITE_CORRELATION_ID_HEADER, VITE_API_TIMEOUT_MS), so the
#   build stage is where a deployment supplies them.
#
# Return values:
#   0 (integer): nginx exited after a requested shutdown.
#   non-zero (integer): the configuration failed to load or the process
#     terminated abnormally. ECS reads that status to decide whether to replace
#     the task.
#
# Exceptions or errors:
#   A type error, a lint violation or a Vite resolution failure aborts the build
#   stage, so a broken bundle is never copied into the runtime stage. At run time
#   an invalid ui/nginx.conf aborts before the listener binds and the task never
#   reports healthy.
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
#   ui/package.json declares in engines.node, which is ^22.22.0 || >=24.0.0 --
#   driven by react-router 8.3.0, the highest floor anywhere in the lock file. A
#   lower image would install a tree whose own constraint it violates and report
#   it only as an npm EBADENGINE warning.
# Refactoring Rationale: the reference is digest-pinned rather than left on the
#   bare tag, and the digest is the MULTI-PLATFORM INDEX digest read with
#   `docker buildx imagetools inspect`, not the platform manifest digest
#   `docker images` reports, so linux/amd64 and linux/arm64 builders both resolve
#   it. A patch-level tag reading like an exact version is the easiest kind of
#   mutable reference to mistake for an immutable one, so two builds of one commit
#   could otherwise ship two different toolchains with nothing in the log to say so.
FROM node:22.23.1-alpine@sha256:16e22a550f3863206a3f701448c45f7912c6896a62de43add43bb9c86130c3e2 AS build

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
RUN npm ci --no-audit --no-fund

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
EXPOSE 8080

USER carddemo:carddemo

# Assumptions: the probe speaks plain HTTP, unlike the service images. This origin
#   sits behind CloudFront, which terminates TLS at the edge, so nginx serves
#   cleartext inside the VPC and an HTTPS probe would fail on every healthy task.
# Assumptions: /healthz is probed rather than /index.html, because ui/nginx.conf
#   answers it from the server block itself with access logging off. Probing the
#   document would additionally assert that the bundle copied, which sounds
#   stronger but makes every probe a disk read and writes a log line every
#   30 seconds for the life of the task.
# Assumptions: wget rather than curl -- verified inside this exact digest, which
#   ships BusyBox wget and no curl -- so the probe needs no package installation.
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
