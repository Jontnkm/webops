#!/usr/bin/env node
// 서버의 인쇄용 보고서 화면을 열어 PDF 파일로 저장한다.
//   node src/pdf.js <보고서 화면 주소> <저장할 PDF 경로>
// 서버(127.0.0.1)가 부르는 용도이므로 내 PC 주소만 연다.
import { mkdir } from 'node:fs/promises';
import path from 'node:path';
import { chromium } from 'playwright';

const FOOTER = `
<div style="width:100%;font-size:8px;color:#666;padding:0 12mm;display:flex;justify-content:space-between;font-family:'Malgun Gothic',sans-serif">
  <span>WebOps(가칭) 웹사이트 품질 검사 보고서</span>
  <span><span class="pageNumber"></span> / <span class="totalPages"></span></span>
</div>`;

async function main() {
  const [url, out] = process.argv.slice(2);
  if (!url || !out) {
    console.log('사용법: node src/pdf.js <보고서 화면 주소> <저장할 PDF 경로>');
    return 1;
  }
  const u = new URL(url);
  if (!['127.0.0.1', 'localhost'].includes(u.hostname)) throw new Error('내 PC(127.0.0.1) 주소만 PDF로 만들 수 있습니다');

  const browser = await chromium.launch();
  try {
    const page = await browser.newPage();
    const res = await page.goto(url, { waitUntil: 'networkidle', timeout: 60000 });
    if (!res || res.status() >= 400) throw new Error(`보고서 화면을 열지 못함 (응답 코드 ${res?.status() ?? '없음'})`);
    await page.emulateMedia({ media: 'print' });
    await mkdir(path.dirname(path.resolve(out)), { recursive: true });
    await page.pdf({
      path: out,
      format: 'A4',
      printBackground: true,
      displayHeaderFooter: true,
      headerTemplate: '<div></div>',
      footerTemplate: FOOTER,
      margin: { top: '14mm', bottom: '16mm', left: '12mm', right: '12mm' },
    });
    console.log(`저장: ${out}`);
    return 0;
  } finally {
    await browser.close();
  }
}

main()
  .then((code) => process.exit(code))
  .catch((err) => {
    console.error(`오류: ${err.message}`);
    process.exit(1);
  });
