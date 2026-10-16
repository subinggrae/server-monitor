import { useQuery } from '@tanstack/react-query'

import { queryState } from '@/components/layout/state'
import { PageSection } from '@/components/layout/PageSection'
import { SettingsPage as SettingsLayout } from '@/components/layout/SettingsPage'
import { buttonVariants } from '@/components/ui/button'
import { DetailList } from '@/components/ui/section'
import { systemInfoQuery } from '@/lib/api'

const LINK_CLASS = 'text-foreground underline underline-offset-4'

export function SettingsPage() {
  const systemInfo = useQuery(systemInfoQuery)
  const systemState = queryState(
    systemInfo,
    '연동 상태를 확인하지 못했습니다. Monitoring Manager가 실행 중인지 확인해 주세요.',
  )
  const grafanaUrl = systemInfo.data?.grafanaUrl

  return (
    <SettingsLayout title="설정" description="시스템 연동 상태를 확인합니다.">
      <PageSection title="연동 상태" state={systemState}>
        {systemInfo.data ? (
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
        ) : null}
      </PageSection>

      <PageSection
        title="Grafana"
        description="상세 시계열 분석은 Grafana에서 봅니다."
        actions={
          grafanaUrl ? (
            <a
              href={grafanaUrl}
              target="_blank"
              rel="noopener noreferrer"
              className={buttonVariants({ variant: 'secondary', size: 'sm' })}
            >
              열기
            </a>
          ) : null
        }
        state={systemState}
      >
        {grafanaUrl ? (
          <p className="tabular text-body text-muted-foreground">{grafanaUrl}</p>
        ) : (
          <div>
            <p className="text-body text-muted-foreground">Grafana 주소가 설정되지 않았습니다.</p>
            <p className="mt-0.5 text-caption text-muted-foreground">환경 변수 GRAFANA_URL로 정합니다.</p>
          </div>
        )}
      </PageSection>

      <PageSection
        title="기본 임계값"
        description="CPU, 메모리, 스토리지 경고 기준값입니다."
        state={{ kind: 'empty', title: '기본 임계값은 아직 정할 수 없습니다.' }}
      />

      <PageSection title="오픈소스 고지">
        <div className="space-y-2 text-body">
          <p>화면 글꼴로 Pretendard(SIL Open Font License 1.1)를 씁니다.</p>
          <ul className="space-y-1">
            <li>
              <a href="/licenses/pretendard-OFL.txt" target="_blank" rel="noopener noreferrer" className={LINK_CLASS}>
                Pretendard 라이선스 전문
              </a>
            </li>
            <li>
              <a href="/licenses.txt" target="_blank" rel="noopener noreferrer" className={LINK_CLASS}>
                화면에 포함한 패키지의 라이선스 목록
              </a>
            </li>
          </ul>
        </div>
      </PageSection>
    </SettingsLayout>
  )
}
