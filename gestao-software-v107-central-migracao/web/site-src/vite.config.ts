import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  server: {
    allowedHosts: [
      '3000-ivh0npfred0pcg30tqt9x-e7330fab.us1.manus.computer',
      '8328-ivh0npfred0pcg30tqt9x-e7330fab.us1.manus.computer',
    ],
  },
  plugins: [react()],
  build: {
    target: 'es2022',
    sourcemap: false,
  },
});
