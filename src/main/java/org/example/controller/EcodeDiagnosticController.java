package org.example.controller;

import org.example.service.EcodeDiagnosticService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/ecode")
public class EcodeDiagnosticController {

    private static final Logger log = LoggerFactory.getLogger(EcodeDiagnosticController.class);
    private final EcodeDiagnosticService service;

    public EcodeDiagnosticController(EcodeDiagnosticService service) { this.service = service; }

    /** 通过追溯码(20位数字)诊断 */
    @GetMapping("/diagnose/by-code")
    public Map<String, Object> diagnoseByCode(
            @RequestParam String ecode,
            @RequestParam long placepointid) {
        long t0 = System.currentTimeMillis();
        try {
            log.info("==> GET /api/ecode/diagnose/by-code ecode={} placepointid={}", ecode, placepointid);
            Map<String, Object> result = service.diagnoseByEcode(ecode, placepointid);
            log.info("<== GET /api/ecode/diagnose/by-code ecode={} placepointid={} cost={}ms blocked={}",
                    ecode, placepointid, System.currentTimeMillis() - t0, result.get("blocked"));
            return result;
        } catch (Exception e) {
            log.error("!!! diagnoseByCode 异常 ecode={} placepointid={}", ecode, placepointid, e);
            return Map.of("error", e.getClass().getName() + ": " + e.getMessage(),
                "ecode", ecode, "placepointid", placepointid, "cost", System.currentTimeMillis() - t0);
        }
    }

    /** 诊断单条明细 */
    @GetMapping("/diagnose")
    public Map<String, Object> diagnose(
            @RequestParam long placepointid,
            @RequestParam long rsadtlid) {
        long t0 = System.currentTimeMillis();
        try {
            log.info("==> GET /api/ecode/diagnose placepointid={} rsadtlid={}", placepointid, rsadtlid);
            Map<String, Object> result = service.diagnoseDetail(placepointid, rsadtlid);
            log.info("<== GET /api/ecode/diagnose placepointid={} rsadtlid={} cost={}ms blocked={}",
                    placepointid, rsadtlid, System.currentTimeMillis() - t0, result.get("blocked"));
            return result;
        } catch (Exception e) {
            log.error("!!! diagnose 异常 placepointid={} rsadtlid={}", placepointid, rsadtlid, e);
            return Map.of("error", e.getClass().getName() + ": " + e.getMessage(),
                "placepointid", placepointid, "rsadtlid", rsadtlid);
        }
    }

    /** 批量诊断 */
    @GetMapping("/diagnose/batch")
    public Map<String, Object> diagnoseBatch(
            @RequestParam long placepointid,
            @RequestParam String beginTime,
            @RequestParam String endTime) {
        long t0 = System.currentTimeMillis();
        try {
            log.info("==> GET /api/ecode/diagnose/batch placepointid={} beginTime={} endTime={}",
                    placepointid, beginTime, endTime);
            Map<String, Object> result = service.diagnoseBatch(placepointid, beginTime, endTime);
            log.info("<== GET /api/ecode/diagnose/batch placepointid={} cost={}ms unsentCount={}",
                    placepointid, System.currentTimeMillis() - t0, result.get("unsentCount"));
            return result;
        } catch (Exception e) {
            log.error("!!! diagnoseBatch 异常 placepointid={} beginTime={} endTime={}",
                    placepointid, beginTime, endTime, e);
            return Map.of("error", e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /** 上传数据概览 */
    @GetMapping("/diagnose/stats")
    public Map<String, Object> diagnoseStats(
            @RequestParam long placepointid,
            @RequestParam String beginTime,
            @RequestParam String endTime) {
        long t0 = System.currentTimeMillis();
        try {
            log.info("==> GET /api/ecode/diagnose/stats placepointid={} beginTime={} endTime={}",
                    placepointid, beginTime, endTime);
            Map<String, Object> result = service.diagnoseUploadStats(placepointid, beginTime, endTime);
            log.info("<== GET /api/ecode/diagnose/stats placepointid={} cost={}ms uploadRate={}",
                    placepointid, System.currentTimeMillis() - t0, result.get("uploadRate"));
            return result;
        } catch (Exception e) {
            log.error("!!! diagnoseStats 异常 placepointid={} beginTime={} endTime={}",
                    placepointid, beginTime, endTime, e);
            return Map.of("error", e.getClass().getName() + ": " + e.getMessage());
        }
    }
}
