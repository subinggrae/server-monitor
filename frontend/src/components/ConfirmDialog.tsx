import { FormDialog } from '@/components/layout/FormDialog'

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
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={title}
      size="md"
      submitLabel={confirmLabel}
      pendingLabel={pendingLabel}
      pending={pending}
      destructive
      formError={errorMessage}
      onSubmit={onConfirm}
    >
      <p className="whitespace-pre-line text-body text-muted-foreground">{description}</p>
    </FormDialog>
  )
}
