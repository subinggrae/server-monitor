import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { Button } from '@/components/ui/button'
import { Dialog, DialogBody, DialogContent, DialogFooter } from '@/components/ui/dialog'
import { Checkbox, CheckboxLabel, Field, Input, Select, Textarea } from '@/components/ui/input'
import {
  ApiError,
  apiPost,
  apiPut,
  queryKeys,
  type ServerTarget,
  type ServerTargetInput,
} from '@/lib/api'

const ENVIRONMENTS = ['Production', 'Staging', 'Development', 'Other']

export function ServerFormDialog({
  open,
  onOpenChange,
  server,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  server?: ServerTarget
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title={server ? '서버 수정' : '서버 추가'}
        description="서버 자산 정보를 등록합니다. SNMP Resource 모니터링에 사용할 예정입니다."
      >
        {/* Dialog를 닫으면 이 component가 사라집니다.
            그래서 입력값을 초기화하는 effect가 따로 필요하지 않습니다. */}
        <ServerForm server={server} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function ServerForm({ server, onDone }: { server?: ServerTarget; onDone: () => void }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<ServerTargetInput>(() => ({
    name: server?.name ?? '',
    host: server?.host ?? '',
    environment: server?.environment ?? 'Production',
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
      onDone()
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
    <form
      onSubmit={(event) => {
        event.preventDefault()
        setFieldErrors({})
        setFormError(null)
        mutation.mutate(form)
      }}
    >
      <DialogBody>
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
          label="Host / IP"
          htmlFor="server-host"
          hint="IP 또는 hostname을 입력합니다. http:// 같은 주소는 넣지 않습니다."
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
            이 서버를 모니터링합니다
          </CheckboxLabel>
          <p className="pl-5.5 text-caption text-muted-foreground">
            끄면 목록에서 사용 안 함으로 표시합니다. Monitoring Check에는 영향을 주지 않습니다.
          </p>
        </div>

        {formError ? <p className="text-body text-destructive">{formError}</p> : null}
      </DialogBody>

      <DialogFooter>
        <Button type="button" variant="secondary" onClick={onDone} disabled={mutation.isPending}>
          취소
        </Button>
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? '저장 중…' : '저장'}
        </Button>
      </DialogFooter>
    </form>
  )
}
