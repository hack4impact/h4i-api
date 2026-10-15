/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// `npm run dev` serves the app on :5173 and forwards API and sign-in calls to Spring Boot on :8080.
const backend = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: Object.fromEntries(['/api', '/oauth2', '/login', '/logout', '/v3'].map((path) => [path, backend])),
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    // Primer imports its own CSS; let Vite handle those imports instead of Node.
    server: { deps: { inline: [/@primer\//] } },
  },
})
