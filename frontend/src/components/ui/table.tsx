import type { ComponentProps } from 'react'

import { cn } from '@/lib/utils'

export function TableFrame({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      className={cn('overflow-hidden rounded-md border border-border bg-surface', className)}
      {...props}
    />
  )
}

export function Table({ className, ...props }: ComponentProps<'table'>) {
  return <table className={cn('w-full text-[13px]', className)} {...props} />
}

export function THead({ className, ...props }: ComponentProps<'thead'>) {
  return (
    <thead
      className={cn('border-b border-border text-left text-xs text-muted-foreground', className)}
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
      className={cn('border-t border-border px-3 py-10 text-center text-[13px] text-muted-foreground', className)}
      {...props}
    />
  )
}
