// Lighthouse로 로딩 속도·검색 노출 점수와, 점수를 깎은 원인을 측정한다.
import lighthouse from 'lighthouse';
import desktopConfig from 'lighthouse/core/config/desktop-config.js';
import * as chromeLauncher from 'chrome-launcher';
import { chromium } from 'playwright';

// 속도 지표 (key → Lighthouse 항목 이름)
const METRICS = [
  ['fcp', 'first-contentful-paint'],
  ['lcp', 'largest-contentful-paint'],
  ['tbt', 'total-blocking-time'],
  ['cls', 'cumulative-layout-shift'],
  ['speedIndex', 'speed-index'],
];

/** Lighthouse 점수(0~1) → 좋음 / 개선 필요 / 나쁨 */
function rate(score) {
  if (score == null) return null;
  return score >= 0.9 ? 'GOOD' : score >= 0.5 ? 'NEEDS_IMPROVEMENT' : 'POOR';
}

/** @param {string} url */
export async function runLighthouse(url) {
  // 별도 Chrome 설치 없이 Playwright가 받아 둔 Chromium을 사용한다.
  const chrome = await chromeLauncher.launch({
    chromePath: chromium.executablePath(),
    chromeFlags: ['--headless=new', '--disable-gpu', '--no-sandbox'],
  });

  try {
    const result = await lighthouse(
      url,
      { port: chrome.port, output: 'json', logLevel: 'error', onlyCategories: ['performance', 'seo'], locale: 'ko' },
      desktopConfig,
    );
    const lhr = result?.lhr;
    if (!lhr) throw new Error('Lighthouse 결과가 비어 있음');
    if (lhr.runtimeError) throw new Error(`${lhr.runtimeError.code}: ${lhr.runtimeError.message}`);

    const toScore = (cat) => (cat?.score == null ? null : Math.round(cat.score * 100));

    const metrics = {};
    const metricDetails = [];
    for (const [key, id] of METRICS) {
      const audit = lhr.audits[id];
      metrics[key] = audit?.displayValue ?? null;
      metricDetails.push({
        key,
        displayValue: audit?.displayValue ?? null,
        numericValue: audit?.numericValue ?? null, // 시간 지표는 ms, 화면 밀림(CLS)은 0~1
        rating: rate(audit?.score),
      });
    }

    // 로딩 속도를 늦춘 원인 (점수에 직접 들어가는 지표는 제외, 개선 효과가 큰 순서로 최대 10개)
    const perfIssues = (lhr.categories.performance?.auditRefs ?? [])
      .filter((ref) => ref.group !== 'metrics')
      .map((ref) => lhr.audits[ref.id])
      .filter((a) => a && a.score !== null && a.score < 0.9 && !['notApplicable', 'manual', 'informative'].includes(a.scoreDisplayMode))
      .map((a) => ({
        id: a.id,
        title: a.title,
        displayValue: a.displayValue ?? null,
        score: a.score,
        savings: savingsOf(a),
        hasSavingsInfo: a.metricSavings != null,
        items: itemsOf(a),
      }))
      // 줄어드는 시간이 거의 없는 항목은 빼서, 빠른 페이지에 사소한 지적이 쌓이지 않게 한다.
      .filter((p) => p.savings >= MIN_SAVINGS_MS || (p.savings === 0 && p.score < 0.5 && !p.hasSavingsInfo))
      .sort((x, y) => y.savings - x.savings || x.score - y.score)
      .slice(0, 10)
      .map(({ hasSavingsInfo, ...rest }) => rest);

    // 검색 노출 점수에 반영되는 항목 중 통과하지 못한 것
    const seoFailures = (lhr.categories.seo?.auditRefs ?? [])
      .filter((ref) => ref.weight > 0)
      .map((ref) => lhr.audits[ref.id])
      .filter((a) => a && a.score !== null && a.score < 1 && !['notApplicable', 'manual'].includes(a.scoreDisplayMode))
      .map((a) => ({ id: a.id, title: a.title, displayValue: a.displayValue ?? null, items: itemsOf(a) }));

    return {
      version: lhr.lighthouseVersion,
      scores: { performance: toScore(lhr.categories.performance), seo: toScore(lhr.categories.seo) },
      metrics,
      metricDetails,
      perfIssues,
      seoFailures,
    };
  } finally {
    // Windows에서 임시 폴더 정리 중 오류가 날 수 있어 무시한다.
    try {
      await chrome.kill();
    } catch {
      /* 무시 */
    }
  }
}

// 이 정도(ms)는 줄어들어야 "로딩 속도 문제"로 보여 준다.
const MIN_SAVINGS_MS = 100;

// 개선하면 줄어드는 시간(ms) 합계.
// 화면 밀림(CLS)은 0~1 값이라 환산한다: 0.1(개선 필요 시작) ≈ 400, 0.25(나쁨) ≈ 1000
function savingsOf(audit) {
  const s = audit.metricSavings ?? {};
  // 첫 화면(FCP)과 주요 내용(LCP) 절감은 같은 시간이 겹쳐 잡히므로 더하지 않고 큰 값만 쓴다.
  return Math.max(s.LCP ?? 0, s.FCP ?? 0) + (s.TBT ?? 0) + (s.CLS ?? 0) * 4000;
}

// 원인이 된 파일·요소를 최대 5개까지 꺼낸다 (항목마다 형식이 달라 있는 값만 쓴다)
function itemsOf(audit) {
  const items = audit.details?.items;
  if (!Array.isArray(items)) return [];
  return items
    .slice(0, 5)
    .map((it) => {
      const node = it.node && typeof it.node === 'object' ? it.node : null;
      const url = typeof it.url === 'string' ? it.url : typeof it.source?.url === 'string' ? it.source.url : null;
      const bytes = it.wastedBytes ?? null;
      const ms = it.wastedMs ?? null;
      const extra = [
        bytes ? `${Math.round(bytes / 1024)}KB 줄일 수 있음` : null,
        ms ? `${Math.round(ms)}ms 줄일 수 있음` : null,
      ].filter(Boolean).join(' · ') || null;
      return { url, selector: node?.selector ?? null, snippet: node?.snippet ?? null, extra };
    })
    .filter((i) => i.url || i.selector);
}
