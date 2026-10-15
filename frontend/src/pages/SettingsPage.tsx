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
            <p className="text-body text-muted-foreground">확인 중입니다.</p>
          ) : systemInfo.isError ? (
            <ErrorState
              message="Backend에 연결할 수 없습니다. 서버가 떠 있는지 확인해 주세요."
              onRetry={() => systemInfo.refetch()}
            />
          ) : (
            <DetailList
              items={[
                { label: 'Backend', value: systemInfo.data.application },
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
            <p className="text-body text-muted-foreground">
              Grafana 주소가 설정되지 않았습니다. 서버의 GRAFANA_URL을 확인해 주세요.
            </p>
          )}
        </Section>

        <Section>
          <SectionHeader
            title="기본 Threshold"
            description="CPU, Memory, Storage 경고 기준값입니다."
          />
          <NotBuiltYet>
            Threshold 기능을 아직 만들지 않았습니다. SNMP Resource 모니터링과 함께 추가합니다.
          </NotBuiltYet>
        </Section>
      </PageBody>
    </>
  )
}
