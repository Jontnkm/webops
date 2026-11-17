// 문제가 된 요소를 빨간 상자로 표시해 화면을 캡처한다.
// 코드 위치(#home > a) 대신 그림으로 보여 주면 비전문가도 어디가 문제인지 바로 알 수 있다.
import { mkdir } from 'node:fs/promises';
import path from 'node:path';

const MARK_ID = '__webops_mark__';
const PAD = 120; // 요소 주변을 이만큼 더 넣어 위치를 알아보기 쉽게
const MIN_W = 640;
const MIN_H = 320;

/**
 * @param {import('playwright').Page} page
 * @param {string[]} selectors 우선순위 순서 (앞의 것부터 찍는다)
 * @param {{ dir: string, urlPrefix: string, prefix: string, max?: number }} opts
 *   dir: 저장 폴더, urlPrefix: 리포트에 적을 상대 경로 앞부분, prefix: 파일 이름 앞부분
 * @returns {Promise<Record<string, string>>} 선택자 → 캡처 파일 상대 경로
 */
export async function captureElements(page, selectors, { dir, urlPrefix, prefix, max = 30 }) {
  const result = {};
  const unique = [...new Set(selectors.filter(Boolean))];
  if (unique.length === 0 || max <= 0) return result;
  await mkdir(dir, { recursive: true });

  const viewport = page.viewportSize() ?? { width: 1280, height: 720 };
  const byElement = new Map(); // 같은 요소를 다른 선택자로 가리키면 한 번만 찍는다 (요소 위치 → 파일)
  let n = 0;
  for (const selector of unique) {
    if (n >= max) break;
    // 페이지 전체(html·body)는 상자로 표시할 의미가 없다
    if (WHOLE_PAGE.test(selector.trim())) continue;
    try {
      const shot = await captureOne(page, selector, viewport, path.join(dir, `${prefix}${n + 1}.jpg`), byElement);
      if (shot?.reuse) {
        result[selector] = shot.reuse;
      } else if (shot?.key) {
        n++;
        result[selector] = `${urlPrefix}/${prefix}${n}.jpg`;
        byElement.set(shot.key, result[selector]);
      }
    } catch {
      // 찾을 수 없거나 찍을 수 없는 요소(숨김, iframe 안 등)는 건너뛴다.
    } finally {
      await page.evaluate((id) => document.getElementById(id)?.remove(), MARK_ID).catch(() => {});
    }
  }
  return result;
}

const WHOLE_PAGE = /^(html|body|html\s*>\s*body)$/i;

/** @returns {Promise<{ key: string } | { reuse: string } | null>} 새로 찍음 / 이미 찍은 같은 요소 / 못 찍음 */
async function captureOne(page, selector, viewport, file, byElement) {
  const loc = page.locator(selector).first();
  if ((await loc.count()) === 0) return null;
  // 세로는 가운데로, 가로는 꼭 필요할 때만 움직인다 (화면보다 넓은 요소는 시작 부분이 보이게)
  await loc.evaluate((el) => el.scrollIntoView({ block: 'center', inline: 'nearest' }), null, { timeout: 2000 });
  const box = await loc.boundingBox({ timeout: 2000 });
  if (!box || box.width < 1 || box.height < 1) return null;
  // 화면 밖으로 숨겨 둔 요소(예: left:-9999px 건너뛰기 링크)는 찍지 않는다
  if (box.x + box.width <= 0 || box.y + box.height <= 0 || box.x >= viewport.width || box.y >= viewport.height) return null;

  // 요소 위에 빨간 상자를 겹쳐 그린다 (페이지 내용은 건드리지 않음).
  // 휴대폰 화면에서 넓은 요소가 있으면 브라우저가 페이지 폭을 늘리므로, 화면 기준(fixed)이 아니라 문서 기준(absolute)으로 놓는다.
  const key = await loc.evaluate((el, id) => {
    const r = el.getBoundingClientRect();
    const x = r.left + window.scrollX;
    const y = r.top + window.scrollY;
    const mark = document.createElement('div');
    mark.id = id;
    mark.style.cssText = [
      'position:absolute', `left:${x - 4}px`, `top:${y - 4}px`, `width:${r.width + 8}px`, `height:${r.height + 8}px`,
      'border:3px solid #e5121b', 'border-radius:4px', 'box-shadow:0 0 0 2px rgba(255,255,255,.9)',
      'box-sizing:border-box', 'pointer-events:none', 'z-index:2147483647', 'margin:0', 'padding:0',
    ].join(';');
    document.documentElement.appendChild(mark);
    return [x, y, r.width, r.height].map(Math.round).join(',');
  }, MARK_ID);
  if (byElement.has(key)) return { reuse: byElement.get(key) };

  await page.screenshot({
    path: file,
    type: 'jpeg',
    quality: 70,
    scale: 'css',
    animations: 'disabled',
    timeout: 5000,
    clip: clipAround(box, viewport),
  });
  return { key };
}

// 요소 주변 영역 (최소 크기를 보장하고 화면 안으로 맞춘다)
function clipAround(box, viewport) {
  const minW = Math.min(MIN_W, viewport.width);
  const minH = Math.min(MIN_H, viewport.height);
  let x0 = box.x - PAD;
  let x1 = box.x + box.width + PAD;
  let y0 = box.y - PAD;
  let y1 = box.y + box.height + PAD;
  if (x1 - x0 < minW) {
    const cx = (x0 + x1) / 2;
    x0 = cx - minW / 2;
    x1 = cx + minW / 2;
  }
  if (y1 - y0 < minH) {
    const cy = (y0 + y1) / 2;
    y0 = cy - minH / 2;
    y1 = cy + minH / 2;
  }
  // 화면 밖으로 나간 만큼 안쪽으로 밀고, 그래도 크면 화면 크기로 자른다
  if (x0 < 0) { x1 -= x0; x0 = 0; }
  if (y0 < 0) { y1 -= y0; y0 = 0; }
  if (x1 > viewport.width) { x0 = Math.max(0, x0 - (x1 - viewport.width)); x1 = viewport.width; }
  if (y1 > viewport.height) { y0 = Math.max(0, y0 - (y1 - viewport.height)); y1 = viewport.height; }
  return { x: Math.round(x0), y: Math.round(y0), width: Math.round(x1 - x0), height: Math.round(y1 - y0) };
}
