// 테스트 페이지를 내 PC에서만 띄우는 아주 작은 웹 서버 (http://127.0.0.1:4173)
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
};

export function startServer(port = 4173) {
  return new Promise((resolve, reject) => {
    const server = http.createServer(async (req, res) => {
      const urlPath = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);

      // 실제 사이트에서 본 경우 재현 1: HEAD에는 404, GET에는 200을 주는 서버 (깨진 링크가 아님)
      if (urlPath === '/head-404.html') {
        res.writeHead(req.method === 'HEAD' ? 404 : 200, { 'content-type': 'text/html; charset=utf-8' });
        res.end(req.method === 'HEAD' ? undefined : '<!doctype html><title>ok</title><p>GET이면 정상');
        return;
      }
      // 보안 점검: 보안 헤더는 갖췄지만 서버 버전이 드러나고, 로그인 쿠키에 HttpOnly가 없는 페이지
      if (urlPath === '/secure.html') {
        res.writeHead(200, {
          'content-type': 'text/html; charset=utf-8',
          'x-frame-options': 'SAMEORIGIN',
          'x-content-type-options': 'nosniff',
          'content-security-policy': "default-src 'self'",
          'x-powered-by': 'PHP/8.1.2',
          'set-cookie': ['JSESSIONID=abc123; Path=/', 'lang=ko; Path=/; HttpOnly'],
        });
        res.end('<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>보안 테스트</title></head><body><h1>보안 헤더 테스트</h1></body></html>');
        return;
      }
      // 서버 오류(500)를 돌려주는 데이터 요청
      if (urlPath === '/api/notice-list.do') {
        res.writeHead(500, { 'content-type': 'application/json; charset=utf-8' });
        res.end('{"error":"test"}');
        return;
      }
      // 재현 2: <img>로 부르지만 그림이 아닌 방문 통계 수집 주소 (200 응답, 내용 없음)
      if (urlPath === '/stats/record.do') {
        res.writeHead(200);
        res.end();
        return;
      }

      const file = path.join(ROOT, path.normalize(urlPath === '/' ? '/ok.html' : urlPath));
      // 테스트 폴더 밖 파일은 내주지 않는다.
      if (!file.startsWith(ROOT + path.sep) || file.endsWith('.js')) {
        res.writeHead(404).end();
        return;
      }
      try {
        const body = await readFile(file);
        res.writeHead(200, { 'content-type': TYPES[path.extname(file)] ?? 'application/octet-stream' });
        res.end(req.method === 'HEAD' ? undefined : body);
      } catch {
        res.writeHead(404, { 'content-type': 'text/html; charset=utf-8' });
        res.end('<!doctype html><title>404</title><h1>404 Not Found</h1>');
      }
    });
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => resolve(server));
  });
}

// "node test-pages/server.js"로 직접 실행했을 때만 서버를 띄운다.
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  await startServer();
  console.log('테스트 페이지 실행 중 (종료: Ctrl+C)');
  console.log('  오류 페이지: http://127.0.0.1:4173/broken.html');
  console.log('  정상 페이지: http://127.0.0.1:4173/ok.html');
}
