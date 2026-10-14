import { cva, type VariantProps } from 'class-variance-authority'
import type { ComponentProps } from 'react'

import { cn } from '@/lib/utils'

const buttonVariants = cva(
  'inline-flex items-center justify-center gap-1.5 rounded-md text-sm whitespace-nowrap ' +
    'transition-colors disabled:pointer-events-none disabled:opacity-45',
  {
    variants: {
      variant: {
        primary: 'bg-primary font-medium text-primary-foreground hover:opacity-90',
        secondary: 'border border-border bg-surface hover:border-border-strong hover:bg-accent',
        ghost: 'text-muted-foreground hover:bg-accent hover:text-foreground',
        destructive: 'bg-destructive font-medium text-destructive-foreground hover:opacity-90',
        'destructive-ghost': 'text-muted-foreground hover:bg-accent hover:text-destructive',
      },
      size: {
        default: 'h-8 px-3',
        sm: 'h-7 px-2.5 text-[13px]',
        icon: 'size-7',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'default',
    },
  },
)

type ButtonProps = ComponentProps<'button'> & VariantProps<typeof buttonVariants>

export function Button({ className, variant, size, ...props }: ButtonProps) {
  return <button className={cn(buttonVariants({ variant, size }), className)} {...props} />
}

export { buttonVariants }
