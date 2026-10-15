import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // 번들한 패키지의 라이선스를 모아 설정 화면의 오픈소스 고지에서 링크합니다.
    // .md로 두면 Nginx가 내려받기로 보낼 수 있어 .txt로 냅니다.
    license: { fileName: 'licenses.txt' },
  },
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    port: 5173,
    // 개발 서버에서는 API 요청을 로컬 Backend로 넘깁니다.
    // Docker Compose로 띄울 때는 manager-web의 Nginx가 같은 역할을 합니다.
    proxy: {
      '/api': {
        target: process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
