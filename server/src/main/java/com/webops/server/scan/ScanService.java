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
import org.springframework.stereotype.Service;

import com.webops.server.config.WebOpsProperties;

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

    private final Map<String, ScanJob> jobs = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper jsonMapper;
    private final WebOpsProperties.Runner runner;
    private final Path runnerDir;
    private final Path outputDir;
    private final ExecutorService executor;

    public ScanService(WebOpsProperties properties, JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        this.runner = properties.runner();
        this.runnerDir = Path.of(runner.dir()).toAbsolutePath().normalize();
        this.outputDir = runnerDir.resolve("output");
        this.executor = Executors.newFixedThreadPool(Math.max(1, runner.maxConcurrent()));
        log.info("검사 실행기 위치: {}", runnerDir);
    }

    public ScanJob start(String url, boolean lighthouse) {
        pruneOldJobs();
        ScanJob job = new ScanJob(newJobId(), url, lighthouse);
        jobs.put(job.getId(), job);
        executor.submit(() -> run(job));
        return job;
    }

    public Optional<ScanJob> find(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    public List<ScanJob> recent(int limit) {
        return jobs.values().stream()
                .sorted(Comparator.comparing(ScanJob::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }

    private void run(ScanJob job) {
        job.markRunning();
        List<String> command = new ArrayList<>(List.of(
                runner.node(), "src/scan.js", job.getUrl(),
                "--job-id", job.getId(),
                "--out", outputDir.toString()));
        if (!job.isLighthouse()) {
            command.add("--no-lighthouse");
        }

        Deque<String> lastLines = new ArrayDeque<>();
        try {
            Process process = new ProcessBuilder(command)
                    .directory(runnerDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            Thread reader = new Thread(() -> readOutput(process, job, lastLines), "scan-output-" + job.getId());
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(runner.timeoutSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                job.fail("검사 시간이 " + runner.timeoutSeconds() + "초를 넘어 중단했습니다.");
                return;
            }
            reader.join(2000);

            int exitCode = process.exitValue();
            Path file = outputDir.resolve("scan-" + job.getId() + ".json");
            // 0 = 정상, 2 = 페이지 접속 불가(결과 파일은 있음)
            if ((exitCode == 0 || exitCode == 2) && Files.exists(file)) {
                ScanReport report = jsonMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), ScanReport.class);
                job.complete(report, file);
            } else {
                String detail;
                synchronized (lastLines) {
                    detail = String.join(" / ", lastLines);
                }
                job.fail("검사 실행기가 오류로 끝났습니다 (종료 코드 " + exitCode + "). " + detail);
            }
        } catch (IOException e) {
            job.fail("검사 실행기를 시작하지 못했습니다: " + e.getMessage()
                    + " (Node.js 설치와 webops.runner.dir 설정을 확인하세요)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.fail("서버가 종료되어 검사를 중단했습니다.");
        } catch (RuntimeException e) {
            log.warn("검사 결과 처리 실패: {}", job.getId(), e);
            job.fail("검사 결과 파일을 읽지 못했습니다: " + e.getMessage());
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
