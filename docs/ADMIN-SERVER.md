# 관리자 서버 만들기

모니터링 코어에 붙일 관리자 서버를 만드는 개발자를 위한 문서다. 관리자 서버가 만들 기능, DB에 둘 데이터, 코어와 함께 띄우는 방법을 다룬다. 코어가 부르는 API의 경로와 JSON 형식은 [통합 계약](INTEGRATION.md)이 기준이고, 코어를 설치하는 방법은 [운영 가이드](OPERATIONS.md)에 있다. 통합 계약에서 "앱"이라고 부르는 것이 이 문서의 관리자 서버다.

## 만들 것

관리자 서버가 감시부터 알림까지 하려면 다음 여섯 가지를 만든다. 앞의 세 가지는 코어가 부르는 API라서 통합 계약의 경로와 형식을 그대로 따르고, 뒤의 세 가지는 관리자 서버 안에서 자유롭게 만든다.

| 만들 것 | 누가 언제 | 하는 일 | 형식의 기준 |
|---|---|---|---|
| 주소 감시 대상 목록 API | 코어가 15초마다 | 확인할 주소와 확인 주기를 내줌 | [가용성 Check 대상](INTEGRATION.md#가용성-check-대상-get-apiprometheussdblackbox) |
| 서버 감시 대상 목록 API | 코어가 30초마다 | 사용량을 모을 서버와 수집 주기를 내줌 | [OS exporter 수집 대상](INTEGRATION.md#os-exporter-수집-대상-get-apiprometheussdos-exporter) |
| 장애 수신 API | 코어가 장애가 생기거나 바뀔 때 | 장애를 기록하고 알림을 보냄 | [Alert](INTEGRATION.md#alert-post-apiintegrationsalertmanagerwebhook) |
| 결과 조회 | 관리자 서버가 필요할 때 | 주소 확인 결과와 사용률을 화면에 보임 | [코어가 제공할 것](INTEGRATION.md#코어가-제공할-것) |
| 임계값 판단 | 관리자 서버의 주기 작업 | 사용률이 임계값을 넘으면 알림 | [리소스 임계값 판단](INTEGRATION.md#리소스-임계값-판단) |
| 알림 발송과 재알림 | 관리자 서버 안 | 문자, 메신저 등으로 보내고 재알림 주기를 지킴 | 수단은 관리자 서버가 정함, 문구는 [알림 메시지 틀](#알림-메시지-틀) |

이 저장소의 관리자 서버(Manager)는 이 가운데 임계값 판단을 뺀 다섯 가지를 갖추고 있다.

## 저장할 데이터

관리자 서버의 DB에는 다음 데이터를 둔다. 테이블과 열 이름은 관리자 서버가 자기 용어사전에 맞게 정하고, 표의 마지막 열은 이 저장소의 기준 migration(`backend/src/main/resources/db/migration/`의 `V1__baseline.sql`)에 있는 이름의 예다. 이미 운영 중인 DB에 더할 때는 그 DB의 migration 방식으로 더한다.

| 데이터 | 꼭 담을 값 | 쓰는 곳 | 이 저장소의 테이블 |
|---|---|---|---|
| 감시 항목 | 이름, 확인 방식, 환경, 켜짐, 확인 주기 | 주소 감시 대상 목록 | `monitoring_checks` |
| 감시 주소 | 감시 항목, 주소, TCP 포트, 켜짐 | 주소 감시 대상 목록 | `monitoring_check_targets` |
| 서버 | 이름, 주소, 환경, OS, 켜짐 | 서버 감시 대상 목록 | `server_targets` |
| 사용량 수집 설정 | 서버, 수집 켜짐, exporter 포트, 수집 주기 | 서버 감시 대상 목록 | `server_exporter_configs` |
| 감시 리소스 | 서버, 리소스 종류와 이름, 표시 이름 | 화면, 임계값 판단 | `monitored_resources` |
| 알림 받을 곳 | 이름, 보낼 곳(메신저 방 id 등), 켜짐 | 알림 발송 | `telegram_destinations` |
| 감시 항목의 알림 연결 | 감시 항목, 알림 받을 곳, 복구 알림 여부, 켜짐 | 알림 발송 | `monitoring_check_notification_bindings` |
| 재알림 주기 | 감시 항목, 주기 | 재알림 | `check_notification_settings` |
| 장애 기록 | 감시 항목, 주소, 장애·복구, 시작·복구 시각, 장애 식별값 | 장애 수신, 화면 | `alert_events` |
| 알림 발송 기록 | 장애 기록, 받은 곳, 결과, 실패 이유 | 알림이 오지 않았다는 문의 확인 | `notification_deliveries` |
| 알림 메시지 틀 | 틀 종류, 틀 문구 | 알림 발송 | 없음(참고용 DDL) |

이름과 관계없이 다음은 지킨다.

- 감시 항목, 감시 주소, 서버의 id는 한 번 정하면 바꾸지 않는다. 코어가 이 id로 기록을 쌓고 장애를 보내기 때문이다.
- 확인 주기와 수집 주기는 10~240초, 재알림 주기는 5~1440분으로 받는다. 범위의 이유는 [통합 계약](INTEGRATION.md)에 있다.
- 장애 기록은 장애 식별값(`fingerprint`)과 시작 시각이 함께 같을 때 같은 장애로 본다. 식별값만으로 묶으면 같은 주소에서 다음에 생긴 장애가 이전 기록을 덮는다.
- 장애 기록과 알림 발송 기록은 감시 항목이나 알림 받을 곳을 지워도 남긴다. 그래서 두 기록에서 감시 항목과 알림 받을 곳을 가리키는 열에는 외래 키를 걸지 않는다.

CPU·Memory·디스크 임계값 알림을 만들면 테이블 네 개가 더 필요하다. 담는 것과 참고용 DDL은 [통합 계약의 참고용 DDL](INTEGRATION.md#참고용-ddl)에 있다. 알림 메시지 틀 테이블의 참고용 DDL과 규칙은 [알림 메시지 틀](#알림-메시지-틀)에 있다. 이 저장소의 관리자 서버를 그대로 도입하면서 이름만 바꾸는 절차는 [통합 계약의 테이블과 열 이름 바꾸기](INTEGRATION.md#테이블과-열-이름-바꾸기)에 있다.

## 화면과 설정 기능

화면과 설정 API는 코어가 부르지 않으므로 경로와 형식을 관리자 서버가 정한다. 다음은 사용자가 감시부터 알림까지 쓰려면 할 수 있어야 하는 일이고, 마지막 열은 이 저장소의 Manager가 만든 API의 예다.

| 사용자가 하는 일 | 필요한 때 | 이 저장소의 API |
|---|---|---|
| 감시 항목 등록·수정·삭제·조회 | 항상 | `GET`, `POST /api/checks`와 `GET`, `PUT`, `DELETE /api/checks/{id}` |
| 감시 항목의 지금 상태 보기 | 항상 | `GET /api/checks/{id}/status` |
| 장애 기록 보기 | 항상 | `GET /api/alerts` |
| 서버 등록·수정·삭제·조회 | 사용량을 볼 때 | `GET`, `POST /api/servers`와 `GET`, `PUT`, `DELETE /api/servers/{id}` |
| 사용량 수집 켜기와 연결 시험 | 사용량을 볼 때 | `GET`, `PUT /api/servers/{id}/exporter`와 `POST /api/servers/{id}/exporter/test` |
| 감시할 리소스 찾기와 고르기 | 사용량을 볼 때 | `POST /api/servers/{id}/resources/discover`와 `GET`, `PUT /api/servers/{id}/resources` |
| 알림 받을 곳 등록·수정·삭제와 시험 발송 | 알림을 보낼 때 | `/api/telegram/destinations`와 `POST /api/telegram/destinations/{id}/test` |
| 감시 항목의 알림 받을 곳과 재알림 주기 정하기 | 알림을 보낼 때 | `GET`, `PUT /api/checks/{id}/notifications` |
| 리소스 임계값과 지속 시간 정하기 | 임계값 알림을 쓸 때 | 없음(이 저장소의 Manager에 없음) |
| 알림 메시지 틀 보기·고치기 | 알림을 보낼 때 | 없음(이 저장소의 Manager에 없음) |

이 저장소의 화면용 API에는 로그인과 권한 확인이 없어서, 포트를 코어 서버의 `127.0.0.1`에만 연다. 여러 사람이 쓰는 관리자 서버를 만들면 화면용 API에 로그인과 권한 확인을 넣는다. 코어가 부르는 세 API는 통합 계약의 인증 방식을 그대로 둔다.

## 알림 메시지 틀

관리자 서버는 알림 문장을 관리자가 화면에서 정한 틀로 만든다. 그래서 문구를 바꿀 때 코드를 고치거나 다시 배포하지 않는다. 코어 Alert 규칙의 summary와 description은 알림 문구에 쓰지 않고 Alertmanager 화면에만 남긴다. 알림을 받는 운영자는 개발에 참여하지 않은 사람일 수 있어서, 코어의 영어 표기 대신 쉬운 한국어로 알려야 하기 때문이다. 이 저장소의 Manager에는 틀 기능이 없고 문구가 코드에 고정되어 있다. 결정과 이유는 [요구사항 0014](requirements/0014-alert-message-template.md)에 있다.

틀 테이블의 참고용 DDL은 [MariaDB용](reference-schema/mariadb/alert-message-templates.sql)과 [PostgreSQL용](reference-schema/postgresql/alert-message-templates.sql)이 있다. 틀 종류마다 0~1행이고, 다른 테이블을 외래 키로 가리키지 않는다. 테이블과 열 이름은 [저장할 데이터](#저장할-데이터)처럼 관리자 서버의 용어사전에 맞게 바꿔도 된다.

### 틀 종류와 재료

틀은 다음 네 종류이고 종류마다 하나씩 둔다. 모든 감시 항목이 감시 항목 틀을, 모든 서버의 리소스가 리소스 틀을 함께 쓴다. 재알림 틀은 따로 두지 않고 장애 틀을 다시 쓴다.

| 종류 | 참고용 DDL의 값 | 쓰는 알림 |
|---|---|---|
| 감시 항목 장애 | `CHECK_FIRING` | 감시 항목의 장애와 재알림 |
| 감시 항목 복구 | `CHECK_RESOLVED` | 감시 항목의 복구 |
| 리소스 장애 | `RESOURCE_FIRING` | 리소스 임계값의 장애와 재알림(위험, 경고 모두) |
| 리소스 복구 | `RESOURCE_RESOLVED` | 리소스 임계값의 복구 |

틀에는 다음 재료를 중괄호로 감싸 쓴다. 메시지를 만들 때 재료 자리에 값이 들어간다. 종류마다 값이 있는 재료만 쓸 수 있어서 메시지에 빈자리가 생기지 않는다.

| 재료 | 쓰는 틀 | 넣는 값 | 예 |
|---|---|---|---|
| `{상태}` | 모든 틀 | 장애 틀은 `장애`, 복구 틀은 `복구` | `장애` |
| `{감시항목}` | 감시 항목 | 보낼 때 DB에서 읽은 감시 항목 이름 | `전자결재 Health` |
| `{환경}` | 모든 틀 | 보낼 때 DB에서 읽은 감시 항목이나 서버의 환경 | `운영` |
| `{확인방식}` | 감시 항목 | 감시 항목의 확인 방식 | `HTTPS` |
| `{대상}` | 감시 항목 | webhook으로 받은 Alert의 `labels.instance` | `https://example.com/health` |
| `{서버}` | 리소스 | 보낼 때 DB에서 읽은 서버 이름 | `웹 서버 1` |
| `{리소스}` | 리소스 | 감시 리소스의 표시 이름 | `디스크 /` |
| `{단계}` | 리소스 | `CRITICAL`이면 `위험`, `WARNING`이면 `경고` | `위험` |
| `{사용률}` | 리소스 | 그 알림을 정한 판단의 사용률(소수 첫째 자리까지) | `91.3%` |
| `{임계값}` | 리소스 | 그 단계의 임계값 | `90%` |
| `{발생시각}` | 모든 틀 | 감시 항목은 Alert의 `startsAt`, 리소스는 발생 기록의 발생 시각 | `2026-11-09 14:03:21` |
| `{복구시각}` | 복구 틀 | 감시 항목은 Alert의 `endsAt`, 리소스는 발생 기록의 복구 시각 | `2026-11-09 14:20:05` |

복구 틀의 `{사용률}`은 복구를 판단한 때의 값이다. 시각은 `yyyy-MM-dd HH:mm:ss` 형식으로, 관리자 서버에 정한 시간대 하나(기본 `Asia/Seoul`)로 바꿔 넣고 시간대는 표시하지 않는다. DB에는 UTC로 저장하고 메시지에 넣을 때만 바꾼다.

### 저장할 때의 검사

관리자 서버는 틀을 저장하기 전에 다음을 서버에서 확인한다. 하나라도 맞지 않으면 저장을 거부하고 기존 틀을 그대로 둔다.

- 틀 종류가 네 종류 가운데 하나다. 참고용 DDL은 종류 값을 DB 제약으로 막지 않는다.
- 목록에 없는 재료가 없다. 예: `{담당자}`
- 그 종류에 값이 없는 재료가 없다. 예: 감시 항목 장애 틀의 `{사용률}`, `{복구시각}`
- 여는 중괄호와 닫는 중괄호의 짝이 맞는다. 예: `{감시항목`
- 1000자 이하다.

중괄호로 감싼 글은 모두 재료로 보므로, 재료가 아닌 중괄호를 글자로 쓰는 방법은 두지 않는다. 1000자는 code point 단위로 센다. 한글 한 글자와 대부분의 이모지는 한 자이고, 여러 문자를 이어 만든 이모지(국기 등)는 여러 자다. 참고용 DDL의 `VARCHAR(1000)`도 두 DB에서 같은 단위로 센다. Java `String.length()`처럼 UTF-16 단위로 세면 이모지 하나를 두 자로 세어 DB와 어긋난다.

거부할 때는 무엇이 문제인지와 어떻게 고치면 되는지 함께 알린다. 재료가 문제이면 `쓸 수 없는 재료가 있습니다: {담당자}. 이 틀에는 {상태}, {감시항목}, {환경}, {확인방식}, {대상}, {발생시각}을 쓸 수 있습니다.`처럼, 길이가 문제이면 `틀은 1000자까지 쓸 수 있습니다.`처럼 알린다. 틀을 고치는 화면은 틀과 이 문구를 화면 프레임워크의 기본 escape로 보인다. 관리자가 넣은 글이 HTML로 해석되면 안 되기 때문이다.

틀을 모두 지우고 저장하면 거부하지 않고 그 종류의 행을 지운다. 행이 없는 종류는 기본 틀을 쓰므로 다음 알림부터 기본 틀로 간다. 그래서 빈 메시지가 가지 않고, 기본 틀로 되돌리는 기능을 따로 만들지 않아도 된다.

틀을 고치는 화면은 행이 없는 종류에 기본 틀을 보이고, 틀마다 그 종류에 쓸 수 있는 재료 목록을 함께 보인다. 그래서 관리자는 빈 칸에서 시작하지 않고, 쓸 수 있는 재료를 찾아보지 않아도 된다.

틀을 바꾸면 모든 알림의 문구가 바뀐다. 그래서 틀 저장 API도 다른 설정 API처럼 로그인과 권한 확인을 거친 사람만 부르게 한다([화면과 설정 기능](#화면과-설정-기능)).

### 메시지 만들기

관리자 서버는 알림을 보낼 때마다 그 종류의 틀을 DB에서 읽어 메시지를 만든다. 그래서 틀을 저장한 뒤 보내는 알림부터 새 틀을 쓰고, 저장 전에 시작한 장애의 재알림과 복구 알림도 새 틀로 간다. 이미 보낸 알림과 쌓인 장애 기록은 바뀌지 않는다. 메시지를 만들 때는 다음을 지킨다.

- 재료 자리를 값으로 바꾸는 일만 한다. 식이나 조건문을 해석하는 템플릿 엔진을 쓰지 않는다. 엔진이 식을 해석하면 틀을 쓰는 사람이 서버의 코드나 데이터에 닿을 수 있다.
- 넣은 값은 다시 재료로 바꾸지 않는다. 틀을 앞에서부터 한 번만 읽으며 바꾸면 된다. 그래서 감시 항목 이름이 `{환경} 점검`이면 메시지에도 `{환경} 점검`이 그대로 보인다.
- 감시 항목 이름, 서버 이름, 환경은 보낼 때 DB에서 읽는다. Alert의 label 값은 사용자가 이름이나 환경을 바꾸기 전의 값일 수 있다.
- 메시지는 서식 없는 글로 보낸다. 값의 밑줄이나 괄호를 알림 수단이 서식으로 읽으면 메시지가 바뀌거나 거부된다. Telegram이면 `parse_mode`를 보내지 않는다.
- 만든 메시지가 알림 수단의 한 메시지 한도(Telegram은 4096자)를 넘으면 뒤를 자르고 끝에 `…`를 붙인다. `…`까지 한도 안에 들게 자르고, 글자를 세는 단위는 알림 수단을 따른다. 자르는 자리는 code point 경계로 둔다. UTF-16 단위로 자르면 이모지가 반으로 갈려 알림 수단이 메시지를 거부할 수 있다.
- 지운 감시 항목의 장애는 [통합 계약의 Alert](INTEGRATION.md#alert-post-apiintegrationsalertmanagerwebhook)대로 알리지 않는다. 그래서 이름을 찾지 못한 메시지를 만들 일이 없다.

### 기본 틀

관리자가 고치지 않은 종류는 관리자 서버가 가진 기본 틀로 보낸다. 기본 틀은 관리자 서버의 코드나 설정에 두고 DB에 넣지 않는다. 그래서 기본 문구를 고칠 때 DB의 행을 옮기지 않는다.

기본 문구는 개발에 참여하지 않은 운영자도 바로 읽을 수 있게 [TERMS.md의 화면 글](TERMS.md#화면-글) 용어(장애, 복구, 감시 항목, 위험, 경고)로 쓴다. `[RESOLVED]`, `[CRITICAL]` 같은 영어 표기와 이모지는 쓰지 않는다. 첫머리의 `[장애]`, `[복구]`만으로 알림 미리 보기에서 구분된다. 또 재료 바로 뒤에는 조사를 붙이지 않는다. 받침이 있는지에 따라 알맞은 조사가 달라 `90%을`처럼 틀린 말이 나오기 때문이다. 관리자가 틀을 고칠 때도 이 방식을 권한다.

감시 항목의 장애·재알림에 쓰는 기본 틀이다.

```text
[{상태}] {감시항목}
{대상} 확인에 실패했습니다.

환경: {환경}
확인 방식: {확인방식}
발생 시각: {발생시각}
```

이 기본 틀로 보낸 장애 알림은 다음처럼 보인다.

```text
[장애] 전자결재 Health
https://example.com/health 확인에 실패했습니다.

환경: 운영
확인 방식: HTTPS
발생 시각: 2026-11-09 14:03:21
```

감시 항목의 복구에 쓰는 기본 틀이다.

```text
[{상태}] {감시항목}
{대상} 확인이 정상으로 돌아왔습니다.

환경: {환경}
확인 방식: {확인방식}
발생 시각: {발생시각}
복구 시각: {복구시각}
```

리소스 임계값의 장애·재알림에 쓰는 기본 틀이다.

```text
[{상태}] {서버}의 {리소스} 사용률이 높습니다
단계: {단계}
사용률: {사용률} (임계값 {임계값})

환경: {환경}
발생 시각: {발생시각}
```

이 기본 틀로 보낸 위험 단계 알림은 다음처럼 보인다.

```text
[장애] 웹 서버 1의 디스크 / 사용률이 높습니다
단계: 위험
사용률: 91.3% (임계값 90%)

환경: 운영
발생 시각: 2026-11-09 14:03:21
```

리소스 임계값의 복구에 쓰는 기본 틀이다.

```text
[{상태}] {서버}의 {리소스} 사용률이 임계값 이하로 내려왔습니다
단계: {단계}
사용률: {사용률} (임계값 {임계값})

환경: {환경}
발생 시각: {발생시각}
복구 시각: {복구시각}
```

### 장애 기록 문구

감시 항목의 장애 기록에는 코어 summary 대신 `{대상} 확인에 실패했습니다` 형식의 고정 문구를 저장한다. 예를 들면 `https://example.com/health 확인에 실패했습니다`다. 이 문구는 틀을 쓰지 않으므로 관리자가 틀을 바꿔도 기록마다 문구가 달라지지 않는다. 감시 항목 이름은 기록에 저장하지 않고, 화면이 기록을 보일 때 DB의 지금 이름을 함께 보인다. 그래서 이름을 바꾸면 지난 기록에도 새 이름이 보인다.

리소스 임계값의 발생 기록에는 문구를 두지 않는다. 발생 기록이 그때의 서버 이름, 리소스 이름, 임계값, 사용률을 이미 담기 때문이다([통합 계약의 참고용 DDL](INTEGRATION.md#참고용-ddl)).

## 관리자 서버 띄우기

관리자 서버는 다음 조건으로 띄운다.

- 코어 다음에 띄우고 `monitoring` 네트워크에 붙는다.
- 컨테이너 이름과 포트가 코어 `.env`의 `MONITORING_APP_ADDRESS`와 같다.
- 코어와 같은 token 파일을 컨테이너에 붙인다. 컨테이너 안에서는 `/run/secrets/alertmanager_webhook_token`에서 읽는다. 코드나 설정 파일에 값을 쓰지 않는다.
- 목록 API는 인증 없이 불리므로 관리자 서버 포트를 밖에 열지 않는다. 화면을 열어야 하면 `127.0.0.1`에만 묶는다.

`docker run`으로 띄우면 다음과 같다. `my-admin`, `my-admin-image`, 환경 변수 이름은 관리자 서버에 맞춘다.

```sh
docker run -d --name my-admin --network monitoring \
  -v /opt/monitoring/secrets/alertmanager_webhook_token:/run/secrets/alertmanager_webhook_token:ro \
  -e PROMETHEUS_URL=http://prometheus:9090 \
  -p 127.0.0.1:8080:8080 my-admin-image
```

이미 떠 있는 관리자 서버는 다시 띄우지 않고 `docker network connect monitoring my-admin`으로 네트워크에 붙여도 된다. compose로 띄우면 다음과 같다.

```yaml
services:
  my-admin:
    image: my-admin-image
    container_name: my-admin
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      PROMETHEUS_URL: http://prometheus:9090
    secrets:
      - alertmanager_webhook_token
    networks:
      - monitoring

secrets:
  alertmanager_webhook_token:
    file: /opt/monitoring/secrets/alertmanager_webhook_token

networks:
  monitoring:
    name: monitoring
    external: true
```

## 가장 작은 구현 예

다음은 코어가 부르는 API 세 개만 만든 Node.js 예다. 고정된 대상 목록을 내주고, 받은 장애를 콘솔에 찍는다. [운영 가이드의 설치 확인](OPERATIONS.md#설치-확인)을 이 예로 시험했다. 실제 관리자 서버는 목록을 DB에서 만들고, 장애를 기록하고 알림을 보낸다.

```js
import http from 'node:http';
import fs from 'node:fs';
import crypto from 'node:crypto';

// 코어와 같은 token 파일을 읽는다. 값을 로그에 남기지 않는다.
const token = fs.readFileSync('/run/secrets/alertmanager_webhook_token', 'utf8').trim();

// label 값은 모두 문자열이다. id를 숫자로 보내면 코어가 목록 전체를 거부한다.
const addressTargets = [
  { targets: ['https://example.com/health'],
    labels: { check_id: '1', check_target_id: '11', check_type: 'HTTPS', module: 'http_2xx',
      environment: 'Production', check_name: '서비스 상태', __scrape_interval__: '30s' } },
];
const serverTargets = [
  { targets: ['10.0.0.21:9100'],
    labels: { server_id: '7', server_name: '웹 서버 1', environment: 'Production', os: 'LINUX',
      __scrape_interval__: '30s' } },
];

function json(res, body) {
  res.writeHead(200, { 'Content-Type': 'application/json' });
  res.end(JSON.stringify(body));
}

http.createServer((req, res) => {
  if (req.method === 'GET' && req.url === '/api/prometheus/sd/blackbox') return json(res, addressTargets);
  if (req.method === 'GET' && req.url === '/api/prometheus/sd/os-exporter') return json(res, serverTargets);
  if (req.method === 'POST' && req.url === '/api/integrations/alertmanager/webhook') {
    const given = Buffer.from(req.headers.authorization ?? '');
    const expected = Buffer.from(`Bearer ${token}`);
    if (given.length !== expected.length || !crypto.timingSafeEqual(given, expected)) {
      res.writeHead(401);
      return res.end();
    }
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      for (const alert of JSON.parse(body).alerts) {
        // status는 firing(장애) 또는 resolved(복구)다. 여기서 기록하고 알림을 보낸다.
        console.log(alert.status, alert.labels.check_id, alert.labels.instance, alert.annotations.summary);
      }
      // 알림 전송에 실패해도 2xx를 돌려준다. 실패를 돌려주면 코어가 같은 장애를 계속 다시 보낸다.
      res.writeHead(200);
      res.end();
    });
    return;
  }
  res.writeHead(404);
  res.end();
}).listen(8080);
```
