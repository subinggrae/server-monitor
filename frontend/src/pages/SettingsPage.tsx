import { useQuery } from '@tanstack/react-query'

import { PageBody, PageHeader } from '@/components/PageHeader'
import { ErrorState } from '@/components/QueryStates'
import { Button } from '@/components/ui/button'
import { DetailList, NotBuiltYet, Section, SectionHeader } from '@/components/ui/section'
import { systemInfoQuery } from '@/lib/api'

export function SettingsPage() {
  const systemInfo = useQuery(systemInfoQuery)

  return (
    <>
      <PageHeader title="설정" description="시스템 연동 상태를 확인합니다." />

      <PageBody>
        <Section>
          <SectionHeader title="연동 상태" />

          {systemInfo.isPending ? (
            <p className="text-body text-muted-foreground">확인 중…</p>
          ) : systemInfo.isError ? (
            <ErrorState
              message="연동 상태를 확인하지 못했습니다. Monitoring Manager가 실행 중인지 확인해 주세요."
              onRetry={() => systemInfo.refetch()}
            />
          ) : (
            <DetailList
              items={[
                { label: '애플리케이션 이름', value: systemInfo.data.application },
                {
                  label: 'Telegram Bot',
                  value: systemInfo.data.telegramConfigured ? (
                    '설정됨'
                  ) : (
                    <span className="text-critical">설정 안 됨</span>
                  ),
                },
              ]}
            />
          )}
        </Section>

        <Section>
          <SectionHeader title="Grafana" description="상세 시계열 분석은 Grafana에서 봅니다." />

          {systemInfo.data?.grafanaUrl ? (
            <div className="flex items-center gap-3">
              <span className="tabular text-body text-muted-foreground">
                {systemInfo.data.grafanaUrl}
              </span>
              <Button
                variant="secondary"
                size="sm"
                onClick={() => window.open(systemInfo.data.grafanaUrl, '_blank', 'noopener')}
              >
                열기
              </Button>
            </div>
          ) : (
            <div>
              <p className="text-body text-muted-foreground">Grafana 주소가 설정되지 않았습니다.</p>
              <p className="mt-0.5 text-caption text-muted-foreground">환경 변수 GRAFANA_URL로 정합니다.</p>
            </div>
          )}
        </Section>

        <Section>
          <SectionHeader
            title="기본 임계값"
            description="CPU, 메모리, 스토리지 경고 기준값입니다."
          />
          <NotBuiltYet>기본 임계값은 아직 정할 수 없습니다.</NotBuiltYet>
        </Section>
      </PageBody>
    </>
  )
}
