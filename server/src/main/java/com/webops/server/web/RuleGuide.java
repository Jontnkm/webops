package com.webops.server.web;

import java.util.HashMap;
import java.util.Map;

/**
 * 검사 규칙별 안내문 (일반 사용자도 이해할 수 있는 말).
 * - title:   목록 제목 (무엇이 문제인지)
 * - action:  개선 방안 목록에 쓸 할 일 (무엇을 하면 되는지)
 * - owner:   주로 누가 고치는지 (퍼블리싱 / 개발 / 서버 담당 / 콘텐츠 담당)
 * - why:     왜 문제인가요
 * - fix:     어떻게 고치나요
 * - example: 고치기 전 → 고친 후 예시 (없으면 null)
 * 여기 없는 규칙은 ResultView에서 기본 문구로 대신한다.
 */
public final class RuleGuide {

    public static final String PUB = "퍼블리싱";
    public static final String DEV = "개발";
    public static final String SERVER = "서버 담당";
    public static final String CONTENT = "콘텐츠 담당";

    public record Guide(String title, String action, String owner, String why, String fix, String example) {
    }

    private static final Map<String, Guide> GUIDES = new HashMap<>();

    private static void put(String ruleId, String title, String action, String owner, String why, String fix, String example) {
        GUIDES.put(ruleId, new Guide(title, action, owner, why, fix, example));
    }

    static {
        // ===================== 접속 =====================
        put("page-unreachable", "페이지에 접속할 수 없음", "페이지 접속 복구", SERVER,
                "방문자가 사이트를 전혀 볼 수 없습니다.",
                "주소가 맞는지, 서버·도메인·보안 인증서가 정상인지 확인해 주세요.", null);
        put("page-http-error", "페이지가 열리지 않음", "오류 응답 원인 해결", SERVER,
                "주소는 있지만 서버가 오류를 돌려줘 방문자에게 오류 화면이 보입니다.",
                "서버 로그에서 오류 원인을 확인하고, 페이지가 옮겨졌다면 새 주소로 연결(리다이렉트)해 주세요.", null);

        // ===================== 이미지·링크 =====================
        put("image-broken", "엑스박스로 보이는 이미지", "깨진 이미지 경로 고치기", PUB,
                "이미지 자리에 빈칸(엑스박스)이 보여 관리되지 않는 사이트라는 인상을 줍니다.",
                "아래 이미지 주소의 파일이 서버에 있는지, 경로·대소문자가 맞는지 확인해 주세요. 필요 없는 이미지면 태그를 지워 주세요.", null);
        put("image-not-rendered", "그림이 아닌 이미지 태그 (통계 수집용일 수 있음)", "이미지 태그 용도 확인", DEV,
                "서버 응답은 정상이지만 그림이 아닙니다.",
                "방문 통계 수집용이면 그대로 둬도 됩니다. 아니라면 이미지 주소를 확인해 주세요.", null);
        put("link-broken", "깨진 링크", "깨진 링크 고치기", PUB,
                "누르면 없는 페이지나 오류 화면으로 이동합니다.",
                "링크 주소를 올바른 주소로 바꾸거나, 없어진 페이지라면 링크를 지워 주세요. 외부 사이트라면 그 사이트의 새 주소를 확인해 주세요.", null);
        put("link-restricted", "자동 확인이 막힌 링크", "링크 직접 눌러 확인", PUB,
                "상대 사이트가 자동 접속을 막아서 열리는지 확인하지 못했습니다.",
                "브라우저에서 직접 눌러 열리는지 확인해 주세요.", null);

        // ===================== 기능 결함 =====================
        put("resource-failed", "화면에 필요한 파일을 불러오지 못함", "빠진 CSS·JS 파일 복구", PUB,
                "디자인 파일(CSS)이 없으면 화면 모양이, 기능 파일(JS)이 없으면 메뉴·슬라이드·버튼이 깨질 수 있습니다.",
                "아래 파일이 서버에 있는지, 경로가 맞는지 확인해 주세요. 배포할 때 빠진 파일일 가능성이 큽니다. 쓰지 않는 파일이면 HTML에서 불러오는 코드를 지워 주세요.", null);
        put("api-failed", "화면에 보여 줄 데이터를 불러오지 못함", "데이터 요청 오류 해결", DEV,
                "게시판 목록·검색 결과·팝업처럼 페이지가 따로 불러오는 데이터가 실패해, 해당 영역이 비어 보이거나 동작하지 않을 수 있습니다.",
                "아래 주소의 서버 처리(컨트롤러·쿼리)에서 오류가 나는지 서버 로그를 확인해 주세요. '서버 오류(500)'면 서버 코드, '없는 주소(404)'면 요청 주소 문제입니다.", null);
        put("js-error", "화면 동작 중 스크립트 오류", "스크립트 오류 고치기", DEV,
                "메뉴·탭·슬라이드 같은 동작이 멈출 수 있습니다.",
                "아래 오류 메시지를 브라우저 개발자 도구(F12)의 콘솔에서 확인하고 해당 스크립트를 고쳐 주세요. 라이브러리(jQuery 등) 불러오는 순서가 바뀐 경우가 흔합니다.", null);
        put("console-error", "브라우저가 남긴 오류 메시지", "콘솔 오류 원인 확인", DEV,
                "당장 눈에 띄지 않아도 일부 기능이 안 될 수 있습니다.",
                "메시지 내용으로 원인을 확인해 주세요. 통계·광고 같은 외부 서비스가 남긴 메시지라면 무시해도 되는지 판단해 주세요.", null);
        put("mobile-horizontal-scroll", "휴대폰에서 화면이 옆으로 밀림", "휴대폰 화면 너비 맞추기", PUB,
                "휴대폰 화면보다 넓은 요소가 있어 좌우로 흔들리거나 오른쪽 내용이 잘립니다.",
                "표시된 요소에 고정 너비가 있는지 확인하고 max-width: 100%로 바꿔 주세요. 표는 감싸는 영역에 overflow-x: auto를 주면 됩니다.",
                ".banner { width: 800px; }\n→ .banner { width: 100%; max-width: 800px; }");
        put("mixed-content", "보안 연결 페이지에서 보안이 안 된 파일을 부름", "http 주소를 https로 바꾸기", PUB,
                "https 페이지가 http 주소의 파일을 부르면 브라우저가 막거나 '안전하지 않음' 경고를 띄울 수 있습니다.",
                "아래 파일 주소의 http://를 https://로 바꿔 주세요. 같은 사이트 파일이면 /img/a.png처럼 경로만 써도 됩니다.",
                "<script src=\"http://example.com/ui.js\">\n→ <script src=\"https://example.com/ui.js\">");

        // ===================== 접근성 =====================
        put("image-alt", "이미지에 설명 글(대체 텍스트)이 없음", "이미지에 설명 글(alt) 넣기", PUB,
                "화면을 볼 수 없는 사용자는 화면 낭독 프로그램으로 내용을 듣습니다. 설명 글이 없으면 무슨 이미지인지 알 수 없습니다.",
                "이미지가 전달하는 내용을 alt에 적어 주세요. 꾸밈·여백용 이미지면 alt=\"\"로 비워 두면 화면 낭독 프로그램이 건너뜁니다.",
                "<img src=\"banner.jpg\">\n→ <img src=\"banner.jpg\" alt=\"봄맞이 체육 강좌 접수 안내\">");
        put("input-image-alt", "이미지 버튼에 설명 글이 없음", "이미지 버튼에 alt 넣기", PUB,
                "이미지로 만든 버튼이 무슨 기능인지 화면 낭독 프로그램이 알려 주지 못합니다.",
                "버튼 기능을 alt에 적어 주세요.",
                "<input type=\"image\" src=\"search.png\">\n→ <input type=\"image\" src=\"search.png\" alt=\"검색\">");
        put("role-img-alt", "그림 역할 요소에 설명이 없음", "그림 요소에 설명 넣기", PUB,
                "그림으로 쓰인 요소에 설명이 없어 화면 낭독 프로그램이 내용을 알려 주지 못합니다.",
                "aria-label로 설명을 넣어 주세요.", "<span role=\"img\">\n→ <span role=\"img\" aria-label=\"맑음\">");
        put("svg-img-alt", "SVG 그림에 설명이 없음", "SVG에 설명 넣기", PUB,
                "그림으로 쓰인 SVG에 설명이 없어 화면 낭독 프로그램이 내용을 알려 주지 못합니다.",
                "svg에 aria-label이나 title을 넣어 주세요.", "<svg role=\"img\">\n→ <svg role=\"img\" aria-label=\"전화\">");
        put("color-contrast", "글자와 배경색 차이가 작아 읽기 어려움", "글자색 대비 높이기", PUB,
                "저시력·고령 사용자나 밝은 곳에서는 글자가 잘 보이지 않습니다.",
                "글자색을 더 진하게 하거나 배경을 바꿔 명도 대비 4.5:1 이상(큰 글자는 3:1)으로 맞춰 주세요.",
                "color: #999999;  (흰 배경 대비 2.8:1)\n→ color: #767676;  (4.5:1)");
        put("link-in-text-block", "본문 속 링크가 색으로만 구분됨", "본문 링크에 밑줄 넣기", PUB,
                "색을 구분하기 어려운 사용자는 링크인지 알 수 없습니다.",
                "밑줄 등 색 외의 표시를 함께 써 주세요.", "a { text-decoration: none; }\n→ a { text-decoration: underline; }");
        put("html-has-lang", "페이지 언어가 지정되지 않음", "html에 lang=\"ko\" 넣기", PUB,
                "화면 낭독 프로그램이 한국어를 다른 언어 발음으로 읽을 수 있습니다.",
                "html 태그에 언어를 지정해 주세요.", "<html>\n→ <html lang=\"ko\">");
        put("html-lang-valid", "페이지 언어 값이 잘못됨", "lang 값 바로잡기", PUB,
                "lang 값이 올바르지 않아 화면 낭독 프로그램이 언어를 알 수 없습니다.",
                "lang=\"ko\"처럼 표준 언어 코드를 써 주세요.", "<html lang=\"kr\">\n→ <html lang=\"ko\">");
        put("document-title", "페이지 제목(title)이 없음", "페이지 제목 넣기", PUB,
                "브라우저 탭·검색 결과·화면 낭독 프로그램에서 어떤 페이지인지 알 수 없습니다.",
                "'페이지명 | 기관명' 형식으로 title을 넣어 주세요.", "<title></title>\n→ <title>공지사항 | 기관명</title>");
        put("button-name", "이름 없는 버튼", "버튼에 이름 넣기", PUB,
                "아이콘만 있는 버튼은 화면 낭독 프로그램에서 '버튼'으로만 읽혀 무슨 기능인지 알 수 없습니다.",
                "버튼 안에 글자를 넣거나 aria-label로 기능을 적어 주세요.",
                "<button class=\"btn-close\"><i class=\"ico\"></i></button>\n→ <button class=\"btn-close\" aria-label=\"닫기\"><i class=\"ico\"></i></button>");
        put("link-name", "이름 없는 링크", "링크에 이름 넣기", PUB,
                "이미지나 아이콘만 있는 링크는 어디로 가는지 알려 주지 못합니다.",
                "링크 안 이미지에 alt를 넣거나, 링크에 aria-label을 넣어 주세요.",
                "<a href=\"/blog\"><img src=\"blog.png\"></a>\n→ <a href=\"/blog\"><img src=\"blog.png\" alt=\"공식 블로그\"></a>");
        put("label", "입력칸에 이름표(레이블)가 없음", "입력칸에 label 연결", PUB,
                "어떤 내용을 입력하는 칸인지 화면 낭독 프로그램이 알려 주지 못합니다.",
                "label의 for와 입력칸의 id를 같게 연결해 주세요. 디자인상 글자를 숨겨야 하면 숨김 클래스(sr-only)를 쓰세요.",
                "<input type=\"text\" id=\"keyword\">\n→ <label for=\"keyword\">검색어</label> <input type=\"text\" id=\"keyword\">");
        put("select-name", "선택 상자에 이름표가 없음", "선택 상자에 label 연결", PUB,
                "무엇을 고르는 상자인지 화면 낭독 프로그램이 알려 주지 못합니다.",
                "label을 연결하거나 title을 넣어 주세요.",
                "<select id=\"year\">…</select>\n→ <label for=\"year\">연도 선택</label> <select id=\"year\">…</select>");
        put("frame-title", "iframe에 제목이 없음", "iframe에 title 넣기", PUB,
                "페이지 안에 들어간 다른 화면(지도·영상 등)이 무엇인지 알려 주지 못합니다.",
                "iframe에 내용을 설명하는 title을 넣어 주세요.",
                "<iframe src=\"map.html\">\n→ <iframe src=\"map.html\" title=\"찾아오시는 길 지도\">");
        put("aria-hidden-focus", "숨겨진 영역 안에 키보드로 이동되는 요소가 있음", "숨긴 영역의 키보드 이동 막기", PUB,
                "화면 낭독 프로그램에는 숨겨져 있는데 키보드 Tab으로는 이동돼서, 사용자가 지금 어디에 있는지 알 수 없습니다.",
                "aria-hidden=\"true\"로 숨긴 영역(닫힌 메뉴, 슬라이드의 안 보이는 장 등) 안의 링크·버튼에 tabindex=\"-1\"을 주거나, 영역에 inert를 주세요. 슬라이드라면 장이 바뀔 때 함께 바꿔야 합니다.",
                "<li aria-hidden=\"true\"><a href=\"/a\">…</a></li>\n→ <li aria-hidden=\"true\" inert><a href=\"/a\">…</a></li>");
        put("list", "목록(ul·ol) 안에 목록 항목이 아닌 요소가 있음", "ul·ol 안에는 li만 두기", PUB,
                "ul·ol 바로 안에는 li만 와야 화면 낭독 프로그램이 '목록, 항목 5개'처럼 정확히 읽어 줍니다.",
                "ul·ol 바로 아래의 div·a 등을 li 안으로 옮겨 주세요.",
                "<ul><div><li>…</li></div></ul>\n→ <ul><li><div>…</div></li></ul>");
        put("listitem", "li가 목록(ul·ol) 밖에 있음", "li를 ul·ol 안으로 옮기기", PUB,
                "li는 ul이나 ol 안에 있어야 목록으로 인식됩니다.",
                "li를 감싸는 요소를 ul로 바꾸거나, li를 다른 태그로 바꿔 주세요.",
                "<div class=\"list\"><li>…</li></div>\n→ <ul class=\"list\"><li>…</li></ul>");
        put("nested-interactive", "누르는 요소 안에 또 누르는 요소가 있음", "겹친 링크·버튼 분리", PUB,
                "링크 안에 버튼이 들어 있는 것처럼 겹쳐 있으면 키보드·화면 낭독 프로그램 사용자가 안쪽 요소를 쓸 수 없습니다.",
                "둘 중 하나만 남기거나 나란히 배치해 주세요.",
                "<a href=\"/view\"><button>자세히</button></a>\n→ <a href=\"/view\" class=\"btn\">자세히</a>");
        put("target-size", "누르는 영역이 너무 작음", "버튼·링크 크기 키우기", PUB,
                "버튼·링크가 24px보다 작고 서로 붙어 있으면 손가락이 굵거나 손떨림이 있는 사용자가 잘못 누르기 쉽습니다.",
                "누르는 영역을 24×24px 이상으로 하거나 주변과 간격을 넉넉히 두세요. padding으로 영역만 넓혀도 됩니다.",
                ".pager a { padding: 0; }\n→ .pager a { display: inline-block; min-width: 24px; min-height: 24px; }");
        put("meta-viewport", "휴대폰에서 확대를 막아 둠", "확대 막는 설정 지우기", PUB,
                "저시력 사용자가 화면을 손가락으로 확대할 수 없습니다.",
                "viewport에서 user-scalable=no, maximum-scale=1을 빼 주세요.",
                "<meta name=\"viewport\" content=\"width=device-width, user-scalable=no\">\n→ <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        put("duplicate-id-aria", "같은 id가 여러 번 쓰임", "겹친 id 바꾸기", PUB,
                "레이블·설명 연결에 쓰는 id가 겹치면 화면 낭독 프로그램이 엉뚱한 설명을 읽을 수 있습니다.",
                "한 페이지 안에서 id가 하나씩만 쓰이도록 바꿔 주세요.", null);
        put("aria-required-children", "ARIA 구조가 맞지 않음 (필요한 하위 요소 없음)", "ARIA 역할 구조 바로잡기", PUB,
                "탭·메뉴 같은 역할(role)에 꼭 있어야 하는 하위 요소가 없어 화면 낭독 프로그램이 제대로 읽지 못합니다.",
                "role=\"tablist\" 안에는 role=\"tab\"이 있어야 하는 식으로 짝을 맞춰 주세요. 잘 모르겠으면 role을 빼는 편이 낫습니다.", null);
        put("aria-required-parent", "ARIA 구조가 맞지 않음 (필요한 상위 요소 없음)", "ARIA 역할 구조 바로잡기", PUB,
                "탭·메뉴 항목이 있어야 할 묶음 밖에 있어 화면 낭독 프로그램이 제대로 읽지 못합니다.",
                "role=\"tab\"은 role=\"tablist\" 안에 두는 식으로 짝을 맞춰 주세요.", null);
        put("aria-valid-attr-value", "ARIA 속성 값이 잘못됨", "ARIA 속성 값 바로잡기", PUB,
                "aria-* 속성 값이 틀려서 화면 낭독 프로그램이 잘못된 정보를 전달할 수 있습니다.",
                "aria-controls·aria-labelledby가 실제로 있는 id를 가리키는지, true/false 값이 맞는지 확인해 주세요.", null);
        put("aria-allowed-attr", "이 요소에 쓸 수 없는 ARIA 속성이 있음", "맞지 않는 ARIA 속성 지우기", PUB,
                "요소에 맞지 않는 aria-* 속성은 무시되거나 혼란을 줍니다.", "해당 속성을 지워 주세요.", null);
        put("scrollable-region-focusable", "스크롤 영역을 키보드로 움직일 수 없음", "스크롤 영역에 tabindex 넣기", PUB,
                "마우스를 쓰지 못하는 사용자가 안쪽 내용을 볼 수 없습니다.",
                "스크롤되는 영역에 tabindex=\"0\"을 넣어 주세요.",
                "<div class=\"scroll-box\">\n→ <div class=\"scroll-box\" tabindex=\"0\">");
        put("td-headers-attr", "표의 제목 칸 연결이 잘못됨", "표 제목 칸 연결 바로잡기", PUB,
                "표의 칸과 제목 칸 연결이 틀려 화면 낭독 프로그램이 칸의 의미를 잘못 읽습니다.",
                "headers 속성이 같은 표 안 th의 id를 가리키도록 고쳐 주세요.", null);
        put("th-has-data-cells", "내용과 연결되지 않은 표 제목 칸", "표 제목 칸 정리", PUB,
                "제목 칸(th)이 실제 내용 칸과 연결되지 않아 표를 이해하기 어렵습니다.",
                "빈 열·행의 th를 지우거나 td로 바꿔 주세요.", null);
        put("autocomplete-valid", "자동 완성 설정 값이 잘못됨", "자동 완성 값 바로잡기", PUB,
                "이름·전화번호 등 입력칸의 자동 완성이 동작하지 않습니다.",
                "autocomplete에 name, tel, email 같은 표준 값을 써 주세요.",
                "<input autocomplete=\"phone\">\n→ <input autocomplete=\"tel\">");
        put("bypass", "본문 바로가기가 없음", "본문 바로가기 링크 넣기", PUB,
                "키보드 사용자가 매 페이지마다 메뉴를 전부 지나가야 본문에 닿습니다.",
                "body 맨 앞에 본문으로 이동하는 링크를 넣어 주세요.",
                "<body>\n→ <body><a href=\"#content\" class=\"skip\">본문 바로가기</a>");

        // ===================== 로딩 속도 =====================
        put("cls-culprits-insight", "로딩 중 화면이 갑자기 밀림", "늦게 뜨는 요소의 자리 미리 잡기", PUB,
                "이미지·배너·팝업이 늦게 자리를 잡으면서 이미 보이던 내용이 밀립니다. 읽던 곳을 놓치거나 엉뚱한 것을 누르게 됩니다.",
                "배너·이미지·띠배너 영역에 width·height(또는 aspect-ratio, min-height)를 미리 지정해 주세요. 늦게 뜨는 공지 띠배너를 내용 위에 끼워 넣지 말고 자리를 확보해 두세요.",
                ".main-visual { }\n→ .main-visual { aspect-ratio: 16 / 6; }");
        put("layout-shifts", "로딩 중 화면이 갑자기 밀림", "늦게 뜨는 요소의 자리 미리 잡기", PUB,
                "늦게 뜨는 요소 때문에 이미 보이던 내용이 밀립니다.",
                "이미지·배너에 크기를 미리 지정해 주세요.", null);
        put("unsized-images", "이미지 크기(width·height)가 지정되지 않음", "img에 width·height 넣기", PUB,
                "이미지가 뜨기 전에는 자리가 비어 있다가, 뜨는 순간 아래 내용이 밀립니다.",
                "img에 원본 비율대로 width·height를 넣어 주세요. CSS로 크기를 바꿔도 비율은 유지됩니다.",
                "<img src=\"a.jpg\">\n→ <img src=\"a.jpg\" width=\"600\" height=\"400\">");
        put("render-blocking-insight", "화면 표시를 늦추는 파일", "화면을 막는 파일 나중에 불러오기", PUB,
                "CSS·JS 파일을 다 받을 때까지 화면이 하얗게 비어 있습니다.",
                "당장 필요 없는 JS에는 defer를 붙이고, CSS는 첫 화면에 꼭 필요한 것만 head에 두세요.",
                "<script src=\"ui.js\"></script>\n→ <script src=\"ui.js\" defer></script>");
        put("image-delivery-insight", "이미지 용량이 큼", "이미지 용량 줄이기", PUB,
                "보이는 크기보다 큰 이미지나 압축이 덜 된 이미지 때문에 로딩이 느립니다.",
                "실제 보이는 크기에 맞게 이미지를 줄이고, WebP로 바꾸거나 압축해 주세요. 아래 파일별로 줄일 수 있는 용량이 나옵니다.", null);
        put("cache-insight", "브라우저 저장(캐시) 기간이 짧음", "정적 파일 캐시 기간 늘리기", SERVER,
                "다시 방문해도 같은 파일을 매번 새로 받습니다.",
                "웹 서버 설정에서 이미지·CSS·JS의 캐시 기간(Cache-Control max-age)을 길게(예: 30일) 지정해 주세요.",
                "Cache-Control: no-cache\n→ Cache-Control: public, max-age=2592000");
        put("lcp-discovery-insight", "가장 큰 화면 요소를 늦게 찾음", "메인 이미지 미리 불러오기", PUB,
                "메인 배너처럼 가장 큰 요소를 브라우저가 늦게 발견해 화면이 늦게 완성됩니다.",
                "메인 배너 이미지를 CSS 배경 대신 img로 넣거나 preload로 미리 불러오고, loading=\"lazy\"는 빼 주세요.",
                "<link rel=\"preload\" as=\"image\" href=\"main_visual.jpg\">");
        put("lcp-phases-insight", "가장 큰 화면 요소가 늦게 뜸", "메인 영역 표시 시간 줄이기", DEV,
                "메인 배너 같은 가장 큰 요소가 나타나기까지 오래 걸립니다.",
                "아래 내용에서 어느 단계(서버 응답·다운로드·그리기)가 늦은지 보고 해당 부분을 줄여 주세요.", null);
        put("document-latency-insight", "서버의 첫 응답이 늦음", "서버 첫 응답 시간 줄이기", SERVER,
                "페이지를 요청한 뒤 서버가 응답하기까지 오래 걸립니다.",
                "메인 페이지의 서버 처리(쿼리·외부 연동) 시간과 불필요한 리다이렉트를 확인해 주세요.", null);
        put("font-display-insight", "글꼴을 받는 동안 글자가 안 보임", "글꼴 로딩 중에도 글자 보이게", PUB,
                "웹 글꼴을 다 받을 때까지 글자가 숨겨집니다.",
                "@font-face에 font-display: swap을 넣어 주세요.",
                "@font-face { font-family: X; src: url(x.woff2); }\n→ @font-face { font-family: X; src: url(x.woff2); font-display: swap; }");
        put("third-parties-insight", "외부 서비스 파일이 무거움", "외부 서비스 스크립트 정리", DEV,
                "통계·광고·SNS 같은 외부 서비스 파일이 로딩을 늦춥니다.",
                "꼭 필요한 것만 남기고, 남는 것은 defer·async로 나중에 불러오세요.", null);
        put("network-dependency-tree-insight", "파일을 줄줄이 차례로 불러옴", "중요한 파일 먼저 불러오기", PUB,
                "한 파일을 받아야 다음 파일을 요청하는 구조라 기다림이 길어집니다.",
                "CSS 안의 @import를 없애고, 중요한 파일은 HTML에서 바로 불러오세요.",
                "@import url(\"sub.css\");\n→ <link rel=\"stylesheet\" href=\"sub.css\">");
        put("dom-size-insight", "페이지 구성 요소가 너무 많음", "HTML 요소 수 줄이기", PUB,
                "HTML 요소 수가 많아 화면을 그리고 반응하는 데 오래 걸립니다.",
                "숨겨 둔 전체 메뉴·탭 내용 등 처음에 안 보이는 부분을 줄이거나 필요할 때 불러오세요.", null);
        put("forced-reflow-insight", "스크립트가 화면 배치를 반복 계산", "화면 배치 반복 계산 줄이기", DEV,
                "스크립트가 요소 크기를 읽고 바꾸기를 반복해 화면이 버벅입니다.",
                "크기 읽기와 바꾸기를 한데 모아 처리해 주세요.", null);
        put("duplicated-javascript-insight", "같은 스크립트를 중복으로 불러옴", "중복 스크립트 정리", DEV,
                "같은 라이브러리(jQuery 등)를 여러 번 불러와 용량이 늘어납니다.",
                "중복으로 불러오는 스크립트 태그를 하나만 남겨 주세요.", null);
        put("legacy-javascript-insight", "오래된 브라우저용 코드가 섞인 스크립트", "스크립트 빌드 설정 정리", DEV,
                "최신 브라우저에는 필요 없는 변환 코드가 들어 있어 용량이 늘어납니다.",
                "빌드 도구의 대상 브라우저 설정을 최신 기준으로 바꿔 주세요.", null);
        put("modern-http-insight", "오래된 전송 방식 사용", "서버 HTTP/2 켜기", SERVER,
                "서버가 HTTP/1.1로 파일을 보내 여러 파일을 동시에 받는 속도가 느립니다.",
                "웹 서버에서 HTTP/2 이상을 켜 주세요.", null);
        put("viewport-insight", "휴대폰 화면 설정(viewport) 문제", "viewport 설정 넣기", PUB,
                "viewport 설정이 없거나 잘못돼 휴대폰에서 화면이 작게 보이거나 터치 반응이 늦습니다.",
                "head에 viewport 메타 태그를 넣어 주세요.",
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">");
        put("unused-javascript", "쓰지 않는 스크립트", "쓰지 않는 JS 정리", PUB,
                "페이지에서 쓰지 않는 스크립트까지 받느라 로딩이 느립니다.",
                "이 페이지에서 쓰지 않는 플러그인·라이브러리를 빼 주세요.", null);
        put("unused-css-rules", "쓰지 않는 CSS", "쓰지 않는 CSS 정리", PUB,
                "페이지에서 쓰지 않는 스타일까지 받느라 로딩이 느립니다.",
                "공통 CSS에서 오래된 스타일을 정리해 주세요.", null);
        put("bootup-time", "스크립트 실행이 오래 걸림", "무거운 스크립트 줄이기", DEV,
                "스크립트를 해석·실행하는 시간이 길어 화면이 늦게 반응합니다.",
                "아래 실행 시간이 긴 스크립트부터 필요 여부를 확인해 주세요.", null);
        put("mainthread-work-breakdown", "브라우저가 처리할 일이 너무 많음", "브라우저 작업량 줄이기", DEV,
                "스크립트·화면 계산이 몰려 페이지가 늦게 반응합니다.",
                "무거운 스크립트를 줄이거나 나중에 실행되도록 해 주세요.", null);
        put("total-byte-weight", "페이지 전체 용량이 큼", "페이지 용량 줄이기", PUB,
                "한 페이지를 열 때 받는 파일 용량이 커서 모바일 데이터 사용자는 특히 느리게 느낍니다.",
                "용량이 큰 이미지·동영상·스크립트부터 줄여 주세요.", null);

        // ===================== 검색 노출 =====================
        put("meta-description", "검색 결과에 나올 페이지 설명이 없음", "페이지 설명(meta description) 넣기", CONTENT,
                "네이버·구글 검색 결과에 페이지 설명이 엉뚱하게 나올 수 있습니다.",
                "페이지 내용을 80~120자로 요약해 meta description에 넣어 주세요.",
                "<meta name=\"description\" content=\"체육 강좌 신청, 시설 대관 등 기관 서비스를 안내합니다.\">");
        put("link-text", "링크 문구가 모호함 ('더보기', '여기' 등)", "모호한 링크 문구 바꾸기", CONTENT,
                "검색엔진과 화면 낭독 프로그램 사용자가 링크가 어디로 가는지 알 수 없습니다.",
                "링크 문구에 목적지를 넣어 주세요. 디자인상 '더보기'만 보여야 하면 숨김 글자를 덧붙이세요.",
                "<a href=\"/notice\">더보기</a>\n→ <a href=\"/notice\">공지사항 더보기</a>");
        put("crawlable-anchors", "검색엔진이 따라갈 수 없는 링크", "href 있는 링크로 바꾸기", DEV,
                "href 없이 스크립트로만 이동하는 링크는 검색엔진이 따라가지 못합니다.",
                "이동할 주소를 href에 적어 주세요.", "<a href=\"javascript:goPage(3)\">\n→ <a href=\"/list?page=3\">");
        put("is-crawlable", "검색엔진 수집이 막혀 있음", "검색엔진 수집 차단 해제 확인", SERVER,
                "이 페이지가 네이버·구글 검색 결과에 나오지 않도록 설정되어 있습니다.",
                "robots.txt의 Disallow, <meta name=\"robots\" content=\"noindex\">, 응답 헤더 X-Robots-Tag 중 무엇이 막고 있는지 확인해 주세요. 의도한 차단이 아니라면 지워 주세요.",
                "<meta name=\"robots\" content=\"noindex\">\n→ (삭제)");
        put("http-status-code", "검색엔진이 오류 페이지로 인식함", "오류 응답 고치기", SERVER,
                "페이지가 오류 응답을 돌려줘 검색 결과에서 빠질 수 있습니다.",
                "페이지가 정상 응답(200)을 돌려주도록 서버를 확인해 주세요.", null);
        put("canonical", "대표 주소 설정이 잘못됨", "대표 주소(canonical) 바로잡기", PUB,
                "같은 내용의 여러 주소 중 대표 주소 설정이 틀려 검색 노출에 불리합니다.",
                "canonical이 실제 접속 가능한 대표 주소를 가리키게 해 주세요.", null);
        put("robots-txt", "검색엔진 안내 파일(robots.txt)에 오류가 있음", "robots.txt 문법 고치기", SERVER,
                "검색엔진이 사이트를 수집할 때 참고하는 파일에 문법 오류가 있어 의도와 다르게 수집될 수 있습니다.",
                "사이트 최상위의 robots.txt를 열어 아래 오류 줄을 고쳐 주세요.",
                "User-agent: *\nDisallow: /admin/");
        put("hreflang", "다국어 페이지 설정이 잘못됨", "다국어 연결(hreflang) 고치기", PUB,
                "언어별 페이지 연결 설정이 틀려 검색엔진이 언어를 잘못 판단할 수 있습니다.",
                "hreflang 값을 ko, en처럼 표준 코드로 써 주세요.", null);
    }

    private RuleGuide() {
    }

    public static Guide find(String ruleId) {
        return GUIDES.get(ruleId);
    }
}
