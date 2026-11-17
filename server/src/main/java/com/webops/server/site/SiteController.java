package com.webops.server.site;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.webops.server.config.WebOpsProperties;
import com.webops.server.scan.InvalidTargetException;
import com.webops.server.scan.ScanJob;
import com.webops.server.store.ScanStore;
import com.webops.server.store.SiteEntity;

/**
 * 정기 검사 화면
 *   GET  /sites               정기 검사 대상 목록 + 등록
 *   POST /sites               등록
 *   GET  /sites/{id}          품질 추이 그래프 · 검사 기록 · 알림 기록 · 설정
 *   POST /sites/{id}          설정 바꾸기
 *   POST /sites/{id}/run      지금 검사
 *   POST /sites/{id}/toggle   정기 검사 멈춤 / 다시 시작
 *   POST /sites/{id}/delete   삭제 (지난 검사 기록은 남음)
 */
@Controller
public class SiteController {

    private final SiteService siteService;
    private final ScanStore store;
    private final int maxPages;

    public SiteController(SiteService siteService, ScanStore store, WebOpsProperties properties) {
        this.siteService = siteService;
        this.store = store;
        this.maxPages = Math.max(1, properties.scan().maxPages());
    }

    @GetMapping("/sites")
    public String list(Model model) {
        java.util.List<SiteEntity> all = siteService.list();
        java.util.Map<Long, com.webops.server.store.ScanJobEntity> latest = new java.util.HashMap<>();
        for (SiteEntity s : all) store.latestOf(s.getSiteId()).ifPresent(j -> latest.put(s.getSiteId(), j));
        model.addAttribute("sites", all);
        model.addAttribute("latest", latest);
        model.addAttribute("maxPages", maxPages);
        return "sites";
    }

    @PostMapping("/sites")
    public String create(@RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String url,
                         @RequestParam(defaultValue = "MAIN") String scope,
                         @RequestParam(defaultValue = "LATEST") String posts,
                         @RequestParam(defaultValue = "false") boolean lighthouse,
                         @RequestParam(defaultValue = "DAILY") String schedule,
                         @RequestParam(defaultValue = "9") int hour,
                         @RequestParam(defaultValue = "1") int dow,
                         @RequestParam(defaultValue = "") String email,
                         @RequestParam(defaultValue = "false") boolean runNow,
                         Model model, RedirectAttributes redirect) {
        SiteService.SiteForm form = new SiteService.SiteForm(name, url, scope, "ALL".equalsIgnoreCase(posts), lighthouse, schedule, hour, dow, email, runNow);
        try {
            SiteEntity site = siteService.create(form);
            redirect.addFlashAttribute("notice", runNow
                    ? "등록했습니다. 첫 검사를 곧 시작합니다 (30초 안에). 이 첫 검사가 앞으로 비교·추이의 기준이 됩니다."
                    : "등록했습니다.");
            return "redirect:/sites/" + site.getSiteId();
        } catch (InvalidTargetException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", SiteFormValues.of(form));
            return list(model);
        }
    }

    @GetMapping("/sites/{id:\\d+}")
    public String detail(@PathVariable Long id, Model model) {
        SiteEntity site = siteService.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("site", site);
        model.addAttribute("trend", TrendView.of(store.historyOf(id)));
        model.addAttribute("alerts", siteService.alertsOf(id, 20));
        model.addAttribute("formValues", SiteFormValues.of(site));
        model.addAttribute("maxPages", maxPages);
        return "site";
    }

    @PostMapping("/sites/{id:\\d+}")
    public String update(@PathVariable Long id,
                         @RequestParam(defaultValue = "") String name,
                         @RequestParam(defaultValue = "") String url,
                         @RequestParam(defaultValue = "MAIN") String scope,
                         @RequestParam(defaultValue = "LATEST") String posts,
                         @RequestParam(defaultValue = "false") boolean lighthouse,
                         @RequestParam(defaultValue = "NONE") String schedule,
                         @RequestParam(defaultValue = "9") int hour,
                         @RequestParam(defaultValue = "1") int dow,
                         @RequestParam(defaultValue = "") String email,
                         Model model, RedirectAttributes redirect) {
        try {
            siteService.update(id, new SiteService.SiteForm(name, url, scope, "ALL".equalsIgnoreCase(posts), lighthouse, schedule, hour, dow, email, false));
            redirect.addFlashAttribute("notice", "설정을 저장했습니다.");
            return "redirect:/sites/" + id;
        } catch (InvalidTargetException e) {
            model.addAttribute("error", e.getMessage());
            return detail(id, model);
        }
    }

    @PostMapping("/sites/{id:\\d+}/run")
    public String run(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            ScanJob job = siteService.runNow(id);
            return "redirect:/scans/" + job.getId();
        } catch (InvalidTargetException e) {
            redirect.addFlashAttribute("error", e.getMessage());
            return "redirect:/sites/" + id;
        }
    }

    @PostMapping("/sites/{id:\\d+}/toggle")
    public String toggle(@PathVariable Long id, @RequestParam boolean enabled, RedirectAttributes redirect) {
        siteService.setEnabled(id, enabled);
        redirect.addFlashAttribute("notice", enabled ? "정기 검사를 다시 시작했습니다." : "정기 검사를 멈췄습니다.");
        return "redirect:/sites/" + id;
    }

    @PostMapping("/sites/{id:\\d+}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        siteService.delete(id);
        redirect.addFlashAttribute("notice", "삭제했습니다. 지난 검사 기록은 '최근 검사'에 남아 있습니다.");
        return "redirect:/sites";
    }
}
