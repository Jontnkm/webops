// 도구마다 다른 검사 결과를 하나의 "표준 JSON" 형식으로 바꾼다.
// 이 형식은 기획서 4장(검사 결과 표준 JSON)과 같으며, 이후 서버·AI·WMS 연동이 모두 이 형식만 읽는다.
import { createHash, randomBytes } from 'node:crypto';

export const SCHEMA_VERSION = '1.0';

const SEVERITY_BY_IMPACT = { critical: 'CRITICAL', serious: 'HIGH', moderate: 'MEDIUM', minor: 'LOW' };
export const SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

// 검색 결과에서 아예 빠질 수 있는 항목은 심각도를 올린다.
const SEO_SEVERITY = { 'is-crawlable': 'HIGH', 'http-status-code': 'HIGH', 'robots-txt': 'MEDIUM' };

// 접속은 되지만 자동 접속을 막는 경우가 많은 상태 → 깨진 링크로 단정하지 않고 "직접 확인 필요"로 둔다.
const RESTRICTED_STATUS = new Set([401, 403, 429]);

// 점검 종류별로 결과에 남길 분류 (접속 문제는 어느 점검이든 남긴다)
const CATEGORIES_BY_CHECK = {
  function: ['AVAILABILITY', 'LINK', 'IMAGE', 'RESOURCE', 'API', 'SCRIPT'],
  a11y: ['AVAILABILITY', 'ACCESSIBILITY'],
  perf: ['AVAILABILITY', 'PERFORMANCE'],
  seo: ['AVAILABILITY', 'SEO'],
  security: ['AVAILABILITY', 'SECURITY'],
  mobile: ['AVAILABILITY', 'MOBILE'],
};

export function makeJobId(date = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  const stamp = `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
  return `J-${stamp}-${randomBytes(2).toString('hex')}`;
}

/**
 * @param {{ jobId: string, startedAt: Date, finishedAt: Date, toolVersion: string, browserVersion: string|null,
 *           raw: { pages: any[], warnings: string[], scope?: object } }} input
 *   raw.pages: 페이지마다 { requestedUrl, loadError, page, a11y, links, mobile, lighthouse, shots }
 */
export function buildReport({ jobId, startedAt, finishedAt, raw, toolVersion, browserVersion }) {
  const all = [];
  const warnings = [...raw.warnings];
  const multi = raw.pages.length > 1;
  const allowed = CATEGORIES_BY_CHECK[raw.scope?.check] ?? null;
  const pages = raw.pages.map((rp) => {
    const own = pageFindings(jobId, rp, warnings, multi)
      .filter((f) => !allowed || allowed.includes(f.category) || f.category.startsWith('ESS_'));
    all.push(...own);
    // 페이지별 요약(점수표용): 페이지 안에서 같은 자리 반복은 1건, 공통 영역 문제는 각 페이지에 반영
    return pageEntry(rp, mergeShared(own));
  });
  // 같은 자리에서 되풀이되는 문제(목록의 같은 칸, 머리글·바닥글·퀵메뉴 등)는 1건으로 합친다 (고칠 곳은 한 곳)
  const findings = mergeShared(all);
  for (const f of findings) {
    delete f.group;
    delete f.place;
  }
  // 도구 정보는 Lighthouse를 돌린 첫 페이지 기준
  const first = raw.pages[0] ?? {};

  findings.sort(
    (a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity) || Number(a.manualCheck) - Number(b.manualCheck),
  );

  return {
    schemaVersion: SCHEMA_VERSION,
    jobId,
    tool: {
      name: 'webops-runner',
      version: toolVersion,
      browser: browserVersion,
      axeCore: raw.pages.find((p) => p.a11y)?.a11y.engineVersion ?? null,
      lighthouse: first.lighthouse?.version ?? null,
    },
    startedAt: startedAt.toISOString(),
    finishedAt: finishedAt.toISOString(),
    durationMs: finishedAt - startedAt,
    // 검사 범위: maxPages(요청한 최대 페이지 수), pageCount(실제 검사한 수), linksChecked(확인한 서로 다른 링크 수)
    scope: raw.scope ?? null,
    pages,
    summary: summarize(findings),
    findings,
    warnings,
  };
}

/**
 * '같은 자리의 같은 문제'를 1건으로 합친다. 같은 페이지 안에서 되풀이돼도, 여러 페이지에 걸쳐 있어도 1건.
 * 합친 항목: pages = 문제가 있는 페이지 주소 모두, occurrences = 실제로 발견된 횟수.
 * 같은 문제의 기준 (sharedKey):
 *   - 링크 · 이미지 · 파일 · 데이터 요청 · 보안 연결: 같은 규칙 + 같은 주소(target)
 *   - 화면 동작(스크립트 오류): 같은 규칙 + 같은 메시지
 *   - 접근성 · 휴대폰 화면: 같은 규칙 + 같은 자리 (+ 글자색 대비는 같은 색 조합)
 *     자리 = 검사할 때 브라우저에서 구한 구조(checks/a11y.js addPlaces), 없으면 placeOf(selector)
 *     예) 검색 결과 27곳의 같은 강조 글자, 목록 42줄의 같은 부제 → 1건. 메뉴와 본문처럼 자리가 다르면 따로.
 *   - 접속 · 로딩 속도 · 검색 노출은 페이지마다 다른 문제이므로 합치지 않는다
 * 합친 항목의 fingerprint는 '자리' 기준으로 만들어, 다음 검사에서 같은 문제로 알아본다.
 */
export function mergeShared(findings) {
  const byKey = new Map();
  const out = [];
  for (const f of findings) {
    const key = sharedKey(f);
    const hit = key ? byKey.get(key) : null;
    if (hit) {
      hit.occurrences += f.occurrences ?? 1;
      for (const u of f.pages ?? [f.pageUrl]) if (!hit.pages.includes(u)) hit.pages.push(u);
      if (!hit.screenshot && f.screenshot) hit.screenshot = f.screenshot;
      continue;
    }
    const copy = { ...f, pages: [...(f.pages ?? [f.pageUrl])], occurrences: f.occurrences ?? 1 };
    if (key) byKey.set(key, copy);
    out.push(copy);
  }
  for (const f of out) {
    const key = sharedKey(f);
    if (!key) continue;
    if (f.pages.length > 1) f.fingerprint = fingerprint('shared', f.ruleId, key);
    else if (f.category === 'ACCESSIBILITY' || f.category === 'MOBILE') f.fingerprint = fingerprint(f.pages[0], f.ruleId, key);
  }
  return out;
}

function sharedKey(f) {
  const base = `${f.manualCheck ? 'M' : 'F'}|${f.source}|${f.ruleId}`;
  switch (f.category) {
    case 'LINK':
    case 'IMAGE':
    case 'RESOURCE':
    case 'API':
    case 'SECURITY':
      return f.target ? `${base}|t|${f.target}` : f.category === 'SECURITY' ? base : null;
    case 'ESS_PRIVACY':
    case 'ESS_TERMS':
    case 'ESS_COPYRIGHT':
      return base; // 필수 요소는 페이지마다 같은 문제 (빠진 페이지를 pages[]에)
    case 'SCRIPT':
      return `${base}|e|${f.evidence ?? ''}|${f.target ?? ''}`;
    case 'ACCESSIBILITY':
    case 'MOBILE':
      if (!f.selector) return f.category === 'MOBILE' ? base : null; // 모바일 화면 설정·작은 글자처럼 페이지 전체 문제
      return `${base}|p|${f.place ?? placeOf(f.selector)}|${f.group ?? ''}`;
    default:
      return null; // AVAILABILITY, PERFORMANCE, SEO
  }
}

/**
 * 위치(selector) → '자리'. 목록·반복 영역에서 몇 번째인지와 주소·제목 같은 값만 다른 위치는 같은 자리로 본다.
 *   .list > li:nth-child(3) > a[href="/notice/8756"] > mark  →  .list > li > a[href] > mark
 *   #tab2 > span  →  #tab* > span  (숫자가 붙은 id는 같은 모양 반복)
 */
export function placeOf(selector) {
  return String(selector)
    .replace(/:nth-(?:last-)?(?:child|of-type)\([^)]*\)/g, '')
    .replace(/:(?:first|last|only)-(?:child|of-type)/g, '')
    .replace(/\[([\w:-]+)\s*[~|^$*]?=\s*(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[^\]]*)\]/g, '[$1]')
    .replace(/#([A-Za-z_-]*)\d[\w-]*/g, '#$1*')
    .replace(/\s+/g, ' ')
    .trim();
}

function pageEntry(raw, own) {
  return {
    requestedUrl: raw.requestedUrl,
    pageUrl: raw.page?.finalUrl ?? raw.requestedUrl,
    httpStatus: raw.page?.httpStatus ?? null,
    title: raw.page?.title ?? null,
    scores: raw.lighthouse?.scores ?? null,
    metrics: raw.lighthouse?.metrics ?? null,
    metricDetails: raw.lighthouse?.metricDetails ?? null,
    linkCount: raw.links?.total ?? null,
    linksChecked: raw.links?.checked.length ?? null,
    imageCount: raw.page?.imageCount ?? null,
    // 끝까지 스크롤한 결과: 내린 횟수, 페이지 높이, 스크롤하며 추가로 불러온 파일 수
    scroll: raw.page?.scroll ?? null,
    // 공공 웹사이트 필수 요소: 찾은 문구 (못 찾으면 null), 확인하지 않았으면 essentials 자체가 null
    essentials: raw.essentials ?? null,
    // 보안 인증서 (보안 점검의 첫 페이지만): 만료일 · 남은 날 · 발급 기관 · 신뢰 여부
    cert: raw.securitySite?.cert ?? null,
    mobile: raw.mobile
      ? { pageUrl: raw.mobile.finalUrl, viewportWidth: raw.mobile.viewportWidth, scrollWidth: raw.mobile.scrollWidth }
      : null,
    summary: summarize(own),
  };
}

/** 페이지 하나의 검사 결과 → 표준 형식의 발견 항목 목록 */
function pageFindings(jobId, raw, warnings, multi) {
  const pageUrl = raw.page?.finalUrl ?? raw.requestedUrl;
  const findings = [];
  // 문제 위치 캡처: 휴대폰 화면 문제는 휴대폰으로 찍은 것, 나머지는 PC 화면에서 찍은 것
  const shotOf = (f) => {
    if (!f.selector) return null;
    const map = f.category === 'MOBILE' ? raw.mobile?.shots : raw.shots;
    return map?.[f.selector] ?? null;
  };
  const add = (f) => findings.push(toFinding(jobId, pageUrl, { ...f, screenshot: shotOf(f) }));

  // 1) 페이지 접속
  if (raw.loadError) {
    add({
      category: 'AVAILABILITY', ruleId: 'page-unreachable', source: 'playwright', severity: 'CRITICAL',
      description: `페이지에 접속할 수 없음 (${friendlyReason(raw.loadError)})`, evidence: raw.loadError, target: raw.requestedUrl,
    });
  }
  if (raw.page) {
    const page = raw.page;
    const sameSite = (u) => sameHost(u, pageUrl);
    const status = page.httpStatus;
    if (status != null && status >= 400) {
      add({
        category: 'AVAILABILITY', ruleId: 'page-http-error', source: 'playwright', severity: 'CRITICAL',
        description: `페이지가 열리지 않음: ${friendlyStatus(status)}`, evidence: `응답 코드 ${status}`, target: pageUrl,
      });
    }

    // 2) 깨진 이미지
    for (const img of page.brokenImages) {
      if (img.reason) {
        // 오류가 확인된 진짜 깨진 이미지
        add({
          category: 'IMAGE', ruleId: 'image-broken', source: 'playwright', severity: 'MEDIUM',
          selector: img.selector, evidence: img.html, target: img.src,
          description: `이미지를 불러오지 못함: ${friendlyReason(img.reason, '파일')}`,
        });
      } else {
        // 서버 응답은 정상인데 그림이 아님 → 방문 통계 수집용 <img> 등 의도된 경우가 많아 직접 확인으로 둔다.
        add({
          category: 'IMAGE', ruleId: 'image-not-rendered', source: 'playwright', severity: 'LOW', manualCheck: true,
          selector: img.selector, evidence: img.html, target: img.src,
          description: '[직접 확인 필요] 이미지로 표시되지 않음 (서버 응답은 정상 · 통계 수집용일 수 있음)',
        });
      }
    }

    // 3) 화면에 필요한 파일(CSS·JS·글꼴 등)을 못 불러옴 → 모양·기능이 깨질 수 있음
    for (const r of page.failedResources) {
      const kind = RESOURCE_KIND[r.type] ?? RESOURCE_KIND.other;
      const external = !sameSite(r.url);
      add({
        category: 'RESOURCE', ruleId: 'resource-failed', source: 'playwright',
        severity: external ? 'LOW' : kind.severity,
        target: r.url,
        description: `${kind.label} 파일을 불러오지 못함: ${friendlyReason(r.reason, '파일')}${external ? ' · 외부 사이트 파일' : ''}`,
      });
    }

    // 4) 데이터 요청(API) 실패 → 목록·검색 결과 같은 영역이 비어 보일 수 있음
    for (const a of page.failedApis) {
      const external = !sameSite(a.url);
      add({
        category: 'API', ruleId: 'api-failed', source: 'playwright',
        severity: external ? 'LOW' : 'HIGH',
        target: a.url, evidence: `${a.method} ${a.url}`,
        description: `데이터를 불러오지 못함: ${friendlyReason(a.reason, '주소')}${external ? ' · 외부 사이트' : ''}`,
      });
    }

    // 5) 스크립트 오류 → 메뉴·슬라이드·버튼 동작이 멈출 수 있음
    for (const message of page.jsErrors) {
      add({
        category: 'SCRIPT', ruleId: 'js-error', source: 'playwright', severity: 'MEDIUM',
        evidence: message, description: '화면 동작 중 스크립트 오류 발생',
      });
    }
    for (const c of page.consoleErrors) {
      add({
        category: 'SCRIPT', ruleId: 'console-error', source: 'playwright', severity: 'LOW',
        evidence: c.text, target: c.source,
        description: `브라우저가 오류 메시지를 남김${c.count > 1 ? ` (${c.count}번)` : ''}`,
      });
    }

    // 6) 보안 연결(https) 페이지에서 보안 안 된(http) 파일을 부름
    for (const u of page.mixedContent) {
      add({
        category: 'SECURITY', ruleId: 'mixed-content', source: 'playwright', severity: 'MEDIUM',
        target: u, description: '보안 연결(https) 페이지가 보안이 안 된 주소(http)의 파일을 부름',
      });
    }
  }

  // 7) 모바일 화면이 옆으로 밀림
  if (raw.mobile && raw.mobile.scrollWidth - raw.mobile.viewportWidth > 1) {
    const { viewportWidth, scrollWidth, offenders } = raw.mobile;
    const base = `휴대폰 화면(가로 ${viewportWidth}px)에서 페이지가 옆으로 ${scrollWidth - viewportWidth}px 밀림`;
    if (offenders.length === 0) {
      add({ category: 'MOBILE', ruleId: 'mobile-horizontal-scroll', source: 'playwright', severity: 'MEDIUM', description: base });
    }
    for (const o of offenders) {
      add({
        category: 'MOBILE', ruleId: 'mobile-horizontal-scroll', source: 'playwright', severity: 'MEDIUM',
        selector: o.selector, evidence: o.html,
        description: `${base} · 이 요소가 화면보다 ${o.overBy}px 넓음`,
      });
    }
  }

  // 4) 웹 접근성
  if (raw.a11y) {
    for (const v of raw.a11y.violations) {
      for (const node of v.nodes) {
        const contrast = v.id === 'color-contrast' ? contrastOf(node) : null;
        add({
          category: 'ACCESSIBILITY', ruleId: v.id, source: 'axe-core',
          severity: SEVERITY_BY_IMPACT[node.impact ?? v.impact] ?? 'MEDIUM',
          selector: node.target.join(' '), evidence: node.html, helpUrl: v.helpUrl,
          description: contrast ? `${v.help} · ${contrast.text}` : v.help,
          // 같은 자리(브라우저에서 구한 구조) + 글자색 대비는 색 조합까지 같아야 같은 문제
          place: node.place, group: contrast?.key,
        });
      }
    }
    for (const v of raw.a11y.incomplete) {
      for (const node of v.nodes) {
        add({
          category: 'ACCESSIBILITY', ruleId: v.id, source: 'axe-core',
          severity: SEVERITY_BY_IMPACT[node.impact ?? v.impact] ?? 'LOW',
          selector: node.target.join(' '), evidence: node.html,
          description: `[직접 확인 필요] ${v.help}`, helpUrl: v.helpUrl, manualCheck: true, place: node.place,
        });
      }
    }
  }

  // 모바일 환경: 화면 설정 · 확대 막음 · 작은 글자
  if (raw.mobile) {
    const m = raw.mobile;
    if (!m.viewportMeta) {
      add({ category: 'MOBILE', ruleId: 'mobile-viewport-missing', source: 'playwright', severity: 'MEDIUM',
        description: '모바일 화면 설정(viewport)이 없음 · 휴대폰에서 PC 화면이 작게 줄어 보임 (반응형이 아님)' });
    } else if (m.zoomBlocked) {
      add({ category: 'MOBILE', ruleId: 'mobile-zoom-blocked', source: 'playwright', severity: 'MEDIUM',
        description: '손가락으로 화면을 확대하지 못하게 막아 둠 · 글자가 작으면 읽을 수 없음', evidence: `<meta name="viewport" content="${m.viewportMeta}">` });
    }
    if (m.smallText?.count >= 5) {
      add({ category: 'MOBILE', ruleId: 'mobile-small-text', source: 'playwright', severity: 'LOW',
        description: `휴대폰에서 글자가 너무 작음 (12px 미만 ${m.smallText.count}곳)`,
        evidence: m.smallText.samples.map((x) => `${x.size}px "${x.text}"`).join('\n') });
    }
  }

  // 보안: 페이지마다 (헤더 · 쿠키) + 첫 페이지에 사이트 한 번 (인증서 · http 넘김)
  for (const s of [...(raw.security ?? []), ...(raw.securitySite?.findings ?? [])]) {
    add({ category: 'SECURITY', ruleId: s.rule, source: 'webops-runner', severity: s.severity,
      description: s.message, target: s.target ?? null, evidence: s.evidence ?? null });
  }

  // 공공 웹사이트 필수 요소 (보통 바닥글에 있음. 여러 페이지에서 빠지면 1건 + pages[])
  if (raw.essentials) {
    const e = raw.essentials;
    if (!e.privacy) {
      add({ category: 'ESS_PRIVACY', ruleId: 'privacy-policy-missing', source: 'webops-runner', severity: 'HIGH',
        description: '개인정보처리방침 링크를 찾지 못함' });
    }
    if (!e.terms) {
      add({ category: 'ESS_TERMS', ruleId: 'terms-missing', source: 'webops-runner', severity: 'LOW',
        description: '이용약관 링크를 찾지 못함 (회원 가입·로그인 등 서비스가 있는 사이트는 필요)' });
    }
    if (!e.copyright) {
      add({ category: 'ESS_COPYRIGHT', ruleId: 'copyright-missing', source: 'webops-runner', severity: 'LOW',
        description: '저작권 표시(Copyright ⓒ 등)를 찾지 못함' });
    }
  }

  // 5) 링크
  if (raw.links) {
    if (raw.links.truncated) {
      const on = multi ? `${pageUrl} 페이지: ` : '';
      warnings.push(`${on}링크 ${raw.links.total}개 중 앞의 ${raw.links.checked.length}개만 확인함 (--max-links로 조정)`);
    }
    for (const link of raw.links.checked) {
      if (link.ok) continue;
      const first = link.occurrences[0];
      const where = link.occurrences.length > 1 ? ` · 페이지 안 ${link.occurrences.length}곳` : '';
      const evidence = `링크 문구: "${first.text}"`;
      if (RESTRICTED_STATUS.has(link.status)) {
        add({
          category: 'LINK', ruleId: 'link-restricted', source: 'webops-runner', severity: 'LOW', manualCheck: true,
          selector: first.selector, evidence, target: link.url,
          description: `[직접 확인 필요] 상대 사이트가 자동 확인을 막음: ${friendlyStatus(link.status)}${where}`,
        });
      } else {
        add({
          category: 'LINK', ruleId: 'link-broken', source: 'webops-runner', severity: 'MEDIUM',
          selector: first.selector, evidence, target: link.url,
          description: `깨진 링크: ${link.status ? friendlyStatus(link.status) : friendlyReason(link.error)}${where}`,
        });
      }
    }
  }

  // 8) 로딩 속도·검색 노출 (Lighthouse)
  if (raw.lighthouse) {
    // 로딩 속도를 늦춘 원인: 원인 파일·요소가 있으면 하나씩, 없으면 한 건으로
    for (const p of raw.lighthouse.perfIssues ?? []) {
      // 0.5초 이상 줄일 수 있거나 화면 밀림이 '나쁨' 수준이면 '보통', 그보다 작으면 '낮음'
      const severity = p.savings >= 500 ? 'MEDIUM' : 'LOW';
      const description = p.displayValue ? `${p.title} (${p.displayValue})` : p.title;
      if (p.items.length === 0) {
        add({ category: 'PERFORMANCE', ruleId: p.id, source: 'lighthouse', severity, description });
      }
      for (const it of p.items) {
        add({
          category: 'PERFORMANCE', ruleId: p.id, source: 'lighthouse', severity, description,
          selector: it.selector, target: it.url, evidence: [it.snippet, it.extra].filter(Boolean).join('\n') || null,
        });
      }
    }

    // image-alt처럼 접근성 검사(axe-core)가 이미 잡은 규칙은 검색 노출 쪽에서 다시 세지 않는다.
    const a11yRules = new Set((raw.a11y?.violations ?? []).map((v) => v.id));
    for (const audit of raw.lighthouse.seoFailures) {
      if (a11yRules.has(audit.id)) continue;
      add({
        category: 'SEO', ruleId: audit.id, source: 'lighthouse', severity: SEO_SEVERITY[audit.id] ?? 'LOW',
        description: audit.title, evidence: audit.displayValue,
      });
    }
  }

  return findings;
}

/** axe 글자색 대비 결과 → 근거 문구 "대비 2.9:1 (기준 4.5:1 이상) · 글자색 #eb6b00 · 배경색 #fef3ee · 글자 16px" */
function contrastOf(node) {
  const d = node.any?.find((c) => c.data?.contrastRatio != null)?.data;
  if (!d) return null;
  const px = /\((\d+(?:\.\d+)?)px\)/.exec(d.fontSize ?? '')?.[1];
  const expected = String(d.expectedContrastRatio ?? '4.5:1').replace(/:1$/, '');
  return {
    key: `${d.fgColor}|${d.bgColor}`,
    text: `대비 ${d.contrastRatio}:1 (기준 ${expected}:1 이상) · 글자색 ${d.fgColor} · 배경색 ${d.bgColor}`
      + (px ? ` · 글자 ${px}px${d.fontWeight === 'bold' ? ' 굵게' : ''}` : ''),
  };
}

function toFinding(jobId, pageUrl, f) {
  const selector = f.selector ?? null;
  const target = f.target ?? null;
  return {
    schemaVersion: SCHEMA_VERSION,
    jobId,
    pageUrl,
    category: f.category,
    ruleId: f.ruleId,
    source: f.source,
    severity: f.severity,
    selector,
    target,
    evidence: f.evidence ? mask(truncate(String(f.evidence), 500)) : null,
    description: f.description,
    helpUrl: f.helpUrl ?? null,
    manualCheck: Boolean(f.manualCheck),
    // 문제 위치를 빨간 상자로 표시한 캡처 (리포트 파일 기준 상대 경로, 없으면 null)
    screenshot: f.screenshot ?? null,
    // 같은 페이지·규칙·위치면 같은 값 → 다음 검사에서 "같은 문제인지" 비교하는 열쇠
    fingerprint: fingerprint(pageUrl, f.ruleId, selector ?? target ?? f.evidence ?? ''),
    // 합칠 때만 쓰는 값 (자리, 글자색 대비의 색 조합). 결과 파일에는 남기지 않는다.
    ...(f.place ? { place: f.place } : {}),
    ...(f.group ? { group: f.group } : {}),
  };
}

function summarize(findings) {
  const bySeverity = Object.fromEntries(SEVERITY_ORDER.map((s) => [s, 0]));
  const byCategory = {};
  let manualCheck = 0;
  for (const f of findings) {
    if (f.manualCheck) {
      manualCheck++;
      continue;
    }
    bySeverity[f.severity]++;
    byCategory[f.category] = (byCategory[f.category] ?? 0) + 1;
  }
  return { total: findings.length - manualCheck, manualCheck, bySeverity, byCategory };
}

function fingerprint(...parts) {
  return createHash('sha1').update(parts.join('|')).digest('hex').slice(0, 16);
}

function truncate(text, max) {
  return text.length > max ? `${text.slice(0, max)}…` : text;
}

// ---------- 쉬운 말로 바꾸기 ----------

const RESOURCE_KIND = {
  stylesheet: { label: '화면 디자인(CSS)', severity: 'HIGH' },
  script: { label: '기능 스크립트(JS)', severity: 'HIGH' },
  font: { label: '글꼴', severity: 'LOW' },
  media: { label: '동영상·음성', severity: 'MEDIUM' },
  other: { label: '기타', severity: 'MEDIUM' },
};

const STATUS_TEXT = {
  400: '잘못된 요청', 401: '로그인 필요', 403: '접근이 막힘', 404: '없는 페이지',
  405: '허용되지 않는 요청 방식', 408: '응답 시간 초과', 410: '삭제됨', 429: '요청이 너무 많음',
  500: '서버 오류', 502: '서버 연결 오류', 503: '서버 점검 또는 과부하', 504: '서버 응답 시간 초과',
};

/** 응답 코드 → "없는 페이지(404)" (noun: 404일 때 무엇이 없는지 - 페이지/파일/주소) */
export function friendlyStatus(code, noun = '페이지') {
  const text = code === 404 ? `없는 ${noun}` : (STATUS_TEXT[code] ?? (code >= 500 ? '서버 오류' : '오류'));
  return `${text}(${code})`;
}

const NETWORK_TEXT = [
  [/NAME_NOT_RESOLVED|ENOTFOUND|EAI_AGAIN/, '주소를 찾을 수 없음'],
  [/CONNECTION_REFUSED|ECONNREFUSED/, '서버가 연결을 거부함'],
  [/TIMED_OUT|ETIMEDOUT|시간 초과|Timeout/i, '응답 시간 초과'],
  [/CERT|SSL|TLS/, '보안 인증서 문제'],
  [/CONNECTION_RESET|ECONNRESET|CONNECTION_CLOSED/, '연결이 끊김'],
  [/BLOCKED/, '브라우저가 차단함'],
];

/** "HTTP 404", "net::ERR_NAME_NOT_RESOLVED" 같은 실패 이유 → 쉬운 말 */
export function friendlyReason(reason, noun = '페이지') {
  const text = String(reason ?? '');
  const m = /^HTTP (\d{3})$/.exec(text);
  if (m) return friendlyStatus(Number(m[1]), noun);
  for (const [re, label] of NETWORK_TEXT) if (re.test(text)) return label;
  return text || '알 수 없는 오류';
}

function sameHost(a, b) {
  try {
    const ha = new URL(a).hostname.replace(/^www\./, '');
    const hb = new URL(b).hostname.replace(/^www\./, '');
    return ha === hb || ha.endsWith(`.${hb}`) || hb.endsWith(`.${ha}`);
  } catch {
    return false;
  }
}

// 근거(evidence)에 섞여 들어온 개인정보는 저장·전송 전에 가린다.
const EMAIL = /[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g;
const PHONE = /\b0\d{1,2}[-.\s]?\d{3,4}[-.\s]?\d{4}\b/g;
export function mask(text) {
  return text.replace(EMAIL, '[이메일 가림]').replace(PHONE, '[전화번호 가림]');
}
