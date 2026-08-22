# CardDemo Web UI

> **Purpose.** Build, test, and run the React 19 single-page replacement for
> the CardDemo online screens.
>
> **Source of truth.** The 21 BMS mapsets — **17 under `app/bms/**`** plus **4 in
> the two extension trees**, `app/app-authorization-ims-db2-mq/bms/COPAU00.bms`
> and `COPAU01.bms` for the pending-authorization screens and
> `app/app-transaction-type-db2/bms/COTRTLI.bms` and `COTRTUP.bms` for the
> transaction-type reference screens — their symbolic maps,
> `ui/src/messages/messages.ts`, and the API contracts consumed by
> `ui/src/api/**`.
>
> Assumptions: the split is stated rather than rounded to "21 under `app/bms`",
> because the two extension trees are where the four optional-extension mapsets
> live and a reader who trusts the rounded form finds nothing at the path it
> names. `ls app/bms/*.bms` returns 17; the remaining four are the two `COPAU0*`
> and the two `COTRT*` mapsets above.

The UI uses Ant Design 6 components and tokens. It preserves field semantics,
message text, keyboard actions, and keyset navigation while replacing the fixed
24-by-80 character grid with responsive layouts.

## Layout composition

AAP section 0.4.4 names three shared shell elements, and `ui/src/layout/**` authors
each once rather than repeating it across the screen routes. `ui/src/layout/AppShell.tsx`
is the single frame: `ui/src/router.tsx` mounts it exactly once, as a pathless layout
route, and it owns all three zones. A screen supplies each zone's CONTENT and composes
none of them:

| Shared element            | Composed by | Content supplied by                       |
| ------------------------- | ----------- | ----------------------------------------- |
| `ScreenHeader` (rows 1-3) | `AppShell`  | every screen, through `useShellSlot`      |
| `MessageBand` (row 23)    | `AppShell`  | every screen, through `useShellSlot`      |
| `PfKeyBar` (row 24)       | `AppShell`  | every screen, through `useShellSlot`      |
| `ShellContentBoundary`    | `AppShell`  | nothing; it catches a failed route render |

All **21** screen modules under `ui/src/screens/**` are authored, routed and delegating.
Two contracts are gated rather than described, because a count in a document cannot fail:

- `ui/src/layout/screenHeaderClock.test.tsx` discovers the screen modules from the
  filesystem and asserts each one delegates its identity, its row-23 message and its
  resolved key bindings, composes no `ScreenHeader` and no `PfKeyBar` of its own, and
  publishes above every early return. It reads a comment-stripped source, so a screen
  that documents the components it no longer renders is not failed for explaining itself.
- `ui/src/routes/routeCensus.test.ts` asserts every authored module is imported by the
  route table and mounted on a route, and that no route names a module that is absent.

Two exceptions exist and both are asserted, not merely written down:

- **Five** screens keep a `MessageBand` inside their own body — `accountView`,
  `accountUpdate`, `cardDetail`, `cardUpdate` and `refTypeEdit`. Each is the row-22
  `INFOMSG` field its mapset declares SEPARATELY from the row-23 `ERRMSG` line the shell
  owns, so each row still has exactly one owner. The census admits a body band only when
  it names itself the information line.
- **One** screen delegates no paint instant: `authDetail`. Its own service contract
  renders `currentDate` and `currentTime` and the screen shows the service's values, so
  delegating an instant would paint two clocks on one screen. The census asserts that
  exemption set is exactly this one screen, in both directions.

Per-screen behaviour is asserted by the suites beside each screen and by
`ui/src/screens/menuScreens.test.tsx`, `cardScreens.test.tsx`, `accountScreens.test.tsx`,
`referenceAndAuthScreens.test.tsx`, `entryScreens.test.tsx` and
`authDetailScreen.test.tsx`; the frame itself by `ui/src/layout/appShell.test.tsx` and
`appShellIntegration.test.tsx`, the lazy-render failure path by
`shellContentBoundary.test.tsx`, and the session store behind it by
`ui/src/hooks/useAuth.test.tsx`. A suite that renders a screen directly mounts the real
`AppShell` around it, because a bare mount would leave all three delegated zones rendered
by nothing.

### Why the frame renders the legend but binds no key

The shell installs **no keyboard listener at all** and offers sign-off as a rendered
control (`SHELL_SIGN_OFF_LABEL`). `usePfKeys` installs one document listener per call site
with no arbitration, and PF12 is `F12=Cancel` on account update, card update and user
update — so a shell that also bound PF12 would end the session on the keystroke that
cancels an edit. An earlier revision made that a per-mount `ownsFunctionKeys` prop; the
prop is gone, because a gate has to be passed correctly at every mount site and this one
was already omitted at the production route table while two isolated test renders passed
it. `routerReachability.test.tsx` asserts the property from the outside instead.

### Why the legend's CONTENT stays per screen

Row 24 is not a shared literal, and neither is the value a screen would have to supply --
`PfKeyBarProps` requires resolved bindings and an `onInvoke` dispatcher, and both exist
only where the keys are bound:

| Mapset    | Legend literal it paints                               |
| --------- | ------------------------------------------------------ |
| `COSGN00` | `ENTER=Sign-on  F3=Exit`                               |
| `COMEN01` | `ENTER=Continue  F3=Exit`                              |
| `COACTUP` | `F12=Cancel`, one of the three legend fields it paints |

So a shell-level bar rendering a legend of its OWN could not be right for any screen. What
the shell does instead is render the legend a screen has delegated and forward each
activation back to that screen's own dispatcher, which is why the census checks
`onInvoke` is the screen's `usePfKeys` result rather than a handler of the screen's making:
a legend control and a keystroke must reach the same code.

Four legends show why the content is irreducibly per-screen -- each is read from that
screen's own mapset, and one screen paints two legend fields rather than one:

| Screen       | Mapset    | Legend the mapset paints            | Note                                                                                          |
| ------------ | --------- | ----------------------------------- | --------------------------------------------------------------------------------------------- |
| `signon`     | `COSGN00` | `ENTER=Sign-on  F3=Exit`            | two keys                                                                                      |
| `cardList`   | `COCRDLI` | `  F3=Exit F7=Backward  F8=Forward` | no Enter legend, though the program accepts Enter; `COLOR=TURQUOISE`, not the yellow majority |
| `cardDetail` | `COCRDSL` | `ENTER=Search Cards  F3=Exit`       | two keys                                                                                      |
| `cardUpdate` | `COCRDUP` | `ENTER=Process F3=Exit`             | plus a second, `ATTRB=(ASKIP,DRK)` field carrying `F5=Save F12=Cancel`                        |

Two of those are the cases a uniform bar would get wrong. `cardList` accepts Enter but
paints no legend for it, so its Enter binding carries an empty label -- which `usePfKeys`
defines as a keyboard-only handler and `PfKeyBar` renders no control for. `cardUpdate`'s
second legend field is non-display until the program reaches its confirmation state, so
that screen registers the save key only once edits have been validated and gives the
cancel key a label only at the same point, even though the key itself is accepted earlier.

No screen renders a raw `Alert` of its own: doing so would bypass the 75-character content
contract of `CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` (`app/cpy/CVCRD01Y.cpy` L28-L29) and
the reserved-space behaviour that keeps a message appearing or clearing from moving the
content around it.

## Prerequisites

- Node.js satisfying `ui/package.json` (`^22.23.2 || >=24.18.1`) — this is a
  security floor rather than a compatibility one: the highest floor any dependency
  in the lock file asks for is 22.22.0, and the range sits above it because 22.23.2
  and 24.18.1 are the first releases on their lines carrying the three HIGH fixes
  from Node's 29 July 2026 security release. `ui/package.json` records the CVE
  identifiers, and the range moves again with the next advisory.
- npm with the committed `package-lock.json`
- a non-secret API base URL ending in `/api/v1`

## Configure

```bash
# WHAT: create the ignored local environment file from the value-free template.
# WHY : Assumptions: every VITE_ value is compiled into the public bundle, so
#       this file may contain endpoints and timeouts but never a credential.
cp ui/.env.example ui/.env
```

Populate `VITE_API_BASE_URL`, `VITE_CORRELATION_ID_HEADER`, and
`VITE_API_TIMEOUT_MS`. The SPA contains no Cognito client secret and does not
authenticate directly with the user pool: it posts credentials to
`auth-service`'s published sign-on operation and holds only the tokens that
operation returns.

Assumptions: those tokens are held **in memory only** and are written to no browser store — no
`sessionStorage` key, no `localStorage` key and no cookie. A client-side route change keeps the
session; a page reload, a restored tab or a second tab holds nothing and the operator signs on
again. That is a deliberate reduction in session durability, taken to remove a credential exposure
rather than to narrow it, and it is registered as `D-SESSION-NOT-PERSISTED` in
`docs/architecture/cobol-to-service-traceability.md` with the two rejected alternatives argued in
full. `ui/.env.example` states the same for a reader who never opens that document.

Assumptions: **`VITE_API_BASE_URL` configures a local development server only.**
Vite inlines it at build time, so it cannot carry a deployed environment's
endpoint -- that endpoint is created by the same infrastructure apply that
provisions the environment, which runs after the bundle is built. A deployed
environment is configured instead by a `config.json` document published beside
the bundle and read at start-up, and the published document takes precedence
wherever it exists. See `ui/src/api/runtimeConfig.ts`, which records the full
reasoning, and note that the value must include the `/api/v1` prefix in either
form because operations are addressed relatively by the client.

### Container runtime configuration

Assumptions: the `VITE_` values above are **build-time** inputs that Vite inlines into the public
bundle, so they configure `npm run dev` and a locally built bundle and nothing else. The container
image is configured at **run time** instead, by two variables that deliberately carry no `VITE_`
prefix — they are read by a shell before nginx starts and must not be inlined into a bundle.

| Variable                           | Required | Read by                                   | Effect                                                                                                                                    |
| ---------------------------------- | -------- | ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `CARDDEMO_API_BASE_URL`            | yes      | `docker-entrypoint.sh` at container start | Published as `/config.json` in the document root and, when it is absolute, its origin is added to the policy's `connect-src`              |
| `CARDDEMO_API_CONNECT_SRC_ORIGINS` | no       | `docker-entrypoint.sh` at container start | Space-separated bare `https://host[:port]` origins added to the same `connect-src`, for a deployment that calls an origin besides the API |

`CARDDEMO_API_BASE_URL` accepts exactly two shapes, and the entrypoint **refuses to start** on
anything else with the reason on stderr:

- an absolute `https://host[:port]/api/v1` — an intermediate path segment is allowed, so
  `https://host/prod/api/v1` is accepted for an API published under a named stage; or
- the same-origin absolute path `/api/v1`, for a deployment that serves the API from the same origin
  as the bundle. This form contributes no `connect-src` origin, because `default-src 'self'` already
  covers the call.

Refused, each with a stated reason: unset or blank, cleartext `http://` (including loopback — a
loopback address inside a container names the container itself, so it can never address an API; use
`npm run dev` for cleartext local work), a trailing slash, a query, a fragment, a wildcard, embedded
whitespace, and any value not ending in `/api/v1`.

```bash
# WHAT: run the built image against one exact API, with the policy derived from it.
# WHY : Assumptions: the API base URL is supplied at START rather than baked in at build, so one
#       reviewed image serves every environment -- the bundle is built before the environment that
#       creates the API endpoint exists. The entrypoint publishes it as /config.json and unions its
#       origin into connect-src, so the configuration and the policy cannot disagree; an unset or
#       malformed value REFUSES TO START rather than serving an application whose every request is
#       unconfigured.
docker run --rm -p 8080:8080 \
  -e CARDDEMO_API_BASE_URL="https://<api-host>/api/v1" \
  carddemo-ui:local
```

The published document is exactly `{"apiBaseUrl": "<value>"}`, written to a staging file and moved
into place so a reader never sees a partial document. `nginx.conf` serves it `no-store`, and
`ui/src/api/runtimeConfig.ts` fetches it before the application mounts: it takes precedence over
`VITE_API_BASE_URL` wherever it exists, and if neither source yields a usable base URL the
application **mounts nothing** and reports a start-up failure rather than rendering screens whose
every request would fail.

Note that the container is **not** the deployed delivery path — `infra/modules/cloudfront-spa`
serves the bundle from a private S3 origin and sets the same headers itself, and the deploy runbook
generates the same `{"apiBaseUrl": "..."}` document from Terraform outputs before syncing. The image
exists for local and container use; the two paths publish one document shape and keep their policies
identical directive for directive.

## Develop

```bash
# WHAT: install exactly the lock-file graph and start the Vite development server.
# WHY : Assumptions: npm ci fails on manifest drift instead of silently choosing
#       a different dependency version.
cd ui
npm ci
npm run dev
```

## Validate

```bash
# WHAT: run all build-failing UI gates and produce the deployable bundle.
# WHY : Assumptions: type, JSDoc, interaction, and bundling failures are
#       independent; all four commands are required evidence.
npm run typecheck
npm run lint
npm test
npm run build
```

Production builds publish **no source maps**: `ui/vite.config.ts` sets `build.sourcemap` to
`false`, so no `.map` file and no `sourceMappingURL` comment reaches the served bundle. A published
map exposes the original module structure and every explanatory comment in it, which is an
information disclosure on an origin serving a card-management application; the cost accepted is that
a production stack trace names generated positions, and the fix for a defect found there is to
reproduce it against `npm run dev` or a local `vite build --sourcemap`. The output directory is
`ui/dist/` and is ignored by Git.

## Preview

A production bundle reads its endpoint from `/config.json` at start-up, so the
previewed directory has to contain that document before the bundle is served.
Write it into `ui/dist/` first and then start the preview server:

```bash
# WHAT: publish a runtime configuration document into the built bundle, then serve it.
# WHY : Refactoring Rationale: the bare `npm run preview` this replaces did not work. Vite's
#       preview server is an SPA server, so a request for a MISSING /config.json is answered with
#       index.html under HTTP 200 rather than 404 -- measured as `status=200 type=text/html`. The
#       start-up loader treats any 2xx as a published document, fails to parse HTML as JSON and
#       rejects, so `ui/src/main.tsx` mounts nothing and writes the verbatim abend sentence into the
#       root element. A 404 is the only absent answer the loader recognises, which is what
#       `ui/nginx.conf` returns for this path and what a plain static server does not.
# WHY : Assumptions: the value is an ABSOLUTE URL and carries the /api/v1 prefix, because every
#       operation is addressed relatively against it -- `ui/src/api/client.ts` parses it with `URL`
#       and refuses a relative value, and it admits plain HTTP only for a loopback host. It names
#       the preview server's OWN origin, port 4173, for the same-origin reason argued below. This
#       document overrides the build-time VITE_API_BASE_URL wherever it exists.
cd ui
printf '{"apiBaseUrl":"http://localhost:4173/api/v1"}\n' > dist/config.json
npm run preview
```

Assumptions: the SPA and the API it addresses have to be served from **one
origin**. `ui/nginx.conf` is deliberately static-only — it proxies nothing — and
no service publishes a CORS configuration, so a cross-origin `apiBaseUrl` is
refused by the browser rather than by anything this package could report. The
preview server serves no API of its own, so with the value above the application
mounts and every screen paints while a data request answers with the error the
screen reports; exercising a real request means putting one reverse proxy in front
of both the bundle and the gateway and naming that proxy's origin here instead.

Trade-offs: previewing this way verifies the production artifact rather than
Vite's development transforms, and it is still not a substitute for the container
path. Only `docker run` of the image built above exercises `ui/nginx.conf` — its
`try_files $uri =404` rule for dotted paths, the content-security policy the
entrypoint renders, and the cache directives — and neither path exercises
CloudFront headers or TLS.
