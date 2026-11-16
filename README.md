# AI WebOps(가칭)

웹사이트 품질 자동 검사 도구. 지금은 **URL 입력 → 검사 → 결과 화면**까지 동작한다.

```
WebOps/
├─ runner/          검사 실행기 (Node.js) - 실제 검사를 하는 엔진
├─ server/          웹 서버 (Spring Boot + Thymeleaf) - 화면과 검사 작업 관리
├─ run-server.cmd   서버 실행 스크립트
└─ .tools/jdk-17    서버용 JDK (이 폴더 안에서만 사용, git 제외)
```

## 실행

**처음 한 번** (검사 실행기 준비):

```bash
cd runner
npm install
npx playwright install chromium
```

**서버 실행** (WebOps 폴더에서):

```bash
run-server.cmd
```

처음 실행은 Maven이 라이브러리를 받느라 1~2분 걸린다. `Started WebopsServerApplication`이 보이면
브라우저에서 http://127.0.0.1:8080 접속. 종료는 터미널에서 `Ctrl+C`.

테스트 페이지(내 PC의 127.0.0.1)까지 검사하려면 내부 주소 차단을 풀고 실행한다:

```bash
run-server.cmd --webops.scan.allow-private-targets=true
```

```bash
npm run serve:test
```

(두 번째 명령은 다른 터미널에서. 그다음 화면에 `127.0.0.1:4173/broken.html` 입력)

## 화면 흐름

1. **첫 화면** `/` — 주소 입력, 성능·SEO 측정 여부 선택, 최근 검사 목록
2. **진행 화면** `/scans/{작업ID}` — 실행기가 보내는 진행 메시지("링크 확인 중")를 1.5초마다 갱신
3. **결과 화면** 같은 주소 — 요약 숫자, 심각도별 건수, 분류 탭, 규칙별 묶음(펼치면 위치와 근거 코드), JSON 받기

## 서버가 하는 일 (server/src/main/java/com/webops/server)

| 파일 | 역할 |
| --- | --- |
| `web/ScanController.java` | 화면 주소(URL)와 연결. 입력 받기, 화면 고르기 |
| `scan/UrlGuard.java` | 검사해도 되는 주소인지 확인. 내부망·localhost 차단(SSRF 방지) |
| `scan/ScanService.java` | 백그라운드에서 `node src/scan.js <URL> --job-id <ID>` 실행, 진행 메시지 수집, 결과 JSON 읽기 |
| `scan/ScanReport.java` | 실행기가 만든 표준 JSON을 그대로 담는 객체 |
| `web/ResultView.java` | 결과를 화면용으로 가공 (분류 탭, 규칙별 묶음, 한글 이름) |

화면 파일: `server/src/main/resources/templates/*.html`(Thymeleaf), 스타일: `static/css/app.css`.
HTML은 서버 재시작 없이 새로고침으로 반영되고, CSS·Java를 고치면 서버를 다시 시작한다.

## 아직 안 된 것 (다음 단계)

- **DB 저장**: 검사 기록은 메모리에만 있어서 서버를 끄면 사라진다
- **로그인·고객사 구분**
- **여러 페이지(사이트 단위) 검사**: 지금은 URL 1개
- **리다이렉트 후 주소 재확인**: 첫 주소만 내부망 여부를 확인한다. 공개 사이트가 내부 주소로 리다이렉트하는 경우는 아직 막지 못함
- **AI 해석, PDF 보고서**

## 설정 (server/src/main/resources/application.properties)

| 키 | 기본값 | 설명 |
| --- | --- | --- |
| `server.address` | 127.0.0.1 | 내 PC에서만 접속 가능 |
| `webops.runner.dir` | ../runner | 검사 실행기 위치 |
| `webops.runner.max-concurrent` | 2 | 동시에 돌릴 검사 수 |
| `webops.runner.timeout-seconds` | 180 | 검사 1건 제한 시간 |
| `webops.scan.allow-private-targets` | false | 내부 주소 검사 허용 |
