// 리포트를 파일로 저장하고, 터미널에 요약을 출력한다.
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { SEVERITY_ORDER } from './normalize.js';

const CATEGORY_LABEL = {
  AVAILABILITY: '접속',
  ACCESSIBILITY: '접근성',
  LINK: '링크',
  IMAGE: '이미지',
  SCRIPT: '동작 오류',
  RESOURCE: '파일',
  API: '데이터',
  MOBILE: '모바일',
  SECURITY: '보안',
  PERFORMANCE: '로딩 속도',
  SEO: '검색 노출',
};

export async function saveReport(report, dir, prefix = 'scan') {
  await mkdir(dir, { recursive: true });
  const file = path.join(dir, `${prefix}-${report.jobId}.json`);
  await writeFile(file, JSON.stringify(report, null, 2), 'utf8');
  return file;
}

export function printSummary(report, { limit = 15 } = {}) {
  const page = report.pages[0];
  const sec = (report.durationMs / 1000).toFixed(1);
  console.log('');
  const access = page.httpStatus == null ? '접속 안 됨' : page.httpStatus < 400 ? '정상' : `오류(${page.httpStatus})`;
  console.log(`검사 완료: ${page.pageUrl}  (접속 ${access}, ${sec}초)`);
  if (report.pages.length > 1) {
    console.log(`사이트 전체 검사: 페이지 ${report.pages.length}개`);
    for (const p of report.pages) {
      const b = p.summary.bySeverity;
      console.log(`  ${p.pageUrl}  (심각 ${b.CRITICAL} · 높음 ${b.HIGH} · 보통 ${b.MEDIUM} · 낮음 ${b.LOW})`);
    }
  }
  if (page.scores) console.log(`로딩 속도 ${page.scores.performance ?? '-'} · 검색 노출 ${page.scores.seo ?? '-'}  (100점 만점)`);

  const s = report.summary;
  const bySev = SEVERITY_ORDER.map((k) => `${k} ${s.bySeverity[k]}`).join(' · ');
  console.log(`발견 항목 ${s.total}건 (${bySev}) · 직접 확인 필요 ${s.manualCheck}건`);

  // 같은 규칙은 한 줄로 묶어 보여 준다 (예: image-alt 34건). 자세한 위치는 JSON 파일에 있다.
  const groups = new Map();
  for (const f of report.findings.filter((x) => !x.manualCheck)) {
    const key = `${f.source}|${f.ruleId}`;
    if (!groups.has(key)) groups.set(key, { first: f, count: 0 });
    groups.get(key).count++;
  }
  const rows = [...groups.values()];
  for (const { first: f, count } of rows.slice(0, limit)) {
    const label = (CATEGORY_LABEL[f.category] ?? f.category).padEnd(4);
    const where = f.selector ?? f.target ?? '';
    console.log(`  [${f.severity.padEnd(8)}] ${label} ${f.ruleId} ${count}건 - ${f.description}`);
    if (where) console.log(`               예: ${where.length > 90 ? `${where.slice(0, 90)}…` : where}`);
  }
  if (rows.length > limit) console.log(`  … 외 ${rows.length - limit}개 규칙은 JSON 파일에서 확인`);

  for (const w of report.warnings) console.log(`주의: ${w}`);
}
