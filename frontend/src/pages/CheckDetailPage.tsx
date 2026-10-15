import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useParams } from 'react-router'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { PageBody, PageHeader } from '@/components/PageHeader'
import { ErrorState, LoadingState } from '@/components/QueryStates'
import { StatusDot } from '@/components/StatusDot'
import { CheckFormDialog } from '@/components/checks/CheckFormDialog'
import { CheckNotificationSection } from '@/components/checks/CheckNotificationSection'
import { Button } from '@/components/ui/button'
import { Section, SectionHeader } from '@/components/ui/section'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import { apiDelete, checkDetailQuery, queryKeys } from '@/lib/api'

export function CheckDetailPage() {
  const { checkId: rawCheckId } = useParams()
  const checkId = Number(rawCheckId)
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const checkResult = useQuery(checkDetailQuery(checkId))
  const [formOpen, setFormOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const deleteMutation = useMutation({
    mutationFn: () => apiDelete(`/api/checks/${checkId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.checks })
      navigate('/monitoring')
    },
  })

  if (checkResult.isPending) {
    return (
      <PageBody>
        <LoadingState message="감시 항목을 불러오는 중…" />
      </PageBody>
    )
  }

  if (checkResult.isError) {
    return (
      <PageBody>
        <ErrorState
          message={
            checkResult.error instanceof Error
              ? checkResult.error.message
              : '감시 항목을 불러오지 못했습니다.'
          }
          onRetry={() => checkResult.refetch()}
        />
      </PageBody>
    )
  }

  const check = checkResult.data

  return (
    <>
      <PageHeader
        title={check.name}
        back={{ to: '/monitoring', label: '감시 항목 목록' }}
        meta={
          <>
            <StatusDot status={check.status} />
            <span>{check.typeDisplayName}</span>
            <span>{check.environment}</span>
            <span>{`수집 주기 ${check.scrapeIntervalSeconds}초`}</span>
            {check.enabled ? null : <span>사용 안 함</span>}
          </>
        }
        actions={
          <>
            <Button variant="secondary" onClick={() => setFormOpen(true)}>
              수정
            </Button>
            <Button variant="destructive-ghost" onClick={() => setDeleting(true)}>
              삭제
            </Button>
          </>
        }
      />

      <PageBody>
        {check.description ? (
          <p className="max-w-2xl text-body text-muted-foreground">{check.description}</p>
        ) : null}

        <Section>
          <SectionHeader
            title="확인 대상"
            description={`확인하는 주소 ${check.targetCount}개입니다.`}
            actions={
              <Button variant="secondary" size="sm" onClick={() => setFormOpen(true)}>
                대상 편집
              </Button>
            }
          />

          <TableFrame>
            <Table>
              <THead>
                <tr>
                  <TH>주소</TH>
                  <TH className="w-28">사용 여부</TH>
                  <TH className="w-32">상태</TH>
                </tr>
              </THead>
              <TBody>
                {check.targets.map((target) => (
                  <TR key={target.id}>
                    <TD className="tabular break-all">{target.blackboxTarget}</TD>
                    <TD className="text-muted-foreground">{target.enabled ? '사용' : '사용 안 함'}</TD>
                    <TD>
                      <StatusDot status={target.status} />
                    </TD>
                  </TR>
                ))}
              </TBody>
            </Table>
          </TableFrame>
        </Section>

        <CheckNotificationSection checkId={checkId} />
      </PageBody>

      <CheckFormDialog open={formOpen} onOpenChange={setFormOpen} check={check} />

      <ConfirmDialog
        open={deleting}
        onOpenChange={setDeleting}
        title="이 감시 항목을 삭제할까요?"
        description={`확인 대상 ${check.targetCount}개가 함께 삭제됩니다.\n삭제하면 잠시 뒤부터 이 주소들을 확인하지 않습니다.`}
        pending={deleteMutation.isPending}
        errorMessage={deleteMutation.isError ? '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.' : undefined}
        onConfirm={() => deleteMutation.mutate()}
      />
    </>
  )
}
