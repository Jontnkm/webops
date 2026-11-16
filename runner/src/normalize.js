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

export function makeJobId(date = new Date()) {
  const p = (n) => String(n).padStart(2, '0');
  const stamp = `${date.getFullYear()}${p(date.getMonth() + 1)}${p(date.getDate())}-${p(date.getHours())}${p(date.getMinutes())}${p(date.getSeconds())}`;
  return `J-${stamp}-${randomBytes(2).toString('hex')}`;
}

/**
 * @param {{ jobId: string, startedAt: Date, finishedAt: Date, raw: any, toolVersion: string, browserVersion: string|null }} input
 */
export function buildReport({ jobId, startedAt, finishedAt, raw, toolVersion, browserVersion }) {
  const pageUrl = raw.page?.finalUrl ?? raw.requestedUrl;
  const findings = [];
  const add = (f) => findings.push(toFinding(jobId, pageUrl, f));
  const warnings = [...raw.warnings];

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
        add({
          category: 'ACCESSIBILITY', ruleId: v.id, source: 'axe-core',
          severity: SEVERITY_BY_IMPACT[node.impact ?? v.impact] ?? 'MEDIUM',
          selector: node.target.join(' '), evidence: node.html, description: v.help, helpUrl: v.helpUrl,
        });
      }
    }
    for (const v of raw.a11y.incomplete) {
      for (const node of v.nodes) {
        add({
          category: 'ACCESSIBILITY', ruleId: v.id, source: 'axe-core',
          severity: SEVERITY_BY_IMPACT[node.impact ?? v.impact] ?? 'LOW',
          selector: node.target.join(' '), evidence: node.html,
          description: `[직접 확인 필요] ${v.help}`, helpUrl: v.helpUrl, manualCheck: true,
        });
      }
    }
  }

  // 5) 링크
  if (raw.links) {
    if (raw.links.truncated) {
      warnings.push(`링크 ${raw.links.total}개 중 앞의 ${raw.links.checked.length}개만 확인함 (--max-links로 조정)`);
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
      axeCore: raw.a11y?.engineVersion ?? null,
      lighthouse: raw.lighthouse?.version ?? null,
    },
    startedAt: startedAt.toISOString(),
    finishedAt: finishedAt.toISOString(),
    durationMs: finishedAt - startedAt,
    pages: [
      {
        requestedUrl: raw.requestedUrl,
        pageUrl,
        httpStatus: raw.page?.httpStatus ?? null,
        title: raw.page?.title ?? null,
        scores: raw.lighthouse?.scores ?? null,
        metrics: raw.lighthouse?.metrics ?? null,
        metricDetails: raw.lighthouse?.metricDetails ?? null,
        linkCount: raw.links?.total ?? null,
        linksChecked: raw.links?.checked.length ?? null,
        imageCount: raw.page?.imageCount ?? null,
        mobile: raw.mobile
          ? { pageUrl: raw.mobile.finalUrl, viewportWidth: raw.mobile.viewportWidth, scrollWidth: raw.mobile.scrollWidth }
          : null,
      },
    ],
    summary: summarize(findings),
    findings,
    warnings,
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
    // 같은 페이지·규칙·위치면 같은 값 → 다음 검사에서 "같은 문제인지" 비교하는 열쇠
    fingerprint: fingerprint(pageUrl, f.ruleId, selector ?? target ?? f.evidence ?? ''),
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
