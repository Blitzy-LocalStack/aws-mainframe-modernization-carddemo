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

AAP section 0.4.4 names three shared shell elements, and `ui/src/layout/**`
authors each once rather than repeating it across the 21 screen routes. Their
consumers differ by design, so the table records which composes which:

| Shared element                | Composed by      | State                              |
| ----------------------------- | ---------------- | ---------------------------------- |
| `MessageBand`                 | each screen body | wired by all four authored screens |
| `ScreenHeader`                | each screen body | wired by all four authored screens |
| `PfKeyBar` (with `usePfKeys`) | each screen body | wired by all four authored screens |

The four authored screens are `signon`, `cardList`, `cardDetail` and `cardUpdate`.
Each composes all three elements, and
`ui/src/screens/cardScreenShell.test.tsx` plus
`ui/src/screens/signon/signon.test.tsx` assert that per screen -- the header band
naming that screen's own transaction and program, and the key bar painting exactly
the keys its mapset paints. The remaining 17 routes are not authored; each will
compose the same three when it lands.

`MessageBand` is composed per screen because the message it carries is that
screen's own outcome: it is the browser form of terminal row 23, which each
program populates and re-sends with its own map. A screen therefore renders
exactly one band and hands it text plus a severity, and no screen renders a raw
`Alert` of its own -- doing so would bypass the 75-character content contract of
`CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` (`app/cpy/CVCRD01Y.cpy` L28-L29) and
the reserved-space behaviour that keeps a message appearing or clearing from
moving the content around it.

`ScreenHeader` and `PfKeyBar` are composed **per screen**, for the same reason
`MessageBand` is: their content is per-screen data, not a constant.

Refactoring Rationale: this section previously said the opposite -- that both were
deliberately withheld from screens because their content is "constant across the
mapsets", pending an `AppShell.tsx` that would compose them once. That premise does
not hold, and the components' own interfaces are the first evidence against it:
`ScreenHeaderProps` requires `transactionId` and `programName`, and `PfKeyBarProps`
requires the `keys` bindings and an `onInvoke` callback. None of those four values
exists at shell level. The mapsets are the second and decisive evidence -- row 24
is **not** a shared literal:

| Mapset    | Legend literal it paints  |
| --------- | ------------------------- |
| `COSGN00` | `ENTER=Sign-on  F3=Exit`  |
| `COMEN01` | `ENTER=Continue  F3=Exit` |
| `COACTUP` | `F12=Cancel`              |

So a single shell-level bar could not render any screen's legend correctly, and
`usePfKeys` is built for the per-screen shape it actually has: a screen declares
which attention identifiers it handles, and the hook returns the bindings the bar
renders. Composing at shell level was never reachable from this API. The consequence
recorded honestly: `AppShell.tsx` is not authored and is **no longer required** for
either element.

The four legends the authored screens paint show why the per-screen shape is the
only workable one -- each is read from that screen's own mapset, and one screen
paints two legend fields rather than one:

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

- Node.js satisfying `ui/package.json` (`^22.22.0 || >=24.0.0`)
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
