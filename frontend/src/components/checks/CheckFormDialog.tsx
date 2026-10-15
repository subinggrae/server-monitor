import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'

import { FormDialog } from '@/components/layout/FormDialog'
import { useOpenCount } from '@/components/layout/state'
import { Button } from '@/components/ui/button'
import { Checkbox, CheckboxLabel, Field, Input, Label, Select, Textarea } from '@/components/ui/input'
import {
  ApiError,
  apiPost,
  apiPut,
  checkTypesQuery,
  queryKeys,
  type CheckTypeName,
  type MonitoringCheck,
  type MonitoringCheckInput,
} from '@/lib/api'
import { parseScrapeIntervalSeconds, SCRAPE_INTERVAL_ERROR, SCRAPE_INTERVAL_HINT } from '@/lib/scrapeInterval'

/** port는 입력 중 빈 값일 수 있어서 문자열로 둡니다. */
type TargetRow = {
  /** 기존 Target이면 id가 있습니다. 유지해야 time series가 끊기지 않습니다. */
  id?: number
  target: string
  port: string
  enabled: boolean
}

type FormState = {
  name: string
  type: CheckTypeName
  environment: string
  description: string | null
  enabled: boolean
  /** port처럼 입력 중 빈 값일 수 있어서 문자열로 둡니다. */
  scrapeInterval: string
  targets: TargetRow[]
}

const EMPTY_TARGET: TargetRow = { target: '', port: '', enabled: true }

const ENVIRONMENTS = ['Production', 'Staging', 'Development', 'Other']

/** Type이 바뀌면 입력 칸의 의미도 바뀌므로 안내 문구를 함께 바꿉니다. */
const TARGET_FIELD: Record<CheckTypeName, { label: string; hint: string; placeholder: string }> = {
  ICMP: {
    label: '대상',
    hint: 'Ping을 보낼 IP 또는 호스트 이름을 입력합니다.',
    placeholder: '10.10.10.15',
  },
  TCP: {
    label: '호스트',
    hint: '연결을 확인할 IP 또는 호스트 이름을 입력합니다. 대상마다 포트를 따로 정할 수 있습니다.',
    placeholder: '10.10.10.15',
  },
  HTTP: {
    label: 'URL',
    hint: 'http://로 시작하는 전체 주소를 입력합니다. 2xx 응답이면 정상으로 봅니다.',
    placeholder: 'http://10.0.0.11:8080/actuator/health',
  },
  HTTPS: {
    label: 'URL',
    hint: 'https://로 시작하는 전체 주소를 입력합니다. 2xx 응답이면 정상으로 봅니다.',
    placeholder: 'https://service.example.com/health',
  },
}

export function CheckFormDialog({
  open,
  onOpenChange,
  check,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  check?: MonitoringCheck
}) {
  // 열 때마다 key가 바뀌어 입력값과 오류가 처음 상태로 돌아갑니다.
  const openCount = useOpenCount(open)
  return <CheckForm key={openCount} open={open} onOpenChange={onOpenChange} check={check} />
}

function CheckForm({
  open,
  onOpenChange,
  check,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  check?: MonitoringCheck
}) {
  const queryClient = useQueryClient()
  const { data: checkTypes } = useQuery({ ...checkTypesQuery, enabled: open })

  const [form, setForm] = useState<FormState>(() => ({
    name: check?.name ?? '',
    type: check?.type ?? 'ICMP',
    environment: check?.environment ?? 'Production',
    description: check?.description ?? null,
    enabled: check?.enabled ?? true,
    scrapeInterval: String(check?.scrapeIntervalSeconds ?? 15),
    targets:
      check?.targets.map((target) => ({
        id: target.id,
        target: target.target,
        port: target.port == null ? '' : String(target.port),
        enabled: target.enabled,
      })) ?? [{ ...EMPTY_TARGET }],
  }))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)

  const capability = checkTypes?.find((item) => item.type === form.type)
  const targetField = TARGET_FIELD[form.type]

  const updateTarget = (index: number, patch: Partial<TargetRow>) => {
    setForm((previous) => ({
      ...previous,
      targets: previous.targets.map((row, rowIndex) =>
        rowIndex === index ? { ...row, ...patch } : row,
      ),
    }))
  }

  const mutation = useMutation({
    mutationFn: (input: MonitoringCheckInput) =>
      check
        ? apiPut<MonitoringCheck>(`/api/checks/${check.id}`, input)
        : apiPost<MonitoringCheck>('/api/checks', input),
    onSuccess: (saved) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.checks })
      queryClient.invalidateQueries({ queryKey: queryKeys.checkDetail(saved.id) })
      onOpenChange(false)
    },
    onError: (error) => {
      if (error instanceof ApiError) {
        setFieldErrors(error.fieldErrors)
        // 여러 값을 함께 봐야 하는 오류는 fieldErrors 없이 메시지로만 옵니다.
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
      title={check ? '감시 항목 수정' : '감시 항목 추가'}
      description="같은 애플리케이션이 서버 여러 대에서 동작하면 대상을 여러 개 등록합니다."
      size="xl"
      submitLabel="저장"
      pendingLabel="저장 중…"
      pending={mutation.isPending}
      formError={formError}
      onSubmit={() => {
        setFieldErrors({})
        setFormError(null)

        const scrapeIntervalSeconds = parseScrapeIntervalSeconds(form.scrapeInterval)
        if (scrapeIntervalSeconds === null) {
          setFieldErrors({ scrapeIntervalSeconds: SCRAPE_INTERVAL_ERROR })
          return
        }

        mutation.mutate({
          name: form.name,
          type: form.type,
          environment: form.environment,
          description: form.description,
          enabled: form.enabled,
          scrapeIntervalSeconds,
          targets: form.targets.map((row) => ({
            id: row.id,
            target: row.target,
            port: capability?.requiresPort && row.port !== '' ? Number(row.port) : null,
            enabled: row.enabled,
          })),
        })
      }}
    >
      <Field label="이름" htmlFor="check-name" error={fieldErrors.name}>
        <Input
          id="check-name"
          value={form.name}
          onChange={(event) => setForm({ ...form, name: event.target.value })}
          placeholder="전자결재 Health"
          aria-invalid={Boolean(fieldErrors.name)}
          autoFocus
        />
      </Field>

      <div className="grid grid-cols-2 gap-4">
        <Field label="종류" htmlFor="check-type" error={fieldErrors.type}>
          <Select
            id="check-type"
            value={form.type}
            onChange={(event) =>
              // Type이 바뀌면 대상 입력의 의미가 달라지므로 줄을 비우고 새로 받습니다.
              setForm({
                ...form,
                type: event.target.value as CheckTypeName,
                targets: [{ ...EMPTY_TARGET }],
              })
            }
          >
            {(checkTypes ?? []).map((item) => (
              <option key={item.type} value={item.type}>
                {item.displayName}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="환경" htmlFor="check-environment" error={fieldErrors.environment}>
          <Select
            id="check-environment"
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
      </div>

      <Field
        label="수집 주기"
        htmlFor="check-scrape-interval"
        hint={SCRAPE_INTERVAL_HINT}
        error={fieldErrors.scrapeIntervalSeconds}
      >
        <div className="flex items-center gap-2">
          <Input
            id="check-scrape-interval"
            name="scrapeIntervalSeconds"
            inputMode="numeric"
            autoComplete="off"
            className="w-24"
            value={form.scrapeInterval}
            onChange={(event) => setForm({ ...form, scrapeInterval: event.target.value })}
            aria-invalid={Boolean(fieldErrors.scrapeIntervalSeconds)}
          />
          <span className="text-body text-muted-foreground">초</span>
        </div>
      </Field>

      <div className="space-y-1.5">
        <div className="flex items-baseline justify-between">
          <Label>확인할 대상</Label>
          <span className="text-caption text-muted-foreground">{form.targets.length}개</span>
        </div>
        <p className="text-caption text-muted-foreground">{targetField.hint}</p>

        <div className="space-y-1.5 pt-0.5">
          {form.targets.map((row, index) => (
            <div key={row.id ?? `new-${index}`} className="flex items-center gap-2">
              <Input
                value={row.target}
                onChange={(event) => updateTarget(index, { target: event.target.value })}
                placeholder={targetField.placeholder}
                aria-label={`${targetField.label} ${index + 1}`}
              />

              {capability?.requiresPort ? (
                <Input
                  type="number"
                  min={1}
                  max={65535}
                  className="w-24"
                  value={row.port}
                  onChange={(event) => updateTarget(index, { port: event.target.value })}
                  placeholder="3306"
                  aria-label={`포트 ${index + 1}`}
                />
              ) : null}

              <CheckboxLabel className="shrink-0 text-caption text-muted-foreground">
                <Checkbox
                  checked={row.enabled}
                  onChange={(event) => updateTarget(index, { enabled: event.target.checked })}
                />
                사용
              </CheckboxLabel>

              <Button
                type="button"
                variant="ghost"
                size="icon"
                className="shrink-0"
                // 대상이 하나뿐이면 지울 수 없습니다. Check에는 대상이 하나 이상 있어야 합니다.
                disabled={form.targets.length === 1}
                onClick={() =>
                  setForm({
                    ...form,
                    targets: form.targets.filter((_, rowIndex) => rowIndex !== index),
                  })
                }
                aria-label={`대상 ${index + 1} 삭제`}
              >
                <Trash2 className="size-3.5" aria-hidden />
              </Button>
            </div>
          ))}
        </div>

        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="-ml-2.5"
          onClick={() => setForm({ ...form, targets: [...form.targets, { ...EMPTY_TARGET }] })}
        >
          <Plus className="size-3.5" aria-hidden />
          대상 추가
        </Button>
      </div>

      <Field label="설명" htmlFor="check-description" error={fieldErrors.description}>
        <Textarea
          id="check-description"
          value={form.description ?? ''}
          onChange={(event) =>
            setForm({
              ...form,
              description: event.target.value.trim() === '' ? null : event.target.value,
            })
          }
          placeholder="무엇을 확인하는 감시 항목인지 적어 두면 나중에 찾기 쉽습니다."
        />
      </Field>

      <CheckboxLabel className="text-body">
        <Checkbox
          checked={form.enabled}
          onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
        />
        이 감시 항목을 사용합니다
      </CheckboxLabel>
    </FormDialog>
  )
}
