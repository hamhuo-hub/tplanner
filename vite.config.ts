import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icon.ico'], // keep existing assets
      manifest: {
        name: 'tPlanner',
        short_name: 'tPlanner',
        description: 'Time management and travel planning',
        theme_color: '#2563eb', // Blue-600 approx to match UI
        display: 'standalone',
        background_color: '#f3f4f6', // gray-100
        icons: [
          {
            src: 'icon.ico', // Reusing existing icon, though standard is png
            sizes: '256x256',
            type: 'image/x-icon'
          }
        ]
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,ico,png,svg,woff2}'],
        runtimeCaching: [
          {
            urlPattern: /^https:\/\/fonts\.googleapis\.com\/.*/i,
            handler: 'CacheFirst',
            options: {
              cacheName: 'google-fonts-cache',
              expiration: {
                maxEntries: 10,
                maxAgeSeconds: 60 * 60 * 24 * 365
              },
              cacheableResponse: {
                statuses: [0, 200]
              }
            }
          }
        ]
      }
    })
  ],
})
