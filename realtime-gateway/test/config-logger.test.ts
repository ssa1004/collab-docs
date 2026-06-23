import { describe, it, expect } from 'vitest';
import { loadConfig } from '../src/config.js';
import { createLogger } from '../src/logger.js';

describe('loadConfig', () => {
  it('uses defaults for an empty env', () => {
    const cfg = loadConfig({});
    expect(cfg.port).toBe(8090);
    expect(cfg.jwtSecret).toBe('');
    expect(cfg.backendWsUrl).toBe('ws://localhost:8080/ws/documents');
    expect(cfg.jwtStrict).toBe(true);
  });

  it('reads and trims values from env', () => {
    const cfg = loadConfig({
      PORT: '9000',
      JWT_SECRET: '  sekret  ',
      BACKEND_WS_URL: 'ws://core:8080/ws/documents/',
      JWT_STRICT: 'false',
    });
    expect(cfg.port).toBe(9000);
    expect(cfg.jwtSecret).toBe('sekret');
    expect(cfg.backendWsUrl).toBe('ws://core:8080/ws/documents'); // trailing slash stripped
    expect(cfg.jwtStrict).toBe(false);
  });

  it('falls back to the default port for an invalid PORT', () => {
    expect(loadConfig({ PORT: 'abc' }).port).toBe(8090);
    expect(loadConfig({ PORT: '70000' }).port).toBe(8090);
  });
});

describe('createLogger', () => {
  it('emits structured JSON lines with merged fields', () => {
    const lines: string[] = [];
    const log = createLogger({ level: 'info', sink: (l) => lines.push(l), now: () => 'T', bindings: { svc: 'gw' } });
    log.info('hello', { conn: 'c1' });
    expect(JSON.parse(lines[0]!)).toEqual({ ts: 'T', level: 'info', msg: 'hello', svc: 'gw', conn: 'c1' });
  });

  it('filters below the configured threshold', () => {
    const lines: string[] = [];
    const log = createLogger({ level: 'warn', sink: (l) => lines.push(l), now: () => 'T' });
    log.debug('d');
    log.info('i');
    log.warn('w');
    expect(lines).toHaveLength(1);
    expect(JSON.parse(lines[0]!).level).toBe('warn');
  });

  it('child loggers inherit and extend bindings', () => {
    const lines: string[] = [];
    const log = createLogger({ level: 'info', sink: (l) => lines.push(l), now: () => 'T', bindings: { svc: 'gw' } });
    log.child({ conn: 'c2' }).info('m');
    expect(JSON.parse(lines[0]!)).toMatchObject({ svc: 'gw', conn: 'c2', msg: 'm' });
  });
});
