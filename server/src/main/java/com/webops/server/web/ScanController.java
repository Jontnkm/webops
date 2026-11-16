package com.webops.server.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
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

import com.webops.server.scan.InvalidTargetException;
import com.webops.server.scan.ScanJob;
import com.webops.server.scan.ScanService;
import com.webops.server.scan.ScanStatus;
import com.webops.server.scan.UrlGuard;

/**
 * 화면 흐름
 *   GET  /                    URL 입력 + 최근 검사 목록
 *   POST /scans               검사 시작 → /scans/{id}로 이동
 *   GET  /scans/{id}          진행 중이면 진행 화면, 끝났으면 결과 화면
 *   GET  /api/scans/{id}      진행 상태(JSON) - 진행 화면이 1.5초마다 확인
 *   GET  /scans/{id}/report.json  결과 원본 JSON 내려받기
 */
@Controller
public class ScanController {

    private static final String JOB_ID_PATTERN = "[A-Za-z0-9-]{1,64}";

    private final ScanService scanService;
    private final UrlGuard urlGuard;

    public ScanController(ScanService scanService, UrlGuard urlGuard) {
        this.scanService = scanService;
        this.urlGuard = urlGuard;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("recent", scanService.recent(10));
        return "index";
    }

    @PostMapping("/scans")
    public String start(@RequestParam(defaultValue = "") String url,
                        @RequestParam(defaultValue = "false") boolean lighthouse,
                        Model model) {
        try {
            String target = urlGuard.check(url);
            ScanJob job = scanService.start(target, lighthouse);
            return "redirect:/scans/" + job.getId();
        } catch (InvalidTargetException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("url", url);
            model.addAttribute("lighthouse", lighthouse);
            model.addAttribute("recent", scanService.recent(10));
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
        model.addAttribute("view", ResultView.of(job.getReport()));
        return "result";
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

    private ScanJob findJob(String id) {
        return scanService.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "검사 기록을 찾을 수 없습니다."));
    }
}
