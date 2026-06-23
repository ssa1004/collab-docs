import { describe, it, expect, beforeEach } from 'vitest';
import { RoomManager, type ClientSink } from '../src/room.js';

class FakeSink implements ClientSink {
  readonly sent: string[] = [];
  isOpen = true;
  constructor(readonly id: string) {}
  send(raw: string): void {
    this.sent.push(raw);
  }
}

describe('RoomManager', () => {
  let rooms: RoomManager;
  beforeEach(() => {
    rooms = new RoomManager();
  });

  it('tracks connections per room and globally', () => {
    rooms.join('doc-1', 'alice', new FakeSink('a'));
    rooms.join('doc-1', 'bob', new FakeSink('b'));
    rooms.join('doc-2', 'carol', new FakeSink('c'));

    expect(rooms.roomSize('doc-1')).toBe(2);
    expect(rooms.roomSize('doc-2')).toBe(1);
    expect(rooms.connectionCount).toBe(3);
    expect(rooms.roomCount).toBe(2);
  });

  it('fans a frame out to every member of a room', () => {
    const a = new FakeSink('a');
    const b = new FakeSink('b');
    const c = new FakeSink('c');
    rooms.join('doc-1', 'alice', a);
    rooms.join('doc-1', 'bob', b);
    rooms.join('doc-2', 'carol', c);

    const delivered = rooms.fanOut('doc-1', '{"type":"presence"}');
    expect(delivered).toBe(2);
    expect(a.sent).toEqual(['{"type":"presence"}']);
    expect(b.sent).toEqual(['{"type":"presence"}']);
    expect(c.sent).toEqual([]); // different room untouched
  });

  it('can exclude the sender from a fan-out', () => {
    const a = new FakeSink('a');
    const b = new FakeSink('b');
    rooms.join('doc-1', 'alice', a);
    rooms.join('doc-1', 'bob', b);

    const delivered = rooms.fanOut('doc-1', 'x', 'a');
    expect(delivered).toBe(1);
    expect(a.sent).toEqual([]);
    expect(b.sent).toEqual(['x']);
  });

  it('skips closed sinks during fan-out', () => {
    const a = new FakeSink('a');
    const b = new FakeSink('b');
    b.isOpen = false;
    rooms.join('doc-1', 'alice', a);
    rooms.join('doc-1', 'bob', b);

    const delivered = rooms.fanOut('doc-1', 'x');
    expect(delivered).toBe(1);
    expect(b.sent).toEqual([]);
  });

  it('removes a connection on leave and prunes empty rooms', () => {
    const a = new FakeSink('a');
    rooms.join('doc-1', 'alice', a);
    expect(rooms.roomCount).toBe(1);

    rooms.leave('a');
    expect(rooms.roomSize('doc-1')).toBe(0);
    expect(rooms.roomCount).toBe(0);
    expect(rooms.connectionCount).toBe(0);
  });

  it('fanOut to an unknown room delivers to nobody', () => {
    expect(rooms.fanOut('nope', 'x')).toBe(0);
  });

  it('leave is idempotent / safe for unknown ids', () => {
    expect(() => rooms.leave('ghost')).not.toThrow();
  });
});
