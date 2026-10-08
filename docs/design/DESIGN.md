# 디자인

이 문서는 이 제품의 시각 체계를 적은 것으로, 화면을 만들거나 고칠 때마다 읽는다. 함께 보는 내용은 다음 문서에 있다.

- 대상과 말투: [PRODUCT.md](../PRODUCT.md)
- 모든 화면에 적용하는 규칙: [RULES.md](RULES.md)
- 만들기 전과 만든 뒤의 점검: [REVIEW.md](REVIEW.md)
- 시각 체계를 정하는 방법, 참고 화면을 찾는 방법, 트렌드를 검토하는 방법: [DIRECTION.md](DIRECTION.md)

화면 목록이나 컴포넌트 API는 코드가 기준이므로 여기에 옮기지 않는다. 개요부터 해야 할 것과 하지 말 것까지의 절 순서는 AI 도구가 읽는 디자인 시스템 설명 형식([Google DESIGN.md](https://github.com/google-labs-code/design.md))을 따른다. 그 형식은 토큰 값을 파일 머리에 두지만, 이 문서는 값을 코드의 토큰 파일에 두고 토큰 이름만 쓴다.

## AI가 만드는 범위

디자이너나 퍼블리셔에게 받은 디자인이 있으면 그 범위의 화면은 받은 디자인대로 옮긴다. 반면 이 문서와 규칙 파일은 AI가 만드는 범위의 화면에 쓴다.

아직 정하지 않았다. 받은 디자인이 있는지 확인한다. 저장소에는 Figma 링크, 퍼블리싱 결과물, 브랜드 가이드가 없다.

- AI가 만드는 범위: 아직 정하지 않았다. 받은 디자인이 없으면 모든 화면을 AI가 만든다.

## 개요

이 제품의 시각 체계를 요약하고, 화면을 검사할 도구와 승인 기록을 함께 적는다.

- 스타일: 운영 도구의 촘촘한 플랫 디자인. 영역은 카드 대신 제목, 여백, 1px 경계선으로 나누고, 색은 상태와 되돌릴 수 없는 동작에만 쓴다. Light가 기본이다.
- 원하는 인상: 차분하고 촘촘하며, 상태가 먼저 읽힌다.
- 참고 화면: Headless Chromium으로 아래 공개 페이지를 직접 열어 확인했다. 로그인이 필요한 화면(Linear의 Issue 목록·Views·Settings, Vercel의 Project Dashboard·Deployment 목록, Stripe의 Dashboard·Settings·결제 상세, Cloudflare의 DNS Records 표와 Filter UI)은 보지 못했고 추측으로 채우지 않았다. Stripe의 설정 UX와 Cloudflare의 운영형 표는 각 회사의 디자인 시스템으로 만든 문서 사이트에서 관찰한 범위다.
  - **Linear**(`linear.app`, `linear.app/plan`): Sidebar 글자는 본문보다 흐리고 선택 항목만 살짝 밝은 배경을 얻는다. 굵은 색 막대를 쓰지 않는다. 영역은 1px 경계선 하나로 나누고 Card와 shadow가 없다. 상태는 작은 원형 glyph와 글자다(`In Progress`, 초록 점과 글자의 `On Track`). 같은 크기에서도 보조 글자의 밝기로 위계를 만든다. 줄 간격을 줄이는 대신 요소 사이 경계선을 지워 밀도가 높아도 답답하지 않다.
  - **Vercel**(`vercel.com/docs/deployments`, `vercel.com/changelog`): Nav는 한 덩어리로 흐르고 선택 항목만 연한 회색 배경에 진한 글자로 보인다. Page Header는 큰 제목, 짧은 설명, 동작 버튼 순서다. 버튼은 두 단계뿐이고 Primary는 채도 높은 색이 아니라 거의 검정이다. Tab은 밑줄 하나로 표시한다. 보조 정보는 본문보다 작고 흐린 오른쪽 rail로 뺀다.
  - **Stripe**(`docs.stripe.com`): 묶음을 Card로 만들지 않고 제목과 여백만으로 나눈다. 한 화면의 채워진 버튼은 하나이고 옆 동작은 밑줄 친 글자다. 목록형 선택 UI는 연한 배경 위에 글자만 있고 항목마다 테두리가 없다.
  - **Cloudflare**(`developers.cloudflare.com/fundamentals/account/`, `developers.cloudflare.com/dns/manage-dns-records/how-to/create-dns-records/`): 긴 목록에는 검색 입력칸을 단다(`/` 단축키 표시). 제목 아래에 작고 흐린 metadata 줄을 두고 얇은 선으로 본문과 나눈다. 강조색은 Primary 버튼과 현재 위치 표시에만 쓴다. 보조 동작은 작은 outline 버튼이다. 상태 표시는 작은 pill이고 행 전체를 물들이지 않는다. 같은 목적의 다른 경로(Dashboard, API)는 tab으로 나눈다.
- 화면 검사 도구: 아직 정하지 않았다. [DIRECTION.md의 시각 체계 정하기](DIRECTION.md#시각-체계-정하기)대로 흔한 기본값, 접근성, 성능을 검사할 도구와 명령을 정한다.
- 승인: 사람 확인 전이다.

## 토큰

토큰은 다음 파일에 있다.

- 토큰 파일: `frontend/src/index.css`. 화면에서 색 값을 직접 쓰지 않는다. dark 토큰도 있어 `prefers-color-scheme`나 `data-theme`로 바뀐다.

## 색

색은 다음 토큰으로 쓴다.

- 주조색과 강조색: `--primary`는 거의 검정이다. 파랑이나 보라를 강조색으로 쓰지 않는다. 색을 쓰는 곳은 상태(`--status-ok`, `--status-critical`, `--status-warning`, `--status-neutral`)와 되돌릴 수 없는 동작(`--destructive`) 두 곳뿐이다.
- 역할
  - 배경: `--background`, `--surface`, `--surface-subtle`, `--sidebar`
  - 글자: `--foreground`, `--muted-foreground`, `--subtle-foreground`
  - 경계: `--border`, `--border-strong`
  - 동작: `--primary`, `--primary-foreground`, `--accent`, `--destructive`, `--ring`
- 정보 우선순위
  1. 현재 장애 상태. 화면에서 강조색을 쓰는 곳은 상태여야 한다. brand 색이 상태보다 눈에 띄면 안 된다.
  2. 되돌릴 수 없는 동작(삭제). 강조색을 쓰는 두 번째이자 마지막 자리다.
  3. 그 밖의 정보(설정 값, 알림 이력, 요약)의 순서는 아직 정하지 않았다. 목록 화면과 상세 화면마다 무엇을 먼저 보여 줄지 정해야 한다.

## 글자

글꼴과 글자 크기는 다음처럼 정한다.

- 글꼴: `Pretendard`를 먼저 쓰고 시스템 글꼴과 `Malgun Gothic`으로 대신한다. 한국어는 어절 단위로만 줄을 바꾼다(`word-break: keep-all`). 표의 숫자와 IP는 `.tabular`로 자리수를 맞춘다.
- 크기와 굵기: 아직 정하지 않았다. 크기 단계와 굵기를 토큰으로 정한다.

## 레이아웃과 간격

화면 배치와 간격, 기준 폭은 다음과 같다.

- 배치: 폭 224px로 고정한 왼쪽 Sidebar가 화면 높이를 따라 붙어 있다. Sidebar는 본문보다 어둡지 않고 흐리며 선택 항목만 또렷하다. 본문은 `PageHeader`의 최대 폭 1232px 안에 두어 화면이 넓어도 표가 지나치게 늘어나지 않는다.
- 간격 단계: Tailwind 기본 간격을 쓴다. 간격 단계는 토큰으로 정하지 않았다.
- 기준 폭: 아직 정하지 않았다. 지금은 breakpoint가 없다. 좁은 화면에서 Sidebar를 숨길지, 표를 어떻게 줄일지 정해야 한다.

## 깊이

화면 요소의 깊이는 다음처럼 표현한다.

- 엘리베이션: 영역은 제목과 여백으로 나누고, 표와 목록에만 테두리를 쓴다. shadow는 Dialog에만 쓴다.

## 모양

화면 요소의 모양은 다음을 따른다.

- 모서리: `--radius` 하나(6px)로 통일한다.

## 컴포넌트

컴포넌트와 아이콘은 다음에서 가져온다.

- 컴포넌트 위치: 공통 컴포넌트는 `frontend/src/components/ui/`, 화면 공통 요소는 `frontend/src/components/`
- 아이콘: `lucide-react`

여러 화면에서 반복되는 패턴과 그 규칙을 적는다.

| 패턴 | 컴포넌트 | 규칙 |
|---|---|---|
| 상태 표시 | `StatusDot` | 작은 점과 한국어 문구(정상, 위험, 알 수 없음, 사용 안 함)로 쓰고, 위험만 글자를 상태 색과 굵기로 강조한다 |
| 표 로딩 | `TableSkeleton` | 화면 전체를 spinner로 막지 않고 표가 들어올 자리를 그대로 남긴다 |
| 오류 | `ErrorState` | 무엇이 문제인지 한 줄로 쓰고, 다시 시도할 수 있으면 **다시 시도** 버튼을 둔다 |
| 빈 상태 | `EmptyState` | 제목, 설명, 필요하면 다음 동작 버튼을 점선 테두리 안에 둔다 |
| 삭제 확인 | `ConfirmDialog` | 설명에 무엇이 사라지는지 구체적으로 쓴다 |
| 영역 나누기 | `Section` | Card가 아니라 제목과 여백으로 나눈다 |
| 만들지 않은 기능 | `NotBuiltYet` | 점선 테두리 안에 아직 없다고 쓴다 |
| 주 동작 | Primary 버튼 | 채워진 Primary 버튼은 화면마다 하나만 Page Header 오른쪽에 두고, 나머지 동작은 secondary 버튼이나 글자 링크로 둔다 |
| 같은 목적의 다른 경로 | tab | Cloudflare 문서의 Dashboard·API tab처럼 tab으로 나눈다 |
| 표 | 없음 | 행 높이를 줄이고 경계선을 얇게 써서 촘촘하게 만든다 |

## 움직임

움직임을 쓰는 곳과 그 길이, 곡선은 다음과 같다.

- 쓰는 곳: 아직 정하지 않았다. 움직임을 쓸 곳과 길이를 정한다.
- 길이와 곡선: 아직 정하지 않았다.

## 배경

화면 배경은 다음처럼 표현한다.

- 배경 표현: 단색 면. 배경은 아주 연한 회색(`--background`), 본문 표면은 흰색(`--surface`)이다.

## 해야 할 것과 하지 말 것

이 제품에서 나오기 쉬운 흔한 기본값과 그 대신 쓸 것은 다음과 같다.

- 상태는 작은 점과 한국어 문구로 표시한다. 배경을 칠한 pill을 쓰지 않고 행 전체를 물들이지 않는다.
- Card를 기본 단위로 쓰지 않는다. 영역은 제목과 여백으로 나눈다.
- 색만으로 상태를 구분하지 않는다. 상태 점 옆에 항상 한국어 문구를 함께 쓴다.
- `:focus-visible`에는 `--ring` 색 2px outline을 쓴다.
- 클릭·터치 대상은 24×24px 이상으로 만든다.
- 다음 요소는 쓰지 않는다.

| 요소 | 본 곳 | 쓰지 않는 이유 |
|---|---|---|
| 어두운 배경 전체 | Linear | 운영자가 밝은 사무실에서 하루 종일 보므로 Light를 기본으로 둔다 |
| 큰 hero typography | Linear, Stripe | 마케팅용이고, 운영 화면은 한 화면에 정보를 더 담아야 한다 |
| 배경 gradient와 blur | Linear, Stripe | 장식이라 상태를 읽는 데 방해가 된다 |
| 채도 높은 brand color | Cloudflare 주황, Stripe 보라 | 강조색은 장애 상태여야 한다 |
| 오른쪽 목차 rail | Vercel, Cloudflare | 문서용이고, Manager 화면은 본문이 짧다 |
| Pagination, Bulk action | 운영 Console 일반 | 지금 데이터 규모에 필요 없다 |
| Command palette | Linear | 지금 화면 수로는 Sidebar로 충분하다 |

### 고정 규칙과 다르게 정한 것

이 프로젝트가 RULES.md, REVIEW.md, DIRECTION.md와 다르게 정한 것을 적는다.

- 없음

## 트렌드 기록

검토한 트렌드: 없음

## 화면을 만드는 순서

화면 하나는 다음 순서로 만든다.

1. 시각 체계가 정해졌는지 확인한다. 개요가 "아직 정하지 않았다."이면 [DIRECTION.md](DIRECTION.md)의 순서로 먼저 정한다.
2. 이 화면이 받은 디자인의 범위인지 [AI가 만드는 범위](#ai가-만드는-범위)에서 확인한다. 받은 디자인의 범위면 그 디자인을 프로젝트의 컴포넌트와 토큰으로 옮긴다.
3. [REVIEW.md](REVIEW.md)의 순서대로 만들기 전 점검, 만들기, 코드 점검, 화면 점검을 한다.
4. 새 패턴이 생기면 컴포넌트 절의 표에 더한다.
