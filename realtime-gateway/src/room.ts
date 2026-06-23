/**
 * Room / connection manager.
 *
 * A "room" is one document (documentId). Each authenticated client connection
 * joins exactly one room. The manager tracks connections per room so the
 * gateway can fan a frame out to every local client in a room (e.g. a presence
 * broadcast, or — if we ever multiplex one backend link per room — a backend
 * edit fanout).
 *
 * In the default 1:1 topology each client has its own backend relay and the
 * backend itself fans out edits to all subscribers, so local fan-out is mainly
 * used for gateway-originated notices and is the seam that makes connection
 * scale-out (many edge clients per node) testable in isolation.
 *
 * The "client socket" is abstracted behind ClientSink so the manager is
 * trivially unit-testable without a real WebSocket.
 */

export interface ClientSink {
  /** Stable id for this connection. */
  readonly id: string;
  /** Send a text frame to this client. */
  send(raw: string): void;
  /** True while the socket can still receive. */
  readonly isOpen: boolean;
}

export interface RoomConnection {
  readonly sink: ClientSink;
  readonly userId: string;
  readonly documentId: string;
}

export class RoomManager {
  /** documentId -> set of connections. */
  private readonly rooms = new Map<string, Set<RoomConnection>>();
  /** connection id -> connection (for O(1) leave). */
  private readonly byId = new Map<string, RoomConnection>();

  join(documentId: string, userId: string, sink: ClientSink): RoomConnection {
    const conn: RoomConnection = { sink, userId, documentId };
    let room = this.rooms.get(documentId);
    if (!room) {
      room = new Set();
      this.rooms.set(documentId, room);
    }
    room.add(conn);
    this.byId.set(sink.id, conn);
    return conn;
  }

  leave(connectionId: string): void {
    const conn = this.byId.get(connectionId);
    if (!conn) return;
    this.byId.delete(connectionId);
    const room = this.rooms.get(conn.documentId);
    if (!room) return;
    room.delete(conn);
    if (room.size === 0) this.rooms.delete(conn.documentId);
  }

  /**
   * Fan a frame out to every open client in a room.
   * @param exceptId optionally skip one connection (e.g. the sender).
   * @returns number of clients the frame was delivered to.
   */
  fanOut(documentId: string, raw: string, exceptId?: string): number {
    const room = this.rooms.get(documentId);
    if (!room) return 0;
    let delivered = 0;
    for (const conn of room) {
      if (exceptId !== undefined && conn.sink.id === exceptId) continue;
      if (!conn.sink.isOpen) continue;
      conn.sink.send(raw);
      delivered += 1;
    }
    return delivered;
  }

  /** Number of connections currently in a room. */
  roomSize(documentId: string): number {
    return this.rooms.get(documentId)?.size ?? 0;
  }

  /** Total number of active connections across all rooms. */
  get connectionCount(): number {
    return this.byId.size;
  }

  /** Number of non-empty rooms. */
  get roomCount(): number {
    return this.rooms.size;
  }
}
