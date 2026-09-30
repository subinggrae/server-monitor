# 디자인 레퍼런스

Monitoring Manager의 UI를 정리하면서 실제로 확인한 제품과, 거기서 가져온 원칙을 적었습니다.

화면을 새로 만들 때 이 문서의 7번 "최종 Visual Direction"을 기준으로 삼습니다.

---

## 1. 실제 확인한 공개 페이지

Headless Chromium으로 아래 주소를 직접 열어 화면을 캡처하고 눈으로 확인했습니다.
캡처는 작업용 임시 폴더에만 두었고 저장소에는 넣지 않았습니다.

| 제품 | 확인한 주소 | 무엇이 보였는가 |
| --- | --- | --- |
| Linear | `linear.app` | Hero 안에 실제 제품 화면. Sidebar, Issue 상세, 오른쪽 Property 목록 |
| Linear | `linear.app/plan` | Project update의 `On Track` 상태 표현 |
| Vercel | `vercel.com/docs/deployments` | Geist 기반 App Shell. 왼쪽 Nav, 오른쪽 On this page, Tabs, Button 두 단계 |
| Vercel | `vercel.com/changelog` | 시간축 목록과 본문 typography |
| Stripe | `docs.stripe.com` | 상단 Nav, Card 없이 제목만으로 나눈 link group, Primary 1개 + text link |
| Cloudflare | `developers.cloudflare.com/fundamentals/account/` | Sidebar filter, 확장 group, Breadcrumb, 제목 아래 metadata 줄, 오른쪽 On this page |
| Cloudflare | `developers.cloudflare.com/dns/manage-dns-records/how-to/create-dns-records/` | DNS record 설정 절차와 Dashboard / API tab 구분 |

### 보지 못한 것

로그인이 필요한 화면은 확인하지 못했습니다. 추측으로 채우지 않았습니다.

```text
Linear     로그인 후의 실제 Issue 목록 / Views / Settings
Vercel     로그인 후의 Project Dashboard와 Deployment 목록
Stripe     로그인 후의 Dashboard, Settings, 결제 상세
Cloudflare 로그인 후의 DNS Records 표와 Filter UI
```

그래서 Stripe의 Settings UX와 Cloudflare의 운영형 Table은
**로그인 없이 공개된 제품 UI(docs 애플리케이션 자체)에서 관찰한 범위**로만 적었습니다.
두 회사의 docs 사이트는 각 회사의 디자인 시스템으로 만든 실제 애플리케이션입니다.

---

## 2. Linear에서 가져올 원칙

Linear 홈 화면의 제품 캡처에서 관찰한 내용입니다.

* **Sidebar가 본문과 경쟁하지 않습니다.**
  Sidebar 글자는 본문보다 흐립니다. 선택된 항목만 살짝 밝은 배경을 얻습니다.
  선택 표시에 굵은 색 막대나 강한 대비를 쓰지 않습니다.

* **Card를 쓰지 않습니다.**
  Sidebar와 본문, 본문과 오른쪽 Property 영역은 1px 경계선 하나로만 나뉩니다.
  Shadow가 보이지 않습니다.

* **상태는 작은 glyph 하나입니다.**
  `In Progress`는 작은 원형 icon과 글자로 표현합니다. 배경을 칠한 pill이 아닙니다.
  `linear.app/plan`의 `On Track`도 작은 초록 점과 초록 글자입니다.

* **Secondary text를 적극적으로 씁니다.**
  Activity 줄에서 사람 이름과 시각의 밝기가 다릅니다. 같은 크기여도 위계가 보입니다.

* **밀도가 높아도 답답하지 않습니다.**
  줄 간격을 줄이는 대신 요소 사이 경계선을 지웁니다.

---

## 3. Vercel에서 가져올 원칙

`vercel.com/docs`의 App Shell에서 관찰한 내용입니다.

* **Nav는 한 덩어리로 흐르고, 선택 항목만 채워집니다.**
  선택된 `Overview`는 연한 회색 배경과 진한 글자를 가집니다. 나머지는 경계선 없이 나열됩니다.

* **Page Header 구조가 일정합니다.**
  큰 제목 → 짧은 설명 → 동작 버튼 순서입니다.

* **Button은 두 단계뿐입니다.**
  `Deploy from the CLI`는 검은 배경, `Connect Git`은 흰 배경에 얇은 테두리입니다.
  두 개를 나란히 두어도 무엇을 먼저 눌러야 하는지 보입니다.
  Primary는 채도 높은 색이 아니라 **거의 검정**입니다.

* **Tab은 밑줄 하나로 표시합니다.**
  배경을 칠하거나 테두리를 두르지 않습니다.

* **보조 정보는 오른쪽 rail로 뺍니다.**
  `On this page`는 본문보다 작고 흐리며, 현재 위치만 얇은 선으로 표시합니다.

---

## 4. Stripe에서 가져올 원칙

`docs.stripe.com`에서 관찰한 내용입니다.

* **묶음을 Card로 만들지 않습니다.**
  `Payments` / `Revenue` / `Developers` 세 묶음은 제목과 여백만으로 나뉩니다.
  테두리도 배경도 없습니다.

* **한 화면의 Primary는 하나입니다.**
  `Get started with Stripe`만 채워진 버튼이고, 옆의 `Explore all products`는 밑줄 친 글자입니다.

* **목록형 선택 UI는 아주 조용합니다.**
  `Try it out`의 왼쪽 목록은 연한 배경 위에 글자만 있습니다. 항목마다 테두리를 주지 않습니다.

* **설정과 절차는 tab으로 가릅니다.**
  Cloudflare 문서에서 본 `Dashboard` / `API` tab처럼, 같은 목적의 다른 경로는 tab으로 나눕니다.

---

## 5. Cloudflare에서 가져올 원칙

`developers.cloudflare.com`에서 관찰한 내용입니다.

* **긴 목록에는 검색을 답니다.**
  Sidebar 맨 위에 `Filter sidebar` 입력칸이 있고 `/` 단축키를 표시합니다.

* **제목 아래 metadata 줄을 둡니다.**
  `Last updated` 와 동작 link들이 제목 바로 아래 작고 흐린 한 줄로 놓입니다.
  본문과는 얇은 선으로 나뉩니다.

* **강조색은 두 곳에만 씁니다.**
  주황색은 Primary 버튼(`Log in`, `Go to Records`)과 현재 위치 표시에만 나타납니다.
  본문 link는 주황이지만 밑줄로 구분하고, 배경을 칠하지 않습니다.

* **보조 동작은 작은 outline 버튼입니다.**
  `Yes` / `No`는 작고 테두리만 있습니다. 채워진 버튼으로 만들지 않습니다.

* **상태 표시는 아주 작습니다.**
  `Beta` 표시는 글자 크기를 줄인 작은 pill입니다. 행 전체를 물들이지 않습니다.

---

## 6. 적용하지 않을 요소

레퍼런스에 있지만 우리 제품에 맞지 않아 쓰지 않는 것입니다.

| 요소 | 어디서 봤는가 | 쓰지 않는 이유 |
| --- | --- | --- |
| 어두운 배경 전체 | Linear | 운영자가 밝은 사무실에서 하루 종일 봅니다. Light를 기본으로 둡니다 |
| 큰 hero typography | Linear, Stripe | 마케팅용입니다. 운영 화면은 한 화면에 정보를 더 담아야 합니다 |
| 배경 gradient와 blur | Linear, Stripe | 장식입니다. 상태를 읽는 데 방해가 됩니다 |
| 채도 높은 brand color | Cloudflare 주황, Stripe 보라 | 우리 강조색은 장애 상태여야 합니다. Brand가 상태보다 눈에 띄면 안 됩니다 |
| 오른쪽 목차 rail | Vercel, Cloudflare | 문서용입니다. 우리 화면은 본문이 짧습니다 |
| Pagination, Bulk action | 운영 Console 일반 | 지금 데이터 규모에 필요 없습니다. 만들지 않습니다 |
| Command palette | Linear | 지금 화면 수로는 Sidebar로 충분합니다 |

---

## 7. 최종 Visual Direction

```text
Light 기본. 배경은 아주 연한 회색, 본문 표면은 흰색.

Sidebar는 본문보다 어둡지 않고 흐립니다. 선택 항목만 또렷합니다.

Card를 기본 단위로 쓰지 않습니다.
영역은 제목과 여백으로 나누고, 표와 목록에만 테두리를 씁니다.

Primary 색은 거의 검정입니다. 파랑이나 보라를 강조색으로 쓰지 않습니다.

색을 쓰는 곳은 두 군데뿐입니다.
  1. 상태 (정상 / 위험 / 알 수 없음)
  2. 되돌릴 수 없는 동작 (삭제)

상태는 작은 점과 글자로 표시합니다. 배경을 칠한 pill을 쓰지 않습니다.

표는 촘촘하게 만듭니다. 행 높이를 줄이고 경계선을 얇게 씁니다.

Radius는 6px 하나로 통일합니다.

Shadow는 Dialog에만 씁니다.

한 화면의 Primary 버튼은 하나입니다. 위치는 Page Header 오른쪽입니다.
```

### Token 이름

`frontend/src/index.css`에 정의합니다. 화면에서 hex 값을 직접 쓰지 않습니다.

```text
배경    --background  --surface  --surface-subtle  --sidebar
글자    --foreground  --muted-foreground  --subtle-foreground
경계    --border  --border-strong
상태    --status-ok  --status-critical  --status-warning  --status-neutral
동작    --primary  --destructive  --ring
```

---

## 관련 문서

* 코드 주석 규칙과 UI 원칙: [CLAUDE.md](../../CLAUDE.md)
* 구조 설명: [architecture.md](../architecture/architecture.md)
* 문서 작성 기준: [documentation-ko.md](../rules/documentation-ko.md)
