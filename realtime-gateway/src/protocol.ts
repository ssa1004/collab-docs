/**
 * Wire protocol helpers for the edge <-> core relay.
 *
 * The authoritative protocol is defined by the Kotlin backend's
 * CollabWebSocketHandler (see collab-adapter-in .../ws/CollabWebSocketHandler.kt).
 * All frames are JSON text. The gateway is a transparent relay: it does NOT
 * apply OT and does NOT rewrite op payloads. It only:
 *   - validates that a frame is well-formed JSON with a string `type`,
 *   - tags gateway-originated control frames (welcome-from-gateway, errors),
 *   - passes edit/presence/ack/error frames through untouched.
 *
 * Keeping a typed view of the protocol here lets us unit-test framing without a
 * live backend, and gives one place to evolve if the contract changes.
 */

/** Frames a client may send upstream (relayed verbatim to the backend). */
export const CLIENT_FRAME_TYPES = ['edit', 'presence'] as const;
export type ClientFrameType = (typeof CLIENT_FRAME_TYPES)[number];

/** Frames the backend may send downstream (relayed verbatim to the client). */
export const SERVER_FRAME_TYPES = ['welcome', 'edit', 'presence', 'ack', 'error'] as const;
export type ServerFrameType = (typeof SERVER_FRAME_TYPES)[number];

export interface Frame {
  type: string;
  [key: string]: unknown;
}

export type ParseResult =
  | { ok: true; frame: Frame; raw: string }
  | { ok: false; reason: string };

/** Parse a raw text frame into a typed Frame, rejecting malformed input. */
export function parseFrame(raw: string): ParseResult {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return { ok: false, reason: 'invalid JSON' };
  }
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return { ok: false, reason: 'frame must be a JSON object' };
  }
  const type = (value as Record<string, unknown>).type;
  if (typeof type !== 'string' || type.length === 0) {
    return { ok: false, reason: 'frame missing string "type"' };
  }
  return { ok: true, frame: value as Frame, raw };
}

/** True if this is a frame a client is allowed to relay upstream. */
export function isClientFrame(frame: Frame): boolean {
  return (CLIENT_FRAME_TYPES as readonly string[]).includes(frame.type);
}

/** Serialize a control frame the gateway itself originates. */
export function encodeFrame(frame: Frame): string {
  return JSON.stringify(frame);
}

/** Gateway -> client error frame, shape-compatible with the backend's. */
export function errorFrame(message: string): string {
  return encodeFrame({ type: 'error', message, source: 'gateway' });
}

/** Gateway -> client frame emitted while the upstream backend link is opening. */
export function gatewayWelcomeFrame(documentId: string, userId: string): string {
  return encodeFrame({
    type: 'gateway-welcome',
    documentId,
    userId,
    source: 'gateway',
  });
}
