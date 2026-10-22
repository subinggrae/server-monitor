#!/usr/bin/env node
// 인터넷이 없는 내부망 서버로 옮길 코어 묶음과 node_exporter 묶음을 만든다. 의존성 없이 Node 18 이상에서 동작한다.
// 결정과 이유는 docs/decisions/0009-core-config-in-image.md, 옮겨 설치하는 방법은 docs/OPERATIONS.md에 있다.
//
//   node scripts/build-core-bundle.mjs
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

function version() {
  const describe = run('git', ['describe', '--tags', '--always', '--dirty'], { capture: true });
  const revision = run('git', ['rev-parse', 'HEAD'], { capture: true });
  if (describe.endsWith('-dirty')) {
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
}

// 묶음 폴더에서는 저장소의 infrastructure/ 아래 파일이 같은 폴더에 있으므로 안내 문구의 경로를 바꾼다.
function forBundle(text) {
  return text
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
    '# 설치 방법은 저장소의 docs/OPERATIONS.md에 있다. 이 파일은 고치지 않고, 바꿀 것은 .env와 secrets/에 둔다.',
    '#',
    '#   docker load -i core-images.tar',
    '#   docker compose up -d',
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
    out.push(line.replaceAll('${CORE_VERSION:-dev}', core.describe));
  }
  const text = `${out.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd()}\n`;
  if (/^\s+build:/m.test(text) || text.includes('CORE_VERSION') || text.includes('x-core-build-args')) {
    fail('실행용 compose 파일에 빌드 항목이 남았다. infrastructure/compose.yml의 형식이 바뀌었는지 확인한다.');
  }
  return text;
}

// 비밀값 파일이 묶음에 섞이지 않았는지, 로컬 비밀값이 이미지 묶음 안에 들어가지 않았는지 확인한다.
function assertNoSecrets(dir) {
  for (const file of filesUnder(dir)) {
    const relative = path.relative(dir, file).split(path.sep).join('/');
    if (relative === '.env' || (relative.startsWith('secrets/') && !relative.endsWith('.example'))) {
      fail(`묶음에 비밀값 파일이 있다: ${relative}`);
    }
  }
  const secretsDir = path.join(CORE_DIR, 'secrets');
  const secrets = fs.existsSync(secretsDir)
    ? fs.readdirSync(secretsDir)
      .filter((name) => !name.endsWith('.example'))
      .map((name) => fs.readFileSync(path.join(secretsDir, name), 'utf8').trim())
      .filter((value) => value.length >= 8)
    : [];
  for (const file of filesUnder(dir)) {
    const bytes = fs.readFileSync(file);
    for (const value of secrets) {
      if (bytes.includes(value)) {
        // 값은 출력하지 않는다.
        fail(`${path.relative(dir, file)}에 infrastructure/secrets/의 비밀값이 들어 있다.`);
      }
    }
  }
}

function buildCoreBundle(core) {
  const out = path.join(DIST, `monitoring-core-${core.describe}`);
  freshDir(out);

  console.log(`코어 이미지를 빌드한다(버전 ${core.describe}, ${PLATFORM}).`);
  run('docker', ['compose', '-f', CORE_COMPOSE, 'build', '--pull'], { env: composeEnv(core) });
  checkCoreImages(core);

  console.log('이미지를 묶음 파일로 내보낸다.');
  run('docker', ['save', '-o', path.join(out, 'core-images.tar'), ...CORE_IMAGES.map((name) => imageName(name, core))]);

  fs.writeFileSync(path.join(out, 'compose.yml'), runtimeCompose(core));
  fs.writeFileSync(path.join(out, '.env.example'), forBundle(fs.readFileSync(path.join(CORE_DIR, '.env.example'), 'utf8')));
  copy(path.join(CORE_DIR, 'secrets', 'alertmanager_webhook_token.example'),
    path.join(out, 'secrets', 'alertmanager_webhook_token.example'));
  for (const name of fs.readdirSync(path.join(CORE_DIR, 'licenses'))) {
    copy(path.join(CORE_DIR, 'licenses', name), path.join(out, 'licenses', name));
  }

  assertNoSecrets(out);
  writeChecksums(out);
  return out;
}

async function download(url, file) {
  const response = await fetch(url);
  if (!response.ok) {
    fail(`${url}을 받지 못했다: HTTP ${response.status}`);
  }
  fs.writeFileSync(file, Buffer.from(await response.arrayBuffer()));
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
  await download(`https://github.com/prometheus/node_exporter/releases/download/v${NODE_EXPORTER.version}/${NODE_EXPORTER.archive}`,
    archive);
  if (sha256(archive) !== NODE_EXPORTER.archiveSha256) {
    fs.rmSync(archive);
    fail(`${NODE_EXPORTER.archive}의 체크섬이 공식 값과 다르다. 받은 파일을 지웠다.`);
  }

  copy(path.join(CORE_DIR, 'node-exporter', 'compose.yml'), path.join(out, 'compose.yml'));
  copy(path.join(CORE_DIR, 'node-exporter', 'node_exporter.service'), path.join(out, 'node_exporter.service'));

  assertNoSecrets(out);
  writeChecksums(out);
  return out;
}

const core = version();
const coreOut = buildCoreBundle(core);
const nodeOut = await buildNodeExporterBundle(core);
console.log('\n묶음을 만들었다. 두 폴더를 내부망 서버로 옮긴다.');
console.log(`  ${path.relative(ROOT, coreOut)}`);
console.log(`  ${path.relative(ROOT, nodeOut)}`);
