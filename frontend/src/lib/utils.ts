import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** shadcn/ui 규칙에 맞춘 className 병합 helper입니다. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/** 입력값이 min 이상 max 이하의 정수면 그 수를, 아니면 null을 돌려줍니다. */
export function parseIntegerInRange(value: string, min: number, max: number): number | null {
  const parsed = Number(value.trim())
  return Number.isInteger(parsed) && parsed >= min && parsed <= max ? parsed : null
}
