import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { FormDialog } from '@/components/layout/FormDialog'
import { useOpenCount } from '@/components/layout/state'
import { Checkbox, CheckboxLabel, Field, Input, Select, Textarea } from '@/components/ui/input'
import {
  ApiError,
  apiPost,
  apiPut,
  queryKeys,
  SERVER_OS_LABELS,
  type ServerOs,
  type ServerTarget,
  type ServerTargetInput,
} from '@/lib/api'

const ENVIRONMENTS = ['Production', 'Staging', 'Development', 'Other']

const OS_OPTIONS: ServerOs[] = ['LINUX', 'WINDOWS']

export function ServerFormDialog({
  open,
  onOpenChange,
  server,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  server?: ServerTarget
}) {
  // 열 때마다 key가 바뀌어 입력값과 오류가 처음 상태로 돌아갑니다.
  const openCount = useOpenCount(open)
  return <ServerForm key={openCount} open={open} onOpenChange={onOpenChange} server={server} />
}

function ServerForm({
  open,
  onOpenChange,
  server,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  server?: ServerTarget
}) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ServerTargetInput>(() => ({
    name: server?.name ?? '',
    host: server?.host ?? '',
    environment: server?.environment ?? 'Production',
    os: server?.os ?? 'LINUX',
    description: server?.description ?? null,
    enabled: server?.enabled ?? true,
  }))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  const mutation = useMutation({
    mutationFn: (input: ServerTargetInput) =>
      server
        ? apiPut<ServerTarget>(`/api/servers/${server.id}`, input)
        : apiPost<ServerTarget>('/api/servers', input),
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.servers })
      queryClient.invalidateQueries({ queryKey: queryKeys.serverDetail(saved.id) })
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
      title={server ? '서버 수정' : '서버 추가'}
      description={
        server
          ? '서버 정보를 바꿉니다. 감시 항목과 수집 설정은 그대로 남습니다.'
          : '감시할 서버를 등록합니다. 등록한 뒤 서버 상세의 수집 탭에서 OS exporter 수집을 켜면 CPU, Memory, Storage 사용량을 수집합니다.'
      }
      submitLabel={server ? '저장' : '추가'}
      pendingLabel={server ? '저장 중…' : '추가 중…'}
      pending={mutation.isPending}
      formError={formError}
      onSubmit={() => {
        setFieldErrors({})
        setFormError(null)
        mutation.mutate(form)
      }}
    >
      <Field label="이름" htmlFor="server-name" error={fieldErrors.name}>
        <Input
          id="server-name"
          value={form.name}
          onChange={(event) => setForm({ ...form, name: event.target.value })}
          placeholder="PROD-WEB-01"
          aria-invalid={Boolean(fieldErrors.name)}
          autoFocus
        />
      </Field>

      <Field
        label="호스트"
        htmlFor="server-host"
        hint="IP 또는 호스트 이름을 입력합니다. http:// 같은 주소는 넣지 않습니다."
        error={fieldErrors.host}
      >
        <Input
          id="server-host"
          value={form.host}
          onChange={(event) => setForm({ ...form, host: event.target.value })}
          placeholder="10.10.10.15"
          aria-invalid={Boolean(fieldErrors.host)}
        />
      </Field>

      <Field label="환경" htmlFor="server-environment" error={fieldErrors.environment}>
        <Select
          id="server-environment"
          value={form.environment}
          onChange={(event) => setForm({ ...form, environment: event.target.value })}
        >
          {ENVIRONMENTS.map((environment) => (
            <option key={environment} value={environment}>
              {environment}
            </option>
          ))}
        </Select>
      </Field>

      <Field
        label="OS"
        htmlFor="server-os"
        hint={server ? 'OS를 바꾸면 포트 기본값과 리소스 찾기 방식이 바뀝니다.' : undefined}
        error={fieldErrors.os}
      >
        <Select
          id="server-os"
          name="os"
          value={form.os}
          onChange={(event) => setForm({ ...form, os: event.target.value as ServerOs })}
          aria-invalid={Boolean(fieldErrors.os)}
        >
          {OS_OPTIONS.map((os) => (
            <option key={os} value={os}>
              {SERVER_OS_LABELS[os]}
            </option>
          ))}
        </Select>
      </Field>

      <Field label="설명" htmlFor="server-description" error={fieldErrors.description}>
        <Textarea
          id="server-description"
          value={form.description ?? ''}
          onChange={(event) =>
            setForm({
              ...form,
              description: event.target.value.trim() === '' ? null : event.target.value,
            })
          }
          placeholder="어떤 용도의 서버인지 적어 두면 나중에 찾기 쉽습니다."
        />
      </Field>

      <div className="space-y-1">
        <CheckboxLabel className="text-body">
          <Checkbox
            checked={form.enabled}
            onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
          />
          이 서버를 감시합니다
        </CheckboxLabel>
        <p className="pl-5.5 text-caption text-muted-foreground">
          끄면 이 서버의 자원 수집을 멈추고 수집 꺼짐으로 표시합니다. 감시 항목에는 영향을 주지 않습니다.
        </p>
      </div>
    </FormDialog>
  )
}
