# WebOps 데모 (GitHub Pages)

실제 서버 없이 화면을 보여 주기 위한 **저장된 예시 화면**입니다. 검사는 실행되지 않습니다.

- 주소(배포 후): https://jontnkm.github.io/webops/
- 검사 대상은 일부러 오류를 넣은 테스트 페이지(`test-pages/`, 127.0.0.1:4173)뿐입니다. 고객사 사이트 결과는 넣지 않습니다.

## 들어 있는 것

| 화면 | 파일 |
|---|---|
| 첫 화면 (최근 검사 8건, 2쪽) | `site/index.html`, `site/index-2.html` |
| 점검 종류 7가지 결과 화면 + 비교용 지난 종합점검 | `site/scans/<검사번호>.html` |
| 문제 위치 캡처 | `site/shots/<검사번호>/` |
| PDF 보고서 · 접근성 보고서 · 원본 데이터(JSON) | `site/files/<검사번호>/` |

팝업, 필터, 심각도 탭, PDF 내려받기, 원본 데이터는 그대로 동작합니다.
검사 시작 · 다시 검사 · 검색 · 삭제 · 정기 검사는 누르면 "데모에서는 쓸 수 없음" 안내만 뜹니다 (`demo.js`).
왼쪽 아래에 "데모" 안내 띠가 늘 보입니다 (`demo.css`).

## 다시 만들기 (화면을 고친 뒤)

1. 테스트 페이지 서버와 WebOps 서버를 켠다 (`run-server.cmd --webops.scan.allow-private-targets=true`).
2. 데모에 넣을 검사가 DB에 있어야 한다. 검사 번호는 `build-demo.mjs`의 `SCANS` 목록. 바꾸려면 테스트 페이지를 새로 검사하고 번호를 고친다.
3. WebOps 폴더에서 `node demo/build-demo.mjs` → `demo/site`를 통째로 새로 만든다.
   - 테스트 페이지가 아닌 주소가 섞인 검사는 넣지 않고 멈춘다.

## 배포

- `.github/workflows/demo-pages.yml`이 main에 `demo/site`가 바뀌어 푸시되면 GitHub Pages에 올린다.
- 처음 한 번: GitHub 저장소 → Settings → Pages → Source를 **GitHub Actions**로.
