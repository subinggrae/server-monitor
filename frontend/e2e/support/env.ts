/** 전체 실행 환경의 화면 주소입니다. 기본값을 localhost로 두어 운영 주소에 실행하지 않게 합니다. */
export const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:8081'

const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]'])

/**
 * 테스트는 `e2e-`로 시작하는 Check와 서버를 지웁니다. 실수로 운영 주소를 넣어도 지우지 않도록,
 * localhost가 아니면 `E2E_ALLOW_REMOTE=1`을 함께 줄 때만 실행합니다.
 */
export function assertSafeTarget() {
  const { hostname } = new URL(baseURL)
  if (!LOCAL_HOSTS.has(hostname) && process.env.E2E_ALLOW_REMOTE !== '1') {
    throw new Error(
      `${baseURL}은 이 PC의 주소가 아니라 실행하지 않았습니다. 테스트 데이터를 만들고 지워도 되는 환경이면 E2E_ALLOW_REMOTE=1을 함께 주세요.`,
    )
  }
}
