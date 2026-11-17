// 휴대폰 크기(가로 360px)로 페이지를 열어, 화면이 옆으로 밀리는지(가로 스크롤) 확인한다.
// 밀리면 화면보다 넓은 요소 중 가장 바깥쪽 원인을 최대 5개까지 찾고, 휴대폰 화면 그대로 캡처한다.
import { captureElements } from './shots.js';

const MOBILE = {
  viewport: { width: 360, height: 740 },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
  userAgent:
    'Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
};

/**
 * @param {import('playwright').Browser} browser
 * @param {string} url
 * @param {{ timeoutMs?: number, shots?: { dir: string, urlPrefix: string, prefix: string } | null }} opts
 */
export async function checkMobile(browser, url, { timeoutMs = 30000, shots = null } = {}) {
  const context = await browser.newContext(MOBILE);
  try {
    const page = await context.newPage();
    await page.goto(url, { waitUntil: 'load', timeout: timeoutMs });
    await page.waitForLoadState('networkidle', { timeout: 5000 }).catch(() => {});

    const result = await page.evaluate(() => {
      const root = document.documentElement;
      const viewportWidth = root.clientWidth;
      const scrollWidth = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0);
      if (scrollWidth - viewportWidth <= 1) return { viewportWidth, scrollWidth, offenders: [] };

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

      const found = [];
      for (const el of document.body.querySelectorAll('*')) {
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0 || rect.right <= viewportWidth + 1) continue;
        const style = getComputedStyle(el);
        if (style.visibility === 'hidden' || style.position === 'fixed') continue;
        // 부모가 이미 원인으로 잡혔으면 자식은 건너뛴다 (가장 바깥 원인만)
        if (found.some((f) => f.el.contains(el))) continue;
        found.push({
          el,
          selector: cssPath(el),
          overBy: Math.round(rect.right - viewportWidth),
          html: el.outerHTML.slice(0, 200),
        });
        if (found.length >= 5) break;
      }
      return { viewportWidth, scrollWidth, offenders: found.map(({ el, ...rest }) => rest) };
    });

    const captured = shots && result.offenders.length
      ? await captureElements(page, result.offenders.map((o) => o.selector), { ...shots, max: 5 })
      : {};
    return { finalUrl: page.url(), ...result, shots: captured };
  } finally {
    await context.close();
  }
}
