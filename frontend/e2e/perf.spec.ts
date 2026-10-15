import { chromium, test } from '@playwright/test'
import lighthouse from 'lighthouse'
import desktopConfig from 'lighthouse/core/config/desktop-config.js'

import { baseURL } from './support/env.ts'

/** Lighthouse가 붙을 Chromium 원격 디버깅 포트입니다. 개발 PC의 다른 Chrome과 겹치지 않게 흔하지 않은 값을 씁니다. */
const DEBUGGING_PORT = 9333

const METRICS = [
  ['LCP', 'largest-contentful-paint'],
  ['CLS', 'cumulative-layout-shift'],
  ['TBT', 'total-blocking-time'],
] as const

// 점수 기준으로 실패시키지 않고 결과만 보고합니다(요구사항 0002 S3).
test('대시보드의 성능 점수와 LCP, CLS, TBT를 보고한다', async () => {
  test.setTimeout(120_000)
  const browser = await chromium.launch({ args: [`--remote-debugging-port=${DEBUGGING_PORT}`] })
  try {
    const result = await lighthouse(
      new URL('/', baseURL).toString(),
      { port: DEBUGGING_PORT, output: 'json', logLevel: 'error', onlyCategories: ['performance'] },
      desktopConfig,
    )
    if (!result) throw new Error('Lighthouse 결과가 없습니다.')

    const { lhr } = result
    const score = Math.round((lhr.categories.performance?.score ?? 0) * 100)
    const metrics = METRICS.map(([label, id]) => `${label} ${lhr.audits[id]?.displayValue ?? '측정 못 함'}`)
    console.log(`성능 점수 ${score} | ${metrics.join(' | ')}`)
  } finally {
    await browser.close()
  }
})
