import { Link } from 'react-router'

import { buttonVariants } from '@/components/ui/button'

export function NotFoundPage() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 px-6 text-center">
      <div>
        <p className="text-section font-semibold">페이지를 찾을 수 없습니다</p>
        <p className="mt-1 text-body text-muted-foreground">
          주소가 바뀌었거나 없는 화면입니다. 주소를 확인하거나 대시보드에서 다시 찾아 주세요.
        </p>
      </div>
      <Link to="/" className={buttonVariants({ variant: 'secondary' })}>
        대시보드로 이동
      </Link>
    </div>
  )
}
