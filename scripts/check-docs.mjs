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
  '.cache', 'tmp', '.claude', '.agents',
]);

// 저장소에 남기지 않는 작업 부산물. 이름만으로 판단할 수 있는 것만 둔다.
const STRAY_PATTERNS = [
  /^REPORT(\..+)?\.md$/i, /^SESSION.*\.md$/i, /^HANDOFF.*\.md$/i, /^PROGRESS\.(md|txt)$/i,
  /^NOTES?\.md$/i, /^TODO\.md$/i, /^SUMMARY-.*\.md$/i, /.*-(report|summary|session|handoff|review-log)\.md$/i,
  /^(BASELINE|MIGRATION)[-_].*\.md$/i, /^claude-progress\.txt$/i, /\.(orig|rej|bak)$/i,
];

const CORE_DOCS = [
  'README.md', 'AGENTS.md', 'docs/ARCHITECTURE.md', 'docs/DEVELOPMENT.md', 'docs/TESTING.md',
  'docs/WRITING.md', 'docs/templates/requirement.md', 'docs/templates/plan.md', 'docs/templates/decision.md',
];

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
  for (const line of stripCode(md).split('\n')) {
    const m = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (!m) continue;
    const base = slugify(m[2].replace(/\[([^\]]*)\]\([^)]*\)/g, '$1'));
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
      if (/\{\{(?!\.\.\.\}\})[^}]+\}\}/.test(l)) add('error', 'placeholder', `${r}:${i + 1}`, '채우지 않은 자리표시자 {{...}}');
      if (/^\s*<!--\s*안내/.test(l)) add('error', 'placeholder', `${r}:${i + 1}`, '템플릿 안내 주석이 남아 있다');
    });
    if (clean.replace(/^#.*$/gm, '').replace(/\s/g, '').length < 40 && !/^(CLAUDE|GEMINI)\.md$/i.test(path.basename(file))) {
      add('error', 'empty-doc', r, '내용이 거의 없는 문서. 채우거나 지운다');
    }
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
  const vb = /viewBox="\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)\s+([\d.]+)/.exec(svg);
  if (vb) {
    const w = Number(vb[1]);
    for (const m of svg.matchAll(/font-size(?:="|:\s*)([\d.]+)/g)) {
      const px = (Number(m[1]) / w) * 880; // 일반적인 Markdown 본문 폭에서 보이는 크기
      if (px < 11) { add('error', 'svg-font', r, `글자 ${m[1]}px은 본문 폭 880px에서 ${px.toFixed(1)}px로 보인다. 최소 11px`); break; }
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
const MARGIN = 40; // 바깥 여백은 64px로 그린다. 40px 안쪽으로 들어온 글자는 여백 침범으로 본다
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
// class="node" 그룹 안의 글자는 첫 도형 안에 8px 여백을 두고 들어가야 한다
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
// class="edge" 연결선이 글자 위를 지나가는지 본다
for (const e of svg.querySelectorAll('.edge')) {
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
for (const e of svg.querySelectorAll('.edge')) {
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
// 노드끼리 겹치는지 본다
const nodes = [...svg.querySelectorAll('.node')].map((g) => ({ g, b: box(g.querySelector('rect, circle, ellipse, polygon, path') || g) }));
for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
  if (nodes[i].g.contains(nodes[j].g) || nodes[j].g.contains(nodes[i].g)) continue;
  if (inter(nodes[i].b, nodes[j].b) > 4) res.issues.push({ kind: 'node-overlap', text: label(nodes[i].g) + ' / ' + label(nodes[j].g) });
}
document.getElementById('result').textContent = JSON.stringify(res);
`;

function render(svgFile, chrome) {
  const svg = fs.readFileSync(svgFile, 'utf8').replace(/<\?xml[^>]*\?>/, '');
  const vb = /viewBox="\s*[-\d.]+\s+[-\d.]+\s+([\d.]+)\s+([\d.]+)/.exec(svg);
  if (!vb) return { error: 'viewBox가 없어 렌더링하지 않았다' };
  const base = rel(svgFile).replace(/[\\/]/g, '__').replace(/\.svg$/i, '');
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const html = path.join(OUT_DIR, `${base}.probe.html`);
  const png = path.join(OUT_DIR, `${base}.png`);
  // 결과는 console.log로 내보낸다. Windows의 Chrome은 표준 출력을 넘기지 않으므로 로그 파일에서 읽는다.
  fs.writeFileSync(html, `<!doctype html><meta charset="utf-8"><style>html,body{margin:0;overflow:hidden;background:#fff}svg{display:block}${FONT ? `svg text{font-family:"${FONT}"!important}` : ''}</style><body>${svg}<script>${PROBE.replace("document.getElementById('result').textContent = JSON.stringify(res);", "console.log('CHECK_DOCS_RESULT ' + JSON.stringify(res));")}</script>`);
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
      if (res.width < 960) add('warn', 'render-size', rel(f), `폭 ${res.width}px. 발표 자료에 쓰려면 1600px 기준으로 그린다`);
      if (ratio > 4) add('warn', 'render-size', rel(f), `가로세로 비 ${ratio.toFixed(1)}. 본문 폭에서 글자가 너무 작아진다`);
      const seen = new Set();
      for (const is of res.issues) {
        const key = is.kind + is.text;
        if (seen.has(key)) continue;
        seen.add(key);
        add(is.kind === 'margin' ? 'warn' : 'error', `render-${is.kind}`, rel(f), is.text);
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
