# CardDemo Web UI

> **Purpose.** Build, test, and run the React 19 single-page replacement for
> the CardDemo online screens.
>
> **Source of truth.** The 21 BMS mapsets under `app/bms/**`, their symbolic
> maps, `ui/src/messages/messages.ts`, and the API contracts consumed by
> `ui/src/api/**`.

The UI uses Ant Design 6 components and tokens. It preserves field semantics,
message text, keyboard actions, and keyset navigation while replacing the fixed
24-by-80 character grid with responsive layouts.

## Layout composition

AAP section 0.4.4 names three shared shell elements, and `ui/src/layout/**` authors
each once rather than repeating it across the screen routes. Their consumers
differ by design, so the table records which composes which:

| Shared element                | Composed by                           | State                                                           |
| ----------------------------- | ------------------------------------- | --------------------------------------------------------------- |
| `MessageBand`                 | each screen body                      | wired by all 12 authored screens                                |
| `ScreenHeader`                | 10 screen bodies; `AppShell` for 2    | `accountView` and `authSummary` delegate through `useShellSlot` |
| `PfKeyBar` (with `usePfKeys`) | each screen body                      | wired by all 12 authored screens                                |
| `AppShell`                    | `ui/src/router.tsx` as a layout route | wraps every route; mounted with `ownsFunctionKeys={false}`      |

The 12 authored screens are `signon`, `menu`, `admin`, `accountView`,
`accountUpdate`, `cardList`, `cardDetail`, `cardUpdate`, `transactionAdd`,
`authSummary`, `userUpdate` and `refTypeList`. Every one of them is mounted in
`ui/src/router.tsx`, and `ui/src/routerReachability.test.tsx` walks each declared
path to prove it resolves to a screen rather than to the not-found result. The
remaining 9 of the 21 routes are not authored yet; each will compose the same
elements when it lands.

Per-screen behaviour is asserted by `ui/src/screens/signon/signon.test.tsx`,
`menuScreens.test.tsx`, `cardScreenShell.test.tsx`, `cardScreens.test.tsx`,
`accountScreens.test.tsx`, `referenceAndAuthScreens.test.tsx` and
`entryScreens.test.tsx`; the frame itself by `ui/src/layout/appShell.test.tsx`
and the session store behind it by `ui/src/hooks/useAuth.test.tsx`.

`AppShell` is mounted with `ownsFunctionKeys={false}` deliberately. It otherwise
binds PF12 to sign-off, and PF12 is `F12=Cancel` on three of the authored
screens; `usePfKeys` installs one document listener per call site with no
arbitration, so leaving the frame's binding in place would make one keystroke
both cancel an edit and end the session.

`MessageBand` is composed per screen because the message it carries is that
screen's own outcome: it is the browser form of terminal row 23, which each
program populates and re-sends with its own map. A screen therefore renders
exactly one band and hands it text plus a severity, and no screen renders a raw
`Alert` of its own -- doing so would bypass the 75-character content contract of
`CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` (`app/cpy/CVCRD01Y.cpy` L28-L29) and
the reserved-space behaviour that keeps a message appearing or clearing from
moving the content around it.

`PfKeyBar` is composed **per screen**, for the same reason `MessageBand` is: its
content is per-screen data, not a constant. `ScreenHeader` is composed per screen
by eight of the ten and delegated by two, because its content is per-screen data
that a screen can hand upwards as two strings -- which the bands cannot be, being
live state rather than identity.

The reason no screen's key bar can move to the frame is the mapsets: row 24 is
**not** a shared literal, and neither is the value a screen would have to supply --
`PfKeyBarProps` requires resolved bindings and an `onInvoke` callback, both of which
exist only where the keys are bound:

| Mapset    | Legend literal it paints  |
| --------- | ------------------------- |
| `COSGN00` | `ENTER=Sign-on  F3=Exit`  |
| `COMEN01` | `ENTER=Continue  F3=Exit` |
| `COACTUP` | `F12=Cancel`              |

So a single shell-level bar could not render any screen's legend correctly, and
`usePfKeys` is built for the per-screen shape it actually has: a screen declares
which attention identifiers it handles, and the hook returns the bindings the bar
renders. What the frame does instead is RENDER a legend a screen has delegated,
forwarding activations back to that screen's own dispatcher, and bind no key of its
own -- `RETIRED_SHELL_FUNCTION_KEY` in `ui/src/layout/AppShell.tsx` records the one
key it used to bind and why it stopped: the hook installs a listener per call site
with no ownership registry, and the three update screens bind F12 as cancel, so a
frame-level F12 would have discarded an edit and ended the session on one keypress.
Sign-off is a visible control in the frame instead.

Four of the ten legends show why the per-screen shape is the only workable one --
each is read from that screen's own mapset, and one screen paints two legend fields
rather than one:

| Screen       | Mapset    | Legend the mapset paints            | Note                                                                                          |
| ------------ | --------- | ----------------------------------- | --------------------------------------------------------------------------------------------- |
| `signon`     | `COSGN00` | `ENTER=Sign-on  F3=Exit`            | two keys                                                                                      |
| `cardList`   | `COCRDLI` | `  F3=Exit F7=Backward  F8=Forward` | no Enter legend, though the program accepts Enter; `COLOR=TURQUOISE`, not the yellow majority |
| `cardDetail` | `COCRDSL` | `ENTER=Search Cards  F3=Exit`       | two keys                                                                                      |
| `cardUpdate` | `COCRDUP` | `ENTER=Process F3=Exit`             | plus a second, `ATTRB=(ASKIP,DRK)` field carrying `F5=Save F12=Cancel`                        |

Two of those are worth calling out because they are the cases a uniform bar would
get wrong. `cardList` accepts Enter but paints no legend for it, so its Enter
binding carries an empty label -- which `usePfKeys` defines as a keyboard-only
handler and `PfKeyBar` renders no control for. `cardUpdate`'s second legend field
is non-display until the program reaches its confirmation state, so that screen
registers the save key only once edits have been validated and gives the cancel key
a label only at the same point, even though the key itself is accepted earlier.

## Prerequisites

- Node.js satisfying `ui/package.json` (`^22.23.2 || >=24.18.1`) — this is a
  security floor rather than a compatibility one: the highest floor any dependency
  in the lock file asks for is 22.22.0, and the range sits above it because 22.23.2
  and 24.18.1 are the first releases on their lines carrying the three HIGH fixes
  from Node's 29 July 2026 security release. `ui/package.json` records the CVE
  identifiers, and the range moves again with the next advisory.
- npm with the committed `package-lock.json`
- a non-secret API base URL

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

Assumptions: the values above are **build-time** `VITE_` variables that Vite inlines into the public
bundle. The container image additionally reads one **runtime** variable, and it is deliberately not a
`VITE_` value because it configures the web server rather than the bundle.

| Variable                           | Read by                                   | Effect                                                                                                                |
| ---------------------------------- | ----------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `CARDDEMO_API_CONNECT_SRC_ORIGINS` | `docker-entrypoint.sh` at container start | Space-separated bare `https://host[:port]` origins written into the content-security policy's `connect-src` directive |

```bash
# WHAT: run the built image with the policy narrowed to one exact API origin.
# WHY : Assumptions: the origin is supplied at START rather than baked in at build, so one reviewed
#       image serves every environment. The entrypoint REFUSES TO START on a wildcard, a path or a
#       non-HTTPS scheme, and an unset variable renders `connect-src 'self'` -- which fails closed and
#       matches what the deployed CloudFront path serves for an empty origin list.
docker run --rm -p 8080:8080 \
  -e CARDDEMO_API_CONNECT_SRC_ORIGINS="https://<api-host>" \
  carddemo-ui:local
```

Note that the container is **not** the deployed delivery path — `infra/modules/cloudfront-spa`
serves the bundle from a private S3 origin and sets the same headers itself. The image exists for
local and container use, and the two policies are kept identical directive for directive.

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

Production builds do not publish source maps. The output directory is
`ui/dist/` and is ignored by Git.

## Preview

```bash
# WHAT: serve the already-built production bundle locally.
# WHY : Trade-offs: preview verifies the production artifact rather than Vite's
#       development transforms, but it is not a substitute for CloudFront
#       headers, TLS, or API integration validation.
npm run preview
```
