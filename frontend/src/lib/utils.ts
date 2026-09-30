import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/** shadcn/ui 규칙에 맞춘 className 병합 helper입니다. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}
