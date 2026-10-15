import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'

import { ConfirmDialog } from '@/components/ConfirmDialog'
import { queryState } from '@/components/layout/state'
import { ListPage } from '@/components/layout/ListPage'
import { PageSection } from '@/components/layout/PageSection'
import { TelegramDestinationDialog } from '@/components/notifications/TelegramDestinationDialog'
import { Button } from '@/components/ui/button'
import { Table, TableFrame, TBody, TD, TH, THead, TR } from '@/components/ui/table'
import {
  apiDelete,
  apiPost,
  notificationBindingsQuery,
  queryKeys,
  telegramDestinationsQuery,
  telegramStatusQuery,
  type TelegramDestination,
  type TelegramStatus,
  type TestMessageResult,
} from '@/lib/api'

/** Check별 알림 설정은 Check 상세에서 합니다. 같은 설정을 두 화면에 두지 않습니다. */
export function NotificationsPage() {
  const destinations = useQuery(telegramDestinationsQuery)
  const { data: telegramStatus } = useQuery(telegramStatusQuery)
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<TelegramDestination | undefined>()

  const openNew = () => {
    setEditing(undefined)
    setFormOpen(true)
  }

  return (
    <>
      <ListPage
        title="Telegram 그룹"
        description="장애 알림을 받을 Telegram 그룹을 관리합니다. Bot Token은 Monitoring Manager가 관리하므로 입력하지 않습니다."
        primaryAction={{ label: '그룹 추가', onClick: openNew }}
        notice={telegramStatus ? <TelegramSetupNotice status={telegramStatus} /> : undefined}
        state={queryState(
          destinations,
          destinations.error instanceof Error
            ? destinations.error.message
            : 'Telegram 그룹을 불러오지 못했습니다.',
          destinations.data?.length === 0
            ? {
                title: '등록된 Telegram 그룹이 없습니다.',
                description:
                  '그룹을 추가해야 장애 알림을 받을 수 있습니다. 추가한 뒤 감시 항목 상세에서 받는 그룹으로 골라 주세요.',
              }
            : null,
        )}
        skeletonColumns={5}
        aside={<BindingOverview />}
      >
        <DestinationTable
          destinations={destinations.data ?? []}
          onEdit={(destination) => {
            setEditing(destination)
            setFormOpen(true)
          }}
        />
      </ListPage>

      <TelegramDestinationDialog open={formOpen} onOpenChange={setFormOpen} destination={editing} />
    </>
  )
}

/** /register가 동작하지 않는 환경이라면 그 이유를 먼저 보여 줘야 합니다. */
function TelegramSetupNotice({ status }: { status: TelegramStatus }) {
  if (!status.configured) {
    return (
      <div className="rounded-md border border-destructive/40 bg-destructive/5 px-3 py-2.5 text-body">
        <p className="font-medium text-destructive">Telegram Bot Token이 설정되지 않았습니다.</p>
        <p className="mt-0.5 text-muted-foreground">
          그룹을 등록해도 메시지를 보낼 수 없습니다. Monitoring Manager의 Bot Token 설정을 확인해 주세요.
        </p>
        <p className="mt-0.5 text-caption text-muted-foreground">
          Docker Secret이나 환경 변수 TELEGRAM_BOT_TOKEN으로 정합니다.
        </p>
      </div>
    )
  }

  return (
    <p className="text-body text-muted-foreground">
      {status.webhookConfigured
        ? 'Telegram 그룹에 Bot을 추가하고 /register를 보내면 아래 목록에 나타납니다. Chat ID를 직접 입력해도 됩니다.'
        : status.publicUrlConfigured
          ? 'Telegram Webhook이 아직 등록되지 않아 /register를 쓸 수 없습니다. Monitoring Manager 로그에서 등록하지 못한 이유를 확인해 주세요. 그때까지는 Chat ID를 직접 등록합니다.'
          : 'Telegram Webhook이 설정되지 않아 /register를 쓸 수 없습니다. Chat ID를 직접 등록해 주세요.'}
    </p>
  )
}

function DestinationTable({
  destinations,
  onEdit,
}: {
  destinations: TelegramDestination[]
  onEdit: (destination: TelegramDestination) => void
}) {
  const queryClient = useQueryClient()
  const [deleting, setDeleting] = useState<TelegramDestination | undefined>()
  const [testResult, setTestResult] = useState<{ id: number; result: TestMessageResult } | null>(null)

  const testMutation = useMutation({
    mutationFn: (destinationId: number) =>
      apiPost<TestMessageResult>(`/api/telegram/destinations/${destinationId}/test`, {}),
    onSuccess: (result, destinationId) => setTestResult({ id: destinationId, result }),
    onError: (_error, destinationId) =>
      setTestResult({
        id: destinationId,
        result: { success: false, message: 'Telegram 메시지를 보내지 못했습니다. 잠시 후 다시 시도해 주세요.' },
      }),
  })

  const deleteMutation = useMutation({
    mutationFn: (destinationId: number) => apiDelete(`/api/telegram/destinations/${destinationId}`),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.telegramDestinations })
      queryClient.invalidateQueries({ queryKey: queryKeys.notificationBindings })
      setDeleting(undefined)
    },
  })

  return (
    <>
      <TableFrame>
        <Table>
          <THead>
            <tr>
              <TH className="w-80">이름</TH>
              <TH className="w-44">Chat ID</TH>
              <TH className="w-20">토픽 ID</TH>
              <TH className="w-24">사용 여부</TH>
              <TH className="text-right">동작</TH>
            </tr>
          </THead>
          <TBody>
            {destinations.map((destination) => (
              <TR key={destination.id}>
                <TD>
                  <span className="font-medium">{destination.name}</span>
                  {testResult?.id === destination.id ? (
                    <p
                      className={
                        testResult.result.success
                          ? 'mt-0.5 text-caption text-muted-foreground'
                          : 'mt-0.5 text-caption text-destructive'
                      }
                    >
                      {testResult.result.message}
                    </p>
                  ) : null}
                </TD>
                <TD className="tabular text-muted-foreground">{destination.chatId}</TD>
                <TD className="tabular text-muted-foreground">{destination.messageThreadId ?? '-'}</TD>
                <TD className="text-muted-foreground">{destination.enabled ? '사용' : '사용 안 함'}</TD>
                <TD>
                  <div className="flex justify-end gap-0.5">
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={testMutation.isPending}
                      onClick={() => testMutation.mutate(destination.id)}
                    >
                      테스트 메시지 보내기
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => onEdit(destination)}>
                      수정
                    </Button>
                    <Button
                      size="sm"
                      variant="destructive-ghost"
                      onClick={() => setDeleting(destination)}
                    >
                      삭제
                    </Button>
                  </div>
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </TableFrame>

      <ConfirmDialog
        open={Boolean(deleting)}
        onOpenChange={(open) => (open ? undefined : setDeleting(undefined))}
        title="이 Telegram 그룹을 삭제할까요?"
        description={`${deleting?.name ?? ''} 그룹으로는 더 이상 알림이 가지 않습니다.\n이 그룹을 받는 그룹으로 고른 감시 항목의 알림 설정도 함께 사라집니다.`}
        pending={deleteMutation.isPending}
        errorMessage={deleteMutation.isError ? '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.' : undefined}
        onConfirm={() => (deleting ? deleteMutation.mutate(deleting.id) : undefined)}
      />
    </>
  )
}

/** 수정은 Check 상세에서 합니다. 여기는 알림 대상이 비어 있는 Check를 찾는 용도입니다. */
function BindingOverview() {
  const bindings = useQuery(notificationBindingsQuery)
  const summaries = bindings.data ?? []
  const missing = summaries.filter((summary) => summary.bindings.length === 0).length

  return (
    <PageSection
      title="감시 항목별 받는 그룹"
      description={
        summaries.length === 0
          ? undefined
          : missing > 0
            ? `받는 그룹이 없는 감시 항목이 ${missing}개 있습니다. 이 감시 항목은 장애가 나도 알림이 가지 않습니다.`
            : '모든 감시 항목에 받는 그룹이 있습니다.'
      }
      state={queryState(
        bindings,
        '감시 항목별 받는 그룹을 불러오지 못했습니다.',
        bindings.data?.length === 0 ? { title: '등록된 감시 항목이 없습니다.' } : null,
      )}
    >
      <TableFrame>
        <Table>
          <THead>
            <tr>
              <TH className="w-96">감시 항목</TH>
              <TH className="w-32">환경</TH>
              <TH>받는 그룹</TH>
            </tr>
          </THead>
          <TBody>
            {summaries.map((summary) => (
              <TR key={summary.checkId}>
                <TD>
                  <Link
                    to={`/monitoring/checks/${summary.checkId}`}
                    className="font-medium hover:underline"
                  >
                    {summary.checkName}
                  </Link>
                </TD>
                <TD className="text-muted-foreground">{summary.environment}</TD>
                <TD>
                  {summary.bindings.length === 0 ? (
                    <span className="text-critical">없음</span>
                  ) : (
                    <span className="text-muted-foreground">
                      {summary.bindings.map((binding) => binding.telegramDestinationName).join(', ')}
                    </span>
                  )}
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      </TableFrame>
    </PageSection>
  )
}
