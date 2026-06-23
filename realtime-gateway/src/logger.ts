/**
 * Minimal structured JSON logger.
 *
 * One line == one JSON object, so logs are greppable and ship cleanly to any
 * collector without a heavy logging dependency. Level is filtered against
 * LOG_LEVEL (default "info"). Fields are merged into the line as-is.
 */

export type LogLevel = 'debug' | 'info' | 'warn' | 'error';

const LEVEL_ORDER: Record<LogLevel, number> = {
  debug: 10,
  info: 20,
  warn: 30,
  error: 40,
};

export interface LogFields {
  [key: string]: unknown;
}

export interface Logger {
  debug(msg: string, fields?: LogFields): void;
  info(msg: string, fields?: LogFields): void;
  warn(msg: string, fields?: LogFields): void;
  error(msg: string, fields?: LogFields): void;
  child(bindings: LogFields): Logger;
}

function normalizeLevel(raw: string | undefined): LogLevel {
  switch ((raw ?? '').toLowerCase()) {
    case 'debug':
      return 'debug';
    case 'warn':
      return 'warn';
    case 'error':
      return 'error';
    default:
      return 'info';
  }
}

export interface LoggerOptions {
  level?: string;
  /** Sink for emitted lines. Defaults to console.log. Override in tests. */
  sink?: (line: string) => void;
  /** Clock, override in tests for deterministic timestamps. */
  now?: () => string;
  /** Base fields attached to every line. */
  bindings?: LogFields;
}

export function createLogger(options: LoggerOptions = {}): Logger {
  const threshold = LEVEL_ORDER[normalizeLevel(options.level)];
  const sink = options.sink ?? ((line: string) => console.log(line));
  const now = options.now ?? (() => new Date().toISOString());
  const bindings = options.bindings ?? {};

  function emit(level: LogLevel, msg: string, fields?: LogFields): void {
    if (LEVEL_ORDER[level] < threshold) return;
    const record: LogFields = {
      ts: now(),
      level,
      msg,
      ...bindings,
      ...(fields ?? {}),
    };
    sink(JSON.stringify(record));
  }

  return {
    debug: (msg, fields) => emit('debug', msg, fields),
    info: (msg, fields) => emit('info', msg, fields),
    warn: (msg, fields) => emit('warn', msg, fields),
    error: (msg, fields) => emit('error', msg, fields),
    child: (childBindings: LogFields) =>
      createLogger({
        ...options,
        bindings: { ...bindings, ...childBindings },
      }),
  };
}
