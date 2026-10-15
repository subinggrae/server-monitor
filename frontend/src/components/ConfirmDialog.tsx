import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogFooter } from '@/components/ui/dialog'

/** description에는 무엇이 사라지는지 구체적으로 적습니다. */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = '삭제',
  pendingLabel = '삭제 중…',
  pending = false,
  errorMessage,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description: string
  confirmLabel?: string
  /** 요청 중 실행 버튼에 보일 문구입니다. 동작 이름으로 씁니다. 예: `해제 중…` */
  pendingLabel?: string
  pending?: boolean
  errorMessage?: string
  onConfirm: () => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={title} className="max-w-md">
        <DialogBody>
          <p className="whitespace-pre-line text-body text-muted-foreground">{description}</p>
          {errorMessage ? <p className="text-body text-destructive">{errorMessage}</p> : null}
        </DialogBody>
        <DialogFooter>
          <Button variant="secondary" onClick={() => onOpenChange(false)} disabled={pending}>
            닫기
          </Button>
          <Button variant="destructive" onClick={onConfirm} disabled={pending}>
            {pending ? pendingLabel : confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
