// 1단계 완료 기준 확인:
// 일부러 오류를 넣은 테스트 페이지를 검사해, 넣은 오류는 모두 잡고 정상 항목은 오류로 잡지 않는지 본다.
//   node test/verify.js                 (성능·SEO 포함, 약 1분)
//   node test/verify.js --no-lighthouse (빠른 확인)
import { statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { startServer } from '../test-pages/server.js';
import { scanUrl } from '../src/scanner.js';
import { saveReport, printSummary } from '../src/output.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const withLighthouse = !process.argv.includes('--no-lighthouse');
const PORT = 4173;
const BASE = `http://127.0.0.1:${PORT}`;

const server = await startServer(PORT);
let failed = 0;
try {
  const OUT = path.join(ROOT, 'output');
  const steps1 = [];
  const report = await scanUrl(`${BASE}/broken.html`, { lighthouse: withLighthouse, out: OUT, onStep: (...s) => steps1.push(s) });
  printSummary(report, { limit: 30 });
  const file = await saveReport(report, OUT, 'verify');

  // 비교용 검사 1: 스크롤하지 않으면 아래쪽 lazy 이미지를 놓치는지 (스크롤 효과 확인)
  console.log('\n비교 검사: 스크롤 없이');
  const noScroll = await scanUrl(`${BASE}/broken.html`, { lighthouse: false, scroll: false, shots: false, out: OUT });
  // 비교용 검사 2: 웹사이트 전체 점검 (탭 3개 동시) → broken·ok·head-404·sub + 게시판 글 7개 중 최신 1개 = 5페이지
  console.log('비교 검사: 웹사이트 전체 점검 (최대 30페이지, 탭 3개)');
  const steps2 = [];
  const site = await scanUrl(`${BASE}/broken.html`, { lighthouse: false, maxPages: 30, concurrency: 3, out: OUT, onStep: (...s) => steps2.push(s) });
  // 비교용 검사 3: 안전 상한(최대 3페이지)에 걸리면 남은 주소를 알려 주는지
  const capped = await scanUrl(`${BASE}/broken.html`, { lighthouse: false, shots: false, maxPages: 3, concurrency: 2, out: OUT });
  // 비교용 검사 4: 상세 옵션 '모든 게시물' → 게시판 글 7개를 모두 검사
  const allPosts = await scanUrl(`${BASE}/broken.html`, { lighthouse: false, shots: false, maxPages: 30, concurrency: 3, posts: 'all', out: OUT });
  printSummary(site, { limit: 0 });
  const shotExists = (rel) => {
    if (!rel) return false;
    try {
      return statSync(path.join(OUT, rel)).size > 1000;
    } catch {
      return false;
    }
  };
  const shotOf = (pred) => report.findings.find(pred)?.screenshot;
  const sitePaths = [...new Set(site.pages.map((p) => new URL(p.pageUrl).pathname))].sort();
  const boardPages = site.pages.filter((p) => p.pageUrl.includes('/board.html')).length;

  const f = report.findings.filter((x) => !x.manualCheck);
  const has = (ruleId, targetEnd) => f.some((x) => x.ruleId === ruleId && (!targetEnd || x.target?.endsWith(targetEnd)));
  // 접근성 항목은 반드시 axe-core가 잡은 것이어야 한다 (SEO 쪽 같은 이름 규칙과 구분)
  const a11y = (ruleId, min = 1) => f.filter((x) => x.ruleId === ruleId && x.source === 'axe-core').length >= min;

  const checks = [
    ['이미지 대체 텍스트 누락 2건 (image-alt)', a11y('image-alt', 2)],
    ['페이지 언어 누락 (html-has-lang)', a11y('html-has-lang')],
    ['이름 없는 버튼 (button-name)', a11y('button-name')],
    ['레이블 없는 입력창 (label)', a11y('label')],
    ['명도 대비 부족 (color-contrast)', a11y('color-contrast')],
    ['image-alt를 SEO에서 중복으로 세지 않음', !f.some((x) => x.ruleId === 'image-alt' && x.source === 'lighthouse')],
    ['접근성 설명이 한국어로 나옴', f.some((x) => x.source === 'axe-core' && /[가-힣]/.test(x.description))],
    ['깨진 링크 no-such-page.html (link-broken)', has('link-broken', '/no-such-page.html')],
    ['깨진 이미지 main-visual.png (image-broken)', has('image-broken', '/img/main-visual.png')],
    ['JavaScript 오류 (js-error)', has('js-error')],
    ['정상 링크 ok.html은 오류로 잡지 않음', !report.findings.some((x) => x.target?.endsWith('/ok.html'))],
    ['정상 이미지 logo.svg는 깨진 이미지로 잡지 않음', !has('image-broken', '/img/logo.svg')],
    ['[기능] 없는 디자인 파일 missing.css', f.some((x) => x.ruleId === 'resource-failed' && x.target?.endsWith('/css/missing.css') && x.severity === 'HIGH')],
    ['[기능] 없는 스크립트 파일 missing-ui.js', f.some((x) => x.ruleId === 'resource-failed' && x.target?.endsWith('/js/missing-ui.js'))],
    ['[기능] 데이터 요청 서버 오류 notice-list.do', f.some((x) => x.ruleId === 'api-failed' && x.target?.endsWith('/api/notice-list.do') && x.description.includes('서버 오류(500)'))],
    ['[기능] 콘솔 오류 메시지', f.some((x) => x.ruleId === 'console-error' && x.evidence?.includes('공지사항 목록'))],
    ['[기능] 콘솔의 "Failed to load resource"는 중복으로 세지 않음', !f.some((x) => x.ruleId === 'console-error' && /Failed to load resource/i.test(x.evidence ?? ''))],
    ['[기능] 휴대폰에서 옆으로 밀림 #wide-banner', f.some((x) => x.ruleId === 'mobile-horizontal-scroll' && x.selector === '#wide-banner')],
    ['[쉬운 말] 깨진 링크 이유가 "없는 페이지(404)"로 표시', f.some((x) => x.ruleId === 'link-broken' && x.description.includes('없는 페이지(404)'))],
    ['HEAD만 404인 정상 링크는 깨진 링크로 잡지 않음', !report.findings.some((x) => x.target?.endsWith('/head-404.html'))],
    ['통계 수집용 img는 깨진 이미지가 아닌 직접 확인으로', !has('image-broken', '/stats/record.do')
      && report.findings.some((x) => x.ruleId === 'image-not-rendered' && x.manualCheck && x.target?.endsWith('/stats/record.do'))],
    ['mailto 링크는 검사하지 않음', !report.findings.some((x) => x.target?.startsWith('mailto:'))],
    ['모든 항목에 표준 필드(fingerprint 등)가 있음', report.findings.every((x) => x.schemaVersion && x.jobId && x.fingerprint && x.severity)],
    ['검사 실패 경고 없음', report.warnings.length === 0],
    // 스크롤 후 검사
    ['[스크롤] 끝까지 내려감', report.pages[0].scroll?.reachedEnd === true],
    ['[스크롤] 아래쪽 lazy 이미지 lazy-banner.png 깨짐을 잡음', has('image-broken', '/img/lazy-banner.png')],
    ['[스크롤] 스크롤하지 않으면 lazy-banner.png를 놓침 (비교)', !noScroll.findings.some((x) => x.ruleId === 'image-broken' && x.target?.endsWith('/img/lazy-banner.png'))],
    // 문제 위치 캡처
    ['[캡처] 이름 없는 버튼 위치 캡처 파일이 있음', shotExists(shotOf((x) => x.ruleId === 'button-name'))],
    ['[캡처] 깨진 이미지 main-visual.png 위치 캡처', shotExists(shotOf((x) => x.ruleId === 'image-broken' && x.target?.endsWith('/main-visual.png')))],
    ['[캡처] 휴대폰 밀림 #wide-banner는 휴대폰 화면으로 캡처', shotExists(shotOf((x) => x.ruleId === 'mobile-horizontal-scroll' && x.selector === '#wide-banner'))
      && /\/p1-m\d+\.jpg$/.test(shotOf((x) => x.ruleId === 'mobile-horizontal-scroll'))],
    ['[캡처] 위치가 없는 항목(js-error)에는 캡처 없음', report.findings.filter((x) => x.ruleId === 'js-error').every((x) => x.screenshot === null)],
    // 사이트 전체 검사
    ['[사이트] 같은 사이트 페이지를 모두 검사 (broken·ok·head-404·sub·board)', sitePaths.join(',') === '/board.html,/broken.html,/head-404.html,/ok.html,/sub.html'],
    ['[게시물] 기본(최신 1개만): 모양이 같은 게시판 글 7개 중 1개만 + 안내', boardPages === 1 && site.warnings.some((w) => w.includes('1개만 검사함'))],
    ['[게시물] 모든 게시물: 게시판 글 7개 모두', allPosts.pages.filter((p) => p.pageUrl.includes('/board.html')).length === 7],
    ['[사이트] 탭 여러 개로 검사해도 페이지 번호 순서대로 정리됨', site.pages[0].pageUrl.endsWith('/broken.html')],
    ['[사이트] 안전 상한에 걸리면 3페이지만 검사하고 남은 주소를 알려 줌', capped.pages.length === 3 && capped.warnings.some((w) => w.includes('최대 3페이지까지만'))],
    ['[공통 영역] 두 페이지 바닥글의 같은 깨진 링크는 1건으로 합침 (페이지 2개)', (() => {
      const hits = site.findings.filter((x) => x.ruleId === 'link-broken' && x.target?.endsWith('/common-missing.html'));
      return hits.length === 1 && hits[0].pages?.length === 2;
    })()],
    ['[공통 영역] 한 페이지에만 있는 깨진 링크(no-such-page)는 페이지 1개', site.findings.some((x) => x.target?.endsWith('/no-such-page.html') && x.pages?.length === 1)],
    ['[공통 영역] 합쳐도 페이지별 요약에는 각 페이지에 반영', site.pages.find((p) => p.pageUrl.endsWith('/sub.html'))?.summary.total >= 1],
    ['[공통 영역] 전체 건수는 합친 뒤 기준', site.summary.total === site.findings.filter((x) => !x.manualCheck).length],
    ['[사이트] 깨진 링크(no-such-page)는 페이지로 다시 열지 않음', !site.findings.some((x) => x.pageUrl.endsWith('/no-such-page.html'))],
    ['[사이트] 페이지마다 요약 점수(심각도별 건수)가 있음', site.pages.every((p) => p.summary?.bySeverity) && site.scope?.pageCount === 5],
    ['[진행 막대] 한 페이지 검사는 단계 번호가 1부터 끝까지 차례로 올라감', steps1.length >= 4 && steps1.every(([ph, d, t], i) => ph === 'page' && d === i + 1 && t === steps1[0][2])],
    ['[진행 막대] 전체 점검은 페이지 찾기 뒤 총 5페이지로 0→5 진행', (() => {
      const scans = steps2.filter(([ph]) => ph === 'scan');
      const firstScan = steps2.findIndex(([ph]) => ph === 'scan');
      return steps2.slice(0, firstScan).every(([ph]) => ph === 'discover') && firstScan > 0
        && scans[0][1] === 0 && scans.every(([, , t]) => t === 5) && scans.at(-1)[1] === 5;
    })()],
    ['[사이트] 문제마다 어느 페이지인지 기록됨', site.findings.every((x) => site.pages.some((p) => p.pageUrl === x.pageUrl))
      && site.findings.some((x) => x.pageUrl.endsWith('/broken.html'))],
  ];
  if (withLighthouse) {
    checks.push(
      ['성능·SEO 점수 측정됨', report.pages[0].scores?.performance != null && report.pages[0].scores?.seo != null],
      ['meta description 누락 (SEO)', has('meta-description')],
      ['로딩 속도 지표 5개에 평가(좋음/개선 필요/나쁨)가 있음', report.pages[0].metricDetails?.length === 5 && report.pages[0].metricDetails.every((m) => m.rating)],
      ['빠른 페이지(100점)에 사소한 속도 지적을 쌓지 않음', report.pages[0].scores.performance < 90 || !f.some((x) => x.category === 'PERFORMANCE')],
    );
  }

  console.log('\n완료 기준 확인');
  for (const [label, ok] of checks) {
    if (!ok) failed++;
    console.log(`  ${ok ? '통과' : '실패'}  ${label}`);
  }
  console.log(`\n결과: ${checks.length - failed}/${checks.length} 통과`);
  console.log(`리포트: ${file}`);
} finally {
  server.close();
}
process.exit(failed ? 1 : 0);
