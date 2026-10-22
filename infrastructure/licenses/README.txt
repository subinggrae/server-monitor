이 묶음에 든 프로그램과 라이선스
================================

모니터링 코어 묶음과 node_exporter 묶음은 아래 공개 소프트웨어를 고치지 않고 담았다. 각 라이선스 전문과 고지는 이 폴더에 있다.

| 프로그램                         | 버전           | 라이선스   | 이 폴더의 파일                                   |
|----------------------------------|----------------|------------|--------------------------------------------------|
| Prometheus                       | 3.14.0         | Apache-2.0 | prometheus-LICENSE, prometheus-NOTICE            |
| Alertmanager                     | 0.34.1         | Apache-2.0 | alertmanager-LICENSE, alertmanager-NOTICE        |
| Blackbox Exporter                | 0.28.0         | Apache-2.0 | blackbox-exporter-LICENSE, blackbox-exporter-NOTICE |
| node_exporter                    | 1.12.1         | Apache-2.0 | node-exporter-LICENSE, node-exporter-NOTICE      |
| BusyBox(각 컨테이너 이미지 안)   | 1.37.0, 1.38.0 | GPL-2.0    | busybox-LICENSE                                  |

BusyBox는 컨테이너 이미지의 바탕(공개 이미지 quay.io/prometheus/*)에 들어 있는 셸과 기본 명령이다.
GPL-2.0 제3조에 따라 이 묶음에 든 BusyBox 버전의 소스를 sources/ 폴더에 함께 담았다.
소스는 https://busybox.net/downloads/ 에서 받아 공식 체크섬과 대조했다.

이 묶음의 설정 파일과 Alert 규칙은 https://github.com/subinggrae/server-monitor 에서 만들었다.

Programs in this bundle and their licenses: Prometheus, Alertmanager, Blackbox Exporter and node_exporter
are Apache-2.0 (see the *-LICENSE and *-NOTICE files). BusyBox inside the container images is GPL-2.0
(see busybox-LICENSE); its corresponding source code is included in the sources/ directory.
