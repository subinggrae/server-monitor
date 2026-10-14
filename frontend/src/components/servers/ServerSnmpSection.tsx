import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { Checkbox, CheckboxLabel, Field, Input, Select } from '@/components/ui/input'
import { DetailList, Section, SectionHeader } from '@/components/ui/section'
import {
  ApiError,
  apiDelete,
  apiPost,
  apiPut,
  queryKeys,
  serverSnmpQuery,
  type ServerSnmp,
  type ServerSnmpInput,
  type SnmpTestResult,
} from '@/lib/api'

type FormState = {
  port: string
  community: string
  enabled: boolean
}

/** Community는 서버가 돌려주지 않습니다. 새로 입력했을 때만 바뀝니다. (CLAUDE.md 11번) */
export function ServerSnmpSection({ serverId }: { serverId: number }) {
  const snmp = useQuery(serverSnmpQuery(serverId))

  if (snmp.isPending) {
    return null
  }

  if (snmp.isError) {
    return (
      <Section>
        <SectionHeader title="SNMP" />
        <p className="text-[13px] text-destructive">SNMP 설정을 불러오지 못했습니다.</p>
      </Section>
    )
  }

  return <SnmpForm key={snmp.dataUpdatedAt} serverId={serverId} snmp={snmp.data} />
}

function SnmpForm({ serverId, snmp }: { serverId: number; snmp: ServerSnmp }) {
  const queryClient = useQueryClient()

  const [form, setForm] = useState<FormState>(() => ({
    port: String(snmp.port ?? 161),
    community: '',
    enabled: snmp.configured ? snmp.enabled : true,
  }))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [testResult, setTestResult] = useState<SnmpTestResult | null>(null)
  const [removing, setRemoving] = useState(false)

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: queryKeys.serverSnmp(serverId) })
    queryClient.invalidateQueries({ queryKey: queryKeys.serverResources(serverId) })
    queryClient.invalidateQueries({ queryKey: queryKeys.servers })
    queryClient.invalidateQueries({ queryKey: queryKeys.serverDetail(serverId) })
  }

  const saveMutation = useMutation({
    mutationFn: (input: ServerSnmpInput) => apiPut<ServerSnmp>(`/api/servers/${serverId}/snmp`, input),
    onSuccess: () => {
      setSaved(true)
      setTestResult(null)
      invalidate()
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

  const testMutation = useMutation({
    mutationFn: () => apiPost<SnmpTestResult>(`/api/servers/${serverId}/snmp/test`, {}),
    onSuccess: (result) => setTestResult(result),
    onError: () =>
      setTestResult({
        success: false,
        message: 'SNMP 연결을 확인하지 못했습니다. 잠시 후 다시 시도해 주세요.',
        systemName: null,
        systemDescription: null,
        responseTimeMs: null,
      }),
  })

  const deleteMutation = useMutation({
    mutationFn: () => apiDelete(`/api/servers/${serverId}/snmp`),
    onSuccess: () => {
      setRemoving(false)
      invalidate()
    },
  })

  return (
    <Section>
      <SectionHeader
        title="SNMP"
        description="Agent를 설치하지 않고 CPU, Memory, Storage를 수집합니다."
        actions={
          snmp.configured ? (
            <Button variant="destructive-ghost" size="sm" onClick={() => setRemoving(true)}>
              SNMP 해제
            </Button>
          ) : null
        }
      />

      {snmp.configured && !snmp.exporterSynced ? (
        <div className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2.5 text-[13px]">
          <p className="font-medium text-destructive">snmp_exporter에 반영되지 않았습니다.</p>
          <p className="mt-0.5 text-muted-foreground">
            설정은 저장했지만 수집은 시작되지 않습니다. {snmp.exporterMessage}
          </p>
        </div>
      ) : null}

      <form
        className="max-w-md space-y-4"
        onSubmit={(event) => {
          event.preventDefault()
          setFieldErrors({})
          setFormError(null)
          setSaved(false)

          const port = Number(form.port.trim())
          if (!Number.isInteger(port) || port < 1 || port > 65535) {
            setFieldErrors({ port: 'Port는 1에서 65535 사이 숫자로 입력해 주세요.' })
            return
          }

          saveMutation.mutate({
            version: 'V2C',
            port,
            community: form.community.trim() === '' ? null : form.community.trim(),
            enabled: form.enabled,
          })
        }}
      >
        <Field label="버전" htmlFor="snmp-version" hint="지금은 SNMP v2c만 지원합니다.">
          <Select id="snmp-version" value="V2C" disabled>
            <option value="V2C">SNMP v2c</option>
          </Select>
        </Field>

        <Field label="Port" htmlFor="snmp-port" error={fieldErrors.port}>
          <Input
            id="snmp-port"
            value={form.port}
            onChange={(event) => setForm({ ...form, port: event.target.value })}
            placeholder="161"
            aria-invalid={Boolean(fieldErrors.port)}
          />
        </Field>

        <Field
          label="Community"
          htmlFor="snmp-community"
          hint={
            snmp.communityConfigured
              ? '설정되어 있습니다. 바꿀 때만 새 값을 입력해 주세요.'
              : '장비에 설정한 read community를 입력해 주세요.'
          }
          error={fieldErrors.community}
        >
          <Input
            id="snmp-community"
            type="password"
            autoComplete="off"
            value={form.community}
            onChange={(event) => setForm({ ...form, community: event.target.value })}
            placeholder={snmp.communityConfigured ? '변경하지 않으면 비워 둡니다' : ''}
            aria-invalid={Boolean(fieldErrors.community)}
          />
        </Field>

        <CheckboxLabel className="text-[13px]">
          <Checkbox
            checked={form.enabled}
            onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
          />
          이 서버에서 SNMP로 수집합니다
        </CheckboxLabel>

        {formError ? <p className="text-[13px] text-destructive">{formError}</p> : null}

        <div className="flex items-center gap-2">
          <Button type="submit" disabled={saveMutation.isPending}>
            {saveMutation.isPending ? '저장 중…' : '저장'}
          </Button>
          <Button
            type="button"
            variant="secondary"
            disabled={!snmp.configured || testMutation.isPending}
            onClick={() => testMutation.mutate()}
          >
            {testMutation.isPending ? '확인 중…' : '연결 테스트'}
          </Button>
          {saved ? <span className="text-[13px] text-muted-foreground">저장했습니다.</span> : null}
        </div>
      </form>

      {testResult ? (
        <div className="max-w-md rounded-md border border-border bg-surface px-3 py-2.5 text-[13px]">
          <p className={testResult.success ? 'font-medium' : 'font-medium text-destructive'}>
            {testResult.message}
          </p>
          {testResult.success ? (
            <div className="mt-2">
              <DetailList
                items={[
                  { label: '장비 이름', value: testResult.systemName ?? '-' },
                  { label: '응답 시간', value: `${testResult.responseTimeMs ?? 0} ms` },
                  ...(testResult.systemDescription
                    ? [{ label: '설명', value: testResult.systemDescription }]
                    : []),
                ]}
              />
            </div>
          ) : null}
        </div>
      ) : null}

      <ConfirmDialog
        open={removing}
        onOpenChange={setRemoving}
        title="SNMP 설정을 해제할까요?"
        description={
          '저장한 Community가 지워지고 이 서버의 Resource 수집이 멈춥니다.\n서버와 고른 Resource 목록은 그대로 남습니다.'
        }
        confirmLabel="해제"
        pending={deleteMutation.isPending}
        errorMessage={deleteMutation.isError ? '해제하지 못했습니다. 잠시 후 다시 시도해 주세요.' : undefined}
        onConfirm={() => deleteMutation.mutate()}
      />
    </Section>
  )
}
