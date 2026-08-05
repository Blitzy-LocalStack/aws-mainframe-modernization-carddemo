// =============================================================================
// ui/src/vite-env.d.ts
// -----------------------------------------------------------------------------
// Purpose:
//   Ambient type declarations for the build-time environment surface of the
//   CardDemo single-page application. This file exists for exactly one reason:
//   to bring Vite's client types into the program so that `import.meta.env` and
//   the `VITE_`-prefixed values `ui/.env.example` documents are typed rather
//   than `any`. `ui/src/api/client.ts` reads `import.meta.env.VITE_API_BASE_URL`
//   to address the API gateway, and every screen reaches the API through that
//   client, so without this declaration the one value that differs between
//   environments would be the one value the compiler could not check.
//
//   It declares no runtime value, exports nothing and emits nothing. It is a
//   declaration file, so `ui/tsconfig.json`'s `noEmit` is not what keeps it out
//   of the bundle -- a `.d.ts` has no emit at all.
//
// WHY (non-obvious design decisions):
//   - Assumptions: a triple-slash reference is the mechanism, and it works even
//     though `ui/tsconfig.json` sets a populated `compilerOptions.types` array.
//     That array suppresses only the AUTOMATIC inclusion of installed `@types`
//     packages; an explicit `/// <reference types="..." />` is always honoured.
//     This is the property that makes the narrow `types` list and the typed
//     `import.meta.env` compatible rather than mutually exclusive, and it is the
//     assumption `ui/tsconfig.json` records beside that array.
//   - Alternatives Considered: naming `vite/client` in `ui/tsconfig.json`'s
//     `types` array instead, which is the reflex fix when `import.meta.env`
//     fails to compile. Rejected because that array applies to every file in the
//     project at once, and `vite/client` carries ambient `declare module`
//     statements for CSS-module and asset specifiers alongside the env typing --
//     so all 21 screens and every test would silently gain the ability to import
//     specifiers this tree does not use, and the deliberately narrow list in
//     that file would stop meaning anything. A single declaration file keeps the
//     widening local, greppable and reviewable, which is the trade this file is.
//   - Alternatives Considered: declaring an `ImportMetaEnv` interface here by
//     hand, one member per documented `VITE_` variable, so that a misspelled
//     variable name became a compile error. Rejected at this checkpoint for a
//     measurable reason rather than a stylistic one: `vite/client` already
//     declares `ImportMetaEnv` with an index signature, and a second local
//     declaration of the same interface MERGES with it rather than replacing it,
//     so the index signature survives and the misspelling still compiles. The
//     check that would actually catch it belongs where the value is read --
//     `ui/src/api/client.ts` validating that the variable is present and
//     absolute at start-up -- not in a declaration file that cannot run.
//   - Trade-offs: this file is one directive and a long header. The ratio is
//     deliberate: the directive is three tokens whose necessity is invisible,
//     and the failure it prevents (a widened `types` array, or an untyped
//     environment) is the kind that looks like a formatting preference in a
//     diff. The reasoning is therefore written where the directive lives.
// =============================================================================

/// <reference types="vite/client" />
