// 검사 전체 흐름
//   페이지마다: 열기(끝까지 스크롤) → 접근성 → 링크 → 문제 위치 캡처 → 휴대폰 화면
//   웹사이트 전체 점검(maxPages > 1): ① 같은 사이트 링크를 빠르게 훑어 검사할 페이지를 모두 찾고(총 개수 확정)
//                                    ② 찾은 페이지를 탭 여러 개로 나눠 검사
//   첫 페이지만: 로딩 속도·검색 노출(Lighthouse) → 표준 JSON으로 변환
// 진행 상황은 onStep(phase, done, total)으로 알린다 (서버 화면의 진행 막대용)
//   page      한 페이지 검사의 단계 (done번째 단계 시작 / 전체 단계 수)
//   discover  페이지 찾는 중 (done: 훑어본 수, total: 지금까지 찾은 수)
//   scan      페이지 검사 (done: 끝난 페이지 수, total: 검사할 페이지 수)
//   lighthouse 로딩 속도 · 검색 노출 측정 중
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { loadPage } from './checks/page.js';
import { checkA11y } from './checks/a11y.js';
import { checkLinks } from './checks/links.js';
import { runLighthouse } from './checks/lighthouse.js';
import { checkMobile } from './checks/mobile.js';
import { captureElements } from './checks/shots.js';
import { buildReport, makeJobId } from './normalize.js';

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));
const DEFAULT_OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', 'output');

// 한 번에 검사할 수 있는 최대 페이지 수 (웹사이트 전체 점검의 안전 상한은 서버 설정으로 이보다 작게 준다)
export const MAX_PAGES_LIMIT = 1000;
// 문제 위치 캡처 개수: 첫 페이지는 넉넉히, 나머지 페이지는 시간이 오래 걸리지 않게 적게
const SHOTS_FIRST_PAGE = 30;
const SHOTS_OTHER_PAGE = 10;
// 게시판 글처럼 '내용만 다르고 화면 모양이 같은' 페이지는 기본으로 1개만 검사한다 (posts: 'latest').
// 같은 모양인지는 templateKey로 본다: 주소에서 게시물 번호에 해당하는 값만 빼고 같으면 같은 모양.
// 게시물 번호로 보는 값 (대소문자 무시)
//   - 이름만으로 게시물 번호가 확실한 것: nttId, bbsSn, articleId, wr_id … (값 상관없음)
//   - 흔한 이름은 값이 숫자일 때만: id, no, num, seq, idx, sn, page … (contents.do?id=about 같은 메뉴 페이지는 그대로 둠)
//   - 경로 중 숫자만 있는 칸: /board/view/123
// menuNo · key 같은 메뉴 구분 값은 빼지 않으므로 메뉴 페이지들은 서로 다른 페이지로 남는다.
const POST_KEYS = new Set([
  'nttid', 'nttsn', 'nttno', 'bbssn', 'bbsno', 'articleid', 'articleno', 'articleseq', 'artclseq',
  'boardseq', 'boardno', 'board_idx', 'postid', 'postno', 'wr_id', 'datasid', 'datano', 'list_no', 'bno', 'bidx',
  'docid', 'docno',
]);
const NUMERIC_POST_KEYS = new Set(['id', 'no', 'num', 'seq', 'idx', 'sn', 'page', 'pageno', 'pageindex', 'cpage', 'currentpage', 'curpage', 'pagenum']);
// 페이지 찾기 단계에서 동시에 여는 탭 수 (그림·글꼴은 받지 않으므로 가볍다)
const DISCOVER_CONCURRENCY = 5;

const IMPACT_RANK = { critical: 0, serious: 1, moderate: 2, minor: 3 };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * URL 하나(또는 그 사이트의 여러 페이지)를 검사해 표준 리포트 객체를 돌려준다.
 * @param {string} url
 * @param {{ lighthouse?: boolean, maxLinks?: number, maxPages?: number, concurrency?: number, scroll?: boolean, shots?: boolean,
 *           timeoutMs?: number, jobId?: string, out?: string, onProgress?: (msg: string) => void,
 *           onStep?: (phase: string, done: number, total: number) => void }} options
 *   concurrency: 여러 페이지를 검사할 때 동시에 여는 탭 수 (1~5)
 */
export async function scanUrl(url, options = {}) {
  const opts = {
    lighthouse: true, maxLinks: 300, maxPages: 1, concurrency: 1, posts: 'latest', scroll: true, shots: true, timeoutMs: 30000,
    out: DEFAULT_OUT, onProgress: () => {}, onStep: () => {}, ...options,
  };
  const maxPages = Math.min(Math.max(1, opts.maxPages), MAX_PAGES_LIMIT);
  const multi = maxPages > 1;
  const concurrency = multi ? Math.min(Math.max(1, opts.concurrency), 5) : 1;
  const jobId = opts.jobId ?? makeJobId();
  const startedAt = new Date();
  const warnings = [];
  const pages = [];
  const linkCache = new Map(); // 여러 페이지에 같은 링크가 있어도 한 번만 확인
  const shotBase = { dir: path.join(opts.out, 'shots', jobId), urlPrefix: `shots/${jobId}` };
  // 한 페이지 검사 단계 수: 열기 · 접근성 · 링크 · (캡처) · 휴대폰 · (로딩 속도)
  const singleSteps = 4 + (opts.shots ? 1 : 0) + (opts.lighthouse ? 1 : 0);
  let browserVersion = null;
  let found = { targets: [url], remaining: 0, skippedByPath: 0 };

  const browser = await chromium.launch();
  try {
    browserVersion = browser.version();
    const context = await browser.newContext();

    // ① 웹사이트 전체 점검: 검사할 페이지를 먼저 모두 찾는다
    if (multi) {
      opts.onProgress('검사할 페이지 찾는 중');
      found = await discoverPages(browser, url, maxPages, opts);
      opts.onProgress(`페이지 ${found.targets.length}개를 찾았습니다. 검사를 시작합니다`);
    }
    const total = found.targets.length;
    let done = 0;
    if (multi) opts.onStep('scan', 0, total);

    const scanned = new Set();
    let lastIndex = 0;

    /** 페이지 하나 검사. 다른 주소로 넘어갔는데 이미 검사한 페이지면 null */
    const scanOne = async (target, index) => {
      const label = multi ? `[페이지 ${Math.min(done + 1, total)} / ${total}] ` : '';
      const where = index > 1 ? ` (${shortPath(target)})` : '';
      let step = 0;
      const progress = (msg) => {
        opts.onProgress(`${label}${msg}${where}`);
        if (!multi) opts.onStep('page', ++step, singleSteps);
      };
      const rp = { index, requestedUrl: target, loadError: null, page: null, a11y: null, links: null, mobile: null, lighthouse: null, shots: {} };
      const tab = await context.newPage();
      try {
        progress(index === 1 ? '페이지 여는 중 (PC 화면, 끝까지 스크롤)' : '페이지 여는 중');
        try {
          rp.page = await loadPage(tab, target, opts);
        } catch (err) {
          rp.loadError = firstLine(err.message);
        }
        // 예: /index.do → /main.do 처럼 이미 검사한 페이지로 넘어가면 건너뛴다
        if (rp.page && index > 1 && scanned.has(pageKey(rp.page.finalUrl))) return null;
        if (rp.page) scanned.add(pageKey(rp.page.finalUrl));
        if (!rp.page) return rp;

        const warn = (name) => (index > 1 ? `${name} (${shortPath(rp.page.finalUrl)})` : name);
        progress('접근성 검사 중');
        rp.a11y = await attempt(warn('접근성 검사'), () => checkA11y(tab), warnings);
        progress(`링크 확인 중 (${rp.page.links.length}개)`);
        rp.links = await attempt(warn('링크 검사'), () => checkLinks(rp.page.links, { ...opts, cache: linkCache }), warnings);
        if (opts.shots) {
          progress('문제 위치 화면 캡처 중');
          rp.shots = (await attempt(warn('문제 위치 캡처'), () => captureElements(tab, shotTargets(rp), {
            ...shotBase, prefix: `p${index}-`, max: index === 1 ? SHOTS_FIRST_PAGE : SHOTS_OTHER_PAGE,
          }), warnings)) ?? {};
        }
        progress('휴대폰 화면 확인 중');
        const mobileShots = opts.shots ? { ...shotBase, prefix: `p${index}-m` } : null;
        rp.mobile = await attempt(warn('휴대폰 화면 확인'), () => checkMobile(browser, rp.page.finalUrl, { ...opts, shots: mobileShots }), warnings);
        return rp;
      } finally {
        await tab.close().catch(() => {});
      }
    };

    // ② 찾은 페이지를 탭 여러 개로 나눠 검사 (첫 페이지가 1번)
    const queue = [...found.targets];
    const worker = async () => {
      while (queue.length > 0) {
        const target = queue.shift();
        const rp = await scanOne(target, ++lastIndex);
        if (rp) pages.push(rp);
        done++;
        if (multi) opts.onStep('scan', done, total);
      }
    };
    await Promise.all(Array.from({ length: concurrency }, worker));
  } finally {
    await browser.close();
  }
  // 동시에 검사하면 끝나는 순서가 섞이므로 찾은 순서(페이지 번호)대로 정리
  pages.sort((a, b) => a.index - b.index);

  const first = pages[0];
  if (first?.page && opts.lighthouse) {
    opts.onProgress('로딩 속도 · 검색 노출 점수 측정 중 (첫 페이지, 20~60초)');
    opts.onStep(multi ? 'lighthouse' : 'page', singleSteps, singleSteps);
    first.lighthouse = await attempt('로딩 속도 · 검색 노출 측정', () => runLighthouse(first.page.finalUrl), warnings);
  }
  if (multi) {
    if (found.remaining > 0) {
      warnings.push(`최대 ${maxPages}페이지까지만 검사함 (아직 확인하지 않은 같은 사이트 주소 ${found.remaining}개)`);
    }
    if (found.skippedByPath > 0) {
      warnings.push(`게시판 글처럼 내용만 다르고 화면 모양이 같은 페이지 ${found.skippedByPath}개는 1개만 검사함 (상세 옵션에서 '모든 게시물'을 고르면 모두 검사)`);
    }
  }

  return buildReport({
    jobId, startedAt, finishedAt: new Date(), toolVersion: pkg.version, browserVersion,
    raw: {
      pages,
      warnings,
      scope: {
        maxPages,
        pageCount: pages.length,
        linksChecked: linkCache.size,
        scrolled: Boolean(opts.scroll),
        remaining: found.remaining,
      },
    },
  });
}

/**
 * 웹사이트 전체 점검 ① 검사할 페이지 찾기.
 * 같은 사이트 링크를 빠르게 훑는다 (그림·글꼴·동영상은 받지 않고, 화면 틀이 뜨면 바로 링크만 모음).
 * 열리지 않는 주소(오류 응답)와 다른 주소로 넘어가 이미 찾은 페이지는 빼고,
 * 게시물처럼 모양이 같은 페이지는 posts가 'latest'면 처음 찾은 1개만 (게시판 목록은 보통 최신 글이 위에 있어 최신 게시물).
 * @returns {Promise<{ targets: string[], remaining: number, skippedByPath: number }>}
 */
async function discoverPages(browser, startUrl, maxPages, opts) {
  const context = await browser.newContext();
  await context.route('**/*', (route) => {
    const type = route.request().resourceType();
    return ['image', 'media', 'font', 'stylesheet'].includes(type) ? route.abort() : route.continue();
  });
  const targets = [];
  const queue = [startUrl];
  const queued = new Set([pageKey(startUrl)]);
  const perTemplate = new Set([templateKey(startUrl)]);
  const onePerTemplate = opts.posts !== 'all';
  const finals = new Set();
  let skippedByPath = 0;
  let processed = 0;
  let overflow = 0;
  let active = 0;
  let siteHost = null;
  const noCache = new Map();

  const visit = async (target, isFirst) => {
    const page = await context.newPage();
    try {
      const res = await page.goto(target, { waitUntil: 'domcontentloaded', timeout: Math.min(opts.timeoutMs, 20000) });
      const finalUrl = page.url();
      if (isFirst) siteHost = hostOf(finalUrl);
      // 첫 페이지는 열리지 않아도 검사 대상에 넣는다 (접속 불가로 보고하기 위해)
      if (!isFirst && (!res || res.status() >= 400)) return;
      if (!isFirst && (hostOf(finalUrl) !== siteHost || finals.has(pageKey(finalUrl)))) return;
      finals.add(pageKey(finalUrl));
      // 상한이 차서 넣지 못한 페이지도 '남은 주소'로 센다
      if (targets.length >= maxPages) {
        overflow++;
        return;
      }
      targets.push(target);
      const links = await page.evaluate(() => [...document.querySelectorAll('a[href]')].map((a) => ({ href: a.href })));
      for (const next of await nextPages(links, siteHost, noCache)) {
        if (queued.has(next)) continue;
        queued.add(next);
        const tk = templateKey(next);
        if (onePerTemplate && perTemplate.has(tk)) {
          skippedByPath++;
          continue;
        }
        perTemplate.add(tk);
        queue.push(next);
      }
    } catch {
      if (isFirst) targets.push(target);
    } finally {
      await page.close().catch(() => {});
      processed++;
      opts.onStep('discover', processed, targets.length);
    }
  };

  try {
    opts.onStep('discover', 0, 1);
    await visit(queue.shift(), true);
    const worker = async () => {
      while (targets.length < maxPages) {
        const target = queue.shift();
        if (!target) {
          if (active === 0) return;
          await sleep(100);
          continue;
        }
        active++;
        try {
          await visit(target, false);
        } finally {
          active--;
        }
      }
    };
    await Promise.all(Array.from({ length: DISCOVER_CONCURRENCY }, worker));
  } finally {
    await context.close().catch(() => {});
  }
  return { targets: targets.slice(0, maxPages), remaining: queue.length + overflow, skippedByPath };
}

/**
 * 화면 모양이 같은 페이지인지 보는 열쇠: 게시물 번호에 해당하는 값을 뺀 주소.
 *   /board/view.do?menuNo=22&nttId=101 → /board/view.do?menuNo=22   (nttId는 게시물 번호)
 *   /board/view/123                    → /board/view/:n
 *   /contents.do?menuNo=100            → 그대로 (메뉴 페이지는 서로 다름)
 */
export function templateKey(url) {
  try {
    const u = new URL(url);
    const path = u.pathname.split('/').map((s) => (/^\d+$/.test(s) ? ':n' : s)).join('/');
    const keep = [...u.searchParams]
      .filter(([k, v]) => {
        const key = k.toLowerCase();
        if (POST_KEYS.has(key)) return false;
        if (NUMERIC_POST_KEYS.has(key) && /^\d+$/.test(v)) return false;
        return true;
      })
      .map(([k, v]) => `${k}=${v}`)
      .sort();
    return `${u.origin}${path}?${keep.join('&')}`;
  } catch {
    return url;
  }
}

/** 캡처할 요소 순서: 깨진 이미지 → 접근성 위반(심각한 순) → 깨진 링크 → 접근성 직접 확인 */
function shotTargets(rp) {
  const out = [];
  for (const img of rp.page.brokenImages) out.push(img.selector);
  const axeNodes = (list) => list
    .flatMap((v) => v.nodes.map((n) => ({ n, rank: IMPACT_RANK[n.impact ?? v.impact] ?? 4 })))
    .sort((a, b) => a.rank - b.rank)
    .map(({ n }) => axeSelector(n))
    .filter(Boolean);
  if (rp.a11y) out.push(...axeNodes(rp.a11y.violations));
  for (const link of rp.links?.checked ?? []) if (!link.ok) out.push(link.occurrences[0]?.selector);
  if (rp.a11y) out.push(...axeNodes(rp.a11y.incomplete));
  return out;
}

/** axe-core 위치 정보 → 리포트와 같은 선택자 (iframe·shadow DOM 안 요소는 찍지 않음) */
export function axeSelector(node) {
  return node.target.length === 1 && typeof node.target[0] === 'string' ? node.target[0] : null;
}

// 페이지가 아닌 파일 (내려받기 링크)
const FILE_EXT = /\.(pdf|hwp|hwpx|docx?|xlsx?|pptx?|zip|rar|7z|alz|egg|jpe?g|png|gif|svg|webp|bmp|mp4|mp3|avi|wmv|txt|xml|json|csv)$/i;
// 누르면 상태가 바뀌거나 내려받기가 되는 주소
const UNSAFE = /logout|logoff|signout|delete|remove|download|filedown/i;

async function nextPages(links, siteHost, linkCache) {
  const result = [];
  for (const link of links) {
    let u;
    try {
      u = new URL(link.href);
    } catch {
      continue;
    }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') continue;
    if (hostOf(u.href) !== siteHost) continue;
    if (FILE_EXT.test(u.pathname) || UNSAFE.test(u.pathname + u.search)) continue;
    u.hash = '';
    // 링크 검사에서 이미 깨진 것으로 확인된 주소는 페이지로 다시 열지 않는다 (깨진 링크로 이미 보고됨)
    const checked = linkCache.get(u.href);
    if (checked && !(await checked).ok) continue;
    result.push(u.href);
  }
  return result;
}

function pageKey(url) {
  try {
    const u = new URL(url);
    u.hash = '';
    return u.href;
  } catch {
    return url;
  }
}

function hostOf(url) {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return null;
  }
}

function shortPath(url) {
  try {
    const u = new URL(url);
    const p = u.pathname + u.search;
    return p.length > 60 ? `${p.slice(0, 60)}…` : p;
  } catch {
    return url;
  }
}

// 검사 하나가 실패해도 나머지 결과는 살리고, 실패 사실은 warnings에 남긴다.
async function attempt(label, fn, warnings) {
  try {
    return await fn();
  } catch (err) {
    warnings.push(`${label} 실패: ${firstLine(err.message)}`);
    return null;
  }
}

function firstLine(text) {
  return String(text).split('\n')[0];
}
