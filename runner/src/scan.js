#!/usr/bin/env node
// 명령줄 시작점:  node src/scan.js <URL> [옵션]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanUrl, MAX_PAGES_LIMIT } from './scanner.js';
import { saveReport, printSummary } from './output.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const HELP = `사용법: node src/scan.js <URL> [옵션]

옵션:
  --no-lighthouse     성능·SEO 측정(Lighthouse)을 건너뜀 (더 빠름)
  --max-links <n>     페이지마다 확인할 링크 최대 개수 (기본 300)
  --main              메인 페이지만: 어떤 주소를 넣어도 그 사이트의 첫 화면(/)을 검사
  --pages <n>         웹사이트 전체 점검: 같은 사이트 링크를 따라가 최대 n개 페이지 검사 (기본 1, 최대 ${MAX_PAGES_LIMIT})
  --concurrency <n>   여러 페이지를 검사할 때 동시에 여는 탭 수 (기본 1, 최대 5)
  --posts <latest|all> 게시판 글처럼 모양이 같은 페이지: latest = 처음 찾은(최신) 1개만 (기본), all = 모두
  --check <종류>      점검 종류: all(종합점검, 기본) · function(기능) · a11y(웹접근성) · perf(로딩속도, 모든 페이지 Lighthouse)
                      · seo(SEO, 모든 페이지 Lighthouse) · security(보안) · mobile(모바일환경)
  --mobile-url <주소> 모바일환경 점검: 반응형이 아닌 사이트의 모바일 전용 주소 (이 주소부터 휴대폰 브라우저로 점검)
  --public            공공기관 검사: 필수 요소(개인정보처리방침 · 이용약관 · 저작권 표시)도 점검 (기본은 일반 검사)
  --no-scroll         페이지 끝까지 스크롤하지 않음 (늦게 뜨는 이미지는 검사 안 됨)
  --no-shots          문제 위치 화면 캡처를 하지 않음
  --out <폴더>        결과 저장 폴더 (기본 runner/output, 캡처는 <폴더>/shots/<작업ID>/)
  --job-id <ID>       작업 ID 지정 (서버가 결과 파일 이름을 알 수 있게)
  -h, --help          도움말

예시:
  node src/scan.js https://www.example.com
  node src/scan.js www.example.com --no-lighthouse
  node src/scan.js www.example.com/sub/page.do --main
  node src/scan.js www.example.com --pages 300 --concurrency 3 --no-lighthouse`;

function parseArgs(argv) {
  const opts = { lighthouse: true, maxLinks: 300, maxPages: 1, concurrency: 1, posts: 'latest', mainOnly: false, essentials: false, check: 'all', mobileUrl: null, scroll: true, shots: true, out: path.join(ROOT, 'output') };
  let url = null;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    else if (arg === '--no-lighthouse') opts.lighthouse = false;
    else if (arg === '--max-links') opts.maxLinks = Number(argv[++i]);
    else if (arg === '--pages') opts.maxPages = Number(argv[++i]);
    else if (arg === '--concurrency') opts.concurrency = Number(argv[++i]);
    else if (arg === '--main') opts.mainOnly = true;
    else if (arg === '--posts') opts.posts = argv[++i];
    else if (arg === '--public') opts.essentials = true;
    else if (arg === '--check') opts.check = argv[++i];
    else if (arg === '--mobile-url') opts.mobileUrl = argv[++i];
    else if (arg === '--no-scroll') opts.scroll = false;
    else if (arg === '--no-shots') opts.shots = false;
    else if (arg === '--out') opts.out = path.resolve(argv[++i]);
    else if (arg === '--job-id') opts.jobId = argv[++i];
    else if (!arg.startsWith('-') && !url) url = arg;
    else throw new Error(`알 수 없는 옵션: ${arg}`);
  }
  if (!Number.isInteger(opts.maxLinks) || opts.maxLinks < 0) throw new Error('--max-links에는 0 이상의 정수를 넣어 주세요');
  if (!Number.isInteger(opts.maxPages) || opts.maxPages < 1 || opts.maxPages > MAX_PAGES_LIMIT) {
    throw new Error(`--pages에는 1~${MAX_PAGES_LIMIT} 사이 정수를 넣어 주세요`);
  }
  if (!Number.isInteger(opts.concurrency) || opts.concurrency < 1 || opts.concurrency > 5) throw new Error('--concurrency에는 1~5 사이 정수를 넣어 주세요');
  if (!['all', 'function', 'a11y', 'perf', 'seo', 'security', 'mobile'].includes(opts.check)) throw new Error('--check에는 all · function · a11y · perf · seo · security · mobile 중 하나를 넣어 주세요');
  if (opts.mobileUrl && !/^https?:\/\//i.test(opts.mobileUrl)) opts.mobileUrl = `https://${opts.mobileUrl}`;
  if (!['latest', 'all'].includes(opts.posts)) throw new Error('--posts에는 latest 또는 all을 넣어 주세요');
  // 작업 ID는 파일 이름에 들어가므로 영문·숫자·하이픈만 허용
  if (opts.jobId !== undefined && !/^[A-Za-z0-9-]{1,64}$/.test(opts.jobId ?? '')) throw new Error('--job-id는 영문·숫자·하이픈만 쓸 수 있습니다');
  return { url, opts };
}

// "www.example.com"처럼 프로토콜 없이 넣어도 https로 처리한다.
function normalizeUrl(input) {
  const withProtocol = /^[a-z][a-z0-9+.-]*:\/\//i.test(input) ? input : `https://${input}`;
  const u = new URL(withProtocol);
  if (u.protocol !== 'http:' && u.protocol !== 'https:') throw new Error('http 또는 https 주소만 검사할 수 있습니다');
  return u.href;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help || !args.url) {
    console.log(HELP);
    return args.help ? 0 : 1;
  }

  let url = normalizeUrl(args.url);
  // 메인 페이지만: 넣은 주소의 경로를 버리고 사이트 첫 화면으로
  if (args.opts.mainOnly) url = `${new URL(url).origin}/`;
  console.log(`검사 시작: ${url}`);
  const report = await scanUrl(url, {
    ...args.opts,
    onProgress: (msg) => console.log(`  - ${msg}`),
    // 서버의 진행 막대용: "  % 단계 완료수 전체수" (단계: page · discover · scan · lighthouse)
    onStep: (phase, done, total) => console.log(`  % ${phase} ${done} ${total}`),
  });
  const file = await saveReport(report, args.opts.out);
  printSummary(report);
  console.log(`\n저장: ${file}`);

  // 페이지 자체에 접속하지 못했으면 종료 코드 2 (나중에 서버가 실패로 구분할 수 있게)
  // (사이트 전체 검사에서 다른 페이지가 안 열린 것은 결과에 문제로 남기고 0으로 끝낸다)
  const startPage = report.pages[0]?.pageUrl;
  return report.findings.some((f) => f.ruleId === 'page-unreachable' && f.pageUrl === startPage) ? 2 : 0;
}

main()
  .then((code) => process.exit(code))
  .catch((err) => {
    console.error(`오류: ${err.message}`);
    process.exit(1);
  });
