package com.webops.server.web;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import com.webops.server.config.WebOpsProperties;
import com.webops.server.scan.InvalidTargetException;
import com.webops.server.scan.ScanJob;
import com.webops.server.scan.ScanScope;
import com.webops.server.scan.ScanService;
import com.webops.server.scan.ScanStatus;
import com.webops.server.scan.UrlGuard;
import com.webops.server.store.ScanStore;
import com.webops.server.store.SiteRepository;

/**
 * 화면 흐름
 *   GET  /                    URL 입력 + 최근 검사 목록
 *   POST /scans               검사 시작 → /scans/{id}로 이동 (pages: 1이면 한 페이지, 2 이상이면 사이트 전체)
 *   GET  /scans/{id}          진행 중이면 진행 화면, 끝났으면 결과 화면
 *   GET  /api/scans/{id}      진행 상태(JSON) - 진행 화면이 1.5초마다 확인
 *   GET  /scans/{id}/report.json  결과 원본 JSON 내려받기
 *   GET  /scans/{id}/shots/{name} 문제 위치 캡처 이미지
 *   GET  /scans/{id}/print    인쇄용 보고서 화면 (A4)
 *   GET  /scans/{id}/report.pdf   인쇄용 보고서를 PDF로 내려받기
 */
@Controller
public class ScanController {

    private static final Logger log = LoggerFactory.getLogger(ScanController.class);
    private static final String JOB_ID_PATTERN = "[A-Za-z0-9-]{1,64}";

    private final ScanService scanService;
    private final UrlGuard urlGuard;
    private final Environment environment;
    private final int maxPages;
    private final ScanStore store;
    private final SiteRepository sites;

    public ScanController(ScanService scanService, UrlGuard urlGuard, Environment environment, WebOpsProperties properties,
                          ScanStore store, SiteRepository sites) {
        this.store = store;
        this.sites = sites;
        this.scanService = scanService;
        this.urlGuard = urlGuard;
        this.environment = environment;
        this.maxPages = Math.max(1, properties.scan().maxPages());
    }

    /** 첫 화면 최근 검사 카드: 한 쪽에 12개(한 줄 4개 × 3줄) */
    private static final int RECENT_PER_PAGE = 12;

    @GetMapping("/")
    public String index(@RequestParam(defaultValue = "1") int page, Model model) {
        addRecent(page, model);
        model.addAttribute("maxPages", maxPages);
        return "index";
    }

    /** 최근 검사 카드 + 페이지 번호 (page는 1부터, 범위를 벗어나면 맞춰 줌) */
    private void addRecent(int page, Model model) {
        org.springframework.data.domain.Page<ScanJob> result = scanService.recentPage(page - 1, RECENT_PER_PAGE);
        if (result.getTotalPages() > 0 && page > result.getTotalPages()) {
            result = scanService.recentPage(result.getTotalPages() - 1, RECENT_PER_PAGE);
        }
        int current = result.getNumber() + 1;
        int total = Math.max(1, result.getTotalPages());
        // 페이지 번호는 최대 5개: 지금 쪽을 가운데에
        int from = Math.max(1, Math.min(current - 2, total - 4));
        int to = Math.min(total, from + 4);
        model.addAttribute("recent", result.getContent().stream().map(RecentCard::of).toList());
        model.addAttribute("recentTotal", result.getTotalElements());
        model.addAttribute("recentPage", current);
        model.addAttribute("recentPages", total);
        model.addAttribute("recentNumbers", java.util.stream.IntStream.rangeClosed(from, to).boxed().toList());
    }

    @PostMapping("/scans")
    /**
     * 검사 시작. scope: MAIN(메인 페이지만) / PAGE(입력한 현재 페이지) / SITE(웹사이트 전체 점검)
     * 메인 페이지만이면 넣은 주소의 경로를 버리고 사이트 첫 화면을 검사한다.
     * posts(상세 옵션, 웹사이트 전체 점검에만): LATEST = 게시판 글처럼 모양이 같은 페이지는 최신 1개만(기본), ALL = 모두
     */
    public String start(@RequestParam(defaultValue = "") String url,
                        @RequestParam(defaultValue = "false") boolean lighthouse,
                        @RequestParam(defaultValue = "MAIN") String scope,
                        @RequestParam(defaultValue = "LATEST") String posts,
                        Model model) {
        ScanScope range = ScanScope.from(scope);
        try {
            String target = range.targetUrl(urlGuard.check(url));
            ScanJob job = scanService.start(target, lighthouse, range.maxPages(maxPages), "ALL".equalsIgnoreCase(posts));
            return "redirect:/scans/" + job.getId();
        } catch (InvalidTargetException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("url", url);
            model.addAttribute("lighthouse", lighthouse);
            model.addAttribute("scope", range.name());
            model.addAttribute("posts", "ALL".equalsIgnoreCase(posts) ? "ALL" : "LATEST");
            model.addAttribute("maxPages", maxPages);
            addRecent(1, model);
            return "index";
        }
    }

    @GetMapping("/scans/{id:" + JOB_ID_PATTERN + "}")
    public String view(@PathVariable String id, Model model) {
        ScanJob job = findJob(id);
        model.addAttribute("job", job);
        if (job.getStatus() != ScanStatus.DONE) {
            return "progress";
        }
        addView(job, model);
        return "result";
    }

    /** 최근 검사 카드의 '삭제'. 지운 뒤 보던 쪽으로 돌아간다. */
    @PostMapping("/scans/{id:" + JOB_ID_PATTERN + "}/delete")
    public String delete(@PathVariable String id, @RequestParam(defaultValue = "1") int page,
                         org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        try {
            scanService.delete(id);
            redirect.addFlashAttribute("recentNotice", "검사 기록을 삭제했습니다.");
        } catch (ScanStore.CannotDeleteException e) {
            redirect.addFlashAttribute("recentError", e.getMessage());
        }
        return "redirect:/?page=" + Math.max(1, page) + "#recent";
    }

    /** 인쇄용 보고서 화면. 브라우저에서 바로 인쇄하거나, PDF 내려받기가 이 화면을 PDF로 만든다. */
    @GetMapping("/scans/{id:" + JOB_ID_PATTERN + "}/print")
    public String print(@PathVariable String id, Model model) {
        ScanJob job = findDoneJob(id);
        model.addAttribute("job", job);
        addView(job, model);
        return "report";
    }

    @GetMapping(value = "/api/scans/{id:" + JOB_ID_PATTERN + "}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public Map<String, Object> status(@PathVariable String id) {
        ScanJob job = findJob(id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", job.getId());
        body.put("status", job.getStatus().name());
        body.put("statusLabel", job.getStatus().getLabel());
        body.put("progress", job.getProgress());
        // 진행 막대: 진행률(0~100), 끝을 모르는 단계인지(페이지 찾는 중), 막대 옆 한 줄
        body.put("percent", job.getPercent());
        body.put("indeterminate", job.isIndeterminate());
        body.put("stepText", job.getStepText());
        body.put("finished", job.isFinished());
        return body;
    }

    @GetMapping("/scans/{id:" + JOB_ID_PATTERN + "}/report.json")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id) {
        ScanJob job = findJob(id);
        if (job.getReportFile() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("webops-" + job.getId() + ".json").build().toString())
                .body(new FileSystemResource(job.getReportFile()));
    }

    @GetMapping("/scans/{id:" + JOB_ID_PATTERN + "}/shots/{name:[A-Za-z0-9-]{1,40}\\.jpg}")
    public ResponseEntity<FileSystemResource> shot(@PathVariable String id, @PathVariable String name) {
        ScanJob job = findJob(id);
        Path file = scanService.shotFile(job, name)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(new FileSystemResource(file));
    }

    @GetMapping("/scans/{id:" + JOB_ID_PATTERN + "}/report.pdf")
    public ResponseEntity<FileSystemResource> pdf(@PathVariable String id) {
        ScanJob job = findDoneJob(id);
        // 항상 이 서버 자신(127.0.0.1)의 인쇄용 화면을 연다 (요청 헤더의 주소는 쓰지 않음)
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8080"));
        String printUrl = "http://127.0.0.1:" + port + "/scans/" + job.getId() + "/print";
        try {
            Path file = scanService.renderPdf(job, printUrl);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            ContentDisposition.attachment().filename("webops-report-" + job.getId() + ".pdf").build().toString())
                    .body(new FileSystemResource(file));
        } catch (IOException e) {
            log.warn("PDF 만들기 실패: {}", job.getId(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    private ScanJob findJob(String id) {
        return scanService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검사 기록을 찾을 수 없습니다."));
    }

    private ScanJob findDoneJob(String id) {
        ScanJob job = findJob(id);
        if (job.getStatus() != ScanStatus.DONE) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 끝나지 않은 검사입니다.");
        }
        return job;
    }

    /** 결과 화면 데이터 + 지난 검사와 비교 + 정기 검사 대상이면 그 사이트 */
    private void addView(ScanJob job, Model model) {
        model.addAttribute("view", ResultView.of(job.getReport(), store.compare(job.getId()).orElse(null)));
        if (job.getSiteId() != null) {
            sites.findById(job.getSiteId()).ifPresent(site -> model.addAttribute("site", site));
        }
    }
}
