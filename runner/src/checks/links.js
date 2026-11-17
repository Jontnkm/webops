// 페이지 안 링크들이 실제로 열리는지 하나씩 요청해 본다.

const USER_AGENT = 'Mozilla/5.0 (compatible; WebOpsRunner/0.1)';

/**
 * @param {{ href: string, selector: string, text: string }[]} links
 * @param {{ maxLinks?: number, concurrency?: number, linkTimeoutMs?: number, cache?: Map<string, Promise<object>> }} opts
 *   cache: 여러 페이지를 검사할 때 같은 주소를 다시 요청하지 않도록 결과를 나눠 쓴다 (주소 → 확인 결과)
 */
export async function checkLinks(links, { maxLinks = 100, concurrency = 5, linkTimeoutMs = 10000, cache = new Map() } = {}) {
  // 같은 주소는 한 번만 확인하되, 페이지 안 위치는 모두 기억한다.
  const byUrl = new Map();
  let skipped = 0;
  for (const link of links) {
    let u;
    try {
      u = new URL(link.href);
    } catch {
      skipped++;
      continue;
    }
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      skipped++; // mailto:, tel:, javascript: 등
      continue;
    }
    u.hash = '';
    if (!byUrl.has(u.href)) byUrl.set(u.href, { url: u.href, occurrences: [] });
    byUrl.get(u.href).occurrences.push({ selector: link.selector, text: link.text });
  }

  const all = [...byUrl.values()];
  const targets = all.slice(0, maxLinks);
  await runPool(targets, concurrency, async (target) => {
    if (!cache.has(target.url)) cache.set(target.url, probe(target.url, linkTimeoutMs));
    Object.assign(target, await cache.get(target.url));
  });

  return { total: all.length, checked: targets, truncated: all.length > targets.length, skipped };
}

async function probe(url, timeoutMs) {
  try {
    // HEAD는 가볍지만, HEAD에만 404·405를 주고 실제 GET은 정상인 서버가 많다.
    // (예: 실제 공공기관 사이트에서 HEAD 404 / GET 200 확인) → 오류면 반드시 GET으로 다시 확인한다.
    let res = await request(url, 'HEAD', timeoutMs);
    if (res.status >= 400) res = await request(url, 'GET', timeoutMs);
    return { status: res.status, ok: res.status < 400, error: null };
  } catch (err) {
    const reason = err.name === 'TimeoutError' ? '응답 시간 초과' : (err.cause?.code ?? err.message);
    return { status: null, ok: false, error: reason };
  }
}

async function request(url, method, timeoutMs) {
  const res = await fetch(url, {
    method,
    redirect: 'follow',
    headers: { 'user-agent': USER_AGENT },
    signal: AbortSignal.timeout(timeoutMs),
  });
  // 본문은 필요 없으므로 바로 닫는다.
  await res.body?.cancel().catch(() => {});
  return res;
}

// 동시에 size개씩만 요청 (상대 서버에 부담을 주지 않도록)
async function runPool(items, size, fn) {
  let next = 0;
  const workers = Array.from({ length: Math.min(size, items.length) }, async () => {
    while (next < items.length) {
      const item = items[next++];
      await fn(item);
    }
  });
  await Promise.all(workers);
}
