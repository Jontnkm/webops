// 1단계 완료 기준 확인:
// 일부러 오류를 넣은 테스트 페이지를 검사해, 넣은 오류는 모두 잡고 정상 항목은 오류로 잡지 않는지 본다.
//   node test/verify.js                 (성능·SEO 포함, 약 1분)
//   node test/verify.js --no-lighthouse (빠른 확인)
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
  const report = await scanUrl(`${BASE}/broken.html`, { lighthouse: withLighthouse });
  printSummary(report, { limit: 30 });
  const file = await saveReport(report, path.join(ROOT, 'output'), 'verify');

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
