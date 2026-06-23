import { describe, it, expect, afterEach, vi } from 'vitest';
import { WebSocket } from 'ws';
import jwt from 'jsonwebtoken';
import { startGateway, documentIdFromUrl, type RunningGateway } from '../src/server.js';
import type { RelaySocket, SocketFactory } from '../src/backend-relay.js';
import { createLogger } from '../src/logger.js';

const silentLogger = createLogger({ level: 'error', sink: () => {} });
const SECRET = 'test-secret';

/** Fake backend socket that auto-opens and records what the gateway relays. */
class FakeBackendSocket implements RelaySocket {
  readyState = 1;
  readonly sent: string[] = [];
  private handlers: Record<string, ((...a: any[]) => void)[]> = {};
  url = '';
  headers: Record<string, string> = {};
  send(d: string): void {
    this.sent.push(d);
  }
  close(): void {
    this.emit('close', 1000, Buffer.from(''));
  }
  on(e: string, cb: (...a: any[]) => void): void {
    (this.handlers[e] ??= []).push(cb);
  }
  emit(e: string, ...a: any[]): void {
    for (const cb of this.handlers[e] ?? []) cb(...a);
  }
}

const backendSockets: FakeBackendSocket[] = [];
const backendFactory: SocketFactory = (url, headers) => {
  const s = new FakeBackendSocket();
  s.url = url;
  s.headers = headers;
  backendSockets.push(s);
  // open on next tick so the gateway's onopen handlers are wired first.
  setImmediate(() => s.emit('open'));
  return s;
};

let gw: RunningGateway | undefined;

afterEach(async () => {
  await gw?.close();
  gw = undefined;
  backendSockets.length = 0;
});

function bootGateway(config: Record<string, unknown>): { port: number } {
  gw = startGateway({
    logger: silentLogger,
    backendSocketFactory: backendFactory,
    config: { port: 0, ...config } as any,
  });
  const addr = gw.httpServer.address();
  if (addr === null || typeof addr === 'string') throw new Error('no port');
  return { port: addr.port };
}

/**
 * Test client that eagerly buffers every inbound frame from the moment the
 * socket is created. This avoids a race where the server's first frame
 * (gateway-welcome) arrives in the same tick as `open`, before a late
 * `once('message')` listener is attached.
 */
class TestClient {
  readonly ws: WebSocket;
  private readonly queue: string[] = [];
  private readonly waiters: ((m: string) => void)[] = [];

  constructor(url: string, headers?: Record<string, string>) {
    this.ws = headers ? new WebSocket(url, { headers }) : new WebSocket(url);
    this.ws.on('message', (d) => {
      const msg = d.toString();
      const waiter = this.waiters.shift();
      if (waiter) waiter(msg);
      else this.queue.push(msg);
    });
  }

  open(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.ws.once('open', () => resolve());
      this.ws.once('error', reject);
    });
  }

  /** Next inbound frame (buffered or awaited). */
  next(): Promise<string> {
    const buffered = this.queue.shift();
    if (buffered !== undefined) return Promise.resolve(buffered);
    return new Promise((resolve) => this.waiters.push(resolve));
  }

  send(raw: string): void {
    this.ws.send(raw);
  }

  close(): void {
    this.ws.close();
  }
}

describe('documentIdFromUrl', () => {
  it('extracts the document id from the ws path', () => {
    expect(documentIdFromUrl('/ws/documents/doc-1')).toBe('doc-1');
    expect(documentIdFromUrl('/ws/documents/doc-1?access_token=x')).toBe('doc-1');
    expect(documentIdFromUrl('/ws/documents/a%20b')).toBe('a b');
  });
  it('returns undefined for non-matching paths', () => {
    expect(documentIdFromUrl('/healthz')).toBeUndefined();
    expect(documentIdFromUrl('/ws/documents/')).toBeUndefined();
    expect(documentIdFromUrl(undefined)).toBeUndefined();
  });
});

describe('gateway HTTP /healthz', () => {
  it('responds 200 with status ok', async () => {
    const { port } = bootGateway({ jwtSecret: '' });
    const res = await fetch(`http://127.0.0.1:${port}/healthz`);
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.status).toBe('ok');
    expect(body.auth).toBe('permissive');
  });

  it('404s unknown paths', async () => {
    const { port } = bootGateway({ jwtSecret: '' });
    const res = await fetch(`http://127.0.0.1:${port}/nope`);
    expect(res.status).toBe(404);
  });
});

describe('gateway WS auth on upgrade', () => {
  it('rejects the upgrade with a bad token in strict mode (401)', async () => {
    const { port } = bootGateway({ jwtSecret: SECRET, jwtStrict: true });
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws/documents/doc-1`, {
      headers: { authorization: 'Bearer not-a-valid-jwt' },
    });
    const err = await new Promise<Error>((resolve) => ws.once('error', resolve));
    expect(err.message).toMatch(/401/);
  });

  it('accepts a valid token and sends a gateway-welcome', async () => {
    const { port } = bootGateway({ jwtSecret: SECRET, jwtStrict: true });
    const token = jwt.sign({ sub: 'alice' }, SECRET, { algorithm: 'HS256' });
    const client = new TestClient(`ws://127.0.0.1:${port}/ws/documents/doc-1`, {
      authorization: `Bearer ${token}`,
    });
    await client.open();
    const first = JSON.parse(await client.next());
    expect(first).toMatchObject({ type: 'gateway-welcome', documentId: 'doc-1', userId: 'alice' });
    client.close();
  });

  it('accepts connections in permissive mode (no secret)', async () => {
    const { port } = bootGateway({ jwtSecret: '' });
    const client = new TestClient(`ws://127.0.0.1:${port}/ws/documents/doc-9`);
    await client.open();
    const first = JSON.parse(await client.next());
    expect(first.type).toBe('gateway-welcome');
    expect(first.userId).toBe('demo-user');
    client.close();
  });
});

describe('gateway relay path (client <-> gateway <-> backend)', () => {
  it('relays a client edit frame upstream to the backend with the right identity', async () => {
    const { port } = bootGateway({ jwtSecret: '' });
    const client = new TestClient(
      `ws://127.0.0.1:${port}/ws/documents/doc-5?access_token=${jwt.sign({ sub: 'bob' }, 'x')}`,
    );
    await client.open();
    await client.next(); // gateway-welcome

    const edit = JSON.stringify({ type: 'edit', op: { type: 'insert', position: 0, text: 'hi' }, baseVersion: 0 });
    client.send(edit);

    // wait for the backend fake to receive the relayed frame
    await vi.waitFor(() => {
      expect(backendSockets.length).toBe(1);
      expect(backendSockets[0]!.sent).toContain(edit);
    });
    expect(backendSockets[0]!.url).toBe('ws://localhost:8080/ws/documents/doc-5');
    expect(backendSockets[0]!.headers.Authorization).toBe('Bearer bob');
    client.close();
  });

  it('relays a backend frame downstream to the client verbatim', async () => {
    const { port } = bootGateway({ jwtSecret: '' });
    const client = new TestClient(`ws://127.0.0.1:${port}/ws/documents/doc-6`);
    await client.open();
    await client.next(); // gateway-welcome

    await vi.waitFor(() => expect(backendSockets.length).toBe(1));
    const ackFromBackend = JSON.stringify({ type: 'ack', version: 12 });
    const incoming = client.next();
    backendSockets[0]!.emit('message', ackFromBackend);
    expect(JSON.parse(await incoming)).toEqual({ type: 'ack', version: 12 });
    client.close();
  });

  it('rejects a client frame with an unsupported type without relaying it', async () => {
    const { port } = bootGateway({ jwtSecret: '' });
    const client = new TestClient(`ws://127.0.0.1:${port}/ws/documents/doc-7`);
    await client.open();
    await client.next(); // gateway-welcome
    await vi.waitFor(() => expect(backendSockets.length).toBe(1));

    const incoming = client.next();
    client.send(JSON.stringify({ type: 'ack', version: 1 })); // server-only type from a client
    const reply = JSON.parse(await incoming);
    expect(reply).toMatchObject({ type: 'error', source: 'gateway' });
    expect(backendSockets[0]!.sent).toEqual([]); // not relayed
    client.close();
  });

  it('returns a gateway error frame for malformed JSON', async () => {
    const { port } = bootGateway({ jwtSecret: '' });
    const client = new TestClient(`ws://127.0.0.1:${port}/ws/documents/doc-8`);
    await client.open();
    await client.next(); // gateway-welcome

    const incoming = client.next();
    client.send('{ broken');
    const reply = JSON.parse(await incoming);
    expect(reply).toMatchObject({ type: 'error', message: 'invalid JSON', source: 'gateway' });
    client.close();
  });
});
