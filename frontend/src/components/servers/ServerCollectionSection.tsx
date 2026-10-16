import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'

import { queryState } from '@/components/layout/state'
import { Notice } from '@/components/layout/Notice'
import { FormActions, PageSection } from '@/components/layout/PageSection'
import { Button } from '@/components/ui/button'
import { Checkbox, CheckboxLabel, Field, Input } from '@/components/ui/input'
import { DetailList } from '@/components/ui/section'
import {
  ApiError,
  apiPost,
  apiPut,
  EXPORTER_TYPE_LABELS,
  queryKeys,
  serverExporterQuery,
  type ExporterTestResult,
  type ServerExporter,
  type ServerExporterInput,
  type ServerOs,
} from '@/lib/api'
import { parseIntegerInRange } from '@/lib/utils'
import { parseScrapeIntervalSeconds, SCRAPE_INTERVAL_ERROR, SCRAPE_INTERVAL_HINT } from '@/lib/scrapeInterval'

const PORT_ERROR = '포트는 1에서 65535 사이 숫자로 입력해 주세요.'

const INSTALL_GUIDES: Record<ServerOs, { label: string; url: string }> = {
  LINUX: { label: 'node_exporter 설치 안내', url: 'https://github.com/prometheus/node_exporter' },
  WINDOWS: { label: 'windows_exporter 설치 안내', url: 'https://github.com/prometheus-community/windows_exporter' },
}

type FormState = {
  enabled: boolean
  port: string
  scrapeInterval: string
}

/** 수집 설정의 기준은 Manager Database입니다. 저장하면 Prometheus가 다시 띄우지 않고 다음 SD 갱신부터 따릅니다. */
export function ServerCollectionSection({ serverId, os }: { serverId: number; os: ServerOs }) {
  const exporter = useQuery(serverExporterQuery(serverId))
  const guide = INSTALL_GUIDES[os]

  return (
    <PageSection
      title="OS exporter"
      description="서버에 설치한 exporter에서 CPU, Memory, Storage를 수집합니다. 서버가 살아 있는지는 감시 항목이 확인합니다."
      state={queryState(exporter, '수집 설정을 불러오지 못했습니다.')}
    >
      <Notice title="exporter 포트는 Prometheus와 Monitoring Manager만 접근하게 해 주세요.">
        exporter는 인증 없이 열립니다. 대상 서버의 방화벽에서 이 포트를 Prometheus와 Monitoring Manager에만
        열어 둡니다.{' '}
        <a href={guide.url} target="_blank" rel="noreferrer" className="text-foreground underline">
          {guide.label}
        </a>
      </Notice>

      {/* 저장한 뒤 다시 불러와도 폼을 새로 만들지 않습니다. 새로 만들면 저장 결과 문구가 사라집니다. */}
      {exporter.data ? <CollectionForm serverId={serverId} exporter={exporter.data} /> : null}
    </PageSection>
  )
}

function CollectionForm({ serverId, exporter }: { serverId: number; exporter: ServerExporter }) {
  const queryClient = useQueryClient()
  const [form, setForm] = useState<FormState>(() => ({
    enabled: exporter.enabled,
    port: String(exporter.port),
    scrapeInterval: String(exporter.scrapeIntervalSeconds),
  }))
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [savedMessage, setSavedMessage] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<ExporterTestResult | null>(null)
  const [testFailed, setTestFailed] = useState(false)

  const saveMutation = useMutation({
    mutationFn: (input: ServerExporterInput) =>
      apiPut<ServerExporter>(`/api/servers/${serverId}/exporter`, input),
    onSuccess: (saved) => {
      setSavedMessage(saved.enabled ? '저장했습니다. 1분 안에 수집을 시작합니다.' : '저장했습니다.')
      // 수집 설정이 바뀌면 서버의 수집 상태와 리소스 찾기 가능 여부도 바뀝니다.
      queryClient.setQueryData(queryKeys.serverExporter(serverId), saved)
      queryClient.invalidateQueries({ queryKey: queryKeys.serverResources(serverId) })
      queryClient.invalidateQueries({ queryKey: queryKeys.servers })
      queryClient.invalidateQueries({ queryKey: queryKeys.serverDetail(serverId) })
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
    mutationFn: (port: number) => apiPost<ExporterTestResult>(`/api/servers/${serverId}/exporter/test`, { port }),
    onSuccess: (result) => setTestResult(result),
    onError: () => setTestFailed(true),
  })

  /** 칸 오류를 보이고 null을 돌려줍니다. 저장과 연결 시험이 같은 포트 검증을 씁니다. */
  const readPort = () => {
    const port = parseIntegerInRange(form.port, 1, 65535)
    if (port === null) {
      setFieldErrors({ port: PORT_ERROR })
    }
    return port
  }

  const startTest = () => {
    setFieldErrors({})
    setTestResult(null)
    setTestFailed(false)
    const port = readPort()
    if (port !== null) {
      testMutation.mutate(port)
    }
  }

  return (
    <>
      <form
        className="max-w-md space-y-4"
        onSubmit={(event) => {
          event.preventDefault()
          setFieldErrors({})
          setFormError(null)
          setSavedMessage(null)

          const port = readPort()
          if (port === null) {
            return
          }
          const scrapeIntervalSeconds = parseScrapeIntervalSeconds(form.scrapeInterval)
          if (scrapeIntervalSeconds === null) {
            setFieldErrors({ scrapeIntervalSeconds: SCRAPE_INTERVAL_ERROR })
            return
          }

          saveMutation.mutate({ enabled: form.enabled, port, scrapeIntervalSeconds })
        }}
      >
        <CheckboxLabel className="text-body">
          <Checkbox
            name="enabled"
            checked={form.enabled}
            onChange={(event) => setForm({ ...form, enabled: event.target.checked })}
          />
          이 서버에서 OS exporter로 수집합니다
        </CheckboxLabel>

        <Field label="포트" htmlFor="exporter-port" hint={`기본 포트는 ${exporter.defaultPort}입니다.`} error={fieldErrors.port}>
          <Input
            id="exporter-port"
            name="port"
            inputMode="numeric"
            autoComplete="off"
            className="tabular w-28"
            value={form.port}
            onChange={(event) => setForm({ ...form, port: event.target.value })}
            aria-invalid={Boolean(fieldErrors.port)}
          />
        </Field>

        <Field
          label="수집 주기"
          htmlFor="exporter-scrape-interval"
          hint={SCRAPE_INTERVAL_HINT}
          error={fieldErrors.scrapeIntervalSeconds}
        >
          <div className="flex items-center gap-2">
            <Input
              id="exporter-scrape-interval"
              name="scrapeIntervalSeconds"
              inputMode="numeric"
              autoComplete="off"
              className="tabular w-24"
              value={form.scrapeInterval}
              onChange={(event) => setForm({ ...form, scrapeInterval: event.target.value })}
              aria-invalid={Boolean(fieldErrors.scrapeIntervalSeconds)}
            />
            <span className="text-body text-muted-foreground">초</span>
          </div>
        </Field>

        <FormActions
          submitLabel="저장"
          pendingLabel="저장 중…"
          pending={saveMutation.isPending}
          result={
            formError
              ? { kind: 'error', text: formError }
              : savedMessage
                ? { kind: 'success', text: savedMessage }
                : null
          }
        >
          <Button type="button" variant="secondary" disabled={testMutation.isPending} onClick={startTest}>
            {testMutation.isPending ? '시험 중…' : '연결 시험'}
          </Button>
        </FormActions>
      </form>

      <div aria-live="polite" className="max-w-md">
        {testFailed ? <Notice tone="warning" title="연결 시험을 하지 못했습니다. 잠시 후 다시 시도해 주세요." /> : null}
        {testResult ? <TestResult result={testResult} /> : null}
      </div>
    </>
  )
}

function TestResult({ result }: { result: ExporterTestResult }) {
  if (!result.success || !result.exporterType) {
    return <Notice tone="warning" title={result.message} />
  }

  const details = (
    <DetailList
      items={[
        { label: '종류', value: <span translate="no">{EXPORTER_TYPE_LABELS[result.exporterType]}</span> },
        { label: '버전', value: <span className="tabular">{result.version ?? '-'}</span> },
        {
          label: '응답 시간',
          value: <span className="tabular">{result.responseTimeMs ?? 0}&nbsp;ms</span>,
        },
      ]}
    />
  )

  if (result.osMatches === false) {
    return (
      <Notice tone="warning" title={result.message}>
        {details}
      </Notice>
    )
  }

  return (
    <div className="space-y-2">
      <p className="text-body font-medium">{result.message}</p>
      {details}
    </div>
  )
}
