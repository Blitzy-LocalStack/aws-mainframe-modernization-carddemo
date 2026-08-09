/// <reference types="vite/client" />

/**
 * @file The typed shape of the build-time environment surface this bundle may read.
 *
 * Purpose
 * -------
 * Declare `ImportMetaEnv` and `ImportMeta` so that `import.meta.env.VITE_API_BASE_URL` and its two
 * siblings are typed rather than `any` wherever `ui/src/api/client.ts` reads them. The sibling
 * `ui/src/vite-env.d.ts` brings Vite's own client types into the program; this file narrows the
 * environment interface to the three variables `ui/.env.example` documents.
 *
 * Assumptions: this declaration is ADDITIVE to the one `vite/client` supplies. TypeScript merges two
 * declarations of the same interface, so naming three members here does not remove the index
 * signature that arrives with the reference above -- an undeclared `VITE_` name still compiles. What
 * the narrowing buys is that the three names this application actually reads are typed and
 * discoverable; it is not, and cannot be, a misspelling check.
 */

/**
 * Build-time values admitted into the CardDemo browser bundle.
 *
 * Assumptions: every `VITE_` value is public by construction, so this interface
 * deliberately contains endpoints and behavioural tuning only. Identity-provider
 * secrets and KMS material remain server-side and have no browser-visible name.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_API_TIMEOUT_MS?: string;
  readonly VITE_CORRELATION_ID_HEADER?: string;
}

/** Supplies the typed Vite environment to browser modules. */
interface ImportMeta {
  readonly env: ImportMetaEnv;
}
