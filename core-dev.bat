@echo off
for /f "tokens=2 delims=:." %%c in ('chcp') do set "OLD_CODEPAGE=%%c"
chcp 65001 >nul
setlocal
rem Windows 빌드 PC에서 모니터링 코어를 빌드하고 개발용으로 띄우는 명령이다. 저장소 어디에서 실행해도 된다.
rem
rem   core-dev.bat build   내부망 서버로 옮길 묶음을 dist\에 만든다(추가 옵션은 빌드 스크립트로 넘긴다. 예: --allow-dirty)
rem   core-dev.bat check   이미지를 빌드해 이미지 검사, 설정 검사, 규칙 테스트, 운영 명령 시험만 한다
rem   core-dev.bat start   infrastructure\.env로 설정을 펼치고, 개발용 코어를 바뀐 설정으로 다시 빌드해 띄운다
rem   core-dev.bat stop    개발용 코어를 멈춘다(쌓은 기록은 남는다)
rem
rem 빌드와 검사는 scripts\build-core-bundle.mjs가 맡는다. 코어 서버(Linux)의 운영 명령은 infrastructure\core-server.sh다.

pushd "%~dp0"

if "%~1"=="build" goto build
if "%~1"=="check" goto check
if "%~1"=="start" goto start
if "%~1"=="stop" goto stop

>&2 echo 사용법: core-dev.bat ^<작업^>
>&2 echo.
>&2 echo   build   내부망 서버로 옮길 묶음을 만듭니다
>&2 echo   check   이미지 검사, 설정 검사, 규칙 테스트를 실행합니다
>&2 echo   start   설정을 펼쳐 개발용 코어를 띄웁니다
>&2 echo   stop    개발용 코어를 멈춥니다
set RC=1
goto end

:build
call :require_docker || goto end
call :require_node || goto end
node scripts\build-core-bundle.mjs %2 %3
set RC=%ERRORLEVEL%
goto end

:check
call :require_docker || goto end
call :require_node || goto end
node scripts\build-core-bundle.mjs --check
set RC=%ERRORLEVEL%
goto end

:start
call :require_docker || goto end
rem 코어 이미지에는 셸이 없고 Windows에는 POSIX sh가 없다. 그래서 Prometheus Dockerfile의 첫 단계(공식 이미지)를 빌드해
rem 그 BusyBox sh로 운영 명령의 render를 실행한다. 이 셸은 빌드 PC에서만 쓰고 묶음에 들어가지 않는다(docs/decisions/0012).
rem 운영 명령처럼 셸 환경 변수가 .env보다 먼저 쓰이도록 같은 이름을 넘긴다.
rem Windows에는 Blackbox Exporter가 읽을 CA 인증서 파일이 없어, infrastructure\certs\에 없을 때만 그 공식 이미지 안의 파일을 꺼내 둔다.
set TOOLS=
for /f "delims=" %%i in ('docker build -q --target src -f infrastructure\prometheus\Dockerfile infrastructure') do set "TOOLS=%%i"
if not defined TOOLS (
  >&2 echo 실패: 설정을 펼칠 공식 Prometheus 이미지를 받지 못했습니다. 위 Docker 오류를 확인해 주세요.
  set RC=1
  goto end
)
docker run --rm -v "%CD%\infrastructure:/work" -w /work -e MONITORING_APP_ADDRESS -e CORE_RETENTION_DAYS -e CORE_RETENTION_SIZE_GB -e CORE_PROBE_TIMEOUT_SECONDS -e CORE_PROMETHEUS_PORT -e CORE_ALERTMANAGER_PORT -e CORE_BLACKBOX_PORT --user 0 --entrypoint /bin/sh %TOOLS% -c "[ -f certs/ca-certificates.crt ] || { mkdir -p certs && cp /etc/ssl/certs/ca-certificates.crt certs/ && chmod 0644 certs/ca-certificates.crt; }; sh core-server.sh render"
if errorlevel 1 (
  set RC=1
  goto end
)
docker compose -f infrastructure\compose.yml up -d --build
set RC=%ERRORLEVEL%
goto end

:stop
call :require_docker || goto end
docker compose -f infrastructure\compose.yml down
set RC=%ERRORLEVEL%
goto end

:require_docker
docker info >nul 2>&1
if errorlevel 1 (
  >&2 echo 실패: Docker에 연결하지 못했습니다. Docker Desktop을 켠 뒤 다시 실행해 주세요.
  set RC=1
  exit /b 1
)
exit /b 0

:require_node
where node >nul 2>&1
if errorlevel 1 (
  >&2 echo 실패: node를 찾지 못했습니다. Node 18 이상을 설치한 뒤 다시 실행해 주세요.
  set RC=1
  exit /b 1
)
exit /b 0

:end
popd
rem 한글 출력을 위해 바꾼 콘솔 코드페이지를 되돌린다.
if defined OLD_CODEPAGE chcp %OLD_CODEPAGE% >nul
exit /b %RC%
