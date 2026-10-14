import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'

// globals를 켜지 않아서 Testing Library가 화면 정리를 스스로 등록하지 못합니다.
afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})
