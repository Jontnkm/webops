// 데모 사이트 만들기: 켜져 있는 WebOps 서버(127.0.0.1:8080)의 화면을 정적 파일로 떠서 demo/site 에 저장한다.
// - 테스트 페이지(127.0.0.1:4173) 검사만 넣는다. 다른 주소가 하나라도 섞인 검사는 넣지 않고 멈춘다 (고객사 결과 보호).
// - 실행: WebOps 폴더에서  node demo/build-demo.mjs   (서버를 먼저 켜 둘 것)
import { mkdir, writeFile, rm, copyFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = join(HERE, 'site');
const SERVER = process.env.WEBOPS_SERVER ?? 'http://127.0.0.1:8080';
const ALLOWED_ORIGIN = 'http://127.0.0.1:4173';

// 데모에 넣을 검사 (최근 것부터). 점검 종류 7가지 + 비교용 지난 종합점검 1건
const SCANS = [
  'J-20261119-101151-3335', // 모바일환경
  'J-20261119-101151-a425', // 보안
  'J-20261119-101151-6cd3', // SEO
  'J-20261119-101151-a97e', // 로딩 속도
  'J-20261119-101151-e829', // 웹접근성
  'J-20261119-101150-081c', // 기능
  'J-20261119-101150-bd70', // 종합 (전체 점검)
  'J-20261119-094357-c7e1', // 종합 (bd70의 지난 검사, 비교 화면용)
];
const PER_PAGE = 6;

async function get(path, type = 'text') {
  const res = await fetch(SERVER + path);
  if (!res.ok) throw new Error(`${path} → 응답 코드 ${res.status}`);
  return type === 'buf' ? Buffer.from(await res.arrayBuffer()) : res.text();
}
async function save(rel, data) {
  const file = join(OUT, rel);
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, data);
}

// 데모 안내 띠 옆 버튼: 첫 화면은 '결과 예시 보기'(종합점검 결과로), 결과 화면은 '다른 예시 보기'(첫 화면 최근 검사 목록으로)
const FEATURED = 'J-20261119-101150-bd70';
const ARROW = '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M4 10h11M11 5.5 15.5 10 11 14.5" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>';
const CHART = '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><path d="M3.5 16.5h13M6 13.5V9M10 13.5V5.5M14 13.5v-6" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>';
const GRID = '<svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true"><rect x="3.5" y="3.5" width="5.5" height="5.5" rx="1.4" fill="none" stroke="currentColor" stroke-width="2"/><rect x="11" y="3.5" width="5.5" height="5.5" rx="1.4" fill="none" stroke="currentColor" stroke-width="2"/><rect x="3.5" y="11" width="5.5" height="5.5" rx="1.4" fill="none" stroke="currentColor" stroke-width="2"/><rect x="11" y="11" width="5.5" height="5.5" rx="1.4" fill="none" stroke="currentColor" stroke-width="2"/></svg>';
function demoDock(prefix, isHome) {
  const cta = isHome
    ? `<a class="demo-cta" href="scans/${FEATURED}.html"><span class="demo-cta-ico">${CHART}</span><span>결과 예시 보기</span><span class="demo-cta-arrow">${ARROW}</span></a>`
    : `<a class="demo-cta demo-cta-ghost" href="${prefix}index.html#recent"><span class="demo-cta-ico">${GRID}</span><span>다른 예시 보기</span><span class="demo-cta-arrow">${ARROW}</span></a>`;
  return `<div class="demo-dock">
  <p class="demo-note"><b>데모</b><span>저장된 예시 화면 · 실제 검사는 실행되지 않음</span><span class="long">· 검사 대상: 일부러 오류를 넣은 테스트 페이지</span></p>
  ${cta}
</div>`;
}

// 모든 페이지에 넣는 데모 안내 띠 + 서버가 필요한 버튼 막기
function injectDemo(html, prefix, isHome = false) {
  return html
    .replace('</head>', `  <link rel="stylesheet" href="${prefix}demo.css">\n</head>`)
    .replace(/<body([^>]*)>/, `<body$1>\n${demoDock(prefix, isHome)}`)
    .replace('</body>', `<script src="${prefix}demo.js"></script>\n</body>`);
}

// 서버 주소를 데모 파일 주소로 바꾸고, 바꿀 수 없는 서버 기능(검사 실행·삭제·정기 검사 등)은 막는다
function rewrite(html, prefix, inScanDir) {
  const scanHref = (id) => (inScanDir ? `${id}.html` : `scans/${id}.html`);
  html = html
    .replace(/(["'])\/css\/app\.css\1/g, `$1${prefix}css/app.css$1`)
    .replace(/\/scans\/(J-[\w-]+)\/shots\/([\w.-]+)/g, `${prefix}shots/$1/$2`)
    .replace(/\/scans\/(J-[\w-]+)\/(report\.pdf|a11y-report\.pdf|report\.json)/g, `${prefix}files/$1/$2`)
    .replace(/href="\/scans\/(J-[\w-]+)"/g, (m, id) => (SCANS.includes(id) ? `href="${scanHref(id)}"` : 'href="#" data-demo-off'))
    .replace(/href="\/(#recent)?"/g, (m, h) => `href="${prefix}index.html${h ?? ''}"`)
    .replace(/href="\/[^"]*"/g, 'href="#" data-demo-off')
    .replace(/action="\/[^"]*"/g, 'action="#" data-demo-off');
  return html;
}

// 첫 화면: 서버 첫 화면을 바탕으로 최근 검사 목록만 데모 검사로 바꾼다 (index.html, index-2.html …)
async function buildHome() {
  // 데모 검사의 카드 <li> 를 서버 검색 결과에서 모은다
  const cards = new Map();
  for (let page = 1; page <= 10 && cards.size < SCANS.length; page++) {
    const html = await get(`/?q=${encodeURIComponent('127.0.0.1:4173')}&page=${page}`);
    for (const li of html.match(/<li>\s*<a class="recent-card"[\s\S]*?<\/li>/g) ?? []) {
      const id = /href="\/scans\/(J-[\w-]+)"/.exec(li)?.[1];
      if (SCANS.includes(id)) cards.set(id, li.replace(/<form class="rc-delete"[\s\S]*?<\/form>/, ''));
    }
  }
  const missing = SCANS.filter((id) => !cards.has(id));
  if (missing.length) throw new Error('첫 화면 카드를 찾지 못함: ' + missing.join(', '));

  const base = await get('/');
  const pages = Math.ceil(SCANS.length / PER_PAGE);
  const fileOf = (n) => (n === 1 ? 'index.html' : `index-${n}.html`);
  for (let n = 1; n <= pages; n++) {
    const list = SCANS.slice((n - 1) * PER_PAGE, n * PER_PAGE).map((id) => '      ' + cards.get(id).trim()).join('\n');
    let pager = '';
    if (pages > 1) {
      const nums = Array.from({ length: pages }, (_, i) => i + 1).map((k) => (k === n
        ? `<span class="pager-num current" aria-current="page">${k}</span>`
        : `<a class="pager-num" href="${fileOf(k)}#recent" aria-label="${k}쪽">${k}</a>`)).join('\n      ');
      const prev = n > 1 ? `<a class="pager-btn" href="${fileOf(n - 1)}#recent">‹ 이전</a>` : '<span class="pager-btn disabled" aria-hidden="true">‹ 이전</span>';
      const next = n < pages ? `<a class="pager-btn" href="${fileOf(n + 1)}#recent">다음 ›</a>` : '<span class="pager-btn disabled" aria-hidden="true">다음 ›</span>';
      pager = `<nav class="pager" aria-label="최근 검사 페이지">\n      ${prev}\n      ${nums}\n      ${next}\n    </nav>`;
    }
    let html = base
      .replace(/<ul class="recent-grid">[\s\S]*?<\/ul>\s*(<!--[^>]*-->\s*)?(<nav class="pager"[\s\S]*?<\/nav>)?/, `<ul class="recent-grid">\n${list}\n    </ul>\n\n    ${pager}\n`)
      .replace(/(<span class="rt-label">최근 검사 기록<\/span>\s*<b>)\d+(<\/b>)/, `$1${SCANS.length}$2`)
      .replace(/<p class="recent-empty"[\s\S]*?<\/p>/, '');
    html = injectDemo(rewrite(html, '', false), '', true);
    await save(fileOf(n), html);
  }
}

// 검사 결과 화면 + 캡처 + PDF + 원본 JSON
async function buildScan(id) {
  const json = await get(`/scans/${id}/report.json`);
  const report = JSON.parse(json);
  const urls = [report.scope?.mobileUrl, ...(report.pages ?? []).flatMap((p) => [p.requestedUrl, p.pageUrl])].filter(Boolean);
  if (!urls.length) throw new Error(`${id}: 검사한 주소를 확인할 수 없어 넣지 않음`);
  for (const u of urls) {
    if (new URL(u).origin !== ALLOWED_ORIGIN) throw new Error(`${id}: 테스트 페이지가 아닌 주소(${u})가 있어 넣지 않음`);
  }

  let html = await get(`/scans/${id}`);
  const shots = [...new Set([...html.matchAll(new RegExp(`/scans/${id}/shots/([\\w.-]+)`, 'g'))].map((m) => m[1]))];
  for (const s of shots) await save(`shots/${id}/${s}`, await get(`/scans/${id}/shots/${s}`, 'buf'));
  await save(`files/${id}/report.json`, json);
  for (const pdf of ['report.pdf', 'a11y-report.pdf']) {
    if (html.includes(`/scans/${id}/${pdf}`)) {
      process.stdout.write(`  ${id} ${pdf} 만드는 중…\n`);
      await save(`files/${id}/${pdf}`, await get(`/scans/${id}/${pdf}`, 'buf'));
    }
  }
  html = injectDemo(rewrite(html, '../', true), '../');
  await save(`scans/${id}.html`, html);
  return shots.length;
}

await rm(OUT, { recursive: true, force: true });
await mkdir(OUT, { recursive: true });
await save('css/app.css', await get('/css/app.css'));
await copyFile(join(HERE, 'demo.css'), join(OUT, 'demo.css'));
await copyFile(join(HERE, 'demo.js'), join(OUT, 'demo.js'));
await save('.nojekyll', '');
for (const id of SCANS) {
  const n = await buildScan(id);
  console.log(`검사 ${id} 저장 (캡처 ${n}개)`);
}
await buildHome();
console.log('완료 → ' + OUT);
