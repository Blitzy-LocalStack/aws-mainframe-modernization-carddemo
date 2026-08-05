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
