// 휴대폰 크기(가로 360px)로 페이지를 열어 모바일 환경을 확인한다.
//  - 화면이 옆으로 밀리는지(가로 스크롤): 밀리면 화면보다 넓은 요소 중 가장 바깥쪽 원인을 최대 5개까지 찾고 캡처
//  - 모바일 화면 설정(viewport)이 있는지, 손가락으로 확대하는 것을 막아 두지 않았는지
//  - 너무 작은 글자(12px 미만)가 많은지
//  - 반응형이 아닌 것 같은지 (viewport 설정이 없고 페이지가 PC 폭으로 그려짐 → 모바일 주소를 따로 넣어 점검 권장)
import { captureElements } from './shots.js';

export const MOBILE = {
  viewport: { width: 360, height: 740 },
  deviceScaleFactor: 3,
  isMobile: true,
  hasTouch: true,
  userAgent:
    'Mozilla/5.0 (Linux; Android 14; SM-S921N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36',
};

/**
 * 따로 휴대폰 브라우저를 열어 확인 (종합점검: PC로 검사한 페이지를 휴대폰으로 한 번 더)
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
    return await inspectMobile(page, { shots });
  } finally {
    await context.close();
  }
}

/**
 * 이미 휴대폰 크기로 열린 페이지를 확인 (모바일환경 점검: 처음부터 휴대폰 브라우저로 연 페이지)
 * @param {import('playwright').Page} page
 */
export async function inspectMobile(page, { shots = null } = {}) {
  const result = await page.evaluate(() => {
    const root = document.documentElement;
    const viewportWidth = root.clientWidth;
    const scrollWidth = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0);

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

    // 모바일 화면 설정(viewport)과 확대 막음
    const meta = document.querySelector('meta[name="viewport" i]');
    const content = meta?.getAttribute('content') ?? null;
    const zoomBlocked = !!content && (/user-scalable\s*=\s*(no|0)/i.test(content)
      || (/maximum-scale\s*=\s*([\d.]+)/i.exec(content) && Number(/maximum-scale\s*=\s*([\d.]+)/i.exec(content)[1]) < 2));

    // 너무 작은 글자: 글자가 직접 들어 있는 보이는 요소 중 12px 미만
    let small = 0;
    const smallSamples = [];
    if (document.body) {
      for (const el of document.body.querySelectorAll('*')) {
        const own = [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim().length > 1);
        if (!own) continue;
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) continue;
        const style = getComputedStyle(el);
        if (style.visibility === 'hidden' || style.display === 'none') continue;
        const size = parseFloat(style.fontSize);
        if (size > 0 && size < 12) {
          small++;
          if (smallSamples.length < 5) smallSamples.push({ selector: cssPath(el), size, text: el.textContent.trim().replace(/\s+/g, ' ').slice(0, 40) });
        }
      }
    }

    const offenders = [];
    if (scrollWidth - viewportWidth > 1 && document.body) {
      for (const el of document.body.querySelectorAll('*')) {
        const rect = el.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0 || rect.right <= viewportWidth + 1) continue;
        const style = getComputedStyle(el);
        if (style.visibility === 'hidden' || style.position === 'fixed') continue;
        // 부모가 이미 원인으로 잡혔으면 자식은 건너뛴다 (가장 바깥 원인만)
        if (offenders.some((f) => f.el.contains(el))) continue;
        offenders.push({ el, selector: cssPath(el), overBy: Math.round(rect.right - viewportWidth), html: el.outerHTML.slice(0, 200) });
        if (offenders.length >= 5) break;
      }
    }
    return {
      viewportWidth,
      scrollWidth,
      offenders: offenders.map(({ el, ...rest }) => rest),
      viewportMeta: content,
      zoomBlocked,
      smallText: { count: small, samples: smallSamples },
      // viewport 설정이 없으면 휴대폰 브라우저가 PC 폭(보통 980px)으로 그린다 → 반응형이 아닌 페이지
      notResponsive: !content,
    };
  });

  const captured = shots && result.offenders.length
    ? await captureElements(page, result.offenders.map((o) => o.selector), { ...shots, max: 5 })
    : {};
  return { finalUrl: page.url(), ...result, shots: captured };
}
