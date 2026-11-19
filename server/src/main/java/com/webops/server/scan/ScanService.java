package com.webops.server.scan;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.webops.server.config.WebOpsProperties;
import com.webops.server.store.ScanJobEntity;
import com.webops.server.store.ScanStore;

import tools.jackson.databind.json.JsonMapper;

/**
 * 검사 작업을 만들고, 백그라운드에서 검사 실행기(Node.js)를 불러 결과를 받는다.
 *
 * 흐름: start() → 작업 등록(대기 중) → 스레드에서 "node src/scan.js <URL> --job-id <ID>" 실행
 *       → 실행기가 찍는 진행 메시지("  - 링크 확인 중")를 화면용 progress로 반영
 *       → 끝나면 output/scan-<ID>.json을 읽어 ScanReport로 변환
 */
@Service
public class ScanService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);
    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int MAX_JOBS_IN_MEMORY = 100;

    /** 진행 중인 검사 + 최근에 연 검사 (나머지는 DB에서 읽는다) */
    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper jsonMapper;
    private final ScanStore store;
    private final ApplicationEventPublisher events;
    private final WebOpsProperties.Runner runner;
    private final Path runnerDir;
    private final Path outputDir;
    private final ExecutorService executor;

    public ScanService(WebOpsProperties properties, JsonMapper jsonMapper, ScanStore store, ApplicationEventPublisher events) {
        this.jsonMapper = jsonMapper;
        this.store = store;
        this.events = events;
        this.runner = properties.runner();
        this.runnerDir = Path.of(runner.dir()).toAbsolutePath().normalize();
        this.outputDir = runnerDir.resolve("output");
        this.executor = Executors.newFixedThreadPool(Math.max(1, runner.maxConcurrent()));
        log.info("검사 실행기 위치: {}", runnerDir);
    }

    /** 서버를 켜면: 꺼지기 전에 진행 중이던 검사를 실패로 정리 */
    @EventListener(ApplicationReadyEvent.class)
    public void cleanUpInterrupted() {
        int n = store.failInterrupted();
        if (n > 0) log.info("중단된 검사 {}건을 실패로 정리했습니다.", n);
    }

    /**
     * 직접 검사. @param maxPages 1이면 한 페이지, 2 이상이면 같은 사이트 링크를 따라가며 최대 그 수만큼 검사
     * @param allPosts 게시판 글처럼 모양이 같은 페이지를 모두 검사할지 (false = 최신 1개만)
     */
    public ScanJob start(String url, boolean lighthouse, int maxPages, boolean allPosts) {
        return start(url, lighthouse, maxPages, allPosts, false, null, ScanJob.MANUAL);
    }

    /** @param publicCheck 공공기관 검사(필수 요소 점검 포함)인지. false = 일반 검사 */
    public ScanJob start(String url, boolean lighthouse, int maxPages, boolean allPosts, boolean publicCheck) {
        return start(url, lighthouse, maxPages, allPosts, publicCheck, null, ScanJob.MANUAL);
    }

    public ScanJob start(String url, boolean lighthouse, int maxPages, boolean allPosts, Long siteId, String trigger) {
        return start(url, lighthouse, maxPages, allPosts, false, siteId, trigger);
    }

    /** @param siteId 정기 검사 대상에서 나온 검사면 그 번호 (알림·추이 그래프에 쓰임) */
    public ScanJob start(String url, boolean lighthouse, int maxPages, boolean allPosts, boolean publicCheck, Long siteId, String trigger) {
        return start(url, lighthouse, maxPages, allPosts, publicCheck, ScanType.ALL, null, siteId, trigger);
    }

    /**
     * @param type      점검 종류 (종합점검 외에는 웹사이트 전체 페이지)
     * @param mobileUrl 모바일환경 점검의 모바일 전용 주소 (없으면 null)
     */
    public ScanJob start(String url, boolean lighthouse, int maxPages, boolean allPosts, boolean publicCheck,
                         ScanType type, String mobileUrl, Long siteId, String trigger) {
        pruneOldJobs();
        ScanJob job = new ScanJob(newJobId(), url, lighthouse, Math.max(1, maxPages), siteId, trigger);
        job.setAllPosts(allPosts && job.isSiteScan());
        ScanJobEntity entity = new ScanJobEntity(job.getId(), siteId, url, trigger, job.getMaxPages(), lighthouse, job.getCreatedAt());
        entity.setAllPosts(job.isAllPosts());
        job.setPublicCheck(publicCheck);
        entity.setPublicCheck(publicCheck);
        job.setType(type);
        entity.setCheckType(job.getType().name());
        String mobile = job.getType() == ScanType.MOBILE ? mobileUrl : null;
        job.setMobileUrl(mobile);
        entity.setMobileUrl(mobile);
        store.create(entity);
        jobs.put(job.getId(), job);
        executor.submit(() -> run(job));
        return job;
    }

    /** 메모리에 없으면 DB에서 읽어 되살린다 (서버를 다시 켜도 지난 결과를 볼 수 있음) */
    public Optional<ScanJob> find(String id) {
        ScanJob live = jobs.get(id);
        if (live != null) return Optional.of(live);
        return store.find(id).map(e -> {
            ScanReport report = null;
            if (ScanStore.DONE.equals(e.getStatus()) && e.getReportJson() != null) {
                report = jsonMapper.readValue(e.getReportJson(), ScanReport.class);
            }
            ScanJob job = ScanJob.restore(e, report);
            pruneOldJobs();
            jobs.put(id, job);
            return job;
        });
    }

    /** 최근 검사 목록 (DB 기준, 진행 중인 검사는 메모리의 진행 상태로) */
    public List<ScanJob> recent(int limit) {
        return store.recent(limit).stream()
                .map(e -> {
                    ScanJob live = jobs.get(e.getJobId());
                    return live != null ? live : ScanJob.restore(e, null);
                })
                .toList();
    }

    /**
     * 검사 기록 삭제: DB 기록(검사·페이지별 결과·발견 항목)을 지우고,
     * 결과 JSON · 위치 캡처 폴더 · PDF 파일도 함께 정리한다. 검사 중이면 ScanStore.CannotDeleteException.
     */
    public void delete(String id) {
        ScanJob live = jobs.get(id);
        if (live != null && !live.isFinished()) {
            throw new ScanStore.CannotDeleteException("검사가 끝난 뒤에 지울 수 있습니다.");
        }
        store.delete(id);
        jobs.remove(id);
        // 파일은 DB를 지운 뒤 정리한다 (못 지워도 기록 삭제는 유지). 결과 폴더 안의 정해진 이름만 지운다.
        deleteQuietly(outputDir.resolve("scan-" + id + ".json"));
        deleteQuietly(outputDir.resolve("report-" + id + ".pdf"));
        deleteQuietly(outputDir.resolve("a11y-" + id + ".pdf"));
        Path shots = outputDir.resolve("shots").resolve(id).normalize();
        if (shots.startsWith(outputDir.resolve("shots")) && Files.isDirectory(shots)) {
            try (var files = Files.list(shots)) {
                files.forEach(ScanService::deleteQuietly);
            } catch (IOException e) {
                log.warn("캡처 폴더 정리 실패: {}", shots, e);
            }
            deleteQuietly(shots);
        }
        log.info("검사 기록 삭제: {}", id);
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("파일 삭제 실패: {}", file, e);
        }
    }

    /** 최근 검사 한 쪽 (page는 0부터). 진행 중인 검사는 메모리의 진행 상태로 */
    public long recentCount() {
        return store.count();
    }

    public org.springframework.data.domain.Page<ScanJob> recentPage(int page, int size, String query) {
        return store.recentPage(page, size, query).map(e -> {
            ScanJob live = jobs.get(e.getJobId());
            return live != null ? live : ScanJob.restore(e, null);
        });
    }

    private void run(ScanJob job) {
        try {
            execute(job);
        } finally {
            // 알림 등 후속 처리 (성공·실패 모두)
            if (job.isFinished()) {
                try {
                    events.publishEvent(new ScanFinishedEvent(job));
                } catch (RuntimeException e) {
                    log.warn("검사 후속 처리 실패: {}", job.getId(), e);
                }
            }
        }
    }

    private void fail(ScanJob job, String message) {
        job.fail(message);
        try {
            store.saveFailed(job.getId(), message);
        } catch (RuntimeException e) {
            log.warn("실패 기록 저장 실패: {}", job.getId(), e);
        }
    }

    private void execute(ScanJob job) {
        job.markRunning();
        store.markRunning(job.getId());
        List<String> command = new ArrayList<>(List.of(
                runner.node(), "src/scan.js", job.getUrl(),
                "--job-id", job.getId(),
                "--out", outputDir.toString()));
        if (!job.isLighthouse()) {
            command.add("--no-lighthouse");
        }
        if (job.isSiteScan()) {
            command.add("--pages");
            command.add(String.valueOf(job.getMaxPages()));
            // 여러 페이지는 탭 여러 개로 나눠 동시에 검사 (시간 단축)
            command.add("--concurrency");
            command.add(String.valueOf(Math.min(5, Math.max(1, runner.siteConcurrency()))));
            // 게시판 글처럼 모양이 같은 페이지: 모두 / 최신 1개만
            command.add("--posts");
            command.add(job.isAllPosts() ? "all" : "latest");
        }
        // 점검 종류 (종합점검이 아니면 그 분야만) · 모바일 전용 주소
        command.add("--check");
        command.add(job.getType().getCli());
        if (job.getMobileUrl() != null) {
            command.add("--mobile-url");
            command.add(job.getMobileUrl());
        }
        // 공공기관 검사: 필수 요소(개인정보처리방침 · 이용약관 · 저작권 표시)도 점검
        if (job.isPublicCheck()) {
            command.add("--public");
        }
        // 페이지가 늘어나는 만큼 제한 시간도 늘린다
        // 로딩속도 · SEO 점검은 페이지마다 Lighthouse를 한 번 더 돌리므로 페이지당 시간을 넉넉히 (+60초)
        long perPage = runner.perPageSeconds() + (job.getType().isLighthouseAll() ? 60 : 0);
        long timeoutSeconds = runner.timeoutSeconds() + (long) (job.getMaxPages() - 1) * perPage;

        Deque<String> lastLines = new ArrayDeque<>();
        try {
            Process process = new ProcessBuilder(command)
                    .directory(runnerDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            Thread reader = new Thread(() -> readOutput(process, job, lastLines), "scan-output-" + job.getId());
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // 실행기가 띄운 브라우저까지 함께 끈다
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                fail(job, "검사 시간이 " + timeoutSeconds + "초를 넘어 중단했습니다.");
                return;
            }
            reader.join(2000);

            int exitCode = process.exitValue();
            Path file = outputDir.resolve("scan-" + job.getId() + ".json");
            // 0 = 정상, 2 = 페이지 접속 불가(결과 파일은 있음)
            if ((exitCode == 0 || exitCode == 2) && Files.exists(file)) {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                ScanReport report = jsonMapper.readValue(json, ScanReport.class);
                store.saveDone(job.getId(), report, json, file.toString());
                job.complete(report, file);
            } else {
                String detail;
                synchronized (lastLines) {
                    detail = String.join(" / ", lastLines);
                }
                fail(job, "검사 실행기가 오류로 끝났습니다 (종료 코드 " + exitCode + "). " + detail);
            }
        } catch (IOException e) {
            fail(job, "검사 실행기를 시작하지 못했습니다: " + e.getMessage()
                    + " (Node.js 설치와 webops.runner.dir 설정을 확인하세요)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(job, "서버가 종료되어 검사를 중단했습니다.");
        } catch (RuntimeException e) {
            log.warn("검사 결과 처리 실패: {}", job.getId(), e);
            fail(job, "검사 결과를 저장하지 못했습니다: " + e.getMessage());
        }
    }

    /** 실행기 출력 중 "  - 진행 메시지" 줄을 화면에 보여 줄 진행 상태로 쓴다. */
    private void readOutput(Process process, ScanJob job, Deque<String> lastLines) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String text = line.strip();
                if (text.startsWith("- ")) {
                    job.updateProgress(text.substring(2));
                } else if (text.startsWith("% ")) {
                    // 진행 막대용 "% 단계 완료수 전체수" (화면에 보여 주는 마지막 줄에는 넣지 않음)
                    String[] parts = text.substring(2).split(" ");
                    if (parts.length == 3) {
                        try {
                            job.updateStep(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                        } catch (NumberFormatException ignored) {
                            // 형식이 다르면 무시
                        }
                    }
                    continue;
                }
                if (!text.isEmpty()) {
                    synchronized (lastLines) {
                        lastLines.addLast(text);
                        if (lastLines.size() > 3) {
                            lastLines.removeFirst();
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 프로세스가 끝나면 스트림이 닫힐 수 있다.
        }
    }

    private String newJobId() {
        byte[] bytes = new byte[2];
        random.nextBytes(bytes);
        return "J-" + LocalDateTime.now().format(ID_TIME) + "-" + HexFormat.of().formatHex(bytes);
    }

    /** 문제 위치 캡처 파일 (runner/output/shots/<작업ID>/<이름>). 이름은 영문·숫자·하이픈 + .jpg만 허용 */
    public Optional<Path> shotFile(ScanJob job, String name) {
        if (name == null || !name.matches("[A-Za-z0-9-]{1,40}\\.jpg")) {
            return Optional.empty();
        }
        Path file = outputDir.resolve("shots").resolve(job.getId()).resolve(name).normalize();
        return file.startsWith(outputDir) && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    /**
     * 인쇄용 보고서 화면(printUrl)을 실행기로 열어 PDF로 만든다. 한 번 만든 PDF는 다시 쓴다.
     * printUrl은 이 서버(127.0.0.1)의 주소만 넘긴다.
     */
    public Path renderPdf(ScanJob job, String printUrl) throws IOException {
        return renderPdf(job, printUrl, "report");
    }

    /** @param kind 파일 이름 앞부분 (report = 전체 보고서, a11y = 접근성 보고서). 한 번 만든 파일은 다시 쓴다 */
    public Path renderPdf(ScanJob job, String printUrl, String kind) throws IOException {
        Path pdf = outputDir.resolve(kind + "-" + job.getId() + ".pdf");
        synchronized (job) {
            if (Files.exists(pdf) && Files.size(pdf) > 0) {
                return pdf;
            }
            Path temp = outputDir.resolve(kind + "-" + job.getId() + ".tmp.pdf");
            Process process = new ProcessBuilder(runner.node(), "src/pdf.js", printUrl, temp.toString())
                    .directory(runnerDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output;
            try {
                if (!process.waitFor(runner.pdfTimeoutSeconds(), TimeUnit.SECONDS)) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    throw new IOException("PDF 만들기가 " + runner.pdfTimeoutSeconds() + "초를 넘어 중단했습니다.");
                }
                output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("PDF 만들기가 중단되었습니다.");
            }
            if (process.exitValue() != 0 || !Files.exists(temp)) {
                throw new IOException("PDF를 만들지 못했습니다: " + output);
            }
            Files.move(temp, pdf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return pdf;
        }
    }

    /** 메모리에 너무 많이 쌓이지 않도록 끝난 작업 중 오래된 것부터 정리 */
    private void pruneOldJobs() {
        if (jobs.size() < MAX_JOBS_IN_MEMORY) {
            return;
        }
        jobs.values().stream()
                .filter(ScanJob::isFinished)
                .sorted(Comparator.comparing(ScanJob::getCreatedAt))
                .limit(jobs.size() - MAX_JOBS_IN_MEMORY + 1L)
                .forEach(old -> jobs.remove(old.getId()));
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
