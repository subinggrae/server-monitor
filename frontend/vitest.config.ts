import { defineConfig, mergeConfig } from 'vitest/config'

import viteConfig from './vite.config.ts'

// 시간대와 로케일은 개발 PC마다 다르므로 여기에서 고정합니다.
// 테스트 worker는 이 프로세스의 환경 변수를 물려받습니다.
process.env.TZ = 'Asia/Seoul'
process.env.LANG = 'ko_KR.UTF-8'

export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      include: ['src/**/*.test.{ts,tsx}'],
      setupFiles: ['src/test/setup.ts'],
    },
  }),
)
