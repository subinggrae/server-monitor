import { Search } from 'lucide-react'
import type { ComponentProps, ReactNode } from 'react'

import { cn } from '@/lib/utils'

const FIELD_CLASS =
  'w-full rounded-md border border-border bg-surface px-2.5 text-sm text-foreground ' +
  'placeholder:text-subtle-foreground hover:border-border-strong ' +
  'disabled:cursor-not-allowed disabled:opacity-50 aria-[invalid=true]:border-destructive'

export function Input({ className, ...props }: ComponentProps<'input'>) {
  return <input className={cn(FIELD_CLASS, 'h-8', className)} {...props} />
}

export function Textarea({ className, ...props }: ComponentProps<'textarea'>) {
  return <textarea className={cn(FIELD_CLASS, 'min-h-18 resize-y py-1.5', className)} {...props} />
}

/** 운영 도구에는 기본 select로 충분합니다. Radix 의존성을 늘리지 않습니다. (CLAUDE.md 13번) */
export function Select({ className, ...props }: ComponentProps<'select'>) {
  return <select className={cn(FIELD_CLASS, 'h-8', className)} {...props} />
}

export function SearchInput({ className, ...props }: ComponentProps<'input'>) {
  return (
    <div className={cn('relative', className)}>
      <Search
        className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-subtle-foreground"
        aria-hidden
      />
      <input type="search" className={cn(FIELD_CLASS, 'h-8 pl-8')} {...props} />
    </div>
  )
}

export function Label({ className, ...props }: ComponentProps<'label'>) {
  return <label className={cn('block text-[13px] font-medium', className)} {...props} />
}

export function Checkbox({ className, ...props }: ComponentProps<'input'>) {
  return <input type="checkbox" className={cn('size-3.5 accent-[var(--primary)]', className)} {...props} />
}

export function CheckboxLabel({ className, ...props }: ComponentProps<'label'>) {
  return <label className={cn('flex items-center gap-2 text-sm', className)} {...props} />
}

export function Field({
  label,
  htmlFor,
  hint,
  error,
  children,
}: {
  label: string
  htmlFor: string
  hint?: string
  error?: string
  children: ReactNode
}) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={htmlFor}>{label}</Label>
      {hint && !error ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
      {children}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  )
}
