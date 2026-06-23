/**
 * Client authentication at the edge.
 *
 * Clients present a JWT as `Authorization: Bearer <token>` on the WS upgrade
 * request (or `?access_token=<token>` for browser WebSocket clients that can't
 * set headers). We verify HS256 against a shared dev secret and use the `sub`
 * claim as the userId, which the backend's handshake interceptor also treats
 * as the principal.
 *
 * Modes:
 *  - secret set + strict (default): signature MUST verify, else reject.
 *  - secret set + non-strict: try to verify; on failure fall back to permissive.
 *  - no secret: DEV-PERMISSIVE. Accept any/decode-only token; derive userId from
 *    an unverified `sub` if present, else "demo-user". This mirrors the backend's
 *    dev profile (DevSecurityConfig), which also trusts the bearer value in dev.
 *
 * This is a learning/portfolio project: the permissive mode is intentional so the
 * whole stack boots with zero infra and no IdP, and it is documented as NOT for
 * production. Production uses a real signed secret with strict verification.
 */

import jwt from 'jsonwebtoken';

export const DEMO_USER_ID = 'demo-user';

export interface AuthOptions {
  /** Shared HS256 secret. Empty string == dev-permissive (no signature check). */
  secret: string;
  /** When a secret is set, reject tokens that fail verification. */
  strict: boolean;
}

export type AuthResult =
  | { ok: true; userId: string; verified: boolean; mode: 'verified' | 'permissive' }
  | { ok: false; reason: string };

/** Extract a bearer token from an Authorization header value. */
export function bearerFromHeader(header: string | undefined): string | undefined {
  if (!header) return undefined;
  const trimmed = header.trim();
  if (!/^Bearer\s+/i.test(trimmed)) return undefined;
  const token = trimmed.replace(/^Bearer\s+/i, '').trim();
  return token.length > 0 ? token : undefined;
}

/** Extract `access_token` (or `token`) from a raw request URL query string. */
export function tokenFromUrl(url: string | undefined): string | undefined {
  if (!url) return undefined;
  const qIndex = url.indexOf('?');
  if (qIndex < 0) return undefined;
  const params = new URLSearchParams(url.slice(qIndex + 1));
  const token = params.get('access_token') ?? params.get('token');
  return token && token.length > 0 ? token : undefined;
}

function userIdFromPayload(payload: unknown): string {
  if (payload && typeof payload === 'object') {
    const sub = (payload as Record<string, unknown>).sub;
    if (typeof sub === 'string' && sub.trim().length > 0) return sub.trim();
  }
  return DEMO_USER_ID;
}

/**
 * Resolve the authenticated userId for a connection from its presented token.
 * Returns an AuthResult; callers reject the upgrade when ok === false.
 */
export function authenticate(token: string | undefined, options: AuthOptions): AuthResult {
  const hasSecret = options.secret.length > 0;

  if (hasSecret) {
    if (!token) {
      if (options.strict) return { ok: false, reason: 'missing bearer token' };
      return { ok: true, userId: DEMO_USER_ID, verified: false, mode: 'permissive' };
    }
    try {
      const payload = jwt.verify(token, options.secret, { algorithms: ['HS256'] });
      return { ok: true, userId: userIdFromPayload(payload), verified: true, mode: 'verified' };
    } catch (err) {
      if (options.strict) {
        const reason = err instanceof Error ? err.message : 'invalid token';
        return { ok: false, reason: `jwt verification failed: ${reason}` };
      }
      // non-strict: fall back to permissive decode (unverified).
      const decoded = token ? jwt.decode(token) : null;
      return { ok: true, userId: userIdFromPayload(decoded), verified: false, mode: 'permissive' };
    }
  }

  // No secret configured -> dev-permissive. Decode-only (never trusts signature).
  const decoded = token ? jwt.decode(token) : null;
  return { ok: true, userId: userIdFromPayload(decoded), verified: false, mode: 'permissive' };
}

/**
 * Convenience: pull a token from either the Authorization header or the URL
 * query and authenticate it.
 */
export function authenticateRequest(
  req: { headers: Record<string, string | string[] | undefined>; url?: string | undefined },
  options: AuthOptions,
): AuthResult {
  const headerVal = req.headers['authorization'] ?? req.headers['Authorization'];
  const header = Array.isArray(headerVal) ? headerVal[0] : headerVal;
  const token = bearerFromHeader(header) ?? tokenFromUrl(req.url);
  return authenticate(token, options);
}
