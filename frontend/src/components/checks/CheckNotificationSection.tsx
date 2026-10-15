import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'

import type { ContentState } from '@/components/layout/ContentState'
import { FormActions, PageSection } from '@/components/layout/PageSection'
import { Checkbox, CheckboxLabel, Field, Input } from '@/components/ui/input'
import {
  ApiError,
  apiPut,
  checkNotificationsQuery,
  queryKeys,
  telegramDestinationsQuery,
  type CheckNotifications,
  type CheckNotificationsInput,
  type NotificationBinding,
} from '@/lib/api'
import { parseRepeatIntervalMinutes, REPEAT_INTERVAL_ERROR, REPEAT_INTERVAL_HINT } from '@/lib/repeatInterval'

type BindingRow = {
  telegramDestinationId: number
  name: string
  destinationEnabled: boolean
  selected: boolean
  warningEnabled: boolean
  criticalEnabled: boolean
  sendResolved: boolean
}

/**
 * 고른 그룹으로만 알림이 갑니다. 아무 것도 고르지 않으면 알림을 보내지 않습니다.
 * 저장하면 Manager Database가 바뀝니다. alertmanager.yml은 건드리지 않습니다. (CLAUDE.md 3번)
 */
export function CheckNotificationSection({ checkId }: { checkId: number }) {
  const destinations = useQuery(telegramDestinationsQuery)
  const notifications = useQuery(checkNotificationsQuery(checkId))

  const state: ContentState =
    destinations.isPending || notifications.isPending
      ? { kind: 'loading' }
      : destinations.isError || notifications.isError
        ? {
            kind: 'error',
            message: '알림 설정을 불러오지 못했습니다.',
            onRetry: () => {
              if (destinations.isError) {
                void destinations.refetch()
              }
              if (notifications.isError) {
                void notifications.refetch()
              }
            },
          }
        : { kind: 'ready' }

  return (
    <PageSection title="알림" description="고른 Telegram 그룹으로만 장애 알림이 갑니다." state={state}>
      {destinations.data?.length === 0 ? (
        <p className="text-body text-muted-foreground">
          등록된 Telegram 그룹이 없습니다.{' '}
          <Link to="/notifications" className="text-foreground underline underline-offset-4">
            Telegram 그룹 추가
          </Link>
        </p>
      ) : destinations.data && notifications.data ? (
        <NotificationForm
          key={notifications.dataUpdatedAt}
          checkId={checkId}
          notifications={notifications.data}
          destinations={destinations.data.map((destination) => ({
            id: destination.id,
            name: destination.name,
            enabled: destination.enabled,
          }))}
        />
      ) : null}
    </PageSection>
  )
}

function NotificationForm({
  checkId,
  notifications,
  destinations,
}: {
  checkId: number
  notifications: CheckNotifications
  destinations: { id: number; name: string; enabled: boolean }[]
}) {
  const queryClient = useQueryClient()

  const [rows, setRows] = useState<BindingRow[]>(() =>
    destinations.map((destination) => {
      const binding = notifications.bindings.find(
        (item: NotificationBinding) => item.telegramDestinationId === destination.id,
      )

      return {
        telegramDestinationId: destination.id,
        name: destination.name,
        destinationEnabled: destination.enabled,
        selected: Boolean(binding),
        // 지금 Prometheus 규칙에는 critical만 있습니다. 그래서 기본값도 critical입니다.
        warningEnabled: binding?.warningEnabled ?? false,
        criticalEnabled: binding?.criticalEnabled ?? true,
        sendResolved: binding?.sendResolved ?? true,
      }
    }),
  )
  const [repeatInterval, setRepeatInterval] = useState(String(notifications.repeatIntervalMinutes))
  const [repeatIntervalError, setRepeatIntervalError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)

  const selectedRows = rows.filter((row) => row.selected)

  const updateRow = (destinationId: number, patch: Partial<BindingRow>) => {
    setSaved(false)
    setRows((previous) =>
      previous.map((row) => (row.telegramDestinationId === destinationId ? { ...row, ...patch } : row)),
    )
  }

  const mutation = useMutation({
    mutationFn: (input: CheckNotificationsInput) =>
      apiPut<CheckNotifications>(`/api/checks/${checkId}/notifications`, input),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.checkNotifications(checkId) })
      queryClient.invalidateQueries({ queryKey: queryKeys.notificationBindings })
      setSaved(true)
    },
    onError: (error) => {
      if (error instanceof ApiError && error.fieldErrors.repeatIntervalMinutes) {
        setRepeatIntervalError(error.fieldErrors.repeatIntervalMinutes)
      }
    },
  })

  const save = (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    setRepeatIntervalError(null)

    const repeatIntervalMinutes = parseRepeatIntervalMinutes(repeatInterval)
    if (repeatIntervalMinutes === null) {
      setRepeatIntervalError(REPEAT_INTERVAL_ERROR)
      return
    }

    mutation.mutate({
      bindings: selectedRows.map((row) => ({
        telegramDestinationId: row.telegramDestinationId,
        warningEnabled: row.warningEnabled,
        criticalEnabled: row.criticalEnabled,
        sendResolved: row.sendResolved,
        enabled: true,
      })),
      repeatIntervalMinutes,
    })
  }

  return (
    <form className="space-y-3" onSubmit={save} noValidate>
      {selectedRows.length === 0 ? (
        <p className="text-body text-critical">
          받는 그룹이 없습니다. 지금은 장애가 나도 Telegram 알림이 가지 않습니다.
        </p>
      ) : null}

      <div className="overflow-hidden rounded-md border border-border bg-surface">
        {rows.map((row) => (
          <div key={row.telegramDestinationId} className="border-b border-border px-3 py-2.5 last:border-0">
            <CheckboxLabel>
              <Checkbox
                checked={row.selected}
                onChange={(event) =>
                  updateRow(row.telegramDestinationId, { selected: event.target.checked })
                }
              />
              <span className="text-body font-medium">{row.name}</span>
              {row.destinationEnabled ? null : (
                <span className="text-caption text-muted-foreground">그룹이 꺼져 있어 알림이 가지 않습니다</span>
              )}
            </CheckboxLabel>

            {row.selected ? (
              <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-2 pl-5.5">
                <CheckboxLabel className="text-body text-muted-foreground">
                  <Checkbox
                    checked={row.warningEnabled}
                    onChange={(event) =>
                      updateRow(row.telegramDestinationId, { warningEnabled: event.target.checked })
                    }
                  />
                  경고
                </CheckboxLabel>

                <CheckboxLabel className="text-body text-muted-foreground">
                  <Checkbox
                    checked={row.criticalEnabled}
                    onChange={(event) =>
                      updateRow(row.telegramDestinationId, { criticalEnabled: event.target.checked })
                    }
                  />
                  위험
                </CheckboxLabel>

                <CheckboxLabel className="text-body text-muted-foreground">
                  <Checkbox
                    checked={row.sendResolved}
                    onChange={(event) =>
                      updateRow(row.telegramDestinationId, { sendResolved: event.target.checked })
                    }
                  />
                  복구 알림 보내기
                </CheckboxLabel>
              </div>
            ) : null}
          </div>
        ))}
      </div>

      <Field
        label="재발송 주기"
        htmlFor="check-repeat-interval"
        hint={REPEAT_INTERVAL_HINT}
        error={repeatIntervalError ?? undefined}
      >
        <div className="flex items-center gap-2">
          <Input
            id="check-repeat-interval"
            name="repeatIntervalMinutes"
            inputMode="numeric"
            autoComplete="off"
            className="w-24"
            value={repeatInterval}
            onChange={(event) => {
              setSaved(false)
              setRepeatInterval(event.target.value)
            }}
            aria-invalid={Boolean(repeatIntervalError)}
          />
          <span className="text-body text-muted-foreground">분</span>
        </div>
      </Field>

      <p className="text-caption text-muted-foreground">
        감시 항목의 장애는 지금 위험 단계만 있습니다.
      </p>

      <FormActions
        submitLabel="저장"
        pendingLabel="저장 중…"
        pending={mutation.isPending}
        result={
          mutation.isError && !repeatIntervalError
            ? { kind: 'error', text: '저장하지 못했습니다. 잠시 후 다시 시도해 주세요.' }
            : saved
              ? { kind: 'success', text: '알림 설정을 저장했습니다.' }
              : null
        }
      />
    </form>
  )
}
