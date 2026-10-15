import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { FormDialog } from '@/components/layout/FormDialog'
import { useOpenCount } from '@/components/layout/state'
import { Checkbox, CheckboxLabel, Field, Input } from '@/components/ui/input'
import {
  ApiError,
  apiPost,
  apiPut,
  queryKeys,
  type TelegramDestination,
  type TelegramDestinationInput,
} from '@/lib/api'

/** Bot Token은 입력하지도 보여 주지도 않습니다. 서버가 secret으로만 받습니다. (CLAUDE.md 11번) */
export function TelegramDestinationDialog({
  open,
  onOpenChange,
  destination,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  destination?: TelegramDestination
}) {
  // 열 때마다 key가 바뀌어 입력값과 오류가 처음 상태로 돌아갑니다.
  const openCount = useOpenCount(open)
  return (
    <DestinationForm key={openCount} open={open} onOpenChange={onOpenChange} destination={destination} />
  )
}

type FormState = {
  name: string
  chatId: string
  messageThreadId: string
  enabled: boolean
}

function DestinationForm({
  open,
  onOpenChange,
  destination,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  destination?: TelegramDestination
}) {
  const queryClient = useQueryClient()

  const [form, setForm] = useState<FormState>(() => ({
    name: destination?.name ?? '',
    // Chat ID는 음수이고 자릿수가 길어서 입력 중에는 문자열로 둡니다.
    chatId: destination ? String(destination.chatId) : '',
    messageThreadId: destination?.messageThreadId == null ? '' : String(destination.messageThreadId),
    enabled: destination?.enabled ?? true,
  }))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (input: TelegramDestinationInput) =>
      destination
        ? apiPut<TelegramDestination>(`/api/telegram/destinations/${destination.id}`, input)
        : apiPost<TelegramDestination>('/api/telegram/destinations', input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.telegramDestinations })
      queryClient.invalidateQueries({ queryKey: queryKeys.notificationBindings })
      onOpenChange(false)
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        setFieldErrors(error.fieldErrors)
        setFormError(Object.keys(error.fieldErrors).length > 0 ? null : error.message)
        return
      }
      setFormError('저장하지 못했습니다. 잠시 후 다시 시도해 주세요.')
    },
  })

  return (
    <FormDialog
      open={open}
      onOpenChange={onOpenChange}
      title={destination ? 'Telegram 그룹 수정' : 'Telegram 그룹 추가'}
      description="Chat ID를 직접 입력합니다. Telegram 그룹에서 /register를 보내 등록할 수도 있습니다."
      submitLabel={destination ? '저장' : '추가'}
      pendingLabel={destination ? '저장 중…' : '추가 중…'}
      pending={mutation.isPending}
      formError={formError}
      onSubmit={() => {
        setFieldErrors({})
        setFormError(null)

        const chatId = Number(form.chatId.trim())
        if (form.chatId.trim() === '' || !Number.isInteger(chatId)) {
          setFieldErrors({ chatId: 'Chat ID는 숫자로 입력해 주세요. 그룹은 보통 -로 시작합니다.' })
          return
        }

        mutation.mutate({
          name: form.name,
          chatId,
          messageThreadId: form.messageThreadId.trim() === '' ? null : Number(form.messageThreadId),
          enabled: form.enabled,
        })
      }}
    >
      <Field label="이름" htmlFor="destination-name" error={fieldErrors.name}>
        <Input
          id="destination-name"
          value={form.name}
          onChange={(event) => setForm({ ...form, name: event.target.value })}
          placeholder="서비스 운영팀"
          aria-invalid={Boolean(fieldErrors.name)}
          autoFocus
        />
      </Field>

      <Field
        label="Chat ID"
        htmlFor="destination-chat-id"
        hint="Telegram 그룹의 식별자입니다. 그룹은 -1001234567890처럼 -로 시작합니다."
        error={fieldErrors.chatId}
      >
        <Input
          id="destination-chat-id"
          value={form.chatId}
          onChange={(event) => setForm({ ...form, chatId: event.target.value })}
          placeholder="-1001234567890"
          aria-invalid={Boolean(fieldErrors.chatId)}
        />
      </Field>

      <Field
        label="토픽 ID"
        htmlFor="destination-thread-id"
        hint="토픽을 쓰는 그룹에만 입력합니다. 보통은 비워 둡니다."
        error={fieldErrors.messageThreadId}
      >
        <Input
          id="destination-thread-id"
          type="number"
          min={1}
          value={form.messageThreadId}
          onChange={(event) => setForm({ ...form, messageThreadId: event.target.value })}
          placeholder="비워 두면 그룹 전체에 보냅니다"
        />
      </Field>

      <CheckboxLabel className="text-body">
        <Checkbox
          checked={form.enabled}
          onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
        />
        이 그룹으로 알림을 보냅니다
      </CheckboxLabel>
    </FormDialog>
  )
}
