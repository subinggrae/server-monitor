이 묶음에 든 프로그램과 라이선스
================================

모니터링 코어 묶음은 아래 공개 소프트웨어의 실행 파일을 고치지 않고 담았다. 각 라이선스 전문과 고지는 이 폴더에 있다.

| 프로그램          | 버전   | 라이선스   | 이 폴더의 파일                                       |
|-------------------|--------|------------|------------------------------------------------------|
| Prometheus        | 3.14.0 | Apache-2.0 | prometheus-LICENSE, prometheus-NOTICE                |
| Alertmanager      | 0.34.1 | Apache-2.0 | alertmanager-LICENSE, alertmanager-NOTICE            |
| Blackbox Exporter | 0.28.0 | Apache-2.0 | blackbox-exporter-LICENSE, blackbox-exporter-NOTICE  |

컨테이너 이미지에는 이 세 프로그램의 실행 파일과 아래 저장소에서 만든 Alert 규칙만 있다. 셸과 다른 프로그램은 없다.
Blackbox Exporter가 HTTPS 확인에 쓰는 CA 인증서는 묶음에 없고, 운영자가 코어 서버 OS의 파일을 복사해 쓴다.

이 묶음의 설정 템플릿과 Alert 규칙은 https://github.com/subinggrae/server-monitor 에서 만들었다.

Programs in this bundle and their licenses: Prometheus, Alertmanager and Blackbox Exporter are Apache-2.0
(see the *-LICENSE and *-NOTICE files). The container images contain only these programs and the alert rules.
