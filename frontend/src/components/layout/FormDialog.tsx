import { useEffect, useRef, type FormEvent, type ReactNode } from 'react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogFooter } from '@/components/ui/dialog'
import { cn } from '@/lib/utils'

const WIDTH = { md: 'max-w-md', lg: 'max-w-lg', xl: 'max-w-2xl' } as const

/**
 * 추가·수정·확인 대화상자의 틀입니다. 제목과 설명 → 필드 → 폼 오류 → 아래 막대([닫기] [실행]) 순서입니다.
 * 제출이 끝났는데 오류 칸이 있으면 첫 오류 칸으로 포커스를 옮깁니다(RULES.md의 폼).
 */
export function FormDialog({
  open,
  onOpenChange,
  title,
  description,
  size = 'lg',
  submitLabel,
  pendingLabel,
  pending,
  destructive = false,
  formError,
  onSubmit,
  children,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  size?: keyof typeof WIDTH
  submitLabel: string
  pendingLabel: string
  pending: boolean
  destructive?: boolean
  formError?: string | null
  onSubmit: () => void
  children?: ReactNode
}) {
  const formRef = useRef<HTMLFormElement>(null)
  const wasPending = useRef(pending)

  useEffect(() => {
    if (wasPending.current && !pending) {
      formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
    }
    wasPending.current = pending
  }, [pending])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    onSubmit()
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={title} description={description} className={cn(WIDTH[size])}>
        <form ref={formRef} onSubmit={submit} noValidate>
          <DialogBody>
            {children}
            {formError ? (
              <p className="text-body text-destructive" role="alert">
                {formError}
              </p>
            ) : null}
          </DialogBody>
          <DialogFooter>
            <Button type="button" variant="secondary" onClick={() => onOpenChange(false)}>
              닫기
            </Button>
            <Button type="submit" variant={destructive ? 'destructive' : 'primary'} disabled={pending}>
              {pending ? pendingLabel : submitLabel}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
