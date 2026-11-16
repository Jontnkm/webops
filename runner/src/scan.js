#!/usr/bin/env node
// 명령줄 시작점:  node src/scan.js <URL> [옵션]
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanUrl } from './scanner.js';
import { saveReport, printSummary } from './output.js';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const HELP = `사용법: node src/scan.js <URL> [옵션]

옵션:
  --no-lighthouse     성능·SEO 측정(Lighthouse)을 건너뜀 (더 빠름)
  --max-links <n>     확인할 링크 최대 개수 (기본 300)
  --out <폴더>        결과 저장 폴더 (기본 runner/output)
  --job-id <ID>       작업 ID 지정 (서버가 결과 파일 이름을 알 수 있게)
  -h, --help          도움말

예시:
  node src/scan.js https://www.example.com
  node src/scan.js www.example.com --no-lighthouse`;

function parseArgs(argv) {
  const opts = { lighthouse: true, maxLinks: 300, out: path.join(ROOT, 'output') };
  let url = null;
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') return { help: true };
    else if (arg === '--no-lighthouse') opts.lighthouse = false;
    else if (arg === '--max-links') opts.maxLinks = Number(argv[++i]);
    else if (arg === '--out') opts.out = path.resolve(argv[++i]);
    else if (arg === '--job-id') opts.jobId = argv[++i];
    else if (!arg.startsWith('-') && !url) url = arg;
    else throw new Error(`알 수 없는 옵션: ${arg}`);
  }
  if (!Number.isInteger(opts.maxLinks) || opts.maxLinks < 0) throw new Error('--max-links에는 0 이상의 정수를 넣어 주세요');
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

  const url = normalizeUrl(args.url);
  console.log(`검사 시작: ${url}`);
  const report = await scanUrl(url, { ...args.opts, onProgress: (msg) => console.log(`  - ${msg}`) });
  const file = await saveReport(report, args.opts.out);
  printSummary(report);
  console.log(`\n저장: ${file}`);

  // 페이지 자체에 접속하지 못했으면 종료 코드 2 (나중에 서버가 실패로 구분할 수 있게)
  return report.findings.some((f) => f.ruleId === 'page-unreachable') ? 2 : 0;
}

main()
  .then((code) => process.exit(code))
  .catch((err) => {
    console.error(`오류: ${err.message}`);
    process.exit(1);
  });
