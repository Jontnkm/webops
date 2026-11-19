// axe-core로 웹 접근성 자동 검사.
// 주의: axe-core는 WCAG 기준 도구이며, KWCAG 인증을 대신하지 않는다.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import * as axePlaywright from '@axe-core/playwright';

const AxeBuilder = axePlaywright.AxeBuilder ?? axePlaywright.default;

// 페이지에 주입할 axe-core 스크립트 + 한국어 번역 설정 (규칙 설명이 한국어로 나온다)
const AXE_SOURCE_KO = loadAxeSourceWithKorean();

function loadAxeSourceWithKorean() {
  try {
    // axe-core는 @axe-core/playwright가 의존하는 패키지이므로 그 위치에서 찾는다.
    const require = createRequire(createRequire(import.meta.url).resolve('@axe-core/playwright'));
    const axeDir = path.dirname(require.resolve('axe-core/package.json'));
    const source = readFileSync(path.join(axeDir, 'axe.min.js'), 'utf8');
    const ko = readFileSync(path.join(axeDir, 'locales', 'ko.json'), 'utf8');
    return `${source};axe.configure({ locale: ${ko} });`;
  } catch {
    return null; // 번역 파일을 못 찾으면 기본(영어)으로 진행
  }
}

// 검사할 WCAG 기준 (A, AA 등급)
const TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

/** @param {import('playwright').Page} page */
export async function checkA11y(page) {
  const builder = new AxeBuilder(AXE_SOURCE_KO ? { page, axeSource: AXE_SOURCE_KO } : { page });
  const result = await builder.withTags(TAGS).analyze();
  await addPlaces(page, [...result.violations, ...result.incomplete]);
  return {
    engineVersion: result.testEngine?.version ?? null,
    // 위반으로 확정된 항목
    violations: result.violations,
    // 자동으로 판단하지 못해 사람이 확인해야 하는 항목
    incomplete: result.incomplete,
  };
}

/**
 * 문제 요소마다 '자리'(node.place)를 붙인다. 같은 자리의 같은 문제는 결과에서 1건으로 묶인다.
 * 자리 = 요소에서 위로 올라가며 [태그.클래스] 를 이은 것 (id가 있으면 거기서 멈춤).
 *   - 되풀이되는 칸(목록 li·표 줄·같은 클래스 블록이 2개 이상, 문장 안의 강조 글자·링크)은 몇 번째인지를 빼서 → 같은 자리
 *     예) 검색 결과 목록 27줄의 강조 글자, 한 제목 안의 검색어 강조 3개, 슬라이드 점 버튼 3개 → 1건
 *   - 그 밖에는 몇 번째인지를 남겨서 → 다른 자리 (한 영역에 나란히 있는 로고 그림과 배너 그림은 따로)
 */
async function addPlaces(page, rules) {
  const targets = [];
  for (const r of rules) for (const n of r.nodes) if (n.target?.length === 1) targets.push(String(n.target[0]));
  if (targets.length === 0) return;
  let places = [];
  try {
    places = await page.evaluate((sels) => {
      const LIST_TAGS = new Set(['li', 'tr', 'td', 'th', 'dt', 'dd', 'option', 'article',
        // 문장 안의 글자 꾸밈·링크(검색어 강조 mark 등)는 몇 번째든 같은 자리
        'mark', 'a', 'span', 'b', 'strong', 'em', 'i', 'u', 'small', 'sup', 'sub', 'abbr', 'code', 'label']);
      const sig = (el) => {
        const cls = [...el.classList].map((c) => c.replace(/\d+/g, '*')).sort().join('.');
        return el.tagName.toLowerCase() + (cls ? `.${cls}` : '');
      };
      const stableId = (el) => (el.id && !/\d/.test(el.id) ? el.id : null);
      const placeOf = (el) => {
        const parts = [];
        for (let cur = el; cur && cur.nodeType === 1 && cur !== document.body && cur !== document.documentElement; cur = cur.parentElement) {
          const id = stableId(cur);
          if (id) { parts.unshift(`#${id}`); break; }
          const s = sig(cur);
          const same = cur.parentElement ? [...cur.parentElement.children].filter((c) => sig(c) === s) : [cur];
          const repeated = same.length > 1 && (LIST_TAGS.has(cur.tagName.toLowerCase()) || cur.classList.length > 0);
          parts.unshift(repeated || same.length === 1 ? s : `${s}(${same.indexOf(cur) + 1})`);
        }
        return parts.join(' > ') || null;
      };
      return sels.map((sel) => {
        try {
          const el = document.querySelector(sel);
          return el ? placeOf(el) : null;
        } catch {
          return null;
        }
      });
    }, targets);
  } catch {
    return; // 자리를 못 구하면 위치(selector)로 묶는다
  }
  let i = 0;
  for (const r of rules) for (const n of r.nodes) if (n.target?.length === 1) n.place = places[i++] ?? null;
}
