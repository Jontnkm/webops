# AI WebOps(가칭) — Claude Code 작업 안내

웹사이트 주소를 넣으면 기능 결함·접근성·로딩 속도·검색 노출을 검사하고, 쉬운 말로 원인과 해결 방법을 보여 주는 제품.
회사(웹에이전시, 주 고객 공공기관·공기업)에 제안하기 위해 만드는 중이다. 사용자는 웹 퍼블리셔이며 서버·DB는 배우는 중이다.

- 기획서(로드맵 1~9단계): https://claude.ai/artifact/7VLzffPvHyKJnBwfa6WnUN
- 작업 인수인계(11/16): https://claude.ai/artifact/9a1QzfcNtQFfwWngPdYviJ
- 작업 기록(11/17): `docs/worklog-2026-11-17.md`
- GitHub: https://github.com/Jontnkm/webops (main)

## 구조

- `runner/` 검사 실행기 (Node.js, ESM). Playwright·axe-core(한국어 locale)·Lighthouse(desktop).
  - `src/scan.js` CLI → `src/scanner.js` 순서 → `src/checks/*` → `src/normalize.js`(표준 JSON, 쉬운 말 설명) → `runner/output/scan-<jobId>.json`
  - 페이지마다: 열기 + 끝까지 스크롤(`checks/scroll.js`) → 접근성 → 링크 → 문제 위치 캡처(`checks/shots.js`, 빨간 상자, `output/shots/<jobId>/`) → 휴대폰 화면(+캡처). Lighthouse는 첫 페이지만.
  - 검사 범위 3가지(서버 `scan/ScanScope`, DB 컬럼 없이 url·max_pages로 판단): MAIN 메인 페이지만(`--main`, 주소를 사이트 첫 화면 / 로), PAGE 입력한 현재 페이지, SITE 웹사이트 전체 점검(`--pages <상한> --concurrency <탭 수>`, 안전 상한 `webops.scan.max-pages`=300, 탭 `webops.runner.site-concurrency`=3).
  - 전체 점검 2단계: ① `discoverPages` 그림·글꼴 막고 domcontentloaded로 같은 도메인 링크를 빠르게 훑어 검사할 페이지를 모두 확정(탭 5개, 오류 응답·리다이렉트 중복 제외, 상한 초과분은 남은 주소로). 게시판 글: `templateKey`(nttId·bbsSn 등 게시물 번호 값, 숫자인 id·no·seq·page 등, 숫자 경로 칸을 뺀 주소)가 같으면 같은 모양 → `--posts latest`(기본, 처음 찾은=최신 1개) / `all`. 화면 "상세 옵션", DB `all_posts_yn`(scan_job·site). ② 확정 목록을 탭 여러 개로 검사. `--no-scroll`, `--no-shots`.
  - 진행 막대: 실행기가 `  % 단계 완료수 전체수`(page · discover · scan · lighthouse) 출력 → `ScanService`가 읽어 `ScanJob.getPercent/getStepText/isIndeterminate` → `/api/scans/{id}` → progress.html 막대. progress.html 스크립트(th:inline)에 대괄호 두 개 연속 금지(Thymeleaf 식으로 읽음).
  - 공통 영역 합치기(`normalize.mergeShared`): 여러 페이지에 같은 문제(링크·이미지·파일·데이터는 같은 주소, 접근성·휴대폰은 같은 selector+코드, 스크립트는 같은 메시지)는 1건 + `pages[]`. 페이지별 summary·점수는 합치기 전 기준(각 페이지에 반영). 서버는 `Finding.onPage/pageList/shared`로 처리.
  - `src/pdf.js` 서버의 `/scans/{id}/print` 화면을 PDF로 (127.0.0.1만).
  - 종료 코드: 0 완료, 1 오류, 2 (첫) 페이지 접속 불가
  - `test-pages/broken.html`에 일부러 넣은 오류 + `test/verify.js`로 검증 (현재 49/49 통과 = Lighthouse 제외 시 45; 게시판 글 최신 1개/모두, 진행 막대 단계, 스크롤 없는 비교 검사, 웹사이트 전체 점검(탭 3개·같은 경로 5개 제한), 안전 상한, 공통 영역 합치기 포함). 기능을 바꾸면 verify에 항목을 추가하고 통과시킬 것. verify는 4173 포트를 쓰므로 테스트 페이지 서버가 떠 있으면 먼저 끌 것.
- `server/` Spring Boot 4.1 + Thymeleaf, Java 17, Jackson 3(`tools.jackson.*`).
  - `scan/ScanService` 실행기를 백그라운드로 `node src/scan.js <url> --job-id <id> --out <dir>` 실행, 진행 메시지("  - ...") 수집, 결과 JSON 읽기. 진행 중에는 메모리, 시작·끝은 DB에 저장(`store/ScanStore`), 메모리에 없으면 DB에서 되살림. 끝나면 `ScanFinishedEvent` 발행.
  - DB: H2 파일(`WebOps\data\webops.mv.db`, git 제외), JPA `ddl-auto=update`. 테이블은 기획서 3장 이름 그대로 `site`·`scan_job`(요약 컬럼 + `report_json` 원본)·`scan_page_result`·`finding` + `alert_log`. 회사 DBMS가 정해지면 드라이버·접속 설정만 교체. 서버 재시작 시 진행 중이던 검사는 FAILED로 정리.
  - 재검사 비교: 같은 url·같은 max_pages의 직전 DONE 검사와 finding.fingerprint로 새로 생김/해결됨/그대로 (`ScanStore.compare` → `scan/Comparison`). 직접 확인 필요 항목은 비교 제외.
  - 정기 검사: `site/SiteService`(등록·수정·지금 검사·`runDue`), `site/SiteScheduler`(30초마다 next_run_at 지난 사이트 검사, DAILY/WEEKLY + 시각), `site/TrendView`(추이 그래프 좌표 0~100%, 선은 SVG non-scaling-stroke, 점·글자는 HTML).
  - 알림: `alert/AlertService`가 ScanFinishedEvent를 받아 사이트 검사만 판단(SITE_DOWN·RECOVERED·NEW_BROKEN_LINK·SCAN_FAILED), `spring.mail.host` 없으면 보내지 않고 alert_log에 SKIPPED. 메일 테스트는 가짜 SMTP(127.0.0.1:2525)로 했음.
  - `scan/UrlGuard` 내부망·localhost 차단(SSRF). 테스트 때만 `--webops.scan.allow-private-targets=true`. 정기 검사는 검사 직전에 다시 확인.
  - `web/ResultView` 결과 화면 데이터(항목별 결과, 속도 지표 그래프 위치, 개선 방안, 지표별 관련 원인, 페이지별 점수표, 비교), `web/RuleGuide` 규칙별 쉬운 안내(title·action·owner·why·fix·example).
  - `web/A11yGuideline` axe 규칙 → KWCAG 2.2 검사항목(번호·이름·원칙) + WCAG 2.2 성공 기준·등급. 팝업·목록 칩·PDF에 표시, "인증 심사 판정과 다를 수 있음" 명시. 새 axe 규칙이 보이면 여기에 추가.
  - Thymeleaf 주의: 같은 태그에서 `th:if`가 `th:with`보다 먼저 처리됨 → th:if에서 th:with 변수를 쓰지 말 것.
  - 품질 점수는 WebOps 자체 계산식(`scan/QualityScore`: 페이지마다 문제 종류별 심각20·높음10·보통4·낮음1 감점, 검사 점수는 페이지 평균·안 열린 페이지 0점)이며 화면·PDF에 계산 방법을 밝힌다.
  - 화면 경로: `/sites`(목록·등록), `/sites/{id}`(추이·검사 기록·알림 기록·설정), `/sites/{id}/run`·`/toggle`·`/delete`.
  - `templates/result.html` 좌우 분할(각각 스크롤), 심각도 탭, 카드·칩 필터, `<template>` + 겹쳐 열리는 비모달 `<dialog>` 팝업, 팝업 안 위치 캡처.
  - `templates/report.html` + `static/css/report.css` 인쇄용(A4) 보고서. `/scans/{id}/report.pdf`가 실행기 `pdf.js`로 PDF 생성(`output/report-<id>.pdf`에 한 번 만들면 재사용).
  - 화면 경로: `/scans/{id}/shots/{name}.jpg` 캡처, `/scans/{id}/print` 인쇄용, `/scans/{id}/report.pdf`.

## 실행

```bash
cd runner && npm install && npx playwright install chromium   # 처음 한 번
run-server.cmd                                               # WebOps 폴더에서, http://127.0.0.1:8080
npm run verify                                               # 실행기 검증 (WebOps 폴더에서)
```

- JDK는 시스템에 없음. `WebOps\.tools\jdk-17`(git 제외)을 run-server.cmd가 JAVA_HOME으로 씀. 컴파일 확인: `server`에서 `JAVA_HOME=..\.tools\jdk-17` + `mvnw.cmd -q compile`.
- CSS·HTML·Java 변경은 서버 재시작 후 반영(8080 포트 프로세스 종료 → run-server.cmd).
- DB 보기: http://127.0.0.1:8080/h2-console (JDBC URL `jdbc:h2:file:../data/webops;MODE=LEGACY`, 사용자 `sa`, 비밀번호 없음). 개발용, 운영에서는 `spring.h2.console.enabled=false`.
- H2 파일 DB는 서버가 켜져 있는 동안 잠김 → 그동안 `mvnw test`(컨텍스트 로딩 테스트)는 DB를 못 열어 실패할 수 있음.
- Git Bash에서 curl로 한글 폼 값을 보내면 깨져 400이 남 → 한글 값 테스트는 Node fetch나 브라우저로.

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

1. 회사 DBMS 확정 후 DB 교체 + 운영용 DDL 관리(ddl-auto=validate), 회사 메일 서버(SMTP) 설정
2. 리다이렉트 후 내부망 주소 재확인 (사이트 전체 검사로 여는 페이지가 늘어 더 중요해짐)
3. 로그인·고객사 구분(기획서 tenant·app_user), 오래된 검사 기록·캡처 정리 정책
4. AI 해석, 시나리오 기능 검사(로그인·검색·글쓰기), WMS 연동

완료(11/17): 문제 위치 화면 캡처, 사이트 전체 검사(최대 20페이지 + 페이지별 점수표), PDF 보고서, 스크롤 후 검사,
검사 기록 DB 저장(H2), 재검사 비교, 정기 검사 + 품질 추이 그래프, 문제 발생 알림 메일.
