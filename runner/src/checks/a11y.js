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
  return {
    engineVersion: result.testEngine?.version ?? null,
    // 위반으로 확정된 항목
    violations: result.violations,
    // 자동으로 판단하지 못해 사람이 확인해야 하는 항목
    incomplete: result.incomplete,
  };
}
