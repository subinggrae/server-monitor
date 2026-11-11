# 관리자 서버 만들기

모니터링 코어에 붙일 관리자 서버를 만드는 개발자를 위한 문서다. 관리자 서버가 만들 기능, 코어와 연결하는 조건, DB에 둘 데이터를 다룬다. 언어, 프레임워크, 띄우는 방법은 환경에 맞게 정한다.

코어가 부르는 API의 경로와 JSON 형식은 [통합 계약](INTEGRATION.md)이 기준이다. 통합 계약에서 "앱"이라고 부르는 것이 이 문서의 관리자 서버다. 코어를 설치하는 방법은 [운영 가이드](OPERATIONS.md)에 있다.

## 만들 것

관리자 서버는 다음 여섯 가지를 만든다. 앞의 세 API는 코어가 부르므로 통합 계약의 경로와 형식을 그대로 따른다. 나머지 셋은 관리자 서버 안의 일이라 자유롭게 만든다.

| 만들 것 | 하는 일 | 하는 때 | 기준 |
|---|---|---|---|
| 주소 감시 대상 목록 API | 확인할 주소와 확인 주기를 코어에 내줌 | 코어가 15초마다 | [가용성 Check 대상](INTEGRATION.md#가용성-check-대상-get-apiprometheussdblackbox) |
| 서버 감시 대상 목록 API | 사용량을 모을 서버와 수집 주기를 코어에 내줌 | 코어가 30초마다 | [OS exporter 수집 대상](INTEGRATION.md#os-exporter-수집-대상-get-apiprometheussdos-exporter) |
| 장애 수신 API | 코어가 보낸 장애를 기록하고 알림을 보냄 | 코어가, 장애가 생기거나 바뀔 때 | [Alert](INTEGRATION.md#alert-post-apiintegrationsalertmanagerwebhook) |
| 결과 조회 | 코어에 주소 확인 결과와 사용률을 물어 화면에 보임 | 관리자 서버가 필요할 때 | [코어가 제공할 것](INTEGRATION.md#코어가-제공할-것) |
| 임계값 판단 | 사용률이 임계값을 넘으면 알림을 보냄 | 관리자 서버의 주기 작업 | [리소스 임계값 판단](INTEGRATION.md#리소스-임계값-판단) |
| 알림 발송과 재알림 | 문자, 메신저 등으로 보내고 재알림 주기를 지킴 | 관리자 서버 안 | 관리자 서버가 정함 |

먼저 코어가 부르는 세 API를 만들어 [코어와 연결](#코어와-연결하는-조건)되는지 확인하고, 그다음 DB와 화면을 만들면 문제를 찾기 쉽다. [가장 작은 구현 예](#가장-작은-구현-예)가 그 첫 단계다.

## 코어와 연결하는 조건

관리자 서버는 코어와 같은 서버에 컨테이너로 띄우고, 다음 조건을 지킨다.

- **띄우는 순서**: 코어 다음에 띄우고 `monitoring` 네트워크에 붙는다. 이 네트워크는 코어가 만든다.
- **컨테이너 이름과 포트**: 코어 `.env`의 `MONITORING_APP_ADDRESS`와 같게 둔다. 예를 들어 이 값이 `manager-api:8080`이면 컨테이너 이름은 `manager-api`, 컨테이너 안 포트는 8080이다. 코어 컨테이너 이름(`prometheus`, `alertmanager`, `blackbox-exporter`)과 겹치게 짓지 않는다.
- **token**: 코어 설치 폴더의 `secrets/alertmanager_webhook_token` 파일을 읽어, 장애 수신 API에 온 Bearer token과 비교한다. 코어와 같은 파일을 읽어야 하고, 값을 코드나 설정 파일에 쓰지 않는다. 코어가 부르는 세 API 가운데 token을 붙이는 것은 장애 수신 API뿐이다.
- **코어에 물을 주소**: 결과를 조회할 때는 `http://prometheus:9090`을 부른다. 운영자가 코어 서버에서 여는 포트를 바꿔도 이 주소는 그대로다.
- **포트**: 관리자 서버 포트는 밖에 열지 않는다. 코어가 부르는 목록 API 두 개에 인증이 없기 때문이다. 화면을 열어야 하면 코어 서버의 `127.0.0.1`에만 묶는다.
- **화면용 API의 인증**: 로그인과 권한 확인을 넣는다. 넣지 않으면 화면을 열 수 있는 누구나 감시 설정을 바꿀 수 있다.

## 가장 작은 구현 예

다음은 코어가 부르는 API 세 개만 만든 Node.js 예다. 고정된 대상 목록을 내주고, 받은 장애를 콘솔에 찍는다. 실제 관리자 서버는 목록을 DB에서 만들고, 장애를 기록하고 알림을 보낸다.

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

이 예는 token 파일을 컨테이너의 `/run/secrets/`에 붙였다고 본다. 컨테이너 이름을 `MONITORING_APP_ADDRESS`에 맞추고 `monitoring` 네트워크에 붙여 띄운 뒤, [운영 가이드의 설치 확인](OPERATIONS.md#설치-확인)대로 코어가 목록을 받고 장애를 보내는지 본다. 설치 확인 절차는 이 예로 시험했다.

## 저장할 데이터

관리자 서버의 DB에는 다음 데이터를 둔다. 테이블과 열 이름은 관리자 서버가 자기 용어사전에 맞게 정한다.

| 데이터 | 꼭 담을 값 | 쓰는 곳 |
|---|---|---|
| 감시 항목 | 이름, 확인 방식, 환경, 켜짐, 확인 주기 | 주소 감시 대상 목록 |
| 감시 주소 | 감시 항목, 주소, TCP 포트, 켜짐 | 주소 감시 대상 목록 |
| 서버 | 이름, 주소, 환경, OS, 켜짐 | 서버 감시 대상 목록 |
| 사용량 수집 설정 | 서버, 수집 켜짐, exporter 포트, 수집 주기 | 서버 감시 대상 목록 |
| 감시 리소스 | 서버, 리소스 종류와 이름, 표시 이름 | 화면, 임계값 판단 |
| 알림 받을 곳 | 이름, 보낼 곳(메신저 방 id 등), 켜짐 | 알림 발송 |
| 감시 항목의 알림 연결 | 감시 항목, 알림 받을 곳, 복구 알림 여부, 켜짐 | 알림 발송 |
| 재알림 주기 | 감시 항목, 주기 | 재알림 |
| 장애 기록 | 감시 항목, 주소, 장애·복구, 시작·복구 시각, 장애 식별값 | 장애 수신, 화면 |
| 알림 발송 기록 | 장애 기록, 받은 곳, 결과, 실패 이유 | 알림이 오지 않았다는 문의 확인 |

이 데이터를 담는 참고용 DDL이 [MariaDB용](reference-schema/mariadb/base-tables.sql)과 [PostgreSQL용](reference-schema/postgresql/base-tables.sql)으로 있다. 빈 DB에는 그대로 적용하거나 이름만 바꿔 쓴다. 이미 운영 중인 DB에 더할 때는 그 DB의 migration 방식으로 더한다.

테이블 이름과 관계없이 다음은 지킨다.

- **id는 바꾸지 않는다**: 감시 항목, 감시 주소, 서버의 id는 한 번 정하면 바꾸지 않는다. 코어가 이 id로 기록을 쌓고 장애를 보내기 때문이다.
- **주기의 범위**: 확인 주기와 수집 주기는 10~240초, 재알림 주기는 5~1440분으로 받는다. 범위의 이유는 [통합 계약](INTEGRATION.md)에 있다.
- **같은 장애의 기준**: 장애 식별값(`fingerprint`)과 시작 시각이 함께 같을 때 같은 장애로 본다. 식별값만으로 묶으면 같은 주소에서 다음에 생긴 장애가 이전 기록을 덮는다.
- **기록은 남긴다**: 감시 항목이나 알림 받을 곳을 지워도 장애 기록과 알림 발송 기록은 남긴다. 그래서 두 기록에서 감시 항목과 알림 받을 곳을 가리키는 열에는 외래 키를 걸지 않는다.

CPU·Memory·디스크 임계값 알림을 만들면 테이블 네 개가 더 필요하다. 담는 것과 참고용 DDL은 [통합 계약의 참고용 DDL](INTEGRATION.md#참고용-ddl)에 있고, 기본 테이블 다음에 적용한다.

## 화면과 설정 기능

화면과 설정 API는 코어가 부르지 않으므로 경로와 형식을 관리자 서버가 정한다. 사용자가 감시부터 알림까지 쓰려면 화면에서 다음을 할 수 있어야 한다.

| 사용자가 하는 일 | 필요한 때 |
|---|---|
| 감시 항목 등록·수정·삭제·조회 | 항상 |
| 감시 항목의 지금 상태 보기 | 항상 |
| 장애 기록 보기 | 항상 |
| 서버 등록·수정·삭제·조회 | 사용량을 볼 때 |
| 사용량 수집 켜기와 연결 시험 | 사용량을 볼 때 |
| 감시할 리소스 찾기와 고르기 | 사용량을 볼 때 |
| 알림 받을 곳 등록·수정·삭제와 시험 발송 | 알림을 보낼 때 |
| 감시 항목의 알림 받을 곳과 재알림 주기 정하기 | 알림을 보낼 때 |
| 리소스 임계값과 지속 시간 정하기 | 임계값 알림을 쓸 때 |
