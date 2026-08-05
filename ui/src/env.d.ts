/// <reference types="vite/client" />

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
