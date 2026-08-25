# CardDemo Web UI

> **Purpose.** Build, test, run and containerise the React 19 single-page application that replaces
> the CardDemo 3270/BMS online screens. This is the per-package guide: the top-level
> [`MIGRATION_README.md`](../MIGRATION_README.md) delegates every UI-specific detail here rather
> than restating it, because a single source of truth cannot desynchronise from itself whereas
> duplicated build steps inevitably drift.
>
> **Source of truth.** The 21 BMS mapsets — **17 under `app/bms/`** plus **4 in the two extension
> trees**: `app/app-authorization-ims-db2-mq/bms/COPAU00.bms` and `COPAU01.bms` for the
> pending-authorization screens, and `app/app-transaction-type-db2/bms/COTRTLI.bms` and
> `COTRTUP.bms` for the transaction-type reference screens. Their symbolic maps in `app/cpy-bms/`,
> the 18 online `app/cbl/CO*.cbl` programs, the message and attribute copybooks in `app/cpy/`,
> `ui/src/messages/messages.ts`, and the API contracts consumed by `ui/src/api/`. House style follows
> [`tests/README.md`](../tests/README.md); the documentation obligation follows
> [`docs/CODE_DOCUMENTATION_STANDARD.md`](../docs/CODE_DOCUMENTATION_STANDARD.md).
>
> Assumptions: the 17-plus-4 split is stated rather than rounded to "21 under `app/bms`", because
> the four optional-extension mapsets live in the two extension trees and a reader who trusts the
> rounded figure finds nothing at the path it names: `ls app/bms/*.bms` returns 17.

> **Precedence.** Where this README and the `scripts` block in [`package.json`](package.json)
> disagree, **the `scripts` block is authoritative** — it is what CI executes. The same holds for
> every version: [`package.json`](package.json) owns them, and a number repeated here is a
> convenience that can go stale. Open a fix rather than letting the two diverge.

The UI is composed entirely from Ant Design components and tokens. It preserves field semantics,
message text, keyboard actions and keyset navigation while replacing the fixed 24-by-80 character
grid with responsive layouts.

## 1. Overview

A React 19 and TypeScript single-page application, built with Vite, that re-implements **all 21
online screens** as client-side routes. It is served either from S3 behind CloudFront
(`infra/modules/cloudfront-spa`) or from the nginx container image built by this package's
[`Dockerfile`](Dockerfile).

The goal is exact, and it is worth stating before anything else because every decision below follows
from it: **re-implement all 21 online screens as a browser SPA that preserves field semantics,
keyboard workflow, message text and validation behaviour, while abandoning fixed character-cell
positioning.**

### 1.1 Why this package exists at all

This folder is the product of a decision rather than a default. The migration brief made a thin web
UI conditional — "if in scope". Leaving the 3270 screens unreplaced would have stranded every one of
the 18 online transactions with no way to reach them, so the UI was brought explicitly into scope
and the choice recorded as decision **D6** in
[`docs/adr/ADR-006-api-and-ui.md`](../docs/adr/ADR-006-api-and-ui.md), which also argues REST/JSON
over gRPC for a browser client and cites this package's contract test by name.

### 1.2 The migration is additive

`app/**` is read as the design and behaviour source and is never written to. The COBOL programs,
copybooks, mapsets, JCL and seed data stay byte-identical, which is what allows them to keep serving
as the behavioural oracle. `tests/**`, `scripts/**` and `samples/**` are likewise untouched. Nothing
in this document asks a reader to alter any of them; every instruction that names one is read-only —
a `grep`, a measurement, a citation.

Assumptions: the baseline remaining runnable is a deliberate property and not an accident of
sequencing. The mainframe path is not deprecated by this work — the migration adds a delivery path
beside it and removes none — so the original screens, programs and jobs are still exactly where they
were, which is also what makes the rollback in
[`docs/runbooks/teardown.md`](../docs/runbooks/teardown.md) require no un-migration step.

## 2. Directory layout

```text
ui/
├── package.json                 # manifest: exact pins, engines floor, the ten scripts, and the
│                                #   carddemoDependencyRationale key that holds pin reasoning
├── package-lock.json            # the exact resolved graph; `npm ci` installs from this alone
├── tsconfig.json                # browser sources under src/ — no @types/node
├── tsconfig.node.json           # vite.config.ts and vitest.config.ts, which run under Node
├── vite.config.ts               # build: outDir dist/, no source maps in production
├── vitest.config.ts             # merges vite.config, adds jsdom + src/test/setup.ts
├── eslint.config.js             # flat config; the eslint-plugin-jsdoc documentation gate
├── .prettierrc                  # YAML so each option carries its reasoning; printWidth 100, LF
├── index.html                   # SPA entry document; the only element React mounts into
├── .env.example                 # build-time variable NAMES with no values, plus the forbidden set
├── Dockerfile                   # multi-stage: node build → nginx runtime, non-root, digest-pinned
├── docker-entrypoint.sh         # start-time work: renders the CSP origins, publishes /config.json
├── nginx.conf                   # installed as a template; SPA history fallback, headers, /healthz
├── .dockerignore                # keeps node_modules, dist and any local .env out of the context
├── documentationGate.test.ts    # asserts the JSDoc gate actually fires; run by Vitest
├── README.md                    # this file
├── public/
│   └── favicon.svg              # the only static asset copied verbatim into the bundle
└── src/
    ├── main.tsx                 # loads runtime configuration, then mounts — or reports and stops
    ├── App.tsx                  # ConfigProvider + the single theme, wrapped around the router
    ├── router.tsx               # the route table, derived from the XCTL graph; access tiers
    ├── env.d.ts                 # types for the VITE_ variables this package reads
    ├── vite-env.d.ts            # Vite client ambient types
    ├── theme/
    │   ├── tokens.ts            # the BMS→antd token bridge, with every measured source count
    │   └── antdTheme.ts         # the one ConfigProvider theme object; the only styling site
    ├── layout/
    │   ├── AppShell.tsx         # the single frame; mounted once as a pathless layout route
    │   ├── ScreenHeader.tsx     # rows 1-3: the title band from COTTL01Y constants
    │   ├── ScreenTitle.tsx      # the per-screen heading inside that band
    │   ├── MessageBand.tsx      # row 23: the 75-character message contract
    │   ├── PfKeyBar.tsx         # row 24: the function-key legend, rendered as controls
    │   ├── usePfKeys.ts         # the keyboard bindings, with PF13-PF24 aliasing
    │   ├── ShellContentBoundary.tsx  # catches a failed or unloadable route render
    │   ├── fieldHelp.tsx        # the field-error contract: validateStatus, help, '*' marker
    │   └── recordLayout.ts      # copybook PICTURE widths, so maxLength is never a literal
    ├── api/
    │   ├── client.ts            # the axios instance: bearer token, correlation id, timeout
    │   ├── runtimeConfig.ts     # fetches /config.json before mount; overrides the build value
    │   ├── serverClock.ts       # the paint instant comes from the server, not the browser
    │   ├── masking.ts           # the shapes a masked account number and path segment must match
    │   ├── types.ts             # DTO types mirroring the service OpenAPI documents
    │   ├── auth.ts              # one module per service contract: sign-on and user maintenance
    │   ├── accounts.ts          # account and customer operations
    │   ├── cards.ts             # card list, detail and update
    │   ├── transactions.ts      # transaction list, detail, add and bill payment
    │   ├── reference.ts         # transaction types, categories and date conversion
    │   ├── authorization.ts     # pending authorizations and fraud marking
    │   └── reporting.ts         # report submission
    ├── format/
    │   └── money.ts             # the COBOL edit masks; money never becomes a JavaScript number
    ├── messages/
    │   └── messages.ts          # every user-visible string, verbatim, keyed to its source line
    ├── hooks/
    │   ├── useAuth.ts           # token and group state; the identity half of the old COMMAREA
    │   ├── usePagedQuery.ts     # keyset paging, bound to PF7 and PF8
    │   └── useServerInstant.ts  # the shared server-supplied instant
    ├── routes/
    │   ├── navigation.ts        # every route path, declared exactly once
    │   ├── programRoutes.ts     # the program-name→route map the two menus resolve against
    │   ├── cards.ts             # the composite card selector and its two routes
    │   └── guards.tsx           # the access tiers, and the shell-slot publication hook
    ├── screens/                 # exactly 21 directories, one per mapset — see section 7
    └── test/
        ├── setup.ts             # the Vitest setup file named by vitest.config.ts
        ├── apiHarness.ts        # service-response doubles shared across screen suites
        ├── sessionHarness.ts    # signed-on session doubles
        ├── identityToken.ts     # token minting for the group-claim assertions
        └── *.test.tsx           # the per-screen suites
```

Everything under `node_modules/` and `dist/` is produced, never committed: the root
[`.gitignore`](../.gitignore) ignores both unanchored.

## 3. Prerequisites

- **Node.js** satisfying the `engines.node` range in [`package.json`](package.json), which is
  `^22.23.2 || >=24.18.1`. [`.github/workflows/ui-ci.yml`](../.github/workflows/ui-ci.yml) pins
  `22.23.2` and the container build stage uses the same line.
- **npm**, with the committed [`package-lock.json`](package-lock.json).
- A non-secret API base URL ending in `/api/v1` — see section 11.

Assumptions: that range is a **security floor and no longer a dependency floor**, which is why it
sits above every constraint the tree itself declares. The highest `engines.node` any entry in the
lock file asks for is 22.22.0, set by `react-router` 8.3.0. The range is raised past it because
Node's 29 July 2026 release fixed eleven CVEs, three rated HIGH, and every 22.x below 22.23.2
predates all three: an HTTP/2 peer evading the `maxSessionMemory` accounting that bounds per-session
memory, a heap use-after-free in bundled nghttp2, and a Permission Model that treats a granted path
as a radix prefix and so over-grants to sibling paths sharing leading characters.
[`package.json`](package.json) records the CVE identifiers against the range itself.

Trade-offs: the second arm is `>=24.18.1` rather than the `>=24.0.0` the dependency floor alone
would permit, because 24.18.1 is the first release on the 24.x line carrying the same three fixes.
Stating `>=24.0.0` beside a 22.23.2 floor would leave a range admitting a 24.x build strictly more
exposed than the oldest 22.x it accepts, which is the asymmetry an `engines` range exists to
prevent. This also means the range **expires**: it states what is patched, so a later advisory moves
it again, and the number is not to be read as a compatibility minimum.

Individual dependency versions are deliberately **not** restated in this section.
[`package.json`](package.json) owns them, and a version duplicated into prose drifts the first time
one is bumped — leaving a document that looks authoritative and is wrong. Section 6 is the one
exception, and it exists to record _reasoning_ rather than to mirror numbers.

## 4. The npm scripts

These names are a cross-folder contract: [`ui-ci.yml`](../.github/workflows/ui-ci.yml) and
[`MIGRATION_README.md`](../MIGRATION_README.md) both invoke them. There are **ten**.

| Script         | Command                                                               | What it is for                                                    |
| -------------- | --------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `dev`          | `vite`                                                                | Development server with hot module replacement                    |
| `build`        | `npm run typecheck && vite build`                                     | Type-check both projects, then bundle into `dist/`                |
| `preview`      | `vite preview`                                                        | Serve an already-built `dist/` — see section 5.6                  |
| `typecheck`    | `tsc --noEmit -p tsconfig.json && tsc --noEmit -p tsconfig.node.json` | Compiler diagnostics for both TypeScript projects                 |
| `lint`         | `eslint . --max-warnings=0`                                           | ESLint plus the JSDoc documentation gate                          |
| `format`       | `prettier --check .`                                                  | Formatting drift check; fails, never rewrites                     |
| `format:write` | `prettier --write .`                                                  | Apply formatting locally                                          |
| `test`         | `vitest run`                                                          | Every suite once, then exit                                       |
| `test:watch`   | `vitest`                                                              | Watch mode, for local iteration only                              |
| `contracts`    | `vitest run src/api/contracts.test.ts`                                | Compare the typed API layer against the service OpenAPI documents |

Five of these embed a decision that the command text does not show, so each is recorded here.

**`build` type-checks first, and delegates to do it.** Vite transforms TypeScript with esbuild, which
strips types **without checking them** — so a bare `vite build` will happily emit a bundle containing
type errors. Trade-offs: `build` calls `npm run typecheck` rather than repeating the two `tsc`
invocations inline. The extra npm process is accepted because a repeated command string in a file
that admits no comments is a drift hazard: an editor extending `typecheck` and forgetting `build`
would silently return the bundle to the unchecked state this arrangement exists to close.

**`typecheck` runs `tsc` twice because this package holds two disjoint TypeScript projects**, and
`tsc -p` checks exactly one. `tsconfig.json` covers the browser sources under `src/`;
`tsconfig.node.json` covers `vite.config.ts` and `vitest.config.ts`, which execute under Node and are
granted `@types/node` that browser code is deliberately denied. Assumptions: checking only the
application project left both build-configuration files unchecked by any compiler — ESLint gives them
type-aware _lint_ rules but does not report compiler diagnostics, so a genuine type error in either
failed nothing and surfaced later as a confusing build or test failure far from its cause.

**`typecheck` also exists separately from `build`**, which already type-checks, so CI can surface a
type failure as its own clearly-labelled step. Assumptions: this mirrors the house precedent in
[`tests.yml`](../.github/workflows/tests.yml), where a redundant compile step is intentionally
redundant but surfaces a broken compile as its own clearly-labelled step. A reader of a failed run
sees which gate failed without opening a log.

**`lint` carries `--max-warnings=0`.** Trade-offs: without it, any `eslint-plugin-jsdoc` rule
configured at `warn` severity would print its finding and **still exit 0**, which makes the Rule 1
documentation gate decoration rather than enforcement. The cost accepted is that a warning cannot be
left outstanding as a note-to-self; the benefit is that the gate cannot be green while reporting
violations.

**`test` is `vitest run`, never bare `vitest`.** Assumptions: bare `vitest` enters watch mode and
would hang a CI job indefinitely rather than failing it. `test:watch` is the explicit local opt-in,
named so that no automation reaches for it by accident.

**`contracts` duplicates a file `test` already runs, and the duplication is deliberate.**
Refactoring Rationale: `src/api/contracts.test.ts` is the only step in this repository that compares
this package's typed API client layer against the service-held OpenAPI documents it is written
against, and [`ADR-006`](../docs/adr/ADR-006-api-and-ui.md) cites it by name as the mechanism making
contract drift a build failure. A named script gives CI a step titled _Check API contract agreement_,
so a reader of a failed run sees that rather than one generic failure among many, and it gives
someone changing a service contract a single command to run without waiting for jsdom to start for
every screen suite.

## 5. Build, run, test, containerise

Every command below is run from `ui/` unless stated otherwise. One command per line: a chained
one-liner hides which step failed.

### 5.1 Install

```bash
# WHAT: install exactly the dependency graph package-lock.json pins.
# WHY : Assumptions: npm ci fails on manifest drift instead of silently resolving a
#       different version, and it verifies every package against the lock's integrity
#       hash. See section 6.5 for why npm install is not an accepted substitute here.
cd ui
npm ci
```

### 5.2 Configure

```bash
# WHAT: create the ignored local environment file from the value-free template.
# WHY : Assumptions: every VITE_ value is compiled into the public bundle, so this
#       file may carry endpoints and timeouts but never a credential. Section 11
#       lists the three variables it defines and the set it explicitly forbids.
cp .env.example .env
```

### 5.3 Develop

```bash
# WHAT: start the Vite development server against the local .env values.
# WHY : Assumptions: this is the only path that reads VITE_API_BASE_URL as its
#       endpoint. A built bundle takes its endpoint from /config.json instead,
#       because the bundle is built before the environment that creates the API
#       endpoint exists -- see section 5.6 and ui/src/api/runtimeConfig.ts.
npm run dev
```

### 5.4 Validate

Run these in this order. It is the order
[`ui-ci.yml`](../.github/workflows/ui-ci.yml) uses, and the ordering is load-bearing rather than
stylistic.

```bash
# WHAT: remove any previously built bundle before the formatting check runs.
# WHY : Trade-offs: prettier --check . has no ignore file in this package, so it
#       reads dist/ too and reports every generated asset as unformatted. Deleting
#       the directory is cheaper than maintaining a second ignore list that could
#       drift out of step with .gitignore and .dockerignore.
rm -rf dist
```

```bash
# WHAT: run the four independent build-failing gates.
# WHY : Assumptions: compiler diagnostics, lint-and-JSDoc findings, contract
#       disagreement and behavioural failures are four separate classes of defect,
#       so all four are required evidence and none substitutes for another.
npm run typecheck
npm run lint
npm run contracts
npm test
```

```bash
# WHAT: check formatting, then produce the deployable bundle in dist/.
# WHY : Trade-offs: format runs BEFORE build, not after. build recreates dist/, and
#       the check would then read those generated assets and fail on files nobody
#       authored -- the same reason the directory is removed above.
npm run format
npm run build
```

The repository-wide Rule 1 gate is separate from this package's own and is run from the repository
root:

```bash
# WHAT: check that every rationale label in the tree is in its one canonical form.
# WHY : Assumptions: this gate governs .md as well as code, so it reads this file
#       too. It is a lexical check, which is exactly why it can cover seven
#       languages at once -- and why it cannot judge whether a rationale is any
#       good. That judgement stays with review.
cd ..
python3 config/rule1/rule1_gate.py
```

Production builds publish **no source maps**: `vite.config.ts` sets `build.sourcemap` to `false`, so
no `.map` file and no `sourceMappingURL` comment reaches the served bundle. Trade-offs: a published
map exposes the original module structure and every explanatory comment in it, which is an
information disclosure on an origin serving a card-management application. The cost accepted is that
a production stack trace names generated positions; the response to a defect found there is to
reproduce it under `npm run dev` or a local `vite build --sourcemap`.

### 5.5 Containerise

```bash
# WHAT: build the image from the repository root, with ui/ as the build context.
# WHY : Assumptions: the context is ui/ rather than the repository root, unlike the
#       service images. Those compile an unpublished sibling module and read
#       config/checkstyle, both outside their own directory; this package is
#       self-contained, so a narrower context copies less and makes an accidental
#       dependency on a file outside this tree fail immediately.
docker build --file ui/Dockerfile --tag carddemo-ui:local ui
```

```bash
# WHAT: run the built image against one exact API base URL.
# WHY : Assumptions: the API base URL is supplied at START rather than baked in at
#       build, so one reviewed image serves every environment. The entrypoint
#       publishes it as /config.json and unions its origin into the policy's
#       connect-src, so configuration and policy cannot disagree; an unset or
#       malformed value REFUSES TO START rather than serving an application whose
#       every request would be unconfigured.
docker run --rm -p 8080:8080 \
  -e CARDDEMO_API_BASE_URL="https://<api-host>/api/v1" \
  carddemo-ui:local
```

### 5.6 Preview a built bundle

A production bundle reads its endpoint from `/config.json` at start-up, so the previewed directory
has to contain that document before the bundle is served.

```bash
# WHAT: publish a runtime configuration document into the built bundle, then serve it.
# WHY : Refactoring Rationale: the bare `npm run preview` this replaces did not work.
#       Vite's preview server is an SPA server, so a request for a MISSING
#       /config.json is answered with index.html under HTTP 200 rather than 404. The
#       start-up loader treats any 2xx as a published document, fails to parse HTML
#       as JSON and rejects, so ui/src/main.tsx mounts nothing and writes the
#       verbatim abend sentence into the root element. A 404 is the only absent
#       answer the loader recognises, which is what ui/nginx.conf returns for this
#       path and what a plain static server does not.
# WHY : Assumptions: the value is an ABSOLUTE URL carrying the /api/v1 prefix,
#       because every operation is addressed relatively against it --
#       ui/src/api/client.ts parses it with URL and refuses a relative value, and it
#       admits plain HTTP only for a loopback host. It names the preview server's OWN
#       origin for the same-origin reason argued below, and it overrides the
#       build-time VITE_API_BASE_URL wherever it exists.
printf '{"apiBaseUrl":"http://localhost:4173/api/v1"}\n' > dist/config.json
npm run preview
```

Assumptions: the SPA and the API it addresses have to be served from **one origin**.
[`nginx.conf`](nginx.conf) is deliberately static-only — it proxies nothing — and no service
publishes a CORS configuration, so a cross-origin `apiBaseUrl` is refused by the browser rather than
by anything this package could report. The preview server serves no API of its own, so with the value
above the application mounts and every screen paints while a data request answers with the error the
screen reports. Exercising a real request means putting one reverse proxy in front of both the bundle
and the gateway and naming that proxy's origin here instead.

Trade-offs: previewing this way verifies the production artifact rather than Vite's development
transforms, and it is still not a substitute for the container path. Only `docker run` of the image
exercises [`nginx.conf`](nginx.conf) — its `try_files $uri =404` rule for dotted paths, the
content-security policy the entrypoint renders, and the cache directives — and neither path exercises
CloudFront headers or TLS.

## 6. Dependency pins and the reasoning behind them

This section records **why** each non-obvious pin is the version it is. It exists because
[`package-lock.json`](package-lock.json) is strict RFC 8259 JSON and can carry no annotation of any
kind — a comment in it makes `npm ci` fail to parse it — so for the lock this document is the only
available site.

Assumptions: [`package.json`](package.json) is the same strict JSON and equally admits no comments,
but it is **not** silent: it carries a project-owned `carddemoDependencyRationale` key, keyed by the
exact dependency entry each rationale explains, using the same four canonical labels. That key is the
machine-readable record at the pin site; this section is the human-facing one. Trade-offs: the
reasoning therefore exists in two places and can drift. Accepted, because only one of the two sits
beside the version string a reader is looking at, and only one of the two can be read as prose — and
losing either would leave this tree's most consequential version decisions undocumented in the place
someone actually looks.

### 6.1 `react-router` 8.3.0, and `react-router-dom` deliberately absent

`react-router-dom` is the conventional choice and is **absent from this manifest on purpose**.

Alternatives Considered: `react-router-dom`, rejected. It has no release at major version 8 at all;
its newest release is a thin re-export shim whose own dependency is `react-router` 7.18.1. Depending
on it would therefore silently pin routing a full major version behind while looking like the safer,
more idiomatic option. All 21 screens and [`src/router.tsx`](src/router.tsx) import from
`react-router` directly.

The failure mode is what makes this worth a paragraph rather than a line: adding the companion
package puts `react-router` 7.18.1 in the tree **alongside** 8.3.0, producing **two router
contexts**. That does not fail at install and does not fail at build — it manifests at runtime as
hooks returning `undefined` inside components that are demonstrably rendered under a router, which is
a considerable distance from its cause.

Assumptions: every browser entry point this project needs — `BrowserRouter`, `Routes`, `Route`,
`Navigate`, `useNavigate`, `useParams`, `useSearchParams` — is exported from `react-router` itself at
8.x, so the companion package would add no API. Reintroducing it would add only an indirection that
caps the version.

### 6.2 `typescript` 6.0.3, explicitly not 7.x

This is the pin most likely to be "tidied up" by someone acting reasonably, and the one whose
removal is hardest to notice.

`typescript` is pinned at **6.0.3** because `typescript-eslint` 8.65.0 peer-declares
`typescript ">=4.8.4 <6.1.0"`. TypeScript 7.x falls outside that range, so it is not accepted by the
type-aware lint pass.

Trade-offs: **crossing that bound does not fail loudly.** It stops the type-aware pass — and with it
the `eslint-plugin-jsdoc` documentation gate that enforces Rule 1 for this package — from running at
all. The build stays green while the gate stops running, which is the worst available failure shape
for a check whose whole purpose is to be unavoidable. Compile speed is therefore traded for a
documentation gate that actually executes.

Assumptions: this pin is load-bearing and is not routine version conservatism. Raising it requires
first raising `typescript-eslint` to a release whose peer range admits the new compiler, and then
confirming that `npm run lint` still reports the JSDoc rules. Bumping `typescript` alone leaves the
manifest installable and the gate silently inert. `typescript-eslint` 8.65.0 is what imposes the
ceiling, so the two versions move together and neither may be raised on its own.

This is the one place a documentation requirement constrains a compiler version. It is recorded here
so that nobody upgrades it later without understanding what breaks.

### 6.3 `dayjs` 1.11.21, declared explicitly despite being transitive

`antd` depends on `dayjs` at `^1.11.11` and it hoists cleanly, so an undeclared `dayjs` import would
resolve today.

Assumptions: `dayjs` is declared as a direct dependency anyway because this package's own code
imports it for date formatting, and relying on hoisting is fragile in two independent ways. It would
break the moment `antd` changed or dropped its own dependency — a change entirely outside this
package's control — and an undeclared direct import is invisible to dependency tooling, so an audit,
a licence report or a vulnerability scan would not attribute `dayjs` to this project at all.

### 6.4 Exact pins, with no range operators

Every version in [`package.json`](package.json) is a bare exact string. There is no `^` and no `~`
anywhere in the manifest.

Assumptions: the reasoning is the repository's own reproducibility doctrine, stated for its Python
dependencies in [`tests/README.md`](../tests/README.md) §3 — exact pins guarantee reproducible CI
runs, a hard financial-grade requirement, because a floating dependency could silently break the
byte-deterministic golden comparisons. This migration verifies behaviour against COBOL golden
masters, so the same doctrine applies in npm form rather than being re-argued from scratch.

The sharpest specific is section 6.2: a caret on `typescript` would let a `6.1.x` resolve and silently
cross the peer ceiling, disabling the documentation gate with no manifest change to review.

Trade-offs: patch-level fixes are not picked up automatically and every bump is a manual, reviewable
edit — accepted in exchange for a resolution graph that cannot drift between one checkout and the
next.

### 6.5 Why the lock is committed, and why every install uses `npm ci`

Alternatives Considered: `npm install`, rejected for both CI and the image build. It may **mutate**
the lock file and resolve a graph different from the one that was reviewed; for a financial-enterprise
UI that is a supply-chain regression rather than a convenience. `npm ci` installs strictly from the
lock and **fails loudly** on any divergence between manifest and lock.

Assumptions: the lock's `integrity` hashes are content addresses, so a substituted artifact fails
verification rather than installing — the npm analogue of the hash-verified `==` pins in
`tests/requirements-test.txt`.

Neither `--legacy-peer-deps` nor `--force` appears anywhere, and the absence is the decision rather
than an oversight. Either flag makes npm resolve past a peer conflict instead of refusing, which
would defeat the peer-range enforcement holding the TypeScript ceiling of section 6.2 in place — the
guard would be disarmed by the very command that installs it. A peer conflict reported here means the
version set in [`package.json`](package.json) is wrong; the response is to correct that manifest,
never to widen the install command.

### 6.6 The container image pins

Both stages in [`Dockerfile`](Dockerfile) are **digest-pinned**, and both digests are the
multi-platform **index** digest rather than the platform manifest digest, so `linux/amd64` and
`linux/arm64` builders resolve the same reference.

| Stage   | Image                 | Reasoning                                                          |
| ------- | --------------------- | ------------------------------------------------------------------ |
| build   | `node:22.23.2-alpine` | Matches the `engines.node` floor and the Node version CI validates |
| runtime | `nginx:1.30.4-alpine` | The **stable** branch, chosen over mainline 1.31.x                 |

Assumptions: the stable nginx branch is chosen deliberately, with both halves of the reason stated —
a static asset server needs no mainline feature, and stable receives the longer patch window.

Refactoring Rationale: the digest pin replaces a bare tag. A patch-level tag reading like an exact
version is the easiest kind of mutable reference to mistake for an immutable one, so two builds of one
commit could otherwise ship two different toolchains with nothing in the log to say so.

One line is worth adding because it generalises: image tags in this migration are **verified against
their registries rather than inferred from a naming pattern**. The Java runtime image used by the
service modules has no Alpine variant at all — an earlier draft's `-alpine` suffix on it was not a
smaller alternative but a tag that does not resolve, and it would have failed every image build.
Applying the same check here is why these two tags are the ones written.

### 6.7 What is deliberately absent

Recorded so a reader can tell an omission from an oversight.

| Not present                                        | Why                                                                                                                                       |
| -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `react-router-dom`                                 | Section 6.1 — it would cap routing a major version behind and create two router contexts                                                  |
| `@ant-design/v5-patch-for-react-19`                | Removed in antd 6; the shim is unnecessary because v6 requires only `react >= 18`                                                         |
| Tailwind CSS and PostCSS                           | Only Shadcn/ui would have needed them, and Shadcn was rejected — see section 9.1                                                          |
| Material UI                                        | Rejected — see section 9.1                                                                                                                |
| Any CSS-in-JS layer                                | antd 6 themes through CSS variables, so it would add a **second** source of design values and break the zero-hardcoded-values rule        |
| Any state-management or data-fetching library      | The screens are stateless request/response turns; `useAuth` and `usePagedQuery` are the whole requirement                                 |
| `eslint-config-prettier`, `eslint-plugin-prettier` | Formatting stays an independent `prettier --check` step, so a formatting disagreement can never justify relaxing a `jsdoc/require-*` rule |
| A coverage package                                 | The gates here are the JSDoc rules, the contract test and the per-screen suites; no coverage threshold is defined for this package        |

## 7. The 21 screens

One route per mapset, one directory per route under `src/screens/`. The table is ranked by `DFHMDF`
field count so that implementation weight is visible where it actually is rather than spread evenly
by the reading order.

| Route                              | Mapset    | Map       | Program    | Fields | Primary antd composition                      |
| ---------------------------------- | --------- | --------- | ---------- | -----: | --------------------------------------------- |
| `/account/update`                  | `COACTUP` | `CACTUPA` | `COACTUPC` |    128 | `Form` + `Row`/`Col` + `Input` + `Popconfirm` |
| `/authorizations`                  | `COPAU00` | `COPAU0A` | `COPAUS0C` |    104 | `Table`                                       |
| `/account/view`                    | `COACTVW` | `CACTVWA` | `COACTVWC` |    100 | `Descriptions` + `Input`                      |
| `/transactions`                    | `COTRN00` | `COTRN0A` | `COTRN00C` |     89 | `Table` + `Radio` column + PF7/PF8            |
| `/users`                           | `COUSR00` | `COUSR0A` | `COUSR00C` |     89 | `Table` + selection column + PF7/PF8          |
| `/reference/transaction-types`     | `COTRTLI` | `CTRTLIA` | `COTRTLIC` |     81 | `Table` with inline edit                      |
| `/cards`                           | `COCRDLI` | `CCRDLIA` | `COCRDLIC` |     72 | `Table` + keyset paging                       |
| `/transactions/new`                | `COTRN02` | `COTRN2A` | `COTRN02C` |     61 | `Form` + `InputNumber` + `Popconfirm`         |
| `/transactions/:id`                | `COTRN01` | `COTRN1A` | `COTRN01C` |     56 | `Descriptions`                                |
| `/authorizations/:key`             | `COPAU01` | `COPAU1A` | `COPAUS1C` |     54 | `Descriptions` + fraud-mark `Popconfirm`      |
| `/reports`                         | `CORPT00` | `CORPT0A` | `CORPT00C` |     42 | `Form` + `DatePicker` + `Radio.Group`         |
| `/signon`                          | `COSGN00` | `COSGN0A` | `COSGN00C` |     37 | `Card` + `Form` + `Input.Password`            |
| `/cards/:cardKey/edit`             | `COCRDUP` | `CCRDUPA` | `COCRDUPC` |     34 | `Form` + `Popconfirm`                         |
| `/cards/:cardKey`                  | `COCRDSL` | `CCRDSLA` | `COCRDSLC` |     31 | `Descriptions`                                |
| `/users/:id/edit`                  | `COUSR02` | `COUSR2A` | `COUSR02C` |     29 | `Form`                                        |
| `/admin`                           | `COADM01` | `COADM1A` | `COADM01C` |     28 | `Menu`                                        |
| `/menu`                            | `COMEN01` | `COMEN1A` | `COMEN01C` |     28 | `Menu`                                        |
| `/users/new`                       | `COUSR01` | `COUSR1A` | `COUSR01C` |     28 | `Form`                                        |
| `/users/:id/delete`                | `COUSR03` | `COUSR3A` | `COUSR03C` |     26 | `Descriptions` + `Popconfirm`                 |
| `/reference/transaction-types/:cd` | `COTRTUP` | `CTRTUPA` | `COTRTUPC` |     25 | `Form`                                        |
| `/billpay`                         | `COBIL00` | `COBIL0A` | `COBIL00C` |     24 | `Form` + `Popconfirm`                         |

Assumptions: the mapset and the map are listed as separate columns because they are different names
and citing only one is ambiguous. The **mapset** is the `DFHMSD` name, which is also the file name —
`app/bms/COACTUP.bms` — so it is what a reader greps for. The **map** is the `DFHMDI` name inside it,
which is what the online program references and what the symbolic map copybook in `app/cpy-bms/`
declares. No map name equals its mapset name, and on seven of them — `CACTUPA`, `CACTVWA`, `CCRDLIA`,
`CCRDSLA`, `CCRDUPA`, `CTRTLIA` and `CTRTUPA` — the map name drops the mapset's leading `O`, so one
name cannot be derived from the other by any suffix rule.

Assumptions: every field count above is a measured `DFHMDF` count, and the population is stated
because it matters. The **17 base mapsets carry 902 field definitions**; the **4 extension mapsets add
264**, for **1166 across all 21**. Re-measure with:

```bash
# WHAT: reproduce the per-mapset and total field counts the table above states.
# WHY : Assumptions: the two populations are reported separately because the base
#       set and the full set differ in more than magnitude -- see section 9.3, where
#       including the extensions inverts two colour ranks and reveals an eighth
#       colour. A single conflated figure would misreport both.
grep -c DFHMDF app/bms/*.bms
grep -h DFHMDF app/bms/*.bms | wc -l
grep -h DFHMDF app/bms/*.bms app/app-*/bms/*.bms | wc -l
```

### 7.1 How the route table is derived

[`src/router.tsx`](src/router.tsx) is derived from the `EXEC CICS XCTL` graph across the 18 online
programs: **program transfer becomes a client-side route change**, and the `DFHCOMMAREA` that carried
continuity between screen turns does not travel at all. Its four concerns separate — navigation
becomes router history, identity becomes signed token claims, selection context becomes path
parameters, and the first-entry-versus-re-entry discriminator disappears entirely because a stateless
handler has no turn to remember.

Every path is declared exactly once, in [`src/routes/navigation.ts`](src/routes/navigation.ts), and
[`src/routes/programRoutes.ts`](src/routes/programRoutes.ts) maps each program name the two menu
copybooks name onto one of them. Assumptions: a path declared in two modules is a path that can
drift, and the specific failure that motivated single-sourcing is worth recording — one module sent
the user-maintenance options to `/users` while the origin census could not see that path, so the
deletion screen's exit key returned an administrator to the administrative menu instead of to the
browse they had come from.

Sign-on branches to `/admin` or `/menu` **from the signed JWT group claim**, not from a field the
client supplied. That is a security improvement rather than a like-for-like port: in the baseline the
COMMAREA is storage the client echoes back, so a client could in principle assert its own user type,
whereas a group claim is signed and cannot be asserted by the holder.

Three further routes exist beyond the 21 and are not screens of their own. `/cards/view`,
`/cards/edit` and `/transactions/view` are **keyless entry** routes: three menu options name programs
that need a selector, and these let an operator enter the screen they actually chose rather than being
dropped into the browse that mints the selector. `/` redirects and `*` renders a not-found screen
inside the same shell.

## 8. The fidelity contracts

These are the behaviours the implementation must preserve, each with the baseline line it comes from.
They are the reason a screen is not simply "a form over an endpoint".

### 8.1 The three persistent shell elements

Every 3270 screen shares a title band, a message line and a function-key legend, so all three are
authored once in `src/layout/` and composed by `AppShell.tsx` — mounted exactly once as a pathless
layout route. A screen supplies each zone's **content** and composes none of them.

| Shared element            | Composed by | Content supplied by                       |
| ------------------------- | ----------- | ----------------------------------------- |
| `ScreenHeader` (rows 1-3) | `AppShell`  | every screen, through `useShellSlot`      |
| `MessageBand` (row 23)    | `AppShell`  | every screen, through `useShellSlot`      |
| `PfKeyBar` (row 24)       | `AppShell`  | every screen, through `useShellSlot`      |
| `ShellContentBoundary`    | `AppShell`  | nothing; it catches a failed route render |

The title band's three constants are carried verbatim from `app/cpy/COTTL01Y.cpy` L17-L24:

| Constant         | Picture | Value                                        |
| ---------------- | ------- | -------------------------------------------- |
| `CCDA-TITLE01`   | `X(40)` | `'      AWS Mainframe Modernization       '` |
| `CCDA-TITLE02`   | `X(40)` | `'              CardDemo                  '` |
| `CCDA-THANK-YOU` | `X(40)` | `'Thank you for using CCDA application... '` |

Assumptions: the padding is part of each value and is reproduced, because these are fixed-width
constants rather than trimmed labels. One caveat is easy to miss and would silently change a title:
**L21 of that copybook holds a commented-out alternative** for `CCDA-TITLE02` —
`'  Credit Card Demo Application (CCDA)   '` — which is **not** the live value. The live value is the
one on L22.

Two contracts about this frame are asserted by tests rather than described here, because a count in a
document cannot fail: `src/layout/screenHeaderClock.test.tsx` discovers the screen modules from the
filesystem and asserts each delegates its identity, its row-23 message and its resolved key bindings
while composing no `ScreenHeader` and no `PfKeyBar` of its own; and
`src/routes/routeCensus.test.ts` asserts every authored module is imported by the route table and
mounted, and that no route names an absent module.

Two exceptions exist and both are asserted in **both** directions rather than merely written down.
**Five** screens keep a `MessageBand` inside their own body — `accountView`, `accountUpdate`,
`cardDetail`, `cardUpdate` and `refTypeEdit` — because each is the row-22 `INFOMSG` field its mapset
declares separately from the row-23 `ERRMSG` line the shell owns, so each row still has exactly one
owner. **One** screen delegates no paint instant: `authDetail`, whose own service contract renders
`currentDate` and `currentTime`, so delegating an instant would paint two clocks on one screen.

### 8.2 The 75-character message contract

`CCARD-ERROR-MSG` and `CCARD-RETURN-MSG` are both `PIC X(75)`, and `MessageBand` reserves the space
for exactly that width so a message appearing or clearing never moves the content around it. No
screen renders a raw `Alert` of its own, because doing so would bypass both the width contract and
the reserved-space behaviour.

Worth recording precisely, because a plausible-looking citation here is wrong: these two fields live
in **`app/cpy/CVCRD01Y.cpy` L28-L30** — `CCARD-ERROR-MSG` on L28, `CCARD-RETURN-MSG` on L29, and
`88 CCARD-RETURN-MSG-OFF VALUE LOW-VALUES` on L30 as the no-message state. They are **not** in
`COCOM01Y.cpy`; `grep -c "X(75)" app/cpy/COCOM01Y.cpy` returns 0, and `CVCRD01Y.cpy` holds the only
`X(75)` fields in all of `app/cpy/`.

### 8.3 The function-key contract

`app/cpy/CSSTRPFY.cpy` L21-L78 normalises the CICS attention identifier into named flags through a
single `EVALUATE`: Enter, Clear, PA1 and PA2 at L22-L29, PF1-PF12 at L30-L53, and **PF13-PF24 aliased
onto PF01-PF12 at L54-L77**. `src/layout/usePfKeys.ts` reproduces that mapping, including the
aliasing, and `PfKeyBar.tsx` renders the same actions as controls so the workflow is discoverable
without being taken away from anyone who knows the keys.

| Key       | Action                 | Uses |
| --------- | ---------------------- | ---: |
| Enter     | Submit                 |   16 |
| PF3       | Back / exit            |   14 |
| PF4       | Clear                  |    6 |
| PF5       | Save                   |    4 |
| PF7       | Page backward          |    4 |
| PF8       | Page forward           |    4 |
| PF12      | Cancel or sign off     |    2 |
| PF13-PF24 | Aliased onto PF01-PF12 |    — |

Assumptions: the counts are `DFH*` attention-identifier references in the **18 base online programs**,
`app/cbl/CO*.cbl`. The population is stated because no other basis reproduces these figures — counting
the `CCARD-AID-*` condition names instead, or widening to all 27 online programs including the
extension trees, both give different totals. A reader re-measuring on another basis and finding
different numbers has not found a defect.

```bash
# WHAT: reproduce the key-usage counts in the table above.
# WHY : Assumptions: the DFH* form is counted rather than the CCARD-AID-* condition
#       names, because a program can test a condition name several times per key
#       while the AID comparison appears once per key it accepts -- so only this
#       form measures "which keys does this screen accept".
for k in DFHENTER DFHPF3 DFHPF4 DFHPF5 DFHPF7 DFHPF8 DFHPF12; do
  printf '%-9s %s\n' "$k" "$(grep -h "$k\b" app/cbl/CO*.cbl | wc -l)"
done
```

Keyboard fidelity is a requirement here and not a nicety: the original is keyboard-only, so removing
the keys in favour of buttons alone would take away the working method of every existing operator.

**Why the shell renders the legend but binds no key.** `AppShell` installs no keyboard listener at all
and offers sign-off as a rendered control. Trade-offs: `usePfKeys` installs one document listener per
call site with no arbitration, and PF12 is `F12=Cancel` on account update, card update and user
update — so a shell that also bound PF12 would end the session on the keystroke that cancels an edit.
An earlier revision made this a per-mount `ownsFunctionKeys` prop; the prop is gone, because a gate
has to be passed correctly at every mount site and this one was already omitted at the production
route table while two isolated test renders passed it. `routerReachability.test.tsx` asserts the
property from the outside instead.

**Why the legend's content stays per screen.** Assumptions: row 24 is not a shared literal, and
neither is the value a screen would have to supply — `PfKeyBarProps` requires resolved bindings and an
`onInvoke` dispatcher, and both exist only where the keys are bound. A shell-level bar rendering a
legend of its own could therefore not be correct for any screen.

| Screen       | Mapset    | Legend the mapset paints            | Note                                                                                          |
| ------------ | --------- | ----------------------------------- | --------------------------------------------------------------------------------------------- |
| `signon`     | `COSGN00` | `ENTER=Sign-on  F3=Exit`            | two keys                                                                                      |
| `cardList`   | `COCRDLI` | `  F3=Exit F7=Backward  F8=Forward` | no Enter legend, though the program accepts Enter; `COLOR=TURQUOISE`, not the yellow majority |
| `cardDetail` | `COCRDSL` | `ENTER=Search Cards  F3=Exit`       | two keys                                                                                      |
| `cardUpdate` | `COCRDUP` | `ENTER=Process F3=Exit`             | plus a second, `ATTRB=(ASKIP,DRK)` field carrying `F5=Save F12=Cancel`                        |

Two of those are the cases a uniform bar would get wrong. `cardList` accepts Enter but paints no
legend for it, so its Enter binding carries an empty label — which `usePfKeys` defines as a
keyboard-only handler and `PfKeyBar` renders no control for. `cardUpdate`'s second legend field is
non-display until the program reaches its confirmation state, so that screen registers the save key
only once edits have been validated and gives the cancel key a label at the same point, even though
the key itself is accepted earlier.

An unmapped key produces `CCDA-MSG-INVALID-KEY` = `'Invalid key pressed. Please see below...         '`
(`PIC X(50)`, `app/cpy/CSMSG01Y.cpy` L20-L21).

### 8.4 Field constraints come from the copybooks

Each input's `maxLength` is the copybook `PICTURE` width, sourced from
[`src/layout/recordLayout.ts`](src/layout/recordLayout.ts) so that no width is ever a literal at a
call site. Numeric-only fields reject non-digits.

**Money is a string end to end** and is rendered in the `fontFamilyCode` token so columns align the
way they did on the terminal. Assumptions: a JSON number is parsed into an IEEE-754 double by most
clients, which destroys exactness at precisely the boundary the user reads — so money crosses the wire
as a JSON string, is never converted to a JavaScript number, and is formatted by
[`src/format/money.ts`](src/format/money.ts), which reproduces the COBOL edit masks rather than
inventing a presentation.

### 8.5 The field-error contract

`app/cpy/CSSETATY.cpy` L17-L27 is a templated copybook: when a field's validation flag is not-OK or
blank it moves `DFHRED` into that field's colour subfield (L21-L22), and when the flag is blank it
additionally moves a literal `'*'` into the field itself (L23-L25). The target renders this as
`Form.Item validateStatus="error"` with `help` text, **plus the `'*'` marker preserved for the blank
case** — see [`src/layout/fieldHelp.tsx`](src/layout/fieldHelp.tsx).

Refactoring Rationale: the COBOL gates that highlight on `CDEMO-PGM-REENTER` at L20 — the
pseudo-conversational re-entry flag. The target has no such flag, because there is no remembered turn
to re-enter from, so the highlight is driven **purely by the response body**. That coupling is
severed deliberately, and noting it matters: a reader looking for the re-entry condition in the
TypeScript will not find it, and its absence is the design rather than an omission.

### 8.6 Lists page by key, not by offset

PF7 and PF8 bind to the keyset envelope's previous and next availability, through
[`src/hooks/usePagedQuery.ts`](src/hooks/usePagedQuery.ts). antd's built-in pagination is
**deliberately disabled** (`pagination={false}`).

Alternatives Considered: antd's offset pagination, rejected. Under concurrent inserts an offset query
skips and repeats rows, which changes observable behaviour that a browse-by-key does not — and the
baseline's own browse state is already a keyset cursor, so offset paging would be both a regression and
a rewrite of a contract that already exists.

Two specifics that a single global figure would get wrong:

- **Page size is per screen.** The card list renders **7** rows (`OCCURS 7 TIMES`,
  `app/cbl/COCRDLIC.cbl` L76, whose L250 comment reads `28 CHARS X 7 ROWS = 196`), while the user list
  renders **10** (`USER-REC OCCURS 10 TIMES`, `app/cbl/COUSR00C.cbl` L57).
- **The card-list cursor key is composite.** `WS-CA-LAST-CARDKEY` and `WS-CA-FIRST-CARDKEY` at
  `app/cbl/COCRDLIC.cbl` L229-L248 each pair a card number `PIC X(16)` with an account id
  `PIC 9(11)`, alongside a screen number, a last-page-displayed flag and a next-page indicator. That
  is why the card routes are `/cards/:cardKey` rather than a bare card number:
  [`src/routes/cards.ts`](src/routes/cards.ts) carries the composite selector, and a single-part
  parameter could not address a page boundary.

### 8.7 Both thank-you strings are preserved separately

There are two, they are **not** duplicates, and merging them would lose real text rather than padding.

| Source                     | Picture | Value                                                 |
| -------------------------- | ------- | ----------------------------------------------------- |
| `app/cpy/COTTL01Y.cpy` L24 | `X(40)` | `'Thank you for using CCDA application... '`          |
| `app/cpy/CSMSG01Y.cpy` L19 | `X(50)` | `'Thank you for using CardDemo application...      '` |

Assumptions: they differ in **text as well as declared width** — one says "CCDA", the other says
"CardDemo" — so they are genuinely two different sentences and the transformation rule that every
message constant is carried across character-for-character applies to each independently. Both are
held separately in [`src/messages/messages.ts`](src/messages/messages.ts). A reader who assumes the
narrower is the wider one truncated would delete a distinct string.

## 9. Design system and the token bridge

**Ant Design 6.5.2**, with `@ant-design/icons` 6.3.2. Two properties of the major version shaped the
choice: it requires only `react >= 18` and needs **no React-19 compatibility patch** — the
`@ant-design/v5-patch-for-react-19` shim was removed in v6 — and it defaults to **pure CSS-variables
theming**, which is what lets the token bridge be expressed once in
[`src/theme/antdTheme.ts`](src/theme/antdTheme.ts) and consumed everywhere without per-component
overrides. Theming happens in exactly one place.

### 9.1 Alternatives considered

Alternatives Considered: **Material UI**, rejected. The Material language is tuned for consumer
applications, whereas CardDemo is a dense enterprise system of forms and tables — 128 fields on the
account-update screen alone — where antd's information density and its first-class `Table`,
`Descriptions` and `Form` primitives are a direct fit for what the mapsets already express.

Alternatives Considered: **Shadcn/ui**, rejected. It requires a Tailwind toolchain plus hand-assembly
of every component and ships no `Table` or `Descriptions` equivalent, which would push a large amount
of table and detail-view logic into bespoke code and directly undermine the zero-hardcoded-values
principle below.

### 9.2 Three non-negotiable compliance rules

1. **Zero hardcoded values.** Every CSS property value resolves to an antd token. The only exceptions
   are `0`, `none`, `auto`, `inherit`, `currentColor` and `transparent`.
2. **Library components over raw HTML.** No raw `<button>`, `<input>`, `<select>`, `<table>` or
   heading element where antd provides `Button`, `Input`, `Select`, `Table` or `Typography.Title`.
3. **Layout through system primitives.** All spacing and alignment goes through `Flex`, `Space`,
   `Row`/`Col` or `Layout` — never bespoke CSS on a raw `div`.

### 9.3 The token bridge

No design source exists other than the baseline itself, so the BMS attribute operands **are** the
design values and were measured exhaustively rather than estimated. The token names are constrained at
compile time against the pinned antd version's own declarations in
[`src/theme/tokens.ts`](src/theme/tokens.ts), so a token renamed in a future major version becomes a
compile error instead of a theme property the library silently stops reading.

| BMS value           | Base 17 | All 21 | antd token           | Resolution                                   |
| ------------------- | ------: | -----: | -------------------- | -------------------------------------------- |
| `COLOR=BLUE`        |     289 |    384 | `colorPrimary`       | exact                                        |
| `COLOR=TURQUOISE`   |     127 |    157 | `colorInfo`          | snap                                         |
| `COLOR=GREEN`       |      76 |     84 | `colorSuccess`       | exact                                        |
| `COLOR=NEUTRAL`     |      60 |     90 | `colorTextSecondary` | snap                                         |
| `COLOR=YELLOW`      |      55 |     70 | `colorWarning`       | exact                                        |
| `COLOR=DEFAULT`     |      38 |     69 | `colorText`          | inherit                                      |
| `COLOR=RED`         |      17 |     23 | `colorError`         | exact                                        |
| `COLOR=PINK`        |       0 |      4 | `colorTextHeading`   | snap — present only in `COPAU01.bms`         |
| `ATTRB=BRT`         |      37 |     43 | `fontWeightStrong`   | snap — brightness becomes weight, not colour |
| `HILIGHT=UNDERLINE` |     158 |    175 | _(none needed)_      | structural — the `Input` border carries it   |
| `HILIGHT=OFF`       |      35 |     54 | _(none needed)_      | the absence of the above                     |

In-program colour constants across the 18 base online programs: `DFHRED` 32, `DFHGREEN` 9,
`DFHDFCOL` 6, `DFHNEUTR` 5. `DFHBLUE`, `DFHTURQ` and `DFHYELLO` do not appear — every blue,
turquoise and yellow is set in the mapset rather than moved by a program.

Assumptions: both populations are reported because they differ in **rank** and not only in magnitude,
and conflating them misreports the design source in two separate ways. Including the extension mapsets
makes `NEUTRAL` (90) overtake `GREEN` (84), reversing which value is the third most common; and it
reveals `COLOR=PINK`, an **eighth** colour absent from the base set entirely. A single conflated table
would report seven colours as exhaustive where the baseline uses eight.

```bash
# WHAT: reproduce both colour histograms and the highlight counts.
# WHY : Assumptions: the second command is not the first with more files -- it is a
#       different population whose ranking differs, which is the reason both appear
#       in the table above rather than one standing for both.
grep -oh "COLOR=[A-Z]*" app/bms/*.bms | sort | uniq -c | sort -rn
grep -oh "COLOR=[A-Z]*" app/bms/*.bms app/app-*/bms/*.bms | sort | uniq -c | sort -rn
grep -oh "HILIGHT=[A-Z]*" app/bms/*.bms | sort | uniq -c
```

The full per-value derivation, the reconciliation that proves these counts and the exhaustive mapping
table live in
[`docs/architecture/design-token-reference.md`](../docs/architecture/design-token-reference.md).

### 9.4 The six gaps, and why none of them blocks anything

**G1 — no antd equivalent of the fixed 24×80 character grid.** `DFHMDI SIZE=(24,80)` appears on all
17 base mapsets and on all 21 including extensions, with absolute row and column positioning on every
field. Resolved with a responsive `Layout` plus `Descriptions` for detail views and `Table` for lists.
Trade-offs: this is an **intentional, documented deviation**. Reproducing absolute character
positioning in a browser would be both hostile to accessibility and impossible to make responsive.
Field grouping, reading order and tab order are preserved; pixel-for-character positioning is not.

**G2 — 3270 non-display renders a truly blank field; `Input.Password` renders dots.** Used with
`visibilityToggle={false}`. The visual difference is accepted as strictly better feedback, with no
behavioural difference: the value is still never displayed.

**G3 — no semantic antd token for turquoise or for 3270 "neutral".** Snapped to `colorInfo` and
`colorTextSecondary`. Assumptions: both original values are recorded alongside the snap in
[`src/theme/tokens.ts`](src/theme/tokens.ts), so the decision is auditable rather than lost — a reader
can see what the source said, not only what it became.

**G4 — `HILIGHT=UNDERLINE` needs no token.** antd expresses input affordance structurally, so the
`Input` component's own border carries it. Recorded so a future reader does not mistake the absence for
an oversight.

**G5 — 3270 has no radius, elevation or motion vocabulary.** `borderRadiusLG`, `boxShadowSecondary`
and the `motionDuration*` family are purely **additive**. They are applied through tokens, so the
additions remain system-compliant.

**G6 — no design-tool source exists.** This is not a system gap. No design attachments were provided
with this work, so a design-tool-to-token mapping table is **not applicable**; the BMS attributes are
the design source and they were measured exhaustively, as above.

## 10. Import discipline

The single-source-of-truth property that the COBOL copybook include path provided has to be
reconstructed deliberately in TypeScript, because nothing enforces it by default.

- **Named imports only.** No default-export barrels for screens.
- **antd components from the package root** — `import { Table } from 'antd'` — so v6's CSS-variables
  theming applies uniformly. Icons come from `@ant-design/icons`.
- **Routing from `react-router`**, never from `react-router-dom`, which is absent from the dependency
  set on purpose (section 6.1).
- **Design values from `../../theme/tokens`**, never as literals. This is the mechanism behind
  compliance rule 1 in section 9.2.
- **Every user-visible string from `../../messages/messages`.** Assumptions: routing all text through
  one module is what makes the verbatim-text guarantee enforceable by a test rather than dependent on
  each author's discipline — a literal typed at a call site is invisible to any check that compares the
  catalog against its copybook sources.
- **Field widths from `../../layout/recordLayout`**, for the same reason as tokens and messages.

## 11. Environment variables

Variables are listed **by name only**. [`.env.example`](.env.example) is the authoritative template
and carries variable names with no values; a real `.env` is git-ignored and never committed.

### 11.1 Build-time, read by Vite

| Name                         | Purpose                                                   |
| ---------------------------- | --------------------------------------------------------- |
| `VITE_API_BASE_URL`          | API base URL for `npm run dev` and a locally built bundle |
| `VITE_CORRELATION_ID_HEADER` | Header name the client sets its correlation identifier on |
| `VITE_API_TIMEOUT_MS`        | Request timeout applied by the shared axios instance      |

Assumptions: only variables carrying the `VITE_` prefix are exposed to client code by Vite — which is
also exactly why the prefix is a warning rather than a convenience. **Anything prefixed is inlined
into the public bundle and is readable by anyone who loads the page.** It is therefore appropriate for
non-secret configuration only, and never for a credential. No secret is committed to this repository;
[`.env.example`](.env.example) names a set of variables that must **not** be introduced — a Cognito
client secret among them — so the prohibition is written down at the point someone would reach for it.

Assumptions: `VITE_API_BASE_URL` configures a **local development server only**. Vite inlines it at
build time, so it cannot carry a deployed environment's endpoint — that endpoint is created by the
same infrastructure apply that provisions the environment, which happens after the bundle is built. A
deployed environment is configured instead by a `config.json` document published beside the bundle and
read at start-up, and the published document takes precedence wherever it exists. See
[`src/api/runtimeConfig.ts`](src/api/runtimeConfig.ts) for the full reasoning. The value must include
the `/api/v1` prefix in either form, because operations are addressed relatively by the client.

### 11.2 Run-time, read by the container before nginx starts

| Name                               | Required | Effect                                                                                        |
| ---------------------------------- | -------- | --------------------------------------------------------------------------------------------- |
| `CARDDEMO_API_BASE_URL`            | yes      | Published as `/config.json`; when absolute, its origin is added to the policy's `connect-src` |
| `CARDDEMO_API_CONNECT_SRC_ORIGINS` | no       | Space-separated bare `https://host[:port]` origins added to the same `connect-src`            |

Assumptions: these two deliberately carry **no** `VITE_` prefix. They are read by
[`docker-entrypoint.sh`](docker-entrypoint.sh) before the listener binds and must not be inlined into
a bundle — a build-time variable could not carry a value that does not exist until the container
starts.

`CARDDEMO_API_BASE_URL` accepts exactly two shapes, and the entrypoint **refuses to start** on
anything else with the reason on stderr: an absolute `https://host[:port]/api/v1`, where an
intermediate path segment is allowed so an API published under a named stage is accepted; or the
same-origin absolute path `/api/v1`, which contributes no `connect-src` origin because `default-src
'self'` already covers the call. Refused, each with a stated reason: unset or blank, cleartext
`http://` including loopback — a loopback address inside a container names the container itself, so it
can never address an API, and `npm run dev` is the path for cleartext local work — a trailing slash, a
query, a fragment, a wildcard, embedded whitespace, and any value not ending in `/api/v1`.

The published document is exactly `{"apiBaseUrl": "<value>"}`, written to a staging file and moved into
place so a reader never sees a partial document. [`nginx.conf`](nginx.conf) serves it `no-store`, and
[`src/api/runtimeConfig.ts`](src/api/runtimeConfig.ts) fetches it before the application mounts. If
neither source yields a usable base URL the application **mounts nothing** and reports a start-up
failure, rather than rendering screens whose every request would fail.

### 11.3 Identity, and what is not carried forward

The SPA holds no client secret and does not authenticate directly against the user pool: it posts
credentials to the auth service's published sign-on operation and holds only the tokens that operation
returns.

Assumptions: those tokens are held **in memory only** and are written to no browser store — no
`sessionStorage` key, no `localStorage` key, no cookie. A client-side route change keeps the session; a
page reload, a restored tab or a second tab holds nothing and the operator signs on again.
Trade-offs: that is a deliberate reduction in session durability, taken to remove a credential
exposure rather than to narrow it, and it is registered as `D-SESSION-NOT-PERSISTED` in
[`docs/architecture/cobol-to-service-traceability.md`](../docs/architecture/cobol-to-service-traceability.md)
with the two rejected alternatives argued in full. [`.env.example`](.env.example) states the same for a
reader who never opens that document.

The demo sign-on credentials documented in the root [`README.md`](../README.md) for the 3270 path are
**not** carried forward here, and neither is the mechanism behind them. The migrated system does not
carry the baseline's plaintext password field at all — its removal is a deliberate, documented
correction rather than an oversight — and seed-user passwords are generated at infrastructure-apply
time straight into a managed secret store. No baseline credential appears anywhere in this package.

## 12. Testing

Vitest with the jsdom environment, configured in [`vitest.config.ts`](vitest.config.ts), which merges
[`vite.config.ts`](vite.config.ts) and adds `src/test/setup.ts` as its setup file. Suites sit beside
what they cover, plus the per-screen suites under `src/test/`.

Three of the fidelity contracts in section 8 are what these suites exist to assert:

- **Field constraints** — that each input's `maxLength` is the copybook `PICTURE` width, and that
  numeric-only fields reject non-digits.
- **Function-key behaviour** — through `@testing-library/user-event`, which dispatches real key events
  rather than invoking a handler directly. That is the only mechanism that can assert the keyboard
  contract, because a handler called by name proves nothing about whether a key reaches it.
- **Message text** — that each user-visible string matches its copybook source character for
  character.

A suite that renders a screen directly mounts the real `AppShell` around it. Assumptions: a bare mount
would leave all three delegated shell zones rendered by nothing, so the assertions would pass against a
frame the application never serves.

[`documentationGate.test.ts`](documentationGate.test.ts) is a gate on the gate: it asserts that the
JSDoc rules actually fire on a deliberately under-documented module and accept a conforming one. A
documentation rule configured but not firing is indistinguishable from a documented tree, which is
exactly the failure shape section 6.2 guards the compiler pin against.

**The honest scope boundary.** The existing COBOL suite is the parity oracle for **batch flows only**.
The online `CO*` programs use the CICS command-level API and cannot be run end to end without a CICS
runtime, which no runner here has — so as [`tests/README.md`](../tests/README.md) §1.1 records, only
their extractable field-validation logic is unit-tested on the baseline side. These per-screen suites
are therefore the **only** verification the 21 online screens get. That raises their importance
considerably and is worth stating plainly rather than leaving a reader to infer coverage that does not
exist.

## 13. Container image and delivery

A two-stage build: `node:22.23.2-alpine` compiles and bundles, `nginx:1.30.4-alpine` serves. Both are
digest-pinned (section 6.6). The runtime stage runs as a fixed non-root identity, exposes 8080 and
carries a `HEALTHCHECK` probing `/healthz`.

Trade-offs: two stages rather than one. The cost is a longer Dockerfile and a build that cannot simply
be run in place; what it buys is a runtime image carrying no toolchain whatsoever — no Node, no npm, no
resolved package tree, no compiler and no source — because only the built bundle and a server cross the
stage boundary. The ECR repository this image is pushed to scans on push, and such a report cannot
distinguish a finding against a build-time dependency from one against a serving dependency, so each
package left out of the runtime stage is a whole class of finding that can never be raised against the
deployed artifact.

Port 8080 rather than 80, because a non-root process cannot bind a privileged port and because the
internal load balancer's target group forwards to 8080 for every CardDemo service.
[`nginx.conf`](nginx.conf) listens on the same port and the two must agree — editing one alone would
leave the image serving a port it does not announce, or announcing a port on which nothing answers.

**The SPA history fallback is not optional.** Assumptions: all 21 client-side routes are **not files
on disk**, so without the fallback a refresh or a deep link into any of them returns 404 from the
server before React ever loads. [`nginx.conf`](nginx.conf) returns `index.html` for those paths while keeping
`try_files $uri =404` for dotted paths, so a genuinely missing asset still 404s rather than being
answered with HTML — the same distinction section 5.6 depends on for `/config.json`. The
`cloudfront-spa` Terraform module implements the identical contract through custom error responses, and
the two paths publish one document shape and keep their policies aligned directive for directive.

Assumptions: the container is **not** the deployed delivery path. `infra/modules/cloudfront-spa` serves
the bundle from a private S3 origin behind an origin access control and sets the same headers itself,
and [`docs/runbooks/deploy.md`](../docs/runbooks/deploy.md) generates the same `{"apiBaseUrl": "..."}`
document from Terraform outputs before syncing. The image exists for local and container use.

Provisioning a real cloud environment is outside this package's scope: the infrastructure is authored
and statically validated, and applying it is an operator action described in that runbook.

## 14. Further reading

- [`../README.md`](../README.md) — the application overview and the mainframe transaction inventory
- [`../MIGRATION_README.md`](../MIGRATION_README.md) — build, deploy, run, migrate, validate, roll back
- [`../CONTRIBUTING.md`](../CONTRIBUTING.md) — contribution conventions
- [`../docs/CODE_DOCUMENTATION_STANDARD.md`](../docs/CODE_DOCUMENTATION_STANDARD.md) — the polyglot
  documentation convention this file is written to
- [`../docs/adr/ADR-006-api-and-ui.md`](../docs/adr/ADR-006-api-and-ui.md) — decision D6: the API and
  UI strategy
- [`../docs/architecture/design-token-reference.md`](../docs/architecture/design-token-reference.md) —
  the full token derivation behind section 9.3
- [`../docs/architecture/service-catalog.md`](../docs/architecture/service-catalog.md) — the eight
  services this SPA calls
- [`../docs/architecture/cobol-to-service-traceability.md`](../docs/architecture/cobol-to-service-traceability.md)
  — the program-to-route matrix and the register of documented divergences
- [`../docs/runbooks/deploy.md`](../docs/runbooks/deploy.md) — deployment procedure
- [`../tests/README.md`](../tests/README.md) — the COBOL parity oracle and the house style this file
  follows
