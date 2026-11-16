# AI WebOps(가칭) — Claude Code 작업 안내

웹사이트 주소를 넣으면 기능 결함·접근성·로딩 속도·검색 노출을 검사하고, 쉬운 말로 원인과 해결 방법을 보여 주는 제품.
회사(웹에이전시, 주 고객 공공기관·공기업)에 제안하기 위해 만드는 중이다. 사용자는 웹 퍼블리셔이며 서버·DB는 배우는 중이다.

- 기획서(로드맵 1~9단계): https://claude.ai/artifact/7VLzffPvHyKJnBwfa6WnUN
- 작업 인수인계(11/16): https://claude.ai/artifact/9a1QzfcNtQFfwWngPdYviJ
- GitHub: https://github.com/Jontnkm/webops (main)

## 구조

- `runner/` 검사 실행기 (Node.js, ESM). Playwright·axe-core(한국어 locale)·Lighthouse(desktop).
  - `src/scan.js` CLI → `src/scanner.js` 순서 → `src/checks/*` → `src/normalize.js`(표준 JSON, 쉬운 말 설명) → `runner/output/scan-<jobId>.json`
  - 종료 코드: 0 완료, 1 오류, 2 페이지 접속 불가
  - `test-pages/broken.html`에 일부러 넣은 오류 + `test/verify.js`로 검증 (현재 28/28 통과). 기능을 바꾸면 verify에 항목을 추가하고 통과시킬 것.
- `server/` Spring Boot 4.1 + Thymeleaf, Java 17, Jackson 3(`tools.jackson.*`).
  - `scan/ScanService` 실행기를 백그라운드로 `node src/scan.js <url> --job-id <id> --out <dir>` 실행, 진행 메시지("  - ...") 수집, 결과 JSON 읽기. 검사 기록은 **메모리에만** 있음.
  - `scan/UrlGuard` 내부망·localhost 차단(SSRF). 테스트 때만 `--webops.scan.allow-private-targets=true`.
  - `web/ResultView` 결과 화면 데이터(항목별 결과, 속도 지표 그래프 위치, 개선 방안, 지표별 관련 원인), `web/RuleGuide` 규칙별 쉬운 안내(title·action·owner·why·fix·example).
  - `templates/result.html` 좌우 분할(각각 스크롤), 심각도 탭, 카드·칩 필터, `<template>` + 겹쳐 열리는 비모달 `<dialog>` 팝업.

## 실행

```bash
cd runner && npm install && npx playwright install chromium   # 처음 한 번
run-server.cmd                                               # WebOps 폴더에서, http://127.0.0.1:8080
npm run verify                                               # 실행기 검증 (WebOps 폴더에서)
```

- JDK는 시스템에 없음. `WebOps\.tools\jdk-17`(git 제외)을 run-server.cmd가 JAVA_HOME으로 씀. 컴파일 확인: `server`에서 `JAVA_HOME=..\.tools\jdk-17` + `mvnw.cmd -q compile`.
- CSS·HTML·Java 변경은 서버 재시작 후 반영(8080 포트 프로세스 종료 → run-server.cmd).

## 이 PC의 함정

- `NoDefaultCurrentDirectoryInExePath=1`: 배치 파일에서 현재 폴더 프로그램을 이름만으로 못 부름 → 전체 경로로 호출.
- `.cmd` 파일은 영문 주석 + CRLF여야 함(`.gitattributes`로 고정). 한글 주석 넣지 말 것.
- WebOps 폴더에서 `node src/scan.js`는 실패 → `npm run scan -- <url>` 사용.

## 지켜야 할 규칙 (사용자 요청)

- 화면 문구는 **일반인도 아는 쉬운 말**. 전문 용어는 괄호로만(예: "정상 (응답 코드 200)").
- 문제 상세 라벨: '원인' · '해결 방법' · '추천 개선 방안(고치기 전 | 고친 후)'. 담당 표시는 화면에 내지 않음.
- 데이터가 없는 수치(예: 웹사이트 평균값)는 지어내지 않는다. 기준선은 Lighthouse 평가 기준이라고 밝힌다.
- 카드 크기 통일, 이상 없는 항목은 누를 수 없게. 팝업은 누른 쪽 영역 가운데, 딤 없이 그림자, 여러 개 겹침, × 로만 닫힘.
- 화면을 바꾸면 브라우저에서 1440 / 1100 / 820 / 375px로 확인하고 가로 넘침이 없는지 본다.
- 고객사 실제 사이트 검사 결과는 서비스 화면에서 직접 확인하기 전에는 근거로 쓰지 않는다. 검사 결과 파일은 git에 올리지 않는다.
- 커밋·푸시는 사용자가 요청할 때만. 커밋 메시지는 한국어.

## 다음 할 일 (순서 권장)

1. 검사 기록 DB 저장 (회사 DBMS 확인 후; 기획서 3장 테이블 설계 참고)
2. 재검사 비교 (fingerprint로 신규/유지/해결)
3. 사이트 단위(여러 페이지) 검사
4. 리다이렉트 후 내부망 주소 재확인
5. PDF 보고서 (MVP 완료 기준, 대표 시연용)
6. AI 해석, 시나리오 기능 검사(로그인·검색·글쓰기), WMS 연동
