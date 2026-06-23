import { describe, it, expect } from 'vitest';
import jwt from 'jsonwebtoken';
import {
  authenticate,
  authenticateRequest,
  bearerFromHeader,
  tokenFromUrl,
  DEMO_USER_ID,
} from '../src/auth.js';

const SECRET = 'dev-shared-secret';

function sign(payload: object, secret = SECRET): string {
  return jwt.sign(payload, secret, { algorithm: 'HS256' });
}

describe('bearerFromHeader', () => {
  it('extracts a token from a Bearer header (case-insensitive)', () => {
    expect(bearerFromHeader('Bearer abc.def.ghi')).toBe('abc.def.ghi');
    expect(bearerFromHeader('bearer xyz')).toBe('xyz');
  });
  it('returns undefined for missing/blank/non-bearer headers', () => {
    expect(bearerFromHeader(undefined)).toBeUndefined();
    expect(bearerFromHeader('')).toBeUndefined();
    expect(bearerFromHeader('Basic abc')).toBeUndefined();
    expect(bearerFromHeader('Bearer    ')).toBeUndefined();
  });
});

describe('tokenFromUrl', () => {
  it('reads access_token then token from the query string', () => {
    expect(tokenFromUrl('/ws/documents/d1?access_token=tok1')).toBe('tok1');
    expect(tokenFromUrl('/ws/documents/d1?token=tok2')).toBe('tok2');
    expect(tokenFromUrl('/ws/documents/d1?foo=bar&access_token=tok3')).toBe('tok3');
  });
  it('returns undefined when no token param is present', () => {
    expect(tokenFromUrl('/ws/documents/d1')).toBeUndefined();
    expect(tokenFromUrl(undefined)).toBeUndefined();
  });
});

describe('authenticate — secret set, strict (production-like)', () => {
  const opts = { secret: SECRET, strict: true };

  it('VERIFIES a valid HS256 token and uses sub as userId', () => {
    const res = authenticate(sign({ sub: 'alice' }), opts);
    expect(res).toEqual({ ok: true, userId: 'alice', verified: true, mode: 'verified' });
  });

  it('REJECTS a token signed with the wrong secret', () => {
    const res = authenticate(sign({ sub: 'mallory' }, 'wrong-secret'), opts);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toMatch(/jwt verification failed/i);
  });

  it('REJECTS a non-HS256 (alg=none) token', () => {
    // jsonwebtoken refuses to sign alg:none with a secret, so craft it by hand.
    const header = Buffer.from(JSON.stringify({ alg: 'none', typ: 'JWT' })).toString('base64url');
    const body = Buffer.from(JSON.stringify({ sub: 'eve' })).toString('base64url');
    const res = authenticate(`${header}.${body}.`, opts);
    expect(res.ok).toBe(false);
  });

  it('REJECTS a missing token in strict mode', () => {
    const res = authenticate(undefined, opts);
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toMatch(/missing bearer/i);
  });

  it('REJECTS an expired token', () => {
    const expired = jwt.sign({ sub: 'bob' }, SECRET, { algorithm: 'HS256', expiresIn: -10 });
    const res = authenticate(expired, opts);
    expect(res.ok).toBe(false);
  });
});

describe('authenticate — secret set, non-strict (lenient fallback)', () => {
  const opts = { secret: SECRET, strict: false };

  it('verifies a good token', () => {
    const res = authenticate(sign({ sub: 'carol' }), opts);
    expect(res).toMatchObject({ ok: true, userId: 'carol', verified: true });
  });

  it('falls back to permissive (unverified) for a bad signature, decoding sub', () => {
    const res = authenticate(sign({ sub: 'dave' }, 'wrong'), opts);
    expect(res).toMatchObject({ ok: true, userId: 'dave', verified: false, mode: 'permissive' });
  });

  it('falls back to demo-user when token missing', () => {
    const res = authenticate(undefined, opts);
    expect(res).toMatchObject({ ok: true, userId: DEMO_USER_ID, verified: false });
  });
});

describe('authenticate — no secret (dev-permissive)', () => {
  const opts = { secret: '', strict: true };

  it('accepts any token and derives userId from unverified sub', () => {
    const res = authenticate(sign({ sub: 'frank' }, 'whatever'), opts);
    expect(res).toMatchObject({ ok: true, userId: 'frank', verified: false, mode: 'permissive' });
  });

  it('accepts no token and yields demo-user', () => {
    const res = authenticate(undefined, opts);
    expect(res).toMatchObject({ ok: true, userId: DEMO_USER_ID, verified: false });
  });

  it('yields demo-user for a token without a sub claim', () => {
    const res = authenticate(sign({ role: 'editor' }, 'whatever'), opts);
    expect(res).toMatchObject({ ok: true, userId: DEMO_USER_ID });
  });
});

describe('authenticateRequest — header/query plumbing', () => {
  const opts = { secret: SECRET, strict: true };

  it('reads the bearer token from the Authorization header', () => {
    const res = authenticateRequest(
      { headers: { authorization: `Bearer ${sign({ sub: 'grace' })}` }, url: '/ws/documents/d1' },
      opts,
    );
    expect(res).toMatchObject({ ok: true, userId: 'grace', verified: true });
  });

  it('falls back to the access_token query param when no header', () => {
    const token = sign({ sub: 'heidi' });
    const res = authenticateRequest(
      { headers: {}, url: `/ws/documents/d1?access_token=${token}` },
      opts,
    );
    expect(res).toMatchObject({ ok: true, userId: 'heidi', verified: true });
  });

  it('rejects when neither header nor query carry a valid token (strict)', () => {
    const res = authenticateRequest({ headers: {}, url: '/ws/documents/d1' }, opts);
    expect(res.ok).toBe(false);
  });
});
