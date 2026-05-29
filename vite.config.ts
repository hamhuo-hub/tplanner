import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import electron from 'vite-plugin-electron'
import { copyFileSync, mkdirSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'

/**
 * Copies the today-widget renderer assets (widget.html / widget.js) into
 * dist-electron/ so they sit next to main.cjs at runtime. They don't need
 * any bundling — the Electron WebView loads them as plain HTML / JS.
 */
function copyWidgetAssets(): Plugin {
  const copy = () => {
    const outDir = resolve(__dirname, 'dist-electron')
    if (!existsSync(outDir)) mkdirSync(outDir, { recursive: true })
    const files = ['widget.html', 'widget.js', 'notes-widget.html', 'notes-widget.js']
    for (const f of files) {
      const src = resolve(__dirname, 'electron', f)
      if (existsSync(src)) copyFileSync(src, resolve(outDir, f))
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
          try { options.startup(); } catch (e) { /* Electron process may have exited */ }
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
          try { options.reload(); } catch (e) { /* Electron process may have exited */ }
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
          try { options.reload(); } catch (e) { /* Electron process may have exited */ }
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
          try { options.reload(); } catch (e) { /* Electron process may have exited */ }
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
