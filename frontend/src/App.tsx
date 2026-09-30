import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router'

import { AppLayout } from '@/components/AppLayout'
import { AlertListPage } from '@/pages/AlertListPage'
import { CheckDetailPage } from '@/pages/CheckDetailPage'
import { CheckListPage } from '@/pages/CheckListPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { NotificationsPage } from '@/pages/NotificationsPage'
import { ServerDetailPage } from '@/pages/ServerDetailPage'
import { ServerListPage } from '@/pages/ServerListPage'
import { SettingsPage } from '@/pages/SettingsPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // 운영 화면이므로 화면을 다시 볼 때마다 최신 상태를 가져옵니다.
      staleTime: 10_000,
      retry: 1,
    },
  },
})

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route element={<AppLayout />}>
            <Route index element={<DashboardPage />} />

            <Route path="monitoring" element={<CheckListPage />} />
            <Route path="monitoring/checks/:checkId" element={<CheckDetailPage />} />

            <Route path="infrastructure" element={<ServerListPage />} />
            <Route path="infrastructure/servers" element={<Navigate to="/infrastructure" replace />} />
            <Route path="infrastructure/servers/:serverId" element={<ServerDetailPage />} />

            <Route path="alerts" element={<AlertListPage />} />
            <Route path="notifications" element={<NotificationsPage />} />
            <Route path="settings" element={<SettingsPage />} />
          </Route>
          <Route path="*" element={<NotFoundPage />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  )
}
