#!/usr/bin/env node
// 감시 항목 Alert 규칙(infrastructure/prometheus/rules/monitoring-checks.yml)의 두 판정 식을 만든다. 의존성 없이 Node 18 이상에서 동작한다.
// 판정 기준과 식의 구조는 docs/decisions/0015-check-down-by-result-windows.md에 있다.
//
//   node scripts/monitoring-check-rules.mjs           규칙 파일의 두 expr를 이 스크립트의 결과로 바꾼다
//   node scripts/monitoring-check-rules.mjs --check   규칙 파일의 두 expr와 판정 기준 annotation이 이 스크립트와 같은지만 본다
//
// 저장소 최상위에서 실행한다. 종료 코드: --check에서 다르면 1.

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const RULE_FILE = 'infrastructure/prometheus/rules/monitoring-checks.yml';

// 창 길이(초). 기준을 채운 창 길이의 범위는 폭이 수집 주기 이상이라, 간격을 주기보다 좁게 둔다.
// 주기 30초 이하가 1분에 걸리는 100초까지는 10초 간격, 그 위는 앞 창의 1.25배 안쪽이다.
// 900초(15분)보다 오래된 확인 결과는 세지 않는다. 바꾸면 10~240초의 모든 주기에서 판정 시각을 다시 확인한다.
export const WINDOWS = [70, 80, 90, 100, 125, 155, 190, 235, 290, 360, 450, 560, 700, 900];
// 관리자 서버가 annotation의 이 두 값과 수집 주기로 첫 실패 시각을 추정한다.
const FAILURES = 3;
const MIN_SECONDS = 60;
const RECENT = '5m';
const BY = 'by (check_id, check_target_id)';

const RULES = [
  { alert: 'MonitoringCheckDown', metric: 'probe_success{job="manager-blackbox"}' },
  { alert: 'MonitoringCheckProbeFailed', metric: 'up{job="manager-blackbox"}' },
];

// 아래 세 조건은 모두 기준을 채웠을 때만 참이므로, 판정이 기준보다 이르지 않다.
// 네 번째 조건은 이미 장애로 판정한 대상을 이어 가기만 한다.

// 확인 결과의 수집 시각을 보는 subquery의 범위와 간격. 간격은 가장 짧은 수집 주기(10초)보다 짧아야
// 각 확인 결과가 다음 결과나 stale 표시로 가려지기 전에 한 번은 보인다.
const TIMESTAMP_RANGE = `${WINDOWS[WINDOWS.length - 1]}s`;
const TIMESTAMP_STEP = '5s';

// 대상의 확인 결과를 두 id로 묶어 본다. 값을 바꿔 series가 바뀌어도 이어서 센다.
function windowAll(m, w) {
  return [
    `max ${BY} (max_over_time(${m}[${w}s])) == 0`,
    `and sum ${BY} (count_over_time(${m}[${w}s])) >= ${FAILURES}`,
    `and sum ${BY} (count_over_time(${m}[${w - MIN_SECONDS}s] offset ${MIN_SECONDS}s)) > 0`,
  ];
}

// series마다 본다. 성공 중에 값을 바꾼 직후 실패하면 옛 series의 마지막 성공이 새 series의 첫 실패와 붙어,
// 두 id로 묶은 창은 그 사이에 걸리지 않는다. 새 series만 보면 성공이 없으므로 걸린다.
// 대상마다 가장 최근에 수집한 series이고, 최근 15분 안의 확인 결과가 같은 대상의 다른 series보다 모두 나중일 때만 본다.
// 실패 중에 값을 바꿨는데 새 series가 성공하거나, 값을 A에서 B로 바꿨다가 A로 되돌리면, 한 series의 실패 사이에
// 다른 series의 성공이 낀다. 그 series만 보면 그 성공을 보지 못해 기준보다 이르게 장애가 된다.
function windowPerSeries(m, w) {
  return [
    `max_over_time(${m}[${w}s]) == 0`,
    `and count_over_time(${m}[${w}s]) >= ${FAILURES}`,
    `and count_over_time(${m}[${w - MIN_SECONDS}s] offset ${MIN_SECONDS}s) > 0`,
  ];
}

// 창 바로 바깥 띠의 가장 최근 확인 결과가 실패면, 그 결과까지 연속 실패다. 주기를 바꿔 마지막 성공과 첫 실패가
// 창 간격보다 가까울 때도 걸린다. 띠의 결과는 창보다 오래됐으므로 60초 조건은 창 길이(60초 이상)로 채운다.
function bandBeyond(m, w, next) {
  return [
    `max ${BY} (max_over_time(${m}[${w}s])) == 0`,
    `and sum ${BY} (count_over_time(${m}[${w}s])) >= ${FAILURES - 1}`,
    `and max ${BY} (last_over_time(${m}[${next - w}s] offset ${w}s)) == 0`,
  ];
}

// 이미 장애인 대상은 장애로 판정한 뒤 성공이 없는 동안 이어 간다. 확인 결과가 더 오지 않는 대상은 시간이 지나며
// 위 조건의 창이 창 간격 사이로 빠져, 마지막 결과 5분 전에 복구됐다가 다시 장애가 될 수 있기 때문이다.
// 장애 시작 시각은 직전 평가까지의 ALERTS_FOR_STATE 값이고, 그 뒤에 수집한 성공이 하나라도 있으면 잇지 않는다.
function continuing(m, alert) {
  const since = `max ${BY} (ALERTS_FOR_STATE{alertname="${alert}"})`;
  const lastSuccess = `max ${BY} (max_over_time((timestamp(${m}) and ${m} == 1)[${TIMESTAMP_RANGE}:${TIMESTAMP_STEP}]))`;
  return [`  ${since} > ${lastSuccess}`, '  or', `  ${since} unless ${lastSuccess}`];
}

function block(lines, indent) {
  return [`${indent}(`, ...lines.map((line) => `${indent}  ${line}`), `${indent})`];
}

function joinOr(blocks, indent) {
  return blocks.flatMap((lines, index) => (index === 0 ? lines : [`${indent}or`, ...lines]));
}

export function expression(metric, alert) {
  const bands = [MIN_SECONDS, ...WINDOWS];
  const perSeries = joinOr(WINDOWS.map((w) => block(windowPerSeries(metric, w), '      ')), '      ');
  const latest = `(timestamp(${metric}) - on (check_id, check_target_id) group_left max ${BY} (timestamp(${metric}))) == 0`;
  const first = `min_over_time(timestamp(${metric})[${TIMESTAMP_RANGE}:${TIMESTAMP_STEP}])`;
  const othersLast = `max ${BY} (max_over_time(timestamp(${metric})[${TIMESTAMP_RANGE}:${TIMESTAMP_STEP}]) unless ${latest})`;
  const onlyAfterOthers = [
    '    (',
    `      (${first} and ${latest}) > on (check_id, check_target_id) group_left ${othersLast}`,
    '      or',
    `      ${latest} unless on (check_id, check_target_id) ${othersLast}`,
    '    )',
  ];
  const parts = [
    [`  max ${BY} (`, '    (', ...perSeries, '    )', '    and', ...onlyAfterOthers, '  )'],
    ...WINDOWS.map((w) => block(windowAll(metric, w), '  ')),
    ...bands.slice(0, -1).map((w, index) => block(bandBeyond(metric, w, bands[index + 1]), '  ')),
    block(continuing(metric, alert).map((line) => line.trimStart()), '  '),
  ];
  return ['(', ...joinOr(parts, '  '), ')', 'and', `sum ${BY} (count_over_time(${metric}[${RECENT}])) > 0`];
}

// 규칙 파일에서 alert의 `expr: |` 아래 줄들을 찾는다. 다음 키(labels 등)가 나오면 끝난다.
function exprRange(lines, alert) {
  const start = lines.findIndex((line) => line.trim() === `- alert: ${alert}`);
  if (start < 0 || lines[start + 1]?.trim() !== 'expr: |') {
    throw new Error(`${RULE_FILE}에서 ${alert}의 expr: | 를 찾지 못했습니다.`);
  }
  const keyIndent = lines[start + 1].search(/\S/);
  let end = start + 2;
  while (end < lines.length && (lines[end].trim() === '' || lines[end].search(/\S/) > keyIndent)) {
    end += 1;
  }
  return { from: start + 2, to: end, indent: ' '.repeat(keyIndent + 2) };
}

// Windows에서 CRLF로 받아도 같은 결과가 되게 원래 줄바꿈을 따른다.
export function render(text) {
  const eol = text.includes('\r\n') ? '\r\n' : '\n';
  const lines = text.split(/\r?\n/);
  for (const { alert, metric } of RULES) {
    const { from, to, indent } = exprRange(lines, alert);
    lines.splice(from, to - from, ...expression(metric, alert).map((line) => indent + line));
  }
  return lines.join(eol);
}

// alert 규칙의 `annotations:` 아래 줄들을 앞뒤 공백을 지워 돌려준다. 다음 규칙이나 더 얕은 키가 나오면 끝난다.
function annotationLines(lines, alert) {
  const start = lines.findIndex((line) => line.trim() === `- alert: ${alert}`);
  const ruleIndent = lines[start].search(/\S/);
  let index = start + 1;
  while (index < lines.length && !(lines[index].trim() === 'annotations:') && !(lines[index].trim() !== '' && lines[index].search(/\S/) <= ruleIndent)) {
    index += 1;
  }
  if (index >= lines.length || lines[index].trim() !== 'annotations:') {
    return [];
  }
  const keyIndent = lines[index].search(/\S/);
  const result = [];
  for (index += 1; index < lines.length; index += 1) {
    const line = lines[index];
    if (line.trim() !== '' && line.search(/\S/) <= keyIndent) {
      break;
    }
    result.push(line.trim());
  }
  return result;
}

// 규칙 파일이 이 스크립트와 다르면 고칠 방법을 담은 메시지를, 같으면 null을 돌려준다.
export function checkRuleFile(root) {
  const text = fs.readFileSync(path.join(root, RULE_FILE), 'utf8');
  if (render(text) !== text) {
    return `${RULE_FILE}의 판정 식이 scripts/monitoring-check-rules.mjs의 결과와 다릅니다. node scripts/monitoring-check-rules.mjs를 실행해 다시 만드세요.`;
  }
  const lines = text.split(/\r?\n/);
  for (const { alert } of RULES) {
    const annotations = annotationLines(lines, alert);
    for (const [name, value] of [['judgment_failures', FAILURES], ['judgment_min_seconds', MIN_SECONDS]]) {
      if (!annotations.includes(`${name}: "${value}"`)) {
        return `${RULE_FILE}의 ${alert} annotations에 ${name}: "${value}"가 없습니다. 판정 기준과 annotation을 함께 고치세요.`;
      }
    }
  }
  return null;
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = process.cwd();
  if (process.argv.includes('--check')) {
    const problem = checkRuleFile(root);
    if (problem) {
      console.error(problem);
      process.exit(1);
    }
    console.log(`${RULE_FILE}의 판정 식과 판정 기준 annotation이 스크립트와 같습니다.`);
  } else {
    const file = path.join(root, RULE_FILE);
    fs.writeFileSync(file, render(fs.readFileSync(file, 'utf8')));
    console.log(`${RULE_FILE}의 판정 식을 다시 만들었습니다.`);
  }
}
