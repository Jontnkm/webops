// 보안 점검 (기본 항목): 페이지를 여는 것만으로 확인할 수 있는 것만 본다. 공격·관리자 주소 찔러보기는 하지 않는다.
//   페이지마다: https 사용, 보안 헤더(HSTS · 클릭재킹 방지 · 파일 형식 위장 방지 · 콘텐츠 보안 정책),
//              서버 프로그램 버전 노출, 쿠키 보안 설정
//   사이트 한 번: 보안 인증서(만료일 · 신뢰할 수 있는지), http로 접속하면 https로 넘어가는지
import tls from 'node:tls';

// 로그인 상태를 담는 쿠키로 흔히 쓰는 이름 (HttpOnly가 없으면 스크립트로 훔쳐 갈 수 있음)
const SESSION_COOKIE = /^(jsessionid|phpsessid|asp\.net_sessionid|aspsessionid\w*|sid|session|sessionid|connect\.sid|_session\w*)$/i;

/**
 * 페이지 하나의 보안 점검 결과 (문제 목록)
 * @param {{ finalUrl: string, headers: Record<string, string>|null, setCookies: string[] }} page
 * @returns {{ rule: string, severity: string, message: string, target?: string, evidence?: string }[]}
 */
export function checkPageSecurity(page) {
  const out = [];
  const https = page.finalUrl.startsWith('https://');
  const h = page.headers ?? {};
  const get = (name) => h[name] ?? h[name.toLowerCase()] ?? null;

  if (!https) {
    out.push({ rule: 'https-not-used', severity: 'HIGH', message: '보안 연결(https)을 쓰지 않음 · 입력한 정보가 암호화되지 않고 전송됨' });
  } else if (!get('strict-transport-security')) {
    out.push({ rule: 'hsts-missing', severity: 'MEDIUM', message: '항상 https로만 접속하게 하는 설정(HSTS)이 없음' });
  }
  const csp = get('content-security-policy') ?? '';
  if (!get('x-frame-options') && !/frame-ancestors/i.test(csp)) {
    out.push({ rule: 'clickjacking-unprotected', severity: 'MEDIUM', message: '다른 사이트가 이 페이지를 몰래 끼워 넣는 것을 막는 설정(X-Frame-Options)이 없음' });
  }
  if ((get('x-content-type-options') ?? '').toLowerCase() !== 'nosniff') {
    out.push({ rule: 'nosniff-missing', severity: 'LOW', message: '파일 형식 위장을 막는 설정(X-Content-Type-Options: nosniff)이 없음' });
  }
  if (!csp) {
    out.push({ rule: 'csp-missing', severity: 'LOW', message: '허용한 곳의 스크립트만 실행하게 하는 설정(콘텐츠 보안 정책, CSP)이 없음' });
  }
  const server = get('server') ?? '';
  const powered = get('x-powered-by') ?? '';
  if (/\d/.test(server) || powered) {
    out.push({
      rule: 'server-version-exposed', severity: 'LOW',
      message: '서버 프로그램 이름·버전이 응답에 드러남 (알려진 취약점 공격의 단서가 됨)',
      evidence: [server && `Server: ${server}`, powered && `X-Powered-By: ${powered}`].filter(Boolean).join(' · '),
    });
  }
  for (const raw of page.setCookies ?? []) {
    for (const line of String(raw).split('\n')) {
      const name = line.split('=')[0].trim();
      if (!name) continue;
      const attrs = line.toLowerCase();
      const missing = [];
      if (https && !/;\s*secure/.test(attrs)) missing.push('Secure');
      if (SESSION_COOKIE.test(name) && !/;\s*httponly/.test(attrs)) missing.push('HttpOnly');
      if (!/;\s*samesite=/.test(attrs)) missing.push('SameSite');
      // SameSite만 없는 것은 브라우저 기본값(Lax)이 있어 따로 알리지 않는다
      if (missing.length === 0 || (missing.length === 1 && missing[0] === 'SameSite')) continue;
      out.push({
        rule: 'cookie-insecure', severity: 'MEDIUM', target: name,
        message: `쿠키 '${name}'에 보안 설정이 빠짐: ${missing.join(' · ')}`,
        evidence: line.slice(0, 200),
      });
    }
  }
  return out;
}

/**
 * 사이트 전체에 한 번: 보안 인증서와 http → https 넘김
 * @param {string} pageUrl 첫 페이지 주소
 * @returns {Promise<{ cert: object|null, findings: { rule: string, severity: string, message: string, evidence?: string }[] }>}
 */
export async function checkSiteSecurity(pageUrl, { timeoutMs = 10000 } = {}) {
  const u = new URL(pageUrl);
  const findings = [];
  if (u.protocol !== 'https:') return { cert: null, findings };

  let cert = null;
  try {
    cert = await readCertificate(u.hostname, Number(u.port) || 443, timeoutMs);
  } catch (err) {
    findings.push({ rule: 'cert-unreadable', severity: 'MEDIUM', message: `보안 인증서를 확인하지 못함 (${err.message})` });
  }
  if (cert) {
    const daysLeft = Math.floor((new Date(cert.validTo) - Date.now()) / 86400000);
    cert.daysLeft = daysLeft;
    const until = new Date(cert.validTo).toISOString().slice(0, 10);
    if (daysLeft < 0) {
      findings.push({ rule: 'cert-expired', severity: 'CRITICAL', message: `보안 인증서가 만료됨 (${until}) · 방문자에게 '안전하지 않음' 경고가 뜸`, evidence: cert.issuer });
    } else if (daysLeft <= 30) {
      findings.push({ rule: 'cert-expiring', severity: 'HIGH', message: `보안 인증서가 ${daysLeft}일 뒤 만료됨 (${until}) · 미리 갱신 필요`, evidence: cert.issuer });
    }
    if (!cert.trusted) {
      findings.push({ rule: 'cert-untrusted', severity: 'HIGH', message: `보안 인증서를 신뢰할 수 없음 (${cert.error ?? '인증 기관 확인 실패'})`, evidence: cert.issuer });
    }
  }

  // http://로 들어오면 https://로 넘어가는지 (서버가 http를 아예 받지 않으면 문제 아님)
  try {
    const httpUrl = `http://${u.host}/`;
    const res = await fetch(httpUrl, { redirect: 'manual', signal: AbortSignal.timeout(timeoutMs) });
    const location = res.headers.get('location') ?? '';
    const toHttps = res.status >= 300 && res.status < 400 && location.startsWith('https://');
    if (!toHttps) {
      findings.push({
        rule: 'http-no-redirect', severity: 'MEDIUM',
        message: 'http://로 접속하면 https로 넘어가지 않음 · 암호화되지 않은 주소로도 사이트가 열림',
        evidence: `${httpUrl} → 응답 코드 ${res.status}${location ? ` · ${location}` : ''}`,
      });
    }
  } catch {
    // http 포트가 닫혀 있으면 http로는 아예 열리지 않으므로 문제 아님
  }
  return { cert, findings };
}

/** 인증서 정보: 만료일 · 발급 기관 · 신뢰 여부 */
function readCertificate(host, port, timeoutMs) {
  return new Promise((resolve, reject) => {
    const socket = tls.connect({ host, port, servername: host, rejectUnauthorized: false, timeout: timeoutMs }, () => {
      const c = socket.getPeerCertificate();
      const info = c && c.valid_to ? {
        validTo: new Date(c.valid_to).toISOString(),
        issuer: [c.issuer?.O, c.issuer?.CN].filter(Boolean).join(' · ') || null,
        subject: c.subject?.CN ?? null,
        trusted: socket.authorized,
        error: socket.authorizationError ? String(socket.authorizationError) : null,
      } : null;
      socket.end();
      info ? resolve(info) : reject(new Error('인증서 정보 없음'));
    });
    socket.on('timeout', () => { socket.destroy(); reject(new Error('응답 시간 초과')); });
    socket.on('error', (err) => reject(err));
  });
}
