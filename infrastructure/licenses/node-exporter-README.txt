이 묶음에 든 프로그램과 라이선스
================================

node_exporter 묶음은 아래 공개 소프트웨어의 실행 파일을 고치지 않고 담았다. 라이선스 전문과 고지는 이 폴더에 있다.

| 프로그램      | 버전   | 라이선스   | 이 폴더의 파일                              |
|---------------|--------|------------|---------------------------------------------|
| node_exporter | 1.12.1 | Apache-2.0 | node-exporter-LICENSE, node-exporter-NOTICE |

컨테이너 이미지(node-exporter-image.tar)에는 node_exporter 실행 파일만 있다. 셸과 다른 프로그램은 없다.
Docker가 없는 서버용 파일(node_exporter-1.12.1.linux-amd64.tar.gz)은 node_exporter 공식 릴리스 파일 그대로다.

이 묶음의 compose 파일과 systemd unit은 https://github.com/subinggrae/server-monitor 에서 만들었다.

Programs in this bundle and their licenses: node_exporter is Apache-2.0 (see node-exporter-LICENSE and
node-exporter-NOTICE). The container image contains only the node_exporter binary.
