import { ArrowDown, ArrowUp, ArrowUpDown } from 'lucide-react'
import type { ComponentProps } from 'react'

import { cn } from '@/lib/utils'

export type SortDirection = 'asc' | 'desc'

/** 누르면 그 열로 정렬하는 머리글입니다. 지금 정렬 중인 열은 aria-sort로 알립니다. */
export function SortableTH({
  label,
  active,
  direction,
  onSort,
  className,
}: {
  label: string
  active: boolean
  direction: SortDirection
  onSort: () => void
  className?: string
}) {
  const Icon = !active ? ArrowUpDown : direction === 'asc' ? ArrowUp : ArrowDown

  return (
    <TH className={className} aria-sort={active ? (direction === 'asc' ? 'ascending' : 'descending') : 'none'}>
      <button
        type="button"
        onClick={onSort}
        className={cn(
          'inline-flex items-center gap-1 rounded-sm font-medium hover:text-foreground',
          active && 'text-foreground',
        )}
      >
        {label}
        <Icon className={cn('size-3', !active && 'opacity-50')} aria-hidden />
      </button>
    </TH>
  )
}

export function TableFrame({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      className={cn('overflow-hidden rounded-md border border-border bg-surface', className)}
      {...props}
    />
  )
}

export function Table({ className, ...props }: ComponentProps<'table'>) {
  return <table className={cn('w-full text-body', className)} {...props} />
}

export function THead({ className, ...props }: ComponentProps<'thead'>) {
  return (
    <thead
      className={cn('border-b border-border text-left text-caption text-muted-foreground', className)}
      {...props}
    />
  )
}

export function TH({ className, ...props }: ComponentProps<'th'>) {
  return <th className={cn('px-3 py-2 font-medium', className)} {...props} />
}

export function TBody({ ...props }: ComponentProps<'tbody'>) {
  return <tbody {...props} />
}

export function TR({ className, ...props }: ComponentProps<'tr'>) {
  return (
    <tr
      className={cn('border-b border-border last:border-0 hover:bg-surface-subtle', className)}
      {...props}
    />
  )
}

export function TD({ className, ...props }: ComponentProps<'td'>) {
  return <td className={cn('px-3 py-2', className)} {...props} />
}

export function TableNotice({ className, ...props }: ComponentProps<'p'>) {
  return (
    <p
      className={cn('border-t border-border px-3 py-10 text-center text-body text-muted-foreground', className)}
      {...props}
    />
  )
}
