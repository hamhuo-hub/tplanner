import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import electron from 'vite-plugin-electron'
import { copyFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

const syncProxy = {
  '/tplanner': {
    target: process.env.TPLANNER_SYNC_PROXY_TARGET || 'https://sync.hamhuo.top',
    changeOrigin: true,
  },
}

/**
 * Copies both standalone renderers and the exact vendored light-token exports.
 * Every HTML/module URL resolves inside dist-electron, including packaged builds.
 */
function copyWidgetAssets(): Plugin {
  const copy = () => {
    const outDir = resolve(__dirname, 'dist-electron')
    if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })
    const files = ['widget.html', 'widget.js', 'widget.css', 'notes-widget.html', 'notes-widget.js', 'notes-widget.css', 'shared-widget.css', 'widget-shared.mjs', 'marked.umd.js']
    for (const f of files) {
      const src = resolve(__dirname, 'electron', f)
      copyFileSync(src, resolve(outDir, f))
    }
    for (const f of ['tplanner-light.css', 'tplanner-light.mjs']) {
      copyFileSync(resolve(__dirname, 'design-assets/tokens/generated', f), resolve(outDir, f))
    }
  }
  return {
    name: 'tplanner:copy-widget-assets',
    buildStart() { copy() },     // dev mode
    closeBundle() { copy() },    // production build
  }
}

export default defineConfig({
  base: './',

  // Keep API requests same-origin in the browser while developing locally.
  server: { proxy: syncProxy },
  preview: { proxy: syncProxy },

  // Force a single copy of these packages — prevents "multiple React instances"
  // and "@emotion/react already loaded" warnings when some deps bundle their own.
  resolve: {
    dedupe: [
      'react',
      'react-dom',
      '@emotion/react',
      '@emotion/styled',
      '@emotion/cache',
      '@emotion/serialize',
    ],
  },

  optimizeDeps: {
    include: [
      'react',
      'react-dom',
      '@emotion/react',
      '@emotion/styled',
    ],
  },

  plugins: [
    react(),
    copyWidgetAssets(),
    electron([
      {
        // Main process
        entry: 'electron/main.js',
        onstart(options) {
          try { options.startup(); } catch { /* Electron process may have exited */ }
        },
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
        // Main-window preload
        entry: 'electron/preload.js',
        onstart(options) {
          try { options.reload(); } catch { /* Electron process may have exited */ }
        },
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
      {
        // Today-widget preload
        entry: 'electron/widget-preload.js',
        onstart(options) {
          try { options.reload(); } catch { /* Electron process may have exited */ }
        },
        vite: {
          build: {
            sourcemap: true,
            outDir: 'dist-electron',
            lib: { entry: 'electron/widget-preload.js', formats: ['cjs'] },
            rollupOptions: {
              external: ['electron'],
              output: { format: 'cjs', entryFileNames: '[name].cjs' },
            },
          },
        },
      },
      {
        // Notes-widget preload
        entry: 'electron/notes-widget-preload.js',
        onstart(options) {
          try { options.reload(); } catch { /* Electron process may have exited */ }
        },
        vite: {
          build: {
            sourcemap: true,
            outDir: 'dist-electron',
            lib: { entry: 'electron/notes-widget-preload.js', formats: ['cjs'] },
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
