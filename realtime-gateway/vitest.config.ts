import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['test/**/*.test.ts'],
    // Server tests bind real sockets; give them room but keep CI snappy.
    testTimeout: 10000,
    hookTimeout: 10000,
  },
});
