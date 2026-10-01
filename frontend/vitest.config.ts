import { defineConfig, mergeConfig } from 'vitest/config'

import viteConfig from './vite.config.ts'

// 화면과 같은 Vite 설정(@ alias 등)으로 테스트합니다.
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
      setupFiles: ['./src/test/setup.ts'],
    },
  }),
)
