// 공공 웹사이트 필수 요소: 개인정보처리방침 링크 · 이용약관 링크 · 저작권 표시가 페이지에 있는지 본다.
// 보통 바닥글에 있으므로 페이지 전체(링크 문구·이미지 대체 텍스트·title·주소, 화면 글자)에서 찾는다.
// 찾으면 찾은 문구를, 못 찾으면 null을 돌려준다.

/** @param {import('playwright').Page} page */
export async function checkEssentials(page) {
  return page.evaluate(() => {
    const norm = (s) => String(s ?? '').replace(/\s+/g, ' ').trim();
    const links = [...document.querySelectorAll('a[href], area[href]')].map((a) => ({
      text: norm(a.innerText || a.textContent),
      alt: norm([...a.querySelectorAll('img[alt]')].map((i) => i.alt).join(' ') || a.getAttribute('alt')),
      title: norm(a.getAttribute('title')),
      href: a.getAttribute('href') || '',
    }));
    const find = (re, hrefRe) => {
      const l = links.find((x) => re.test(x.text) || re.test(x.alt) || re.test(x.title) || (hrefRe && hrefRe.test(x.href)));
      return l ? (l.text || l.alt || l.title || l.href).slice(0, 60) : null;
    };
    const privacy = find(/개인\s*정보\s*(처리\s*)?방침|개인\s*정보\s*보호\s*(정책|방침)|privacy\s*policy/i, /privacy/i);
    const terms = find(/이용\s*약관|서비스\s*약관|홈페이지\s*(이용\s*)?약관|terms\s*of\s*(use|service)/i, /terms|yakgwan|agreement/i);

    // 저작권 표시: 화면 글자 → 없으면 이미지 대체 텍스트(바닥글 저작권 문구를 그림으로 넣은 경우)
    const COPY = /(copyright|ⓒ|©|\(c\)\s*\d{4}|all\s+rights\s+reserved|저작권)/i;
    const lines = (document.body?.innerText ?? '').split('\n').map(norm).filter(Boolean);
    let copyright = lines.find((l) => COPY.test(l)) ?? null;
    if (!copyright) copyright = [...document.querySelectorAll('img[alt]')].map((i) => norm(i.alt)).find((a) => COPY.test(a)) ?? null;
    return { privacy, terms, copyright: copyright ? copyright.slice(0, 80) : null };
  });
}
