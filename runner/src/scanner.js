// 검사 전체 흐름: 페이지 열기 → 접근성 → 링크 → 성능·SEO → 표준 JSON으로 변환
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright';
import { loadPage } from './checks/page.js';
import { checkA11y } from './checks/a11y.js';
import { checkLinks } from './checks/links.js';
import { runLighthouse } from './checks/lighthouse.js';
import { checkMobile } from './checks/mobile.js';
import { buildReport, makeJobId } from './normalize.js';

const pkg = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8'));

/**
 * URL 하나를 검사해 표준 리포트 객체를 돌려준다.
 * @param {string} url
 * @param {{ lighthouse?: boolean, maxLinks?: number, timeoutMs?: number, jobId?: string, onProgress?: (msg: string) => void }} options
 */
export async function scanUrl(url, options = {}) {
  const opts = { lighthouse: true, maxLinks: 300, timeoutMs: 30000, onProgress: () => {}, ...options };
  const jobId = opts.jobId ?? makeJobId();
  const startedAt = new Date();
  const raw = { requestedUrl: url, loadError: null, page: null, a11y: null, links: null, mobile: null, lighthouse: null, warnings: [] };
  let browserVersion = null;

  const browser = await chromium.launch();
  try {
    browserVersion = browser.version();
    const page = await (await browser.newContext()).newPage();

    opts.onProgress('페이지 여는 중 (PC 화면)');
    try {
      raw.page = await loadPage(page, url, opts);
    } catch (err) {
      raw.loadError = firstLine(err.message);
    }

    if (raw.page) {
      opts.onProgress('접근성 검사 중');
      raw.a11y = await attempt('접근성 검사', () => checkA11y(page), raw.warnings);
      opts.onProgress(`링크 확인 중 (${raw.page.links.length}개)`);
      raw.links = await attempt('링크 검사', () => checkLinks(raw.page.links, opts), raw.warnings);
      opts.onProgress('모바일 화면 확인 중');
      raw.mobile = await attempt('모바일 화면 확인', () => checkMobile(browser, raw.page.finalUrl, opts), raw.warnings);
    }
  } finally {
    await browser.close();
  }

  if (raw.page && opts.lighthouse) {
    opts.onProgress('로딩 속도 · 검색 노출 점수 측정 중 (20~60초)');
    raw.lighthouse = await attempt('로딩 속도 · 검색 노출 측정', () => runLighthouse(raw.page.finalUrl), raw.warnings);
  }

  return buildReport({ jobId, startedAt, finishedAt: new Date(), raw, toolVersion: pkg.version, browserVersion });
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
