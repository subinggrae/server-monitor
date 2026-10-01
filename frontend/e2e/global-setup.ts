import { execFileSync } from 'node:child_process'
import path from 'node:path'

import { chromium } from '@playwright/test'

const COMPOSE_FILE = path.join(import.meta.dirname, 'compose.yml')

/**
 * project 이름을 직접 넘깁니다. COMPOSE_PROJECT_NAME 환경 변수는 compose 파일의 name보다 우선해서,
 * 개발자 셸에 설정돼 있으면 down --remove-orphans가 개발용 구성을 지울 수 있습니다.
 */
const PROJECT_NAME = 'monitoring-manager-e2e'

function compose(...args: string[]) {
  execFileSync('docker', ['compose', '-p', PROJECT_NAME, '-f', COMPOSE_FILE, ...args], { stdio: 'inherit' })
}

function down() {
  compose('down', '--volumes', '--remove-orphans')
}

async function requireChromium() {
  try {
    const browser = await chromium.launch()
    await browser.close()
  } catch (error) {
    throw new Error(
      'E2E용 Chromium을 실행하지 못했습니다. 설치하지 않았다면 frontend에서 npm run test:e2e:install을 먼저 실행해 주세요.',
      { cause: error },
    )
  }
}

function requireDocker() {
  try {
    execFileSync('docker', ['compose', 'version'], { stdio: 'ignore' })
    execFileSync('docker', ['info'], { stdio: 'ignore' })
  } catch {
    throw new Error('E2E에는 Docker가 필요합니다. Docker가 떠 있는지 확인해 주세요.')
  }
}

/**
 * 빈 Manager Database와 Backend, 화면을 띄웁니다.
 * 돌려주는 함수는 Playwright가 테스트 결과와 관계없이 마지막에 부릅니다.
 */
export default async function globalSetup() {
  await requireChromium()
  requireDocker()

  // 이전 실행이 중간에 끊겨 남은 container가 있으면 지우고 빈 Database로 시작합니다.
  down()

  try {
    compose('up', '--detach', '--build', '--wait')
  } catch (error) {
    // 로그 출력이 실패해도 container를 내리고 원래 오류를 알립니다.
    try {
      compose('logs', '--no-color', '--tail', '200')
    } catch {
      // 로그는 원인을 찾는 보조 정보입니다.
    }
    down()
    throw error
  }

  return down
}
