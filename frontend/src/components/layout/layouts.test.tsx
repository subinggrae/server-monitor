import { fireEvent, render, screen, within } from '@testing-library/react'
import { useState } from 'react'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'

import { DetailPage } from '@/components/layout/DetailPage'
import { FormDialog } from '@/components/layout/FormDialog'
import { ListPage } from '@/components/layout/ListPage'
import { PageSection } from '@/components/layout/PageSection'

describe('목록 틀', () => {
  it('빈 상태에 머리의 주요 동작을 같은 문구로 한 번 더 둔다', () => {
    const onAdd = vi.fn()
    render(
      <ListPage
        title="서버"
        primaryAction={{ label: '서버 추가', onClick: onAdd }}
        state={{ kind: 'empty', title: '등록한 서버가 없습니다.', description: '서버를 추가해 주세요.' }}
      >
        <table />
      </ListPage>,
    )

    const buttons = screen.getAllByRole('button', { name: '서버 추가' })
    expect(buttons).toHaveLength(2)
    fireEvent.click(buttons[1])
    expect(onAdd).toHaveBeenCalledTimes(1)
  })

  it('알림 띠는 머리와 표 사이에 둔다', () => {
    render(
      <ListPage title="서버" notice={<p>임계값이 없는 리소스는 알리지 않습니다.</p>} state={{ kind: 'ready' }}>
        <table aria-label="서버 표" />
      </ListPage>,
    )

    const notice = screen.getByText('임계값이 없는 리소스는 알리지 않습니다.')
    const table = screen.getByRole('table', { name: '서버 표' })
    expect(notice.compareDocumentPosition(table) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

describe('상세 틀', () => {
  it('불러오는 동안에도 목록으로 가는 링크를 남긴다', () => {
    render(
      <MemoryRouter>
        <DetailPage back={{ to: '/infrastructure', label: '서버 목록' }} state={{ kind: 'loading' }}>
          <p>본문</p>
        </DetailPage>
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: '서버 목록' })).toBeTruthy()
    expect(screen.queryByText('본문')).toBeNull()
  })

  it('불러오지 못하면 머리를 남기고 다시 시도를 보인다', () => {
    const onRetry = vi.fn()
    render(
      <MemoryRouter>
        <DetailPage
          back={{ to: '/infrastructure', label: '서버 목록' }}
          state={{ kind: 'error', message: '서버를 불러오지 못했습니다.', onRetry }}
        >
          <p>본문</p>
        </DetailPage>
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: '서버 목록' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})

describe('절', () => {
  it('불러오는 동안 절을 숨기지 않고 제목과 자리를 남긴다', () => {
    render(
      <PageSection title="알림" state={{ kind: 'loading' }}>
        <p>본문</p>
      </PageSection>,
    )

    expect(screen.getByRole('heading', { name: '알림' })).toBeTruthy()
    expect(screen.getByRole('status')).toBeTruthy()
  })

  it('불러오지 못하면 문구와 다시 시도를 보인다', () => {
    const onRetry = vi.fn()
    render(
      <PageSection title="알림" state={{ kind: 'error', message: '알림 설정을 불러오지 못했습니다.', onRetry }}>
        <p>본문</p>
      </PageSection>,
    )

    expect(screen.getByText('알림 설정을 불러오지 못했습니다.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(onRetry).toHaveBeenCalledTimes(1)
  })
})

describe('대화상자 폼', () => {
  it('아래 막대에 닫기를 실행 버튼보다 앞에 두고, 폼 오류는 아래 막대 바로 위에 둔다', () => {
    render(
      <FormDialog
        open
        onOpenChange={() => {}}
        title="서버 추가"
        submitLabel="저장"
        pendingLabel="저장 중…"
        pending={false}
        formError="저장하지 못했습니다."
        onSubmit={() => {}}
      >
        <input aria-label="이름" />
      </FormDialog>,
    )

    const dialog = screen.getByRole('dialog', { name: '서버 추가' })
    const buttons = within(dialog)
      .getAllByRole('button')
      .map((button) => button.textContent)
      .filter((text) => text === '닫기' || text === '저장')
    expect(buttons).toEqual(['닫기', '저장'])

    const error = within(dialog).getByText('저장하지 못했습니다.')
    const submit = within(dialog).getByRole('button', { name: '저장' })
    expect(error.compareDocumentPosition(submit) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('제출이 끝나고 오류가 있으면 첫 오류 칸으로 포커스를 옮긴다', () => {
    const { rerender } = render(
      <FormDialog
        open
        onOpenChange={() => {}}
        title="서버 추가"
        submitLabel="저장"
        pendingLabel="저장 중…"
        pending
        onSubmit={() => {}}
      >
        <input aria-label="이름" />
        <input aria-label="호스트" />
      </FormDialog>,
    )

    rerender(
      <FormDialog
        open
        onOpenChange={() => {}}
        title="서버 추가"
        submitLabel="저장"
        pendingLabel="저장 중…"
        pending={false}
        onSubmit={() => {}}
      >
        <input aria-label="이름" />
        <input aria-label="호스트" aria-invalid />
      </FormDialog>,
    )

    expect(document.activeElement).toBe(screen.getByLabelText('호스트'))
  })

  it('요청을 보내기 전에 막은 오류도 첫 오류 칸으로 포커스를 옮긴다', () => {
    function ClientValidated() {
      const [invalid, setInvalid] = useState(false)
      return (
        <FormDialog
          open
          onOpenChange={() => {}}
          title="서버 추가"
          submitLabel="추가"
          pendingLabel="추가 중…"
          pending={false}
          onSubmit={() => setInvalid(true)}
        >
          <input aria-label="이름" />
          <input aria-label="수집 주기" aria-invalid={invalid} />
        </FormDialog>
      )
    }
    render(<ClientValidated />)

    fireEvent.click(screen.getByRole('button', { name: '추가' }))

    expect(document.activeElement).toBe(screen.getByLabelText('수집 주기'))
  })
})
