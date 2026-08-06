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

| Shared element                | Composed by      | State                                              |
| ----------------------------- | ---------------- | -------------------------------------------------- |
| `MessageBand`                 | each screen body | wired by `cardDetail`, `cardList` and `cardUpdate` |
| `ScreenHeader`                | the app shell    | awaiting `AppShell.tsx`                            |
| `PfKeyBar` (with `usePfKeys`) | the app shell    | awaiting `AppShell.tsx`                            |

`MessageBand` is composed per screen because the message it carries is that
screen's own outcome: it is the browser form of terminal row 23, which each
program populates and re-sends with its own map. A screen therefore renders
exactly one band and hands it text plus a severity, and no screen renders a raw
`Alert` of its own -- doing so would bypass the 75-character content contract of
`CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` (`app/cpy/CVCRD01Y.cpy` L28-L29) and
the reserved-space behaviour that keeps a message appearing or clearing from
moving the content around it.

`ScreenHeader` and `PfKeyBar` are deliberately **not** composed by a screen.
Their content is constant across the mapsets -- the transaction and program slots
with the two shared titles and the paint-time clock on row 1 to 3, and the
function-key legend on row 24 -- so composing either inside a screen body would
repeat it 21 times and contradict the split the AAP fixes. Their single intended
consumer is `AppShell.tsx`, which is not yet authored; each module's own header
records that expectation, so their present lack of an importer is the documented
composition order rather than dead code.

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
authenticate directly with the user pool.

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
