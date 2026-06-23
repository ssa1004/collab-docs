/**
 * Gateway configuration, resolved from environment variables.
 *
 * Kept as a pure function so tests can pass an explicit env map instead of
 * mutating process.env.
 */

export interface GatewayConfig {
  /** Port for the edge WS + /healthz HTTP server. */
  port: number;
  /** Shared HS256 secret for verifying client JWTs. Empty == dev-permissive. */
  jwtSecret: string;
  /** Authoritative backend WS base URL; documentId is appended per connection. */
  backendWsUrl: string;
  /** Log level. */
  logLevel: string;
  /**
   * When a secret IS configured, reject tokens that fail verification.
   * When false, always allow the dev-permissive fallback.
   */
  jwtStrict: boolean;
}

export type Env = Record<string, string | undefined>;

function parsePort(raw: string | undefined, fallback: number): number {
  const n = Number.parseInt(raw ?? '', 10);
  return Number.isInteger(n) && n > 0 && n < 65536 ? n : fallback;
}

function parseBool(raw: string | undefined, fallback: boolean): boolean {
  if (raw === undefined) return fallback;
  const v = raw.trim().toLowerCase();
  if (v === 'true' || v === '1' || v === 'yes') return true;
  if (v === 'false' || v === '0' || v === 'no') return false;
  return fallback;
}

export function loadConfig(env: Env = process.env): GatewayConfig {
  return {
    port: parsePort(env.PORT, 8090),
    jwtSecret: (env.JWT_SECRET ?? '').trim(),
    backendWsUrl: (env.BACKEND_WS_URL ?? 'ws://localhost:8080/ws/documents').replace(/\/+$/, ''),
    logLevel: env.LOG_LEVEL ?? 'info',
    jwtStrict: parseBool(env.JWT_STRICT, true),
  };
}
