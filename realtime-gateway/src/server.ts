/**
 * realtime-gateway entrypoint.
 *
 * Edge WebSocket gateway for collab-docs. Topology:
 *
 *     browser/client  <->  gateway (this, ws SERVER)  <->  Kotlin backend (ws CLIENT)
 *
 * Responsibilities (edge): authenticate the client (JWT/HS256 + dev-permissive
 * fallback), accept the WS connection per document room, open one upstream link
 * to the authoritative backend, and relay edit/presence frames both ways. The
 * backend performs the OT and is the single source of truth (see ADR-0001).
 *
 * Also serves a tiny HTTP `/healthz` for liveness/readiness probes.
 */

import { createServer, type IncomingMessage, type Server as HttpServer } from 'node:http';
import { WebSocketServer, WebSocket } from 'ws';
import { loadConfig, type GatewayConfig } from './config.js';
import { createLogger, type Logger } from './logger.js';
import { authenticateRequest } from './auth.js';
import { RoomManager, type ClientSink } from './room.js';
import { BackendRelay, type SocketFactory } from './backend-relay.js';
import { parseFrame, isClientFrame, errorFrame, gatewayWelcomeFrame } from './protocol.js';

const DOC_PATH = /^\/ws\/documents\/([^/?]+)/;

export interface GatewayDeps {
  config?: Partial<GatewayConfig>;
  logger?: Logger;
  /** Injected backend socket factory (for tests / smoke). */
  backendSocketFactory?: SocketFactory;
}

export interface RunningGateway {
  httpServer: HttpServer;
  wss: WebSocketServer;
  rooms: RoomManager;
  config: GatewayConfig;
  close(): Promise<void>;
}

/** Pull the documentId out of a /ws/documents/{id} path. */
export function documentIdFromUrl(url: string | undefined): string | undefined {
  if (!url) return undefined;
  const match = DOC_PATH.exec(url);
  if (!match) return undefined;
  const id = decodeURIComponent(match[1] ?? '');
  return id.length > 0 ? id : undefined;
}

let connectionSeq = 0;

export function startGateway(deps: GatewayDeps = {}): RunningGateway {
  const config: GatewayConfig = { ...loadConfig(), ...deps.config };
  const logger = deps.logger ?? createLogger({ level: config.logLevel, bindings: { svc: 'gateway' } });
  const rooms = new RoomManager();

  const httpServer = createServer((req, res) => {
    if (req.method === 'GET' && (req.url === '/healthz' || req.url === '/health')) {
      const body = JSON.stringify({
        status: 'ok',
        connections: rooms.connectionCount,
        rooms: rooms.roomCount,
        backend: config.backendWsUrl,
        auth: config.jwtSecret.length > 0 ? (config.jwtStrict ? 'strict' : 'lenient') : 'permissive',
      });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(body);
      return;
    }
    res.writeHead(404, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ status: 'not_found' }));
  });

  // noServer mode: we run auth in the upgrade handler before accepting the WS.
  const wss = new WebSocketServer({ noServer: true });

  httpServer.on('upgrade', (req: IncomingMessage, socket, head) => {
    const documentId = documentIdFromUrl(req.url);
    if (!documentId) {
      rejectUpgrade(socket, 404, 'unknown path');
      logger.warn('upgrade rejected: bad path', { url: req.url });
      return;
    }

    const auth = authenticateRequest(
      { headers: req.headers as Record<string, string | string[] | undefined>, url: req.url },
      { secret: config.jwtSecret, strict: config.jwtStrict },
    );
    if (!auth.ok) {
      rejectUpgrade(socket, 401, 'unauthorized');
      logger.warn('upgrade rejected: auth failed', { documentId, reason: auth.reason });
      return;
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      acceptConnection(ws, documentId, auth.userId, auth.mode === 'verified');
    });
  });

  function acceptConnection(ws: WebSocket, documentId: string, userId: string, verified: boolean): void {
    const id = `c${++connectionSeq}`;
    const connLog = logger.child({ conn: id, documentId, userId });

    const sink: ClientSink = {
      id,
      get isOpen() {
        return ws.readyState === WebSocket.OPEN;
      },
      send(raw: string) {
        if (ws.readyState === WebSocket.OPEN) ws.send(raw);
      },
    };
    rooms.join(documentId, userId, sink);
    connLog.info('client connected', { verified, roomSize: rooms.roomSize(documentId) });

    // Let the client know the edge accepted it while the upstream link opens.
    sink.send(gatewayWelcomeFrame(documentId, userId));

    const relay = new BackendRelay({
      baseUrl: config.backendWsUrl,
      documentId,
      userId,
      logger: connLog,
      onMessage: (raw) => {
        // Backend -> client: relay verbatim (edit/presence/ack/welcome/error).
        sink.send(raw);
      },
      onClose: (code, reason) => {
        connLog.info('backend link closed', { code, reason });
        if (ws.readyState === WebSocket.OPEN) ws.close(1011, 'backend closed');
      },
      onError: (err) => {
        connLog.warn('backend link error', { error: err.message });
        sink.send(errorFrame('backend unavailable'));
      },
      ...(deps.backendSocketFactory ? { socketFactory: deps.backendSocketFactory } : {}),
    });

    ws.on('message', (data) => {
      const raw = data.toString();
      const parsed = parseFrame(raw);
      if (!parsed.ok) {
        sink.send(errorFrame(parsed.reason));
        return;
      }
      if (!isClientFrame(parsed.frame)) {
        sink.send(errorFrame(`unsupported client frame type '${parsed.frame.type}'`));
        return;
      }
      // Client -> backend: relay verbatim. The backend applies the OT.
      relay.send(raw);
    });

    ws.on('close', () => {
      rooms.leave(id);
      relay.close();
      connLog.info('client disconnected', { roomSize: rooms.roomSize(documentId) });
    });

    ws.on('error', (err) => {
      connLog.warn('client socket error', { error: err.message });
    });
  }

  httpServer.listen(config.port, () => {
    logger.info('realtime-gateway listening', {
      port: config.port,
      backend: config.backendWsUrl,
      auth: config.jwtSecret.length > 0 ? (config.jwtStrict ? 'strict' : 'lenient') : 'permissive',
    });
  });

  return {
    httpServer,
    wss,
    rooms,
    config,
    close: () =>
      new Promise<void>((resolve) => {
        wss.close(() => httpServer.close(() => resolve()));
      }),
  };
}

function rejectUpgrade(socket: NodeJS.WritableStream & { destroy(): void }, code: number, message: string): void {
  const text = `HTTP/1.1 ${code} ${message}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`;
  try {
    socket.write(text);
  } finally {
    socket.destroy();
  }
}

// Boot when run directly (not when imported by tests).
const isMain = process.argv[1] !== undefined && import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  startGateway();
}
