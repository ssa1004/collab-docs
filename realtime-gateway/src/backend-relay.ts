/**
 * Upstream relay to the authoritative Kotlin backend.
 *
 * One BackendRelay wraps a single `ws` client connection to
 * <BACKEND_WS_URL>/<documentId>. The gateway opens one upstream link per client
 * connection (1:1), forwarding the resolved userId as a bearer token so the
 * backend's handshake interceptor attributes ops to the right principal.
 *
 * Frames the client sends are forwarded with `send()`. If the upstream socket
 * is still connecting, frames are buffered and flushed on open so no early edit
 * is lost. Frames the backend sends arrive via the `onMessage` callback and the
 * server relays them straight back to the client.
 *
 * The WebSocket implementation is injected (defaults to `ws`) so unit tests can
 * supply a fake socket and assert framing without a live backend.
 */

import { WebSocket as WsWebSocket } from 'ws';
import type { Logger } from './logger.js';

/** Minimal socket surface the relay depends on (satisfied by `ws`). */
export interface RelaySocket {
  readyState: number;
  send(data: string): void;
  close(code?: number, reason?: string): void;
  on(event: 'open', cb: () => void): void;
  on(event: 'message', cb: (data: unknown) => void): void;
  on(event: 'close', cb: (code: number, reason: Buffer) => void): void;
  on(event: 'error', cb: (err: Error) => void): void;
}

export type SocketFactory = (url: string, headers: Record<string, string>) => RelaySocket;

const OPEN = 1;

const defaultSocketFactory: SocketFactory = (url, headers) =>
  new WsWebSocket(url, { headers }) as unknown as RelaySocket;

export interface BackendRelayOptions {
  baseUrl: string;
  documentId: string;
  userId: string;
  logger: Logger;
  /** Called for every text frame the backend sends downstream. */
  onMessage: (raw: string) => void;
  /** Called once when the upstream link closes (for any reason). */
  onClose: (code: number, reason: string) => void;
  /** Called on upstream error. */
  onError: (err: Error) => void;
  socketFactory?: SocketFactory;
}

export class BackendRelay {
  private readonly socket: RelaySocket;
  private readonly logger: Logger;
  private readonly pending: string[] = [];
  private open = false;
  private closed = false;

  constructor(private readonly options: BackendRelayOptions) {
    this.logger = options.logger;
    const factory = options.socketFactory ?? defaultSocketFactory;
    const url = `${options.baseUrl}/${encodeURIComponent(options.documentId)}`;
    // Forward identity to the backend. In dev the backend trusts the bearer
    // value as the userId; in prod it verifies a real JWT (out of scope here —
    // the gateway would forward the original client token instead).
    this.socket = factory(url, { Authorization: `Bearer ${options.userId}` });
    this.wire();
  }

  private wire(): void {
    this.socket.on('open', () => {
      this.open = true;
      this.logger.debug('backend relay open', { pending: this.pending.length });
      for (const frame of this.pending) this.rawSend(frame);
      this.pending.length = 0;
    });
    this.socket.on('message', (data: unknown) => {
      this.options.onMessage(toText(data));
    });
    this.socket.on('close', (code: number, reason: Buffer) => {
      this.open = false;
      if (this.closed) return;
      this.closed = true;
      this.options.onClose(code, reason?.toString() ?? '');
    });
    this.socket.on('error', (err: Error) => {
      this.options.onError(err);
    });
  }

  /** Forward a client frame upstream, buffering if not yet open. */
  send(raw: string): void {
    if (this.closed) return;
    if (this.open && this.socket.readyState === OPEN) {
      this.rawSend(raw);
    } else {
      this.pending.push(raw);
    }
  }

  private rawSend(raw: string): void {
    try {
      this.socket.send(raw);
    } catch (err) {
      this.options.onError(err instanceof Error ? err : new Error(String(err)));
    }
  }

  /** Close the upstream link. */
  close(code = 1000, reason = 'client disconnected'): void {
    if (this.closed) return;
    this.closed = true;
    try {
      this.socket.close(code, reason);
    } catch {
      // ignore — already closing/closed
    }
  }

  get isOpen(): boolean {
    return this.open && !this.closed;
  }
}

function toText(data: unknown): string {
  if (typeof data === 'string') return data;
  if (data instanceof Buffer) return data.toString('utf8');
  if (data instanceof ArrayBuffer) return Buffer.from(data).toString('utf8');
  if (Array.isArray(data)) return Buffer.concat(data as Buffer[]).toString('utf8');
  return String(data);
}
