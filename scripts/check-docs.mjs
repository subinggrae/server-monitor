#!/usr/bin/env node
// 프로젝트 문서 검사. 의존성 없이 Node 18 이상에서 동작한다.
//
//   node scripts/check-docs.mjs            문서 구조, 링크, 형식 검사
//   node scripts/check-docs.mjs --render   SVG를 Chrome으로 렌더링해 겹침·잘림까지 검사하고 PNG를 남긴다
//
// 옵션: --root <dir>  --out <dir>(PNG 저장 위치, 기본은 OS 임시 폴더)  --json
//       --font <글꼴>(대체 글꼴로 렌더링)  --ignore <경로>(검사에서 뺄 폴더, 여러 번 쓸 수 있다)  --no-core(기본 문서 확인 생략)
// 종료 코드: 오류가 있으면 1, 경고만 있으면 0.

import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

const args = process.argv.slice(2);
const opt = (name, def) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] && !args[i + 1].startsWith('--') ? args[i + 1] : def;
};
const ROOT = path.resolve(opt('--root', process.cwd()));
const RENDER = args.includes('--render');
const JSON_OUT = args.includes('--json');
const FONT = opt('--font', ''); // 대체 글꼴로 그렸을 때도 넘치지 않는지 확인할 때 쓴다. 예: --font "Malgun Gothic"
const OUT_DIR = path.resolve(opt('--out', path.join(os.tmpdir(), 'check-docs-render')));
const NO_CORE = args.includes('--no-core');
const IGNORE = args.flatMap((a, i) => (a === '--ignore' && args[i + 1] ? [args[i + 1].split('\\').join('/').replace(/\/$/, '')] : []));
const PROFILE_BASE = path.join(os.tmpdir(), `check-docs-chrome-${process.pid}`);
let renderCount = 0;

const IGNORED_DIRS = new Set([
  'node_modules', '.git', '.svn', 'dist', 'build', 'out', 'coverage', 'vendor', '.next', '.nuxt',
  '.output', '.svelte-kit', 'target', '.venv', 'venv', '__pycache__', '.gradle', '.idea', '.turbo',
  '.cache', 'tmp', '.claude', '.agents', '.vscode',
]);

// 저장소에 남기지 않는 작업 부산물. 이름만으로 판단할 수 있는 것만 둔다.
const STRAY_PATTERNS = [
  /^REPORT(\..+)?\.md$/i, /^SESSION.*\.md$/i, /^HANDOFF.*\.md$/i, /^PROGRESS\.(md|txt)$/i,
  /^NOTES?\.md$/i, /^TODO\.md$/i, /^SUMMARY-.*\.md$/i, /.*-(report|summary|session|handoff|review-log)\.md$/i,
  /^(BASELINE|MIGRATION)[-_].*\.md$/i, /^claude-progress\.txt$/i, /\.(orig|rej|bak)$/i,
];

const CORE_DOCS = [
  'README.md', 'AGENTS.md', 'CLAUDE.md', 'docs/ARCHITECTURE.md', 'docs/DEVELOPMENT.md', 'docs/TESTING.md',
  'docs/WRITING.md', 'docs/TERMS.md', 'docs/LICENSES.md', 'docs/DIAGRAMS.md', 'docs/PRODUCT.md', 'docs/templates/requirement.md', 'docs/templates/plan.md', 'docs/templates/decision.md',
];
// 화면이 있는 프로젝트에만 두는 문서. 하나라도 있으면 모두 있어야 한다
const DESIGN_DOCS = ['docs/design/DESIGN.md', 'docs/design/RULES.md', 'docs/design/REVIEW.md', 'docs/design/DIRECTION.md'];

// 장·절 번호에 기대는 참조. 문서가 바뀌면 의미를 잃는다.
// "절차", "장점"처럼 장·절로 시작하는 낱말은 빼고, 조사가 붙은 경우("3장을", "다음 절에서")는 잡는다.
const PARTICLE = '(?=$|[^가-힣]|을|를|은|는|이|가|의|에|과|와|으로|로|도|만|까지|부터|참고|참조)';
const SECTION_REF = new RegExp(`(\\d+\\s*(장|절)${PARTICLE}|(앞|다음|위|아래|이전)\\s*(장|절)${PARTICLE}|[Ss]ection\\s+\\d+|§\\s*\\d+|[Cc]hapter\\s+\\d+)`);

// 번역투와 군더더기 표현. 경고로만 알린다.
const KO_STYLE = [
  [/되어지|되어진|지어지|불려지/, '이중 피동'],
  [/에 있어서|에 있어 /, '"~에 있어서"는 "~에서", "~할 때"로'],
  [/을 통해서?|를 통해서?/, '"~를 통해"는 "~로", "~해서"로 바꿀 수 있는지 확인'],
  [/에 대해서?|에 대한/, '"~에 대해/대한"이 꼭 필요한지 확인'],
  [/할 수 있도록 한다|하도록 한다|하도록 합니다/, '"~하도록 한다"는 "~한다"로'],
  [/것이다\.|것입니다\./, '"~것이다" 문장 끝맺음은 직접 서술로'],
  [/가지고 있다|가지고 있습니다|를 가진다|을 가진다/, '"가지다"는 "있다"로 바꿀 수 있는지 확인'],
  [/진행하도록|진행한다|진행합니다/, '"진행하다" 대신 실제 동작을 쓰기'],
  [/적절한|적절히|적절하게|등등|기타 등등/, '모호한 표현. 기준을 구체적으로'],
];

const errors = [];
const warnings = [];
const add = (level, rule, file, msg) => (level === 'error' ? errors : warnings).push({ rule, file, msg });

// ---------- 파일 수집 ----------
function walk(dir, acc = []) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return acc; }
  for (const e of entries) {
    if (e.isDirectory()) {
      if (IGNORED_DIRS.has(e.name)) continue;
      walk(path.join(dir, e.name), acc);
    } else if (e.isFile()) acc.push(path.join(dir, e.name));
  }
  return acc;
}
const rel = (p) => path.relative(ROOT, p).split(path.sep).join('/');
const allFiles = walk(ROOT).filter((f) => !IGNORE.some((ig) => rel(f) === ig || rel(f).startsWith(`${ig}/`)));
const mdFiles = allFiles.filter((f) => f.toLowerCase().endsWith('.md'));
const isTemplateDoc = (r) => r.startsWith('docs/templates/');
// PR·MR 템플릿은 제목과 작성 안내 주석만 있는 것이 정상이다
const isVcsTemplate = (r) => /^(\.github\/|\.gitea\/|\.forgejo\/|docs\/)?pull_request_template(\.md|\/[^/]+\.md)$|^\.gitlab\/merge_request_templates\/[^/]+\.md$/i.test(r);

// ---------- Markdown 파싱 ----------
function stripCode(md) {
  // 코드 블록과 인라인 코드는 링크·문체 검사에서 뺀다. 줄 수는 유지한다.
  return md
    .replace(/^(```|~~~)[^\n]*\n[\s\S]*?^\1[^\n]*$/gm, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/`[^`\n]*`/g, (m) => ' '.repeat(m.length));
}
function stripComments(md) {
  return md.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ' '));
}
function slugify(text) {
  return text
    .trim()
    .toLowerCase()
    .replace(/<[^>]+>/g, '')
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .replace(/\s/g, '-');
}
const anchorCache = new Map();
function anchorsOf(file) {
  if (anchorCache.has(file)) return anchorCache.get(file);
  const set = new Set();
  const counts = new Map();
  let md = '';
  try { md = fs.readFileSync(file, 'utf8'); } catch { /* 없음 */ }
  // 코드 블록 안의 # 줄은 제목이 아니다. 제목 안의 인라인 코드는 GitHub처럼 백틱만 지우고 글자는 남긴다
  const body = md.replace(/^(```|~~~)[^\n]*\n[\s\S]*?^\1[^\n]*$/gm, (m) => m.replace(/[^\n]/g, ' '));
  for (const line of body.split('\n')) {
    const m = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (!m) continue;
    const base = slugify(m[2].replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/`/g, ''));
    const n = counts.get(base) || 0;
    counts.set(base, n + 1);
    set.add(n ? `${base}-${n}` : base);
  }
  for (const m of md.matchAll(/<a\s+(?:name|id)="([^"]+)"/g)) set.add(m[1]);
  anchorCache.set(file, set);
  return set;
}
function h2List(md) {
  return stripCode(md).split('\n').filter((l) => /^##\s+/.test(l)).map((l) => l.replace(/^##\s+/, '').trim());
}
function linksOf(md) {
  const out = [];
  const body = stripComments(stripCode(md));
  const lines = body.split('\n');
  lines.forEach((line, i) => {
    for (const m of line.matchAll(/!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g)) out.push({ target: m[1], line: i + 1 });
    for (const m of line.matchAll(/<(?:img|source|a)\s[^>]*?(?:src|srcset|href)="([^"]+)"/g)) out.push({ target: m[1], line: i + 1 });
    for (const m of line.matchAll(/^\s*\[[^\]]+\]:\s*(\S+)/g)) out.push({ target: m[1], line: i + 1 });
  });
  return out;
}

// ---------- 검사 ----------
const referencedAssets = new Set();

for (const doc of NO_CORE ? [] : CORE_DOCS) {
  if (!fs.existsSync(path.join(ROOT, doc))) add('error', 'doc-missing', doc, '기본 문서가 없다');
}
if (!NO_CORE && DESIGN_DOCS.some((d) => fs.existsSync(path.join(ROOT, d)))) {
  for (const doc of DESIGN_DOCS) if (!fs.existsSync(path.join(ROOT, doc))) add('error', 'doc-missing', doc, '디자인 문서 네 개는 함께 있어야 한다');
}

for (const file of allFiles) {
  const name = path.basename(file);
  if (STRAY_PATTERNS.some((re) => re.test(name))) add('error', 'stray-file', rel(file), '작업 부산물로 보이는 파일. 필요한 내용은 기본 문서로 옮기고 지운다');
}

for (const file of mdFiles) {
  const r = rel(file);
  const md = fs.readFileSync(file, 'utf8');
  const clean = stripComments(stripCode(md));
  const lines = clean.split('\n');

  // 링크. docs/templates/의 링크는 새 문서에서 채울 예시이므로 검사하지 않는다.
  for (const { target, line } of isTemplateDoc(r) ? [] : linksOf(md)) {
    if (/^(https?:|mailto:|tel:|data:)/i.test(target)) continue;
    const [p, hash] = target.split('#');
    const decoded = decodeURIComponent(p || '');
    const dest = decoded ? path.resolve(path.dirname(file), decoded) : file;
    if (decoded && !fs.existsSync(dest)) {
      add('error', 'link-broken', `${r}:${line}`, `없는 경로: ${target}`);
      continue;
    }
    if (/\.(svg|png|jpe?g|gif|webp)$/i.test(decoded)) referencedAssets.add(path.normalize(dest));
    if (hash && dest.toLowerCase().endsWith('.md') && !anchorsOf(dest).has(decodeURIComponent(hash))) {
      add('error', 'anchor-broken', `${r}:${line}`, `없는 제목 링크: ${target}`);
    }
  }

  // 장·절 번호 참조
  lines.forEach((l, i) => {
    if (/^\s*#/.test(l)) return;
    const m = SECTION_REF.exec(l);
    if (m) add('error', 'section-number', `${r}:${i + 1}`, `장·절 번호 참조 "${m[0]}". 문서 이름과 제목 링크로 바꾼다`);
  });

  // 채우지 않은 템플릿 흔적
  if (!isTemplateDoc(r)) {
    md.split('\n').forEach((l, i) => {
      // 자리표시자 형식을 설명하는 {{...}} 표기는 빼고, 실제 내용이 들어갈 자리만 잡는다
      if (/\{\{(?!\.\.\.\}\})[^}]*\}\}/.test(l)) add('error', 'placeholder', `${r}:${i + 1}`, '채우지 않은 자리표시자 {{...}}');
      if (/^\s*<!--\s*안내/.test(l)) add('error', 'placeholder', `${r}:${i + 1}`, '템플릿 안내 주석이 남아 있다');
    });
    if (clean.replace(/^#.*$/gm, '').replace(/\s/g, '').length < 40 && !/^(CLAUDE|GEMINI)\.md$/i.test(path.basename(file)) && !isVcsTemplate(r)) {
      add('error', 'empty-doc', r, '내용이 거의 없는 문서. 채우거나 지운다');
    }
  }

  // 표 칸에 문장이 여럿이면 좁은 본문 폭에서 읽기 어렵다. 목록으로 바꿀지 알린다
  if (!isTemplateDoc(r)) {
    const rawLines = md.split('\n');
    lines.forEach((l, i) => {
      if (!/^\s*\|/.test(l) || /^\s*\|[\s:|-]+\|\s*$/.test(l)) return;
      // 코드 서식 안의 점과 세로선은 문장 끝이나 칸 경계가 아니다. 판정할 때만 다른 글자로 바꾸고 보여 줄 때 되돌린다
      const masked = rawLines[i].replace(/`[^`]*`/g, (m) => m.replace(/\./g, '').replace(/\|/g, ''));
      // 문장 끝 뒤에 글이 더 이어지면 문장이 둘 이상이다. 숫자 뒤의 점은 날짜(2026. 10. 7.)라서 문장 끝으로 보지 않는다
      const long = masked.split('|').slice(1, -1).find((c) => /[가-힣A-Za-z)`]\.\s+[가-힣A-Za-z`(]/.test(c.trim()));
      const shown = long?.trim().replace(//g, '.').replace(//g, '|').slice(0, 30);
      if (long) add('warn', 'table-cell', `${r}:${i + 1}`, `표 칸에 문장이 여럿이다: "${shown}…". 칸은 짧게 쓰고, 길면 이름표를 붙인 목록으로 바꾼다`);
    });
  }

  // 한국어 문체
  if (!isTemplateDoc(r)) {
    lines.forEach((l, i) => {
      if (/^\s*(#|\||>)/.test(l)) return;
      for (const [re, why] of KO_STYLE) if (re.test(l)) add('warn', 'ko-style', `${r}:${i + 1}`, why);
    });
    const formal = (clean.match(/습니다[.!]/g) || []).length;
    const plain = (clean.match(/[^니]다\.(\s|$)/gm) || []).length;
    if (formal >= 3 && plain >= 3) add('warn', 'ko-tone', r, `합니다체(${formal})와 평서형(${plain})이 섞여 있다`);
  }
}

// 같은 종류 문서는 템플릿과 같은 제목 구조를 가진다. 한 폴더에 여러 종류가 있을 수 있다.
const kinds = {
  'docs/requirements': [
    ['docs/templates/plan.md', (n) => /^\d{4}-.+\.plan\.md$/.test(n)],
    ['docs/templates/requirement.md', (n) => /^\d{4}-.+\.md$/.test(n)],
  ],
  'docs/decisions': [
    ['docs/templates/decision.md', (n) => /^\d{4}-.+\.md$/.test(n)],
  ],
};
for (const [dir, list] of Object.entries(kinds)) {
  const dirPath = path.join(ROOT, dir);
  if (!fs.existsSync(dirPath)) continue;
  for (const name of fs.readdirSync(dirPath)) {
    const f = path.join(dirPath, name);
    if (!fs.statSync(f).isFile() || !name.endsWith('.md')) continue;
    const kind = list.find(([, match]) => match(name));
    if (!kind) {
      if (!/^README\.md$/i.test(name)) add('error', 'doc-name', rel(f), '이름 형식은 NNNN-짧은-설명.md (계획은 NNNN-짧은-설명.plan.md)');
      continue;
    }
    const tpl = kind[0];
    const tplPath = path.join(ROOT, tpl);
    if (!fs.existsSync(tplPath)) continue;
    const want = h2List(fs.readFileSync(tplPath, 'utf8'));
    const have = h2List(fs.readFileSync(f, 'utf8'));
    const missing = want.filter((h) => !have.includes(h));
    if (missing.length) add('error', 'doc-structure', rel(f), `${tpl}의 제목이 빠졌다: ${missing.join(', ')}`);
    const order = want.filter((h) => have.includes(h));
    const actual = have.filter((h) => want.includes(h));
    if (!missing.length && order.join('|') !== actual.join('|')) add('warn', 'doc-structure', rel(f), '제목 순서가 템플릿과 다르다');
  }
}

// AGENTS.md 크기와 명령 일치
const agentsPath = path.join(ROOT, 'AGENTS.md');
if (fs.existsSync(agentsPath)) {
  const agents = fs.readFileSync(agentsPath, 'utf8');
  const lineCount = agents.split('\n').length;
  if (Buffer.byteLength(agents) > 32 * 1024) add('error', 'agents-size', 'AGENTS.md', '32 KiB를 넘는다. 일부 도구가 뒷부분을 읽지 않는다');
  else if (lineCount > 200) add('warn', 'agents-size', 'AGENTS.md', `${lineCount}줄. 200줄 이하로 줄이고 세부 내용은 docs/로 옮긴다`);
  const sources = ['docs/DEVELOPMENT.md', 'docs/TESTING.md']
    .map((p) => path.join(ROOT, p)).filter(fs.existsSync).map((p) => fs.readFileSync(p, 'utf8')).join('\n');
  const norm = (s) => s.replace(/\s+/g, ' ').trim();
  if (sources) {
    for (const block of agents.matchAll(/^```(?:sh|bash|shell|console|powershell|ps1)?\n([\s\S]*?)^```/gm)) {
      for (const raw of block[1].split('\n')) {
        const cmd = raw.replace(/\s+#.*$/, '').trim();
        if (!cmd || cmd.startsWith('#')) continue;
        if (!norm(sources).includes(norm(cmd))) add('warn', 'command-drift', 'AGENTS.md', `DEVELOPMENT.md·TESTING.md에 없는 명령: ${cmd}`);
      }
    }
  }
}

// AI 지침과 디자인 문서의 낡은 참조, 강조 남용, 고정 값.
// 판단이 필요한 정리(필요 없는 지시, 충돌, 한 번만 필요했던 지시)는 WRITING.md의 AI 지침 점검으로 사람과 AI가 한다.
const instructionFiles = mdFiles.filter((f) => /^(AGENTS|CLAUDE)\.md$/.test(path.basename(f)) || rel(f) === 'docs/design/DESIGN.md');
const EMPHASIS = /\b(IMPORTANT|MUST|NEVER|CRITICAL|ALWAYS)\b|반드시|절대로|절대 /;
const EMPHASIS_LIMIT = 3; // 강조한 줄이 많으면 어느 것도 두드러지지 않는다
const INSTRUCTION_LINE_LIMIT = 200; // 늘 읽는 지침이 길면 지시를 덜 지킨다(Claude Code는 200줄 미만을 권한다)
for (const f of instructionFiles) {
  const r = rel(f);
  const md = stripComments(fs.readFileSync(f, 'utf8'));
  const lines = md.split('\n');
  // 백틱 안의 저장소 경로가 실제로 있는지. 예시 자리(NNNN, *, 한글 이름)는 뺀다
  lines.forEach((l, i) => {
    for (const m of l.matchAll(/`([^`\s]+\/[^`\s]+\.[A-Za-z0-9]+)`/g)) {
      const p = m[1].replace(/^\.\//, '').replace(/[#:].*$/, '');
      if (/NNNN|[*{}<>]|[가-힣]|^https?:|^~|^[A-Za-z]:/.test(p)) continue;
      if (!fs.existsSync(path.join(ROOT, p)) && !fs.existsSync(path.join(path.dirname(f), p))) {
        add('warn', 'instruction-stale', `${r}:${i + 1}`, `없는 경로를 가리킨다: ${p}. 지시가 낡았는지 확인한다`);
      }
    }
  });
  if (/^AGENTS\.md$/.test(path.basename(f)) && lines.length > INSTRUCTION_LINE_LIMIT) add('warn', 'instruction-length', r, `안내 주석을 뺀 길이가 ${lines.length}줄이다. ${INSTRUCTION_LINE_LIMIT}줄 아래로 줄이고, 일부 작업에만 필요한 규칙은 docs/의 문서나 하위 AGENTS.md로 옮긴다`);
  const emphasized = lines.filter((l) => EMPHASIS.test(l)).length;
  if (emphasized > EMPHASIS_LIMIT) add('warn', 'instruction-emphasis', r, `강조 표현이 있는 줄이 ${emphasized}개다. 지켜지지 않는 한두 줄만 강조하고, 반드시 지켜야 하면 hook이나 CI로 강제한다`);
  if (r === 'docs/design/DESIGN.md') {
    stripCode(md).split('\n').forEach((l, i) => {
      if (/(^|[\s(:])#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?\b/.test(l)) add('warn', 'design-literal', `${r}:${i + 1}`, '색 값을 직접 적었다. 값은 토큰 파일이 기준이므로 토큰 이름이나 역할로 쓴다');
    });
  }
}

// 보이지 않는 문자. 지침과 문서에 숨긴 지시는 사람의 리뷰를 지나 AI에게만 읽힌다. 파일 맨 앞의 BOM은 뺀다
const HIDDEN_CHARS = /[​-‏‪-‮⁠-⁤⁦-⁩﻿]|[\u{E0000}-\u{E007F}]/u;
for (const f of mdFiles) {
  fs.readFileSync(f, 'utf8').replace(/^﻿/, '').split('\n').forEach((l, i) => {
    if (HIDDEN_CHARS.test(l)) add('error', 'hidden-char', `${rel(f)}:${i + 1}`, '보이지 않는 문자(폭 없는 문자, 방향 제어 문자, 태그 문자)가 있다. 지우고, 밖에서 들여온 내용이면 숨긴 지시가 있는지 확인한다');
  });
}

// 여러 문서에 같은 문장. 지식은 한 문서에만 쓰고 나머지는 링크한다(WRITING.md의 출처는 한 곳)
const DUPLICATE_MIN_LENGTH = 60; // 짧은 이름표나 관용구는 겹쳐도 문제가 아니다
const sentences = new Map();
for (const f of mdFiles) {
  const r = rel(f);
  if (isTemplateDoc(r) || isVcsTemplate(r)) continue;
  stripCode(stripComments(fs.readFileSync(f, 'utf8'))).split('\n').forEach((l, i) => {
    const s = l.replace(/^\s*([-*+]|\d+\.)\s+/, '').replace(/\s+/g, ' ').trim();
    if (s.length < DUPLICATE_MIN_LENGTH || /^[|#>]/.test(s) || s.includes('{{')) return;
    if (!sentences.has(s)) sentences.set(s, []);
    sentences.get(s).push(`${r}:${i + 1}`);
  });
}
for (const [s, locs] of sentences) {
  if (new Set(locs.map((x) => x.replace(/:\d+$/, ''))).size < 2) continue;
  add('warn', 'duplicate', locs[0], `같은 문장이 ${locs.slice(1).join(', ')}에도 있다: "${s.slice(0, 40)}…". 한 문서에만 두고 나머지는 링크한다`);
}

// TERMS.md 표에서 쓰지 않기로 한 말. 한글이 없는 말과 괄호로 뜻을 한정한 말은 코드, 주소, 다른 뜻의 낱말과 구별할 수 없어 검사하지 않는다
const termsPath = path.join(ROOT, 'docs/TERMS.md');
if (fs.existsSync(termsPath)) {
  const pairs = [];
  for (const line of stripComments(fs.readFileSync(termsPath, 'utf8')).split('\n')) {
    const cells = line.split('|').map((c) => c.trim());
    if (cells.length < 4 || /^-+$/.test(cells[1]) || cells[1] === '쓰는 말') continue;
    const use = cells[1].replace(/`/g, '');
    for (const avoid of cells[2].split(',').map((s) => s.replace(/`/g, '').trim())) {
      if (/[가-힣]/.test(avoid) && !avoid.includes('(')) pairs.push([avoid, use]);
    }
  }
  // 받침이 있으면(ㄹ 받침 제외) "으로", 없으면 "로"를 붙인다
  const ro = (w) => {
    const c = w.charCodeAt(w.length - 1) - 0xac00;
    return c >= 0 && c < 11172 && c % 28 !== 0 && c % 28 !== 8 ? '으로' : '로';
  };
  const termMessage = (avoid, use) => `"${avoid}" 대신 "${use}"${ro(use)} 쓴다(TERMS.md)`;
  if (pairs.length) {
    for (const f of mdFiles) {
      const r = rel(f);
      if (r === 'docs/TERMS.md') continue;
      stripComments(stripCode(fs.readFileSync(f, 'utf8'))).split('\n').forEach((l, i) => {
        for (const [avoid, use] of pairs) if (l.includes(avoid)) add('warn', 'term', `${r}:${i + 1}`, termMessage(avoid, use));
      });
    }
    // 그림 속 글자도 같은 표기를 쓴다
    for (const f of allFiles.filter((p) => p.toLowerCase().endsWith('.svg') && rel(p).startsWith('docs/'))) {
      const words = [...fs.readFileSync(f, 'utf8').matchAll(/<(text|title|desc)[^>]*>([^<]*)</g)].map((m) => m[2]).join('\n');
      for (const [avoid, use] of pairs) if (words.includes(avoid)) add('warn', 'term', rel(f), termMessage(avoid, use));
    }
  }
}

// 문서에 적힌 npm 스크립트가 package.json에 있는지
const pkgPath = path.join(ROOT, 'package.json');
if (fs.existsSync(pkgPath)) {
  let scripts = null;
  try { scripts = JSON.parse(fs.readFileSync(pkgPath, 'utf8')).scripts || {}; } catch { /* 읽을 수 없으면 건너뛴다 */ }
  if (scripts) {
    // 루트 문서, docs/ 기준 문서, 하위 AGENTS.md. 요구사항과 ADR은 앞으로 만들 스크립트를 말할 수 있어 뺀다
    const isCommandDoc = (r) => !r.includes('/') || /(^|\/)AGENTS\.md$/.test(r) || /^docs\/[^/]+\.md$/.test(r);
    for (const f of mdFiles.filter((p) => isCommandDoc(rel(p)))) {
      const md = stripComments(fs.readFileSync(f, 'utf8'));
      const seen = new Set();
      for (const m of md.matchAll(/\b(?:npm|pnpm|yarn)\s+run\s+([\w:.-]+)|\bnpm\s+(test|start)\b/g)) {
        const name = m[1] || m[2];
        if (seen.has(name) || name in scripts) continue;
        seen.add(name);
        add('warn', 'instruction-stale', rel(f), `package.json에 없는 스크립트: ${name}`);
      }
    }
  }
}

// 사용하지 않는 그림
const assetFiles = allFiles.filter((f) => /\.(svg|png|jpe?g|gif|webp)$/i.test(f) && rel(f).startsWith('docs/assets/'));
for (const f of assetFiles) {
  if (!referencedAssets.has(path.normalize(f))) add('error', 'asset-unused', rel(f), '어느 문서에서도 쓰지 않는 그림');
}

// SVG 정적 검사
// docs/ 아래 SVG와 Markdown이 참조하는 SVG를 문서 그림으로 본다
const svgFiles = allFiles.filter((f) => f.toLowerCase().endsWith('.svg') && (rel(f).startsWith('docs/') || referencedAssets.has(path.normalize(f))));
for (const f of svgFiles) {
  const svg = fs.readFileSync(f, 'utf8');
  const r = rel(f);
  if (/<script/i.test(svg)) add('error', 'svg-static', r, '<script>는 문서 그림에서 실행되지 않는다');
  if (/<foreignObject/i.test(svg)) add('error', 'svg-static', r, 'foreignObject는 변환·발표 도구에서 깨진다');
  if (/@import|url\(\s*['"]?https?:|(?:xlink:)?href="https?:/i.test(svg)) add('error', 'svg-static', r, '외부 리소스는 <img>로 표시할 때 차단된다');
  if (!/viewBox=/.test(svg)) add('error', 'svg-static', r, 'viewBox가 없다');
  if (!/<title[\s>]/.test(svg)) add('error', 'svg-static', r, '<title>이 없다');
  if (r.startsWith('docs/') && !/@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)/.test(svg)) add('warn', 'svg-dark', r, '다크 모드 색이 없다. DIAGRAMS.md의 기본 스타일대로 prefers-color-scheme: dark 블록을 둔다');
  const vb = /viewBox="\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)\s+([\d.]+)/.exec(svg);
  if (vb) {
    const w = Number(vb[1]);
    for (const m of svg.matchAll(/font-size(?:="|:\s*)([\d.]+)/g)) {
      // 저장소 화면의 본문 폭(약 840px)에서 보이는 크기. 본문보다 좁은 그림은 늘어나지 않는다
      const px = (Number(m[1]) / Math.max(w, 840)) * 840;
      if (px < 12) { add('error', 'svg-font', r, `글자 ${m[1]}px은 본문 폭 840px에서 ${px.toFixed(1)}px로 보인다. 최소 12px`); break; }
    }
  }
}

// ---------- 렌더링 검사 ----------
function findChrome() {
  const candidates = [
    process.env.CHROME_PATH,
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
    '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser',
  ].filter(Boolean);
  return candidates.find((c) => fs.existsSync(c));
}

const PROBE = `
const svg = document.querySelector('svg');
const res = { width: 0, height: 0, issues: [] };
const vb = svg.viewBox.baseVal; res.width = vb.width; res.height = vb.height;
svg.setAttribute('width', vb.width); svg.setAttribute('height', vb.height);
const canvas = svg.getBoundingClientRect();
const box = (el) => { const r = el.getBoundingClientRect(); return { x: r.left - canvas.left, y: r.top - canvas.top, w: r.width, h: r.height }; };
const label = (el) => (el.textContent || '').trim().slice(0, 30);
const texts = [...svg.querySelectorAll('text')].filter((t) => t.textContent.trim() && t.getBoundingClientRect().width > 0);
const boxes = texts.map((t) => ({ t, b: box(t) }));
res.minFont = texts.length ? Math.min(...texts.map((t) => parseFloat(getComputedStyle(t).fontSize) || 99)) : 0;
const MARGIN = 8; // 바깥 여백은 16px로 그린다. 8px 안쪽으로 들어온 글자는 여백 침범으로 본다
for (const { t, b } of boxes) {
  if (b.x < -0.5 || b.y < -0.5 || b.x + b.w > vb.width + 0.5 || b.y + b.h > vb.height + 0.5) res.issues.push({ kind: 'clipped', text: label(t) });
  else if (b.x < MARGIN || b.y < MARGIN || b.x + b.w > vb.width - MARGIN || b.y + b.h > vb.height - MARGIN) res.issues.push({ kind: 'margin', text: label(t) });
}
const inter = (a, b, pad = 0) => Math.max(0, Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x) + pad) * Math.max(0, Math.min(a.y + a.h, b.y + b.h) - Math.max(a.y, b.y) + pad);
for (let i = 0; i < boxes.length; i++) for (let j = i + 1; j < boxes.length; j++) {
  // 글자 상자는 줄 간격 여백을 포함하므로 위아래 3px은 겹침으로 보지 않는다
  const a = { ...boxes[i].b, y: boxes[i].b.y + 3, h: boxes[i].b.h - 6 }, b = { ...boxes[j].b, y: boxes[j].b.y + 3, h: boxes[j].b.h - 6 };
  if (inter(a, b) > 4) res.issues.push({ kind: 'text-overlap', text: label(boxes[i].t) + ' / ' + label(boxes[j].t) });
}
// class="node" 그룹 안의 글자는 첫 도형 안에 들어가야 한다. 좌우 6px, 위아래 2px보다 가까우면 도형에 붙은 것으로 본다
for (const g of svg.querySelectorAll('.node')) {
  const shape = g.querySelector('rect, circle, ellipse, polygon, path');
  if (!shape) continue;
  const s = box(shape);
  for (const t of g.querySelectorAll('text')) {
    const b = box(t);
    const pad = 6;
    if (b.x < s.x + pad || b.x + b.w > s.x + s.w - pad || b.y < s.y + 2 || b.y + b.h > s.y + s.h - 2) res.issues.push({ kind: 'text-overflow', text: label(t) });
  }
}
// 연결선(.edge, .reply, .edge-key, .reply-key)이 글자 위를 지나가는지 본다
const EDGE_SEL = '.edge, .reply, .edge-key, .reply-key';
for (const e of svg.querySelectorAll(EDGE_SEL)) {
  const len = e.getTotalLength ? e.getTotalLength() : 0;
  const m = e.getScreenCTM();
  for (let d = 0; d <= len; d += 4) {
    const p = e.getPointAtLength(d);
    const x = (m ? m.a * p.x + m.c * p.y + m.e : p.x) - canvas.left, y = (m ? m.b * p.x + m.d * p.y + m.f : p.y) - canvas.top;
    const hit = boxes.find(({ t, b }) => !t.closest('.edge-label') && x > b.x + 2 && x < b.x + b.w - 2 && y > b.y + 4 && y < b.y + b.h - 4);
    if (hit) { res.issues.push({ kind: 'edge-over-text', text: label(hit.t) }); break; }
  }
}
// class="edge" 연결선이 나중에 그린 도형(묶음 바탕 등)에 가려지는지 본다. 끝부분 12px은 노드와 맞닿으므로 뺀다.
const SHAPES = new Set(['rect', 'circle', 'ellipse', 'polygon', 'path', 'polyline', 'line']);
for (const e of svg.querySelectorAll(EDGE_SEL)) {
  const len = e.getTotalLength ? e.getTotalLength() : 0;
  const m = e.getScreenCTM();
  let samples = 0, hidden = 0;
  for (let d = 12; d <= len - 12; d += 6) {
    const p = e.getPointAtLength(d);
    const x = m ? m.a * p.x + m.c * p.y + m.e : p.x, y = m ? m.b * p.x + m.d * p.y + m.f : p.y;
    const top = document.elementFromPoint(x, y);
    samples++;
    if (!top || top === e || e.contains(top) || top.closest('text')) continue;
    if (SHAPES.has(top.tagName) && (e.compareDocumentPosition(top) & Node.DOCUMENT_POSITION_FOLLOWING)) hidden++;
  }
  if (samples && hidden / samples > 0.25) res.issues.push({ kind: 'edge-hidden', text: (e.id || e.getAttribute('d') || '').slice(0, 30) });
}
// 글자와 바로 아래 도형 색의 대비. 그림 글자는 12~14px이므로 WCAG AA 일반 글자 기준 4.5:1을 쓴다
const rgb = (s) => { const m = /rgba?\\(([^)]+)\\)/.exec(s || ''); if (!m) return null; const v = m[1].split(',').map(Number); return v.length > 3 && v[3] === 0 ? null : v.slice(0, 3); };
const lum = (c) => { const f = (x) => { x /= 255; return x <= 0.03928 ? x / 12.92 : Math.pow((x + 0.055) / 1.055, 2.4); }; return 0.2126 * f(c[0]) + 0.7152 * f(c[1]) + 0.0722 * f(c[2]); };
// 바탕은 글자 가운데를 감싸는 채워진 도형 가운데 글자보다 먼저 그린 마지막 것이다
const filled = [...svg.querySelectorAll('rect, circle, ellipse, polygon, path')]
  .filter((el) => !el.closest('defs, marker') && rgb(getComputedStyle(el).fill));
for (const { t, b } of boxes) {
  const fg = rgb(getComputedStyle(t).fill);
  if (!fg) continue;
  const cx = b.x + b.w / 2, cy = b.y + b.h / 2;
  const under = filled.filter((el) => {
    const r = box(el);
    return (el.compareDocumentPosition(t) & Node.DOCUMENT_POSITION_FOLLOWING) && cx >= r.x && cx <= r.x + r.w && cy >= r.y && cy <= r.y + r.h;
  }).pop();
  const bg = under ? rgb(getComputedStyle(under).fill) : [255, 255, 255];
  const [l1, l2] = [lum(fg), lum(bg)].sort((x, y) => y - x);
  const ratio = (l1 + 0.05) / (l2 + 0.05);
  if (ratio < 4.5) res.issues.push({ kind: 'contrast', text: label(t) + ' ' + ratio.toFixed(1) + ':1' });
}
// 도형 좌표와 크기가 4의 배수인지. 선 굵기, 반지름, path 좌표는 보지 않는다
const GRID_ATTRS = { rect: ['x', 'y', 'width', 'height'], line: ['x1', 'y1', 'x2', 'y2'], circle: ['cx', 'cy'], ellipse: ['cx', 'cy'], text: ['x', 'y'] };
const offGrid = [];
for (const [tag, attrs] of Object.entries(GRID_ATTRS)) {
  for (const el of svg.querySelectorAll(tag)) {
    if (el.closest('defs, marker')) continue;
    for (const a of attrs) {
      const v = el.getAttribute(a);
      if (v !== null && v.trim() !== '' && !/%$/.test(v) && Number(v) % 4 !== 0) offGrid.push(tag + ' ' + a + '=' + v);
    }
  }
}
if (offGrid.length) res.issues.push({ kind: 'grid', text: offGrid.slice(0, 5).join(', ') + (offGrid.length > 5 ? ' 외 ' + (offGrid.length - 5) + '개' : '') });
// 노드 밖 글자가 노드 도형에 4보다 가까이 붙어 있는지
const nodeShapes = [...svg.querySelectorAll('.node')].map((g) => g.querySelector('rect, circle, ellipse, polygon, path')).filter(Boolean);
for (const { t, b } of boxes) {
  if (t.closest('.node')) continue;
  for (const s of nodeShapes) {
    const r = box(s);
    if (inter(b, { x: r.x - 4, y: r.y - 4, w: r.w + 8, h: r.h + 8 }) > 0) { res.issues.push({ kind: 'text-touch', text: label(t) }); break; }
  }
}
// 노드끼리 겹치는지 본다
const nodes = [...svg.querySelectorAll('.node')].map((g) => ({ g, b: box(g.querySelector('rect, circle, ellipse, polygon, path') || g) }));
for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
  if (nodes[i].g.contains(nodes[j].g) || nodes[j].g.contains(nodes[i].g)) continue;
  if (inter(nodes[i].b, nodes[j].b) > 4) res.issues.push({ kind: 'node-overlap', text: label(nodes[i].g) + ' / ' + label(nodes[j].g) });
}
document.getElementById('result').textContent = JSON.stringify(res);
`;

// 다크 모드 색을 정의한 그림인지
const DARK_QUERY = /@media\s*\(\s*prefers-color-scheme\s*:\s*dark\s*\)/;

function render(svgFile, chrome, dark = false) {
  let svg = fs.readFileSync(svgFile, 'utf8').replace(/<\?xml[^>]*\?>/, '');
  // 브라우저 설정과 관계없이 다크 색으로 그리려고 미디어 조건을 항상 참으로 바꾼다
  if (dark) svg = svg.replace(new RegExp(DARK_QUERY.source, 'g'), '@media all');
  const vb = /viewBox="\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)\s+([\d.]+)/.exec(svg);
  if (!vb) return { error: 'viewBox가 없어 렌더링하지 않았다' };
  const base = rel(svgFile).replace(/[\\/]/g, '__').replace(/\.svg$/i, '') + (dark ? '.dark' : '');
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const html = path.join(OUT_DIR, `${base}.probe.html`);
  const png = path.join(OUT_DIR, `${base}.png`);
  // 결과는 console.log로 내보낸다. Windows의 Chrome은 표준 출력을 넘기지 않으므로 로그 파일에서 읽는다.
  fs.writeFileSync(html, `<!doctype html><meta charset="utf-8"><style>html,body{margin:0;overflow:hidden;background:#fff}svg{display:block}${FONT ? `svg text:not(.mono),svg tspan:not(.mono){font-family:"${FONT}"!important}` : ''}</style><body>${svg}<script>${PROBE.replace("document.getElementById('result').textContent = JSON.stringify(res);", "console.log('CHECK_DOCS_RESULT ' + JSON.stringify(res));")}</script>`);
  // Chrome 하위 프로세스가 늦게 끝나며 파일을 잡고 있을 수 있어 그림마다 새 프로필을 쓴다
  const PROFILE = path.join(PROFILE_BASE, String(renderCount++));
  fs.mkdirSync(PROFILE, { recursive: true });
  const run = spawnSync(chrome, [
    '--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--hide-scrollbars',
    `--user-data-dir=${PROFILE}`, '--enable-logging', '--v=0', '--virtual-time-budget=3000',
    `--window-size=${Math.ceil(Number(vb[1]))},${Math.ceil(Number(vb[2]))}`, `--screenshot=${png}`, pathToFileURL(html).href,
  ], { encoding: 'utf8', timeout: 90000 });
  // Chrome은 주 프로세스가 끝난 뒤에 로그를 쓰기도 하므로 결과 줄이 나올 때까지 잠시 기다린다
  const logFile = path.join(PROFILE, 'chrome_debug.log');
  let line;
  for (let waited = 0; waited <= 15000 && !line; waited += 250) {
    if (waited) Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 250);
    const text = [run.stdout || '', run.stderr || '', fs.existsSync(logFile) ? fs.readFileSync(logFile, 'utf8') : ''].join('\n');
    line = text.split('\n').find((l) => l.includes('CHECK_DOCS_RESULT '));
  }
  if (!line) return { error: `렌더링 결과를 읽지 못했다. 확인용 파일: ${html}` };
  fs.rmSync(html, { force: true });
  const json = line.slice(line.indexOf('CHECK_DOCS_RESULT ') + 18).replace(/",\s*source:.*$/, '').replace(/"$/, '');
  return { ...JSON.parse(json), png };
}

const rendered = [];
if (RENDER) {
  const chrome = findChrome();
  if (!chrome) add('warn', 'render', '-', 'Chrome 또는 Edge를 찾지 못해 렌더링 검사를 건너뛴다. CHROME_PATH로 지정할 수 있다');
  else {
    for (const f of svgFiles) {
      const res = render(f, chrome);
      if (res.error) { add('error', 'render', rel(f), res.error); continue; }
      rendered.push({ file: rel(f), png: res.png, size: `${res.width}x${res.height}` });
      const ratio = res.width / res.height;
      // 저장소 화면의 본문 폭(약 840px)보다 넓은 그림은 줄어서 보인다. 줄어든 뒤 가장 작은 글자가 12px 아래면 읽기 어렵다
      const BODY_WIDTH = 840;
      const shown = res.minFont * Math.min(1, BODY_WIDTH / res.width);
      if (res.minFont && shown < 12) add('warn', 'render-size', rel(f), `본문 폭(${BODY_WIDTH}px)에서 가장 작은 글자가 ${shown.toFixed(1)}px로 보인다. 폭을 ${BODY_WIDTH} 안팎으로 줄이거나 글자를 키운다`);
      if (ratio > 4 && res.width > BODY_WIDTH) add('warn', 'render-size', rel(f), `가로세로 비 ${ratio.toFixed(1)}. 본문 폭으로 줄면 글자가 너무 작아진다`);
      const report = (issues, mode) => {
        const seen = new Set();
        for (const is of issues) {
          const key = is.kind + is.text;
          if (seen.has(key)) continue;
          seen.add(key);
          add(['margin', 'contrast', 'grid', 'text-touch'].includes(is.kind) ? 'warn' : 'error', `render-${is.kind}`, rel(f), is.text + mode);
        }
      };
      report(res.issues, '');
      if (DARK_QUERY.test(fs.readFileSync(f, 'utf8'))) {
        const dark = render(f, chrome, true);
        if (dark.error) add('error', 'render', rel(f), `다크: ${dark.error}`);
        else {
          rendered.push({ file: `${rel(f)} (다크)`, png: dark.png, size: `${dark.width}x${dark.height}` });
          report(dark.issues, ' (다크)');
        }
      }
    }
    try { fs.rmSync(PROFILE_BASE, { recursive: true, force: true, maxRetries: 10, retryDelay: 500 }); } catch { /* 브라우저가 늦게 닫혀도 결과에는 영향이 없다 */ }
  }
}

// ---------- 출력 ----------
if (JSON_OUT) {
  console.log(JSON.stringify({ root: ROOT, errors, warnings, rendered }, null, 2));
} else {
  const show = (list, tag) => list.forEach((x) => console.log(`${tag} [${x.rule}] ${x.file}  ${x.msg}`));
  show(errors, '오류');
  show(warnings, '경고');
  if (rendered.length) {
    console.log(`\n렌더링 결과 (${OUT_DIR})`);
    rendered.forEach((r) => console.log(`  ${r.file}  ${r.size}  →  ${path.basename(r.png)}`));
  }
  console.log(`\n문서 ${mdFiles.length}개, 그림 ${svgFiles.length}개 검사. 오류 ${errors.length}, 경고 ${warnings.length}`);
}
process.exit(errors.length ? 1 : 0);
