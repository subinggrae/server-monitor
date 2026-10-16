import * as DialogPrimitive from '@radix-ui/react-dialog'
import { X } from 'lucide-react'
import type { ComponentProps, ReactNode } from 'react'

import { cn } from '@/lib/utils'

export const Dialog = DialogPrimitive.Root
export const DialogTrigger = DialogPrimitive.Trigger
export const DialogClose = DialogPrimitive.Close

export function DialogContent({
  className,
  title,
  description,
  children,
  ...props
}: ComponentProps<typeof DialogPrimitive.Content> & { title: string; description?: ReactNode }) {
  return (
    <DialogPrimitive.Portal>
      <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-scrim" />
      <DialogPrimitive.Content
        className={cn(
          'fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2',
          'max-h-[85vh] overflow-y-auto rounded-lg border border-border bg-surface shadow-overlay',
          className,
        )}
        {...props}
      >
        <div className="flex items-start justify-between gap-4 px-5 pb-3 pt-4">
          <div>
            <DialogPrimitive.Title className="text-section font-semibold">
              {title}
            </DialogPrimitive.Title>
            {description ? (
              <DialogPrimitive.Description className="mt-1 text-body text-muted-foreground">
                {description}
              </DialogPrimitive.Description>
            ) : null}
          </div>
          <DialogPrimitive.Close
            className="-mr-1 -mt-0.5 rounded-md p-1 text-muted-foreground hover:bg-accent hover:text-foreground"
            aria-label="닫기"
          >
            <X className="size-4" aria-hidden />
          </DialogPrimitive.Close>
        </div>
        {children}
      </DialogPrimitive.Content>
    </DialogPrimitive.Portal>
  )
}

export function DialogBody({ className, ...props }: ComponentProps<'div'>) {
  return <div className={cn('space-y-4 px-5 py-3', className)} {...props} />
}

export function DialogFooter({ className, ...props }: ComponentProps<'div'>) {
  return (
    <div
      className={cn('flex justify-end gap-2 border-t border-border px-5 py-3', className)}
      {...props}
    />
  )
}
