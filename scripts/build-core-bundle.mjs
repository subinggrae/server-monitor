#!/usr/bin/env node
// 인터넷이 없는 내부망 서버로 옮길 코어 묶음과 node_exporter 묶음을 만든다. 의존성 없이 Node 18 이상에서 동작한다.
// 결정과 이유는 docs/decisions/0009-core-config-in-image.md, 옮겨 설치하는 방법은 docs/OPERATIONS.md에 있다.
//
//   node scripts/build-core-bundle.mjs           이미지를 빌드해 검사하고 묶음을 만든다
//   node scripts/build-core-bundle.mjs --check   이미지를 빌드해 설정 검사와 규칙 테스트만 한다(묶음을 만들지 않음)
//
// 옵션: --allow-dirty  커밋하지 않은 변경이 있어도 시험용 묶음을 만든다(버전 끝에 -dirty가 붙음)
//
// 저장소 최상위에서 실행하고, Docker와 인터넷이 필요하다. 결과는 dist/monitoring-core-<버전>/, dist/node-exporter-<버전>/에 생긴다.
// 종료 코드: 빌드, 검사, 비밀값 확인 가운데 하나라도 실패하면 1.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const ROOT = process.cwd();
const CORE_DIR = path.join(ROOT, 'infrastructure');
const CORE_COMPOSE = path.join(CORE_DIR, 'compose.yml');
const DIST = path.join(ROOT, 'dist');

// 내부망 서버는 x86-64만 쓴다(요구사항 0010).
const PLATFORM = 'linux/amd64';

const CORE_IMAGES = ['prometheus', 'alertmanager', 'blackbox-exporter'];

// 설정 검사에 쓰는 앱 주소다. 빌드한 이미지에는 들어가지 않고, 서버에서 실행할 때 진짜 주소를 받는다.
const CHECK_APP_ADDRESS = 'manager-api:8080';

// node_exporter의 버전과 공식 릴리스 값이다. 버전을 올릴 때 infrastructure/node-exporter/compose.yml과 함께 바꾼다.
const NODE_EXPORTER = {
  version: '1.12.1',
  image: 'quay.io/prometheus/node-exporter:v1.12.1',
  imageDigest: 'sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0',
  archive: 'node_exporter-1.12.1.linux-amd64.tar.gz',
  // https://github.com/prometheus/node_exporter/releases/download/v1.12.1/sha256sums.txt 의 값이다(2026-10-22 확인).
  archiveSha256: 'b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63',
};

// 이미지 바탕에 든 BusyBox(GPL-2.0)의 소스다. 묶음을 회사 밖에 줄 때도 GPL-2.0 제3조를 지키도록 같은 버전의 소스를 함께 싣는다.
// 바탕 이미지를 올려 BusyBox 버전이 바뀌면 빌드가 멈추므로, 그때 https://busybox.net/downloads/ 의 .sha256 값을 여기에 더한다.
const BUSYBOX_SOURCES = {
  '1.38.0': '34f9ea6ff8636f2c9241153b9114eefa9e65674a45318ae1ef95bb5f31c53bb2',
  '1.37.0': '3311dff32e746499f4df0d5df04d7eb396382d7e108bb9250e7b519b837043a4',
};
// busybox.net의 HTTPS를 막는 네트워크가 있어, 닿지 않으면 Buildroot의 공식 소스 미러에서 받는다. 두 곳의 파일은 같다(2026-10-23 확인).
const BUSYBOX_MIRRORS = ['https://busybox.net/downloads', 'https://sources.buildroot.net/busybox'];
const CACHE = path.join(DIST, '.cache');

function fail(message) {
  console.error(`실패: ${message}`);
  process.exit(1);
}

function run(command, args, { env = {}, capture = false } = {}) {
  const result = spawnSync(command, args, {
    cwd: ROOT,
    env: { ...process.env, ...env },
    stdio: capture ? ['ignore', 'pipe', 'inherit'] : 'inherit',
    encoding: 'utf8',
  });
  if (result.error) {
    fail(`${command}를 실행하지 못했다: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`${command} ${args.join(' ')} 종료 코드 ${result.status}`);
  }
  return capture ? result.stdout.trim() : '';
}

function sha256(file) {
  return crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
}

function filesUnder(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    return entry.isDirectory() ? filesUnder(full) : [full];
  });
}

// sha256sum -c로 확인할 수 있는 형식으로 쓴다.
function writeChecksums(dir) {
  const lines = filesUnder(dir)
    .map((file) => path.relative(dir, file).split(path.sep).join('/'))
    .filter((relative) => relative !== 'SHA256SUMS')
    .sort()
    .map((relative) => `${sha256(path.join(dir, relative))}  ${relative}`);
  fs.writeFileSync(path.join(dir, 'SHA256SUMS'), `${lines.join('\n')}\n`);
}

function freshDir(dir) {
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
}

function copy(from, to) {
  fs.mkdirSync(path.dirname(to), { recursive: true });
  fs.copyFileSync(from, to);
}

// 묶음의 텍스트 파일은 Windows에서 빌드해도 LF로 쓴다. 내부망 Linux 서버의 도구가 CR을 값으로 읽지 않게 하기 위해서다.
function writeText(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text.replace(/\r\n/g, '\n'));
}

// 같은 -dirty 태그가 내용이 다른 이미지에 붙으면 태그로 커밋을 알 수 없고 되돌리기도 깨진다.
// 그래서 묶음은 커밋한 상태에서만 만들고, 검사(--check)와 --allow-dirty만 커밋하지 않은 변경을 허용한다.
function version({ allowDirty }) {
  const describe = run('git', ['describe', '--tags', '--always', '--dirty'], { capture: true });
  const revision = run('git', ['rev-parse', 'HEAD'], { capture: true });
  if (describe.endsWith('-dirty')) {
    if (!allowDirty) {
      fail('커밋하지 않은 변경이 있다. 커밋한 뒤 묶음을 만든다. 시험용으로만 만들려면 --allow-dirty를 붙인다.');
    }
    console.warn('경고: 커밋하지 않은 변경이 있다. 버전 끝에 -dirty가 붙고, 그 커밋만으로는 같은 이미지를 다시 만들 수 없다.');
  }
  return { describe, revision };
}

function composeEnv(core) {
  return {
    CORE_VERSION: core.describe,
    CORE_REVISION: core.revision,
    DOCKER_DEFAULT_PLATFORM: PLATFORM,
    // compose.yml의 ${MONITORING_APP_ADDRESS:?}가 빌드에서도 값을 요구한다. 이미지에는 들어가지 않는다.
    MONITORING_APP_ADDRESS: process.env.MONITORING_APP_ADDRESS ?? CHECK_APP_ADDRESS,
  };
}

function imageName(name, core) {
  return `monitoring-core-${name}:${core.describe}`;
}

function checkCoreImages(core) {
  const prometheus = imageName('prometheus', core);
  const alertmanager = imageName('alertmanager', core);
  // 설정 검사에는 token이 필요 없으므로 시작할 때의 비밀값 파일 확인(CORE_REQUIRED_FILES)을 끈다.
  const env = ['-e', `MONITORING_APP_ADDRESS=${CHECK_APP_ADDRESS}`, '-e', 'CORE_REQUIRED_FILES='];
  console.log('빌드한 이미지로 Prometheus 설정을 검사한다.');
  run('docker', ['run', '--rm', ...env, '--entrypoint', '/bin/sh', prometheus,
    '/etc/core/render-config.sh', '/etc/prometheus/prometheus.yml.template', '/tmp/prometheus.yml',
    'promtool', 'check', 'config', '/tmp/prometheus.yml']);
  // 테스트 파일은 ../rules/를 읽으므로 이미지 안의 규칙 바로 옆에 붙여, 이미지에 들어간 규칙을 그대로 시험한다.
  console.log('빌드한 이미지의 규칙으로 규칙 테스트를 실행한다.');
  const tests = path.join(CORE_DIR, 'prometheus', 'tests');
  for (const testFile of fs.readdirSync(tests).filter((name) => name.endsWith('.test.yml'))) {
    run('docker', ['run', '--rm', '-v', `${tests}:/etc/prometheus/tests:ro`, '-w', '/etc/prometheus/tests',
      '--entrypoint', 'promtool', prometheus, 'test', 'rules', testFile]);
  }
  console.log('빌드한 이미지로 Alertmanager 설정을 검사한다.');
  run('docker', ['run', '--rm', ...env, '--entrypoint', '/bin/sh', alertmanager,
    '/etc/core/render-config.sh', '/etc/alertmanager/alertmanager.yml.template', '/tmp/alertmanager.yml',
    'amtool', 'check-config', '/tmp/alertmanager.yml']);
  // 코어 서버의 sh가 BusyBox일 수도 있어, 이미지에 든 BusyBox sh로 운영 명령을 시험한다.
  console.log('코어 서버 운영 명령(core-server.sh)의 자동 시험을 실행한다.');
  run('docker', ['run', '--rm', '-v', `${CORE_DIR}:/src:ro`, '--entrypoint', '/bin/sh', prometheus,
    '/src/tests/core-server.test.sh', '/src/core-server.sh']);
}

// 저장소에서는 infrastructure/secrets/를 쓰고, 묶음은 버전 폴더가 바뀌어도 같은 token을 쓰도록 설치 폴더의 secrets/를 쓴다(요구사항 0011).
const SECRETS_DEFAULT = '${CORE_SECRETS_DIR:-./secrets}';
const BUNDLE_SECRETS_DEFAULT = '${CORE_SECRETS_DIR:-../secrets}';

// 묶음 폴더에서는 저장소의 infrastructure/ 아래 파일이 같은 폴더에 있으므로 안내 문구의 경로를 바꾼다.
function forBundle(text) {
  return text
    .replaceAll('infrastructure/secrets/', 'secrets/')
    .replaceAll('infrastructure/.env.example', '.env.example')
    .replaceAll('infrastructure/.env', '.env')
    .replaceAll('infrastructure/compose.yml', 'compose.yml');
}

// 저장소의 compose.yml에서 build 항목만 pull_policy: never로 바꾼다. 서비스 정의의 출처를 하나로 두기 위해서다.
// 이미지를 불러오지 않고 띄우면 Compose가 받거나 빌드하지 않고 이미지가 없다는 오류로 멈춘다.
// 파일 머리의 개발용 안내 주석은 내부망에서 맞지 않으므로 빼고 묶음용 안내로 바꾼다.
function runtimeCompose(core) {
  const lines = forBundle(fs.readFileSync(CORE_COMPOSE, 'utf8')).split(/\r?\n/);
  while (lines.length > 0 && (lines[0].startsWith('#') || lines[0].trim() === '')) {
    lines.shift();
  }
  const out = [
    '# 내부망 서버용 모니터링 코어 실행 파일이다. scripts/build-core-bundle.mjs가 저장소의 infrastructure/compose.yml에서 만들었다.',
    `# 버전 ${core.describe}, 커밋 ${core.revision}`,
    '# 설치 방법은 저장소의 docs/OPERATIONS.md에 있다. 이 파일은 고치지 않고, 바꿀 것은 .env와 설치 폴더의 secrets/에 둔다.',
    '#',
    '#   sh core-server.sh install',
    '#   sh core-server.sh start',
    '',
  ];
  let skipIndent = -1;
  for (const line of lines) {
    const indent = line.length - line.trimStart().length;
    if (skipIndent >= 0) {
      if (line.trim() === '' || indent > skipIndent) {
        continue;
      }
      skipIndent = -1;
    }
    if (/^x-core-build-args:/.test(line)) {
      skipIndent = 0;
      continue;
    }
    if (/^\s+build:\s*$/.test(line)) {
      out.push(`${' '.repeat(indent)}pull_policy: never`);
      skipIndent = indent;
      continue;
    }
    out.push(line.replaceAll('${CORE_VERSION:-dev}', core.describe).replaceAll(SECRETS_DEFAULT, BUNDLE_SECRETS_DEFAULT));
  }
  const text = `${out.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd()}\n`;
  if (!text.includes(BUNDLE_SECRETS_DEFAULT)) {
    fail(`실행용 compose 파일에 token 폴더 기본값 ${SECRETS_DEFAULT}가 없다. infrastructure/compose.yml의 secrets 항목을 확인한다.`);
  }
  if (/^\s+build:/m.test(text) || /CORE_VERSION|x-core-build-args|core-build-args/.test(text)) {
    fail('실행용 compose 파일에 빌드 항목이 남았다. infrastructure/compose.yml의 형식이 바뀌었는지 확인한다.');
  }
  if ((text.match(/^\s+pull_policy:/gm) ?? []).length !== CORE_IMAGES.length) {
    fail('실행용 compose 파일의 pull_policy가 서비스 수와 다르다. infrastructure/compose.yml에 pull_policy를 직접 두지 않는다.');
  }
  return text;
}

// 만든 실행용 compose 파일을 Compose가 실제로 읽을 수 있는지 확인한다. 깨진 파일이 내부망 서버에서야 드러나지 않게 한다.
function validateRuntimeCompose(dir) {
  run('docker', ['compose', '--project-directory', dir, '-f', path.join(dir, 'compose.yml'), 'config', '--quiet'],
    { env: { MONITORING_APP_ADDRESS: CHECK_APP_ADDRESS } });
}

// infrastructure/secrets/의 실제 비밀값이다. Docker가 없는 secret 자리에 만든 폴더는 건너뛴다. 값은 출력하지 않는다.
function localSecretValues() {
  const secretsDir = path.join(CORE_DIR, 'secrets');
  if (!fs.existsSync(secretsDir)) {
    return [];
  }
  return fs.readdirSync(secretsDir, { withFileTypes: true })
    .filter((entry) => entry.isFile() && !entry.name.endsWith('.example'))
    .map((entry) => fs.readFileSync(path.join(secretsDir, entry.name), 'utf8').trim())
    .filter((value) => value.length >= 8);
}

function assertNoSecretValue(bytes, where, secrets) {
  if (secrets.some((value) => bytes.includes(value))) {
    fail(`${where}에 infrastructure/secrets/의 비밀값이 들어 있다.`);
  }
}

// 묶음 폴더에 비밀값 파일이 없고, 이미지 tar가 아닌 파일에 비밀값이 없는지 확인한다.
// 이미지 tar의 layer는 압축되어 있어 여기서 찾지 못하므로 assertImagesHaveNoSecrets가 따로 본다.
function assertNoSecrets(dir, secrets) {
  for (const file of filesUnder(dir)) {
    const relative = path.relative(dir, file).split(path.sep).join('/');
    if (relative === '.env' || (relative.startsWith('secrets/') && !relative.endsWith('.example'))) {
      fail(`묶음에 비밀값 파일이 있다: ${relative}`);
    }
    if (!/\.tar(\.gz|\.bz2)?$/.test(relative)) {
      assertNoSecretValue(fs.readFileSync(file), relative, secrets);
    }
  }
}

// 이미지마다 파일 시스템을 압축 없는 tar로 내보내 비밀값이 들어 있지 않은지 확인한다.
function assertImagesHaveNoSecrets(images, secrets) {
  if (secrets.length === 0) {
    console.warn('경고: infrastructure/secrets/에 비밀값 파일이 없어 이미지 안의 비밀값 검사를 건너뛴다.');
    return;
  }
  console.log('이미지 안에 로컬 비밀값이 들어가지 않았는지 확인한다.');
  for (const image of images) {
    const container = run('docker', ['create', image], { capture: true });
    const exported = path.join(DIST, `.export-${process.pid}.tar`);
    // fail()은 프로세스를 바로 끝내므로, 검사 결과만 받아 두고 컨테이너와 임시 파일을 지운 뒤에 멈춘다.
    const exportResult = spawnSync('docker', ['export', '-o', exported, container], { stdio: 'inherit' });
    const leaked = exportResult.status === 0
      && secrets.some((value) => fs.readFileSync(exported).includes(value));
    fs.rmSync(exported, { force: true });
    spawnSync('docker', ['rm', container], { stdio: 'ignore' });
    if (exportResult.status !== 0) {
      fail(`${image}의 파일 시스템을 내보내지 못했다.`);
    }
    if (leaked) {
      fail(`${image}에 infrastructure/secrets/의 비밀값이 들어 있다.`);
    }
  }
}

async function buildCoreBundle(core) {
  const out = path.join(DIST, `monitoring-core-${core.describe}`);
  freshDir(out);

  console.log(`코어 이미지를 빌드한다(버전 ${core.describe}, ${PLATFORM}).`);
  run('docker', ['compose', '-f', CORE_COMPOSE, 'build', '--pull'], { env: composeEnv(core) });
  checkCoreImages(core);
  const images = CORE_IMAGES.map((name) => imageName(name, core));
  const secrets = localSecretValues();
  assertImagesHaveNoSecrets(images, secrets);

  console.log('이미지를 묶음 파일로 내보낸다.');
  run('docker', ['save', '-o', path.join(out, 'core-images.tar'), ...images]);

  writeText(path.join(out, 'compose.yml'), runtimeCompose(core));
  validateRuntimeCompose(out);
  writeText(path.join(out, '.env.example'), forBundle(fs.readFileSync(path.join(CORE_DIR, '.env.example'), 'utf8')));
  // token은 core-server.sh install이 설치 폴더에 만들므로 묶음에는 token 예시를 싣지 않는다.
  writeText(path.join(out, 'core-server.sh'), fs.readFileSync(path.join(CORE_DIR, 'core-server.sh'), 'utf8'));
  fs.chmodSync(path.join(out, 'core-server.sh'), 0o755);
  await addLicensesAndSources(out, images);

  assertNoSecrets(out, secrets);
  writeChecksums(out);
  return out;
}

// fetch는 실패 원인(연결 시간 초과, 이름 풀이 실패 등)을 cause에만 담으므로 함께 보인다.
function failureReason(error) {
  const cause = error.cause;
  return cause ? `${error.message} (${cause.code ?? cause.message})` : error.message;
}

// 주소를 차례로 시도하고, 모두 실패하면 처음부터 세 번까지 다시 돈다. 받은 파일은 부르는 쪽이 체크섬으로 확인한다.
async function download(urls, file) {
  for (let attempt = 1; ; attempt += 1) {
    for (const url of urls) {
      try {
        const response = await fetch(url, { signal: AbortSignal.timeout(300_000) });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        fs.writeFileSync(file, Buffer.from(await response.arrayBuffer()));
        return;
      }
      catch (error) {
        if (attempt >= 3 && url === urls.at(-1)) {
          fail(`${urls.join(', ')}에서 받지 못했다: ${failureReason(error)}`);
        }
        console.warn(`경고: ${url}을 받지 못했다(${attempt}/3): ${failureReason(error)}`);
      }
    }
  }
}

function busyboxVersion(image) {
  const result = spawnSync('docker', ['run', '--rm', '--entrypoint', '/bin/busybox', image],
    { cwd: ROOT, encoding: 'utf8' });
  const match = /BusyBox v(\d+\.\d+\.\d+)/.exec(`${result.stdout}${result.stderr}`);
  if (!match) {
    fail(`${image}에서 BusyBox 버전을 읽지 못했다.`);
  }
  return match[1];
}

// 라이선스 전문과 안내 파일을 싣고, 이미지에 든 BusyBox 버전의 소스를 받아 공식 체크섬과 대조해 싣는다.
async function addLicensesAndSources(out, images) {
  for (const name of fs.readdirSync(path.join(CORE_DIR, 'licenses'))) {
    copy(path.join(CORE_DIR, 'licenses', name), path.join(out, 'licenses', name));
  }
  const versions = [...new Set(images.map(busyboxVersion))].sort();
  for (const version of versions) {
    const expected = BUSYBOX_SOURCES[version];
    if (!expected) {
      fail(`이미지에 든 BusyBox ${version}의 소스 체크섬이 없다. BUSYBOX_SOURCES에 더한다.`);
    }
    const name = `busybox-${version}.tar.bz2`;
    const cached = path.join(CACHE, name);
    if (!fs.existsSync(cached) || sha256(cached) !== expected) {
      console.log(`BusyBox ${version} 소스를 받는다.`);
      fs.mkdirSync(CACHE, { recursive: true });
      await download(BUSYBOX_MIRRORS.map((mirror) => `${mirror}/${name}`), cached);
      if (sha256(cached) !== expected) {
        fs.rmSync(cached);
        fail(`${name}의 체크섬이 공식 값과 다르다. 받은 파일을 지웠다.`);
      }
    }
    copy(cached, path.join(out, 'sources', name));
  }
}

async function buildNodeExporterBundle(core) {
  const out = path.join(DIST, `node-exporter-${core.describe}`);
  freshDir(out);

  console.log(`node_exporter 이미지를 받는다(${NODE_EXPORTER.image}, ${PLATFORM}).`);
  run('docker', ['pull', '--platform', PLATFORM, NODE_EXPORTER.image]);
  const digests = run('docker', ['image', 'inspect', NODE_EXPORTER.image, '--format', '{{json .RepoDigests}}'],
    { capture: true });
  if (!digests.includes(NODE_EXPORTER.imageDigest)) {
    fail(`${NODE_EXPORTER.image}의 digest가 고정한 값과 다르다. 받은 값: ${digests}`);
  }
  run('docker', ['save', '-o', path.join(out, 'node-exporter-image.tar'), NODE_EXPORTER.image]);

  console.log(`node_exporter 실행 파일을 받아 공식 체크섬과 대조한다(${NODE_EXPORTER.archive}).`);
  const archive = path.join(out, NODE_EXPORTER.archive);
  await download([`https://github.com/prometheus/node_exporter/releases/download/v${NODE_EXPORTER.version}/${NODE_EXPORTER.archive}`],
    archive);
  if (sha256(archive) !== NODE_EXPORTER.archiveSha256) {
    fs.rmSync(archive);
    fail(`${NODE_EXPORTER.archive}의 체크섬이 공식 값과 다르다. 받은 파일을 지웠다.`);
  }

  for (const name of ['compose.yml', 'node_exporter.service']) {
    writeText(path.join(out, name), fs.readFileSync(path.join(CORE_DIR, 'node-exporter', name), 'utf8'));
  }
  await addLicensesAndSources(out, [NODE_EXPORTER.image]);

  assertNoSecrets(out, localSecretValues());
  writeChecksums(out);
  return out;
}

const CHECK_ONLY = process.argv.includes('--check');
const core = version({ allowDirty: CHECK_ONLY || process.argv.includes('--allow-dirty') });
if (CHECK_ONLY) {
  console.log(`코어 이미지를 빌드한다(버전 ${core.describe}, ${PLATFORM}).`);
  run('docker', ['compose', '-f', CORE_COMPOSE, 'build'], { env: composeEnv(core) });
  checkCoreImages(core);
  console.log('\n설정 검사와 규칙 테스트를 통과했다.');
  process.exit(0);
}
const coreOut = await buildCoreBundle(core);
const nodeOut = await buildNodeExporterBundle(core);
console.log('\n묶음을 만들었다. 두 폴더를 내부망 서버로 옮긴다.');
console.log(`  ${path.relative(ROOT, coreOut)}`);
console.log(`  ${path.relative(ROOT, nodeOut)}`);
