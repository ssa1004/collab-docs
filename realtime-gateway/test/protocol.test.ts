import { describe, it, expect } from 'vitest';
import {
  parseFrame,
  isClientFrame,
  errorFrame,
  gatewayWelcomeFrame,
  encodeFrame,
} from '../src/protocol.js';

describe('parseFrame', () => {
  it('parses a well-formed edit frame', () => {
    const raw = JSON.stringify({ type: 'edit', op: { type: 'insert', position: 0, text: 'hi' }, baseVersion: 3 });
    const res = parseFrame(raw);
    expect(res.ok).toBe(true);
    if (res.ok) {
      expect(res.frame.type).toBe('edit');
      expect(res.raw).toBe(raw); // raw preserved for verbatim relay
    }
  });

  it('rejects invalid JSON', () => {
    const res = parseFrame('{not json');
    expect(res.ok).toBe(false);
    if (!res.ok) expect(res.reason).toMatch(/invalid JSON/i);
  });

  it('rejects JSON that is not an object', () => {
    expect(parseFrame('"a string"').ok).toBe(false);
    expect(parseFrame('[1,2,3]').ok).toBe(false);
    expect(parseFrame('null').ok).toBe(false);
  });

  it('rejects a frame missing a string type', () => {
    expect(parseFrame(JSON.stringify({ op: {} })).ok).toBe(false);
    expect(parseFrame(JSON.stringify({ type: 123 })).ok).toBe(false);
    expect(parseFrame(JSON.stringify({ type: '' })).ok).toBe(false);
  });
});

describe('isClientFrame', () => {
  it('allows edit and presence', () => {
    expect(isClientFrame({ type: 'edit' })).toBe(true);
    expect(isClientFrame({ type: 'presence' })).toBe(true);
  });
  it('rejects server-only / unknown frame types from clients', () => {
    expect(isClientFrame({ type: 'ack' })).toBe(false);
    expect(isClientFrame({ type: 'welcome' })).toBe(false);
    expect(isClientFrame({ type: 'bogus' })).toBe(false);
  });
});

describe('gateway control frames', () => {
  it('errorFrame is a tagged JSON error', () => {
    expect(JSON.parse(errorFrame('boom'))).toEqual({ type: 'error', message: 'boom', source: 'gateway' });
  });
  it('gatewayWelcomeFrame carries documentId and userId', () => {
    expect(JSON.parse(gatewayWelcomeFrame('d1', 'alice'))).toEqual({
      type: 'gateway-welcome',
      documentId: 'd1',
      userId: 'alice',
      source: 'gateway',
    });
  });
  it('encodeFrame round-trips through parseFrame', () => {
    const encoded = encodeFrame({ type: 'presence', cursor: 5 });
    const res = parseFrame(encoded);
    expect(res.ok).toBe(true);
    if (res.ok) expect(res.frame).toEqual({ type: 'presence', cursor: 5 });
  });
});
