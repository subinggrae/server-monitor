import { clsx, type ClassValue } from 'clsx'
import { extendTailwindMerge } from 'tailwind-merge'

/**
 * index.css의 글자 크기 토큰(--text-*)을 tailwind-merge에 알려 줍니다.
 * 알려 주지 않으면 text-body를 글자 색으로 보고 text-primary-foreground 같은 색 class를 지웁니다.
 */
const twMerge = extendTailwindMerge({
  extend: {
    classGroups: {
      'font-size': [{ text: ['caption', 'body', 'section', 'page', 'figure'] }],
    },
  },
})

/** shadcn/ui 규칙에 맞춘 className 병합 helper입니다. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** 입력값이 min 이상 max 이하의 정수면 그 수를, 아니면 null을 돌려줍니다. */
export function parseIntegerInRange(value: string, min: number, max: number): number | null {
  const parsed = Number(value.trim())
  return Number.isInteger(parsed) && parsed >= min && parsed <= max ? parsed : null
}
