// 페이지를 끝까지 조금씩 내려가며, 스크롤해야 불러오는 이미지(lazy 로딩)·내용까지 화면에 띄운다.
// 공공기관 메인처럼 아래쪽 배너·이미지를 늦게 부르는 페이지에서 깨진 이미지를 놓치지 않기 위함.
// 끝없이 길어지는 페이지(무한 스크롤)도 있으므로 횟수·시간에 제한을 둔다.

/**
 * @param {import('playwright').Page} page
 * @param {{ maxSteps?: number, maxMs?: number, stepWaitMs?: number }} opts
 * @returns {Promise<{ steps: number, pageHeight: number, reachedEnd: boolean }>}
 */
export async function autoScroll(page, { maxSteps = 40, maxMs = 12000, stepWaitMs = 250 } = {}) {
  const started = Date.now();
  let steps = 0;
  let reachedEnd = false;
  let pageHeight = 0;
  let lastY = -1;

  while (steps < maxSteps && Date.now() - started < maxMs) {
    const pos = await page.evaluate(() => {
      window.scrollBy(0, Math.round(window.innerHeight * 0.8));
      const root = document.scrollingElement || document.documentElement;
      return { y: window.scrollY, height: root.scrollHeight, viewport: window.innerHeight };
    });
    steps++;
    pageHeight = pos.height;
    await page.waitForTimeout(stepWaitMs);
    // 맨 아래에 닿았거나, 더 내려가지 않으면(스크롤이 막힌 페이지) 멈춘다
    if (pos.y + pos.viewport >= pos.height - 2 || pos.y === lastY) {
      reachedEnd = true;
      break;
    }
    lastY = pos.y;
  }

  // 마지막으로 불러오기 시작한 이미지가 끝날 때까지 잠깐 기다린 뒤 맨 위로 돌아간다.
  await page.waitForLoadState('networkidle', { timeout: 4000 }).catch(() => {});
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.waitForTimeout(200);
  return { steps, pageHeight, reachedEnd };
}
