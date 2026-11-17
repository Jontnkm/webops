# WebOps 검사 실행기 (runner)

AI WebOps(가칭) 로드맵 1단계. URL 하나를 실제 브라우저로 열어 검사하고, 결과를 **표준 JSON** 파일로 저장한다.
서버·DB·화면·AI는 아직 없다. 2단계 이후 Spring 서버가 이 실행기를 불러 쓴다.

## 검사 항목

| 구분 | 도구 | 잡는 것 |
| --- | --- | --- |
| 접속 | Playwright | 접속 불가, 오류 응답(404·500 등) |
| 파일 불러오기 | Playwright | CSS·JS·글꼴 등 화면에 필요한 파일 로딩 실패 |
| 데이터 불러오기 | Playwright | 페이지가 부르는 데이터 요청(API)의 오류 응답·실패 |
| 화면 동작 | Playwright | 처리되지 않은 스크립트 오류, 브라우저 콘솔 오류 |
| 모바일 화면 | Playwright (가로 360px) | 화면이 옆으로 밀리는지, 원인 요소 |
| 링크 | Node fetch (HEAD 실패 시 GET 재확인) | 깨진 링크, 연결 실패 |
| 이미지 | Playwright (끝까지 스크롤) | 불러오지 못한 이미지. 페이지 끝까지 내려가 늦게 뜨는(lazy) 이미지까지 확인 (통계 수집용 img는 직접 확인으로 분리) |
| 보안 연결 | Playwright | https 페이지에서 http 파일을 부르는 경우 |
| 접근성 | axe-core (WCAG 2.2 A·AA, 한국어 설명) | 대체 텍스트, 명도 대비, 레이블, 버튼·링크 이름, lang 등 |
| 로딩 속도·검색 노출 | Lighthouse (데스크톱 기준) | 점수, 주요 지표, 검색 노출 미흡 항목 |

위치가 있는 문제(깨진 이미지·링크, 접근성, 휴대폰 밀림)는 해당 요소를 **빨간 상자로 표시한 화면 캡처**를 남긴다
(`output/shots/<작업ID>/p<페이지번호>-<n>.jpg`, 휴대폰 화면은 `p<n>-m<n>.jpg`, 발견 항목의 `screenshot`에 상대 경로).
첫 페이지는 최대 30장, 사이트 전체 검사의 나머지 페이지는 페이지당 10장.

`--pages n`을 주면 **사이트 전체 검사**: 같은 도메인 링크를 차례로 따라가 최대 n개 페이지를 검사한다.
파일 내려받기(pdf·hwp 등)·로그아웃 같은 주소, 이미 깨진 링크로 확인된 주소는 열지 않는다. 같은 링크는 사이트 전체에서 한 번만 확인한다.
로딩 속도·검색 노출은 첫 페이지만 측정한다. 리포트의 `pages[]`에 페이지마다 `summary`(심각도별 건수)가 들어간다.

외부 사이트(통계·광고 등) 파일이나 데이터 요청 실패는 심각도를 '낮음'으로 둔다.
버튼을 누르거나 입력해야 하는 기능(로그인·검색·글쓰기)은 아직 검사하지 않는다.

> axe-core는 WCAG 기준의 자동 검사 도구다. KWCAG 인증이나 법적 준수를 보장하지 않으며,
> 자동으로 판단할 수 없는 항목은 `manualCheck: true`("직접 확인 필요")로 따로 표시한다.

## 설치 (처음 한 번)

```bash
cd runner
npm install
npx playwright install chromium
```

## 사용법

```bash
# 기본 (성능·SEO 포함, 페이지당 약 10~60초)
node src/scan.js https://www.example.com

# 빠르게 (성능·SEO 제외)
node src/scan.js www.example.com --no-lighthouse

# 링크 확인 개수 조정 (기본 300개)
node src/scan.js https://www.example.com --max-links 30

# 사이트 전체 검사 (같은 사이트 링크를 따라 최대 20페이지)
node src/scan.js www.example.com --pages 20 --no-lighthouse

# 스크롤 · 위치 캡처 끄기
node src/scan.js www.example.com --no-scroll --no-shots

# 서버의 인쇄용 보고서 화면을 PDF로 (서버가 부르는 용도, 127.0.0.1만)
node src/pdf.js http://127.0.0.1:8080/scans/<작업ID>/print output/report.pdf
```

결과는 `output/scan-<작업ID>.json`에 저장되고, 터미널에는 규칙별 요약이 나온다.

종료 코드: `0` 검사 완료 · `1` 실행 오류 · `2` (첫) 페이지 접속 불가

## 1단계 완료 기준 확인

일부러 오류를 넣은 테스트 페이지(`test-pages/broken.html`)를 검사해, 넣은 오류는 모두 잡고 정상 항목은 잡지 않는지 확인한다.

```bash
npm run verify                     # 성능·SEO 포함
node test/verify.js --no-lighthouse  # 빠른 확인
```

테스트 페이지를 브라우저로 직접 보려면 `npm run serve:test` 후 http://127.0.0.1:4173/broken.html

## 결과 형식 (요약)

```json
{
  "schemaVersion": "1.0",
  "jobId": "J-20261116-141510-2606",
  "pages": [{ "pageUrl": "...", "httpStatus": 200, "scores": { "performance": 100, "seo": 82 } }],
  "summary": { "total": 10, "manualCheck": 0, "bySeverity": { "CRITICAL": 4, "HIGH": 2, "MEDIUM": 3, "LOW": 1 } },
  "findings": [
    {
      "category": "ACCESSIBILITY",
      "ruleId": "image-alt",
      "source": "axe-core",
      "severity": "CRITICAL",
      "selector": "img[src$=\"main-visual.png\"]",
      "evidence": "<img src=\"img/main-visual.png\" ...>",
      "description": "이미지는 반드시 대체텍스트를 가져야 합니다.",
      "manualCheck": false,
      "fingerprint": "a3f9c1..."
    }
  ],
  "warnings": []
}
```

- `fingerprint`: 페이지·규칙·위치로 만든 값. 다음 검사에서 같은 문제인지(유지/해결/신규) 비교할 때 쓴다.
- `evidence`의 이메일·전화번호는 저장 전에 가린다.
- 검사 하나가 실패해도 나머지 결과는 저장하고, 실패 사실은 `warnings`에 남긴다.

## 폴더 구조

```
runner/
├─ src/
│  ├─ scan.js          명령줄 시작점
│  ├─ scanner.js       검사 순서 (페이지 → 접근성 → 링크 → 성능·SEO)
│  ├─ normalize.js     도구별 결과 → 표준 JSON
│  ├─ output.js        파일 저장, 터미널 요약
│  └─ checks/          page.js · a11y.js · links.js · lighthouse.js
├─ test-pages/         오류를 넣은 테스트 페이지 + 로컬 서버
├─ test/verify.js      완료 기준 확인 스크립트
└─ output/             검사 결과 (git 제외)
```

## 아직 하지 않은 것 (다음 단계)

- 여러 페이지 검사(사이트 단위) — 지금은 URL 1개
- SSRF 차단(사설 IP·localhost 검사 거부) — 3단계에서 서버와 연결할 때 추가. 지금은 내 PC에서 직접 실행하는 도구라 테스트 페이지(127.0.0.1)를 검사할 수 있게 열어 둠
- 서버 연동, AI 해석, 보고서(PDF)

## 주의

- 고객사 실제 사이트는 허가를 받은 뒤에 검사한다.
- 링크 확인은 동시에 5개씩, 링크당 최대 10초로 제한해 상대 서버에 부담을 주지 않게 했다.
