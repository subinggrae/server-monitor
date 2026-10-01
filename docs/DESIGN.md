# 디자인

화면을 만들 때 지키는 제품 목적, 사용자 목표, 시각 원칙을 정한다. 새 화면을 만들거나 기존 화면의 구조를 바꾸기 전에 읽는다. 화면 목록이나 컴포넌트 API는 코드가 기준이므로 여기에 옮기지 않는다.

## 제품과 사용자

- 제품 목적: 운영자가 YAML이나 PromQL을 직접 고치지 않고 가용성 Check, 서버 SNMP Resource, Telegram 알림을 화면에서 설정하고 현재 상태를 본다.
- 주요 사용자: 밝은 사무실에서 하루 종일 화면을 보는 운영자
- 사용자 목표: 지금 장애가 난 대상을 알아보고, 모니터링 대상과 알림 대상을 등록·수정한다. 상세 시계열 분석은 Grafana에서 하고 Manager 화면에서는 하지 않는다.

## 정보 우선순위

1. 현재 장애 상태. 화면에서 강조색을 쓰는 곳은 상태여야 한다. brand 색이 상태보다 눈에 띄면 안 된다.
2. 되돌릴 수 없는 동작(삭제). 강조색을 쓰는 두 번째이자 마지막 자리다.

그 밖의 정보(설정 값, 알림 이력, 요약)의 순서는 아직 정하지 않았다. 목록 화면과 상세 화면마다 무엇을 먼저 보여 줄지 정해야 한다.

## UX 원칙

- **한 화면의 Primary는 하나**: 채워진 Primary 버튼은 화면마다 하나이고 위치는 Page Header 오른쪽이다. 나머지 동작은 secondary 버튼이나 글자 링크로 둔다.
- **상태는 주인공이 아니다**: 상태는 작은 점과 한국어 문구로 표시한다. 배경을 칠한 pill을 쓰지 않고 행 전체를 물들이지 않는다.
- **동작하지 않는 입력은 받지 않는다**: 시스템이 실제로 반영할 수 없는 값은 화면에서 입력받지 않는다. 예: Blackbox가 Check마다 다른 응답 코드와 timeout을 받지 못해 Expected Status와 Timeout 입력을 뺐다([ADR 0004](decisions/0004-fixed-blackbox-modules.md)).
- **없는 기능을 있는 것처럼 꾸미지 않는다**: 아직 만들지 않은 기능 자리는 `NotBuiltYet`으로 그렇다고 표시한다.
- **같은 목적의 다른 경로는 tab으로 나눈다**: Cloudflare 문서의 Dashboard·API tab처럼, 같은 일을 하는 다른 방법은 tab으로 가른다.

## 시각 방향

- 인상: 차분함, 촘촘함, 상태가 먼저 읽힘
- 기본 원칙
  - Light가 기본이다. 배경은 아주 연한 회색, 본문 표면은 흰색이다. `index.css`에는 dark token도 있어 `prefers-color-scheme`나 `data-theme`로 바뀐다.
  - Sidebar는 본문보다 어둡지 않고 흐리다. 선택 항목만 또렷하다.
  - Card를 기본 단위로 쓰지 않는다. 영역은 제목과 여백으로 나누고, 표와 목록에만 테두리를 쓴다.
  - Primary 색은 거의 검정이다. 파랑이나 보라를 강조색으로 쓰지 않는다.
  - 색을 쓰는 곳은 상태(정상, 위험, 알 수 없음)와 되돌릴 수 없는 동작(삭제) 두 곳뿐이다.
  - 표는 촘촘하게 만든다. 행 높이를 줄이고 경계선을 얇게 쓴다.
  - radius는 6px 하나로 통일한다.
  - shadow는 Dialog에만 쓴다.

### 참고한 제품

Headless Chromium으로 아래 공개 페이지를 직접 열어 확인했다. 캡처는 저장소에 넣지 않았다. 로그인이 필요한 화면(Linear의 Issue 목록·Views·Settings, Vercel의 Project Dashboard·Deployment 목록, Stripe의 Dashboard·Settings·결제 상세, Cloudflare의 DNS Records 표와 Filter UI)은 보지 못했고 추측으로 채우지 않았다. Stripe의 설정 UX와 Cloudflare의 운영형 표는 각 회사의 디자인 시스템으로 만든 docs 사이트에서 관찰한 범위다.

| 제품 | 확인한 주소 | 가져온 원칙 |
|---|---|---|
| Linear | `linear.app`, `linear.app/plan` | Sidebar 글자는 본문보다 흐리고 선택 항목만 살짝 밝은 배경을 얻는다. 굵은 색 막대를 쓰지 않는다. 영역은 1px 경계선 하나로 나누고 Card와 shadow가 없다. 상태는 작은 원형 glyph와 글자다(`In Progress`, 초록 점과 글자의 `On Track`). 같은 크기에서도 secondary text의 밝기로 위계를 만든다. 줄 간격을 줄이는 대신 요소 사이 경계선을 지워 밀도가 높아도 답답하지 않다 |
| Vercel | `vercel.com/docs/deployments`, `vercel.com/changelog` | Nav는 한 덩어리로 흐르고 선택 항목만 연한 회색 배경과 진한 글자를 가진다. Page Header는 큰 제목, 짧은 설명, 동작 버튼 순서다. 버튼은 두 단계뿐이고 Primary는 채도 높은 색이 아니라 거의 검정이다. Tab은 밑줄 하나로 표시한다. 보조 정보는 본문보다 작고 흐린 오른쪽 rail로 뺀다 |
| Stripe | `docs.stripe.com` | 묶음을 Card로 만들지 않고 제목과 여백만으로 나눈다. 한 화면의 채워진 버튼은 하나이고 옆 동작은 밑줄 친 글자다. 목록형 선택 UI는 연한 배경 위에 글자만 있고 항목마다 테두리가 없다 |
| Cloudflare | `developers.cloudflare.com/fundamentals/account/`, `developers.cloudflare.com/dns/manage-dns-records/how-to/create-dns-records/` | 긴 목록에는 검색 입력칸을 단다(`/` 단축키 표시). 제목 아래에 작고 흐린 metadata 줄을 두고 얇은 선으로 본문과 나눈다. 강조색은 Primary 버튼과 현재 위치 표시에만 쓴다. 보조 동작은 작은 outline 버튼이다. 상태 표시는 작은 pill이고 행 전체를 물들이지 않는다. 같은 목적의 다른 경로(Dashboard, API)는 tab으로 나눈다 |

### 쓰지 않는 요소

| 요소 | 본 곳 | 쓰지 않는 이유 |
|---|---|---|
| 어두운 배경 전체 | Linear | 운영자가 밝은 사무실에서 하루 종일 본다. Light를 기본으로 둔다 |
| 큰 hero typography | Linear, Stripe | 마케팅용이다. 운영 화면은 한 화면에 정보를 더 담아야 한다 |
| 배경 gradient와 blur | Linear, Stripe | 장식이다. 상태를 읽는 데 방해가 된다 |
| 채도 높은 brand color | Cloudflare 주황, Stripe 보라 | 강조색은 장애 상태여야 한다 |
| 오른쪽 목차 rail | Vercel, Cloudflare | 문서용이다. Manager 화면은 본문이 짧다 |
| Pagination, Bulk action | 운영 Console 일반 | 지금 데이터 규모에 필요 없다 |
| Command palette | Linear | 지금 화면 수로는 Sidebar로 충분하다 |

## 디자인 토큰

- 토큰 파일: `frontend/src/index.css`. 화면에서 hex 값을 직접 쓰지 않는다.
- 색

  ```text
  배경    --background  --surface  --surface-subtle  --sidebar
  글자    --foreground  --muted-foreground  --subtle-foreground
  경계    --border  --border-strong
  상태    --status-ok  --status-critical  --status-warning  --status-neutral
  동작    --primary  --primary-foreground  --accent  --destructive  --ring
  ```

- 글자: `Pretendard`를 먼저 쓰고 시스템 글꼴과 `Malgun Gothic`으로 대신한다. 한국어는 어절 단위로만 줄을 바꾼다(`word-break: keep-all`). 표의 숫자와 IP는 `.tabular`로 자리수를 맞춘다. 크기 단계는 token으로 정하지 않았다. 크기 단계와 굵기를 정해야 한다.
- 간격: Tailwind 기본 간격을 쓴다. 간격 단계는 token으로 정하지 않았다.
- 모서리와 높이: `--radius`는 6px(`0.375rem`)이다. shadow는 Dialog에만 쓴다.

## 레이아웃과 반응형

- Sidebar는 폭 224px로 고정하고 화면 높이를 따라 붙어 있다.
- 본문은 `PageHeader`의 최대 폭 1232px 안에 둔다. 화면이 넓어도 표가 지나치게 늘어나지 않는다.
- 반응형 기준 폭은 아직 정하지 않았다. 지금은 breakpoint가 없다. 좁은 화면에서 Sidebar를 숨길지, 표를 어떻게 줄일지 정해야 한다.

## 접근성

WCAG 2.2 AA를 최소 기준으로 한다.

- 본문 글자 대비 4.5:1 이상, 큰 글자와 UI 요소 3:1 이상
- 키보드로 모든 기능을 쓸 수 있고, 포커스 위치가 항상 보인다. `:focus-visible`에 `--ring` 색 2px outline을 쓴다
- 클릭·터치 대상은 24×24px 이상
- 색만으로 상태를 구분하지 않는다. 상태 점 옆에 항상 한국어 문구를 함께 쓴다
- 움직임 줄이기 설정(`prefers-reduced-motion`)을 따른다

## 반복 UI 패턴

| 패턴 | 규칙 |
|---|---|
| 상태 표시 | `StatusDot`. 작은 점과 한국어 문구(정상, 위험, 알 수 없음, 사용 안 함). 위험만 글자를 상태 색과 굵기로 강조한다 |
| 표 로딩 | `TableSkeleton`. 화면 전체를 spinner로 막지 않고 표가 들어올 자리를 그대로 남긴다 |
| 오류 | `ErrorState`. 무엇이 문제인지 한 줄로 쓰고, 다시 시도할 수 있으면 **다시 시도** 버튼을 둔다 |
| 빈 상태 | `EmptyState`. 제목, 설명, 필요하면 다음 동작 버튼을 점선 테두리 안에 둔다 |
| 삭제 확인 | `ConfirmDialog`. 설명에 무엇이 사라지는지 구체적으로 쓴다 |
| 영역 나누기 | `Section`. Card가 아니라 제목과 여백으로 나눈다 |
| 만들지 않은 기능 | `NotBuiltYet`. 점선 테두리 안에 아직 없다고 쓴다 |

## 화면을 만드는 순서

1. 요구사항에서 이 화면의 사용자 목표를 확인한다.
2. 정보 우선순위에 따라 보여줄 정보와 순서를 정한다.
3. 구조를 정한다. 복잡하면 글로 된 와이어프레임이나 그림으로 먼저 합의한다.
4. 디자인 토큰과 반복 UI 패턴으로 시각 표현을 정한다. 새 패턴이 필요하면 이 문서에 추가한다.
5. 코드로 만든다.
6. 실제 화면을 여러 폭에서 확인하고 접근성 기준을 점검한다.
