import { describe, it, expect, vi } from 'vitest';
import { BackendRelay, type RelaySocket, type SocketFactory } from '../src/backend-relay.js';
import { createLogger } from '../src/logger.js';

const silentLogger = createLogger({ level: 'error', sink: () => {} });

/** A controllable fake of the `ws` socket surface. */
class FakeSocket implements RelaySocket {
  readyState = 0; // CONNECTING
  readonly sent: string[] = [];
  closeArgs: { code?: number; reason?: string } | null = null;
  private handlers: Record<string, ((...args: any[]) => void)[]> = {};
  lastUrl = '';
  lastHeaders: Record<string, string> = {};

  send(data: string): void {
    this.sent.push(data);
  }
  close(code?: number, reason?: string): void {
    this.closeArgs = { code, reason };
  }
  on(event: string, cb: (...args: any[]) => void): void {
    (this.handlers[event] ??= []).push(cb);
  }
  emit(event: string, ...args: any[]): void {
    for (const cb of this.handlers[event] ?? []) cb(...args);
  }
  /** Simulate the socket opening. */
  open(): void {
    this.readyState = 1; // OPEN
    this.emit('open');
  }
}

function makeRelay(overrides: Partial<Parameters<typeof BackendRelay.prototype.constructor>[0]> = {}) {
  const fake = new FakeSocket();
  const factory: SocketFactory = (url, headers) => {
    fake.lastUrl = url;
    fake.lastHeaders = headers;
    return fake;
  };
  const onMessage = vi.fn();
  const onClose = vi.fn();
  const onError = vi.fn();
  const relay = new BackendRelay({
    baseUrl: 'ws://backend:8080/ws/documents',
    documentId: 'doc-42',
    userId: 'alice',
    logger: silentLogger,
    onMessage,
    onClose,
    onError,
    socketFactory: factory,
    ...overrides,
  });
  return { relay, fake, onMessage, onClose, onError };
}

describe('BackendRelay framing', () => {
  it('targets <baseUrl>/<documentId> and forwards identity as a bearer header', () => {
    const { fake } = makeRelay();
    expect(fake.lastUrl).toBe('ws://backend:8080/ws/documents/doc-42');
    expect(fake.lastHeaders.Authorization).toBe('Bearer alice');
  });

  it('url-encodes the documentId', () => {
    const { fake } = makeRelay({ documentId: 'a/b c' });
    expect(fake.lastUrl).toBe('ws://backend:8080/ws/documents/a%2Fb%20c');
  });

  it('buffers frames sent before open, then flushes in order on open', () => {
    const { relay, fake } = makeRelay();
    relay.send('{"type":"edit","baseVersion":1}');
    relay.send('{"type":"presence"}');
    expect(fake.sent).toEqual([]); // nothing sent while connecting

    fake.open();
    expect(fake.sent).toEqual(['{"type":"edit","baseVersion":1}', '{"type":"presence"}']);
  });

  it('sends frames straight through once open', () => {
    const { relay, fake } = makeRelay();
    fake.open();
    relay.send('{"type":"edit"}');
    expect(fake.sent).toEqual(['{"type":"edit"}']);
  });

  it('relays backend text messages verbatim via onMessage', () => {
    const { fake, onMessage } = makeRelay();
    fake.open();
    fake.emit('message', '{"type":"ack","version":7}');
    expect(onMessage).toHaveBeenCalledWith('{"type":"ack","version":7}');
  });

  it('decodes Buffer messages from the backend to text', () => {
    const { fake, onMessage } = makeRelay();
    fake.open();
    fake.emit('message', Buffer.from('{"type":"edit"}', 'utf8'));
    expect(onMessage).toHaveBeenCalledWith('{"type":"edit"}');
  });

  it('invokes onClose exactly once when the backend link closes', () => {
    const { fake, onClose } = makeRelay();
    fake.open();
    fake.emit('close', 1000, Buffer.from('bye'));
    fake.emit('close', 1000, Buffer.from('bye')); // duplicate ignored
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledWith(1000, 'bye');
  });

  it('propagates socket errors to onError', () => {
    const { fake, onError } = makeRelay();
    fake.emit('error', new Error('econnrefused'));
    expect(onError).toHaveBeenCalledWith(expect.objectContaining({ message: 'econnrefused' }));
  });

  it('close() closes the upstream socket and drops later sends', () => {
    const { relay, fake } = makeRelay();
    fake.open();
    relay.close(1000, 'done');
    expect(fake.closeArgs).toEqual({ code: 1000, reason: 'done' });
    relay.send('{"type":"edit"}');
    expect(fake.sent).toEqual([]); // nothing sent after close
  });

  it('does not double-fire onClose after an explicit close()', () => {
    const { relay, fake, onClose } = makeRelay();
    fake.open();
    relay.close();
    fake.emit('close', 1000, Buffer.from(''));
    expect(onClose).not.toHaveBeenCalled();
  });
});
