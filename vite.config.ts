import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import electron from 'vite-plugin-electron'

export default defineConfig({
  base: './',
  plugins: [
    react(),
    electron([
      {
        // Main process
        entry: 'electron/main.js',
        onstart(options) { options.startup(); },
        vite: {
          build: {
            sourcemap: true,
            outDir: 'dist-electron',
            lib: { entry: 'electron/main.js', formats: ['cjs'] },
            rollupOptions: {
              external: ['electron'],
              output: { format: 'cjs', entryFileNames: '[name].cjs' },
            },
          },
        },
      },
      {
        // Preload
        entry: 'electron/preload.js',
        onstart(options) { options.reload(); },
        vite: {
          build: {
            sourcemap: true,
            outDir: 'dist-electron',
            lib: { entry: 'electron/preload.js', formats: ['cjs'] },
            rollupOptions: {
              external: ['electron'],
              output: { format: 'cjs', entryFileNames: '[name].cjs' },
            },
          },
        },
      },
    ]),
  ],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
})
