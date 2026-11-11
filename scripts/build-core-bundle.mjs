#!/usr/bin/env node
// 인터넷이 없는 내부망 서버로 옮길 코어 묶음과 node_exporter 묶음을 만든다. 의존성 없이 Node 18 이상에서 동작한다.
// 결정과 이유는 docs/decisions/0009-core-config-in-image.md와 0012-core-render-outside-image.md, 옮겨 설치하는 방법은 docs/OPERATIONS.md에 있다.
//
//   node scripts/build-core-bundle.mjs           이미지를 빌드해 검사하고 묶음을 만든다
//   node scripts/build-core-bundle.mjs --check   이미지를 빌드해 설정 검사와 규칙 테스트만 한다(묶음을 만들지 않음)
//
// 옵션: --allow-dirty  커밋하지 않은 변경이 있어도 시험용 묶음을 만든다(버전 끝에 -dirty가 붙음)
//
// 저장소 최상위에서 실행하고, Docker와 인터넷이 필요하다. 결과는 dist/monitoring-core-<버전>/, dist/node-exporter-<버전>/에 생긴다.
// 운영 이미지에는 셸과 도구가 없으므로, 설정 검사는 Dockerfile의 첫 단계(src, 공식 이미지)에 든 promtool, amtool, 셸로 한다.
// 종료 코드: 빌드, 검사, 비밀값 확인 가운데 하나라도 실패하면 1.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';

const ROOT = process.cwd();
const CORE_DIR = path.join(ROOT, 'infrastructure');
const CORE_COMPOSE = path.join(CORE_DIR, 'compose.yml');
const NODE_EXPORTER_DIR = path.join(CORE_DIR, 'node-exporter');
const NODE_EXPORTER_COMPOSE = path.join(NODE_EXPORTER_DIR, 'compose.yml');
const LICENSES_DIR = path.join(CORE_DIR, 'licenses');
const DIST = path.join(ROOT, 'dist');

// 내부망 서버는 x86-64만 쓴다(요구사항 0010).
const PLATFORM = 'linux/amd64';

const CORE_IMAGES = ['prometheus', 'alertmanager', 'blackbox-exporter'];

// 설정 검사에 쓰는 앱 주소다. 빌드한 이미지에는 들어가지 않고, 서버에서 펼칠 때 진짜 주소를 쓴다.
const CHECK_APP_ADDRESS = 'manager-api:8080';

// node_exporter 묶음에 싣는 Docker가 없는 서버용 실행 파일의 버전과 공식 릴리스 값이다.
// 이미지는 infrastructure/node-exporter/Dockerfile이 고정하므로, 버전을 올릴 때 그 Dockerfile과 함께 바꾼다.
const NODE_EXPORTER = {
  version: '1.12.1',
  archive: 'node_exporter-1.12.1.linux-amd64.tar.gz',
  // https://github.com/prometheus/node_exporter/releases/download/v1.12.1/sha256sums.txt 의 값이다(2026-10-22 확인).
  archiveSha256: 'b51d8a76aa2a9156a55d501aca6276fae09e262259a5e4e831d2c2222f084e63',
};

// 묶음마다 싣는 라이선스 파일이다. 묶음에 든 프로그램의 것만 싣는다(요구사항 0015). README는 묶음의 licenses/README.txt가 된다.
const BUNDLE_LICENSES = {
  core: {
    readme: 'README.txt',
    files: ['prometheus-LICENSE', 'prometheus-NOTICE', 'alertmanager-LICENSE', 'alertmanager-NOTICE',
      'blackbox-exporter-LICENSE', 'blackbox-exporter-NOTICE'],
  },
  nodeExporter: { readme: 'node-exporter-README.txt', files: ['node-exporter-LICENSE', 'node-exporter-NOTICE'] },
};

// Docker가 컨테이너를 만들 때 넣는 파일과 폴더다. 이미지 파일 목록을 docker export로 읽으므로 함께 나온다.
const DOCKER_MADE_FILES = ['.dockerenv', 'dev/', 'dev/console', 'dev/pts/', 'dev/shm/', 'etc/', 'etc/hostname', 'etc/hosts',
  'etc/mtab', 'etc/resolv.conf', 'proc/', 'sys/'];

const NOBODY = '65534:65534';

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
    maxBuffer: 64 * 1024 * 1024,
  });
  if (result.error) {
    fail(`${command}를 실행하지 못했습니다: ${result.error.message}`);
  }
  if (result.status !== 0) {
    fail(`명령이 실패했습니다(종료 코드 ${result.status}): ${command} ${args.join(' ')}`);
  }
  return capture ? result.stdout.trim() : '';
}

// 실패해도 멈추지 않고 결과를 돌려준다.
function runResult(command, args, { env = {} } = {}) {
  const result = spawnSync(command, args, { cwd: ROOT, env: { ...process.env, ...env }, encoding: 'utf8' });
  if (result.error) {
    fail(`${command}를 실행하지 못했습니다: ${result.error.message}`);
  }
  return result;
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

// 검사에 쓰는 임시 폴더는 Docker Desktop이 붙일 수 있도록 dist/ 안에 만든다. 실패로 멈춰도 끝날 때 지운다.
const scratchDirs = new Set();
process.on('exit', () => {
  for (const dir of scratchDirs) {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});

function scratchDir(name) {
  const dir = path.join(DIST, `.${name}-${process.pid}`);
  freshDir(dir);
  scratchDirs.add(dir);
  return dir;
}

// 컨테이너가 붙인 폴더에 쓴 파일을 빌드 PC의 사용자가 지울 수 있게, Linux와 macOS에서는 같은 사용자로 실행한다.
function hostUser() {
  return process.getuid ? `${process.getuid()}:${process.getgid()}` : '0:0';
}

// 같은 -dirty 태그가 내용이 다른 이미지에 붙으면 태그로 커밋을 알 수 없고 되돌리기도 깨진다.
// 그래서 묶음은 커밋한 상태에서만 만들고, 검사(--check)와 --allow-dirty만 커밋하지 않은 변경을 허용한다.
function version({ allowDirty }) {
  const describe = run('git', ['describe', '--tags', '--always', '--dirty'], { capture: true });
  const revision = run('git', ['rev-parse', 'HEAD'], { capture: true });
  const commitTime = run('git', ['show', '-s', '--format=%ct', 'HEAD'], { capture: true });
  if (describe.endsWith('-dirty')) {
    if (!allowDirty) {
      fail('커밋하지 않은 변경이 있습니다. 커밋한 뒤 다시 실행해 주세요. 시험용 묶음만 만들려면 --allow-dirty를 붙여 주세요.');
    }
    console.warn('경고: 커밋하지 않은 변경이 있습니다. 버전 끝에 -dirty가 붙고, 이 커밋만으로는 같은 이미지를 다시 만들 수 없습니다.');
  }
  return { describe, revision, commitTime };
}

function composeEnv(core) {
  return {
    CORE_VERSION: core.describe,
    CORE_REVISION: core.revision,
    CORE_COMMIT_TIME: core.commitTime,
    DOCKER_DEFAULT_PLATFORM: PLATFORM,
  };
}

function imageName(name, core) {
  return `monitoring-core-${name}:${core.describe}`;
}

function nodeExporterImage(core) {
  return `monitoring-node-exporter:${core.describe}`;
}

// Dockerfile의 첫 단계(src)만 빌드해 공식 이미지를 돌려준다. 운영 이미지와 같은 digest의 도구로 검사하기 위해서다.
function sourceImage(dockerfile, context) {
  return run('docker', ['build', '-q', '--platform', PLATFORM, '--target', 'src', '-f', dockerfile, context], { capture: true });
}

// --- 운영 이미지의 파일과 설정 ---

// docker export가 내보낸 tar에서 항목의 이름, 종류, 소유자를 읽는다. 긴 이름의 pax·GNU 머리도 읽는다.
function tarEntries(buffer) {
  const entries = [];
  let offset = 0;
  let longName = null;
  while (offset + 512 <= buffer.length) {
    const header = buffer.subarray(offset, offset + 512);
    if (header.every((byte) => byte === 0)) {
      break;
    }
    const field = (start, length) => header.toString('utf8', start, start + length).replace(/\0[\s\S]*$/, '');
    const octal = (start, length) => parseInt(field(start, length).trim() || '0', 8);
    const size = octal(124, 12);
    const type = header[156] === 0 ? '0' : String.fromCharCode(header[156]);
    const body = buffer.subarray(offset + 512, offset + 512 + size);
    offset += 512 + Math.ceil(size / 512) * 512;
    if (type === 'x' || type === 'L') {
      longName = type === 'L' ? body.toString('utf8').replace(/\0[\s\S]*$/, '') : (/\d+ path=([^\n]*)\n/.exec(body.toString('utf8')) ?? [])[1] ?? null;
      continue;
    }
    if (type === 'g') {
      continue;
    }
    const prefix = field(345, 155);
    const name = longName ?? (prefix ? `${prefix}/${field(0, 100)}` : field(0, 100));
    longName = null;
    entries.push({ name: name.replace(/^\.\//, ''), type, owner: `${octal(108, 8)}:${octal(116, 8)}` });
  }
  return entries;
}

// 이미지로 컨테이너를 만들어(실행하지 않음) 파일 시스템을 압축 없는 tar로 내보낸다.
function exportImage(image) {
  const container = run('docker', ['create', image], { capture: true });
  const exported = path.join(DIST, `.export-${process.pid}.tar`);
  fs.mkdirSync(DIST, { recursive: true });
  // fail()은 프로세스를 바로 끝내므로, 결과만 받아 두고 컨테이너와 임시 파일을 지운 뒤에 멈춘다.
  const result = spawnSync('docker', ['export', '-o', exported, container], { stdio: 'inherit' });
  const bytes = result.status === 0 ? fs.readFileSync(exported) : null;
  fs.rmSync(exported, { force: true });
  spawnSync('docker', ['rm', container], { stdio: 'ignore' });
  if (!bytes) {
    fail(`${image}의 파일 시스템을 내보내지 못했습니다.`);
  }
  return bytes;
}

function ruleFiles() {
  return fs.readdirSync(path.join(CORE_DIR, 'prometheus', 'rules')).filter((name) => name.endsWith('.yml')).sort();
}

// 운영 이미지마다 담는 것과 이미지 설정이다(요구사항 0015의 C1, C2). 실행 사용자, 작업 폴더, 포트, volume, 실행 인자는
// 공식 이미지에서 읽은 값과 같고, 설정 파일 위치만 펼친 설정을 붙이는 /etc/core/로 바뀐다.
// 작업 폴더가 없던 공식 이미지("")는 빈 이미지에서 "/"로 보이고, 컨테이너의 작업 폴더는 둘 다 /다.
function imageSpecs(core) {
  const labels = (title, commitTime = true) => ({
    'org.opencontainers.image.title': title,
    'org.opencontainers.image.version': core.describe,
    'org.opencontainers.image.revision': core.revision,
    'org.opencontainers.image.source': 'https://github.com/subinggrae/server-monitor',
    ...(commitTime ? { 'monitoring-core.commit-time': core.commitTime } : {}),
  });
  return {
    [imageName('prometheus', core)]: {
      files: ['bin/', 'bin/prometheus', 'etc/prometheus/', 'etc/prometheus/rules/',
        ...ruleFiles().map((name) => `etc/prometheus/rules/${name}`), 'prometheus/'],
      owners: { 'prometheus/': NOBODY },
      config: { User: NOBODY, WorkingDir: '/prometheus', ExposedPorts: ['9090/tcp'], Volumes: ['/prometheus'],
        Entrypoint: ['/bin/prometheus'], Cmd: ['--config.file=/etc/core/prometheus.yml', '--storage.tsdb.path=/prometheus'] },
      labels: labels('monitoring-core-prometheus'),
    },
    [imageName('alertmanager', core)]: {
      files: ['bin/', 'bin/alertmanager', 'alertmanager/'],
      owners: { 'alertmanager/': NOBODY },
      config: { User: NOBODY, WorkingDir: '/alertmanager', ExposedPorts: ['9093/tcp'], Volumes: ['/alertmanager'],
        Entrypoint: ['/bin/alertmanager'],
        Cmd: ['--config.file=/etc/core/alertmanager.yml', '--storage.path=/alertmanager', '--enable-feature=utf8-strict-mode'] },
      labels: labels('monitoring-core-alertmanager'),
    },
    [imageName('blackbox-exporter', core)]: {
      files: ['bin/', 'bin/blackbox_exporter'],
      owners: {},
      config: { User: '0:0', WorkingDir: '/', ExposedPorts: ['9115/tcp'], Volumes: [],
        Entrypoint: ['/bin/blackbox_exporter'], Cmd: ['--config.file=/etc/core/blackbox.yml'] },
      labels: labels('monitoring-core-blackbox-exporter'),
    },
    [nodeExporterImage(core)]: {
      files: ['bin/', 'bin/node_exporter'],
      owners: {},
      config: { User: NOBODY, WorkingDir: '/', ExposedPorts: ['9100/tcp'], Volumes: [], Entrypoint: ['/bin/node_exporter'], Cmd: [] },
      labels: labels('monitoring-node-exporter', false),
    },
  };
}

// 이미지의 파일이 정해진 것뿐인지 본다. 셸, BusyBox, promtool, amtool, CA 묶음, 시간대 자료 등이 들어가면 실패한다.
// 내보낸 파일 시스템은 비밀값 검사에도 쓰도록 돌려준다.
function checkImageFiles(image, spec) {
  const bytes = exportImage(image);
  const entries = tarEntries(bytes);
  const allowed = new Set([...spec.files, ...DOCKER_MADE_FILES]);
  const extra = entries.filter((entry) => !allowed.has(entry.name)).map((entry) => entry.name);
  if (extra.length > 0) {
    fail(`${image}에 정해진 파일 밖의 것이 있습니다: ${extra.slice(0, 20).join(', ')}${extra.length > 20 ? ` 외 ${extra.length - 20}개` : ''}`);
  }
  const names = new Set(entries.map((entry) => entry.name));
  const missing = spec.files.filter((name) => !names.has(name));
  if (missing.length > 0) {
    fail(`${image}에 있어야 할 파일이 없습니다: ${missing.join(', ')}`);
  }
  for (const entry of entries.filter((item) => spec.files.includes(item.name))) {
    const expected = spec.owners[entry.name] ?? '0:0';
    if (entry.owner !== expected) {
      fail(`${image}의 ${entry.name} 소유자가 ${entry.owner}입니다. 기대: ${expected}. 새 volume에 기록을 쓰지 못할 수 있습니다.`);
    }
  }
  return bytes;
}

function checkImageConfig(image, spec) {
  const config = JSON.parse(run('docker', ['image', 'inspect', '-f', '{{json .Config}}', image], { capture: true }));
  const found = {
    User: config.User ?? '',
    WorkingDir: config.WorkingDir ?? '',
    ExposedPorts: Object.keys(config.ExposedPorts ?? {}).sort(),
    Volumes: Object.keys(config.Volumes ?? {}).sort(),
    Entrypoint: config.Entrypoint ?? [],
    Cmd: config.Cmd ?? [],
  };
  for (const [key, expected] of Object.entries(spec.config)) {
    if (JSON.stringify(found[key]) !== JSON.stringify(expected)) {
      fail(`${image}의 ${key}가 기대와 다릅니다. 받은 값: ${JSON.stringify(found[key])}, 기대: ${JSON.stringify(expected)}`);
    }
  }
  for (const [key, expected] of Object.entries(spec.labels)) {
    if ((config.Labels ?? {})[key] !== expected) {
      fail(`${image}의 label ${key}가 기대와 다릅니다. 받은 값: ${JSON.stringify((config.Labels ?? {})[key])}, 기대: ${JSON.stringify(expected)}`);
    }
  }
}

// 이미지마다 파일 목록과 설정을 보고, 내보낸 파일 시스템을 돌려준다.
function checkImages(images, specs) {
  console.log('운영 이미지에 정해진 파일만 있고 실행 사용자, 포트, 실행 인자, label이 기대와 같은지 확인합니다.');
  return Object.fromEntries(images.map((image) => {
    const bytes = checkImageFiles(image, specs[image]);
    checkImageConfig(image, specs[image]);
    return [image, bytes];
  }));
}

// --- 설정 검사 ---

// 코어 서버의 .env에서 바꾸는 값이다(요구사항 0013). 허용 범위는 저장소의 core-settings.sh에서 읽는다.
const CORE_SETTING_NAMES = ['CORE_RETENTION_DAYS', 'CORE_RETENTION_SIZE_GB', 'CORE_PROBE_TIMEOUT_SECONDS',
  'CORE_PROMETHEUS_PORT', 'CORE_ALERTMANAGER_PORT', 'CORE_BLACKBOX_PORT'];
// 호스트 포트 값마다 그 포트가 묶이는 컨테이너 안 포트다. 컨테이너 안 포트는 바뀌지 않는다.
const HOST_PORT_TARGETS = { CORE_PROMETHEUS_PORT: 9090, CORE_ALERTMANAGER_PORT: 9093, CORE_BLACKBOX_PORT: 9115 };
// Blackbox는 module의 timeout과 scrape_timeout에서 이 값(--timeout-offset 기본값)을 뺀 값 가운데 짧은 쪽으로 확인한다.
const BLACKBOX_TIMEOUT_OFFSET_SECONDS = 0.5;
// 포트 번호의 범위다. 1024 아래는 시스템이 쓰는 포트다.
const PORT_RANGE = { min: 1024, max: 65535 };
// compose 파일이 붙이는 파일이다. CA 인증서 파일은 HTTPS 확인을 하는 Blackbox Exporter에만 붙인다(요구사항 0015).
// CA 인증서 파일은 운영자가 복사해 두는 곳(caDir: 저장소는 certs, 묶음은 설치 폴더의 ../certs)에서 붙인다.
function renderedMounts(caDir) {
  return {
    prometheus: { 'rendered/prometheus.yml': '/etc/core/prometheus.yml' },
    alertmanager: { 'rendered/alertmanager.yml': '/etc/core/alertmanager.yml' },
    'blackbox-exporter': { 'rendered/blackbox.yml': '/etc/core/blackbox.yml',
      [`${caDir}/ca-certificates.crt`]: '/etc/ssl/certs/ca-certificates.crt' },
  };
}

function readSettingRanges(tools) {
  const out = run('docker', ['run', '--rm', '-v', `${CORE_DIR}:/src:ro`, '--entrypoint', '/bin/sh', tools.prometheus, '-c',
    '. /src/core-settings.sh && core_settings_ranges'], { capture: true });
  const ranges = out.split(/\r?\n/).map((line) => {
    const [name, defaultValue, min, max] = line.split('|');
    return { name, defaultValue, min: Number(min), max: Number(max) };
  });
  const names = ranges.map((range) => range.name).sort();
  if (names.join() !== [...CORE_SETTING_NAMES].sort().join()) {
    fail(`core-settings.sh의 값 목록(${names.join(', ')})이 이 스크립트의 CORE_SETTING_NAMES와 다릅니다. 두 곳을 함께 고쳐 주세요.`);
  }
  return ranges;
}

// 기본값, 허용 범위 아래 끝, 위 끝의 세 벌이다. 상한이 없는 보관 용량은 받는 가장 큰 값(9자리)을 위 끝으로 쓴다.
// 호스트 포트는 세 값이 같으면 겹친다고 거부되므로 포트끼리 1씩 다르게 둔다.
function settingSets(ranges) {
  const pick = (choose) => Object.fromEntries(ranges.map((range, index) => [range.name, choose(range, index)]));
  const portOffset = (range) => Object.keys(HOST_PORT_TARGETS).indexOf(range.name);
  return [
    { label: '값을 모두 비움', values: pick(() => '') },
    { label: '허용 범위 아래 끝', values: pick((range) => String(range.min + Math.max(0, portOffset(range)))) },
    { label: '허용 범위 위 끝', values: pick((range) => String(range.max - Math.max(0, portOffset(range)))) },
  ];
}

function effective(ranges, values, name) {
  return values[name] === '' ? ranges.find((range) => range.name === name).defaultValue : values[name];
}

// Prometheus 기간 표기(예: 10s, 1m30s, 15d)를 초로 바꾼다.
function durationSeconds(text) {
  const units = { ms: 0.001, s: 1, m: 60, h: 3600, d: 86400, w: 604800, y: 31536000 };
  const parts = [...text.matchAll(/(\d+)(ms|s|m|h|d|w|y)/g)];
  if (parts.length === 0 || parts.map((part) => part[0]).join('') !== text) {
    fail(`기간 표기를 읽지 못했습니다: ${text}`);
  }
  return parts.reduce((sum, [, amount, unit]) => sum + Number(amount) * units[unit], 0);
}

// 코어 서버와 같은 운영 명령(core-server.sh render)으로 설정을 펼친다. 저장소 배치를 본뜬 임시 폴더에서 공식 이미지의 셸로 실행한다.
// token과 CA 인증서 파일은 검사용 값이고 비밀값이 아니다.
function renderConfigs(tools, values) {
  const work = scratchDir('render-check');
  for (const name of ['core-server.sh', 'core-settings.sh', 'prometheus/prometheus.yml', 'alertmanager/alertmanager.yml',
    'blackbox/blackbox.yml']) {
    copy(path.join(CORE_DIR, name), path.join(work, name));
  }
  const env = Object.entries({ MONITORING_APP_ADDRESS: CHECK_APP_ADDRESS, ...values }).map(([key, value]) => `${key}=${value}`);
  writeText(path.join(work, '.env'), `${env.join('\n')}\n`);
  writeText(path.join(work, 'secrets', 'alertmanager_webhook_token'), 'build-check-token\n');
  fs.chmodSync(path.join(work, 'secrets', 'alertmanager_webhook_token'), 0o644);
  writeText(path.join(work, 'certs', 'ca-certificates.crt'), 'build-check-ca\n');
  fs.chmodSync(path.join(work, 'certs', 'ca-certificates.crt'), 0o644);
  run('docker', ['run', '--rm', '--user', hostUser(), '-v', `${work}:/work`, '-w', '/work',
    '--entrypoint', '/bin/sh', tools.prometheus, 'core-server.sh', 'render'], { capture: true });
  const rendered = path.join(work, 'rendered');
  return { dir: rendered, work, read: (name) => fs.readFileSync(path.join(rendered, name), 'utf8') };
}

// 펼친 prometheus.yml의 보관 항목(storage.tsdb.retention)이 보관 기간과 용량인지 본다. 용량이 비면 0B(제한 없음)다.
function checkPrometheusRetention(rendered, ranges, set) {
  const text = rendered.read('prometheus.yml');
  const size = effective(ranges, set.values, 'CORE_RETENTION_SIZE_GB');
  const expected = { time: `${effective(ranges, set.values, 'CORE_RETENTION_DAYS')}d`, size: size === '' ? '0B' : `${size}GB` };
  const found = /^storage:\s*\n\s+tsdb:\s*\n\s+retention:\s*\n\s+time:\s*(\S+)\s*\n\s+size:\s*(\S+)\s*$/m.exec(text);
  if (!found || (text.match(/^\s+retention:/gm) ?? []).length !== 1 || found[1] !== expected.time || found[2] !== expected.size) {
    fail(`${set.label}: 펼친 prometheus.yml의 보관 항목이 time ${expected.time}, size ${expected.size}가 아닙니다. 받은 값: ${found ? `${found[1]}, ${found[2]}` : '찾지 못함'}`);
  }
}

// 펼친 Blackbox 설정의 module마다 timeout이 확인 제한 시간인지 본다. 인증서 확인을 끄는 항목도 없어야 한다.
function checkBlackboxConfig(rendered, ranges, set) {
  const text = rendered.read('blackbox.yml');
  const modules = (text.match(/^\s+prober:/gm) ?? []).length;
  const timeouts = [...text.matchAll(/^\s+timeout:\s*(\S+)\s*$/gm)].map((match) => match[1]);
  const expected = `${effective(ranges, set.values, 'CORE_PROBE_TIMEOUT_SECONDS')}s`;
  if (modules === 0 || timeouts.length !== modules || timeouts.some((timeout) => timeout !== expected)) {
    fail(`${set.label}: 펼친 Blackbox 설정의 timeout이 module마다 ${expected}가 아닙니다. 받은 값: ${timeouts.join(', ')}`);
  }
  if (/insecure_skip_verify/.test(text)) {
    fail(`${set.label}: 펼친 Blackbox 설정에 인증서 확인을 끄는 insecure_skip_verify가 있습니다.`);
  }
}

// 펼친 core.env가 검사를 통과한 값을 컨테이너에 넘기는지 본다. status와 보관 값 줄임 확인이 이 값을 읽는다.
function checkCoreEnv(rendered, ranges, set) {
  const text = rendered.read('core.env');
  for (const name of ['CORE_RETENTION_DAYS', 'CORE_RETENTION_SIZE_GB', 'CORE_PROBE_TIMEOUT_SECONDS']) {
    if (!text.split('\n').includes(`${name}=${effective(ranges, set.values, name)}`)) {
      fail(`${set.label}: 펼친 core.env에 ${name}=${effective(ranges, set.values, name)}이 없습니다.`);
    }
  }
}

// promtool과 amtool이 모르는 약속을 본다. 허용 범위가 이 약속을 깨면 코어가 검사를 통과한 채로 잘못 돈다.
function checkSettingContracts(rendered, rulesDir, ranges) {
  const range = (name) => ranges.find((item) => item.name === name);
  const scrapeTimeout = /^global:\s*$[\s\S]*?^\s+scrape_timeout:\s*(\S+)/m.exec(rendered.read('prometheus.yml'));
  if (!scrapeTimeout) {
    fail('펼친 prometheus.yml에서 전역 scrape_timeout을 찾지 못했습니다.');
  }
  const probeLimit = durationSeconds(scrapeTimeout[1]) - BLACKBOX_TIMEOUT_OFFSET_SECONDS;
  if (range('CORE_PROBE_TIMEOUT_SECONDS').max > probeLimit) {
    fail(`주소 확인 제한 시간의 상한(${range('CORE_PROBE_TIMEOUT_SECONDS').max}초)이 scrape_timeout(${scrapeTimeout[1]})에서 ${BLACKBOX_TIMEOUT_OFFSET_SECONDS}초를 뺀 ${probeLimit}초보다 깁니다.`);
  }
  const rules = fs.readdirSync(rulesDir).map((name) => fs.readFileSync(path.join(rulesDir, name), 'utf8')).join('\n');
  const longestRange = Math.max(0, ...[...rules.matchAll(/\[((?:\d+(?:ms|s|m|h|d|w|y))+)(?::[^\]]*)?\]/g)].map((match) => durationSeconds(match[1])));
  if (range('CORE_RETENTION_DAYS').min * 86400 <= longestRange) {
    fail(`보관 기간의 하한(${range('CORE_RETENTION_DAYS').min}일)이 규칙이 읽는 가장 긴 범위(${longestRange}초)보다 길지 않습니다.`);
  }
  const ports = ranges.filter((item) => HOST_PORT_TARGETS[item.name]);
  for (const port of ports) {
    if (port.min < PORT_RANGE.min || port.max > PORT_RANGE.max) {
      fail(`${port.name}의 허용 범위(${port.min}~${port.max})가 ${PORT_RANGE.min}~${PORT_RANGE.max} 밖입니다.`);
    }
  }
  if (new Set(ports.map((port) => port.defaultValue)).size !== ports.length) {
    fail('세 포트의 기본값 가운데 같은 값이 있습니다.');
  }
}

// 펼친 core.env에 넣는 표시 값이다. Compose가 env_file을 펼친 결과에서 서비스마다 이 값을 받는지 본다.
const COMPOSE_CHECK_MARK = 'CORE_CONFIG_SHA256=compose-check';

// Compose가 펼친 compose 파일을 읽는다. env_file(rendered/core.env, required: true)이 없으면 Compose가 읽지 못하므로
// 표시 값만 든 core.env를 둔 임시 폴더를 project directory로 준다. 상대 경로는 그 폴더 기준으로 풀린다.
// withEnvFile이 false면 core.env 없이 읽어 실패한 결과를 돌려준다.
function composeConfig(composeFile, env, { withEnvFile = true } = {}) {
  const project = scratchDir('compose-check');
  if (withEnvFile) {
    writeText(path.join(project, 'rendered', 'core.env'), `${COMPOSE_CHECK_MARK}
`);
  }
  const result = runResult('docker', ['compose', '--project-directory', project, '-f', composeFile, 'config', '--format', 'json'],
    { env });
  fs.rmSync(project, { recursive: true, force: true });
  if (!withEnvFile) {
    return { result };
  }
  if (result.status !== 0) {
    fail(`${composeFile}을 Compose가 읽지 못했습니다: ${result.stderr.trim()}`);
  }
  return { config: JSON.parse(result.stdout), project };
}

// Compose가 펼친 모든 ports의 host_ip가 127.0.0.1이고 호스트 포트가 설정 값인지 본다.
// 운영 명령 없이 띄울 때는 포트 범위를 검사하지 않으므로(요구사항 0013의 S11) 바인딩 주소만은 파일이 지킨다.
function checkPortBindings(composeFile, ranges) {
  const portRanges = ranges.filter((range) => HOST_PORT_TARGETS[range.name]);
  for (const set of settingSets(portRanges)) {
    const { config } = composeConfig(composeFile, set.values);
    const ports = Object.values(config.services).flatMap((service) => service.ports ?? []);
    const expected = Object.fromEntries(portRanges.map((range) =>
      [HOST_PORT_TARGETS[range.name], effective(portRanges, set.values, range.name)]));
    const published = Object.fromEntries(ports.map((port) => [port.target, String(port.published)]));
    if (ports.some((port) => port.host_ip !== '127.0.0.1')) {
      fail(`${set.label}: ${composeFile}에 127.0.0.1이 아닌 주소에 여는 포트가 있습니다: ${JSON.stringify(ports)}`);
    }
    if (ports.length !== portRanges.length || JSON.stringify(published, Object.keys(expected).sort())
      !== JSON.stringify(expected, Object.keys(expected).sort())) {
      fail(`${set.label}: ${composeFile}의 호스트 포트가 기대와 다릅니다. 받은 값: ${JSON.stringify(published)}, 기대: ${JSON.stringify(expected)}`);
    }
  }
}

// 서비스마다 붙이는 파일이 운영 명령이 펼친 설정뿐이고 읽기 전용인지, 세 서비스가 펼친 core.env를 읽는지 본다.
// CA 인증서 파일은 Blackbox Exporter에만 붙어야 한다(요구사항 0015의 C5). core.env가 없으면 Compose가 멈춰야 한다(S16).
function checkRenderedMounts(composeFile, caDir) {
  const { config, project } = composeConfig(composeFile, {});
  for (const [name, expected] of Object.entries(renderedMounts(caDir))) {
    const service = config.services[name];
    const binds = (service.volumes ?? []).filter((volume) => volume.type === 'bind');
    const found = Object.fromEntries(binds.map((volume) =>
      [path.relative(project, volume.source).split(path.sep).join('/'), volume.target]));
    if (JSON.stringify(found, Object.keys(found).sort()) !== JSON.stringify(expected, Object.keys(expected).sort())
      || binds.some((volume) => volume.read_only !== true)) {
      fail(`${composeFile}의 ${name}가 붙이는 파일이 기대와 다릅니다. 받은 값: ${JSON.stringify(binds)}, 기대(읽기 전용): ${JSON.stringify(expected)}`);
    }
    const [key, value] = COMPOSE_CHECK_MARK.split('=');
    if ((service.environment ?? {})[key] !== value) {
      fail(`${composeFile}의 ${name}가 펼친 rendered/core.env를 env_file로 읽지 않습니다.`);
    }
    if (service.healthcheck && service.healthcheck.disable !== true) {
      fail(`${composeFile}의 ${name}에 healthcheck가 있습니다. 코어 이미지에는 healthcheck를 실행할 명령이 없습니다.`);
    }
  }
  const { result } = composeConfig(composeFile, {}, { withEnvFile: false });
  if (result.status === 0 || !result.stderr.includes('core.env')) {
    fail(`${composeFile}을 펼친 설정(rendered/core.env) 없이 Compose가 읽었습니다. env_file에 required: true가 있어야 Docker가 빈 폴더를 만들지 않습니다.`);
  }
}

// 운영 이미지에 든 규칙을 꺼낸다. 셸 없이 컨테이너를 만들고(실행하지 않음) docker cp로 꺼내, 이미지에 든 규칙을 그대로 검사한다.
function extractRules(prometheus) {
  const dir = scratchDir('rules-check');
  const container = run('docker', ['create', prometheus], { capture: true });
  const result = spawnSync('docker', ['cp', `${container}:/etc/prometheus/rules`, dir], { stdio: 'inherit' });
  spawnSync('docker', ['rm', container], { stdio: 'ignore' });
  if (result.status !== 0) {
    fail(`${prometheus}에서 규칙을 꺼내지 못했습니다.`);
  }
  return { root: dir, rules: path.join(dir, 'rules') };
}

function checkCoreImages(core, tools) {
  const images = Object.fromEntries(CORE_IMAGES.map((name) => [name, imageName(name, core)]));
  const ranges = readSettingRanges(tools);
  const extracted = extractRules(images.prometheus);
  for (const [index, set] of settingSets(ranges).entries()) {
    console.log(`운영 명령으로 펼친 설정을 공식 이미지의 도구로 검사합니다(${set.label}): ${Object.entries(set.values).map(([key, value]) => `${key}=${value}`).join(' ')}`);
    const rendered = renderConfigs(tools, set.values);
    if (index === 0) {
      console.log('설정 값의 허용 범위가 코어의 약속(주소 확인 제한 시간, 규칙의 범위, 포트)을 지키는지 확인합니다.');
      checkSettingContracts(rendered, extracted.rules, ranges);
    }
    run('docker', ['run', '--rm', '-v', `${rendered.dir}:/etc/core:ro`, '-v', `${extracted.rules}:/etc/prometheus/rules:ro`,
      '--entrypoint', 'promtool', tools.prometheus, 'check', 'config', '/etc/core/prometheus.yml']);
    checkPrometheusRetention(rendered, ranges, set);
    run('docker', ['run', '--rm', '-v', `${rendered.dir}:/etc/core:ro`, '--entrypoint', 'amtool', tools.alertmanager,
      'check-config', '/etc/core/alertmanager.yml']);
    // Blackbox Exporter는 셸 없이 실행 파일만 부르므로 운영 이미지로 검사한다.
    run('docker', ['run', '--rm', '-v', `${rendered.dir}:/etc/core:ro`, images['blackbox-exporter'],
      '--config.file=/etc/core/blackbox.yml', '--config.check']);
    checkBlackboxConfig(rendered, ranges, set);
    checkCoreEnv(rendered, ranges, set);
    fs.rmSync(rendered.work, { recursive: true, force: true });
  }
  console.log('compose.yml의 포트가 127.0.0.1에만 열리고, 펼친 설정만 읽기 전용으로 붙는지 확인합니다.');
  checkPortBindings(CORE_COMPOSE, ranges);
  checkRenderedMounts(CORE_COMPOSE, CERTS_DIR);
  // 테스트 파일은 ../rules/를 읽으므로 꺼낸 규칙 바로 옆에 붙여, 이미지에 들어간 규칙을 그대로 시험한다.
  console.log('운영 이미지에서 꺼낸 규칙으로 규칙 테스트를 실행합니다.');
  const tests = path.join(CORE_DIR, 'prometheus', 'tests');
  for (const testFile of fs.readdirSync(tests).filter((name) => name.endsWith('.test.yml'))) {
    run('docker', ['run', '--rm', '-v', `${extracted.rules}:/etc/prometheus/rules:ro`, '-v', `${tests}:/etc/prometheus/tests:ro`,
      '-w', '/etc/prometheus/tests', '--entrypoint', 'promtool', tools.prometheus, 'test', 'rules', testFile]);
  }
  fs.rmSync(extracted.root, { recursive: true, force: true });
  // 코어 서버의 sh가 BusyBox일 수도 있어, 공식 이미지에 든 BusyBox sh로 운영 명령을 시험한다.
  // 확인 질문 시험이 가상 터미널을 여는데, 이미지의 기본 사용자(nobody)는 열 수 없어 root로 실행한다.
  console.log('코어 서버 운영 명령(core-server.sh)의 자동 시험을 실행합니다.');
  run('docker', ['run', '--rm', '--user', '0', '-v', `${CORE_DIR}:/src:ro`, '--entrypoint', '/bin/sh', tools.prometheus,
    '/src/tests/core-server.test.sh', '/src/core-server.sh']);
  return ranges;
}

// 코어 이미지와 node_exporter 이미지를 빌드하고, 검사에 쓸 공식 이미지(Dockerfile의 src 단계)를 받는다.
function buildImages(core, { pull }) {
  console.log(`코어 이미지와 node_exporter 이미지를 빌드합니다(버전 ${core.describe}, ${PLATFORM}).`);
  const pullFlag = pull ? ['--pull'] : [];
  run('docker', ['compose', '-f', CORE_COMPOSE, 'build', ...pullFlag], { env: composeEnv(core) });
  run('docker', ['compose', '-f', NODE_EXPORTER_COMPOSE, 'build', ...pullFlag], { env: composeEnv(core) });
  return {
    prometheus: sourceImage(path.join(CORE_DIR, 'prometheus', 'Dockerfile'), CORE_DIR),
    alertmanager: sourceImage(path.join(CORE_DIR, 'alertmanager', 'Dockerfile'), CORE_DIR),
  };
}

// --- 묶음 ---

// 저장소에서는 infrastructure/secrets/를 쓰고, 묶음은 버전 폴더가 바뀌어도 같은 token을 쓰도록 설치 폴더의 secrets/를 쓴다(요구사항 0011).
const SECRETS_DEFAULT = '${CORE_SECRETS_DIR:-./secrets}';
const BUNDLE_SECRETS_DEFAULT = '${CORE_SECRETS_DIR:-../secrets}';
// CA 인증서 파일도 token처럼, 묶음에서는 버전이 바뀌어도 그대로인 설치 폴더의 certs/에 둔다(요구사항 0015).
const CERTS_DIR = 'certs';
const BUNDLE_CERTS_DIR = '../certs';

// 묶음 폴더에서는 저장소의 infrastructure/ 아래 파일이 같은 폴더에 있으므로 안내 문구의 경로를 바꾼다.
function forBundle(text) {
  return text
    .replaceAll('infrastructure/secrets/', 'secrets/')
    .replaceAll('infrastructure/.env.example', '.env.example')
    .replaceAll('infrastructure/.env', '.env')
    .replaceAll('infrastructure/compose.yml', 'compose.yml')
    .replaceAll('infrastructure/core-server.sh', 'core-server.sh');
}

// 저장소의 compose 파일에서 build 항목만 pull_policy: never로 바꾼다. 서비스 정의의 출처를 하나로 두기 위해서다.
// 이미지를 불러오지 않고 띄우면 Compose가 받거나 빌드하지 않고 이미지가 없다는 오류로 멈춘다.
// 파일 머리의 개발용 안내 주석은 내부망에서 맞지 않으므로 빼고 묶음용 안내(header)로 바꾼다.
function bundleCompose(sourceFile, core, header, services) {
  const lines = forBundle(fs.readFileSync(sourceFile, 'utf8')).split(/\r?\n/);
  while (lines.length > 0 && (lines[0].startsWith('#') || lines[0].trim() === '')) {
    lines.shift();
  }
  const out = [...header, ''];
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
    out.push(line.replaceAll('${CORE_VERSION:-dev}', core.describe).replaceAll(SECRETS_DEFAULT, BUNDLE_SECRETS_DEFAULT)
      .replaceAll(`./${CERTS_DIR}/`, `${BUNDLE_CERTS_DIR}/`));
  }
  const text = `${out.join('\n').replace(/\n{3,}/g, '\n\n').trimEnd()}\n`;
  if (/^\s+build:/m.test(text) || /CORE_VERSION|CORE_REVISION|x-core-build-args|core-build-args/.test(text)) {
    fail(`${sourceFile}로 만든 실행용 compose 파일에 빌드 항목이 남았습니다. 형식이 바뀌었는지 확인해 주세요.`);
  }
  if ((text.match(/^\s+pull_policy:/gm) ?? []).length !== services) {
    fail(`${sourceFile}로 만든 실행용 compose 파일의 pull_policy가 서비스 수와 다릅니다. 원본에서 pull_policy를 지워 주세요.`);
  }
  return text;
}

function runtimeCompose(core) {
  const text = bundleCompose(CORE_COMPOSE, core, [
    '# 내부망 서버에서 모니터링 코어를 띄우는 파일입니다. 저장소의 infrastructure/compose.yml로 만들었습니다.',
    `# 버전 ${core.describe}, 커밋 ${core.revision}`,
    '# 설치 방법은 저장소의 docs/OPERATIONS.md에 있습니다. 이 파일은 고치지 말고, 바꿀 값은 .env에 적어 주세요.',
    '#',
    '#   sh core-server.sh install',
    '#   sh core-server.sh start',
    '#',
    '# docker compose로 직접 띄울 때는 먼저 sh core-server.sh render를 실행해 주세요.',
  ], CORE_IMAGES.length);
  if (!text.includes(BUNDLE_SECRETS_DEFAULT)) {
    fail(`실행용 compose 파일에 token 폴더 기본값 ${SECRETS_DEFAULT}가 없습니다. infrastructure/compose.yml의 secrets 항목을 확인해 주세요.`);
  }
  if (!text.includes(`${BUNDLE_CERTS_DIR}/ca-certificates.crt`)) {
    fail(`실행용 compose 파일에 CA 인증서 파일 위치 ${BUNDLE_CERTS_DIR}/ca-certificates.crt가 없습니다. infrastructure/compose.yml의 blackbox-exporter 항목을 확인해 주세요.`);
  }
  return text;
}

function runtimeNodeExporterCompose(core) {
  return bundleCompose(NODE_EXPORTER_COMPOSE, core, [
    '# 감시 대상 Linux 서버에서 node_exporter를 띄우는 파일입니다. 저장소의 infrastructure/node-exporter/compose.yml로 만들었습니다.',
    `# 버전 ${core.describe}, 커밋 ${core.revision}`,
    '# 설치 방법은 저장소의 docs/OPERATIONS.md에 있습니다.',
    '#',
    '#   docker load -i node-exporter-image.tar',
    '#   docker compose -f compose.yml up -d',
  ], 1);
}

// 만든 실행용 compose 파일을 Compose가 실제로 읽을 수 있는지, 포트와 붙이는 파일이 저장소의 compose 파일과 같은 규칙을 지키는지 확인한다.
// 깨진 파일이 내부망 서버에서야 드러나지 않게 한다.
function validateRuntimeCompose(dir, ranges) {
  const file = path.join(dir, 'compose.yml');
  checkPortBindings(file, ranges);
  checkRenderedMounts(file, BUNDLE_CERTS_DIR);
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
    fail(`${where}에 infrastructure/secrets/의 비밀값이 들어 있습니다.`);
  }
}

// 묶음 폴더에 비밀값 파일과 펼친 설정이 없고, 이미지 tar가 아닌 파일에 비밀값이 없는지 확인한다.
// 이미지 tar의 layer는 압축되어 있어 여기서 찾지 못하므로, 이미지는 내보낸 파일 시스템으로 따로 본다.
function assertNoSecrets(dir, secrets) {
  for (const file of filesUnder(dir)) {
    const relative = path.relative(dir, file).split(path.sep).join('/');
    if (relative === '.env' || relative.startsWith('rendered/') || relative.startsWith('certs/')
      || (relative.startsWith('secrets/') && !relative.endsWith('.example'))) {
      fail(`묶음에 비밀값 파일이나 서버에서 만드는 파일이 있습니다: ${relative}`);
    }
    if (!/\.tar(\.gz|\.bz2)?$/.test(relative)) {
      assertNoSecretValue(fs.readFileSync(file), relative, secrets);
    }
  }
}

function assertImagesHaveNoSecrets(exported, secrets) {
  if (secrets.length === 0) {
    console.warn('경고: infrastructure/secrets/에 비밀값 파일이 없어 이미지 안의 비밀값 검사를 건너뜁니다.');
    return;
  }
  console.log('이미지 안에 로컬 비밀값이 들어가지 않았는지 확인합니다.');
  for (const [image, bytes] of Object.entries(exported)) {
    assertNoSecretValue(bytes, image, secrets);
  }
}

// 묶음에 든 프로그램의 라이선스 전문과 고지, 안내 파일만 싣는다. licenses/의 파일은 모두 어느 한 묶음에 속해야 한다.
function addLicenses(out, bundle) {
  const known = Object.values(BUNDLE_LICENSES).flatMap((item) => [item.readme, ...item.files]);
  const unknown = fs.readdirSync(LICENSES_DIR).filter((name) => !known.includes(name));
  if (unknown.length > 0) {
    fail(`infrastructure/licenses/의 ${unknown.join(', ')}을 싣는 묶음이 없습니다. BUNDLE_LICENSES에 더해 주세요.`);
  }
  const { readme, files } = BUNDLE_LICENSES[bundle];
  writeText(path.join(out, 'licenses', 'README.txt'), fs.readFileSync(path.join(LICENSES_DIR, readme), 'utf8'));
  for (const name of files) {
    copy(path.join(LICENSES_DIR, name), path.join(out, 'licenses', name));
  }
}

function buildCoreBundle(core, ranges, secrets, exported) {
  const out = path.join(DIST, `monitoring-core-${core.describe}`);
  freshDir(out);
  const images = CORE_IMAGES.map((name) => imageName(name, core));
  assertImagesHaveNoSecrets(Object.fromEntries(images.map((image) => [image, exported[image]])), secrets);

  console.log('코어 이미지를 묶음 파일로 내보냅니다.');
  run('docker', ['save', '-o', path.join(out, 'core-images.tar'), ...images]);

  writeText(path.join(out, 'compose.yml'), runtimeCompose(core));
  validateRuntimeCompose(out, ranges);
  writeText(path.join(out, '.env.example'), forBundle(fs.readFileSync(path.join(CORE_DIR, '.env.example'), 'utf8')));
  // token은 core-server.sh install이 설치 폴더에 만들므로 묶음에는 token 예시를 싣지 않는다.
  writeText(path.join(out, 'core-server.sh'), fs.readFileSync(path.join(CORE_DIR, 'core-server.sh'), 'utf8'));
  fs.chmodSync(path.join(out, 'core-server.sh'), 0o755);
  // core-server.sh가 . 으로 읽는 값 검사 규칙이다.
  writeText(path.join(out, 'core-settings.sh'), fs.readFileSync(path.join(CORE_DIR, 'core-settings.sh'), 'utf8'));
  // core-server.sh render가 펼치는 설정 템플릿이다. 이미지와 같은 커밋에서 만들고 SHA256SUMS가 함께 지킨다(ADR 0012).
  for (const [from, name] of [['prometheus/prometheus.yml', 'prometheus.yml'], ['alertmanager/alertmanager.yml', 'alertmanager.yml'],
    ['blackbox/blackbox.yml', 'blackbox.yml']]) {
    writeText(path.join(out, 'templates', name), forBundle(fs.readFileSync(path.join(CORE_DIR, from), 'utf8')));
  }
  addLicenses(out, 'core');

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
          fail(`${urls.join(', ')}에서 받지 못했습니다: ${failureReason(error)}`);
        }
        console.warn(`경고: ${url}을 받지 못했습니다(${attempt}/3): ${failureReason(error)}`);
      }
    }
  }
}

async function buildNodeExporterBundle(core, secrets, exported) {
  const out = path.join(DIST, `node-exporter-${core.describe}`);
  freshDir(out);
  const image = nodeExporterImage(core);
  assertImagesHaveNoSecrets({ [image]: exported[image] }, secrets);

  console.log('node_exporter 이미지를 묶음 파일로 내보냅니다.');
  run('docker', ['save', '-o', path.join(out, 'node-exporter-image.tar'), image]);

  console.log(`node_exporter 실행 파일을 받아 공식 체크섬과 대조합니다(${NODE_EXPORTER.archive}).`);
  const archive = path.join(out, NODE_EXPORTER.archive);
  await download([`https://github.com/prometheus/node_exporter/releases/download/v${NODE_EXPORTER.version}/${NODE_EXPORTER.archive}`],
    archive);
  if (sha256(archive) !== NODE_EXPORTER.archiveSha256) {
    fs.rmSync(archive);
    fail(`${NODE_EXPORTER.archive}의 체크섬이 공식 값과 다릅니다. 받은 파일을 지웠습니다.`);
  }

  const compose = runtimeNodeExporterCompose(core);
  writeText(path.join(out, 'compose.yml'), compose);
  run('docker', ['compose', '--project-directory', out, '-f', path.join(out, 'compose.yml'), 'config', '--quiet']);
  writeText(path.join(out, 'node_exporter.service'), fs.readFileSync(path.join(NODE_EXPORTER_DIR, 'node_exporter.service'), 'utf8'));
  addLicenses(out, 'nodeExporter');

  assertNoSecrets(out, secrets);
  writeChecksums(out);
  return out;
}

const CHECK_ONLY = process.argv.includes('--check');
const core = version({ allowDirty: CHECK_ONLY || process.argv.includes('--allow-dirty') });
const tools = buildImages(core, { pull: !CHECK_ONLY });
const specs = imageSpecs(core);
const exported = checkImages(Object.keys(specs), specs);
const ranges = checkCoreImages(core, tools);
if (CHECK_ONLY) {
  console.log('\n이미지 검사, 설정 검사, 규칙 테스트, 운영 명령 시험을 통과했습니다.');
  process.exit(0);
}
const secrets = localSecretValues();
const coreOut = buildCoreBundle(core, ranges, secrets, exported);
const nodeOut = await buildNodeExporterBundle(core, secrets, exported);
console.log('\n묶음을 만들었습니다. 다음 두 폴더를 내부망 서버로 옮겨 주세요.');
console.log(`  ${path.relative(ROOT, coreOut)}`);
console.log(`  ${path.relative(ROOT, nodeOut)}`);
