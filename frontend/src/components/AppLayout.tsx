import { useQuery } from '@tanstack/react-query'
import { Activity, Bell, BellRing, LayoutDashboard, Server, Settings, type LucideIcon } from 'lucide-react'
import { NavLink, Outlet } from 'react-router'

import { systemInfoQuery } from '@/lib/api'
import { cn } from '@/lib/utils'

type NavItem = {
  to: string
  label: string
  icon: LucideIcon
  end?: boolean
}

type NavSection = {
  title?: string
  items: NavItem[]
}

/** Monitoring과 Infrastructure는 독립된 도메인이라 메뉴도 따로 둡니다. (CLAUDE.md 2-1번) */
const NAV_SECTIONS: NavSection[] = [
  {
    items: [{ to: '/', label: '대시보드', icon: LayoutDashboard, end: true }],
  },
  {
    title: '모니터링',
    items: [
      { to: '/monitoring', label: 'Check', icon: Activity },
      { to: '/alerts', label: 'Alert', icon: BellRing },
    ],
  },
  {
    title: '인프라',
    items: [{ to: '/infrastructure', label: '서버', icon: Server }],
  },
  {
    title: '알림',
    items: [{ to: '/notifications', label: 'Telegram 그룹', icon: Bell }],
  },
  {
    items: [{ to: '/settings', label: '설정', icon: Settings }],
  },
]

export function AppLayout() {
  return (
    <div className="flex min-h-screen bg-background text-foreground">
      <aside className="sticky top-0 flex h-screen w-56 shrink-0 flex-col border-r border-border bg-sidebar">
        <div className="px-4 py-4">
          <p className="text-[13px] font-semibold">Monitoring Manager</p>
        </div>

        <nav className="flex-1 space-y-5 overflow-y-auto px-2 pb-4">
          {NAV_SECTIONS.map((section, index) => (
            <div key={section.title ?? `section-${index}`} className="space-y-0.5">
              {section.title ? (
                <p className="px-2 pb-1 text-[11px] font-medium text-subtle-foreground">
                  {section.title}
                </p>
              ) : null}
              {section.items.map(({ to, label, icon: Icon, end }) => (
                <NavLink
                  key={to}
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    cn(
                      'flex items-center gap-2 rounded-md px-2 py-1.5 text-[13px] transition-colors',
                      isActive
                        ? 'bg-accent font-medium text-foreground'
                        : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground',
                    )
                  }
                >
                  <Icon className="size-4 shrink-0 opacity-70" aria-hidden />
                  {label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>

        <BackendStatus />
      </aside>

      <main className="min-w-0 flex-1">
        <Outlet />
      </main>
    </div>
  )
}

function BackendStatus() {
  const { data, isError, isPending } = useQuery(systemInfoQuery)

  return (
    <div className="border-t border-border px-4 py-2.5">
      <span className="flex items-center gap-1.5 text-[11px] text-muted-foreground">
        <span
          className={cn(
            'size-1.5 shrink-0 rounded-full',
            isError ? 'bg-critical' : isPending ? 'bg-neutral' : 'bg-ok',
          )}
          aria-hidden
        />
        {isError ? 'Backend 연결 끊김' : isPending ? '연결 확인 중' : (data?.application ?? '')}
      </span>
    </div>
  )
}
