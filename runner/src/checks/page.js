// 페이지를 실제 브라우저로 열고, 화면이 뜨는 동안 생기는 문제를 모두 기록한다.
//  - 페이지 응답 상태, 깨진 이미지, 링크 목록
//  - CSS·JS·글꼴 등 화면에 필요한 파일을 못 불러온 경우
//  - 페이지가 따로 부르는 데이터 요청(API)이 실패한 경우
//  - 스크립트 오류, 브라우저 콘솔 오류
//  - https 페이지에서 http 파일을 부르는 경우(혼합 콘텐츠)
// 화면이 뜬 뒤 끝까지 스크롤해서, 늦게 불러오는 이미지(lazy 로딩)의 문제까지 잡는다.
import { autoScroll } from './scroll.js';

// 화면을 만드는 데 쓰이는 파일 종류
const RESOURCE_TYPES = new Set(['stylesheet', 'script', 'font', 'media', 'other']);
// 페이지가 따로 부르는 데이터 요청
const API_TYPES = new Set(['xhr', 'fetch']);

/**
 * @param {import('playwright').Page} page
 * @param {string} url
 * @param {{ timeoutMs?: number, scroll?: boolean }} opts
 */
export async function loadPage(page, url, { timeoutMs = 30000, scroll = true } = {}) {
  const imageErrors = new Map(); // 이미지 주소 → 실패 이유
  const failedResources = new Map(); // 파일 주소 → { type, reason }
  const failedApis = new Map(); // 요청 → { method, reason }
  const insecureUrls = new Set(); // 페이지가 부른 http:// 파일
  const jsErrors = [];
  const consoleErrors = new Map(); // 메시지 → { count, source }
  let requestCount = 0;

  const recordFailure = (req, reason) => {
    const type = req.resourceType();
    const reqUrl = req.url();
    if (type === 'image') imageErrors.set(reqUrl, reason);
    else if (RESOURCE_TYPES.has(type)) failedResources.set(reqUrl, { type, reason });
    else if (API_TYPES.has(type)) failedApis.set(`${req.method()} ${reqUrl}`, { url: reqUrl, method: req.method(), reason });
  };

  page.on('request', (req) => {
    requestCount++;
    if (req.resourceType() !== 'document' && req.url().startsWith('http://')) insecureUrls.add(req.url());
  });
  page.on('response', (res) => {
    if (res.status() >= 400) recordFailure(res.request(), `HTTP ${res.status()}`);
  });
  page.on('requestfailed', (req) => {
    const reason = req.failure()?.errorText ?? '요청 실패';
    // 페이지 이동·탭 닫힘 등으로 중간에 취소된 요청은 결함이 아님
    if (reason.includes('ERR_ABORTED')) return;
    recordFailure(req, reason);
  });
  page.on('pageerror', (err) => jsErrors.push(err.message));
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    // "Failed to load resource ..."는 파일 불러오기 실패로 이미 따로 기록하므로 제외
    if (/^Failed to load resource/i.test(text)) return;
    const entry = consoleErrors.get(text) ?? { count: 0, source: msg.location()?.url ?? null };
    entry.count++;
    consoleErrors.set(text, entry);
  });

  const response = await page.goto(url, { waitUntil: 'load', timeout: timeoutMs });
  // 늦게 뜨는 리소스를 조금 더 기다림 (끝까지 안 끝나는 사이트도 있으므로 짧게)
  await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {});

  // 끝까지 내려가며 늦게 뜨는 이미지·내용을 불러온다 (그동안 생긴 오류도 위 기록에 함께 쌓임)
  let scrollInfo = null;
  if (scroll) {
    const before = requestCount;
    try {
      scrollInfo = { ...(await autoScroll(page)), extraRequests: requestCount - before };
    } catch {
      scrollInfo = null; // 스크롤 중 페이지가 바뀌는 등 실패해도 검사는 계속
    }
  }

  const collected = await page.evaluate(() => {
    // 요소를 다시 찾을 수 있는 짧은 CSS 경로 (id가 있으면 거기서 멈춤)
    const cssPath = (el) => {
      const parts = [];
      while (el && el.nodeType === 1 && parts.length < 5) {
        if (el.id) {
          parts.unshift('#' + CSS.escape(el.id));
          break;
        }
        let part = el.tagName.toLowerCase();
        const parent = el.parentElement;
        if (parent) {
          const same = [...parent.children].filter((c) => c.tagName === el.tagName);
          if (same.length > 1) part += `:nth-of-type(${same.indexOf(el) + 1})`;
        }
        parts.unshift(part);
        el = parent;
      }
      return parts.join(' > ');
    };

    const links = [...document.querySelectorAll('a[href]')].map((a) => ({
      href: a.href,
      selector: cssPath(a),
      text: (a.textContent || '').trim().replace(/\s+/g, ' ').slice(0, 80),
    }));

    const images = [...document.querySelectorAll('img')].map((img) => ({
      src: img.currentSrc || img.src,
      selector: cssPath(img),
      // 로딩이 끝났는데 크기가 0이면 깨진 이미지 (lazy 로딩으로 아직 안 불러온 것은 제외)
      broken: !!img.getAttribute('src') && img.complete && img.naturalWidth === 0,
      html: img.outerHTML.slice(0, 300),
    }));

    // 브라우저가 막아서 요청조차 안 된 http:// 파일도 찾기 위해 HTML 속성을 직접 본다.
    const insecureInHtml = [...document.querySelectorAll(
      'script[src], link[rel~="stylesheet"][href], img[src], iframe[src], video[src], audio[src], source[src], embed[src]',
    )]
      .map((el) => el.src || el.href || '')
      .filter((u) => u.startsWith('http://'));

    return { title: document.title, links, images, insecureInHtml };
  });

  const brokenImages = collected.images
    .filter((img) => img.broken)
    .map((img) => ({ ...img, reason: imageErrors.get(img.src) ?? null }));

  // 보안 점검용: 페이지 응답 헤더와 서버가 심은 쿠키 (리다이렉트 끝의 최종 응답 기준)
  const headers = response ? await response.allHeaders().catch(() => ({})) : null;
  const setCookies = response
    ? (await response.headersArray().catch(() => [])).filter((h) => h.name.toLowerCase() === 'set-cookie').map((h) => h.value)
    : [];

  const finalUrl = page.url();
  const mixedContent = finalUrl.startsWith('https://')
    ? [...new Set([...insecureUrls, ...collected.insecureInHtml])]
    : [];

  return {
    requestedUrl: url,
    finalUrl,
    httpStatus: response ? response.status() : null,
    title: collected.title,
    scroll: scrollInfo,
    links: collected.links,
    imageCount: collected.images.length,
    brokenImages,
    failedResources: [...failedResources].map(([u, v]) => ({ url: u, ...v })),
    failedApis: [...failedApis.values()],
    mixedContent,
    headers,
    setCookies,
    jsErrors: [...new Set(jsErrors)],
    consoleErrors: [...consoleErrors].map(([text, v]) => ({ text, ...v })),
  };
}
